package com.oilquiz.app.ai.chat.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * {@link MarkdownLinkRouter#normalizeAssetHref} 的口径。
 *
 * <p>主气泡的 WebView 用 {@code file:///android_asset/} 当 baseUrl，模型写的
 * {@code [报告](files/报告.md)} 会被浏览器补成 assets 目录下一条根本不存在的路径。
 * 路由器必须先把这层假前缀剥掉，否则工作区解析永远命中不了，链接就"点了没反应"。</p>
 */
public class MarkdownLinkRouterTest {

    @Test
    public void stripsBogusAssetPrefix() {
        String got = MarkdownLinkRouter.normalizeAssetHref("file:///android_asset/files/报告.md");
        assertEquals("files/报告.md", got);
        assertFalse("不应残留 android_asset 假路径", got.contains("android_asset"));
    }

    @Test
    public void leavesRealUrlsUntouched() {
        assertEquals("https://example.com/a", MarkdownLinkRouter.normalizeAssetHref("https://example.com/a"));
        assertEquals("/storage/emulated/0/x.md", MarkdownLinkRouter.normalizeAssetHref("/storage/emulated/0/x.md"));
        assertEquals("tel:10086", MarkdownLinkRouter.normalizeAssetHref("tel:10086"));
    }
}
