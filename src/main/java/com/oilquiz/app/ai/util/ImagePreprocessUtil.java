package com.oilquiz.app.ai.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 本地多模态图片预处理工具。
 *
 * 背景：原生层 mtmd 使用 stb_image 系解码器，只支持 jpg/png/bmp/gif 等格式，
 * 不支持 WebP / HEIC / AVIF；且原图全尺寸解码在手机端容易 OOM。
 *
 * 处理流程：采样解码（长边 ≤ MAX_VISION_EDGE）→ EXIF 方向矫正 → 重编码为 JPEG，
 * 再交给本地视觉模型。任一步失败时回退返回原文件路径，避免阻断上层流程。
 */
public final class ImagePreprocessUtil {

    private static final String TAG = "ImagePreprocessUtil";

    /** 视觉模型输入长边上限（与 OCR 路径解码保护一致） */
    private static final int MAX_VISION_EDGE = 2048;
    /** JPEG 压缩质量 */
    private static final int JPEG_QUALITY = 88;
    /** 超过该体积的图片不做整图解码，直接回退原文件（避免把超大图当普通图处理） */
    private static final long MAX_INPUT_FILE_SIZE = 20L * 1024 * 1024;

    private ImagePreprocessUtil() {
    }

    /**
     * 把任意本地图片预处理为可直接喂给本地视觉模型的 JPEG 文件路径。
     *
     * @param context 用于获取缓存目录
     * @param src     原始图片文件
     * @return 预处理后的 JPEG 绝对路径；任何异常时回退返回原文件路径（不会返回 null 或阻断流程）
     */
    public static String prepareVisionImage(Context context, File src) {
        if (src == null) return null;
        if (!src.exists()) return src.getAbsolutePath();
        try {
            // 超大文件不冒险整图解码，直接回退（native 侧还有 mtmd 自身的 token 上限保护）
            if (src.length() > MAX_INPUT_FILE_SIZE) {
                Log.w(TAG, "Image too large, pass through: " + src.getName());
                return src.getAbsolutePath();
            }

            Bitmap bitmap = decodeSampled(src);
            if (bitmap == null) {
                Log.w(TAG, "BitmapFactory cannot decode " + src.getName() + ", fallback to original");
                return src.getAbsolutePath();
            }
            bitmap = rotateByExif(src, bitmap);

            File dir = new File(context.getCacheDir(), "vision");
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Cannot create vision cache dir, fallback to original");
                bitmap.recycle();
                return src.getAbsolutePath();
            }
            File out = new File(dir, "vision_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)) {
                    Log.w(TAG, "JPEG encode failed, fallback to original");
                    bitmap.recycle();
                    return src.getAbsolutePath();
                }
            }
            bitmap.recycle();
            Log.i(TAG, "Preprocessed " + src.getName() + " -> " + out.getName()
                    + " (" + (out.length() / 1024) + "KB, edge<=2048)");
            return out.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "Preprocess failed, fallback to original: " + t.getMessage());
            return src.getAbsolutePath();
        }
    }

    /** 采样解码：先用 inJustDecodeBounds 读尺寸，按 MAX_VISION_EDGE 计算采样率，避免全尺寸解码 OOM */
    private static Bitmap decodeSampled(File file) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = new FileInputStream(file)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int sample = 1;
        while (bounds.outWidth / sample > MAX_VISION_EDGE
                || bounds.outHeight / sample > MAX_VISION_EDGE) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inSampleSize = sample;
        try (InputStream in = new FileInputStream(file)) {
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }

    /** EXIF 方向矫正（解决手机竖拍照片旋转问题） */
    private static Bitmap rotateByExif(File file, Bitmap bitmap) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rotation;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    rotation = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    rotation = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    rotation = 270;
                    break;
                default:
                    return bitmap;
            }
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) {
                bitmap.recycle();
            }
            return rotated;
        } catch (Throwable t) {
            return bitmap;
        }
    }
}
