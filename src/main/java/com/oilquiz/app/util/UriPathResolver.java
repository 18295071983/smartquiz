package com.oilquiz.app.util;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 把路径字符串（绝对路径 / content:// / file://）解析为可直接访问的真实文件。
 * <p>
 * AI 工具（OCR / 图像识别等）收到的 image_path / pdf_path 可能来自系统文件选择器或相册，
 * 是 content:// URI（如 content://media/external/images/media/123），
 * 而 OCR / 视觉模型 / 图片解码只认真实文件路径。本工具负责统一解析：
 * <ul>
 *   <li>content:// —— 通过 ContentResolver 复制流到应用缓存目录，返回临时文件</li>
 *   <li>file:// —— 直接取路径段</li>
 *   <li>普通绝对路径 —— 原样返回</li>
 * </ul>
 */
public final class UriPathResolver {

    private static final String TAG = "UriPathResolver";

    private UriPathResolver() {
    }

    /**
     * 把路径/URI 字符串解析为真实文件。
     *
     * @return 可直接访问的 File；文件不存在或解析失败时返回 null
     */
    public static File resolveToFile(Context context, String pathOrUri) {
        if (context == null || pathOrUri == null || pathOrUri.isEmpty()) return null;
        String lower = pathOrUri.toLowerCase();
        // 1. file:// URI —— 直接取路径段
        if (lower.startsWith("file://")) {
            try {
                File f = new File(Uri.parse(pathOrUri).getPath());
                return f.exists() ? f : null;
            } catch (Exception e) {
                AILogger.w(TAG, "file:// URI 解析失败: " + pathOrUri + ", " + e.getMessage());
                return null;
            }
        }
        // 2. content:// URI —— 复制流到缓存目录，返回临时文件
        if (lower.startsWith("content://")) {
            return copyContentUriToCache(context, pathOrUri);
        }
        // 3. 普通绝对路径
        File f = new File(pathOrUri);
        return f.exists() ? f : null;
    }

    /** 把 content:// URI 的内容复制到应用缓存目录，返回临时文件（OCR/视觉模型只认真实文件） */
    public static File copyContentUriToCache(Context context, String uriString) {
        try {
            Uri uri = Uri.parse(uriString);
            String name = queryDisplayName(context, uri);
            if (name == null || name.isEmpty()) {
                name = "uri_" + System.currentTimeMillis();
            }
            // 文件名安全化，保留扩展名
            name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
            File dir = context.getExternalCacheDir();
            if (dir == null) dir = context.getCacheDir();
            File out = new File(dir, name);
            if (out.exists()) {
                String base = name, ext = "";
                int dot = base.lastIndexOf('.');
                if (dot > 0) { ext = base.substring(dot); base = base.substring(0, dot); }
                int i = 1;
                while (out.exists()) {
                    out = new File(dir, base + "_" + (i++) + ext);
                }
            }
            try (InputStream is = context.getContentResolver().openInputStream(uri);
                 FileOutputStream os = new FileOutputStream(out)) {
                if (is == null) {
                    AILogger.w(TAG, "content:// 无法打开输入流: " + uriString);
                    return null;
                }
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            }
            return out.exists() ? out : null;
        } catch (Exception e) {
            AILogger.e(TAG, "复制 content:// 到缓存失败: " + uriString + ", " + e.getMessage());
            return null;
        }
    }

    /** 查询 content:// URI 的显示文件名（拿不到时返回 null） */
    private static String queryDisplayName(Context context, Uri uri) {
        try (android.database.Cursor c = context.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
