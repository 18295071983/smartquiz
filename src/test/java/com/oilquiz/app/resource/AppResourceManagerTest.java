package com.oilquiz.app.resource;

import android.content.Context;
import android.graphics.Typeface;

import androidx.test.core.app.ApplicationProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

/**
 * 应用资源管理器统一入口单元测试
 */
@RunWith(RobolectricTestRunner.class)
public class AppResourceManagerTest {

    private Context context;
    private AppResourceManager appResourceManager;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        appResourceManager = AppResourceManager.getInstance(context);
    }

    @Test
    public void testGetInstance() {
        assertNotNull("AppResourceManager instance should not be null", appResourceManager);
    }

    @Test
    public void testFonts() {
        FontResourceProvider fontProvider = appResourceManager.fonts();
        assertNotNull("Font provider should not be null", fontProvider);
    }

    @Test
    public void testColors() {
        ColorResourceProvider colorProvider = appResourceManager.colors();
        assertNotNull("Color provider should not be null", colorProvider);
    }

    @Test
    public void testSounds() {
        SoundResourceProvider soundProvider = appResourceManager.sounds();
        assertNotNull("Sound provider should not be null", soundProvider);
    }

    @Test
    public void testConfig() {
        ConfigResourceProvider configProvider = appResourceManager.config();
        assertNotNull("Config provider should not be null", configProvider);
    }

    @Test
    public void testPermissions() {
        PermissionResourceProvider permissionProvider = appResourceManager.permissions();
        assertNotNull("Permission provider should not be null", permissionProvider);
    }

    @Test
    public void testWebView() {
        WebViewResourceProvider webViewProvider = appResourceManager.webView();
        assertNotNull("WebView provider should not be null", webViewProvider);
    }

    @Test
    public void testFiles() {
        FileResourceProvider fileProvider = appResourceManager.files();
        assertNotNull("File provider should not be null", fileProvider);
    }

    @Test
    public void testGetFont() {
        Typeface font = appResourceManager.getFont("title");
        assertNotNull("Font should not be null", font);
    }

    @Test
    public void testGetDefaultFont() {
        Typeface defaultFont = appResourceManager.getDefaultFont();
        assertNotNull("Default font should not be null", defaultFont);
    }

    @Test
    public void testGetColor() {
        int color = appResourceManager.getColor("primary");
        assertNotEquals("Color should not be 0", 0, color);
    }

    @Test
    public void testGetPrimaryColor() {
        int primaryColor = appResourceManager.getPrimaryColor();
        assertNotEquals("Primary color should not be 0", 0, primaryColor);
    }

    @Test
    public void testGetConfigString() {
        String appName = appResourceManager.getConfigString("app_name", "DefaultApp");
        assertNotNull("App name should not be null", appName);
    }

    @Test
    public void testGetConfigInt() {
        int questionCount = appResourceManager.getConfigInt("default_question_count", 5);
        assertTrue("Question count should be positive", questionCount > 0);
    }

    @Test
    public void testGetConfigBoolean() {
        boolean soundEnabled = appResourceManager.getConfigBoolean("enable_sound_effects", false);
        // 不做具体值的断言，因为默认值可能不同
    }

    @Test
    public void testHasStoragePermission() {
        // 这个测试只是验证方法能正常调用
        boolean hasPermission = appResourceManager.hasStoragePermission();
        // 不做具体值的断言，因为权限状态可能不同
    }

    @Test
    public void testHasCameraPermission() {
        // 这个测试只是验证方法能正常调用
        boolean hasPermission = appResourceManager.hasCameraPermission();
        // 不做具体值的断言，因为权限状态可能不同
    }

    @Test
    public void testPreloadAllResources() {
        // 这个测试只是验证方法能正常调用
        appResourceManager.preloadAllResources();
    }

    @Test
    public void testClearAllCaches() {
        // 这个测试只是验证方法能正常调用
        appResourceManager.clearAllCaches();
    }

    @Test
    public void testReleaseAll() {
        // 这个测试只是验证方法能正常调用
        appResourceManager.releaseAll();
    }

    @Test
    public void testResourceAccessAfterRelease() {
        // 释放所有资源
        appResourceManager.releaseAll();
        
        // 再次获取实例
        AppResourceManager newInstance = AppResourceManager.getInstance(context);
        assertNotNull("New instance should be created after release", newInstance);
        
        // 测试资源访问
        Typeface font = newInstance.getDefaultFont();
        assertNotNull("Font should be accessible after reinitialization", font);
        
        int color = newInstance.getPrimaryColor();
        assertNotEquals("Color should be accessible after reinitialization", 0, color);
    }
}
