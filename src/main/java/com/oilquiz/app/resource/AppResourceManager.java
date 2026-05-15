package com.oilquiz.app.resource;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.FontRes;
import androidx.annotation.RawRes;
import androidx.annotation.StringRes;

/**
 * 应用资源管理器统一入口
 * 提供对所有资源提供者的统一访问接口
 */
public class AppResourceManager {

    private static AppResourceManager instance;
    private Context context;

    // 各资源提供者实例
    private FontResourceProvider fontProvider;
    private ColorResourceProvider colorProvider;
    private SoundResourceProvider soundProvider;
    private ConfigResourceProvider configProvider;
    private PermissionResourceProvider permissionProvider;
    private WebViewResourceProvider webViewProvider;
    private FileResourceProvider fileProvider;

    private AppResourceManager(Context context) {
        this.context = context.getApplicationContext();
        initProviders();
    }

    public static synchronized AppResourceManager getInstance(Context context) {
        if (instance == null) {
            instance = new AppResourceManager(context);
        }
        return instance;
    }

    /**
     * 初始化所有资源提供者
     */
    private void initProviders() {
        fontProvider = FontResourceProvider.getInstance(context);
        colorProvider = ColorResourceProvider.getInstance(context);
        soundProvider = SoundResourceProvider.getInstance(context);
        configProvider = ConfigResourceProvider.getInstance(context);
        permissionProvider = PermissionResourceProvider.getInstance(context);
        webViewProvider = WebViewResourceProvider.getInstance(context);
        fileProvider = FileResourceProvider.getInstance(context);
    }

    // ==================== 字体资源访问 ====================

    public FontResourceProvider fonts() {
        return fontProvider;
    }

    public Typeface getFont(String fontName) {
        return fontProvider.getFont(fontName);
    }

    public Typeface getDefaultFont() {
        return fontProvider.getDefaultFont();
    }

    // ==================== 颜色资源访问 ====================

    public ColorResourceProvider colors() {
        return colorProvider;
    }

    public int getColor(String colorName) {
        return colorProvider.getColor(colorName);
    }

    public int getPrimaryColor() {
        return colorProvider.getPrimaryColor();
    }

    // ==================== 音效资源访问 ====================

    public SoundResourceProvider sounds() {
        return soundProvider;
    }

    public void playSound(String soundName) {
        soundProvider.playSound(soundName);
    }

    public void playSuccess() {
        soundProvider.playSuccess();
    }

    public void playError() {
        soundProvider.playError();
    }

    // ==================== 配置资源访问 ====================

    public ConfigResourceProvider config() {
        return configProvider;
    }

    public String getConfigString(String key, String defaultValue) {
        return configProvider.getString(key, defaultValue);
    }

    public int getConfigInt(String key, int defaultValue) {
        return configProvider.getInt(key, defaultValue);
    }

    public boolean getConfigBoolean(String key, boolean defaultValue) {
        return configProvider.getBoolean(key, defaultValue);
    }

    // ==================== 权限资源访问 ====================

    public PermissionResourceProvider permissions() {
        return permissionProvider;
    }

    public boolean hasStoragePermission() {
        return permissionProvider.hasStoragePermission();
    }

    public boolean hasCameraPermission() {
        return permissionProvider.hasCameraPermission();
    }

    public boolean hasLocationPermission() {
        return permissionProvider.hasLocationPermission();
    }

    public boolean hasMicrophonePermission() {
        return permissionProvider.hasMicrophonePermission();
    }

    public void requestMicrophonePermission(android.app.Activity activity) {
        permissionProvider.requestMicrophonePermission(activity);
    }

    public void requestMicrophonePermission(android.app.Activity activity, 
            PermissionResourceProvider.PermissionCallback callback) {
        permissionProvider.requestMicrophonePermission(activity, callback);
    }

    public boolean hasMediaPermission() {
        return permissionProvider.hasMediaPermission();
    }

    public boolean hasNotificationPermission() {
        return permissionProvider.hasNotificationPermission();
    }

    public void requestNotificationPermission(android.app.Activity activity) {
        permissionProvider.requestNotificationPermission(activity);
    }

    public void requestNotificationPermission(android.app.Activity activity, 
            PermissionResourceProvider.PermissionCallback callback) {
        permissionProvider.requestNotificationPermission(activity, callback);
    }

    // ==================== WebView资源访问 ====================

    public WebViewResourceProvider webView() {
        return webViewProvider;
    }

    // ==================== 文件资源访问 ====================

    public FileResourceProvider files() {
        return fileProvider;
    }

    // ==================== 全局方法 ====================

    /**
     * 预加载所有资源
     */
    public void preloadAllResources() {
        // 预加载音效
        soundProvider.preloadAllSounds();
    }

    /**
     * 释放所有资源
     */
    public void releaseAll() {
        soundProvider.release();
        fontProvider.clearCache();
        colorProvider.clearCache();
        instance = null;
    }

    /**
     * 清除所有缓存
     */
    public void clearAllCaches() {
        fontProvider.clearCache();
        colorProvider.clearCache();
        fileProvider.clearTempFiles();
    }
}
