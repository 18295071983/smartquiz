package com.oilquiz.app.ai.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.concurrent.CompletionException;
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
    /**
     * chat/agent 请求的 read 超时（首 token 前思考/长输出可远超 30s）：
     * 流式 SSE 下 HttpURLConnection 的 readTimeout 是单次 read 阻塞上限，模型静默思考
     * （DeepSeek-R1 / Qwen3-Think 等深度思考模型）超过 30s 无数据就会 SocketTimeout，
     * 与 Agent 引擎的 120s idle 保护矛盾（底层先断）。提升到与 imageGen/ASR 一致。
     */
    private static final int CHAT_READ_TIMEOUT_MS = 120_000;
    private static final int DEFAULT_MAX_TOKENS = 16384;
    private static final float DEFAULT_TEMPERATURE = 0.7f;

    private static volatile OnlineInferenceService INSTANCE;
    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final Gson gson;

    // 结构化输出能力缓存:key=apiUrl+modelName, value=true支持/false不支持/null未知
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> structuredCapabilityCache = new java.util.concurrent.ConcurrentHashMap<>();
    // function calling 能力缓存:key=apiUrl+modelName, value=true支持/false不支持/null未知
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> functionCallingCapabilityCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** M3：探测未知（网络失败）短时缓存：key=apiUrl|modelName, value=探测时间 */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> fcUnknownCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FC_UNKNOWN_RETRY_MS = 10 * 60 * 1000; // 未知结果 10 分钟内不重复探测
    private static final long FC_CACHE_TTL_MS = 7L * 24 * 3600 * 1000; // 持久化缓存 7 天 TTL
    /** 能力探测内存缓存：key = apiUrl|modelName|capability */
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> capabilityProbeCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** M9：DNS 预解析专用单线程池（避免与主请求线程争抢） */
    private static final java.util.concurrent.ExecutorService dnsExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "DNS-Resolve");
                t.setDaemon(true);
                return t;
            });

    private OnlineInferenceService(Context context) {
        this.context = context.getApplicationContext();
        // cached 线程池：chat/agent/摘要/翻译/题目生成/embedding 等全部在线请求共用，
        // 固定小池会被慢请求（HTTP read 最长 120s）占满导致后续请求无限排队（"卡死"）；
        // cached 下慢请求只占自己的线程，HTTP 超时后自动回收，互不阻塞。
        this.executor = Executors.newCachedThreadPool(r -> {
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
        // 默认不带 tools：摘要/翻译等非 agent 场景发 tools 会触发不支持 function calling 的模型 400；
        // 需要原生 function calling 的场景显式传 enableTools=true（Agent 引擎走 generateStreamWithToolsV2）
        return generateAsync(prompt, config, history, maxTokens, false);
    }

    /**
     * 异步生成（非流式），支持控制是否启用工具调用
     * enableTools=true 时使用 OpenAI 原生 function calling，模型返回结构化 tool_calls
     * 工具调用结果转换为兼容 AgentService.parseToolCalls 的文本格式，无缝接入现有 Agent 执行循环
     */
    public CompletableFuture<String> generateAsync(String prompt, OnlineModelManager.OnlineModelConfig config,
                                                  List<ChatMessage> history, int maxTokens, boolean enableTools) {
        return CompletableFuture.supplyAsync(() -> {
            Exception lastError = null;
            for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
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
                            result = callOpenAIAPI(apiUrl, apiKey, modelName, prompt, history, maxTokens, false, false, null);
                        }
                    } else {
                        result = callOpenAIAPI(apiUrl, apiKey, modelName, prompt, history, maxTokens, false, false, null);
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
                    lastError = e;
                    if (attempt < MAX_RETRY_ATTEMPTS - 1 && isRetryableOnlineError(e)) {
                        AILogger.w(TAG, "在线请求瞬时错误(" + e.getMessage() + ")，" + RETRY_DELAY_MS + "ms 后重试");
                        try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                        continue;
                    }
                    break;
                }
            }
            AILogger.e(TAG, "Async generate failed: " + (lastError != null ? lastError.getMessage() : "unknown"), lastError);
            throw new RuntimeException(lastError != null ? lastError : new RuntimeException("Async generate failed"));
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
            Exception lastError = null;
            for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
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
                    lastError = e;
                    if (attempt < MAX_RETRY_ATTEMPTS - 1 && isRetryableOnlineError(e)) {
                        AILogger.w(TAG, "generateOnce 瞬时错误(" + e.getMessage() + ")，" + RETRY_DELAY_MS + "ms 后重试");
                        try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                        continue;
                    }
                    break;
                }
            }
            AILogger.e(TAG, "generateOnce failed: " + (lastError != null ? lastError.getMessage() : "unknown"), lastError);
            throw new RuntimeException(lastError != null ? lastError : new RuntimeException("generateOnce failed"));
        }, executor);
    }

    /** 瞬时错误自动重试次数（429 限流 / 5xx 服务端抖动 / 网络超时） */
    private static final int MAX_RETRY_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 1000L;

    /** 是否值得重试的瞬时错误：限流 429、服务端 5xx、连接/读取超时等（业务 400/401/403 不重试） */
    private static boolean isRetryableOnlineError(Throwable t) {
        String m = t != null && t.getMessage() != null ? t.getMessage() : "";
        if (m.contains("429") || m.contains("500") || m.contains("502") || m.contains("503")
                || m.contains("504") || m.contains("Read timed out") || m.contains("connect timed out")
                || m.contains("Connection") || m.contains("connect") || m.contains("timed out")
                || m.contains("timeout") || m.contains("Socket") || m.contains("refused")) {
            return true;
        }
        return false;
    }

    // ==================== 配置表驱动的扩展能力调用 ====================
    // embedding / imageGen / rerank 端点与预置模型全部由 providers.json 的
    // services.embedding / services.imageGen / services.rerank + embeddingModel / imageModel / rerankModel 驱动，
    // 鉴权统一走 applyAuthHeaders + withAuthQuery。UI 勾选 supportsEmbedding 等后即可调用。

    /** 文本向量化（Embedding），返回归一化前的原始向量 */
    public CompletableFuture<List<Float>> generateEmbeddingAsync(final String text,
                                                                 final OnlineModelManager.OnlineModelConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return generateEmbedding(text, config);
            } catch (Exception e) {
                AILogger.e(TAG, "Embedding failed: " + e.getMessage(), e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    private List<Float> generateEmbedding(String text, OnlineModelManager.OnlineModelConfig config) throws Exception {
        String apiUrl = config.apiUrl;
        String apiKey = config.apiKey;
        if (apiUrl == null || apiUrl.isEmpty()) throw new IllegalArgumentException("API URL 不能为空");
        if (apiKey == null || apiKey.isEmpty()) throw new IllegalArgumentException("API Key 不能为空");
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("待向量化文本不能为空");
        com.oilquiz.app.ai.model.ProviderConfigManager pcm =
                com.oilquiz.app.ai.model.ProviderConfigManager.get();
        String model = pcm.getServiceModel(apiUrl, "embedding");
        if (model == null || model.isEmpty())
            throw new IllegalArgumentException("配置表未声明该服务商 embedding 模型（embeddingModel）");
        String fullUrl = buildOpenAIUrl(apiUrl, pcm.getServiceEndpoint(apiUrl, "embedding"));
        fullUrl = pcm.withAuthQuery(fullUrl, apiKey);
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
            pcm.applyAuthHeaders(connection, fullUrl, apiKey, config.apiSecret, config.appId);
            connection.setDoOutput(true);
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("input", text);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new Exception("Embedding 请求失败: HTTP " + code + " - " + readErrorStream(connection));
            }
            JsonObject resp = gson.fromJson(readFullBody(connection), JsonObject.class);
            JsonArray data = resp != null && resp.has("data") && resp.get("data").isJsonArray()
                    ? resp.getAsJsonArray("data") : null;
            if (data == null || data.size() == 0) throw new Exception("Embedding 响应缺少 data");
            JsonObject first = data.get(0).getAsJsonObject();
            JsonArray emb = first != null && first.has("embedding") && first.get("embedding").isJsonArray()
                    ? first.getAsJsonArray("embedding") : null;
            if (emb == null) throw new Exception("Embedding 响应缺少 embedding");
            List<Float> result = new ArrayList<>(emb.size());
            for (int i = 0; i < emb.size(); i++) result.add(emb.get(i).getAsFloat());
            return result;
        } finally {
            connection.disconnect();
        }
    }

    /** 文生图（ImageGen），返回图片 URL 或 data:image/png;base64 前缀的 data URL */
    public CompletableFuture<String> generateImageAsync(final String prompt,
                                                        final OnlineModelManager.OnlineModelConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return generateImage(prompt, config);
            } catch (Exception e) {
                AILogger.e(TAG, "ImageGen failed: " + e.getMessage(), e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    private String generateImage(String prompt, OnlineModelManager.OnlineModelConfig config) throws Exception {
        String apiUrl = config.apiUrl;
        String apiKey = config.apiKey;
        if (apiUrl == null || apiUrl.isEmpty()) throw new IllegalArgumentException("API URL 不能为空");
        if (apiKey == null || apiKey.isEmpty()) throw new IllegalArgumentException("API Key 不能为空");
        if (prompt == null || prompt.trim().isEmpty()) throw new IllegalArgumentException("绘图提示词不能为空");
        com.oilquiz.app.ai.model.ProviderConfigManager pcm =
                com.oilquiz.app.ai.model.ProviderConfigManager.get();
        String model = pcm.getServiceModel(apiUrl, "imageGen");
        if (model == null || model.isEmpty())
            throw new IllegalArgumentException("配置表未声明该服务商 imageGen 模型（imageModel）");
        String fullUrl = buildOpenAIUrl(apiUrl, pcm.getServiceEndpoint(apiUrl, "imageGen"));
        fullUrl = pcm.withAuthQuery(fullUrl, apiKey);
        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(fullUrl)) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        }
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(120000);
            connection.setRequestProperty("Content-Type", "application/json");
            pcm.applyAuthHeaders(connection, fullUrl, apiKey, config.apiSecret, config.appId);
            connection.setDoOutput(true);
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("prompt", prompt);
            body.addProperty("n", 1);
            body.addProperty("size", "1024x1024");
            try (OutputStream os = connection.getOutputStream()) {
                os.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new Exception("文生图请求失败: HTTP " + code + " - " + readErrorStream(connection));
            }
            JsonObject resp = gson.fromJson(readFullBody(connection), JsonObject.class);
            JsonArray data = resp != null && resp.has("data") && resp.get("data").isJsonArray()
                    ? resp.getAsJsonArray("data") : null;
            if (data == null || data.size() == 0) throw new Exception("文生图响应缺少 data");
            JsonObject first = data.get(0).getAsJsonObject();
            String b64 = first != null && first.has("b64_json") && !first.get("b64_json").isJsonNull()
                    ? first.get("b64_json").getAsString() : "";
            if (!b64.isEmpty()) return "data:image/png;base64," + b64;
            String urlStr = first != null && first.has("url") && !first.get("url").isJsonNull()
                    ? first.get("url").getAsString() : "";
            if (urlStr.isEmpty()) throw new Exception("文生图响应既无 url 也无 b64_json");
            return urlStr;
        } finally {
            connection.disconnect();
        }
    }

    /** 重排序（Rerank），返回服务商原始 results JSON（各服务商格式不一，由调用方解析） */
    public CompletableFuture<String> rerankAsync(final String query, final List<String> documents,
                                                 final OnlineModelManager.OnlineModelConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return rerank(query, documents, config);
            } catch (Exception e) {
                AILogger.e(TAG, "Rerank failed: " + e.getMessage(), e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    private String rerank(String query, List<String> documents,
                          OnlineModelManager.OnlineModelConfig config) throws Exception {
        String apiUrl = config.apiUrl;
        String apiKey = config.apiKey;
        if (apiUrl == null || apiUrl.isEmpty()) throw new IllegalArgumentException("API URL 不能为空");
        if (apiKey == null || apiKey.isEmpty()) throw new IllegalArgumentException("API Key 不能为空");
        if (query == null || query.trim().isEmpty() || documents == null || documents.isEmpty())
            throw new IllegalArgumentException("查询与文档列表不能为空");
        com.oilquiz.app.ai.model.ProviderConfigManager pcm =
                com.oilquiz.app.ai.model.ProviderConfigManager.get();
        String model = pcm.getServiceModel(apiUrl, "rerank");
        if (model == null || model.isEmpty())
            throw new IllegalArgumentException("配置表未声明该服务商 rerank 模型（rerankModel）");
        String fullUrl = buildOpenAIUrl(apiUrl, pcm.getServiceEndpoint(apiUrl, "rerank"));
        fullUrl = pcm.withAuthQuery(fullUrl, apiKey);
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
            pcm.applyAuthHeaders(connection, fullUrl, apiKey, config.apiSecret, config.appId);
            connection.setDoOutput(true);
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("query", query);
            JsonArray docs = new JsonArray();
            for (String d : documents) docs.add(d);
            body.add("documents", docs);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new Exception("Rerank 请求失败: HTTP " + code + " - " + readErrorStream(connection));
            }
            return readFullBody(connection);
        } finally {
            connection.disconnect();
        }
    }

    /** 读取完整响应体 */
    private String readFullBody(HttpURLConnection connection) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 清理模型输出；清理后为空则返回原文 */
    private String cleanOrSanitize(String result) {
        if (result == null) return null;
        String cleaned = com.oilquiz.app.ai.agent.ToolResultInterpreter.cleanModelOutput(result);
        return cleaned != null ? cleaned : result;
    }

    /**
     * 从 API 实际识别模型上下文窗口大小（而非模型名推断）。
     *
     * 调用 OpenAI 兼容的 GET /models（或 /v1/models）接口，从返回的模型详情中
     * 提取上下文窗口。不同服务商字段不同，兼容解析：
     *   - context_length / max_context / context_window / max_model_len
     *   - 或从 /models/{modelName} 详情提取
     *
     * 失败（端点不支持 /models、网络错误、字段缺失）返回 null，
     * 调用方回退到 {@link OnlineModelProfile} 配置表推断。
     *
     * 结果按 apiUrl+modelName 持久化缓存（不换模型不重复查询）。
     *
     * @param config 在线模型配置
     * @return 上下文窗口（tokens）；未知/失败返回 null
     */
    public Integer queryContextWindowFromAPI(final OnlineModelManager.OnlineModelConfig config) {
        if (config == null) return null;
        String key = cacheKey(config.apiUrl, config.modelName); // M10：规范化缓存键
        try {
            Integer cached = contextWindowApiCache.get(key);
            if (cached != null) return cached;
            Integer persisted = loadContextWindowCache(key);
            if (persisted != null) {
                contextWindowApiCache.put(key, persisted);
                return persisted;
            }
            Integer result = CompletableFuture.supplyAsync(() -> {
                try {
                    return doQueryContextWindow(config);
                } catch (Exception e) {
                    AILogger.w(TAG, "Context window query failed: " + e.getMessage());
                    return null;
                }
            }, executor).get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (result != null) {
                contextWindowApiCache.put(key, result);
                saveContextWindowCache(key, result);
                AILogger.i(TAG, "API context window: " + config.modelName + " = " + result);
            }
            return result;
        } catch (Exception e) {
            AILogger.w(TAG, "Context window query error: " + e.getMessage());
            return null;
        }
    }

    // 上下文窗口 API 查询缓存（内存 + 持久化）
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> contextWindowApiCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final String PREFS_CTX = "ctx_window_cache";
    private static final String PREFS_CTX_PREFIX = "ctx_";

    /**
     * M10：规范化 API 地址用于缓存键——小写、去尾部斜杠、去除尾随空白。
     * 修复同一地址因尾斜杠产生两份缓存记录（.../api/paas/v4 与 .../api/paas/v4/）的问题。
     */
    private static String normalizeApiUrl(String apiUrl) {
        if (apiUrl == null) return "";
        String u = apiUrl.trim().toLowerCase();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** M10：构造统一的缓存键（apiUrl 规范化 + 模型名），所有能力缓存共用 */
    private static String cacheKey(String apiUrl, String modelName) {
        return normalizeApiUrl(apiUrl) + "|"
                + (modelName != null ? modelName.trim().toLowerCase() : "");
    }

    private Integer loadContextWindowCache(String key) {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_CTX, Context.MODE_PRIVATE);
            String v = prefs.getString(PREFS_CTX_PREFIX + key, null);
            // M10 旧键迁移：规范化 key（无尾斜杠）未命中时，尝试带尾斜杠的旧格式键
            // （.../api/paas/v4/ 旧缓存），命中后迁移删除，避免同一地址两份缓存并存
            if (v == null && !key.endsWith("/")) {
                String legacyKey = key + "/";
                v = prefs.getString(PREFS_CTX_PREFIX + legacyKey, null);
                if (v != null) {
                    prefs.edit().remove(PREFS_CTX_PREFIX + legacyKey).apply();
                }
            }
            if (v == null) return null;
            return Integer.parseInt(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private void saveContextWindowCache(String key, int value) {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_CTX, Context.MODE_PRIVATE);
            prefs.edit().putString(PREFS_CTX_PREFIX + key, String.valueOf(value)).apply();
        } catch (Throwable t) {
            AILogger.w(TAG, "saveContextWindowCache failed: " + t.getMessage());
        }
    }

    /** 执行 /models 查询（同步） */
    private Integer doQueryContextWindow(OnlineModelManager.OnlineModelConfig config) throws Exception {
        String apiUrl = config.apiUrl;
        String apiKey = config.apiKey;
        String modelName = config.modelName;
        if (apiUrl == null || apiUrl.isEmpty()) return null;
        if (isAnthropicAPI(apiUrl)) return null; // Anthropic 不同接口，跳过

        // 1. 尝试 GET /models 列表，查找目标模型条目
        try {
            String listUrl = buildOpenAIUrl(apiUrl, "/models");
            String listBody = httpGet(listUrl, apiKey, 10000);
            if (listBody != null && !listBody.isEmpty()) {
                try {
                    JsonObject root = JsonParser.parseString(listBody).getAsJsonObject();
                    JsonArray data = root.has("data") ? root.getAsJsonArray("data") : null;
                    if (data != null) {
                        for (int i = 0; i < data.size(); i++) {
                            JsonObject item = data.get(i).getAsJsonObject();
                            String id = item.has("id") ? item.get("id").getAsString() : "";
                            if (id.equals(modelName)) {
                                Integer w = extractContextWindow(item);
                                if (w != null) return w;
                            }
                        }
                        // 列表无详情字段：尝试按 id 查单模型详情
                        if (modelName != null && !modelName.isEmpty()) {
                            try {
                                String detailUrl = buildOpenAIUrl(apiUrl, "/models/" + modelName);
                                String detailBody = httpGet(detailUrl, apiKey, 10000);
                                if (detailBody != null) {
                                    JsonObject root2 = JsonParser.parseString(detailBody).getAsJsonObject();
                                    Integer w2 = extractContextWindow(
                                            root2.has("data") ? root2.getAsJsonObject("data") : root2);
                                    if (w2 != null) return w2;
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                } catch (Exception e) {
                    AILogger.d(TAG, "parse /models failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            AILogger.d(TAG, "GET /models failed (endpoint may not support): " + e.getMessage());
        }
        return null;
    }

    /** 从模型 JSON 中提取上下文窗口（兼容多字段名）。
     *  public 供同包 {@link ModelListFetcher} 在配置时解析模型列表复用它，字段表单点维护。 */
    public static Integer extractContextWindow(JsonObject item) {
        return extractContextWindow(item, 0);
    }

    /** 递归提取（限制深度，兼容 meta / extra_info.default_envs 等嵌套结构） */
    private static Integer extractContextWindow(JsonObject item, int depth) {
        if (item == null || depth > 3) return null;
        String[] keys = {"context_length", "max_context", "context_window",
                "max_model_len", "contextLength", "contextWindow", "max_context_length",
                "max_input_tokens"};
        for (String k : keys) {
            if (item.has(k) && !item.get(k).isJsonNull()) {
                try {
                    int v = item.get(k).getAsInt();
                    if (v > 0) return v;
                } catch (Exception ignored) {
                }
            }
        }
        // 嵌套对象兜底（如 meta: {...}、阿里云百炼 extra_info.default_envs: {max_input_tokens: ...}）
        String[] nested = {"meta", "extra_info", "default_envs"};
        for (String n : nested) {
            if (item.has(n) && item.get(n).isJsonObject()) {
                Integer w = extractContextWindow(item.getAsJsonObject(n), depth + 1);
                if (w != null) return w;
            }
        }
        return null;
    }

    /** 简单 HTTP GET（按服务商配置表鉴权），返回响应体或 null */
    private String httpGet(String urlStr, String apiKey, int timeoutMs) {
        try {
            // query-key 型服务商（Gemini 等）：密钥走 URL ?key=
            urlStr = com.oilquiz.app.ai.model.ProviderConfigManager.get().withAuthQuery(urlStr, apiKey);
            URL url = new URL(urlStr);
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(urlStr)) {
                SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
            }
            try {
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(timeoutMs);
                connection.setReadTimeout(timeoutMs);
                connection.setRequestProperty("Accept", "application/json");
                com.oilquiz.app.ai.model.ProviderConfigManager.get()
                        .applyAuthHeaders(connection, urlStr, apiKey, null, null);
                int code = connection.getResponseCode();
                if (code != 200) {
                    return null;
                }
                InputStream is = connection.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return sb.toString();
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            AILogger.d(TAG, "httpGet failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 探测模型是否支持原生 function calling（"询问模型"而非硬编码模型名匹配）。
     *
     * 向模型发送一个最小 function calling 测试请求（一个无参工具 + 明确要求调用），
     * 观察响应：
     * - 返回合法 tool_calls → 支持（true）
     * - 返回普通文本/400 错误（tools 参数不支持）→ 不支持（false）
     *
     * 结果按 apiUrl+modelName **持久化缓存**（SharedPreferences）：
     * 用户不更换模型就永远不会重复探测，不浪费请求、不破坏前缀缓存；
     * 内存缓存做首层加速。
     *
     * 探测失败（网络等）默认倾向支持（用户常用模型基本都支持 function calling），
     * 避免误判降级为辅助模式。
     *
     * @param config 在线模型配置
     * @return true=支持（探测成功或失败默认倾向支持）/ false=不支持
     */
    public boolean probeFunctionCalling(final OnlineModelManager.OnlineModelConfig config) {
        if (config == null) return true; // 无配置默认支持（避免降级）
        String key = cacheKey(config.apiUrl, config.modelName); // M10：规范化缓存键
        // 1. 内存缓存
        Boolean cached = functionCallingCapabilityCache.get(key);
        if (cached != null) return cached;
        // 1.5 探测未知（网络失败）短时内存缓存：避免网络抖动期每次请求都重探
        Long lastUnknown = fcUnknownCache.get(key);
        if (lastUnknown != null && System.currentTimeMillis() - lastUnknown < FC_UNKNOWN_RETRY_MS) {
            return true;
        }
        // 2. 持久化缓存（App 重启后仍记住，不重复探测；带 7 天 TTL）
        Boolean persisted = loadFunctionCallingCapability(key);
        if (persisted != null) {
            functionCallingCapabilityCache.put(key, persisted);
            return persisted;
        }
        try {
            Boolean result = CompletableFuture.supplyAsync(() -> {
                try {
                    return doProbeFunctionCalling(config);
                } catch (Exception e) {
                    AILogger.w(TAG, "Function calling probe failed: " + e.getMessage());
                    return null;
                }
            }, executor).get(15, java.util.concurrent.TimeUnit.SECONDS);
            if (result != null) {
                functionCallingCapabilityCache.put(key, result);
                saveFunctionCallingCapability(key, result);
                AILogger.i(TAG, "Function calling probe: " + config.modelName + " -> " + result);
                return result;
            }
            // M3：探测未知（超时/网络不可达）不得静默持久化为"支持"。
            // 仅做短时内存记忆（10 分钟内不重复探测），不写持久化；
            // 网络恢复后下次进程内重探即可得到真实能力。
            fcUnknownCache.put(key, System.currentTimeMillis());
            AILogger.w(TAG, "Function calling probe unknown for " + config.modelName
                    + ", not persisted (assume supported this session only, will re-probe)");
            return true;
        } catch (Exception e) {
            AILogger.w(TAG, "Function calling probe timeout/error: " + e.getMessage());
            return true; // 失败默认支持，不降级（下次会话重新探测）
        }
    }

    private static final String PREFS_FC_PROBE = "fc_probe_cache";
    private static final String PREFS_FC_PREFIX = "fc_";

    /** 从 SharedPreferences 读持久化的 function calling 能力（null=未缓存或已过期） */
    private Boolean loadFunctionCallingCapability(String key) {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_FC_PROBE, Context.MODE_PRIVATE);
            String v = prefs.getString(PREFS_FC_PREFIX + key, null);
            // M10 旧键迁移：规范化 key 未命中时尝试带尾斜杠的旧格式键
            if (v == null && !key.endsWith("/")) {
                String legacyKey = key + "/";
                v = prefs.getString(PREFS_FC_PREFIX + legacyKey, null);
                if (v != null) {
                    prefs.edit().remove(PREFS_FC_PREFIX + legacyKey).apply();
                }
            }
            if (v == null) return null;
            int sep = v.indexOf('|');
            if (sep > 0) {
                long ts = Long.parseLong(v.substring(sep + 1));
                if (System.currentTimeMillis() - ts > FC_CACHE_TTL_MS) {
                    // 超过 7 天：过期，删除并重新探测（服务商能力可能变化）
                    prefs.edit().remove(PREFS_FC_PREFIX + key).apply();
                    return null;
                }
                return "1".equals(v.substring(0, sep));
            }
            // 旧格式（无时间戳）：兼容保留
            return "1".equals(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 持久化 function calling 能力到 SharedPreferences（带时间戳，支持 TTL 过期） */
    private void saveFunctionCallingCapability(String key, boolean value) {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_FC_PROBE, Context.MODE_PRIVATE);
            prefs.edit().putString(PREFS_FC_PREFIX + key,
                    (value ? "1" : "0") + "|" + System.currentTimeMillis()).apply();
        } catch (Throwable t) {
            AILogger.w(TAG, "saveFunctionCallingCapability failed: " + t.getMessage());
        }
    }

    // ==================== 能力动态探测（真实请求验证，替代硬编码） ====================
    // 配置表硬编码 capabilities 会过时/不准确（同一服务商不同模型能力差异大），
    // 这里用最小真实请求验证模型能力，结果持久化缓存（与 function calling 探测同模式）：
    // - embedding：POST /embeddings 最小 input（零生成成本）
    // - vision：POST /chat/completions 带 1x1 透明图（几 token）
    // - webSearch：POST /chat/completions 带配置表搜索参数（几 token）
    // - agent：POST /responses 最小 input（探测端点存在性）
    // imageGen/rerank 会真实消耗（真生成图/真重排），不做自动探测，保留配置声明。
    // 探测失败（网络/超时/401）返回 null 表示未知，调用方保留配置声明不覆盖。

    private static final String PREFS_CAP_PROBE = "cap_probe_cache";

    /** 批量探测模型能力（embedding/vision/webSearch/agent），返回探测到结果的子集 */
    public CompletableFuture<Map<String, Boolean>> probeCapabilities(
            final OnlineModelManager.OnlineModelConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Boolean> result = new HashMap<>();
            if (config == null) return result;
            String[] caps = {"embedding", "vision", "webSearch", "agent"};
            for (String cap : caps) {
                Boolean v = probeCapability(config, cap);
                if (v != null) result.put(cap, v);
            }
            return result;
        }, executor);
    }

    /** 探测单个能力（内存+持久化缓存；null=未知） */
    public Boolean probeCapability(final OnlineModelManager.OnlineModelConfig config, final String capability) {
        if (config == null || capability == null) return null;
        String key = cacheKey(config.apiUrl, config.modelName) + "|" + capability; // M10：规范化缓存键
        Boolean cached = capabilityProbeCache.get(key);
        if (cached != null) return cached;
        Boolean persisted = loadCapabilityProbe(key);
        if (persisted != null) {
            capabilityProbeCache.put(key, persisted);
            return persisted;
        }
        try {
            Boolean v = CompletableFuture.supplyAsync(() -> {
                try {
                    switch (capability) {
                        case "embedding": return doProbeEmbedding(config);
                        case "vision": return doProbeVision(config);
                        case "webSearch": return doProbeWebSearch(config);
                        case "agent": return doProbeAgentEndpoint(config);
                        default: return null;
                    }
                } catch (Exception e) {
                    AILogger.w(TAG, capability + " probe failed: " + e.getMessage());
                    return null;
                }
            }, executor).get(15, java.util.concurrent.TimeUnit.SECONDS);
            if (v != null) {
                capabilityProbeCache.put(key, v);
                saveCapabilityProbe(key, v);
                AILogger.i(TAG, "Capability probe: " + config.modelName + " [" + capability + "] -> " + v);
            }
            return v;
        } catch (Exception e) {
            AILogger.w(TAG, capability + " probe timeout: " + e.getMessage());
            return null;
        }
    }

    private Boolean loadCapabilityProbe(String key) {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_CAP_PROBE, Context.MODE_PRIVATE);
            String v = prefs.getString("cap_" + key, null);
            if (v == null) return null;
            return "1".equals(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private void saveCapabilityProbe(String key, boolean value) {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_CAP_PROBE, Context.MODE_PRIVATE);
            prefs.edit().putString("cap_" + key, value ? "1" : "0").apply();
        } catch (Throwable t) {
            AILogger.w(TAG, "saveCapabilityProbe failed: " + t.getMessage());
        }
    }

    /** 配置是否可用于探测（字段齐全且非 Anthropic——其格式不同，跳过探测走模型名/配置声明） */
    private boolean isProbeUsable(OnlineModelManager.OnlineModelConfig config) {
        return config != null && config.apiUrl != null && !config.apiUrl.isEmpty()
                && config.modelName != null && !config.modelName.isEmpty()
                && config.apiKey != null && !config.apiKey.isEmpty()
                && !isAnthropicAPI(config.apiUrl);
    }

    /** 打开带统一鉴权的 POST 连接（query-key 已拼 URL） */
    private HttpsURLConnection openProbePost(String fullUrl, String apiKey, int timeoutSec) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(fullUrl).openConnection();
        if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(fullUrl)) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        }
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(timeoutSec * 1000);
        connection.setReadTimeout(timeoutSec * 1000);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        com.oilquiz.app.ai.model.ProviderConfigManager.get()
                .applyAuthHeaders(connection, fullUrl, apiKey, null, null);
        connection.setDoOutput(true);
        return connection;
    }

    /** 探测 embedding：POST /embeddings 最小 input，200 且返回 data[].embedding → 支持 */
    private Boolean doProbeEmbedding(OnlineModelManager.OnlineModelConfig config) throws Exception {
        if (!isProbeUsable(config)) return null;
        com.oilquiz.app.ai.model.ProviderConfigManager pcm =
                com.oilquiz.app.ai.model.ProviderConfigManager.get();
        String model = pcm.getServiceModel(config.apiUrl, "embedding");
        if (model == null || model.isEmpty()) return null; // 配置表未声明 embedding 模型 → 不探测
        String fullUrl = buildOpenAIUrl(config.apiUrl, pcm.getServiceEndpoint(config.apiUrl, "embedding"));
        fullUrl = pcm.withAuthQuery(fullUrl, config.apiKey);
        HttpsURLConnection conn = openProbePost(fullUrl, config.apiKey, 12);
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("input", "ping");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            int code = conn.getResponseCode();
            if (code != 200) return false; // 404/400 → 端点或模型不支持
            JsonObject resp = gson.fromJson(readFullBody(conn), JsonObject.class);
            JsonArray data = resp != null && resp.has("data") ? resp.getAsJsonArray("data") : null;
            return data != null && data.size() > 0
                    && data.get(0).getAsJsonObject().has("embedding");
        } finally {
            conn.disconnect();
        }
    }

    /** 探测 vision：带 1x1 透明图的最小 chat 请求，200 → 支持；400（图片相关错误）→ 不支持 */
    private Boolean doProbeVision(OnlineModelManager.OnlineModelConfig config) throws Exception {
        if (!isProbeUsable(config)) return null;
        String fullUrl = buildOpenAIUrl(config.apiUrl, "/chat/completions");
        fullUrl = com.oilquiz.app.ai.model.ProviderConfigManager.get()
                .withAuthQuery(fullUrl, config.apiKey);
        HttpsURLConnection conn = openProbePost(fullUrl, config.apiKey, 12);
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", config.modelName);
            JsonArray messages = new JsonArray();
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            JsonArray content = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", "hi");
            content.add(text);
            JsonObject img = new JsonObject();
            img.addProperty("type", "image_url");
            JsonObject iu = new JsonObject();
            iu.addProperty("url", "data:image/png;base64,"
                    + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
            img.add("image_url", iu);
            content.add(img);
            user.add("content", content);
            messages.add(user);
            body.add("messages", messages);
            body.addProperty("max_tokens", 8);
            body.addProperty("temperature", 0f);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            int code = conn.getResponseCode();
            if (code == 200) return true;
            if (code == 400) {
                String err = readErrorStream(conn);
                String el = err == null ? "" : err.toLowerCase();
                // 明确图片/多模态不支持 → false；其他 400（参数等）→ 未知
                if (el.contains("image") || el.contains("vision") || el.contains("multimodal")
                        || el.contains("not support") || el.contains("unsupported")) {
                    return false;
                }
                return null;
            }
            return null; // 401/403/429 等 → 未知（鉴权/限流问题不代表能力缺失）
        } finally {
            conn.disconnect();
        }
    }

    /** 探测 webSearch：带配置表搜索参数的最小请求（注入失败不探测；端点不匹配时自动重拼） */
    private Boolean doProbeWebSearch(OnlineModelManager.OnlineModelConfig config) throws Exception {
        if (!isProbeUsable(config)) return null;
        // 端口重拼：配置表 webSearch.endpoint=responses（DeepSeek/MiniMax 官方 web_search 仅 /responses 生效）
        // → 探测请求自动重拼到 /responses 并用最小 Responses 请求体，不被 chat/completions 限定死
        String wse = com.oilquiz.app.ai.model.ProviderConfigManager.get().getWebSearchEndpoint(config.apiUrl);
        boolean responsesProbe = wse != null && !wse.isEmpty() && !config.apiUrl.contains(wse);
        String fullUrl = buildOpenAIUrl(config.apiUrl, responsesProbe ? "/responses" : "/chat/completions");
        fullUrl = com.oilquiz.app.ai.model.ProviderConfigManager.get()
                .withAuthQuery(fullUrl, config.apiKey);
        HttpsURLConnection conn = openProbePost(fullUrl, config.apiKey, 12);
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", config.modelName);
            if (responsesProbe) {
                // 最小 Responses 请求体：input[0].content[0].input_text
                JsonArray input = new JsonArray();
                JsonObject user = new JsonObject();
                user.addProperty("role", "user");
                JsonArray uc = new JsonArray();
                JsonObject uct = new JsonObject();
                uct.addProperty("type", "input_text");
                uct.addProperty("text", "hi");
                uc.add(uct);
                user.add("content", uc);
                input.add(user);
                body.add("input", input);
                body.addProperty("max_output_tokens", 8);
                body.addProperty("temperature", 0f);
            } else {
                JsonArray messages = new JsonArray();
                JsonObject user = new JsonObject();
                user.addProperty("role", "user");
                user.addProperty("content", "hi");
                messages.add(user);
                body.add("messages", messages);
                body.addProperty("max_tokens", 8);
                body.addProperty("temperature", 0f);
            }
            applyWebSearch(body, config.apiUrl, true);
            if (!body.has("web_search") && !body.has("tools")) {
                return null; // 配置表未声明 webSearch 参数 → 不探测
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            int code = conn.getResponseCode();
            if (code == 200) return true;
            if (code == 400) {
                String err = readErrorStream(conn);
                String el = err == null ? "" : err.toLowerCase();
                if (el.contains("search") || el.contains("web_search") || el.contains("unsupported")) {
                    return false;
                }
                return null;
            }
            return null;
        } finally {
            conn.disconnect();
        }
    }

    /** 探测 agent/Responses 端点：POST /responses 最小请求，200/4xx（端点存在）→ true；404 → false */
    private Boolean doProbeAgentEndpoint(OnlineModelManager.OnlineModelConfig config) throws Exception {
        if (!isProbeUsable(config)) return null;
        com.oilquiz.app.ai.model.ProviderConfigManager pcm =
                com.oilquiz.app.ai.model.ProviderConfigManager.get();
        if (!pcm.hasService(config.apiUrl, "agent")) return null;
        String ep = pcm.getServiceEndpoint(config.apiUrl, "agent");
        if (ep == null || !ep.endsWith("/responses")) return null; // 非 Responses 格式不探测
        String fullUrl = buildOpenAIUrl(config.apiUrl, ep);
        fullUrl = pcm.withAuthQuery(fullUrl, config.apiKey);
        HttpsURLConnection conn = openProbePost(fullUrl, config.apiKey, 12);
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", config.modelName);
            JsonArray input = new JsonArray();
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            JsonArray content = new JsonArray();
            JsonObject t = new JsonObject();
            t.addProperty("type", "input_text");
            t.addProperty("text", "hi");
            content.add(t);
            user.add("content", content);
            input.add(user);
            body.add("input", input);
            body.addProperty("max_output_tokens", 4);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            int code = conn.getResponseCode();
            if (code == 200) return true;
            if (code == 404) return false; // 端点不存在
            if (code == 400 || code == 401 || code == 403) return true; // 端点存在（参数/鉴权问题）
            return null;
        } finally {
            conn.disconnect();
        }
    }


    /**
     * M9：连接前预解析主机名（带 5 秒超时）。
     * HttpURLConnection 的 connectTimeout 不覆盖 DNS 解析耗时（Android 上 DNS 卡死可达分钟级，
     * 表现为请求"卡死"远超超时上限），这里把 DNS 解析放进带超时的线程中执行，
     * 超时即抛出可读错误，不再让调用方无限等待。
     */
    private static void preResolveHost(String fullUrl) throws java.io.IOException {
        try {
            java.net.URI uri = java.net.URI.create(fullUrl);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return;
            java.util.concurrent.Future<java.net.InetAddress[]> future = dnsExecutor.submit(() -> {
                try {
                    return java.net.InetAddress.getAllByName(host);
                } catch (Exception e) {
                    return null;
                }
            });
            java.net.InetAddress[] addrs = future.get(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (addrs == null || addrs.length == 0) {
                throw new java.net.UnknownHostException("无法解析主机: " + host);
            }
        } catch (java.util.concurrent.TimeoutException e) {
            throw new java.io.IOException("DNS 解析超时（5秒），请检查网络或主机名: " + fullUrl);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new java.io.IOException("DNS 解析失败: " + fullUrl + " - "
                    + (e.getCause() != null ? e.getCause().getMessage() : "未知原因"));
        } catch (java.lang.InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("DNS 解析被中断: " + fullUrl);
        } catch (java.lang.IllegalArgumentException e) {
            // 非法 URL：交由后续 new URL 抛标准异常
        }
    }


    /** 执行探针请求（同步，OpenAI 兼容格式） */
    private Boolean doProbeFunctionCalling(OnlineModelManager.OnlineModelConfig config) throws Exception {
        String apiUrl = config.apiUrl;
        String modelName = config.modelName;
        String apiKey = config.apiKey;
        if (apiUrl == null || apiUrl.isEmpty() || modelName == null || apiKey == null || apiKey.isEmpty()) {
            return null;
        }
        if (isAnthropicAPI(apiUrl)) {
            // Anthropic 用 tools 参数（不同格式），这里保守返回 null（走模型名推断）
            return null;
        }
        String fullUrl = buildOpenAIUrl(apiUrl, "/chat/completions");
        preResolveHost(fullUrl); // M9：DNS 预解析带超时
        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(fullUrl)) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        }
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Content-Type", "application/json");
            com.oilquiz.app.ai.model.ProviderConfigManager.get()
                    .applyAuthHeaders(connection, fullUrl, apiKey, config.apiSecret, config.appId);
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);
            // 最小探针：一个无参工具，prompt 明确要求调用
            JsonArray tools = new JsonArray();
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            JsonObject func = new JsonObject();
            func.addProperty("name", "ping_probe");
            func.addProperty("description", "探测工具，无参数");
            // M2：显式 JSON Schema（type=object），空对象会被部分服务商拒绝
            // （"schema must be a JSON Schema of 'type: object', got 'type: null'"）
            JsonObject paramsSchema = new JsonObject();
            paramsSchema.addProperty("type", "object");
            paramsSchema.add("properties", new JsonObject());
            paramsSchema.add("required", new JsonArray());
            func.add("parameters", paramsSchema);
            tool.add("function", func);
            tools.add(tool);
            requestBody.add("tools", tools);

            JsonArray messages = new JsonArray();
            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", "请调用 ping_probe 工具。");
            messages.add(userMsg);
            requestBody.add("messages", messages);
            requestBody.addProperty("max_tokens", 64);
            requestBody.addProperty("temperature", 0f);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(gson.toJson(requestBody).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                String errorBody = readErrorStream(connection);
                // 400 且错误指向 tools/function 不支持 → 明确不支持
                if (responseCode == 400 && isToolsUnsupportedError(errorBody)) {
                    return false;
                }
                // 其他错误（401/429/5xx 等）→ 未知
                AILogger.w(TAG, "Probe HTTP " + responseCode + ": " + errorBody);
                return null;
            }

            String response = readFullProbeResponse(connection);
            if (response == null) return null;
            // 含 tool_calls → 支持
            if (response.contains("\"tool_calls\"") || response.contains("tool_calls")) {
                return true;
            }
            // 200 但返回普通文本（模型忽略了 tools）→ 不支持
            return false;
        } finally {
            connection.disconnect();
        }
    }

    /** 读取探针响应全文（与 readFullResponseWithTools 类似，但只判断 tool_calls） */
    private String readFullProbeResponse(HttpURLConnection connection) throws Exception {
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
        return response.toString();
    }

    /**
     * OpenAI 兼容 API 单次请求：关闭 thinking，content 为空时回退 reasoning_content。
     */
    private String callOpenAIAPIOnce(String apiUrl, String apiKey, String modelName,
                                     String prompt, int maxTokens) throws Exception {
        String fullUrl = buildOpenAIUrl(apiUrl, "/chat/completions");
        preResolveHost(fullUrl); // M9：DNS 预解析带超时
        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(fullUrl)) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        }
        try {
            connection.setRequestMethod("POST");
            // 批量修复输出较长，读超时提升到 90 秒（默认 30 秒对 5 题批量不够）
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(90000);
            connection.setRequestProperty("Content-Type", "application/json");
                                applyAuth(com.oilquiz.app.ai.model.ProviderConfigManager.get(), connection, fullUrl, apiKey, modelName);
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
                    // 回退：思考模型可能把全部内容放在思考字段。
                    // 字段归一化双读：reasoning_content || reasoning || thinking（Ollama 原生）
                    String reasoning = null;
                    if (message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull()) {
                        reasoning = message.get("reasoning_content").getAsString();
                    } else if (message.has("reasoning") && !message.get("reasoning").isJsonNull()) {
                        reasoning = message.get("reasoning").getAsString();
                    } else if (message.has("thinking") && !message.get("thinking").isJsonNull()) {
                        reasoning = message.get("thinking").getAsString();
                    }
                    if (reasoning != null && !reasoning.isEmpty()) {
                        AILogger.w(TAG, "content为空，回退使用思考字段(长度" + reasoning.length() + ")");
                        return reasoning;
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
     *
     * @param enableThinking 深度思考开关：true 且模型支持时传 thinking 参数（enable_thinking /
     *                       reasoning_effort），reasoning_content 增量经 onThinkingToken 分流思考区
     */
    public void generateStream(String prompt, OnlineModelManager.OnlineModelConfig config,
                               List<ChatMessage> history, int maxTokens, boolean enableThinking,
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
                    // Anthropic 思考块（content[] 内 thinking）暂不扩展，保持现有正文流式
                    callAnthropicAPI(apiUrl, apiKey, modelName, prompt, history, maxTokens, true, callback);
                } else if (shouldUseResponsesAPI(config)) {
                    // Agent/Responses 接口：服务商配置表声明 agent 且端点 /responses 兼容
                    // （OpenAI Responses API 流式，reasoning 走思考区）
                    callResponsesAPI(apiUrl, apiKey, modelName, prompt, history, maxTokens, true,
                            config.supportsWebSearch, callback);
                } else {
                    callOpenAIAPI(apiUrl, apiKey, modelName, prompt, history, maxTokens, true,
                            enableThinking, config.supportsWebSearch, callback);
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
     * 是否启用 Agent/Responses 接口：
     * - 用户勾选 supportsAgent（配置对话框"Agent 接口"）
     * - 配置表声明该服务商提供 agent 服务
     * - agent 端点为 Responses 兼容（/responses 结尾，OpenAI/MiniMax/Moonshot）
     * 智谱 /agent/chat、百炼 multimodal-generation 等特殊格式暂不走此路由（保持 chat 兼容可用）。
     */
    private boolean shouldUseResponsesAPI(OnlineModelManager.OnlineModelConfig config) {
        if (config == null || !config.supportsAgent) return false;
        String apiUrl = config.apiUrl;
        if (apiUrl == null || isAnthropicAPI(apiUrl)) return false;
        com.oilquiz.app.ai.model.ProviderConfigManager pcm =
                com.oilquiz.app.ai.model.ProviderConfigManager.get();
        if (!pcm.hasService(apiUrl, "agent")) return false;
        String ep = pcm.getServiceEndpoint(apiUrl, "agent");
        return ep != null && ep.endsWith("/responses");
    }

    /**
     * 调用 OpenAI Responses API（agent 接口，配置表 services.agent 声明 /responses 端点时启用）。
     * 流式事件：response.output_text.delta（正文）、response.reasoning_*（思考区）、response.completed/failed。
     * 非流式：output[] 中 type=message → content[].output_text。
     */
    private String callResponsesAPI(String apiUrl, String apiKey, String modelName,
                                    String prompt, List<ChatMessage> history, int maxTokens,
                                    boolean stream, boolean webSearch, StreamCallback callback) throws Exception {
        com.oilquiz.app.ai.model.ProviderConfigManager pcm =
                com.oilquiz.app.ai.model.ProviderConfigManager.get();
        String endpoint = pcm.getServiceEndpoint(apiUrl, "agent");
        if (endpoint == null || endpoint.isEmpty()) endpoint = "/responses";
        String fullUrl = buildOpenAIUrl(apiUrl, endpoint);
        // query-key 型服务商：密钥走 URL ?key=
        fullUrl = pcm.withAuthQuery(fullUrl, apiKey);

        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(fullUrl)) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        }
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(CHAT_READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
                                applyAuth(com.oilquiz.app.ai.model.ProviderConfigManager.get(), connection, fullUrl, apiKey, modelName);
            connection.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
            connection.setDoOutput(true);

            JsonObject body = new JsonObject();
            body.addProperty("model", modelName);

            // 历史 + 当前提示 → input 数组（Responses 格式：content[] 带类型）
            JsonArray input = new JsonArray();
            if (history != null) {
                for (ChatMessage msg : history) {
                    JsonObject m = new JsonObject();
                    if (msg.isSystemMessage()) {
                        m.addProperty("role", "system");
                    } else if (msg.isUserMessage()) {
                        m.addProperty("role", "user");
                    } else {
                        m.addProperty("role", "assistant");
                    }
                    JsonArray content = new JsonArray();
                    JsonObject c = new JsonObject();
                    c.addProperty("type", msg.isAIMessage() ? "output_text" : "input_text");
                    c.addProperty("text", msg.content);
                    content.add(c);
                    m.add("content", content);
                    input.add(m);
                }
            }
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            JsonArray uc = new JsonArray();
            JsonObject uct = new JsonObject();
            uct.addProperty("type", "input_text");
            // 联网搜索优先自有工具：预搜索成功则拼入 prompt 且不再注入官方 webSearch
            String searchPrompt = applySearchToPrompt(prompt, webSearch);
            boolean ownSearchUsed = searchPrompt != prompt;
            uct.addProperty("text", searchPrompt);
            uc.add(uct);
            user.add("content", uc);
            input.add(user);
            body.add("input", input);

            body.addProperty("max_output_tokens", maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS);
            body.addProperty("temperature", DEFAULT_TEMPERATURE);
            // 网络搜索：Responses API 也支持 web_search 工具（OpenAI 托管工具 web_search_preview）；
            // 自有工具已接管时跳过官方注入
            if (!ownSearchUsed) {
                applyWebSearch(body, apiUrl, webSearch);
            }
            if (stream) body.addProperty("stream", true);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                String errorBody = readErrorStream(connection);
                throw new Exception("API 请求失败: HTTP " + responseCode + " - " + errorBody);
            }
            if (stream) {
                return readResponsesStream(connection, callback);
            }
            return readResponsesFull(connection);
        } finally {
            connection.disconnect();
        }
    }

    /** 解析 Responses API 流式事件（SSE data: 行） */
    private String readResponsesStream(HttpURLConnection connection, StreamCallback callback) throws Exception {
        StringBuilder fullText = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line == null || !line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                try {
                    JsonObject json = gson.fromJson(data, JsonObject.class);
                    if (json == null) continue;
                    String type = json.has("type") && !json.get("type").isJsonNull()
                            ? json.get("type").getAsString() : "";
                    switch (type) {
                        case "response.output_text.delta": {
                            JsonObject delta = json.has("delta") && json.get("delta").isJsonObject()
                                    ? json.getAsJsonObject("delta") : null;
                            if (delta != null) {
                                String t = delta.has("text") && !delta.get("text").isJsonNull()
                                        ? delta.get("text").getAsString() : "";
                                if (!t.isEmpty()) {
                                    fullText.append(t);
                                    if (callback != null) callback.onToken(t);
                                }
                            }
                            break;
                        }
                        case "response.reasoning_summary_text.delta":
                        case "response.reasoning_text.delta": {
                            JsonObject delta = json.has("delta") && json.get("delta").isJsonObject()
                                    ? json.getAsJsonObject("delta") : null;
                            if (delta != null) {
                                String t = delta.has("text") && !delta.get("text").isJsonNull()
                                        ? delta.get("text").getAsString() : "";
                                if (!t.isEmpty() && callback != null) callback.onThinkingToken(t);
                            }
                            break;
                        }
                        case "response.completed": {
                            if (callback != null) callback.onThinkingEnd();
                            return fullText.toString();
                        }
                        case "response.failed": {
                            JsonObject r = json.has("response") && json.get("response").isJsonObject()
                                    ? json.getAsJsonObject("response") : null;
                            String err = r != null && r.has("status") && !r.get("status").isJsonNull()
                                    ? r.get("status").getAsString() : "generation failed";
                            if (callback != null) callback.onError(err);
                            return fullText.toString();
                        }
                        case "error": {
                            JsonObject err = json.has("error") && json.get("error").isJsonObject()
                                    ? json.getAsJsonObject("error") : null;
                            String msg = err != null && err.has("message") && !err.get("message").isJsonNull()
                                    ? err.get("message").getAsString() : "unknown error";
                            throw new Exception(msg);
                        }
                        default:
                            break;
                    }
                } catch (com.google.gson.JsonSyntaxException ignored) {
                    // 跳过非 JSON 行（注释/心跳）
                }
            }
        }
        return fullText.toString();
    }

    /** 解析 Responses API 非流式响应（output[] → message.content[].output_text） */
    private String readResponsesFull(HttpURLConnection connection) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        }
        JsonObject json = gson.fromJson(sb.toString(), JsonObject.class);
        JsonArray output = json != null && json.has("output") && json.get("output").isJsonArray()
                ? json.getAsJsonArray("output") : null;
        if (output == null) return "";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < output.size(); i++) {
            JsonObject o = output.get(i).getAsJsonObject();
            JsonArray content = o != null && o.has("content") && o.get("content").isJsonArray()
                    ? o.getAsJsonArray("content") : null;
            if (content != null) {
                for (int j = 0; j < content.size(); j++) {
                    JsonObject c = content.get(j).getAsJsonObject();
                    if ("output_text".equals(c.has("type") ? c.get("type").getAsString() : "")) {
                        text.append(c.has("text") ? c.get("text").getAsString() : "");
                    }
                }
            }
        }
        return text.toString();
    }

    /**
     * 在线多模态生成：带图片（base64 data URL）的 OpenAI 兼容请求。
     * 图片以 OpenAI 多模态 content 数组格式注入最后一条 user 消息：
     * [{type:text,text:prompt}, {type:image_url,image_url:{url:"data:image/jpeg;base64,..."}}]
     * 支持 Qwen-VL / GPT-4o 等兼容 OpenAI 图片消息的模型。
     *
     * @param enableThinking 深度思考开关：模型支持时注入 thinking 参数（多模态思考模型如
     *                       Qwen3-VL），reasoning 增量经 onThinkingToken 分流思考区
     * @param imageBase64List 图片 base64 数据（不含前缀），将自动加 data:image/jpeg;base64 前缀
     */
    public void generateStreamWithImages(String prompt, List<String> imageBase64List,
                                         OnlineModelManager.OnlineModelConfig config,
                                         List<ChatMessage> history, int maxTokens, boolean enableThinking,
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
                    generateStream(prompt, config, history, maxTokens, enableThinking, callback);
                    return;
                }

                mainHandler.post(callback::onStart);

                if (isAnthropicAPI(apiUrl)) {
                    postError(callback, "当前 API 端点不支持图片消息，已回退请用 OCR 或本地多模态");
                    return;
                }
                callOpenAIAPIWithImages(apiUrl, apiKey, modelName, prompt, imageBase64List,
                        history, maxTokens, true, enableThinking, config.supportsWebSearch, callback);
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
                                           int maxTokens, boolean stream, boolean enableThinking,
                                           boolean webSearch,
                                           StreamCallback callback) throws Exception {
        String fullUrl = buildOpenAIUrl(apiUrl, "/chat/completions");
        // query-key 型服务商（Gemini 等）：密钥走 URL ?key=，openConnection 前拼好
        fullUrl = com.oilquiz.app.ai.model.ProviderConfigManager.get().withAuthQuery(fullUrl, apiKey);

        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(fullUrl)) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        }

        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(CHAT_READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
                                applyAuth(com.oilquiz.app.ai.model.ProviderConfigManager.get(), connection, fullUrl, apiKey, modelName);
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
            // 联网搜索优先自有工具：预搜索成功则拼入 prompt 且不再注入官方 webSearch
            String searchPrompt = applySearchToPrompt(prompt, webSearch);
            boolean ownSearchUsed = searchPrompt != prompt;
            // 端口不匹配（配置表 webSearch.endpoint=responses，如图片+DeepSeek 官方 web_search 仅 /responses 生效）：
            // 多模态无法重拼到 Responses（图片输入）→ 预搜索失败时跳过官方注入，避免 chat/completions 400
            if (!ownSearchUsed) {
                String wse = com.oilquiz.app.ai.model.ProviderConfigManager.get().getWebSearchEndpoint(apiUrl);
                if (wse != null && !wse.isEmpty() && !apiUrl.contains(wse)) {
                    AILogger.i(TAG, "webSearch skipped in multimodal path: endpoint '" + wse
                            + "' required but images cannot route to Responses; own pre-search took over");
                    ownSearchUsed = true; // 视为已处理，跳过官方注入
                }
            }
            textPart.addProperty("text", searchPrompt);
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
            // 深度思考：与文本路径共用注入逻辑（多模态思考模型如 Qwen3-VL 生效）
            applyThinkingParams(requestBody, modelName, enableThinking);
            // 网络搜索：自有工具已接管时跳过官方注入
            if (!ownSearchUsed) {
                applyWebSearch(requestBody, apiUrl, webSearch);
            }
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
                return readStreamResponse(connection, enableThinking, callback);
            } else {
                return readFullResponse(connection);
            }
        } finally {
            connection.disconnect();
        }
    }


    /**
     * 应用鉴权头（含 apiSecret/appId）：讯飞 HMAC 签名、百度 OAuth 换 token 都需要，
     * 从 apiUrl+modelName 反查配置补齐；未找到时退化为仅 apiKey（等价旧行为）。
     */
    private void applyAuth(com.oilquiz.app.ai.model.ProviderConfigManager pcm,
                           HttpURLConnection conn, String url, String apiKey, String modelName) {
        String apiSecret = null;
        String appId = null;
        if (modelName != null && !modelName.isEmpty()) {
            try {
                OnlineModelManager.OnlineModelConfig c = OnlineModelManager.getInstance(context)
                        .findConfig(url, modelName);
                if (c != null) {
                    apiSecret = c.apiSecret;
                    appId = c.appId;
                }
            } catch (Exception ignored) {
            }
        }
        pcm.applyAuthHeaders(conn, url, apiKey, apiSecret, appId);
    }

    /**
     * 构建 OpenAI 兼容格式的 URL（统一走 ProviderConfigManager 配置驱动的拼装接口）。
     * 兼容规则：endpoint 自带版本前缀直接拼；baseUrl 已以版本路径结尾（/v1、/v4 等，
     * 如智谱 /api/paas/v4）→ 直接拼 endpoint；已以 endpoint 结尾 → 原样；
     * 否则默认补 /v1 + endpoint。
     */
    private String buildOpenAIUrl(String apiUrl, String endpoint) {
        return com.oilquiz.app.ai.model.ProviderConfigManager.get().buildUrl(apiUrl, endpoint);
    }

    /**
     * 向请求体注入深度思考参数（文本与多模态路径共用）。
     * - 仅当 enableThinking 且模型名判定支持思考时注入，未知模型保守不传避免 400；
     * - OpenAI o 系用 reasoning_effort；DeepSeek/Qwen/GLM/Kimi/豆包用 enable_thinking，
     *   同时放 chat_template_kwargs 双位置下发（vLLM/llama.cpp 类服务端参数在嵌套位置）。
     */
    private void applyThinkingParams(JsonObject requestBody, String modelName, boolean enableThinking) {
        if (!enableThinking) return;
        if (!OnlineModelManager.isThinkingModelName(modelName)) return;
        String thinkingParam = OnlineModelManager.getThinkingParamName(modelName);
        if ("reasoning_effort".equals(thinkingParam)) {
            requestBody.addProperty("reasoning_effort", "medium");
        } else {
            requestBody.addProperty("enable_thinking", true);
            JsonObject chatTemplateKwargs = new JsonObject();
            chatTemplateKwargs.addProperty("enable_thinking", true);
            requestBody.add("chat_template_kwargs", chatTemplateKwargs);
        }
    }

    /**
     * 联网搜索来源路由（优先自有工具）：
     * - 自有 network_search 工具（秘塔，内置 Key 兜底）可用 → 预搜索并把结果拼入 prompt，返回改写后的 prompt；
     *   调用方应跳过官方 webSearch 注入（防止与官方 API 工具捆绑/冲突）。
     * - 预搜索失败/无结果 → 返回原 prompt（引用不变），调用方回退官方 webSearch 注入。
     */
    private String applySearchToPrompt(String prompt, boolean webSearch) {
        if (!webSearch || prompt == null || prompt.isEmpty()) return prompt;
        try {
            if (com.oilquiz.app.ai.tool.NetworkSearchTool.isAvailable()) {
                String searchCtx = com.oilquiz.app.ai.tool.NetworkSearchTool.preSearch(context, prompt, 5);
                if (searchCtx != null && !searchCtx.isEmpty()) {
                    return "【联网搜索结果】\n" + searchCtx
                            + "\n\n请结合以上联网搜索结果回答用户的问题（重要信息请附来源链接）：" + prompt;
                }
                AILogger.w(TAG, "own network_search returned empty, fallback to official webSearch");
            }
        } catch (Exception e) {
            AILogger.w(TAG, "own network_search failed, fallback to official webSearch: " + e.getMessage());
        }
        return prompt;
    }

    /**
     * 向请求体注入网络搜索能力（配置表 services.webSearch 驱动，UI 勾选 supportsWebSearch 生效）：
     * - param=enable_search（百炼/混元等）→ 扁平布尔 enable_search: true（服务端执行，最稳）
     * - param=web_search_tool（OpenAI/智谱/讯飞/豆包/千帆等）→ tools:[{type:"web_search"}]
     * - param=google_search（Gemini）→ tools:[{google_search:{}}]（grounding 自动执行）
     * - param=kimi_web_search（Kimi）→ tools:[{type:"builtin_function",function:{name:"$web_search"}}]
     * 配置表未声明 webSearch 参数或未勾选时不注入。
     * 安全守卫：请求体已含 function 类型 tools（agent 工具）时跳过官方注入，
     * 默认由本地工具（network_search 等）承担联网，避免讯飞等平台"web_search 与 function 不可同时传"返回 400
     * 以及官方工具与本地工具捆绑。
     */
    private void applyWebSearch(JsonObject requestBody, String apiUrl, boolean webSearch) {
        if (!webSearch) return;
        if (requestBody == null || apiUrl == null) return;
        com.oilquiz.app.ai.model.ProviderConfigManager pcm =
                com.oilquiz.app.ai.model.ProviderConfigManager.get();
        String param = pcm.getServiceParam(apiUrl, "webSearch");
        if (param == null || param.isEmpty()) return; // 配置表未声明 → 不注入
        // 统一守卫：已有 function 类型工具（agent 模式）→ 不注入官方 webSearch，避免捆绑/400
        if (requestBody.has("tools") && requestBody.get("tools").isJsonArray()) {
            for (com.google.gson.JsonElement el : requestBody.getAsJsonArray("tools")) {
                if (el.isJsonObject() && "function".equals(
                        el.getAsJsonObject().get("type") != null
                                ? el.getAsJsonObject().get("type").getAsString() : null)) {
                    return;
                }
            }
        }
        if ("google_search".equals(param)) {
            // Gemini 系：OpenAI 兼容 tools 里声明 google_search 工具（grounding 自动执行）
            JsonArray tools = getOrCreateTools(requestBody);
            JsonObject gs = new JsonObject();
            gs.add("google_search", new JsonObject());
            tools.add(gs);
            requestBody.add("tools", tools);
        } else if ("web_search_tool".equals(param)) {
            // 服务端托管 web_search 工具（OpenAI/智谱/讯飞/豆包/千帆/百川/360/MiMo/DeepSeek Responses 等）
            JsonArray tools = getOrCreateTools(requestBody);
            JsonObject ws = new JsonObject();
            ws.addProperty("type", "web_search");
            tools.add(ws);
            requestBody.add("tools", tools);
        } else if ("kimi_web_search".equals(param)) {
            // Kimi 内置 $web_search（builtin_function，服务端执行搜索）
            JsonArray tools = getOrCreateTools(requestBody);
            JsonObject kf = new JsonObject();
            kf.addProperty("type", "builtin_function");
            JsonObject fn = new JsonObject();
            fn.addProperty("name", "$web_search");
            kf.add("function", fn);
            tools.add(kf);
            requestBody.add("tools", tools);
        } else if ("web_search_plugin".equals(param)) {
            // 商汤 SenseNova：plugins.web_search.search_enable 嵌套插件形态
            JsonObject plugins = requestBody.has("plugins") && requestBody.get("plugins").isJsonObject()
                    ? requestBody.getAsJsonObject("plugins") : new JsonObject();
            JsonObject ws = plugins.has("web_search") && plugins.get("web_search").isJsonObject()
                    ? plugins.getAsJsonObject("web_search") : new JsonObject();
            ws.addProperty("search_enable", true);
            ws.addProperty("result_enable", true);
            plugins.add("web_search", ws);
            requestBody.add("plugins", plugins);
        } else {
            // 扁平布尔形态（enable_search 等）
            requestBody.addProperty(param, true);
        }
    }

    /** 获取或创建请求体的 tools 数组 */
    private JsonArray getOrCreateTools(JsonObject requestBody) {
        if (requestBody.has("tools") && requestBody.get("tools").isJsonArray()) {
            return requestBody.getAsJsonArray("tools");
        }
        JsonArray tools = new JsonArray();
        requestBody.add("tools", tools);
        return tools;
    }

    /**
     * 调用 OpenAI 兼容 API
     *
     * @param enableThinking 深度思考开关：true 且模型名判定支持时注入 thinking 参数
     *                       （OpenAI o 系 reasoning_effort / DeepSeek·Qwen·GLM·Kimi·豆包 enable_thinking
     *                       双位置下发），未知模型保守不传避免 400
     */
    private String callOpenAIAPI(String apiUrl, String apiKey, String modelName,
                                  String prompt, List<ChatMessage> history,
                                  int maxTokens, boolean stream, boolean enableThinking,
                                  StreamCallback callback) throws Exception {
        return callOpenAIAPI(apiUrl, apiKey, modelName, prompt, history, maxTokens, stream,
                enableThinking, false, callback);
    }

    /** 带网络搜索开关的 OpenAI 兼容调用（webSearch=true 时按服务商配置注入搜索参数） */
    private String callOpenAIAPI(String apiUrl, String apiKey, String modelName,
                                  String prompt, List<ChatMessage> history,
                                  int maxTokens, boolean stream, boolean enableThinking,
                                  boolean webSearch,
                                  StreamCallback callback) throws Exception {
        // 端口重拼决策（不被端点限定死）：联网搜索需官方注入，但当前 chat/completions 端点不支持
        // （配置表 webSearch.endpoint=responses，如 DeepSeek/MiniMax 官方 web_search 仅 /responses 生效）→
        // 先尝试自有 network_search 预搜索；预搜索成功则留在本路径（结果拼入 prompt）；
        // 预搜索失败则自动重拼 URL 到 /responses 走 Responses API（重建请求体 + 注入官方 web_search 工具）
        String searchPrompt = prompt;
        boolean ownSearchUsed = false;
        if (webSearch) {
            searchPrompt = applySearchToPrompt(prompt, webSearch);
            ownSearchUsed = searchPrompt != prompt;
            if (!ownSearchUsed) {
                String wse = com.oilquiz.app.ai.model.ProviderConfigManager.get().getWebSearchEndpoint(apiUrl);
                if (wse != null && !wse.isEmpty() && !apiUrl.contains(wse)) {
                    AILogger.i(TAG, "webSearch needs endpoint '" + wse + "', re-routing to Responses API: " + apiUrl);
                    return callResponsesAPI(apiUrl, apiKey, modelName, prompt, history, maxTokens,
                            stream, true, callback);
                }
            }
        }
        String fullUrl = buildOpenAIUrl(apiUrl, "/chat/completions");
        // query-key 型服务商（Gemini 等）：密钥走 URL ?key=，openConnection 前拼好
        fullUrl = com.oilquiz.app.ai.model.ProviderConfigManager.get().withAuthQuery(fullUrl, apiKey);
        preResolveHost(fullUrl); // M9：DNS 预解析带超时
        
        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        
        // 禁用SSL证书验证以支持阿里云百炼等服务
        if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(fullUrl)) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        }
        
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(CHAT_READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
                                applyAuth(com.oilquiz.app.ai.model.ProviderConfigManager.get(), connection, fullUrl, apiKey, modelName);
            connection.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
            connection.setDoOutput(true);

            // 构建请求体
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);
            
            // 构建消息列表
            JsonArray messages = new JsonArray();

            // 注入当前日期（权威事实）：防止模型用训练截止时间回答"今天几号/最新"类问题
            try {
                JsonObject sysMsg = new JsonObject();
                sysMsg.addProperty("role", "system");
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                        "yyyy年M月d日 EEEE", java.util.Locale.CHINA);
                sysMsg.addProperty("content", "当前日期：" + sdf.format(new java.util.Date())
                        + "。这是系统实时提供的当前时间，回答今天/几号/当前时间/最新等问题以它为准，不要使用训练数据中的旧时间。");
                messages.add(sysMsg);
            } catch (Exception e) {
                AILogger.w(TAG, "Time inject failed: " + e.getMessage());
            }

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
                    // M6：深度思考模式下，assistant 历史消息必须原样回传 reasoning_content，
                    // 否则 DeepSeek/Qwen 等推理模型下一轮请求返回 HTTP 400
                    // ("The reasoning_content in the thinking mode must be passed back")。
                    // thinkingContent 即上一轮保存的思考链。
                    if (enableThinking && msg.isAIMessage()
                            && msg.thinkingContent != null && !msg.thinkingContent.isEmpty()) {
                        message.addProperty("reasoning_content", msg.thinkingContent);
                    }
                    messages.add(message);
                }
            }
            
            // 添加当前提示（联网搜索预搜索结果已在方法开头决策拼入 searchPrompt）
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");
            userMessage.addProperty("content", searchPrompt);
            messages.add(userMessage);
            
            requestBody.add("messages", messages);
            requestBody.addProperty("max_tokens", maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS);
            requestBody.addProperty("temperature", DEFAULT_TEMPERATURE);

            // 深度思考：模型支持时按参数名规范传 thinking 开关（与多模态路径共用同一注入逻辑）
            applyThinkingParams(requestBody, modelName, enableThinking);
            // 网络搜索：自有工具已接管时跳过官方注入，否则按服务商配置表注入 web_search/google_search
            if (!ownSearchUsed) {
                applyWebSearch(requestBody, apiUrl, webSearch);
            }
            
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
                return readStreamResponse(connection, enableThinking, callback);
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
        // query-key 型服务商（Gemini 等）：密钥走 URL ?key=，openConnection 前拼好
        fullUrl = com.oilquiz.app.ai.model.ProviderConfigManager.get().withAuthQuery(fullUrl, apiKey);

        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(fullUrl)) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        }

        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(CHAT_READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
                                applyAuth(com.oilquiz.app.ai.model.ProviderConfigManager.get(), connection, fullUrl, apiKey, modelName);
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
                return callOpenAIAPI(apiUrl, apiKey, modelName, prompt, history, maxTokens, false, false, null);
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
        // Anthropic messages 端点：兼容 /v1 结尾的地址，避免拼成 /v1/v1/messages
        String fullUrl = buildOpenAIUrl(apiUrl, "/messages");
        
        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        
        // 禁用SSL证书验证以支持各种服务
        if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(fullUrl)) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        }
        
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
            // Anthropic 鉴权：x-api-key + anthropic-version（按服务商配置表 auth 类型统一处理）
                                applyAuth(com.oilquiz.app.ai.model.ProviderConfigManager.get(), connection, fullUrl, apiKey, modelName);
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
    private String readStreamResponse(HttpURLConnection connection, boolean enableThinking,
                                      StreamCallback callback) throws Exception {
        StringBuilder fullText = new StringBuilder();
        StringBuilder reasoningText = new StringBuilder();
        // 思考结束信号：content 首次出现即思考段结束；流结束仍未触发则补发（防 UI 思考行悬挂）
        final boolean[] thinkingEnded = {false};
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
                                // 深度思考：思考链（累计，用于 content 空时兜底；同时实时转发 onThinkingToken
                                // 供思考区/顶部单行显示）。字段归一化双读：reasoning_content || reasoning || thinking
                                String rc = null;
                                if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                                    rc = delta.get("reasoning_content").getAsString();
                                } else if (delta.has("reasoning") && !delta.get("reasoning").isJsonNull()) {
                                    rc = delta.get("reasoning").getAsString();
                                } else if (delta.has("thinking") && !delta.get("thinking").isJsonNull()) {
                                    rc = delta.get("thinking").getAsString();
                                }
                                if (rc != null) {
                                    reasoningText.append(rc);
                                    if (!rc.isEmpty()) {
                                        final String rct = rc;
                                        mainHandler.post(() -> callback.onThinkingToken(rct));
                                    }
                                }
                                if (delta.has("content") && !delta.get("content").isJsonNull()) {
                                    String content = delta.get("content").getAsString();
                                    // 思考结束：正文首次出现即思考段完成
                                    if (!thinkingEnded[0] && reasoningText.length() > 0) {
                                        thinkingEnded[0] = true;
                                        mainHandler.post(callback::onThinkingEnd);
                                    }
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
        
        // 思考已开始但未收到正文（纯思考/思考即全部输出）：补发思考结束，避免思考行悬挂
        if (!thinkingEnded[0] && reasoningText.length() > 0) {
            thinkingEnded[0] = true;
            mainHandler.post(callback::onThinkingEnd);
        }
        
        String result = fullText.toString();
        // M7：思维链与正文各就各位——思考模式（有思考区承载）下正文为空时
        // 不得把 reasoning_content 填充为正文（思考链已实时展示在思考区）；
        // 仅非思考模式（请求未开 thinking 却返回思维链）下保留兜底，避免空回复。
        if ((result == null || result.trim().isEmpty())
                && reasoningText.length() > 0) {
            if (enableThinking) {
                AILogger.w(TAG, "思考模式：模型仅输出 reasoning_content 无正文，不回退填充正文（思考链已在思考区）");
            } else {
                AILogger.w(TAG, "content为空，回退使用reasoning_content作为回复(长度" + reasoningText.length() + ")");
                result = reasoningText.toString();
            }
        }
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
                
                // P2-4 api_usage_log 用量日志接线：非流式与流式共用此解析点，
                // 模型/服务商取当前活跃配置（请求均基于活跃模型发起，足够审计口径）
                try {
                    OnlineModelManager.OnlineModelConfig active = getActiveConfig();
                    String modelId = active != null && active.modelName != null
                            ? active.modelName : (active != null && active.name != null ? active.name : "unknown");
                    String providerId = active != null && active.apiUrl != null
                            ? active.apiUrl.replaceAll("^https?://", "").split("/")[0] : "";
                    long now = System.currentTimeMillis();
                    new com.oilquiz.app.ai.usage.interceptor.UsageInterceptingWrapper(context)
                            .recordUsage("local_" + now, "", "local",
                                    modelId, providerId, promptTokens, completionTokens,
                                    now - 5000, now);
                } catch (Throwable t) {
                    AILogger.d(TAG, "Usage log skipped: " + t.getMessage());
                }
                
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
            String capabilityKey = cacheKey(apiUrl, modelName); // M10：规范化缓存键

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
                        raw = callOpenAIAPI(apiUrl, apiKey, modelName, currentPrompt, null, maxTokens, false, false, null);
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
        // query-key 型服务商（Gemini 等）：密钥走 URL ?key=，openConnection 前拼好
        fullUrl = com.oilquiz.app.ai.model.ProviderConfigManager.get().withAuthQuery(fullUrl, apiKey);

        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

        // 禁用SSL证书验证以支持阿里云百炼等服务
        if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(fullUrl)) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        }

        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
                                applyAuth(com.oilquiz.app.ai.model.ProviderConfigManager.get(), connection, fullUrl, apiKey, modelName);
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
                    generateStreamFallback(prompt, config, history, maxTokens, false, callback);
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
                                            boolean enableThinking,
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
                    generateStreamFallback(prompt, config, null, maxTokens, enableThinking, callback);
                } else {
                    callOpenAIStreamWithToolsV2(apiUrl, apiKey, modelName,
                        messages, maxTokens, toolsJson, enableThinking, callback);
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
                                              String toolsJson, boolean enableThinking,
                                              NativeToolStreamCallback callback) throws Exception {
        String fullUrl = buildOpenAIUrl(apiUrl, "/chat/completions");
        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(fullUrl)) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        }

        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(CHAT_READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
                                applyAuth(com.oilquiz.app.ai.model.ProviderConfigManager.get(), connection, fullUrl, apiKey, modelName);
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setDoOutput(true);

            // 构建请求体 —— 直接使用传入的 messages JsonArray
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);
            requestBody.add("messages", messages);
            requestBody.addProperty("max_tokens", maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS);
            requestBody.addProperty("temperature", DEFAULT_TEMPERATURE);
            requestBody.addProperty("stream", true);
            // 深度思考：按模型名选择 thinking 参数。
            // DeepSeek/Qwen3/GLM/豆包 → enable_thinking（+chat_template_kwargs 双位置，
            // 兼容 vLLM/llama.cpp/DeepSeek 官方 API 的参数位置差异）；
            // OpenAI o1/o3/o4 → reasoning_effort。
            // 门控：模型名不在支持名单（isThinkingModelName=false）时不发送 thinking 参数，
            // 避免对不支持的服务商 400 报错——静默降级为普通模式（引擎层已优先降级，此处为最后防线）。
            // 重要：仅在用户/引擎主动开启深度思考时才传 enable_thinking=true；
            // 关闭时不强制传 false（不阻拦模型自身行为，如 DeepSeek-R1 在复杂任务下自行思考）。
            boolean thinkingSupported = com.oilquiz.app.ai.model.OnlineModelManager
                    .isThinkingModelName(modelName);
            if (thinkingSupported && enableThinking) {
                try {
                    String paramName = com.oilquiz.app.ai.model.OnlineModelManager
                            .getThinkingParamName(modelName);
                    if ("reasoning_effort".equals(paramName)) {
                        requestBody.addProperty("reasoning_effort", "high");
                        AILogger.i(TAG, "Deep thinking enabled (reasoning_effort=high for o-series)");
                    } else {
                        requestBody.addProperty("enable_thinking", true);
                        JsonObject chatTemplateKwargs = new JsonObject();
                        chatTemplateKwargs.addProperty("enable_thinking", true);
                        requestBody.add("chat_template_kwargs", chatTemplateKwargs);
                        AILogger.i(TAG, "Deep thinking enabled (enable_thinking=true for " + modelName + ")");
                    }
                } catch (Exception ignored) {}
            } else {
                AILogger.d(TAG, "Thinking param not sent for model " + modelName
                        + " (supported=" + thinkingSupported + ", enabled=" + enableThinking + ")");
            }
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
                    + " tools=" + toolsCount + " thinking=" + enableThinking + " body_len=" + bodyStr.length());
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
                    callOpenAIStreamWithToolsV2(apiUrl, apiKey, modelName, messages, maxTokens, null, enableThinking, callback);
                    return;
                }
                // thinking 参数导致 400（部分服务商不支持）：去掉 thinking 重试。
                // 注意：重试时必须同时剥离 assistant 消息里的 reasoning_content 字段
                //（DeepSeek 思考模式硬性要求：思考请求中所有 assistant 消息都要带该字段；
                //  反过来非思考请求带该字段也可能 400）。
                // 例外：服务商配置 requiresReasoningInContext=true（不回传 reasoning 会 400）时
                // 不剥离——这类模型要求恒回传，剥离反而触发 400。
                if (responseCode == 400 && enableThinking && isThinkingUnsupportedError(errorBody)) {
                    AILogger.i(TAG, "Model does not support thinking param (400), retrying without thinking");
                    JsonArray retryMessages = com.oilquiz.app.ai.model.ProviderConfigManager.get()
                            .requiresReasoningInContext(apiUrl)
                            ? messages
                            : stripReasoningContent(messages);
                    callOpenAIStreamWithToolsV2(apiUrl, apiKey, modelName,
                            retryMessages, maxTokens, toolsJson, false, callback);
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

    /** 判断 400 错误是否因 thinking 参数不支持引起（部分服务商/模型） */
    private boolean isThinkingUnsupportedError(String errorBody) {
        if (errorBody == null) return false;
        String lower = errorBody.toLowerCase();
        return lower.contains("enable_thinking")
            || lower.contains("thinking")
            || lower.contains("chat_template_kwargs")
            || lower.contains("unrecognized");
    }

    /**
     * 返回剥离了 assistant 消息 reasoning_content 字段的消息数组副本。
     * 用于「去掉 thinking 重试」：非思考请求若仍带 reasoning_content 字段，DeepSeek 等
     * 服务商同样可能返回 400（思考模式字段不能发给非思考请求）。不修改原数组。
     */
    private JsonArray stripReasoningContent(JsonArray messages) {
        if (messages == null) return null;
        JsonArray copy = new JsonArray();
        for (JsonElement el : messages) {
            JsonObject msg = el.isJsonObject() ? el.getAsJsonObject() : null;
            if (msg == null) {
                copy.add(el.deepCopy());
                continue;
            }
            JsonObject msgCopy = msg.deepCopy();
            String role = msgCopy.has("role") && !msgCopy.get("role").isJsonNull()
                    ? msgCopy.get("role").getAsString() : "";
            if ("assistant".equals(role)) {
                msgCopy.remove("reasoning_content");
            }
            copy.add(msgCopy);
        }
        return copy;
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
                                         boolean enableThinking,
                                         NativeToolStreamCallback callback) {
        generateStream(prompt, config, history, maxTokens, enableThinking, new StreamCallback() {
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
        if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(fullUrl)) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        }

        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
                                applyAuth(com.oilquiz.app.ai.model.ProviderConfigManager.get(), connection, fullUrl, apiKey, modelName);
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
                    history, maxTokens, false, callback);
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

                    // 1. 解析思考链：字段归一化双读（reasoning_content || reasoning || thinking）
                    //    ——不同框架字段名不同（OpenAI 兼容=reasoning_content、Ollama /v1=reasoning、
                    //    Ollama 原生=thinking），"非空即用"覆盖主流框架
                    String reasoningToken = null;
                    if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                        reasoningToken = delta.get("reasoning_content").getAsString();
                    } else if (delta.has("reasoning") && !delta.get("reasoning").isJsonNull()) {
                        reasoningToken = delta.get("reasoning").getAsString();
                    } else if (delta.has("thinking") && !delta.get("thinking").isJsonNull()) {
                        reasoningToken = delta.get("thinking").getAsString();
                    }
                    if (reasoningToken != null) {
                        reasoningBuf.append(reasoningToken);
                        final String t = reasoningToken;
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
        // 深度思考模型（DeepSeek-R1/Qwen3 等）可能只返回 reasoning_content 而无 content：
        // content 为空时回退使用 reasoning_content，避免"未返回 response"空白回复
        if ((fullContent == null || fullContent.trim().isEmpty())
                && reasoningContent != null && !reasoningContent.trim().isEmpty()) {
            AILogger.w(TAG, "content为空，回退使用reasoning_content作为回复(长度" + reasoningContent.length() + ")");
            fullContent = reasoningContent;
            reasoningContent = "";
        }
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