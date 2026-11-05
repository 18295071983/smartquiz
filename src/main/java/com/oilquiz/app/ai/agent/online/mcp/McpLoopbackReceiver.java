package com.oilquiz.app.ai.agent.online.mcp;

import com.oilquiz.app.util.AILogger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 环回回调接收器（RFC 8252 §7 原生 App 推荐的 loopback redirect）。
 *
 * <p>为什么需要它：自定义 scheme 不是所有服务商都收。实测（2026-11-04）
 * MaxCompute 的动态注册直接回 {@code invalid_redirect_uri: redirect_uri must be RFC 8252
 * loopback or match the trusted HTTPS callback policy}——它只认 {@code http://127.0.0.1:port/...}。
 * 手机上的浏览器把授权码重定向到本机环回端口，App 里这条临时监听接住即可，不需要公网回调地址。</p>
 *
 * <p>端口从绑定起就占着不释放，直到本次授权结束：先绑再取号、中间不关，避免别的进程抢占同一端口
 * 造成"注册时一个端口、监听时另一个端口"。</p>
 */
public final class McpLoopbackReceiver implements AutoCloseable {

    private static final String TAG = "McpLoopback";

    private final ServerSocket socket;
    private final int port;
    private volatile boolean stop;
    private volatile boolean delivered;
    private volatile String error;
    private Thread worker;

    private McpLoopbackReceiver(ServerSocket socket, int port) {
        this.socket = socket;
        this.port = port;
    }

    /**
     * 绑定一个环回端口并保持在听。
     *
     * @return 绑定好的接收器（还没开始收，调用 {@link #start} 才收）；失败返回 null
     */
    public static McpLoopbackReceiver open() {
        try {
            ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            ss.setSoTimeout(1000);
            AILogger.i(TAG, "环回回调端口已绑定: " + ss.getLocalPort());
            return new McpLoopbackReceiver(ss, ss.getLocalPort());
        } catch (Exception e) {
            AILogger.e(TAG, "绑定环回端口失败: " + e.getMessage());
            return null;
        }
    }

    public int port() {
        return port;
    }

    public String redirectUri() {
        return "http://127.0.0.1:" + port + "/callback";
    }

    /** 后台线程接一条带 code/error 的请求；其余请求（favicon、探活）回 404 继续等。 */
    public void start(final String expectedState) {
        worker = new Thread(() -> listen(expectedState), "mcp-oauth-loopback");
        worker.setDaemon(true);
        worker.start();
    }

    private void listen(String expectedState) {
        long deadline = System.currentTimeMillis() + 5 * 60_000L;
        while (!stop && System.currentTimeMillis() < deadline && !delivered) {
            try (Socket client = socket.accept()) {
                handle(client, expectedState);
            } catch (java.net.SocketTimeoutException timeoutTryAgain) {
                // soTimeout 到点：还没等到回调，继续循环（总超时到了会自己退出）
            } catch (Exception e) {
                if (!stop) {
                    error = e.getMessage();
                    AILogger.w(TAG, "环回监听中断: " + e.getMessage());
                }
                return;
            }
        }
        if (!delivered && error == null && !stop) {
            error = "5 分钟内没收到浏览器回传的授权码";
        }
    }

    private void handle(Socket client, String expectedState) throws Exception {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();
        String line = readLine(in);
        if (line == null) {
            respond(out, 400, "请求行读不到");
            return;
        }
        String[] parts = line.split(" ");
        if (parts.length < 2) {
            respond(out, 400, "请求行不合法");
            return;
        }
        String target = parts[1];
        Map<String, String> query = parseQuery(target);
        String state = query.get("state");
        if (state == null || !state.equals(expectedState)) {
            // state 对不上：不是这次授权的回调。回一个页面但绝不喂给等待方
            AILogger.w(TAG, "环回回调 state 不匹配，忽略: " + target);
            respond(out, 404, "无关请求");
            return;
        }
        String code = query.get("code");
        String err = query.get("error");
        if (code == null && err == null) {
            respond(out, 404, "无关请求");
            return;
        }
        boolean ok = McpOAuthPending.deliver(state, new McpOAuthPending.Result(
                code == null ? "" : code, err == null ? "" : err,
                query.getOrDefault("error_description", "")));
        delivered = ok;
        respond(out, 200, ok
                ? "<h3>授权已回传，可以关闭本页</h3><p>回到答题宝继续。</p>"
                : "<h3>授权码无效或已过期</h3><p>回到答题宝重试。</p>");
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
        }
        if (c == -1 && buf.size() == 0) return null;
        return new String(buf.toByteArray(), StandardCharsets.US_ASCII);
    }

    /** 简单解析 {@code /callback?code=x&state=y}；不依赖 Android。 */
    static Map<String, String> parseQuery(String target) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (target == null) return out;
        int q = target.indexOf('?');
        if (q < 0) return out;
        for (String pair : target.substring(q + 1).split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                out.put(java.net.URLDecoder.decode(k, "UTF-8"),
                        java.net.URLDecoder.decode(v, "UTF-8"));
            } catch (Exception e) {
                out.put(k, v);
            }
        }
        return out;
    }

    private void respond(OutputStream out, int status, String html) throws Exception {
        String body = "<html><body>" + html + "</body></html>";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Not Found")
                + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    /** 授权结束时可读到的失败原因（成功回传后为 null）。 */
    public String error() {
        return delivered ? null : error;
    }

    @Override
    public void close() {
        stop = true;
        try {
            socket.close();
        } catch (Exception ignored) {
        }
        if (worker != null) {
            worker.interrupt();
        }
    }
}
