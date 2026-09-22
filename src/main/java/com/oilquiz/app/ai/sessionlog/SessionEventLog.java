package com.oilquiz.app.ai.sessionlog;

import android.util.Log;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 会话事件日志 —— append-only 事件追加器。
 *
 * <p>职责（对应 deepseek-harness 的 session event log 模式）：
 * <ul>
 *   <li>为每个会话分配单调递增 seq，保证顺序稳定；</li>
 *   <li>只追加不修改；</li>
 *   <li>追加后通知订阅者（live 观察），并落库（持久化可回放）。</li>
 * </ul>
 *
 * <p>线程安全：{@code append} 系列方法 synchronized，保证同会话 seq 严格有序。
 */
public class SessionEventLog {

    private static final String TAG = "SessionEventLog";

    /** 事件追加订阅者（live 观察，如 UI 刷新 / 统计聚合） */
    public interface Listener {
        void onEventAppended(SessionEvent event);
    }

    private final SessionEventDao dao;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public SessionEventLog(SessionEventDao dao) {
        this.dao = dao;
    }

    // ==================== 追加（append-only） ====================

    /** 追加一条事件（自动分配会话内 seq）。payload 可为 null。 */
    public synchronized SessionEvent append(String sessionId, SessionEventType type,
                                            JSONObject payload) {
        long seq = dao.maxSeq(sessionId) + 1;
        long now = System.currentTimeMillis();
        SessionEvent event = new SessionEvent(seq, sessionId, type,
                payload == null ? null : payload.toString(), now, 0L);
        dao.insert(event);
        for (Listener l : listeners) {
            try {
                l.onEventAppended(event);
            } catch (Exception e) {
                Log.w(TAG, "Listener failed: " + e.getMessage());
            }
        }
        return event;
    }

    /** 追加一条事件并附带耗时（毫秒）。 */
    public synchronized SessionEvent append(String sessionId, SessionEventType type,
                                            JSONObject payload, long durationMs) {
        long seq = dao.maxSeq(sessionId) + 1;
        long now = System.currentTimeMillis();
        SessionEvent event = new SessionEvent(seq, sessionId, type,
                payload == null ? null : payload.toString(), now, durationMs);
        dao.insert(event);
        for (Listener l : listeners) {
            try {
                l.onEventAppended(event);
            } catch (Exception e) {
                Log.w(TAG, "Listener failed: " + e.getMessage());
            }
        }
        return event;
    }

    /** 会话创建事件。 */
    public SessionEvent newSession(String sessionId) {
        return append(sessionId, SessionEventType.SESSION_STARTED, null);
    }

    /** 会话结束事件。 */
    public SessionEvent endSession(String sessionId) {
        return append(sessionId, SessionEventType.SESSION_ENDED,
                SessionEventPayload.endReason("plugin_unmount"));
    }

    // ==================== 订阅（effect 语义：返回 disposer） ====================

    /** 订阅事件流；返回的 Runnable 用于退订（卸载即撤销，符合“注册即 effect”约定）。 */
    public Runnable subscribe(Listener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    // ==================== 读取 / 回放 ====================

    /** 按会话读取全部事件（追加顺序）。 */
    public List<SessionEvent> events(String sessionId) {
        return dao.events(sessionId);
    }

    /** 回放：由事件日志重建会话视图（消息 / 工具调用 / 统计）。 */
    public SessionReplay.ReplayResult replay(String sessionId) {
        return SessionReplay.replay(dao.events(sessionId));
    }

    /** 会话事件总数。 */
    public int count(String sessionId) {
        return dao.count(sessionId);
    }

    /** 清理会话事件（会话删除/重置；非正常流程）。 */
    public int deleteSession(String sessionId) {
        return dao.deleteBySession(sessionId);
    }
}
