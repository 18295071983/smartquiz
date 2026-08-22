package com.oilquiz.app.ai.agent;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具执行前预检器。
 *
 * 在工具执行前，预知性地检查参数完整性，主动调用工具补充缺失参数：
 * - 缺位置(city/lat/lon) → 调用 location 工具获取
 * - 缺时间(time/date) → 获取系统时间
 * - 缺用户输入类参数 → 通知调用方弹出输入框
 *
 * 设计理念：动态避免错误，而非等失败后再纠错。
 * 通过分析工具名和用户已选 action，预判需要的参数，提前补充。
 */
public class ToolPreChecker {

    private static final String TAG = "ToolPreChecker";

    /** 预检回调 */
    public interface PreCheckCallback {
        /**
         * 预检完成，参数已补充。
         * @param params          补充后的参数（已被修改）
         * @param autoFilledInfo  自动补充的信息描述（如"位置:北京, 时间:2026-08-01"），可为空
         */
        void onReady(Map<String, Object> params, String autoFilledInfo);

        /**
         * 需要用户输入的参数缺失。
         * @param missing 缺失参数列表
         */
        void onNeedUserInput(List<ToolErrorRecovery.MissingParam> missing);
    }

    /**
     * 预检工具参数，自动调用工具补充缺失参数。
     *
     * @param ctx      上下文
     * @param toolName 工具名
     * @param params   当前参数（会被修改：补充缺失参数）
     * @param callback 回调
     */
    public static void preCheck(Context ctx, String toolName,
                                Map<String, Object> params, PreCheckCallback callback) {
        // 1. 获取工具需要的参数（基于工具名和已选action预判）
        List<String> requiredParams = getRequiredParams(toolName, params);

        // 2. 找出缺失的参数
        List<String> missingKeys = new ArrayList<>();
        for (String key : requiredParams) {
            if (!hasValue(params, key)) {
                missingKeys.add(key);
            }
        }

        if (missingKeys.isEmpty()) {
            // 所有参数齐全，直接执行
            callback.onReady(params, "");
            return;
        }

        Log.i(TAG, "预检: 工具=" + toolName + " 缺失参数=" + missingKeys);

        // 3. 分类：可自动获取 vs 需用户输入
        List<String> autoKeys = new ArrayList<>();
        List<ToolErrorRecovery.MissingParam> userMissing = new ArrayList<>();
        for (String key : missingKeys) {
            if (ToolErrorRecovery.canAutoFill(key)) {
                autoKeys.add(key);
            } else {
                userMissing.add(new ToolErrorRecovery.MissingParam(
                        key, getParamDescription(key), false));
            }
        }

        // 4. 如果有需要用户输入的参数，通知调用方
        if (!userMissing.isEmpty()) {
            callback.onNeedUserInput(userMissing);
            return;
        }

        // 5. 自动获取参数（调用工具）
        autoFillByTool(ctx, autoKeys, params, callback);
    }

