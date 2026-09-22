package com.oilquiz.app.ai.plugin;

import android.util.Log;

import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.sessionlog.AgentEventBridge;
import com.oilquiz.app.ai.sessionlog.SessionEventLog;
import com.oilquiz.app.ai.sessionlog.SessionReplay;
import com.oilquiz.app.ai.tool.AIToolResult;

import org.json.JSONObject;

import java.util.Collections;
import java.util.Map;

/**
 * 会话日志插件 —— 路径 B 的演示插件，串起三条主线：
 *
 * <ul>
 *   <li>挂载 = 开启会话事件日志（SESSION_STARTED）并注册 session_stats 工具；</li>
 *   <li>工具注册走插件式（注册返回 disposer，卸载自动注销）；</li>
 *   <li>卸载 = 结束会话日志（SESSION_ENDED）并撤销工具。</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 * PluginRegistry registry = ...;
 * AutoCloseable h = registry.mount(new SessionLogPlugin("chat-001"));
 * // UI 回调包一层桥接器即可自动记日志：
 * agentSession.setCallback(SessionLogPlugin.wrap(uiCallback, "chat-001", eventLog));
 * h.close(); // 结束会话
 * </pre>
 */
public class SessionLogPlugin implements Plugin {

    private static final String TAG = "SessionLogPlugin";

    private final String sessionId;
    private AutoCloseable statsToolHandle;

    public SessionLogPlugin(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String id() {
        return "session-log";
    }

    @Override
    public String name() {
        return "会话事件日志";
    }

    @Override
    public void onMount(PluginContext ctx) {
        SessionEventLog log = ctx.eventLog();
        if (log == null) {
            Log.w(TAG, "eventLog 不可用，会话日志插件仅注册工具");
        } else {
            log.newSession(sessionId);
        }
        statsToolHandle = ctx.registerJavaTool(new JavaPluginTool() {
            @Override
            public String name() {
                return "session_stats";
            }

            @Override
            public String description() {
                return "查询当前会话的事件日志统计：轮次数、工具调用数、消息数、思考数、会话状态。"
                        + "用于了解本次智能体会话的执行过程。";
            }

            @Override
            public String category() {
                return "meta";
            }

            @Override
            public Map<String, String> parameters() {
                return Collections.emptyMap();
            }

            @Override
            public AIToolResult execute(Map<String, Object> parameters) throws Exception {
                SessionEventLog l = ctx.eventLog();
                if (l == null) {
                    return AIToolResult.fail("事件日志不可用", null);
                }
                SessionReplay.ReplayResult replay = l.replay(sessionId);
                SessionReplay.ReplayStats s = replay.stats;
                JSONObject o = new JSONObject();
                o.put("sessionId", sessionId);
                o.put("status", s.status.name());
                o.put("turnCount", s.turnCount);
                o.put("toolCallCount", s.toolCallCount);
                o.put("assistantMessageCount", s.assistantMessageCount);
                o.put("userMessageCount", s.userMessageCount);
                o.put("thinkingCount", s.thinkingCount);
                o.put("eventCount", l.count(sessionId));
                return AIToolResult.success(o.toString(), null);
            }
        });
    }

    @Override
    public void onUnmount() {
        if (statsToolHandle != null) {
            try {
                statsToolHandle.close();
            } catch (Exception e) {
                Log.w(TAG, "unregister tool failed", e);
            }
            statsToolHandle = null;
        }
    }

    /** 便捷：把 UI 回调包上事件日志桥接器（delegate 可为 null，仅记日志）。 */
    public static AgentCallback wrap(AgentCallback delegate, String sessionId,
                                     SessionEventLog log) {
        return AgentEventBridge.wrap(delegate, sessionId, log);
    }
}
