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
import com.oilquiz.app.ai.model.ProviderConfigManager;
import com.oilquiz.app.ai.util.SSLSocketFactoryUtil;
import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

                // 合并配置表预置模型（providers.json models[]：API 未返回时补录，
                // 如智谱免费模型；带模型级 capabilities 时精确到模型能力）
                mergePresetModels(apiUrl, models);

                // 按能力过滤
                if (capability != null && !capability.isEmpty()) {
                    models = filterByCapability(models, capability);
                }

                return models;
            } catch (Exception e) {
                AILogger.e(TAG, "Fetch models failed for " + apiUrl + ": " + e, e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    /**
     * 把 {@link ApiModel} 序列化为**缓存用** JSON（写入 {@code cachedModelsJson}）。
     *
     * <p><b>为什么必须是单一事实源</b>：缓存是思考档位、真实上下文窗口等能力数据的
     * 唯一持久化载体，读取侧（档位选择器、请求注入、max_tokens 上限）全部依赖它。
     * 此前"配置对话框"与"AI 中心自动获取"各写一份，后者漏了
     * {@code thinkingEffortLevels} / {@code maxOutputTokens}，于是**自动获取后档位丢失**，
     * 用户看到"思考强度没有获取到"。改为共用本方法，杜绝再次不同步。</p>
     */
    public static org.json.JSONObject modelToCacheJson(ApiModel model) {
        org.json.JSONObject obj = new org.json.JSONObject();
        try {
            obj.put("id", model.id);
            obj.put("name", model.getName());
            if (model.contextLength > 0) {
                obj.put("contextLength", model.contextLength);
                obj.put("contextLengthFromApi", model.contextLengthFromApi);
            }
            // 思考强度档位（GET /models 的 effort.supported_levels）：
            // UI 据此决定是否展示档位选择器，请求据此决定是否下发 reasoning_effort。
            if (model.hasThinkingEffortLevels()) {
                org.json.JSONArray lv = new org.json.JSONArray();
                for (String level : model.thinkingEffortLevels) lv.put(level);
                obj.put("thinkingEffortLevels", lv);
                if (model.thinkingEffortDefault != null) {
                    obj.put("thinkingEffortDefault", model.thinkingEffortDefault);
                }
            }
            // 服务端允许的最大输出 token（决定 max_tokens 上限）
            if (model.maxOutputTokens > 0) {
                obj.put("maxOutputTokens", model.maxOutputTokens);
            }
            // 输入模态：判断"支持图片"的权威依据（供能力判定与 UI 显示）
            if (model.inputModalities != null && !model.inputModalities.isEmpty()) {
                org.json.JSONArray mods = new org.json.JSONArray();
                for (String m : model.inputModalities) mods.put(m);
                obj.put("inputModalities", mods);
            }
        } catch (org.json.JSONException e) {
            AILogger.w(TAG, "modelToCacheJson failed for " + model.id + ": " + e.getMessage());
        }
        return obj;
    }

    /**
     * 从持久化的模型缓存（{@code cachedModelsJson}）里按 id 取**展示名**；未命中返回 null。
     *
     * <p>用于界面回显：官方 {@code GET /models} 的 {@code name} 是展示名
     * （{@code deepseek-flash} ↔ {@code DeepSeek-V4.1-Flash}），而请求必须用 id。
     * 二者同时可见才不会让人误以为选错了模型。</p>
     */
    public static String findDisplayNameInCache(String cachedModelsJson, String modelId) {
        if (cachedModelsJson == null || cachedModelsJson.isEmpty() || modelId == null) return null;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(cachedModelsJson);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (!modelId.equals(o.optString("id"))) continue;
                String n = o.optString("name", null);
                if (n != null && !n.isEmpty() && !n.equals(modelId)) return n;
                return null;
            }
        } catch (Throwable ignored) {
        }
        return null;
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

    /** 合并配置表预置模型：API 未返回的模型补录进列表（按 id 去重），并携带模型级能力 */
    private void mergePresetModels(String apiUrl, List<ApiModel> models) {
        try {
            ProviderConfigManager pcm = ProviderConfigManager.get();
            List<String> presetNames = pcm.getPredefinedModels(apiUrl);
            if (presetNames.isEmpty()) return;
            Set<String> existing = new HashSet<>();
            for (ApiModel m : models) existing.add(m.id);
            for (String name : presetNames) {
                if (existing.contains(name)) continue; // API 已有不覆盖
                // 预置显示名（配置表 models[] 的 displayName）；未声明时回落为 id
                String presetDisplay = pcm.getPredefinedModelDisplayName(apiUrl, name);
                ApiModel pm = presetDisplay != null
                        ? new ApiModel(name, presetDisplay) : new ApiModel(name);
                pm.source = "preset";
                pm.contextLength = 0;
                List<String> caps = pcm.getModelCapabilities(apiUrl, name);
                if (caps != null && !caps.isEmpty()) pm.capabilities = caps; // 模型级能力
                models.add(pm);
                existing.add(name);
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Merge preset models failed: " + e.getMessage());
        }
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
        // query-key 型服务商（Gemini 等）：密钥走 URL ?key=
        fullUrl = com.oilquiz.app.ai.model.ProviderConfigManager.get().withAuthQuery(fullUrl, apiKey);

        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

        // 禁用SSL证书验证以支持阿里云百炼等服务
        if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(fullUrl)) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        }

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
            com.oilquiz.app.ai.model.ProviderConfigManager.get()
                    .applyAuthHeaders(connection, fullUrl, apiKey, null, null);
            connection.setRequestProperty("Content-Type", "application/json");

            int responseCode = connection.getResponseCode();
            AILogger.i(TAG, "GET " + fullUrl + " -> HTTP " + responseCode);
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
        if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(fullUrl)) {
            SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
        }

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
            // 统一鉴权：Anthropic → x-api-key + anthropic-version
            com.oilquiz.app.ai.model.ProviderConfigManager.get()
                    .applyAuthHeaders(connection, fullUrl, apiKey, null, null);

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
     * 构建OpenAI格式的URL（统一走 ProviderConfigManager 配置驱动的拼装接口）。
     * 兼容规则：baseUrl 已以版本路径结尾（/v1、/v4 等，如智谱 /api/paas/v4）→ 直接拼 endpoint；
     * 已以 endpoint 结尾 → 原样返回；否则默认补 /v1 + endpoint。
     */
    private String buildOpenAIUrl(String apiUrl, String endpoint) {
        return com.oilquiz.app.ai.model.ProviderConfigManager.get().buildUrl(apiUrl, endpoint);
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
        AILogger.i(TAG, "models response: " + response.length() + " bytes, data="
                + (data == null ? "null" : String.valueOf(data.size())));

        if (data != null) {
            for (int i = 0; i < data.size(); i++) {
                JsonObject modelObj = data.get(i).getAsJsonObject();
                String id = modelObj.has("id") && !modelObj.get("id").isJsonNull()
                        ? modelObj.get("id").getAsString() : null;
                if (id == null || id.isEmpty()) {
                    AILogger.w(TAG, "models[" + i + "] has no id, skipped; keys=" + modelObj.keySet());
                    continue;
                }
                String ownedBy = modelObj.has("owned_by") ? modelObj.get("owned_by").getAsString() : "";
                long created = modelObj.has("created") ? modelObj.get("created").getAsLong() : 0;
                // 官方规范的显示名（"for use in model pickers"）：与 id 不同，
                // 例如 id=deepseek-flash → name=DeepSeek-V4.1-Flash。
                // 不读它，UI 就只显示 id，用户看不到 v4.1 这类版本信息。
                String displayName = null;
                if (modelObj.has("name") && !modelObj.get("name").isJsonNull()
                        && modelObj.get("name").isJsonPrimitive()) {
                    String n = modelObj.get("name").getAsString();
                    if (n != null && !n.trim().isEmpty()) displayName = n.trim();
                }

                // 过滤掉嵌入模型和其他非对话模型
                if (!id.contains("embedding") && !id.contains("ada") && !id.contains("babbage") &&
                    !id.contains("curie") && !id.contains("davinci") && !id.contains("text-") &&
                    !id.contains("-search") && !id.contains("-similarity") && !id.contains("-bison")) {
                    ApiModel model = ApiModel.fromOpenAI(id, displayName, ownedBy, created);
                    // 配置时直接提取服务商返回的真实上下文字段（如 context_length / max_model_len），
                    // 命中则覆盖名称推断值，并标记为真实值（配置保存时优先采用）
                    Integer realLen = OnlineInferenceService.extractContextWindow(modelObj);
                    if (realLen != null && realLen > 0) {
                        model.contextLength = realLen;
                        model.contextLengthFromApi = true;
                    }
                    // 思考强度档位：官方 schema 的 effort.supported_levels / effort.default_level。
                    // 这是"每个模型各自不同"的能力声明，必须取 API，不能硬编码全局档位表
                    // —— 否则会给不支持的模型传入越界取值而报错（详见 ApiModel 字段注释）。
                    // 服务商未声明时保持为空：UI 不展示档位、请求也不下发强度参数。
                    parseThinkingEffort(modelObj, model);
                    models.add(model);
                }
            }
        }

        // 解析成功时只记汇总；**解析不到模型是异常情况**，此时输出明细以便定位
        // （此前该场景静默失败，只看到"未获取到可用模型"，无从判断是请求问题还是解析问题）
        if (models.isEmpty() && data != null && data.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < data.size(); i++) {
                if (!data.get(i).isJsonObject()) continue;
                JsonObject o = data.get(i).getAsJsonObject();
                sb.append(o.has("id") ? o.get("id").getAsString() : "?").append(' ');
            }
            AILogger.w(TAG, "所有 " + data.size() + " 个模型都被过滤/无 id，原始 id: " + sb);
        }
        AILogger.i(TAG, "Fetched " + models.size() + " OpenAI models");
        return models;
    }

    /**
     * 解析模型级"思考强度档位"能力声明（官方 {@code GET /models} schema）。
     *
     * <pre>
     * "effort": { "supported_levels": ["low","high","max"], "default_level": "high" },
     * "max_output_tokens": 393216
     * </pre>
     *
     * <p>这两个字段都是**可选**的：服务商未声明时留空，调用方据此判定
     * "该模型只有思考开关、没有强度档位"，从而不在 UI 展示档位选择器、
     * 也不在请求里下发强度参数 —— 避免把不支持的取值传给模型。</p>
     */
    private void parseThinkingEffort(com.google.gson.JsonObject modelObj, ApiModel model) {
        if (modelObj == null || model == null) return;
        try {
            if (modelObj.has("effort") && modelObj.get("effort").isJsonObject()) {
                com.google.gson.JsonObject effort = modelObj.getAsJsonObject("effort");
                if (effort.has("supported_levels") && effort.get("supported_levels").isJsonArray()) {
                    com.google.gson.JsonArray arr = effort.getAsJsonArray("supported_levels");
                    java.util.List<String> levels = new java.util.ArrayList<>();
                    for (int i = 0; i < arr.size(); i++) {
                        if (arr.get(i).isJsonPrimitive()) {
                            String lv = arr.get(i).getAsString();
                            if (lv != null && !lv.isEmpty()) levels.add(lv);
                        }
                    }
                    if (!levels.isEmpty()) {
                        model.thinkingEffortLevels = levels;
                        if (effort.has("default_level") && effort.get("default_level").isJsonPrimitive()) {
                            model.thinkingEffortDefault = effort.get("default_level").getAsString();
                        }
                    }
                }
            }
            if (modelObj.has("max_output_tokens") && modelObj.get("max_output_tokens").isJsonPrimitive()) {
                model.maxOutputTokens = modelObj.get("max_output_tokens").getAsInt();
            }
            // 输入模态（官方 input_modalities）：判断多模态（图片输入）的权威依据。
            // 不解析它就只能靠模型名关键词猜，会漏掉像 deepseek-flash 这种
            // "名字里没有 vision/vl 但官方支持图像理解"的模型。
            if (modelObj.has("input_modalities") && modelObj.get("input_modalities").isJsonArray()) {
                com.google.gson.JsonArray mods = modelObj.getAsJsonArray("input_modalities");
                java.util.List<String> out = new java.util.ArrayList<>();
                for (int i = 0; i < mods.size(); i++) {
                    if (!mods.get(i).isJsonPrimitive()) continue;
                    String s = mods.get(i).getAsString();
                    if (s != null && !s.isEmpty()) out.add(s);
                }
                model.inputModalities = out;
            }
        } catch (Exception e) {
            // 能力字段解析失败不影响模型列表本身（保持"未声明"语义）
            AILogger.d(TAG, "parseThinkingEffort skipped for " + model.id + ": " + e.getMessage());
        }
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
     * 测试连通性（URL 拼接统一走 buildOpenAIUrl：兼容 /v1、/v4 等版本路径结尾的地址，
     * 避免智谱 /api/paas/v4 被错误拼成 /v4/v1/models）
     */
    public CompletableFuture<Boolean> testConnection(String apiUrl, String apiKey) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String fullUrl = buildOpenAIUrl(apiUrl, "/models");
                // query-key 型服务商（Gemini 等）：密钥走 URL ?key=
                fullUrl = com.oilquiz.app.ai.model.ProviderConfigManager.get().withAuthQuery(fullUrl, apiKey);

                URL url = new URL(fullUrl);
                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

                // 禁用SSL证书验证以支持阿里云百炼等服务
                if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(fullUrl)) {
                    SSLSocketFactoryUtil.disableSSLCertificateValidation(connection);
                }

                try {
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);

                    // 统一鉴权：按服务商配置表 auth 类型（Anthropic→x-api-key+version；query-key/none→无头；其余 Bearer）
                    com.oilquiz.app.ai.model.ProviderConfigManager.get()
                            .applyAuthHeaders(connection, fullUrl, apiKey, null, null);

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