package com.oilquiz.app.manager;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.label.custom.CustomImageLabelerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ImageLabelManager {
    private static final String TAG = "ImageLabelManager";
    
    public static final String MODEL_TYPE_DEFAULT = "default";
    public static final String MODEL_TYPE_CUSTOM = "custom";
    
    private final Context context;
    private ImageLabeler defaultLabeler;
    private ImageLabeler customLabeler;
    private String currentModelType = MODEL_TYPE_DEFAULT;
    private float confidenceThreshold = 0.5f;
    
    public ImageLabelManager(Context context) {
        this.context = context.getApplicationContext();
        initDefaultLabeler();
    }
    
    private void initDefaultLabeler() {
        try {
            ImageLabelerOptions options = new ImageLabelerOptions.Builder()
                .setConfidenceThreshold(confidenceThreshold)
                .build();
            defaultLabeler = com.google.mlkit.vision.label.ImageLabeling.getClient(options);
            Log.d(TAG, "默认图像标签识别器已初始化");
        } catch (Exception e) {
            Log.e(TAG, "初始化默认图像标签识别器失败: " + e.getMessage(), e);
        }
    }
    
    public void setConfidenceThreshold(float threshold) {
        this.confidenceThreshold = Math.max(0.01f, Math.min(0.99f, threshold));
        initDefaultLabeler();
    }
    
    public float getConfidenceThreshold() {
        return confidenceThreshold;
    }
    
    public void switchModelType(String modelType) {
        this.currentModelType = modelType;
        Log.d(TAG, "切换模型类型: " + modelType);
    }
    
    public String getCurrentModelType() {
        return currentModelType;
    }
    
    public boolean loadCustomModel(String modelPath, String labelPath) {
        try {
            File modelFile = new File(modelPath);
            if (!modelFile.exists()) {
                Log.e(TAG, "自定义模型文件不存在: " + modelPath);
                return false;
            }
            
            CustomImageLabelerOptions.Builder builder = new CustomImageLabelerOptions.Builder(
                new com.google.mlkit.common.model.LocalModel.Builder().setAbsoluteFilePath(modelPath).build())
                .setConfidenceThreshold(confidenceThreshold)
                .setMaxResultCount(10);
            
            customLabeler = com.google.mlkit.vision.label.ImageLabeling.getClient(builder.build());
            currentModelType = MODEL_TYPE_CUSTOM;
            Log.d(TAG, "自定义图像标签识别器已加载");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "加载自定义模型失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    private ImageLabeler getCurrentLabeler() {
        if (MODEL_TYPE_CUSTOM.equals(currentModelType) && customLabeler != null) {
            return customLabeler;
        }
        return defaultLabeler;
    }
    
    public void processImage(Bitmap bitmap, ImageLabelCallback callback) {
        processImage(bitmap, callback, 0);
    }
    
    public void processImage(Bitmap bitmap, ImageLabelCallback callback, int rotationDegrees) {
        try {
            if (bitmap == null || bitmap.isRecycled()) {
                callback.onFailure("图片无效或已回收");
                return;
            }
            
            InputImage image = InputImage.fromBitmap(bitmap, rotationDegrees);
            ImageLabeler labeler = getCurrentLabeler();
            
            if (labeler == null) {
                callback.onFailure("图像标签识别器未初始化");
                return;
            }
            
            labeler.process(image)
                .addOnSuccessListener(labels -> {
                    List<ImageLabelResult> results = convertToResults(labels);
                    callback.onSuccess(results);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "图像标签识别失败: " + e.getMessage(), e);
                    callback.onFailure("识别失败: " + e.getMessage());
                });
        } catch (Exception e) {
            Log.e(TAG, "图像标签处理失败: " + e.getMessage(), e);
            callback.onFailure("处理失败: " + e.getMessage());
        }
    }
    
    public void processImage(Uri imageUri, ImageLabelCallback callback) {
        try {
            if (imageUri == null) {
                callback.onFailure("图片URI无效");
                return;
            }
            
            InputImage image = InputImage.fromFilePath(context, imageUri);
            ImageLabeler labeler = getCurrentLabeler();
            
            if (labeler == null) {
                callback.onFailure("图像标签识别器未初始化");
                return;
            }
            
            labeler.process(image)
                .addOnSuccessListener(labels -> {
                    List<ImageLabelResult> results = convertToResults(labels);
                    callback.onSuccess(results);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "图像标签识别失败: " + e.getMessage(), e);
                    callback.onFailure("识别失败: " + e.getMessage());
                });
        } catch (Exception e) {
            Log.e(TAG, "图像标签处理失败: " + e.getMessage(), e);
            callback.onFailure("处理失败: " + e.getMessage());
        }
    }
    
    public void processImageAsync(Bitmap bitmap, ImageLabelStreamCallback callback) {
        try {
            callback.onProgress("开始识别...");
            
            processImage(bitmap, new ImageLabelCallback() {
                @Override
                public void onSuccess(List<ImageLabelResult> results) {
                    callback.onProgress("识别完成");
                    callback.onSuccess(results);
                }
                
                @Override
                public void onFailure(String error) {
                    callback.onProgress("识别失败");
                    callback.onFailure(error);
                }
            });
        } catch (Exception e) {
            callback.onFailure("处理失败: " + e.getMessage());
        }
    }
    
    private List<ImageLabelResult> convertToResults(List<ImageLabel> labels) {
        List<ImageLabelResult> results = new ArrayList<>();
        int index = 0;
        
        for (ImageLabel label : labels) {
            ImageLabelResult result = new ImageLabelResult();
            result.index = index++;
            result.text = label.getText();
            result.confidence = label.getConfidence();
            result.confidencePercent = Math.round(label.getConfidence() * 100);
            results.add(result);
        }
        
        return results;
    }
    
    public String formatResultsAsText(List<ImageLabelResult> results) {
        if (results == null || results.isEmpty()) {
            return "未识别到任何标签";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("图像识别结果:\n");
        sb.append("================\n");
        
        for (int i = 0; i < results.size(); i++) {
            ImageLabelResult result = results.get(i);
            sb.append(String.format("%d. %s (置信度: %.1f%%)\n", 
                i + 1, result.text, result.confidence * 100));
        }
        
        return sb.toString();
    }
    
    public JSONObject formatResultsAsJson(List<ImageLabelResult> results) {
        JSONObject json = new JSONObject();
        try {
            JSONArray labelsArray = new JSONArray();
            
            if (results != null) {
                for (ImageLabelResult result : results) {
                    JSONObject labelJson = new JSONObject();
                    labelJson.put("index", result.index);
                    labelJson.put("text", result.text);
                    labelJson.put("confidence", result.confidence);
                    labelJson.put("confidence_percent", result.confidencePercent);
                    if (result.entityId != null) {
                        labelJson.put("entity_id", result.entityId);
                    }
                    labelsArray.put(labelJson);
                }
            }
            
            json.put("status", "success");
            json.put("label_count", labelsArray.length());
            json.put("labels", labelsArray);
            json.put("model_type", currentModelType);
        } catch (Exception e) {
            Log.e(TAG, "格式化JSON结果失败: " + e.getMessage(), e);
        }
        return json;
    }
    
    public String getPrimaryLabel(List<ImageLabelResult> results) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        return results.get(0).text;
    }
    
    public List<String> getLabelTexts(List<ImageLabelResult> results) {
        List<String> texts = new ArrayList<>();
        if (results != null) {
            for (ImageLabelResult result : results) {
                texts.add(result.text);
            }
        }
        return texts;
    }
    
    public void release() {
        try {
            if (defaultLabeler != null) {
                defaultLabeler.close();
                defaultLabeler = null;
            }
            if (customLabeler != null) {
                customLabeler.close();
                customLabeler = null;
            }
            Log.d(TAG, "图像标签识别器已释放");
        } catch (Exception e) {
            Log.e(TAG, "释放图像标签识别器失败: " + e.getMessage(), e);
        }
    }
    
    public static class ImageLabelResult {
        public int index;
        public String text;
        public float confidence;
        public int confidencePercent;
        public String entityId;
        
        @Override
        public String toString() {
            return String.format("%s (%.1f%%)", text, confidence * 100);
        }
    }
    
    public interface ImageLabelCallback {
        void onSuccess(List<ImageLabelResult> results);
        void onFailure(String error);
    }
    
    public interface ImageLabelStreamCallback extends ImageLabelCallback {
        void onProgress(String progress);
    }
}
