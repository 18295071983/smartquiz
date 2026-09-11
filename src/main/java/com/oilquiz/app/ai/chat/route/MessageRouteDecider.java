package com.oilquiz.app.ai.chat.route;

import java.util.List;

/**
 * 聊天发送路由决策器（全局可复用）。
 *
 * 从 AIChatActivity 发送分发逻辑抽取的纯决策组件：根据消息文本、附件类型、
 * 模型状态与能力，判定消息应走哪条执行通道。只做判定，不执行——
 * 执行由宿主按 {@link Route} 分发。
 *
 * 用法：
 * <pre>
 * MessageRouteDecider.DecisionInput in = new MessageRouteDecider.DecisionInput()
 *         .message(text).attachments(list).isGenerating(false)
 *         .isOnlineModel(true).localAgentEnabled(false)...
 * MessageRouteDecider.Route route = MessageRouteDecider.decide(in);
 * </pre>
 */
public class MessageRouteDecider {

    /** 发送路由结果 */
    public enum Route {
        /** 拦截：正在生成中 */
        BLOCKED_GENERATING,
        /** 拦截：空消息且无附件 */
        BLOCKED_EMPTY,
        /** 需要先加载本地模型（无图片附件时守卫） */
        NEED_MODEL_LOAD,
        /** 在线模型 → 完整 Agent（工具调用自动） */
        ONLINE_AGENT,
        /** 本地 Agent 开关开启 → 本地 Agent 循环 */
        LOCAL_AGENT,
        /** 带附件（图片或在线）→ 附件走 Agent（OCR + 分析） */
        ATTACH_AGENT,
        /** 带附件（非图片，本地）→ 附件本地预处理 */
        ATTACH_LOCAL_PARSE,
        /** 普通本地对话 */
        PLAIN
    }

    /** 决策输入（构造后链式设置） */
    public static class DecisionInput {
        public String message;
        public List<?> attachments;
        public boolean isGenerating;
        public boolean hasImageAttachment;
        public boolean isOnlineModel;
        public boolean localAgentEnabled;
        /** 本地模型是否已加载（ensureModelLoaded 结果） */
        public boolean modelLoaded;
        /** 本地文件内容提取器是否可用（决定附件本地预处理通道） */
        public boolean fileExtractorAvailable;

        public DecisionInput message(String v) { this.message = v; return this; }
        public DecisionInput attachments(List<?> v) { this.attachments = v; return this; }
        public DecisionInput isGenerating(boolean v) { this.isGenerating = v; return this; }
        public DecisionInput hasImageAttachment(boolean v) { this.hasImageAttachment = v; return this; }
        public DecisionInput isOnlineModel(boolean v) { this.isOnlineModel = v; return this; }
        public DecisionInput localAgentEnabled(boolean v) { this.localAgentEnabled = v; return this; }
        public DecisionInput modelLoaded(boolean v) { this.modelLoaded = v; return this; }
        public DecisionInput fileExtractorAvailable(boolean v) { this.fileExtractorAvailable = v; return this; }

        public boolean hasAttachments() {
            return attachments != null && !attachments.isEmpty();
        }

        public boolean isEmptyMessage() {
            return message == null || message.trim().isEmpty();
        }
    }

    private MessageRouteDecider() {}

    /**
     * 判定消息应走的执行通道（只判定不执行）。
     * 决策顺序对齐对话页 sendMessage + processChatMessage：
     * 守卫（生成中/空消息/模型加载）→ 附件分流（图片或在线→Agent；否则本地预处理）
     * → 无附件分流（在线或本地Agent→Agent；否则普通）。
     */
    public static Route decide(DecisionInput in) {
        if (in.isGenerating) return Route.BLOCKED_GENERATING;
        if (in.isEmptyMessage() && !in.hasAttachments()) return Route.BLOCKED_EMPTY;

        if (in.hasAttachments()) {
            if (in.hasImageAttachment || in.isOnlineModel) {
                return Route.ATTACH_AGENT;
            }
            return in.fileExtractorAvailable ? Route.ATTACH_LOCAL_PARSE : Route.PLAIN;
        }

        // 无附件：在线模型 → 完整 Agent；本地 Agent 开关开启 → 本地 Agent 循环；否则普通
        if (in.isOnlineModel || in.localAgentEnabled) {
            return Route.ONLINE_AGENT;
        }
        return Route.PLAIN;
    }

    /** 便捷入口：无附件场景判定（由调用方先完成模型加载守卫后调用） */
    public static Route decideNoAttachment(boolean isOnlineModel, boolean localAgentEnabled) {
        if (isOnlineModel || localAgentEnabled) return Route.ONLINE_AGENT;
        return Route.PLAIN;
    }
}
