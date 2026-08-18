package com.oilquiz.app.ai.importing.v2;

import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.sqlite.db.SupportSQLiteDatabase;

import com.oilquiz.app.ai.importing.FieldMappingRegistry;
import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 离线题库批量导入全局调度核心（Java 调度层入口）。
 * <p>
 * 四层分层架构单向数据流：
 * <ol>
 *   <li>Java 全局调度层（本类）：Python 进程调用、专用 AI 引擎管理、工具调度、
 *       数据库读写、缓存持久化、断点进度保存、异常容错处理</li>
 *   <li>{@link ImportLlmEngine} 独立专用推理引擎</li>
 *   <li>Python 文件预处理层（{@link ImportPythonBridge}）</li>
 *   <li>SQLite 持久存储层（{@link ImportCsvIngestor} 自定义事务批量入库）</li>
 * </ol>
 */
public class ImportMain {

    private static final String TAG = "ImportMain";

    /** CSV 分片行数（超大题库分片防内存溢出） */
    private static final int CHUNK_ROWS = 3000;
    /** 智能填充单批题数（LLM 批量推理） */
    private static final int FILL_BATCH_SIZE = 10;

    /** PRAGMA 读取失败时的内置默认字段列表（兜底，流程不中断） */
    private static final String[] DEFAULT_COLUMNS = {
            "id", "questionText", "optionA", "optionB", "optionC", "optionD",
            "correctAnswer", "category", "difficulty", "explanation",
            "relatedQuestion", "questionType", "favorite", "createdAt", "updatedAt",
            "source", "tags", "points", "timeLimit", "status"
    };

    /** 下发给 Python 的标准化 CSV 候选列顺序（与真实表列取交集，动态适配表结构变化） */
    private static final String[] STD_COLUMN_PREFERENCE = {
            "questionText", "optionA", "optionB", "optionC", "optionD",
            "optionE", "optionF", "optionG", "optionH", "optionI", "optionJ",
            "optionK", "optionL", "correctAnswer", "answerText", "category",
            "difficulty", "explanation", "questionType", "source", "tags",
            "knowledgePoint", "subCategory", "hint", "analysis", "author",
            "relatedQuestion"
    };

    /** AI 可填充字段（填充引擎输出结构固定为 category/difficulty/explanation，
     * 下发前与真实表列取交集） */
    private static final String[] FILL_FIELD_CANDIDATES = {"questionType", "difficulty", "category", "explanation"};

    /** 虚拟映射字段：合并选项列（非数据库列，Python 解析层拆分到 optionA~L） */
    private static final String VIRTUAL_FIELD_OPTIONS_COMBINED = "optionsCombined";

    /** 导入事件回调 */
    public interface ImportListener {
        void onStage(String stage, String message);

        void onLog(String message);

        void onProgress(long current, long total, String detail);

        void onComplete(ImportSummary summary);

        void onError(String message);
    }

    /** 导入统计 */
    public static class ImportSummary {
        public long imported;
        public long duplicated;
        public long failed;
        public long totalRows;
        /** 映射来源：cache/ai/fallback/breakpoint */
        public String mappingSource = "ai";
        public boolean resumed;
        public long elapsedMs;
        /** 缺字段引导文案（非空时 UI 应展示，提示用户修复后重导） */
        public String issuesMessage;
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "import-main");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean cancelled = false;
    private ImportLlmEngine engine;
    /** 当前文件的字段映射（标准字段→源列名），fillMissingFields 据此判断源表是否有对应列 */
    private Map<String, String> currentMapping;
    /** 题库说明/模板说明提取的字段约定（映射推理时注入，帮助 AI 理解字段含义） */
    private volatile String docHint = null;
    /** Excel 用户选定工作表索引（-1=自动扫全部） */
    private volatile int excelSheetIndex = -1;
    /** 批量模式标志：缺字段报告由批量入口统一生成一次，避免逐文件重复扫描 */
    private volatile boolean batchMode = false;
    /** 本地 AI 引擎（可选，用于字段映射 + 智能填充） */
    private volatile com.oilquiz.app.ai.importing.AIImportOrchestrator localOrchestrator;

