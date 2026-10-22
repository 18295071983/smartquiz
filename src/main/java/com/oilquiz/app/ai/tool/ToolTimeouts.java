package com.oilquiz.app.ai.tool;

import java.util.Map;

/**
 * 工具超时的裁定口径（<b>单一写者</b>）：调用方基线 与 工具自声明 取较大者，受全局硬上限约束。
 *
 * <p>背景（2026-09-27 实测，见 remote_dsh 的踩坑记录）：调用方对所有工具套一个固定默认超时，
 * 而有些工具的耗时天然不可控 —— 在电脑上真跑任务、Python 里画图/装依赖。工具传了更大的
 * {@code timeout} 也没用，框架先到点把它掐断，模型只拿到一句通用超时文案，
 * 工具自己产出的结构化错误（stdout / TimeoutError / environment_error）全丢，只能盲目重试。
 * 因此由工具经 {@link AITool#executionTimeoutMs(Map)} 申报所需时长，调用方按本方法放宽等待。
 *
 * <p>为什么要抽出来：这套判定原先只在 {@code OnlineToolManager} 里内联写了一遍，而
 * {@code AgentService}（对话页 ModelExecutionBridge 走的执行路径）有自己的一份 30 秒硬编码 ——
 * 同一个工具在两条路径上有两种死法，正是本项目反复踩到的"并行写者漂移"。
 */
public final class ToolTimeouts {

    /** 全局硬上限（11 分钟）：防止个别工具把调用方无限拖住 */
    public static final int MAX_TOOL_TIMEOUT_MS = 660_000;

    private ToolTimeouts() {
    }

    /**
     * @param tool   要执行的工具实例（可为 null，例如未注册的工具名）
     * @param args   本次调用的参数（申报值可能依赖它，如 timeout / action）
     * @param baseMs 调用方自己的基线超时（默认档或交互档）
     * @return 实际应当等待的毫秒数：{@code max(基线, 申报值)}，申报值受硬上限约束
     */
    public static int effectiveMs(AITool tool, Map<String, Object> args, int baseMs) {
        if (tool == null) {
            return baseMs;
        }
        int declared;
        try {
            long d = tool.executionTimeoutMs(args);
            if (d <= 0) {
                return baseMs;
            }
            declared = (int) Math.min(MAX_TOOL_TIMEOUT_MS, d);
        } catch (Exception e) {
            // 申报只是"允许多等一会儿"，不是执行的前置条件：读失败必须退回基线，
            // 绝不能因为申报本身把一次正常调用判成失败。
            return baseMs;
        }
        return Math.max(baseMs, declared);
    }
}
