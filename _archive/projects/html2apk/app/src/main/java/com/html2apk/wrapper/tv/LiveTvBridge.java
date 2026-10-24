package com.html2apk.wrapper.tv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 电视直播桥的业务实现（与具体壳的包名解耦，两套壳共用同一份逻辑）。
 *
 * <p>MainActivity 的 {@code AppBridge} 只做一行转发；网络抓取、清单解析、缓存、
 * 收藏/源管理、启动播放器都在这里。所有 JS 回调仍然是
 * {@code window[callbackName]({...})} 的对象契约。</p>
 */
public final class LiveTvBridge {

    /** 宿主提供的最小能力：Context、主线程、JS 回调、IO 线程池 */
    public interface Host {
        Context context();

        void ui(Runnable r);

        void callback(String name, String json);

        ExecutorService io();
    }

    /** 交给播放器的频道列表上限（超出部分不传，避免播放器内存/界面压力） */
    private static final int MAX_PLAYER_CHANNELS = LiveSessionLauncher.MAX_PLAYER_CHANNELS;

    private final Host host;
    private final LiveStore store;
    private final LivePlaylistRepository repo;

    public LiveTvBridge(Host host) {
        this.host = host;
        this.store = new LiveStore(host.context());
        this.repo = new LivePlaylistRepository(host.context());
    }

    // ==================== 播放 ====================

    /** 播放单路直播流（HLS/HTTP 视频），返回同步结果 JSON */
    public String openLive(String url, String title, String headersJson) {
        try {
            String u = url == null ? "" : url.trim();
            if (!LivePlaylistRepository.isHttpUrl(u)) {
                return errorJson("直播地址必须是 http:// 或 https://");
            }
            Map<String, String> headers = parseHeaders(headersJson);
            String name = isBlank(title) ? "直播" : title.trim();
            LiveChannel ch = new LiveChannel(null, name, "", "", u, headers);
            host.ui(() -> startPlayer(Collections.singletonList(ch), 0, ""));
            return okJson();
        } catch (Exception e) {
            return errorJson("播放失败：" + message(e));
        }
    }

    /**
     * 加载远程频道源并打开播放器。
     *
     * @param startChannel 起始频道 id 或名称（可空=第一个）
     * @param callbackName 可空；回调 {ok,count,cached,warning,error}
     */
    public void openLiveTv(final String playlistUrl, final String sourceName,
                           final String startChannel, final String callbackName) {
        final String u = playlistUrl == null ? "" : playlistUrl.trim();
        if (!LivePlaylistRepository.isHttpUrl(u)) {
            respond(callbackName, errorJson("频道源地址必须是 http:// 或 https://"));
            return;
        }
        final String name = isBlank(sourceName) ? deriveSourceName(u) : sourceName.trim();
        host.io().execute(() -> {
            final LivePlaylistRepository.LoadResult r = repo.load(u, name);
            if (!r.ok()) {
                respond(callbackName, errorJson(r.error == null ? "频道源为空" : r.error));
                return;
            }
            // 已显式保存过的源，顺手刷新频道数；没保存过的临时源不落盘。
            store.updateSourceCount(u, r.channels.size());
            int index = findChannel(r.channels, startChannel);
            JSONObject resp = new JSONObject();
            try {
                resp.put("ok", true);
                resp.put("count", r.channels.size());
                resp.put("cached", r.cached);
                resp.put("singleStream", r.singleStream);
                if (r.error != null) resp.put("warning", r.error);
            } catch (Exception ignored) {
            }
            final int startIndex = index;
            host.ui(() -> {
                // 先回调（此时 WebView 仍在前台，evaluateJavascript 不会丢），再启动播放器
                respond(callbackName, resp.toString());
                startPlayer(r.channels, startIndex, name);
            });
        });
    }

