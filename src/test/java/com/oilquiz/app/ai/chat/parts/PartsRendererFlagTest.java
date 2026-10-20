package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * parts_renderer feature flag（parts 架构升级 P2）：默认关，显式开才生效。
 */
@RunWith(RobolectricTestRunner.class)
public class PartsRendererFlagTest {

    @Test
    public void defaultsToDisabled() {
        Context ctx = ApplicationProvider.getApplicationContext();
        // 清掉可能残留的键，确认默认档为 false（P2 不改变现网行为）
        PartsRendererFlag.setEnabled(ctx, false);
        assertFalse(PartsRendererFlag.enabled(ctx));
    }

    @Test
    public void persistsWhenEnabled() {
        Context ctx = ApplicationProvider.getApplicationContext();
        PartsRendererFlag.setEnabled(ctx, true);
        assertTrue(PartsRendererFlag.enabled(ctx));
        PartsRendererFlag.setEnabled(ctx, false);
        assertFalse(PartsRendererFlag.enabled(ctx));
    }

    @Test
    public void nullContextIsDisabled() {
        assertFalse(PartsRendererFlag.enabled(null));
    }
}
