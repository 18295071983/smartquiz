package com.oilquiz.app.ai.importing.v2;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.model.ModelManager;
import com.oilquiz.app.ai.model.ModelManager.ModelFileInfo;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.OnlineInferenceService;
import com.oilquiz.app.ai.util.PromptBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 独立轻量化专用推理引擎 —— 仅服务题库导入两大推理任务。
 * <p>
 * 完全剥离通用对话引擎的人设、多轮聊天缓存、冗余工具描述：
 * <ul>
 *   <li>无聊天/问答/总结等冗余能力，仅支持字段映射推理、缺失字段填充</li>
 *   <li>极简提示词：仅单行短句约束 + 1 组最简输出样例，适配 Qwen2.5-VL 7B Q4_K_M</li>
 *   <li>每次推理前强制清空全部 KV 缓存，无历史上下文残留</li>
 *   <li>常驻模式（推荐）/临时一次性会话模式；闲置 5 分钟自动卸载释放内存</li>
 *   <li>自动迭代重试（最多 3 轮熔断），输出经四层后置硬过滤</li>
 * </ul>
 */
public class ImportLlmEngine {

    private static final String TAG = "ImportLlmEngine";

    /** 极简全局约束基线 */
    private static final String BASE_RULE = "仅输出单行JSON，无任何多余文字、符号、解释。";
    /** 第2轮追加约束 */
    private static final String FIX_ROUND2 = "严格照搬样例格式，禁止任何文字解释。";
    /** 第3轮追加约束 */
    private static final String FIX_ROUND3 = "只允许输出一个合法JSON对象，首字符必须是{，末字符必须是}。";

    /** 最大自动重试轮数（熔断） */
    private static final int MAX_INFER_ROUNDS = 3;
    /** 推理上下文窗口 */
    private static final int INFER_CTX = 4096;
    private static final int INFER_THREADS = 6;
    /** 映射任务输出 Token 上限 */
    private static final int MAPPING_MAX_TOKENS = 512;
    /** 填充任务输出 Token 上限 */
    private static final int FILL_MAX_TOKENS = 256;
    /** 闲置自动卸载时间（5 分钟） */
    private static final long IDLE_UNLOAD_MS = 5 * 60 * 1000L;
    /** 熔断阈值：native 推理连续崩溃达到该次数后进程内永久停用本地 AI 推理，自动切换在线模型兜底 */
    private static final int MAX_CONSECUTIVE_CRASHES = 3;
    /** 在线兜底单次请求超时（秒） */
    private static final long ONLINE_TIMEOUT_SEC = 60;
    /** 在线兜底输出 Token 下限：本地 FILL_MAX_TOKENS(256) 对批量填充 JSON 过小，
     *  在线模型生成到一半被截断导致整批 JSON 解析失败白白耗时，在线侧放宽至 2048 */
    private static final int ONLINE_MIN_TOKENS = 2048;

    /** 全局熔断状态（进程内跨引擎实例共享，避免批量导入每个文件重复踩坑） */
    private static volatile int sGlobalCrashes = 0;
    private static volatile boolean sGlobalBroken = false;
    private static volatile boolean sOnlineAnnounced = false;
    /** 在线兜底连续失败计数；达到阈值后停用在线调用（断网保护，避免批量导入被逐次请求拖慢） */
    private static volatile int sOnlineFailures = 0;
    private static final int MAX_ONLINE_FAILURES = 2;

    /** 字段映射推理结果 */
    public static class MappingResult {
        public boolean valid;
        /** 标准字段 → 源列名 */
        public Map<String, String> mapping;
        /** 重试轮次 */
        public int rounds;
        /** 使用的工具调用次数 */
        public int toolCalls;
        /** 结果来源 ai/tool/fallback */
        public String source = "ai";
        public String failReason;
    }

    /** 缺失字段填充推理结果（含题型推断） */
    public static class FillResult {
        public boolean valid;
        public String category;
        public int difficulty = 1;
        public String explanation = "";
        public String questionType = "";
        public String failReason;
    }

