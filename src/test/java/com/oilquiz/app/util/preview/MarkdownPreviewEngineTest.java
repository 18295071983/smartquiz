package com.oilquiz.app.util.preview;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;

/**
 * Markdown 缩略图引擎（2026-10-25）。
 *
 * <p>缩略图从"Canvas.drawText 画去标签纯文本"改成 MarkdownDocPage + 离屏 WebView 截图。
 * 这里钉住三件事：支持的扩展名、页面确实来自 MarkdownDocPage（而不是旧的正则转换）、
 * 相对图片的 baseUrl 指向 md 所在目录。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class MarkdownPreviewEngineTest {

    private final MarkdownPreviewEngine engine = new MarkdownPreviewEngine();

    private static Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void supportsMarkdownExtensionsOnly() {
        assertTrue(engine.supports(new File("/x/readme.md")));
        assertTrue("扩展名大小写不敏感", engine.supports(new File("/x/README.MARKDOWN")));
        assertFalse(engine.supports(new File("/x/readme.txt")));
        assertFalse(engine.supports(new File("/x/readme.html")));
    }

    @Test
    public void thumbnailPageComesFromMarkdownDocPage() {
        String html = MarkdownPreviewEngine.buildThumbnailHtml(ctx(),
                "# 标题\n\n| 左 | 右 |\n|---|---|\n| 1 | 2 |");

        assertTrue("缩略图页面要带 md-src 与 markdown-it", html.contains("id=\"md-src\""));
        assertTrue(html.contains("markdownit({"));
        assertTrue("表格原文交给前端渲染", html.contains("| 左 | 右 |"));
        assertFalse("不能再是旧的正则转 HTML", html.contains("<html><body><p>#"));
    }

    @Test
    public void baseUrlPointsAtMarkdownDirectory() {
        File dir = new File(ctx().getCacheDir(), "notes");
        String url = MarkdownPreviewEngine.baseUrlOf(new File(dir, "a.md"));

        assertTrue(url.startsWith("file://"));
        assertTrue(url.endsWith("notes/"));
        assertFalse(url.contains("a.md"));
        assertNull(MarkdownPreviewEngine.baseUrlOf(null));
    }
}
