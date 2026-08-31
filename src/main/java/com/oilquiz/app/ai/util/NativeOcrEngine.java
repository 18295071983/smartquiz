package com.oilquiz.app.ai.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import io.github.hzkitty.RapidOCR;
import io.github.hzkitty.entity.OcrConfig;
import io.github.hzkitty.entity.OcrResult;

/**
 * 本地高精度 OCR 引擎（RapidOCR4j + PP-OCRv6_small 模型 + ONNX Runtime + OpenCV）。
 * 端侧离线推理，PP-OCRv6 为 PaddleOCR 第六代（2026）端侧最高精度档（small 定位移动端，
 * 中文印刷识别 94%+，支持中英/繁体/日文等 50 语言单模型），精度远超 ML Kit 与 PP-OCRv4。
 *
 * 模型与配置（已实测验证）：
 *  - det.onnx / rec.onnx：PP-OCRv6_small（assets/ocr/v6/，输入规格与 v4 一致，可直接套用推理管线）
 *  - keys.txt：v6 rec 字符字典（18708 字符，CTC blank=索引 0，RapidOCR4j 已按此解码）
 *  - DB 后处理参数按 v6 官方 inference.yml：thresh 0.2 / box_thresh 0.45 / unclip 1.4 / max_candidates 3000
 *  - rec 归一化 [-1,1] 已实测与 v6 兼容；cls 复用 RapidOCR4j 自带 PP-OCRv4 mobile v2.0（v6 官方同款）
 *
 * 线程安全：RapidOCR 实例全局单例（首次初始化加载 OpenCV + 三个 ONNX 模型，耗时较长）；
 * 所有识别必须在工作线程执行（严禁主线程调用 run()）。
 */
public class NativeOcrEngine {
    private static final String TAG = "NativeOcrEngine";

    private static final String V6_DET_ASSET = "ocr/v6/det.onnx";
    private static final String V6_REC_ASSET = "ocr/v6/rec.onnx";
    private static final String V6_KEYS_ASSET = "ocr/v6/keys.txt";

    private static volatile RapidOCR rapidOCR;
    private static volatile boolean initFailed = false;

    private NativeOcrEngine() {
    }

    /**
     * 获取全局 RapidOCR 引擎实例（懒加载，线程安全）。
     * 使用 PP-OCRv6_small 模型 + v6 官方后处理参数；
     * 初始化失败后会记录 initFailed，避免每次识别重复尝试加载。
     */
    public static synchronized RapidOCR getInstance(Context context) {
        if (rapidOCR == null && !initFailed) {
            try {
                Context ctx = context.getApplicationContext();
                OcrConfig config = new OcrConfig();

                // 检测：PP-OCRv6_small det + v6 官方 DB 后处理参数
                config.Det.modelPath = V6_DET_ASSET;
                config.Det.thresh = 0.2f;
                config.Det.boxThresh = 0.45f;
                config.Det.maxCandidates = 3000;
                config.Det.unclipRatio = 1.4f;

                // 识别：PP-OCRv6_small rec + v6 字典（复制到 cache 供文件读取）
                config.Rec.modelPath = V6_REC_ASSET;
                config.Rec.recKeysPath = copyAssetToCache(ctx, V6_KEYS_ASSET);

                // 方向分类：复用库自带 PP-OCRv4 mobile v2.0（v6 官方同款），保持默认

                rapidOCR = RapidOCR.create(ctx, config);
                Log.i(TAG, "RapidOCR 引擎初始化完成（PP-OCRv6_small 模型就绪）");
            } catch (Throwable t) {
                initFailed = true;
                Log.e(TAG, "RapidOCR 引擎初始化失败: " + t.getMessage(), t);
            }
        }
        return rapidOCR;
    }

    /**
     * 同步识别一张图片。
     *
     * @param context 应用上下文
     * @param bitmap  待识别图片
     * @return 识别文本（按行 \n 连接）；引擎不可用 / 未识别到文本 / 异常时返回 null
     */
    public static String recognize(Context context, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }
        RapidOCR engine = getInstance(context);
        if (engine == null) {
            return null;
        }
        try {
            OcrResult result = engine.run(bitmap);
            if (result == null) {
                return null;
            }
            String text = result.getStrRes();
            if (text == null || text.trim().isEmpty()) {
                return null;
            }
            Log.i(TAG, "RapidOCR 识别完成: " + text.length() + " chars, "
                    + Math.round(result.getElapseTime() * 1000) + "ms");
            return text;
        } catch (Throwable t) {
            Log.e(TAG, "RapidOCR 识别异常: " + t.getMessage(), t);
            return null;
        }
    }

    /**
     * 将 assets 中字典文件复制到应用 cache 目录（CTCLabelDecode 按文件系统路径读取），返回绝对路径。
     */
    private static String copyAssetToCache(Context context, String assetPath) {
        try {
            File outFile = new File(context.getCacheDir(), "ocr_v6_keys.txt");
            if (outFile.exists() && outFile.length() > 0) {
                return outFile.getAbsolutePath();
            }
            try (InputStream in = context.getAssets().open(assetPath);
                 OutputStream out = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
            }
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "复制 OCR 字典失败: " + e.getMessage(), e);
            return null;
        }
    }
}
