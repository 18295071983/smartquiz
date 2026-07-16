package com.oilquiz.app.resource;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.Log;

import androidx.annotation.ColorRes;
import androidx.core.content.res.ResourcesCompat;

import com.oilquiz.app.R;

import java.util.HashMap;
import java.util.Map;

/**
 * 颜色资源提供者
 * 通过系统资源接口动态获取文本颜色
 */
public class ColorResourceProvider {
    
    private static final String TAG = "ColorResourceProvider";
    private static ColorResourceProvider instance;
    private Context context;
    private Resources resources;
    
    // 颜色缓存
    private Map<String, Integer> colorCache;
    
    // 颜色资源映射表
    private Map<String, Integer> colorResourceMap;
    
    private ColorResourceProvider(Context context) {
        this.context = context.getApplicationContext();
        this.resources = this.context.getResources();
        this.colorCache = new HashMap<>();
        this.colorResourceMap = new HashMap<>();
        initColorResources();
    }
    
    public static synchronized ColorResourceProvider getInstance(Context context) {
        if (instance == null) {
            instance = new ColorResourceProvider(context);
        }
        return instance;
    }
    
    /**
     * 初始化颜色资源映射
     */
    private void initColorResources() {
        // 主题色
        colorResourceMap.put("primary", R.color.primary);
        colorResourceMap.put("primary_dark", R.color.primary_dark);
        colorResourceMap.put("primary_light", R.color.primary_light);
        colorResourceMap.put("accent", R.color.colorAccent);
        
        // 背景色
        colorResourceMap.put("background", R.color.background_color);
        colorResourceMap.put("card_background", R.color.card_background);
        colorResourceMap.put("surface", R.color.white);
        
        // 文本颜色
        colorResourceMap.put("text_primary", R.color.text_primary);
        colorResourceMap.put("text_secondary", R.color.text_secondary);
        colorResourceMap.put("text_hint", R.color.text_hint);
        colorResourceMap.put("text_on_primary", R.color.white);
        
        // 状态颜色
        colorResourceMap.put("success", R.color.success_color);
        colorResourceMap.put("error", R.color.error_color);
        colorResourceMap.put("warning", R.color.warning_color);
        colorResourceMap.put("info", R.color.info_color);
        
        // 难度颜色
        colorResourceMap.put("easy", R.color.colorEasy);
        colorResourceMap.put("medium", R.color.colorMedium);
        colorResourceMap.put("hard", R.color.colorHard);
        colorResourceMap.put("correct", R.color.colorCorrect);
        
        // 灰度颜色
        colorResourceMap.put("gray_50", R.color.gray_50);
        colorResourceMap.put("gray_100", R.color.gray_100);
        colorResourceMap.put("gray_200", R.color.gray_200);
        colorResourceMap.put("gray_300", R.color.gray_300);
        colorResourceMap.put("gray_400", R.color.gray_400);
        colorResourceMap.put("gray_500", R.color.gray_500);
        colorResourceMap.put("gray_600", R.color.gray_600);
        colorResourceMap.put("gray_700", R.color.gray_700);
        colorResourceMap.put("gray_800", R.color.gray_800);
        colorResourceMap.put("gray_900", R.color.gray_900);
    }
    
