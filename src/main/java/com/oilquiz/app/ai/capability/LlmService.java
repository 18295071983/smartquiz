package com.oilquiz.app.ai.capability;

/**
 * LLM 服务 —— 能力缝的 Service Definition（消费者唯一依赖的契约）。
 *
 * <p>Consumer 只依赖本接口，不感知具体 Provider（本地 llama / 在线 API / 未来插件）。
 * 对应 deepseek-harness 能力缝中 “Service Definition” 角色。
 */
public interface LlmService {

    /** 服务商 ID */
    String providerId();

    /** 发起一次流式请求 */
    void stream(LlmRequest request, LlmStreamCallback callback);

    /** 取消当前请求 */
    void cancel();

    /** 是否正在生成 */
    boolean isBusy();
}
