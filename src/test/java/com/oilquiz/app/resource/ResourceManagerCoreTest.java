package com.oilquiz.app.resource;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

/**
 * 资源管理器核心功能测试
 * 不依赖UI资源，只测试核心逻辑
 */
@RunWith(RobolectricTestRunner.class)
public class ResourceManagerCoreTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void testAppResourceManagerCreation() {
        // 测试AppResourceManager创建
        AppResourceManager resourceManager = AppResourceManager.getInstance(context);
        assertNotNull("AppResourceManager should be created successfully", resourceManager);
    }

    @Test
    public void testConfigResourceProvider() {
        // 测试配置资源提供者
        ConfigResourceProvider configProvider = ConfigResourceProvider.getInstance(context);
        assertNotNull("ConfigResourceProvider should be created successfully", configProvider);
        
        // 测试配置设置和获取
        String testKey = "test_config_key";
        String testValue = "test_config_value";
        configProvider.setConfig(testKey, testValue);
        assertEquals("Config value should be set correctly", testValue, configProvider.getString(testKey, "default"));
        
        // 清理测试数据
        configProvider.removeConfig(testKey);
    }

    @Test
    public void testColorResourceProvider() {
        // 测试颜色资源提供者
        ColorResourceProvider colorProvider = ColorResourceProvider.getInstance(context);
        assertNotNull("ColorResourceProvider should be created successfully", colorProvider);
        
        // 测试颜色获取（使用系统默认颜色）
        int blackColor = colorProvider.getColor("#000000");
        assertNotEquals("Black color should be retrieved", 0, blackColor);
        
        int redColor = colorProvider.getColor("#FF0000");
        assertNotEquals("Red color should be retrieved", 0, redColor);
    }

    @Test
    public void testFontResourceProvider() {
        // 测试字体资源提供者
        FontResourceProvider fontProvider = FontResourceProvider.getInstance(context);
        assertNotNull("FontResourceProvider should be created successfully", fontProvider);
        
        // 测试默认字体获取
        assertNotNull("Default font should be available", fontProvider.getDefaultFont());
    }

    @Test
    public void testPermissionResourceProvider() {
        // 测试权限资源提供者
        PermissionResourceProvider permissionProvider = PermissionResourceProvider.getInstance(context);
        assertNotNull("PermissionResourceProvider should be created successfully", permissionProvider);
        
        // 测试权限检查方法
        // 注意：这里只是测试方法调用，不测试实际权限状态
        permissionProvider.hasStoragePermission();
        permissionProvider.hasCameraPermission();
    }

    @Test
    public void testFileResourceProvider() {
        // 测试文件资源提供者
        FileResourceProvider fileProvider = FileResourceProvider.getInstance(context);
        assertNotNull("FileResourceProvider should be created successfully", fileProvider);
        
        // 测试目录获取
        assertNotNull("Import directory should be available", fileProvider.getImportDirectory());
        assertNotNull("Export directory should be available", fileProvider.getExportDirectory());
        assertNotNull("Temp directory should be available", fileProvider.getTempDirectory());
    }

    @Test
    public void testWebViewResourceProvider() {
        // 测试WebView资源提供者
        WebViewResourceProvider webViewProvider = WebViewResourceProvider.getInstance(context);
        assertNotNull("WebViewResourceProvider should be created successfully", webViewProvider);
    }

    @Test
    public void testSoundResourceProvider() {
        // 测试音效资源提供者
        SoundResourceProvider soundProvider = SoundResourceProvider.getInstance(context);
        assertNotNull("SoundResourceProvider should be created successfully", soundProvider);
    }

    @Test
    public void testResourceManagerIntegration() {
        // 测试资源管理器集成
        AppResourceManager resourceManager = AppResourceManager.getInstance(context);
        
        // 测试所有提供者都能正常获取
        assertNotNull("Font provider should be available", resourceManager.fonts());
        assertNotNull("Color provider should be available", resourceManager.colors());
        assertNotNull("Config provider should be available", resourceManager.config());
        assertNotNull("Permission provider should be available", resourceManager.permissions());
        assertNotNull("WebView provider should be available", resourceManager.webView());
        assertNotNull("File provider should be available", resourceManager.files());
        assertNotNull("Sound provider should be available", resourceManager.sounds());
    }

    @Test
    public void testResourceManagerRelease() {
        // 测试资源管理器释放
        AppResourceManager resourceManager = AppResourceManager.getInstance(context);
        assertNotNull("Resource manager should be created", resourceManager);
        
        // 释放资源
        resourceManager.releaseAll();
        
        // 重新获取实例
        AppResourceManager newInstance = AppResourceManager.getInstance(context);
        assertNotNull("New instance should be created after release", newInstance);
    }

    @Test
    public void testConfigPersistence() {
        // 测试配置持久化
        ConfigResourceProvider configProvider = ConfigResourceProvider.getInstance(context);
        
        // 设置测试配置
        String testKey = "persistence_test_key";
        int testValue = 12345;
        configProvider.setConfig(testKey, testValue);
        
        // 重新获取实例
        ConfigResourceProvider newProvider = ConfigResourceProvider.getInstance(context);
        
        // 验证配置是否持久化
        assertEquals("Config should be persisted", testValue, newProvider.getInt(testKey, 0));
        
        // 清理测试数据
        newProvider.removeConfig(testKey);
    }
}
