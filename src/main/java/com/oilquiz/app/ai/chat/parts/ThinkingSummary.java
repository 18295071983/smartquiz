package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.text.TextUtils;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.turn.AssistantTurn.Part;

import java.util.List;

/**
 * 思考手风琴的"头部状态/文案"计算（2026-11-18）。
 *
 * <p>原生渲染器（{@link ThinkingPartRenderer}）与 WebView PoC（{@link AgentPanelPayload}）必须
 * 用同一套口径，否则两套 UI 的文案会各说各话（"正在思考…" vs "运行中"）。这里只做纯计算，
 * 不碰 View。</p>
 */
final class ThinkingSummary {

    final boolean active;
    final boolean runningTool;
    final boolean streamingReasoning;
    /** 工具侧"正在联网搜索…"这类短语（工具在跑时优先）。 */
    final String toolPhrase;
    /** 思考侧的状态短语。 */
    final String reasoningPhrase;
    final String runningName;
    final int toolCount;
    final long reasoningMs;
    /** 有没有思考段（纯工具组时收起文案不能用"已深度思考"）。 */
    final boolean hasReasoning;
    /** 最后一个工具：收起态的副标题用它说明"刚才在干嘛"。 */
    final String lastToolName;
    final String lastToolArgs;
    final String lastToolStatus;

    private ThinkingSummary(boolean active, boolean runningTool, boolean streamingReasoning,
                            String toolPhrase, String reasoningPhrase, String runningName,
                            int toolCount, long reasoningMs, boolean hasReasoning,
                            String lastToolName, String lastToolArgs, String lastToolStatus) {
        this.active = active;
        this.runningTool = runningTool;
        this.streamingReasoning = streamingReasoning;
        this.toolPhrase = toolPhrase;
        this.reasoningPhrase = reasoningPhrase;
        this.runningName = runningName;
        this.toolCount = toolCount;
        this.reasoningMs = reasoningMs;
        this.hasReasoning = hasReasoning;
        this.lastToolName = lastToolName;
        this.lastToolArgs = lastToolArgs;
        this.lastToolStatus = lastToolStatus;
    }

    static ThinkingSummary of(List<Part> members) {
        boolean runningTool = false;
        boolean streamingReasoning = false;
        boolean hasReasoning = false;
        String toolPhrase = null;
        String reasoningPhrase = null;
        String runningName = null;
        String lastToolName = null;
        String lastToolArgs = null;
        String lastToolStatus = null;
        int toolCount = 0;
        long reasoningMs = 0L;
        if (members != null) {
            for (Part m : members) {
                if (m == null) {
                    continue;
                }
                if (Part.TYPE_REASONING.equals(m.type)) {
                    hasReasoning = true;
                    reasoningMs += Math.max(0L, m.durationMs);
                    if (m.isStreaming()) {
                        streamingReasoning = true;
                    }
                    if (!TextUtils.isEmpty(m.statusText)) {
                        reasoningPhrase = m.statusText;
                    }
                } else if (Part.TYPE_TOOL_GROUP.equals(m.type) && m.group != null) {
                    for (Part t : m.group) {
                        if (t == null) {
                            continue;
                        }
                        toolCount++;
                        if ("running".equals(ToolCallPartRenderer.ToolCardProps
                                .normalizeStatus(t.status))) {
                            runningTool = true;
                            runningName = displayName(t.toolName);
                        }
                        // 顺序覆盖：拿到组里最后一个工具
                        lastToolName = displayName(t.toolName);
                        lastToolArgs = ToolActivityRowView.argsPreview(t.args);
                        lastToolStatus = ToolCallPartRenderer.ToolCardProps.normalizeStatus(t.status);
                    }
                    if (!TextUtils.isEmpty(m.statusText)) {
                        toolPhrase = m.statusText;
                    }
                }
            }
        }
        boolean active = runningTool || streamingReasoning;
        return new ThinkingSummary(active, runningTool, streamingReasoning, toolPhrase,
                reasoningPhrase, runningName, toolCount, reasoningMs, hasReasoning,
                lastToolName, lastToolArgs, lastToolStatus);
    }

    /** 头部文案：运行中优先工具短语；结束时"已深度思考[ X 秒]"（工具数交给副标题）。 */
    String label(Context ctx) {
        if (active) {
            String phrase = runningTool ? toolPhrase : reasoningPhrase;
            if (!TextUtils.isEmpty(phrase)) {
                return phrase;
            }
            if (runningTool) {
                if (runningName != null && toolCount == 1) {
                    return "正在运行 " + runningName;
                }
                return toolCount + " 个工具执行中";
            }
            return ctx != null ? ctx.getString(R.string.chat_v2_thinking_now) : "正在思考…";
        }
        StringBuilder sb = new StringBuilder(hasReasoning ? "深度思考" : "已执行");
        if (reasoningMs > 0L) {
            sb.append(' ').append(Math.round(reasoningMs / 1000.0)).append(" 秒");
        }
        return sb.toString();
    }

    /**
     * 收起态副标题（2026-11-18，用户口径"收起时也能看出刚才在干嘛"）：
     * {@code 已完成 · 最后：终端命令 · Get-ChildItem -Recurse…}。
     *
     * <p>状态放最前面是有意的：折叠头只有一行，参数一长就会 ellipsize 到结尾，
     * 状态若放末尾（"…（已完成）"）在手机上永远看不到。参数预览复用工具行同一套
     * {@code argsPreview}（人话化、80 字内），这里再截到 30 字。没有工具时返回 null。</p>
     */
    String summary() {
        if (lastToolName == null || lastToolName.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(statusText(lastToolStatus));
        sb.append(" · 最后：").append(lastToolName);
        if (!TextUtils.isEmpty(lastToolArgs)) {
            String args = lastToolArgs.replaceAll("\\s+", " ").trim();
            if (args.length() > 30) {
                args = args.substring(0, 30) + "…";
            }
            sb.append(" · ").append(args);
        }
        return sb.toString();
    }

    private static String statusText(String normalized) {
        if ("running".equals(normalized)) {
            return "运行中";
        }
        if ("failed".equals(normalized)) {
            return "失败";
        }
        return "已完成";
    }

    private static String displayName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            return null;
        }
        try {
            String shown = com.oilquiz.app.ai.chat.ToolMetaRegistry.displayName(name);
            return TextUtils.isEmpty(shown) ? name : shown;
        } catch (Throwable t) {
            return name;
        }
    }
}
