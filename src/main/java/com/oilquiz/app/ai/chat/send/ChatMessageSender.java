package com.oilquiz.app.ai.chat.send;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.route.MessageRouteDecider;

import java.util.List;

/**
 * 聊天消息发送编排器（全局可复用，逻辑层）。
 *
 * 从 AIChatActivity sendMessage / processChatMessage 的编排逻辑抽取：
 * 守卫（生成中/空消息）→ 附件/模型守卫 → 用户消息构建 → 路由分发。
 * 决策由 {@link MessageRouteDecider} 提供，执行由宿主按 Route 完成。
 */
public class ChatMessageSender {

    /** 宿主执行能力 */
    public interface Host {
        boolean isGenerating();
        /** 通知"仍在生成" */
        void onBlockedGenerating();
        /** 空消息拦截提示 */
        void onBlockedEmpty();
        /** 模型未加载：宿主决定加载提示并仅入历史 */
        boolean onNeedModelLoad(String message);
        /** 新增用户消息（普通文本） */
        void onAddUserText(String message, boolean fromVoice);
        /** 新增用户消息（带附件） */
        void onAddUserWithAttachments(ChatMessage userMessage);
        /** 附件清空（宿主清输入区） */
        void onAttachmentsConsumed();
        /** 附件 + Agent 路径 */
        void onRouteAttachAgent(String agentMessage, List<ChatMessage.Attachment> attachments);
        /** 附件 + 本地预处理路径 */
        void onRouteAttachLocalParse(String agentMessage, List<ChatMessage.Attachment> attachments);
        /** 无附件：普通/Agent 路径 */
        void onRoutePlain(String message);
    }

    private ChatMessageSender() {}

    /** 直发附件（无文字输入）时的默认占位文案 */
    public static final String DEFAULT_ATTACHMENT_MESSAGE = "请分析这些附件的内容";

    /** 构建用户消息（附件 / 普通 / 语音标记） */
    public static ChatMessage buildUserMessage(String message, List<ChatMessage.Attachment> attachments,
                                               boolean voiceInput) {
        String userContent = message == null || message.isEmpty()
                ? DEFAULT_ATTACHMENT_MESSAGE : message;
        ChatMessage userMessage = ChatMessage.createUserMessage(userContent, attachments);
        if (voiceInput) userMessage.voiceInput = true;
        return userMessage;
    }

    /**
     * 发送编排：守卫 → 构建 → 分发（决策与执行分离）。
     * 附件有图片/在线时走 ATTACH_AGENT；否则本地解析/普通。
     */
    public static void dispatch(String rawMessage, List<ChatMessage.Attachment> attachments,
                                boolean isOnlineModel, boolean localAgentEnabled,
                                boolean modelLoaded, boolean fileExtractorAvailable,
                                boolean voiceInput, Host host) {
        if (host.isGenerating()) {
            host.onBlockedGenerating();
            return;
        }
        String message = rawMessage == null ? "" : rawMessage.trim();
        boolean hasImage = false;
        if (attachments != null) {
            for (ChatMessage.Attachment att : attachments) {
                if (att != null && "image".equals(att.type)) { hasImage = true; break; }
            }
        }
        if (message.isEmpty() && (attachments == null || attachments.isEmpty())) {
            host.onBlockedEmpty();
            return;
        }

        // 无图片且模型未加载：守卫失败（仅入历史不推理）
        if (!hasImage && !modelLoaded && !isOnlineModel) {
            if (!host.onNeedModelLoad(message)) return;
        }

        if (attachments != null && !attachments.isEmpty()) {
            ChatMessage userMessage = buildUserMessage(message, attachments, voiceInput);
            host.onAddUserWithAttachments(userMessage);
            host.onAttachmentsConsumed();

            String agentMessage = message.isEmpty() ? DEFAULT_ATTACHMENT_MESSAGE : message;
            MessageRouteDecider.Route route = MessageRouteDecider.decide(
                    new MessageRouteDecider.DecisionInput()
                            .message(agentMessage).attachments(attachments)
                            .hasImageAttachment(hasImage).isOnlineModel(isOnlineModel)
                            .localAgentEnabled(localAgentEnabled).modelLoaded(true)
                            .fileExtractorAvailable(fileExtractorAvailable));
            if (route == MessageRouteDecider.Route.ATTACH_AGENT) {
                host.onRouteAttachAgent(agentMessage, attachments);
            } else {
                host.onRouteAttachLocalParse(agentMessage, attachments);
            }
            return;
        }

        host.onAddUserText(message, voiceInput);
        MessageRouteDecider.Route route = MessageRouteDecider.decideNoAttachment(isOnlineModel, localAgentEnabled);
        host.onRoutePlain(message);
    }
}
