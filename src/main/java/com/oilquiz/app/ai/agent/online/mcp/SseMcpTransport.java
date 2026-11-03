package com.oilquiz.app.ai.agent.online.mcp;

import com.oilquiz.app.ai.util.NetworkUtil;
import com.oilquiz.app.util.AILogger;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 经典 SSE 传输实现。
 *
 * <p>连接流程：GET {@code url}（事件流端点）建立长连接，后台线程持续读流；
 * 服务端先发 {@code endpoint} 事件告知消息端点（可能为相对路径），
 * 客户端随后把 JSON-RPC 请求 POST 到该端点，响应经 SSE 流以 {@code message} 事件回传。
 * 会话内以请求 id 关联响应。</p>
 *
 * <p>线程安全：send 可并发；每个请求 id 独立挂起等待，SSE 读取线程按 id 完成。</p>
 */
public class SseMcpTransport implements McpTransport {

    private static final String TAG = "SseMcpTransport";

    private final String sseUrl;
    private final String token;
    /** 独立 OkHttpClient：SSE 是长连接，不能占用共享连接池里的连接 */
    private final OkHttpClient client;

    /** 挂起的请求：requestId → future */
    private final Map<Long, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();

    private volatile Response sseResponse;
    private volatile Thread readerThread;
    private volatile String messageEndpoint;
    private volatile boolean closed;
    /** 工具列表变更通知（服务端主动推） */
    private volatile Runnable notificationListener;

