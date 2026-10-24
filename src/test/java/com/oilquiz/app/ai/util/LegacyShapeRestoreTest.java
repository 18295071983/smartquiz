package com.oilquiz.app.ai.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 真旧格式（2026-10-21 parts 模型之前）落盘数据的恢复形状回归（2026-10-24）。
 *
 * <p>夹具不是凭空写的：字段取自真机那条会话里 AI 消息的实际落盘键集
 * （{@code content / contentRoundBounds / contentRoundToolIds / thinkingRounds / components /
 * turnId / subId / status / type}），并刻意<b>不写</b> {@code assistantTurn} 与
 * {@code roundSnapshots}——那正是老包不会有的两个字段（每个用例都先断言落盘 JSON 里
 * 确实没有 {@code assistantTurn}，防止夹具被后来的代码"污染"成新数据）。</p>
 *
 * <p>覆盖的都是真机见过的形状：工具夹在正文中间、多个工具挂在同一偏移（{@code bounds=[0,0]}，
 * 工具在正文之前）、组件没有 {@code toolCallId}、历史里的 id 指向已不存在的组件、只有正文、正文为空。
 * 断言一律走<b>真加载出口</b>（{@code saveAIChatHistory → loadAIChatHistory} 真文件往返），
 * 不是直接调函数——这样"挂钩在不在链上"和"形状对不对"一起被钉住。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class LegacyShapeRestoreTest {

    private Context context;
    private ChatHistoryManager manager;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        manager = new ChatHistoryManager(context);
        manager.clearAIChatHistory();
        manager.clearAllConversationSessions();
    }

    // ---- 夹具 --------------------------------------------------------------

    private static ComponentData tool(String callId, String name, String status, String result) {
        try {
            JSONObject p = new JSONObject();
            p.put("toolCallId", callId);
            p.put("toolName", name);
            p.put("status", status);
            if (result != null) p.put("result", result);
            return ComponentData.of("tool_call", p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ComponentData product(String id, String type, String producerCallId) {
        try {
            JSONObject p = new JSONObject();
            p.put("title", "产物" + id);
            if (producerCallId != null) p.put("producerToolCallId", producerCallId);
            ComponentData c = ComponentData.of(type, p);
            c.id = id;
            return c;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 一条真旧格式 AI 消息：有边界、无 assistantTurn、无 roundSnapshots。 */
    private ChatMessage legacyAi(String id, String content, List<Integer> bounds,
                                 List<String> toolIds, List<String> thinks,
                                 List<ComponentData> comps) {
        ChatMessage m = ChatMessage.createAIMessage(id, content, 1730000000000L);
        m.turnId = "T-" + id;
        m.subId = id + "-S1";
        m.contentRoundBounds = bounds == null ? null : new ArrayList<>(bounds);
        m.contentRoundToolIds = toolIds == null ? null : new ArrayList<>(toolIds);
        if (thinks != null) m.thinkingRounds = new ArrayList<>(thinks);
        if (comps != null) m.components = new ArrayList<>(comps);
        return m;
    }

    /** 真文件往返：落盘（并自查确实是旧形状）→ 走加载出口读回 → 按 id 取。 */
    private ChatMessage roundTrip(String id, ChatMessage... msgs) {
        manager.saveAIChatHistory(new ArrayList<>(Arrays.asList(msgs)));
        try {
            String raw = new String(Files.readAllBytes(
                    new File(context.getFilesDir(), "ai_chat_history.json").toPath()),
                    StandardCharsets.UTF_8);
            assertFalse("夹具落盘后不应带 assistantTurn（那就不是旧数据了）",
                    raw.contains("\"assistantTurn\""));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        for (ChatMessage m : manager.loadAIChatHistory()) {
            if (m != null && id.equals(m.id)) return m;
        }
        throw new AssertionError("历史文件里找不到消息 " + id);
    }

    private static List<String> types(ChatMessage m) {
        List<String> out = new ArrayList<>();
        if (m == null || m.assistantTurn == null) return out;
        for (AssistantTurn.Part p : m.assistantTurn.parts) {
            out.add(p.type + (p.toolName == null || p.toolName.isEmpty() ? "" : ":" + p.toolName));
        }
        return out;
    }

    private static List<String> ids(ChatMessage m) {
        List<String> out = new ArrayList<>();
        if (m == null || m.assistantTurn == null) return out;
        for (AssistantTurn.Part p : m.assistantTurn.parts) out.add(p.id);
        return out;
    }

    private static List<AssistantTurn.Part> partsOfType(ChatMessage m, String type) {
        List<AssistantTurn.Part> out = new ArrayList<>();
        if (m == null || m.assistantTurn == null) return out;
        for (AssistantTurn.Part p : m.assistantTurn.parts) {
            if (type.equals(p.type)) out.add(p);
        }
        return out;
    }

    // ---- 形状 1：工具夹在正文中间（真机最常见） ----------------------------

    @Test
    public void legacyToolsInterleavedInRoundsRestoreInPlace() {
        String content = "开头分析\n\n中间结论\n\n最终回答";
        ChatMessage m = legacyAi("a-shape1", content, Arrays.asList(6, 12),
                Arrays.asList("call_1", "call_2"),
                Arrays.asList("想1", "想2", "想3"),
                Arrays.asList(tool("call_1", "查天气", "success", "晴"),
                        tool("call_2", "算温度", "success", null),
                        product("cmp1", "info_card", "call_1")));

        ChatMessage r = roundTrip("a-shape1", m);

        assertEquals(Arrays.asList(
                "reasoning", "text", "tool_call:查天气", "tool_result:查天气",
                "reasoning", "text", "tool_call:算温度",
                "reasoning", "text", "data"), types(r));
        assertEquals("正文不能被恢复改写", content, r.content);
        assertEquals("边界不能被恢复改写", Arrays.asList(6, 12), r.contentRoundBounds);
        assertTrue("切片口径的 turn 不回写正文", r.assistantTurn.textPartsAreSlices);

        List<AssistantTurn.Part> calls = partsOfType(r, AssistantTurn.Part.TYPE_TOOL_CALL);
        assertEquals(2, calls.size());
        assertEquals("查天气", calls.get(0).toolName);
        assertEquals("success", calls.get(1).status);
        // 结果挂在 tool_result part 上（归并进工具卡是渲染期 fromMessageForParts 的 groupTools 做的）
        List<AssistantTurn.Part> results = partsOfType(r, AssistantTurn.Part.TYPE_TOOL_RESULT);
        assertEquals(1, results.size());
        assertEquals("晴", results.get(0).result);
        // 时间戳取消息时间，不能是构建时刻（否则每次重开 part 时间都在变）
        assertEquals(1730000000000L, calls.get(1).timestamp);
        // 产物卡按 fromMessage 口径追加在末尾，归到工具卡下发生在渲染期的 fromMessageForParts
        assertEquals(1, partsOfType(r, AssistantTurn.Part.TYPE_DATA).size());
    }

    // ---- 形状 2：多个工具挂在同一偏移（真机见过 bounds=[0,0]） --------------

    @Test
    public void legacyToolsBeforeBodyStayAheadOfText() {
        String content = "正文在前面的回答";
        ChatMessage m = legacyAi("a-shape2", content, Arrays.asList(0, 0),
                Arrays.asList("c1", "c2"), null,
                Arrays.asList(tool("c1", "网络搜索", "success", null),
                        tool("c2", "组件渲染", "success", null)));

        ChatMessage r = roundTrip("a-shape2", m);

        assertEquals(Arrays.asList("tool_call:网络搜索", "tool_call:组件渲染", "text"), types(r));
        assertEquals(content, r.content);
        assertEquals(Arrays.asList(0, 0), r.contentRoundBounds);
    }

    // ---- 形状 3：组件没有 toolCallId（早于 id 落库） ------------------------

    @Test
    public void legacyToolsWithoutCallIdPairByOrderNotByTail() {
        String content = "第一段\n\n第二段\n\n第三段";
        ChatMessage m = legacyAi("a-shape3", content, Arrays.asList(5, 10),
                Arrays.asList("", ""), null,
                Arrays.asList(tool("", "先跑的工具", "success", "结果A"),
                        tool("", "后跑的工具", "success", null)));

        ChatMessage r = roundTrip("a-shape3", m);

        assertEquals(Arrays.asList("text", "tool_call:先跑的工具", "tool_result:先跑的工具",
                "text", "tool_call:后跑的工具", "text"), types(r));
        assertEquals(content, r.content);
    }

    // ---- 形状 4：历史里的 id 指向已不存在的组件 -----------------------------

    /**
     * 坏数据留下的"失效槽"：id 写进了历史、组件却已经没了。补一个未认领的组件 id 顶上，
     * 既不会渲一张没有名字的空白工具行，也不会让真组件被补到正文末尾去。
     */
    @Test
    public void staleToolIdSlotTakesOverAnUnclaimedComponent() {
        String content = "正文AAA\n\n正文BBB";
        ChatMessage m = legacyAi("a-shape4", content, Arrays.asList(7, 7),
                Arrays.asList("gone_1", "gone_2"), null,
                Collections.singletonList(tool("live_1", "在的工具", "success", null)));

        ChatMessage r = roundTrip("a-shape4", m);

        assertEquals(Arrays.asList("text", "tool_call:在的工具", "text"), types(r));
        assertEquals(Collections.singletonList("live_1"), r.contentRoundToolIds);
        assertEquals("不许留空白工具行", 1,
                partsOfType(r, AssistantTurn.Part.TYPE_TOOL_CALL).size());
        assertEquals(content, r.content);
    }

    // ---- 形状 5/6：只有正文、正文为空 --------------------------------------

    @Test
    public void legacyPlainTextRestoresSingleTextPart() {
        ChatMessage m = legacyAi("a-shape5", "只有一段普通回答", null, null, null, null);

        ChatMessage r = roundTrip("a-shape5", m);

        assertEquals(Collections.singletonList("text"), types(r));
        assertEquals("只有一段普通回答", r.content);
    }

    /** 重建不出 part 的消息不补 turn，否则 parts 闸门会接管并渲出空气泡。 */
    @Test
    public void legacyBlankMessageGetsNoTurnAndStaysOnLegacyPath() {
        ChatMessage blank = legacyAi("a-blank", "   ", null, null, null, null);
        ChatMessage good = legacyAi("a-ok", "有正文", null, null, null, null);

        manager.saveAIChatHistory(new ArrayList<>(Arrays.asList(blank, good)));
        ChatMessage rBlank = null;
        ChatMessage rGood = null;
        for (ChatMessage m : manager.loadAIChatHistory()) {
            if ("a-blank".equals(m.id)) rBlank = m;
            if ("a-ok".equals(m.id)) rGood = m;
        }
        assertNotNull(rGood);
        assertNotNull(rBlank);
        assertNull("空正文消息不能补上 canonical turn", rBlank.assistantTurn);
        assertNotNull("同一条历史里的正常消息照常补", rGood.assistantTurn);
    }

    // ---- 顺序只有一个来源：恢复 == 实时；且反复读写不漂 --------------------

    @Test
    public void restoredOrderEqualsLiveProducerAndSurvivesSecondRoundTrip() {
        String content = "开头分析\n\n中间结论\n\n最终回答";
        List<Integer> bounds = Arrays.asList(6, 12);
        List<String> idsIn = Arrays.asList("call_1", "call_2");
        List<String> thinks = Arrays.asList("想1", "想2", "想3");
        List<ComponentData> comps = Arrays.asList(tool("call_1", "查天气", "success", "晴"),
                tool("call_2", "算温度", "success", null));

        ChatMessage once = roundTrip("a-shape6",
                legacyAi("a-shape6", content, bounds, idsIn, thinks, comps));
        List<String> firstIds = ids(once);
        List<String> firstTypes = types(once);

        // 第二次"打开→保存→打开"：完全一致，证明每重开一次不会再漂一遍
        manager.saveAIChatHistory(Collections.singletonList(once));
        ChatMessage twice = null;
        for (ChatMessage x : manager.loadAIChatHistory()) {
            if ("a-shape6".equals(x.id)) twice = x;
        }
        assertNotNull(twice);
        assertEquals(firstIds, ids(twice));
        assertEquals(firstTypes, types(twice));
        assertEquals(once.content, twice.content);

        // 恢复出来的顺序必须等于实时生产者对同一条消息的输出——顺序没有第二个来源
        List<String> liveIds = new ArrayList<>();
        for (AssistantTurn.Part p :
                AssistantTurn.fromMessage(legacyAi("a-shape6", content, bounds, idsIn, thinks, comps)).parts) {
            liveIds.add(p.id);
        }
        assertEquals(liveIds, firstIds);
    }
}
