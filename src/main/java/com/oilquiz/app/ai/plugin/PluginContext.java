package com.oilquiz.app.ai.plugin;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.agent.online.OnlineToolRegistry;
import com.oilquiz.app.ai.capability.LlmRegistry;
import com.oilquiz.app.ai.sessionlog.SessionEventLog;
import com.oilquiz.app.ai.tool.AITool;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.openai.ToolDefinition;

import java.util.HashMap;
import java.util.Map;

/**
 * 插件上下文 —— 插件向宿主声明/获取能力的唯一入口。
 *
 * <p>对应 deepseek-harness 的 Cordis ctx：一切注册都通过 ctx 完成，
 * 且注册返回 disposer（AutoCloseable），卸载即撤销。
 */
public class PluginContext {

    private static final String TAG = "PluginContext";

    private final Context appContext;
    private final SessionEventLog eventLog;     // 可空（数据库不可用时）
    private final LlmRegistry llmRegistry;      // 可空（未装配时）
    private final AIToolManager toolManager;    // 可空（未装配时）
    private final OnlineToolRegistry onlineToolRegistry; // 可空

    public PluginContext(Context context, SessionEventLog eventLog, LlmRegistry llmRegistry) {
        this(context, eventLog, llmRegistry, null, null);
    }

    public PluginContext(Context context, SessionEventLog eventLog, LlmRegistry llmRegistry,
                         AIToolManager toolManager, OnlineToolRegistry onlineToolRegistry) {
        this.appContext = context != null ? context.getApplicationContext() : null;
        this.eventLog = eventLog;
        this.llmRegistry = llmRegistry;
        this.toolManager = toolManager;
        this.onlineToolRegistry = onlineToolRegistry;
    }

    public Context context() {
        return appContext;
    }

    /** 会话事件日志（可能为 null：数据库不可用或未装配） */
    public SessionEventLog eventLog() {
        return eventLog;
    }

    /** LLM Provider 注册表（可能为 null） */
    public LlmRegistry llmRegistry() {
        return llmRegistry;
    }

    /**
     * 注册一个 Java 实现的插件工具；返回的 disposer 卸载时自动注销。
     * 同时写入 AIToolManager（可执行）与 OnlineToolRegistry（模型可见）。
     *
     * @param tool 插件工具定义
     * @return close() 即注销工具
     */
    public AutoCloseable registerJavaTool(JavaPluginTool tool) {
        if (tool == null || tool.name() == null || tool.name().isEmpty()) {
            throw new IllegalArgumentException("Tool name must be non-empty");
        }
        AITool adapter = tool.asAitool();
        if (toolManager != null) {
            toolManager.registerDynamicTool(adapter);
        }
        if (onlineToolRegistry != null) {
            ToolDefinition def = buildToolDefinition(tool);
            onlineToolRegistry.registerTool(tool.name(), tool.description(),
                    tool.category() == null ? "general" : tool.category(), def);
        }
        Log.i(TAG, "Java tool registered: " + tool.name());
        return () -> {
            if (toolManager != null) {
                toolManager.unregisterDynamicTool(tool.name());
            }
            if (onlineToolRegistry != null) {
                onlineToolRegistry.unregisterTool(tool.name());
            }
            Log.i(TAG, "Java tool unregistered: " + tool.name());
        };
    }

    private static ToolDefinition buildToolDefinition(JavaPluginTool tool) {
        ToolDefinition.Builder builder = ToolDefinition.builder(tool.name(), tool.description());
        builder.category(tool.category() == null ? "general" : tool.category());
        Map<String, String> params = tool.parameters();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                builder.addParameter(e.getKey(), "string", e.getValue(), false);
            }
        }
        return builder.build();
    }

    // ==================== 便捷：普通 Java 工具适配 ====================

    /** 简单实现：直接用工具实现类（避免重复样板） */
    public static final class SimpleJavaTool implements AITool {
        private final JavaPluginTool delegate;

        public SimpleJavaTool(JavaPluginTool delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getName() {
            return delegate.name();
        }

        @Override
        public String getDescription() {
            return delegate.description();
        }

        @Override
        public AIToolResult execute(Map<String, Object> parameters) {
            try {
                return delegate.execute(parameters);
            } catch (Exception e) {
                return AIToolResult.fail("工具执行异常: " + e.getMessage(), null);
            }
        }

        @Override
        public Map<String, String> getParameterDescriptions() {
            return delegate.parameters() != null
                    ? new HashMap<>(delegate.parameters()) : new HashMap<>();
        }
    }
}
