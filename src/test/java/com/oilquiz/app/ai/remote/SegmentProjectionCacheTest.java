package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 尾段增量投影的缓存与失效（2026-11-15，§19 尾段增量投影）。
 *
 * <p>验收口径：<b>已完成段重建 = 0</b>。这里把每个失效条件钉成一条用例：</p>
 * <ul>
 *   <li>封口段复用：内容没变 → 不重建（{@code SEALED_REBUILD} 计数不涨）；</li>
 *   <li>活动段重建：每帧只重建最后一段；</li>
 *   <li>新增段：只投影新段，旧段不重建；</li>
 *   <li>工具结果晚到 / 卡片增删 / 审批插入：封口段必须重建；</li>
 *   <li>纯尾部文本追加：只动活动段，封口段不失效。</li>
 * </ul>
 */
public class SegmentProjectionCacheTest {

    private static final int SEG = SegmentSealPolicy.STEPS_PER_SEGMENT;

    /** 按段号提供指纹；未登记的段用空指纹 */
    private static final class Source implements SegmentProjectionCache.ProgressSource {
        final Map<Integer, SegmentProjectionCache.Progress> byIndex = new HashMap<>();

        Source put(int index, SegmentProjectionCache.Progress p) {
            byIndex.put(index, p);
            return this;
        }

        @Override
        public SegmentProjectionCache.Progress progress(int index, int fromStep, int toStep,
                                                        int stepsInRange) {
            SegmentProjectionCache.Progress p = byIndex.get(index);
            return p != null ? p : SegmentProjectionCache.Progress.empty(stepsInRange);
        }
    }

    private static SegmentProjectionCache.Progress progress(int steps, int textChars,
                                                           int running, int finished,
                                                           int resultChars, int cards) {
        return new SegmentProjectionCache.Progress(steps, textChars, textChars / 2,
                running, finished, resultChars, cards, 0);
    }

    private static SegmentProjectionCache.Progress withApprovals(
            SegmentProjectionCache.Progress p, int approvals) {
        return new SegmentProjectionCache.Progress(p.steps, p.textChars, p.thinkingChars,
                p.runningTools, p.finishedTools, p.toolResultChars, p.cards, approvals);
    }

    private static void projectAll(SegmentProjectionCache cache,
                                   SegmentProjectionCache.Plan plan) {
        for (SegmentProjectionCache.Rebuild r : plan.rebuilds()) cache.markProjected(r);
    }

    @Test
    public void activeTailIsRebuiltEveryFrame() {
        SegmentProjectionCache cache = new SegmentProjectionCache();
        Source src = new Source().put(0, progress(3, 30, 0, 1, 100, 1));

        SegmentProjectionCache.Plan p1 = cache.plan(3, false, src);
        assertEquals(1, p1.rebuilds().size());
        assertEquals(SegmentProjectionCache.Kind.NEW, p1.rebuilds().get(0).kind);
        projectAll(cache, p1);

        SegmentProjectionCache.Plan p2 = cache.plan(3, false, src);
        assertEquals(1, p2.rebuilds().size());
        assertEquals(SegmentProjectionCache.Kind.ACTIVE, p2.rebuilds().get(0).kind);
        projectAll(cache, p2);

        assertEquals(0, cache.sealedRebuilds());
        assertEquals(2, cache.activeProjections());     // NEW 1 + ACTIVE 1
        assertEquals(1, cache.totalSegments());         // 只有 1 段
    }

    @Test
    public void sealedSegmentIsReusedWithoutProjection() {
        SegmentProjectionCache cache = new SegmentProjectionCache();
        Source src = new Source()
                .put(0, progress(6, 60, 0, 2, 200, 1))
                .put(1, progress(2, 20, 0, 0, 0, 0));

        projectAll(cache, cache.plan(8, false, src));   // 段0 + 段1
        assertEquals(0, cache.sealedRebuilds());
        assertTrue(cache.isSealed(0));

        // 下一帧：封口段内容没变，活动段照常重建
        SegmentProjectionCache.Plan p = cache.plan(8, false, src);
        assertEquals(1, p.rebuilds().size());
        assertEquals(1, p.rebuilds().get(0).index);
        assertEquals(SegmentProjectionCache.Kind.ACTIVE, p.rebuilds().get(0).kind);
        projectAll(cache, p);
        assertEquals(0, cache.sealedRebuilds());
        assertTrue(cache.skippedSealed() > 0);
    }

