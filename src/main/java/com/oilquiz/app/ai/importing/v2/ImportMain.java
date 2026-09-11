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
    private static final int FILL_BATCH_CHARS = 1900;

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

    /**
     * 不允许 LLM 填充的字段（按性质排除，非硬编码可填字段名）：
     * - 题干/答案/选项类：缺失即题目无效，应标记跳过而非"猜"；
     * - 合并选项虚拟字段：由程序拆分，非填充对象。
     */
    private static final java.util.Set<String> FILL_EXCLUDED_FIELDS = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(Arrays.asList(
                    "id", "questionText", "correctAnswer", "answerText",
                    "optionsCombined", "source", "relatedQuestion"
            )));

    /** 选项字段前缀（optionA~L 全部排除填充） */
    private static final String FILL_EXCLUDED_OPTION_PREFIX = "option";

    /** 虚拟映射字段：合并选项列（非数据库列，Python 解析层拆分到 optionA~L） */
    private static final String VIRTUAL_FIELD_OPTIONS_COMBINED = "optionsCombined";

    /** 导入事件回调 */
    public interface ImportListener {
        void onStage(String stage, String message);

        void onLog(String message);

        void onProgress(long current, long total, String detail);

        void onComplete(ImportSummary summary);

        void onError(String message);

        /**
         * 导入被用户取消（决策弹窗取消 / 等待确认超时）。
         * 实现方应把任务状态置为 CANCELLED，否则取消后状态会一直卡在 RUNNING，
         * 智能体/UI 轮询会误判为仍在导入。
         */
        default void onCancelled(String reason) {
        }

        /**
         * 解析完成、入库前的质量预览回调（可选实现）。
         * 让 UI 在真正入库前展示"不完整题目"情况，并让用户选择处理方式。
         */
        default void onQualityPreview(QualityPreview preview) {
        }
    }

    /**
     * 交互确认处理器：把导入流水线从"一键跑到底"改为"关键决策点暂停、等用户确认后继续"。
     * UI 层注入后，在字段映射/数据预览/填充/入库 4 个决策点回调，返回用户的处理决定。
     * 未注入（null）时流水线按默认行为直接放行，兼容批量/断点等无 UI 场景。
     */
    public interface InteractionHandler {

        /** 交互决策结果 */
        class Decision {
            public static final int CONTINUE = 0;   // 按当前设置继续
            public static final int CANCEL = 1;     // 取消本次导入
            public int action = CONTINUE;
            /** 用户是否选择"仅导入完整题目"（跳过缺可补充字段的行） */
            public boolean skipIncomplete = false;
            /** 用户是否关闭智能填充 */
            public boolean fillEnabled = true;
            /** 用户修改后的字段映射（标准字段→源列名；null=未修改） */
            public Map<String, String> newMapping;
            /** 预览页是否已消费（避免再次弹出） */
            public boolean previewConsumed = false;
        }

        /**
         * 决策点1：字段映射完成后（AI 推理/缓存命中/规则识别后）。
         * @param mapping      当前字段映射（标准字段→源列名）
         * @param headers      文件表头（列名列表）
         * @param sourceName   源文件名
         * @param docHint      检测到的题库说明（可空，UI 可展示确认是否被正确识别）
         * @param mappingSource 映射来源：cache/rules/ai/fallback（UI 展示，帮助用户判断是否需手动修正）
         */
        Decision onMappingReady(Map<String, String> mapping, List<String> headers,
                                String sourceName, String docHint, String mappingSource);

        /**
         * 决策点2：解析完成后、入库前（数据预览 + 错误处理）。
         * @param preview   质量预览统计
         * @param chunks    解析分片文件（供预览读行）
         */
        Decision onPreviewReady(QualityPreview preview, List<File> chunks);

        /**
         * 决策点3：缺失字段智能填充前。
         * @param preview    质量预览
         * @param missingCount 缺字段行数
         */
        Decision onFillReady(QualityPreview preview, int missingCount);

        /**
         * 决策点4：最终入库前（汇总确认）。
         * @param preview  质量预览
         * @param summary  当前累计统计（预计导入数等）
         */
        Decision onFinalConfirm(QualityPreview preview, ImportSummary summary);
    }

    /** 质量预览：解析完成后对源文件的完整度统计。 */
    public static class QualityPreview implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        /** 源文件总行数（含被跳过的无效行） */
        public long totalRows;
        /** 实际写入分片的行数（= 总行 - 重复行；题干为空也已写入） */
        public long writtenRows;
        /** 题干为空/批内重复被丢弃的行数（无法入库） */
        public long skippedCount;
        /** 文件内重复题数（写入分片，入库层统一去重后计入 duplicated） */
        public long duplicateCount;
        /** 题干为空的题数（写入分片但入库判失败；与重复区分） */
        public long emptyQuestionCount;
        /** 已解析为题目、但缺可补充字段（题型/难度/分类/解析）的行数 */
        public long incompleteCount;
        /** 各字段缺失统计（字段名 → 缺失行数），如 category/difficulty/explanation/questionType */
        public final Map<String, Long> missingByField = new LinkedHashMap<>();
        /** 已映射字段集合（标准字段名）：缺失判断只针对这些字段，不硬编码非映射字段 */
        public java.util.Set<String> mappedFields = new java.util.HashSet<>();

        /** 单组重复/近似重复题目明细 */
        public static class DuplicateDetail implements java.io.Serializable {
            private static final long serialVersionUID = 1L;
            /** 题干摘要 */
            public String question = "";
            /** 答案摘要 */
            public String answer = "";
            /** 出现的数据题序号（1-based，含首次出现；与预览列表行号一致） */
            public java.util.List<Integer> rows = new java.util.ArrayList<>();
            /** 人类可读的重复原因（由 Java 生成） */
            public String reason = "";
        }

        /** 文件内完全重复（题干+答案相同，仅保留 1 题） */
        public final java.util.List<DuplicateDetail> duplicateDetails = new java.util.ArrayList<>();
        /** 同题干不同答案（近似重复，均保留，提示用户核对是否需合并） */
        public final java.util.List<DuplicateDetail> stemVariantDetails = new java.util.ArrayList<>();

        /** 可正常入库的完整题目数（写入行 - 题干为空 - 缺可补充字段数） */
        public long completeCount() {
            long base = writtenRows > 0 ? writtenRows : totalRows;
            long c = base - emptyQuestionCount - incompleteCount;
            return Math.max(0, c);
        }

        /** 重复跳过行数（题干为空已单独计 emptyQuestionCount） */
        public long getDuplicateCount() {
            long d = duplicateCount > 0 ? duplicateCount : (skippedCount - emptyQuestionCount);
            return Math.max(0, d);
        }

        /** 是否存在不可入库的无效行（题干为空等） */
        public boolean hasSkipped() {
            return skippedCount > 0;
        }

        /** 是否存在缺关键字段（分类/答案）的题目 —— 仅当这些字段已映射时才判定为"关键缺失" */
        public boolean hasCriticalMissing() {
            boolean catMapped = mappedFields.contains("category") || mappedFields.contains("correctAnswer");
            if (!catMapped) return false;
            return missingByField.containsKey("category")
                    || missingByField.containsKey("correctAnswer");
        }

        @Override
        public String toString() {
            return "QualityPreview{total=" + totalRows + ", skipped=" + skippedCount
                    + ", incomplete=" + incompleteCount + ", missing=" + missingByField + "}";
        }
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
    /** 默认题型（工作表名推断）：源表无题型列时填充 questionType */
    private volatile String defaultQuestionType = null;
    /** 批量模式标志：缺字段报告由批量入口统一生成一次，避免逐文件重复扫描 */
    private volatile boolean batchMode = false;
    /** 缺失字段智能填充开关：默认开启；关闭后缺失字段留空直接入库（导入更快，不调用 LLM） */
    private volatile boolean fillEnabled = true;
    /** 跳过不完整题目：true 时入库阶段跳过缺可补充字段（题型/难度/分类/解析）的行，仅导入完整题 */
    private volatile boolean skipIncomplete = false;
    /** 交互确认处理器（UI 注入；null 时流水线直接放行，兼容批量/断点等无 UI 场景） */
    private volatile InteractionHandler interactionHandler = null;
    /** 多工作表模式标志：每 sheet 完成只累计统计不发 onComplete，全部完成后汇总一次 */
    private volatile boolean multiSheetMode = false;
    /** 多工作表累计统计 */
    private volatile ImportSummary multiTotal;
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

    /**
     * 设置默认题型（由工作表名推断，如"单选题"）：源表无题型列时填充 questionType，
     * 避免按题型分 sheet 的题库导入后题型为空。null/空/"未分类"表示不填充。
     */
    public void setDefaultQuestionType(String questionType) {
        String t = (questionType == null || questionType.trim().isEmpty())
                ? null : questionType.trim();
        // "未分类"是推断兜底值，不是真实题型，不填充
        if ("未分类".equals(t)) t = null;
        this.defaultQuestionType = t;
    }

    /**
     * 设置缺失字段智能填充开关。
     * @param enabled true=用 LLM 补全缺失的题型/难度/分类/解析（默认）；false=缺失留空直接入库
     */
    public void setFillEnabled(boolean enabled) {
        this.fillEnabled = enabled;
    }

    /** 设置是否跳过不完整题目（入库阶段跳过缺可补充字段的行）。 */
    public void setSkipIncomplete(boolean skip) {
        this.skipIncomplete = skip;
    }

    /** 注入交互确认处理器（UI 层在启动导入前调用；null 恢复默认直接放行）。 */
    public void setInteractionHandler(InteractionHandler handler) {
        this.interactionHandler = handler;
    }

    /**
     * 执行一次交互决策：调用注入的处理器（若无则返回默认 CONTINUE 决策）。
     * 处理"用户取消"（标记 cancelled，后续步骤自然停止）与"用户修改映射/开关"的落地。
     */
    private InteractionHandler.Decision askDecision(ImportListener listener,
            java.util.function.Function<InteractionHandler, InteractionHandler.Decision> action) {
        InteractionHandler handler = interactionHandler;
        if (handler == null) {
            return new InteractionHandler.Decision();
        }
        InteractionHandler.Decision d;
        try {
            d = action.apply(handler);
        } catch (Throwable t) {
            Log.w(TAG, "交互决策异常，按继续处理: " + t.getMessage());
            d = new InteractionHandler.Decision();
        }
        if (d == null) d = new InteractionHandler.Decision();
        // 落地决策：取消 → 设置 cancelled；跳过不完整 → 同步开关；填充开关 → 同步
        if (d.action == InteractionHandler.Decision.CANCEL) {
            cancelled = true;
            // 清理断点：取消后残留断点会导致下次导入从断点续导跳过部分行
            ImportBreakpointStore.clear();
            Log.i(TAG, "用户取消导入，已清理断点");
            // 通知取消状态：否则取消后任务状态卡 RUNNING，智能体/UI 轮询误判仍在导入
            try {
                emitStage(listener, "cancelled", "用户取消导入");
                listener.onCancelled("用户取消导入");
            } catch (Throwable t) {
                Log.w(TAG, "取消通知异常: " + t.getMessage());
            }
        }
        if (d.skipIncomplete) {
            this.skipIncomplete = true;
        }
        if (!d.fillEnabled) {
            this.fillEnabled = false;
        }
        return d;
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
            } finally {
                // 导入结束（成功/取消/异常）：本地模型保持常驻（复用对话模块内核），不再主动释放
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
        // 质量预览（解析后填充，供交互点 3/4 复用）
        final QualityPreview[] previewHolder = {null};

        if (sourceFile == null || !sourceFile.exists()) {
            emitError(listener, "源文件不存在");
            return;
        }
        emitStage(listener, "init", "初始化导入引擎: " + sourceFile.getName());

        // ========== 步骤0：初始化准备 ==========
        // 引擎复用：多工作表导入时同一 ImportMain 实例连续处理多个 sheet，
        // 避免每个 sheet 重新加载本地模型（仅首次创建）
        if (engine == null) {
            if (localOrchestrator != null) {
                // 携带外部编排器（用于模型模式/信息展示；映射与填充推理仍由 ImportLlmEngine 本地模型完成）
                emitLog(listener, "初始化导入引擎（本地模型推理）");
                engine = new ImportLlmEngine(context, localOrchestrator);
            } else {
                engine = new ImportLlmEngine(context);
            }
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
        // 交互模式（注入 InteractionHandler）每次全新导入，不续导——
        // 避免上次中途取消残留断点导致本次跳过部分行（如 130 题只导 129）。
        // 断点续导仅保留给非交互场景（断点恢复/后台批量）。
        boolean interactiveMode = interactionHandler != null;
        // 断点按文件+工作表隔离：多工作表导入时 sheet 间的断点互不串扰
        boolean resumeParse = !interactiveMode && bp != null
                && sourceFile.getAbsolutePath().equals(bp.sourceFile)
                && bp.sheetIndex == excelSheetIndex
                && ImportBreakpointStore.STAGE_PARSE.equals(bp.stage);
        boolean resumeIngest = !interactiveMode && bp != null
                && sourceFile.getAbsolutePath().equals(bp.sourceFile)
                && bp.sheetIndex == excelSheetIndex
                && ImportBreakpointStore.STAGE_INGEST.equals(bp.stage);
        if (interactiveMode && bp != null) {
            // 清理历史断点，避免与本次全新导入的解析/入库冲突
            ImportBreakpointStore.clear();
        }
        // 全新导入前打扫 temp/ 历史残留（仅删空目录，并行活动目录安全跳过）
        ImportDirs.cleanAllEmptySessionDirs();

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

        // 题库说明/模板说明：优先使用调用方注入的（Excel 说明 sheet 提取 / 用户填写），
        // 否则用 Python 采样自动检测的（文件头部说明块，有则用无则跳过）
        if (docHint == null || docHint.isEmpty()) {
            String autoDoc = sample.optString("doc_hint", "").trim();
            if (!autoDoc.isEmpty()) {
                docHint = autoDoc;
                emitLog(listener, "自动检测到题库说明，用于优化映射推理");
            }
        }

        // ========== 步骤1.5：表头可疑时 LLM 识别表头行与列映射 ==========
        // 触发条件：Python 表头关键词命中 ≤1（无表头/非标准表头）+ 用户已选定工作表 +
        // 非断点恢复。LLM 根据前 12 行原始内容判断真实表头行号与列映射；
        // 正常文件零额外调用（不增加导入压力）。
        int pythonHeaderRow = -2; // -2=自动检测；>= -1 表示 LLM/断点指定
        JSONArray rawRows = sample.optJSONArray("raw_rows");
        if (sample.optBoolean("header_suspicious", false) && excelSheetIndex >= 0
                && rawRows != null && rawRows.length() > 0 && !resumeParse) {
            emitStage(listener, "mapping", "表头无法自动识别，AI 智能识别表头行与字段...");
            ImportLlmEngine.HeaderResult hr = engine.runHeaderInfer(
                    rawRows, legalFields, docHint, buildAliasHint());
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
                        + ")，尝试纯规则识别（离线可用）...");
                RuleHeaderResult ruleHr = ruleBasedHeaderInfer(rawRows);
                if (ruleHr != null && ruleHr.valid && ruleHr.mergedHeaders != null
                        && !ruleHr.mergedHeaders.isEmpty()) {
                    headers = ruleHr.mergedHeaders;
                    pythonHeaderRow = ruleHr.headerRow;
                    Map<String, String> newMapping = new LinkedHashMap<>();
                    for (Map.Entry<String, Integer> e : ruleHr.mapping.entrySet()) {
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
                        summary.mappingSource = "rules";
                        emitLog(listener, "纯规则表头识别成功: header_row=" + ruleHr.headerRow
                                + ", 映射=" + new JSONObject(mapping));
                    } else {
                        emitLog(listener, "纯规则表头识别缺少题干列，按原逻辑处理");
                        pythonHeaderRow = -2;
                        headers = toStringList(headersArr);
                    }
                } else {
                    emitLog(listener, "纯规则表头识别亦失败，按原逻辑处理");
                }
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
                // 缓存有效性校验：映射引用的每个源列名必须存在于当前表头。
                // 若表头已变化（缓存引用的列在当前表头中不存在），视为"表头不一致"，
                // 放弃缓存重新映射，避免旧映射错位套用到新表头上。
                if (!cacheMappingMatchesHeaders(cached, headers)) {
                    emitLog(listener, "缓存映射与当前表头不一致（引用的列已不存在），重新映射");
                } else {
                    mapping = cached;
                    // 缓存映射可能残缺（历史原因/手动精简，如仅题干/答案/选项）：
                    // 用本地词典按表头别名补齐缺失的标准字段（题型/难度/分类等），
                    // 避免同表头文件反复命中残缺映射导致这些列永远不映射（入库丢字段）。
                    Map<String, String> merged = mergeMappingWithAlias(cached, headers);
                    // 聚合选项列纠正必须先于缓存保存：词典可能把"可选项"识别成 optionA，
                    // 不纠正就入缓存会导致下次命中错误版本
                    merged = ensureCombinedOptionsMapping(merged, headers);
                    if (!merged.equals(cached)) {
                        mapping = merged;
                        emitLog(listener, "缓存映射已补全/纠正: " + new JSONObject(merged));
                        ImportMapCache.save(cacheKey, merged); // 补全结果回写，下次直接命中完整版
                    }
                    summary.mappingSource = "cache";
                    emitStage(listener, "mapping", "命中字段映射缓存，跳过 AI 推理");
                }
            }
        }

        // ========== 步骤2：字段映射（分层策略，尽量少调 LLM） ==========
        // 优先级：缓存命中 > 本地词典完整识别（零 LLM）> LLM 映射（结合说明）> 词典兜底。
        // 本地模型推理慢、占用资源，规则能完整识别时直接采用，可大幅减轻模型压力；
        // 规则识别不完整（特殊列名/复杂模板）才调用 LLM 补强；无 LLM 时规则结果即最终可用。
        if (mapping == null) {
            // 2a. 本地词典规则优先：零 LLM 成本
            Map<String, String> ruleMapping = fallbackAliasMapping(headers);
            if (ruleMappingSufficient(ruleMapping)) {
                mapping = ruleMapping;
                summary.mappingSource = "rules";
                emitStage(listener, "mapping", "本地词典识别映射（零模型调用）");
                emitLog(listener, "本地词典完整识别，跳过 LLM 映射: " + new JSONObject(mapping));
                ImportMapCache.save(cacheKey, mapping);
            } else {
                // 2b. 规则不完整 → LLM 映射（说明驱动），失败再兜底词典
                if (cancelled) return;
                emitStage(listener, "mapping", "本地词典识别不完整，启动 LLM 字段映射...");
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

                // 附加修正提示：题库说明/模板说明提取的字段约定（如有），帮助 AI 理解列含义。
                // 说明是每个文件的动态内容——特殊列名（"正确答案(必填)"、"填空项"、"选项A~E"）
                // 由 AI 依据说明识别映射，而不是依赖硬编码别名表。
                String fixPrompt = null;
                if (docHint != null && !docHint.isEmpty()) {
                    fixPrompt = "以下是题库文件中的【题库说明/模板说明】提取内容（描述字段填写约定与格式要求），"
                            + "请以它为准理解各列的真实含义来完成字段映射：\n" + docHint
                            + "\n注意：若说明描述了列含义（如\"正确答案(必填)\"是答案列、\"填空项\"是填空答案列、"
                            + "\"选项A~E\"是选项列），务必映射到对应标准字段；"
                            + "特殊列名（带括号/序号/后缀）按说明含义识别，不要仅凭字面猜。";
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
        }

        String mappingJson = new JSONObject(mapping).toString();
        // 可选项列规则补全（纯程序，不依赖 LLM）：
        // 若映射没有任何 optionA~L，但表头存在聚合选项列（可选项/选项等），
        // 强制设为 optionsCombined —— 确保程序拆分一定生效，AI 漏识别也能兜住。
        mapping = ensureCombinedOptionsMapping(mapping, headers);
        mappingJson = new JSONObject(mapping).toString();
        currentMapping = mapping;
        emitLog(listener, "字段映射: " + mappingJson);
        // 说明交叉校验：说明中提到且表头存在的关键列，若未映射则提示（动态发现漏映射）
        logMappingVsDocHint(headers, mapping, docHint, listener);

        // ========== 交互点1：字段映射确认 ==========
        // 让用户核对 AI 识别的列含义（可直接修改映射），准确率优先而非一味自动化。
        if (!resumeIngest) {
            final Map<String, String> mappingForConfirm = mapping;
            final List<String> headersForConfirm = headers;
            final String sourceForConfirm = summary.mappingSource;
            emitStage(listener, "mapping-confirm", "等待用户确认字段映射…");
            InteractionHandler.Decision d1 = askDecision(listener,
                    h -> h.onMappingReady(mappingForConfirm, headersForConfirm,
                            sourceFile.getName(), this.docHint, sourceForConfirm));
            if (d1.action == InteractionHandler.Decision.CANCEL) {
                return; // 用户取消
            }
            if (d1.newMapping != null && !d1.newMapping.isEmpty()) {
                // 用户修改了映射：校验并落地（缺题干/答案时给出错误，避免带伤入库）
                Map<String, String> userMapping = validateMappingAgainstHeaders(d1.newMapping, headers);
                userMapping = ensureCombinedOptionsMapping(userMapping, headers);
                if (userMapping.containsKey("questionText") && userMapping.containsKey("correctAnswer")) {
                    mapping = userMapping;
                    mappingJson = new JSONObject(mapping).toString();
                    currentMapping = mapping;
                    // 用户确认的映射落缓存：下次同表头文件直接命中用户版本，
                    // 避免再次命中旧的残缺缓存需要重复修改
                    ImportMapCache.save(cacheKey, userMapping);
                    emitLog(listener, "用户已修改字段映射: " + mappingJson);
                } else {
                    emitLog(listener, "用户映射缺少题干/答案列，保留原 AI 映射");
                }
            }
        }

        // 保存解析断点
        ImportBreakpointStore.State state = new ImportBreakpointStore.State();
        state.stage = ImportBreakpointStore.STAGE_PARSE;
        state.sourceFile = sourceFile.getAbsolutePath();
        state.sourceName = sourceFile.getName();
        state.mappingJson = mappingJson;
        state.parseRowIndex = resumeParse ? bp.parseRowIndex : 0;
        state.headerRow = pythonHeaderRow;
        state.sheetIndex = excelSheetIndex;
        ImportBreakpointStore.save(state);

        // ========== 步骤3：Python 全量解析（文件级断点续导） ==========
        if (cancelled) return;
        emitStage(listener, "parse", resumeParse
                ? "Python 全量解析中（断点续导）..." : "Python 全量解析中...");
        long resumeRow = resumeParse ? bp.parseRowIndex : 0;
        // 非续导时清理旧分片
        if (!resumeParse) ImportDirs.cleanSessionDir(sessionDir);

        // 题型兜底优先级：题库说明推断（动态，如"单选题说明…"）> 工作表名推断 > 无。
        // 说明是文件自己的内容，对"按题型分 sheet / 说明声明题型"的文件更准确。
        String effectiveQuestionType = inferQuestionTypeFromDoc(docHint);
        if (effectiveQuestionType == null) {
            effectiveQuestionType = defaultQuestionType;
        }
        if (effectiveQuestionType != null) {
            emitLog(listener, "题型兜底: " + effectiveQuestionType
                    + (inferQuestionTypeFromDoc(docHint) != null ? "（来自题库说明）" : "（来自工作表名）"));
        }
        // 填空格式提示：说明描述了【】/双中括号填空约定 → 提示确认填空答案列映射
        if (docDescribesFillFormat(docHint)) {
            emitLog(listener, "说明描述填空格式（【】/双中括号），已按填空题处理，"
                    + "请在字段映射确认时核对填空答案列");
        }

        JSONObject parseResult = python.parseFile(sourceFile.getAbsolutePath(), mappingJson,
                sessionDir.getAbsolutePath(), resumeRow, CHUNK_ROWS,
                ImportDirs.breakpointFile().getAbsolutePath(),
                buildPythonFieldSpec(legalFields).toString(), excelSheetIndex,
                pythonHeaderRow >= -1 ? Integer.valueOf(pythonHeaderRow) : null,
                effectiveQuestionType, extractOptionDelimiter(docHint));
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
        long skippedCount = parseResult.optLong("skipped_count", 0);
        emitLog(listener, "解析完成: " + processedRows + " 行, CSV 分片 " + chunks.size() + " 个");
        if (chunks.isEmpty()) {
            emitError(listener, "解析结果为空，未产生可入库数据");
            ImportBreakpointStore.clear();
            return;
        }

        // ========== 步骤3.5：数据预览 + 交互点2（错误处理决策） ==========
        // 汇总解析质量（跳过行/缺失字段），交给 UI 展示全部行预览 + 错误处理选择；
        // 用户可决定"仅导入完整题目"（skipIncomplete）或"全部导入"。
        try {
            QualityPreview preview = buildQualityPreview(parseResult, processedRows, skippedCount);
            previewHolder[0] = preview;
            if (preview != null) {
                // 先发旧式统计回调（兼容既有 UI 展示），再走交互决策
                listener.onQualityPreview(preview);
                emitLog(listener, "质量预览: 共 " + preview.totalRows + " 行, 跳过 "
                        + preview.skippedCount + ", 缺字段 " + preview.incompleteCount);

                final QualityPreview previewForConfirm = preview;
                final List<File> chunksForConfirm = chunks;
                emitStage(listener, "preview-confirm", "等待用户确认数据预览…");
                InteractionHandler.Decision d2 = askDecision(listener,
                        h -> h.onPreviewReady(previewForConfirm, chunksForConfirm));
                if (d2.action == InteractionHandler.Decision.CANCEL) {
                    return; // 用户取消
                }
                emitLog(listener, "质量预览决策: " + (skipIncomplete ? "仅导入完整题目" : "全部导入"));
            }
        } catch (Exception e) {
            Log.w(TAG, "数据预览交互失败(不影响导入): " + e.getMessage());
        }

        // ========== 步骤4：缺失字段补充（规则优先，LLM 仅辅助） ==========
        // 原则：程序/规则为主干——能用确定性规则补的字段（如题型由选项/题干特征判定）
        // 先用规则补（零 LLM）；规则补不了的剩余缺失，才在用户确认后用 LLM 辅助推断；
        // 用户也可选择不填充（留空入库，由入库层默认值兜底）。LLM 绝不自动参与。
        // ========== 交互点3：填充前确认（仅针对规则无法补充的剩余） ==========
        JSONArray missingArr = parseResult.optJSONArray("missing");
        // 规则优先填充：题型等可规则判定的字段先补，返回仍缺失的题目（剩余才考虑 LLM）
        JSONArray remainingMissing = ruleFillMissingFields(missingArr, listener);
        int missingCount = remainingMissing != null ? remainingMissing.length() : 0;
        if (interactionHandler != null) {
            try {
                emitStage(listener, "fill-confirm", "等待用户确认智能填充…");
                InteractionHandler.Decision d3 = askDecision(listener,
                        h -> h.onFillReady(previewHolder[0], missingCount));
                if (d3.action == InteractionHandler.Decision.CANCEL) {
                    return; // 用户取消
                }
            } catch (Exception e) {
                Log.w(TAG, "填充交互失败(不影响导入): " + e.getMessage());
            }
        }
        try {
            List<String> fillableCols = buildFillableColumns();
            if (remainingMissing != null && remainingMissing.length() > 0
                    && !fillableCols.isEmpty() && fillEnabled) {
                // 规则已补过一遍，这里只处理剩余缺失（LLM 辅助角色）
                emitStage(listener, "fill", "AI 辅助填充剩余缺失字段("
                        + remainingMissing.length() + " 题)...");
                fillMissingFieldsWithContext(remainingMissing, listener);
                emitLog(listener, "AI 辅助填充完成");
            } else if (remainingMissing != null && remainingMissing.length() > 0
                    && !fillableCols.isEmpty()) {
                emitLog(listener, "智能填充已关闭，剩余缺失字段留空直接入库");
            } else {
                emitLog(listener, "无剩余缺失字段，跳过 AI 辅助填充");
            }
        } catch (Exception e) {
            emitLog(listener, "智能填充异常(不影响入库): " + e.getMessage());
        }

        writeReadyFlag(sessionDir, sourceFile.getName(), processedRows);

        // ========== 交互点4：最终入库确认 ==========
        // 汇总将导入/跳过/失败的预期，用户确认后才真正写入数据库。
        if (interactionHandler != null) {
            try {
                summary.totalRows = processedRows;
                emitStage(listener, "ingest-confirm", "等待用户确认入库…");
                InteractionHandler.Decision d4 = askDecision(listener,
                        h -> h.onFinalConfirm(previewHolder[0], summary));
                if (d4.action == InteractionHandler.Decision.CANCEL) {
                    return; // 用户取消
                }
            } catch (Exception e) {
                Log.w(TAG, "最终确认交互失败(不影响导入): " + e.getMessage());
            }
        }

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
        ingestState.sheetIndex = excelSheetIndex;
        ImportBreakpointStore.save(ingestState);

        // 跳过不完整题目：从分片 CSV 中剔除缺可补充字段（题型/难度/分类/解析）的行，仅导入完整题。
        // 过滤产生新的临时分片文件（不覆盖原分片，保证断点/重导语义不变）。
        if (skipIncomplete) {
            List<File> filtered = filterIncompleteChunks(chunks, parseResult.optJSONArray("missing"));
            if (filtered != null && !filtered.isEmpty()) {
                chunks = filtered;
                emitLog(listener, "已跳过不完整题目，仅导入完整题目（分片 " + chunks.size() + " 个）");
            }
        }

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

    /**
     * 可 LLM 填充的列 = 已映射字段 − 排除类（题干/答案/选项/来源等）。
     * 不硬编码"可填字段名单"：文件映射了哪些字段，哪些就有资格被填充，
     * 只排除按性质不允许猜的字段。
     */
    private List<String> buildFillableColumns() {
        List<String> cols = new ArrayList<>();
        if (currentMapping == null) return cols;
        for (String f : currentMapping.keySet()) {
            if (f == null || f.isEmpty()) continue;
            if (FILL_EXCLUDED_FIELDS.contains(f)) continue;
            if (f.startsWith(FILL_EXCLUDED_OPTION_PREFIX)) continue; // optionA~L
            cols.add(f);
        }
        return cols;
    }

    /**
     * 缺失字段智能填充（LLM 结合上下文推断）。
     * Python missing 条目含 has(缺失字段)/ctx_prev/ctx_next(相邻题上下文)，
     * 只对缺失字段跑 LLM，其他字段保持原值。
     */
    /**
     * 规则优先填充（零 LLM）：用确定性规则补齐缺失字段（题型由选项/题干特征判定），
     * 回写 CSV 分片后返回仍缺失的题目列表——剩余缺失才考虑 LLM 辅助，
     * 贯彻"程序/规则为主干、LLM 仅辅助"的导入架构。
     */
    private JSONArray ruleFillMissingFields(JSONArray missing, ImportListener listener) {
        if (missing == null || missing.length() == 0) return missing;
        List<String> fillableCols = buildFillableColumns();
        if (fillableCols.isEmpty() || !fillableCols.contains("questionType")) {
            return missing; // 无可规则填充字段
        }
        Map<String, JSONObject> fillsByChunk = new LinkedHashMap<>();
        JSONArray remaining = new JSONArray();
        int ruleFilled = 0;
        for (int i = 0; i < missing.length(); i++) {
            JSONObject m = missing.optJSONObject(i);
            if (m == null) continue;
            JSONObject has = m.optJSONObject("has");
            boolean typeMissing = has == null || !has.optBoolean("questionType", false);
            String ruleType = typeMissing ? inferQuestionTypeByRule(m) : null;
            if (ruleType != null) {
                ruleFilled++;
                String chunk = m.optString("chunk");
                JSONObject chunkFills = fillsByChunk.get(chunk);
                if (chunkFills == null) {
                    chunkFills = new JSONObject();
                    fillsByChunk.put(chunk, chunkFills);
                }
                try {
                    JSONObject fill = new JSONObject();
                    fill.put("questionType", ruleType);
                    chunkFills.put(String.valueOf(m.optInt("row")), fill);
                } catch (Exception ignored) {
                }
                // 是否还有其他缺失字段（除题型外）→ 保留给 LLM 辅助
                boolean hasOtherMissing = false;
                if (has != null) {
                    for (String f : fillableCols) {
                        if ("questionType".equals(f)) continue;
                        if (!has.optBoolean(f, false)) { hasOtherMissing = true; break; }
                    }
                }
                if (hasOtherMissing) remaining.put(m);
            } else {
                remaining.put(m);
            }
        }
        if (ruleFilled > 0) {
            ImportPythonBridge python = ImportPythonBridge.getInstance(context);
            String fillFieldsJson = new JSONArray(fillableCols).toString();
            int chunksWritten = 0;
            for (Map.Entry<String, JSONObject> e : fillsByChunk.entrySet()) {
                JSONObject r = python.applyFills(e.getKey(), e.getValue().toString(), fillFieldsJson);
                String err = extractError(r);
                if (err != null) {
                    emitLog(listener, "规则填充回写失败(不影响入库): " + err);
                } else {
                    chunksWritten++;
                }
            }
            emitLog(listener, "规则优先填充题型 " + ruleFilled + " 题（零模型调用）"
                    + (remaining.length() > 0 ? "，剩余 " + remaining.length() + " 题交 AI 辅助" : ""));
        }
        return remaining;
    }

    private void fillMissingFieldsWithContext(JSONArray missing, ImportListener listener) {
        List<String> fillableCols = buildFillableColumns();
        if (fillableCols.isEmpty()) return;

        // 按 chunk 分组收集（规则预筛的 fill 与 LLM fill 按 row 合并）
        Map<String, JSONObject> fillsByChunk = new LinkedHashMap<>();

        // 第一遍：规则预筛（零模型）——题型可由规则判定的题直接回写，不进 LLM 批量；
        // 剩余真正缺字段的题按估算长度动态分批（防输入逼近 4K 把输出挤出上下文）
        List<String> infos = new ArrayList<>();
        List<Integer> pendingIdx = new ArrayList<>();
        int batchChars = 0;

        int total = missing.length();
        for (int i = 0; i < total; i++) {
            if (cancelled) return;
            JSONObject m = missing.optJSONObject(i);
            if (m == null) continue;
            JSONObject has = m.optJSONObject("has");

            // 规则优先：题型可判定则零模型直接填（不占 LLM 批量）
            JSONObject ruleFill = new JSONObject();
            if (fillableCols.contains("questionType")
                    && (has == null || !has.optBoolean("questionType", false))) {
                String ruleType = inferQuestionTypeByRule(m);
                if (ruleType != null) {
                    try {
                        ruleFill.put("questionType", ruleType);
                    } catch (Exception ignored) {
                    }
                }
            }

            // 该题是否仍需 LLM：存在未被规则覆盖且缺失的字段
            boolean needLlm = false;
            for (String f : fillableCols) {
                if (ruleFill.has(f)) continue;
                boolean fieldMissing = has == null || !has.optBoolean(f, false);
                if (fieldMissing) {
                    needLlm = true;
                    break;
                }
            }
            if (needLlm) {
                String info = buildFillInfoWithContext(m);
                int est = info.length() + 80; // 含编号/分隔开销
                if (!infos.isEmpty() && batchChars + est > FILL_BATCH_CHARS) {
                    applyFillBatch(infos, pendingIdx, missing, fillsByChunk, fillableCols, listener);
                    infos.clear();
                    pendingIdx.clear();
                    batchChars = 0;
                }
                infos.add(info);
                pendingIdx.add(i);
                batchChars += est;
            }
            if (ruleFill.length() > 0) {
                putFillMerge(m, ruleFill, fillsByChunk);
            }
        }
        if (!infos.isEmpty()) {
            applyFillBatch(infos, pendingIdx, missing, fillsByChunk, fillableCols, listener);
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

    /** 对一批需 LLM 填充的题执行批量推理并回写（仅补规则未覆盖的缺失字段） */
    private void applyFillBatch(List<String> infos, List<Integer> pendingIdx,
                                JSONArray missing, Map<String, JSONObject> fillsByChunk,
                                List<String> fillableCols, ImportListener listener) {
        if (infos.isEmpty()) return;
        List<ImportLlmEngine.FillResult> fills =
                engine.runFieldFillBatchInfer(infos, fillableCols, docHint);
        for (int k = 0; k < pendingIdx.size(); k++) {
            JSONObject m = missing.optJSONObject(pendingIdx.get(k));
            if (m == null) continue;
            ImportLlmEngine.FillResult fr = k < fills.size() ? fills.get(k) : null;
            JSONObject fill = new JSONObject();
            try {
                JSONObject has = m.optJSONObject("has");
                // 动态遍历可填字段：从 LLM 结果 fields 中取对应值（缺失字段才回写）
                if (fr != null && fr.valid && fr.fields != null) {
                    for (String f : fillableCols) {
                        // 该字段是否缺失（has 里 false 或缺省）
                        boolean fieldMissing = has == null || !has.optBoolean(f, false);
                        if (!fieldMissing) continue;
                        Object v = fr.fields.get(f);
                        if (v == null) continue;
                        String sv = String.valueOf(v).trim();
                        if (sv.isEmpty()) continue;
                        // difficulty 归一化 1-3
                        if ("difficulty".equals(f)) {
                            try {
                                int d = Integer.parseInt(sv);
                                if (d < 1 || d > 3) d = 1;
                                fill.put(f, d);
                            } catch (Exception ignored) {
                            }
                            continue;
                        }
                        fill.put(f, sv);
                    }
                }
            } catch (Exception ignored) {
            }
            if (fill.length() == 0) continue; // 无实际填充，跳过回写
            putFillMerge(m, fill, fillsByChunk);
        }
        emitProgress(listener, pendingIdx.isEmpty() ? 0
                : pendingIdx.get(pendingIdx.size() - 1) + 1, missing.length(), "智能填充");
    }

    /** 按 row 合并写入 chunk fills（规则 fill 与 LLM fill 落在同一 row 时合并而非覆盖） */
    private void putFillMerge(JSONObject m, JSONObject fill, Map<String, JSONObject> fillsByChunk) {
        if (fill == null || fill.length() == 0) return;
        String chunk = m.optString("chunk");
        JSONObject chunkFills = fillsByChunk.get(chunk);
        if (chunkFills == null) {
            chunkFills = new JSONObject();
            fillsByChunk.put(chunk, chunkFills);
        }
        String row = String.valueOf(m.optInt("row"));
        JSONObject existing = chunkFills.optJSONObject(row);
        if (existing == null) {
            existing = new JSONObject();
            try {
                chunkFills.put(row, existing);
            } catch (Exception ignored) {
            }
        }
        try {
            java.util.Iterator<String> it = fill.keys();
            while (it.hasNext()) {
                String k = it.next();
                existing.put(k, fill.opt(k));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 题型规则识别（规则优先，不依赖 LLM）：根据选项/题干/答案特征判定题型。
     * 返回标准题型名（单选题/多选题/判断题/填空题/简答题），无法判定返回 null。
     */
    private String inferQuestionTypeByRule(JSONObject m) {
        if (m == null) return null;
        try {
            JSONObject options = m.optJSONObject("options");
            String answer = m.optString("answerText", "");
            if (answer.isEmpty()) {
                // options 里的值可作为答案线索（Python 侧 options 是各选项文本）
            }
            if (options == null) return null;

            // 收集非空选项
            java.util.List<String> nonEmpty = new ArrayList<>();
            java.util.Iterator<String> it = options.keys();
            while (it.hasNext()) {
                String v = options.optString(it.next(), "");
                if (!v.isEmpty()) nonEmpty.add(v);
            }
            if (nonEmpty.isEmpty()) return null;

            // 判断特征：所有选项都是"对/错"或题干含"是否正确/对不对/是否"
            String joined = String.join(" ", nonEmpty);
            String question = m.optString("questionText", "");
            boolean judgeLike = joined.contains("对") && joined.contains("错")
                    || question.contains("是否正确") || question.contains("对不对")
                    || question.contains("是否");
            // 选项数量：2 个且是对错 → 判断题；≥2 个不同选项 → 选择题
            if (judgeLike && nonEmpty.size() <= 2) {
                return "判断题";
            }
            if (nonEmpty.size() >= 2) {
                // 选项是否像字母标记（A/B/C/D）：区分单选/多选较难，保守判单选
                return nonEmpty.size() > 4 ? "多选题" : "单选题";
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 构建填充提示信息：题干 + 选项 + 缺失字段 + 前后题上下文（供 LLM 参考推断） */
    private String buildFillInfoWithContext(JSONObject m) {
        StringBuilder sb = new StringBuilder();
        // 题干截断：保留题意同时控制每批输入 token（提速关键）
        String q = m.optString("questionText", "");
        sb.append("题干:").append(q.length() > 100 ? q.substring(0, 100) : q);
        JSONObject options = m.optJSONObject("options");
        if (options != null) {
            java.util.Iterator<String> it = options.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = options.optString(k, "");
                if (!v.isEmpty()) {
                    if (v.length() > 40) v = v.substring(0, 40);
                    sb.append(' ').append(k).append(':').append(v);
                }
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
        // 前后题上下文：仅取首 40 字（长题干重复 2 次是输入超限/变慢主因）
        String prev = m.optString("ctx_prev", "");
        String next = m.optString("ctx_next", "");
        if (!prev.isEmpty()) sb.append(" 上一题[").append(prev.length() > 40 ? prev.substring(0, 40) : prev).append("]");
        if (!next.isEmpty()) sb.append(" 下一题[").append(next.length() > 40 ? next.substring(0, 40) : next).append("]");
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

    /**
     * 下发给 Python 的 fill_fields = 已映射字段 ∩ 真实表列 − 排除类。
     * 让 Python 只收集"映射中存在且可填充"字段的缺失行，不硬编码字段名单。
     */
    private List<String> buildFillFieldList(Set<String> tableCols) {
        List<String> fills = new ArrayList<>();
        if (currentMapping != null) {
            for (String f : currentMapping.keySet()) {
                if (f == null || f.isEmpty()) continue;
                if (FILL_EXCLUDED_FIELDS.contains(f)) continue;
                if (f.startsWith(FILL_EXCLUDED_OPTION_PREFIX)) continue;
                // 必须是真实数据库列（虚拟字段 optionsCombined 等排除）
                if (tableCols != null && !tableCols.isEmpty() && !tableCols.contains(f)) continue;
                fills.add(f);
            }
        }
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
        summary.elapsedMs = System.currentTimeMillis() - startMs;
        // 单文件导入：同步生成缺字段报告供结果弹窗展示（批量模式由入口统一生成）
        if (!batchMode) {
            summary.issuesMessage = exportIssuesReport(listener);
        }
        emitStage(listener, "done", "导入完成");
        emitLog(listener, "统计: 新增 " + summary.imported + " / 重复 " + summary.duplicated
                + " / 失败 " + summary.failed + " (耗时 " + summary.elapsedMs + "ms)");
        if (listener != null) {
            if (multiSheetMode) {
                // 多工作表模式：累计统计，全部 sheet 完成后由 runSheets 统一回调 onComplete
                ImportSummary t = multiTotal;
                if (t == null) {
                    t = new ImportSummary();
                    multiTotal = t;
                }
                t.imported += summary.imported;
                t.duplicated += summary.duplicated;
                t.failed += summary.failed;
                t.totalRows += summary.totalRows;
                t.elapsedMs += summary.elapsedMs;
                emitStage(listener, "sheet-done", "工作表完成: 新增 " + summary.imported
                        + " 题（累计 " + t.imported + " 题）");
            } else {
                mainHandler.post(() -> listener.onComplete(summary));
            }
        }
    }

    /**
     * 多工作表导入：对选定工作表逐个执行完整导入流程（每个 sheet 独立采样/映射/解析/入库），
     * 全部完成后汇总一次 onComplete；中间每个 sheet 完成发 sheet-done 阶段信号。
     * 断点按文件+工作表隔离，sheet 间互不串扰；引擎复用避免重复加载本地模型。
     */
    public void runSheets(File sourceFile, List<Integer> sheetIndexes, ImportListener listener) {
        runSheets(sourceFile, sheetIndexes, null, listener);
    }

    /**
     * 多工作表导入：对选定工作表逐个执行完整导入流程（每个 sheet 独立采样/映射/解析/入库），
     * 全部完成后汇总一次 onComplete；中间每个 sheet 完成发 sheet-done 阶段信号。
     * 断点按文件+工作表隔离，sheet 间互不串扰；引擎复用避免重复加载本地模型。
     *
     * @param inferredTypes 与 sheetIndexes 对齐的工作表名推断题型（如"单选题"），
     *                      可为 null（不填充题型）；源表无题型列时用于填充 questionType。
     */
    public void runSheets(File sourceFile, List<Integer> sheetIndexes,
                          java.util.List<String> inferredTypes, ImportListener listener) {
        cancelled = false;
        batchMode = true; // 缺字段报告由本入口统一生成一次
        multiSheetMode = true;
        multiTotal = null;
        executor.execute(() -> {
            try {
                if (sourceFile == null || !sourceFile.exists()) {
                    emitError(listener, "源文件不存在");
                    return;
                }
                if (sheetIndexes == null || sheetIndexes.isEmpty()) {
                    emitError(listener, "未选择任何工作表");
                    return;
                }
                int total = sheetIndexes.size();
                int done = 0;
                for (int i = 0; i < sheetIndexes.size(); i++) {
                    if (cancelled) {
                        emitLog(listener, "已取消，剩余工作表未导入");
                        break;
                    }
                    Integer idx = sheetIndexes.get(i);
                    done++;
                    excelSheetIndex = idx;
                    // 当前 sheet 的推断题型（源表无题型列时兜底填充）
                    defaultQuestionType = (inferredTypes != null && i < inferredTypes.size())
                            ? inferredTypes.get(i) : null;
                    if (defaultQuestionType != null) {
                        emitLog(listener, "工作表题型推断: " + defaultQuestionType);
                    }
                    emitStage(listener, "sheet", "开始导入工作表 " + done + "/" + total);
                    runSync(sourceFile, listener);
                }
                ImportSummary t = multiTotal != null ? multiTotal : new ImportSummary();
                t.mappingSource = "ai";
                t.issuesMessage = exportIssuesReport(listener);
                emitStage(listener, "done", "全部工作表导入完成: 新增 " + t.imported + " 题");
                if (listener != null) {
                    mainHandler.post(() -> listener.onComplete(t));
                }
            } catch (Throwable t) {
                Log.e(TAG, "多工作表导入异常: " + t.getMessage(), t);
                emitError(listener, "多工作表导入异常: " + t.getMessage());
            } finally {
                multiSheetMode = false;
                batchMode = false;
                // 多 sheet 全部结束后：本地模型保持常驻（复用对话模块内核），不再主动释放
            }
        });
    }

    /**
     * 构建质量预览：解析完成后统计总行数/跳过行/缺失字段分布。
     *
     * @param parseResult  Python parse_file 返回（含 missing 数组，每条含 has:{字段:bool}）
     * @param processedRows 已处理源行数（total_rows）
     * @param skippedCount  题干为空/重复被丢弃的行数（Python skipped_count）
     */
    private QualityPreview buildQualityPreview(JSONObject parseResult, long processedRows, long skippedCount) {
        QualityPreview preview = new QualityPreview();
        preview.totalRows = processedRows;
        preview.writtenRows = parseResult.optLong("written_rows", processedRows);
        preview.skippedCount = skippedCount;
        preview.duplicateCount = parseResult.optLong("duplicate_count", 0);
        preview.emptyQuestionCount = parseResult.optLong("empty_question_count", 0);
        // 已映射字段集：缺失判断只针对映射中出现的字段（不硬编码非映射字段）
        if (currentMapping != null) {
            preview.mappedFields.addAll(currentMapping.keySet());
        }

        JSONArray missingArr = parseResult.optJSONArray("missing");
        if (missingArr != null) {
            for (int i = 0; i < missingArr.length(); i++) {
                JSONObject m = missingArr.optJSONObject(i);
                if (m == null) continue;
                preview.incompleteCount++;
                JSONObject has = m.optJSONObject("has");
                if (has == null) continue;
                // 统计各字段缺失数：has 里值为 false 的字段即缺失
                for (java.util.Iterator<String> it = has.keys(); it.hasNext(); ) {
                    String field = it.next();
                    if (!has.optBoolean(field, true)) {
                        preview.missingByField.merge(field, 1L, Long::sum);
                    }
                }
            }
        }

        // 文件内重复明细：题干+答案完全相同（仅保留 1 题）
        JSONArray dupArr = parseResult.optJSONArray("duplicates");
        if (dupArr != null) {
            for (int i = 0; i < dupArr.length(); i++) {
                JSONObject d = dupArr.optJSONObject(i);
                if (d == null) continue;
                QualityPreview.DuplicateDetail det = new QualityPreview.DuplicateDetail();
                det.question = d.optString("question", "");
                det.answer = d.optString("answer", "");
                JSONArray rows = d.optJSONArray("rows");
                if (rows != null) {
                    for (int j = 0; j < rows.length(); j++) {
                        det.rows.add(rows.optInt(j, 0));
                    }
                }
                if (det.rows.size() >= 2) {
                    det.reason = "该题在文件内出现 " + det.rows.size() + " 次（第 "
                            + joinRows(det.rows) + " 题），题干与答案完全相同，仅保留 1 题";
                    preview.duplicateDetails.add(det);
                }
            }
        }
        // 同题干不同答案（近似重复）：均保留，提示用户核对
        JSONArray stemArr = parseResult.optJSONArray("stem_variants");
        if (stemArr != null) {
            for (int i = 0; i < stemArr.length(); i++) {
                JSONObject v = stemArr.optJSONObject(i);
                if (v == null) continue;
                QualityPreview.DuplicateDetail det = new QualityPreview.DuplicateDetail();
                det.question = v.optString("question", "");
                JSONArray rows = v.optJSONArray("rows");
                if (rows != null) {
                    for (int j = 0; j < rows.length(); j++) {
                        det.rows.add(rows.optInt(j, 0));
                    }
                }
                if (det.rows.size() >= 2) {
                    det.reason = "题干相同但答案写法不同（第 " + joinRows(det.rows)
                            + " 题），两题均已保留，请核对是否需要合并";
                    preview.stemVariantDetails.add(det);
                }
            }
        }
        return preview;
    }

    /**
     * 从题库说明推断题型（说明驱动）：取说明首句（如"单选题说明（说明部分请勿删除）"、
     * "本卷为安全知识判断题"）匹配标准题型词。说明是每个文件的动态内容，
     * 对"按题型分 sheet / 说明声明题型"的文件比工作表名推断更准确。无命中返回 null。
     */
    private String inferQuestionTypeFromDoc(String docHint) {
        if (docHint == null || docHint.trim().isEmpty()) return null;
        String head = docHint.trim();
        // 题型声明通常在首句/首行；截断防长文本尾部干扰（如"其他题型请录入其他Sheet"）
        int cut = head.indexOf('\n');
        if (cut > 0) head = head.substring(0, cut);
        if (head.length() > 80) head = head.substring(0, 80);
        String type = com.oilquiz.app.ai.importing.ExcelSheetPicker.inferQuestionTypeFromName(head);
        return ("未分类".equals(type) || type.isEmpty()) ? null : type;
    }

    /**
     * 说明交叉校验：题库说明中提到的关键列（正确答案/选项/填空项/知识点/难度/解析/题目等），
     * 若在当前表头中存在却未被映射，提示用户/映射确认时核对——利用说明动态发现漏映射，
     * 不依赖硬编码的完整列名清单。
     */
    private void logMappingVsDocHint(List<String> headers, Map<String, String> mapping,
                                     String docHint, ImportListener listener) {
        if (docHint == null || docHint.isEmpty() || headers == null || mapping == null) return;
        String[] words = {"正确答案", "选项", "填空项", "知识点", "难度", "解析", "题目", "题干", "答案", "序号"};
        for (String w : words) {
            if (!docHint.contains(w)) continue;
            for (String h : headers) {
                if (h == null || h.trim().isEmpty()) continue;
                if (h.contains(w) && !mapping.containsValue(h)) {
                    emitLog(listener, "题库说明提到「" + w + "」，表头列「" + h.trim()
                            + "」未被映射，请在字段映射确认时核对");
                    break;
                }
            }
        }
    }

    /**
     * 从题库说明提取选项分隔符（说明驱动，如"多个备选答案用竖线'/'分隔"→"/"、
     * "选项用分号分隔"→";"）。不同文件说明不同，分隔符随说明动态变化；
     * 说明指定时优先于自动检测（Python _split_options 用 preferred 参数）。无命中返回 null。
     * 上下文限定：分隔符描述必须与 选项/答案/备选/内容 相关，避免把
     * "多个知识点用竖线|分割"（知识点分隔符）误当选项分隔符。
     */
    private String extractOptionDelimiter(String docHint) {
        if (docHint == null || docHint.isEmpty()) return null;
        final String[] ctxWords = {"选项", "答案", "备选", "内容", "填空"};
        // 中文分隔符词 → 实际字符
        java.util.Map<String, String> words = new java.util.HashMap<>();
        words.put("竖线", "|");
        words.put("分号", ";");
        words.put("顿号", "、");
        words.put("逗号", ",");
        words.put("斜杠", "/");
        // 1. 引号包裹的单字符分隔符（最精确）：用竖线'/'分隔 / 以"|"分隔
        java.util.regex.Matcher qm = java.util.regex.Pattern
                .compile("['\"“”‘’]([|｜;；、，,/／~～\\s])['\"“”‘’]").matcher(docHint);
        while (qm.find()) {
            String d = qm.group(1);
            if (d.trim().isEmpty()) continue;
            int s = Math.max(0, qm.start() - 30);
            int en = Math.min(docHint.length(), qm.end() + 30);
            if (hasContextWord(docHint.substring(s, en), ctxWords)) return d;
        }
        // 2. 中文词 + 动作词（用X分隔/以X分隔/X隔开/X分割/分隔符为X）
        for (java.util.Map.Entry<String, String> e : words.entrySet()) {
            String kw = e.getKey();
            String[] pats = {"用" + kw, "以" + kw, kw + "分隔", kw + "分割", kw + "隔开", "分隔符" + kw};
            for (String pat : pats) {
                int idx = docHint.indexOf(pat);
                if (idx >= 0) {
                    int s = Math.max(0, idx - 30);
                    int en = Math.min(docHint.length(), idx + pat.length() + 30);
                    if (hasContextWord(docHint.substring(s, en), ctxWords)) {
                        return e.getValue();
                    }
                }
            }
        }
        // 3. 通用短模式：用X分隔 / 以X分隔 / X隔开 / 分隔符为X（X 为 1~2 字符标点）
        String[] pats = {
                "用[（(]?([^，。；;\\s]{1,2})[）)]?分隔",
                "以[（(]?([^，。；;\\s]{1,2})[）)]?分隔",
                "([^，。；;\\s]{1,2})隔开",
                "分隔符[为是]([^，。；;\\s]{1,2})",
        };
        for (String p : pats) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(p).matcher(docHint);
            while (m.find()) {
                String d = m.group(1).trim();
                if (d.isEmpty()) continue;
                int s = Math.max(0, m.start() - 30);
                int en = Math.min(docHint.length(), m.end() + 30);
                if (hasContextWord(docHint.substring(s, en), ctxWords)) {
                    if (words.containsKey(d)) d = words.get(d);
                    if (d.length() <= 2) return d;
                }
            }
        }
        return null;
    }

    /** 片段是否含任一上下文词 */
    private static boolean hasContextWord(String around, String[] words) {
        if (around == null) return false;
        for (String w : words) {
            if (around.contains(w)) return true;
        }
        return false;
    }

    /**
     * 检测题库说明是否描述填空格式（双中括号【】/中括号/填空空位）。
     * 用于提示"说明描述的填空格式"已识别，辅助确认填空答案列映射。
     */
    private boolean docDescribesFillFormat(String docHint) {
        if (docHint == null) return false;
        return docHint.contains("【】") || docHint.contains("双中括号")
                || docHint.contains("中括号") || docHint.contains("填空空位")
                || (docHint.contains("填空") && docHint.contains("【"));
    }

    /**
     * 本地词典映射完整度：题干 + 答案 + （聚合选项列 或 至少一个独立选项列）都识别到，
     * 视为完整可直接采用（跳过 LLM 映射）。完整度不足（特殊列名/复杂模板）才交给
     * LLM 补强，避免每次导入都调用本地模型（推理慢、占用资源）。
     */
    private boolean ruleMappingSufficient(Map<String, String> mapping) {
        if (mapping == null) return false;
        if (!mapping.containsKey("questionText") || !mapping.containsKey("correctAnswer")) {
            return false;
        }
        if (mapping.containsKey(VIRTUAL_FIELD_OPTIONS_COMBINED)) return true;
        for (char c = 'A'; c <= 'L'; c++) {
            if (mapping.containsKey("option" + c)) return true;
        }
        return false;
    }

    /** 行号列表 → "112、130"（中文顿号分隔） */
    private static String joinRows(java.util.List<Integer> rows) {
        if (rows == null || rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append('、');
            sb.append(rows.get(i));
        }
        return sb.toString();
    }

    /**
     * 过滤不完整题目：从分片 CSV 中剔除 missing 记录的行（缺题型/难度/分类/解析），
     * 生成新的临时分片文件（写入会话目录 .incomplete_filtered 后缀）。
     *
     * @param chunks  原始分片文件列表
     * @param missing Python missing 数组（每条含 chunk=分片文件名, row=分片内数据行索引 0-based）
     * @return 过滤后的分片列表；无法处理时返回原始分片（不阻塞导入）
     */
    private List<File> filterIncompleteChunks(List<File> chunks, JSONArray missing) {
        if (missing == null || missing.length() == 0) return chunks;
        // chunk 文件名（如 import_part_0001.csv）→ 需要剔除的数据行号集合
        Map<String, Set<Long>> dropByChunk = new HashMap<>();
        for (int i = 0; i < missing.length(); i++) {
            JSONObject m = missing.optJSONObject(i);
            if (m == null) continue;
            String chunkName = m.optString("chunk", "");
            long row = m.optLong("row", -1);
            if (chunkName.isEmpty() || row < 0) continue;
            dropByChunk.computeIfAbsent(chunkName, k -> new HashSet<>()).add(row);
        }
        if (dropByChunk.isEmpty()) return chunks;

        List<File> filteredChunks = new ArrayList<>();
        for (File chunk : chunks) {
            String name = chunk.getName();
            Set<Long> dropRows = dropByChunk.get(name);
            if (dropRows == null || dropRows.isEmpty()) {
                filteredChunks.add(chunk);
                continue;
            }
            File out = new File(chunk.getParentFile(), name.replace(".csv", ".incomplete_filtered.csv"));
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(chunk), StandardCharsets.UTF_8));
                 java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                         new java.io.FileOutputStream(out), StandardCharsets.UTF_8)) {
                String line;
                long lineIdx = -1; // 0 为表头，数据行从 1 开始
                while ((line = r.readLine()) != null) {
                    lineIdx++;
                    if (lineIdx == 0) {
                        w.write(line);
                        w.write("\r\n");
                        continue;
                    }
                    // 数据行索引（0-based）= 文件行号 - 1
                    long dataRow = lineIdx - 1;
                    if (dropRows.contains(dataRow)) {
                        continue; // 跳过不完整题
                    }
                    w.write(line);
                    w.write("\r\n");
                }
                filteredChunks.add(out);
            } catch (Exception e) {
                Log.w(TAG, "过滤分片失败(" + name + ")，保留原分片: " + e.getMessage());
                filteredChunks.add(chunk);
            }
        }
        return filteredChunks;
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
     * 缓存映射与本地词典合并：缓存项优先，词典按表头别名补齐缺失的标准字段。
     * 用于缓存命中场景——历史/精简缓存可能只有题干/答案/选项等核心字段，
     * 若表头明明存在题型/难度/分类等列却未被映射，入库就会丢字段，此方法兜底补全。
     */
    private Map<String, String> mergeMappingWithAlias(Map<String, String> cached,
                                                      List<String> headers) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (cached != null) merged.putAll(cached);
        Map<String, Integer> built = FieldMappingRegistry.buildMappingFromHeaders(headers);
        for (Map.Entry<String, Integer> e : built.entrySet()) {
            int idx = e.getValue();
            if (idx >= 0 && idx < headers.size()) {
                String field = e.getKey();
                if (!merged.containsKey(field)) {
                    merged.put(field, headers.get(idx));
                }
            }
        }
        return merged;
    }

    /**
     * 缓存映射有效性校验：映射引用的每个源列名都必须存在于当前表头（去空白比较）。
     * 只要有一个源列在当前表头中找不到，说明表头已变化（列被删除/改名/换顺序），
     * 缓存不可再用——应放弃缓存重新映射，防止旧映射错位套用导致字段张冠李戴。
     */
    private static boolean cacheMappingMatchesHeaders(Map<String, String> mapping,
                                                      List<String> headers) {
        if (mapping == null || mapping.isEmpty()) return false;
        java.util.Set<String> headerSet = new java.util.HashSet<>();
        if (headers != null) {
            for (String h : headers) {
                headerSet.add(h == null ? "" : h.trim());
            }
        }
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            String src = e.getValue();
            if (src == null || src.trim().isEmpty()) continue;
            if (!headerSet.contains(src.trim())) return false;
        }
        return true;
    }

    /**
     * 可选项/聚合选项列规则识别（纯程序）：表头是聚合选项列（可选项/选项/备选答案等）且
     * 映射中没有独立选项列时，设为虚拟字段 optionsCombined，Python 按分隔符自动拆分 A~L。
     * 在任何映射路径（AI/词典）之后调用，确保程序拆分一定生效。
     * <p>纠正逻辑：AI/词典可能把聚合列误映射成 optionA（源列名本身是聚合选项列且无独立
     * optionB~D），此时强制改为 optionsCombined，否则整串选项会原样落入 optionA。
     */
    private Map<String, String> ensureCombinedOptionsMapping(Map<String, String> mapping,
                                                             List<String> headers) {
        if (mapping == null) mapping = new LinkedHashMap<>();
        if (mapping.containsKey("optionA")) {
            String srcA = mapping.get("optionA");
            boolean hasOtherOptions = mapping.containsKey("optionB")
                    || mapping.containsKey("optionC") || mapping.containsKey("optionD");
            if (!hasOtherOptions && srcA != null && isAggregateHeader(srcA)) {
                mapping.remove("optionA");
                mapping.put(VIRTUAL_FIELD_OPTIONS_COMBINED, srcA);
                emitLog2("聚合选项列识别(纠正): " + srcA + " → optionsCombined");
            }
            return mapping; // 真正独立选项列，无需聚合
        }
        if (mapping.containsKey(VIRTUAL_FIELD_OPTIONS_COMBINED)) return mapping;
        if (headers == null) return mapping;
        for (String h : headers) {
            if (isAggregateHeader(h)) {
                mapping.put(VIRTUAL_FIELD_OPTIONS_COMBINED, h.trim());
                emitLog2("聚合选项列识别(规则): " + h.trim() + " → optionsCombined");
                break;
            }
        }
        return mapping;
    }

    /** 聚合选项列判定：可选项/选项/备选答案/选项内容/ABCD选项/options/choices/选择项，
     * 排除"选项A/B/C/D"等独立选项列。 */
    private static boolean isAggregateHeader(String h) {
        if (h == null) return false;
        String t = h.trim().toLowerCase();
        String[] hints = {"可选项", "选项", "备选答案", "备选项", "选项内容",
                "abcd选项", "abcd", "options", "choices", "选择项"};
        for (String hint : hints) {
            if (t.equals(hint)) return true;
        }
        // 包含聚合词（如"可选项(多选)"、"选项列表"），但"选项A/B"独立列不算
        if (t.contains("可选项") || t.contains("备选答案") || t.contains("备选项")
                || t.contains("选择项") || t.contains("选项内容")) {
            return true;
        }
        // "选项"前缀但非"选项A/B/C"独立列
        if (t.startsWith("选项")
                && !t.matches("选项[a-lA-L甲乙丙丁戊己庚辛壬癸子丑1-9一二三四五六七八九十].*")) {
            return true;
        }
        return false;
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

    // ==================== 纯规则表头识别（离线兜底，零 LLM） ====================

    /** 纯规则表头识别结果（LLM 不可用时离线兜底） */
    private static class RuleHeaderResult {
        boolean valid;
        /** 真实表头行号（0-based） */
        int headerRow = -2;
        /** 该行内容作为表头列名（与 Python 按 header_row 取行一致，保证列对位） */
        List<String> mergedHeaders;
        /** 标准字段 → 列索引（基于 mergedHeaders，由别名词典生成） */
        Map<String, Integer> mapping;
    }

    /**
     * 纯规则表头识别（离线无模型兜底）：
     * 扫描前 12 行原始内容，用 {@link FieldMappingRegistry} 别名词典对每行打分
     * （该行能映射出多少个标准字段），取"题干+答案齐全且映射字段最多"的行为表头行。
     * <p>
     * 零 LLM 依赖，列名以该行原文为准，与 Python 侧按 header_row 取行完全一致，
     * 保证后续解析列对位不偏移。双子行模板（子表头 A/B/C/D）由 Python
     * {@code _detect_header} 自动合并处理，此处不做合并（那种表头含中文关键词，
     * 不会走到本兜底）。
     */
    private RuleHeaderResult ruleBasedHeaderInfer(JSONArray rawRows) {
        RuleHeaderResult result = new RuleHeaderResult();
        if (rawRows == null || rawRows.length() == 0) return result;

        int n = Math.min(rawRows.length(), 30);
        int bestRow = -1;
        List<String> bestHeaders = null;
        Map<String, Integer> bestMapping = null;
        int bestScore = -1;

        for (int i = 0; i < n; i++) {
            JSONArray rowArr = rawRows.optJSONArray(i);
            if (rowArr == null) continue;
            List<String> cells = new ArrayList<>();
            for (int j = 0; j < rowArr.length(); j++) {
                String v = rowArr.optString(j, "");
                cells.add(v == null ? "" : v.trim());
            }
            Map<String, Integer> m = FieldMappingRegistry.buildMappingFromHeaders(cells);
            int score = m.size();
            // 必须同时识别出题干与答案，否则不是有效表头行
            if (m.containsKey("questionText") && m.containsKey("correctAnswer")
                    && score > bestScore) {
                bestScore = score;
                bestRow = i;
                bestHeaders = cells;
                bestMapping = m;
            }
        }

        if (bestRow < 0 || bestHeaders == null || bestMapping == null) return result;
        result.valid = true;
        result.headerRow = bestRow;
        result.mergedHeaders = bestHeaders;
        result.mapping = bestMapping;
        return result;
    }
}
