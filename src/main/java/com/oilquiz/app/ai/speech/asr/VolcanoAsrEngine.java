package com.oilquiz.app.ai.speech.asr;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * 火山引擎语音识别引擎（HTTP REST API）
 *
 * <h3>接入方式</h3>
 * HTTP POST {@code https://openspeech.bytedance.com/api/v1/auc}
 *
 * <h3>鉴权</h3>
 * Bearer Token 鉴权，通过 config.apiKey 传入 Access Token。
 *
 * <h3>协议</h3>
 * JSON 请求体（音频 base64 编码），返回 JSON 结果。
 *
 * @see <a href="https://www.volcengine.com/docs/6561/80818">火山引擎语音识别 API 文档</a>
 */
public class VolcanoAsrEngine implements AsrEngine {

    private static final String TAG = "VolcanoAsrEngine";
    private static final String ASR_URL = "https://openspeech.bytedance.com/api/v1/auc";
    private static final int MAX_AUDIO_BYTES = 50 * 1024 * 1024;  // 50MB

    private final Gson gson = new Gson();

    public VolcanoAsrEngine() {
    }

    @Override
    public boolean handles(String apiUrl) {
        return SpeechModelSelector.isVolcanoEndpoint(apiUrl);
    }

    @Override
    public String transcribe(OnlineModelConfig config, String modelName, File audioFile, String language)
            throws Exception {
        String appId = config.appId;
        String accessToken = config.apiKey;

        if (appId == null || appId.isEmpty()) {
            throw new Exception("火山引擎ASR缺少 appId，请在模型管理页设置");
        }
        if (accessToken == null || accessToken.isEmpty()) {
            throw new Exception("火山引擎ASR缺少 Access Token，请在模型管理页设置");
        }

        long size = audioFile.length();
        if (size > MAX_AUDIO_BYTES) {
            throw new Exception("音频文件过大（" + (size / 1024 / 1024) + "MB），请缩短录音时长");
        }

        AILogger.i(TAG, "火山ASR: file=" + audioFile.getName() + " size=" + size + "B");

        // 读取音频文件并 base64 编码
        byte[] audioData = readFile(audioFile);
        String audioBase64 = Base64.getEncoder().encodeToString(audioData);

        // 构建请求体
        JsonObject body = new JsonObject();

        JsonObject app = new JsonObject();
        app.addProperty("appid", appId);
        app.addProperty("token", accessToken);
        app.addProperty("cluster", "volcengine_streaming_common");
        body.add("app", app);

        JsonObject user = new JsonObject();
        user.addProperty("uid", "smartquiz_user");
        body.add("user", user);

        JsonObject audio = new JsonObject();
        audio.addProperty("format", inferFormat(audioFile.getName()));
        audio.addProperty("codec", "raw");
        audio.addProperty("rate", 16000);
        audio.addProperty("bits", 16);
        audio.addProperty("channel", 1);
        audio.addProperty("audio", audioBase64);
        body.add("audio", audio);

        JsonObject request = new JsonObject();
        request.addProperty("reqid", UUID.randomUUID().toString());
        request.addProperty("sequence", 1);
        request.addProperty("nbest", 1);
        request.addProperty("operation", "query");
        if (language != null && !language.isEmpty()) {
            request.addProperty("language", language);
        }
        body.add("request", request);

        // 发送 HTTP 请求（火山引擎需要自定义 Authorization header）
        java.net.URL url = new java.net.URL(ASR_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer;" + accessToken);

        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            SpeechHttpClient.assertOk(conn, "火山引擎ASR");

            String responseBody = SpeechHttpClient.readBody(conn);
            return parseAsrResponse(responseBody);
        } finally {
            conn.disconnect();
        }
    }

    private String parseAsrResponse(String body) throws Exception {
        JsonObject json = gson.fromJson(body, JsonObject.class);
        if (json == null) {
            throw new Exception("火山引擎ASR响应解析失败");
        }
        int code = json.has("code") ? json.get("code").getAsInt() : -1;
        if (code != 3000) {
            String message = json.has("message") ? json.get("message").getAsString() : "未知错误";
            throw new Exception("火山引擎ASR错误[" + code + "]: " + message);
        }
        JsonObject result = json.getAsJsonObject("result");
        if (result == null) {
            throw new Exception("火山引擎ASR响应中无识别结果");
        }
        // 从 utterances 中提取文本
        JsonArray utterances = result.getAsJsonArray("utterances");
        if (utterances != null && utterances.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : utterances) {
                JsonObject utt = el.getAsJsonObject();
                String text = utt.has("text") ? utt.get("text").getAsString() : "";
                sb.append(text);
            }
            if (sb.length() > 0) return sb.toString();
        }
        // 兜底：直接取 text 字段
        if (result.has("text")) {
            String text = result.get("text").getAsString();
            if (text != null && !text.isEmpty()) return text;
        }
        throw new Exception("火山引擎ASR识别结果为空");
    }

    private static String inferFormat(String fileName) {
        if (fileName == null) return "m4a";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".wav")) return "wav";
        if (lower.endsWith(".mp3")) return "mp3";
        if (lower.endsWith(".pcm")) return "pcm";
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
}
