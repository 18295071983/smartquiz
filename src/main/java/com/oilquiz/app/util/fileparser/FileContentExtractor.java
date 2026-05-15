package com.oilquiz.app.util.fileparser;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;

import com.oilquiz.app.manager.OCRManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
                if (mimeType == null) {
                    return "无法确定文件类型";
                }

                if (mimeType.startsWith("text")) {
                    return extractTextFromUri(fileUri);
                } else if (mimeType.startsWith("image")) {
                    return extractTextFromImage(fileUri);
                } else if (mimeType.equals("application/pdf")) {
                    return "PDF文件需要特殊处理";
                } else if (mimeType.equals("application/msword") || mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
                    return "Word文件需要特殊处理";
                } else if (mimeType.equals("application/vnd.ms-excel") || mimeType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) {
                    return "Excel文件需要特殊处理";
                } else {
                    return "不支持的文件类型: " + mimeType;
                }
            } catch (Exception e) {
                return "文件解析失败: " + e.getMessage();
            }
        });
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
        
        ocrManager.processImage(bitmap, new OCRManager.OCRCallback() {
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
            lock.wait(10000);
        }
        
        return extractedText.length() > 0 ? extractedText.toString() : "OCR未识别到文本";
    }

    private Bitmap loadOriginalImage(Uri uri) {
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            if (inputStream == null) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(inputStream, null, options);
            inputStream.close();

            int originalWidth = options.outWidth;
            int originalHeight = options.outHeight;

            inputStream = context.getContentResolver().openInputStream(uri);
            if (inputStream == null) return null;

            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;

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

            if (bitmap == null) return null;

            bitmap = rotateImageIfNeeded(uri, bitmap);
            return bitmap;
        } catch (Exception e) {
            return null;
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
