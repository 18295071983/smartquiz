package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * parts_renderer feature flag（P2 建开关，P4 于 2026-10-24 翻出厂档）。
 *
 * <p>钉住三条语义：<b>无键＝开</b>（接管链路已验收，出厂即生效）；<b>显式写 false 才回 legacy</b>
 * （产品里没有设置页入口，prefs 是唯一退路，所以这条必须测）；<b>拿不到 Context 也按默认档</b>
 * （与 {@link com.oilquiz.app.ai.chat.component.HtmlSubmitFlag} 同写法，不特殊化成"读不到就关"）。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class PartsRendererFlagTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /** 清掉键，回到"出厂态"（本类几个用例会写键，必须显式清才测得到默认档）。 */
    private void removeKey() {
        ctx().getSharedPreferences(PartsRendererFlag.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(PartsRendererFlag.PREF_KEY).commit();
    }

    @Test
    public void defaultsToEnabledWhenKeyAbsent() {
        removeKey();
        assertTrue("出厂档＝开：无键就走 parts 接管", PartsRendererFlag.enabled(ctx()));
    }

    @Test
    public void explicitFalseIsHonoured() {
        PartsRendererFlag.setEnabled(ctx(), false);
        assertFalse("显式写 false 是唯一的退回 legacy 途径", PartsRendererFlag.enabled(ctx()));
        PartsRendererFlag.setEnabled(ctx(), true);
        assertTrue(PartsRendererFlag.enabled(ctx()));
        removeKey();
    }

    @Test
    public void missingContextFallsBackToDefaultEnabled() {
        assertTrue(PartsRendererFlag.enabled(null));
    }
}
