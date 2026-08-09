package com.oilquiz.app.ai.speech;

import android.content.Context;
import android.net.Uri;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.util.SSLSocketFactoryUtil;
import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * 在线语音识别服务（ASR，Speech-to-Text）
 *
 * 调用 OpenAI 兼容格式的 /audio/transcriptions 接口，将音频文件转写为文本。
 * 支持主流兼容端点（OpenAI Whisper、DashScope paraformer/qwen-asr、Azure、Moonshot 等）。
 *
 * 设计原则（与 OnlineOCRService 保持一致）：
 * 1. 优先选择支持音频能力的模型配置
 * 2. 按模型名启发式识别 ASR 模型（whisper/paraformer/asr/sensevoice）
 * 3. 在线不可用时返回失败，由调用方决定兜底策略
 * 4. 支持文件路径与 content:// URI 两种输入
 */
public class SpeechRecognitionService {

    private static final String TAG = "SpeechRecognitionService";
    private static final int DEFAULT_TIMEOUT_MS = 120000; // 120s，长音频转写可能较慢
    private static final String ENDPOINT = "/audio/transcriptions";

    /** 默认 ASR 模型名（可被 setAsrModel 覆盖） */
    private static final String DEFAULT_ASR_MODEL = "whisper-1";

    /**
     * 语音识别结果
     */
    public static class RecognitionResult {
        public final String text;           // 识别出的文本
        public final String modelName;      // 使用的模型名称
        public final long timestamp;        // 识别时间戳

