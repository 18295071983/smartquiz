package com.oilquiz.app.ai.prompt;

/**
 * 一次 prompt 组装的上下文：scope（模式）与自定义字段。
 * 对应 dsh 的 {@code AssembleContext}（scope / signal / 自定义字段）。
 * scope 为 null 表示全局组装；指定 scope 时该 scope 的注册段覆盖全局同名段。
 */
public final class AssembleContext {

    /** 组装模式：null=全局；任意字符串=模式（如 "online"/"local"/"agent"），同名字段覆盖全局 */
    public final String scope;

    public AssembleContext(String scope) {
        this.scope = scope;
    }

    public static AssembleContext global() {
        return new AssembleContext(null);
    }

    public static AssembleContext of(String scope) {
        return new AssembleContext(scope);
    }

    /** 是否匹配给定 scope（全局上下文匹配所有；scope 上下文精确匹配） */
    public boolean matches(String candidateScope) {
        if (candidateScope == null) return true;
        return candidateScope.equals(scope);
    }
}
