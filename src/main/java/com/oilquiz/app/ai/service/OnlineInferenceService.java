package com.oilquiz.app.ai.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oilquiz.app.ai.callback.StreamCallback;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.stats.TokenStatsManager;
import com.oilquiz.app.ai.util.SSLSocketFactoryUtil;
import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * 在线模型推理服务
 * 支持 OpenAI API 格式和 Anthropic Claude API 格式
 */
public class OnlineInferenceService {

    private static final String TAG = "OnlineInferenceService";
    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final int DEFAULT_MAX_TOKENS = 2048;
    private static final float DEFAULT_TEMPERATURE = 0.7f;

    private static volatile OnlineInferenceService INSTANCE;
    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final Gson gson;

    private OnlineInferenceService(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Online-Inference-Worker");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        });
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.gson = new Gson();
    }

    public static OnlineInferenceService getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (OnlineInferenceService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new OnlineInferenceService(context);
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 检查在线模型是否可用
     */
    public boolean isOnlineModelAvailable() {
        OnlineModelManager manager = OnlineModelManager.getInstance(context);
        return manager.getActiveModel() != null;
    }

    /**
     * 获取当前激活的在线模型配置
     */
    public OnlineModelManager.OnlineModelConfig getActiveConfig() {
        OnlineModelManager manager = OnlineModelManager.getInstance(context);
        return manager.getActiveModel();
    }

    /**
     * 同步生成（非流式）
     */
    public String generateSync(String prompt, OnlineModelManager.OnlineModelConfig config,
                                List<ChatMessage> history, int maxTokens) {
        try {
            return generateAsync(prompt, config, history, maxTokens).get();
        } catch (Exception e) {
            AILogger.e(TAG, "Sync generate failed: " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 异步生成（非流式）
     */
    public CompletableFuture<String> generateAsync(String prompt, OnlineModelManager.OnlineModelConfig config,
                                                  List<ChatMessage> history, int maxTokens) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiUrl = config.apiUrl;
                String modelName = config.modelName;
                String apiKey = config.apiKey;

                if (apiUrl == null || apiUrl.isEmpty()) {
                    throw new IllegalArgumentException("API URL 不能为空");
                }
                if (modelName == null || modelName.isEmpty()) {
                    throw new IllegalArgumentException("模型名称不能为空");
                }
                if (apiKey == null || apiKey.isEmpty()) {
                    throw new IllegalArgumentException("API Key 不能为空");
                }

                // 判断 API 类型
                if (isAnthropicAPI(apiUrl)) {
                    return callAnthropicAPI(apiUrl, apiKey, modelName, prompt, history, maxTokens, false, null);
                } else {
                    return callOpenAIAPI(apiUrl, apiKey, modelName, prompt, history, maxTokens, false, null);
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Async generate failed: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    /**
     * 流式生成
     */
    public void generateStream(String prompt, OnlineModelManager.OnlineModelConfig config,
                               List<ChatMessage> history, int maxTokens,
                               StreamCallback callback) {
        executor.execute(() -> {
            try {
                String apiUrl = config.apiUrl;
                String modelName = config.modelName;
                String apiKey = config.apiKey;

                if (apiUrl == null || apiUrl.isEmpty()) {
                    postError(callback, "API URL 不能为空");
                    return;
                }
                if (modelName == null || modelName.isEmpty()) {
                    postError(callback, "模型名称不能为空");
                    return;
                }
                if (apiKey == null || apiKey.isEmpty()) {
                    postError(callback, "API Key 不能为空");
                    return;
                }

                mainHandler.post(callback::onStart);

                if (isAnthropicAPI(apiUrl)) {
                    callAnthropicAPI(apiUrl, apiKey, modelName, prompt, history, maxTokens, true, callback);
                } else {
                    callOpenAIAPI(apiUrl, apiKey, modelName, prompt, history, maxTokens, true, callback);
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Stream generate failed: " + e.getMessage(), e);
                postError(callback, e.getMessage());
            }
        });
    }

    /**
     * 判断是否为 Anthropic API
     */
    private boolean isAnthropicAPI(String apiUrl) {
        return apiUrl != null && apiUrl.toLowerCase().contains("anthropic");
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
     * 调用 OpenAI 兼容 API
     */
    private String callOpenAIAPI(String apiUrl, String apiKey, String modelName,
                                  String prompt, List<ChatMessage> history,
                                  int maxTokens, boolean stream, StreamCallback callback) throws Exception {
        String fullUrl = buildOpenAIUrl(apiUrl, "/chat/completions");
        
        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        
        // 禁用SSL证书验证以支持阿里云百炼等服务
        SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
            connection.setDoOutput(true);

            // 构建请求体
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);
            
            // 构建消息列表
            JsonArray messages = new JsonArray();
            
            // 添加历史消息
            if (history != null) {
                for (ChatMessage msg : history) {
                    JsonObject message = new JsonObject();
                    if (msg.isSystemMessage()) {
                        message.addProperty("role", "system");
                    } else if (msg.isUserMessage()) {
                        message.addProperty("role", "user");
                    } else if (msg.isAIMessage()) {
                        message.addProperty("role", "assistant");
                    }
                    message.addProperty("content", msg.content);
                    messages.add(message);
                }
            }
            
            // 添加当前提示
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");
            userMessage.addProperty("content", prompt);
            messages.add(userMessage);
            
            requestBody.add("messages", messages);
            requestBody.addProperty("max_tokens", maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS);
            requestBody.addProperty("temperature", DEFAULT_TEMPERATURE);
            
            if (stream) {
                requestBody.addProperty("stream", true);
            }

            // 发送请求
            try (OutputStream os = connection.getOutputStream()) {
                os.write(gson.toJson(requestBody).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                String errorBody = readErrorStream(connection);
                throw new Exception("API 请求失败: HTTP " + responseCode + " - " + errorBody);
            }

            if (stream) {
                return readStreamResponse(connection, callback);
            } else {
                return readFullResponse(connection);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 调用 Anthropic Claude API
     */
    private String callAnthropicAPI(String apiUrl, String apiKey, String modelName,
                                    String prompt, List<ChatMessage> history,
                                    int maxTokens, boolean stream, StreamCallback callback) throws Exception {
        String fullUrl = apiUrl.endsWith("/") ? apiUrl + "v1/messages" : apiUrl + "/v1/messages";
        
        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        
        // 禁用SSL证书验证以支持各种服务
        SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("x-api-key", apiKey);
            connection.setRequestProperty("anthropic-version", "2023-06-01");
            connection.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
            connection.setDoOutput(true);

            // 构建请求体
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);
            
            // 构建消息列表（Anthropic 格式）
            JsonArray messages = new JsonArray();
            
            // 添加历史消息
            if (history != null) {
                for (ChatMessage msg : history) {
                    if (msg.isUserMessage() || msg.isAIMessage()) {
                        JsonObject message = new JsonObject();
                        message.addProperty("role", msg.isUserMessage() ? "user" : "assistant");
                        message.addProperty("content", msg.content);
                        messages.add(message);
                    }
                }
            }
            
            // 添加当前提示
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");
            userMessage.addProperty("content", prompt);
            messages.add(userMessage);
            
            requestBody.add("messages", messages);
            requestBody.addProperty("max_tokens", maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS);
            
            if (stream) {
                requestBody.addProperty("stream", true);
            }

            // 发送请求
            try (OutputStream os = connection.getOutputStream()) {
                os.write(gson.toJson(requestBody).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                String errorBody = readErrorStream(connection);
                throw new Exception("API 请求失败: HTTP " + responseCode + " - " + errorBody);
            }

            if (stream) {
                return readAnthropicStreamResponse(connection, callback);
            } else {
                return readAnthropicFullResponse(connection);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 读取流式响应（OpenAI SSE 格式）
     */
    private String readStreamResponse(HttpURLConnection connection, StreamCallback callback) throws Exception {
        StringBuilder fullText = new StringBuilder();
        InputStream inputStream = connection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    try {
                        JsonObject json = JsonParser.parseString(data).getAsJsonObject();
                        JsonArray choices = json.getAsJsonArray("choices");
                        if (choices != null && choices.size() > 0) {
                            JsonObject choice = choices.get(0).getAsJsonObject();
                            if (choice.has("delta")) {
                                JsonObject delta = choice.getAsJsonObject("delta");
                                if (delta.has("content")) {
                                    String content = delta.get("content").getAsString();
                                    fullText.append(content);
                                    final String token = content;
                                    mainHandler.post(() -> callback.onToken(token));
                                }
                            }
                        }
                    } catch (Exception e) {
                        AILogger.w(TAG, "Failed to parse SSE line: " + data);
                    }
                }
            }
        } finally {
            reader.close();
        }
        
        String result = fullText.toString();
        mainHandler.post(() -> callback.onComplete(result));
        // 注意：流式响应结束后无法获取 usage 统计信息
        return result;
    }

    /**
     * 读取完整响应（OpenAI 格式）
     */
    private String readFullResponse(HttpURLConnection connection) throws Exception {
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
        
        JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
        
        // 解析 Token 统计信息
        parseAndNotifyTokenStats(json);
        
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices != null && choices.size() > 0) {
            JsonObject choice = choices.get(0).getAsJsonObject();
            if (choice.has("message")) {
                JsonObject message = choice.getAsJsonObject("message");
                if (message.has("content")) {
                    return message.get("content").getAsString();
                }
            }
        }
        
        throw new Exception("无法解析 API 响应");
    }
    
    /**
     * 解析并通知 Token 统计信息
     */
    private void parseAndNotifyTokenStats(JsonObject json) {
        try {
            if (json.has("usage")) {
                JsonObject usage = json.getAsJsonObject("usage");
                int promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").getAsInt() : 0;
                int completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").getAsInt() : 0;
                
                // 更新 TokenStatsManager
                TokenStatsManager.getInstance().updateRequestStats(promptTokens, completionTokens);
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to parse token stats: " + e.getMessage());
        }
    }

    /**
     * 读取 Anthropic 流式响应
     */
    private String readAnthropicStreamResponse(HttpURLConnection connection, StreamCallback callback) throws Exception {
        StringBuilder fullText = new StringBuilder();
        InputStream inputStream = connection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    try {
                        JsonObject json = JsonParser.parseString(data).getAsJsonObject();
                        if (json.has("type")) {
                            String type = json.get("type").getAsString();
                            if ("content_block_delta".equals(type) && json.has("delta")) {
                                JsonObject delta = json.getAsJsonObject("delta");
                                if (delta.has("text")) {
                                    String content = delta.get("text").getAsString();
                                    fullText.append(content);
                                    final String token = content;
                                    mainHandler.post(() -> callback.onToken(token));
                                }
                            }
                        }
                    } catch (Exception e) {
                        AILogger.w(TAG, "Failed to parse Anthropic SSE line: " + data);
                    }
                }
            }
        } finally {
            reader.close();
        }
        
        String result = fullText.toString();
        mainHandler.post(() -> callback.onComplete(result));
        return result;
    }

    /**
     * 读取 Anthropic 完整响应
     */
    private String readAnthropicFullResponse(HttpURLConnection connection) throws Exception {
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
        
        JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
        
        // 解析 Anthropic Token 统计信息
        parseAndNotifyAnthropicTokenStats(json);
        
        if (json.has("content") && json.get("content").isJsonArray()) {
            JsonArray content = json.getAsJsonArray("content");
            for (int i = 0; i < content.size(); i++) {
                JsonObject block = content.get(i).getAsJsonObject();
                if ("text".equals(block.get("type").getAsString()) && block.has("text")) {
                    return block.get("text").getAsString();
                }
            }
        }
        
        throw new Exception("无法解析 Anthropic API 响应");
    }
    
    /**
     * 解析并通知 Anthropic Token 统计信息
     */
    private void parseAndNotifyAnthropicTokenStats(JsonObject json) {
        try {
            if (json.has("usage")) {
                JsonObject usage = json.getAsJsonObject("usage");
                int promptTokens = usage.has("input_tokens") ? usage.get("input_tokens").getAsInt() : 0;
                int completionTokens = usage.has("output_tokens") ? usage.get("output_tokens").getAsInt() : 0;
                
                // 更新 TokenStatsManager
                TokenStatsManager.getInstance().updateRequestStats(promptTokens, completionTokens);
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to parse Anthropic token stats: " + e.getMessage());
        }
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
     * 在主线程回调错误
     */
    private void postError(StreamCallback callback, String error) {
        mainHandler.post(() -> callback.onError(error));
    }

    /**
     * 关闭服务
     */
    public void shutdown() {
        executor.shutdown();
    }
}