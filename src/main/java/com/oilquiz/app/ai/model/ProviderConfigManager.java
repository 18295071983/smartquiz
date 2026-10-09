package com.oilquiz.app.ai.model;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONObject;

/**
 * 在线模型服务商统一配置管理器。
 *
 * <p>把「模型 API 地址、服务类型、深度思考参数、端点识别、地址拼装」从代码硬编码抽到
 * {@code providers.json} 配置表（assets 内置），外部可直接覆盖 {@code filesDir/providers.json}
 * 或调用 {@link #updateFromJson(String)} 热更新，无需改代码重编译。
 *
 * <p>加载优先级（与 {@link OnlineModelProfile} 一致的三级机制）：
 * <ol>
 *   <li>filesDir/providers.json（外部可更新覆盖）</li>
 *   <li>SharedPreferences 双保险（上次成功解析的 JSON）</li>
 *   <li>assets/providers.json（App 内置默认）</li>
 * </ol>
 *
 * <p>统一接口：{@link #buildUrl(String, String)} 地址拼装、{@link #isThinkingModelName(String)}
 * 思考模型判断、{@link #getThinkingParamName(String)} 思考参数名、{@link #matchByUrl(String)}
 * 端点识别，所有调用方（在线引擎 / 模型列表 / API 配置管理 / 语音注册表 / UI 预置表）
 * 都从这里读取，改配置即全局生效。
 */
public class ProviderConfigManager {

    private static final String TAG = "ProviderConfigManager";
    private static final String ASSET_FILE = "providers.json";
    private static final String OVERRIDE_FILE = "providers.json";
    private static final String PREFS_NAME = "provider_config";
    private static final String KEY_JSON = "providers_json";

    private static volatile Context appContext;
    private static volatile ProviderConfigManager INSTANCE;
    private static volatile ProviderTable table;

    private final Gson gson = new Gson();

    // ==================== 初始化 ====================

    /** 在 Application.onCreate 中注入 applicationContext（无需 Context 的静态调用也能加载配置） */
    public static void init(Context context) {
        appContext = context != null ? context.getApplicationContext() : null;
        get(); // 触发加载
    }

