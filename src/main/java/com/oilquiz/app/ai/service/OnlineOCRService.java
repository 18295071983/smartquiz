package com.oilquiz.app.ai.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.util.SSLSocketFactoryUtil;
import com.oilquiz.app.util.AILogger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * 在线视觉模型 OCR 服务
 * 
 * 利用在线多模态大模型（GPT-4o、Qwen-VL、Gemini 等）的视觉理解能力进行文字识别，
 * 替代本地 ML Kit OCR，获得更高的识别精度，尤其对复杂排版、表格、手写体等场景效果显著。
 *
 * 设计原则：
 * 1. 优先使用 OCR 专用模型（用户专门配置的模型）
 * 2. 回退到支持 vision 的聊天模型
 * 3. 在线不可用时静默失败，由调用方回退到本地 OCR
 * 4. 图片压缩到合理尺寸，控制 token 消耗
 * 5. 返回 OCR 模型信息，支持多层数据传递
 */
public class OnlineOCRService {

    private static final String TAG = "OnlineOCRService";
    private static final int DEFAULT_TIMEOUT_MS = 60000; // 60s，OCR 图片处理可能较慢
    private static final int MAX_IMAGE_DIMENSION = 2048; // 最大边长，控制 token 消耗
    private static final int JPEG_QUALITY = 85;

    /**
     * OCR 识别结果（包含模型信息，支持多层数据传递）
     */
    public static class OCRResult {
        public final String text;           // 识别的文本内容
        public final String modelName;      // 使用的 OCR 模型名称
        public final String modelId;        // 使用的 OCR 模型 ID
        public final long timestamp;        // 识别时间戳
        
