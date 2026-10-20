package com.oilquiz.app.ai.chat;

import com.oilquiz.app.ai.agent.AgentExecutionState;
import com.oilquiz.app.ai.chat.component.ComponentData;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条 AI 消息的“活动时间线”模型（2026-10-20，合并方案 S1）。
 *
 * <p>合并前同一份过程数据有 5 种表示：独立 THINKING 行、独立 TOOL_CALL 行、正文内联的
 * tool_call 卡、v2 的思考步骤列表、v2 的工具总行 + 明细，外加 AgentExecutionState 的
 * blocks（又有执行面板 / 进度环 / timeline 三个渲染器）。它们各自判断“第几轮、展开没展开”，
 * 谁最后跑谁赢。这里把“消息 → 有序的活动行”抽成纯逻辑，视图只负责画：
 * <b>思考轮与工具调用按 contentRoundBounds 交替排列，同一 toolCallId 只留一条</b>。</p>
 *
 * <p>纯 Java、不碰 Android，便于在 JVM 单测里钉住顺序、去重、计数与旧数据兼容。
 * 同一份模型将来喂给 v1 与 v2 两棵树（S1 先保证不改变任何现有渲染）。</p>
 *
 * <p>范围：只描述“过程”（思考 / 工具 / 规划 / 反思 / 错误）；正文与产物卡片不在这里，
 * 它们仍由正文段渲染。没有 rounds 与 tool_call 卡时，退回读
 * {@link AgentExecutionState} 的 blocks（旧 Agent 消息的唯一数据源）。</p>
 */
public final class ActivityTimeline {

    public enum Kind {THINKING, TOOL, PLAN, REFLECTION, ERROR}

    public enum Status {PENDING, RUNNING, DONE, FAILED}

    /** 活动区里的一行；字段都是渲染所需的最终值，视图不再回读 ChatMessage。 */
    public static final class Item {
        public final Kind kind;
        /** 稳定锚点：思考轮用 roundId（缺省 round-N），工具用 tool:<toolCallId>；折叠状态按它记 */
        public final String id;
        /** 行标题（工具中文名 / 规划标题）；思考行可为 null，视图用摘要当标题 */
        public final String title;
        /** 收起态一行摘要（工具参数 / 思考首句），已限长 */
        public final String summary;
        /** 展开后的全文（思考全文 / 工具结果 / 规划内容）；null 或空表示没有可展开内容 */
        public final String body;
        public final Status status;
        /** 该行耗时（毫秒）；0 = 未知，不显示 */
        public final long durationMs;
        /** 工具调用 id（非工具行为 null） */
        public final String toolCallId;

        Item(Kind kind, String id, String title, String summary, String body,
             Status status, long durationMs, String toolCallId) {
            this.kind = kind;
            this.id = id;
            this.title = title != null ? title : "";
            this.summary = summary != null ? summary : "";
            this.body = body;
            this.status = status != null ? status : Status.PENDING;
            this.durationMs = Math.max(0L, durationMs);
            this.toolCallId = toolCallId;
        }

        public boolean hasBody() {
            return body != null && !body.trim().isEmpty();
        }
    }

    /** build() 的结果：有序行 + 头部要显示的几个数（文案由视图按 strings 组装）。 */
    public static final class Model {
        public final List<Item> items;
        public final int thinkingSteps;
        public final long thinkingDurationMs;
        public final int toolCalls;
        public final boolean anyRunning;
        public final boolean anyFailed;

        Model(List<Item> items, int thinkingSteps, long thinkingDurationMs, int toolCalls,
              boolean anyRunning, boolean anyFailed) {
            this.items = items;
            this.thinkingSteps = thinkingSteps;
            this.thinkingDurationMs = thinkingDurationMs;
            this.toolCalls = toolCalls;
            this.anyRunning = anyRunning;
            this.anyFailed = anyFailed;
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }

    private ActivityTimeline() {
    }

    public static Model build(ChatMessage message) {
        List<Item> items = new ArrayList<>();
        Map<String, Integer> indexById = new HashMap<>();
        if (message == null) return finish(items);

        List<String> rounds = message.thinkingRounds;
        List<ComponentData> tools = collectTools(message);

        if ((rounds != null && !rounds.isEmpty()) || !tools.isEmpty()) {
            int bounds = message.contentRoundBounds != null ? message.contentRoundBounds.size() : 0;
            if (bounds > 0) {
                // 轮次模式：第 i 轮思考 → 第 i 个工具（contentRoundBounds 每个边界对应一次工具调用），
                // 最后一轮（i == bounds）只有思考、没有工具。
                // 2026-10-21：优先按 contentRoundToolIds 找工具身份；只有旧数据没有 id 时才按顺序取。
                int ri = 0;
                boolean[] used = new boolean[tools.size()];
                for (int i = 0; i <= bounds; i++) {
                    if (rounds != null && ri < rounds.size()) {
                        addThinking(items, indexById, message, ri);
                        ri++;
                    }
                    if (i < bounds) {
                        int pick = pickTool(message, tools, used, i);
                        if (pick >= 0) {
                            used[pick] = true;
                            addTool(items, indexById, tools.get(pick), pick);
                        }
                    }
                }
                // 数据比边界长（旧数据/异常路径）：剩余项按原顺序补在末尾，不丢。
                while (rounds != null && ri < rounds.size()) {
                    addThinking(items, indexById, message, ri);
                    ri++;
                }
                for (int j = 0; j < tools.size(); j++) {
                    if (!used[j]) addTool(items, indexById, tools.get(j), j);
                }
            } else {
                // 无边界：思考轮在前、工具在后（现有 v2 的快照形状；没有时序信息时不假装知道）
                if (rounds != null) {
                    for (int i = 0; i < rounds.size(); i++) addThinking(items, indexById, message, i);
                }
                for (int i = 0; i < tools.size(); i++) addTool(items, indexById, tools.get(i), i);
            }
        } else {
            addBlocksFallback(items, message.agentExecutionState);
        }
        return finish(items);
    }

