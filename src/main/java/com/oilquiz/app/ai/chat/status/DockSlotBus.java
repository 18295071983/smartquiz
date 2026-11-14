package com.oilquiz.app.ai.chat.status;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * <p>§20（2026-11-15）：新增 {@code rev} 乱序保护、{@code expireAt} TTL 清理、以及
 * "插件优先、本地兜底"的单一写者规则，判定逻辑全在纯 JVM 的 {@link SlotLedger} 里。</p>
 *
 * <p>线程：可从任意线程调用；监听回调在调用线程执行，UI 自己切主线程。
 * 1 秒一次的 TTL 清扫由 {@link #attachSweeper()} 在主线程挂上，{@link #detachSweeper()} 摘掉。</p>
 */
public final class DockSlotBus {

    private static final String TAG = "DockSlotBus";
    /** TTL 清扫间隔：与需求约定一致（1s 粒度足够，槽位 ttl 都是秒级）。 */
    private static final long SWEEP_INTERVAL_MS = 1000L;

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
            this(id, priority, title, detail, action, warn, kind, board, pinned, count, 0L, 0L, "");
        }

        /**
         * §20（2026-11-15）：插件帧的完整构造。
         *
         * @param rev      按 id 单调递增的版本号；0 = 旧版/本地（不参与乱序丢弃）
         * @param expireAt 绝对过期时间（epoch ms）；0 = 不过期（稳定态，靠显式 remove）
         * @param source   写者来源：plugin / local / 空（诊断用，不参与渲染）
         */
        public Slot(String id, int priority, String title, String detail, String action, boolean warn,
                    String kind, String board, boolean pinned, int count,
                    long rev, long expireAt, String source) {
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
            this.rev = Math.max(0L, rev);
            this.expireAt = Math.max(0L, expireAt);
            this.source = source == null ? "" : source;
        }

        public final String kind;
        public final String board;
        public final boolean pinned;
        public final int count;
        /** §20：版本号（同 id 单调递增；0 = 无版本帧） */
        public final long rev;
        /** §20：绝对过期时间（epoch ms；0 = 不过期） */
        public final long expireAt;
        /** §20：写者来源（plugin / local / 空），只用于诊断 */
        public final String source;

        /** 复制一份并换写者来源（本地兜底写入时标 source=local）。 */
        public Slot withSource(String newSource) {
            return new Slot(id, priority, title, detail, action, warn,
                    kind, board, pinned, count, rev, expireAt, newSource);
        }
    }

    public interface Listener {
        void onSlots(List<Slot> slots);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    /** §20：rev/TTL/兜底判定账本；读写都拿 {@link #LOCK}。 */
    private static final SlotLedger LEDGER = new SlotLedger();
    private static final Object LOCK = new Object();

    private static final Object SWEEP_LOCK = new Object();
    private static android.os.Handler sweepHandler;

    private static final Runnable SWEEP_TASK = new Runnable() {
        @Override
        public void run() {
            sweepExpired(System.currentTimeMillis());
            synchronized (SWEEP_LOCK) {
                if (sweepHandler != null) sweepHandler.postDelayed(this, SWEEP_INTERVAL_MS);
            }
        }
    };

    private DockSlotBus() {
    }

    /** 放/更新一条插件插槽（同 id 覆盖；rev 回退/重复丢弃）。 */
    public static void update(Slot slot) {
        if (slot == null || slot.id.isEmpty()) return;
        long now = System.currentTimeMillis();
        SlotLedger.Result result;
        synchronized (LOCK) {
            result = LEDGER.update(slot, now);
        }
        if (result == SlotLedger.Result.APPLIED) {
            notifyListeners();
        } else {
            logDropped(slot, result);
        }
    }

    /**
     * §20：本地兜底写入（App 从事件自己派生状态用）。
     *
     * <p>插件最近 3 秒内推过同一个 id 就不写 —— 同一事实永远只有插件那一行；
     * 旧插件不推的时候仍然能显示（能力不删）。</p>
     */
    public static void updateLocal(Slot slot) {
        if (slot == null || slot.id.isEmpty()) return;
        long now = System.currentTimeMillis();
        SlotLedger.Result result;
        synchronized (LOCK) {
            result = LEDGER.updateLocal(slot, now);
        }
        if (result == SlotLedger.Result.APPLIED) {
            notifyListeners();
        } else {
            logDropped(slot, result);
        }
    }

    /** 移除一条插槽（无版本：本地/旧插件）。 */
    public static void remove(String id) {
        remove(id, 0L);
    }

    /** §20：带 rev 的移除；比已存 rev 旧的移除不生效（防止乱序复活/错杀）。 */
    public static void remove(String id, long rev) {
        if (id == null || id.isEmpty()) return;
        boolean had;
        SlotLedger.Result result;
        synchronized (LOCK) {
            had = LEDGER.get(id) != null;
            result = LEDGER.remove(id, rev);
        }
        if (result == SlotLedger.Result.STALE_REMOVE) {
            logi("[dock] 丢弃回退 remove id=" + id + " rev=" + rev);
            return;
        }
        if (had) notifyListeners();
    }

    /**
     * §20 修订（2026-11-15）：插件世代（epoch）—— 插件重载后 rev 从 1 重来，必须重置水位。
     *
     * @return -1 = 旧世代帧（调用方应整帧丢弃）；0 = 无变化；&gt;0 = 清掉的旧插件槽条数
     */
    public static int notePluginEpoch(long epoch) {
        int cleared;
        long current;
        synchronized (LOCK) {
            cleared = LEDGER.applyEpoch(epoch);
            current = LEDGER.pluginEpoch();
        }
        if (cleared < 0) {
            logi("[dock] 丢弃旧世代帧 epoch=" + epoch + " 当前=" + current);
        } else if (cleared > 0) {
            logi("[dock] 插件世代切换 epoch=" + epoch + " 清掉 " + cleared + " 条旧插件槽");
            notifyListeners();
        }
        return cleared;
    }

    /**
     * 全清（断连 / 切会话 / 页面销毁）。
     *
     * <p>§20：保留 {@code pinned} 的槽位 —— 它们是"连着就该一直看到"的状态；
     * 非 pinned 的瞬时态断连后没人会再更新，清掉。</p>
     */
    public static void clear() {
        int removed;
        synchronized (LOCK) {
            removed = LEDGER.clearNonPinned();
        }
        if (removed > 0) {
            logi("[dock] clear 保留 pinned，清掉 " + removed + " 条");
            notifyListeners();
        }
    }

    /** TTL 清扫：移除 {@code expireAt>0 && now>expireAt} 的槽位，返回清掉的条数。 */
    public static int sweepExpired(long now) {
        List<String> expired;
        synchronized (LOCK) {
            expired = LEDGER.sweep(now);
        }
        if (expired.isEmpty()) return 0;
        for (String id : expired) {
            logi("[dock] TTL 过期移除 id=" + id);
        }
        notifyListeners();
        return expired.size();
    }

    /** 挂 1 秒一次的 TTL 清扫（主线程调用；重复调用幂等）。 */
    public static void attachSweeper() {
        synchronized (SWEEP_LOCK) {
            if (sweepHandler != null) return;
            try {
                sweepHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                sweepHandler.postDelayed(SWEEP_TASK, SWEEP_INTERVAL_MS);
                logi("[dock] TTL 清扫已启动 间隔=" + SWEEP_INTERVAL_MS + "ms");
            } catch (Throwable t) {
                // 纯 JVM 单测没有 Looper：清理由测试显式调用 sweepExpired(now)
                sweepHandler = null;
            }
        }
    }

    /** 摘掉清扫（页面销毁 / 退到后台；TTL 语义由下次 attach 时补一次）。 */
    public static void detachSweeper() {
        synchronized (SWEEP_LOCK) {
            if (sweepHandler == null) return;
            try {
                sweepHandler.removeCallbacks(SWEEP_TASK);
            } catch (Throwable ignored) {
                // 已经退出 Looper 也无所谓
            }
            sweepHandler = null;
            logi("[dock] TTL 清扫已停止");
        }
    }

    /** 当前插槽（按 priority 升序的副本）。 */
    public static List<Slot> list() {
        List<Slot> out;
        synchronized (LOCK) {
            out = LEDGER.snapshot();
        }
        out.sort(Comparator.comparingInt(s -> s.priority));
        return out;
    }

    /** 注册监听：立刻回放一次当前值（页面晚于状态出现打开也能看到）。 */
    public static void addListener(Listener l) {
        if (l == null) return;
        // 回放前先清一次过期槽位：页面晚上打开时不该看到已经死掉的"正在压缩"
        sweepExpired(System.currentTimeMillis());
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

    /** §20：丢弃/让位日志（真机 logcat 一条就能看出是版本乱序还是兜底让位）。 */
    private static void logDropped(Slot slot, SlotLedger.Result result) {
        if (result == SlotLedger.Result.IGNORED || result == SlotLedger.Result.APPLIED) return;
        String why;
        switch (result) {
            case STALE_REV:
                why = "回退 rev";
                break;
            case DUPLICATE_REV:
                why = "重复 rev";
                break;
            case EXPIRED:
                why = "到达即过期";
                break;
            case LOCAL_SUPPRESSED:
                why = "兜底让位（插件 3s 内写过）";
                break;
            default:
                why = result.name();
                break;
        }
        long storedRev;
        synchronized (LOCK) {
            storedRev = LEDGER.lastRev(slot.id);
        }
        logi("[dock] 丢弃 " + why + " id=" + slot.id + " rev=" + slot.rev
                + " 已存rev=" + storedRev + " source=" + slot.source);
    }

    private static void logi(String message) {
        try {
            android.util.Log.i(TAG, message);
        } catch (Throwable ignored) {
            // 单测环境没有真实 Log：忽略
        }
    }
}
