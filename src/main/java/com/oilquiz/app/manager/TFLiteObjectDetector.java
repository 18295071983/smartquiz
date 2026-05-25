package com.oilquiz.app.manager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp;
import org.tensorflow.lite.support.image.ops.Rot90Op;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class TFLiteObjectDetector {
    private static final String TAG = "TFLiteObjectDetector";
    
    public static final int MAX_RESULTS = 10;
    
    public static final String MODEL_TYPE_YOLOV5 = "yolov5";
    public static final String MODEL_TYPE_YOLOV8 = "yolov8";
    public static final String MODEL_TYPE_SSD = "ssd";
    public static final String MODEL_TYPE_EFFICIENTDET = "efficientdet";
    
    private final Context context;
    private Interpreter interpreter;
    private Interpreter.Options interpreterOptions;
    private List<String> labels;
    private String modelType;
    private int imageSizeX;
    private int imageSizeY;
    private int numThreads = 4;
    private float confidenceThreshold = 0.5f;
    private float nmsThreshold = 0.45f;
    private boolean isModelLoaded = false;
    
    private ImageProcessor imageProcessor;
    private TensorImage inputImageBuffer;
    
    public TFLiteObjectDetector(Context context) {
        this.context = context.getApplicationContext();
    }
    
    public boolean loadModelFromAssets(String modelAssetName, String labelsAssetName, String modelType) {
        try {
            MappedByteBuffer modelBuffer = FileUtil.loadMappedFile(context, modelAssetName);
            labels = loadLabelsFromAssets(labelsAssetName);
            this.modelType = modelType;
            return initInterpreter(modelBuffer);
        } catch (IOException e) {
            Log.e(TAG, "从Assets加载模型失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    public boolean loadModelFromFile(String modelPath, String labelsPath, String modelType) {
        try {
            File modelFile = new File(modelPath);
            if (!modelFile.exists()) {
                Log.e(TAG, "模型文件不存在: " + modelPath);
                return false;
            }
            
            FileInputStream fis = new FileInputStream(modelFile);
            FileChannel fc = fis.getChannel();
            MappedByteBuffer modelBuffer = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
            fis.close();
            
            labels = loadLabelsFromFile(labelsPath);
            this.modelType = modelType;
            return initInterpreter(modelBuffer);
        } catch (IOException e) {
            Log.e(TAG, "从文件加载模型失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    private boolean initInterpreter(MappedByteBuffer modelBuffer) {
        try {
            interpreterOptions = new Interpreter.Options();
            interpreterOptions.setNumThreads(numThreads);
            
            interpreter = new Interpreter(modelBuffer, interpreterOptions);
            
            int imageTensorIndex = 0;
            int[] imageShape = interpreter.getInputTensor(imageTensorIndex).shape();
            imageSizeX = imageShape[1];
            imageSizeY = imageShape[2];
            
            int[] outputShape = interpreter.getOutputTensor(0).shape();
            Log.d(TAG, "模型输入尺寸: " + imageSizeX + "x" + imageSizeY);
            Log.d(TAG, "模型输出形状: " + java.util.Arrays.toString(outputShape));
            Log.d(TAG, "模型类型: " + modelType);
            
            inputImageBuffer = new TensorImage(DataType.FLOAT32);
            
            imageProcessor = new ImageProcessor.Builder()
                .add(new ResizeWithCropOrPadOp(imageSizeX, imageSizeY))
                .add(new ResizeOp(imageSizeX, imageSizeY, ResizeOp.ResizeMethod.BILINEAR))
                .add(new NormalizeOp(0f, 255f))
                .build();
            
            isModelLoaded = true;
            Log.d(TAG, "TensorFlow Lite 目标检测器加载成功");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "初始化解释器失败: " + e.getMessage(), e);
            release();
            return false;
        }
    }
    
    private List<String> loadLabelsFromAssets(String labelsAssetName) {
        List<String> labelsList = new ArrayList<>();
        try {
            InputStream is = context.getAssets().open(labelsAssetName);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                labelsList.add(line.trim());
            }
            reader.close();
            Log.d(TAG, "从Assets加载标签: " + labelsList.size() + " 个");
        } catch (IOException e) {
            Log.e(TAG, "从Assets加载标签失败: " + e.getMessage(), e);
        }
        return labelsList;
    }
    
    private List<String> loadLabelsFromFile(String labelsPath) {
        List<String> labelsList = new ArrayList<>();
        if (labelsPath == null) {
            return labelsList;
        }
        try {
            FileInputStream fis = new FileInputStream(labelsPath);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
            String line;
            while ((line = reader.readLine()) != null) {
                labelsList.add(line.trim());
            }
            reader.close();
            Log.d(TAG, "从文件加载标签: " + labelsList.size() + " 个");
        } catch (IOException e) {
            Log.e(TAG, "从文件加载标签失败: " + e.getMessage(), e);
        }
        return labelsList;
    }
    
    public boolean isModelLoaded() {
        return isModelLoaded && interpreter != null;
    }
    
    public void setNumThreads(int numThreads) {
        this.numThreads = numThreads;
    }
    
    public void setConfidenceThreshold(float threshold) {
        this.confidenceThreshold = Math.max(0.01f, Math.min(0.99f, threshold));
    }
    
    public float getConfidenceThreshold() {
        return confidenceThreshold;
    }
    
    public void setNmsThreshold(float threshold) {
        this.nmsThreshold = Math.max(0.01f, Math.min(0.99f, threshold));
    }
    
    public int getImageSizeX() {
        return imageSizeX;
    }
    
    public int getImageSizeY() {
        return imageSizeY;
    }
    
    public List<String> getLabels() {
        return labels;
    }
    
    public String getModelType() {
        return modelType;
    }
    
    public void detect(Bitmap bitmap, final ObjectDetectionCallback callback) {
        detect(bitmap, 0, callback);
    }
    
    public void detect(Bitmap bitmap, int rotationDegrees, final ObjectDetectionCallback callback) {
        if (!isModelLoaded()) {
            callback.onFailure("模型未加载");
            return;
        }
        
        if (bitmap == null || bitmap.isRecycled()) {
            callback.onFailure("图片无效或已回收");
            return;
        }
        
        try {
            inputImageBuffer.load(bitmap);
            
            int numRotations = rotationDegrees / 90;
            ImageProcessor processor = this.imageProcessor;
            if (numRotations != 0) {
                processor = new ImageProcessor.Builder()
                    .add(new ResizeWithCropOrPadOp(imageSizeX, imageSizeY))
                    .add(new ResizeOp(imageSizeX, imageSizeY, ResizeOp.ResizeMethod.BILINEAR))
                    .add(new Rot90Op(numRotations))
                    .add(new NormalizeOp(0f, 255f))
                    .build();
            }
            
            inputImageBuffer = processor.process(inputImageBuffer);
            
            Object outputArray = createOutputBuffer();
            interpreter.run(inputImageBuffer.getBuffer(), outputArray);
            
            List<DetectionResult> results = parseOutput(outputArray, bitmap.getWidth(), bitmap.getHeight());
            callback.onSuccess(results);
            
        } catch (Exception e) {
            Log.e(TAG, "目标检测失败: " + e.getMessage(), e);
            callback.onFailure("检测失败: " + e.getMessage());
        }
    }
    
    public void detect(Uri imageUri, final ObjectDetectionCallback callback) {
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(
                context.getContentResolver(), imageUri);
            detect(bitmap, callback);
        } catch (IOException e) {
            Log.e(TAG, "加载图片失败: " + e.getMessage(), e);
            callback.onFailure("加载图片失败: " + e.getMessage());
        }
    }
    
    private Object createOutputBuffer() {
        int[] outputShape = interpreter.getOutputTensor(0).shape();
        int numDetections = outputShape.length > 1 ? outputShape[1] : 0;
        int numClasses = outputShape.length > 2 ? outputShape[2] - 5 : 0;
        
        if (MODEL_TYPE_YOLOV8.equals(modelType) || MODEL_TYPE_YOLOV5.equals(modelType)) {
            if (outputShape.length == 3) {
                return new float[1][outputShape[1]][outputShape[2]];
            }
            return new float[1][numDetections][numClasses + 5];
        } else if (MODEL_TYPE_SSD.equals(modelType) || MODEL_TYPE_EFFICIENTDET.equals(modelType)) {
            return new float[1][10][4];
        }
        return new float[1][numDetections][numClasses + 5];
    }
    
    private List<DetectionResult> parseOutput(Object outputArray, int originalWidth, int originalHeight) {
        List<DetectionResult> results = new ArrayList<>();
        
        try {
            if (outputArray instanceof float[][][]) {
                float[][][] output = (float[][][]) outputArray;
                
                if (MODEL_TYPE_YOLOV8.equals(modelType)) {
                    results = parseYOLOv8Output(output, originalWidth, originalHeight);
                } else if (MODEL_TYPE_YOLOV5.equals(modelType)) {
                    results = parseYOLOv5Output(output, originalWidth, originalHeight);
                } else {
                    results = parseGenericOutput(output, originalWidth, originalHeight);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析输出失败: " + e.getMessage(), e);
        }
        
        return results;
    }
    
    private List<DetectionResult> parseYOLOv8Output(float[][][] output, int originalWidth, int originalHeight) {
        List<DetectionResult> results = new ArrayList<>();
        
        int numDetections = output[0][0].length;
        int numAttributes = output[0].length;
        int numClasses = numAttributes - 4;
        
        for (int i = 0; i < numDetections; i++) {
            float xCenter = output[0][0][i];
            float yCenter = output[0][1][i];
            float width = output[0][2][i];
            float height = output[0][3][i];
            
            float maxClassScore = 0;
            int bestClassIdx = 0;
            for (int j = 0; j < numClasses; j++) {
                float score = output[0][4 + j][i];
                if (score > maxClassScore) {
                    maxClassScore = score;
                    bestClassIdx = j;
                }
            }
            
            float confidence = maxClassScore;
            if (confidence >= confidenceThreshold) {
                float x1 = (xCenter - width / 2) * originalWidth / imageSizeX;
                float y1 = (yCenter - height / 2) * originalHeight / imageSizeY;
                float x2 = (xCenter + width / 2) * originalWidth / imageSizeX;
                float y2 = (yCenter + height / 2) * originalHeight / imageSizeY;
                
                DetectionResult result = new DetectionResult();
                result.label = bestClassIdx < labels.size() ? labels.get(bestClassIdx) : "class_" + bestClassIdx;
                result.labelIndex = bestClassIdx;
                result.confidence = confidence;
                result.confidencePercent = Math.round(confidence * 100);
                result.boundingBox = new RectF(x1, y1, x2, y2);
                
                results.add(result);
            }
        }
        
        return applyNMS(results);
    }
    
    private List<DetectionResult> parseYOLOv5Output(float[][][] output, int originalWidth, int originalHeight) {
        List<DetectionResult> results = new ArrayList<>();
        
        int numDetections = output[0].length;
        
        for (int i = 0; i < numDetections; i++) {
            float[] detection = output[0][i];
            if (detection.length < 6) continue;
            
            float xCenter = detection[0];
            float yCenter = detection[1];
            float width = detection[2];
            float height = detection[3];
            float objectness = detection[4];
            
            float maxClassScore = 0;
            int bestClassIdx = 0;
            for (int j = 5; j < detection.length; j++) {
                if (detection[j] > maxClassScore) {
                    maxClassScore = detection[j];
                    bestClassIdx = j - 5;
                }
            }
            
            float confidence = objectness * maxClassScore;
            if (confidence >= confidenceThreshold) {
                float x1 = (xCenter - width / 2) * originalWidth / imageSizeX;
                float y1 = (yCenter - height / 2) * originalHeight / imageSizeY;
                float x2 = (xCenter + width / 2) * originalWidth / imageSizeX;
                float y2 = (yCenter + height / 2) * originalHeight / imageSizeY;
                
                DetectionResult result = new DetectionResult();
                result.label = bestClassIdx < labels.size() ? labels.get(bestClassIdx) : "class_" + bestClassIdx;
                result.labelIndex = bestClassIdx;
                result.confidence = confidence;
                result.confidencePercent = Math.round(confidence * 100);
                result.boundingBox = new RectF(x1, y1, x2, y2);
                
                results.add(result);
            }
        }
        
        return applyNMS(results);
    }
    
    private List<DetectionResult> parseGenericOutput(float[][][] output, int originalWidth, int originalHeight) {
        List<DetectionResult> results = new ArrayList<>();
        
        int numDetections = Math.min(output[0].length, MAX_RESULTS);
        
        for (int i = 0; i < numDetections; i++) {
            float[] detection = output[0][i];
            if (detection.length < 5) continue;
            
            float x1 = detection[0] * originalWidth;
            float y1 = detection[1] * originalHeight;
            float x2 = detection[2] * originalWidth;
            float y2 = detection[3] * originalHeight;
            float confidence = detection[4];
            
            int classIdx = detection.length > 5 ? (int) detection[5] : 0;
            
            if (confidence >= confidenceThreshold) {
                DetectionResult result = new DetectionResult();
                result.label = classIdx < labels.size() ? labels.get(classIdx) : "class_" + classIdx;
                result.labelIndex = classIdx;
                result.confidence = confidence;
                result.confidencePercent = Math.round(confidence * 100);
                result.boundingBox = new RectF(x1, y1, x2, y2);
                
                results.add(result);
            }
        }
        
        return applyNMS(results);
    }
    
    private List<DetectionResult> applyNMS(List<DetectionResult> results) {
        if (results.size() <= 1) {
            return results;
        }
        
        Collections.sort(results, new Comparator<DetectionResult>() {
            @Override
            public int compare(DetectionResult a, DetectionResult b) {
                return Float.compare(b.confidence, a.confidence);
            }
        });
        
        List<DetectionResult> kept = new ArrayList<>();
        boolean[] suppressed = new boolean[results.size()];
        
        for (int i = 0; i < results.size(); i++) {
            if (suppressed[i]) continue;
            
            DetectionResult current = results.get(i);
            kept.add(current);
            
            for (int j = i + 1; j < results.size(); j++) {
                if (suppressed[j]) continue;
                
                DetectionResult other = results.get(j);
                float iou = calculateIoU(current.boundingBox, other.boundingBox);
                
                if (iou > nmsThreshold) {
                    suppressed[j] = true;
                }
            }
        }
        
        if (kept.size() > MAX_RESULTS) {
            kept = kept.subList(0, MAX_RESULTS);
        }
        
        return kept;
    }
    
    private float calculateIoU(RectF boxA, RectF boxB) {
        float xA = Math.max(boxA.left, boxB.left);
        float yA = Math.max(boxA.top, boxB.top);
        float xB = Math.min(boxA.right, boxB.right);
        float yB = Math.min(boxA.bottom, boxB.bottom);
        
        float interArea = Math.max(0f, xB - xA + 1) * Math.max(0f, yB - yA + 1);
        
        float boxAArea = (boxA.right - boxA.left + 1) * (boxA.bottom - boxA.top + 1);
        float boxBArea = (boxB.right - boxB.left + 1) * (boxB.bottom - boxB.top + 1);
        
        return interArea / (boxAArea + boxBArea - interArea);
    }
    
    public String formatResultsAsText(List<DetectionResult> results) {
        if (results == null || results.isEmpty()) {
            return "未检测到任何物体";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("目标检测结果 (TFLite):\n");
        sb.append("================\n");
        
        for (int i = 0; i < results.size(); i++) {
            DetectionResult result = results.get(i);
            sb.append(String.format("%d. %s (置信度: %.1f%%)\n",
                i + 1, result.label, result.confidence * 100));
            
            if (result.boundingBox != null) {
                sb.append(String.format("   位置: [%.0f, %.0f, %.0f, %.0f]\n",
                    result.boundingBox.left, result.boundingBox.top,
                    result.boundingBox.right, result.boundingBox.bottom));
            }
        }
        
        return sb.toString();
    }
    
    public String formatResultsAsJson(List<DetectionResult> results) {
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            org.json.JSONArray objectsArray = new org.json.JSONArray();
            
            if (results != null) {
                for (DetectionResult result : results) {
                    org.json.JSONObject objectJson = new org.json.JSONObject();
                    objectJson.put("label", result.label);
                    objectJson.put("label_index", result.labelIndex);
                    objectJson.put("confidence", result.confidence);
                    objectJson.put("confidence_percent", result.confidencePercent);
                    
                    if (result.boundingBox != null) {
                        org.json.JSONObject bboxJson = new org.json.JSONObject();
                        bboxJson.put("left", (int) result.boundingBox.left);
                        bboxJson.put("top", (int) result.boundingBox.top);
                        bboxJson.put("right", (int) result.boundingBox.right);
                        bboxJson.put("bottom", (int) result.boundingBox.bottom);
                        bboxJson.put("width", (int) result.boundingBox.width());
                        bboxJson.put("height", (int) result.boundingBox.height());
                        objectJson.put("bounding_box", bboxJson);
                    }
                    
                    objectsArray.put(objectJson);
                }
            }
            
            json.put("status", "success");
            json.put("object_count", objectsArray.length());
            json.put("objects", objectsArray);
            json.put("framework", "tflite");
            json.put("model_type", modelType);
            json.put("input_size", imageSizeX + "x" + imageSizeY);
            
            return json.toString();
        } catch (Exception e) {
            Log.e(TAG, "格式化JSON失败: " + e.getMessage(), e);
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }
    
    public void release() {
        try {
            if (interpreter != null) {
                interpreter.close();
                interpreter = null;
            }
            isModelLoaded = false;
            Log.d(TAG, "TFLite 目标检测器已释放");
        } catch (Exception e) {
            Log.e(TAG, "释放资源失败: " + e.getMessage(), e);
        }
    }
    
    public static class DetectionResult {
        public String label;
        public int labelIndex;
        public float confidence;
        public int confidencePercent;
        public RectF boundingBox;
        
        @Override
        public String toString() {
            if (boundingBox != null) {
                return String.format("%s (%.1f%%) at [%.0f, %.0f]",
                    label, confidence * 100, boundingBox.left, boundingBox.top);
            }
            return String.format("%s (%.1f%%)", label, confidence * 100);
        }
    }
    
    public interface ObjectDetectionCallback {
        void onSuccess(List<DetectionResult> results);
        void onFailure(String error);
    }
}
