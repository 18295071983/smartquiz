package com.oilquiz.app.ai.chat.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dock 插槽的「版本 + TTL + 单一写者」账本（§20，2026-11-15）。
 *
 * <p>纯 JVM：不碰 Android，时间一律由调用方传入，单测可以直接构造时间轴。</p>
 *
 * <p>解决的问题（真机现象）：</p>
 * <ul>
 *   <li><b>乱序覆盖</b>：重连补发 + 实时推送可能重叠，一条晚到的旧帧会把新状态盖回去。
 *       帧带 {@code rev}（按 id 单调递增）后，{@code rev} 回退 / 同 rev 重复一律丢弃。</li>
 *   <li><b>残留</b>：生产者（job 扫描、压缩、重试）失联后不再有帧，槽位永远挂着。
 *       持续态帧带 {@code expireAt}，到点由 {@link #sweep(long)} 清掉。</li>
 *   <li><b>同一状态两行</b>：插件推一条、手机端自己又派生一条。插件最近
 *       {@link #LOCAL_FALLBACK_MS} 内写过同一 id 时，本地兜底写入让位。</li>
 * </ul>
 *
 * <p>版本语义（2026-11-15 约定）：{@code rev > 0} 的帧参与乱序校验；
 * {@code rev == 0} 表示旧插件/本地写入的"无版本帧"，按覆盖语义处理（不建立水位），
 * 保证升级前后行为不倒退。</p>
 *
 * <p>世代语义（2026-11-15 修订）：插件帧带 {@code epoch}（毫秒时间戳）。插件重载/重启后
 * rev 从 1 重来，{@link #applyEpoch(long)} 会清掉上一世代留下的插件槽并重置水位，
 * 避免新帧被旧水位当成回退帧永久丢弃；更旧的 epoch 帧整帧丢弃。</p>
 *
 * <p>容量语义（§22，2026-11-15）：{@link #enforceLimit(int)} 把总线压在
 * `DockSlotBus.MAX_SLOTS`（默认 16）条以内，超出时按「priority 最大、同档最旧」淘汰
 * 非 pinned 槽位；淘汰不是删除语义，rev 水位保留（旧帧不能把淘汰掉的槽复活）。</p>
 */
public final class SlotLedger {

    /** 插件写过同一 id 后，本地兜底写入的静默窗口（毫秒）。 */
    public static final long LOCAL_FALLBACK_MS = 3000L;

    /** 一次写入的判定结果（调用方据此打日志 / 决定是否通知 UI）。 */
    public enum Result {
        /** 已写入（新槽位或合法覆盖）。 */
        APPLIED,
        /** 空 id / 空对象，忽略。 */
        IGNORED,
        /** rev 比已存的旧：迟到帧，丢弃。 */
        STALE_REV,
        /** rev 与已存的相同且槽位还在：重复帧，丢弃。 */
        DUPLICATE_REV,
        /** 到达时就已经过期（ttl 太短或补发太晚）。 */
        EXPIRED,
        /** 插件最近 3 秒内写过同一 id，本地兜底写入让位。 */
        LOCAL_SUPPRESSED,
        /** 移除请求的 rev 比已存的旧（乱序移除不生效）。 */
        STALE_REMOVE
    }

    /** id -> 当前槽位（顺序即插入顺序，最终展示顺序由 priority 决定）。 */
    private final LinkedHashMap<String, DockSlotBus.Slot> slots = new LinkedHashMap<>();
    /** id -> 已见过的最大 rev（移除后仍保留，阻止旧帧把槽位复活）。 */
    private final HashMap<String, Long> lastRev = new HashMap<>();
    /** id -> 插件最近一次写这个 id 的时刻（兜底窗口用）。 */
    private final HashMap<String, Long> pluginAt = new HashMap<>();
    /** §22：id -> 最近一次写入（插件或本地）的时刻；容量淘汰时的"最旧"判据。 */
    private final HashMap<String, Long> updatedAt = new HashMap<>();
    /** §22：累计淘汰条数（自上次 {@link #resetCounters()} 起；诊断/测试用）。 */
    private int evictedTotal = 0;
    /** 插件实例世代（毫秒时间戳；0 = 旧插件帧不带 epoch）；只增不减。 */
    private long pluginEpoch = 0L;

    /** 插件帧写入：rev 校验 + 记录插件活跃时间。 */
    public Result update(DockSlotBus.Slot slot, long now) {
        if (slot == null || slot.id.isEmpty()) return Result.IGNORED;
        if (slot.expireAt > 0 && now > slot.expireAt) return Result.EXPIRED;
        Long prev = lastRev.get(slot.id);
        if (slot.rev > 0 && prev != null) {
            if (slot.rev < prev) return Result.STALE_REV;
            /**
             * 同 rev 一律视为重复帧（§22 修订，2026-11-15）。
             *
             * <p>旧规则是"槽位已被移除/清空时允许同一帧补发回来"，理由写在 §20.3（重连补发）。
             * 但 {@code clear()} 现在会清 rev 水位（§20 修订），真正需要"按原 rev 补发恢复"的
             * 场景已经走 {@code prev == null} 这条路；剩下的"水位还在但槽位不在"只有两种来源：
             * 显式 {@code remove}（同 rev 复活是错的）与容量淘汰（更错：被淘汰的槽会被补发帧/
             * 快照帧重新塞回总线，再被淘汰，一秒内空转几十次 —— 真机日志
             * `上限 16，淘汰 id=cap-0x` 连打 29 行就是这么来的）。</p>
             */
            if (slot.rev == prev) return Result.DUPLICATE_REV;
        }
        if (slot.rev > 0) {
            lastRev.put(slot.id, prev == null ? slot.rev : Math.max(prev, slot.rev));
        }
        slots.put(slot.id, slot);
        pluginAt.put(slot.id, now);
        updatedAt.put(slot.id, now);
        return Result.APPLIED;
    }

    /**
     * 本地兜底写入（App 自己从事件派生状态）：插件最近 3 秒内写过同一 id 就不写。
     * 来源统一标成 {@code source=local}。
     */
    public Result updateLocal(DockSlotBus.Slot slot, long now) {
        if (slot == null || slot.id.isEmpty()) return Result.IGNORED;
        if (slot.expireAt > 0 && now > slot.expireAt) return Result.EXPIRED;
        Long at = pluginAt.get(slot.id);
        if (at != null && now - at < LOCAL_FALLBACK_MS) return Result.LOCAL_SUPPRESSED;
        slots.put(slot.id, slot.source.equals("local") ? slot : slot.withSource("local"));
        updatedAt.put(slot.id, now);
        return Result.APPLIED;
    }

    /** 移除一条（rev>0 时做乱序保护）。返回 true 表示确实删掉了一条。 */
    public Result remove(String id, long rev) {
        if (id == null || id.isEmpty()) return Result.IGNORED;
        Long prev = lastRev.get(id);
        if (rev > 0 && prev != null) {
            if (rev < prev) return Result.STALE_REMOVE;
            // 同 rev 的移除只可能是重复帧；槽位已经不在就当成功（幂等）
            if (rev == prev && !slots.containsKey(id)) return Result.APPLIED;
        }
        if (rev > 0) {
            lastRev.put(id, prev == null ? rev : Math.max(prev, rev));
        }
        slots.remove(id);
        updatedAt.remove(id);
        return Result.APPLIED;
    }

    /**
     * §22：容量上限（`DockSlotBus.MAX_SLOTS`）。
     *
     * <p>超出时按「priority 最大（数字大的先走）、同档最旧」淘汰**非 pinned** 的槽位，
     * 直到回到上限；`pinned` 是"连着就该一直看到"的状态，永远不参与淘汰。全是 pinned
     * 且已经超限时直接放弃（宁可多几条，也不把固定状态挤掉）。</p>
     *
     * <p>"最旧"用最后一次写入时间（{@link #updatedAt}）衡量：同 id 覆盖更新会刷新时间，
     * 所以长期不动的槽先被淘汰，正被持续更新的槽留在甲板上。</p>
     *
     * @return 被淘汰的 id（按淘汰顺序；空表示没超限/无可淘汰）
     */
    public List<String> enforceLimit(int maxSlots) {
        List<String> evicted = new ArrayList<>();
        if (maxSlots <= 0) return evicted;
        while (slots.size() > maxSlots) {
            String worstId = null;
            int worstPriority = Integer.MIN_VALUE;
            long worstAt = Long.MAX_VALUE;
            for (Map.Entry<String, DockSlotBus.Slot> e : slots.entrySet()) {
                DockSlotBus.Slot s = e.getValue();
                if (s == null || s.pinned) continue;
                Long at = updatedAt.get(e.getKey());
                long stamp = at == null ? 0L : at.longValue();
                if (s.priority > worstPriority
                        || (s.priority == worstPriority && stamp < worstAt)) {
                    worstId = e.getKey();
                    worstPriority = s.priority;
                    worstAt = stamp;
                }
            }
            if (worstId == null) break;   // 全是 pinned：不淘汰
            slots.remove(worstId);
            updatedAt.remove(worstId);
            // lastRev/pluginAt 刻意保留：与 remove 同口径，旧帧不能把淘汰掉的槽复活
            evicted.add(worstId);
            evictedTotal += 1;
        }
        return evicted;
    }

    /** §22：累计淘汰条数（诊断/测试用；{@link #resetCounters()} 归零）。 */
    public int evictedTotal() {
        return evictedTotal;
    }

    /** §22：清零淘汰计数（断连/清空时调用，让日志的"本轮淘汰"有意义）。 */
    public void resetCounters() {
        evictedTotal = 0;
    }

    /**
     * 按写者来源清理（切会话用，2026-11-16）。
     *
     * <p>只删 {@code source} 匹配且非 {@code pinned} 的槽位；版本水位（{@code lastRev}）
     * **保留** —— 切会话不是插件重启，旧会话的迟到帧仍应被水位挡住，不能让它们复活。</p>
     *
     * @param source 写者来源（plugin / local / 空）
     * @return 实际清掉的条数
     */
    public int clearBySource(String source) {
        String want = source == null ? "" : source;
        int removed = 0;
        Iterator<Map.Entry<String, DockSlotBus.Slot>> it = slots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, DockSlotBus.Slot> e = it.next();
            DockSlotBus.Slot s = e.getValue();
            if (s == null || s.pinned) continue;
            String src = s.source == null ? "" : s.source;
            if (src.equals(want)) {
                it.remove();
                removed += 1;
            }
        }
        updatedAt.keySet().retainAll(slots.keySet());
        return removed;
    }

    /** 断连/切会话：清非 pinned，pinned 留着（连上后仍然有意义的状态）。 */
    public int clearNonPinned() {        int removed = 0;
        Iterator<Map.Entry<String, DockSlotBus.Slot>> it = slots.entrySet().iterator();
        while (it.hasNext()) {
            if (!it.next().getValue().pinned) {
                it.remove();
                removed += 1;
            }
        }
        updatedAt.keySet().retainAll(slots.keySet());
        /**
         * 版本水位也归零（2026-11-15）：断连的另一层含义是"插件可能重启过" —— 插件进程
         * 一重启，{@code SLOT_REV} 就从 1 重来；水位若留着，新世代的 rev=1 会被当成回退帧
         * 永久丢弃（真机表现：重连后 jobs/compaction 再也不出现）。
         *
         * <p>旧世代的迟到帧本来随连接一起断掉（队列也在断连时清空），所以这里归零不会
         * 把乱序保护真正打穿；显式 {@link #remove(String, long)} 仍保留水位，防旧帧复活。</p>
         */
        lastRev.clear();
        /**
         * §22：插件世代水位一起归零。断连后旧世代帧已经不可能再到（连接队列同步清空），
         * 留着水位只会让"重连后拉快照"的第一帧被当成旧世代整帧丢弃 —— 快照恢复不了就白做了。
         * 归零后同一 epoch 的重连帧按"新世代"处理：清一次（本来就空）+ 重置水位，无副作用。
         */
        pluginEpoch = 0L;
        return removed;
    }

    /** TTL 清理：返回过期被移除的 id（供日志）。无 ttl（expireAt==0）永不清理。 */
    public List<String> sweep(long now) {
        List<String> expired = new ArrayList<>();
        Iterator<Map.Entry<String, DockSlotBus.Slot>> it = slots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, DockSlotBus.Slot> e = it.next();
            long expireAt = e.getValue().expireAt;
            if (expireAt > 0 && now > expireAt) {
                expired.add(e.getKey());
                it.remove();
            }
        }
        for (String id : expired) updatedAt.remove(id);
        // 兜底窗口过期的记录顺手清掉，避免长时间运行无界增长
        Iterator<Map.Entry<String, Long>> pa = pluginAt.entrySet().iterator();
        while (pa.hasNext()) {
            if (now - pa.next().getValue() >= LOCAL_FALLBACK_MS * 2) pa.remove();
        }
        return expired;
    }

    /**
     * 插件世代切换（§20 修订，2026-11-15）。
     *
     * <p>插件进程重启/热重载后 {@code SLOT_REV} 从 1 重来，而 App 还记着上一世代的 rev 水位，
     * 新帧会被当成回退帧**永久丢弃**（真机表现：插件重载后任务/权限槽不再更新，phone_ui
     * 的移除也无效）。帧里带 {@code epoch}（毫秒时间戳）后：</p>
     * <ul>
     *   <li>epoch 变大 → 清掉上一世代留下的插件槽 + 重置全部 rev 水位（本地派生的槽保留）；</li>
     *   <li>epoch 相同 → 无事发生；</li>
     *   <li>epoch 变小 → 迟到的旧世代帧，调用方应整帧丢弃。</li>
     * </ul>
     *
     * @return -1 = 旧世代帧（丢弃）；0 = 无变化；&gt;0 = 清掉的旧插件槽条数
     */
    public int applyEpoch(long epoch) {
        if (epoch <= 0L) return 0;          // 旧插件/本地帧不带 epoch：不参与世代判断
        if (epoch < pluginEpoch) return -1; // 迟到的旧世代帧
        if (epoch == pluginEpoch) return 0;
        pluginEpoch = epoch;
        lastRev.clear();
        int cleared = 0;
        Iterator<Map.Entry<String, DockSlotBus.Slot>> it = slots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, DockSlotBus.Slot> e = it.next();
            if ("plugin".equals(e.getValue().source)) {
                updatedAt.remove(e.getKey());
                it.remove();
                cleared += 1;
            }
        }
        return cleared;
    }

    /** 当前插件世代（0 = 还没见过带 epoch 的帧；诊断/测试用）。 */
    public long pluginEpoch() {
        return pluginEpoch;
    }

    /** 按 priority 排序后的快照（与旧 {@code DockSlotBus.list()} 口径一致）。 */
    public List<DockSlotBus.Slot> snapshot() {
        return new ArrayList<>(slots.values());
    }

    /** 当前条数（测试/诊断用）。 */
    public int size() {
        return slots.size();
    }

    /** 某 id 的当前槽位（可以没有）。 */
    public DockSlotBus.Slot get(String id) {
        return slots.get(id);
    }

    /** 某 id 已见过的最大 rev（诊断/测试用）。 */
    public long lastRev(String id) {
        Long v = lastRev.get(id);
        return v == null ? 0L : v;
    }

    /** 插件最近一次写这个 id 的时刻（0 = 从没写过；测试/诊断用）。 */
    public long lastPluginAt(String id) {
        Long v = pluginAt.get(id);
        return v == null ? 0L : v;
    }
}
