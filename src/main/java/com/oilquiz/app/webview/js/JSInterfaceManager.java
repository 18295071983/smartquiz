package com.oilquiz.app.webview.js;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JavaScript 接口管理器
 * 统一管理所有 JS 接口，按模块化组织
 * 替换原来的 WebAppInterface
 */
public class JSInterfaceManager {
    private static final String TAG = "JSInterfaceManager";
    private static volatile JSInterfaceManager instance;
    
    private final WeakReference<Context> contextRef;
    private final Handler mainHandler;
    
    // 模块化接口实例
    private JSToolInterface toolInterface;
    private JSClipboardInterface clipboardInterface;
    private JSFileInterface fileInterface;
    private JSDatabaseInterface databaseInterface;
    
    // 接口启用状态
    private boolean enableTool = true;
    private boolean enableClipboard = true;
    private boolean enableFile = true;
    private boolean enableDatabase = true;
    
    // JS 回调存储（用于异步调用）
    private final ConcurrentHashMap<String, JSCallback> jsCallbacks = new ConcurrentHashMap<>();

    private JSInterfaceManager(Context context) {
        this.contextRef = new WeakReference<>(context.getApplicationContext());
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        // 初始化各模块接口
        initInterfaces();
    }
    
    public static JSInterfaceManager getInstance(Context context) {
        if (instance == null) {
            synchronized (JSInterfaceManager.class) {
                if (instance == null) {
                    instance = new JSInterfaceManager(context);
                }
            }
        }
        return instance;
    }
    
    private void initInterfaces() {
        Context context = contextRef.get();
        if (context == null) return;
        
        toolInterface = new JSToolInterface(context);
        clipboardInterface = new JSClipboardInterface(context);
        fileInterface = new JSFileInterface(context);
        databaseInterface = new JSDatabaseInterface(context);
        
        Log.d(TAG, "JS接口模块初始化完成");
    }

    /**
     * 获取工具接口
     */
    public JSToolInterface getToolInterface() {
        return toolInterface;
    }
    
    /**
     * 获取剪贴板接口
     */
    public JSClipboardInterface getClipboardInterface() {
        return clipboardInterface;
    }
    
    /**
     * 获取文件接口
     */
    public JSFileInterface getFileInterface() {
        return fileInterface;
    }
    
    /**
     * 获取数据库接口
     */
    public JSDatabaseInterface getDatabaseInterface() {
        return databaseInterface;
    }
    


    /**
     * 启用/禁用接口模块
     */
    public void setModuleEnabled(String module, boolean enabled) {
        switch (module.toLowerCase()) {
            case "tool":
                enableTool = enabled;
                Log.d(TAG, "工具接口: " + (enabled ? "启用" : "禁用"));
                break;
            case "clipboard":
                enableClipboard = enabled;
                Log.d(TAG, "剪贴板接口: " + (enabled ? "启用" : "禁用"));
                break;
            case "file":
                enableFile = enabled;
                Log.d(TAG, "文件接口: " + (enabled ? "启用" : "禁用"));
                break;
            case "database":
                enableDatabase = enabled;
                Log.d(TAG, "数据库接口: " + (enabled ? "启用" : "禁用"));
                break;
        }
    }
    
    /**
     * 获取接口模块启用状态
     */
    @JavascriptInterface
    public String getModuleStatus() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"tool\":").append(enableTool).append(",");
        sb.append("\"clipboard\":").append(enableClipboard).append(",");
        sb.append("\"file\":").append(enableFile).append(",");
        sb.append("\"database\":").append(enableDatabase);
        sb.append("}");
        return sb.toString();
    }

    /**
     * 通用回调接口
     */
    public interface JSCallback {
        void onResult(String result);
    }
    
    /**
     * 注册 JS 回调
     */
    public void registerCallback(String callbackId, JSCallback callback) {
        jsCallbacks.put(callbackId, callback);
        // 5分钟后自动移除（防止内存泄漏）
        mainHandler.postDelayed(() -> jsCallbacks.remove(callbackId), 5 * 60 * 1000);
    }
    
    /**
     * 触发 JS 回调
     */
    public void triggerCallback(String callbackId, String result) {
        JSCallback callback = jsCallbacks.get(callbackId);
        if (callback != null) {
            callback.onResult(result);
            jsCallbacks.remove(callbackId);
        }
    }

    /**
     * 获取接口版本信息
     */
    @JavascriptInterface
    public String getInterfaceVersion() {
        return "{\"version\": \"2.0\", \"modules\": [\"tool\", \"clipboard\", \"file\", \"database\"]}";
    }

    /**
     * 获取调试信息
     */
    @JavascriptInterface
    public String getDebugInfo() {
        Context context = contextRef.get();
        if (context == null) return "{}";
        
        StringBuilder info = new StringBuilder();
        info.append("{");
        info.append("\"contextAvailable\": true,");
        info.append("\"modules\": {");
        info.append("\"tool\": ").append(enableTool).append(",");
        info.append("\"clipboard\": ").append(enableClipboard).append(",");
        info.append("\"file\": ").append(enableFile).append(",");
        info.append("\"database\": ").append(enableDatabase);
        info.append("},");
        info.append("\"callbackCount\": ").append(jsCallbacks.size());
        info.append("}");
        return info.toString();
    }

    /**
     * 清理资源
     */
    public void destroy() {
        Log.d(TAG, "销毁 JS 接口管理器");
        
        if (databaseInterface != null) {
            databaseInterface.destroy();
        }
        
        jsCallbacks.clear();
        
        instance = null;
    }
}
