package com.oilquiz.app.ai.agent.online;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 工具结果取文口径单测（2026-10-25）。
 *
 * <p>钉住一件事：<b>失败原因住在 {@code error} 里，{@code result} 恒为 null</b>，
 * 所以 UI/持久化一律走 {@link OnlineToolResult#displayText()}。真机上失败的 js_execute
 * 卡片与会话组件里完全没有原因，就是因为它取了 {@code result}。</p>
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
}
