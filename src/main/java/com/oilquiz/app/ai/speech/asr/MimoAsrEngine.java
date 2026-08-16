package com.oilquiz.app.ai.speech.asr;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig;
import com.oilquiz.app.ai.speech.core.SpeechHttpClient;
import com.oilquiz.app.ai.speech.core.SpeechModelSelector;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * 小米 MiMo ASR 引擎（语音识别）
 *
 * MiMo ASR 使用 /chat/completions 接口（非 /audio/transcriptions），
 * 请求格式：messages 包含 audio content part + asr_options，返回 JSON 包含识别文本。
 *
 * 参考文档：https://platform.xiaomimimo.com/docs/usage-guide/Speech-Recognition
 */
public class MimoAsrEngine implements AsrEngine {

    private static final String TAG = "MimoAsrEngine";
    private static final String ENDPOINT = "/chat/completions";
    private static final Gson gson = new Gson();

    /** 该引擎是否接管给定端点 */
    @Override
    public boolean handles(String apiUrl) {
        return SpeechModelSelector.isMimoEndpoint(apiUrl);
    }

    /**
     * 语音识别：将音频文件转为文本
     */
    @Override
    public String transcribe(OnlineModelConfig config, String modelName, File audioFile, String language)
            throws Exception {
        // 读取音频文件并转 base64
        byte[] audioBytes = new byte[(int) audioFile.length()];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(audioFile)) {
            int offset = 0;
            int remaining = audioBytes.length;
            while (remaining > 0) {
                int count = fis.read(audioBytes, offset, remaining);
                if (count == -1) break;
                offset += count;
                remaining -= count;
            }
        }

        String audioBase64 = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP);
        String mimeType = getMimeType(audioFile);
        String dataUrl = "data:" + mimeType + ";base64," + audioBase64;

        String url = SpeechHttpClient.buildUrl(config.apiUrl, ENDPOINT);
        HttpURLConnection connection = SpeechHttpClient.openPostJson(url, config.apiKey);

        // MiMo ASR 请求格式：chat/completions
        JsonObject body = new JsonObject();
        body.addProperty("model", modelName);

        // messages: user 包含 audio content part
        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");

        JsonArray contentParts = new JsonArray();
        JsonObject audioPart = new JsonObject();
        audioPart.addProperty("type", "input_audio");
        JsonObject inputData = new JsonObject();
        inputData.addProperty("data", dataUrl);
        audioPart.add("input_audio", inputData);
        contentParts.add(audioPart);

        userMsg.add("content", contentParts);
        messages.add(userMsg);
        body.add("messages", messages);

        // asr_options 通过 extra_body 传递（非标准 OpenAI 字段）
        JsonObject asrOptions = new JsonObject();
        if (language != null && !language.isEmpty()) {
            asrOptions.addProperty("language", language);
        }
        body.add("extra_body", asrOptions);

        try {
            try (OutputStream os = connection.getOutputStream()) {
                os.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            SpeechHttpClient.assertOk(connection, "语音识别");

            String bodyText = SpeechHttpClient.readBody(connection);
            JsonObject json = gson.fromJson(bodyText, JsonObject.class);

            // MiMo ASR 返回完整 JSON，文本在 text 字段（OpenAI 兼容响应）
            if (json.has("choices") && json.get("choices").isJsonArray()) {
                JsonArray choices = json.get("choices").getAsJsonArray();
                if (choices.size() > 0) {
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    // 尝试 message.content（标准 OpenAI 格式）
                    if (choice.has("message") && choice.get("message").isJsonObject()) {
                        String text = choice.get("message").getAsJsonObject().get("content").getAsString();
                        if (text != null && !text.trim().isEmpty()) {
                            AILogger.i(TAG, "MiMo ASR 识别文本: " + text);
                            return text;
                        }
                    }
                    // 尝试直接 text 字段（MiMo 特有格式）
                    if (json.has("text")) {
                        String text = json.get("text").getAsString();
                        if (text != null && !text.trim().isEmpty()) {
                            AILogger.i(TAG, "MiMo ASR 识别文本: " + text);
                            return text;
                        }
                    }
                }
            }
            throw new Exception("语音识别响应格式异常: " + bodyText);
        } finally {
            connection.disconnect();
        }
    }

    /** 获取音频文件的 MIME 类型 */
    private String getMimeType(File audioFile) {
        String lower = audioFile.getName().toLowerCase();
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        return "audio/wav";
    }
}