    /**
     * 通过调用工具自动获取缺失参数。
     * 位置类：调用 location 工具获取，失败时用 ToolContextProvider 兜底。
     * 时间类：直接获取系统时间。
     */
    private static void autoFillByTool(Context ctx, List<String> autoKeys,
                                       Map<String, Object> params, PreCheckCallback callback) {
        boolean needLocation = false;
        for (String key : autoKeys) {
            if ("city".equals(key) || "lat".equals(key) || "lon".equals(key)) {
                needLocation = true;
                break;
            }
        }

        if (needLocation) {
            // 调用 location 工具获取位置
            new Thread(() -> {
                String city = null, lat = null, lon = null;

                // 方式1：调用 location 工具
                try {
                    Map<String, Object> locParams = new HashMap<>();
                    locParams.put("action", "get_current");
                    AIToolResult locResult = AIToolManager.getInstance(ctx).executeTool("location", locParams);

                    if (locResult != null && locResult.isSuccess() && locResult.getResult() instanceof Map) {
                        Map<?, ?> resultMap = (Map<?, ?>) locResult.getResult();
                        Object cityObj = resultMap.get("city");
                        Object latObj = resultMap.get("latitude");
                        Object lonObj = resultMap.get("longitude");
                        if (cityObj != null && !"未知".equals(String.valueOf(cityObj))) {
                            city = String.valueOf(cityObj);
                        }
                        if (latObj != null) lat = String.valueOf(latObj);
                        if (lonObj != null) lon = String.valueOf(lonObj);
                        Log.i(TAG, "location工具返回: city=" + city + " lat=" + lat + " lon=" + lon);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "调用location工具失败: " + e.getMessage());
                }

                // 方式2：location工具失败时，用ToolContextProvider兜底
                if (city == null || lat == null || lon == null) {
                    Log.i(TAG, "location工具未返回完整位置，尝试ToolContextProvider...");
                    final String fCity = city;
                    final String fLat = lat;
                    final String fLon = lon;
                    ToolContextProvider.getCurrentLocation(ctx, new ToolContextProvider.LocationCallback() {
                        @Override
                        public void onLocationReady(String c, double la, double lo) {
                            String city = fCity != null ? fCity : c;
                            String lat = fLat != null ? fLat : String.valueOf(la);
                            String lon = fLon != null ? fLon : String.valueOf(lo);
                            fillLocationAndComplete(city, lat, lon, autoKeys, params, callback);
                        }
                        @Override
                        public void onLocationFailed(String error) {
                            Log.w(TAG, "ToolContextProvider定位也失败: " + error + "，使用默认城市北京");
                            // 最终兜底：使用默认城市
                            String city = fCity != null ? fCity : "北京";
                            String lat = fLat != null ? fLat : "39.9042";
                            String lon = fLon != null ? fLon : "116.4074";
                            fillLocationAndComplete(city, lat, lon, autoKeys, params, callback);
                        }
                    });
                } else {
                    // location工具成功获取完整位置
                    fillLocationAndComplete(city, lat, lon, autoKeys, params, callback);
                }
            }).start();
        } else {
            // 只需要时间类参数
            String info = fillTimeParams(autoKeys, params);
            callback.onReady(params, info);
        }
    }

    /** 填充位置参数并完成预检 */
    private static void fillLocationAndComplete(String city, String lat, String lon,
                                                List<String> autoKeys,
                                                Map<String, Object> params,
                                                PreCheckCallback callback) {
        StringBuilder info = new StringBuilder();

        // 始终注入当前时间作为环境信息（即使工具未明确要求）
        if (!hasValue(params, "time") && !hasValue(params, "datetime")) {
            params.put("_env_time", ToolContextProvider.getCurrentDateTime());
        }
        if (!hasValue(params, "date")) {
            params.put("_env_date", ToolContextProvider.getCurrentDate());
        }

        for (String key : autoKeys) {
            switch (key) {
                case "city":
                    if (city != null) {
                        params.put("city", city);
                        if (info.length() > 0) info.append(", ");
                        info.append("位置:").append(city);
                    }
                    break;
                case "lat":
                    if (lat != null) {
                        try { params.put("lat", Double.parseDouble(lat)); }
                        catch (NumberFormatException e) { params.put("lat", lat); }
                    }
                    break;
                case "lon":
                    if (lon != null) {
                        try { params.put("lon", Double.parseDouble(lon)); }
                        catch (NumberFormatException e) { params.put("lon", lon); }
                    }
                    break;
                case "time":
                case "datetime":
                    String time = ToolContextProvider.getCurrentDateTime();
                    params.put(key, time);
                    if (info.length() > 0) info.append(", ");
                    info.append("时间:").append(time);
                    break;
                case "date":
                    String date = ToolContextProvider.getCurrentDate();
                    params.put("date", date);
                    if (info.length() > 0) info.append(", ");
                    info.append("日期:").append(date);
                    break;
            }
        }

        // 如果获取了位置，补充经纬度信息到环境上下文
        if (city != null && lat != null && lon != null) {
            if (!hasValue(params, "lat")) params.put("_env_lat", lat);
            if (!hasValue(params, "lon")) params.put("_env_lon", lon);
        }

        Log.i(TAG, "预检完成，自动补充: " + info);
        callback.onReady(params, info.toString());
    }

