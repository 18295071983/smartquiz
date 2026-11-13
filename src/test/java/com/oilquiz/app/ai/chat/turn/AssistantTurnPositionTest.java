package com.oilquiz.app.ai.chat.turn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * parts 位置基础设施（2026-11-12）：数据卡与审批卡不再统一追加到末尾。
 *
 * <p>旧行为：{@code appendDataParts} 把每张卡都 {@code parts.add(...)} 到最后，于是
 * "第 1 步产出的待办卡"会排在第 3 步正文之后；审批只能弹窗、时间线里没有记录。
 * 现在卡片带 {@code round}，按 {@code -R{n}} 轮次键插到该轮正文/工具之后。</p>
 */
public class AssistantTurnPositionTest {

    @Test
    public void dataCardWithRoundIsInsertedAfterItsOwnRound() throws Exception {
        ChatMessage msg = ChatMessage.createAIMessage("m1", "", 1L);
        msg.turnId = "T1";
        msg.roundSnapshots = new ArrayList<>(Arrays.asList(
                new ChatMessage.RoundSnapshot(1, "第一步正文", "", 10L),
                new ChatMessage.RoundSnapshot(2, "第二步正文", "", 20L)));
        msg.components = new ArrayList<>();
        ComponentData first = ComponentData.of("todo_card",
                new JSONObject().put("items", new org.json.JSONArray()));
        first.id = "T1-C1";
        first.round = 1;   // 属于第一步
        msg.components.add(first);
        ComponentData legacy = ComponentData.of("alert", new JSONObject());
        legacy.id = "T1-C2";
        legacy.round = 0;  // 未定位 → 保持旧行为（末尾）
        msg.components.add(legacy);

        AssistantTurn turn = AssistantTurn.fromMessageForParts(msg);
        List<String> types = new ArrayList<>();
        for (AssistantTurn.Part p : turn.parts) types.add(p.type);

        assertEquals(Arrays.asList("text", "data", "text", "data"), types);
        assertEquals("第一步正文", turn.parts.get(0).text);
        assertEquals("todo_card", turn.parts.get(1).data.type);
        assertEquals("第二步正文", turn.parts.get(2).text);
        assertEquals("alert", turn.parts.get(3).data.type);
    }

    @Test
    public void approvalPartIsPendingAndOrderedByRound() {
        ChatMessage msg = ChatMessage.createAIMessage("m2", "", 1L);
        msg.turnId = "T2";
        msg.roundSnapshots = new ArrayList<>(Arrays.asList(
                new ChatMessage.RoundSnapshot(1, "先跑一个工具", "", 10L),
                new ChatMessage.RoundSnapshot(2, "然后写文件", "", 20L)));
        msg.approvals = new ArrayList<>();
        msg.approvals.add(new ChatMessage.ApprovalRequest(
                "ap_1", "call_1", "write_file", "覆盖 config.json？",
                AssistantTurn.Part.APPROVAL_PENDING, 2, 30L, "dsh"));
        msg.approvals.add(new ChatMessage.ApprovalRequest(
                "ap_0", "", "shell", "历史遗留：无轮次",
                AssistantTurn.Part.APPROVAL_APPROVED, 0, 5L, "dsh"));

        AssistantTurn turn = AssistantTurn.fromMessageForParts(msg);
        List<String> types = new ArrayList<>();
        for (AssistantTurn.Part p : turn.parts) types.add(p.type);

        // 审批只在甲板卡槽渲染（2026-11-13）：消息流 parts 里不再出现 approval，
        // 数据仍然留在 msg.approvals（甲板卡就是从这里构造的）。
        assertEquals(Arrays.asList("text", "text"), types);
        assertEquals(2, msg.approvals.size());
        assertEquals("ap_1", msg.approvals.get(0).id);
        assertEquals("write_file", msg.approvals.get(0).permission);
        assertEquals(AssistantTurn.Part.APPROVAL_PENDING, msg.approvals.get(0).status);
        assertEquals(AssistantTurn.Part.APPROVAL_APPROVED, msg.approvals.get(1).status);
    }

