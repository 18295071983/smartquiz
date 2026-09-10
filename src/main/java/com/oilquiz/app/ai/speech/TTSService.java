package com.oilquiz.app.ai.speech;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.speech.core.SpeechHttpClient;
import com.oilquiz.app.ai.speech.core.SpeechModelSelector;
import com.oilquiz.app.ai.speech.tts.BaiduTtsEngine;
import com.oilquiz.app.ai.speech.tts.DashScopeTtsEngine;
import com.oilquiz.app.ai.speech.tts.IflytekTtsEngine;
import com.oilquiz.app.ai.speech.tts.MimoTtsEngine;
import com.oilquiz.app.ai.speech.tts.OpenAiTtsEngine;
import com.oilquiz.app.ai.speech.tts.SystemTtsEngine;
import com.oilquiz.app.ai.speech.tts.TtsEngine;
import com.oilquiz.app.ai.speech.tts.VolcanoTtsEngine;
import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 语音合成服务（TTS，Text-to-Speech）编排层
 *
 * 重构前：本类是一个 1100+ 行的"上帝类"，混了 OpenAI / DashScope / WebSocket / 系统 TTS
 * 四条路径，重复实现模型选择与 URL/错误读取，且通过 CosyVoice WebSocket 实时合成
 * （其完成检测为假的 Thread.sleep 桩）存在可靠性与安全隐患。
 *
 * 重构后：
 * - 模型选择统一交给 {@link SpeechModelSelector}
 * - 网络层统一交给 {@link SpeechHttpClient}（不再 trust-all SSL）
 * - 三种合成实现拆分到 tts 包：{@link OpenAiTtsEngine} / {@link DashScopeTtsEngine} / {@link SystemTtsEngine}
 * - DashScope 端点固定走稳定的 HTTP 原生接口，移除对坏 WS 的依赖
 * - 本类仅负责引擎路由、兜底策略、音色解析、播放与音色列表聚合
 *
 * 兜底策略：在线可用时优先在线合成；在线失败且非 forceOnline 时透明回退系统 TTS；
 * 选择了系统音色（sys: 前缀）或在线不可用时直接走系统 TTS。
 */
public class TTSService {

    private static final String TAG = "TTSService";
    private static final int VOICES_TIMEOUT_MS = 15000;
    private static final String ENDPOINT_VOICES = "/audio/speech/voices";

    /** 系统 TTS 引擎音色 ID 前缀（与在线音色区分，如 "sys:cmn-cn-…"） */
    public static final String SYS_VOICE_PREFIX = "sys:";

    private static final String DEFAULT_VOICE = "alloy";

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
     * 内置常用音色预设（在线拉取失败且模型未命中注册表时的最终兜底）
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
    /** 强制系统语音合成（功能专用模型显式选择"系统语音合成"时生效） */
    private volatile boolean forceSystemTts = false;
    private volatile String voice = DEFAULT_VOICE;

    /** 合成引擎（按端点类型路由） */
    private final OpenAiTtsEngine openAiEngine;
    private final DashScopeTtsEngine dashScopeEngine;
    private final IflytekTtsEngine iflytekEngine;
    private final VolcanoTtsEngine volcanoEngine;
    private final BaiduTtsEngine baiduEngine;
    private final MimoTtsEngine mimoEngine;
    private final SystemTtsEngine systemEngine;

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
        this.openAiEngine = new OpenAiTtsEngine(this.context);
        this.dashScopeEngine = new DashScopeTtsEngine(this.context);
        this.iflytekEngine = new IflytekTtsEngine(this.context);
        this.volcanoEngine = new VolcanoTtsEngine(this.context);
        this.baiduEngine = new BaiduTtsEngine(this.context);
        this.mimoEngine = new MimoTtsEngine(this.context);
        this.systemEngine = new SystemTtsEngine(this.context);
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