    @Test
    public void newSegmentDoesNotRebuildSealedOnes() {
        SegmentProjectionCache cache = new SegmentProjectionCache();
        Source src = new Source().put(0, progress(6, 60, 0, 2, 200, 1));
        projectAll(cache, cache.plan(6, false, src));   // 只有段0

        // 第 7 步出现：段0 封口（内容没变，不重建），段1 新建
        src.put(1, progress(1, 5, 0, 0, 0, 0));
        SegmentProjectionCache.Plan p = cache.plan(7, false, src);
        assertEquals(1, p.rebuilds().size());
        assertEquals(1, p.rebuilds().get(0).index);
        assertEquals(SegmentProjectionCache.Kind.NEW, p.rebuilds().get(0).kind);
        projectAll(cache, p);

        assertEquals(0, cache.sealedRebuilds());
        // 段0 内容没变 → 封口瞬间连收口投影都不需要
        assertEquals(0, cache.finalizeProjections());
        assertTrue(cache.isSealed(0));
    }

    @Test
    public void boundaryCrossingWithChangedTailFinalizesOnce() {
        SegmentProjectionCache cache = new SegmentProjectionCache();
        Source src = new Source().put(0, progress(6, 50, 0, 2, 200, 1));
        projectAll(cache, cache.plan(6, false, src));

        // 同一批里第 6 步的权威正文晚到：段0 内容变了 → 收口投影一次
        src.put(0, progress(6, 80, 0, 2, 220, 1));
        src.put(1, progress(1, 8, 0, 0, 0, 0));
        SegmentProjectionCache.Plan p = cache.plan(7, false, src);
        assertEquals(2, p.rebuilds().size());
        assertEquals(SegmentProjectionCache.Kind.FINALIZE, p.rebuilds().get(0).kind);
        assertEquals(SegmentProjectionCache.Kind.NEW, p.rebuilds().get(1).kind);
        projectAll(cache, p);
        assertEquals(1, cache.finalizeProjections());
        assertEquals(0, cache.sealedRebuilds());

        // 下一帧：段0 已封口且内容没变 → 不再投影
        SegmentProjectionCache.Plan p2 = cache.plan(7, false, src);
        assertEquals(1, p2.rebuilds().size());
        assertEquals(1, p2.rebuilds().get(0).index);
        projectAll(cache, p2);
        assertEquals(0, cache.sealedRebuilds());
    }

    @Test
    public void lateToolResultOnSealedSegmentRebuildsIt() {
        SegmentProjectionCache cache = new SegmentProjectionCache();
        Source src = new Source()
                .put(0, progress(6, 60, 1, 1, 100, 1))
                .put(1, progress(6, 60, 0, 2, 200, 0))
                .put(2, progress(2, 10, 1, 0, 0, 0));
        projectAll(cache, cache.plan(14, false, src));

        // 封口段（段0）里那个 running 的工具结果晚到了
        src.put(0, progress(6, 60, 0, 2, 300, 1));
        SegmentProjectionCache.Plan p = cache.plan(14, false, src);
        SegmentProjectionCache.Rebuild first = p.rebuilds().get(0);
        assertEquals(0, first.index);
        assertEquals(SegmentProjectionCache.Kind.SEALED_REBUILD, first.kind);
        assertTrue(first.reason.contains("工具结果"));
        projectAll(cache, p);

        assertEquals(1, cache.sealedRebuilds());
    }

    @Test
    public void pureTailTextAppendDoesNotInvalidateSealedSegments() {
        SegmentProjectionCache cache = new SegmentProjectionCache();
        Source src = new Source()
                .put(0, progress(6, 60, 0, 2, 200, 1))
                .put(1, progress(6, 60, 0, 2, 200, 1))
                .put(2, progress(3, 30, 0, 0, 0, 0));
        projectAll(cache, cache.plan(15, false, src));
        assertEquals(0, cache.sealedRebuilds());

        // 纯尾部文本追加：只改最后一段
        src.put(2, progress(3, 90, 0, 0, 0, 0));
        SegmentProjectionCache.Plan p = cache.plan(15, false, src);
        assertEquals(1, p.rebuilds().size());
        assertEquals(2, p.rebuilds().get(0).index);
        assertEquals(SegmentProjectionCache.Kind.ACTIVE, p.rebuilds().get(0).kind);
        projectAll(cache, p);
        assertEquals(0, cache.sealedRebuilds());
    }

    @Test
    public void cardAddedToSealedSegmentRebuildsIt() {
        SegmentProjectionCache cache = new SegmentProjectionCache();
        Source src = new Source()
                .put(0, progress(6, 60, 0, 2, 200, 1))
                .put(1, progress(2, 10, 0, 0, 0, 0));
        projectAll(cache, cache.plan(8, false, src));

        src.put(0, progress(6, 60, 0, 2, 200, 2));   // 段0 里新增一张卡
        SegmentProjectionCache.Plan p = cache.plan(8, false, src);
        assertEquals(0, p.rebuilds().get(0).index);
        assertEquals(SegmentProjectionCache.Kind.SEALED_REBUILD, p.rebuilds().get(0).kind);
        assertTrue(p.rebuilds().get(0).reason.contains("卡片"));
        projectAll(cache, p);
        assertEquals(1, cache.sealedRebuilds());
    }

