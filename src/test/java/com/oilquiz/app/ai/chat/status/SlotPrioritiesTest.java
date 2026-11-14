package com.oilquiz.app.ai.chat.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * 命名优先级档位契约（§22，2026-11-15）。
 *
 * <p>钉住三件事：</p>
 * <ol>
 *   <li><b>档位数值</b>：p0=0 / p1=5 / p2=10 / p3=20 / p4=30 / p5=50，
 *       与插件 {@code SLOT_PRIORITY} 同名同值（插件侧由 {@code slot.test.mjs} 钉住）；</li>
 *   <li><b>未知 level 回退 p4</b>：空串/null/`p9` 一律 {@link SlotPriorities#DEFAULT}，
 *       写错档位的帧不会把排序搞乱；</li>
 *   <li><b>与 int 兼容</b>：{@link DockSlotBus.Slot} 仍收 int，档位只是取值的唯一来源；
 *       历史中间值（6/15/35/60）不硬凑成最近档位（{@code levelOf} 返回空串）。</li>
 * </ol>
 */
public class SlotPrioritiesTest {

    @Test
    public void tierValuesMatchThePluginTable() {
        assertEquals(0, SlotPriorities.P0);
        assertEquals(5, SlotPriorities.P1);
        assertEquals(10, SlotPriorities.P2);
        assertEquals(20, SlotPriorities.P3);
        assertEquals(30, SlotPriorities.P4);
        assertEquals(50, SlotPriorities.P5);
        assertEquals("未指定档位 = p4", SlotPriorities.P4, SlotPriorities.DEFAULT);
    }

    @Test
    public void tableHasExactlySixTiers() {
        Map<String, Integer> table = SlotPriorities.table();
        assertEquals(6, table.size());
        assertEquals(Integer.valueOf(0), table.get("p0"));
        assertEquals(Integer.valueOf(5), table.get("p1"));
        assertEquals(Integer.valueOf(10), table.get("p2"));
        assertEquals(Integer.valueOf(20), table.get("p3"));
        assertEquals(Integer.valueOf(30), table.get("p4"));
        assertEquals(Integer.valueOf(50), table.get("p5"));
    }

    @Test
    public void ofResolvesTierNamesCaseAndSpaceInsensitively() {
        assertEquals(10, SlotPriorities.of("p2"));
        assertEquals(10, SlotPriorities.of(" P2 "));
        assertEquals(0, SlotPriorities.of("p0"));
        assertEquals(50, SlotPriorities.of("p5"));
    }

    @Test
    public void unknownLevelFallsBackToP4() {
        assertEquals("未知档位回退默认档", SlotPriorities.P4, SlotPriorities.of("p9"));
        assertEquals(SlotPriorities.P4, SlotPriorities.of(""));
        assertEquals(SlotPriorities.P4, SlotPriorities.of("   "));
        assertEquals(SlotPriorities.P4, SlotPriorities.of(null));
    }

    @Test
    public void isKnownDistinguishesUnknownLevelFromDefault() {
        assertTrue(SlotPriorities.isKnown("p0"));
        assertTrue(SlotPriorities.isKnown("P5"));
        assertFalse(SlotPriorities.isKnown("p9"));
        assertFalse(SlotPriorities.isKnown(""));
        assertFalse(SlotPriorities.isKnown(null));
    }

    @Test
    public void levelOfMapsTierValuesAndLeavesLegacyNumbersAlone() {
        assertEquals("p0", SlotPriorities.levelOf(0));
        assertEquals("p3", SlotPriorities.levelOf(20));
        assertEquals("p5", SlotPriorities.levelOf(50));
        // 历史遗留的中间值不硬凑（否则日志会把 35 说成 p4/p5，误导排查）
        assertEquals("", SlotPriorities.levelOf(6));
        assertEquals("", SlotPriorities.levelOf(15));
        assertEquals("", SlotPriorities.levelOf(35));
        assertEquals("", SlotPriorities.levelOf(60));
        assertFalse(SlotPriorities.isTier(35));
        assertTrue(SlotPriorities.isTier(10));
    }

    @Test
    public void intPriorityStillRoundTripsThroughSlot() {
        // 兼容性：Slot 的构造签名不变（int），内部产生的槽位用档位常量填值
        DockSlotBus.Slot slot = new DockSlotBus.Slot(
                "permission", SlotPriorities.P2, "权限", "可写工作区", "permission", false);
        assertEquals(10, slot.priority);
        assertEquals("p2", SlotPriorities.levelOf(slot.priority));

        // 旧帧只有 int 时照旧排序（数值语义不变：越小越靠上）
        DockSlotBus.Slot legacy = new DockSlotBus.Slot(
                "legacy", 55, "旧", "", "", false);
        assertEquals(55, legacy.priority);
        assertEquals("", SlotPriorities.levelOf(legacy.priority));
        assertTrue("p2 应排在旧数值 55 之上", slot.priority < legacy.priority);
    }
}
