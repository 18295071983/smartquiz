package com.oilquiz.app.ai.chat.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.source.BehindLiveWindowException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 直播 HLS 的起播点与自愈（2026-10-23）。
 *
 * <p>真机：IPTV 直播放 20 多秒就停，日志是
 * {@code ExoPlaybackException: Source error ← BehindLiveWindowException}（HlsChunkSource）。
 * 这类源是 5×5s=25s 的滑动窗口，ExoPlayer 默认从"边缘后 3 个 targetDuration"起播，
 * 只剩 6-7s 余量，一次卡顿就被窗口甩下。这里钉住两件事：起播点收到 10s；
 * 以及被甩下时能识别成可自愈错误（由 manager 自动回直播边缘重连）。</p>
 *
 * <p>必须跑在 Robolectric 里：{@code MediaItem.Builder.setUri(String)} 走
 * {@code android.net.Uri.parse}，纯 JVM 下 android.jar 是桩（returnDefaultValues 给 null），
 * uri 为空时 MediaItem 的 localConfiguration 就是 null，断言会假失败。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AudioPlaybackManagerLiveTest {

    @Test
    public void hlsItemStartsCloserToLiveEdge() {
        MediaItem item = AudioPlaybackManager.buildMediaItem(
                "http://112.27.5.218:9901/tsfile/live/0142_1.m3u8?key=txiptv&playlive=1&authid=0",
                "湖南卫视", "");
        assertNotNull("m3u8 必须带 uri/localConfiguration", item.localConfiguration);
        assertEquals(MimeTypes.APPLICATION_M3U8, item.localConfiguration.mimeType);
        assertNotNull("m3u8 必须带直播起播配置，否则默认落后 3 个分片", item.liveConfiguration);
        assertEquals("起播点应收到离直播边缘 10s", 10_000L,
                item.liveConfiguration.targetOffsetMs);
    }

    @Test
    public void nonHlsItemKeepsDefaultInference() {
        MediaItem item = AudioPlaybackManager.buildMediaItem("https://example.com/a.mp4", "v", "");
        assertNotNull(item.localConfiguration);
        assertNull(item.localConfiguration.mimeType);
        // MediaItem 的 liveConfiguration 永远非 null（默认值），没显式设过就是 TIME_UNSET
        assertEquals("非 HLS 不能带自定义起播偏移", C.TIME_UNSET,
                item.liveConfiguration.targetOffsetMs);
    }

    @Test
    public void behindLiveWindowIsRecognizedThroughCauseChain() {
        PlaybackException wrapped = new PlaybackException("Source error",
                new BehindLiveWindowException(), PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        assertTrue("被 PlaybackException 包一层也要认出来", AudioPlaybackManager.isBehindLiveWindow(wrapped));

        assertFalse(AudioPlaybackManager.isBehindLiveWindow(new PlaybackException("x", null,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)));
        assertFalse(AudioPlaybackManager.isBehindLiveWindow(null));
    }

    @Test
    public void errorsAreTranslatedForCards() {
        assertEquals("网络连接失败", AudioPlaybackManager.describeError(new PlaybackException(
                "timeout", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)));
        assertEquals("服务器拒绝请求（HTTP 错误）", AudioPlaybackManager.describeError(new PlaybackException(
                "403", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)));
        assertEquals("没消息时不能把 null 拼到卡片上", "播放失败",
                AudioPlaybackManager.describeError(
                        new PlaybackException(null, null, PlaybackException.ERROR_CODE_UNSPECIFIED)));
    }
}
