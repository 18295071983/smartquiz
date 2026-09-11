package com.oilquiz.app.ai.speech;

import android.content.Context;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.spi.AppServices;
import com.oilquiz.app.ai.spi.PreferenceStore;
import com.oilquiz.app.ai.spi.SpeechGateway;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.VoiceInputTool;

import java.util.HashMap;
import java.util.Map;

/**
 * 语音输入编排控制器（全局可复用，逻辑层）。
 *
 * 从 AIChatActivity startVoiceRecognitionTool / doVoiceRecognitionTool /
 * startVoiceRecorderTool / isLocalAsrSelected / toggleAutoTts 抽取：
 * 权限申请 → ASR 可用性判定 → 本地模型预热 → voice_input 工具识别 →
 * 识别文本回调；以及自动 TTS 开关的持久化状态。
 *
 * <p>系统能力经 {@link AppServices} 注入（SpeechGateway / ModelGateway /
 * PreferenceStore），不直接持有 Context；旧构造 {@code (Context, Host)}
 * 保留兼容（内部走 AppServices.ensure）。</p>
 */
public class ChatSpeechInputController {

    private static final String KEY_AUTO_TTS = "auto_tts_enabled";
    private static final String FEATURE_ASR = "asr";

    /** 宿主回调 */
    public interface Host {
        void onToast(int resId);
        void onToastString(String message);
        void runOnUi(Runnable r);
        /** 识别完成文本（宿主填入输入框待确认） */
        void onRecognizedText(String text);
        /** 语音按钮可用性变化 */
        void onVoiceButtonEnabled(boolean enabled);
        /** 自动 TTS 开关变化（宿主刷新 UI） */
        void onAutoTtsChanged(boolean enabled);
        /** 请求麦克风权限（宿主实现，授权成功回调 onGranted） */
        void requestMicrophonePermission(Runnable onGranted);
    }

    private final Host host;
    private boolean autoTtsEnabled;

    public ChatSpeechInputController(Context context, Host host) {
        AppServices.ensure(context);
        this.host = host;
        this.autoTtsEnabled = AppServices.prefs().getBoolean(KEY_AUTO_TTS, false);
    }

    public boolean isAutoTtsEnabled() { return autoTtsEnabled; }

    /** 语音识别入口：先申请权限，授权后执行识别流程 */
    public void startVoiceRecognition() {
        host.requestMicrophonePermission(() -> doVoiceRecognition());
    }

    /** 执行识别：ASR 可用性判定 + 本地模型预热 + voice_input 工具 */
    public void doVoiceRecognition() {
        SpeechGateway speech = AppServices.speech();
        if (!speech.isAnyAsrAvailable()) {
            host.onToast(R.string.h_b2f5500b);
            host.onVoiceButtonEnabled(false);
            return;
        }
        final boolean useLocal = isLocalAsrSelected() || !speech.isAsrAvailable();
        if (useLocal && !speech.isLocalAsrReady()) {
            speech.acquireLocalAsr(() -> host.runOnUi(() -> {
                host.onToast(R.string.h_e6f7a8b9);
                startVoiceRecorderTool();
            }), e -> host.runOnUi(() ->
                    host.onToastString("本地语音识别模型加载失败: " + e.getMessage())));
        } else {
            startVoiceRecorderTool();
        }
    }

    /** 调用 voice_input 工具 record 动作（后台线程：录音组件→识别→文本） */
    public void startVoiceRecorderTool() {
        new Thread(() -> {
            try {
                Map<String, Object> params = new HashMap<>();
                params.put("action", "record");
                params.put("duration_seconds", 60);
                params.put("timeout_seconds", 90);
                VoiceInputTool tool = new VoiceInputTool(AppServices.appContext());
                AIToolResult r = tool.execute(params);
                if (r != null && r.isSuccess()) {
                    final String text = r.getAdditionalInfo() != null
                            ? String.valueOf(r.getAdditionalInfo().get("text")) : "";
                    host.runOnUi(() -> host.onRecognizedText(text));
                } else {
                    final String err = (r != null && r.getErrorMessage() != null)
                            ? r.getErrorMessage() : "语音识别失败";
                    host.runOnUi(() -> host.onToastString(err));
                }
            } catch (Exception e) {
                android.util.Log.e("ChatSpeechInput", "语音识别工具失败: " + e.getMessage());
                host.runOnUi(() -> host.onToastString("语音识别失败: " + e.getMessage()));
            }
        }).start();
    }

    /** 用户是否在"功能专用模型"中显式选择了本地 SenseVoice（→ 识别本地优先） */
    public boolean isLocalAsrSelected() {
        try {
            String id = AppServices.models().getFeatureModelId(FEATURE_ASR);
            return SpeechManager.LOCAL_ASR_ID.equals(id);
        } catch (Exception e) {
            return false;
        }
    }

    /** 切换自动 TTS（持久化），返回新状态 */
    public boolean toggleAutoTts() {
        autoTtsEnabled = !autoTtsEnabled;
        AppServices.prefs().putBoolean(KEY_AUTO_TTS, autoTtsEnabled);
        host.onAutoTtsChanged(autoTtsEnabled);
        return autoTtsEnabled;
    }
}
