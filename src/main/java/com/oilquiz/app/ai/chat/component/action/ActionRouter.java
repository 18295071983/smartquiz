package com.oilquiz.app.ai.chat.component.action;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 组件动作路由器（2026-11-13，M4）——**动态**落点表。
 *
 * <p>不是编译期写死两条链：sink 由宿主/插件/页面在运行时注册、替换、摘除，
 * 顺序由 {@code priority}（越小越先）决定，可按 {@link ActionEvent#channel} 过滤。
 * 这样新增一种动作去处（动态卡跳转 / MCP / 某个页面自己的处理）不需要改
 * {@code ComponentActions}，也不需要发版。</p>
 *
 * <p>契约：单个 sink 抛异常只记日志、不影响后续（故障隔离）；无人认领返回 false。</p>
 */
public final class ActionRouter {

    private static final String TAG = "ActionRouter";

    /** 默认优先级：内置 DSH / 本机回调由 ComponentActions 指定 */
    public static final int PRIORITY_DSH = 10;
    public static final int PRIORITY_LOCAL = 900;

    private static final class Entry {
        final String id;
        final int priority;
        /** 空 = 所有通道；否则只处理这些 channel */
        final java.util.Set<String> channels;
        final ActionSink sink;
        final long seq;

        Entry(String id, int priority, java.util.Set<String> channels, ActionSink sink, long seq) {
            this.id = id;
            this.priority = priority;
            this.channels = channels;
            this.sink = sink;
            this.seq = seq;
        }

        boolean accepts(ActionEvent e) {
            if (channels.isEmpty()) return true;
            return channels.contains(e.channel);
        }
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    /** 运行时注册（同 id 覆盖 = 替换实现/改优先级） */
    public ActionRouter register(String id, int priority, ActionSink sink) {
        return register(id, priority, java.util.Collections.<String>emptySet(), sink);
    }

    /**
     * 运行时注册（限定通道）。
     *
     * @param channels 为空的集合表示"所有通道"；非空则只对匹配 channel 的动作生效
     */
    public ActionRouter register(String id, int priority, java.util.Set<String> channels, ActionSink sink) {
        if (sink == null) return this;
        String key = id == null || id.trim().isEmpty() ? sink.id() : id.trim();
        java.util.Set<String> chans = channels == null
                ? java.util.Collections.<String>emptySet() : new java.util.HashSet<>(channels);
        unregister(key);
        entries.add(new Entry(key, priority, chans, sink, seq.incrementAndGet()));
        return this;
    }

    /** 摘除（页面退出/插件卸载时调用；不存在也返回 true，幂等） */
    public boolean unregister(String id) {
        if (id == null) return false;
        boolean removed = false;
        for (Entry e : entries) {
            if (e.id.equals(id)) {
                removed |= entries.remove(e);
            }
        }
        return removed;
    }

    public boolean has(String id) {
        for (Entry e : entries) {
            if (e.id.equals(id)) return true;
        }
        return false;
    }

    /** 当前落点（按 priority 升序、同优先级按注册先后） */
    public List<ActionSink> sinks() {
        List<ActionSink> out = new ArrayList<>();
        for (Entry e : ordered()) out.add(e.sink);
        return Collections.unmodifiableList(out);
    }

    public List<String> sinkIds() {
        List<String> out = new ArrayList<>();
        for (Entry e : ordered()) out.add(e.id);
        return Collections.unmodifiableList(out);
    }

    /** 清空全部（测试/宿主重载用；生产默认 sink 由 ComponentActions 重新安装） */
    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    /**
     * 分发一次动作。
     *
     * @return true = 有 sink 消费了；false = 无人认领（已记日志）
     */
    public boolean dispatch(Context context, ActionEvent event) {
        if (event == null || event.componentId.isEmpty()) return false;
        for (Entry entry : ordered()) {
            if (!entry.accepts(event)) continue;
            boolean eligible;
            try {
                eligible = entry.sink.canHandle(event);
            } catch (Throwable t) {
                Log.w(TAG, "sink " + entry.id + " canHandle 抛异常（跳过）: " + t);
                continue;
            }
            if (!eligible) continue;
            try {
                if (entry.sink.send(context, event)) {
                    Log.i(TAG, "动作已投递 sink=" + entry.id + " " + event);
                    return true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "sink " + entry.id + " send 抛异常（继续下一个）: " + t);
            }
        }
        Log.w(TAG, "动作无人认领 " + event);
        return false;
    }

    private List<Entry> ordered() {
        List<Entry> list = new ArrayList<>(entries);
        Collections.sort(list, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int byPriority = Integer.compare(a.priority, b.priority);
                if (byPriority != 0) return byPriority;
                return Long.compare(a.seq, b.seq);
            }
        });
        return list;
    }
}
