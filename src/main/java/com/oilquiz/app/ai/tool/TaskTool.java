package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务状态工具（维度四 P0-1：多轮对话任务状态跟踪）。
 *
 * Agent 显式维护跨轮任务清单：
 * - add: 新建任务（默认进行中）
 * - update: 更新描述/状态/进度
 * - complete: 标记完成
 * - fail: 标记失败
 * - delete: 删除任务
 * - list: 查看任务清单（支持按状态过滤）
 *
 * 使用边界（防滥用）：
 * - 仅跟踪"需要多轮/多步完成"的任务；一次性问答、单步操作不建任务
 * - 完成/失败后任务自动从注入摘要移除，不再占用上下文
 * - 任务清单每次 Agent 执行自动注入系统提示词，无需手动查看即可保持连续
 */
@Tool(value = "task", category = "memory")
public class TaskTool implements AITool {

    private static final String TAG = "TaskTool";
    private final Context context;

    public TaskTool() {
        this.context = null;
    }

    public TaskTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "task";
    }

    @Override
    public String getDescription() {
        return "任务清单：跟踪跨多轮的多步任务状态（进行中/完成/待办/失败）。用户布置多步任务时 add（如\"帮我整理复习资料并生成 PDF\"→add description=整理复习资料并生成PDF）；任务推进时 update 进度；某步完成时 complete；任务无法完成时 fail；用户取消时 delete。任务清单每轮自动注入提示词，跨轮保持。使用边界：一次性问答、单步操作不建任务；同一任务的多个步骤合并为一条任务，不逐步骤建任务。action: add|update|complete|fail|delete|list";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作：add(新建)|update(更新)|complete(完成)|fail(失败)|delete(删除)|list(查看)");
        params.put("id", "任务ID（update/complete/fail/delete 用；add 时由系统生成）");
        params.put("description", "任务描述（add 必填；update 可选）");
        params.put("status", "状态（update 用：in_progress|completed|todo|failed）");
        params.put("progress", "进度百分比 0-100（update 用，可选）");
        params.put("filter", "list 过滤（可选：in_progress|completed|todo|failed）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            if (context == null) {
                return AIToolResult.fail("任务工具未初始化");
            }
            TaskStateTracker tracker = TaskStateTracker.getInstance(context);
            String action = parameters.get("action") != null
                    ? String.valueOf(parameters.get("action")).trim().toLowerCase() : "list";

            switch (action) {
                case "add": {
                    String description = parameters.get("description") != null
                            ? String.valueOf(parameters.get("description")) : "";
                    if (description.trim().isEmpty()) {
                        return AIToolResult.fail("add 需要 description 参数");
                    }
                    TaskStateTracker.TaskEntry entry = tracker.add(description.trim());
                    if (entry == null) {
                        return AIToolResult.fail("任务新建失败（已达上限且无可归档任务）", null);
                    }
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "added");
                    result.put("id", entry.id);
                    result.put("description", entry.description);
                    result.put("taskState", entry.status);
                    result.put("total", tracker.size());
                    result.put("message", "已新建任务: " + entry.description);
                    return AIToolResult.success(result);
                }
                case "update": {
                    String id = parameters.get("id") != null ? String.valueOf(parameters.get("id")) : "";
                    if (id.trim().isEmpty()) {
                        return AIToolResult.fail("update 需要 id 参数");
                    }
                    String description = parameters.get("description") != null
                            ? String.valueOf(parameters.get("description")) : null;
                    String status = parameters.get("status") != null
                            ? String.valueOf(parameters.get("status")) : null;
                    int progress = parameters.get("progress") != null
                            ? Integer.parseInt(String.valueOf(parameters.get("progress"))) : -1;
                    boolean ok = tracker.update(id.trim(), description, status, progress);
                    if (!ok) {
                        return AIToolResult.fail("未找到任务: " + id.trim());
                    }
                    TaskStateTracker.TaskEntry e = tracker.get(id.trim());
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "updated");
                    result.put("id", id.trim());
                    result.put("taskState", e != null ? e.status : "");
                    result.put("progress", e != null ? e.progress : 0);
                    result.put("message", "已更新任务: " + (e != null ? e.description : id.trim()));
                    return AIToolResult.success(result);
                }
                case "complete": {
                    String id = parameters.get("id") != null ? String.valueOf(parameters.get("id")) : "";
                    if (id.trim().isEmpty()) {
                        return AIToolResult.fail("complete 需要 id 参数");
                    }
                    boolean ok = tracker.complete(id.trim());
                    if (!ok) {
                        return AIToolResult.fail("未找到任务: " + id.trim());
                    }
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "completed");
                    result.put("id", id.trim());
                    result.put("message", "任务已完成: " + id.trim());
                    return AIToolResult.success(result);
                }
                case "fail": {
                    String id = parameters.get("id") != null ? String.valueOf(parameters.get("id")) : "";
                    if (id.trim().isEmpty()) {
                        return AIToolResult.fail("fail 需要 id 参数");
                    }
                    boolean ok = tracker.fail(id.trim());
                    if (!ok) {
                        return AIToolResult.fail("未找到任务: " + id.trim());
                    }
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "failed");
                    result.put("id", id.trim());
                    result.put("message", "任务已标记失败: " + id.trim());
                    return AIToolResult.success(result);
                }
                case "delete": {
                    String id = parameters.get("id") != null ? String.valueOf(parameters.get("id")) : "";
                    if (id.trim().isEmpty()) {
                        return AIToolResult.fail("delete 需要 id 参数");
                    }
                    boolean existed = tracker.delete(id.trim());
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "deleted");
                    result.put("id", id.trim());
                    result.put("deleted", existed);
                    result.put("total", tracker.size());
                    result.put("message", existed ? "已删除任务: " + id.trim() : "未找到任务: " + id.trim());
                    return AIToolResult.success(result);
                }
                case "list":
                default: {
                    String filter = parameters.get("filter") != null
                            ? String.valueOf(parameters.get("filter")) : null;
                    String statusFilter = (filter != null && !filter.trim().isEmpty())
                            ? TaskStateTracker.normalizeStatus(filter.trim()) : null;
                    List<TaskStateTracker.TaskEntry> all = tracker.getAll();
                    List<Map<String, Object>> items = new ArrayList<>();
                    StringBuilder summary = new StringBuilder();
                    int active = 0;
                    for (TaskStateTracker.TaskEntry e : all) {
                        if (statusFilter != null && !statusFilter.equals(e.status)) continue;
                        String state = "completed".equals(e.status) ? "完成"
                                : "in_progress".equals(e.status) ? "进行中"
                                : "failed".equals(e.status) ? "失败" : "待办";
                        Map<String, Object> item = new HashMap<>();
                        item.put("id", e.id);
                        item.put("description", e.description);
                        item.put("status", e.status);
                        item.put("progress", e.progress);
                        items.add(item);
                        if (summary.length() > 0) summary.append("\n");
                        summary.append("- ").append(e.id).append(" [").append(state)
                                .append(e.progress > 0 ? " " + e.progress + "%" : "")
                                .append("] ").append(e.description);
                        if ("in_progress".equals(e.status) || "todo".equals(e.status)) active++;
                    }
                    Map<String, Object> result = new HashMap<>();
                    result.put("count", items.size());
                    result.put("activeCount", active);
                    result.put("tasks", items);
                    result.put("message", items.isEmpty()
                            ? (statusFilter != null ? "无[" + statusFilter + "]任务" : "暂无任务")
                            : "任务清单:\n" + summary);
                    return AIToolResult.success(result);
                }
            }
        } catch (NumberFormatException e) {
            return AIToolResult.fail("progress 参数必须为 0-100 的整数");
        } catch (Exception e) {
            AILogger.e(TAG, "Task tool error: " + e.getMessage(), e);
            return AIToolResult.fail("任务工具出错: " + e.getMessage());
        }
    }
}
