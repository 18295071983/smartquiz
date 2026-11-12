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

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
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