    public SseMcpTransport(String sseUrl, String token) {
        this.sseUrl = sseUrl;
        this.token = token == null ? "" : token;
        this.client = NetworkUtil.getClient().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接：读超时交给上层
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public synchronized void connect() throws Exception {
        if (closed) throw new IllegalStateException("transport 已关闭");
        if (readerThread != null && readerThread.isAlive()) return; // 幂等

        Request.Builder rb = new Request.Builder().url(sseUrl).get();
        if (!token.isEmpty()) {
            rb.header(McpConstants.HEADER_AUTHORIZATION, "Bearer " + token);
        }
        // 兜底 message 端点：url 去尾 /sse 后 + /message
        String base = sseUrl;
        if (base.endsWith("/sse")) {
            base = base.substring(0, base.length() - 4);
        }
        this.messageEndpoint = defaultMessageEndpoint(base);

        Response resp = client.newCall(rb.build()).execute();
        if (!resp.isSuccessful()) {
            resp.close();
            throw new java.io.IOException("SSE 连接失败: HTTP " + resp.code());
        }
        this.sseResponse = resp;
        Thread t = new Thread(this::readLoop, "Mcp-Sse-Reader");
        t.setDaemon(true);
        this.readerThread = t;
        t.start();
        AILogger.i(TAG, "SSE connected: " + McpServerConfig.maskUrl(sseUrl));
    }

    /** 默认消息端点：{base}/message */
    private static String defaultMessageEndpoint(String base) {
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/message";
    }

    /** SSE 读取循环：解析 event:/data: 行。 */
    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(sseResponse.body().byteStream(), StandardCharsets.UTF_8))) {
            String line;
            StringBuilder data = new StringBuilder();
            String event = "";
            while (!closed && (line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                } else if (line.isEmpty()) {
                    if (data.length() > 0) {
                        handleEvent(event, data.toString());
                    }
                    event = "";
                    data.setLength(0);
                }
            }
        } catch (Exception e) {
            if (!closed) AILogger.w(TAG, "SSE read loop ended: " + e.getMessage());
        } finally {
            // 流结束：所有挂起请求按失败结束（避免 send 永久等待）
            if (!closed) {
                AILogger.w(TAG, "SSE stream closed by server, failing pending: " + pending.size());
                completeAllPending(new java.io.IOException("SSE 流已断开"));
            }
        }
    }

    private void handleEvent(String event, String data) {
        try {
            if (McpConstants.SSE_EVENT_ENDPOINT.equals(event)) {
                String ep = data.trim();
                if (!ep.isEmpty()) {
                    // 相对路径（如 /mcp/message）拼到端点 origin；完整 URL 直接用
                    if (ep.startsWith("http://") || ep.startsWith("https://")) {
                        this.messageEndpoint = ep;
                    } else {
                        this.messageEndpoint = resolveRelative(sseUrl, ep);
                    }
                    AILogger.d(TAG, "SSE endpoint -> " + this.messageEndpoint);
                }
            } else if (McpConstants.SSE_EVENT_MESSAGE.equals(event)) {
                JSONObject msg = new JSONObject(data);
                if (msg.has("id")) {
                    CompletableFuture<JSONObject> f = pending.remove(msg.optLong("id", -1));
                    if (f != null) f.complete(msg);
                } else if (McpConstants.NOTIFICATION_TOOLS_CHANGED.equals(msg.optString("method"))) {
                    Runnable r = notificationListener;
                    if (r != null) {
                        try {
                            r.run();
                        } catch (Exception e) {
                            AILogger.w(TAG, "notification listener failed: " + e.getMessage());
                        }
                    }
                }
            }
            // 其他事件（ping/keep-alive 等）忽略
        } catch (Exception e) {
            AILogger.w(TAG, "handleEvent failed: " + e.getMessage());
        }
    }

    /** 相对路径解析：以 sseUrl 的目录为基础。 */
    private static String resolveRelative(String baseUrl, String rel) {
        try {
            URI base = new URI(baseUrl);
            String dir = base.getPath();
            int slash = dir.lastIndexOf('/');
            String prefix = dir.substring(0, slash + 1);
            if (!rel.startsWith("/") && !prefix.endsWith("/")) rel = "/" + rel;
            URI resolved = base.resolve(rel.startsWith("/") ? rel : prefix + rel);
            return resolved.toString();
        } catch (Exception e) {
            return rel;
        }
    }

    @Override
    public JSONObject send(JSONObject request, long timeoutMs) throws Exception {
        connect(); // 未连接则先建流
        final long id = request.optLong("id", -1);
        if (id < 0) {
            // 通知类消息（无 id）：POST 后不等待响应
            post(request);
            return null;
        }
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            post(request);
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw e;
        } catch (Exception e) {
            pending.remove(id);
            throw e;
        }
    }

    private void post(JSONObject request) throws Exception {
        RequestBody body = RequestBody.create(
                request.toString(), MediaType.parse("application/json; charset=utf-8"));
        Request.Builder rb = new Request.Builder()
                .url(messageEndpoint)
                .header("Content-Type", "application/json")
                .post(body);
        if (!token.isEmpty()) {
            rb.header(McpConstants.HEADER_AUTHORIZATION, "Bearer " + token);
        }
        try (Response resp = client.newCall(rb.build()).execute()) {
            if (!resp.isSuccessful()) {
                throw new java.io.IOException("POST 失败: HTTP " + resp.code());
            }
            // 部分实现会直接回 JSON 而不是走 SSE：读一遍响应体碰运气
            String bodyStr = resp.body() != null ? resp.body().string() : "";
            if (!bodyStr.isEmpty()) {
                try {
                    JSONObject direct = new JSONObject(bodyStr);
                    if (direct.has("id") && direct.optLong("id", -1) == request.optLong("id", -1)) {
                        CompletableFuture<JSONObject> f = pending.remove(request.optLong("id", -1));
                        if (f != null) f.complete(direct);
                    }
                } catch (Exception ignored) {
                    // 非 JSON 响应体（如空 202），忽略
                }
            }
        }
    }

    private void completeAllPending(Exception error) {
        for (CompletableFuture<JSONObject> f : pending.values()) {
            f.completeExceptionally(error);
        }
        pending.clear();
    }

    @Override
    public void setNotificationListener(Runnable listener) {
        this.notificationListener = listener;
    }

    @Override
    public void close() {
        closed = true;
        try {
            if (sseResponse != null) sseResponse.close();
        } catch (Exception ignored) {
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
        completeAllPending(new java.io.IOException("transport 已关闭"));
    }

    @Override
    public boolean isConnected() {
        return readerThread != null && readerThread.isAlive() && !closed;
    }
}
