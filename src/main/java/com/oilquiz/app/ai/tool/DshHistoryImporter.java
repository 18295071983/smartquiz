package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.remote.DshCards;
import com.oilquiz.app.ai.remote.DshEvent;
import com.oilquiz.app.ai.remote.DshProjection;
import com.oilquiz.app.ai.remote.DshTimeline;
import com.oilquiz.app.ai.util.ChatHistoryManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 把电脑端（DSH）的一条会话**导入成本机的一条对话历史**。
 *
 * <p>为什么走"历史文件"而不是持续镜像：手机端本来就有对话历史系统，同一条对话应该有
 * 一份本地历史文件；导入之后它在历史抽屉里可见、可搜索、可离线回看，不需要靠每次补拉
 * 重新拼。</p>
 *
 * <p><b>与实时同步同源（2026-11-12 重构）</b>：这里不再自带一套"Dsh 事件 → 消息"的规则，
 * 而是复用 {@link DshTimeline}（事件归约）+ {@link DshProjection}（消息投影）。
 * 旧实现的导入器只认四类事件，导入出来的会话<b>没有任何工具结果卡、待办卡、工作流卡</b>，
 * 与手机实时看到的是两种样子；现在两条路径逐字段一致，只是导入一次性读完整段事件流。</p>
 */
public final class DshHistoryImporter {

    private DshHistoryImporter() {
    }

    /**
     * 导入时只拉"能变成消息/卡片"的事件类型。
     *
     * <p>全量事件里 {@code request/header}、{@code session-log}、{@code web/*} 这类占绝大多数字节
     * 却没有任何 UI 产物；过滤后长会话的事件数从 9000+ 降到一千多，隧道上快一个量级。
     * 过滤表必须与 {@link DshTimeline} / {@link DshCards} 支持的类型保持同步。</p>
     */
    static final String EVENT_TYPES = String.join(",",
            "user/message", "assistant/message", "tool/call", "tool/result",
            "approval", "approval_gone", "question", "question_gone",
            "todo/write", "deliverables/presented", "goal/activation-changed", "workspace/changes",
            "job/registered", "command/run",
            "tool-workflow/run-start", "tool-workflow/agent-start",
            "tool-workflow/agent-end", "tool-workflow/run-end",
            "subagent/catalog", "llm/retry", "assistant/attempt",
            "compaction/start", "compaction/summary");

    /** 进度回调（在后台线程触发；调用方自行 post 到 UI） */
    public interface Progress {
        /** 已收到的事件条数（会话越长越多） */
        void onEvents(int count);

        /** 事件收完、开始转消息 */
        void onBuilding(int events);
    }

    /** 导入结果：导入的消息条数 + 本地会话 id */
    public static final class Result {
        public final int count;
        public final String sessionId;
        public final String title;

        Result(int count, String sessionId, String title) {
            this.count = count;
            this.sessionId = sessionId;
            this.title = title;
        }
    }

    /**
     * 拉取电脑端整条会话并写成本机历史。
     *
     * @param target 电脑端会话 id
     * @param title  电脑端会话标题（仅用于提示，历史标题仍由首条用户消息生成）
     */
    public static Result importSession(Context ctx, String target, String title) throws Exception {
        return importSession(ctx, target, title, null);
    }

    public static Result importSession(Context ctx, String target, String title, Progress progress)
            throws Exception {
        List<JSONObject> events = fetchAllEvents(ctx, target, progress);
        if (progress != null) progress.onBuilding(events.size());
        List<ChatMessage> messages = buildMessages(events);
        if (messages.isEmpty()) return new Result(0, null, title);

        ChatHistoryManager mgr = new ChatHistoryManager(ctx);
        // 同一条电脑会话重复导入 → 覆盖原来那一条，而不是每次都新建
        String existing = mappedLocalSession(ctx, target);
        if (existing != null && mgr.loadConversationSession(existing) == null) existing = null;
        com.oilquiz.app.ai.util.ConversationSession session =
                mgr.saveCurrentChatAsSession(messages, existing);
        if (session == null) return new Result(0, null, title);
        rememberMapping(ctx, target, session.id);
        return new Result(messages.size(), session.id, session.title);
    }

