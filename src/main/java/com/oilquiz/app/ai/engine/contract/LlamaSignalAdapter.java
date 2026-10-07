package com.oilquiz.app.ai.engine.contract;

import com.oilquiz.app.ai.chat.util.ChatTextUtils;
import com.oilquiz.app.ai.jni.LlamaHelper;

import org.json.JSONObject;

/**
 * llama.cpp 引擎的 {@link GenSignal} 适配器。
 *
 * <p><b>这里是 llama.cpp 字段名的唯一解析点</b>。原先 UI（{@code GenerationStatusBar}）直接解析
 * native 的三段 JSON，字段名与语义散落在渲染代码里；改为适配器后：
 * <ul>
 *   <li>UI 只读 {@link GenSignal}，与引擎字段解耦；</li>
 *   <li>native 侧 JSON 结构变化只影响本文件；</li>
 *   <li>新增引擎时照抄本类写一个适配器即可，不会污染 UI。</li>
 * </ul>
 *
 * <p>契约字段来源（{@code native-lib.cpp}）：
 * <ul>
 *   <li>{@code nativeGetGenPhase} → {@code {"phase":"THINKING","stop_cause":"EOS","running":true}}</li>
 *   <li>{@code nativeGetPrefillProgress} → {@code {"done":0,"total":0,"pct":0,"prompt":0}}</li>
 *   <li>{@code nativeGetKvCacheStats} → {@code {"ctx_usage_pct":0.0,"plans":3,"hit_rate_pct":0.0,...}}</li>
 * </ul>
 */
public final class LlamaSignalAdapter {

    private static final String ENGINE = "llama.cpp";

    private LlamaSignalAdapter() {
    }

    public static GenSignal current() {
        try {
            String gp = LlamaHelper.getGenPhase();
            if (gp == null || gp.isEmpty()) {
                return GenSignal.idle(ENGINE);
            }
            JSONObject o = new JSONObject(gp);
            String phaseName = o.optString("phase", "IDLE");
            boolean running = o.optBoolean("running", false);

            GenSignal.Phase phase = toPhase(phaseName);

            // prefill 进度只在 PREPROCESS 阶段有意义（native 只在那一阶段填真值）
            float pct = GenSignal.UNKNOWN;
            if (phase == GenSignal.Phase.PREPROCESS) {
                try {
                    String pp = LlamaHelper.getPrefillProgress();
                    if (pp != null && !pp.isEmpty()) {
                        JSONObject po = new JSONObject(pp);
                        int total = po.optInt("total", 0);
                        if (total > 0) {
                            // 契约要求 0-100 的百分比；native 的 pct 已是百分比
                            pct = po.optInt("pct", 0);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            return GenSignal.builder(ENGINE)
                    .phase(phase)
                    .running(running)
                    .progressOrUnknown(pct)
                    .phaseLabel(ChatTextUtils.phaseToCn(phaseName))
                    .decodeSpeedOrUnknown(LlamaHelper.getDecodeSpeed())
                    .phaseSpeedOrUnknown(LlamaHelper.getPhaseSpeed())
                    .build();
        } catch (Throwable t) {
            return GenSignal.idle(ENGINE);
        }
    }

    /**
     * KV 缓存统计。llama.cpp **具备**该能力（增量 KV 缓存计数）。
     * 解析失败或 native 未就绪时返回 {@code null}（UI 隐藏该控件）。
     */
    public static GenSignal.KvStats kvStats() {
        try {
            String j = LlamaHelper.getKvCacheStats();
            if (j == null || j.isEmpty()) {
                return null;
            }
            JSONObject o = new JSONObject(j);
            double hit = o.optDouble("hit_rate_pct", -1);
            double usage = o.optDouble("ctx_usage_pct", -1);
            int plans = o.optInt("plans", 0);
            GenSignal.KvStats s = new GenSignal.KvStats(hit, usage, plans);
            return s.isDisplayable() ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** native 的阶段名 → 契约枚举。未知名字归为 IDLE（不猜、不静默套用别的引擎语义） */
    private static GenSignal.Phase toPhase(String name) {
        if (name == null) return GenSignal.Phase.IDLE;
        switch (name) {
            case "PREPROCESS": return GenSignal.Phase.PREPROCESS;
            case "THINKING":   return GenSignal.Phase.THINKING;
            case "GENERATING": return GenSignal.Phase.GENERATING;
            default:           return GenSignal.Phase.IDLE;
        }
    }

    /**
     * 上下文占用：llama.cpp 取 native 的 KV 上下文实际占用与容量。
     * native 未就绪（或 NPU 模式下）两者为 0 → 报 {@link ContextUsage#UNKNOWN}，
     * 由 UI 决定降级（如回退字符估算），而不是显示假数据。
     *
     * <p><b>AGENT-KV(2026-10-07)</b>：本地 Agent 模式优先取 AgentKvCache 记账
     * （{@code nativeGetKvCacheStats} 的 {@code cached_npast} / {@code ctx_size}）——
     * 那是 KV 里真实 decode 的 token 数（prompt + 生成输出）。旧实现读
     * {@code getContextUsedTokens()}（{@code NativeChatContext}），对 Agent 的
     * {@code InferenceContext} 恒为 0 → 对话页上下文仪表在 Agent 模式显示 0 / "--"。
     * Agent 记账未建立（ctx_size=0，普通对话路径）时回退 chat handle。
     */
    public static ContextUsage contextUsage() {
        try {
            String stats = LlamaHelper.getKvCacheStats();
            if (stats != null && !stats.isEmpty()) {
                JSONObject o = new JSONObject(stats);
                int ctxSize = o.optInt("ctx_size", 0);
                int cachedNpast = o.optInt("cached_npast", 0);
                if (ctxSize > 0) {
                    return new ContextUsage(ctxSize, Math.max(0, cachedNpast), ENGINE);
                }
            }
            long used = LlamaHelper.getContextUsedTokens();
            long size = LlamaHelper.getContextSize();
            return new ContextUsage(
                    size > 0 ? size : ContextUsage.UNKNOWN,
                    used > 0 ? used : 0,
                    ENGINE);
        } catch (Throwable t) {
            return new ContextUsage(ContextUsage.UNKNOWN, ContextUsage.UNKNOWN, ENGINE);
        }
    }
}
