package com.oilquiz.app.ai.inference;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.util.AILogger;

import org.json.JSONObject;

/**
 * 单轮账的落点（LOCAL-ROUND-METRICS，2026-11-13）。
 *
 * <p>统一从 native 的 KV 记账里取 token 数（{@code prompt_tokens} / {@code generated_tokens}
 * 是本轮**实际 decode** 的量），避免每个调用方各写一份解析。取不到就只记时间，绝不因为
 * 观测失败影响主流程。</p>
 *
 * <p>为什么必须 INFO：{@code engineLogAppend} 对 DEBUG 有硬过滤，降级等于"App 内看不到"。</p>
 */
public final class RoundLog {

    private RoundLog() {
    }

    /** 落一行 {@code ROUND-STATS}。 */
    public static void log(String tag, LocalRoundMetrics m) {
        if (m == null) return;
        int prompt = 0;
        int gen = 0;
        int matched = 0;
        String strategy = "";
        try {
            String j = LlamaHelper.getKvCacheStats();
            if (j != null && !j.isEmpty()) {
                JSONObject o = new JSONObject(j);
                prompt = o.optInt("prompt_tokens", 0);
                gen = o.optInt("generated_tokens", 0);
                strategy = o.optString("strategy", "");
                matched = o.optInt("matched_len", 0);
            }
        } catch (Throwable ignored) {
            // 观测失败不影响主流程
        }
        String kv = strategy.isEmpty() ? "" : ("kv=" + strategy + " matched=" + matched);
        AILogger.i(tag, m.describe(prompt, gen, kv));
    }
}
