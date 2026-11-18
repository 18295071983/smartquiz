package com.oilquiz.app.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * 主题色系对比度护栏（2026-11-18）。
 *
 * <p><b>为什么有它</b>：主题里"有颜色"不等于"能用"——语义色之前出现过
 * 白字压青底 2.43:1、白字压琥珀底 2.15:1、夜间 info 浅字压蓝底 2.07:1 这种
 * 肉眼能看出糊、但没有任何测试能发现的问题。这个类把色系验收口径钉住：</p>
 * <ul>
 *   <li>文字对：on* / 容器 与 base / 容器 的对比度 ≥ 4.5:1（WCAG AA 正文）；</li>
 *   <li>品牌主色是唯一例外：白字压 #8B5CF6 是 4.23:1，只允许用于 ≥16sp 的按钮/大标题
 *       （见 docs/ui/主题色系-设计规范.md）；</li>
 *   <li>非文字元素（组件描边）对底色的对比度 ≥ 3:1；</li>
 *   <li>图表系列色对白底 ≥ 3:1（WCAG 1.4.11 图形对象）；</li>
 *   <li>分隔线必须与组件描边分开（colorDivider == colorOutlineVariant）。</li>
 * </ul>
 *
 * <p>深浅两套都跑；颜色从主题 attr 取，所以预设 / OLED / 动态色只要覆盖了同名
 * attr 就自动被这套口径约束。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ThemeContrastTest {

    /** on/base 成对角色（文字压填充面）。 */
    private static final int[][] TEXT_PAIRS = {
            {R.attr.colorOnSecondary, R.attr.colorSecondary},
            {R.attr.colorOnTertiary, R.attr.colorTertiary},
            {R.attr.colorOnError, R.attr.colorError},
            {R.attr.colorOnSuccess, R.attr.colorSuccess},
            {R.attr.colorOnWarning, R.attr.colorWarning},
            {R.attr.colorOnInfo, R.attr.colorInfo},
            {R.attr.colorOnPrimaryContainer, R.attr.colorPrimaryContainer},
            {R.attr.colorOnSecondaryContainer, R.attr.colorSecondaryContainer},
            {R.attr.colorOnTertiaryContainer, R.attr.colorTertiaryContainer},
            {R.attr.colorOnErrorContainer, R.attr.colorErrorContainer},
            {R.attr.colorOnSuccessContainer, R.attr.colorSuccessContainer},
            {R.attr.colorOnWarningContainer, R.attr.colorWarningContainer},
            {R.attr.colorOnInfoContainer, R.attr.colorInfoContainer},
            {R.attr.colorOnSurface, R.attr.colorSurface},
            {R.attr.colorOnSurfaceVariant, R.attr.colorSurfaceVariant},
            {R.attr.colorOnBackground, R.attr.colorBackground},
    };

    @Test
    public void textPairsPassAaInLightTheme() {
        assertTextPairs(themedContext());
    }

    @Test
    public void textPairsPassAaInDarkTheme() {
        RuntimeEnvironment.setQualifiers("+night");
        assertTextPairs(themedContext());
    }

    /** 品牌主色：4.23:1，仅限大号文字/按钮；这条把"品牌例外"显式记录在测试里。 */
    @Test
    public void primaryOnBrandIsDocumentedBrandException() {
        Context ctx = themedContext();
        double cr = contrast(ThemeColors.attr(ctx, R.attr.colorOnPrimary),
                ThemeColors.attr(ctx, R.attr.colorPrimary));
        assertTrue("白字压主紫的对比度低于 4.2 时，连大字都不达标；品牌色值调整必须同步评审",
                cr >= 4.2);
        assertTrue("主紫白字本来就不满足 4.5（当前 " + String.format(java.util.Locale.ROOT, "%.2f", cr)
                + "），若哪天改成达标了，请把这条测试收紧到 4.5", cr < 4.5);
    }

    @Test
    public void outlineAndChartsAreVisibleOnSurface() {
        for (boolean dark : new boolean[]{false, true}) {
            if (dark) {
                RuntimeEnvironment.setQualifiers("+night");
            }
            Context ctx = themedContext();
            int surface = ThemeColors.attr(ctx, R.attr.colorSurface);
            double outline = contrast(ThemeColors.attr(ctx, R.attr.colorOutline), surface);
            assertTrue("组件描边对表面必须 ≥3:1（当前 " + outline + "）", outline >= 3.0);

            for (int i = 1; i <= 8; i++) {
                int attr = chartAttr(i);
                double cr = contrast(ThemeColors.attr(ctx, attr), surface);
                assertTrue("图表系列色 chart" + i + " 对表面必须 ≥3:1（当前 " + cr + "）", cr >= 3.0);
            }
        }
    }

    @Test
    public void dividerIsOutlineVariantNotComponentOutline() {
        Context ctx = themedContext();
        int divider = ThemeColors.attr(ctx, R.attr.colorDivider);
        int outlineVariant = ThemeColors.attr(ctx, R.attr.colorOutlineVariant);
        int outline = ThemeColors.attr(ctx, R.attr.colorOutline);
        assertEquals("colorDivider 必须映射到 outlineVariant（分隔线语义）", outlineVariant, divider);
        assertTrue("colorDivider 不能等于 colorOutline（组件描边，深色）", divider != outline);
    }

    private static void assertTextPairs(Context ctx) {
        for (int[] pair : TEXT_PAIRS) {
            int on = ThemeColors.attr(ctx, pair[0]);
            int base = ThemeColors.attr(ctx, pair[1]);
            assertTrue("角色未解析出颜色: 0x" + Integer.toHexString(pair[0])
                    + " / 0x" + Integer.toHexString(pair[1]), on != 0 && base != 0);
            double cr = contrast(on, base);
            assertTrue("文字对比度不足 4.5:1（当前 "
                            + String.format(java.util.Locale.ROOT, "%.2f", cr) + "）：attr 0x"
                            + Integer.toHexString(pair[0]) + " on 0x" + Integer.toHexString(pair[1]),
                    cr >= 4.5);
        }
    }

    private static int chartAttr(int index) {
        switch (index) {
            case 1: return R.attr.colorChart1;
            case 2: return R.attr.colorChart2;
            case 3: return R.attr.colorChart3;
            case 4: return R.attr.colorChart4;
            case 5: return R.attr.colorChart5;
            case 6: return R.attr.colorChart6;
            case 7: return R.attr.colorChart7;
            default: return R.attr.colorChart8;
        }
    }

    private static Context themedContext() {
        Context app = ApplicationProvider.getApplicationContext();
        app.setTheme(R.style.Theme_OilQuizApp);
        return app;
    }

    /** WCAG 2.x 相对亮度对比度。 */
    static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    static double luminance(int color) {
        return 0.2126 * channel((color >> 16) & 0xFF)
                + 0.7152 * channel((color >> 8) & 0xFF)
                + 0.0722 * channel(color & 0xFF);
    }

    private static double channel(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
