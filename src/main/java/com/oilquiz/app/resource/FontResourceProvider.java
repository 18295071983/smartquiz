package com.oilquiz.app.resource;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;

import androidx.annotation.FontRes;
import androidx.core.content.res.ResourcesCompat;

import java.util.HashMap;
import java.util.Map;

/**
 * 字体资源提供者
 * 通过系统资源接口动态获取字体样式
 */
public class FontResourceProvider {
    
    private static final String TAG = "FontResourceProvider";
    private static FontResourceProvider instance;
    private Context context;
    
    // 字体缓存
    private Map<String, Typeface> fontCache;
    
    // 字体资源映射表
    private Map<String, Integer> fontResourceMap;
    
    private FontResourceProvider(Context context) {
        this.context = context.getApplicationContext();
        this.fontCache = new HashMap<>();
        this.fontResourceMap = new HashMap<>();
        initFontResources();
    }
    
    public static synchronized FontResourceProvider getInstance(Context context) {
        if (instance == null) {
            instance = new FontResourceProvider(context);
        }
        return instance;
    }
    
    /**
     * 初始化字体资源映射
     * 使用系统默认字体，不依赖自定义字体文件
     */
    private void initFontResources() {
        // 系统默认字体 - 使用Android系统内置字体
        // 由于Android R.font资源不可用，我们使用Typeface常量
        fontResourceMap.put("default", 0);
        fontResourceMap.put("sans_serif", 0);
        fontResourceMap.put("serif", 0);
        fontResourceMap.put("monospace", 0);
        
        // 中文字体 - 使用系统默认字体
        fontResourceMap.put("chinese_default", 0);
        fontResourceMap.put("chinese_bold", 0);
        fontResourceMap.put("chinese_light", 0);
        
        // 标题字体 - 使用系统默认字体
        fontResourceMap.put("title", 0);
        fontResourceMap.put("title_bold", 0);
        
        // 正文字体 - 使用系统默认字体
        fontResourceMap.put("body", 0);
        fontResourceMap.put("body_medium", 0);
        
        // 代码字体 - 使用等宽字体
        fontResourceMap.put("code", 0);
        fontResourceMap.put("code_medium", 0);
    }
    
    /**
     * 获取字体资源
     * @param fontName 字体名称
     * @return Typeface对象
     */
    public Typeface getFont(String fontName) {
        // 先从缓存获取
        if (fontCache.containsKey(fontName)) {
            return fontCache.get(fontName);
        }
        
        // 根据字体名称返回对应的Typeface
        Typeface typeface = getTypefaceByName(fontName);
        if (typeface != null) {
            fontCache.put(fontName, typeface);
            return typeface;
        }
        
        // 返回默认字体
        Log.w(TAG, "Font not found: " + fontName + ", using default");
        return Typeface.DEFAULT;
    }
    
    /**
     * 根据字体名称获取Typeface
     */
    private Typeface getTypefaceByName(String fontName) {
        switch (fontName) {
            case "monospace":
            case "code":
            case "code_medium":
                return Typeface.MONOSPACE;
            case "serif":
                return Typeface.SERIF;
            case "title":
            case "title_bold":
            case "chinese_bold":
                return Typeface.DEFAULT_BOLD;
            default:
                return Typeface.DEFAULT;
        }
    }
    
    /**
     * 从资源加载字体
     * @param fontResId 字体资源ID
     * @return Typeface对象
     */
    private Typeface loadFontFromResource(@FontRes int fontResId) {
        try {
            if (fontResId == 0) {
                return Typeface.DEFAULT;
            }
            return ResourcesCompat.getFont(context, fontResId);
        } catch (Exception e) {
            Log.e(TAG, "Error loading font resource: " + fontResId, e);
            return Typeface.DEFAULT;
        }
    }
    
    /**
     * 注册自定义字体资源
     * @param fontName 字体名称
     * @param fontResId 字体资源ID
     */
    public void registerFont(String fontName, @FontRes int fontResId) {
        fontResourceMap.put(fontName, fontResId);
        // 清除缓存，下次获取时重新加载
        fontCache.remove(fontName);
    }
    
    /**
     * 获取系统默认字体
     * @return Typeface对象
     */
    public Typeface getDefaultFont() {
        return Typeface.DEFAULT;
    }
    
    /**
     * 获取标题字体
     * @return Typeface对象
     */
    public Typeface getTitleFont() {
        return Typeface.DEFAULT_BOLD;
    }
    
    /**
     * 获取正文字体
     * @return Typeface对象
     */
    public Typeface getBodyFont() {
        return Typeface.DEFAULT;
    }
    
    /**
     * 获取中文字体
     * @return Typeface对象
     */
    public Typeface getChineseFont() {
        return Typeface.DEFAULT;
    }
    
    /**
     * 获取代码字体
     * @return Typeface对象
     */
    public Typeface getCodeFont() {
        return Typeface.MONOSPACE;
    }
    
    /**
     * 清除字体缓存
     */
    public void clearCache() {
        fontCache.clear();
    }
    
    /**
     * 获取所有可用字体名称
     * @return 字体名称数组
     */
    public String[] getAvailableFonts() {
        return fontResourceMap.keySet().toArray(new String[0]);
    }
    
    /**
     * 检查字体是否可用
     * @param fontName 字体名称
     * @return 是否可用
     */
    public boolean isFontAvailable(String fontName) {
        return fontResourceMap.containsKey(fontName);
    }
}
