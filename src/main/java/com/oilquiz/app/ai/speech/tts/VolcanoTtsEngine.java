package com.oilquiz.app.ai.speech.tts;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig;
import com.oilquiz.app.ai.speech.core.SpeechHttpClient;
import com.oilquiz.app.ai.speech.core.SpeechModelSelector;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 火山引擎语音合成引擎（HTTP REST API）
 *
 * <h3>接入方式</h3>
 * HTTP POST {@code https://openspeech.bytedance.com/api/v1/tts}
 *
 * <h3>鉴权</h3>
 * Bearer Token 鉴权，通过 config.apiKey 传入 Access Token。
 *
 * <h3>协议</h3>
 * JSON 请求体，返回音频二进制（mp3/wav/pcm）。
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li>config.apiKey → 火山引擎 Access Token</li>
 *   <li>config.appId → 火山引擎 AppID</li>
 *   <li>voice → 发音人（如 zh_female_qingxin、zh_male_chunhou 等）</li>
 * </ul>
 *
 * @see <a href="https://www.volcengine.com/docs/6561/97465">火山引擎语音合成 API 文档</a>
 */
public class VolcanoTtsEngine implements TtsEngine {

    private static final String TAG = "VolcanoTtsEngine";
    private static final String TTS_URL = "https://openspeech.bytedance.com/api/v1/tts";
    private static final String DEFAULT_VOICE = "zh_female_qingxin";
    private static final String DEFAULT_ENCODING = "mp3";

    private final Context context;
    private final Gson gson = new Gson();

    public VolcanoTtsEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean handles(String apiUrl) {
        return SpeechModelSelector.isVolcanoEndpoint(apiUrl);
    }

    @Override
    public File synthesize(OnlineModelConfig config, String modelName, String text, String voice)
            throws Exception {
        String appId = config.appId;
        String accessToken = config.apiKey;

        if (appId == null || appId.isEmpty()) {
            throw new Exception("火山引擎TTS缺少 appId，请在模型管理页设置");
        }
        if (accessToken == null || accessToken.isEmpty()) {
            throw new Exception("火山引擎TTS缺少 Access Token，请在模型管理页设置");
        }

        String actualVoice = (voice != null && !voice.isEmpty()) ? voice : DEFAULT_VOICE;
        AILogger.i(TAG, "火山TTS: voice=" + actualVoice + " textLen=" + text.length());

        // 构建请求体
        JsonObject body = new JsonObject();

        JsonObject app = new JsonObject();
        app.addProperty("appid", appId);
        app.addProperty("token", accessToken);
        app.addProperty("cluster", "volcano_tts");
        body.add("app", app);

        JsonObject user = new JsonObject();
        user.addProperty("uid", "smartquiz_user");
        body.add("user", user);

        JsonObject audio = new JsonObject();
        audio.addProperty("voice_type", actualVoice);
        audio.addProperty("encoding", DEFAULT_ENCODING);
        audio.addProperty("speed_ratio", 1.0);
        body.add("audio", audio);

        JsonObject request = new JsonObject();
        request.addProperty("reqid", UUID.randomUUID().toString());
        request.addProperty("text", text);
        request.addProperty("text_type", "plain");
        request.addProperty("operation", "query");
        body.add("request", request);

        // 发送 HTTP 请求（火山引擎需要自定义 Authorization header，不使用 openPost）
        java.net.URL url = new java.net.URL(TTS_URL);
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

            SpeechHttpClient.assertOk(conn, "火山引擎TTS");

            // 尝试解析 JSON 响应（火山引擎可能返回 JSON 包含 base64 音频）
            String contentType = conn.getContentType();
            if (contentType != null && contentType.contains("application/json")) {
                String responseBody = SpeechHttpClient.readBody(conn);
                return parseJsonResponse(responseBody);
            } else {
                // 直接返回二进制音频
                File audioFile = SpeechHttpClient.newAudioFile(
                        context.getCacheDir(), "tts_volcano_", "." + DEFAULT_ENCODING);
                try (InputStream is = conn.getInputStream()) {
                    SpeechHttpClient.pipeToFile(is, audioFile);
                }
                if (audioFile.length() == 0) {
                    audioFile.delete();
                    throw new Exception("火山引擎语音合成返回空音频");
                }
                return audioFile;
            }
        } finally {
            conn.disconnect();
        }
    }

    private File parseJsonResponse(String body) throws Exception {
        JsonObject json = gson.fromJson(body, JsonObject.class);
        if (json == null) {
            throw new Exception("火山引擎TTS响应解析失败");
        }
        int code = json.has("code") ? json.get("code").getAsInt() : -1;
        if (code != 3000) {
            String message = json.has("message") ? json.get("message").getAsString() : "未知错误";
            throw new Exception("火山引擎TTS错误[" + code + "]: " + message);
        }
        JsonObject data = json.getAsJsonObject("data");
        if (data == null || !data.has("audio")) {
            throw new Exception("火山引擎TTS响应中无音频数据");
        }
        String audioBase64 = data.get("audio").getAsString();
        byte[] audioBytes = java.util.Base64.getDecoder().decode(audioBase64);

        File audioFile = SpeechHttpClient.newAudioFile(
                context.getCacheDir(), "tts_volcano_", "." + DEFAULT_ENCODING);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(audioFile)) {
            fos.write(audioBytes);
        }
        if (audioFile.length() == 0) {
            audioFile.delete();
            throw new Exception("火山引擎语音合成返回空音频");
        }
        return audioFile;
    }
}
