package com.oilquiz.app.ai.model;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 模型索引（sidecar）：把「预设 id / 期望文件名」与实际下载到磁盘的文件绑定，
 * 从而让**用户改名后**下载页与引擎仍能正确识别（原实现只按 URL 文件名比对，改名即失效）。
 *
 * <p>存储：{@code files/ai_models/.model_index.json}
 * <pre>{"presets":{"qwen3-vl-2b-instruct":{"fileName":"Qwen3-VL-2B-Instruct-Q4_0.gguf","sizeBytes":123}}}</pre>
 *
 * <p>自愈策略：按名字找不到时，用记录的**字节数**在同目录里找唯一匹配的 .gguf
 * （同一文件的字节数固定，改名不影响），命中后顺手把新文件名写回索引。
 */
public final class ModelIndexStore {
    private static final String TAG = "ModelIndexStore";
    private static final String INDEX_FILE = ".model_index.json";

    private ModelIndexStore() {
    }

    private static File indexFile(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "ai_models"), INDEX_FILE);
    }

    private static JSONObject load(Context ctx) {
        try {
            File f = indexFile(ctx);
            if (!f.isFile()) {
                return new JSONObject();
            }
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(buf);
                if (n <= 0) {
                    return new JSONObject();
                }
            }
            return new JSONObject(new String(buf, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static void save(Context ctx, JSONObject root) {
        try {
            File f = indexFile(ctx);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.w(TAG, "保存索引失败: " + t);
        }
    }

    /** 下载完成后登记：预设 id → 实际文件名 + 字节数（mmproj 等辅助文件不登记） */
    public static void record(Context ctx, String presetId, File file) {
        if (ctx == null || presetId == null || file == null || !file.isFile()) {
            return;
        }
        if (file.getName().toLowerCase().contains("mmproj")) {
            return;
        }
        try {
            JSONObject root = load(ctx);
            JSONObject presets = root.optJSONObject("presets");
            if (presets == null) {
                presets = new JSONObject();
                root.put("presets", presets);
            }
            JSONObject e = new JSONObject();
            e.put("fileName", file.getName());
            e.put("sizeBytes", file.length());
            presets.put(presetId, e);
            save(ctx, root);
            Log.i(TAG, "索引已登记: " + presetId + " -> " + file.getName() + " (" + file.length() + "B)");
        } catch (Throwable t) {
            Log.w(TAG, "登记索引失败: " + t);
        }
    }

    /**
     * 把「期望路径」解析为**实际存在**的文件：
     * ① 期望路径存在 → 直接用；
     * ② 否则按索引里记录的文件名找同目录；
     * ③ 再否则按索引里记录的字节数找唯一匹配，命中后写回新名字（改名自愈）。
     *
     * @return 实际文件；仍找不到返回 null
     */
    public static File resolveHealed(Context ctx, File expected) {
        if (expected == null) {
            return null;
        }
        if (expected.isFile()) {
            return expected;
        }
        File dir = expected.getParentFile();
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        try {
            JSONObject root = load(ctx);
            JSONObject presets = root.optJSONObject("presets");
            if (presets == null) {
                return null;
            }
            String wanted = expected.getName();
            java.util.Iterator<String> keys = presets.keys();
            while (keys.hasNext()) {
                String presetId = keys.next();
                JSONObject e = presets.optJSONObject(presetId);
                if (e == null) {
                    continue;
                }
                String recName = e.optString("fileName", "");
                long recSize = e.optLong("sizeBytes", 0L);
                if (wanted.equals(recName)) {
                    // ② 按记录的文件名找
                    File byName = new File(dir, recName);
                    if (byName.isFile()) {
                        return byName;
                    }
                    // ③ 按体积找唯一匹配（改名场景）
                    File bySize = findBySize(dir, recSize);
                    if (bySize != null) {
                        Log.i(TAG, "改名自愈: " + recName + " -> " + bySize.getName()
                                + "（预设 " + presetId + "）");
                        record(ctx, presetId, bySize);
                        return bySize;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "解析索引失败: " + t);
        }
        return null;
    }

    private static File findBySize(File dir, long size) {
        if (size <= 0) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        File hit = null;
        for (File f : files) {
            if (!f.isFile() || !f.getName().toLowerCase().endsWith(".gguf")) {
                continue;
            }
            if (f.getName().toLowerCase().contains("mmproj")) {
                continue;
            }
            if (f.length() == size) {
                if (hit != null) {
                    return null;   // 多个同体积 → 不猜
                }
                hit = f;
            }
        }
        return hit;
    }
}
