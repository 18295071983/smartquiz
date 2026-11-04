package com.oilquiz.app.ai.agent.online.mcp;

import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /** initialize 响应里的协议版本（市场页"测一下"要展示真实协商结果，而不是我们发出去的那版） */
    private volatile String negotiatedProtocolVersion = "";
    /** initialize 响应里的 serverInfo 摘要（name/version） */
    private volatile String serverInfoText = "";
    /**
     * initialize 响应里的 capabilities。服务端只声明自己支持什么，客户端才该去要——
     * 对没实现 resources 的 server 发 {@code resources/list} 得到的是方法不存在错误，
     * 目录里就会多出一段"永远失败的刷新"。
     */
    private volatile JSONObject capabilities = new JSONObject();

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
            negotiatedProtocolVersion = result.optString("protocolVersion", "");
            JSONObject caps = result.optJSONObject("capabilities");
            capabilities = caps != null ? caps : new JSONObject();
            JSONObject si = result.optJSONObject("serverInfo");
            serverInfoText = si == null ? ""
                    : si.optString("name", "") + " " + si.optString("version", "");
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

    // ==================== 资源与方法（resources / prompts） ====================

    /** 服务端 initialize 时是否声明支持某个能力。 */
    public boolean supports(String capability) {
        return capabilities != null && capabilities.has(capability);
    }

    public boolean supportsResources() {
        return supports("resources");
    }

    public boolean supportsPrompts() {
        return supports("prompts");
    }

    /** 服务端自报的能力表（管理页与 mcp_toolbox 展示"这家到底给什么"用）。 */
    public JSONObject getCapabilities() {
        return capabilities != null ? capabilities : new JSONObject();
    }

    /**
     * 按游标翻完一个列表方法，返回原始条目数组。
     *
     * <p>与 {@link #listTools()} 同一套保护：页数上限、条目上限、重复游标即停——
     * 异常服务端回同一个 {@code nextCursor} 时不能把同步线程挂死。</p>
     */
    private List<JSONObject> pagedItems(String method, String field, int maxItems, String label)
            throws Exception {
        ensureInitialized();
        List<JSONObject> out = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        int pages = 0;
        final int maxPages = 20;
        do {
            JSONObject params = new JSONObject();
            if (cursor != null && !cursor.isEmpty()) params.put("cursor", cursor);
            JSONObject resp = send(method, params);
            if (McpJsonRpc.isError(resp)) {
                throw new java.io.IOException(McpJsonRpc.errorMessage(resp));
            }
            JSONObject result = McpJsonRpc.result(resp);
            JSONArray items = result.optJSONArray(field);
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.optJSONObject(i);
                    if (o != null) out.add(o);
                    if (out.size() >= maxItems) break;
                }
            }
            pages++;
            Object next = result.opt("nextCursor");
            cursor = (next == null || next == JSONObject.NULL) ? "" : String.valueOf(next);
            if (cursor != null && !cursor.isEmpty() && !seenCursors.add(cursor)) {
                AILogger.w(TAG, "MCP " + method + " 游标重复，停止翻页: " + config.name);
                break;
            }
            if (out.size() >= maxItems) {
                AILogger.w(TAG, "MCP " + label + " 达到上限 " + maxItems + "，忽略剩余页: " + config.name);
                break;
            }
        } while (cursor != null && !cursor.isEmpty() && pages < maxPages);
        return out;
    }

    /** 枚举资源（resources/list）。未声明支持时返回空表而不是报错。 */
    public List<McpResourceInfo> listResources() throws Exception {
        if (!supportsResources()) return new ArrayList<>();
        List<McpResourceInfo> out = new ArrayList<>();
        for (JSONObject o : pagedItems(McpConstants.METHOD_RESOURCES_LIST, "resources",
                McpConstants.MAX_CATALOG_ITEMS, "resources")) {
            String uri = o.optString("uri");
            if (uri.isEmpty()) continue;
            out.add(new McpResourceInfo(uri, o.optString("name", uri),
                    o.optString("description", ""), o.optString("mimeType", "")));
        }
        return out;
    }

    /** 枚举资源模板（{@code resources/templates/list}，URI 模式而非具体条目）。 */
    public List<McpResourceInfo> listResourceTemplates() throws Exception {
        if (!supportsResources()) return new ArrayList<>();
        List<McpResourceInfo> out = new ArrayList<>();
        for (JSONObject o : pagedItems(McpConstants.METHOD_RESOURCES_TEMPLATES_LIST,
                "resourceTemplates", McpConstants.MAX_CATALOG_ITEMS, "resource templates")) {
            String uriTemplate = o.optString("uriTemplate", o.optString("uri", ""));
            if (uriTemplate.isEmpty()) continue;
            out.add(new McpResourceInfo(uriTemplate, o.optString("name", uriTemplate),
                    o.optString("description", ""), o.optString("mimeType", "")));
        }
        return out;
    }

    /** 读一个资源（resources/read）。返回渲染后的文本，错误走 {@link McpCallResult#isError}。 */
    public McpCallResult readResource(String uri) throws Exception {
        ensureInitialized();
        JSONObject params = new JSONObject();
        params.put("uri", uri);
        JSONObject resp = send(McpConstants.METHOD_RESOURCES_READ, params,
                McpConstants.CALL_TIMEOUT_MS);
        if (McpJsonRpc.isError(resp)) {
            return McpCallResult.fail(McpJsonRpc.errorMessage(resp));
        }
        JSONObject result = McpJsonRpc.result(resp);
        return new McpCallResult(false, renderContents(result.optJSONArray("contents")));
    }

    /** 枚举提示模板（prompts/list）。arguments 原样保留，模型要照它填。 */
    public List<McpPromptInfo> listPrompts() throws Exception {
        if (!supportsPrompts()) return new ArrayList<>();
        List<McpPromptInfo> out = new ArrayList<>();
        for (JSONObject o : pagedItems(McpConstants.METHOD_PROMPTS_LIST, "prompts",
                McpConstants.MAX_CATALOG_ITEMS, "prompts")) {
            String name = o.optString("name");
            if (name.isEmpty()) continue;
            out.add(new McpPromptInfo(name, o.optString("description", ""),
                    o.optJSONArray("arguments")));
        }
        return out;
    }

    /** 取一个提示模板的具体消息（prompts/get）。 */
    public McpCallResult getPrompt(String name, JSONObject arguments) throws Exception {
        ensureInitialized();
        JSONObject params = new JSONObject();
        params.put("name", name);
        if (arguments != null) params.put("arguments", arguments);
        JSONObject resp = send(McpConstants.METHOD_PROMPTS_GET, params,
                McpConstants.CALL_TIMEOUT_MS);
        if (McpJsonRpc.isError(resp)) {
            return McpCallResult.fail(McpJsonRpc.errorMessage(resp));
        }
        JSONObject result = McpJsonRpc.result(resp);
        JSONArray messages = result.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            return new McpCallResult(false, "(该提示没有返回消息)");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.optJSONObject(i);
            if (msg == null) continue;
            sb.append("[").append(msg.optString("role", "?")).append("] ");
            JSONObject content = msg.optJSONObject("content");
            if (content != null && "text".equals(content.optString("type", "text"))) {
                sb.append(content.optString("text", ""));
            } else {
                sb.append(renderContents(new JSONArray().put(msg.opt("content"))));
            }
            sb.append("\n");
        }
        String text = sb.toString();
        if (text.length() > McpConstants.RESULT_MAX_LENGTH) {
            text = text.substring(0, McpConstants.RESULT_MAX_LENGTH) + "\n...(结果过长已截断)";
        }
        return new McpCallResult(false, text);
    }

    /**
     * 渲染 {@code contents[]}（resources/read 的形状）与 {@code content[]} 略有差别：
     * 前者每项自带 uri/mimeType，text 或 blob 二选一。blob 不当文本用，只报大小。
     */
    private String renderContents(JSONArray contents) {
        if (contents == null || contents.length() == 0) return "(无内容返回)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contents.length(); i++) {
            JSONObject item = contents.optJSONObject(i);
            if (item == null) continue;
            String uri = item.optString("uri", "");
            String text = item.optString("text", null);
            if (text != null && !text.isEmpty()) {
                if (!uri.isEmpty()) sb.append("[").append(uri).append("]\n");
                sb.append(text);
            } else {
                String blob = item.optString("blob", "");
                sb.append("[").append(uri.isEmpty() ? "资源" : uri).append(" 二进制 ")
                  .append(byteLength(blob)).append(" 字节，未内联文本]");
            }
            if (i < contents.length() - 1) sb.append("\n");
        }
        String out = sb.toString();
        if (out.length() > McpConstants.RESULT_MAX_LENGTH) {
            out = out.substring(0, McpConstants.RESULT_MAX_LENGTH) + "\n...(结果过长已截断)";
        }
        return out;
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

    /** initialize 协商到的协议版本；未初始化返回空串。 */
    public String getNegotiatedProtocolVersion() {
        return negotiatedProtocolVersion;
    }

    /** 服务端自报名称/版本摘要；未初始化返回空串。 */
    public String getServerInfoText() {
        return serverInfoText;
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

    /** 资源条目：{@code resources/list} 里的一条，或 {@code resources/templates/list} 的一个 URI 模式。 */
    public static class McpResourceInfo {
        public final String uri;
        public final String name;
        public final String description;
        public final String mimeType;

        public McpResourceInfo(String uri, String name, String description, String mimeType) {
            this.uri = uri == null ? "" : uri;
            this.name = name == null ? this.uri : name;
            this.description = description == null ? "" : description;
            this.mimeType = mimeType == null ? "" : mimeType;
        }

        /** 模板条目的 uri 里带 {@code {…}}，UI 与模型提示要按"需要填参数"来说。 */
        public boolean isTemplate() {
            return uri.contains("{") && uri.contains("}");
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uri", uri);
            m.put("name", name);
            if (!description.isEmpty()) m.put("description", description);
            if (!mimeType.isEmpty()) m.put("mime_type", mimeType);
            if (isTemplate()) m.put("needs_argument", true);
            return m;
        }
    }

    /** 提示模板条目：{@code prompts/list} 里的一条，arguments 是给模型看的填法说明。 */
    public static class McpPromptInfo {
        public final String name;
        public final String description;
        public final JSONArray arguments;

        public McpPromptInfo(String name, String description, JSONArray arguments) {
            this.name = name == null ? "" : name;
            this.description = description == null ? "" : description;
            this.arguments = arguments != null ? arguments : new JSONArray();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            if (!description.isEmpty()) m.put("description", description);
            m.put("arguments", arguments.length());
            return m;
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
