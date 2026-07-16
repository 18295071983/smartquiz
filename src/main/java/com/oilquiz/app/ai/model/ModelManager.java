package com.oilquiz.app.ai.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.oilquiz.app.ai.util.ModelFileSelector;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ModelManager - 模型文件管理器
 * 
 * 功能：
 * - 管理AI模型文件的存储、复制、删除
 * - 从assets目录复制模型到应用内部存储
 * - 扫描和管理已下载的模型
 * - 支持可配置的模型保存目录
 * - 支持手动和自动导入模型
 * 
 * 模型存储位置：
 * - 默认: /data/data/com.oilquiz.app/files/ai_models/
 * - 可配置: 用户可自定义保存目录
 * 
 * 主要方法：
 * - getModelPath(): 获取模型路径，不存在则从assets复制
 * - copyModelFromAssets(): 从assets复制模型
 * - deleteModel(): 删除模型文件
 * - listAvailableModels(): 列出所有可用模型
 * - setModelSaveDirectory(): 设置自定义保存目录
 * - importModelFromUri(): 从Uri手动导入模型
 * - autoImportFromDirectory(): 从目录自动扫描导入模型
 * 
 * @author AI Team
 * @since 2024
 */
public class ModelManager {
    private static final String TAG = "ModelManager";
    private static final String MODEL_DIR = "ai_models";
    private static final String PREFS_NAME = "model_manager_prefs";
    private static final String KEY_CUSTOM_MODEL_DIR = "custom_model_dir";
    private static final String KEY_AUTO_IMPORT_ENABLED = "auto_import_enabled";
    
