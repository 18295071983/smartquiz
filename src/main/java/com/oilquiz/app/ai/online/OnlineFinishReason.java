package com.oilquiz.app.ai.online;

/**
 * 在线模式最近一次调用的 finish_reason。
 *
 * <p>OpenAI 兼容接口的取值：{@code stop}（自然结束）、{@code length}（达到输出上限）、
 * {@code content_filter}（服务商内容过滤）、{@code tool_calls}（调用工具，随后继续）。
 * UI 的"本轮结束原因"与本地 GenSM 的 stop_cause 是两套来源，但展示口径统一；
 * 这里只存最近一次结果供完成回调后读取，不参与任何路由判断。</p>
 */
public final class OnlineFinishReason {

    public static volatile String last;

    private OnlineFinishReason() {
    }
}
