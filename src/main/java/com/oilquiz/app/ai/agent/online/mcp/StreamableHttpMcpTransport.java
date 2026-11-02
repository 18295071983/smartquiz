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

    private volatile String sessionId;
    private volatile boolean closed;

    public StreamableHttpMcpTransport(String endpoint, String token) {
        this.endpoint = endpoint;
        this.token = token == null ? "" : token;
        this.client = NetworkUtil.getClient().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
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

        long start = System.currentTimeMillis();
        try (Response resp = client.newCall(rb.build()).execute()) {
            if (!resp.isSuccessful()) {
                throw new java.io.IOException("MCP 请求失败: HTTP " + resp.code());
            }
            // 会话 id 可能在本轮下发，也可能不带（无状态服务）
            String newSession = resp.header(McpConstants.HEADER_MCP_SESSION_ID);
            if (newSession != null && !newSession.isEmpty()) {
                this.sessionId = newSession;
            }

            if (isNotification) {
                return null; // 通知不需要响应
            }

            String contentType = resp.header("Content-Type", "");
            long remaining = timeoutMs - (System.currentTimeMillis() - start);
            if (remaining <= 0) throw new java.util.concurrent.TimeoutException("MCP 请求超时");

            if (contentType.toLowerCase().contains("text/event-stream")) {
                return readSseResponse(resp, id, remaining);
            }
            // 单 JSON 响应
            String bodyStr = resp.body() != null ? resp.body().string() : "";
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
                    data.append(d);
                    try {
                        JSONObject msg = new JSONObject(d);
                        if (McpJsonRpc.matchesId(msg, id)) {
                            return msg;
                        }
                    } catch (Exception ignored) {
                        // 非 JSON 数据行（keep-alive 等）
                    }
                } else if (line.isEmpty() && data.length() > 0) {
                    try {
                        JSONObject msg = new JSONObject(data.toString());
                        if (McpJsonRpc.matchesId(msg, id)) {
                            return msg;
                        }
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

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public boolean isConnected() {
        return !closed;
    }
}
