package com.oilquiz.app.ai.chat.context;

import com.oilquiz.app.ai.agent.software.engine.AgentLoopEngine;
import com.oilquiz.app.ai.chat.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史条目构建器（全局可复用）。
 *
 * 从 AIChatActivity buildNormalHistoryEntries / buildAgentHistory / mergeConsecutiveSameRole /
 * lastUserMessageIndex 抽取：以 UI 会话历史为上下文真相源，普通↔Agent 两模式共享同一份
 * 完整对话（切换不失忆）。依赖 {@link ChatContextBuilder} 完成 token 预算收集，
 * 本类只负责条目组装、同角色合并、历史要点与提示词变更标记注入。
 *
 * 用法：
 * <pre>
 * HistoryEntriesBuilder builder = new HistoryEntriesBuilder(contextBuilder);
 * List&lt;String[]&gt; normal = builder.buildNormalHistoryEntries(history);
 * List&lt;AgentLoopEngine.HistoryEntry&gt; agent = builder.buildAgentHistory(history);
 * </pre>
 */
public class HistoryEntriesBuilder {

    private final ChatContextBuilder contextBuilder;

    public HistoryEntriesBuilder(ChatContextBuilder contextBuilder) {
        this.contextBuilder = contextBuilder;
    }

    /** 合并连续同角色消息（保持严格 user/assistant 交替，防模板畸形） */
    public List<String[]> mergeConsecutiveSameRole(List<String[]> entries) {
        List<String[]> result = new ArrayList<>();
        String lastRole = null;
        for (String[] entry : entries) {
            if (entry[0].equals(lastRole) && !result.isEmpty()) {
                String[] last = result.get(result.size() - 1);
                last[1] = last[1] + "\n\n" + entry[1];
            } else {
                result.add(new String[]{entry[0], entry[1]});
                lastRole = entry[0];
            }
        }
        return result;
    }

    /** 定位最后一条 user 消息索引（即当前待发送消息）；无则返回 -1 */
    public int lastUserMessageIndex(List<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (m != null && m.type == ChatMessage.MessageType.USER) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从 UI 会话历史构建普通对话的上下文轮次（{role, content} 对）。
     * 排除当前待发送的 user 消息（调用方会追加它）。
     */
    public List<String[]> buildNormalHistoryEntries(List<ChatMessage> history) {
        List<String[]> result = new ArrayList<>();
        if (history == null || history.isEmpty()) return result;

        int currentUserIdx = lastUserMessageIndex(history);
        if (currentUserIdx < 0) return result;

        List<String[]> temp = contextBuilder.collectHistoryByBudget(history, currentUserIdx, false);
        result = mergeConsecutiveSameRole(temp);

        // 提示词变更标记放最前作系统指令
        String marker = contextBuilder.getPromptChangeMarkerIfAny();
        if (marker != null) {
            result.add(0, new String[]{"system", marker});
        }
        // 历史压缩要点追加到末尾（紧挨当前待发送消息之前，保持前缀稳定利于 KV 缓存命中）
        List<String> evicted = contextBuilder.getEvictedContextPoints();
        if (!evicted.isEmpty()) {
            StringBuilder pts = new StringBuilder("【历史对话要点】(较早对话已压缩，上下文有限)\n");
            for (String p : evicted) {
                pts.append("• ").append(p).append('\n');
            }
            result.add(new String[]{"system", pts.toString().trim()});
        }
        return result;
    }

    /**
     * 从 UI 对话历史构建本地 Agent 的多轮上下文（含工具调用痕迹与思考过程）。
     */
    public List<AgentLoopEngine.HistoryEntry> buildAgentHistory(List<ChatMessage> history) {
        List<AgentLoopEngine.HistoryEntry> result = new ArrayList<>();
        if (history == null || history.isEmpty()) return result;

        int currentUserIdx = lastUserMessageIndex(history);
        if (currentUserIdx < 0) return result;

        List<String[]> temp = contextBuilder.collectHistoryByBudget(history, currentUserIdx, true);
        List<String[]> merged = mergeConsecutiveSameRole(temp);

        for (String[] entry : merged) {
            result.add(new AgentLoopEngine.HistoryEntry(entry[0], entry[1]));
        }
        return result;
    }
}
