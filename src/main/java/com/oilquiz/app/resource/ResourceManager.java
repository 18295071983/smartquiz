package com.oilquiz.app.resource;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.util.Log;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.FontRes;
import androidx.annotation.RawRes;
import androidx.annotation.StringRes;
import androidx.core.content.res.ResourcesCompat;

import com.oilquiz.app.R;

import java.util.HashMap;
import java.util.Map;

/**
 * 系统资源管理器
 * 统一管理字体样式、文本颜色、提示音效、服务框架配置、应用权限管理、WebView组件、文件渲染引擎及文件导入导出功能的资源调用
 */
public class ResourceManager {
    
    private static final String TAG = "ResourceManager";
    private static ResourceManager instance;
    private Context context;
    private Resources resources;
    
    // 音效资源缓存
    private SoundPool soundPool;
    private Map<Integer, Integer> soundMap;
    
    // 字体资源缓存
    private Map<String, Typeface> fontCache;
    
    // 资源监听器
    private ResourceChangeListener resourceChangeListener;
    
    public interface ResourceChangeListener {
        void onResourceChanged(ResourceType type, int resourceId);
    }
    
    public enum ResourceType {
        FONT,
        COLOR,
        SOUND,
        CONFIG,
        PERMISSION,
        WEBVIEW,
        RENDER_ENGINE,
        IMPORT,
        EXPORT
    }
    
    private ResourceManager(Context context) {
        this.context = context.getApplicationContext();
        this.resources = this.context.getResources();
        this.soundMap = new HashMap<>();
        this.fontCache = new HashMap<>();
        initSoundPool();
    }
    
    public static synchronized ResourceManager getInstance(Context context) {
        if (instance == null) {
            instance = new ResourceManager(context);
        }
        return instance;
    }
    
    /**
     * 初始化音效池
     */
    private void initSoundPool() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(5)
                    .setAudioAttributes(audioAttributes)
                    .build();
        } else {
            soundPool = new SoundPool(5, android.media.AudioManager.STREAM_NOTIFICATION, 0);
        }
    }
    
    /**
     * 设置资源变更监听器
     */
    public void setResourceChangeListener(ResourceChangeListener listener) {
        this.resourceChangeListener = listener;
    }
    
    /**
     * 通知资源变更
     */
    private void notifyResourceChanged(ResourceType type, int resourceId) {
        if (resourceChangeListener != null) {
            resourceChangeListener.onResourceChanged(type, resourceId);
        }
    }
    
    // ==================== 字体资源管理 ====================
    
    /**
     * 获取系统字体
     * @param fontResId 字体资源ID
     * @return Typeface对象
     */
    public Typeface getFont(@FontRes int fontResId) {
        try {
            return ResourcesCompat.getFont(context, fontResId);
        } catch (Exception e) {
            Log.e(TAG, "Error loading font: " + fontResId, e);
            return Typeface.DEFAULT;
        }
    }
    
    /**
     * 获取缓存字体
     * @param fontName 字体名称
     * @param fontResId 字体资源ID
     * @return Typeface对象
     */
    public Typeface getCachedFont(String fontName, @FontRes int fontResId) {
        if (fontCache.containsKey(fontName)) {
            return fontCache.get(fontName);
        }
        Typeface typeface = getFont(fontResId);
        if (typeface != null) {
            fontCache.put(fontName, typeface);
        }
        return typeface;
    }
    
    /**
     * 清除字体缓存
     */
    public void clearFontCache() {
        fontCache.clear();
    }
    
    // ==================== 颜色资源管理 ====================
    
    /**
     * 获取颜色资源
     * @param colorResId 颜色资源ID
     * @return 颜色值
     */
    public int getColor(@ColorRes int colorResId) {
        return ResourcesCompat.getColor(resources, colorResId, null);
    }
    
    /**
     * 获取主题颜色
     * @param attrId 主题属性ID
     * @return 颜色值
     */
    public int getThemeColor(int attrId) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        context.getTheme().resolveAttribute(attrId, typedValue, true);
        return typedValue.data;
    }
    
    // ==================== 音效资源管理 ====================
    
    /**
     * 加载音效资源
     * @param soundResId 音效资源ID
     * @return 音效ID
     */
    public int loadSound(@RawRes int soundResId) {
        if (soundMap.containsKey(soundResId)) {
            return soundMap.get(soundResId);
        }
        int soundId = soundPool.load(context, soundResId, 1);
        soundMap.put(soundResId, soundId);
        return soundId;
    }
    
    /**
     * 播放音效
     * @param soundResId 音效资源ID
     */
    public void playSound(@RawRes int soundResId) {
        int soundId = loadSound(soundResId);
        soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f);
    }
    
    /**
     * 释放音效资源
     */
    public void releaseSounds() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        soundMap.clear();
    }
    
    // ==================== Drawable资源管理 ====================
    
    /**
     * 获取Drawable资源
     * @param drawableResId Drawable资源ID
     * @return Drawable对象
     */
    public Drawable getDrawable(@DrawableRes int drawableResId) {
        return ResourcesCompat.getDrawable(resources, drawableResId, null);
    }
    
    // ==================== 字符串资源管理 ====================
    
    /**
     * 获取字符串资源
     * @param stringResId 字符串资源ID
     * @return 字符串
     */
    public String getString(@StringRes int stringResId) {
        return resources.getString(stringResId);
    }
    
    /**
     * 获取格式化字符串
     * @param stringResId 字符串资源ID
     * @param formatArgs 格式化参数
     * @return 格式化后的字符串
     */
    public String getString(@StringRes int stringResId, Object... formatArgs) {
        return resources.getString(stringResId, formatArgs);
    }
    
    /**
     * 释放所有资源
     */
    public void release() {
        releaseSounds();
        clearFontCache();
        instance = null;
    }
}
