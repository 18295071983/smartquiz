package com.oilquiz.app.ai.chat.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 坑 2 的护栏：文档就绪判定必须带**代号**（2026-11-16）。
 *
 * <p>对应 HANDOVER「十六、WebView 坑位总表」坑 2。教训一句话：
 * 「页面加载完成」不是布尔值 —— 上一次加载的 {@code onPageFinished} 迟到回调
 * 会把新文档误判成已就绪（真机：首屏偶发空白，滚一下才出现）。</p>
 *
 * <p>原实现是三处重复的布尔表达式（L370/L475/L624），已抽成
 * {@link MarkdownWebView#documentReadyFor(boolean, int, int)}，这里把四种组合钉死。</p>
 */
public class MarkdownDocReadinessTest {

    @Test
    public void readyOnlyWhenSameGeneration() {
        // 同一代、页面已回调 → 就绪
        assertTrue(MarkdownWebView.documentReadyFor(true, 7, 7));
    }

    @Test
    public void notReadyWhenStaleGeneration() {
        // 迟到的上一代回调：页面说 ready，但代号是旧的 → 绝不能当就绪
        assertFalse(MarkdownWebView.documentReadyFor(true, 8, 7));
    }

    @Test
    public void notReadyBeforeFirstDocument() {
        // 还没开始加载（代号 0）
        assertFalse(MarkdownWebView.documentReadyFor(true, 0, 0));
        assertFalse(MarkdownWebView.documentReadyFor(false, 0, 0));
    }

    @Test
    public void notReadyBeforePageCallback() {
        // 代号对，但 onPageFinished 还没来
        assertFalse(MarkdownWebView.documentReadyFor(false, 5, 5));
    }
}
