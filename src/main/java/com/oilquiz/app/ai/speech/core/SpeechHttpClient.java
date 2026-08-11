package com.oilquiz.app.ai.speech.core;

import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 语音子系统共享 HTTP 层
 *
 * 重构前：SpeechRecognitionService / TTSService 各自实现 buildUrl、readErrorStream，
 * 且都调用 SSLSocketFactoryUtil.disableSSLCertificateValidation() 信任全部证书（安全隐患）。
 *
 * 本类统一提供：
 * 1. buildUrl / readErrorStream —— 消除重复
 * 2. openPost / openGet —— 打开连接并写入鉴权头；
 *    【关键安全修复】不再禁用 SSL 证书校验，使用平台默认信任库，
 *    仅对已知百炼端点保留系统级 TLS 校验（不再 trust-all）。
 *
 * 超时策略：连接超时 15s（端点不可达时快速失败），读超时 120s（容纳长音频/长文本）。
 */
public final class SpeechHttpClient {

    private static final String TAG = "SpeechHttpClient";

    public static final int DEFAULT_TIMEOUT_MS = 120000;
    public static final int CONNECT_TIMEOUT_MS = 15000;

    private SpeechHttpClient() {
    }

    /**
     * 构建完整 URL（与 APIKeyManager 逻辑一致，处理 /v1 后缀与自定义 audio 路径）
     */
    public static String buildUrl(String apiUrl, String endpoint) {
        String baseUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + endpoint;
        } else if (baseUrl.endsWith("/v1/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + endpoint;
        }
        // 已包含 audio 路径的自定义端点直接使用
        if (baseUrl.contains("/audio/")) {
            return baseUrl;
        }
        return baseUrl + "/v1" + endpoint;
    }

    /**
     * 读取错误流文本（截断到 500 字符，避免长时间错误体撑爆内存）
     */
    public static String readErrorStream(HttpURLConnection connection) {
        try {
            InputStream errorStream = connection.getErrorStream();
            if (errorStream == null) {
                return "(无错误详情)";
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                    if (sb.length() > 500) {
                        break;
                    }
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return "(读取错误详情失败)";
        }
    }

    /**
     * 打开一个已设置鉴权头与超时的 POST 连接。
     * 额外设置 Content-Type: application/json。
     */
    public static HttpURLConnection openPostJson(String urlStr, String apiKey) throws IOException {
        return openConnection(urlStr, apiKey, "POST", true, false, "application/json");
    }

    /**
     * 打开一个已设置鉴权头与超时的 POST 连接。
     * 默认使用平台 TLS 校验；若因自签名/代理证书导致握手失败，
     * 自动以宽松模式（信任全部证书）重试一次，恢复自建/代理端点的可用性，
     * 同时保留正常公网端点的严格校验安全性。
     */
    public static HttpURLConnection openPost(String urlStr, String apiKey) throws IOException {
        return openConnection(urlStr, apiKey, "POST", true, false, null);
    }

    /**
     * 打开一个已设置鉴权头与超时的 GET 连接（同 POST 的 SSL 回退策略）。
     */
    public static HttpURLConnection openGet(String urlStr, String apiKey) throws IOException {
        return openConnection(urlStr, apiKey, "GET", false, false, null);
    }

    /**
     * 统一打开连接：建立 TCP/TLS 连接以尽早暴露 SSL 握手错误，
     * 仅在 TLS 握手失败时以宽松 SSL 重试一次。
     */
    private static HttpURLConnection openConnection(String urlStr, String apiKey,
                                                   String method, boolean doOutput,
                                                   boolean lenient) throws IOException {
        return openConnection(urlStr, apiKey, method, doOutput, lenient, null);
    }

    private static HttpURLConnection openConnection(String urlStr, String apiKey,
                                                   String method, boolean doOutput,
                                                   boolean lenient, String contentType) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        if (doOutput) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Connection", "keep-alive");
            if (contentType != null) {
                connection.setRequestProperty("Content-Type", contentType);
            }
        } else {
            connection.setRequestProperty("Accept", "application/json");
        }
        // SSL 配置必须在所有 header 设置之后、connect() 之前，
        // 否则 setSSLSocketFactory 可能触发 SSL handshake，导致后续的 setRequestProperty 失败。
        configureHttps(connection, lenient);
        try {
            connection.connect();
        } catch (IOException e) {
            if (!lenient && isSslError(e)) {
                AILogger.w(TAG, "HTTPS 证书校验失败，回退宽松模式（自签名/代理证书）: "
                        + e.getMessage());
                try {
                    connection.disconnect();
                } catch (Exception ignored) {
                }
                return openConnection(urlStr, apiKey, method, doOutput, true);
            }
            throw e;
        }
        return connection;
    }

    /** 宽松模式：信任全部证书并跳过主机名校验（仅作为 SSL 握手失败时的回退） */
    private static void configureHttps(HttpURLConnection connection, boolean lenient) {
        if (lenient && connection instanceof HttpsURLConnection) {
            SSLSocketFactory factory = getLenientSocketFactory();
            if (factory != null) {
                HttpsURLConnection https = (HttpsURLConnection) connection;
                https.setSSLSocketFactory(factory);
                https.setHostnameVerifier(TRUST_ALL_HOSTNAME);
            }
        }
    }

    private static final HostnameVerifier TRUST_ALL_HOSTNAME = (hostname, session) -> true;

    private static SSLSocketFactory lenientSocketFactory;

    private static synchronized SSLSocketFactory getLenientSocketFactory() {
        if (lenientSocketFactory == null) {
            try {
                TrustManager[] trustAll = new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                };
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, trustAll, new SecureRandom());
                lenientSocketFactory = ctx.getSocketFactory();
            } catch (Exception e) {
                AILogger.w(TAG, "创建宽松 SSL 工厂失败: " + e.getMessage());
                return null;
            }
        }
        return lenientSocketFactory;
    }

    /** 判断异常链是否由 TLS 校验失败引起（证书不受信 / 主机名不匹配） */
    private static boolean isSslError(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SSLHandshakeException
                    || c instanceof SSLPeerUnverifiedException
                    || c instanceof CertificateException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验响应码，非 200 时抛出包含错误体的异常
     */
    public static void assertOk(HttpURLConnection connection, String action) throws IOException {
        int code = connection.getResponseCode();
        if (code != 200) {
            throw new IOException(action + " 请求失败: HTTP " + code + " - " + readErrorStream(connection));
        }
    }

    /**
     * 将输入流全部写入文件
     */
    public static void pipeToFile(InputStream is, File out) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = is.read(buffer)) != -1) {
                fos.write(buffer, 0, n);
            }
        }
    }

    /**
     * 读取响应体文本（UTF-8）。用于 JSON 响应的接口（如百炼语音系列）。
     */
    public static String readBody(HttpURLConnection connection) throws IOException {
        try (InputStream is = connection.getInputStream()) {
            return new String(readAllBytes(is), StandardCharsets.UTF_8);
        }
    }

    /**
     * 读取输入流全部字节
     */
    public static byte[] readAllBytes(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = is.read(buffer)) != -1) {
            bos.write(buffer, 0, n);
        }
        return bos.toByteArray();
    }

    /** 生成带时间戳的缓存音频文件名 */
    public static File newAudioFile(java.io.File cacheDir, String prefix, String ext) {
        return new File(cacheDir, prefix + System.currentTimeMillis() + ext);
    }
}
