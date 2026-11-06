package com.oilquiz.app.theme;

import com.oilquiz.app.theme.mcu.hct.Hct;
import com.oilquiz.app.theme.mcu.palettes.TonalPalette;

/**
 * 主题色板生成器（项目自维护，2026-11-06）。
 *
 * <p>基于 HCT 色彩空间生成 Material 3 tonal-spot 色板；不再经过 vendored 的
 * {@code Scheme}/{@code CorePalette}。chroma 取值见 {@link #palettes(int)}，
 * 33 套 overlay 的 ARGB 由 {@code tools/GenOverlays.java} 用同一入口生成。</p>
 */
public final class ThemePaletteProvider {

    /** 主色 chroma 下限（旧值 48；提高后预设色更鲜明） */
    private static final double PRIMARY_MIN_CHROMA = 56.0;
    private static final double SECONDARY_CHROMA = 24.0;
    private static final double TERTIARY_CHROMA = 32.0;
    private static final double NEUTRAL_CHROMA = 6.0;
    private static final double NEUTRAL_VARIANT_CHROMA = 10.0;
    private static final double ERROR_HUE = 25.0;
    private static final double ERROR_CHROMA = 84.0;

    private ThemePaletteProvider() {
    }

    public static ThemePalette light(int argb) {
        return generate(argb, false);
    }

    public static ThemePalette dark(int argb) {
        return generate(argb, true);
    }

    public static ThemePalette generate(int argb, boolean dark) {
        TonalPalette[] p = palettes(argb);
        return new ThemePalette(p[0], p[1], p[2], p[3], p[4], p[5], dark);
    }

    /** 扩展点：从 HCT 颜色对象直接生成色板（壁纸取色 / 动态取色的统一入口）。 */
    public static ThemePalette fromHct(Hct hct, boolean dark) {
        return generate(hct.toInt(), dark);
    }

    /** [primary, secondary, tertiary, neutral, neutralVariant, error] */
    static TonalPalette[] palettes(int argb) {
        Hct hct = Hct.fromInt(argb);
        double hue = hct.getHue();
        double chroma = hct.getChroma();
        return new TonalPalette[]{
                TonalPalette.fromHueAndChroma(hue, Math.max(PRIMARY_MIN_CHROMA, chroma)),
                TonalPalette.fromHueAndChroma(hue, SECONDARY_CHROMA),
                TonalPalette.fromHueAndChroma(hue + 60.0, TERTIARY_CHROMA),
                TonalPalette.fromHueAndChroma(hue, NEUTRAL_CHROMA),
                TonalPalette.fromHueAndChroma(hue, NEUTRAL_VARIANT_CHROMA),
                TonalPalette.fromHueAndChroma(ERROR_HUE, ERROR_CHROMA)
        };
    }
}
