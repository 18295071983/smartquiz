package com.oilquiz.app.ai.util;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.chat.ChatMessage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

    private int conversationFileCount() {
        File[] files = conversationsDir.listFiles((d, name) -> name.endsWith(".json"));
        return files == null ? 0 : files.length;
    }
}
