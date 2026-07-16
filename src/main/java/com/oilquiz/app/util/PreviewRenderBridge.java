package com.oilquiz.app.util;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.util.preview.FilePreviewManager;
import com.oilquiz.app.util.preview.PreviewEngine;
import com.oilquiz.app.util.render.FileRenderEngine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 预览和渲染功能的桥接类，确保两个模块的协同工作
 */
public class PreviewRenderBridge {
    private static final String TAG = "PreviewRenderBridge";
    private FilePreviewManager previewManager;
    private RenderEngineFactory renderEngineFactory;

    private Context context;

    public PreviewRenderBridge(Context context) {
        this.context = context;
        this.previewManager = FilePreviewManager.getInstance();
        this.renderEngineFactory = RenderEngineFactory.getInstance();
    }

    /**
     * 预览文件，优先使用缓存，缓存未命中时使用渲染引擎
     * @param file 要预览的文件
     * @param callback 预览回调
     */
    public void previewFile(File file, PreviewCallback callback) {
        if (file == null || !file.exists()) {
            callback.onError("文件不存在");
            return;
        }

        // 首先尝试使用预览管理器
        previewManager.preview(context, file, new PreviewEngine.PreviewCallback() {
            @Override
            public void onSuccess(android.graphics.Bitmap bitmap) {
                callback.onSuccess(bitmap);
            }

            @Override
            public void onFailure(String error) {
                Log.w(TAG, "预览失败，尝试使用渲染引擎: " + error);
                // 预览失败，尝试使用渲染引擎
                renderFile(file, callback);
            }
        });
    }

    /**
     * 渲染文件
     * @param file 要渲染的文件
     * @param callback 渲染回调
     */
    public void renderFile(File file, PreviewCallback callback) {
        if (file == null || !file.exists()) {
            callback.onError("文件不存在");
            return;
        }

        // 获取适合的渲染引擎
        FileRenderEngine engine = renderEngineFactory.getEngineForFile(file);
        if (engine != null) {
            engine.render(file, new FileRenderEngine.RenderCallback() {
                @Override
                public void onSuccess(Object renderedContent) {
                    callback.onSuccess(renderedContent);
                }

                @Override
                public void onError(String message) {
                    callback.onError(message);
                }

                @Override
                public void onProgress(int progress) {
                    callback.onProgress(progress);
                }
            });
        } else {
            callback.onError("不支持的文件格式");
        }
    }

    /**
     * 检查文件是否可预览
     * @param file 要检查的文件
     * @return 是否可预览
     */
    public boolean canPreview(File file) {
        if (file == null || !file.exists()) {
            return false;
        }

        // 检查是否有对应的渲染引擎
        FileRenderEngine engine = renderEngineFactory.getEngineForFile(file);
        return engine != null;
    }

    /**
     * 清理预览缓存
     */
    public void clearPreviewCache() {
        previewManager.clearPreviewCache(context);
    }

    /**
     * 获取预览缓存大小
     * @return 缓存大小（字节）
     */
    public long getPreviewCacheSize() {
        return previewManager.getPreviewCacheSize(context);
    }

    /**
     * 预览回调接口
     */
    public interface PreviewCallback {
        void onSuccess(Object previewContent);
        void onError(String error);
        void onProgress(int progress);
    }

    /**
     * 渲染引擎工厂类，用于获取适合的渲染引擎
     */
    public static class RenderEngineFactory {
        private static RenderEngineFactory instance;
        private List<FileRenderEngine> engines;

        private RenderEngineFactory() {
            engines = new ArrayList<>();
            // 注册所有渲染引擎
            registerEngines();
        }

        /**
         * 注册所有渲染引擎
         */
        private void registerEngines() {
            // 先注册具体的引擎
            engines.add(new com.oilquiz.app.util.render.CSVRenderEngine());
            engines.add(new com.oilquiz.app.util.render.HTMLRenderEngine());
            engines.add(new com.oilquiz.app.util.render.MarkdownRenderEngine());
            engines.add(new com.oilquiz.app.util.render.PDFRenderEngine());
            engines.add(new com.oilquiz.app.util.render.ImageRenderEngine());
            engines.add(new com.oilquiz.app.util.render.WordRenderEngine());
            engines.add(new com.oilquiz.app.util.render.ExcelRenderEngine());
            engines.add(new com.oilquiz.app.util.render.PowerPointRenderEngine());
            // 最后注册通用的文本引擎
            engines.add(new com.oilquiz.app.util.render.TextRenderEngine());
        }

        /**
         * 获取单例实例
         * @return RenderEngineFactory实例
         */
        public static synchronized RenderEngineFactory getInstance() {
            if (instance == null) {
                instance = new RenderEngineFactory();
            }
            return instance;
        }

        /**
         * 获取适合指定文件的渲染引擎
         * @param file 文件
         * @return 渲染引擎
         */
        public FileRenderEngine getEngineForFile(File file) {
            for (FileRenderEngine engine : engines) {
                if (engine.canRender(file)) {
                    return engine;
                }
            }
            return null;
        }

        /**
         * 获取所有渲染引擎
         * @return 渲染引擎列表
         */
        public List<FileRenderEngine> getAllEngines() {
            return engines;
        }
    }
}