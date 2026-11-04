package com.oilquiz.app.ai.agent.online.mcp;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个 MCP server 的配置。
 *
 * <p>两种传输约定：</p>
 * <ul>
 *   <li>{@link Transport#SSE}：url 为 SSE 事件流端点（如 https://mcp.linear.app/sse），
 *       消息端点由服务端 {@code endpoint} 事件下发，未下发时回退为
 *       {@code url 去尾 /sse 后 + /message}。</li>
 *   <li>{@link Transport#STREAMABLE_HTTP}：url 为完整 JSON-RPC 端点（如
 *       https://api.githubcopilot.com/mcp/），客户端直接 POST。</li>
 * </ul>
 */
public class McpServerConfig {

    public enum Transport {
        /** 经典 SSE：GET 建流 + POST 发消息 */
        SSE,
        /** streamable HTTP：直接 POST，响应为单 JSON 或 SSE 流 */
        STREAMABLE_HTTP
    }

    public final String id;
    public final String name;
    public final String url;
    public final Transport transport;
    /** 可选鉴权令牌（Bearer 方式），留空表示无需鉴权 */
    public final String token;
    /**
     * 可选：引用 {@code APIKeyManager} 已存密钥的键名（APIConfig 的 name 或 saveAPIKey 的 service）。
     * 运行时 {@code token} 为空时按此键名从密钥库取值，无需重复填写。
     */
    public final String tokenRef;
    /** 是否启用（启用后才初始化并注册工具） */
    public final boolean enabled;
    /**
     * 来源标记：{@code true} = 由内置市场清单（{@code mcp_market_catalog.json}）装入，
     * {@code false} = 用户在管理页手填。只影响列表上的这一行标注，
     * <b>不限制编辑或删除</b>（早期版本用它表示"开机自动补齐的模板"，那套预置已撤掉）。
     */
    public final boolean builtin;
    /**
     * 逐工具审批策略：远端工具名 → {@code auto/ask/deny}。
     * 未登记时按 annotations 推断（readOnlyHint=true → auto，其余 ask）。
     */
    public final Map<String, String> toolApproval;

    public McpServerConfig(String id, String name, String url, Transport transport,
                           String token, boolean enabled, boolean builtin) {
        this(id, name, url, transport, token, "", enabled, builtin);
    }

    public McpServerConfig(String id, String name, String url, Transport transport,
                           String token, String tokenRef, boolean enabled, boolean builtin) {
        this(id, name, url, transport, token, tokenRef, enabled, builtin, null);
    }

    public McpServerConfig(String id, String name, String url, Transport transport,
                           String token, String tokenRef, boolean enabled, boolean builtin,
                           Map<String, String> toolApproval) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.transport = transport;
        this.token = token;
        this.tokenRef = tokenRef;
        this.enabled = enabled;
        this.builtin = builtin;
        this.toolApproval = toolApproval != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(toolApproval))
                : Collections.emptyMap();
    }

    // ==================== 序列化 ====================

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name != null && !name.isEmpty() ? name : id);
        o.put("url", url != null ? url : "");
        o.put("transport", transport != null ? transport.name() : Transport.STREAMABLE_HTTP.name());
        if (token != null && !token.isEmpty()) o.put("token", token);
        if (tokenRef != null && !tokenRef.isEmpty()) o.put("tokenRef", tokenRef);
        if (toolApproval != null && !toolApproval.isEmpty()) {
            JSONObject approval = new JSONObject();
            for (Map.Entry<String, String> e : toolApproval.entrySet()) {
                approval.put(e.getKey(), e.getValue());
            }
            o.put("toolApproval", approval);
        }
        o.put("enabled", enabled);
        o.put("builtin", builtin);
        return o;
    }

    public static McpServerConfig fromJson(JSONObject o) {
        if (o == null) return null;
        try {
            String id = o.optString("id");
            if (id.isEmpty()) return null;
            String name = o.optString("name", id);
            String url = o.optString("url");
            if (url.isEmpty()) return null;
            Transport transport;
            try {
                transport = Transport.valueOf(o.optString("transport", Transport.SSE.name()));
            } catch (IllegalArgumentException e) {
                transport = Transport.SSE;
            }
            String token = o.has("token") ? o.optString("token") : "";
            String tokenRef = o.has("tokenRef") ? o.optString("tokenRef") : "";
            Map<String, String> approvals = new LinkedHashMap<>();
            JSONObject approvalObj = o.optJSONObject("toolApproval");
            if (approvalObj != null) {
                Iterator<String> keys = approvalObj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    approvals.put(k, approvalObj.optString(k, ""));
                }
            }
            boolean enabled = o.optBoolean("enabled", false);
            boolean builtin = o.optBoolean("builtin", false);
            return new McpServerConfig(id, name, url, transport, token, tokenRef,
                    enabled, builtin, approvals);
        } catch (Exception e) {
            return null;
        }
    }

    /** 派生副本：改令牌 */
    public McpServerConfig withToken(String newToken) {
        return new McpServerConfig(id, name, url, transport, newToken, tokenRef,
                enabled, builtin, toolApproval);
    }

    /** 派生副本：改密钥引用 */
    public McpServerConfig withTokenRef(String newTokenRef) {
        return new McpServerConfig(id, name, url, transport, token, newTokenRef,
                enabled, builtin, toolApproval);
    }

    /** 派生副本：改端点 */
    public McpServerConfig withUrl(String newUrl) {
        return new McpServerConfig(id, name, newUrl, transport, token, tokenRef,
                enabled, builtin, toolApproval);
    }

    /** 派生副本：同时改端点与令牌（把 URL 里的明文密钥收进加密字段时用）。 */
    public McpServerConfig withUrlAndToken(String newUrl, String newToken) {
        return new McpServerConfig(id, name, newUrl, transport, newToken, tokenRef,
                enabled, builtin, toolApproval);
    }

    // ==================== 密钥在 URL 里的服务 ====================

    /**
     * 密钥占位符：{@code https://mcp.amap.com/mcp?key={key}}。
     *
     * <p>存在的理由是一致性而不是美观——同一个配置文件里，Bearer 类密钥已经走 Keystore 加密落盘，
     * 而 query 里的密钥原先是明文写死的（高德、腾讯位置服务）。占位符让两类服务用同一套存储：
     * 值放在加密的 {@link #token} 里，端点里只留 {@code {key}}，请求发出前才拼回去
     * （见 {@code McpManager#resolvedCfg}）。</p>
     */
    public static final String SECRET_PLACEHOLDER = "{key}";

    /** URL 是否含密钥占位符。 */
    public static boolean urlNeedsSecret(String url) {
        return url != null && url.contains(SECRET_PLACEHOLDER);
    }

    /** 把密钥填进占位符；没有占位符或没密钥就原样返回。密钥按 URL 查询串规则转义。 */
    public static String injectSecret(String url, String secret) {
        if (!urlNeedsSecret(url) || secret == null || secret.isEmpty()) return url;
        try {
            String encoded = java.net.URLEncoder.encode(secret, "UTF-8").replace("+", "%20");
            return url.replace(SECRET_PLACEHOLDER, encoded);
        } catch (java.io.UnsupportedEncodingException e) {
            return url.replace(SECRET_PLACEHOLDER, secret);   // UTF-8 必然存在，真出错也不该丢端点
        }
    }

    /**
     * 从明文 URL 里拆出查询串密钥：返回 {@code {带占位符的模板, 密钥值}}。
     *
     * <p>用于历史配置的一键收口：老条目把真实 Key 直接写在 URL 上落盘，这里把它挪进
     * 加密的 {@link #token} 字段，端点里只留占位符。识别不到就返回原串 + 空密钥。</p>
     */
    public static String[] splitQuerySecret(String url) {
        if (url == null || url.isEmpty()) return new String[]{url == null ? "" : url, ""};
        java.util.regex.Matcher m = QUERY_SECRET_PATTERN.matcher(url);
        if (!m.find()) return new String[]{url, ""};
        String value = m.group(2);
        if (value.isEmpty() || SECRET_PLACEHOLDER.equals(value)) {
            return new String[]{url, ""};   // 已经是占位符，不该再"迁移"一次
        }
        String template = url.substring(0, m.start(2)) + SECRET_PLACEHOLDER + url.substring(m.end(2));
        return new String[]{template, value};
    }

    /** query 里的密钥参数名（与 {@link #maskUrl} 同一份名单，两处口径必须一致）。 */
    private static final java.util.regex.Pattern QUERY_SECRET_PATTERN =
            java.util.regex.Pattern.compile(
                    "(?i)([?&](?:key|token|api_key|apikey|access_token|secret)=)([^&#]*)");

    /** 端点是否"除密钥占位符外"合法（市场里 QUERY_KEY 条目允许带着 {key} 保存）。 */
    public static boolean isUsableEndpointWithKeyPlaceholder(String url) {
        if (url == null) return false;
        return isUsableEndpoint(url.replace(SECRET_PLACEHOLDER, "placeholder"));
    }

    /** 派生副本：改启用状态 */
    public McpServerConfig withEnabled(boolean newEnabled) {
        return new McpServerConfig(id, name, url, transport, token, tokenRef,
                newEnabled, builtin, toolApproval);
    }

    /** 派生副本：设置/清除某个远端工具的审批策略（null/空 = 跟随注解默认）。 */
    public McpServerConfig withToolApproval(String remoteTool, String policy) {
        Map<String, String> next = new LinkedHashMap<>(toolApproval);
        if (remoteTool == null || remoteTool.isEmpty()
                || policy == null || policy.isEmpty()) {
            if (remoteTool != null) next.remove(remoteTool);
        } else {
            next.put(remoteTool, policy);
        }
        return new McpServerConfig(id, name, url, transport, token, tokenRef,
                enabled, builtin, next);
    }

    /** 显式审批策略；未设置返回空串。 */
    public String approvalFor(String remoteTool) {
        if (remoteTool == null || toolApproval == null) return "";
        String v = toolApproval.get(remoteTool);
        return v != null ? v : "";
    }

    /** 生成一个稳定的配置 id（内置模板固定，自定义按名称去重） */
    public static String normalizeId(String rawId) {
        if (rawId == null) return "";
        String s = rawId.trim().toLowerCase();
        s = s.replaceAll("[^a-z0-9_-]", "_");
        // 中文名会全部变成下划线：压成一个并去掉首尾，避免“阿里云/腾讯云”生成同一个 id
        s = s.replaceAll("_+", "_");
        s = s.replaceAll("^_+|_+$", "");
        if (s.isEmpty()) s = "server";
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    /** 端点是否可用：必须 http(s)，且不能保留占位符（占位符添加后必然 401/404）。 */
    public static boolean isUsableEndpoint(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false;
        if (u.contains("{") || u.contains("}") || u.contains("你的") || u.contains("按开通")
                || u.contains("…") || u.contains("（")) {
            return false;
        }
        try {
            java.net.URI uri = new java.net.URI(url.trim());
            return uri.getHost() != null && uri.getScheme() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 展示/日志用：把 query 里的 key/token 值脱敏（腾讯位置服务等把 key 放在 URL 里）。 */
    public static String maskUrl(String url) {
        if (url == null) return "";
        return url.replaceAll(
                "(?i)([?&](?:key|token|api_key|apikey|access_token|secret)=)[^&#]+",
                "$1****");
    }

    @Override
    public String toString() {
        return "McpServerConfig{" + id + ", " + name + ", " + transport + ", enabled=" + enabled + "}";
    }
}
