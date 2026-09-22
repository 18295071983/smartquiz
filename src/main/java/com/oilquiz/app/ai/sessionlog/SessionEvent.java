package com.oilquiz.app.ai.sessionlog;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 会话事件 —— 事件日志的持久化行（append-only）。
 *
 * <p>语义约束：
 * <ul>
 *   <li>只追加、不修改、不删除（按会话清理除外）；</li>
 *   <li>{@code seq} 为会话内单调递增序号，回放与排序的唯一依据；</li>
 *   <li>{@code payload} 为事件类型对应的 JSON 载荷（见 {@link SessionEventPayload}）。</li>
 * </ul>
 */
@Entity(tableName = "session_events",
        indices = {@Index(value = "sessionId")})
public class SessionEvent {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 会话内单调递增序号（append-only 排序键） */
    public long seq;

    /** 会话 ID（业务侧自行分配，如聊天会话 ID / Agent 任务 ID） */
    public String sessionId;

    /** 事件类型名（{@link SessionEventType#name()}） */
    public String type;

    /** 事件载荷 JSON（可为 null） */
    @Nullable
    public String payload;

    /** 事件发生时间（epoch millis） */
    public long createdAt;

    /** 事件耗时（毫秒，可选，0 表示不适用） */
    public long durationMs;

    public SessionEvent() {
    }

    public SessionEvent(long seq, String sessionId, SessionEventType type,
                        @Nullable String payload, long createdAt, long durationMs) {
        this.seq = seq;
        this.sessionId = sessionId;
        this.type = type.name();
        this.payload = payload;
        this.createdAt = createdAt;
        this.durationMs = durationMs;
    }

    /** 便捷构造：无耗时 */
    public SessionEvent(long seq, String sessionId, SessionEventType type,
                        @Nullable String payload, long createdAt) {
        this(seq, sessionId, type, payload, createdAt, 0L);
    }

    public SessionEventType eventType() {
        try {
            return SessionEventType.valueOf(type);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "SessionEvent{" + seq + ":" + sessionId + ":" + type + "}";
    }
}
