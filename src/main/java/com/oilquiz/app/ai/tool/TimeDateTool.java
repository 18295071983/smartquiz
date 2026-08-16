package com.oilquiz.app.ai.tool;

import android.content.Context;

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
                    long ts = parameters.get("timestamp") != null
                            ? Long.parseLong(String.valueOf(parameters.get("timestamp")))
                            : System.currentTimeMillis() / 1000;
                    String dateStr = formatDate(new Date(ts * 1000));
                    Map<String, Object> info = new HashMap<>();
                    info.put("timestamp", ts);
                    info.put("date", dateStr);
                    info.put("weekday", weekday(new Date(ts * 1000)));
                    return AIToolResult.success(dateStr + "（周" + weekday(new Date(ts * 1000)) + "）", info);
                }
                case "date_to_timestamp": {
                    String date = String.valueOf(parameters.get("date"));
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                    Date d = sdf.parse(date);
                    if (d == null) return AIToolResult.fail("日期格式错误，应为 yyyy-MM-dd HH:mm:ss");
                    long ts = d.getTime() / 1000;
                    Map<String, Object> info = new HashMap<>();
                    info.put("date", date);
                    info.put("timestamp", ts);
                    return AIToolResult.success("时间戳: " + ts, info);
                }
                case "timezone": {
                    TimeZone tz = TimeZone.getDefault();
                    Map<String, Object> info = new HashMap<>();
                    info.put("id", tz.getID());
                    info.put("offsetHours", tz.getOffset(System.currentTimeMillis()) / 3600000);
                    info.put("dst", tz.inDaylightTime(new Date()));
                    return AIToolResult.success("时区: " + tz.getID() + "，UTC偏移: " + (tz.getOffset(System.currentTimeMillis()) / 3600000) + "小时", info);
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
