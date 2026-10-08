package com.oilquiz.app.ai.chat.status;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.refactor.AIConfig;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AIServiceState;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.inference.InferenceRouter;

import java.util.List;

import com.oilquiz.app.theme.ThemeColors;
/**
 * 管理 AI 服务状态栏的显示、加载计时器、状态详情对话框。
 * 从 AIChatActivity 中提取的独立模块。
 */
public class ServiceStatusManager {

    private static final String TAG = "ServiceStatusManager";
    private static final long LOADING_TIMER_INTERVAL_MS = 1000;

    public interface Callback {
        void onAddSystemMessage(String message, ChatMessage.SystemMessageType type);
        void onAddErrorMessage(String title, String detail, boolean withRetry);
        void onShowToast(String message);
        void onShouldUseOnlineModel();
        void onHideLoading();
    }

    private final Activity activity;
    private final Handler uiHandler;
    private final Callback callback;

    // View references
    private TextView serviceStatusIcon;
    private TextView serviceStatusText;
    private android.widget.ProgressBar serviceStatusProgress;
    private TextView serviceStatusElapsed;
    private View thinkingIndicator;

    // State
    private AIService aiService;
    private InferenceRouter inferenceRouter;
    private AIService.DetailedStatusObserver aiStatusObserver;
    private Runnable loadingTimerRunnable;
    private boolean isLoadingModel = false;
    private int loadingProgressMessageIndex = -1;
    private int lastLoadingProgressShown = -1;
    private int lastRecoveryProgressShown = -1;
    private int recoveryProgressUpdateCount = 0;
    private List<ChatMessage> chatHistory;

    public ServiceStatusManager(Activity activity, Handler uiHandler, Callback callback) {
        this.activity = activity;
        this.uiHandler = uiHandler;
        this.callback = callback;
    }

    public void bindViews(View serviceStatusBar, TextView serviceStatusIcon,
                          TextView serviceStatusText, android.widget.ProgressBar serviceStatusProgress,
                          TextView serviceStatusElapsed, View thinkingIndicator) {
        this.serviceStatusIcon = serviceStatusIcon;
        this.serviceStatusText = serviceStatusText;
        this.serviceStatusProgress = serviceStatusProgress;
        this.serviceStatusElapsed = serviceStatusElapsed;
        this.thinkingIndicator = thinkingIndicator;
    }

    public void setServices(AIService aiService, InferenceRouter inferenceRouter, List<ChatMessage> chatHistory) {
        this.aiService = aiService;
        this.inferenceRouter = inferenceRouter;
        this.chatHistory = chatHistory;
    }

    public void registerObserver() {
        if (aiService == null) return;
        aiStatusObserver = new AIService.DetailedStatusObserver() {
            @Override
            public void onStateChanged(AIServiceState.ServiceStage stage, String message, int progress, long elapsedMs) {
                // NPU（GenieX）引擎下本地服务状态与本轮推理无关 → 不覆盖状态栏
                if (isNpuEngineOn()) return;
                activity.runOnUiThread(() -> handleStatusChange(stage, message, progress, elapsedMs));
            }
            @Override
            public void onError(String errorMessage) {
                // 关键：NPU 引擎下本地模型缺失/初始化失败不该弹进对话（否则界面一直报"AI服务初始化失败"，
                // 而实际上 NPU 推理是正常的）
                if (isNpuEngineOn()) return;
                activity.runOnUiThread(() -> handleError(errorMessage));
            }
            @Override
            public void onInitialized(String modelName, long loadTimeMs) {
                if (isNpuEngineOn()) return;
                activity.runOnUiThread(() -> handleInitialized(modelName, loadTimeMs));
            }
        };
        aiService.registerDetailedStatusObserver(aiStatusObserver);
    }

    // ==================== NPU（Qualcomm GenieX）引擎 ====================

