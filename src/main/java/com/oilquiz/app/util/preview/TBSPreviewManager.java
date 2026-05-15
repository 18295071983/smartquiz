package com.oilquiz.app.util.preview;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;

import com.oilquiz.app.infra.AppLogger;

import java.io.File;

/**
 * TBS SDK 预览管理器
 * 封装 TBS SDK 的文档预览功能
 * 
 * 使用说明：
 * 1. 下载 TBS SDK AAR 文件放到 app/libs/ 目录
 * 2. 申请 LicenseKey 并配置到代码中
 * 3. 在 Application 或 MainActivity 中初始化
 */
public class TBSPreviewManager {
    
    private static final String TAG = "TBSPreviewManager";
    
    // TBS SDK LicenseKey
    private static final String TBS_LICENSE_KEY = "TAr5IUwL2uW29JKv1m+n7hSsef69k6qKiYtiJfuxd9e+Eo/XgiXYSPApHryre/Ls";
    
    private static TBSPreviewManager instance;
    private Context context;
    private boolean isInitialized = false;
    private boolean isTBSAvailable = false;
    private boolean isPrivacyPolicyAccepted = false;
    
    // TBS SDK 类（通过反射加载）
    private Class<?> tbsFileInterfaceClass;
    private Object tbsFileInterfaceInstance;
    
    private TBSPreviewManager(Context context) {
        this.context = context.getApplicationContext();
        checkTBSAvailability();
    }
    
    public static synchronized TBSPreviewManager getInstance(Context context) {
        if (instance == null) {
            instance = new TBSPreviewManager(context);
        }
        return instance;
    }
    
    /**
     * 检查 TBS SDK 是否可用
     */
    private void checkTBSAvailability() {
        try {
            tbsFileInterfaceClass = Class.forName("com.tencent.tbs.reader.TbsFileInterfaceImpl");
            isTBSAvailable = true;
            AppLogger.d(TAG, "TBS SDK 可用");
        } catch (ClassNotFoundException e) {
            isTBSAvailable = false;
            AppLogger.w(TAG, "TBS SDK 未集成，将使用备用渲染引擎");
        }
    }
    
    /**
     * 设置隐私政策同意状态
     * 根据腾讯文档要求：用户同意隐私政策后，才能调用 setLicenseKey 和 initEngine
     * @param accepted 用户是否同意隐私政策
     */
    public void setPrivacyPolicyAccepted(boolean accepted) {
        this.isPrivacyPolicyAccepted = accepted;
        AppLogger.d(TAG, "隐私政策同意状态: " + accepted);
    }
    
    /**
     * 检查是否已同意隐私政策
     * @return true 如果用户已同意隐私政策
     */
    public boolean isPrivacyPolicyAccepted() {
        return isPrivacyPolicyAccepted;
    }
    
    /**
     * 初始化 TBS SDK
     * 注意：根据腾讯文档要求，用户必须先同意隐私政策才能调用此方法
     * @return 0 表示成功，其他表示失败
     */
    public int initialize() {
        if (!isTBSAvailable) {
            AppLogger.w(TAG, "TBS SDK 不可用，跳过初始化");
            return -1;
        }
        
        // 检查隐私政策同意状态
        if (!isPrivacyPolicyAccepted) {
            AppLogger.e(TAG, "用户未同意隐私政策，无法初始化 TBS SDK");
            AppLogger.e(TAG, "请先调用 setPrivacyPolicyAccepted(true)");
            return -1;
        }
        
        try {
            // 设置 LicenseKey
            tbsFileInterfaceClass.getMethod("setLicenseKey", String.class)
                    .invoke(null, TBS_LICENSE_KEY);
            
            // 检查是否已经加载
            Boolean isLoaded = (Boolean) tbsFileInterfaceClass.getMethod("isEngineLoaded")
                    .invoke(null);
            
            if (isLoaded) {
                isInitialized = true;
                return 0;
            }
            
            // 初始化引擎
            Integer result = (Integer) tbsFileInterfaceClass.getMethod("initEngine", Context.class)
                    .invoke(null, context);
            
            if (result == 0) {
                isInitialized = true;
                AppLogger.d(TAG, "TBS SDK 初始化成功");
            } else {
                AppLogger.e(TAG, "TBS SDK 初始化失败，错误码: " + result);
            }
            
            return result;
        } catch (Exception e) {
            AppLogger.e(TAG, "TBS SDK 初始化异常: " + e.getMessage(), e);
            return -1;
        }
    }
    
