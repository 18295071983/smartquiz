package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.python.PythonToolManager;

import java.util.HashMap;
import java.util.Map;

/**
 * 系统 UI 组件工具 —— Agent 直接填参数调用系统原生 UI 组件（无需写 Python）。
 *
 * 组件"握手"协议：create → 拿 component_id → update/close → get_result（阻塞取用户操作结果）。
 *
 * 组件类型（component_type）：
 *  - dialog：系统对话框（dialog_type: info/confirm/warning；结果 positive/negative/cancelled）
 *  - progress：水平进度条（max_value；update_component 推进，到 100 自动关闭）
 *  - input：文本输入（input_hint/default_value；结果=用户输入文本）
 *  - choice：单选（options；结果=选中项文本）
 *  - multi_choice：多选（options；结果=选中项 JSON 数组）
 *  - date：日期选择（default_value=YYYY-MM-DD；结果=YYYY-MM-DD）
 *  - time：时间选择（default_value=HH:mm；结果=HH:mm）
 *  - image：图片预览（default_value=本地路径或 http(s) url）
 *  - snackbar：底部提示条（message + action_label 可选；结果 action/closed）
 *  - 内置 UI 组件（chart/info_card/table_card/alert_card/metric_card/steps_card/list_card/
 *    note_card/todo_card/progress_card/json_viewer/code_card/link_card/grid_card/contact_card/
 *    file_card/file_list/image_grid/quiz_card/weather_card/html）：props 传参，渲染成卡片弹窗
 */
@Tool(
    value = "ui_component",
    description = "系统UI组件工具：Agent直接填参数调用系统原生UI组件（对话框/进度条/输入框/单选/多选/日期/时间/底部提示条）"
        + "和内置UI组件库（信息卡/表格/图表/列表/步骤/待办/代码/JSON/HTML等21种卡片，渲染到聊天流展示，props带actions可交互）。"
        + "web=网页卡片(等价html，传url或html)、image=图片卡片(等价image_grid，传default_value或props.images)。"
        + "组件握手：create→拿component_id→update/close→get_result阻塞取用户操作结果。"
        + "示例：create_component对话框→get_result等用户点确定/取消；create_component进度条→update推进；"
        + "create_component输入框→get_result得用户输入。"
        + "用途：需要用户确认/输入/选择、耗时任务进度反馈、展示结构化信息时使用。",
    category = "system",
    actions = {
        @Action(name = "create", description = "创建系统UI组件（component_type=组件类型，返回component_id）"),
        @Action(name = "update", description = "更新组件（进度条推进progress/message；对话框改标题/内容）"),
        @Action(name = "close", description = "关闭组件"),
        @Action(name = "get_result", description = "获取组件结果（阻塞等待用户操作，wait_seconds=等待秒数）")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作: create/update/close/get_result", required = true),
        @Param(name = "component_type", type = "string", description = "组件类型: dialog/progress/input/choice/multi_choice/date/time/snackbar/list/notification/内置组件(chart,info_card,table_card,image_grid,link_card,list_card,alert_card,metric_card,json_viewer,steps_card,note_card,file_list,grid_card,contact_card,todo_card,quiz_card,weather_card,file_card,code_card,progress_card,html); web=网页卡片, image=图片卡片", required = false),
        @Param(name = "component_id", type = "string", description = "组件ID（update/close/get_result用）", required = false),
        @Param(name = "title", type = "string", description = "标题", required = false),
        @Param(name = "message", type = "string", description = "内容/提示文本", required = false),
        @Param(name = "dialog_type", type = "string", description = "对话框类型: info/confirm/warning", required = false),
        @Param(name = "max_value", type = "int", description = "进度最大值(progress用,默认100)", required = false),
        @Param(name = "progress", type = "int", description = "进度值(update用)", required = false),
        @Param(name = "options", type = "list", description = "选项列表(choice/multi_choice用)", required = false),
        @Param(name = "default_value", type = "string", description = "默认值(input/date/time/image用)", required = false),
        @Param(name = "input_hint", type = "string", description = "输入框提示(input用)", required = false),
        @Param(name = "action_label", type = "string", description = "按钮文字(snackbar用)", required = false),
        @Param(name = "props", type = "object", description = "内置组件参数(component_type为内置类型时用)。各类型字段："
            + "chart:{chartType:'bar|line|pie',title,categories:[分类],series:[{name,data:[数值]}]}; "
            + "info_card:{title,items:[{label,value}]}; table_card:{title,headers:[列名],rows:[[值]]}; "
            + "image_grid:{images:[url],columns}; link_card:{url,title,description}; "
            + "list_card:{title,items:[{icon,title,description,value}]}; alert_card:{type:'success|warning|error|info',title,content}; "
            + "metric_card:{title,metrics:[{label,value,color}]}; json_viewer:{title,data,maxHeight}; "
            + "steps_card:{title,steps:[{status:'done|current|failed|todo',title,description}]}; "
            + "note_card:{type:'note|quote|tip|summary',content,author}; file_list:{title,files:[{name,path,size,type}]}; "
            + "grid_card:{title,columns,items:[{icon,label}]}; contact_card:{type:'phone|sms|email',title,value,description}; "
            + "todo_card:{title,items:[{done:bool,text}]}; quiz_card:{type:'single|multiple|judge',question,options:[],answer,analysis}; "
            + "weather_card:{city,temp,text,icon,humidity,windDir,windScale,forecast:[{date,text,tempMin,tempMax}]}; "
            + "file_card:{name,size,type,path}; code_card:{language,code,title}; progress_card:{title,progress,description}; "
            + "html:{html:'<h3>标题</h3>...',title,maxHeight}; 交互组件加 actions:[{label,value,action:'callback'}]", required = false),
        @Param(name = "wait_seconds", type = "int", description = "等待秒数(get_result用,默认30)", required = false),
        @Param(name = "auto_close", type = "int", description = "自动关闭秒数(create时指定,到点自动关闭并置result=closed;如提示类组件auto_close=5五秒后消失)", required = false)
    }
)
public class SystemUIComponentTool implements AITool {
    private static final String TAG = "SystemUIComponentTool";
    private final Context context;
    private final PythonToolManager pythonToolManager;

