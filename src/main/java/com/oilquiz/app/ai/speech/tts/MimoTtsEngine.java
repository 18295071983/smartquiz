package com.oilquiz.app.ai.speech.tts;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig;
import com.oilquiz.app.ai.speech.core.SpeechHttpClient;
import com.oilquiz.app.ai.speech.core.SpeechModelSelector;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * 小米 MiMo TTS 引擎
 *
 * MiMo TTS 使用 /chat/completions 接口（非 /audio/speech），
 * 请求格式：messages + audio 对象，返回 JSON 包含 base64 音频。
 *
 * 参考文档：https://platform.xiaomimimo.com/docs/usage-guide/speech-synthesis-v2.5
 */
public class MimoTtsEngine implements TtsEngine {

    private static final String TAG = "MimoTtsEngine";
    private static final String ENDPOINT = "/chat/completions";
    private static final String DEFAULT_VOICE = "mimo_default";

    private final Context context;
    private final Gson gson = new Gson();

    public MimoTtsEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 该引擎是否接管给定端点 */
    @Override
    public boolean handles(String apiUrl) {
        return SpeechModelSelector.isMimoEndpoint(apiUrl);
    }

    /**
     * 合成文本为音频文件
     *
     * @return 合成后的音频文件（wav）
     */
    @Override
    public File synthesize(OnlineModelConfig config, String modelName, String text, String voice)
            throws Exception {
        String url = SpeechHttpClient.buildUrl(config.apiUrl, ENDPOINT);
        HttpURLConnection connection = SpeechHttpClient.openPostJson(url, config.apiKey);

        // MiMo TTS 请求格式：chat/completions
        JsonObject body = new JsonObject();
        body.addProperty("model", modelName);

        // messages: user（风格指令）+ assistant（合成文本）
        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", "");
        messages.add(userMsg);

        JsonObject assistantMsg = new JsonObject();
        assistantMsg.addProperty("role", "assistant");
        assistantMsg.addProperty("content", text);
        messages.add(assistantMsg);
        body.add("messages", messages);

        // audio 参数
        JsonObject audio = new JsonObject();
        audio.addProperty("format", "mp3");
        audio.addProperty("voice", voice != null && !voice.isEmpty() ? voice : DEFAULT_VOICE);
        body.add("audio", audio);

        try {
            try (OutputStream os = connection.getOutputStream()) {
                os.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            SpeechHttpClient.assertOk(connection, "语音合成");

            // 解析响应：获取 JSON 文本
            String response = SpeechHttpClient.readBody(connection);
            JSONObject jsonResponse = new JSONObject(response);

            // choices[0].message.audio.data = base64 audio
            JSONArray choices = jsonResponse.getJSONArray("choices");
            JSONObject choice = choices.getJSONObject(0);
            JSONObject message = choice.getJSONObject("message");
            JSONObject audioData = message.getJSONObject("audio");
            String base64Audio = audioData.getString("data");

            // 解码 base64 并写入文件（只调用一次 decode）
            byte[] audioBytes = android.util.Base64.decode(base64Audio, android.util.Base64.DEFAULT);
            File audioFile = SpeechHttpClient.newAudioFile(context.getCacheDir(), "tts_mimo_", ".mp3");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(audioFile);
            fos.write(audioBytes);
            fos.close();

            if (audioFile.length() == 0) {
                audioFile.delete();
                throw new Exception("语音合成返回空音频");
            }
            AILogger.i(TAG, "MiMo TTS 合成成功: " + audioFile.length() + " bytes");
            return audioFile;
        } finally {
            connection.disconnect();
        }
    }
}
