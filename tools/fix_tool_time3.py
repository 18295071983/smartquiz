# -*- coding: utf-8 -*-
# ToolResultInterpreter.java: 补加 formatRelativeDate 方法（strDeep 前）
import io, sys
path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\agent\ToolResultInterpreter.java"
s = io.open(path, "r", encoding="utf-8").read()
old = '''    /** 从 JsonObject 中按多个候选 key 取首个非空字符串值（兼容 now 子对象，向下查一层） */
    private static String strDeep(JsonObject obj, String... keys) {'''
new = '''    /**
     * 将工具结果中的日期字符串与当前日期对比，返回相对时间标注。
     * 支持 "yyyy-MM-dd" 及更长的 ISO 时间戳（取前 10 位）。无法解析返回 null（不标注）。
     * 用途：提示模型搜索结果/网页发布时间的新旧，防止把历史旧闻当成最新信息。
     */
    private static String formatRelativeDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) return null;
        try {
            String d = dateStr.trim();
            if (d.length() > 10) d = d.substring(0, 10);
            // 只接受 yyyy-MM-dd 形态，避免把非日期字符串当日期解析
            if (d.length() != 10 || d.charAt(4) != '-' || d.charAt(7) != '-') return null;
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
            sdf.setLenient(false);
            java.util.Date d1 = sdf.parse(d);
            java.util.Date today = sdf.parse(sdf.format(new java.util.Date()));
            long diffMs = today.getTime() - d1.getTime();
            if (diffMs < 0) return null; // 未来日期不标注
            long days = diffMs / (24L * 3600 * 1000L);
            if (days == 0) return "(今天)";
            if (days == 1) return "(昨天)";
            if (days < 365) return "(" + days + "天前)";
            return "(" + (days / 365) + "年前)";
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 JsonObject 中按多个候选 key 取首个非空字符串值（兼容 now 子对象，向下查一层） */
    private static String strDeep(JsonObject obj, String... keys) {'''
c = s.count(old)
if c != 1:
    print("FAIL count=%d" % c); sys.exit(1)
s = s.replace(old, new)
io.open(path, "w", encoding="utf-8", newline="\n").write(s)
print("FORMAT_RELATIVE_DATE OK")
