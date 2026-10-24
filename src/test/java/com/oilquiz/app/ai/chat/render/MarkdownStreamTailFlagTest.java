package com.oilquiz.app.ai.chat.render;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link MarkdownStreamTailFlag} 的出厂档与读写口径（轮内流式 P7）。
 *
 * <p>默认 false 是硬约束：不开 prefs 时，80ms 逐 tick 刷新与 WebView 块路径都不能生效；
 * 打开后也必须由 Activity 侧再与 {@code web_markdown_render} 相与。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class MarkdownStreamTailFlagTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void clearKey() {
        ctx().getSharedPreferences(MarkdownStreamTailFlag.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(MarkdownStreamTailFlag.PREF_KEY).commit();
    }

    @After
    public void tearDown() {
        clearKey();
    }

    @Test
    public void defaultsToDisabledWhenKeyAbsent() {
        assertFalse("出厂档必须是关：无键不能进块级流式",
                MarkdownStreamTailFlag.enabled(ctx()));
    }

    @Test
    public void explicitTrueAndFalseRoundTrip() {
        MarkdownStreamTailFlag.setEnabled(ctx(), true);
        assertTrue(MarkdownStreamTailFlag.enabled(ctx()));

        MarkdownStreamTailFlag.setEnabled(ctx(), false);
        assertFalse(MarkdownStreamTailFlag.enabled(ctx()));
    }

    @Test
    public void missingContextFallsBackToDisabled() {
        assertFalse(MarkdownStreamTailFlag.enabled(null));
    }
}
