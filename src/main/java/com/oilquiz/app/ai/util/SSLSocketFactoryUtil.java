package com.oilquiz.app.ai.util;

import android.util.Log;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * SSL证书验证工具类
 * 用于处理SSL证书验证问题，特别是自签名证书或不被系统信任的证书
 */
public class SSLSocketFactoryUtil {

    private static final String TAG = "SSLSocketFactoryUtil";

    /**
     * 信任所有证书的TrustManager
     */
    private static class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /**
     * 接受所有主机名的HostnameVerifier
     */
    private static class TrustAllHostnameVerifier implements HostnameVerifier {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    }

    /**
     * 禁用SSL证书验证
     * 注意：这会降低安全性，仅在明确信任目标服务器时使用
     */
    public static void disableSSLCertificateValidation() {
        try {
            // 创建信任所有证书的TrustManager
            TrustManager[] trustAllCerts = new TrustManager[]{new TrustAllManager()};

            // 创建SSLContext并初始化
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            // 设置默认的SSLSocketFactory
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());

            // 设置默认的HostnameVerifier
            HttpsURLConnection.setDefaultHostnameVerifier(new TrustAllHostnameVerifier());

            Log.d(TAG, "SSL certificate validation disabled");
        } catch (Exception e) {
            Log.e(TAG, "Failed to disable SSL certificate validation", e);
        }
    }

    /**
     * 为单个连接禁用SSL证书验证
     * @param connection 需要禁用验证的HttpsURLConnection
     */
    public static void disableSSLCertificateValidation(HttpsURLConnection connection) {
        try {
            // 创建信任所有证书的TrustManager
            TrustManager[] trustAllCerts = new TrustManager[]{new TrustAllManager()};

            // 创建SSLContext并初始化
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            // 设置SSLSocketFactory
            connection.setSSLSocketFactory(sslContext.getSocketFactory());

            // 设置HostnameVerifier
            connection.setHostnameVerifier(new TrustAllHostnameVerifier());

            Log.d(TAG, "SSL certificate validation disabled for connection");
        } catch (Exception e) {
            Log.e(TAG, "Failed to disable SSL certificate validation for connection", e);
        }
    }

    /**
     * 恢复默认的SSL证书验证
     */
    public static void restoreSSLCertificateValidation() {
        try {
            // 创建默认的SSLContext
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, null, null);

            // 恢复默认的SSLSocketFactory
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());

            // 恢复默认的HostnameVerifier
            HttpsURLConnection.setDefaultHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());

            Log.d(TAG, "SSL certificate validation restored");
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore SSL certificate validation", e);
        }
    }
}