package com.oilquiz.app.ai.plugin;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.agent.online.OnlineToolRegistry;
import com.oilquiz.app.ai.capability.LlmGateway;
import com.oilquiz.app.ai.capability.LlmRegistry;
import com.oilquiz.app.ai.capability.LocalLlmProvider;
import com.oilquiz.app.ai.capability.OnlineLlmProvider;
import com.oilquiz.app.ai.sessionlog.AgentEventBridge;
import com.oilquiz.app.ai.sessionlog.SessionEventLog;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.database.AppDatabase;

/**
 * 路径 B 装配入口 —— 一键初始化“会话事件日志 + LLM 能力缝 + 插件注册”。
 *
 * <p>用法：
 * <pre>
 * AiPathB.Holder holder = AiPathB.init(context);
 * holder.pluginRegistry().mount(new SessionLogPlugin("chat-001")); // 插件生命周期
 * agentSession.setCallback(holder.bridge("chat-001", uiCallback)); // 事件日志桥接
 * holder.gateway().stream(LlmRequest.builder()...build(), cb);     // 能力缝调用
 * holder.shutdown(); // 卸载全部插件
 * </pre>
 */
public final class AiPathB {

    private static final String TAG = "AiPathB";

    private AiPathB() {
    }

    /** 一键装配；数据库不可用时事件日志降级为 null（其余能力不受影响）。 */
    public static Holder init(Context context) {
        Context app = context.getApplicationContext();

        // 1. 会话事件日志（Room 持久化；失败不阻塞启动）
        SessionEventLog eventLog = null;
        try {
            AppDatabase db = AppDatabase.getDatabase(app);
            if (db != null) {
                eventLog = new SessionEventLog(db.sessionEventDao());
            }
        } catch (Exception e) {
            Log.e(TAG, "session event log init failed", e);
        }

        // 2. LLM 能力缝：注册本地 + 在线 Provider
        LlmRegistry llmRegistry = new LlmRegistry();
        try {
            llmRegistry.register(new LocalLlmProvider(app));
            llmRegistry.register(new OnlineLlmProvider(app));
        } catch (Exception e) {
            Log.e(TAG, "llm registry init failed", e);
        }
        LlmGateway gateway = new LlmGateway(llmRegistry, eventLog);

        // 3. 插件注册器（一切皆插件）
        AIToolManager toolManager = null;
        OnlineToolRegistry onlineToolRegistry = null;
        try {
            toolManager = AIToolManager.getInstance(app);
            onlineToolRegistry = new OnlineToolRegistry(app);
        } catch (Exception e) {
            Log.w(TAG, "tool registry init failed", e);
        }
        PluginContext pluginContext = new PluginContext(app, eventLog, llmRegistry,
                toolManager, onlineToolRegistry);
        PluginRegistry pluginRegistry = new PluginRegistry(pluginContext);

        return new Holder(eventLog, llmRegistry, gateway, pluginRegistry, pluginContext);
    }

    /** 路径 B 装配结果句柄。 */
    public static final class Holder {
        private final SessionEventLog eventLog;      // 可空
        private final LlmRegistry llmRegistry;
        private final LlmGateway gateway;
        private final PluginRegistry pluginRegistry;
        private final PluginContext pluginContext;

        Holder(SessionEventLog eventLog, LlmRegistry llmRegistry, LlmGateway gateway,
               PluginRegistry pluginRegistry, PluginContext pluginContext) {
            this.eventLog = eventLog;
            this.llmRegistry = llmRegistry;
            this.gateway = gateway;
            this.pluginRegistry = pluginRegistry;
            this.pluginContext = pluginContext;
        }

        public SessionEventLog eventLog() {
            return eventLog;
        }

        public LlmRegistry llmRegistry() {
            return llmRegistry;
        }

        public LlmGateway gateway() {
            return gateway;
        }

        public PluginRegistry pluginRegistry() {
            return pluginRegistry;
        }

        public PluginContext pluginContext() {
            return pluginContext;
        }

        /** 把 UI 回调包上事件日志桥接器（日志不可用时原样返回 delegate）。 */
        public AgentCallback bridge(String sessionId, AgentCallback delegate) {
            if (eventLog == null || sessionId == null) {
                return delegate;
            }
            return AgentEventBridge.wrap(delegate, sessionId, eventLog);
        }

        /** 卸载全部插件（结束时调用）。 */
        public void shutdown() {
            pluginRegistry.unmountAll();
        }
    }
}
