package com.oilquiz.app.theme;

import static org.junit.Assert.assertTrue;

import com.oilquiz.app.theme.mcu.hct.Hct;

import org.junit.Test;

/**
 * 31 套主题（7 预设 + 24 色相网格）的色板回归（2026-11-06 换新引擎后）。
 *
 * <p>不钉具体 ARGB（允许视觉刷新），钉“每套都能生成完整色板 + 关键前景/背景对比度 ≥ 3”
 * —— overlay 生成器与运行时 ThemePaletteProvider 共用同一引擎，这里守住基本可读性。</p>
 */
public class ThemePaletteProviderTest {

    private static final int[] PRESETS = {
            0xFF3B82F6, 0xFF10B981, 0xFF8B5CF6, 0xFFF97316,
            0xFFEC4899, 0xFF14B8A6, 0xFF6366F1
    };

    @Test
    public void all31ThemesGenerateReadableLightAndDarkPalettes() {
        int checked = 0;
        for (int i = 0; i < PRESETS.length; i++) {
            assertReadable(PRESETS[i], "preset-" + i);
            checked++;
        }
        for (int i = 0; i < 24; i++) {
            int seed = Hct.from(i * 15.0, 50.0, 50.0).toInt();
            assertReadable(seed, "hue-" + i);
            checked++;
        }
        assertTrue("应覆盖 31 套主题", checked == 31);
    }

    private static void assertReadable(int argb, String label) {
        assertPalette(ThemePaletteProvider.light(argb), label + "-light");
        assertPalette(ThemePaletteProvider.dark(argb), label + "-dark");
    }

    private static void assertPalette(ThemePalette p, String label) {
        assertTrue(label + " primary 为空", p.primary != 0);
        assertTrue(label + " surface 为空", p.surface != 0);
        assertTrue(label + " onPrimary/primary 对比不足",
                contrast(p.onPrimary, p.primary) >= 3.0);
        assertTrue(label + " onSurface/surface 对比不足",
                contrast(p.onSurface, p.surface) >= 3.0);
        assertTrue(label + " onBackground/background 对比不足",
                contrast(p.onBackground, p.background) >= 3.0);
        assertTrue(label + " onSurfaceVariant/surfaceVariant 对比不足",
                contrast(p.onSurfaceVariant, p.surfaceVariant) >= 3.0);
        assertTrue(label + " onError/error 对比不足",
                contrast(p.onError, p.error) >= 3.0);
    }

    private static double contrast(int fg, int bg) {
        double l1 = luminance(fg);
        double l2 = luminance(bg);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    private static double luminance(int argb) {
        return 0.2126 * channel((argb >> 16) & 0xFF)
                + 0.7152 * channel((argb >> 8) & 0xFF)
                + 0.0722 * channel(argb & 0xFF);
    }

    private static double channel(int c) {
        double s = c / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
