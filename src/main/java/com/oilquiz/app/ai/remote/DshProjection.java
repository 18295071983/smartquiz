package com.oilquiz.app.ai.remote;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 时间线 → 聊天消息的<b>唯一</b>投影。
 *
 * <p>旧实现往 {@code msg.assistantTurn.parts} 里写了一份"看起来是渲染真值"的东西，
 * 但渲染层根本不读它：{@code ChatAdapter.bindWithParts} 每次绑定都用
 * {@code AssistantTurn.fromMessageForParts(msg)} 从
 * {@code roundSnapshots + components + content} <b>现建</b> parts。于是那份 parts
 * 是纯死数据 —— 这也是为什么多步对话在手机上"思考一大段、正文一大段、工具卡全堆在最后"：
 * 真正被消费的 {@code roundSnapshots} 从来没被填过。</p>
 *
 * <p>本类把事件时间线投影成 App 已有的消息模型，让 DSH 同步过来的回合与本地/在线回合
 * 走完全相同的渲染管线：</p>
 * <ul>
 *   <li>{@code roundSnapshots}：一步一条（思考 → 正文 → 该步工具），负责<b>交错顺序</b>；</li>
 *   <li>{@code components}：工具卡 + 各类数据卡，负责卡片内容；</li>
 *   <li>{@code content / thinkingContent}：兼容投影（搜索、复制、旧渲染路径）。</li>
 * </ul>
 *
 * <p>不再维护 {@code assistantTurn}：它会触发 {@code applyToMessage} 反向覆盖上面三项，
 * 等于又开一个写者。</p>
 */
public final class DshProjection {

    /** 组件 id 发号器（Activity 用 ChatIdDispatcher，导入器用递增序号） */
    public interface IdSource {
        String idFor(String key);

        /** 无 id 场景（例如纯离线解析） */
        IdSource NONE = key -> null;
    }

    private DshProjection() {
    }

    /**
     * 把时间线写进消息。
     *
     * <p>幂等：同一时间线重复投影的结果完全一致（列表整体重建，不做增量追加），
     * 因此补拉时同一条消息被投影 N 次不会重复插卡。</p>
     *
     * @param now 无事件时间戳时的兜底时间（一般传消息时间或当前时间）
     */
    public static void project(ChatMessage msg, DshTimeline timeline, IdSource ids, long now) {
        if (msg == null || timeline == null) return;
        IdSource source = ids != null ? ids : IdSource.NONE;
        long base = now > 0 ? now : (msg.timestamp > 0 ? msg.timestamp : System.currentTimeMillis());

        List<ChatMessage.RoundSnapshot> snapshots = new ArrayList<>();
        List<ComponentData> components = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        int round = 0;

        for (DshTimeline.Step step : timeline.orderedSteps()) {
            String text = trim(step.text);
            String reason = trim(step.reasoning);
            if (text.isEmpty() && reason.isEmpty() && step.tools.isEmpty()) continue;

            if (!text.isEmpty()) {
                if (content.length() > 0) content.append("\n\n");
                content.append(text);
            }
            if (!reason.isEmpty()) {
                if (thinking.length() > 0) thinking.append("\n\n");
                thinking.append(reason);
            }

            round += 1;
            ChatMessage.RoundSnapshot snapshot = new ChatMessage.RoundSnapshot(round, text, reason, base);
            if (!step.tools.isEmpty()) {
                snapshot.toolCallIds = new ArrayList<>();
                for (DshTimeline.Tool tool : step.tools.values()) {
                    snapshot.toolCallIds.add(tool.callId);
                    components.add(toolComponent(tool, source));
                }
            }
            snapshots.add(snapshot);
        }

        for (DshCards.Card card : timeline.orderedCards()) {
            ComponentData data = ComponentData.of(card.type, card.props);
            data.id = source.idFor("card:" + card.key);
            // 轮次定位：让 `AssistantTurn` 把卡插到它自己那一步的正文/工具之后，
            // 而不是统一堆到整条消息末尾（这正是之前"卡片全在最后"的根因）。
            data.round = timeline.roundOfCard(card.key);
            components.add(data);
        }

        msg.content = content.toString();
        msg.thinkingContent = thinking.length() > 0 ? thinking.toString() : null;
        msg.roundSnapshots = snapshots;
        msg.components = components;
        // 单一投影：渲染真值是 roundSnapshots/components，assistantTurn 会反向覆盖它们
        msg.assistantTurn = null;
    }

    /** 工具卡：props 形状与旧实现逐字段一致（toolName/args/status/result） */
    private static ComponentData toolComponent(DshTimeline.Tool tool, IdSource ids) {
        JSONObject props = DshCards.obj(
                "toolCallId", tool.callId,
                "toolName", tool.name,
                "args", tool.args,
                "status", tool.error ? "failed" : (tool.finished ? "success" : "running"));
        if (!tool.result.isEmpty()) DshCards.put(props, "result", tool.result);
        ComponentData data = ComponentData.of("tool_call", props);
        data.id = ids.idFor("tool:" + tool.callId);
        return data;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
