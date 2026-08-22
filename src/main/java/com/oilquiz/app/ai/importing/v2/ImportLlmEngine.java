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
    /** CPU 推理模式的上下文大小：KV 缓存走系统内存（手机 RAM 宽裕），
     *  可放大到 8192 缓解"提示词超长/上下文超限"；GPU 模式受显存限制保持 4096。 */
    private static final int CPU_INFER_CTX = 8192;
    /** 导入推理后端设置 key（import_prefs） */
    private static final String PREF_CPU_INFERENCE = "cpu_inference";
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

    /** 缺失字段填充推理结果（含题型推断；fields 保存 LLM 输出的全部字段，供动态回写） */
    public static class FillResult {
        public boolean valid;
        public String category;
        public int difficulty = 1;
        public String explanation = "";
        public String questionType = "";
        /** LLM 输出的完整字段映射（标准字段 → 值），供动态字段回写 */
        public Map<String, Object> fields = new java.util.LinkedHashMap<>();
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
    /** 模型是否由本导入引擎自己加载（而非复用其他模块已加载的模型）。
     *  为 true 时导入结束立即真正释放（LlamaHelper.release），不再占用算力资源；
     *  复用的不释放，避免误伤对话等其他模块正在使用的模型。 */
    private volatile boolean selfLoaded = false;
    /** 正在进行的本地推理数（保护 idle 卸载：推理中不卸载模型，避免打断连续推理/多 sheet 导入） */
    private final java.util.concurrent.atomic.AtomicInteger inferringCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
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
                selfLoaded = false; // 复用其他模块的模型：导入结束不释放
                // CPU 模式下若已加载模型是 GPU 后端加载的（n_gpu_layers>0），复用会沿用 GPU：
                // 不强制重载（有二次初始化崩溃风险），提示用户重启 App 后导入才走 CPU
                boolean wantCpu = isCpuInferenceEnabled();
                int gpuLayers = LlamaHelper.getGPULayers();
                log("复用已加载的本地模型，跳过重复初始化"
                        + (wantCpu && gpuLayers > 0
                            ? "（当前为 GPU 模式 n_gpu_layers=" + gpuLayers
                                + "，重启 App 后导入将使用 CPU + 上下文 8192）"
                            : ""));
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
            // CPU 推理模式：KV 缓存走系统内存，可放大上下文（缓解提示词超限），
            // 且避开 Vulkan/Adreno GPU 后端的已知崩溃问题（signal 6/11）；
            // GPU 层数需在 initModel 前设置（n_gpu_layers 仅加载时生效）。
            boolean cpuMode = isCpuInferenceEnabled();
            int ctx = INFER_CTX;
            if (cpuMode) {
                LlamaHelper.setGPULayers(0); // 纯 CPU：n_gpu_layers=0
                ctx = CPU_INFER_CTX;
            }
            int ret = LlamaHelper.initModel(path, ctx, INFER_THREADS);
            if (ret == 0) {
                modelLoaded = true;
                selfLoaded = true; // 本导入引擎自己加载：导入结束立即释放
                loadedModelPath = path;
                log("导入引擎模型加载成功(" + (System.currentTimeMillis() - t0) + "ms): "
                        + new java.io.File(path).getName()
                        + (cpuMode ? "，CPU 推理模式，上下文 " + ctx : "，GPU 推理模式，上下文 " + ctx));
                return true;
            }
            log("模型加载失败, initModel 返回 " + ret);
            return false;
        } catch (Throwable t) {
            log("模型加载异常: " + t.getMessage());
            return false;
        }
    }

    /** 是否启用导入 CPU 推理模式（KV 走系统内存，上下文放大到 8192，避开 GPU 后端崩溃坑）。
     *  默认 true：导入功能以 CPU 推理为默认后端——更稳定、上下文更大，速度损失可接受；
     *  可在导入页开关改回 GPU（仅当模型未加载时生效，已加载需重启 App）。 */
    public static boolean isCpuInferenceEnabled(android.content.Context ctx) {
        try {
            android.content.SharedPreferences prefs =
                    ctx.getSharedPreferences("import_prefs", android.content.Context.MODE_PRIVATE);
            return prefs.getBoolean(PREF_CPU_INFERENCE, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 设置导入 CPU 推理模式开关（下次模型加载生效；模型已常驻时需重启 App 后生效） */
    public static void setCpuInferenceEnabled(android.content.Context ctx, boolean enabled) {
        try {
            ctx.getSharedPreferences("import_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_CPU_INFERENCE, enabled).apply();
        } catch (Throwable ignored) {
        }
    }

    private boolean isCpuInferenceEnabled() {
        return isCpuInferenceEnabled(context);
    }

    /**
     * 应用内热切换推理后端（GPU/CPU），无需重启 App：
     * 保存设置 → 释放当前模型 → 按新后端参数（n_gpu_layers/上下文）重新初始化。
     * 曾因本地崩溃熔断时自动重置（新后端可能是健康的）。
     * 返回：0=切换成功；1=无需切换（未加载或已同后端，设置已保存）；-1=切换失败（设置已回滚，可重启兜底）。
     */
    public static synchronized int switchInferenceBackend(android.content.Context ctx,
                                                          boolean cpuMode) {
        boolean prevCpu = isCpuInferenceEnabled(ctx);
        setCpuInferenceEnabled(ctx, cpuMode);
        try {
            if (!LlamaHelper.isModelInitialized()) {
                logStatic("模型未加载，后端设置已保存，下次加载生效: "
                        + (cpuMode ? "CPU 8192" : "GPU 4096"));
                return 1;
            }
            int curGpu = LlamaHelper.getGPULayers();
            boolean curCpu = curGpu == 0;
            if (curCpu == cpuMode) {
                logStatic("推理后端已是目标模式: " + (cpuMode ? "CPU" : "GPU"));
                return 1;
            }
            // 曾因本地崩溃熔断：切换后端后允许重试（新后端可能是健康的）
            if (sGlobalBroken) {
                sGlobalBroken = false;
                sGlobalCrashes = 0;
                logStatic("已重置本地推理熔断状态，新后端重新尝试");
            }
            logStatic("热切换推理后端: " + (curCpu ? "CPU" : "GPU") + " → "
                    + (cpuMode ? "CPU（上下文 8192）" : "GPU（上下文 4096）"));
            LlamaHelper.release();
            ModelManager mm = new ModelManager(ctx.getApplicationContext());
            String path = pickModelPathStatic(mm);
            if (path == null) {
                setCpuInferenceEnabled(ctx, prevCpu); // 回滚设置
                logStatic("热切换失败：未找到 GGUF 模型，已恢复原设置");
                return -1;
            }
            if (cpuMode) {
                LlamaHelper.setGPULayers(0);          // 纯 CPU
            } else {
                LlamaHelper.setGPULayers(-1);         // llama.cpp 惯例：-1 = 自动分配 GPU 层
            }
            int nCtx = cpuMode ? CPU_INFER_CTX : INFER_CTX;
            int ret = LlamaHelper.initModel(path, nCtx, INFER_THREADS);
            if (ret != 0) {
                setCpuInferenceEnabled(ctx, prevCpu); // 回滚设置
                logStatic("热切换失败：模型重新加载失败(ret=" + ret + ")，已恢复原设置");
                return -1;
            }
            logStatic("热切换完成: " + (cpuMode ? "CPU 推理模式，上下文 8192" : "GPU 推理模式，上下文 4096"));
            return 0;
        } catch (Throwable t) {
            setCpuInferenceEnabled(ctx, prevCpu);
            Log.w(TAG, "热切换异常: " + t.getMessage());
            return -1;
        }
    }

    private static String pickModelPathStatic(ModelManager mm) {
        try {
            List<ModelManager.ModelFileInfo> models = mm.listAvailableModelsWithInfo();
            if (models == null || models.isEmpty()) return null;
            ModelManager.ModelFileInfo best = null;
            for (ModelManager.ModelFileInfo m : models) {
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
            return null;
        }
    }

    private static void logStatic(String msg) {
        Log.i(TAG, msg);
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

    /** 闲置 5 分钟自动卸载模型释放内存（推理进行中会推迟，避免打断连续推理/多 sheet 导入）。
     *  触发时真正释放权重（LlamaHelper.release），不再占用算力资源。 */
    public void unloadModelIfIdle() {
        cancelIdleTimer();
        idleTimer = new Timer("import-llm-idle", true);
        idleTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (ImportLlmEngine.this) {
                    if (inferringCount.get() > 0) {
                        // 推理进行中：推迟卸载，5 分钟后再检查（多 sheet 导入/连续填充期间
                        // 模型保持常驻，避免中途卸载导致后续推理重新加载、内存抖动）
                        unloadModelIfIdle();
                        return;
                    }
                    releaseModelWeights("闲置5分钟，模型已卸载释放内存");
                }
            }
        }, IDLE_UNLOAD_MS);
    }

    /**
     * 导入结束立即释放本地模型（不再占用算力资源，无需等 5 分钟闲置）。
     * 仅释放本导入自己加载的模型（selfLoaded）；复用的（对话等其他模块加载的）不释放，
     * 避免误伤其他模块正在使用的模型。真正释放权重走 LlamaHelper.release（带推理锁）。
     */
    public synchronized void releaseAfterImport() {
        cancelIdleTimer();
        if (!modelLoaded) return;
        if (!selfLoaded) {
            log("导入结束：模型为复用（其他模块加载），不释放，交还原主");
            return;
        }
        log("导入结束：立即释放本地模型（不再占用算力资源）");
        releaseModelWeights("导入完成，模型已释放");
    }

    /** 真正释放模型权重并重置引擎状态（推理锁保护，防止与推理竞态） */
    private void releaseModelWeights(String logMsg) {
        clearAllKvCache();
        modelLoaded = false;
        loadedModelPath = null;
        selfLoaded = false;
        try {
            LlamaHelper.release(); // 真正释放权重（内部持推理写锁，推理中会等待）
            log(logMsg);
        } catch (Throwable t) {
            Log.w(TAG, "释放模型异常(忽略): " + t.getMessage());
        }
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

        String prompt = BASE_RULE + "\n示例：{\"category\":\"分类名称\",\"difficulty\":1,\"explanation\":\"简短说明\",\"questionType\":\"单选题\"}\n"
                + "为题目补充题型questionType(单选/多选/判断/填空/简答)、分类category、难度difficulty(1-3)、解析explanation，"
                + "只推断缺失字段。\n题目内容："
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
                result.questionType = clean.json.optString("questionType", "").trim();
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
     * @param fillFields    本次要填充的标准字段列表（动态，来自映射∩数据库列）
     * @param docHint       题库说明/模板说明（可为空，字段约定帮助推断题型/难度/分类等）
     */
    public List<FillResult> runFieldFillBatchInfer(List<String> questionInfos,
                                                   List<String> fillFields,
                                                   String docHint) {
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
                    .append(truncate(questionInfos.get(i), 120)).append('\n');
        }
        // 动态字段描述：本次要填充的字段（如 题型/难度/分类/解析/知识点/标签…）
        List<String> fieldDescs = new ArrayList<>();
        for (String f : fillFields) {
            String desc = fieldDesc(f);
            if (desc != null) fieldDescs.add(desc);
        }
        if (fieldDescs.isEmpty()) return results; // 无实际可填字段
        StringBuilder fieldList = new StringBuilder();
        for (int i = 0; i < fieldDescs.size(); i++) {
            if (i > 0) fieldList.append("、");
            fieldList.append(fieldDescs.get(i));
        }
        String prompt = BASE_RULE + "\n"
                + "示例：{\"fills\":[{\"category\":\"分类\",\"difficulty\":1,\"explanation\":\"说明\",\"questionType\":\"单选题\"}]}\n"
                + "为下列" + n + "道题按顺序补充：" + fieldList
                + "。只推断缺失字段，参考每题的题干/选项内容以及相邻题目的规律一致性。"
                + "fills数组长度必须为" + n + "，每项只输出推断出的字段，无法推断的字段省略。\n题目列表：\n" + info;
        // 题库说明/模板说明：字段约定帮助推断（如"选项用分号分隔""答案在最后一列"等）
        if (docHint != null && !docHint.isEmpty()) {
            prompt = prompt + "\n题库说明（参考字段约定）：" + truncate(docHint, 400);
        }

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
                    FillResult fr = new FillResult();
                    fr.valid = true;
                    // 提取本次要求的字段（兼容大小写/别名）
                    for (String f : fillFields) {
                        Object v = extractFieldValue(fo, f);
                        if (v != null) {
                            fr.fields.put(f, v);
                        }
                    }
                    // 兼容旧字段引用
                    fr.category = fr.fields.containsKey("category")
                            ? String.valueOf(fr.fields.get("category")) : "";
                    fr.difficulty = fr.fields.containsKey("difficulty")
                            ? toInt(fr.fields.get("difficulty"), 1) : 1;
                    fr.explanation = fr.fields.containsKey("explanation")
                            ? String.valueOf(fr.fields.get("explanation")) : "";
                    fr.questionType = fr.fields.containsKey("questionType")
                            ? String.valueOf(fr.fields.get("questionType")) : "";
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

    /** 标准字段 → 中文描述（用于填充提示词）；不支持填充的字段返回 null */
    private static String fieldDesc(String field) {
        switch (field == null ? "" : field) {
            case "questionType": return "题型questionType(单选/多选/判断/填空/简答)";
            case "difficulty": return "难度difficulty(1-3)";
            case "category": return "分类category";
            case "explanation": return "解析explanation";
            case "knowledgePoint": return "知识点knowledgePoint";
            case "subCategory": return "子分类subCategory";
            case "tags": return "标签tags";
            case "hint": return "提示hint";
            case "points": return "分值points(数字)";
            case "timeLimit": return "时限timeLimit(秒)";
            case "author": return "作者author";
            case "comment": return "备注comment";
            default: return null; // 未知字段不要求 LLM 输出
        }
    }

    /** 从填充 JSON 中提取指定字段值（兼容别名/大小写） */
    private static Object extractFieldValue(JSONObject fo, String field) {
        if (fo == null || field == null) return null;
        if (fo.has(field) && !fo.isNull(field)) {
            Object v = fo.opt(field);
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : v;
        }
        // 别名匹配
        String lower = field.toLowerCase();
        java.util.Iterator<String> it = fo.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.equalsIgnoreCase(field) || k.toLowerCase().equals(lower)) {
                Object v = fo.opt(k);
                String s = String.valueOf(v).trim();
                return s.isEmpty() ? null : v;
            }
        }
        return null;
    }

    private static int toInt(Object v, int def) {
        try {
            if (v instanceof Number) return ((Number) v).intValue();
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 表头行识别推理（独立隔离会话）：当文件表头无法自动检测（无表头/非标准表头）时，
     * 根据工作表前 12 行原始内容判断真实表头行号与列映射。
     *
     * @param rawRows     工作表前 12 行原始内容（含空行，行号与工作表一致）
     * @param legalFields 合法标准字段集合（用于硬过滤）
     * @param docHint     题库说明/模板说明（可为空，帮助 AI 理解列含义）
     * @param aliasJson   本地别名词典（标准字段→常见列名，可为空）
     * @return HeaderResult：headerRow(-1=无表头) + 标准字段→列索引映射
     */
    public HeaderResult runHeaderInfer(JSONArray rawRows, java.util.Set<String> legalFields,
                                       String docHint, String aliasJson) {
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
        if (aliasJson != null && !aliasJson.isEmpty()) {
            sb.append("参考别名词典（标准字段的常见列名写法，用于理解自定义列名）：")
                    .append(truncate(aliasJson, 300)).append('\n');
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
    /**
     * 原始行文本化（供表头识别 prompt）：跳过空单元格（Excel 格式残留 255 列全是空，
     * 不跳会把 prompt 撑到十几万字符 → 上下文超限）+ 每行内容预算，保证 prompt 在安全范围内。
     */
    private String buildRawRowsText(JSONArray rawRows) {
        StringBuilder sb = new StringBuilder();
        int max = Math.min(rawRows.length(), 12);
        int cellBudget = 40;      // 每格截断
        int rowBudget = 600;      // 每行总长预算（防 255 列撑爆上下文）
        for (int i = 0; i < max; i++) {
            sb.append("行").append(i).append(": ");
            JSONArray row = rawRows.optJSONArray(i);
            int added = 0;
            int rowLen = 0;
            if (row != null) {
                for (int j = 0; j < row.length(); j++) {
                    String cell = row.optString(j, "");
                    if (cell == null || cell.trim().isEmpty()) continue; // 空列（格式残留）跳过
                    String t = cell.length() > cellBudget ? cell.substring(0, cellBudget) + "…" : cell;
                    rowLen += t.length() + 3;
                    if (rowLen > rowBudget) break; // 行预算：超出截断该行，防 prompt 超限
                    if (added++ > 0) sb.append(" | ");
                    sb.append(t);
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
        inferringCount.incrementAndGet(); // 标记本地推理进行中（idle 卸载据此推迟）
        try {
            List<PromptBuilder.Message> messages = new ArrayList<>();
            messages.add(new PromptBuilder.Message("system", BASE_RULE));
            messages.add(new PromptBuilder.Message("user", prompt));
            String raw = LlamaHelper.generate(messages, maxTokens, 0.1f);
            if (raw != null && raw.startsWith("Error:")) {
                // 区分错误类型：上下文超限/锁占用是"可恢复"错误，不是模型崩溃——
                // 不累计熔断计数（否则连续几次超长提示词会误触全局熔断停用本地模型）
                if (raw.contains("Prompt too long") || raw.contains("too long")) {
                    log("提示词超长被拒（输入/上下文超限），改走在线模型兜底: " + raw);
                    return inferOnline(prompt, maxTokens);
                }
                if (raw.contains("already in progress")) {
                    log("推理锁被占用（其他推理进行中），改走在线模型兜底: " + raw);
                    return inferOnline(prompt, maxTokens);
                }
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
        } finally {
            inferringCount.decrementAndGet();
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
