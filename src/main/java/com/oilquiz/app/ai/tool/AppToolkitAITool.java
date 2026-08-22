package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.manager.ImageLabelManager;
import com.oilquiz.app.manager.ObjectDetectionManager;
import com.oilquiz.app.manager.OCRManager;
import com.oilquiz.app.toolkit.AppToolkit;
import com.oilquiz.app.util.ImageGeneratorUtil.ImageFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Tool(
    value = "app_toolkit",
    description = "应用工具集，聚合天气/计算/OCR/图像/网页等能力，通过action指定具体操作",
    category = "utility",
    aliases = {"toolkit", "工具集", "工具箱"},
    actions = {
        @Action(name = "weather_current", description = "查询当前天气"),
        @Action(name = "weather_forecast", description = "查询天气预报"),
        @Action(name = "calculate", description = "数学计算"),
        @Action(name = "ocr_recognize", description = "OCR文字识别"),
        @Action(name = "image_label", description = "图片标签识别"),
        @Action(name = "object_detect", description = "物体检测"),
        @Action(name = "webpage_parse", description = "网页解析")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作类型", required = true),
        @Param(name = "command", type = "string", description = "命令参数", required = false)
    }
)
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
        return "应用工具集，聚合天气/计算/OCR/图像/网页等能力，通过action指定具体操作";
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = (String) parameters.get("action");
            if (action == null || action.isEmpty()) {
                return new AIToolResult("缺少参数: action", parameters);
            }

            normalizePathParams(action, parameters);
            
            switch (action) {
                // 天气功能
                case "weather_current":
                    return getCurrentWeather(parameters);
                case "weather_forecast":
                    return getWeatherForecast(parameters);
                case "weather_hourly":
                    return getWeatherHourly(parameters);
                case "weather_air":
                    return getWeatherAirQuality(parameters);
                case "weather_alerts":
                    return getWeatherAlerts(parameters);
                case "weather_indices":
                    return getWeatherIndices(parameters);
                case "weather_all":
                    return getAllWeatherInfo(parameters);
                    
                // 计算功能
                case "calculate":
                    return calculate(parameters);
                    
                // OCR功能
                case "ocr_recognize":
                    return ocrRecognize(parameters);
                case "ocr_recognize_pdf":
                    return ocrRecognizePdf(parameters);
                case "ocr_set_language":
                    return ocrSetLanguage(parameters);
                case "ocr_get_language":
                    return ocrGetLanguage();
                    
                // 图像标签识别功能
                case "image_label_recognize":
                    return imageLabelRecognize(parameters);
                case "image_label_set_threshold":
                    return imageLabelSetThreshold(parameters);
                case "image_label_get_threshold":
                    return imageLabelGetThreshold();
                case "image_label_load_custom_model":
                    return imageLabelLoadCustomModel(parameters);
                    
                // 目标检测功能
                case "object_detect":
                    return objectDetect(parameters);
                case "object_set_threshold":
                    return objectSetThreshold(parameters);
                case "object_get_threshold":
                    return objectGetThreshold();
                case "object_set_multiple":
                    return objectSetMultiple(parameters);
                case "object_set_classification":
                    return objectSetClassification(parameters);
                    
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

    private void normalizePathParams(String action, Map<String, Object> parameters) {
        if (action == null) return;

        switch (action) {
            case "ocr_recognize":
                if (!parameters.containsKey("image_path")) {
                    if (parameters.containsKey("file_path")) {
                        parameters.put("image_path", parameters.get("file_path"));
                    } else if (parameters.containsKey("path")) {
                        parameters.put("image_path", parameters.get("path"));
                    }
                }
                break;
            case "ocr_recognize_pdf":
                if (!parameters.containsKey("pdf_path")) {
                    if (parameters.containsKey("file_path")) {
                        parameters.put("pdf_path", parameters.get("file_path"));
                    } else if (parameters.containsKey("path")) {
                        parameters.put("pdf_path", parameters.get("path"));
                    }
                }
                break;
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
            // 优先使用在线视觉模型 OCR，失败自动回退本地 ML Kit
            com.oilquiz.app.manager.OCRManager ocrManager = toolkit.getOcrManager();
            String resultText = ocrManager.recognizeFileOnlineFirst(imagePath, language)
                    .get(60, java.util.concurrent.TimeUnit.SECONDS);
            
            if (resultText == null || resultText.isEmpty()) {
                return new AIToolResult("未识别到文字", parameters);
            }
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("text", resultText);
            resultMap.put("engine", "online_vision"); // 标记使用的引擎
            resultMap.put("language", language != null ? language : "auto");
            
            return new AIToolResult(resultMap, parameters);
        } catch (java.util.concurrent.TimeoutException e) {
            return new AIToolResult("OCR识别超时，请稍后重试", parameters);
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
    
    // ==================== 图像标签识别功能 ====================
    
    private AIToolResult imageLabelRecognize(Map<String, Object> parameters) {
        String imagePath = (String) parameters.get("image_path");
        Object thresholdObj = parameters.get("threshold");
        Float threshold = thresholdObj != null ? ((Number) thresholdObj).floatValue() : null;
        
        if (imagePath == null) {
            return new AIToolResult("缺少参数: image_path", parameters);
        }
        
        File imageFile = new File(imagePath);
        if (!imageFile.exists()) {
            return new AIToolResult("图片文件不存在: " + imagePath, parameters);
        }
        
        try {
            // 采样解码：图像识别最长边限制 2048，避免大图全尺寸解码撑爆内存
            Bitmap bitmap = com.oilquiz.app.util.ImageParserUtil.parseImage(imageFile, 2048, 2048);
            if (bitmap == null) {
                return new AIToolResult("无法解码图片文件", parameters);
            }
            
            if (threshold != null) {
                toolkit.setImageLabelConfidenceThreshold(threshold);
            }
            
            final java.util.List<ImageLabelManager.ImageLabelResult>[] resultWrapper = new java.util.List[1];
            final String[] error = new String[1];
            final boolean[] notified = new boolean[1];
            final Object lock = new Object();
            
            synchronized (lock) {
                toolkit.recognizeImageLabels(bitmap, new ImageLabelManager.ImageLabelCallback() {
                    @Override
                    public void onSuccess(java.util.List<ImageLabelManager.ImageLabelResult> results) {
                        synchronized (lock) {
                            notified[0] = true;
                            resultWrapper[0] = results;
                            lock.notify();
                        }
                    }
                    
                    @Override
                    public void onFailure(String err) {
                        synchronized (lock) {
                            notified[0] = true;
                            error[0] = err;
                            lock.notify();
                        }
                    }
                });
                
                try {
                    lock.wait(30000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            // 超时（30s 内未收到回调）→ 明确报错，不再伪装成"未识别到标签"
            if (!notified[0]) {
                return new AIToolResult("图像标签识别超时(30秒)，请重试", parameters);
            }

            if (error[0] != null) {
                return new AIToolResult("图像标签识别失败: " + error[0], parameters);
            }
            
            if (resultWrapper[0] == null || resultWrapper[0].isEmpty()) {
                Map<String, Object> emptyResult = new HashMap<>();
                emptyResult.put("status", "success");
                emptyResult.put("label_count", 0);
                emptyResult.put("labels", new JSONArray());
                emptyResult.put("message", "未识别到任何标签");
                return new AIToolResult(emptyResult, parameters);
            }
            
            org.json.JSONObject jsonResult = toolkit.formatImageLabelsAsJson(resultWrapper[0]);
            jsonResult.put("text_summary", toolkit.formatImageLabelsAsText(resultWrapper[0]));
            jsonResult.put("primary_label", resultWrapper[0].get(0).text);
            
            java.util.List<String> labelTexts = toolkit.getImageLabelManager().getLabelTexts(resultWrapper[0]);
            jsonResult.put("label_texts", new JSONArray(labelTexts));
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("data", jsonResult.toString());
            resultMap.put("label_count", resultWrapper[0].size());
            resultMap.put("primary_label", resultWrapper[0].get(0).text);
            
            return new AIToolResult(resultMap, parameters);
        } catch (Exception e) {
            Log.e(TAG, "图像标签识别失败: " + e.getMessage(), e);
            return new AIToolResult("图像标签识别失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult imageLabelSetThreshold(Map<String, Object> parameters) {
        Object thresholdObj = parameters.get("threshold");
        if (thresholdObj == null) {
            return new AIToolResult("缺少参数: threshold", parameters);
        }
        
        float threshold = ((Number) thresholdObj).floatValue();
        toolkit.setImageLabelConfidenceThreshold(threshold);
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("threshold", toolkit.getImageLabelConfidenceThreshold());
        resultMap.put("message", "置信度阈值已设置为: " + threshold);
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult imageLabelGetThreshold() {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("threshold", toolkit.getImageLabelConfidenceThreshold());
        resultMap.put("model_type", toolkit.getCurrentImageLabelModelType());
        
        return new AIToolResult(resultMap, new HashMap<>());
    }
    
    private AIToolResult imageLabelLoadCustomModel(Map<String, Object> parameters) {
        String modelPath = (String) parameters.get("model_path");
        String labelPath = (String) parameters.get("label_path");
        
        if (modelPath == null) {
            return new AIToolResult("缺少参数: model_path", parameters);
        }
        
        boolean success = toolkit.loadCustomImageLabelModel(modelPath, labelPath);
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", success ? "success" : "failed");
        resultMap.put("model_type", toolkit.getCurrentImageLabelModelType());
        
        if (success) {
            resultMap.put("message", "自定义模型加载成功");
        } else {
            resultMap.put("message", "自定义模型加载失败");
        }
        
        return new AIToolResult(resultMap, parameters);
    }
    
    // ==================== 目标检测功能 ====================
    
    private AIToolResult objectDetect(Map<String, Object> parameters) {
        String imagePath = (String) parameters.get("image_path");
        Object thresholdObj = parameters.get("threshold");
        Float threshold = thresholdObj != null ? ((Number) thresholdObj).floatValue() : null;
        Object multipleObj = parameters.get("multiple_objects");
        Boolean multipleObjects = parseBooleanParam(parameters, "multiple_objects");
        Object classificationObj = parameters.get("enable_classification");
        Boolean enableClassification = parseBooleanParam(parameters, "enable_classification");
        
        if (imagePath == null) {
            return new AIToolResult("缺少参数: image_path", parameters);
        }
        
        File imageFile = new File(imagePath);
        if (!imageFile.exists()) {
            return new AIToolResult("图片文件不存在: " + imagePath, parameters);
        }
        
        try {
            // 采样解码：目标检测最长边限制 2048，避免大图全尺寸解码撑爆内存
            Bitmap bitmap = com.oilquiz.app.util.ImageParserUtil.parseImage(imageFile, 2048, 2048);
            if (bitmap == null) {
                return new AIToolResult("无法解码图片文件", parameters);
            }
            
            if (threshold != null) {
                toolkit.setObjectDetectionConfidenceThreshold(threshold);
            }
            if (multipleObjects != null) {
                toolkit.setObjectDetectionMultipleObjects(multipleObjects);
            }
            if (enableClassification != null) {
                toolkit.setObjectDetectionClassification(enableClassification);
            }
            
            final java.util.List<ObjectDetectionManager.DetectedObjectResult>[] resultWrapper = new java.util.List[1];
            final String[] error = new String[1];
            final boolean[] notified = new boolean[1];
            final Object lock = new Object();
            
            synchronized (lock) {
                toolkit.detectObjects(bitmap, new ObjectDetectionManager.ObjectDetectionCallback() {
                    @Override
                    public void onSuccess(java.util.List<ObjectDetectionManager.DetectedObjectResult> results) {
                        synchronized (lock) {
                            notified[0] = true;
                            resultWrapper[0] = results;
                            lock.notify();
                        }
                    }
                    
                    @Override
                    public void onFailure(String err) {
                        synchronized (lock) {
                            notified[0] = true;
                            error[0] = err;
                            lock.notify();
                        }
                    }
                });
                
                try {
                    lock.wait(30000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            // 超时（30s 内未收到回调）→ 明确报错，不再伪装成"未检测到"
            if (!notified[0]) {
                return new AIToolResult("目标检测超时(30秒)，请重试", parameters);
            }

            if (error[0] != null) {
                return new AIToolResult("目标检测失败: " + error[0], parameters);
            }
            
            if (resultWrapper[0] == null || resultWrapper[0].isEmpty()) {
                Map<String, Object> emptyResult = new HashMap<>();
                emptyResult.put("status", "success");
                emptyResult.put("object_count", 0);
                emptyResult.put("objects", new JSONArray());
                emptyResult.put("message", "未检测到任何物体");
                return new AIToolResult(emptyResult, parameters);
            }
            
            org.json.JSONObject jsonResult = toolkit.formatDetectedObjectsAsJson(resultWrapper[0]);
            jsonResult.put("text_summary", toolkit.formatDetectedObjectsAsText(resultWrapper[0]));
            
            java.util.List<String> detectedLabels = toolkit.getObjectDetectionManager().getDetectedLabels(resultWrapper[0]);
            jsonResult.put("detected_labels", new JSONArray(detectedLabels));
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("data", jsonResult.toString());
            resultMap.put("object_count", resultWrapper[0].size());
            
            return new AIToolResult(resultMap, parameters);
        } catch (Exception e) {
            Log.e(TAG, "目标检测失败: " + e.getMessage(), e);
            return new AIToolResult("目标检测失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult objectSetThreshold(Map<String, Object> parameters) {
        Object thresholdObj = parameters.get("threshold");
        if (thresholdObj == null) {
            return new AIToolResult("缺少参数: threshold", parameters);
        }
        
        float threshold = ((Number) thresholdObj).floatValue();
        toolkit.setObjectDetectionConfidenceThreshold(threshold);
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("threshold", toolkit.getObjectDetectionConfidenceThreshold());
        resultMap.put("message", "置信度阈值已设置为: " + threshold);
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult objectGetThreshold() {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("threshold", toolkit.getObjectDetectionConfidenceThreshold());
        resultMap.put("multiple_objects", toolkit.isObjectDetectionMultipleObjects());
        resultMap.put("enable_classification", toolkit.isObjectDetectionClassification());
        resultMap.put("detection_mode", toolkit.getObjectDetectionMode());
        
        return new AIToolResult(resultMap, new HashMap<>());
    }
    
    private AIToolResult objectSetMultiple(Map<String, Object> parameters) {
        Object enableObj = parameters.get("enable");
        if (enableObj == null) {
            return new AIToolResult("缺少参数: enable", parameters);
        }
        
        boolean enable = Boolean.TRUE.equals(parseBooleanParam(parameters, "enable"));
        toolkit.setObjectDetectionMultipleObjects(enable);
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("multiple_objects", toolkit.isObjectDetectionMultipleObjects());
        resultMap.put("message", enable ? "已启用多物体检测" : "已禁用多物体检测");
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult objectSetClassification(Map<String, Object> parameters) {
        Object enableObj = parameters.get("enable");
        if (enableObj == null) {
            return new AIToolResult("缺少参数: enable", parameters);
        }
        
        boolean enable = Boolean.TRUE.equals(parseBooleanParam(parameters, "enable"));
        toolkit.setObjectDetectionClassification(enable);
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("enable_classification", toolkit.isObjectDetectionClassification());
        resultMap.put("message", enable ? "已启用物体分类" : "已禁用物体分类");
        
        return new AIToolResult(resultMap, parameters);
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
            Bitmap bitmap = com.oilquiz.app.util.ImageParserUtil.parseImage(inputFile, 4096, 4096);
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
            Bitmap bitmap = com.oilquiz.app.util.ImageParserUtil.parseImage(inputFile, 4096, 4096);
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
            Bitmap bitmap = com.oilquiz.app.util.ImageParserUtil.parseImage(inputFile, 4096, 4096);
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
        Float degrees = parseFloatParam(parameters, "degrees");
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
            Bitmap bitmap = com.oilquiz.app.util.ImageParserUtil.parseImage(inputFile, 4096, 4096);
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
        Float textSize = parseFloatParam(parameters, "text_size");
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
        categories.put("calculate", "数学计算");
        categories.put("weather", "天气查询");
        categories.put("ocr", "OCR文字识别");
        categories.put("image_label", "图像标签识别");
        categories.put("object_detection", "目标检测");
        categories.put("image", "图片处理");
        categories.put("web", "网页解析");
        resultMap.put("categories", categories);
        
        Map<String, String> calculateActions = new HashMap<>();
        calculateActions.put("calculate", "执行数学计算");
        
        Map<String, String> weatherActions = new HashMap<>();
        weatherActions.put("weather_current", "获取当前天气");
        weatherActions.put("weather_forecast", "获取未来天气预报");
        weatherActions.put("weather_hourly", "获取24小时逐时预报");
        weatherActions.put("weather_air", "获取空气质量");
        weatherActions.put("weather_alerts", "获取天气预警");
        weatherActions.put("weather_indices", "获取生活指数");
        weatherActions.put("weather_all", "获取全部天气信息");
        
        Map<String, String> ocrActions = new HashMap<>();
        ocrActions.put("ocr_recognize", "识别图片文字");
        ocrActions.put("ocr_recognize_pdf", "识别PDF文字");
        ocrActions.put("ocr_set_language", "设置OCR语言");
        ocrActions.put("ocr_get_language", "获取当前语言");
        
        Map<String, String> imageLabelActions = new HashMap<>();
        imageLabelActions.put("image_label_recognize", "识别图像标签");
        imageLabelActions.put("image_label_set_threshold", "设置置信度阈值");
        imageLabelActions.put("image_label_get_threshold", "获取当前阈值");
        imageLabelActions.put("image_label_load_custom_model", "加载自定义模型");
        
        Map<String, String> objectDetectionActions = new HashMap<>();
        objectDetectionActions.put("object_detect", "检测图像中的物体");
        objectDetectionActions.put("object_set_threshold", "设置置信度阈值");
        objectDetectionActions.put("object_get_threshold", "获取当前配置");
        objectDetectionActions.put("object_set_multiple", "启用/禁用多物体检测");
        objectDetectionActions.put("object_set_classification", "启用/禁用物体分类");
        
        Map<String, String> imageActions = new HashMap<>();
        imageActions.put("image_save", "保存图片");
        imageActions.put("image_scale", "缩放图片");
        imageActions.put("image_crop", "裁剪图片");
        imageActions.put("image_rotate", "旋转图片");
        imageActions.put("image_generate_color", "生成纯色图片");
        imageActions.put("image_generate_text", "生成文字图片");
        
        Map<String, String> webActions = new HashMap<>();
        webActions.put("web_parse_html", "解析HTML");
        webActions.put("web_get_title", "获取网页标题");
        webActions.put("web_get_links", "获取所有链接");
        webActions.put("web_get_images", "获取所有图片");
        webActions.put("web_get_text", "获取网页正文");
        
        Map<String, Map<String, String>> actions = new HashMap<>();
        actions.put("calculate", calculateActions);
        actions.put("weather", weatherActions);
        actions.put("ocr", ocrActions);
        actions.put("image_label", imageLabelActions);
        actions.put("object_detection", objectDetectionActions);
        actions.put("image", imageActions);
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
        
        // 计算参数
        descriptions.put("expression", "数学表达式（必填），支持加减乘除、括号、幂运算等，例如: 3+5, (10-2)*3, 2^10");
        
        // 天气参数
        descriptions.put("city", "城市名称或城市ID（天气查询时使用，不传则使用定位）");
        descriptions.put("lat", "纬度（坐标查询天气时使用）");
        descriptions.put("lon", "经度（坐标查询天气时使用）");
        
        // OCR参数
        descriptions.put("image_path", "图片文件路径（用于OCR和图片操作）");
        descriptions.put("language", "OCR语言: auto, chinese, english, japanese, korean");
        descriptions.put("pdf_path", "PDF文件路径（用于PDF识别）");
        
        // 图像标签识别参数
        descriptions.put("threshold", "置信度阈值，范围 0.01-0.99，默认 0.5");
        descriptions.put("model_path", "自定义模型文件路径");
        descriptions.put("label_path", "自定义标签文件路径（可选）");
        
        // 目标检测参数
        descriptions.put("multiple_objects", "是否启用多物体检测，默认 true");
        descriptions.put("enable_classification", "是否启用物体分类，默认 true");
        descriptions.put("enable", "启用/禁用标志，用于设置多物体检测和分类");
        
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
    
    // ==================== 天气功能 ====================
    
    private AIWeatherManager getWeatherManager() {
        return new AIWeatherManager(context);
    }
    
    private AIToolResult getCurrentWeather(Map<String, Object> parameters) {
        try {
            AIWeatherManager weatherManager = getWeatherManager();
            String city = (String) parameters.get("city");
            Double lat = parseDoubleParam(parameters, "lat");
            Double lon = parseDoubleParam(parameters, "lon");
            
            AIWeatherManager.QueryRetryResult result = weatherManager.getCurrentWeatherSmart(city, lat, lon);
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", result.success ? "success" : "failed");
            resultMap.put("data", result.data);
            resultMap.put("provider", result.provider);
            resultMap.put("query_type", result.queryType);
            resultMap.put("attempts", result.attempt);
            resultMap.put("error_message", result.errorMessage);
            
            return result.success ? new AIToolResult(resultMap, parameters)
                    : AIToolResult.fail(String.valueOf(result.errorMessage), resultMap);
        } catch (Exception e) {
            Log.e(TAG, "Error getting current weather", e);
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("status", "failed");
            errorMap.put("error", e.getMessage());
            return AIToolResult.fail(String.valueOf(errorMap.get("error")), errorMap);
        }
    }
    
    private AIToolResult getWeatherForecast(Map<String, Object> parameters) {
        try {
            AIWeatherManager weatherManager = getWeatherManager();
            String city = (String) parameters.get("city");
            Double lat = parseDoubleParam(parameters, "lat");
            Double lon = parseDoubleParam(parameters, "lon");
            
            AIWeatherManager.QueryRetryResult result = weatherManager.getForecastSmart(city, lat, lon);
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", result.success ? "success" : "failed");
            resultMap.put("data", result.data);
            resultMap.put("provider", result.provider);
            resultMap.put("query_type", result.queryType);
            resultMap.put("attempts", result.attempt);
            resultMap.put("error_message", result.errorMessage);
            
            return result.success ? new AIToolResult(resultMap, parameters)
                    : AIToolResult.fail(String.valueOf(result.errorMessage), resultMap);
        } catch (Exception e) {
            Log.e(TAG, "Error getting weather forecast", e);
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("status", "failed");
            errorMap.put("error", e.getMessage());
            return AIToolResult.fail(String.valueOf(errorMap.get("error")), errorMap);
        }
    }
    
    private AIToolResult getWeatherHourly(Map<String, Object> parameters) {
        try {
            AIWeatherManager weatherManager = getWeatherManager();
            String city = (String) parameters.get("city");
            Double lat = parseDoubleParam(parameters, "lat");
            Double lon = parseDoubleParam(parameters, "lon");
            
            AIWeatherManager.QueryRetryResult result = weatherManager.getHourlySmart(city, lat, lon);
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", result.success ? "success" : "failed");
            resultMap.put("data", result.data);
            resultMap.put("provider", result.provider);
            resultMap.put("query_type", result.queryType);
            resultMap.put("attempts", result.attempt);
            resultMap.put("error_message", result.errorMessage);
            
            return result.success ? new AIToolResult(resultMap, parameters)
                    : AIToolResult.fail(String.valueOf(result.errorMessage), resultMap);
        } catch (Exception e) {
            Log.e(TAG, "Error getting hourly weather", e);
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("status", "failed");
            errorMap.put("error", e.getMessage());
            return AIToolResult.fail(String.valueOf(errorMap.get("error")), errorMap);
        }
    }
    
    private AIToolResult getWeatherAirQuality(Map<String, Object> parameters) {
        try {
            AIWeatherManager weatherManager = getWeatherManager();
            String city = (String) parameters.get("city");
            Double lat = parseDoubleParam(parameters, "lat");
            Double lon = parseDoubleParam(parameters, "lon");
            
            AIWeatherManager.QueryRetryResult result = weatherManager.getAirQualitySmart(city, lat, lon);
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", result.success ? "success" : "failed");
            resultMap.put("data", result.data);
            resultMap.put("provider", result.provider);
            resultMap.put("query_type", result.queryType);
            resultMap.put("attempts", result.attempt);
            resultMap.put("error_message", result.errorMessage);
            
            return result.success ? new AIToolResult(resultMap, parameters)
                    : AIToolResult.fail(String.valueOf(result.errorMessage), resultMap);
        } catch (Exception e) {
            Log.e(TAG, "Error getting air quality", e);
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("status", "failed");
            errorMap.put("error", e.getMessage());
            return AIToolResult.fail(String.valueOf(errorMap.get("error")), errorMap);
        }
    }
    
    private AIToolResult getWeatherAlerts(Map<String, Object> parameters) {
        try {
            AIWeatherManager weatherManager = getWeatherManager();
            String city = (String) parameters.get("city");
            Double lat = parseDoubleParam(parameters, "lat");
            Double lon = parseDoubleParam(parameters, "lon");
            
            AIWeatherManager.QueryRetryResult result = weatherManager.getAlertsSmart(city, lat, lon);
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", result.success ? "success" : "failed");
            resultMap.put("data", result.data);
            resultMap.put("provider", result.provider);
            resultMap.put("query_type", result.queryType);
            resultMap.put("attempts", result.attempt);
            resultMap.put("error_message", result.errorMessage);
            
            return result.success ? new AIToolResult(resultMap, parameters)
                    : AIToolResult.fail(String.valueOf(result.errorMessage), resultMap);
        } catch (Exception e) {
            Log.e(TAG, "Error getting weather alerts", e);
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("status", "failed");
            errorMap.put("error", e.getMessage());
            return AIToolResult.fail(String.valueOf(errorMap.get("error")), errorMap);
        }
    }
    
    private AIToolResult getWeatherIndices(Map<String, Object> parameters) {
        try {
            AIWeatherManager weatherManager = getWeatherManager();
            String city = (String) parameters.get("city");
            Double lat = parseDoubleParam(parameters, "lat");
            Double lon = parseDoubleParam(parameters, "lon");
            
            AIWeatherManager.QueryRetryResult result = weatherManager.getIndicesSmart(city, lat, lon);
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", result.success ? "success" : "failed");
            resultMap.put("data", result.data);
            resultMap.put("provider", result.provider);
            resultMap.put("query_type", result.queryType);
            resultMap.put("attempts", result.attempt);
            resultMap.put("error_message", result.errorMessage);
            
            return result.success ? new AIToolResult(resultMap, parameters)
                    : AIToolResult.fail(String.valueOf(result.errorMessage), resultMap);
        } catch (Exception e) {
            Log.e(TAG, "Error getting weather indices", e);
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("status", "failed");
            errorMap.put("error", e.getMessage());
            return AIToolResult.fail(String.valueOf(errorMap.get("error")), errorMap);
        }
    }
    
    private AIToolResult getAllWeatherInfo(Map<String, Object> parameters) {
        try {
            AIWeatherManager weatherManager = getWeatherManager();
            String city = (String) parameters.get("city");
            Double lat = parseDoubleParam(parameters, "lat");
            Double lon = parseDoubleParam(parameters, "lon");
            
            Map<String, Object> resultMap = new HashMap<>();
            List<String> attemptsInfo = new ArrayList<>();
            
            AIWeatherManager.QueryRetryResult currentResult = weatherManager.getCurrentWeatherSmart(city, lat, lon);
            if (currentResult.success) {
                resultMap.put("current", currentResult.data);
                attemptsInfo.add("当前天气: " + currentResult.attempt + "次尝试, " + currentResult.provider + "/" + currentResult.queryType);
            }
            
            AIWeatherManager.QueryRetryResult forecastResult = weatherManager.getForecastSmart(city, lat, lon);
            if (forecastResult.success) {
                resultMap.put("forecast", forecastResult.data);
                attemptsInfo.add("天气预报: " + forecastResult.attempt + "次尝试");
            }
            
            AIWeatherManager.QueryRetryResult hourlyResult = weatherManager.getHourlySmart(city, lat, lon);
            if (hourlyResult.success) {
                resultMap.put("hourly", hourlyResult.data);
                attemptsInfo.add("逐时预报: " + hourlyResult.attempt + "次尝试");
            }
            
            AIWeatherManager.QueryRetryResult airResult = weatherManager.getAirQualitySmart(city, lat, lon);
            if (airResult.success) {
                resultMap.put("air_quality", airResult.data);
                attemptsInfo.add("空气质量: " + airResult.attempt + "次尝试");
            }
            
            AIWeatherManager.QueryRetryResult indicesResult = weatherManager.getIndicesSmart(city, lat, lon);
            if (indicesResult.success) {
                resultMap.put("indices", indicesResult.data);
                attemptsInfo.add("生活指数: " + indicesResult.attempt + "次尝试");
            }
            
            AIWeatherManager.QueryRetryResult alertsResult = weatherManager.getAlertsSmart(city, lat, lon);
            if (alertsResult.success && alertsResult.data != null && !alertsResult.data.contains("暂无")) {
                resultMap.put("alerts", alertsResult.data);
                attemptsInfo.add("天气预警: " + alertsResult.attempt + "次尝试");
            }
            
            resultMap.put("status", resultMap.size() > 2 ? "success" : "failed");
            resultMap.put("attempts_info", attemptsInfo);
            
            return new AIToolResult(resultMap, parameters);
        } catch (Exception e) {
            Log.e(TAG, "Error getting all weather info", e);
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("status", "failed");
            errorMap.put("error", e.getMessage());
            return AIToolResult.fail(String.valueOf(errorMap.get("error")), errorMap);
        }
    }
    
    private Double parseDoubleParam(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) return null;
        if (value instanceof Double) return (Double) value;
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 容错解析 Float 参数（org.json 可能给 Integer/Double/Long） */
    private Float parseFloatParam(Map<String, Object> parameters, String key) {
        Double d = parseDoubleParam(parameters, key);
        return d != null ? d.floatValue() : null;
    }

    /** 容错解析 Boolean 参数（org.json 可能给字符串 "true"/"false" 或数字） */
    private Boolean parseBooleanParam(Map<String, Object> parameters, String key) {
        Object value = parameters != null ? parameters.get(key) : null;
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return "true".equalsIgnoreCase((String) value);
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return null;
    }
    
    // ==================== 计算功能 ====================
    
    private AIToolResult calculate(Map<String, Object> parameters) {
        String expression = (String) parameters.get("expression");
        
        if (expression == null || expression.trim().isEmpty()) {
            return new AIToolResult("缺少参数: expression", parameters);
        }
        
        expression = expression.trim();
        
        try {
            // 验证表达式只包含安全的字符（支持 ^ 幂运算）
            if (!expression.matches("[0-9+\\-*/.^%() ]+")) {
                return new AIToolResult("表达式包含非法字符，仅支持数字和基本运算符 (+, -, *, /, ^, %)", parameters);
            }

            double result = evaluateExpression(expression);
            
            // 判断是否为整数
            String resultStr;
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                resultStr = String.valueOf((long) result);
            } else {
                resultStr = String.valueOf(result);
            }
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("expression", expression);
            resultMap.put("result", resultStr);
            resultMap.put("status", "success");
            
            return new AIToolResult(resultMap, parameters);
        } catch (Exception e) {
            Log.e(TAG, "Calculation failed: " + expression, e);
            return new AIToolResult("计算失败: " + e.getMessage(), parameters);
        }
    }
    
    private double evaluateExpression(String expression) {
        try {
            // Android不支持javax.script，使用简单的表达式解析
            expression = expression.replaceAll("\\s+", "");
            return parseExpression(expression, new int[]{0});
        } catch (Exception e) {
            throw new ArithmeticException("表达式计算失败: " + e.getMessage());
        }
    }
    
    private double parseExpression(String expr, int[] pos) {
        double left = parseTerm(expr, pos);
        while (pos[0] < expr.length()) {
            char op = expr.charAt(pos[0]);
            if (op == '+' || op == '-') {
                pos[0]++;
                double right = parseTerm(expr, pos);
                left = (op == '+') ? left + right : left - right;
            } else {
                break;
            }
        }
        return left;
    }
    
    private double parseTerm(String expr, int[] pos) {
        double left = parsePower(expr, pos);
        while (pos[0] < expr.length()) {
            char op = expr.charAt(pos[0]);
            if (op == '*' || op == '/' || op == '%') {
                pos[0]++;
                double right = parsePower(expr, pos);
                if (op == '/') {
                    if (right == 0) throw new ArithmeticException("除数不能为零");
                    left /= right;
                } else if (op == '%') {
                    if (right == 0) throw new ArithmeticException("除数不能为零");
                    left %= right;
                } else {
                    left *= right;
                }
            } else {
                break;
            }
        }
        return left;
    }

    /** 解析幂运算：base ^ exponent（右结合，如 2^3^2 = 2^9 = 512） */
    private double parsePower(String expr, int[] pos) {
        double base = parseFactor(expr, pos);
        if (pos[0] < expr.length() && expr.charAt(pos[0]) == '^') {
            pos[0]++;
            double exponent = parsePower(expr, pos); // 右结合
            return Math.pow(base, exponent);
        }
        return base;
    }

    private double parseFactor(String expr, int[] pos) {
        if (pos[0] >= expr.length()) throw new ArithmeticException("表达式不完整");

        char c = expr.charAt(pos[0]);
        // 一元正负号：-5、+5、2*(-3)
        if (c == '-') {
            pos[0]++;
            return -parseFactor(expr, pos);
        }
        if (c == '+') {
            pos[0]++;
            return parseFactor(expr, pos);
        }
        if (c == '(') {
            pos[0]++;
            double result = parseExpression(expr, pos);
            if (pos[0] < expr.length() && expr.charAt(pos[0]) == ')') {
                pos[0]++;
            }
            return result;
        } else if (Character.isDigit(c) || c == '.') {
            int start = pos[0];
            while (pos[0] < expr.length() && (Character.isDigit(expr.charAt(pos[0])) || expr.charAt(pos[0]) == '.')) {
                pos[0]++;
            }
            return Double.parseDouble(expr.substring(start, pos[0]));
        } else {
            throw new ArithmeticException("无效字符: " + c);
        }
    }
}