package com.oilquiz.app.util.export;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 导出文件保存器。
 * <p>
 * 将导出器生成的文件复制到系统「下载/OilQuiz」目录（公共存储，文件管理器可见可编辑），
 * 替代旧的私有目录（cacheDir/exports，用户无法查看）。
 * <p>
 * API 29+ 走 MediaStore Downloads；API 28- 走公共 Download 目录。
 */
public final class ExportFileSaver {

    private static final String TAG = "ExportFileSaver";

    /** MediaStore 相对子目录：Download/OilQuiz */
    private static final String RELATIVE_SUB_DIR = "OilQuiz";

    private ExportFileSaver() {
    }

    /**
     * 将导出文件复制到公共「下载/OilQuiz」目录，确保文件管理器可见可编辑。
     * <p>
     * 支持多种文件格式，包括 HTML 文件。
     *
     * @param context  上下文
     * @param srcFile  导出器生成的临时文件
     * @param mimeType MIME 类型（如 application/vnd...sheet 或 text/html），可为 null
     * @return 用户可见的保存路径描述（如 "内部存储/Download/OilQuiz/导出题目_xxx.xlsx"）；
     *         失败返回 null（原始文件仍在应用缓存目录可分享）
     */
    public static String copyToDownloads(Context context, File srcFile, String mimeType) {
        if (context == null || srcFile == null || !srcFile.exists()) {
            return null;
        }
        try {
            byte[] bytes = readAllBytes(srcFile);
            if (bytes == null || bytes.length == 0) {
                Log.w(TAG, "源文件为空: " + srcFile.getAbsolutePath());
                return null;
            }
            String fileName = srcFile.getName();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return writeToMediaStore(context, fileName, mimeType, bytes);
            } else {
                return writeToLegacyDownloads(fileName, bytes);
            }
        } catch (Throwable e) {
            Log.e(TAG, "保存导出文件失败: " + e.getMessage(), e);
            return null;
        }
    }

    private static byte[] readAllBytes(File file) throws Exception {
        try (InputStream is = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        }
    }

    /** API 29+：通过 MediaStore 写入 Download/OilQuiz，文件管理器可见且可编辑 */
    private static String writeToMediaStore(Context context, String fileName, String mimeType,
                                            byte[] bytes) throws Exception {
        String relativePath = Environment.DIRECTORY_DOWNLOADS + File.separator + RELATIVE_SUB_DIR;

        // 先删除同名旧文件，避免重复生成 "(1)" 后缀副本
        try {
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                    + MediaStore.Downloads.RELATIVE_PATH + "=?";
            String[] args = {fileName, relativePath + File.separator};
            context.getContentResolver().delete(collection, selection, args);
            // 兼容部分设备 RELATIVE_PATH 末尾无分隔符的存储方式
            context.getContentResolver().delete(collection, selection, new String[]{fileName, relativePath});
        } catch (Exception ignore) {
            // 删除失败不影响后续插入
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        if (mimeType == null || mimeType.isEmpty()) {
            mimeType = guessMimeType(fileName);
        }
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);

        Uri uri = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            Log.w(TAG, "MediaStore 插入失败: " + fileName);
            return null;
        }
        try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
            if (os == null) {
                Log.w(TAG, "打开输出流失败: " + uri);
                return null;
            }
            os.write(bytes);
            os.flush();
        }
        Log.i(TAG, "导出文件保存成功(MediaStore): " + relativePath + "/" + fileName);
        return "内部存储/Download/" + RELATIVE_SUB_DIR + "/" + fileName;
    }

    /** API 28 及以下：直接写入公共 Download/OilQuiz 目录 */
    private static String writeToLegacyDownloads(String fileName, byte[] bytes) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), RELATIVE_SUB_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "创建下载目录失败: " + dir.getAbsolutePath());
            return null;
        }
        File out = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(bytes);
        }
        Log.i(TAG, "导出文件保存成功(File): " + out.getAbsolutePath());
        return out.getAbsolutePath();
    }

    /** 根据扩展名猜测 MIME 类型 */
    public static String guessMimeType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String ext = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        switch (ext) {
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls":
                return "application/vnd.ms-excel";
            case "csv":
                return "text/csv";
            case "json":
                return "application/json";
            case "html":
            case "htm":
                return "text/html";
            case "md":
                return "text/markdown";
            case "pdf":
                return "application/pdf";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "doc":
                return "application/msword";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "zip":
                return "application/zip";
            case "apk":
                return "application/vnd.android.package-archive";
            default:
                return "application/octet-stream";
        }
    }
}
