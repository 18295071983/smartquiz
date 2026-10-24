package com.oilquiz.app.ai.chat.render;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 主气泡 Markdown：表格包装、脚注预处理，避免回退到旧的 table display:block。 */
public class MarkdownHtmlConverterTest {

    @Test
    public void tableUsesScrollWrapperNotDisplayBlock() {
        String body = MarkdownHtmlConverter.markdownToBodyHtml(
                "| 左 | 右 |\n|---|---|\n| 1 | 2 |");
        assertTrue(body.contains("<table>"));
        String doc = MarkdownHtmlConverter.wrapDocument(body, false);
        assertTrue(doc.contains(".table-wrap"));
        assertTrue(doc.contains("function qzTables"));
        assertFalse(doc.contains("display:block;overflow-x:auto"));
    }

    @Test
    public void footnotesBecomeSupAndSection() {
        String html = MarkdownHtmlConverter.markdownToBodyHtml(
                "正文引用[^1]\n\n[^1]: 脚注内容");
        assertTrue(html.contains("qz-fn-ref"));
        assertTrue(html.contains("id=\"fn-1\""));
        assertTrue(html.contains("脚注内容"));
        assertFalse(html.contains("[^1]:"));
    }

    @Test
    public void footnoteDefinitionInsideFenceIsUntouched() {
        String html = MarkdownHtmlConverter.markdownToBodyHtml(
                "```\n[^1]: 不是脚注\n```");
        assertFalse(html.contains("qz-footnotes"));
        assertTrue(html.contains("[^1]: 不是脚注"));
    }

    /** 思考正文与回答正文同用一个 WebView 渲染器，必须靠 body 类 + CSS 拉开排版差异。 */
    @Test
    public void reasoningDocumentUsesSecondaryTypography() {
        String doc = MarkdownHtmlConverter.wrapDocument("<p>思考</p>", false, null, true);
        assertTrue(doc.contains("class=\"light qz-reasoning\""));
        assertTrue(doc.contains("body.qz-reasoning{font-size:13px"));
        assertTrue(doc.contains("--muted"));

        // 普通回答正文绝不能带思考类/思考 CSS（两者共用 MarkdownWebView）
        String plain = MarkdownHtmlConverter.wrapDocument("<p>回答</p>", false);
        assertFalse(plain.contains("qz-reasoning"));
        assertTrue(plain.contains("font-size:15px"));
    }
}
