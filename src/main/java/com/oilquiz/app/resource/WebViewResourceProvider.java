package com.oilquiz.app.resource;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.RawRes;

import com.oilquiz.app.R;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * WebView资源提供者
 * 通过系统资源接口动态获取WebView组件配置
 */
public class WebViewResourceProvider {
    
    private static final String TAG = "WebViewResourceProvider";
    private static WebViewResourceProvider instance;
    private Context context;
    
    // WebView配置缓存
    private Map<String, Object> webViewConfig;
    
    // JavaScript接口映射
    private Map<String, Object> javascriptInterfaces;
    
    private WebViewResourceProvider(Context context) {
        this.context = context.getApplicationContext();
        this.webViewConfig = new HashMap<>();
        this.javascriptInterfaces = new HashMap<>();
        initDefaultConfig();
    }
    
    public static synchronized WebViewResourceProvider getInstance(Context context) {
        if (instance == null) {
            instance = new WebViewResourceProvider(context);
        }
        return instance;
    }
    
    /**
     * 初始化默认WebView配置
     */
    private void initDefaultConfig() {
        // 基本设置
        webViewConfig.put("javaScriptEnabled", true);
        webViewConfig.put("domStorageEnabled", true);
        webViewConfig.put("databaseEnabled", true);
        webViewConfig.put("cacheMode", WebSettings.LOAD_DEFAULT);
        
        // 缩放设置
        webViewConfig.put("supportZoom", true);
        webViewConfig.put("builtInZoomControls", true);
        webViewConfig.put("displayZoomControls", false);
        
        // 布局设置
        webViewConfig.put("loadWithOverviewMode", true);
        webViewConfig.put("useWideViewPort", true);
        webViewConfig.put("layoutAlgorithm", WebSettings.LayoutAlgorithm.NORMAL);
        
        // 文件访问设置
        webViewConfig.put("allowFileAccess", true);
        webViewConfig.put("allowContentAccess", true);
        webViewConfig.put("allowFileAccessFromFileURLs", true);
        webViewConfig.put("allowUniversalAccessFromFileURLs", true);
        
        // 多媒体设置
        webViewConfig.put("loadsImagesAutomatically", true);
        webViewConfig.put("mediaPlaybackRequiresUserGesture", false);
        
        // 性能设置
        webViewConfig.put("renderPriority", WebSettings.RenderPriority.HIGH);
        webViewConfig.put("enableSmoothTransition", true);
        
        // 安全设置
        webViewConfig.put("mixedContentMode", WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
    }
    
    /**
     * 配置WebView设置
     */
    public void configureWebSettings(WebSettings settings) {
        // JavaScript
        settings.setJavaScriptEnabled((Boolean) webViewConfig.get("javaScriptEnabled"));
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        
        // DOM Storage
        settings.setDomStorageEnabled((Boolean) webViewConfig.get("domStorageEnabled"));
        settings.setDatabaseEnabled((Boolean) webViewConfig.get("databaseEnabled"));
        
        // Cache
        settings.setCacheMode((Integer) webViewConfig.get("cacheMode"));
        
        // Zoom
        settings.setSupportZoom((Boolean) webViewConfig.get("supportZoom"));
        settings.setBuiltInZoomControls((Boolean) webViewConfig.get("builtInZoomControls"));
        settings.setDisplayZoomControls((Boolean) webViewConfig.get("displayZoomControls"));
        
        // Layout
        settings.setLoadWithOverviewMode((Boolean) webViewConfig.get("loadWithOverviewMode"));
        settings.setUseWideViewPort((Boolean) webViewConfig.get("useWideViewPort"));
        settings.setLayoutAlgorithm((WebSettings.LayoutAlgorithm) webViewConfig.get("layoutAlgorithm"));
        
        // File Access
        settings.setAllowFileAccess((Boolean) webViewConfig.get("allowFileAccess"));
        settings.setAllowContentAccess((Boolean) webViewConfig.get("allowContentAccess"));
        settings.setAllowFileAccessFromFileURLs((Boolean) webViewConfig.get("allowFileAccessFromFileURLs"));
        settings.setAllowUniversalAccessFromFileURLs((Boolean) webViewConfig.get("allowUniversalAccessFromFileURLs"));
        
        // Media
        settings.setLoadsImagesAutomatically((Boolean) webViewConfig.get("loadsImagesAutomatically"));
        settings.setMediaPlaybackRequiresUserGesture((Boolean) webViewConfig.get("mediaPlaybackRequiresUserGesture"));
        
        // Performance
        settings.setRenderPriority((WebSettings.RenderPriority) webViewConfig.get("renderPriority"));
        
        // Security
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode((Integer) webViewConfig.get("mixedContentMode"));
        }
        
        // Text Encoding
        settings.setDefaultTextEncodingName("UTF-8");
        
        // Font Size
        settings.setDefaultFontSize(16);
        settings.setMinimumFontSize(12);
        
        // User-Agent - 设置为安卓手机竖屏
        String androidMobileUserAgent = "Mozilla/5.0 (Linux; Android 13; SM-G998U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Mobile Safari/537.36";
        settings.setUserAgentString(androidMobileUserAgent);
    }
    
    /**
     * 从资源加载HTML内容
     */
    public String loadHtmlFromResource(@RawRes int resId) {
        StringBuilder html = new StringBuilder();
        try (InputStream is = context.getResources().openRawResource(resId);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                html.append(line).append("\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error loading HTML from resource: " + resId, e);
        }
        return html.toString();
    }
    
    /**
     * 从Assets加载HTML内容
     */
    public String loadHtmlFromAssets(String fileName) {
        StringBuilder html = new StringBuilder();
        try (InputStream is = context.getAssets().open(fileName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                html.append(line).append("\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error loading HTML from assets: " + fileName, e);
        }
        return html.toString();
    }
    
    /**
     * 设置WebView配置
     */
    public void setConfig(String key, Object value) {
        webViewConfig.put(key, value);
    }
    
    /**
     * 获取WebView配置
     */
    public Object getConfig(String key) {
        return webViewConfig.get(key);
    }
    
    /**
     * 注册JavaScript接口
     */
    public void registerJavascriptInterface(String name, Object obj) {
        javascriptInterfaces.put(name, obj);
    }
    
    /**
     * 应用JavaScript接口到WebView
     */
    public void applyJavascriptInterfaces(WebView webView) {
        for (Map.Entry<String, Object> entry : javascriptInterfaces.entrySet()) {
            webView.addJavascriptInterface(entry.getValue(), entry.getKey());
        }
    }
    
    /**
     * 清除WebView缓存
     */
    public void clearCache(WebView webView) {
        webView.clearCache(true);
        webView.clearHistory();
        
        // 清除Cookie
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.flush();
    }
    
    /**
     * 获取默认User Agent
     */
    public String getDefaultUserAgent() {
        return System.getProperty("http.agent");
    }
    
    /**
     * 创建自定义User Agent
     */
    public String createCustomUserAgent(String appName, String appVersion) {
        return String.format("%s/%s (Android %s; %s)",
                appName,
                appVersion,
                Build.VERSION.RELEASE,
                getDefaultUserAgent());
    }
}
