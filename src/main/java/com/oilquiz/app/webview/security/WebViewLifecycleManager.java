package com.oilquiz.app.webview.security;

import android.os.FileObserver;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * WebView 生命周期管理器
 * 防止内存泄漏，统一管理 WebView 资源
 */
public class WebViewLifecycleManager {
    private static final String TAG = "WebViewLifecycleManager";
    private static volatile WebViewLifecycleManager instance;
    
    // WebView 池（复用已销毁的 WebView）
    private final List<WebView> webViewPool = new ArrayList<>();
    private final int MAX_POOL_SIZE = 3;
    
    // 活跃的 WebView 列表
    private final List<WeakReference<WebView>> activeWebViews = new ArrayList<>();
    
    // FileObserver 列表
    private final List<FileObserver> fileObservers = new ArrayList<>();

    private WebViewLifecycleManager() {
    }
    
    public static WebViewLifecycleManager getInstance() {
        if (instance == null) {
            synchronized (WebViewLifecycleManager.class) {
                if (instance == null) {
                    instance = new WebViewLifecycleManager();
                }
            }
        }
        return instance;
    }

    /**
     * 从池中获取 WebView，或创建新的
     */
    public WebView acquireWebView(WebView webView) {
        synchronized (webViewPool) {
            if (!webViewPool.isEmpty()) {
                WebView pooled = webViewPool.remove(0);
                Log.d(TAG, "从池中获取 WebView，池剩余: " + webViewPool.size());
                
                // 清理旧 WebView
                cleanupWebView(pooled);
                
                activeWebViews.add(new WeakReference<>(pooled));
                return pooled;
            }
        }
        
        // 记录新 WebView
        activeWebViews.add(new WeakReference<>(webView));
        return webView;
    }

    /**
     * 回收 WebView 到池中
     */
    public void releaseWebView(WebView webView) {
        if (webView == null) return;
        
        synchronized (webViewPool) {
            if (webViewPool.size() < MAX_POOL_SIZE) {
                cleanupWebView(webView);
                webViewPool.add(webView);
                Log.d(TAG, "WebView 已回收，池大小: " + webViewPool.size());
            } else {
                // 池已满，彻底销毁
                destroyWebView(webView);
                Log.d(TAG, "WebView 池已满，彻底销毁");
            }
            
            // 从活跃列表移除
            activeWebViews.removeIf(ref -> {
                WebView wv = ref.get();
                return wv == null || wv == webView;
            });
        }
    }

    /**
     * 清理 WebView（保留在池中）
     */
    public void cleanupWebView(WebView webView) {
        if (webView == null) return;
        
        try {
            // 1. 停止加载
            try {
                webView.stopLoading();
            } catch (Exception e) {
                // 忽略异常
            }
            
            // 2. 暂停 JavaScript
            webView.onPause();
            
            // 3. 清除历史
            webView.clearHistory();
            
            // 4. 清除缓存（可选）
            // webView.clearCache(true);
            
            // 5. 移除 JavaScript 接口
            try {
                webView.removeJavascriptInterface("Android");
                webView.removeJavascriptInterface("AndroidUtil");
                webView.removeJavascriptInterface("AndroidClipboard");
                webView.removeJavascriptInterface("AndroidFile");
                webView.removeJavascriptInterface("AndroidDatabase");
                webView.removeJavascriptInterface("AndroidAI");
            } catch (Exception e) {
                Log.w(TAG, "移除 JavaScript 接口时出错", e);
            }
            
            // 6. 清除 WebView 客户端
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            
            Log.d(TAG, "WebView 清理完成");
            
        } catch (Exception e) {
            Log.e(TAG, "清理 WebView 失败", e);
        }
    }

    /**
     * 彻底销毁 WebView
     */
    public void destroyWebView(WebView webView) {
        if (webView == null) return;
        
        try {
            // 1. 清理 WebView
            cleanupWebView(webView);
            
            // 2. 从父视图移除
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            
            // 3. 销毁 WebView
            webView.destroy();
            
            Log.d(TAG, "WebView 已销毁");
            
        } catch (Exception e) {
            Log.e(TAG, "销毁 WebView 失败", e);
        }
    }

    /**
     * 注册 FileObserver
     */
    public void registerFileObserver(FileObserver observer) {
        synchronized (fileObservers) {
            fileObservers.add(observer);
            Log.d(TAG, "FileObserver 已注册，总数: " + fileObservers.size());
        }
    }

    /**
     * 注销所有 FileObserver
     */
    public void unregisterAllFileObservers() {
        synchronized (fileObservers) {
            for (FileObserver observer : fileObservers) {
                try {
                    observer.stopWatching();
                } catch (Exception e) {
                    Log.w(TAG, "停止 FileObserver 失败", e);
                }
            }
            fileObservers.clear();
            Log.d(TAG, "所有 FileObserver 已注销");
        }
    }

    /**
     * 清理所有 WebView
     */
    public void destroyAllWebViews() {
        Log.d(TAG, "开始清理所有 WebView...");
        
        // 清理池中的
        synchronized (webViewPool) {
            for (WebView wv : webViewPool) {
                try {
                    destroyWebView(wv);
                } catch (Exception e) {
                    Log.w(TAG, "销毁池中 WebView 失败", e);
                }
            }
            webViewPool.clear();
        }
        
        // 清理活跃的
        for (WeakReference<WebView> ref : activeWebViews) {
            WebView wv = ref.get();
            if (wv != null) {
                try {
                    destroyWebView(wv);
                } catch (Exception e) {
                    Log.w(TAG, "销毁活跃 WebView 失败", e);
                }
            }
        }
        activeWebViews.clear();
        
        // 清理 FileObserver
        unregisterAllFileObservers();
        
        Log.d(TAG, "所有 WebView 已清理完成");
    }

    /**
     * 获取活跃 WebView 数量
     */
    public int getActiveWebViewCount() {
        int count = 0;
        for (WeakReference<WebView> ref : activeWebViews) {
            if (ref.get() != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取池中 WebView 数量
     */
    public int getPoolSize() {
        return webViewPool.size();
    }

    /**
     * 释放未使用的池中 WebView
     */
    public void prunePool() {
        synchronized (webViewPool) {
            while (webViewPool.size() > 1) {
                WebView wv = webViewPool.remove(webViewPool.size() - 1);
                destroyWebView(wv);
            }
            Log.d(TAG, "池修剪完成，剩余: " + webViewPool.size());
        }
    }

    /**
     * 重置实例（用于测试或内存清理）
     */
    public void reset() {
        destroyAllWebViews();
        instance = null;
    }
}