    /** 页面自己拿到的清单文本（本地文件、带自定义鉴权的请求）→ 频道列表 */
    public void parseLiveChannels(final String text, final String baseUrl,
                                  final String callbackName) {
        if (text == null || text.isEmpty()) {
            respond(callbackName, errorJson("清单文本为空"));
            return;
        }
        if (text.length() > 8 * 1024 * 1024) {
            respond(callbackName, errorJson("清单超过 8MB"));
            return;
        }
        host.io().execute(() -> {
            LivePlaylistRepository.LoadResult r = repo.parse(text, baseUrl, "直播");
            respond(callbackName, resultJson(r).toString());
        });
    }

    /** 加载远程清单并返回频道列表（不打开播放器） */
    public void loadLiveChannels(final String playlistUrl, final String callbackName) {
        final String u = playlistUrl == null ? "" : playlistUrl.trim();
        if (!LivePlaylistRepository.isHttpUrl(u)) {
            respond(callbackName, errorJson("频道源地址必须是 http:// 或 https://"));
            return;
        }
        host.io().execute(() -> {
            LivePlaylistRepository.LoadResult r = repo.load(u, deriveSourceName(u));
            if (r.ok()) store.updateSourceCount(u, r.channels.size());
            respond(callbackName, resultJson(r).toString());
        });
    }

    /** 播放页面给的频道数组；channelsJson 可以是数组或 {"channels":[...]} */
    public void openLiveChannels(final String channelsJson, final int index,
                                 final String sourceName) {
        if (channelsJson == null || channelsJson.trim().isEmpty()) {
            emitError("频道列表为空");
            return;
        }
        final String source = sourceName == null ? "" : sourceName.trim();
        host.io().execute(() -> {
            List<LiveChannel> list = parseChannels(channelsJson);
            if (list.isEmpty()) {
                emitError("频道列表为空或格式不正确");
                return;
            }
            if (list.size() > MAX_PLAYER_CHANNELS) {
                list = new ArrayList<>(list.subList(0, MAX_PLAYER_CHANNELS));
            }
            final List<LiveChannel> play = list;
            final int idx = Math.max(0, Math.min(index, play.size() - 1));
            host.ui(() -> startPlayer(play, idx, source));
        });
    }

    /** 关闭当前直播播放器（没有打开时无副作用） */
    public void stopLive() {
        host.ui(LivePlayerActivity::finishIfOpen);
    }

    // ==================== 频道源 ====================

    public String saveLiveSource(String name, String url) {
        String u = url == null ? "" : url.trim();
        if (!LivePlaylistRepository.isHttpUrl(u)) {
            return errorJson("频道源地址必须是 http:// 或 https://");
        }
        try {
            LiveStore.Source s = store.saveSource(name, u, 0);
            if (s == null) return errorJson("保存失败");
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("source", s.toJson());
            return o.toString();
        } catch (Exception e) {
            return errorJson("保存失败：" + message(e));
        }
    }

