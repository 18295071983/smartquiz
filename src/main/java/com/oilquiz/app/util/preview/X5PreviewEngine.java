package com.oilquiz.app.util.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;

import com.oilquiz.app.infra.AppLogger;

import java.io.File;

/**
 * X5/TBS 文件预览引擎
 * 
 * 使用腾讯浏览服务(TBS) SDK 进行文件预览
 * 支持格式：doc, docx, xls, xlsx, ppt, pptx, pdf, txt, epub, chm
 * 
 * 注意：需要先在项目中集成 TBS SDK AAR 文件
 */
public class X5PreviewEngine implements PreviewEngine {
    
    private static final String TAG = "X5PreviewEngine";
    
    private TBSPreviewManager tbsManager;
    
    public X5PreviewEngine(Context context) {
        this.tbsManager = TBSPreviewManager.getInstance(context);
    }
    
    @Override
    public boolean supports(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        
        // 首先检查 TBS SDK 是否可用
        if (tbsManager.isTBSAvailable()) {
            return tbsManager.canOpenFile(file.getAbsolutePath());
        }
        
        // TBS 不可用时，检查是否是支持的格式
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".doc") || fileName.endsWith(".docx") ||
               fileName.endsWith(".xls") || fileName.endsWith(".xlsx") ||
               fileName.endsWith(".ppt") || fileName.endsWith(".pptx") ||
               fileName.endsWith(".pdf") || fileName.endsWith(".txt") ||
               fileName.endsWith(".epub") || fileName.endsWith(".chm");
    }
    
    @Override
    public void preview(Context context, File file, PreviewCallback callback) {
        preview(context, file, callback, null);
    }
    
    @Override
    public void preview(Context context, File file, PreviewCallback callback, PreviewProgressCallback progressCallback) {
        if (file == null || !file.exists()) {
            if (callback != null) {
                callback.onFailure("文件不存在");
            }
            return;
        }
        
        // 检查 TBS SDK 是否可用
        if (!tbsManager.isTBSAvailable()) {
            AppLogger.w(TAG, "TBS SDK 不可用，无法使用 X5PreviewEngine");
            if (callback != null) {
                callback.onFailure("TBS SDK 未集成");
            }
            return;
        }
        
        // 检查是否已初始化
        if (!tbsManager.isInitialized()) {
            AppLogger.d(TAG, "TBS SDK 未初始化，尝试初始化...");
            int ret = tbsManager.initialize();
            if (ret != 0) {
                AppLogger.e(TAG, "TBS SDK 初始化失败: " + ret);
                if (callback != null) {
                    callback.onFailure("TBS SDK 初始化失败: " + ret);
                }
                return;
            }
        }
        
        // 检查文件格式
        if (!tbsManager.canOpenFile(file.getAbsolutePath())) {
            AppLogger.w(TAG, "不支持的文件格式: " + file.getName());
            if (callback != null) {
                callback.onFailure("不支持的文件格式");
            }
            return;
        }
        
        // TBS SDK 使用自己的预览界面，不返回 Bitmap
        // 这里我们返回一个空的 Bitmap，实际预览由 TBS SDK 处理
        AppLogger.d(TAG, "使用 TBS SDK 打开文件: " + file.getAbsolutePath());
        int ret = tbsManager.openFile(file.getAbsolutePath(), new TBSPreviewManager.TBSPreviewCallback() {
            @Override
            public void onFileOpened() {
                AppLogger.d(TAG, "文件已打开");
            }
            
            @Override
            public void onFileClosed() {
                AppLogger.d(TAG, "文件已关闭");
            }
            
            @Override
            public void onReadyToDisplay() {
                AppLogger.d(TAG, "准备显示文档");
                // TBS 预览已准备就绪，返回成功
                if (callback != null) {
                    // TBS 使用自己的预览界面，这里返回 null Bitmap
                    callback.onSuccess(null);
                }
            }
            
            @Override
            public void onClick() {
                AppLogger.d(TAG, "点击事件");
            }
            
            @Override
            public void onScrollBegin() {
                AppLogger.d(TAG, "开始滚动");
            }
            
            @Override
            public void onScrollEnd() {
                AppLogger.d(TAG, "结束滚动");
            }
            
            @Override
            public void onScaleBegin() {
                AppLogger.d(TAG, "开始缩放");
            }
            
            @Override
            public void onScaleEnd() {
                AppLogger.d(TAG, "结束缩放");
            }
            
            @Override
            public void onPageChanged(int currentPage, int totalPages) {
                AppLogger.d(TAG, "页面变化: " + currentPage + "/" + totalPages);
                if (progressCallback != null) {
                    int progress = totalPages > 0 ? (currentPage * 100 / totalPages) : 0;
                    progressCallback.onProgress(progress);
                }
            }
        }, null);
        
        if (ret != 0) {
            AppLogger.e(TAG, "打开文件失败: " + ret);
            if (callback != null) {
                callback.onFailure("打开文件失败: " + ret);
            }
        }
    }
    
    @Override
    public String getEngineName() {
        return "X5/TBS 预览引擎";
    }
    
    /**
     * 检查 TBS SDK 是否可用
     */
    public boolean isTBSAvailable() {
        return tbsManager.isTBSAvailable();
    }
    
    /**
     * 检查 TBS SDK 是否已初始化
     */
    public boolean isInitialized() {
        return tbsManager.isInitialized();
    }
    
    /**
     * 初始化 TBS SDK
     * @return 0 表示成功
     */
    public int initialize() {
        return tbsManager.initialize();
    }
    
    /**
     * 异步初始化 TBS SDK
     * @param callback 初始化回调
     */
    public void initializeAsync(TBSPreviewManager.TBSInitCallback callback) {
        tbsManager.initializeAsync(callback);
    }
    
    /**
     * 关闭文件预览
     */
    public void closeFile() {
        tbsManager.closeFile();
    }
}
