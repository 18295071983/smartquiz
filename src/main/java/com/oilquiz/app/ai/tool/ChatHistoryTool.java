package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.util.ChatHistoryManager;
import com.oilquiz.app.util.AILogger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话历史工具：把本地持久化的对话历史暴露给 Agent——跨会话读取"之前说过什么、创建过什么"，
 * 解决新会话上下文丢失（如"上次创建的组件 id"在历史里可查）。
 *
 * 数据源（2026-09-25 对齐新架构）：
 * - source=ai:   AI 对话历史 ai_chat_history.json（ChatMessage 数组；流式 count + 分段加载，
 *                避免大文件全量 Gson 解析 OOM/卡顿）
 * - source=agent: Agent 运行历史 online_agent_history_{session}_{model}.json（OpenAI 风格消息数组；
 *                自动取最近写入的会话文件，即"当前会话"的 Agent 历史）
 *
 * 动作：
 * - recent: 最近 N 条消息（source=ai|agent，limit 默认 30，最大 200）
 * - search: 关键词搜历史（keyword + source，最多返回 50 条命中，倒序）
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
        this.context = SmartQuizApplication.getAppContext();
    }

    public ChatHistoryTool(Context context) {
        this.context = context != null
                ? context.getApplicationContext()
                : SmartQuizApplication.getAppContext();
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
                + "source=ai 读 AI 对话历史；source=agent 读 Agent 运行历史（当前会话的思考/工具调用记录）。"
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

            switch (action) {
                case "search": {
                    String keyword = parameters.get("keyword") != null
                            ? String.valueOf(parameters.get("keyword")).trim() : "";
                    if (keyword.isEmpty()) {
                        return AIToolResult.fail("search 需要 keyword 参数");
                    }
                    return agent ? searchAgentHistory(keyword) : searchAIHistory(keyword);
                }
                case "count": {
                    Map<String, Object> info = new HashMap<>();
                    int count = agent ? countAgentHistory() : countAIHistory();
                    info.put("count", count);
                    info.put("source", agent ? "agent" : "ai");
                    return AIToolResult.success("对话历史共 " + count + " 条（"
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
                    return agent ? recentAgentHistory(limit) : recentAIHistory(limit);
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "chat_history failed: " + e.getMessage(), e);
            return AIToolResult.fail("对话历史读取失败: " + e.getMessage());
        }
    }

    // ==================== AI 历史（ai_chat_history.json，流式分段，防 OOM） ====================

    private File aiHistoryFile() {
        return new File(context.getFilesDir(), "ai_chat_history.json");
    }

    private int countAIHistory() {
        return countMessages(aiHistoryFile());
    }

    private AIToolResult recentAIHistory(int limit) {
        File file = aiHistoryFile();
        if (!file.exists()) {
            return noHistory("AI");
        }
        ChatHistoryManager mgr = new ChatHistoryManager(context);
        int total = countMessages(file);
        if (total == 0) {
            return noHistory("AI");
        }
        int skip = Math.max(0, total - limit);
        List<ChatMessage> page = mgr.loadAIHistoryMessages(skip, limit);
        return renderMessages(page, total, limit, false);
    }

    private AIToolResult searchAIHistory(String keyword) {
        File file = aiHistoryFile();
        if (!file.exists()) {
            return noHistory("AI");
        }
        String kw = keyword.toLowerCase();
        // 流式扫描：只保留最近 50 条命中（内存有界），输出时倒序
        List<ChatMessage> hits = new ArrayList<>();
        Gson gson = new Gson();
        try (JsonReader reader = new JsonReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            reader.beginArray();
            while (reader.hasNext()) {
                ChatMessage msg = gson.fromJson(reader, ChatMessage.class);
                if (msg == null) continue;
                String content = msg.getContent() != null ? msg.getContent() : "";
                if (content.toLowerCase().contains(kw)) {
                    if (hits.size() >= 50) {
                        hits.remove(0);
                    }
                    hits.add(msg);
                }
            }
            reader.endArray();
        } catch (Exception e) {
            AILogger.w(TAG, "search ai history failed: " + e.getMessage());
        }
        if (hits.isEmpty()) {
            return AIToolResult.fail("「AI 历史」中未找到包含『" + keyword + "』的消息");
        }
        StringBuilder sb = new StringBuilder("命中 " + hits.size() + " 条（倒序）:\n");
        int seq = hits.size();
        for (int i = hits.size() - 1; i >= 0; i--) {
            appendMessage(sb, hits.get(i), seq--);
            if (sb.length() > OUTPUT_MAX) {
                sb.append("\n...（已达输出上限，请用更精确的关键词）");
                break;
            }
        }
        Map<String, Object> info = new HashMap<>();
        info.put("source", "ai");
        info.put("hits", hits.size());
        return AIToolResult.success(sb.toString(), info);
    }

    // ==================== Agent 历史（online_agent_history_*.json，取最近会话） ====================

    /** 最近的 agent 历史文件（当前会话：最后写入的那个；无则 null） */
    private File latestAgentHistoryFile() {
        File dir = context.getFilesDir();
        File[] files = dir.listFiles((d, name) -> name.startsWith("online_agent_history")
                && name.endsWith(".json"));
        File best = null;
        long bestTime = -1;
        if (files != null) {
            for (File f : files) {
                if (f.lastModified() > bestTime) {
                    bestTime = f.lastModified();
                    best = f;
                }
            }
        }
        return best;
    }

    private int countAgentHistory() {
        File file = latestAgentHistoryFile();
        if (file == null) return 0;
        int count = 0;
        try (JsonReader reader = new JsonReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                reader.beginArray();
                while (reader.hasNext()) {
                    reader.skipValue();
                    count++;
                }
                reader.endArray();
            }
        } catch (Exception e) {
            AILogger.w(TAG, "count agent history failed: " + e.getMessage());
        }
        return count;
    }

    /** agent 历史尾部 limit 条（{role, content}；OpenAI 风格消息数组） */
    private AIToolResult recentAgentHistory(int limit) {
        File file = latestAgentHistoryFile();
        if (file == null) {
            return noHistory("Agent");
        }
        List<String[]> window = new ArrayList<>();   // ring：只保留最后 limit 条
        Gson gson = new Gson();
        try (JsonReader reader = new JsonReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            reader.beginArray();
            while (reader.hasNext()) {
                JsonObject msg = gson.fromJson(reader, JsonObject.class);
                if (msg == null) continue;
                String role = msg.has("role") && !msg.get("role").isJsonNull()
                        ? msg.get("role").getAsString() : "";
                String content = msg.has("content") && !msg.get("content").isJsonNull()
                        ? msg.get("content").getAsString() : "";
                if (window.size() >= limit) {
                    window.remove(0);
                }
                window.add(new String[]{role, content});
            }
            reader.endArray();
        } catch (Exception e) {
            AILogger.w(TAG, "load agent history failed: " + e.getMessage());
        }
        int total = countMessages(file);
        if (total == 0) {
            return noHistory("Agent");
        }
        StringBuilder sb = new StringBuilder("「Agent 对话历史」最近 ")
                .append(window.size()).append(" 条（共 ").append(total).append(" 条，倒序）:\n");
        for (int i = window.size() - 1; i >= 0; i--) {
            String[] m = window.get(i);
            appendRoleContent(sb, m[0], m[1], window.size() - i);
            if (sb.length() > OUTPUT_MAX) {
                sb.append("\n...（已达输出上限，可用 search 精确查找）");
                break;
            }
        }
        Map<String, Object> info = new HashMap<>();
        info.put("source", "agent");
        info.put("total", total);
        return AIToolResult.success(sb.toString(), info);
    }

    /** agent 历史流式搜索（只保留最近 50 条命中） */
    private AIToolResult searchAgentHistory(String keyword) {
        File file = latestAgentHistoryFile();
        if (file == null) {
            return noHistory("Agent");
        }
        String kw = keyword.toLowerCase();
        List<String[]> hits = new ArrayList<>();
        Gson gson = new Gson();
        try (JsonReader reader = new JsonReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            reader.beginArray();
            while (reader.hasNext()) {
                JsonObject msg = gson.fromJson(reader, JsonObject.class);
                if (msg == null) continue;
                String role = msg.has("role") && !msg.get("role").isJsonNull()
                        ? msg.get("role").getAsString() : "";
                String content = msg.has("content") && !msg.get("content").isJsonNull()
                        ? msg.get("content").getAsString() : "";
                if (content.toLowerCase().contains(kw)) {
                    if (hits.size() >= 50) {
                        hits.remove(0);
                    }
                    hits.add(new String[]{role, content});
                }
            }
            reader.endArray();
        } catch (Exception e) {
            AILogger.w(TAG, "search agent history failed: " + e.getMessage());
        }
        if (hits.isEmpty()) {
            return AIToolResult.fail("「Agent 历史」中未找到包含『" + keyword + "』的消息");
        }
        StringBuilder sb = new StringBuilder("命中 " + hits.size() + " 条（倒序）:\n");
        int seq = hits.size();
        for (int i = hits.size() - 1; i >= 0; i--) {
            String[] m = hits.get(i);
            appendRoleContent(sb, m[0], m[1], seq--);
            if (sb.length() > OUTPUT_MAX) {
                sb.append("\n...（已达输出上限，请用更精确的关键词）");
                break;
            }
        }
        Map<String, Object> info = new HashMap<>();
        info.put("source", "agent");
        info.put("hits", hits.size());
        return AIToolResult.success(sb.toString(), info);
    }

    // ==================== 公共 ====================

    /** 流式统计 JSON 数组中的元素个数（数组或 {messages:[...]} 对象两种形态都支持） */
    private int countMessages(File file) {
        if (file == null || !file.exists()) return 0;
        int count = 0;
        try (JsonReader reader = new JsonReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                reader.beginArray();
                while (reader.hasNext()) {
                    reader.skipValue();
                    count++;
                }
                reader.endArray();
            } else {
                reader.beginObject();
                while (reader.hasNext()) {
                    if ("messages".equals(reader.nextName())) {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            reader.skipValue();
                            count++;
                        }
                        reader.endArray();
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
            }
        } catch (Exception e) {
            AILogger.w(TAG, "count messages failed: " + e.getMessage());
        }
        return count;
    }

    private AIToolResult noHistory(String label) {
        Map<String, Object> info = new HashMap<>();
        info.put("source", "Agent".equals(label) ? "agent" : "ai");
        info.put("count", 0);
        return AIToolResult.success("「" + label + " 对话历史」暂无记录（0 条）", info);
    }

    /** 渲染 ChatMessage 列表（倒序输出） */
    private AIToolResult renderMessages(List<ChatMessage> history, int total, int limit, boolean agent) {
        StringBuilder sb = new StringBuilder("「").append(agent ? "Agent 对话历史" : "AI 对话历史")
                .append("」最近 ").append(Math.min(limit, history.size())).append(" 条（共 ")
                .append(total).append(" 条，倒序）:\n");
        int seq = history.size();
        for (int i = history.size() - 1; i >= 0; i--) {
            appendMessage(sb, history.get(i), seq--);
            if (sb.length() > OUTPUT_MAX) {
                sb.append("\n...（已达输出上限，可用 search 精确查找）");
                break;
            }
        }
        Map<String, Object> info = new HashMap<>();
        info.put("source", agent ? "agent" : "ai");
        info.put("total", total);
        return AIToolResult.success(sb.toString(), info);
    }

    private void appendMessage(StringBuilder sb, ChatMessage msg, int seq) {
        String role = msg.getRole() != null ? msg.getRole().trim().toLowerCase() : "";
        String content = msg.getContent() != null ? msg.getContent().trim() : "";
        appendRoleContent(sb, role, content, seq);
    }

    private void appendRoleContent(StringBuilder sb, String role, String content, int seq) {
        String roleLabel;
        if (role == null) role = "";
        switch (role) {
            case "user": roleLabel = "用户"; break;
            case "assistant": roleLabel = "AI"; break;
            case "tool": roleLabel = "工具"; break;
            case "system": roleLabel = "系统"; break;
            case "agent": roleLabel = "Agent"; break;
            default: roleLabel = role.isEmpty() ? "消息" : role;
        }
        if (content != null) {
            content = content.trim();
            if (content.length() > CONTENT_MAX) {
                content = content.substring(0, CONTENT_MAX) + "...（已截断）";
            }
        } else {
            content = "";
        }
        sb.append("[").append(seq).append("] ").append(roleLabel).append(": ")
                .append(content.isEmpty() ? "（无文本）" : content).append("\n");
    }
}
