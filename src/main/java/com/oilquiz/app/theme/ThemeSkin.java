package com.oilquiz.app.theme;

import java.util.List;

/**
 * 主题皮肤包（多品牌皮肤）。
 *
 * <p>一个皮肤 = 一套命名配色方案：自带预设色系 + 默认主色。
 * 切换皮肤时主色自动重置为该皮肤的 {@link #defaultArgb}，色板引擎（HCT）自动派生全套语义色。
 *
 * <p>新增皮肤只需在 {@link com.oilquiz.app.manager.ThemeManager#SKINS} 中追加一条记录；
 * 皮肤内预设色的 {@link ThemePreset#overlayResId} 传 0 时，自动走 24 色相网格近似映射，
 * 同样实现 XML 体系全量变色（无需生成精确 overlay）。
 */
public final class ThemeSkin {

    /** 皮肤唯一标识（英文小写，持久化用） */
    public final String id;

    /** 界面展示名（中文） */
    public final String displayName;

    /** 皮肤可用预设色（主题页颜色区遍历此列表渲染） */
    public final List<ThemePreset> presets;

    /** 皮肤默认主色（ARGB）；切换皮肤时主色重置为该值 */
    public final int defaultArgb;

    public ThemeSkin(String id, String displayName, List<ThemePreset> presets, int defaultArgb) {
        this.id = id;
        this.displayName = displayName;
        this.presets = presets;
        this.defaultArgb = defaultArgb;
    }
}
