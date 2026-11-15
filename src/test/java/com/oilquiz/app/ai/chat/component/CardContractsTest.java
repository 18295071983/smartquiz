package com.oilquiz.app.ai.chat.component;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 注册期契约提示测试（2026-11-16，第 2 项验收）。
 *
 * <p>规则被刻意设计成"只在交集为空时提示、永不阻断"：扫源码得出的键都是可选语义，
 * 而且有卡片走条件分支（python 解释器回传、weather 无温度时改渲染表格卡），
 * 硬校验会误杀 —— 所以这里把"什么时候提示、什么时候不提示"钉住。</p>
 */
public class CardContractsTest {

    @Test
    public void noWarningWhenOverlap() {
        List<Map<String, Object>> w = CardContracts.contractWarnings("table_card",
                Arrays.asList("headers", "rows"));
        assertTrue("字段能对上就不该提示", w.isEmpty());
    }

    @Test
    public void warnsOnlyWhenZeroOverlap() {
        List<Map<String, Object>> w = CardContracts.contractWarnings("table_card",
                Arrays.asList("foo", "bar"));
        assertEquals(1, w.size());
        assertEquals("table_card", w.get(0).get("card"));
        String msg = String.valueOf(w.get(0).get("message"));
        assertTrue("提示里要写出期望字段", msg.contains("headers"));
        assertTrue("提示必须说明可忽略（由 AI 判断）", msg.contains("可忽略"));
    }

    @Test
    public void noWarningForUnknownCard() {
        assertTrue("无契约的卡不提示", CardContracts.contractWarnings("not_a_card",
                Arrays.asList("foo")).isEmpty());
    }

    @Test
    public void noWarningForEmptyProps() {
        assertTrue("空 props 不提示", CardContracts.contractWarnings("table_card",
                Arrays.asList()).isEmpty());
    }
}
