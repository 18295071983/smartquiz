package com.oilquiz.app.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * M3 颜色角色完整性（2026-11-18）。
 *
 * <p><b>为什么有它</b>：主题里只声明了一部分 M3 role，<code>colorSurfaceContainer*</code> /
 * <code>colorOutlineVariant</code> / <code>colorInverse*</code> 等缺失时，MDC 的日期/时间选择器、
 * Chip、Card、Snackbar 会退回 <code>colorSurface</code>/透明——观感上就是"用 M3 的名字显示的不是
 * M3 的颜色"。本类把两条口径钉住：</p>
 * <ol>
 *   <li>这套 role 在浅色 / 深色主题下都能解析出非 0 颜色；</li>
 *   <li>outline（组件描边）与 outlineVariant（分隔线）必须分开，且 surfaceContainer 五档
 *       按 M3 的明暗关系排序（浅色越低越暗、深色越高越亮）。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
public class ThemeColorRolesTest {

    private static final int[] M3_ROLE_ATTRS = {
            R.attr.colorOutline,
            R.attr.colorOutlineVariant,
            R.attr.colorSurfaceContainerLowest,
            R.attr.colorSurfaceContainerLow,
            R.attr.colorSurfaceContainer,
            R.attr.colorSurfaceContainerHigh,
            R.attr.colorSurfaceContainerHighest,
            R.attr.colorSurfaceTint,
            R.attr.colorInverseSurface,
            R.attr.colorInverseOnSurface,
            R.attr.colorInversePrimary,
            // 层级（2026-11-18 补全）
            R.attr.colorSurfaceDim,
            R.attr.colorSurfaceBright,
            // 语义容器成套
            R.attr.colorInfoContainer,
            R.attr.colorOnInfo,
            R.attr.colorOnInfoContainer,
            R.attr.colorOnSuccessContainer,
            R.attr.colorOnWarningContainer,
            // 状态层
            R.attr.colorStateHover,
            R.attr.colorStateFocus,
            R.attr.colorStatePressed,
            R.attr.colorStateDragged,
            R.attr.colorStateDisabledContainer,
            R.attr.colorStateDisabledContent,
            // 图表
            R.attr.colorChartGrid,
            R.attr.colorChartAxis,
            R.attr.colorChartLabel,
    };

    private static final int[] CONTAINER_ATTRS = {
            R.attr.colorSurfaceContainerLowest,
            R.attr.colorSurfaceContainerLow,
            R.attr.colorSurfaceContainer,
            R.attr.colorSurfaceContainerHigh,
            R.attr.colorSurfaceContainerHighest,
    };

    @Test
    public void m3RolesResolveInLightTheme() {
        assertRolesResolve(themedContext());
    }

    @Test
    public void m3RolesResolveInDarkTheme() {
        RuntimeEnvironment.setQualifiers("+night");
        assertRolesResolve(themedContext());
    }

    @Test
    public void stateLayersAreTranslucentAndOrdered() {
        Context ctx = themedContext();
        int hover = ThemeColors.attr(ctx, R.attr.colorStateHover);
        int pressed = ThemeColors.attr(ctx, R.attr.colorStatePressed);
        int dragged = ThemeColors.attr(ctx, R.attr.colorStateDragged);
        int disabledContent = ThemeColors.attr(ctx, R.attr.colorStateDisabledContent);

        assertTrue("状态层必须是半透明叠色（否则会盖住底下的内容）",
                alphaOf(hover) > 0 && alphaOf(hover) < 255);
        assertTrue("hover 应比 pressed 轻", alphaOf(hover) < alphaOf(pressed));
        assertTrue("pressed 不应比 dragged 重", alphaOf(pressed) <= alphaOf(dragged));
        assertTrue("禁用文字要比 pressed 更明显（否则禁用态看不出来）",
                alphaOf(disabledContent) > alphaOf(pressed));
    }

    private static int alphaOf(int color) {
        return (color >>> 24) & 0xFF;
    }

    @Test
    public void presetOverlayDefinesFullSurfaceHierarchy() {
        Context ctx = presetContext(R.style.ThemeOverlay_SmartQuiz_Purple);
        assertContainerHierarchy(ctx, true);
        assertNotEquals("预设 overlay 的 outline 不能等于 outlineVariant（否则描边又看不见）",
                ThemeColors.attr(ctx, R.attr.colorOutline),
                ThemeColors.attr(ctx, R.attr.colorOutlineVariant));
        assertEquals("surfaceTint 必须跟随该预设主色",
                ThemeColors.attr(ctx, R.attr.colorPrimary),
                ThemeColors.attr(ctx, R.attr.colorSurfaceTint));
        int scrim = ThemeColors.attr(ctx, R.attr.colorScrim);
        assertTrue("预设遮罩必须是半透明（生成器曾输出不透明 #000000，弹层会把底图盖死）",
                alphaOf(scrim) > 0 && alphaOf(scrim) < 255);
    }

