package com.oilquiz.app.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * 生成色板的角色完整性（2026-11-18）。
 *
 * <p>预设 overlay 的色值全部由 {@link ThemePaletteProvider} 生成，所以"角色缺一档"
 * 或"五档容器明暗反了"必须在色板层就挡住，而不是等真机上看日期选择器糊成一片。
 * 这里对 7 个预设种子 + 若干色相网格种子逐一验证：</p>
 * <ul>
 *   <li>五档 surfaceContainer 齐全且按明暗排序（浅色越低越亮、深色越高越亮）；</li>
 *   <li>surfaceDim / surfaceBright 在正确一侧；</li>
 *   <li>outline 与 outlineVariant 分离（组件描边 vs 分隔线）；</li>
 *   <li>onPrimary / onSurface 与底色的方向正确（深色底配浅字，浅色底配深字）。</li>
 * </ul>
 */
public class ThemePaletteTest {

    private static final int[] SEEDS = {
            0xFF3B82F6, 0xFF10B981, 0xFF8B5CF6, 0xFFF97316,
            0xFFEC4899, 0xFF14B8A6, 0xFF6366F1,
            // 色相网格里的几个代表值（HCT hue 0 / 120 / 240，chroma 50 / tone 50）
            0xFF9E2A2B, 0xFF2E7D32, 0xFF1565C0,
    };

    @Test
    public void lightPaletteHasOrderedSurfaceHierarchy() {
        for (int seed : SEEDS) {
            ThemePalette p = ThemePaletteProvider.light(seed);
            int[] levels = {p.surfaceContainerLowest, p.surfaceContainerLow, p.surfaceContainer,
                    p.surfaceContainerHigh, p.surfaceContainerHighest};
            assertEquals("五档容器必须至少有 4 个不同色值（seed " + Integer.toHexString(seed) + "）",
                    5, (int) Arrays.stream(levels).distinct().count());
            for (int i = 1; i < levels.length; i++) {
                assertTrue("浅色：容器越高越暗（seed " + Integer.toHexString(seed) + "）",
                        luminance(levels[i - 1]) >= luminance(levels[i]));
            }
            assertTrue("浅色：surfaceDim 不能比 surface 亮",
                    luminance(p.surfaceDim) <= luminance(p.surface));
            assertTrue("浅色：surfaceBright 不能比 surface 暗",
                    luminance(p.surfaceBright) >= luminance(p.surface));
            assertNotEquals("outline 与 outlineVariant 不能同值",
                    p.outline, p.outlineVariant);
            assertTrue("浅色边框三档：variant 最浅 > strong > outline 最深",
                    luminance(p.outlineVariant) > luminance(p.borderStrong)
                            && luminance(p.borderStrong) > luminance(p.outline));
            assertTrue("浅色文字三档：onSurfaceVariant 最深 < tertiary < outline",
                    luminance(p.onSurfaceVariant) < luminance(p.textTertiary)
                            && luminance(p.textTertiary) < luminance(p.outline));
            assertTrue("浅色：onSurface 必须是深色字",
                    luminance(p.onSurface) < luminance(p.surface));
            assertTrue("浅色：onPrimary 必须是浅色字",
                    luminance(p.onPrimary) > luminance(p.primary));
        }
    }

    @Test
    public void darkPaletteHasOrderedSurfaceHierarchy() {
        for (int seed : SEEDS) {
            ThemePalette p = ThemePaletteProvider.dark(seed);
            int[] levels = {p.surfaceContainerLowest, p.surfaceContainerLow, p.surfaceContainer,
                    p.surfaceContainerHigh, p.surfaceContainerHighest};
            assertEquals("五档容器必须至少有 4 个不同色值（seed " + Integer.toHexString(seed) + "）",
                    5, (int) Arrays.stream(levels).distinct().count());
            for (int i = 1; i < levels.length; i++) {
                assertTrue("深色：容器越高越亮（seed " + Integer.toHexString(seed) + "）",
                        luminance(levels[i - 1]) <= luminance(levels[i]));
            }
            assertTrue("深色：surfaceDim 不能比 surface 亮",
                    luminance(p.surfaceDim) <= luminance(p.surface));
            assertTrue("深色：surfaceBright 必须比 surface 亮",
                    luminance(p.surfaceBright) > luminance(p.surface));
            assertNotEquals("outline 与 outlineVariant 不能同值",
                    p.outline, p.outlineVariant);
            assertTrue("深色边框三档：variant 最暗 < strong < outline 最亮",
                    luminance(p.outlineVariant) < luminance(p.borderStrong)
                            && luminance(p.borderStrong) < luminance(p.outline));
            assertTrue("深色文字三档：onSurfaceVariant 最亮 > tertiary > outline",
                    luminance(p.onSurfaceVariant) > luminance(p.textTertiary)
                            && luminance(p.textTertiary) > luminance(p.outline));
            assertTrue("深色：onSurface 必须是浅色字",
                    luminance(p.onSurface) > luminance(p.surface));
        }
    }

    @Test
    public void generatedOnColorsKeepTextDirection() {
        for (int seed : SEEDS) {
            ThemePalette light = ThemePaletteProvider.light(seed);
            assertTrue("浅色：onPrimary 压主色应比主色亮",
                    luminance(light.onPrimary) > luminance(light.primary));
            assertTrue("浅色：onError 压 error 应比 error 亮",
                    luminance(light.onError) > luminance(light.error));

            ThemePalette dark = ThemePaletteProvider.dark(seed);
            assertTrue("深色：onPrimary 压主色应比主色暗",
                    luminance(dark.onPrimary) < luminance(dark.primary));
            assertTrue("深色：onError 压 error 应比 error 暗",
                    luminance(dark.onError) < luminance(dark.error));
        }
    }

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
