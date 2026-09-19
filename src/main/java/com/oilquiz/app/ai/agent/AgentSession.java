package com.oilquiz.app.ai.agent;

import android.content.Context;

import com.oilquiz.app.ai.agent.debug.DebugTracer;
import com.oilquiz.app.ai.agent.online.OnlineAgentEngine;
import com.oilquiz.app.ai.agent.online.OnlineToolManager;

/**
 * 独立 Agent 会话 API —— 把在线模型推理打包成可复用的智能体会话。
 *
 * 与 UI 完全解耦：不持有任何 Activity（内部只用 ApplicationContext），
 * 可多实例并发、可脱离界面独立执行、可单元测试。
 * 事件（正文流 / 思考流 / 工具调用 / 完成 / 错误）统一经 {@link AgentCallback} 流出。
 *
 * 用法：
 * <pre>
 * AgentSession session = AgentSession.create(context);
 * session.setCallback(new AgentCallback() { ... });   // 接收流式事件
 * session.start("帮我整理需求", 8192);                  // 启动一轮智能体执行
 * session.stop();                                      // 取消
 * session.shutdown();                                  // 释放资源
 * </pre>
 */
public class AgentSession {

    private final Context context;
    private final OnlineToolManager toolManager;
    private final OnlineAgentEngine engine;

    private AgentSession(Context context) {
        this.context = context.getApplicationContext();
        this.toolManager = new OnlineToolManager(this.context);
        OnlineToolManager.setInstance(toolManager);
        this.engine = new OnlineAgentEngine(this.context, toolManager);
    }

    /** 创建独立 Agent 会话（context 可为 Activity/Service/Application，内部统一取 ApplicationContext） */
    public static AgentSession create(Context context) {
        return new AgentSession(context);
    }

    /** 注册事件回调（正文/思考/工具/完成/错误） */
    public void setCallback(AgentCallback callback) {
        engine.setCallback(callback);
    }

    /** 注册推理进度监听（token 数 / 阶段 / 速率） */
    public void setInferenceProgressListener(InferenceProgressListener listener) {
        engine.setInferenceProgressListener(listener);
    }

    /** 注册调试追踪监听（调试控制台用；LLM 级 token/耗时/run 生命周期） */
    public void setDebugTracer(DebugTracer tracer) {
        engine.setDebugTracer(tracer);
    }

    /** 启动一轮智能体执行（推理 → 工具调用 → 结果注入 → 再推理，最多 8 轮） */
    public void start(String prompt, int maxTokens) {
        engine.execute(prompt, maxTokens);
    }

    /** 启动一轮智能体执行，可指定深度思考开关 */
    public void start(String prompt, int maxTokens, boolean enableThinking) {
        engine.execute(prompt, maxTokens, enableThinking);
    }

    /** 取消当前执行 */
    public void stop() {
        engine.cancel();
    }

    /**
     * 与智能体直接对话（续聊）：在既有会话上下文上追加一条用户消息并执行一轮推理+工具循环。
     * 引擎保留 messageHistory 实现连续对话（与导入任务同会话），回复经 AgentCallback 回调。
     */
    public void sendMessage(String message, int maxTokens) {
        engine.execute(message, maxTokens, false);
    }

    /** 当前是否正在生成（对话/导入执行中返回 true，UI 应禁用发送） */
    public boolean isBusy() {
        return engine.isGenerating();
    }

    /**
     * 开启全新会话：清空 messageHistory 与持久化历史文件（含摘要），
     * 下一次 start/sendMessage 从干净上下文开始。用于新任务（如每次题库导入）。
     * 生成中调用会被忽略（引擎并发保护），调用方可先等 isBusy()==false。
     */
    public void newSession() {
        engine.clearHistory();
    }

    /** 释放引擎资源（线程池等） */
    public void shutdown() {
        engine.shutdown();
    }

    /** 上下文用量信息 {window, used, remaining} */
    public int[] getContextWindowInfo() {
        return engine.getContextWindowInfo();
    }
}
