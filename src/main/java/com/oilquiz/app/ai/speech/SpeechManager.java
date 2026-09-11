package com.oilquiz.app.ai.speech;

import android.content.Context;
import android.net.Uri;

import com.oilquiz.app.ai.model.OnlineModelManager;

import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 语音能力统一门面（Speech Facade）
 *
 * 整合语音识别（ASR）与语音合成（TTS）两大能力，对外提供简洁统一的调用接口。
 * 用户只需在模型配置中为"语音识别"或"语音合成"选择对应的在线模型即可使用，
 * 未配置时自动选择支持音频的模型，TTS 在线不可用时自动回退系统 TTS。
 *
 * 使用示例：
 * <pre>
 * // 语音识别（音频文件转文字）
 * SpeechManager.getInstance(context)
 *     .recognizeSpeech(audioFile, null)
 *     .thenAccept(result -> Log.d("ASR", result.text));
 *
 * // 语音合成并播放（文字转语音）
 * SpeechManager.getInstance(context).speak("你好，世界！", null);
 * </pre>
 */
public class SpeechManager {

    private static final String TAG = "SpeechManager";

    private final Context context;
    private final SpeechRecognitionService asrService;
    private final TTSService ttsService;
    private volatile LocalAsrRecognizer localRecognizer;      // 本地离线 ASR（SenseVoice，App 前台录音，绕开系统后台限制）

    /**
     * 本地 SenseVoice 离线语音识别开关。
     * true=使用本地 SenseVoice（完全离线，绕开系统识别服务与后台限制）；false=禁用。
     * 2026-09-10 应恢复：此前曾按要求禁用（false），现已重新启用本地语音识别。
     */
    private static final boolean LOCAL_ASR_ENABLED = true;
    /** 功能专用模型里"本地 SenseVoice"的端点 ID 标记（与 SpeechModelSelectorDialog/VoiceInputTool 共享） */
    public static final String LOCAL_ASR_ID = "local";
    /** 功能专用模型里"系统语音合成"的端点 ID 标记（与 SpeechModelSelectorDialog/TTSService 共享） */
    public static final String LOCAL_TTS_ID = "local_tts";
    private volatile SystemSpeechRecognizer offlineRecognizer; // 系统语音识别兜底（本地模型不可用时）
    private final ExecutorService executor = Executors.newCachedThreadPool(); // 本地文件识别专用（解码+推理）

    /** 录音占用者："app"=应用层录音按钮 / "agent"=Agent语音输入组件，同一时间只允许一方录音 */
    private volatile String recordingOwner = null;

    /**
     * 尝试获取麦克风录音权（应用层与 Agent 互斥）。
     * @param owner "app" 或 "agent"
     * @return true 获取成功（或已持有同一方）
     */
    public synchronized boolean tryAcquireRecording(String owner) {
        if (owner == null) return false;
        if (recordingOwner == null || recordingOwner.equals(owner)) {
            recordingOwner = owner;
            return true;
        }
        return false;
    }

    /** 释放录音权（仅同一占用者可释放） */
    public synchronized void releaseRecording(String owner) {
        if (owner != null && owner.equals(recordingOwner)) {
            recordingOwner = null;
        }
    }

    /** 当前录音占用者（null=空闲；"app"/"agent"） */
    public String getRecordingOwner() {
        return recordingOwner;
    }

    private static volatile SpeechManager INSTANCE;

    private SpeechManager(Context context) {
        this.context = context.getApplicationContext();
        this.asrService = SpeechRecognitionService.getInstance(context);
        this.ttsService = TTSService.getInstance(context);
    }

