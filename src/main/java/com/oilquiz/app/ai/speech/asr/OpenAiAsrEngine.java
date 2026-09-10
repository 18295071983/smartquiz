package com.oilquiz.app.ai.speech.asr;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig;
import com.oilquiz.app.ai.speech.core.SpeechHttpClient;
import com.oilquiz.app.ai.speech.core.SpeechModelSelector;
import com.oilquiz.app.util.AILogger;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * OpenAI 兼容语音识别引擎
 *
 * 走标准 /audio/transcriptions（multipart/form-data 上传音频文件，返回 {"text": "..."}）。
 * 适用于 OpenAI Whisper、Groq、Azure OpenAI 等兼容端点。
 *
 * 路由依据：非百炼 / DashScope 端点。百炼并不提供该路径，详见 {@link DashScopeAsrEngine}。
 */
public class OpenAiAsrEngine implements AsrEngine {

    private static final String TAG = "OpenAiAsrEngine";
    private static final String ENDPOINT = "/audio/transcriptions";
    /** OpenAI 兼容端点 /audio/transcriptions 的常见文件上限（Whisper/Groq/Azure 等通常 25MB） */
    private static final long MAX_RAW_BYTES = 25L * 1024 * 1024;

    private final Gson gson = new Gson();

    @Override
    public boolean handles(String apiUrl) {
        return !SpeechModelSelector.isDashScopeEndpoint(apiUrl);
    }

    @Override
    public String transcribe(OnlineModelConfig config, String modelName, File audioFile, String language)
            throws Exception {
        long size = audioFile.length();
        if (size > MAX_RAW_BYTES) {
            throw new Exception("音频文件过大（" + (size / 1024 / 1024) + "MB），OpenAI 兼容识别端点通常限制 25MB，"
                    + "请缩短录音时长或降低采样率");
        }
        String fullUrl = SpeechHttpClient.buildUrl(config.apiUrl, ENDPOINT);
        AILogger.i(TAG, "OpenAI兼容ASR: model=" + modelName + " url=" + fullUrl);

        HttpURLConnection connection = SpeechHttpClient.openPost(fullUrl, config.apiKey);
        // Content-Type 必须在 connect() 之前设置，但 openPost 已连接
        String boundary = "----SpeechASRBoundary" + UUID.randomUUID().toString().replace("-", "");
        String lineEnd = "\r\n";

        try {
            try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
                writeFormField(out, boundary, lineEnd, "model", modelName);
                if (language != null && !language.isEmpty()) {
                    writeFormField(out, boundary, lineEnd, "language", language);
                }
                writeFilePart(out, boundary, lineEnd, "file", audioFile);
                out.writeBytes("--" + boundary + "--" + lineEnd);
                out.flush();
            }

            SpeechHttpClient.assertOk(connection, "语音识别");

            String body = SpeechHttpClient.readBody(connection);
            JsonObject json = gson.fromJson(body, JsonObject.class);
            if (json != null && json.has("text") && !json.get("text").isJsonNull()) {
                return json.get("text").getAsString();
            }
            throw new Exception("语音识别响应格式异常: " + body);
        } finally {
            connection.disconnect();
        }
    }

    private void writeFormField(DataOutputStream out, String boundary, String lineEnd,
                                String name, String value) throws Exception {
        out.writeBytes("--" + boundary + lineEnd);
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"" + lineEnd);
        out.writeBytes(lineEnd);
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(lineEnd);
    }

    private void writeFilePart(DataOutputStream out, String boundary, String lineEnd,
                               String fieldName, File file) throws Exception {
        out.writeBytes("--" + boundary + lineEnd);
        out.writeBytes("Content-Disposition: form-data; name=\"" + fieldName
                + "\"; filename=\"" + file.getName() + "\"" + lineEnd);
        out.writeBytes("Content-Type: application/octet-stream" + lineEnd);
        out.writeBytes("Content-Transfer-Encoding: binary" + lineEnd);
        out.writeBytes(lineEnd);

        byte[] buffer = new byte[8192];
        try (FileInputStream fis = new FileInputStream(file)) {
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        out.writeBytes(lineEnd);
    }
}
