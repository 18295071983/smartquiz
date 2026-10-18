package com.oilquiz.app.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * 图片解析工具类
 * 用于解析和处理图片文件
 */
public class ImageParserUtil {
    private static final String TAG = "ImageParserUtil";

    /**
     * 解析图片为Bitmap
     * @param file 图片文件
     * @return Bitmap对象
     */
    public static Bitmap parseImage(File file) {
        return parseImage(file, 0, 0);
    }

    /**
     * 解析图片为Bitmap（指定最大尺寸）
     * @param file 图片文件
     * @param maxWidth 最大宽度
     * @param maxHeight 最大高度
     * @return Bitmap对象
     */
    public static Bitmap parseImage(File file, int maxWidth, int maxHeight) {
        if (file == null || !file.exists()) {
            Log.e(TAG, "图片文件不存在: " + (file != null ? file.getAbsolutePath() : "null"));
            return null;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            // 先获取图片尺寸
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(fis, null, options);

            // 计算缩放比例
            int sampleSize = calculateSampleSize(options, maxWidth, maxHeight);
            options.inSampleSize = sampleSize;
            options.inJustDecodeBounds = false;

            // 重新读取图片
            fis.getChannel().position(0);
            Bitmap bitmap = BitmapFactory.decodeStream(fis, null, options);

            // 修正图片方向
            bitmap = correctImageOrientation(file, bitmap);

            Log.i(TAG, "图片解析成功: " + file.getAbsolutePath() + 
                  ", 尺寸: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            return bitmap;
        } catch (IOException e) {
            Log.e(TAG, "解析图片失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 计算采样大小
     */
    private static int calculateSampleSize(BitmapFactory.Options options, int maxWidth, int maxHeight) {
        if (maxWidth <= 0 || maxHeight <= 0) {
            return 1;
        }

        int width = options.outWidth;
        int height = options.outHeight;
        int sampleSize = 1;

        while (width / sampleSize > maxWidth || height / sampleSize > maxHeight) {
            sampleSize *= 2;
        }

        return sampleSize;
    }

    /**
     * 修正图片方向
     */
    private static Bitmap correctImageOrientation(File file, Bitmap bitmap) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, 
                    ExifInterface.ORIENTATION_NORMAL);

            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    matrix.postRotate(90);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    matrix.postRotate(180);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    matrix.postRotate(270);
                    break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    matrix.postScale(-1, 1);
                    break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    matrix.postScale(1, -1);
                    break;
                default:
                    return bitmap;
            }

            return Bitmap.createBitmap(bitmap, 0, 0, 
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        } catch (IOException e) {
            Log.e(TAG, "修正图片方向失败: " + e.getMessage(), e);
            return bitmap;
        }
    }

    /**
     * 获取图片尺寸
     * @param file 图片文件
     * @return 尺寸数组 [width, height]
     */
    public static int[] getImageSize(File file) {
        if (file == null || !file.exists()) {
            return new int[]{0, 0};
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(fis, null, options);
            return new int[]{options.outWidth, options.outHeight};
        } catch (IOException e) {
            Log.e(TAG, "获取图片尺寸失败: " + e.getMessage(), e);
            return new int[]{0, 0};
        }
    }

    /**
     * 获取图片MIME类型：先按文件头字节嗅探（扩展名可能与实际编码不符），
     * 嗅探不出来再退回按扩展名。
     * @param file 图片文件
     * @return MIME类型
     */
    public static String getImageMimeType(File file) {
        if (file == null) return "image/*";
        String sniffed = com.oilquiz.app.ai.util.ImagePreprocessUtil.sniffMimeType(file);
        if (!"application/octet-stream".equals(sniffed)) {
            return sniffed;
        }
        String extension = FileParserUtil.getFileExtension(file);
        switch (extension.toLowerCase()) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "webp":
                return "image/webp";
            case "heic":
            case "heif":
                return "image/heic";
            default:
                return "image/*";
        }
    }

    /**
     * 判断是否为图片文件
     * @param file 文件
     * @return 是否为图片
     */
    public static boolean isImageFile(File file) {
        if (file == null) return false;
        return isImageExtension(FileParserUtil.getFileExtension(file));
    }

    /**
     * 判断路径/URI 是否指向图片（供"点缩略图走应用内预览"这类按后缀决策使用）。
     * 后缀集合与 AttachmentPreParser 的图片判定一致，并含 HEIC/HEIF —— Android 10+ 相机默认产物，
     * 之前按后缀判断的地方漏掉它，导致 HEIC 图片附件点击后被丢给系统 VIEW 而报"没有可打开的页面"。
     */
    public static boolean isImagePath(String pathOrUri) {
        if (pathOrUri == null || pathOrUri.isEmpty()) return false;
        String path = pathOrUri.startsWith("file://") ? android.net.Uri.parse(pathOrUri).getPath() : pathOrUri;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (slash >= 0 && slash < path.length() - 1) path = path.substring(slash + 1);
        int dot = path.lastIndexOf('.');
        String ext = dot > 0 ? path.substring(dot + 1) : "";
        return isImageExtension(ext);
    }

    private static boolean isImageExtension(String extension) {
        if (extension == null) return false;
        String[] imageExtensions = {"jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif",
                "heic", "heif", "avif"};
        for (String candidate : imageExtensions) {
            if (candidate.equalsIgnoreCase(extension)) {
                return true;
            }
        }
        return false;
    }
}
