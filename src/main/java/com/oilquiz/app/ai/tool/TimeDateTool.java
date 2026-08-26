package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 时间日期工具：查询当前时间/日期/时区、时间戳与日期互转、日期计算。
 *
 * 参数：
 * - action: now（当前时间，默认）| timestamp_to_date | date_to_timestamp | timezone
 * - timestamp: 时间戳（秒），timestamp_to_date 用
 * - date: 日期字符串（yyyy-MM-dd HH:mm:ss），date_to_timestamp 用
 */
@Tool(
    value = "time_date",
    description = "时间日期工具：查询当前时间/日期/时区，时间戳与日期互转。"
            + "action: now(当前时间,默认)/timestamp_to_date(时间戳转日期)/date_to_timestamp(日期转时间戳)/timezone(时区信息)。"
            + "当前时间相关询问（现在几点/今天日期）用本工具获取准确时间。",
    category = "utility",
    aliases = {"time", "date", "current_time", "当前时间"},
    actions = {
        @Action(name = "now", description = "获取当前时间/日期/时区",
            params = {}),
        @Action(name = "timestamp_to_date", description = "时间戳转日期",
            params = {
                @Param(name = "timestamp", type = "integer", description = "秒级时间戳", required = true)
            }),
        @Action(name = "date_to_timestamp", description = "日期转时间戳",
            params = {
                @Param(name = "date", type = "string", description = "日期字符串（yyyy-MM-dd HH:mm:ss）", required = true)
            }),
        @Action(name = "timezone", description = "获取时区信息",
            params = {})
    }
)
public class TimeDateTool implements AITool {

    private static final String TAG = "TimeDateTool";

    public TimeDateTool() {
    }

    public TimeDateTool(Context context) {
    }

    @Override
    public String getName() {
        return "time_date";
    }

    @Override
    public String getDescription() {
        return "查询当前时间/日期/时区，时间戳与日期互转。action: now|timestamp_to_date|date_to_timestamp|timezone";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作：now(默认)|timestamp_to_date|date_to_timestamp|timezone");
        params.put("timestamp", "时间戳（秒），timestamp_to_date 时使用");
        params.put("date", "日期字符串（yyyy-MM-dd HH:mm:ss），date_to_timestamp 时使用");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = parameters.get("action") != null
                    ? String.valueOf(parameters.get("action")) : "now";
            switch (action) {
                case "timestamp_to_date": {
                    if (parameters.get("timestamp") == null) {
                        return AIToolResult.fail("缺少参数: timestamp（秒级时间戳）");
                    }
                    long ts;
                    try {
                        ts = Long.parseLong(String.valueOf(parameters.get("timestamp")).trim());
                    } catch (NumberFormatException e) {
                        return AIToolResult.fail("timestamp 格式错误: " + parameters.get("timestamp"));
                    }
                    // 合理范围校验（1970-01-01 ~ 9999-12-31）
                    if (ts < 0 || ts > 253402300799L) {
                        return AIToolResult.fail("timestamp 超出合理范围（0 ~ 253402300799）");
                    }
                    String dateStr = formatDate(new Date(ts * 1000));
                    Map<String, Object> info = new HashMap<>();
                    info.put("timestamp", ts);
                    info.put("date", dateStr);
                    info.put("weekday", weekday(new Date(ts * 1000)));
                    return AIToolResult.success(dateStr + "（周" + weekday(new Date(ts * 1000)) + "）", info);
                }
                case "date_to_timestamp": {
                    if (parameters.get("date") == null) {
                        return AIToolResult.fail("缺少参数: date（格式 yyyy-MM-dd HH:mm:ss）");
                    }
                    String date = String.valueOf(parameters.get("date"));
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                    Date d;
                    try {
                        d = sdf.parse(date);
                    } catch (java.text.ParseException e) {
                        return AIToolResult.fail("日期格式错误: " + date + "，应为 yyyy-MM-dd HH:mm:ss");
                    }
                    if (d == null) return AIToolResult.fail("日期格式错误，应为 yyyy-MM-dd HH:mm:ss");
                    long ts = d.getTime() / 1000;
                    Map<String, Object> info = new HashMap<>();
                    info.put("date", date);
                    info.put("timestamp", ts);
                    return AIToolResult.success("时间戳: " + ts, info);
                }
                case "timezone": {
                    TimeZone tz = TimeZone.getDefault();
                    int offsetMillis = tz.getOffset(System.currentTimeMillis());
                    // 输出 ±HH:mm（正确处理半小时/45分钟时区）
                    int absOffset = Math.abs(offsetMillis);
                    int hours = absOffset / 3600000;
                    int minutes = (absOffset % 3600000) / 60000;
                    String offsetStr = (offsetMillis >= 0 ? "+" : "-")
                            + String.format(Locale.US, "%02d:%02d", hours, minutes);
                    Map<String, Object> info = new HashMap<>();
                    info.put("id", tz.getID());
                    info.put("offset", offsetStr);
                    info.put("offsetMillis", offsetMillis);
                    info.put("dst", tz.inDaylightTime(new Date()));
                    return AIToolResult.success("时区: " + tz.getID() + "，UTC偏移: " + offsetStr
                            + (tz.inDaylightTime(new Date()) ? "（夏令时生效）" : ""), info);
                }
                case "now":
                default: {
                    long now = System.currentTimeMillis();
                    Map<String, Object> info = new HashMap<>();
                    info.put("date", formatDate(new Date(now)));
                    info.put("weekday", weekday(new Date(now)));
                    info.put("timestamp", now / 1000);
                    info.put("timezone", TimeZone.getDefault().getID());
                    return AIToolResult.success(formatDate(new Date(now)) + "（周" + weekday(new Date(now)) + "）", info);
                }
            }
        } catch (Exception e) {
            return AIToolResult.fail("时间工具执行失败: " + e.getMessage());
        }
    }

    private static String formatDate(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date);
    }

    private static String weekday(Date date) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(date);
        String[] weeks = {"日", "一", "二", "三", "四", "五", "六"};
        return weeks[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1];
    }
}
