package com.oilquiz.app.browser.push;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 订阅持久化（单 JSON 文件，整表覆盖；条目上限由 Manager 控制） */
final class WebPushStore {

    private static final String TAG = "WebPushStore";
    private static final String FILE_NAME = "web_push_subscriptions.json";

    private final File file;
    private final Gson gson = new Gson();
    private final Object lock = new Object();

    WebPushStore(Context context) {
        this.file = new File(context.getFilesDir(), FILE_NAME);
    }

    private static final class Wrapper {
        List<WebPushSubscription> subscriptions = new ArrayList<>();
    }

    List<WebPushSubscription> load() {
        synchronized (lock) {
            List<WebPushSubscription> out = new ArrayList<>();
            try {
                if (!file.exists()) return out;
                String json = readFile();
                if (json.trim().isEmpty()) return out;
                Wrapper wrapper = gson.fromJson(json, new TypeToken<Wrapper>() { }.getType());
                if (wrapper == null || wrapper.subscriptions == null) return out;
                for (WebPushSubscription s : wrapper.subscriptions) {
                    if (s == null || s.endpoint == null || s.endpoint.isEmpty()) continue;
                    if (s.id == null || s.id.isEmpty()) continue;
                    if (s.method == null || s.method.isEmpty()) s.method = "GET";
                    if (s.headersJson == null || s.headersJson.isEmpty()) s.headersJson = "{}";
                    if (s.titleField == null || s.titleField.isEmpty()) s.titleField = "title";
                    if (s.bodyField == null || s.bodyField.isEmpty()) s.bodyField = "body";
                    if (s.idField == null || s.idField.isEmpty()) s.idField = "id";
                    if (s.urlField == null || s.urlField.isEmpty()) s.urlField = "url";
                    if (s.seenIds == null) s.seenIds = new ArrayList<>();
                    if (s.intervalMinutes < 15) s.intervalMinutes = 15;
                    out.add(s);
                }
            } catch (Throwable t) {
                Log.w(TAG, "读取订阅失败: " + t.getMessage());
            }
            return out;
        }
    }

    void save(List<WebPushSubscription> subscriptions) {
        synchronized (lock) {
            try {
                Wrapper wrapper = new Wrapper();
                wrapper.subscriptions = subscriptions == null
                        ? new ArrayList<>() : new ArrayList<>(subscriptions);
                String json = gson.toJson(wrapper);
                byte[] data = json.getBytes(StandardCharsets.UTF_8);
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                File tmp = new File(file.getAbsolutePath() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(data);
                }
                if (!tmp.renameTo(file)) {
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write(data);
                    }
                    tmp.delete();
                }
            } catch (Throwable t) {
                Log.w(TAG, "保存订阅失败: " + t.getMessage());
            }
        }
    }

    private String readFile() throws Exception {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
