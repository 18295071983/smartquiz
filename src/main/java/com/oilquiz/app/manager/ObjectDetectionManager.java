package com.oilquiz.app.manager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ObjectDetectionManager {
    private static final String TAG = "ObjectDetectionManager";
    
    public static final String MODE_SINGLE_IMAGE = "single_image";
    public static final String MODE_STREAM = "stream";
    
    public static final String MODEL_TYPE_DEFAULT = "default";
    public static final String MODEL_TYPE_CUSTOM = "custom";
    
    private final Context context;
    private ObjectDetector singleImageDetector;
    private ObjectDetector streamDetector;
    private String currentMode = MODE_SINGLE_IMAGE;
    private String currentModelType = MODEL_TYPE_DEFAULT;
    private float confidenceThreshold = 0.5f;
    private boolean enableMultipleObjects = true;
    private boolean enableClassification = true;
    
    public ObjectDetectionManager(Context context) {
        this.context = context.getApplicationContext();
        initDetectors();
    }
    
    private void initDetectors() {
        try {
            ObjectDetectorOptions.Builder singleOptionsBuilder = new ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE);
            
            if (enableMultipleObjects) {
                singleOptionsBuilder.enableMultipleObjects();
            }
            if (enableClassification) {
                singleOptionsBuilder.enableClassification();
            }
            
            singleImageDetector = ObjectDetection.getClient(singleOptionsBuilder.build());
            Log.d(TAG, "单图片目标检测器已初始化");
            
            ObjectDetectorOptions.Builder streamOptionsBuilder = new ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE);
            
            if (enableMultipleObjects) {
                streamOptionsBuilder.enableMultipleObjects();
            }
            if (enableClassification) {
                streamOptionsBuilder.enableClassification();
            }
            
            streamDetector = ObjectDetection.getClient(streamOptionsBuilder.build());
            Log.d(TAG, "流式目标检测器已初始化");
            
        } catch (Exception e) {
            Log.e(TAG, "初始化目标检测器失败: " + e.getMessage(), e);
        }
    }
    
    public void setConfidenceThreshold(float threshold) {
        this.confidenceThreshold = Math.max(0.01f, Math.min(0.99f, threshold));
    }
    
    public float getConfidenceThreshold() {
        return confidenceThreshold;
    }
    
    public void setEnableMultipleObjects(boolean enable) {
        this.enableMultipleObjects = enable;
        reinitDetectors();
    }
    
    public boolean isEnableMultipleObjects() {
        return enableMultipleObjects;
    }
    
    public void setEnableClassification(boolean enable) {
        this.enableClassification = enable;
        reinitDetectors();
    }
    
    public boolean isEnableClassification() {
        return enableClassification;
    }
    
    public void setDetectionMode(String mode) {
        this.currentMode = mode;
    }
    
    public String getCurrentMode() {
        return currentMode;
    }
    
    public String getCurrentModelType() {
        return currentModelType;
    }
    
    private void reinitDetectors() {
        release();
        initDetectors();
    }
    
    private ObjectDetector getCurrentDetector() {
        if (MODE_STREAM.equals(currentMode) && streamDetector != null) {
            return streamDetector;
        }
        return singleImageDetector;
    }
    
    public void processImage(Bitmap bitmap, ObjectDetectionCallback callback) {
        processImage(bitmap, callback, 0);
    }
    
    public void processImage(Bitmap bitmap, ObjectDetectionCallback callback, int rotationDegrees) {
        try {
            if (bitmap == null || bitmap.isRecycled()) {
                callback.onFailure("图片无效或已回收");
                return;
            }
            
            InputImage image = InputImage.fromBitmap(bitmap, rotationDegrees);
            ObjectDetector detector = getCurrentDetector();
            
            if (detector == null) {
                callback.onFailure("目标检测器未初始化");
                return;
            }
            
            detector.process(image)
                .addOnSuccessListener(detectedObjects -> {
                    List<DetectedObjectResult> results = convertToResults(detectedObjects);
                    callback.onSuccess(results);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "目标检测失败: " + e.getMessage(), e);
                    callback.onFailure("检测失败: " + e.getMessage());
                });
        } catch (Exception e) {
            Log.e(TAG, "目标检测处理失败: " + e.getMessage(), e);
            callback.onFailure("处理失败: " + e.getMessage());
        }
    }
    
    public void processImage(Uri imageUri, ObjectDetectionCallback callback) {
        try {
            if (imageUri == null) {
                callback.onFailure("图片URI无效");
                return;
            }
            
            InputImage image = InputImage.fromFilePath(context, imageUri);
            ObjectDetector detector = getCurrentDetector();
            
            if (detector == null) {
                callback.onFailure("目标检测器未初始化");
                return;
            }
            
            detector.process(image)
                .addOnSuccessListener(detectedObjects -> {
                    List<DetectedObjectResult> results = convertToResults(detectedObjects);
                    callback.onSuccess(results);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "目标检测失败: " + e.getMessage(), e);
                    callback.onFailure("检测失败: " + e.getMessage());
                });
        } catch (Exception e) {
            Log.e(TAG, "目标检测处理失败: " + e.getMessage(), e);
            callback.onFailure("处理失败: " + e.getMessage());
        }
    }
    
    public void processImageAsync(Bitmap bitmap, ObjectDetectionStreamCallback callback) {
        try {
            callback.onProgress("开始检测...");
            
            processImage(bitmap, new ObjectDetectionCallback() {
                @Override
                public void onSuccess(List<DetectedObjectResult> results) {
                    callback.onProgress("检测完成");
                    callback.onSuccess(results);
                }
                
                @Override
                public void onFailure(String error) {
                    callback.onProgress("检测失败");
                    callback.onFailure(error);
                }
            });
        } catch (Exception e) {
            callback.onFailure("处理失败: " + e.getMessage());
        }
    }
    
    private List<DetectedObjectResult> convertToResults(List<DetectedObject> detectedObjects) {
        List<DetectedObjectResult> results = new ArrayList<>();
        int index = 0;
        
        for (DetectedObject object : detectedObjects) {
            DetectedObjectResult result = new DetectedObjectResult();
            result.index = index++;
            result.trackingId = object.getTrackingId();
            result.boundingBox = object.getBoundingBox();
            
            List<DetectedObjectResult.Label> labels = new ArrayList<>();
            for (DetectedObject.Label label : object.getLabels()) {
                if (label.getConfidence() >= confidenceThreshold) {
                    DetectedObjectResult.Label objectLabel = new DetectedObjectResult.Label();
                    objectLabel.text = label.getText();
                    objectLabel.confidence = label.getConfidence();
                    objectLabel.confidencePercent = Math.round(label.getConfidence() * 100);
                    objectLabel.index = label.getIndex();
                    labels.add(objectLabel);
                }
            }
            result.labels = labels;
            
            if (!labels.isEmpty()) {
                DetectedObjectResult.Label primaryLabel = labels.get(0);
                result.primaryLabel = primaryLabel.text;
                result.primaryConfidence = primaryLabel.confidence;
                result.primaryConfidencePercent = primaryLabel.confidencePercent;
            }
            
            results.add(result);
        }
        
        return results;
    }
    
    public String formatResultsAsText(List<DetectedObjectResult> results) {
        if (results == null || results.isEmpty()) {
            return "未检测到任何物体";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("目标检测结果:\n");
        sb.append("================\n");
        
        for (int i = 0; i < results.size(); i++) {
            DetectedObjectResult result = results.get(i);
            sb.append(String.format("物体 %d:\n", i + 1));
            
            if (result.primaryLabel != null) {
                sb.append(String.format("  - 类别: %s (置信度: %.1f%%)\n", 
                    result.primaryLabel, result.primaryConfidence * 100));
            }
            
            if (result.boundingBox != null) {
                sb.append(String.format("  - 位置: left=%d, top=%d, width=%d, height=%d\n",
                    result.boundingBox.left, result.boundingBox.top,
                    result.boundingBox.width(), result.boundingBox.height()));
            }
            
            if (result.labels != null && result.labels.size() > 1) {
                sb.append("  - 其他可能类别: ");
                for (int j = 1; j < result.labels.size(); j++) {
                    if (j > 1) sb.append(", ");
                    sb.append(String.format("%s (%.0f%%)", 
                        result.labels.get(j).text, 
                        result.labels.get(j).confidence * 100));
                }
                sb.append("\n");
            }
            
            if (result.trackingId != null) {
                sb.append(String.format("  - 追踪ID: %d\n", result.trackingId));
            }
            
            sb.append("\n");
        }
        
        return sb.toString();
    }
    
    public JSONObject formatResultsAsJson(List<DetectedObjectResult> results) {
        JSONObject json = new JSONObject();
        try {
            JSONArray objectsArray = new JSONArray();
            
            if (results != null) {
                for (DetectedObjectResult result : results) {
                    JSONObject objectJson = new JSONObject();
                    objectJson.put("index", result.index);
                    
                    if (result.trackingId != null) {
                        objectJson.put("tracking_id", result.trackingId);
                    }
                    
                    if (result.boundingBox != null) {
                        JSONObject bboxJson = new JSONObject();
                        bboxJson.put("left", result.boundingBox.left);
                        bboxJson.put("top", result.boundingBox.top);
                        bboxJson.put("right", result.boundingBox.right);
                        bboxJson.put("bottom", result.boundingBox.bottom);
                        bboxJson.put("width", result.boundingBox.width());
                        bboxJson.put("height", result.boundingBox.height());
                        objectJson.put("bounding_box", bboxJson);
                    }
                    
                    if (result.primaryLabel != null) {
                        objectJson.put("primary_label", result.primaryLabel);
                        objectJson.put("primary_confidence", result.primaryConfidence);
                        objectJson.put("primary_confidence_percent", result.primaryConfidencePercent);
                    }
                    
                    if (result.labels != null) {
                        JSONArray labelsArray = new JSONArray();
                        for (DetectedObjectResult.Label label : result.labels) {
                            JSONObject labelJson = new JSONObject();
                            labelJson.put("text", label.text);
                            labelJson.put("confidence", label.confidence);
                            labelJson.put("confidence_percent", label.confidencePercent);
                            labelJson.put("index", label.index);
                            labelsArray.put(labelJson);
                        }
                        objectJson.put("labels", labelsArray);
                    }
                    
                    objectsArray.put(objectJson);
                }
            }
            
            json.put("status", "success");
            json.put("object_count", objectsArray.length());
            json.put("objects", objectsArray);
            json.put("model_type", currentModelType);
            json.put("detection_mode", currentMode);
            json.put("enable_multiple_objects", enableMultipleObjects);
            json.put("enable_classification", enableClassification);
        } catch (Exception e) {
            Log.e(TAG, "格式化JSON结果失败: " + e.getMessage(), e);
        }
        return json;
    }
    
    public List<String> getDetectedLabels(List<DetectedObjectResult> results) {
        List<String> labels = new ArrayList<>();
        if (results != null) {
            for (DetectedObjectResult result : results) {
                if (result.primaryLabel != null) {
                    labels.add(result.primaryLabel);
                }
            }
        }
        return labels;
    }
    
    public void release() {
        try {
            if (singleImageDetector != null) {
                singleImageDetector.close();
                singleImageDetector = null;
            }
            if (streamDetector != null) {
                streamDetector.close();
                streamDetector = null;
            }
            Log.d(TAG, "目标检测器已释放");
        } catch (Exception e) {
            Log.e(TAG, "释放目标检测器失败: " + e.getMessage(), e);
        }
    }
    
    public static class DetectedObjectResult {
        public int index;
        public Integer trackingId;
        public Rect boundingBox;
        public String primaryLabel;
        public float primaryConfidence;
        public int primaryConfidencePercent;
        public List<Label> labels;
        
        public static class Label {
            public String text;
            public float confidence;
            public int confidencePercent;
            public int index;
            
            @Override
            public String toString() {
                return String.format("%s (%.1f%%)", text, confidence * 100);
            }
        }
        
        @Override
        public String toString() {
            if (primaryLabel != null) {
                return String.format("%s (%.1f%%) at [%d,%d,%d,%d]", 
                    primaryLabel, primaryConfidence * 100,
                    boundingBox != null ? boundingBox.left : 0,
                    boundingBox != null ? boundingBox.top : 0,
                    boundingBox != null ? boundingBox.right : 0,
                    boundingBox != null ? boundingBox.bottom : 0);
            }
            return "未分类物体";
        }
    }
    
    public interface ObjectDetectionCallback {
        void onSuccess(List<DetectedObjectResult> results);
        void onFailure(String error);
    }
    
    public interface ObjectDetectionStreamCallback extends ObjectDetectionCallback {
        void onProgress(String progress);
    }
}
