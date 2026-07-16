package com.oilquiz.app.resource;

import android.content.Context;
import android.graphics.Color;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

/**
 * 颜色资源提供者单元测试
 */
@RunWith(RobolectricTestRunner.class)
public class ColorResourceProviderTest {

    private Context context;
    private ColorResourceProvider colorProvider;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        colorProvider = ColorResourceProvider.getInstance(context);
    }

    @Test
    public void testGetInstance() {
        assertNotNull("ColorResourceProvider instance should not be null", colorProvider);
    }

    @Test
    public void testGetPrimaryColor() {
        int primaryColor = colorProvider.getPrimaryColor();
        assertNotEquals("Primary color should not be 0", 0, primaryColor);
    }

    @Test
    public void testGetBackgroundColor() {
        int backgroundColor = colorProvider.getBackgroundColor();
        assertNotEquals("Background color should not be 0", 0, backgroundColor);
    }

    @Test
    public void testGetTextPrimaryColor() {
        int textPrimaryColor = colorProvider.getTextPrimaryColor();
        assertNotEquals("Text primary color should not be 0", 0, textPrimaryColor);
    }

    @Test
    public void testGetTextSecondaryColor() {
        int textSecondaryColor = colorProvider.getTextSecondaryColor();
        assertNotEquals("Text secondary color should not be 0", 0, textSecondaryColor);
    }

    @Test
    public void testGetSuccessColor() {
        int successColor = colorProvider.getSuccessColor();
        assertNotEquals("Success color should not be 0", 0, successColor);
    }

    @Test
    public void testGetErrorColor() {
        int errorColor = colorProvider.getErrorColor();
        assertNotEquals("Error color should not be 0", 0, errorColor);
    }

    @Test
    public void testGetWarningColor() {
        int warningColor = colorProvider.getWarningColor();
        assertNotEquals("Warning color should not be 0", 0, warningColor);
    }

    @Test
    public void testGetInfoColor() {
        int infoColor = colorProvider.getInfoColor();
        assertNotEquals("Info color should not be 0", 0, infoColor);
    }

    @Test
    public void testGetDifficultyColor() {
        // 测试不同难度的颜色
        int easyColor = colorProvider.getDifficultyColor("easy");
        int mediumColor = colorProvider.getDifficultyColor("medium");
        int hardColor = colorProvider.getDifficultyColor("hard");
        
        assertNotEquals("Easy color should not be 0", 0, easyColor);
        assertNotEquals("Medium color should not be 0", 0, mediumColor);
        assertNotEquals("Hard color should not be 0", 0, hardColor);
    }

    @Test
    public void testGetColor() {
        // 测试获取存在的颜色
        int primaryColor = colorProvider.getColor("primary");
        assertNotEquals("Primary color should not be 0", 0, primaryColor);

        // 测试获取不存在的颜色（应该返回默认黑色）
        int defaultColor = colorProvider.getColor("non_existent_color");
        assertEquals("Default color should be black", Color.BLACK, defaultColor);

        // 测试十六进制颜色
        int hexColor = colorProvider.getColor("#FF0000");
        assertEquals("Hex color should be red", Color.RED, hexColor);
    }

    @Test
    public void testIsColorAvailable() {
        // 测试存在的颜色
        assertTrue("Primary color should be available", colorProvider.isColorAvailable("primary"));
        assertTrue("Success color should be available", colorProvider.isColorAvailable("success"));

        // 测试不存在的颜色
        assertFalse("Non-existent color should not be available", colorProvider.isColorAvailable("non_existent_color"));
    }

    @Test
    public void testGetAvailableColors() {
        String[] availableColors = colorProvider.getAvailableColors();
        assertNotNull("Available colors array should not be null", availableColors);
        assertTrue("Available colors array should not be empty", availableColors.length > 0);
        
        // 检查常用颜色是否在列表中
        boolean containsPrimary = false;
        boolean containsSuccess = false;
        for (String color : availableColors) {
            if ("primary".equals(color)) containsPrimary = true;
            if ("success".equals(color)) containsSuccess = true;
        }
        assertTrue("Available colors should contain 'primary'", containsPrimary);
        assertTrue("Available colors should contain 'success'", containsSuccess);
    }

    @Test
    public void testAdjustAlpha() {
        int color = Color.RED;
        int semiTransparent = colorProvider.adjustAlpha(color, 128);
        
        // 检查透明度是否正确
        int alpha = Color.alpha(semiTransparent);
        assertEquals("Alpha should be 128", 128, alpha);
        
        // 检查RGB值是否保持不变
        int red = Color.red(semiTransparent);
        int green = Color.green(semiTransparent);
        int blue = Color.blue(semiTransparent);
        assertEquals("Red should be 255", 255, red);
        assertEquals("Green should be 0", 0, green);
        assertEquals("Blue should be 0", 0, blue);
    }

    @Test
    public void testBlendColors() {
        int color1 = Color.RED;
        int color2 = Color.BLUE;
        
        // 测试50%混合
        int blended = colorProvider.blendColors(color1, color2, 0.5f);
        
        // 检查混合结果
        int red = Color.red(blended);
        int green = Color.green(blended);
        int blue = Color.blue(blended);
        
        // 红色和蓝色混合应该接近紫色
        assertTrue("Red should be between 120-140", red >= 120 && red <= 140);
        assertTrue("Green should be low", green <= 50);
        assertTrue("Blue should be between 120-140", blue >= 120 && blue <= 140);
    }

    @Test
    public void testClearCache() {
        // 先获取颜色以填充缓存
        colorProvider.getColor("primary");
        colorProvider.getColor("success");
        
        // 清除缓存
        colorProvider.clearCache();
        
        // 再次获取颜色应该重新加载
        int primaryColor = colorProvider.getColor("primary");
        assertNotEquals("Primary color should be available after clearing cache", 0, primaryColor);
    }
}
