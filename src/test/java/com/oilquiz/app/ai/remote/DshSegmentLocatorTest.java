package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;

import com.oilquiz.app.ai.chat.ChatMessage;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 审批/反问精确落段（2026-11-15，§19C）。
 *
 * <p>口径：事件所属全局步序号（1 基）→ 段号（6 步一段）；**无法定位返回 -1**，
 * 由 Activity 回退当前尾段并打日志。{@code gone} 按 id 找回<b>原段</b>，不能只清尾段。</p>
 */
public class DshSegmentLocatorTest {

    private static ChatMessage ai(String id) {
        return ChatMessage.createAIMessage(id, "", 1L, null, 0, 0);
    }

    private static ChatMessage.ApprovalRequest approval(String id) {
        return new ChatMessage.ApprovalRequest(
                id, "", "shell", "", "pending", 2, 1L, "dsh");
    }

    private static ChatMessage.QuestionRequest question(String id, String batchId) {
        ChatMessage.QuestionRequest q = new ChatMessage.QuestionRequest();
        q.id = id;
        q.batchId = batchId;
        q.status = "pending";
        return q;
    }

    @Test
    public void roundMapsToSegmentBySixSteps() {
        assertEquals(0, DshSegmentLocator.segmentOfRound(1, 2));
        assertEquals(0, DshSegmentLocator.segmentOfRound(6, 2));
        assertEquals(1, DshSegmentLocator.segmentOfRound(7, 2));
        assertEquals(1, DshSegmentLocator.segmentOfRound(12, 2));
        assertEquals(2, DshSegmentLocator.segmentOfRound(13, 3));
    }

    @Test
    public void unknownRoundOrOutOfRangeFallsBack() {
        // 事件缺 step / 旧数据 → 无法定位，调用方回退尾段
        assertEquals(-1, DshSegmentLocator.segmentOfRound(0, 2));
        assertEquals(-1, DshSegmentLocator.segmentOfRound(-1, 2));
        // 时间线只切出 2 段，却算出第 3 段 → 越界同样回退
        assertEquals(-1, DshSegmentLocator.segmentOfRound(13, 2));
        assertEquals(-1, DshSegmentLocator.segmentOfRound(7, 0));
    }

    @Test
    public void approvalLocatedInOwningSegmentNotTail() {
        List<ChatMessage> segs = new ArrayList<>();
        ChatMessage g0 = ai("T1-A1");
        ChatMessage g1 = ai("T1-G1");
        g0.approvals = new ArrayList<>();
        g0.approvals.add(approval("ap_1"));
        segs.add(g0);
        segs.add(g1);

        assertEquals("approval 在 G0，gone 必须定位到 0 而不是尾段 1",
                0, DshSegmentLocator.segmentOfApproval(segs, "ap_1"));
        assertEquals(-1, DshSegmentLocator.segmentOfApproval(segs, "ap_missing"));
        assertEquals(-1, DshSegmentLocator.segmentOfApproval(segs, ""));
        assertEquals(-1, DshSegmentLocator.segmentOfApproval(null, "ap_1"));
    }

    @Test
    public void questionBatchLocatedInOwningSegment() {
        List<ChatMessage> segs = new ArrayList<>();
        ChatMessage g0 = ai("T1-A1");
        ChatMessage g1 = ai("T1-G1");
        g1.questions = new ArrayList<>();
        g1.questions.add(question("q1", "batch-7"));
        segs.add(g0);
        segs.add(g1);

        assertEquals(1, DshSegmentLocator.segmentOfQuestionBatch(segs, "batch-7"));
        assertEquals(-1, DshSegmentLocator.segmentOfQuestionBatch(segs, "batch-x"));
        assertEquals(-1, DshSegmentLocator.segmentOfQuestionBatch(segs, ""));
    }

    @Test
    public void toolCallRoundDrivesApprovalSegment() {
        // 7 个独立工具调用 → 7 步。第 7 步的工具批的是第 7 步，应落第 2 段（G1）。
        DshTimeline t = new DshTimeline();
        for (int step = 1; step <= 7; step += 1) {
            t.reduce(DshEvent.from(event("tool/call", step, located(1, step),
                    payload("callId", "c" + step, "name", "pwsh", "arguments", "{}"))));
        }
        assertEquals(7, t.currentRound());
        assertEquals(7, t.roundOfTool("c7"));
        assertEquals("第 7 步属于第 2 段（G1，0 基段号 1）",
                1, DshSegmentLocator.segmentOfRound(t.roundOfTool("c7"), 2));
        assertEquals(2, t.roundOfTool("c2"));
        assertEquals(0, t.roundOfTool("missing"));
        assertEquals(0, t.roundOfTool(""));
    }

    // ===== JSON 构造（与 DshProjectionTest 同形） =====

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
