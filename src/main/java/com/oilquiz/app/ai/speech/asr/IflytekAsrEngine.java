package com.oilquiz.app.ai.speech.asr;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig;
import com.oilquiz.app.ai.speech.core.SpeechModelSelector;
import com.oilquiz.app.util.AILogger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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
 * 讯飞语音识别引擎（WebSocket 流式版）
 *
 * <h3>接入方式</h3>
 * 官方 WebSocket API：{@code wss://iat-api.xfyun.cn/v2/iat}
 *
 * <h3>鉴权</h3>
 * 与 TTS 相同的 HMAC-SHA256 签名机制。
 *
 * <h3>协议流程</h3>
 * <ol>
 *   <li>客户端携带签名参数发起 WebSocket 握手</li>
 *   <li>分片发送 base64 编码的音频数据（status=0 首帧，status=1 中间帧，status=2 尾帧）</li>
 *   <li>服务端逐帧返回识别结果（含 ws 属性）</li>
 *   <li>is_end=true 表示识别结束</li>
 * </ol>
 *
 * @see <a href="https://www.xfyun.cn/doc/asr/voicedictation/API.html">讯飞语音听写 WebAPI 文档</a>
 */
public class IflytekAsrEngine implements AsrEngine {

    private static final String TAG = "IflytekAsrEngine";
    private static final String IAT_URL = "wss://iat-api.xfyun.cn/v2/iat";
    private static final int TIMEOUT_SECONDS = 60;
    private static final int CHUNK_SIZE = 8000;  // 音频分片大小（字节）

    private static final String DEFAULT_ENGINE_TYPE = "16k_zh";
    private static final String DEFAULT_AUE = "raw";

    private final Gson gson = new Gson();
    private final OkHttpClient wsClient;

    public IflytekAsrEngine() {
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
    public String transcribe(OnlineModelConfig config, String modelName, File audioFile, String language)
            throws Exception {
        String appId = config.appId;
        String apiKey = config.apiKey;
        String apiSecret = config.apiSecret;

        if (appId == null || appId.isEmpty()) {
            throw new Exception("讯飞ASR缺少 appId，请在模型管理页设置");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new Exception("讯飞ASR缺少 apiKey，请在模型管理页设置");
        }
        if (apiSecret == null || apiSecret.isEmpty()) {
            throw new Exception("讯飞ASR缺少 apiSecret，请在模型管理页设置");
        }

        AILogger.i(TAG, "讯飞ASR: file=" + audioFile.getName() + " size=" + audioFile.length() + "B");

        // 1. 读取音频文件
        byte[] audioData = readFile(audioFile);

        // 2. 构建鉴权 URL
        String authUrl = buildAuthUrl(IAT_URL, apiKey, apiSecret);

        // 3. WebSocket 通信
        String result = wsRecognize(authUrl, appId, audioData, language);

        if (result == null || result.isEmpty()) {
            throw new Exception("讯飞语音识别结果为空");
        }

        AILogger.i(TAG, "讯飞ASR识别完成: " + result.length() + " chars");
        return result;
    }

    // ==================== 鉴权 ====================

    private static String buildAuthUrl(String hostUrl, String apiKey, String apiSecret) throws Exception {
        java.net.URL url = new java.net.URL(hostUrl);
        String host = url.getHost();
        String path = url.getPath();

        String date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC));

        String signatureOrigin = "host: " + host + "\n"
                + "date: " + date + "\n"
                + "GET " + path + " HTTP/1.1";

        String signature = hmacSha256Base64(apiSecret, signatureOrigin);

        String authorizationOrigin = "api_key=\"" + apiKey + "\", "
                + "algorithm=\"hmac-sha256\", "
                + "headers=\"host date request-line\", "
                + "signature=\"" + signature + "\"";

        String authorization = Base64.getEncoder().encodeToString(
                authorizationOrigin.getBytes(StandardCharsets.UTF_8));

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

    // ==================== WebSocket 通信 ====================

    private String wsRecognize(String authUrl, String appId, byte[] audioData, String language)
            throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> resultRef = new AtomicReference<>(null);
        AtomicReference<Exception> errorRef = new AtomicReference<>(null);

        Request request = new Request.Builder().url(authUrl).build();
        IatWebSocketListener listener = new IatWebSocketListener(
                appId, audioData, language, latch, resultRef, errorRef);

        WebSocket ws = wsClient.newWebSocket(request, listener);

        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            ws.close(1000, "timeout");
            throw new Exception("讯飞ASR识别超时（" + TIMEOUT_SECONDS + "秒）");
        }

        if (errorRef.get() != null) {
            throw errorRef.get();
        }

        return resultRef.get();
    }

    private class IatWebSocketListener extends WebSocketListener {
        private final String appId;
        private final byte[] audioData;
        private final String language;
        private final CountDownLatch latch;
        private final AtomicReference<String> resultRef;
        private final AtomicReference<Exception> errorRef;
        private final StringBuilder resultBuilder = new StringBuilder();

        IatWebSocketListener(String appId, byte[] audioData, String language,
                             CountDownLatch latch, AtomicReference<String> resultRef,
                             AtomicReference<Exception> errorRef) {
            this.appId = appId;
            this.audioData = audioData;
            this.language = language;
            this.latch = latch;
            this.resultRef = resultRef;
            this.errorRef = errorRef;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            // 按讯飞协议分片发送音频：
            // status=0 首帧（含业务参数 + 第一片音频）
            // status=1 中间帧（仅音频）
            // status=2 尾帧（最后一片音频，标记结束）
            int totalLen = audioData.length;
            int offset = 0;
            int frameIndex = 0;

            while (offset < totalLen) {
                int end = Math.min(offset + CHUNK_SIZE, totalLen);
                boolean isFirst = (frameIndex == 0);
                boolean isLast = (end >= totalLen);

                int status;
                if (isFirst && isLast) {
                    status = 2;  // 首帧即尾帧（数据量小，一帧发完）
                } else if (isFirst) {
                    status = 0;  // 首帧
                } else if (isLast) {
                    status = 2;  // 尾帧
                } else {
                    status = 1;  // 中间帧
                }

                sendFrame(webSocket, status, offset, end);
                offset = end;
                frameIndex++;
            }
        }

        /**
         * 发送一帧音频数据
         * @param status  0=首帧 1=中间帧 2=尾帧
         * @param start   音频数据起始偏移（含）
         * @param end     音频数据结束偏移（不含）
         */
        private void sendFrame(WebSocket webSocket, int status, int start, int end) {
            JsonObject root = new JsonObject();

            JsonObject common = new JsonObject();
            common.addProperty("app_id", appId);
            root.add("common", common);

            if (status == 0 || (status == 2 && start == 0)) {
                // 首帧：发送业务参数（status=2 且 start==0 表示首帧即尾帧，也需要业务参数）
                JsonObject business = new JsonObject();
                business.addProperty("language", language != null ? language : "zh_cn");
                business.addProperty("domain", "iat");
                business.addProperty("accent", "mandarin");
                business.addProperty("dwa", "wpgs");  // 动态修正
                root.add("business", business);
            }

            JsonObject data = new JsonObject();
            data.addProperty("status", status);
            byte[] chunk = new byte[end - start];
            System.arraycopy(audioData, start, chunk, 0, chunk.length);
            data.addProperty("image", Base64.getEncoder().encodeToString(chunk));
            root.add("data", data);

            webSocket.send(gson.toJson(root));
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JsonObject json = gson.fromJson(text, JsonObject.class);
                int code = json.has("code") ? json.get("code").getAsInt() : -1;
                if (code != 0) {
                    String message = json.has("message") ? json.get("message").getAsString() : "未知错误";
                    errorRef.set(new Exception("讯飞ASR错误[" + code + "]: " + message));
                    latch.countDown();
                    webSocket.close(1000, "error");
                    return;
                }

                JsonObject data = json.getAsJsonObject("data");
                if (data != null && data.has("result")) {
                    JsonObject result = data.getAsJsonObject("result");
                    JsonArray ws = result.getAsJsonArray("ws");
                    if (ws != null) {
                        for (JsonElement wsEl : ws) {
                            JsonArray cw = wsEl.getAsJsonObject().getAsJsonArray("cw");
                            if (cw != null) {
                                for (JsonElement cwEl : cw) {
                                    String w = cwEl.getAsJsonObject().has("w")
                                            ? cwEl.getAsJsonObject().get("w").getAsString() : "";
                                    resultBuilder.append(w);
                                }
                            }
                        }
                    }
                    // 检查是否结束
                    boolean isEnd = result.has("is_end") && result.get("is_end").getAsBoolean();
                    if (isEnd) {
                        resultRef.set(resultBuilder.toString());
                        latch.countDown();
                        webSocket.close(1000, "done");
                    }
                }

                // 也可以通过 ls 字段判断
                if (data != null && data.has("result")) {
                    JsonObject result = data.getAsJsonObject("result");
                    String ls = result.has("ls") ? result.get("ls").getAsString() : "";
                    if ("true".equals(ls)) {
                        resultRef.set(resultBuilder.toString());
                        latch.countDown();
                        webSocket.close(1000, "done");
                    }
                }
            } catch (Exception e) {
                errorRef.set(new Exception("讯飞ASR解析失败: " + e.getMessage(), e));
                latch.countDown();
                webSocket.close(1000, "parse error");
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            errorRef.set(new Exception("讯飞ASR连接失败: " + t.getMessage(), t));
            latch.countDown();
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            if (latch.getCount() > 0) {
                if (resultRef.get() == null && errorRef.get() == null) {
                    resultRef.set(resultBuilder.toString());
                }
                latch.countDown();
            }
        }
    }

    // ==================== 工具方法 ====================

    private static byte[] readFile(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        }
    }
}
