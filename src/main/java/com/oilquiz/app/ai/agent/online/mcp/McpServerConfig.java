package com.oilquiz.app.ai.agent.online.mcp;

import org.json.JSONException;
import org.json.JSONObject;

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
    /** 是否为内置模板（内置项可被删除/编辑，但首次启动会自动补齐） */
    public final boolean builtin;

    public McpServerConfig(String id, String name, String url, Transport transport,
                           String token, boolean enabled, boolean builtin) {
        this(id, name, url, transport, token, "", enabled, builtin);
    }

    public McpServerConfig(String id, String name, String url, Transport transport,
                           String token, String tokenRef, boolean enabled, boolean builtin) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.transport = transport;
        this.token = token;
        this.tokenRef = tokenRef;
        this.enabled = enabled;
        this.builtin = builtin;
    }

    // ==================== 序列化 ====================

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("url", url);
        o.put("transport", transport.name());
        if (token != null && !token.isEmpty()) o.put("token", token);
        if (tokenRef != null && !tokenRef.isEmpty()) o.put("tokenRef", tokenRef);
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
            boolean enabled = o.optBoolean("enabled", false);
            boolean builtin = o.optBoolean("builtin", false);
            return new McpServerConfig(id, name, url, transport, token, tokenRef, enabled, builtin);
        } catch (Exception e) {
            return null;
        }
    }

    /** 派生副本：改令牌 */
    public McpServerConfig withToken(String newToken) {
        return new McpServerConfig(id, name, url, transport, newToken, tokenRef, enabled, builtin);
    }

    /** 派生副本：改密钥引用 */
    public McpServerConfig withTokenRef(String newTokenRef) {
        return new McpServerConfig(id, name, url, transport, token, newTokenRef, enabled, builtin);
    }

    /** 派生副本：改启用状态 */
    public McpServerConfig withEnabled(boolean newEnabled) {
        return new McpServerConfig(id, name, url, transport, token, newEnabled, builtin);
    }

    /** 生成一个稳定的配置 id（内置模板固定，自定义按名称去重） */
    public static String normalizeId(String rawId) {
        if (rawId == null) return "";
        String s = rawId.trim().toLowerCase();
        s = s.replaceAll("[^a-z0-9_-]", "_");
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    @Override
    public String toString() {
        return "McpServerConfig{" + id + ", " + name + ", " + transport + ", enabled=" + enabled + "}";
    }
}
