package com.oilquiz.app.webview.security;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * WebView 安全配置工具类
 */
public class WebViewSecurityConfig {
    private static final String TAG = "WebViewSecurityConfig";

    /**
     * 配置 WebView 安全设置
     */
    public static void configure(WebSettings settings) {
        Log.d(TAG, "配置 WebView 安全设置");
        
        // ==================== JavaScript 配置 ====================
        
        // 禁止自动打开窗口（防止弹窗）
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        
        // 启用 JavaScript（如果需要的话）
        settings.setJavaScriptEnabled(true);
        
        // ==================== 文件访问配置 ====================
        
        // 禁止从 file:// URL 访问其他文件（防止跨域攻击）
        settings.setAllowFileAccessFromFileURLs(false);
        
        // 禁止从 file:// URL 访问任意来源（防止 CSS/JS 攻击）
        settings.setAllowUniversalAccessFromFileURLs(false);
        
        // 允许 file:// 访问同源文件（保留必要功能）
        settings.setAllowFileAccess(true);
        
        // ==================== 混合内容配置 ====================
        
        // 永不允许混合内容（HTTPS 页面中的 HTTP 资源）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        } else {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        
        // ==================== 位置服务配置 ====================
        
        // 禁用地理位置
        settings.setGeolocationEnabled(false);
        
        // ==================== 密码和数据保存配置 ====================
        
        // 不保存密码
        settings.setSavePassword(false);
        
        // 不保存表单数据
        settings.setSaveFormData(false);
        
        // ==================== 数据库和存储配置 ====================
        
        // 禁用 WebSQL（已废弃，存在安全风险）
        settings.setDatabaseEnabled(false);
        
        // DOM 存储（LocalStorage）- 根据需要开启
        settings.setDomStorageEnabled(true);
        
        // ==================== 缩放配置 ====================
        
        // 允许缩放（用户可控）
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        
        // 不显示缩放控件
        settings.setDisplayZoomControls(false);
        
        // ==================== 缓存配置 ====================
        
        // 根据cacheMode配置决定
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        
        // 不使用缓存目录存储数据库
        settings.setDatabasePath(null);
        
        // ==================== 安全浏览（Android O+）====================
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                settings.setSafeBrowsingEnabled(true);
            } catch (Exception e) {
                Log.w(TAG, "无法启用安全浏览: " + e.getMessage());
            }
        }
        
        // ==================== 其他安全设置 ====================
        
        // 禁止访问设备媒体（照片、摄像头等）- 需要明确授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMediaPlaybackRequiresUserGesture(true);
        }
        
        // 禁用远程调试（Release 版本）
        // 注意：在 Application 类中通过 BuildConfig.DEBUG 控制
        
        Log.d(TAG, "WebView 安全配置完成");
    }

    /**
     * 获取推荐的安全 ContentMode
     */
    public static int getRecommendedContentMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return WebSettings.LOAD_NORMAL;
        }
        return WebSettings.LOAD_DEFAULT;
    }

    /**
     * 获取推荐的缓存模式
     */
    public static int getRecommendedCacheMode(boolean offlineFirst) {
        if (offlineFirst) {
            return WebSettings.LOAD_CACHE_ELSE_NETWORK;
        }
        return WebSettings.LOAD_DEFAULT;
    }
}
