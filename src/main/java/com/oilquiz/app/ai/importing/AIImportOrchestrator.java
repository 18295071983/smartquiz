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
    private static final int CHUNK_MAX_TOKENS = 600;
    private static final int CHUNK_MIN_TOKENS = 100;
    private static final float CHUNK_OVERLAP_RATIO = 0.10f;

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
        try {
            stats = stageIngest(fr.remainingChunks, fr, listener);
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
    }

    private ProfileResult stageProfile(File file, ImportListener listener) {
        Log.i(TAG, "stageProfile 开始: " + file.getName());
        runOnMain(() -> listener.onStage(Stage.PROFILE, "识别文件..."));

        // 1. 文件基本信息识别
        FileProfiler.FileProfile fp = FileProfiler.profile(file);
        String fileName = file.getName();
        Log.i(TAG, "stageProfile 文件类型: " + (fp != null ? fp.formatHint : "null"));

        // 2. 利用项目已有解析库提取文本（v4：复用 FileContentExtractor）
        String rawText = extractFileContent(file, fp);
        Log.i(TAG, "stageProfile extractFileContent 返回: len=" + (rawText == null ? -1 : rawText.length()));
        ImportDebugTracer.trace("【0】stageProfile入口", fileName + " | rawText len=" + (rawText == null ? -1 : rawText.length()));
        if (rawText == null || rawText.trim().isEmpty()) {
            runOnMain(() -> listener.onError("文件内容为空或读取失败: " + fileName, null));
            return null;
        }

        // 2.5. 解析 SHEET_META（如：题型/Sheet名），供后续 questionType 回填
        java.util.Map<String, String> sheetMeta = ExcelSheetPicker.parseSheetMeta(rawText);
        String defaultQuestionType = sheetMeta.get("questionType");
        String sourceSheetName = sheetMeta.get("sheetName");
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

        // 5. 递归分块
        List<String> rawChunks = recursiveChunkText(processedText);
        if (rawChunks.isEmpty()) {
            runOnMain(() -> listener.onError("文件分块后为空: " + fileName, null));
            return null;
        }

        int estCount = fp != null ? fp.estimatedCount : 0;
        String formatHint = fp != null ? fp.formatHint : "未知";
        runOnMain(() -> listener.onThinking(
                "预处理完成: 预估 " + estCount + " 题, 格式:" + formatHint
                        + ", 原始文本 " + rawText.length() + " 字符"));

        // 6. 预过滤 + overlap
        List<String> filtered = new ArrayList<>();
        int skipped = 0;
        for (String chunk : rawChunks) {
            if (preFilterChunk(chunk)) {
                filtered.add(chunk);
            } else {
                skipped++;
            }
        }

        List<String> withOverlap = addOverlap(filtered);

        final int total = withOverlap.size();
        final int skippedFinal = skipped;
        runOnMain(() -> listener.onProgress(0, total,
                "共 " + total + " 块" + (skippedFinal > 0 ? " (过滤 " + skippedFinal + " 空块)" : "")));

        ProfileResult pr = new ProfileResult();
        pr.fullText = processedText;
        pr.chunks = withOverlap;
        pr.fileProfile = fp;
        pr.fileName = fileName;
        pr.filteredCount = skipped;
        pr.defaultQuestionType = defaultQuestionType;
        pr.sourceSheetName = sourceSheetName;
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
        String parseMethod = "ai";     // rule / ai / hybrid
        /** AI 动态智能映射结果（标准字段名→列索引），用于辅助 AI 抽取 */
        Map<String, Integer> smartFieldMapping = null;
        /** AI 识别的字段映射描述（用于 UI 展示） */
        List<String> aiResolvedFields = new ArrayList<>();
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

        // 2. 规则引擎快速解析（高置信度格式）
        if (QuestionFormatDetector.canUseRuleParser(detection)) {
            runOnMain(() -> listener.onThinking("启用规则引擎快速解析..."));

            RuleBasedQuestionParser.ParseResult parseResult =
                    RuleBasedQuestionParser.parse(pr.fullText, detection.format);

            fr.ruleParsedQuestions = parseResult.questions;
            fr.ruleParsedCount = parseResult.ruleParsed;

            runOnMain(() -> listener.onThinking(
                    "规则引擎解析出 " + fr.ruleParsedCount + " 道题"));

            // 判断是否需要 AI 兜底
            if (fr.ruleParsedCount > 0) {
                if (detection.confidence >= 0.85f) {
                    // 高置信度：全部走规则，不需要 AI
                    fr.parseMethod = "rule";
                    fr.remainingChunks = new ArrayList<>();
                    runOnMain(() -> listener.onProgress(1, 1,
                            "规则解析完成: " + fr.ruleParsedCount + " 题"));
                } else {
                    // 中置信度：规则为主，AI 兜底剩余 chunk
                    fr.parseMethod = "hybrid";
                    fr.remainingChunks = pr.chunks;
                }
            } else {
                // 规则解析失败，全部交给 AI
                fr.parseMethod = "ai";
                fr.remainingChunks = pr.chunks;
            }
        } else {
            // 低置信度：全部交给 AI
            fr.parseMethod = "ai";
            fr.remainingChunks = pr.chunks;
        }

        // 3. AI 动态智能字段映射（当有表格结构时，提取表头并通过 AI 识别未知字段）
        if (fr.remainingChunks != null && !fr.remainingChunks.isEmpty()) {
            runOnMain(() -> listener.onThinking("构建 AI 动态字段映射..."));
            try {
                List<String> headers = extractTableHeaders(pr.fullText, String.valueOf(detection.format));
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

    // ======================== 阶段 3: INGEST ========================

    private static class IngestStats {
        int imported, duplicated, failed;
        List<Question> allValid = new ArrayList<>();
        List<Question> allInvalid = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int aiParsedCount = 0;
        String parseMethod = "ai";
    }

    private IngestStats stageIngest(List<String> chunks, FormatResult fr, ImportListener listener) {
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

        // 先把规则解析的题目校验+收集,再批量入库
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

        // 实时回调规则解析结果
        if (!fr.ruleParsedQuestions.isEmpty()) {
            final int imp = stats.imported;
            final int dup = stats.duplicated;
            runOnMain(() -> listener.onImportedBatch(imp, dup, stats.failed));
            runOnMain(() -> listener.onPreviewQuestions(new ArrayList<>(stats.allValid)));
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
                runOnMain(() -> listener.onProgress(idx + 1, total, "Chunk " + (idx + 1) + " 异常跳过"));
                continue;
            }

            if (questions == null || questions.isEmpty()) {
                runOnMain(() -> listener.onProgress(idx + 1, total, "已入库 " + stats.imported + " 题"));
                continue;
            }

            stats.aiParsedCount += questions.size();

            // 去重(规则已解析过的题目不再重复入库) + 入库
            List<Question> validAiParsed = new ArrayList<>();
            for (Question q : questions) {
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

            // 实时回调
            final int imp = stats.imported;
            final int dup = stats.duplicated;
            runOnMain(() -> listener.onImportedBatch(imp, dup, stats.failed));
            runOnMain(() -> listener.onPreviewQuestions(new ArrayList<>(stats.allValid)));
            runOnMain(() -> listener.onProgress(idx + 1, total, "已入库 " + imp + " 题，重复 " + dup));
        }
        return stats;
    }

    /** 检查 AI 解析的题目是否与规则已解析的重复 */
    private boolean isDuplicateOfRuleParsed(Question aiQ, List<Question> ruleQuestions) {
        if (aiQ == null || ruleQuestions == null) return false;
        String aiStem = aiQ.getQuestionText();
        if (aiStem == null || aiStem.trim().isEmpty()) return false;

        String aiTrimmed = aiStem.trim();
        for (Question rq : ruleQuestions) {
            String rqStem = rq.getQuestionText();
            if (rqStem != null && aiTrimmed.equals(rqStem.trim())) {
                return true;
            }
        }
        return false;
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
        q.setExtraOptions("");
        return q;
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

        return result;
    }

    // ======================== 递归分块 ========================

    private List<String> recursiveChunkText(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;

        List<String> segments = splitByQuestionBoundary(text);
        if (segments.isEmpty()) {
            segments.add(text);
        }

        for (String seg : segments) {
            int segTokens = estimateTokens(seg);
            if (segTokens <= CHUNK_MAX_TOKENS) {
                result.add(seg);
            } else {
                result.addAll(recursiveSplit(seg, 0));
            }
        }

        return mergeShortChunks(result);
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

    private List<String> recursiveSplit(String text, int sepLevel) {
        List<String> result = new ArrayList<>();
        if (sepLevel >= CHUNK_SEPARATORS.length) {
            result.addAll(forceSplitByLength(text));
            return result;
        }

        String sep = CHUNK_SEPARATORS[sepLevel];
        String[] parts = text.split(Pattern.quote(sep), -1);

        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String candidate = current.length() == 0 ? part : current.toString() + sep + part;
            if (estimateTokens(candidate) <= CHUNK_MAX_TOKENS) {
                if (current.length() > 0) current.append(sep);
                current.append(part);
            } else {
                if (current.length() > 0) {
                    int curTokens = estimateTokens(current.toString());
                    if (curTokens >= CHUNK_MIN_TOKENS) {
                        result.add(current.toString());
                    } else {
                        current.append(sep).append(part);
                        if (estimateTokens(current.toString()) > CHUNK_MAX_TOKENS) {
                            result.addAll(recursiveSplit(current.toString(), sepLevel + 1));
                            current = new StringBuilder();
                        }
                        continue;
                    }
                }
                if (estimateTokens(part) > CHUNK_MAX_TOKENS) {
                    result.addAll(recursiveSplit(part, sepLevel + 1));
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

    private List<String> forceSplitByLength(String text) {
        List<String> result = new ArrayList<>();
        int charsPerChunk = CHUNK_MAX_TOKENS;
        for (int i = 0; i < text.length(); i += charsPerChunk) {
            int end = Math.min(i + charsPerChunk, text.length());
            result.add(text.substring(i, end));
        }
        return result;
    }

    private List<String> mergeShortChunks(List<String> chunks) {
        List<String> result = new ArrayList<>();
        for (String chunk : chunks) {
            if (!result.isEmpty() && estimateTokens(chunk) < CHUNK_MIN_TOKENS) {
                int last = result.size() - 1;
                result.set(last, result.get(last) + chunk);
            } else {
                result.add(chunk);
            }
        }
        return result;
    }

    private List<String> addOverlap(List<String> chunks) {
        if (chunks.size() <= 1) return new ArrayList<>(chunks);
        List<String> result = new ArrayList<>(chunks.size());
        result.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            String cur = chunks.get(i);
            int overlapLen = Math.round(prev.length() * CHUNK_OVERLAP_RATIO);
            if (overlapLen > 0) {
                int start = Math.max(0, prev.length() - overlapLen);
                cur = prev.substring(start) + cur;
            }
            result.add(cur);
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

        return QUESTION_PATTERN.matcher(trimmed).find();
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