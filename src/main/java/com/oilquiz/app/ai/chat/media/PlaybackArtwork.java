package com.oilquiz.app.ai.chat.media;

import android.net.Uri;

/**
 * 当前媒体的封面（P1，2026-10-25）。
 *
 * <p>和 {@link PlaybackHeaders} 一样，服务侧建 MediaItem 时拿不到卡片的 props，
 * 但播放器是单例、同一时刻只有一个当前媒体，所以用一张"当前封面"表即可。
 * 新曲目没有封面时必须 {@link #clear()}，避免上一首的封面留在通知栏。</p>
 */
public final class PlaybackArtwork {

    private static volatile Uri current;

    private PlaybackArtwork() {
    }

    public static void set(Uri artwork) {
        current = artwork;
    }

    public static Uri get() {
        return current;
    }

    public static void clear() {
        current = null;
    }
}
