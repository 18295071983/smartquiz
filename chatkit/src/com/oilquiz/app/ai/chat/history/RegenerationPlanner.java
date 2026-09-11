package com.oilquiz.app.ai.chat.history;

import com.oilquiz.app.ai.chat.ChatMessage;

import java.util.List;

/**
 * 重新生成规划器（全局可复用，逻辑层）。
 *
 * 从 AIChatActivity regenerateMessage 的定位/回滚逻辑抽取：给定 AI 消息 id，
 * 找到其前面的最后一条用户消息与回滚起点，供宿主执行"删除 → 重发"。
 * 纯计算，不触碰 UI 与生成管线。
 */
public final class RegenerationPlanner {

    /** 规划成功：删除 removeStart 之后的所有消息（保留用户消息本身）后重发 userContent */
    public static class Plan {
        public final int userIndex;
        public final int removeStart;
        public final String userContent;

        Plan(int userIndex, int removeStart, String userContent) {
            this.userIndex = userIndex;
            this.removeStart = removeStart;
            this.userContent = userContent;
        }
    }

    public static final int OK = 0;
    /** AI 消息未找到 */
    public static final int ERROR_TARGET_NOT_FOUND = 1;
    /** 未找到前置用户消息 */
    public static final int ERROR_NO_USER_MESSAGE = 2;

    private RegenerationPlanner() {}

    /**
     * 规划重新生成。
     *
     * @return {@link #OK} 时通过 out[0] 返回 Plan；否则返回错误码，out[0] 为 null
     */
    public static int plan(List<ChatMessage> history, String aiMessageId, Plan[] out) {
        if (out != null) out[0] = null;
        if (history == null || aiMessageId == null) return ERROR_TARGET_NOT_FOUND;

        int aiIndex = -1;
        for (int i = 0; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            if (m != null && aiMessageId.equals(m.id)) { aiIndex = i; break; }
        }
        if (aiIndex < 0) return ERROR_TARGET_NOT_FOUND;

        String userContent = null;
        int userIndex = -1;
        for (int i = aiIndex - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (m != null && m.type == ChatMessage.MessageType.USER && m.content != null) {
                userContent = m.content;
                userIndex = i;
                break;
            }
        }
        if (userContent == null) return ERROR_NO_USER_MESSAGE;

        if (out != null) out[0] = new Plan(userIndex, userIndex + 1, userContent);
        return OK;
    }
}
