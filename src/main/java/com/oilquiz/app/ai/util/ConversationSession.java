package com.oilquiz.app.ai.util;

import com.oilquiz.app.ai.chat.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话会话模型 - 表示一次完整的对话记录。
 *
 * 用于多会话持久化：每次"新对话"自动保存当前会话，
 * 用户可从历史列表中切换回任意会话。
 */
public class ConversationSession {

    /** 会话唯一ID */
    public String id;

    /** 会话标题（自动取第一条用户消息的前30字，或"新对话"） */
    public String title;

    /** 会话中的所有消息 */
    public List<ChatMessage> messages;

    /** 创建时间戳 */
    public long createdAt;

    /** 最后更新时间戳 */
    public long updatedAt;

    /** 消息数量（列表加载时为节省内存会置空 messages，用此字段展示条数） */
    public int messageCount;

    public ConversationSession() {
        this.messages = new ArrayList<>();
    }

    public ConversationSession(String id, String title, List<ChatMessage> messages) {
        this.id = id;
        this.title = title;
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        this.messageCount = this.messages.size();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
        if (!this.messages.isEmpty()) {
            this.createdAt = this.messages.get(0).timestamp;
            this.updatedAt = this.messages.get(this.messages.size() - 1).timestamp;
        }
    }

    /** 消息数量（messages 为空但已持久化 messageCount 时，返回持久化值） */
    public int getMessageCount() {
        if (messages != null) return messages.size();
        return messageCount;
    }

    /** 从消息列表自动生成标题 */
    public static String generateTitle(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return "新对话";
        for (ChatMessage msg : messages) {
            if (msg.isUserMessage() && msg.content != null && !msg.content.trim().isEmpty()) {
                String content = msg.content.trim();
                // 去掉换行，取前30字
                content = content.replace("\n", " ").trim();
                if (content.length() > 30) {
                    return content.substring(0, 30) + "...";
                }
                return content;
            }
        }
        return "新对话";
    }
}