    private final Context context;
    private final SharedPreferences prefs;
    private File modelDir;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    public ModelManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        initializeModelDirectory();
    }
    
    private void initializeModelDirectory() {
        String customDir = prefs.getString(KEY_CUSTOM_MODEL_DIR, null);
        if (customDir != null && !customDir.isEmpty()) {
            File dir = new File(customDir);
            if (dir.exists() || dir.mkdirs()) {
                this.modelDir = dir;
                Log.i(TAG, "Using custom model directory: " + customDir);
                return;
            }
        }
        
        this.modelDir = new File(context.getFilesDir(), MODEL_DIR);
        if (!modelDir.exists()) {
            modelDir.mkdirs();
        }
        Log.i(TAG, "Using default model directory: " + modelDir.getAbsolutePath());
    }
    
    public boolean setModelSaveDirectory(String directoryPath) {
        if (directoryPath == null || directoryPath.isEmpty()) {
            return false;
        }
        
        File dir = new File(directoryPath);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Cannot create or access directory: " + directoryPath);
            return false;
        }
        
        if (!dir.canWrite()) {
            Log.e(TAG, "Directory is not writable: " + directoryPath);
            return false;
        }
        
        try {
            moveExistingModelsToNewDirectory(this.modelDir, dir);
        } catch (Exception e) {
            Log.w(TAG, "Failed to move existing models, but directory will be used for new downloads", e);
        }
        
        this.modelDir = dir;
        prefs.edit().putString(KEY_CUSTOM_MODEL_DIR, directoryPath).apply();
        Log.i(TAG, "Model save directory changed to: " + directoryPath);
        return true;
    }
    
    public String getModelSaveDirectory() {
        return modelDir.getAbsolutePath();
    }
    
    public boolean isUsingCustomDirectory() {
        String customDir = prefs.getString(KEY_CUSTOM_MODEL_DIR, null);
        return customDir != null && !customDir.isEmpty();
    }
    
    public void resetToDefaultDirectory() {
        String customDir = prefs.getString(KEY_CUSTOM_MODEL_DIR, null);
        if (customDir != null) {
            try {
                moveExistingModelsToNewDirectory(this.modelDir, new File(context.getFilesDir(), MODEL_DIR));
            } catch (Exception e) {
                Log.w(TAG, "Failed to move models back to default directory", e);
            }
        }
        
        prefs.edit().remove(KEY_CUSTOM_MODEL_DIR).apply();
        this.modelDir = new File(context.getFilesDir(), MODEL_DIR);
        if (!modelDir.exists()) {
            modelDir.mkdirs();
        }
        Log.i(TAG, "Reset to default model directory");
    }
    
    private void moveExistingModelsToNewDirectory(File sourceDir, File destDir) throws IOException {
        if (sourceDir == null || destDir == null) {
            return;
        }
        
        if (sourceDir.getAbsolutePath().equals(destDir.getAbsolutePath())) {
            return;
        }
        
        if (!destDir.exists()) {
            destDir.mkdirs();
        }
        
        File[] modelFiles = sourceDir.listFiles((dir, name) -> 
            name.endsWith(".gguf") || name.endsWith(".bin") || name.endsWith(".ggml"));
        
        if (modelFiles != null) {
            for (File source : modelFiles) {
                File dest = new File(destDir, source.getName());
                if (dest.exists()) {
                    Log.i(TAG, "Model already exists in destination: " + source.getName());
                    continue;
                }
                
                if (source.renameTo(dest)) {
                    Log.i(TAG, "Moved model: " + source.getName());
                } else {
                    copyFile(source, dest);
                    source.delete();
                    Log.i(TAG, "Copied and deleted model: " + source.getName());
                }
            }
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
        return modelDir.list((dir, name) -> name.endsWith(".gguf") || name.endsWith(".bin") || name.endsWith(".ggml"));
    }
    
    /**
     * 列出所有可用的模型文件详细信息
     */
    public List<ModelFileInfo> listAvailableModelsWithInfo() {
        List<ModelFileInfo> result = new ArrayList<>();
        String[] modelNames = listAvailableModels();
        if (modelNames == null) {
            return result;
        }
        
        for (String name : modelNames) {
            File file = new File(modelDir, name);
            if (file.exists()) {
                result.add(new ModelFileInfo(
                    name,
                    file.getAbsolutePath(),
                    file.length(),
                    file.lastModified(),
                    getModelType(name)
                ));
            }
        }
        return result;
    }
    
    private String getModelType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.contains("qwen")) return "Qwen";
        if (lower.contains("llama")) return "Llama";
        if (lower.contains("chatglm")) return "ChatGLM";
        if (lower.contains("vicuna")) return "Vicuna";
        if (lower.contains("phi")) return "Phi";
        if (lower.contains("mistral")) return "Mistral";
        if (lower.contains("gemma")) return "Gemma";
        if (lower.contains("yi")) return "Yi";
        if (lower.contains("deepseek")) return "DeepSeek";
        if (lower.contains("baichuan")) return "Baichuan";
        if (lower.contains("internlm")) return "InternLM";
        return "Unknown";
    }
    
    public String importModelFromLocalPath(String modelPath) {
        return importModelFromLocalPath(modelPath, null);
    }
    
    public String importModelFromLocalPath(String modelPath, ImportCallback callback) {
        File sourceFile = new File(modelPath);
        if (!sourceFile.exists()) {
            Log.e(TAG, "Model file not found: " + modelPath);
            if (callback != null) {
                callback.onError("模型文件不存在: " + modelPath);
            }
            return null;
        }
        
        if (!isValidModelFile(sourceFile)) {
            Log.e(TAG, "Invalid model file: " + modelPath);
            if (callback != null) {
                callback.onError("无效的模型文件格式");
            }
            return null;
        }
        
        String modelName = sourceFile.getName();
        File destFile = new File(modelDir, modelName);
        
        if (destFile.exists()) {
            Log.i(TAG, "Model already exists: " + modelName);
            if (callback != null) {
                callback.onComplete(modelName);
            }
            return modelName;
        }
        
        final long totalSize = sourceFile.length();
        
        executor.execute(() -> {
            try {
                copyFileWithProgress(sourceFile, destFile, new ProgressListener() {
                    @Override
                    public void onProgress(long copied, long total) {
                        if (callback != null) {
                            callback.onProgress((int)((copied * 100) / total), copied / (1024 * 1024), total / (1024 * 1024));
                        }
                    }
                });
                
                Log.i(TAG, "Model imported successfully: " + modelName);
                if (callback != null) {
                    callback.onComplete(modelName);
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to import model: " + e.getMessage());
                if (destFile.exists()) {
                    destFile.delete();
                }
                if (callback != null) {
                    callback.onError("导入失败: " + e.getMessage());
                }
            }
        });
        
        return modelName;
    }
    
    public List<String> autoImportFromDirectory(String directoryPath) {
        return autoImportFromDirectory(directoryPath, null);
    }
    
    public List<String> autoImportFromDirectory(String directoryPath, ImportCallback callback) {
        List<String> importedModels = new ArrayList<>();
        File dir = new File(directoryPath);
        
        if (!dir.exists() || !dir.isDirectory()) {
            if (callback != null) {
                callback.onError("目录不存在或不是有效目录");
            }
            return importedModels;
        }
        
        File[] modelFiles = dir.listFiles((file, name) -> 
            name.endsWith(".gguf") || name.endsWith(".bin") || name.endsWith(".ggml"));
        
        if (modelFiles == null || modelFiles.length == 0) {
            if (callback != null) {
                callback.onError("目录中没有找到模型文件");
            }
            return importedModels;
        }
        
        for (File modelFile : modelFiles) {
            String modelName = modelFile.getName();
            File destFile = new File(modelDir, modelName);
            
            if (destFile.exists()) {
                Log.i(TAG, "Model already exists, skipping: " + modelName);
                continue;
            }
            
            try {
                copyFile(modelFile, destFile);
                importedModels.add(modelName);
                Log.i(TAG, "Auto imported: " + modelName);
            } catch (IOException e) {
                Log.e(TAG, "Failed to auto import: " + modelName, e);
            }
        }
        
        if (callback != null && !importedModels.isEmpty()) {
            callback.onBatchComplete(importedModels);
        }
        
        return importedModels;
    }
    
    public void setAutoImportEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_IMPORT_ENABLED, enabled).apply();
    }
    
    public boolean isAutoImportEnabled() {
        return prefs.getBoolean(KEY_AUTO_IMPORT_ENABLED, false);
    }
    
    public List<String> scanExternalStorageForModels() {
        List<String> modelPaths = new ArrayList<>();
        
        File[] externalDirs = context.getExternalFilesDirs(null);
        if (externalDirs != null) {
            for (File externalDir : externalDirs) {
                if (externalDir != null) {
                    scanDirectoryForModels(externalDir, modelPaths);
                }
            }
        }
        
        File downloadsDir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        } else {
            downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        }
        if (downloadsDir != null) {
            scanDirectoryForModels(downloadsDir, modelPaths);
        }
        
        File documentsDir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        } else {
            documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        }
        if (documentsDir != null) {
            scanDirectoryForModels(documentsDir, modelPaths);
        }
        
        return modelPaths;
    }
    
    private void scanDirectoryForModels(File dir, List<String> modelPaths) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return;
        }
        
        File[] files = dir.listFiles((file, name) -> 
            name.endsWith(".gguf") || name.endsWith(".bin") || name.endsWith(".ggml"));
        
        if (files != null) {
            for (File file : files) {
                modelPaths.add(file.getAbsolutePath());
            }
        }
        
        File[] subDirs = dir.listFiles(File::isDirectory);
        if (subDirs != null) {
            for (File subDir : subDirs) {
                scanDirectoryForModels(subDir, modelPaths);
            }
        }
    }
    
    private boolean isValidModelFile(File file) {
        if (!file.exists() || !file.isFile()) {
            return false;
        }
        String name = file.getName().toLowerCase();
        return name.endsWith(".gguf") || name.endsWith(".bin") || name.endsWith(".ggml");
    }
    
    private void copyFile(File source, File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            fos.flush();
        }
    }
    
    private void copyFileWithProgress(File source, File dest, ProgressListener listener) throws IOException {
        long totalBytes = source.length();
        long copiedBytes = 0;
        
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dest))) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            long lastUpdate = System.currentTimeMillis();
            
            while ((bytesRead = bis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
                copiedBytes += bytesRead;
                
                long now = System.currentTimeMillis();
                if (now - lastUpdate > 500 && listener != null) {
                    lastUpdate = now;
                    listener.onProgress(copiedBytes, totalBytes);
                }
            }
            bos.flush();
            
            if (listener != null) {
                listener.onProgress(totalBytes, totalBytes);
            }
        }
    }
    
    public static class ModelFileInfo {
        public final String name;
        public final String path;
        public final long size;
        public final long lastModified;
        public final String modelType;
        
        public ModelFileInfo(String name, String path, long size, long lastModified, String modelType) {
            this.name = name;
            this.path = path;
            this.size = size;
            this.lastModified = lastModified;
            this.modelType = modelType;
        }
    }
    
    public interface ImportCallback {
        void onProgress(int progress, long copiedMB, long totalMB);
        void onComplete(String modelName);
        void onBatchComplete(List<String> modelNames);
        void onError(String error);
    }
    
    private interface ProgressListener {
        void onProgress(long copied, long total);
    }
}