    /** 表头行识别推理结果（表头无法自动检测时启用） */
    public static class HeaderResult {
        public boolean valid;
        /** 真实表头行号（0-based）；-1 = 无表头（首行即数据） */
        public int headerRow = -2;
        /** 标准字段 → 源列索引（0-based） */
        public Map<String, Integer> mapping = new java.util.LinkedHashMap<>();
        public int rounds;
        public String failReason;
    }

    /** 引擎日志回调（可选） */
    public interface EngineListener {
        void onLog(String message);
    }

    private final Context context;
    /** 底层 GGUF 模型推理内核（复用 LlamaHelper JNI） */
    private final ModelManager modelManager;
    /** 工具调度管理器 */
    private final ImportToolManager toolManager;

    private volatile boolean modelLoaded = false;
    /** 常驻模式标志：false=临时一次性会话，推理完立即释放 */
    private volatile boolean residentMode = true;
    private String loadedModelPath;

    private Timer idleTimer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private EngineListener listener;
    /** 本地 AI 编排引擎引用（历史参数：仅用于展示模型信息/日志提示。
     *  当前字段映射与填充推理仍直接走本地模型推理，未转发给 Orchestrator——
     *  若需接入其 Agent 管线需为其补充细粒度 inferMapping/inferFills API） */
    private com.oilquiz.app.ai.importing.AIImportOrchestrator localOrchestrator;

    public ImportLlmEngine(Context context) {
        this.context = context.getApplicationContext();
        this.modelManager = new ModelManager(this.context);
        this.toolManager = new ImportToolManager(this.context);
    }

    /** 使用本地 AI 引擎的构造方式（避免 sign6 错误） */
    public ImportLlmEngine(Context context, com.oilquiz.app.ai.importing.AIImportOrchestrator orchestrator) {
        this.context = context.getApplicationContext();
        this.modelManager = new ModelManager(this.context);
        this.toolManager = new ImportToolManager(this.context);
        this.localOrchestrator = orchestrator;
    }

    public void setListener(EngineListener listener) {
        this.listener = listener;
    }

    public ImportToolManager getToolManager() {
        return toolManager;
    }

