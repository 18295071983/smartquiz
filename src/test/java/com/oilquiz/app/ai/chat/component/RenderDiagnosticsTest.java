package com.oilquiz.app.ai.chat.component;

import org.junit.After;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** RenderDiagnostics 单元测试（2026-11-16）：渲染失败要能被"送出去"，且只送一次。 */
public class RenderDiagnosticsTest {

    @After
    public void tearDown() {
        RenderDiagnostics.clear();
    }

    @Test
    public void drainReturnsEntriesOnce() {
        RenderDiagnostics.record("table_card", "数据与卡片契约不匹配", "table_card 需要 headers/rows");
        List<RenderDiagnostics.Entry> first = RenderDiagnostics.drain();
        assertEquals(1, first.size());
        assertTrue("文字应含卡片类型", first.get(0).text().contains("table_card"));
        assertTrue("文字应含字段建议", first.get(0).text().contains("headers"));

        List<RenderDiagnostics.Entry> second = RenderDiagnostics.drain();
        assertTrue("送完即清空，不应重复送达", second.isEmpty());
    }

    @Test
    public void capacityIsBoundedAndCounted() {
        for (int i = 0; i < 30; i++) {
            RenderDiagnostics.record("card" + i, "渲染异常", null);
        }
        List<RenderDiagnostics.Entry> all = RenderDiagnostics.drain();
        assertEquals("上限 20 条", 20, all.size());
        assertEquals("被丢弃的数量应可观测", 10, RenderDiagnostics.droppedCount());
        assertEquals("保留最新的", "card29", all.get(all.size() - 1).type);
    }

    @Test
    public void peekDoesNotConsume() {
        RenderDiagnostics.record("x", "渲染返回空视图", null);
        assertEquals(1, RenderDiagnostics.peek().size());
        assertEquals("peek 不应取走", 1, RenderDiagnostics.peek().size());
        assertEquals(1, RenderDiagnostics.drain().size());
    }
}
