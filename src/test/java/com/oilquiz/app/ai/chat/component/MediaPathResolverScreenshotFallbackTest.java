package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;

/** 历史脏路径兜底：Pictures/Screenshots → DCIM/Screenshots（只限截图目录）。 */
@RunWith(RobolectricTestRunner.class)
public class MediaPathResolverScreenshotFallbackTest {

    @Test
    public void missingPicturesScreenshotFallsBackToDcimSibling() throws Exception {
        File dcim = java.nio.file.Files.createTempDirectory("dcim").toFile();
        File shots = new File(dcim, "Screenshots");
        assertTrue(shots.mkdirs());
        File target = new File(shots, "Screenshot_a.jpg");
        assertTrue(target.createNewFile());

        File got = MediaPathResolver.screenshotFallback(dcim,
                "file:///storage/emulated/0/Pictures/Screenshots/Screenshot_a.jpg");
        assertNotNull("应回退到 DCIM/Screenshots 的同名文件", got);
        assertEquals(target.getCanonicalFile(), got.getCanonicalFile());
    }

    @Test
    public void existingPicturesPathIsNotRewrittenHere() {
        // resolveToFile 会先命中真实路径；这里只锁兜底函数不处理非截图目录
        assertNull(MediaPathResolver.screenshotFallback(new File("/tmp/none"),
                "/storage/emulated/0/DCIM/Screenshots/a.jpg"));
        assertNull(MediaPathResolver.screenshotFallback(new File("/tmp/none"),
                "/storage/emulated/0/Pictures/a.jpg"));
    }

    @Test
    public void missingDcimSiblingReturnsNull() throws Exception {
        File dcim = java.nio.file.Files.createTempDirectory("dcim2").toFile();
        assertNull(MediaPathResolver.screenshotFallback(dcim,
                "/storage/emulated/0/Pictures/Screenshots/missing.jpg"));
    }
}
