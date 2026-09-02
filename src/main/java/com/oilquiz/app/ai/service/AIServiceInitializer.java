package com.oilquiz.app.ai.service;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.oilquiz.app.ai.model.ModelDownloadManager;
import com.oilquiz.app.ai.model.ModelManager;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * AI 服务一键初始化配置类
 *
 * 场景：当本地模型与在线模型均未配置时，用户在入口点击即可一键完成：
 *   1. 检测配置状态（{@link #needsInitialization}）
 *   2. 自动下载默认预置模型（多模态模型会连带下载 mmproj 投影文件）
 *   3. 配置为当前模型（写入模型状态缓存）
 *   4. 初始化并加载本地模型服务
 *   5. 全程进度 / 结果回调
 *
 * 健壮性设计：
 *   - 模型文件完整性校验（存在但大小不足视为损坏，自动重新下载）
 *   - 全局初始化互斥（防并发下载 / 重复初始化）
 *   - 主模型缺失或损坏 → 自动下载；mmproj 缺失 → 自动补充下载
 *   - 所有回调统一派发到主线程
 */
public final class AIServiceInitializer {

    private static final String TAG = "AIServiceInitializer";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long MMPROJ_WAIT_TIMEOUT_MS = 120_000L;
    private static final long MMPROJ_WAIT_STEP_MS = 1_000L;
    private static final long INIT_TIMEOUT_MS = 15 * 60 * 1000L; // 初始化总超时 15 分钟，超时强制复位互斥
    /** 文件完整性阈值：实际大小 >= 预期大小 * 该比例 视为完整 */
    private static final double INTEGRITY_THRESHOLD = 0.90;

    /** 默认预置模型：多模态 Agent（视觉理解 + 思考链 + 原生工具调用） */
    public static final String DEFAULT_MODEL_ID = "qwen3-vl-2b-thinking";

    /** 全局初始化互斥锁 */
    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initializing = false;
    // 两步分离：下载完成待用户触发的待加载模型信息
    private static volatile String pendingModelFile = null;
    private static volatile String pendingMmprojFile = null;
    private static volatile long initStartTime = 0L;

    /** 初始化回调（回调统一在调用线程派发；若需 UI 请自行切主线程） */
    public interface InitCallback {
        /**
         * @param message 进度文案
         * @param percent 0-100；-1 表示进度不确定（如 mmproj 下载 / 加载中）
         */
        void onProgress(String message, int percent);

        /**
         * 辅助文件（如 mmproj 视觉投影）独立下载进度。主模型与辅助文件各自独立进度，
         * 避免在同一进度上互相覆盖造成回退。
         * @param label 辅助文件标签（如 "视觉模块"）
         * @param percent 0-100；-1 表示进度不确定
         * @param downloadedMB 已下载 MB
         * @param totalMB 总 MB
         */
        default void onSecondaryProgress(String label, int percent, long downloadedMB, long totalMB) {
        }

        /**
         * 初始化完成
         * @param modelName 已配置并加载的模型文件名
         * @param downloaded true = 本次自动下载完成；false = 模型原本已存在直接加载
         */
        void onComplete(String modelName, boolean downloaded);

        /** 初始化失败 */
        void onError(String error);

        /**
         * 下载完成但尚未加载（两步分离：大模型下载完不自动加载，避免卡 UI）。
         * UI 应显示「加载模型」按钮，点击后调用 {@link #loadDownloadedModel(Context, String, InitCallback)}。
         * @param modelName 模型显示名
         * @param modelFileName 已下载的模型文件名
         */
        default void onDownloadReady(String modelName, String modelFileName) {
        }
    }

    private AIServiceInitializer() {
    }

    // ==================== 状态检测 ====================

    /**
     * 是否需要一键初始化：本地模型与在线模型均未配置。
     * 在线模型已配置，或本地模型已配置且模型文件完整存在 → 无需初始化。
     */
    public static boolean needsInitialization(Context context) {
        try {
            Context app = context != null ? context.getApplicationContext() : null;
            if (app == null) return true;

            // 在线模型已配置 → 无需初始化
            OnlineModelManager online = OnlineModelManager.getInstance(app);
            if (online != null && online.hasModels()) {
                return false;
            }

            // 本地模型已配置且模型文件完整存在 → 无需初始化
            AIService aiService = AIService.getInstance(app);
            if (aiService != null) {
                String name = aiService.getCurrentModelName();
                // 排除 mmproj/CLIP 等投影文件被误设为当前模型（无法作为主模型加载）
                if (name != null && !name.isEmpty()
                        && !isProjectionFileName(name)
                        && modelFileExists(app, name, expectedBytesForModelName(app, name))) {
                    return false;
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "needsInitialization check failed: " + e.getMessage());
        }
        return true;
    }

    /** 是否已配置（本地或在线任一可用） */
    public static boolean isConfigured(Context context) {
        return !needsInitialization(context);
    }

    /** 当前是否正在进行初始化（供 UI 层展示状态 / 防止重复点击） */
    public static boolean isInitializing() {
        return initializing;
    }

    // ==================== 一键初始化 ====================

    /** 一键初始化（默认预置模型） */
    public static void start(Context context, InitCallback callback) {
        start(context, DEFAULT_MODEL_ID, callback);
    }

    /** 一键初始化（指定预置模型 id） */
    public static void start(final Context context, final String modelId, final InitCallback callback) {
        final Context app = context != null ? context.getApplicationContext() : null;
        if (app == null) {
            notifyError(callback, "上下文无效");
            return;
        }

        // 全局互斥：已有初始化进行中则拒绝新的，防止并发下载 / 重复初始化
        synchronized (INIT_LOCK) {
            if (initializing) {
                // 若上一次初始化已远超超时仍卡住（如网络挂起），强制复位允许重试
                if (initStartTime > 0 && System.currentTimeMillis() - initStartTime > INIT_TIMEOUT_MS) {
                    AILogger.w(TAG, "上一次初始化超时，强制复位互斥锁");
                    initializing = false;
                    initStartTime = 0L;
                } else {
                    AILogger.i(TAG, "已有初始化任务进行中，拒绝重复启动");
                    notifyError(callback, "已有初始化任务进行中，请稍候");
                    return;
                }
            }
            initializing = true;
            initStartTime = System.currentTimeMillis();
            // 清理上一步骤分离的待加载残留（若上次下载完未点「加载模型」就退出，避免串到本次初始化）
            pendingModelFile = null;
            pendingMmprojFile = null;
        }

        // 取消进行中的模型预加载，避免与一键下载/加载抢同一模型槽（一边下载一边加载）。
        // 说明：AIProcessingService 是推理通道（非自动初始化源，onCreate 不加载模型），此处不停止它；
        // App 启动自动预加载（preloadAIServiceInternal）已通过 isInitializing() 检查在初始化期间跳过。
        // 注意：只发 CANCEL_PRELOAD 指令，不做 stopService+重建——ModelPreloadService 的 native
        // initModel 是阻塞调用，shutdownNow 无法真正中断；stopService 重建反而引入并发初始化风险。
        try {
            app.startService(new Intent(app, ModelPreloadService.class).setAction("CANCEL_PRELOAD"));
        } catch (Exception e) {
            AILogger.w(TAG, "cancel background preload failed: " + e.getMessage());
        }

        final ModelDownloadManager downloadManager = ModelDownloadManager.getInstance(app);
        final ModelDownloadManager.ModelPresetInfo preset = findPreset(downloadManager, modelId);
        if (preset == null) {
            finishInit(callback, null, false, "未找到预置模型: " + modelId);
            return;
        }
        final String modelFileName = fileNameFromUrl(preset.downloadUrl);
        final String mmprojFileName = (preset.mmprojUrl != null && !preset.mmprojUrl.isEmpty())
                ? fileNameFromUrl(preset.mmprojUrl) : null;
        final long modelExpectedBytes = preset.sizeMB > 0 ? preset.sizeMB * 1024L * 1024L : -1L;
        final long mmprojExpectedBytes = preset.mmprojSizeMB > 0 ? preset.mmprojSizeMB * 1024L * 1024L : -1L;

        AILogger.i(TAG, "start: model=" + modelFileName + " mmproj=" + mmprojFileName
                + " modelMB=" + preset.sizeMB + " mmprojMB=" + preset.mmprojSizeMB);

        // 主模型已存在（且完整） → 检查 mmproj 是否齐备
        if (modelFileExists(app, modelFileName, modelExpectedBytes)) {
            // 多模态模型但 mmproj 缺失 → 自动补充下载视觉投影文件
            if (mmprojFileName != null && !modelFileExists(app, mmprojFileName, mmprojExpectedBytes)) {
                AILogger.i(TAG, "主模型已存在，mmproj 缺失，补充下载: " + mmprojFileName);
                notifyProgress(callback, "模型已就绪，正在补充下载视觉模块…", 100);
                // 补充下载同样带期望大小与 SHA-256 哈希校验（与 downloadPresetModel 一致），
                // 避免下载到错误版本/半截文件被误判为已就绪。
                downloadManager.downloadFromCustomUrl(modelId + "_mmproj", preset.mmprojUrl,
                        mmprojExpectedBytes, preset.mmprojSha256,
                        preset.backupMmprojUrl, preset.backupMmprojSha256,
                        new ModelDownloadManager.DownloadCallback() {
                            @Override
                            public void onProgress(String id, int progress, long downloadedMB, long totalMB) {
                                notifySecondaryProgress(callback, "视觉模块", progress, downloadedMB, totalMB);
                            }

                            @Override
                            public void onSpeedUpdate(String id, long speedBps, long etaSeconds) {
                                notifyProgress(callback, "正在补充下载视觉模块…", -1);
                            }

                            @Override
                            public void onComplete(String id, String filePath) {
                                AILogger.i(TAG, "mmproj 下载完成: " + filePath);
                                // 两步分离：下载完成不自动加载（大模型加载卡 UI），等用户点「加载模型」
                                new Thread(() -> {
                                    waitForFiles(app, modelFileName, mmprojFileName,
                                            modelExpectedBytes, mmprojExpectedBytes);
                                    pendingModelFile = modelFileName;
                                    pendingMmprojFile = mmprojFileName;
                                    notifyProgress(callback, "下载完成，点击「加载模型」开始使用", 100);
                                    notifyDownloadReady(callback, preset.name, modelFileName);
                                }, "ai-init-mmproj-ready").start();
                            }

                            @Override
                            public void onError(String id, String error) {
                                AILogger.w(TAG, "mmproj 下载失败: " + error);
                                // 降级：主模型可文本对话，视觉不可用 → 仍走两步分离等用户点加载
                                pendingModelFile = modelFileName;
                                pendingMmprojFile = null;
                                notifyProgress(callback, "视觉模块下载失败，文本对话可用。点击「加载模型」", 100);
                                notifyDownloadReady(callback, preset.name, modelFileName);
                            }

                            @Override
                            public void onPaused(String id) { notifyProgress(callback, "视觉模块下载暂停", -1); }
                            @Override
                            public void onCancelled(String id) {
                                // 取消同样降级（主模型可用），等用户点加载
                                pendingModelFile = modelFileName;
                                pendingMmprojFile = null;
                                notifyProgress(callback, "视觉模块下载已取消，文本对话可用。点击「加载模型」", 100);
                                notifyDownloadReady(callback, preset.name, modelFileName);
                            }
                            @Override public void onResumed(String id) { notifyProgress(callback, "继续补充下载…", -1); }
                        });
                return;
            }

            // 模型与 mmproj 齐备 → 直接配置加载
            AILogger.i(TAG, "模型已存在，直接加载: " + modelFileName);
            notifyProgress(callback, "模型已就绪，正在加载 " + preset.name + " …", 100);
            new Thread(() -> {
                boolean ok = configureAndLoad(app, modelFileName, mmprojFileName);
                if (ok) {
                    finishInit(callback, modelFileName, false, null);
                } else {
                    finishInit(callback, null, false, "模型加载失败，请重试或检查模型文件");
                }
            }, "ai-init-load").start();
            return;
        }

        // 主模型不存在或已损坏 → 清理损坏文件后自动下载（mmproj 由 downloadPresetModel 联动下载）
        deleteCorruptedFile(app, modelFileName);
        AILogger.i(TAG, "开始一键初始化：下载 " + preset.id + " → " + modelFileName);
        notifyProgress(callback, "开始下载 " + preset.name + "（" + preset.sizeMB + " MB）…", 0);
        downloadManager.downloadPresetModel(modelId, preset, new ModelDownloadManager.DownloadCallback() {
            // 主模型与 mmproj 联动下载共用本回调；mmproj 的 id 以 "_mmproj" 结尾
            private final java.util.concurrent.atomic.AtomicBoolean loadStarted =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            private boolean isMmproj(String id) {
                return id != null && id.endsWith("_mmproj");
            }

            @Override
            public void onProgress(String id, int progress, long downloadedMB, long totalMB) {
                if (isMmproj(id)) {
                    // mmproj 视觉模块独立进度条显示，不影响主模型进度环
                    notifySecondaryProgress(callback, "视觉模块", progress, downloadedMB, totalMB);
                } else {
                    notifyProgress(callback,
                            "正在下载 " + preset.name + "（" + downloadedMB + "/" + totalMB + " MB）", progress);
                }
            }

            @Override
            public void onSpeedUpdate(String id, long speedBps, long etaSeconds) {
                if (isMmproj(id)) {
                    notifySecondaryProgress(callback, "视觉模块", -1, 0, 0);
                    return;
                }
                String msg = "正在下载 " + preset.name + "（" + formatSpeed(speedBps);
                if (etaSeconds > 0) {
                    msg += "，预计还需 " + (Math.max(1, (etaSeconds + 59) / 60)) + " 分钟";
                }
                msg += "）";
                notifyProgress(callback, msg, -1);
            }

            @Override
            public void onComplete(String id, String filePath) {
                // 主模型与 mmproj 任一完成都会回调；仅首次触发"等待齐备→通知下载完成"，防止并发
                if (loadStarted.compareAndSet(false, true)) {
                    AILogger.i(TAG, "下载完成回调 id=" + id + " path=" + filePath);
                    new Thread(() -> {
                        boolean ready = waitForFiles(app, modelFileName, mmprojFileName,
                                modelExpectedBytes, mmprojExpectedBytes);
                        if (!ready) {
                            AILogger.w(TAG, "下载完成但文件未就绪（可能超时），仍通知用户尝试加载");
                        }
                        // 两步分离：下载完成不自动加载（大模型加载卡 UI），等待用户点「加载模型」
                        pendingModelFile = modelFileName;
                        pendingMmprojFile = mmprojFileName;
                        notifyProgress(callback, "下载完成，点击「加载模型」开始使用", 100);
                        notifyDownloadReady(callback, preset.name, modelFileName);
                    }, "ai-init-download").start();
                }
            }

            @Override
            public void onError(String id, String error) {
                if (isMmproj(id)) {
                    // mmproj 失败可降级（文本对话可用），主模型已就绪 → 通知下载完成，等用户加载
                    AILogger.w(TAG, "mmproj 下载失败，降级提示: " + error);
                    if (loadStarted.compareAndSet(false, true)) {
                        new Thread(() -> {
                            waitForFiles(app, modelFileName, null, modelExpectedBytes, 0);
                            pendingModelFile = modelFileName;
                            pendingMmprojFile = null; // mmproj 失败/缺失，不标记，加载时 AIService 自动跳过视觉
                            notifyProgress(callback, "视觉模块下载失败，文本对话可用。点击「加载模型」", 100);
                            notifyDownloadReady(callback, preset.name, modelFileName);
                        }, "ai-init-mmproj-fallback").start();
                    }
                } else {
                    finishInit(callback, null, false, "下载失败：" + error);
                }
            }

            @Override
            public void onPaused(String id) {
                notifyProgress(callback, "下载已暂停", -1);
            }

            @Override
            public void onCancelled(String id) {
                finishInit(callback, null, false, "下载已取消");
            }

            @Override
            public void onResumed(String id) {
                notifyProgress(callback, "继续下载…", -1);
            }
        });
    }

    // ==================== 内部实现 ====================

    /** 初始化结束：复位互斥锁并派发结果 */
    private static void finishInit(InitCallback callback, String modelName,
                                   boolean downloaded, String error) {
        synchronized (INIT_LOCK) {
            initializing = false;
            initStartTime = 0L;
        }
        if (error != null) {
            notifyError(callback, error);
        } else {
            notifyComplete(callback, modelName, downloaded);
        }
    }

    /**
     * 加载已下载完成的模型（两步分离第二步）。用户在收到 {@link InitCallback#onDownloadReady} 后调用。
     * 从待加载信息（或 preset）解析模型文件并配置加载。
     */
    public static void loadDownloadedModel(final Context context, final String modelId, final InitCallback callback) {
        final Context app = context != null ? context.getApplicationContext() : null;
        if (app == null) {
            notifyError(callback, "上下文无效");
            return;
        }
        final String modelFile = pendingModelFile;
        final String mmprojFile = pendingMmprojFile;
        // 待加载信息缺失时尝试按 preset 重新解析
        String resolvedModel = modelFile;
        String resolvedMmproj = mmprojFile;
        if (resolvedModel == null && modelId != null) {
            try {
                ModelDownloadManager manager = ModelDownloadManager.getInstance(app);
                ModelDownloadManager.ModelPresetInfo preset = findPreset(manager, modelId);
                if (preset != null) {
                    resolvedModel = fileNameFromUrl(preset.downloadUrl);
                    resolvedMmproj = (preset.mmprojUrl != null && !preset.mmprojUrl.isEmpty())
                            ? fileNameFromUrl(preset.mmprojUrl) : null;
                }
            } catch (Exception e) {
                AILogger.w(TAG, "loadDownloadedModel preset resolve failed: " + e.getMessage());
            }
        }
        if (resolvedModel == null) {
            notifyError(callback, "未找到已下载的模型文件，请重新初始化");
            return;
        }
        // 加载前重新校验模型文件完整性（下载完成后文件可能被外部改动/损坏，避免加载时报错无明确提示）
        if (!modelFileExists(app, resolvedModel, expectedBytesForModelName(app, resolvedModel))) {
            AILogger.w(TAG, "loadDownloadedModel: 模型文件不完整: " + resolvedModel);
            notifyError(callback, "模型文件不完整，请重新初始化");
            return;
        }
        final String fModel = resolvedModel;
        final String fMmproj = resolvedMmproj;
        AILogger.i(TAG, "loadDownloadedModel: " + fModel + " mmproj=" + fMmproj);
        notifyProgress(callback, "正在加载模型 " + fModel + " …", -1);
        new Thread(() -> {
            boolean ok = configureAndLoad(app, fModel, fMmproj);
            if (ok) {
                pendingModelFile = null;
                pendingMmprojFile = null;
                finishInit(callback, fModel, true, null);
            } else {
                finishInit(callback, null, false, "模型加载失败，请重试或检查模型文件");
            }
        }, "ai-init-load").start();
    }

    /** 配置为当前模型并加载本地模型服务（需在后台线程调用） */
    private static boolean configureAndLoad(Context app, String modelFileName, String mmprojFileName) {
        try {
            AIService aiService = AIService.getInstance(app);
            if (aiService == null) {
                AILogger.e(TAG, "AIService 获取失败");
                return false;
            }
            // 说明：mmprojFileName 仅用于语义确认——AIService.switchModel → loadModelLocked 内部会按
            // GGUF 架构自动加载同目录 mmproj（autoLoadMultimodalIfAvailable：预设精确配对+逐个尝试）。
            // 此处无需显式传入；mmproj 缺失/失败时主模型仍可加载（文本对话可用），视觉功能自动降级。
            if (mmprojFileName != null && !mmprojFileName.isEmpty()) {
                AILogger.i(TAG, "configureAndLoad: mmproj 由 AIService 自动挂载, 期望=" + mmprojFileName);
            }
            AILogger.i(TAG, "配置当前模型并加载: " + modelFileName);
            boolean ok = aiService.switchModel(modelFileName);
            AILogger.i(TAG, "switchModel(" + modelFileName + ") = " + ok);
            return ok;
        } catch (Exception e) {
            AILogger.e(TAG, "configureAndLoad failed", e);
            return false;
        }
    }

    /** 等待主模型 / mmproj 文件就绪（限时轮询；含完整性校验）。就绪返回 true；超时返回 false 并打日志。 */
    private static boolean waitForFiles(Context app, String modelFileName, String mmprojFileName,
                                        long modelExpectedBytes, long mmprojExpectedBytes) {
        long deadline = System.currentTimeMillis() + MMPROJ_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (modelFileExistsInternal(app, modelFileName, modelExpectedBytes)
                    && (mmprojFileName == null || modelFileExistsInternal(app, mmprojFileName, mmprojExpectedBytes))) {
                return true;
            }
            try {
                Thread.sleep(MMPROJ_WAIT_STEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                AILogger.w(TAG, "waitForFiles 被中断");
                return false;
            }
        }
        AILogger.w(TAG, "waitForFiles 超时: model=" + modelFileName
                + " mmproj=" + mmprojFileName);
        return false;
    }

    /** 查找预置模型 */
    private static ModelDownloadManager.ModelPresetInfo findPreset(ModelDownloadManager manager, String modelId) {
        try {
            List<ModelDownloadManager.ModelPresetInfo> list = manager.getPresetDomesticModels();
            if (list != null) {
                for (ModelDownloadManager.ModelPresetInfo p : list) {
                    if (p != null && modelId.equals(p.id)) {
                        return p;
                    }
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "findPreset failed", e);
        }
        return null;
    }

    /**
     * 按模型文件名查找 preset 的期望字节数（用于完整性校验）。
     * 命中 preset 返回 sizeMB*1024*1024；未命中返回 -1（不校验大小，仅检查存在）。
     */
    private static long expectedBytesForModelName(Context app, String name) {
        if (name == null || name.isEmpty()) return -1L;
        try {
            ModelDownloadManager manager = ModelDownloadManager.getInstance(app);
            java.util.List<ModelDownloadManager.ModelPresetInfo> list = manager.getPresetDomesticModels();
            if (list != null) {
                for (ModelDownloadManager.ModelPresetInfo p : list) {
                    if (p == null || p.downloadUrl == null) continue;
                    String presetFile = fileNameFromUrl(p.downloadUrl);
                    if (name.equals(presetFile) && p.sizeMB > 0) {
                        return p.sizeMB * 1024L * 1024L;
                    }
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "expectedBytesForModelName failed: " + e.getMessage());
        }
        return -1L;
    }

    /** 模型文件是否完整存在（内部 ai_models / 自定义目录 / files 根；expectedBytes>0 时校验大小） */
    static boolean modelFileExists(Context app, String fileName, long expectedBytes) {
        if (fileName == null || fileName.isEmpty()) return false;
        String expectedSha = expectedShaForFileName(app, fileName);
        try {
            // 内部 ai_models（加载模型的实际目录）
            File internal = new File(app.getFilesDir(), "ai_models");
            File f1 = new File(internal, fileName);
            if (f1.exists() && f1.isFile()) {
                if (!ModelDownloadManager.verifyComplete(f1, expectedBytes, expectedSha)) {
                    AILogger.w(TAG, "模型文件不完整(内部): " + fileName + " size=" + f1.length()
                            + " expected=" + expectedBytes);
                    return false;
                }
                return true;
            }

            // 自定义模型目录
            ModelManager modelManager = new ModelManager(app);
            File custom = new File(modelManager.getModelSaveDirectory(), fileName);
            if (custom.exists() && custom.isFile()) {
                if (!ModelDownloadManager.verifyComplete(custom, expectedBytes, expectedSha)) {
                    AILogger.w(TAG, "模型文件不完整(自定义): " + fileName + " size=" + custom.length()
                            + " expected=" + expectedBytes);
                    return false;
                }
                return true;
            }

            // files 根目录
            File f2 = new File(app.getFilesDir(), fileName);
            if (f2.exists() && f2.isFile()) {
                if (!ModelDownloadManager.verifyComplete(f2, expectedBytes, expectedSha)) {
                    AILogger.w(TAG, "模型文件不完整(files根): " + fileName + " size=" + f2.length()
                            + " expected=" + expectedBytes);
                    return false;
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            AILogger.w(TAG, "modelFileExists failed: " + e.getMessage());
            return false;
        }
    }

    /** 根据模型文件名从 preset 解析期望 SHA-256（无 preset 匹配返回 null） */
    private static String expectedShaForFileName(Context app, String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        try {
            ModelDownloadManager manager = ModelDownloadManager.getInstance(app);
            java.util.List<ModelDownloadManager.ModelPresetInfo> list = manager.getPresetDomesticModels();
            if (list != null) {
                for (ModelDownloadManager.ModelPresetInfo p : list) {
                    if (p == null) continue;
                    if (p.downloadUrl != null && fileName.equals(fileNameFromUrl(p.downloadUrl))) return p.sha256;
                    if (p.mmprojUrl != null && !p.mmprojUrl.isEmpty()
                            && fileName.equals(fileNameFromUrl(p.mmprojUrl))) return p.mmprojSha256;
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "expectedShaForFileName failed: " + e.getMessage());
        }
        return null;
    }

    private static boolean modelFileExistsInternal(Context app, String fileName, long expectedBytes) {
        if (fileName == null || fileName.isEmpty()) return false;
        String expectedSha = expectedShaForFileName(app, fileName);
        try {
            File f = new File(new File(app.getFilesDir(), "ai_models"), fileName);
            if (!f.exists() || !f.isFile()) return false;
            return ModelDownloadManager.verifyComplete(f, expectedBytes, expectedSha);
        } catch (Exception e) {
            AILogger.w(TAG, "modelFileExistsInternal failed: " + e.getMessage());
            return false;
        }
    }

    /** 文件大小是否达到完整性阈值（expectedBytes<=0 时仅需存在） */
    private static boolean isComplete(File f, long expectedBytes) {
        if (expectedBytes <= 0) return true;
        try {
            return f.length() >= (long) (expectedBytes * INTEGRITY_THRESHOLD);
        } catch (Exception e) {
            return true;
        }
    }

    /** 删除损坏 / 不完整的模型文件（避免下次再次误判为已存在） */
    private static void deleteCorruptedFile(Context app, String fileName) {
        try {
            File internal = new File(new File(app.getFilesDir(), "ai_models"), fileName);
            if (internal.exists() && internal.isFile()) {
                AILogger.w(TAG, "删除不完整主模型: " + internal.getAbsolutePath());
                //noinspection ResultOfMethodCallIgnored
                internal.delete();
            }
        } catch (Exception e) {
            AILogger.w(TAG, "deleteCorruptedFile failed: " + e.getMessage());
        }
    }

    /** 判断是否为 mmproj 投影文件（投影文件不能作为主模型加载） */
    private static boolean isProjectionFileName(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.contains("mmproj") || lower.contains("clip") || lower.contains("projection");
    }

    /** 从 URL 提取文件名 */
    private static String fileNameFromUrl(String url) {
        if (url == null || url.isEmpty()) return "model.gguf";
        try {
            String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
            int lastSlash = decoded.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < decoded.length() - 1) {
                String fileName = decoded.substring(lastSlash + 1);
                int queryIdx = fileName.indexOf('?');
                if (queryIdx > 0) fileName = fileName.substring(0, queryIdx);
                if (!fileName.isEmpty()) return fileName;
            }
        } catch (Exception ignored) {
        }
        return "model.gguf";
    }

    private static String formatSpeed(long speedBps) {
        if (speedBps <= 0) return "速度未知";
        if (speedBps >= 1024 * 1024) return String.format(java.util.Locale.CHINA, "%.1f MB/s", speedBps / 1024.0 / 1024.0);
        return String.format(java.util.Locale.CHINA, "%.0f KB/s", speedBps / 1024.0);
    }

    // ==================== 回调派发 ====================

    private static void notifyProgress(final InitCallback cb, final String msg, final int percent) {
        if (cb == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb.onProgress(msg, percent);
        } else {
            MAIN.post(() -> cb.onProgress(msg, percent));
        }
    }

    private static void notifySecondaryProgress(final InitCallback cb, final String label,
                                                final int percent, final long downloadedMB, final long totalMB) {
        if (cb == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb.onSecondaryProgress(label, percent, downloadedMB, totalMB);
        } else {
            MAIN.post(() -> cb.onSecondaryProgress(label, percent, downloadedMB, totalMB));
        }
    }

    private static void notifyDownloadReady(final InitCallback cb, final String modelName, final String modelFileName) {
        if (cb == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb.onDownloadReady(modelName, modelFileName);
        } else {
            MAIN.post(() -> cb.onDownloadReady(modelName, modelFileName));
        }
    }

    private static void notifyComplete(final InitCallback cb, final String modelName, final boolean downloaded) {
        if (cb == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb.onComplete(modelName, downloaded);
        } else {
            MAIN.post(() -> cb.onComplete(modelName, downloaded));
        }
    }

    private static void notifyError(final InitCallback cb, final String error) {
        if (cb == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb.onError(error);
        } else {
            MAIN.post(() -> cb.onError(error));
        }
    }
}
