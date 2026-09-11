package com.oilquiz.app.ai.tool;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主动提醒调度器（维度十 PER-02：按要求提供定时/事件提醒）。
 *
 * 基于 AlarmManager 实现一次性定时提醒，到点发系统通知（含震动/声音默认行为）：
 * - set：N 秒后 / 指定时刻（HH:mm，当天或次日）提醒，内容为自然语言
 * - list：查看全部未触发的提醒
 * - cancel：取消提醒
 * - 持久化到 reminders.json（App 重启后未触发的提醒仍有效）
 *
 * 约束：
 * - 单条内容最长 200 字符；提醒时间最多 7 天
 * - 系统省电模式下精确提醒可能延迟（setAndAllowWhileIdle 尽力而为）
 */
public class ReminderScheduler {

    private static final String TAG = "ReminderScheduler";
    private static final String REMINDER_FILE = "ai_reminders.json";
    private static final String CHANNEL_ID = "ai_reminder_channel";
    private static final int MAX_CONTENT_LENGTH = 200;
    /** 提醒最长时间跨度：7 天 */
    public static final long MAX_DELAY_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_REMINDERS = 50;

    private final Context context;
    private final ConcurrentHashMap<String, ReminderEntry> reminders = new ConcurrentHashMap<>();
    private static volatile ReminderScheduler instance;

