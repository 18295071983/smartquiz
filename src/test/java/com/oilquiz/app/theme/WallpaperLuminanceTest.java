package com.oilquiz.app.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.TypedValue;

import androidx.core.content.ContextCompat;

import com.oilquiz.app.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 首页壁纸自适应（2026-11-16）的判定与主题覆盖契约。
 *
 * <p>背景：首页背景跟随系统壁纸，但文字/玻璃卡此前是写死给深色壁纸的（白字 + 半透明白卡），
 * 换成浅色壁纸后白字几乎不可读。这里钉住：</p>
 * <ul>
 *   <li>{@code averageLuminance} 的感知亮度计算（白→1、黑→0、中灰≈0.5）；</li>
 *   <li>{@code isLightWallpaper} 在无壁纸时按"亮"处理（调用方只在壁纸开启时用）；</li>
 *   <li>{@code ThemeOverlay.Home.LightWallpaper} 确实把首页三个文字 attr 和卡片底
 *       覆盖成"深字 + 强白卡"，基础主题仍是白字/半透明卡。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class WallpaperLuminanceTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = com.oilquiz.app.testsupport.ThemedAppContext.get();
    }

    @Test
    public void averageLuminanceMatchesPerceivedBrightness() {
        assertEquals("白色亮度应为 1", 1f, AppWallpaperManager.averageLuminance(solid(0xFFFFFFFF)), 0.01f);
        assertEquals("黑色亮度应为 0", 0f, AppWallpaperManager.averageLuminance(solid(0xFF000000)), 0.01f);
        float mid = AppWallpaperManager.averageLuminance(solid(0xFF808080));
        assertTrue("中灰亮度应在 0.5 附近，实际=" + mid, Math.abs(mid - 0.5f) < 0.02f);
        assertEquals("null 位图按 0 处理", 0f, AppWallpaperManager.averageLuminance(null), 0.0001f);
    }

    @Test
    public void lightClassificationUsesScrimmedDisplayLuminance() {
        // 纯白壁纸叠 35% 黑 scrim 后仍偏亮 → 判"亮"
        assertTrue("白色壁纸应判为亮", AppWallpaperManager.isLightWallpaperBitmap(solid(0xFFFFFFFF)));
        // 纯黑壁纸 → 判"暗"
        assertFalse("黑色壁纸应判为暗", AppWallpaperManager.isLightWallpaperBitmap(solid(0xFF000000)));
        // 无壁纸（null）按亮处理：调用方只在壁纸模式开启时才用
        assertTrue("无壁纸时应按亮处理", AppWallpaperManager.isLightWallpaperBitmap(null));
    }

    @Test
    public void lightWallpaperOverlayOverridesHomePalette() {
        // 基础主题：白字 + 现有半透明玻璃卡
        assertEquals(R.color.future_text_primary, resolvedColorRes(R.attr.homeTextPrimary));
        assertEquals(R.drawable.weather_card_glass, resolvedRefRes(R.attr.homeCardBackground));

        // 浅色壁纸覆盖：深字 + 强白卡
        ctx.getTheme().applyStyle(R.style.ThemeOverlay_Home_LightWallpaper, true);
        assertEquals(R.color.home_text_primary_on_light, resolvedColorRes(R.attr.homeTextPrimary));
        assertEquals(R.color.home_text_secondary_on_light, resolvedColorRes(R.attr.homeTextSecondary));
        assertEquals(R.color.home_text_tertiary_on_light, resolvedColorRes(R.attr.homeTextTertiary));
        assertEquals(R.drawable.home_card_glass_light, resolvedRefRes(R.attr.homeCardBackground));
    }

    // ---------------- 工具 ----------------

    private static Bitmap solid(int argb) {
        Bitmap bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(argb);
        return bmp;
    }

    private int resolvedColorRes(int attr) {
        TypedValue tv = new TypedValue();
        assertTrue("主题未解析到 attr", ctx.getTheme().resolveAttribute(attr, tv, true));
        return tv.resourceId;
    }

    private int resolvedRefRes(int attr) {
        TypedValue tv = new TypedValue();
        assertTrue("主题未解析到 attr", ctx.getTheme().resolveAttribute(attr, tv, true));
        return tv.resourceId;
    }
}
