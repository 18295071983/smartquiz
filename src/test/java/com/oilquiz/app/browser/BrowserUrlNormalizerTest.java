package com.oilquiz.app.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BrowserUrlNormalizerTest {

    @Test
    public void bareDomain_getsHttps() {
        assertEquals("https://baidu.com", BrowserUrlNormalizer.toLoadableUrl("baidu.com"));
        assertEquals("https://www.zhihu.com", BrowserUrlNormalizer.toLoadableUrl("www.zhihu.com"));
    }

    @Test
    public void webUrl_isPreservedAndEncoded() {
        assertEquals("https://example.com/a", BrowserUrlNormalizer.toLoadableUrl("https://example.com/a"));
        assertEquals("https://example.com/a%20b", BrowserUrlNormalizer.toLoadableUrl("https://example.com/a b"));
        assertEquals("https://example.com/%E4%B8%AD%E6%96%87",
                BrowserUrlNormalizer.toLoadableUrl("example.com/中文"));
    }

    @Test
    public void idnHost_isPunycodeEncoded() {
        String url = BrowserUrlNormalizer.toLoadableUrl("https://例子.测试/a");
        assertTrue(url, url.startsWith("https://xn--"));
        assertTrue(url, url.endsWith("/a"));
    }

    @Test
    public void localAndIpAddresses_useHttp() {
        assertEquals("http://localhost:8080", BrowserUrlNormalizer.toLoadableUrl("localhost:8080"));
        assertEquals("http://192.168.1.1:8080/x", BrowserUrlNormalizer.toLoadableUrl("192.168.1.1:8080/x"));
        assertEquals("http://10.0.0.2", BrowserUrlNormalizer.toLoadableUrl("10.0.0.2"));
        assertEquals("http://8.8.8.8", BrowserUrlNormalizer.toLoadableUrl("8.8.8.8"));
    }

    @Test
    public void freeText_goesToSearch() {
        String search = BrowserUrlNormalizer.SEARCH_PREFIX;
        assertEquals(search + "hello+world", BrowserUrlNormalizer.toLoadableUrl("hello world"));
        assertEquals(search + "%E6%8A%A5%E5%91%8A.md", BrowserUrlNormalizer.toLoadableUrl("报告.md"));
        assertEquals(search + "oil+quiz", BrowserUrlNormalizer.toLoadableUrl("oil quiz"));
    }

    @Test
    public void dangerousScheme_isRejected() {
        assertNull(BrowserUrlNormalizer.toLoadableUrl("javascript:alert(1)"));
        assertNull(BrowserUrlNormalizer.toLoadableUrl("   "));
        assertNull(BrowserUrlNormalizer.toLoadableUrl(null));
    }

    @Test
    public void otherLocalSchemes_arePreserved() {
        assertEquals("file:///sdcard/a.html",
                BrowserUrlNormalizer.toLoadableUrl("file:///sdcard/a.html"));
        assertEquals("content://media/1",
                BrowserUrlNormalizer.toLoadableUrl("content://media/1"));
    }

    @Test
    public void displayUrl_hidesSchemeAndHome() {
        assertEquals("example.com", BrowserUrlNormalizer.displayUrl("https://example.com/"));
        assertEquals("example.com/a", BrowserUrlNormalizer.displayUrl("http://example.com/a"));
        assertEquals("", BrowserUrlNormalizer.displayUrl(BrowserUrlNormalizer.HOME_URL));
    }

    @Test
    public void hostAndSchemeHelpers() {
        assertEquals("www.example.com", BrowserUrlNormalizer.hostOf("https://www.example.com:8443/a"));
        assertTrue(BrowserUrlNormalizer.isWebUrl("HTTPS://example.com"));
        assertTrue(BrowserUrlNormalizer.isHttps("https://example.com"));
        assertFalse(BrowserUrlNormalizer.isHttps("http://example.com"));
        assertFalse(BrowserUrlNormalizer.isWebUrl("file:///tmp/a"));
    }

    @Test
    public void hostDetection_rejectsBareWordsAndCjk() {
        assertFalse(BrowserUrlNormalizer.looksLikeHost("mydomain"));
        assertFalse(BrowserUrlNormalizer.looksLikeHost("报告.md"));
        assertFalse(BrowserUrlNormalizer.looksLikeHost("hello world"));
        assertTrue(BrowserUrlNormalizer.looksLikeHost("example.com"));
        assertTrue(BrowserUrlNormalizer.looksLikeHost("example.com/a"));
        assertTrue(BrowserUrlNormalizer.looksLikeHost("localhost:8080"));
    }
}
