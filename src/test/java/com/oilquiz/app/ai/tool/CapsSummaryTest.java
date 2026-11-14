package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手机端 caps 回执解析（2026-11-15，§18 能力协商体检）。
 *
 * <p>纯 JVM：不依赖 Android / 网络。重点是**
 * 新形状（hello/ready.caps）与旧形状（/v2/hello 的 summarize）都能读**、
 * legacy 不假装"零缺失"、missing/unknown 原样透出。</p>
 */
public class CapsSummaryTest {

    /** 新版 clientCaps()：declared 是数组 */
    private static Map<String, Object> newShape() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 1);
        m.put("legacy", false);
        m.put("declared", new ArrayList<>(Arrays.asList(
                "events.stream", "parts.approval", "parts.questions")));
        m.put("declared_count", 3);
        m.put("missing", new ArrayList<>(Arrays.asList("view.cards.timeline", "events.ack")));
        m.put("unknown", new ArrayList<>(Arrays.asList("future.cap")));
        return m;
    }

    @Test
    public void parsesNewHelloShape() {
        CapsSummary s = CapsSummary.parse(newShape(), CapsSummary.SOURCE_WS_HELLO);
        assertNotNull(s);
        assertEquals(1, s.version());
        assertFalse(s.legacy());
        assertTrue(s.serverKnown());
        assertEquals(3, s.declared().size());
        assertEquals(Arrays.asList("view.cards.timeline", "events.ack"), s.missing());
        assertEquals(Arrays.asList("future.cap"), s.unknown());
        assertEquals(CapsSummary.SOURCE_WS_HELLO, s.source());
    }

    @Test
    public void parsesOldSummarizeShape() {
        // /v2/hello 的 summarize：declared 是布尔，名单在 client（旧 APK / 旧插件都可能）
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("version", 1);
        old.put("declared", true);
        old.put("legacy", false);
        old.put("client", new ArrayList<>(Arrays.asList("events.stream", "parts.approval")));
        old.put("missing", new ArrayList<>(Arrays.asList("view.cards.status")));
        old.put("unknown", new ArrayList<>());

        CapsSummary s = CapsSummary.parse(old, "/v2/hello");
        assertNotNull(s);
        assertEquals(Arrays.asList("events.stream", "parts.approval"), s.declared());
        assertEquals(Arrays.asList("view.cards.status"), s.missing());
        assertFalse(s.legacy());
        assertTrue(s.serverKnown());
    }

    @Test
    public void legacyClientHasNoMissingAndSaysSo() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("version", 1);
        legacy.put("legacy", true);
        legacy.put("declared", new ArrayList<>());
        legacy.put("missing", new ArrayList<>());
        legacy.put("unknown", new ArrayList<>());

        CapsSummary s = CapsSummary.parse(legacy, CapsSummary.SOURCE_WS_HELLO);
        assertNotNull(s);
        assertTrue(s.legacy());
        assertEquals(0, s.declared().size());
        assertEquals(0, s.missing().size());
        assertTrue(s.line().contains("legacy=1"));
    }

    @Test
    public void localFallbackDoesNotClaimZeroMissing() {
        CapsSummary s = CapsSummary.local();
        assertEquals(RemoteCaps.ALL.length, s.declared().size());
        assertFalse(s.serverKnown());
        assertTrue(s.line().contains("[caps] 声明=" + RemoteCaps.ALL.length));
        assertTrue(s.line().contains("缺失=?"));
        assertEquals(CapsSummary.SOURCE_LOCAL, s.source());
    }

    @Test
    public void lineFormatForLogcat() {
        CapsSummary s = CapsSummary.parse(newShape(), CapsSummary.SOURCE_SSE);
        String line = s.line();
        assertTrue(line.startsWith("[caps] 声明=3 缺失=2("));
        assertTrue(line.contains("view.cards.timeline"));
        assertTrue(line.contains("未知=1(future.cap)"));
        assertTrue(line.contains("version=1"));
        assertTrue(line.contains("来源=sse:ready"));
    }

    @Test
    public void missingPreviewIsBounded() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < 9; i += 1) missing.add("cap." + i);
        m.put("missing", missing);
        m.put("unknown", new ArrayList<>());
        CapsSummary s = CapsSummary.parse(m, null);
        assertNotNull(s);
        assertTrue(s.line().contains("缺失=9(cap.0,cap.1,cap.2,cap.3,cap.4,…)"));
    }

    @Test
    public void unknownStructureReturnsNull() {
        assertNull(CapsSummary.parse(null, CapsSummary.SOURCE_WS_HELLO));
        assertNull(CapsSummary.parse("events.stream", CapsSummary.SOURCE_WS_HELLO));
        assertNull(CapsSummary.parse(42, CapsSummary.SOURCE_WS_HELLO));
    }

    @Test
    public void csvAndDedupAreTolerated() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("declared", "events.stream, parts.approval ,,events.stream");
        m.put("missing", "");
        CapsSummary s = CapsSummary.parse(m, null);
        assertNotNull(s);
        assertEquals(Arrays.asList("events.stream", "parts.approval"), s.declared());
        assertEquals(0, s.missing().size());
    }
}
