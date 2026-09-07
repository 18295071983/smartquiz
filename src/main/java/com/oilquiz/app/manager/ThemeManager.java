package com.oilquiz.app.manager;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.res.ResourcesCompat;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemePalette;
import com.oilquiz.app.theme.ThemePaletteProvider;
import com.oilquiz.app.theme.ThemePreset;
import com.oilquiz.app.theme.ThemeSkin;
import com.oilquiz.app.theme.mcu.hct.Hct;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 主题系统统一入口（重构版）。
 *
 * <p>职责：
 * <ul>
 *   <li>深浅模式三档（浅色/深色/跟随系统）——统一走 AppCompatDelegate + DayNight 资源；</li>
 *   <li>主题色（7 预设色 + 自定义色）——基于 Google material-color-utilities HCT 色板；</li>
 *   <li>全局注入 ThemeOverlay（由 Application 的 onActivityPreCreated 调用），
 *       使所有页面在 setContentView 之前应用动态主题，切换即时生效、无需重启；</li>
 *   <li>预设色注册表 {@link #PRESETS}（单一数据源，新增预设色只改此处 + 重跑生成器）；</li>
 *   <li>主题变更监听 {@link OnThemeChangedListener}（供 Compose 页面 / Service / 运行中组件
 *       在不重建 Activity 的情况下感知主题切换）；</li>
 *   <li>兼容迁移旧存储（current_theme_color 存 resId → 新 current_theme_color_value 存 ARGB）。</li>
 * </ul>
 */
public class ThemeManager {

    /** 主题变更监听器：mode=深浅模式，themeColor=当前主题色(ARGB)，custom=是否自定义色 */
    public interface OnThemeChangedListener {
        void onThemeChanged(int mode, int themeColor, boolean custom);
    }

    private static final String PREF_NAME = "theme_preferences";
    private static final String KEY_THEME = "current_theme";
    /** 新存储：主题色 ARGB int */
    private static final String KEY_THEME_COLOR_VALUE = "current_theme_color_value";
    /** 旧存储：主题色资源 id（仅迁移用） */
    private static final String KEY_THEME_COLOR_RES = "current_theme_color";
    private static final String KEY_USE_CUSTOM_COLOR = "use_custom_color";
    private static final String KEY_CUSTOM_THEME_COLOR = "custom_theme_color";
    /** 皮肤包 id */
    private static final String KEY_SKIN = "current_skin";
    /** 皮肤包默认值 */
    private static final String DEFAULT_SKIN_ID = "standard";

    public static final int THEME_LIGHT = 0;
    public static final int THEME_DARK = 1;
    public static final int THEME_SYSTEM = 2;

    /** 预设主题色（ARGB，与 res/values/theme_overlays.xml 生成脚本一致） */
    public static final int COLOR_BLUE = 0xFF3B82F6;
    public static final int COLOR_GREEN = 0xFF10B981;
    public static final int COLOR_PURPLE = 0xFF8B5CF6;
    public static final int COLOR_ORANGE = 0xFFF97316;
    public static final int COLOR_PINK = 0xFFEC4899;
    public static final int COLOR_TEAL = 0xFF14B8A6;
    public static final int COLOR_INDIGO = 0xFF6366F1;

    /**
     * 预设色注册表（单一数据源）。
     *
     * <p>新增预设色：在此追加一条记录，然后重跑 tools/GenOverlays.java
     * 生成对应 ThemeOverlay（浅/深两套），重新编译即可。UI 层只遍历本列表，
     * 无需修改任何页面代码。
     */
    public static final List<ThemePreset> PRESETS = List.of(
            new ThemePreset("blue", "蓝色", COLOR_BLUE, R.style.ThemeOverlay_SmartQuiz_Blue),
            new ThemePreset("green", "绿色", COLOR_GREEN, R.style.ThemeOverlay_SmartQuiz_Green),
            new ThemePreset("purple", "紫色", COLOR_PURPLE, R.style.ThemeOverlay_SmartQuiz_Purple),
            new ThemePreset("orange", "橙色", COLOR_ORANGE, R.style.ThemeOverlay_SmartQuiz_Orange),
            new ThemePreset("pink", "粉色", COLOR_PINK, R.style.ThemeOverlay_SmartQuiz_Pink),
            new ThemePreset("teal", "青色", COLOR_TEAL, R.style.ThemeOverlay_SmartQuiz_Teal),
            new ThemePreset("indigo", "靛蓝", COLOR_INDIGO, R.style.ThemeOverlay_SmartQuiz_Indigo)
    );

    /** 主题变更监听器集合（CopyOnWrite，支持任意线程安全注册/注销） */
    private static final CopyOnWriteArrayList<OnThemeChangedListener> THEME_CHANGE_LISTENERS =
            new CopyOnWriteArrayList<>();

    /**
     * 24 色相网格 overlay（每 15° 一档，由 GenOverlays.java 生成）。
     * 自定义色 / 皮肤扩展色就近映射到最近色相，实现 XML 体系全量变色。
     */
    private static final int[] HUE_OVERLAY_RES_IDS = {
            R.style.ThemeOverlay_SmartQuiz_Hue00, R.style.ThemeOverlay_SmartQuiz_Hue01,
            R.style.ThemeOverlay_SmartQuiz_Hue02, R.style.ThemeOverlay_SmartQuiz_Hue03,
            R.style.ThemeOverlay_SmartQuiz_Hue04, R.style.ThemeOverlay_SmartQuiz_Hue05,
            R.style.ThemeOverlay_SmartQuiz_Hue06, R.style.ThemeOverlay_SmartQuiz_Hue07,
            R.style.ThemeOverlay_SmartQuiz_Hue08, R.style.ThemeOverlay_SmartQuiz_Hue09,
            R.style.ThemeOverlay_SmartQuiz_Hue10, R.style.ThemeOverlay_SmartQuiz_Hue11,
            R.style.ThemeOverlay_SmartQuiz_Hue12, R.style.ThemeOverlay_SmartQuiz_Hue13,
            R.style.ThemeOverlay_SmartQuiz_Hue14, R.style.ThemeOverlay_SmartQuiz_Hue15,
            R.style.ThemeOverlay_SmartQuiz_Hue16, R.style.ThemeOverlay_SmartQuiz_Hue17,
            R.style.ThemeOverlay_SmartQuiz_Hue18, R.style.ThemeOverlay_SmartQuiz_Hue19,
            R.style.ThemeOverlay_SmartQuiz_Hue20, R.style.ThemeOverlay_SmartQuiz_Hue21,
            R.style.ThemeOverlay_SmartQuiz_Hue22, R.style.ThemeOverlay_SmartQuiz_Hue23
    };

    /** 皮肤包注册表（多品牌皮肤）：新增皮肤在此追加一条记录即可 */
    public static final List<ThemeSkin> SKINS = List.of(
            new ThemeSkin("standard", "标准",
                    PRESETS,
                    COLOR_BLUE),
            new ThemeSkin("military", "军旅",
                    List.of(
                            new ThemePreset("military_green", "军绿", 0xFF4A7C59, 0),
                            new ThemePreset("sand", "沙褐", 0xFFB08D57, 0),
                            new ThemePreset("navy", "深蓝", 0xFF1F3A5F, 0),
                            new ThemePreset("charcoal", "炭黑", 0xFF455A64, 0)),
                    0xFF4A7C59),
            new ThemeSkin("campus", "校园",
                    List.of(
                            new ThemePreset("vivid_orange", "活力橙", 0xFFFF7043, 0),
                            new ThemePreset("sky_blue", "天空蓝", 0xFF42A5F5, 0),
                            new ThemePreset("grass_green", "草绿", 0xFF66BB6A, 0),
                            new ThemePreset("sakura_pink", "樱粉", 0xFFF48FB1, 0)),
                    0xFFFF7043)
    );

    private static final Map<String, ThemePalette> PALETTE_CACHE = new HashMap<>();

    // ---------------------------------------------------------------------
    // 预设色注册表
    // ---------------------------------------------------------------------

    /** 全部预设色（不可变），UI 层遍历此列表渲染色块 */
    public static List<ThemePreset> getPresets() {
        return PRESETS;
    }

    /** 按主色精确查找预设色；未命中（自定义色/未知色）返回 null */
    public static ThemePreset getPresetByColor(int argb) {
        for (ThemePreset preset : PRESETS) {
            if (preset.argb == argb) {
                return preset;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // 皮肤包（多品牌皮肤）
    // ---------------------------------------------------------------------

    /** 全部皮肤包（不可变），主题页皮肤区遍历此列表渲染 */
    public static List<ThemeSkin> getSkins() {
        return SKINS;
    }

    /** 按 id 查找皮肤包；未命中返回 null */
    public static ThemeSkin getSkinById(String skinId) {
        for (ThemeSkin skin : SKINS) {
            if (skin.id.equals(skinId)) {
                return skin;
            }
        }
        return null;
    }

    /** 当前皮肤包（默认"标准"；存储异常自动回退） */
    public static ThemeSkin getSkin(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        ThemeSkin skin = getSkinById(prefs.getString(KEY_SKIN, DEFAULT_SKIN_ID));
        return skin != null ? skin : SKINS.get(0);
    }

    /**
     * 切换皮肤包：保存皮肤 + 主色重置为该皮肤默认色 + 关闭自定义色
     * （切皮肤 = 用户手动选择配色，覆盖动态/自定义状态）+ 清色板缓存 + 通知监听。
     * Activity 场景由调用方 recreate 使 overlay 立即生效；Compose 等场景由监听自动刷新。
     */
    public static void setSkin(Context context, String skinId) {
        ThemeSkin skin = getSkinById(skinId);
        if (skin == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_SKIN, skin.id)
                .putInt(KEY_THEME_COLOR_VALUE, skin.defaultArgb)
                .putBoolean(KEY_USE_CUSTOM_COLOR, false)
                .apply();
        PALETTE_CACHE.clear();
        notifyThemeChanged(context);
    }

    // ---------------------------------------------------------------------
    // 色相网格（自定义色 / 皮肤扩展色 → XML 全量变色）
    // ---------------------------------------------------------------------

    /**
     * 任意颜色就近映射到 24 色相网格 overlay（HCT 感知色相，15° 一档）。
     * 使自定义色与皮肤扩展色在 XML 体系（?attr/colorPrimary 等）全量生效。
     */
    public static int getHueOverlayResId(int argb) {
        double hue = Hct.fromInt(argb).getHue(); // 0..360（感知均匀色相）
        int idx = (int) Math.round(hue / (360.0 / HUE_OVERLAY_RES_IDS.length)) % HUE_OVERLAY_RES_IDS.length;
        return HUE_OVERLAY_RES_IDS[idx];
    }

    // ---------------------------------------------------------------------
    // 主题变更监听
    // ---------------------------------------------------------------------

    /** 注册主题变更监听（可在任意线程调用；重复注册自动去重） */
    public static void registerThemeChangedListener(OnThemeChangedListener listener) {
        if (listener != null) {
            THEME_CHANGE_LISTENERS.addIfAbsent(listener);
        }
    }

    /** 注销主题变更监听 */
    public static void unregisterThemeChangedListener(OnThemeChangedListener listener) {
        THEME_CHANGE_LISTENERS.remove(listener);
    }

    /**
     * 通知全部主题变更监听（供外部组件触发刷新）。
     * 普通场景无需手动调用——setMode/setThemeColor/setCustomThemeColor/setSkin 已自动通知。
     */
    public static void notifyThemeChangedPublic(Context context) {
        notifyThemeChanged(context);
    }

    private static void notifyThemeChanged(Context context) {
        int mode = getMode(context);
        int color = getThemeColor(context);
        boolean custom = isCustomColor(context);
        for (OnThemeChangedListener listener : THEME_CHANGE_LISTENERS) {
            try {
                listener.onThemeChanged(mode, color, custom);
            } catch (Throwable ignored) {
                // 单个监听器异常不影响其它监听器
            }
        }
    }

    // ---------------------------------------------------------------------
    // 深浅模式
    // ---------------------------------------------------------------------

    public static int getMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_THEME, THEME_SYSTEM);
    }

    /** 设置深浅模式并立即作用于 AppCompat（AppCompat 会自动重建界面）；通知主题变更监听 */
    public static void setMode(Context context, int mode) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_THEME, mode).apply();
        applyNightMode(context);
        notifyThemeChanged(context);
    }

    /** 应用当前保存的深浅模式到 AppCompat（Application 启动时调用） */
    public static void applyNightMode(Context context) {
        switch (getMode(context)) {
            case THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case THEME_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case THEME_SYSTEM:
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    /** 当前是否处于深色显示（含跟随系统时的实际 uiMode） */
    public static boolean isDarkTheme(Context context) {
        int mode = getMode(context);
        if (mode == THEME_LIGHT) {
            return false;
        }
        if (mode == THEME_DARK) {
            return true;
        }
        int uiMode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == Configuration.UI_MODE_NIGHT_YES;
    }

    // ---------------------------------------------------------------------
    // 主题色
    // ---------------------------------------------------------------------

    /**
     * 当前主题色（ARGB）。优先级：自定义色 → 预设色；
     * 兼容迁移旧 resId 存储。
     */
    public static int getThemeColor(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        if (prefs.getBoolean(KEY_USE_CUSTOM_COLOR, false)) {
            int custom = prefs.getInt(KEY_CUSTOM_THEME_COLOR, -1);
            if (custom != -1) {
                return custom;
            }
        }

        if (prefs.contains(KEY_THEME_COLOR_VALUE)) {
            return prefs.getInt(KEY_THEME_COLOR_VALUE, COLOR_PURPLE);
        }

        // 旧版本：current_theme_color 存的是资源 id，迁移为 ARGB
        int resId = prefs.getInt(KEY_THEME_COLOR_RES, R.color.theme_blue);
        int argb = ResourcesCompat.getColor(context.getResources(), resId, context.getTheme());
        prefs.edit().putInt(KEY_THEME_COLOR_VALUE, argb).apply();
        return argb;
    }

    /** 是否使用系统动态色（Material You 系统控件主题，控件配色跟随系统壁纸） */
    private static final String KEY_USE_SYSTEM_DYNAMIC = "use_system_dynamic_color";

    public static boolean isSystemDynamicColor(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_USE_SYSTEM_DYNAMIC, false);
    }

    /** 开关系统动态色：开启后控件配色由系统 Material You 接管（自定义色/预设色暂不生效） */
    public static void setSystemDynamicColor(Context context, boolean enabled) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_USE_SYSTEM_DYNAMIC, enabled).apply();
        PALETTE_CACHE.clear();
        notifyThemeChanged(context);
    }

    /** 是否使用了自定义主题色 */
    public static boolean isCustomColor(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_USE_CUSTOM_COLOR, false)
                && prefs.getInt(KEY_CUSTOM_THEME_COLOR, -1) != -1;
    }

    /** 选择预设主题色（ARGB）；手动选择会关闭自定义色；通知主题变更监听 */
    public static void setThemeColor(Context context, int argb) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putInt(KEY_THEME_COLOR_VALUE, argb)
                .putBoolean(KEY_USE_CUSTOM_COLOR, false)
                .apply();
        notifyThemeChanged(context);
    }

    /** 设置自定义主题色（ARGB）；设置自定义主题色；通知主题变更监听 */
    public static void setCustomThemeColor(Context context, int argb) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putInt(KEY_CUSTOM_THEME_COLOR, argb)
                .putBoolean(KEY_USE_CUSTOM_COLOR, true)
                .apply();
        notifyThemeChanged(context);
    }

    /** 兼容旧调用：当前主题色对应的资源 id（自定义色返回默认紫色） */
    public static int getThemeColorRes(Context context) {
        switch (getThemeColor(context)) {
            case COLOR_BLUE:
                return R.color.theme_blue;
            case COLOR_GREEN:
                return R.color.theme_green;
            case COLOR_PURPLE:
                return R.color.theme_purple;
            case COLOR_ORANGE:
                return R.color.theme_orange;
            case COLOR_PINK:
                return R.color.theme_pink;
            case COLOR_TEAL:
                return R.color.theme_teal;
            case COLOR_INDIGO:
                return R.color.theme_indigo;
            default:
                return R.color.theme_purple;
        }
    }

    // ---------------------------------------------------------------------
    // 动态色板
    // ---------------------------------------------------------------------

    /** 获取当前主题色对应的完整 M3 色板（带缓存） */
    public static ThemePalette getPalette(Context context, boolean dark) {
        int color = getThemeColor(context);
        String key = color + "_" + dark;
        ThemePalette cached = PALETTE_CACHE.get(key);
        if (cached == null) {
            cached = ThemePaletteProvider.generate(color, dark);
            PALETTE_CACHE.put(key, cached);
        }
        return cached;
    }

    /** 清除色板缓存（切换主题色后调用） */
    public static void clearPaletteCache() {
        PALETTE_CACHE.clear();
    }

    // ---------------------------------------------------------------------
    // 全局 overlay 应用
    // ---------------------------------------------------------------------

    /**
     * 当前主题色对应的静态 ThemeOverlay 资源：
     * <ol>
     *   <li>精确预设色（ThemePreset.overlayResId != 0）→ 使用精确 overlay；</li>
     *   <li>自定义色 / 皮肤扩展色（overlayResId == 0）→ 就近映射 24 色相网格，
     *       XML 体系（?attr/colorPrimary 等）同样全量变色。</li>
     * </ol>
     * overlay 由 Google material-color-utilities HCT 算法离线生成（values/theme_overlays.xml）。
     */
    public static int getOverlayResId(Context context) {
        int color = getThemeColor(context);
        ThemePreset preset = getPresetByColor(color);
        if (preset != null && preset.overlayResId != 0) {
            return preset.overlayResId;
        }
        return getHueOverlayResId(color);
    }

    /**
     * 将动态主题注入 Activity 主题（必须在 setContentView 之前调用）。
     * 由 SmartQuizApplication 的 onActivityPreCreated 统一调用，无需各 Activity 处理。
     */
    public static void applyThemeOverlay(Activity activity) {
        try {
            int overlayResId = getOverlayResId(activity);
            if (overlayResId != 0) {
                activity.getTheme().applyStyle(overlayResId, true);
            }
        } catch (Throwable t) {
            // 注入失败不阻断页面打开，回退默认紫色主题
            com.oilquiz.app.util.AILogger.w("ThemeManager",
                    "applyThemeOverlay failed: " + t.getMessage());
        }
    }
}
