package com.oilquiz.app.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 协议判定的口径测试：哪些算协议、回退网页怎么挖、给用户列哪几条出路。
 *
 * <p>{@code localhost:8080} 那几条是重点——按字符看它就是合法 scheme，误判会把开发机地址
 * 当协议转交出去，用户看到的是"没应用能打开 localhost"。</p>
 */
public class BrowserSchemeTest {

    @Test
    public void classifiesWebAndInPageSchemes() {
        assertEquals(BrowserScheme.Kind.WEB, BrowserScheme.classify("https://example.com/a"));
        assertEquals(BrowserScheme.Kind.WEB, BrowserScheme.classify("http://example.com"));
        assertEquals(BrowserScheme.Kind.IN_PAGE, BrowserScheme.classify("file:///sdcard/a.pdf"));
        assertEquals(BrowserScheme.Kind.IN_PAGE, BrowserScheme.classify("about:blank"));
        assertEquals(BrowserScheme.Kind.IN_PAGE, BrowserScheme.classify("data:text/html,<p>a</p>"));
        assertEquals(BrowserScheme.Kind.SCRIPT, BrowserScheme.classify("javascript:alert(1)"));
    }

    @Test
    public void classifiesCustomAndCommunicationSchemes() {
        assertEquals(BrowserScheme.Kind.SYSTEM_APP, BrowserScheme.classify("weixin://dl/business/?t=x"));
        assertEquals(BrowserScheme.Kind.SYSTEM_APP, BrowserScheme.classify("alipays://platformapi/startapp"));
        assertEquals(BrowserScheme.Kind.SYSTEM_APP, BrowserScheme.classify("magnet:?xt=urn:btih:abc"));
        assertEquals(BrowserScheme.Kind.SYSTEM_APP, BrowserScheme.classify("ftp://10.0.0.1/pub"));
        assertEquals(BrowserScheme.Kind.SYSTEM_APP, BrowserScheme.classify("tel:10086"));
        assertEquals(BrowserScheme.Kind.SYSTEM_APP, BrowserScheme.classify("mailto:a@b.com"));
        assertEquals(BrowserScheme.Kind.INTENT, BrowserScheme.classify("intent://foo#Intent;end"));
        assertTrue(BrowserSchemeHandler.isSchemeNavigation("weixin://dl/business"));
        assertFalse(BrowserSchemeHandler.isSchemeNavigation("https://example.com"));
    }

    @Test
    public void doesNotMistakeHostWithPortForScheme() {
        assertEquals(BrowserScheme.Kind.NOT_A_SCHEME, BrowserScheme.classify("localhost:8080/x"));
        assertEquals(BrowserScheme.Kind.NOT_A_SCHEME, BrowserScheme.classify("127.0.0.1:3000"));
        assertEquals(BrowserScheme.Kind.NOT_A_SCHEME, BrowserScheme.classify("example.com:8443"));
        assertEquals(BrowserScheme.Kind.NOT_A_SCHEME, BrowserScheme.classify("微信"));
        assertEquals(BrowserScheme.Kind.NOT_A_SCHEME, BrowserScheme.classify("report.md"));
        // 中文标点/全角冒号也不能被当成协议（Character.isLetter('时') 是 true）
        assertEquals(BrowserScheme.Kind.NOT_A_SCHEME, BrowserScheme.classify("时间: 12:00"));
    }

    @Test
    public void anyRfcSchemeIsAProtocolWithoutAPresetList() {
        // 协议清单永远补不全：不在任何预设表里的私有协议也必须认（交给系统按 ACTION_VIEW 解析）
        assertEquals(BrowserScheme.Kind.SYSTEM_APP, BrowserScheme.classify("notascheme:whatever"));
        assertEquals(BrowserScheme.Kind.SYSTEM_APP, BrowserScheme.classify("myapp:open/page.html"));
        assertEquals(BrowserScheme.Kind.SYSTEM_APP, BrowserScheme.classify("spotify:track:abc"));
        assertTrue(BrowserSchemeHandler.isSchemeNavigation("notascheme:whatever"));
        // 常见协议名的全数字正文不能被"端口"误伤
        assertEquals(BrowserScheme.Kind.SYSTEM_APP, BrowserScheme.classify("tel:10086"));
        assertEquals(BrowserScheme.Kind.SYSTEM_APP, BrowserScheme.classify("sms:10086"));
        // 地址栏的口径更严：带冒号的普通话仍按搜索处理，除非系统里真有应用能接
        assertFalse(BrowserScheme.isExplicitProtocol("Note: hello"));
        assertTrue(BrowserScheme.isExplicitProtocol("myapp://open"));
        assertTrue(BrowserScheme.isExplicitProtocol("tel:10086"));
    }

