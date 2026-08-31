package com.oilquiz.app.util.fileparser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;

import com.oilquiz.app.manager.OCRManager;
import com.oilquiz.app.util.AdvancedFileParserUtil;
import com.oilquiz.app.util.OfficeParserUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class FileContentExtractor {

    private final Context context;
    private final OCRManager ocrManager;

    public FileContentExtractor(Context context) {
        this.context = context;
        this.ocrManager = new OCRManager(context);
    }

    public CompletableFuture<String> extractContent(Uri fileUri) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String mimeType = context.getContentResolver().getType(fileUri);
                // ⚠ Uri.fromFile(file) 场景拿不到mimeType时，必须回退到扩展名判断
                if (mimeType == null || mimeType.isEmpty()) {
                    mimeType = guessMimeTypeFromExtension(fileUri);
                }
                if (mimeType == null || mimeType.isEmpty()) {
                    return "无法确定文件类型";
                }

                if (mimeType.startsWith("text")) {
                    return extractTextFromUri(fileUri);
                } else if (mimeType.startsWith("image")) {
                    return extractTextFromImage(fileUri);
                } else if (mimeType.equals("application/pdf")) {
                    return extractPdfContent(fileUri);
                } else if (isWordMime(mimeType)) {
                    return extractWordContent(fileUri);
                } else if (isExcelMime(mimeType)) {
                    return extractExcelContent(fileUri);
                } else if (mimeType.equals("application/json")) {
                    return extractTextFromUri(fileUri);
                } else if (isZipMime(mimeType)) {
                    return extractZipContent(fileUri);
                } else if (mimeType.equals("text/html")) {
                    return extractHtmlContent(fileUri);
                } else {
                    return "不支持的文件类型: " + mimeType;
                }
            } catch (Exception e) {
                return "文件解析失败: " + e.getMessage();
            }
        });
    }

    /** 从 Uri 的最后一段路径推断 MIME 类型（应对 ContentResolver.getType() == null 的场景） */
    private String guessMimeTypeFromExtension(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return null;
        String lower = name.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".doc"))  return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xls"))  return "application/vnd.ms-excel";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".zip"))  return "application/zip";
        if (lower.endsWith(".htm") || lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv")) return "text/plain";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) {
            return "image/*";
        }
        return null;
    }

    private static boolean isExcelMime(String m) {
        return "application/vnd.ms-excel".equals(m)
                || "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(m);
    }
    private static boolean isWordMime(String m) {
        return "application/msword".equals(m)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(m);
    }
    private static boolean isZipMime(String m) {
        return "application/zip".equals(m) || "application/x-zip-compressed".equals(m);
    }

    private File saveUriToTempFile(Uri uri, String extension) {
        try {
            File cacheDir = context.getCacheDir();
            String fileName = "temp_extract_" + System.currentTimeMillis() + extension;
            File tempFile = new File(cacheDir, fileName);

            try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
                 FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                if (inputStream == null) {
                    return null;
                }
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.flush();
            }
            return tempFile;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractPdfContent(Uri uri) {
        File tempFile = saveUriToTempFile(uri, ".pdf");
        if (tempFile == null) {
            return "PDF文件需要特殊处理";
        }
        try {
            String content = AdvancedFileParserUtil.parsePdfToText(tempFile);
            if (content != null && !content.trim().isEmpty()) {
                return content;
            }
            return "PDF解析失败，请检查文件是否损坏";
        } finally {
            tempFile.delete();
        }
    }

    private String extractWordContent(Uri uri) {
        File tempFile = saveUriToTempFile(uri, ".docx");
        if (tempFile == null) {
            return "Word文件需要特殊处理";
        }
        try {
            String content = OfficeParserUtil.parseWordToText(tempFile);
            if (content != null && !content.trim().isEmpty()) {
                return content;
            }
            return "Word文件需要特殊处理";
        } finally {
            tempFile.delete();
        }
    }

    private String extractExcelContent(Uri uri) {
        // 根据原始文件名确定扩展名（xls vs xlsx）
        String fileName = getFileName(uri);
        String ext = ".xlsx";
        if (fileName != null && fileName.toLowerCase().endsWith(".xls") && !fileName.toLowerCase().endsWith(".xlsx")) {
            ext = ".xls";
        }
        File tempFile = saveUriToTempFile(uri, ext);
        if (tempFile == null) {
            return "Excel文件需要特殊处理";
        }
        try {
            List<String[]> data = OfficeParserUtil.parseExcelFirstSheet(tempFile);
            if (data != null && !data.isEmpty()) {
                String markdown = formatExcelData(data);
                // ========== 诊断：Markdown输出节点 ==========
                com.oilquiz.app.util.ImportDebugTracer.trace("【3】FileContentExtractor-Format后",
                        markdown.substring(0, Math.min(500, markdown.length())));
                return markdown;
            }
            return "Excel解析失败，请检查文件是否损坏";
        } finally {
            tempFile.delete();
        }
    }

    private String formatExcelData(List<String[]> data) {
        if (data == null || data.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int rowCount = 0;
        for (String[] row : data) {
            if (rowCount > 1000) {
                sb.append("\n... (数据过多，已截断)\n");
                break;
            }
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    sb.append("\t");
                }
                // 替换单元格内的换行符为空格，避免破坏 Tab 分隔表格行结构
                String cellVal = row[i];
                if (cellVal != null) {
                    cellVal = cellVal.replace("\r\n", " ").replace("\n", " ").replace("\r", " ");
                }
                sb.append(cellVal != null ? cellVal : "");
            }
            sb.append("\n");
            rowCount++;
        }
        return sb.toString();
    }

    private String extractZipContent(Uri uri) {
        File tempFile = saveUriToTempFile(uri, ".zip");
        if (tempFile == null) {
            return "不支持的文件类型: application/zip";
        }
        try {
            List<java.util.Map<String, Object>> contents = AdvancedFileParserUtil.listZipContents(tempFile);
            if (contents != null && !contents.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("ZIP文件内容列表:\n");
                for (java.util.Map<String, Object> item : contents) {
                    String name = (String) item.get("name");
                    Object sizeObj = item.get("size");
                    sb.append("  - ").append(name);
                    if (sizeObj != null) {
                        long size = sizeObj instanceof Long ? (Long) sizeObj : ((Number) sizeObj).longValue();
                        sb.append(" (").append(formatFileSize(size)).append(")\n");
                    } else {
                        sb.append("\n");
                    }
                }
                return sb.toString();
            }
            return "ZIP文件内容列表:\n(无法详细解析ZIP文件内容)";
        } finally {
            tempFile.delete();
        }
    }

    private String extractHtmlContent(Uri uri) {
        File tempFile = saveUriToTempFile(uri, ".html");
        if (tempFile == null) {
            try {
                return extractTextFromUri(uri);
            } catch (IOException e) {
                return "文件解析失败: " + e.getMessage();
            }
        }
        try {
            String content = AdvancedFileParserUtil.parseHtmlToText(tempFile);
            if (content != null && !content.trim().isEmpty()) {
                return content;
            }
            try {
                return extractTextFromUri(uri);
            } catch (IOException e) {
                return "文件解析失败: " + e.getMessage();
            }
        } finally {
            tempFile.delete();
        }
    }

    private String extractTextFromUri(Uri uri) throws IOException {
        StringBuilder content = new StringBuilder();
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        return content.toString();
    }

    private static final int MAX_OCR_IMAGE_DIMENSION = 4096;

    private String extractTextFromImage(Uri imageUri) throws Exception {
        Bitmap bitmap = loadOriginalImage(imageUri);
        if (bitmap == null) {
            return "无法加载图片";
        }

        StringBuilder extractedText = new StringBuilder();
        final Object lock = new Object();
        
        // 优先使用在线视觉模型 OCR，失败回退本地高精度 OCR（PP-OCRv6），最终 ML Kit 兜底
        ocrManager.processImageOnlineFirst(bitmap, new OCRManager.OCRCallback() {
            @Override
            public void onSuccess(String text) {
                synchronized (lock) {
                    extractedText.append(text).append("\n");
                    lock.notify();
                }
            }
            
            @Override
            public void onFailure(String error) {
                synchronized (lock) {
                    extractedText.append("OCR识别失败: " + error);
                    lock.notify();
                }
            }
        });

        synchronized (lock) {
            lock.wait(60000); // 在线 OCR 可能需要更长时间
        }
        
        return extractedText.length() > 0 ? extractedText.toString() : "OCR未识别到文本";
    }

    private Bitmap loadOriginalImage(Uri uri) {
        InputStream inputStream = null;
        try {
            // 首先检查文件大小
            long fileSize = getFileSize(uri);
            if (fileSize > 20 * 1024 * 1024) { // 20MB限制
                return null;
            }

            inputStream = context.getContentResolver().openInputStream(uri);
            if (inputStream == null) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(inputStream, null, options);
            inputStream.close();
            inputStream = null;

            int originalWidth = options.outWidth;
            int originalHeight = options.outHeight;

            // 检查图片尺寸是否合理
            if (originalWidth <= 0 || originalHeight <= 0) {
                return null;
            }

            inputStream = context.getContentResolver().openInputStream(uri);
            if (inputStream == null) return null;

            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;

            // 计算采样率以限制内存使用
            if (originalWidth > MAX_OCR_IMAGE_DIMENSION || originalHeight > MAX_OCR_IMAGE_DIMENSION) {
                int sampleSize = 1;
                while (originalWidth / sampleSize > MAX_OCR_IMAGE_DIMENSION
                        || originalHeight / sampleSize > MAX_OCR_IMAGE_DIMENSION) {
                    sampleSize *= 2;
                }
                options.inSampleSize = sampleSize;
            }

            Bitmap bitmap = BitmapFactory.decodeStream(inputStream, null, options);
            inputStream.close();
            inputStream = null;

            if (bitmap == null) return null;

            bitmap = rotateImageIfNeeded(uri, bitmap);
            return bitmap;
        } catch (Exception e) {
            return null;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private Bitmap rotateImageIfNeeded(Uri uri, Bitmap bitmap) {
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            if (inputStream == null) return bitmap;
            ExifInterface exif = new ExifInterface(inputStream);
            inputStream.close();

            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rotation = 0;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                default: return bitmap;
            }

            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (Exception e) {
            return bitmap;
        }
    }

    public String getFileInfo(Uri fileUri) {
        try {
            String mimeType = context.getContentResolver().getType(fileUri);
            String fileName = getFileName(fileUri);
            long fileSize = getFileSize(fileUri);

            return "文件信息:\n" +
                    "名称: " + fileName + "\n" +
                    "类型: " + (mimeType != null ? mimeType : "未知") + "\n" +
                    "大小: " + formatFileSize(fileSize);
        } catch (Exception e) {
            return "无法获取文件信息";
        }
    }

    private String getFileName(Uri uri) {
        String fileName = "未知文件";
        try {
            if (uri.getScheme().equals("content")) {
                String[] projection = {MediaStore.MediaColumns.DISPLAY_NAME};
                try (android.database.Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        fileName = cursor.getString(0);
                    }
                }
            } else if (uri.getScheme().equals("file")) {
                fileName = new File(uri.getPath()).getName();
            }
        } catch (Exception e) {
            // 忽略异常
        }
        return fileName;
    }

    private long getFileSize(Uri uri) throws IOException {
        if (uri.getScheme().equals("content")) {
            try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
                return inputStream != null ? inputStream.available() : 0;
            }
        } else if (uri.getScheme().equals("file")) {
            File file = new File(uri.getPath());
            return file.length();
        }
        return 0;
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    /**
     * 释放资源
     */
    public void release() {
        if (ocrManager != null) {
            ocrManager.release();
        }
    }
}
