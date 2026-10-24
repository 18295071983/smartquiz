package com.oilquiz.app.ai.chat.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;

/**
 * 整篇 Markdown 页面（2026-10-25）。
 *
 * <p>文件预览（FileRenderActivity）和缩略图（MarkdownPreviewEngine）都走
 * {@code buildForScreen(..., resolveWorkspaceImages=false)}：页面自带 markdown-it + 插件 +
 * KaTeX + highlight.js，相对图片交给 baseUrl（md 所在目录）解析。这里钉住这两条约定，
 * 避免以后又退回"服务端正则转 HTML + 无样式 WebView"的老路。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class MarkdownDocPageTest {

    private static Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void screenPageIsSelfContainedMarkdownItWithKatexAndHighlight() {
        String page = MarkdownDocPage.buildForScreen(ctx(),
                "# 标题\n\n公式 $a^2+b^2=c^2$\n\n```java\nint a = 1;\n```", false, false);

        assertTrue("正文应进 md-src，由页面脚本渲染", page.contains("id=\"md-src\""));
        assertTrue("应内联 markdown-it", page.contains("markdownit({"));
        assertTrue("脚注/定义列表/任务列表插件应可挂载", page.contains("markdownitFootnote"));
        assertTrue("公式走 KaTeX auto-render", page.contains("renderMathInElement"));
        assertTrue("代码块走 highlight.js", page.contains("highlightElement"));
        assertTrue("KaTeX 字体应内联成 data URI", page.contains("data:font/woff2;base64,"));
        assertTrue("全屏阅读页限宽排版", page.contains("max-width:860px"));
        assertTrue("原文应原样保留", page.contains("# 标题"));
    }

    @Test
    public void filePreviewPageKeepsRelativeImagesForBaseUrl() {
        String md = "![流程图](img/flow.png)";
        String page = MarkdownDocPage.buildForScreen(ctx(), md, false, false);

        // 文件预览不做工作区改写：相对地址原样留着，由 WebView 的 baseUrl（md 所在目录）解析
        assertTrue(page.contains(md));
        assertFalse("不应猜成工作区绝对路径", page.contains("file:///img/flow.png"));
    }

    @Test
    public void closingScriptTagInMarkdownIsEscaped() {
        String page = MarkdownDocPage.buildForScreen(ctx(),
                "正文</script><script>alert(1)</script>", false, false);

        assertFalse("md 原文不能提前闭合 md-src 脚本块",
                page.contains("</script><script>alert(1)"));
        assertTrue(page.contains("<\\/script>"));
    }

    @Test
    public void fileBaseUrlPointsAtMarkdownDirectory() {
        File dir = new File(ctx().getCacheDir(), "notes");
        String url = MarkdownDocView.fileBaseUrl(new File(dir, "a.md"));

        // 相对图片要按 md 所在目录解析：base 必须指向父目录、以 / 结尾，不能带文件名
        // （JVM 里 Robolectric 的 Uri 会把 Windows 路径分隔符转义，所以不断言 "/notes/"）
        assertTrue(url.startsWith("file://"));
        assertTrue(url.endsWith("notes/"));
        assertFalse(url.contains("a.md"));
    }

    @Test
    public void fileBaseUrlFallsBackToAssetsWithoutFile() {
        assertEquals("file:///android_asset/", MarkdownDocView.fileBaseUrl(null));
    }
}