    @Test
    public void unknownSchemeStillCountsAsSchemeWhenItHasAnAuthority() {
        assertEquals(BrowserScheme.Kind.SYSTEM_APP, BrowserScheme.classify("myapp://open/page"));
        assertTrue(BrowserSchemeHandler.isSchemeNavigation("myapp://open/page"));
    }

    @Test
    public void readsIntentBrowserFallbackUrl() {
        assertEquals("https://render.alipay.com/p/y.html",
                BrowserScheme.webFallback("intent://m/#Intent;scheme=alipays;"
                        + "package=com.eg.android.AlipayGphone;"
                        + "S.browser_fallback_url=https%3A%2F%2Frender.alipay.com%2Fp%2Fy.html;end"));
        // 站点没按规范编码回退地址时也要认
        assertEquals("https://m.example.com/x?a=1",
                BrowserScheme.webFallback("intent://m/#Intent;scheme=tbopen;"
                        + "S.browser_fallback_url=https://m.example.com/x?a=1;end"));
    }

    @Test
    public void readsWebUrlFromAppLinkQueryParams() {
        assertEquals("https://example.com/page",
                BrowserScheme.webFallback("weixin://dl/business/?url=https%3A%2F%2Fexample.com%2Fpage"));
        assertEquals("https://m.taobao.com/x",
                BrowserScheme.webFallback("tbopen://m.taobao.com/tbopen/index.html?h5Url=https://m.taobao.com/x"));
        // 双重编码（%25253A%25252F…）：解一层再扫
        assertEquals("https://m.example.com/deep",
                BrowserScheme.webFallback("myapp://open?target=https%253A%252F%252Fm.example.com%252Fdeep"));
        assertNull(BrowserScheme.webFallback("weixin://dl/business/?text=a+b"));
    }

    @Test
    public void keepsPlusInFallbackValuesAndRejectsScriptFallback() {
        // URLDecoder 会把 + 变成空格，回退地址的查询值会被改坏
        assertEquals("https://example.com/s?q=a+b",
                BrowserScheme.webFallback("myapp://go?url=https%3A%2F%2Fexample.com%2Fs%3Fq%3Da%2Bb"));
        assertNull(BrowserScheme.webFallback("myapp://go?url=javascript%3Aalert(1)"));
    }

    @Test
    public void intentWithoutFallbackDoesNotGuessFromItsQuery() {
        // intent 串的 query 是给 App 的参数，不是网页地址
        assertNull(BrowserScheme.webFallback("intent://scan/?url=note#Intent;scheme=zxing;end"));
    }

    @Test
    public void listsWaysOutInOrder() {
        List<String> unresolved = BrowserScheme.optionsFor("weixin://dl/business?url=https%3A%2F%2Fx.com%2Fp",
                BrowserScheme.Kind.SYSTEM_APP, false, "https://x.com/p");
        assertEquals(java.util.Arrays.asList(BrowserScheme.OPT_WEB_FALLBACK,
                BrowserScheme.OPT_SYSTEM_BROWSER, BrowserScheme.OPT_COPY), unresolved);

        List<String> resolved = BrowserScheme.optionsFor("weixin://dl/business",
                BrowserScheme.Kind.SYSTEM_APP, true, null);
        assertEquals(java.util.Arrays.asList(BrowserScheme.OPT_SYSTEM_APP,
                BrowserScheme.OPT_SYSTEM_BROWSER, BrowserScheme.OPT_COPY), resolved);

        // 拨号/写信这类：系统浏览器帮不上，不给那条出路
        List<String> tel = BrowserScheme.optionsFor("tel:10086",
                BrowserScheme.Kind.SYSTEM_APP, false, null);
        assertEquals(java.util.Collections.singletonList(BrowserScheme.OPT_COPY), tel);
        assertTrue(BrowserScheme.isCommunicationScheme("mailto:a@b.com"));
        assertFalse(BrowserScheme.isCommunicationScheme("weixin://x"));
    }

    @Test
    public void policyDefaultsToAsk() {
        assertEquals(BrowserScheme.POLICY_ASK, BrowserScheme.normalizePolicy(null));
        assertEquals(BrowserScheme.POLICY_ASK, BrowserScheme.normalizePolicy("  "));
        assertEquals(BrowserScheme.POLICY_ASK, BrowserScheme.normalizePolicy("always"));
        assertEquals(BrowserScheme.POLICY_SYSTEM_APP, BrowserScheme.normalizePolicy("SYSTEM"));
        assertEquals(BrowserScheme.POLICY_SYSTEM_BROWSER, BrowserScheme.normalizePolicy("browser"));
    }
}
