package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import com.oilquiz.app.manager.OCRManager;
import com.oilquiz.app.toolkit.AppToolkit;
import com.oilquiz.app.util.FileParserUtil;
import com.oilquiz.app.util.ImageGeneratorUtil.ImageFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AppToolkit AI工具类
 * 将AppToolkit的功能封装为AI可调用的工具
 */
public class AppToolkitAITool implements AITool {
    
    private static final String TAG = "AppToolkitAITool";
    private final Context context;
    private AppToolkit toolkit;
    
    public AppToolkitAITool(Context context) {
        this.context = context;
        this.toolkit = AppToolkit.getInstance(context);
    }
    
    @Override
    public String getName() {
        return "app_toolkit";
    }
    
    @Override
    public String getDescription() {
        return "应用工具集合，提供OCR文字识别、图片处理、文件解析、网页解析等功能";
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = (String) parameters.get("action");
            if (action == null || action.isEmpty()) {
                return new AIToolResult("缺少参数: action", parameters);
            }
            
            switch (action) {
                // OCR功能
                case "ocr_recognize":
                    return ocrRecognize(parameters);
                case "ocr_recognize_pdf":
                    return ocrRecognizePdf(parameters);
                case "ocr_set_language":
                    return ocrSetLanguage(parameters);
                case "ocr_get_language":
                    return ocrGetLanguage();
                    
                // 图片处理功能
                case "image_save":
                    return imageSave(parameters);
                case "image_scale":
                    return imageScale(parameters);
                case "image_crop":
                    return imageCrop(parameters);
                case "image_rotate":
                    return imageRotate(parameters);
                case "image_generate_color":
                    return imageGenerateColor(parameters);
                case "image_generate_text":
                    return imageGenerateText(parameters);
                    
                // 文件解析功能
                case "file_parse_text":
                    return fileParseText(parameters);
                case "file_parse_csv":
                    return fileParseCsv(parameters);
                case "file_parse_json":
                    return fileParseJson(parameters);
                case "file_read_lines":
                    return fileReadLines(parameters);
                case "file_get_type":
                    return fileGetType(parameters);
                    
                // 网页解析功能
                case "web_parse_html":
                    return webParseHtml(parameters);
                case "web_get_title":
                    return webGetTitle(parameters);
                case "web_get_links":
                    return webGetLinks(parameters);
                case "web_get_images":
                    return webGetImages(parameters);
                case "web_get_text":
                    return webGetText(parameters);
                    
                // 工具信息
                case "get_info":
                    return getToolInfo();
                    
                // 使用教程
                case "get_guide":
                    return getGuide();
                    
                // 意图预测（调试）
                case "predict_intent":
                    return predictIntent(parameters);
                    
                // 生成调试报告
                case "debug_report":
                    return generateDebugReport(parameters);
                    
                default:
                    return new AIToolResult("未知操作: " + action, parameters);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error executing tool: " + e.getMessage(), e);
            return new AIToolResult("执行失败: " + e.getMessage(), parameters);
        }
    }
    
    // ==================== OCR功能 ====================
    
