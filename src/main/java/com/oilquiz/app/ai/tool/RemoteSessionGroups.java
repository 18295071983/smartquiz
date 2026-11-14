package com.oilquiz.app.ai.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话选择框的分组逻辑（2026-11-14）。
 *
 * <p>为什么单独拆出来：分组/排序/标签是纯数据变换，跟 Android 控件无关；
 * 拆出来才能单测（"当前工作区必须排第一""路径太长要取末两级"这类规则，
 * 靠人点很容易漏），Activity 那边只负责把结果画成 View。</p>
 *
 * <p>电脑端按 <b>cwd（工作区）</b>把会话归到 {@code sessions/--<slug>--/} 目录下，
 * 桌面端也就按工作区看。手机以前把所有工作区的会话平铺成一行条，于是"电脑上 6 条、
 * 手机上三十多条"。这里按 cwd 分组、当前工作区置顶，其余归到"其他工作区"。</p>
 */
public final class RemoteSessionGroups {

    private RemoteSessionGroups() {
    }

    /** 一条会话属于哪个工作区（空 cwd 归到空串这组，界面上叫"未知工作区"） */
    public static String keyOf(Map<String, Object> row) {
        String cwd = str(row == null ? null : row.get("cwd"));
        return cwd.trim();
    }

    /** 按工作区分组；组内保持传入顺序（服务端已按最近活动倒序） */
    public static LinkedHashMap<String, List<Map<String, Object>>> group(List<Map<String, Object>> rows) {
        LinkedHashMap<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        if (rows == null) return groups;
        for (Map<String, Object> row : rows) {
            if (row == null) continue;
            String key = keyOf(row);
            List<Map<String, Object>> bucket = groups.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                groups.put(key, bucket);
            }
            bucket.add(row);
        }
        return groups;
    }

    /**
     * 组的显示顺序：当前工作区第一，其余按"组内最近一条活动"倒序。
     *
     * @param current 电脑当前工作区（/sessions.json 的 {@code current_workspace}）
     */
    public static List<String> orderedKeys(LinkedHashMap<String, List<Map<String, Object>>> groups,
                                           String current) {
        List<String> keys = new ArrayList<>(groups.keySet());
        final String cur = current == null ? "" : current.trim();
        keys.sort((a, b) -> {
            boolean ca = !cur.isEmpty() && cur.equals(a);
            boolean cb = !cur.isEmpty() && cur.equals(b);
            if (ca != cb) return ca ? -1 : 1;
            return Long.compare(latestOf(groups.get(b)), latestOf(groups.get(a)));
        });
        return keys;
    }

    /** 组内最近一次活动时间（毫秒；认 Number 和数字字符串，解析不了记 0） */
    public static long latestOf(List<Map<String, Object>> rows) {
        long max = 0L;
        if (rows == null) return max;
        for (Map<String, Object> row : rows) {
            long t = msOf(row == null ? null : row.get("updated_at"));
            if (t > max) max = t;
        }
        return max;
    }

    /**
     * 工作区短名：取末两级目录。
     * {@code D:\qzq\smartquiz → qzq/smartquiz}、{@code …\profiles\desktop → profiles/desktop}。
     * 只取末一级会和别的项目重名（smargquiz 这种还算好，{@code src}/ {@code app} 就完全分不出来），
     * 取全路径又长到看不全。
     */
    public static String label(String cwd) {
        String p = str(cwd).replace('\\', '/');
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty()) return "未知工作区";
        String[] parts = p.split("/");
        String last = parts.length > 0 ? parts[parts.length - 1] : p;
        if (last.isEmpty()) return "未知工作区";
        String prev = parts.length > 1 ? parts[parts.length - 2] : "";
        if (prev.isEmpty() || "users".equalsIgnoreCase(prev) || prev.endsWith(":")) return last;
        return prev + "/" + last;
    }

    /** 宽松毫秒解析：JSON 里的时间可能是 Integer/Long/Double 或字符串 */
    public static long msOf(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return d <= 0 ? 0L : (long) d;
        }
        try {
            double d = Double.parseDouble(String.valueOf(v).trim());
            return d <= 0 ? 0L : (long) d;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
