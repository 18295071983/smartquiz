package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.agent.AgentExecutionState;
import com.oilquiz.app.ai.chat.component.ComponentData;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 活动时间线模型（{@link ActivityTimeline}）的口径用例（2026-10-20，合并方案 S1）。
 *
 * <p>合并后思考与工具必须是同一条有序时间线，且同一 toolCallId 只能出现一次。
 * 这些是纯数据规则，全部在 JVM 里钉住；视图侧只按行画，不再各自判断轮次。
 */
public class ActivityTimelineTest {

    private ChatMessage aiMessage() {
        ChatMessage m = ChatMessage.createAIMessage("正文");
        m.turnId = "T1";
        return m;
    }

    private ComponentData tool(String callId, String name, String status) throws Exception {
        JSONObject props = new JSONObject();
        props.put("toolCallId", callId);
        props.put("toolName", name);
        props.put("status", status);
        props.put("args", "{\"q\":\"一元二次方程\"}");
        return ComponentData.of("tool_call", props);
    }

    private static List<String> ids(ActivityTimeline.Model model) {
        List<String> out = new ArrayList<>();
        for (ActivityTimeline.Item it : model.items) out.add(it.id);
        return out;
    }

    private static List<ActivityTimeline.Kind> kinds(ActivityTimeline.Model model) {
        List<ActivityTimeline.Kind> out = new ArrayList<>();
        for (ActivityTimeline.Item it : model.items) out.add(it.kind);
        return out;
    }

    @Test
    public void interleavesThinkingAndToolsByRoundBounds() throws Exception {
        ChatMessage m = aiMessage();
        m.addThinkingRound("先定位问题", "T1-K1", 2100L);
        m.addThinkingRound("再确认范围", "T1-K2", 2500L);
        m.addThinkingRound("最后决定输出", "T1-K3", 0L);
        m.contentRoundBounds = Arrays.asList(10, 20);
        m.components = new ArrayList<>(Arrays.asList(
                tool("C1", "search", "completed"),
                tool("C2", "calc", "running")));

        ActivityTimeline.Model model = ActivityTimeline.build(m);

        assertEquals(Arrays.asList(
                        ActivityTimeline.Kind.THINKING, ActivityTimeline.Kind.TOOL,
                        ActivityTimeline.Kind.THINKING, ActivityTimeline.Kind.TOOL,
                        ActivityTimeline.Kind.THINKING),
                kinds(model));
        assertEquals(Arrays.asList("T1-K1", "tool:C1", "T1-K2", "tool:C2", "T1-K3"),
                ids(model));
        assertEquals(3, model.thinkingSteps);
        assertEquals(2, model.toolCalls);
        assertEquals(4600L, model.thinkingDurationMs);
        assertTrue(model.anyRunning);
        assertFalse(model.anyFailed);
    }

    @Test
    public void sameToolCallIdIsMergedAndLatestStatusWins() throws Exception {
        ChatMessage m = aiMessage();
        m.components = new ArrayList<>(Arrays.asList(
                tool("C1", "search", "running"),
                tool("C1", "search", "completed")));

        ActivityTimeline.Model model = ActivityTimeline.build(m);

        assertEquals(1, model.toolCalls);
        assertEquals("tool:C1", model.items.get(0).id);
        assertEquals(ActivityTimeline.Status.DONE, model.items.get(0).status);
        assertFalse(model.anyRunning);
    }

    @Test
    public void toolStatusDurationAndAnchorAreParsed() throws Exception {
        ChatMessage m = aiMessage();
        JSONObject props = new JSONObject();
        props.put("toolCallId", "C9");
        props.put("toolName", "ai_weather");
        props.put("status", "failed");
        props.put("args", "{\"city\":\"银川\"}");
        props.put("result", "网络超时");
        props.put("durationMs", 1200L);
        m.components = new ArrayList<>();
        m.components.add(ComponentData.of("tool_call", props));

        ActivityTimeline.Model model = ActivityTimeline.build(m);

        ActivityTimeline.Item item = model.items.get(0);
        assertEquals(ActivityTimeline.Status.FAILED, item.status);
        assertEquals(1200L, item.durationMs);
        assertEquals("C9", item.toolCallId);
        assertEquals("网络超时", item.body);
        assertTrue(model.anyFailed);
        // 中文名走 ToolMetaRegistry，不把内部名甩给用户
        assertFalse(item.title.contains("ai_weather"));
    }

