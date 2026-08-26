package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 原生 UI 组件插件系统工具（ui_component_plugin）—— 类型安全 + 生命周期版。
 *
 * Agent 用本工具动态注册"组件插件"（任何自定义组件类型），注册后即可用
 * ui_component action=create component_type=插件名 props={...} 创建原生 UI 组件。
 *
 * 生命周期（由任务决定）：
 * - 长久插件（persist=true / 默认）：落盘 files/ui_component_plugins.json，跨重启保留，可复用；
 * - 临时插件（persist=false）：仅内存，任务结束/应用退出即消失，适合一次性任务。
 *
 * 兼容性保证（注册时自动校验，防止不兼容/空指针/参数不匹配/类型转换崩溃）：
 * - name 仅允许字母/数字/下划线；
 * - params 每个字段 type 必须 ∈ string/number/boolean/array/object；
 * - render.card 必须是项目内置卡片类型（ComponentRegistry 已注册）；
 * - monitor.tool 必须是已注册工具（AIToolManager.hasTool）。
 *
 * 复用：list 列出全部（含 mode），get 查看单个定义，create 同名覆盖更新，
 * remove 删除；create 前可用 template 拿标准模板、validate 预检定义。
 *
 * 动作：
 * - create：注册插件（name/description/params/render/monitor/persist）；
 * - template：返回标准插件模板 JSON（填参即用）；
 * - validate：校验插件定义（不落库），返回问题列表（空=通过）；
 * - get：查看单个插件定义；list：列出全部；remove：删除；clear_temporary：清除全部临时插件。
 */
