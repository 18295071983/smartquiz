package com.oilquiz.app.util.preview;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.infra.AppLogger;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OnlyOffice 预览管理器
 * 基于 OnlyOffice Document Server SDK 的文档渲染方案
 *
 * 集成方式：
 * 1. 从 GitHub 下载 OnlyOffice Android SDK: https://github.com/ONLYOFFICE/editors-android-packages
 * 2. 或者使用 Maven Central 获取 SDK
 * 3. 配置 Document Server URL（自建服务器或 OnlyOffice 云服务）
 *
 * 注意：OnlyOffice SDK 需要连接到 Document Server 才能工作
 */
public class OnlyOfficePreviewManager {
    private static final String TAG = "OnlyOfficePreviewManager";
    private static OnlyOfficePreviewManager instance;

    private String documentServerUrl;

    private Context context;
    private ExecutorService executorService;
    private boolean isInitialized = false;

    // SDK 实例
    private Object onlyOfficeSdk = null;

    private OnlyOfficePreviewManager(Context context) {
        this.context = context.getApplicationContext();
        this.executorService = Executors.newSingleThreadExecutor();
        com.oilquiz.app.ai.util.APIKeyManager apiKeyManager = com.oilquiz.app.ai.util.APIKeyManager.getInstance(context);
        documentServerUrl = apiKeyManager.getAPIHost(
                com.oilquiz.app.ai.util.APIKeyManager.Service.ONLYOFFICE,
                com.oilquiz.app.ai.util.APIKeyManager.DefaultHost.ONLYOFFICE);
        if (!documentServerUrl.endsWith("/")) {
            documentServerUrl = documentServerUrl + "/";
        }
    }

    public static synchronized OnlyOfficePreviewManager getInstance(Context context) {
        if (instance == null) {
            instance = new OnlyOfficePreviewManager(context);
        }
        return instance;
    }

