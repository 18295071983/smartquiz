package com.oilquiz.app.manager;

import android.content.Context;
import android.graphics.Bitmap;
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
import org.tensorflow.lite.support.label.TensorLabel;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public class TFLiteImageClassifier {
    private static final String TAG = "TFLiteImageClassifier";
    
    public static final int MAX_RESULTS = 5;
    
    public static final String MODEL_SOURCE_ASSETS = "assets";
    public static final String MODEL_SOURCE_FILE = "file";
    
    private final Context context;
    private Interpreter interpreter;
    private Interpreter.Options interpreterOptions;
    private List<String> labels;
    private int imageSizeX;
    private int imageSizeY;
    private int numThreads = 4;
    private float confidenceThreshold = 0.3f;
    private boolean isModelLoaded = false;
    
    private ImageProcessor imageProcessor;
    private TensorImage inputImageBuffer;
    private TensorBuffer outputProbabilityBuffer;
    
    public TFLiteImageClassifier(Context context) {
        this.context = context.getApplicationContext();
    }
    
    public boolean loadModelFromAssets(String modelAssetName, String labelsAssetName) {
        try {
            MappedByteBuffer modelBuffer = FileUtil.loadMappedFile(context, modelAssetName);
            labels = loadLabelsFromAssets(labelsAssetName);
            return initInterpreter(modelBuffer);
        } catch (IOException e) {
            Log.e(TAG, "从Assets加载模型失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    public boolean loadModelFromFile(String modelPath, String labelsPath) {
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
            DataType imageDataType = interpreter.getInputTensor(imageTensorIndex).dataType();
            
            int probabilityTensorIndex = 0;
            int[] probabilityShape = interpreter.getOutputTensor(probabilityTensorIndex).shape();
            DataType probabilityDataType = interpreter.getOutputTensor(probabilityTensorIndex).dataType();
            
            Log.d(TAG, "模型输入尺寸: " + imageSizeX + "x" + imageSizeY);
            Log.d(TAG, "模型输出类别数: " + probabilityShape[1]);
            
            inputImageBuffer = new TensorImage(imageDataType);
            outputProbabilityBuffer = TensorBuffer.createFixedSize(
                new int[]{1, probabilityShape[1]}, probabilityDataType);
            
            imageProcessor = new ImageProcessor.Builder()
                .add(new ResizeWithCropOrPadOp(imageSizeX, imageSizeY))
                .add(new ResizeOp(imageSizeX, imageSizeY, ResizeOp.ResizeMethod.NEAREST_NEIGHBOR))
                .add(new NormalizeOp(127.5f, 127.5f))
                .build();
            
            isModelLoaded = true;
            Log.d(TAG, "TensorFlow Lite 图像分类器加载成功");
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
    
    public int getImageSizeX() {
        return imageSizeX;
    }
    
    public int getImageSizeY() {
        return imageSizeY;
    }
    
    public List<String> getLabels() {
        return labels;
    }
    
    public void recognize(Bitmap bitmap, final ImageClassificationCallback callback) {
        recognize(bitmap, 0, callback);
    }
    
    public void recognize(Bitmap bitmap, int rotationDegrees, final ImageClassificationCallback callback) {
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
                    .add(new ResizeOp(imageSizeX, imageSizeY, ResizeOp.ResizeMethod.NEAREST_NEIGHBOR))
                    .add(new Rot90Op(numRotations))
                    .add(new NormalizeOp(127.5f, 127.5f))
                    .build();
            }
            
            inputImageBuffer = processor.process(inputImageBuffer);
            
            interpreter.run(inputImageBuffer.getBuffer(), outputProbabilityBuffer.getBuffer().rewind());
            
            Map<String, Float> labeledProbability = new TensorLabel(
                labels, outputProbabilityBuffer).getMapWithFloatValue();
            
            List<ClassificationResult> results = getTopKLabels(labeledProbability);
            callback.onSuccess(results);
            
        } catch (Exception e) {
            Log.e(TAG, "图像分类失败: " + e.getMessage(), e);
            callback.onFailure("分类失败: " + e.getMessage());
        }
    }
    
    public void recognize(Uri imageUri, final ImageClassificationCallback callback) {
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(
                context.getContentResolver(), imageUri);
            recognize(bitmap, callback);
        } catch (IOException e) {
            Log.e(TAG, "加载图片失败: " + e.getMessage(), e);
            callback.onFailure("加载图片失败: " + e.getMessage());
        }
    }
    
    private List<ClassificationResult> getTopKLabels(Map<String, Float> labelProb) {
        PriorityQueue<ClassificationResult> pq = new PriorityQueue<>(
            MAX_RESULTS,
            new Comparator<ClassificationResult>() {
                @Override
                public int compare(ClassificationResult lhs, ClassificationResult rhs) {
                    return Float.compare(lhs.confidence, rhs.confidence);
                }
            }
        );
        
        int index = 0;
        for (Map.Entry<String, Float> entry : labelProb.entrySet()) {
            ClassificationResult result = new ClassificationResult();
            result.index = index++;
            result.label = entry.getKey();
            result.confidence = entry.getValue();
            result.confidencePercent = Math.round(entry.getValue() * 100);
            
            if (entry.getValue() >= confidenceThreshold) {
                pq.add(result);
                if (pq.size() > MAX_RESULTS) {
                    pq.poll();
                }
            }
        }
        
        List<ClassificationResult> results = new ArrayList<>(pq);
        results.sort(new Comparator<ClassificationResult>() {
            @Override
            public int compare(ClassificationResult lhs, ClassificationResult rhs) {
                return Float.compare(rhs.confidence, lhs.confidence);
            }
        });
        
        return results;
    }
    
    public String formatResultsAsText(List<ClassificationResult> results) {
        if (results == null || results.isEmpty()) {
            return "未识别到任何标签";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("图像识别结果 (TFLite):\n");
        sb.append("================\n");
        
        for (int i = 0; i < results.size(); i++) {
            ClassificationResult result = results.get(i);
            sb.append(String.format("%d. %s (置信度: %.1f%%)\n",
                i + 1, result.label, result.confidence * 100));
        }
        
        return sb.toString();
    }
    
    public String formatResultsAsJson(List<ClassificationResult> results) {
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            org.json.JSONArray labelsArray = new org.json.JSONArray();
            
            if (results != null) {
                for (ClassificationResult result : results) {
                    org.json.JSONObject labelJson = new org.json.JSONObject();
                    labelJson.put("index", result.index);
                    labelJson.put("label", result.label);
                    labelJson.put("confidence", result.confidence);
                    labelJson.put("confidence_percent", result.confidencePercent);
                    labelsArray.put(labelJson);
                }
            }
            
            json.put("status", "success");
            json.put("label_count", labelsArray.length());
            json.put("labels", labelsArray);
            json.put("framework", "tflite");
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
            Log.d(TAG, "TFLite 图像分类器已释放");
        } catch (Exception e) {
            Log.e(TAG, "释放资源失败: " + e.getMessage(), e);
        }
    }
    
    public static class ClassificationResult {
        public int index;
        public String label;
        public float confidence;
        public int confidencePercent;
        
        @Override
        public String toString() {
            return String.format("%s (%.1f%%)", label, confidence * 100);
        }
    }
    
    public interface ImageClassificationCallback {
        void onSuccess(List<ClassificationResult> results);
        void onFailure(String error);
    }
}
