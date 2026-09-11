package com.oilquiz.app.ai.tool;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主动提醒调度器（维度十 PER-02：按要求提供定时/事件提醒）。
 *
 * 基于 AlarmManager 实现定时提醒，到点发系统通知（含震动/声音默认行为）：
 * - set：N 秒后 / 指定时刻（HH:mm，当天或次日）提醒，内容为自然语言
 * - repeat：daily=每天固定时刻重复；weekly=每周指定星期几（weekdays="1,3,5"，1=周一…7=周日）固定时刻重复
 * - list：查看全部未触发的提醒
 * - cancel：取消提醒
 * - 持久化到 reminders.json（App 重启后未触发的提醒仍有效；周期提醒触发后自动重排下一次）
 *
 * 约束：
 * - 单条内容最长 200 字符；一次性提醒时间最多 7 天；周期提醒首次触发最多 30 天
 * - 系统省电模式下精确提醒可能延迟（setAndAllowWhileIdle 尽力而为）
 */
public class ReminderScheduler {

    private static final String TAG = "ReminderScheduler";
    private static final String REMINDER_FILE = "ai_reminders.json";
    private static final String CHANNEL_ID = "ai_reminder_channel";
    /** 闹钟渠道：周期提醒使用系统闹钟铃声，更接近闹钟体验 */
    private static final String CHANNEL_ID_ALARM = "ai_alarm_channel";
    private static final int MAX_CONTENT_LENGTH = 200;
    /** 一次性提醒最长时间跨度：7 天 */
    public static final long MAX_DELAY_MS = 7L * 24 * 60 * 60 * 1000;
    /** 周期提醒首次触发最长时间跨度：30 天 */
    public static final long MAX_REPEAT_DELAY_MS = 30L * 24 * 60 * 60 * 1000;
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
        /** 下次触发时间（epoch ms） */
        public long fireAt;
        public final long createdAt;
        /** 重复类型："none"一次性 / "daily"每天 / "weekly"每周指定星期 */
        public String repeatType;
        /** weekly 的星期集合："1,3,5"（1=周一…7=周日），空串=每天 */
        public String weekdays;

        public ReminderEntry(String id, String content, long fireAt, long createdAt) {
            this(id, content, fireAt, createdAt, "none", "");
        }

