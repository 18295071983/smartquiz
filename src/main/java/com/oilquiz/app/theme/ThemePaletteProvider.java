package com.oilquiz.app.theme;

import com.oilquiz.app.theme.mcu.hct.Hct;
import com.oilquiz.app.theme.mcu.scheme.Scheme;

/**
 * 主题色板生成器：基于 Google material-color-utilities（Apache-2.0）的 HCT 色彩空间，
 * 从任意主色生成一套完整的 Material 3 语义色板（浅色/深色）。
 *
 * <p>扩展点：{@link #fromHct(Hct, boolean)} 接受 HCT 颜色对象直接生成色板，
 * 供未来「壁纸动态取色」（WallpaperManager → HCT）、相机取色等场景接入——
 * 只需将取到的颜色转为 {@link Hct} 即可复用整套色板引擎。
 */
public final class ThemePaletteProvider {

    private ThemePaletteProvider() {
    }

    /** 生成浅色色板 */
    public static ThemePalette light(int argb) {
        return new ThemePalette(Scheme.light(argb));
    }

    /** 生成深色色板 */
    public static ThemePalette dark(int argb) {
        return new ThemePalette(Scheme.dark(argb));
    }

    /** 按深浅模式生成色板 */
    public static ThemePalette generate(int argb, boolean dark) {
        return dark ? dark(argb) : light(argb);
    }

    /**
     * 扩展点：从 HCT 颜色对象直接生成色板（壁纸取色 / 动态取色的统一入口）。
     *
     * @param hct  HCT 色彩对象（可来自 {@code Hct.fromInt(argb)} 或壁纸主色提取）
     * @param dark 是否深色色板
     */
    public static ThemePalette fromHct(Hct hct, boolean dark) {
        return dark ? dark(hct.toInt()) : light(hct.toInt());
    }
}
