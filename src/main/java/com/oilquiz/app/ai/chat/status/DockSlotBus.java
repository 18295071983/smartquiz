package com.oilquiz.app.ai.chat.status;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 输入区上方的通用「dock 插槽」总线（2026-11-13）。
 *
 * <p><b>与消息流的分工</b>：进时间线的是"发生过什么"（消息 + 卡片，落定即快照、不可原地更新）；
 * 插槽放"现在是什么、我能做什么"（后台任务、目标、压缩中…），随状态实时改写、消失即恢复原状。
 * 这两件事混在一起，就是之前"进度只能用卡片反复建、越叠越多"的根因。</p>
 *
 * <p>形态约束（照 {@link AgentProgressBus} 的静态总线，已验证好用）：</p>
 * <ul>
 *   <li>按 {@code priority} 升序（小的在上），UI 侧最多渲染 3 条；</li>
 *   <li>同一 {@code id} 覆盖更新，不追加；</li>
 *   <li>每条只有标题 + 一行细节，可选一个点击动作（{@code action} 交给 UI 解释）；</li>
 *   <li>{@code warn=true} 表示"需要人处理"（黄），不是错误态。</li>
 * </ul>
 *
 * <p>线程：可从任意线程调用；监听回调在调用线程执行，UI 自己切主线程。</p>
 */
public final class DockSlotBus {

    /** 一条插槽；不可变。 */
    public static final class Slot {
        public final String id;
        /** 排序键：小的在前。约定 —— 10 瞬时过程 / 30 目标 / 40 任务 / 70 提示 */
        public final int priority;
        public final String title;
        public final String detail;
        /** 点击动作标识，由 UI 解释（如 "jobs" / "connect"）；空=不可点 */
        public final String action;
        /** true = 需要人注意（黄色） */
        public final boolean warn;

        public Slot(String id, int priority, String title, String detail, String action, boolean warn) {
            this(id, priority, title, detail, action, warn, "display", "state", false, 0);
        }

        /**
         * U1 (2026-11-14): full constructor with deck classification.
         *
         * @param kind   toggle | action | display | list
         * @param board  alert | task | asset | state  (one board = one chip row)
         * @param pinned true = always shown while connected (empty value renders as a dash)
         * @param count  item count for list-kind slots (0 otherwise)
         */
        public Slot(String id, int priority, String title, String detail, String action, boolean warn,
                    String kind, String board, boolean pinned, int count) {
            this.id = id == null ? "" : id;
            this.priority = priority;
            this.title = title == null ? "" : title;
            this.detail = detail == null ? "" : detail;
            this.action = action == null ? "" : action;
            this.warn = warn;
            this.kind = (kind == null || kind.isEmpty()) ? "display" : kind;
            this.board = (board == null || board.isEmpty()) ? "state" : board;
            this.pinned = pinned;
            this.count = Math.max(0, count);
        }

        public final String kind;
        public final String board;
        public final boolean pinned;
        public final int count;
    }

    public interface Listener {
        void onSlots(List<Slot> slots);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Map<String, Slot> SLOTS = new LinkedHashMap<>();

    private DockSlotBus() {
    }

    /** 放/更新一条插槽（同 id 覆盖）。 */
    public static void update(Slot slot) {
        if (slot == null || slot.id.isEmpty()) return;
        synchronized (SLOTS) {
            SLOTS.put(slot.id, slot);
        }
        notifyListeners();
    }

    public static void remove(String id) {
        if (id == null || id.isEmpty()) return;
        boolean removed;
        synchronized (SLOTS) {
            removed = SLOTS.remove(id) != null;
        }
        if (removed) notifyListeners();
    }

    /** 全清（断连 / 切会话 / 页面销毁）。 */
    public static void clear() {
        synchronized (SLOTS) {
            if (SLOTS.isEmpty()) return;
            SLOTS.clear();
        }
        notifyListeners();
    }

    /** 当前插槽（按 priority 升序的副本）。 */
    public static List<Slot> list() {
        List<Slot> out;
        synchronized (SLOTS) {
            out = new ArrayList<>(SLOTS.values());
        }
        out.sort(Comparator.comparingInt(s -> s.priority));
        return out;
    }

    /** 注册监听：立刻回放一次当前值（页面晚于状态出现打开也能看到）。 */
    public static void addListener(Listener l) {
        if (l == null) return;
        LISTENERS.addIfAbsent(l);
        try {
            l.onSlots(list());
        } catch (Throwable ignored) {
            // 回放失败不影响后续
        }
    }

    public static void removeListener(Listener l) {
        if (l != null) LISTENERS.remove(l);
    }

    private static void notifyListeners() {
        List<Slot> snapshot = list();
        for (Listener l : LISTENERS) {
            try {
                l.onSlots(snapshot);
            } catch (Throwable ignored) {
                // 单个监听失败不影响其他
            }
        }
    }
}
