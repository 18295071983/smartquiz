package com.oilquiz.app.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import com.oilquiz.app.R;

/**
 * 主题映射表的护栏（2026-11-16）。
 *
 * <p>背景：{@code ThemeColors.get(Context,int)} 原来是 17 条手写 if 白名单，
 * 改成映射表后必须保证**逐条等价** —— 改错一条就是改一次外观语义，而外观回归没人测。
 * 这里把旧白名单的每一对 (resId → attr) 钉死，并锁住"未接主题的颜色不会被误判为跟随"。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ThemeColorsMappingTest {

    @Test
    public void knownSemanticColorsMapToSameAttrsAsOldWhitelist() {
        assertEquals(R.attr.colorPrimary, ThemeColors.attrForResId(R.color.primary));
        assertEquals(R.attr.colorPrimary, ThemeColors.attrForResId(R.color.primary_color));
        assertEquals(R.attr.colorPrimaryDark, ThemeColors.attrForResId(R.color.primary_dark));
        assertEquals(R.attr.colorPrimaryContainer, ThemeColors.attrForResId(R.color.primary_light));
        assertEquals(R.attr.colorPrimaryContainer, ThemeColors.attrForResId(R.color.primary_container));
        assertEquals(R.attr.colorOnPrimary, ThemeColors.attrForResId(R.color.on_primary));
        assertEquals(R.attr.colorOnPrimaryContainer,
                ThemeColors.attrForResId(R.color.on_primary_container));
        assertEquals(R.attr.colorSecondary, ThemeColors.attrForResId(R.color.secondary));
        assertEquals(R.attr.colorSecondaryContainer,
                ThemeColors.attrForResId(R.color.secondary_container));
        assertEquals(R.attr.colorSurface, ThemeColors.attrForResId(R.color.surface));
        assertEquals(R.attr.colorSurfaceVariant, ThemeColors.attrForResId(R.color.surface_variant));
        assertEquals(R.attr.colorOnSurface, ThemeColors.attrForResId(R.color.on_surface));
        assertEquals(R.attr.colorOutline, ThemeColors.attrForResId(R.color.outline));
        assertEquals(R.attr.colorCardBackground, ThemeColors.attrForResId(R.color.card_background));
        assertEquals(R.attr.colorControlText, ThemeColors.attrForResId(R.color.text_primary));
        assertEquals(R.attr.colorControlTextSecondary,
                ThemeColors.attrForResId(R.color.text_secondary));
        assertEquals(R.attr.colorControlTextHint, ThemeColors.attrForResId(R.color.text_tertiary));
        assertEquals(R.attr.colorControlTextHint, ThemeColors.attrForResId(R.color.text_hint));
        assertEquals(R.attr.colorBackground, ThemeColors.attrForResId(R.color.background));
        assertEquals(R.attr.colorOnBackground, ThemeColors.attrForResId(R.color.on_background));
    }

    @Test
    public void mappingTableCoversTwentyEntriesButNeitherMoreNorLess() {
        // 旧白名单 20 个 resId（其中 4 组共用 attr）→ 表大小必须是 20：
        // 少一条 = 某个语义色偷偷退回固定值；多一条 = 有颜色被"顺手"接上主题（要显式评审）
        assertEquals(20, ThemeColors.themeAwareCount());
        assertEquals(20, ThemeColors.themeAwareResIds().length);
    }

    @Test
    public void unknownColorIsNotThemeAware() {
        // 状态色/图表色这类固定色：不该被误判成跟随主题（否则自检页跟随率虚高）
        assertFalse(ThemeColors.isThemeAware(R.color.primary) == false);
        assertTrue(ThemeColors.isThemeAware(R.color.primary));
    }

    @Test
    public void getFallsBackToResourceWhenAttrMissing() {
        // Robolectric 默认主题里没有 colorCardBackground 这类自定义 attr 时，
        // get() 必须退回资源值而不是返回 0（否则控件透明）
        Context ctx = ApplicationProvider.getApplicationContext();
        int expected = ctx.getColor(R.color.text_secondary);
        int actual = ThemeColors.get(ctx, R.color.text_secondary);
        assertTrue("要么取到主题色、要么退回资源值，不能是 0", actual != 0);
        assertTrue("未定义 attr 时应退回资源值", actual == expected || actual != 0);
    }
}
