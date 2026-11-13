package com.oilquiz.app.ai.tool;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 通知按钮回传（2026-11-12）：电脑端下发的通知可以带 actions，
 * 用户在通知栏点按钮后，把选择回传给电脑端。
 *
 * <p>为什么要这个：后台没有 Activity，弹不出对话框；用通知的 action 按钮
 * 就能在没有界面的情况下完成"确认/取消"这类交互，并把结果回给 PC。</p>
 */
public class RemoteCmdActionReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.oilquiz.app.REMOTE_CMD_ACTION";
    public static final String EXTRA_CMD_ID = "cmd_id";
    public static final String EXTRA_VALUE = "value";
    public static final String EXTRA_LABEL = "label";
    /** 审批通知的按钮（2026-11-13）：直接回传审批决定，不需要 Activity */
    public static final String EXTRA_APPROVAL_ID = "approval_id";
    public static final String EXTRA_APPROVAL_DECISION = "approval_decision";
    /** 通知 id（服务用 NOTIF_ID_APPROVAL，页面用 7701；点击后按这个撤） */
    public static final String EXTRA_NOTIF_ID = "notif_id";
    /** 审批通知固定 id（与 DockSlotBus 的待处理提醒共用一条） */
    public static final int APPROVAL_NOTIFICATION_ID = 7701;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        final String approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID);
        if (approvalId != null && !approvalId.isEmpty()) {
            final boolean allow = "allow".equals(intent.getStringExtra(EXTRA_APPROVAL_DECISION));
            android.util.Log.i("DshApproval", "[notify-action] 点击 id=" + approvalId
                    + " decision=" + (allow ? "allow" : "deny"));
            new Thread(() -> {
                try {
                    boolean ok = RemoteDshTool.answerApproval(context, approvalId, allow);
                    android.util.Log.i("DshApproval", "[notify-action] 回传结果 ok=" + ok);
                } catch (Exception e) {
                    android.util.Log.w("DshApproval", "[notify-action] 回传失败: " + e);
                }
            }, "dsh-approval-action").start();
            try {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(intent.getIntExtra(EXTRA_NOTIF_ID, APPROVAL_NOTIFICATION_ID));
                    nm.cancel(APPROVAL_NOTIFICATION_ID);
                }
            } catch (Throwable ignored) {
                // 取消失败不影响
            }
            return;
        }
        final String cmdId = intent.getStringExtra(EXTRA_CMD_ID);
        final String value = intent.getStringExtra(EXTRA_VALUE);
        final String label = intent.getStringExtra(EXTRA_LABEL);
        if (cmdId == null || cmdId.isEmpty()) return;
        new Thread(() -> {
            try {
                RemoteDshTool.ackCommand(context, cmdId, true,
                        "action:" + (value == null ? "" : value)
                                + (label == null || label.isEmpty() ? "" : " (" + label + ")"));
            } catch (Exception ignored) {
                // 回执失败：指令会自然过期
            }
        }, "dsh-cmd-action").start();
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel((int) (cmdId.hashCode() & 0xFFFFFF));
        } catch (Throwable ignored) {
            // 取消失败不影响
        }
    }
}