    @Test
    public void leftoverToolsAreAppendedWhenBoundsAreShorter() throws Exception {
        ChatMessage m = aiMessage();
        m.addThinkingRound("第一轮", "T1-K1", 0L);
        m.addThinkingRound("第二轮", "T1-K2", 0L);
        m.contentRoundBounds = java.util.Collections.singletonList(5);
        m.components = new ArrayList<>(Arrays.asList(
                tool("C1", "a", "completed"),
                tool("C2", "b", "completed"),
                tool("C3", "c", "completed")));

        ActivityTimeline.Model model = ActivityTimeline.build(m);

        assertEquals(Arrays.asList("T1-K1", "tool:C1", "T1-K2", "tool:C2", "tool:C3"),
                ids(model));
        assertEquals(3, model.toolCalls);
    }

    @Test
    public void withoutBoundsThinkingComesFirstThenTools() throws Exception {
        ChatMessage m = aiMessage();
        m.addThinkingRound("第一轮", "T1-K1", 1000L);
        m.addThinkingRound("第二轮", "T1-K2", 1000L);
        m.components = new ArrayList<>(Arrays.asList(
                tool("C1", "a", "completed"),
                tool("C2", "b", "completed")));

        ActivityTimeline.Model model = ActivityTimeline.build(m);

        assertEquals(Arrays.asList(
                        ActivityTimeline.Kind.THINKING, ActivityTimeline.Kind.THINKING,
                        ActivityTimeline.Kind.TOOL, ActivityTimeline.Kind.TOOL),
                kinds(model));
        assertEquals(2000L, model.thinkingDurationMs);
    }

    @Test
    public void oldThinkingWithoutIdGetsStableFallbackAnchor() {
        ChatMessage m = aiMessage();
        m.thinkingRounds = new ArrayList<>(Arrays.asList("只有内容没有 id 的旧数据"));
        m.thinkingRoundIds = null;
        m.thinkingRoundDurationsMs = null;

        ActivityTimeline.Model model = ActivityTimeline.build(m);

        assertEquals(1, model.items.size());
        assertEquals("round-1", model.items.get(0).id);
        assertEquals(0L, model.items.get(0).durationMs);
    }

    @Test
    public void fallsBackToAgentBlocksWhenThereAreNoRoundsOrToolCards() {
        ChatMessage m = aiMessage();
        m.agentExecutionState = new AgentExecutionState("m1");
        m.agentExecutionState.addBlock(AgentExecutionState.BlockType.THINKING, "思考", "先想");
        m.agentExecutionState.addBlock(AgentExecutionState.BlockType.TOOL_CALL, "查天气", "调用");
        m.agentExecutionState.setBlockStatus(1, AgentExecutionState.ExecutionBlock.BlockStatus.RUNNING);

        ActivityTimeline.Model model = ActivityTimeline.build(m);

        assertEquals(Arrays.asList(ActivityTimeline.Kind.THINKING, ActivityTimeline.Kind.TOOL),
                kinds(model));
        assertEquals("block:0", model.items.get(0).id);
        assertEquals("先想", model.items.get(0).body);
        assertTrue(model.anyRunning);
    }

    @Test
    public void emptyMessageYieldsEmptyModel() {
        ActivityTimeline.Model model = ActivityTimeline.build(aiMessage());
        assertTrue(model.isEmpty());
        assertEquals(0, model.thinkingSteps);
        assertEquals(0, model.toolCalls);
        assertNotNull(ActivityTimeline.build(null));
        assertTrue(ActivityTimeline.build(null).isEmpty());
    }

    @Test
    public void previewTakesFirstLineAndStripsMarkdownNoise() {
        assertEquals("第一行摘要", ActivityTimeline.preview("**第一行摘要**\n第二行", 48));
        assertEquals("只有一段", ActivityTimeline.preview("只有一段", 48));
        String longText = "一二三四五六七八九十";
        assertEquals("一二三四五", ActivityTimeline.preview(longText, 5));
        assertEquals("", ActivityTimeline.preview(null, 10));
    }

    @Test
    public void oneLineBriefFlattensAndTruncates() {
        assertEquals("a b c", ActivityTimeline.oneLineBrief("a\r\nb\tc"));
        String longArgs = "0123456789012345678901234567890123456789012345678901234567890123456789";
        String brief = ActivityTimeline.oneLineBrief(longArgs);
        assertEquals(61, brief.length()); // 60 + 省略号
        assertTrue(brief.endsWith("…"));
    }
}
