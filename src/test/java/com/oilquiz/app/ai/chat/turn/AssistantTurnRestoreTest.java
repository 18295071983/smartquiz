package com.oilquiz.app.ai.chat.turn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.RoundBounds;
import com.oilquiz.app.ai.chat.component.ComponentData;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 历史恢复的 canonical turn 归一（2026-10-24）：新旧数据必须拿到同一个生产者产出的 parts，
 * 且恢复不得改写既有正文。
 */
public class AssistantTurnRestoreTest {

    // ---- 夹具 --------------------------------------------------------------

    /**
     * modelCalls 的两个全文字段是 transient：落盘只留每轮的元信息。
     * 同一段正文在文件里已有两份真值（content 投影 + text part），第三份没有任何消费者，
     * 却把每条消息的体积抬高约六分之一，而历史文件超上限会按最早的 1/4 条裁剪。
     */
    @Test
    public void modelCallFullTextIsNotPersistedButMetadataIs() {
        ChatMessage msg = ChatMessage.createAIMessage("m9", "旧", 1000L);
        msg.turnId = "T9";
        msg.roundSnapshots = new ArrayList<>();
        ChatMessage.RoundSnapshot r1 =
                new ChatMessage.RoundSnapshot(1, "这轮的唯一正文串ZQX", "这轮的唯一思考串KFC", 11L);
        r1.toolCallIds = new ArrayList<>(Arrays.asList("call_1"));
        msg.roundSnapshots.add(r1);

        AssistantTurn turn = AssistantTurn.fromMessage(msg);
        // 内存里照旧拿得到全文（渲染/调试要用）
        assertEquals("这轮的唯一正文串ZQX", turn.modelCalls.get(0).content);
        assertEquals("这轮的唯一思考串KFC", turn.modelCalls.get(0).reasoning);

        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        String json = gson.toJson(turn);
        // 整个 turn 的 JSON 里，正文/思考各只允许出现一次（text part / reasoning part），
        // modelCalls 再抄一份就是第三份落盘重复
        assertEquals(1, countOccurrences(json, "这轮的唯一正文串ZQX"));
        assertEquals(1, countOccurrences(json, "这轮的唯一思考串KFC"));
        assertTrue("元信息仍要落盘", json.contains("\"stopReason\"") && json.contains("\"round\":1"));

        AssistantTurn back = gson.fromJson(json, AssistantTurn.class);
        assertEquals(1, back.modelCalls.size());
        assertEquals(1, back.modelCalls.get(0).round);
        assertEquals(Arrays.asList("call_1"), back.modelCalls.get(0).toolCallIds);
        assertNull("读回来的 ModelCall 不带全文", back.modelCalls.get(0).content);
        // 全文没落盘也不丢：fromMessage 从 roundSnapshots 重建时照样填回内存
        assertEquals("这轮的唯一正文串ZQX",
                AssistantTurn.fromMessage(msg).modelCalls.get(0).content);
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static JSONObject props(String... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    private static ComponentData tool(String id, String name, String result) {
        JSONObject p = new JSONObject();
        try {
            p.put("toolCallId", id);
            p.put("toolName", name);
            p.put("status", "success");
            if (result != null) p.put("result", result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return ComponentData.of("tool_call", p);
    }

    private static List<String> types(AssistantTurn t) {
        List<String> out = new ArrayList<>();
        for (AssistantTurn.Part p : t.parts) out.add(p.type);
        return out;
    }

    private static List<String> ids(AssistantTurn t) {
        List<String> out = new ArrayList<>();
        for (AssistantTurn.Part p : t.parts) out.add(p.id);
        return out;
    }

    /** 旧数据：没有逐轮快照、没有落盘 turn，但有轮次边界 + 工具组件（legacy 渲染的输入）。 */
    private static ChatMessage legacyTwoToolMsg() {
        String content = "开头分析\n\n中间结论\n\n最终回答";
        ChatMessage msg = ChatMessage.createAIMessage("m1", content, 1000L);
        msg.turnId = "T1";
        // 第 1 个工具挂在 "开头分析\n\n" 之后，第 2 个挂在 "中间结论\n\n" 之后
        msg.contentRoundBounds = new ArrayList<>(Arrays.asList(6, 12));
        msg.contentRoundToolIds = new ArrayList<>(Arrays.asList("call_1", "call_2"));
        msg.thinkingRounds = new ArrayList<>(Arrays.asList("想1", "想2", "想3"));
        msg.components = new ArrayList<>(Arrays.asList(
                tool("call_1", "查天气", "晴"),
                tool("call_2", "算温度", null)));
        RoundBounds.normalize(msg); // 模拟加载出口：先归一边界，再归一 turn
        return msg;
    }

    // ---- 旧数据补齐：顺序必须等于 legacy 的"思考→正文→工具→下一轮" ----------

    @Test
    public void legacyDataGetsPartsInRoundOrderNotAppendedAtTail() {
        ChatMessage msg = legacyTwoToolMsg();
        assertNull(msg.assistantTurn);

        assertTrue(AssistantTurnRestore.restore(msg));
        AssistantTurn turn = msg.assistantTurn;
        assertNotNull(turn);
        assertTrue("切片口径的 turn 不回写正文", turn.textPartsAreSlices);

        assertEquals(Arrays.asList(
                AssistantTurn.Part.TYPE_REASONING,
                AssistantTurn.Part.TYPE_TEXT,
                AssistantTurn.Part.TYPE_TOOL_CALL,
                AssistantTurn.Part.TYPE_TOOL_RESULT,
                AssistantTurn.Part.TYPE_REASONING,
                AssistantTurn.Part.TYPE_TEXT,
                AssistantTurn.Part.TYPE_TOOL_CALL,
                AssistantTurn.Part.TYPE_REASONING,
                AssistantTurn.Part.TYPE_TEXT), types(turn));

        // 工具卡在对的那一轮后面，且拿的是自己那份 props
        List<AssistantTurn.Part> calls = new ArrayList<>();
        for (AssistantTurn.Part p : turn.parts) {
            if (AssistantTurn.Part.TYPE_TOOL_CALL.equals(p.type)) calls.add(p);
        }
        assertEquals(2, calls.size());
        assertEquals("查天气", calls.get(0).toolName);
        assertEquals("call_1", calls.get(0).toolCallId);
        assertEquals("算温度", calls.get(1).toolName);
        assertEquals(2, turn.parts.indexOf(calls.get(0)));
        assertEquals(6, turn.parts.indexOf(calls.get(1)));
        assertEquals("想1", turn.parts.get(0).text);
        assertEquals("最终回答", turn.parts.get(turn.parts.size() - 1).text);
    }

    @Test
    public void restoreMatchesTheLiveProducerExactly() {
        ChatMessage msg = legacyTwoToolMsg();
        AssistantTurnRestore.restore(msg);
        // 实时提交走的就是 fromMessage：恢复结果必须与它逐 part 同 id 同序
        assertEquals(ids(AssistantTurn.fromMessage(msg)), ids(msg.assistantTurn));
        assertEquals(types(AssistantTurn.fromMessage(msg)), types(msg.assistantTurn));
    }

    @Test
    public void restoreIsIdempotentAndNeverRewritesLegacyContent() {
        ChatMessage msg = legacyTwoToolMsg();
        String contentBefore = msg.content;
        List<Integer> boundsBefore = new ArrayList<>(msg.contentRoundBounds);

        AssistantTurnRestore.restore(msg);
        List<String> firstIds = ids(msg.assistantTurn);
        // 再恢复一次（重开历史）+ 渲染期投影：正文/边界都不能动
        AssistantTurnRestore.restore(msg);
        msg.assistantTurn.applyToMessage(msg);

        assertEquals(firstIds, ids(msg.assistantTurn));
        assertEquals(contentBefore, msg.content);
        assertEquals(boundsBefore, msg.contentRoundBounds);
    }

    /** 边界空白不规整时，把切片 turn 投影回 content 会凭空加空行——必须原样跳过。 */
    @Test
    public void unevenSliceWhitespaceIsPreserved() {
        ChatMessage msg = ChatMessage.createAIMessage("m2", "开头\n\n中间\n\n结尾", 1000L);
        msg.turnId = "T2";
        // 第 2 个边界落在 "中间" 之后（不含分隔符），朴素投影会再插一次 \n\n
        msg.contentRoundBounds = new ArrayList<>(Arrays.asList(4, 6));
        msg.contentRoundToolIds = new ArrayList<>(Arrays.asList("c1", "c2"));
        msg.components = new ArrayList<>(Arrays.asList(tool("c1", "工具1", null),
                tool("c2", "工具2", null)));
        RoundBounds.normalize(msg);
        String contentBefore = msg.content;

        AssistantTurnRestore.restore(msg);
        msg.assistantTurn.applyToMessage(msg);

        assertEquals(contentBefore, msg.content);
    }

    // ---- 无 toolCallId 的极旧数据：按序归位，不再掉到末尾 -------------------

    @Test
    public void toolsWithoutCallIdStayInTheirRound() {
        ChatMessage msg = ChatMessage.createAIMessage("m3", "第一段\n\n第二段\n\n第三段", 1000L);
        msg.turnId = "T3";
        msg.contentRoundBounds = new ArrayList<>(Arrays.asList(6, 12));
        msg.contentRoundToolIds = new ArrayList<>(Arrays.asList("", "")); // 早于 id 落库
        msg.components = new ArrayList<>(Arrays.asList(
                tool("", "先跑的工具", "结果A"), tool("", "后跑的工具", null)));
        RoundBounds.normalize(msg);

        AssistantTurnRestore.restore(msg);
        List<String> types = types(msg.assistantTurn);
        // text, tool_call(+result), text, tool_call, text —— 工具绝不整体沉底
        assertEquals(Arrays.asList(
                AssistantTurn.Part.TYPE_TEXT,
                AssistantTurn.Part.TYPE_TOOL_CALL,
                AssistantTurn.Part.TYPE_TOOL_RESULT,
                AssistantTurn.Part.TYPE_TEXT,
                AssistantTurn.Part.TYPE_TOOL_CALL,
                AssistantTurn.Part.TYPE_TEXT), types);
        List<AssistantTurn.Part> calls = new ArrayList<>();
        for (AssistantTurn.Part p : msg.assistantTurn.parts) {
            if (AssistantTurn.Part.TYPE_TOOL_CALL.equals(p.type)) calls.add(p);
        }
        assertEquals("先跑的工具", calls.get(0).toolName);
        assertEquals("后跑的工具", calls.get(1).toolName);
    }

    /** 边界比工具少时，多出来的工具仍要有地方渲染（末尾兜底），但时间戳不能取构建时刻。 */
    @Test
    public void leftoverToolsAreAppendedWithMessageTimestampNotNow() {
        ChatMessage msg = ChatMessage.createAIMessage("m4", "正文", 2000L);
        msg.turnId = "T4";
        msg.components = new ArrayList<>(Arrays.asList(tool("c9", "无边界工具", null)));

        AssistantTurnRestore.restore(msg);
        AssistantTurn.Part tail = msg.assistantTurn.parts.get(1);
        assertEquals(AssistantTurn.Part.TYPE_TOOL_CALL, tail.type);
        assertEquals("legacy:c9", tail.id);
        assertEquals(2000L, tail.timestamp);
    }

    // ---- 护栏 --------------------------------------------------------------

    @Test
    public void messageWithNoRenderableStructureIsNotBackfilled() {
        ChatMessage msg = ChatMessage.createAIMessage("m5", "   ", 1000L);
        assertFalse(AssistantTurnRestore.restore(msg));
        assertNull("空正文不能补出 turn，否则 parts 会接管并渲出空气泡", msg.assistantTurn);
    }

    @Test
    public void storedTurnWithNonDerivablePartsIsKept() {
        ChatMessage msg = ChatMessage.createAIMessage("m6", "正文", 1000L);
        AssistantTurn stored = new AssistantTurn();
        stored.parts.add(AssistantTurn.Part.text("t1", "正文", 1L));
        stored.parts.add(AssistantTurn.Part.approval("a1", "call_x", "存储权限", "允许吗",
                AssistantTurn.Part.APPROVAL_PENDING, 1L));
        msg.assistantTurn = stored;

        assertFalse(AssistantTurnRestore.restore(msg));
        assertSame(stored, msg.assistantTurn);
    }

    @Test
    public void nonCompletedMessagesAreLeftAlone() {
        ChatMessage msg = ChatMessage.createAIMessage("m7", "正文", 1000L);
        msg.status = ChatMessage.MessageStatus.GENERATING;
        assertFalse(AssistantTurnRestore.restore(msg));
        assertNull(msg.assistantTurn);
    }

    // ---- 新数据：逐轮快照仍是权威，投影行为不变 -----------------------------

    @Test
    public void snapshotDataStillProjectsContentFromParts() {
        ChatMessage msg = ChatMessage.createAIMessage("m8", "旧投影", 1000L);
        msg.turnId = "T8";
        msg.roundSnapshots = new ArrayList<>();
        ChatMessage.RoundSnapshot r1 = new ChatMessage.RoundSnapshot(1, "第一轮正文", "轮1思考", 11L);
        r1.toolCallIds = new ArrayList<>(Arrays.asList("call_1"));
        msg.roundSnapshots.add(r1);
        msg.roundSnapshots.add(new ChatMessage.RoundSnapshot(2, "第二轮最终", "", 12L));
        msg.components = new ArrayList<>(Arrays.asList(tool("call_1", "查天气", "晴")));

        assertTrue(AssistantTurnRestore.restore(msg));
        assertFalse(msg.assistantTurn.textPartsAreSlices);
        assertEquals("第一轮正文\n\n第二轮最终", msg.content);
        assertEquals(1, msg.contentRoundBounds.size());
        assertEquals("call_1", msg.contentRoundToolIds.get(0));

        String after = msg.content;
        AssistantTurnRestore.restore(msg); // 二次恢复不得再改正文
        assertEquals(after, msg.content);
    }

    // ---- 落盘 parts 的顺序本身要能在 JSON 往返中站住 ------------------------

    @Test
    public void persistedPartOrderSurvivesGsonRoundTrip() {
        AssistantTurn turn = AssistantTurn.fromMessage(legacyTwoToolMsg());
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        String json = gson.toJson(turn);
        AssistantTurn back = gson.fromJson(json, new TypeToken<AssistantTurn>() {}.getType());

        assertEquals(ids(turn), ids(back));
        assertEquals(types(turn), types(back));
        assertTrue(back.textPartsAreSlices);
        // transient 的 data 引用不落库，恢复后必须由 components 重新挂上
        for (AssistantTurn.Part p : back.parts) {
            if (AssistantTurn.Part.TYPE_TOOL_CALL.equals(p.type)) {
                assertNotNull(p.toolCallId);
            }
        }
    }
}
