package com.oilquiz.app.ai.chat.turn;

import com.oilquiz.app.ai.chat.ChatMessage;

import java.util.List;

/**
 * 历史恢复期的 canonical turn 归一（2026-10-24）。
 *
 * <p>问题：{@code ChatMessage.assistantTurn} 虽然随历史落盘，但只有 2026-10-21 之后的新消息才有；
 * 旧数据恢复后该字段为 null，{@code ChatAdapter} 的 parts 闸门直接把整条消息判回 legacy 路径。
 * 同时渲染时的 parts 又是由 {@link AssistantTurn#fromMessage} 现场重建的，于是同一条消息存在
 * <b>两份 parts 顺序</b>：落盘的那份（用来投影 content/bounds）和重建的那份（用来渲染）。
 * 两者一旦不一致（旧版本落盘的顺序、缺 toolCallId 的组件被补到末尾……），退出重进后
 * "思考→正文→工具→产物"的时序就会与实时不一致。</p>
 *
 * <p>做法：在加载归一层（与 {@link com.oilquiz.app.ai.chat.RoundBounds#normalizeAll} 同一出口、
 * 同一时机）把每条已完成 AI 消息的 {@code assistantTurn} 统一替换成
 * {@link AssistantTurn#fromMessage} 的结果——也就是与实时提交<b>同一个生产者、同一套入参</b>。
 * 新旧数据由此拿到同一口径的 parts，顺序不再有第二个来源。</p>
 *
 * <p>flag=false（出厂档）时旧路径仍照旧渲染，这里只多做了一件幂等的事：把 turn 归一成
 * {@link AssistantTurn#fromMessage} 的结果。两条护栏把它的影响压到最小（另有下文那条"落盘 turn 保留"规则）：</p>
 * <ul>
 *   <li>重建不出任何 part（空正文、无组件）的消息不回填，维持"无 canonical turn"的原状，
 *       免得渲染器接管后渲出空气泡；</li>
 *   <li>只有"逐轮快照是权威"的消息才反向投影 {@code content/contentRoundBounds}；旧数据的 turn
 *       本来就从 content/bounds 派生，再投影回去会把分段边界的空白规范化掉，等于改写用户既有正文
 *       （该规则由 {@link AssistantTurn#applyToMessage} 自身按 {@code textPartsAreSlices} 把关）。</li>
 * </ul>
 *
 * <p>落盘 turn 若含 {@code fromMessage} 重建不出来的 part（error/approval 这类尚无生产者的类型，
 * 见 {@code docs/ui/parts-architecture-upgrade.md} §11.7），保留落盘原样不覆盖——重建更弱的一方
 * 不该抹掉更强的真值。</p>
 */
public final class AssistantTurnRestore {

    private AssistantTurnRestore() {
    }

    /** 批量归一（历史/会话加载出口）。 */
    public static void restoreAll(List<ChatMessage> messages) {
        if (messages == null) return;
        for (ChatMessage m : messages) restore(m);
    }

    /**
     * 归一条消息的 canonical turn。
     *
     * @return 真正写入了 turn 返回 true（含覆盖落盘值）
     */
    public static boolean restore(ChatMessage msg) {
        if (msg == null || !msg.isAIMessage() || !msg.isCompleted()) {
            return false;
        }
        if (msg.assistantTurn != null && !isDerivableFromMessage(msg.assistantTurn)) {
            return false; // 落盘 turn 含重建不出的 part，保留它
        }
        AssistantTurn rebuilt = AssistantTurn.fromMessage(msg);
        if (rebuilt == null || rebuilt.parts == null || rebuilt.parts.isEmpty()) {
            return false; // 无可渲染结构，维持原状
        }
        // 逐轮快照是权威时才真投影；切片口径的 turn 由 applyToMessage 自己跳过（content/bounds
        // 才是它的真值来源），所以这里无条件调用也不会改写旧数据的正文。
        rebuilt.applyToMessage(msg);
        msg.assistantTurn = rebuilt;
        return true;
    }

    /** 落盘 turn 的每个 part 是否都能由 {@link AssistantTurn#fromMessage} 从兼容字段重建。 */
    private static boolean isDerivableFromMessage(AssistantTurn turn) {
        if (turn.parts == null || turn.parts.isEmpty()) {
            return false; // 空落盘 turn 没有保留价值，交给重建
        }
        for (AssistantTurn.Part p : turn.parts) {
            if (p == null || p.type == null) {
                return false;
            }
            boolean derivable = AssistantTurn.Part.TYPE_TEXT.equals(p.type)
                    || AssistantTurn.Part.TYPE_REASONING.equals(p.type)
                    || AssistantTurn.Part.TYPE_TOOL_CALL.equals(p.type)
                    || AssistantTurn.Part.TYPE_TOOL_RESULT.equals(p.type)
                    || AssistantTurn.Part.TYPE_DATA.equals(p.type);
            if (!derivable) {
                return false; // error/approval/tool_group（构建期折叠态）等 → 保留落盘
            }
        }
        return true;
    }
}
