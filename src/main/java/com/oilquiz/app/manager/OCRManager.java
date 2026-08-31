package com.oilquiz.app.manager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.oilquiz.app.ai.service.OnlineOCRService;
import com.oilquiz.app.ai.util.NativeOcrEngine;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * OCR 管理器
 * 用于处理文字识别功能，支持多种语言和PDF识别
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
    private TextRecognizer currentRecognizer;
    private String currentLanguage;

    // ==================== 实际生效引擎记录（前端 / OCR 工具可追溯） ====================
    public static final String ENGINE_ONLINE = "online_vision";
    public static final String ENGINE_RAPIDOCR_V6 = "rapidocr_v6";
    public static final String ENGINE_MLKIT = "mlkit";

    /** 最近一次 OCR 实际生效的引擎（volatile，跨线程可见） */
    public volatile String lastEngine = "";
    
    // 语言检测的正则表达式
    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern JAPANESE_PATTERN = Pattern.compile("[\\u3040-\\u30ff\\u31f0-\\u31ff]");
    private static final Pattern KOREAN_PATTERN = Pattern.compile("[\\uac00-\\ud7af\\u1100-\\u11ff]");
    
    public OCRManager(Context context) {
        this.context = context.getApplicationContext();
        this.currentLanguage = LANG_AUTO;
        // 默认使用中文识别模型
        switchRecognizer(LANG_CHINESE);
    }
    
    /**
     * 切换识别模型
     */
    public void switchRecognizer(String language) {
        try {
            if (currentRecognizer != null) {
                currentRecognizer.close();
            }
            this.currentLanguage = language;
            
            switch (language) {
                case LANG_CHINESE:
                    currentRecognizer = TextRecognition.getClient(
                        new ChineseTextRecognizerOptions.Builder().build());
                    Log.d(TAG, "切换到中文识别模型");
                    break;
                case LANG_ENGLISH:
                    currentRecognizer = TextRecognition.getClient(
                        TextRecognizerOptions.DEFAULT_OPTIONS);
                    Log.d(TAG, "切换到英文识别模型");
                    break;
                case LANG_JAPANESE:
                    currentRecognizer = TextRecognition.getClient(
                        new JapaneseTextRecognizerOptions.Builder().build());
                    Log.d(TAG, "切换到日文识别模型");
                    break;
                case LANG_KOREAN:
                    currentRecognizer = TextRecognition.getClient(
                        new KoreanTextRecognizerOptions.Builder().build());
                    Log.d(TAG, "切换到韩文识别模型");
                    break;
                case LANG_AUTO:
                    // 自动模式默认使用中文模型，后续会根据检测结果切换
                    currentRecognizer = TextRecognition.getClient(
                        new ChineseTextRecognizerOptions.Builder().build());
                    Log.d(TAG, "自动模式，默认使用中文识别模型");
                    break;
                default:
                    currentRecognizer = TextRecognition.getClient(
                        new ChineseTextRecognizerOptions.Builder().build());
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "切换识别模型失败: " + e.getMessage(), e);
            // 失败时回退到中文模型
            try {
                currentRecognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build());
            } catch (Exception ex) {
                Log.e(TAG, "回退识别模型失败: " + ex.getMessage(), ex);
            }
        }
    }
    
    /**
     * 获取当前语言
     */
    public String getCurrentLanguage() {
        return currentLanguage;
    }

    /**
     * 最近一次 OCR 实际生效的引擎标识（online_vision / rapidocr_v6 / mlkit）
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
            case ENGINE_MLKIT:
                return "本地 ML Kit";
            default:
                return "未知引擎";
        }
    }
    
    /**
     * 检测文本语言
     */
    private String detectLanguage(String text) {
        int chineseCount = 0;
        int japaneseCount = 0;
        int koreanCount = 0;
        
        java.util.regex.Matcher chineseMatcher = CHINESE_PATTERN.matcher(text);
        while (chineseMatcher.find()) {
            chineseCount++;
        }
        
        java.util.regex.Matcher japaneseMatcher = JAPANESE_PATTERN.matcher(text);
        while (japaneseMatcher.find()) {
            japaneseCount++;
        }
        
        java.util.regex.Matcher koreanMatcher = KOREAN_PATTERN.matcher(text);
        while (koreanMatcher.find()) {
            koreanCount++;
        }
        
        Log.d(TAG, "语言检测 - 中文: " + chineseCount + ", 日文: " + japaneseCount + ", 韩文: " + koreanCount);
        
        if (chineseCount > japaneseCount && chineseCount > koreanCount) {
            return LANG_CHINESE;
        } else if (japaneseCount > chineseCount && japaneseCount > koreanCount) {
            return LANG_JAPANESE;
        } else if (koreanCount > chineseCount && koreanCount > japaneseCount) {
            return LANG_KOREAN;
        } else {
            return LANG_ENGLISH;
        }
    }

    /**
     * 处理图片进行文字识别（优先在线视觉模型，失败回退本地 ML Kit）
     */
    public void processImageOnlineFirst(Bitmap bitmap, OCRCallback callback) {
        processImageOnlineFirst(bitmap, callback, currentLanguage);
    }

    /**
     * 处理图片进行文字识别（优先在线视觉模型，失败回退本地 ML Kit）
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
     * 优先 RapidOCR（PP-OCRv6 高精度），失败回退 ML Kit
     */
    private String recognizeFileLocal(String filePath) {
        try {
            // 采样解码：OCR 最长边限制 2048 已足够识别，避免大图全尺寸解码撑爆内存
            Bitmap bitmap = com.oilquiz.app.util.ImageParserUtil.parseImage(new java.io.File(filePath), 2048, 2048);
            if (bitmap == null) return "无法解码图片文件";

            // 优先本地高精度 RapidOCR（PP-OCRv6，同步，工作线程）
            try {
                String rapidText = NativeOcrEngine.recognize(context, bitmap);
                if (rapidText != null && !rapidText.trim().isEmpty()) {
                    lastEngine = ENGINE_RAPIDOCR_V6;
                    Log.i(TAG, "RapidOCR 识别成功: " + rapidText.length() + " chars");
                    return cleanText(rapidText);
                }
                Log.w(TAG, "RapidOCR 未识别到文本，回退 ML Kit");
            } catch (Exception e) {
                Log.w(TAG, "RapidOCR 识别失败，回退 ML Kit: " + e.getMessage());
            }

            // 回退 ML Kit（现有流程）
            final String[] result = new String[1];
            final Object lock = new Object();
            synchronized (lock) {
                processImage(bitmap, new OCRCallback() {
                    @Override
                    public void onSuccess(String text) {
                        synchronized (lock) { result[0] = text; lock.notify(); }
                    }
                    @Override
                    public void onFailure(String error) {
                        synchronized (lock) { result[0] = "OCR识别失败: " + error; lock.notify(); }
                    }
                }, true);
                try { lock.wait(30000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return result[0] != null ? result[0] : "OCR未识别到文本";
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
     * 处理图片进行文字识别（带自动检测）
     * 优先本地高精度 RapidOCR（PP-OCRv6），失败/无结果回退 ML Kit。
     * RapidOCR 为同步推理，在独立工作线程执行后回到主线程回调，调用方可保持原有异步语义。
     */
    public void processImage(Bitmap bitmap, OCRCallback callback, boolean retryOnFailure) {
        new Thread(() -> {
            String rapidText = NativeOcrEngine.recognize(context, bitmap);
            if (rapidText != null && !rapidText.trim().isEmpty()) {
                lastEngine = ENGINE_RAPIDOCR_V6;
                String cleaned = cleanText(rapidText);
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .post(() -> callback.onSuccess(cleaned));
                return;
            }
            Log.w(TAG, "RapidOCR 无结果，回退 ML Kit");
            mlKitProcess(bitmap, callback, retryOnFailure);
        }, "rapidocr-process").start();
    }

    /**
     * ML Kit 本地识别（原 processImage 实现，作为 RapidOCR 的兜底）
     */
    private void mlKitProcess(Bitmap bitmap, OCRCallback callback, boolean retryOnFailure) {
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            
            final String originalLanguage = currentLanguage;
            
            currentRecognizer.process(image)
                .addOnSuccessListener(text -> {
                    String resultText = text.getText();
                    
                    // 如果是自动模式，根据识别结果检测语言并重新识别
                    if (originalLanguage.equals(LANG_AUTO) && retryOnFailure) {
                        String detectedLang = detectLanguage(resultText);
                        if (!detectedLang.equals(LANG_CHINESE)) {
                            Log.d(TAG, "检测到语言: " + detectedLang + "，切换模型重新识别");
                            switchRecognizer(detectedLang);
                            processImage(bitmap, callback, false);
                            return;
                        }
                    }
                    
                    lastEngine = ENGINE_MLKIT;
                    // 清理文本，避免乱码
                    resultText = cleanText(resultText);
                    callback.onSuccess(resultText);
                    
                    // 如果是自动模式，恢复默认设置
                    if (originalLanguage.equals(LANG_AUTO) && !currentLanguage.equals(originalLanguage)) {
                        switchRecognizer(originalLanguage);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "OCR识别失败: " + e.getMessage(), e);
                    callback.onFailure(e.getMessage());
                    
                    // 如果是自动模式，恢复默认设置
                    if (originalLanguage.equals(LANG_AUTO) && !currentLanguage.equals(originalLanguage)) {
                        switchRecognizer(originalLanguage);
                    }
                });
        } catch (Exception e) {
            Log.e(TAG, "OCR处理失败: " + e.getMessage(), e);
            callback.onFailure(e.getMessage());
        }
    }

    /**
     * 处理图片进行流式文字识别
     */
    public void processImageStream(Bitmap bitmap, OCRStreamCallback callback) {
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            
            // 模拟流式处理
            callback.onProgress("开始识别...");
            
            final String originalLanguage = currentLanguage;
            
            currentRecognizer.process(image)
                .addOnSuccessListener(text -> {
                    callback.onProgress("识别完成");
                    String resultText = text.getText();
                    
                    // 清理文本，避免乱码
                    resultText = cleanText(resultText);
                    callback.onSuccess(resultText);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "OCR识别失败: " + e.getMessage(), e);
                    callback.onFailure(e.getMessage());
                });
        } catch (Exception e) {
            Log.e(TAG, "OCR处理失败: " + e.getMessage(), e);
            callback.onFailure(e.getMessage());
        }
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
                    float scale = 3.0f;
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
     * 优先 RapidOCR（PP-OCRv6），失败/无结果回退 ML Kit（同步等待）。
     * 返回清理后的文本；无结果返回 null。
     */
    private String recognizePageSync(Bitmap bitmap) {
        // RapidOCR 同步推理（工作线程）
        try {
            String t = NativeOcrEngine.recognize(context, bitmap);
            if (t != null && !t.trim().isEmpty()) {
                lastEngine = ENGINE_RAPIDOCR_V6;
                return cleanText(t);
            }
            Log.w(TAG, "PDF 页 RapidOCR 无结果，回退 ML Kit");
        } catch (Exception e) {
            Log.w(TAG, "PDF 页 RapidOCR 失败，回退 ML Kit: " + e.getMessage());
        }
        // ML Kit 兜底（异步，用 CountDownLatch 同步等待结果）
        try {
            final String[] result = new String[1];
            final CountDownLatch latch = new CountDownLatch(1);
            mlKitProcess(bitmap, new OCRCallback() {
                @Override
                public void onSuccess(String text) {
                    synchronized (result) { result[0] = text; }
                    latch.countDown();
                }
                @Override
                public void onFailure(String error) {
                    synchronized (result) { result[0] = "OCR识别失败: " + error; }
                    latch.countDown();
                }
            }, true);
            if (!latch.await(30, TimeUnit.SECONDS)) {
                Log.w(TAG, "PDF 页 ML Kit 识别超时");
                return null;
            }
            String r;
            synchronized (result) { r = result[0]; }
            if (r != null && !r.startsWith("OCR识别失败")) {
                return cleanText(r);
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "PDF 页 ML Kit 兜底异常: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 清理文本，去除乱码和不可打印字符
     */
    private String cleanText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        
        // 去除不可打印的控制字符（保留换行和制表符）
        StringBuilder cleaned = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == '\n' || c == '\r' || c == '\t' || 
                (c >= 32 && c <= 126) || 
                (c >= 0x4e00 && c <= 0x9fff) || 
                (c >= 0x3040 && c <= 0x30ff) || 
                (c >= 0x31f0 && c <= 0x31ff) || 
                (c >= 0xac00 && c <= 0xd7af) || 
                (c >= 0x1100 && c <= 0x11ff) ||
                (c >= 0xff00 && c <= 0xffef)) {
                cleaned.append(c);
            }
        }
        
        // 去除多余的空行
        String result = cleaned.toString();
        result = result.replaceAll("\\n\\s*\\n\\s*\\n", "\n\n");
        
        return result.trim();
    }

    /**
     * 释放资源
     */
    public void release() {
        if (currentRecognizer != null) {
            currentRecognizer.close();
            currentRecognizer = null;
        }
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