    public boolean isModelLoaded() {
        return modelLoaded;
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        final EngineListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onLog(msg));
        }
    }

    // ==================== 模型生命周期 ====================

    /**
     * 常驻模型模式（推荐）：APP 启动后加载一次常驻内存，
     * 无需重复加载大权重，适配高频批量导入场景。
     */
    public synchronized boolean loadModelOnce() {
        if (modelLoaded) return true;
        residentMode = true;
        return doLoadModel();
    }

    /**
     * 临时一次性会话模式：触发推理临时加载，单次推理完成后立即释放权重。
     * 适合低频少量导入，降低长期内存占用。
     */
    public synchronized boolean tempLoadModel() {
        if (modelLoaded) return true;
        residentMode = false;
        return doLoadModel();
    }

    private boolean doLoadModel() {
        // 复用已加载的模型：AIService/对话模块若已加载本地模型则直接复用。
        // 实测在 Vulkan/Adreno 后端上，销毁现有上下文后重新 initModel 产生的新上下文
        // 首次 decode 必崩（signal 6/11），而复用现有上下文的推理一直正常，
        // 因此存在可用内核时绝不重复初始化。
        try {
            if (LlamaHelper.isModelInitialized()) {
                modelLoaded = true;
                log("复用已加载的本地模型，跳过重复初始化");
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "检测模型状态异常，尝试全新加载: " + t.getMessage());
        }
        String path = pickModelPath();
        if (path == null) {
            log("未找到本地 GGUF 模型，请先下载/导入（推荐 Qwen2.5-VL-7B Q4_K_M）");
            return false;
        }
        long t0 = System.currentTimeMillis();
        try {
            int ret = LlamaHelper.initModel(path, INFER_CTX, INFER_THREADS);
            if (ret == 0) {
                modelLoaded = true;
                loadedModelPath = path;
                log("导入引擎模型加载成功(" + (System.currentTimeMillis() - t0) + "ms): "
                        + new java.io.File(path).getName());
                return true;
            }
            log("模型加载失败, initModel 返回 " + ret);
            return false;
        } catch (Throwable t) {
            log("模型加载异常: " + t.getMessage());
            return false;
        }
    }

    /** 选型：优先 Qwen 7B GGUF，其次体积最大的 GGUF */
    private String pickModelPath() {
        try {
            List<ModelFileInfo> models = modelManager.listAvailableModelsWithInfo();
            if (models == null || models.isEmpty()) return null;
            ModelFileInfo best = null;
            for (ModelFileInfo m : models) {
                String lower = m.name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".gguf")) continue;
                if (best == null) best = m;
                if (lower.contains("qwen") && lower.contains("7b")) {
                    best = m;
                    break;
                }
                if (m.size > best.size && !(best.name.toLowerCase(Locale.ROOT).contains("qwen"))) {
                    best = m;
                }
            }
            return best != null ? best.path : null;
        } catch (Throwable t) {
            Log.w(TAG, "枚举模型失败: " + t.getMessage());
            return null;
        }
    }

    /**
     * 强制清空全部 KV 缓存：底层单次推理接口本身不累积历史，
     * 此处额外重置引擎内部会话状态（工具计数等），保证无上下文残留。
     * 注意：本地推理崩溃后 GPU/KV 状态可能已损坏，此时禁止再触碰内核，
     * 否则会触发 SIGSEGV 杀死整个进程（native 层已加信号保护作第二道防线）。
     */
    public void clearAllKvCache() {
        if (sGlobalBroken) {
            return; // 熔断后不再调用本地内核任何接口
        }
        try {
            LlamaHelper.clearContextForInference();
        } catch (Throwable t) {
            Log.w(TAG, "KV缓存清理异常(忽略): " + t.getMessage());
        }
    }

    /** 释放临时会话（临时模式推理完成后调用） */
    public synchronized void releaseTempSession() {
        if (!residentMode && modelLoaded) {
            clearAllKvCache();
            modelLoaded = false;
            loadedModelPath = null;
            log("临时会话已释放");
        }
    }

    /** 闲置 5 分钟自动卸载模型释放内存 */
    public void unloadModelIfIdle() {
        cancelIdleTimer();
        idleTimer = new Timer("import-llm-idle", true);
        idleTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (ImportLlmEngine.this) {
                    clearAllKvCache();
                    modelLoaded = false;
                    loadedModelPath = null;
                }
                log("闲置5分钟，模型已卸载释放内存");
            }
        }, IDLE_UNLOAD_MS);
    }

    private void cancelIdleTimer() {
        if (idleTimer != null) {
            idleTimer.cancel();
            idleTimer = null;
        }
    }

    /** 主动销毁引擎 */
    public synchronized void shutdown() {
        cancelIdleTimer();
        clearAllKvCache();
        modelLoaded = false;
    }

    // ==================== 两大核心推理入口 ====================

    /**
     * 字段映射推理（含工具调用循环 + 3 轮迭代重试 + 四层硬过滤）。
     *
     * @param tableCols  数据库合法字段列表（逗号分隔）
     * @param aliasJson  本地别名词典提示（可为空）
     * @param sampleText 文件表头样本
     * @param fixPrompt  外部附加修正提示（可为空）
     * @param legalFields 合法标准字段集合（用于硬过滤）
     */
    public MappingResult runColumnMappingInfer(String tableCols, String aliasJson,
                                               String sampleText, String fixPrompt,
                                               java.util.Set<String> legalFields) {
        MappingResult result = new MappingResult();

        // 说明：localOrchestrator 仅作模型信息展示（ImportLlmEngine 未转发推理，
        // 映射推理直接走下方 ensureModel + inferOnce 本地模型路径）
        if (localOrchestrator != null) {
            log("使用本地模型进行字段映射推理");
        }
        
        if (!ensureModel()) {
            result.failReason = "模型不可用";
            return result;
        }

        clearAllKvCache();
        int toolCalls = 0;
        StringBuilder toolContext = new StringBuilder();

        String basePrompt = buildMappingPrompt(tableCols, aliasJson, sampleText, toolContext.toString());
        if (fixPrompt != null && !fixPrompt.isEmpty()) {
            basePrompt = basePrompt + "\n" + fixPrompt;
        }

        for (int round = 1; round <= MAX_INFER_ROUNDS; round++) {
            result.rounds = round;
            String prompt = basePrompt;
            if (round == 2) prompt = basePrompt + "\n" + FIX_ROUND2;
            if (round >= 3) prompt = basePrompt + "\n" + FIX_ROUND2 + "\n" + FIX_ROUND3;

            String raw = inferOnce(prompt, MAPPING_MAX_TOKENS);
            result.toolCalls = toolCalls;

            if (raw == null) {
                result.failReason = "推理无输出";
                continue;
            }

            // 工具调用循环（熔断：最多 2 次）
            ImportToolManager.ToolCall call = toolManager.parseToolRequest(raw);
            if (call != null) {
                if (toolManager.reachToolLimit(toolCalls)) {
                    log("工具调用达到熔断上限，停止工具循环");
                } else {
                    toolCalls++;
                    String toolResult = toolManager.executeTool(call);
                    log("工具调用[" + toolCalls + "] " + call.name);
                    toolContext.append("工具").append(call.name)
                            .append("结果: ").append(toolResult).append('\n');
                    basePrompt = buildMappingPrompt(tableCols, aliasJson, sampleText,
                            toolContext.toString());
                    round--; // 工具调用不消耗重试轮次
                    if (toolCalls >= ImportToolManager.MAX_TOOL_CALLS_PER_SESSION) {
                        // 已达上限，下一轮强制按映射输出
                        basePrompt = basePrompt + "\n已完成工具调用，直接输出最终映射JSON。";
                    }
                    continue;
                }
            }

            // 四层后置硬过滤
            ImportOutputSanitizer.SanitizedOutput clean =
                    ImportOutputSanitizer.sanitizeMappingOutput(raw, legalFields,
                            "questionText", "correctAnswer");
            if (clean.valid) {
                result.valid = true;
                result.mapping = ImportOutputSanitizer.toFlatStringMap(clean.json);
                result.toolCalls = toolCalls;
                if (clean.removedIllegalFields > 0) {
                    log("硬过滤剔除非法字段 " + clean.removedIllegalFields + " 个");
                }
                postInferHousekeeping();
                return result;
            }
            result.failReason = clean.failReason;
            log("第" + round + "轮映射输出无效: " + clean.failReason);
        }

        postInferHousekeeping();
        return result;
    }

    /**
     * 缺失字段批量填充推理（独立隔离会话，单批≤10题，Token≤1000）。
     *
     * @param questionInfo 题目内容摘要（题干+选项）
     */
    public FillResult runFieldFillInfer(String questionInfo) {
        FillResult result = new FillResult();
        if (!ensureModel()) {
            result.failReason = "模型不可用";
            return result;
        }
        clearAllKvCache();

        String prompt = BASE_RULE + "\n示例：{\"category\":\"分类名称\",\"difficulty\":1,\"explanation\":\"简短说明\"}\n"
                + "为题目补充分类category、难度difficulty(1-3)、解析explanation。\n题目内容："
                + truncate(questionInfo, 800);

        for (int round = 1; round <= MAX_INFER_ROUNDS; round++) {
            String p = prompt;
            if (round == 2) p = prompt + "\n" + FIX_ROUND2;
            if (round >= 3) p = prompt + "\n" + FIX_ROUND2 + "\n" + FIX_ROUND3;

            String raw = inferOnce(p, FILL_MAX_TOKENS);
            if (raw == null) {
                result.failReason = "推理无输出";
                continue;
            }
            ImportOutputSanitizer.SanitizedOutput clean =
                    ImportOutputSanitizer.sanitizeFillOutput(raw);
            if (clean.valid) {
                result.valid = true;
                result.category = clean.json.optString("category", "").trim();
                result.difficulty = clean.json.optInt("difficulty", 1);
                result.explanation = clean.json.optString("explanation", "").trim();
                postInferHousekeeping();
                return result;
            }
            result.failReason = clean.failReason;
        }
        postInferHousekeeping();
        return result;
    }

    /**
     * 缺失字段批量填充推理（独立隔离会话，单批≤10题，Token≤1000）。
     * 返回与输入顺序一致的填充结果数组；推理失败由调用方使用固定兜底默认值。
     *
     * @param questionInfos 题目内容摘要列表（题干+选项）
     */
    public List<FillResult> runFieldFillBatchInfer(List<String> questionInfos) {
        List<FillResult> results = new ArrayList<>();
        if (questionInfos == null || questionInfos.isEmpty()) return results;
        int n = Math.min(questionInfos.size(), 10);
        for (int i = 0; i < n; i++) {
            results.add(null);
        }
        if (!ensureModel()) {
            return results; // 调用方兜底默认值
        }
        clearAllKvCache();

        StringBuilder info = new StringBuilder();
        for (int i = 0; i < n; i++) {
            info.append("[").append(i + 1).append("]")
                    .append(truncate(questionInfos.get(i), 80)).append('\n');
        }
        String prompt = BASE_RULE + "\n"
                + "示例：{\"fills\":[{\"category\":\"分类\",\"difficulty\":1,\"explanation\":\"说明\",\"questionType\":\"单选题\"}]}\n"
                + "为下列" + n + "道题按顺序补充分类category、难度difficulty(1-3)、解析explanation、"
                + "题型questionType(单选/多选/判断/填空/简答)，fills数组长度必须为" + n + "。\n"
                + "注意：只推断缺失字段，参考每题的题干/选项内容以及相邻题目的题型难度一致性。\n题目列表：\n" + info;

        for (int round = 1; round <= MAX_INFER_ROUNDS; round++) {
            String p = prompt;
            if (round == 2) p = prompt + "\n" + FIX_ROUND2;
            if (round >= 3) p = prompt + "\n" + FIX_ROUND2 + "\n" + FIX_ROUND3;

            String raw = inferOnce(p, Math.min(2048, 160 + n * 200));
            if (raw == null) continue;

            String block = ImportOutputSanitizer.trimToJsonBlock(raw);
            if (block == null) continue;
            try {
                JSONObject root = new JSONObject(block);
                JSONArray fills = root.optJSONArray("fills");
                if (fills == null) continue;
                int got = Math.min(fills.length(), n);
                for (int i = 0; i < got; i++) {
                    JSONObject fo = fills.optJSONObject(i);
                    if (fo == null) continue;
                    ImportOutputSanitizer.SanitizedOutput clean =
                            ImportOutputSanitizer.sanitizeFillOutput(fo.toString());
                    if (!clean.valid) continue;
                    FillResult fr = new FillResult();
                    fr.valid = true;
                    fr.category = clean.json.optString("category", "").trim();
                    fr.difficulty = clean.json.optInt("difficulty", 1);
                    fr.explanation = clean.json.optString("explanation", "").trim();
                    fr.questionType = clean.json.optString("questionType", "").trim();
                    results.set(i, fr);
                }
                postInferHousekeeping();
                return results;
            } catch (Exception e) {
                Log.w(TAG, "批量填充解析失败(第" + round + "轮): " + e.getMessage());
            }
        }
        postInferHousekeeping();
        return results;
    }

    /**
     * 表头行识别推理（独立隔离会话）：当文件表头无法自动检测（无表头/非标准表头）时，
     * 根据工作表前 12 行原始内容判断真实表头行号与列映射。
     *
     * @param rawRows     工作表前 12 行原始内容（含空行，行号与工作表一致）
     * @param legalFields 合法标准字段集合（用于硬过滤）
     * @param docHint     题库说明/模板说明（可为空，帮助 AI 理解列含义）
     * @return HeaderResult：headerRow(-1=无表头) + 标准字段→列索引映射
     */
    public HeaderResult runHeaderInfer(JSONArray rawRows, java.util.Set<String> legalFields,
                                       String docHint) {
        HeaderResult result = new HeaderResult();
        if (rawRows == null || rawRows.length() == 0) {
            result.failReason = "无原始行数据";
            return result;
        }
        if (!ensureModel()) {
            result.failReason = "模型不可用";
            return result;
        }
        clearAllKvCache();

        StringBuilder sb = new StringBuilder();
        sb.append(BASE_RULE).append('\n');
        sb.append("你是题库文件表头识别器。下面是 Excel 工作表前 12 行原始内容（每行用 | 分隔各列），");
        sb.append("可能包含标题行、说明行、表头行、数据行。\n");
        sb.append("任务：\n");
        sb.append("1. 找出真正的表头行（列名行，如：题干/选项A/答案），返回其行号 header_row（从 0 开始计）。\n");
        sb.append("2. 若第一行就是题目数据、整张表没有任何表头行，返回 header_row = -1。\n");
        sb.append("3. 给出列映射 mapping：标准字段 -> 列索引（0 开始），如 {\"questionText\":1,\"optionA\":2}。\n");
        if (legalFields != null && !legalFields.isEmpty()) {
            sb.append("可映射字段：").append(truncate(joinFields(legalFields), 300)).append('\n');
        } else {
            sb.append("可映射字段：questionText, optionA~L, correctAnswer, category, difficulty, explanation, questionType, optionsCombined\n");
        }
        sb.append("   某列同时包含多个选项（分号/竖线/A.前缀 分隔）时映射为 optionsCombined；");
        sb.append("无法判断的列不要映射。\n");
        sb.append("原始内容：\n").append(buildRawRowsText(rawRows));
        if (docHint != null && !docHint.isEmpty()) {
            sb.append("题库说明（参考列含义）：").append(truncate(docHint, 300)).append('\n');
        }
        sb.append("只输出 JSON：{\"header_row\":N,\"mapping\":{...}}");

        String prompt = sb.toString();
        for (int round = 1; round <= MAX_INFER_ROUNDS; round++) {
            result.rounds = round;
            String p = prompt;
            if (round == 2) p = prompt + "\n" + FIX_ROUND2;
            if (round >= 3) p = prompt + "\n" + FIX_ROUND2 + "\n" + FIX_ROUND3;

            String raw = inferOnce(p, MAPPING_MAX_TOKENS);
            if (raw == null) {
                result.failReason = "推理无输出";
                continue;
            }
            ImportOutputSanitizer.SanitizedOutput clean =
                    ImportOutputSanitizer.sanitizeHeaderOutput(raw, legalFields);
            if (clean.valid) {
                result.valid = true;
                result.headerRow = clean.json.optInt("header_row", -2);
                JSONObject mp = clean.json.optJSONObject("mapping");
                if (mp != null) {
                    java.util.Iterator<String> it = mp.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        result.mapping.put(k, mp.optInt(k, -1));
                    }
                }
                postInferHousekeeping();
                return result;
            }
            result.failReason = clean.failReason;
            log("第" + round + "轮表头识别无效: " + clean.failReason);
        }
        postInferHousekeeping();
        return result;
    }

    /** 前 12 行原始内容 → 文本（每行 "行N: 值1 | 值2 | ..."，单元格截断） */
    private String buildRawRowsText(JSONArray rawRows) {
        StringBuilder sb = new StringBuilder();
        int max = Math.min(rawRows.length(), 12);
        for (int i = 0; i < max; i++) {
            sb.append("行").append(i).append(": ");
            JSONArray row = rawRows.optJSONArray(i);
            if (row != null) {
                for (int j = 0; j < row.length(); j++) {
                    if (j > 0) sb.append(" | ");
                    String cell = row.optString(j, "");
                    sb.append(cell.length() > 40 ? cell.substring(0, 40) + "…" : cell);
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String joinFields(java.util.Set<String> fields) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String f : fields) {
            if (count++ > 0) sb.append(',');
            sb.append(f);
        }
        return sb.toString();
    }

    // ==================== 内部实现 ====================

    private String buildMappingPrompt(String tableCols, String aliasJson,
                                      String sampleText, String toolContext) {
        StringBuilder sb = new StringBuilder();
        sb.append(BASE_RULE).append('\n');
        sb.append("示例格式：{\"questionText\":\"题干列名\",\"optionA\":\"A选项列名\",\"correctAnswer\":\"答案列名\"}\n");
        sb.append("任务：将文件表头列名映射到数据库字段，value必须是文件中的真实列名。\n");
        sb.append("合法数据库字段：").append(tableCols).append('\n');
        if (aliasJson != null && !aliasJson.isEmpty()) {
            sb.append("参考别名词典：").append(truncate(aliasJson, 300)).append('\n');
        }
        sb.append("文件表头样本：").append(truncate(sampleText, 500)).append('\n');
        if (toolContext != null && !toolContext.isEmpty()) {
            sb.append(toolContext);
        }
        return sb.toString();
    }

    private boolean ensureModel() {
        if (sGlobalBroken) return onlineConfigured();
        if (modelLoaded) return true;
        boolean loaded = residentMode ? loadModelOnce() : tempLoadModel();
        // 本地模型不可用（未下载/加载失败）：若已配置在线模型则改走在线兜底
        return loaded || onlineConfigured();
    }

    /** 是否已配置激活的在线模型（兜底可用性探测） */
    private boolean onlineConfigured() {
        try {
            return OnlineModelManager.getInstance(context).getActiveModel() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 单次无状态推理：本地内核优先，熔断/本地不可用时自动切换在线模型兜底 */
    private String inferOnce(String prompt, int maxTokens) {
        if (sGlobalBroken || !modelLoaded) {
            return inferOnline(prompt, maxTokens);
        }
        try {
            List<PromptBuilder.Message> messages = new ArrayList<>();
            messages.add(new PromptBuilder.Message("system", BASE_RULE));
            messages.add(new PromptBuilder.Message("user", prompt));
            String raw = LlamaHelper.generate(messages, maxTokens, 0.1f);
            if (raw != null && raw.startsWith("Error:")) {
                sGlobalCrashes++;
                if (sGlobalCrashes >= MAX_CONSECUTIVE_CRASHES && !sGlobalBroken) {
                    sGlobalBroken = true;
                    log("本地推理连续崩溃 " + sGlobalCrashes + " 次，触发全局熔断：停用本地 AI 推理"
                            + (onlineConfigured() ? "，自动切换在线模型兜底" : "，未配置在线模型，改用固定兜底值"));
                    // 卸载模型释放内存：崩溃后 GPU/KV 状态已损坏，跳过 KV 清理避免二次崩溃杀进程
                    synchronized (this) {
                        cancelIdleTimer();
                        modelLoaded = false;
                        loadedModelPath = null;
                    }
                    // 熔断当次立即尝试在线兜底，不浪费本次调用
                    return inferOnline(prompt, maxTokens);
                } else {
                    log("推理错误(" + sGlobalCrashes + "/" + MAX_CONSECUTIVE_CRASHES + "): " + raw);
                }
                return null;
            }
            sGlobalCrashes = 0;
            return raw;
        } catch (Throwable t) {
            log("推理异常: " + t.getMessage());
            return null;
        }
    }

    /** 在线模型兜底推理（同步阻塞，由导入后台线程调用）；失败返回 null 由调用方固定兜底 */
    private String inferOnline(String prompt, int maxTokens) {
        if (sOnlineFailures >= MAX_ONLINE_FAILURES) {
            return null; // 断网保护：连续失败后不再尝试，直接固定兜底值
        }
        try {
            OnlineModelManager.OnlineModelConfig cfg =
                    OnlineModelManager.getInstance(context).getActiveModel();
            if (cfg == null) {
                return null;
            }
            if (!sOnlineAnnounced) {
                sOnlineAnnounced = true;
                log("已切换在线模型兜底推理: " + cfg.modelName);
            }
            // 在线侧放宽输出上限：本地 256 token 对批量填充 JSON 过小，中途截断会令整批解析失败白白耗时
            int effectiveTokens = Math.max(maxTokens, ONLINE_MIN_TOKENS);
            String raw = OnlineInferenceService.getInstance(context)
                    .generateAsync(prompt, cfg, null, effectiveTokens, false)
                    .get(ONLINE_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS);
            if (raw == null || raw.trim().isEmpty()) {
                sOnlineFailures++;
                return null;
            }
            sOnlineFailures = 0;
            return raw;
        } catch (Throwable t) {
            sOnlineFailures++;
            log("在线兜底推理失败(" + sOnlineFailures + "/" + MAX_ONLINE_FAILURES + "): " + t.getMessage()
                    + (sOnlineFailures >= MAX_ONLINE_FAILURES ? "，停用在线调用改用固定兜底值" : ""));
            return null;
        }
    }

    /** 推理完成后的收尾：仅本地模型需要；临时模式立即释放，常驻模式启动闲置卸载计时 */
    private void postInferHousekeeping() {
        if (!modelLoaded) return; // 纯在线兜底路径无需本地模型收尾
        if (!residentMode) {
            releaseTempSession();
        } else {
            unloadModelIfIdle();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
