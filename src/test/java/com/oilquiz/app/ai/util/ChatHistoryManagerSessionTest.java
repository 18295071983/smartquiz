package com.oilquiz.app.ai.util;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.chat.ActivityTimeline;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 会话持久化的两个真实缺陷的回归用例（2026-10-19 真机查"每次重进多一条历史会话"时定位）。
 *
 * <p>背景：{@code ai_chat_history.json} 是纯消息数组、不存会话归属，恢复后
 * {@code AIChatActivity#currentSessionId} 为 null，之后任何一次保存都会另起一条会话文件；
 * Activity 侧靠"最后一跳消息 id 相同"把这份历史认领回原会话，而认领要读会话文件的最后一条消息。
 * 这条链上有两个坑，都在本用例里钉住：
 * <ol>
 *   <li><b>{@code null.json}</b>：新建会话且要合并单文件头部时走 {@code writeSessionWithHead}，
 *       这条路不经过唯一 mint id 的 {@code saveConversationSession}，
 *       于是文件名成了字面量 {@code null.json}，返回的 {@code session.id} 也是 null，
 *       认领拿不到 id，所有会话挤进同一个文件。</li>
 *   <li><b>会话文件只持久化 USER / AI 两类消息</b>（保存时按 {@code isUserMessage()||isAIMessage()} 过滤），
 *       而内存窗口末尾常常是一条 SYSTEM（"✓ 模型加载完成"）消息；
 *       分段读会话文件时另一个坑是 {@code messages} 之后还有 createdAt/updatedAt/messageCount，
 *       读到就 break 会让 {@code endObject()} 抛 IllegalStateException。
 *       用例 3 钉住"按页读会话文件能拿到指定的那一条"。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
public class ChatHistoryManagerSessionTest {

    /** ChatHistoryManager.CONVERSATIONS_DIR 是私有常量，这里按同一路径取值 */
    private static final String CONVERSATIONS_DIR = "conversations";

    private Context context;
    private ChatHistoryManager manager;
    private File conversationsDir;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        manager = new ChatHistoryManager(context);
        conversationsDir = new File(context.getFilesDir(), CONVERSATIONS_DIR);
        // 单测之间不能互相看见残留会话，否则 listConversationSessions 会串台
        manager.clearAllConversationSessions();
    }

    private ChatMessage user(String id, String content) {
        return ChatMessage.createUserMessage(id, content, System.currentTimeMillis());
    }

    private ChatMessage ai(String id, String content) {
        return ChatMessage.createAIMessage(id, content, System.currentTimeMillis());
    }

    /**
     * 缺陷 1：合并单文件头部的新会话必须拿到一个真 uuid，文件名不能是字面量 "null.json"。
     */
    @Test
    public void mergeSingleFileHeadMintsSessionIdInsteadOfNullFileName() {
        List<ChatMessage> window = new ArrayList<>(Arrays.asList(
                user("u-1", "今天的天气怎么样"),
                ai("a-1", "正在为你查询")));
        // 单文件历史是 headSource 的来源，先把它写出来
        manager.saveAIChatHistory(window);

        ConversationSession saved = manager.saveCurrentChatAsSession(window, null, 1, true);

        assertNotNull("合并头部后应返回保存的会话", saved);
        assertNotNull("session.id 必须被 mint：否则文件名是 null.json、调用方也无法认领", saved.id);
        assertFalse("不能再出现字面量 null.json",
                new File(conversationsDir, "null.json").exists());
        assertTrue("会话应以自己的 id 落盘：" + saved.id,
                new File(conversationsDir, saved.id + ".json").exists());
    }

    /**
     * 缺陷 2 的口径依据：会话文件里只有 USER / AI，所以"窗口最后一条消息"不等于"会话最后一条消息"。
     * Activity 侧认领必须比较窗口里最后一条 <b>USER/AI</b> 消息的 id。
     */
    @Test
    public void sessionFilePersistsOnlyUserAndAiMessages() {
        ChatMessage sys = ChatMessage.createSystemMessage("✓ 模型加载完成");
        List<ChatMessage> window = new ArrayList<>(Arrays.asList(
                user("u-2", "你好"),
                ai("a-2", "你好呀，有什么可以帮你？"),
                sys));

        ConversationSession saved = manager.saveCurrentChatAsSession(window, null);

        assertNotNull(saved);
        int total = manager.countConversationMessages(saved.id);
        assertEquals("SYSTEM 消息不落会话文件", 2, total);

        // 窗口末尾是 SYSTEM，会话末尾是 AI —— 用"窗口最后一条"去比必然对不上
        ChatMessage lastInWindow = window.get(window.size() - 1);
        assertTrue("用例前提：窗口最后一条不是 USER/AI",
                !lastInWindow.isUserMessage() && !lastInWindow.isAIMessage());

        List<ChatMessage> tail = manager.loadConversationSessionMessages(saved.id, total - 1, 1);
        assertEquals(1, tail.size());
        assertTrue("会话文件的最后一条应是 AI 消息", tail.get(0).isAIMessage());
        assertEquals("a-2", tail.get(0).id);
    }

    /**
     * 缺陷 2 的读取侧：会话对象的 {@code messages} 之后还有尾部成员，
     * 分段读取必须能正常读到任意一页（而不是靠异常被 catch 之后"碰巧"返回）。
     */
    @Test
    public void pagedSessionReadReturnsRequestedPage() {
        List<ChatMessage> window = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            window.add(user("u-p" + i, "问题" + i));
            window.add(ai("a-p" + i, "第 " + i + " 答"));
        }
        ConversationSession saved = manager.saveCurrentChatAsSession(window, null);
        assertNotNull(saved);

        int total = manager.countConversationMessages(saved.id);
        assertEquals(10, total);

        List<ChatMessage> firstPage = manager.loadConversationSessionMessages(saved.id, 0, 3);
        assertEquals(3, firstPage.size());
        assertEquals("u-p0", firstPage.get(0).id);
        assertEquals("u-p1", firstPage.get(2).id);

        List<ChatMessage> lastOnly = manager.loadConversationSessionMessages(saved.id, total - 1, 1);
        assertEquals(1, lastOnly.size());
        assertEquals("a-p4", lastOnly.get(0).id);
    }

    /**
     * 认领得以成立的前提：同一个会话被再次保存时是<b>更新</b>而不是再新建一条。
     */
    @Test
    public void savingWithExistingSessionIdUpdatesThatSession() {
        List<ChatMessage> window = new ArrayList<>(Arrays.asList(
                user("u-3", "第一条"),
                ai("a-3", "回复")));
        ConversationSession created = manager.saveCurrentChatAsSession(window, null);
        assertNotNull(created);
        int before = conversationFileCount();
        assertTrue(before >= 1);

        window.add(ai("a-3b", "追加一条"));
        ConversationSession again = manager.saveCurrentChatAsSession(window, created.id, 0, false);

        assertNotNull(again);
        assertEquals("带 id 保存应复用同一条会话", created.id, again.id);
        assertEquals("不该多出新的会话文件", before, conversationFileCount());
        assertEquals(3, manager.countConversationMessages(created.id));
    }

    /**
     * 2026-10-21 每轮活动区之后：保存深拷贝必须保留轮次/组件数据，但不能把
     * toolSectionExpandedIds 这类 transient 展开状态带进持久化。
     */
    @Test
    public void saveCopyKeepsRoundDataAndDropsTransientExpansionState() throws Exception {
        ChatMessage msg = ChatMessage.createAIMessage("a-round", "回答内容", System.currentTimeMillis());
        msg.turnId = "T7";
        msg.contentRoundBounds = new ArrayList<>(Arrays.asList(0, 4));
        msg.contentRoundToolIds = new ArrayList<>(Arrays.asList("T7-F1", "T7-F2"));
        msg.thinkingRounds = new ArrayList<>(Arrays.asList("t0", "t1"));
        msg.thinkingRoundIds = new ArrayList<>(Arrays.asList("T7-K1", "T7-K2"));
        msg.components = new ArrayList<>();
        JSONObject props = new JSONObject();
        props.put("title", "运行状态");
        msg.components.add(ComponentData.of("info_card", props));
        msg.toolSectionExpandedIds = new HashSet<>(Collections.singletonList("T7-F1"));

        List<ChatMessage> copy = ChatHistoryManager.deepCopyForSave(
                Collections.singletonList(msg));
        ChatMessage saved = copy.get(0);

        assertEquals("T7", saved.turnId);
        assertEquals(Arrays.asList(0, 4), saved.contentRoundBounds);
        assertEquals(Arrays.asList("T7-F1", "T7-F2"), saved.contentRoundToolIds);
        assertEquals(2, saved.thinkingRounds.size());
        assertEquals(1, saved.components.size());
        assertEquals("info_card", saved.components.get(0).type);
        assertNull("展开状态是 UI transient，不能持久化", saved.toolSectionExpandedIds);
    }

    /**
     * 2026-10-21：保存/恢复必须保留轮次身份，恢复后 ActivityTimeline 仍按 toolCallId 配对，
     * 不能退回组件顺序（实时与重进两种形态不一致的根因之一）。
     */
    @Test
    public void savedRoundToolIdsSurviveReloadAndStillPairTools() throws Exception {
        ChatMessage msg = ChatMessage.createAIMessage("a-pair", "正文内容", System.currentTimeMillis());
        msg.turnId = "T9";
        msg.contentRoundBounds = new ArrayList<>(Arrays.asList(0, 0));
        msg.contentRoundToolIds = new ArrayList<>(Arrays.asList("b", "a"));
        msg.components = new ArrayList<>();
        msg.components.add(tool("a", "network_search"));
        msg.components.add(tool("b", "ui_component"));

        manager.saveAIChatHistory(Collections.singletonList(msg));
        List<ChatMessage> loaded = manager.loadAIChatHistory();
        ChatMessage restored = null;
        for (ChatMessage m : loaded) {
            if (m != null && "a-pair".equals(m.id)) restored = m;
        }
        assertNotNull(restored);
        assertEquals(Arrays.asList(0, 0), restored.contentRoundBounds);
        assertEquals(Arrays.asList("b", "a"), restored.contentRoundToolIds);

        List<String> toolIds = new ArrayList<>();
        for (ActivityTimeline.Item it : ActivityTimeline.build(restored).items) {
            if (it.kind == ActivityTimeline.Kind.TOOL) toolIds.add(it.toolCallId);
        }
        assertEquals("恢复后仍应按保存的 toolCallId 配对", Arrays.asList("b", "a"), toolIds);
    }

    // ---- 2026-10-24：四个加载出口的 canonical turn 归一（真文件链路，不是纯函数推断） ----

    private static final String LEGACY_CONTENT = "开头分析\n\n中间结论\n\n最终回答";

    private static final List<String> LEGACY_PART_ORDER = Arrays.asList(
            AssistantTurn.Part.TYPE_REASONING,
            AssistantTurn.Part.TYPE_TEXT,
            AssistantTurn.Part.TYPE_TOOL_CALL,
            AssistantTurn.Part.TYPE_TOOL_RESULT,
            AssistantTurn.Part.TYPE_REASONING,
            AssistantTurn.Part.TYPE_TEXT,
            AssistantTurn.Part.TYPE_TOOL_CALL,
            AssistantTurn.Part.TYPE_REASONING,
            AssistantTurn.Part.TYPE_TEXT);

    /**
     * 旧数据形态：有轮次边界 + 工具组件，但<b>没有</b>逐轮快照、也<b>没有</b>落盘 assistantTurn。
     * 这正是 2026-10-21 之前存下来的会话。
     */
    private ChatMessage legacyToolRoundMsg(String id) throws Exception {
        ChatMessage msg = ChatMessage.createAIMessage(id, LEGACY_CONTENT, System.currentTimeMillis());
        msg.turnId = "T-" + id;
        msg.contentRoundBounds = new ArrayList<>(Arrays.asList(6, 12));
        msg.contentRoundToolIds = new ArrayList<>(Arrays.asList("call_1", "call_2"));
        msg.thinkingRounds = new ArrayList<>(Arrays.asList("想1", "想2", "想3"));
        JSONObject p1 = new JSONObject();
        p1.put("toolCallId", "call_1");
        p1.put("toolName", "查天气");
        p1.put("status", "success");
        p1.put("result", "晴");
        msg.components = new ArrayList<>(Arrays.asList(
                ComponentData.of("tool_call", p1), tool("call_2", "算温度")));
        return msg;
    }

    private static ChatMessage byId(List<ChatMessage> list, String id) {
        for (ChatMessage m : list) {
            if (m != null && id.equals(m.id)) return m;
        }
        return null;
    }

    private static List<String> partTypes(AssistantTurn turn) {
        List<String> out = new ArrayList<>();
        for (AssistantTurn.Part p : turn.parts) out.add(p.type);
        return out;
    }

    private static String readAll(File f) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 断言一条恢复出来的消息：turn 补上了、顺序按边界归位、正文与边界一个字都没改。 */
    private void assertLegacyTurnRestored(ChatMessage restored, ChatMessage saved) {
        assertNotNull("用例前提：这条消息要能从文件里读回来", restored);
        assertNotNull("加载出口必须补上 canonical turn", restored.assistantTurn);
        assertEquals("恢复的 parts 顺序 = 思考→正文→工具→下一轮", LEGACY_PART_ORDER,
                partTypes(restored.assistantTurn));
        assertEquals("正文不能被恢复改写", saved.content, restored.content);
        assertEquals("轮次边界不能被恢复改写", saved.contentRoundBounds, restored.contentRoundBounds);
        assertTrue("切片口径的 turn 不许回写正文", restored.assistantTurn.textPartsAreSlices);
    }

    /** 挂钩 1/4：单文件 AI 历史 {@code loadAIChatHistory}。 */
    @Test
    public void legacyAiHistoryLoadBackfillsCanonicalTurn() throws Exception {
        ChatMessage msg = legacyToolRoundMsg("a-legacy-hist");
        manager.saveAIChatHistory(Collections.singletonList(msg));

        String file = readAll(new File(context.getFilesDir(), "ai_chat_history.json"));
        assertFalse("用例前提：落盘必须是「没有 assistantTurn」的旧形态",
                file.contains("\"assistantTurn\""));

        assertLegacyTurnRestored(byId(manager.loadAIChatHistory(), "a-legacy-hist"), msg);
    }

    /** 挂钩 2/4：Agent 历史 {@code loadAgentChatHistory}。 */
    @Test
    public void legacyAgentHistoryLoadBackfillsCanonicalTurn() throws Exception {
        ChatMessage msg = legacyToolRoundMsg("a-legacy-agent");
        manager.saveAgentChatHistory(Collections.singletonList(msg));

        assertFalse(readAll(new File(context.getFilesDir(), "agent_chat_history.json"))
                .contains("\"assistantTurn\""));

        assertLegacyTurnRestored(byId(manager.loadAgentChatHistory(), "a-legacy-agent"), msg);
    }

    /** 挂钩 3/4：整份会话对象 {@code loadConversationSession}（热更新轮询走的就是它）。 */
    @Test
    public void legacySessionObjectLoadBackfillsCanonicalTurn() throws Exception {
        ChatMessage msg = legacyToolRoundMsg("a-legacy-obj");
        ConversationSession saved = manager.saveCurrentChatAsSession(
                new ArrayList<>(Arrays.asList(user("u-legacy-obj", "查一下"), msg)));
        assertNotNull(saved);

        ConversationSession loaded = manager.loadConversationSession(saved.id);
        assertNotNull(loaded);
        assertLegacyTurnRestored(byId(loaded.messages, "a-legacy-obj"), msg);
    }

    /**
     * 挂钩 4/4：分段读 {@code loadConversationSessionMessages}（进会话页取首页/翻页走它）。
     * 顺带钉住"恢复出的 turn 与实时生产者逐 part 同 id"——顺序只剩一个来源这件事本身也要有回归网。
     */
    @Test
    public void legacyPagedSessionLoadBackfillsSameTurnAsLiveProducer() throws Exception {
        ChatMessage msg = legacyToolRoundMsg("a-legacy-page");
        ConversationSession saved = manager.saveCurrentChatAsSession(
                new ArrayList<>(Arrays.asList(user("u-legacy-page", "查一下"), msg)));
        assertNotNull(saved);

        List<ChatMessage> page = manager.loadConversationSessionMessages(saved.id, 0, 10);
        ChatMessage restored = byId(page, "a-legacy-page");
        assertLegacyTurnRestored(restored, msg);

        List<String> liveIds = new ArrayList<>();
        for (AssistantTurn.Part p : AssistantTurn.fromMessage(msg).parts) liveIds.add(p.id);
        List<String> restoredIds = new ArrayList<>();
        for (AssistantTurn.Part p : restored.assistantTurn.parts) restoredIds.add(p.id);
        assertEquals("恢复顺序必须等于实时 fromMessage 顺序", liveIds, restoredIds);
    }

    /**
     * 反复"打开历史 → 自动保存"不能逐次漂移：第二次读回来的正文、边界与 parts（id + 文本）
     * 必须与第一次完全一致。恢复会补一份重建的 turn 并落盘，这条钉住"补完再读"是幂等的，
     * 不会每重开一次就规范化一遍空白/挪一次工具位。
     */
    @Test
    public void reopeningLegacyHistoryTwiceDoesNotDrift() throws Exception {
        ChatMessage msg = legacyToolRoundMsg("a-legacy-twice");
        manager.saveAIChatHistory(Collections.singletonList(msg));
        List<ChatMessage> first = Collections.singletonList(
                byId(manager.loadAIChatHistory(), "a-legacy-twice"));
        assertNotNull(first.get(0));
        String content1 = first.get(0).content;
        List<Integer> bounds1 = new ArrayList<>(first.get(0).contentRoundBounds);
        List<String> parts1 = describeParts(first.get(0).assistantTurn);

        manager.saveAIChatHistory(first);
        ChatMessage second = byId(manager.loadAIChatHistory(), "a-legacy-twice");
        assertNotNull(second);
        assertEquals("第二次打开正文应一字不差", content1, second.content);
        assertEquals("第二次打开边界应一致", bounds1, second.contentRoundBounds);
        assertEquals("第二次打开 parts 应一致", parts1, describeParts(second.assistantTurn));
    }

    /** parts 的可比较快照：type + id + 文本/工具名，顺序敏感。 */
    private static List<String> describeParts(AssistantTurn turn) {
        List<String> out = new ArrayList<>();
        if (turn == null) return out;
        for (AssistantTurn.Part p : turn.parts) {
            out.add(p.type + "|" + p.id + "|" + (p.text == null ? "" : p.text)
                    + "|" + (p.toolName == null ? "" : p.toolName));
        }
        return out;
    }

    /**
     * 落盘文件里同一段正文只允许出现两次：一次在 {@code content}（兼容投影，搜索/复制用），
     * 一次在 {@code assistantTurn.parts[].text}（顺序真值）。
     * 第三次复制来自 {@code ModelCall.content}——它零消费者，却把每条消息抬高约 1/6 体积，
     * 而历史文件超上限会按最早的 1/4 条裁剪，等于把用户历史往刀下推。两个字段已标 transient，
     * 这条把它们永久钉在门外。
     */
    @Test
    public void persistedFileCarriesBodyTextOnlyTwice() throws Exception {
        String marker = "唯一正文串ZQX-用于数副本";
        ChatMessage msg = ChatMessage.createAIMessage("a-dup", "开头" + marker + "\n\n结尾", 1000L);
        msg.turnId = "TDUP";
        msg.contentRoundBounds = new ArrayList<>(Arrays.asList(2));
        msg.contentRoundToolIds = new ArrayList<>(Arrays.asList("call_1"));
        msg.components = new ArrayList<>(Arrays.asList(tool("call_1", "查天气")));
        manager.saveAIChatHistory(Collections.singletonList(msg));

        ChatMessage loaded = byId(manager.loadAIChatHistory(), "a-dup");
        assertNotNull(loaded);
        manager.saveAIChatHistory(Collections.singletonList(loaded)); // 打开过一次后再落盘

        String file = readAll(new File(context.getFilesDir(), "ai_chat_history.json"));
        int copies = 0;
        for (int i = file.indexOf(marker); i >= 0; i = file.indexOf(marker, i + 1)) copies++;
        assertEquals(marker + " 在落盘文件里应只有 content + text part 两份，实际=" + copies
                + "；文件片段=" + file.substring(0, Math.min(file.length(), 400)), 2, copies);
    }

    private static ComponentData tool(String id, String name) throws Exception {
        JSONObject props = new JSONObject();
        props.put("toolCallId", id);
        props.put("toolName", name);
        props.put("status", "done");
        return ComponentData.of("tool_call", props);
    }

    // ==================== 重复恢复：新 id + "（恢复副本）"标题（2026-10-28） ====================

    @Test
    public void restoredCopyTitlePrefixesMarkExactlyOnce() {
        assertEquals(ChatHistoryManager.RESTORED_COPY_MARK + "git clone …",
                ChatHistoryManager.restoredCopyTitle("git clone …"));
        // 副本再被删、再恢复：不叠第二个标记
        assertEquals(ChatHistoryManager.RESTORED_COPY_MARK + "X",
                ChatHistoryManager.restoredCopyTitle(ChatHistoryManager.RESTORED_COPY_MARK + "X"));
        // 原标题缺失时给个能看的兜底
        assertEquals(ChatHistoryManager.RESTORED_COPY_MARK + "未命名会话",
                ChatHistoryManager.restoredCopyTitle(null));
        assertEquals(ChatHistoryManager.RESTORED_COPY_MARK + "未命名会话",
                ChatHistoryManager.restoredCopyTitle("   "));
    }

    /**
     * 同 id 撞车的恢复：旧会话原地不动，恢复出来的副本拿新 id，标题带"（恢复副本）"。
     * 从前两条同名同内容，历史抽屉里根本分不出哪条是恢复出来的那份。
     */
    @Test
    public void restoreIntoOccupiedIdMintsCopyWithSuffixTitle() throws Exception {
        List<ChatMessage> window = new ArrayList<>(Arrays.asList(
                user("u-r1", "被删掉的会话"),
                ai("a-r1", "回复")));
        ConversationSession original = manager.saveCurrentChatAsSession(window, null);
        assertNotNull(original);
        String id = original.id;
        String title = original.title;

        assertTrue(manager.trashConversationSession(id));
        assertFalse("回收站操作后原文件应已移走", new File(conversationsDir, id + ".json").exists());

        // 同 id 又存在了（例如删之前那份已被恢复过一轮）
        try (java.io.FileWriter w = new java.io.FileWriter(new File(conversationsDir, id + ".json"))) {
            w.write("{\"id\":\"" + id + "\",\"title\":\"占用中的会话\",\"messages\":[]}");
        }

        ChatHistoryManager.TrashEntry target = null;
        for (ChatHistoryManager.TrashEntry e : manager.listTrashedSessions()) {
            if (id.equals(e.sessionId)) target = e;
        }
        assertNotNull("回收站里应能找到刚删的那条", target);

        String copyId = manager.restoreTrashedSession(target);
        assertNotNull(copyId);
        assertFalse("撞车时必须换新 id，不能覆盖在用的那条", id.equals(copyId));
        assertTrue("旧会话文件不能被覆盖", new File(conversationsDir, id + ".json").exists());
        assertTrue("副本应以新 id 落盘", new File(conversationsDir, copyId + ".json").exists());

        ConversationSession copy = manager.loadConversationSession(copyId);
        assertNotNull(copy);
        assertEquals("副本标题要能一眼认出来（标记在前，长标题被截断也看得见）",
                ChatHistoryManager.RESTORED_COPY_MARK + title, copy.title);
        assertEquals("副本内容仍是删除时的快照", 2, copy.messages.size());

        // 切会话/退出都会再保存一次：标题不能被"按首条用户消息重算"覆盖回原标题
        // （真机 2026-10-28：恢复副本被点开一次，标记就没了）
        manager.saveCurrentChatAsSession(copy.messages, copyId, 0, false);
        ConversationSession afterSave = manager.loadConversationSession(copyId);
        assertNotNull(afterSave);
        assertEquals("恢复副本标记必须在后续保存后仍然保留",
                ChatHistoryManager.RESTORED_COPY_MARK + title, afterSave.title);
    }

    private int conversationFileCount() {
        File[] files = conversationsDir.listFiles((d, name) -> name.endsWith(".json"));
        return files == null ? 0 : files.length;
    }
}
