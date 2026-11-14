package com.oilquiz.app.ai.remote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 尾段增量投影的分段缓存与失效记账（2026-11-15，§19 尾段增量投影）。
 *
 * <p>与 {@link SegmentSealPolicy} 分工：后者只回答"这段封口了没有"，本类回答
 * <b>"这一帧要重建哪几段、为什么"</b>，并给出验收守卫计数：</p>
 * <ul>
 *   <li>{@code 封口重建}：段在<b>上一次投影决策时已经封口</b>，本帧又调了一次
 *       {@code project()}。优化目标 = 0（正常回合里封口段的内容不会再变）；</li>
 *   <li>{@code 活动重建}：活动段／新建段／封口瞬间的"收口"投影 —— 这些是允许且必要的；</li>
 *   <li>{@code 收口}：段从"活动"变成"封口"的那一次最终投影（单列，不算封口重建）。</li>
 * </ul>
 *
 * <p>失效条件（写死在 {@link #plan} 的指纹比较里，逐条有单测）：</p>
 * <ol>
 *   <li><b>新增段</b>：段数增加 → 新段首次投影，旧段只在内容确实变了时收口；</li>
 *   <li><b>工具结果晚到</b>：段内 {@code running/finished/resultChars} 变化 → 重建；</li>
 *   <li><b>卡片增删 / 内容变化</b>：round 落在该段的卡片数量或组件摘要变化 → 重建
 *       （摘要见 {@link SegmentComponentDigest}：卡片关键字段 + 子代理轨迹 + layout 元数据）；</li>
 *   <li><b>审批插入</b>：该段审批计数变化 → 重建（审批是消息级字段，见 Activity 记账）；</li>
 *   <li><b>正文/思考追加</b>：段内字符数变化 → 重建（活动段天然满足；封口段只有
 *       权威 {@code assistant/message} 晚到才会命中，属于必须救回的内容）；</li>
 *   <li><b>纯尾部文本追加</b>：只改活动段 → 封口段指纹不变 → <b>不失效</b>。</li>
 * </ol>
 *
 * <p>线程模型：只在主线程（Activity 帧末）调用，无锁。</p>
 */
public final class SegmentProjectionCache {

    /** 为什么这次要投影（用于日志与单测断言） */
    public enum Kind {
        /** 新段第一次投影 */
        NEW,
        /** 仍是活动段，正常重建 */
        ACTIVE,
        /** 本帧刚被封口，做最后一次收口投影 */
        FINALIZE,
        /** 已经封口的段因内容变化被重新投影（验收目标 = 0） */
        SEALED_REBUILD
    }

    /**
     * 一段内容的**投影指纹**：只放"投影结果会变"的维度。
     *
     * <p>字符数而不是内容本身：{@code assistant/message} 的权威值会整体替换文本，
     * 长度变化足够触发重建；同长度换内容在 DSH 事件流里不存在（delta 只会追加）。</p>
     */
    public static final class Progress {
        public final int steps;
        public final int textChars;
        public final int thinkingChars;
        public final int runningTools;
        public final int finishedTools;
        public final int toolResultChars;
        /** round 落在本段的卡片数（卡片增删） */
        public final int cards;
        /** 本段插入的审批数（审批是消息级字段；用于失效口径，不参与组件投影） */
        public final int approvals;
        /**
         * 段内非工具组件的稳定摘要（2026-11-15，§19C）。
         *
         * <p>卡片张数不变、但卡片<b>内容</b>变了（子代理轨迹更新、job/alert 状态变化、
         * layout 元数据换版）时，仅靠 {@link #cards} 是发现不了的。这里存
         * {@link SegmentComponentDigest} 的摘要：相同内容必同值，变化才失效（每帧只滚 hash）。</p>
         */
        public final long componentsDigest;

        public Progress(int steps, int textChars, int thinkingChars,
                        int runningTools, int finishedTools, int toolResultChars,
                        int cards, int approvals) {
            this(steps, textChars, thinkingChars, runningTools, finishedTools, toolResultChars,
                    cards, approvals, 0L);
        }

        public Progress(int steps, int textChars, int thinkingChars,
                        int runningTools, int finishedTools, int toolResultChars,
                        int cards, int approvals, long componentsDigest) {
            this.steps = steps;
            this.textChars = textChars;
            this.thinkingChars = thinkingChars;
            this.runningTools = runningTools;
            this.finishedTools = finishedTools;
            this.toolResultChars = toolResultChars;
            this.cards = cards;
            this.approvals = approvals;
            this.componentsDigest = componentsDigest;
        }

        public static Progress empty(int steps) {
            return new Progress(steps, 0, 0, 0, 0, 0, 0, 0, 0L);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Progress)) return false;
            Progress p = (Progress) o;
            return steps == p.steps
                    && textChars == p.textChars
                    && thinkingChars == p.thinkingChars
                    && runningTools == p.runningTools
                    && finishedTools == p.finishedTools
                    && toolResultChars == p.toolResultChars
                    && cards == p.cards
                    && approvals == p.approvals
                    && componentsDigest == p.componentsDigest;
        }

        @Override
        public int hashCode() {
            int h = steps;
            h = h * 31 + textChars;
            h = h * 31 + thinkingChars;
            h = h * 31 + runningTools;
            h = h * 31 + finishedTools;
            h = h * 31 + toolResultChars;
            h = h * 31 + cards;
            h = h * 31 + approvals;
            h = h * 31 + (int) (componentsDigest ^ (componentsDigest >>> 32));
            return h;
        }

        @Override
        public String toString() {
            return "Progress{steps=" + steps + " text=" + textChars + " think=" + thinkingChars
                    + " tools=" + runningTools + "/" + finishedTools + " res=" + toolResultChars
                    + " cards=" + cards + " approvals=" + approvals
                    + " digest=" + Long.toHexString(componentsDigest) + '}';
        }
    }

    /** 由 Activity 提供：按段区间从 DshTimeline 取指纹（不把 Android/时间线依赖带进本类） */
    public interface ProgressSource {
        Progress progress(int segmentIndex, int fromStep, int toStep, int stepsInRange);
    }

    /** 一条"本帧要投影"的决策 */
    public static final class Rebuild {
        public final int index;
        public final int fromStep;
        public final int toStep;
        public final Kind kind;
        public final String reason;
        public final Progress progress;
        final boolean wasSealed;

        Rebuild(int index, int fromStep, int toStep, Kind kind, String reason,
                Progress progress, boolean wasSealed) {
            this.index = index;
            this.fromStep = fromStep;
            this.toStep = toStep;
            this.kind = kind;
            this.reason = reason;
            this.progress = progress;
            this.wasSealed = wasSealed;
        }

        @Override
        public String toString() {
            return "段" + index + "[" + fromStep + "," + toStep + ") " + kind + " · " + reason;
        }
    }

    /** 本帧投影计划（Activity 按顺序执行；列表只读） */
    public static final class Plan {
        private final List<Rebuild> rebuilds;
        public final int totalSegments;
        public final int sealedSegments;
        public final int activeSegments;

        Plan(List<Rebuild> rebuilds, int totalSegments, int sealedSegments, int activeSegments) {
            this.rebuilds = Collections.unmodifiableList(rebuilds);
            this.totalSegments = totalSegments;
            this.sealedSegments = sealedSegments;
            this.activeSegments = activeSegments;
        }

        public List<Rebuild> rebuilds() {
            return rebuilds;
        }

        public boolean isEmpty() {
            return rebuilds.isEmpty();
        }

        /** 本帧是否有封口段被重建（验收要看的那个数） */
        public boolean hasSealedRebuild() {
            for (Rebuild r : rebuilds) if (r.kind == Kind.SEALED_REBUILD) return true;
            return false;
        }
    }

    private static final class Slot {
        final int index;
        final int fromStep;
        final int toStep;
        boolean projected;
        boolean sealed;
        Progress progress;

        Slot(int index) {
            this.index = index;
            this.fromStep = SegmentSealPolicy.fromStep(index);
            this.toStep = SegmentSealPolicy.toStep(index);
        }
    }

    private final List<Slot> slots = new ArrayList<>();
    private long newProjections;
    private long activeProjections;
    private long finalizeProjections;
    private long sealedRebuilds;
    private long skippedSealed;

    /** 换回合/快照重建时清空（消息列表由 Activity 负责，本类只清决策状态） */
    public void reset() {
        slots.clear();
        newProjections = 0;
        activeProjections = 0;
        finalizeProjections = 0;
        sealedRebuilds = 0;
        skippedSealed = 0;
    }

    /**
     * 算出本帧要投影哪些段。
     *
     * @param stepCount 时间线当前总步数（原始步序号；空步也占号，投影内部会跳过空步）
     * @param ended     本帧是否收到 turn/end（最后一段也随之封口）
     * @param source    段指纹来源（null = 全 0，仅单测用）
     */
    public Plan plan(int stepCount, boolean ended, ProgressSource source) {
        int total = Math.max(0, stepCount);
        int count = SegmentSealPolicy.segmentCount(total);
        // 时间线回退（重放/快照）时裁剪尾部槽位；正常流式只会增长
        while (slots.size() > count) slots.remove(slots.size() - 1);
        while (slots.size() < count) slots.add(new Slot(slots.size()));

        int lastIndex = count - 1;
        List<Rebuild> rebuilds = new ArrayList<>();
        for (int i = 0; i < count; i += 1) {
            Slot s = slots.get(i);
            int steps = Math.max(0, Math.min(s.toStep, total) - s.fromStep);
            Progress cur = source == null
                    ? Progress.empty(steps)
                    : source.progress(i, s.fromStep, s.toStep, steps);
            if (cur == null) cur = Progress.empty(steps);
            boolean sealedNow = SegmentSealPolicy.isSealed(i, lastIndex, !ended);
            boolean wasSealed = s.sealed;

            if (!s.projected) {
                rebuilds.add(new Rebuild(i, s.fromStep, s.toStep, Kind.NEW, "新建", cur, false));
            } else if (wasSealed) {
                if (s.progress != null && s.progress.equals(cur)) {
                    skippedSealed += 1;
                } else {
                    rebuilds.add(new Rebuild(i, s.fromStep, s.toStep,
                            Kind.SEALED_REBUILD, diff(s.progress, cur), cur, true));
                }
            } else if (!sealedNow) {
                rebuilds.add(new Rebuild(i, s.fromStep, s.toStep, Kind.ACTIVE, "活动", cur, false));
            } else {
                // 本帧刚封口：上一帧已经投影过，只有内容确实变了才补一次收口投影
                if (s.progress != null && s.progress.equals(cur)) {
                    skippedSealed += 1;
                } else {
                    rebuilds.add(new Rebuild(i, s.fromStep, s.toStep,
                            Kind.FINALIZE, diff(s.progress, cur), cur, false));
                }
            }
            s.sealed = sealedNow;
        }
        return new Plan(rebuilds, count,
                ended ? count : Math.max(0, count - 1),
                count > 0 && !ended ? 1 : 0);
    }

    /**
     * 投影成功后记账（§19A 守卫计数就在这个"投影入口"上）。
     *
     * <p>计数口径：只有 {@code wasSealed=true} 的重投影才算"封口重建"；封口瞬间的
     * 收口投影单列，避免把每次跨段的正常收口误算成"已完成段被反复重建"。</p>
     */
    public void markProjected(Rebuild r) {
        if (r == null || r.index < 0 || r.index >= slots.size()) return;
        Slot s = slots.get(r.index);
        s.projected = true;
        s.progress = r.progress;
        switch (r.kind) {
            case NEW:
                newProjections += 1;
                break;
            case FINALIZE:
                finalizeProjections += 1;
                break;
            case SEALED_REBUILD:
                sealedRebuilds += 1;
                break;
            case ACTIVE:
            default:
                activeProjections += 1;
                break;
        }
    }

    /** 已封口段被重新 project 的次数（验收目标 = 0） */
    public long sealedRebuilds() {
        return sealedRebuilds;
    }

    /** 活动段投影次数（含新建段与收口投影，见类注释口径） */
    public long activeProjections() {
        return activeProjections + newProjections + finalizeProjections;
    }

    public long newProjections() {
        return newProjections;
    }

    public long finalizeProjections() {
        return finalizeProjections;
    }

    /** 本帧按指纹跳过（不投影）的已封口段次数 */
    public long skippedSealed() {
        return skippedSealed;
    }

    public int totalSegments() {
        return slots.size();
    }

    public boolean isSealed(int index) {
        return index >= 0 && index < slots.size() && slots.get(index).sealed;
    }

    public boolean isProjected(int index) {
        return index >= 0 && index < slots.size() && slots.get(index).projected;
    }

    /** 两帧指纹的差异描述（日志用） */
    static String diff(Progress before, Progress after) {
        if (before == null) return "首次内容";
        List<String> parts = new ArrayList<>();
        if (before.steps != after.steps) parts.add("步数");
        if (before.textChars != after.textChars || before.thinkingChars != after.thinkingChars) {
            parts.add("正文");
        }
        if (before.runningTools != after.runningTools
                || before.finishedTools != after.finishedTools
                || before.toolResultChars != after.toolResultChars) {
            parts.add("工具结果");
        }
        if (before.cards != after.cards) parts.add("卡片");
        else if (before.componentsDigest != after.componentsDigest) parts.add("组件");
        if (before.approvals != after.approvals) parts.add("审批");
        if (parts.isEmpty()) return "内容";
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append('+');
            sb.append(p);
        }
        return sb.toString();
    }
}
