package com.oilquiz.app.ai.agent.online;

import com.oilquiz.app.util.AILogger;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 轻量 Agent 事件总线（dsh 事件体系对齐，2026-09-23）。
 *
 * <p>复刻 deepseek-harness（dsh）的插件化扩展点：引擎主循环只负责
 * 「调用模型、运行工具、重复」，一切策略（压缩、重试、权限、日志、审计）
 * 通过注册事件监听器实现，不再改动主循环代码。对应 dsh 事件：
 * <ul>
 *   <li>{@link EventType#PRE_STEP}        → dsh agent/pre-step</li>
 *   <li>{@link EventType#TOOL_PRE_EXECUTE} → dsh tools/pre-execute（支持 veto 拒绝）</li>
 *   <li>{@link EventType#TOOL_RESULT}     → dsh tools/result</li>
 *   <li>{@link EventType#REQUEST_ERROR}   → dsh agent/request-error</li>
 *   <li>{@link EventType#COMPACTION}      → dsh compaction/*</li>
 *   <li>{@link EventType#TURN_END}        → dsh turn/end</li>
 * </ul>
 *
 * <p>veto 语义（对齐 dsh tools/pre-execute 可拒绝）：{@link #postVetoable} 遍历监听器，
 * 任一监听器返回 false 即整体拒绝——引擎据此跳过该工具执行。监听器异常一律吞掉，
 * 不影响引擎主流程。
 */
public class AgentEventBus {

    /** 事件类型（dsh SurfaceEventType 之外的引擎控制事件） */
    public enum EventType {
        /** 一轮 execute 开始（dsh turn/start） */
        TURN_START,
        /** 每步推理前（dsh agent/pre-step），detail=iteration 序号，可 veto */
        PRE_STEP,
        /** 工具执行前（dsh tools/pre-execute），detail=toolName，可 veto */
        TOOL_PRE_EXECUTE,
        /** 工具执行后（dsh tools/result），detail=toolName */
        TOOL_RESULT,
        /** LLM 请求失败（dsh agent/request-error），detail=错误摘要 */
        REQUEST_ERROR,
        /** 历史压缩触发（dsh compaction/*），detail=移除消息数 */
        COMPACTION,
        /** 轮次结束（dsh turn/end），detail=结束原因（final_complete/error/...） */
        TURN_END
    }

    /** 事件载体 */
    public static class AgentEvent {
        public final EventType type;
        public final long timestamp;
        /** 简短描述：轮次序号 / 工具名 / 错误摘要 / 移除条数 */
        public final String detail;
        /** 扩展 payload（工具参数、错误对象等），无则为 null */
        public final Object payload;

        public AgentEvent(EventType type, String detail, Object payload) {
            this.type = type;
            this.detail = detail;
            this.payload = payload;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return "AgentEvent{" + type + " '" + detail + "' @" + timestamp + '}';
        }
    }

    /** 事件监听器 */
    public interface Listener {
        /**
         * 事件回调。
         *
         * @return veto 事件（PRE_STEP/TOOL_PRE_EXECUTE）返回 false 表示拒绝；
         *         普通事件忽略返回值。监听器异常不影响引擎主流程。
         */
        boolean onEvent(AgentEvent event);
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /** 注册监听器（线程安全，可随时注册/注销） */
    public void register(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
            AILogger.i("AgentEventBus", "Listener registered, total=" + listeners.size());
        }
    }

    /** 注销监听器 */
    public void unregister(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /** 广播事件（不可 veto，监听器异常被吞掉） */
    public void post(EventType type, String detail) {
        post(type, detail, null);
    }

    /** 广播事件（带 payload，不可 veto） */
    public void post(EventType type, String detail, Object payload) {
        if (listeners.isEmpty()) return;
        AgentEvent event = new AgentEvent(type, detail, payload);
        for (Listener l : listeners) {
            try {
                l.onEvent(event);
            } catch (Throwable t) {
                AILogger.w("AgentEventBus", "Listener error on " + type + ": " + t.getMessage());
            }
        }
    }

    /**
     * 广播可 veto 事件（PRE_STEP / TOOL_PRE_EXECUTE）。
     *
     * @return true=全部监听器放行；false=有监听器拒绝（引擎应跳过该动作）
     */
    public boolean postVetoable(EventType type, String detail, Object payload) {
        if (listeners.isEmpty()) return true;
        AgentEvent event = new AgentEvent(type, detail, payload);
        for (Listener l : listeners) {
            try {
                if (!l.onEvent(event)) {
                    AILogger.i("AgentEventBus", "Vetoed: " + type + " '" + detail + "'");
                    return false;
                }
            } catch (Throwable t) {
                AILogger.w("AgentEventBus", "Listener error on " + type + ": " + t.getMessage());
            }
        }
        return true;
    }

    public int listenerCount() {
        return listeners.size();
    }
}
