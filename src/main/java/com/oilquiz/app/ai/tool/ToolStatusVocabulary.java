package com.oilquiz.app.ai.tool;

/**
 * 工具结果 {@code status} 字段的词表与"发给模型的标签"（单一写者）。
 *
 * <p>为什么要收：工具在结果 map 里自己写 {@code status}，值域是散的
 * （success / failed / error / timeout / truncated / fallback / deleted / 个别还写大写），
 * 而读它的地方各写了一套判断：{@code AgentService.formatToolOutput} 用一条
 * if-else 链且<b>只认 "failed"/"error" 为失败</b>，其它值（包括 "timeout"）一律走
 * "成功"分支；{@code ToolRowStyle}、{@code ToolCallCardView}、{@code ActivityTimeline}、
 * {@code ToolCallPartRenderer.normalizeStatus} 又各有自己的词表。
 *
 * <p>本类只统一<b>发给模型的那一份标签</b>。UI 侧那三份词表属于渲染层，改动面和风险都不同，
 * 留待单独一轮收敛（它们目前的行为不受本类影响）。
 *
 * <p>一条硬规则：<b>不认识的 status 值不许当成成功</b>。旧实现默认 {@code isSuccess = true}，
 * 于是任何新状态词（或大写 FAILED）都会被读成"成功"，这正是并行词表最坏的失效方式 ——
 * 它不会崩，只会安静地说谎。
 */
public final class ToolStatusVocabulary {

    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    public static final String ERROR = "error";
    public static final String TIMEOUT = "timeout";
    public static final String TRUNCATED = "truncated";
    public static final String FALLBACK = "fallback";

    /**
     * 完成同义词。这份名单不是想象出来的：抓了全仓库所有 {@code put("status", "...")}
     * 的字面值（success 148 处，另有 completed/added/deleted/saved/updated/set/cleared/reverted
     * 各 1~7 处），把它们归成"做成了"，否则按下面"未知不当成功"的硬规则，
     * 这些正常结果都会被标成"状态值未识别"，反而是噪声。
     */
    private static final java.util.Set<String> SUCCESS_WORDS = new java.util.HashSet<>(
            java.util.Arrays.asList("success", "completed", "done", "ok", "added", "deleted",
                    "cleared", "saved", "set", "updated", "reverted",
                    "success_with_detail", "success_with_details"));

    /** 没做成的同义词 */
    private static final java.util.Set<String> FAILURE_WORDS = new java.util.HashSet<>(
            java.util.Arrays.asList("failed", "error", "connect_failed", "not_found",
                    "restricted", "unsupported", "cancelled", "timeout"));

    /** 需要用户先做点什么（授权、给 key），既不是成功也不该被当成失败 */
    private static final java.util.Set<String> ACTION_NEEDED_WORDS = new java.util.HashSet<>(
            java.util.Arrays.asList("need_permission", "need_key"));

    /** 还在跑 */
    private static final java.util.Set<String> IN_PROGRESS_WORDS = new java.util.HashSet<>(
            java.util.Arrays.asList("in_progress", "running", "speaking"));

    private ToolStatusVocabulary() {
    }

    /** 是否属于"没做成"的语义（模型需要据此改下一步，而不是照结果往下推） */
    public static boolean isFailure(Object statusValue) {
        String s = text(statusValue);
        return s != null && FAILURE_WORDS.contains(s.toLowerCase());
    }

    /**
     * 给模型的抬头。返回 null 表示这个 status 值本类不认识 —— 调用方必须如实标注"未知状态"，
     * 不许回落成"成功"。
     */
    public static String modelLabel(Object statusValue) {
        String s = text(statusValue);
        if (s == null || s.isEmpty()) {
            return null;
        }
        String key = s.toLowerCase();
        if (SUCCESS_WORDS.contains(key)) {
            return "[工具执行成功]";
        }
        if (FAILED.equalsIgnoreCase(s) || ERROR.equalsIgnoreCase(s)) {
            return "[工具执行失败]";
        }
        if (TIMEOUT.equalsIgnoreCase(s)) {
            return "[工具执行超时：命令可能已经生效，只是没在时限内回传]";
        }
        if ("connect_failed".equalsIgnoreCase(key)) {
            return "[连接失败]";
        }
        if ("not_found".equalsIgnoreCase(key)) {
            return "[没找到目标]";
        }
        if ("restricted".equalsIgnoreCase(key) || "unsupported".equalsIgnoreCase(key)) {
            return "[该操作不被支持或被限制]";
        }
        if ("cancelled".equalsIgnoreCase(key)) {
            return "[已取消]";
        }
        if (TRUNCATED.equalsIgnoreCase(s)) {
            return "[工具输出超限被截断]";
        }
        if (FALLBACK.equalsIgnoreCase(s)) {
            return "[回退结果：主路径未成功，这份内容由备用实现产出]";
        }
        if (ACTION_NEEDED_WORDS.contains(key)) {
            return "[需要用户先完成动作: " + s + "]";
        }
        if (IN_PROGRESS_WORDS.contains(key)) {
            return "[仍在进行中: " + s + "]";
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? SUCCESS : FAILED;
        }
        return String.valueOf(value).trim();
    }
}
