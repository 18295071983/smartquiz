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

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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
     * 处理图片进行文字识别
     */
    public void processImage(Bitmap bitmap, OCRCallback callback) {
        processImage(bitmap, callback, false);
    }
    
    /**
     * 处理图片进行文字识别（带自动检测）
     */
    public void processImage(Bitmap bitmap, OCRCallback callback, boolean retryOnFailure) {
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
                    
                    // 使用当前模型识别文字
                    final int currentPage = i;
                    final boolean isLastPage = (i == pageCount - 1);
                    
                    // 在主线程处理OCR
                    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                    mainHandler.post(() -> {
                        processImage(bitmap, new OCRCallback() {
                            @Override
                            public void onSuccess(String text) {
                                synchronized (totalText) {
                                    totalText.append(text);
                                    totalText.append("\n\n--- 第 ").append(currentPage + 1).append(" 页结束 ---\n\n");
                                    
                                    if (isLastPage) {
                                        String finalText = cleanText(totalText.toString());
                                        if (progressCallback != null) {
                                            progressCallback.onProgress(100, "识别完成！");
                                        }
                                        callback.onSuccess(finalText);
                                    }
                                }
                            }
                            
                            @Override
                            public void onFailure(String error) {
                                Log.e(TAG, "第 " + (currentPage + 1) + " 页识别失败: " + error);
                                // 继续处理下一页
                                if (isLastPage) {
                                    String finalText = cleanText(totalText.toString());
                                    if (progressCallback != null) {
                                        progressCallback.onProgress(100, "识别完成（部分页面可能识别失败）！");
                                    }
                                    callback.onSuccess(finalText);
                                }
                            }
                        });
                    });
                    
                    // 等待一下让主线程处理
                    Thread.sleep(500);
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
