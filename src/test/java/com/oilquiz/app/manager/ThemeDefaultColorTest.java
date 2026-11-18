package com.oilquiz.app.manager;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 默认主题色护栏（2026-11-18）。
 *
 * <p>产品口径：主题设置页默认选中<b>蓝色</b>，不改任何色值。
 * 这里把三条口径钉住：</p>
 * <ol>
 *   <li>全新安装 / 没有存储时 → 蓝色；</li>
 *   <li>旧版本"默认紫但用户没手动选过色" → 一次性迁到蓝色；</li>
 *   <li>用户手动选过紫色（picked=true）→ 保持紫色，不被默认值覆盖。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
public class ThemeDefaultColorTest {

    private static final String PREF_NAME = "theme_preferences";
    private static final String KEY_VALUE = "current_theme_color_value";
    private static final String KEY_PICKED = "theme_color_picked";

    @Test
    public void freshInstallDefaultsToBlue() {
        Context ctx = contextWithCleanPrefs();
        assertEquals("没选过主题色时默认必须是蓝色",
                ThemeManager.COLOR_BLUE, ThemeManager.getThemeColor(ctx));
    }

    @Test
    public void legacyDefaultPurpleMigratesToBlueOnce() {
        Context ctx = contextWithCleanPrefs();
        prefs(ctx).edit().putInt(KEY_VALUE, ThemeManager.COLOR_PURPLE).commit();

        assertEquals("旧版本默认紫且没有 picked 标记 → 迁到默认蓝",
                ThemeManager.COLOR_BLUE, ThemeManager.getThemeColor(ctx));
        assertEquals("迁移后存储里也必须是蓝色，避免下次又读回紫",
                ThemeManager.COLOR_BLUE, prefs(ctx).getInt(KEY_VALUE, 0));
    }

    @Test
    public void explicitPurpleChoiceIsKept() {
        Context ctx = contextWithCleanPrefs();
        ThemeManager.setThemeColor(ctx, ThemeManager.COLOR_PURPLE);

        assertEquals("用户手动选过紫色，必须保持紫色",
                ThemeManager.COLOR_PURPLE, ThemeManager.getThemeColor(ctx));
    }

    @Test
    public void standardSkinResetsToBlue() {
        Context ctx = contextWithCleanPrefs();
        ThemeManager.setThemeColor(ctx, ThemeManager.COLOR_GREEN);
        ThemeManager.setSkin(ctx, "standard");

        assertEquals("标准皮肤默认色是蓝色",
                ThemeManager.COLOR_BLUE, ThemeManager.getThemeColor(ctx));
    }

    private static Context contextWithCleanPrefs() {
        Context ctx = ApplicationProvider.getApplicationContext();
        prefs(ctx).edit().clear().commit();
        return ctx;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
}