    public SystemUIComponentTool(Context context) {
        this.context = context.getApplicationContext();
        this.pythonToolManager = PythonToolManager.getInstance(context);
    }

    @Override
    public String getName() {
        return "ui_component";
    }

    @Override
    public String getDescription() {
        return "系统UI组件工具：创建系统对话框/进度条/输入框/选择器/图片预览/提示条，或展示内置UI组件卡片。"
            + "组件握手：create→component_id→update/close→get_result取用户操作结果。"
            + "需要用户确认/输入/选择、任务进度反馈、展示结构化信息时使用。";
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Object actionObj = parameters.get("action");
            if (actionObj == null) {
                return new AIToolResult("缺少参数: action (create/update/close/get_result)", parameters);
            }
            String action = actionObj.toString();

            switch (action) {
                case "create": {
                    String componentType = str(parameters.get("component_type"));
                    if (componentType == null || componentType.isEmpty()) {
                        return new AIToolResult("缺少参数: component_type", parameters);
                    }
                    // 内置组件类型 → 进聊天流（withComponent → ComponentCollector → 聊天消息组件容器渲染）
                    if (isBuiltinComponentType(componentType)) {
                        return createBuiltinChatComponent(componentType, parameters);
                    }
                    // 系统原生组件 → 弹窗展示
                    Map<String, Object> params = extractCreateParams(parameters);
                    Map<String, Object> result = pythonToolManager.createUiComponent(componentType, params);                    return new AIToolResult(result, parameters);
                }
                case "update": {
                    String componentId = str(parameters.get("component_id"));
                    if (componentId == null || componentId.isEmpty()) {
                        return new AIToolResult("缺少参数: component_id", parameters);
                    }
                    Map<String, Object> params = new HashMap<>();
                    putIfNotNull(params, "progress", parameters.get("progress"));
                    putIfNotNull(params, "message", parameters.get("message"));
                    putIfNotNull(params, "title", parameters.get("title"));
                    putIfNotNull(params, "max", parameters.get("max_value"));
                    Map<String, Object> result = pythonToolManager.updateUiComponent(componentId, params);
                    return new AIToolResult(result, parameters);
                }
                case "close": {
                    String componentId = str(parameters.get("component_id"));
                    if (componentId == null || componentId.isEmpty()) {
                        return new AIToolResult("缺少参数: component_id", parameters);
                    }
                    Map<String, Object> result = pythonToolManager.closeUiComponent(componentId);
                    return new AIToolResult(result, parameters);
                }
                case "get_result": {
                    String componentId = str(parameters.get("component_id"));
                    if (componentId == null || componentId.isEmpty()) {
                        return new AIToolResult("缺少参数: component_id", parameters);
                    }
                    int waitSeconds = 30;
                    if (parameters.get("wait_seconds") instanceof Number) {
                        waitSeconds = ((Number) parameters.get("wait_seconds")).intValue();
                    } else if (parameters.get("wait_seconds") != null) {
                        try { waitSeconds = Integer.parseInt(parameters.get("wait_seconds").toString()); } catch (Exception ignored) {}
                    }
                    Map<String, Object> result = pythonToolManager.getUiComponentResult(componentId, waitSeconds);
                    return new AIToolResult(result, parameters);
                }
                default:
                    return new AIToolResult("未知操作: " + action + "（支持 create/update/close/get_result）", parameters);
            }
        } catch (Exception e) {
            Log.e(TAG, "UI组件工具执行失败: " + e.getMessage(), e);
            return new AIToolResult("UI组件工具执行失败: " + e.getMessage(), parameters);
        }
    }

    /** 内置 UI 组件类型清单（进聊天流展示，非弹窗） */
    private static final java.util.Set<String> BUILTIN_COMPONENT_TYPES = new java.util.HashSet<>(java.util.Arrays.asList(
            "chart", "info_card", "table_card", "image_grid", "link_card", "list_card",
            "alert_card", "metric_card", "json_viewer", "steps_card", "note_card",
            "file_list", "grid_card", "contact_card", "todo_card", "quiz_card",
            "weather_card", "file_card", "code_card", "progress_card", "html", "tool_call",
            "web", "image"
    ));

    /** 别名映射：模型常用名 → 实际渲染组件类型（web→html 卡片、image→image_grid 卡片，进聊天流渲染而非弹窗） */
    private static final java.util.Map<String, String> COMPONENT_ALIAS = new java.util.HashMap<>();
    static {
        COMPONENT_ALIAS.put("web", "html");
        COMPONENT_ALIAS.put("image", "image_grid");
    }

    private boolean isBuiltinComponentType(String type) {
        if (type == null) return false;
        // 任意未识别类型也可作为自定义组件进聊天流（ComponentRegistry 有兜底渲染）
        String[] nativeTypes = {"dialog", "progress", "input", "choice", "multi_choice",
                "date", "time", "snackbar", "list", "notification", "toast"};
        for (String nt : nativeTypes) {
            if (nt.equals(type)) return false;
        }
        return true;
    }

    /**
     * 内置组件 → 聊天流：构造 ComponentData，通过 withComponent 让引擎
     * 附加到聊天消息（ComponentCollector → ChatAdapter 组件容器渲染）。
     * 若 props 带 actions（交互），注册到组件注册表并注入 component_id，
     * 用户点击按钮（action=callback）→ 回调写 result → Agent get_result 取回。
     */
    private AIToolResult createBuiltinChatComponent(String componentType, Map<String, Object> parameters) {
        try {
            // 别名映射：模型传 web/image → 实际渲染组件类型（html / image_grid，进聊天流）
            String renderType = COMPONENT_ALIAS.containsKey(componentType)
                    ? COMPONENT_ALIAS.get(componentType) : componentType;
            org.json.JSONObject props = new org.json.JSONObject();
            Object propsObj = parameters.get("props");
            if (propsObj != null) {
                if (propsObj instanceof String) {
                    try {
                        props = new org.json.JSONObject((String) propsObj);
                    } catch (Exception e) {
                        props = new org.json.JSONObject();
                    }
                } else {
                    try {
                        props = new org.json.JSONObject(new com.google.gson.Gson().toJson(propsObj));
                    } catch (Exception e) {
                        props = new org.json.JSONObject();
                    }
                }
            }
            // web 组件适配：url 参数 → html 组件的 url 字段（WebView 卡片直接加载网页）；
            // html 内容 → html 组件的 html 字段（富文本渲染）
            if ("web".equals(componentType)) {
                Object urlObj = parameters.get("url");
                if (urlObj != null && !props.has("html") && !props.has("url")) {
                    String url = urlObj.toString();
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        props.put("url", url);
                    } else {
                        props.put("html", url);
                    }
                }
                if (!props.has("title")) {
                    Object title = parameters.get("title");
                    if (title != null) props.put("title", title.toString());
                }
            }
            // image 组件适配：default_value(单图路径/url) → image_grid 的 images 数组
            if ("image".equals(componentType) && !props.has("images")) {
                Object dv = parameters.get("default_value");
                if (dv != null && !dv.toString().isEmpty()) {
                    try {
                        org.json.JSONArray images = new org.json.JSONArray();
                        images.put(dv.toString());
                        props.put("images", images);
                    } catch (Exception ignored) {
                    }
                }
            }
            // 顶层 title/message 兜底补进 props
            if (!props.has("title")) {
                Object title = parameters.get("title");
                if (title != null) props.put("title", title.toString());
            }
            if (!props.has("content") && !props.has("text")) {
                Object message = parameters.get("message");
                if (message != null) props.put("content", message.toString());
            }

            // 交互支持：props 带 actions 时注册到组件注册表 + 注入 component_id
            boolean interactive = props.has("actions");
            String componentId = null;
            if (interactive) {
                componentId = "chat_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 10000);
                pythonToolManager.registerChatComponent(componentId);
                // 全局回调：聊天流组件按钮点击 → 写入 result
                com.oilquiz.app.ai.chat.component.ComponentActions.setResultCallback(
                        (cid, value) -> {
                            if (cid != null && value != null) {
                                pythonToolManager.notifyChatComponentResult(cid, value);
                            }
                        });
                // 给每个 action 注入 component_id（action=callback 时用）
                org.json.JSONArray actions = props.optJSONArray("actions");
                if (actions != null) {
                    for (int i = 0; i < actions.length(); i++) {
                        org.json.JSONObject a = actions.optJSONObject(i);
                        if (a != null) {
                            a.put("component_id", componentId);
                            if (!a.has("action")) a.put("action", "callback");
                        }
                    }
                }
            }

            com.oilquiz.app.ai.chat.component.ComponentData data =
                    com.oilquiz.app.ai.chat.component.ComponentData.of(renderType, props);
            // 返回带组件的结果：AIToolManager 会自动 collect 进聊天流
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("component_type", componentType);
            result.put("message", "组件已加入聊天流: " + componentType
                    + (interactive ? "（交互组件，component_id=" + componentId + "）" : ""));
            result.put("props", props.toString());
            if (componentId != null) {
                result.put("component_id", componentId);
            }
            return new AIToolResult(result, parameters).withComponent(data);
        } catch (Exception e) {
            Log.e(TAG, "创建聊天流组件失败: " + e.getMessage(), e);
            return new AIToolResult("创建聊天流组件失败: " + e.getMessage(), parameters);
        }
    }

    /** 提取 create 参数（排除 action/component_type 控制字段） */
    private Map<String, Object> extractCreateParams(Map<String, Object> parameters) {
        Map<String, Object> params = new HashMap<>();
        putIfNotNull(params, "title", parameters.get("title"));
        putIfNotNull(params, "message", parameters.get("message"));
        putIfNotNull(params, "dialog_type", parameters.get("dialog_type"));
        putIfNotNull(params, "max", parameters.get("max_value"));
        putIfNotNull(params, "options", parameters.get("options"));
        putIfNotNull(params, "default_value", parameters.get("default_value"));
        putIfNotNull(params, "input_hint", parameters.get("input_hint"));
        putIfNotNull(params, "action_label", parameters.get("action_label"));
        putIfNotNull(params, "url", parameters.get("url"));
        putIfNotNull(params, "click_action", parameters.get("click_action"));
        putIfNotNull(params, "auto_close", parameters.get("auto_close"));
        // props 可能是 Map 或 JSON 字符串：统一转成 JSON 字符串（handler 按 JSON 解析）
        Object propsObj = parameters.get("props");
        if (propsObj != null) {
            if (propsObj instanceof String) {
                params.put("props", propsObj.toString());
            } else {
                try {
                    org.json.JSONObject jo = new org.json.JSONObject(
                            new com.google.gson.Gson().toJson(propsObj));
                    params.put("props", jo.toString());
                } catch (Exception e) {
                    params.put("props", String.valueOf(propsObj));
                }
            }
        }
        // items 可能是 List 或 JSON 字符串：统一转成 JSON 字符串
        Object itemsObj = parameters.get("items");
        if (itemsObj != null) {
            if (itemsObj instanceof String) {
                params.put("items", itemsObj.toString());
            } else {
                try {
                    org.json.JSONArray ja = new org.json.JSONArray(
                            new com.google.gson.Gson().toJson(itemsObj));
                    params.put("items", ja.toString());
                } catch (Exception e) {
                    params.put("items", String.valueOf(itemsObj));
                }
            }
        }
        return params;
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> desc = new HashMap<>();
        desc.put("action", "操作: create/update/close/get_result");
        desc.put("component_type", "组件类型: dialog/progress/input/choice/multi_choice/date/time/image/snackbar/notification/内置组件(chart,info_card,table_card,image_grid,link_card,list_card,alert_card,metric_card,json_viewer,steps_card,note_card,file_list,grid_card,contact_card,todo_card,quiz_card,weather_card,file_card,code_card,progress_card,html); web=网页卡片(传url或html), image=图片卡片(传default_value或props.images)");
        desc.put("component_id", "组件ID（update/close/get_result用）");
        desc.put("title", "标题");
        desc.put("message", "内容/提示文本");
        desc.put("dialog_type", "对话框类型: info/confirm/warning");
        desc.put("max_value", "进度最大值(progress用)");
        desc.put("progress", "进度值(update用)");
        desc.put("options", "选项列表(choice/multi_choice用)");
        desc.put("default_value", "默认值(input/date/time/image用)");
        desc.put("input_hint", "输入框提示(input用)");
        desc.put("action_label", "按钮文字(snackbar用)");
        desc.put("props", "内置组件参数(component_type为内置类型时用)。各类型字段：chart:{chartType:'bar|line|pie',title,categories:[分类],series:[{name,data:[数值]}]}; info_card:{title,items:[{label,value}]}; table_card:{title,headers:[列名],rows:[[值]]}; image_grid:{images:[url],columns}; link_card:{url,title,description}; list_card:{title,items:[{icon,title,description,value}]}; alert_card:{type:'success|warning|error|info',title,content}; metric_card:{title,metrics:[{label,value,color}]}; json_viewer:{title,data,maxHeight}; steps_card:{title,steps:[{status:'done|current|failed|todo',title,description}]}; note_card:{type:'note|quote|tip|summary',content,author}; file_list:{title,files:[{name,path,size,type}]}; grid_card:{title,columns,items:[{icon,label}]}; contact_card:{type:'phone|sms|email',title,value,description}; todo_card:{title,items:[{done:bool,text}]}; quiz_card:{type:'single|multiple|judge',question,options:[],answer,analysis}; weather_card:{city,temp,text,icon,humidity,windDir,windScale,forecast:[{date,text,tempMin,tempMax}]}; file_card:{name,size,type,path}; code_card:{language,code,title}; progress_card:{title,progress,description}; html:{html:'<h3>标题</h3>...',title,maxHeight}; 交互组件加 actions:[{label,value,action:'callback'}]");
        desc.put("wait_seconds", "等待秒数(get_result用,默认30)");
        desc.put("auto_close", "自动关闭秒数(create时指定,到点自动关闭并置result=closed;如提示类组件auto_close=5五秒后消失)");
        return desc;
    }
}
