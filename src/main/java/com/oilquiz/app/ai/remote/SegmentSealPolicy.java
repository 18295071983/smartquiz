package com.oilquiz.app.ai.remote;

/**
 * 尾段增量投影的<b>封口判据</b>（2026-11-15，§19 尾段增量投影）。
 *
 * <p>背景：一条 DSH 回合在手机上按 6 步一条消息派发（{@code T12-G2} 这种段 id）。
 * 流式期间如果每批事件都 {@code project()} 整条消息，那么已经写完的段会被反复重建 ——
 * 真机日志里 1~10 条事件的小批也要重投影全部工具卡/思考。dsh-Go 的做法是
 * <b>只重排尾段</b>：行主键带起点，已完成的行缓存不重建。</p>
 *
 * <p>本类只做纯计算（无 Android / 无 org.json），封口规则一句话：</p>
 * <ul>
 *   <li><b>不是最后一段</b>（{@code segmentIndex < lastSegmentIndex}）→ 已封口；</li>
 *   <li><b>最后一段</b>：回合仍在写（{@code hasActiveWork=true}）→ 活动段；回合已结束
 *       （{@code hasActiveWork=false}）→ 封口。</li>
 * </ul>
 *
 * <p>"同段但该段已无活动工具/流式标志"这一条<b>没有</b>被用作流式期间的封口依据：
 * 一步结束到下一步开始之间工具确实都停了，但正文/思考随时可能再追加，按"无活动工具"
 * 封口会把最后一段提前锁死，尾部文本就再也进不去。所以流式期间最后一段一律按活动段处理，
 * 只有"不再是最后一段"或"回合结束"才封口。纯尾部文本追加因此永远落在活动段，
 * 不需要让任何封口段失效。</p>
 *
 * @see SegmentProjectionCache 基于本判据的缓存与失效记账
 */
public final class SegmentSealPolicy {

    /** 一条消息最多承载多少步（与 {@code splitLiveIntoSegments}／历史导入同粒度） */
    public static final int STEPS_PER_SEGMENT = 6;

    private SegmentSealPolicy() {
    }

    /** 总步数 → 段数（0 步 = 0 段；6 步 = 1 段；7 步 = 2 段） */
    public static int segmentCount(int stepCount) {
        if (stepCount <= 0) return 0;
        return (stepCount + STEPS_PER_SEGMENT - 1) / STEPS_PER_SEGMENT;
    }

    /** 全局步序号（0 基）→ 段号（0 基） */
    public static int segmentIndex(int stepIndex) {
        if (stepIndex <= 0) return 0;
        return stepIndex / STEPS_PER_SEGMENT;
    }

    /** 段号的起始步（含） */
    public static int fromStep(int segmentIndex) {
        return Math.max(0, segmentIndex) * STEPS_PER_SEGMENT;
    }

    /** 段号的结束步（不含）；最后一段一般会被总步数截断 */
    public static int toStep(int segmentIndex) {
        return fromStep(segmentIndex) + STEPS_PER_SEGMENT;
    }

    /** 最后一段的段号（0 步时返回 -1） */
    public static int lastSegmentIndex(int stepCount) {
        return segmentCount(stepCount) - 1;
    }

    /**
     * 封口判定。
     *
     * @param segmentIndex     要判定的段号
     * @param lastSegmentIndex 当前最后一段的段号
     * @param hasActiveWork    该回合是否仍在写入（流式中=true；turn/end 之后=false）
     */
    public static boolean isSealed(int segmentIndex, int lastSegmentIndex, boolean hasActiveWork) {
        if (segmentIndex < 0) return true;
        if (segmentIndex < lastSegmentIndex) return true;
        return !hasActiveWork;
    }
}
