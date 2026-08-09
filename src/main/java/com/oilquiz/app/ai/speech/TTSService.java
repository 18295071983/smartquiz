package com.oilquiz.app.ai.speech;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.speech.CosyVoiceWebSocketClient;
import com.oilquiz.app.ai.util.SSLSocketFactoryUtil;
import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/**
 * 语音合成服务（TTS，Text-to-Speech）
 *
 * 优先调用 OpenAI 兼容格式的 /audio/speech 接口合成高质量语音（返回音频二进制），
 * 在线不可用时自动回退到 Android 系统 TTS 引擎。
 *
 * 设计原则（与 OnlineOCRService / SpeechRecognitionService 保持一致）：
 * 1. 优先选择支持音频能力的模型配置
 * 2. 按模型名启发式识别 TTS 模型（tts/cosyvoice/sambert 等）
 * 3. 合成结果缓存为本地音频文件，通过 MediaPlayer 播放
 * 4. 系统 TTS 兜底保证离线可用
 */
public class TTSService {

    private static final String TAG = "TTSService";
    private static final int DEFAULT_TIMEOUT_MS = 120000; // 120s，长文本合成可能较慢
    private static final int VOICES_TIMEOUT_MS = 15000;   // 拉取音色列表超时
    private static final String ENDPOINT = "/audio/speech";
    private static final String ENDPOINT_VOICES = "/audio/speech/voices";
    /** 百炼/DashScope 原生非实时语音合成接口路径（Qwen-TTS/CosyVoice 不提供 OpenAI 兼容 /audio/speech） */
    private static final String DASHSCOPE_NATIVE_TTS_PATH = "/api/v1/services/audio/tts/SpeechSynthesizer";

    /** 默认 TTS 模型与音色(可覆盖) */
    private static final String DEFAULT_TTS_MODEL = "tts-1";
    private static final String DEFAULT_VOICE = "alloy";
    /** 系统 TTS 引擎音色 ID 前缀(与在线音色区分,如 "sys:cmn-cn-…") */
    public static final String SYS_VOICE_PREFIX = "sys:";
        
    /** 是否启用 CosyVoice WebSocket 实时语音合成 */
    // 禁用了 WebSocket TTS，使用系统 TTS
    private volatile boolean enableCosyVoiceWebSocket = false;

    /**
     * 音色信息
     */
    public static class Voice {
        public final String id;     // 传给 API 的音色 ID
        public final String name;   // 显示名称

        public Voice(String id, String name) {
            this.id = id;
            this.name = (name == null || name.isEmpty()) ? id : name;
        }
    }

    /**
     * 内置常用音色预设（在线拉取失败且模型未命中注册表时的最终兜底）：
     * 来自 SpeechModelRegistry 全部已注册模型的音色并集
     */
    public static List<Voice> getPresetVoices() {
        return SpeechModelRegistry.getAllPresetVoices();
    }

    /**
     * 语音合成结果
     */
    public static class SynthesisResult {
        public final File audioFile;        // 合成的音频文件（mp3/wav）
        public final String modelName;      // 使用的模型名称
        public final boolean fromSystemTts; // 是否来自系统 TTS 兜底
        public final long timestamp;

