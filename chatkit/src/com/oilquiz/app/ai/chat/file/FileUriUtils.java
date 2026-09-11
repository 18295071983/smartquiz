package com.oilquiz.app.ai.chat.file;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 文件 / URI 工具集（全局可复用）。
 *
 * 从 AIChatActivity 文件工具方法抽取：content:// / file:// / tree:// 解析为真实路径、
 * 流复制到缓存、文件名/大小读取、人类可读体积格式化。零 UI 依赖。
 */
public class FileUriUtils {

    private final Context context;

    public FileUriUtils(Context context) {
        this.context = context.getApplicationContext();
    }

    /** file:// 直接返回路径；content:// 尝试 _data 列真实路径，失败则复制到缓存 */
    public String resolveOrCopyToCacheFile(Uri uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            return uri.getPath();
        }
        if ("content".equalsIgnoreCase(scheme)) {
            String direct = queryDataColumnForPath(uri);
            if (direct != null && new File(direct).exists()) {
                return direct;
            }
        }
        return copyUriToCacheFile(uri);
    }

    /** 目录 URI 解析为尽可能真实的路径 */
    public String resolveDirectoryPath(Uri treeUri) {
        if (treeUri == null) return null;
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            if (docId != null) {
                if (docId.startsWith("primary:")) {
                    String rel = docId.substring("primary:".length());
                    String ext = Environment.getExternalStorageDirectory().getAbsolutePath();
                    File f = new File(ext, rel);
                    if (f.exists() && f.isDirectory()) return f.getAbsolutePath();
                }
                int colon = docId.indexOf(':');
                if (colon > 0) {
                    String rel = docId.substring(colon + 1);
                    String ext = Environment.getExternalStorageDirectory().getAbsolutePath();
                    File f = new File(ext, rel);
                    if (f.exists() && f.isDirectory()) return f.getAbsolutePath();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 尝试通过 MediaStore _data 列拿真实文件路径（不保证所有 ROM 都可用） */
    public String queryDataColumnForPath(Uri uri) {
        try {
            Cursor c = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, "_data"}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex("_data");
                        if (idx >= 0) {
                            String v = c.getString(idx);
                            if (v != null && !v.isEmpty()) return v;
                        }
                    }
                } finally { c.close(); }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 把 content:// 流复制到 app 缓存，返回临时文件的绝对路径（同名自动加序号避免覆盖） */
    public String copyUriToCacheFile(Uri uri) {
        try {
            String name = getFileNameFromUri(uri);
            if (name == null || name.isEmpty()) name = "picked_" + System.currentTimeMillis();
            name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
            File dir = context.getExternalCacheDir();
            if (dir == null) dir = context.getCacheDir();
            File out = new File(dir, name);
            if (out.exists()) {
                String base = name;
                String ext = "";
                int dot = base.lastIndexOf('.');
                if (dot > 0) { ext = base.substring(dot); base = base.substring(0, dot); }
                int i = 1;
                while (out.exists()) {
                    out = new File(dir, base + "_" + (i++) + ext);
                }
            }
            InputStream is = null;
            OutputStream os = null;
            try {
                is = context.getContentResolver().openInputStream(uri);
                if (is == null) return null;
                os = new FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            } finally {
                try { if (is != null) is.close(); } catch (Exception ignored) {}
                try { if (os != null) os.close(); } catch (Exception ignored) {}
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            android.util.Log.w("FileUriUtils", "复制缓存文件失败: " + e.getMessage());
            return null;
        }
    }

    /** 从 Uri 取展示名（DISPLAY_NAME 列 → lastPathSegment → 兜底） */
    public String getFileNameFromUri(Uri uri) {
        String result = null;
        try {
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) result = cursor.getString(idx);
                cursor.close();
            }
        } catch (Exception e) { /* ignore */ }
        if (result == null) {
            result = uri.getLastPathSegment();
            if (result != null && result.contains("/")) result = result.substring(result.lastIndexOf("/") + 1);
        }
        return result != null ? result : "未知文件";
    }

    /** 从 Uri 读取文件大小（SIZE 列，不可用时返回 0） */
    public long getFileSizeFromUri(Uri uri) {
        try {
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                long size = idx >= 0 ? cursor.getLong(idx) : 0;
                cursor.close();
                return size;
            }
        } catch (Exception e) { /* ignore */ }
        return 0;
    }

    /** 人类可读文件体积（B / KB / MB） */
    public static String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / (1024.0 * 1024.0));
    }
}
