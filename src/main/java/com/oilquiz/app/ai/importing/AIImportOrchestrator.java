package com.oilquiz.app.ai.importing;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.oilquiz.app.ai.agent.ToolResultInterpreter;
import com.oilquiz.app.ai.engine.ALChat;
import com.oilquiz.app.ai.importing.model.AIImportResult;
import com.oilquiz.app.ai.model.ModelMemoryManager;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.OnlineInferenceService;
import com.oilquiz.app.database.DatabaseManager;
import com.oilquiz.app.model.ImportHistory;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.repository.ImportHistoryRepository;
import com.oilquiz.app.util.CharsetDetector;
import com.oilquiz.app.util.GarbledTextFixer;
import com.oilquiz.app.util.ImportDebugTracer;
import com.oilquiz.app.util.fileparser.FileContentExtractor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 题库 AI 导入编排引擎（v5：AIImportAgent 驱动 — Agent 式多轮自纠 + 流式输出）。
 * <p>
 * 串联 4 阶段流水线：
 * <ol>
 *   <li>PROFILE — 文件识别 + 内容提取（复用项目已有解析库 POI/iText/jsoup）</li>
 *   <li>FORMAT — 格式自动检测 + 规则引擎快速解析（零 LLM 调用）</li>
 *   <li>INGEST — AIImportAgent 智能抽取（Agent 式：分析→抽取→校验→自纠→输出）</li>
 *   <li>DONE — 质量网关 + 校验 + 入库 + 结果汇总</li>
 * </ol>
 * <p>
 * v5 核心改进：
 * <ul>
 *   <li>引入 AIImportAgent 专用 Agent 组件，职责分离：Orchestrator 管文件/分块/入库，Agent 管 AI 推理</li>
 *   <li>Agent 式多轮自纠：首次输出校验失败时，构建修正 prompt 让 AI 重新输出（最多 2 轮）</li>
 *   <li>动态上下文管理：在线模型 4096 token 窗口，本地模型 2048 token 保守窗口</li>
 *   <li>流式 token 输出：AI 推理过程实时回调 UI，提升用户体验</li>
 *   <li>在线模型优先 + 自动降级：在线失败自动降级到本地模型（AUTO/ONLINE_PREFERRED 模式）</li>
 *   <li>Few-shot + 强格式约束 prompt：显著提升 JSON 格式遵循率</li>
 * </ul>
 * 所有 IO/推理在单线程 executor 上执行，listener 回调通过主线程 Handler 切回 UI 线程。
 */
public class AIImportOrchestrator {

    private static final String TAG = "AIImportOrchestrator";

    // ======================== Token 预算常量 ========================

    private static final int CONTEXT_WINDOW_MAX = 2048;
    private static final int CONTEXT_BUFFER = 256;
    /** v7: 分块上限兜底值（实际值运行时按模型上下文窗口自适应，见 agent.getRuntimeChunkBudget()） */
    private static final int CHUNK_MAX_TOKENS = 600;
    private static final int CHUNK_MIN_TOKENS = 100;
    /** v7: 缺口重试最大 chunk 数（QA Gate） */
    private static final int QA_MAX_RETRY_CHUNKS = 3;
    /** v7: 数量对账允许的最大缺口比例 */
    private static final float QA_GAP_TOLERANCE = 0.10f;

    private static final String[] CHUNK_SEPARATORS = {"\n\n", "\n", "。", ". ", "  "};

