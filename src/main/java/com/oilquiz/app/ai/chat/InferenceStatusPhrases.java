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

    /** 结束态类型：正常完成（完成态短摘要用） */
    public static final int FINISH_COMPLETED = 1;
    /** 结束态类型：用户主动停止 */
    public static final int FINISH_STOPPED = 2;

    private InferenceStatusPhrases() {
    }

    /**
     * 完成态文案：过程 trace 收起后给一句短摘要，展示约 2 秒后收起（业界口径：
     * 结束后不常驻状态行，但也不"瞬间无痕"）。例：{@code 已完成 · 12.3 秒}、
     * {@code 已停止 · 5 秒}；未知类型返回 null。
     */
    public static String completionPhrase(int finishKind, long elapsedMs) {
        String duration = formatElapsed(elapsedMs);
        if (finishKind == FINISH_COMPLETED) return "已完成 · " + duration;
        if (finishKind == FINISH_STOPPED) return "已停止 · " + duration;
        return null;
    }

    /** 耗时格式化：{@code 0.5 秒 / 12.3 秒 / 1 分 5 秒} */
    private static String formatElapsed(long elapsedMs) {
        long tenths = Math.max(0L, elapsedMs) / 100L;
        if (tenths < 10) {
            return "0." + tenths + " 秒";
        }
        long seconds = tenths / 10;
        long frac = tenths % 10;
        if (seconds < 60) {
            return frac == 0 ? seconds + " 秒" : seconds + "." + frac + " 秒";
        }
        long minutes = seconds / 60;
        long rest = seconds % 60;
        return frac == 0
                ? minutes + " 分 " + rest + " 秒"
                : minutes + " 分 " + rest + "." + frac + " 秒";
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
                // 多步深度思考不报"第 N 轮"（业界 Progress Steps 口径：轮次属于可折叠 trace，
                // 不塞进全局状态文案——见 docs/ui/inference-status-phrases.md §9）
                return thinking(seconds);
            case GENERATING:
            case DECODING:
                return generating(seconds);
            default:
                return null;
        }
    }

    /**
     * 卡顿（长时间没有新 token）专属文案：正常推进句在"卡住"时会显得在撒谎
     * （比如生成阶段一直显示"正在收尾…"），所以一旦超过阈值就换成明确的"较慢/可重试"，
     * 阈值见 {@code AIChatActivity#noteStreamActivity} 与状态心跳。
     *
     * @param phase     当前推理阶段
     * @param noDataMs  距最近一次 token/阶段更新的毫秒数
     * @return 卡顿文案；未达阈值返回 null（继续用正常推进句）
     */
    public static String stalledPhrase(ChatMessage.InferencePhase phase, long noDataMs) {
        if (phase == null) return null;
        long seconds = Math.max(0L, noDataMs) / 1000L;
        switch (phase) {
            case WAITING:
            case INITIALIZING:
            case LOADING_MODEL:
            case ENCODING:
            case PREFILL:
                return seconds >= 15 ? "响应较慢，仍在等待…" : null;
            case THINKING:
                return seconds >= 15 ? "思考较久，仍在继续…" : null;
            case GENERATING:
            case DECODING:
                if (seconds >= 45) return "生成较久，可停止后重试";
                return seconds >= 15 ? "生成较慢，仍在继续…" : null;
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

    /**
     * 工具执行阶段的推进文案（"工具侧思考"）：工具卡/工具行在 running 时用它，
     * 与推理/生成状态行同一套设计（阶段 + 已耗时；不编假百分比）。
     *
     * @param toolElapsedMs 从"本轮第一个工具开始执行"到现在的毫秒数；≤0 视为刚开始
     */
    public static String toolPhrase(long toolElapsedMs) {
        return toolPhrase(com.oilquiz.app.ai.chat.status.AgentActivityKind.GENERIC, toolElapsedMs);
    }

    /**
     * 工具执行阶段文案（带引擎侧分类）：{"正在联网搜索…","正在生成文件…","正在读取文件…"…}。
     * 分类由引擎侧的 {@code AgentActivityBus} 发布，UI 只按分类 + 已耗时选句。
     */
    public static String toolPhrase(com.oilquiz.app.ai.chat.status.AgentActivityKind kind,
                                    long toolElapsedMs) {
        com.oilquiz.app.ai.chat.status.AgentActivityKind k =
                kind != null ? kind : com.oilquiz.app.ai.chat.status.AgentActivityKind.GENERIC;
        return k.statusText(toolElapsedMs);
    }

    /**
     * 工具执行文案 + "正在干什么"的追加（文件名/查询词等）：如
     * {@code 正在生成文件 · 季度报告.docx}。detail 为空时等价于不带追加的版本。
     */
    public static String toolPhrase(com.oilquiz.app.ai.chat.status.AgentActivityKind kind,
                                    long toolElapsedMs, CharSequence detail) {
        String base = toolPhrase(kind, toolElapsedMs);
        if (detail == null) return base;
        String d = String.valueOf(detail).replace('\n', ' ').replace('\r', ' ').trim();
        if (d.isEmpty()) return base;
        // 追加的目标最长 24 字：模板是单行状态，太长会把"速度/余额"挤掉
        if (d.length() > 24) d = d.substring(0, 24) + "…";
        // 有具体目标时去掉句尾省略号："正在生成文件 · 季度报告.docx"（而不是"… · 文件"）
        if (base.endsWith("…")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + " · " + d;
    }
}
