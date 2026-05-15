package com.oilquiz.app.infra;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

public class Network {

    private static final OkHttpClient client = new OkHttpClient();

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