    public static ReminderScheduler getInstance(Context context) {
        if (instance == null) {
            synchronized (ReminderScheduler.class) {
                if (instance == null) {
                    instance = new ReminderScheduler(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private ReminderScheduler(Context context) {
        this.context = context;
        ensureChannel();
        load();
    }

    /** 提醒条目 */
    public static class ReminderEntry {
        public final String id;
        public String content;
        /** 触发时间（epoch ms） */
        public long fireAt;
        public final long createdAt;

        public ReminderEntry(String id, String content, long fireAt, long createdAt) {
            this.id = id;
            this.content = content;
            this.fireAt = fireAt;
            this.createdAt = createdAt;
        }
    }

    // ==================== 对外操作 ====================

    /**
     * 创建提醒。
     * @param content 提醒内容
     * @param delayMs 距触发时间（毫秒），1 秒 ~ MAX_DELAY_MS
     * @return 条目；参数非法返回 null
     */
    public synchronized ReminderEntry schedule(String content, long delayMs) {
        if (content == null || content.trim().isEmpty()) return null;
        String c = content.trim();
        if (c.length() > MAX_CONTENT_LENGTH) {
            c = c.substring(0, MAX_CONTENT_LENGTH);
        }
        if (delayMs < 1000 || delayMs > MAX_DELAY_MS) return null;
        long now = System.currentTimeMillis();
        long fireAt = now + delayMs;
        if (reminders.size() >= MAX_REMINDERS) {
            // 淘汰最早已触发的提醒
            ReminderEntry oldest = null;
            for (ReminderEntry e : reminders.values()) {
                if (e.fireAt <= now && (oldest == null || e.fireAt < oldest.fireAt)) oldest = e;
            }
            if (oldest != null) {
                reminders.remove(oldest.id);
                cancelAlarm(oldest.id);
            } else {
                AILogger.w(TAG, "提醒已达上限且无已触发条目可淘汰，拒绝新建");
                return null;
            }
        }
        ReminderEntry entry = new ReminderEntry(
                "rm_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 10000),
                c, fireAt, now);
        reminders.put(entry.id, entry);
        scheduleAlarm(entry.id, fireAt);
        persist();
        AILogger.i(TAG, "提醒已创建: " + entry.id + " 于 " + fireAt);
        return entry;
    }

    /** 取消提醒（未触发的可取消；已触发的条目已移除返回 false） */
    public synchronized boolean cancel(String id) {
        if (id == null) return false;
        ReminderEntry removed = reminders.remove(id);
        if (removed != null) {
            cancelAlarm(id);
            persist();
            return true;
        }
        return false;
    }

    /** 全部未触发提醒（按触发时间升序） */
    public List<ReminderEntry> list() {
        long now = System.currentTimeMillis();
        List<ReminderEntry> result = new ArrayList<>();
        for (ReminderEntry e : reminders.values()) {
            if (e.fireAt > now) result.add(e);
        }
        result.sort((a, b) -> Long.compare(a.fireAt, b.fireAt));
        return result;
    }

    // ==================== 定时与通知 ====================

    private void scheduleAlarm(String id, long fireAt) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(context, ReminderReceiver.class);
            intent.putExtra("reminder_id", id);
            int reqCode = (id.hashCode() & 0x7fffffff);
            PendingIntent pi = PendingIntent.getBroadcast(context, reqCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, fireAt, pi);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "调度提醒失败: " + e.getMessage(), e);
        }
    }

    private void cancelAlarm(String id) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(context, ReminderReceiver.class);
            int reqCode = (id.hashCode() & 0x7fffffff);
            PendingIntent pi = PendingIntent.getBroadcast(context, reqCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
        } catch (Exception e) {
            AILogger.w(TAG, "取消提醒失败: " + e.getMessage());
        }
    }

    private void ensureChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = (NotificationManager)
                        context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm == null) return;
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "AI 提醒", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("AI 助手定时提醒");
                nm.createNotificationChannel(channel);
            }
        } catch (Exception e) {
            AILogger.w(TAG, "创建通知渠道失败: " + e.getMessage());
        }
    }

    /** 触发提醒：发通知并移除条目 */
    void fire(String id) {
        ReminderEntry entry = reminders.remove(id);
        if (entry == null) return;
        persist();
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(context, CHANNEL_ID)
                    : new Notification.Builder(context);
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("⏰ 提醒")
                    .setContentText(entry.content)
                    .setStyle(new Notification.BigTextStyle().bigText(entry.content))
                    .setAutoCancel(true)
                    .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
            nm.notify("ai_reminder_" + id, entry.id.hashCode() & 0x7fffffff, builder.build());
            AILogger.i(TAG, "提醒已触发: " + entry.content);
        } catch (Exception e) {
            AILogger.w(TAG, "提醒通知发送失败: " + e.getMessage());
        }
    }

    // ==================== 持久化 ====================

    private synchronized void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (ReminderEntry e : reminders.values()) {
                JSONObject obj = new JSONObject();
                obj.put("id", e.id);
                obj.put("content", e.content);
                obj.put("fireAt", e.fireAt);
                obj.put("createdAt", e.createdAt);
                arr.put(obj);
            }
            byte[] data = arr.toString(2).getBytes("UTF-8");
            File f = new File(context.getFilesDir(), REMINDER_FILE);
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
            }
            if (!tmp.renameTo(f)) {
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(data);
                }
                tmp.delete();
            }
        } catch (Exception e) {
            AILogger.w(TAG, "提醒持久化失败: " + e.getMessage());
        }
    }

    private synchronized void load() {
        try {
            File f = new File(context.getFilesDir(), REMINDER_FILE);
            if (!f.exists()) return;
            byte[] bytes;
            try (FileInputStream fis = new FileInputStream(f);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) out.write(buf, 0, n);
                bytes = out.toByteArray();
            }
            if (bytes.length == 0) return;
            JSONArray arr = new JSONArray(new String(bytes, "UTF-8"));
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String id = obj.optString("id", "");
                String content = obj.optString("content", "");
                long fireAt = obj.optLong("fireAt", 0);
                if (id.isEmpty() || content.isEmpty() || fireAt <= now) continue;
                reminders.put(id, new ReminderEntry(id, content, fireAt, obj.optLong("createdAt", now)));
                // 重新注册闹钟（App 重启后兜底）
                scheduleAlarm(id, fireAt);
            }
            AILogger.i(TAG, "提醒加载: " + reminders.size() + " 条");
        } catch (Exception e) {
            AILogger.w(TAG, "提醒加载失败: " + e.getMessage());
        }
    }

    /** 提醒触发广播接收器（必须静态注册于 Manifest 或动态注册；此处由调度器内部使用） */
    public static class ReminderReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            try {
                String id = intent.getStringExtra("reminder_id");
                if (id == null || id.isEmpty()) return;
                getInstance(ctx).fire(id);
            } catch (Throwable t) {
                AILogger.w(TAG, "提醒接收处理失败: " + t.getMessage());
            }
        }
    }
}
