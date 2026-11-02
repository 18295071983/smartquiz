package com.oilquiz.app.ai.agent.online.mcp;

import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
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

            // initialized 通知（无 id）
            sendNotification(McpConstants.METHOD_NOTIFICATION_INITIALIZED, null);
            initialized = true;
        } catch (Exception e) {
            AILogger.e(TAG, "initialize failed (" + config.id + "): " + e.getMessage(), e);
            throw e;
        }
    }

    // ==================== 工具 ====================

    /** 枚举远端工具（tools/list）。 */
    public List<McpToolInfo> listTools() throws Exception {
        ensureInitialized();
        JSONObject resp = send(McpConstants.METHOD_TOOLS_LIST, new JSONObject());
        if (McpJsonRpc.isError(resp)) {
            throw new java.io.IOException(McpJsonRpc.errorMessage(resp));
        }
        JSONObject result = McpJsonRpc.result(resp);
        JSONArray tools = result.optJSONArray("tools");
        List<McpToolInfo> list = new ArrayList<>();
        if (tools != null) {
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                if (t == null) continue;
                String name = t.optString("name");
                if (name.isEmpty()) continue;
                String description = t.optString("description", "");
                JSONObject schema = t.optJSONObject("inputSchema");
                list.add(new McpToolInfo(name, description, schema != null ? schema : new JSONObject()));
            }
        }
        AILogger.i(TAG, "MCP tools/list: " + config.name + " -> " + list.size() + " tools");
        return list;
    }

    /** 执行远端工具（tools/call）。 */
    public McpCallResult callTool(String toolName, JSONObject arguments) throws Exception {
        ensureInitialized();
        JSONObject params = new JSONObject();
        params.put("name", toolName);
        if (arguments != null) params.put("arguments", arguments);
        JSONObject resp = send(McpConstants.METHOD_TOOLS_CALL, params);
        if (McpJsonRpc.isError(resp)) {
            return McpCallResult.fail(McpJsonRpc.errorMessage(resp));
        }
        JSONObject result = McpJsonRpc.result(resp);
        boolean isError = result.optBoolean("isError", false);
        String text = renderContent(result.optJSONArray("content"));
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
        long id = nextId.getAndIncrement();
        JSONObject request = McpJsonRpc.request(method, params, id);
        return transport.send(request, McpConstants.DEFAULT_TIMEOUT_MS);
    }

    private void sendNotification(String method, JSONObject params) throws Exception {
        JSONObject notification = McpJsonRpc.notification(method, params);
        transport.send(notification, 10_000L); // 通知类给短超时
    }

    private void ensureInitialized() throws Exception {
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

    public String getServerId() {
        return config.id;
    }

    public String getServerName() {
        return config.name;
    }

    // ==================== 数据类 ====================

    /** 远端工具元数据（名称 + 描述 + JSON Schema 参数定义）。 */
    public static class McpToolInfo {
        public final String name;
        public final String description;
        public final JSONObject inputSchema;

        public McpToolInfo(String name, String description, JSONObject inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
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
