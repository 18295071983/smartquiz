package com.oilquiz.app.ai.util;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class NetworkUtil {

    private static OkHttpClient client;
    private static final Random random = new Random();
    private static final AtomicLong lastRequestTime = new AtomicLong(0);
    private static final long MIN_REQUEST_INTERVAL = 500;

    private static final String[] USER_AGENTS = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Edge/120.0.0.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/605.1.15",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"
    };

    private static final String[] ACCEPT_VALUES = {
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "application/json,text/plain,*/*;q=0.9"
    };

    private static final String[] ACCEPT_LANGUAGE_VALUES = {
        "zh-CN,zh;q=0.9,en;q=0.8",
        "zh-CN,zh;q=0.9",
        "en-US,en;q=0.9,zh-CN;q=0.8"
    };

    private static final String[] ACCEPT_ENCODING_VALUES = {
        "gzip, deflate, br",
        "gzip, deflate",
        "br, gzip, deflate"
    };

    private static final String[] SEC_CH_UA_VALUES = {
        "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
        "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"119\", \"Google Chrome\";v=\"119\"",
        "\"Microsoft Edge\";v=\"120\", \"Chromium\";v=\"120\", \"Not.A/Brand\";v=\"24\""
    };

    static {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            client = new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .retryOnConnectionFailure(true)
                    .addInterceptor(new okhttp3.Interceptor() {
                        @Override
                        public Response intercept(Chain chain) throws IOException {
                            Request originalRequest = chain.request();
                            Request request = originalRequest.newBuilder()
                                    .addHeader("Accept-Encoding", "gzip, deflate")
                                    .build();
                            Response response = chain.proceed(request);
                            
                            String contentEncoding = response.header("Content-Encoding");
                            String contentLength = response.header("Content-Length");
                            
                            if (contentEncoding != null && contentEncoding.contains("gzip")) {
                                System.out.println("[Gzip] Response is gzip compressed, OkHttp will auto-decompress");
                            }
                            
                            return response;
                        }
                    })
                    .build();
        } catch (Exception e) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .retryOnConnectionFailure(true)
                    .build();
        }
    }

    private static void enforceRequestInterval() {
        long now = System.currentTimeMillis();
        long last = lastRequestTime.get();
        long diff = now - last;
        if (diff < MIN_REQUEST_INTERVAL) {
            try {
                Thread.sleep(MIN_REQUEST_INTERVAL - diff + random.nextInt(200));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestTime.set(System.currentTimeMillis());
    }

    private static String getRandomUserAgent() {
        return USER_AGENTS[random.nextInt(USER_AGENTS.length)];
    }

    private static String getRandomAccept() {
        return ACCEPT_VALUES[random.nextInt(ACCEPT_VALUES.length)];
    }

    private static String getRandomAcceptLanguage() {
        return ACCEPT_LANGUAGE_VALUES[random.nextInt(ACCEPT_LANGUAGE_VALUES.length)];
    }

    private static String getRandomAcceptEncoding() {
        return ACCEPT_ENCODING_VALUES[random.nextInt(ACCEPT_ENCODING_VALUES.length)];
    }

    private static String getRandomSecChUa() {
        return SEC_CH_UA_VALUES[random.nextInt(SEC_CH_UA_VALUES.length)];
    }

    public static String get(String url) throws IOException {
        return get(url, null, true);
    }

    public static String get(String url, Map<String, String> customHeaders) throws IOException {
        return get(url, customHeaders, true);
    }

    public static String get(String url, Map<String, String> customHeaders, boolean useAntiCrawl) throws IOException {
        enforceRequestInterval();
        
        Request.Builder builder = new Request.Builder()
                .url(url)
                .get();

        if (useAntiCrawl) {
            builder.addHeader("User-Agent", getRandomUserAgent());
            builder.addHeader("Accept", getRandomAccept());
            builder.addHeader("Accept-Language", getRandomAcceptLanguage());
            builder.addHeader("Accept-Encoding", getRandomAcceptEncoding());
            builder.addHeader("Sec-Ch-Ua", getRandomSecChUa());
            builder.addHeader("Sec-Ch-Ua-Mobile", "?0");
            builder.addHeader("Sec-Ch-Ua-Platform", "\"Windows\"");
            builder.addHeader("Sec-Fetch-Site", "none");
            builder.addHeader("Sec-Fetch-Mode", "navigate");
            builder.addHeader("Sec-Fetch-User", "?1");
            builder.addHeader("Sec-Fetch-Dest", "document");
            builder.addHeader("Connection", "keep-alive");
            builder.addHeader("Cache-Control", "max-age=0");
        }

        if (customHeaders != null) {
            for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
                builder.addHeader(entry.getKey(), entry.getValue());
            }
        }

        Request request = builder.build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        }
    }

    public static Response execute(Request request) throws IOException {
        enforceRequestInterval();
        return client.newCall(request).execute();
    }

    public static OkHttpClient getClient() {
        return client;
    }

    public static Request.Builder createRequestBuilder(String url) {
        return new Request.Builder()
                .url(url)
                .addHeader("User-Agent", getRandomUserAgent())
                .addHeader("Accept", getRandomAccept())
                .addHeader("Accept-Language", getRandomAcceptLanguage())
                .addHeader("Accept-Encoding", getRandomAcceptEncoding())
                .addHeader("Sec-Ch-Ua", getRandomSecChUa())
                .addHeader("Sec-Ch-Ua-Mobile", "?0")
                .addHeader("Sec-Ch-Ua-Platform", "\"Windows\"")
                .addHeader("Sec-Fetch-Site", "none")
                .addHeader("Sec-Fetch-Mode", "navigate")
                .addHeader("Sec-Fetch-User", "?1")
                .addHeader("Sec-Fetch-Dest", "document")
                .addHeader("Connection", "keep-alive")
                .addHeader("Cache-Control", "max-age=0");
    }

    public static Request.Builder createApiRequestBuilder(String url) {
        return new Request.Builder()
                .url(url)
                .addHeader("User-Agent", getRandomUserAgent())
                .addHeader("Accept", "application/json,text/plain,*/*;q=0.9")
                .addHeader("Accept-Language", getRandomAcceptLanguage())
                .addHeader("Accept-Encoding", getRandomAcceptEncoding())
                .addHeader("Connection", "keep-alive")
                .addHeader("Cache-Control", "no-cache");
    }
}