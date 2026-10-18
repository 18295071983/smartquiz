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
 * 图片预处理与视觉请求编码工具。
 *
 * 背景：原生层 mtmd 使用 stb_image 系解码器，只支持 jpg/png/bmp/gif 等格式，
 * 不支持 WebP / HEIC / AVIF；且原图全尺寸解码在手机端容易 OOM。
 *
 * 两条出口共用一套解码/EXIF 矫正实现：
 * - {@link #prepareVisionImage} —— 本地 llama.cpp mtmd 视觉输入（JPEG 落盘，长边 2048）
 * - {@link #prepareForOnlineVision} —— 在线视觉模型请求体（长边 1568 + 预算内质量梯度，
 *   并回报与真实字节一致的 MIME，杜绝"PNG 标成 image/jpeg"）
 *
 * 阈值依据（各家官方文档取交集，详见 CHANGELOG 2026-10-18）：Anthropic 长边 >1568px 会被
 * 服务端等比缩小、单图 base64 ≤5MB；OpenAI 高细节档最短边 768 / 最长边 2048；Qwen-VL 建议
 * 边长对齐 32；Gemini inline 整请求 ≤20MB。预缩到 1568（=32×49）对所有目标无损。
 */
public final class ImagePreprocessUtil {

    private static final String TAG = "ImagePreprocessUtil";

    /** 本地 mtmd 视觉输入长边上限 */
    private static final int MAX_VISION_EDGE = 2048;
    /** 在线视觉请求长边上限（Anthropic 服务端缩放阈值，同时是 32 的整数倍） */
    public static final int ONLINE_VISION_MAX_EDGE = 1568;
    /** 预算兜底时的次档长边（=32×32） */
    private static final int ONLINE_VISION_FALLBACK_EDGE = 1024;
    /** JPEG 质量（本地路径固定档） */
    private static final int JPEG_QUALITY = 88;
    /** 在线视觉质量梯度：先按业界公认的 85 起步，超预算逐级降 */
    private static final int[] ONLINE_VISION_QUALITY_LADDER = {85, 75, 65};
    /**
     * 在线视觉单图预算（base64 之前的字节数）。
     * 取 2.4MB：base64 膨胀约 1.33 倍后 ≈3.2MB，稳落在 Anthropic 单图 5MB 内，
     * 5 张图的请求体也仍在 Gemini 20MB / 其余服务商常见限制之内。
     */
    public static final long ONLINE_VISION_BUDGET_BYTES = 2_400_000L;
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

            Bitmap bitmap = decodeSampled(src, MAX_VISION_EDGE);
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

    /** 采样解码：先用 inJustDecodeBounds 读尺寸，按 maxEdge 计算采样率，避免全尺寸解码 OOM */
    private static Bitmap decodeSampled(File file, int maxEdge) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = new FileInputStream(file)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int sample = 1;
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
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
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: {
                    Matrix m = new Matrix();
                    m.postScale(-1, 1);
                    return flip(bitmap, m);
                }
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: {
                    Matrix m = new Matrix();
                    m.postScale(1, -1);
                    return flip(bitmap, m);
                }
                default:
                    return bitmap;
            }
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            return flip(bitmap, matrix);
        } catch (Throwable t) {
            return bitmap;
        }
    }

    private static Bitmap flip(Bitmap bitmap, Matrix matrix) {
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) {
            bitmap.recycle();
        }
        return rotated;
    }

    // ==================== 在线视觉请求编码 ====================

    /** 一张喂给在线视觉模型的图片：字节 + 真实 MIME + base64 + 是否重编码过 + 是否落在预算内 */
    public static class PreparedVisionImage {
        /** 原始字节；仅以 base64 形式传入时可能为 null */
        public final byte[] bytes;
        public final String mimeType;
        public final String base64;
        /** true=我们重编码为 JPEG（尺寸/MIME 由本工具保证）；false=按真实字节嗅探后原样透传 */
        public final boolean reencoded;
        /** false 表示按预算兜底后仍超限（调用方决定是否降级为 OCR 文本） */
        public final boolean withinBudget;

        PreparedVisionImage(byte[] bytes, String mimeType, boolean reencoded, boolean withinBudget) {
            this.bytes = bytes;
            this.mimeType = mimeType;
            this.base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            this.reencoded = reencoded;
            this.withinBudget = withinBudget;
        }

        private PreparedVisionImage(String base64, String mimeType, boolean base64Only) {
            this.bytes = null;
            this.base64 = base64;
            this.mimeType = mimeType;
            this.reencoded = false;
            this.withinBudget = true;
        }

        /**
         * 包一层已经算好的 base64（工具入参、缓存命中等场景）。
         * mimeType 由调用方保证与真实字节一致 —— 拿不准时优先用
         * {@link #prepareForOnlineVision(File)} 而不是这个方法。
         */
        public static PreparedVisionImage ofBase64(String base64, String mimeType) {
            return new PreparedVisionImage(base64,
                    mimeType != null ? mimeType : "image/jpeg", true);
        }

        public int size() {
            if (bytes != null) return bytes.length;
            return base64 == null ? 0 : base64.length() * 3 / 4;
        }

        /**
         * data URI 形式（各家视觉接口的图片载荷都是"前缀必须等于真实 media_type"）。
         * 唯一出口，避免 chat / Responses / Anthropic 三条链路各拼一遍前缀而拼歪。
         */
        public String dataUrl() {
            return "data:" + (mimeType != null ? mimeType : "image/jpeg") + ";base64," + base64;
        }
    }

    /**
     * 把本地图片编码为可直接进请求体的视觉输入。
     *
     * 与旧实现（整文件读字节 + 硬编码 image/jpeg 前缀 + 超 4MB 直接放弃视觉）的三点区别：
     * 1. 先按长边 1568 降采样、应用 EXIF 方向，再按质量梯度 85→75→65 编 JPEG，超预算退一档长边，
     *    因此回报的 mimeType 与真实字节必然一致，且绝大多数手机原片不再需要整条链路降级；
     * 2. 解码失败时嗅探真实 MIME 原样透传（JPEG/PNG/WebP/GIF 各家都收），只有 HEIC/HEIF/TIFF
     *    这类"OpenAI 与 Anthropic 根本不支持的编码"才判定为不可用；
     * 3. 返回 withinBudget 标记而不是 boolean 成功与否，让调用方能区分"可发但偏大"与"发不了"。
     *
     * @return 编码结果；无法作为视觉输入时返回 null
     */
    public static PreparedVisionImage prepareForOnlineVision(File src) {
        if (src == null || !src.exists() || src.length() == 0) return null;
        try {
            Bitmap bitmap = decodeSampled(src, ONLINE_VISION_MAX_EDGE);
            if (bitmap != null) {
                bitmap = rotateByExif(src, bitmap);
                PreparedVisionImage encoded = encodeWithinLadder(bitmap, ONLINE_VISION_QUALITY_LADDER);
                if (encoded != null) {
                    if (encoded.withinBudget) {
                        bitmap.recycle();
                        return encoded;
                    }
                    // 预算仍超：按次档长边再缩一次重编（OpenAI 高细节档最短边 768 足够看清文字）
                    Bitmap smaller = scaleToMaxEdge(bitmap, ONLINE_VISION_FALLBACK_EDGE);
                    PreparedVisionImage retried = encodeWithinLadder(smaller, ONLINE_VISION_QUALITY_LADDER);
                    if (smaller != bitmap) smaller.recycle();
                    bitmap.recycle();
                    if (retried != null) {
                        return new PreparedVisionImage(retried.bytes, retried.mimeType, true,
                                retried.size() <= ONLINE_VISION_BUDGET_BYTES);
                    }
                    return new PreparedVisionImage(encoded.bytes, encoded.mimeType, true, false);
                }
                bitmap.recycle();
            }

            // 解码失败：按真实字节嗅探，可被各家接受的编码原样透传
            String sniffed = sniffMimeType(src);
            if (isProviderAgnosticFormat(sniffed)) {
                byte[] raw = java.nio.file.Files.readAllBytes(src.toPath());
                if (raw.length > MAX_INPUT_FILE_SIZE) {
                    Log.w(TAG, "Pass-through skipped, too large: " + src.getName());
                    return null;
                }
                return new PreparedVisionImage(raw, sniffed, false,
                        raw.length <= ONLINE_VISION_BUDGET_BYTES);
            }
            Log.w(TAG, "Cannot decode image for vision and format not portable: "
                    + src.getName() + " (" + sniffed + ")");
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "prepareForOnlineVision failed: " + t.getMessage());
            return null;
        }
    }

    /** 质量梯度编码：命中预算即返回，全部超预算时返回最后一档（最小）结果并标记 withinBudget=false */
    private static PreparedVisionImage encodeWithinLadder(Bitmap bitmap, int[] qualities) {
        byte[] last = null;
        for (int quality : qualities) {
            byte[] bytes = encodeJpeg(bitmap, quality);
            if (bytes == null || bytes.length == 0) continue;
            last = bytes;
            if (bytes.length <= ONLINE_VISION_BUDGET_BYTES) {
                return new PreparedVisionImage(bytes, "image/jpeg", false, true);
            }
        }
        if (last == null) return null;
        return new PreparedVisionImage(last, "image/jpeg", false, false);
    }

    private static byte[] encodeJpeg(Bitmap bitmap, int quality) {
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos)) return null;
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 等比缩到长边 ≤ maxEdge（保持 32 的整数倍，贴合 Qwen-VL factor=32 对齐建议） */
    private static Bitmap scaleToMaxEdge(Bitmap src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        long longEdge = Math.max(w, h);
        if (longEdge <= maxEdge) return src;
        float ratio = (float) maxEdge / longEdge;
        int nw = Math.max(32, (int) (w * ratio) / 32 * 32);
        int nh = Math.max(32, (int) (h * ratio) / 32 * 32);
        Bitmap scaled = Bitmap.createScaledBitmap(src, nw, nh, true);
        return scaled;
    }

    /**
     * 按文件头字节嗅探图片 MIME（口径实现见 {@link ImageFormatSniffer}，
     * 不信任扩展名 —— content:// 复制来的文件名经常与实际编码不符，
     * 而视觉接口要求 media_type / data URI 前缀与真实字节一致）。
     */
    public static String sniffMimeType(File file) {
        return ImageFormatSniffer.sniffMimeType(file);
    }

    /** 纯字节嗅探（与 Android 无关，可单测） */
    public static String sniffMimeType(byte[] head, int len) {
        return ImageFormatSniffer.sniffMimeType(head, len);
    }

    /** OpenAI / Anthropic / Gemini / Qwen 都能直接接收的编码（HEIC/AVIF/TIFF 不在其中） */
    public static boolean isProviderAgnosticFormat(String mimeType) {
        return ImageFormatSniffer.isProviderAgnosticFormat(mimeType);
    }
}
