package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 非工具组件纳入段指纹（2026-11-15，§19C）。
 *
 * <p>两个方向都要钉住：</p>
 * <ul>
 *   <li><b>会渲染的字段变了 → 摘要必须变</b>（子代理轨迹状态、job/alert 卡内容、
 *       layout 元数据、dynamic 未知载荷）——否则真机上"卡不刷新"；</li>
 *   <li><b>无关字段变了 → 摘要不能变</b>（埋点/noise、同内容不同插入顺序）——
 *       否则封口段每帧都会被误判失效，「封口重建=0」守不住。</li>
 * </ul>
 */
public class SegmentComponentDigestTest {

    // ===== 卡片：只认关键字段 =====

    @Test
    public void cardKeyFieldChangeChangesHash() {
        DshCards.Card running = new DshCards.Card("job-1", "job",
                DshCards.obj("title", "后台任务", "job_label", "pwsh", "status", "running"));
        DshCards.Card settled = new DshCards.Card("job-1", "job",
                DshCards.obj("title", "后台任务", "job_label", "pwsh", "status", "success"));
        assertTrue("job 状态 running→success 必须让摘要变化",
                SegmentComponentDigest.cardHash(running) != SegmentComponentDigest.cardHash(settled));
    }

    @Test
    public void irrelevantFieldChangeKeepsHash() {
        DshCards.Card a = new DshCards.Card("alert-1", "alert_card",
                DshCards.obj("title", "提醒", "text", "内容", "level", "info", "noise", 1));
        DshCards.Card b = new DshCards.Card("alert-1", "alert_card",
                DshCards.obj("title", "提醒", "text", "内容", "level", "info", "noise", 2));
        assertEquals("无关字段（noise）不该触发重建",
                SegmentComponentDigest.cardHash(a), SegmentComponentDigest.cardHash(b));
    }

    @Test
    public void alertCardTextChangeChangesHash() {
        DshCards.Card a = new DshCards.Card("sys-1", "alert_card",
                DshCards.obj("title", "系统消息", "content", "开始", "level", "info"));
        DshCards.Card b = new DshCards.Card("sys-1", "alert_card",
                DshCards.obj("title", "系统消息", "content", "结束", "level", "info"));
        assertTrue(SegmentComponentDigest.cardHash(a) != SegmentComponentDigest.cardHash(b));
    }

    @Test
    public void dynamicCardHashesWholePayload() {
        // dynamic 没有固定 schema：未知字段也可能被渲染，必须全量参与
        DshCards.Card a = new DshCards.Card("unknown-x-1", "dynamic",
                DshCards.obj("title", "电脑事件", "content", "一", "extra", 1));
        DshCards.Card b = new DshCards.Card("unknown-x-1", "dynamic",
                DshCards.obj("title", "电脑事件", "content", "一", "extra", 2));
        assertTrue(SegmentComponentDigest.cardHash(a) != SegmentComponentDigest.cardHash(b));
    }

    @Test
    public void jsonObjectKeyOrderDoesNotChangeHash() {
        DshCards.Card a = new DshCards.Card("todo-1", "todo_card",
                DshCards.obj("title", "待办", "items", payload("text", "写测试", "done", false)));
        DshCards.Card b = new DshCards.Card("todo-1", "todo_card",
                DshCards.obj("items", payload("done", false, "text", "写测试"), "title", "待办"));
        assertEquals("同内容不同插入顺序必须同摘要（避免每帧误判）",
                SegmentComponentDigest.cardHash(a), SegmentComponentDigest.cardHash(b));
    }

    // ===== 优先级 ①：子代理卡状态变化 =====

    private static JSONObject subagentTool(String childId, String callId, String tool, String args) {
        return payload("child_id", childId, "label", "子代理A", "kind", "tool",
                "callId", callId, "tool", tool, "args", args);
    }

    private static JSONObject subagentResult(String childId, String callId, boolean error, String text) {
        return payload("child_id", childId, "label", "子代理A", "kind", "tool-result",
                "toolCallId", callId, "error", error, "text", text);
    }

    @Test
    public void subagentStatusChangeChangesDigest() {
        DshTimeline t = new DshTimeline();
        t.reduce(DshEvent.from(event("subagent/activity", 1,
                subagentTool("c1", "call-1", "read_file", "/a.txt"))));
        long running = SegmentComponentDigest.digest(t, 0, 6, true);
        assertEquals("同内容重复归约不应变摘要",
                running, SegmentComponentDigest.digest(t, 0, 6, true));

        t.reduce(DshEvent.from(event("subagent/activity", 2,
                subagentResult("c1", "call-1", false, "12 lines"))));
        long success = SegmentComponentDigest.digest(t, 0, 6, true);
        assertTrue("子代理工具 running→success 必须让段指纹变化", running != success);
    }

    @Test
    public void subagentStepsAreOnlyCountedForTailSegment() {
        DshTimeline t = new DshTimeline();
        t.reduce(DshEvent.from(event("subagent/activity", 1,
                subagentTool("c1", "call-1", "read_file", "/a.txt"))));
        long headBefore = SegmentComponentDigest.digest(t, 0, 6, false);
        long tailBefore = SegmentComponentDigest.digest(t, 0, 6, true);

        t.reduce(DshEvent.from(event("subagent/activity", 2,
                subagentResult("c1", "call-1", false, "done"))));

        assertEquals("非尾段不算子代理（否则所有封口段每帧失效）",
                headBefore, SegmentComponentDigest.digest(t, 0, 6, false));
        assertTrue("尾段必须算子代理（甲板读的就是最新一段的 deck_only 组件）",
                tailBefore != SegmentComponentDigest.digest(t, 0, 6, true));
    }

