package com.oilquiz.app.resource;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

/**
 * 资源管理集成测试
 * 测试不同资源提供者之间的协作
 */
@RunWith(RobolectricTestRunner.class)
public class ResourceIntegrationTest {

    private Context context;
    private AppResourceManager appResourceManager;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        appResourceManager = AppResourceManager.getInstance(context);
    }

    @Test
    public void testResourceManagerIntegration() {
        // 测试字体资源
        assertNotNull("Font provider should be available", appResourceManager.fonts());
        assertNotNull("Default font should be available", appResourceManager.getDefaultFont());

        // 测试颜色资源
        assertNotNull("Color provider should be available", appResourceManager.colors());
        assertNotEquals("Primary color should be available", 0, appResourceManager.getPrimaryColor());

        // 测试配置资源
        assertNotNull("Config provider should be available", appResourceManager.config());
        assertNotNull("App name should be available", appResourceManager.getConfigString("app_name", "Default"));

        // 测试权限资源
        assertNotNull("Permission provider should be available", appResourceManager.permissions());

        // 测试WebView资源
        assertNotNull("WebView provider should be available", appResourceManager.webView());

        // 测试文件资源
        assertNotNull("File provider should be available", appResourceManager.files());
    }

    @Test
    public void testConfigPersistence() {
        // 设置配置
        String testKey = "integration_test_key";
        String testValue = "integration_test_value";
        appResourceManager.config().setConfig(testKey, testValue);

        // 释放资源
        appResourceManager.releaseAll();

        // 重新获取实例
        AppResourceManager newInstance = AppResourceManager.getInstance(context);

        // 验证配置是否持久化
        String retrievedValue = newInstance.getConfigString(testKey, "default");
        assertEquals("Config should be persisted", testValue, retrievedValue);

        // 清理测试数据
        newInstance.config().removeConfig(testKey);
    }

    @Test
    public void testResourceCaching() {
        // 第一次获取资源（应该加载并缓存）
        int primaryColor1 = appResourceManager.getPrimaryColor();
        assertNotEquals("First color retrieval should succeed", 0, primaryColor1);

        // 第二次获取资源（应该从缓存获取）
        int primaryColor2 = appResourceManager.getPrimaryColor();
        assertEquals("Second color retrieval should return cached value", primaryColor1, primaryColor2);

        // 清除缓存
        appResourceManager.clearAllCaches();

        // 第三次获取资源（应该重新加载）
        int primaryColor3 = appResourceManager.getPrimaryColor();
        assertNotEquals("Third color retrieval should succeed after clearing cache", 0, primaryColor3);
    }

    @Test
    public void testResourceProviderIndependence() {
        // 测试配置提供者
        ConfigResourceProvider configProvider = appResourceManager.config();
        assertNotNull("Config provider should be independent", configProvider);

        // 测试字体提供者
        FontResourceProvider fontProvider = appResourceManager.fonts();
        assertNotNull("Font provider should be independent", fontProvider);

        // 测试颜色提供者
        ColorResourceProvider colorProvider = appResourceManager.colors();
        assertNotNull("Color provider should be independent", colorProvider);

        // 验证它们是不同的实例
        assertNotSame("Providers should be different instances", configProvider, fontProvider);
        assertNotSame("Providers should be different instances", fontProvider, colorProvider);
    }

    @Test
    public void testResourceAccessAfterReinitialization() {
        // 第一次获取资源
        int color1 = appResourceManager.getColor("success");
        assertNotEquals("First color access should succeed", 0, color1);

        // 释放所有资源
        appResourceManager.releaseAll();

        // 重新初始化
        AppResourceManager newManager = AppResourceManager.getInstance(context);

        // 第二次获取资源
        int color2 = newManager.getColor("success");
        assertNotEquals("Second color access should succeed after reinitialization", 0, color2);

        // 验证资源值一致
        assertEquals("Color values should be consistent after reinitialization", color1, color2);
    }

    @Test
    public void testResourceSystemIntegration() {
        // 测试配置系统
        String testKey = "system_integration_test";
        int testValue = 999;
        appResourceManager.config().setConfig(testKey, testValue);
        assertEquals("Config system should work", testValue, appResourceManager.getConfigInt(testKey, 0));

        // 测试颜色系统
        int successColor = appResourceManager.getColor("success");
        int errorColor = appResourceManager.getColor("error");
        assertNotEquals("Different colors should have different values", successColor, errorColor);

        // 测试字体系统
        assertNotNull("Title font should be available", appResourceManager.getFont("title"));
        assertNotNull("Body font should be available", appResourceManager.getFont("body"));

        // 清理测试数据
        appResourceManager.config().removeConfig(testKey);
    }
}
