package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;

import org.junit.Test;

/**
 * Agent 执行汇总的持久化契约（2026-10-25 立；2026-10-31 对准改名后的字段）：
 * 它是"完成后显示 2 秒就隐藏"的瞬时 UI，{@code agentSummaryInfo} 必须 transient、不落盘 ——
 * 否则历史恢复后旧统计会常驻在最新一条 AI 回复上，又回到用户报的"统计停在旧回复/位置不稳定"。
 *
 * <p>会话历史是整棵 {@link ChatMessage} 树直接 Gson 落盘的
 * （{@code ChatHistoryManager} 里 {@code gson.toJson(chatHistory, writer)}），
 * 所以 transient 是这条契约唯一的执行机制。</p>
 *
 * <p>2ac71ebc 把 {@code agentSummary}(String) 换成 {@code agentSummaryInfo}(对象) 时漏标了
 * transient，本测试当场编不过（找不到符号）——即"改了产出侧却没跟上契约"。现在字段与测试
 * 都对齐，并用断言把契约重新钉住。</p>
 */
public class ChatMessageStatsTransientTest {

    private static ChatMessage.AgentSummaryInfo sampleSummary() {
        ChatMessage.AgentSummaryInfo info =
                new ChatMessage.AgentSummaryInfo(1234L, 3, 2, 812, true);
        info.summary = "输入 1 · 输出 2 tokens";
        return info;
    }

    @Test
    public void agentSummaryInfo_不落盘_历史恢复后不会常驻() {
        ChatMessage msg = ChatMessage.createAIMessage("id-1", "答", 1L);
        msg.agentSummaryInfo = sampleSummary();

        String json = new Gson().toJson(msg);
        assertFalse("agentSummaryInfo 不应出现在持久化 JSON 里", json.contains("agentSummaryInfo"));
        // 汇总内容本身也不能借别的路径漏进磁盘（Gson 跳过 null，字段名不出现≠值不出现）
        assertFalse("汇总文本不应落盘", json.contains("输入 1 · 输出 2 tokens"));

        ChatMessage restored = new Gson().fromJson(json, ChatMessage.class);
        assertNotNull("消息本身要能正常反序列化", restored);
        assertNull("历史恢复后统计应为空（等下一次完成后重新显示 2 秒）", restored.agentSummaryInfo);
    }

    @Test
    public void 运行时仍可正常携带汇总() {
        ChatMessage msg = ChatMessage.createAIMessage("id-2", "答", 2L);
        msg.agentSummaryInfo = sampleSummary();

        assertNotNull("运行时字段不能被清空", msg.agentSummaryInfo);
        // 界面取的就是这两个格式化值（ChatAdapter 回落空串的分支在 null 时）
        assertEquals("1.2s", msg.agentSummaryInfo.getFormattedTime());
        assertEquals("812", msg.agentSummaryInfo.getFormattedTokens());
    }
}
