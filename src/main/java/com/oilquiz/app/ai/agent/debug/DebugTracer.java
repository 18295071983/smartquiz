package com.oilquiz.app.ai.agent.debug;

/**
 * 调试追踪监听器 —— 引擎向外部（调试控制台）暴露可观测事件的轻量钩子。
 *
 * 与 {@link com.oilquiz.app.ai.agent.AgentCallback} 的区别：
 *  - AgentCallback 面向"对话内容"（token/思考/工具/完成），UI 展示消息用；
 *  - DebugTracer 面向"可观测性"（run 生命周期、LLM 调用级 token/耗时），调试分析用。
 *
 * 所有方法都可能在工作线程回调，实现方需自行切回 UI 线程。
 * 引擎只在 debugTracer 非空时回调，默认无任何开销。
 */
public interface DebugTracer {

    /** 一次 Agent 执行开始（run 根节点） */
    void onRunStart(String runId, String model, long ts);

    /** 一次 LLM 推理调用开始 */
    void onLlmStart(String runId, String spanId, long ts);

    /** 一次 LLM 推理调用结束并上报用量（cached=0 表示该 API 未返回缓存命中） */
    void onLlmUsage(String runId, String spanId, int promptTokens, int completionTokens,
                    int totalTokens, int cachedTokens, long startTs, long endTs);

    /** 一次 Agent 执行结束（status: ok / error / cancelled） */
    void onRunEnd(String runId, String status, String error, long ts);

    /** 空实现，便于继承时只覆写关心的回调 */
    class NoOp implements DebugTracer {
        @Override public void onRunStart(String runId, String model, long ts) { }
        @Override public void onLlmStart(String runId, String spanId, long ts) { }
        @Override public void onLlmUsage(String runId, String spanId, int promptTokens,
                                         int completionTokens, int totalTokens,
                                         int cachedTokens, long startTs, long endTs) { }
        @Override public void onRunEnd(String runId, String status, String error, long ts) { }
    }
}
