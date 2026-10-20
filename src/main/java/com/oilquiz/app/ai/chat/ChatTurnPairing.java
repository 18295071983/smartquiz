package com.oilquiz.app.ai.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户消息 / AI 回复成对展示的兜底（2026-10-21）。
 *
 * <p>约定：每条带 turnId 的 USER 都必须有同一 turnId 的 AI 回复；没有就补一条
 * 「未生成回复」的 AI 占位（ERROR），而不是只显示 USER、让 AI 永久缺失。</p>
 *
 * <p>只做纯数据插入，不碰 Android UI；subId 由调用方通过 ChatIdDispatcher 发放，
 * 避免在工具类里推进全局序号。</p>
 */
public final class ChatTurnPairing {

    public static final String ORPHAN_REPLY_TEXT = "未生成回复。点重新生成重试。";

    private ChatTurnPairing() {
    }

    /**
     * 就地补齐缺失的 AI 回复。
     *
     * @return 本次插入的 AI 占位消息（调用方负责补 subId、notify、保存）
     */
    public static List<ChatMessage> insertMissingReplies(List<ChatMessage> messages, String replyText) {
        List<ChatMessage> added = new ArrayList<>();
        if (messages == null || messages.isEmpty()) return added;
        String text = replyText != null && !replyText.isEmpty()
                ? replyText : ORPHAN_REPLY_TEXT;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage user = messages.get(i);
            if (user == null || user.type != ChatMessage.MessageType.USER) continue;
            if (user.turnId == null || user.turnId.isEmpty()) continue;
            String turn = user.turnId;
            boolean hasReply = false;
            for (int j = i + 1; j < messages.size(); j++) {
                ChatMessage next = messages.get(j);
                if (next == null) continue;
                if (next.type == ChatMessage.MessageType.USER
                        && next.turnId != null && !turn.equals(next.turnId)) {
                    break;
                }
                if (next.type == ChatMessage.MessageType.AI && turn.equals(next.turnId)) {
                    hasReply = true;
                    break;
                }
            }
            if (hasReply) continue;
            ChatMessage ai = ChatMessage.createAIMessage(
                    java.util.UUID.randomUUID().toString(), text,
                    Math.max(System.currentTimeMillis(), user.timestamp + 1), null, 0, 0);
            ai.turnId = turn;
            ai.status = ChatMessage.MessageStatus.ERROR;
            ai.inferenceProgress = new ChatMessage.InferenceProgress(
                    ChatMessage.InferencePhase.FAILED);
            messages.add(i + 1, ai);
            added.add(ai);
            i++; // 跳过刚插入的 AI
        }
        return added;
    }
}
