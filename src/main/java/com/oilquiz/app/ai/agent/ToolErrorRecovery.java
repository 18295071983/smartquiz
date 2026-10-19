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
        String genericParam = extractGenericMissingParam(lower);
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

    /** 从通用错误格式中提取缺失参数名 */
    private static String extractGenericMissingParam(String lowerError) {
        // "parameter xxx is required"
        int idx = lowerError.indexOf("parameter ");
        if (idx >= 0) {
            String rest = lowerError.substring(idx + 10);
            int end = rest.indexOf(' ');
            if (end > 0) {
                return rest.substring(0, end).trim();
            }
            return rest.trim();
        }
        // "缺少参数: xxx" / "缺少参数 xxx"
        idx = lowerError.indexOf("缺少参数");
        if (idx >= 0) {
            String rest = lowerError.substring(idx + 4);
            rest = rest.replace(":", "").replace("：", "").trim();
            if (!rest.isEmpty()) {
                int end = rest.indexOf(' ');
                if (end > 0) {
                    return rest.substring(0, end).trim();
                }
                return rest;
            }
        }
        // "xxx cannot be null" / "xxx is null"
        idx = lowerError.indexOf(" cannot be null");
        if (idx > 0) {
            String before = lowerError.substring(0, idx);
            int space = before.lastIndexOf(' ');
            if (space >= 0 && space < before.length() - 1) {
                return before.substring(space + 1).trim();
            }
            return before.trim();
        }
        idx = lowerError.indexOf(" is null");
        if (idx > 0) {
            String before = lowerError.substring(0, idx);
            int space = before.lastIndexOf(' ');
            if (space >= 0 && space < before.length() - 1) {
                return before.substring(space + 1).trim();
            }
        }
        return null;
    }
}
