package com.oilquiz.app.manager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.oilquiz.app.ai.service.OnlineOCRService;
import com.oilquiz.app.ai.util.NativeOcrEngine;

import java.util.concurrent.CompletableFuture;

/**
 * OCR 管理器（在线视觉模型 + 本地 RapidOCR 双引擎）。
 *
 * <p><b>MLKIT-REMOVED(2026-11-20)</b>：原来还有第三级 ML Kit 兜底（{@code TextRecognizer}），
 * 已于本次整套移除，原因有两条：
 * <ol>
 *   <li>识别质量不行（中文/表格/手写均明显弱于 RapidOCR PP-OCRv6），实际几乎不生效；</li>
 *   <li>它带来的 {@code libmlkit_google_ocr_pipeline.so} / {@code libmlkitcommonpipeline.so}
 *       是 NDK r25 构建的 4 KB 对齐库，16 KB 页设备上加载失败，是 ELF 审计里的主要负担。</li>
 * </ol>
 * 现在本地识别统一走 RapidOCR（PP-OCRv6 单模型覆盖中英日韩），
 * {@link #switchRecognizer(String)} 的语言参数只作为在线视觉模型的提示保留。</p>
 */
public class OCRManager {
    private static final String TAG = "OCRManager";

    // 语言类型
    public static final String LANG_AUTO = "auto";
    public static final String LANG_CHINESE = "chinese";
    public static final String LANG_ENGLISH = "english";
    public static final String LANG_JAPANESE = "japanese";
    public static final String LANG_KOREAN = "korean";

    private final Context context;
    private String currentLanguage;

    // ==================== 实际生效引擎记录（前端 / OCR 工具可追溯） ====================
    public static final String ENGINE_ONLINE = "online_vision";
    public static final String ENGINE_RAPIDOCR_V6 = "rapidocr_v6";

    /** 最近一次 OCR 实际生效的引擎（volatile，跨线程可见） */
    public volatile String lastEngine = "";

    public OCRManager(Context context) {
        this.context = context.getApplicationContext();
        this.currentLanguage = LANG_AUTO;
    }

    /**
     * 切换/设置识别语言。
     * 本地引擎（RapidOCR）是单模型多语言，不再按语言切换；该值只用于提示在线视觉模型。
     */
    public void switchRecognizer(String language) {
        this.currentLanguage = language;
        Log.d(TAG, "OCR 语言设置为: " + language + "（本地引擎为 RapidOCR，多语言单模型）");
    }

    /**
     * 获取当前语言
     */
    public String getCurrentLanguage() {
        return currentLanguage;
    }

    /**
     * 最近一次 OCR 实际生效的引擎标识（online_vision / rapidocr_v6）
     */
    public String getLastEngine() {
        return lastEngine;
    }

    /**
     * 最近一次 OCR 实际生效引擎的中文描述（前端提示用）
     */
    public String getLastEngineLabel() {
        switch (lastEngine) {
            case ENGINE_ONLINE:
                return "在线视觉模型";
            case ENGINE_RAPIDOCR_V6:
                return "本地高精度 OCR（PP-OCRv6）";
            default:
                return "未知引擎";
        }
    }

    /**
     * 处理图片进行文字识别（优先在线视觉模型，失败回退本地 RapidOCR）
     */
    public void processImageOnlineFirst(Bitmap bitmap, OCRCallback callback) {
        processImageOnlineFirst(bitmap, callback, currentLanguage);
    }

