package com.oilquiz.app.ai.chat.status;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * "待你处理"通知栏联动（2026-11-13）。
 *
 * <p>手机不在前台/用户没看聊天页时，审批与反问只能靠通知栏喊一声：有 N 条待处理，
 * 点通知直接回到对话页（那里有插槽里的卡片和消息里的原卡）。处理完自动撤掉。</p>
 *
 * <p>只做"提醒 + 跳转"，不在通知里放操作按钮 —— 审批/反问需要完整上下文（权限名、问题选项），
 * 塞进通知按钮既点不准也容易误触。</p>
 */
public final class PendingActionNotifier {

    public static final String CHANNEL_ID = "pending_action";
    private static final int NOTIFICATION_ID = 7701;

    private PendingActionNotifier() {
    }

    /** 通知正文（纯函数，便于单测）：几条、哪一类 */
    public static String contentText(int approvals, int questions) {
        int total = Math.max(0, approvals) + Math.max(0, questions);
        if (total <= 0) return "";
        String kind;
        if (approvals > 0 && questions > 0) {
            kind = "审批与提问";
        } else if (approvals > 0) {
            kind = "审批";
        } else {
            kind = "提问";
        }
        return total + " 条" + kind + "待你处理，点这里回到对话";
    }

    /** 标题（纯函数）：0 条返回空串，调用方据此跳过 */
    public static String titleFor(int approvals, int questions) {
        int total = Math.max(0, approvals) + Math.max(0, questions);
        return total <= 0 ? "" : "待你处理";
    }

    /** 有 pending 就发（同 id 覆盖），没有就撤 */
    public static void sync(Context context, int approvals, int questions, Class<?> targetActivity) {
        if (context == null) return;
        if (titleFor(approvals, questions).isEmpty()) {
            clear(context);
            return;
        }
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 33
                    && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                return;   // 用户没给通知权限：静默跳过，不打扰
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "待你处理",
                        NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("审批 / 反问等待你处理时的提醒");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(context, targetActivity);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(context, NOTIFICATION_ID, open, flags);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, CHANNEL_ID)
                    : new Notification.Builder(context);
            b.setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(titleFor(approvals, questions))
                    .setContentText(contentText(approvals, questions))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pi);
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (Throwable t) {
            // 通知失败绝不能影响对话页
            android.util.Log.w("PendingActionNotifier", "通知栏联动失败: " + t);
        }
    }

    public static void clear(Context context) {
        if (context == null) return;
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
        } catch (Throwable ignored) {
            // 同上
        }
    }
}
