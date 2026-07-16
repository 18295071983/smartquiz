package com.oilquiz.app.util.render;

import android.content.Context;

import java.io.File;

public interface FileRenderEngine {
    /**
     * 渲染文件
     * @param file 要渲染的文件
     * @param callback 渲染回调
     */
    void render(File file, RenderCallback callback);
    
    /**
     * 检查文件是否可以被此引擎渲�?     * @param file 要检查的文件
     * @return 是否可以渲染
     */
    boolean canRender(File file);
    
    /**
     * 获取引擎名称
     * @return 引擎名称
     */
    String getEngineName();
    
    /**
     * 获取文件类型描述
     * @param file 文件
     * @return 文件类型描述
     */
    String getFileTypeDescription(File file);
    
    /**
     * 渲染回调接口
     */
    interface RenderCallback {
        void onSuccess(Object renderedContent);
        void onError(String message);
        void onProgress(int progress);
    }
}