    /**
     * 处理图片进行文字识别（优先在线视觉模型，失败回退本地 RapidOCR）
     * @param bitmap   图片
     * @param callback 回调
     * @param language 语言提示（传给在线模型）
     */
    public void processImageOnlineFirst(Bitmap bitmap, OCRCallback callback, String language) {
        try {
            OnlineOCRService onlineOCR = OnlineOCRService.getInstance(context);
            if (onlineOCR.isAvailable()) {
                Log.i(TAG, "尝试在线视觉模型 OCR...");
                String langCode = mapLanguageCode(language);
                onlineOCR.recognizeAsync(bitmap, langCode)
                    .thenAccept(ocrResult -> {
                        // ocrResult 现在包含 text + modelName + modelId（支持多层数据传递）
                        String text = ocrResult.text;
                        if (text != null && !text.isEmpty() && !text.contains("未检测到文字")) {
                            lastEngine = ENGINE_ONLINE;
                            Log.i(TAG, "在线视觉模型 OCR 成功: " + text.length() + " chars, model=" + ocrResult.modelName);
                            String cleaned = cleanText(text);
                            callback.onSuccess(cleaned);
                        } else {
                            Log.w(TAG, "在线视觉模型返回空结果，回退本地 OCR");
                            processImage(bitmap, callback, true);
                        }
                    })
                    .exceptionally(ex -> {
                        Log.w(TAG, "在线视觉模型 OCR 失败，回退本地 OCR: " + ex.getMessage());
                        processImage(bitmap, callback, true);
                        return null;
                    });
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "在线 OCR 调用异常，回退本地: " + e.getMessage());
        }
        // 在线不可用，直接本地
        processImage(bitmap, callback, true);
    }

    /**
     * 从文件路径进行在线 OCR（优先在线视觉模型，失败回退本地）
     */
    public CompletableFuture<String> recognizeFileOnlineFirst(String filePath, String language) {
        // 本次调用开始重置引擎标记：在线失败时本方法内部会静默回退本地 OCR，
        // 调用方只能靠 lastEngine 判断真正用了哪个引擎。不重置的话上一次的
        // 值会残留，把本地识别误标成"在线视觉模型 OCR"（这个标记会被写进模型上下文）。
        lastEngine = "";
        OnlineOCRService onlineOCR = OnlineOCRService.getInstance(context);
        if (onlineOCR.isAvailable()) {
            String langCode = mapLanguageCode(language);
            return onlineOCR.recognizeFromFileAsync(filePath, langCode)
                .thenApply(ocrResult -> {
                    // 从 OCRResult 中提取文本（模型信息可用于多层数据传递）
                    lastEngine = ENGINE_ONLINE;
                    Log.i(TAG, "在线文件 OCR 完成: model=" + ocrResult.modelName + ", text_len=" + ocrResult.text.length());
                    return ocrResult.text;
                })
                .exceptionally(ex -> {
                    Log.w(TAG, "在线文件 OCR 失败，回退本地: " + ex.getMessage());
                    // 回退到本地 OCR
                    return recognizeFileLocal(filePath);
                });
        }
        // 在线不可用，直接本地
        return CompletableFuture.supplyAsync(() -> recognizeFileLocal(filePath));
    }

    /**
     * 本地文件 OCR（同步，阻塞；仅在工作线程调用）
     * 只用 RapidOCR（PP-OCRv6 高精度）。
     */
    private String recognizeFileLocal(String filePath) {
        try {
            // 采样解码：OCR 最长边限制 2048 已足够识别，避免大图全尺寸解码撑爆内存
            Bitmap bitmap = com.oilquiz.app.util.ImageParserUtil.parseImage(new java.io.File(filePath), 2048, 2048);
            if (bitmap == null) return "无法解码图片文件";

            try {
                String rapidText = NativeOcrEngine.recognize(context, bitmap);
                if (rapidText != null && !rapidText.trim().isEmpty()) {
                    lastEngine = ENGINE_RAPIDOCR_V6;
                    Log.i(TAG, "RapidOCR 识别成功: " + rapidText.length() + " chars");
                    return cleanText(rapidText);
                }
                Log.w(TAG, "RapidOCR 未识别到文本");
            } catch (Exception e) {
                Log.w(TAG, "RapidOCR 识别失败: " + e.getMessage());
            }
            return "OCR未识别到文本";
        } catch (Exception e) {
            return "OCR识别失败: " + e.getMessage();
        }
    }

