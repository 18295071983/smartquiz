package com.oilquiz.app.ai.chat;

/**
 * 推理状态机的"用户可见短标签"唯一来源（2026-10-25）。
 *
 * <p>底部生成状态行（输入胶囊上方）和气泡内「深度思考」折叠头必须显示同一套词：
 * 用户在真机上同时看到"生成中"和"正在思考…"两种说法会以为状态对不上（2026-10-25 反馈）。
 * 所以两边都从这里取标签，不再各自硬编码。</p>
 *
 * <p>口径：</p>
 * <ul>
 *   <li>{@link ChatMessage.InferencePhase#THINKING} → "思考中"</li>
 *   <li>{@link ChatMessage.InferencePhase#GENERATING}/{@code DECODING} → "生成中"</li>
 *   <li>等待类阶段（WAITING/INITIALIZING/LOADING_MODEL/ENCODING/PREFILL）→ "等待响应"</li>
 *   <li>完成/失败/空闲 → {@code null}（"思考块"这时应该回到"已深度思考"，不再挂活动态）</li>
 * </ul>
 */
public final class InferenceStatusLabel {

    public static final String WAITING = "等待响应";
    public static final String THINKING = "思考中";
    public static final String GENERATING = "生成中";

    private InferenceStatusLabel() {
    }

    /** 阶段 → 短标签；不属于"正在生成"的阶段（完成/失败/空闲/未知）返回 null。 */
    public static String shortLabel(ChatMessage.InferencePhase phase) {
        if (phase == null) return null;
        switch (phase) {
            case THINKING:
                return THINKING;
            case GENERATING:
            case DECODING:
                return GENERATING;
            case WAITING:
            case INITIALIZING:
            case LOADING_MODEL:
            case ENCODING:
            case PREFILL:
                return WAITING;
            default:
                return null;
        }
    }

    /**
     * 思考段是否仍处于"激活（正在思考）"状态——即它的折叠头该不该显示动态状态文字。
     *
     * <p>阶段未知（null）按激活处理：历史消息 / 旧引擎没有状态机信息，保持改造前
     * "流式即思考中"的行为；一旦进入正文（GENERATING/DECODING）或结束（COMPLETED/FAILED/IDLE）
     * 就回到"已深度思考"。</p>
     */
    public static boolean isReasoningActive(ChatMessage.InferencePhase phase) {
        if (phase == null) return true;
        switch (phase) {
            case GENERATING:
            case DECODING:
            case COMPLETED:
            case FAILED:
            case IDLE:
                return false;
            default:
                return true;
        }
    }
}
