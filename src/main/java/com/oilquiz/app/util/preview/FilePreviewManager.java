package com.oilquiz.app.util.preview;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件预览管理器
 * 管理所有预览引擎，提供统一的文件预览接口
 */
public class FilePreviewManager {

    private static FilePreviewManager instance;
    private List<PreviewEngine> engines;
    private X5PreviewEngine x5PreviewEngine;
    private Context appContext;

    private FilePreviewManager() {
        engines = new ArrayList<>();
    }

    public static synchronized FilePreviewManager getInstance() {
        if (instance == null) {
            instance = new FilePreviewManager();
        }
        return instance;
    }

    /**
     * 初始化预览引擎
     * @param context 上下文
     */
    public void initialize(Context context) {
        this.appContext = context.getApplicationContext();
        initializeEngines();
    }

    private void initializeEngines() {
        if (appContext == null) {
            throw new IllegalStateException("FilePreviewManager 未初始化，请先调用 initialize()");
        }
        
        engines.clear();
        
        // 添加各种预览引擎
        engines.add(new ImagePreviewEngine());
        engines.add(new TextPreviewEngine());
        engines.add(new PDFPreviewEngine());
        engines.add(new ExcelPreviewEngine());
        engines.add(new MarkdownPreviewEngine());
        engines.add(new HTMLPreviewEngine());
        
        // 初始化X5预览引擎
        x5PreviewEngine = new X5PreviewEngine(appContext);
        engines.add(x5PreviewEngine);
    }

    public void preview(Context context, File file, PreviewEngine.PreviewCallback callback) {
        preview(context, file, callback, null);
    }

    public void preview(Context context, File file, PreviewEngine.PreviewCallback callback, PreviewEngine.PreviewProgressCallback progressCallback) {
        if (engines.isEmpty()) {
            initialize(context);
        }
        
        for (PreviewEngine engine : engines) {
            if (engine.supports(file)) {
                engine.preview(context, file, callback, progressCallback);
                return;
            }
        }
        callback.onFailure("未找到合适的预览引擎");
    }

    /**
     * 使用X5引擎预览文件
     * @param context 上下文
     * @param file 文件
     * @param callback 回调
     * @param progressCallback 进度回调
     */
    public void previewWithX5(Context context, File file, PreviewEngine.PreviewCallback callback, 
                             PreviewEngine.PreviewProgressCallback progressCallback) {
        if (x5PreviewEngine == null) {
            x5PreviewEngine = new X5PreviewEngine(context);
        }
        
        if (x5PreviewEngine.supports(file)) {
            x5PreviewEngine.preview(context, file, callback, progressCallback);
        } else {
            callback.onFailure("X5引擎不支持此文件类型");
        }
    }

    /**
     * 清理预览缓存
     * @param context 上下文
     */
    public void clearPreviewCache(Context context) {
        BasePreviewEngine.clearCache(context);
    }

    /**
     * 获取预览缓存大小
     * @param context 上下文
     * @return 缓存大小（字节）
     */
    public long getPreviewCacheSize(Context context) {
        return BasePreviewEngine.getCacheSize(context);
    }

    public List<PreviewEngine> getEngines() {
        return engines;
    }

    public PreviewEngine getEngineForFile(File file) {
        for (PreviewEngine engine : engines) {
            if (engine.supports(file)) {
                return engine;
            }
        }
        return null;
    }

    /**
     * 获取X5预览引擎
     * @return X5预览引擎
     */
    public X5PreviewEngine getX5PreviewEngine() {
        return x5PreviewEngine;
    }

    /**
     * 检查X5内核是否可用
     * @return true 如果TBS SDK可用
     */
    public boolean isX5Available() {
        return x5PreviewEngine != null && x5PreviewEngine.isTBSAvailable();
    }

    /**
     * 检查X5内核是否已初始化
     * @return true 如果TBS SDK已初始化
     */
    public boolean isX5Initialized() {
        return x5PreviewEngine != null && x5PreviewEngine.isInitialized();
    }

    /**
     * 初始化X5内核
     * @return 0 表示成功
     */
    public int initializeX5() {
        if (x5PreviewEngine != null) {
            return x5PreviewEngine.initialize();
        }
        return -1;
    }

    /**
     * 异步初始化X5内核
     * @param callback 初始化回调
     */
    public void initializeX5Async(TBSPreviewManager.TBSInitCallback callback) {
        if (x5PreviewEngine != null) {
            x5PreviewEngine.initializeAsync(callback);
        } else if (callback != null) {
            callback.onInitResult(false, -1);
        }
    }
    
    /**
     * 使用 Pdfium 引擎预览 PDF 文件
     * 免费开源方案，无需网络，无需 LicenseKey
     * @param context 上下文
     * @param filePath PDF 文件路径
     */
    public void previewWithPdfium(Context context, String filePath) {
        com.oilquiz.app.ui.activity.PdfiumPreviewActivity.start(context, filePath);
    }
    
    /**
     * 使用 TBS SDK 预览文件
     * @param context 上下文
     * @param filePath 文件路径
     */
    public void previewWithTBS(Context context, String filePath) {
        com.oilquiz.app.ui.activity.TBSFilePreviewActivity.start(context, filePath);
    }
    
    /**
     * 使用 LibreOfficeKit 引擎预览文档
     * 开源免费方案，支持多种 Office 格式
     * @param context 上下文
     * @param filePath 文件路径
     */
    public void previewWithLibreOffice(Context context, String filePath) {
        com.oilquiz.app.ui.activity.LibreOfficeKitPreviewActivity.start(context, filePath);
    }
    
    /**
     * 使用 OnlyOffice 引擎预览文档
     * 开源免费方案，支持多种 Office 格式
     * @param context 上下文
     * @param filePath 文件路径
     */
    public void previewWithOnlyOffice(Context context, String filePath) {
        com.oilquiz.app.ui.activity.OnlyOfficePreviewActivity.start(context, filePath);
    }
    
    /**
     * 预览数据类
     */
    public static class PreviewData {
        private String content;
        private Bitmap thumbnail;
        private long fileSize;
        private String mimeType;
        private boolean isX5Supported;
        
        public PreviewData(String content, Bitmap thumbnail, long fileSize, String mimeType, boolean isX5Supported) {
            this.content = content;
            this.thumbnail = thumbnail;
            this.fileSize = fileSize;
            this.mimeType = mimeType;
            this.isX5Supported = isX5Supported;
        }
        
        public String getContent() {
            return content;
        }
        
        public Bitmap getThumbnail() {
            return thumbnail;
        }
        
        public long getFileSize() {
            return fileSize;
        }
        
        public String getMimeType() {
            return mimeType;
        }
        
        public boolean isX5Supported() {
            return isX5Supported;
        }
    }
}
