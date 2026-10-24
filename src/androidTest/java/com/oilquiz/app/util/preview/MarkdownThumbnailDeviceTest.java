package com.oilquiz.app.util.preview;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 真机验证 Markdown 缩略图（MarkdownDocPage + 离屏 WebView 截图）。
 *
 * <p>为什么必须有这个测试：缩略图完全依赖"没挂到窗口上的 WebView 也能 draw 出内容"，
 * 这是 Robolectric 影子对象证明不了的（它不跑 WebView 内核）；之前那版 Canvas 画文本
 * 不需要真机，现在这版不真机跑一次就不知道会不会截出全白图。</p>
 *
 * <p>测试同时把截图落到外部 files 目录，方便 adb pull 出来肉眼确认排版。</p>
 *
 * <p>运行：
 * <pre>
 * adb shell am instrument -w -e class com.oilquiz.app.util.preview.MarkdownThumbnailDeviceTest \
 *     com.oilquiz.app.test/androidx.test.runner.AndroidJUnitRunner
 * </pre></p>
 */
@RunWith(AndroidJUnit4.class)
public class MarkdownThumbnailDeviceTest {

    private Context ctx;
    private File dir;

    @Before
    public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        dir = new File(ctx.getCacheDir(), "md_thumb_test");
        assertTrue("无法创建 " + dir, dir.exists() || dir.mkdirs());
    }

    @After
    public void tearDown() {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
    }

    @Test
    public void offscreenWebViewProducesNonBlankThumbnail() throws Exception {
        // 内容带随机后缀：缓存按内容哈希，避免命中上一轮（可能是空图）的缓存
        String markdown = "# 缩略图验收 " + System.currentTimeMillis() + "\n\n"
                + "正文包含 **粗体**、`行内代码` 和 [链接](https://example.com)。\n\n"
                + "| 列 A | 列 B |\n|---|---|\n| 1 | 2 |\n\n"
                + "$$E = mc^2$$\n\n```java\npublic class Demo {}\n```\n";
        File md = new File(dir, "thumb.md");
        try (FileOutputStream fos = new FileOutputStream(md)) {
            fos.write(markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Bitmap> bitmap = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
        new MarkdownPreviewEngine().preview(ctx, md, new PreviewEngine.PreviewCallback() {
            @Override
            public void onSuccess(Bitmap previewBitmap) {
                bitmap.set(previewBitmap);
                done.countDown();
            }

            @Override
            public void onFailure(String message) {
                error.set(message);
                done.countDown();
            }
        }, null);

        assertTrue("缩略图生成超时", done.await(40, TimeUnit.SECONDS));
        assertNotNull("缩略图生成失败: " + error.get(), bitmap.get());

        Bitmap shot = bitmap.get();
        assertTrue("宽度应缩到 720: " + shot.getWidth(), shot.getWidth() == 720);
        assertTrue("高度应大于 0: " + shot.getHeight(), shot.getHeight() > 0);

        // 全白 = WebView 没画出来（或页面没渲染），用非白像素比例兜底
        double ink = nonWhiteRatio(shot);
        assertTrue("截图几乎全白（ink=" + ink + "），离屏 WebView 可能没渲染",
                ink > 0.005d);

        File out = new File(ctx.getExternalFilesDir(null), "md-thumb-device-test.png");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            shot.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        assertTrue("截图应已落盘: " + out, out.isFile() && out.length() > 0);
        // 再解码一次，确认文件是可读的 PNG（adb pull 出来的就是它）
        assertNotNull(BitmapFactory.decodeFile(out.getAbsolutePath()));
    }

    /** 采样网格里非白像素占比（只统计明显偏暗的像素，抗 JPEG/抗锯齿噪点）。 */
    private static double nonWhiteRatio(Bitmap bmp) {
        int stepX = Math.max(1, bmp.getWidth() / 60);
        int stepY = Math.max(1, bmp.getHeight() / 80);
        int total = 0;
        int ink = 0;
        for (int y = 0; y < bmp.getHeight(); y += stepY) {
            for (int x = 0; x < bmp.getWidth(); x += stepX) {
                int c = bmp.getPixel(x, y);
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                total++;
                if (r < 200 || g < 200 || b < 200) ink++;
            }
        }
        return total == 0 ? 0d : (double) ink / total;
    }
}
