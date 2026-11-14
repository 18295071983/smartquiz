package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.chat.status.DockSlotBus;

/**
 * 远程连接的**唯一插槽**（2026-11-14 第三版）。
 *
 * <p>用户反馈："槽占大空间""四个长条""光显示数据"。所以撤掉原来的 5 条数据插槽
 * （阶段/会话/链路/用量/模型），只保留**需要人处理**的一条：</p>
 * <ul>
 *   <li>电脑断线 → 「电脑断线 · 点这里重连」（黄）</li>
 *   <li>有审批 → 「电脑待审批 N · 点这里处理」（黄）</li>
 *   <li>通道落后很多 → 「通道落后 N 条」</li>
 *   <li>其它情况 → 不占行（阶段和短指标由顶部状态条一行显示）</li>
 * </ul>
 *
 * <p>完整指标（RTT/token/上下文/缓存/模型）仍在状态条长按的详情弹窗里，不占甲板空间。</p>
 */
public final class RemoteStatusSlots {

    public static final String ID = "remote/attention";

    private static volatile String lastSignature = "";

    private RemoteStatusSlots() {
    }

    /** 只在"需要人处理"时出一条；其余情况清掉。 */
    public static void publish(Context c) {
        if (c == null) return;
        boolean configured = RemoteDshTool.isConfigured(c);
        boolean connected = RemoteDshTool.isConnected(c);
        boolean barOn = RemoteDshTool.isBarEnabled(c);
        if (!configured || !barOn) {
            clear();
            return;
        }

        RemoteLiveStatus.Snapshot live = RemoteLiveStatus.snapshot();
        RemoteStatusSnapshot st = RemoteStatusMonitor.get();
        RemoteConnection conn = RemoteConnection.get(c);

        String title = "";
        String detail = "";
        boolean warn = true;
        // 动作要和"用户此刻要做什么"对齐（2026-11-14 用户反馈"点第一个为什么跳转"）：
        // 待审批/待回答是**原地处理**（弹出那条审/问），不是跳远程连接页；
        // 只有断线那条才该走 connect。
        String action = "attention";

        if (!connected || !conn.isOnline()) {
            title = "电脑断线";
            detail = "点这里重连";
            action = "connect";
        } else if (live.phase == RemoteLiveStatus.Phase.WAITING_APPROVAL) {
            title = "电脑待审批";
            detail = "点这里处理";
        } else if (st != null && st.ok && st.approvalsPending > 0) {
            title = "电脑待处理 " + st.approvalsPending;
            detail = "点这里处理";
        } else if (conn.behindCount() > 8) {
            title = "通道落后 " + conn.behindCount() + " 条";
            detail = "正在补";
            warn = false;
            action = "connect";
        }

        String sig = title + '|' + detail + '|' + action;
        if (sig.equals(lastSignature)) return;
        lastSignature = sig;

        if (title.isEmpty()) {
            DockSlotBus.remove(ID);
            return;
        }
        // U1 (2026-11-14): classify the slot for the multi-board chip deck.
        // Rendering still uses the legacy full-width row, so behaviour is unchanged.
        String kind = warn ? "action" : "display";
        String board = warn ? "alert" : "state";
        boolean pinned = !warn;
        DockSlotBus.update(new DockSlotBus.Slot(ID, 6, title, detail, action, warn,
                kind, board, pinned, 0));
    }

    /** 断连/关闭状态条/页面销毁时调用。 */
    public static void clear() {
        lastSignature = "";
        DockSlotBus.remove(ID);
    }
}
