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
    /**
     * 事件类型是否由卡片映射消费（供 reducer 统计"未识别事件"，别把卡片事件算成没人管）。
     *
     * <p>2026-11-14 用户口径：**瞬时状态放插槽，不放消息流**。下面这些类型不再出聊天卡，
     * 统一由插件 {@code ui/slot} 帧驱动甲板插槽（收到事件更新、完成即消失），
     * 因此改由 {@code DshTimeline.IGNORED_TYPES} 登记、这里返回 false：
     * {@code job/*}、{@code compaction/*}、{@code llm/retry*}、{@code goal/*}、
     * {@code permission/preset}、{@code sandbox/mode}、{@code approval/policy}、
     * {@code agent-preset/selected}、{@code model/selection}、{@code agent/inbox/spliced}。</p>
     */    public static boolean handles(String type) {
        return "todo/write".equals(type)
                || "deliverables/presented".equals(type)
                || "workspace/changes".equals(type)
                || "command/run".equals(type)
                || "command/done".equals(type)
                || "tool-workflow/run-start".equals(type)
                || "tool-workflow/agent-start".equals(type)
                || "tool-workflow/agent-end".equals(type)
                || "tool-workflow/run-end".equals(type)
                // 2026-11-13：subagent/catalog 不再出提示卡（子代理改由甲板轨迹卡承载）
                || "subagent/descriptor".equals(type)
                || "assistant/attempt".equals(type)
                || "working-directory/change".equals(type)
                // B14（2026-11-13）：这些类型此前只是"登记在目录里"，手机端完全不渲染。
                || "system/message".equals(type)
                || "developer/message".equals(type)
                || "feedback/message-put".equals(type)
                || "feedback/message-delete".equals(type)
                || "team/mode".equals(type)
                || "team/task".equals(type)
                || "team/member".equals(type)
                || "team/message/queued".equals(type)
                || "team/message/delivered".equals(type)
                // 2026-11-14：按 DSH known-event-types 补齐的 7 类
                || "tool/ptc-dispatch".equals(type)
                || "tool/ptc-dispatch-start".equals(type)
                || "hook/invoked".equals(type)
                || "hook/result".equals(type)
                || "image/offload".equals(type)
                || "model/selection".equals(type);
        // 2026-11-12：approval/asked 与 approval/decided 不再出"提示卡"。
        // 审批已经落成 approval part（带允许/拒绝按钮、绑定 toolCallId 钉在工具卡下面），
        // 再出一张同名 alert 卡就是同一件事画两遍，而且那张卡点不动（用户反馈"没绑定"）。
                // || "approval/asked".equals(type)
                // || "approval/decided".equals(type);
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
                return new Card("goal-" + turn, "alert_card",
                        obj("title", "当前目标", "text", text, "level", "info"));
            }
            case "workspace/changes":
                // 事件只有 {turn}，明细在电脑端 —— 如实说明，不假装手机上有内容
                return new Card("changes-" + turn, "alert_card", obj("title", "文件变更",
                        "text", "本轮在电脑工作区有文件改动（明细见电脑端）", "level", "info"));
            case "job/registered": {
                String label = data.optString("label", data.optString("kind", "后台任务"));
                // ★ 卡片 key 必须是**任务身份**，不能是 turn（2026-11-12）：
                //   以前按 "jobs-<turn>"，job/settled 若落在另一轮就覆盖不到，
                //   于是后台任务卡永远停在「运行中」（真机截图可复现）。
                String jobId = data.optString("jobId",
                        data.optString("id", data.optString("job_id", ""))).trim();
                String key = jobId.isEmpty() ? ("jobs-" + turn) : ("job-" + jobId);
                return new Card(key, "job",
                        obj("job_label", label, "title", label, "status", "running"));
            }
            case "command/run":
                // 命令执行 = 终端命令卡（等宽 + 状态灯），而不是“执行命令”提示卡
                return new Card("cmd-" + data.optString("commandId", String.valueOf(turn)), "code_card",
                        obj("command", (data.optString("name") + " " + data.optString("args")).trim(),
                                "status", "running"));
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
            case "llm/retry": {
                JSONObject failure = data.optJSONObject("failure");
                String code = failure == null ? "" : failure.optString("code", "");
                int retry = data.optInt("retry", 0);
                int max = data.optInt("maxRetries", 0);
                String content = "第 " + retry + (max > 0 ? "/" + max : "") + " 次"
                        + (code.isEmpty() ? "" : "（" + code + "）") + "，稍后重试";
                return new Card("retry-" + turn, "alert_card",
                        obj("title", "模型重试", "text", content, "level", "warning"));
            }
            case "assistant/attempt":
                return attemptFailureCard(data, turn);
            case "compaction/start":
                return new Card("compact-" + data.optString("compactionId", String.valueOf(turn)),
                        "alert_card", obj("title", "上下文压缩", "text", "上下文压缩中…", "level", "info"));
            case "compaction/summary": {
                String key = "compact-" + data.optString("compactionId", String.valueOf(turn));
                String text = collectText(data.optJSONArray("summary"));
                if (text.isEmpty()) {
                    return new Card(key, "alert_card",
                            obj("title", "上下文压缩", "text", "上下文压缩中…", "level", "info"));
                }
                return new Card(key, "markdown_doc",
                        obj("title", "上下文压缩摘要", "content", text));
            }
            // B14（2026-11-13）：目录里登记过、手机端却完全没渲染的类型
            case "system/message": {
                String text = messageText(data);
                if (text.isEmpty()) return null;
                return new Card("system-" + turn, "alert_card",
                        obj("title", "系统消息", "content", text, "level", "info"));
            }
            case "developer/message": {
                String text = messageText(data);
                if (text.isEmpty()) return null;
                if (text.length() > 240) text = text.substring(0, 240) + "…";
                return new Card("developer-" + turn, "alert_card",
                        obj("title", "开发者指令", "content", text, "level", "info"));
            }
            case "feedback/message-put":
                return new Card("feedback-put-" + turn, "alert_card",
                        obj("title", "消息已更新", "text", "电脑端改动了历史消息（以电脑端为准）", "level", "info"));
            case "feedback/message-delete":
                return new Card("feedback-del-" + turn, "alert_card",
                        obj("title", "消息已删除", "text", "电脑端删除了历史消息（以电脑端为准）", "level", "warning"));
            case "team/mode": {
                String mode = data.optString("mode", data.optString("value", ""));
                JSONObject props = obj("title", "团队模式",
                        "text", mode.isEmpty() ? "已切换" : mode, "level", "info");
                put(props, "actions", actions("看电脑面板", "insight"));
                return new Card("team-mode", "alert_card", props);
            }
            case "team/task": {
                String title = data.optString("title",
                        data.optString("subject", data.optString("taskId", "团队任务")));
                String status = data.optString("status", "");
                return new Card("team-task-" + data.optString("taskId", title), "alert_card",
                        obj("title", "团队任务",
                                "text", status.isEmpty() ? title : (title + " · " + status),
                                "level", "info"));
            }
            case "team/member": {
                String name = data.optString("name", data.optString("label", "成员"));
                return new Card("team-member-" + name, "alert_card",
                        obj("title", "团队成员", "text", name, "level", "info"));
            }
            case "team/message/queued":
            case "team/message/delivered": {
                String text = messageText(data);
                return new Card("team-msg-" + turn, "alert_card",
                        obj("title", "团队消息", "text", text.isEmpty() ? "成员间消息" : text, "level", "info"));
            }
            case "goal/change": {
                JSONObject goal = data.optJSONObject("goal");
                String text = goal != null
                        ? goal.optString("text", goal.optString("title", ""))
                        : data.optString("text", "");
                String op = data.optString("operation", "");
                int rounds = data.optInt("roundsStarted", 0);
                StringBuilder sb = new StringBuilder(text.isEmpty() ? "目标状态变更" : text);
                if (!op.isEmpty()) sb.append("（").append(op).append("）");
                if (rounds > 0) sb.append(" · 已进行 ").append(rounds).append(" 轮");
                return new Card("goal-state", "alert_card",
                        obj("title", "当前目标", "content", sb.toString(), "level", "info"));
            }
            case "job/settled": {
                String label = data.optString("label", data.optString("kind", "后台任务"));
                String status = data.optString("status", "");
                boolean bad = "failed".equals(status) || "error".equals(status) || "cancelled".equals(status);
                // 与 job/registered 用同一个 key（任务身份，不是 turn）：
                // 任务卡从"运行中"原地变成"已完成/失败"，跨轮也能对上。
                String jobId = data.optString("jobId",
                        data.optString("id", data.optString("job_id", ""))).trim();
                String key = jobId.isEmpty() ? ("jobs-" + turn) : ("job-" + jobId);
                return new Card(key, "job",
                        obj("job_label", label, "title", label,
                                "status", status.isEmpty() ? (bad ? "failed" : "success") : status));
            }
            case "command/done": {
                String text = data.optString("text", "");
                // 同一 commandId 的卡：命令卡原地补上输出，不再另起一张“命令完成”提示卡
                return new Card("cmd-" + data.optString("commandId", String.valueOf(turn)), "code_card",
                        obj("command", data.optString("command",
                                        (data.optString("name") + " " + data.optString("args")).trim()),
                                "status", data.optBoolean("failed", false) ? "failed" : "success",
                                "output", text));
            }
            case "compaction/prune": {
                int tokens = data.optInt("shadowedTokenCount", 0);
                return new Card("compact-prune", "alert_card",
                        obj("title", "上下文清理",
                                "content", tokens > 0 ? ("已清理约 " + tokens + " tokens 的历史") : "已清理部分历史",
                                "level", "info"));
            }
            case "compaction/end":
                return new Card("compact-" + data.optString("compactionId", String.valueOf(turn)), "alert_card",
                        obj("title", "上下文压缩", "content", "压缩完成", "level", "info"));
            case "working-directory/change": {
                String cwd = data.optString("cwd", "");
                return new Card("cwd", "alert_card",
                        obj("title", "工作目录", "content", cwd.isEmpty() ? "已切换" : cwd, "level", "info"));
            }
            case "sandbox/mode":
                permSandbox = data.optString("mode", "");
                return permissionStateCard();
            case "permission/preset":
                permPreset = data.optString("preset", "");
                if (permSandbox.isEmpty()) permSandbox = permPreset;
                return permissionStateCard();
            case "approval/policy":
                permPolicy = data.optString("policy", "");
                return permissionStateCard();
            case "agent-preset/selected": {
                String name = data.optString("agentPreset", data.optString("preset", ""));
                JSONObject props = obj("title", "智能体预设",
                        "content", name.isEmpty() ? "已切换" : name, "level", "info");
                put(props, "actions", actions("看电脑面板", "insight"));
                return new Card("agent-preset", "alert_card", props);
            }
            case "subagent/descriptor": {
                String label = data.optString("label", "子代理");
                String mode = data.optString("mode", "");
                return new Card("subagent-" + label, "alert_card",
                        obj("title", "子代理 · " + label,
                                "content", mode.isEmpty() ? "已创建" : ("模式：" + mode),
                                "level", "info"));
            }
            case "llm/retry-started":
                return new Card("retry-" + turn, "alert_card",
                        obj("title", "模型重试",
                                "content", "第 " + data.optInt("retry", 0) + " 次重试中…",
                                "level", "warning"));
            case "approval/asked": {
                String id = data.optString("id", String.valueOf(turn));
                String reason = data.optString("reason", "");
                String tool = data.optString("toolName", "工具");
                return new Card("approval-" + id, "alert_card",
                        obj("title", "需要审批 · " + tool,
                                "content", reason.isEmpty() ? "等待你的允许/拒绝" : reason,
                                "level", "warning"));
            }
            case "approval/decided": {
                String id = data.optString("id", String.valueOf(turn));
                String outcome = data.optString("outcome", "");
                return new Card("approval-" + id, "alert_card",
                        obj("title", "审批结果",
                                "content", "allowed".equals(outcome) || "allow".equals(outcome) ? "已允许" : "已拒绝",
                                "level", "info"));
            }
            // ===== 2026-11-14：按 DSH 官方 known-event-types 逐条补齐（原来这 7 类"不渲染也不报未知"）=====
            case "agent/inbox/spliced": {
                /**
                 * 插话/排队入队。{@code target=next-step} 就是"插话：下一个 step 生效"，
                 * {@code next-turn} 是"排队：本轮结束后发送"。内容在 {@code inserted[].content}。
                 * 手机自己发的消息会先进这条队列再被 claim；这张卡让"我插的话去哪了"可见。
                 */
                JSONObject props = obj("title", "插话已入队", "level", "info");
                String target = data.optString("target", "");
                String text = inboxText(data);
                String where = "next-step".equals(target) ? "电脑下一步就会看到"
                        : "next-turn".equals(target) ? "电脑本轮结束后发送"
                        : "已交给电脑";
                put(props, "content", text.isEmpty() ? where : (where + "：" + text));
                // 点击滚到末尾那条"正在电脑上执行/排队中"的气泡（ComponentHosts.Host.scrollToLast）
                put(props, "jump", "last");
                String key = "inbox-" + data.optString("messageId", String.valueOf(turn));
                return new Card(key, "alert_card", props);
            }
            case "tool/ptc-dispatch":
            case "tool/ptc-dispatch-start": {
                /**
                 * 程序化工具调用（PTC）本质就是一次工具调用 —— 复用内置的 `tool_call` 卡，
                 * 不再单独做提示卡（2026-11-14 组件管线审计）。
                 */
                String name = data.optString("name", data.optString("toolName", "工具"));
                String callId = data.optString("callId", data.optString("id", String.valueOf(turn)));
                String args = data.optString("arguments", data.optString("args", ""));
                return new Card("ptc-" + callId, "tool_call",
                        obj("toolCallId", callId, "toolName", name,
                                "args", args, "status", "running"));
            }
            case "hook/invoked":
            case "hook/result": {
                String name = data.optString("name", data.optString("hook", "钩子"));
                boolean done = "hook/result".equals(ev.type);
                String detail = done ? data.optString("text", "") : data.optString("event", "");
                return new Card("hook-" + name, "alert_card",
                        obj("title", "钩子 · " + name,
                                "content", done ? (detail.isEmpty() ? "已执行完" : detail) : "触发执行",
                                "level", "info"));
            }
            case "image/offload":
                return new Card("offload-" + turn, "alert_card",
                        obj("title", "图片已移出上下文",
                                "content", "为省 token 把历史图片挪到会话附件里，画面不受影响",
                                "level", "info"));
            case "model/selection": {
                String model = data.optString("model", data.optString("modelId", ""));
                return new Card("model-sel", "alert_card",
                        obj("title", "模型", "content", model.isEmpty() ? "已切换" : model, "level", "info"));
            }
            default:
                return null;
        }
    }

    /**
     * 未知事件类型的**动态兜底**（2026-11-14）。
     *
     * <p>DSH 升级后新增的事件类型，手机端不该"什么都看不到"，也不该为此发版：
     * 只要事件里带可读内容（text/content/message/items），就产出一张 {@code dynamic} 卡 ——
     * 由 {@code ComponentRegistry} 落到 {@code DynamicCardView}（标题 + 正文 + items +
     * 「查看原始 JSON」折叠区）。协议类事件（request/header、retry 元数据等）没有可读内容，
     * 自然被过滤，不会刷屏。</p>
     *
     * <p>同一类型每轮只出一张（key 含类型 + turn，原地覆盖），避免一个事件刷十几张卡。</p>
     */
    public Card mapUnknown(DshEvent ev, int turn) {
        if (ev == null || ev.data == null) return null;
        JSONObject data = ev.data;
        String text = null;
        for (String k : new String[]{"text", "content", "description", "summary", "detail"}) {
            String v = data.optString(k, "").trim();
            if (!v.isEmpty()) { text = v; break; }
        }
        JSONArray items = data.optJSONArray("items");
        if (text == null || text.trim().isEmpty()) {
            JSONObject msg = data.optJSONObject("message");
            if (msg != null) {
                String t = collectText(msg.optJSONArray("content"));
                if (!t.trim().isEmpty()) text = t;
            }
        }
        boolean hasItems = items != null && items.length() > 0;
        if ((text == null || text.trim().isEmpty()) && !hasItems) return null;

        String type = String.valueOf(ev.type);
        JSONObject props = obj("title", chineseTypeName(type), "type", "unknown_event",
                "event_type", type);
        if (text != null && !text.trim().isEmpty()) put(props, "content", text.trim());
        if (hasItems) put(props, "items", items);
        put(props, "level", "info");
        return new Card("unknown-" + type + "-" + turn, "dynamic", props);
    }

    /** 已知事件的中文名（未知的就原样给类型名，配"电脑事件"前缀，避免英文裸奔） */
    private static String chineseTypeName(String type) {
        switch (String.valueOf(type)) {
            case "team/task": return "团队任务";
            case "team/member": return "团队成员";
            case "team/mode": return "团队模式";
            case "team/message/queued": return "团队消息（排队）";
            case "team/message/delivered": return "团队消息（送达）";
            case "system/message": return "系统消息";
            case "developer/message": return "开发者消息";
            case "hook/invoked": return "钩子触发";
            case "hook/result": return "钩子结果";
            case "image/offload": return "图片移出上下文";
            case "feedback/message-put": return "消息被编辑";
            case "feedback/message-delete": return "消息被删除";
            case "feedback/record": return "反馈记录";
            case "workspace/changes": return "工作区变更";
            case "working-directory/change": return "工作目录变更";
            default: return "电脑事件 · " + type;
        }
    }
    /** 从 agent/inbox/spliced 的 inserted[].content 里拼出可读文本（截断，卡片只放一行） */
    private static String inboxText(JSONObject data) {
        JSONArray inserted = data.optJSONArray("inserted");
        if (inserted == null || inserted.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inserted.length() && i < 3; i += 1) {
            JSONObject msg = inserted.optJSONObject(i);
            if (msg == null) continue;
            String t = collectText(msg.optJSONArray("content"));
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(t);
        }
        String out = sb.toString().trim();
        return out.length() > 120 ? out.substring(0, 120) + "…" : out;
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
            return new Card("fail-" + turn, "alert_card",
                    obj("title", "模型报错", "content", message, "level", "error"));
        }
        return null;
    }

    private static Card workflowCard(String runId, WfRun run) {
        JSONArray steps = new JSONArray();
        for (JSONObject step : run.steps.values()) add(steps, step);
        return new Card("wf-" + runId, "steps", obj("title", "工作流：" + run.name, "steps", steps));
    }

    /** 从 message 形态或 text 字段取正文（system/developer/team 消息共用，B14） */
    private static String messageText(JSONObject data) {
        if (data == null) return "";
        JSONObject msg = data.optJSONObject("message");
        String text = msg != null ? collectText(msg.optJSONArray("content")) : "";
        if (text.isEmpty()) text = collectText(data.optJSONArray("content"));
        if (text.isEmpty()) text = data.optString("text", data.optString("summary", ""));
        return text.trim();
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
        return new Card("plan", "alert_card", obj(
                "title", "计划模式",
                "content", active ? "已开启：模型先给计划，等你批准后再执行" : "已关闭",
                "level", "info"));
    }

    public static String approvalTool(JSONObject request) {
        if (request == null) return "工具";
        // DSH 的 approval/request 用 toolName；本地引擎用 tool/name。三个都认，别再退化成“工具”。
        String tool = request.optString("toolName",
                request.optString("tool", request.optString("name", "")));
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

    // ---- M9 B 档（2026-11-14）：权限三件套合成一张"能点"的状态卡 ----

    /** 最近一次 sandbox/mode 的取值（三件套共用一张卡，所以状态挂在 mapper 上） */
    private String permSandbox = "";
    private String permPreset = "";
    private String permPolicy = "";

    /**
     * 权限状态卡：{@code 只读 / 可写工作区 / 完全访问} + {@code 审批:每次询问}，
     * 带一个「改权限」原生按钮（点了走 {@code ComponentActions} 的本地动作 → 页面弹变更卡）。
     *
     * <p>为什么合并：一次预设切换会连发 {@code permission/preset} + {@code sandbox/mode} +
     * {@code approval/policy} 三条事件 —— 以前每出一条就冒一张卡，用户看到三张同义卡。
     * 现在固定 key {@code permission-state}，后到的就地更新前一张。</p>
     */
    private Card permissionStateCard() {
        String sandbox = chineseSandbox(permSandbox);
        String policy = chinesePolicy(permPolicy);
        StringBuilder sb = new StringBuilder();
        if (!sandbox.isEmpty()) sb.append(sandbox);
        if (!policy.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(policy);
        JSONObject props = obj("title", "权限",
                "content", sb.length() > 0 ? sb.toString() : "已更新", "level", "info");
        put(props, "actions", actions("改权限", "permission"));
        return new Card("permission-state", "alert_card", props);
    }

    /** 内部枚举 → 人话（与插件槽位文案同一套说法，别让英文枚举漏到界面上） */
    private static String chineseSandbox(String mode) {
        String m = mode == null ? "" : mode.trim();
        switch (m) {
            case "read-only":
                return "只读";
            case "workspace-write":
                return "可写工作区";
            case "danger-full-access":
                return "完全访问";
            default:
                return m;
        }
    }

    private static String chinesePolicy(String policy) {
        String p = policy == null ? "" : policy.trim();
        switch (p) {
            case "never":
                return "审批:从不询问";
            case "ask":
                return "审批:每次询问";
            case "always":
                return "审批:总是询问";
            case "auto":
                return "审批:自动判断";
            default:
                return p;
        }
    }

    /** 一条原生按钮：{@code {label, action}}（action 由 ComponentActions 执行） */
    static JSONArray actions(String label, String act) {
        JSONArray arr = new JSONArray();
        add(arr, obj("label", label, "action", act));
        return arr;
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
