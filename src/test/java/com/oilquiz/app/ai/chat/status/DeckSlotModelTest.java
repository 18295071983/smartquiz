package com.oilquiz.app.ai.chat.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * U1 (2026-11-14): deck slot classification and chip label.
 *
 * <p>Guards the data model that U2 (multi-board chips) and U3 (toggles) build on:
 * legacy callers must keep working, and the chip label must stay compact.</p>
 */
public class DeckSlotModelTest {

    @Test
    public void legacyConstructorKeepsOldDefaults() {
        DockSlotBus.Slot s = new DockSlotBus.Slot("x", 40, "任务", "1 个在跑", "jobs", false);
        assertEquals("state", s.kind);
        assertEquals("state", s.board);
        assertFalse(s.pinned);
        assertEquals(0, s.count);
        assertEquals(0, s.progress);
        assertTrue(s.actions.isEmpty());
    }

    @Test
    public void classifiedConstructorStoresFields() {
        DockSlotBus.Slot s = new DockSlotBus.Slot("t", 30, "任务", "共 3 条", "jobs", false,
                "list", "task", true, 3);
        assertEquals("list", s.kind);
        assertEquals("task", s.board);
        assertTrue(s.pinned);
        assertEquals(3, s.count);
    }

    @Test
    public void blankKindAndBoardFallBack() {
        DockSlotBus.Slot s = new DockSlotBus.Slot("t", 30, "任务", "", "", false, "", "", false, -5);
        assertEquals("state", s.kind);
        assertEquals("state", s.board);
        assertEquals("count never negative", 0, s.count);
    }

    @Test
    public void chipTextAppendsCount() {
        DockSlotBus.Slot s = new DockSlotBus.Slot("t", 30, "任务", "", "jobs", false,
                "list", "task", false, 3);
        DeckRowModel m = DeckRowModel.of(s);
        assertEquals("任务 3", m.chipText);
        assertEquals(3, m.count);
    }

    @Test
    public void chipTextIsCappedAtTwelve() {
        DockSlotBus.Slot s = new DockSlotBus.Slot("t", 30,
                "这是一个特别特别长的插槽标题文字", "", "jobs", false);
        DeckRowModel m = DeckRowModel.of(s);
        assertEquals(13, m.chipText.length());   // 12 chars + ellipsis
        assertTrue(m.chipText.endsWith("\u2026"));
    }

    @Test
    public void selectionSortsByPriorityThenIdAndCaps() {
        List<DockSlotBus.Slot> slots = new ArrayList<>();
        slots.add(new DockSlotBus.Slot("b", 40, "B", "", "x", false));
        slots.add(new DockSlotBus.Slot("a", 40, "A", "", "x", false));
        slots.add(new DockSlotBus.Slot("z", 10, "Z", "", "x", false));
        slots.add(new DockSlotBus.Slot("", 5, "ignored", "", "x", false));
        List<DockSlotBus.Slot> out = StatusDeckController.select(slots, false, 2, 2);
        assertEquals(2, out.size());
        assertEquals("z", out.get(0).id);
        assertEquals("a", out.get(1).id);        // same priority -> id order
    }
}
