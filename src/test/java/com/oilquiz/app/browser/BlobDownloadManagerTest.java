package com.oilquiz.app.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
public class BlobDownloadManagerTest {

    @Test
    public void fetchScript_quotesUrlAndCallsBridge() {
        String script = BlobDownloadManager.buildFetchScript("rid-1", "blob:https://x.test/a\"b");
        assertTrue(script, script.contains("var rid=\"rid-1\""));
        // org.json 会把 / 转义成 \/，这里只校验 URL 与引号都被安全编码
        assertTrue(script, script.contains("blob:https:"));
        assertTrue(script, script.contains("a\\\"b"));
        assertTrue(script, script.contains("SmartQuizBlob.chunk"));
        assertTrue(script, script.contains("SmartQuizBlob.complete"));
        assertTrue(script, script.contains("SmartQuizBlob.fail"));
    }

    @Test
    public void dataUrl_base64IsDecoded() throws Exception {
        BlobDownloadManager.DataPayload payload =
                BlobDownloadManager.parseDataUrl("data:text/plain;base64,SGVsbG8=");
        assertNotNull(payload);
        assertEquals("text/plain", payload.mime);
        assertEquals("Hello", new String(payload.bytes, StandardCharsets.UTF_8));
    }

    @Test
    public void dataUrl_plainIsUrlDecoded() throws Exception {
        BlobDownloadManager.DataPayload payload =
                BlobDownloadManager.parseDataUrl("data:text/plain,Hi%20there");
        assertNotNull(payload);
        assertEquals("text/plain", payload.mime);
        assertEquals("Hi there", new String(payload.bytes, StandardCharsets.UTF_8));
    }

    @Test
    public void dataUrl_defaultsToPlainText() throws Exception {
        BlobDownloadManager.DataPayload payload =
                BlobDownloadManager.parseDataUrl("data:,abc");
        assertNotNull(payload);
        assertEquals("text/plain", payload.mime);
        assertEquals("abc", new String(payload.bytes, StandardCharsets.UTF_8));
    }

    @Test
    public void dataUrl_invalidReturnsNull() throws Exception {
        assertNull(BlobDownloadManager.parseDataUrl("https://x.test/a"));
        assertNull(BlobDownloadManager.parseDataUrl("data:no-comma"));
    }
}
