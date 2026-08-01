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
import com.oilquiz.app.ai.importing.ImportValidator;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.stats.TokenStatsManager;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.util.SSLSocketFactoryUtil;
import com.oilquiz.app.util.AILogger;

import org.json.JSONObject;

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

    // 结构化输出能力缓存:key=apiUrl+modelName, value=true支持/false不支持/null未知
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> structuredCapabilityCache = new java.util.concurrent.ConcurrentHashMap<>();

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
     * 异步生成（非流式），默认启用工具调用
     */
    public CompletableFuture<String> generateAsync(String prompt, OnlineModelManager.OnlineModelConfig config,
                                                  List<ChatMessage> history, int maxTokens) {
        return generateAsync(prompt, config, history, maxTokens, true);
    }

    /**
     * 异步生成（非流式），支持控制是否启用工具调用
     * enableTools=true 时使用 OpenAI 原生 function calling，模型返回结构化 tool_calls
     * 工具调用结果转换为兼容 AgentService.parseToolCalls 的文本格式，无缝接入现有 Agent 执行循环
     */
    public CompletableFuture<String> generateAsync(String prompt, OnlineModelManager.OnlineModelConfig config,
                                                  List<ChatMessage> history, int maxTokens, boolean enableTools) {
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
                    // 尝试使用 OpenAI 原生 function calling
                    if (enableTools) {
                        String toolsJson = null;
                        try {
                            toolsJson = AIToolManager.getInstance().getOpenAIToolDefinitions();
                        } catch (Exception e) {
                            AILogger.w(TAG, "Failed to get tool definitions, falling back to no-tools mode: " + e.getMessage());
                        }
                        if (toolsJson != null && !toolsJson.isEmpty()) {
                            return callOpenAIWithTools(apiUrl, apiKey, modelName, prompt, history, maxTokens, toolsJson);
                        }
                    }
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
     * 调用 OpenAI 兼容 API（带工具调用 function calling）
     * 发送 tools 参数，解析结构化 tool_calls 响应，转换为兼容 parseToolCalls 的文本格式
     */
    private String callOpenAIWithTools(String apiUrl, String apiKey, String modelName,
                                        String prompt, List<ChatMessage> history,
                                        int maxTokens, String toolsJson) throws Exception {
        String fullUrl = buildOpenAIUrl(apiUrl, "/chat/completions");

        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);

        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);

            JsonArray messages = new JsonArray();
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
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");
            userMessage.addProperty("content", prompt);
            messages.add(userMessage);

            requestBody.add("messages", messages);
            requestBody.addProperty("max_tokens", maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS);
            requestBody.addProperty("temperature", DEFAULT_TEMPERATURE);

            // 添加 tools 参数（OpenAI function calling 原生格式）
            if (toolsJson != null && !toolsJson.isEmpty()) {
                JsonArray tools = JsonParser.parseString(toolsJson).getAsJsonArray();
                requestBody.add("tools", tools);
            }

            try (OutputStream os = connection.getOutputStream()) {
                os.write(gson.toJson(requestBody).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                String errorBody = readErrorStream(connection);
                AILogger.w(TAG, "OpenAI WithTools API failed: HTTP " + responseCode + ", falling back to no-tools mode");
                // 工具调用失败时降级为普通调用（某些模型不支持 function calling）
                return callOpenAIAPI(apiUrl, apiKey, modelName, prompt, history, maxTokens, false, null);
            }

            return readFullResponseWithTools(connection);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 读取完整响应并检查 tool_calls（OpenAI 原生 function calling）
     * 如果有 tool_calls，转换为兼容 AgentService.parseToolCalls 的文本格式
     */
    private String readFullResponseWithTools(HttpURLConnection connection) throws Exception {
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
        parseAndNotifyTokenStats(json);

        JsonArray choices = json.getAsJsonArray("choices");
        if (choices != null && choices.size() > 0) {
            JsonObject choice = choices.get(0).getAsJsonObject();
            if (choice.has("message")) {
                JsonObject message = choice.getAsJsonObject("message");

                // 检查是否有 tool_calls（OpenAI 原生 function calling 响应）
                if (message.has("tool_calls") && !message.get("tool_calls").isJsonNull()) {
                    JsonArray toolCalls = message.getAsJsonArray("tool_calls");
                    String toolCallText = convertToolCallsToText(toolCalls);
                    AILogger.i(TAG, "OpenAI native tool_calls detected: " + toolCallText.substring(0, Math.min(200, toolCallText.length())));
                    return toolCallText;
                }

                // 普通文本响应
                if (message.has("content") && !message.get("content").isJsonNull()) {
                    return message.get("content").getAsString();
                }
            }
        }

        throw new Exception("无法解析 API 响应");
    }

    /**
     * 将 OpenAI 结构化 tool_calls 转换为文本格式
     * OpenAI 格式: [{"id":"call_xxx","type":"function","function":{"name":"...","arguments":"{...}"}}]
     * 输出格式: {"tool_calls":[{"function":{"name":"工具名","arguments":{...}}}]}
     * 匹配 AgentService.TOOL_CALL_PATTERN_OPENAI 正则，无缝接入现有 Agent 执行循环
     */
    private String convertToolCallsToText(JsonArray toolCalls) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"tool_calls\":[");
        int count = 0;
        for (int i = 0; i < toolCalls.size(); i++) {
            try {
                JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
                if (!toolCall.has("function")) continue;
                JsonObject function = toolCall.getAsJsonObject("function");
                String name = function.get("name").getAsString();
                String arguments = function.get("arguments").getAsString();

                // OpenAI 的 arguments 是 JSON 字符串，解析为对象后输出（正则期望对象非字符串）
                JSONObject argsObj;
                try {
                    argsObj = new JSONObject(arguments);
                } catch (Exception e) {
                    argsObj = new JSONObject();
                }

                if (count > 0) sb.append(",");
                // 输出 OpenAI 原生格式，匹配 TOOL_CALL_PATTERN_OPENAI 正则
                sb.append("{\"function\":{\"name\":\"").append(name)
                  .append("\",\"arguments\":").append(argsObj.toString()).append("}}");
                count++;
            } catch (Exception e) {
                AILogger.w(TAG, "Failed to convert tool_call at index " + i + ": " + e.getMessage());
            }
        }
        sb.append("]}");
        return sb.toString();
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
     * 结构化输出异步生成(题库 AI 导入专用)
     * 优先尝试 response_format json_schema(strict)约束解码;
     * API 不支持或解析失败时,降级为 prompt+JSON repair+schema 校验+重试(上限2次)。
     *
     * @param prompt    用户提示(已含要求输出 JSON 的指令)
     * @param config    在线模型配置
     * @param schema    期望的 JSON Schema(org.json.JSONObject),用于 response_format 或降级校验
     * @param maxTokens 最大输出 token
     * @return CompletableFuture<String> 完成时返回"经过 repair 的、可被 org.json.JSONObject 解析的"JSON 字符串;失败时 exceptionally
     */
    public CompletableFuture<String> generateStructuredAsync(String prompt,
            OnlineModelManager.OnlineModelConfig config,
            JSONObject schema, int maxTokens) {
        return CompletableFuture.supplyAsync(() -> {
            String apiUrl = config.apiUrl;
            String modelName = config.modelName;
            String apiKey = config.apiKey;

            // 校验 config(复用现有 generateAsync 的校验风格)
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
            boolean isAnthropic = isAnthropicAPI(apiUrl);
            // 能力缓存 key = apiUrl + "|" + modelName
            String capabilityKey = apiUrl + "|" + modelName;

            // 尝试结构化输出(若缓存不为 false)
            Boolean capability = structuredCapabilityCache.get(capabilityKey);
            if (capability == null || capability) {
                try {
                    String raw;
                    if (isAnthropic) {
                        raw = callAnthropicStructured(apiUrl, apiKey, modelName, prompt, schema, maxTokens);
                    } else {
                        raw = callOpenAIStructured(apiUrl, apiKey, modelName, prompt, schema, maxTokens);
                    }
                    String repaired = ImportValidator.repairJson(raw);
                    // 验证可被 org.json.JSONObject 解析
                    new JSONObject(repaired);
                    // 结构化路径首次成功 → 缓存 true
                    structuredCapabilityCache.put(capabilityKey, true);
                    return repaired;
                } catch (UnsupportedOperationException e) {
                    // Anthropic 走降级:不标记缓存,直接进入降级路径
                    AILogger.w(TAG, "Anthropic structured unsupported, fallback to prompt+repair: " + e.getMessage());
                } catch (Exception e) {
                    AILogger.w(TAG, "Structured output failed, fallback to prompt+repair: " + e.getMessage());
                    // 若缓存未知,标记 false 并走降级
                    if (capability == null) {
                        structuredCapabilityCache.put(capabilityKey, false);
                    }
                }
            }

            // 降级路径:prompt+repair+校验+重试(上限2次)
            String lastError = null;
            String currentPrompt = prompt;
            for (int attempt = 0; attempt <= 2; attempt++) {
                try {
                    String raw;
                    // 直接调用现有 callOpenAIAPI/callAnthropicAPI(stream=false, callback=null)
                    if (isAnthropic) {
                        raw = callAnthropicAPI(apiUrl, apiKey, modelName, currentPrompt, null, maxTokens, false, null);
                    } else {
                        raw = callOpenAIAPI(apiUrl, apiKey, modelName, currentPrompt, null, maxTokens, false, null);
                    }
                    String repaired = ImportValidator.repairJson(raw);
                    // 验证可解析
                    new JSONObject(repaired);
                    return repaired;
                } catch (Exception e) {
                    lastError = e.getMessage();
                    AILogger.w(TAG, "Fallback attempt " + attempt + " failed: " + lastError);
                    if (attempt < 2) {
                        // 将上次错误信息追加到 prompt 末尾
                        currentPrompt = prompt + "\n上次输出错误:" + lastError + ",请修正后重新输出严格符合 schema 的 JSON";
                    }
                }
            }
            // 重试用尽仍失败 → 抛 RuntimeException
            throw new RuntimeException("结构化输出失败,重试用尽: " + lastError);
        }, executor);
    }

    /**
     * OpenAI 结构化输出调用(独立新方法,不改动 callOpenAIAPI)
     * 在请求体加 response_format: json_schema(strict),非流式,返回 content 字符串。
     */
    private String callOpenAIStructured(String apiUrl, String apiKey, String modelName,
                                        String prompt, JSONObject schema, int maxTokens) throws Exception {
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
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);

            // 构建请求体
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);

            // 构建消息列表(仅当前提示)
            JsonArray messages = new JsonArray();
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");
            userMessage.addProperty("content", prompt);
            messages.add(userMessage);
            requestBody.add("messages", messages);
            requestBody.addProperty("max_tokens", maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS);
            requestBody.addProperty("temperature", DEFAULT_TEMPERATURE);

            // 添加 response_format: json_schema(strict) 约束解码
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_schema");
            JsonObject jsonSchemaWrapper = new JsonObject();
            jsonSchemaWrapper.addProperty("name", "question_extraction");
            jsonSchemaWrapper.addProperty("strict", true);
            // 将 org.json.JSONObject schema 转为 Gson JsonObject
            jsonSchemaWrapper.add("schema", JsonParser.parseString(schema.toString()).getAsJsonObject());
            responseFormat.add("json_schema", jsonSchemaWrapper);
            requestBody.add("response_format", responseFormat);

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

            // 复用 readFullResponse 取 content
            return readFullResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Anthropic 结构化输出调用(简化实现:暂不支持,直接抛异常走降级)
     * Anthropic tool use 模式(tools + tool_choice required)实现较复杂,
     * 此处直接降级到 prompt+repair 路径,由 generateStructuredAsync 捕获后处理。
     */
    private String callAnthropicStructured(String apiUrl, String apiKey, String modelName,
                                           String prompt, JSONObject schema, int maxTokens) throws Exception {
        throw new UnsupportedOperationException("Anthropic 走降级");
    }

    /**
     * 关闭服务
     */
    public void shutdown() {
        executor.shutdown();
    }
}