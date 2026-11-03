package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Streamable HTTP 传输的 JVM 级集成测试：不依赖手机/外部 server，CI 也能跑。
 * 用一个最小 HTTP/1.1 服务端模拟 MCP，覆盖 initialize → session → tools/list → tools/call。
 *
 * <p>不用 com.sun.net.httpserver：Android 单元测试的编译 classpath 是 android.jar，
 * 不含 jdk.httpserver 模块；这里只用 java.base 的 ServerSocket。</p>
 */
public class McpClientStreamableIntegrationTest {

    private static final String TOKEN = "unit-token";
    private static final String SESSION = "unit-session";

    @Test
    public void initializeListCallOverRealHttp() throws Exception {
        TestServer server = new TestServer();
        try {
            server.start();
            McpClient client = new McpClient(new McpServerConfig(
                    "unit", "Unit", "http://127.0.0.1:" + server.port + "/mcp",
                    McpServerConfig.Transport.STREAMABLE_HTTP, TOKEN, true, false));

            client.initialize();
            assertEquals("initialize 后应带上协商版本", "2025-06-18", server.protocolVersion);
            assertEquals("initialize 后应带上 session", SESSION, server.sessionId);

            List<McpClient.McpToolInfo> tools = client.listTools();
            assertEquals(1, tools.size());
            assertEquals("echo", tools.get(0).name);
            assertEquals("string", tools.get(0).inputSchema
                    .getJSONObject("properties").getJSONObject("text").getString("type"));

            JSONObject args = new JSONObject();
            args.put("text", "hi");
            McpClient.McpCallResult result = client.callTool("echo", args);
            assertTrue("应成功返回，实际: " + result.text, !result.isError);
            assertTrue("应回显文本，实际: " + result.text, result.text.contains("echo:hi"));
        } finally {
            server.close();
        }
    }

    @Test
    public void receivesListChangedOverGetSse() throws Exception {
        TestServer server = new TestServer();
        try {
            server.start();
            McpClient client = new McpClient(new McpServerConfig(
                    "unit", "Unit", "http://127.0.0.1:" + server.port + "/mcp",
                    McpServerConfig.Transport.STREAMABLE_HTTP, TOKEN, true, false));
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            client.setToolsChangedListener(latch::countDown);

            client.initialize();
            assertTrue("应通过 GET SSE 收到 tools/list_changed",
                    latch.await(3, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            server.close();
        }
    }

    /** 最小 HTTP/1.1 + MCP server；一次一个连接，响应后 close。 */
    private static class TestServer {
        ServerSocket socket;
        Thread thread;
        volatile int port;
        volatile String protocolVersion = "";
        volatile String sessionId = "";

        void start() throws Exception {
            socket = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
            port = socket.getLocalPort();
            thread = new Thread(this::acceptLoop, "mcp-test-server");
            thread.setDaemon(true);
            thread.start();
        }

        void close() throws Exception {
            if (socket != null) socket.close();
            if (thread != null) thread.join(1000);
        }

        private void acceptLoop() {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    handle(client);
                } catch (Exception ignored) {
                    // close() 打断 accept 时正常退出
                }
            }
        }

        private void handle(Socket client) throws Exception {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String requestLine = readLine(in);
            String httpMethod = requestLine != null && requestLine.contains(" ")
                    ? requestLine.substring(0, requestLine.indexOf(' ')) : "";
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(),
                            line.substring(colon + 1).trim());
                }
            }
            if (!("Bearer " + TOKEN).equals(headers.get("authorization"))) {
                respond(out, 401, "{}", null);
                return;
            }
            sessionId = headers.getOrDefault("mcp-session-id", "");
            protocolVersion = headers.getOrDefault("mcp-protocol-version", "");

            if ("GET".equalsIgnoreCase(httpMethod)) {
                // 独立 GET SSE 通知流：发一条 list_changed 后关闭
                String body = "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n\n";
                respondSse(out, body);
                return;
            }

            int len = 0;
            try {
                len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (Exception ignored) {
            }
            byte[] bodyBytes = new byte[len];
            int read = 0;
            while (read < len) {
                int n = in.read(bodyBytes, read, len - read);
                if (n < 0) break;
                read += n;
            }
            JSONObject req = new JSONObject(new String(bodyBytes, StandardCharsets.UTF_8));
            String method = req.optString("method");
            Object id = req.opt("id");

            if ("initialize".equals(method)) {
                JSONObject result = new JSONObject()
                        .put("protocolVersion", "2025-06-18")
                        .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                        .put("serverInfo", new JSONObject().put("name", "unit").put("version", "1"));
                respond(out, 200, new JSONObject().put("jsonrpc", "2.0").put("id", id)
                        .put("result", result).toString(), SESSION);
                return;
            }
            if ("notifications/initialized".equals(method)) {
                respond(out, 202, "", null);
                return;
            }
            if ("tools/list".equals(method)) {
                JSONObject tool = new JSONObject()
                        .put("name", "echo")
                        .put("description", "Echo text")
                        .put("inputSchema", new JSONObject()
                                .put("type", "object")
                                .put("properties", new JSONObject().put("text",
                                        new JSONObject().put("type", "string"))));
                JSONObject result = new JSONObject().put("tools", new JSONArray().put(tool));
                respond(out, 200, new JSONObject().put("jsonrpc", "2.0").put("id", id)
                        .put("result", result).toString(), null);
                return;
            }
            if ("tools/call".equals(method)) {
                JSONObject params = req.optJSONObject("params");
                String name = params != null ? params.optString("name") : "";
                JSONObject args = params != null ? params.optJSONObject("arguments") : null;
                String text = args != null ? args.optString("text", "") : "";
                JSONObject result;
                if ("echo".equals(name)) {
                    result = new JSONObject().put("content", new JSONArray().put(
                            new JSONObject().put("type", "text").put("text", "echo:" + text)));
                } else {
                    result = new JSONObject().put("isError", true).put("content",
                            new JSONArray().put(new JSONObject().put("type", "text")
                                    .put("text", "unknown")));
                }
                respond(out, 200, new JSONObject().put("jsonrpc", "2.0").put("id", id)
                        .put("result", result).toString(), null);
                return;
            }
            respond(out, 200, new JSONObject().put("jsonrpc", "2.0").put("id", id)
                    .put("error", new JSONObject().put("code", -32601)
                            .put("message", "method not found")).toString(), null);
        }

        private static void respond(OutputStream out, int status, String body, String session)
                throws Exception {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(status).append(" X\r\n");
            head.append("Content-Type: application/json; charset=utf-8\r\n");
            head.append("Content-Length: ").append(bytes.length).append("\r\n");
            if (session != null) head.append("Mcp-Session-Id: ").append(session).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        }

        private static void respondSse(OutputStream out, String body) throws Exception {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 200 OK\r\n");
            head.append("Content-Type: text/event-stream; charset=utf-8\r\n");
            head.append("Content-Length: ").append(bytes.length).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        }

        private static String readLine(InputStream in) throws Exception {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') break;
                if (c != '\r') buf.write(c);
            }
            if (c < 0 && buf.size() == 0) return null;
            return new String(buf.toByteArray(), StandardCharsets.US_ASCII);
        }
    }
}