        public OCRResult(String text, String modelName, String modelId) {
            this.text = text;
            this.modelName = modelName;
            this.modelId = modelId;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final Context context;
    private final ExecutorService executor;
    private final Gson gson;

    private static volatile OnlineOCRService INSTANCE;

    private OnlineOCRService(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "OnlineOCR-Worker");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        });
        this.gson = new Gson();
    }

    public static OnlineOCRService getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (OnlineOCRService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new OnlineOCRService(context);
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 检查是否有可用的在线视觉模型
     */
    public boolean isAvailable() {
        OnlineModelManager.OnlineModelConfig config = selectVisionModel();
        return config != null;
    }

    /**
     * 异步识别图片文字（在线视觉模型）
     *
     * @param bitmap   图片 Bitmap
     * @param language 语言提示（zh/en/ja/ko/auto），可选
     * @return CompletableFuture<OCRResult> 识别结果（包含文本和模型信息）
     */
    public CompletableFuture<OCRResult> recognizeAsync(Bitmap bitmap, String language) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                OnlineModelManager.OnlineModelConfig config = selectVisionModel();
                if (config == null) {
                    throw new Exception("没有可用的在线视觉模型");
                }

                // 压缩并编码为 base64
                String base64Image = encodeBitmapToBase64(bitmap);
                if (base64Image == null || base64Image.isEmpty()) {
                    throw new Exception("图片编码失败");
                }

                // 获取用户为 OCR 指定的具体模型名（同一 API Key 下的某个模型）
                String ocrModelOverride = OnlineModelManager.getInstance(context).getOCRModelName();
                String actualModelName = resolveModelName(config, ocrModelOverride);

                AILogger.i(TAG, "Online OCR: endpoint=" + config.name +
                           ", model=" + actualModelName +
                           ", image_size=" + bitmap.getWidth() + "x" + bitmap.getHeight() +
                           ", base64_len=" + base64Image.length());

                // 构建视觉 API 请求
                String result = callVisionAPI(config, base64Image, language, ocrModelOverride);
                
                if (result == null || result.trim().isEmpty()) {
                    throw new Exception("在线视觉模型返回空结果");
                }

                AILogger.i(TAG, "Online OCR success: result_len=" + result.length());
                
                // 返回包含模型信息的完整结果（支持多层数据传递）
                return new OCRResult(result, actualModelName, config.id);

            } catch (Exception e) {
                AILogger.e(TAG, "Online OCR failed: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    /**
     * 从文件路径异步识别
     *
     * @param filePath 文件路径
     * @param language 语言提示
     * @return CompletableFuture<OCRResult> 识别结果（包含文本和模型信息）
     */
    public CompletableFuture<OCRResult> recognizeFromFileAsync(String filePath, String language) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                File file = new File(filePath);
                if (!file.exists()) {
                    throw new Exception("文件不存在: " + filePath);
                }

                // 读取文件为 base64
                String base64Image = encodeFileToBase64(file);
                if (base64Image == null || base64Image.isEmpty()) {
                    throw new Exception("文件编码失败");
                }

                OnlineModelManager.OnlineModelConfig config = selectVisionModel();
                if (config == null) {
                    throw new Exception("没有可用的在线视觉模型");
                }

                // 获取用户为 OCR 指定的具体模型名（同一 API Key 下的某个模型）
                String ocrModelOverride = OnlineModelManager.getInstance(context).getOCRModelName();
                String actualModelName = resolveModelName(config, ocrModelOverride);

                AILogger.i(TAG, "Online OCR from file: endpoint=" + config.name +
                           ", model=" + actualModelName +
                           ", file=" + file.getName() + ", base64_len=" + base64Image.length());

                String result = callVisionAPI(config, base64Image, language, ocrModelOverride);
                if (result == null || result.trim().isEmpty()) {
                    throw new Exception("在线视觉模型返回空结果");
                }

                AILogger.i(TAG, "Online OCR from file success: result_len=" + result.length());
                
                // 返回包含模型信息的完整结果（支持多层数据传递）
                return new OCRResult(result, actualModelName, config.id);

            } catch (Exception e) {
                AILogger.e(TAG, "Online OCR from file failed: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    /**
     * 选择支持视觉能力的在线模型
     * 
     * 优先级：
     * 1. OCR 专用模型（用户专门为 OCR 配置的模型，最高优先级）
     * 2. 激活的聊天模型（如果支持 vision）
     * 3. 任何支持 vision 的模型
     * 4. 按模型名启发式判断
     */
    private OnlineModelManager.OnlineModelConfig selectVisionModel() {
        try {
            OnlineModelManager modelManager = OnlineModelManager.getInstance(context);
            java.util.List<OnlineModelManager.OnlineModelConfig> allModels = modelManager.getModelList();

            if (allModels == null || allModels.isEmpty()) {
                return null;
            }

            // 1. 优先使用 OCR 专用模型（用户专门为 OCR 配置的模型）
            OnlineModelManager.OnlineModelConfig ocrModel = modelManager.getOCRModel();
            if (ocrModel != null) {
                AILogger.i(TAG, "Using dedicated OCR model: " + ocrModel.name);
                return ocrModel;
            }

            // 2. 回退：激活的聊天模型（如果支持 vision）
            OnlineModelManager.OnlineModelConfig active = modelManager.getActiveModel();
            if (active != null && active.enabled && active.supportsVision) {
                AILogger.i(TAG, "Using active model for OCR (supports vision): " + active.name);
                return active;
            }

            // 3. 遍历所有模型，找第一个支持 vision 的
            for (OnlineModelManager.OnlineModelConfig config : allModels) {
                if (config.enabled && config.supportsVision) {
                    AILogger.i(TAG, "Using vision model for OCR: " + config.name);
                    return config;
                }
            }

            // 4. 按模型名启发式判断（很多模型名包含 vision/vl 但没标记能力）
            for (OnlineModelManager.OnlineModelConfig config : allModels) {
                if (!config.enabled) continue;
                String m = (config.selectedModel != null ? config.selectedModel : config.modelName);
                if (m == null) continue;
                String ml = m.toLowerCase();
                if (ml.contains("vision") || ml.contains("-vl") || ml.contains("gpt-4o")
                        || ml.contains("gemini") || ml.contains("claude-3")
                        || ml.contains("qwen-vl") || ml.contains("internvl")
                        || ml.contains("llava") || ml.contains("moondream")) {
                    AILogger.i(TAG, "Vision model detected by name: " + m);
                    return config;
                }
            }

            // 5. 没有视觉模型，返回 null（调用方会回退到本地 OCR）
            AILogger.w(TAG, "No vision-capable online model found");
            return null;

        } catch (Exception e) {
            AILogger.e(TAG, "selectVisionModel failed: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 解析实际使用的模型名：用户指定的 OCR 模型名 > 端点配置的默认模型
     */
    private String resolveModelName(OnlineModelManager.OnlineModelConfig config, String modelOverride) {
        if (modelOverride != null && !modelOverride.isEmpty()) {
            return modelOverride;
        }
        return config.selectedModel != null ? config.selectedModel : config.modelName;
    }

    /**
     * 调用视觉 API（OpenAI 兼容格式）
     * 使用 image_url + base64 方式发送图片
     * @param modelOverride 用户为 OCR 指定的具体模型名（可为 null）
     */
    private String callVisionAPI(OnlineModelManager.OnlineModelConfig config,
                                  String base64Image, String language, String modelOverride) throws Exception {
        String apiUrl = config.apiUrl;
        String apiKey = config.apiKey;
        String modelName = resolveModelName(config, modelOverride);

        if (apiUrl == null || apiUrl.isEmpty()) {
            throw new IllegalArgumentException("API URL 不能为空");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }

        String fullUrl = buildUrl(apiUrl, "/chat/completions");
        // query-key 型服务商（Gemini 等）：密钥走 URL ?key=
        fullUrl = com.oilquiz.app.ai.model.ProviderConfigManager.get().withAuthQuery(fullUrl, apiKey);
        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(fullUrl)) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        }

        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
            com.oilquiz.app.ai.model.ProviderConfigManager.get()
                    .applyAuthHeaders(connection, fullUrl, apiKey, null, null);
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);

            // 构建请求体
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);
            requestBody.addProperty("max_tokens", 4096);
            requestBody.addProperty("temperature", 0.1); // 低温度，OCR 需要精确输出

            // 构建消息 - 使用 content array 格式
            JsonArray messages = new JsonArray();
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");

            // content 为数组，包含文本提示和图片
            JsonArray contentArray = new JsonArray();

            // 文本提示
            String prompt = buildOCRPrompt(language);
            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text", prompt);
            contentArray.add(textContent);

            // 图片内容
            JsonObject imageContent = new JsonObject();
            imageContent.addProperty("type", "image_url");
            JsonObject imageUrlObj = new JsonObject();
            imageUrlObj.addProperty("url", "data:image/jpeg;base64," + base64Image);
            imageUrlObj.addProperty("detail", "high");
            imageContent.add("image_url", imageUrlObj);
            contentArray.add(imageContent);

            userMessage.add("content", contentArray);
            messages.add(userMessage);
            requestBody.add("messages", messages);

            // 发送请求
            try (OutputStream os = connection.getOutputStream()) {
                os.write(gson.toJson(requestBody).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                String errorBody = readErrorStream(connection);
                throw new Exception("视觉API请求失败: HTTP " + responseCode + " - " + errorBody);
            }

            // 解析响应
            return parseVisionResponse(connection);

        } finally {
            connection.disconnect();
        }
    }

    /**
     * 构建 OCR 专用提示词
     */
    private String buildOCRPrompt(String language) {
        StringBuilder sb = new StringBuilder();
        sb.append("请对这张图片进行精确的 OCR 文字识别。\n\n");
        sb.append("要求：\n");
        sb.append("1. 完整、准确地识别图片中的所有文字内容\n");
        sb.append("2. 保持原文的段落结构和换行格式\n");
        sb.append("3. 如果是表格，使用 Markdown 表格格式输出\n");
        sb.append("4. 如果有数学公式，使用 LaTeX 格式输出\n");
        sb.append("5. 只输出识别到的文字内容，不要添加任何解释或评论\n");
        sb.append("6. 如果图片中没有文字，返回\"图片中未检测到文字\"\n");

        if (language != null && !language.isEmpty() && !language.equals("auto")) {
            sb.append("7. 识别语言为：");
            switch (language) {
                case "zh":
                case "chinese":
                    sb.append("中文");
                    break;
                case "en":
                case "english":
                    sb.append("英文");
                    break;
                case "ja":
                case "japanese":
                    sb.append("日文");
                    break;
                case "ko":
                case "korean":
                    sb.append("韩文");
                    break;
                default:
                    sb.append("自动检测");
                    break;
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 解析视觉 API 响应，提取文字内容
     */
    private String parseVisionResponse(HttpsURLConnection connection) throws Exception {
        StringBuilder response = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }

        String responseBody = response.toString();
        com.google.gson.JsonObject jsonResponse = gson.fromJson(responseBody, com.google.gson.JsonObject.class);

        // 提取 choices[0].message.content
        com.google.gson.JsonArray choices = jsonResponse.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            throw new Exception("API响应中没有choices字段");
        }

        com.google.gson.JsonObject firstChoice = choices.get(0).getAsJsonObject();
        com.google.gson.JsonObject message = firstChoice.getAsJsonObject("message");
        if (message == null) {
            throw new Exception("API响应中没有message字段");
        }

        String content = message.has("content") ? message.get("content").getAsString() : null;
        if (content == null || content.trim().isEmpty()) {
            throw new Exception("视觉模型返回空内容");
        }

        return content.trim();
    }

    /**
     * 将 Bitmap 压缩编码为 base64
     */
    private String encodeBitmapToBase64(Bitmap bitmap) {
        try {
            // 缩放到合理尺寸
            Bitmap scaled = scaleBitmap(bitmap, MAX_IMAGE_DIMENSION);
            if (scaled == null) return null;

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream);
            if (scaled != bitmap) scaled.recycle();

            byte[] bytes = outputStream.toByteArray();
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            AILogger.e(TAG, "encodeBitmapToBase64 failed: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 将文件编码为 base64（发送大小合适的图片：采样缩放 + 压缩，控制视觉模型 tokens 消耗）
     */
    private String encodeFileToBase64(File file) {
        try {
            // 先读取图片尺寸（仅边界信息，不加载像素），避免大图解码 OOM
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                // 非图片文件：直接读取原始字节（限制大小）
                if (file.length() > 10 * 1024 * 1024) {
                    AILogger.w(TAG, "File too large for direct base64: " + file.length());
                    return null;
                }
                byte[] raw = new byte[(int) file.length()];
                try (FileInputStream fis = new FileInputStream(file)) {
                    fis.read(raw);
                }
                return Base64.encodeToString(raw, Base64.NO_WRAP);
            }

            // 按目标最大边长计算采样率，避免大图 OOM
            int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
            int sampleSize = 1;
            if (maxSide > MAX_IMAGE_DIMENSION * 2) {
                sampleSize = maxSide / MAX_IMAGE_DIMENSION;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bitmap == null) return null;

            // 缩放至合适尺寸并压缩（encodeBitmapToBase64 内执行），控制 tokens 消耗
            return encodeBitmapToBase64(bitmap);
        } catch (Exception e) {
            AILogger.e(TAG, "encodeFileToBase64 failed: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 缩放 Bitmap，保持宽高比，最大边不超过 maxDimension
     */
    private Bitmap scaleBitmap(Bitmap source, int maxDimension) {
        if (source == null) return null;

        int width = source.getWidth();
        int height = source.getHeight();

        if (width <= maxDimension && height <= maxDimension) {
            return source;
        }

        float scale = Math.min((float) maxDimension / width, (float) maxDimension / height);
        int newWidth = Math.round(width * scale);
        int newHeight = Math.round(height * scale);

        Bitmap scaled = Bitmap.createScaledBitmap(source, newWidth, newHeight, true);
        return scaled;
    }

    /**
     * 构建 API URL
     */
    private String buildUrl(String apiUrl, String endpoint) {
        String baseUrl = apiUrl;
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://api.openai.com";
        }
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // M1：已以 /v1、/v4 等版本路径结尾（如智谱 /api/paas/v4）→ 直接拼 endpoint，
        // 避免拼出 /vN/v1/... 重复路径导致 404
        if (baseUrl.matches(".*/v\\d+$")) {
            return baseUrl + endpoint;
        }
        return baseUrl + "/v1" + endpoint;
    }

    /**
     * 读取错误响应流
     */
    private String readErrorStream(HttpURLConnection connection) {
        try {
            InputStream errorStream = connection.getErrorStream();
            if (errorStream == null) return "无错误详情";
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(errorStream, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return "读取错误详情失败: " + e.getMessage();
        }
    }
}