    /**
     * 异步初始化 TBS SDK
     * 注意：根据腾讯文档要求，用户必须先同意隐私政策才能调用此方法
     */
    public void initializeAsync(final TBSInitCallback callback) {
        if (!isTBSAvailable) {
            AppLogger.w(TAG, "TBS SDK 不可用");
            if (callback != null) {
                callback.onInitResult(false, -1);
            }
            return;
        }
        
        // 检查隐私政策同意状态
        if (!isPrivacyPolicyAccepted) {
            AppLogger.e(TAG, "用户未同意隐私政策，无法初始化 TBS SDK");
            AppLogger.e(TAG, "请先调用 setPrivacyPolicyAccepted(true)");
            if (callback != null) {
                callback.onInitResult(false, -1);
            }
            return;
        }
        
        try {
            // 设置 LicenseKey
            tbsFileInterfaceClass.getMethod("setLicenseKey", String.class)
                    .invoke(null, TBS_LICENSE_KEY);
            
            // 创建回调
            Class<?> tbsReaderCallbackClass = Class.forName("com.tencent.tbs.reader.ITbsReaderCallback");
            Object tbsCallback = java.lang.reflect.Proxy.newProxyInstance(
                    context.getClassLoader(),
                    new Class<?>[]{tbsReaderCallbackClass},
                    (proxy, method, args) -> {
                        if ("onCallBackAction".equals(method.getName()) && args.length >= 3) {
                            Integer actionType = (Integer) args[0];
                            Object actionArgs = args[1];
                            
                            // OPEN_FILEREADER_ASYNC_LOAD_READER_ENTRY_CALLBACK = 7002
                            // 注意：根据腾讯文档，异步初始化回调的 actionType 是 7002，不是错误码
                            if (actionType == 7002 && actionArgs instanceof Integer) {
                                int ret = (Integer) actionArgs;
                                isInitialized = (ret == 0);
                                AppLogger.d(TAG, "TBS 异步初始化回调，错误码: " + ret);
                                if (callback != null) {
                                    callback.onInitResult(ret == 0, ret);
                                }
                            }
                        }
                        return null;
                    }
            );
            
            // 异步初始化
            tbsFileInterfaceClass.getMethod("initEngineAsync", Context.class, tbsReaderCallbackClass)
                    .invoke(null, context, tbsCallback);
            
        } catch (Exception e) {
            AppLogger.e(TAG, "TBS SDK 异步初始化异常: " + e.getMessage(), e);
            if (callback != null) {
                callback.onInitResult(false, -1);
            }
        }
    }
    
