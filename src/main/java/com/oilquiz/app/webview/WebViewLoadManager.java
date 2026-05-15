package com.oilquiz.app.webview;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.resource.AppResourceManager;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * WebView加载管理器
 * 提供WebView池管理、延迟加载、智能预加载等优化功能
 */
public class WebViewLoadManager {
    
    private static final String TAG = "WebViewLoadManager";
    private static final int MAX_POOL_SIZE = 3; // WebView池最大大小
    private static final int PRELOAD_DELAY_MS = 300; // 预加载延迟
    
    private static WebViewLoadManager instance;
    private Context context;
    
    // WebView池
    private Queue<WebView> webViewPool;
    private List<WebView> activeWebViews;
    
    // 预加载任务队列
    private Queue<PreloadTask> preloadQueue;
    private Handler preloadHandler;
    
    // 加载状态回调
    private PageLoadCallback pageLoadCallback;
    
    // 配置选项
    private boolean enableHardwareAcceleration = true;
    private boolean enableCache = true;
    private boolean enablePreload = true;
    
    private WebViewLoadManager(Context context) {
        this.context = context.getApplicationContext();
        this.webViewPool = new LinkedList<>();
        this.activeWebViews = new ArrayList<>();
        this.preloadQueue = new LinkedList<>();
        this.preloadHandler = new Handler(Looper.getMainLooper());
    }
    
    public static synchronized WebViewLoadManager getInstance(Context context) {
        if (instance == null) {
            instance = new WebViewLoadManager(context);
        }
        return instance;
    }
    
    /**
     * 从池中获取WebView
     */
    public WebView acquireWebView() {
        WebView webView = webViewPool.poll();
        if (webView == null) {
            webView = createWebView();
        }
        activeWebViews.add(webView);
        prepareWebView(webView);
        return webView;
    }
    
    /**
     * 将WebView归还到池中
     */
    public void releaseWebView(WebView webView) {
        if (webView == null) return;
        
        activeWebViews.remove(webView);
        
        // 清理WebView状态
        cleanupWebView(webView);
        
        // 如果池未满，将WebView加入池中
        if (webViewPool.size() < MAX_POOL_SIZE) {
            webViewPool.offer(webView);
        } else {
            // 池已满，销毁WebView
            destroyWebView(webView);
        }
    }
    
    /**
     * 创建新的WebView
     */
    private WebView createWebView() {
        AppLogger.d(TAG, "创建新的WebView实例");
        WebView webView = new WebView(context);
        
        // 配置WebSettings
        configureWebSettings(webView.getSettings());
        
        // 启用硬件加速
        if (enableHardwareAcceleration && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        
        return webView;
    }
    
    /**
     * 配置WebSettings
     */
    private void configureWebSettings(WebSettings settings) {
        // 使用AppResourceManager的配置
        AppResourceManager.getInstance(context).webView().configureWebSettings(settings);
        
        // 额外的性能优化
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(enableCache ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_NO_CACHE);
        
        // 图片加载优化 - 先不自动加载图片，等页面加载完成后再加载
        settings.setLoadsImagesAutomatically(false);
        settings.setBlockNetworkImage(true);
        
        // 布局优化
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        
        // 字体优化
        settings.setDefaultFontSize(16);
        settings.setMinimumFontSize(12);
        settings.setDefaultTextEncodingName("UTF-8");
        
        // 缩放控制
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
    }
    
    /**
     * 准备WebView（从池中取出后的初始化）
     */
    private void prepareWebView(WebView webView) {
        // 重置WebView状态
        webView.stopLoading();
        webView.clearHistory();
        webView.setVisibility(View.VISIBLE);
        
        // 恢复图片加载
        webView.getSettings().setLoadsImagesAutomatically(true);
        webView.getSettings().setBlockNetworkImage(false);
    }
    
    /**
     * 清理WebView（归还到池前的清理）
     */
    private void cleanupWebView(WebView webView) {
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.clearHistory();
        
        // 移除所有视图
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) {
            parent.removeView(webView);
        }
        
        // 清除客户端回调
        webView.setWebViewClient(null);
        webView.setWebChromeClient(null);
        
        // 隐藏WebView
        webView.setVisibility(View.GONE);
    }
    