    @Test
    public void approvalWithoutRoundStaysAtEnd() {
        ChatMessage msg = ChatMessage.createAIMessage("m3", "", 1L);
        msg.turnId = "T3";
        msg.roundSnapshots = new ArrayList<>(Arrays.asList(
                new ChatMessage.RoundSnapshot(1, "正文", "", 1L)));
        msg.approvals = new ArrayList<>();
        msg.approvals.add(new ChatMessage.ApprovalRequest(
                "ap_x", "", "shell", "命令", AssistantTurn.Part.APPROVAL_PENDING, 0, 2L, "local"));

        AssistantTurn turn = AssistantTurn.fromMessageForParts(msg);
        assertEquals("text", turn.parts.get(0).type);
        // 审批不进 parts（甲板卡槽负责渲染），数据仍在 msg.approvals
        for (AssistantTurn.Part p : turn.parts) {
            assertNotEquals("approval", p.type);
        }
        assertEquals(1, msg.approvals.size());
    }

    @Test
    public void questionPartsGroupByCallAndCarryOptions() throws Exception {
        ChatMessage msg = ChatMessage.createAIMessage("m5", "", 1L);
        msg.turnId = "T5";
        msg.roundSnapshots = new ArrayList<>(Arrays.asList(
                new ChatMessage.RoundSnapshot(1, "先问你", "", 10L)));
        msg.questions = new ArrayList<>();
        msg.questions.add(question("q1", "call-9", "选哪个？", 1));
        msg.questions.add(question("q2", "call-9", "还要什么？", 1));

        AssistantTurn turn = AssistantTurn.fromMessageForParts(msg);
        // 反问也不进 parts（甲板卡槽渲染），数据仍在 msg.questions
        for (AssistantTurn.Part p : turn.parts) {
            assertNotEquals(AssistantTurn.Part.TYPE_QUESTION, p.type);
        }
        assertEquals(2, msg.questions.size());
        assertEquals("q1", msg.questions.get(0).id);
        assertEquals("call-9", msg.questions.get(0).callId);

        // 已回答：状态变了，问题内容不丢（甲板卡按 status 决定要不要继续显示）
        msg.questions.get(0).status = "answered";
        msg.questions.get(1).status = "answered";
        AssistantTurn answered = AssistantTurn.fromMessageForParts(msg);
        for (AssistantTurn.Part p : answered.parts) {
            assertNotEquals(AssistantTurn.Part.TYPE_QUESTION, p.type);
        }
        assertEquals("answered", msg.questions.get(0).status);
        assertEquals(2, msg.questions.size());
    }

    private static ChatMessage.QuestionRequest question(String id, String callId,
                                                        String text, int round) {
        ChatMessage.QuestionRequest q = new ChatMessage.QuestionRequest();
        q.id = id;
        q.callId = callId;
        q.question = text;
        q.options = "[{\"label\":\"A\"},{\"label\":\"B\"}]";
        q.status = AssistantTurn.Part.APPROVAL_PENDING;
        q.round = round;
        q.timestamp = 1L;
        return q;
    }
    /** 纯工具步也参与轮次计算：卡插在工具组之后，而不是跑到下一步正文后面 */
    @Test
    public void dataCardFollowsToolGroupOfItsRound() throws Exception {
        ChatMessage msg = ChatMessage.createAIMessage("m4", "", 1L);
        msg.turnId = "T4";
        ChatMessage.RoundSnapshot r1 = new ChatMessage.RoundSnapshot(1, "", "", 10L);
        r1.toolCallIds = new ArrayList<>(Arrays.asList("call_1"));
        msg.roundSnapshots = new ArrayList<>(Arrays.asList(
                r1, new ChatMessage.RoundSnapshot(2, "第二步正文", "", 20L)));
        msg.components = new ArrayList<>();

        JSONObject toolProps = new JSONObject()
                .put("toolCallId", "call_1").put("toolName", "pwsh").put("status", "success");
        ComponentData tool = ComponentData.of("tool_call", toolProps);
        tool.id = "T4-F1";
        msg.components.add(tool);

        ComponentData todo = ComponentData.of("todo_card", new JSONObject());
        todo.id = "T4-C1";
        todo.round = 1;
        msg.components.add(todo);

        AssistantTurn turn = AssistantTurn.fromMessageForParts(msg);
        List<String> types = new ArrayList<>();
        for (AssistantTurn.Part p : turn.parts) types.add(p.type);

        assertEquals(Arrays.asList("tool_group", "data", "text"), types);
    }
}
