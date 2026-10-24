package com.oilquiz.app.ai.chat.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link MarkdownStreamSplitter} 的 AST 版口径（轮内流式 P7，2026-10-25）。
 *
 * <p>切块直接拿 commonmark 顶层节点，不再手写 CommonMark 行扫描；因此这里的断言重点是：</p>
 * <ol>
 *   <li><b>逐块 HTML 拼接 == 整篇 HTML</b>：对每个字符前缀回放，钉住“按块渲染不改变语义”；</li>
 *   <li><b>闭合判定</b>：围栏跨 tick、表格未闭合、列表空行、` ```\n ` 只有开栏、EOF 无换行等；</li>
 *   <li><b>前缀单调</b>：已闭合块只增不退；追加式增长 `diff` 永不 reset；</li>
 *   <li><b>引用式链接</b>不再回退：后置定义改变前缀 HTML 时 `diff` 必须 reset（安全地整体重建）；</li>
 *   <li><b>显示公式跨块</b>仍回退整篇。</li>
 * </ol>
 */
public class MarkdownStreamSplitterTest {

    /**
     * 逐字符前缀回放的语料。刻意避开引用式链接和 $$：前者用专门用例证明“前缀变了就 reset”，
     * 后者会触发整条回退。
     */
    private static final String[] CORPUS = {
            "第一段。\n\n第二段有 **加粗** 和 `code`。\n\n- 列表一\n- 列表二\n\n"
                    + "> 引用第一行\n> 引用第二行\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\n"
                    + "```java\nint a = 1;\n```\n\n结尾。\n",
            "# 标题\n\n正文第一行\n正文第二行\n\n---\n\n## 二级标题\n\n1. 第一\n2. 第二\n\n"
                    + "    缩进代码\n\n结束\n",
            "<div>\nraw html\n</div>\n\n普通段落\n\n- [ ] 待办\n- [x] 完成\n",
            "> 引用\n> 第二行\n\n普通\n\n```\ncode\n\nmore\n```\n\n尾部",
            "标题\n===\n\n副标题\n---\n\n正文",
            "| 左 | 右 |\n| :-- | --: |\n| a | b |\n| c | d |\n\n后文",
            "    indented\n\n    code\n\n段落",
            "<!-- 注释\n\n里面有空格 -->\n\n段落\n\n<pre>\npre 里\n\n也有空行\n</pre>\n\n尾",
            "- 外层\n  - 内层\n\n  继续\n\n段落",
            "说明：\n```\ncode\n\nmore\n```\n\n后文",
            "段落一\n段落二\n\n段落三",
            "前言\n- 项一\n\n- 项二\n\n后记",
            // 2026-10-25 新增语料：四反引号/波浪号围栏、结尾闭合围栏、结尾 HTML 块、
            // 结尾列表、纯标题、纯表格——都进入逐字符前缀回放。
            "````markdown\n```\ninner\n```\n````\n\n~~~\nx\n\nb\n~~~\n\n后文",
            "前言\n\n```java\nint a = 1;\n```\n",
            "<pre>\na\n\nb\n</pre>\n",
            "- 一\n\n- 二\n\n- 三\n",
            "# 标题\n",
            "|a|b|\n|-|-|\n|1|2|\n\n"
    };

    // ==== 段落 / 空行 ====

    @Test
    public void paragraphOnlyClosesAtTerminatedBlankLine() {
        MarkdownStreamSplitter.Split noBlank = split("第一段\n第二段\n");
        assertEquals("没有空行，整段仍应算尾巴（后面可能续写）",
                0, noBlank.closed.size());
        assertNotNull(noBlank.tail);
        assertEquals(MarkdownStreamSplitter.Kind.PARAGRAPH, noBlank.tail.kind);

        MarkdownStreamSplitter.Split blank = split("第一段\n\n第二段");
        assertEquals(1, blank.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.PARAGRAPH, blank.closed.get(0).kind);
        assertTrue(blank.closed.get(0).html.contains("<p>第一段</p>"));
        assertNotNull(blank.tail);
        assertTrue(blank.tail.html.contains("<p>第二段</p>"));

        // 末尾只有空格、没有换行的“空行”不算闭合证据：下一 token 可能直接粘成正文
        MarkdownStreamSplitter.Split spacesAtEof = split("第一段\n   ");
        assertEquals(0, spacesAtEof.closed.size());
    }

    @Test
    public void trailingBlankGrowthKeepsClosedBlockStable() {
        MarkdownStreamSplitter.Split one = split("第一段\n\n");
        MarkdownStreamSplitter.Split two = split("第一段\n\n\n");
        assertEquals(1, one.closed.size());
        assertEquals(1, two.closed.size());
        assertEquals("末尾多空行不应改动已闭合块",
                one.closed.get(0).html, two.closed.get(0).html);
        assertEquals(one.closed.get(0).key, two.closed.get(0).key);
        assertEquals(one.prefixHash, two.prefixHash);
        assertNull(one.tail);
        assertNull(two.tail);

        MarkdownStreamSplitter.Update update = MarkdownStreamSplitter.diff(one, two);
        assertFalse(update.reset);
        assertEquals(1, update.fromIndex);
        assertEquals("只需换尾巴（这里是空），不追加块",
                0, update.closed.size() - update.fromIndex);
    }

    // ==== 围栏代码 ====

    @Test
    public void fenceStaysOpenAcrossTicksAndClosesOnlyOnTerminatedClosingFence() {
        String t1 = "说明：\n\n```java\nint a";
        MarkdownStreamSplitter.Split s1 = split(t1);
        assertEquals(1, s1.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.FENCED_CODE, s1.tail.kind);
        assertFalse("开栏后没闭合，必须留在尾巴", s1.tail.closed);
        assertTrue(s1.tail.html.contains("<pre>"));

        String t2 = t1 + " = 1;";
        MarkdownStreamSplitter.Split s2 = split(t2);
        assertEquals(s1.prefixHash, s2.prefixHash);
        assertEquals(s1.closed.size(), s2.closed.size());
        assertNotEquals("尾巴内容变了", s1.tail.key, s2.tail.key);

        String t3 = t2 + "\n";
        MarkdownStreamSplitter.Split s3 = split(t3);
        assertEquals("还没读到闭合围栏", 1, s3.closed.size());

        String t4 = t3 + "```";
        MarkdownStreamSplitter.Split s4 = split(t4);
        assertEquals("闭合围栏在 EOF 处没有换行，仍不能算闭合", 1, s4.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.FENCED_CODE, s4.tail.kind);

        String t5 = t4 + "\n";
        MarkdownStreamSplitter.Split s5 = split(t5);
        assertEquals(2, s5.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.FENCED_CODE, s5.closed.get(1).kind);
        assertNull(s5.tail);

        MarkdownStreamSplitter.Update update = MarkdownStreamSplitter.diff(s4, s5);
        assertFalse(update.reset);
        assertEquals(1, update.fromIndex);
        assertEquals("只追加闭合的代码块", 1, update.closed.size() - update.fromIndex);

        assertWholeEqualsBlocks(t1);
        assertWholeEqualsBlocks(t2);
        assertWholeEqualsBlocks(t3);
        assertWholeEqualsBlocks(t4);
        assertWholeEqualsBlocks(t5);
    }

    @Test
    public void openingFenceOnlyIsNotMistakenForClosedFence() {
        MarkdownStreamSplitter.Split open = split("```\n");
        assertEquals("只有一行开栏，不能因为这一行也像闭栏就闭合", 0, open.closed.size());
        assertNotNull(open.tail);
        assertEquals(MarkdownStreamSplitter.Kind.FENCED_CODE, open.tail.kind);

        MarkdownStreamSplitter.Split emptyClosed = split("```\n```\n");
        assertEquals("开栏 + 闭栏的空代码块应闭合", 1, emptyClosed.closed.size());
        assertNull(emptyClosed.tail);

        MarkdownStreamSplitter.Split withInfo = split("```java\n");
        assertEquals("带 info 的单独开栏仍不能闭合", 0, withInfo.closed.size());
        assertWholeEqualsBlocks("```\n");
        assertWholeEqualsBlocks("```\n```\n");
        assertWholeEqualsBlocks("```java\n");
    }

    @Test
    public void fourBacktickAndTildeFencesRespectClosingLength() {
        MarkdownStreamSplitter.Split four = split("````markdown\n```\ninner\n```\n````\n\n后文");
        assertEquals(1, four.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.FENCED_CODE, four.closed.get(0).kind);
        assertTrue(four.closed.get(0).html.contains("inner"));
        assertEquals(MarkdownStreamSplitter.Kind.PARAGRAPH, four.tail.kind);

        MarkdownStreamSplitter.Split tilde = split("~~~\nx\n\nb\n~~~\n\n后文");
        assertEquals(1, tilde.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.FENCED_CODE, tilde.closed.get(0).kind);
        assertTrue(tilde.closed.get(0).html.contains("b"));
        assertWholeEqualsBlocks("````markdown\n```\ninner\n```\n````\n\n后文");
        assertWholeEqualsBlocks("~~~\nx\n\nb\n~~~\n\n后文");
    }

    @Test
    public void fenceAfterColonBecomesItsOwnClosedBlock() {
        String doc = "说明：\n```\ncode\n\nmore\n```\n\n后文";
        MarkdownStreamSplitter.Split split = split(doc);
        assertEquals("段落 + 围栏 + 后文：段落和围栏各一块", 2, split.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.PARAGRAPH, split.closed.get(0).kind);
        assertEquals(MarkdownStreamSplitter.Kind.FENCED_CODE, split.closed.get(1).kind);
        assertTrue("代码里的空行不能被切开",
                split.closed.get(1).html.contains("code\n\nmore"));
        assertEquals(MarkdownStreamSplitter.Kind.PARAGRAPH, split.tail.kind);
        assertWholeEqualsBlocks(doc);
    }

    // ==== 表格 ====

    @Test
    public void tableStaysTailUntilBlankLine() {
        String header = "| 左 | 右 |\n| --- | --- |\n";
        MarkdownStreamSplitter.Split s1 = split(header + "| 1 | 2 |");
        assertEquals("表格没有空行收尾，仍在生成中", 0, s1.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.TABLE, s1.tail.kind);
        assertTrue(s1.tail.html.contains("<table>"));

        MarkdownStreamSplitter.Split s2 = split(header + "| 1 | 2 |\n| 3 | 4 |");
        assertEquals(0, s2.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.TABLE, s2.tail.kind);

        MarkdownStreamSplitter.Split s3 = split(header + "| 1 | 2 |\n| 3 | 4 |\n\n");
        assertEquals(1, s3.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.TABLE, s3.closed.get(0).kind);
        assertTrue(s3.closed.get(0).html.contains(">3</td>")
                && s3.closed.get(0).html.contains(">4</td>"));
        assertNull(s3.tail);

        MarkdownStreamSplitter.Split s4 = split(header + "| 1 | 2 |\n| 3 | 4 |\n\n后文");
        assertEquals(1, s4.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.PARAGRAPH, s4.tail.kind);

        assertWholeEqualsBlocks(header + "| 1 | 2 |");
        assertWholeEqualsBlocks(header + "| 1 | 2 |\n| 3 | 4 |");
        assertWholeEqualsBlocks(header + "| 1 | 2 |\n| 3 | 4 |\n\n");
        assertWholeEqualsBlocks(header + "| 1 | 2 |\n| 3 | 4 |\n\n后文");
    }

    @Test
    public void tableNeedsDelimiterRowBeforeBeingClassifiedAsTable() {
        MarkdownStreamSplitter.Split headerOnly = split("| 左 | 右 |");
        assertEquals(MarkdownStreamSplitter.Kind.PARAGRAPH, headerOnly.tail.kind);
        MarkdownStreamSplitter.Split withDelimiter = split("| 左 | 右 |\n| --- | --- |");
        assertEquals(MarkdownStreamSplitter.Kind.TABLE, withDelimiter.tail.kind);
    }

    // ==== 列表 ====

    @Test
    public void blankBetweenListItemsDoesNotCloseList() {
        MarkdownStreamSplitter.Split split = split("- 一\n\n- 二\n");
        assertEquals("空行后还可能再来一项，列表不能闭合", 0, split.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.LIST, split.tail.kind);
        assertTrue(split.tail.html.contains("一"));
        assertTrue(split.tail.html.contains("二"));
    }

    @Test
    public void listClosesWhenFollowingBlockAppears() {
        String doc = "- 一\n\n- 二\n\n后文";
        MarkdownStreamSplitter.Split split = split(doc);
        assertEquals(1, split.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.LIST, split.closed.get(0).kind);
        assertEquals(MarkdownStreamSplitter.Kind.PARAGRAPH, split.tail.kind);
        assertWholeEqualsBlocks(doc);
    }

    @Test
    public void indentedContinuationKeepsListTail() {
        MarkdownStreamSplitter.Split split = split("- 一\n\n  续行\n");
        assertEquals(0, split.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.LIST, split.tail.kind);
    }

    @Test
    public void listWithFenceKeepsCodeBlankLinesInsideOneBlock() {
        String doc = "- 步骤：\n```\ncode\n\nmore\n```\n\n后文";
        MarkdownStreamSplitter.Split split = split(doc);
        assertTrue("列表/代码应作为闭合块整体渲染，不被代码里的空行切开",
                split.closed.size() >= 1);
        boolean found = false;
        for (MarkdownStreamSplitter.Block block : split.closed) {
            if (block.html.contains("code\n\nmore")) found = true;
        }
        assertTrue(found);
        assertEquals(MarkdownStreamSplitter.Kind.PARAGRAPH, split.tail.kind);
        assertWholeEqualsBlocks(doc);
    }

    // ==== 标题 / 分隔线 ====

    @Test
    public void headingClosesAtTerminatedLine() {
        assertEquals("EOF 处没有换行的标题仍算尾巴", 0, split("# 标题").closed.size());
        MarkdownStreamSplitter.Split withNewline = split("# 标题\n");
        assertEquals(1, withNewline.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.HEADING, withNewline.closed.get(0).kind);
        assertNull(withNewline.tail);

        MarkdownStreamSplitter.Split followed = split("# 标题\n正文");
        assertEquals(1, followed.closed.size());
        assertEquals("正文", followed.tail.html.replaceAll("<[^>]+>", "").trim());
        assertWholeEqualsBlocks("# 标题\n正文");
    }

    @Test
    public void setextHeadingIsNotSplitAtUnderline() {
        String doc = "标题\n---\n\n正文";
        MarkdownStreamSplitter.Split split = split(doc);
        assertEquals(1, split.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.HEADING, split.closed.get(0).kind);
        String html = split.closed.get(0).html;
        assertTrue("应渲染成 h2，而不是标题 + 分隔线", html.contains("<h2>") && !html.contains("<hr"));
        assertEquals(MarkdownStreamSplitter.Kind.PARAGRAPH, split.tail.kind);
        assertWholeEqualsBlocks(doc);
    }

    // ==== 缩进代码 / 引用 / HTML ====

    @Test
    public void indentedCodeStaysTailUntilFollowingBlock() {
        MarkdownStreamSplitter.Split alone = split("    a\n\n    b\n");
        assertEquals("缩进代码空行后还可能续代码，不能闭合", 0, alone.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.INDENTED_CODE, alone.tail.kind);
        assertEquals("两个缩进片段应仍在同一个 pre 里",
                1, count(alone.tail.html, "<pre"));

        MarkdownStreamSplitter.Split followed = split("    a\n\n    b\n\n段落");
        assertEquals(1, followed.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.INDENTED_CODE, followed.closed.get(0).kind);
        assertEquals(MarkdownStreamSplitter.Kind.PARAGRAPH, followed.tail.kind);
        assertWholeEqualsBlocks("    a\n\n    b\n\n段落");
    }

    @Test
    public void blockquoteClosesAtBlank() {
        String doc = "> 一\n\n> 二\n";
        MarkdownStreamSplitter.Split split = split(doc);
        assertEquals(1, split.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.BLOCKQUOTE, split.closed.get(0).kind);
        assertTrue(split.tail.html.contains("二"));
        assertWholeEqualsBlocks(doc);
    }

    @Test
    public void blockquoteWithFollowingListOrFenceKeepsBlankLinesTogether() {
        String listDoc = "> 引用\n- 一\n\n- 二\n\n后文";
        MarkdownStreamSplitter.Split listSplit = split(listDoc);
        assertTrue(listSplit.closed.size() >= 1);
        boolean listFound = false;
        for (MarkdownStreamSplitter.Block block : listSplit.closed) {
            if (block.html.contains("一") && block.html.contains("二")) listFound = true;
        }
        assertTrue("引用后的列表不能被空行切开", listFound);
        assertWholeEqualsBlocks(listDoc);

        String fenceDoc = "> 引用\n```\ncode\n\nmore\n```\n\n后文";
        MarkdownStreamSplitter.Split fenceSplit = split(fenceDoc);
        assertTrue(fenceSplit.closed.size() >= 1);
        boolean found = false;
        for (MarkdownStreamSplitter.Block block : fenceSplit.closed) {
            if (block.html.contains("code\n\nmore")) found = true;
        }
        assertTrue(found);
        assertWholeEqualsBlocks(fenceDoc);
    }

    @Test
    public void htmlPreAndCommentKeepBlankLines() {
        String pre = "<pre>\na\n\nb\n</pre>\n\n段落";
        MarkdownStreamSplitter.Split preSplit = split(pre);
        assertEquals(1, preSplit.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.HTML_BLOCK, preSplit.closed.get(0).kind);
        assertTrue(preSplit.closed.get(0).html.contains("a\n\nb"));
        assertWholeEqualsBlocks(pre);

        String comment = "<!-- a\n\nb -->\n\n段落";
        MarkdownStreamSplitter.Split commentSplit = split(comment);
        assertEquals(1, commentSplit.closed.size());
        assertEquals(MarkdownStreamSplitter.Kind.HTML_BLOCK, commentSplit.closed.get(0).kind);
        assertTrue(commentSplit.closed.get(0).html.contains("<!-- a\n\nb -->"));
        assertWholeEqualsBlocks(comment);
    }

    @Test
    public void taskListStillRendersCheckboxesPerBlock() {
        String doc = "- [ ] 待办\n- [x] 完成\n\n后文";
        MarkdownStreamSplitter.Split split = split(doc);
        assertEquals(1, split.closed.size());
        assertTrue(split.closed.get(0).html.contains("type=\"checkbox\""));
        assertTrue("已完成项要保留 checked", split.closed.get(0).html.contains("checked"));
        assertTrue(split.closed.get(0).html.contains("待办")
                && split.closed.get(0).html.contains("完成"));
        assertWholeEqualsBlocks(doc);
    }

    @Test
    public void orderedListStartingAtTwoAndType7HtmlStayInsideParagraph() {
        String ordered = "说明\n2. 不算打断\n\n";
        MarkdownStreamSplitter.Split orderedSplit = split(ordered);
        assertEquals(1, orderedSplit.closed.size());
        assertFalse("整块应按段落渲染，而不是 <ol>",
                orderedSplit.closed.get(0).html.contains("<ol>"));

        String html7 = "说明\n<span>x</span>\n\n";
        MarkdownStreamSplitter.Split htmlSplit = split(html7);
        assertEquals(1, htmlSplit.closed.size());
        assertTrue(htmlSplit.closed.get(0).html.contains("说明"));
        assertTrue(htmlSplit.closed.get(0).html.contains("<span>x</span>"));
        assertWholeEqualsBlocks(html7);
    }

    // ==== diff：前缀不变只换尾巴 ====

    @Test
    public void tailGrowthOnlyReplacesTail() {
        MarkdownStreamSplitter.Split a = split("第一段\n\n第二段");
        MarkdownStreamSplitter.Split b = split("第一段\n\n第二段继续");
        assertEquals(1, a.closed.size());
        assertEquals(a.closed.get(0).key, b.closed.get(0).key);
        assertEquals("前缀哈希必须不变", a.prefixHash, b.prefixHash);

        MarkdownStreamSplitter.Update update = MarkdownStreamSplitter.diff(a, b);
        assertFalse(update.reset);
        assertEquals(1, update.fromIndex);
        assertEquals("没有新闭合块", 0, update.closed.size() - update.fromIndex);
        assertNotEquals(a.tail.key, b.tail.key);
    }

    @Test
    public void closingParagraphAppendsOneBlock() {
        MarkdownStreamSplitter.Split a = split("第一段\n\n第二段继续");
        MarkdownStreamSplitter.Split b = split("第一段\n\n第二段继续\n\n第三段");
        assertEquals(1, a.closed.size());
        assertEquals(2, b.closed.size());

        MarkdownStreamSplitter.Update update = MarkdownStreamSplitter.diff(a, b);
        assertFalse(update.reset);
        assertEquals(1, update.fromIndex);
        assertEquals(1, update.closed.size() - update.fromIndex);
        assertEquals(MarkdownStreamSplitter.Kind.PARAGRAPH, update.tail.kind);
    }

    @Test
    public void replacementResets() {
        MarkdownStreamSplitter.Split a = split("第一段\n\n第二段");
        MarkdownStreamSplitter.Split b = split("完全换了内容");
        MarkdownStreamSplitter.Update update = MarkdownStreamSplitter.diff(a, b);
        assertTrue("前缀对不上必须整体重建", update.reset);
        assertEquals(0, update.fromIndex);
    }

    // ==== 引用式链接 / 公式 / 整篇 HTML ====

    @Test
    public void laterReferenceDefinitionChangesPrefixAndForcesReset() {
        MarkdownStreamSplitter.Split before = split("见[文档][ref]\n\n正文");
        assertTrue(before.streamable);
        assertFalse(before.closed.get(0).html.contains("<a "));
        assertEquals(1, before.closed.size());

        MarkdownStreamSplitter.Split after = split(
                "见[文档][ref]\n\n正文\n\n[ref]: https://example.com\n");
        assertTrue(after.streamable);
        assertTrue("后置定义应当被 commonmark 解析进前面段落",
                after.closed.get(0).html.contains("<a href=\"https://example.com\">"));

        MarkdownStreamSplitter.Update update = MarkdownStreamSplitter.diff(before, after);
        assertTrue("前缀 HTML 变了必须整体重建，不能只换尾巴", update.reset);
        assertWholeEqualsBlocks("见[文档][ref]\n\n正文\n\n[ref]: https://example.com\n");
    }

    @Test
    public void prefixHashIsDerivedFromClosedBlockKeysOnly() {
        // 1) 追加式增长：没有新闭合块时 prefixHash 必须不变；有新块时必须变。
        for (String doc : CORPUS) {
            MarkdownStreamSplitter.Split prev = split("");
            for (int cut = 1; cut <= doc.length(); cut++) {
                MarkdownStreamSplitter.Split cur = split(doc.substring(0, cut));
                assertTrue(cur.closed.size() >= prev.closed.size());
                if (cur.closed.size() == prev.closed.size()) {
                    assertEquals("没有新闭合块时 prefixHash 必须不变: cut=" + cut,
                            prev.prefixHash, cur.prefixHash);
                    for (int i = 0; i < prev.closed.size(); i++) {
                        assertEquals(prev.closed.get(i).key, cur.closed.get(i).key);
                    }
                } else {
                    assertNotEquals("有新闭合块时 prefixHash 必须变: cut=" + cut,
                            prev.prefixHash, cur.prefixHash);
                }
                prev = cur;
            }
        }

        // 2) 块数不变、但后置定义改了前面块的 HTML：key 和 prefixHash 必须一起变。
        MarkdownStreamSplitter.Split before = split("见[ref]\n\n正文");
        MarkdownStreamSplitter.Split after = split(
                "见[ref]\n\n[ref]: https://example.com\n\n正文");
        assertEquals("块数应不变", 1, before.closed.size());
        assertEquals(1, after.closed.size());
        assertNotEquals("块 HTML 变了，key 必须变",
                before.closed.get(0).key, after.closed.get(0).key);
        assertNotEquals("块 key 变了，prefixHash 必须一起变",
                before.prefixHash, after.prefixHash);
        assertTrue(after.closed.get(0).html.contains("<a href=\"https://example.com\">"));
        assertTrue("前缀变了必须 reset", MarkdownStreamSplitter.diff(before, after).reset);
    }

    @Test
    public void unbalancedDisplayMathFallsBack() {
        MarkdownStreamSplitter.Split split = split("$$a\n\nb$$\n\n");
        assertFalse(split.streamable);
        assertEquals(MarkdownStreamSplitter.Fallback.UNBALANCED_MATH, split.fallback);

        MarkdownStreamSplitter.Split balanced = split("$$\na + b\n$$\n\n后文");
        assertTrue("成对公式可以按块渲染", balanced.streamable);
        assertTrue(balanced.closed.get(0).html.contains("$$\na + b\n$$"));
    }

    @Test
    public void fullHtmlDocumentFallsBack() {
        MarkdownStreamSplitter.Split split = split("<!DOCTYPE html><html><body>x</body></html>");
        assertFalse(split.streamable);
        assertEquals(MarkdownStreamSplitter.Fallback.FULL_HTML_DOCUMENT, split.fallback);
    }

    // ==== 全前缀性质 ====

    @Test
    public void blockHtmlConcatenationEqualsWholeDocumentAtEveryPrefix() {
        for (String doc : CORPUS) {
            for (int cut = 0; cut <= doc.length(); cut++) {
                String prefix = doc.substring(0, cut);
                MarkdownStreamSplitter.Split split = split(prefix);
                assertTrue("语料不应触发回退: cut=" + cut + " src=" + escape(prefix),
                        split.streamable);
                assertEquals("块拼接 != 整篇: cut=" + cut + " src=" + escape(prefix),
                        MarkdownHtmlConverter.toBodyHtml(prefix), htmlOf(split));
            }
        }
    }

    @Test
    public void closedPrefixNeverRetractedAtEveryPrefix() {
        for (String doc : CORPUS) {
            MarkdownStreamSplitter.Split prev = split("");
            for (int cut = 1; cut <= doc.length(); cut++) {
                String prefix = doc.substring(0, cut);
                MarkdownStreamSplitter.Split cur = split(prefix);
                assertTrue(cur.closed.size() >= prev.closed.size());
                for (int i = 0; i < prev.closed.size(); i++) {
                    assertEquals("已闭合块被回退: cut=" + cut + " block=" + i
                                    + " src=" + escape(prefix),
                            prev.closed.get(i).html, cur.closed.get(i).html);
                    assertEquals(prev.closed.get(i).key, cur.closed.get(i).key);
                }
                prev = cur;
            }
        }
    }

    @Test
    public void diffNeverResetsForAppendOnlyGrowth() {
        for (String doc : CORPUS) {
            MarkdownStreamSplitter.Split prev = split("");
            for (int cut = 1; cut <= doc.length(); cut++) {
                MarkdownStreamSplitter.Split cur = split(doc.substring(0, cut));
                MarkdownStreamSplitter.Update update = MarkdownStreamSplitter.diff(prev, cur);
                assertFalse("追加式增长不应 reset: cut=" + cut
                        + " src=" + escape(doc.substring(0, cut)), update.reset);
                assertEquals(prev.closed.size(), update.fromIndex);
                for (int i = 0; i < update.fromIndex; i++) {
                    assertEquals(prev.closed.get(i).key, update.closed.get(i).key);
                }
                prev = cur;
            }
        }
    }

    @Test
    public void emptyAndBlankOnlyContentAreStreamableAndEmpty() {
        for (String src : new String[]{"", "\n\n", "   \n"}) {
            MarkdownStreamSplitter.Split split = split(src);
            assertTrue(split.streamable);
            assertEquals(0, split.closed.size());
            assertNull(split.tail);
            assertEquals(MarkdownHtmlConverter.toBodyHtml(src), htmlOf(split));
        }
    }

    // ==== helpers ====

    private static MarkdownStreamSplitter.Split split(String markdown) {
        return MarkdownStreamSplitter.split(markdown);
    }

    private static String htmlOf(MarkdownStreamSplitter.Split split) {
        StringBuilder sb = new StringBuilder();
        for (MarkdownStreamSplitter.Block block : split.closed) {
            sb.append(block.html);
        }
        if (split.tail != null) {
            sb.append(split.tail.html);
        }
        return sb.toString();
    }

    private static void assertWholeEqualsBlocks(String markdown) {
        assertEquals("块拼接 != 整篇: " + escape(markdown),
                MarkdownHtmlConverter.toBodyHtml(markdown), htmlOf(split(markdown)));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static String escape(String s) {
        return s.replace("\n", "\\n");
    }
}
