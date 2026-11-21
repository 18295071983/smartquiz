package com.oilquiz.app.ai.onnx;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ONNX 模型仓库（**内置模式**）。
 *
 * <p>模型文件随 APK 打进 {@code assets/models/}（用户不需要联网），首次使用时复制到
 * {@code filesDir/onnx_models/}——ONNX Runtime 只认文件路径，不能直接读 assets。
 * 复制是一次性的（之后按大小/哈希判定已就绪直接复用），并且带 {@code *.part} 临时文件 + 原子改名，
 * 避免半截文件被当成模型。</p>
 *
 * <p>历史：最初这里自己用 OkHttp 下载、后来改成走 {@link com.oilquiz.app.ai.model.ModelDownloadManager}，
 * 但两个模型的官方源都在 GitHub（ONNX Model Zoo），而下载管理器的镜像只覆盖 HF/ModelScope，
 * 国内实际拉不动，所以最终改为内置。</p>
 */
public final class OnnxModelStore {

    private static final String TAG = "OnnxModelStore";
    private static final String DIR_NAME = "onnx_models";

    /** key → 是否正在复制（防并发重复拷贝） */
    private static final ConcurrentHashMap<String, AtomicBoolean> RUNNING = new ConcurrentHashMap<>();

    private OnnxModelStore() {
    }

    /**
     * 一个内置模型的描述。
     */
    public static final class Spec {
        /** 唯一标识（日志/并发锁用） */
        public final String key;
        /** 复制到 filesDir 后的文件名 */
        public final String fileName;
        /** APK 内 assets 路径（如 models/mobilenetv2-7.onnx） */
        public final String assetPath;
        /** 期望字节数（0 = 不校验） */
        public final long expectedBytes;
        /** 期望 SHA-256（null = 不校验） */
        public final String sha256;

        public Spec(String key, String fileName, String assetPath, long expectedBytes, String sha256) {
            this.key = key;
            this.fileName = fileName;
            this.assetPath = assetPath;
            this.expectedBytes = expectedBytes;
            this.sha256 = sha256;
        }
    }

    /**
     * 就绪回调（工作线程回调，更新 UI 请自行切主线程）。
     */
    public interface Callback {
        /** 开始准备（totalBytes 是模型大小） */
        void onStart(long totalBytes);

        /** 进度；percent 可能是 -1（大小未知） */
        void onProgress(int percent, long copiedBytes, long totalBytes);

        /** 模型就绪（filesDir 下的真实文件） */
        void onReady(File model);

        /** 失败（assets 缺失/磁盘/哈希） */
        void onError(String message);
    }

    /** 模型落地目录（不存在则创建） */
    public static File dir(Context context) {
        File d = new File(context.getApplicationContext().getFilesDir(), DIR_NAME);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** 模型在 filesDir 下的路径 */
    public static File file(Context context, Spec spec) {
        return new File(dir(context), spec.fileName);
    }

    /** 本地是否已经准备好（大小与期望值一致） */
    public static boolean isReady(Context context, Spec spec) {
        File f = file(context, spec);
        if (!f.isFile() || f.length() < 1024) {
            return false;
        }
        if (spec.expectedBytes <= 0) {
            return true;
        }
        // 内置模型走精确大小判断；避免"差几个字节也算就绪"导致 ORT 加载失败
        return f.length() == spec.expectedBytes;
    }

    /**
     * 确保模型就绪：已复制过 → 同步回调 onReady；否则后台从 assets 复制。
     * 本方法立即返回，不会阻塞调用线程。
     */
    public static void ensure(Context context, Spec spec, Callback callback) {
        final Context app = context.getApplicationContext();
        if (isReady(app, spec)) {
            callback.onReady(file(app, spec));
            return;
        }

        AtomicBoolean running = RUNNING.computeIfAbsent(spec.key, k -> new AtomicBoolean(false));
        if (!running.compareAndSet(false, true)) {
            callback.onError("模型正在准备中，请稍后再试");
            return;
        }

        callback.onStart(spec.expectedBytes);
        new Thread(() -> {
            try {
                File out = copyFromAssets(app, spec, callback);
                running.set(false);
                callback.onReady(out);
            } catch (Throwable t) {
                running.set(false);
                Log.e(TAG, "内置模型准备失败(" + spec.key + "): " + t.getMessage(), t);
                callback.onError("模型准备失败: " + t.getMessage());
            }
        }, "onnx-copy-" + spec.key).start();
    }

    /** 删除已复制到 filesDir 的模型（换模型/清理磁盘） */
    public static boolean delete(Context context, Spec spec) {
        File f = file(context, spec);
        if (!f.exists()) {
            return true;
        }
        boolean ok = f.delete();
        if (ok) {
            RUNNING.remove(spec.key);
        }
        return ok;
    }

    private static File copyFromAssets(Context context, Spec spec, Callback callback) throws Exception {
        File target = file(context, spec);
        File part = new File(target.getParentFile(), spec.fileName + ".part");
        if (part.exists() && !part.delete()) {
            throw new IOException("无法清理临时文件: " + part.getName());
        }

        long total = spec.expectedBytes;
        MessageDigest digest = spec.sha256 != null ? MessageDigest.getInstance("SHA-256") : null;
        long copied = 0;
        int lastPercent = Integer.MIN_VALUE;
        byte[] buf = new byte[64 * 1024];

        try (InputStream in = context.getAssets().open(spec.assetPath);
             FileOutputStream out = new FileOutputStream(part)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (digest != null) {
                    digest.update(buf, 0, n);
                }
                copied += n;
                int percent = total > 0 ? (int) (copied * 100 / total) : -1;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    callback.onProgress(percent, copied, total);
                }
            }
            out.flush();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("读取内置模型失败(" + spec.assetPath + "): " + e.getMessage());
        }

        long size = part.length();
        if (spec.expectedBytes > 0 && size != spec.expectedBytes) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("内置模型大小不符: " + size + " != " + spec.expectedBytes);
        }
        if (digest != null) {
            String got = toHex(digest.digest());
            if (!got.equalsIgnoreCase(spec.sha256)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("内置模型 SHA-256 不符: " + got);
            }
        }

        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("无法覆盖旧模型文件: " + target.getName());
        }
        if (!part.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("临时文件改名失败: " + target.getName());
        }
        Log.i(TAG, "内置模型已就绪: " + target.getName() + " (" + size + " 字节)");
        return target;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
