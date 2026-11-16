package com.oilquiz.app.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * IME 标准（2026-11-16）。
 *
 * <p>本应用全部页面都是真 edge-to-edge（{@code decorFitsSystemWindows(false)}），
 * 该模式下系统不会自动给键盘让位——底部 inset 必须显式并入 {@code Type.ime()}，
 * 否则键盘会盖住输入框。这里钉住"系统栏 / 手势条 / 键盘取最大"的口径。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class EdgeToEdgeImeTest {

    @Test
    public void imeWinsWhenKeyboardIsTallerThanSystemBar() {
        WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 60, 0, 60))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 900))
                .build();
        assertEquals("键盘弹出时底部 inset 应取键盘高度", 900, EdgeToEdgeHelper.bottomInset(insets));
    }

    @Test
    public void systemBarUsedWhenKeyboardHidden() {
        WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 60, 0, 96))
                .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(0, 0, 0, 120))
                .build();
        assertEquals("键盘收起时底部 inset 应取系统栏/手势条最大值", 120, EdgeToEdgeHelper.bottomInset(insets));
    }

    @Test
    public void noInsetsMeansZero() {
        WindowInsetsCompat insets = new WindowInsetsCompat.Builder().build();
        assertEquals("没有 inset 时应为 0", 0, EdgeToEdgeHelper.bottomInset(insets));
    }

    @Test
    public void themeAsksForAdjustResizeSoImeInsetsAreDispatched() {
        // adjustResize 是 IME insets 分发的前提；写在主题里，所有 Activity 默认继承
        int res = com.oilquiz.app.R.style.Theme_OilQuizApp;
        assertTrue("主题应存在", res != 0);
        android.util.TypedValue tv = new android.util.TypedValue();
        android.content.Context ctx = com.oilquiz.app.testsupport.ThemedAppContext.get();
        assertTrue("主题里应配置 windowSoftInputMode",
                ctx.getTheme().resolveAttribute(android.R.attr.windowSoftInputMode, tv, true));
        assertEquals("windowSoftInputMode 应为 adjustResize",
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, tv.data);
    }
}
