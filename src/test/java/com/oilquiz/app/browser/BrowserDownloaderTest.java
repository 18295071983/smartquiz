package com.oilquiz.app.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class BrowserDownloaderTest {

    @Test
    public void fileName_fromUrlIsDecoded() {
        assertEquals("a b.pdf",
                BrowserDownloader.guessFileName("https://x.test/files/a%20b.pdf", null, "application/pdf"));
    }

    @Test
    public void fileName_fromContentDisposition() {
        assertEquals("报告.pptx", BrowserDownloader.guessFileName(
                "https://x.test/download",
                "attachment; filename=\"报告.pptx\"",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
    }

    @Test
    public void fileName_rfc5987ContentDisposition() {
        assertEquals("报告.pdf", BrowserDownloader.guessFileName(
                "https://x.test/download",
                "attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf",
                "application/pdf"));
    }

    @Test
    public void fileName_getsExtensionFromMimeWhenMissing() {
        assertEquals("download.pdf",
                BrowserDownloader.guessFileName("https://x.test/download", null, "application/pdf"));
    }

    @Test
    public void fileNameForBlob_usesDispositionThenMime() {
        assertEquals("x.zip", BrowserDownloader.fileNameFromDispositionOrMime(
                "attachment; filename=\"x.zip\"", "application/zip"));
        assertEquals("download.pdf",
                BrowserDownloader.fileNameFromDispositionOrMime(null, "application/pdf"));
        assertEquals("download.bin",
                BrowserDownloader.fileNameFromDispositionOrMime(null, null));
    }

    @Test
    public void sanitize_removesPathSeparatorsAndReservedChars() {
        String cleaned = BrowserDownloader.sanitize("../../evil:name?.txt");
        assertFalse(cleaned.contains("/"));
        assertFalse(cleaned.contains("\\"));
        assertFalse(cleaned.contains(":"));
        assertFalse(cleaned.contains("?"));
        assertTrue(cleaned.endsWith(".txt"));
    }

    @Test
    public void sanitize_neverReturnsEmpty() {
        assertEquals("download", BrowserDownloader.sanitize("..."));
        assertEquals("download", BrowserDownloader.sanitize(null));
        assertEquals("download", BrowserDownloader.sanitize("   "));
    }
}
