package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 填空判题口径（2026-10-23 补 quiz_card 填空/简答时抽出的单一写者）。
 * 规则：忽略空白/全角半角/大小写，其余逐字；多可接受答案用 | 分隔；不做模糊匹配。
 */
public class QuizAnswerMatcherTest {

    @Test
    public void normalizeIgnoresWhitespaceWidthAndCase() {
        assertEquals("北京", QuizAnswerMatcher.normalize(" 北 京 "));
        assertEquals("beijing", QuizAnswerMatcher.normalize("ＢＥＩＪＩＮＧ"));
        assertEquals("beijing", QuizAnswerMatcher.normalize("BeiJing"));
        assertEquals("3.14", QuizAnswerMatcher.normalize("3 . 14"));
        assertEquals("", QuizAnswerMatcher.normalize(null));
        assertEquals("", QuizAnswerMatcher.normalize("  \u3000 "));
    }

    @Test
    public void matchesAnyAcceptsAlternatives() {
        assertTrue(QuizAnswerMatcher.matchesAny("北京", "北京|首都北京"));
        assertTrue(QuizAnswerMatcher.matchesAny(" 首都北京 ", "北京|首都北京"));
        // 全角竖线也认
        assertTrue(QuizAnswerMatcher.matchesAny("上海", "北京｜上海"));
        // 大小写/全角英文
        assertTrue(QuizAnswerMatcher.matchesAny("beijing", "ＢＥＩＪＩＮＧ"));
    }

    @Test
    public void noFuzzyMatching() {
        assertFalse("不许同义/模糊匹配", QuizAnswerMatcher.matchesAny("北京市", "北京"));
        assertFalse(QuizAnswerMatcher.matchesAny("北京", "北京市"));
        assertFalse(QuizAnswerMatcher.matchesAny("上海", "北京|广州"));
    }

    @Test
    public void emptyInputsNeverMatch() {
        assertFalse(QuizAnswerMatcher.matchesAny("", "北京"));
        assertFalse(QuizAnswerMatcher.matchesAny("   ", "北京"));
        assertFalse(QuizAnswerMatcher.matchesAny("北京", ""));
        assertFalse(QuizAnswerMatcher.matchesAny("北京", null));
        assertFalse("空候选不能命中空输入", QuizAnswerMatcher.matchesAny("", "|"));
    }
}
