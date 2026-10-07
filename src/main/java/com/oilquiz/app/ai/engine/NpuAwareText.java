package com.oilquiz.app.ai.engine;

import com.oilquiz.app.ai.jni.LlamaHelper;

/**
 * NPU 感知的文本/上下文工具。
 *
 * <p><b>为什么需要它</b>：本项目有两个推理后端（llama.cpp 与 GenieX NPU），但很多通用逻辑
 * 直接依赖 llama.cpp 的 native 状态。NPU 模式下 llama.cpp 的模型与上下文**故意不创建**，
 * 于是这些调用恒返回 0/空，导致：
 * <ul>
 *   <li>token 统计恒为 0 → Agent 的预算裁剪失效；</li>
 *   <li>上下文窗口恒为 0 → 取不到真实窗口，回退成写死的 8192；</li>
 *   <li>思考标签恒为空且实现里失败不缓存 → 高频重复打 JNI。</li>
 * </ul>
 * 这里把"按当前引擎选择正确数据源"的逻辑集中一处，避免每个调用点各写一遍 NPU 判断。</p>
 *
 * <p>所有方法在 NPU 引擎关闭时**完全保持原有 llama.cpp 行为**。</p>
 */
public final class NpuAwareText {

    private NpuAwareText() {
    }

    /** 当前是否 NPU 引擎（引擎开关，非"已加载"——加载中也应由 NPU 侧回答） */
    public static boolean isNpu() {
        try {
            return NpuLlmChat.isEngineEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 估算 token 数（NPU 侧口径）。
     *
     * <p>与 {@code NpuEngineRouter.estimatePromptTokens} 一致：CJK 约 1.5 字符/token，
     * 其余约 4 字符/token。NPU 的 tokenizer 在 native 侧，Java 拿不到精确值，故用估算。</p>
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4E00' && c <= '\u9FFF') {
                cjk++;
            }
        }
        int other = text.length() - cjk;
        return (int) (cjk / 1.5 + other / 4.0) + 1;
    }

    /**
     * 统计文本 token 数：NPU 模式用估算，否则用 llama.cpp 的 native tokenizer
     * （native 返回 <=0 时同样退回估算，保持原有兜底语义）。
     */
    public static int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (isNpu()) {
            return Math.max(1, estimateTokens(text));
        }
        try {
            int n = LlamaHelper.countTokens(text);
            return n > 0 ? n : Math.max(1, text.length() / 4);
        } catch (Throwable t) {
            return Math.max(1, text.length() / 4);
        }
    }

    /**
     * 当前实际生效的上下文窗口（tokens）。
     *
     * <p>NPU 模式取 {@code NpuLlmChat.plannedNCtxValue()}（内存预算反推出的 nCtx，
     * 实测本机 8192），而不是 llama.cpp 的 0。</p>
     *
     * @param preset 预设窗口，作为双后端都取不到时的兜底
     * @param fallback 最终兜底值
     */
    public static int effectiveContextSize(int preset, int fallback) {
        if (isNpu()) {
            try {
                int n = NpuLlmChat.plannedNCtxValue();
                if (n > 0) {
                    return n;
                }
            } catch (Throwable ignored) {
            }
        } else {
            try {
                int real = LlamaHelper.getContextSize();
                if (real > 0) {
                    return real;
                }
            } catch (Throwable ignored) {
            }
        }
        return preset > 0 ? preset : fallback;
    }

    /**
     * 当前上下文使用率（0-100）。
     *
     * <p>NPU 模式用 NPU 上报的"本轮 prompt + 已生成"占 nCtx 的比例；
     * llama.cpp 模式保持原 {@code getContextUsagePercent()}。</p>
     */
    public static float contextUsagePercent() {
        if (isNpu()) {
            try {
                int used = NpuLlmChat.getLastCtxUsed();
                int window = NpuLlmChat.plannedNCtxValue();
                if (used > 0 && window > 0) {
                    return Math.min(100f, used * 100f / window);
                }
            } catch (Throwable ignored) {
            }
            return 0f;
        }
        try {
            return LlamaHelper.getContextUsagePercent();
        } catch (Throwable t) {
            return 0f;
        }
    }
}
