package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;
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
 * <p>数据来源：插件 {@code /events?since=0&follow=0} 的原样事件重放（live 读内存、
 * 非 live 解压会话日志）。转换规则与实时转换器一致：reasoning → thinkingContent、
 * text → content、tool/call+tool/result → components 里的 tool_call 卡。</p>
 */
public final class DshHistoryImporter {

    private DshHistoryImporter() {
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
        List<JSONObject> events = fetchAllEvents(ctx, target);
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

    /** 一次性把事件流读完（follow=0；插件侧会按 seq 顺序重放） */
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

    private static List<JSONObject> fetchAllEvents(Context ctx, String target) throws Exception {
        final List<JSONObject> out = new ArrayList<>();
        RemoteDshTool.subscribeEvents(ctx, target, 0, 90, false,
                new RemoteDshTool.DshEventListener() {
                    @Override public void onReady(int seq) { }

                    @Override public void onSnapshot(int seq, JSONArray turns) { }

                    @Override public void onEvent(JSONObject event) {
                        if (event != null) out.add(event);
                    }

                    @Override public void onEnd() { }

                    @Override public void onError(String message) { }
                });
        return out;
    }

    /** DSH 事件 → 本机 ChatMessage（用户 / AI + 工具卡组件） */
    private static List<ChatMessage> buildMessages(List<JSONObject> events) {
        List<ChatMessage> out = new ArrayList<>();
        ChatMessage lastAi = null;
        for (JSONObject ev : events) {
            String type = ev.optString("type", "");
            long ts = ev.optLong("time", 0L);
            if (ts <= 0) ts = System.currentTimeMillis();
            JSONObject data = ev.optJSONObject("data");

            if ("user/message".equals(type) && data != null) {
                String text = collectText(data.optJSONArray("content"));
                if (text.isEmpty()) continue;
                ChatMessage msg = ChatMessage.createUserMessage(text);
                msg.timestamp = ts;
                out.add(msg);
                lastAi = null;
            } else if ("assistant/message".equals(type) && data != null) {
                JSONObject message = data.optJSONObject("message");
                JSONArray parts = message == null ? null : message.optJSONArray("content");
                StringBuilder answer = new StringBuilder();
                StringBuilder thinking = new StringBuilder();
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i += 1) {
                        JSONObject p = parts.optJSONObject(i);
                        if (p == null) continue;
                        String pt = p.optString("type");
                        String tx = p.optString("text");
                        if (tx.isEmpty()) continue;
                        if ("reasoning".equals(pt) || "thinking".equals(pt)) thinking.append(tx);
                        else answer.append(tx);
                    }
                }
                if (answer.length() == 0 && thinking.length() == 0) continue;
                ChatMessage ai = ChatMessage.createAIMessage(
                        java.util.UUID.randomUUID().toString(), answer.toString(), ts, null, 0, 0);
                if (thinking.length() > 0) ai.thinkingContent = thinking.toString();
                out.add(ai);
                lastAi = ai;
            } else if ("tool/call".equals(type) && data != null) {
                if (lastAi == null) continue;
                String callId = data.optString("callId");
                if (callId.isEmpty()) continue;
                JSONObject props = new JSONObject();
                try {
                    props.put("toolCallId", callId);
                    props.put("toolName", data.optString("name", "工具"));
                    props.put("args", data.optString("arguments"));
                    props.put("status", "success");
                } catch (Exception ignored) { /* 字段坏了就跳过 */ }
                if (lastAi.components == null) lastAi.components = new ArrayList<>();
                lastAi.components.add(ComponentData.of("tool_call", props));
            } else if ("tool/result".equals(type) && data != null) {
                if (lastAi == null || lastAi.components == null) continue;
                String callId = data.optString("toolCallId");
                String text = "";
                JSONObject message = data.optJSONObject("message");
                if (message != null) text = collectText(message.optJSONArray("content"));
                for (ComponentData c : lastAi.components) {
                    if (c == null || c.props == null) continue;
                    if (!callId.equals(c.props.optString("toolCallId"))) continue;
                    try {
                        c.props.put("result", text);
                        c.props.put("status",
                                data.optBoolean("isError", false) ? "failed" : "success");
                    } catch (Exception ignored) { /* 忽略 */ }
                    break;
                }
            }
        }
        return out;
    }

    private static String collectText(JSONArray parts) {
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
}