    /** 强制仅使用系统语音合成（用户在功能专用模型里显式选择"系统语音合成"时置 true） */
    public void setForceSystemTts(boolean force) {
        this.forceSystemTts = force;
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
     * @param forceOnline   强制仅使用在线合成（用于音色试听，避免系统 TTS 回退导致音色不生效）；
     *                      在线失败时直接报错，不回退
     */
    public CompletableFuture<SynthesisResult> synthesizeAsync(String text, String voiceOverride,
                                                              boolean forceOnline) {
        return CompletableFuture.supplyAsync(() -> {
            if (text == null || text.trim().isEmpty()) {
                throw new IllegalArgumentException("合成文本不能为空");
            }

            OnlineModelManager.OnlineModelConfig config =
                    SpeechModelSelector.select(context, SpeechModelSelector.Capability.TTS);
            String actualVoice = resolveVoice(voiceOverride);
            boolean systemVoiceSelected = actualVoice != null && actualVoice.startsWith(SYS_VOICE_PREFIX);
            // 用户显式选择"系统语音合成"（forceSystemTts）或已选系统音色 → 直接走系统 TTS
            boolean useOnline = config != null && !systemVoiceSelected && !forceSystemTts;

            if (useOnline) {
                try {
                    String featureModelName = OnlineModelManager.getInstance(context)
                            .getFeatureModelName(OnlineModelManager.FEATURE_TTS);
                    String modelName = SpeechModelSelector.resolveModelName(config,
                            featureModelName != null ? featureModelName : ttsModelOverride,
                            SpeechModelSelector.Capability.TTS);
                    AILogger.i(TAG, "TTS using model: " + modelName + ", voice: " + actualVoice
                            + " @ " + config.name);

                    File audioFile = pickEngine(config).synthesize(config, modelName, text, actualVoice);
                    notifyEngine("在线语音合成");
                    return new SynthesisResult(audioFile, modelName, false);
                } catch (Exception e) {
                    AILogger.w(TAG, "在线TTS失败: " + e.getMessage());
                    if (forceOnline) {
                        throw new java.util.concurrent.CompletionException(
                                "在线语音合成失败: " + e.getMessage(), e);
                    }
                    notifyEngine("系统语音合成");
                }
            } else if (forceOnline && config == null) {
                throw new java.util.concurrent.CompletionException(
                        "未配置可用的在线语音合成模型，请在模型管理页设置语音合成模型", null);
            }

            // 回退系统 TTS
            try {
                File audioFile = systemEngine.synthesize(text, actualVoice);
                return new SynthesisResult(audioFile, "system-tts", true);
            } catch (Exception e) {
                notifyEngine("系统语音合成不可用");
                throw new java.util.concurrent.CompletionException("语音合成失败: " + e.getMessage(), e);
            }
        }, executor);
    }

    /** 按端点类型选择在线合成引擎 */
    private com.oilquiz.app.ai.speech.tts.TtsEngine pickEngine(
            OnlineModelManager.OnlineModelConfig config) {
        String apiUrl = config != null ? config.apiUrl : null;
        // 百炼端点统一走官方 SDK（DashScopeTtsEngine），不管是不是 compatible-mode：
        // 百炼的 compatible-mode 端点（/compatible-mode/v1）不提供 /audio/speech，
        // 只提供 OpenAI 兼容的 /api/v1 接口，但 TTS 不走 /audio/speech，而是走
        // /api/v1/services/audio/tts（DashScopeTtsEngine 的 SpeechSynthesizer）。
        // 旧逻辑把 compatible-mode 路由到 openAiEngine（走 /audio/speech），
        // 百炼平台直接 404。修复：所有百炼端点走 DashScopeTtsEngine。
        if (SpeechModelSelector.isDashScopeEndpoint(apiUrl)) return dashScopeEngine;
        if (SpeechModelSelector.isXfyunEndpoint(apiUrl)) return iflytekEngine;
        if (SpeechModelSelector.isVolcanoEndpoint(apiUrl)) return volcanoEngine;
        if (SpeechModelSelector.isBaiduEndpoint(apiUrl)) return baiduEngine;
        if (SpeechModelSelector.isMimoEndpoint(apiUrl)) return mimoEngine;
        // 其他 OpenAI 兼容端点也走 openAiEngine
        return openAiEngine;
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
     * 根据当前配置的 TTS 模型自动检测匹配的默认音色。
     *
     * 只基于"语音合成功能模型"（FEATURE_TTS）解析模型名：
     * - 显式配置了具体模型名 → 直接用
     * - 只配置了 TTS 专用端点、未指定具体模型名 → 用 SpeechModelSelector 解析出的
     *   端点默认 TTS 模型名（如 qwen3-tts-flash / cosyvoice-v2）
     * - 未配置 TTS 专用端点 → 返回 null（落到默认音色）
     *
     * 修复：不再回退到"全局活跃聊天模型"（getActiveModel）。
     * 旧逻辑在用户未配 TTS 专用模型时用聊天模型名猜 TTS 音色，切换聊天模型即导致
     * 默认音色跳变（音色漂移）——即"功能模型入口硬编码"问题。
     */
    private String detectDefaultVoiceForModel() {
        try {
            OnlineModelManager manager = OnlineModelManager.getInstance(context);
            String featureModelName = manager.getFeatureModelName(OnlineModelManager.FEATURE_TTS);
            if (featureModelName == null || featureModelName.isEmpty()) {
                // 未显式指定具体模型名：从 TTS 专用端点解析实际使用的模型名
                OnlineModelManager.OnlineModelConfig ttsConfig =
                        manager.getFeatureModel(OnlineModelManager.FEATURE_TTS);
                if (ttsConfig != null) {
                    featureModelName = SpeechModelSelector.resolveModelName(
                            ttsConfig, null, SpeechModelSelector.Capability.TTS);
                }
            }
            if (featureModelName == null || featureModelName.isEmpty()) {
                return null;
            }
            List<Voice> matchedVoices = SpeechModelRegistry.getVoicesForModel(featureModelName);
            if (matchedVoices != null && !matchedVoices.isEmpty()) {
                return matchedVoices.get(0).id;
            }
        } catch (Exception e) {
            AILogger.w(TAG, "自动检测默认音色失败: " + e.getMessage());
        }
        return null;
    }

    /** 是否有可用的在线 TTS 模型配置（用于流式朗读锁定引擎，避免在线/系统音色混用） */
    public boolean isOnlineAvailable() {
        try {
            return SpeechModelSelector.select(context, SpeechModelSelector.Capability.TTS) != null;
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
     */
    public void speak(String text, PlaybackCallback callback) {
        speakWithVoice(text, null, callback);
    }

    /**
     * 使用指定音色合成并播放（voiceId 为 null 时使用默认音色）
     *
     * @param forceOnline 强制仅在线合成（音色试听用，系统 TTS 不支持音色切换）
     */
    public void speakWithVoice(String text, String voiceId, PlaybackCallback callback,
                              boolean forceOnline) {
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
     *
     * <p>数据来源（按优先级）：</p>
     * <ol>
     *   <li>注册表实时获取 + 内置音色（{@link SpeechModelRegistry#getVoicesForModel}）</li>
     *   <li>端点 API 拉取（/audio/speech/voices，OpenAI 兼容端点）</li>
     *   <li>全部预设音色并集兜底</li>
     *   <li>系统 TTS 引擎音色（sys: 前缀，始终追加）</li>
     * </ol>
     */
    public CompletableFuture<List<Voice>> fetchVoicesAsync() {
        return CompletableFuture.supplyAsync(() -> {
            // 先触发注册表刷新（异步，不阻塞，缓存未过期则跳过）
            SpeechModelRegistry.refreshAsync(context);

            List<Voice> voices = new ArrayList<>();
            OnlineModelManager.OnlineModelConfig config =
                    SpeechModelSelector.select(context, SpeechModelSelector.Capability.TTS);
            String modelName = null;
            if (config != null) {
                try {
                    modelName = SpeechModelSelector.resolveModelName(config,
                            OnlineModelManager.getInstance(context).getFeatureModelName(
                                    OnlineModelManager.FEATURE_TTS),
                            SpeechModelSelector.Capability.TTS);
                } catch (Exception ignored) {
                }
            }
            // 注册表聚合：内置 + 自定义音色
            List<Voice> registryVoices = SpeechModelRegistry.getVoicesForModel(modelName);
            if (!registryVoices.isEmpty()) {
                SpeechModelRegistry.ModelEntry entry = SpeechModelRegistry.matchTtsModel(modelName);
                AILogger.i(TAG, "音色列表: model=" + modelName
                        + " (" + (entry != null ? entry.displayName : "在线") + ") 共 " + registryVoices.size() + " 个");
                voices.addAll(registryVoices);
            } else if (config != null) {
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
            // 系统音色始终追加（sys: 前缀区分）
            try {
                List<Voice> systemVoices = systemEngine.getVoices();
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
     * 1. /audio/speech/voices?model=xxx
     * 2. /audio/speech/voices
     * 3. /audio/voices
     */
    private List<Voice> fetchVoicesFromApi(OnlineModelManager.OnlineModelConfig config) throws Exception {
        Exception lastError = null;
        String model = null;
        try {
            model = SpeechModelSelector.resolveModelName(config,
                    OnlineModelManager.getInstance(context).getFeatureModelName(
                            OnlineModelManager.FEATURE_TTS),
                    SpeechModelSelector.Capability.TTS);
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

    /** 执行音色列表 GET 请求（安全 SSL，不信任全部证书） */
    private String httpGetVoices(OnlineModelManager.OnlineModelConfig config, String endpoint) throws Exception {
        String apiUrl = config.apiUrl;
        String apiKey = config.apiKey;
        if (apiUrl == null || apiUrl.isEmpty() || apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("API 配置不完整");
        }
        String fullUrl = SpeechHttpClient.buildUrl(apiUrl, endpoint);
        HttpURLConnection connection = SpeechHttpClient.openGet(fullUrl, apiKey);
        connection.setConnectTimeout(VOICES_TIMEOUT_MS);
        connection.setReadTimeout(VOICES_TIMEOUT_MS);
        try {
            SpeechHttpClient.assertOk(connection, "拉取音色列表");
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
                for (String key : new String[]{"voices", "data", "result", "list", "items"}) {
                    if (obj.has(key) && obj.get(key).isJsonArray()) {
                        arr = obj.getAsJsonArray(key);
                        break;
                    }
                }
            }
            if (arr == null) {
                return voices;
            }
            for (JsonElement el : arr) {
                if (el.isJsonPrimitive()) {
                    String id = el.getAsString();
                    if (!id.isEmpty()) {
                        voices.add(new Voice(id, id));
                    }
                } else if (el.isJsonObject()) {
                    JsonObject vo = el.getAsJsonObject();
                    String id = vo.has("id") ? vo.get("id").getAsString()
                            : (vo.has("voice_id") ? vo.get("voice_id").getAsString()
                            : (vo.has("voice") ? vo.get("voice").getAsString()
                            : (vo.has("name") ? vo.get("name").getAsString() : null)));
                    if (id == null || id.isEmpty()) {
                        continue;
                    }
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
                if (callback != null) {
                    callback.onStart();
                }
                mp.start();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                if (callback != null) {
                    callback.onComplete();
                }
                releaseMediaPlayer();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                if (callback != null) {
                    callback.onError("音频播放失败 (what=" + what + ")");
                }
                releaseMediaPlayer();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            AILogger.e(TAG, "播放音频失败: " + e.getMessage(), e);
            if (callback != null) {
                callback.onError("播放音频失败: " + e.getMessage());
            }
        }
    }

    /** 停止播放 */
    public synchronized void stopPlayback() {
        releaseMediaPlayer();
    }

    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    private synchronized void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
    }

    /** 获取系统 TTS 引擎内置音色列表（离线可选，sys: 前缀区分） */
    public List<Voice> getSystemVoices() {
        return systemEngine.getVoices();
    }

    /**
     * 释放所有资源（停止播放、关闭系统 TTS、关闭线程池）
     */
    public void shutdown() {
        stopPlayback();
        systemEngine.shutdown();
        executor.shutdownNow();
    }
}
