package com.oilquiz.app.ai.engine.contract;

/**
 * {@link GenSignal} 的引擎分派入口。
 *
 * <p>UI（状态栏、上下文仪表、token 徽标、Agent 状态显示）一律通过本类取生成状态，
 * 而不是直连 {@code LlamaHelper}。这样做的好处：
 * <ul>
 *   <li>UI 与引擎实现解耦，可单测（喂一个假的 GenSignal 即可渲染）；</li>
 *   <li>契约集中在 {@link LlamaSignalAdapter} 一处，字段语义变了只改那里。</li>
 * </ul>
 *
 * <p><b>GENIEX-REMOVED(2026-11-09)</b>：这里原有第二条分支（{@code NpuSignalAdapter}，
 * 读 GenieX 的 NpuEngineState）。GenieX 已整条删除，现在只有本地 llama.cpp 一个引擎，
 * 于是本类退化为对 {@link LlamaSignalAdapter} 的一层"永不返回 null / 永不抛"包装。
 * 这层包装仍有价值：调用方（500ms 轮询的状态栏等）不需要自己写 try/catch 与 null 判断。</p>
 *
 * <p><b>注意</b>：本类只负责"生成状态"这一件事。像"是否使用在线模型"这类**路由决策**不属于
 * 生成状态契约，仍由原有路由组件判断 —— 契约要保持单一职责，否则又会变成什么都往里塞的接口。</p>
 */
public final class GenSignalSource {

    private GenSignalSource() {
    }

    /**
     * 当前生成状态。**永不为 null**（拿不到时返回 idle 信号），
     * 以避免调用方到处写 null 判断。
     */
    public static GenSignal current() {
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
            return LlamaSignalAdapter.kvStats();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 上下文占用。**永不为 null**：拿不到时返回 window/used 均为
     * {@link ContextUsage#UNKNOWN} 的实例，由 UI 决定如何降级（例如回退到字符估算）。
     */
    public static ContextUsage contextUsage() {
        try {
            return LlamaSignalAdapter.contextUsage();
        } catch (Throwable t) {
            return new ContextUsage(ContextUsage.UNKNOWN, ContextUsage.UNKNOWN, "none");
        }
    }
}
