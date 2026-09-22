package com.oilquiz.app.ai.capability;

/**
 * LLM 流式回调 —— Consumer 侧统一收口（与引擎回调解耦）。
 *
 * <p>与 {@link com.oilquiz.app.ai.agent.AgentCallback} 同构但独立，
 * 保证能力缝不被具体引擎类型污染。
 */
public interface LlmStreamCallback {

    /** 正文 token 流 */
    void onToken(String token);

    /** 思考链 token 流 */
    void onThinkingToken(String token);

    /** 思考链结束 */
    void onThinkingEnd();

    /** 工具调用开始 */
    void onToolCallStart(String toolCallId, String toolName, String args);

    /** 工具调用完成 */
    void onToolCallComplete(String toolCallId, String toolName, boolean success, String result);

    /** 生成完成 */
    void onComplete(String fullText);

    /** 出错 */
    void onError(String error);

    /** 空实现基类（只关心部分事件的 Consumer 可继承） */
    class Adapter implements LlmStreamCallback {
        @Override
        public void onToken(String token) {
        }

        @Override
        public void onThinkingToken(String token) {
        }

        @Override
        public void onThinkingEnd() {
        }

        @Override
        public void onToolCallStart(String toolCallId, String toolName, String args) {
        }

        @Override
        public void onToolCallComplete(String toolCallId, String toolName,
                                       boolean success, String result) {
        }

        @Override
        public void onComplete(String fullText) {
        }

        @Override
        public void onError(String error) {
        }
    }
}
