package com.oilquiz.app.ai.tool;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 轮级工具等待预算：单工具预算之上再设一道"这一轮一共还能等多久"（每轮重置）。
 *
 * <p>为什么要这道闸：同一轮的工具虽然并发提交，但执行池 4 线程、主循环自己占一个，
 * 第 4 个起要排队；加上共享锁（Python 静态执行锁）会让并发退化成串行。没有回合预算时，
 * 3 个各申报 165~200s 的工具能把一轮拖过 10 分钟，用户只看到"一直在转"。</p>
 */
public class ToolTimeoutsBudgetTest {

    @Test
    public void perToolWaitIsClampedByRemainingTurnBudget() {
        // 预算充足：按工具自己的预算等
        assertEquals(170_000L, ToolTimeouts.clampToTurnBudget(170_000L, 300_000L));
        // 预算将尽：只等剩下的（典型：第三个长工具只剩 30s）
        assertEquals(30_000L, ToolTimeouts.clampToTurnBudget(170_000L, 30_000L));
        // 相等时取哪个都对，这里钉住 min 的语义
        assertEquals(120_000L, ToolTimeouts.clampToTurnBudget(120_000L, 120_000L));
    }

    @Test
    public void exhaustedOrInvalidBudgetYieldsZero() {
        // <=0 表示"本轮不再等"：调用方据此立刻按预算耗尽处理（未来得及完成的结果不回传）
        assertEquals(0L, ToolTimeouts.clampToTurnBudget(170_000L, 0L));
        assertEquals(0L, ToolTimeouts.clampToTurnBudget(170_000L, -5_000L));
        assertEquals(0L, ToolTimeouts.clampToTurnBudget(0L, 300_000L));
        assertEquals(0L, ToolTimeouts.clampToTurnBudget(-1L, 300_000L));
    }

    @Test
    public void budgetFitsOneLongToolButNotThree() {
        // 取值意图：单个 200s 级工具必须能跑完（否则等于把长工具变相废掉）
        assertTrue("回合预算要能容纳单个中长工具",
                ToolTimeouts.TURN_TOOL_BUDGET_MS > 200_000L);
        // 但三个 165s 的工具不允许把回合拖到 8 分钟以上
        assertTrue("三个 165s 工具必须被回合预算截断",
                ToolTimeouts.TURN_TOOL_BUDGET_MS < 3 * 165_000L);
        // 与全局单工具硬上限的关系：回合预算可能比单工具上限还小，这是有意的——
        // "某个工具声明 11 分钟"不等于"这一轮可以等 11 分钟"
        assertTrue(ToolTimeouts.TURN_TOOL_BUDGET_MS < ToolTimeouts.MAX_TOOL_TIMEOUT_MS);
    }
}
