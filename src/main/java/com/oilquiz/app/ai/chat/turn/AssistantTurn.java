package com.oilquiz.app.ai.chat.turn;

import com.oilquiz.app.ai.chat.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮用户请求对应的完整助手回合（业界 parts 模型，2026-10-21）。
 *
 * <p>结构对齐 OpenAI Responses 的 output items、Anthropic Messages 的 content
 * blocks、Vercel AI SDK 的 UIMessage.parts：</p>
 *
 * <pre>
 * AssistantTurn
 *   turnId
 *   modelCalls[]   // 每次模型生成一条：fullText / reasoning / stopReason / tool ids
 *   parts[]        // 有序：reasoning → text → tool_call → tool_result → ...
 * </pre>
 *
 * <p><b>单一权威</b>：parts + modelCalls 是持久化真值；{@link ChatMessage#content}
 * 只是兼容/搜索/复制用的投影，不由它反向推导 parts。</p>
 */
public class AssistantTurn {

    public static final int VERSION = 1;

    public int version = VERSION;
    public String turnId;
    public List<ModelCall> modelCalls = new ArrayList<>();
    public List<Part> parts = new ArrayList<>();

    public static class ModelCall {
        public String id;
        public int round;
        /** 该次模型生成的完整正文（清理后的 fullText） */
        public String content;
        /** 该次生成的 reasoning */
        public String reasoning;
        /** native / API 的停止原因，如 eos / ctx_full / stop_word */
        public String stopReason;
        public long timestamp;
        public List<String> toolCallIds = new ArrayList<>();

        public ModelCall() {
        }

        public ModelCall(String id, int round, String content, String reasoning,
                         String stopReason, long timestamp) {
            this.id = id;
            this.round = round;
            this.content = content != null ? content : "";
            this.reasoning = reasoning != null ? reasoning : "";
            this.stopReason = stopReason != null ? stopReason : "";
            this.timestamp = timestamp;
        }
    }

    public static class Part {
        public static final String TYPE_REASONING = "reasoning";
        public static final String TYPE_TEXT = "text";
        public static final String TYPE_TOOL_CALL = "tool_call";
        public static final String TYPE_TOOL_RESULT = "tool_result";
        public static final String TYPE_ERROR = "error";

        public String id;
        public String type;
        public String text;
        public String toolCallId;
        public String toolName;
        public String args;
        public String result;
        public String status;
        public long timestamp;

        public Part() {
        }

        public static Part text(String id, String text, long ts) {
            Part p = new Part();
            p.id = id;
            p.type = TYPE_TEXT;
            p.text = text != null ? text : "";
            p.timestamp = ts;
            return p;
        }

        public static Part reasoning(String id, String text, long ts) {
            Part p = new Part();
            p.id = id;
            p.type = TYPE_REASONING;
            p.text = text != null ? text : "";
            p.timestamp = ts;
            return p;
        }

        public static Part toolCall(String id, String toolCallId, String toolName,
                                    String args, String status, long ts) {
            Part p = new Part();
            p.id = id;
            p.type = TYPE_TOOL_CALL;
            p.toolCallId = toolCallId;
            p.toolName = toolName;
            p.args = args;
            p.status = status;
            p.timestamp = ts;
            return p;
        }
    }

    /** 从当前 ChatMessage 的兼容字段构建 canonical turn。 */
    public static AssistantTurn fromMessage(ChatMessage msg) {
        if (msg == null) return null;
        AssistantTurn turn = new AssistantTurn();
        turn.turnId = msg.turnId;
        long base = msg.timestamp > 0 ? msg.timestamp : System.currentTimeMillis();
        int seq = 0;

        if (msg.roundSnapshots != null && !msg.roundSnapshots.isEmpty()) {
            for (ChatMessage.RoundSnapshot snap : msg.roundSnapshots) {
                if (snap == null) continue;
                String callId = turn.turnId != null
                        ? turn.turnId + "-R" + snap.round
                        : "R" + snap.round;
                ModelCall call = new ModelCall(callId, snap.round,
                        snap.content, snap.thinking, "", snap.timestamp > 0 ? snap.timestamp : base);
                if (snap.toolCallIds != null) call.toolCallIds.addAll(snap.toolCallIds);
                turn.modelCalls.add(call);

                if (snap.thinking != null && !snap.thinking.trim().isEmpty()) {
                    turn.parts.add(Part.reasoning(callId + "-think", snap.thinking,
                            call.timestamp));
                }
                if (snap.content != null && !snap.content.trim().isEmpty()) {
                    turn.parts.add(Part.text(callId + "-text", snap.content,
                            call.timestamp));
                }
                // 工具调用按该轮顺序挂到 text 后；name/args 由 UI 的 tool card 保留
                if (snap.toolCallIds != null) {
                    for (String toolId : snap.toolCallIds) {
                        if (toolId == null || toolId.isEmpty()) continue;
                        turn.parts.add(Part.toolCall(callId + "-tool-" + (seq++),
                                toolId, "", "", "done", call.timestamp));
                    }
                }
            }
        } else {
            // 旧数据/无逐轮快照：至少保留一条 model call + text part
            ModelCall call = new ModelCall("R1", 1, msg.content, msg.thinkingContent,
                    "", base);
            turn.modelCalls.add(call);
            if (msg.thinkingContent != null && !msg.thinkingContent.trim().isEmpty()) {
                turn.parts.add(Part.reasoning("R1-think", msg.thinkingContent, base));
            }
            if (msg.content != null && !msg.content.trim().isEmpty()) {
                turn.parts.add(Part.text("R1-text", msg.content, base));
            }
        }
        fillToolPartsFromComponents(turn, msg);
        return turn;
    }

    /** 从消息的 tool_call 组件回填 name/args/status/result，并补 TOOL_RESULT part。 */
    private static void fillToolPartsFromComponents(AssistantTurn turn, ChatMessage msg) {
        if (msg.components == null || msg.components.isEmpty()) return;
        java.util.Map<String, org.json.JSONObject> byId = new java.util.HashMap<>();
        java.util.List<org.json.JSONObject> all = new java.util.ArrayList<>();
        for (com.oilquiz.app.ai.chat.component.ComponentData c : msg.components) {
            if (c == null || c.props == null || !"tool_call".equals(c.type)) continue;
            all.add(c.props);
            String id = c.props.optString("toolCallId", "");
            if (!id.isEmpty()) byId.put(id, c.props);
        }
        java.util.Set<org.json.JSONObject> used =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int seq = 0;
        for (Part p : turn.parts) {
            if (p == null || !Part.TYPE_TOOL_CALL.equals(p.type)) continue;
            org.json.JSONObject props = p.toolCallId != null ? byId.get(p.toolCallId) : null;
            if (props == null) continue;
            used.add(props);
            p.toolName = props.optString("toolName", p.toolName);
            p.args = props.optString("args", p.args);
            p.status = props.optString("status", p.status);
            String result = props.optString("result", "");
            if (!result.isEmpty()) {
                Part r = new Part();
                r.id = (p.id != null ? p.id : "tool") + "-result";
                r.type = Part.TYPE_TOOL_RESULT;
                r.toolCallId = p.toolCallId;
                r.toolName = p.toolName;
                r.result = result;
                r.status = p.status;
                r.timestamp = p.timestamp;
                int at = turn.parts.indexOf(p) + 1;
                turn.parts.add(at, r);
            }
        }
        // 没有快照/没有 toolCallIds 的旧数据：组件里的工具调用补到末尾
        for (org.json.JSONObject props : all) {
            if (used.contains(props)) continue;
            String id = props.optString("toolCallId", "tool_legacy_" + (seq++));
            Part p = Part.toolCall("tool-" + id, id,
                    props.optString("toolName", ""), props.optString("args", ""),
                    props.optString("status", "done"), System.currentTimeMillis());
            turn.parts.add(p);
            String result = props.optString("result", "");
            if (!result.isEmpty()) {
                Part r = new Part();
                r.id = p.id + "-result";
                r.type = Part.TYPE_TOOL_RESULT;
                r.toolCallId = id;
                r.toolName = p.toolName;
                r.result = result;
                r.status = p.status;
                r.timestamp = p.timestamp;
                turn.parts.add(r);
            }
        }
    }

    /**
     * 把 canonical parts 投影回兼容字段：
     * content / contentRoundBounds / contentRoundToolIds。
     *
     * <p>这是“移除手工 bounds”的关键：UI 仍按 content+bounds 渲染，但 bounds 不再由
     * onToolCallStart 手写，而是由 parts 顺序在提交时统一派生。</p>
     */
    public void applyToMessage(ChatMessage msg) {
        if (msg == null) return;
        StringBuilder sb = new StringBuilder();
        java.util.List<Integer> bounds = new java.util.ArrayList<>();
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (Part p : parts) {
            if (p == null) continue;
            if (Part.TYPE_TEXT.equals(p.type)) {
                if (p.text == null || p.text.isEmpty()) continue;
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append("\n\n");
                }
                sb.append(p.text);
            } else if (Part.TYPE_TOOL_CALL.equals(p.type)) {
                bounds.add(sb.length());
                ids.add(p.toolCallId != null ? p.toolCallId : "");
            }
        }
        if (sb.length() == 0) return;
        msg.content = sb.toString();
        msg.contentRoundBounds = bounds;
        msg.contentRoundToolIds = ids;
    }

    /** 兼容投影：按 parts 顺序拼出正文（不含 tool 参数/结果）。 */
    public String deriveText() {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            if (p == null || !Part.TYPE_TEXT.equals(p.type)) continue;
            if (p.text == null || p.text.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(p.text);
        }
        return sb.toString();
    }

    /** 供落库/并发安全使用的深拷贝。 */
    public AssistantTurn copy() {
        AssistantTurn t = new AssistantTurn();
        t.version = version;
        t.turnId = turnId;
        for (ModelCall c : modelCalls) {
            if (c == null) continue;
            ModelCall n = new ModelCall(c.id, c.round, c.content, c.reasoning,
                    c.stopReason, c.timestamp);
            if (c.toolCallIds != null) n.toolCallIds.addAll(c.toolCallIds);
            t.modelCalls.add(n);
        }
        for (Part p : parts) {
            if (p == null) continue;
            Part n = new Part();
            n.id = p.id;
            n.type = p.type;
            n.text = p.text;
            n.toolCallId = p.toolCallId;
            n.toolName = p.toolName;
            n.args = p.args;
            n.result = p.result;
            n.status = p.status;
            n.timestamp = p.timestamp;
            t.parts.add(n);
        }
        return t;
    }
}
