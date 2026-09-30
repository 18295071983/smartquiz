package com.oilquiz.app.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

import com.oilquiz.app.util.export.ExportFileSaver;

/**
 * 公共目录写入器：目标 Download/OilQuiz/&lt;sub&gt;/&lt;name&gt;。
 *
 * <p><b>设计目标：脱离「所有文件访问」(MANAGE_EXTERNAL_STORAGE) 依赖。</b>
 * <ul>
 *   <li><b>写</b>：MediaStore.Downloads 优先（Android 10+ 无需任何权限即可写 Download 公共目录，
 *       文件管理器可见可编辑）；失败回退 File API（已授权时更快）；再失败返回 null（调用方提示/降级）。</li>
 *   <li><b>读</b>：File API 优先（快、可 seek 尾部）；无权限时 File 读公共目录会失败，
 *       回退 MediaStore 查询 + openInputStream（Download 目录变化会被 MediaProvider watcher 自动索引）。</li>
 *   <li><b>删</b>：File + MediaStore 双删（MediaStore 残留旧记录会误导后续查询）。</li>
 * </ul>
 * Termux 侧读这些文件走 sdcard_rw 组（公共目录实体），与 App 权限无关——整条链路不再需要
 * MANAGE_EXTERNAL_STORAGE。
 */
public final class PublicStorageWriter {
    private static final String TAG = "PublicStorageWriter";

    /** MediaStore RELATIVE_PATH 前缀（不含 "Download/" 前缀目录段） */
    public static final String ROOT_REL = "Download/OilQuiz";

    private PublicStorageWriter() {
    }

    // ---------- 写 ----------

    /** 写小文件（字节），成功返回用户可见路径描述，失败返回 null */
    public static String writeBytes(Context ctx, String sub, String name, String mime, byte[] bytes) {
        if (ctx == null || name == null || bytes == null) {
            return null;
        }
        String viaMedia = writeViaMediaStore(ctx, sub, name, mime,
                new ByteArrayInputStream(bytes));
        if (viaMedia != null) {
            return viaMedia;
        }
        return writeViaFile(ctx, sub, name, new ByteArrayInputStream(bytes));
    }

    /** 写文本（UTF-8） */
    public static String writeText(Context ctx, String sub, String name, String content) {
        if (content == null) {
            return null;
        }
        return writeBytes(ctx, sub, name, "text/plain; charset=utf-8",
                content.getBytes(StandardCharsets.UTF_8));
    }

    /** 流式写（大文件，如 rootfs 28MB）。只尝试 MediaStore；失败返回 null（调用方自行回退/提示） */
    public static String writeStream(Context ctx, String sub, String name, String mime, InputStream in) {
        if (ctx == null || name == null || in == null) {
            return null;
        }
        return writeViaMediaStore(ctx, sub, name, mime, in);
    }

