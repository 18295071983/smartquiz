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
 * DASH（.mpd）支持守卫（2026-10-24）。
 *
 * <p>和 HLS 同一套道理：DASH 在 media3 里也是<b>独立模块</b>。只引 media3-exoplayer 时
 * {@code DefaultMediaSourceFactory} 反射找不到 {@code DashMediaSource.Factory}，prepare 阶段报
 * {@code No suitable media source factory found for content type: 0}（0 = DASH）。</p>
 *
 * <p>两条断言分别钉住：①.mpd（含带 query 的地址、大写扩展名、本地路径）显式带上 DASH MIME；
 * ②真的能造出 DashMediaSource。缺依赖时第 2 条必红，防止以后清依赖把 DASH 悄悄删掉。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AudioPlaybackManagerDashTest {

    @Test
    public void mpdUrlsGetDashMimeType() {
        assertEquals("纯 .mpd 地址必须给 DASH MIME",
                MimeTypes.APPLICATION_MPD,
                AudioPlaybackManager.mimeTypeOf(
                        "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd"));
        assertEquals("带 query 的 DASH 地址（token/时间戳）也要认出来",
                MimeTypes.APPLICATION_MPD,
                AudioPlaybackManager.mimeTypeOf("https://cdn.example.com/live/manifest.MPD?token=abc"));
        assertEquals("本地 mpd 同样按 DASH 处理",
                MimeTypes.APPLICATION_MPD,
                AudioPlaybackManager.mimeTypeOf("/sdcard/Download/a.mpd"));
        assertNull("mp4 继续走默认推断",
                AudioPlaybackManager.mimeTypeOf("https://cdn.example.com/a.mp4"));
        assertNull("扩展名不是 mpd 就别硬套 DASH",
                AudioPlaybackManager.mimeTypeOf("https://cdn.example.com/a.mpd.mp4"));
        assertNull(AudioPlaybackManager.mimeTypeOf(null));
    }

    @Test
    public void dashMediaSourceCanBeBuilt() {
        Context context = ApplicationProvider.getApplicationContext();
        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(context);
        MediaItem item = new MediaItem.Builder()
                .setUri("https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd")
                .setMimeType(MimeTypes.APPLICATION_MPD)
                .build();
        MediaSource source = factory.createMediaSource(item);
        assertNotNull(source);
        assertTrue("mpd 必须落到 DashMediaSource；缺 media3-exoplayer-dash 时这里会抛 "
                        + "'No suitable media source factory found for content type: 0'，实际是 "
                        + source.getClass().getName(),
                source.getClass().getName().contains("DashMediaSource"));
    }
}
