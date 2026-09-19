package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.util.ChatHistoryManager;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话历史工具：把本地持久化的对话历史（ai_chat_history.json / agent_chat_history.json）
 * 暴露给 Agent——跨会话读取"之前说过什么、创建过什么"，解决新会话上下文丢失
 * （如"上次创建的组件 id"在历史里可查）。
 *
 * 动作：
 * - recent: 最近 N 条消息（source=ai|agent，limit 默认 30，最大 200）
 * - search: 关键词搜历史（keyword + source，最多返回 50 条命中）
 * - count: 历史消息条数
 *
 * 角色: user=用户, assistant=AI, tool=工具结果, system=系统, agent=Agent 思考/步骤。
 */
@Tool(value = "chat_history", category = "memory")
public class ChatHistoryTool implements AITool {

    private static final String TAG = "ChatHistoryTool";
    private static final int MAX_LIMIT = 200;
    private static final int CONTENT_MAX = 600;
    private static final int OUTPUT_MAX = 6000;
    private final Context context;

    public ChatHistoryTool() {
        this.context = null;
    }

    public ChatHistoryTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "chat_history";
    }

    @Override
    public String getDescription() {
        return "对话历史：读取本地保存的聊天历史（跨会话）。新会话里需要回忆之前说过的话、上次创建的工具/组件/文件时使用；"
                + "比如用户说\"之前让你创建过xx\"\"上次那个组件\"\"历史里找\"时调用。"
                + "action: recent(最近消息，source=ai|agent，limit条数默认30最大200)|search(关键词搜索，keyword必填)|count(条数)。"
                + "返回带序号与角色的消息内容，按时间倒序。历史是只读的，不能修改/删除。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作：recent(最近消息，默认)|search(关键词搜索)|count(消息条数)");
        params.put("source", "历史来源：ai(AI对话历史，默认)|agent(Agent对话历史)");
        params.put("limit", "最近消息条数（recent用，默认30，最大200）");
        params.put("keyword", "搜索关键词（search用）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            if (context == null) {
                return AIToolResult.fail("对话历史工具未初始化");
            }
            String action = parameters.get("action") != null
                    ? String.valueOf(parameters.get("action")).trim().toLowerCase() : "recent";
            String source = parameters.get("source") != null
                    ? String.valueOf(parameters.get("source")).trim().toLowerCase() : "ai";
            boolean agent = "agent".equals(source);

            ChatHistoryManager mgr = new ChatHistoryManager(context);
            List<ChatMessage> history = agent ? mgr.loadAgentChatHistory() : mgr.loadAIChatHistory();
            if (history == null) history = new ArrayList<>();

            switch (action) {
                case "search": {
                    String keyword = parameters.get("keyword") != null
                            ? String.valueOf(parameters.get("keyword")).trim() : "";
                    if (keyword.isEmpty()) {
                        return AIToolResult.fail("search 需要 keyword 参数");
                    }
                    return search(history, keyword, agent);
                }
                case "count": {
                    Map<String, Object> info = new HashMap<>();
                    info.put("count", history.size());
                    info.put("source", agent ? "agent" : "ai");
                    return AIToolResult.success("对话历史共 " + history.size() + " 条（"
                            + (agent ? "Agent 历史" : "AI 历史") + "）", info);
                }
                case "recent":
                default: {
                    int limit = 30;
                    try {
                        Object l = parameters.get("limit");
                        if (l != null) {
                            limit = Math.min(MAX_LIMIT, Math.max(1, Integer.parseInt(String.valueOf(l))));
                        }
                    } catch (Exception ignored) {
                    }
                    return recent(history, limit, agent);
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "chat_history failed: " + e.getMessage(), e);
            return AIToolResult.fail("对话历史读取失败: " + e.getMessage());
        }
    }

    private AIToolResult recent(List<ChatMessage> history, int limit, boolean agent) {
        StringBuilder sb = new StringBuilder();
        sb.append("「").append(agent ? "Agent 对话历史" : "AI 对话历史").append("」最近 ")
                .append(Math.min(limit, history.size())).append(" 条（共 ").append(history.size()).append(" 条，倒序）:\n");
        int start = Math.max(0, history.size() - limit);
        for (int i = history.size() - 1; i >= start; i--) {
            appendMessage(sb, history.get(i), history.size() - i);
            if (sb.length() > OUTPUT_MAX) {
                sb.append("\n...（已达输出上限，可用 search 精确查找）");
                break;
            }
        }
        Map<String, Object> info = new HashMap<>();
        info.put("source", agent ? "agent" : "ai");
        info.put("total", history.size());
        return AIToolResult.success(sb.toString(), info);
    }

    private AIToolResult search(List<ChatMessage> history, String keyword, boolean agent) {
        String kw = keyword.toLowerCase();
        StringBuilder sb = new StringBuilder();
        int hits = 0;
        for (int i = history.size() - 1; i >= 0 && hits < 50; i--) {
            ChatMessage msg = history.get(i);
            String content = msg.getContent() != null ? msg.getContent() : "";
            if (content.toLowerCase().contains(kw)) {
                appendMessage(sb, msg, history.size() - i);
                hits++;
                if (sb.length() > OUTPUT_MAX) {
                    sb.append("\n...（已达输出上限，请用更精确的关键词）");
                    break;
                }
            }
        }
        if (hits == 0) {
            return AIToolResult.fail("「" + (agent ? "Agent 历史" : "AI 历史")
                    + "」中未找到包含『" + keyword + "』的消息");
        }
        Map<String, Object> info = new HashMap<>();
        info.put("source", agent ? "agent" : "ai");
        info.put("hits", hits);
        return AIToolResult.success(sb.insert(0, "命中 " + hits + " 条（倒序）:\n").toString(), info);
    }

    private void appendMessage(StringBuilder sb, ChatMessage msg, int seq) {
        String role = msg.getRole() != null ? msg.getRole().trim().toLowerCase() : "";
        String roleLabel;
        switch (role) {
            case "user": roleLabel = "用户"; break;
            case "assistant": roleLabel = "AI"; break;
            case "tool": roleLabel = "工具"; break;
            case "system": roleLabel = "系统"; break;
            case "agent": roleLabel = "Agent"; break;
            default: roleLabel = role.isEmpty() ? "消息" : role;
        }
        String content = msg.getContent() != null ? msg.getContent().trim() : "";
        if (content.length() > CONTENT_MAX) {
            content = content.substring(0, CONTENT_MAX) + "...（已截断）";
        }
        sb.append("[").append(seq).append("] ").append(roleLabel).append(": ")
                .append(content.isEmpty() ? "（无文本）" : content).append("\n");
    }
}
