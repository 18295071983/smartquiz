package com.oilquiz.app.webview.security;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 安全 WebViewClient
 * 拦截危险 URL，处理非标准协议
 */
public class SecurityWebViewClient extends WebViewClient {
    private static final String TAG = "SecurityWebViewClient";

    // 危险协议列表
    private static final Set<String> DANGEROUS_SCHEMES = new java.util.HashSet<>();
    
    // 应用内部协议
    private static final Set<String> APP_SCHEMES = new java.util.HashSet<>();
    
    // 自定义协议处理器
    private CustomSchemeHandler schemeHandler;

    static {
        // 危险协议
        DANGEROUS_SCHEMES.add("javascript:");
        DANGEROUS_SCHEMES.add("data:");
        DANGEROUS_SCHEMES.add("intent:");
        
        // 应用内部协议（如果有的话）
        // APP_SCHEMES.add("oilquiz:");
    }

    public SecurityWebViewClient() {
    }

    /**
     * 设置自定义协议处理器
     */
    public void setCustomSchemeHandler(@Nullable CustomSchemeHandler handler) {
        this.schemeHandler = handler;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        String scheme = request.getUrl().getScheme();
        
        return handleUrlLoading(view, url, scheme);
    }

    // 兼容旧版 API
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        String scheme = null;
        try {
            Uri uri = Uri.parse(url);
            scheme = uri.getScheme();
        } catch (Exception e) {
            Log.w(TAG, "无法解析 URL scheme: " + url);
        }
        
        return handleUrlLoading(view, url, scheme);
    }

    /**
     * 处理 URL 加载
     */
    private boolean handleUrlLoading(WebView view, String url, String scheme) {
        if (url == null) {
            return true; // 阻止加载 null URL
        }

        // 1. 检查危险协议
        if (scheme != null && DANGEROUS_SCHEMES.contains(scheme.toLowerCase())) {
            Log.w(TAG, "拦截危险协议: " + url);
            
            if ("javascript:".equalsIgnoreCase(scheme)) {
                // 阻止 JavaScript 协议
                Log.w(TAG, "拒绝执行 JavaScript 协议");
                return true;
            }
            
            if ("data:".equalsIgnoreCase(scheme)) {
                // 阻止 data: 协议（防止 XSS）
                Log.w(TAG, "拒绝加载 data: 协议");
                return true;
            }
        }

        // 2. 检查应用内部协议
        if (scheme != null && APP_SCHEMES.contains(scheme.toLowerCase())) {
            Log.d(TAG, "处理应用内部协议: " + url);
            if (schemeHandler != null) {
                return schemeHandler.handleScheme(view, url);
            }
            return true;
        }

        // 3. 检查 Intent 协议
        if ("intent".equalsIgnoreCase(scheme)) {
            Log.d(TAG, "处理 Intent 协议: " + url);
            return handleIntentUrl(view, url);
        }

        // 4. 检查非 HTTP/HTTPS 协议
        if (scheme != null && !scheme.startsWith("http") && !scheme.equals("file")) {
            Log.w(TAG, "拦截非标准协议: " + scheme + " - " + url);
            
            // 尝试用浏览器打开
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                view.getContext().startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "无法打开 URL: " + url, e);
            }
            return true;
        }

        // 5. 标准 HTTP/HTTPS URL，让 WebView 正常处理
        return false;
    }

    /**
     * 处理 Intent URL
     */
    private boolean handleIntentUrl(WebView view, String url) {
        try {
            // 解析 intent:// URL
            // 格式: intent://host#Intent;scheme=xxx;package=xxx;end
            
            if (url.startsWith("intent://")) {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                
                // 检查包名
                String packageName = intent.getPackage();
                if (packageName != null) {
                    // 尝试启动应用
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        // 检查应用是否安装
                        view.getContext().getPackageManager().getPackageInfo(packageName, 0);
                        view.getContext().startActivity(intent);
                        return true;
                    } catch (Exception e) {
                        // 应用未安装，尝试获取 fallback URL
                        String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                        if (fallbackUrl != null) {
                            // 这里需要返回给调用者去加载 fallback URL
                            Log.d(TAG, "应用未安装，使用 fallback URL");
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "处理 Intent URL 失败: " + url, e);
        }
        
        return true; // 阻止加载
    }

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        Log.d(TAG, "页面开始加载: " + url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        Log.d(TAG, "页面加载完成: " + url);
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
        super.onReceivedError(view, request, error);
        
        // 只处理主框架错误
        if (request.isForMainFrame()) {
            Log.e(TAG, "页面加载错误: " + error.getDescription());
        }
    }

    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
        super.onReceivedHttpError(view, request, errorResponse);
        
        // 记录 HTTP 错误
        if (request.isForMainFrame()) {
            int statusCode = errorResponse.getStatusCode();
            Log.w(TAG, "HTTP 错误: " + statusCode + " - " + request.getUrl());
        }
    }

    /**
     * 自定义协议处理器接口
     */
    public interface CustomSchemeHandler {
        /**
         * 处理自定义协议
         * @return true 如果已处理，false 让 WebView 继续处理
         */
        boolean handleScheme(WebView view, String url);
    }
}
