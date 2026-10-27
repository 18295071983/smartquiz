package com.oilquiz.app.ai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具执行错误智能恢复。
 *
 * 分析工具执行失败的错误信息，识别缺失的参数，并判断是否可以自动获取。
 * 配合 AIChatActivity 实现错误自动恢复：自动定位补充位置、弹出输入框收集用户输入。
 */
public class ToolErrorRecovery {

    /** 缺失参数信息 */
    public static class MissingParam {
        /** 参数名（city/lat/lon/query 等） */
        public String key;
        /** 参数描述（城市/纬度/搜索关键词） */
        public String description;
        /** 是否可自动获取（位置类可自动，文本类需用户输入） */
        public boolean autoFillable;
        /** 是否必填（默认 true） */
        public boolean required;

        public MissingParam(String key, String description, boolean autoFillable) {
            this(key, description, autoFillable, true);
        }
        public MissingParam(String key, String description, boolean autoFillable, boolean required) {
            this.key = key;
            this.description = description;
            this.autoFillable = autoFillable;
            this.required = required;
        }
    }

    /**
     * 分析错误信息，识别缺失的参数。
     *
     * @param toolName      工具名
     * @param errorMsg      错误信息
     * @param currentParams 当前已有参数
     * @return 缺失参数列表；空列表表示无法识别
     */
    public static List<MissingParam> analyzeMissingParams(String toolName, String errorMsg,
                                                          Map<String, Object> currentParams) {
        List<MissingParam> missing = new ArrayList<>();
        if (errorMsg == null || errorMsg.trim().isEmpty()) {
            return missing;
        }
        String lower = errorMsg.toLowerCase();

        // 城市/位置类参数缺失
        if (containsAny(lower, "city", "城市", "location", "位置", "地点")
                && !hasParam(currentParams, "city")) {
            missing.add(new MissingParam("city", "城市", true));
        }
        // 纬度缺失
        if (containsAny(lower, "lat", "latitude", "纬度")
                && !hasParam(currentParams, "lat")) {
            missing.add(new MissingParam("lat", "纬度", true));
        }
        // 经度缺失
        if (containsAny(lower, "lon", "lng", "longitude", "经度")
                && !hasParam(currentParams, "lon")) {
            missing.add(new MissingParam("lon", "经度", true));
        }
        // 坐标整体缺失（lat+lon）
        if (containsAny(lower, "坐标", "coordinate", "coordinates")
                && !hasParam(currentParams, "lat") && !hasParam(currentParams, "lon")) {
            missing.add(new MissingParam("lat", "纬度", true));
            missing.add(new MissingParam("lon", "经度", true));
        }

        // 搜索关键词类参数缺失
        if (containsAny(lower, "query", "keyword", "关键词", "搜索词", "搜索关键字")
                && !hasParam(currentParams, "query")
                && !hasParam(currentParams, "keyword")) {
            missing.add(new MissingParam("query", "搜索关键词", false));
        }

        // 数据库关键词缺失
        if (containsAny(lower, "keyword", "题目关键词", "搜索关键词")
                && "database".equals(toolName)
                && !hasParam(currentParams, "keyword")) {
            // 避免重复添加
            boolean exists = false;
            for (MissingParam mp : missing) {
                if ("query".equals(mp.key)) { exists = true; break; }
            }
            if (!exists) {
                missing.add(new MissingParam("keyword", "搜索关键词", false));
            }
        }

        // 文本类参数缺失
        if (containsAny(lower, "text", "文本", "内容", "content")
                && !hasParam(currentParams, "text")) {
            missing.add(new MissingParam("text", "文本内容", false));
        }

        // URL类参数缺失
        if (containsAny(lower, "url", "网址", "链接", "link", "网页地址")
                && !hasParam(currentParams, "url")) {
            missing.add(new MissingParam("url", "网页地址", false));
        }

        // 数学表达式缺失
        if (containsAny(lower, "expression", "表达式", "算式")
                && !hasParam(currentParams, "expression")) {
            missing.add(new MissingParam("expression", "数学表达式", false));
        }

        // 文件路径缺失
        if (containsAny(lower, "file_path", "filepath", "文件路径", "路径")
                && !hasParam(currentParams, "file_path")
                && !hasParam(currentParams, "directory_path")) {
            missing.add(new MissingParam("file_path", "文件路径", false));
        }

        // 页面名称缺失
        if (containsAny(lower, "page", "页面")
                && !hasParam(currentParams, "page")) {
            missing.add(new MissingParam("page", "页面名称", false));
        }

        // 通用：parameter xxx is required / 缺少参数: xxx / xxx cannot be null
        String genericParam = extractGenericMissingParam(errorMsg, lower);
        if (genericParam != null && !hasParam(currentParams, genericParam)) {
            // 避免重复
            boolean exists = false;
            for (MissingParam mp : missing) {
                if (genericParam.equals(mp.key)) { exists = true; break; }
            }
            if (!exists) {
                missing.add(new MissingParam(genericParam, genericParam, canAutoFill(genericParam)));
            }
        }

        return missing;
    }