    public static SpeechManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (SpeechManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new SpeechManager(context);
                }
            }
        }
        return INSTANCE;
    }

    // ==================== 语音识别（ASR） ====================

    /**
     * 语音识别：音频文件转文字
     *
     * @param audioFile 音频文件（mp3/m4a/wav 等）
     * @param language  语言提示（如 zh/en），null 表示自动检测
     */
    public CompletableFuture<SpeechRecognitionService.RecognitionResult> recognizeSpeech(
            File audioFile, String language) {
        applyAsrModelConfig();
        return asrService.recognizeAsync(audioFile, language);
    }

    /**
     * 语音识别：content:// URI 音频转文字
     */
    public CompletableFuture<SpeechRecognitionService.RecognitionResult> recognizeSpeech(
            Uri audioUri, String language) {
        applyAsrModelConfig();
        return asrService.recognizeAsync(audioUri, language);
    }

    /** 是否有可用的在线语音识别模型 */
    public boolean isAsrAvailable() {
        return asrService.isAvailable();
    }

    /**
     * 是否有任何可用的语音识别能力（在线模型 OR 本地 SenseVoice OR 系统识别服务）。
     * 供 Agent 语音工具判定：用户启用本地语音识别后，即使未配置在线 ASR 模型也可用。
     */
    public boolean isAnyAsrAvailable() {
        try {
            if (isAsrAvailable()) return true;
            return isOfflineAsrAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 本地语音识别：解码音频文件（m4a/mp3/wav 等）为 16kHz PCM 后交给端侧 SenseVoice。
     * 供 Agent 录音落盘后的本地识别（不依赖在线 ASR 配置）。
     */
    public CompletableFuture<SpeechRecognitionService.RecognitionResult> recognizeSpeechLocal(
            final File audioFile, final String language) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!LOCAL_ASR_ENABLED) {
                    throw new Exception("本地语音识别已禁用（LOCAL_ASR_ENABLED=false）");
                }
                if (audioFile == null || !audioFile.exists()) {
                    throw new Exception("音频文件不存在: " + (audioFile != null ? audioFile.getAbsolutePath() : "null"));
                }
                float[] pcm = AudioPcmDecoder.decodeFile(context, audioFile);
                if (pcm == null || pcm.length < AudioPcmDecoder.TARGET_SAMPLE_RATE / 4) {
                    throw new Exception("音频过短或未检测到有效语音");
                }
                // 占用模型识别（与流式识别共用实例，识别结束释放；避免并发识别被中途卸载）
                com.oilquiz.app.ai.speech.asr.SenseVoiceAsr asr =
                        com.oilquiz.app.ai.speech.asr.SenseVoiceAsr.acquire(context);
                try {
                    String text = asr.recognize(pcm, pcm.length);
                    if (text == null || text.trim().isEmpty()) {
                        throw new Exception("未识别到语音内容（音频可能无有效人声或格式不支持）");
                    }
                    return new SpeechRecognitionService.RecognitionResult(text.trim(), "SenseVoice(本地)");
                } finally {
                    com.oilquiz.app.ai.speech.asr.SenseVoiceAsr.release();
                }
            } catch (Exception e) {
                AILogger.e(TAG, "本地文件识别失败: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    /** 本地语音识别：content:// URI 版本 */
    public CompletableFuture<SpeechRecognitionService.RecognitionResult> recognizeSpeechLocal(
            final Uri audioUri, final String language) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!LOCAL_ASR_ENABLED) {
                    throw new Exception("本地语音识别已禁用（LOCAL_ASR_ENABLED=false）");
                }
                float[] pcm = AudioPcmDecoder.decodeUri(context, audioUri);
                if (pcm == null || pcm.length < AudioPcmDecoder.TARGET_SAMPLE_RATE / 4) {
                    throw new Exception("音频过短或未检测到有效语音");
                }
                // 占用模型识别（与流式识别共用实例，识别结束释放；避免并发识别被中途卸载）
                com.oilquiz.app.ai.speech.asr.SenseVoiceAsr asr =
                        com.oilquiz.app.ai.speech.asr.SenseVoiceAsr.acquire(context);
                try {
                    String text = asr.recognize(pcm, pcm.length);
                    if (text == null || text.trim().isEmpty()) {
                        throw new Exception("未识别到语音内容（音频可能无有效人声或格式不支持）");
                    }
                    return new SpeechRecognitionService.RecognitionResult(text.trim(), "SenseVoice(本地)");
                } finally {
                    com.oilquiz.app.ai.speech.asr.SenseVoiceAsr.release();
                }
            } catch (Exception e) {
                AILogger.e(TAG, "本地文件识别失败: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    // ---------- 离线/系统语音识别兜底 ----------

    /**
     * 设备是否有可用的系统语音识别服务（离线兜底）。
     * 真实检测设备上是否注册了系统 RecognitionService（如小米小爱语音引擎的
     * com.xiaomi.mibrain.speech/.asr.AsrService），而非写死禁用。
     */
    public boolean isOfflineAsrAvailable() {
        try {
            // 本地 SenseVoice 已禁用（LOCAL_ASR_ENABLED=false），仅检查系统识别服务
            if (!LOCAL_ASR_ENABLED) {
                return SystemSpeechRecognizer.isAvailable(context);
            }
            // 优先本地 SenseVoice（完全离线，无系统服务限制）
            if (localRecognizer == null) {
                localRecognizer = new LocalAsrRecognizer(context);
            }
            if (localRecognizer.isAvailable()) {
                return true;
            }
            // 本地模型未就绪时回退检查系统识别服务
            return SystemSpeechRecognizer.isAvailable(context);
        } catch (Exception e) {
            AILogger.w(TAG, "检查离线语音识别可用性失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 启动离线/系统语音识别（实时监听麦克风）
     * 用于在线 ASR 不可用或调用失败时的兜底；需用户重新说话。
     * 修复：恢复被禁用的系统语音识别兜底，不再强制要求配置在线语音识别模型。
     */
    public void startOfflineRecognition(SystemSpeechRecognizer.RecognitionCallback callback) {
        try {
            // 本地 SenseVoice 已禁用：直接走系统语音识别兜底
            if (!LOCAL_ASR_ENABLED) {
                if (!SystemSpeechRecognizer.isAvailable(context)) {
                    if (callback != null) {
                        callback.onError("本地语音识别已禁用且系统识别不可用，请配置在线语音识别模型");
                        callback.onEnd();
                    }
                    return;
                }
                if (offlineRecognizer == null) {
                    offlineRecognizer = new SystemSpeechRecognizer(context);
                }
                offlineRecognizer.startListening(callback);
                return;
            }
            // 优先本地 SenseVoice（App 前台录音 + 端侧推理，天然绕开 Android 12+ 后台录音限制）
            if (localRecognizer == null) {
                localRecognizer = new LocalAsrRecognizer(context);
            }
            if (localRecognizer.isAvailable()) {
                localRecognizer.startListening(callback);
                return;
            }
            // 本地模型未就绪（如首次加载中/加载失败）：回退系统语音识别
            if (!SystemSpeechRecognizer.isAvailable(context)) {
                if (callback != null) {
                    callback.onError("当前设备没有可用的离线语音识别（本地模型与系统识别均不可用），请稍后重试或配置在线语音识别模型");
                    callback.onEnd();
                }
                return;
            }
            if (offlineRecognizer == null) {
                offlineRecognizer = new SystemSpeechRecognizer(context);
            }
            offlineRecognizer.startListening(callback);
        } catch (Exception e) {
            AILogger.e(TAG, "启动离线语音识别失败: " + e.getMessage(), e);
            if (callback != null) {
                callback.onError("离线语音识别启动失败: " + e.getMessage());
                callback.onEnd();
            }
        }
    }

    /** 停止离线识别（触发最终结果回调） */
    public void stopOfflineRecognition() {
        if (LOCAL_ASR_ENABLED && localRecognizer != null && localRecognizer.isListening()) {
            localRecognizer.stopListening();            return;
        }
        if (offlineRecognizer != null) {
            offlineRecognizer.stopListening();
        }
    }

    /** 取消离线识别并释放 */
    public void cancelOfflineRecognition() {
        if (LOCAL_ASR_ENABLED && localRecognizer != null && localRecognizer.isListening()) {
            localRecognizer.cancel();
            return;
        }
        if (offlineRecognizer != null) {
            offlineRecognizer.cancel();
        }
    }

    /**
     * VC-03 方言/口音适配：设置系统语音识别的语言（普通话 zh-CN / 粤语 yue-HK / 台湾 zh-TW 等）。
     * 须在 startOfflineRecognition 之前调用；识别结束后语言保持，直到再次设置。
     */
    public void setOfflineAsrLanguage(String lang) {
        try {
            if (lang == null || lang.trim().isEmpty()) return;
            if (offlineRecognizer == null) {
                offlineRecognizer = new SystemSpeechRecognizer(context);
            }
            offlineRecognizer.setLanguage(lang.trim());
            AILogger.i(TAG, "离线识别语言已切换: " + lang.trim());
        } catch (Exception e) {
            AILogger.w(TAG, "设置离线识别语言失败: " + e.getMessage());
        }
    }

    /** 是否正在进行离线识别 */
    public boolean isOfflineRecognizing() {
        return (LOCAL_ASR_ENABLED && localRecognizer != null && localRecognizer.isListening())
                || (offlineRecognizer != null && offlineRecognizer.isListening());
    }

    /** 获取当前生效的 ASR 模型显示名（用于 UI 展示） */
    public String getCurrentAsrModelDisplay() {
        try {
            OnlineModelManager manager = OnlineModelManager.getInstance(context);
            String featureId = manager.getFeatureModelId(OnlineModelManager.FEATURE_ASR);
            // 显式选择本地 SenseVoice（内置离线识别，无需在线配置）
            if (LOCAL_ASR_ID.equals(featureId)) {
                return "本地 SenseVoice（内置离线）";
            }
            String featureName = manager.getFeatureModelName(OnlineModelManager.FEATURE_ASR);
            if (featureName != null && !featureName.isEmpty()) {
                return featureName;
            }
            OnlineModelManager.OnlineModelConfig config =
                    manager.getFeatureModel(OnlineModelManager.FEATURE_ASR);
            if (config != null) {
                return config.selectedModel != null ? config.selectedModel : config.modelName;
            }
            // 未配置专用模型：本地可用时提示自动走本地
            if (isOfflineAsrAvailable()) {
                return "本地 SenseVoice（自动）";
            }
        } catch (Exception ignored) {
        }
        return "自动选择";
    }

    // ==================== 语音合成（TTS） ====================

    /**
     * 语音合成：文字转音频文件
     * 在线可用时使用在线模型，否则回退系统 TTS
     */
    public CompletableFuture<TTSService.SynthesisResult> synthesizeSpeech(String text) {
        applyTtsModelConfig();
        return ttsService.synthesizeAsync(text);
    }

    /**
     * 语音合成（可强制仅在线）：forceOnline=true 时失败不回退系统TTS，
     * 用于流式朗读锁定同一音色，避免在线/系统音色句间混用
     */
    public CompletableFuture<TTSService.SynthesisResult> synthesizeSpeech(String text, boolean forceOnline) {
        applyTtsModelConfig();
        return ttsService.synthesizeAsync(text, null, forceOnline);
    }

    /** 是否有可用的在线 TTS 模型配置 */
    public boolean isOnlineTtsAvailable() {
        try {
            return ttsService.isOnlineAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    /** 当前生效的音色是否为系统 TTS 引擎音色（sys: 前缀） */
    public boolean isCurrentTtsVoiceSystem() {
        try {
            return ttsService.isCurrentVoiceSystem();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 语音合成并播放
     *
     * @param text     待朗读文本
     * @param callback 播放状态回调（可为 null）
     */
    public void speak(String text, TTSService.PlaybackCallback callback) {
        applyTtsModelConfig();
        ttsService.speak(text, callback);
    }

    /**
     * 直接播放已合成的音频文件（流式预合成场景：合成与播放分离，消除句间等待）
     */
    public void playAudioFile(java.io.File audioFile, TTSService.PlaybackCallback callback) {
        ttsService.playAudio(audioFile, callback);
    }

    /**
     * 锁定引擎朗读：在线可用时全程在线音色合成（同一条消息音色始终一致），
     * 在线失败才整体改用系统TTS——避免同一条朗读中在线/系统音色混用
     */
    public void speakLocked(String text, TTSService.PlaybackCallback callback) {
        applyTtsModelConfig();
        // 选择了系统TTS音色或无在线模型：全程系统引擎（音色仍保持一致）
        if (!ttsService.isOnlineAvailable() || ttsService.isCurrentVoiceSystem()) {
            ttsService.speak(text, callback);
            return;
        }
        ttsService.synthesizeAsync(text, null, true).whenComplete((result, throwable) -> {
            if (throwable == null && result != null && result.audioFile != null) {
                ttsService.playAudio(result.audioFile, callback);
                return;
            }
            // 在线失败：整条改用系统TTS（仍保证全程单一引擎）
            ttsService.synthesizeAsync(text).whenComplete((r2, t2) -> {
                if (t2 == null && r2 != null && r2.audioFile != null) {
                    ttsService.playAudio(r2.audioFile, callback);
                } else if (callback != null) {
                    callback.onError("语音合成失败");
                }
            });
        });
    }

    /** 停止语音播放 */
    public void stopSpeaking() {
        ttsService.stopPlayback();
    }

    /** 是否正在播放 */
    public boolean isSpeaking() {
        return ttsService.isPlaying();
    }
    
    /** 设置 TTS 音色（如 alloy/echo/nova/shimmer，仅当前会话生效，不持久化） */
    public void setTtsVoice(String voice) {
        ttsService.setVoice(voice);
    }

    /**
     * 保存默认音色（持久化，后续合成/朗读默认使用）
     * 传 null 清除，恢复默认音色
     */
    public void saveTtsVoice(String voiceId) {
        try {
            OnlineModelManager.getInstance(context).setTtsVoice(voiceId);
        } catch (Exception ignored) {
        }
    }

    /** 获取当前生效的音色 ID */
    public String getCurrentTtsVoice() {
        return ttsService.getCurrentVoice();
    }

    /** 获取用户已保存的音色 ID（未设置返回 null，不回填默认值避免误导） */
    public String getSavedTtsVoice() {
        try {
            return OnlineModelManager.getInstance(context).getTtsVoice();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 异步获取可用音色列表（优先从在线端点自动拉取，失败时返回内置预设）
     */
    public CompletableFuture<java.util.List<TTSService.Voice>> fetchVoicesAsync() {
        applyTtsModelConfig();
        return ttsService.fetchVoicesAsync();
    }

    /**
     * 使用指定音色试听一段文本（不改变默认音色配置）
     * 强制仅在线合成：系统TTS不支持音色切换，回退会导致试听听不出音色差异
     */
    public void previewVoice(String voiceId, TTSService.PlaybackCallback callback) {
        applyTtsModelConfig();
        ttsService.speakWithVoice("你好，这是音色试听效果。", voiceId, callback, true);
    }

    /** 获取当前生效的 TTS 模型显示名（用于 UI 展示） */
    public String getCurrentTtsModelDisplay() {
        try {
            OnlineModelManager manager = OnlineModelManager.getInstance(context);
            String featureId = manager.getFeatureModelId(OnlineModelManager.FEATURE_TTS);
            // 显式选择系统语音合成（内置，无需在线模型配置）
            if (LOCAL_TTS_ID.equals(featureId)) {
                return "系统语音合成（内置）";
            }
            String featureName = manager.getFeatureModelName(OnlineModelManager.FEATURE_TTS);
            if (featureName != null && !featureName.isEmpty()) {
                return featureName;
            }
            OnlineModelManager.OnlineModelConfig config =
                    manager.getFeatureModel(OnlineModelManager.FEATURE_TTS);
            if (config != null) {
                return config.selectedModel != null ? config.selectedModel : config.modelName;
            }
            // 未配置专用模型：在线不可用时自动回退系统 TTS
            if (!isOnlineTtsAvailable()) {
                return "系统语音合成（自动）";
            }
        } catch (Exception ignored) {
        }
        return "自动选择";
    }

    // ==================== 内部方法 ====================

    /**
     * 应用用户配置的 ASR 专用模型（未配置则自动选择）
     * 显式选择本地 SenseVoice（LOCAL_ASR_ID）时不改动在线 ASR 服务模型名
     */
    private void applyAsrModelConfig() {
        try {
            OnlineModelManager manager = OnlineModelManager.getInstance(context);
            String featureId = manager.getFeatureModelId(OnlineModelManager.FEATURE_ASR);
            if (LOCAL_ASR_ID.equals(featureId)) {
                return; // 本地识别专用：在线 ASR 配置保持不动
            }
            asrService.setAsrModel(manager.getFeatureModelName(OnlineModelManager.FEATURE_ASR));
        } catch (Exception ignored) {
        }
    }

    /**
     * 应用用户配置的 TTS 专用模型（未配置则自动选择）
     * 显式选择"系统语音合成"（LOCAL_TTS_ID）时强制走系统 TTS，不再使用在线模型
     */
    private void applyTtsModelConfig() {
        try {
            OnlineModelManager manager = OnlineModelManager.getInstance(context);
            String featureId = manager.getFeatureModelId(OnlineModelManager.FEATURE_TTS);
            if (LOCAL_TTS_ID.equals(featureId)) {
                ttsService.setForceSystemTts(true);
                return;
            }
            ttsService.setForceSystemTts(false);
            ttsService.setTtsModel(manager.getFeatureModelName(OnlineModelManager.FEATURE_TTS));
        } catch (Exception ignored) {
        }
    }

    /**
     * 释放所有语音资源
     */
    public void shutdown() {
        asrService.shutdown();
        ttsService.shutdown();
    }
}
