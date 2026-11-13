package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.chat.status.DockSlotBus;

/**
 * 远程连接的数据插槽（2026-11-13）：「既然数据都有了，就多摆几个插槽」。
 *
 * <p>状态条只放得下两行，而链路/上下文/token/模型/轮次这些指标各有各的更新节奏。
 * 这里把它们拆成独立插槽，交给 {@link DockSlotBus} 按优先级渲染——插槽同 id 覆盖、
 * 断连可单条摘除，不影响消息时间线。</p>
 *
 * <p>去抖：每秒刷新一次，但只有文案变化才真的 {@code update()}，避免甲板每秒重建导致跳动。</p>
 */
public final class RemoteStatusSlots {

    public static final String ID_PHASE = "remote/phase";
    public static final String ID_SESSION = "remote/session";
    public static final String ID_LINK = "remote/link";
    public static final String ID_USAGE = "remote/usage";
    public static final String ID_MODEL = "remote/model";

    private static volatile String lastSignature = "";

    private RemoteStatusSlots() {
    }

    /** 按当前状态发布/更新插槽；未连接或状态条关闭时自动清空。 */
    public static void publish(Context c) {
        if (c == null) return;
        boolean configured = RemoteDshTool.isConfigured(c);
        boolean connected = RemoteDshTool.isConnected(c);
        boolean barOn = RemoteDshTool.isBarEnabled(c);
        if (!configured || !connected || !barOn) {
            clear();
            return;
        }

        RemoteLiveStatus.Snapshot live = RemoteLiveStatus.snapshot();
        RemoteStatusSnapshot st = RemoteStatusMonitor.get();
        RemoteConnection conn = RemoteConnection.get(c);

        StringBuilder sig = new StringBuilder();
        sig.append(live.phase).append('|').append(live.tool).append('|')
                .append(live.phaseElapsedMs / 1000L).append('|')
                .append(live.turn).append('|').append(live.step).append('|')
                .append(live.windowEvents).append('|')
                .append(conn.isOnline()).append('|').append(conn.behindCount()).append('|')
                .append(conn.reconnectCount());
        if (st != null) {
            sig.append('|').append(st.rttMs).append('|').append(st.pressureTokens)
                    .append('|').append(st.totalTokens).append('|').append(st.cacheHitPct)
                    .append('|').append(st.model).append('|').append(st.effort)
                    .append('|').append(st.approvalsPending).append('|').append(st.cliQueued)
                    .append('|').append(st.activeJobs);
        }
        String s = sig.toString();
        if (s.equals(lastSignature)) return;
        lastSignature = s;

        // 1) 阶段（只在真的在干活/等审批时出现；空闲不占行）
        if (live.phase == RemoteLiveStatus.Phase.WAITING_APPROVAL) {
            DockSlotBus.update(new DockSlotBus.Slot(ID_PHASE, 8, "等待审批",
                    duration(live.phaseElapsedMs) + " · 点状态条处理", "connect", true));
        } else if (live.phase != RemoteLiveStatus.Phase.IDLE) {
            String title = phaseName(live.phase);
            String detail = (live.tool == null ? "" : live.tool);
            String d = duration(live.phaseElapsedMs);
            if (!d.isEmpty()) detail = detail.isEmpty() ? d : detail + " " + d;
            DockSlotBus.update(new DockSlotBus.Slot(ID_PHASE, 8, title, detail, "connect", false));
        } else {
            DockSlotBus.remove(ID_PHASE);
        }

        // 2) 会话进度（轮/步/事件速率）
        StringBuilder session = new StringBuilder();
        if (live.turn > 0) {
            session.append(live.turn).append(" 轮");
            if (live.step > 0) session.append(' ').append(live.step).append(" 步");
        }
        if (live.windowEvents > 0) {
            if (session.length() > 0) session.append(" · ");
            session.append(live.windowEvents).append(" 条/分");
        }
        if (session.length() > 0) {
            DockSlotBus.update(new DockSlotBus.Slot(ID_SESSION, 56, "会话", session.toString(), "connect", false));
        } else {
            DockSlotBus.remove(ID_SESSION);
        }

        // 3) 链路（在线/重连/RTT/落后）
        StringBuilder link = new StringBuilder();
        link.append(conn.isOnline() ? "在线" : "重连中");
        if (st != null && st.rttMs > 0) link.append(" · RTT ").append(st.rttMs).append("ms");
        if (conn.behindCount() > 0) link.append(" · 落后 ").append(conn.behindCount());
        if (conn.reconnectCount() > 0) link.append(" · 重连 ").append(conn.reconnectCount());
        boolean linkWarn = !conn.isOnline() || conn.behindCount() > 5;
        DockSlotBus.update(new DockSlotBus.Slot(ID_LINK, 58, "链路", link.toString(), "connect", linkWarn));

        // 4) 用量（上下文 / token / 缓存），没拿到 usage 就不占行
        if (st != null && st.usageOk) {
            StringBuilder usage = new StringBuilder();
            String ctx = st.contextText();
            String tok = st.tokensText();
            String cache = st.cacheText();
            if (!ctx.isEmpty()) usage.append(ctx.replace("上下文 ", ""));
            if (!tok.isEmpty()) {
                if (usage.length() > 0) usage.append(" · ");
                usage.append(tok);
            }
            if (!cache.isEmpty()) {
                if (usage.length() > 0) usage.append(" · ");
                usage.append(cache);
            }
            if (usage.length() > 0) {
                DockSlotBus.update(new DockSlotBus.Slot(ID_USAGE, 60, "用量", usage.toString(), "connect", false));
            } else {
                DockSlotBus.remove(ID_USAGE);
            }
        } else {
            DockSlotBus.remove(ID_USAGE);
        }

        // 5) 模型（模型名 + 推理档 / 上限）
        if (st != null && st.usageOk && !st.model.isEmpty()) {
            StringBuilder model = new StringBuilder(st.model);
            if (!st.effort.isEmpty()) model.append(" · ").append(st.effort);
            DockSlotBus.update(new DockSlotBus.Slot(ID_MODEL, 62, "模型", model.toString(), "connect", false));
        } else {
            DockSlotBus.remove(ID_MODEL);
        }
    }

    /** 断连/关闭状态条时调用：五条全部摘掉。 */
    public static void clear() {
        lastSignature = "";
        DockSlotBus.remove(ID_PHASE);
        DockSlotBus.remove(ID_SESSION);
        DockSlotBus.remove(ID_LINK);
        DockSlotBus.remove(ID_USAGE);
        DockSlotBus.remove(ID_MODEL);
    }

    private static String phaseName(RemoteLiveStatus.Phase p) {
        switch (p) {
            case THINKING:
                return "思考中";
            case GENERATING:
                return "生成中";
            case TOOL:
                return "执行工具";
            case COMPACTING:
                return "压缩上下文";
            case RETRYING:
                return "重试中";
            case WAITING_APPROVAL:
                return "等待审批";
            default:
                return "空闲";
        }
    }

    private static String duration(long ms) {
        if (ms <= 0L) return "";
        long sec = ms / 1000L;
        if (sec < 60L) return "0:" + (sec < 10L ? "0" : "") + sec;
        return (sec / 60L) + ":" + String.format(java.util.Locale.US, "%02d", sec % 60L);
    }
}