    /**
     * 判断参数是否可以自动获取。
     */
    public static boolean canAutoFill(String paramKey) {
        if (paramKey == null) return false;
        switch (paramKey) {
            case "city":
            case "lat":
            case "lon":
            case "time":
            case "date":
            case "datetime":
                return true;
            default:
                return false;
        }
    }

    /**
     * 判断缺失参数列表中是否全部可以自动获取。
     */
    public static boolean allAutoFillable(List<MissingParam> missing) {
        if (missing == null || missing.isEmpty()) return false;
        for (MissingParam mp : missing) {
            if (!mp.autoFillable) return false;
        }
        return true;
    }

    /**
     * 判断是否需要定位（缺失参数中包含 city/lat/lon）。
     */
    public static boolean needsLocation(List<MissingParam> missing) {
        if (missing == null) return false;
        for (MissingParam mp : missing) {
            if ("city".equals(mp.key) || "lat".equals(mp.key) || "lon".equals(mp.key)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 内部辅助方法 ====================

    /** 检查文本是否包含任一关键词（不区分大小写） */
    private static boolean containsAny(String text, String... keywords) {
        if (text == null) return false;
        for (String kw : keywords) {
            if (kw != null && text.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 检查参数是否已存在且非空 */
    private static boolean hasParam(Map<String, Object> params, String key) {
        if (params == null) return false;
        Object v = params.get(key);
        if (v == null) return false;
        if (v instanceof String) {
            return !((String) v).trim().isEmpty();
        }
        return true;
    }

    /**
     * 从通用错误格式中提取缺失参数名。
     *
     * <p>锚点（{@code parameter } / {@code 缺少参数}）在<b>小写副本</b>里找，但名字要从
     * <b>原文</b>里切：此前整段都在小写副本上处理，于是 {@code fileName} 出来变成
     * {@code filename}，拿它去 {@code currentParams} 里找永远找不到（参数名是大小写敏感的）。</p>
     */
    private static String extractGenericMissingParam(String original, String lowerError) {
        // "parameter xxx is required"
        int idx = lowerError.indexOf("parameter ");
        if (idx >= 0) {
            return leadingIdentifier(sliceOriginal(original, lowerError, idx + 10));
        }
        // "缺少参数: xxx" / "缺少参数 xxx"（工具侧统一用它，后面可跟全角括号补条件说明）
        idx = lowerError.indexOf("缺少参数");
        if (idx >= 0) {
            String rest = sliceOriginal(original, lowerError, idx + 4)
                    .replace(":", "").replace("：", "").trim();
            if (!rest.isEmpty()) {
                return leadingIdentifier(rest);
            }
        }
        // "xxx cannot be null" / "xxx is null"
        idx = lowerError.indexOf(" cannot be null");
        if (idx > 0) {
            String before = sliceOriginal(original, lowerError, 0).substring(0, idx);
            int space = before.lastIndexOf(' ');
            if (space >= 0 && space < before.length() - 1) {
                return before.substring(space + 1).trim();
            }
            return before.trim();
        }
        idx = lowerError.indexOf(" is null");
        if (idx > 0) {
            String before = sliceOriginal(original, lowerError, 0).substring(0, idx);
            int space = before.lastIndexOf(' ');
            if (space >= 0 && space < before.length() - 1) {
                return before.substring(space + 1).trim();
            }
        }
        return null;
    }

    /** 小写副本上的下标对应回原文的同位置片段（原文为空或下标越界时退回小写副本） */
    private static String sliceOriginal(String original, String lower, int from) {
        if (original == null) return lower.substring(Math.min(from, lower.length()));
        int start = Math.min(from, Math.min(original.length(), lower.length()));
        return original.substring(start);
    }

    /**
     * 取开头那个<b>参数名</b>：只收 ASCII 字母/数字/下划线。
     *
     * <p>此前按"第一个空格"截断，于是 {@code "缺少参数: timestamp（秒级时间戳）"} 会把
     * {@code "timestamp（秒级时间戳）"} 当成参数名——全角括号里根本没有空格，整段都进了 key，
     * 后续按 key 找工具参数自然找不到，这条恢复链路等于静默失效。工具侧现在允许在参数名后
     * 用全角括号补"哪个 action 需要它"，这个截断规则就是那条约定的另一半。</p>
     *
     * <p>开头不是 ASCII 标识符时（如中文参数名）退回"截到第一个空格"，保持旧行为。</p>
     */
    private static String leadingIdentifier(String s) {
        String v = s == null ? "" : s.trim();
        int end = 0;
        while (end < v.length()) {
            char c = v.charAt(end);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) break;
            end++;
        }
        if (end > 0) return v.substring(0, end);
        int space = v.indexOf(' ');
        return space > 0 ? v.substring(0, space).trim() : v;
    }
}
