package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 定时提醒工具（维度十 PER-02：主动提醒能力）。
 *
 * 用户要求"X 分钟后提醒我 / 明天早上 8 点提醒我"时创建系统通知提醒：
 * - set: 创建提醒（in_seconds=距现在秒数；或 datetime=具体时间"MM-dd HH:mm"/"HH:mm"，当天未到则当天、否则次日）
 * - list: 查看全部未触发的提醒
 * - cancel: 取消提醒（id）
 *
 * 到点发系统通知（标题"⏰ 提醒"+内容），即使 App 不在前台也能收到。
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
        return "定时提醒：用户要求\"X分钟后/明天早上8点/下午3点半 提醒我...\"时创建系统通知提醒（到点发通知，App不在前台也能收到）。set 创建（in_seconds=距现在秒数，或 datetime=具体时间\"HH:mm\"当天或\"MM-dd HH:mm\"指定日期，均按当前时刻最近的一个）；list 查看未触发提醒；cancel 取消（id）。到点后自动发通知并移除条目。使用边界：一次性提醒用本工具；周期性/重复提醒暂不支持，可如实告知用户。action: set|list|cancel";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作：set(创建提醒)|list(查看提醒)|cancel(取消提醒)");
        params.put("content", "提醒内容（set 必填，如\"记得喝水\"\"下午开会\"）");
        params.put("in_seconds", "距现在多少秒后提醒（set 用，1~604800，与 datetime 二选一）");
        params.put("datetime", "具体提醒时间（set 用，\"HH:mm\"=今天或明天最近的一个，\"MM-dd HH:mm\"=指定日期；与 in_seconds 二选一）");
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
                    long delayMs = parseDelayMs(parameters);
                    if (delayMs < 0) {
                        return AIToolResult.fail("需要时间参数：in_seconds（距现在秒数）或 datetime（\"HH:mm\"或\"MM-dd HH:mm\"），二选一");
                    }
                    ReminderScheduler.ReminderEntry entry = scheduler.schedule(content.trim(), delayMs);
                    if (entry == null) {
                        return AIToolResult.fail("提醒创建失败（时间参数非法或已达上限）：in_seconds 需 1~604800，datetime 需为未来时刻");
                    }
                    String fireText = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                            .format(new Date(entry.fireAt));
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "set");
                    result.put("id", entry.id);
                    result.put("content", entry.content);
                    result.put("fireAt", fireText);
                    result.put("message", "已设置提醒：\"" + entry.content + "\"，将于 " + fireText + " 通过系统通知提醒你");
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
                        items.add(item);
                        if (summary.length() > 0) summary.append("\n");
                        summary.append("- ").append(e.id).append(" 于 ").append(fmt.format(new Date(e.fireAt)))
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

    /** 解析提醒延迟毫秒数；参数缺失/非法返回 -1 */
    private long parseDelayMs(Map<String, Object> parameters) {
        try {
            Object secObj = parameters.get("in_seconds");
            if (secObj != null && !String.valueOf(secObj).trim().isEmpty()) {
                long secs = Long.parseLong(String.valueOf(secObj).trim());
                return secs * 1000L;
            }
            Object dtObj = parameters.get("datetime");
            if (dtObj != null && !String.valueOf(dtObj).trim().isEmpty()) {
                String dt = String.valueOf(dtObj).trim();
                long now = System.currentTimeMillis();
                java.util.Calendar cal = java.util.Calendar.getInstance();
                try {
                    if (dt.matches("\\d{2}:\\d{2}")) {
                        cal.set(java.util.Calendar.HOUR_OF_DAY, Integer.parseInt(dt.substring(0, 2)));
                        cal.set(java.util.Calendar.MINUTE, Integer.parseInt(dt.substring(3, 5)));
                        cal.set(java.util.Calendar.SECOND, 0);
                        cal.set(java.util.Calendar.MILLISECOND, 0);
                        long target = cal.getTimeInMillis();
                        if (target <= now) target += 24 * 60 * 60 * 1000L; // 今天已过则明天
                        return target - now;
                    } else if (dt.matches("\\d{2}-\\d{2} \\d{2}:\\d{2}")) {
                        int month = Integer.parseInt(dt.substring(0, 2));
                        int day = Integer.parseInt(dt.substring(3, 5));
                        int hour = Integer.parseInt(dt.substring(6, 8));
                        int minute = Integer.parseInt(dt.substring(9, 11));
                        cal.set(java.util.Calendar.MONTH, month - 1);
                        cal.set(java.util.Calendar.DAY_OF_MONTH, day);
                        cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
                        cal.set(java.util.Calendar.MINUTE, minute);
                        cal.set(java.util.Calendar.SECOND, 0);
                        cal.set(java.util.Calendar.MILLISECOND, 0);
                        return cal.getTimeInMillis() - now;
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
}
