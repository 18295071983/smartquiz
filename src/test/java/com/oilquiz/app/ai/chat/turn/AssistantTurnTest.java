package com.oilquiz.app.ai.chat.turn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.ChatMessage;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * AssistantTurn parts 模型（2026-10-21）：modelCalls + 有序 parts 为权威，content 为投影。
 */
public class AssistantTurnTest {

    @Test
    public void snapshotsBecomeOrderedParts() {
        ChatMessage msg = ChatMessage.createAIMessage("a1", "final", 1L);
        msg.turnId = "T1";
        msg.roundSnapshots = new ArrayList<>();
        ChatMessage.RoundSnapshot r1 = new ChatMessage.RoundSnapshot(1, "第一轮正文", "轮1思考", 11L);
        r1.toolCallIds = new ArrayList<>(Arrays.asList("call_1"));
        ChatMessage.RoundSnapshot r2 = new ChatMessage.RoundSnapshot(2, "第二轮最终", "", 12L);
        msg.roundSnapshots.add(r1);
        msg.roundSnapshots.add(r2);

        AssistantTurn turn = AssistantTurn.fromMessage(msg);

        assertNotNull(turn);
        assertEquals("T1", turn.turnId);
        assertEquals(2, turn.modelCalls.size());
        assertEquals("第一轮正文", turn.modelCalls.get(0).content);
        assertEquals("call_1", turn.modelCalls.get(0).toolCallIds.get(0));
        // reasoning -> text -> tool_call -> reasoning? 第二轮无 thinking，直接 text
        assertEquals(AssistantTurn.Part.TYPE_REASONING, turn.parts.get(0).type);
        assertEquals(AssistantTurn.Part.TYPE_TEXT, turn.parts.get(1).type);
        assertEquals(AssistantTurn.Part.TYPE_TOOL_CALL, turn.parts.get(2).type);
        assertEquals(AssistantTurn.Part.TYPE_TEXT, turn.parts.get(3).type);
        assertEquals("第一轮正文\n\n第二轮最终", turn.deriveText());
    }

    @Test
    public void legacyContentFallsBackToSingleCall() {
        ChatMessage msg = ChatMessage.createAIMessage("a2", "老消息", 1L);
        msg.turnId = "T2";
        AssistantTurn turn = AssistantTurn.fromMessage(msg);
        assertEquals(1, turn.modelCalls.size());
        assertEquals("老消息", turn.deriveText());
    }

    @Test
    public void copyIsDeepForCollections() {
        AssistantTurn turn = new AssistantTurn();
        turn.turnId = "T3";
        turn.modelCalls.add(new AssistantTurn.ModelCall("R1", 1, "a", "t", "eos", 1L));
        turn.parts.add(AssistantTurn.Part.text("p1", "a", 1L));
        AssistantTurn copy = turn.copy();
        copy.modelCalls.get(0).content = "b";
        copy.parts.get(0).text = "b";
        assertEquals("a", turn.modelCalls.get(0).content);
        assertEquals("a", turn.parts.get(0).text);
        assertTrue(copy.modelCalls.get(0).toolCallIds.isEmpty());
    }
}
