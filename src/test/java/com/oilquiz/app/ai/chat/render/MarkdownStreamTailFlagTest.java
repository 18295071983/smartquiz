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
 * <p>默认 true 是 2026-10-25 用户显式拍板；显式写 false 才是回退，且必须由 Activity 侧
 * 再与 {@code web_markdown_render} 相与，Markwon 路径不放开逐 tick 刷新。</p>
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
    public void defaultsToEnabledWhenKeyAbsent() {
        assertTrue("出厂档必须是开：无键就走轮内块级上屏",
                MarkdownStreamTailFlag.enabled(ctx()));
    }

    @Test
    public void explicitFalseAndTrueRoundTrip() {
        MarkdownStreamTailFlag.setEnabled(ctx(), false);
        assertFalse("显式 false 是回退途径", MarkdownStreamTailFlag.enabled(ctx()));

        MarkdownStreamTailFlag.setEnabled(ctx(), true);
        assertTrue(MarkdownStreamTailFlag.enabled(ctx()));
    }

    @Test
    public void missingContextFallsBackToEnabled() {
        assertTrue(MarkdownStreamTailFlag.enabled(null));
    }
}
