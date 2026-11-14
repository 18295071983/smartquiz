package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.remote.DshCards;
import com.oilquiz.app.ai.remote.DshEvent;
import com.oilquiz.app.ai.remote.DshProjection;
import com.oilquiz.app.ai.remote.DshTimeline;
import com.oilquiz.app.ai.util.ChatHistoryManager;
import com.oilquiz.app.ai.util.ConversationSession;

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
     * 拉取电脑端整条会话并**强行同步**成本机的一条对话历史（2026-11-14 改）。
     *
     * <p>"强行同步"= 用电脑上的内容**整条覆盖**本地那条会话，不合并旧头部、不另开一条：
     * 用户在手机上切到哪条电脑会话，本地看到的就该和电脑上完全一致（标题也用电脑的）。
     * 本地身份取<b>锚点</b>里的 {@code local_session_id}（设备 × 电脑会话的唯一映射）；
     * 没有锚点才退回旧的 {@code import_sid_} 映射 / 随机 id，保证老数据不会丢。</p>
     *
     * <p>同步完把传输游标和渲染游标都推到这段历史的末尾：不推的话通道会从旧游标重放一遍，
     * 页面上就会把同一段对话再显示一次。</p>
     *
     * @param target 电脑端会话 id
     * @param title  电脑端会话标题（直接用作本地会话标题，保证两端一致）
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
        String localId = resolveLocalSessionId(ctx, mgr, target);
        // 2026-11-14 真机：电脑端偶尔读不到标题（会话正在写），传进来是空的，
        // 于是 `generateTitle` 拿第一条 USER 消息当标题 —— 而 DSH 会把「background job …」这类
        // 后台任务通知也记成 USER 消息，结果本机会话被命名成 "background job pwsh-…"。
        // 顺序：电脑给的 > 本地文件原有的 > 从"像人说的话"里生成。
        String finalTitle = resolveTitle(mgr, localId, title, messages);
        ConversationSession session = new ConversationSession(localId, finalTitle, messages);
        if (messages.get(0).timestamp > 0) session.createdAt = messages.get(0).timestamp;
        // saveConversationSession 是"整文件重写"，天然就是覆盖：不需要先删旧文件
        session = mgr.saveConversationSession(session);
        if (session == null || session.id == null) return new Result(0, null, title);
        rememberMapping(ctx, target, session.id);

        int maxSeq = maxSeqOf(events);
        if (maxSeq > 0) {
            RemoteDshTool.setTransportSeq(ctx, target, maxSeq);
            RemoteDshTool.setLastSeq(ctx, target, maxSeq);
        }
        return new Result(messages.size(), session.id, session.title);
    }

    /**
     * 本地会话标题：电脑给的 > 本地文件原有的 > 从消息里生成（跳过"后台任务通知"这类伪用户消息）。
     *
     * <p>为什么要挑：DSH 把 {@code background job xxx (pwsh: …)} 之类也记成 {@code user/message}，
     * 直接按"第一条用户消息"生成标题会把会话命名成一串任务 id（真机踩到过）。</p>
     */
    private static String resolveTitle(ChatHistoryManager mgr, String localId, String title,
                                       List<ChatMessage> messages) {
        if (title != null && !title.trim().isEmpty()) return title.trim();
        try {
            ConversationSession old = mgr.loadConversationSession(localId);
            if (old != null && old.title != null && !old.title.trim().isEmpty()) return old.title.trim();
        } catch (Throwable ignored) {
            // 读不到就继续往下挑
        }
        List<ChatMessage> human = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m == null || !m.isUserMessage() || m.content == null) continue;
            if (looksLikeSystemNotice(m.content)) continue;
            human.add(m);
        }
        String generated = ConversationSession.generateTitle(human);
        if (generated != null && !generated.isEmpty() && !"新对话".equals(generated)) return generated;
        return "电脑对话";
    }

    /** 这些不是人说的话：后台任务通知 / 任务回执 / 工具输出摘要 */
    private static boolean looksLikeSystemNotice(String text) {
        String s = text.trim();
        if (s.isEmpty()) return true;
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("background job ")
                || lower.startsWith("[background job")
                || lower.startsWith("后台任务")
                || lower.contains("(pwsh:")
                || lower.contains("(powershell:")
                || lower.startsWith("<task-notification");
    }

    /**
     * 这条电脑会话在本机该用哪个会话 id：
     * 锚点优先（A3/A4 的单一账本）→ 没有就让电脑签发一条（ensure，不新建电脑会话）
     * → 再退回旧的 import_sid_ 映射（升级前数据）→ 最后才随机新建。
     *
     * <p>{@code ensure} 这一步是必须的：切到一条从没同步过的电脑会话时，本地还没有锚点；
     * 先随机一个 uuid、随后握手又签发另一个 local_session_id，同一条会话就会裂成两条历史。</p>
     */
    private static String resolveLocalSessionId(Context ctx, ChatHistoryManager mgr, String target) {
        String anchored = RemoteAnchorStore.ensureLocalSessionId(ctx, RemoteDshTool.deviceId(ctx), target);
        if (anchored != null && !anchored.isEmpty()) return anchored;
        String mapped = mappedLocalSession(ctx, target);
        if (mapped != null && !mapped.isEmpty() && mgr.loadConversationSession(mapped) != null) {
            return mapped;
        }
        return java.util.UUID.randomUUID().toString();
    }

    /** 这批事件里最大的 seq（用来把两套游标推到末尾） */
    private static int maxSeqOf(List<JSONObject> events) {
        int max = 0;
        for (JSONObject ev : events) {
            if (ev == null) continue;
            int s = ev.optInt("seq", 0);
            if (s > max) max = s;
        }
        return max;
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
                String text = ev.data != null
                        ? DshCards.collectText(ev.data.optJSONArray("content")) : "";
                /**
                 * 2026-11-14：历史同步必须和**实时镜像**用同一把尺子。
                 *
                 * <p>实时路径走 {@code AIChatActivity.mirrorUserMessage}：只认
                 * {@code source.kind} 为空或 {@code 'user'} 的消息，空文本跳过，同文本去重。
                 * 这里以前是"见到 user/message 就加一条用户气泡"，于是 DSH 记成 user/message 的
                 * 后台任务通知（{@code background job pwsh-…}）、重试回执、系统提示，历史一同步
                 * 就全变成"用户提问"——真机上就是历史里冒出一堆莫名其妙的用户气泡。</p>
                 */
                if (!isHumanUserMessage(ev) || text.isEmpty()) {
                    // 不是人说的话：不建气泡，也**不**当作轮次边界（避免把一轮回复切成两段）
                    continue;
                }
                if (!out.isEmpty()) {
                    ChatMessage last = out.get(out.size() - 1);
                    if (last != null && last.isUserMessage() && text.equals(last.content)) {
                        continue;   // 相邻重复（重试/补拉重放）
                    }
                }
                finishAssistant(out, assistant, timeline, ts, componentSeq);
                ChatMessage user = ChatMessage.createUserMessage(text);
                user.timestamp = ts;
                out.add(user);
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

    /**
     * 这条 {@code user/message} 是不是"人说的话"。
     *
     * <p>口径与实时镜像 {@code AIChatActivity.mirrorUserMessage} 一致：没有 {@code source}
     * 的旧日志照常显示；有 {@code source} 时 {@code kind} 必须为空或 {@code 'user'}。
     * DSH 会把后台任务通知、重试回执、系统提示也记成 {@code user/message}，它们不该出现在对话里。</p>
     */
    static boolean isHumanUserMessage(DshEvent ev) {
        if (ev == null) return true;
        return isHumanUserMessage(ev.data);
    }

    /**
     * 同上，直接吃原始事件 JSON —— 实时镜像（{@code AIChatActivity.mirrorUserMessage}）也用这个，
     * 保证"历史同步"和"实时镜像"永远是同一把尺子，不会再各写一套。
     */
    public static boolean isHumanUserMessage(org.json.JSONObject data) {
        if (data == null) return true;
        org.json.JSONObject src = data.optJSONObject("source");
        if (src == null) return true;
        String kind = src.optString("kind", "");
        return kind.isEmpty() || "user".equals(kind);
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
    /** 历史派发粒度：每个 turn 按 6 步切一条消息（与投影的 MAX_ROUNDS_PER_MESSAGE 同口径） */
    private static final int SEGMENT_STEPS = 6;

    private static void finishAssistant(List<ChatMessage> out, ChatMessage assistant,
                                        DshTimeline timeline, long now, int[] componentSeq) {
        if (assistant == null) return;
        int total = DshProjection.stepCount(timeline);
        if (total <= SEGMENT_STEPS) {
            DshProjection.project(assistant, timeline, key -> "dsh-I" + (++componentSeq[0]), now);
            if (isEmptyAssistant(assistant)) out.remove(assistant);
            return;
        }
        // 消息派发器改密（2026-11-14）：一个 turn 几十步 → 每 6 步一条消息。
        // 历史里就变成一堆小 item，RecyclerView 按消息回收才有意义（在线 agent 同款粒度）。
        List<ChatMessage> segments = new ArrayList<>();
        int seg = 0;
        for (int from = 0; from < total; from += SEGMENT_STEPS, seg += 1) {
            ChatMessage m = seg == 0 ? assistant : ChatMessage.createAIMessage(
                    assistant.id + "-s" + seg, "", assistant.timestamp, null, 0, 0);
            m.turnId = assistant.turnId;
            m.timestamp = assistant.timestamp + seg;
            DshProjection.project(m, timeline, key -> "dsh-I" + (++componentSeq[0]), now,
                    from, from + SEGMENT_STEPS);
            if (isEmptyAssistant(m)) continue;
            segments.add(m);
        }
        if (segments.isEmpty()) {
            out.remove(assistant);
            return;
        }
        out.remove(assistant);
        out.addAll(segments);
    }

    private static boolean isEmptyAssistant(ChatMessage m) {
        boolean hasText = m.content != null && !m.content.isEmpty();
        boolean hasThinking = m.thinkingContent != null && !m.thinkingContent.isEmpty();
        boolean hasCards = m.components != null && !m.components.isEmpty();
        boolean hasApprovals = m.approvals != null && !m.approvals.isEmpty();
        return !hasText && !hasThinking && !hasCards && !hasApprovals;
    }
}