    /** 第 i 轮应使用哪个工具：优先按 contentRoundToolIds[i] 的身份找，找不到再取下一个未用工具。 */
    private static int pickTool(ChatMessage message, List<ComponentData> tools,
                                boolean[] used, int roundIndex) {
        if (tools.isEmpty()) return -1;
        String wanted = null;
        if (message.contentRoundToolIds != null
                && roundIndex < message.contentRoundToolIds.size()) {
            wanted = message.contentRoundToolIds.get(roundIndex);
        }
        if (wanted != null && !wanted.isEmpty()) {
            for (int j = 0; j < tools.size(); j++) {
                if (!used[j] && wanted.equals(toolId(tools.get(j)))) return j;
            }
        }
        for (int j = 0; j < tools.size(); j++) {
            if (!used[j]) return j;
        }
        return -1;
    }

    private static String toolId(ComponentData tool) {
        if (tool == null || tool.props == null) return "";
        String id = tool.props.optString("toolCallId", "");
        return id != null ? id : "";
    }

    private static void addThinking(List<Item> items, Map<String, Integer> indexById,
                                    ChatMessage message, int index) {
        List<String> rounds = message.thinkingRounds;
        if (rounds == null || index < 0 || index >= rounds.size()) return;
        String text = rounds.get(index);
        if (text == null || text.trim().isEmpty()) return;
        String roundId = (message.thinkingRoundIds != null && index < message.thinkingRoundIds.size())
                ? message.thinkingRoundIds.get(index) : null;
        if (roundId == null || roundId.isEmpty()) roundId = "round-" + (index + 1);
        Item item = new Item(Kind.THINKING, roundId, null, preview(text, 48), text,
                Status.DONE, message.thinkingRoundDurationMs(index), null);
        put(items, indexById, item);
    }

    private static void addTool(List<Item> items, Map<String, Integer> indexById,
                                ComponentData tool, int index) {
        if (tool == null) return;
        JSONObject p = tool.props;
        String name = p != null ? p.optString("toolName", "") : "";
        String callId = p != null ? p.optString("toolCallId", "") : "";
        String status = p != null ? p.optString("status", "running") : "running";
        String args = p != null ? p.optString("args", "") : "";
        String result = p != null ? p.optString("result", "") : "";
        long duration = p != null ? p.optLong("durationMs", p.optLong("executionTimeMs", 0L)) : 0L;
        String anchor = (callId != null && !callId.isEmpty())
                ? "tool:" + callId : "tool#idx" + index;
        String title = ToolMetaRegistry.displayName(name);
        String body = result.isEmpty() ? null : formatToolResult(result);
        Item item = new Item(Kind.TOOL, anchor, title, oneLineBrief(args),
                body, parseStatus(status), duration,
                callId == null || callId.isEmpty() ? null : callId);
        put(items, indexById, item);
    }

    /** 同一 toolCallId 可能同时来自 components 与 blocks：后到的状态/结果覆盖先到的，位置不变。 */
    private static void put(List<Item> items, Map<String, Integer> indexById, Item item) {
        Integer at = indexById.get(item.id);
        if (at != null) {
            items.set(at, item);
            return;
        }
        indexById.put(item.id, items.size());
        items.add(item);
    }