    private AIToolResult ocrRecognize(Map<String, Object> parameters) {
        String imagePath = (String) parameters.get("image_path");
        String language = (String) parameters.get("language");
        
        if (imagePath == null) {
            return new AIToolResult("缺少参数: image_path", parameters);
        }
        
        File imageFile = new File(imagePath);
        if (!imageFile.exists()) {
            return new AIToolResult("图片文件不存在: " + imagePath, parameters);
        }
        
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
            if (bitmap == null) {
                return new AIToolResult("无法解码图片文件", parameters);
            }
            
            // 设置语言（可选）
            if (language != null && !language.isEmpty()) {
                toolkit.setOcrLanguage(language);
            }
            
            // 同步执行OCR
            final String[] result = new String[1];
            final String[] error = new String[1];
            final Object lock = new Object();
            
            synchronized (lock) {
                toolkit.recognizeText(bitmap, new OCRManager.OCRCallback() {
                    @Override
                    public void onSuccess(String text) {
                        synchronized (lock) {
                            result[0] = text;
                            lock.notify();
                        }
                    }
                    
                    @Override
                    public void onFailure(String err) {
                        synchronized (lock) {
                            error[0] = err;
                            lock.notify();
                        }
                    }
                });
                
                try {
                    lock.wait(30000); // 30秒超时
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            if (error[0] != null) {
                return new AIToolResult("OCR识别失败: " + error[0], parameters);
            }
            
            if (result[0] == null || result[0].isEmpty()) {
                return new AIToolResult("未识别到文字", parameters);
            }
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("text", result[0]);
            resultMap.put("language", toolkit.getCurrentOcrLanguage());
            
            return new AIToolResult(resultMap, parameters);
        } catch (Exception e) {
            return new AIToolResult("OCR识别失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult ocrRecognizePdf(Map<String, Object> parameters) {
        String pdfPath = (String) parameters.get("pdf_path");
        
        if (pdfPath == null) {
            return new AIToolResult("缺少参数: pdf_path", parameters);
        }
        
        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists()) {
            return new AIToolResult("PDF文件不存在: " + pdfPath, parameters);
        }
        
        Uri pdfUri = Uri.fromFile(pdfFile);
        
        try {
            final String[] result = new String[1];
            final String[] error = new String[1];
            final Object lock = new Object();
            
            synchronized (lock) {
                toolkit.recognizePdf(pdfUri, new OCRManager.OCRCallback() {
                    @Override
                    public void onSuccess(String text) {
                        synchronized (lock) {
                            result[0] = text;
                            lock.notify();
                        }
                    }
                    
                    @Override
                    public void onFailure(String err) {
                        synchronized (lock) {
                            error[0] = err;
                            lock.notify();
                        }
                    }
                });
                
                try {
                    lock.wait(120000); // 120秒超时
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            if (error[0] != null) {
                return new AIToolResult("PDF识别失败: " + error[0], parameters);
            }
            
            if (result[0] == null || result[0].isEmpty()) {
                return new AIToolResult("PDF中未识别到文字", parameters);
            }
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("text", result[0]);
            resultMap.put("pageCount", result[0].split("--- 第 ").length - 1);
            
            return new AIToolResult(resultMap, parameters);
        } catch (Exception e) {
            return new AIToolResult("PDF识别失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult ocrSetLanguage(Map<String, Object> parameters) {
        String language = (String) parameters.get("language");
        
        if (language == null) {
            return new AIToolResult("缺少参数: language", parameters);
        }
        
        toolkit.setOcrLanguage(language);
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("language", language);
        resultMap.put("message", "OCR语言已设置为: " + language);
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult ocrGetLanguage() {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("language", toolkit.getCurrentOcrLanguage());
        resultMap.put("available_languages", new String[]{
            AppToolkit.OCR_LANG_AUTO,
            AppToolkit.OCR_LANG_CHINESE,
            AppToolkit.OCR_LANG_ENGLISH,
            AppToolkit.OCR_LANG_JAPANESE,
            AppToolkit.OCR_LANG_KOREAN
        });
        
        return new AIToolResult(resultMap, new HashMap<>());
    }
    
    // ==================== 图片处理功能 ====================
    
    private AIToolResult imageSave(Map<String, Object> parameters) {
        String imagePath = (String) parameters.get("image_path");
        String outputPath = (String) parameters.get("output_path");
        String format = (String) parameters.get("format");
        
        if (imagePath == null) {
            return new AIToolResult("缺少参数: image_path", parameters);
        }
        if (outputPath == null) {
            return new AIToolResult("缺少参数: output_path", parameters);
        }
        
        File inputFile = new File(imagePath);
        if (!inputFile.exists()) {
            return new AIToolResult("图片文件不存在: " + imagePath, parameters);
        }
        
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
            if (bitmap == null) {
                return new AIToolResult("无法解码图片文件", parameters);
            }
            
            ImageFormat imageFormat = ImageFormat.PNG;
            if ("jpeg".equalsIgnoreCase(format)) {
                imageFormat = ImageFormat.JPEG;
            } else if ("webp".equalsIgnoreCase(format)) {
                imageFormat = ImageFormat.WEBP;
            }
            
            File outputFile = new File(outputPath);
            boolean success = toolkit.saveBitmap(bitmap, outputFile, imageFormat);
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", success ? "success" : "failed");
            resultMap.put("output_path", outputPath);
            resultMap.put("format", imageFormat.name());
            
            if (success) {
                resultMap.put("message", "图片保存成功");
            } else {
                resultMap.put("message", "图片保存失败");
            }
            
            return new AIToolResult(resultMap, parameters);
        } catch (Exception e) {
            return new AIToolResult("图片保存失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult imageScale(Map<String, Object> parameters) {
        String imagePath = (String) parameters.get("image_path");
        Integer width = (Integer) parameters.get("width");
        Integer height = (Integer) parameters.get("height");
        String outputPath = (String) parameters.get("output_path");
        
        if (imagePath == null) {
            return new AIToolResult("缺少参数: image_path", parameters);
        }
        if (width == null || height == null) {
            return new AIToolResult("缺少参数: width 或 height", parameters);
        }
        
        File inputFile = new File(imagePath);
        if (!inputFile.exists()) {
            return new AIToolResult("图片文件不存在: " + imagePath, parameters);
        }
        
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
            if (bitmap == null) {
                return new AIToolResult("无法解码图片文件", parameters);
            }
            
            Bitmap scaledBitmap = toolkit.scaleBitmap(bitmap, width, height);
            
            if (outputPath != null) {
                File outputFile = new File(outputPath);
                toolkit.saveAsPng(scaledBitmap, outputFile);
                
                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("status", "success");
                resultMap.put("output_path", outputPath);
                resultMap.put("width", scaledBitmap.getWidth());
                resultMap.put("height", scaledBitmap.getHeight());
                resultMap.put("message", "图片缩放成功并保存");
                
                return new AIToolResult(resultMap, parameters);
            } else {
                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("status", "success");
                resultMap.put("width", scaledBitmap.getWidth());
                resultMap.put("height", scaledBitmap.getHeight());
                resultMap.put("message", "图片缩放成功");
                
                return new AIToolResult(resultMap, parameters);
            }
        } catch (Exception e) {
            return new AIToolResult("图片缩放失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult imageCrop(Map<String, Object> parameters) {
        String imagePath = (String) parameters.get("image_path");
        Integer x = (Integer) parameters.get("x");
        Integer y = (Integer) parameters.get("y");
        Integer width = (Integer) parameters.get("width");
        Integer height = (Integer) parameters.get("height");
        String outputPath = (String) parameters.get("output_path");
        
        if (imagePath == null) {
            return new AIToolResult("缺少参数: image_path", parameters);
        }
        if (x == null || y == null || width == null || height == null) {
            return new AIToolResult("缺少参数: x, y, width 或 height", parameters);
        }
        
        File inputFile = new File(imagePath);
        if (!inputFile.exists()) {
            return new AIToolResult("图片文件不存在: " + imagePath, parameters);
        }
        
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
            if (bitmap == null) {
                return new AIToolResult("无法解码图片文件", parameters);
            }
            
            Bitmap croppedBitmap = toolkit.cropBitmap(bitmap, x, y, width, height);
            
            if (outputPath != null) {
                File outputFile = new File(outputPath);
                toolkit.saveAsPng(croppedBitmap, outputFile);
                
                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("status", "success");
                resultMap.put("output_path", outputPath);
                resultMap.put("message", "图片裁剪成功并保存");
                
                return new AIToolResult(resultMap, parameters);
            } else {
                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("status", "success");
                resultMap.put("message", "图片裁剪成功");
                
                return new AIToolResult(resultMap, parameters);
            }
        } catch (Exception e) {
            return new AIToolResult("图片裁剪失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult imageRotate(Map<String, Object> parameters) {
        String imagePath = (String) parameters.get("image_path");
        Float degrees = (Float) parameters.get("degrees");
        String outputPath = (String) parameters.get("output_path");
        
        if (imagePath == null) {
            return new AIToolResult("缺少参数: image_path", parameters);
        }
        if (degrees == null) {
            return new AIToolResult("缺少参数: degrees", parameters);
        }
        
        File inputFile = new File(imagePath);
        if (!inputFile.exists()) {
            return new AIToolResult("图片文件不存在: " + imagePath, parameters);
        }
        
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
            if (bitmap == null) {
                return new AIToolResult("无法解码图片文件", parameters);
            }
            
            Bitmap rotatedBitmap = toolkit.rotateBitmap(bitmap, degrees);
            
            if (outputPath != null) {
                File outputFile = new File(outputPath);
                toolkit.saveAsPng(rotatedBitmap, outputFile);
                
                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("status", "success");
                resultMap.put("output_path", outputPath);
                resultMap.put("degrees", degrees);
                resultMap.put("message", "图片旋转成功并保存");
                
                return new AIToolResult(resultMap, parameters);
            } else {
                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("status", "success");
                resultMap.put("degrees", degrees);
                resultMap.put("message", "图片旋转成功");
                
                return new AIToolResult(resultMap, parameters);
            }
        } catch (Exception e) {
            return new AIToolResult("图片旋转失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult imageGenerateColor(Map<String, Object> parameters) {
        Integer width = (Integer) parameters.get("width");
        Integer height = (Integer) parameters.get("height");
        String color = (String) parameters.get("color");
        String outputPath = (String) parameters.get("output_path");
        String format = (String) parameters.get("format");
        
        if (width == null || height == null) {
            return new AIToolResult("缺少参数: width 或 height", parameters);
        }
        if (outputPath == null) {
            return new AIToolResult("缺少参数: output_path", parameters);
        }
        
        // 默认白色背景
        int colorInt = 0xFFFFFFFF;
        if (color != null) {
            try {
                if (color.startsWith("#")) {
                    colorInt = android.graphics.Color.parseColor(color);
                } else {
                    colorInt = Integer.parseInt(color, 16);
                }
            } catch (Exception e) {
                // 保持默认颜色
            }
        }
        
        ImageFormat imageFormat = ImageFormat.PNG;
        if ("jpeg".equalsIgnoreCase(format)) {
            imageFormat = ImageFormat.JPEG;
        } else if ("webp".equalsIgnoreCase(format)) {
            imageFormat = ImageFormat.WEBP;
        }
        
        File outputFile = new File(outputPath);
        boolean success = toolkit.generateSolidColorImage(outputFile, width, height, colorInt, imageFormat);
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", success ? "success" : "failed");
        resultMap.put("output_path", outputPath);
        resultMap.put("width", width);
        resultMap.put("height", height);
        resultMap.put("color", color);
        
        if (success) {
            resultMap.put("message", "纯色图片生成成功");
        } else {
            resultMap.put("message", "纯色图片生成失败");
        }
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult imageGenerateText(Map<String, Object> parameters) {
        Integer width = (Integer) parameters.get("width");
        Integer height = (Integer) parameters.get("height");
        String backgroundColor = (String) parameters.get("background_color");
        String text = (String) parameters.get("text");
        String textColor = (String) parameters.get("text_color");
        Float textSize = (Float) parameters.get("text_size");
        String outputPath = (String) parameters.get("output_path");
        String format = (String) parameters.get("format");
        
        if (width == null || height == null) {
            return new AIToolResult("缺少参数: width 或 height", parameters);
        }
        if (outputPath == null) {
            return new AIToolResult("缺少参数: output_path", parameters);
        }
        
        // 默认白色背景
        int bgColor = 0xFFFFFFFF;
        if (backgroundColor != null) {
            try {
                bgColor = android.graphics.Color.parseColor(backgroundColor);
            } catch (Exception e) {
            }
        }
        
        // 默认黑色文字
        int txtColor = 0xFF000000;
        if (textColor != null) {
            try {
                txtColor = android.graphics.Color.parseColor(textColor);
            } catch (Exception e) {
            }
        }
        
        // 默认字体大小
        float size = textSize != null ? textSize : 24f;
        
        ImageFormat imageFormat = ImageFormat.PNG;
        if ("jpeg".equalsIgnoreCase(format)) {
            imageFormat = ImageFormat.JPEG;
        } else if ("webp".equalsIgnoreCase(format)) {
            imageFormat = ImageFormat.WEBP;
        }
        
        File outputFile = new File(outputPath);
        boolean success = toolkit.generateTextImage(outputFile, width, height, bgColor, text, txtColor, size, imageFormat);
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", success ? "success" : "failed");
        resultMap.put("output_path", outputPath);
        resultMap.put("width", width);
        resultMap.put("height", height);
        
        if (success) {
            resultMap.put("message", "文字图片生成成功");
        } else {
            resultMap.put("message", "文字图片生成失败");
        }
        
        return new AIToolResult(resultMap, parameters);
    }
    
    // ==================== 文件解析功能 ====================
    
    private AIToolResult fileParseText(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        String content = toolkit.parseTextFile(file);
        
        if (content == null) {
            return new AIToolResult("解析文件失败", parameters);
        }
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("content", content);
        resultMap.put("length", content.length());
        resultMap.put("fileName", file.getName());
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult fileParseCsv(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        List<String[]> data = toolkit.parseCsvFile(file);
        
        if (data == null) {
            return new AIToolResult("解析CSV文件失败", parameters);
        }
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("rowCount", data.size());
        resultMap.put("columnCount", data.size() > 0 ? data.get(0).length : 0);
        resultMap.put("data", data);
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult fileParseJson(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String type = (String) parameters.get("type");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        Object resultObj = null;
        String resultType = "";
        
        if ("array".equalsIgnoreCase(type)) {
            resultObj = toolkit.parseJsonArrayFile(file);
            resultType = "array";
        } else if ("map".equalsIgnoreCase(type)) {
            resultObj = toolkit.parseJsonToMap(file);
            resultType = "map";
        } else {
            resultObj = toolkit.parseJsonFile(file);
            resultType = "object";
        }
        
        if (resultObj == null) {
            return new AIToolResult("解析JSON文件失败", parameters);
        }
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("type", resultType);
        resultMap.put("data", resultObj);
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult fileReadLines(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        Integer startLine = (Integer) parameters.get("start_line");
        Integer endLine = (Integer) parameters.get("end_line");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        List<String> lines = toolkit.readLines(file);
        
        if (lines == null) {
            return new AIToolResult("读取文件失败", parameters);
        }
        
        // 截取指定范围
        int start = startLine != null ? startLine - 1 : 0;
        int end = endLine != null ? endLine : lines.size();
        
        if (start < 0) start = 0;
        if (end > lines.size()) end = lines.size();
        
        List<String> resultLines = lines.subList(start, end);
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("lines", resultLines);
        resultMap.put("totalLines", lines.size());
        resultMap.put("startLine", start + 1);
        resultMap.put("endLine", end);
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult fileGetType(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        FileParserUtil.FileType type = toolkit.getFileType(file);
        String extension = toolkit.getFileExtension(file);
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("type", type.name());
        resultMap.put("extension", extension);
        resultMap.put("fileName", file.getName());
        resultMap.put("size", file.length());
        
        return new AIToolResult(resultMap, parameters);
    }
    
    // ==================== 网页解析功能 ====================
    
    private AIToolResult webParseHtml(Map<String, Object> parameters) {
        String source = (String) parameters.get("source");
        String sourceType = (String) parameters.get("source_type");
        
        if (source == null) {
            return new AIToolResult("缺少参数: source", parameters);
        }
        
        org.jsoup.nodes.Document doc = null;
        
        if ("file".equalsIgnoreCase(sourceType)) {
            File file = new File(source);
            if (!file.exists()) {
                return new AIToolResult("文件不存在: " + source, parameters);
            }
            doc = toolkit.parseHtmlFromFile(file);
        } else {
            doc = toolkit.parseHtmlFromString(source);
        }
        
        if (doc == null) {
            return new AIToolResult("解析HTML失败", parameters);
        }
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("title", doc.title());
        resultMap.put("hasBody", doc.body() != null);
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult webGetTitle(Map<String, Object> parameters) {
        String source = (String) parameters.get("source");
        String sourceType = (String) parameters.get("source_type");
        
        if (source == null) {
            return new AIToolResult("缺少参数: source", parameters);
        }
        
        org.jsoup.nodes.Document doc = null;
        
        if ("file".equalsIgnoreCase(sourceType)) {
            File file = new File(source);
            if (!file.exists()) {
                return new AIToolResult("文件不存在: " + source, parameters);
            }
            doc = toolkit.parseHtmlFromFile(file);
        } else {
            doc = toolkit.parseHtmlFromString(source);
        }
        
        if (doc == null) {
            return new AIToolResult("解析HTML失败", parameters);
        }
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("title", doc.title());
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult webGetLinks(Map<String, Object> parameters) {
        String source = (String) parameters.get("source");
        String sourceType = (String) parameters.get("source_type");
        
        if (source == null) {
            return new AIToolResult("缺少参数: source", parameters);
        }
        
        org.jsoup.nodes.Document doc = null;
        
        if ("file".equalsIgnoreCase(sourceType)) {
            File file = new File(source);
            if (!file.exists()) {
                return new AIToolResult("文件不存在: " + source, parameters);
            }
            doc = toolkit.parseHtmlFromFile(file);
        } else {
            doc = toolkit.parseHtmlFromString(source);
        }
        
        if (doc == null) {
            return new AIToolResult("解析HTML失败", parameters);
        }
        
        List<String> links = toolkit.getAllLinks(doc);
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("links", links);
        resultMap.put("count", links.size());
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult webGetImages(Map<String, Object> parameters) {
        String source = (String) parameters.get("source");
        String sourceType = (String) parameters.get("source_type");
        
        if (source == null) {
            return new AIToolResult("缺少参数: source", parameters);
        }
        
        org.jsoup.nodes.Document doc = null;
        
        if ("file".equalsIgnoreCase(sourceType)) {
            File file = new File(source);
            if (!file.exists()) {
                return new AIToolResult("文件不存在: " + source, parameters);
            }
            doc = toolkit.parseHtmlFromFile(file);
        } else {
            doc = toolkit.parseHtmlFromString(source);
        }
        
        if (doc == null) {
            return new AIToolResult("解析HTML失败", parameters);
        }
        
        List<String> images = toolkit.getAllImages(doc);
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("images", images);
        resultMap.put("count", images.size());
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult webGetText(Map<String, Object> parameters) {
        String source = (String) parameters.get("source");
        String sourceType = (String) parameters.get("source_type");
        
        if (source == null) {
            return new AIToolResult("缺少参数: source", parameters);
        }
        
        org.jsoup.nodes.Document doc = null;
        
        if ("file".equalsIgnoreCase(sourceType)) {
            File file = new File(source);
            if (!file.exists()) {
                return new AIToolResult("文件不存在: " + source, parameters);
            }
            doc = toolkit.parseHtmlFromFile(file);
        } else {
            doc = toolkit.parseHtmlFromString(source);
        }
        
        if (doc == null) {
            return new AIToolResult("解析HTML失败", parameters);
        }
        
        String text = toolkit.getHtmlPlainText(doc);
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("text", text);
        resultMap.put("length", text != null ? text.length() : 0);
        
        return new AIToolResult(resultMap, parameters);
    }
    
    // ==================== 工具信息 ====================
    
    private AIToolResult getToolInfo() {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("name", getName());
        resultMap.put("description", getDescription());
        
        Map<String, String> categories = new HashMap<>();
        categories.put("ocr", "OCR文字识别");
        categories.put("image", "图片处理");
        categories.put("file", "文件解析");
        categories.put("web", "网页解析");
        resultMap.put("categories", categories);
        
        Map<String, String> ocrActions = new HashMap<>();
        ocrActions.put("ocr_recognize", "识别图片文字");
        ocrActions.put("ocr_recognize_pdf", "识别PDF文字");
        ocrActions.put("ocr_set_language", "设置OCR语言");
        ocrActions.put("ocr_get_language", "获取当前语言");
        
        Map<String, String> imageActions = new HashMap<>();
        imageActions.put("image_save", "保存图片");
        imageActions.put("image_scale", "缩放图片");
        imageActions.put("image_crop", "裁剪图片");
        imageActions.put("image_rotate", "旋转图片");
        imageActions.put("image_generate_color", "生成纯色图片");
        imageActions.put("image_generate_text", "生成文字图片");
        
        Map<String, String> fileActions = new HashMap<>();
        fileActions.put("file_parse_text", "解析文本文件");
        fileActions.put("file_parse_csv", "解析CSV文件");
        fileActions.put("file_parse_json", "解析JSON文件");
        fileActions.put("file_read_lines", "按行读取文件");
        fileActions.put("file_get_type", "获取文件类型");
        
        Map<String, String> webActions = new HashMap<>();
        webActions.put("web_parse_html", "解析HTML");
        webActions.put("web_get_title", "获取网页标题");
        webActions.put("web_get_links", "获取所有链接");
        webActions.put("web_get_images", "获取所有图片");
        webActions.put("web_get_text", "获取网页正文");
        
        Map<String, Map<String, String>> actions = new HashMap<>();
        actions.put("ocr", ocrActions);
        actions.put("image", imageActions);
        actions.put("file", fileActions);
        actions.put("web", webActions);
        
        resultMap.put("actions", actions);
        
        return new AIToolResult(resultMap, new HashMap<>());
    }
    
    private AIToolResult getGuide() {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("guide", AIToolUsageGuide.getUsageGuide());
        
        return new AIToolResult(resultMap, new HashMap<>());
    }
    
    private AIToolResult predictIntent(Map<String, Object> parameters) {
        String message = (String) parameters.get("message");
        
        if (message == null || message.isEmpty()) {
            return new AIToolResult("缺少参数: message", parameters);
        }
        
        String result = AIToolUsageGuide.predictIntent(message);
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("prediction", result);
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult generateDebugReport(Map<String, Object> parameters) {
        String message = (String) parameters.get("message");
        
        if (message == null || message.isEmpty()) {
            return new AIToolResult("缺少参数: message", parameters);
        }
        
        String report = AIToolUsageGuide.generateDebugReport(message);
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("report", report);
        
        return new AIToolResult(resultMap, parameters);
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        
        // 通用参数
        descriptions.put("action", "操作类型（必填）");
        
        // OCR参数
        descriptions.put("image_path", "图片文件路径（用于OCR和图片操作）");
        descriptions.put("language", "OCR语言: auto, chinese, english, japanese, korean");
        descriptions.put("pdf_path", "PDF文件路径（用于PDF识别）");
        
        // 图片处理参数
        descriptions.put("output_path", "输出文件路径");
        descriptions.put("format", "图片格式: jpeg, png, webp");
        descriptions.put("width", "宽度（像素）");
        descriptions.put("height", "高度（像素）");
        descriptions.put("x", "裁剪起始X坐标");
        descriptions.put("y", "裁剪起始Y坐标");
        descriptions.put("degrees", "旋转角度");
        descriptions.put("color", "颜色值（如 #FFFFFF 或 FF000000）");
        descriptions.put("background_color", "背景颜色");
        descriptions.put("text", "文字内容");
        descriptions.put("text_color", "文字颜色");
        descriptions.put("text_size", "文字大小");
        
        // 文件解析参数
        descriptions.put("file_path", "文件路径");
        descriptions.put("type", "JSON类型: object, array, map");
        descriptions.put("start_line", "起始行号");
        descriptions.put("end_line", "结束行号");
        
        // 网页解析参数
        descriptions.put("source", "HTML源（字符串或文件路径）");
        descriptions.put("source_type", "源类型: string, file");
        
        return descriptions;
    }
}