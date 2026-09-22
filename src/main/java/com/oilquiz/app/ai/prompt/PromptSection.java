package com.oilquiz.app.ai.prompt;

/**
 * 一个命名系统提示分段 —— 对应 dsh 的 {@code PromptSection}。
 * <p>
 * 命名段 = {name, order, text|provider, complete, interpolate}：
 * <ul>
 *   <li>name：唯一名，同层重复注册抛错（scoped 同名可覆盖 global，见 {@link PromptAssembler}）；</li>
 *   <li>order：升序拼接，等序按 name 字典序（确定性排序，跨机器一致）；</li>
 *   <li>text 或 provider：静态文本，或每次组装时按 {@link AssembleContext} 求值的动态文本；</li>
 *   <li>complete：true 时该段是"完整 prompt"——组装后恢复为唯一段（其余段仍参与变量/上下文解析）；</li>
 *   <li>interpolate：false 时保留 {{变量}} 原文不插值。</li>
 * </ul>
 */
public final class PromptSection {

    /** 动态文本提供者：每次组装时求值 */
    public interface Provider {
        String text(AssembleContext ctx);
    }

    public final String name;
    public final int order;
    public final String text;
    public final Provider provider;
    public final boolean complete;
    public final boolean interpolate;

    private PromptSection(String name, int order, String text, Provider provider,
                          boolean complete, boolean interpolate) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("prompt section name must not be empty");
        }
        if (!Double.isFinite(order)) {
            throw new IllegalArgumentException("prompt section \"" + name + "\" order must be a finite number");
        }
        if (text == null && provider == null) {
            throw new IllegalArgumentException("prompt section \"" + name + "\" needs text or provider");
        }
        this.name = name;
        this.order = order;
        this.text = text;
        this.provider = provider;
        this.complete = complete;
        this.interpolate = interpolate;
    }

    public static PromptSection of(String name, int order, String text) {
        return new PromptSection(name, order, text, null, false, true);
    }

    public static PromptSection of(String name, int order, Provider provider) {
        return new PromptSection(name, order, null, provider, false, true);
    }

    public PromptSection complete(boolean complete) {
        return new PromptSection(name, order, text, provider, complete, interpolate);
    }

    public PromptSection interpolate(boolean interpolate) {
        return new PromptSection(name, order, text, provider, complete, interpolate);
    }

    /** 求值本段文本：provider 优先，无 provider 用静态文本；返回 null 视为空段（渲染时丢弃） */
    public String resolve(AssembleContext ctx) {
        if (provider != null) {
            String s = provider.text(ctx);
            return s == null ? "" : s;
        }
        return text;
    }
}
