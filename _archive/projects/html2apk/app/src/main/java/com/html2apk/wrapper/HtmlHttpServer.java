package com.html2apk.wrapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 极简本地 HTTP 服务：把 HTML 目录通过 http://localhost:port/ 提供给 WebView。
 *
 * - 支持 GET/HEAD，正确 Content-Type / Content-Length
 * - 路径穿越防护：规范化路径必须位于根目录内
 * - 线程池处理连接，stop() 关闭监听与连接
 */
public class HtmlHttpServer {

    private static final Map<String, String> MIME = new HashMap<>();

    static {
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("htm", "text/html; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("js", "application/javascript; charset=utf-8");
        MIME.put("mjs", "application/javascript; charset=utf-8");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("xml", "application/xml; charset=utf-8");
        MIME.put("txt", "text/plain; charset=utf-8");
        MIME.put("md", "text/markdown; charset=utf-8");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("gif", "image/gif");
        MIME.put("webp", "image/webp");
        MIME.put("svg", "image/svg+xml");
        MIME.put("ico", "image/x-icon");
        MIME.put("bmp", "image/bmp");
        MIME.put("avif", "image/avif");
        MIME.put("woff", "font/woff");
        MIME.put("woff2", "font/woff2");
        MIME.put("ttf", "font/ttf");
        MIME.put("otf", "font/otf");
        MIME.put("eot", "application/vnd.ms-fontobject");
        MIME.put("mp3", "audio/mpeg");
        MIME.put("wav", "audio/wav");
        MIME.put("ogg", "audio/ogg");
        MIME.put("m4a", "audio/mp4");
        MIME.put("mp4", "video/mp4");
        MIME.put("webm", "video/webm");
        MIME.put("pdf", "application/pdf");
        MIME.put("wasm", "application/wasm");
    }

    private final File root;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private Thread acceptThread;

    public HtmlHttpServer(File root, int port) {
        this.root = root;
        this.port = port;
    }

    public void start() throws IOException {
        if (running.getAndSet(true)) return;
        serverSocket = new ServerSocket(port);
        pool = Executors.newCachedThreadPool();
        acceptThread = new Thread(this::acceptLoop, "HtmlHttpServer-" + port);
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        if (!running.getAndSet(false)) return;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                pool.execute(() -> handle(socket));
            } catch (IOException e) {
                if (running.get()) {
                    // 短暂退避后继续
                    try { Thread.sleep(50); } catch (InterruptedException ignored) { }
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             BufferedInputStream in = new BufferedInputStream(s.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeResponse(out, 400, "text/plain; charset=utf-8", "Bad Request".getBytes());
                return;
            }
            String method = parts[0].toUpperCase(Locale.US);
            String rawPath = parts[1];
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                writeResponse(out, 405, "text/plain; charset=utf-8", "Method Not Allowed".getBytes());
                return;
            }
            // 去掉 query
            int q = rawPath.indexOf('?');
            String path = q >= 0 ? rawPath.substring(0, q) : rawPath;
            if (!path.startsWith("/")) path = "/" + path;

            File target = resolve(root, path);
            if (target == null) {
                writeResponse(out, 403, "text/plain; charset=utf-8", "Forbidden".getBytes());
                return;
            }
            if (target.isDirectory()) {
                target = new File(target, "index.html");
            }
            if (!target.isFile() || !target.canRead()) {
                writeResponse(out, 404, "text/plain; charset=utf-8", "Not Found".getBytes());
                return;
            }

            byte[] body = readFile(target);
            String mime = mimeOf(target.getName());
            writeResponse(out, 200, mime, body, "HEAD".equals(method));
        } catch (Exception ignored) {
            // 单个连接失败不影响服务
        }
    }

    /** 解析请求路径为根目录内文件；越界返回 null */
    private File resolve(File rootDir, String urlPath) {
        try {
            String decoded = URLDecoder.decode(urlPath, "UTF-8");
            File f = new File(rootDir, decoded);
            String canonical = f.getCanonicalPath();
            String rootCanonical = rootDir.getCanonicalPath();
            if (!canonical.startsWith(rootCanonical + File.separator) && !canonical.equals(rootCanonical)) {
                return null;
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    public static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
        return MIME.getOrDefault(ext, "application/octet-stream");
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            return fis.readAllBytes();
        }
    }

    private static String readLine(BufferedInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void writeResponse(OutputStream out, int code, String mime, byte[] body) throws IOException {
        writeResponse(out, code, mime, body, false);
    }

    private static void writeResponse(OutputStream out, int code, String mime, byte[] body, boolean headOnly) throws IOException {
        String status = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 403 ? "Forbidden"
                : code == 405 ? "Method Not Allowed" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + mime + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "Cache-Control: no-cache\r\n"
                + "\r\n";
        out.write(head.getBytes("UTF-8"));
        if (!headOnly) out.write(body);
        out.flush();
    }
}
