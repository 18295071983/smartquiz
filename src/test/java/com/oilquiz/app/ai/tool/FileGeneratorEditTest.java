package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@code file_generator(action=edit)} 的纯逻辑回归（2026-10-28 从 python_file_ops 搬过来）。
 *
 * <p>这套语义的价值全在"不乱改"：命中 0 处必须报错（说明 old_text 抄错了），
 * 命中多处必须报错（说明上下文不够唯一）——任何一条松掉，模型就会在"以为改对了"的情况下
 * 改错文件，而这种错在手机上很难被发现。
 */
public class FileGeneratorEditTest {

    private static final String SRC = "# 标题\n\n规则一：先声明式\n规则二：后代码\n";

    @Test
    public void uniqueMatchReplacesExactlyOneSpot() {
        FileGeneratorTool.EditOutcome out = FileGeneratorTool.applyEdit(
                SRC, "先声明式", "先想专用工具", false);
        assertNull(out.error);
        assertEquals(1, out.count);
        assertEquals(1, out.replaced);
        assertEquals("# 标题\n\n规则一：先想专用工具\n规则二：后代码\n", out.text);
    }

    @Test
    public void missingOldTextFailsWithActionableMessage() {
        FileGeneratorTool.EditOutcome out = FileGeneratorTool.applyEdit(SRC, "这句话正文里没有", "x", false);
        assertNotNull("命中 0 处必须报错，不能默默成功", out.error);
        assertTrue("报错要指路怎么核对原文", out.error.contains("核对原文"));
    }

    @Test
    public void multipleMatchesFailUnlessReplaceAll() {
        FileGeneratorTool.EditOutcome out = FileGeneratorTool.applyEdit(SRC, "规则", "条款", false);
        assertNotNull("多处匹配默认必须报错", out.error);
        assertTrue("报错要说清命中几处", out.error.contains("2 处"));
        assertTrue("报错要给出 replace_all 这个出路", out.error.contains("replace_all=true"));
    }

    @Test
    public void replaceAllTouchesEveryMatch() {
        FileGeneratorTool.EditOutcome out = FileGeneratorTool.applyEdit(SRC, "规则", "条款", true);
        assertNull(out.error);
        assertEquals(2, out.count);
        assertEquals(2, out.replaced);
        assertEquals("# 标题\n\n条款一：先声明式\n条款二：后代码\n", out.text);
    }

    @Test
    public void emptyNewTextDeletesTheMatchedSpan() {
        FileGeneratorTool.EditOutcome out = FileGeneratorTool.applyEdit(SRC, "规则二：后代码\n", "", false);
        assertNull(out.error);
        assertEquals("# 标题\n\n规则一：先声明式\n", out.text);
    }

    @Test
    public void emptyOldTextIsRejectedBeforeTouchingAnything() {
        assertNotNull(FileGeneratorTool.applyEdit(SRC, "", "x", false).error);
        assertNotNull(FileGeneratorTool.applyEdit(SRC, null, "x", false).error);
    }

    @Test
    public void unifiedDiffShowsTheSingleChangedLine() {
        String after = "# 标题\n\n规则一：先想专用工具\n规则二：后代码\n";
        String diff = UnifiedDiffText.render(SRC, after, 3);
        assertTrue("要有 hunk 头", diff.contains("@@ -1,4 +1,4 @@"));
        assertTrue("旧行要带减号", diff.contains("-规则一：先声明式"));
        assertTrue("新行要带加号", diff.contains("+规则一：先想专用工具"));
    }

    @Test
    public void identicalContentProducesNoDiff() {
        assertEquals("", UnifiedDiffText.render(SRC, SRC, 3));
    }

    /**
     * 2026-10-28：大文件不再"整篇 -/+"。4000 行里改一行，给模型的 diff 应该只围绕那一行，
     * 否则会被 6000 字截断，等于没给。
     */
    @Test
    public void largeFileDiffOnlyCoversTheChangedRegion() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            a.append("row").append(i).append("\n");
            b.append(i == 2000 ? "row2000 changed" : "row" + i).append("\n");
        }
        String diff = UnifiedDiffText.render(a.toString(), b.toString(), 3);
        assertTrue("要有 hunk 头", diff.contains("@@"));
        assertTrue("要能看到改前", diff.contains("-row2000"));
        assertTrue("要能看到改后", diff.contains("+row2000 changed"));
        assertTrue("不该铺整篇文件，实际长度=" + diff.length(), diff.length() < 2000);
    }

    @Test
    public void longFilesDegradeToFullReplacementInsteadOfHanging() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            a.append("line ").append(i).append('\n');
            b.append("line ").append(i).append(" changed\n");
        }
        String diff = UnifiedDiffText.render(a.toString(), b.toString(), 3);
        assertTrue("超出 LCS 上限要退化而不是卡死", diff.contains("@@"));
        assertTrue(diff.length() > 0);
    }
}