    // ===== 卡片归属：只算本段范围 =====

    @Test
    public void cardsOutsideRangeDoNotAffectDigest() {
        DshTimeline t = new DshTimeline();
        // 先把 1..7 步都建出来：卡片归属轮次是"已存在步里的序号"，缺步会错位
        for (int step = 1; step <= 7; step += 1) {
            t.reduce(DshEvent.from(event("tool/call", step, located(1, step),
                    payload("callId", "c" + step, "name", "pwsh", "arguments", "{}"))));
        }
        t.reduce(DshEvent.from(event("workspace/changes", 8, located(1, 1))));
        t.reduce(DshEvent.from(event("team/mode", 9, located(7, 7), payload("mode", "A"))));
        long seg0 = SegmentComponentDigest.digest(t, 0, 6, false);
        long seg1 = SegmentComponentDigest.digest(t, 6, 12, false);

        // 第 7 步的卡内容变化：只影响第 2 段
        t.reduce(DshEvent.from(event("team/mode", 10, located(7, 7), payload("mode", "B"))));
        assertEquals(seg0, SegmentComponentDigest.digest(t, 0, 6, false));
        assertTrue(seg1 != SegmentComponentDigest.digest(t, 6, 12, false));
    }

    // ===== layout 藏在 tool/result.meta 里 =====

    @Test
    public void layoutMetaChangeChangesDigest() {
        DshTimeline t = new DshTimeline();
        t.reduce(DshEvent.from(event("tool/call", 1, located(1, 1),
                payload("callId", "c1", "name", "read_file", "arguments", "{}"))));
        t.reduce(DshEvent.from(event("tool/result", 2, located(1, 1),
                payload("toolCallId", "c1", "isError", false),
                payload("meta", payload("layout", payload("type", "card", "title", "A"))))));
        long before = SegmentComponentDigest.digest(t, 0, 6, false);

        // 结果字符串一样，只有 layout 元数据换版
        t.reduce(DshEvent.from(event("tool/result", 3, located(1, 1),
                payload("toolCallId", "c1", "isError", false),
                payload("meta", payload("layout", payload("type", "card", "title", "B"))))));
        long after = SegmentComponentDigest.digest(t, 0, 6, false);
        assertTrue("layout 换版必须让段指纹变化", before != after);
    }

    // ===== 闭环：子代理状态变化 → 封口尾段重建一次 =====

    @Test
    public void subagentUpdateRebuildsSealedTailExactlyOnce() {
        final DshTimeline t = new DshTimeline();
        for (int step = 1; step <= 7; step += 1) {
            t.reduce(DshEvent.from(event("tool/call", step, located(1, step),
                    payload("callId", "c" + step, "name", "pwsh", "arguments", "{}"))));
        }
        SegmentProjectionCache.ProgressSource src =
                (index, fromStep, toStep, stepsInRange) ->
                        new SegmentProjectionCache.Progress(stepsInRange, 0, 0, 0, 0, 0, 0, 0,
                                SegmentComponentDigest.digest(t, fromStep, toStep, index == 1));
        SegmentProjectionCache cache = new SegmentProjectionCache();
        SegmentProjectionCache.Plan first = cache.plan(7, true, src); // 回合已结束：两段都封口
        assertEquals(2, first.rebuilds().size());
        for (SegmentProjectionCache.Rebuild r : first.rebuilds()) cache.markProjected(r);
        assertEquals(0, cache.sealedRebuilds());

        t.reduce(DshEvent.from(event("subagent/activity", 100,
                subagentTool("c1", "sub-call", "grep", "TODO"))));
        SegmentProjectionCache.Plan second = cache.plan(7, true, src);
        assertEquals("只该重建子代理所在的尾段", 1, second.rebuilds().size());
        SegmentProjectionCache.Rebuild r = second.rebuilds().get(0);
        assertEquals(1, r.index);
        assertEquals(SegmentProjectionCache.Kind.SEALED_REBUILD, r.kind);
        assertTrue("差异原因应标成组件：" + r.reason, r.reason.contains("组件"));
        for (SegmentProjectionCache.Rebuild x : second.rebuilds()) cache.markProjected(x);
        assertEquals(1, cache.sealedRebuilds());

        // 再算一帧：内容没变，不再重建
        SegmentProjectionCache.Plan third = cache.plan(7, true, src);
        assertTrue("摘要稳定后不应再触发重建", third.isEmpty());
        assertFalse(third.hasSealedRebuild());
    }

    // ===== JSON 构造 =====

    private static JSONObject located(int turn, int step) {
        return payload("turn", turn, "step", step);
    }

    private static JSONObject event(String type, int seq, JSONObject located, JSONObject... merges) {
        JSONObject ev = new JSONObject();
        JSONObject data = new JSONObject();
        try {
            merge(data, located);
            for (JSONObject m : merges) merge(data, m);
            ev.put("type", type);
            ev.put("seq", seq);
            ev.put("data", data);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return ev;
    }

    private static void merge(JSONObject target, JSONObject src) throws Exception {
        if (src == null) return;
        for (java.util.Iterator<String> it = src.keys(); it.hasNext(); ) {
            String k = it.next();
            target.put(k, src.get(k));
        }
    }

    private static JSONObject payload(Object... pairs) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            try {
                o.put(String.valueOf(pairs[i]), pairs[i + 1]);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        return o;
    }
}
