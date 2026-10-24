package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;

import org.junit.Test;

/**
 * 输入统计块的持久化契约（2026-10-25）：它是"完成后显示 2 秒就隐藏"的瞬时 UI，
 * {@code agentSummary} 必须 transient、不落盘 —— 否则历史恢复后旧统计会常驻在
 * 最新一条 AI 回复上，又回到用户报的"统计停在旧回复/位置不稳定"。
 */
public class ChatMessageStatsTransientTest {

    @Test
    public void agentSummary_不落盘_历史恢复后不会常驻() {
        ChatMessage msg = ChatMessage.createAIMessage("id-1", "答", 1L);
        msg.agentSummary = "📥 输入 1 · 📤 输出 2 tokens";
        String json = new Gson().toJson(msg);
        assertFalse("agentSummary 不应出现在持久化 JSON 里", json.contains("agentSummary"));
        ChatMessage restored = new Gson().fromJson(json, ChatMessage.class);
        assertNotNull("消息本身要能正常反序列化", restored);
        assertNull("历史恢复后统计应为空（等下一次完成后重新显示 2 秒）", restored.agentSummary);
    }

    @Test
    public void 运行时仍可正常携带统计文本() {
        ChatMessage msg = ChatMessage.createAIMessage("id-2", "答", 2L);
        msg.agentSummary = "📥 输入 3 · 📤 输出 4 tokens";
        assertFalse("运行时字段不能被清空", msg.agentSummary.isEmpty());
    }
}
