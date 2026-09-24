package com.oilquiz.app.infra;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

public class Network {

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                okhttp3.Request request = chain.request();
                // 自动附加应用内已保存的 WebView 登录态 Cookie（未显式传 Cookie 时）
                // 安全：明文 HTTP 不自动携带登录态 Cookie（防中间人窃听请求头中的登录凭证）
                if (request.header("Cookie") == null) {
                    String reqUrl = request.url().toString();
                    boolean plainHttp = reqUrl.startsWith("http://");
                    boolean hasLogin = com.oilquiz.app.webview.AppCookieStore.getInstance().hasLogin(reqUrl);
                    if (!plainHttp || !hasLogin) {
                        String cookie = com.oilquiz.app.webview.AppCookieStore.getInstance()
                                .getCookieHeader(reqUrl);
                        if (cookie != null && !cookie.isEmpty()) {
                            request = request.newBuilder().header("Cookie", cookie).build();
                        }
                    }
                }
                return chain.proceed(request);
            })
            .build();

    public static Response execute(Request request) throws IOException {
        return client.newCall(request).execute();
    }

    public static String get(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .build();
        try (Response response = client.newCall(request).execute()) {
            return response.body().string();
        }
    }

    public static OkHttpClient getClient() {
        return client;
    }
}
