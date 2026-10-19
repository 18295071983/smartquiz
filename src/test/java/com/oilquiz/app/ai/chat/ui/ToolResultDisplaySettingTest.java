package com.oilquiz.app.ai.chat.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.component.ComponentDisplay;

import org.junit.Test;

/**
 * 「工具结果显示」设置项的口径（2026-10-20）。
 *
 * <p>设置页与渲染端必须说同一套话：档位顺序、pref 字符串、以及"交互类不受影响"这条承诺。
 * 这里钉的是纯函数部分（弹窗/写 pref 需要 Activity，交给真机）。
 */
public class ToolResultDisplaySettingTest {

    @Test
    public void choicesCoverEveryModeAndRoundTripThroughPrefName() {
        ComponentDisplay.Mode[] modes = ToolResultDisplaySetting.choices();
        assertEquals(3, modes.length);
        for (ComponentDisplay.Mode m : modes) {
            // 设置页写出去的名字必须能被 ComponentDisplay 读回同一个档位
            assertEquals(m, ComponentDisplay.parse(ComponentDisplay.name(m), null));
            assertNotEquals("", ToolResultDisplaySetting.label(m));
        }
        // 顺序是"信息最全 → 最克制"，默认档必须排在中间那格
        assertEquals(ComponentDisplay.Mode.INLINE, modes[0]);
        assertEquals(ComponentDisplay.Mode.PEEK, modes[1]);
        assertEquals(ComponentDisplay.Mode.HIDDEN, modes[2]);
    }

    @Test
    public void labelsAreDistinctAndStable() {
        assertEquals("整卡展开", ToolResultDisplaySetting.label(ComponentDisplay.Mode.INLINE));
        assertEquals("摘要一行", ToolResultDisplaySetting.label(ComponentDisplay.Mode.PEEK));
        assertEquals("不显示卡片", ToolResultDisplaySetting.label(ComponentDisplay.Mode.HIDDEN));
    }

    /** 读不到 pref（单测/异常路径）时按钮要说清默认档，不能是空标签 */
    @Test
    public void buttonLabelFallsBackToPeekWithoutContext() {
        assertEquals("🖼️ 摘要一行", ToolResultDisplaySetting.buttonLabel(null));
    }

    /**
     * 说明文案不许与渲染端底线打架：{@code ComponentDisplay} 会把交互类/正文类强制 inline，
     * 所以"不显示卡片"这一档必须写明例外，否则用户以为按下去就一张卡都不剩。
     */
    @Test
    public void hiddenTierDescriptionKeepsTheAlwaysInlinePromise() {
        String desc = ToolResultDisplaySetting.description(ComponentDisplay.Mode.HIDDEN);
        assertTrue("hidden 档说明必须交代交互/正文类仍显示: " + desc, desc.contains("仍强制显示"));
        assertTrue("peek 档说明必须交代交互/正文类不受影响: "
                        + ToolResultDisplaySetting.description(ComponentDisplay.Mode.PEEK),
                ToolResultDisplaySetting.description(ComponentDisplay.Mode.PEEK).contains("不受影响"));
    }
}
