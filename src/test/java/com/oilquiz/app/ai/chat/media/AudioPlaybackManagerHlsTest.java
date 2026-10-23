package com.oilquiz.app.ai.chat.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * HLS（.m3u8）支持守卫（2026-10-23）。
 *
 * <p>真机：直播/点播的 m3u8 地址在卡片里点开就失败，App 侧只能退化成"用 html 卡 + hls.js
 * 在 WebView 里播"。根因是 HLS 在 media3 里是<b>独立模块</b>：只引 {@code media3-exoplayer}
 * 时 {@code DefaultMediaSourceFactory} 反射找不到 {@code HlsMediaSource.Factory}，
 * prepare 阶段直接抛 "Module HLS not present"。</p>
 *
 * <p>两条断言分别钉住：①m3u8（含带 query 的直播地址）显式带上 HLS 的 MIME；
 * ②真的能造出 HlsMediaSource。缺依赖时第 2 条会失败，避免以后清理依赖又把 HLS 悄悄删掉。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AudioPlaybackManagerHlsTest {

    @Test
    public void m3u8UrlsGetHlsMimeType() {
        assertEquals("纯 .m3u8 地址必须给 HLS MIME",
                MimeTypes.APPLICATION_M3U8,
                AudioPlaybackManager.mimeTypeOf("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"));
        assertEquals("带 query 的直播地址（token/时间戳）也要认出来",
                MimeTypes.APPLICATION_M3U8,
                AudioPlaybackManager.mimeTypeOf(
                        "https://cdn.example.com/live/index.M3U8?token=abc&t=1792750000"));
        assertEquals("本地 m3u8 同样按 HLS 处理",
                MimeTypes.APPLICATION_M3U8,
                AudioPlaybackManager.mimeTypeOf("/sdcard/Download/a.m3u8"));
        assertNull("mp4 继续走默认推断",
                AudioPlaybackManager.mimeTypeOf("https://cdn.example.com/a.mp4"));
        assertNull("扩展名不是 m3u8 就别硬套 HLS",
                AudioPlaybackManager.mimeTypeOf("https://cdn.example.com/a.m3u8.mp4"));
        assertNull(AudioPlaybackManager.mimeTypeOf(null));
    }

    @Test
    public void hlsMediaSourceCanBeBuilt() {
        Context context = ApplicationProvider.getApplicationContext();
        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(context);
        MediaItem item = new MediaItem.Builder()
                .setUri("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build();
        MediaSource source = factory.createMediaSource(item);
        assertNotNull(source);
        assertTrue("m3u8 必须落到 HlsMediaSource；缺 media3-exoplayer-hls 时这里会抛 "
                        + "'Module HLS not present'，实际是 " + source.getClass().getName(),
                source.getClass().getName().contains("HlsMediaSource"));
    }
}
