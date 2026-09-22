package com.oilquiz.app.ai.capability;

/**
 * LLM Provider —— 能力缝的 Service Provider 角色。
 *
 * <p>负责按需创建 {@link LlmService} 实例。对应 deepseek-harness
 * 能力缝中 “Service Provider” 角色：注册进 {@link LlmRegistry} 后
 * 由 Consumer 按 id 或能力匹配解析。
 */
public interface LlmProvider {

    /** 全局唯一 Provider ID（如 "local" / "online"） */
    String id();

    /** 展示名 */
    String displayName();

    /** 是否支持该请求（按 model / 能力门控） */
    boolean supports(LlmRequest request);

    /** 创建一个服务实例（每次调用返回新实例，由 Consumer 管理生命周期） */
    LlmService createService();
}
