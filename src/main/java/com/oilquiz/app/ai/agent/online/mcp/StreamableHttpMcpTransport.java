package com.oilquiz.app.ai.agent.online.mcp;

import com.oilquiz.app.ai.util.NetworkUtil;
import com.oilquiz.app.util.AILogger;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * streamable HTTP 传输实现（协议版本 2025-03-26+）。
 *
 * <p>客户端把 JSON-RPC 请求直接 POST 到端点，服务端可能返回：</p>
 * <ul>
 *   <li>单个 JSON 响应（{@code application/json}）</li>
 *   <li>SSE 事件流（{@code text/event-stream}），响应藏在 {@code message} 事件中，流尾 {@code [DONE]}</li>
 * </ul>
 *
 * <p>会话复用：服务端通过 {@code Mcp-Session-Id} 响应头下发会话，后续请求须回带该 header。
 * 无持久连接，connect 为空操作；鉴权通过 {@code Authorization: Bearer}。</p>
 */
public class StreamableHttpMcpTransport implements McpTransport {

    private static final String TAG = "StreamableHttpMcpTransport";

    private final String endpoint;
    private final String token;
    private final OkHttpClient client;
    /** 独立 client：GET 通知流是长连接，不能占用共享连接池的读超时 */
    private final OkHttpClient notifyClient;

    private volatile String sessionId;
    private volatile String protocolVersion;
    private volatile boolean closed;
    private volatile Runnable notificationListener;
    private volatile Thread notificationThread;
    private volatile Response notificationResponse;
    private volatile boolean notificationsStarted;
    private volatile String lastEventId = "";
    private final Object notificationLock = new Object();

