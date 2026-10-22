package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link CodeSnippet} 的规则钉桩：创建出口只给"共几行 + 前 N 行"，全文留给 show。
 *
 * <p>这条边界是 token 成本与"模型能不能看见自己写的逻辑"之间的取舍，一旦哪天有人
 * 顺手改成贴全文，上下文会无声膨胀 —— 所以用测试钉住"必须截断、必须说明还剩多少行"。
 */
public class CodeSnippetTest {

    @Test
    public void lineCountsEmptyAsZero() {
        assertEquals(0, CodeSnippet.lineCount(null));
        assertEquals(0, CodeSnippet.lineCount(""));
        assertEquals(0, CodeSnippet.lineCount("   \n  "));
    }

    @Test
    public void lineCountHandlesCrlf() {
        assertEquals(3, CodeSnippet.lineCount("a\r\nb\r\nc"));
        assertEquals(2, CodeSnippet.lineCount("a\nb"));
        // 尾随换行不能多算一行（25 行的脚本以 \n 结尾时 split 会给出 26 段）
        assertEquals(2, CodeSnippet.lineCount("a\nb\n"));
        assertEquals(2, CodeSnippet.lineCount("a\r\nb\r\n"));
        assertFalse(CodeSnippet.preview("a\nb\n", 10).contains("行略"));
    }

    @Test
    public void previewShowsAllWhenShortAndNoOmissionNote() {
        String code = "def f(x):\n    return x";
        String p = CodeSnippet.preview(code, 10);
        assertTrue(p, p.contains("def f(x):"));
        assertTrue(p, p.contains("return x"));
        assertFalse("短代码不该出现省略提示: " + p, p.contains("行略"));
    }

    @Test
    public void previewTruncatesAndSaysHowManyLinesRemain() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            sb.append("line").append(i).append('\n');
        }
        String p = CodeSnippet.preview(sb.toString(), 10);
        assertTrue(p, p.contains("line10"));
        assertFalse("超过前 10 行的内容不能出现: " + p, p.contains("line11"));
        // 说清还剩多少行，模型才知道全文有多长、该不该去 show
        assertTrue(p, p.contains("其余 15 行略"));
        assertTrue(p, p.contains("查看动作"));
    }

    @Test
    public void previewCapsVeryLongLines() {
        StringBuilder longLine = new StringBuilder("x = '");
        for (int i = 0; i < 400; i++) {
            longLine.append('a');
        }
        String p = CodeSnippet.preview(longLine.toString(), 5);
        assertTrue("单行必须被截断: " + p, p.contains("…"));
        assertTrue(p.length() < 200);
    }

    @Test
    public void previewEmptyInputsReturnEmpty() {
        assertEquals("", CodeSnippet.preview(null, 10));
        assertEquals("", CodeSnippet.preview("   ", 10));
        assertEquals("", CodeSnippet.preview("abc", 0));
    }
}
