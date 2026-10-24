package com.oilquiz.app.ai.chat.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.media3.common.MimeTypes;

import org.junit.Test;

/** P2 协议 MIME：RTSP/SmoothStreaming 显式识别，RTMP 交给 progressive + RtmpDataSource。 */
public class ProtocolMimeTest {

    @Test
    public void smoothStreamingManifestIsRecognized() {
        assertEquals(MimeTypes.APPLICATION_SS,
                AudioPlaybackManager.mimeTypeOf("https://x/manifest.ism/manifest"));
        assertEquals(MimeTypes.APPLICATION_SS,
                AudioPlaybackManager.mimeTypeOf("https://x/a.isml?token=1"));
    }

    @Test
    public void rtspIsRecognizedByScheme() {
        assertEquals(MimeTypes.APPLICATION_RTSP,
                AudioPlaybackManager.mimeTypeOf("rtsp://camera.local/live"));
    }

    @Test
    public void rtmpFallsBackToProgressive() {
        assertNull("RTMP 不能标 HLS/DASH，否则会选错 MediaSource",
                AudioPlaybackManager.mimeTypeOf("rtmp://host/live/stream"));
    }

    @Test
    public void hlsAndDashStillWin() {
        assertEquals(MimeTypes.APPLICATION_M3U8,
                AudioPlaybackManager.mimeTypeOf("https://x/a.m3u8?token=1"));
        assertEquals(MimeTypes.APPLICATION_MPD,
                AudioPlaybackManager.mimeTypeOf("https://x/a.mpd"));
    }
}
