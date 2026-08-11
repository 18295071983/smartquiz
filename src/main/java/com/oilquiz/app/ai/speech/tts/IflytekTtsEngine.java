package com.oilquiz.app.ai.speech.tts;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig;
import com.oilquiz.app.ai.speech.core.SpeechHttpClient;
import com.oilquiz.app.ai.speech.core.SpeechModelSelector;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 讯飞语音合成引擎（WebSocket 流式版）
 *
 * <h3>接入方式</h3>
 * 官方 WebSocket API：{@code wss://tts-api.xfyun.cn/v2/tts}
 *
 * <h3>鉴权</h3>
 * HMAC-SHA256 签名，基于 apiKey + apiSecret，生成 authorization 参数拼接到 URL。
 *
 * <h3>协议流程</h3>
 * <ol>
 *   <li>客户端携带签名参数发起 WebSocket 握手</li>
 *   <li>握手成功后发送 JSON 请求（文本 base64 编码 + 业务参数）</li>
 *   <li>服务端逐帧返回 base64 编码的音频片段</li>
 *   <li>status=2 表示合成结束</li>
 * </ol>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li>config.apiKey → 讯飞 APIKey</li>
 *   <li>config.apiSecret → 讯飞 APISecret（通过 OnlineModelConfig.apiSecret 传入）</li>
 *   <li>config.appId → 讯飞 APPID（通过 OnlineModelConfig.appId 传入）</li>
 *   <li>voice → 发音人（如 xiaoyan、x4_xiaoyan 等）</li>
 * </ul>
 *
 * @see <a href="https://www.xfyun.cn/doc/tts/online_tts/API.html">讯飞语音合成 WebAPI 文档</a>
 */
public class IflytekTtsEngine implements TtsEngine {

    private static final String TAG = "IflytekTtsEngine";
    private static final String TTS_URL = "wss://tts-api.xfyun.cn/v2/tts";
    private static final int TIMEOUT_SECONDS = 30;

    /** 默认发音人 */
    private static final String DEFAULT_VOICE = "x4_xiaoyan";
    /** 默认音频编码：mp3 */
    private static final String DEFAULT_AUE = "lame";
    /** 默认采样率 */
    private static final String DEFAULT_AUF = "audio/L16;rate=16000";

    private final Context context;
    private final Gson gson = new Gson();
    private final OkHttpClient wsClient;

    public IflytekTtsEngine(Context context) {
        this.context = context.getApplicationContext();
        this.wsClient = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public boolean handles(String apiUrl) {
        return SpeechModelSelector.isXfyunEndpoint(apiUrl);
    }

    @Override
    public File synthesize(OnlineModelConfig config, String modelName, String text, String voice)
            throws Exception {
        String appId = config.appId;
        String apiKey = config.apiKey;
        String apiSecret = config.apiSecret;

        if (appId == null || appId.isEmpty()) {
            throw new Exception("讯飞TTS缺少 appId，请在模型管理页设置");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new Exception("讯飞TTS缺少 apiKey，请在模型管理页设置");
        }
        if (apiSecret == null || apiSecret.isEmpty()) {
            throw new Exception("讯飞TTS缺少 apiSecret，请在模型管理页设置");
        }

        String actualVoice = (voice != null && !voice.isEmpty()) ? voice : DEFAULT_VOICE;
        AILogger.i(TAG, "讯飞TTS: voice=" + actualVoice + " textLen=" + text.length());

        // 1. 构建鉴权 URL
        String authUrl = buildAuthUrl(TTS_URL, apiKey, apiSecret);

        // 2. 构建请求体
        String requestBody = buildRequestBody(appId, actualVoice, text);

        // 3. WebSocket 通信
        byte[] audioData = wsSynthesize(authUrl, requestBody);

        if (audioData == null || audioData.length == 0) {
            throw new Exception("讯飞语音合成返回空音频");
        }

        // 4. 写入文件
        String extension = "lame".equals(DEFAULT_AUE) ? ".mp3" : ".wav";
        File audioFile = SpeechHttpClient.newAudioFile(context.getCacheDir(), "tts_iflytek_", extension);
        try (FileOutputStream fos = new FileOutputStream(audioFile)) {
            fos.write(audioData);
        }
        AILogger.i(TAG, "讯飞TTS合成完成: " + audioFile.length() + " bytes");
        return audioFile;
    }

    // ==================== 鉴权 ====================

    /**
     * 生成带鉴权参数的 WebSocket URL
     * 签名规则：host + date + request-line → HMAC-SHA256(apiSecret) → base64
     */
    private static String buildAuthUrl(String hostUrl, String apiKey, String apiSecret) throws Exception {
        java.net.URL url = new java.net.URL(hostUrl);
        String host = url.getHost();
        String path = url.getPath();

        // RFC 1123 格式时间
        String date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC));

        // 签名原始字段
        String signatureOrigin = "host: " + host + "\n"
                + "date: " + date + "\n"
                + "GET " + path + " HTTP/1.1";

        // HMAC-SHA256 签名
        String signature = hmacSha256Base64(apiSecret, signatureOrigin);

        // authorization 原始字段
        String authorizationOrigin = "api_key=\"" + apiKey + "\", "
                + "algorithm=\"hmac-sha256\", "
                + "headers=\"host date request-line\", "
                + "signature=\"" + signature + "\"";

        // base64 编码
        String authorization = Base64.getEncoder().encodeToString(
                authorizationOrigin.getBytes(StandardCharsets.UTF_8));

        // 拼接 URL
        return hostUrl + "?authorization=" + URLEncoder.encode(authorization, "UTF-8")
                + "&date=" + URLEncoder.encode(date, "UTF-8")
                + "&host=" + URLEncoder.encode(host, "UTF-8");
    }

    private static String hmacSha256Base64(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    // ==================== 请求体 ====================

    private String buildRequestBody(String appId, String voice, String text) {
        JsonObject root = new JsonObject();

        // 公共参数
        JsonObject common = new JsonObject();
        common.addProperty("app_id", appId);
        root.add("common", common);

        // 业务参数
        JsonObject business = new JsonObject();
        business.addProperty("aue", DEFAULT_AUE);
        business.addProperty("sfl", 1);       // 流式返回 mp3
        business.addProperty("auf", DEFAULT_AUF);
        business.addProperty("vcn", voice);
        business.addProperty("speed", 50);
        business.addProperty("volume", 50);
        business.addProperty("pitch", 50);
        root.add("business", business);

        // 数据参数
        JsonObject data = new JsonObject();
        data.addProperty("status", 2);  // 一次性传输
        data.addProperty("text", Base64.getEncoder().encodeToString(
                text.getBytes(StandardCharsets.UTF_8)));
        root.add("data", data);

        return gson.toJson(root);
    }

    // ==================== WebSocket 通信 ====================

    private byte[] wsSynthesize(String authUrl, String requestBody) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<byte[]> resultRef = new AtomicReference<>(null);
        AtomicReference<Exception> errorRef = new AtomicReference<>(null);

        Request request = new Request.Builder().url(authUrl).build();
        TtsWebSocketListener listener = new TtsWebSocketListener(requestBody, latch, resultRef, errorRef);

        WebSocket ws = wsClient.newWebSocket(request, listener);

        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            ws.close(1000, "timeout");
            throw new Exception("讯飞TTS合成超时（" + TIMEOUT_SECONDS + "秒）");
        }

        if (errorRef.get() != null) {
            throw errorRef.get();
        }

        return resultRef.get();
    }

    private class TtsWebSocketListener extends WebSocketListener {
        private final String requestBody;
        private final CountDownLatch latch;
        private final AtomicReference<byte[]> resultRef;
        private final AtomicReference<Exception> errorRef;
        private final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        TtsWebSocketListener(String requestBody, CountDownLatch latch,
                             AtomicReference<byte[]> resultRef, AtomicReference<Exception> errorRef) {
            this.requestBody = requestBody;
            this.latch = latch;
            this.resultRef = resultRef;
            this.errorRef = errorRef;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            // 握手成功，发送请求
            webSocket.send(requestBody);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JsonObject json = gson.fromJson(text, JsonObject.class);
                int code = json.has("code") ? json.get("code").getAsInt() : -1;
                if (code != 0) {
                    String message = json.has("message") ? json.get("message").getAsString() : "未知错误";
                    errorRef.set(new Exception("讯飞TTS错误[" + code + "]: " + message));
                    latch.countDown();
                    webSocket.close(1000, "error");
                    return;
                }

                JsonObject data = json.getAsJsonObject("data");
                if (data != null && data.has("audio")) {
                    String audioBase64 = data.get("audio").getAsString();
                    if (audioBase64 != null && !audioBase64.isEmpty()) {
                        byte[] chunk = Base64.getDecoder().decode(audioBase64);
                        baos.write(chunk);
                    }
                }

                int status = (data != null && data.has("status")) ? data.get("status").getAsInt() : 0;
                if (status == 2) {
                    // 合成完成
                    resultRef.set(baos.toByteArray());
                    latch.countDown();
                    webSocket.close(1000, "done");
                }
            } catch (Exception e) {
                errorRef.set(new Exception("讯飞TTS解析失败: " + e.getMessage(), e));
                latch.countDown();
                webSocket.close(1000, "parse error");
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            errorRef.set(new Exception("讯飞TTS连接失败: " + t.getMessage(), t));
            latch.countDown();
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            // 如果还没设置结果，可能是异常关闭
            if (latch.getCount() > 0) {
                if (resultRef.get() == null && errorRef.get() == null) {
                    resultRef.set(baos.toByteArray());
                }
                latch.countDown();
            }
        }
    }
}
