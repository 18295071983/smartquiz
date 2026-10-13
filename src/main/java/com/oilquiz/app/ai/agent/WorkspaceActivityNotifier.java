package com.oilquiz.app.ai.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import java.util.List;

/**
 * 后台产出的系统通知（2026-10-13）。
 *
 * <p>只在<b>用户不在看聊天页</b>时发（由 {@link WorkspaceActivityLog#isForeground()} 决定），
 * 前台一律不打扰。通知用<b>固定 tag/id</b>：同一批后续产出是<b>更新</b>同一条，
 * 不会每写一个文件就堆一条。</p>
 *
 * <p>权限：Android 13+ 需要 {@code POST_NOTIFICATIONS}。这里<b>不主动申请</b>——
 * 未授权就安静跳过，回页面时的系统消息提示仍然有效，产出留痕本身也不受影响。
 * （为一个"提示音"弹权限框是打扰，不是反馈。）</p>
 */
public final class WorkspaceActivityNotifier {

    private static final String CHANNEL_ID = "ai_workspace_activity";
    private static final String TAG_NAME = "ai_workspace_activity";
    private static final int NOTIFY_ID = 0x5741;   // 'WA'

    private WorkspaceActivityNotifier() {
    }

    /** 发一条"助手在后台产出了 N 个文件"的通知；不可通知/无内容时静默返回 */
    public static void showArtifacts(Context context, List<WorkspaceActivityLog.Entry> entries) {
        if (context == null || entries == null || entries.isEmpty()) return;
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || !canNotify(context, nm)) return;

            ensureChannel(nm);

            StringBuilder names = new StringBuilder();
            int shown = Math.min(entries.size(), 4);
            for (int i = 0; i < shown; i++) {
                if (i > 0) names.append("、");
                names.append(iconOf(entries.get(i).kind)).append(entries.get(i).name);
            }
            if (entries.size() > shown) {
                names.append(" 等 ").append(entries.size()).append(" 项");
            }
            String detail = detailOf(entries);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(context, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(context);
            }
            PendingIntent pi = openChatIntent(context);
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("助手已产出 " + entries.size() + " 个文件")
                    .setContentText(names)
                    .setStyle(new Notification.BigTextStyle().bigText(
                            "助手在你离开期间完成了：\n" + names + "\n\n" + detail))
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setAutoCancel(true);
            if (pi != null) builder.setContentIntent(pi);
            nm.notify(TAG_NAME, NOTIFY_ID, builder.build());
        } catch (Throwable ignored) {
            // 通知失败不影响生成主流程
        }
    }

    /** 同一条通知就地更新为"已读/取消"（回到前台时调用，避免提示滞留） */
    public static void cancel(Context context) {
        if (context == null) return;
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(TAG_NAME, NOTIFY_ID);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 内部 ====================

    private static boolean canNotify(Context context, NotificationManager nm) {
        try {
            if (!nm.areNotificationsEnabled()) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "助手后台产出", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("离开聊天页期间，助手完成文件产出时的提示（前台不打扰）");
            channel.enableVibration(false);
            nm.createNotificationChannel(channel);
        } catch (Throwable ignored) {
        }
    }

    /** 点击回到 App（用 launcher intent，避免 ai.agent 反向依赖 ui.activity） */
    private static PendingIntent openChatIntent(Context context) {
        try {
            Intent i = context.getPackageManager()
                    .getLaunchIntentForPackage(context.getPackageName());
            if (i == null) return null;
            i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return PendingIntent.getActivity(context, NOTIFY_ID, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String iconOf(String kind) {
        if ("image".equals(kind)) return "🖼";
        if ("video".equals(kind)) return "🎬";
        if ("audio".equals(kind)) return "🎵";
        return "📄";
    }

    private static String detailOf(List<WorkspaceActivityLog.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (WorkspaceActivityLog.Entry e : entries) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("· ").append(e.name);
            if (!TextUtils.isEmpty(e.tool)) sb.append("  ← ").append(e.tool);
        }
        return sb.toString();
    }
}