@Tool(
        value = "ui_component_plugin",
        description = "原生UI组件插件系统：Agent动态创建/复用原生UI组件插件（任何自定义组件类型，类型安全，兼容校验）与原生layout模板库（可复用控件模板）。插件动作: create(注册插件)/template(取标准模板)/validate(校验定义不落库)/get(查单个)/list(列出全部)/remove(删除)/clear_temporary(清除临时插件)。layout模板动作: register_layout(注册命名layout模板)/layout_list(列出)/layout_remove(删除)/layout_clear_temporary(清除临时模板)。生命周期由任务决定: persist=true(默认)长久落盘可复用, persist=false临时仅内存任务结束即消失。兼容性由系统自动校验: 插件名仅字母数字下划线、params类型限string/number/boolean/array/object、render.card限项目内置卡片、render.layout限项目原生控件框架、render不能为空(card或layout至少其一)、monitor.tool限已注册工具。创建组件时自动按params schema校验: 缺必填明确报错、类型自动转换(number/boolean/array/object)、有default自动填充。创建后可动态注入参数刷新: ui_component(action=update, component_id=..., props={新参数}) → 重新校验/重渲染卡片/重启监控轮询。典型流程: ①template取模板或直接create注册 ②ui_component(action=create,component_type=插件名,props={参数})创建 ③任务中ui_component(action=update,component_id=...,props={新参数})动态刷新 ④ui_component(action=get_result,component_id=...)取结果。复用: list查看已有插件,直接用其name创建。layout模板: register_layout注册后,任何render.layout树内可用use=模板名引用复用; **嵌套与现场定义（实测可用）**: 插件名/类型名/模板名可直接作layout节点type嵌套({\"type\":\"插件名\"},自动展开其render.layout,节点props覆盖占位), 未注册类型节点带layout字段现场展开({\"type\":\"x\",\"layout\":{...}})。",
        actions = {
                @Action(name = "create", description = "注册组件插件（name=插件名即新组件类型, description=用途, params=参数schema JSON{字段名:{type,required,default,description,enum}}, render=渲染配置JSON{card:内置卡片类型 或 layout:项目原生控件框架树,title,props}, monitor=可选任务监控JSON{tool:已注册工具,action,poll_seconds,param_map,success_field,error_field}, persist=可选布尔,true长久落盘默认,false临时仅内存）。render.layout 示例: {\"root\":{\"type\":\"column\",\"children\":[{\"type\":\"text\",\"text\":\"标题\",\"bold\":true},{\"type\":\"input\",\"hint\":\"输入\",\"key\":\"name\"},{\"type\":\"button\",\"text\":\"提交\",\"action\":\"submit\"}]}}；控件type: 布局column/row/scroll/card/wrap(流式换行)/grid(网格,columns)/space(弹性空白)/tabs(标签页,tabs=[{label,content}]/stack(层叠,gravity定位)/accordion(折叠面板,items=[{title,content}])/carousel(图片轮播,images)；展示text/image/marquee/badge/avatar/avatar_group(头像组)/quote/code/icon；数据table(headers/rows)/steps(步骤条)/timeline(时间线)/alert(提示条,样式字段用alert_type或variant:success|warning|error|info)/stat(指标卡)/empty(空态)/notice(通知条)/progress_ring(环形进度)；图表line_chart(折线)/bar_chart(柱状)/pie_chart(饼图)/sparkline(迷你趋势)；工具qrcode(二维码)/barcode(条形码)/countdown(倒计时)/calendar(日历)/breadcrumb(面包屑)；媒体video(url或src)/audio(url或src)/html(富文本)；输入input/number/password/multiline/otp/email/tel/url/search/search_bar/tag_input(标签输入,点击可唤起软键盘)；选择select/switch/checkbox/checkbox_group/radio/radio_group/date/time/datetime/color/rating/toggle(胶囊开关)/dropdown(下拉)/stepper(步进器)/slider_range(双滑块)；交互button/link/slider/progress/spinner；文件file；装饰divider/divider_v/separator。通用属性: width/height(match/wrap/数字dp/百分比如\"50%\"在wrap/grid内), margin(数字或{top,left,bottom,right}), weight或flex(弹性比例), align(对齐), 容器spacing(子项间距)/alignItems(对齐)/justify(主轴分布)。**交互能力（实测可用）**: 带key控件值在布局内button提交时统一收集,get_result返回values={key:值};多控件内容自动可滚动;divider正常显示。自定义控件: layout顶层define={模板名:节点树}定义可复用控件,树内use={模板名,props:{参数}}引用,模板内{key}由props替换——任意组合控件自由扩展。**已注册类型嵌套**: 已注册的组件类型名(插件名/register_type类型名/layout模板名)可直接作layout节点type嵌套(如{\"type\":\"online_music_player\"}),自动展开其render.layout,节点props覆盖模板占位。后端组件: button 加 tool=后端工具名+tool_params={参数,支持{key}占位符}，点击直接调用后端工具并把结果回传"),
                @Action(name = "register_layout", description = "注册命名layout模板（name=模板名, layout=控件树JSON, description=用途, persist=可选布尔默认true）：注册后任何render.layout树内可用{\"use\":\"模板名\",\"props\":{参数}}引用复用，模板内{key}由props替换；模板名也可直接作layout节点type嵌套({\"type\":\"模板名\"},自动展开)。示例: name=stat_item, layout={\"type\":\"card\",\"title\":\"{label}\",\"children\":[{\"type\":\"text\",\"text\":\"{value}\",\"bold\":true}]} → 之后 {\"use\":\"stat_item\",\"props\":{\"label\":\"收入\",\"value\":\"¥100\"}}"),
                @Action(name = "template", description = "返回标准插件模板 JSON（填参即用，含字段设计方法注释）"),
                @Action(name = "validate", description = "校验插件定义（不落库），返回问题列表（空=通过），注册前预检用"),
                @Action(name = "get", description = "查看单个插件完整定义（name=插件名）"),
                @Action(name = "list", description = "列出全部插件（含 mode 长久/临时）"),
                @Action(name = "remove", description = "删除插件（name=插件名）"),
                @Action(name = "clear_temporary", description = "清除全部临时插件（任务收尾用）"),
                @Action(name = "layout_list", description = "列出全部layout模板（含完整定义，可直接use复用）"),
                @Action(name = "layout_remove", description = "删除layout模板（name=模板名）"),
                @Action(name = "layout_clear_temporary", description = "清除全部临时layout模板（长久模板保留）")
        },
        params = {
                @Param(name = "action", type = "string", description = "操作: create/template/validate/get/list/remove/clear_temporary/register_layout/layout_list/layout_remove/layout_clear_temporary", required = true),
                @Param(name = "name", type = "string", description = "插件名或layout模板名（create/get/remove/register_layout/layout_remove用），仅字母数字下划线", required = false),
                @Param(name = "description", type = "string", description = "用途说明（create/register_layout用，给模型看）", required = false),
                @Param(name = "params", type = "object", description = "参数 schema JSON（create 用）：{字段名: {type: string|number|boolean|array|object, required: 可选布尔, default: 可选, description: 可选, enum: 可选数组}}", required = false),
                @Param(name = "render", type = "object", description = "渲染配置 JSON（create 用，可选）：{card: 内置卡片类型如 info_card/progress_card（必须是项目内置类型）, title: 标题, props: 卡片固定字段, layout: 项目原生控件框架树(JSON 声明原生 UI，任意组合控件)}。layout 示例: {\"root\":{\"type\":\"column\",\"children\":[{\"type\":\"text\",\"text\":\"任务\",\"bold\":true},{\"type\":\"input\",\"hint\":\"任务ID\",\"key\":\"task_id\"},{\"type\":\"progress\",\"progress\":30,\"max\":100},{\"type\":\"row\",\"children\":[{\"type\":\"button\",\"text\":\"刷新\",\"action\":\"refresh\"},{\"type\":\"button\",\"text\":\"关闭\",\"action\":\"close\"}]}]}}；控件 type: 布局 column/row/scroll/card(圆角卡片容器,title)/wrap(流式换行)/grid(网格,columns=N)/space(弹性空白)/tabs(标签页,tabs=[{label,content}] 点击切换)；展示 text(bold,size,color,align)/marquee(跑马灯,speed 0~3)/image(url)/badge(徽章,text,color)/avatar(头像,url,size)/quote(引用,text,author)/code(代码块,code,language)/icon(图标,size,color)；媒体 video(url或src,title,autoPlay,loop,speed)/audio(url或src,title,artist)/html(html富文本,maxHeight)；输入 input(hint,key)/number/password/multiline/otp(length)/email/tel/url/search；选择 select(options,key)/switch(checked,key)/checkbox/radio(options,value)/date/time/datetime(日期时间)/color(value)/rating(value 1~5)；交互 button(action回传或tool后端调用)/link(text,url或action)/slider(key,min,max)/progress(进度)/spinner(加载圈)；文件 file(key,label)；装饰 divider/separator。通用属性: width/height(match/wrap/数字dp/百分比如\"50%\"在wrap/grid内), margin(数字或{top,left,bottom,right}), weight或flex(弹性比例,row/column内用weight,wrap/grid内用flex), align(start/center/end), 容器 spacing(子项间距dp)/alignItems(start/center/end 子项对齐)/justify(flex_start/flex_end/center/space_between/space_around,wrap用)；文本支持 {key} 占位符从 props 替换", required = false),
                @Param(name = "layout", type = "object", description = "layout模板定义（register_layout用）：控件树JSON，如{\"type\":\"card\",\"title\":\"{label}\",\"children\":[...]}，模板内{key}由use的props替换", required = false),
                @Param(name = "monitor", type = "object", description = "任务监控配置 JSON（create 用，可选）：{tool: 已注册工具名, action: 工具action参数, poll_seconds: 轮询间隔秒数(2~30,默认5), param_map: {组件props字段: 查询参数名}, success_field: 查询结果含该字段即完成(展示该文件路径), error_field: 失败原因字段(可选)}", required = false),
                @Param(name = "persist", type = "boolean", description = "生命周期（create/register_layout用）：true=长久落盘跨重启保留可复用(默认)；false=临时仅内存任务结束即消失", required = false)
        })
