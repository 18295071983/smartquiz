package com.oilquiz.app.ai.chat.media;

import androidx.media3.common.MediaItem;

/**
 * 当前媒体的 DRM 配置（P2，2026-10-25）。
 *
 * <p>与请求头/封面同一套单例约定：卡片在起播前写入，服务侧建 MediaItem 时读取；
 * 新曲目没有 DRM 时必须 {@link #clear()}，避免把上一首的 license 配置带过去。</p>
 */
public final class PlaybackDrm {

    private static volatile MediaItem.DrmConfiguration current;

    private PlaybackDrm() {
    }

    public static void set(MediaItem.DrmConfiguration configuration) {
        current = configuration;
    }

    public static MediaItem.DrmConfiguration get() {
        return current;
    }

    public static void clear() {
        current = null;
    }
}
