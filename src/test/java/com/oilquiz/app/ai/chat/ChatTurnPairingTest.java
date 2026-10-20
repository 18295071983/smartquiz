package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * USER / AI 成对兜底（2026-10-21）：缺 AI 的 USER 补一条 ERROR 占位。
 */
public class ChatTurnPairingTest {

    private static ChatMessage user(String id, String turn) {
        ChatMessage m = ChatMessage.createUserMessage(id, "hi", 1L);
        m.turnId = turn;
        return m;
    }

    private static ChatMessage ai(String id, String turn) {
        ChatMessage m = ChatMessage.createAIMessage(id, "ok", 2L, null, 0, 0);
        m.turnId = turn;
        return m;
    }

    @Test
    public void orphanUserGetsErrorPlaceholder() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(user("u1", "T1"));

        List<ChatMessage> added = ChatTurnPairing.insertMissingReplies(messages, null);

        assertEquals(1, added.size());
        assertEquals(2, messages.size());
        ChatMessage placeholder = messages.get(1);
        assertEquals(ChatMessage.MessageType.AI, placeholder.type);
        assertEquals("T1", placeholder.turnId);
        assertEquals(ChatMessage.MessageStatus.ERROR, placeholder.status);
        assertTrue(placeholder.content.contains("未生成回复"));
    }

    @Test
    public void pairedUserIsUntouched() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(user("u1", "T1"));
        messages.add(ai("a1", "T1"));

        List<ChatMessage> added = ChatTurnPairing.insertMissingReplies(messages, null);

        assertTrue(added.isEmpty());
        assertEquals(2, messages.size());
    }

    @Test
    public void multipleOrphansEachGetOnePlaceholder() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(user("u1", "T1"));
        messages.add(user("u2", "T2"));

        List<ChatMessage> added = ChatTurnPairing.insertMissingReplies(messages, null);

        assertEquals(2, added.size());
        assertEquals(4, messages.size());
        assertNotNull(messages.get(1).turnId);
        assertEquals("T1", messages.get(1).turnId);
        assertEquals("T2", messages.get(3).turnId);
    }

    @Test
    public void orphanBeforeNextTurnIsRepairedInPlace() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(user("u1", "T1"));
        messages.add(user("u2", "T2"));
        messages.add(ai("a2", "T2"));

        List<ChatMessage> added = ChatTurnPairing.insertMissingReplies(messages, "未生成回复");

        assertEquals(1, added.size());
        assertEquals(4, messages.size());
        assertEquals("T1", messages.get(1).turnId);
        assertEquals(ChatMessage.MessageType.AI, messages.get(1).type);
        assertEquals("T2", messages.get(2).turnId);
    }
}
