package com.oilquiz.app.resource;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 文件资源提供者
 * 通过系统资源接口动态获取文件导入导出功能配置
 */
public class FileResourceProvider {

    private static final String TAG = "FileResourceProvider";
    private static final String AUTHORITY = "com.oilquiz.app.fileprovider";
    private static FileResourceProvider instance;
    private Context context;

    // 支持的文件类型
    public static final String[] SUPPORTED_IMPORT_FORMATS = {
            "xlsx", "xls", "csv", "json", "xml", "txt", "md", "docx", "pdf"
    };

    public static final String[] SUPPORTED_EXPORT_FORMATS = {
            "xlsx", "csv", "json", "xml", "txt", "md", "html", "pdf", "docx"
    };

    private FileResourceProvider(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized FileResourceProvider getInstance(Context context) {
        if (instance == null) {
            instance = new FileResourceProvider(context);
        }
        return instance;
    }

    // ==================== 文件路径管理 ====================

    /**
     * 获取应用私有目录
     */
    public File getAppDirectory(String subDir) {
        File dir = new File(context.getFilesDir(), subDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * 获取缓存目录
     */
    public File getCacheDirectory(String subDir) {
        File dir = new File(context.getCacheDir(), subDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * 获取外部存储目录
     */
    public File getExternalDirectory(String subDir) {
        File dir = new File(context.getExternalFilesDir(null), subDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * 获取导入目录
     */
    public File getImportDirectory() {
        return getExternalDirectory("imports");
    }

    /**
     * 获取导出目录
     */
    public File getExportDirectory() {
        return getExternalDirectory("exports");
    }

    /**
     * 获取临时目录
     */
    public File getTempDirectory() {
        return getCacheDirectory("temp");
    }

    // ==================== 文件操作 ====================

    /**
     * 创建临时文件
     */
    public File createTempFile(String prefix, String suffix) throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = prefix + "_" + timeStamp + suffix;
        return new File(getTempDirectory(), fileName);
    }

    /**
     * 创建导出文件
     */
    public File createExportFile(String fileName) {
        return new File(getExportDirectory(), fileName);
    }

    /**
     * 复制文件
     */
    public boolean copyFile(File source, File dest) {
        FileChannel sourceChannel = null;
        FileChannel destChannel = null;
        try {
            sourceChannel = new FileInputStream(source).getChannel();
            destChannel = new FileOutputStream(dest).getChannel();
            destChannel.transferFrom(sourceChannel, 0, sourceChannel.size());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error copying file", e);
            return false;
        } finally {
            try {
                if (sourceChannel != null) sourceChannel.close();
                if (destChannel != null) destChannel.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing channels", e);
            }
        }
    }

    /**
     * 从URI复制到文件
     */
    public boolean copyFromUri(Uri sourceUri, File destFile) {
        try (InputStream is = context.getContentResolver().openInputStream(sourceUri);
             OutputStream os = new FileOutputStream(destFile)) {
            if (is == null) return false;
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error copying from URI", e);
            return false;
        }
    }

    /**
     * 获取文件扩展名
     */
    public String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }

    /**
     * 检查是否是支持的导入格式
     */
    public boolean isSupportedImportFormat(String fileName) {
        String ext = getFileExtension(fileName);
        for (String format : SUPPORTED_IMPORT_FORMATS) {
            if (format.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否是支持的导出格式
     */
    public boolean isSupportedExportFormat(String format) {
        for (String supported : SUPPORTED_EXPORT_FORMATS) {
            if (supported.equalsIgnoreCase(format)) {
                return true;
            }
        }
        return false;
    }

    // ==================== URI处理 ====================

    /**
     * 获取文件URI
     */
    public Uri getFileUri(File file) {
        return FileProvider.getUriForFile(context, AUTHORITY, file);
    }

    /**
     * 从URI获取文件路径
     */
    @Nullable
    public String getPathFromUri(Uri uri) {
        if (uri == null) return null;

        // 处理文件URI
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }

        // 处理内容URI
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            return getPathFromContentUri(uri);
        }

        return null;
    }

    /**
     * 从内容URI获取路径
     */
    @Nullable
    private String getPathFromContentUri(Uri uri) {
        String[] projection = {MediaStore.MediaColumns.DATA};
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
                return cursor.getString(columnIndex);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting path from content URI", e);
        }
        return null;
    }

    /**
     * 从URI获取文件名
     */
    public String getFileNameFromUri(Uri uri) {
        String result = null;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        result = cursor.getString(index);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result;
    }

    /**
     * 从URI获取文件大小
     */
    public long getFileSizeFromUri(Uri uri) {
        long size = 0;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (index >= 0) {
                        size = cursor.getLong(index);
                    }
                }
            }
        }
        return size;
    }

    // ==================== MIME类型 ====================

    /**
     * 获取文件的MIME类型
     */
    public String getMimeType(String fileName) {
        String extension = getFileExtension(fileName);
        switch (extension) {
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls":
                return "application/vnd.ms-excel";
            case "csv":
                return "text/csv";
            case "json":
                return "application/json";
            case "xml":
                return "application/xml";
            case "txt":
                return "text/plain";
            case "md":
                return "text/markdown";
            case "html":
            case "htm":
                return "text/html";
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
            case "gif":
                return "image/gif";
            default:
                return "application/octet-stream";
        }
    }

    // ==================== 文件清理 ====================

    /**
     * 清理临时文件
     */
    public void clearTempFiles() {
        File tempDir = getTempDirectory();
        File[] files = tempDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
    }

    /**
     * 清理旧文件
     * @param maxAge 最大保留时间（毫秒）
     */
    public void clearOldFiles(File directory, long maxAge) {
        long now = System.currentTimeMillis();
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (now - file.lastModified() > maxAge) {
                    file.delete();
                }
            }
        }
    }

    /**
     * 获取文件大小（格式化）
     */
    public String getFormattedFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.2f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.2f MB", size / (1024.0 * 1024));
        } else {
            return String.format(Locale.getDefault(), "%.2f GB", size / (1024.0 * 1024 * 1024));
        }
    }
}
