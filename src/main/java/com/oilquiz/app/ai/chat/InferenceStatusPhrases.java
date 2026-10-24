package com.oilquiz.app.ai.chat;

/**
 * 推理/生成状态的"多套备用文案"（2026-10-25）。
 *
 * <p><b>为什么需要</b>：推理/生成耗时不确定，固定一句"思考中…"会让用户觉得卡死。
 * 业界做法（Perplexity 的三阶段 "Searching the web → Reading sources → Writing answer"、
 * ChatGPT/Claude 的 thinking → thought for Xs、NN/g 的响应时间建议）都是：
 * <b>把过程拆成可感知的推进阶段，随真实进度切换文案</b>；超过 10 秒要给"仍在进行"的持续反馈，
 * 并保留中断入口（我们有停止按钮）。</p>
 *
 * <p>本类只做纯文案选择：输入"当前阶段 + 本阶段已耗时"，输出一句短状态。
 * 底部生成状态行和气泡内「深度思考」折叠头共用它，保证同屏同词。</p>
 *
 * <p>文案长度控制在 ≤10 个汉字：状态行一行放得下"文案 + 速度 + 余额"，
 * 折叠头也不会把箭头挤掉。</p>
 */
public final class InferenceStatusPhrases {

    private InferenceStatusPhrases() {
    }

    /** 阶段 + 本阶段已耗时(ms) → 当前状态文案；完成/失败/空闲返回 null。 */
    public static String phrase(ChatMessage.InferencePhase phase, long phaseElapsedMs) {
        if (phase == null) return null;
        long seconds = Math.max(0L, phaseElapsedMs) / 1000L;
        switch (phase) {
            case WAITING:
            case INITIALIZING:
            case LOADING_MODEL:
            case ENCODING:
            case PREFILL:
                return waiting(seconds);
            case THINKING:
                return thinking(seconds);
            case GENERATING:
            case DECODING:
                return generating(seconds);
            default:
                return null;
        }
    }

    /** 等首个 token / 准备上下文：让用户知道"已发出、在连、连接慢不是死了"。 */
    private static String waiting(long s) {
        if (s < 3) return "等待模型响应…";
        if (s < 8) return "正在连接模型…";
        if (s < 20) return "响应较慢，仍在等待…";
        return "仍在等待响应…";
    }

    /** 思考阶段：按思考时长给"越来越深"的推进感（不是百分比，避免假进度）。 */
    private static String thinking(long s) {
        if (s < 4) return "正在理解问题…";
        if (s < 10) return "正在梳理思路…";
        if (s < 20) return "正在推敲关键点…";
        if (s < 35) return "正在验证推理…";
        if (s < 60) return "正在收敛结论…";
        return "思考较久，仍在继续…";
    }

    /** 生成阶段：正文已经在上屏，文案只需要"还在写、快写完"。 */
    private static String generating(long s) {
        if (s < 3) return "正在组织回答…";
        if (s < 10) return "正在撰写回答…";
        if (s < 25) return "正在补充细节…";
        return "正在收尾…";
    }
}
