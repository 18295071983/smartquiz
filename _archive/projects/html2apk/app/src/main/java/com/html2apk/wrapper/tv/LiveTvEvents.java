package com.html2apk.wrapper.tv;

/**
 * 壳内直播事件总线（进程内）。
 *
 * <p>播放器是独立 Activity，但和 WebView 在同一个进程。播放器启动/出错/关闭时
 * 通过这里把事件交给 MainActivity，再以 {@code window.dispatchEvent(new CustomEvent('liveevent'))}
 * 的形式发给页面——不启动 Intent、不把 WebView 顶到前台，避免"一播放就跳回网页"。</p>
 */
public final class LiveTvEvents {

    public interface Listener {
        void onLiveEvent(String json);
    }

    private static volatile Listener listener;

    private LiveTvEvents() {
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void clear(Listener l) {
        if (listener == l) listener = null;
    }

    public static void emit(String json) {
        if (json == null || json.isEmpty()) return;
        Listener l = listener;
        if (l == null) return;
        try {
            l.onLiveEvent(json);
        } catch (Throwable ignored) {
        }
    }
}
