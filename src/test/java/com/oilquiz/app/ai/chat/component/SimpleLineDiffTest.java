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

    /**
     * 2026-10-28：卡片从"整篇铺开"改成"改动 ±3 行 + ⋯ 折叠"。
     * 两处改动离得远时必须出现折叠标记，近处则并成一段。
     */
    @Test
    public void hunksFoldDistantChangesAndKeepContext() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            before.append("line").append(i).append("\n");
            after.append(i == 1 || i == 28 ? "line" + i + " changed\n" : "line" + i + "\n");
        }
        List<SimpleLineDiff.Line> shown = SimpleLineDiff.hunks(before.toString(), after.toString(), 3);
        StringBuilder texts = new StringBuilder();
        for (SimpleLineDiff.Line line : shown) texts.append(line.text).append("|");
        assertTrue("远距离改动之间要有折叠标记: " + texts, texts.toString().contains(SimpleLineDiff.FOLD_MARK));
        assertTrue("必须保留下改动行", texts.toString().contains("line1 changed"));
        assertTrue("也要看到末尾那处改动", texts.toString().contains("line28 changed"));
        assertTrue("不改的行不该整篇铺出来", !texts.toString().contains("line15|"));
        assertTrue("行数应远小于全文 30 行", shown.size() < 20);

        // 相邻改动（间隔 ≤ 2*ctx）并成一段，不折叠
        List<SimpleLineDiff.Line> near = SimpleLineDiff.hunks("a\nb\nc\nd\ne", "a\nB\nc\nD\ne", 3);
        StringBuilder nearText = new StringBuilder();
        for (SimpleLineDiff.Line line : near) nearText.append(line.text).append("|");
        assertTrue("相邻改动不该被折叠", !nearText.toString().contains(SimpleLineDiff.FOLD_MARK));
    }

    /**
     * 2026-10-28：超限不再"整篇铺两遍"。4000 行文件改一行，卡片上只该出现那一行。
     */
    @Test
    public void largeFileShowsOnlyTheChangedRegion() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        int n = 4000;
        for (int i = 0; i < n; i++) {
            String line = "row" + i;
            before.append(line).append("\n");
            after.append(i == 2000 ? "row2000 changed" : line).append("\n");
        }
        // diff() 语义上是"完整差异"（含全部未改行），卡片取的是 hunks() 的折叠视图
        List<SimpleLineDiff.Line> d = SimpleLineDiff.diff(before.toString(), after.toString());
        int[] stats = SimpleLineDiff.stats(d);
        assertEquals("只该有 1 行新增", 1, stats[0]);
        assertEquals("只该有 1 行删除", 1, stats[1]);
        List<SimpleLineDiff.Line> shown = SimpleLineDiff.hunks(before.toString(), after.toString(), 3);
        assertTrue("卡片上不该把整篇文件铺出来，实际行数=" + shown.size(), shown.size() < 20);
    }

    /**
     * 点击卡片/「查看完整差异」的详情：上下文要比卡片上的 3 行宽，且改动点一个都不能少。
     * 这条钉住"卡片只是摘要、详情才是全貌"的产品口径。
     */
    @Test
    public void detailViewGivesWiderContextThanCard() throws Exception {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            before.append("line").append(i).append("\n");
            after.append(i == 1 || i == 28 ? "line" + i + " changed\n" : "line" + i + "\n");
        }
        org.json.JSONObject props = new org.json.JSONObject()
                .put("title", "已修改 · demo.md")
                .put("before", before.toString())
                .put("after", after.toString());

        List<SimpleLineDiff.Line> card = SimpleLineDiff.hunks(
                before.toString(), after.toString(), 3);
        List<SimpleLineDiff.Line> detail =
                DiffCardView.resolveDetailLines(props, DiffCardView.DETAIL_CONTEXT);

        assertTrue("详情要给更宽的上下文: card=" + card.size() + " detail=" + detail.size(),
                detail.size() > card.size());
        StringBuilder texts = new StringBuilder();
        for (SimpleLineDiff.Line line : detail) texts.append(line.text).append("|");
        assertTrue(texts.toString().contains("line1 changed"));
        assertTrue(texts.toString().contains("line28 changed"));
        assertTrue("远距离改动之间仍要折叠", texts.toString().contains(SimpleLineDiff.FOLD_MARK));
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
