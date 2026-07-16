package com.oilquiz.app.util;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

/**
 * OBB文件助手类
 * 用于读取APK扩展文件（OBB）中的模型文件
 * 使用Apache Commons Compress库读取ZIP/OBB文件
 */
public class ObbHelper {
    private static final String TAG = "ObbHelper";
    
    private static final int MAIN_EXPANSION_VERSION = 2;
    private static final String OBB_MAIN_FILENAME = "main.2.com.oilquiz.app.obb";
    
    private final Context context;
    private ZipFile obbFile = null;
    private File currentObbPath = null;
    private boolean obbAvailable = false;
    
    // 缓存OBB文件中的文件列表
    private Set<String> obbFileList = null;
    
    public ObbHelper(Context context) {
        this.context = context;
    }
    
    /**
     * 打开OBB文件
     */
    public boolean openObbFile() {
        try {
            File obbPath = getObbPath();
            if (obbPath != null && obbPath.exists()) {
                // 如果OBB文件路径没有变化，且已经打开，直接返回
                if (currentObbPath != null && currentObbPath.equals(obbPath) && obbFile != null) {
                    return true;
                }
                
                // 关闭之前的OBB文件
                close();
                
                obbFile = new ZipFile(obbPath);
                currentObbPath = obbPath;
                obbAvailable = true;
                
                // 缓存OBB文件中的文件列表
                cacheObbFileList();
                
                Log.i(TAG, "OBB文件打开成功: " + obbPath.getAbsolutePath());
                return true;
            } else {
                Log.w(TAG, "OBB文件不存在: " + (obbPath != null ? obbPath.getAbsolutePath() : "null"));
                obbAvailable = false;
                return false;
            }
        } catch (IOException e) {
            Log.e(TAG, "打开OBB文件失败: " + e.getMessage(), e);
            obbAvailable = false;
            return false;
        }
    }
    
    /**
     * 缓存OBB文件中的文件列表
     */
    private void cacheObbFileList() {
        if (obbFile != null) {
            obbFileList = new HashSet<>();
            try {
                Enumeration<? extends ZipArchiveEntry> entries = obbFile.getEntries();
                while (entries.hasMoreElements()) {
                    ZipArchiveEntry entry = entries.nextElement();
                    obbFileList.add(entry.getName());
                }
            } catch (Exception e) {
                Log.e(TAG, "缓存OBB文件列表失败: " + e.getMessage(), e);
                obbFileList = null;
            }
        }
    }
    
    /**
     * 获取OBB文件路径
     */
    private File getObbPath() {
        try {
            // 尝试从外部存储的obb目录查找
            File obbDir = new File(Environment.getExternalStorageDirectory(), 
                "Android/obb/" + context.getPackageName());
            
            if (obbDir.exists()) {
                File obbFile = new File(obbDir, OBB_MAIN_FILENAME);
                if (obbFile.exists()) {
                    return obbFile;
                }
            }
            
            // 尝试从应用私有obb目录查找
            File privateObbDir = context.getObbDir();
            if (privateObbDir != null) {
                File privateObbFile = new File(privateObbDir, OBB_MAIN_FILENAME);
                if (privateObbFile.exists()) {
                    return privateObbFile;
                }
            }
            
            // 尝试从Download文件夹查找
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File modelDir = new File(downloadDir, "模型");
            if (modelDir.exists() && modelDir.isDirectory()) {
                File obbFile = new File(modelDir, OBB_MAIN_FILENAME);
                if (obbFile.exists()) {
                    Log.i(TAG, "从Download/模型文件夹找到OBB文件: " + obbFile.getAbsolutePath());
                    return obbFile;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取OBB路径失败: " + e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * 从OBB中读取模型文件到内部存储
     */
    public File extractModelToInternalStorage(String modelFileName) {
        if (obbFile == null) {
            if (!openObbFile()) {
                Log.e(TAG, "OBB文件未打开，无法提取模型");
                return null;
            }
        }
        
        try {
            // 查找ZIP中的模型文件
            ZipArchiveEntry entry = obbFile.getEntry(modelFileName);
            if (entry == null) {
                Log.e(TAG, "在OBB中未找到模型文件: " + modelFileName);
                return null;
            }
            
            File destFile = new File(context.getFilesDir(), modelFileName);
            
            // 如果已存在，直接返回
            if (destFile.exists()) {
                Log.i(TAG, "模型文件已存在，跳过提取: " + destFile.getAbsolutePath());
                return destFile;
            }
            
            Log.i(TAG, "从OBB提取模型文件: " + modelFileName);
            try (InputStream inputStream = obbFile.getInputStream(entry);
                 FileOutputStream outputStream = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;
                
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                }
                
                Log.i(TAG, "模型提取完成，共 " + totalBytes + " bytes");
            }
            
            return destFile;
            
        } catch (IOException e) {
            Log.e(TAG, "提取模型文件失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 检查OBB文件是否存在
     */
    public boolean isObbAvailable() {
        File obbPath = getObbPath();
        return obbPath != null && obbPath.exists();
    }
    
    /**
     * 检查OBB中是否包含指定文件
     */
    public boolean hasFileInObb(String fileName) {
        // 首先检查缓存
        if (obbFileList != null && obbFileList.contains(fileName)) {
            return true;
        }
        
        // 如果缓存未命中，打开OBB文件并检查
        if (obbFile == null && !openObbFile()) {
            return false;
        }
        
        try {
            ZipArchiveEntry entry = obbFile.getEntry(fileName);
            return entry != null;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 关闭OBB文件
     */
    public void close() {
        if (obbFile != null) {
            try {
                obbFile.close();
                obbFile = null;
                currentObbPath = null;
                obbAvailable = false;
                obbFileList = null;
            } catch (Exception e) {
                Log.e(TAG, "关闭OBB文件失败: " + e.getMessage());
            }
        }
    }
}
