package com.oilquiz.app.ai.plugin;

/**
 * 插件 —— “一切皆插件”的最小契约。
 *
 * <p>对应 deepseek-harness 的插件模型：挂载时通过 {@link PluginContext}
 * 贡献能力（注册工具 / 订阅事件 / 注册 Provider），卸载时全部撤销
 * （注册即 effect）。{@link PluginRegistry#mount(Plugin)} 负责生命周期，
 * 返回的 disposer 在 close() 时调用 {@link #onUnmount()}。
 */
public interface Plugin {

    /** 全局唯一插件 ID */
    String id();

    /** 展示名 */
    String name();

    /** 挂载：向上下文注册能力（可抛异常中止挂载）。 */
    default void onMount(PluginContext ctx) throws Exception {
    }

    /** 卸载：撤销挂载时注册的一切（幂等）。 */
    default void onUnmount() {
    }
}
