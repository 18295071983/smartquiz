package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** 行级 diff（2026-10-23，diff 卡用）：钉住上下文/增/删的顺序、换行处理与超限退化。 */
public class SimpleLineDiffTest {

    private static String kinds(List<SimpleLineDiff.Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (SimpleLineDiff.Line l : lines) {
            sb.append(l.kind == SimpleLineDiff.Kind.ADD ? '+'
                    : l.kind == SimpleLineDiff.Kind.DEL ? '-' : '=');
        }
        return sb.toString();
    }

    @Test
    public void identicalTextIsAllContext() {
        List<SimpleLineDiff.Line> d = SimpleLineDiff.diff("a\nb", "a\nb");
        assertEquals("==", kinds(d));
        int[] stats = SimpleLineDiff.stats(d);
        assertEquals(0, stats[0]);
        assertEquals(0, stats[1]);
    }

    @Test
    public void addedAndRemovedLines() {
        assertEquals("=+", kinds(SimpleLineDiff.diff("a", "a\nb")));
        assertEquals("=-", kinds(SimpleLineDiff.diff("a\nb", "a")));
        assertEquals("=-+", kinds(SimpleLineDiff.diff("a\nb", "a\nc")));
    }

    @Test
    public void crlfAndTrailingNewlineAreNormalized() {
        assertEquals("==", kinds(SimpleLineDiff.diff("a\r\nb\r\n", "a\nb")));
        assertEquals("=", kinds(SimpleLineDiff.diff("a\n", "a")));
        // 中间空行是真实内容，不能被吞
        assertEquals("=+=", kinds(SimpleLineDiff.diff("a\nb", "a\n\nb")));
    }

    @Test
    public void emptySidesWork() {
        assertEquals("+", kinds(SimpleLineDiff.diff("", "x")));
        assertEquals("-", kinds(SimpleLineDiff.diff("x", "")));
        assertTrue(SimpleLineDiff.diff("", "").isEmpty());
    }

    @Test
    public void overLimitFallsBackToWholeReplace() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 0; i < SimpleLineDiff.MAX_LINES + 1; i++) {
            before.append("old").append(i).append('\n');
            after.append("new").append(i).append('\n');
        }
        List<SimpleLineDiff.Line> d = SimpleLineDiff.diff(before.toString(), after.toString());
        int[] stats = SimpleLineDiff.stats(d);
        assertEquals(SimpleLineDiff.MAX_LINES + 1, stats[1]);
        assertEquals(SimpleLineDiff.MAX_LINES + 1, stats[0]);
        // 退化形态：先整段删除、再整段新增
        assertEquals(SimpleLineDiff.Kind.DEL, d.get(0).kind);
        assertEquals(SimpleLineDiff.Kind.ADD, d.get(d.size() - 1).kind);
    }

    @Test
    public void hunksAndBeforeAfterBothResolve() throws Exception {
        org.json.JSONObject hunkProps = new org.json.JSONObject()
                .put("hunks", new org.json.JSONArray()
                        .put(new org.json.JSONObject().put("type", "add").put("text", "x"))
                        .put(new org.json.JSONObject().put("type", "del").put("text", "y"))
                        .put(new org.json.JSONObject().put("type", "ctx").put("text", "z")));
        assertEquals("+-=", kinds(DiffCardView.resolveLines(hunkProps)));

        org.json.JSONObject textProps = new org.json.JSONObject()
                .put("before", "a\nb").put("after", "a\nc");
        assertEquals("=-+", kinds(DiffCardView.resolveLines(textProps)));
    }
}
