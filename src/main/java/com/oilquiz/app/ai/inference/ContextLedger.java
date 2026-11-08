package com.oilquiz.app.ai.inference;

import com.oilquiz.app.ai.jni.LlamaHelper;

/**
 * CONTEXT-LEDGER(2026-11-08)：推理上下文的单一读数源。
 *
 * <p>此前上下文有四个口径：ResourceConfig/ContextWindowManager 的 n_ctx、
 * AgentLoopEngine 的 prompt budget、AIService/InferenceQueue 的 remaining、
 * AgentKvCache 的 cachedNPast，以及 native 真实 seq_pos_max。任何一个口径不同步，
 * 就会出现“守卫以为还剩 16K、实际只剩 12K”或 UI 显示 52/实际 4304。</p>
 *
 * <p>这里不决定策略，只负责把**同一时刻的真实读数**聚合成一个不可变快照；
 * 策略层（AgentLoopEngine/AIService/ModelExecutionBridge）只读它，不再各自调
 * 不同的 getter。</p>
 */
public final class ContextLedger {

    private ContextLedger() {
    }

    public static Snapshot snapshot() {
        int nCtx;
        int realUsed;
        int bookkeepingUsed;
        boolean nativeValid;
        try {
            nCtx = Math.max(0, LlamaHelper.getContextSize());
            realUsed = Math.max(0, LlamaHelper.getContextUsedTokens());
            bookkeepingUsed = Math.max(0, LlamaHelper.getContextUsedTokensBookkeeping());
            nativeValid = LlamaHelper.isNativeStateValid();
        } catch (Throwable t) {
            return new Snapshot(0, 0, 0, 0, false, false, String.valueOf(t));
        }
        boolean chatReady = nativeValid && nCtx > 0;
        int remaining = Math.max(0, nCtx - realUsed);
        return new Snapshot(nCtx, realUsed, remaining, bookkeepingUsed,
                nativeValid, chatReady, "");
    }

    /** 本地 chat context 真正可用的判据（不是只看 isNativeStateValid）。 */
    public static boolean isChatReady() {
        return snapshot().chatReady;
    }

    public static final class Snapshot {
        public final int nCtx;
        /** 真实 KV：llama_memory_seq_pos_max(seq=0)+1。 */
        public final int realUsed;
        public final int remaining;
        /** NativeChatContext.total_tokens_in_kv：只做增量缓存统计，不作为守卫/UI 口径。 */
        public final int bookkeepingUsed;
        public final boolean nativeValid;
        public final boolean chatReady;
        public final String error;

        public Snapshot(int nCtx, int realUsed, int remaining, int bookkeepingUsed,
                        boolean nativeValid, boolean chatReady, String error) {
            this.nCtx = nCtx;
            this.realUsed = realUsed;
            this.remaining = remaining;
            this.bookkeepingUsed = bookkeepingUsed;
            this.nativeValid = nativeValid;
            this.chatReady = chatReady;
            this.error = error;
        }

        public int promptBudget(int reserveTokens) {
            return Math.max(0, remaining - Math.max(0, reserveTokens));
        }

        public String describe() {
            if (error != null && !error.isEmpty()) {
                return "context: error=" + error;
            }
            return "context: nCtx=" + nCtx
                    + " real=" + realUsed
                    + " remain=" + remaining
                    + " bookkeeping=" + bookkeepingUsed
                    + " nativeValid=" + nativeValid
                    + " chatReady=" + chatReady;
        }
    }
}
