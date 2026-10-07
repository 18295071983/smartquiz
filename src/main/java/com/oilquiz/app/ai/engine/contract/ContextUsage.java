package com.oilquiz.app.ai.engine.contract;

/**
 * 上下文占用的**引擎无关契约**。
 *
 * <p>对话页的"上下文仪表"需要知道"窗口多大、用了多少"，这件事与引擎无关。但历史上 UI 直接读
 * llama.cpp 的 {@code getContextUsedTokens()} / {@code getContextSize()}，NPU 模式下两者恒为 0
 * → 仪表走 {@code window<=0} 分支显示故障态（用户看到的"上下文故障"）。</p>
 *
 * <p>与 {@link GenSignal} 同样的规则：引擎适配器负责翻译，UI 只读本类，不判断引擎。</p>
 */
public final class ContextUsage {

    /** 数值未知（比 0 更明确：0 可能是真实的空上下文） */
    public static final long UNKNOWN = -1L;

    /** 上下文窗口容量（tokens）；{@link #UNKNOWN} 表示不可用 */
    public final long window;
    /** 已占用（tokens）；{@link #UNKNOWN} 表示不可用 */
    public final long used;
    /** 数据来源引擎名（仅日志/诊断用，UI 不应据此分支） */
    public final String engineName;

    public ContextUsage(long window, long used, String engineName) {
        this.window = window;
        this.used = used;
        this.engineName = engineName == null ? "unknown" : engineName;
    }

    /** 是否有可用的窗口容量（UI 据此决定是否显示百分比而不是故障态） */
    public boolean hasWindow() {
        return window > 0;
    }

    /** 占用百分比 0-100；窗口不可用时返回 {@link #UNKNOWN} */
    public int percent() {
        if (window <= 0) {
            return (int) UNKNOWN;
        }
        long u = Math.max(0, used == UNKNOWN ? 0 : used);
        return (int) Math.min(100, u * 100 / window);
    }

    @Override
    public String toString() {
        return "ContextUsage{" + engineName + " used=" + used + " window=" + window + "}";
    }
}
