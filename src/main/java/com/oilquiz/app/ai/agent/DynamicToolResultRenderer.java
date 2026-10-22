package com.oilquiz.app.ai.agent;

import com.google.gson.Gson;
import com.oilquiz.app.ai.chat.ToolMetaRegistry;
import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.tool.AIToolResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 任意工具结果的动态卡片兜底（2026-10-21）。
 *
 * <p>已有的 {@link ToolResultCardBinder} 是按工具名 switch 的静态映射；没命中的工具
 * 以前没有卡片，只能在正文里看文本。这里按 **结果形状** 兜底：
 * 有媒体路径→原生媒体卡；headers/rows→表格；items→列表；metrics→指标；
 * html→网页卡；url→链接卡；其余标量对象→info_card。</p>
 *
 * <p>只在静态 binder 没有产出卡片时调用；纯文本/无结构结果不硬造卡，避免刷屏。</p>
 */
public final class DynamicToolResultRenderer {

    private static final Set<String> IMAGE_EXT = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif"));
    private static final Set<String> VIDEO_EXT = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "webm", "mov", "avi", "flv", "m3u8", "ts", "3gp"));
    private static final Set<String> AUDIO_EXT = new HashSet<>(Arrays.asList(
            "mp3", "m4a", "aac", "wav", "ogg", "flac", "amr", "opus", "wma"));
    private static final int MAX_CARDS = 3;
    private static final int MAX_INFO_ROWS = 8;

    private DynamicToolResultRenderer() {
    }

    public static List<ComponentData> render(String toolName, AIToolResult result) {
        List<ComponentData> out = new ArrayList<>();
        if (result == null || !result.isSuccess()) return out;
        try {
            Object raw = result.getResult();
            JSONObject obj = asObject(raw);
            if (obj != null) {
                if (isErrorObject(obj)) return out;
                List<ComponentData> media = mediaCards(obj, toolName);
                if (!media.isEmpty()) return cap(media);

                ComponentData table = tableCard(obj);
                if (table != null) return cap(single(table));
                ComponentData metric = metricCard(obj);
                if (metric != null) return cap(single(metric));
                ComponentData html = htmlCard(obj);
                if (html != null) return cap(single(html));
                ComponentData list = listCard(obj, toolName);
                if (list != null) return cap(single(list));
                ComponentData link = linkCard(obj);
                if (link != null) return cap(single(link));
                ComponentData json = jsonCard(obj, toolName);
                if (json != null) return cap(single(json));
                ComponentData info = infoCard(obj, toolName);
                if (info != null) return cap(single(info));
            }
            JSONArray arr = asArray(raw);
            if (arr != null && arr.length() > 0) {
                ComponentData list = arrayListCard(arr, toolName);
                if (list != null) return cap(single(list));
            }
            // 字符串结果：能解析成 JSON 就递归；纯 URL/文件路径也可成卡；纯文本不造卡
            if (raw instanceof String) {
                String s = ((String) raw).trim();
                if (s.startsWith("{") || s.startsWith("[")) {
                    JSONObject o = tryObject(s);
                    if (o != null) return render(toolName,
                            AIToolResult.success(o));
                    JSONArray a = tryArray(s);
                    if (a != null) return render(toolName,
                            AIToolResult.success(a));
                }
                ComponentData one = mediaCardForPath(s, toolName);
                if (one != null) return cap(single(one));
                if (s.startsWith("http://") || s.startsWith("https://")) {
                    return cap(single(ComponentData.of("link_card", new JSONObject()
                            .put("url", s).put("title", toolName))));
                }
            }
        } catch (Throwable ignored) {
            // 动态兜底失败不能影响工具结果本身
        }
        return out;
    }

    private static List<ComponentData> cap(List<ComponentData> cards) {
        if (cards.size() <= MAX_CARDS) return cards;
        return new ArrayList<>(cards.subList(0, MAX_CARDS));
    }

    private static List<ComponentData> single(ComponentData card) {
        List<ComponentData> out = new ArrayList<>(1);
        if (card != null) out.add(card);
        return out;
    }

    // ==================== 媒体 ====================

    private static List<ComponentData> mediaCards(JSONObject o, String toolName) throws Exception {
        Set<String> images = new LinkedHashSet<>();
        Set<String> videos = new LinkedHashSet<>();
        Set<String> audios = new LinkedHashSet<>();
        Set<String> files = new LinkedHashSet<>();
        collectPaths(o.optJSONArray("images"), images, videos, audios, files);
        collectPaths(o.optJSONArray("videos"), images, videos, audios, files);
        collectPaths(o.optJSONArray("audios"), images, videos, audios, files);
        collectPaths(o.optJSONArray("files"), images, videos, audios, files);
        for (String key : new String[]{"file", "path", "file_path", "local_path", "url"}) {
            String p = o.optString(key, "");
            if (!p.isEmpty()) classify(p, images, videos, audios, files);
        }
        if (images.isEmpty() && videos.isEmpty() && audios.isEmpty() && files.isEmpty()) {
            return new ArrayList<>();
        }
        String title = firstNonEmpty(o.optString("title"), o.optString("name"),
                ToolMetaRegistry.displayName(toolName));
        List<ComponentData> out = new ArrayList<>();
        if (!images.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (String s : images) {
                arr.put(s);
                if (arr.length() >= 6) break;
            }
            out.add(ComponentData.of("image_grid", new JSONObject()
                    .put("title", title).put("columns", arr.length() > 1 ? 2 : 1)
                    .put("images", arr)));
        }
        for (String s : videos) {
            if (out.size() >= MAX_CARDS) break;
            out.add(ComponentData.of("video", new JSONObject()
                    .put("url", s).put("title", firstNonEmpty(o.optString("name"), title))));
        }
        for (String s : audios) {
            if (out.size() >= MAX_CARDS) break;
            out.add(ComponentData.of("audio", new JSONObject()
                    .put("url", s).put("title", firstNonEmpty(o.optString("name"), title))));
        }
        if (!files.isEmpty() && out.size() < MAX_CARDS) {
            if (files.size() == 1) {
                String p = files.iterator().next();
                out.add(ComponentData.of("file_card", new JSONObject()
                        .put("name", fileName(p)).put("path", p)
                        .put("type", ext(p)).put("title", title)));
            } else {
                JSONArray arr = new JSONArray();
                for (String p : files) {
                    arr.put(new JSONObject().put("name", fileName(p)).put("path", p)
                            .put("type", ext(p)));
                    if (arr.length() >= 8) break;
                }
                out.add(ComponentData.of("file_list", new JSONObject()
                        .put("title", title + " · " + files.size() + " 个文件")
                        .put("files", arr)));
            }
        }
        return out;
    }

    private static void collectPaths(JSONArray arr, Set<String> images, Set<String> videos,
                                     Set<String> audios, Set<String> files) {
        if (arr == null) return;
        for (int i = 0; i < arr.length() && i < 12; i++) {
            Object raw = arr.opt(i);
            String p = "";
            if (raw instanceof JSONObject) {
                JSONObject o = (JSONObject) raw;
                // 键名取自 ToolResultFields.ARTIFACT：产物引用可能是 file / file_path / path /
                // url / link 几种写法，硬写三个键时新工具换个名就归不进任何一类。
                // 但表里**不能有 output**：动态工具的 results 数组常带 output=正文，
                // 一旦被当路径取走，这里就会生成一张指向一段文本的 file_card
                //（2026-10-23 别名合并引入的卡片链接故障，规则见 ToolResultFields 类注释第四条）
                for (String key : com.oilquiz.app.ai.tool.ToolResultFields.ARTIFACT) {
                    String candidate = o.optString(key, "");
                    if (!candidate.isEmpty()) {
                        p = candidate;
                        break;
                    }
                }
            } else if (raw != null) {
                p = String.valueOf(raw);
            }
            if (!p.isEmpty()) classify(p, images, videos, audios, files);
        }
    }

    private static void classify(String p, Set<String> images, Set<String> videos,
                                 Set<String> audios, Set<String> files) {
        String ext = ext(p);
        if (IMAGE_EXT.contains(ext)) images.add(p);
        else if (VIDEO_EXT.contains(ext)) videos.add(p);
        else if (AUDIO_EXT.contains(ext)) audios.add(p);
        else files.add(p);
    }

    private static ComponentData mediaCardForPath(String p, String toolName) throws Exception {
        if (p == null || p.isEmpty() || p.contains(" ") || p.length() > 500) return null;
        String ext = ext(p);
        if (IMAGE_EXT.contains(ext)) {
            return ComponentData.of("image_grid", new JSONObject()
                    .put("columns", 1).put("images", new JSONArray().put(p)));
        }
        if (VIDEO_EXT.contains(ext)) {
            return ComponentData.of("video", new JSONObject()
                    .put("url", p).put("title", fileName(p)));
        }
        if (AUDIO_EXT.contains(ext)) {
            return ComponentData.of("audio", new JSONObject()
                    .put("url", p).put("title", fileName(p)));
        }
        return null;
    }

    // ==================== 结构卡 ====================

    private static ComponentData tableCard(JSONObject o) throws Exception {
        JSONArray headers = o.optJSONArray("headers");
        JSONArray rows = o.optJSONArray("rows");
        if (headers == null || rows == null || headers.length() == 0) return null;
        return ComponentData.of("table_card", new JSONObject()
                .put("title", firstNonEmpty(o.optString("title"), "数据表"))
                .put("headers", headers).put("rows", rows));
    }

    private static ComponentData metricCard(JSONObject o) throws Exception {
        JSONArray metrics = o.optJSONArray("metrics");
        if (metrics == null || metrics.length() == 0) return null;
        return ComponentData.of("metric_card", new JSONObject()
                .put("title", firstNonEmpty(o.optString("title"), "指标"))
                .put("metrics", metrics));
    }

    private static ComponentData htmlCard(JSONObject o) throws Exception {
        String html = firstNonEmpty(o.optString("html"), o.optString("content_html"));
        if (html.isEmpty()) return null;
        return ComponentData.of("html", new JSONObject()
                .put("title", firstNonEmpty(o.optString("title"), "网页"))
                .put("html", html));
    }

    private static ComponentData listCard(JSONObject o, String toolName) throws Exception {
        JSONArray items = o.optJSONArray("items");
        if (items == null || items.length() == 0) return null;
        JSONArray normalized = new JSONArray();
        for (int i = 0; i < items.length() && i < 30; i++) {
            Object raw = items.opt(i);
            if (raw instanceof JSONObject) {
                JSONObject item = (JSONObject) raw;
                JSONObject n = new JSONObject();
                n.put("title", firstNonEmpty(item.optString("title"),
                        item.optString("label"), item.optString("name")));
                n.put("description", firstNonEmpty(item.optString("description"),
                        item.optString("value"), item.optString("text")));
                normalized.put(n);
            } else if (raw != null) {
                normalized.put(new JSONObject().put("title", String.valueOf(raw)));
            }
        }
        if (normalized.length() == 0) return null;
        return ComponentData.of("list_card", new JSONObject()
                .put("title", firstNonEmpty(o.optString("title"),
                        ToolMetaRegistry.displayName(toolName)))
                .put("items", normalized));
    }

    private static ComponentData linkCard(JSONObject o) throws Exception {
        String url = o.optString("url", "");
        if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return null;
        }
        return ComponentData.of("link_card", new JSONObject()
                .put("url", url)
                .put("title", firstNonEmpty(o.optString("title"), url))
                .put("description", o.optString("description", "")));
    }

    private static ComponentData jsonCard(JSONObject o, String toolName) throws Exception {
        Object data = o.opt("data");
        if (data == null) return null;
        return ComponentData.of("json_viewer", new JSONObject()
                .put("title", firstNonEmpty(o.optString("title"),
                        ToolMetaRegistry.displayName(toolName)))
                .put("data", data));
    }

    private static ComponentData infoCard(JSONObject o, String toolName) throws Exception {
        JSONArray items = new JSONArray();
        Iterator<String> keys = o.keys();
        while (keys.hasNext() && items.length() < MAX_INFO_ROWS) {
            String k = keys.next();
            if (isMetaKey(k)) continue;
            Object v = o.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            if (v instanceof JSONObject || v instanceof JSONArray) continue;
            String text = String.valueOf(v).trim();
            if (text.isEmpty() || text.length() > 300) continue;
            items.put(new JSONObject().put("label", k).put("value", text));
        }
        if (items.length() == 0) return null;
        return ComponentData.of("info_card", new JSONObject()
                .put("title", firstNonEmpty(o.optString("title"),
                        ToolMetaRegistry.displayName(toolName)))
                .put("items", items));
    }

    private static ComponentData arrayListCard(JSONArray arr, String toolName) throws Exception {
        JSONArray items = new JSONArray();
        for (int i = 0; i < arr.length() && i < 30; i++) {
            Object raw = arr.opt(i);
            if (raw instanceof JSONObject) {
                JSONObject item = (JSONObject) raw;
                JSONObject n = new JSONObject();
                n.put("title", firstNonEmpty(item.optString("title"),
                        item.optString("name"), item.optString("label"), "条目 " + (i + 1)));
                n.put("description", firstNonEmpty(item.optString("description"),
                        item.optString("text"), item.optString("value")));
                items.put(n);
            } else if (raw != null) {
                items.put(new JSONObject().put("title", String.valueOf(raw)));
            }
        }
        if (items.length() == 0) return null;
        return ComponentData.of("list_card", new JSONObject()
                .put("title", ToolMetaRegistry.displayName(toolName))
                .put("items", items));
    }

    // ==================== JSON 工具 ====================

    private static JSONObject asObject(Object raw) {
        if (raw instanceof JSONObject) return (JSONObject) raw;
        if (raw instanceof Map) {
            try {
                return new JSONObject(new Gson().toJson(raw));
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (raw instanceof String) return tryObject((String) raw);
        return null;
    }

    private static JSONArray asArray(Object raw) {
        if (raw instanceof JSONArray) return (JSONArray) raw;
        if (raw instanceof List) {
            try {
                return new JSONArray(new Gson().toJson(raw));
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (raw instanceof String) return tryArray((String) raw);
        return null;
    }

    private static JSONObject tryObject(String s) {
        try {
            String t = s.trim();
            if (!t.startsWith("{")) return null;
            return new JSONObject(t);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JSONArray tryArray(String s) {
        try {
            String t = s.trim();
            if (!t.startsWith("[")) return null;
            return new JSONArray(t);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isErrorObject(JSONObject o) {
        String status = o.optString("status", "");
        return "error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)
                || o.optBoolean("error", false);
    }

    private static boolean isMetaKey(String key) {
        switch (key) {
            case "status":
            case "ok":
            case "success":
            case "message":
            case "title":
            case "name":
            case "description":
            case "html":
            case "content_html":
            case "url":
            case "path":
            case "file":
            case "file_path":
            case "local_path":
            case "images":
            case "videos":
            case "audios":
            case "files":
            case "headers":
            case "rows":
            case "items":
            case "metrics":
            case "data":
            case "display":
            case "collapsed":
                return true;
            default:
                return false;
        }
    }

    private static String ext(String p) {
        if (p == null) return "";
        String s = p;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        int dot = s.lastIndexOf('.');
        if (dot < 0 || dot < slash) return "";
        return s.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static String fileName(String p) {
        if (p == null) return "";
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }
}
