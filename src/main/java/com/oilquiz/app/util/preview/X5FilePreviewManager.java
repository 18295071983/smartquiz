package com.oilquiz.app.util.preview;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.util.Map;
import java.util.HashMap;

/**
 * X5文件预览管理器 - 使用腾讯X5内核预览各种文件
 */
public class X5FilePreviewManager {

    private static final String TAG = "X5FilePreviewManager";
    private static volatile X5FilePreviewManager instance;
    private Context context;
    private X5CoreStatus x5CoreStatus = X5CoreStatus.NOT_INITED;

    private X5FilePreviewManager(Context context) {
        this.context = context.getApplicationContext();
        initX5Core();
    }

    /**
     * 初始化X5内核
     */
    private void initX5Core() {
        x5CoreStatus = X5CoreStatus.INITING;
        
        try {
            // 使用反射初始化X5内核（使用TbsFileInterfaceImpl）
            Class<?> tbsFileInterfaceClass = Class.forName("com.tencent.tbs.reader.TbsFileInterfaceImpl");
            
            // 初始化SDK（尝试不同的方法名）
            java.lang.reflect.Method initEngineMethod = null;
            try {
                // 尝试init方法
                initEngineMethod = tbsFileInterfaceClass.getMethod("init", Context.class);
                initEngineMethod.invoke(null, context);
                x5CoreStatus = X5CoreStatus.AVAILABLE;
                Log.d(TAG, "X5内核初始化成功（使用init方法）");
            } catch (NoSuchMethodException e1) {
                try {
                    // 尝试initialize方法
                    initEngineMethod = tbsFileInterfaceClass.getMethod("initialize", Context.class);
                    initEngineMethod.invoke(null, context);
                    x5CoreStatus = X5CoreStatus.AVAILABLE;
                    Log.d(TAG, "X5内核初始化成功（使用initialize方法）");
                } catch (NoSuchMethodException e2) {
                    Log.e(TAG, "X5内核初始化失败，找不到合适的初始化方法", e2);
                    x5CoreStatus = X5CoreStatus.LOAD_FAILED;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "X5内核初始化失败: " + e.getMessage(), e);
            x5CoreStatus = X5CoreStatus.LOAD_FAILED;
        }
    }

    /**
     * 创建TbsReaderCallback
     */
    private Object createTbsReaderCallback() {
        try {
            Class<?> callbackClass = Class.forName("com.tencent.tbs.reader.ITbsReaderCallback");
            return java.lang.reflect.Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        if ("onCallBackAction".equals(method.getName())) {
                            int actionType = (int) args[0];
                            Object result = args[1];
                            
                            if (actionType == 0) { // OPEN_FILEREADER_ASYNC_LOAD_READER_ENTRY_CALLBACK
                                int loadResult = (int) result;
                                if (loadResult == 0) {
                                    x5CoreStatus = X5CoreStatus.AVAILABLE;
                                    Log.d(TAG, "X5内核初始化成功");
                                } else {
                                    x5CoreStatus = X5CoreStatus.LOAD_FAILED;
                                    Log.e(TAG, "X5内核初始化失败，错误码: " + loadResult);
                                }
                            }
                        }
                        return null;
                    }
            );
        } catch (Exception e) {
            Log.e(TAG, "创建TbsReaderCallback失败: " + e.getMessage(), e);
            return null;
        }
    }

    public static X5FilePreviewManager getInstance(Context context) {
        if (instance == null) {
            synchronized (X5FilePreviewManager.class) {
                if (instance == null) {
                    instance = new X5FilePreviewManager(context);
                }
            }
        }
        return instance;
    }

    /**
     * 预览文件
     * @param file 要预览的文件
     * @return 是否成功启动预览
     */
    public boolean previewFile(File file) {
        return previewFile(file, null);
    }

    /**
     * 预览文件（带预览选项）
     * @param file 要预览的文件
     * @param options 预览选项
     * @return 是否成功启动预览
     */
    public boolean previewFile(File file, PreviewOptions options) {
        if (file == null || !file.exists()) {
            Log.e(TAG, "文件不存在");
            return false;
        }

        // 检查文件大小
        if (file.length() > 500 * 1024 * 1024) { // 500MB
            Log.e(TAG, "文件过大，无法预览");
            return false;
        }

        // 检查X5内核状态
        if (x5CoreStatus != X5CoreStatus.AVAILABLE) {
            Log.e(TAG, "X5内核未初始化或不可用，状态: " + x5CoreStatus);
            // 尝试重新初始化
            initX5Core();
            return false;
        }

        try {
            // 使用TbsFileInterfaceImpl预览文件
            String filePath = file.getAbsolutePath();
            String fileName = file.getName();
            String fileExt = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();

            // 构建预览参数
            Bundle bundle = new Bundle();
            bundle.putString("filePath", filePath);
            bundle.putString("fileExt", fileExt);
            bundle.putString("tempPath", context.getCacheDir().getAbsolutePath());
            bundle.putString("fileName", fileName); // 添加文件名
            
            // 添加预览选项
            if (options != null) {
                if (options.isEnableEdit()) {
                    bundle.putBoolean("enableEdit", true);
                }
                if (options.isEnableShare()) {
                    bundle.putBoolean("enableShare", true);
                }
                if (options.isEnablePrint()) {
                    bundle.putBoolean("enablePrint", true);
                }
                if (options.getThemeColor() != 0) {
                    bundle.putInt("themeColor", options.getThemeColor());
                }
            }

            Log.d(TAG, "预览文件: " + filePath);
            Log.d(TAG, "文件扩展名: " + fileExt);
            Log.d(TAG, "临时目录: " + context.getCacheDir().getAbsolutePath());

            // 调用TbsFileInterfaceImpl打开文档
            Class<?> tbsFileInterfaceClass = Class.forName("com.tencent.tbs.reader.TbsFileInterfaceImpl");
            java.lang.reflect.Method openFileReaderMethod = tbsFileInterfaceClass.getMethod("openFileReader", Context.class, Bundle.class, Object.class, android.widget.FrameLayout.class);
            
            // 创建回调
            Object callback = createTbsReaderCallback();
            
            // 打开文档（传入null作为FrameLayout，使用默认dialog方式）
            int result = (int) openFileReaderMethod.invoke(null, context, bundle, callback, null);
            
            Log.d(TAG, "预览文件结果: " + result);
            
            return result == 0;
        } catch (Exception e) {
            Log.e(TAG, "预览文件失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取文件Uri，适配Android 7.0+
     * @param file 文件
     * @return 文件Uri
     */
    private Uri getUriForFile(File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // 对于Android 7.0+，使用FileProvider
            // 这里需要在AndroidManifest.xml中配置FileProvider
            // 为了简化，暂时使用Uri.fromFile，但在实际应用中应该使用FileProvider
            return Uri.fromFile(file);
        } else {
            // 对于Android 7.0以下，使用Uri.fromFile
            return Uri.fromFile(file);
        }
    }

    /**
     * 获取文件MIME类型
     * @param fileExt 文件扩展名
     * @return MIME类型
     */
    private String getMimeType(String fileExt) {
        Map<String, String> mimeTypes = new HashMap<>();
        // 文档类型
        mimeTypes.put("doc", "application/msword");
        mimeTypes.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        mimeTypes.put("xls", "application/vnd.ms-excel");
        mimeTypes.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        mimeTypes.put("ppt", "application/vnd.ms-powerpoint");
        mimeTypes.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        mimeTypes.put("pdf", "application/pdf");
        mimeTypes.put("txt", "text/plain");
        mimeTypes.put("rtf", "application/rtf");
        mimeTypes.put("odt", "application/vnd.oasis.opendocument.text");
        mimeTypes.put("ods", "application/vnd.oasis.opendocument.spreadsheet");
        mimeTypes.put("odp", "application/vnd.oasis.opendocument.presentation");
        
        // 图片类型
        mimeTypes.put("jpg", "image/jpeg");
        mimeTypes.put("jpeg", "image/jpeg");
        mimeTypes.put("png", "image/png");
        mimeTypes.put("gif", "image/gif");
        mimeTypes.put("bmp", "image/bmp");
        mimeTypes.put("webp", "image/webp");
        mimeTypes.put("svg", "image/svg+xml");
        
        // 视频类型
        mimeTypes.put("mp4", "video/mp4");
        mimeTypes.put("avi", "video/x-msvideo");
        mimeTypes.put("mov", "video/quicktime");
        mimeTypes.put("wmv", "video/x-ms-wmv");
        mimeTypes.put("flv", "video/x-flv");
        mimeTypes.put("mkv", "video/x-matroska");
        
        // 音频类型
        mimeTypes.put("mp3", "audio/mpeg");
        mimeTypes.put("wav", "audio/wav");
        mimeTypes.put("ogg", "audio/ogg");
        mimeTypes.put("aac", "audio/aac");
        mimeTypes.put("m4a", "audio/mp4");
        
        // 压缩包类型
        mimeTypes.put("zip", "application/zip");
        mimeTypes.put("rar", "application/x-rar-compressed");
        mimeTypes.put("7z", "application/x-7z-compressed");
        mimeTypes.put("tar", "application/x-tar");
        mimeTypes.put("gz", "application/gzip");
        
        // 其他类型
        mimeTypes.put("html", "text/html");
        mimeTypes.put("htm", "text/html");
        mimeTypes.put("xml", "text/xml");
        mimeTypes.put("json", "application/json");

        return mimeTypes.getOrDefault(fileExt, "application/octet-stream");
    }

    /**
     * 检查X5内核是否可用
     * @return 是否可用
     */
    public boolean isX5CoreAvailable() {
        return x5CoreStatus == X5CoreStatus.AVAILABLE;
    }

    /**
     * 获取X5内核版本
     * @return 版本号
     */
    public String getX5CoreVersion() {
        try {
            Class<?> tbsFileInterfaceClass = Class.forName("com.tencent.tbs.reader.TbsFileInterfaceImpl");
            java.lang.reflect.Method getVersionMethod = tbsFileInterfaceClass.getMethod("getVersion");
            return (String) getVersionMethod.invoke(null);
        } catch (Exception e) {
            Log.e(TAG, "获取X5内核版本失败: " + e.getMessage(), e);
            return "未知";
        }
    }

    /**
     * 清理X5内核缓存
     */
    public void clearX5Cache() {
        try {
            // 清理临时目录
            File tempDir = new File(context.getCacheDir(), "tbs");
            if (tempDir.exists()) {
                deleteDirectory(tempDir);
            }
            Log.d(TAG, "X5内核缓存已清理");
        } catch (Exception e) {
            Log.e(TAG, "清理X5内核缓存失败: " + e.getMessage(), e);
        }
    }

    /**
     * 检查X5内核是否需要更新
     * @return 是否需要更新
     */
    public boolean checkX5Update() {
        try {
            Class<?> tbsFileInterfaceClass = Class.forName("com.tencent.tbs.reader.TbsFileInterfaceImpl");
            java.lang.reflect.Method checkUpdateMethod = tbsFileInterfaceClass.getMethod("checkUpdate", Context.class);
            return (boolean) checkUpdateMethod.invoke(null, context);
        } catch (Exception e) {
            Log.e(TAG, "检查X5内核更新失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 预下载X5内核
     */
    public void preloadX5Core() {
        try {
            Class<?> tbsFileInterfaceClass = Class.forName("com.tencent.tbs.reader.TbsFileInterfaceImpl");
            java.lang.reflect.Method preloadMethod = tbsFileInterfaceClass.getMethod("preload", Context.class);
            preloadMethod.invoke(null, context);
            Log.d(TAG, "X5内核预下载中");
        } catch (Exception e) {
            Log.e(TAG, "预下载X5内核失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除目录
     * @param directory 目录
     */
    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }

    /**
     * 获取X5内核加载状态
     * @return 加载状态
     */
    public X5CoreStatus getX5CoreStatus() {
        return x5CoreStatus;
    }

    /**
     * X5内核状态枚举
     */
    public enum X5CoreStatus {
        NOT_INITED,    // 未初始化
        INITING,       // 初始化中
        AVAILABLE,     // 可用
        LOAD_FAILED    // 加载失败
    }

    /**
     * 预览选项类
     */
    public static class PreviewOptions {
        private boolean enableEdit = false;
        private boolean enableShare = true;
        private boolean enablePrint = true;
        private int themeColor = 0;

        public boolean isEnableEdit() {
            return enableEdit;
        }

        public PreviewOptions setEnableEdit(boolean enableEdit) {
            this.enableEdit = enableEdit;
            return this;
        }

        public boolean isEnableShare() {
            return enableShare;
        }

        public PreviewOptions setEnableShare(boolean enableShare) {
            this.enableShare = enableShare;
            return this;
        }

        public boolean isEnablePrint() {
            return enablePrint;
        }

        public PreviewOptions setEnablePrint(boolean enablePrint) {
            this.enablePrint = enablePrint;
            return this;
        }

        public int getThemeColor() {
            return themeColor;
        }

        public PreviewOptions setThemeColor(int themeColor) {
            this.themeColor = themeColor;
            return this;
        }
    }
}