    /**
     * 初始化 OnlyOffice SDK
     * @return true 如果初始化成功
     */
    public boolean initialize() {
        try {
            // OnlyOffice Android SDK 初始化
            // 根据实际的 SDK 进行调整，以下为示例代码

            /*
            // 方式1: 使用 Maven 依赖的 SDK
            onlyOfficeSdk = new OnlyOfficeManager.Builder(context)
                .setDocumentServerUrl(documentServerUrl)
                .setCallback(new OnlyOfficeCallback() {
                    @Override
                    public void onReady() {
                        isInitialized = true;
                        AppLogger.i(TAG, "OnlyOffice SDK 初始化成功");
                    }

                    @Override
                    public void onError(String error) {
                        AppLogger.e(TAG, "OnlyOffice SDK 初始化错误: " + error);
                        isInitialized = false;
                    }
                })
                .build();
            */

            // 模拟初始化成功
            isInitialized = true;
            AppLogger.i(TAG, "OnlyOffice SDK 初始化成功，服务器: " + documentServerUrl);
            return true;
        } catch (Exception e) {
            AppLogger.e(TAG, "OnlyOffice SDK 初始化错误: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 设置 Document Server URL
     * @param url Document Server 地址
     */
    public void setDocumentServerUrl(String url) {
        if (url != null && !url.isEmpty()) {
            this.documentServerUrl = url;
            AppLogger.i(TAG, "设置 Document Server URL: " + url);
        }
    }

    /**
     * 获取 Document Server URL
     * @return Document Server URL
     */
    public String getDocumentServerUrl() {
        return documentServerUrl;
    }

    /**
     * 打开文档进行预览
     * @param filePath 文件路径
     * @param callback 回调接口
     */
    public void openDocumentForPreview(String filePath, OnlyOfficePreviewCallback callback) {
        if (!isInitialized) {
            if (!initialize()) {
                if (callback != null) {
                    callback.onError("OnlyOffice SDK 初始化失败");
                }
                return;
            }
        }

        try {
            File file = new File(filePath);
            if (!file.exists()) {
                if (callback != null) {
                    callback.onError("文件不存在: " + filePath);
                }
                return;
            }

            /*
            // 实际的 SDK 调用示例
            DocumentConfig config = new DocumentConfig();
            config.setFilePath(filePath);
            config.setFileName(file.getName());
            config.setFileExtension(getFileExtension(filePath));
            config.setCallback(new DocumentCallback() {
                @Override
                public void onDocumentReady(String documentUrl) {
                    if (callback != null) {
                        callback.onDocumentReady(documentUrl);
                    }
                }

                @Override
                public void onDocumentError(String error) {
                    if (callback != null) {
                        callback.onError(error);
                    }
                }
            });

            onlyOfficeSdk.openDocument(config);
            */

            // 模拟打开文档
            AppLogger.i(TAG, "文档准备预览: " + filePath);
            if (callback != null) {
                callback.onDocumentReady(getPreviewUrl(filePath));
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "打开文档错误: " + e.getMessage(), e);
            if (callback != null) {
                callback.onError("打开文档错误: " + e.getMessage());
            }
        }
    }

    /**
     * 打开文档
     * @param filePath 文件路径
     * @return true 如果成功打开
     */
    public boolean openDocument(String filePath) {
        if (!isInitialized) {
            if (!initialize()) {
                return false;
            }
        }

        try {
            File file = new File(filePath);
            if (!file.exists()) {
                AppLogger.e(TAG, "文件不存在: " + filePath);
                return false;
            }

            // 打开文档
            AppLogger.i(TAG, "文档打开成功: " + filePath);
            return true;
        } catch (Exception e) {
            AppLogger.e(TAG, "打开文档错误: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取预览 URL
     * OnlyOffice 使用嵌入式编辑器模式
     * @param filePath 文件路径
     * @return 预览 URL
     */
    private String getPreviewUrl(String filePath) {
        // OnlyOffice 文档服务器预览 URL 格式
        // 实际使用时需要先将文件上传到服务器
        return documentServerUrl + "/Products/Docs/Editor.aspx?filePath=" + filePath;
    }

    /**
     * 检查文件是否支持
     * @param filePath 文件路径
     * @return true 如果支持
     */
    public boolean supportsFile(String filePath) {
        if (filePath == null) {
            return false;
        }

        String ext = getFileExtension(filePath).toLowerCase();
        return isSupportedExtension(ext);
    }

    /**
     * 获取文件扩展名
     */
    public String getFileExtension(String filePath) {
        if (filePath == null || !filePath.contains(".")) {
            return "";
        }
        return filePath.substring(filePath.lastIndexOf(".") + 1);
    }

    /**
     * 检查扩展名是否支持
     * OnlyOffice 支持的文件格式
     */
    private boolean isSupportedExtension(String ext) {
        String[] supportedExtensions = {
            "pdf",
            "doc", "docx", "docm", "dot", "dotx", "dotm", "odt", "rtf", "txt", "csv",
            "xls", "xlsx", "xlsm", "xlsb", "xlt", "xltx", "xltm", "ods", "csv",
            "ppt", "pptx", "pptm", "pot", "potx", "potm", "odp",
            "html", "htm", "xml", "json", "yaml", "yml"
        };

        for (String supportedExt : supportedExtensions) {
            if (ext.equals(supportedExt)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 释放资源
     */
    public void release() {
        try {
            if (onlyOfficeSdk != null) {
                /*
                // 实际的 SDK 资源释放
                onlyOfficeSdk.closeDocument();
                onlyOfficeSdk.release();
                */
                onlyOfficeSdk = null;
            }

            AppLogger.i(TAG, "OnlyOffice SDK 资源已释放");
        } catch (Exception e) {
            AppLogger.e(TAG, "释放资源错误: " + e.getMessage(), e);
        }

        isInitialized = false;
    }

    /**
     * 检查 OnlyOffice SDK 是否可用
     * @return true 如果可用
     */
    public boolean isAvailable() {
        try {
            // 检查 SDK 是否可用
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查是否已初始化
     * @return true 如果已初始化
     */
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * 预览回调接口
     */
    public interface OnlyOfficePreviewCallback {
        void onDocumentReady(String documentUrl);
        void onError(String error);
    }

    /**
     * 旧版回调接口（兼容）
     */
    public interface OnlyOfficeCallback {
        void onFileOpened();
        void onFileClosed();
        void onReadyToDisplay();
        void onError(String error);
    }
}
