package com.oilquiz.app.browser.push;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import com.oilquiz.app.R;
import com.oilquiz.app.WebViewActivity;

/**
 * 网页后台提醒的通知出口。
 *
 * <p>点击通知回到内置浏览器打开消息里的地址；消息地址只允许 http/https，
 * 避免服务器数据把用户带到 file:// 等本地路径。页面注册时给的 pageUrl 作为
 * 最后兜底（它本来就是用户正在看的可信页面）。</p>
 */
public final class WebPushNotifier {

    public static final String CHANNEL_ID = "web_push_channel";
    private static final String GROUP = "web_push";

    private WebPushNotifier() {
    }

    public static boolean canNotify(Context context) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            return manager != null && manager.areNotificationsEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void ensureChannel(Context context) {
        try {
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.webpush_channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(context.getString(R.string.webpush_channel_desc));
            manager.createNotificationChannel(channel);
        } catch (Throwable ignored) {
        }
    }

    public static void show(Context context, WebPushSubscription subscription,
                            WebPushMessageParser.Message message) {
        if (context == null || subscription == null || message == null) return;
        String title = message.title.isEmpty()
                ? context.getString(R.string.webpush_default_title) : message.title;
        String url = resolveUrl(subscription, message);
        int notifyId = ("webpush|" + subscription.id + "|" + message.id).hashCode();
        show(context, notifyId, title, message.body, url);
    }

    /** 页面立即弹一条（用于联调/前台提醒），不落持久化 */
    public static void showNow(Context context, String title, String body, String url) {
        String safeTitle = title == null || title.trim().isEmpty()
                ? context.getString(R.string.webpush_default_title) : title.trim();
        String safeBody = body == null ? "" : body.trim();
        String safeUrl = isHttpUrl(url) ? url.trim() : "";
        int notifyId = ("webpush_now|" + System.currentTimeMillis()).hashCode();
        show(context, notifyId, safeTitle, safeBody, safeUrl);
    }

    private static void show(Context context, int notifyId, String title, String body, String url) {
        try {
            ensureChannel(context);
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;

            Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setGroup(GROUP);

            if (url != null && !url.isEmpty()) {
                Intent intent = new Intent(context, WebViewActivity.class);
                intent.putExtra("url", url);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent pending = PendingIntent.getActivity(
                        context, notifyId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                builder.setContentIntent(pending);
            }
            manager.notify(notifyId, builder.build());
        } catch (Throwable ignored) {
        }
    }

    private static String resolveUrl(WebPushSubscription subscription,
                                     WebPushMessageParser.Message message) {
        if (message != null && isHttpUrl(message.url)) return message.url;
        String template = subscription.openUrl;
        if (template != null && !template.isEmpty()) {
            String filled = template
                    .replace("{id}", message != null ? message.id : "")
                    .replace("{title}", message != null ? message.title : "")
                    .replace("{url}", message != null ? message.url : "");
            if (isHttpUrl(filled)) return filled;
        }
        String page = subscription.pageUrl;
        if (isHttpUrl(page) || isLocalPage(page)) return page;
        return "";
    }

    private static boolean isHttpUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            Uri uri = Uri.parse(url.trim());
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLocalPage(String url) {
        return url != null && url.startsWith("file://");
    }
}