    @Test
    public void approvalInsertedIntoSealedSegmentRebuildsIt() {
        SegmentProjectionCache cache = new SegmentProjectionCache();
        SegmentProjectionCache.Progress base = progress(6, 60, 1, 1, 100, 1);
        Source src = new Source().put(0, base).put(1, progress(2, 10, 0, 0, 0, 0));
        projectAll(cache, cache.plan(8, false, src));

        src.put(0, withApprovals(base, 1));
        SegmentProjectionCache.Plan p = cache.plan(8, false, src);
        assertEquals(0, p.rebuilds().get(0).index);
        assertEquals(SegmentProjectionCache.Kind.SEALED_REBUILD, p.rebuilds().get(0).kind);
        assertTrue(p.rebuilds().get(0).reason.contains("审批"));
        projectAll(cache, p);
        assertEquals(1, cache.sealedRebuilds());
    }

    @Test
    public void lateStepCreationInsideSealedSegmentRebuildsIt() {
        SegmentProjectionCache cache = new SegmentProjectionCache();
        Source src = new Source()
                .put(0, progress(3, 20, 0, 0, 0, 0))
                .put(1, progress(2, 30, 0, 1, 50, 0));
        projectAll(cache, cache.plan(8, false, src));

        // 空步被补出内容（同一段里步数从 3 变 4）
        src.put(0, progress(4, 20, 0, 0, 0, 0));
        SegmentProjectionCache.Plan p = cache.plan(8, false, src);
        assertEquals(0, p.rebuilds().get(0).index);
        assertEquals(SegmentProjectionCache.Kind.SEALED_REBUILD, p.rebuilds().get(0).kind);
        assertTrue(p.rebuilds().get(0).reason.contains("步数"));
    }

    @Test
    public void turnedEndSealsLastSegment() {
        SegmentProjectionCache cache = new SegmentProjectionCache();
        Source src = new Source().put(0, progress(4, 40, 0, 1, 100, 0));
        projectAll(cache, cache.plan(4, false, src));
        assertFalse(cache.isSealed(0));

        // turn/end：最后一段封口；内容没变 → 不重复投影
        SegmentProjectionCache.Plan ended = cache.plan(4, true, src);
        assertEquals(0, ended.rebuilds().size());
        assertTrue(cache.isSealed(0));

        // 封口后内容不再变化 → 永远跳过
        SegmentProjectionCache.Plan after = cache.plan(4, true, src);
        assertEquals(0, after.rebuilds().size());
        assertEquals(0, cache.sealedRebuilds());
    }

    @Test
    public void resetClearsStateAndCounters() {
        SegmentProjectionCache cache = new SegmentProjectionCache();
        Source src = new Source().put(0, progress(6, 60, 0, 1, 100, 0));
        projectAll(cache, cache.plan(6, false, src));
        assertTrue(cache.totalSegments() > 0);

        cache.reset();
        assertEquals(0, cache.totalSegments());
        assertEquals(0, cache.sealedRebuilds());
        assertEquals(0, cache.activeProjections());
    }

    @Test
    public void guardsNeverReportSealedRebuildsForNormalGrowth() {
        // 一帧帧地把 20 步喂进来：只有活动段/收口投影，封口重建必须一直是 0
        SegmentProjectionCache cache = new SegmentProjectionCache();
        Source src = new Source();
        for (int step = 1; step <= 20; step += 1) {
            int seg = SegmentSealPolicy.segmentIndex(step - 1);
            src.put(seg, progress(step - seg * SEG, 10 * step,
                    step % 3 == 0 ? 1 : 0, step / 3, 5 * step, 0));
            SegmentProjectionCache.Plan p = cache.plan(step, false, src);
            projectAll(cache, p);
            assertFalse("第 " + step + " 步不该重建封口段", p.hasSealedRebuild());
        }
        assertEquals(0, cache.sealedRebuilds());
    }

    @Test
    public void planKeepsRebuildOrderAscending() {
        SegmentProjectionCache cache = new SegmentProjectionCache();
        Source src = new Source()
                .put(0, progress(6, 60, 0, 2, 200, 1))
                .put(1, progress(6, 60, 0, 2, 200, 1))
                .put(2, progress(1, 5, 0, 0, 0, 0));
        projectAll(cache, cache.plan(13, false, src));
        src.put(0, progress(6, 60, 0, 3, 250, 1));    // 段0 晚到
        src.put(1, progress(6, 60, 0, 3, 250, 1));    // 段1 晚到
        SegmentProjectionCache.Plan p = cache.plan(13, false, src);
        List<Integer> order = new ArrayList<>();
        for (SegmentProjectionCache.Rebuild r : p.rebuilds()) order.add(r.index);
        assertEquals(java.util.Arrays.asList(0, 1, 2), order);
        assertTrue(p.hasSealedRebuild());
    }
}