        public RecognitionResult(String text, String modelName) {
            this.text = text;
            this.modelName = modelName;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final Context context;
    private final ExecutorService executor;
    private final Gson gson;

    /** 用户指定的 ASR 模型名（为 null 时自动选择） */
    private volatile String asrModelOverride = null;

    private static volatile SpeechRecognitionService INSTANCE;

    private SpeechRecognitionService(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "SpeechASR-Worker");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        });
        this.gson = new Gson();
    }

    public static SpeechRecognitionService getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (SpeechRecognitionService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new SpeechRecognitionService(context);
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 设置 ASR 模型名覆盖（例如 whisper-1 / paraformer-v2 / qwen3-asr-flash）
     * 传 null 恢复自动选择
     */
    public void setAsrModel(String modelName) {
        this.asrModelOverride = modelName;
    }

    public String getAsrModel() {
        return asrModelOverride;
    }

    /**
     * 检查是否有可用的在线 ASR 模型配置
     * 已禁用在线语音识别
     */
    public boolean isAvailable() {
        return false;  // 已禁用在线语音识别
    }

    /**
     * 异步识别音频文件（自动语言检测）
     *
     * @param audioFile 音频文件（mp3/m4a/wav/mp4/webm 等常见格式）
     * @return 识别结果
     */
    public CompletableFuture<RecognitionResult> recognizeAsync(File audioFile) {
        return recognizeAsync(audioFile, null);
    }

    /**
     * 异步识别音频文件
     *
     * @param audioFile 音频文件
     * @param language  语言提示（ISO-639-1，如 zh/en），可为 null 表示自动检测
     * @return 识别结果
     */
    public CompletableFuture<RecognitionResult> recognizeAsync(File audioFile, String language) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (audioFile == null || !audioFile.exists() || audioFile.length() == 0) {
                    throw new IllegalArgumentException("音频文件不存在或为空");
                }

                OnlineModelManager.OnlineModelConfig config = selectAsrModel();
                if (config == null) {
                    throw new Exception("没有可用的在线语音识别模型配置，请在语音模型设置中选择 ASR 模型");
                }

                // 专用模型配置的具体模型名优先于手动覆盖
                String featureModelName = OnlineModelManager.getInstance(context)
                        .getFeatureModelName(OnlineModelManager.FEATURE_ASR);
                String modelName = resolveModelName(config,
                        featureModelName != null ? featureModelName : asrModelOverride);
                AILogger.i(TAG, "ASR using model: " + modelName + " @ " + config.name);

                String text = callTranscriptionAPI(config, modelName, audioFile, language);
                if (text == null || text.trim().isEmpty()) {
                    throw new Exception("语音识别返回结果为空");
                }
                return new RecognitionResult(text.trim(), modelName);

            } catch (Exception e) {
                AILogger.e(TAG, "语音识别失败: " + e.getMessage(), e);
                throw new java.util.concurrent.CompletionException(e);
            }
        }, executor);
    }

    /**
     * 异步识别 content:// URI 指向的音频（先复制到缓存文件再上传）
     */
    public CompletableFuture<RecognitionResult> recognizeAsync(Uri audioUri, String language) {
        return CompletableFuture.supplyAsync(() -> {
            File tempFile = null;
            try {
                tempFile = copyUriToTempFile(audioUri);
                return tempFile;
            } catch (Exception e) {
                if (tempFile != null) tempFile.delete();
                throw new java.util.concurrent.CompletionException(e);
            }
        }, executor).thenCompose(file -> {
            CompletableFuture<RecognitionResult> future = recognizeAsync(file, language);
            // 识别完成后清理临时文件
            future.whenComplete((r, ex) -> file.delete());
            return future;
        });
    }

    /**
     * 选择 ASR 模型配置
     * 优先级：
     * 0. 用户配置的语音识别专用模型（最高优先级）
     * 1. 激活模型中标记 supportsAudio 的
     * 2. 任意标记 supportsAudio 的启用模型
     * 3. 模型名启发式包含 ASR 关键字的启用模型
     * 4. 当前激活模型（兜底，部分端点所有模型共用一个端点）
     */
    private OnlineModelManager.OnlineModelConfig selectAsrModel() {
        try {
            OnlineModelManager modelManager = OnlineModelManager.getInstance(context);

            // 0. 优先使用用户配置的语音识别专用模型
            OnlineModelManager.OnlineModelConfig asrModel =
                    modelManager.getFeatureModel(OnlineModelManager.FEATURE_ASR);
            if (asrModel != null) {
                AILogger.i(TAG, "Using dedicated ASR model: " + asrModel.name);
                return asrModel;
            }

            List<OnlineModelManager.OnlineModelConfig> allModels = modelManager.getModelList();
            if (allModels == null || allModels.isEmpty()) {
                return null;
            }

            OnlineModelManager.OnlineModelConfig active = modelManager.getActiveModel();
            if (active != null && active.enabled && active.supportsAudio) {
                return active;
            }

            for (OnlineModelManager.OnlineModelConfig config : allModels) {
                if (config.enabled && config.supportsAudio) {
                    return config;
                }
            }

            for (OnlineModelManager.OnlineModelConfig config : allModels) {
                if (!config.enabled) continue;
                String m = (config.selectedModel != null ? config.selectedModel : config.modelName);
                if (m == null) continue;
                String ml = m.toLowerCase();
                if (ml.contains("whisper") || ml.contains("paraformer") || ml.contains("sensevoice")
                        || ml.contains("asr")) {
                    return config;
                }
            }

            if (active != null && active.enabled) {
                return active;
            }
            return null;
        } catch (Exception e) {
            AILogger.e(TAG, "selectAsrModel failed: " + e.getMessage(), e);
            return null;
        }
    }

    private String resolveModelName(OnlineModelManager.OnlineModelConfig config, String modelOverride) {
        if (modelOverride != null && !modelOverride.isEmpty()) {
            return modelOverride;
        }
        String m = config.selectedModel != null ? config.selectedModel : config.modelName;
        return (m != null && !m.isEmpty()) ? m : DEFAULT_ASR_MODEL;
    }

    /**
     * 调用 OpenAI 兼容 /audio/transcriptions 接口（multipart/form-data）
     */
    private String callTranscriptionAPI(OnlineModelManager.OnlineModelConfig config,
                                        String modelName, File audioFile, String language) throws Exception {
        String apiUrl = config.apiUrl;
        String apiKey = config.apiKey;
        if (apiUrl == null || apiUrl.isEmpty()) throw new IllegalArgumentException("API URL 不能为空");
        if (apiKey == null || apiKey.isEmpty()) throw new IllegalArgumentException("API Key 不能为空");

        String fullUrl = buildUrl(apiUrl, ENDPOINT);
        URL url = new URL(fullUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (connection instanceof HttpsURLConnection) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation((HttpsURLConnection) connection);
        }

        String boundary = "----SpeechASRBoundary" + UUID.randomUUID().toString().replace("-", "");
        String lineEnd = "\r\n";

        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Connection", "Keep-Alive");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setDoOutput(true);
            connection.setUseCaches(false);

            try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
                // model 字段
                writeFormField(out, boundary, lineEnd, "model", modelName);
                // language 字段（可选）
                if (language != null && !language.isEmpty()) {
                    writeFormField(out, boundary, lineEnd, "language", language);
                }
                // 音频文件字段
                writeFilePart(out, boundary, lineEnd, "file", audioFile);
                out.writeBytes("--" + boundary + "--" + lineEnd);
                out.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                String errorBody = readErrorStream(connection);
                throw new Exception("语音识别请求失败: HTTP " + responseCode + " - " + errorBody);
            }

            return parseTranscriptionResponse(connection);
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

    /**
     * 解析响应 JSON，提取 text 字段
     */
    private String parseTranscriptionResponse(HttpURLConnection connection) throws Exception {
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        JsonObject json = gson.fromJson(response.toString(), JsonObject.class);
        if (json.has("text") && !json.get("text").isJsonNull()) {
            return json.get("text").getAsString();
        }
        throw new Exception("语音识别响应格式异常: " + response);
    }

    /**
     * 将 content:// URI 复制到缓存文件（便于 multipart 上传）
     */
    private File copyUriToTempFile(Uri uri) throws Exception {
        if (uri == null) throw new IllegalArgumentException("音频 URI 为空");

        String extension = ".m4a";
        String lastPath = uri.getLastPathSegment();
        if (lastPath != null && lastPath.contains(".")) {
            extension = lastPath.substring(lastPath.lastIndexOf('.'));
        }

        File tempFile = new File(context.getCacheDir(), "asr_" + System.currentTimeMillis() + extension);
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(tempFile)) {
            if (is == null) throw new Exception("无法打开音频 URI");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }
        return tempFile;
    }

    /**
     * 构建完整 URL（处理 /v1 后缀，与 APIKeyManager 逻辑一致）
     */
    private String buildUrl(String apiUrl, String endpoint) {
        String baseUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + endpoint;
        } else if (baseUrl.endsWith("/v1/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + endpoint;
        }
        // 已包含 audio 路径的自定义端点直接使用
        if (baseUrl.contains("/audio/")) {
            return baseUrl;
        }
        return baseUrl + "/v1" + endpoint;
    }

    private String readErrorStream(HttpURLConnection connection) {
        try {
            InputStream errorStream = connection.getErrorStream();
            if (errorStream == null) return "(无错误详情)";
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                    if (sb.length() > 500) break;
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return "(读取错误详情失败)";
        }
    }

    /**
     * 释放资源
     */
    public void shutdown() {
        executor.shutdownNow();
    }
}