public class UIComponentPluginTool implements AITool {

    private final Context context;

    public UIComponentPluginTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "ui_component_plugin";
    }

    @Override
    public String getDescription() {
        return "原生UI组件插件系统：Agent动态创建/复用原生UI组件插件（类型安全，兼容校验）与原生layout模板库。插件动作: create/template/validate/get/list/remove/clear_temporary；layout模板动作: register_layout/layout_list/layout_remove/layout_clear_temporary。生命周期由任务决定: persist=true长久落盘可复用(默认), false临时仅内存。兼容性自动校验: 插件名仅字母数字下划线、params类型限string/number/boolean/array/object、render.card限内置卡片、render.layout限项目原生控件框架(布局column/row/scroll/card/wrap/grid/space/tabs/stack/accordion/carousel；展示text/image/marquee/badge/avatar/avatar_group/quote/code/icon；数据table/steps/timeline/alert/stat/empty/notice/progress_ring；图表line_chart/bar_chart/pie_chart/sparkline；工具qrcode/barcode/countdown/calendar/breadcrumb；媒体video/audio/html；输入input/number/password/multiline/otp/email/tel/url/search/search_bar/tag_input；选择select/switch/checkbox/checkbox_group/radio/radio_group/date/time/datetime/color/rating/toggle/dropdown/stepper/slider_range；交互button/link/slider/progress/spinner；文件file；装饰divider/divider_v/separator；通用width/height/margin/weight/flex/align/spacing/alignItems/justify)、monitor.tool限已注册工具。**嵌套与复用（实测可用）**: 注册的插件名/类型名可直接作其他layout树节点type嵌套({\"type\":\"插件名\"},自动展开其render.layout,节点props覆盖占位)；layout模板注册后任意layout内用{\"use\":\"模板名\",\"props\":{参数}}引用,模板内{key}由props替换；未注册类型节点带layout字段现场展开。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> m = new HashMap<>();
        m.put("action", "操作: create/template/validate/get/list/remove/clear_temporary/register_layout/layout_list/layout_remove/layout_clear_temporary");
        m.put("name", "插件名或layout模板名（create/get/remove/register_layout/layout_remove 用），仅字母数字下划线");
        m.put("description", "插件或模板用途说明（create/register_layout 用）");
        m.put("params", "参数 schema JSON（create 用）：{字段名: {type,required,default,description,enum}}");
        m.put("render", "渲染配置 JSON（create 用，可选）：{card:内置卡片, title, props, layout:项目原生控件框架树(JSON声明原生UI)}");
        m.put("layout", "layout模板定义（register_layout 用）：控件树JSON，模板内{key}由use的props替换");
        m.put("monitor", "任务监控配置 JSON（create 用，可选）：{tool,action,poll_seconds,param_map,success_field,error_field}");
        m.put("persist", "生命周期（create/register_layout 用）：true=长久落盘(默认), false=临时仅内存");
        return m;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = parameters.get("action") != null
                    ? String.valueOf(parameters.get("action")).toLowerCase() : "list";
            UIComponentPluginManager mgr = UIComponentPluginManager.getInstance(context);
            switch (action) {
                case "create": {
                    JSONObject plugin = new JSONObject();
                    plugin.put("name", str(parameters.get("name")));
                    plugin.put("description", str(parameters.get("description")));
                    Object paramsObj = parameters.get("params");
                    if (paramsObj != null) plugin.put("params", toJsonObject(paramsObj));
                    Object renderObj = parameters.get("render");
                    if (renderObj != null) plugin.put("render", toJsonObject(renderObj));
                    Object monitorObj = parameters.get("monitor");
                    if (monitorObj != null) plugin.put("monitor", toJsonObject(monitorObj));
                    boolean persist = parameters.get("persist") == null
                            || Boolean.parseBoolean(String.valueOf(parameters.get("persist")));
                    List<String> problems = mgr.createPlugin(plugin, persist);
                    if (!problems.isEmpty()) {
                        Map<String, Object> fail = new HashMap<>();
                        fail.put("status", "failed");
                        fail.put("problems", new org.json.JSONArray(problems).toString());
                        fail.put("message", "插件校验未通过: " + String.join("; ", problems));
                        fail.put("hint", "修复后重试；可先用 action=template 取标准模板、action=validate 预检");
                        return new AIToolResult(fail, parameters);
                    }
                    Map<String, Object> ok = new HashMap<>();
                    ok.put("status", "success");
                    ok.put("name", plugin.optString("name", ""));
                    ok.put("mode", persist ? "persistent" : "temporary");
                    ok.put("message", "组件插件已注册（" + (persist ? "长久，跨重启保留可复用" : "临时，任务结束即消失")
                            + "），现在可用 ui_component(action=create, component_type="
                            + plugin.optString("name", "") + ", props={参数}) 创建该原生组件"
                            + (plugin.has("monitor") ? "，创建后自动监控任务状态" : ""));
                    return AIToolResult.success(ok);
                }
                case "template": {
                    Map<String, Object> r = new HashMap<>();
                    r.put("status", "success");
                    r.put("template", UIComponentPluginManager.template().toString());
                    r.put("message", "标准插件模板（填参即用）。字段说明: name=插件名(字母数字下划线), description=用途, params=参数schema{字段:{type,required,default,description,enum}}, render.card=内置卡片类型, monitor.tool=已注册工具");
                    return AIToolResult.success(r);
                }
                case "validate": {
                    JSONObject plugin = new JSONObject();
                    plugin.put("name", str(parameters.get("name")));
                    plugin.put("description", str(parameters.get("description")));
                    Object paramsObj = parameters.get("params");
                    if (paramsObj != null) plugin.put("params", toJsonObject(paramsObj));
                    Object renderObj = parameters.get("render");
                    if (renderObj != null) plugin.put("render", toJsonObject(renderObj));
                    Object monitorObj = parameters.get("monitor");
                    if (monitorObj != null) plugin.put("monitor", toJsonObject(monitorObj));
                    List<String> problems = mgr.validatePlugin(plugin);
                    Map<String, Object> r = new HashMap<>();
                    r.put("status", problems.isEmpty() ? "valid" : "invalid");
                    r.put("problems", new org.json.JSONArray(problems).toString());
                    r.put("message", problems.isEmpty() ? "✅ 插件定义校验通过（未落库，可 action=create 注册）"
                            : "❌ 插件定义存在问题: " + String.join("; ", problems));
                    return AIToolResult.success(r);
                }
                case "get": {
                    String name = str(parameters.get("name"));
                    JSONObject p = mgr.getPlugin(name);
                    Map<String, Object> r = new HashMap<>();
                    if (p == null) {
                        r.put("status", "not_found");
                        r.put("message", "插件不存在: " + name + "（可用 list 查看全部）");
                    } else {
                        r.put("status", "success");
                        r.put("name", name);
                        r.put("mode", p.optString("mode", "persistent"));
                        r.put("plugin", p.toString());
                    }
                    return AIToolResult.success(r);
                }
                case "remove": {
                    String name = str(parameters.get("name"));
                    if (name.isEmpty()) return AIToolResult.fail("缺少参数: name（插件名）");
                    boolean removed = mgr.removePlugin(name);
                    Map<String, Object> r = new HashMap<>();
                    r.put("status", removed ? "success" : "not_found");
                    r.put("name", name);
                    r.put("message", removed ? "插件已删除: " + name : "插件不存在: " + name);
                    return AIToolResult.success(r);
                }
                case "clear_temporary": {
                    mgr.clearTemporaryPlugins();
                    Map<String, Object> r = new HashMap<>();
                    r.put("status", "success");
                    r.put("message", "全部临时插件已清除（长久插件保留）");
                    return AIToolResult.success(r);
                }
                // ========== 原生 layout 模板库（插件式注册可复用控件模板） ==========
                case "register_layout": {
                    com.oilquiz.app.ai.python.LayoutTemplateRegistry ltr =
                            com.oilquiz.app.ai.python.LayoutTemplateRegistry.getInstance(context);
                    String name = str(parameters.get("name"));
                    Object layoutObj = parameters.get("layout");
                    JSONObject layout = toJsonObject(layoutObj == null ? parameters.get("render") : layoutObj);
                    String desc = str(parameters.get("description"));
                    boolean persist = parameters.get("persist") == null
                            || Boolean.parseBoolean(String.valueOf(parameters.get("persist")));
                    List<String> problems = ltr.registerTemplate(name, layout, desc, persist);
                    if (!problems.isEmpty()) {
                        Map<String, Object> fail = new HashMap<>();
                        fail.put("status", "failed");
                        fail.put("problems", new org.json.JSONArray(problems).toString());
                        fail.put("message", "layout 模板校验未通过: " + String.join("; ", problems));
                        return new AIToolResult(fail, parameters);
                    }
                    Map<String, Object> ok = new HashMap<>();
                    ok.put("status", "success");
                    ok.put("name", name);
                    ok.put("mode", persist ? "persistent" : "temporary");
                    ok.put("message", "layout 模板已注册（" + (persist ? "长久，跨重启保留" : "临时，任务结束即消失")
                            + "）：任何 render.layout 树内可用 {\"use\":\"" + name + "\",\"props\":{...}} 引用，"
                            + "模板内 {key} 由 props 替换。示例：{\"use\":\"" + name + "\",\"props\":{\"label\":\"A\",\"value\":\"1\"}}");
                    return AIToolResult.success(ok);
                }
                case "layout_list": {
                    com.oilquiz.app.ai.python.LayoutTemplateRegistry ltr =
                            com.oilquiz.app.ai.python.LayoutTemplateRegistry.getInstance(context);
                    org.json.JSONArray arr = ltr.listTemplates();
                    Map<String, Object> r = new HashMap<>();
                    r.put("status", "success");
                    r.put("count", arr.length());
                    r.put("templates", arr.toString());
                    r.put("message", "已注册 " + arr.length() + " 个 layout 模板（可用 use=模板名 引用，局部 define 优先）");
                    return AIToolResult.success(r);
                }
                case "layout_remove": {
                    com.oilquiz.app.ai.python.LayoutTemplateRegistry ltr =
                            com.oilquiz.app.ai.python.LayoutTemplateRegistry.getInstance(context);
                    String name = str(parameters.get("name"));
                    if (name.isEmpty()) return AIToolResult.fail("缺少参数: name（模板名）");
                    boolean removed = ltr.removeTemplate(name);
                    Map<String, Object> r = new HashMap<>();
                    r.put("status", removed ? "success" : "not_found");
                    r.put("name", name);
                    r.put("message", removed ? "layout 模板已删除: " + name : "模板不存在: " + name);
                    return AIToolResult.success(r);
                }
                case "layout_clear_temporary": {
                    com.oilquiz.app.ai.python.LayoutTemplateRegistry ltr =
                            com.oilquiz.app.ai.python.LayoutTemplateRegistry.getInstance(context);
                    int n = ltr.clearTemporary();
                    Map<String, Object> r = new HashMap<>();
                    r.put("status", "success");
                    r.put("cleared", n);
                    r.put("message", "已清除 " + n + " 个临时 layout 模板（长久模板保留）");
                    return AIToolResult.success(r);
                }
                case "list":
                default: {
                    List<JSONObject> list = mgr.listPlugins();
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (JSONObject p : list) arr.put(p);
                    Map<String, Object> r = new HashMap<>();
                    r.put("status", "success");
                    r.put("count", list.size());
                    r.put("plugins", arr.toString());
                    r.put("message", "已注册 " + list.size() + " 个组件插件（含 mode 标记，可直接用 name 复用）");
                    return AIToolResult.success(r);
                }
            }
        } catch (Exception e) {
            android.util.Log.e("UIComponentPluginTool", "插件工具失败: " + e.getMessage(), e);
            return AIToolResult.fail("插件工具失败: " + e.getMessage());
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private JSONObject toJsonObject(Object o) {
        try {
            if (o instanceof String) {
                String s = ((String) o).trim();
                if (s.isEmpty()) return new JSONObject();
                return new JSONObject(s);
            }
            return new JSONObject(new com.google.gson.Gson().toJson(o));
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
