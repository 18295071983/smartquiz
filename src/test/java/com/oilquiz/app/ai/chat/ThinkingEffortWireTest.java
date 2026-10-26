package com.oilquiz.app.ai.chat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

/**
 * 思考强度档位的取值解析与显示名 —— 钉住"档位按服务商/模型分别处理，不再被全局枚举折叠"。
 *
 * <p><b>背景</b>：各家 {@code effort.supported_levels} 集合互不相同（DeepSeek low/high/max、
 * OpenAI minimal/low/medium/high/xhigh、DashScope 另有 xhigh/max、Kimi low/medium/high/max）。
 * 旧实现里档位字段是 {@code ThinkingEffort} 枚举且 {@code fromWire} 对认不出的取值
 * <b>返回 HIGH</b>，于是 {@code medium/xhigh/minimal} 在读取时就被折叠成"高"：
 * 选择器出现重复项、用户点不到中档、请求里也永远发不出这些值。</p>
 *
 * <p>这里只测纯静态口径（不需要 Context / SharedPreferences）。</p>
 */
public class ThinkingEffortWireTest {

    private static final ChatModeManager.ThinkingEffort NONE =
            ChatModeManager.ThinkingEffort.NONE;
    private static final ChatModeManager.ThinkingEffort LOW =
            ChatModeManager.ThinkingEffort.LOW;
    private static final ChatModeManager.ThinkingEffort MEDIUM =
            ChatModeManager.ThinkingEffort.MEDIUM;
    private static final ChatModeManager.ThinkingEffort HIGH =
            ChatModeManager.ThinkingEffort.HIGH;
    private static final ChatModeManager.ThinkingEffort MAX =
            ChatModeManager.ThinkingEffort.MAX;

    @Test
    public void otherProvidersLevelsNoLongerFoldIntoHigh() {
        // 这三档此前都不在枚举里，fromWire 会把它们统一变成 HIGH
        assertEquals(MEDIUM, ChatModeManager.ThinkingEffort.fromWireOrNull("medium"));
        assertEquals(ChatModeManager.ThinkingEffort.XHIGH,
                ChatModeManager.ThinkingEffort.fromWireOrNull("xhigh"));
        assertEquals(ChatModeManager.ThinkingEffort.MINIMAL,
                ChatModeManager.ThinkingEffort.fromWireOrNull("minimal"));
        // 大小写与空格不能影响判定（各家写法不一）
        assertEquals(LOW, ChatModeManager.ThinkingEffort.fromWireOrNull(" LOW "));
        assertEquals(MAX, ChatModeManager.ThinkingEffort.fromWireOrNull("Max"));
    }

    @Test
    public void strictResolveReturnsNullInsteadOfGuessing() {
        // 私有取值（如某家的 auto）必须"承认不认识"，由 wire 通道原样透传，
        // 而不是替用户猜一个档位
        assertNull(ChatModeManager.ThinkingEffort.fromWireOrNull("auto"));
        assertNull(ChatModeManager.ThinkingEffort.fromWireOrNull(null));
        assertNull(ChatModeManager.ThinkingEffort.fromWireOrNull("   "));
        // 兼容入口仍然回落 HIGH（旧调用方语义不变）—— 这条同时钉住"别用兼容入口做判定"
        assertEquals(HIGH, ChatModeManager.ThinkingEffort.fromWire("auto"));
    }

    @Test
    public void displayNamesAreKnownInChineseAndUnknownPassthrough() {
        assertEquals("中", ChatModeManager.ThinkingEffort.displayNameForWire("medium"));
        assertEquals("极高", ChatModeManager.ThinkingEffort.displayNameForWire("xhigh"));
        assertEquals("极低", ChatModeManager.ThinkingEffort.displayNameForWire("minimal"));
        assertEquals("关闭", ChatModeManager.ThinkingEffort.displayNameForWire("none"));
        // 未知取值原样显示，界面不会把它改名或隐藏
        assertEquals("auto", ChatModeManager.ThinkingEffort.displayNameForWire("auto"));
        // 空白按官方默认 high 显示（与 getThinkingEffortWire 的默认一致）
        assertEquals("高", ChatModeManager.ThinkingEffort.displayNameForWire(null));
        assertEquals("高", ChatModeManager.ThinkingEffort.displayNameForWire(" "));
    }

    @Test
    public void wireValuesAreUniqueSoThePickerCannotShowDuplicates() {
        ChatModeManager.ThinkingEffort[] all = ChatModeManager.ThinkingEffort.values();
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                assertNotEquals("wire 值重复会让选择器出现同义项：" + all[i] + " vs " + all[j],
                        all[i].wireValue, all[j].wireValue);
                assertNotEquals("显示名重复会让用户分不清选的是哪一档",
                        all[i].displayName, all[j].displayName);
            }
            assertNotNull(all[i].wireValue);
        }
    }

    @Test
    public void noneIsTheOnlyDisabledLevel() {
        // 开关态由 wire 派生：除 none 外都算开启 —— 私有取值也必须能开启思考
        assertEquals(false, NONE.isEnabled());
        assertEquals(true, MEDIUM.isEnabled());
        assertEquals(true, ChatModeManager.ThinkingEffort.XHIGH.isEnabled());
    }
}