        public ReminderEntry(String id, String content, long fireAt, long createdAt,
                             String repeatType, String weekdays) {
            this.id = id;
            this.content = content;
            this.fireAt = fireAt;
            this.createdAt = createdAt;
            this.repeatType = repeatType == null ? "none" : repeatType;
            this.weekdays = weekdays == null ? "" : weekdays;
        }
    }

    // ==================== 对外操作 ====================

    /**
     * 创建一次性提醒（兼容旧接口）。
     * @param content 提醒内容
     * @param delayMs 距触发时间（毫秒），1 秒 ~ MAX_DELAY_MS
     * @return 条目；参数非法返回 null
     */
    public synchronized ReminderEntry schedule(String content, long delayMs) {
        return schedule(content, System.currentTimeMillis() + delayMs, "none", "");
    }

    /**
     * 创建提醒（支持周期重复）。
     * @param content 提醒内容
     * @param fireAt 下次触发时刻（epoch ms，必须为未来时刻）
     * @param repeatType 重复类型：none/daily/weekly
     * @param weekdays weekly 的星期集合（"1,3,5"，1=周一…7=周日；空串=每天）
     * @return 条目；参数非法返回 null
     */
    public synchronized ReminderEntry schedule(String content, long fireAt,
                                               String repeatType, String weekdays) {
        if (content == null || content.trim().isEmpty()) return null;
        String c = content.trim();
        if (c.length() > MAX_CONTENT_LENGTH) {
            c = c.substring(0, MAX_CONTENT_LENGTH);
        }
        String rt = normalizeRepeat(repeatType);
        String wd = normalizeWeekdays(weekdays);
        if (rt == null || wd == null) return null;
        long now = System.currentTimeMillis();
        long maxFireAt = "none".equals(rt) ? now + MAX_DELAY_MS : now + MAX_REPEAT_DELAY_MS;
        if (fireAt <= now || fireAt > maxFireAt) return null;
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
                c, fireAt, now, rt, wd);
        reminders.put(entry.id, entry);
        scheduleAlarm(entry.id, fireAt);
        persist();
        AILogger.i(TAG, "提醒已创建: " + entry.id + " 于 " + fireAt + " repeat=" + rt + " wd=" + wd);
        return entry;
    }

    /** 校验并归一化重复类型；非法返回 null */
    private static String normalizeRepeat(String repeatType) {
        if (repeatType == null) return "none";
        String t = repeatType.trim().toLowerCase();
        if (t.isEmpty() || "none".equals(t) || "once".equals(t)) return "none";
        if ("daily".equals(t) || "day".equals(t) || "每日".equals(t) || "每天".equals(t)) return "daily";
        if ("weekly".equals(t) || "week".equals(t) || "每周".equals(t)) return "weekly";
        return null;
    }

    /** 校验并归一化星期集合："1,3,5" → "1,3,5"；非法返回 null（合法但空 → 空串=每天） */
    private static String normalizeWeekdays(String weekdays) {
        if (weekdays == null || weekdays.trim().isEmpty()) return "";
        String[] parts = weekdays.trim().split("[,，\\s]+");
        Set<Integer> days = new HashSet<>();
        for (String p : parts) {
            try {
                int d = Integer.parseInt(p.trim());
                if (d >= 1 && d <= 7) days.add(d);
            } catch (NumberFormatException ignored) {
            }
        }
        if (days.isEmpty()) return "";
        List<Integer> sorted = new ArrayList<>(days);
        sorted.sort(Integer::compareTo);
        StringBuilder sb = new StringBuilder();
        for (int d : sorted) {
            if (sb.length() > 0) sb.append(",");
            sb.append(d);
        }
        return sb.toString();
    }

    /** 解析星期集合字符串为 Set（空/非法 → 空集合） */
    private static Set<Integer> parseWeekdays(String weekdays) {
        Set<Integer> days = new HashSet<>();
        if (weekdays == null || weekdays.trim().isEmpty()) return days;
        for (String p : weekdays.split(",")) {
            try {
                int d = Integer.parseInt(p.trim());
                if (d >= 1 && d <= 7) days.add(d);
            } catch (NumberFormatException ignored) {
            }
        }
        return days;
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
                channel.setDescription("AI 助手定时提醒（一次性）");
                nm.createNotificationChannel(channel);
                // 闹钟渠道：周期提醒使用系统闹钟铃声 + 震动，体验更接近闹钟
                NotificationChannel alarmChannel = new NotificationChannel(
                        CHANNEL_ID_ALARM, "AI 闹钟", NotificationManager.IMPORTANCE_HIGH);
                alarmChannel.setDescription("AI 助手周期闹钟（每天/每周固定时刻，闹钟铃声+震动）");
                Uri alarmSound = Settings.System.DEFAULT_ALARM_ALERT_URI;
                if (alarmSound != null) {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build();
                    alarmChannel.setSound(alarmSound, attrs);
                }
                alarmChannel.enableVibration(true);
                alarmChannel.setVibrationPattern(new long[]{1000, 500, 1000, 500, 1000});
                nm.createNotificationChannel(alarmChannel);
            }
        } catch (Exception e) {
            AILogger.w(TAG, "创建通知渠道失败: " + e.getMessage());
        }
    }

    /** 计算周期提醒的下一次触发时刻 */
    private long computeNextFireAt(ReminderEntry entry) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(entry.fireAt);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if ("daily".equals(entry.repeatType)) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
            return cal.getTimeInMillis();
        }
        // weekly：从下一天起找最近的指定星期（保持时分不变）
        Set<Integer> days = parseWeekdays(entry.weekdays);
        for (int i = 0; i < 7; i++) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
            if (days.isEmpty()) return cal.getTimeInMillis();
            int dow = cal.get(Calendar.DAY_OF_WEEK);
            int dowCn = dow == Calendar.SUNDAY ? 7 : dow - 1; // 1=周一…7=周日
            if (days.contains(dowCn)) return cal.getTimeInMillis();
        }
        cal.add(Calendar.DAY_OF_YEAR, 1);
        return cal.getTimeInMillis();
    }

    /** 触发提醒：发通知；周期提醒自动重排下一次，一次性提醒移除 */
    void fire(String id) {
        ReminderEntry entry = reminders.get(id);
        if (entry == null) return;
        sendNotification(entry);
        if ("daily".equals(entry.repeatType) || "weekly".equals(entry.repeatType)) {
            long next = computeNextFireAt(entry);
            entry.fireAt = next;
            scheduleAlarm(id, next);
            AILogger.i(TAG, "周期提醒已重排: " + id + " 下次 " + next + " (" + entry.content + ")");
        } else {
            reminders.remove(id);
            cancelAlarm(id);
        }
        persist();
    }

    /** 发送提醒通知（周期提醒走闹钟渠道：系统闹钟铃声+震动） */
    private void sendNotification(ReminderEntry entry) {
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            boolean repeat = "daily".equals(entry.repeatType) || "weekly".equals(entry.repeatType);
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(context, repeat ? CHANNEL_ID_ALARM : CHANNEL_ID);
            } else {
                builder = new Notification.Builder(context);
            }
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(repeat ? "⏰ 定时提醒" : "⏰ 提醒")
                    .setContentText(entry.content)
                    .setStyle(new Notification.BigTextStyle().bigText(entry.content))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
            nm.notify("ai_reminder_" + entry.id, entry.id.hashCode() & 0x7fffffff, builder.build());
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
                obj.put("repeatType", e.repeatType);
                obj.put("weekdays", e.weekdays);
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
                String rt = normalizeRepeat(obj.optString("repeatType", "none"));
                String wd = normalizeWeekdays(obj.optString("weekdays", ""));
                if (id.isEmpty() || content.isEmpty() || rt == null || wd == null) continue;
                ReminderEntry entry = new ReminderEntry(id, content, fireAt, obj.optLong("createdAt", now), rt, wd);
                if (fireAt <= now) {
                    // 已过期：一次性丢弃；周期提醒重排到最近未来再注册
                    if ("none".equals(rt)) continue;
                    for (int k = 0; k < 8 && entry.fireAt <= now; k++) {
                        entry.fireAt = computeNextFireAt(entry);
                    }
                    if (entry.fireAt <= now) continue;
                }
                reminders.put(id, entry);
                // 重新注册闹钟（App 重启后兜底）
                scheduleAlarm(id, entry.fireAt);
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
