package com.oilquiz.app.theme;

/**
 * 主题预设色描述 —— 预设色单一数据源。
 *
 * <p>新增预设色只需三处配合（详见 docs/主题系统重构说明.md「扩展指南」）：
 * <ol>
 *   <li>在 {@link com.oilquiz.app.manager.ThemeManager#PRESETS} 中追加一条记录；</li>
 *   <li>重跑 {@code tools/GenOverlays.java} 生成对应的 ThemeOverlay（浅/深两套）；</li>
 *   <li>重新编译。</li>
 * </ol>
 *
 * <p>展示名、色值、overlay 资源全部集中于此，UI 层（ThemeActivity 等）只遍历
 * {@code ThemeManager.getPresets()}，新增预设色无需再改任何页面代码。
 */
public final class ThemePreset {

    /** 唯一标识（英文小写，用于持久化键/日志/埋点） */
    public final String id;

    /** 界面展示名（中文） */
    public final String displayName;

    /** 主色（ARGB） */
    public final int argb;

    /** 静态 ThemeOverlay 资源 id（由 GenOverlays.java 生成）；自定义色场景为 0 */
    public final int overlayResId;

    public ThemePreset(String id, String displayName, int argb, int overlayResId) {
        this.id = id;
        this.displayName = displayName;
        this.argb = argb;
        this.overlayResId = overlayResId;
    }
}
