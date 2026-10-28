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

    /**
     * 本会话用到的最大回合序号（{@code turnId = "T<n>"} 里的 n；没有回合时为 0）。
     *
     * <p>用途只有一个：启动/恢复时把 {@code ChatIdDispatcher} 的主计数器抬到"全库最高"，
     * 而不是只按"当前打开的那个会话"播种。否则一旦 {@code next_master} 丢失
     * （重装、清数据、从备份恢复 appdata 时没带 prefs），先打开一个老会话就会把计数器
     * 压回它的 T 段，之后新建的回合就可能与另一个会话里已存在的 T 重号。</p>
     *
     * <p>存在会话元数据里而不是另建索引：会话文件本来就每次保存全量重写，
     * 顺手算一次几乎零成本；读侧又是流式只读元数据（不解析 messages），
     * 比"启动时把所有会话的 messages 全量扫一遍找最大 T"便宜一个量级。</p>
     *
     * <p>兼容：旧文件没有这个字段 → 反序列化为 0，等它下次被保存时补齐；
     * 0 不参与取最大值。</p>
     */
    public long maxTurnId;

    public ConversationSession() {
        this.messages = new ArrayList<>();
    }

    public ConversationSession(String id, String title, List<ChatMessage> messages) {
        this.id = id;
        this.title = title;
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        this.messageCount = this.messages.size();
        this.maxTurnId = maxTurnNumber(messages);
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

    /** 解析 {@code turnId} 里的序号（{@code "T533" → 533}）；不是该形态返回 -1 */
    public static long turnNumber(String turnId) {
        if (turnId == null) return -1L;
        String s = turnId.trim();
        if (s.length() < 2 || s.charAt(0) != 'T') return -1L;
        long n = 0L;
        for (int i = 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return -1L;
            n = n * 10 + (ch - '0');
            if (n > 999_999_999L) return -1L;   // 脏数据防溢出
        }
        return n;
    }

    /** 一批消息里的最大回合序号（没有 turnId 时返回 0）；保存会话时写进 {@link #maxTurnId} */
    public static long maxTurnNumber(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return 0L;
        long max = 0L;
        for (ChatMessage m : messages) {
            if (m == null) continue;
            long n = turnNumber(m.turnId);
            if (n > max) max = n;
        }
        return max;
    }
}