        public SynthesisResult(File audioFile, String modelName, boolean fromSystemTts) {
            this.audioFile = audioFile;
            this.modelName = modelName;
            this.fromSystemTts = fromSystemTts;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * 播放状态回调
     */
    public interface PlaybackCallback {
        void onStart();
        void onComplete();
        void onError(String error);
    }

    private final Context context;
    private final ExecutorService executor;
    private final Gson gson;

    /** 用户指定的 TTS 模型名与音色（为 null 时使用默认值） */
    private volatile String ttsModelOverride = null;
    private volatile String voice = DEFAULT_VOICE;

    /** 系统 TTS 引擎（懒加载） */
    private volatile TextToSpeech systemTts;
    private final AtomicBoolean systemTtsReady = new AtomicBoolean(false);

    private MediaPlayer mediaPlayer;

    private static volatile TTSService INSTANCE;

    private TTSService(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "TTS-Worker");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        });
        this.gson = new Gson();
    }

    public static TTSService getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (TTSService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new TTSService(context);
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 设置 TTS 模型名覆盖（例如 tts-1 / tts-1-hd / cosyvoice-v2）
     * 传 null 恢复自动选择
     */
    public void setTtsModel(String modelName) {
        this.ttsModelOverride = modelName;
    }

    /**
     * 设置音色（例如 alloy / echo / fable / onyx / nova / shimmer）
     */
    public void setVoice(String voice) {
        if (voice != null && !voice.isEmpty()) {
            this.voice = voice;
        }
    }

    /**
     * 异步合成文本为音频文件
     * 在线可用时使用在线 API，否则回退到系统 TTS
     *
     * @param text 待合成文本
     * @return 合成结果（包含音频文件）
     */
    public CompletableFuture<SynthesisResult> synthesizeAsync(String text) {
        return synthesizeAsync(text, null, false);
    }

    /**
     * 异步合成（指定音色；传 null 使用用户配置的默认音色）
     */
    public CompletableFuture<SynthesisResult> synthesizeAsync(String text, String voiceOverride) {
        return synthesizeAsync(text, voiceOverride, false);
    }

    /**
     * 异步合成
     *
     * @param text          待合成文本
     * @param voiceOverride 音色覆盖（null 用默认）
     * @param forceOnline   强制仅使用在线合成（用于音色试听，避免系统TTS回退导致音色不生效）；
     *                      在线失败时直接报错，不回退
     */
    public CompletableFuture<SynthesisResult> synthesizeAsync(String text, String voiceOverride, boolean forceOnline) {
        return CompletableFuture.supplyAsync(() -> {
            if (text == null || text.trim().isEmpty()) {
                throw new IllegalArgumentException("合成文本不能为空");
            }

            // 1. 优先在线合成（选择了系统TTS音色时跳过在线，直接走系统合成）
            OnlineModelManager.OnlineModelConfig config = selectTtsModel();
            String actualVoice = resolveVoice(voiceOverride);
            boolean systemVoiceSelected = actualVoice != null && actualVoice.startsWith(SYS_VOICE_PREFIX);
            if (config != null && !systemVoiceSelected) {
                try {
                    String featureModelName = OnlineModelManager.getInstance(context)
                            .getFeatureModelName(OnlineModelManager.FEATURE_TTS);
                    String modelName = resolveModelName(config,
                            featureModelName != null ? featureModelName : ttsModelOverride);
                    AILogger.i(TAG, "TTS using model: " + modelName + ", voice: " + actualVoice
                            + " @ " + config.name);
                    File audioFile = callSpeechAPI(config, modelName, text, actualVoice);
                    notifyEngine("在线语音合成");
                    return new SynthesisResult(audioFile, modelName, false);
                } catch (Exception e) {
                    AILogger.w(TAG, "在线TTS失败: " + e.getMessage());
                    if (forceOnline) {
                        // 试听等场景：不回退系统TTS（系统TTS无音色概念，回退会导致音色切换不生效）
                        throw new java.util.concurrent.CompletionException(
                                "在线语音合成失败: " + e.getMessage(), e);
                    }
                    notifyEngine("系统语音合成");
                }
            } else if (forceOnline && config == null) {
                throw new java.util.concurrent.CompletionException(
                        "未配置可用的在线语音合成模型，请在模型管理页设置语音合成模型", null);
            }

            // 2. 回退系统 TTS（选择了系统音色时应用该音色）
            try {
                File audioFile = synthesizeWithSystemTts(text, actualVoice);
                return new SynthesisResult(audioFile, "system-tts", true);
            } catch (Exception e) {
                notifyEngine("系统语音合成不可用");
                throw new java.util.concurrent.CompletionException("语音合成失败: " + e.getMessage(), e);
            }
        }, executor);
    }

    /** 在主线程简洁提示当前使用的语音合成引擎（引擎未变化时不重复提示，避免流式逐句刷屏） */
    private volatile String lastNotifiedEngine;

    private void notifyEngine(String engine) {
        if (engine != null && engine.equals(lastNotifiedEngine)) {
            return;
        }
        lastNotifiedEngine = engine;
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(context, "使用" + engine, Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    /**
     * 解析实际使用的音色：试听覆盖 > 用户保存的音色 > 自动检测模型默认音色
     * 同一次配置下返回值始终一致，保证逐句合成音色不变
     */
    private String resolveVoice(String voiceOverride) {
        if (voiceOverride != null && !voiceOverride.isEmpty()) {
            return voiceOverride;
        }
        try {
            String saved = OnlineModelManager.getInstance(context).getTtsVoice();
            if (saved != null && !saved.isEmpty()) {
                return saved;
            }
        } catch (Exception ignored) {
        }
        // 未保存音色时，自动根据配置的模型选择匹配的默认音色
        try {
            String autoVoice = detectDefaultVoiceForModel();
            if (autoVoice != null) {
                AILogger.i(TAG, "自动检测到模型对应默认音色: " + autoVoice);
                return autoVoice;
            }
        } catch (Exception ignored) {
        }
        return voice != null && !voice.isEmpty() ? voice : DEFAULT_VOICE;
    }

    /**
     * 根据当前配置的 TTS 模型自动检测匹配的默认音色
     * 优先级：从 SpeechModelRegistry 查询模型对应的第一个音色（推荐）
     */
    private String detectDefaultVoiceForModel() {
        try {
            OnlineModelManager manager = OnlineModelManager.getInstance(context);
            String featureModelName = manager.getFeatureModelName(OnlineModelManager.FEATURE_TTS);
            if (featureModelName == null || featureModelName.isEmpty()) {
                // 如果没有专用模型，尝试活动模型
                OnlineModelManager.OnlineModelConfig activeConfig = manager.getActiveModel();
                if (activeConfig != null) {
                    featureModelName = activeConfig.selectedModel != null 
                            ? activeConfig.selectedModel 
                            : activeConfig.modelName;
                }
            }
            if (featureModelName == null || featureModelName.isEmpty()) {
                return null;
            }
            // 从注册表查询该模型的默认音色
            List<Voice> matchedVoices = SpeechModelRegistry.getVoicesForModel(featureModelName);
            if (matchedVoices != null && !matchedVoices.isEmpty()) {
                return matchedVoices.get(0).id; // 返回第一个（推荐）音色
            }
        } catch (Exception e) {
            AILogger.w(TAG, "自动检测默认音色失败: " + e.getMessage());
        }
        return null;
    }

    /** 是否有可用的在线 TTS 模型配置（用于流式朗读锁定引擎，避免在线/系统音色混用） */
    public boolean isOnlineAvailable() {
        try {
            return selectTtsModel() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 获取当前生效的音色 ID */
    public String getCurrentVoice() {
        return resolveVoice(null);
    }

    /** 当前生效的音色是否为系统 TTS 引擎音色 */
    public boolean isCurrentVoiceSystem() {
        String v = resolveVoice(null);
        return v != null && v.startsWith(SYS_VOICE_PREFIX);
    }

    /**
     * 合成并直接播放
     *
     * @param text     待合成文本
     * @param callback 播放状态回调（可为 null）
     */
    public void speak(String text, PlaybackCallback callback) {
        speakWithVoice(text, null, callback);
    }

    /**
     * 使用指定音色合成并播放（voiceId 为 null 时使用默认音色）
     *
     * @param forceOnline 强制仅在线合成（音色试听用，系统TTS不支持音色切换）
     */
    public void speakWithVoice(String text, String voiceId, PlaybackCallback callback, boolean forceOnline) {
        synthesizeAsync(text, voiceId, forceOnline).whenComplete((result, throwable) -> {
            if (throwable != null) {
                if (callback != null) {
                    String msg = throwable.getCause() != null && throwable.getCause().getMessage() != null
                            ? throwable.getCause().getMessage()
                            : (throwable.getMessage() != null ? throwable.getMessage() : "语音合成失败");
                    callback.onError(msg);
                }
                return;
            }
            playAudio(result.audioFile, callback);
        });
    }

    public void speakWithVoice(String text, String voiceId, PlaybackCallback callback) {
        speakWithVoice(text, voiceId, callback, false);
    }

    /**
     * 异步获取可用音色列表
     * 优先按当前模型名匹配内置注册表的配套音色，其次尝试从端点 API 拉取，
     * 最后使用全部注册表音色并集兜底
     */
    public CompletableFuture<List<Voice>> fetchVoicesAsync() {
        return CompletableFuture.supplyAsync(() -> {
            List<Voice> voices = new ArrayList<>();
            OnlineModelManager.OnlineModelConfig config = selectTtsModel();
            String modelName = null;
            if (config != null) {
                try {
                    modelName = resolveModelName(config,
                            OnlineModelManager.getInstance(context).getFeatureModelName(OnlineModelManager.FEATURE_TTS));
                } catch (Exception ignored) {
                }
            }
            // 1. 按模型名自动路由到内置注册表的配套音色（最可靠，避免显示不可用音色）
            List<Voice> registryVoices = SpeechModelRegistry.getVoicesForModel(modelName);
            if (!registryVoices.isEmpty()) {
                SpeechModelRegistry.ModelEntry entry = SpeechModelRegistry.matchTtsModel(modelName);
                AILogger.i(TAG, "音色列表自动路由到内置资源: model=" + modelName
                        + " (" + (entry != null ? entry.displayName : "") + ") 共 " + registryVoices.size() + " 个");
                voices.addAll(registryVoices);
            } else if (config != null) {
                // 2. 注册表未收录：尝试从端点 API 拉取
                try {
                    List<Voice> apiVoices = fetchVoicesFromApi(config);
                    if (apiVoices != null && !apiVoices.isEmpty()) {
                        AILogger.i(TAG, "从端点获取到 " + apiVoices.size() + " 个音色");
                        voices.addAll(apiVoices);
                    }
                } catch (Exception e) {
                    AILogger.w(TAG, "拉取音色列表失败，使用预设: " + e.getMessage());
                }
            }
            if (voices.isEmpty()) {
                voices.addAll(getPresetVoices());
            }
            // 追加系统 TTS 引擎内置音色（离线也可选，sys: 前缀区分）
            try {
                List<Voice> systemVoices = getSystemVoices();
                if (!systemVoices.isEmpty()) {
                    voices.addAll(systemVoices);
                }
            } catch (Exception ignored) {
            }
            return voices;
        }, executor);
    }

    /**
     * 从在线端点拉取音色列表：不同服务商实现不一致，依次尝试：
     * 1. /audio/speech/voices?model=xxx（DashScope 兼容模式需要 model 参数）
     * 2. /audio/speech/voices
     * 3. /audio/voices
     * 并记录响应便于排查
     */
    private List<Voice> fetchVoicesFromApi(OnlineModelManager.OnlineModelConfig config) throws Exception {
        Exception lastError = null;
        String model = null;
        try {
            model = resolveModelName(config,
                    OnlineModelManager.getInstance(context).getFeatureModelName(OnlineModelManager.FEATURE_TTS));
        } catch (Exception ignored) {
        }
        List<String> endpoints = new ArrayList<>();
        if (model != null && !model.isEmpty()) {
            endpoints.add(ENDPOINT_VOICES + "?model=" + model);
        }
        endpoints.add(ENDPOINT_VOICES);
        endpoints.add("/audio/voices");
        for (String endpoint : endpoints) {
            try {
                String body = httpGetVoices(config, endpoint);
                AILogger.i(TAG, "音色列表响应(" + endpoint + "): "
                        + (body.length() > 300 ? body.substring(0, 300) + "..." : body));
                List<Voice> voices = parseVoicesResponse(body);
                if (!voices.isEmpty()) {
                    return voices;
                }
                lastError = new Exception("响应中未包含音色数据");
            } catch (Exception e) {
                lastError = e;
                AILogger.w(TAG, "拉取音色列表失败(" + endpoint + "): " + e.getMessage());
            }
        }
        throw lastError != null ? lastError : new Exception("获取音色列表失败");
    }

    /** 执行音色列表 GET 请求 */
    private String httpGetVoices(OnlineModelManager.OnlineModelConfig config, String endpoint) throws Exception {
        String apiUrl = config.apiUrl;
        String apiKey = config.apiKey;
        if (apiUrl == null || apiUrl.isEmpty() || apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("API 配置不完整");
        }

        String fullUrl = buildUrl(apiUrl, endpoint);
        URL url = new URL(fullUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (connection instanceof HttpsURLConnection) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation((HttpsURLConnection) connection);
        }

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(VOICES_TIMEOUT_MS);
            connection.setReadTimeout(VOICES_TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Accept", "application/json");

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                throw new Exception("HTTP " + responseCode);
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            return response.toString();
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 解析音色列表响应，兼容多种格式：
     * {"voices":[{"id":"..","name":".."}]} / {"data":[...]} / ["alloy","echo"] / 对象数组
     */
    private List<Voice> parseVoicesResponse(String body) {
        List<Voice> voices = new ArrayList<>();
        try {
            String trimmed = body.trim();
            JsonArray arr = null;
            if (trimmed.startsWith("[")) {
                arr = gson.fromJson(trimmed, JsonArray.class);
            } else if (trimmed.startsWith("{")) {
                JsonObject obj = gson.fromJson(trimmed, JsonObject.class);
                // 兼容常见包装键：voices / data / result / list / items
                for (String key : new String[]{"voices", "data", "result", "list", "items"}) {
                    if (obj.has(key) && obj.get(key).isJsonArray()) {
                        arr = obj.getAsJsonArray(key);
                        break;
                    }
                }
            }
            if (arr == null) return voices;

            for (JsonElement el : arr) {
                if (el.isJsonPrimitive()) {
                    String id = el.getAsString();
                    if (!id.isEmpty()) voices.add(new Voice(id, id));
                } else if (el.isJsonObject()) {
                    JsonObject vo = el.getAsJsonObject();
                    String id = vo.has("id") ? vo.get("id").getAsString()
                            : (vo.has("voice_id") ? vo.get("voice_id").getAsString()
                            : (vo.has("voice") ? vo.get("voice").getAsString()
                            : (vo.has("name") ? vo.get("name").getAsString() : null)));
                    if (id == null || id.isEmpty()) continue;
                    String name = vo.has("display_name") ? vo.get("display_name").getAsString()
                            : (vo.has("name") ? vo.get("name").getAsString() : id);
                    voices.add(new Voice(id, name));
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "解析音色列表失败: " + e.getMessage());
        }
        return voices;
    }

    /**
     * 播放音频文件
     */
    public synchronized void playAudio(File audioFile, PlaybackCallback callback) {
        try {
            stopPlayback();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(audioFile.getAbsolutePath());
            mediaPlayer.setOnPreparedListener(mp -> {
                if (callback != null) callback.onStart();
                mp.start();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                if (callback != null) callback.onComplete();
                releaseMediaPlayer();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                if (callback != null) callback.onError("音频播放失败 (what=" + what + ")");
                releaseMediaPlayer();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            AILogger.e(TAG, "播放音频失败: " + e.getMessage(), e);
            if (callback != null) callback.onError("播放音频失败: " + e.getMessage());
        }
    }

    /**
     * 停止播放
     */
    public synchronized void stopPlayback() {
        releaseMediaPlayer();
    }

    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    private synchronized void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            } catch (Exception ignored) {
            }
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
    }

    // ==================== 在线合成 ====================

    /**
     * 选择 TTS 模型配置
     * 优先级：
     * 0. 用户配置的语音合成专用模型（最高优先级）
     * 1. 激活模型中标记 supportsAudio 的
     * 2. 任意标记 supportsAudio 的启用模型
     * 3. 模型名启发式包含 TTS 关键字的启用模型
     * 4. 当前激活模型（兜底）
     */
    private OnlineModelManager.OnlineModelConfig selectTtsModel() {
        try {
            OnlineModelManager modelManager = OnlineModelManager.getInstance(context);

            // 0. 优先使用用户配置的语音合成专用模型
            OnlineModelManager.OnlineModelConfig ttsModel =
                    modelManager.getFeatureModel(OnlineModelManager.FEATURE_TTS);
            if (ttsModel != null) {
                AILogger.i(TAG, "Using dedicated TTS model: " + ttsModel.name);
                return ttsModel;
            }

            List<OnlineModelManager.OnlineModelConfig> allModels = modelManager.getModelList();
            if (allModels == null || allModels.isEmpty()) {
                return null;
            }

            OnlineModelManager.OnlineModelConfig active = modelManager.getActiveModel();
            if (active != null && active.enabled && active.supportsAudio) {
                return active;
            }

            for (OnlineModelManager.OnlineModelConfig config : allModels) {
                if (config.enabled && config.supportsAudio) {
                    return config;
                }
            }

            for (OnlineModelManager.OnlineModelConfig config : allModels) {
                if (!config.enabled) continue;
                String m = (config.selectedModel != null ? config.selectedModel : config.modelName);
                if (m == null) continue;
                String ml = m.toLowerCase();
                if (ml.contains("tts") || ml.contains("cosyvoice") || ml.contains("sambert")
                        || ml.contains("speech")) {
                    return config;
                }
            }

            if (active != null && active.enabled) {
                return active;
            }
            return null;
        } catch (Exception e) {
            AILogger.e(TAG, "selectTtsModel failed: " + e.getMessage(), e);
            return null;
        }
    }

    private String resolveModelName(OnlineModelManager.OnlineModelConfig config, String modelOverride) {
        if (modelOverride != null && !modelOverride.isEmpty()) {
            return modelOverride;
        }
        String m = config.selectedModel != null ? config.selectedModel : config.modelName;
        // 若端点默认模型明显是聊天模型，仍使用配置值（由端点自行路由），否则用默认 TTS 模型
        return (m != null && !m.isEmpty()) ? m : DEFAULT_TTS_MODEL;
    }

    /**
     * 调用 OpenAI 兼容 /audio/speech 接口,返回合成的音频文件
     */
    private File callSpeechAPI(OnlineModelManager.OnlineModelConfig config,
                               String modelName, String text, String voiceId) throws Exception {
        String apiUrl = config.apiUrl;
        String apiKey = config.apiKey;
        if (apiUrl == null || apiUrl.isEmpty()) throw new IllegalArgumentException("API URL 不能为空");
        if (apiKey == null || apiKey.isEmpty()) throw new IllegalArgumentException("API Key 不能为空");
    
        // 百炼/DashScope 端点的语音合成必须走原生接口,OpenAI 兼容 /audio/speech 路径不存在(404)
        if (isDashScopeEndpoint(apiUrl)) {
            // 1. 优先尝试 CosyVoice WebSocket 实时语音合成(更低延迟)
            try {
                return tryCosyVoiceWebSocketSynthesis(config, modelName, text, voiceId);
            } catch (Exception e) {
                AILogger.w(TAG, "WebSocket TTS 失败: " + e.getMessage() + ",回退到 HTTP 方式");
            }
                
            // 2. 回退到传统 HTTP 非实时语音合成
            return callDashScopeTtsAPI(config, modelName, text, voiceId);
        }

        String fullUrl = buildUrl(apiUrl, ENDPOINT);
        URL url = new URL(fullUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (connection instanceof HttpsURLConnection) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation((HttpsURLConnection) connection);
        }

        try {
            connection.setRequestMethod("POST");
            // 连接超时单独缩短（15s），避免端点不可达时首句长时间无响应；
            // 读超时保持长值以容纳长句合成；keep-alive 复用连接减少每句握手开销
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
            connection.setRequestProperty("Connection", "keep-alive");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setDoOutput(true);

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);
            requestBody.addProperty("input", text);
            requestBody.addProperty("voice", voiceId != null && !voiceId.isEmpty() ? voiceId : DEFAULT_VOICE);
            requestBody.addProperty("response_format", "mp3");

            try (OutputStream os = connection.getOutputStream()) {
                os.write(gson.toJson(requestBody).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                String errorBody = readErrorStream(connection);
                throw new Exception("语音合成请求失败: HTTP " + responseCode + " - " + errorBody);
            }

            // 保存音频二进制到缓存目录
            File audioFile = new File(context.getCacheDir(),
                    "tts_" + System.currentTimeMillis() + ".mp3");
            try (InputStream is = connection.getInputStream();
                 FileOutputStream fos = new FileOutputStream(audioFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }

            if (audioFile.length() == 0) {
                audioFile.delete();
                throw new Exception("语音合成返回空音频");
            }
            return audioFile;
        } finally {
            connection.disconnect();
        }
    }

    // ==================== 百炼/DashScope 原生语音合成 ====================
    
    /**
     * 尝试使用 CosyVoice WebSocket 实时语音合成(低延迟,边合成边返回音频)
     * 如果未启用或不支持,返回 null
     */
    private File tryCosyVoiceWebSocketSynthesis(
            OnlineModelManager.OnlineModelConfig config,
            String modelName, String text, String voiceId) throws Exception {
            
        if (!enableCosyVoiceWebSocket) {
            return null;
        }
            
        try {
            // 提取 Workspace ID 和区域
            String workspaceId = CosyVoiceWebSocketClient.extractWorkspaceId(config.apiUrl);
            String region = CosyVoiceWebSocketClient.extractRegion(config.apiUrl);
                
            if (workspaceId == null || workspaceId.isEmpty()) {
                AILogger.w(TAG, "无法从 API URL 提取 Workspace ID,回退到 HTTP 方式");
                return null;
            }
                
            AILogger.i(TAG, "使用 CosyVoice WebSocket TTS: workspace=" + workspaceId 
                    + ", region=" + region + ", model=" + modelName);
                
            // 创建 WebSocket 客户端
            CosyVoiceWebSocketClient.AudioFormat format = convertToAudioFormat(modelName);
            final byte[][] audioData = {null};
                
            CosyVoiceWebSocketClient client = new CosyVoiceWebSocketClient(
                    workspaceId,
                    config.apiKey,
                    region,
                    format,
                    new CosyVoiceWebSocketClient.AudioCallback() {
                        @Override
                        public void onAudioData(byte[] data) {
                            audioData[0] = data;
                        }
                            
                        @Override
                        public void onComplete() {
                            AILogger.i(TAG, "WebSocket TTS 合成完成");
                        }
                            
                        @Override
                        public void onError(String error) {
                            AILogger.w(TAG, "WebSocket TTS 错误: " + error);
                        }
                    }
            );
                
            // 建立连接并执行合成
            if (!client.connect()) {
                AILogger.w(TAG, "WebSocket 连接失败,回退到 HTTP 方式");
                client.disconnect();
                return null;
            }
                
            try {
                // 使用带超时的同步合成(120秒超时),传递模型名
                boolean completed = client.synthesizeWithTimeout(text, voiceId, modelName, DEFAULT_TIMEOUT_MS);
                    
                if (!completed || audioData[0] == null || audioData[0].length == 0) {
                    throw new Exception("WebSocket TTS 合成失败或超时");
                }
                    
                AILogger.i(TAG, "WebSocket TTS 成功: 音频大小=" + audioData[0].length + " bytes");
                    
                // 保存音频文件
                File audioFile = new File(context.getCacheDir(),
                        "tts_ws_" + System.currentTimeMillis() + ".mp3");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(audioFile)) {
                    fos.write(audioData[0]);
                }
                    
                return audioFile;
            } finally {
                client.disconnect();
            }
        } catch (Exception e) {
            AILogger.w(TAG, "WebSocket TTS 失败: " + e.getMessage() + ",回退到 HTTP 方式");
            throw e;  // 抛出异常以触发回退逻辑
        }
    }
        
    /**
     * 转换音频格式(根据模型名)
     */
    private CosyVoiceWebSocketClient.AudioFormat convertToAudioFormat(String modelName) {
        String lower = modelName.toLowerCase();
        if (lower.contains("pcm") || lower.contains("raw")) {
            return CosyVoiceWebSocketClient.AudioFormat.PCM_S16_LE;
        } else if (lower.contains("wav")) {
            return CosyVoiceWebSocketClient.AudioFormat.WAV;
        }
        return CosyVoiceWebSocketClient.AudioFormat.MP3;  // 默认 MP3
    }
    
    /** 是否为百炼/DashScope 端点(语音合成需走原生接口而非 OpenAI 兼容路径) */
    private boolean isDashScopeEndpoint(String apiUrl) {
        return apiUrl != null
                && (apiUrl.contains("maas.aliyuncs.com") || apiUrl.contains("dashscope.aliyuncs.com"));
    }

    /** 从 apiUrl 提取 scheme://host 基础地址（剥离 /compatible-mode/v1 等路径后缀） */
    private String extractBaseUrl(String apiUrl) throws Exception {
        java.net.URI uri = new java.net.URI(apiUrl);
        String base = uri.getScheme() + "://" + uri.getHost();
        if (uri.getPort() > 0) base += ":" + uri.getPort();
        return base;
    }

    /**
     * 调用百炼/DashScope 原生非实时语音合成接口：
     * POST {host}/api/v1/services/audio/tts/SpeechSynthesizer
     * 成功时直接返回音频二进制；失败返回 JSON（code/message）
     */
    private File callDashScopeTtsAPI(OnlineModelManager.OnlineModelConfig config,
                                        String modelName, String text, String voiceId) throws Exception {
        String apiKey = config.apiKey;
        String fullUrl = extractBaseUrl(config.apiUrl) + DASHSCOPE_NATIVE_TTS_PATH;
        AILogger.i(TAG, "DashScope 原生TTS: model=" + modelName + " voice=" + voiceId + " url=" + fullUrl);

        URL url = new URL(fullUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (connection instanceof HttpsURLConnection) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation((HttpsURLConnection) connection);
        }
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
            connection.setRequestProperty("Connection", "keep-alive");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setDoOutput(true);

            JsonObject input = new JsonObject();
            input.addProperty("text", text);
            // Qwen-TTS/CosyVoice: voice/format/sample_rate 在 input 对象内
            input.addProperty("voice", voiceId != null && !voiceId.isEmpty() ? voiceId : "Cherry");
            input.addProperty("format", "mp3");
            input.addProperty("sample_rate", 24000);
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);
            requestBody.add("input", input);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(gson.toJson(requestBody).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                String errorBody = readErrorStream(connection);
                String msg = parseDashScopeError(errorBody);
                AILogger.e(TAG, "DashScope 原生TTS失败: HTTP " + responseCode + " " + errorBody);
                throw new Exception("语音合成请求失败: HTTP " + responseCode
                        + (msg != null ? " - " + msg : (errorBody != null && !errorBody.isEmpty() ? " - " + errorBody : "")));
            }

            // 成功时直接返回音频二进制；若响应为 JSON 则视为异常
            byte[] data = readAllBytes(connection.getInputStream());
            String contentType = connection.getContentType();
            if (contentType != null && contentType.contains("json")) {
                String body = new String(data, StandardCharsets.UTF_8);
                String msg = parseDashScopeError(body);
                throw new Exception("语音合成失败: " + (msg != null ? msg : body));
            }
            if (data.length == 0) {
                throw new Exception("语音合成返回空音频");
            }

            File audioFile = new File(context.getCacheDir(),
                    "tts_" + System.currentTimeMillis() + ".mp3");
            try (FileOutputStream fos = new FileOutputStream(audioFile)) {
                fos.write(data);
            }
            return audioFile;
        } finally {
            connection.disconnect();
        }
    }

    /** 解析百炼错误 JSON 中的 code/message，解析失败返回 null */
    private String parseDashScopeError(String errorBody) {
        if (errorBody == null || errorBody.isEmpty()) return null;
        try {
            JsonObject obj = gson.fromJson(errorBody, JsonObject.class);
            String code = obj != null && obj.has("code") ? obj.get("code").getAsString() : null;
            String message = obj != null && obj.has("message") ? obj.get("message").getAsString() : null;
            if (code != null || message != null) {
                return (code != null ? "[" + code + "] " : "") + (message != null ? message : "");
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 读取输入流全部字节 */
    private byte[] readAllBytes(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = is.read(buffer)) != -1) {
            bos.write(buffer, 0, n);
        }
        is.close();
        return bos.toByteArray();
    }

    // ==================== 系统 TTS 兜底 ====================

    /**
     * 使用 Android 系统 TTS 引擎合成到文件（离线兜底）
     *
     * @param voiceId 音色 ID（sys: 前缀的系统音色会被应用，其他忽略）
     */
    private File synthesizeWithSystemTts(String text, String voiceId) throws Exception {
        ensureSystemTtsInitialized();
        // 系统TTS引擎为异步初始化，首次调用时等待其就绪（最多 5s）
        if (!systemTtsReady.get()) {
            for (int i = 0; i < 50 && !systemTtsReady.get(); i++) {
                Thread.sleep(100);
            }
        }
        if (!systemTtsReady.get()) {
            throw new Exception("系统TTS引擎初始化失败，请检查设备是否安装TTS引擎");
        }
        applySystemVoice(voiceId);

        File audioFile = new File(context.getCacheDir(),
                "tts_sys_" + System.currentTimeMillis() + ".wav");

        CompletableFuture<Boolean> done = new CompletableFuture<>();
        Bundle params = new Bundle();
        int ret = systemTts.synthesizeToFile(text, params, audioFile, "tts_utterance_" + System.currentTimeMillis());
        if (ret != TextToSpeech.SUCCESS) {
            throw new Exception("系统TTS合成启动失败");
        }

        systemTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                done.complete(true);
            }

            @Override
            public void onError(String utteranceId) {
                done.complete(false);
            }
        });

        Boolean success = done.get(DEFAULT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (success == null || !success || !audioFile.exists() || audioFile.length() == 0) {
            throw new Exception("系统TTS合成失败");
        }
        return audioFile;
    }

    private synchronized void ensureSystemTtsInitialized() {
        if (systemTts != null) return;
        systemTts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = systemTts.setLanguage(Locale.CHINESE);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    systemTts.setLanguage(Locale.getDefault());
                }
                systemTtsReady.set(true);
                AILogger.i(TAG, "系统TTS引擎初始化成功");
            } else {
                AILogger.w(TAG, "系统TTS引擎初始化失败: " + status);
            }
        });
    }

    /**
     * 获取系统 TTS 引擎内置音色列表（离线可选，中文音色排在前面）
     */
    public List<Voice> getSystemVoices() {
        List<Voice> result = new ArrayList<>();
        try {
            ensureSystemTtsInitialized();
            for (int i = 0; i < 30 && !systemTtsReady.get(); i++) {
                Thread.sleep(100);
            }
            if (systemTts == null || !systemTtsReady.get()) return result;
            java.util.Set<android.speech.tts.Voice> voices = systemTts.getVoices();
            if (voices == null) return result;
            for (android.speech.tts.Voice v : voices) {
                if (v == null || v.getName() == null || v.getName().isEmpty()) continue;
                String locale = v.getLocale() != null ? v.getLocale().toString() : "";
                result.add(new Voice(SYS_VOICE_PREFIX + v.getName(),
                        v.getName() + "（系统·" + locale + "）"));
            }
            // 中文音色优先展示
            java.util.Collections.sort(result, (a, b) -> {
                String al = a.id.toLowerCase();
                String bl = b.id.toLowerCase();
                boolean az = al.contains("zh") || al.contains("cmn");
                boolean bz = bl.contains("zh") || bl.contains("cmn");
                return Boolean.compare(bz, az);
            });
            AILogger.i(TAG, "获取到 " + result.size() + " 个系统TTS音色");
        } catch (Exception e) {
            AILogger.w(TAG, "获取系统TTS音色列表失败: " + e.getMessage());
        }
        return result;
    }

    /** 合成前应用用户选择的系统音色（仅 sys: 前缀音色生效） */
    private void applySystemVoice(String voiceId) {
        if (voiceId == null || !voiceId.startsWith(SYS_VOICE_PREFIX) || systemTts == null) return;
        try {
            String name = voiceId.substring(SYS_VOICE_PREFIX.length());
            java.util.Set<android.speech.tts.Voice> voices = systemTts.getVoices();
            if (voices == null) return;
            for (android.speech.tts.Voice v : voices) {
                if (v != null && name.equals(v.getName())) {
                    systemTts.setVoice(v);
                    AILogger.i(TAG, "应用系统TTS音色: " + name);
                    return;
                }
            }
            AILogger.w(TAG, "未找到系统音色: " + name);
        } catch (Exception e) {
            AILogger.w(TAG, "应用系统音色失败: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 构建完整 URL（处理 /v1 后缀，与 APIKeyManager 逻辑一致）
     */
    private String buildUrl(String apiUrl, String endpoint) {
        String baseUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + endpoint;
        } else if (baseUrl.endsWith("/v1/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + endpoint;
        }
        // 已包含 audio 路径的自定义端点直接使用
        if (baseUrl.contains("/audio/")) {
            return baseUrl;
        }
        return baseUrl + "/v1" + endpoint;
    }

    private String readErrorStream(HttpURLConnection connection) {
        try {
            InputStream errorStream = connection.getErrorStream();
            if (errorStream == null) return "(无错误详情)";
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                    if (sb.length() > 500) break;
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return "(读取错误详情失败)";
        }
    }

    /**
     * 释放所有资源（停止播放、关闭系统TTS、关闭线程池）
     */
    public void shutdown() {
        stopPlayback();
        if (systemTts != null) {
            try {
                systemTts.stop();
                systemTts.shutdown();
            } catch (Exception ignored) {
            }
            systemTts = null;
            systemTtsReady.set(false);
        }
        executor.shutdownNow();
    }
}
