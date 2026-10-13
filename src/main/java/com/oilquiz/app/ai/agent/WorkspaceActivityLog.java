package com.oilquiz.app.ai.agent;

import android.content.Context;

import com.oilquiz.app.ai.chat.component.ComponentData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 后台产出活动记录（2026-10-13）。
 *
 * <p><b>要解决的问题</b>：用户离开聊天页（按 Home、切到别的 App、或 Activity 被销毁）后，
 * 生成与工具调用<b>继续在后台跑</b>——{@code AIChatActivity} 的流式回调在
 * {@code uiDetached} 时直接 return（"生成在桥/服务层继续，回调不再更新界面，只做结果落盘"），
 * 而 {@code uiDetached} 只在 onDestroy 才置 true，所以"切到后台但 Activity 还活着"时
 * 界面在更新一个看不见的页面。结果就是：<b>模型在后台写了文件，用户完全无感</b>，
 * 也没有任何通知或回来后的提示。</p>
 *
 * <p><b>做法</b>：在工具执行的统一收口（{@code AIToolManager.executeTool} 的卡片桥接处）
 * 记一笔"哪个工具产出了哪个文件"。前台时只留痕不打扰；
 * 非前台时累积到 {@link #drainPending()} 并交给 {@link WorkspaceActivityNotifier} 发一条通知；
 * 回到前台由界面用一条系统消息回灌。</p>
 *
 * <p><b>只存内存</b>是有意的：后台生成发生在<b>同一进程</b>内（Activity 销毁≠进程死），
 * 落盘只会带来"重启后残留陈旧产出"的新问题，收益为零。</p>
 */
public final class WorkspaceActivityLog {

    /** 单次会话内最多留痕的产出条目数（超出丢最早的，通知只报最近一批） */
    private static final int MAX_ENTRIES = 50;

    private static volatile WorkspaceActivityLog instance;

    /** 一条产出记录 */
    public static final class Entry {
        public final String tool;
        public final String name;
        public final String path;
        /** image / video / audio / file */
        public final String kind;
        public final long time;

        Entry(String tool, String name, String path, String kind) {
            this.tool = tool;
            this.name = name;
            this.path = path;
            this.kind = kind;
            this.time = System.currentTimeMillis();
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> pending = new ArrayList<>();
    private volatile boolean foreground = true;

    private WorkspaceActivityLog() {
    }

    public static WorkspaceActivityLog getInstance() {
        if (instance == null) {
            synchronized (WorkspaceActivityLog.class) {
                if (instance == null) {
                    instance = new WorkspaceActivityLog();
                }
            }
        }
        return instance;
    }

    // ==================== 前后台状态 ====================

    /**
     * 由聊天页 onResume/onStop 驱动。
     *
     * <p>用"可见性"而不是 {@code uiDetached} 作判据：后者只在 onDestroy 置位，
     * 用户按 Home 切走时它仍是 false，会漏掉最常见的"后台跑"场景。</p>
     */
    public void setForeground(boolean fg) {
        foreground = fg;
    }

    public boolean isForeground() {
        return foreground;
    }

    // ==================== 记录 ====================

    /**
     * 记录一次工具产出。{@code toolName} 为 ui_component 这类"模型主动展示的卡片"时忽略——
     * 那是显示选择，不是产出物，记了会把用户自己点的卡片也算成"后台写了文件"。
     */
    public void record(Context context, String toolName, ComponentData card) {
        if (card == null || card.props == null) return;
        if ("ui_component".equals(toolName) || "ui_component_plugin".equals(toolName)) return;
        List<Entry> parsed = parse(toolName, card);
        if (parsed.isEmpty()) return;
        List<Entry> snapshot = null;
        synchronized (this) {
            entries.addAll(parsed);
            while (entries.size() > MAX_ENTRIES) entries.remove(0);
            if (!foreground) {
                pending.addAll(parsed);
                snapshot = new ArrayList<>(pending);
            }
        }
        // 用户不在看页面 → 更新同一条通知（固定 tag/id，不会每产出一个文件堆一条）
        if (snapshot != null) {
            WorkspaceActivityNotifier.showArtifacts(context, snapshot);
        }
    }

    /** 批量记录（自动卡片桥接一次可能产出多张卡） */
    public void recordAll(Context context, String toolName, List<ComponentData> cards) {
        if (cards == null) return;
        for (ComponentData cd : cards) record(context, toolName, cd);
    }

    /** 从卡片 props 里提取产出物；非文件类卡片（info_card/table_card 等）返回空 */
    private static List<Entry> parse(String toolName, ComponentData card) {
        List<Entry> out = new ArrayList<>();
        JSONObject p = card.props;
        String type = card.type == null ? "" : card.type;
        try {
            if ("file_card".equals(type)) {
                addEntry(out, toolName, p.optString("name", ""), p.optString("path", ""),
                        kindOf(p.optString("type", "")));
            } else if ("file_list".equals(type)) {
                JSONArray files = p.optJSONArray("files");
                if (files != null) {
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject f = files.optJSONObject(i);
                        if (f == null) continue;
                        addEntry(out, toolName, f.optString("name", ""),
                                f.optString("path", ""), "file");
                    }
                }
            } else if ("image_grid".equals(type)) {
                JSONArray imgs = p.optJSONArray("images");
                if (imgs != null) {
                    for (int i = 0; i < imgs.length(); i++) {
                        Object o = imgs.opt(i);
                        String url = o instanceof JSONObject
                                ? ((JSONObject) o).optString("url", "") : String.valueOf(o);
                        addEntry(out, toolName, nameOf(url), url, "image");
                    }
                }
            } else if ("video".equals(type) || "audio".equals(type)) {
                String url = firstOf(p, "url", "src", "media", "video_url", "audio_url", "uri");
                addEntry(out, toolName, nameOf(url), url, "video".equals(type) ? "video" : "audio");
            }
        } catch (Throwable ignored) {
            // 解析失败只是少一条留痕，绝不影响工具执行
        }
        return out;
    }

    private static void addEntry(List<Entry> list, String tool, String name, String path, String kind) {
        if (path == null || path.trim().isEmpty()) return;
        String n = (name == null || name.trim().isEmpty()) ? nameOf(path) : name.trim();
        if (n.isEmpty()) return;
        list.add(new Entry(tool, n, path.trim(), kind));
    }

    private static String firstOf(JSONObject p, String... keys) {
        for (String k : keys) {
            String v = p.optString(k, "");
            if (!v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static String nameOf(String path) {
        if (path == null) return "";
        String p = path.trim();
        int q = p.indexOf('?');
        if (q > 0) p = p.substring(0, q);
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private static String kindOf(String ext) {
        String e = ext == null ? "" : ext.toLowerCase().trim();
        if (e.matches("(png|jpe?g|webp|gif|bmp|heic)")) return "image";
        if (e.matches("(mp4|mkv|mov|webm|avi)")) return "video";
        if (e.matches("(mp3|m4a|wav|aac|ogg|flac)")) return "audio";
        return "file";
    }

    // ==================== 取走待提示项 ====================

    /** 取走"用户不在看页面期间"累积的产出（回到前台时回灌提示用） */
    public List<Entry> drainPending() {
        synchronized (this) {
            if (pending.isEmpty()) return new ArrayList<>();
            List<Entry> out = new ArrayList<>(pending);
            pending.clear();
            return out;
        }
    }

    public boolean hasPending() {
        synchronized (this) {
            return !pending.isEmpty();
        }
    }

    /** 会话清空时调用（与 ChatIdDispatcher.reset 同处） */
    public void clear() {
        synchronized (this) {
            entries.clear();
            pending.clear();
        }
    }
}
