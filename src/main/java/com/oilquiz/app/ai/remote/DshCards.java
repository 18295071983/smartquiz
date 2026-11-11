package com.oilquiz.app.ai.remote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DSH 事件 → 聊天页卡片（{@code ChatMessage.components} 的一种）。
 *
 * <p>这里只回答一个问题：<b>这条事件该变成哪张卡、卡里的 props 长什么样</b>。
 * 不碰 View、不碰消息、不碰线程 —— 所以它能被单测直接覆盖（这是旧实现最缺的东西：
 * 卡片文案/字段全埋在 Activity 的 if-else 里，只能靠真机看）。</p>
 *
 * <p>返回 {@code null} 表示"这条事件不产卡"（不是所有事件都该有 UI）。卡片带一个稳定
 * {@link Card#key}：同 key 覆盖式更新（待办会反复写、后台任务要按 turn 聚合），
 * 渲染层据此发号并做增量 diff。</p>
 */
public final class DshCards {

    /** 一张待渲染的卡：逻辑键 + 组件类型 + props */
    public static final class Card {
        public final String key;
        public final String type;
        public final JSONObject props;

        public Card(String key, String type, JSONObject props) {
            this.key = key;
            this.type = type;
            this.props = props != null ? props : new JSONObject();
        }
    }

    /** 每个 turn 的后台任务计数（聚合成一张卡，别一个任务一张） */
    private final Map<Integer, Integer> jobCount = new LinkedHashMap<>();
    /** 工作流运行状态：runId → 名称 + 各子任务（真机事件 tool-workflow/*） */
    private final Map<String, WfRun> workflows = new LinkedHashMap<>();

    private static final class WfRun {
        String name = "工作流";
        final LinkedHashMap<Integer, JSONObject> steps = new LinkedHashMap<>();
    }

    /** 事件类型是否由卡片映射消费（供 reducer 统计"未识别事件"，别把卡片事件算成没人管） */
    public static boolean handles(String type) {
        return "todo/write".equals(type)
                || "deliverables/presented".equals(type)
                || "goal/activation-changed".equals(type)
                || "workspace/changes".equals(type)
                || "job/registered".equals(type)
                || "command/run".equals(type)
                || "tool-workflow/run-start".equals(type)
                || "tool-workflow/agent-start".equals(type)
                || "tool-workflow/agent-end".equals(type)
                || "tool-workflow/run-end".equals(type)
                || "subagent/catalog".equals(type)
                || "llm/retry".equals(type)
                || "assistant/attempt".equals(type)
                || "compaction/start".equals(type)
                || "compaction/summary".equals(type);
    }

    /**
     * 事件 → 卡片。
     *
     * @param turn 事件所属 turn（无位置事件由 reducer 补齐后的值）
     */
    public Card map(DshEvent ev, int turn) {
        if (ev == null || ev.data == null) return null;
        JSONObject data = ev.data;
        switch (ev.type) {
            case "todo/write": {
                JSONObject props = obj("title", "待办");
                JSONArray items = new JSONArray();
                JSONArray todos = data.optJSONArray("todos");
                if (todos != null) {
                    for (int i = 0; i < todos.length(); i += 1) {
                        JSONObject t = todos.optJSONObject(i);
                        if (t == null) continue;
                        add(items, obj("text", t.optString("content"),
                                "done", "completed".equals(t.optString("status"))));
                    }
                }
                put(props, "items", items);
                return new Card("todo-" + turn, "todo_card", props);
            }
            case "deliverables/presented": {
                JSONObject props = obj("title", "本轮产物");
                JSONArray files = new JSONArray();
                JSONArray srcs = data.optJSONArray("files");
                if (srcs != null) {
                    for (int i = 0; i < srcs.length(); i += 1) {
                        JSONObject f = srcs.optJSONObject(i);
                        if (f == null) continue;
                        String path = f.optString("path");
                        if (path.isEmpty()) continue;
                        add(files, obj("path", path, "name", baseName(path), "type", "file"));
                    }
                }
                put(props, "files", files);
                return new Card("files-" + turn, "file_list", props);
            }
            case "goal/activation-changed": {
                JSONObject goal = data.optJSONObject("goal");
                String text = goal != null
                        ? goal.optString("text", goal.optString("title", ""))
                        : data.optString("text", "");
                if (text.isEmpty()) return null;
                return new Card("goal-" + turn, "alert",
                        obj("title", "当前目标", "content", text, "level", "info"));
            }
            case "workspace/changes":
                // 事件只有 {turn}，明细在电脑端 —— 如实说明，不假装手机上有内容
                return new Card("changes-" + turn, "alert", obj("title", "文件变更",
                        "content", "本轮在电脑工作区有文件改动（明细见电脑端）", "level", "info"));
            case "job/registered": {
                String label = data.optString("label", data.optString("kind", "后台任务"));
                int n = jobCount.merge(turn, 1, Integer::sum);
                String content = n <= 1 ? label : (n + " 个后台任务，最近：" + label);
                return new Card("jobs-" + turn, "alert",
                        obj("title", "后台任务", "content", content, "level", "info"));
            }
            case "command/run":
                return new Card("cmd-" + data.optString("commandId", String.valueOf(turn)), "alert",
                        obj("title", "执行命令",
                                "content", (data.optString("name") + " " + data.optString("args")).trim(),
                                "level", "info"));
            case "tool-workflow/run-start": {
                String runId = data.optString("runId");
                if (runId.isEmpty()) return null;
                WfRun run = new WfRun();
                run.name = data.optString("name", "工作流");
                workflows.put(runId, run);
                return workflowCard(runId, run);
            }
            case "tool-workflow/agent-start": {
                String runId = data.optString("runId");
                WfRun run = workflows.get(runId);
                if (run == null) return null;
                int seq = data.optInt("seq", run.steps.size() + 1);
                String phase = data.optString("phase", "");
                String label = data.optString("label", "子任务");
                run.steps.put(seq, obj("title", phase.isEmpty() ? label : (phase + " · " + label),
                        "status", "current"));
                return workflowCard(runId, run);
            }
            case "tool-workflow/agent-end": {
                String runId = data.optString("runId");
                WfRun run = workflows.get(runId);
                if (run == null) return null;
                JSONObject step = run.steps.get(data.optInt("seq", -1));
                if (step != null) {
                    put(step, "status",
                            "completed".equals(data.optString("outcome", "completed")) ? "done" : "failed");
                }
                return workflowCard(runId, run);
            }
            case "tool-workflow/run-end": {
                String runId = data.optString("runId");
                WfRun run = workflows.get(runId);
                if (run == null) return null;
                String stop = data.optString("stopReason", "completed");
                for (JSONObject step : run.steps.values()) {
                    if ("current".equals(step.optString("status"))) {
                        put(step, "status", "completed".equals(stop) ? "done" : "failed");
                    }
                }
                Card done = workflowCard(runId, run);
                workflows.remove(runId);
                return done;
            }
            case "subagent/catalog": {
                String label = data.optString("label", "子代理");
                String mode = data.optString("mode", "");
                String content = label + ("continuable".equals(mode) ? "（可续跑）" : "");
                return new Card("subagent-" + data.optString("childId", String.valueOf(turn)), "alert",
                        obj("title", "子代理", "content", content, "level", "info"));
            }
            case "llm/retry": {
                JSONObject failure = data.optJSONObject("failure");
                String code = failure == null ? "" : failure.optString("code", "");
                int retry = data.optInt("retry", 0);
                int max = data.optInt("maxRetries", 0);
                String content = "第 " + retry + (max > 0 ? "/" + max : "") + " 次"
                        + (code.isEmpty() ? "" : "（" + code + "）") + "，稍后重试";
                return new Card("retry-" + turn, "alert",
                        obj("title", "模型重试", "content", content, "level", "warning"));
            }
            case "assistant/attempt":
                return attemptFailureCard(data, turn);
            case "compaction/start":
                return new Card("compact-" + data.optString("compactionId", String.valueOf(turn)),
                        "alert", obj("title", "上下文压缩", "content", "上下文压缩中…", "level", "info"));
            case "compaction/summary": {
                String key = "compact-" + data.optString("compactionId", String.valueOf(turn));
                String text = collectText(data.optJSONArray("summary"));
                if (text.isEmpty()) {
                    return new Card(key, "alert",
                            obj("title", "上下文压缩", "content", "上下文压缩中…", "level", "info"));
                }
                return new Card(key, "markdown_doc",
                        obj("title", "上下文压缩摘要", "content", text));
            }
            default:
                return null;
        }
    }

    /** 一次模型尝试失败：只认 stream[].chunk = {type:finish, reason:{kind:error, failure.message}} */
    private static Card attemptFailureCard(JSONObject data, int turn) {
        JSONArray stream = data.optJSONArray("stream");
        if (stream == null) return null;
        for (int i = 0; i < stream.length(); i += 1) {
            JSONObject item = stream.optJSONObject(i);
            JSONObject chunk = item == null ? null : item.optJSONObject("chunk");
            if (chunk == null || !"finish".equals(chunk.optString("type"))) continue;
            JSONObject reason = chunk.optJSONObject("reason");
            if (reason == null || !"error".equals(reason.optString("kind"))) continue;
            JSONObject failure = reason.optJSONObject("failure");
            String message = failure == null ? "" : failure.optString("message", "");
            if (message.isEmpty()) message = "模型调用失败";
            return new Card("fail-" + turn, "alert",
                    obj("title", "模型报错", "content", message, "level", "error"));
        }
        return null;
    }

    private static Card workflowCard(String runId, WfRun run) {
        JSONArray steps = new JSONArray();
        for (JSONObject step : run.steps.values()) add(steps, step);
        return new Card("wf-" + runId, "steps", obj("title", "工作流：" + run.name, "steps", steps));
    }

    /** content[{type,text}] 里正文部分的拼接（reasoning 不算正文）；实时转换与历史导入共用 */
    public static String collectText(JSONArray parts) {
        if (parts == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i += 1) {
            JSONObject p = parts.optJSONObject(i);
            if (p == null) continue;
            if ("reasoning".equals(p.optString("type"))) continue;
            sb.append(p.optString("text"));
        }
        return sb.toString().trim();
    }

    /** 计划模式状态卡（由 {@code plan/mode} 事件驱动） */
    public Card planCard(boolean active) {
        return new Card("plan", "alert", obj(
                "title", "计划模式",
                "content", active ? "已开启：模型先给计划，等你批准后再执行" : "已关闭",
                "level", "info"));
    }

    /** 子代理卡（同 key 覆盖：catalog 建卡、activity 更新最近动作） */
    public Card subagentCard(String childId, String label, String content) {
        String title = "子代理";
        if (label != null && !label.trim().isEmpty()) title += " · " + label.trim();
        return new Card("subagent-" + childId, "alert",
                obj("title", title, "content", content == null ? "" : content, "level", "info"));
    }
    public static String approvalTool(JSONObject request) {
        if (request == null) return "工具";
        String tool = request.optString("tool", request.optString("name", ""));
        return tool.trim().isEmpty() ? "工具" : tool.trim();
    }

    /** 审批请求的说明文案：summary / command / reason，兜底整段 JSON（截断） */
    public static String approvalDetail(JSONObject request) {
        if (request == null) return "";
        String detail = request.optString("summary",
                request.optString("command", request.optString("reason", "")));
        if (detail.isEmpty()) detail = request.toString();
        return detail.length() > 400 ? (detail.substring(0, 400) + "…") : detail;
    }

    static String baseName(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    // ---- JSON 小工具：org.json 的 put 在本工程会抛受检异常，坏字段不该炸整张卡 ----

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            put(o, String.valueOf(kv[i]), kv[i + 1]);
        }
        return o;
    }

    static JSONObject put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (Exception ignored) {
            // 坏字段跳过，整卡照渲染
        }
        return o;
    }

    static JSONArray add(JSONArray arr, Object value) {
        try {
            arr.put(value);
        } catch (Exception ignored) {
            // 同上
        }
        return arr;
    }
}