    public static ProviderConfigManager get() {
        if (INSTANCE == null) {
            synchronized (ProviderConfigManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ProviderConfigManager();
                    table = loadTable();
                }
            }
        }
        return INSTANCE;
    }

    private ProviderConfigManager() {
    }

    // ==================== 加载 / 热更新 ====================

    /** 重新从磁盘/内置加载配置（外部更新 filesDir/providers.json 后调用生效） */
    public synchronized void reload() {
        table = loadTable();
        AILogger.i(TAG, "reload done: version=" + (table != null ? table.version : "null")
                + ", providers=" + (table != null && table.providers != null ? table.providers.size() : 0));
    }

    /** 用新 JSON 更新配置（写 filesDir + SharedPreferences + 内存，立即生效） */
    public synchronized boolean updateFromJson(String json) {
        ProviderTable t = parseJson(json);
        if (t == null || t.providers == null || t.providers.isEmpty()) {
            AILogger.w(TAG, "updateFromJson rejected: invalid JSON or empty providers");
            return false;
        }
        try {
            if (appContext != null) {
                File override = new File(appContext.getFilesDir(), OVERRIDE_FILE);
                try (FileOutputStream fos = new FileOutputStream(override);
                     OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                    writer.write(json);
                }
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_JSON, json).apply();
            }
            table = t;
            AILogger.i(TAG, "updateFromJson success: version=" + t.version
                    + ", providers=" + t.providers.size());
            return true;
        } catch (Exception e) {
            AILogger.e(TAG, "updateFromJson write failed: " + e.getMessage());
            return false;
        }
    }

    private static ProviderTable loadTable() {
        // 1) filesDir 外部覆盖
        if (appContext != null) {
            try {
                File override = new File(appContext.getFilesDir(), OVERRIDE_FILE);
                if (override.exists()) {
                    ProviderTable t = parseJson(readFile(override));
                    if (t != null) {
                        AILogger.i(TAG, "loaded from filesDir override: " + override.getAbsolutePath());
                        return t;
                    }
                }
            } catch (Exception e) {
                AILogger.w(TAG, "filesDir override load failed: " + e.getMessage());
            }
            // 2) SharedPreferences 双保险
            try {
                String cached = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(KEY_JSON, null);
                if (cached != null && !cached.isEmpty()) {
                    ProviderTable t = parseJson(cached);
                    if (t != null) {
                        AILogger.i(TAG, "loaded from SharedPreferences cache");
                        return t;
                    }
                }
            } catch (Exception e) {
                AILogger.w(TAG, "prefs cache load failed: " + e.getMessage());
            }
        }
        // 3) assets 内置
        try {
            if (appContext != null) {
                try (InputStream is = appContext.getAssets().open(ASSET_FILE)) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                    ProviderTable t = parseJson(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                    if (t != null) {
                        AILogger.i(TAG, "loaded from assets");
                        return t;
                    }
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "assets load failed: " + e.getMessage());
        }
        return null;
    }

    private static String readFile(File f) throws Exception {
        try (InputStream is = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static ProviderTable parseJson(String json) {
        try {
            return new Gson().fromJson(json, ProviderTable.class);
        } catch (Exception e) {
            AILogger.w(TAG, "parse providers.json failed: " + e.getMessage());
            return null;
        }
    }

    // ==================== 服务商列表 ====================

    public List<Provider> getProviders() {
        ProviderTable t = table;
        return t != null && t.providers != null ? t.providers : Collections.emptyList();
    }

    public Provider getProvider(String id) {
        if (id == null) return null;
        for (Provider p : getProviders()) {
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    /** 端点识别：URL 包含任一 urlKeywords 即命中；未命中返回 null */
    public Provider matchByUrl(String apiUrl) {
        if (apiUrl == null) return null;
        String url = apiUrl.toLowerCase();
        for (Provider p : getProviders()) {
            if (p.urlKeywords != null) {
                for (String kw : p.urlKeywords) {
                    if (kw != null && !kw.isEmpty() && url.contains(kw.toLowerCase())) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /** 服务商显示名（仅展示用），未识别返回 "Custom" */
    public String providerName(String apiUrl) {
        Provider p = matchByUrl(apiUrl);
        return p != null ? p.name : "Custom";
    }

    /**
     * 该端点是否需要信任自签/非标准 CA 证书（providers.json 服务商对象声明 trustAllCerts=true 时）。
     * 默认 false=系统证书校验；未识别服务商（自定义端点）同样返回 false，保持安全默认。
     */
    public boolean needsTrustAllCerts(String apiUrl) {
        Provider p = matchByUrl(apiUrl);
        return p != null && p.trustAllCerts;
    }

    // ==================== 模型规则（思考参数） ====================

    /** 是否支持深度思考：模型名命中任一服务商的 thinking.modelKeywords 即视为思考模型 */
    public boolean isThinkingModelName(String modelName) {
        if (modelName == null) return false;
        String m = modelName.toLowerCase();
        for (Provider p : getProviders()) {
            if (p.thinking != null && p.thinking.modelKeywords != null) {
                for (String kw : p.thinking.modelKeywords) {
                    if (kw != null && !kw.isEmpty() && m.contains(kw.toLowerCase())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 该模型是否"始终思考"（不支持关闭思考，如智谱 GLM-5 系列）：
     * 此类模型传 enable_thinking=false 会被服务端 400（code 1210: 该模型始终思考，不支持关闭思考），
     * 应跳过关闭思考参数（不传任何 thinking 开关，服务端按默认思考）。
     */
    public boolean isAlwaysThinkingModel(String modelName) {
        if (modelName == null) return false;
        String m = modelName.trim().toLowerCase();
        // **仅按配置声明的型号关键词判定**（providers.json → thinking.alwaysThinkingKeywords）。
        // 同服务商内不同型号能力不同：智谱 GLM-5.3 / 5.3-FLASH / 5.3-FLASHX 不可关闭思考
        // （传 disabled 会被服务端拒绝），而 GLM-5.2 及以下**可以**关闭。
        // 因此不能再按 "glm-5" 前缀一刀切 —— 那会把可关闭的 GLM-5/5.1/5.2 也误锁成常开。
        for (Provider p : getProviders()) {
            if (p.thinking == null || p.thinking.alwaysThinkingKeywords == null) continue;
            for (String kw : p.thinking.alwaysThinkingKeywords) {
                if (kw != null && !kw.isEmpty() && m.contains(kw.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 思考参数名 —— **按实际服务商**取，其次按模型名回落。
     *
     * <p><b>为什么必须带 apiUrl</b>：此前只按模型名在所有服务商里找"第一个命中关键词"的，
     * 于是同一个模型名会被**先出现的服务商**决定参数形态。实测 bug：请求发往官方
     * DeepSeek，模型名 {@code deepseek-v4-flash}，但 providers.json 里 dashscope /
     * xfyun / siliconflow 等更早声明了 {@code deepseek-v4} 关键词 → 取到
     * {@code enable_thinking}，而 DeepSeek 要的是 {@code thinking.type}
     * （嵌套 {@code {"thinking":{"type":"enabled"}}}）→ 思考参数形态发错。</p>
     *
     * @param apiUrl    服务商地址（用于定位实际服务商）；为 null 时退回旧的全局匹配
     * @param modelName 模型名
     */
    public String getThinkingParamName(String apiUrl, String modelName) {
        Provider provider = matchByUrl(apiUrl);
        if (provider != null && provider.thinking != null
                && provider.thinking.param != null && !provider.thinking.param.isEmpty()) {
            return provider.thinking.param;
        }
        return getThinkingParamName(modelName);
    }

    /**
     * 思考参数名（仅按模型名）—— 全局匹配，**仅在无法识别服务商时使用**。
     * 注意其局限：同一模型名可能命中多个服务商的关键词，结果取决于配置顺序。
     */
    public String getThinkingParamName(String modelName) {
        if (modelName != null) {
            String m = modelName.toLowerCase();
            for (Provider p : getProviders()) {
                if (p.thinking != null && p.thinking.modelKeywords != null) {
                    for (String kw : p.thinking.modelKeywords) {
                        if (kw != null && !kw.isEmpty() && m.contains(kw.toLowerCase())
                                && p.thinking.param != null && !p.thinking.param.isEmpty()) {
                            return p.thinking.param;
                        }
                    }
                }
            }
        }
        return "enable_thinking";
    }

    /** 思考指令（按实际服务商优先，其次按模型名） */
    public String getThinkingInstruction(String apiUrl, String modelName) {
        Provider provider = matchByUrl(apiUrl);
        if (provider != null && provider.thinking != null
                && provider.thinking.instruction != null && !provider.thinking.instruction.isEmpty()) {
            return provider.thinking.instruction;
        }
        return getThinkingInstruction(modelName);
    }

    /** 思考指令（仅按模型名）：命中服务商的 thinking.instruction；未命中返回通用指令 */
    public String getThinkingInstruction(String modelName) {
        if (modelName != null) {
            String m = modelName.toLowerCase();
            for (Provider p : getProviders()) {
                if (p.thinking != null && p.thinking.modelKeywords != null) {
                    for (String kw : p.thinking.modelKeywords) {
                        if (kw != null && !kw.isEmpty() && m.contains(kw.toLowerCase())
                                && p.thinking.instruction != null && !p.thinking.instruction.isEmpty()) {
                            return p.thinking.instruction;
                        }
                    }
                }
            }
        }
        return "你当前处于深度思考模式。对于复杂问题，请先进行系统性的分析推理（输出在 reasoning_content 思考链中），再给出最终答案。\n"
            + "思考阶段：拆解问题→多角度分析→逐步推理验证逻辑链条。\n"
            + "最终回答：结论先行，简洁明确，只保留关键论据。";
    }

    // ==================== 统一地址拼装 ====================

    /**
     * 构建 OpenAI 兼容格式 URL（全项目统一入口）。
     * 规则：
     * - endpoint 自带版本前缀（/v1/...、/v4/...）→ 直接 baseUrl + endpoint（Anthropic /v1/messages 等）
     * - baseUrl 已以版本路径结尾（/v1、/v4 等，如智谱 /api/paas/v4）→ 直接拼 endpoint
     * - baseUrl 已以 endpoint 结尾 → 原样返回
     * - 否则默认补 /v1 + endpoint（OpenAI/DeepSeek/Kimi/Qwen 等）
     */
    public String buildUrl(String apiUrl, String endpoint) {
        // 完整 URL 端点（百炼/百度/讯飞文生图等独立 endpoint）直接使用，不拼 baseUrl
        if (endpoint != null && (endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
            return endpoint;
        }
        String baseUrl = (apiUrl == null || apiUrl.isEmpty()) ? "https://api.openai.com" : apiUrl;
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (endpoint == null) endpoint = "";
        if (endpoint.matches("^/v\\d+/.*")) {
            return baseUrl + endpoint;
        }
        if (baseUrl.endsWith(endpoint)) {
            return baseUrl;
        }
        if (baseUrl.matches(".*/v\\d+$")) {
            return baseUrl + endpoint;
        }
        return baseUrl + "/v1" + endpoint;
    }

    /** 服务商级 chat 端点（缺省用全局配置表 chatEndpoint） */
    public String getChatEndpoint(String apiUrl) {
        Provider p = matchByUrl(apiUrl);
        if (p != null && p.chatEndpoint != null && !p.chatEndpoint.isEmpty()) {
            return p.chatEndpoint;
        }
        ProviderTable t = table;
        return t != null && t.chatEndpoint != null && !t.chatEndpoint.isEmpty()
                ? t.chatEndpoint : "/chat/completions";
    }

    /**
     * 按**协议**构造 chat URL —— 与 {@link #buildUrl} 的差别只在 Ollama 原生端点。
     *
     * <p>{@code buildUrl} 会给没有 {@code /vN} 的 baseUrl 补 {@code /v1}，这对
     * OpenAI 兼容端点是正确的；但 Ollama 原生端点是 {@code /api/chat}，官方 base 是
     * {@code http://host:11434}，**不能带 /v1**（{@code /v1/api/chat} 是 404）。
     * 而本工程 Ollama 的 baseUrl 配的是 {@code http://localhost:11434/v1}（兼容层用法），
     * 所以走原生协议时必须把末尾的 {@code /v1} 去掉。</p>
     */
    public String buildChatUrl(String apiUrl, String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) return buildUrl(apiUrl, endpoint);
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) return endpoint;
        if (PROTOCOL_OLLAMA.equals(getChatProtocolFromEndpoint(endpoint))) {
            String base = apiUrl == null ? "" : apiUrl;
            base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            // 去掉兼容层的 /v1 尾巴，避免拼成 /v1/api/chat
            if (base.matches(".*/v\\d+$")) base = base.substring(0, base.lastIndexOf('/'));
            return base + endpoint;
        }
        return buildUrl(apiUrl, endpoint);
    }

    /** 仅按端点字符串判定协议（{@link #getChatProtocol} 的内部复用） */
    private static String getChatProtocolFromEndpoint(String endpoint) {
        if (endpoint.endsWith("/api/chat") || endpoint.endsWith("/api/generate")) return PROTOCOL_OLLAMA;
        if (endpoint.endsWith("/messages")) return PROTOCOL_ANTHROPIC;
        if (endpoint.endsWith("/responses")) return PROTOCOL_RESPONSES;
        return PROTOCOL_OPENAI;
    }

    /** 服务商级 models 端点（缺省用全局配置表 modelsEndpoint） */
    public String getModelsEndpoint(String apiUrl) {
        Provider p = matchByUrl(apiUrl);
        if (p != null && p.modelsEndpoint != null && !p.modelsEndpoint.isEmpty()) {
            return p.modelsEndpoint;
        }
        ProviderTable t = table;
        return t != null && t.modelsEndpoint != null && !t.modelsEndpoint.isEmpty()
                ? t.modelsEndpoint : "/models";
    }

    // ==================== 统一鉴权 ====================

    /**
     * 生成鉴权头 Map（key=header名, value=header值），按服务商配置表 auth 类型：
     * <ul>
     *   <li>bearer → Authorization: Bearer key（默认，OpenAI 兼容全家）</li>
     *   <li>x-api-key → x-api-key: key，且 Anthropic 服务商附加 anthropic-version: 2023-06-01</li>
     *   <li>apikey-header → apikey: key（少数服务商专用 header 名）</li>
     *   <li>query-key → 无 header（密钥走 URL ?key=，见 {@link #withAuthQuery}）</li>
     *   <li>none → 无 header（免鉴权，如 Ollama）</li>
     *   <li>hmac-xfyun → Authorization: HMAC-SHA256 签名（apiKey + apiSecret + URL host/date，POST）</li>
     *   <li>oauth-baidu → Authorization: Bearer access_token（apiKey=API Key，apiSecret=Secret Key，换 token 带缓存）</li>
     * </ul>
     * 未识别服务商默认 bearer；key 为空返回空 Map（避免发出无效 Authorization 头）。
     */
    public Map<String, String> getAuthHeaders(String apiUrl, String apiKey, String apiSecret, String appId) {
        Map<String, String> headers = new HashMap<>();
        if (apiKey == null || apiKey.isEmpty()) return headers;
        Provider p = matchByUrl(apiUrl);
        String auth = p != null && p.auth != null && !p.auth.isEmpty() ? p.auth : "bearer";

        switch (auth) {
            case "x-api-key":
                headers.put("x-api-key", apiKey);
                if (p != null && "anthropic".equalsIgnoreCase(p.id)) {
                    headers.put("anthropic-version", "2023-06-01");
                }
                break;
            case "apikey-header":
                headers.put("apikey", apiKey);
                break;
            case "query-key":
            case "none":
                // 密钥在 URL query 或免鉴权：不设任何 header
                break;
            case "hmac-xfyun": {
                String authHeader = buildXfyunHmacAuth(apiKey, apiSecret, apiUrl, "POST");
                if (authHeader != null) headers.put("Authorization", authHeader);
                break;
            }
            case "oauth-baidu": {
                String token = getBaiduAccessToken(apiKey, apiSecret);
                if (token != null) headers.put("Authorization", "Bearer " + token);
                break;
            }
            case "bearer":
            default:
                headers.put("Authorization", "Bearer " + apiKey);
                break;
        }
        return headers;
    }

    /**
     * query-key 型服务商（Gemini 等）：URL 尚未带 key 参数时追加 ?key=xxx；其他类型原样返回。
     * 调用点应在 openConnection 之前处理 URL。
     */
    public String withAuthQuery(String url, String apiKey) {
        if (url == null || url.isEmpty() || apiKey == null || apiKey.isEmpty()) return url;
        Provider p = matchByUrl(url);
        if (p == null || !"query-key".equals(p.auth)) return url;
        try {
            java.net.URI uri = new java.net.URI(url);
            String query = uri.getQuery();
            if (query != null && query.contains("key=")) {
                return url; // 已有 key 参数
            }
            return url + (query == null || query.isEmpty() ? "?" : "&")
                    + "key=" + java.net.URLEncoder.encode(apiKey, "UTF-8");
        } catch (Exception e) {
            return url + (url.contains("?") ? "&" : "?") + "key=" + apiKey;
        }
    }

    /** 便捷：把鉴权头直接应用到 HttpURLConnection（query-key/none 不设头） */
    public void applyAuthHeaders(HttpURLConnection conn, String apiUrl, String apiKey, String apiSecret, String appId) {
        if (conn == null) return;
        for (Map.Entry<String, String> e : getAuthHeaders(apiUrl, apiKey, apiSecret, appId).entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }
    }

    /**
     * 兼容旧签名：返回 Authorization 单值（无则 null）。
     * 注意不再返回 "x-api-key: xxx" 这类「名: 值」拼法（旧实现是 bug）；
     * 多 header 场景请用 {@link #getAuthHeaders} / {@link #applyAuthHeaders}。
     */
    public String getAuthHeader(String apiUrl, String apiKey) {
        return getAuthHeaders(apiUrl, apiKey, null, null).get("Authorization");
    }

    // 讯飞星火 HMAC-SHA256 签名（Authorization 为 base64 的 authorization_origin）
    private String buildXfyunHmacAuth(String apiKey, String apiSecret, String apiUrl, String method) {
        try {
            if (apiKey == null || apiSecret == null || apiSecret.isEmpty()) return null;
            URL u = apiUrl != null ? new URL(apiUrl) : null;
            String host = u != null ? u.getHost() : "";
            String path = u != null && u.getPath() != null && !u.getPath().isEmpty() ? u.getPath() : "/";
            if (u != null && u.getQuery() != null && !u.getQuery().isEmpty()) {
                path = path + "?" + u.getQuery();
            }
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
            String date = sdf.format(new java.util.Date());
            String signingOrigin = "host: " + host + "\n"
                    + "date: " + date + "\n"
                    + (method == null ? "POST" : method) + " " + path + " HTTP/1.1";
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = android.util.Base64.encodeToString(
                    mac.doFinal(signingOrigin.getBytes(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP);
            String authorizationOrigin = "api_key=\"" + apiKey
                    + "\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"" + signature + "\"";
            return android.util.Base64.encodeToString(
                    authorizationOrigin.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            AILogger.e(TAG, "讯飞 HMAC 签名失败", e);
            return null;
        }
    }

    // 百度千帆 OAuth token（缓存至过期前 5 分钟）
    private static final Map<String, String> BAIDU_TOKEN_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> BAIDU_TOKEN_EXPIRY = new ConcurrentHashMap<>();

    private String getBaiduAccessToken(String apiKey, String apiSecret) {
        if (apiKey == null || apiKey.isEmpty() || apiSecret == null || apiSecret.isEmpty()) return null;
        String cacheKey = apiKey + ":" + apiSecret;
        String cached = BAIDU_TOKEN_CACHE.get(cacheKey);
        Long exp = BAIDU_TOKEN_EXPIRY.get(cacheKey);
        if (cached != null && exp != null && exp > System.currentTimeMillis()) return cached;
        try {
            String tokenUrl = "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials&client_id="
                    + java.net.URLEncoder.encode(apiKey, "UTF-8")
                    + "&client_secret=" + java.net.URLEncoder.encode(apiSecret, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(tokenUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream is = conn.getInputStream();
                String body = readStream(is);
                is.close();
                JSONObject obj = new JSONObject(body);
                String token = obj.optString("access_token");
                int expiresIn = obj.optInt("expires_in", 2592000);
                if (!token.isEmpty()) {
                    BAIDU_TOKEN_CACHE.put(cacheKey, token);
                    BAIDU_TOKEN_EXPIRY.put(cacheKey, System.currentTimeMillis() + (expiresIn - 300) * 1000L);
                    return token;
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            AILogger.e(TAG, "百度千帆 token 获取失败", e);
        }
        return null;
    }

    private String readStream(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    // ==================== 服务能力（services） ====================

    /**
     * 服务商是否要求多轮上下文回传 reasoning_content（assistant 消息必须带该字段，
     * 否则 HTTP 400）。配置表 requiresReasoningInContext=true 生效；缺省 false。
     */
    public boolean requiresReasoningInContext(String apiUrl) {
        Provider p = matchByUrl(apiUrl);
        return p != null && p.requiresReasoningInContext;
    }

    /** 服务商是否提供指定服务能力（agent/vision/webSearch/embedding/rerank/imageGen/functionCalling/tts/asr/chat）。
     * 未在配置中声明时：仅 chat 视为默认支持（OpenAI 兼容基本能力），
     * 其余按 false（避免误判视觉等能力；模型名推断/用户手动设置作为兜底）。
     */
    public boolean hasService(String apiUrl, String service) {
        Provider p = matchByUrl(apiUrl);
        if (p == null || p.services == null || service == null) return false;
        ServiceConfig sc = getServiceConfig(p, service);
        if (sc == null) {
            return "chat".equals(service);
        }
        return sc.enabled == null || sc.enabled;
    }

    /** 服务能力端点（缺省：按服务类型回落到标准 OpenAI 兼容端点，chat 缺省全局 chatEndpoint） */
    public String getServiceEndpoint(String apiUrl, String service) {
        Provider p = matchByUrl(apiUrl);
        if (p != null && p.services != null) {
            ServiceConfig sc = getServiceConfig(p, service);
            if (sc != null && sc.endpoint != null && !sc.endpoint.isEmpty()) {
                return sc.endpoint;
            }
        }
        String def = defaultServiceEndpoint(service);
        if (def != null) return def;
        return getChatEndpoint(apiUrl);
    }

    /** 未声明端点时的标准 OpenAI 兼容兜底（避免 embedding 等误用 chat 端点） */
    private String defaultServiceEndpoint(String service) {
        if (service == null) return null;
        switch (service) {
            case "embedding": return "/embeddings";
            case "rerank": return "/rerank";
            case "imageGen": return "/images/generations";
            case "agent": return "/responses";
            default: return null; // chat/vision/webSearch/tts/asr 等走 chat 端点或独立 wss
        }
    }

    /** chat 协议族：按 chat 端点形态判定，新增服务商只改配置表，不改调用方 */
    public static final String PROTOCOL_OPENAI = "openai";        // /chat/completions（含各家 OpenAI 兼容层）
    public static final String PROTOCOL_ANTHROPIC = "anthropic";  // /v1/messages
    public static final String PROTOCOL_RESPONSES = "responses";  // /responses
    public static final String PROTOCOL_OLLAMA = "ollama";        // 原生 /api/chat（ndjson）

    /**
     * 当前服务商 chat 走哪套协议 —— **由配置表的 chat 端点决定**。
     *
     * <p>为什么要有它：服务商协议差异此前散落在调用方的 {@code endsWith("/responses")}、
     * {@code isAnthropicAPI(url)} 这类字符串判断里，每加一家就要改一次调用代码。
     * 集中到配置驱动后，补一家只改 providers.json。</p>
     *
     * <p>判定顺序（端点形态优先，URL 形态兜底）：</p>
     * <ol>
     *   <li>{@code /api/chat} 或 {@code /api/generate} → Ollama 原生（ndjson，非 SSE）</li>
     *   <li>{@code /messages} → Anthropic Messages</li>
     *   <li>{@code /responses} → OpenAI Responses</li>
     *   <li>其余 → OpenAI 兼容（含 DashScope/Gemini 等官方兼容层）</li>
     * </ol>
     */
    public String getChatProtocol(String apiUrl) {
        String ep = getServiceEndpoint(apiUrl, "chat");
        String url = apiUrl == null ? "" : apiUrl;
        if (ep != null) {
            if (ep.endsWith("/api/chat") || ep.endsWith("/api/generate")) return PROTOCOL_OLLAMA;
            if (ep.endsWith("/messages")) return PROTOCOL_ANTHROPIC;
            if (ep.endsWith("/responses")) return PROTOCOL_RESPONSES;
        }
        // 端点未声明时按地址兜底（Anthropic 的 baseUrl 无 /v1 后缀，靠域名识别）
        if (url.contains("anthropic.com")) return PROTOCOL_ANTHROPIC;
        return PROTOCOL_OPENAI;
    }

    /** 服务预置模型名（embedding/imageGen/rerank/tts/asr），配置表未声明返回 null */
    public String getServiceModel(String apiUrl, String service) {
        Provider p = matchByUrl(apiUrl);
        if (p != null) {
            String v = null;
            switch (service) {
                case "embedding": v = p.embeddingModel; break;
                case "rerank": v = p.rerankModel; break;
                case "imageGen": v = p.imageModel; break;
                case "tts": v = p.ttsModel; break;
                case "asr": v = p.asrModel; break;
                default: return null;
            }
            if (v != null && !v.isEmpty()) return v;
        }
        // 未匹配服务商或服务商未声明 → 回退全局配置表（自定义 OpenAI 兼容端点可用全局默认）
        ProviderTable t = table;
        if (t == null) return null;
        switch (service) {
            case "embedding": return t.embeddingModel;
            case "rerank": return t.rerankModel;
            case "imageGen": return t.imageModel;
            default: return null;
        }
    }

    // ==================== 模型级能力（models[] 预置模型） ====================
    // providers.json 的 models[] 支持两种形态：
    //   "deepseek-chat"                              → 纯字符串，仅声明 chat，能力继承服务商级 services
    //   {"name":"qwen-vl-max","capabilities":[...]}  → 对象，模型级能力覆盖（与服务商级取交集）
    // 用于：模型列表合并预置模型、按模型判能力（不再服务商级一刀切）。

    /** 配置表预置模型名列表（兼容字符串/对象形态），未声明返回空列表 */
    public List<String> getPredefinedModels(String apiUrl) {
        Provider p = matchByUrl(apiUrl);
        if (p == null || p.models == null) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (JsonElement e : p.models) {
            if (e == null) continue;
            String n = null;
            if (e.isJsonPrimitive()) {
                n = e.getAsString();
            } else if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("name")) n = o.get("name").getAsString();
            }
            if (n != null && !n.isEmpty()) names.add(n);
        }
        return names;
    }

    /** 模型级能力列表（models[] 对象形态的 capabilities），无模型级声明返回 null */
    public List<String> getModelCapabilities(String apiUrl, String modelName) {
        if (modelName == null) return null;
        Provider p = matchByUrl(apiUrl);
        if (p == null || p.models == null) return null;
        for (JsonElement e : p.models) {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (!o.has("name") || !o.has("capabilities") || !o.get("capabilities").isJsonArray()) continue;
            if (!modelName.equals(o.get("name").getAsString())) continue;
            JsonArray arr = o.getAsJsonArray("capabilities");
            List<String> caps = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) caps.add(arr.get(i).getAsString());
            return caps;
        }
        return null;
    }

    /**
     * 模型是否支持指定能力（按模型判能力，不再服务商级一刀切）：
     * - 服务商级未声明该能力 → false（接口不存在，模型不可能支持）
     * - 模型级 capabilities 声明 → 看模型级是否包含
     * - 模型级未声明（API 自动获取/预置纯字符串）→ 继承服务商级
     */
    public boolean supportsModelCapability(String apiUrl, String modelName, String capability) {
        if (capability == null) return false;
        if (!hasService(apiUrl, capability)) return false;
        List<String> modelCaps = getModelCapabilities(apiUrl, modelName);
        if (modelCaps == null) return true; // 模型级未声明 → 继承服务商级
        for (String c : modelCaps) {
            if (capability.equalsIgnoreCase(c)) return true;
        }
        return false;
    }

    /** 服务能力可选参数名（如 webSearch 的请求体开关参数），无则 null */
    public String getServiceParam(String apiUrl, String service) {
        Provider p = matchByUrl(apiUrl);
        if (p == null || p.services == null) return null;
        ServiceConfig sc = getServiceConfig(p, service);
        return sc != null ? sc.param : null;
    }

    /** webSearch 声明的端点约束（如 "responses"：仅该端点支持注入），null 表示不限端点 */
    public String getWebSearchEndpoint(String apiUrl) {
        Provider p = matchByUrl(apiUrl);
        if (p == null || p.services == null || p.services.webSearch == null) return null;
        return p.services.webSearch.endpoint;
    }

    /** 服务商声明的全部可用服务名列表（UI 展示/设置用） */
    public List<String> getServiceNames(String apiUrl) {
        Provider p = matchByUrl(apiUrl);
        List<String> out = new ArrayList<>();
        if (p == null || p.services == null) return out;
        if (p.services.chat != null && (p.services.chat.enabled == null || p.services.chat.enabled))
            out.add("chat");
        if (p.services.agent != null && (p.services.agent.enabled == null || p.services.agent.enabled))
            out.add("agent");
        if (p.services.vision != null && (p.services.vision.enabled == null || p.services.vision.enabled))
            out.add("vision");
        if (p.services.webSearch != null && (p.services.webSearch.enabled == null || p.services.webSearch.enabled))
            out.add("webSearch");
        if (p.services.embedding != null && (p.services.embedding.enabled == null || p.services.embedding.enabled))
            out.add("embedding");
        if (p.services.rerank != null && (p.services.rerank.enabled == null || p.services.rerank.enabled))
            out.add("rerank");
        if (p.services.imageGen != null && (p.services.imageGen.enabled == null || p.services.imageGen.enabled))
            out.add("imageGen");
        if (p.services.functionCalling != null && (p.services.functionCalling.enabled == null || p.services.functionCalling.enabled))
            out.add("functionCalling");
        if (p.services.tts != null && (p.services.tts.enabled == null || p.services.tts.enabled))
            out.add("tts");
        if (p.services.asr != null && (p.services.asr.enabled == null || p.services.asr.enabled))
            out.add("asr");
        return out;
    }

    private static ServiceConfig getServiceConfig(Provider p, String service) {
        if (p.services == null) return null;
        switch (service) {
            case "chat": return p.services.chat;
            case "agent": return p.services.agent;
            case "vision": return p.services.vision;
            case "webSearch": return p.services.webSearch;
            case "embedding": return p.services.embedding;
            case "rerank": return p.services.rerank;
            case "imageGen": return p.services.imageGen;
            case "functionCalling": return p.services.functionCalling;
            case "tts": return p.services.tts;
            case "asr": return p.services.asr;
            default: return null;
        }
    }

    // ==================== 预置端点模板（UI 下拉 / 语音注册表） ====================

    public List<EndpointTemplate> getEndpointTemplates() {
        List<EndpointTemplate> list = new ArrayList<>();
        for (Provider p : getProviders()) {
            list.add(new EndpointTemplate(p.name, p.baseUrl, p.ttsModel, p.asrModel));
        }
        return list;
    }

    // ==================== 版本信息（供 UI/Agent 展示） ====================

    public String getTableVersion() {
        return table != null ? table.version : null;
    }

    public int getTableSize() {
        return table != null && table.providers != null ? table.providers.size() : 0;
    }

    public String getTableJson() {
        return table != null ? gson.toJson(table) : null;
    }

    // ==================== 数据类 ====================

    public static class ProviderTable {
        public String version;
        public String note;
        public String chatEndpoint;
        public String modelsEndpoint;
        public String embeddingEndpoint;   // 可选：全局 Embedding 端点
        public String rerankEndpoint;      // 可选：全局 Rerank 端点
        public String imageEndpoint;       // 可选：全局文生图端点
        public String embeddingModel;      // 可选：全局 Embedding 预置模型（未匹配服务商时兜底）
        public String rerankModel;         // 可选：全局 Rerank 预置模型（未匹配服务商时兜底）
        public String imageModel;          // 可选：全局文生图预置模型（未匹配服务商时兜底）
        public List<Provider> providers;
    }

    public static class Provider {
        public String id;
        public String name;
        public String baseUrl;
        public List<String> urlKeywords;
        public String auth;                 // bearer / x-api-key / query-key / apikey-header / hmac-xfyun / oauth-baidu / none
        public String chatEndpoint;         // 可选，服务商级覆盖
        public String modelsEndpoint;       // 可选，服务商级覆盖
        public Thinking thinking;
        /** 可选：预置模型列表。兼容两种形态：
         *  纯字符串 "deepseek-chat" = 仅声明 chat，能力继承服务商级 services；
         *  对象 {"name":"qwen-vl-max","capabilities":["chat","vision","functionCalling"]} = 模型级能力覆盖。 */
        public List<JsonElement> models;
        public String embeddingModel;       // 可选：Embedding 预置模型
        public String rerankModel;          // 可选：Rerank 预置模型
        public String imageModel;           // 可选：文生图预置模型
        public String ttsModel;             // 可选：语音合成预置模型
        public String asrModel;             // 可选：语音识别预置模型
        /** 可选：多轮对话时 assistant 消息必须回传 reasoning_content（如部分在线思考模型的硬性要求，
         *  不回传报 HTTP 400）。true=始终回传（不论是否思考模式）；缺省 false=按规范不回传/仅思考模式回传。 */
        public boolean requiresReasoningInContext;
        /** 可选：是否信任自签/非标准 CA 证书（内网网关、自建 vLLM/llama.cpp 走 https 自签证书时置 true）。
         *  默认 false=使用系统证书校验，保证云端 API（百炼/OpenAI/Gemini 等正规 CA）流量不可被中间人截获。 */
        public boolean trustAllCerts;
        public Services services;           // 可选：服务能力声明（agent/多模态/网络搜索/embedding/rerank/文生图/语音等）
    }

    /** 服务能力声明：enabled=是否提供；endpoint=独立端点（缺省继承 chat）；param=可选参数名 */
    public static class Services {
        public ServiceConfig chat;
        public ServiceConfig agent;
        public ServiceConfig vision;
        public ServiceConfig webSearch;
        public ServiceConfig embedding;
        public ServiceConfig rerank;
        public ServiceConfig imageGen;
        public ServiceConfig functionCalling;
        public ServiceConfig tts;
        public ServiceConfig asr;
    }

    public static class ServiceConfig {
        public Boolean enabled;
        public String endpoint;
        public String param;
    }

    public static class Thinking {
        public String param;                // enable_thinking / reasoning_effort
        public Boolean defaultEnabled;      // 默认是否开启思考
        public List<String> modelKeywords;  // 命中即视为思考模型
        /** 命中即"不可关闭思考"的模型（传禁用会被服务端拒绝，如智谱 GLM-5.3 报错 1210） */
        public List<String> alwaysThinkingKeywords;
        public String instruction;          // 思考指令（注入 system prompt）
    }

    public static class EndpointTemplate {
        public final String name;
        public final String apiUrl;
        public final String ttsModel;
        public final String asrModel;

        public EndpointTemplate(String name, String apiUrl, String ttsModel, String asrModel) {
            this.name = name;
            this.apiUrl = apiUrl;
            this.ttsModel = ttsModel;
            this.asrModel = asrModel;
        }
    }
}