    /** 填充时间类参数，返回信息描述 */
    private static String fillTimeParams(List<String> autoKeys, Map<String, Object> params) {
        StringBuilder info = new StringBuilder();
        for (String key : autoKeys) {
            switch (key) {
                case "time":
                case "datetime":
                    String time = ToolContextProvider.getCurrentDateTime();
                    params.put(key, time);
                    if (info.length() > 0) info.append(", ");
                    info.append("时间:").append(time);
                    break;
                case "date":
                    String date = ToolContextProvider.getCurrentDate();
                    params.put("date", date);
                    if (info.length() > 0) info.append(", ");
                    info.append("日期:").append(date);
                    break;
            }
        }
        return info.toString();
    }

    /**
     * 获取工具需要的参数列表（基于工具名和已选action预判）。
     * 这就是"预知性"：根据用户选择的操作，预判需要的参数。
     */
    public static List<String> getRequiredParams(String toolName, Map<String, Object> params) {
        List<String> required = new ArrayList<>();
        String action = params.get("action") != null ? String.valueOf(params.get("action")) : null;

        switch (toolName) {
            case "ai_weather":
                // 天气工具总是需要city和坐标：空气质量、预警等需要精确坐标
                // 预先获取GPS，避免切换操作时缺少经纬度导致失败
                required.add("city");
                required.add("lat");
                required.add("lon");
                break;

            case "network_search":
                if ("search".equals(action)) {
                    required.add("query");
                } else if ("ask".equals(action)) {
                    required.add("question");
                } else if ("read_url".equals(action)) {
                    required.add("url");
                }
                break;

            case "database":
                if ("search_questions".equals(action)) {
                    required.add("keyword");
                }
                break;

            case "app_operation":
                if (action == null) break;
                switch (action) {
                    case "navigate":
                        required.add("page");
                        break;
                    case "open_settings":
                        // setting 非必填（为空打开系统设置首页）
                        break;
                    case "share":
                        required.add("text");
                        // title 非必填
                        break;
                    case "list_pages":
                    case "go_home":
                    case "go_back":
                    case "get_info":
                        // 无需必填参数
                        break;
                }
                break;

            case "location":
                // location工具本身不需要额外参数
                break;

            case "app_toolkit":
                // 聚合工具包：根据 action 预判所需参数
                if (action == null) break;
                switch (action) {
                    case "weather_current":
                    case "weather_forecast":
                    case "weather_hourly":
                    case "weather_air":
                        required.add("city");
                        required.add("lat");
                        required.add("lon");
                        break;
                    case "ocr_recognize":
                    case "image_save":
                    case "image_scale":
                    case "image_crop":
                    case "image_rotate":
                    case "image_label_recognize":
                    case "object_detect":
                        required.add("image_path");
                        break;
                    case "file_parse_text":
                    case "file_parse_csv":
                    case "file_parse_json":
                    case "file_read_lines":
                    case "file_get_type":
                        required.add("file_path");
                        break;
                    case "web_parse_html":
                    case "web_get_title":
                    case "web_get_links":
                    case "web_get_images":
                    case "web_get_text":
                        required.add("url");
                        break;
                    case "calculate":
                        required.add("expression");
                        break;
                    case "get_current_location":
                    case "locate":
                        // 自动获取，不强制用户填
                        break;
                    case "import_questions":
                        required.add("file_path");
                        break;
                    case "export_questions":
                        // 默认路径，非必须
                        break;
                    case "search_questions":
                        required.add("keyword");
                        break;
                    case "generate_questions":
                        required.add("topic");
                        break;
                    case "get_study_plan":
                        // 基于用户情况，可选
                        break;
                }
                break;

            case "file_reader":
                required.add("file_path");
                if ("read_lines".equals(action)) {
                    // startLine / endLine 可选
                } else if ("search_text".equals(action)) {
                    required.add("keyword");
                }
                break;

            case "file_analyzer":
                required.add("file_path");
                break;

            case "file_generator":
                if (action == null) break;
                switch (action) {
                    case "create":
                    case "append":
                    case "json":
                    case "config":
                    case "markdown":
                    case "report":
                    case "template":
                        required.add("file_name");
                        break;
                    case "delete":
                        required.add("file_name");
                        break;
                    case "copy":
                        required.add("source_path");
                        required.add("file_name"); // target
                        break;
                }
                break;

            case "webpage_reader":
                if (action == null) break;
                switch (action) {
                    case "read":
                    case "extract":
                    case "summarize":
                    case "follow_links":
                        required.add("url");
                        break;
                    case "read_multiple":
                        required.add("url");
                        break;
                }
                break;

            case "smart_research":
                // topic 或 query 至少一个
                if (!hasValue(params, "topic") && !hasValue(params, "query")) {
                    required.add("topic");
                }
                break;

            case "system_resource":
                if (action == null) break;
                switch (action) {
                    case "open_app":
                    case "get_app_info":
                        required.add("app_name");
                        break;
                    case "open_url":
                        required.add("url");
                        break;
                    case "send_sms":
                    case "make_call":
                        required.add("phone_number");
                        if ("send_sms".equals(action)) required.add("message");
                        break;
                }
                break;

            case "permission_manager":
                if (action == null) break;
                switch (action) {
                    case "check":
                    case "request":
                    case "request_and_wait":
                    case "get_status":
                    case "explain_permission":
                    case "can_request":
                        required.add("permission");
                        break;
                    case "check_all":
                    case "list_permissions":
                        // 不强制
                        break;
                }
                break;

            case "python_calculate":
                required.add("expression");
                break;
        }
        return required;
    }

