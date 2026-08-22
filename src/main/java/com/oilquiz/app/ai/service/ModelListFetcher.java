package com.oilquiz.app.ai.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oilquiz.app.ai.model.ApiModel;
import com.oilquiz.app.ai.util.SSLSocketFactoryUtil;
import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * 模型列表获取服务
 * 从 API 获取可用的模型列表
 */
public class ModelListFetcher {

    private static final String TAG = "ModelListFetcher";
    private static final int DEFAULT_TIMEOUT_MS = 15000;

    private static volatile ModelListFetcher INSTANCE;
    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final Gson gson;

    private ModelListFetcher(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "ModelList-Fetcher");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        });
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.gson = new Gson();
    }

    public static ModelListFetcher getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ModelListFetcher.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ModelListFetcher(context);
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 获取模型列表
     * @param apiUrl API 地址
     * @param apiKey API 密钥
     * @return 可用的模型列表
     */
    public CompletableFuture<List<ApiModel>> fetchModels(String apiUrl, String apiKey) {
        return fetchModels(apiUrl, apiKey, null);
    }

    /**
     * 获取模型列表，可按能力过滤（TTS/ASR）
     * @param apiUrl API 地址
     * @param apiKey API 密钥
     * @param capability 能力过滤：null=不过滤, "TTS", "ASR"
     * @return 可用的模型列表
     */
    public CompletableFuture<List<ApiModel>> fetchModels(String apiUrl, String apiKey, String capability) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (apiUrl == null || apiUrl.isEmpty()) {
                    throw new IllegalArgumentException("API URL 不能为空");
                }
                if (apiKey == null || apiKey.isEmpty()) {
                    throw new IllegalArgumentException("API Key 不能为空");
                }

                // 判断 API 类型
                String lowerUrl = apiUrl.toLowerCase();
                List<ApiModel> models;
                if (lowerUrl.contains("anthropic")) {
                    models = fetchAnthropicModels(apiUrl, apiKey);
                } else if (lowerUrl.contains("openai") || lowerUrl.contains("azure")) {
                    models = fetchOpenAIModels(apiUrl, apiKey);
                } else if (lowerUrl.contains("google") || lowerUrl.contains("generativelanguage")) {
                    models = fetchGoogleModels(apiUrl, apiKey);
                } else {
                    // 尝试 OpenAI 格式作为默认
                    models = fetchOpenAIModels(apiUrl, apiKey);
                }

                // 按能力过滤
                if (capability != null && !capability.isEmpty()) {
                    models = filterByCapability(models, capability);
                }

                return models;
            } catch (Exception e) {
                AILogger.e(TAG, "Fetch models failed: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    /**
     * 按能力过滤模型列表
     */
    private List<ApiModel> filterByCapability(List<ApiModel> models, String capability) {
        List<ApiModel> filtered = new ArrayList<>();
        String lower = capability.toLowerCase();
        for (ApiModel m : models) {
            if (matchesCapability(m, lower)) {
                filtered.add(m);
            }
        }
        return filtered;
    }

    /**
     * 判断模型是否匹配指定能力
     * 优先使用 capabilities 字段，回退到启发式模型名匹配
     */
    private boolean matchesCapability(ApiModel model, String capability) {
        // 优先检查 capabilities 字段
        if (model.capabilities != null) {
            for (String cap : model.capabilities) {
                if (cap.equalsIgnoreCase(capability)
                        || (capability.equals("tts") && (cap.equals("TTS") || cap.equals("Realtime-Text-to-Speech")))
                        || (capability.equals("asr") && (cap.equals("ASR") || cap.equals("Realtime-ASR")))) {
                    return true;
                }
            }
        }

        // 回退到启发式匹配
        String ml = model.id.toLowerCase();
        if (capability.equals("tts")) {
            return ml.contains("tts") || ml.contains("speech") || ml.contains("voice")
                    || ml.contains("cosy") || ml.contains("qwen-tts");
        } else if (capability.equals("asr")) {
            return ml.contains("asr") || ml.contains("whisper") || ml.contains("paraformer")
                    || ml.contains("sensevoice") || ml.contains("stt");
        }
        return false;
    }

    /**
     * 获取模型名称列表（字符串形式）
     */
    public CompletableFuture<List<String>> fetchModelNames(String apiUrl, String apiKey) {
        return fetchModels(apiUrl, apiKey).thenApply(models -> {
            List<String> names = new ArrayList<>();
            for (ApiModel model : models) {
                names.add(model.id);
            }
            return names;
        });
    }

    /**
     * 获取 OpenAI 格式的模型列表
     */
    private List<ApiModel> fetchOpenAIModels(String apiUrl, String apiKey) throws Exception {
        String fullUrl = buildOpenAIUrl(apiUrl, "/models");

        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

        // 禁用SSL证书验证以支持阿里云百炼等服务
        SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json");

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                String errorBody = readErrorStream(connection);
                throw new Exception("获取模型列表失败: HTTP " + responseCode + " - " + errorBody);
            }

            return parseOpenAIResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 获取 Anthropic 模型列表
     */
    private List<ApiModel> fetchAnthropicModels(String apiUrl, String apiKey) throws Exception {
        String fullUrl = buildOpenAIUrl(apiUrl, "/models");

        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

        // 禁用SSL证书验证以支持各种服务
        SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
            connection.setRequestProperty("x-api-key", apiKey);
            connection.setRequestProperty("anthropic-version", "2023-06-01");

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                String errorBody = readErrorStream(connection);
                throw new Exception("获取模型列表失败: HTTP " + responseCode + " - " + errorBody);
            }

            return parseAnthropicResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 构建OpenAI格式的URL，避免重复添加v1路径
     */
    private String buildOpenAIUrl(String apiUrl, String endpoint) {
        String baseUrl = apiUrl;
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://api.openai.com";
        }
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + endpoint;
        } else {
            return baseUrl + "/v1" + endpoint;
        }
    }

    /**
     * 获取 Google Gemini 模型列表
     */
    private List<ApiModel> fetchGoogleModels(String apiUrl, String apiKey) throws Exception {
        // Google 使用固定模型列表
        List<ApiModel> models = new ArrayList<>();
        
        // 添加常见的 Gemini 模型
        models.add(new ApiModel("gemini-pro", "Gemini Pro"));
        models.add(new ApiModel("gemini-pro-vision", "Gemini Pro Vision"));
        models.add(new ApiModel("gemini-1.5-pro", "Gemini 1.5 Pro"));
        models.add(new ApiModel("gemini-1.5-flash", "Gemini 1.5 Flash"));
        models.add(new ApiModel("gemini-1.5-pro-latest", "Gemini 1.5 Pro Latest"));
        models.add(new ApiModel("gemini-1.0-pro", "Gemini 1.0 Pro"));
        models.add(new ApiModel("gemini-1.0-pro-vision", "Gemini 1.0 Pro Vision"));
        
        for (ApiModel model : models) {
            model.source = "google";
            model.contextLength = 32768; // 默认上下文长度
        }
        
        return models;
    }

    /**
     * 解析 OpenAI 格式的响应
     */
    private List<ApiModel> parseOpenAIResponse(HttpURLConnection connection) throws Exception {
        InputStream inputStream = connection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        } finally {
            reader.close();
        }

        List<ApiModel> models = new ArrayList<>();
        JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
        JsonArray data = json.getAsJsonArray("data");

        if (data != null) {
            for (int i = 0; i < data.size(); i++) {
                JsonObject modelObj = data.get(i).getAsJsonObject();
                String id = modelObj.get("id").getAsString();
                String ownedBy = modelObj.has("owned_by") ? modelObj.get("owned_by").getAsString() : "";
                long created = modelObj.has("created") ? modelObj.get("created").getAsLong() : 0;

                // 过滤掉嵌入模型和其他非对话模型
                if (!id.contains("embedding") && !id.contains("ada") && !id.contains("babbage") &&
                    !id.contains("curie") && !id.contains("davinci") && !id.contains("text-") &&
                    !id.contains("-search") && !id.contains("-similarity") && !id.contains("-bison")) {
                    ApiModel model = ApiModel.fromOpenAI(id, ownedBy, created);
                    // 配置时直接提取服务商返回的真实上下文字段（如 context_length / max_model_len），
                    // 命中则覆盖名称推断值，并标记为真实值（配置保存时优先采用）
                    Integer realLen = OnlineInferenceService.extractContextWindow(modelObj);
                    if (realLen != null && realLen > 0) {
                        model.contextLength = realLen;
                        model.contextLengthFromApi = true;
                    }
                    models.add(model);
                }
            }
        }

        AILogger.i(TAG, "Fetched " + models.size() + " OpenAI models");
        return models;
    }

    /**
     * 解析 Anthropic 格式的响应
     */
    private List<ApiModel> parseAnthropicResponse(HttpURLConnection connection) throws Exception {
        InputStream inputStream = connection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        } finally {
            reader.close();
        }

        List<ApiModel> models = new ArrayList<>();
        JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();

        // Anthropic 可能返回不同的格式
        if (json.has("data")) {
            // OpenAI 兼容格式
            JsonArray data = json.getAsJsonArray("data");
            for (int i = 0; i < data.size(); i++) {
                JsonObject modelObj = data.get(i).getAsJsonObject();
                String id = modelObj.get("id").getAsString();
                models.add(ApiModel.fromAnthropic(id));
            }
        } else if (json.has("models")) {
            // Anthropic 原始格式
            JsonArray data = json.getAsJsonArray("models");
            for (int i = 0; i < data.size(); i++) {
                JsonObject modelObj = data.get(i).getAsJsonObject();
                String name = modelObj.get("name").getAsString();
                models.add(ApiModel.fromAnthropic(name));
            }
        } else {
            // 使用已知模型列表作为后备
            addKnownAnthropicModels(models);
        }

        AILogger.i(TAG, "Fetched " + models.size() + " Anthropic models");
        return models;
    }

    /**
     * 添加已知的 Anthropic 模型列表（后备方案）
     */
    private void addKnownAnthropicModels(List<ApiModel> models) {
        models.add(ApiModel.fromAnthropic("claude-3-opus-20240229"));
        models.add(ApiModel.fromAnthropic("claude-3-sonnet-20240229"));
        models.add(ApiModel.fromAnthropic("claude-3-opus"));
        models.add(ApiModel.fromAnthropic("claude-3-sonnet"));
        models.add(ApiModel.fromAnthropic("claude-3-haiku-20240307"));
        models.add(ApiModel.fromAnthropic("claude-3-haiku"));
        models.add(ApiModel.fromAnthropic("claude-2.1"));
        models.add(ApiModel.fromAnthropic("claude-2.0"));
        models.add(ApiModel.fromAnthropic("claude-instant-1"));
        models.add(ApiModel.fromAnthropic("claude-instant"));
    }

    /**
     * 读取错误流
     */
    private String readErrorStream(HttpURLConnection connection) {
        try {
            InputStream errorStream = connection.getErrorStream();
            if (errorStream != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8));
                StringBuilder error = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line);
                }
                reader.close();
                return error.toString();
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to read error stream: " + e.getMessage());
        }
        return "Unknown error";
    }

    /**
     * 测试连通性
     */
    public CompletableFuture<Boolean> testConnection(String apiUrl, String apiKey) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String fullUrl = apiUrl;
                if (!fullUrl.endsWith("/")) {
                    fullUrl += "/";
                }
                fullUrl += "v1/models";

                URL url = new URL(fullUrl);
                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

                // 禁用SSL证书验证以支持阿里云百炼等服务
                SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);

                try {
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);

                    // 设置认证头
                    if (apiUrl.toLowerCase().contains("anthropic")) {
                        connection.setRequestProperty("x-api-key", apiKey);
                        connection.setRequestProperty("anthropic-version", "2023-06-01");
                    } else {
                        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                    }

                    int responseCode = connection.getResponseCode();
                    return responseCode == 200;
                } finally {
                    connection.disconnect();
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Connection test failed: " + e.getMessage());
                return false;
            }
        }, executor);
    }

    /**
     * 关闭服务
     */
    public void shutdown() {
        executor.shutdown();
    }
}