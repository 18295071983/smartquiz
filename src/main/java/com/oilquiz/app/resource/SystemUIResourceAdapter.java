package com.oilquiz.app.resource;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.oilquiz.app.R;
import com.oilquiz.app.manager.ThemeColorManager;

/**
 * 系统UI资源适配器
 * 自适应调用系统UI框架，根据系统主题动态获取颜色、字体等资源
 */
public class SystemUIResourceAdapter {

    private static final String TAG = "SystemUIResourceAdapter";
    private static SystemUIResourceAdapter instance;
    private Context context;
    private Resources resources;
    private Resources.Theme theme;

    // 系统主题属性缓存
    private int primaryColor;
    private int primaryDarkColor;
    private int accentColor;
    private int backgroundColor;
    private int surfaceColor;
    private int textPrimaryColor;
    private int textSecondaryColor;
    private int errorColor;
    private int successColor;
    private int warningColor;

    private SystemUIResourceAdapter(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.resources = this.context.getResources();
        this.theme = this.context.getTheme();
        initSystemColors();
    }

    public static synchronized SystemUIResourceAdapter getInstance(@NonNull Context context) {
        // 每次获取实例时都创建新实例，确保使用最新的主题
        instance = new SystemUIResourceAdapter(context);
        return instance;
    }

    /**
     * 初始化系统颜色
     */
    private void initSystemColors() {
        // 使用ThemeColorManager获取当前选择的主题颜色
        ThemeColorManager themeColorManager = new ThemeColorManager();
        int themeColor = themeColorManager.getCurrentThemeColorValue(context);
        
        // 根据主题颜色计算深色版本
        primaryColor = themeColor;
        primaryDarkColor = darkenColor(themeColor, 0.2f);
        accentColor = themeColor;
        
        // 获取背景颜色
        backgroundColor = getThemeColor(android.R.attr.colorBackground);
        
        // 获取表面颜色（Material Design）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            surfaceColor = getThemeColor(android.R.attr.colorBackground);
        } else {
            surfaceColor = backgroundColor;
        }
        
        // 获取文本颜色
        textPrimaryColor = getThemeColor(android.R.attr.textColorPrimary);
        textSecondaryColor = getThemeColor(android.R.attr.textColorSecondary);
        
        // 获取状态颜色
        errorColor = Color.parseColor("#B00020");
        successColor = Color.parseColor("#4CAF50");
        warningColor = Color.parseColor("#FF9800");
    }
    
    /**
     * 使颜色变暗
     */
    private int darkenColor(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= (1.0f - factor);
        return Color.HSVToColor(hsv);
    }

    /**
     * 从主题属性获取颜色
     */
    @ColorInt
    public int getThemeColor(int attrId) {
        TypedValue typedValue = new TypedValue();
        if (theme.resolveAttribute(attrId, typedValue, true)) {
            return typedValue.data;
        }
        return Color.BLACK;
    }

    /**
     * 从自定义属性获取颜色，如果不存在则返回默认值
     */
    @ColorInt
    public int getColorFromAttr(int attrId, @ColorInt int defaultColor) {
        TypedValue typedValue = new TypedValue();
        if (theme.resolveAttribute(attrId, typedValue, true)) {
            return typedValue.data;
        }
        return defaultColor;
    }

    // ==================== 颜色获取方法 ====================

    @ColorInt
    public int getPrimaryColor() {
        return primaryColor;
    }

    @ColorInt
    public int getPrimaryDarkColor() {
        return primaryDarkColor;
    }

    @ColorInt
    public int getAccentColor() {
        return accentColor;
    }

    @ColorInt
    public int getBackgroundColor() {
        return backgroundColor;
    }

    @ColorInt
    public int getSurfaceColor() {
        return surfaceColor;
    }

    @ColorInt
    public int getTextPrimaryColor() {
        return textPrimaryColor;
    }

    @ColorInt
    public int getTextSecondaryColor() {
        return textSecondaryColor;
    }

    @ColorInt
    public int getErrorColor() {
        return errorColor;
    }

    @ColorInt
    public int getSuccessColor() {
        return successColor;
    }

    @ColorInt
    public int getWarningColor() {
        return warningColor;
    }

    // ==================== 动态主题应用 ====================

    /**
     * 应用系统主题到Activity
     */
    public void applySystemTheme(@NonNull AppCompatActivity activity) {
        // 设置状态栏颜色
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.getWindow().setStatusBarColor(primaryDarkColor);
        }
        
        // 设置导航栏颜色
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.getWindow().setNavigationBarColor(backgroundColor);
        }
    }

    /**
     * 应用主色调到View
     */
    public void applyPrimaryColor(@NonNull View view) {
        view.setBackgroundColor(primaryColor);
    }

    /**
     * 应用强调色到View
     */
    public void applyAccentColor(@NonNull View view) {
        view.setBackgroundColor(accentColor);
    }

    /**
     * 应用文本颜色到TextView
     */
    public void applyTextColor(@NonNull TextView textView, boolean isPrimary) {
        textView.setTextColor(isPrimary ? textPrimaryColor : textSecondaryColor);
    }

    /**
     * 获取带主题色的Drawable
     */
    public Drawable getThemedDrawable(int drawableResId, @ColorInt int tintColor) {
        Drawable drawable = ContextCompat.getDrawable(context, drawableResId);
        if (drawable != null) {
            drawable = DrawableCompat.wrap(drawable);
            DrawableCompat.setTint(drawable, tintColor);
        }
        return drawable;
    }

    // ==================== 自适应UI方法 ====================

    /**
     * 根据系统主题判断是否为深色模式
     */
    public boolean isDarkTheme() {
        int nightMode = resources.getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 获取自适应背景颜色（根据深色/浅色模式）
     */
    @ColorInt
    public int getAdaptiveBackgroundColor() {
        return isDarkTheme() ? Color.parseColor("#121212") : Color.WHITE;
    }

    /**
     * 获取自适应表面颜色
     */
    @ColorInt
    public int getAdaptiveSurfaceColor() {
        return isDarkTheme() ? Color.parseColor("#1E1E1E") : Color.WHITE;
    }

    /**
     * 获取自适应文本颜色
     */
    @ColorInt
    public int getAdaptiveTextColor(boolean isPrimary) {
        if (isDarkTheme()) {
            return isPrimary ? Color.WHITE : Color.parseColor("#B3B3B3");
        } else {
            return isPrimary ? Color.BLACK : Color.parseColor("#666666");
        }
    }

    /**
     * 获取自适应卡片背景色
     */
    @ColorInt
    public int getAdaptiveCardBackgroundColor() {
        return isDarkTheme() ? Color.parseColor("#2C2C2C") : Color.WHITE;
    }

    // ==================== 系统资源获取 ====================

    /**
     * 获取系统默认分隔线颜色
     */
    @ColorInt
    public int getDividerColor() {
        return isDarkTheme() ? Color.parseColor("#1FFFFFFF") : Color.parseColor("#1F000000");
    }

    /**
     * 获取系统默认禁用颜色
     */
    @ColorInt
    public int getDisabledColor() {
        return isDarkTheme() ? Color.parseColor("#4DFFFFFF") : Color.parseColor("#4D000000");
    }

    /**
     * 获取系统默认波纹颜色
     */
    @ColorInt
    public int getRippleColor() {
        return isDarkTheme() ? Color.parseColor("#33FFFFFF") : Color.parseColor("#1F000000");
    }

    /**
     * 刷新系统颜色（主题变更时调用）
     */
    public void refreshSystemColors() {
        initSystemColors();
    }

    /**
     * 释放资源
     */
    public void release() {
        instance = null;
    }
}
