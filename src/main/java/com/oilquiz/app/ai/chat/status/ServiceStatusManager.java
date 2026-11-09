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
                activity.runOnUiThread(() -> handleStatusChange(stage, message, progress, elapsedMs));
            }
            @Override
            public void onError(String errorMessage) {
                activity.runOnUiThread(() -> handleError(errorMessage));
            }
            @Override
            public void onInitialized(String modelName, long loadTimeMs) {
                activity.runOnUiThread(() -> handleInitialized(modelName, loadTimeMs));
            }
        };
        aiService.registerDetailedStatusObserver(aiStatusObserver);
    }

    public void unregisterObserver() {
        if (aiService != null && aiStatusObserver != null) {
            aiService.unregisterDetailedStatusObserver(aiStatusObserver);
            aiStatusObserver = null;
        }
    }

    public void updateInitialStatus() {
        if (inferenceRouter != null && inferenceRouter.isUsingOnlineModel()) {
            updateOnlineModelStatus();
            return;
        }

        if (aiService == null) return;
        AIServiceState state = aiService.getServiceState();
        if (state == null) {
            updateStatusDisplay(AIServiceState.ServiceStage.UNINITIALIZED,
                    "本地模型 · 待加载", 0, 0);
            return;
        }
        AIServiceState.ServiceStage stage = state.getCurrentStage();
        if (stage == AIServiceState.ServiceStage.UNINITIALIZED) {
            // 口径修正（2026-10-21）：UNINITIALIZED 只表示"还没开始加载"，不等于服务不可用。
            // 本地模型是懒加载的：之前加载过（isInitialized）就显示就绪；有模型文件就显示待加载；
            // 一个模型都没导入才说"未导入"。别再把"未初始化"甩给用户。
            if (aiService.isInitialized()) {
                updateStatusDisplay(AIServiceState.ServiceStage.INITIALIZED,
                        "本地模型 · 就绪", 100, 0);
                return;
            }
            boolean hasModel = false;
            try {
                hasModel = aiService.isCurrentModelFileExists();
            } catch (Throwable stepFailed) {
                com.oilquiz.app.util.AILogger.w("ServiceStatusManager", "这一步失败，结果可能被当成正常 · updateInitialStatus: " + stepFailed);
            }
            updateStatusDisplay(AIServiceState.ServiceStage.UNINITIALIZED,
                    hasModel ? "本地模型 · 待加载" : "未导入本地模型", 0, 0);
            return;
        }
        String message = state.getStageDescription();
        if (message == null || message.isEmpty()) {
            message = getStageDisplayName(stage);
        }
        updateStatusDisplay(stage, message, state.getProgressPercent(), 0);
    }

    public void showStatusDetails() {
        if (inferenceRouter != null && inferenceRouter.isUsingOnlineModel()) {
            showOnlineModelDetails();
            return;
        }

        if (aiService == null) {
            callback.onShowToast("本地模型尚未加载");
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
        // v2：生成状态改由输入区上方的状态行承载，v1 那条金色渐变细线（thinking_indicator）
        // 不再点亮——留在列表底部像一条脏边（2026-10-25 用户反馈）。
        if (activity != null
                && com.oilquiz.app.ai.chat.ui.ChatUiV2Chrome.isApplied(activity)) {
            if (thinkingIndicator != null) thinkingIndicator.setVisibility(View.GONE);
            return;
        }
        if (thinkingIndicator != null) thinkingIndicator.setVisibility(View.VISIBLE);
    }

    public void hideThinkingIndicator() {
        if (thinkingIndicator != null) thinkingIndicator.setVisibility(View.GONE);
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
        // 而实际上只是本地那份模型没下而已。
        try {
            if (aiService != null && !aiService.isCurrentModelFileExists()) {
                android.util.Log.i("ServiceStatusManager",
                        "本地模型文件不存在，忽略本地服务错误（不污染对话）: " + errorMessage);
                return;
            }
        } catch (Throwable ignored) {
        }

        callback.onAddErrorMessage("AI服务初始化失败", errorMessage, true);
        callback.onShowToast("模型加载失败");
    }

    private void handleInitialized(String modelName, long loadTimeMs) {
        isLoadingModel = false;
        loadingProgressMessageIndex = -1;
        lastLoadingProgressShown = -1;
        hideThinkingIndicator();
        // 模型服务信息改走 toast（2026-10-30）：消息流只承载对话与引擎过程，不承载服务播报
        callback.onShowToast(String.format(java.util.Locale.ROOT,
            "模型加载成功: %s (%.1f秒)",
            modelName != null ? modelName : "未知", loadTimeMs / 1000.0));
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
        // 文案统一走"短句 + 秒数"（与状态机同一套）：原实现优先用 stageMessage，
        // 而那是各调用点手写的中文长句（如"加载模型到内存中..."），与状态机风格不一致。
        // 这里让 AIServiceState.getStageLabel() 作为唯一来源；它取不到时再退回 stageMessage。
        String humanLabel = null;
        // UNINITIALIZED 的短标签是"AI 待加载"，会把上面按"是否有模型文件/是否已加载"分好的
        // 口径盖掉；这一档直接用调用方传进来的 message。
        if (stage != AIServiceState.ServiceStage.UNINITIALIZED) {
            try {
                if (aiService != null && aiService.getServiceState() != null) {
                    humanLabel = aiService.getServiceState().getStageLabel();
                }
            } catch (Throwable stepFailed) {
                com.oilquiz.app.util.AILogger.w("ServiceStatusManager", "这一步失败，结果可能被当成正常 · updateStatusDisplay: " + stepFailed);
            }
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
            // 注意：init 分支原先硬拼 "本地推理就绪 · " + message，而后面的 message 是
            // AIService 写入的 stageMessage（"AI服务已就绪"）—— 两段同义，读起来是重复的
            // （实测显示为"本地推理就绪 · AI服务已就绪"）。统一只保留一处人话即可。
            // 统一文案：本地/在线都用「引擎 · 模型 · 就绪」，引擎名与模型名都由 engineStatusLine 拼。
            displayMessage = engineStatusLine("就绪");
        }

        serviceStatusIcon.setText(stageIcon);
        publishStatusText(withEngine(displayMessage));
        int dotColor = stage == AIServiceState.ServiceStage.INITIALIZED
                ? R.color.chat_v2_success
                : stage == AIServiceState.ServiceStage.ERROR
                ? R.color.chat_v2_danger
                : stage == AIServiceState.ServiceStage.UNINITIALIZED
                ? R.color.chat_v2_text_3
                : R.color.chat_v2_brand;
        try {
            com.oilquiz.app.ai.chat.ui.ChatUiV2Chrome.updateStatusDot(activity, dotColor);
        } catch (Throwable stepFailed) {
            com.oilquiz.app.util.AILogger.w("ServiceStatusManager", "这一步失败，结果可能被当成正常 · updateStatusDisplay: " + stepFailed);
        }
    }

    /**
     * 状态文案的唯一出口：既写旧的 service_status_text（v2 下隐藏，但「⋯」面板还在读），
     * 也同步 v2 顶栏副标题。此前只写前者，导致副标题一直停在布局默认的
     * "AI服务未初始化"——服务其实已经就绪。
     */
    private void publishStatusText(String text) {
        if (serviceStatusText != null) serviceStatusText.setText(text);
        try {
            com.oilquiz.app.ai.chat.ui.ChatUiV2Chrome.updateSubtitle(activity, text);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 横幅用的引擎名（两态：本地模型 / 在线模型）。
     *
     * <p>口径 = {@link InferenceRouter#getCurrentInferenceType()}。
     *
     * <p>2026-10-31：这段判定从 engineStatusLine 里抽出来给 {@link #withEngine} 共用，
     * 让顶部横幅与工具抽屉用同一个判据 —— 两处各写一份必然漂开。</p>
     *
     * <p>GENIEX-REMOVED(2026-11-09)：原先还有第三态 NPU（GenieX），以及"实际引擎"与"配置引擎"
     * 的区分（NpuEngineRouter 记录本轮真实决策）。GenieX 删除后只剩本地/在线两态，
     * 没有"选了 A 实际跑 B"的可能，这个区分也随之取消。</p>
     */
    private String currentEngineName() {
        try {
            if (inferenceRouter == null) return null;
            com.oilquiz.app.ai.model.InferenceType type = inferenceRouter.getCurrentInferenceType();
            return type != null ? type.getDisplayName() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 任何时刻横幅都要能点名当前引擎。
     * 加载期/生成期的文案来自 {@code AIServiceState.getStageLabel()}，那是引擎无关短句
     * （如"模型加载中 6s"），过去直接上屏 → 用户看不出这次加载的是哪一侧。
     * 已经点名的（engineStatusLine 的产物）不重复拼。
     */
    private String withEngine(String label) {
        if (label == null || label.isEmpty()) return label;
        String engine = currentEngineName();
        if (engine == null || engine.isEmpty()) return label;
        if (label.startsWith(engine) || label.contains("本地模型")
                || label.contains("在线模型")) {
            return label;
        }
        return engine + " · " + label;
    }

    /**
     * 状态栏统一文案：「引擎 · 加速后端 · 模型 · 状态」。
     *
     * <p>存在理由：原先本地路径写死 {@code "本地推理就绪"}（不含引擎与模型名）、在线路径写
     * {@code "云端推理就绪 · 模型名"} —— 措辞并存，用户无法从状态栏看出当前用的是哪一侧、
     * 哪个模型。</p>
     *
     * <p>引擎与模型名一律取自 {@code InferenceRouter}（全项目路由权威），
     * 本方法不自行判断引擎，避免"选了 A 就当成在用 A"这类误报。</p>
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
                String engine = currentEngineName();

                if (engine != null && !engine.isEmpty()) {
                    String model = inferenceRouter.getCurrentModelName();
                    StringBuilder sb = new StringBuilder(engine);
                    // BACKEND-TRUTH(2026-11-09)：引擎名只回答"谁在执行"（llama.cpp → 本地模型），
                    // 回答不了"权重跑在哪颗设备上"。HTP0 就是 NPU，可它在引擎维度属于 LOCAL，
                    // 于是横幅长期只写"本地模型"，用户据此以为 NPU 没启用。这里紧跟引擎名补一段
                    // 后端真值（NPU·HTP0·25层 / OpenCL·…·N层 / CPU）。
                    // 为什么放在模型名**之前**：service_status_text 是 weight=1 + maxLines=1 +
                    // ellipsize=end，模型文件名很长，放后面会被整段截掉 = 等于没显示。
                    String accel = localAccelSegment();
                    if (!accel.isEmpty()) {
                        sb.append(" · ").append(accel);
                    }
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

    /**
     * 横幅里的"加速后端"段（BACKEND-TRUTH，2026-11-09）。
     *
     * <p>只在**本地引擎**下补：在线模型跑在服务商侧，没有本地加速后端。取不到后端就返回空串，
     * 横幅退回原来的「引擎 · 模型 · 状态」，绝不猜一个"CPU"上去。</p>
     *
     * <p>性能：本方法被 500ms 轮询间接调用。后端与层数每次读的是 native 的轻量 getter，
     * 真正贵的"设备名"查询（{@code getDeviceCaps()} 过 JNI 再解析 JSON）在
     * {@code LlamaHelper.accelBackendSummary()} 里按 (后端, 层数) 缓存，只有落点真变了才重查。</p>
     */
    private String localAccelSegment() {
        try {
            if (inferenceRouter == null) return "";
            if (inferenceRouter.getCurrentInferenceType()
                    != com.oilquiz.app.ai.model.InferenceType.LOCAL) {
                return "";
            }
            return com.oilquiz.app.ai.jni.LlamaHelper.accelBackendSummary();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void updateOnlineModelStatus() {
        stopLoadingTimer();
        if (serviceStatusProgress != null) {
            serviceStatusProgress.setVisibility(View.GONE);
        }

        if (serviceStatusIcon != null) serviceStatusIcon.setText("\u2601\uFE0F");
        // 统一文案：engineStatusLine 自己会拼「在线模型 · 模型名 · 状态」，
        // 不能再把模型名塞进 state（会变成 "deepseek-flash · deepseek-flash 就绪"）。
        publishStatusText(engineStatusLine("就绪"));
        try {
            com.oilquiz.app.ai.chat.ui.ChatUiV2Chrome.updateStatusDot(
                    activity, R.color.chat_v2_success);
        } catch (Throwable stepFailed) {
            com.oilquiz.app.util.AILogger.w("ServiceStatusManager", "这一步失败，结果可能被当成正常 · updateOnlineModelStatus: " + stepFailed);
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

                // 文案统一交给 AIServiceState.getStageLabel()（短句 + 秒数，与状态机一致）。
                // 原先这里自己拼 "%s (已耗时: %.1fs)"：既与状态机风格不一，
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
                publishStatusText(withEngine(displayMessage));

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
            .setNegativeButton("关闭", null)
            .show();
    }

    private String buildFullDetailsText() {
        StringBuilder details = new StringBuilder();

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
            case UNINITIALIZED: return "待加载";
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
}
