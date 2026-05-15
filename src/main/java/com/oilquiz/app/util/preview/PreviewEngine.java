package com.oilquiz.app.util.preview;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;

public interface PreviewEngine {
    
    /**
     * 预览文件
     * @param context 上下文
     * @param file 文件
     * @param callback 回调
     */
    void preview(Context context, File file, PreviewCallback callback);
    
    /**
     * 预览文件（带进度回调）
     * @param context 上下文
     * @param file 文件
     * @param callback 回调
     * @param progressCallback 进度回调
     */
    void preview(Context context, File file, PreviewCallback callback, PreviewProgressCallback progressCallback);
    
    /**
     * 检查文件是否支持预览
     * @param file 文件
     * @return 是否支持
     */
    boolean supports(File file);
    
    /**
     * 获取引擎名称
     * @return 引擎名称
     */
    String getEngineName();
    
    /**
     * 预览回调接口
     */
    interface PreviewCallback {
        void onSuccess(Bitmap bitmap);
        void onFailure(String error);
    }
    
    /**
     * 预览进度回调接口
     */
    interface PreviewProgressCallback {
        void onProgress(int progress);
        void onTotalSize(long totalSize);
        void onCurrentSize(long currentSize);
    }
}