package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.agent.online.AgentMemoryStore;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆工具：Agent 主动读写跨会话持久化的用户信息。
 *
 * 动作：
 * - save: 保存一条记忆（key + value）
 * - recall: 读取一条记忆（key）
 * - delete: 删除一条记忆（key）
 * - list: 列出所有记忆
 * - clear: 清空所有记忆（需确认）
 *
 * 记忆持久化在 agent_memory.json，跨对话/重启保留；
 * 每次 Agent 执行时会自动注入记忆摘要到系统提示词（无需手动调用）。
 */
public class MemoryTool implements AITool {

    private static final String TAG = "MemoryTool";
    private final Context context;

    public MemoryTool() {
        this.context = null;
    }

    public MemoryTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "memory";
    }

    @Override
    public String getDescription() {
        return "长期记忆：跨会话保存/读取/删除用户信息。仅在用户明确要求记住、或主动告知个人信息/偏好时 save（不要擅自把普通聊天内容存为记忆）；需要回忆历史信息时 recall；用户要求忘记某条记忆时 delete。action: save|recall|delete|list|clear";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作：save(保存记忆)|recall(读取)|delete(删除单条)|list(列出所有)|clear(清空)");
        params.put("key", "记忆键（如 user_name / preference_city），save/recall/delete 用");
        params.put("value", "记忆值（内容），save 用");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            if (context == null) {
                return AIToolResult.fail("记忆工具未初始化");
            }
            AgentMemoryStore store = AgentMemoryStore.getInstance(context);
            String action = parameters.get("action") != null
                    ? String.valueOf(parameters.get("action")) : "list";

            switch (action) {
                case "save": {
                    String key = parameters.get("key") != null ? String.valueOf(parameters.get("key")) : "";
                    String value = parameters.get("value") != null ? String.valueOf(parameters.get("value")) : "";
                    if (key.trim().isEmpty() || value.trim().isEmpty()) {
                        return AIToolResult.fail("save 需要 key 和 value 参数");
                    }
                    boolean replaced = store.get(key.trim()) != null;
                    boolean ok = store.save(key.trim(), value.trim());
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", ok ? "saved" : "failed");
                    result.put("key", key.trim());
                    result.put("replaced", replaced);
                    result.put("total", store.size());
                    result.put("message", !ok ? "保存失败"
                            : (replaced ? "已更新记忆: " + key.trim() : "已保存记忆: " + key.trim()));
                    return AIToolResult.success(result);
                }
                case "recall": {
                    String key = parameters.get("key") != null ? String.valueOf(parameters.get("key")) : "";
                    if (key.trim().isEmpty()) {
                        return AIToolResult.fail("recall 需要 key 参数");
                    }
                    String value = store.get(key.trim());
                    Map<String, Object> result = new HashMap<>();
                    result.put("key", key.trim());
                    result.put("found", value != null);
                    result.put("value", value != null ? value : "");
                    result.put("message", value != null ? "记忆内容: " + value : "未找到该记忆");
                    return AIToolResult.success(result);
                }
                case "delete": {
                    String key = parameters.get("key") != null ? String.valueOf(parameters.get("key")) : "";
                    if (key.trim().isEmpty()) {
                        return AIToolResult.fail("delete 需要 key 参数");
                    }
                    boolean existed = store.get(key.trim()) != null;
                    store.remove(key.trim());
                    Map<String, Object> result = new HashMap<>();
                    result.put("key", key.trim());
                    result.put("deleted", existed);
                    result.put("total", store.size());
                    result.put("message", existed ? "已删除记忆: " + key.trim() : "未找到该记忆: " + key.trim());
                    return AIToolResult.success(result);
                }
                case "clear": {
                    store.clear();
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "cleared");
                    result.put("message", "已清空所有记忆");
                    return AIToolResult.success(result);
                }
                case "list":
                default: {
                    List<AgentMemoryStore.MemoryEntry> all = store.getAll();
                    Map<String, Object> result = new HashMap<>();
                    result.put("count", all.size());
                    List<Map<String, String>> items = new ArrayList<>();
                    StringBuilder summary = new StringBuilder();
                    for (AgentMemoryStore.MemoryEntry e : all) {
                        Map<String, String> item = new HashMap<>();
                        item.put("key", e.key);
                        item.put("value", e.value);
                        items.add(item);
                        if (summary.length() > 0) summary.append("\n");
                        summary.append(e.key).append(": ").append(e.value);
                    }
                    result.put("memories", items);
                    result.put("message", all.isEmpty() ? "暂无记忆" : "记忆列表:\n" + summary);
                    return AIToolResult.success(result);
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Memory tool error: " + e.getMessage(), e);
            return AIToolResult.fail("记忆工具出错: " + e.getMessage());
        }
    }
}
