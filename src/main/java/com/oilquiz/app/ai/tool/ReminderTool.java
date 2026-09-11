package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 定时提醒工具（维度十 PER-02：主动提醒能力）。
 *
 * 用户要求"X 分钟后提醒我 / 明天早上 8 点提醒我 / 每天早上 8 点提醒我 / 每周一三五 9 点提醒我"时创建系统通知提醒：
 * - set: 创建提醒（in_seconds=距现在秒数；或 datetime=具体时间"MM-dd HH:mm"/"HH:mm"，当天未到则当天、否则次日；
 *         repeat=daily 每天重复 / weekly 每周指定星期重复 + weekdays="1,3,5"）
 * - list: 查看全部未触发的提醒
 * - cancel: 取消提醒（id）
 *
 * 到点发系统通知（一次性提醒标题"⏰ 提醒"；周期提醒走闹钟渠道"⏰ 定时提醒"，系统闹钟铃声+震动），
 * 即使 App 不在前台也能收到；周期提醒触发后自动重排下一次。
 */
@Tool(value = "reminder", category = "memory")
public class ReminderTool implements AITool {

    private static final String TAG = "ReminderTool";
    private final Context context;

    public ReminderTool() {
        this.context = null;
    }

    public ReminderTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "reminder";
    }

    @Override
    public String getDescription() {
        return "定时提醒/闹钟：用户要求\"X分钟后/明天早上8点/下午3点半/每天早上8点/每周一三五9点 提醒我...\"时创建系统通知提醒（到点发通知，App不在前台也能收到）。set 创建：in_seconds=距现在秒数，或 datetime=\"HH:mm\"当天或次日最近时刻（或\"MM-dd HH:mm\"指定日期）；repeat=重复方式(none一次性默认/daily每天/weekly每周固定星期)，repeat=weekly 时 weekdays 传星期集合\"1,3,5\"(1=周一…7=周日，空=每天)；周期提醒到点自动重排下一次，使用闹钟铃声+震动。list 查看未触发提醒；cancel 取消（id）。action: set|list|cancel";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作：set(创建提醒)|list(查看提醒)|cancel(取消提醒)");
        params.put("content", "提醒内容（set 必填，如\"记得喝水\"\"下午开会\"）");
        params.put("in_seconds", "距现在多少秒后提醒（set 用，1~604800，与 datetime 二选一；可配 repeat 作为周期首次触发）");
        params.put("datetime", "具体提醒时间（set 用，\"HH:mm\"=今天或明天最近的一个，\"MM-dd HH:mm\"=指定日期；与 in_seconds 二选一）");
        params.put("repeat", "重复方式（set 用，可选：none=一次性(默认)/daily=每天固定时刻/weekly=每周固定星期固定时刻）");
        params.put("weekdays", "每周星期集合（repeat=weekly 时用，\"1,3,5\"=周一三五，1=周一…7=周日；不传=每天）");
        params.put("id", "提醒ID（cancel 用，list 可查看）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            if (context == null) {
                return AIToolResult.fail("提醒工具未初始化");
            }
            ReminderScheduler scheduler = ReminderScheduler.getInstance(context);
            String action = parameters.get("action") != null
                    ? String.valueOf(parameters.get("action")).trim().toLowerCase() : "list";

            switch (action) {
                case "set": {
                    String content = parameters.get("content") != null
                            ? String.valueOf(parameters.get("content")) : "";
                    if (content.trim().isEmpty()) {
                        return AIToolResult.fail("set 需要 content 参数（提醒内容）");
                    }
                    String repeat = parameters.get("repeat") != null
                            ? String.valueOf(parameters.get("repeat")).trim() : "none";
                    String weekdays = parameters.get("weekdays") != null
                            ? String.valueOf(parameters.get("weekdays")).trim() : "";
                    long fireAt = parseFireAt(parameters, repeat);
                    if (fireAt < 0) {
                        return AIToolResult.fail("需要时间参数：in_seconds（距现在秒数）或 datetime（\"HH:mm\"或\"MM-dd HH:mm\"），二选一");
                    }
                    ReminderScheduler.ReminderEntry entry = scheduler.schedule(
                            content.trim(), fireAt, repeat, weekdays);
                    if (entry == null) {
                        return AIToolResult.fail("提醒创建失败（时间/重复参数非法或已达上限）：in_seconds 需 1~604800，datetime 需为未来时刻，repeat 仅支持 none/daily/weekly，weekdays 数字需在 1~7");
                    }
                    String fireText = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                            .format(new Date(entry.fireAt));
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "set");
                    result.put("id", entry.id);
                    result.put("content", entry.content);
                    result.put("fireAt", fireText);
                    result.put("repeat", entry.repeatType);
                    result.put("weekdays", entry.weekdays);
                    result.put("message", "已设置" + describeRepeat(entry) + "提醒：\"" + entry.content
                            + "\"，首次将于 " + fireText + " 通过系统通知提醒你"
                            + ("none".equals(entry.repeatType) ? "" : "（此后自动重复）"));
                    return AIToolResult.success(result);
                }
                case "cancel": {
                    String id = parameters.get("id") != null ? String.valueOf(parameters.get("id")) : "";
                    if (id.trim().isEmpty()) {
                        return AIToolResult.fail("cancel 需要 id 参数（用 list 查看提醒 id）");
                    }
                    boolean ok = scheduler.cancel(id.trim());
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "cancelled");
                    result.put("id", id.trim());
                    result.put("cancelled", ok);
                    result.put("message", ok ? "已取消提醒: " + id.trim() : "未找到提醒: " + id.trim() + "（可能已触发移除）");
                    return AIToolResult.success(result);
                }
                case "list":
                default: {
                    List<ReminderScheduler.ReminderEntry> all = scheduler.list();
                    List<Map<String, Object>> items = new ArrayList<>();
                    StringBuilder summary = new StringBuilder();
                    SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
                    for (ReminderScheduler.ReminderEntry e : all) {
                        Map<String, Object> item = new HashMap<>();
                        item.put("id", e.id);
                        item.put("content", e.content);
                        item.put("fireAt", fmt.format(new Date(e.fireAt)));
                        item.put("repeat", e.repeatType);
                        item.put("weekdays", e.weekdays);
                        items.add(item);
                        if (summary.length() > 0) summary.append("\n");
                        summary.append("- ").append(e.id).append(" 于 ").append(fmt.format(new Date(e.fireAt)))
                                .append(describeRepeat(e).isEmpty() ? "" : "(" + describeRepeat(e) + ")")
                                .append(": ").append(e.content);
                    }
                    Map<String, Object> result = new HashMap<>();
                    result.put("count", items.size());
                    result.put("reminders", items);
                    result.put("message", items.isEmpty() ? "暂无未触发的提醒" : "未触发提醒:\n" + summary);
                    return AIToolResult.success(result);
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "提醒工具出错: " + e.getMessage(), e);
            return AIToolResult.fail("提醒工具出错: " + e.getMessage());
        }
    }

    /** 重复方式的中文描述（一次性返回空串） */
    private String describeRepeat(ReminderScheduler.ReminderEntry entry) {
        if (entry == null) return "";
        if ("daily".equals(entry.repeatType)) return "每日";
        if ("weekly".equals(entry.repeatType)) {
            if (entry.weekdays == null || entry.weekdays.trim().isEmpty()) return "每周";
            StringBuilder sb = new StringBuilder("每周");
            String[] days = entry.weekdays.split(",");
            for (String d : days) {
                switch (d.trim()) {
                    case "1": sb.append("一"); break;
                    case "2": sb.append("二"); break;
                    case "3": sb.append("三"); break;
                    case "4": sb.append("四"); break;
                    case "5": sb.append("五"); break;
                    case "6": sb.append("六"); break;
                    case "7": sb.append("日"); break;
                    default: break;
                }
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * 解析首次触发时刻（epoch ms）；参数缺失/非法返回 -1。
     * weekly 时结合 weekdays 找最近的指定星期；daily 时当天已过则次日。
     */
    private long parseFireAt(Map<String, Object> parameters, String repeatType) {
        try {
            Object secObj = parameters.get("in_seconds");
            if (secObj != null && !String.valueOf(secObj).trim().isEmpty()) {
                long secs = Long.parseLong(String.valueOf(secObj).trim());
                if (secs < 1 || secs > 604800) return -1;
                return System.currentTimeMillis() + secs * 1000L;
            }
            Object dtObj = parameters.get("datetime");
            if (dtObj != null && !String.valueOf(dtObj).trim().isEmpty()) {
                String dt = String.valueOf(dtObj).trim();
                long now = System.currentTimeMillis();
                Calendar cal = Calendar.getInstance();
                try {
                    if (dt.matches("\\d{2}:\\d{2}")) {
                        cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(dt.substring(0, 2)));
                        cal.set(Calendar.MINUTE, Integer.parseInt(dt.substring(3, 5)));
                        cal.set(Calendar.SECOND, 0);
                        cal.set(Calendar.MILLISECOND, 0);
                        if ("weekly".equalsIgnoreCase(repeatType)) {
                            return nextWeeklyFireAt(cal, now, parameters);
                        }
                        if (cal.getTimeInMillis() <= now) cal.add(Calendar.DAY_OF_YEAR, 1); // 今天已过则明天
                        return cal.getTimeInMillis();
                    } else if (dt.matches("\\d{2}-\\d{2} \\d{2}:\\d{2}")) {
                        int month = Integer.parseInt(dt.substring(0, 2));
                        int day = Integer.parseInt(dt.substring(3, 5));
                        int hour = Integer.parseInt(dt.substring(6, 8));
                        int minute = Integer.parseInt(dt.substring(9, 11));
                        cal.set(Calendar.MONTH, month - 1);
                        cal.set(Calendar.DAY_OF_MONTH, day);
                        cal.set(Calendar.HOUR_OF_DAY, hour);
                        cal.set(Calendar.MINUTE, minute);
                        cal.set(Calendar.SECOND, 0);
                        cal.set(Calendar.MILLISECOND, 0);
                        if (cal.getTimeInMillis() <= now) return -1; // 指定日期已过
                        return cal.getTimeInMillis();
                    }
                } catch (NumberFormatException ignored) {
                }
                return -1;
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** weekly：从 cal 当天起找最近的指定星期（保持时分），weekdays 为空=每天 */
    private long nextWeeklyFireAt(Calendar cal, long now, Map<String, Object> parameters) {
        Object wdObj = parameters.get("weekdays");
        Set<Integer> days = new HashSet<>();
        if (wdObj != null && !String.valueOf(wdObj).trim().isEmpty()) {
            for (String p : String.valueOf(wdObj).trim().split("[,，\\s]+")) {
                try {
                    int d = Integer.parseInt(p.trim());
                    if (d >= 1 && d <= 7) days.add(d);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        long base = cal.getTimeInMillis();
        if (base > now && (days.isEmpty() || days.contains(dowCn(cal)))) return base;
        for (int i = 0; i < 7; i++) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
            int d = dowCn(cal);
            if (days.isEmpty() || days.contains(d)) return cal.getTimeInMillis();
        }
        cal.add(Calendar.DAY_OF_YEAR, 1);
        return cal.getTimeInMillis();
    }

    /** Calendar.DAY_OF_WEEK(1=周日…7=周六) → 1=周一…7=周日 */
    private static int dowCn(Calendar cal) {
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        return dow == Calendar.SUNDAY ? 7 : dow - 1;
    }
}
