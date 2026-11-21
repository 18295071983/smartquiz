package com.oilquiz.app.toolkit;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import com.oilquiz.app.ai.onnx.OnnxImageClassifier;
import com.oilquiz.app.ai.onnx.OnnxModelStore;
import com.oilquiz.app.ai.onnx.OnnxObjectDetector;
import com.oilquiz.app.manager.OCRManager;
import com.oilquiz.app.util.FileParserUtil;
import com.oilquiz.app.util.ImageGeneratorUtil;
import com.oilquiz.app.util.ImageGeneratorUtil.ImageFormat;
import com.oilquiz.app.util.WebParserUtil;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 应用工具集合
 * 统一管理项目中所有可用的工具功能
 */
public class AppToolkit {
    
    private static AppToolkit instance;
    private final Context context;
    
    // 工具实例
    private OCRManager ocrManager;
    
    private AppToolkit(Context context) {
        this.context = context.getApplicationContext();
        this.ocrManager = new OCRManager(this.context);
    }
    
    /**
     * 获取工具集合单例
     */
    public static synchronized AppToolkit getInstance(Context context) {
        if (instance == null) {
            instance = new AppToolkit(context);
        }
        return instance;
    }
    
    /**
     * 获取OCR管理器
     */
    public OCRManager getOcrManager() {
        return ocrManager;
    }
    
    // ==================== OCR功能 ====================
    
    /**
     * 识别图片中的文字
     */
    public void recognizeText(Bitmap bitmap, OCRManager.OCRCallback callback) {
        ocrManager.processImage(bitmap, callback);
    }
    
    /**
     * 识别图片中的文字（带语言指定）
     */
    public void recognizeText(Bitmap bitmap, String language, OCRManager.OCRCallback callback) {
        ocrManager.switchRecognizer(language);
        ocrManager.processImage(bitmap, callback);
    }
    
    /**
     * 识别PDF文件中的文字
     */
    public void recognizePdf(Uri pdfUri, OCRManager.OCRCallback callback) {
        ocrManager.processPdf(pdfUri, callback);
    }
    
    /**
     * 识别PDF文件中的文字（带进度）
     */
    public void recognizePdf(Uri pdfUri, OCRManager.OCRCallback callback, 
                            OCRManager.OCRProgressCallback progressCallback) {
        ocrManager.processPdf(pdfUri, callback, progressCallback);
    }
    
    /**
     * 设置OCR识别语言
     */
    public void setOcrLanguage(String language) {
        ocrManager.switchRecognizer(language);
    }
    
    /**
     * 获取当前OCR语言
     */
    public String getCurrentOcrLanguage() {
        return ocrManager.getCurrentLanguage();
    }
    
    // ==================== 图像标签 / 目标检测（ONNX，模型内置、首次使用复制） ====================
    // ONNX-FEATURE(2026-11-20)：ML Kit 的 image-labeling / object-detection 已整套移除（模型质量差 +
    // 4 KB 对齐的 native 库），这里用 ONNX Runtime 1.32 + ImageNet/COCO 模型补回同样的能力。
    // 模型文件内置在 assets/models/：第一次调用 ensureXxxModel() 才复制到 filesDir/onnx_models/（不联网）。

    /** 图像标签模型是否已下载 */
    public boolean isImageLabelModelReady() {
        return OnnxImageClassifier.isReady(context);
    }

    /** 确保图像标签模型就绪（首次会把内置模型复制到 filesDir；回调在工作线程） */
    public void ensureImageLabelModel(OnnxModelStore.Callback callback) {
        OnnxImageClassifier.ensureReady(context, callback);
    }

    /**
     * 识别图片标签（同步阻塞，必须在工作线程调用；模型未就绪时返回空列表）。
     *
     * @param topK 返回前 K 个标签
     */
    public List<OnnxImageClassifier.Label> classifyImage(Bitmap bitmap, int topK) {
        return OnnxImageClassifier.classify(context, bitmap, topK);
    }

    /** 目标检测模型是否已下载 */
    public boolean isObjectDetectionModelReady() {
        return OnnxObjectDetector.isReady(context, OnnxObjectDetector.SPEC_DEFAULT);
    }

    /** 确保目标检测模型就绪（首次会把内置模型复制到 filesDir；建议只给需要检测的功能调用） */
    public void ensureObjectDetectionModel(OnnxModelStore.Callback callback) {
        OnnxObjectDetector.ensureReady(context, OnnxObjectDetector.SPEC_DEFAULT, callback);
    }

