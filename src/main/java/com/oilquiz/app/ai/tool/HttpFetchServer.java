package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Base64;

import com.oilquiz.app.util.AILogger;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 本地回环下载服务：让 shell 里的 wget/curl 也能下载 https。
 *
 * <p>为什么需要：内置 busybox（Termux 官方包）编译时没有开 TLS —— 二进制里连
 * openssl / s_client / TLS / SSL 字符串都没有（已实测计数为 0），wget 只能走 http。
 * 自己带 openssl/curl 要拖 8 个动态库，而且它们的 SONAME 带版本号（libcrypto.so.3）
 * 没法直接以 lib*.so 打进 jniLibs；而 App 里本来就有 okhttp（证书校验、重定向、
 * 超时、gzip 全都现成），所以直接复用它。
 *
 * <p>协议（只监听 127.0.0.1，路径 = 目标 URL 的 base64url 无填充编码）：
 * <pre>
 *   GET /aHR0cHM6Ly9leGFtcGxlLmNvbS9hLnppcA HTTP/1.0
 *   → HTTP/1.1 200 + 原始字节（失败：502 + 错误文本）
 * </pre>
 * bin 目录下的 wget/curl 是包装脚本：先用内置 busybox 以 http 访问本服务，
 * 再把响应写进目标文件 —— 对调用方来说就是"wget https 能用"。
 */
public final class HttpFetchServer {

    private static final String TAG = "HttpFetchServer";
    private static volatile int sPort = -1;
    private static volatile boolean sRunning = false;

    private HttpFetchServer() {
    }

    /** 当前监听端口；未启动为 -1 */
    public static int getPort() {
        return sPort;
    }

    /** 幂等启动；返回监听端口（失败 -1） */
    public static synchronized int start(final Context ctx) {
        if (sRunning && sPort > 0) {
            return sPort;
        }
        try {
            final ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            sPort = server.getLocalPort();
            sRunning = true;
            // 端口落盘，供 shell 包装脚本读取（不依赖环境变量注入，Python 子进程也能用）
            try {
                File binDir = new File(ctx.getFilesDir(), "bin");
                if (binDir.isDirectory()) {
                    java.nio.file.Files.write(new File(binDir, ".http_port").toPath(),
                            String.valueOf(sPort).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }
            final ExecutorService pool = Executors.newFixedThreadPool(4);
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (sRunning) {
                        try {
                            final Socket sock = server.accept();
                            pool.execute(new Runnable() {
                                @Override
                                public void run() {
                                    handle(sock);
                                }
                            });
                        } catch (Exception e) {
                            if (sRunning) {
                                AILogger.e(TAG, "accept 失败: " + e.getMessage());
                            }
                        }
                    }
                }
            }, "http-fetch-accept");
            t.setDaemon(true);
            t.start();
            AILogger.i(TAG, "本地下载服务已启动: 127.0.0.1:" + sPort);
            return sPort;
        } catch (Exception e) {
            AILogger.e(TAG, "启动本地下载服务失败: " + e.getMessage());
            return -1;
        }
    }

    private static void handle(Socket sock) {
        OutputStream out = null;
        try {
            sock.setSoTimeout(180000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), "ISO-8859-1"));
            String line = in.readLine();
            out = new BufferedOutputStream(sock.getOutputStream(), 65536);
            if (line == null || !line.startsWith("GET ")) {
                writeError(out, 400, "bad request");
                return;
            }
            String path = line.substring(4).trim();
            int sp = path.indexOf(' ');
            if (sp > 0) {
                path = path.substring(0, sp);
            }
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            String target = decodeTarget(path);
            if (target == null) {
                writeError(out, 400, "bad target url");
                return;
            }
            fetch(target, out);
        } catch (Exception e) {
            AILogger.e(TAG, "handle 失败: " + e.getMessage());
            try {
                if (out != null) {
                    writeError(out, 502, String.valueOf(e.getMessage()));
                }
            } catch (Exception ignored) {
            }
        } finally {
            try {
                sock.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String decodeTarget(String b64) {
        try {
            String s = b64.replace('-', '+').replace('_', '/');
            int pad = s.length() % 4;
            if (pad == 2) {
                s += "==";
            } else if (pad == 3) {
                s += "=";
            } else if (pad != 0) {
                return null;
            }
            String url = new String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8);
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return null;
            }
            return url;
        } catch (Exception e) {
            return null;
        }
    }

    /** 真正的下载：okhttp，https 走系统证书校验 */
    private static void fetch(String url, OutputStream out) throws Exception {
        Request req = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
        Response resp = null;
        try {
            resp = com.oilquiz.app.ai.util.NetworkUtil.getClient().newCall(req).execute();
            ResponseBody body = resp.body();
            long len = body != null ? body.contentLength() : -1;
            String head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                    + (len >= 0 ? ("Content-Length: " + len + "\r\n") : "")
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("ISO-8859-1"));
            if (body != null) {
                InputStream is = body.byteStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                is.close();
            }
            out.flush();
        } finally {
            if (resp != null) {
                resp.close();
            }
        }
    }

    private static void writeError(OutputStream out, int code, String msg) throws Exception {
        byte[] b = ("下载失败: " + msg).getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " Error\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                + b.length + "\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
        out.write(b);
        out.flush();
    }

    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14) OilQuizAgent/1.0";

    /** 供 http_download 动作复用：下载到文件，返回字节数（<0 表示失败） */
    public static long downloadToFile(String url, File dest) {
        Response resp = null;
        try {
            Request req = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
            resp = com.oilquiz.app.ai.util.NetworkUtil.getClient().newCall(req).execute();
            if (!resp.isSuccessful()) {
                AILogger.e(TAG, "下载失败 HTTP " + resp.code() + ": " + url);
                return -1;
            }
            ResponseBody body = resp.body();
            if (body == null) {
                return -1;
            }
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                AILogger.e(TAG, "无法创建目录: " + parent);
            }
            InputStream is = body.byteStream();
            FileOutputStream fos = new FileOutputStream(dest);
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
                total += n;
            }
            fos.close();
            is.close();
            return total;
        } catch (Exception e) {
            AILogger.e(TAG, "下载异常 " + url + ": " + e.getMessage());
            return -1;
        } finally {
            if (resp != null) {
                resp.close();
            }
        }
    }
}
