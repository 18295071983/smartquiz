package com.oilquiz.app.ai.service;

import com.oilquiz.app.ai.model.ProviderConfigManager;

/**
 * 在线对话**协议族**枚举 —— 协议分发的唯一事实源。
 *
 * <p>把此前散落在调用方的一串字符串判断（{@code apiUrl.contains("anthropic")}、
 * {@code endpoint.endsWith("/responses")}、{@code PROTOCOL_OLLAMA.equals(...)}）
 * 收敛到一处。</p>
 *
 * <p><b>为什么需要它</b>：每新增一种实现（新的协议、或某家改用官方 SDK）都要改
 * 调用链上的多处 if/else；收敛后，新增即"加一个枚举值 + 在 {@link #of} 里给判据
 * + 在分发布点加一个分支"，调用方不再感知。</p>
 *
 * <p>判定依据：{@code providers.json} 的 {@code services.chat.endpoint} 形态优先，
 * 地址形态兜底（Anthropic 的 baseUrl 无 {@code /v1} 后缀，只能靠域名识别）。</p>
 */
public enum ChatProtocol {

    /** OpenAI 兼容 {@code /chat/completions} —— 覆盖 26 家，含各家的官方兼容层 */
    OPENAI_COMPAT,

    /** Anthropic Messages {@code /v1/messages} */
    ANTHROPIC_MESSAGES,

    /** OpenAI Responses {@code /responses} */
    OPENAI_RESPONSES,

    /** Ollama 原生 {@code /api/chat} —— ndjson 流，非 SSE */
    OLLAMA_NATIVE;

    /**
     * 由 API 地址判定协议。判据全部来自配置表，不硬编码厂商名单（域名兜底除外）。
     *
     * @param apiUrl 服务商 baseUrl（如 {@code https://open.bigmodel.cn/api/paas/v4}）
     * @return 协议族；无法判定时为 {@link #OPENAI_COMPAT}（最广兼容）
     */
    public static ChatProtocol of(String apiUrl) {
        String p = ProviderConfigManager.get().getChatProtocol(apiUrl);
        if (ProviderConfigManager.PROTOCOL_OLLAMA.equals(p)) return OLLAMA_NATIVE;
        if (ProviderConfigManager.PROTOCOL_ANTHROPIC.equals(p)) return ANTHROPIC_MESSAGES;
        if (ProviderConfigManager.PROTOCOL_RESPONSES.equals(p)) return OPENAI_RESPONSES;
        return OPENAI_COMPAT;
    }

    /** 该协议是否由本工程自己解析 SSE/ndjson（用于决定是否需要文本兜底剥离） */
    public boolean isStreamingTextProtocol() {
        return this == OPENAI_COMPAT || this == ANTHROPIC_MESSAGES
                || this == OPENAI_RESPONSES || this == OLLAMA_NATIVE;
    }

    /** Anthropic Messages 协议（调用方用它替代原来的字符串判断） */
    public static boolean isAnthropic(String apiUrl) {
        return of(apiUrl) == ANTHROPIC_MESSAGES;
    }

    /** Ollama 原生协议 */
    public static boolean isOllamaNative(String apiUrl) {
        return of(apiUrl) == OLLAMA_NATIVE;
    }
}
