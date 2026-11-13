package com.oilquiz.app.ai.chat.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 甲板行文案模型（2026-11-13）：主/副文案拆分、可点标记、长文案截断。
 */
public class DeckRowModelTest {

    @Test
    public void splitsTitleAndDetail() {
        DeckRowModel m = DeckRowModel.of(new DockSlotBus.Slot(
                "jobs", 40, "后台任务", "1 个在跑 · 共 20 条", "jobs", false));
        assertEquals("后台任务", m.title);
        assertEquals("1 个在跑 · 共 20 条", m.detail);
        assertTrue("有 action 才画箭头/涟漪", m.clickable);
        assertFalse(m.warn);
    }

    @Test
    public void legacyFrameWithOnlyDetailBecomesTitle() {
        DeckRowModel m = DeckRowModel.of(new DockSlotBus.Slot(
                "x", 50, "", "没有标题的老帧", "", false));
        assertEquals("没有标题的老帧", m.title);
        assertEquals("", m.detail);
        assertFalse("无动作不可点", m.clickable);
    }

    @Test
    public void longDetailIsTruncated() {
        String longDetail = "这是一段特别特别长的副标题用来测试截断行为不应该把整行撑爆掉而且还要再长一点点";
        DeckRowModel m = DeckRowModel.of(new DockSlotBus.Slot(
                "x", 50, "标题", longDetail, "jobs", false));
        assertTrue("副文案要截断：" + m.detail, m.detail.length() <= 29);
        assertTrue(m.detail.endsWith("…"));
    }

    @Test
    public void nullSlotIsSafe() {
        DeckRowModel m = DeckRowModel.of(null);
        assertEquals("", m.title);
        assertEquals("", m.detail);
        assertFalse(m.clickable);
    }

    @Test
    public void warnFlagPassesThrough() {
        DeckRowModel m = DeckRowModel.of(new DockSlotBus.Slot(
                "permission", 35, "权限", "danger-full-access", "permission", true));
        assertTrue("危险权限要警示色", m.warn);
    }
}
