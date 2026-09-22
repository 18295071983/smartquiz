package com.oilquiz.app.ai.sessionlog;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话回放 —— 由 append-only 事件日志重建会话视图。
 *
 * <p>纯逻辑、无 Android / Room 依赖，可单元测试。
 * 输入 {@link SessionEvent} 列表（按 seq 升序），输出：
 * 消息列表、工具调用列表、会话统计与终止状态。
 */
public final class SessionReplay {

    private SessionReplay() {
    }

    /** 会话终止状态（由事件流推导） */
    public enum Status {
        OPEN,        // 尚无终止事件（会话进行中）
        COMPLETED,   // 最后以 TURN_COMPLETED 结束
        CANCELLED,   // 最后以 TURN_CANCELLED 结束
        FAILED       // 最后以 TURN_FAILED 结束
    }

    /** 回放出的消息（角色 + 内容） */
    public static final class ReplayMessage {
        public final String role;    // user / assistant / thinking
        public final String content;
        public final long createdAt;

        ReplayMessage(String role, String content, long createdAt) {
            this.role = role;
            this.content = content;
            this.createdAt = createdAt;
        }
    }

    /** 回放出的工具调用 */
    public static final class ReplayToolCall {
        public final String toolCallId;
        public final String name;
        public final String args;
        public final boolean success;
        public final String result;
        public final long durationMs;

        ReplayToolCall(String toolCallId, String name, String args,
                       boolean success, String result, long durationMs) {
            this.toolCallId = toolCallId;
            this.name = name;
            this.args = args;
            this.success = success;
            this.result = result;
            this.durationMs = durationMs;
        }
    }

    /** 会话统计 */
    public static final class ReplayStats {
        public int turnCount;
        public int toolCallCount;
        public int assistantMessageCount;
        public int userMessageCount;
        public int thinkingCount;
        public long totalDurationMs;   // 首事件到终止事件耗时（无终止则为 0）
        public Status status = Status.OPEN;

        @Override
        public String toString() {
            return "ReplayStats{turns=" + turnCount + ", tools=" + toolCallCount
                    + ", assistant=" + assistantMessageCount + ", user=" + userMessageCount
                    + ", thinking=" + thinkingCount + ", durationMs=" + totalDurationMs
                    + ", status=" + status + "}";
        }
    }

    /** 回放结果 */
    public static final class ReplayResult {
        public final List<ReplayMessage> messages;
        public final List<ReplayToolCall> toolCalls;
        public final ReplayStats stats;

        ReplayResult(List<ReplayMessage> messages, List<ReplayToolCall> toolCalls,
                     ReplayStats stats) {
            this.messages = messages;
            this.toolCalls = toolCalls;
            this.stats = stats;
        }

        /** 把消息列表渲染成模型可用的 history 文本（按角色拼接） */
        public String toText() {
            StringBuilder sb = new StringBuilder();
            for (ReplayMessage m : messages) {
                sb.append(m.role).append(": ").append(m.content).append("\n");
            }
            return sb.toString();
        }
    }

    /**
     * 由事件列表重建会话视图。
     *
     * @param events 按 seq 升序的事件列表（可来自 Room，也可来自测试构造）
     */
    public static ReplayResult replay(List<SessionEvent> events) {
        List<ReplayMessage> messages = new ArrayList<>();
        List<ReplayToolCall> toolCalls = new ArrayList<>();
        ReplayStats stats = new ReplayStats();

        if (events == null || events.isEmpty()) {
            return new ReplayResult(messages, toolCalls, stats);
        }

        long firstTs = events.get(0).createdAt;
        long lastTs = firstTs;
        // 按 toolCallId 暂存开始信息，供完成事件配对
        java.util.Map<String, String[]> pendingToolCalls = new java.util.HashMap<>();

        for (SessionEvent ev : events) {
            lastTs = ev.createdAt;
            SessionEventType type = ev.eventType();
            if (type == null) {
                continue;
            }
            JSONObject p = parse(ev.payload);
            switch (type) {
                case TURN_STARTED:
                    stats.turnCount++;
                    break;
                case USER_MESSAGE:
                    stats.userMessageCount++;
                    messages.add(new ReplayMessage("user", optString(p, "content"), ev.createdAt));
                    break;
                case ASSISTANT_MESSAGE:
                    stats.assistantMessageCount++;
                    messages.add(new ReplayMessage("assistant", optString(p, "content"), ev.createdAt));
                    break;
                case THINKING_MESSAGE:
                    stats.thinkingCount++;
                    messages.add(new ReplayMessage("thinking", optString(p, "content"), ev.createdAt));
                    break;
                case TOOL_CALL_STARTED: {
                    String id = optString(p, "toolCallId");
                    String name = optString(p, "name");
                    String args = optString(p, "args");
                    pendingToolCalls.put(id, new String[]{name, args});
                    break;
                }
                case TOOL_CALL_COMPLETED: {
                    String id = optString(p, "toolCallId");
                    String name = optString(p, "name");
                    boolean success = p != null && p.optBoolean("success", false);
                    String result = optString(p, "result");
                    stats.toolCallCount++;
                    String[] start = pendingToolCalls.remove(id);
                    if (start != null) {
                        name = start[0];
                    }
                    toolCalls.add(new ReplayToolCall(id, name,
                            start != null ? start[1] : "", success, result, ev.durationMs));
                    break;
                }
                case TURN_COMPLETED:
                    stats.status = Status.COMPLETED;
                    break;
                case TURN_CANCELLED:
                    stats.status = Status.CANCELLED;
                    break;
                case TURN_FAILED:
                    stats.status = Status.FAILED;
                    break;
                default:
                    break;
            }
        }

        if (stats.status == Status.OPEN && lastTs > firstTs) {
            stats.totalDurationMs = lastTs - firstTs;
        }
        return new ReplayResult(messages, toolCalls, stats);
    }

    private static JSONObject parse(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private static String optString(JSONObject o, String key) {
        return o != null && o.has(key) ? o.optString(key, "") : "";
    }
}
