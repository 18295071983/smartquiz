package com.oilquiz.app.ai.util;

import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

/**
 * OCR 图像预处理器（OpenCV 4.14）。
 *
 * <p>用途：RapidOCR 第一次识别不到文字时，用 OpenCV 做一次「提对比度 + 去噪 + 小图放大」再重试。
 * 原来这条兜底是 ML Kit，ML Kit 已整套移除（模型质量不行 + 4 KB 对齐库），
 * 这里用 OpenCV 的确定性图像处理补回来，专治：拍照糊、逆光/阴影、低对比度扫描件、小字号截图。</p>
 *
 * <p>注意：必须在 RapidOCR 引擎初始化成功之后调用（OpenCV native 库由 RapidOCR 的
 * {@code OpenCVLoader.initLocal()} 加载）。本类所有入口都吞掉 Throwable，
 * 预处理失败时返回 null，调用方保持「原图再试一次」的语义。</p>
 */
public final class OcrImagePreprocessor {

    private static final String TAG = "OcrImagePreprocessor";

    /** 小图放大的目标最长边（像素）。PP-OCR 的 det 对 <800px 的小字召回很差 */
    private static final int TARGET_LONG_SIDE = 1280;

    private OcrImagePreprocessor() {
    }

    /**
     * 生成「更适合 OCR」的增强图：灰度 → CLAHE 提对比度 → 轻微去噪 → 小图放大。
     *
     * @param src 原图
     * @return 增强后的 ARGB_8888 Bitmap；失败返回 null（调用方应跳过重试）
     */
    public static Bitmap enhanceForOcr(Bitmap src) {
        if (src == null || src.isRecycled()) {
            return null;
        }
        Mat rgba = null, gray = null, eq = null, denoised = null, scaled = null, out = null;
        Bitmap result = null;
        try {
            rgba = new Mat();
            Utils.bitmapToMat(src, rgba);

            gray = new Mat();
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);

            // 1) CLAHE：局部直方图均衡，专治阴影/逆光/低对比度扫描件
            eq = new Mat();
            CLAHE clahe = Imgproc.createCLAHE(2.5, new Size(8, 8));
            clahe.apply(gray, eq);

            // 2) 轻去噪：高斯 3x3，避免把细小笔画抹掉（不用中值/非局部均值，耗时且损字）
            denoised = new Mat();
            Imgproc.GaussianBlur(eq, denoised, new Size(3, 3), 0);

            // 3) 小图放大：长边不足 TARGET_LONG_SIDE 时按比例放大，INTER_CUBIC 保边
            int longSide = Math.max(denoised.cols(), denoised.rows());
            Mat ready;
            if (longSide > 0 && longSide < TARGET_LONG_SIDE) {
                double f = (double) TARGET_LONG_SIDE / longSide;
                scaled = new Mat();
                Imgproc.resize(denoised, scaled, new Size(), f, f, Imgproc.INTER_CUBIC);
                ready = scaled;
            } else {
                ready = denoised;
            }

            // 4) 回 RGBA（RapidOCR 的 Bitmap 入口要求 ARGB_8888）
            out = new Mat();
            Imgproc.cvtColor(ready, out, Imgproc.COLOR_GRAY2RGBA);

            result = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(out, result);
            Log.d(TAG, "OCR 预处理完成: " + src.getWidth() + "x" + src.getHeight()
                    + " -> " + result.getWidth() + "x" + result.getHeight());
            return result;
        } catch (Throwable t) {
            // OpenCV native 没加载 / OOM / 尺寸非法：都不要影响主流程
            Log.w(TAG, "OCR 预处理失败，跳过重试: " + t.getMessage());
            if (result != null && !result.isRecycled()) {
                result.recycle();
            }
            return null;
        } finally {
            releaseQuietly(rgba, gray, eq, denoised, scaled, out);
        }
    }

    private static void releaseQuietly(Mat... mats) {
        for (Mat m : mats) {
            if (m != null) {
                try {
                    m.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
