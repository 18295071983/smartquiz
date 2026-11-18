package com.oilquiz.app.ai.chat.turn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * 思考手风琴的合并规则（2026-11-18）。
 *
 * <p>口径：相邻的 reasoning / tool_group 合成一个 {@code thinking} part（一轮一个折叠头），
 * 文本、审批、反问等一律切断分组；单成员也合并（保证后续工具加入时 part id 稳定、视图复用）。</p>
 */
public class AssistantTurnCoalesceTest {

    private static AssistantTurn.Part reasoning(String id, String text) {
        return AssistantTurn.Part.reasoning(id, text, 1L);
    }

    private static AssistantTurn.Part toolGroup(String id, String status) {
        AssistantTurn.Part call = AssistantTurn.Part.toolCall("t-" + id, "c-" + id, "search",
                "{}", status, 2L);
        return AssistantTurn.Part.toolGroup(id, Arrays.asList(call), 2L);
    }

    @Test
    public void adjacentReasoningAndToolsBecomeOneThinkingPart() {
        List<AssistantTurn.Part> merged = AssistantTurn.coalesceThinking(Arrays.asList(
                reasoning("r1", "第一段思考"),
                toolGroup("g1", "success"),
                reasoning("r2", "第二段思考"),
                toolGroup("g2", "success")));

        assertEquals("四段合成一段", 1, merged.size());
        AssistantTurn.Part g = merged.get(0);
        assertEquals(AssistantTurn.Part.TYPE_THINKING, g.type);
        assertEquals("id 取首个成员，后续追加工具时视图可复用", "r1:cot", g.id);
        assertEquals(4, g.group.size());
        assertSame("成员沿用原 part 实例", "第一段思考", g.group.get(0).text);
    }

    @Test
    public void textAndApprovalBreakTheRun() {
        AssistantTurn.Part approval = AssistantTurn.Part.approval(
                "a1", "c1", "file_write", "允许写文件", AssistantTurn.Part.APPROVAL_PENDING, 3L);
        List<AssistantTurn.Part> merged = AssistantTurn.coalesceThinking(Arrays.asList(
                reasoning("r1", "思考"),
                toolGroup("g1", "success"),
                AssistantTurn.Part.text("x1", "正文", 3L),
                approval,
                reasoning("r2", "又思考")));

        assertEquals("工具链 + 正文 + 审批 + 又一段思考 = 4 个 part", 4, merged.size());
        assertEquals(AssistantTurn.Part.TYPE_THINKING, merged.get(0).type);
        assertEquals(AssistantTurn.Part.TYPE_TEXT, merged.get(1).type);
        assertEquals(AssistantTurn.Part.TYPE_APPROVAL, merged.get(2).type);
        assertEquals(AssistantTurn.Part.TYPE_THINKING, merged.get(3).type);
    }

    @Test
    public void singleMemberStillCoalescesForStableId() {
        List<AssistantTurn.Part> merged = AssistantTurn.coalesceThinking(Arrays.asList(
                reasoning("r1", "只有一段思考")));
        assertEquals(1, merged.size());
        assertEquals(AssistantTurn.Part.TYPE_THINKING, merged.get(0).type);
        assertEquals("r1:cot", merged.get(0).id);
    }

    @Test
    public void streamingStateFollowsMembers() {
        AssistantTurn.Part streamingReasoning = reasoning("r1", "思考中");
        streamingReasoning.state = AssistantTurn.Part.STATE_STREAMING;
        List<AssistantTurn.Part> merged = AssistantTurn.coalesceThinking(
                Arrays.asList(streamingReasoning, toolGroup("g1", "success")));
        assertTrue("有成员在流式 → 手风琴算活跃", merged.get(0).isStreaming());

        List<AssistantTurn.Part> runningTool = AssistantTurn.coalesceThinking(
                Arrays.asList(reasoning("r1", "想完了"), toolGroup("g1", "running")));
        assertTrue("工具在跑也算活跃（呼吸点不能停）", runningTool.get(0).isStreaming());

        List<AssistantTurn.Part> done = AssistantTurn.coalesceThinking(
                Arrays.asList(reasoning("r1", "想完了"), toolGroup("g1", "success")));
        assertFalse("全部结束 → 非活跃", done.get(0).isStreaming());
    }

    @Test
    public void inputListIsNotMutated() {
        AssistantTurn.Part r = reasoning("r1", "思考");
        AssistantTurn.Part g = toolGroup("g1", "success");
        List<AssistantTurn.Part> input = Arrays.asList(r, g);
        List<AssistantTurn.Part> out = AssistantTurn.coalesceThinking(input);

        assertEquals("原列表不被替换", 2, input.size());
        assertSame(r, input.get(0));
        assertEquals("产出是新列表", 1, out.size());
        assertNotNull(out.get(0).group);
    }
}
