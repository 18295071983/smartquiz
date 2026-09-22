package com.oilquiz.app.ai.prompt;

/**
 * 一个动态运行时上下文段 —— 对应 dsh 的 {@code PromptContext}。
 * <p>
 * 与 section 的区别：context 渲染为独立的消息/快照（"当前运行时上下文"），
 * 不混入系统提示正文；适合放会话级动态事实（模式开关、日期、工具状态、预算要点等）。
 * 每次组装按 order 升序求值；空文本贡献为空（渲染时丢弃）。
 */
public final class PromptContext {

    /** 动态文本提供者：每次组装时求值 */
    public interface Provider {
        String text(AssembleContext ctx);
    }

    public final String name;
    public final int order;
    public final String text;
    public final Provider provider;

    private PromptContext(String name, int order, String text, Provider provider) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("prompt context name must not be empty");
        }
        if (!Double.isFinite(order)) {
            throw new IllegalArgumentException("prompt context \"" + name + "\" order must be a finite number");
        }
        if (text == null && provider == null) {
            throw new IllegalArgumentException("prompt context \"" + name + "\" needs text or provider");
        }
        this.name = name;
        this.order = order;
        this.text = text;
        this.provider = provider;
    }

    public static PromptContext of(String name, int order, String text) {
        return new PromptContext(name, order, text, null);
    }

    public static PromptContext of(String name, int order, Provider provider) {
        return new PromptContext(name, order, null, provider);
    }

    /** 求值本段文本：provider 优先；空/空白视为无贡献 */
    public String resolve(AssembleContext ctx) {
        if (provider != null) {
            String s = provider.text(ctx);
            return s == null ? "" : s;
        }
        return text == null ? "" : text;
    }
}
