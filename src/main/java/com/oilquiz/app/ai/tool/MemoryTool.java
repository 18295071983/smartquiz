package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.agent.online.AgentMemoryStore;
import com.oilquiz.app.ai.tool.annotation.Tool;
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
@Tool(value = "memory", category = "memory")
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
        return "长期记忆：跨会话保存/读取/删除用户信息，按分类管理。分类 category: fact(事实，如住址/单位)|preference(偏好，如称呼/口味)|context(情境，如当前项目)。用户主动告知姓名/称呼/偏好/常驻信息时主动 save（如\"我叫小明\"→save key=user_name value=小明 category=preference；\"我住在北京\"→save key=address value=北京 category=fact；\"我在做考研复习\"→save key=current_goal value=考研复习 category=context）；用户说\"记住...\"时 save。保存规则：只有稳定、跨会话有用的信息才存；一次性对话内容、临时情绪、可由上下文推导的信息不存。需要回忆历史信息时 recall；用户要求忘记某条记忆时 delete。action: save|recall|delete|list|clear";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作：save(保存记忆)|recall(读取)|delete(删除单条)|list(列出所有)|clear(清空)");
        params.put("key", "记忆键（如 user_name / preference_city），save/recall/delete 用");
        params.put("value", "记忆值（内容），save 用");
        params.put("category", "分类：fact(事实)|preference(偏好)|context(情境)，save 用；list 可按分类过滤。默认 fact");
        params.put("ttl", "可选，有效期秒数（save 用，>0 时记忆到期自动失效；不传则永不过期/沿用原TTL）。如临时情境 context 可设 3600");
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
                    if (value.trim().length() > 2048) {
                        return AIToolResult.fail("记忆内容过长（>" + 2048 + "字符），请精简后保存");
                    }
                    String category = parameters.get("category") != null
                            ? String.valueOf(parameters.get("category")) : null;
                    String cat = AgentMemoryStore.normalizeCategory(category);
                    long ttlSeconds = 0L;
                    if (parameters.get("ttl") != null) {
                        try {
                            ttlSeconds = Long.parseLong(String.valueOf(parameters.get("ttl")));
                            if (ttlSeconds < 0) ttlSeconds = 0L;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    boolean replaced = store.get(key.trim()) != null;
                    boolean ok = store.save(key.trim(), value.trim(), cat, ttlSeconds);
                    if (!ok) {
                        return AIToolResult.fail("记忆保存失败（存储异常）", null);
                    }
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "saved");
                    result.put("key", key.trim());
                    result.put("category", cat);
                    result.put("ttlSeconds", ttlSeconds);
                    result.put("replaced", replaced);
                    result.put("total", store.size());
                    result.put("message", (replaced ? "已更新" : "已保存") + "[" + cat + "]记忆: " + key.trim()
                            + (ttlSeconds > 0 ? "（" + ttlSeconds + "秒后自动失效）" : ""));
                    return AIToolResult.success(result);
                }
                case "recall": {
                    String key = parameters.get("key") != null ? String.valueOf(parameters.get("key")) : "";
                    if (key.trim().isEmpty()) {
                        return AIToolResult.fail("recall 需要 key 参数");
                    }
                    AgentMemoryStore.MemoryEntry entry = store.getEntry(key.trim());
                    Map<String, Object> result = new HashMap<>();
                    result.put("key", key.trim());
                    result.put("found", entry != null);
                    result.put("category", entry != null ? entry.category : "");
                    result.put("value", entry != null ? entry.value : "");
                    result.put("message", entry != null
                            ? "[" + entry.category + "]记忆内容: " + entry.value : "未找到该记忆");
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
                    // 清空不可恢复，需显式 confirm=true 确认（防 prompt 注入误触）
                    Object confirmObj = parameters.get("confirm");
                    boolean confirm = confirmObj instanceof Boolean ? (Boolean) confirmObj
                            : Boolean.parseBoolean(String.valueOf(confirmObj));
                    if (!confirm) {
                        Map<String, Object> info = new HashMap<>();
                        info.put("requiresConfirm", true);
                        return AIToolResult.fail("清空所有记忆不可恢复，如需清空请传 confirm=true 再次调用", info);
                    }
                    store.clear();
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "cleared");
                    result.put("message", "已清空所有记忆");
                    return AIToolResult.success(result);
                }
                case "list":
                default: {
                    // 支持 category 过滤（维度三 P0-1：按分类查看）
                    String category = parameters.get("category") != null
                            ? String.valueOf(parameters.get("category")) : null;
                    List<AgentMemoryStore.MemoryEntry> all = (category != null && !category.trim().isEmpty())
                            ? store.getByCategory(category.trim()) : store.getAll();
                    Map<String, Object> result = new HashMap<>();
                    result.put("count", all.size());
                    result.put("filterCategory", (category != null && !category.trim().isEmpty())
                            ? AgentMemoryStore.normalizeCategory(category) : "");
                    List<Map<String, String>> items = new ArrayList<>();
                    StringBuilder summary = new StringBuilder();
                    for (AgentMemoryStore.MemoryEntry e : all) {
                        Map<String, String> item = new HashMap<>();
                        item.put("key", e.key);
                        item.put("value", e.value);
                        item.put("category", e.category);
                        if (e.ttlSeconds > 0) {
                            long remain = e.ttlSeconds - (System.currentTimeMillis() - e.updatedAt) / 1000L;
                            item.put("ttl", Math.max(0, remain) + "s");
                        }
                        items.add(item);
                        if (summary.length() > 0) summary.append("\n");
                        summary.append("[").append(e.category).append("] ").append(e.key).append(": ").append(e.value)
                                .append(e.ttlSeconds > 0 ? "（剩余" + Math.max(0, e.ttlSeconds
                                        - (System.currentTimeMillis() - e.updatedAt) / 1000L) + "s）" : "");
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
