package com.oilquiz.app.ai.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * {@code guide_config_json} feature flag（PoC 档，2026-10-28）。
 *
 * <p>钉三条语义，写法对齐 {@code PartsRendererFlagTest}：<b>无键＝关</b>（接管路径未真机验收，
 * 出厂维持 builder）；<b>显式写 true 才用配置</b>（产品里没有设置页入口，prefs 是唯一开关途径，
 * 所以这条必须测到）；<b>拿不到 Context 也按默认档</b>（不特殊化成"读不到就开"）。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ToolGuideFlowFlagTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /** 清掉键回到出厂态（本类有用例会写键，不显式清就测不到默认档）。 */
    private void removeKey() {
        ctx().getSharedPreferences(ToolGuideFlowFlag.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(ToolGuideFlowFlag.PREF_KEY).commit();
    }

    @Test
    public void defaultsToDisabledWhenKeyAbsent() {
        removeKey();
        assertFalse("出厂档＝关：无键仍走硬编码 builder", ToolGuideFlowFlag.enabled(ctx()));
    }

    @Test
    public void explicitTrueIsTheOnlyWayInAndExplicitFalseGetsItBackOff() {
        ToolGuideFlowFlag.setEnabled(ctx(), true);
        assertTrue("显式写 true 才允许配置接管", ToolGuideFlowFlag.enabled(ctx()));
        ToolGuideFlowFlag.setEnabled(ctx(), false);
        assertFalse(ToolGuideFlowFlag.enabled(ctx()));
        removeKey();
    }

    @Test
    public void missingContextFallsBackToDefaultDisabled() {
        assertFalse(ToolGuideFlowFlag.enabled(null));
        // 写也写不进去，不抛
        ToolGuideFlowFlag.setEnabled(null, true);
        assertFalse(ToolGuideFlowFlag.enabled(null));
    }

    @Test
    public void flagIsReadFromTheSharedChatPrefsNamespace() {
        // 与 AIChatActivity 用的 ai_chat_prefs 同一个命名空间，真机 run-as 读一份文件就能核全部开关
        assertEquals("ai_chat_prefs", ToolGuideFlowFlag.PREFS_NAME);
        assertEquals("guide_config_json", ToolGuideFlowFlag.PREF_KEY);
        assertFalse("默认档必须是关", ToolGuideFlowFlag.DEFAULT_ENABLED);
    }
}
