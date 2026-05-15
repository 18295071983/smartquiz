package com.oilquiz.app.ai.model;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.oilquiz.app.ai.util.ModelFileSelector;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * ModelManager - 模型文件管理器
 * 
 * 功能：
 * - 管理AI模型文件的存储、复制、删除
 * - 从assets目录复制模型到应用内部存储
 * - 扫描和管理已下载的模型
 * 
 * 模型存储位置：
 * - 应用内部: /data/data/com.oilquiz.app/files/ai_models/
 * 
 * 主要方法：
 * - getModelPath(): 获取模型路径，不存在则从assets复制
 * - copyModelFromAssets(): 从assets复制模型
 * - deleteModel(): 删除模型文件
 * - listAvailableModels(): 列出所有可用模型
 * 
 * @author AI Team
 * @since 2024
 */
public class ModelManager {
    private static final String TAG = "ModelManager";
    private static final String MODEL_DIR = "ai_models";
    private final Context context;
    private final File modelDir;
    
    public ModelManager(Context context) {
        this.context = context;
        this.modelDir = new File(context.getFilesDir(), MODEL_DIR);
        if (!modelDir.exists()) {
            modelDir.mkdirs();
        }
    }
    
    public String getModelPath(String modelName) {
        File modelFile = new File(modelDir, modelName);
        if (modelFile.exists()) {
            return modelFile.getAbsolutePath();
        }
        
        // 从assets复制模型
        try {
            copyModelFromAssets(modelName);
            return modelFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy model from assets: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 从系统本地目录加载模型文件
     * @param modelPath 系统本地目录中的模型文件路径
     * @return 加载后的模型文件名
     */
    public String loadModelFromLocalPath(String modelPath) {
        File sourceFile = new File(modelPath);
        if (!sourceFile.exists()) {
            Log.e(TAG, "Model file not found: " + modelPath);
            return null;
        }
        
        String modelName = sourceFile.getName();
        File destFile = new File(modelDir, modelName);
        
        try {
            // 复制文件到应用的模型目录
            Files.copy(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Log.i(TAG, "Model loaded from local path: " + modelPath);
            Log.i(TAG, "Model copied to: " + destFile.getAbsolutePath());
            return modelName;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load model from local path: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 从Uri加载模型文件（适用于Android 10+的外部存储文件）
     * @param uri 文件Uri
     * @return 加载后的模型文件名
     */
    public String loadModelFromUri(Uri uri) {
        return loadModelFromUri(uri, null);
    }
    
    /**
     * 从Uri加载模型文件（适用于Android 10+的外部存储文件）
     * @param uri 文件Uri
     * @param progressCallback 进度回调
     * @return 加载后的模型文件名
     */
    public String loadModelFromUri(Uri uri, ModelFileSelector.ProgressCallback progressCallback) {
        // 获取文件名
        String modelName = ModelFileSelector.getFileNameFromUri(context, uri);
        if (modelName == null) {
            Log.e(TAG, "Failed to get filename from Uri");
            if (progressCallback != null) {
                progressCallback.onError("无法获取文件名");
            }
            return null;
        }
        
        // 检查文件扩展名是否有效
        if (!ModelFileSelector.isValidModelUri(context, uri)) {
            Log.e(TAG, "Invalid model file: " + modelName);
            if (progressCallback != null) {
                progressCallback.onError("无效的模型文件");
            }
            return null;
        }
        
        File destFile = new File(modelDir, modelName);
        
        // 直接从Uri复制文件到目标位置
        boolean success = ModelFileSelector.copyUriToFile(context, uri, destFile, progressCallback);
        
        if (success) {
            Log.i(TAG, "Model loaded from Uri: " + uri.toString());
            Log.i(TAG, "Model copied to: " + destFile.getAbsolutePath());
            return modelName;
        } else {
            Log.e(TAG, "Failed to copy model from Uri");
            // 如果复制失败，清理目标文件
            if (destFile.exists()) {
                destFile.delete();
            }
            return null;
        }
    }
    
    /**
     * 从assets复制模型
     */
    private void copyModelFromAssets(String modelName) throws IOException {
        File modelFile = new File(modelDir, modelName);
        if (!modelFile.exists()) {
            try (InputStream inputStream = context.getAssets().open("models/" + modelName);
                 OutputStream outputStream = new java.io.FileOutputStream(modelFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                Log.i(TAG, "Model copied from assets: " + modelName);
            }
        }
    }
    
    /**
     * 检查模型是否可用
     */
    public boolean isModelAvailable(String modelName) {
        File modelFile = new File(modelDir, modelName);
        return modelFile.exists();
    }
    
    /**
     * 获取模型大小
     */
    public long getModelSize(String modelName) {
        File modelFile = new File(modelDir, modelName);
        if (modelFile.exists()) {
            return modelFile.length();
        }
        return 0;
    }
    
    /**
     * 删除模型
     */
    public void deleteModel(String modelName) {
        File modelFile = new File(modelDir, modelName);
        if (modelFile.exists()) {
            modelFile.delete();
            Log.i(TAG, "Model deleted: " + modelName);
        }
    }
    
    /**
     * 获取模型目录
     */
    public File getModelDir() {
        return modelDir;
    }
    
    /**
     * 列出所有可用的模型
     */
    public String[] listAvailableModels() {
        return modelDir.list((dir, name) -> name.endsWith(".gguf"));
    }
}