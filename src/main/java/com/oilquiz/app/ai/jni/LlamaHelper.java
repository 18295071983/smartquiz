package com.oilquiz.app.ai.jni;

import android.util.Log;
import android.content.Context;
import android.preference.PreferenceManager;
import com.oilquiz.app.ai.spi.AppServices;
import com.oilquiz.app.util.AILogger;
import com.oilquiz.app.ai.util.PromptBuilder;
import com.oilquiz.app.ai.callback.StreamCallback;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.parser.ThinkingTagConfig;
import com.oilquiz.app.ai.chat.parser.ToolCallTagConfig;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.TimeUnit;

/**
 * LlamaHelper - Llama.cpp Native JNI接口封装
 * 
 * 功能：
 * - 封装Llama.cpp原生库的JNI调用
 * - 提供模型加载、文本生成、历史管理功能
 * - 支持流式生成和批处理生成
 * - Native层日志回调集成
 * - GPU/设备能力检测
 * - 推理锁保护（防止并发冲突）
 * 
 * 主要功能：
 * 1. 模型管理：initModel, release, isModelInitialized
 * 2. 文本生成：generate, generateStream, generateBatch
 * 3. 参数设置：setGPULayers, setMemoryPoolSize, setBatchSize, setThreadCount
 * 4. 设备检测：getDeviceCount, getDeviceInfo, getFreeDeviceMemory
 * 5. 性能监控：getInferenceSpeed, getMemoryUsage, getTokenCount
 * 
 * Native层对应：
 * - llama-jni native库
 * - native-lib.cpp 实现
 * 
 * 使用示例：
 * // 初始化
 * int result = LlamaHelper.initModel("/path/to/model.gguf", 4096, 4);
 * 
 * // 生成
 * String response = LlamaHelper.generate("Hello", 512, 0.7f);
 * 
 * @author AI Team
 * @since 2024
 */
public class LlamaHelper {
    private static final String TAG = "LlamaHelper";
    private static final String LIBRARY_NAME = "llama-jni";
    private static volatile boolean libraryLoaded = false;
    
    private static volatile NativeLogCallback sLogCallback = null;
    
    private static volatile long lastModelInitCheckTime = 0;
    private static volatile boolean cachedModelInitialized = false;
    private static final long MODEL_INIT_CHECK_INTERVAL_MS = 1000;

    // ========== 推理锁 ==========
    // 用于保护 generate/chatSend 不会并发执行
    private static final ReentrantReadWriteLock inferenceLock = new ReentrantReadWriteLock();
    private static final long INFERENCE_LOCK_TIMEOUT_MS = 30000; // 30秒超时
    
    // Native 状态恢复跟踪
    private static volatile int nativeRecoveryAttemptCount = 0;
    private static volatile int nativeRecoverySuccessCount = 0;
    private static volatile long lastNativeRecoveryTime = 0;
    private static final int MAX_RECOVERY_ATTEMPTS_PER_HOUR = 5;
    private static final long RECOVERY_COOLDOWN_MS = 30000; // 30秒冷却
    
    public interface NativeLogCallback {
        void onLog(int level, String tag, String message);
    }
    
    public static void setNativeLogCallback(NativeLogCallback callback) {
        sLogCallback = callback;
    }
    
    public static void onNativeLog(int level, String tag, String message) {
        if (sLogCallback != null) {
            sLogCallback.onLog(level, tag, message);
        }
    }

    static {
        try {
            System.loadLibrary(LIBRARY_NAME);
            libraryLoaded = true;
            AILogger.i(TAG, "Successfully loaded llama-jni library");
        } catch (UnsatisfiedLinkError e) {
            libraryLoaded = false;
            AILogger.e(TAG, "Failed to load llama-jni library: " + e.getMessage(), e);
        }
    }

