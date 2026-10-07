package com.oilquiz.app.ai.engine.contract;

import com.oilquiz.app.ai.engine.NpuEngineState;
import com.oilquiz.app.ai.engine.NpuLlmChat;

/**
 * GenieX NPU 引擎的 {@link GenSignal} 适配器。
 *
 * <p>把 {@link NpuEngineState} 的状态翻译成引擎无关契约。**这里是 NPU 语义的唯一出口**：
 * 状态栏、上下文仪表等 UI 只读契约，不认识 {@code NpuEngineState} 的字段名，
 * 因此不会再出现"字段名不一致 → 静默取默认值 → 显示 llama.cpp 术语"的故障。</p>
 *
 * <p>两点刻意的语义选择：
 * <ul>
 *   <li><b>进度</b>：NPU 的 {@code prefillPercent} 是阶段量（0 或 100），没有逐 token 进度。
 *       只有真正落在 (0,100) 之间时才报给 UI，否则给 {@link GenSignal#UNKNOWN}，
 *       让 UI 显示阶段标签而不是一个假的 0%。</li>
 *   <li><b>文案</b>：用 {@code NpuEngineState.getInferencePhaseLabel()}（如「处理提示」），
 *       而不是让 UI 按枚举名硬编码 llama.cpp 的「预处理」。</li>
 * </ul>
 */
public final class NpuSignalAdapter {

    private static final String ENGINE = "geniex-npu";

    private NpuSignalAdapter() {
    }

    /** 供 UI 入队前判定用（避免 UI 直连 NpuLlmChat） */
    public static boolean isActive() {
        try {
            return NpuLlmChat.isEngineEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    public static GenSignal current() {
        try {
            NpuEngineState st = NpuEngineState.get();
            NpuEngineState.InferencePhase p = st.getInferencePhase();
            if (p == null) {
                return GenSignal.idle(ENGINE);
            }
            GenSignal.Phase phase;
            switch (p) {
                case PREPROCESS: phase = GenSignal.Phase.PREPROCESS; break;
                case THINKING:   phase = GenSignal.Phase.THINKING;   break;
                case GENERATING: phase = GenSignal.Phase.GENERATING; break;
                default:         phase = GenSignal.Phase.IDLE;       break;
            }
            int pct = st.getPrefillPercent();
            // 阶段量（0/100）不当作进度上报，避免 UI 显示假的 0%
            boolean pctMeaningful = pct > 0 && pct < 100;
            return GenSignal.builder(ENGINE)
                    .phase(phase)
                    .running(phase != GenSignal.Phase.IDLE)
                    .progressOrUnknown(pctMeaningful ? pct : -1)
                    .tokensOrUnknown(st.getGeneratedTokens())
                    .phaseLabel(st.getInferencePhaseLabel())
                    .decodeSpeedOrUnknown(st.getLastTps())
                    .phaseSpeedOrUnknown(st.getLastTps())
                    .build();
        } catch (Throwable t) {
            return GenSignal.idle(ENGINE);
        }
    }

    /**
     * KV 缓存统计：**NPU 侧不具备该能力**（GenieX SDK 未暴露增量缓存计数）。
     * 返回 {@code null}，UI 据此隐藏该控件 —— 而不是伪造一份数据。
     */
    public static GenSignal.KvStats kvStats() {
        return null;
    }

    /**
     * 上下文占用：NPU 侧取"内存预算规划出的 nCtx"与"本轮 prompt + 已生成"。
     *
     * <p>注意与 llama.cpp 的语义差异：NPU 每轮**全量**喂 prompt，没有增量 KV 复用，
     * 所以 {@code getLastCtxUsed()} 反映的是本轮真实占用，而不是累积会话长度。</p>
     */
    public static ContextUsage contextUsage() {
        try {
            int win = NpuLlmChat.plannedNCtxValue();
            int used = NpuLlmChat.getLastCtxUsed();
            return new ContextUsage(
                    win > 0 ? win : ContextUsage.UNKNOWN,
                    used > 0 ? used : 0,
                    ENGINE);
        } catch (Throwable t) {
            return new ContextUsage(ContextUsage.UNKNOWN, ContextUsage.UNKNOWN, ENGINE);
        }
    }
}
