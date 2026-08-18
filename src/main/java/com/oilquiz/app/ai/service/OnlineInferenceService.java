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
import java.util.HashMap;
import java.util.LinkedHashMap;
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

                String result;
                // 判断 API 类型
                if (isAnthropicAPI(apiUrl)) {
                    result = callAnthropicAPI(apiUrl, apiKey, modelName, prompt, history, maxTokens, false, null);
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
                            result = callOpenAIWithTools(apiUrl, apiKey, modelName, prompt, history, maxTokens, toolsJson);
                        } else {
                            result = callOpenAIAPI(apiUrl, apiKey, modelName, prompt, history, maxTokens, false, null);
                        }
                    } else {
                        result = callOpenAIAPI(apiUrl, apiKey, modelName, prompt, history, maxTokens, false, null);
                    }
                }
                // 清理模型输出中的乱码/非法字符
                String cleaned = com.oilquiz.app.ai.agent.ToolResultInterpreter.cleanModelOutput(result);
                if (cleaned != null) {
                    return cleaned;
                } else if (result != null) {
                    AILogger.w(TAG, "在线模型输出检测为乱码，已清理非法字符");
                    return com.oilquiz.app.ai.agent.ToolResultInterpreter.sanitize(result);
                }
                return result;
            } catch (Exception e) {
                AILogger.e(TAG, "Async generate failed: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    /**
     * 纯单次推理（专供数据修复等批量场景）：
     * <ul>
     *   <li>非流式、单次请求，无重试、无降级、无兜底；</li>
     *   <li>显式关闭 thinking（enable_thinking=false），防思考模型把 token 预算耗在
     *       reasoning 上导致 content 返回空；</li>
     *   <li>content 为空时回退读取 reasoning_content，避免整批白等。</li>
     * </ul>
     */
    public CompletableFuture<String> generateOnceAsync(String prompt,
            OnlineModelManager.OnlineModelConfig config, int maxTokens) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiUrl = config.apiUrl;
                String modelName = config.modelName;
                String apiKey = config.apiKey;
                if (apiUrl == null || apiUrl.isEmpty()) throw new IllegalArgumentException("API URL 不能为空");
                if (modelName == null || modelName.isEmpty()) throw new IllegalArgumentException("模型名称不能为空");
                if (apiKey == null || apiKey.isEmpty()) throw new IllegalArgumentException("API Key 不能为空");
                if (isAnthropicAPI(apiUrl)) {
                    String result = callAnthropicAPI(apiUrl, apiKey, modelName, prompt, null, maxTokens, false, null);
                    return cleanOrSanitize(result);
                }
                String result = callOpenAIAPIOnce(apiUrl, apiKey, modelName, prompt, maxTokens);
                return cleanOrSanitize(result);
            } catch (Exception e) {
                AILogger.e(TAG, "generateOnce failed: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    /** 清理模型输出；清理后为空则返回原文 */
    private String cleanOrSanitize(String result) {
        if (result == null) return null;
        String cleaned = com.oilquiz.app.ai.agent.ToolResultInterpreter.cleanModelOutput(result);
        return cleaned != null ? cleaned : result;
    }

    /**
     * OpenAI 兼容 API 单次请求：关闭 thinking，content 为空时回退 reasoning_content。
     */
    private String callOpenAIAPIOnce(String apiUrl, String apiKey, String modelName,
                                     String prompt, int maxTokens) throws Exception {
        String fullUrl = buildOpenAIUrl(apiUrl, "/chat/completions");
        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        try {
            connection.setRequestMethod("POST");
            // 批量修复输出较长，读超时提升到 90 秒（默认 30 秒对 5 题批量不够）
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(90000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);
            JsonArray messages = new JsonArray();
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");
            userMessage.addProperty("content", prompt);
            messages.add(userMessage);
            requestBody.add("messages", messages);
            requestBody.addProperty("max_tokens", maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS);
            requestBody.addProperty("temperature", DEFAULT_TEMPERATURE);
            // 关闭思考模式：防 reasoning 耗尽 token 预算导致 content 为空（DeepSeek/Qwen3 等）
            requestBody.addProperty("enable_thinking", false);
            // vLLM/llama.cpp 类服务端参数位置在 chat_template_kwargs 内，双位置下发兼容
            JsonObject chatTemplateKwargs = new JsonObject();
            chatTemplateKwargs.addProperty("enable_thinking", false);
            requestBody.add("chat_template_kwargs", chatTemplateKwargs);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(gson.toJson(requestBody).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                String errorBody = readErrorStream(connection);
                throw new Exception("API 请求失败: HTTP " + responseCode + " - " + errorBody);
            }

            InputStream inputStream = connection.getInputStream();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8));
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
                    String content = message.has("content") && !message.get("content").isJsonNull()
                            ? message.get("content").getAsString() : "";
                    if (!content.isEmpty()) return content;
                    // 回退：思考模型可能把全部内容放在 reasoning_content
                    if (message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull()) {
                        String reasoning = message.get("reasoning_content").getAsString();
                        if (!reasoning.isEmpty()) {
                            AILogger.w(TAG, "content为空，回退使用reasoning_content(长度" + reasoning.length() + ")");
                            return reasoning;
                        }
                    }
                    return "";
                }
            }
            throw new Exception("无法解析 API 响应");
        } finally {
            connection.disconnect();
        }
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
     * 在线多模态生成：带图片（base64 data URL）的 OpenAI 兼容请求。
     * 图片以 OpenAI 多模态 content 数组格式注入最后一条 user 消息：
     * [{type:text,text:prompt}, {type:image_url,image_url:{url:"data:image/jpeg;base64,..."}}]
     * 支持 Qwen-VL / GPT-4o 等兼容 OpenAI 图片消息的模型。
     *
     * @param imageBase64List 图片 base64 数据（不含前缀），将自动加 data:image/jpeg;base64 前缀
     */
    public void generateStreamWithImages(String prompt, List<String> imageBase64List,
                                         OnlineModelManager.OnlineModelConfig config,
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
                if (imageBase64List == null || imageBase64List.isEmpty()) {
                    generateStream(prompt, config, history, maxTokens, callback);
                    return;
                }

                mainHandler.post(callback::onStart);

                if (isAnthropicAPI(apiUrl)) {
                    postError(callback, "当前 API 端点不支持图片消息，已回退请用 OCR 或本地多模态");
                    return;
                }
                callOpenAIAPIWithImages(apiUrl, apiKey, modelName, prompt, imageBase64List,
                        history, maxTokens, true, callback);
            } catch (Exception e) {
                AILogger.e(TAG, "Stream generate with images failed: " + e.getMessage(), e);
                postError(callback, e.getMessage());
            }
        });
    }

    /**
     * 调用 OpenAI 兼容 API（带图片 content 数组）
     */
    private String callOpenAIAPIWithImages(String apiUrl, String apiKey, String modelName,
                                           String prompt, List<String> imageBase64List,
                                           List<ChatMessage> history,
                                           int maxTokens, boolean stream, StreamCallback callback) throws Exception {
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

            // 最后一条 user 消息：content 数组（文本 + 图片）
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");
            JsonArray contentArray = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("type", "text");
            textPart.addProperty("text", prompt);
            contentArray.add(textPart);
            for (String b64 : imageBase64List) {
                JsonObject imgPart = new JsonObject();
                imgPart.addProperty("type", "image_url");
                JsonObject imgUrl = new JsonObject();
                imgUrl.addProperty("url", "data:image/jpeg;base64," + b64);
                imgPart.add("image_url", imgUrl);
                contentArray.add(imgPart);
            }
            userMessage.add("content", contentArray);
            messages.add(userMessage);

            requestBody.add("messages", messages);
            requestBody.addProperty("max_tokens", maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS);
            requestBody.addProperty("temperature", DEFAULT_TEMPERATURE);
            if (stream) {
                requestBody.addProperty("stream", true);
            }

            AILogger.i(TAG, "Online multimodal request: model=" + modelName
                    + ", images=" + imageBase64List.size() + ", prompt_len=" + prompt.length());

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
        // 清理模型输出中的乱码/非法字符
        String cleaned = com.oilquiz.app.ai.agent.ToolResultInterpreter.cleanModelOutput(result);
        final String outputResult = cleaned != null ? cleaned : 
            (result != null ? com.oilquiz.app.ai.agent.ToolResultInterpreter.sanitize(result) : result);
        mainHandler.post(() -> callback.onComplete(outputResult));
        // 注意：流式响应结束后无法获取 usage 统计信息
        return outputResult;
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
        parseAndNotifyTokenStats(json, null);
    }

    private void parseAndNotifyTokenStats(JsonObject json, StreamCallback callback) {
        try {
            if (json.has("usage")) {
                JsonObject usage = json.getAsJsonObject("usage");
                int promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").getAsInt() : 0;
                int completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").getAsInt() : 0;
                
                // 更新 TokenStatsManager
                TokenStatsManager.getInstance().updateRequestStats(promptTokens, completionTokens);
                
                // 回调通知调用方
                if (callback != null) {
                    mainHandler.post(() -> callback.onTokenStats(promptTokens, completionTokens));
                }
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
        // 清理模型输出中的乱码/非法字符
        String cleaned = com.oilquiz.app.ai.agent.ToolResultInterpreter.cleanModelOutput(result);
        final String outputResult = cleaned != null ? cleaned : 
            (result != null ? com.oilquiz.app.ai.agent.ToolResultInterpreter.sanitize(result) : result);
        mainHandler.post(() -> callback.onComplete(outputResult));
        return outputResult;
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
        parseAndNotifyAnthropicTokenStats(json, null);
    }

    private void parseAndNotifyAnthropicTokenStats(JsonObject json, StreamCallback callback) {
        try {
            if (json.has("usage")) {
                JsonObject usage = json.getAsJsonObject("usage");
                int promptTokens = usage.has("input_tokens") ? usage.get("input_tokens").getAsInt() : 0;
                int completionTokens = usage.has("output_tokens") ? usage.get("output_tokens").getAsInt() : 0;
                
                // 更新 TokenStatsManager
                TokenStatsManager.getInstance().updateRequestStats(promptTokens, completionTokens);
                
                // 回调通知调用方
                if (callback != null) {
                    mainHandler.post(() -> callback.onTokenStats(promptTokens, completionTokens));
                }
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
                    // 清理模型输出中的乱码/非法字符
                    String cleaned = com.oilquiz.app.ai.agent.ToolResultInterpreter.cleanModelOutput(repaired);
                    return cleaned != null ? cleaned : repaired;
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
                    // 清理模型输出中的乱码/非法字符
                    String cleaned = com.oilquiz.app.ai.agent.ToolResultInterpreter.cleanModelOutput(repaired);
                    return cleaned != null ? cleaned : repaired;
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

    // ========== 原生工具流式支持 ==========

    /**
     * 原生工具流式回调接口。
     * 支持 reasoning_content（思考链）、content（正文）、tool_calls（工具调用）的增量流式回调。
     */
    public interface NativeToolStreamCallback {
        void onStart();
        void onReasoningToken(String token);
        void onContentToken(String token);
        void onToolCallsReady(List<ToolCallInfo> toolCalls);
        /**
         * 流式完成回调。
         * @param finishReason 完成原因：stop(自然结束)/tool_calls(调用工具)/length(达到token上限)/content_filter(内容过滤)/null(未知)
         */
        void onComplete(String fullContent, String reasoningContent, List<ToolCallInfo> toolCalls, String finishReason);
        void onError(String error);
        /**
         * 是否已取消。流读取循环会在每个 SSE chunk 之间检查，
         * 返回 true 时立即中断读取。默认返回 false。
         */
        default boolean isCancelled() { return false; }
        /**
         * API 返回的 token 用量（需请求中带 stream_options.include_usage）。
         * prompt_tokens 包含 system 消息 + 工具定义 + 历史消息的 token。
         * cachedTokens：前缀缓存命中 token（DeepSeek prompt_cache_hit_tokens / OpenAI cached_tokens），0 表示未命中/不支持。
         * 默认空实现，调用方可覆盖以精确更新推理速度显示与缓存统计。
         */
        default void onUsage(int promptTokens, int completionTokens, int totalTokens) {}
        default void onUsageWithCache(int promptTokens, int completionTokens, int totalTokens, int cachedTokens) {}
    }

    /**
     * 工具调用信息（流式累积后最终结果）
     */
    public static class ToolCallInfo {
        public String id;
        public String name;
        public String arguments; // JSON 字符串
    }

    /**
     * 流式生成（带原生工具调用支持）。
     * 向 API 发送 tools 参数，流式解析 reasoning_content、content、tool_calls。
     *
     * @param prompt    用户提示
     * @param config    在线模型配置
     * @param history   对话历史（含 system/user/assistant/tool 角色消息）
     * @param maxTokens 最大输出 token
     * @param toolsJson OpenAI 格式的工具定义 JSON 字符串
     * @param callback  回调
     */
    public void generateStreamWithTools(String prompt,
                                         OnlineModelManager.OnlineModelConfig config,
                                         List<ChatMessage> history, int maxTokens,
                                         String toolsJson,
                                         NativeToolStreamCallback callback) {
        executor.execute(() -> {
            try {
                String apiUrl = config.apiUrl;
                String modelName = config.modelName;
                String apiKey = config.apiKey;

                if (apiUrl == null || apiUrl.isEmpty()) {
                    callback.onError("API URL 不能为空");
                    return;
                }
                if (modelName == null || modelName.isEmpty()) {
                    callback.onError("模型名称不能为空");
                    return;
                }
                if (apiKey == null || apiKey.isEmpty()) {
                    callback.onError("API Key 不能为空");
                    return;
                }

                mainHandler.post(callback::onStart);

                if (isAnthropicAPI(apiUrl)) {
                    AILogger.w(TAG, "Anthropic API does not support streaming tools, falling back to plain stream");
                    generateStreamFallback(prompt, config, history, maxTokens, callback);
                } else {
                    callOpenAIStreamWithTools(apiUrl, apiKey, modelName, prompt,
                        history, maxTokens, toolsJson, callback);
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Stream with tools failed: " + e.getMessage(), e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    /**
     * 流式生成 V2（带原生工具调用支持）。
     * 直接接收 JsonArray 消息列表，支持所有 OpenAI 角色包括 tool 和 assistant+tool_calls。
     * 不依赖 ChatMessage，完全解耦。
     *
     * @param messages  OpenAI 格式消息列表（JsonArray，每条含 role/content/可选 tool_calls/tool_call_id）
     * @param config    在线模型配置
     * @param maxTokens 最大输出 token
     * @param toolsJson OpenAI 格式的工具定义 JSON 字符串（null 时不发送 tools）
     * @param callback  回调
     */
    public void generateStreamWithToolsV2(JsonArray messages,
                                            OnlineModelManager.OnlineModelConfig config,
                                            int maxTokens, String toolsJson,
                                            NativeToolStreamCallback callback) {
        executor.execute(() -> {
            try {
                String apiUrl = config.apiUrl;
                String modelName = config.modelName;
                String apiKey = config.apiKey;

                if (apiUrl == null || apiUrl.isEmpty()) {
                    callback.onError("API URL 不能为空");
                    return;
                }
                if (modelName == null || modelName.isEmpty()) {
                    callback.onError("模型名称不能为空");
                    return;
                }
                if (apiKey == null || apiKey.isEmpty()) {
                    callback.onError("API Key 不能为空");
                    return;
                }

                mainHandler.post(callback::onStart);

                if (isAnthropicAPI(apiUrl)) {
                    // Anthropic 不支持流式工具调用，降级
                    AILogger.w(TAG, "Anthropic API does not support streaming tools, falling back");
                    String prompt = extractLastUserContent(messages);
                    generateStreamFallback(prompt, config, null, maxTokens, callback);
                } else {
                    callOpenAIStreamWithToolsV2(apiUrl, apiKey, modelName,
                        messages, maxTokens, toolsJson, callback);
                }
            } catch (Exception e) {
                AILogger.e(TAG, "StreamV2 with tools failed: " + e.getMessage(), e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    /**
     * 从 JsonArray 消息中提取最后一条 user 消息的 content
     */
    private String extractLastUserContent(JsonArray messages) {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonObject msg = messages.get(i).getAsJsonObject();
            if (msg.has("role") && "user".equals(msg.get("role").getAsString())) {
                return msg.has("content") ? msg.get("content").getAsString() : "";
            }
        }
        return "";
    }

    /**
     * 调用 OpenAI 流式 API V2（带 tools 参数，直接使用 JsonArray 消息）
     */
    private void callOpenAIStreamWithToolsV2(String apiUrl, String apiKey, String modelName,
                                              JsonArray messages, int maxTokens,
                                              String toolsJson,
                                              NativeToolStreamCallback callback) throws Exception {
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
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setDoOutput(true);

            // 构建请求体 —— 直接使用传入的 messages JsonArray
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);
            requestBody.add("messages", messages);
            requestBody.addProperty("max_tokens", maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS);
            requestBody.addProperty("temperature", DEFAULT_TEMPERATURE);
            requestBody.addProperty("stream", true);
            // 请求流式 usage（缓存命中统计等）：OpenAI/DeepSeek 标准 stream_options.include_usage
            try {
                JsonObject streamOptions = new JsonObject();
                streamOptions.addProperty("include_usage", true);
                requestBody.add("stream_options", streamOptions);
            } catch (Exception ignored) {}

            // 添加 tools 参数（跳过空数组，避免 API 忽略或报错）
            if (toolsJson != null && !toolsJson.isEmpty() && !toolsJson.equals("[]")) {
                try {
                    JsonArray tools = JsonParser.parseString(toolsJson).getAsJsonArray();
                    if (tools.size() > 0) {
                        requestBody.add("tools", tools);
                        AILogger.i(TAG, "Added " + tools.size() + " tools to API request");
                    }
                } catch (Exception e) {
                    AILogger.w(TAG, "Failed to parse toolsJson: " + e.getMessage());
                }
            }

            try (OutputStream os = connection.getOutputStream()) {
                String bodyStr = gson.toJson(requestBody);
                // 打印请求摘要（不打印完整 body 避免日志过大）
                int toolsCount = requestBody.has("tools") ? requestBody.getAsJsonArray("tools").size() : 0;
                AILogger.i(TAG, "API request: model=" + modelName + " messages=" + messages.size()
                    + " tools=" + toolsCount + " body_len=" + bodyStr.length());
                os.write(bodyStr.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                String errorBody = readErrorStream(connection);
                AILogger.w(TAG, "StreamV2 API failed: HTTP " + responseCode + ": " + errorBody);
                // 仅当 400 且错误信息明确指向 tools/function 不支持时，降级为不带 tools 的普通流式
                // 401/403/429/5xx 等错误降级必然再次失败，直接报错避免浪费请求
                if (responseCode == 400 && toolsJson != null && isToolsUnsupportedError(errorBody)) {
                    AILogger.i(TAG, "Model does not support tools (400), retrying without tools parameter");
                    callOpenAIStreamWithToolsV2(apiUrl, apiKey, modelName, messages, maxTokens, null, callback);
                    return;
                }
                String errorMsg = buildHttpErrorMessage(responseCode, errorBody);
                mainHandler.post(() -> callback.onError(errorMsg));
                return;
            }

            readStreamResponseWithTools(connection, callback);

        } finally {
            connection.disconnect();
        }
    }

    /** 依次尝试多个 JSON 字段名，返回第一个存在的整数值（兼容各服务商字段差异），无则返回 0 */
    private static int firstInt(JsonObject obj, String... keys) {
        if (obj == null) return 0;
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                try {
                    return obj.get(key).getAsInt();
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    /**
     * 判断 400 错误是否因模型不支持 tools/function calling 引起。
     * 仅匹配明确的 tools/function 不支持关键词，避免其他参数（如 stream_options）
     * 导致的 400 被误判为 tools 不支持而盲目降级。
     */
    private boolean isToolsUnsupportedError(String errorBody) {        if (errorBody == null) return false;
        String lower = errorBody.toLowerCase();
        return lower.contains("does not support tools")
            || lower.contains("tools are not supported")
            || lower.contains("function calling is not supported")
            || lower.contains("does not support function")
            || (lower.contains("unrecognized") && lower.contains("tools"));
    }

    /**
     * 构建 HTTP 错误的友好提示信息。
     */
    private String buildHttpErrorMessage(int responseCode, String errorBody) {
        String brief = errorBody != null && errorBody.length() > 200
            ? errorBody.substring(0, 200) + "..." : errorBody;
        switch (responseCode) {
            case 401: return "API Key 无效或已过期（401），请检查在线模型配置";
            case 403: return "API 访问被拒绝（403），可能无权限或 IP 受限";
            case 429: return "API 请求过于频繁（429），请稍后重试";
            case 500: case 502: case 503: case 504:
                return "模型服务暂时不可用（" + responseCode + "），请稍后重试";
            case 400: return "请求参数错误（400）: " + brief;
            default: return "API 返回 " + responseCode + ": " + brief;
        }
    }

    /**
     * 降级：普通流式 + 文本格式工具调用（兼容旧逻辑）
     */
    private void generateStreamFallback(String prompt,
                                         OnlineModelManager.OnlineModelConfig config,
                                         List<ChatMessage> history, int maxTokens,
                                         NativeToolStreamCallback callback) {
        generateStream(prompt, config, history, maxTokens, new StreamCallback() {
            @Override
            public void onStart() {
                mainHandler.post(callback::onStart);
            }

            @Override
            public void onToken(String token) {
                mainHandler.post(() -> callback.onContentToken(token));
            }

            @Override
            public void onComplete(String fullText) {
                mainHandler.post(() -> callback.onComplete(fullText, "", null, "stop"));
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> callback.onError(error));
            }
        });
    }

    /**
     * 调用 OpenAI 流式 API（带 tools 参数）
     */
    private void callOpenAIStreamWithTools(String apiUrl, String apiKey, String modelName,
                                            String prompt, List<ChatMessage> history,
                                            int maxTokens, String toolsJson,
                                            NativeToolStreamCallback callback) throws Exception {
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
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setDoOutput(true);

            // 构建请求体
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);

            // 构建消息列表
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
                    } else {
                        message.addProperty("role", "user"); // 默认
                    }
                    message.addProperty("content", msg.content != null ? msg.content : "");
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
            requestBody.addProperty("stream", true);

            // 添加 tools 参数
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
                AILogger.w(TAG, "StreamWithTools API failed: HTTP " + responseCode + ": " + errorBody);
                // 降级为普通流式
                AILogger.i(TAG, "Falling back to plain stream without tools");
                generateStreamFallback(prompt, OnlineModelManager.getInstance(context).getActiveModel(),
                    history, maxTokens, callback);
                return;
            }

            readStreamResponseWithTools(connection, callback);

        } finally {
            connection.disconnect();
        }
    }

    /**
     * 读取流式响应，解析 reasoning_content、content、tool_calls 三种 delta
     */
    private void readStreamResponseWithTools(HttpURLConnection connection,
                                              NativeToolStreamCallback callback) throws Exception {
        StringBuilder contentBuf = new StringBuilder();
        StringBuilder reasoningBuf = new StringBuilder();
        // 按 index 累积 tool_calls
        Map<Integer, ToolCallInfo> toolCallMap = new LinkedHashMap<>();
        // finish_reason 持有者（最后一个 chunk 通常 delta 为空，仅含 finish_reason）
        final String[] finishReasonHolder = {null};

        InputStream inputStream = connection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

        String line;
        try {
            while ((line = reader.readLine()) != null) {
                // 取消检查：调用方请求取消时立即中断流读取
                if (callback.isCancelled()) {
                    AILogger.i(TAG, "Stream cancelled by caller, stopping read");
                    break;
                }
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if (data.equals("[DONE]")) break;

                try {
                    JsonObject json = JsonParser.parseString(data).getAsJsonObject();

                    // 解析 usage（通常在 choices 为空的最后一个 chunk，需请求带 stream_options.include_usage）
                    if (json.has("usage") && !json.get("usage").isJsonNull()) {
                        try {
                            JsonObject usage = json.getAsJsonObject("usage");
                            // 调试：打印原始 usage 结构（确认服务商字段名，正式可移除）
                            AILogger.i(TAG, "API usage raw: " + usage.toString());
                            int promptTokens = firstInt(usage, "prompt_tokens", "input_tokens");
                            int completionTokens = firstInt(usage, "completion_tokens", "output_tokens");
                            int totalTokens = firstInt(usage, "total_tokens", "prompt_tokens", "input_tokens")
                                    + firstInt(usage, "completion_tokens", "output_tokens");
                            if (usage.has("total_tokens")) {
                                totalTokens = usage.get("total_tokens").getAsInt();
                            }
                            // 缓存命中统计，兼容多种结构：
                            // 1) OpenAI/Moonshot/通义: usage.prompt_tokens_details.cached_tokens（嵌套）
                            // 2) DeepSeek/智谱: usage.prompt_cache_hit_tokens（顶层）
                            // 3) 兜底: usage.cached_tokens（顶层）
                            int cachedTokens = 0;
                            if (usage.has("prompt_tokens_details")
                                    && !usage.get("prompt_tokens_details").isJsonNull()) {
                                JsonObject details = usage.getAsJsonObject("prompt_tokens_details");
                                if (details.has("cached_tokens") && !details.get("cached_tokens").isJsonNull()) {
                                    cachedTokens = details.get("cached_tokens").getAsInt();
                                }
                            }
                            if (cachedTokens == 0 && usage.has("prompt_cache_hit_tokens")) {
                                cachedTokens = usage.get("prompt_cache_hit_tokens").getAsInt();
                            }
                            if (cachedTokens == 0 && usage.has("cached_tokens")) {
                                cachedTokens = usage.get("cached_tokens").getAsInt();
                            }
                            final int pt = promptTokens, ct = completionTokens, tt = totalTokens, cache = cachedTokens;
                            AILogger.i(TAG, "API usage: prompt=" + pt + " completion=" + ct
                                + " total=" + tt + " cache_hit=" + cache);
                            mainHandler.post(() -> {
                                callback.onUsage(pt, ct, tt);
                                callback.onUsageWithCache(pt, ct, tt, cache);
                            });
                        } catch (Exception ex) {
                            AILogger.w(TAG, "Failed to parse usage: " + ex.getMessage());
                        }
                    }

                    JsonArray choices = json.getAsJsonArray("choices");
                    if (choices == null || choices.isEmpty()) continue;

                    JsonObject choice = choices.get(0).getAsJsonObject();

                    // 解析 finish_reason（可能出现在最后一个 chunk，此时 delta 可能为空）
                    if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                        finishReasonHolder[0] = choice.get("finish_reason").getAsString();
                    }

                    if (!choice.has("delta")) continue;
                    JsonObject delta = choice.getAsJsonObject("delta");

                    // 1. 解析 reasoning_content（思考链）
                    if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                        String token = delta.get("reasoning_content").getAsString();
                        reasoningBuf.append(token);
                        final String t = token;
                        mainHandler.post(() -> callback.onReasoningToken(t));
                    }

                    // 2. 解析 content（正文）
                    if (delta.has("content") && !delta.get("content").isJsonNull()) {
                        String token = delta.get("content").getAsString();
                        contentBuf.append(token);
                        final String t = token;
                        mainHandler.post(() -> callback.onContentToken(t));
                    }

                    // 3. 解析 tool_calls（工具调用增量）
                    if (delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull()) {
                        JsonArray toolCallsArray = delta.getAsJsonArray("tool_calls");
                        for (int i = 0; i < toolCallsArray.size(); i++) {
                            JsonObject tc = toolCallsArray.get(i).getAsJsonObject();
                            int index = tc.has("index") ? tc.get("index").getAsInt() : 0;

                            ToolCallInfo info = toolCallMap.get(index);
                            if (info == null) {
                                info = new ToolCallInfo();
                                toolCallMap.put(index, info);
                            }

                            // 首次 delta 包含 id 和 function.name
                            if (tc.has("id") && !tc.get("id").isJsonNull()) {
                                info.id = tc.get("id").getAsString();
                            }
                            if (tc.has("type") && !tc.get("type").isJsonNull()) {
                                // type = "function"，不需要存储
                            }

                            if (tc.has("function") && !tc.get("function").isJsonNull()) {
                                JsonObject function = tc.getAsJsonObject("function");
                                if (function.has("name") && !function.get("name").isJsonNull()) {
                                    info.name = function.get("name").getAsString();
                                }
                                if (function.has("arguments") && !function.get("arguments").isJsonNull()) {
                                    String argsDelta = function.get("arguments").getAsString();
                                    info.arguments = (info.arguments == null ? "" : info.arguments) + argsDelta;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    AILogger.w(TAG, "Failed to parse SSE line in streamWithTools: " + data);
                }
            }
        } finally {
            reader.close();
        }

        // 构建 final 结果
        String fullContent = contentBuf.toString();
        String reasoningContent = reasoningBuf.toString();
        // 清理模型输出中的乱码/非法字符
        String cleaned = com.oilquiz.app.ai.agent.ToolResultInterpreter.cleanModelOutput(fullContent);
        if (cleaned != null) {
            fullContent = cleaned;
        } else if (fullContent != null) {
            fullContent = com.oilquiz.app.ai.agent.ToolResultInterpreter.sanitize(fullContent);
        }
        final String fc = fullContent;
        final String rc = reasoningContent;
        List<ToolCallInfo> toolCalls = null;
        if (!toolCallMap.isEmpty()) {
            toolCalls = new ArrayList<>(toolCallMap.values());
            // 过滤掉没有 name 的无效 tool_calls
            toolCalls.removeIf(tc -> tc.name == null || tc.name.isEmpty());
            if (toolCalls.isEmpty()) toolCalls = null;
        }

        AILogger.i(TAG, "StreamWithTools complete: content_len=" + fullContent.length()
            + " reasoning_len=" + reasoningContent.length()
            + " tool_calls=" + (toolCalls != null ? toolCalls.size() : 0));

        final List<ToolCallInfo> finalToolCalls = toolCalls;
        if (finalToolCalls != null && !finalToolCalls.isEmpty()) {
            mainHandler.post(() -> callback.onToolCallsReady(finalToolCalls));
        }
        final String fr = finishReasonHolder[0];
        mainHandler.post(() -> callback.onComplete(fc, rc, finalToolCalls, fr));
    }
}