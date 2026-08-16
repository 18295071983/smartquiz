package com.oilquiz.app.ai.importing.v2;

import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.sqlite.db.SupportSQLiteDatabase;

import com.oilquiz.app.ai.importing.FieldMappingRegistry;
import com.oilquiz.app.database.AppDatabase;

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
    /** 填充任务单批上限 */
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
    private static final String[] FILL_FIELD_CANDIDATES = {"category", "difficulty", "explanation"};

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
        JSONObject sample = python.sampleFile(sourceFile.getAbsolutePath(), 15);
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

        if (resumeParse && bp.mappingJson != null && !bp.mappingJson.isEmpty()) {
            mapping = jsonToMap(bp.mappingJson);
            summary.resumed = true;
            summary.mappingSource = "breakpoint";
            emitLog(listener, "断点恢复：复用已推理映射，从第 " + bp.parseRowIndex + " 行续导");
        } else {
            Map<String, String> cached = ImportMapCache.find(cacheKey);
            if (cached != null && cached.containsKey("questionText")) {
                mapping = cached;
                summary.mappingSource = "cache";
                emitStage(listener, "mapping", "命中字段映射缓存，跳过 AI 推理");
            }
        }

        // ========== 步骤2：引擎字段映射推理 ==========
        if (mapping == null) {
            if (cancelled) return;
            emitStage(listener, "mapping", "启动字段映射推理...");
            String sampleText = buildSampleText(sample);
            String aliasJson = buildAliasHint();
            // 追加虚拟字段提示，让 AI 识别"可选项"等单列合并选项模板
            try {
                aliasJson = new JSONObject(aliasJson)
                        .put(VIRTUAL_FIELD_OPTIONS_COMBINED,
                                "选项合并在单列(分号/竖线分隔)时映射该列")
                        .toString();
            } catch (Exception ignored) {
            }

            ImportLlmEngine.MappingResult mr = engine.runColumnMappingInfer(
                    tableCols, aliasJson, sampleText, null, legalFields);
            if (mr.valid && mr.mapping != null) {
                mapping = mr.mapping;
                summary.mappingSource = "ai";
                emitLog(listener, "AI 映射完成(" + mr.rounds + "轮, 工具调用 "
                        + mr.toolCalls + " 次)");
                // 映射值必须真实存在于文件表头，否则本地词典纠正
                mapping = validateMappingAgainstHeaders(mapping, headers);
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
        emitLog(listener, "字段映射: " + mappingJson);

        // 保存解析断点
        ImportBreakpointStore.State state = new ImportBreakpointStore.State();
        state.stage = ImportBreakpointStore.STAGE_PARSE;
        state.sourceFile = sourceFile.getAbsolutePath();
        state.sourceName = sourceFile.getName();
        state.mappingJson = mappingJson;
        state.parseRowIndex = resumeParse ? bp.parseRowIndex : 0;
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
                buildPythonFieldSpec(legalFields).toString());
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

        // ========== 步骤4：独立会话 AI 智能补充缺失字段 ==========
        // 循环分批填充直到扫不出缺失行：scanMissingFromChunks 每轮上限 500 条，
        // 填充写回分片后下一轮不再收集，从而覆盖全量缺失（而非仅前 500 条落兜底值）。
        // 同时自然覆盖断点续导后旧分片中的缺失行（Python missing 只含新分片）。
        int filledTotal = 0;
        final int FILL_TOTAL_CAP = 50000; // 安全上限，防异常死循环
        int fillRounds = 0;
        while (!cancelled && filledTotal < FILL_TOTAL_CAP) {
            JSONArray scanned = scanMissingFromChunks(chunks);
            if (scanned.length() == 0) break;
            emitStage(listener, "fill", "AI 补充缺失字段(" + scanned.length() + " 题"
                    + (filledTotal > 0 ? "，累计 " + filledTotal + ")" : ")") + "...");
            fillMissingFields(scanned, listener);
            filledTotal += scanned.length();
            fillRounds++;
            if (fillRounds > 100) break; // 单轮上限 500 条，100 轮=50000 条
        }
        if (filledTotal > 0) {
            emitLog(listener, "字段填充完成: 共处理 " + filledTotal + " 题");
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

    /** 缺失字段批量填充：单批 10 题，失败固定兜底默认值 */
    private void fillMissingFields(JSONArray missing, ImportListener listener) {
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
                infos.add(buildFillInfo(m));
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
                    if (fr != null && fr.valid) {
                        fill.put("category", fr.category.isEmpty() ? "未分类" : fr.category);
                        fill.put("difficulty", fr.difficulty);
                        fill.put("explanation", fr.explanation);
                    } else {
                        // 固定兜底默认值
                        fill.put("category", "未分类");
                        fill.put("difficulty", 1);
                        fill.put("explanation", "");
                    }
                } catch (Exception ignored) {
                }
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
            emitProgress(listener, end, total, "字段填充");
        }

        // 回写 CSV 分片（可填充字段列表由 Java 动态下发，与真实表列保持一致）
        ImportPythonBridge python = ImportPythonBridge.getInstance(context);
        String fillFieldsJson = buildFillFieldsJson();
        for (Map.Entry<String, JSONObject> e : fillsByChunk.entrySet()) {
            JSONObject r = python.applyFills(e.getKey(), e.getValue().toString(), fillFieldsJson);
            String err = extractError(r);
            if (err != null) {
                // 回写失败不阻断入库：入库层对缺失字段有默认值兜底
                emitLog(listener, "回写填充失败(不影响入库): " + err);
            }
        }
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

    /** 可填充字段列表 JSON（下发给 apply_fills） */
    private String buildFillFieldsJson() {
        List<String> cols = readTableColumns();
        Set<String> set = cols.isEmpty() ? new HashSet<>() : new HashSet<>(cols);
        return new JSONArray(buildFillFieldList(set)).toString();
    }

    private String buildFillInfo(JSONObject m) {
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
        return sb.toString();
    }

    /** 扫描已有 CSV 分片，收集缺失 category/difficulty/explanation 的题目（断点续导兼兼容） */
    private JSONArray scanMissingFromChunks(List<File> chunks) {
        JSONArray missing = new JSONArray();
        final int cap = 500;
        try {
            for (File f : chunks) {
                if (missing.length() >= cap) break;
                java.io.BufferedReader br = null;
                try {
                    br = new java.io.BufferedReader(new java.io.InputStreamReader(
                            new java.io.FileInputStream(f), StandardCharsets.UTF_8));
                    String headerLine = ImportCsvIngestor.readLogicalLine(br);
                    if (headerLine == null) continue;
                    String[] headers = ImportCsvIngestor.parseCsvLine(headerLine);
                    Map<String, Integer> idx = new HashMap<>();
                    for (int i = 0; i < headers.length; i++) idx.put(headers[i].trim(), i);
                    Integer qi = idx.get("questionText");
                    Integer ci = idx.get("category");
                    Integer di = idx.get("difficulty");
                    Integer ei = idx.get("explanation");
                    if (qi == null) continue;

                    String line;
                    int row = 0;
                    while ((line = ImportCsvIngestor.readLogicalLine(br)) != null) {
                        if (line.trim().isEmpty()) continue;
                        String[] cells = ImportCsvIngestor.parseCsvLine(line);
                        boolean need = (ci == null || cellEmpty(cells, ci))
                                || (di == null || cellEmpty(cells, di))
                                || (ei == null || cellEmpty(cells, ei));
                        if (need) {
                            JSONObject m = new JSONObject();
                            m.put("chunk", f.getAbsolutePath());
                            m.put("row", row);
                            m.put("questionText", truncateSafe(cells[qi], 120));
                            JSONObject options = new JSONObject();
                            putOption(options, "A", cells, idx.get("optionA"));
                            putOption(options, "B", cells, idx.get("optionB"));
                            putOption(options, "C", cells, idx.get("optionC"));
                            putOption(options, "D", cells, idx.get("optionD"));
                            m.put("options", options);
                            missing.put(m);
                            if (missing.length() >= cap) break;
                        }
                        row++;
                    }
                } finally {
                    if (br != null) {
                        try {
                            br.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "扫描缺失字段异常: " + e.getMessage());
        }
        return missing;
    }

    private static boolean cellEmpty(String[] cells, int idx) {
        return idx < 0 || idx >= cells.length || cells[idx] == null || cells[idx].trim().isEmpty();
    }

    private static void putOption(JSONObject options, String key, String[] cells, Integer idx) {
        try {
            options.put(key, (idx != null && idx >= 0 && idx < cells.length)
                    ? truncateSafe(cells[idx], 40) : "");
        } catch (Exception ignored) {
        }
    }

    private static String truncateSafe(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
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
        // 合并选项列兜底：无独立选项列且表头含"可选项/选项"时，映射为虚拟字段 optionsCombined
        if (!mapping.containsKey("optionA")) {
            for (String h : headers) {
                String t = h == null ? "" : h.trim();
                if ("可选项".equals(t) || "选项".equals(t)) {
                    mapping.put(VIRTUAL_FIELD_OPTIONS_COMBINED, t);
                    break;
                }
            }
        }
        return mapping;
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
