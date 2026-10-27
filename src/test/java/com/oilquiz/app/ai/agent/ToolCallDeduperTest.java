package com.oilquiz.app.ai.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 空转/重复检测的判定表。
 *
 * <p>要钉住三件事：①同参不同键序必须被看成同一次调用（否则改个键序就能绕过）；
 * ②同一轮内重复的<b>有副作用</b>调用必须 SKIP（最危险的一种：删文件写两遍）；
 * ③只读/轮询型重复不能拦（{@code import_status} 这类轮询本来就该反复调）。</p>
 */
public class ToolCallDeduperTest {

    @Test
    public void canonicalizationIgnoresKeyOrderAndWhitespace() {
        String a = "{\"b\":2,\"a\":1}";
        String b = "{ \"a\" : 1 , \"b\" : 2 }";
        assertEquals(ToolCallDeduper.canonical(a), ToolCallDeduper.canonical(b));
        // 嵌套对象也要递归排序
        assertEquals(ToolCallDeduper.canonical("{\"x\":{\"d\":4,\"c\":3}}"),
                ToolCallDeduper.canonical("{\"x\":{\"c\":3,\"d\":4}}"));
        // 参数不同必须区分
        assertNotEquals(ToolCallDeduper.keyOf("workspace", "{\"fileName\":\"a\"}"),
                ToolCallDeduper.keyOf("workspace", "{\"fileName\":\"b\"}"));
        // 裸文本会被 Gson 宽松解析成字符串，规范化后带引号——不抛异常、同样输入同样结果即可
        assertEquals(ToolCallDeduper.canonical("not-json"), ToolCallDeduper.canonical(" not-json "));
    }

    @Test
    public void duplicateSideEffectWithinRoundIsSkipped() {
        ToolCallDeduper d = new ToolCallDeduper();
        d.beginRound();
        assertEquals(ToolCallDeduper.Verdict.NEW,
                d.judge("workspace", "{\"action\":\"delete\",\"fileName\":\"a.txt\"}", true, false));
        assertEquals(ToolCallDeduper.Verdict.SKIP,
                d.judge("workspace", "{\"fileName\":\"a.txt\",\"action\":\"delete\"}", true, false));
        // 参数不同 → 新的，正常执行
        assertEquals(ToolCallDeduper.Verdict.NEW,
                d.judge("workspace", "{\"action\":\"delete\",\"fileName\":\"b.txt\"}", true, false));
    }

    @Test
    public void readOnlyAndPollingRepeatsAreNotSkipped() {
        ToolCallDeduper d = new ToolCallDeduper();
        d.beginRound();
        assertEquals(ToolCallDeduper.Verdict.NEW, d.judge("time_date", "{}", false, false));
        // 只读重复：照常执行（缓存命中）
        assertEquals(ToolCallDeduper.Verdict.REPEAT_EXECUTED, d.judge("time_date", "{}", false, false));
        // 轮询型副作用：同参反复是正常用法
        assertEquals(ToolCallDeduper.Verdict.NEW,
                d.judge("knowledge_base", "{\"action\":\"import_status\"}", true, true));
        assertEquals(ToolCallDeduper.Verdict.REPEAT_EXECUTED,
                d.judge("knowledge_base", "{\"action\":\"import_status\"}", true, true));
    }

    @Test
    public void consecutiveAllDuplicateRoundsAccumulateAndReset() {
        ToolCallDeduper d = new ToolCallDeduper();

        // 第 1 轮：新调用 → 有进展
        d.beginRound();
        d.judge("workspace", "{\"action\":\"clear\"}", true, false);
        assertEquals(0, d.endRound());

        // 第 2、3 轮：同样的副作用调用 → 连续空转计数
        d.beginRound();
        d.judge("workspace", "{\"action\":\"clear\"}", true, false);
        assertEquals(1, d.endRound());
        d.beginRound();
        d.judge("workspace", "{\"action\":\"clear\"}", true, false);
        assertEquals(2, d.endRound());

        // 一旦出现新的副作用调用，计数清零
        d.beginRound();
        d.judge("workspace", "{\"action\":\"list\"}", true, false);
        assertEquals(0, d.endRound());
    }

    @Test
    public void pureReadOnlyRoundsDoNotCountAsNoProgress() {
        ToolCallDeduper d = new ToolCallDeduper();
        // 只读轮：即使全是重复，也不该累计（否则读文件/查时间会被判空转）
        for (int i = 0; i < 3; i++) {
            d.beginRound();
            d.judge("time_date", "{}", false, false);
            assertEquals("只读轮不该累计空转", 0, d.endRound());
        }
    }

    @Test
    public void resetClearsCrossTurnState() {
        ToolCallDeduper d = new ToolCallDeduper();
        d.beginRound();
        d.judge("workspace", "{\"action\":\"clear\"}", true, false);
        d.endRound();
        d.beginRound();
        d.judge("workspace", "{\"action\":\"clear\"}", true, false);
        assertTrue(d.consecutiveAllDuplicateRounds() >= 0);

        d.reset();
        assertEquals(0, d.consecutiveAllDuplicateRounds());
        // reset 之后同参调用重新算"新的"
        assertEquals(ToolCallDeduper.Verdict.NEW,
                d.judge("workspace", "{\"action\":\"clear\"}", true, false));
    }
}