    /**
     * 检查文件格式是否支持
     * 注意：此方法可以在初始化之前调用
     */
    public boolean canOpenFile(String filePath) {
        if (!isTBSAvailable) {
            return false;
        }
        
        String ext = getFileExtension(filePath);
        try {
            Boolean result = (Boolean) tbsFileInterfaceClass.getMethod("canOpenFileExt", String.class)
                    .invoke(null, ext);
            return result != null && result;
        } catch (Exception e) {
            AppLogger.e(TAG, "检查文件格式失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 打开文件预览
     * @param filePath 文件路径
     * @param callback 预览回调
     * @return 0 表示成功
     */
    public int openFile(String filePath, TBSPreviewCallback callback) {
        return openFile(filePath, callback, null);
    }
    
    /**
     * 打开文件预览（自定义布局）
     * @param filePath 文件路径
     * @param callback 预览回调
     * @param container 容器布局（null 则使用默认 Dialog）
     * @return 0 表示成功
     */
    public int openFile(String filePath, TBSPreviewCallback callback, FrameLayout container) {
        // 使用保存的 context（Application Context），但 TBS 需要 Activity Context
        // 这个方法保留用于兼容，实际应该使用带 Activity 参数的版本
        return openFileWithActivity(context, filePath, callback, container);
    }
    
    /**
     * 打开文件预览（使用 Activity Context - 推荐）
     * @param activity Activity 上下文（必须是 Activity，不能是 Application）
     * @param filePath 文件路径
     * @param callback 预览回调
     * @return 0 表示成功
     */
    public int openFileWithActivity(Context activity, String filePath, TBSPreviewCallback callback) {
        return openFileWithActivity(activity, filePath, callback, null);
    }
    
    /**
     * 打开文件预览（使用 Activity Context - 完整版）
     * @param activity Activity 上下文（必须是 Activity，不能是 Application）
     * @param filePath 文件路径
     * @param callback 预览回调
     * @param container 容器布局（null 则使用默认 Dialog）
     * @return 0 表示成功
     */
    public int openFileWithActivity(Context activity, String filePath, TBSPreviewCallback callback, FrameLayout container) {
        // 检查TBS SDK状态
        if (!isTBSAvailable) {
            AppLogger.e(TAG, "TBS SDK 不可用");
            return -1;
        }
        
        if (!isInitialized) {
            AppLogger.e(TAG, "TBS SDK 未初始化，请先调用 initialize()");
            return -1;
        }
        
        // 检查文件
        java.io.File file = new java.io.File(filePath);
        if (!file.exists() || !file.canRead()) {
            AppLogger.e(TAG, "文件不存在或无法读取: " + filePath);
            return -1;
        }
        
        String ext = getFileExtension(filePath);
        if (!canOpenFile(filePath)) {
            AppLogger.w(TAG, "不支持的文件格式: " + ext);
            return -1;
        }
        
        AppLogger.d(TAG, "TBS打开文件: " + filePath + " (" + file.length() + " bytes)");
        
        try {
            // 准备参数
            Bundle param = new Bundle();
            param.putString("filePath", filePath);
            param.putString("fileExt", ext);
            
            // 临时目录
            java.io.File tempDir = new java.io.File(activity.getExternalFilesDir(null), "tbs_temp");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
            param.putString("tempPath", tempDir.getAbsolutePath());
            
            // 可选参数
            param.putBoolean("file_reader_enable_long_press_menu", true);
            param.putBoolean("file_reader_goto_last_pos", true);
            
            // Dialog模式参数
            if (container == null) {
                param.putString("file_reader_top_bar_bg_color", "#3B82F6");
                param.putInt("file_reader_top_bar_hight", 120);
            }
            
            // 创建回调
            Class<?> tbsReaderCallbackClass = Class.forName("com.tencent.tbs.reader.ITbsReaderCallback");
            Object tbsCallback = java.lang.reflect.Proxy.newProxyInstance(
                    activity.getClassLoader(),
                    new Class<?>[]{tbsReaderCallbackClass},
                    (proxy, method, args) -> {
                        if ("onCallBackAction".equals(method.getName()) && callback != null) {
                            handleCallback(args, callback);
                        }
                        return null;
                    }
            );
            
            // 打开文件
            Object instance = tbsFileInterfaceClass.getMethod("getInstance").invoke(null);
            Integer ret = (Integer) tbsFileInterfaceClass
                    .getMethod("openFileReader", Context.class, Bundle.class, 
                              tbsReaderCallbackClass, FrameLayout.class)
                    .invoke(instance, activity, param, tbsCallback, container);
            
            if (ret != 0) {
                AppLogger.e(TAG, "TBS打开文件失败，错误码: " + ret);
            }
            return ret != null ? ret : -1;
            
        } catch (Exception e) {
            AppLogger.e(TAG, "打开文件失败: " + e.getMessage(), e);
            return -1;
        }
    }
    
    /**
     * 关闭文件预览
     */
    public void closeFile() {
        if (!isTBSAvailable || !isInitialized) {
            return;
        }
        
        try {
            Object instance = tbsFileInterfaceClass.getMethod("getInstance").invoke(null);
            tbsFileInterfaceClass.getMethod("closeFileReader").invoke(instance);
        } catch (Exception e) {
            AppLogger.e(TAG, "关闭文件失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 跳转到指定页面
     * 支持 PDF、DOCX、PPTX 格式
     * @param pageNumber 目标页码（从1开始）
     */
    public void gotoPage(int pageNumber) {
        if (!isTBSAvailable || !isInitialized) {
            AppLogger.w(TAG, "TBS SDK 未初始化，无法跳转页面");
            return;
        }
        
        try {
            Bundle bundle = new Bundle();
            bundle.putInt("page", pageNumber);
            
            Object instance = tbsFileInterfaceClass.getMethod("getInstance").invoke(null);
            tbsFileInterfaceClass.getMethod("gotoPosition", Bundle.class)
                    .invoke(instance, bundle);
            
            AppLogger.d(TAG, "跳转到页面: " + pageNumber);
        } catch (Exception e) {
            AppLogger.e(TAG, "页面跳转失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 横屏适配
     * 根据腾讯文档：自定义 layout 方式需要主动调用接口适配横屏
     * 默认 dialog 模式无需调用该接口
     * @param width layout宽度
     * @param height layout高度
     */
    public void onSizeChanged(int width, int height) {
        if (!isTBSAvailable || !isInitialized) {
            AppLogger.w(TAG, "TBS SDK 未初始化，无法调整大小");
            return;
        }
        
        try {
            Object instance = tbsFileInterfaceClass.getMethod("getInstance").invoke(null);
            tbsFileInterfaceClass.getMethod("onSizeChanged", int.class, int.class)
                    .invoke(instance, width, height);
            
            AppLogger.d(TAG, "TBS 布局调整: " + width + "x" + height);
        } catch (Exception e) {
            AppLogger.e(TAG, "布局调整失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理 TBS 回调
     */
    private void handleCallback(Object[] args, TBSPreviewCallback callback) {
        try {
            if (args.length < 3) return;
            
            Integer actionType = (Integer) args[0];
            Object actionArgs = args[1];
            Object result = args[2];
            
            // 处理不同的事件类型
            switch (actionType) {
                case 5001: // OPEN_FILEREADER_STATUS_UI_CALLBACK
                    if (actionArgs instanceof Bundle) {
                        Bundle bundle = (Bundle) actionArgs;
                        int typeId = bundle.getInt("typeId");
                        if (typeId == 0) {
                            callback.onFileOpened();
                        } else if (typeId == 1) {
                            callback.onFileClosed();
                        }
                    }
                    break;
                case 5002: // NOTIFY_CANDISPLAY
                    callback.onReadyToDisplay();
                    break;
                case 5003: // READER_EVENT_CLICK
                    callback.onClick();
                    break;
                case 5004: // READER_EVENT_SCROLL_BEGIN
                    callback.onScrollBegin();
                    break;
                case 5005: // READER_EVENT_SCROLL_END
                    callback.onScrollEnd();
                    break;
                case 5006: // READER_EVENT_SCALE_BEGIN
                    callback.onScaleBegin();
                    break;
                case 5007: // READER_EVENT_SCALE_END
                    callback.onScaleEnd();
                    break;
                case 5008: // READER_PAGE_TOAST
                    if (actionArgs instanceof Bundle) {
                        Bundle bundle = (Bundle) actionArgs;
                        int curPage = bundle.getInt("cur_page");
                        int pageCount = bundle.getInt("page_count");
                        callback.onPageChanged(curPage, pageCount);
                    }
                    break;
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "处理回调失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filePath) {
        if (filePath == null || !filePath.contains(".")) {
            return "";
        }
        return filePath.substring(filePath.lastIndexOf(".") + 1).toLowerCase();
    }
    
    /**
     * 检查 TBS SDK 是否可用
     */
    public boolean isTBSAvailable() {
        return isTBSAvailable;
    }
    
    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return isInitialized;
    }
    
    /**
     * 初始化回调接口
     */
    public interface TBSInitCallback {
        void onInitResult(boolean success, int errorCode);
    }
    
    /**
     * 预览回调接口
     */
    public interface TBSPreviewCallback {
        void onFileOpened();
        void onFileClosed();
        void onReadyToDisplay();
        void onClick();
        void onScrollBegin();
        void onScrollEnd();
        void onScaleBegin();
        void onScaleEnd();
        void onPageChanged(int currentPage, int totalPages);
    }
}
