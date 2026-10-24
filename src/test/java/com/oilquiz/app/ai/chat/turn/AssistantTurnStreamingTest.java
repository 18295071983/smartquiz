package com.oilquiz.app.ai.chat.turn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 流式（未完成）消息的 parts 构建（P6 流式接管，2026-10-24）。
 *
 * <p>钉住三件事：① 正在生成的那一轮不能凭空消失（快照分支会丢，这里必须不丢）；
 * ② 已完成轮次的 part id 与指纹逐 tick 不漂（否则 {@code TurnPartsHost} 每 tick 重建含 WebView 的视图）；
 * ③ live 轮的思考只有一个来源、且只有 live 段被打上 streaming 态。</p>
 */
public class AssistantTurnStreamingTest {

    // ---- 夹具 --------------------------------------------------------------

    private static JSONObject props(String... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    private static ComponentData tool(String id, String name, String status, String result) {
        JSONObject p = props("toolCallId", id, "toolName", name, "status", status);
        try {
            if (result != null) p.put("result", result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return ComponentData.of("tool_call", p);
    }

    /** 生成中：第 1 轮已定稿并落了边界，第 2 轮正文与思考还在长。 */
    private static ChatMessage streamingTwoRounds() {
        ChatMessage msg = ChatMessage.createAIMessage("m1", "第一段\n\n第二段在写", 1000L);
        msg.turnId = "T1";
        msg.status = ChatMessage.MessageStatus.GENERATING;
        msg.roundSnapshots = new ArrayList<>();
        ChatMessage.RoundSnapshot r1 =
                new ChatMessage.RoundSnapshot(1, "第一段\n\n", "想1", 11L);
        r1.toolCallIds = new ArrayList<>(Arrays.asList("call_1"));
        msg.roundSnapshots.add(r1);
        // 轮次边界在工具调用那刻就记下了，而第 2 轮还没有快照（"第一段\n\n" = 5 个字符）
        msg.contentRoundBounds = new ArrayList<>(Arrays.asList(5));
        msg.contentRoundToolIds = new ArrayList<>(Arrays.asList("call_1"));
        msg.thinkingRounds = new ArrayList<>(Arrays.asList("想1"));
        msg.thinkingContent = "想2 正在想";
        msg.components = new ArrayList<>(Arrays.asList(
                tool("call_1", "查天气", "success", "晴")));
        return msg;
    }

    private static List<String> types(AssistantTurn t) {
        List<String> out = new ArrayList<>();
        if (t == null) return out;
        for (AssistantTurn.Part p : t.parts) out.add(p.type);
        return out;
    }

    private static List<String> ids(AssistantTurn t) {
        List<String> out = new ArrayList<>();
        if (t == null) return out;
        for (AssistantTurn.Part p : t.parts) out.add(p.id);
        return out;
    }

    private static AssistantTurn.Part byId(AssistantTurn t, String id) {
        for (AssistantTurn.Part p : t.parts) {
            if (p != null && id.equals(p.id)) return p;
        }
        return null;
    }

    private static int countType(AssistantTurn t, String type) {
        int n = 0;
        for (AssistantTurn.Part p : t.parts) {
            if (type.equals(p.type)) n++;
        }
        return n;
    }

    // ---- ① live 轮不许掉 --------------------------------------------------

    @Test
    public void streamingBuilderKeepsTheRoundSnapshotsHaveNotClosedYet() {
        ChatMessage msg = streamingTwoRounds();

        AssistantTurn live = AssistantTurn.fromMessageForStreaming(msg, false);
        assertNotNull(live);
        // 顺序 = 思考1 → 正文1 → 工具组1 → 思考2(live) → 正文2(live)
        assertEquals(Arrays.asList(
                AssistantTurn.Part.TYPE_REASONING,
                AssistantTurn.Part.TYPE_TEXT,
                AssistantTurn.Part.TYPE_TOOL_GROUP,
                AssistantTurn.Part.TYPE_REASONING,
                AssistantTurn.Part.TYPE_TEXT), types(live));
        assertEquals("第一段\n\n", byId(live, "T1-R1:text").text);
        assertEquals("第二段在写", byId(live, "T1-R2:text").text);
        assertEquals("想2 正在想", byId(live, "T1-R2:reasoning").text);
    }

    /**
     * 这条是本轮改造的理由本身：完成态生产者读 {@code roundSnapshots} 优先，
     * 拿它渲流式消息就会把"正在写的这一段"整段吃掉。若哪天有人把闸门改成
     * "流式也走 fromMessageForParts"，这条会立刻变红。
     */
    @Test
    public void completedProducerDropsTheLiveRoundSoTheGateMustNotReuseIt() {
        ChatMessage msg = streamingTwoRounds();

        AssistantTurn done = AssistantTurn.fromMessageForParts(msg, false);
        assertNull("完成态构建里没有 live 轮的正文 part", byId(done, "T1-R2:text"));
        assertFalse("完成态构建里根本看不到正在写的这一段",
                textOfAll(done).contains("第二段在写"));
        assertTrue(textOfAll(AssistantTurn.fromMessageForStreaming(msg, false))
                .contains("第二段在写"));
    }

    private static String textOfAll(AssistantTurn t) {
        StringBuilder sb = new StringBuilder();
        for (AssistantTurn.Part p : t.parts) {
            if (p != null && p.text != null) sb.append(p.text);
        }
        return sb.toString();
    }

    @Test
    public void withoutAnyRoundBoundaryTheWholeAccumulatedContentIsTheLiveRound() {
        ChatMessage msg = ChatMessage.createAIMessage("m2", "正在写的全文", 1000L);
        msg.turnId = "T2";
        msg.status = ChatMessage.MessageStatus.GENERATING;
        msg.thinkingContent = "正在想的事";

        AssistantTurn live = AssistantTurn.fromMessageForStreaming(msg, false);
        assertEquals(Arrays.asList(
                AssistantTurn.Part.TYPE_REASONING,
                AssistantTurn.Part.TYPE_TEXT), types(live));
        assertEquals("正在写的全文", live.parts.get(1).text);
        assertEquals("live 段要打流式态", AssistantTurn.Part.STATE_STREAMING, live.parts.get(1).state);
        assertEquals(AssistantTurn.Part.STATE_STREAMING, live.parts.get(0).state);
    }

    // ---- ② id 与指纹不漂 --------------------------------------------------

    @Test
    public void closedRoundsKeepTheirIdsAndFingerprintsAcrossTicks() {
        ChatMessage tick1 = streamingTwoRounds();
        AssistantTurn first = AssistantTurn.fromMessageForStreaming(tick1, false);

        // 下一 tick：live 正文在长、思考在长，第 1 轮与它的工具一动不动
        tick1.content = "第一段\n\n第二段在写了更多";
        tick1.thinkingContent = "想2 正在想了更多";
        AssistantTurn next = AssistantTurn.fromMessageForStreaming(tick1, false);

        assertEquals(ids(first), ids(next));
        assertEquals("已完成轮的思考 part 不该重绑",
                byId(first, "T1-R1:reasoning").fingerprint(),
                byId(next, "T1-R1:reasoning").fingerprint());
        assertEquals("已完成轮的正文 part 不该重绑",
                byId(first, "T1-R1:text").fingerprint(),
                byId(next, "T1-R1:text").fingerprint());
        assertEquals("工具组卡不该重绑",
                byId(first, "T1-R1:tool:call_1:grp").fingerprint(),
                byId(next, "T1-R1:tool:call_1:grp").fingerprint());
        assertFalse("只有 live 段的指纹在变",
                byId(first, "T1-R2:text").fingerprint().equals(
                        byId(next, "T1-R2:text").fingerprint()));
        assertEquals("第二段在写了更多", byId(next, "T1-R2:text").text);
    }

    @Test
    public void toolAppearingMidStreamStartsItsOwnStableGroup() {
        ChatMessage msg = ChatMessage.createAIMessage("m3", "先写一段", 1000L);
        msg.turnId = "T3";
        msg.status = ChatMessage.MessageStatus.GENERATING;
        msg.thinkingRounds = new ArrayList<>(Arrays.asList("想A"));
        // 工具刚调用：边界与 id 已记下，正文还没 flush 下一轮
        msg.contentRoundBounds = new ArrayList<>(Arrays.asList(4));
        msg.contentRoundToolIds = new ArrayList<>(Arrays.asList("call_9"));
        msg.components = new ArrayList<>(Arrays.asList(
                tool("call_9", "算温度", "running", null)));
        msg.content = "先写一段";

        AssistantTurn beforeTool = AssistantTurn.fromMessageForStreaming(msg, false);
        assertEquals(1, countType(beforeTool, AssistantTurn.Part.TYPE_TOOL_GROUP));
        String groupId = "T3-R1:tool:call_9:grp";
        assertNotNull(byId(beforeTool, groupId));
        // 结果回来后仍然只是那张组卡指纹变化，id 不变 → 复用同一张卡
        msg.components = new ArrayList<>(Arrays.asList(
                tool("call_9", "算温度", "success", "26 度")));
        AssistantTurn afterResult = AssistantTurn.fromMessageForStreaming(msg, false);
        assertEquals(ids(beforeTool), ids(afterResult));
        assertNotNull(byId(afterResult, groupId));
        assertFalse("结果进来要重绑那张卡",
                byId(beforeTool, groupId).fingerprint()
                        .equals(byId(afterResult, groupId).fingerprint()));
    }

    // ---- ③ 思考只有一个来源，streaming 态只落在 live 段 -------------------

    @Test
    public void liveThinkingOverwritesTheRecordedRoundInsteadOfAppendingASecondOne() {
        ChatMessage msg = streamingTwoRounds();
        // live 轮的思考既已落库（更长），又还在长（更长的另一份）→ 只允许一条，取更全的那份
        msg.thinkingRounds = new ArrayList<>(Arrays.asList("想1", "想2 已落库"));
        msg.thinkingContent = "想2 已落库并且又长了一点";

        AssistantTurn live = AssistantTurn.fromMessageForStreaming(msg, false);
        assertEquals("live 轮只能有一条思考 part",
                2, countType(live, AssistantTurn.Part.TYPE_REASONING));
        assertEquals("想2 已落库并且又长了一点", byId(live, "T1-R2:reasoning").text);
    }

    /**
     * 还没有任何轮次边界（模型连着出多轮、没调工具）：已定稿的每轮思考都要一条一条摆出来
     * （legacy 的思考段是"一个入口 + 一步一行"），但<b>最后一条定稿思考不得与 live 重复</b>——
     * {@code finalizeAgentThinking} 落库后 {@code msg.thinkingContent} 还留着同一份文本。
     */
    @Test
    public void toollessMultiRoundThinkingListsEachRoundOnce() {
        ChatMessage msg = ChatMessage.createAIMessage("m5", "第一轮正文\n\n第二轮在写", 1000L);
        msg.turnId = "T5";
        msg.status = ChatMessage.MessageStatus.GENERATING;
        msg.thinkingRounds = new ArrayList<>(Arrays.asList("想1 定稿"));
        msg.thinkingContent = "想1 定稿";   // 刚定稿、live 缓冲还没换轮

        AssistantTurn live = AssistantTurn.fromMessageForStreaming(msg, false);
        assertEquals("同一段思考只许出现一次",
                1, countType(live, AssistantTurn.Part.TYPE_REASONING));
        assertEquals(1, countType(live, AssistantTurn.Part.TYPE_TEXT));
        assertEquals(AssistantTurn.Part.STATE_STREAMING, byId(live, "T5-R1:text").state);
    }

    @Test
    public void toollessSecondRoundThinkingBecomesItsOwnStep() {
        ChatMessage msg = ChatMessage.createAIMessage("m6", "两轮正文拼在一起", 1000L);
        msg.turnId = "T6";
        msg.status = ChatMessage.MessageStatus.GENERATING;
        msg.thinkingRounds = new ArrayList<>(Arrays.asList("想1 定稿", "想2 刚定稿"));
        msg.thinkingContent = "想2 刚定稿，还在长";

        AssistantTurn live = AssistantTurn.fromMessageForStreaming(msg, false);
        assertEquals(Arrays.asList("T6-R1:reasoning", "T6-R2:reasoning", "T6-R2:text"),
                ids(live));
        assertEquals("想1 定稿", byId(live, "T6-R1:reasoning").text);
        assertEquals("想2 刚定稿，还在长", byId(live, "T6-R2:reasoning").text);
        assertNull("已定稿的第 1 轮不许被打成流式", byId(live, "T6-R1:reasoning").state);
        assertEquals(AssistantTurn.Part.STATE_STREAMING, byId(live, "T6-R2:reasoning").state);
    }

    @Test
    public void shorterLiveThinkingDoesNotTruncateTheFinalizedRound() {
        ChatMessage msg = streamingTwoRounds();
        msg.thinkingRounds = new ArrayList<>(Arrays.asList("想1", "很完整的定稿思考"));
        msg.thinkingContent = "短";

        AssistantTurn live = AssistantTurn.fromMessageForStreaming(msg, false);
        assertEquals("很完整的定稿思考", byId(live, "T1-R2:reasoning").text);
    }

    @Test
    public void onlyTheLiveRoundCarriesStreamingState() {        AssistantTurn live = AssistantTurn.fromMessageForStreaming(streamingTwoRounds(), false);
        assertEquals(AssistantTurn.Part.STATE_STREAMING, byId(live, "T1-R2:text").state);
        assertEquals(AssistantTurn.Part.STATE_STREAMING, byId(live, "T1-R2:reasoning").state);
        assertNull("已完成轮的正文不带流式态", byId(live, "T1-R1:text").state);
        assertNull("已完成轮的思考不带流式态", byId(live, "T1-R1:reasoning").state);
        for (AssistantTurn.Part p : live.parts) {
            assertFalse("工具组卡不打流式态",
                    AssistantTurn.Part.TYPE_TOOL_GROUP.equals(p.type)
                            && AssistantTurn.Part.STATE_STREAMING.equals(p.state));
        }
    }

    /**
     * 轮号是十进制拼接（"T-R1" 是 "T-R11" 的前缀），这里用 11 轮把误匹配钉死：
     * 只有 live 的第 11 轮该被打上流式态，第 1 轮不能跟着中。
     */
    @Test
    public void roundNumberPrefixCollisionDoesNotMarkEarlierRounds() {
        ChatMessage msg = ChatMessage.createAIMessage("m4", "轮1\n\n轮11在写", 1000L);
        msg.turnId = "T4";
        msg.status = ChatMessage.MessageStatus.GENERATING;
        List<Integer> bounds = new ArrayList<>();
        List<String> toolIds = new ArrayList<>();
        List<String> thinks = new ArrayList<>();
        thinks.add("想1");
        for (int i = 1; i <= 10; i++) {
            bounds.add(3 * i);          // 每轮一个边界，正文按 3 字一段切
            toolIds.add("c" + i);
            thinks.add("想" + (i + 1));
        }
        msg.contentRoundBounds = bounds;
        msg.contentRoundToolIds = toolIds;
        msg.thinkingRounds = thinks;
        // content 长度要盖过最后一个边界，否则第 11 段是空
        StringBuilder content = new StringBuilder();
        for (int i = 0; i <= 10; i++) content.append("段").append(i).append("\n");
        msg.content = content.toString();

        AssistantTurn live = AssistantTurn.fromMessageForStreaming(msg, false);
        assertNotNull(byId(live, "T4-R1:text"));
        assertNull("第 1 轮不能被打成流式", byId(live, "T4-R1:text").state);
        assertEquals(AssistantTurn.Part.STATE_STREAMING, byId(live, "T4-R11:text").state);
    }

    // ---- 产物归属在流式期也要成立 ------------------------------------------

    @Test
    public void productMadeByARunningToolFoldsUnderThatToolDuringStreaming() {
        ChatMessage msg = streamingTwoRounds();
        ComponentData chart = ComponentData.of("chart", props(
                "title", "天气图", "producerToolCallId", "call_1", "producerToolName", "查天气"));
        chart.id = "cmp_chart";
        msg.components = new ArrayList<>(msg.components);
        msg.components.add(chart);

        AssistantTurn live = AssistantTurn.fromMessageForStreaming(msg, false);
        assertEquals("顶层不留独立产物卡",
                0, countType(live, AssistantTurn.Part.TYPE_DATA));
        AssistantTurn.Part group = null;
        for (AssistantTurn.Part p : live.parts) {
            if (AssistantTurn.Part.TYPE_TOOL_GROUP.equals(p.type)) group = p;
        }
        assertNotNull(group);
        assertNotNull("组内那张工具要挂上产物", group.group);
        assertNotNull(group.group.get(0).products);
        assertEquals(1, group.group.get(0).products.size());
        assertEquals("cmp_chart", group.group.get(0).products.get(0).id);
    }
}