    /** 获取参数的中文描述 */
    private static String getParamDescription(String key) {
        if (key == null) return key;
        switch (key) {
            case "city": return "城市";
            case "lat": return "纬度";
            case "lon": return "经度";
            case "query": return "搜索关键词";
            case "keyword": return "搜索关键词";
            case "question": return "问题";
            case "url": return "网页地址";
            case "text": return "文本内容";
            case "target_lang": return "目标语言";
            case "file_path": return "文件路径";
            case "image_path": return "图片路径";
            case "directory_path": return "目录路径";
            case "source_path": return "源文件路径";
            case "target_path": return "目标文件路径";
            case "output_path": return "输出文件路径";
            case "save_path": return "保存路径";
            case "file_name": return "文件名称/路径";
            case "folder": return "目录";
            case "page": return "页面名称";
            case "expression": return "数学表达式";
            case "encoding": return "文件编码";
            case "startLine": return "起始行号";
            case "endLine": return "结束行号";
            case "analysis_type": return "分析类型";
            case "action": return "操作类型";
            case "content": return "文件内容";
            case "title": return "标题";
            case "topic": return "研究主题/话题";
            case "maxResults": return "结果数量";
            case "maxDepth": return "最大深度";
            case "app_name": return "应用名称";
            case "phone_number": return "电话号码";
            case "message": return "消息内容";
            case "permission": return "权限名称";
            case "task": return "任务描述";
            case "description": return "描述信息";
            case "hint": return "提示文本";
            default: return key;
        }
    }

    /** 检查参数是否已有非空值 */
    private static boolean hasValue(Map<String, Object> params, String key) {
        if (params == null) return false;
        Object v = params.get(key);
        if (v == null) return false;
        if (v instanceof String) {
            return !((String) v).trim().isEmpty();
        }
        return true;
    }
}
