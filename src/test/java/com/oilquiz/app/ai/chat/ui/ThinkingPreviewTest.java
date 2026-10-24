package com.oilquiz.app.ai.chat.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * 思考折叠头摘要口径（2026-10-25 从 ChatAdapter 抽出的唯一实现）。
 *
 * <p>它决定折叠头那行显示什么——用户口径"折叠头要露出实际思考内容"，所以这里钉住三件事：
 * 流式取"最新已完成段落"首行、回退到进行中段、定稿取首段；以及标记清理与限长。</p>
 */
public class ThinkingPreviewTest {

    @Test
    public void streamingPrefersLastCompletedParagraph() {
        String text = "第一步：通读题目，找出已知条件\n第二行细节\n\n第二步：列方程（还在写）";
        assertEquals("第一步：通读题目，找出已知条件", ThinkingPreview.of(text, true));
    }

    @Test
    public void streamingFallsBackToInProgressParagraph() {
        assertEquals("只有一段", ThinkingPreview.of("只有一段\n还在写", true));
    }

    @Test
    public void doneTakesFirstParagraph() {
        String text = "开篇结论\n细节一行\n\n后面还有";
        assertEquals("开篇结论", ThinkingPreview.of(text, false));
    }

    @Test
    public void stripsMarkdownAndCapsLength() {
        StringBuilder longLine = new StringBuilder("**加粗** 与 `行内代码` ");
        for (int i = 0; i < 80; i++) longLine.append('长');
        String s = ThinkingPreview.of(longLine.toString(), false);

        assertFalse("强调标记要清掉: " + s, s.contains("**"));
        assertFalse("反引号要清掉: " + s, s.contains("`"));
        assertEquals("超长截断到 MAX_CHARS", ThinkingPreview.MAX_CHARS, s.length());
    }

    @Test
    public void blankInputIsEmpty() {
        assertEquals("", ThinkingPreview.of(null, true));
        assertEquals("", ThinkingPreview.of("   \n\t", false));
    }

    /** 折叠头只显示状态（2026-10-25 定稿），摘要口径仍被 ChatAdapter 的折叠行使用。 */
    @Test
    public void endsWithParagraphBreakOnlyForTrailingBlankLine() {
        org.junit.Assert.assertTrue(ThinkingPreview.endsWithParagraphBreak("段落\n\n"));
        org.junit.Assert.assertTrue(ThinkingPreview.endsWithParagraphBreak("段落\n  \n"));
        org.junit.Assert.assertFalse(ThinkingPreview.endsWithParagraphBreak("段落\n"));
        org.junit.Assert.assertFalse(ThinkingPreview.endsWithParagraphBreak("段落"));
    }
}
