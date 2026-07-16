package com.oilquiz.app.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 图片生成工具类
 * 用于生成各种格式的图片文件
 */
public class ImageGeneratorUtil {
    private static final String TAG = "ImageGeneratorUtil";

    /**
     * 图片格式枚举
     */
    public enum ImageFormat {
        JPEG(Bitmap.CompressFormat.JPEG, 85),
        PNG(Bitmap.CompressFormat.PNG, 100),
        WEBP(Bitmap.CompressFormat.WEBP, 85);

        final Bitmap.CompressFormat format;
        final int quality;

        ImageFormat(Bitmap.CompressFormat format, int quality) {
            this.format = format;
            this.quality = quality;
        }
    }

    /**
     * 保存Bitmap为图片文件
     * @param bitmap Bitmap对象
     * @param file 目标文件
     * @param format 图片格式
     * @return 是否成功
     */
    public static boolean saveBitmap(Bitmap bitmap, File file, ImageFormat format) {
        return saveBitmap(bitmap, file, format, format.quality);
    }

    /**
     * 保存Bitmap为图片文件（指定质量）
     * @param bitmap Bitmap对象
     * @param file 目标文件
     * @param format 图片格式
     * @param quality 质量（0-100）
     * @return 是否成功
     */
    public static boolean saveBitmap(Bitmap bitmap, File file, ImageFormat format, int quality) {
        if (bitmap == null || bitmap.isRecycled()) {
            Log.e(TAG, "Bitmap为空或已回收");
            return false;
        }

        // 确保父目录存在
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(file)) {
            boolean success = bitmap.compress(format.format, quality, fos);
            fos.flush();
            
            if (success) {
                Log.i(TAG, "图片保存成功: " + file.getAbsolutePath() + 
                      ", 格式: " + format.name() + ", 尺寸: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            } else {
                Log.e(TAG, "图片压缩失败");
            }
            
            return success;
        } catch (IOException e) {
            Log.e(TAG, "保存图片失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 保存Bitmap为JPEG文件
     * @param bitmap Bitmap对象
     * @param file 目标文件
     * @return 是否成功
     */
    public static boolean saveAsJpeg(Bitmap bitmap, File file) {
        return saveBitmap(bitmap, file, ImageFormat.JPEG);
    }

    /**
     * 保存Bitmap为PNG文件
     * @param bitmap Bitmap对象
     * @param file 目标文件
     * @return 是否成功
     */
    public static boolean saveAsPng(Bitmap bitmap, File file) {
        return saveBitmap(bitmap, file, ImageFormat.PNG);
    }

    /**
     * 生成纯色背景图片
     * @param file 目标文件
     * @param width 宽度
     * @param height 高度
     * @param color 颜色
     * @param format 图片格式
     * @return 是否成功
     */
    public static boolean generateSolidColorImage(File file, int width, int height, 
                                                    int color, ImageFormat format) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(color);
            boolean success = saveBitmap(bitmap, file, format);
            bitmap.recycle();
            return success;
        } catch (Exception e) {
            Log.e(TAG, "生成纯色背景图片失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 生成带文字的图片
     * @param file 目标文件
     * @param width 宽度
     * @param height 高度
     * @param backgroundColor 背景颜色
     * @param text 文字内容
     * @param textColor 文字颜色
     * @param textSize 文字大小
     * @param format 图片格式
     * @return 是否成功
     */
    public static boolean generateTextImage(File file, int width, int height, 
                                             int backgroundColor, String text, 
                                             int textColor, float textSize, ImageFormat format) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(backgroundColor);

            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTextSize(textSize);
            paint.setAntiAlias(true);
            paint.setTextAlign(Paint.Align.CENTER);

            float x = width / 2f;
            float y = height / 2f - ((paint.descent() + paint.ascent()) / 2f);

            if (text != null) {
                String[] lines = text.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    canvas.drawText(lines[i], x, y + i * (paint.descent() - paint.ascent()), paint);
                }
            }

            boolean success = saveBitmap(bitmap, file, format);
            bitmap.recycle();
            return success;
        } catch (Exception e) {
            Log.e(TAG, "生成文字图片失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 缩放图片
     * @param sourceBitmap 原始Bitmap
     * @param newWidth 新宽度
     * @param newHeight 新高度
     * @return 缩放后的Bitmap
     */
    public static Bitmap scaleBitmap(Bitmap sourceBitmap, int newWidth, int newHeight) {
        if (sourceBitmap == null || sourceBitmap.isRecycled()) {
            return null;
        }
        return Bitmap.createScaledBitmap(sourceBitmap, newWidth, newHeight, true);
    }

    /**
     * 裁剪图片
     * @param sourceBitmap 原始Bitmap
     * @param x 起始X坐标
     * @param y 起始Y坐标
     * @param width 裁剪宽度
     * @param height 裁剪高度
     * @return 裁剪后的Bitmap
     */
    public static Bitmap cropBitmap(Bitmap sourceBitmap, int x, int y, int width, int height) {
        if (sourceBitmap == null || sourceBitmap.isRecycled()) {
            return null;
        }
        return Bitmap.createBitmap(sourceBitmap, x, y, width, height);
    }

    /**
     * 旋转图片
     * @param sourceBitmap 原始Bitmap
     * @param degrees 旋转角度
     * @return 旋转后的Bitmap
     */
    public static Bitmap rotateBitmap(Bitmap sourceBitmap, float degrees) {
        if (sourceBitmap == null || sourceBitmap.isRecycled()) {
            return null;
        }
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(sourceBitmap, 0, 0, 
                sourceBitmap.getWidth(), sourceBitmap.getHeight(), matrix, true);
    }
}