    public StreamableHttpMcpTransport(String endpoint, String token) {
        this.endpoint = endpoint;
        this.token = token == null ? "" : token;
        this.client = NetworkUtil.getClient().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        this.notifyClient = NetworkUtil.getClient().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void connect() throws Exception {
        if (closed) throw new IllegalStateException("transport 已关闭");
        // streamable HTTP 无持久连接：connect 仅为语义占位
    }

    @Override
    public JSONObject send(JSONObject request, long timeoutMs) throws Exception {
        connect();
        final long id = request.optLong("id", -1);
        boolean isNotification = id < 0;

        RequestBody body = RequestBody.create(
                request.toString(), MediaType.parse("application/json; charset=utf-8"));
        Request.Builder rb = new Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .post(body);
        if (!token.isEmpty()) {
            rb.header(McpConstants.HEADER_AUTHORIZATION, "Bearer " + token);
        }
        String sid = sessionId;
        if (sid != null && !sid.isEmpty()) {
            rb.header(McpConstants.HEADER_MCP_SESSION_ID, sid);
        }
        String pv = protocolVersion;
        if (pv != null && !pv.isEmpty()) {
            rb.header(McpConstants.HEADER_MCP_PROTOCOL_VERSION, pv);
        }

        long start = System.currentTimeMillis();
        okhttp3.Call call = client.newCall(rb.build());
        // 外层声明的超时要真正约束到 socket 读，而不只是响应头之后的判断
        call.timeout().timeout(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        Response resp;
        try {
            resp = call.execute();
            // 会话过期：服务端通常回 404。清掉 session 重试一次（无状态服务/新会话可恢复）。
            if (resp.code() == 404 && sid != null && !sid.isEmpty()) {
                resp.close();
                this.sessionId = null;
                rb.removeHeader(McpConstants.HEADER_MCP_SESSION_ID);
                AILogger.w(TAG, "MCP session 已失效，清空后重试: " + endpoint);
                call = client.newCall(rb.build());
                call.timeout().timeout(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
                resp = call.execute();
            }
        } catch (java.io.InterruptedIOException e) {
            throw new java.util.concurrent.TimeoutException("MCP 请求超时（" + timeoutMs + "ms）");
        }
        try (Response r = resp) {
            if (!r.isSuccessful()) {
                McpAuthRequiredException auth = McpAuthRequiredException.fromFailure(
                        endpoint, r.code(), r.header("WWW-Authenticate"));
                if (auth != null) throw auth;
                throw new java.io.IOException("MCP 请求失败: HTTP " + r.code());
            }
            // 会话 id 可能在本轮下发，也可能不带（无状态服务）
            String newSession = r.header(McpConstants.HEADER_MCP_SESSION_ID);
            if (newSession != null && !newSession.isEmpty()) {
                this.sessionId = newSession;
            }

            if (isNotification) {
                return null; // 通知不需要响应
            }

            String contentType = r.header("Content-Type", "");
            long remaining = timeoutMs - (System.currentTimeMillis() - start);
            if (remaining <= 0) throw new java.util.concurrent.TimeoutException("MCP 请求超时");

            if (contentType.toLowerCase().contains("text/event-stream")) {
                return readSseResponse(r, id, remaining);
            }
            // 单 JSON 响应
            String bodyStr = r.body() != null ? r.body().string() : "";
            if (bodyStr.isEmpty()) {
                throw new java.io.IOException("MCP 空响应");
            }
            return new JSONObject(bodyStr);
        }
    }

    /** 从 SSE 流中读取与 id 匹配的 JSON-RPC 响应（读到即断开，不等 [DONE]）。 */
    private JSONObject readSseResponse(Response resp, long id, long timeoutMs) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body().byteStream(), StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + timeoutMs;
        StringBuilder data = new StringBuilder();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (System.currentTimeMillis() > deadline) {
                    throw new java.util.concurrent.TimeoutException("MCP 响应流超时");
                }
                if (line.startsWith("data:")) {
                    String d = line.substring(5).trim();
                    if (McpConstants.SSE_DATA_DONE.equals(d)) break; // 流尾
                    data.append(d).append('\n');
                    try {
                        JSONObject msg = new JSONObject(data.toString().trim());
                        if (McpJsonRpc.matchesId(msg, id)) {
                            return msg;
                        }
                        notifyIfToolsChanged(msg);
                    } catch (Exception ignored) {
                        // 非 JSON 数据行（keep-alive 等）
                    }
                } else if (line.isEmpty() && data.length() > 0) {
                    try {
                        JSONObject msg = new JSONObject(data.toString().trim());
                        if (McpJsonRpc.matchesId(msg, id)) {
                            return msg;
                        }
                        notifyIfToolsChanged(msg);
                    } catch (Exception ignored) {
                    }
                    data.setLength(0);
                }
            }
            throw new java.io.IOException("MCP 响应流未返回 id=" + id + " 的响应");
        } finally {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyIfToolsChanged(JSONObject msg) {
        if (msg == null || msg.has("id")) return;
        if (!McpConstants.NOTIFICATION_TOOLS_CHANGED.equals(msg.optString("method"))) return;
        Runnable r = notificationListener;
        if (r != null) {
            try {
                r.run();
            } catch (Exception e) {
                AILogger.w(TAG, "notification listener failed: " + e.getMessage());
            }
        }
    }

    @Override
    public void startNotifications() {
        synchronized (notificationLock) {
            if (closed || notificationsStarted) return;
            notificationsStarted = true;
            Thread t = new Thread(this::notificationLoop, "Mcp-Notify");
            t.setDaemon(true);
            notificationThread = t;
            t.start();
        }
    }

    /**
     * GET SSE 通知流：streamable HTTP 的服务端主动通知（如 tools/list_changed）从这里来。
     * 服务端不支持时返回 405/404，静默停止；断开后按 1s→30s 退避重连。
     */
    private void notificationLoop() {
        long backoffMs = 1000L;
        while (!closed) {
            Response resp = null;
            try {
                Request.Builder rb = new Request.Builder()
                        .url(endpoint)
                        .get()
                        .header("Accept", "text/event-stream");
                if (!token.isEmpty()) {
                    rb.header(McpConstants.HEADER_AUTHORIZATION, "Bearer " + token);
                }
                String sid = sessionId;
                if (sid != null && !sid.isEmpty()) {
                    rb.header(McpConstants.HEADER_MCP_SESSION_ID, sid);
                }
                String pv = protocolVersion;
                if (pv != null && !pv.isEmpty()) {
                    rb.header(McpConstants.HEADER_MCP_PROTOCOL_VERSION, pv);
                }
                String leid = lastEventId;
                if (leid != null && !leid.isEmpty()) {
                    rb.header("Last-Event-ID", leid);
                }

                resp = notifyClient.newCall(rb.build()).execute();
                notificationResponse = resp;
                if (resp.code() == 405 || resp.code() == 404) {
                    AILogger.i(TAG, "MCP 通知流不受支持（HTTP " + resp.code() + "），停止: "
                            + McpServerConfig.maskUrl(endpoint));
                    return;
                }
                if (!resp.isSuccessful()) {
                    AILogger.w(TAG, "MCP 通知流失败: HTTP " + resp.code());
                } else {
                    String ct = resp.header("Content-Type", "");
                    if (ct == null || !ct.toLowerCase().contains("text/event-stream")) {
                        AILogger.i(TAG, "MCP 通知流非 event-stream，停止: " + ct);
                        return;
                    }
                    backoffMs = 1000L;
                    readNotificationStream(resp);
                }
            } catch (Exception e) {
                if (!closed) AILogger.w(TAG, "MCP 通知流断开: " + e.getMessage());
            } finally {
                notificationResponse = null;
                if (resp != null) {
                    try {
                        resp.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            if (closed) return;
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoffMs = Math.min(backoffMs * 2, 30_000L);
        }
    }

    private void readNotificationStream(Response resp) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body().byteStream(), StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String event = "";
        try {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String d = line.substring(5).trim();
                    if (McpConstants.SSE_DATA_DONE.equals(d)) return;
                    data.append(d).append('\n');
                } else if (line.startsWith("id:")) {
                    String id = line.substring(3).trim();
                    if (!id.isEmpty()) lastEventId = id;
                } else if (line.isEmpty() && data.length() > 0) {
                    try {
                        JSONObject msg = new JSONObject(data.toString().trim());
                        // 服务端请求（带 id）本客户端不支持 sampling/elicitation，忽略；
                        // 无 id 的通知按方法分发。
                        if (msg.has("method") && !msg.has("id")) {
                            notifyIfToolsChanged(msg);
                        }
                    } catch (Exception ignored) {
                    }
                    data.setLength(0);
                    event = "";
                }
            }
        } finally {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void setNotificationListener(Runnable listener) {
        this.notificationListener = listener;
    }

    @Override
    public void setProtocolVersion(String version) {
        this.protocolVersion = version;
    }

    @Override
    public void close() {
        closed = true;
        Response r = notificationResponse;
        if (r != null) {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
        Thread t = notificationThread;
        if (t != null) t.interrupt();
    }

    @Override
    public boolean isConnected() {
        return !closed;
    }
}