    private static String writeViaMediaStore(Context ctx, String sub, String name, String mime, InputStream in) {
        try {
            String relPath = ROOT_REL + "/" + sub + "/";
            String mimeType = (mime == null || mime.isEmpty()) ? guessMime(name) : mime;
            ContentResolver cr = ctx.getContentResolver();
            deleteViaMediaStore(cr, relPath, name);
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            v.put(MediaStore.Downloads.RELATIVE_PATH, relPath);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                Log.w(TAG, "MediaStore insert 失败: " + relPath + name);
                return null;
            }
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) {
                    Log.w(TAG, "MediaStore openOutputStream 失败: " + uri);
                    return null;
                }
                byte[] buf = new byte[262144];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
            }
            Log.i(TAG, "MediaStore 写入成功: " + relPath + name);
            return "内部存储/" + relPath + name;
        } catch (Exception e) {
            Log.w(TAG, "MediaStore 写失败(" + name + "): " + e.getMessage());
            return null;
        }
    }

    private static String writeViaFile(Context ctx, String sub, String name, InputStream in) {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "OilQuiz/" + sub);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "创建目录失败: " + dir.getAbsolutePath());
                return null;
            }
            File f = new File(dir, name);
            try (OutputStream os = new FileOutputStream(f)) {
                byte[] buf = new byte[262144];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "File 写失败(" + name + "): " + e.getMessage());
            return null;
        }
    }

    // ---------- 读 ----------

    /** 读文件尾部文本（最长 maxBytes），读不到返回空串。File 优先 → MediaStore */
    public static String readTail(Context ctx, String sub, String name, int maxBytes) {
        if (ctx == null || name == null) {
            return "";
        }
        try {
            File f = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "OilQuiz/" + sub + "/" + name);
            if (f.exists() && f.length() > 0) {
                int cap = (int) Math.min(f.length(), maxBytes);
                byte[] all = new byte[cap];
                try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                    raf.seek(Math.max(0, f.length() - cap));
                    raf.readFully(all);
                }
                return new String(all, StandardCharsets.UTF_8);
            }
        } catch (Exception ignore) {
        }
        try {
            Uri uri = findUri(ctx, sub, name);
            if (uri == null) {
                return "";
            }
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return "";
                }
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                byte[] all = bos.toByteArray();
                int cap = Math.min(all.length, maxBytes);
                return new String(all, all.length - cap, cap, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Log.w(TAG, "readTail 失败(" + name + "): " + e.getMessage());
            return "";
        }
    }

    /** 文件大小（字节）；不存在/读不到返回 0 */
    public static long size(Context ctx, String sub, String name) {
        if (ctx == null || name == null) {
            return 0;
        }
        try {
            File f = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "OilQuiz/" + sub + "/" + name);
            if (f.isFile()) {
                return f.length();
            }
        } catch (Exception ignore) {
        }
        try {
            Uri uri = findUri(ctx, sub, name);
            if (uri == null) {
                return 0;
            }
            String[] proj = {MediaStore.MediaColumns.SIZE};
            try (android.database.Cursor c = ctx.getContentResolver()
                    .query(uri, proj, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    return c.getLong(0);
                }
            }
        } catch (Exception ignore) {
        }
        return 0;
    }

    /** 是否真实存在（文件 > 0 字节） */
    public static boolean exists(Context ctx, String sub, String name) {
        return size(ctx, sub, name) > 0;
    }

    /** 删除（File + MediaStore 双删） */
    public static boolean delete(Context ctx, String sub, String name) {
        boolean any = false;
        try {
            File f = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "OilQuiz/" + sub + "/" + name);
            if (f.exists() && f.delete()) {
                any = true;
            }
        } catch (Exception ignore) {
        }
        try {
            String relPath = ROOT_REL + "/" + sub + "/";
            deleteViaMediaStore(ctx.getContentResolver(), relPath, name);
            any = true;
        } catch (Exception ignore) {
        }
        return any;
    }

    // ---------- 内部 ----------

    private static void deleteViaMediaStore(ContentResolver cr, String relPath, String name) {
        try {
            String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                    + MediaStore.Downloads.RELATIVE_PATH + "=?";
            cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, selection,
                    new String[]{name, relPath});
            cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, selection,
                    new String[]{name, relPath.substring(0, relPath.length() - 1)});
        } catch (Exception ignore) {
        }
    }

    /** 查询第一个匹配 uri（RELATIVE_PATH 带/不带尾斜杠双兼容） */
    private static Uri findUri(Context ctx, String sub, String name) {
        try {
            String relPath = ROOT_REL + "/" + sub + "/";
            String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                    + MediaStore.Downloads.RELATIVE_PATH + "=?";
            ContentResolver cr = ctx.getContentResolver();
            String[] proj = {MediaStore.Downloads._ID};
            try (android.database.Cursor c = cr.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, selection,
                    new String[]{name, relPath}, null)) {
                if (c != null && c.moveToFirst()) {
                    return android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
                }
            }
            try (android.database.Cursor c = cr.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, selection,
                    new String[]{name, relPath.substring(0, relPath.length() - 1)}, null)) {
                if (c != null && c.moveToFirst()) {
                    return android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "findUri 失败(" + name + "): " + e.getMessage());
        }
        return null;
    }

    /** MIME 猜测（复用导出器逻辑） */
    public static String guessMime(String name) {
        if (name == null) {
            return "application/octet-stream";
        }
        return ExportFileSaver.guessMimeType(name);
    }
}