    /** 旧 Agent 消息：没有 rounds、也没有 tool_call 卡时，blocks 是唯一的数据源。 */
    private static void addBlocksFallback(List<Item> items, AgentExecutionState state) {
        if (state == null || state.getBlocks() == null) return;
        List<AgentExecutionState.ExecutionBlock> blocks = state.getBlocks();
        for (int i = 0; i < blocks.size(); i++) {
            AgentExecutionState.ExecutionBlock b = blocks.get(i);
            if (b == null) continue;
            String content = b.getContent();
            String title = b.getTitle();
            Kind kind;
            if (b.getType() == null) {
                kind = Kind.PLAN;
            } else {
                switch (b.getType()) {
                case THINKING:
                case INFERENCE:
                    kind = Kind.THINKING;
                    break;
                case TOOL_CALL:
                case TOOL_RESULT:
                    kind = Kind.TOOL;
                    break;
                case PLANNING:
                    kind = Kind.PLAN;
                    break;
                case REFLECTION:
                case OBSERVATION:
                    kind = Kind.REFLECTION;
                    break;
                case ERROR:
                    kind = Kind.ERROR;
                    break;
                case TEXT:
                default:
                    kind = Kind.PLAN;
                    break;
                }
            }
            if ((content == null || content.trim().isEmpty())
                    && (title == null || title.trim().isEmpty())) {
                continue;
            }
            String anchor = "block:" + i;
            Item item = new Item(kind, anchor, title,
                    preview(content != null ? content : title, 60),
                    content, parseBlockStatus(b.getStatus()), 0L, null);
            put(items, indexById(items), item);
        }
    }

    /** 给 addBlocksFallback 用：按当前列表重建索引（blocks 的 id 天然唯一，够用）。 */
    private static Map<String, Integer> indexById(List<Item> items) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < items.size(); i++) map.put(items.get(i).id, i);
        return map;
    }

    private static Model finish(List<Item> items) {
        int steps = 0;
        int tools = 0;
        long thinkingMs = 0L;
        boolean running = false;
        boolean failed = false;
        for (Item it : items) {
            if (it.kind == Kind.THINKING) {
                steps++;
                thinkingMs += it.durationMs;
            } else if (it.kind == Kind.TOOL) {
                tools++;
            }
            if (it.status == Status.RUNNING) running = true;
            if (it.status == Status.FAILED) failed = true;
        }
        return new Model(items, steps, thinkingMs, tools, running, failed);
    }

    private static List<ComponentData> collectTools(ChatMessage message) {
        List<ComponentData> out = new ArrayList<>();
        if (message == null || message.components == null) return out;
        for (ComponentData c : message.components) {
            if (c != null && "tool_call".equals(c.type)) out.add(c);
        }
        return out;
    }

    private static Status parseStatus(String s) {
        if ("completed".equals(s) || "success".equals(s) || "done".equals(s)) return Status.DONE;
        if ("failed".equals(s) || "error".equals(s)) return Status.FAILED;
        if ("running".equals(s) || "active".equals(s)) return Status.RUNNING;
        return Status.PENDING;
    }

    private static Status parseBlockStatus(AgentExecutionState.ExecutionBlock.BlockStatus s) {
        if (s == null) return Status.PENDING;
        switch (s) {
            case COMPLETED:
                return Status.DONE;
            case RUNNING:
                return Status.RUNNING;
            case FAILED:
                return Status.FAILED;
            default:
                return Status.PENDING;
        }
    }

    /**
     * 收起态摘要：取首个段落的首行，去掉 ** / ` 标记、压空白、限长。
     * 与 {@code ChatAdapter#thinkingPreview} 同口径（S2 会改成共用这一份）。
     */
    static String preview(String content, int maxChars) {
        if (content == null) return "";
        String t = content.trim();
        if (t.isEmpty()) return "";
        int para = t.indexOf("\n\n");
        if (para >= 0) t = t.substring(0, para);
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl);
        t = t.replace("**", "").replace("`", "").trim();
        StringBuilder sb = new StringBuilder(t.length());
        boolean lastSpace = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastSpace && sb.length() > 0) sb.append(' ');
                lastSpace = true;
            } else {
                sb.append(c);
                lastSpace = false;
            }
        }
        String s = sb.toString();
        if (s.length() > maxChars) s = s.substring(0, maxChars);
        return s;
    }

    /**
     * 工具结果的展示文本：优先取工具包装 JSON 里的 {@code formatted_result}（天气等工具
     * 真正给人看的那段），其次 content/text；解析不了原样返回。
     *
     * <p>tool_call 组件的 {@code result} 往往是整个原始 JSON（含 24 小时预报、空气质量、
     * 预警全文），直接塞进预览就是一堆转义字符——活动区的行应该显示"结果"而不是"报文"。</p>
     */
    static String formatToolResult(String result) {
        if (result == null || result.isEmpty()) return result;
        String t = result.trim();
        if (!t.startsWith("{")) return result;
        try {
            JSONObject o = new JSONObject(t);
            String f = o.optString("formatted_result", "");
            if (!f.isEmpty()) return f;
            String c = o.optString("content", "");
            if (!c.isEmpty()) return c;
            String text = o.optString("text", "");
            if (!text.isEmpty()) return text;
        } catch (Exception ignored) {
            // 不是合法 JSON（有些工具直接返回纯文本），原样展示
        }
        return result;
    }

    /** 工具参数摘要：压成一行、限长 60（与 ChatAdapter#oneLineBrief 同口径）。 */
    static String oneLineBrief(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (s.length() > 60) s = s.substring(0, 60) + "…";
        return s;
    }
}
