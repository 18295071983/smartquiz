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
 * 字体资源提供者单元测试
 */
@RunWith(RobolectricTestRunner.class)
public class FontResourceProviderTest {

    private Context context;
    private FontResourceProvider fontProvider;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        fontProvider = FontResourceProvider.getInstance(context);
    }

    @Test
    public void testGetInstance() {
        assertNotNull("FontResourceProvider instance should not be null", fontProvider);
    }

    @Test
    public void testGetDefaultFont() {
        Typeface defaultFont = fontProvider.getDefaultFont();
        assertNotNull("Default font should not be null", defaultFont);
    }

    @Test
    public void testGetTitleFont() {
        Typeface titleFont = fontProvider.getTitleFont();
        assertNotNull("Title font should not be null", titleFont);
    }

    @Test
    public void testGetBodyFont() {
        Typeface bodyFont = fontProvider.getBodyFont();
        assertNotNull("Body font should not be null", bodyFont);
    }

    @Test
    public void testGetChineseFont() {
        Typeface chineseFont = fontProvider.getChineseFont();
        assertNotNull("Chinese font should not be null", chineseFont);
    }

    @Test
    public void testGetCodeFont() {
        Typeface codeFont = fontProvider.getCodeFont();
        assertNotNull("Code font should not be null", codeFont);
    }

    @Test
    public void testGetFont() {
        // 测试获取存在的字体
        Typeface font = fontProvider.getFont("title");
        assertNotNull("Font should not be null", font);

        // 测试获取不存在的字体（应该返回默认字体）
        Typeface defaultFont = fontProvider.getFont("non_existent_font");
        assertNotNull("Default font should be returned for non-existent font", defaultFont);
    }

    @Test
    public void testIsFontAvailable() {
        // 测试存在的字体
        assertTrue("Title font should be available", fontProvider.isFontAvailable("title"));
        assertTrue("Body font should be available", fontProvider.isFontAvailable("body"));

        // 测试不存在的字体
        assertFalse("Non-existent font should not be available", fontProvider.isFontAvailable("non_existent_font"));
    }

    @Test
    public void testGetAvailableFonts() {
        String[] availableFonts = fontProvider.getAvailableFonts();
        assertNotNull("Available fonts array should not be null", availableFonts);
        assertTrue("Available fonts array should not be empty", availableFonts.length > 0);
        
        // 检查常用字体是否在列表中
        boolean containsTitle = false;
        boolean containsBody = false;
        for (String font : availableFonts) {
            if ("title".equals(font)) containsTitle = true;
            if ("body".equals(font)) containsBody = true;
        }
        assertTrue("Available fonts should contain 'title'", containsTitle);
        assertTrue("Available fonts should contain 'body'", containsBody);
    }

    @Test
    public void testClearCache() {
        // 先获取字体以填充缓存
        fontProvider.getFont("title");
        fontProvider.getFont("body");
        
        // 清除缓存
        fontProvider.clearCache();
        
        // 再次获取字体应该重新加载
        Typeface titleFont = fontProvider.getFont("title");
        assertNotNull("Title font should be available after clearing cache", titleFont);
    }
}
