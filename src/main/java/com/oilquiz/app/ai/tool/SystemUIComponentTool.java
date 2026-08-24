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
    description = "创建UI组件：系统原生(dialog/progress/input/choice/multi_choice/date/time/snackbar/list/notification/custom动态表单/file_picker文件选择/image_picker选图/contact_picker联系人/rating评分/color取色/otp验证码/number数字/marquee跑马灯滚动文字/media_task文生图文生视频任务监控)或内置卡片(chart/info_card/table_card等,见component_type参数)。有结构信息一律用组件卡片展示,不用Markdown表格。自定义原生类型：register_type 外部注入新类型名(render.layout原生控件树),创建时也可直接传 props 内 layout 现场自定义UI。握手:create→component_id→update/close→get_result取用户操作。",
    category = "system",
    actions = {
        @Action(name = "create", description = "创建系统UI组件（component_type=组件类型，返回component_id）"),
        @Action(name = "update", description = "更新组件（所有类型通用）：progress 推进 progress/message；dialog 改标题/内容；**任何组件类型传 props 动态注入参数并刷新**——关闭旧组件按新参数重建（options/default_value/input_hint/props/items/url/html/dialog_type/max_value 等字段均生效）"),
        @Action(name = "close", description = "关闭组件"),
        @Action(name = "get_result", description = "获取组件结果（阻塞等待用户操作，wait_seconds=等待秒数）"),
        @Action(name = "register_type", description = "外部注入自定义原生组件类型：name=类型名(字母数字下划线)+description+render={card:内置卡片 或 layout:原生控件框架树,props:固定字段}+monitor可选+persist(可选布尔,true长久落盘默认,false临时仅内存)；注册后可用 create 直接创建该类型（无需写代码，纯JSON声明原生UI）"),
        @Action(name = "list_types", description = "列出全部已注册的自定义组件类型（含 mode 长久/临时）"),
        @Action(name = "remove_type", description = "删除自定义组件类型（name=类型名）"),
        @Action(name = "clear_temporary_types", description = "清除全部临时自定义组件类型（任务收尾用，长久类型保留）")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作: create/update/close/get_result", required = true),
        @Param(name = "component_type", type = "string", description = "组件类型: dialog/progress/input/choice/multi_choice/date/time/snackbar/list/notification/custom(动态自定义原生表单,props.fields定义字段)/file_picker(系统文件选择器,返回content:// URI)/image_picker(相册选图,返回URI)/contact_picker(通讯录选联系人,返回{name,phone,uri})/rating(星级评分1-5)/color(取色器,返回#RRGGBB)/otp(验证码输入,props.length设位数,默认6)/number(数字输入,props.min/max范围校验)/marquee(跑马灯滚动文字,props.text=滚动内容,speed=速度0~3,bold,size,color,repeat)/media_task(文生图/文生视频任务监控,props传task_id+type=image|video+api_url+api_key,自动轮询查询状态,完成后展示图片或视频并可播放/分享)/内置组件(chart,info_card,table_card,image_grid,link_card,list_card,alert_card,metric_card,json_viewer,steps_card,note_card,file_list,grid_card,contact_card,todo_card,quiz_card,weather_card,file_card,code_card,progress_card,html,markdown_card); web=网页卡片, image=图片卡片", required = false),
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
        @Param(name = "fields", type = "object", description = "custom动态表单字段定义数组，如[{\"key\":\"name\",\"label\":\"姓名\",\"type\":\"text\",\"required\":true},{\"key\":\"age\",\"label\":\"年龄\",\"type\":\"number\"},{\"key\":\"sex\",\"label\":\"性别\",\"type\":\"select\",\"options\":[\"男\",\"女\"]},{\"key\":\"agree\",\"label\":\"同意\",\"type\":\"switch\",\"default\":true},{\"key\":\"score\",\"label\":\"评分\",\"type\":\"slider\",\"min\":0,\"max\":10},{\"key\":\"tags\",\"label\":\"标签\",\"type\":\"checkbox\",\"options\":[\"A\",\"B\"]},{\"key\":\"birth\",\"label\":\"生日\",\"type\":\"date\"}]；字段类型:text/password/number/multiline/select/radio/checkbox/switch/slider/date，返回全部值JSON", required = false),
        @Param(name = "props", type = "object", description = "内置组件参数(component_type为内置类型时用)；update 时传 props 可动态注入新参数刷新组件（所有类型通用：重新校验/重建 UI/重启监控），字段可含 title/message/options/default_value/input_hint/dialog_type/max_value/items/url/html 等。各类型字段："
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
            + "html:{html:'<h3>标题</h3>...',title,maxHeight}; markdown_card:{content:'**加粗** 文本',title}; 任务需要用户提供信息/反馈(确认/选择/输入/点赞等)时加actions:[{label:'按钮文字',value:'回传值',action:'callback'}]或[{label,link:url}]/[{label,copy:文本}],创建后get_result取回用户点击值", required = false),
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
        return "创建UI组件：系统原生(dialog/progress/input/choice/multi_choice/date/time/snackbar/list/notification/custom动态表单/file_picker/image_picker/contact_picker/rating/color/otp/number/marquee跑马灯/media_task任务监控)或内置卡片(chart/info_card/table_card等,见component_type参数)。插件组件(ui_component_plugin注册)可用 update 传 props 动态注入参数刷新。有结构信息一律用组件卡片展示,不用Markdown表格。握手:create→component_id→update/close→get_result取用户操作。";
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
                    // 类型解析优先级：已注册类型（register_type/插件）> 原生类型 > 内置卡片
                    UIComponentTypeRegistry typeRegistry = UIComponentTypeRegistry.getInstance(context);
                    boolean isRegisteredType = typeRegistry.hasType(componentType);
                    boolean isPlugin = com.oilquiz.app.ai.tool.UIComponentPluginManager
                            .getInstance(context).hasPlugin(componentType);
                    // 组件类型未知（非原生弹窗类型/非内置卡片/非别名/非注册类型/非插件）→ 若 Agent 传了
                    // props/layout 或 render 参数带 layout 则降级为自定义渲染（layout 树直渲），否则报错
                    if (!isNativeComponentType(componentType) && !isRegisteredCardType(componentType)
                            && !isRegisteredType && !isPlugin) {
                        // 降级：props 内带 layout 或 render 参数带 layout → 按原生控件框架渲染（现场自定义 UI）
                        Object propsRaw = parameters.get("props");
                        Object renderRaw = parameters.get("render");
                        boolean hasLayout = (propsRaw != null && String.valueOf(propsRaw).contains("\"layout\""))
                                || (renderRaw != null && String.valueOf(renderRaw).contains("\"layout\""));
                        if (hasLayout) {
                            isRegisteredType = true; // 走自定义渲染（createUiComponent 遇 layout 直渲）
                        } else {
                            java.util.Map<String, Object> err = new java.util.HashMap<>();
                            err.put("status", "failed");
                            err.put("component_type", componentType);
                            err.put("error", "组件类型不存在或已被删除: " + componentType);
                            err.put("hint", "可用类型见 component_type 参数说明；自定义组件请用 ui_component(action=register_type, name=类型名, render={layout:...}) 现场注册，或 ui_component_plugin(action=create) 注册插件；若 props 内带 layout 树可直接创建自定义 UI");
                            return new AIToolResult(err, parameters);
                        }
                    }
                    // 已注册插件优先（可覆盖原生类型名/内置卡片名——Agent 自定义优先）
                    if (isPlugin) {
                        Map<String, Object> params0 = extractCreateParams(parameters);
                        params0.put("plugin_name", componentType);
                        return new AIToolResult(
                                pythonToolManager.createUiComponent(componentType, params0), parameters);
                    }
                    // 内置组件类型 → 进聊天流（withComponent → ComponentCollector → 聊天消息组件容器渲染）。
                    // 注意：路线1 降级类型（isRegisteredType=true 但非注册表内）已在下方走原生弹窗渲染 layout，
                    // 不能进聊天流（否则 props 的 layout JSON 会被当源码显示）。
                    if (isBuiltinComponentType(componentType) && !isRegisteredType) {
                        return createBuiltinChatComponent(componentType, parameters);
                    }
                    // 系统原生组件 → 弹窗展示
                    Map<String, Object> params = extractCreateParams(parameters);
                    // 自定义注册类型（register_type）：render 定义并入 props（原生渲染器从 props 读）
                    if (isRegisteredType && typeRegistry.hasType(componentType)) {
                        org.json.JSONObject typeDef = typeRegistry.getType(componentType);
                        try {
                            org.json.JSONObject pj = params.get("props") != null
                                    ? new org.json.JSONObject(String.valueOf(params.get("props"))) : new org.json.JSONObject();
                            if (typeDef != null && typeDef.has("render")) {
                                pj.put("__type_render", typeDef.optJSONObject("render").toString());
                            }
                            if (typeDef != null && typeDef.has("monitor")) {
                                pj.put("__type_monitor", typeDef.optJSONObject("monitor").toString());
                            }
                            params.put("props", pj.toString());
                        } catch (Exception ignored) {
                        }
                    }
                    // 路线1：未注册类型 + props 内直接带 layout 或 render 参数 → 现场自定义渲染
                    // （等效临时注册类型；兼容两种传法：props={layout:...} 或 render={layout:...}）
                    if (isRegisteredType && !typeRegistry.hasType(componentType)) {
                        try {
                            org.json.JSONObject pj = params.get("props") != null
                                    ? new org.json.JSONObject(String.valueOf(params.get("props"))) : new org.json.JSONObject();
                            Object layoutVal = pj.has("layout") ? pj.opt("layout") : null;
                            // 顶层 render 参数（模型常按 register_type 习惯传 render 而非 props 包装）
                            if (layoutVal == null && parameters.get("render") != null) {
                                Object rObj = parameters.get("render");
                                org.json.JSONObject renderObj = rObj instanceof String
                                        ? new org.json.JSONObject((String) rObj)
                                        : new org.json.JSONObject(new com.google.gson.Gson().toJson(rObj));
                                if (renderObj.has("layout")) {
                                    layoutVal = renderObj.opt("layout");
                                    // render.props 固定字段并入 pj
                                    org.json.JSONObject rp = renderObj.optJSONObject("props");
                                    if (rp != null) {
                                        java.util.Iterator<String> rk = rp.keys();
                                        while (rk.hasNext()) {
                                            String rk2 = rk.next();
                                            pj.put(rk2, rp.get(rk2));
                                        }
                                    }
                                }
                            }
                            if (layoutVal != null) {
                                // 把 layout 树包装为 render 定义注入，走插件渲染链路（含按钮/后端/监控）
                                org.json.JSONObject render = new org.json.JSONObject();
                                render.put("layout", layoutVal);
                                pj.put("__type_render", render.toString());
                                pj.remove("layout");
                                params.put("props", pj.toString());
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    // custom 动态表单：顶层 fields 参数并入 props.fields（原生 handler 从 props 读字段定义）
                    if ("custom".equals(componentType) && params.get("fields") != null) {
                        try {
                            org.json.JSONObject pj = new org.json.JSONObject(
                                    params.get("props") != null ? String.valueOf(params.get("props")) : "{}");
                            Object fields = params.get("fields");
                            if (fields instanceof String && !((String) fields).trim().isEmpty()) {
                                pj.put("fields", new org.json.JSONArray((String) fields));
                            }
                            params.put("props", pj.toString());
                        } catch (Exception ignored) {
                        }
                    }
                    params.remove("fields");
                    Map<String, Object> result = pythonToolManager.createUiComponent(componentType, params);
                    return new AIToolResult(result, parameters);
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
                    // 插件组件动态参数注入：update 传 props → 重新校验/重渲染/重启监控
                    Object propsObj = parameters.get("props");
                    if (propsObj != null) {
                        if (propsObj instanceof String) {
                            params.put("props", propsObj.toString());
                        } else {
                            try {
                                params.put("props", new org.json.JSONObject(
                                        new com.google.gson.Gson().toJson(propsObj)).toString());
                            } catch (Exception e) {
                                params.put("props", String.valueOf(propsObj));
                            }
                        }
                    }
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
                    // 夹取 1~120 秒：防止模型传超大值阻塞 Agent 线程数小时
                    waitSeconds = Math.max(1, Math.min(120, waitSeconds));
                    Map<String, Object> result = pythonToolManager.getUiComponentResult(componentId, waitSeconds);
                    return new AIToolResult(result, parameters);
                }
                case "register_type": {
                    // 外部注入自定义原生组件类型：name=类型名 + description + render={card|layout} + monitor可选
                    UIComponentTypeRegistry typeRegistry = UIComponentTypeRegistry.getInstance(context);
                    org.json.JSONObject typeDef = new org.json.JSONObject();
                    typeDef.put("name", str(parameters.get("name")));
                    typeDef.put("description", str(parameters.get("description")));
                    Object rObj = parameters.get("render");
                    if (rObj != null) {
                        try {
                            typeDef.put("render", rObj instanceof String
                                    ? new org.json.JSONObject((String) rObj)
                                    : new org.json.JSONObject(new com.google.gson.Gson().toJson(rObj)));
                        } catch (Exception e) {
                            typeDef.put("render", new org.json.JSONObject());
                        }
                    }
                    Object mObj = parameters.get("monitor");
                    if (mObj != null) {
                        try {
                            typeDef.put("monitor", mObj instanceof String
                                    ? new org.json.JSONObject((String) mObj)
                                    : new org.json.JSONObject(new com.google.gson.Gson().toJson(mObj)));
                        } catch (Exception e) {
                            typeDef.put("monitor", new org.json.JSONObject());
                        }
                    }
                    boolean persist = parameters.get("persist") == null
                            || Boolean.parseBoolean(String.valueOf(parameters.get("persist")));
                    java.util.List<String> problems = typeRegistry.registerType(typeDef, persist);
                    if (!problems.isEmpty()) {
                        java.util.Map<String, Object> fail = new java.util.HashMap<>();
                        fail.put("status", "failed");
                        fail.put("problems", new org.json.JSONArray(problems).toString());
                        fail.put("message", "类型注册未通过: " + String.join("; ", problems));
                        return new AIToolResult(fail, parameters);
                    }
                    java.util.Map<String, Object> ok = new java.util.HashMap<>();
                    ok.put("status", "success");
                    ok.put("name", typeDef.optString("name", ""));
                    ok.put("mode", persist ? "persistent" : "temporary");
                    ok.put("message", "自定义组件类型已注册（" + (persist ? "长久，跨重启保留" : "临时，任务结束消失")
                            + "）: " + typeDef.optString("name", "")
                            + "，现在可用 ui_component(action=create, component_type="
                            + typeDef.optString("name", "") + ", props={参数}) 创建");
                    return AIToolResult.success(ok);
                }
                case "list_types": {
                    UIComponentTypeRegistry typeRegistry = UIComponentTypeRegistry.getInstance(context);
                    java.util.List<org.json.JSONObject> list = typeRegistry.listTypes();
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (org.json.JSONObject t : list) arr.put(t);
                    java.util.Map<String, Object> r = new java.util.HashMap<>();
                    r.put("status", "success");
                    r.put("count", list.size());
                    r.put("types", arr.toString());
                    r.put("message", "已注册 " + list.size() + " 个自定义组件类型（含 mode 长久/临时）");
                    return AIToolResult.success(r);
                }
                case "clear_temporary_types": {
                    UIComponentTypeRegistry typeRegistry = UIComponentTypeRegistry.getInstance(context);
                    typeRegistry.clearTemporaryTypes();
                    java.util.Map<String, Object> r = new java.util.HashMap<>();
                    r.put("status", "success");
                    r.put("message", "全部临时类型已清除（长久类型保留）");
                    return AIToolResult.success(r);
                }
                case "remove_type": {
                    String name = str(parameters.get("name"));
                    if (name.isEmpty()) return new AIToolResult("缺少参数: name（类型名）", parameters);
                    UIComponentTypeRegistry typeRegistry = UIComponentTypeRegistry.getInstance(context);
                    boolean removed = typeRegistry.removeType(name);
                    java.util.Map<String, Object> r = new java.util.HashMap<>();
                    r.put("status", removed ? "success" : "not_found");
                    r.put("name", name);
                    r.put("message", removed ? "类型已删除: " + name : "类型不存在: " + name);
                    return AIToolResult.success(r);
                }
                default:
                    return new AIToolResult("未知操作: " + action + "（支持 create/update/close/get_result/register_type/list_types/remove_type）", parameters);
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
            "markdown_card", "web", "image"
    ));

    /** 别名映射：模型常用名 → 实际渲染组件类型（web→html 卡片、image→image_grid 卡片，进聊天流渲染而非弹窗） */
    private static final java.util.Map<String, String> COMPONENT_ALIAS = new java.util.HashMap<>();
    static {
        COMPONENT_ALIAS.put("web", "html");
        COMPONENT_ALIAS.put("image", "image_grid");
    }

    /** 是否为系统原生弹窗组件类型（nativeTypes 成员，如 dialog/progress/input 等） */
    private boolean isNativeComponentType(String type) {
        if (type == null) return false;
        String[] nativeTypes = {"dialog", "progress", "input", "choice", "multi_choice",
                "date", "time", "snackbar", "list", "notification", "toast", "voice_recorder",
                "speech_player", "custom", "file_picker", "image_picker", "contact_picker",
                "rating", "color", "otp", "number", "media_task", "marquee"};
        for (String nt : nativeTypes) {
            if (nt.equals(type)) return true;
        }
        return false;
    }

    private boolean isBuiltinComponentType(String type) {
        if (type == null) return false;
        // 任意未识别类型也可作为自定义组件进聊天流（ComponentRegistry 有兜底渲染）
        String[] nativeTypes = {"dialog", "progress", "input", "choice", "multi_choice",
                "date", "time", "snackbar", "list", "notification", "toast", "voice_recorder",
                "speech_player", "custom", "file_picker", "image_picker", "contact_picker",
                "rating", "color", "otp", "number", "media_task", "marquee"};
        for (String nt : nativeTypes) {
            if (nt.equals(type)) return false;
        }
        // Agent 注册的组件插件（ui_component_plugin create）→ 原生弹窗 + 监控
        if (com.oilquiz.app.ai.tool.UIComponentPluginManager
                .getInstance(context).hasPlugin(type)) {
            return false;
        }
        // register_type 外部注入的自定义类型 → 原生弹窗渲染（插件链路）
        if (com.oilquiz.app.ai.tool.UIComponentTypeRegistry
                .getInstance(context).hasType(type)) {
            return false;
        }
        return true;
    }

    /** 是否为项目内置卡片类型（ComponentRegistry 已注册）或别名（web/image） */
    private boolean isRegisteredCardType(String type) {
        if (type == null) return false;
        if (COMPONENT_ALIAS.containsKey(type)) return true;
        return com.oilquiz.app.ai.chat.component.ComponentRegistry.getInstance().hasType(type);
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
                    String url = urlObj.toString().trim();
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        props.put("url", url);
                    } else if (isLocalFilePath(url)) {
                        // 本地网页文件：只放行 Agent 工作区目录内文件（防注入读取任意路径），
                        // 存在则转 file:// 让 WebView 加载，否则提示（不渲染错误路径）
                        String raw = url.startsWith("file://") ? android.net.Uri.parse(url).getPath() : url;
                        String resolved = resolveWorkspacePath(raw);
                        java.io.File f = new java.io.File(resolved);
                        if (f.exists() && f.isFile() && isInsideWorkspace(f)) {
                            props.put("url", "file://" + f.getAbsolutePath());
                        } else {
                            props.put("html", "无法加载本地文件（仅支持工作区目录内的网页文件）: " + url);
                        }
                    } else {
                        props.put("html", url);
                    }
                }
                // 顶层 html/content/text 参数 → html 字段（模型常把 HTML 内容放顶层参数而非 props 内，
                // 否则 HtmlCardView 从 props.html 取不到内容，显示"html 数据解析为空"）
                if (!props.has("html") && !props.has("url")) {
                    Object htmlObj = parameters.get("html");
                    if (htmlObj == null) htmlObj = parameters.get("content");
                    if (htmlObj == null) htmlObj = parameters.get("text");
                    if (htmlObj != null && !htmlObj.toString().trim().isEmpty()) {
                        props.put("html", htmlObj.toString());
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
            // 顶层 content/text/html 兜底补进 props（模型常把内容放顶层参数而非 props 内，
            // 否则 HtmlCardView/InfoCardView 等取不到内容显示"数据解析为空"）
            if (!props.has("content") && !props.has("text") && !props.has("html")) {
                Object content = parameters.get("content");
                if (content == null) content = parameters.get("text");
                if (content == null) content = parameters.get("html");
                if (content != null) {
                    // html 组件用 html 字段；其他组件用 content 字段
                    if ("html".equals(renderType) || "web".equals(componentType)) {
                        props.put("html", content.toString());
                    } else {
                        props.put("content", content.toString());
                    }
                } else if (parameters.get("message") != null) {
                    if ("html".equals(renderType) || "web".equals(componentType)) {
                        props.put("html", parameters.get("message").toString());
                    } else {
                        props.put("content", parameters.get("message").toString());
                    }
                }
            }
            // 顶层 actions（模型常把交互按钮放顶层参数而非 props 内）并入 props，
            // 否则 interactive=false 时按钮被静默忽略且不返回 component_id（Bug3）
            if (!props.has("actions")) {
                Object topActions = parameters.get("actions");
                if (topActions != null) {
                    try {
                        if (topActions instanceof String) {
                            props.put("actions", new org.json.JSONArray((String) topActions));
                        } else {
                            props.put("actions", new org.json.JSONArray(
                                    new com.google.gson.Gson().toJson(topActions)));
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            // file_card/file_list 路径解析：模型常传工作区相对路径（如 "report.md" / "files/报告.pdf"）
            // 或缩写/幻觉路径，统一解析为真实存在的绝对路径，否则打开/分享"文件不存在"
            try {
                if ("file_card".equals(renderType) && !props.has("uri")) {
                    Object topPath = props.has("path") ? props.opt("path")
                            : props.has("file_path") ? props.opt("file_path") : null;
                    if (topPath == null) topPath = parameters.get("path");
                    if (topPath == null) topPath = parameters.get("file_path");
                    if (topPath != null && !topPath.toString().trim().isEmpty()) {
                        props.put("path", resolveWorkspacePath(topPath.toString()));
                    }
                } else if ("file_list".equals(renderType)) {
                    org.json.JSONArray files = props.optJSONArray("files");
                    if (files != null) {
                        for (int i = 0; i < files.length(); i++) {
                            org.json.JSONObject f = files.optJSONObject(i);
                            if (f == null) continue;
                            String p = f.optString("path", "");
                            if (p.isEmpty()) p = f.optString("file_path", "");
                            if (!p.isEmpty()) {
                                f.put("path", resolveWorkspacePath(p));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            // 交互支持：props 带 actions 时注册到组件注册表 + 注入 component_id
            boolean interactive = props.has("actions");
            // 无条件生成 component_id（create 响应始终返回，供后续 update/close/get_result 使用）
            String componentId = "chat_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 10000);
            if (interactive) {
                pythonToolManager.registerChatComponent(componentId);
                // 按 component_id 增量注册回调：多组件并发互不覆盖（覆盖式 setResultCallback 已废弃该用法）
                com.oilquiz.app.ai.chat.component.ComponentActions.registerComponentCallback(
                        componentId,
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
            result.put("component_id", componentId);
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
        putIfNotNull(params, "html", parameters.get("html"));
        putIfNotNull(params, "dialog_type", parameters.get("dialog_type"));
        putIfNotNull(params, "max", parameters.get("max_value"));
        putIfNotNull(params, "options", parameters.get("options"));
        putIfNotNull(params, "default_value", parameters.get("default_value"));
        putIfNotNull(params, "input_hint", parameters.get("input_hint"));
        putIfNotNull(params, "action_label", parameters.get("action_label"));
        putIfNotNull(params, "url", parameters.get("url"));
        putIfNotNull(params, "click_action", parameters.get("click_action"));
        putIfNotNull(params, "auto_close", parameters.get("auto_close"));
        // fields（custom 动态表单字段定义）：Map/List 或 JSON 字符串统一转 JSON 字符串
        Object fieldsObj = parameters.get("fields");
        if (fieldsObj != null) {
            if (fieldsObj instanceof String) {
                params.put("fields", fieldsObj.toString());
            } else {
                try {
                    params.put("fields", new com.google.gson.Gson().toJson(fieldsObj));
                } catch (Exception e) {
                    params.put("fields", String.valueOf(fieldsObj));
                }
            }
        }
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

    /**
     * 解析文件路径为真实存在的绝对路径：
     * - content:// / file:// → 原样（content:// 由 FileCardView 按 URI 处理）
     * - 绝对路径存在 → 原样
     * - 相对路径（"report.md"、"files/报告.pdf"、"tmp/xx"）→ AgentWorkspace 解析
     * - 路径不存在 → 按文件名在工作区 files/、tmp/、根目录兜底搜索（模型常传缩写/幻觉路径）
     * 解析失败返回原值（FileCardView 会提示无路径）。
     */
    private String resolveWorkspacePath(String path) {
        if (path == null || path.trim().isEmpty()) return path;
        String p = path.trim();
        // content:// 或 file:// 原样（file:// 由 FileCardView/WebView 处理）
        if (p.startsWith("content://") || p.startsWith("file://")) return p;
        try {
            com.oilquiz.app.ai.agent.online.AgentWorkspace ws =
                    com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(context);
            java.io.File f = ws.resolveExistingFile(p);
            if (f != null && f.isFile()) {
                return f.getAbsolutePath();
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolveWorkspacePath failed: " + t.getMessage());
        }
        return p;
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** 判断是否本地文件路径（file:// 前缀、/ 开头绝对路径、含 .html/.htm 等扩展名） */
    private static boolean isLocalFilePath(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.startsWith("file://") || s.startsWith("/")) return true;
        String lower = s.toLowerCase();
        return lower.endsWith(".html") || lower.endsWith(".htm")
                || lower.endsWith(".xhtml") || lower.endsWith(".mht")
                || lower.endsWith(".svg");
    }

    /** 校验文件位于 Agent 工作区目录内（web 本地加载白名单，防注入读取应用私有/系统文件） */
    private boolean isInsideWorkspace(java.io.File f) {
        try {
            com.oilquiz.app.ai.agent.online.AgentWorkspace ws =
                    com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(context);
            java.io.File root = new java.io.File(ws.getWorkspacePath());
            String filePath = f.getCanonicalPath();
            String rootPath = root.getCanonicalPath();
            return filePath.startsWith(rootPath + java.io.File.separator)
                    || filePath.equals(rootPath);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> desc = new HashMap<>();
        desc.put("action", "操作: create/update/close/get_result");
        desc.put("component_type", "组件类型: dialog/progress/input/choice/multi_choice/date/time/image/snackbar/notification/custom(动态自定义原生表单,用fields定义字段)/marquee(跑马灯滚动文字,props.text=内容,speed=0~3,bold,size,color,repeat)/media_task(文生图/文生视频任务监控:props传task_id,type=image|video,api_url,api_key;自动轮询状态,完成后展示图片或视频并可播放/分享)/内置组件(chart,info_card,table_card,image_grid,link_card,list_card,alert_card,metric_card,json_viewer,steps_card,note_card,file_list,grid_card,contact_card,todo_card,quiz_card,weather_card,file_card,code_card,progress_card,html,markdown_card); web=网页卡片(传url或html), image=图片卡片(传default_value或props.images)");
        desc.put("component_id", "组件ID（update/close/get_result用）");
        desc.put("title", "标题");
        desc.put("message", "内容/提示文本");
        desc.put("dialog_type", "对话框类型: info/confirm/warning");
        desc.put("max_value", "进度最大值(progress用)");
        desc.put("progress", "进度值(update用)");
        desc.put("options", "选项列表(choice/multi_choice用)");
        desc.put("fields", "custom动态表单字段定义数组，如[{\"key\":\"name\",\"label\":\"姓名\",\"type\":\"text\",\"required\":true},{\"key\":\"age\",\"label\":\"年龄\",\"type\":\"number\"},{\"key\":\"sex\",\"label\":\"性别\",\"type\":\"select\",\"options\":[\"男\",\"女\"]},{\"key\":\"agree\",\"label\":\"同意\",\"type\":\"switch\",\"default\":true},{\"key\":\"score\",\"label\":\"评分\",\"type\":\"slider\",\"min\":0,\"max\":10},{\"key\":\"tags\",\"label\":\"标签\",\"type\":\"checkbox\",\"options\":[\"A\",\"B\"]},{\"key\":\"birth\",\"label\":\"生日\",\"type\":\"date\"}]；字段类型:text/password/number/multiline/select/radio/checkbox/switch/slider/date；确定返回全部值JSON");
        desc.put("default_value", "默认值(input/date/time/image用)");
        desc.put("input_hint", "输入框提示(input用)");
        desc.put("action_label", "按钮文字(snackbar用)");
        desc.put("props", "内置组件参数(component_type为内置类型时用)。各类型字段：chart:{chartType:'bar|line|pie',title,categories:[分类],series:[{name,data:[数值]}]}; info_card:{title,items:[{label,value}]}; table_card:{title,headers:[列名],rows:[[值]]}; image_grid:{images:[url],columns}; link_card:{url,title,description}; list_card:{title,items:[{icon,title,description,value}]}; alert_card:{type:'success|warning|error|info',title,content}; metric_card:{title,metrics:[{label,value,color}]}; json_viewer:{title,data,maxHeight}; steps_card:{title,steps:[{status:'done|current|failed|todo',title,description}]}; note_card:{type:'note|quote|tip|summary',content,author}; file_list:{title,files:[{name,path,size,type}]}; grid_card:{title,columns,items:[{icon,label}]}; contact_card:{type:'phone|sms|email',title,value,description}; todo_card:{title,items:[{done:bool,text}]}; quiz_card:{type:'single|multiple|judge',question,options:[],answer,analysis}; weather_card:{city,temp,text,icon,humidity,windDir,windScale,forecast:[{date,text,tempMin,tempMax}]}; file_card:{name,size,type,path}; code_card:{language,code,title}; progress_card:{title,progress,description}; html:{html:'<h3>标题</h3>...',title,maxHeight}; markdown_card:{content:'**加粗** 文本',title}; 任务需要用户提供信息/反馈(确认/选择/输入/点赞等)时加actions:[{label:'按钮文字',value:'回传值',action:'callback'}]或[{label,link:url}]/[{label,copy:文本}],创建后get_result取回用户点击值");
        desc.put("wait_seconds", "等待秒数(get_result用,默认30)");
        desc.put("auto_close", "自动关闭秒数(create时指定,到点自动关闭并置result=closed;如提示类组件auto_close=5五秒后消失)");
        return desc;
    }
}
