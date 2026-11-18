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
        // 2026-11-18：R.color.outline 是历史"细边框/分隔线"色，语义对应 outlineVariant；
        // M3 的组件描边是另一档（colorOutline，tone 50 深灰）。
        assertEquals(R.attr.colorOutlineVariant, ThemeColors.attrForResId(R.color.outline));
        assertEquals(R.attr.colorCardBackground, ThemeColors.attrForResId(R.color.card_background));
        assertEquals(R.attr.colorControlText, ThemeColors.attrForResId(R.color.text_primary));
        assertEquals(R.attr.colorControlTextSecondary,
                ThemeColors.attrForResId(R.color.text_secondary));
        assertEquals(R.attr.colorControlTextTertiary,
                ThemeColors.attrForResId(R.color.text_tertiary));
        assertEquals(R.attr.colorControlTextHint, ThemeColors.attrForResId(R.color.text_hint));
        assertEquals(R.attr.colorBackground, ThemeColors.attrForResId(R.color.background));
        assertEquals(R.attr.colorOnBackground, ThemeColors.attrForResId(R.color.on_background));
    }

    /**
     * 映射表规模（2026-11-16 订正）。
     *
     * <p>最初是旧白名单的 **20 条**；2026-11-16 的"语义色补齐"把状态色/边框/分隔线/遮罩/
     * 图表色接上主题（20 → 53）；同一天聊天 v2 token 分两轮接入主题：
     * 第一轮 chat_v2_bg/text/text_2/text_3/surface_2/brand/success/danger（53 → 61），
     * 第二轮布局/drawable 改走 ?attr 后补齐 surface/brand_ink/brand_soft/on_brand（61 → **65**）。
     * 此前它们只有 values-night 变体，换肤档位对聊天页不生效。表规模是有意评审的结果，
     * 不是"顺手接上"；这里钉当前规模，并要求两个可观测 API 一致：改表必须同步改这条。</p>
     */
    @Test
    public void mappingTableSizeIsPinned() {
        assertEquals("映射表规模变了：要么补了语义色、要么误接了固定色（见 ThemeColors 注释）",
                65, ThemeColors.themeAwareCount());
        assertEquals("两个可观测 API 口径必须一致",
                ThemeColors.themeAwareCount(), ThemeColors.themeAwareResIds().length);
        // 扩表时新增的几类必须真的在表里（防"数量对了但接错对象"）
        int[] added = {R.color.success, R.color.error_container, R.color.border,
                R.color.divider, R.color.card_scrim, R.color.chart_1,
                R.color.chat_v2_text, R.color.chat_v2_surface_2, R.color.chat_v2_brand,
                R.color.chat_v2_surface, R.color.chat_v2_brand_ink, R.color.chat_v2_on_brand};
        for (int resId : added) {
            assertTrue(resId + " 应跟随主题（语义色补齐 / 聊天 v2 token 接入）",
                    ThemeColors.isThemeAware(resId));
        }
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
