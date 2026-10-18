package com.oilquiz.app.ai.chat.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link ToolResultText} 的预览口径（C0-T2）。
 *
 * <p>工具卡只显示预览，全文留给"查看全部"弹层。这里钉住三件事：
 * 行数/字数双上限谁先到算谁；截断不能切坏 emoji（代理对）和 Windows 换行（CRLF）；
 * 短内容必须原样返回（否则"查看全部"会凭空出现）。
 */
public class ToolResultTextTest {

    @Test
    public void shortTextIsNotTruncated() {
        String raw = "晴，26℃，湿度 40%";
        ToolResultText.Preview pv = ToolResultText.preview(raw);

        assertFalse(pv.truncated);
        assertEquals(raw, pv.text);
        assertEquals(raw.length(), pv.totalChars);
    }

    @Test
    public void nullAndEmptyAreSafe() {
        assertFalse(ToolResultText.preview(null).truncated);
        assertEquals("", ToolResultText.preview(null).text);
        assertEquals("", ToolResultText.preview("").text);
    }

    @Test
    public void charCapWinsWhenContentIsOneLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) sb.append('a');
        String raw = sb.toString();

        ToolResultText.Preview pv = ToolResultText.preview(raw);

        assertTrue(pv.truncated);
        assertEquals(ToolResultText.MAX_PREVIEW_CHARS + 1, pv.text.length());
        assertTrue("省略号收尾", pv.text.endsWith("…"));
        assertEquals(3000, pv.totalChars);
    }

    @Test
    public void lineCapWinsBeforeCharCap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append("aa\n");
        String raw = sb.toString();   // 30 字符、10 行，远低于字数上限

        ToolResultText.Preview pv = ToolResultText.preview(raw);

        assertTrue("超过 8 行必须截断", pv.truncated);
        assertEquals("预览里保留 7 个行尾", 7, countNewlines(pv.text));
        assertEquals(30, pv.totalChars);
    }

    @Test
    public void doesNotSplitSurrogatePair() {
        // 1999 个普通字符 + 一个 emoji（占 2 个 char）：切到 2000 会把代理对劈开
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ToolResultText.MAX_PREVIEW_CHARS - 1; i++) sb.append('a');
        sb.append("😀");
        sb.append("tail");

        ToolResultText.Preview pv = ToolResultText.preview(sb.toString());

        assertTrue(pv.truncated);
        String body = stripEllipsis(pv.text);
        assertEquals("必须退到代理对之前", ToolResultText.MAX_PREVIEW_CHARS - 1, body.length());
        assertFalse("结尾不能是半个代理对",
                Character.isHighSurrogate(body.charAt(body.length() - 1)));
    }

    @Test
    public void doesNotSplitCrLf() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ToolResultText.MAX_PREVIEW_CHARS - 1; i++) sb.append('a');
        sb.append("\r\nnext");

        ToolResultText.Preview pv = ToolResultText.preview(sb.toString());

        assertTrue(pv.truncated);
        String body = stripEllipsis(pv.text);
        assertEquals("不能停在 \\r 上", ToolResultText.MAX_PREVIEW_CHARS - 1, body.length());
        assertFalse("结尾不能残留孤立 \\r", body.charAt(body.length() - 1) == '\r');
    }

    /** 去掉预览末尾的省略号，便于断言"真正的截断位置" */
    private static String stripEllipsis(String preview) {
        return preview.endsWith("…")
                ? preview.substring(0, preview.length() - 1) : preview;
    }

    private static int countNewlines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }
}