    /** 电脑会话 id → 本机历史会话 id（保证重复导入是"更新"而不是"新增"） */
    private static String mappedLocalSession(Context ctx, String target) {
        try {
            return ctx.getSharedPreferences("remote_dsh", Context.MODE_PRIVATE)
                    .getString("import_sid_" + target, null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void rememberMapping(Context ctx, String target, String localId) {
        try {
            ctx.getSharedPreferences("remote_dsh", Context.MODE_PRIVATE)
                    .edit().putString("import_sid_" + target, localId).apply();
        } catch (Throwable ignored) { /* 记不住就下次新建，不影响导入 */ }
    }

    private static List<JSONObject> fetchAllEvents(Context ctx, String target, Progress progress) throws Exception {
        final List<JSONObject> out = new ArrayList<>();
        // 导入要的是"整条历史"，阈值放到 100 万：否则长会话（事件 >5000）会被服务端
        // 降级成 snapshot（只有角色+文本），导入器就收不到任何 event，表现出"没有可导入的消息"。
        RemoteDshTool.subscribeEvents(ctx, target, 0, 120, false, 1_000_000,
                EVENT_TYPES,
                new RemoteDshTool.DshEventListener() {
                    @Override public void onReady(int seq) { }

                    @Override public void onSnapshot(int seq, JSONArray turns) {
                        // 兜底：万一是真正的超大会话，snapshot 的 turn 也要能落成消息
                        if (turns == null) return;
                        for (int i = 0; i < turns.length(); i += 1) {
                            JSONObject t = turns.optJSONObject(i);
                            if (t == null) continue;
                            JSONObject synthetic = new JSONObject();
                            try {
                                boolean user = "user".equals(t.optString("role"));
                                synthetic.put("type", user ? "user/message" : "assistant/message");
                                JSONObject data = new JSONObject();
                                JSONObject message = new JSONObject();
                                JSONArray content = new JSONArray();
                                JSONObject p = new JSONObject();
                                p.put("type", "text");
                                p.put("text", t.optString("text"));
                                content.put(p);
                                if (user) {
                                    data.put("content", content);
                                } else {
                                    message.put("content", content);
                                    data.put("message", message);
                                }
                                synthetic.put("data", data);
                            } catch (Exception ignored) { /* 跳过坏 turn */ }
                            out.add(synthetic);
                        }
                    }

                    @Override public void onEvent(JSONObject event) {
                        if (event == null) return;
                        out.add(event);
                        if (progress != null && out.size() % 200 == 0) {
                            progress.onEvents(out.size());
                        }
                    }

                    @Override public void onEnd() { }

                    @Override public void onError(String message) { }
                });
        if (progress != null && !out.isEmpty()) progress.onEvents(out.size());
        return out;
    }

    /**
     * 事件流 → 本机 ChatMessage：用户消息照原样落；每次 {@code user/message} 之前
     * 的助手事件聚成**一条** AI 消息（含思考/正文/工具卡/数据卡），与实时同步同构。
     *
     * <p>package-private + 无 Context：纯逻辑，单测直接喂事件。</p>
     */
    static List<ChatMessage> buildMessages(List<JSONObject> events) {
        List<ChatMessage> out = new ArrayList<>();
        DshTimeline timeline = new DshTimeline();
        ChatMessage assistant = null;
        int[] componentSeq = {0};

        for (JSONObject raw : events) {
            DshEvent ev = DshEvent.from(raw);
            if (ev == null) continue;
            long ts = ev.ts > 0 ? ev.ts : System.currentTimeMillis();

            if ("user/message".equals(ev.type)) {
                finishAssistant(out, assistant, timeline, ts, componentSeq);
                String text = ev.data != null
                        ? DshCards.collectText(ev.data.optJSONArray("content")) : "";
                if (!text.isEmpty()) {
                    ChatMessage user = ChatMessage.createUserMessage(text);
                    user.timestamp = ts;
                    out.add(user);
                }
                timeline = new DshTimeline();
                assistant = null;
                continue;
            }

            if (assistant == null) {
                assistant = ChatMessage.createAIMessage(
                        java.util.UUID.randomUUID().toString(), "", ts, null, 0, 0);
                assistant.timestamp = ts;
                out.add(assistant);
            }
            try {
                DshTimeline.Update up = timeline.reduce(ev);
                collectApprovals(assistant, up, timeline, ts);
            } catch (Throwable ignored) {
                // 单条坏事件不影响整段导入
            }
        }
        finishAssistant(out, assistant, timeline, System.currentTimeMillis(), componentSeq);
        return out;
    }

    /** 审批事件也进历史：导入后的会话照样能看到"当时批了什么" */
    private static void collectApprovals(ChatMessage assistant, DshTimeline.Update up,
                                         DshTimeline timeline, long ts) {
        if (assistant == null || up == null) return;
        for (DshTimeline.Action action : up.actions) {
            if (action.kind == DshTimeline.ActionKind.APPROVAL) {
                if (assistant.approvals == null) assistant.approvals = new ArrayList<>();
                boolean exists = false;
                for (ChatMessage.ApprovalRequest r : assistant.approvals) {
                    if (r != null && action.id.equals(r.id)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    assistant.approvals.add(new ChatMessage.ApprovalRequest(
                            action.id, "", DshCards.approvalTool(action.request),
                            DshCards.approvalDetail(action.request),
                            com.oilquiz.app.ai.chat.turn.AssistantTurn.Part.APPROVAL_PENDING,
                            timeline.currentRound(), ts > 0 ? ts : System.currentTimeMillis(), "dsh"));
                }
            } else if (action.kind == DshTimeline.ActionKind.APPROVAL_GONE
                    && assistant.approvals != null) {
                for (ChatMessage.ApprovalRequest r : assistant.approvals) {
                    if (r != null && r.isPending()
                            && (action.id == null || action.id.isEmpty() || action.id.equals(r.id))) {
                        r.status = "expired";
                    }
                }
            }
        }
    }

    /** 把攒在时间线里的助手回合投影成消息；只有一条空壳（无正文/思考/卡片）时摘掉 */
    private static void finishAssistant(List<ChatMessage> out, ChatMessage assistant,
                                        DshTimeline timeline, long now, int[] componentSeq) {
        if (assistant == null) return;
        DshProjection.project(assistant, timeline, key -> "dsh-I" + (++componentSeq[0]), now);
        boolean hasText = assistant.content != null && !assistant.content.isEmpty();
        boolean hasThinking = assistant.thinkingContent != null && !assistant.thinkingContent.isEmpty();
        boolean hasCards = assistant.components != null && !assistant.components.isEmpty();
        boolean hasApprovals = assistant.approvals != null && !assistant.approvals.isEmpty();
        if (!hasText && !hasThinking && !hasCards && !hasApprovals) {
            out.remove(assistant);
        }
    }
}
