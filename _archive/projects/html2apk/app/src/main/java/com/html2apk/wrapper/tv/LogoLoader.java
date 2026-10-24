package com.html2apk.wrapper.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 频道台标加载器（v9.14）。
 *
 * <p>不引入 Glide/Coil，保持壳体积：支持 http(s)、file://、content://、本地路径，
 * 做尺寸采样 + 内存 LruCache，回调在主线程。加载失败时由调用方保留自适应占位图。</p>
 */
public final class LogoLoader {

    public interface Callback {
        void onLoaded(String url, Bitmap bitmap);
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private static volatile LogoLoader instance;

    public static LogoLoader get(Context context) {
        LogoLoader l = instance;
        if (l == null) {
            synchronized (LogoLoader.class) {
                l = instance;
                if (l == null) {
                    l = new LogoLoader(context.getApplicationContext());
                    instance = l;
                }
            }
        }
        return l;
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> cache;

    private LogoLoader(Context context) {
        this.appContext = context;
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        this.cache = new LruCache<String, Bitmap>(Math.max(2048, maxKb)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public void load(String url, int targetPx, Callback callback) {
        if (url == null || url.trim().isEmpty()) return;
        final String key = url.trim();
        Bitmap hit = cache.get(key);
        if (hit != null) {
            final Bitmap b = hit;
            main.post(() -> callback.onLoaded(key, b));
            return;
        }
        final int size = Math.max(24, targetPx);
        executor.execute(() -> {
            Bitmap b = fetch(key, size);
            if (b != null) cache.put(key, b);
            final Bitmap out = b;
            main.post(() -> callback.onLoaded(key, out));
        });
    }

    private Bitmap fetch(String url, int targetPx) {
        try {
            if (url.startsWith("file://") || url.startsWith("/")) {
                String path = url.startsWith("file://") ? Uri.parse(url).getPath() : url;
                if (path == null) return null;
                return decodeFile(new File(path), targetPx);
            }
            if (url.startsWith("content://")) {
                try (InputStream in = appContext.getContentResolver().openInputStream(Uri.parse(url))) {
                    if (in == null) return null;
                    return decode(readLimited(in), targetPx);
                }
            }
            if (!(url.startsWith("http://") || url.startsWith("https://"))) return null;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "image/*");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 400) return null;
                try (InputStream in = conn.getInputStream()) {
                    return decode(readLimited(in), targetPx);
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] readLimited(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_BYTES) return null;
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static Bitmap decodeFile(File f, int targetPx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx);
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decode(byte[] data, int targetPx) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx);
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) {
            return null;
        }
    }

    private static int sampleSize(int w, int h, int target) {
        int sample = 1;
        if (w <= 0 || h <= 0) return 1;
        while (w / (sample * 2) >= target * 2 && h / (sample * 2) >= target * 2) {
            sample *= 2;
        }
        return sample;
    }
}