    // 检查库是否已加载
    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }

    // 初始化模型
    public static int initModel(String modelPath, int nCtx, int nThreads) {
        if (!libraryLoaded) {
            AILogger.w(TAG, "Library not loaded, cannot initialize model");
            return -1;
        }
        
        ValidationResult validation = validateInitModelParams(modelPath, nCtx, nThreads);
        if (!validation.valid) {
            AILogger.e(TAG, "initModel param validation failed: " + validation.errorMessage);
            return -1;
        }
        
        if (validation.warningMessage != null) {
            AILogger.w(TAG, "initModel warning: " + validation.warningMessage);
        }
        
        // GPU 后端偏好（OpenCL/Vulkan/auto）：读取设置并下发 native，加载时按 devices 过滤
        applyBackendPreference();

        try {
            int result = nativeInitModel(modelPath, nCtx, nThreads);
            if (result == 0) {
                cachedModelInitialized = true;
                lastModelInitCheckTime = System.currentTimeMillis();
            }
            // 组3.4：模型加载成功后，读取模型元数据
            if (result == 0) {
                try {
                    ModelMeta meta = new ModelMeta();
                    meta.nCtxTrain = nativeGetMetaNCtxTrain();
                    meta.nEmbd = nativeGetMetaNEmbd();
                    meta.nLayer = nativeGetMetaNLayer();
                    meta.nHead = nativeGetMetaNHead();
                    meta.nParams = nativeGetMetaNParams();
                    meta.modelName = nativeGetMetaModelName();
                    meta.valid = nativeGetMetaValid();
                    modelMeta = meta;
                    AILogger.i(TAG, "ModelMeta: nCtxTrain=" + meta.nCtxTrain + ", nEmbd=" + meta.nEmbd +
                            ", nLayer=" + meta.nLayer + ", nHead=" + meta.nHead +
                            ", nParams=" + meta.nParams + ", name=" + meta.modelName);
                } catch (UnsatisfiedLinkError e) {
                    AILogger.w(TAG, "Failed to read model meta: " + e.getMessage());
                }
            }
            return result;
        } catch (Throwable t) {
            AILogger.e(TAG, "Error initializing model: " + t.getMessage(), t);
            return -1;
        }
    }

    private static native int nativeInitModel(String modelPath, int nCtx, int nThreads);

    // ========== GPU 后端开关（hexagon / opencl / vulkan / auto）==========
    // 设置项 key: gpu_backend
    // 默认值 DEFAULT_BACKEND = "hexagon"（2026-10-09）：
    //   · 实测本机 SM8850 的 Hexagon/HTP NPU：prefill 约 1500 tok/s、decode 29~34 tok/s；
    //     同一模型走 OpenCL 只有 prefill ~205、decode ~15.6 tok/s。故选 NPU 为默认。
    //   · 前提是 Hexagon 后端已编入（GGML_HEXAGON=ON）且设备枚举出 HTP 设备；
    //     不满足时 native 侧 "no hexagon device found" 会自动回退到全部可用设备，
    //     不会因为默认值而拒绝加载。
    //   · 设为 opencl 的历史理由（2026-10-07）：Vulkan 走基础路径较慢，而 auto 会把
    //     Vulkan 与 OpenCL 一起放进 devices 互相拖慢。该理由只否定了旧的 auto 行为，
    //     不构成选 opencl 优于 NPU 的依据。
    //   · auto 现在的行为（2026-10-09）与默认值一致：只挑一个设备，优先 NPU，
    //     再退 OpenCL / Vulkan。旧 auto 传入多个设备会导致模型加载 segfault
    //     （signal 11 -> ggml_backend_dev_get_props），已修。
    // 想换回：设置项写 opencl / vulkan / auto 即可（运行时生效，无需重新编译）。
    public static final String DEFAULT_BACKEND = "hexagon";
    private static String sBackendPreference = DEFAULT_BACKEND;

    public static void setBackend(String backend) {
        if (backend == null) return;
        String b = backend.trim().toLowerCase();
        // NPU-SELECT(2026-10-09)：新增 "hexagon" —— Hexagon/HTP NPU 后端。
        // 与 opencl/vulkan 同为"限制 offload 目标设备"的语义：native 侧只把
        // 类型为 ACCEL、注册名为 "HTP" 的设备放进 model_params.devices。
        if ("opencl".equals(b) || "vulkan".equals(b) || "auto".equals(b) || "hexagon".equals(b)) {
            sBackendPreference = b;
            AILogger.i(TAG, "GPU backend preference set to: " + b);
        } else {
            AILogger.w(TAG, "Invalid GPU backend: " + backend);
        }
    }

    public static String getBackend() { return sBackendPreference; }

    /**
     * BACKEND-INIT-EARLY(2026-10-09)：在**进程启动时**读取并下发 GPU 后端偏好。
     *
     * <p>为什么必须在 Application.onCreate 调：模型加载由后台线程在 App 启动时就发起，
     * 远早于任何 Activity 的 onCreate。此前 {@code applyBackendPreference()} 只在
     * {@code initModel()} 内调用，读到的是 {@link #sBackendPreference} 的**静态默认值**
     * （"opencl"），于是出现"设置里存的是 hexagon、模型却按 opencl 加载"的不一致：</p>
     * <pre>
     * 03:26:58  initModel() 下发 opencl -> Backend switch: offload restricted to opencl
     * 03:27:04  Activity.onCreate 才读到 hexagon（已经晚了 6 秒）
     * </pre>
     *
     * @param context 任意 Context（Application 即可）
     * @return 生效的后端键
     */
    public static String initBackendPreference(Context context) {
        // DSP skel 目录：Hexagon 后端据此构造绝对 FastRPC URI（见 nativeSetDspLibDir）
        try {
            if (context != null) {
                String libDir = context.getApplicationInfo().nativeLibraryDir;
                if (libDir != null && !libDir.isEmpty()) {
                    nativeSetDspLibDir(libDir);
                }
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "initBackendPreference: set dsp lib dir failed: " + t.getMessage());
        }
        try {
            if (context != null) {
                // DEFAULT-NPU(2026-10-09)：默认后端改为 hexagon（NPU）。
                // 实测本机 SM8850：NPU prefill 约 1500 tok/s、decode 29~34 tok/s，
                // 明显优于 OpenCL（prefill ~205、decode ~15.6）。未安装 Hexagon 后端
                // 或设备不支持时，native 侧找不到 HTP 设备会自动回退全部设备。
                String saved = PreferenceManager.getDefaultSharedPreferences(context)
                        .getString("gpu_backend", DEFAULT_BACKEND);
                if (saved != null && !saved.isEmpty()) {
                    sBackendPreference = saved.trim().toLowerCase();
                }
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "initBackendPreference read failed: " + t.getMessage());
        }
        try {
            nativeSetBackend(sBackendPreference);
            AILogger.i(TAG, "GPU backend initialized early: " + sBackendPreference);
        } catch (UnsatisfiedLinkError e) {
            // native 库尚未加载也无妨：initModel() 时会再下发一次
            AILogger.w(TAG, "initBackendPreference: native not ready yet (" + e.getMessage() + ")");
        }
        return sBackendPreference;
    }

    /**
     * 最近一次**成功加载模型时真正下发给 native** 的后端；null = 本进程尚未加载过模型。
     *
     * <p>BACKEND-LOADED-STATE(2026-10-09)：存在的意义是不要把"偏好"当成"已生效"。
     * 后端偏好是在 {@code initModel()} 里下发的，改设置后必须重载模型才生效；
     * 若 UI 直接显示偏好值，就会出现"顶部说 NPU 已启用、实际 25 层还在 OpenCL 上"的假信息。</p>
     */
    private static String sLoadedBackend = null;

    /**
     * 返回"当前实际在用的后端"，未加载过模型时返回 null。
     * 与 {@link #getEffectiveBackend()}（下次加载将使用的偏好）区分开。
     */
    public static String getLoadedBackend() { return sLoadedBackend; }

    /**
     * 后端偏好键 -> 界面显示名。
     *
     * <p>集中在本类（后端状态的唯一真相源），避免各 Activity 各写一份三元链／switch
     * 而漏掉分支或默认值不一致。此前 AIServiceStatusActivity 与 DeviceInfoActivity
     * 就各自实现过一次，且后者把默认值写死成 "auto"，与 {@link #DEFAULT_BACKEND} 脱节。</p>
     *
     * @param backend "hexagon" / "opencl" / "vulkan" / "auto"；其它值按"自动"处理
     */
    public static String backendLabel(String backend) {
        if ("opencl".equals(backend)) return "OpenCL";
        if ("vulkan".equals(backend)) return "Vulkan";
        if ("hexagon".equals(backend)) return "NPU (Hexagon)";
        return "自动";
    }

    /**
     * BACKEND-SINGLE-SOURCE(2026-10-09)：返回**实际生效**的后端偏好。
     *
     * <p>存在理由：此前 UI 与 native 各自读 SharedPreferences 且**默认值不一致** ——
     * UI 用 {@code getString("gpu_backend", "auto")}、native 用 {@code ..., "opencl")}。
     * 首次安装未选任何后端时，就会出现"UI 显示自动、native 实际跑 opencl"的自相矛盾
     * （AI 服务页顶部状态行即由此显示成"自动"）。</p>
     *
     * <p>现在两边都走 {@link #DEFAULT_BACKEND} 与 {@link #sBackendPreference}
     * （native 真正下发的那个值）：先确保偏好已从设置读出并应用到 native，再返回它。</p>
     *
     * @return 实际生效的后端："hexagon" / "opencl" / "vulkan" / "auto"
     */
    public static String getEffectiveBackend() {
        applyBackendPreference();
        return sBackendPreference;
    }

    private static void applyBackendPreference() {
        try {
            Context ctx = AppServices.appContext();
            if (ctx != null) {
                String saved = PreferenceManager.getDefaultSharedPreferences(ctx)
                    .getString("gpu_backend", DEFAULT_BACKEND);   // 与 DEFAULT_BACKEND 保持同源
                if (saved != null && !saved.isEmpty()) {
                    sBackendPreference = saved.trim().toLowerCase();
                }
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "applyBackendPreference failed: " + t.getMessage());
        }
        try {
            nativeSetBackend(sBackendPreference);
            // BACKEND-LOADED-STATE：本方法是在 initModel() 里、nativeInitModel() 之前调用的，
            // 因此这里成功下发即等于"该后端将用于本次模型加载"，记为已生效。
            sLoadedBackend = sBackendPreference;
            AILogger.i(TAG, "GPU backend preference applied to native: " + sBackendPreference);
        } catch (UnsatisfiedLinkError e) {
            AILogger.w(TAG, "nativeSetBackend unavailable: " + e.getMessage());
        }
    }

    private static native void nativeSetBackend(String backend);

    /**
     * DSP-LIB-DIR(2026-10-09)：把应用 native 库目录告诉 native 侧。
     *
     * <p>Hexagon 后端默认构造的 FastRPC 模块 URI 是 {@code file:///libggml-htp-vNN.so}
     * （相对形式）。FastRPC 不会搜索应用 nativeLibraryDir，于是 cdsprpcd 报
     * {@code apps_std_fopen_with_env failed ... No such file or directory}，
     * 上下文初始化失败、服务初始化返回 -1。传入绝对目录后改为绝对 URI 即可。</p>
     */
    private static native void nativeSetDspLibDir(String dir);

    /**
     * RESOLVED-BACKEND(2026-10-09)：返回模型加载时**实际选中**的加速后端。
     *
     * <p>与 {@link #getLoadedBackend()} 的区别：后者返回的是**偏好值**，偏好为
     * {@code "auto"} 时无法说明最终落到了哪个设备。界面要显示"NPU 43层"就必须知道
     * 解析结果，故由 native 在选设备时记下并回传。</p>
     *
     * @return "hexagon" / "opencl" / "vulkan"；未选任何加速设备（纯 CPU）时为空串
     */
    public static String getResolvedBackend() {
        try {
            String v = nativeGetResolvedBackend();
            return v != null ? v : "";
        } catch (Throwable t) {
            // 老 so 或未加载：视为未知，调用方回退到偏好值取词
            AILogger.w(TAG, "getResolvedBackend unavailable: " + t.getMessage());
            return "";
        }
    }

    private static native String nativeGetResolvedBackend();

    // ========== 推理锁方法 ==========
    
    /**
     * 获取推理写锁（用于 generate/chatSend 等独占操作）
     * @return true 如果成功获取锁，false 如果超时
     */
    public static boolean acquireInferenceWriteLock() {
        try {
            return inferenceLock.writeLock().tryLock(INFERENCE_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * 释放推理写锁
     */
    public static void releaseInferenceWriteLock() {
        try {
            if (inferenceLock.getWriteHoldCount() > 0) {
                inferenceLock.writeLock().unlock();
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Error releasing write lock: " + e.getMessage());
        }
    }
    
    /**
     * 获取推理读锁（用于检查状态等共享操作）
     * @return true 如果成功获取锁，false 如果超时
     */
    public static boolean acquireInferenceReadLock() {
        try {
            return inferenceLock.readLock().tryLock(INFERENCE_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * 释放推理读锁
     */
    public static void releaseInferenceReadLock() {
        try {
            if (inferenceLock.getReadHoldCount() > 0) {
                inferenceLock.readLock().unlock();
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Error releasing read lock: " + e.getMessage());
        }
    }
    
    /**
     * 检查是否有正在进行的推理
     */
    public static boolean isInferenceInProgress() {
        return inferenceLock.getWriteHoldCount() > 0 || inferenceLock.getReadLockCount() > 0;
    }

    // 生成文本（同步）
    public static String generate(String prompt, int maxTokens, float temperature) {
        return generate(prompt, maxTokens, temperature, 0.9f, 40);
    }

    public static String generate(String prompt, int maxTokens, float temperature, float topP, int topK) {
        String threadName = Thread.currentThread().getName();
        long entryTime = System.currentTimeMillis();

        AILogger.i(TAG, "[generate-String] 入口: thread=" + threadName
            + ", promptLen=" + (prompt != null ? prompt.length() : 0)
            + ", maxTokens=" + maxTokens);

        if (!libraryLoaded) {
            AILogger.w(TAG, "[generate-String] 失败: Native库未加载, thread=" + threadName);
            return "Error: AI model not available";
        }

        // 获取推理锁，确保不与 chatSend 并发执行
        AILogger.i(TAG, "[generate-String] 尝试获取推理锁, thread=" + threadName
            + ", 当前锁持有数=" + inferenceLock.getWriteHoldCount()
            + ", 等待队列长度=" + inferenceLock.getQueueLength());
        long lockStart = System.currentTimeMillis();
        if (!acquireInferenceWriteLock()) {
            long lockWaitTime = System.currentTimeMillis() - lockStart;
            AILogger.w(TAG, "[generate-String] ❌ 获取推理锁超时(" + lockWaitTime + "ms), thread=" + threadName);
            return "Error: Inference lock timeout";
        }
        long lockAcquireTime = System.currentTimeMillis() - lockStart;
        AILogger.i(TAG, "[generate-String] ✅ 获取推理锁成功(" + lockAcquireTime + "ms), thread=" + threadName
            + ", 持有数=" + inferenceLock.getWriteHoldCount());

        try {
            AILogger.i(TAG, "[generate-String] 调用 nativeGenerate, thread=" + threadName);
            long nativeStart = System.currentTimeMillis();
            String result = nativeGenerate(prompt, maxTokens, temperature, topP, topK);
            long nativeTime = System.currentTimeMillis() - nativeStart;
            AILogger.i(TAG, "[generate-String] nativeGenerate 完成(" + nativeTime + "ms), thread=" + threadName
                + ", resultLen=" + (result != null ? result.length() : 0));
            return result;
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "[generate-String] ❌ UnsatisfiedLinkError: " + e.getMessage()
                + ", thread=" + threadName, e);
            return "Error: AI generation failed";
        } catch (Exception e) {
            AILogger.e(TAG, "[generate-String] ❌ 异常: " + e.getClass().getSimpleName()
                + ": " + e.getMessage() + ", thread=" + threadName, e);
            return "Error: AI generation failed";
        } finally {
            AILogger.i(TAG, "[generate-String] 释放推理锁前, 持有数=" + inferenceLock.getWriteHoldCount()
                + ", thread=" + threadName);
            releaseInferenceWriteLock();
            long totalTime = System.currentTimeMillis() - entryTime;
            AILogger.i(TAG, "[generate-String] ✅ 完成, 总耗时=" + totalTime + "ms, thread=" + threadName);
        }
    }

    private static native String nativeGenerate(String prompt, int maxTokens, float temperature, float topP, int topK);

    // 生成文本（同步）- 接收消息列表，native 层用 llama_chat_apply_template 自动适配模型格式
    // 不污染多轮对话状态，适合单次生成场景（翻译、题目生成等）
    public static String generate(List<PromptBuilder.Message> messages, int maxTokens, float temperature) {
        return generate(messages, maxTokens, temperature, 0.9f, 40);
    }

    public static String generate(List<PromptBuilder.Message> messages, int maxTokens, float temperature, float topP, int topK) {
        String threadName = Thread.currentThread().getName();
        long entryTime = System.currentTimeMillis();

        AILogger.i(TAG, "[generate-Messages] 入口: thread=" + threadName
            + ", msgCount=" + (messages != null ? messages.size() : 0)
            + ", maxTokens=" + maxTokens);

        if (!libraryLoaded) {
            AILogger.w(TAG, "[generate-Messages] 失败: Native库未加载, thread=" + threadName);
            return "Error: AI model not available";
        }
        if (messages == null || messages.isEmpty()) {
            AILogger.e(TAG, "[generate-Messages] 失败: 消息列表为空, thread=" + threadName);
            return "Error: Messages list is empty";
        }

        // 获取推理锁，确保不与 chatSend 并发执行
        AILogger.i(TAG, "[generate-Messages] 尝试获取推理锁, thread=" + threadName
            + ", 当前锁持有数=" + inferenceLock.getWriteHoldCount()
            + ", 等待队列长度=" + inferenceLock.getQueueLength());
        long lockStart = System.currentTimeMillis();
        if (!acquireInferenceWriteLock()) {
            long lockWaitTime = System.currentTimeMillis() - lockStart;
            AILogger.w(TAG, "[generate-Messages] ❌ 获取推理锁超时(" + lockWaitTime + "ms), thread=" + threadName);
            return "Error: Inference lock timeout";
        }
        long lockAcquireTime = System.currentTimeMillis() - lockStart;
        AILogger.i(TAG, "[generate-Messages] ✅ 获取推理锁成功(" + lockAcquireTime + "ms), thread=" + threadName
            + ", 持有数=" + inferenceLock.getWriteHoldCount());

        try {
            final StringBuilder result = new StringBuilder();
            final String[] error = {null};
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            AILogger.i(TAG, "[generate-Messages] 调用 generateStream(消息列表), thread=" + threadName);
            // 注意: generateStream 内部会重入获取推理锁(ReentrantReadWriteLock 可重入)
            generateStream(messages, maxTokens, temperature, topP, topK, false, new TokenCallback() {
                @Override
                public void onToken(String token) {
                    if (token != null) {
                        result.append(token);
                    }
                }

                @Override
                public void onComplete(String fullText) {
                    // 优先使用 fullText，否则用累积的 token
                    if (fullText != null && !fullText.isEmpty()) {
                        result.setLength(0);
                        result.append(fullText);
                    }
                    AILogger.i(TAG, "[generate-Messages] onComplete 回调, resultLen=" + result.length()
                        + ", thread=" + threadName);
                    latch.countDown();
                }

                @Override
                public void onError(String errorMsg) {
                    AILogger.e(TAG, "[generate-Messages] onError 回调: " + errorMsg + ", thread=" + threadName);
                    error[0] = errorMsg;
                    latch.countDown();
                }
            });

            // 等待生成完成（120 秒超时，与旧 generateSync 一致）
            AILogger.i(TAG, "[generate-Messages] 等待 latch 完成(120s超时), thread=" + threadName);
            long waitStart = System.currentTimeMillis();
            if (!latch.await(120, java.util.concurrent.TimeUnit.SECONDS)) {
                long waitTime = System.currentTimeMillis() - waitStart;
                AILogger.e(TAG, "[generate-Messages] ❌ latch 等待超时(" + waitTime + "ms), thread=" + threadName);
                return "Error: Generation timeout";
            }
            long waitTime = System.currentTimeMillis() - waitStart;
            AILogger.i(TAG, "[generate-Messages] latch 完成, 等待耗时=" + waitTime + "ms, thread=" + threadName);

            if (error[0] != null) {
                AILogger.e(TAG, "[generate-Messages] ❌ 回调返回错误: " + error[0] + ", thread=" + threadName);
                return "Error: " + error[0];
            }

            AILogger.i(TAG, "[generate-Messages] 生成成功, resultLen=" + result.length() + ", thread=" + threadName);
            return result.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AILogger.e(TAG, "[generate-Messages] ❌ InterruptedException: " + e.getMessage()
                + ", thread=" + threadName, e);
            return "Error: Generation interrupted";
        } catch (Exception e) {
            AILogger.e(TAG, "[generate-Messages] ❌ 异常: " + e.getClass().getSimpleName()
                + ": " + e.getMessage() + ", thread=" + threadName, e);
            return "Error: AI generation failed";
        } finally {
            AILogger.i(TAG, "[generate-Messages] 释放推理锁前, 持有数=" + inferenceLock.getWriteHoldCount()
                + ", thread=" + threadName);
            releaseInferenceWriteLock();
            long totalTime = System.currentTimeMillis() - entryTime;
            AILogger.i(TAG, "[generate-Messages] ✅ 完成, 总耗时=" + totalTime + "ms, thread=" + threadName);
        }
    }

    // 生成文本（流式）
    public static void generateStream(String prompt, int maxTokens, float temperature, float topP, int topK, boolean enableThinking, TokenCallback callback) {
        String threadName = Thread.currentThread().getName();
        long threadId = Thread.currentThread().getId();
        long entryTime = System.currentTimeMillis();

        AILogger.i(TAG, "[generateStream-String] 入口: thread=" + threadName + "(" + threadId + ")"
            + ", promptLen=" + (prompt != null ? prompt.length() : 0)
            + ", maxTokens=" + maxTokens
            + ", temp=" + temperature
            + ", hasCallback=" + (callback != null));

        if (!libraryLoaded) {
            AILogger.w(TAG, "[generateStream-String] 失败: Native库未加载, thread=" + threadName);
            if (callback != null) {
                callback.onError("AI model not available");
            }
            return;
        }

        // 获取推理锁，防止并发推理导致 native 层崩溃
        // ReentrantReadWriteLock 写锁可重入，generate(List<Message>) 调用时不会死锁
        AILogger.i(TAG, "[generateStream-String] 尝试获取推理锁, thread=" + threadName
            + ", 当前锁持有数=" + inferenceLock.getWriteHoldCount()
            + ", 等待队列长度=" + inferenceLock.getQueueLength());
        long lockStart = System.currentTimeMillis();
        if (!acquireInferenceWriteLock()) {
            long lockWaitTime = System.currentTimeMillis() - lockStart;
            AILogger.w(TAG, "[generateStream-String] ❌ 获取推理锁超时(" + lockWaitTime + "ms), thread=" + threadName
                + ", 可能存在并发推理");
            if (callback != null) {
                callback.onError("Generation already in progress");
            }
            return;
        }
        long lockAcquireTime = System.currentTimeMillis() - lockStart;
        AILogger.i(TAG, "[generateStream-String] ✅ 获取推理锁成功(" + lockAcquireTime + "ms), thread=" + threadName
            + ", 持有数=" + inferenceLock.getWriteHoldCount());

        try {
            AILogger.i(TAG, "[generateStream-String] 调用 nativeGenerateStream, thread=" + threadName);
            long nativeStart = System.currentTimeMillis();
            nativeGenerateStream(prompt, maxTokens, temperature, topP, topK, enableThinking, callback);
            long nativeTime = System.currentTimeMillis() - nativeStart;
            AILogger.i(TAG, "[generateStream-String] nativeGenerateStream 完成(" + nativeTime + "ms), thread=" + threadName);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "[generateStream-String] ❌ UnsatisfiedLinkError: " + e.getMessage()
                + ", thread=" + threadName, e);
            if (callback != null) {
                callback.onError("AI generation failed");
            }
        } catch (Exception e) {
            AILogger.e(TAG, "[generateStream-String] ❌ 异常: " + e.getClass().getSimpleName()
                + ": " + e.getMessage() + ", thread=" + threadName, e);
            if (callback != null) {
                callback.onError("AI generation failed: " + e.getMessage());
            }
        } finally {
            AILogger.i(TAG, "[generateStream-String] 释放推理锁前, 持有数=" + inferenceLock.getWriteHoldCount()
                + ", thread=" + threadName);
            releaseInferenceWriteLock();
            long totalTime = System.currentTimeMillis() - entryTime;
            AILogger.i(TAG, "[generateStream-String] ✅ 完成, 总耗时=" + totalTime + "ms, thread=" + threadName);
        }
    }

    private static native void nativeGenerateStream(String prompt, int maxTokens, float temperature, float topP, int topK, boolean enableThinking, TokenCallback callback);

    // 生成文本（流式）- 接收消息列表，native 层用 llama_chat_apply_template 自动适配模型格式
    // 不污染多轮对话状态，适合单次生成场景
    public static void generateStream(List<PromptBuilder.Message> messages, int maxTokens, float temperature, float topP, int topK, boolean enableThinking, TokenCallback callback) {
        String threadName = Thread.currentThread().getName();
        long threadId = Thread.currentThread().getId();
        long entryTime = System.currentTimeMillis();

        AILogger.i(TAG, "[generateStream-Messages] 入口: thread=" + threadName + "(" + threadId + ")"
            + ", msgCount=" + (messages != null ? messages.size() : 0)
            + ", maxTokens=" + maxTokens
            + ", temp=" + temperature
            + ", hasCallback=" + (callback != null));

        if (!libraryLoaded) {
            AILogger.w(TAG, "[generateStream-Messages] 失败: Native库未加载, thread=" + threadName);
            if (callback != null) {
                callback.onError("AI model not available");
            }
            return;
        }
        if (messages == null || messages.isEmpty()) {
            AILogger.e(TAG, "[generateStream-Messages] 失败: 消息列表为空, thread=" + threadName);
            if (callback != null) {
                callback.onError("Messages list is empty");
            }
            return;
        }
        // 转换为并行数组：roles（String[]）+ contents（byte[][]，UTF-8 编码避免中文问题）
        String[] roles = new String[messages.size()];
        byte[][] contents = new byte[messages.size()][];
        for (int i = 0; i < messages.size(); i++) {
            PromptBuilder.Message msg = messages.get(i);
            roles[i] = msg.role();
            contents[i] = msg.content() == null ? new byte[0] : msg.content().getBytes(StandardCharsets.UTF_8);
        }
        // 组3.6：最后一扇门——防超 n_ctx assert
        int safeRef = getSafeContextReference(contextTotalSize > 0 ? contextTotalSize : 4096);
        int promptTokens = 0;
        for (PromptBuilder.Message msg : messages) {
            if (msg.content() != null) {
                promptTokens += countTokens(msg.content());
            }
        }
        AILogger.i(TAG, "[generateStream-Messages] Token检查: promptTokens=" + promptTokens
            + ", maxTokens=" + maxTokens + ", safeRef=" + safeRef
            + ", thread=" + threadName);
        // 修复：max_tokens 是生成停止上限，不计入 context 预算（见 chatJson）
        final int GENERATION_RESERVE = 512;
        if (promptTokens >= safeRef - GENERATION_RESERVE) {
            AILogger.w(TAG, "[generateStream-Messages] ❌ Prompt过长: " + promptTokens + ">=" + (safeRef - GENERATION_RESERVE)
                + ", thread=" + threadName);
            if (callback != null) {
                callback.onError("Prompt too long: " + promptTokens + " tokens, aborting");
            }
            return;
        }

        // 获取推理锁，防止并发推理导致 native 层崩溃
        // ReentrantReadWriteLock 写锁可重入，generate(List<Message>) 调用时不会死锁
        AILogger.i(TAG, "[generateStream-Messages] 尝试获取推理锁, thread=" + threadName
            + ", 当前锁持有数=" + inferenceLock.getWriteHoldCount()
            + ", 等待队列长度=" + inferenceLock.getQueueLength());
        long lockStart = System.currentTimeMillis();
        if (!acquireInferenceWriteLock()) {
            long lockWaitTime = System.currentTimeMillis() - lockStart;
            AILogger.w(TAG, "[generateStream-Messages] ❌ 获取推理锁超时(" + lockWaitTime + "ms), thread=" + threadName
                + ", 可能存在并发推理");
            if (callback != null) {
                callback.onError("Generation already in progress");
            }
            return;
        }
        long lockAcquireTime = System.currentTimeMillis() - lockStart;
        AILogger.i(TAG, "[generateStream-Messages] ✅ 获取推理锁成功(" + lockAcquireTime + "ms), thread=" + threadName
            + ", 持有数=" + inferenceLock.getWriteHoldCount());

        try {
            AILogger.i(TAG, "[generateStream-Messages] 调用 nativeGenerateStreamFromMessages, thread=" + threadName);
            long nativeStart = System.currentTimeMillis();
            nativeGenerateStreamFromMessages(roles, contents, maxTokens, temperature, topP, topK, enableThinking, callback);
            long nativeTime = System.currentTimeMillis() - nativeStart;
            AILogger.i(TAG, "[generateStream-Messages] nativeGenerateStreamFromMessages 完成(" + nativeTime + "ms), thread=" + threadName);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "[generateStream-Messages] ❌ UnsatisfiedLinkError: " + e.getMessage()
                + ", thread=" + threadName, e);
            if (callback != null) {
                callback.onError("AI generation failed");
            }
        } catch (Exception e) {
            AILogger.e(TAG, "[generateStream-Messages] ❌ 异常: " + e.getClass().getSimpleName()
                + ": " + e.getMessage() + ", thread=" + threadName, e);
            if (callback != null) {
                callback.onError("AI generation failed: " + e.getMessage());
            }
        } finally {
            AILogger.i(TAG, "[generateStream-Messages] 释放推理锁前, 持有数=" + inferenceLock.getWriteHoldCount()
                + ", thread=" + threadName);
            releaseInferenceWriteLock();
            long totalTime = System.currentTimeMillis() - entryTime;
            AILogger.i(TAG, "[generateStream-Messages] ✅ 完成, 总耗时=" + totalTime + "ms, thread=" + threadName);
        }
    }

    private static native void nativeGenerateStreamFromMessages(String[] roles, byte[][] contents, int maxTokens, float temperature, float topP, int topK, boolean enableThinking, TokenCallback callback);

    /**
     * 原生 Function Calling 生成
     * 使用 llama.cpp 底层的 common_chat_templates_apply 传入 tools，
     * 模型会根据原生格式（Qwen Hermes/Llama3/Mistral 等）自动格式化工具调用。
     *
     * @param roles 消息角色数组
     * @param contents 消息内容字节数组
     * @param toolsJson OpenAI 格式的 tools JSON 数组
     * @param maxTokens 最大生成 token 数
     * @param temperature 温度
     * @param topP top_p
     * @param topK top_k
     * @param enableThinking 是否启用思考
     * @param callback Token 回调
     */
    public static void generateWithTools(String[] roles, byte[][] contents, byte[] toolsJson,
                                          int maxTokens, float temperature, float topP, int topK,
                                          boolean enableThinking, TokenCallback callback) {
        String threadName = Thread.currentThread().getName();
        AILogger.i(TAG, "[generateWithTools] 入口: thread=" + threadName
                + ", messages=" + roles.length
                + ", toolsJsonLen=" + (toolsJson != null ? toolsJson.length : 0));

        if (!libraryLoaded) {
            if (callback != null) callback.onError("AI model not available");
            return;
        }

        // 最后一扇门：防超 n_ctx assert——prompt 过长时优雅失败，避免 native 崩溃
        try {
            int safeRef = getSafeContextReference(contextTotalSize > 0 ? contextTotalSize : 4096);
            int promptTokens = 0;
            for (int i = 0; i < roles.length; i++) {
                promptTokens += countTokens(new String(contents[i], StandardCharsets.UTF_8));
            }
            if (toolsJson != null && toolsJson.length > 0) {
                promptTokens += countTokens(new String(toolsJson, StandardCharsets.UTF_8));
            }
            // 修复：max_tokens 是生成停止上限，不计入 context 预算（见 chatJson）
            final int GENERATION_RESERVE = 512;
            if (promptTokens >= safeRef - GENERATION_RESERVE) {
                AILogger.e(TAG, "[generateWithTools] ❌ Prompt过长: " + promptTokens
                        + " >= " + (safeRef - GENERATION_RESERVE) + "，拒绝生成避免 native 崩溃");
                if (callback != null) callback.onError("Prompt too long: " + promptTokens + " tokens, aborting");
                return;
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "[generateWithTools] 预算检查失败(放行): " + t.getMessage());
        }

        // 获取推理锁
        long lockStart = System.currentTimeMillis();
        try {
            if (!inferenceLock.writeLock().tryLock(120, TimeUnit.SECONDS)) {
                AILogger.e(TAG, "[generateWithTools] 获取推理锁超时");
                if (callback != null) callback.onError("Inference lock timeout");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (callback != null) callback.onError("Interrupted");
            return;
        }

        try {
            nativeGenerateWithTools(roles, contents, toolsJson, maxTokens, temperature, topP, topK, enableThinking, callback);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "[generateWithTools] UnsatisfiedLinkError: " + e.getMessage());
            if (callback != null) callback.onError("Native method not available");
        } finally {
            inferenceLock.writeLock().unlock();
        }
    }

    private static native void nativeGenerateWithTools(String[] roles, byte[][] contents, byte[] toolsJson, int maxTokens, float temperature, float topP, int topK, boolean enableThinking, TokenCallback callback);

    /**
     * 统一 JSON 协议入口（spec §7.1）
     * 请求：{"action":"chat","messages":[...],"tools":[...],"tool_choice":"auto|required|none",
     *        "enable_thinking":bool,"max_tokens":int,"temperature":float,"top_p":float,"top_k":int}
     * C++ 层完成模板格式化 + KV 增量生成 + parse 解析，统一 onJson 事件回调。
     */
    public static void chatJson(String requestJson, JsonCallback callback) {
        String threadName = Thread.currentThread().getName();
        AILogger.i(TAG, "[chatJson] 入口: thread=" + threadName
                + ", requestLen=" + (requestJson != null ? requestJson.length() : 0));

        if (!libraryLoaded) {
            if (callback != null) callback.onError("AI model not available");
            return;
        }
        if (requestJson == null || requestJson.isEmpty()) {
            if (callback != null) callback.onError("Empty request");
            return;
        }

        // 最后一扇门：防超 n_ctx assert（与 generateStream 一致）——prompt 过长时优雅失败，
        // 避免 llama.cpp 断言崩溃导致进程退出（Agent 循环/FC 循环都走 chatJson）
        try {
            int safeRef = getSafeContextReference(contextTotalSize > 0 ? contextTotalSize : 4096);
            int promptTokens = estimateChatJsonPromptTokens(requestJson);
            // 修复：max_tokens 是生成停止上限，不是 context 预算的一部分。
            // 原 promptTokens + maxTokens 会把大 max_tokens（如 16384 > n_ctx 12288）
            // 误判为超长，导致 11 token 的短 prompt 也被拒绝。这里只校验 prompt
            // 本体是否超出安全窗口，预留固定生成余量（生成到顶时自然截断）。
            final int GENERATION_RESERVE = 512;
            if (promptTokens >= safeRef - GENERATION_RESERVE) {
                AILogger.e(TAG, "[chatJson] ❌ Prompt过长: " + promptTokens
                        + " >= " + (safeRef - GENERATION_RESERVE) + "，拒绝生成避免 native 崩溃");
                if (callback != null) callback.onError("Prompt too long: " + promptTokens + " tokens, aborting");
                return;
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "[chatJson] 预算检查失败(放行): " + t.getMessage());
        }

        // 获取推理锁（与 generateWithTools 同一把写锁，防止并发推理）
        try {
            if (!inferenceLock.writeLock().tryLock(120, TimeUnit.SECONDS)) {
                AILogger.e(TAG, "[chatJson] 获取推理锁超时");
                if (callback != null) callback.onError("Inference lock timeout");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (callback != null) callback.onError("Interrupted");
            return;
        }

        try {
            nativeChatJson(requestJson.getBytes(StandardCharsets.UTF_8), callback);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "[chatJson] UnsatisfiedLinkError: " + e.getMessage());
            if (callback != null) callback.onError("Native method not available");
        } catch (Throwable t) {
            AILogger.e(TAG, "[chatJson] 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            if (callback != null) callback.onError("chatJson failed: " + t.getMessage());
        } finally {
            inferenceLock.writeLock().unlock();
        }
    }

    private static native void nativeChatJson(byte[] requestJson, JsonCallback callback);
    // ===== KV 记忆引擎（独立 seq 1，长文档 KV 持久化，living-kv 方案）=====
    private static native boolean nativeKvMemPreload(long handle, String text);
    private static native byte[] nativeKvMemSave(long handle);
    private static native boolean nativeKvMemRestore(long handle, byte[] data);
    private static native String nativeKvMemAsk(long handle, String question, int maxTokens, float temperature);

    /**
     * 读取 GGUF 头部元数据（架构信息卡片 + NPU 上下文预算规划用）。
     *
     * <p>实现走 ggml/llama.cpp 自带的官方 GGUF API（{@code gguf_init_from_file}，no_alloc），
     * 不再由 Java 手写解析器读取 —— 手写解析器出过"位置漂移 → EOFException → 静默返回 null"，
     * 导致 NPU 的 KV 估算拿不到模型规格、上下文规划走 4096 兜底、Agent 工具结果被裁掉。</p>
     *
     * <p>返回值为字段数组；未找到的键为 -1。失败返回 null。字段顺序见
     * {@code com.oilquiz.app.ai.model.GgufMeta} 中的 IDX_* 常量。</p>
     */
    public static native long[] nativeReadGgufMeta(String path);

    // 生成文本（流式）- 使用ChatRequest批量传递参数，解决中文编码问题
    public static void generateStream(ChatRequest request, TokenCallback callback) {
        String threadName = Thread.currentThread().getName();
        long threadId = Thread.currentThread().getId();
        long entryTime = System.currentTimeMillis();

        AILogger.i(TAG, "[generateStream-ChatRequest] 入口: thread=" + threadName + "(" + threadId + ")"
            + ", hasRequest=" + (request != null)
            + ", maxTokens=" + (request != null ? request.getMaxTokens() : 0)
            + ", hasCallback=" + (callback != null));

        if (!libraryLoaded) {
            AILogger.w(TAG, "[generateStream-ChatRequest] 失败: Native库未加载, thread=" + threadName);
            if (callback != null) {
                callback.onError("AI model not available");
            }
            return;
        }
        if (request == null || request.getFullPromptUtf8() == null) {
            AILogger.e(TAG, "[generateStream-ChatRequest] 失败: 无效的ChatRequest, thread=" + threadName);
            if (callback != null) {
                callback.onError("Invalid request");
            }
            return;
        }

        // 最后一扇门：防超 n_ctx assert（与 generateStream-Messages 一致）
        try {
            int safeRef = getSafeContextReference(contextTotalSize > 0 ? contextTotalSize : 4096);
            int promptTokens = countTokens(new String(request.getFullPromptUtf8(), StandardCharsets.UTF_8));
            // 修复：max_tokens 是生成停止上限，不计入 context 预算（见 chatJson）
            final int GENERATION_RESERVE = 512;
            if (promptTokens >= safeRef - GENERATION_RESERVE) {
                AILogger.w(TAG, "[generateStream-ChatRequest] ❌ Prompt过长: " + promptTokens + ">="
                        + (safeRef - GENERATION_RESERVE) + ", thread=" + threadName);
                if (callback != null) {
                    callback.onError("Prompt too long: " + promptTokens + " tokens, aborting");
                }
                return;
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "[generateStream-ChatRequest] 预算检查失败(放行): " + t.getMessage());
        }

        // 获取推理锁，防止并发推理导致 native 层崩溃
        AILogger.i(TAG, "[generateStream-ChatRequest] 尝试获取推理锁, thread=" + threadName
            + ", 当前锁持有数=" + inferenceLock.getWriteHoldCount()
            + ", 等待队列长度=" + inferenceLock.getQueueLength());
        long lockStart = System.currentTimeMillis();
        if (!acquireInferenceWriteLock()) {
            long lockWaitTime = System.currentTimeMillis() - lockStart;
            AILogger.w(TAG, "[generateStream-ChatRequest] ❌ 获取推理锁超时(" + lockWaitTime + "ms), thread=" + threadName
                + ", 可能存在并发推理");
            if (callback != null) {
                callback.onError("Generation already in progress");
            }
            return;
        }
        long lockAcquireTime = System.currentTimeMillis() - lockStart;
        AILogger.i(TAG, "[generateStream-ChatRequest] ✅ 获取推理锁成功(" + lockAcquireTime + "ms), thread=" + threadName
            + ", 持有数=" + inferenceLock.getWriteHoldCount());

        try {
            AILogger.i(TAG, "[generateStream-ChatRequest] 调用 nativeGenerateStreamBytes, thread=" + threadName);
            long nativeStart = System.currentTimeMillis();
            nativeGenerateStreamBytes(
                request.getFullPromptUtf8(),
                request.getMaxTokens(),
                request.getTemperature(),
                request.getTopP(),
                request.getTopK(),
                request.isEnableThinking(),
                callback
            );
            long nativeTime = System.currentTimeMillis() - nativeStart;
            AILogger.i(TAG, "[generateStream-ChatRequest] nativeGenerateStreamBytes 完成(" + nativeTime + "ms), thread=" + threadName);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "[generateStream-ChatRequest] ❌ UnsatisfiedLinkError: " + e.getMessage()
                + ", thread=" + threadName, e);
            if (callback != null) {
                callback.onError("AI generation failed");
            }
        } catch (Exception e) {
            AILogger.e(TAG, "[generateStream-ChatRequest] ❌ 异常: " + e.getClass().getSimpleName()
                + ": " + e.getMessage() + ", thread=" + threadName, e);
            if (callback != null) {
                callback.onError("AI generation failed: " + e.getMessage());
            }
        } finally {
            AILogger.i(TAG, "[generateStream-ChatRequest] 释放推理锁前, 持有数=" + inferenceLock.getWriteHoldCount()
                + ", thread=" + threadName);
            releaseInferenceWriteLock();
            long totalTime = System.currentTimeMillis() - entryTime;
            AILogger.i(TAG, "[generateStream-ChatRequest] ✅ 完成, 总耗时=" + totalTime + "ms, thread=" + threadName);
        }
    }

    private static native void nativeGenerateStreamBytes(byte[] promptUtf8, int maxTokens, float temperature,
                                                         float topP, int topK, boolean enableThinking, TokenCallback callback);

    // 停止生成
    public static void stopGeneration() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot stop generation");
            return;
        }
        try {
            nativeStopGeneration();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error stopping generation: " + e.getMessage(), e);
        }
    }

    private static native void nativeStopGeneration();

    // 清空历史
    public static void clearHistory() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot clear history");
            return;
        }
        try {
            nativeClearHistory();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error clearing history: " + e.getMessage(), e);
        }
    }

    private static native void nativeClearHistory();

    // 释放资源
    /**
     * 释放模型资源（应用内热切换后端时调用）。持推理写锁执行：
     * 推理进行中释放会破坏正在解码的状态（竞态），先等锁再释放。
     */
    public static void release() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot release resources");
            return;
        }
        long lockStart = System.currentTimeMillis();
        if (!acquireInferenceWriteLock()) {
            AILogger.w(TAG, "release 获取推理锁超时("
                    + (System.currentTimeMillis() - lockStart) + "ms)，放弃释放");
            return;
        }
        try {
            nativeRelease();
            cachedModelInitialized = false;
            lastModelInitCheckTime = System.currentTimeMillis();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error releasing resources: " + e.getMessage(), e);
        } finally {
            releaseInferenceWriteLock();
        }
    }

    private static native void nativeRelease();

    // ========== 多模态视觉支持 ==========

    /**
     * 加载多模态投影文件（mmproj）
     * @param mmprojPath mmproj 文件路径
     * @return true 成功，false 失败
     */
    public static boolean loadMultimodal(String mmprojPath) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot load multimodal");
            return false;
        }
        try {
            return nativeLoadMultimodal(mmprojPath);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error loading multimodal: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 释放多模态上下文
     */
    public static void releaseMultimodal() {
        if (!libraryLoaded) return;
        try {
            nativeReleaseMultimodal();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error releasing multimodal: " + e.getMessage(), e);
        }
    }

    /**
     * 检查多模态是否已加载
     */
    public static boolean isMultimodalLoaded() {
        if (!libraryLoaded) return false;
        try {
            return nativeIsMultimodalLoaded();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /**
     * 带图像的生成长文本（流式）
     * @param history 历史消息列表（用于上下文）
     * @param prompt 当前用户文本
     * @param imagePath 图像文件路径
     * @param maxTokens 最大生成 token 数
     * @param temperature 温度
     * @param topP top-p 采样
     * @param topK top-k 采样
     * @param enableThinking 是否启用思考
     * @param callback 流式回调
     */
    public static void generateWithImage(List<ChatMessage> history, String prompt, String imagePath, int maxTokens,
                                          float temperature, float topP, int topK,
                                          boolean enableThinking, TokenCallback callback) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot generate with image");
            if (callback != null) {
                callback.onError("Library not loaded");
            }
            return;
        }
        // 与文本推理共用同一把写锁，防止多模态推理与普通推理并发导致 native 崩溃
        long lockStart = System.currentTimeMillis();
        if (!acquireInferenceWriteLock()) {
            AILogger.e(TAG, "generateWithImage: 获取推理锁超时(" + (System.currentTimeMillis() - lockStart) + "ms)");
            if (callback != null) {
                callback.onError("Generation already in progress");
            }
            return;
        }
        try {
            ChatMessage[] historyArray = history != null ? history.toArray(new ChatMessage[0]) : null;
            nativeGenerateWithImage(historyArray, prompt, imagePath, maxTokens, temperature, topP, topK, enableThinking, callback);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error generating with image: " + e.getMessage(), e);
            if (callback != null) {
                callback.onError("Native method not found: " + e.getMessage());
            }
        } finally {
            releaseInferenceWriteLock();
        }
    }

    private static native boolean nativeLoadMultimodal(String mmprojPath);
    private static native void nativeReleaseMultimodal();
    private static native boolean nativeIsMultimodalLoaded();
    private static native void nativeGenerateWithImage(ChatMessage[] history, String prompt, String imagePath, int maxTokens,
                                                        float temperature, float topP, int topK,
                                                        boolean enableThinking, TokenCallback callback);

    // 获取模型信息
    public static String getModelInfo() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get model info");
            return "Error: AI model not available";
        }
        try {
            return nativeGetModelInfo();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting model info: " + e.getMessage(), e);
            return "Error: Failed to get model info";
        }
    }

    private static native String nativeGetModelInfo();

    // 性能监控相关方法
    public static float getInferenceSpeed() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get inference speed");
            return 0;
        }
        try {
            return nativeGetInferenceSpeed();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting inference speed: " + e.getMessage(), e);
            return 0;
        }
    }

    private static native float nativeGetInferenceSpeed();

    /**
     * 纯 decode 速度（tokens/s）：仅统计正文生成阶段（思考段不计），
     * think_end 后开始计时，反映模型实际解码正文的速度。
     */
    public static float getDecodeSpeed() {
        if (!libraryLoaded) return 0;
        try {
            return nativeGetDecodeSpeed();
        } catch (UnsatisfiedLinkError e) {
            AILogger.w(TAG, "nativeGetDecodeSpeed unavailable: " + e.getMessage());
            return 0;
        }
    }

    private static native float nativeGetDecodeSpeed();

    /**
     * 当前阶段速度（tokens/s）：按 native 状态机阶段返回对应速度。
     * THINKING → 思考段速度；GENERATING → 正文解码速度；其他阶段返回 0。
     * 用于对话页 ⚡ t/s 随推理阶段切换显示。
     */
    public static float getPhaseSpeed() {
        if (!libraryLoaded) return 0;
        try {
            return nativeGetPhaseSpeed();
        } catch (UnsatisfiedLinkError e) {
            AILogger.w(TAG, "nativeGetPhaseSpeed unavailable: " + e.getMessage());
            return 0;
        }
    }

    private static native float nativeGetPhaseSpeed();

    /**
     * PREPROCESS（prefill）阶段进度 JSON：{"done":已处理,"total":本轮总数,"pct":百分比}。
     * 用于对话页状态条显示 prefill 进度；空闲返回 0/0。
     */
    public static String getPrefillProgress() {
        if (!libraryLoaded) return null;
        try {
            return nativeGetPrefillProgress();
        } catch (UnsatisfiedLinkError e) {
            AILogger.w(TAG, "nativeGetPrefillProgress unavailable: " + e.getMessage());
            return null;
        }
    }

    private static native String nativeGetPrefillProgress();

    public static float getMemoryUsage() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get memory usage");
            return 0;
        }
        try {
            return nativeGetMemoryUsage();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting memory usage: " + e.getMessage(), e);
            return 0;
        }
    }

    private static native float nativeGetMemoryUsage();

    public static int getTokenCount() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get token count");
            return 0;
        }
        try {
            return nativeGetTokenCount();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting token count: " + e.getMessage(), e);
            return 0;
        }
    }

    private static native int nativeGetTokenCount();

    // 性能优化相关方法
    public static void optimizeForPerformance() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot optimize for performance");
            return;
        }
        try {
            nativeOptimizeForPerformance();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error optimizing for performance: " + e.getMessage(), e);
        }
    }

    private static native void nativeOptimizeForPerformance();

    public static void optimizeForMemory() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot optimize for memory");
            return;
        }
        try {
            nativeOptimizeForMemory();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error optimizing for memory: " + e.getMessage(), e);
        }
    }

    private static native void nativeOptimizeForMemory();

    // GPU优化相关方法
    public static void setGPULayers(int layers) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot set GPU layers");
            return;
        }
        try {
            nativeSetGPULayers(layers);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error setting GPU layers: " + e.getMessage(), e);
        }
    }

    private static native void nativeSetGPULayers(int layers);

    public static int getGPULayers() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get GPU layers");
            return 0;
        }
        try {
            return nativeGetGPULayers();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting GPU layers: " + e.getMessage(), e);
            return 0;
        }
    }

    private static native int nativeGetGPULayers();

    /**
     * 降低 GPU 层数以减少设备卡顿
     * ⚠ 注意：n_gpu_layers 仅在模型加载（initModel）时生效，运行时修改对已加载模型无效；
     * 需先 release() 再重新 initModel（新层数）才能真正降低 GPU 负载。
     * @param targetLayers 目标层数（建议 15-20）
     */
    public static void reduceGPULayers(int targetLayers) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot reduce GPU layers");
            return;
        }
        if (isModelInitialized()) {
            AILogger.w(TAG, "模型已加载：GPU 层数修改需重新加载模型后生效"
                    + "（当前调用仅更新下次加载参数）");
        }
        // 限制在合理范围内
        int layers = Math.max(1, Math.min(targetLayers, 30));
        AILogger.i(TAG, "Reducing GPU layers to " + layers + " to prevent device lag");
        try {
            nativeSetGPULayers(layers);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error reducing GPU layers: " + e.getMessage(), e);
        }
    }

    /**
     * 使用性能模式（降低 GPU 层数到 15 层）
     * 适用于设备卡顿时快速降低负载
     */
    public static void enablePerformanceMode() {
        reduceGPULayers(15);
    }

    /**
     * 使用平衡模式（GPU 层数 20 层）
     * 平衡性能和速度
     */
    public static void enableBalancedMode() {
        reduceGPULayers(20);
    }

    // 内存池设置
    public static void setMemoryPoolSize(int size) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot set memory pool size");
            return;
        }
        try {
            nativeSetMemoryPoolSize(size);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error setting memory pool size: " + e.getMessage(), e);
        }
    }

    private static native void nativeSetMemoryPoolSize(int size);

    public static int getMemoryPoolSize() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get memory pool size");
            return 0;
        }
        try {
            return nativeGetMemoryPoolSize();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting memory pool size: " + e.getMessage(), e);
            return 0;
        }
    }

    private static native int nativeGetMemoryPoolSize();

    // 批处理大小设置
    public static void setBatchSize(int size) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot set batch size");
            return;
        }
        try {
            nativeSetBatchSize(size);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error setting batch size: " + e.getMessage(), e);
        }
    }

    private static native void nativeSetBatchSize(int size);

    public static int getBatchSize() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get batch size");
            return 0;
        }
        try {
            return nativeGetBatchSize();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting batch size: " + e.getMessage(), e);
            return 0;
        }
    }

    private static native int nativeGetBatchSize();

    // 线程数设置
    public static void setThreadCount(int count) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot set thread count");
            return;
        }
        try {
            nativeSetThreadCount(count);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error setting thread count: " + e.getMessage(), e);
        }
    }

    private static native void nativeSetThreadCount(int count);

    /**
     * 设置 KV cache 量化类型（仅模型加载时生效，改后需重载模型）：
     * 0 = Q8_0（KV 内存减半，精度损失极小，适合大模型/低内存设备）
     * 1 = F16（默认，精度更高，KV 内存占满）
     */
    public static void setKvCacheType(int type) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot set KV cache type");
            return;
        }
        try {
            nativeSetKvCacheType(type);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error setting KV cache type: " + e.getMessage(), e);
        }
    }

    private static native void nativeSetKvCacheType(int type);

    /**
     * 获取当前模型的 GGUF 架构（general.architecture 元数据，如 qwen2vl/gemma3v/llama3.2 等）。
     * 用于按权重元数据判断模型能力（多模态视觉等），而非模型名。
     * 返回空串表示不可用。
     */
    public static String getModelArchitecture() {
        if (!libraryLoaded) {
            return "";
        }
        try {
            return nativeGetModelArchitecture();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting model architecture: " + e.getMessage(), e);
            return "";
        }
    }

    private static native String nativeGetModelArchitecture();

    /**
     * 获取模型加载时的实际上下文大小（主 llama_context 的 n_ctx，如 7680）。
     * 与 chat context 无关，Agent 原生 FC 路径可直接使用；未加载返回 0。
     */
    public static int getModelNctx() {
        if (!libraryLoaded) {
            return 0;
        }
        try {
            return nativeGetModelNctx();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting model n_ctx: " + e.getMessage(), e);
            return 0;
        }
    }

    private static native int nativeGetModelNctx();

    /**
     * 增量生成（KV 复用）：对 appendStart 起的消息（Qwen3 格式手动渲染）解码到既有 KV cache，
     * 然后从当前位置生成——工具循环后续轮次不再全量重解码 prompt，显著提速。
     * 返回生成文本（Java 侧自行解析 tool_calls）；失败返回空串（调用方回退全量）。
     */
    public static String appendMessagesAndGenerate(String[] roles, String[] contents, int appendStart,
                                                   int maxTokens, float temperature, float topP, int topK,
                                                   boolean enableThinking) {
        if (!libraryLoaded || roles == null || contents == null) return "";
        try {
            return nativeAppendMessagesAndGenerate(roles, contents, appendStart,
                    maxTokens, temperature, topP, topK, enableThinking);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "appendMessagesAndGenerate error: " + e.getMessage(), e);
            return "";
        }
    }

    private static native String nativeAppendMessagesAndGenerate(String[] roles, String[] contents, int appendStart,
                                                                 int maxTokens, float temperature, float topP, int topK,
                                                                 boolean enableThinking);

    public static int getThreadCount() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get thread count");
            return 0;
        }
        try {
            return nativeGetThreadCount();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting thread count: " + e.getMessage(), e);
            return 0;
        }
    }

    private static native int nativeGetThreadCount();

    // 检查模型是否已初始化
    public static boolean isModelInitialized() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot check if model is initialized");
            return false;
        }
        
        long now = System.currentTimeMillis();
        boolean shouldCheck = (now - lastModelInitCheckTime) > MODEL_INIT_CHECK_INTERVAL_MS;
        
        if (shouldCheck) {
            try {
                cachedModelInitialized = nativeIsModelInitialized();
                lastModelInitCheckTime = now;
                // NPU-DIAG: 在 NPU 引擎开启时，这个 native 调用必然返回 false（llama.cpp 上下文
                // 故意不创建）。若它出现在消息路径上，调用方就会误判"没就绪"并进入等待。
                // 这里打印调用线程与堆栈，用于定位到底是谁在轮询它（实测每 2.4s 一次、共 7 次）。
                if (com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    StackTraceElement[] st = Thread.currentThread().getStackTrace();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 2; i < Math.min(st.length, 9); i++) {
                        sb.append("\n    at ").append(st[i]);
                    }
                    AILogger.w(TAG, "[NPU-DIAG] nativeIsModelInitialized 被调用（线程 "
                            + Thread.currentThread().getName() + "，NPU 模式下必为 false）" + sb);
                }
            } catch (UnsatisfiedLinkError e) {
                AILogger.e(TAG, "Error checking if model is initialized: " + e.getMessage(), e);
                cachedModelInitialized = false;
                lastModelInitCheckTime = now;
            }
        }
        
        return cachedModelInitialized;
    }

    private static native boolean nativeIsModelInitialized();

    /**
     * 推理引擎是否"有模型可用"——**NPU 感知**的替代判定。
     *
     * <p>背景（2026-10-07 实测定位）：NPU 引擎开启时 llama.cpp 上下文**故意不创建**，
     * 于是 {@link #isModelInitialized()} 永远返回 false（native 日志刷屏
     * {@code s_helperContext is nullptr, returning false}）。而不少上层逻辑把
     * "llama 模型没就绪"当成"引擎没好"→ 进入等待/轮询，白等一轮超时后才继续。
     * 实测一次首轮对话因此多花约 16 秒（8 次 x 2.4s，等于 isModelInitialized 的缓存周期），
     * 而真正的推理只用了 0.93 秒。</p>
     *
     * <p>所以判断"能否推理"时应当用它：NPU 开着就看 NPU 是否就绪，否则再看 llama。</p>
     */
    public static boolean isEngineReady() {
        try {
            if (com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                // NPU 路径：模型已加载（READY/GENERATING）即视为就绪
                return com.oilquiz.app.ai.engine.NpuLlmChat.isLoaded();
            }
        } catch (Throwable t) {
            // NpuLlmChat 不可用时退回 llama 判定，保持行为不变
        }
        return isModelInitialized();
    }

    /**
     * 检查 Native 层状态是否有效（比 isModelInitialized 更严格）
     * 同时检查模型初始化和上下文活性
     */
    public static boolean isNativeStateValid() {
        if (!libraryLoaded) {
            return false;
        }
        boolean modelOk = isModelInitialized();
        if (!modelOk) {
            return false;
        }
        // 如果 chatContextHandle 为 0，说明还没创建聊天上下文，不算无效
        if (chatContextHandle == 0) {
            return true;
        }
        // 如果已经创建了聊天上下文，检查它是否仍然有效
        return isChatContextActive();
    }

    /**
     * 记录一次 Native 恢复尝试，返回是否可以继续尝试
     */
    public static boolean canAttemptRecovery() {
        long now = System.currentTimeMillis();
        // 冷却期检查
        if (now - lastNativeRecoveryTime < RECOVERY_COOLDOWN_MS) {
            AILogger.w(TAG, "Recovery attempt blocked by cooldown");
            return false;
        }
        // 每小时最大尝试次数检查
        if (nativeRecoveryAttemptCount >= MAX_RECOVERY_ATTEMPTS_PER_HOUR) {
            long timeSinceFirstAttempt = now - lastNativeRecoveryTime;
            if (timeSinceFirstAttempt < 3600000) { // 1小时内
                AILogger.w(TAG, "Recovery attempts exhausted for this hour: " + nativeRecoveryAttemptCount);
                return false;
            }
            // 超过1小时，重置计数
            nativeRecoveryAttemptCount = 0;
        }
        return true;
    }

    /**
     * 记录恢复尝试
     */
    public static void recordRecoveryAttempt() {
        nativeRecoveryAttemptCount++;
        lastNativeRecoveryTime = System.currentTimeMillis();
        AILogger.i(TAG, "Recovery attempt recorded, total: " + nativeRecoveryAttemptCount);
    }

    /**
     * 记录恢复成功
     */
    public static void recordRecoverySuccess() {
        nativeRecoverySuccessCount++;
        AILogger.i(TAG, "Recovery success recorded, total success: " + nativeRecoverySuccessCount);
    }

    /**
     * 获取恢复尝试次数
     */
    public static int getRecoveryAttemptCount() {
        return nativeRecoveryAttemptCount;
    }

    /**
     * 获取恢复成功次数
     */
    public static int getRecoverySuccessCount() {
        return nativeRecoverySuccessCount;
    }

    /**
     * 重置恢复计数
     */
    public static void resetRecoveryCount() {
        nativeRecoveryAttemptCount = 0;
        nativeRecoverySuccessCount = 0;
        lastNativeRecoveryTime = 0;
    }

    // 错误处理
    public static String getLastError() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get last error");
            return "Library not loaded";
        }
        try {
            return nativeGetLastError();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting last error: " + e.getMessage(), e);
            return "Error getting last error";
        }
    }

    private static native String nativeGetLastError();
    
    // 获取设备信息
    public static String getDeviceInfo() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get device info");
            return "Library not loaded";
        }
        try {
            String info = nativeGetDeviceInfo();
            AILogger.i(TAG, "Device info: " + info.replace("\n", ", "));
            return info;
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting device info: " + e.getMessage(), e);
            return "Error getting device info";
        }
    }
    
    private static native String nativeGetDeviceInfo();
    
    // 获取设备数量
    public static int getDeviceCount() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get device count");
            return 0;
        }
        try {
            return nativeGetDeviceCount();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting device count: " + e.getMessage(), e);
            return 0;
        }
    }
    
    private static native int nativeGetDeviceCount();

    /**
     * ACCEL-CAPS(2026-10-09)：返回**全部** ggml 设备的能力 JSON。
     *
     * <p>每项含：backend / name / desc / type / memory_free_mb / memory_total_mb /
     * mem_type / mem_align / caps_async / caps_host_buffer / caps_from_host /
     * caps_events / caps_mmap / device_id / host_buft。</p>
     *
     * <p>走 ggml 的**设备无关**接口，所以 Vulkan、OpenCL、Hexagon(NPU)、CPU 都能报出
     * 显存与能力位。此前设备信息页只查 OpenCL（它直接调 clGetDeviceInfo），
     * 其他设备的能力项缺失。注意 Hexagon NPU 实测报 type=GPU（它作为 GPU 类型设备
     * 参与 -ngl），判断是否 NPU 要看 backend 名（"HTP"）。</p>
     *
     * @return JSON 数组字符串；库未加载或调用失败时返回空数组 "[]"
     */
    public static String getDeviceCaps() {
        if (!libraryLoaded) return "[]";
        try {
            String v = nativeGetDeviceCaps();
            return (v != null && !v.isEmpty()) ? v : "[]";
        } catch (UnsatisfiedLinkError e) {
            AILogger.w(TAG, "getDeviceCaps unavailable: " + e.getMessage());
            return "[]";
        } catch (Throwable t) {
            AILogger.w(TAG, "getDeviceCaps failed: " + t.getMessage());
            return "[]";
        }
    }

    private static native String nativeGetDeviceCaps();

    /**
     * ACCEL-DEVICES(2026-10-09)：从 GPU 驱动查询中取出 GPU 名称。
     *
     * <p>系统属性法拿不到真名（真机 ro.vendor.gpu.renderer / gpu.renderer 均为空，
     * ro.product.board 是主板代号 "canoe"），而驱动查询可返回
     * "QUALCOMM Adreno(TM) 840"。结果由调用方（DeviceDetector）缓存。</p>
     *
     * @return GPU 名称；查询失败或为空时返回空串
     */
    public static String getGPUNameFromDriver() {
        try {
            String json = detectGPUInfo();
            if (json == null || json.isEmpty() || "{}".equals(json)) return "";
            org.json.JSONObject o = new org.json.JSONObject(json);
            String name = o.optString("name", "");
            return "Unknown".equals(name) ? "" : name;
        } catch (Throwable t) {
            AILogger.w(TAG, "getGPUNameFromDriver failed: " + t.getMessage());
            return "";
        }
    }

    /**
     * ACCEL-CAPS(2026-10-09)：本机是否存在 Hexagon/HTP NPU 设备。
     * 设备信息页据此显示 NPU 行；判断依据是后端注册名而非 type（见 getDeviceCaps）。
     */
    public static boolean hasNpuDevice() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(getDeviceCaps());
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject d = arr.optJSONObject(i);
                if (d == null) continue;
                String backend = d.optString("backend", "");
                if (backend.contains("HTP") || backend.contains("Hexagon")) return true;
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "hasNpuDevice failed: " + t.getMessage());
        }
        return false;
    }
    
    // 获取空闲设备内存
    public static long getFreeDeviceMemory() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get free device memory");
            return 0;
        }
        try {
            return nativeGetFreeDeviceMemory();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting free device memory: " + e.getMessage(), e);
            return 0;
        }
    }
    
    private static native long nativeGetFreeDeviceMemory();
    
    // 获取总设备内存
    public static long getTotalDeviceMemory() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get total device memory");
            return 0;
        }
        try {
            return nativeGetTotalDeviceMemory();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting total device memory: " + e.getMessage(), e);
            return 0;
        }
    }
    
    private static native long nativeGetTotalDeviceMemory();

    // 批处理生成
    public static String[] generateBatch(String[] prompts, int maxTokens, float temperature) {
        return generateBatch(prompts, maxTokens, temperature, 0.9f, 40);
    }

    public static String[] generateBatch(String[] prompts, int maxTokens, float temperature, float topP, int topK) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot generate batch");
            return new String[0];
        }
        try {
            return nativeGenerateBatch(prompts, maxTokens, temperature, topP, topK);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error generating batch: " + e.getMessage(), e);
            return new String[0];
        }
    }

    private static native String[] nativeGenerateBatch(String[] prompts, int maxTokens, float temperature, float topP, int topK);

    // Token回调接口
    public interface TokenCallback {
        void onToken(String token);
        void onComplete(String fullText);
        void onError(String error);
        /**
         * C++ 层 common_chat_parse 解析出的工具调用（OpenAI 格式）。
         * 在 onComplete 之前调用，包含解析后的 tool_calls 列表。
         * 本地模型和在线模型使用相同的 ToolCallInfo 结构，工具调用互通。
         */
        default void onToolCalls(java.util.List<com.oilquiz.app.ai.service.OnlineInferenceService.ToolCallInfo> toolCalls) {}
        /**
         * C++ 层 common_chat_parse 解析出的推理/思考内容。
         * 在 onComplete 之前调用，包含模型输出的 reasoning_content。
         */
        default void onReasoning(String reasoning) {}
    }

    /**
     * 统一 JSON 协议回调（spec §7.1）
     * C++ 层 chatJson 通过 onJson 发送事件：token / tool_call / reasoning / complete / error（§4.2）
     */
    public interface JsonCallback {
        void onJson(String json);
        /**
         * SAFE_RUN_INFERENCE 崩溃恢复路径兼容（native 崩溃时直接调 onError 裸字符串）。
         * 默认实现转成 error JSON 事件。
         */
        default void onError(String error) {
            try {
                org.json.JSONObject j = new org.json.JSONObject();
                j.put("type", "error");
                j.put("message", error != null ? error : "Unknown native error");
                onJson(j.toString());
            } catch (Exception ignored) {
                onJson("{\"type\":\"error\",\"message\":\"Native error\"}");
            }
        }
    }
    
    // 基于 llama_tokenize 的精确 Token 计数
    public static int countTokens(String text) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot count tokens");
            return 0;
        }
        try {
            return nativeCountTokens(text);
        } catch (Throwable t) {
            AILogger.e(TAG, "Error counting tokens: " + t.getMessage(), t);
            return 0;
        }
    }

    /**
     * 从 chatJson 请求 JSON 估算 prompt token 数（供"最后一扇门"守卫使用）：
     * 统计 messages[].content + assistant.tool_calls 的 arguments + tools 定义。
     * 解析失败返回 0（守卫层有兜底放行逻辑，不会误伤）。
     */
    public static int estimateChatJsonPromptTokens(String requestJson) {
        if (requestJson == null || requestJson.isEmpty()) return 0;
        int tokens = 0;
        try {
            org.json.JSONObject req = new org.json.JSONObject(requestJson);
            org.json.JSONArray msgs = req.optJSONArray("messages");
            if (msgs != null) {
                for (int i = 0; i < msgs.length(); i++) {
                    org.json.JSONObject m = msgs.optJSONObject(i);
                    if (m == null) continue;
                    String content = m.optString("content", "");
                    if (!content.isEmpty()) tokens += countTokens(content);
                    org.json.JSONArray tcs = m.optJSONArray("tool_calls");
                    if (tcs != null) {
                        for (int j = 0; j < tcs.length(); j++) {
                            org.json.JSONObject tc = tcs.optJSONObject(j);
                            if (tc == null) continue;
                            org.json.JSONObject fn = tc.optJSONObject("function");
                            if (fn != null) tokens += countTokens(fn.optString("arguments", ""));
                        }
                    }
                }
            }
            org.json.JSONArray tools = req.optJSONArray("tools");
            if (tools != null && tools.length() > 0) tokens += countTokens(tools.toString());
        } catch (Throwable t) {
            AILogger.w(TAG, "estimateChatJsonPromptTokens failed: " + t.getMessage());
        }
        return tokens;
    }

    /** 从 chatJson 请求 JSON 提取 max_tokens（供守卫使用），缺失/非法返回 0 */
    public static int extractJsonMaxTokens(String requestJson) {
        if (requestJson == null || requestJson.isEmpty()) return 0;
        try {
            int v = new org.json.JSONObject(requestJson).optInt("max_tokens", 0);
            return v > 0 ? v : 0;
        } catch (Throwable t) {
            return 0;
        }
    }
    
    private static native int nativeCountTokens(String text);

    // ========== 组3.2：模型元数据 + 纯净推理 JNI 声明 ==========
    private static native int nativeGetMetaNCtxTrain();
    private static native int nativeGetMetaNEmbd();
    private static native int nativeGetMetaNLayer();
    private static native int nativeGetMetaNHead();
    private static native long nativeGetMetaNParams();
    private static native String nativeGetMetaModelName();
    private static native boolean nativeGetMetaValid();
    private static native int nativeRunInferenceOnce(String prompt, int contextSize, int maxTokens, StreamCallback callback);
    private static native void nativeInstallSignalHandlers();

    /** 安装 native 信号处理器，在首次 initModel 之前调用 */
    public static void installSignalHandlers() {
        nativeInstallSignalHandlers();
    }

    // ========== 组3.1：模型元数据 POJO ==========
    /** 模型元数据，与 C++ InferenceContext::ModelMeta 字段对齐 */
    public static class ModelMeta {
        public boolean valid = false;
        public int nCtxTrain = 0;      // 模型训练时的上下文长度
        public int nEmbd = 0;          // embedding 维度
        public int nLayer = 0;         // 层数
        public int nHead = 0;          // 注意力头数
        public long nParams = 0;       // 参数量
        public String modelName = "";  // 模型名称
    }
    private static volatile ModelMeta modelMeta = new ModelMeta();
    public static ModelMeta getModelMeta() { return modelMeta; }

    /**
     * 组3.3：获取安全上下文大小参考值。
     * 优先取 min(nCtxTrain, runtimeCtx)，取不到回退 runtimeCtx。
     * 不强制覆盖 contextSize，仅用于裁剪/拦截参考。
     */
    public static int getSafeContextReference(int runtimeCtx) {
        if (modelMeta != null && modelMeta.valid && modelMeta.nCtxTrain > 0) {
            return Math.min(modelMeta.nCtxTrain, runtimeCtx);
        }
        return runtimeCtx;
    }

    // ========== Native Chat Context API ==========
    private static volatile long chatContextHandle = 0;
    private static volatile int contextTotalSize = 0;
    private static volatile int contextUsedTokens = 0;

    // 活跃生成任务标志，防止并发调用native层
    private static volatile boolean hasActiveGeneration = false;

    public static long chatCreate(String modelPath, int ctxSize, int nThreads, String globalPrompt, String systemPrompt, String normalPrompt) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "chatCreate: library not loaded");
            return 0;
        }

        // ctxSize<=0：0 表示使用模型加载时的完整上下文（native 支持），
        // 不再拒绝——此前拒绝导致 tryCreateChatContextWithFallback 的 ctxSize=0 首试
        // 永远失败、回退 8192，Java 记账与实际 native 上下文不符（状态页显示 8192，
        // 实际 12288）。AIConfig 预设可能小于真实窗口，传 0 用模型 n_ctx 更准。
        if (nThreads <= 0) {
            nThreads = 4;
        }

        String gPrompt = globalPrompt != null ? globalPrompt : "";
        String sPrompt = systemPrompt != null ? systemPrompt : "";
        String nPrompt = normalPrompt != null ? normalPrompt : "";

        AILogger.i(TAG, "chatCreate: ctxSize=" + ctxSize + ", nThreads=" + nThreads +
                ", globalPromptLen=" + gPrompt.length() +
                ", systemPromptLen=" + sPrompt.length() +
                ", normalPromptLen=" + nPrompt.length());

        try {
            chatContextHandle = nativeChatCreate(modelPath, ctxSize, nThreads, gPrompt, sPrompt, nPrompt);
            if (chatContextHandle != 0) {
                // 记账用 native 实际值（ctxSize=0 时实际为模型 n_ctx，含 memoryPool 钳制）
                int actual = 0;
                try { actual = nativeGetContextSize(chatContextHandle); } catch (Throwable ignored) {}
                contextTotalSize = actual > 0 ? actual : ctxSize;
                contextUsedTokens = 0;
                AILogger.i(TAG, "chatCreate: success, handle=" + chatContextHandle
                        + ", ctxSize=" + ctxSize + ", actual=" + contextTotalSize);
            } else {
                AILogger.e(TAG, "chatCreate: nativeChatCreate returned 0");
            }
            return chatContextHandle;
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "chatCreate: UnsatisfiedLinkError: " + e.getMessage(), e);
            return 0;
        } catch (Exception e) {
            AILogger.e(TAG, "chatCreate: Exception: " + e.getMessage(), e);
            return 0;
        }
    }

    public static boolean chatUpdatePrompts(String globalPrompt, String systemPrompt, String normalPrompt) {
        if (!libraryLoaded || chatContextHandle == 0) return false;
        try {
            return nativeChatUpdatePrompts(chatContextHandle, globalPrompt, systemPrompt, normalPrompt);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error updating chat prompts: " + e.getMessage(), e);
            return false;
        }
    }

    // ===== KV 记忆引擎公开 API（长文档 KV 持久化，living-kv 方案）=====
    /** 预加载长文本到 KV 记忆（独立 seq 1）。文档会被模型"读"一遍，KV 可随后 kvMemSave 落盘。 */
    public static boolean kvMemPreload(String text) {
        if (!libraryLoaded || chatContextHandle == 0) return false;
        if (text == null || text.isEmpty()) return false;
        try { return nativeKvMemPreload(chatContextHandle, text); }
        catch (UnsatisfiedLinkError e) { AILogger.e(TAG, "kvMemPreload: " + e.getMessage(), e); return false; }
        catch (Exception e) { AILogger.e(TAG, "kvMemPreload: " + e.getMessage(), e); return false; }
    }

    /** 保存 KV 记忆状态为字节数组（写入文件后即可释放内存，需时再 restore）。 */
    public static byte[] kvMemSave() {
        if (!libraryLoaded || chatContextHandle == 0) return null;
        try { return nativeKvMemSave(chatContextHandle); }
        catch (UnsatisfiedLinkError e) { AILogger.e(TAG, "kvMemSave: " + e.getMessage(), e); return null; }
        catch (Exception e) { AILogger.e(TAG, "kvMemSave: " + e.getMessage(), e); return null; }
    }

    /** 从字节数组恢复 KV 记忆状态（模型重新"记得"文档，无需重新 prefill）。 */
    public static boolean kvMemRestore(byte[] data) {
        if (!libraryLoaded || chatContextHandle == 0 || data == null || data.length == 0) return false;
        try { return nativeKvMemRestore(chatContextHandle, data); }
        catch (UnsatisfiedLinkError e) { AILogger.e(TAG, "kvMemRestore: " + e.getMessage(), e); return false; }
        catch (Exception e) { AILogger.e(TAG, "kvMemRestore: " + e.getMessage(), e); return false; }
    }

    /** 在已恢复的 KV 记忆上提问并生成回答（不重放文档，直接基于记忆续写）。 */
    public static String kvMemAsk(String question, int maxTokens, float temperature) {
        if (!libraryLoaded || chatContextHandle == 0) return null;
        if (question == null) question = "";
        try { return nativeKvMemAsk(chatContextHandle, question, maxTokens, temperature); }
        catch (UnsatisfiedLinkError e) { AILogger.e(TAG, "kvMemAsk: " + e.getMessage(), e); return null; }
        catch (Exception e) { AILogger.e(TAG, "kvMemAsk: " + e.getMessage(), e); return null; }
    }

    public static boolean chatAddAssistantToolCall(String toolCallContent) {
        if (!libraryLoaded || chatContextHandle == 0) return false;
        if (toolCallContent == null || toolCallContent.isEmpty()) {
            AILogger.w(TAG, "chatAddAssistantToolCall: content is empty");
            return false;
        }
        try {
            nativeChatAddAssistantToolCall(chatContextHandle, toolCallContent);
            AILogger.i(TAG, "chatAddAssistantToolCall: added, len=" + toolCallContent.length());
            return true;
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "chatAddAssistantToolCall failed: " + e.getMessage(), e);
            return false;
        }
    }

    public static boolean chatAddToolResult(String toolResultContent) {
        if (!libraryLoaded || chatContextHandle == 0) return false;
        if (toolResultContent == null || toolResultContent.isEmpty()) {
            AILogger.w(TAG, "chatAddToolResult: content is empty");
            return false;
        }
        try {
            nativeChatAddToolResult(chatContextHandle, toolResultContent);
            AILogger.i(TAG, "chatAddToolResult: added, len=" + toolResultContent.length());
            return true;
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "chatAddToolResult failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 注入 assistant 消息到主对话上下文（不触发生成）。
     * 用于将外部产物（如工具结果的 AI 解读）写入本地模型的多轮历史，
     * 使后续追问能连续对话。下一轮 chatSend 会通过增量模板自动编入 KV。
     */
    public static boolean chatAddAssistant(String content) {
        if (!libraryLoaded || chatContextHandle == 0) return false;
        if (content == null || content.isEmpty()) {
            AILogger.w(TAG, "chatAddAssistant: content is empty");
            return false;
        }
        try {
            nativeChatAddAssistant(chatContextHandle, content);
            AILogger.i(TAG, "chatAddAssistant: added, len=" + content.length());
            return true;
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "chatAddAssistant failed: " + e.getMessage(), e);
            return false;
        }
    }

    public static void chatSend(String message, int maxTokens, float temperature, float topP, int topK, boolean enableThinking, TokenCallback callback) {
        if (!libraryLoaded) {
            handleValidationError(callback, "Native库未加载，无法发送消息");
            return;
        }

        if (chatContextHandle == 0) {
            handleValidationError(callback, "Chat上下文未初始化");
            return;
        }

        // 防止并发调用native层
        if (hasActiveGeneration) {
            AILogger.w(TAG, "已有活跃的生成任务，拒绝新的chatSend调用");
            handleValidationError(callback, "已有活跃的生成任务");
            return;
        }

        // 检查Native层状态
        if (!isNativeStateValid()) {
            AILogger.e(TAG, "Native状态异常，拒绝chatSend调用");
            handleValidationError(callback, "Native状态异常");
            return;
        }
        
        ValidationResult validation = validateChatSendParams(message, maxTokens, temperature, topP, topK);
        if (!validation.valid) {
            handleValidationError(callback, validation.errorMessage);
            return;
        }
        
        if (validation.warningMessage != null) {
            AILogger.w(TAG, "chatSend warning: " + validation.warningMessage);
        }
        
        String safeMessage = sanitizeMessage(message);
        if (safeMessage == null) {
            handleValidationError(callback, "消息内容格式错误");
            return;
        }

        // 设置活跃生成标志
        hasActiveGeneration = true;
        SafeTokenCallbackWrapper safeCallback = new SafeTokenCallbackWrapper(callback);
        
        try {
            nativeChatSend(chatContextHandle, safeMessage, maxTokens, temperature, topP, topK, enableThinking, safeCallback);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Native链接错误: " + e.getMessage(), e);
            safeCallback.invalidate();
            handleValidationError(callback, "Native层不可用: " + e.getMessage());
            resetNativeState();
        } catch (Exception e) {
            AILogger.e(TAG, "chatSend调用异常: " + e.getMessage(), e);
            safeCallback.invalidate();
            handleValidationError(callback, "调用异常: " + e.getMessage());
            hasActiveGeneration = false;
        }
    }

    public static void chatStop() {
        long handle = chatContextHandle;
        if (!libraryLoaded || handle == 0) return;
        try { nativeChatStop(handle); } catch (UnsatisfiedLinkError e) {}
    }

    public static void chatClear() {
        long handle = chatContextHandle;
        if (!libraryLoaded || handle == 0) return;
        try { nativeChatClear(handle); } catch (UnsatisfiedLinkError e) {}
    }

    // 活跃生成标志管理
    public static void setHasActiveGeneration(boolean active) {
        hasActiveGeneration = active;
    }

    public static boolean hasActiveGeneration() {
        return hasActiveGeneration;
    }

    // 消息内容清理
    private static String sanitizeMessage(String message) {
        if (message == null) return "";
        // 移除null字符等潜在危险字符
        String cleaned = message.replace("\u0000", "");
        // 限制长度
        if (cleaned.length() > 32768) {
            AILogger.w(TAG, "消息过长，截断到32768字符，原长度=" + cleaned.length());
            cleaned = cleaned.substring(0, 32768);
        }
        if (cleaned.isEmpty()) return null;
        return cleaned;
    }

    // 重置Native状态
    private static void resetNativeState() {
        AILogger.w(TAG, "重置native状态");
        hasActiveGeneration = false;
        if (chatContextHandle != 0) {
            chatDestroy();
        }
    }

    // 清理Native层回调引用
    public static void cleanupNativeCallback() {
        if (!libraryLoaded) return;
        try {
            nativeCleanupCallback();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error cleaning up native callback: " + e.getMessage());
        }
    }

    public static synchronized void chatDestroy() {
        if (!libraryLoaded || chatContextHandle == 0) return;
        long handle = chatContextHandle;
        chatContextHandle = 0;
        try {
            nativeChatDestroy(handle);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "chatDestroy failed: " + e.getMessage());
        }
    }

    public static String chatGetInfo() {
        if (!libraryLoaded || chatContextHandle == 0) return "No chat context";
        try { return nativeChatGetInfo(chatContextHandle); } catch (UnsatisfiedLinkError e) { return "Error"; }
    }

    public static boolean isChatContextActive() {
        return chatContextHandle != 0;
    }
    
    public static int getContextSize() {
        if (!libraryLoaded || chatContextHandle == 0) return 0;
        try { 
            int nativeValue = nativeGetContextSize(chatContextHandle);
            return nativeValue > 0 ? nativeValue : contextTotalSize;
        } catch (UnsatisfiedLinkError e) { 
            return contextTotalSize; 
        }
    }
    
    public static int getContextUsedTokens() {
        if (!libraryLoaded || chatContextHandle == 0) return 0;
        try { 
            int nativeValue = nativeGetContextUsedTokens(chatContextHandle);
            return nativeValue > 0 ? nativeValue : contextUsedTokens;
        } catch (UnsatisfiedLinkError e) { 
            return contextUsedTokens; 
        }
    }
    
    public static int getContextRemainingTokens() {
        int total = getContextSize();
        int used = getContextUsedTokens();
        return Math.max(0, total - used);
    }
    
    public static float getContextUsagePercent() {
        int total = getContextSize();
        int used = getContextUsedTokens();
        if (total <= 0) return 0.0f;
        return (float) used / total * 100;
    }

    /**
     * 检查是否有足够的上下文空间用于单次推理
     * 
     * @param promptTokens 输入 token 数
     * @param maxOutputTokens 预期输出 token 数
     * @return true 如果有足够的空间
     */
    public static boolean hasEnoughContextSpace(int promptTokens, int maxOutputTokens) {
        if (!libraryLoaded) return false;
        try {
            long handle = chatContextHandle;
            if (handle == 0) return false;
            return nativeHasEnoughContextSpace(handle, promptTokens, maxOutputTokens);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error checking context space: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 清理 KV cache 以释放上下文空间
     * 用于在上下文接近满时清理以继续推理
     */
    /**
     * 清空推理上下文（KV 缓存）。必须持写锁执行：推理进行中（generate/chatSend 持写锁）
     * 清上下文会污染正在解码的状态（竞态/逻辑锁问题），因此先等锁再清，超时则放弃本次清理。
     */
    public static void clearContextForInference() {
        if (!libraryLoaded) return;
        long lockStart = System.currentTimeMillis();
        if (!acquireInferenceWriteLock()) {
            AILogger.w(TAG, "clearContextForInference 获取推理锁超时("
                    + (System.currentTimeMillis() - lockStart) + "ms)，放弃本次清理");
            return;
        }
        try {
            long handle = chatContextHandle;
            if (handle == 0) return;
            nativeClearContextForInference(handle);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error clearing context: " + e.getMessage(), e);
        } finally {
            releaseInferenceWriteLock();
        }
    }

    private static native long nativeChatCreate(String modelPath, int ctxSize, int nThreads, String globalPrompt, String systemPrompt, String normalPrompt);
    private static native void nativeChatSend(long handle, String message, int maxTokens, float temperature, float topP, int topK, boolean enableThinking, TokenCallback callback);
    private static native void nativeChatStop(long handle);
    private static native void nativeChatClear(long handle);
    private static native void nativeChatDestroy(long handle);
    private static native String nativeChatGetInfo(long handle);
    private static native boolean nativeChatUpdatePrompts(long handle, String globalPrompt, String systemPrompt, String normalPrompt);
    private static native void nativeChatAddAssistantToolCall(long handle, String toolCallContent);
    private static native void nativeChatAddToolResult(long handle, String toolResultContent);
    private static native void nativeChatAddAssistant(long handle, String content);
    private static native int nativeHandleMemoryPressure(int level);
    private static native int nativeGetContextSize(long handle);
    private static native int nativeGetContextUsedTokens(long handle);
    private static native int nativeGetContextRemainingTokens(long handle);
    private static native boolean nativeHasEnoughContextSpace(long handle, int promptTokens, int maxOutputTokens);
    private static native void nativeClearContextForInference(long handle);
    private static native void nativeCleanupCallback();
    private static native String nativeGetThinkingTags();
    private static native String nativeGetToolCallTags();

    /**
     * 本轮生成是否由**模板 PEG 解析器**接管正文与工具调用的分离。
     *
     * <p>判据与 native 侧 chatSend 内部一致：模板声明了 parser 才为真。为真时
     * {@code common_chat_parse} 已经给出干净正文与结构化 {@code tool_calls}，
     * Java 侧**不应再按文本标签剥一遍**（那是双协议，文本匹配会误伤正常内容 ——
     * 实测模型输出的 HTML 大量丢 {@code <}）。</p>
     */
    private static native boolean nativeIsChatParserActive();

    /**
     * 查询解析器是否生效；native 不可用或未加载模型时返回 false（调用方保持旧行为）。
     */
    public static boolean isChatParserActive() {
        if (!libraryLoaded) return false;
        try {
            return nativeIsChatParserActive();
        } catch (UnsatisfiedLinkError e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
    private static native String nativeGetKvCacheStats();
    private static native String nativeGetGenPhase();

    /**
     * 获取当前推理已累积的思考内容（实时监控模型思考过程）。
     * chatJson 思考段 token 实时累积到 native 缓冲区，UI 轮询此接口展示模型在想什么。
     */
    public static String getThinkingContent() {
        if (!libraryLoaded) return "";
        try {
            return nativeGetThinkingContent();
        } catch (UnsatisfiedLinkError e) {
            AILogger.w(TAG, "nativeGetThinkingContent unavailable: " + e.getMessage());
            return "";
        }
    }

    private static native String nativeGetThinkingContent();

    /** 模板思考标签缓存：模型固定后标签不变，避免渲染/逐 token 重复走 JNI */
    private static volatile ThinkingTagConfig cachedThinkingTags = null;

    /** 工具调用标签缓存（与 cachedThinkingTags 同策略：仅缓存"已就绪"结果） */
    private static volatile ToolCallTagConfig cachedToolCallTags = null;

    /**
     * 获取当前模型 chat template 声明的思考标签 —— 标签来自模板，不硬编码。
     *
     * <p>native 在模型加载时（nativeInitModel）已从 GGUF 内置模板提取并缓存，
     * 覆盖 Qwen3（{@code <think>}）、DeepSeek（{@code [THINK]}）、
     * GPT-OSS（{@code <|channel|>analysis<|message|>}）、MiniMax、Llama3
     * 等各自不同的写法。chatJson 路径还会通过 meta 事件再下发一次。</p>
     *
     * @return 模板标签配置；模型未加载或模板未声明思考段时返回 isAvailable()==false 的配置，
     *         调用方应据此跳过思考段识别，而不是回退到硬编码
     */
    public static ThinkingTagConfig getThinkingTags() {
        if (!libraryLoaded) return ThinkingTagConfig.empty();
        // 命中缓存直接返回（仅缓存"已就绪"结果；模型未加载时返回空但下次重试，避免永久缓存空结果）
        if (cachedThinkingTags != null) return cachedThinkingTags;
        try {
            String json = nativeGetThinkingTags();
            ThinkingTagConfig cfg = json == null ? ThinkingTagConfig.empty() : ThinkingTagConfig.fromJson(json);
            if (cfg.isAvailable()) cachedThinkingTags = cfg;
            return cfg;
        } catch (UnsatisfiedLinkError e) {
            AILogger.w(TAG, "nativeGetThinkingTags unavailable: " + e.getMessage());
            return ThinkingTagConfig.empty();
        } catch (Throwable t) {
            AILogger.w(TAG, "getThinkingTags failed: " + t.getMessage());
            return ThinkingTagConfig.empty();
        }
    }

    /**
     * 获取当前模型 chat template 声明的**工具调用标签** —— 标签来自模板，不硬编码。
     *
     * <p>native 在模型加载时从模板的 {@code preserved_tokens} 推导并缓存，覆盖
     * MiniCPM5（{@code <function}/{@code </function>}、{@code <param}）、
     * Qwen（{@code <tool_call>}）等各自不同的写法。</p>
     *
     * <p>用途：流式阶段判断 token 是正文还是工具调用语法，避免标签泄漏到 UI/TTS。</p>
     *
     * @return 模板标签配置；模型未加载或模板未声明工具语法时返回 isAvailable()==false
     *         的配置，调用方**必须据此跳过吞除**，而不是回退到硬编码
     */
    public static ToolCallTagConfig getToolCallTags() {
        if (!libraryLoaded) return ToolCallTagConfig.empty();
        if (cachedToolCallTags != null) return cachedToolCallTags;
        try {
            String json = nativeGetToolCallTags();
            ToolCallTagConfig cfg = json == null ? ToolCallTagConfig.empty()
                    : ToolCallTagConfig.fromJson(json);
            if (cfg.isAvailable()) cachedToolCallTags = cfg;
            return cfg;
        } catch (UnsatisfiedLinkError e) {
            AILogger.w(TAG, "nativeGetToolCallTags unavailable: " + e.getMessage());
            return ToolCallTagConfig.empty();
        } catch (Throwable t) {
            AILogger.w(TAG, "getToolCallTags failed: " + t.getMessage());
            return ToolCallTagConfig.empty();
        }
    }

    /**
     * 获取 KV 增量缓存状态与上下文占用监控（native AgentKvCache 统计）。
     *
     * <p>返回 JSON：strategy(INCREMENTAL/PARTIAL/FULL)、matched_len、cached_npast、
     * ctx_size、ctx_usage_pct（上下文占用率）、plans/inc/part/full（策略分布）、
     * hit_rate_pct（增量命中率）、valid、full_reason（全量原因）。</p>
     *
     * <p>用于诊断"为什么没吃到 KV 增量缓存"（普通对话每轮新 prompt 多走 FULL）
     * 与监控长对话上下文占用（KV 逼近 n_ctx 时的裁剪决策依据）。</p>
     *
     * @return JSON 字符串；模型未加载或 JNI 不可用时返回空串
     */
    public static String getKvCacheStats() {
        if (!libraryLoaded) return "";
        try {
            return nativeGetKvCacheStats();
        } catch (UnsatisfiedLinkError e) {
            AILogger.w(TAG, "nativeGetKvCacheStats unavailable: " + e.getMessage());
            return "";
        } catch (Throwable t) {
            AILogger.w(TAG, "getKvCacheStats failed: " + t.getMessage());
            return "";
        }
    }

    /**
     * 获取 native 生成流程状态机的当前阶段（GenPhase + StopCause）。
     *
     * <p>返回 JSON：{"phase":"THINKING","stop_cause":"EOS","running":true}。
     * phase 取值 IDLE/PREPROCESS/THINKING/GENERATING/COMPLETE/ERROR，
     * 用于对话界面顶部实时展示推理处于思考段还是正文生成、以及完成原因。</p>
     *
     * @return JSON 字符串；模型未加载或 JNI 不可用时返回空串
     */
    public static String getGenPhase() {
        if (!libraryLoaded) return "";
        try {
            return nativeGetGenPhase();
        } catch (UnsatisfiedLinkError e) {
            AILogger.w(TAG, "nativeGetGenPhase unavailable: " + e.getMessage());
            return "";
        } catch (Throwable t) {
            AILogger.w(TAG, "getGenPhase failed: " + t.getMessage());
            return "";
        }
    }

    /**
     * 模型（重新）加载后调用，清除标签缓存，下次 getThinkingTags 重新读取。
     */
    public static void invalidateThinkingTagsCache() {
        cachedThinkingTags = null;
        cachedToolCallTags = null;
    }

    public static int handleMemoryPressure(int level) {
        if (!libraryLoaded) return 0;
        try {
            return nativeHandleMemoryPressure(level);
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
    }
    
    public static boolean isOpenCLLoaded() {
        if (!libraryLoaded) {
            AILogger.w(TAG, "Library not loaded, cannot check OpenCL status");
            return false;
        }
        try {
            return nativeIsOpenCLLoaded();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error checking OpenCL loaded: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 更新 native 层的 OpenCL 加载状态（System.load 加载后调用）
     */
    public static void setOpenCLLoaded(boolean loaded) {
        if (!libraryLoaded) {
            return;
        }
        try {
            nativeSetOpenCLLoaded(loaded);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error setting OpenCL loaded: " + e.getMessage(), e);
        }
    }

    private static native void nativeSetOpenCLLoaded(boolean loaded);

    public static boolean isGPUWorking() {
        if (!libraryLoaded) {
            AILogger.w(TAG, "Library not loaded, cannot check GPU status");
            return false;
        }
        try {
            return nativeIsGPUWorking();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error checking GPU working: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取 GPU 单次最大分配大小（字节）
     * 这是 OpenCL 的 CL_DEVICE_MAX_MEM_ALLOC_SIZE 值
     * 移动 GPU 通常是总显存的 1/4
     */
    public static long getGpuMaxMemAllocSize() {
        if (!libraryLoaded) return 0;
        try {
            return nativeGetGpuMaxMemAllocSize();
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
    }

    /**
     * 获取 GPU 单次最大分配大小（MB）
     */
    public static long getGpuMaxMemAllocSizeMB() {
        return getGpuMaxMemAllocSize() / (1024 * 1024);
    }

    private static native long nativeGetGpuMaxMemAllocSize();
    
    public static String getOpenCLInfo() {
        if (!libraryLoaded) {
            AILogger.w(TAG, "Library not loaded, cannot get OpenCL info");
            return "Library not loaded";
        }
        try {
            return nativeGetOpenCLInfo();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting OpenCL info: " + e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }
    
    public static String getFullDeviceInfoSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== AI 加速信息\n");
        sb.append("================\n");
        sb.append("Native库: ").append(libraryLoaded ? "已加载" : "未加载").append("\n");
        sb.append("模型初始化: ").append(isModelInitialized() ? "是" : "否").append("\n");
        sb.append("\n=== OpenCL/GPU状态\n");
        sb.append("================\n");
        
        if (libraryLoaded) {
            sb.append("OpenCL库: ").append(isOpenCLLoaded() ? "已加载" : "未加载").append("\n");
            sb.append("GPU加速: ").append(isGPUWorking() ? "启用" : "未启用").append("\n");
            sb.append("\n=== 设备信息\n");
            sb.append("================\n");
            try {
                String deviceInfo = getDeviceInfo();
                sb.append(deviceInfo);
            } catch (Exception e) {
                sb.append("获取设备信息失败: ").append(e.getMessage());
            }
        } else {
            sb.append("Native库未加载，无法获取GPU状态\n");
        }
        
        return sb.toString();
    }
    
    private static native boolean nativeIsOpenCLLoaded();
    private static native boolean nativeIsGPUWorking();
    private static native String nativeGetOpenCLInfo();
    private static native String nativeDetectGPUInfo();
    
    public static String detectGPUInfo() {
        if (!libraryLoaded) {
            AILogger.w(TAG, "Library not loaded, cannot detect GPU info");
            return "{}";
        }
        try {
            String info = nativeDetectGPUInfo();
            AILogger.i(TAG, "GPU info detected: " + info);
            return info;
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error detecting GPU info: " + e.getMessage(), e);
            return "{}";
        }
    }
    
    public static class ValidationResult {
        public final boolean valid;
        public final String errorMessage;
        public final String warningMessage;
        
        public ValidationResult(boolean valid, String errorMessage, String warningMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.warningMessage = warningMessage;
        }
        
        public static ValidationResult ok() {
            return new ValidationResult(true, null, null);
        }
        
        public static ValidationResult error(String message) {
            return new ValidationResult(false, message, null);
        }
        
        public static ValidationResult warning(String message) {
            return new ValidationResult(true, null, message);
        }
    }
    
    private static ValidationResult validateChatSendParams(String message, int maxTokens, 
            float temperature, float topP, int topK) {
        if (message == null) {
            return ValidationResult.error("消息内容不能为null");
        }
        
        if (message.length() > 32768) {
            return ValidationResult.error("消息内容过长: " + message.length() + "字符，最大支持32768");
        }
        
        if (maxTokens <= 0) {
            return ValidationResult.error("maxTokens必须大于0: " + maxTokens);
        }
        
        if (maxTokens > 32768) {
            return ValidationResult.warning("maxTokens可能过大: " + maxTokens + "，建议不超过4096");
        }
        
        if (temperature < 0.0f || temperature > 2.0f) {
            return ValidationResult.error("temperature必须在0.0-2.0之间: " + temperature);
        }
        
        if (topP < 0.0f || topP > 1.0f) {
            return ValidationResult.error("topP必须在0.0-1.0之间: " + topP);
        }
        
        if (topK < 0 || topK > 1000) {
            return ValidationResult.error("topK必须在0-1000之间: " + topK);
        }
        
        return ValidationResult.ok();
    }
    
    private static ValidationResult validateGenerateParams(String prompt, int maxTokens, 
            float temperature, float topP, int topK) {
        if (prompt == null) {
            return ValidationResult.error("prompt不能为null");
        }
        
        if (prompt.length() > 32768) {
            return ValidationResult.error("prompt过长: " + prompt.length() + "字符，最大支持32768");
        }
        
        if (maxTokens <= 0) {
            return ValidationResult.error("maxTokens必须大于0: " + maxTokens);
        }
        
        if (temperature < 0.0f || temperature > 2.0f) {
            return ValidationResult.error("temperature必须在0.0-2.0之间: " + temperature);
        }
        
        if (topP < 0.0f || topP > 1.0f) {
            return ValidationResult.error("topP必须在0.0-1.0之间: " + topP);
        }
        
        if (topK < 0 || topK > 1000) {
            return ValidationResult.error("topK必须在0-1000之间: " + topK);
        }
        
        return ValidationResult.ok();
    }
    
    private static ValidationResult validateInitModelParams(String modelPath, int nCtx, int nThreads) {
        if (modelPath == null || modelPath.isEmpty()) {
            return ValidationResult.error("模型路径不能为空");
        }
        
        // 上限放宽到 65536：32k 上下文（Qwen3-4B 窗口）+ 预留余量；
        // native 层仍按模型 n_ctx_train 与内存池预算钳制，不会真正越界
        if (nCtx <= 0 || nCtx > 65536) {
            return ValidationResult.error("nCtx必须在1-65536之间: " + nCtx);
        }
        
        if (nThreads <= 0 || nThreads > 32) {
            return ValidationResult.error("nThreads必须在1-32之间: " + nThreads);
        }
        
        return ValidationResult.ok();
    }
    
    private static void handleValidationError(TokenCallback callback, String errorMessage) {
        AILogger.e(TAG, "参数验证失败: " + errorMessage);
        if (callback != null) {
            try {
                callback.onError("参数错误: " + errorMessage);
            } catch (Exception e) {
                AILogger.e(TAG, "Error calling onError callback: " + e.getMessage());
            }
        }
    }

    /**
     * 安全的Token回调包装器，防止回调中异常导致native层崩溃
     */
    private static class SafeTokenCallbackWrapper implements TokenCallback {
        private final TokenCallback wrapped;
        private volatile boolean isActive = true;

        SafeTokenCallbackWrapper(TokenCallback wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public void onToken(String token) {
            if (!isActive || wrapped == null) return;
            try {
                wrapped.onToken(token);
            } catch (Exception e) {
                AILogger.e(TAG, "Token回调异常: " + e.getMessage());
            }
        }

        @Override
        public void onComplete(String fullText) {
            if (!isActive || wrapped == null) return;
            isActive = false;
            hasActiveGeneration = false;
            try {
                wrapped.onComplete(fullText);
            } catch (Exception e) {
                AILogger.e(TAG, "Complete回调异常: " + e.getMessage());
            }
        }

        @Override
        public void onError(String error) {
            if (!isActive || wrapped == null) return;
            isActive = false;
            hasActiveGeneration = false;
            try {
                wrapped.onError(error);
            } catch (Exception e) {
                AILogger.e(TAG, "Error回调异常: " + e.getMessage());
            }
        }

        void invalidate() {
            isActive = false;
        }
    }
}
