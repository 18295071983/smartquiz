package com.oilquiz.app.ai.tool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import java.util.Map;

/**
 * 反向指令执行器（电脑端 → 手机）。
 *
 * <p>下沉到服务层用：不依赖 Activity，App 在后台也能弹提示 / 发通知。
 * 只做纯 UI 与轻状态，不碰文件、不跑命令、不改权限。</p>
 *
 * <p>为什么对话框在这里退化成通知：`AlertDialog` 必须要 Activity 上下文，
 * 后台没有窗口可依附；用系统级悬浮窗需要额外权限，不值得为一条提示要权限。
 * 所以——需要"当场确认"的场景，等聊天页在前台时由 Activity 侧执行器处理。</p>
 */
public final class RemoteCommandExecutor {

    private RemoteCommandExecutor() {
    }

    /**
     * 执行一条指令。
     *
     * @return 给电脑端看的结果文案
     */
    public static String execute(Context ctx, String tool, Map<String, Object> args) {
        if (ctx == null || tool == null) return "no context";
        String title = str(args == null ? null : args.get("title"));
        String text = args == null ? "" : str(args.get("text") != null
                ? args.get("text") : args.get("content"));
        switch (tool) {
            case "toast":
                android.os.Handler h = new android.os.Handler(
                        android.os.Looper.getMainLooper());
                final String msg = text.isEmpty() ? title : text;
                h.post(() -> android.widget.Toast.makeText(ctx, msg,
                        android.widget.Toast.LENGTH_LONG).show());
                return "toast shown";
            case "dialog":
            case "ui_component":
                // 后台没有 Activity：退化成通知，至少让人能看到
                return notify(ctx, title.isEmpty() ? "来自电脑端" : title, text);
            case "notification":
            case "reminder":
            default:
                return notify(ctx, title.isEmpty() ? "来自电脑端" : title, text);
        }
    }

    private static String notify(Context ctx, String title, String text) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return "no notification service";
            String ch = "dsh_cmd";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(
                        ch, "电脑端消息", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(ctx, ch)
                    : new Notification.Builder(ctx);
            b.setContentTitle(title)
                    .setContentText(text == null ? "" : text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true);
            nm.notify((int) (System.currentTimeMillis() & 0xFFFFFF), b.build());
            return "notification shown";
        } catch (Throwable t) {
            return "notify failed: " + t;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
