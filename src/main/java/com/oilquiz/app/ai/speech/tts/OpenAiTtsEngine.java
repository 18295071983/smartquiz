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

/**
 * OpenAI 兼容 TTS 引擎
 *
 * 调用 OpenAI 兼容格式的 /audio/speech 接口（JSON 请求，返回音频二进制）。
 * 适用于 OpenAI、MiniMax 等走标准 /v1/audio/speech 路径的端点。
 *
 * 路由依据：非百炼 / DashScope 端点（见 {@link SpeechModelSelector#isDashScopeEndpoint}）。
 */
public class OpenAiTtsEngine implements TtsEngine {

    private static final String TAG = "OpenAiTtsEngine";
    private static final String ENDPOINT = "/audio/speech";
    private static final String DEFAULT_VOICE = "alloy";

    private final Context context;
    private final Gson gson = new Gson();

    public OpenAiTtsEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 该引擎是否接管给定端点 */
    public boolean handles(String apiUrl) {
        return !SpeechModelSelector.isDashScopeEndpoint(apiUrl);
    }

    /**
     * 合成文本为音频文件
     *
     * @return 合成后的音频文件（mp3）
     */
    public File synthesize(OnlineModelConfig config, String modelName, String text, String voice)
            throws Exception {
        String url = SpeechHttpClient.buildUrl(config.apiUrl, ENDPOINT);
        HttpURLConnection connection = SpeechHttpClient.openPostJson(url, config.apiKey);

        JsonObject body = new JsonObject();
        body.addProperty("model", modelName);
        body.addProperty("input", text);
        body.addProperty("voice", voice != null && !voice.isEmpty() ? voice : DEFAULT_VOICE);
        body.addProperty("response_format", "mp3");

        try {
            try (OutputStream os = connection.getOutputStream()) {
                os.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            SpeechHttpClient.assertOk(connection, "语音合成");

            File audioFile = SpeechHttpClient.newAudioFile(context.getCacheDir(), "tts_", ".mp3");
            try (InputStream is = connection.getInputStream()) {
                SpeechHttpClient.pipeToFile(is, audioFile);
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
}
