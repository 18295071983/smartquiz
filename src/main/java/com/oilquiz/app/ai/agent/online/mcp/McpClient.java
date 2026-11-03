package com.oilquiz.app.ai.agent.online.mcp;

import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个 MCP server 的客户端：封装连接生命周期与工具协议。
 *
 * <p>协议流程：{@code initialize} 协商版本 → {@code notifications/initialized} →
 * {@code tools/list} 枚举远端工具 → {@code tools/call} 执行。
 * 所有方法均为同步阻塞（内部走 {@link McpTransport#send}），须在后台线程调用。</p>
 */
public class McpClient {

    private static final String TAG = "McpClient";
    private static final String CLIENT_NAME = "smartquiz-android";
    private static final String CLIENT_VERSION = "1.0";

    private final McpServerConfig config;
    private final McpTransport transport;
    private final AtomicLong nextId = new AtomicLong(1);

    private volatile boolean initialized;

    public McpClient(McpServerConfig config) {
        this.config = config;
        this.transport = createTransport(config);
    }

    /** 按配置的传输类型构建传输实例。 */
    private static McpTransport createTransport(McpServerConfig config) {
        if (config.transport == McpServerConfig.Transport.STREAMABLE_HTTP) {
            return new StreamableHttpMcpTransport(config.url, config.token);
        }
        return new SseMcpTransport(config.url, config.token);
    }

    // ==================== 生命周期 ====================

    /** 初始化（幂等）：initialize 协商 + initialized 通知。 */
    public synchronized void initialize() throws Exception {
        if (initialized) return;
        try {
            JSONObject params = new JSONObject();
            params.put("protocolVersion", McpConstants.PROTOCOL_VERSION_LATEST);
            params.put("capabilities", new JSONObject());
            JSONObject clientInfo = new JSONObject();
            clientInfo.put("name", CLIENT_NAME);
            clientInfo.put("version", CLIENT_VERSION);
            params.put("clientInfo", clientInfo);

            JSONObject resp = send(McpConstants.METHOD_INITIALIZE, params);
            if (McpJsonRpc.isError(resp)) {
                // 部分老版本服务端只认旧协议：降级重试一次
                params.put("protocolVersion", McpConstants.PROTOCOL_VERSION_LEGACY);
                resp = send(McpConstants.METHOD_INITIALIZE, params);
                if (McpJsonRpc.isError(resp)) {
                    throw new java.io.IOException(McpJsonRpc.errorMessage(resp));
                }
            }
            JSONObject result = McpJsonRpc.result(resp);
            AILogger.i(TAG, "MCP initialized: " + config.name
                    + " protocol=" + result.optString("protocolVersion", "?")
                    + " server=" + result.optJSONObject("serverInfo"));
            // 服务端可能回自己支持的版本；后续 Streamable HTTP 请求必须带协商版本 header
            transport.setProtocolVersion(result.optString(
                    "protocolVersion", McpConstants.PROTOCOL_VERSION_LATEST));

            // initialized 通知（无 id）
            sendNotification(McpConstants.METHOD_NOTIFICATION_INITIALIZED, null);
            initialized = true;
            // streamable HTTP 的 GET SSE 通知流（list_changed 等）；SSE 传输为空实现
            transport.startNotifications();
        } catch (Exception e) {
            AILogger.e(TAG, "initialize failed (" + config.id + "): " + e.getMessage(), e);
            throw e;
        }
    }

    // ==================== 工具 ====================

    /**
     * 枚举远端工具（tools/list）。
     *
     * <p>MCP 规范允许服务端用 {@code nextCursor} 分页；工具多的 server（GitHub 等）只读第一页
     * 会静默丢工具，因此这里按游标拉全。上限 20 页/2000 个工具，防止异常服务端返回
     * 重复游标导致死循环。</p>
     */
    public List<McpToolInfo> listTools() throws Exception {
        ensureInitialized();
        List<McpToolInfo> list = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        final int maxPages = 20;
        final int maxTools = 2000;
        final Set<String> seenCursors = new HashSet<>();
        do {
            JSONObject params = new JSONObject();
            if (cursor != null && !cursor.isEmpty()) params.put("cursor", cursor);
            JSONObject resp = send(McpConstants.METHOD_TOOLS_LIST, params);
            if (McpJsonRpc.isError(resp)) {
                throw new java.io.IOException(McpJsonRpc.errorMessage(resp));
            }
            JSONObject result = McpJsonRpc.result(resp);
            JSONArray tools = result.optJSONArray("tools");
            if (tools != null) {
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject t = tools.optJSONObject(i);
                    if (t == null) continue;
                    String name = t.optString("name");
                    if (name.isEmpty()) continue;
                    String description = t.optString("description", "");
                    JSONObject schema = t.optJSONObject("inputSchema");
                    JSONObject annotations = t.optJSONObject("annotations");
                    list.add(new McpToolInfo(name, description,
                            schema != null ? schema : new JSONObject(), annotations));
                    if (list.size() >= maxTools) break;
                }
            }
            pages++;
            Object next = result.opt("nextCursor");
            cursor = (next == null || next == JSONObject.NULL) ? "" : String.valueOf(next);
            if (cursor != null && !cursor.isEmpty() && !seenCursors.add(cursor)) {
                AILogger.w(TAG, "MCP tools/list 游标重复，停止翻页: " + config.name);
                break;
            }
            if (list.size() >= maxTools) {
                AILogger.w(TAG, "MCP tools/list 达到上限 " + maxTools + "，忽略剩余页: " + config.name);
                break;
            }
        } while (cursor != null && !cursor.isEmpty() && pages < maxPages);
        if (pages >= maxPages && cursor != null && !cursor.isEmpty()) {
            AILogger.w(TAG, "MCP tools/list 达到页数上限 " + maxPages + ": " + config.name);
        }
        AILogger.i(TAG, "MCP tools/list: " + config.name + " -> " + list.size() + " tools, pages=" + pages);
        return list;
    }

    /** 执行远端工具（tools/call）。 */
    public McpCallResult callTool(String toolName, JSONObject arguments) throws Exception {
        ensureInitialized();
        JSONObject params = new JSONObject();
        params.put("name", toolName);
        if (arguments != null) params.put("arguments", arguments);
        JSONObject resp = send(McpConstants.METHOD_TOOLS_CALL, params,
                McpConstants.CALL_TIMEOUT_MS);
        if (McpJsonRpc.isError(resp)) {
            return McpCallResult.fail(McpJsonRpc.errorMessage(resp));
        }
        JSONObject result = McpJsonRpc.result(resp);
        boolean isError = result.optBoolean("isError", false);
        String text = renderContent(result.optJSONArray("content"));
        // 2025-06-18 起服务端可只回 structuredContent（无 content）；没有文本时把它给模型
        if ("(无内容返回)".equals(text) && result.has("structuredContent")) {
            Object sc = result.opt("structuredContent");
            text = sc != null ? sc.toString() : text;
        }
        return new McpCallResult(isError, text);
    }

    /** 将 content 数组渲染为文本（text 拼接；image/audio/resource 摘要）。 */
    private String renderContent(JSONArray content) {
        if (content == null || content.length() == 0) return "(无内容返回)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject item = content.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type", "");
            if ("text".equals(type)) {
                sb.append(item.optString("text", ""));
            } else if ("image".equals(type)) {
                String mime = item.optString("mimeType", "image");
                String data = item.optString("data", "");
                sb.append("[图片 ").append(mime).append(" ").append(byteLength(data)).append(" 字节]");
            } else if ("audio".equals(type)) {
                String mime = item.optString("mimeType", "audio");
                String data = item.optString("data", "");
                sb.append("[音频 ").append(mime).append(" ").append(byteLength(data)).append(" 字节]");
            } else if ("resource".equals(type)) {
                JSONObject res = item.optJSONObject("resource");
                String uri = res != null ? res.optString("uri", "?") : "?";
                String text = res != null ? res.optString("text", "") : "";
                sb.append("[资源 ").append(uri).append("]");
                if (!text.isEmpty()) sb.append(text);
            } else {
                sb.append("[").append(type).append("]");
            }
        }
        String result = sb.toString();
        if (result.length() > McpConstants.RESULT_MAX_LENGTH) {
            result = result.substring(0, McpConstants.RESULT_MAX_LENGTH)
                    + "\n...(结果过长已截断)";
        }
        return result;
    }

    private static int byteLength(String data) {
        try {
            return data.getBytes("UTF-8").length;
        } catch (Exception e) {
            return data.length();
        }
    }

    /** 连通性检查。 */
    public void ping() throws Exception {
        ensureInitialized();
        JSONObject resp = send(McpConstants.METHOD_PING, null);
        if (McpJsonRpc.isError(resp)) {
            throw new java.io.IOException(McpJsonRpc.errorMessage(resp));
        }
    }

    // ==================== 底层 ====================

    private JSONObject send(String method, JSONObject params) throws Exception {
        return send(method, params, McpConstants.DEFAULT_TIMEOUT_MS);
    }

    private JSONObject send(String method, JSONObject params, long timeoutMs) throws Exception {
        long id = nextId.getAndIncrement();
        JSONObject request = McpJsonRpc.request(method, params, id);
        return transport.send(request, timeoutMs);
    }

    private void sendNotification(String method, JSONObject params) throws Exception {
        JSONObject notification = McpJsonRpc.notification(method, params);
        transport.send(notification, 10_000L); // 通知类给短超时
    }

    private void ensureInitialized() throws Exception {
        // SSE 长连接被服务端关掉后，transport.isConnected() 会变 false；此时必须重新 initialize，
        // 否则后续 tools/list / tools/call 会一直往死流里 POST。
        if (initialized && !transport.isConnected()) {
            initialized = false;
            AILogger.w(TAG, "MCP transport 已断开，重新初始化: " + config.id);
        }
        if (!initialized) {
            initialize();
        }
        if (!initialized) {
            throw new java.io.IOException("MCP client 未初始化: " + config.id);
        }
    }

    public void close() {
        try {
            transport.close();
        } catch (Exception ignored) {
        }
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /** 服务端 tools/list_changed 通知 → 由 McpManager 调度一次后台重同步。 */
    public void setToolsChangedListener(Runnable listener) {
        transport.setNotificationListener(listener);
    }

    public String getServerId() {
        return config.id;
    }

    public String getServerName() {
        return config.name;
    }

    // ==================== 数据类 ====================

    /** 远端工具元数据（名称 + 描述 + JSON Schema 参数定义 + 可选注解）。 */
    public static class McpToolInfo {
        public final String name;
        public final String description;
        public final JSONObject inputSchema;
        /** MCP 2025-06-18 annotations（readOnlyHint/destructiveHint 等）；旧服务端可能为 null */
        public final JSONObject annotations;

        public McpToolInfo(String name, String description, JSONObject inputSchema) {
            this(name, description, inputSchema, null);
        }

        public McpToolInfo(String name, String description, JSONObject inputSchema,
                           JSONObject annotations) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.annotations = annotations;
        }
    }

    /** 工具调用结果。 */
    public static class McpCallResult {
        public final boolean isError;
        public final String text;

        public McpCallResult(boolean isError, String text) {
            this.isError = isError;
            this.text = text;
        }

        public static McpCallResult fail(String message) {
            return new McpCallResult(true, message);
        }
    }
}