    public String deleteLiveSource(String id) {
        boolean deleted = store.deleteSource(id);
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("deleted", deleted);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    public String getLiveSources() {
        JSONArray arr = new JSONArray();
        for (LiveStore.Source s : store.loadSources()) arr.put(s.toJson());
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("sources", arr);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    // ==================== 收藏 / 历史 ====================

    public String getLiveFavorites() {
        JSONArray arr = new JSONArray();
        for (LiveChannel c : store.loadFavorites()) arr.put(c.toJson());
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("channels", arr);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    public String toggleLiveFavorite(String channelJson) {
        try {
            JSONObject o = new JSONObject(channelJson == null ? "" : channelJson);
            LiveChannel ch = LiveChannel.fromJson(o);
            if (ch == null) return errorJson("频道数据无效");
            boolean fav = store.toggleFavorite(ch);
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("favorite", fav);
            r.put("channel", ch.toJson());
            return r.toString();
        } catch (Exception e) {
            return errorJson("收藏失败：" + message(e));
        }
    }

    public String getLastLiveChannel() {
        JSONObject last = store.getLastChannel();
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("last", last == null ? JSONObject.NULL : last);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    public String clearLiveHistory() {
        store.clearLastChannel();
        return okJson();
    }

    // ==================== 内部 ====================

    private void startPlayer(List<LiveChannel> channels, int index, String sourceName) {
        if (channels == null || channels.isEmpty()) {
            emitError("没有可播放的频道");
            return;
        }
        if (!LiveSessionLauncher.launch(host.context(), channels, index, sourceName)) {
            emitError("启动播放器失败");
        }
    }

    private List<LiveChannel> parseChannels(String json) {
        List<LiveChannel> out = new ArrayList<>();
        try {
            String s = json.trim();
            JSONArray arr;
            if (s.startsWith("[")) {
                arr = new JSONArray(s);
            } else {
                JSONObject o = new JSONObject(s);
                arr = o.optJSONArray("channels");
            }
            if (arr == null) return out;
            for (int i = 0; i < arr.length() && out.size() < MAX_PLAYER_CHANNELS; i++) {
                LiveChannel c = LiveChannel.fromJson(arr.optJSONObject(i));
                if (c != null && c.isValid()) out.add(c);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private JSONObject resultJson(LivePlaylistRepository.LoadResult r) {
        JSONObject o = new JSONObject();
        try {
            if (!r.ok()) {
                o.put("ok", false);
                o.put("error", r.error == null ? "频道源为空" : r.error);
                return o;
            }
            JSONArray arr = new JSONArray();
            for (LiveChannel c : r.channels) {
                if (arr.length() >= 3000) break;
                arr.put(c.toJson());
            }
            o.put("ok", true);
            o.put("count", r.channels.size());
            o.put("channels", arr);
            o.put("truncated", r.channels.size() > arr.length());
            o.put("cached", r.cached);
            o.put("singleStream", r.singleStream);
            if (r.error != null) o.put("warning", r.error);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static Map<String, String> parseHeaders(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyMap();
        Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(json);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext() && out.size() < 24) {
                String k = it.next();
                String v = o.optString(k, null);
                if (k != null && v != null && !v.isEmpty()) out.put(k, v);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static int findChannel(List<LiveChannel> channels, String wanted) {
        if (wanted == null || wanted.trim().isEmpty()) return 0;
        String w = wanted.trim();
        for (int i = 0; i < channels.size(); i++) {
            LiveChannel c = channels.get(i);
            if (c.id.equals(w) || c.name.equalsIgnoreCase(w)) return i;
        }
        // 名称包含匹配（页面上常用"CCTV1"匹配"CCTV-1 综合"）
        String lower = w.toLowerCase(Locale.US);
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).name.toLowerCase(Locale.US).contains(lower)) return i;
        }
        return 0;
    }

    private void respond(String callbackName, String json) {
        if (!validJsName(callbackName)) return;
        host.callback(callbackName, json);
    }

    private void emitError(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("action", "error");
            o.put("message", message == null ? "" : message);
        } catch (Exception ignored) {
        }
        LiveTvEvents.emit(o.toString());
    }

    private static boolean validJsName(String s) {
        return s != null && s.matches("[A-Za-z_$][A-Za-z0-9_$]*");
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String message(Exception e) {
        if (e == null) return "未知错误";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static String deriveSourceName(String url) {
        String s = url == null ? "" : url.trim();
        int cut = s.indexOf('?');
        if (cut >= 0) s = s.substring(0, cut);
        cut = s.lastIndexOf('/');
        if (cut >= 0 && cut + 1 < s.length()) s = s.substring(cut + 1);
        cut = s.lastIndexOf('.');
        if (cut > 0) s = s.substring(0, cut);
        if (s.isEmpty()) return "直播源";
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static String okJson() {
        return "{\"ok\":true}";
    }

    private static String errorJson(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", message == null ? "未知错误" : message);
        } catch (Exception ignored) {
        }
        return o.toString();
    }
}
