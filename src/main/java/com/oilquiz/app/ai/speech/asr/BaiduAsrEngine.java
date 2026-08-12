package com.oilquiz.app.ai.speech.asr;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig;
import com.oilquiz.app.ai.speech.core.SpeechHttpClient;
import com.oilquiz.app.ai.speech.core.SpeechModelSelector;
import com.oilquiz.app.util.AILogger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 百度语音识别引擎（HTTP REST API）
 *
 * <h3>接入方式</h3>
 * HTTP POST {@code https://vop.baidu.com/server_api}
 *
 * <h3>鉴权</h3>
 * 与百度 TTS 相同的 Token 鉴权机制。
 *
 * <h3>协议</h3>
 * JSON 请求体（音频 base64 编码），返回 JSON 结果。
 *
 * @see <a href="https://ai.baidu.com/ai-doc/SPEECH/Vk38lxily">百度语音识别 API 文档</a>
 */
public class BaiduAsrEngine implements AsrEngine {

    private static final String TAG = "BaiduAsrEngine";
    private static final String ASR_URL = "https://vop.baidu.com/server_api";
    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final int MAX_AUDIO_BYTES = 60 * 1024 * 1024;  // 60MB

    private static final long TOKEN_CACHE_TTL = 25 * 60 * 60 * 1000L;

    private final Gson gson = new Gson();

    /** Token 缓存（与 BaiduTtsEngine 共享逻辑） */
    private static volatile String cachedToken;
    private static volatile long cachedTokenTime;
    private static volatile String cachedTokenKey;

    public BaiduAsrEngine() {
    }

    @Override
    public boolean handles(String apiUrl) {
        return SpeechModelSelector.isBaiduEndpoint(apiUrl);
    }

    @Override
    public String transcribe(OnlineModelConfig config, String modelName, File audioFile, String language)
            throws Exception {
        String apiKey = config.apiKey;
        String secretKey = config.apiSecret;

        if (apiKey == null || apiKey.isEmpty()) {
            throw new Exception("百度ASR缺少 API Key，请在模型管理页设置");
        }
        if (secretKey == null || secretKey.isEmpty()) {
            throw new Exception("百度ASR缺少 Secret Key，请在模型管理页设置");
        }

        long size = audioFile.length();
        if (size > MAX_AUDIO_BYTES) {
            throw new Exception("音频文件过大（" + (size / 1024 / 1024) + "MB），请缩短录音时长");
        }

        AILogger.i(TAG, "百度ASR: file=" + audioFile.getName() + " size=" + size + "B");

        // 1. 获取 Token
        String token = getAccessToken(apiKey, secretKey);

        // 2. 读取音频并 base64 编码
        byte[] audioData = readFile(audioFile);
        String audioBase64 = Base64.getEncoder().encodeToString(audioData);

        // 3. 构建请求体
        JsonObject body = new JsonObject();
        body.addProperty("format", inferFormat(audioFile.getName()));
        body.addProperty("rate", 16000);
        body.addProperty("channel", 1);
        body.addProperty("cuid", "smartquiz_android");
        body.addProperty("token", token);
        body.addProperty("speech", audioBase64);
        body.addProperty("len", audioData.length);
        if (language != null && !language.isEmpty()) {
            body.addProperty("lan", language);
        }

        // 4. 发送请求（百度需要自定义 URL，不使用 openPost）
        String fullUrl = ASR_URL + "?access_token=" + token;
        java.net.URL url = new java.net.URL(fullUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            SpeechHttpClient.assertOk(conn, "百度ASR");

            String responseBody = SpeechHttpClient.readBody(conn);
            return parseAsrResponse(responseBody);
        } finally {
            conn.disconnect();
        }
    }

    private String parseAsrResponse(String body) throws Exception {
        JsonObject json = gson.fromJson(body, JsonObject.class);
        if (json == null) {
            throw new Exception("百度ASR响应解析失败");
        }
        int errNo = json.has("err_no") ? json.get("err_no").getAsInt() : -1;
        if (errNo != 0) {
            String errMsg = json.has("err_msg") ? json.get("err_msg").getAsString() : "未知错误";
            throw new Exception("百度ASR错误[" + errNo + "]: " + errMsg);
        }
        if (json.has("result")) {
            com.google.gson.JsonArray result = json.getAsJsonArray("result");
            if (result != null && result.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (com.google.gson.JsonElement el : result) {
                    sb.append(el.getAsString());
                }
                String text = sb.toString();
                if (!text.isEmpty()) return text;
            }
        }
        throw new Exception("百度ASR识别结果为空");
    }

    private static String inferFormat(String fileName) {
        if (fileName == null) return "m4a";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".wav")) return "wav";
        if (lower.endsWith(".pcm")) return "pcm";
        if (lower.endsWith(".amr")) return "amr";
        return "m4a";
    }

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

    private String getAccessToken(String apiKey, String secretKey) throws Exception {
        if (cachedToken != null && apiKey.equals(cachedTokenKey)
                && System.currentTimeMillis() - cachedTokenTime < TOKEN_CACHE_TTL) {
            return cachedToken;
        }

        String url = TOKEN_URL + "?grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(apiKey, "UTF-8")
                + "&client_secret=" + URLEncoder.encode(secretKey, "UTF-8");

        HttpURLConnection conn = SpeechHttpClient.openPost(url, "");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);

        try {
            String body = SpeechHttpClient.readBody(conn);
            JsonObject json = gson.fromJson(body, JsonObject.class);
            if (json == null || !json.has("access_token")) {
                String err = json != null && json.has("error_description")
                        ? json.get("error_description").getAsString() : body;
                throw new Exception("百度Token获取失败: " + err);
            }
            String token = json.get("access_token").getAsString();
            cachedToken = token;
            cachedTokenTime = System.currentTimeMillis();
            cachedTokenKey = apiKey;
            return token;
        } finally {
            conn.disconnect();
        }
    }
}
