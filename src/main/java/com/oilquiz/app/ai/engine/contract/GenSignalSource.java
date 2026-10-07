package com.oilquiz.app.ai.engine.contract;

/**
 * {@link GenSignal} 的引擎分派入口 —— **全项目唯一允许判断"用哪个引擎"的地方**。
 *
 * <p>UI（状态栏、上下文仪表、token 徽标、Agent 状态显示）一律通过本类取生成状态，
 * 不再自己 {@code isNpuEngineEnabled()} 然后分支。这样做的好处：
 * <ul>
 *   <li>新增引擎只需在 {@link #current()} 里加一条分支，UI 零改动；</li>
 *   <li>"引擎判断"集中一处，不会再散落到各处导致漏改（本项目已多次因此出故障）；</li>
 *   <li>UI 与引擎实现彻底解耦，可单测（喂一个假的 GenSignal 即可渲染）。</li>
 * </ul>
 *
 * <p><b>注意</b>：本类只负责"生成状态"这一件事。像"是否使用在线模型"这类**路由决策**不属于
 * 生成状态契约，仍由原有路由组件判断 —— 契约要保持单一职责，否则又会变成什么都往里塞的接口。</p>
 */
public final class GenSignalSource {

    private GenSignalSource() {
    }

    /**
     * 当前生成状态。**永不为 null**（无引擎可用时返回 idle 信号），
     * 以避免调用方到处写 null 判断。
     */
    public static GenSignal current() {
        try {
            if (NpuSignalAdapter.isActive()) {
                return NpuSignalAdapter.current();
            }
        } catch (Throwable ignored) {
        }
        try {
            return LlamaSignalAdapter.current();
        } catch (Throwable t) {
            return GenSignal.idle("none");
        }
    }

    /**
     * KV 缓存统计（可选能力）。返回 {@code null} 表示**当前引擎不具备该能力**
     * 或暂时拿不到，UI 应隐藏对应控件。
     */
    public static GenSignal.KvStats kvStats() {
        try {
            if (NpuSignalAdapter.isActive()) {
                return NpuSignalAdapter.kvStats();   // NPU 侧恒为 null（能力不具备）
            }
        } catch (Throwable ignored) {
        }
        try {
            return LlamaSignalAdapter.kvStats();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 上下文占用（引擎无关）。**永不为 null**：拿不到时返回 window/used 均为
     * {@link ContextUsage#UNKNOWN} 的实例，由 UI 决定如何降级（例如回退到字符估算）。
     *
     * <p>这是"上下文故障"那类问题的正解：UI 不再直接问 llama.cpp，而是问契约，
     * 由适配器按当前引擎给出正确的数字。</p>
     */
    public static ContextUsage contextUsage() {
        try {
            if (NpuSignalAdapter.isActive()) {
                return NpuSignalAdapter.contextUsage();
            }
        } catch (Throwable ignored) {
        }
        try {
            return LlamaSignalAdapter.contextUsage();
        } catch (Throwable t) {
            return new ContextUsage(ContextUsage.UNKNOWN, ContextUsage.UNKNOWN, "none");
        }
    }
}
