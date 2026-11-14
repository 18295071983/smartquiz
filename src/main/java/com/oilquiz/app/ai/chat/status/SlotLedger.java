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

    /** 插件帧写入：rev 校验 + 记录插件活跃时间。 */
    public Result update(DockSlotBus.Slot slot, long now) {
        if (slot == null || slot.id.isEmpty()) return Result.IGNORED;
        if (slot.expireAt > 0 && now > slot.expireAt) return Result.EXPIRED;
        Long prev = lastRev.get(slot.id);
        if (slot.rev > 0 && prev != null) {
            if (slot.rev < prev) return Result.STALE_REV;
            // 同 rev 视为重复；但槽位已被移除/清空时允许同一帧补发回来（重连补发口径）
            if (slot.rev == prev && slots.containsKey(slot.id)) return Result.DUPLICATE_REV;
        }
        if (slot.rev > 0) {
            lastRev.put(slot.id, prev == null ? slot.rev : Math.max(prev, slot.rev));
        }
        slots.put(slot.id, slot);
        pluginAt.put(slot.id, now);
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
        return Result.APPLIED;
    }

    /** 断连/切会话：清非 pinned，pinned 留着（连上后仍然有意义的状态）。 */
    public int clearNonPinned() {
        int removed = 0;
        Iterator<Map.Entry<String, DockSlotBus.Slot>> it = slots.entrySet().iterator();
        while (it.hasNext()) {
            if (!it.next().getValue().pinned) {
                it.remove();
                removed += 1;
            }
        }
        /**
         * 版本水位也归零（2026-11-15）：断连的另一层含义是"插件可能重启过" —— 插件进程
         * 一重启，{@code SLOT_REV} 就从 1 重来；水位若留着，新世代的 rev=1 会被当成回退帧
         * 永久丢弃（真机表现：重连后 jobs/compaction 再也不出现）。
         *
         * <p>旧世代的迟到帧本来随连接一起断掉（队列也在断连时清空），所以这里归零不会
         * 把乱序保护真正打穿；显式 {@link #remove(String, long)} 仍保留水位，防旧帧复活。</p>
         */
        lastRev.clear();
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
        // 兜底窗口过期的记录顺手清掉，避免长时间运行无界增长
        Iterator<Map.Entry<String, Long>> pa = pluginAt.entrySet().iterator();
        while (pa.hasNext()) {
            if (now - pa.next().getValue() >= LOCAL_FALLBACK_MS * 2) pa.remove();
        }
        return expired;
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