    public ImportMain(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 使用本地 AI 引擎的构造方式 */
    public ImportMain(Context context, com.oilquiz.app.ai.importing.AIImportOrchestrator orchestrator) {
        this.context = context.getApplicationContext();
        this.localOrchestrator = orchestrator;
    }

    /** 取消导入 */
    public void cancel() {
        cancelled = true;
    }

    /** 异步执行单文件导入（自动断点续导） */
    /**
     * 注入题库说明/模板说明的字段约定文本（映射推理时参考）。
     * 从 Excel 的"题库说明"等 sheet 提取：如"题干格式：...、答案：...、选项：A.xxx"，
     * 帮助 AI 字段映射理解文件列的真实含义，提升映射准确率。
     */
    public void setDocHint(String docHintText) {
        this.docHint = (docHintText == null || docHintText.trim().isEmpty())
                ? null : docHintText.trim();
    }

    /**
     * 设置 Excel 用户选定的工作表索引（Python 直接读取该 sheet）。
     * @param sheetIndex 工作表索引（0-based）；-1 或 <0 表示自动扫全部
     */
    public void setExcelSheetIndex(int sheetIndex) {
        this.excelSheetIndex = sheetIndex;
    }

    public void run(File sourceFile, ImportListener listener) {
        cancelled = false;
        batchMode = false;
        executor.execute(() -> {
            try {
                runSync(sourceFile, listener);
            } catch (Throwable t) {
                Log.e(TAG, "导入流程异常: " + t.getMessage(), t);
                emitError(listener, "导入流程异常: " + t.getMessage());
            }
        });
    }

    /**
     * 扫描公共 source 目录依次导入（结束时发出 all-done 阶段信号，供 UI 汇总展示）。
     * 后台线程执行 + 重置取消/批量标志：避免在调用线程（UI）同步阻塞，且上次取消后再次批量不空跑。
     */
    public void runAllFromSourceDir(ImportListener listener) {
        cancelled = false;
        batchMode = true;
        executor.execute(() -> {
            try {
                File dir = ImportDirs.sourceDir();
                File[] files = dir.listFiles();
                if (files == null || files.length == 0) {
                    emitError(listener, "源目录为空: " + dir.getAbsolutePath()
                            + "，请先将题库文件放入该目录");
                    return;
                }
                runAllFromSourceFilesInternalInner(java.util.Arrays.asList(files), listener);
            } finally {
                // 所有路径（正常/取消/空目录）都必须发 all-done，否则 UI 永远等不到批量收尾
                emitStage(listener, "all-done", "批量导入结束");
            }
        });
    }

    /** 批量导入指定文件列表（支持多文件） */
    public void runAllFromSourceFiles(java.util.List<File> files, ImportListener listener) {
        cancelled = false;
        batchMode = true;
        executor.execute(() -> {
            try {
                if (files == null || files.isEmpty()) {
                    emitError(listener, "文件列表为空");
                    return;
                }
                runAllFromSourceFilesInternalInner(files, listener);
            } finally {
                emitStage(listener, "all-done", "批量导入结束");
            }
        });
    }

    private void runAllFromSourceFilesInternalInner(java.util.List<File> files, ImportListener listener) {
        int matched = 0;
        for (File f : files) {
            if (cancelled) {
                emitLog(listener, "已取消，剩余文件未处理");
                break;
            }
            String lower = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".sql") || lower.endsWith(".db") || lower.endsWith(".sqlite")
                    || lower.endsWith(".sqlite3") || lower.endsWith(".db3")
                    || lower.endsWith(".xlsx")
                    || lower.endsWith(".xls") || lower.endsWith(".csv")
                    || lower.endsWith(".json") || lower.endsWith(".txt")) {
                matched++;
                emitStage(listener, "batch", "批量导入(" + matched + "): " + f.getName());
                try {
                    runSync(f, listener);
                } catch (Throwable t) {
                    emitError(listener, f.getName() + " 导入失败: " + t.getMessage());
                }
            }
        }
        if (matched == 0) {
            emitError(listener, "无可识别的题库文件(支持 csv/xlsx/xls/json/sql/db/txt)");
        } else {
            String issues = exportIssuesReport(listener);
            if (issues != null) {
                emitStage(listener, "issues", issues);
            }
        }
    }

    // ==================== 主流程 ====================

    private void runSync(File sourceFile, ImportListener listener) throws Exception {
        long startMs = System.currentTimeMillis();
        ImportSummary summary = new ImportSummary();

        if (sourceFile == null || !sourceFile.exists()) {
            emitError(listener, "源文件不存在");
            return;
        }
        emitStage(listener, "init", "初始化导入引擎: " + sourceFile.getName());

        // ========== 步骤0：初始化准备 ==========
        if (localOrchestrator != null) {
            // 携带外部编排器（用于模型模式/信息展示；映射与填充推理仍由 ImportLlmEngine 本地模型完成）
            emitLog(listener, "初始化导入引擎（本地模型推理）");
            engine = new ImportLlmEngine(context, localOrchestrator);
        } else {
            engine = new ImportLlmEngine(context);
        }
        engine.setListener(msg -> emitLog(listener, msg));
        ImportPythonBridge python = ImportPythonBridge.getInstance(context);
        if (!python.ensureReady()) {
            emitError(listener, "Python 预处理层初始化失败");
            return;
        }

        // 实时读取表结构与唯一指纹（读取失败 → 内置默认字段兜底）
        List<String> columns = readTableColumns();
        boolean metaFallback = columns.isEmpty();
        if (metaFallback) {
            columns = Arrays.asList(DEFAULT_COLUMNS);
            emitLog(listener, "数据库元数据读取失败，使用内置默认字段列表");
        }
        String tableCols = joinComma(columns);
        Set<String> legalFields = new HashSet<>(columns);
        // 虚拟字段：合并选项列（如"可选项"单列分号分隔），Python 侧解析时拆分到 optionA~L，
        // 加入合法集以免 AI 映射结果被硬过滤剔除
        legalFields.add(VIRTUAL_FIELD_OPTIONS_COMBINED);
        String tableFinger = fingerprint(tableCols);

        File sessionDir = ImportDirs.sessionDir(context, sourceFile.getName());

        // ========== 步骤1：缓存匹配判断 ==========
        ImportBreakpointStore.State bp = ImportBreakpointStore.load();
        boolean resumeParse = bp != null
                && sourceFile.getAbsolutePath().equals(bp.sourceFile)
                && ImportBreakpointStore.STAGE_PARSE.equals(bp.stage);
        boolean resumeIngest = bp != null
                && sourceFile.getAbsolutePath().equals(bp.sourceFile)
                && ImportBreakpointStore.STAGE_INGEST.equals(bp.stage);

        Map<String, String> mapping = null;
        JSONArray headersArr = null;

        if (resumeIngest) {
            // 入库断点恢复：直接进入步骤5
            summary.resumed = true;
            summary.mappingSource = "breakpoint";
            emitStage(listener, "resume", "检测到入库断点，跳过解析直接续导入库");
            List<File> chunks = splitChunkFiles(bp.csvChunks);
            // 分片部分丢失（用户清理 temp/磁盘异常）：显式报错并清断点，
            // 禁止静默带伤入库导致部分数据无声丢失
            int declared = bp.csvChunks == null ? 0
                    : bp.csvChunks.split("\u0001").length;
            if (chunks.size() != declared || chunks.isEmpty()) {
                ImportBreakpointStore.clear();
                emitError(listener, "断点分片文件缺失(" + chunks.size() + "/" + declared
                        + ")，请重新发起导入");
                return;
            }
            IngestOutcome outcome = doIngest(chunks, bp.ingestOffset, sessionDir, listener, bp);
            fillSummary(summary, outcome);
            finish(listener, summary, startMs, sessionDir);
            return;
        }

        // 采样文件表头（映射推理与缓存指纹共用）
        // 容错：桥接层永不返回 null，失败时返回 {"error":...} 归一化错误对象
        JSONObject sample = python.sampleFile(sourceFile.getAbsolutePath(), 15, excelSheetIndex);
        String sampleErr = extractError(sample);
        if (sampleErr != null) {
            emitError(listener, "文件采样失败: " + sampleErr);
            return;
        }
        headersArr = sample.optJSONArray("headers");
        if (headersArr == null || headersArr.length() == 0) {
            emitError(listener, "文件采样异常：未识别到表头，请确认文件为有效题库文件");
            return;
        }
        List<String> headers = toStringList(headersArr);
        String headerFinger = ImportMapCache.headerFingerprint(headers);
        String cacheKey = ImportMapCache.buildCacheKey(tableFinger, headerFinger);

        // ========== 步骤1.5：表头可疑时 LLM 识别表头行与列映射 ==========
        // 触发条件：Python 表头关键词命中 ≤1（无表头/非标准表头）+ 用户已选定工作表 +
        // 非断点恢复。LLM 根据前 12 行原始内容判断真实表头行号与列映射；
        // 正常文件零额外调用（不增加导入压力）。
        int pythonHeaderRow = -2; // -2=自动检测；>= -1 表示 LLM/断点指定
        JSONArray rawRows = sample.optJSONArray("raw_rows");
        if (sample.optBoolean("header_suspicious", false) && excelSheetIndex >= 0
                && rawRows != null && rawRows.length() > 0 && !resumeParse) {
            emitStage(listener, "mapping", "表头无法自动识别，AI 智能识别表头行与字段...");
            ImportLlmEngine.HeaderResult hr = engine.runHeaderInfer(rawRows, legalFields, docHint);
            if (hr != null && hr.valid) {
                List<String> newHeaders = rebuildHeadersForHeaderRow(
                        rawRows, sample.optJSONArray("rows"), hr.headerRow);
                if (newHeaders != null && !newHeaders.isEmpty()) {
                    headers = newHeaders;
                    pythonHeaderRow = hr.headerRow;
                    Map<String, String> newMapping = new LinkedHashMap<>();
                    for (Map.Entry<String, Integer> e : hr.mapping.entrySet()) {
                        int idx = e.getValue();
                        if (idx >= 0 && idx < headers.size()) {
                            newMapping.put(e.getKey(), headers.get(idx));
                        }
                    }
                    newMapping = ensureCombinedOptionsMapping(newMapping, headers);
                    if (newMapping.containsKey("questionText")) {
                        mapping = newMapping;
                        headerFinger = ImportMapCache.headerFingerprint(headers);
                        cacheKey = ImportMapCache.buildCacheKey(tableFinger, headerFinger);
                        summary.mappingSource = "ai";
                        emitLog(listener, "AI 表头识别: header_row=" + hr.headerRow
                                + (hr.headerRow == -1 ? "(无表头，占位列名)" : "")
                                + ", 映射=" + new JSONObject(mapping));
                    } else {
                        emitLog(listener, "AI 表头识别缺少题干列，按原逻辑处理");
                        pythonHeaderRow = -2;
                        headers = toStringList(headersArr);
                    }
                } else {
                    emitLog(listener, "AI 表头识别行号无效，按原逻辑处理");
                }
            } else {
                emitLog(listener, "AI 表头识别失败(" + (hr == null ? "无结果" : hr.failReason)
                        + ")，按原逻辑处理");
            }
        }

        if (resumeParse && bp.mappingJson != null && !bp.mappingJson.isEmpty()) {
            mapping = jsonToMap(bp.mappingJson);
            summary.resumed = true;
            summary.mappingSource = "breakpoint";
            emitLog(listener, "断点恢复：复用已推理映射，从第 " + bp.parseRowIndex + " 行续导");
            // 断点恢复沿用 LLM 识别的表头行（若有），保证列对位一致
            if (bp.headerRow >= -1) {
                pythonHeaderRow = bp.headerRow;
                List<String> newHeaders = rebuildHeadersForHeaderRow(
                        rawRows, sample.optJSONArray("rows"), bp.headerRow);
                if (newHeaders != null && !newHeaders.isEmpty()) {
                    headers = newHeaders;
                }
            }
        } else {
            Map<String, String> cached = ImportMapCache.find(cacheKey);
            if (cached != null && cached.containsKey("questionText")) {
                mapping = cached;
                summary.mappingSource = "cache";
                emitStage(listener, "mapping", "命中字段映射缓存，跳过 AI 推理");
            }
        }

        // ========== 步骤2：AI 字段映射（缓存命中跳过；否则 AI 推理，失败兜底词典） ==========
        if (mapping == null) {
            if (cancelled) return;
            emitStage(listener, "mapping", "启动字段映射推理...");
            String sampleText = buildSampleText(sample);
            String aliasJson = buildAliasHint();
            // 追加虚拟字段提示，让 AI 识别"可选项"等单列合并选项模板
            try {
                aliasJson = new JSONObject(aliasJson)
                        .put(VIRTUAL_FIELD_OPTIONS_COMBINED,
                                "选项聚合列：表头为 可选项/选项/备选答案/ABCD选项 等且单列内含多个选项"
                                + "（分号/竖线/顿号/换行/A.前缀 分隔）时，映射该列为 optionsCombined，"
                                + "系统自动拆分为 optionA/B/C/D。"
                                + "注意：只有 A/B/C/D 各占一列才分别映射 optionA/optionB/optionC/optionD")
                        .toString();
            } catch (Exception ignored) {
            }

            // 附加修正提示：题库说明/模板说明提取的字段约定（如有），帮助 AI 理解列含义
            String fixPrompt = null;
            if (docHint != null && !docHint.isEmpty()) {
                fixPrompt = "以下是题库文件中的【题库说明/模板说明】提取内容（描述字段填写约定），"
                        + "请参考它理解各列的真实含义来完成字段映射：\n" + docHint;
            }

            ImportLlmEngine.MappingResult mr = engine.runColumnMappingInfer(
                    tableCols, aliasJson, sampleText, fixPrompt, legalFields);
            if (mr.valid && mr.mapping != null) {
                mapping = mr.mapping;
                summary.mappingSource = "ai";
                emitLog(listener, "AI 映射完成(" + mr.rounds + "轮, 工具调用 "
                        + mr.toolCalls + " 次)");
                // 映射值必须真实存在于文件表头，否则本地词典纠正
                mapping = validateMappingAgainstHeaders(mapping, headers);
                // 可选项列规则补全（缓存也存补全后的，避免下次命中缺 optionsCombined）
                mapping = ensureCombinedOptionsMapping(mapping, headers);
                ImportMapCache.save(cacheKey, mapping);
            } else {
                emitLog(listener, "AI 映射失效(" + mr.failReason + ")，切换本地别名词典兜底");
                mapping = fallbackAliasMapping(headers);
                summary.mappingSource = "fallback";
                if (!mapping.containsKey("questionText") || !mapping.containsKey("correctAnswer")) {
                    emitError(listener, "字段映射失败：本地词典亦无法识别题干/答案列");
                    return;
                }
                ImportMapCache.save(cacheKey, mapping);
            }
        }

        String mappingJson = new JSONObject(mapping).toString();
        // 可选项列规则补全（纯程序，不依赖 LLM）：
        // 若映射没有任何 optionA~L，但表头存在聚合选项列（可选项/选项等），
        // 强制设为 optionsCombined —— 确保程序拆分一定生效，AI 漏识别也能兜住。
        mapping = ensureCombinedOptionsMapping(mapping, headers);
        mappingJson = new JSONObject(mapping).toString();
        currentMapping = mapping;
        emitLog(listener, "字段映射: " + mappingJson);

        // 保存解析断点
        ImportBreakpointStore.State state = new ImportBreakpointStore.State();
        state.stage = ImportBreakpointStore.STAGE_PARSE;
        state.sourceFile = sourceFile.getAbsolutePath();
        state.sourceName = sourceFile.getName();
        state.mappingJson = mappingJson;
        state.parseRowIndex = resumeParse ? bp.parseRowIndex : 0;
        state.headerRow = pythonHeaderRow;
        ImportBreakpointStore.save(state);

        // ========== 步骤3：Python 全量解析（文件级断点续导） ==========
        if (cancelled) return;
        emitStage(listener, "parse", "Python 全量解析中（断点续导）...");
        long resumeRow = resumeParse ? bp.parseRowIndex : 0;
        // 非续导时清理旧分片
        if (!resumeParse) ImportDirs.cleanSessionDir(sessionDir);

        JSONObject parseResult = python.parseFile(sourceFile.getAbsolutePath(), mappingJson,
                sessionDir.getAbsolutePath(), resumeRow, CHUNK_ROWS,
                ImportDirs.breakpointFile().getAbsolutePath(),
                buildPythonFieldSpec(legalFields).toString(), excelSheetIndex,
                pythonHeaderRow >= -1 ? Integer.valueOf(pythonHeaderRow) : null);
        String parseErr = extractError(parseResult);
        if (parseErr != null || !parseResult.optBoolean("success", false)) {
            // 永久性失败（文件损坏/格式不符等）：清断点，避免下次重跑时
            // 与已写分片叠加产生重复数据
            ImportBreakpointStore.clear();
            emitError(listener, "Python 解析失败: "
                    + (parseErr != null ? parseErr : parseResult.optString("error", "未知错误")));
            return;
        }

        // 分片清单校验：只保留磁盘上真实存在的分片（防 Python 返回脏路径）
        List<File> chunks = new ArrayList<>();
        JSONArray chunkArr = parseResult.optJSONArray("chunks");
        if (chunkArr != null) {
            for (int i = 0; i < chunkArr.length(); i++) {
                String p = chunkArr.optString(i, "");
                if (p.isEmpty()) continue;
                File f = new File(p);
                if (f.exists() && f.length() > 0) {
                    chunks.add(f);
                } else {
                    Log.w(TAG, "丢弃不存在的分片: " + p);
                }
            }
        }
        long processedRows = parseResult.optLong("processed_rows", 0);
        emitLog(listener, "解析完成: " + processedRows + " 行, CSV 分片 " + chunks.size() + " 个");
        if (chunks.isEmpty()) {
            emitError(listener, "解析结果为空，未产生可入库数据");
            ImportBreakpointStore.clear();
            return;
        }

        // ========== 步骤4：缺失字段智能填充（LLM 结合上下文推断，只补缺失） ==========
        // 原则：源表有该列但个别行缺失（题型/难度/分类/解析）时，LLM 参考前后题上下文推断；
        // 整列缺失或字段映射不存在时跳过（缺失字段由入库层默认值兜底，不劳烦 LLM）。
        // 仅当源文件确实存在可填充列（questionType/difficulty/category/explanation）时才值得推理。
        try {
            JSONArray missingArr = parseResult.optJSONArray("missing");
            List<String> fillableCols = buildFillableColumns();
            if (missingArr != null && missingArr.length() > 0 && !fillableCols.isEmpty()) {
                // Python 已按 fill_fields 收集缺失行；仅当映射中存在可填充列时处理
                emitStage(listener, "fill", "智能填充缺失字段(" + missingArr.length() + " 题)...");
                fillMissingFieldsWithContext(missingArr, listener);
                emitLog(listener, "缺失字段智能填充完成");
            } else {
                emitLog(listener, "无缺失字段或源文件无可填充列，跳过填充");
            }
        } catch (Exception e) {
            emitLog(listener, "智能填充异常(不影响入库): " + e.getMessage());
        }

        writeReadyFlag(sessionDir, sourceFile.getName(), processedRows);

        // ========== 步骤5：Java 事务批量入库（入库断点恢复） ==========
        if (cancelled) return;
        emitStage(listener, "ingest", "事务批量入库中...");
        ImportBreakpointStore.State ingestState = new ImportBreakpointStore.State();
        ingestState.stage = ImportBreakpointStore.STAGE_INGEST;
        ingestState.sourceFile = sourceFile.getAbsolutePath();
        ingestState.sourceName = sourceFile.getName();
        ingestState.mappingJson = mappingJson;
        ingestState.csvChunks = joinChunkFiles(chunks);
        ingestState.ingestOffset = 0;
        ImportBreakpointStore.save(ingestState);

        IngestOutcome outcome = doIngest(chunks, 0, sessionDir, listener, ingestState);
        fillSummary(summary, outcome);
        finish(listener, summary, startMs, sessionDir);
    }

    // ==================== 步骤内部实现 ====================

    private static class IngestOutcome {
        long imported, duplicated, failed, totalRows;
    }

    private IngestOutcome doIngest(List<File> chunks, long startOffset, File sessionDir,
                                   ImportListener listener,
                                   ImportBreakpointStore.State state) {
        IngestOutcome outcome = new IngestOutcome();
        ImportCsvIngestor ingestor = new ImportCsvIngestor(context);
        ImportCsvIngestor.IngestStats stats = ingestor.ingest(chunks, startOffset,
                new ImportCsvIngestor.IngestListener() {
                    @Override
                    public void onProgress(long processed, long total) {
                        emitProgress(listener, processed, total, "入库中");
                    }

                    @Override
                    public void onOffsetChange(long globalOffset) {
                        state.ingestOffset = globalOffset;
                        ImportBreakpointStore.save(state);
                    }
                });
        outcome.imported = stats.imported;
        outcome.failed = stats.failed;
        outcome.totalRows = stats.totalRows;
        // 精确统计：批内去重与库内去重丢弃数已由入库层累计
        outcome.duplicated = stats.duplicated;
        return outcome;
    }

    /**
     * 归一化提取 Python/桥接层返回的错误信息：
     * null、含 error 字段、success=false 均视为错误，返回可读原因；正常返回 null。
     */
    private static String extractError(JSONObject r) {
        if (r == null) return "无返回结果";
        String err = r.optString("error", "").trim();
        if (!err.isEmpty()) return err;
        if (r.has("success") && !r.optBoolean("success", true)) {
            return "Python 返回失败状态";
        }
        return null;
    }

    /** 可 LLM 填充的列：字段映射中存在 questionType/difficulty/category/explanation 才可填 */
    private List<String> buildFillableColumns() {
        List<String> cols = new ArrayList<>();
        if (currentMapping == null) return cols;
        String[] candidates = {"questionType", "difficulty", "category", "explanation"};
        for (String c : candidates) {
            if (currentMapping.containsKey(c)) cols.add(c);
        }
        return cols;
    }

    /**
     * 缺失字段智能填充（LLM 结合上下文推断）。
     * Python missing 条目含 has(缺失字段)/ctx_prev/ctx_next(相邻题上下文)，
     * 只对缺失字段跑 LLM，其他字段保持原值。
     */
    private void fillMissingFieldsWithContext(JSONArray missing, ImportListener listener) {
        List<String> fillableCols = buildFillableColumns();
        if (fillableCols.isEmpty()) return;

        // 按 chunk 分组收集
        Map<String, JSONObject> fillsByChunk = new LinkedHashMap<>();
        List<String> infos = new ArrayList<>();
        List<Integer> pendingIdx = new ArrayList<>();

        int total = missing.length();
        for (int base = 0; base < total; base += FILL_BATCH_SIZE) {
            if (cancelled) return;
            infos.clear();
            pendingIdx.clear();
            int end = Math.min(total, base + FILL_BATCH_SIZE);
            for (int i = base; i < end; i++) {
                JSONObject m = missing.optJSONObject(i);
                if (m == null) continue;
                infos.add(buildFillInfoWithContext(m));
                pendingIdx.add(i);
            }
            if (infos.isEmpty()) continue;

            List<ImportLlmEngine.FillResult> fills = engine.runFieldFillBatchInfer(infos);
            for (int k = 0; k < pendingIdx.size(); k++) {
                JSONObject m = missing.optJSONObject(pendingIdx.get(k));
                if (m == null) continue;
                ImportLlmEngine.FillResult fr = k < fills.size() ? fills.get(k) : null;
                JSONObject fill = new JSONObject();
                try {
                    // 只填确实缺失的字段（has=false）；其余字段保持原值（不回写）
                    JSONObject has = m.optJSONObject("has");
                    boolean missingType = has == null || !has.optBoolean("questionType", false);
                    boolean missingDiff = has == null || !has.optBoolean("difficulty", false);
                    boolean missingCat = has == null || !has.optBoolean("category", false);
                    boolean missingExp = has == null || !has.optBoolean("explanation", false);

                    if (fillableCols.contains("questionType") && missingType && fr != null && fr.valid
                            && !fr.questionType.isEmpty()) {
                        fill.put("questionType", fr.questionType);
                    }
                    // difficulty/category/explanation 缺失：仅当 LLM 有效时才填（不硬编码默认值，
                    // 遵循"题库有啥导啥"——LLM 推断不出就留空，入库层有默认值兜底）
                    if (fillableCols.contains("difficulty") && missingDiff && fr != null && fr.valid) {
                        fill.put("difficulty", fr.difficulty);
                    }
                    if (fillableCols.contains("category") && missingCat && fr != null && fr.valid
                            && !fr.category.isEmpty()) {
                        fill.put("category", fr.category);
                    }
                    if (fillableCols.contains("explanation") && missingExp && fr != null && fr.valid
                            && fr.explanation != null && !fr.explanation.isEmpty()) {
                        fill.put("explanation", fr.explanation);
                    }
                } catch (Exception ignored) {
                }
                if (fill.length() == 0) continue; // 无实际填充，跳过回写
                String chunk = m.optString("chunk");
                JSONObject chunkFills = fillsByChunk.get(chunk);
                if (chunkFills == null) {
                    chunkFills = new JSONObject();
                    fillsByChunk.put(chunk, chunkFills);
                }
                try {
                    chunkFills.put(String.valueOf(m.optInt("row")), fill);
                } catch (Exception ignored) {
                }
            }
            emitProgress(listener, end, total, "智能填充");
        }

        // 回写 CSV 分片
        if (fillsByChunk.isEmpty()) return;
        ImportPythonBridge python = ImportPythonBridge.getInstance(context);
        String fillFieldsJson = new JSONArray(fillableCols).toString();
        for (Map.Entry<String, JSONObject> e : fillsByChunk.entrySet()) {
            JSONObject r = python.applyFills(e.getKey(), e.getValue().toString(), fillFieldsJson);
            String err = extractError(r);
            if (err != null) {
                emitLog(listener, "回写填充失败(不影响入库): " + err);
            }
        }
    }

    /** 构建填充提示信息：题干 + 选项 + 缺失字段 + 前后题上下文（供 LLM 参考推断） */
    private String buildFillInfoWithContext(JSONObject m) {
        StringBuilder sb = new StringBuilder();
        sb.append("题干:").append(m.optString("questionText", ""));
        JSONObject options = m.optJSONObject("options");
        if (options != null) {
            java.util.Iterator<String> it = options.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = options.optString(k, "");
                if (!v.isEmpty()) sb.append(' ').append(k).append(':').append(v);
            }
        }
        JSONObject has = m.optJSONObject("has");
        if (has != null) {
            StringBuilder miss = new StringBuilder("缺失字段:");
            java.util.Iterator<String> it = has.keys();
            while (it.hasNext()) {
                String f = it.next();
                if (!has.optBoolean(f, false)) {
                    if (miss.length() > 5) miss.append(",");
                    miss.append(f);
                }
            }
            if (miss.length() > 5) sb.append(' ').append(miss);
        }
        String prev = m.optString("ctx_prev", "");
        String next = m.optString("ctx_next", "");
        if (!prev.isEmpty()) sb.append(" 上一题[").append(prev).append("]");
        if (!next.isEmpty()) sb.append(" 下一题[").append(next).append("]");
        return sb.toString();
    }

    /**
     * 根据 question 表真实结构（PRAGMA）动态构建下发给 Python 的字段规格。
     * Python 侧不再硬编码业务字段，全部以此参数为准；构建失败返回空对象，
     * Python 会用内置默认值兜底。
     */
    private JSONObject buildPythonFieldSpec(Set<String> tableCols) {
        JSONObject spec = new JSONObject();
        try {
            // 标准化 CSV 列：候选顺序 ∩ 真实表列（questionText 必须存在）
            JSONArray std = new JSONArray();
            for (String c : STD_COLUMN_PREFERENCE) {
                if (tableCols.contains(c)) std.put(c);
            }
            if (std.length() == 0 || !tableCols.contains("questionText")) {
                return new JSONObject(); // 元数据异常 → Python 兜底默认值
            }
            spec.put("std_columns", std);

            // AI 可填充字段：候选 ∩ 真实表列
            spec.put("fill_fields", new JSONArray(buildFillFieldList(tableCols)));

            // 选项字段：真实表中存在的 optionA~L → 字母映射
            JSONObject opts = new JSONObject();
            for (char c = 'A'; c <= 'L'; c++) {
                String col = "option" + c;
                if (tableCols.contains(col)) opts.put(String.valueOf(c), col);
            }
            if (opts.length() > 0) spec.put("option_fields", opts);
        } catch (Exception e) {
            Log.w(TAG, "构建 Python 字段规格失败，Python 将用默认值兜底: " + e.getMessage());
            return new JSONObject();
        }
        return spec;
    }

    /** AI 可填充字段 ∩ 真实表列（供填充与回写共用） */
    private List<String> buildFillFieldList(Set<String> tableCols) {
        List<String> fills = new ArrayList<>();
        if (tableCols == null || tableCols.isEmpty()) {
            fills.addAll(Arrays.asList(FILL_FIELD_CANDIDATES));
            return fills;
        }
        for (String f : FILL_FIELD_CANDIDATES) {
            if (tableCols.contains(f)) fills.add(f);
        }
        if (fills.isEmpty()) fills.addAll(Arrays.asList(FILL_FIELD_CANDIDATES));
        return fills;
    }

    private void finish(ImportListener listener, ImportSummary summary, long startMs,
                        File sessionDir) {
        // 清理断点、临时 CSV、标记文件
        ImportBreakpointStore.clear();
        File flag = ImportDirs.readyFlagFile();
        if (flag.exists()) {
            boolean ok = flag.delete();
            if (!ok) Log.w(TAG, "清理标记文件失败");
        }
        ImportDirs.cleanSessionDir(sessionDir);
        if (engine != null) {
            engine.unloadModelIfIdle();
        }

        summary.elapsedMs = System.currentTimeMillis() - startMs;
        // 单文件导入：同步生成缺字段报告供结果弹窗展示（批量模式由入口统一生成）
        if (!batchMode) {
            summary.issuesMessage = exportIssuesReport(listener);
        }
        emitStage(listener, "done", "导入完成");
        emitLog(listener, "统计: 新增 " + summary.imported + " / 重复 " + summary.duplicated
                + " / 失败 " + summary.failed + " (耗时 " + summary.elapsedMs + "ms)");
        if (listener != null) {
            mainHandler.post(() -> listener.onComplete(summary));
        }
    }

    private void fillSummary(ImportSummary summary, IngestOutcome outcome) {
        summary.imported = outcome.imported;
        summary.duplicated = outcome.duplicated;
        summary.failed = outcome.failed;
        summary.totalRows = outcome.totalRows;
    }

    /**
     * 缺字段报告：扫描题库中缺少分类/答案等关键字段的题目（答案解析属可选增强项，不纳入告警），
     * 导出 CSV 报告并返回修复引导文案；无缺失返回 null。
     * <p>引导闭环：用户在原表格补齐对应列后重新导入同一文件，
     * 入库层对重复题自动回填缺失字段，已入库题目不会重复。
     */
    private String exportIssuesReport(ImportListener listener) {
        Cursor c = null;
        java.io.OutputStreamWriter w = null;
        try {
            AppDatabase db = AppDatabase.getDatabase(context);
            SupportSQLiteDatabase sqlite = db.getOpenHelper().getReadableDatabase();
            c = sqlite.query("SELECT id, source, questionText, category, explanation, correctAnswer "
                    + "FROM question WHERE (category IS NULL OR category = '') "
                    + "OR (correctAnswer IS NULL OR correctAnswer = '') "
                    + "ORDER BY source, id LIMIT 5000");
            File report = ImportDirs.issuesReportFile();
            w = new java.io.OutputStreamWriter(new FileOutputStream(report), StandardCharsets.UTF_8);
            w.write("题目ID,来源文件,题干,缺失字段,当前解析\r\n");
            int count = 0;
            while (c.moveToNext()) {
                StringBuilder miss = new StringBuilder();
                if (isEmptyCell(c.getString(3))) miss.append("分类;");
                if (isEmptyCell(c.getString(5))) miss.append("答案;");
                if (isEmptyCell(c.getString(4))) miss.append("答案解析(可选);");
                if (miss.length() == 0) continue;
                count++;
                w.write(c.getLong(0) + ",\"" + csvSafe(c.getString(1)) + "\",\""
                        + csvSafe(c.getString(2)) + "\",\"" + miss + "\",\""
                        + csvSafe(c.getString(4)) + "\"\r\n");
            }
            if (count == 0) {
                boolean deleted = report.delete();
                if (!deleted && report.exists()) Log.w(TAG, "清理旧缺字段报告失败");
                return null;
            }
            String msg = "⚠ " + count + " 道题缺少关键字段（分类/答案），报告已导出: "
                    + report.getAbsolutePath()
                    + "\n修复方法：在原表格中为这些题目补充对应列（列名可用：分类、答案、答案解析），"
                    + "然后重新导入同一文件——已导入题目不会重复，缺失字段会自动补齐。";
            emitLog(listener, msg);
            return msg;
        } catch (Exception e) {
            Log.w(TAG, "生成缺字段报告失败: " + e.getMessage());
            return null;
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean isEmptyCell(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String csvSafe(String s) {
        if (s == null) return "";
        return s.replace('\r', ' ').replace('\n', ' ').replace("\"", "\"\"");
    }

    private void writeReadyFlag(File sessionDir, String fileName, long rows) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(ImportDirs.readyFlagFile());
            String content = "file=" + fileName + "\nrows=" + rows
                    + "\ntime=" + System.currentTimeMillis() + "\n";
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "写标记文件失败: " + e.getMessage());
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ==================== 辅助方法 ====================

    private List<String> readTableColumns() {
        List<String> cols = new ArrayList<>();
        try {
            AppDatabase db = AppDatabase.getDatabase(context);
            SupportSQLiteDatabase sqlite = db.getOpenHelper().getReadableDatabase();
            Cursor c = sqlite.query("PRAGMA table_info(question)");
            try {
                while (c.moveToNext()) {
                    cols.add(c.getString(c.getColumnIndexOrThrow("name")));
                }
            } finally {
                c.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "读取表结构失败: " + e.getMessage());
        }
        return cols;
    }

    /** 本地硬编码别名词典兜底（复用 FieldMappingRegistry 别名体系） */
    private Map<String, String> fallbackAliasMapping(List<String> headers) {
        Map<String, String> mapping = new LinkedHashMap<>();
        Map<String, Integer> built = FieldMappingRegistry.buildMappingFromHeaders(headers);
        for (Map.Entry<String, Integer> e : built.entrySet()) {
            int idx = e.getValue();
            if (idx >= 0 && idx < headers.size()) {
                mapping.put(e.getKey(), headers.get(idx));
            }
        }
        // 聚合选项列规则识别（纯程序，不依赖 LLM）
        mapping = ensureCombinedOptionsMapping(mapping, headers);
        return mapping;
    }

    /**
     * 可选项/聚合选项列规则识别（纯程序）：表头是聚合选项列（可选项/选项/备选答案等）且
     * 映射中没有独立选项列时，设为虚拟字段 optionsCombined，Python 按分隔符自动拆分 A~L。
     * 在任何映射路径（AI/词典）之后调用，确保程序拆分一定生效。
     */
    private Map<String, String> ensureCombinedOptionsMapping(Map<String, String> mapping,
                                                             List<String> headers) {
        if (mapping == null) mapping = new LinkedHashMap<>();
        if (mapping.containsKey("optionA")) return mapping; // 已有独立选项列，无需聚合
        if (mapping.containsKey(VIRTUAL_FIELD_OPTIONS_COMBINED)) return mapping;
        if (headers == null) return mapping;

        String[] aggregateHints = {"可选项", "选项", "备选答案", "备选项", "选项内容",
                "abcd选项", "abcd", "options", "choices", "选择项"};
        for (String h : headers) {
            String t = h == null ? "" : h.trim().toLowerCase();
            for (String hint : aggregateHints) {
                // 精确匹配或"选项"前缀（如"选项列表"），排除"选项A/B/C/D"独立列
                if (t.equals(hint) || (hint.equals("选项") && t.startsWith("选项")
                        && !t.matches("选项[a-lA-L甲乙丙丁戊己庚辛壬癸子丑1-9一二三四五六七八九十].*"))) {
                    mapping.put(VIRTUAL_FIELD_OPTIONS_COMBINED, h.trim());
                    emitLog2("聚合选项列识别(规则): " + h.trim() + " → optionsCombined");
                    break;
                }
            }
            if (mapping.containsKey(VIRTUAL_FIELD_OPTIONS_COMBINED)) break;
        }
        return mapping;
    }

    /** 日志辅助（避免依赖具体 listener） */
    private void emitLog2(String msg) {
        try {
            AILogger.i("ImportMain", msg);
        } catch (Exception ignored) {
        }
    }

    /** 校验映射值是否为文件真实列名，非法值尝试本地词典纠正 */
    private Map<String, String> validateMappingAgainstHeaders(Map<String, String> mapping,
                                                              List<String> headers) {
        Set<String> headerSet = new HashSet<>();
        for (String h : headers) headerSet.add(h.trim());

        Map<String, String> fixed = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (headerSet.contains(v)) {
                fixed.put(e.getKey(), v);
            } else {
                // 尝试别名纠正：按表头逐列 resolve
                String corrected = null;
                for (String h : headers) {
                    String std = FieldMappingRegistry.resolve(h);
                    if (e.getKey().equals(std)) {
                        corrected = h.trim();
                        break;
                    }
                }
                if (corrected != null) {
                    fixed.put(e.getKey(), corrected);
                    Log.i(TAG, "映射纠正: " + e.getKey() + " -> " + corrected);
                } else {
                    Log.w(TAG, "剔除非法映射: " + e.getKey() + " -> " + v);
                }
            }
        }
        return fixed;
    }

    /** 构建别名词典短提示（控制长度） */
    private String buildAliasHint() {
        try {
            JSONObject hint = new JSONObject();
            String[] focus = {"questionText", "optionA", "correctAnswer",
                    "category", "difficulty", "explanation"};
            for (String f : focus) {
                Set<String> aliases = FieldMappingRegistry.getAliases(f);
                if (aliases != null && !aliases.isEmpty()) {
                    List<String> limited = new ArrayList<>();
                    int count = 0;
                    for (String a : aliases) {
                        if (count++ >= 6) break;
                        limited.add(a);
                    }
                    hint.put(f, joinComma(limited));
                }
            }
            return hint.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String buildSampleText(JSONObject sample) {
        StringBuilder sb = new StringBuilder();
        JSONArray headers = sample.optJSONArray("headers");
        if (headers != null) {
            sb.append("表头: ");
            for (int i = 0; i < headers.length(); i++) {
                if (i > 0) sb.append(" | ");
                sb.append(headers.optString(i, ""));
            }
            sb.append('\n');
        }
        JSONArray rows = sample.optJSONArray("rows");
        if (rows != null) {
            int maxRows = Math.min(rows.length(), 3);
            for (int i = 0; i < maxRows; i++) {
                JSONArray row = rows.optJSONArray(i);
                if (row == null) continue;
                sb.append("行").append(i + 1).append(": ");
                for (int j = 0; j < row.length(); j++) {
                    if (j > 0) sb.append(" | ");
                    String cell = row.optString(j, "");
                    sb.append(cell.length() > 40 ? cell.substring(0, 40) + "…" : cell);
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static List<String> toStringList(JSONArray arr) {
        List<String> list = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.optString(i, ""));
            }
        }
        return list;
    }

    /**
     * 按 LLM 识别的表头行号重建表头列名。
     * headerRow >= 0：取原始行该行内容为表头；headerRow == -1：无表头，
     * 用占位列名 "列1/列2/..."（列数取首行实际列数）。无法重建返回 null。
     */
    private static List<String> rebuildHeadersForHeaderRow(JSONArray rawRows,
                                                           JSONArray fallbackRows,
                                                           int headerRow) {
        if (headerRow >= 0) {
            if (rawRows != null && headerRow < rawRows.length()) {
                JSONArray h = rawRows.optJSONArray(headerRow);
                if (h != null && h.length() > 0) {
                    return toStringList(h);
                }
            }
            return null;
        }
        // 无表头：占位列名
        JSONArray first = null;
        if (rawRows != null && rawRows.length() > 0) {
            first = rawRows.optJSONArray(0);
        }
        if (first == null && fallbackRows != null && fallbackRows.length() > 0) {
            first = fallbackRows.optJSONArray(0);
        }
        if (first == null) return null;
        List<String> placeholders = new ArrayList<>();
        for (int i = 1; i <= first.length(); i++) {
            placeholders.add("列" + i);
        }
        return placeholders;
    }

    private static Map<String, String> jsonToMap(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(json);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                map.put(k, o.optString(k, ""));
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private static String fingerprint(String content) {
        return Integer.toHexString(content.hashCode()) + "_" + content.length();
    }

    private static String joinComma(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static String joinChunkFiles(List<File> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) sb.append('\u0001');
            sb.append(chunks.get(i).getAbsolutePath());
        }
        return sb.toString();
    }

    private static List<File> splitChunkFiles(String joined) {
        List<File> list = new ArrayList<>();
        if (joined == null || joined.isEmpty()) return list;
        for (String p : joined.split("\u0001")) {
            File f = new File(p);
            if (f.exists()) list.add(f);
        }
        return list;
    }

    // ==================== 事件分发 ====================

    private void emitStage(ImportListener l, String stage, String msg) {
        Log.i(TAG, "[" + stage + "] " + msg);
        if (l != null) mainHandler.post(() -> l.onStage(stage, msg));
    }

    private void emitLog(ImportListener l, String msg) {
        Log.i(TAG, msg);
        if (l != null) mainHandler.post(() -> l.onLog(msg));
    }

    private void emitProgress(ImportListener l, long cur, long total, String detail) {
        if (l != null) mainHandler.post(() -> l.onProgress(cur, total, detail));
    }

    private void emitError(ImportListener l, String msg) {
        Log.e(TAG, msg);
        if (l != null) mainHandler.post(() -> l.onError(msg));
    }
}