    @Test
    public void presetOverlayDefinesFullSurfaceHierarchyInDark() {
        RuntimeEnvironment.setQualifiers("+night");
        Context ctx = presetContext(R.style.ThemeOverlay_SmartQuiz_Purple);
        assertContainerHierarchy(ctx, false);
    }

    @Test
    public void materialControlAttrsResolveInAllAppThemes() {
        int activated = com.google.android.material.R.attr.colorControlActivated;
        int normal = com.google.android.material.R.attr.colorControlNormal;
        int[] styles = {
                R.style.Theme_OilQuizApp,
                R.style.ThemeOverlay_App_Dialog,
                R.style.Theme_App_Translucent,
        };
        for (int style : styles) {
            androidx.appcompat.view.ContextThemeWrapper ctx =
                    new androidx.appcompat.view.ContextThemeWrapper(
                            ApplicationProvider.getApplicationContext(), style);
            assertTrue("主题 0x" + Integer.toHexString(style)
                            + " 缺 colorControlActivated：SwitchMaterial 会在 onAttachedToWindow 直接崩",
                    ThemeColors.attr(ctx, activated) != 0);
            assertTrue("主题 0x" + Integer.toHexString(style) + " 缺 colorControlNormal",
                    ThemeColors.attr(ctx, normal) != 0);
        }
    }

    private static void assertContainerHierarchy(Context ctx, boolean light) {
        int[] levels = resolveAll(ctx, CONTAINER_ATTRS);
        long distinct = java.util.Arrays.stream(levels).distinct().count();
        assertTrue("预设 overlay 必须自带五档 surfaceContainer（生成器 tone 100/96/94/92/90），"
                + "不能靠 ThemeOverlay.App.M3Roles 压成 2 档；当前不同值只有 " + distinct,
                distinct >= 4);
        for (int i = 1; i < levels.length; i++) {
            if (light) {
                assertTrue("浅色预设：容器越高越暗", luminance(levels[i - 1]) >= luminance(levels[i]));
            } else {
                assertTrue("深色预设：容器越高越亮", luminance(levels[i - 1]) <= luminance(levels[i]));
            }
        }
    }

    @Test
    public void outlineAndDividerColorsAreSplit() {
        Context ctx = themedContext();
        int outline = ThemeColors.attr(ctx, R.attr.colorOutline);
        int variant = ThemeColors.attr(ctx, R.attr.colorOutlineVariant);
        assertNotEquals("outline 是组件描边、outlineVariant 是分隔线，不能共用同一个色",
                outline, variant);
    }

    @Test
    public void surfaceContainersFollowM3ElevationDirection() {
        Context light = themedContext();
        int[] lightColors = resolveAll(light, CONTAINER_ATTRS);
        for (int i = 1; i < lightColors.length; i++) {
            assertTrue("浅色主题：surfaceContainer 越高越暗（lowest 最亮）",
                    luminance(lightColors[i - 1]) >= luminance(lightColors[i]));
        }

        RuntimeEnvironment.setQualifiers("+night");
        Context dark = themedContext();
        int[] darkColors = resolveAll(dark, CONTAINER_ATTRS);
        for (int i = 1; i < darkColors.length; i++) {
            assertTrue("深色主题：surfaceContainer 越高越亮（lowest 最暗）",
                    luminance(darkColors[i - 1]) <= luminance(darkColors[i]));
        }
    }

    // ==================== 工具 ====================

    private static Context themedContext() {
        Context app = ApplicationProvider.getApplicationContext();
        app.setTheme(R.style.Theme_OilQuizApp);
        return app;
    }

    /** 模拟 ThemeManager.applyThemeOverlay：基础主题 + 预设 overlay（不再叠 M3Roles 兜底）。 */
    private static Context presetContext(int overlayStyle) {
        androidx.appcompat.view.ContextThemeWrapper ctx =
                new androidx.appcompat.view.ContextThemeWrapper(
                        ApplicationProvider.getApplicationContext(), R.style.Theme_OilQuizApp);
        ctx.getTheme().applyStyle(overlayStyle, true);
        return ctx;
    }

    private static void assertRolesResolve(Context ctx) {
        for (int attr : M3_ROLE_ATTRS) {
            int color = ThemeColors.attr(ctx, attr);
            assertTrue("M3 角色必须是已定义的颜色（attr 0x" + Integer.toHexString(attr)
                    + "），解析到 0 说明主题里缺这个 role", color != 0);
        }
    }

    private static int[] resolveAll(Context ctx, int[] attrs) {
        int[] out = new int[attrs.length];
        for (int i = 0; i < attrs.length; i++) {
            out[i] = ThemeColors.attr(ctx, attrs[i]);
        }
        return out;
    }

    /** 相对亮度（0~1，sRGB 近似），只用于比较明暗顺序。 */
    private static double luminance(int color) {
        return 0.2126 * channel((color >> 16) & 0xFF)
                + 0.7152 * channel((color >> 8) & 0xFF)
                + 0.0722 * channel(color & 0xFF);
    }

    private static double channel(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