    /**
     * 检测图片中的物体（同步阻塞，必须在工作线程调用；模型未就绪时返回空列表）。
     *
     * @param minScore   置信度阈值（0.35~0.5 比较合适）
     * @param maxResults 最多返回多少个框
     */
    public List<OnnxObjectDetector.Detection> detectObjects(Bitmap bitmap, float minScore, int maxResults) {
        return OnnxObjectDetector.detect(context, OnnxObjectDetector.SPEC_DEFAULT, bitmap, minScore, maxResults);
    }

    /** 把标签列表格式化成一行文本（给工具/卡片展示用） */
    public String formatImageLabelsAsText(List<OnnxImageClassifier.Label> results) {
        if (results == null || results.isEmpty()) {
            return "未识别到图像标签";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(results.get(i).toString());
        }
        return sb.toString();
    }

    /** 把检测结果格式化成多行文本 */
    public String formatDetectionsAsText(List<OnnxObjectDetector.Detection> results) {
        if (results == null || results.isEmpty()) {
            return "未检测到物体";
        }
        StringBuilder sb = new StringBuilder();
        for (OnnxObjectDetector.Detection d : results) {
            sb.append(String.format(java.util.Locale.US, "%s %.0f%% [%.3f,%.3f,%.3f,%.3f]",
                    d.label, d.score * 100f, d.x1, d.y1, d.x2, d.y2));
            if (sb.length() > 0) {
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    // ==================== 图片功能 ====================
    
    /**
     * 保存Bitmap为图片文件
     */
    public boolean saveBitmap(Bitmap bitmap, File file, ImageFormat format) {
        return ImageGeneratorUtil.saveBitmap(bitmap, file, format);
    }
    
    /**
     * 保存Bitmap为JPEG文件
     */
    public boolean saveAsJpeg(Bitmap bitmap, File file) {
        return ImageGeneratorUtil.saveAsJpeg(bitmap, file);
    }
    
    /**
     * 保存Bitmap为PNG文件
     */
    public boolean saveAsPng(Bitmap bitmap, File file) {
        return ImageGeneratorUtil.saveAsPng(bitmap, file);
    }
    
    /**
     * 生成纯色背景图片
     */
    public boolean generateSolidColorImage(File file, int width, int height, 
                                          int color, ImageFormat format) {
        return ImageGeneratorUtil.generateSolidColorImage(file, width, height, color, format);
    }
    
    /**
     * 生成带文字的图片
     */
    public boolean generateTextImage(File file, int width, int height, 
                                     int backgroundColor, String text, 
                                     int textColor, float textSize, ImageFormat format) {
        return ImageGeneratorUtil.generateTextImage(file, width, height, 
                backgroundColor, text, textColor, textSize, format);
    }
    
    /**
     * 缩放图片
     */
    public Bitmap scaleBitmap(Bitmap sourceBitmap, int newWidth, int newHeight) {
        return ImageGeneratorUtil.scaleBitmap(sourceBitmap, newWidth, newHeight);
    }
    
    /**
     * 裁剪图片
     */
    public Bitmap cropBitmap(Bitmap sourceBitmap, int x, int y, int width, int height) {
        return ImageGeneratorUtil.cropBitmap(sourceBitmap, x, y, width, height);
    }
    
    /**
     * 旋转图片
     */
    public Bitmap rotateBitmap(Bitmap sourceBitmap, float degrees) {
        return ImageGeneratorUtil.rotateBitmap(sourceBitmap, degrees);
    }
    
    // ==================== 文件解析功能 ====================
    
    /**
     * 解析文本文件
     */
    public String parseTextFile(File file) {
        return FileParserUtil.parseTextFile(file);
    }
    
    /**
     * 解析CSV文件
     */
    public List<String[]> parseCsvFile(File file) {
        return FileParserUtil.parseCsvFile(file);
    }
    
    /**
     * 解析JSON文件为JSONObject
     */
    public JSONObject parseJsonFile(File file) {
        return FileParserUtil.parseJsonFile(file);
    }
    
    /**
     * 解析JSON文件为JSONArray
     */
    public JSONArray parseJsonArrayFile(File file) {
        return FileParserUtil.parseJsonArrayFile(file);
    }
    
    /**
     * 解析JSON文件为Map
     */
    public Map<String, Object> parseJsonToMap(File file) {
        return FileParserUtil.parseJsonToMap(file);
    }
    
    /**
     * 按行读取文件
     */
    public List<String> readLines(File file) {
        return FileParserUtil.readLines(file);
    }
    
    /**
     * 获取文件扩展名
     */
    public String getFileExtension(File file) {
        return FileParserUtil.getFileExtension(file);
    }
    
    /**
     * 判断文件类型
     */
    public FileParserUtil.FileType getFileType(File file) {
        return FileParserUtil.getFileType(file);
    }
    
    // ==================== 网页解析功能 ====================
    
    /**
     * 从文件解析HTML
     */
    public Document parseHtmlFromFile(File file) {
        return WebParserUtil.parseHtmlFromFile(file);
    }
    
    /**
     * 从字符串解析HTML
     */
    public Document parseHtmlFromString(String html) {
        return WebParserUtil.parseHtmlFromString(html);
    }
    
    /**
     * 获取网页标题
     */
    public String getHtmlTitle(Document doc) {
        return WebParserUtil.getTitle(doc);
    }
    
    /**
     * 获取网页正文文本
     */
    public String getHtmlPlainText(Document doc) {
        return WebParserUtil.getPlainText(doc);
    }
    
    /**
     * 获取所有链接
     */
    public List<String> getAllLinks(Document doc) {
        return WebParserUtil.getAllLinks(doc);
    }
    
    /**
     * 获取所有图片链接
     */
    public List<String> getAllImages(Document doc) {
        return WebParserUtil.getAllImages(doc);
    }
    
    /**
     * 获取所有段落文本
     */
    public List<String> getAllParagraphs(Document doc) {
        return WebParserUtil.getAllParagraphs(doc);
    }
    
    /**
     * 获取所有表格数据
     */
    public List<List<String[]>> getAllTables(Document doc) {
        return WebParserUtil.getAllTables(doc);
    }
    
    /**
     * 通过CSS选择器查找元素
     */
    public Elements selectElements(Document doc, String selector) {
        return WebParserUtil.selectElements(doc, selector);
    }
    
    /**
     * 通过ID查找元素
     */
    public Element getElementById(Document doc, String id) {
        return WebParserUtil.getElementById(doc, id);
    }
    
    /**
     * 通过类名查找元素
     */
    public Elements getElementsByClass(Document doc, String className) {
        return WebParserUtil.getElementsByClass(doc, className);
    }
    
    /**
     * 通过标签名查找元素
     */
    public Elements getElementsByTag(Document doc, String tag) {
        return WebParserUtil.getElementsByTag(doc, tag);
    }
    
    /**
     * 获取元素的文本内容
     */
    public String getElementText(Element element) {
        return WebParserUtil.getElementText(element);
    }
    
    /**
     * 获取元素的属性值
     */
    public String getElementAttribute(Element element, String attribute) {
        return WebParserUtil.getElementAttribute(element, attribute);
    }
    
    // ==================== 工具类型常量 ====================
    
    /**
     * OCR语言常量
     */
    public static final String OCR_LANG_AUTO = OCRManager.LANG_AUTO;
    public static final String OCR_LANG_CHINESE = OCRManager.LANG_CHINESE;
    public static final String OCR_LANG_ENGLISH = OCRManager.LANG_ENGLISH;
    public static final String OCR_LANG_JAPANESE = OCRManager.LANG_JAPANESE;
    public static final String OCR_LANG_KOREAN = OCRManager.LANG_KOREAN;
    
    /**
     * 图片格式枚举
     */
    public enum ToolImageFormat {
        JPEG, PNG, WEBP
    }
    
    /**
     * 工具类型枚举
     */
    public enum ToolType {
        OCR("文字识别"),
        IMAGE_LABEL("图像标签识别"),
        OBJECT_DETECTION("目标检测"),
        IMAGE("图片处理"),
        FILE_PARSER("文件解析"),
        WEB_PARSER("网页解析");
        
        private final String displayName;
        
        ToolType(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 检查文件是否存在
     */
    public boolean fileExists(File file) {
        return file != null && file.exists();
    }
    
    /**
     * 检查Bitmap是否有效
     */
    public boolean isValidBitmap(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled();
    }
    
    /**
     * 释放资源
     */
    public void release() {
        if (ocrManager != null) {
            ocrManager.release();
            ocrManager = null;
        }
    }
}