    /**
     * 将内部语言代码映射为在线模型常用语言代码
     */
    private String mapLanguageCode(String language) {
        if (language == null || language.isEmpty() || language.equals(LANG_AUTO)) return "auto";
        switch (language) {
            case LANG_CHINESE: return "zh";
            case LANG_ENGLISH: return "en";
            case LANG_JAPANESE: return "ja";
            case LANG_KOREAN: return "ko";
            default: return language;
        }
    }

    /**
     * 处理图片进行文字识别
     */
    public void processImage(Bitmap bitmap, OCRCallback callback) {
        processImage(bitmap, callback, false);
    }

    /**
     * 处理图片进行文字识别（本地 RapidOCR）。
     * RapidOCR 为同步推理，在独立工作线程执行后回到主线程回调，调用方保持原有异步语义。
     * @param retryOnFailure 兼容旧签名保留（原 ML Kit 自动模式重试用），现已无意义
     */
    public void processImage(Bitmap bitmap, OCRCallback callback, boolean retryOnFailure) {
        new Thread(() -> {
            String rapidText = null;
            try {
                rapidText = NativeOcrEngine.recognize(context, bitmap);
            } catch (Exception e) {
                Log.w(TAG, "RapidOCR 识别异常: " + e.getMessage());
            }
            if (rapidText != null && !rapidText.trim().isEmpty()) {
                lastEngine = ENGINE_RAPIDOCR_V6;
                String cleaned = cleanText(rapidText);
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .post(() -> callback.onSuccess(cleaned));
                return;
            }
            Log.w(TAG, "RapidOCR 无结果");
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> callback.onFailure("本地 OCR 未识别到文字"));
        }, "rapidocr-process").start();
    }

    /**
     * 处理图片进行流式文字识别
     */
    public void processImageStream(Bitmap bitmap, OCRStreamCallback callback) {
        new Thread(() -> {
            callback.onProgress("开始识别...");
            String text = null;
            try {
                text = NativeOcrEngine.recognize(context, bitmap);
            } catch (Exception e) {
                Log.e(TAG, "OCR处理失败: " + e.getMessage(), e);
            }
            final String raw = text;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (raw == null || raw.trim().isEmpty()) {
                    callback.onFailure("本地 OCR 未识别到文字");
                    return;
                }
                lastEngine = ENGINE_RAPIDOCR_V6;
                callback.onProgress("识别完成");
                callback.onSuccess(cleanText(raw));
            });
        }, "rapidocr-stream").start();
    }

    /**
     * 处理PDF文件
     */
    public void processPdf(Uri pdfUri, OCRCallback callback) {
        processPdf(pdfUri, callback, null);
    }

    /**
     * 处理PDF文件（带进度回调）
     */
    public void processPdf(Uri pdfUri, OCRCallback callback, OCRProgressCallback progressCallback) {
        new Thread(() -> {
            StringBuilder totalText = new StringBuilder();
            ParcelFileDescriptor fileDescriptor = null;
            PdfRenderer pdfRenderer = null;

            try {
                if (progressCallback != null) {
                    progressCallback.onProgress(0, "正在打开PDF文件...");
                }

                fileDescriptor = context.getContentResolver().openFileDescriptor(pdfUri, "r");
                if (fileDescriptor == null) {
                    throw new Exception("无法打开PDF文件");
                }

                pdfRenderer = new PdfRenderer(fileDescriptor);
                int pageCount = pdfRenderer.getPageCount();

                Log.d(TAG, "PDF总页数: " + pageCount);

                for (int i = 0; i < pageCount; i++) {
                    if (progressCallback != null) {
                        int progress = (i * 100) / pageCount;
                        progressCallback.onProgress(progress, "正在识别第 " + (i + 1) + " / " + pageCount + " 页...");
                    }

                    PdfRenderer.Page page = pdfRenderer.openPage(i);

                    int pageWidth = page.getWidth();
                    int pageHeight = page.getHeight();
                    // 渲染 scale 3.0→4.0：发票/扫描件小字密集，提高分辨率利于 det 检出；
                    // 超长边用 4096 上限兜底，避免 OOM。
                    float scale = 4.0f;
                    if (pageWidth * scale > 4096 || pageHeight * scale > 4096) {
                        scale = 4096f / Math.max(pageWidth, pageHeight);
                    }
                    int width = Math.max((int)(pageWidth * scale), 100);
                    int height = Math.max((int)(pageHeight * scale), 100);
                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

                    // 渲染PDF页面到Bitmap
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.close();

                    // 串行同步识别（工作线程）。原实现每页异步 processImage + sleep(500)，
                    // 而 v6 单页识别可达 10s+，多页会并发调用 RapidOCR 单例
                    // （ONNX session 非线程安全）导致识别错乱/失败，这里改为逐页串行。
                    String pageText = recognizePageSync(bitmap);
                    bitmap.recycle();
                    if (pageText != null && !pageText.trim().isEmpty()) {
                        totalText.append(pageText);
                        totalText.append("\n\n--- 第 ").append(i + 1).append(" 页结束 ---\n\n");
                    } else {
                        Log.w(TAG, "第 " + (i + 1) + " 页未识别到文本");
                    }
                }

                String finalText = cleanText(totalText.toString());
                if (progressCallback != null) {
                    progressCallback.onProgress(100, "识别完成！");
                }
                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                if (finalText.trim().isEmpty()) {
                    mainHandler.post(() -> callback.onFailure("PDF未识别到文字"));
                } else {
                    mainHandler.post(() -> callback.onSuccess(finalText));
                }

            } catch (Exception e) {
                Log.e(TAG, "PDF处理失败: " + e.getMessage(), e);
                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                mainHandler.post(() -> {
                    callback.onFailure("PDF处理失败: " + e.getMessage());
                });
            } finally {
                try {
                    if (pdfRenderer != null) {
                        pdfRenderer.close();
                    }
                    if (fileDescriptor != null) {
                        fileDescriptor.close();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "关闭PDF资源失败: " + e.getMessage(), e);
                }
            }
        }).start();
    }

    /**
     * 同步识别一页 PDF 渲染的 Bitmap（工作线程串行调用）。
     * 只用 RapidOCR（PP-OCRv6）；无结果返回 null。
     */
    private String recognizePageSync(Bitmap bitmap) {
        try {
            String t = NativeOcrEngine.recognize(context, bitmap);
            if (t != null && !t.trim().isEmpty()) {
                lastEngine = ENGINE_RAPIDOCR_V6;
                return cleanText(t);
            }
            Log.w(TAG, "PDF 页 RapidOCR 无结果");
        } catch (Exception e) {
            Log.w(TAG, "PDF 页 RapidOCR 失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 清理文本，去除乱码和不可打印字符
     */
    private String cleanText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // 仅过滤不可见控制字符（换行/回车/制表保留），保留全部可打印字符。
        // 原实现用白名单区间，但区间不含中文标点（。、、《》「」）、弯引号、
        // 破折号、省略号及 CJK 扩展汉字，导致识别结果被删成“残缺”。
        StringBuilder cleaned = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                cleaned.append(c);
            } else if (!Character.isISOControl(c)) {
                cleaned.append(c);
            }
        }

        // 去除多余的空行
        String result = cleaned.toString();
        result = result.replaceAll("\\n\\s*\\n\\s*\\n", "\n\n");

        return result.trim();
    }

    /**
     * 释放资源（本地 RapidOCR 是全局单例，由 NativeOcrEngine 管理，这里无需释放）
     */
    public void release() {
        // no-op：ML Kit recognizer 已移除；RapidOCR 引擎生命周期由 NativeOcrEngine 持有
    }

    /**
     * OCR 回调接口
     */
    public interface OCRCallback {
        void onSuccess(String text);
        void onFailure(String error);
    }

    /**
     * OCR 流式回调接口
     */
    public interface OCRStreamCallback extends OCRCallback {
        void onProgress(String progress);
        void onPartialResult(String partialText);
    }

    /**
     * PDF识别进度回调接口
     */
    public interface OCRProgressCallback {
        void onProgress(int percent, String message);
    }
}