    /**
     * 销毁WebView
     */
    private void destroyWebView(WebView webView) {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.removeAllViews();
            webView.destroy();
        }
    }
    
    /**
     * 延迟加载URL
     */
    public void loadUrlDelayed(final WebView webView, final String url, long delayMillis) {
        if (webView == null || url == null) return;
        
        preloadHandler.postDelayed(() -> {
            if (activeWebViews.contains(webView)) {
                loadUrlInternal(webView, url);
            }
        }, delayMillis);
    }
    
    /**
     * 立即加载URL
     */
    public void loadUrl(WebView webView, String url) {
        if (webView == null || url == null) return;
        loadUrlInternal(webView, url);
    }
    
    /**
     * 内部加载URL方法
     */
    private void loadUrlInternal(WebView webView, String url) {
        // 设置加载回调
        setupLoadCallbacks(webView);
        
        // 开始加载
        webView.loadUrl(url);
    }
    
    /**
     * 加载HTML内容
     */
    public void loadData(WebView webView, String data, String mimeType, String encoding) {
        if (webView == null || data == null) return;
        
        setupLoadCallbacks(webView);
        webView.loadDataWithBaseURL(null, data, mimeType, encoding, null);
    }
    
    /**
     * 带BaseURL加载HTML
     */
    public void loadDataWithBaseURL(WebView webView, String baseUrl, String data, 
                                     String mimeType, String encoding, String historyUrl) {
        if (webView == null || data == null) return;
        
        setupLoadCallbacks(webView);
        webView.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl);
    }
    
    /**
     * 设置加载回调
     */
    private void setupLoadCallbacks(WebView webView) {
        webView.setWebViewClient(new OptimizedWebViewClient());
        webView.setWebChromeClient(new OptimizedWebChromeClient());
    }
    
    /**
     * 添加预加载任务
     */
    public void addPreloadTask(String url) {
        if (!enablePreload || url == null) return;
        
        PreloadTask task = new PreloadTask(url);
        preloadQueue.offer(task);
        
        // 延迟执行预加载
        preloadHandler.postDelayed(() -> executePreloadTask(), PRELOAD_DELAY_MS);
    }
    
    /**
     * 执行预加载任务
     */
    private void executePreloadTask() {
        PreloadTask task = preloadQueue.poll();
        if (task == null) return;
        
        // 创建临时WebView进行预加载
        WebView preloadWebView = createWebView();
        preloadWebView.loadUrl(task.url);
        
        // 预加载完成后销毁临时WebView
        preloadHandler.postDelayed(() -> {
            destroyWebView(preloadWebView);
        }, 5000); // 5秒后销毁
    }
    
    /**
     * 设置页面加载回调
     */
    public void setPageLoadCallback(PageLoadCallback callback) {
        this.pageLoadCallback = callback;
    }
    
    /**
     * 清空WebView池
     */
    public void clearPool() {
        for (WebView webView : webViewPool) {
            destroyWebView(webView);
        }
        webViewPool.clear();
    }
    
    /**
     * 释放所有资源
     */
    public void releaseAll() {
        // 释放活跃WebView
        for (WebView webView : new ArrayList<>(activeWebViews)) {
            releaseWebView(webView);
        }
        activeWebViews.clear();
        
        // 清空池
        clearPool();
        
        // 清空预加载队列
        preloadQueue.clear();
        preloadHandler.removeCallbacksAndMessages(null);
    }
    
    /**
     * 设置是否启用硬件加速
     */
    public void setEnableHardwareAcceleration(boolean enable) {
        this.enableHardwareAcceleration = enable;
    }
    
    /**
     * 设置是否启用缓存
     */
    public void setEnableCache(boolean enable) {
        this.enableCache = enable;
    }
    
    /**
     * 设置是否启用预加载
     */
    public void setEnablePreload(boolean enable) {
        this.enablePreload = enable;
    }
    
    /**
     * 获取池中WebView数量
     */
    public int getPoolSize() {
        return webViewPool.size();
    }
    
    /**
     * 获取活跃WebView数量
     */
    public int getActiveCount() {
        return activeWebViews.size();
    }
    
    /**
     * 优化的WebViewClient
     */
    private class OptimizedWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            AppLogger.d(TAG, "页面开始加载: " + url);
            if (pageLoadCallback != null) {
                pageLoadCallback.onPageStarted(url);
            }
        }
        
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            AppLogger.d(TAG, "页面加载完成: " + url);
            
            // 页面加载完成后启用图片加载
            view.getSettings().setLoadsImagesAutomatically(true);
            view.getSettings().setBlockNetworkImage(false);
            
            // 注入优化脚本
            injectOptimizationScript(view);
            
            if (pageLoadCallback != null) {
                pageLoadCallback.onPageFinished(url);
            }
        }
        
        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            super.onReceivedError(view, errorCode, description, failingUrl);
            AppLogger.e(TAG, "页面加载错误 [" + errorCode + "]: " + description);
            if (pageLoadCallback != null) {
                pageLoadCallback.onError(errorCode, description, failingUrl);
            }
        }
    }
    
    /**
     * 优化的WebChromeClient
     */
    private class OptimizedWebChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            if (pageLoadCallback != null) {
                pageLoadCallback.onProgressChanged(newProgress);
            }
        }
        
        @Override
        public void onReceivedTitle(WebView view, String title) {
            super.onReceivedTitle(view, title);
            if (pageLoadCallback != null) {
                pageLoadCallback.onReceivedTitle(title);
            }
        }
    }
    
    /**
     * 注入优化脚本
     */
    private void injectOptimizationScript(WebView view) {
        String script = "javascript:(function() { " +
            "var style = document.createElement('style'); " +
            "style.textContent = 'body { max-width: 100%; margin: 0; padding: 10px; overflow-x: hidden; } " +
            "img { max-width: 100%; height: auto; display: block; } " +
            "table { max-width: 100%; overflow-x: auto; display: block; }'; " +
            "document.head.appendChild(style); " +
            "})();";
        view.loadUrl(script);
    }
    
    /**
     * 预加载任务
     */
    private static class PreloadTask {
        String url;
        long createTime;
        
        PreloadTask(String url) {
            this.url = url;
            this.createTime = System.currentTimeMillis();
        }
    }
    
    /**
     * 页面加载回调接口
     */
    public interface PageLoadCallback {
        void onPageStarted(String url);
        void onPageFinished(String url);
        void onProgressChanged(int progress);
        void onReceivedTitle(String title);
        void onError(int errorCode, String description, String failingUrl);
    }
    
    /**
     * 简化的页面加载回调适配器
     */
    public static abstract class PageLoadCallbackAdapter implements PageLoadCallback {
        @Override
        public void onPageStarted(String url) {}
        @Override
        public void onPageFinished(String url) {}
        @Override
        public void onProgressChanged(int progress) {}
        @Override
        public void onReceivedTitle(String title) {}
        @Override
        public void onError(int errorCode, String description, String failingUrl) {}
    }
}
