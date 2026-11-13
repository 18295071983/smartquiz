package com.oilquiz.app.ai.chat.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * "待你处理"指路牌（2026-11-13）。
 *
 * <p>审批/反问卡本身留在消息流（真值只有一份），插槽只放一个能定位到它的提示：
 * 几条、是什么、点一下跳过去。这里钉住纯函数契约。</p>
 */
public class PendingAttentionTest {

    @Test
    public void noPendingNoSlot() {
        assertNull(PendingAttention.slotOf(0, 0, "whatever"));
        assertNull("负数也不该产牌", PendingAttention.slotOf(-1, -2, null));
    }

    @Test
    public void approvalOnly() {
        DockSlotBus.Slot s = PendingAttention.slotOf(1, 0, "write_file");
        assertEquals(PendingAttention.SLOT_ID, s.id);
        assertEquals(PendingAttention.PRIORITY, s.priority);
        assertEquals("attention", s.action);
        assertTrue("待处理要醒目（warn 橙色）", s.warn);
        assertEquals("待你处理：审批 · write_file", s.title);
        assertEquals("1 条 · 点这里定位", s.detail);
    }

    @Test
    public void questionOnly() {
        DockSlotBus.Slot s = PendingAttention.slotOf(0, 2, "选哪个方案？");
        assertEquals("待你处理：提问 · 选哪个方案？", s.title);
        assertEquals("2 条 · 点这里定位", s.detail);
    }

    @Test
    public void mixedKindsAndWithoutLabel() {
        assertEquals("待你处理：审批与提问", PendingAttention.slotOf(1, 1, null).title);
        assertEquals("待你处理：审批与提问", PendingAttention.slotOf(1, 1, "  ").title);
        assertEquals("3 条 · 点这里定位", PendingAttention.slotOf(2, 1, "").detail);
    }

    @Test
    public void longLabelIsTruncated() {
        String longLabel = "这是一个非常非常非常长的工具名或者问题描述超过二十四个字符";
        String title = PendingAttention.slotOf(1, 0, longLabel).title;
        assertTrue("标题不能无限长：" + title, title.length() <= "待你处理：审批 · ".length() + 25);
        assertTrue("截断要有省略号：" + title, title.endsWith("…"));
    }
}