    /** 题目特征正则：用于预过滤 */
    private static final Pattern QUESTION_PATTERN = Pattern.compile(
            "第\\s*\\d+\\s*题|^\\s*\\d+\\s*[\\.、\\)]|Q\\s*\\d+|"
                    + "选项\\s*[A-D]|正确答案|题目|题干|问题",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** 规则解析置信度阈值：≥ 此值直接入库 */
    private static final float RULE_CONFIDENCE_THRESHOLD = 0.7f;

    // ======================== 字段 ========================

    private final Context context;
    private final OnlineInferenceService inferenceService;
    private final OnlineModelManager onlineModelManager;
    private final DatabaseManager databaseManager;
    private final ALChat localChat = new ALChat();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ModelMemoryManager memoryManager;
    private final FileContentExtractor fileExtractor;
    private final AIImportAgent agent;
    private final AIFieldMapper fieldMapper;

    private volatile boolean cancelled = false;
    private ModelMode modelMode = ModelMode.AUTO;
    private final AtomicInteger localFailCount = new AtomicInteger(0);
    private static final int LOCAL_FAIL_THRESHOLD = 3;
    private volatile boolean localModelPaused = false;
    /** 最近一次 stageProfile 的结果（供 stageIngest 取默认题型/sheet名等上下文） */
    private ProfileResult lastProfile = null;

    // v6: Excel 原文件直读参数（由 UI 选定工作表后传入，为 null 时走通用文件提取路径）
    private int excelSheetIndex = -1;
    private int excelHeaderRow = -1;
    private int excelSubHeaderRow = -1;
    private int excelDataStartRow = -1;
    private String excelSheetName = null;
    private String excelInferredType = null;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AIImport-Orchestrator");
        t.setDaemon(true);
        return t;
    });

    // ======================== 公共接口 ========================

    /** 导入过程监听器，所有方法在主线程回调。 */
    public interface ImportListener {
        void onStage(Stage stage, String message);
        void onProgress(int current, int total, String detail);
        void onTokenStream(String delta, int tokenCount, float tokPerSec);
        void onThinking(String text);
        void onPreviewQuestions(List<Question> partial);
        void onImportedBatch(int imported, int duplicated, int failed);
        void onComplete(AIImportResult result);
        void onError(String message, Throwable error);
    }

    /** 流水线阶段（v4: 4 阶段） */
    public enum Stage {
        PROFILE, FORMAT, INGEST, DONE
    }

    /** 模型调用模式 */
    public enum ModelMode {
        ONLINE_PREFERRED, ONLINE_ONLY, LOCAL_ONLY, AUTO
    }

    // ======================== 构造与生命周期 ========================

    public AIImportOrchestrator(Context context) {
        this.context = context.getApplicationContext();
        this.inferenceService = OnlineInferenceService.getInstance(this.context);
        this.onlineModelManager = OnlineModelManager.getInstance(this.context);
        this.databaseManager = DatabaseManager.getInstance(this.context);
        this.memoryManager = ModelMemoryManager.getInstance(this.context);
        this.fileExtractor = new FileContentExtractor(this.context);
        this.agent = new AIImportAgent(this.context);
        this.fieldMapper = new AIFieldMapper(this.context);
    }

    public void setModelMode(ModelMode mode) {
        this.modelMode = mode;
        this.localModelPaused = false;
        this.localFailCount.set(0);
        // 同步设置 Agent 的模型模式
        this.agent.setModelMode(AIImportAgent.ModelMode.valueOf(mode.name()));
    }

    public ModelMode getModelMode() { return modelMode; }

    public String getCurrentModelInfo() {
        OnlineModelManager.OnlineModelConfig online = onlineModelManager.getActiveModel();
        switch (modelMode) {
            case ONLINE_ONLY:
                return online != null ? "在线: " + online.name : "在线(无可用模型)";
            case LOCAL_ONLY:
                if (localModelPaused) return "本地(已暂停-故障)";
                return localChat.isInitialized() ? "本地模型" : "本地(未加载)";
            case ONLINE_PREFERRED:
                if (online != null) return "在线: " + online.name;
                if (localModelPaused) return "本地(已暂停-故障)";
                return localChat.isInitialized() ? "本地模型(降级)" : "本地(未加载)";
            case AUTO:
            default:
                if (online != null) return "在线: " + online.name;
                if (localModelPaused) return "本地(已暂停-故障)";
                return localChat.isInitialized() ? "本地模型" : "无可用模型";
        }
    }

    public void start(File file, ImportListener listener) {
        cancelled = false;
        excelSheetIndex = -1;
        executor.execute(() -> runPipeline(file, listener));
    }

    /**
     * v6: Excel 原文件直读入口 — UI 已选定工作表时直接传原文件 + sheet 参数，
     * Orchestrator 在 PROFILE 阶段用 POI 内存直读选定工作表，不再依赖临时 Markdown 文件。
     */
    public void startWithSheet(File file, ImportListener listener,
                               int sheetIndex, int headerRow, int subHeaderRow,
                               int dataStartRow, String sheetName, String inferredType) {
        cancelled = false;
        this.excelSheetIndex = sheetIndex;
        this.excelHeaderRow = headerRow;
        this.excelSubHeaderRow = subHeaderRow;
        this.excelDataStartRow = dataStartRow;
        this.excelSheetName = sheetName;
        this.excelInferredType = inferredType;
        executor.execute(() -> runPipeline(file, listener));
    }

    public void cancel() { cancelled = true; agent.cancel(); }

    private java.util.function.Consumer<String> agentErrorCallback;
    public void setAgentErrorCallback(java.util.function.Consumer<String> callback) {
        this.agentErrorCallback = callback;
    }

    // ======================== 流水线主流程（v4：4 阶段） ========================

    private void runPipeline(File file, ImportListener listener) {
        long startMs = System.currentTimeMillis();

        // 阶段 1 PROFILE：文件识别 + 内容提取 + 预处理 + 分块
        ProfileResult pr = null;
        try {
            pr = stageProfile(file, listener);
            this.lastProfile = pr;
        } catch (Exception e) {
            Log.e(TAG, "PROFILE 阶段异常: " + e.getMessage(), e);
            runOnMain(() -> listener.onError("文件识别失败: " + e.getMessage(), e));
            return;  // PROFILE 失败无法继续
        }
        if (pr == null || cancelled) return;

        // 阶段 2 FORMAT：格式检测 + 规则引擎快速解析
        FormatResult fr = null;
        try {
            fr = stageFormat(pr, listener);
        } catch (Exception e) {
            Log.e(TAG, "FORMAT 阶段异常，降级为纯 AI 模式: " + e.getMessage(), e);
            runOnMain(() -> listener.onThinking("格式检测异常，降级为纯 AI 模式"));
        }
        if (fr == null) {
            // FORMAT 失败时降级：创建默认 FormatResult，全部 chunk 交给 AI
            fr = new FormatResult();
            fr.remainingChunks = pr.chunks != null ? pr.chunks : new ArrayList<>();
            fr.parseMethod = "ai";
        }
        if (cancelled) return;

        // 阶段 3 INGEST：AI 智能抽取
        IngestStats stats = null;
        final List<Integer> chunkYields = new ArrayList<>();
        try {
            stats = stageIngest(fr.remainingChunks, fr, listener, chunkYields);
        } catch (Exception e) {
            Log.e(TAG, "INGEST 阶段异常: " + e.getMessage(), e);
            runOnMain(() -> listener.onThinking("AI 抽取阶段异常: " + e.getMessage()));
        }
        if (stats == null) {
            // INGEST 失败时降级：使用规则解析的结果
            stats = new IngestStats();
            stats.parseMethod = fr.parseMethod;
            for (Question q : fr.ruleParsedQuestions) {
                stats.allValid.add(q);
            }
        }
        if (cancelled) return;

        // 阶段 3.5 v7 QA Gate：数量对账 + 缺口 chunk 重试（解决漏题不可见）
        try {
            stageQualityGate(pr, stats, fr.remainingChunks, chunkYields, listener);
        } catch (Exception e) {
            Log.w(TAG, "QA Gate 异常(不影响已入库结果): " + e.getMessage());
        }
        if (cancelled) return;

        // 阶段 4 DONE：质量网关 + 校验 + 入库 + 结果汇总
        try {
            AIImportResult result = finalizeResult(file, pr, stats, startMs, listener);
            if (cancelled) return;
            final AIImportResult finalResult = result;
            runOnMain(() -> listener.onComplete(finalResult));
        } catch (Exception e) {
            Log.e(TAG, "DONE 阶段异常: " + e.getMessage(), e);
            final String msg = "结果汇总失败: " + e.getMessage();
            runOnMain(() -> listener.onError(msg, e));
        }
    }

    // ======================== 阶段 1: PROFILE ========================

    private static class ProfileResult {
        String fullText;
        List<String> chunks;
        FileProfiler.FileProfile fileProfile;
        String fileName;
        int filteredCount;
        /** 从 SHEET_META 推断的默认题型（可为空，表示未指定） */
        String defaultQuestionType;
        /** 从 SHEET_META 拿到的 sheetName（用于显示，可为空） */
        String sourceSheetName;
        /** v7: 源规模估算题数（Excel 数据行数/文本题号数，0=无法估算），供 QA Gate 对账 */
        int sourceEstimate;
        /** v7: 内容是否来自 OCR（图片/扫描件），供质量报告展示 */
        boolean usedOcr;
    }

    private ProfileResult stageProfile(File file, ImportListener listener) {
        Log.i(TAG, "stageProfile 开始: " + file.getName());
        runOnMain(() -> listener.onStage(Stage.PROFILE, "识别文件..."));

        // 1. 文件基本信息识别
        FileProfiler.FileProfile fp = FileProfiler.profile(file);
        String fileName = file.getName();
        Log.i(TAG, "stageProfile 文件类型: " + (fp != null ? fp.formatHint : "null"));

        // 2. v6: 文件内容提取
        //    - Excel + 已选定工作表：POI 内存直读选定 sheet（与直接导入同源能力，不再绕道临时 .md 文件）
        //    - 其他情况：复用 FileContentExtractor / 编码探测
        String rawText;
        String defaultQuestionType = null;
        String sourceSheetName = null;
        boolean usedOcr = false;
        boolean excelSheetDirect = excelSheetIndex >= 0
                && (file.getName().toLowerCase().endsWith(".xlsx")
                    || file.getName().toLowerCase().endsWith(".xls"));
        if (excelSheetDirect) {
            rawText = ExcelSheetPicker.exportSheetAsMarkdownWithMeta(
                    file, excelSheetIndex, excelHeaderRow, excelSubHeaderRow,
                    excelDataStartRow, excelSheetName, excelInferredType);
            Log.i(TAG, "stageProfile Excel 原文件直读 sheet[" + excelSheetIndex + "]: len="
                    + (rawText == null ? -1 : rawText.length()));
            if (rawText != null && !rawText.trim().isEmpty()) {
                java.util.Map<String, String> sheetMeta = ExcelSheetPicker.parseSheetMeta(rawText);
                defaultQuestionType = sheetMeta.get("questionType");
                sourceSheetName = sheetMeta.get("sheetName");
            }
        } else {
            rawText = extractFileContent(file, fp);
            // v7: 图片/扫描件 PDF 文本层为空时，走 OCR 兜底（复用 AttachmentPreParser：在线视觉优先+缓存）
            String lowerName = file.getName().toLowerCase();
            boolean ocrCandidate = lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                    || lowerName.endsWith(".png") || lowerName.endsWith(".webp")
                    || lowerName.endsWith(".bmp") || lowerName.endsWith(".pdf");
            if (ocrCandidate && (rawText == null || rawText.trim().length() < 30)) {
                runOnMain(() -> listener.onThinking("文本层为空，启动 OCR 识别（可能耗时）..."));
                try {
                    com.oilquiz.app.ai.chat.input.AttachmentPreParser preParser =
                            new com.oilquiz.app.ai.chat.input.AttachmentPreParser(context);
                    com.oilquiz.app.ai.chat.ChatMessage.Attachment att =
                            new com.oilquiz.app.ai.chat.ChatMessage.Attachment(
                                    "file", Uri.fromFile(file).toString(), file.getName(), file.length());
                    com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult pr2 =
                            preParser.parseOne(att, file.getAbsolutePath());
                    if (pr2.isUsable()) {
                        rawText = pr2.content;
                        usedOcr = true;
                        final String ocrMethod = pr2.method;
                        final int ocrLen = rawText.length();
                        final boolean ocrCached = pr2.fromCache;
                        runOnMain(() -> listener.onThinking("OCR 完成(" + ocrMethod + ")，文本 "
                                + ocrLen + " 字符" + (ocrCached ? "（缓存）" : "")));
                    } else {
                        final String errMsg = "[" + pr2.errorCode + "] " + pr2.errorMessage;
                        runOnMain(() -> listener.onError("文件文本提取与 OCR 均失败: " + errMsg, null));
                        return null;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "OCR 兜底异常: " + e.getMessage(), e);
                    runOnMain(() -> listener.onError("OCR 识别失败: " + e.getMessage(), e));
                    return null;
                }
            }
        }
        Log.i(TAG, "stageProfile 内容提取返回: len=" + (rawText == null ? -1 : rawText.length()));
        ImportDebugTracer.trace("【0】stageProfile入口", fileName + " | rawText len=" + (rawText == null ? -1 : rawText.length()));
        if (rawText == null || rawText.trim().isEmpty()) {
            runOnMain(() -> listener.onError("文件内容为空或读取失败: " + fileName, null));
            return null;
        }

        // 2.5. 非 Excel 直读路径：从文本中解析 SHEET_META（如：题型/Sheet名），供后续 questionType 回填
        if (!excelSheetDirect) {
            java.util.Map<String, String> sheetMeta = ExcelSheetPicker.parseSheetMeta(rawText);
            defaultQuestionType = sheetMeta.get("questionType");
            sourceSheetName = sheetMeta.get("sheetName");
        }
        if (defaultQuestionType != null && !defaultQuestionType.trim().isEmpty()) {
            Log.i(TAG, "stageProfile 从 SHEET_META 命中默认题型: " + defaultQuestionType
                    + (sourceSheetName != null ? " (sheet=" + sourceSheetName + ")" : ""));
        }

        // 3. 文本预处理（全角半角 + 去噪 + 边界修复）
        String processedText = QuestionPreprocessor.preprocess(rawText);
        if (processedText.trim().isEmpty()) {
            runOnMain(() -> listener.onError("文件内容预处理后为空: " + fileName, null));
            return null;
        }

        // 4. 乱码检查
        if (looksLikeGarbled(processedText)) {
            runOnMain(() -> listener.onError("文件内容疑似乱码,无法解析: " + fileName, null));
            return null;
        }

        // 5. 递归分块（v7: 按运行时上下文窗口自适应预算；表格类文本按完整行组分块并附源行号 provenance）
        int chunkBudget = CHUNK_MAX_TOKENS;
        try {
            chunkBudget = agent.getRuntimeChunkBudget();
        } catch (Throwable t) {
            Log.w(TAG, "获取分块预算失败，使用默认 " + CHUNK_MAX_TOKENS + ": " + t.getMessage());
        }
        List<String> rawChunks;
        if (isMostlyTable(processedText)) {
            rawChunks = chunkTableByRowGroups(processedText, chunkBudget);
        } else {
            rawChunks = recursiveChunkText(processedText, chunkBudget);
        }
        if (rawChunks.isEmpty()) {
            runOnMain(() -> listener.onError("文件分块后为空: " + fileName, null));
            return null;
        }

        int estCount = fp != null ? fp.estimatedCount : 0;
        String formatHint = fp != null ? fp.formatHint : "未知";
        // v7: 源规模估算（QA Gate 对账基准）：表格优先用数据行数，文本用题号估算
        int tableDataRows = countTableDataRows(processedText);
        int sourceEstimate = tableDataRows > 0 ? tableDataRows : estCount;
        final int srcEstFinal = sourceEstimate;
        final int chunkBudgetFinal = chunkBudget;
        final int rawTextLen = rawText.length();
        runOnMain(() -> listener.onThinking(
                "预处理完成: 源估算 " + srcEstFinal + " 题, 格式:" + formatHint
                        + ", 分块预算 " + chunkBudgetFinal + " tokens"
                        + ", 原始文本 " + rawTextLen + " 字符"));

        // 6. 预过滤（v6: 去掉 chunk 重叠，重叠片段会导致 AI 重复抽取同一题，跨 chunk 去重由 stageIngest 负责）
        List<String> filtered = new ArrayList<>();
        int skipped = 0;
        for (String chunk : rawChunks) {
            if (preFilterChunk(chunk)) {
                filtered.add(chunk);
            } else {
                skipped++;
            }
        }

        final int total = filtered.size();
        final int skippedFinal = skipped;
        runOnMain(() -> listener.onProgress(0, total,
                "共 " + total + " 块" + (skippedFinal > 0 ? " (过滤 " + skippedFinal + " 空块)" : "")));

        ProfileResult pr = new ProfileResult();
        pr.fullText = processedText;
        pr.chunks = filtered;
        pr.fileProfile = fp;
        pr.fileName = fileName;
        pr.filteredCount = skipped;
        pr.defaultQuestionType = defaultQuestionType;
        pr.sourceSheetName = sourceSheetName;
        pr.sourceEstimate = sourceEstimate;
        pr.usedOcr = usedOcr;
        return pr;
    }

    /**
     * v4: 利用项目已有解析库提取文件内容。
     * 优先使用 FileContentExtractor（支持 Word/PDF/Excel/HTML/ZIP/图片OCR），
     * 降级使用直读 + 编码探测。
     */
    private String extractFileContent(File file, FileProfiler.FileProfile fp) {
        String name = file.getName().toLowerCase();

        // Word/PDF/Excel/HTML: 优先用 FileContentExtractor
        boolean needsExtractor = name.endsWith(".docx") || name.endsWith(".doc")
                || name.endsWith(".pdf")
                || name.endsWith(".xlsx") || name.endsWith(".xls")
                || name.endsWith(".html") || name.endsWith(".htm")
                || name.endsWith(".zip");
        // ⚠ 二进制格式：即使 FileContentExtractor 失败，也绝对不能降级到文本解码（否则会把OLE2/ZIP字节当文本解码→产生乱码）
        boolean isBinaryFormat = name.endsWith(".docx") || name.endsWith(".doc")
                || name.endsWith(".pdf")
                || name.endsWith(".xlsx") || name.endsWith(".xls")
                || name.endsWith(".zip");

        if (needsExtractor) {
            try {
                Uri fileUri = Uri.fromFile(file);
                String content = fileExtractor.extractContent(fileUri).join();
                // ========== 诊断：Orchestrator收到Extractor结果 ==========
                com.oilquiz.app.util.ImportDebugTracer.trace("【4】Orchestrator收到Extractor",
                        content == null ? "[null]" : content.substring(0, Math.min(500, content.length())));
                if (content != null && !content.trim().isEmpty()
                        && !content.startsWith("不支持")
                        && !content.startsWith("文件解析失败")
                        && !content.startsWith("Excel解析失败")
                        && !content.startsWith("Word文件需要")
                        && !content.startsWith("PDF解析失败")
                        && !content.startsWith("无法确定文件类型")) {
                    Log.i(TAG, "FileContentExtractor 解析成功: " + name);
                    // ========== 诊断：返回前预处理前 ==========
                    com.oilquiz.app.util.ImportDebugTracer.trace("【5】Preprocessor输入",
                            content.substring(0, Math.min(500, content.length())));
                    return content;
                } else {
                    Log.w(TAG, "FileContentExtractor 返回失败结果: " + (content == null ? "null" : content.substring(0, Math.min(100, content.length()))));
                }
            } catch (Exception e) {
                Log.w(TAG, "FileContentExtractor 解析失败: " + e.getMessage());
            }

            // ⚠ 二进制格式：Extractor失败就直接返回空提示，禁止走下面的CharsetDetector文本解码路径
            if (isBinaryFormat) {
                Log.e(TAG, "二进制格式(" + name + ")解析失败，已拦截错误的文本解码降级路径");
                return "";
            }
        }

        // 纯文本/CSV/JSON/Markdown: 使用CharsetDetector智能识别编码
        // ⚠ 系统生成的临时 .md 文件（ai_sheet 前缀）确定是 UTF-8，直接读取，跳过 CharsetDetector 误判
        if (name.startsWith("ai_sheet") && name.endsWith(".md")) {
            Log.i(TAG, "系统生成的 .md 临时文件，直接 UTF-8 读取: " + name);
            String content = readFileWithEncoding(file, "UTF-8");
            ImportDebugTracer.trace("【4b】MD临时文件UTF-8直读",
                    content == null ? "[null]" : content.substring(0, Math.min(500, content.length())));
            return content;
        }

        try {
            String content = CharsetDetector.readFileAutoDetect(file);
            Log.i(TAG, "CharsetDetector 检测完成: len=" + (content == null ? -1 : content.length()));
            // 乱码二次检查：若解码结果大量是替换字符，再用fallback列表再试
            if (looksLikeGarbled(content)) {
                Log.w(TAG, "首次检测疑似乱码，启用decodeWithFallback二次尝试");
                byte[] rawBytes = CharsetDetector.readFileBytes(file);
                content = CharsetDetector.decodeWithFallback(rawBytes, null);
            }
            return content;
        } catch (Exception e) {
            Log.e(TAG, "CharsetDetector读取失败，回退UTF-8直读: " + e.getMessage());
            return readFileWithEncoding(file, "UTF-8");
        }
    }

    /** 用指定编码读取文件 */
    private String readFileWithEncoding(File file, String encoding) {
        String enc = (encoding == null || encoding.trim().isEmpty()) ? "UTF-8" : encoding;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), enc))) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "读取文件失败: " + e.getMessage());
            return null;
        }
        return sb.toString();
    }

    // ======================== 阶段 2: FORMAT（v4 新增） ========================

    private static class FormatResult {
        QuestionFormatDetector.DetectionResult detection;
        List<Question> ruleParsedQuestions = new ArrayList<>();
        int ruleParsedCount = 0;
        List<String> remainingChunks;  // 需要 AI 兜底解析的 chunk
        String parseMethod = "ai";     // rule / ai / hybrid / rule_fallback
        /** AI 动态智能映射结果（标准字段名→列索引），用于辅助 AI 抽取 */
        Map<String, Integer> smartFieldMapping = null;
        /** AI 识别的字段映射描述（用于 UI 展示） */
        List<String> aiResolvedFields = new ArrayList<>();
        /** v7: 规则题是否已被 INGEST 消费（防止 rule_fallback 重复入库预解析题） */
        boolean ruleQuestionsConsumed = false;
        /** v7: 路由描述（供质量报告展示） */
        String routeDesc = "";
    }

    private FormatResult stageFormat(ProfileResult pr, ImportListener listener) {
        runOnMain(() -> listener.onStage(Stage.FORMAT, "检测格式..."));

        FormatResult fr = new FormatResult();

        // 1. 格式检测
        QuestionFormatDetector.DetectionResult detection =
                QuestionFormatDetector.detect(pr.fullText, pr.fileName);
        fr.detection = detection;

        runOnMain(() -> listener.onThinking(
                "格式检测: " + detection.description
                        + " (置信度: " + String.format("%.0f%%", detection.confidence * 100) + ")"));

        // 2. v7 智能路由：AI 只做规则做不了的事
        boolean aiAvailable = agent.hasAnyModel();

        // 路由①：确定性表格快速通道 — 关键列（题干+答案）全部命中 → 规则 0 token 入库
        List<Question> fastQuestions = null;
        if (detection.format == QuestionFormatDetector.DetectedFormat.EXCEL_TABLE) {
            fastQuestions = tryRuleFastPath(pr, listener);
        }

        if (fastQuestions != null && !fastQuestions.isEmpty()) {
            fr.parseMethod = "rule";
            fr.routeDesc = "规则快速通道";
            fr.ruleParsedQuestions = fastQuestions;
            fr.ruleParsedCount = fastQuestions.size();
            fr.remainingChunks = new ArrayList<>();
            runOnMain(() -> listener.onThinking(
                    "规则快速通道: 表头确定性映射成功，解析 " + fr.ruleParsedCount + " 题（0 token）"));

            // 抽样质检：规则结果也要过 AI 抽样核验（无模型则跳过）
            if (aiAvailable && !sampleCheckRuleQuestions(fastQuestions, listener)) {
                runOnMain(() -> listener.onThinking(
                        "抽样质检失败(不一致率>50%)，转 AI 全量抽取..."));
                fr.parseMethod = "ai";
                fr.routeDesc = "AI Agent（抽样质检未通过）";
                fr.ruleParsedQuestions = new ArrayList<>();
                fr.ruleParsedCount = 0;
                fr.remainingChunks = pr.chunks;
            } else {
                runOnMain(() -> listener.onProgress(1, 1,
                        "规则解析完成: " + fr.ruleParsedCount + " 题"));
            }
        } else if (aiAvailable) {
            // 路由②：AI Agent 通道（半结构化文本/未知表头/非表格格式）
            fr.parseMethod = "ai";
            fr.routeDesc = "AI Agent 抽取";
            fr.remainingChunks = pr.chunks;
            runOnMain(() -> listener.onThinking("AI 通道: " + pr.chunks.size() + " 个文本块全部交给 Agent 抽取"));

            // 规则引擎预解析(不入库)：当 AI 抽取零结果时自动降级兜底（保留 rule_fallback）
            try {
                if (detection.format != QuestionFormatDetector.DetectedFormat.UNKNOWN) {
                    RuleBasedQuestionParser.ParseResult parseResult =
                            RuleBasedQuestionParser.parse(pr.fullText, detection.format);
                    fr.ruleParsedQuestions = parseResult.questions;
                    fr.ruleParsedCount = parseResult.ruleParsed;
                    if (fr.ruleParsedCount > 0) {
                        runOnMain(() -> listener.onThinking(
                                "规则引擎兜底就绪: 预解析 " + fr.ruleParsedCount + " 题"));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "规则引擎兜底预解析失败: " + e.getMessage());
            }
        } else if (QuestionFormatDetector.canUseRuleParser(detection)) {
            // 无可用模型：降级为规则引擎优先（旧路径）
            runOnMain(() -> listener.onThinking("无可用 AI 模型，降级为规则引擎解析..."));

            RuleBasedQuestionParser.ParseResult parseResult =
                    RuleBasedQuestionParser.parse(pr.fullText, detection.format);

            fr.ruleParsedQuestions = parseResult.questions;
            fr.ruleParsedCount = parseResult.ruleParsed;

            runOnMain(() -> listener.onThinking(
                    "规则引擎解析出 " + fr.ruleParsedCount + " 道题"));

            if (fr.ruleParsedCount > 0) {
                fr.parseMethod = "rule";
                fr.routeDesc = "规则引擎（无模型降级）";
                fr.remainingChunks = new ArrayList<>();
                runOnMain(() -> listener.onProgress(1, 1,
                        "规则解析完成: " + fr.ruleParsedCount + " 题"));
            } else {
                // 规则解析也失败：保留 chunk 让 INGEST 阶段报"无可用模型"错误
                fr.parseMethod = "ai";
                fr.remainingChunks = pr.chunks;
            }
        } else {
            // 无模型且低置信度：保留 chunk，INGEST 阶段会提示无可用模型
            fr.parseMethod = "ai";
            fr.remainingChunks = pr.chunks;
        }

        // 3. AI 动态智能字段映射（当走 AI 通道且有表格结构时，提取表头并通过 AI 识别未知字段）
        if ("ai".equals(fr.parseMethod) && fr.remainingChunks != null && !fr.remainingChunks.isEmpty()) {
            runOnMain(() -> listener.onThinking("构建 AI 动态字段映射..."));
            try {
                List<String> headers = extractFirstTableRow(pr.fullText);
                if (headers == null) {
                    headers = extractTableHeaders(pr.fullText, String.valueOf(detection.format));
                }
                if (headers != null && !headers.isEmpty()) {
                    // 同步调用 AIFieldMapper（在后台线程中执行）
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    final Map<String, Integer>[] resultHolder = new Map[]{new java.util.LinkedHashMap<>()};
                    final List<String>[] resolvedHolder = new List[]{new ArrayList<>()};

                    fieldMapper.buildSmartMappingAsync(headers, new AIFieldMapper.MappingCallback() {
                        @Override
                        public void onMappingComplete(Map<String, Integer> finalMapping, List<String> aiResolvedFields) {
                            resultHolder[0] = finalMapping;
                            resolvedHolder[0] = aiResolvedFields;
                            latch.countDown();
                        }

                        @Override
                        public void onMappingProgress(String message) {
                            runOnMain(() -> listener.onThinking(message));
                        }

                        @Override
                        public void onMappingError(String message) {
                            Log.w(TAG, "AI 字段映射失败: " + message);
                            latch.countDown();
                        }
                    });

                    latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
                    fr.smartFieldMapping = resultHolder[0];
                    fr.aiResolvedFields = resolvedHolder[0];

                    if (!fr.aiResolvedFields.isEmpty()) {
                        runOnMain(() -> listener.onThinking(
                                "AI 动态映射识别 " + fr.aiResolvedFields.size() + " 个字段: "
                                        + String.join("; ", fr.aiResolvedFields)));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "AI 动态字段映射异常: " + e.getMessage());
            }
        }

        return fr;
    }

    /**
     * 尝试从全文中提取表格表头（用于 AI 动态字段映射）。
     * 支持 Markdown 表格、Tab 分隔表格、CSV 格式。
     */
    private List<String> extractTableHeaders(String fullText, String format) {
        if (fullText == null || fullText.isEmpty()) return null;
        try {
            String firstLine = fullText.split("\n", 2)[0].trim();
            if (firstLine.isEmpty()) return null;

            List<String> headers;
            if (firstLine.contains("|")) {
                // Markdown 表格
                String[] parts = firstLine.split("\\|");
                headers = new ArrayList<>();
                for (String p : parts) {
                    String t = p.trim();
                    if (!t.isEmpty() && !t.matches("[-:]+")) headers.add(t);
                }
            } else if (firstLine.contains("\t")) {
                // Tab 分隔
                String[] parts = firstLine.split("\t");
                headers = new ArrayList<>();
                for (String p : parts) {
                    String t = p.trim();
                    if (!t.isEmpty()) headers.add(t);
                }
            } else if (firstLine.contains(",")) {
                // CSV
                headers = com.oilquiz.app.util.render.ExcelUtil.splitCsvLine(firstLine);
            } else {
                return null;
            }

            return headers.isEmpty() ? null : headers;
        } catch (Exception e) {
            return null;
        }
    }

    /** v7: 提取文本中第一个 Markdown 表格行的单元格（通常是表头，兼容 SHEET_META 注释前置） */
    private List<String> extractFirstTableRow(String text) {
        if (text == null) return null;
        try {
            for (String line : text.split("\n", -1)) {
                String t = line.trim();
                if (!t.startsWith("|")) continue;
                String[] parts = t.split("\\|", -1);
                List<String> cells = new ArrayList<>();
                for (String p : parts) {
                    String c = p.trim();
                    if (!c.isEmpty() && !c.matches("[-:]+")) cells.add(c);
                }
                if (!cells.isEmpty()) return cells;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * v7: 解析 Markdown 表格数据行（跳过表头行、分隔行、SHEET_META 注释）。
     */
    private List<List<String>> parseMarkdownTableRows(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null) return rows;
        boolean headerSkipped = false;
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (!t.startsWith("|")) continue;
            String[] parts = t.split("\\|", -1);
            List<String> cells = new ArrayList<>();
            for (String p : parts) cells.add(p.trim());
            if (!cells.isEmpty() && cells.get(0).isEmpty()) cells.remove(0);
            if (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) cells.remove(cells.size() - 1);
            if (cells.isEmpty()) continue;
            // 分隔行（--- / :---:）
            boolean sep = true;
            for (String c : cells) {
                if (!c.matches("[-:]+")) { sep = false; break; }
            }
            if (sep) continue;
            if (cells.get(0).toUpperCase().contains("SHEET_META")) continue;
            if (!headerSkipped) { headerSkipped = true; continue; }  // 第一个表格行是表头
            rows.add(cells);
        }
        return rows;
    }

    /** v7: 统计 Markdown 表格数据行数（供源规模估算） */
    private int countTableDataRows(String text) {
        try {
            return parseMarkdownTableRows(text).size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * v7 路由①：确定性表格规则快速通道。
     * 条件：表头关键列（questionText + correctAnswer）被 FieldMappingRegistry 确定性识别，
     * 按映射直接逐行提取，0 LLM 调用。不满足条件/提取率异常时返回 null 交给 AI 通道。
     */
    private List<Question> tryRuleFastPath(ProfileResult pr, ImportListener listener) {
        try {
            List<String> headers = extractFirstTableRow(pr.fullText);
            if (headers == null) headers = extractTableHeaders(pr.fullText, null);
            if (headers == null || headers.isEmpty()) return null;

            Map<String, Integer> mapping = FieldMappingRegistry.buildMappingFromHeaders(headers);
            if (!mapping.containsKey("questionText") || !mapping.containsKey("correctAnswer")) {
                runOnMain(() -> listener.onThinking(
                        "表头未确定性识别（缺题干/答案列），走 AI 通道"));
                return null;
            }

            List<List<String>> rows = parseMarkdownTableRows(pr.fullText);
            if (rows.isEmpty()) return null;

            // 映射防错校验：必填列/表头冲突/样例行填充率，失败则放弃快速通道转 AI 通道
            List<List<String>> sampleRows = rows.subList(0, Math.min(10, rows.size()));
            ImportMappingValidator.Report vr = ImportMappingValidator.validate(headers, mapping, sampleRows);
            if (!vr.ok) {
                final String vrSummary = vr.summary().trim();
                Log.w(TAG, "规则快速通道映射校验失败: " + vrSummary);
                runOnMain(() -> listener.onThinking(
                        "表头映射校验未通过，为防止导入错误转 AI 通道：" + vrSummary));
                return null;
            }
            if (!vr.warnings.isEmpty()) {
                Log.w(TAG, "规则快速通道映射警告: " + vr.warnings);
            }

            List<Question> questions = new ArrayList<>();
            for (List<String> row : rows) {
                Question q = FieldMappingRegistry.extractFromRow(row, mapping);
                if (q.getQuestionText() != null && !q.getQuestionText().trim().isEmpty()) {
                    questions.add(q);
                }
            }
            // 置信度保护：提取题数不足数据行一半，可能结构异常（合并单元格/跨行单元格），放弃快速通道
            if (questions.size() < Math.max(1, rows.size() / 2)) {
                Log.w(TAG, "规则快速通道提取率过低: " + questions.size() + "/" + rows.size() + "，转 AI 通道");
                return null;
            }
            return questions;
        } catch (Exception e) {
            Log.w(TAG, "规则快速通道异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * v7 抽样质检：规则解析结果抽 3~5 题交 AI 做字段一致性核验。
     * 不一致率 >50% 返回 false（该批应转 AI 全量重抽）。
     * 无模型/核验异常时返回 true（不因核验通道自身问题阻断导入）。
     */
    private boolean sampleCheckRuleQuestions(List<Question> questions, ImportListener listener) {
        if (questions == null || questions.isEmpty()) return true;
        if (!agent.hasAnyModel()) return true;
        try {
            JSONObject schema = QuestionSchemaDictionary.getQaCheckSchema();
            int sampleSize = Math.min(questions.size(), questions.size() >= 5 ? 4 : 3);
            java.util.Random rnd = new java.util.Random(42);
            List<Integer> idxs = new ArrayList<>();
            while (idxs.size() < sampleSize) {
                int r = rnd.nextInt(questions.size());
                if (!idxs.contains(r)) idxs.add(r);
            }
            int fail = 0;
            for (int idx : idxs) {
                if (cancelled) return true;
                String prompt = QuestionSchemaDictionary.getQaCheckPrompt(
                        buildQuestionJsonForQa(questions.get(idx)));
                String raw = callLlmStructuredMinimal(prompt, schema);
                boolean ok = true;
                if (raw != null) {
                    JSONObject root = ImportValidator.parseStructuredOutput(raw);
                    if (root != null) {
                        String verdict = root.optString("verdict", "pass").toLowerCase();
                        ok = verdict.contains("pass");
                        if (!ok) {
                            Log.w(TAG, "抽样质检 fail(第" + (idx + 1) + "题): "
                                    + root.optString("reason", ""));
                        }
                    }
                }
                // AI 无响应视为 pass：不因核验通道自身不稳定阻断导入
                if (!ok) fail++;
            }
            final int f = fail, s = idxs.size();
            runOnMain(() -> listener.onThinking("抽样质检: 抽 " + s + " 题，通过 "
                    + (s - f) + "，不通过 " + f));
            return fail * 2 <= s;  // 不一致率 ≤50% 视为通过
        } catch (Exception e) {
            Log.w(TAG, "抽样质检异常: " + e.getMessage());
            return true;
        }
    }

    /** 构建抽样质检用的题目 JSON（仅核心字段） */
    private JSONObject buildQuestionJsonForQa(Question q) {
        JSONObject jo = new JSONObject();
        try {
            jo.put("questionText", nz(q.getQuestionText()));
            jo.put("optionA", nz(q.getOptionA()));
            jo.put("optionB", nz(q.getOptionB()));
            jo.put("optionC", nz(q.getOptionC()));
            jo.put("optionD", nz(q.getOptionD()));
            jo.put("correctAnswer", nz(q.getCorrectAnswer()));
            jo.put("questionType", nz(q.getQuestionType()));
        } catch (Exception ignored) {}
        return jo;
    }

    private String nz(String s) { return s == null ? "" : s; }

    // ======================== 阶段 3: INGEST ========================

    private static class IngestStats {
        int imported, duplicated, failed;
        List<Question> allValid = new ArrayList<>();
        List<Question> allInvalid = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int aiParsedCount = 0;
        String parseMethod = "ai";
        /** v7: 方法分布（质量报告） */
        int ruleCount = 0;
        int aiCount = 0;
        /** v7: QA Gate 结果 */
        int sourceEstimate = 0;
        boolean qaPassed = true;
        int retriedChunks = 0;
    }

    private IngestStats stageIngest(List<String> chunks, FormatResult fr, ImportListener listener,
                                    List<Integer> chunkYields) {
        // 读取 ProfileResult 中的默认题型（来自 SHEET_META）：
        //   - 追加 "DEFAULT_QUESTION_TYPE=xxx" 行到每个 chunk 的首部（让 Agent 输出 questionType 时优先使用）
        //   - 入库时仍会做兜底回填（见 finalizeResult）
        String defaultType = null;
        if (this.lastProfile != null) {
            defaultType = this.lastProfile.defaultQuestionType;
        }
        final String metaPrefix;
        if (defaultType != null && !defaultType.trim().isEmpty() && !"未分类".equals(defaultType.trim())) {
            String sheetHint = (this.lastProfile != null && this.lastProfile.sourceSheetName != null)
                    ? " sheetName=" + this.lastProfile.sourceSheetName.replace(" ", "_") : "";
            metaPrefix = "DEFAULT_QUESTION_TYPE=" + defaultType + sheetHint + "\n"
                    + "SHEET_META_INLINE: questionType=" + defaultType + sheetHint + "\n";
        } else {
            metaPrefix = null;
        }
        IngestStats stats = new IngestStats();
        stats.parseMethod = fr.parseMethod;
        boolean aiFirst = "ai".equals(fr.parseMethod);

        if (!aiFirst) {
            // 规则优先模式（无可用模型时）：先把规则解析的题目校验+收集,再批量入库
            List<Question> validRuleParsed = new ArrayList<>();
            for (Question q : fr.ruleParsedQuestions) {
                List<ImportValidator.ValidationError> errs = ImportValidator.validateQuestion(q);
                if (errs.isEmpty()) {
                    validRuleParsed.add(q);
                    stats.allValid.add(q);
                } else {
                    stats.allInvalid.add(q);
                    for (ImportValidator.ValidationError ve : errs) {
                        stats.errors.add("规则解析: " + ve.toString());
                    }
                }
            }
            // 批量入库规则解析的题目
            if (!validRuleParsed.isEmpty()) {
                BatchPersistResult bpr = persistBatch(validRuleParsed);
                stats.imported += bpr.imported;
                stats.duplicated += bpr.duplicated;
                stats.failed += bpr.failed;
            }
            stats.ruleCount += validRuleParsed.size();
            fr.ruleQuestionsConsumed = true;  // v7: 已消费，rule_fallback 不得重复入库

            // 实时回调规则解析结果
            if (!fr.ruleParsedQuestions.isEmpty()) {
                final int imp = stats.imported;
                final int dup = stats.duplicated;
                runOnMain(() -> listener.onImportedBatch(imp, dup, stats.failed));
                runOnMain(() -> listener.onPreviewQuestions(new ArrayList<>(stats.allValid)));
            }
        }

        // 如果不需要 AI 兜底，直接返回
        if (chunks == null || chunks.isEmpty()) {
            runOnMain(() -> listener.onStage(Stage.INGEST, "跳过 AI 抽取(规则已完成)"));
            return stats;
        }

        // AI Agent 智能抽取（v5：使用 AIImportAgent 替代简单 LLM 调用）
        runOnMain(() -> listener.onStage(Stage.INGEST, "AI Agent 智能抽取中..."));

        if (!agent.hasAnyModel()) {
            if (stats.allValid.isEmpty()) {
                runOnMain(() -> listener.onError("无可用 AI 模型，请配置在线模型或加载本地模型", null));
            }
            return stats;
        }

        int total = chunks.size();
        // v6: 跨 chunk 去重集合（归一化题干），避免重复入库
        final Set<String> seenAiKeys = new HashSet<>();
        for (int i = 0; i < total; i++) {
            final int idx = i;
            if (cancelled) break;
            String chunk = chunks.get(i);
            // 追加题型上下文：让 Agent 知道默认题型
            if (metaPrefix != null && !chunk.startsWith("DEFAULT_QUESTION_TYPE")) {
                chunk = metaPrefix + chunk;
            }

            // 定期内存检查（本地模式）
            if (i > 0 && i % 5 == 0 && (modelMode == ModelMode.LOCAL_ONLY || modelMode == ModelMode.AUTO)) {
                ModelMemoryManager.MemoryState ms = memoryManager.getMemoryState();
                if (ms == ModelMemoryManager.MemoryState.LOW || ms == ModelMemoryManager.MemoryState.CRITICAL) {
                    System.gc();
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                }
            }

            // 调用 AIImportAgent 执行 Agent 式抽取（含自纠循环）
            // 模型崩溃隔离：单个 chunk 抽取异常不中断整个导入流程
            List<Question> questions;
            try {
                questions = agent.extractFromChunk(chunk, idx, total, new AIImportAgent.AgentCallback() {
                @Override
                public void onStateChanged(AIImportAgent.AgentState state, String detail) {
                    runOnMain(() -> listener.onThinking("[" + state.name() + "] " + detail));
                }

                @Override
                public void onTokenStream(String delta, int tokenCount, float tokPerSec) {
                    runOnMain(() -> listener.onTokenStream(delta, tokenCount, tokPerSec));
                }

                @Override
                public void onThinking(String text) {
                    runOnMain(() -> listener.onThinking(text));
                }

                @Override
                public void onChunkComplete(int chunkIndex, int totalChunks, List<Question> extracted) {
                    // 由外层统一处理
                }

                @Override
                public void onError(String message, Throwable error) {
                    runOnMain(() -> listener.onError(message, error));
                }
            });
            } catch (Exception e) {
                Log.e(TAG, "Chunk " + idx + " AI 抽取异常，跳过继续: " + e.getMessage(), e);
                stats.errors.add("Chunk " + (idx + 1) + " AI异常: " + e.getMessage());
                stats.failed++;
                if (chunkYields != null) chunkYields.add(0);
                runOnMain(() -> listener.onProgress(idx + 1, total, "Chunk " + (idx + 1) + " 异常跳过"));
                continue;
            }

            if (questions == null || questions.isEmpty()) {
                if (chunkYields != null) chunkYields.add(0);
                runOnMain(() -> listener.onProgress(idx + 1, total, "已入库 " + stats.imported + " 题"));
                continue;
            }

            stats.aiParsedCount += questions.size();

            // 去重(规则已解析过的题目/跨 chunk 重复题不再重复入库) + 入库
            List<Question> validAiParsed = new ArrayList<>();
            for (Question q : questions) {
                String dedupKey = normalizeForDedup(q.getQuestionText());
                if (!dedupKey.isEmpty() && !seenAiKeys.add(dedupKey)) {
                    continue;  // 本次导入内已出现过，跳过
                }
                if (isDuplicateOfRuleParsed(q, fr.ruleParsedQuestions)) continue;

                // Agent 已做校验，这里再做一次确保一致性
                List<ImportValidator.ValidationError> errs = ImportValidator.validateQuestion(q);
                if (errs.isEmpty()) {
                    validAiParsed.add(q);
                    stats.allValid.add(q);
                } else {
                    stats.allInvalid.add(q);
                    for (ImportValidator.ValidationError ve : errs) {
                        stats.errors.add("AI解析: " + ve.toString());
                    }
                }
            }
            // 批量入库 AI 解析的题目
            if (!validAiParsed.isEmpty()) {
                BatchPersistResult bpr = persistBatch(validAiParsed);
                stats.imported += bpr.imported;
                stats.duplicated += bpr.duplicated;
                stats.failed += bpr.failed;
            }
            stats.aiCount += validAiParsed.size();
            if (chunkYields != null) chunkYields.add(validAiParsed.size());

            // 实时回调
            final int imp = stats.imported;
            final int dup = stats.duplicated;
            runOnMain(() -> listener.onImportedBatch(imp, dup, stats.failed));
            runOnMain(() -> listener.onPreviewQuestions(new ArrayList<>(stats.allValid)));
            runOnMain(() -> listener.onProgress(idx + 1, total, "已入库 " + imp + " 题，重复 " + dup));
        }

        // v6: AI 优先模式下 AI 抽取零有效结果时，自动降级到规则引擎兜底
        // v7: 仅当预解析题未被规则通道消费过时才允许（避免重复入库）
        if (aiFirst && stats.allValid.isEmpty() && !fr.ruleParsedQuestions.isEmpty()
                && !fr.ruleQuestionsConsumed) {
            runOnMain(() -> listener.onThinking("AI 抽取零结果，自动降级到规则引擎兜底..."));
            List<Question> fallbackValid = new ArrayList<>();
            for (Question q : fr.ruleParsedQuestions) {
                List<ImportValidator.ValidationError> errs = ImportValidator.validateQuestion(q);
                if (errs.isEmpty()) {
                    fallbackValid.add(q);
                    stats.allValid.add(q);
                } else {
                    stats.allInvalid.add(q);
                    for (ImportValidator.ValidationError ve : errs) {
                        stats.errors.add("规则兜底: " + ve.toString());
                    }
                }
            }
            if (!fallbackValid.isEmpty()) {
                BatchPersistResult bpr = persistBatch(fallbackValid);
                stats.imported += bpr.imported;
                stats.duplicated += bpr.duplicated;
                stats.failed += bpr.failed;
                stats.parseMethod = "rule_fallback";
                stats.ruleCount += fallbackValid.size();
                final int imp = stats.imported;
                final int dup = stats.duplicated;
                runOnMain(() -> listener.onImportedBatch(imp, dup, stats.failed));
                runOnMain(() -> listener.onPreviewQuestions(new ArrayList<>(stats.allValid)));
                runOnMain(() -> listener.onThinking("规则引擎兜底成功: 入库 " + fallbackValid.size() + " 题"));
            }
        }
        return stats;
    }

    /** 检查 AI 解析的题目是否与规则已解析的重复 */
    private boolean isDuplicateOfRuleParsed(Question aiQ, List<Question> ruleQuestions) {
        if (aiQ == null || ruleQuestions == null || ruleQuestions.isEmpty()) return false;
        String aiKey = normalizeForDedup(aiQ.getQuestionText());
        if (aiKey.isEmpty()) return false;

        for (Question rq : ruleQuestions) {
            if (aiKey.equals(normalizeForDedup(rq.getQuestionText()))) {
                return true;
            }
        }
        return false;
    }

    /** 去重归一化：移除所有空白字符（含全角空格）并转小写 */
    private String normalizeForDedup(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\u00A0\\u3000]+", "").toLowerCase();
    }

    /** 极简 LLM 调用 */
    private String callLlmStructuredMinimal(String prompt, JSONObject schema) {
        OnlineModelManager.OnlineModelConfig onlineConfig = onlineModelManager.getActiveModel();
        boolean useOnline = (modelMode != ModelMode.LOCAL_ONLY) && onlineConfig != null;

        if (useOnline) {
            try {
                String result = inferenceService.generateStructuredAsync(prompt, onlineConfig, schema, 2048).join();
                result = ToolResultInterpreter.cleanModelOutput(result);
                if (result != null && !result.trim().isEmpty()) {
                    localFailCount.set(0);
                    return result;
                }
            } catch (Exception e) {
                Log.w(TAG, "在线调用失败: " + e.getMessage());
                if (modelMode == ModelMode.ONLINE_ONLY) return null;
            }
        }

        if ((modelMode != ModelMode.ONLINE_ONLY) && !localModelPaused && localChat.isInitialized()) {
            return callLocalLlmMinimal(prompt);
        }
        return null;
    }

    private String callLocalLlmMinimal(String prompt) {
        ModelMemoryManager.MemoryState memState = memoryManager.getMemoryState();
        if (memState == ModelMemoryManager.MemoryState.OUT_OF_MEMORY) return null;
        if (memState == ModelMemoryManager.MemoryState.CRITICAL) {
            System.gc();
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }

        try {
            String raw = localChat.sendMessage(prompt, 1024, 0.1f, 0.9f, 40);
            raw = ToolResultInterpreter.cleanModelOutput(raw);
            localFailCount.set(0);
            return (raw != null && !raw.trim().isEmpty()) ? raw : null;
        } catch (OutOfMemoryError oom) {
            handleLocalFailure("内存溢出");
            emergencyMemoryCleanup();
            return null;
        } catch (Throwable t) {
            handleLocalFailure(t.getClass().getSimpleName());
            return null;
        }
    }

    // ======================== 题目解析辅助 ========================

    private List<Question> extractQuestionsFromJsonObject(JSONObject root) {
        List<Question> list = new ArrayList<>();
        if (root == null) return list;
        try {
            JSONArray arr = root.optJSONArray("questions");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    try {
                        Question q = parseMinimalQuestion(arr.getJSONObject(i));
                        if (q != null) list.add(q);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "题目提取失败: " + e.getMessage());
        }
        return list;
    }

    private Question parseMinimalQuestion(JSONObject jo) {
        if (jo == null) return null;
        Question q = new Question();
        q.setQuestionText(jo.optString("questionText", ""));
        q.setOptionA(jo.optString("optionA", ""));
        q.setOptionB(jo.optString("optionB", ""));
        q.setOptionC(jo.optString("optionC", ""));
        q.setOptionD(jo.optString("optionD", ""));
        q.setCorrectAnswer(jo.optString("correctAnswer", ""));
        q.setQuestionType(jo.optString("questionType", ""));
        q.setCategory(jo.optString("category", ""));
        q.setSubCategory("");
        q.setDifficulty(0);
        q.setPoints(0);
        q.setTimeLimit(0);
        q.setHint("");
        q.setExplanation("");
        q.setAnalysis("");
        q.setKnowledgePoint("");
        q.setTags("");
        q.setAuthor("");
        q.setComment("");
        return q;
    }

    // ======================== 阶段 3.5: v7 质量对账 QA Gate ========================

    /**
     * v7 QA Gate：数量对账（源估算 vs 实际抽取）+ 缺口 chunk 重试。
     * 解决"漏题不可见"痛点：缺口 >10% 时自动重试零产出 chunk 补抽。
     * 异常不影响已入库结果（调用方已 try-catch）。
     */
    private void stageQualityGate(ProfileResult pr, IngestStats stats, List<String> chunks,
                                  List<Integer> chunkYields, ImportListener listener) {
        if (cancelled || pr == null || stats == null) return;
        int sourceEstimate = pr.sourceEstimate;
        stats.sourceEstimate = sourceEstimate;
        if (sourceEstimate <= 0) {
            stats.qaPassed = true;  // 无法估算时不阻断
            return;
        }

        int detected = stats.allValid.size();
        int gap = sourceEstimate - detected;
        if (gap <= sourceEstimate * QA_GAP_TOLERANCE) {
            stats.qaPassed = true;
            final int d = detected;
            runOnMain(() -> listener.onThinking("数量对账通过: 源估算 "
                    + sourceEstimate + " 题 / 实际抽取 " + d + " 题"));
            return;
        }

        final int gapFinal = gap;
        runOnMain(() -> listener.onThinking("数量对账发现缺口: 源估算 " + sourceEstimate
                + " 题, 实际 " + detected + " 题(缺 " + gapFinal + ")，尝试缺口重试..."));

        if (!agent.hasAnyModel() || chunks == null || chunks.isEmpty()) {
            stats.qaPassed = false;
            return;
        }

        // 构建已见题干去重集，补抽题目不与已入库题目重复
        final Set<String> seenKeys = new HashSet<>();
        for (Question q : stats.allValid) {
            seenKeys.add(normalizeForDedup(q.getQuestionText()));
        }

        int retried = 0;
        for (int i = 0; i < chunks.size() && retried < QA_MAX_RETRY_CHUNKS && !cancelled; i++) {
            int yield = (chunkYields != null && i < chunkYields.size()) ? chunkYields.get(i) : -1;
            if (yield != 0) continue;  // 仅重试零产出 chunk
            retried++;
            final int retryIdx = i;
            try {
                runOnMain(() -> listener.onThinking("缺口重试 chunk " + (retryIdx + 1) + "..."));
                List<Question> qs = agent.extractFromChunk(chunks.get(i), i, chunks.size(),
                        new AIImportAgent.AgentCallback() {
                            @Override
                            public void onStateChanged(AIImportAgent.AgentState state, String detail) {
                                runOnMain(() -> listener.onThinking("[缺口重试][" + state.name() + "] " + detail));
                            }
                            @Override
                            public void onTokenStream(String delta, int tokenCount, float tokPerSec) {}
                            @Override
                            public void onThinking(String text) {
                                runOnMain(() -> listener.onThinking(text));
                            }
                            @Override
                            public void onChunkComplete(int chunkIndex, int totalChunks, List<Question> extracted) {}
                            @Override
                            public void onError(String message, Throwable error) {
                                runOnMain(() -> listener.onThinking("缺口重试错误: " + message));
                            }
                        });
                List<Question> recovered = new ArrayList<>();
                for (Question q : qs) {
                    String key = normalizeForDedup(q.getQuestionText());
                    if (key.isEmpty() || !seenKeys.add(key)) continue;
                    if (ImportValidator.validateQuestion(q).isEmpty()) {
                        recovered.add(q);
                    }
                }
                if (!recovered.isEmpty()) {
                    BatchPersistResult bpr = persistBatch(recovered);
                    stats.imported += bpr.imported;
                    stats.duplicated += bpr.duplicated;
                    stats.failed += bpr.failed;
                    stats.allValid.addAll(recovered);
                    stats.aiCount += recovered.size();
                    runOnMain(() -> listener.onPreviewQuestions(new ArrayList<>(stats.allValid)));
                    final int rec = recovered.size();
                    runOnMain(() -> listener.onThinking("缺口重试补回 " + rec + " 题"));
                }
            } catch (Exception e) {
                Log.w(TAG, "缺口 chunk 重试失败: " + e.getMessage());
            }
        }

        stats.retriedChunks = retried;
        int gapAfter = sourceEstimate - stats.allValid.size();
        stats.qaPassed = gapAfter <= sourceEstimate * QA_GAP_TOLERANCE;
        final int finalDetected = stats.allValid.size();
        final boolean passed = stats.qaPassed;
        final int retriedFinal = retried;
        runOnMain(() -> listener.onThinking("QA Gate 完成: 重试 " + retriedFinal + " 块, 当前 "
                + finalDetected + " 题" + (passed ? ", 对账通过" : ", 仍有缺口建议人工复核")));
    }

    // ======================== 阶段 4: DONE ========================

    private AIImportResult finalizeResult(File file, ProfileResult pr, IngestStats stats,
                                          long startMs, ImportListener listener) {
        runOnMain(() -> listener.onStage(Stage.DONE, "完成"));
        // 题型回填：若题目 questionType 为空，使用 SHEET_META 中的默认题型（从 Sheet 名推断而来）
        String defaultType = (pr != null) ? pr.defaultQuestionType : null;
        if (defaultType != null && !defaultType.trim().isEmpty()) {
            int filled = 0;
            for (Question q : stats.allValid) {
                String t = q == null ? null : q.getQuestionType();
                if (t == null || t.trim().isEmpty() || "未分类".equals(t.trim())) {
                    q.setQuestionType(defaultType);
                    filled++;
                }
            }
            for (Question q : stats.allInvalid) {
                String t = q == null ? null : q.getQuestionType();
                if (t == null || t.trim().isEmpty() || "未分类".equals(t.trim())) {
                    q.setQuestionType(defaultType);
                    filled++;
                }
            }
            if (filled > 0) {
                Log.i(TAG, "finalizeResult: 使用默认题型(" + defaultType + ")回填 " + filled + " 题");
            }
        }
        try {
            Application app = asApplication(context);
            if (app != null) {
                ImportHistory history = new ImportHistory(file.getName(), file.getAbsolutePath(),
                        stats.imported + stats.duplicated, stats.failed,
                        System.currentTimeMillis(), "成功");
                new ImportHistoryRepository(app).addImportHistory(history);
            }
        } catch (Exception e) {
            Log.w(TAG, "记录导入历史失败: " + e.getMessage());
        }

        AIImportResult result = new AIImportResult();
        result.setValidQuestions(stats.allValid);
        result.setInvalidQuestions(stats.allInvalid);
        result.setErrorMessages(stats.errors);
        result.setTotalDetected(stats.allValid.size() + stats.allInvalid.size());
        result.setTotalImported(stats.imported + stats.duplicated);
        result.setDuplicatedCount(stats.duplicated);
        result.setImportTimeMs(System.currentTimeMillis() - startMs);
        result.setFieldCoverage(ImportValidator.computeFieldCoverage(stats.allValid));
        result.setSourceFileName(file.getName());
        result.setSuccess(true);

        // v4: 附加解析方法信息
        if (result.getExtraInfo() == null) {
            result.setExtraInfo(new java.util.HashMap<>());
        }
        result.getExtraInfo().put("parseMethod", stats.parseMethod);
        result.getExtraInfo().put("formatDetected",
                pr.fileProfile != null ? pr.fileProfile.formatHint : "未知");
        if (pr.usedOcr) {
            result.getExtraInfo().put("usedOcr", "true");
        }

        // v7: 导入质量报告
        result.setSourceEstimate(stats.sourceEstimate);
        result.setQaPassed(stats.qaPassed);
        result.setRetriedChunks(stats.retriedChunks);
        result.setRuleCount(stats.ruleCount);
        result.setAiCount(stats.aiCount);

        // v7: 失败复核队列 — 无效题目+错误原因持久化到复核文件（UI 可查看）
        if (!stats.allInvalid.isEmpty()) {
            String reviewPath = writeFailureReviewQueue(file, stats);
            if (reviewPath != null) {
                result.getExtraInfo().put("reviewQueuePath", reviewPath);
                Log.i(TAG, "失败复核队列已写入: " + reviewPath);
            }
        }

        return result;
    }

    /**
     * v7: 失败复核队列持久化 — 将无效题目与错误原因写入 JSON 复核文件，
     * 供后续人工查看/单题重试。写入失败不影响导入主流程。
     */
    private String writeFailureReviewQueue(File sourceFile, IngestStats stats) {
        try {
            File dir = new File(context.getFilesDir(), "ai_import_review");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File reviewFile = new File(dir, "review_" + System.currentTimeMillis() + ".json");

            JSONObject root = new JSONObject();
            root.put("sourceFile", sourceFile.getName());
            root.put("createdAt", System.currentTimeMillis());
            root.put("parseMethod", stats.parseMethod);
            JSONArray items = new JSONArray();
            int max = Math.min(stats.allInvalid.size(), 500);  // 上限保护
            for (int i = 0; i < max; i++) {
                Question q = stats.allInvalid.get(i);
                JSONObject item = new JSONObject();
                item.put("questionText", nz(q.getQuestionText()));
                item.put("correctAnswer", nz(q.getCorrectAnswer()));
                item.put("questionType", nz(q.getQuestionType()));
                items.put(item);
            }
            root.put("invalidCount", stats.allInvalid.size());
            root.put("invalidQuestions", items);
            JSONArray errArr = new JSONArray();
            int errMax = Math.min(stats.errors.size(), 200);
            for (int i = 0; i < errMax; i++) errArr.put(stats.errors.get(i));
            root.put("errors", errArr);

            try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(reviewFile), java.nio.charset.StandardCharsets.UTF_8)) {
                w.write(root.toString());
            }
            return reviewFile.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "写入失败复核队列异常: " + e.getMessage());
            return null;
        }
    }

    // ======================== 递归分块（v7: chunkMax 自适应参数化） ========================

    private List<String> recursiveChunkText(String text, int chunkMax) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;

        List<String> segments = splitByQuestionBoundary(text);
        if (segments.isEmpty()) {
            segments.add(text);
        }

        for (String seg : segments) {
            int segTokens = estimateTokens(seg);
            if (segTokens <= chunkMax) {
                result.add(seg);
            } else {
                result.addAll(recursiveSplit(seg, 0, chunkMax));
            }
        }

        return mergeShortChunks(result, chunkMax);
    }

    /** v7: 判断文本是否以表格为主（>40% 非空行以 | 开头） */
    private boolean isMostlyTable(String text) {
        if (text == null) return false;
        int total = 0, table = 0;
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            total++;
            if (t.startsWith("|")) table++;
        }
        return total >= 3 && table * 10 > total * 4;
    }

    /**
     * v7: 表格感知分块 — 以"完整行组"为单位（表头+分隔行+N 数据行），行内字段不拆散。
     * 每个 chunk 首部附 [SOURCE_ROWS:start-end] provenance 标记，供对账定位。
     */
    private List<String> chunkTableByRowGroups(String text, int chunkMax) {
        List<String> result = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        List<String> prefix = new ArrayList<>();      // 表格前的非表格行（如 SHEET_META 注释）
        String headerLine = null, sepLine = null;
        List<String> buffer = new ArrayList<>();       // 当前行组的数据行
        int groupStartRow = -1, rowNum = 0;
        int overhead = estimateTokens("[SOURCE_ROWS:00000-00000]\n");

        for (String line : lines) {
            String t = line.trim();
            if (!t.startsWith("|")) {
                if (headerLine == null && !t.isEmpty()) prefix.add(line);
                continue;
            }
            if (headerLine == null) { headerLine = line; continue; }
            if (sepLine == null && t.replaceAll("[^\\-:]", "").length() >= t.length() / 2) {
                sepLine = line;  // 分隔行
                continue;
            }
            // 数据行
            rowNum++;
            if (groupStartRow < 0) groupStartRow = rowNum;
            int headerTokens = estimateTokens(headerLine)
                    + (sepLine != null ? estimateTokens(sepLine) : 0)
                    + overhead;
            int curTokens = headerTokens;
            for (String b : buffer) curTokens += estimateTokens(b);
            if (!buffer.isEmpty() && curTokens + estimateTokens(line) > chunkMax) {
                result.add(buildTableChunk(prefix, headerLine, sepLine, buffer, groupStartRow, rowNum - 1));
                buffer = new ArrayList<>();
                groupStartRow = rowNum;
            }
            buffer.add(line);
        }
        if (!buffer.isEmpty()) {
            result.add(buildTableChunk(prefix, headerLine, sepLine, buffer, groupStartRow, rowNum));
        }
        if (result.isEmpty() && text.trim().length() > 0) {
            // 非标准表格结构回退到递归分块
            return recursiveChunkText(text, chunkMax);
        }
        return result;
    }

    private String buildTableChunk(List<String> prefix, String headerLine, String sepLine,
                                   List<String> rows, int startRow, int endRow) {
        StringBuilder sb = new StringBuilder();
        sb.append("[SOURCE_ROWS:").append(startRow).append("-").append(endRow).append("]\n");
        for (String p : prefix) sb.append(p).append("\n");
        if (headerLine != null) sb.append(headerLine).append("\n");
        if (sepLine != null) sb.append(sepLine).append("\n");
        for (String r : rows) sb.append(r).append("\n");
        return sb.toString();
    }

    private List<String> splitByQuestionBoundary(String text) {
        List<String> segments = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            boolean isBoundary = trimmed.startsWith("Q:")
                    || trimmed.startsWith("题目")
                    || trimmed.matches("^\\s*\\d+\\s*[\\.、．)]\\s.*")
                    || trimmed.matches("^第[\\d一二三四五六七八九十百]+题.*")
                    || trimmed.matches("^\\[\\s*\\d+\\s*\\].*");

            if (isBoundary && current.length() > 0) {
                segments.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append("\n");
        }
        if (current.length() > 0) {
            segments.add(current.toString());
        }
        return segments;
    }

    private List<String> recursiveSplit(String text, int sepLevel, int chunkMax) {
        List<String> result = new ArrayList<>();
        if (sepLevel >= CHUNK_SEPARATORS.length) {
            result.addAll(forceSplitByLength(text, chunkMax));
            return result;
        }

        String sep = CHUNK_SEPARATORS[sepLevel];
        String[] parts = text.split(Pattern.quote(sep), -1);

        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String candidate = current.length() == 0 ? part : current.toString() + sep + part;
            if (estimateTokens(candidate) <= chunkMax) {
                if (current.length() > 0) current.append(sep);
                current.append(part);
            } else {
                if (current.length() > 0) {
                    int curTokens = estimateTokens(current.toString());
                    if (curTokens >= CHUNK_MIN_TOKENS) {
                        result.add(current.toString());
                    } else {
                        current.append(sep).append(part);
                        if (estimateTokens(current.toString()) > chunkMax) {
                            result.addAll(recursiveSplit(current.toString(), sepLevel + 1, chunkMax));
                            current = new StringBuilder();
                        }
                        continue;
                    }
                }
                if (estimateTokens(part) > chunkMax) {
                    result.addAll(recursiveSplit(part, sepLevel + 1, chunkMax));
                    current = new StringBuilder();
                } else {
                    current = new StringBuilder(part);
                }
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    private List<String> forceSplitByLength(String text, int chunkMax) {
        List<String> result = new ArrayList<>();
        int charsPerChunk = chunkMax;
        for (int i = 0; i < text.length(); i += charsPerChunk) {
            int end = Math.min(i + charsPerChunk, text.length());
            result.add(text.substring(i, end));
        }
        return result;
    }

    private List<String> mergeShortChunks(List<String> chunks, int chunkMax) {
        List<String> result = new ArrayList<>();
        for (String chunk : chunks) {
            if (!result.isEmpty() && estimateTokens(chunk) < CHUNK_MIN_TOKENS) {
                int last = result.size() - 1;
                String merged = result.get(last) + chunk;
                if (estimateTokens(merged) <= chunkMax) {
                    result.set(last, merged);
                } else {
                    result.add(chunk);
                }
            } else {
                result.add(chunk);
            }
        }
        return result;
    }

    // ======================== 预过滤 ========================

    private boolean preFilterChunk(String chunk) {
        if (chunk == null || chunk.trim().isEmpty()) return false;
        String trimmed = chunk.trim();
        if (trimmed.length() < 20) return false;

        String lower = trimmed.toLowerCase();
        if (lower.startsWith("目录") || lower.startsWith("前言") || lower.startsWith("序言")
                || lower.startsWith("版权") || lower.startsWith("参考文献")
                || (lower.startsWith("答案") && trimmed.length() < 100)) {
            return false;
        }

        // v6 修复漏题：表格类 chunk（Markdown/Tab 分隔）直接放行，
        // 否则不含"题号/题目"关键词的表格数据块会被误过滤导致整块丢题
        if (looksLikeTableChunk(trimmed)) return true;

        return QUESTION_PATTERN.matcher(trimmed).find();
    }

    /** 判断 chunk 是否为表格结构（至少 2 行以 | 开头或含 Tab 分隔的行） */
    private boolean looksLikeTableChunk(String chunk) {
        String[] lines = chunk.split("\n", -1);
        int tableLines = 0;
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty() && (t.startsWith("|") || t.contains("\t"))) {
                tableLines++;
                if (tableLines >= 2) return true;
            }
        }
        return false;
    }

    // ======================== Token 管理 ========================

    private int estimateTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        int chinese = 0, englishWords = 0;
        boolean inWord = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                chinese++;
                if (inWord) { englishWords++; inWord = false; }
            } else if (Character.isLetterOrDigit(c)) {
                inWord = true;
            } else {
                if (inWord) { englishWords++; inWord = false; }
            }
        }
        if (inWord) englishWords++;
        return Math.round(chinese * 1.5f) + englishWords;
    }

    private String truncateToTokenBudget(String text, int maxTokens) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder();
        int tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int addition = (c >= 0x4E00 && c <= 0x9FFF) ? 2 : 1;
            if (tokens + addition > maxTokens) break;
            sb.append(c);
            tokens += addition;
        }
        return sb.toString();
    }

    // ======================== LLM 辅助 ========================

    private boolean hasAnyModel() {
        // v5: 委托给 Agent 检查（Agent 独立管理在线/本地模型状态）
        return agent.hasAnyModel();
    }

    private void handleLocalFailure(String reason) {
        int fails = localFailCount.incrementAndGet();
        if (fails >= LOCAL_FAIL_THRESHOLD) {
            localModelPaused = true;
            final String msg = "本地模型已暂停(连续失败" + LOCAL_FAIL_THRESHOLD + "次：" + reason + ")";
            runOnMain(() -> {
                if (agentErrorCallback != null) agentErrorCallback.accept(msg);
            });
        }
    }

    private void emergencyMemoryCleanup() {
        try {
            System.gc();
            System.runFinalization();
            Thread.sleep(300);
            System.gc();
        } catch (InterruptedException ignored) {}
    }

    // ======================== 入库 ========================

    /** 批量入库结果统计 */
    private static class BatchPersistResult {
        int imported;   // 新增数
        int duplicated; // 更新数(重复)
        int failed;     // 失败数
    }

    /**
     * 批量入库:一次查询去重 + 批量插入 + 逐个更新。
     * 替代旧的 persistOneQuestion 逐题 search+insert,大幅减少 DB 操作次数。
     */
    private BatchPersistResult persistBatch(List<Question> questions) {
        BatchPersistResult result = new BatchPersistResult();
        if (questions == null || questions.isEmpty()) return result;

        // ========== 诊断：GarbledTextFixer处理前首道题 ==========
        if (!questions.isEmpty()) {
            ImportDebugTracer.traceQuestion("【8b】入库前GarbledFixer前首题", questions.get(0));
        }

        // ⚠ 乱码最终防线：入库前对每道题的所有 String 字段强制清洗、乱码修复、纯乱码字段置空
        List<Question> cleanedQuestions = new ArrayList<>();
        int garbledCount = 0;
        int questionCounter = 0;
        for (Question q : questions) {
            Question fixed = GarbledTextFixer.fixQuestion(q);
            // ========== 诊断：GarbledTextFixer处理后首道题 ==========
            if (cleanedQuestions.isEmpty() && questionCounter == 0) {
                ImportDebugTracer.traceQuestion("【9】入库前GarbledFixer后首题", fixed);
            }
            questionCounter++;
            // 清洗后题干为空，丢弃（避免只有乱码入库）
            if (fixed.getQuestionText() == null || fixed.getQuestionText().trim().isEmpty()) {
                garbledCount++;
                result.failed++;
                continue;
            }
            cleanedQuestions.add(fixed);
        }
        if (garbledCount > 0) {
            Log.d(TAG, "入库前清洗: 丢弃" + garbledCount + "道乱码空题");
        }
        questions = cleanedQuestions;
        if (questions.isEmpty()) return result;

        long now = System.currentTimeMillis();

        // 1. 一次性获取数据库中所有已有题目,构建去重索引
        Set<String> existingKeys = new HashSet<>();
        Map<String, Question> existingMap = new HashMap<>();
        try {
            List<Question> all = databaseManager.getAllQuestions().get();
            if (all != null) {
                for (Question q : all) {
                    String key = buildDedupKey(q.getQuestionText(), q.getCategory());
                    existingKeys.add(key);
                    existingMap.put(key, q);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "获取已有题目失败: " + e.getMessage());
        }

        // 2. 分桶:新题(insert) vs 重复题(update)
        List<Question> toInsert = new ArrayList<>();
        List<Question> toUpdate = new ArrayList<>();

        for (Question q : questions) {
            q.setSource("AI导入");
            q.setCreatedAt(now);
            q.setUpdatedAt(now);

            // 入库校验：选择题必须带选项，否则丢弃（防止坏数据入库）
            String qType = q.getQuestionType();
            boolean isChoice = qType != null && (qType.contains("单选") || qType.contains("多选"));
            if (isChoice && q.getOptionCount() < 2) {
                Log.w(TAG, "丢弃无选项选择题: " + q.getQuestionText());
                result.failed++;
                continue;
            }
            // 答案格式规范化："A;B;C" -> "ABC"（仅纯字母答案）
            q.setCorrectAnswer(com.oilquiz.app.model.Question.normalizeChoiceAnswer(q.getCorrectAnswer()));

            String key = buildDedupKey(q.getQuestionText(), q.getCategory());
            if (existingKeys.contains(key)) {
                // 重复:更新已有记录
                Question existing = existingMap.get(key);
                if (existing != null) {
                    q.setId(existing.getId());
                    toUpdate.add(q);
                    existingKeys.remove(key);  // 避免批内重复更新
                }
            } else {
                // 新题:插入
                toInsert.add(q);
                existingKeys.add(key);  // 避免批内重复插入
            }
        }

        // ========== 诊断：DB写入前最终首题 ==========
        if (!toInsert.isEmpty()) {
            ImportDebugTracer.traceQuestion("【10】DB插入前最终首题", toInsert.get(0));
        } else if (!toUpdate.isEmpty()) {
            ImportDebugTracer.traceQuestion("【10】DB更新前最终首题", toUpdate.get(0));
        }

        // 3. 批量插入新题(一次 insertAll)
        if (!toInsert.isEmpty()) {
            try {
                Boolean success = databaseManager.addQuestions(toInsert).get();
                if (success != null && success) {
                    result.imported = toInsert.size();
                } else {
                    result.failed = toInsert.size();
                }
            } catch (Exception e) {
                Log.w(TAG, "批量插入失败(" + toInsert.size() + "题): " + e.getMessage());
                result.failed = toInsert.size();
            }
        }

        // 4. 逐个更新重复题
        for (Question q : toUpdate) {
            try {
                Boolean success = databaseManager.updateQuestion(q).get();
                if (success != null && success) {
                    result.duplicated++;
                } else {
                    result.failed++;
                }
            } catch (Exception e) {
                Log.w(TAG, "更新失败: " + e.getMessage());
                result.failed++;
            }
        }

        Log.i(TAG, "批量入库: 新增=" + result.imported + " 更新=" + result.duplicated + " 失败=" + result.failed);
        return result;
    }

    /** 构建去重键: questionText|category */
    private String buildDedupKey(String questionText, String category) {
        String qt = (questionText != null) ? questionText.trim() : "";
        String cat = (category != null) ? category.trim() : "";
        return qt + "|" + cat;
    }

    // ======================== 通用辅助 ========================

    private boolean looksLikeGarbled(String text) {
        if (text == null) return true;
        int len = Math.min(text.length(), 2000);
        if (len == 0) return false;
        int repl = 0;
        for (int i = 0; i < len; i++) {
            if (text.charAt(i) == '\uFFFD') repl++;
        }
        return repl > len * 0.05;
    }

    private void runOnMain(Runnable r) { mainHandler.post(r); }

    private Application asApplication(Context ctx) {
        if (ctx instanceof Application) return (Application) ctx;
        Context app = ctx.getApplicationContext();
        if (app instanceof Application) return (Application) app;
        return null;
    }

    private boolean equalsStr(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}