package com.oilquiz.app.ai.remote;

/**
 * 事件流守护策略（2026-11-15，文档 §14）：<b>断档判定</b>与<b>背压硬线</b>。
 *
 * <p>为什么单独抽出来：这两件事都是"阈值 + 计数"的判断，写进 Activity 里只能靠真机看日志，
 * 参数一改就没人敢动。抽成纯类后阈值可单测、可解释（见 {@code EventFlowPolicyTest}）。</p>
 *
 * <h3>断档（B）：主要看连续性，数量阈值只当辅助</h3>
 * <p>DSH/dsh-Go 的背压是"合并 / 丢弃 / 断"三级，重同步靠 revision（版本代数）判定必须重建；
 * 我们这条通道没有 revision，可用的权威信号只有两个：<b>seq 连续性</b>与<b>服务端 snapshot 帧</b>。
 * 于是：</p>
 * <ul>
 *   <li>seq 跳号（{@code incoming > last + 1}）→ 记一次不连续；<b>连续两次不连续</b>说明中间
 *       真的缺了一段（不是单条被 caps 过滤或乱序补发）→ 转快照；</li>
 *   <li>一次跳号超过 {@link #SNAPSHOT_GAP}（默认 200，可调）→ 直接转快照；</li>
 *   <li>服务端 {@code snapshot} 帧 / {@code __force_resync} → 无条件转快照（通道层已转成内部帧）；</li>
 *   <li>收到比水位更旧的 seq（乱序/补发）→ 不算断档（时间线按 seq 幂等，重复应用无害）。</li>
 * </ul>
 *
 * <h3>背压（C）：合并 → 丢弃 → 断，三级</h3>
 * <ul>
 *   <li><b>合并</b>：在 UI 侧做（{@code EventBatchQueue} 一帧最多取 50 条，帧末只投影一次）；</li>
 *   <li><b>丢弃</b>：队列满（默认 2000 条）丢最旧的，计数不退让；</li>
 *   <li><b>断</b>：累计丢弃 ≥ {@link #HARD_DROP_LIMIT}（默认 500）或单批丢弃 ≥
 *       {@link #HARD_DROP_BURST}（默认 200）→ 主动重连 + 请求快照，只按水位续传。</li>
 * </ul>
 * <p><b>为什么不做"切拉模式"</b>：DSH/dsh-Go 的背压没有"客户端慢就改成轮询"这一档 ——
 * 拉模式会把延迟与实现复杂度都翻倍，而正确的解法是"扔掉追不上的旧事件、必要时断线重建"。
 * 详见 {@code docs/dsh-remote-ui-design/34-component-pipeline-audit.md} §14C。</p>
 */
public final class EventFlowPolicy {

    /** 一次断档超过这么多条 → 直接转快照（辅助判据，默认 200，可调） */
    public static final int SNAPSHOT_GAP = 200;
    /** 累计丢弃超过这么多条 → 判定"已经追不上"，走断线 + 快照 */
    public static final int HARD_DROP_LIMIT = 500;
    /** 单批丢弃超过这么多条 → 立刻走断线 + 快照（不必等累计） */
    public static final int HARD_DROP_BURST = 200;

    /** 单条 seq 的判定结果。 */
    public enum Decision {
        /** 连续 / 首条 / 旧序号补发：照常归约 */
        OK,
        /** 比水位旧：重复/乱序，时间线幂等忽略 */
        DUPLICATE,
        /** 判定为真断档：转快照重建，不再逐条重放 */
        RESYNC
    }

    private final int snapshotGap;
    private final int hardDropLimit;
    private final int hardDropBurst;

    /** 已收到的最大正 seq（-1 = 还没有） */
    private int lastSeq = -1;
    /** 连续不连续次数（收到连续 seq 时清零） */
    private int consecutiveJumps;
    /** 见过的断档次数（诊断/验收用） */
    private int gapCount;
    /** 判定"必须重建"的次数（诊断/验收用） */
    private int resyncCount;

    public EventFlowPolicy() {
        this(SNAPSHOT_GAP, HARD_DROP_LIMIT, HARD_DROP_BURST);
    }

    /** 阈值可注入（单测/调参用；生产走默认常量）。 */
    public EventFlowPolicy(int snapshotGap, int hardDropLimit, int hardDropBurst) {
        this.snapshotGap = Math.max(1, snapshotGap);
        this.hardDropLimit = Math.max(1, hardDropLimit);
        this.hardDropBurst = Math.max(1, hardDropBurst);
    }

    public int snapshotGap() {
        return snapshotGap;
    }

    public int hardDropLimit() {
        return hardDropLimit;
    }

    public int hardDropBurst() {
        return hardDropBurst;
    }

    /** 已收到的最大正 seq（-1 = 还没收到过）。 */
    public int lastSeq() {
        return lastSeq;
    }

    public int gapCount() {
        return gapCount;
    }

    public int resyncCount() {
        return resyncCount;
    }

    /**
     * 登记一条新到事件的 seq，返回该不该转快照。
     *
     * <p>只在"入队"时调用（任意线程），不要拿它替代时间线自己的幂等去重。</p>
     */
    public Decision noteSeq(int seq) {
        if (seq <= 0) return Decision.OK;           // 合成帧（审批/提问/ui）不参与水位
        if (lastSeq < 0) {                          // 首条/重建后的第一条
            lastSeq = seq;
            consecutiveJumps = 0;
            return Decision.OK;
        }
        if (seq <= lastSeq) return Decision.DUPLICATE;
        if (seq == lastSeq + 1) {
            lastSeq = seq;
            consecutiveJumps = 0;
            return Decision.OK;
        }
        int gap = seq - lastSeq - 1;
        lastSeq = seq;
        gapCount += 1;
        if (gap > snapshotGap) {
            // 缺口大到不用再犹豫：补丁式重放只会把工具卡停在"运行中"
            resyncCount += 1;
            consecutiveJumps = 0;
            return Decision.RESYNC;
        }
        consecutiveJumps += 1;
        if (consecutiveJumps >= 2) {
            // 连续两次不连续 = 真的缺了一段；单次小跳更可能是被 caps 过滤或乱序补发
            resyncCount += 1;
            return Decision.RESYNC;
        }
        return Decision.OK;
    }

    /**
     * 快照重建后复位：水位对齐快照 seq，不连续计数清零（否则重建完第一条就会被再判一次）。
     */
    public void resetAfterSnapshot(int watermark) {
        lastSeq = Math.max(0, watermark);
        consecutiveJumps = 0;
    }

    /**
     * 服务端 {@code state} 帧那条路径的辅助判据：已落屏水位落后服务端太多
     * （{@code last_seq - applied_seq > snapshotGap}）→ 也该转快照。
     *
     * <p>主判据仍是 seq 连续性（{@link #noteSeq}）；这里只在"服务端明说落后多少"时兜底。</p>
     */
    public boolean appliedBehind(int serverSeq, int appliedSeq) {
        if (serverSeq <= 0 || appliedSeq <= 0) return false;
        return serverSeq - appliedSeq > snapshotGap;
    }

    /**
     * 背压"断"这一档的阈值判断。
     *
     * @param droppedSinceReset 自上次重建/重连以来累计丢弃条数
     * @param burstDropped      最近一批丢弃条数
     * @return true = 已经追不上，应主动重连 + 请求快照
     */
    public boolean hardLine(int droppedSinceReset, int burstDropped) {
        if (burstDropped >= hardDropBurst) return true;
        return droppedSinceReset >= hardDropLimit;
    }
}
