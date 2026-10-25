package com.oilquiz.app.ai.agent.online;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 工具结果取文口径单测（2026-10-25）。
 *
 * <p>钉住一件事：<b>失败原因住在 {@code error} 里，{@code result} 恒为 null</b>，
 * 所以 UI/持久化一律走 {@link OnlineToolResult#displayText()}，回填模型的 tool 消息一律走
 * {@link OnlineToolResult#modelText()}。真机上失败的 js_execute 卡片与会话组件里完全没有
 * 原因，就是因为它取了 {@code result}；而模型读到的是 {@code "工具执行失败: null"}。</p>
 */
public class OnlineToolResultDisplayTextTest {

    @Test
    public void successReturnsResult() {
        OnlineToolResult r = OnlineToolResult.success("id", "js_execute", "执行成功\n返回值: 42", 10);
        assertEquals("执行成功\n返回值: 42", r.displayText());
    }

    @Test
    public void successWithNullResultIsNotAnError() {
        assertEquals("", OnlineToolResult.success("id", "t", null, 0).displayText());
    }

    /** 核心回归点：失败时 displayText 必须给出 error，而不是 null/空。 */
    @Test
    public void failureReturnsErrorReason() {
        OnlineToolResult r = OnlineToolResult.failure("id", "js_execute",
                "Illegal return statement", 0);
        assertEquals("Illegal return statement", r.displayText());
    }

    /** failure() 的 result 恒为 null——若哪天有人改了工厂，这条会先响。 */
    @Test
    public void failureFactoryKeepsResultNull() {
        OnlineToolResult r = OnlineToolResult.failure("id", "t", "boom", 0);
        org.junit.Assert.assertNull(r.result);
        assertEquals("boom", r.error);
    }

    @Test
    public void failureWithoutAnyReasonGetsIdentifiablePlaceholder() {
        String s = OnlineToolResult.failure("id", "t", null, 0).displayText();
        assertEquals("工具执行失败（引擎未给出原因）", s);
        // 绝不能退化成空串：空 result 在卡片上看起来像"执行过了但没结果"
        org.junit.Assert.assertFalse(s.trim().isEmpty());
    }

    /** 空白 error 也算"没给原因"，走占位串而不是空文本。 */
    @Test
    public void blankErrorAlsoFallsBackToPlaceholder() {
        assertEquals("工具执行失败（引擎未给出原因）",
                OnlineToolResult.failure("id", "t", "   ", 0).displayText());
    }

    /**
     * 模型侧口径（2026-10-25 复核补）：引擎原先拼 {@code "工具执行失败: " + error}，
     * error 为 null 时模型读到字面量 {@code "工具执行失败: null"}。这条钉住不再出现 null。
     */
    @Test
    public void modelTextNeverContainsNullLiteral() {
        String s = OnlineToolResult.failure("id", "js_execute", null, 0).modelText();
        assertEquals("工具执行失败（引擎未给出原因）", s);
        org.junit.Assert.assertFalse(s.contains("null"));
    }

    /** 有原因时模型侧要带"工具执行失败"标记——模型靠它判断是失败而不是普通文本结果。 */
    @Test
    public void modelTextKeepsFailureMarkerWhenReasonExists() {
        assertEquals("工具执行失败: Illegal return statement",
                OnlineToolResult.failure("id", "js_execute", "Illegal return statement", 0).modelText());
    }

    /** 成功侧两个口径必须一致，避免回填模型的正文被占位文案污染。 */
    @Test
    public void modelTextEqualsDisplayTextOnSuccess() {
        OnlineToolResult ok = OnlineToolResult.success("id", "t", "42", 5);
        assertEquals(ok.displayText(), ok.modelText());
        assertEquals("", OnlineToolResult.success("id", "t", null, 0).modelText());
    }
}
