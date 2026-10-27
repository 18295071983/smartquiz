package com.oilquiz.app.ai.chat.status;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本轮任务的<b>进度插槽</b>：独立于消息流的一条细进度条（标题 / 百分比 / 说明）。
 *
 * <p><b>为什么单独做一条而不是聊天流卡片</b>：聊天流卡片是"消息快照"——一旦随消息落定，
 * 既不能原地更新（{@code ui_component update} 只能对弹窗/持久化组件生效），也会在消息列表刷新、
 * 重建、滚动回来时按快照重绘，长任务的进度因此在卡片上没法连续推进（真机上模型只能反复 create
 * 新卡，越叠越多）。插槽挂在输入区上方，不参与消息列表渲染，天然不会被刷新冲掉。</p>
 *
 * <p>生命周期：工具侧 {@link #update} 推一次就画一次；一轮生成结束（{@code endGeneration}）
 * 由聊天页 {@link #clear()}。结构照抄 {@link AgentActivityBus} 的静态总线模式——
 * 工具执行入口分散在多条路径，但都落到同一个 {@code AIToolManager}，UI 只需注册一个监听。</p>
 */
public final class AgentProgressBus {

    /** 一次进度快照；不可变，UI 只读 */
    public static final class Snapshot {
        public final String title;
        public final String message;
        public final int progress;
        public final int max;

        public Snapshot(String title, String message, int progress, int max) {
            this.title = title;
            this.message = message;
            this.progress = Math.max(0, progress);
            this.max = max > 0 ? max : 100;
        }

        public int percent() {
            return (int) Math.max(0, Math.min(100, 100.0 * progress / max));
        }
    }

    /** 监听器；{@code snapshot == null} 表示清空 */
    public interface Listener {
        void onProgress(Snapshot snapshot);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Snapshot current;

    private AgentProgressBus() {
    }

    /**
     * 推进一步进度。参数为 null/空时沿用上一次的值（只推进度时不必重复传标题）。
     */
    public static void update(String title, String message, int progress, int max) {
        Snapshot prev = current;
        String t = (title == null || title.trim().isEmpty())
                ? (prev == null ? "" : prev.title) : title.trim();
        String m = (message == null || message.trim().isEmpty())
                ? (prev == null ? "" : prev.message) : message.trim();
        int mx = max > 0 ? max : (prev == null ? 100 : prev.max);
        Snapshot s = new Snapshot(t, m, progress, mx);
        current = s;
        for (Listener l : LISTENERS) {
            try {
                l.onProgress(s);
            } catch (Throwable ignored) {
                // 单个监听失败不影响其他监听
            }
        }
    }

    /** 清空（一轮生成结束时调用） */
    public static void clear() {
        current = null;
        for (Listener l : LISTENERS) {
            try {
                l.onProgress(null);
            } catch (Throwable ignored) {
            }
        }
    }

    public static Snapshot current() {
        return current;
    }

    /** 注册监听：立刻回放一次当前值，页面晚于进度出现打开也能看到 */
    public static void addListener(Listener l) {
        if (l == null) return;
        LISTENERS.addIfAbsent(l);
        Snapshot s = current;
        try {
            l.onProgress(s);
        } catch (Throwable ignored) {
        }
    }

    public static void removeListener(Listener l) {
        if (l != null) LISTENERS.remove(l);
    }
}