    /** NPU（GenieX）引擎是否开启 */
    private boolean isNpuEngineOn() {
        try {
            return inferenceRouter != null && inferenceRouter.isNpuEngineEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * NPU 状态一行摘要（顶部状态栏显示用）。
     *
     * <p>结构：{@code AI · <中间随状态变化的词> [· 补充]}，例如：</p>
     * <pre>
     *   AI · 模型加载中 6s              加载期（秒数每秒递增）
     *   AI · 空闲 · Qwen3.5-2B-Q4_0     已就绪且无任务 → 顺便把模型名放这儿，不占额外一行
     *   AI · 处理提示                   正在 prefill
     *   AI · 思考中                     思考链
     *   AI · 生成中 · 12.3 t/s          出正文，带速度
     *   AI · 不可用                     出错
     * </pre>
     *
     * <p>WHY（2026-10-07）：原先中间是固定的"已就绪"，只有加载完成那一刻才动一下；
     * 而 NpuEngineState 本就维护着与加载正交的**推理阶段**（IDLE/PREPROCESS/THINKING/GENERATING），
     * 状态栏却没消费它。改成让中间段随真实状态变化，一行就能表达"AI 现在在干什么"。</p>
     */
    private String npuStatusLine() {
        // 加载/出错：阶段标签本身就是"现在在做什么"（如"模型加载中 6s"、"AI 不可用"）
        String stageLabel = npuSvc().getNpuStageLabel();
        boolean ready = false;
        try {
            ready = com.oilquiz.app.ai.engine.NpuEngineState.get().isReady();
        } catch (Throwable ignored) {
        }
        if (!ready) {
            return (stageLabel == null || stageLabel.isEmpty()) ? "AI · 待加载" : ("AI · " + stageLabel);
        }

        // 已就绪：用推理阶段作为中间段
        String phase = null;
        try {
            phase = com.oilquiz.app.ai.engine.NpuEngineState.get().getInferencePhaseLabel();
        } catch (Throwable ignored) {
        }
        boolean idle = (phase == null || phase.isEmpty());
        if (idle) {
            // 空闲：统一走「引擎 · 模型 · 就绪」，与本地/在线两条路径措辞一致
            return engineStatusLine("就绪");
        }

        StringBuilder sb = new StringBuilder("AI · ").append(phase);
        float tps = npuSvc().getNpuLastTps();
        if (tps > 0) {
            sb.append(" · ").append(String.format(java.util.Locale.US, "%.1f t/s", tps));
        }
        return sb.toString();
    }

    private String npuStateName(String state) {
        if (state == null) return "未知";
        switch (state) {
            case "IDLE": return "待加载（首次发送时自动加载）";
            case "LOADING": return "模型加载中";
            case "READY": return "就绪";
            case "GENERATING": return "生成中";
            case "DOWNLOADING": return "模型下载中";
            case "ERROR": return "不可用";
            default: return state;
        }
    }

    /** NPU 状态详情对话框（独立于本地 AIService） */
    private void showNpuStatusDetails() {
        String model = npuSvc().getNpuModelName();
        int tokens = npuSvc().getNpuLastTokens();
        long elapsed = npuSvc().getNpuLastElapsedMs();
        float tps = npuSvc().getNpuLastTps();
        StringBuilder sb = new StringBuilder();
        sb.append("引擎: Qualcomm GenieX（llama_cpp 运行时）\n");
        sb.append("算力单元: Hexagon NPU（HTP）\n");
        sb.append("状态: ").append(npuStateName(npuSvc().getNpuStateName())).append('\n');
        String willUse = npuModelWillUse();
        sb.append("模型: ").append(model == null || model.isEmpty()
                ? (willUse == null ? "未加载（模型库为空，请先下载 Q4_0）" : willUse + "（待加载）")
                : model).append('\n');
        if (tokens > 0) {
            sb.append("上次推理: ").append(tokens).append(" tokens / ")
                    .append(elapsed).append(" ms / ")
                    .append(String.format(java.util.Locale.US, "%.1f t/s", tps)).append('\n');
        }
        sb.append("App 模型库: files/ai_models → ").append(willUse == null ? "（空）" : willUse).append('\n');
        sb.append("(\u4e0d\u4f9d\u8d56 llama.cpp \u672c\u5730\u670d\u52a1)");

        new AlertDialog.Builder(activity)
                .setTitle("AI服务状态详情（NPU / GenieX）")
                .setMessage(sb.toString())
                .setPositiveButton("模型下载", (d, w) -> openModelDownload())
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 把 NPU 状态映射到顶部状态栏。
     *
     * <p>注意进度参数：NPU 侧 {@code setCurrentStage} 里的百分比是**阶段入口写死的常量**
     * （10 / 45 / 100），不是测量值，而 SDK 也没有加载进度回调，所以**不把它当百分比展示**
     * （界面文案只用 {@link #npuStatusLine()} 的阶段名 + 已耗时）。
     * 但也不该硬写 100 —— 那会让加载中的进度条显示"已满"，同样失真；
     * 这里用"加载中 0 / 就绪 100"来表达"未完成 / 完成"两态，不对未完成量做虚假的精确断言。</p>
     */
    private void updateNpuStatusBar() {
        boolean ready = false;
        boolean busy = false;
        try {
            com.oilquiz.app.ai.engine.NpuEngineState st =
                    com.oilquiz.app.ai.engine.NpuEngineState.get();
            ready = st.isReady();
            busy = st.isStageBusy();
        } catch (Throwable ignored) {
        }
        // 只在**确实处于加载阶段**时开心跳：让"（已 X 秒）"真的在走。
        // 不能用 !ready 作条件 —— 那样"尚未加载"的常态也会每秒跑一个无意义的定时器。
        if (busy) {
            startNpuLoadingTimer();
        } else {
            stopNpuLoadingTimer();
        }
        updateStatusDisplay(
                ready ? AIServiceState.ServiceStage.INITIALIZED
                        : AIServiceState.ServiceStage.MODEL_LOADING,
                npuStatusLine(),
                ready ? 100 : 0,
                0);
    }

    public void unregisterObserver() {
        stopNpuLoadingTimer();
        if (aiService != null && aiStatusObserver != null) {
            aiService.unregisterDetailedStatusObserver(aiStatusObserver);
            aiStatusObserver = null;
        }
    }

    public void updateInitialStatus() {
        if (isNpuEngineOn()) {
            updateNpuStatusBar();
            // NPU-STATUS-OBSERVER: 订阅 NPU 状态机 —— 聊天页横幅原先没有任何刷新触发，
            // 引擎已 READY/GENERATING 时横幅仍停在初始的"待加载"。
            if (!npuObserverRegistered) {
                npuObserverRegistered = true;
                try {
                    com.oilquiz.app.ai.engine.NpuEngineState.get().addListener(
                            (stage, msg, percent) -> {
                                try {
                                    // 阶段变化即刷新（原来硬写 INITIALIZED/100，导致加载中也显示"就绪满格"）
                                    activity.runOnUiThread(this::updateNpuStatusBar);
                                } catch (Throwable ignored) {
                                }
                            });
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        if (inferenceRouter != null && inferenceRouter.isUsingOnlineModel()) {
            updateOnlineModelStatus();
            return;
        }

        if (aiService == null) return;
        AIServiceState state = aiService.getServiceState();
        if (state == null) {
            updateStatusDisplay(AIServiceState.ServiceStage.UNINITIALIZED, "AI服务未初始化", 0, 0);
            return;
        }
        AIServiceState.ServiceStage stage = state.getCurrentStage();
        String message = state.getStageDescription();
        if (message == null || message.isEmpty()) {
            message = getStageDisplayName(stage);
        }
        updateStatusDisplay(stage, message, state.getProgressPercent(), 0);
    }

    public void showStatusDetails() {
        // NPU（GenieX）引擎：本地 AIService 跟当前推理无关，显示 NPU 自己的详情
        if (isNpuEngineOn()) {
            showNpuStatusDetails();
            return;
        }
        if (inferenceRouter != null && inferenceRouter.isUsingOnlineModel()) {
            showOnlineModelDetails();
            return;
        }

        if (aiService == null) {
            callback.onShowToast("AI服务未初始化");
            return;
        }

        AIServiceState state = aiService.getServiceState();
        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_service_status_detail, null);

        TextView statusValue = dialogView.findViewById(R.id.detail_status_value);
        TextView descValue = dialogView.findViewById(R.id.detail_desc_value);
        TextView progressValue = dialogView.findViewById(R.id.detail_progress_value);
        View errorRow = dialogView.findViewById(R.id.detail_error_row);
        TextView errorValue = dialogView.findViewById(R.id.detail_error_value);
        TextView libValue = dialogView.findViewById(R.id.detail_lib_value);
        TextView modelValue = dialogView.findViewById(R.id.detail_model_value);
        TextView optValue = dialogView.findViewById(R.id.detail_opt_value);
        View memRow = dialogView.findViewById(R.id.detail_mem_row);
        View memDivider = dialogView.findViewById(R.id.detail_mem_divider);
        TextView memValue = dialogView.findViewById(R.id.detail_mem_value);
        View speedRow = dialogView.findViewById(R.id.detail_speed_row);
        View speedDivider = dialogView.findViewById(R.id.detail_speed_divider);
        TextView speedValue = dialogView.findViewById(R.id.detail_speed_value);
        View tokenRow = dialogView.findViewById(R.id.detail_token_row);
        TextView tokenValue = dialogView.findViewById(R.id.detail_token_value);
        com.google.android.material.card.MaterialCardView modelInfoCard = dialogView.findViewById(R.id.detail_model_info_card);
        TextView modelInfoText = dialogView.findViewById(R.id.detail_model_info_text);

        if (state != null) {
            AIServiceState.ServiceStage stage = state.getCurrentStage();
            statusValue.setText("\uD83D\uDCF1 " + getStageDisplayName(stage));
            descValue.setText(state.getStageMessage() != null ? state.getStageMessage() : "无");
            progressValue.setText(state.getProgressPercent() + "%");
            if (state.isError() && state.getErrorMessage() != null) {
                errorRow.setVisibility(View.VISIBLE);
                errorValue.setText(state.getErrorMessage());
            }
        } else {
            statusValue.setText("未知");
            descValue.setText("-");
            progressValue.setText("-");
        }

        boolean libLoaded = com.oilquiz.app.ai.jni.LlamaHelper.isLibraryLoaded();
        libValue.setText(libLoaded ? "\u2713 已加载" : "\u2717 未加载");
        libValue.setTextColor(libLoaded ? ThemeColors.get(R.color.hc_ff4caf50) : ThemeColors.get(R.color.hc_fff44336));

        String modelName = aiService.getCurrentModelName();
        modelValue.setText(modelName != null ? modelName : "未选择");

        AIConfig.OptimizationMode optMode = aiService.getOptimizationMode();
        optValue.setText(optMode != null ? optMode.displayName : "平衡模式");

        try {
            float memUsage = com.oilquiz.app.ai.jni.LlamaHelper.getMemoryUsage();
            if (memUsage > 0) {
                memRow.setVisibility(View.VISIBLE);
                memDivider.setVisibility(View.VISIBLE);
                memValue.setText(String.format("%.1f MB", memUsage));
            }
        } catch (Exception ignored) { }

        try {
            float speed = com.oilquiz.app.ai.jni.LlamaHelper.getInferenceSpeed();
            if (speed > 0) {
                speedRow.setVisibility(View.VISIBLE);
                speedDivider.setVisibility(View.VISIBLE);
                speedValue.setText(String.format("%.2f token/s", speed));
            }
        } catch (Exception ignored) { }

        try {
            int tokenCount = com.oilquiz.app.ai.jni.LlamaHelper.getTokenCount();
            if (tokenCount > 0) {
                tokenRow.setVisibility(View.VISIBLE);
                tokenValue.setText(String.valueOf(tokenCount));
            }
        } catch (Exception ignored) { }

        try {
            String modelInfo = com.oilquiz.app.ai.jni.LlamaHelper.getModelInfo();
            if (modelInfo != null && !modelInfo.isEmpty() && !modelInfo.startsWith("Error:")) {
                modelInfoCard.setVisibility(View.VISIBLE);
                modelInfoText.setText(modelInfo);
            }
        } catch (Exception ignored) { }

        String fullDetails = buildFullDetailsText();

        new AlertDialog.Builder(activity)
            .setTitle("AI服务状态详情（本地）")
            .setView(dialogView)
            .setPositiveButton("复制详情", (dialog, which) -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("AI Status Details", fullDetails));
                callback.onShowToast("已复制到剪贴板");
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    /**
     * 打开「模型下载」页：NPU 模型走项目原有下载功能（`models_presets.json` 里已有
     * Qwen3-0.6B / Qwen3-1.7B 的 **Q4_0** 预设 —— Q4_0 才是 Hexagon NPU 原生加速的量化）。
     *
     * <p>2026-10-04：独立的「NPU 推理」页已删除。NPU 已接进主对话流程，引擎开关在
     * 「模型选择」页的「NPU 引擎（GenieX）」行，模型下载走这里。
     */
    private void openModelDownload() {
        try {
            activity.startActivity(new android.content.Intent(activity,
                    com.oilquiz.app.ui.activity.ModelDownloadActivity.class));
        } catch (Throwable t) {
            callback.onShowToast("打不开模型下载页: " + t);
        }
    }

    public void setLoadingModel(boolean loading) {
        this.isLoadingModel = loading;
        if (!loading) {
            loadingProgressMessageIndex = -1;
            lastLoadingProgressShown = -1;
        }
    }

    public boolean isLoadingModel() {
        return isLoadingModel;
    }

    public void updateRecoveryProgress(String message, int progress) {
        if (progress == lastRecoveryProgressShown) return;
        lastRecoveryProgressShown = progress;
        recoveryProgressUpdateCount++;
        if (recoveryProgressUpdateCount % 5 == 0 || progress >= 100) {
            updateRecoveryProgressMessage(message, progress);
        }
    }

    public void showThinkingIndicator() {
        if (thinkingIndicator != null) thinkingIndicator.setVisibility(View.VISIBLE);
    }

    public void hideThinkingIndicator() {
        if (thinkingIndicator != null) thinkingIndicator.setVisibility(View.GONE);
    }

    public void cleanup() {
        stopLoadingTimer();
        unregisterObserver();
    }

    // --- Private methods ---

    private void handleStatusChange(AIServiceState.ServiceStage stage, String message, int progress, long elapsedMs) {
        if (inferenceRouter != null && inferenceRouter.isUsingOnlineModel()) {
            return;
        }

        updateStatusDisplay(stage, message, progress, elapsedMs);

        if (!isLoadingModel) return;

        String stageName = getStageDisplayName(stage);
        updateLoadingProgressMessage(stageName, message, progress, elapsedMs);
        showThinkingIndicator();
    }

    private void handleError(String errorMessage) {
        isLoadingModel = false;
        loadingProgressMessageIndex = -1;
        lastLoadingProgressShown = -1;
        hideThinkingIndicator();

        // 本地模型文件不存在（没下过 / 已删除）：**不是故障**，是"本来就不该尝试加载"。
        // 不再往对话里写"AI服务初始化失败"、也不弹 toast —— 否则用户会以为 App 坏了，
        // 而实际上（开了 NPU 时）推理完全正常，只是本地那份模型没下而已。
        try {
            if (aiService != null && !aiService.isCurrentModelFileExists()) {
                android.util.Log.i("ServiceStatusManager",
                        "本地模型文件不存在，忽略本地服务错误（不污染对话）: " + errorMessage);
                return;
            }
        } catch (Throwable ignored) {
        }

        if (!npuSvc().isNpuEngineEnabled()) {
            callback.onAddErrorMessage("AI服务初始化失败", errorMessage, true);
        }
        callback.onShowToast("模型加载失败");
    }

    private void handleInitialized(String modelName, long loadTimeMs) {
        isLoadingModel = false;
        loadingProgressMessageIndex = -1;
        lastLoadingProgressShown = -1;
        hideThinkingIndicator();
        String successMsg = String.format("\u2713 模型加载完成\n模型: %s\n耗时: %.1f秒",
            modelName != null ? modelName : "未知", loadTimeMs / 1000.0);
        callback.onAddSystemMessage(successMsg, ChatMessage.SystemMessageType.SUCCESS);
        callback.onShowToast("模型加载成功");
        updateStatusDisplay(AIServiceState.ServiceStage.INITIALIZED, "AI服务已就绪", 100, 0);
    }

    private void updateStatusDisplay(AIServiceState.ServiceStage stage, String message, int progress, long elapsedMs) {
        if (serviceStatusIcon == null || serviceStatusText == null) return;

        if (inferenceRouter != null && inferenceRouter.isUsingOnlineModel()) {
            updateOnlineModelStatus();
            return;
        }

        String stageIcon = getStageIcon(stage);
        String stageName = getStageDisplayName(stage);
        // 文案统一走"短句 + 秒数"（与 NPU 侧同一套）：原实现优先用 stageMessage，
        // 而那是各调用点手写的中文长句（如"加载模型到内存中..."），与 NPU 侧风格不一致。
        // 这里让 AIServiceState.getStageLabel() 作为唯一来源；它取不到时再退回 stageMessage。
        String humanLabel = null;
        try {
            if (aiService != null && aiService.getServiceState() != null) {
                humanLabel = aiService.getServiceState().getStageLabel();
            }
        } catch (Throwable ignored) {
        }
        String displayMessage = (humanLabel != null && !humanLabel.isEmpty())
                ? humanLabel
                : (message != null ? message : stageName);

        boolean isLoading = stage == AIServiceState.ServiceStage.NATIVE_LIBRARY_LOADING
            || stage == AIServiceState.ServiceStage.MODEL_FILE_PREPARING
            || stage == AIServiceState.ServiceStage.MODEL_LOADING
            || stage == AIServiceState.ServiceStage.GPU_INITIALIZATION
            || stage == AIServiceState.ServiceStage.CPU_FALLBACK
            || stage == AIServiceState.ServiceStage.CHAT_CONTEXT_CREATING;

        if (isLoading) {
            startLoadingTimer(stage, message, progress);
            if (serviceStatusProgress != null) {
                serviceStatusProgress.setVisibility(View.VISIBLE);
                serviceStatusProgress.setProgress(progress);
            }
        } else {
            stopLoadingTimer();
            if (serviceStatusProgress != null) {
                serviceStatusProgress.setVisibility(View.GONE);
            }
        }

        if (stage == AIServiceState.ServiceStage.INITIALIZED) {
            // NPU-BANNER-ICON2
            stageIcon = npuSvc().isNpuEngineEnabled() ? "\uD83E\uDDE0" : "\uD83D\uDCF1";
            // NPU-BANNER-FIX
            // 注意：init 分支原先硬拼 "本地推理就绪 · " + message，而后面的 message 是
            // AIService 写入的 stageMessage（"AI服务已就绪"）—— 两段同义，读起来是重复的
            // （实测显示为"本地推理就绪 · AI服务已就绪"）。统一只保留一处人话即可。
            // 统一文案：NPU 用其阶段行，其余（llama.cpp/在线）用「引擎 · 模型 · 就绪」。
            // 原先本地分支写死"本地推理就绪"，既缺引擎也缺模型名。
            displayMessage = npuSvc().isNpuEngineEnabled()
                    ? (message != null && !message.isEmpty() ? message : npuStatusLine())
                    : engineStatusLine("就绪");
        }

        serviceStatusIcon.setText(stageIcon);
        serviceStatusText.setText(displayMessage);
    }

    /**
     * 状态栏统一文案：「引擎 · 模型 · 状态」。
     *
     * <p>存在理由：原先本地路径写死 {@code "本地推理就绪"}（不含引擎与模型名）、在线路径写
     * {@code "云端推理就绪 · 模型名"}、NPU 路径写 {@code "AI · 空闲 · 模型名"} —— 三种措辞并存，
     * 且本地/NPU 两条都用 {@code "本地模型"} 字样，用户无法从状态栏区分当前到底跑在
     * llama.cpp 还是 GenieX NPU 上。</p>
     *
     * <p>引擎与模型名一律取自 {@code InferenceRouter}（全项目路由权威，优先级 NPU &gt; 在线 &gt; 本地），
     * 本方法不自行判断引擎，避免"开了 NPU 开关就当成在用 NPU"这类误报。</p>
     *
     * <p>可见性：{@code public} 供 {@code AIChatActivity} 在 Agent 执行结束后把第 1 行复位成
     * 「引擎 · 模型」（阶段文案只走第 3 行，不再覆盖第 1 行）。</p>
     *
     * @param state 状态词，如「就绪」「处理中」；为空则只显示引擎与模型
     * @return 如 {@code "本地模型 · MiniCPM5-2B-Q4_K_M.gguf · 就绪"}；
     *         引擎信息不可用时退回 {@code state}
     */
    public String engineStatusLine(String state) {
        try {
            if (inferenceRouter != null) {
                com.oilquiz.app.ai.model.InferenceType type = inferenceRouter.getCurrentInferenceType();
                String engine = type != null ? type.getDisplayName() : null;

                // ACTUAL-ENGINE(2026-10-08)：优先显示**本轮实际执行**的引擎，
                // 而不是"用户选了哪个"。因为开了 NPU 开关也可能实际跑在 llama.cpp 上：
                //   - 多模态请求（needsVision=true 时 shouldRouteToNpu 直接返回 false）
                //   - NPU 加载失败/异常 → 无条件回退 llama.cpp
                // 记录点在 NpuEngineRouter 的每个真实决策分支。
                String actual = null;
                try {
                    actual = com.oilquiz.app.ai.engine.NpuEngineRouter.getActualEngine();
                } catch (Throwable ignored) {
                }
                boolean actualNpu = com.oilquiz.app.ai.engine.NpuEngineRouter.ENGINE_NPU.equals(actual);
                boolean actualLlama = com.oilquiz.app.ai.engine.NpuEngineRouter.ENGINE_LLAMA.equals(actual);

                if (actualLlama) {
                    engine = "本地模型";
                } else if (actualNpu) {
                    engine = "NPU（GenieX）";
                } else if (type == com.oilquiz.app.ai.model.InferenceType.NPU) {
                    // 尚无推理记录：只能按配置显示
                    engine = "NPU（GenieX）";
                }

                if (engine != null && !engine.isEmpty()) {
                    String model = inferenceRouter.getCurrentModelName();
                    // NPU 模型名自带「（NPU）」后缀，与引擎名重复，去掉避免
                    // "NPU（GenieX） · Qwen3（NPU）" 这类同义重复
                    if (model != null && model.contains("（NPU）")) {
                        model = model.replace("（NPU）", "").trim();
                    }
                    StringBuilder sb = new StringBuilder(engine);
                    if (model != null && !model.isEmpty()) {
                        sb.append(" · ").append(model);
                    }
                    if (state != null && !state.isEmpty()) {
                        sb.append(" · ").append(state);
                    }
                    return sb.toString();
                }
            }
        } catch (Throwable ignored) {
            // 引擎信息取不到就退回状态词，不影响原有行为
        }
        return state != null ? state : "";
    }

    private void updateOnlineModelStatus() {
        stopLoadingTimer();
        if (serviceStatusProgress != null) {
            serviceStatusProgress.setVisibility(View.GONE);
        }

        String modelName = inferenceRouter != null ? inferenceRouter.getCurrentModelName() : null;
        String displayName = modelName != null && !modelName.isEmpty() ? modelName : "在线模型";
        if (serviceStatusIcon != null) serviceStatusIcon.setText("\u2601\uFE0F");
        // 统一文案：在线模型 · 模型名 · 就绪（原先只有"云端推理就绪 · 模型名"）
        if (serviceStatusText != null) {
            serviceStatusText.setText(engineStatusLine(displayName + " 就绪"));
        }
    }

    private void startLoadingTimer(AIServiceState.ServiceStage stage, String message, int progress) {
        if (uiHandler == null) return;
        stopLoadingTimer();

        final String stageIcon = getStageIcon(stage);

        loadingTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (aiService == null || aiService.getServiceState() == null) return;

                // 文案统一交给 AIServiceState.getStageLabel()（短句 + 秒数，与 NPU 侧一致）。
                // 原先这里自己拼 "%s (已耗时: %.1fs)"：既与 NPU 侧风格不一，
                // 又会把 updateStatusDisplay 刚写好的文案覆盖掉。取不到才退回 message。
                String displayMessage = null;
                try {
                    displayMessage = aiService.getServiceState().getStageLabel();
                } catch (Throwable ignored) {
                }
                if (displayMessage == null || displayMessage.isEmpty()) {
                    displayMessage = message != null ? message : getStageDisplayName(stage);
                }

                if (serviceStatusIcon != null) serviceStatusIcon.setText(stageIcon);
                if (serviceStatusText != null) serviceStatusText.setText(displayMessage);

                if (aiService.getServiceState().isLoading()) {
                    uiHandler.postDelayed(this, LOADING_TIMER_INTERVAL_MS);
                }
            }
        };

        uiHandler.post(loadingTimerRunnable);
    }

    private void stopLoadingTimer() {
        if (uiHandler != null && loadingTimerRunnable != null) {
            uiHandler.removeCallbacks(loadingTimerRunnable);
            loadingTimerRunnable = null;
        }
    }

    // ---- NPU 加载心跳 ----
    // 复用不了上面那个 loadingTimer：它读的是 aiService（llama.cpp）的 ServiceState，
    // 且续跑条件是 aiService.getServiceState().isLoading() —— NPU 加载时 llama 侧并没有在加载，
    // 所以定时器会立刻停掉，界面上"已耗时"永远不会走字。
    // 这里直接以 NpuEngineState 为准：**只要还没 READY/ERROR 就每秒刷新**，
    // 让"加载权重（已 Xs）"里的秒数真的在动（SDK 无加载进度回调，这是唯一可得的活动信号）。
    private Runnable npuLoadingTimerRunnable;

    private void startNpuLoadingTimer() {
        if (uiHandler == null) return;
        stopNpuLoadingTimer();
        npuLoadingTimerRunnable = new Runnable() {
            @Override
            public void run() {
                boolean done;
                try {
                    com.oilquiz.app.ai.engine.NpuEngineState st =
                            com.oilquiz.app.ai.engine.NpuEngineState.get();
                    // 不在加载阶段（已就绪 / 已失败 / 未加载）就停止心跳
                    done = !st.isStageBusy();
                } catch (Throwable t) {
                    done = true;
                }
                updateNpuStatusBar();     // 重新求值 → 标签里的"已 Xs"随之前进
                if (serviceStatusIcon != null) serviceStatusIcon.setText("\uD83E\uDDE0");
                if (!done) {
                    uiHandler.postDelayed(this, 1000);
                }
            }
        };
        uiHandler.postDelayed(npuLoadingTimerRunnable, 1000);
    }

    private void stopNpuLoadingTimer() {
        if (uiHandler != null && npuLoadingTimerRunnable != null) {
            uiHandler.removeCallbacks(npuLoadingTimerRunnable);
            npuLoadingTimerRunnable = null;
        }
    }

    private void updateRecoveryProgressMessage(String message, int progress) {
        if (chatHistory == null) return;
        for (int i = chatHistory.size() - 1; i >= 0; i--) {
            ChatMessage msg = chatHistory.get(i);
            if (msg.type == ChatMessage.MessageType.SYSTEM &&
                msg.content != null &&
                msg.content.contains("\uD83D\uDD04 正在重新加载模型")) {
                String newContent = msg.content.replaceAll("\\[\\d+%\\]", "[" + progress + "%]");
                if (!newContent.contains("[")) {
                    newContent = msg.content + " [" + progress + "%]";
                }
                msg.content = newContent;
                break;
            }
        }
    }

    private void updateLoadingProgressMessage(String stageName, String message, int progress, long elapsedMs) {
        if (chatHistory == null) return;
        if (progress == lastLoadingProgressShown) return;
        lastLoadingProgressShown = progress;

        if (loadingProgressMessageIndex < 0 || loadingProgressMessageIndex >= chatHistory.size()) {
            for (int i = chatHistory.size() - 1; i >= 0; i--) {
                ChatMessage msg = chatHistory.get(i);
                if (msg.type == ChatMessage.MessageType.SYSTEM &&
                    msg.content != null &&
                    msg.content.contains("\u23F3")) {
                    loadingProgressMessageIndex = i;
                    break;
                }
            }
        }

        if (loadingProgressMessageIndex >= 0 && loadingProgressMessageIndex < chatHistory.size()) {
            ChatMessage msg = chatHistory.get(loadingProgressMessageIndex);
            String progressStr = progress > 0 ? String.format("[%d%%]", progress) : "";
            String timeStr = String.format("%.1f秒", elapsedMs / 1000.0);
            String icon = progress >= 100 ? "\u2705" : "\u23F3";
            msg.content = String.format("%s %s %s\n已耗时: %s", icon, stageName, progressStr, timeStr);
        }
    }

    private void showOnlineModelDetails() {
        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_service_status_detail, null);

        TextView statusValue = dialogView.findViewById(R.id.detail_status_value);
        TextView descValue = dialogView.findViewById(R.id.detail_desc_value);
        TextView progressValue = dialogView.findViewById(R.id.detail_progress_value);
        TextView libValue = dialogView.findViewById(R.id.detail_lib_value);
        TextView modelValue = dialogView.findViewById(R.id.detail_model_value);
        TextView optValue = dialogView.findViewById(R.id.detail_opt_value);

        View memRow = dialogView.findViewById(R.id.detail_mem_row);
        View memDivider = dialogView.findViewById(R.id.detail_mem_divider);
        View speedRow = dialogView.findViewById(R.id.detail_speed_row);
        View speedDivider = dialogView.findViewById(R.id.detail_speed_divider);
        View tokenRow = dialogView.findViewById(R.id.detail_token_row);
        View errorRow = dialogView.findViewById(R.id.detail_error_row);

        if (memRow != null) memRow.setVisibility(View.GONE);
        if (memDivider != null) memDivider.setVisibility(View.GONE);
        if (speedRow != null) speedRow.setVisibility(View.GONE);
        if (speedDivider != null) speedDivider.setVisibility(View.GONE);
        if (tokenRow != null) tokenRow.setVisibility(View.GONE);
        if (errorRow != null) errorRow.setVisibility(View.GONE);

        com.google.android.material.card.MaterialCardView modelInfoCard = dialogView.findViewById(R.id.detail_model_info_card);
        TextView modelInfoText = dialogView.findViewById(R.id.detail_model_info_text);

        String modelName = inferenceRouter != null ? inferenceRouter.getCurrentModelName() : null;
        String displayName = modelName != null && !modelName.isEmpty() ? modelName : "在线模型";

        statusValue.setText("\u2601\uFE0F 云端推理");
        descValue.setText("使用远程AI模型进行推理");
        progressValue.setText("100%");
        libValue.setText("-");
        modelValue.setText(displayName);
        optValue.setText("云端服务");

        if (modelInfoCard != null && modelInfoText != null) {
            StringBuilder info = new StringBuilder();
            info.append("推理类型: 云端在线推理\n");
            info.append("模型名称: ").append(displayName).append("\n");
            info.append("推理优势: 强大的模型能力、无需本地资源\n");
            if (inferenceRouter != null) {
                info.append("是否可用: ").append(inferenceRouter.isCurrentModelAvailable() ? "是" : "否").append("\n");
            }
            modelInfoText.setText(info.toString());
            modelInfoCard.setVisibility(View.VISIBLE);
        }

        String fullDetails = "\u2601\uFE0F 云端推理服务\n\n" +
            "当前状态: 云端推理就绪\n" +
            "模型名称: " + displayName + "\n" +
            "推理类型: 在线/云端\n" +
            "优势: 强大的模型能力、无需本地资源\n";

        new AlertDialog.Builder(activity)
            .setTitle("AI服务状态详情（云端）")
            .setView(dialogView)
            .setPositiveButton("复制详情", (dialog, which) -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("AI Status Details", fullDetails));
                callback.onShowToast("已复制到剪贴板");
            })
            // NPU 是设备能力，跟当前跑的是本地还是云端模型无关 → 两个详情页都给入口
            // （之前只加在"本地"那份上，用云端模型的人根本看不到按钮）
            .setNegativeButton("关闭", null)
            .show();
    }

    private String buildFullDetailsText() {
        StringBuilder details = new StringBuilder();
        // NPU-AWARE(2026-10-07)：本方法整段是 llama.cpp 的本地运行信息（Native库/显存/速度/
        // token/模型信息）。NPU 模式下这些全无意义（对应 native 调用恒为 0/空）。
        // 目前唯一调用点在已做 NPU 早退的本地详情路径里，这里加防御性守卫，
        // 避免将来被其它入口调用时又泄出一堆 llama.cpp 内容。
        if (isNpuEngineOn()) {
            details.append("\uD83E\uDD16 NPU（GenieX）引擎\n\n");
            details.append(npuStatusLine()).append("\n");
            return details.toString();
        }
        details.append("\uD83E\uDD16 AI服务状态详情\n\n");

        AIServiceState state = aiService.getServiceState();
        if (state != null) {
            AIServiceState.ServiceStage stage = state.getCurrentStage();
            details.append("\uD83D\uDCCA 当前状态: ").append(getStageDisplayName(stage)).append("\n");
            details.append("\uD83D\uDCDD 状态描述: ").append(state.getStageMessage() != null ? state.getStageMessage() : "无").append("\n");
            details.append("\uD83D\uDCC8 进度: ").append(state.getProgressPercent()).append("%\n");
            if (state.getCurrentModelName() != null) {
                details.append("\uD83D\uDCE6 当前模型: ").append(state.getCurrentModelName()).append("\n");
            }
            if (state.isError() && state.getErrorMessage() != null) {
                details.append("\u274C 错误信息: ").append(state.getErrorMessage()).append("\n");
            }
        } else {
            details.append("\uD83D\uDCCA 当前状态: 未知\n");
        }

        details.append("\n\uD83D\uDD27 运行信息\n");
        details.append("Native库: ").append(com.oilquiz.app.ai.jni.LlamaHelper.isLibraryLoaded() ? "\u2713 已加载" : "\u2717 未加载").append("\n");

        String modelName = aiService.getCurrentModelName();
        details.append("模型名称: ").append(modelName != null ? modelName : "未选择").append("\n");

        AIConfig.OptimizationMode optMode = aiService.getOptimizationMode();
        details.append("优化模式: ").append(optMode != null ? optMode.displayName : "平衡模式").append("\n");

        try {
            float memUsage = com.oilquiz.app.ai.jni.LlamaHelper.getMemoryUsage();
            if (memUsage > 0) details.append("内存使用: ").append(String.format("%.1f MB", memUsage)).append("\n");
        } catch (Exception ignored) { }

        try {
            float speed = com.oilquiz.app.ai.jni.LlamaHelper.getInferenceSpeed();
            if (speed > 0) details.append("推理速度: ").append(String.format("%.2f token/s", speed)).append("\n");
        } catch (Exception ignored) { }

        try {
            int tokenCount = com.oilquiz.app.ai.jni.LlamaHelper.getTokenCount();
            if (tokenCount > 0) details.append("Token计数: ").append(tokenCount).append("\n");
        } catch (Exception ignored) { }

        try {
            String modelInfo = com.oilquiz.app.ai.jni.LlamaHelper.getModelInfo();
            if (modelInfo != null && !modelInfo.isEmpty() && !modelInfo.startsWith("Error:")) {
                details.append("\n\uD83D\uDCCB 模型信息\n").append(modelInfo).append("\n");
            }
        } catch (Exception ignored) { }

        // NPU（GenieX 端侧推理）：只报零成本信息，真加载/推理走详情页的「NPU 推理」按钮。
        // 2026-10-03 换轨：旧的手搓 QNN+Genie 自检已退休（随包 QNN 库会与 GenieX AAR 同名互顶，
        // 且只能自检不能推理）；现在能力与状态统一由 GenieX SDK 汇报。
        details.append("\n\uD83E\uDDE0 NPU（Qualcomm GenieX）\n");
        details.append("运行时: GenieX SDK（llama_cpp 跑 GGUF / qairt 跑 AI Hub 预编译）\n");
        details.append("算力单元: Hexagon NPU（HTP）· Adreno GPU · CPU\n");
        details.append("状态: ").append(npuSvc().getNpuStageLabel());
        String npuModel = npuSvc().getNpuModelName();
        if (npuModel != null && !npuModel.isEmpty()) details.append(" · ").append(npuModel);
        float tps = npuSvc().getNpuLastTps();
        if (tps > 0) details.append(" · 上次 ").append(String.format(java.util.Locale.US, "%.1f t/s", tps));
        details.append("\n");
        details.append("入口: 本页下方「NPU 推理」（支持 SM8750 / SM8850）\n");

        return details.toString();
    }

    private String getStageIcon(AIServiceState.ServiceStage stage) {
        if (stage == null) return "\u2699\uFE0F";
        switch (stage) {
            case UNINITIALIZED: return "\u23F3";
            case NATIVE_LIBRARY_LOADING: return "\uD83D\uDCE6";
            case MODEL_FILE_PREPARING: return "\uD83D\uDCC1";
            case MODEL_LOADING: return "\uD83D\uDCE5";
            case GPU_INITIALIZATION: return "\uD83C\uDFAE";
            case CPU_FALLBACK: return "\uD83D\uDCBB";
            case CHAT_CONTEXT_CREATING: return "\uD83D\uDD27";
            case INITIALIZED: return "\u2713";
            case ERROR: return "\u2717";
            default: return "\u2699\uFE0F";
        }
    }

    private String getStageDisplayName(AIServiceState.ServiceStage stage) {
        if (stage == null) return "处理中";
        switch (stage) {
            case UNINITIALIZED: return "未初始化";
            case NATIVE_LIBRARY_LOADING: return "加载原生库";
            case MODEL_FILE_PREPARING: return "准备模型文件";
            case MODEL_LOADING: return "加载模型";
            case GPU_INITIALIZATION: return "初始化GPU";
            case CPU_FALLBACK: return "切换到CPU模式";
            case CHAT_CONTEXT_CREATING: return "创建对话上下文";
            case INITIALIZED: return "已就绪";
            case ERROR: return "错误";
            default: return "处理中";
        }
    }

    /** NPU 将要使用的模型（App 模型库 files/ai_models 里 Q4_0 优先、其次体积最大）；没有返回 null */
    private String npuModelWillUse() {
        try {
            java.io.File dir = new java.io.File(activity.getFilesDir(), "ai_models");
            java.io.File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".gguf"));
            if (files == null || files.length == 0) {
                return null;
            }
            java.io.File best = null;
            for (java.io.File f : files) {
                if (best == null) {
                    best = f;
                    continue;
                }
                boolean fq4 = f.getName().toLowerCase().contains("q4_0");
                boolean bq4 = best.getName().toLowerCase().contains("q4_0");
                if (fq4 != bq4) {
                    if (fq4) best = f;
                } else if (f.length() > best.length()) {
                    best = f;
                }
            }
            return best == null ? null : best.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** UI 单一状态源：NPU 只读值统一走 AIService（聊天页横幅） */
    /** NPU-STATUS-OBSERVER：是否已订阅 NPU 状态机（避免重复注册） */
    private boolean npuObserverRegistered = false;

    private com.oilquiz.app.ai.service.AIService npuSvc() {
        return com.oilquiz.app.ai.service.AIService.getInstance(activity);
    }
}
