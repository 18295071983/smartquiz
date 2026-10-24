package com.oilquiz.app.ai.chat.media;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 播放器请求头（P1，2026-10-25）。
 *
 * <p>ExoPlayer 的 DataSource 工厂在服务里创建，卡片拿不到它的实例；但播放器是进程级单例、
 * 同一时刻只有一个当前媒体，所以用一张"当前请求头"表就够了：卡片在起播前写入，
 * 服务侧 DataSource 每次创建时读取。新曲目不带 headers 时必须 {@link #set} 空表，
 * 避免上一首的 Cookie/Referer 泄到下一首。</p>
 *
 * <p>只允许 UA/Referer/Cookie 等普通头；不处理 DRM/证书。值不做日志输出。</p>
 */
public final class PlaybackHeaders {

    private static volatile Map<String, String> current = Collections.emptyMap();

    private PlaybackHeaders() {
    }

    public static void set(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            current = Collections.emptyMap();
            return;
        }
        current = Collections.unmodifiableMap(new HashMap<>(headers));
    }

    public static Map<String, String> get() {
        return current;
    }

    public static void clear() {
        current = Collections.emptyMap();
    }
}