    /**
     * 获取颜色资源
     * @param colorName 颜色名称
     * @return 颜色值
     */
    public int getColor(String colorName) {
        // 先从缓存获取
        if (colorCache.containsKey(colorName)) {
            return colorCache.get(colorName);
        }
        
        // 从资源映射获取
        Integer colorResId = colorResourceMap.get(colorName);
        if (colorResId != null) {
            int color = loadColorFromResource(colorResId);
            colorCache.put(colorName, color);
            return color;
        }
        
        // 尝试解析十六进制颜色
        try {
            if (colorName.startsWith("#")) {
                return Color.parseColor(colorName);
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid color format: " + colorName);
        }
        
        // 返回默认颜色
        Log.w(TAG, "Color not found: " + colorName + ", using default black");
        return Color.BLACK;
    }
    
    /**
     * 从资源加载颜色
     * @param colorResId 颜色资源ID
     * @return 颜色值
     */
    private int loadColorFromResource(@ColorRes int colorResId) {
        try {
            return ResourcesCompat.getColor(resources, colorResId, null);
        } catch (Exception e) {
            Log.e(TAG, "Error loading color resource: " + colorResId, e);
            // 返回一个基于资源ID的唯一颜色，确保不同的资源ID返回不同的颜色
            return Color.rgb(Math.abs(colorResId) % 256, Math.abs(colorResId / 256) % 256, Math.abs(colorResId / (256 * 256)) % 256);
        }
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
    
    /**
     * 注册自定义颜色资源
     * @param colorName 颜色名称
     * @param colorResId 颜色资源ID
     */
    public void registerColor(String colorName, @ColorRes int colorResId) {
        colorResourceMap.put(colorName, colorResId);
        // 清除缓存，下次获取时重新加载
        colorCache.remove(colorName);
    }
    
    /**
     * 注册自定义颜色值
     * @param colorName 颜色名称
     * @param colorValue 颜色值
     */
    public void registerColorValue(String colorName, int colorValue) {
        colorCache.put(colorName, colorValue);
    }
    
    /**
     * 获取主色调
     * @return 颜色值
     */
    public int getPrimaryColor() {
        return getColor("primary");
    }
    
    /**
     * 获取背景色
     * @return 颜色值
     */
    public int getBackgroundColor() {
        return getColor("background");
    }
    
    /**
     * 获取主要文本颜色
     * @return 颜色值
     */
    public int getTextPrimaryColor() {
        return getColor("text_primary");
    }
    
    /**
     * 获取次要文本颜色
     * @return 颜色值
     */
    public int getTextSecondaryColor() {
        return getColor("text_secondary");
    }
    
    /**
     * 获取成功状态颜色
     * @return 颜色值
     */
    public int getSuccessColor() {
        return getColor("success");
    }
    
    /**
     * 获取错误状态颜色
     * @return 颜色值
     */
    public int getErrorColor() {
        return getColor("error");
    }
    
    /**
     * 获取警告状态颜色
     * @return 颜色值
     */
    public int getWarningColor() {
        return getColor("warning");
    }
    
    /**
     * 获取信息状态颜色
     * @return 颜色值
     */
    public int getInfoColor() {
        return getColor("info");
    }
    
    /**
     * 获取难度颜色
     * @param difficulty 难度级别 (easy, medium, hard)
     * @return 颜色值
     */
    public int getDifficultyColor(String difficulty) {
        return getColor(difficulty.toLowerCase());
    }
    
    /**
     * 清除颜色缓存
     */
    public void clearCache() {
        colorCache.clear();
    }
    
    /**
     * 获取所有可用颜色名称
     * @return 颜色名称数组
     */
    public String[] getAvailableColors() {
        return colorResourceMap.keySet().toArray(new String[0]);
    }
    
    /**
     * 检查颜色是否可用
     * @param colorName 颜色名称
     * @return 是否可用
     */
    public boolean isColorAvailable(String colorName) {
        return colorResourceMap.containsKey(colorName) || colorCache.containsKey(colorName);
    }
    
    /**
     * 调整颜色透明度
     * @param color 原始颜色
     * @param alpha 透明度 (0-255)
     * @return 调整后的颜色
     */
    public int adjustAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
    
    /**
     * 混合两种颜色
     * @param color1 第一种颜色
     * @param color2 第二种颜色
     * @param ratio 混合比例 (0.0 - 1.0)
     * @return 混合后的颜色
     */
    public int blendColors(int color1, int color2, float ratio) {
        float inverseRatio = 1f - ratio;
        int r = (int) (Color.red(color1) * ratio + Color.red(color2) * inverseRatio);
        int g = (int) (Color.green(color1) * ratio + Color.green(color2) * inverseRatio);
        int b = (int) (Color.blue(color1) * ratio + Color.blue(color2) * inverseRatio);
        int a = (int) (Color.alpha(color1) * ratio + Color.alpha(color2) * inverseRatio);
        return Color.argb(a, r, g, b);
    }
}
