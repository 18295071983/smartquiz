package com.oilquiz.app.ai.python;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputLayout;

import com.oilquiz.app.ai.chat.component.CanvasControls;
import com.oilquiz.app.ai.chat.component.ComponentColors;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;
/**
 * 项目级原生 UI 框架：把 JSON 声明的原生控件树（layout）编译渲染成真实 Android View。
 *
 * Agent 在插件 render.layout 中用 JSON 描述任意原生 UI：
 * <pre>
 * {"root": {"type": "column", "children": [
 *   {"type": "text", "text": "任务监控", "bold": true, "size": 18},
 *   {"type": "input", "hint": "任务ID", "key": "task_id", "value": "abc123"},
 *   {"type": "progress", "progress": 30, "max": 100},
 *   {"type": "row", "children": [
 *     {"type": "button", "text": "刷新", "action": "refresh"},
 *     {"type": "button", "text": "关闭", "action": "close"}
 *   ]}
 * ]}}
 * </pre>
 *
 * 控件类型（type）：
 * - 布局容器: column(纵向)/row(横向)/scroll(可滚动)/card(卡片,圆角边框包裹children,可加title)/
 *         wrap(流式换行,子项横向排列超出自动换行)/grid(网格,columns列数)/space(弹性空白占位)/
 *         tabs(标签页,tabs=[{label,content}] 点击切换)/stack(层叠,FrameLayout,子项gravity定位)/
 *         accordion(折叠面板,items=[{title,content}])/carousel(图片轮播,images=[url])
 * - 通用布局属性(任意节点): width/height(match/wrap/数字dp/百分比如"50%"), margin(数字或{top,left,bottom,right}),
 *         weight 或 flex(弹性比例), align(子项对齐 start/center/end,容器内用 alignItems)
 * - 容器属性: spacing(子项间距dp), alignItems(start/center/end 子项对齐), justify(wrap/grid 主轴对齐)
 * - 展示: text(文本, bold/size/color/align)/marquee(跑马灯,speed 0~3)/image(url 或 path, width/height)/
 *         badge(徽章,text/color)/avatar(头像,url,size 圆形)/avatar_group(头像组,urls,size,overlap)/
 *         quote(引用块,text,author)/code(代码块,code,language)/icon(图标,emoji 或字体图标,size,color)
 * - 数据展示: table(headers/rows,striped)/steps(steps=[{title,status}]步骤条)/timeline(items=[{title,time,description}]时间线)/
 *         alert(type=success|warning|error|info,title,content提示条)/stat|metric(label,value,unit,sub指标卡)/
 *         empty(icon,title,description空态)/notice(icon,text,action通知条)/progress_ring(progress环形进度)
 * - 图表: line_chart(categories/series折线)/bar_chart(categories/series柱状)/pie_chart(data=[{label,value}]饼图)/
 *         sparkline(data迷你趋势)
 * - 工具: qrcode(content,size二维码)/barcode(content条形码)/countdown(seconds倒计时)/
 *         calendar(日历,value选中)/breadcrumb(items面包屑)
 * - 媒体: video(url 或 src, title/autoPlay/loop/speed, ExoPlayer 原生播放)/audio(url 或 src, title/artist, 播放条)/
 *         html(html 富文本内容, WebView 渲染, title/maxHeight)
 * - 输入: input(单行,key/hint/value)/number(数字)/password(密码)/multiline(多行)/otp(验证码,length)/
 *         email(邮箱)/tel(电话)/url(网址)/search(搜索,IME搜索键)/search_bar(搜索条,圆角+图标)/
 *         tag_input(标签输入,tags)
 * - 选择: select(下拉,key/options/value)/switch(开关,key/checked)/checkbox(复选,key/checked)/
 *         checkbox_group(复选组,options,key收集数组)/radio(单选组,key/options/value)/
 *         radio_group(单选组,{label,value}选项)/date(日期,key/value)/time(时间,key/value)/
 *         datetime(日期+时间,key/value)/color(取色器,key/value)/rating(星级评分,key/value 1~5)/
 *         toggle(胶囊分段开关,options/value)/dropdown(下拉菜单,options)/stepper(步进器,min/max/step)/
 *         slider_range(双滑块范围,min/max/low/high)
 * - 交互: button(text,action回传 或 tool+tool_params调后端)/link(超链接,text,url 或 action)/
 *         slider(滑块,key/min/max/value,show_value)/progress(progress/max)/spinner(加载圈,size)
 * - 文件: file(文件路径,key,label,value 预填或手填)
 * - 装饰: divider/separator(横分割线)/divider_v(竖分割线)
 *
 * 值与回传：控件带 key 的值（input/number/password/multiline/otp/email/tel/url/search/file/select/switch/
 * checkbox/radio/slider/date/time/datetime/color/rating/progress）在按钮点击时收集为 JSON；
 * button 的 action 作为点击结果回传；link 的 action 同 button 回传。
 * 数据绑定（2026-11-15）：任意节点属性可写 {"$":"data.a.b"}，渲染时从 props.data 取值；
 * text 的 text 与 stat/metric 的 value 还可写 {"=":"a * b"}，由 {@link LayoutFormula} 有界求值
 * （只认数字、+ - * /、括号、data 路径、min/max/round/abs；其它一律显示 "—"）。输入控件变化时
 * 写回 props.data 并只刷新同一棵树内登记过的文本/公式节点（见 {@link LayoutBindingScope}）。
 * 图片：image/avatar/avatar_group/carousel 的 url/src 支持 data:image/...;base64,...（单张
 * payload ≤ 512 KiB base64、单边 ≤ 4096，超限/解码失败显示浅底"图片无法显示"占位）。
 * 后端组件：button 支持 {action, tool, tool_params} —— tool=后端工具名（如 dashscope_media），
 * 点击直接调用后端工具，tool_params 中 {key} 占位符替换为控件值，结果经组件 result 回传。
 * 尺寸自定义：任意节点支持 width/height 属性（"match"/"fill"=铺满, "wrap"=自适应, 数字=dp, "50%"=百分比仅wrap/grid内），
 * 如 {"type":"text","text":"标题","width":200,"height":40}。
 * 所有 text 支持 {key} 占位符，从 props 替换。
 * 自定义控件模板（Agent 自由扩展）：layout 顶层 define={模板名: 节点树} 定义可复用控件，
 * 树内 {"use": "模板名", "props": {...}} 引用，模板内 {key} 由 use 的 props 替换，
 * 实现任意组合控件的定义与复用（无需新增原生类型）。
 */
public class NativeLayoutRenderer {

    private static final String TAG = "NativeLayoutRenderer";
    /** space 控件 Tag 标记（addChildren 识别保留弹性） */
    private static final String SPACE_TAG = "__space__";
    /** EditText 软键盘防抖 Tag key */
    private static final int TAG_KEY_SOFT_INPUT_TIME = 0x5A17F001;
    private static final String[] LAYOUT_TYPES = {"column", "row", "scroll", "card", "wrap", "grid", "space", "tabs", "stack", "accordion", "carousel"};
    private static final String[] CONTROL_TYPES = {
            // 展示
            "text", "image", "marquee", "badge", "avatar", "avatar_group", "quote", "code", "icon",
            // 媒体（ExoPlayer 原生播放）
            "video", "audio",
            // HTML 富文本（WebView）
            "html",
            // 输入
            "input", "number", "password", "multiline", "otp", "email", "tel", "url", "search", "search_bar",
            "tag_input",
            // 选择
            "select", "switch", "checkbox", "checkbox_group", "radio", "radio_group",
            "date", "time", "datetime", "color", "rating",
            "toggle", "dropdown", "stepper", "slider_range",
            // 交互/进度
            "button", "link", "slider", "progress", "spinner", "progress_ring",
            // 文件
            "file",
            // 数据展示
            "table", "steps", "timeline", "alert", "stat", "metric", "empty", "notice",
            // 图表（Canvas 自绘）
            "line_chart", "bar_chart", "pie_chart", "sparkline",
            // 工具
            "qrcode", "barcode", "countdown", "calendar", "breadcrumb",
            // 装饰
            "divider", "divider_v", "separator"
    };

    /** 校验 layout 定义，返回错误描述；null=通过。
     *  支持自定义控件模板：layout 顶层 define={名字: 节点} 定义模板，
     *  树内 {"use": "名字", "props": {...}} 引用（模板内 {key} 由 props 替换）。
     *  已注册组件类型名（register_type / 插件 / layout 模板）也可作为节点 type 嵌套，
     *  渲染时自动展开其 render.layout —— 传入 registeredNames 放行这些类型名。 */
    public static String validate(Object layoutObj) {
        return validate(layoutObj, null);
    }

    /** 校验 layout 定义；registeredNames 非空时，命中集合的类型名视为合法（嵌套注册组件类型）。 */
    public static String validate(Object layoutObj, java.util.Set<String> registeredNames) {
        try {
            JSONObject root = layoutObj instanceof JSONObject
                    ? (JSONObject) layoutObj : new JSONObject(String.valueOf(layoutObj));
            JSONObject defines = root.optJSONObject("define");
            if (defines != null) {
                java.util.Iterator<String> dk = defines.keys();
                while (dk.hasNext()) {
                    String dname = dk.next();
                    JSONObject dnode = defines.optJSONObject(dname);
                    if (dnode == null) return "define." + dname + " 必须是对象";
                    String de = validateNode(dnode, 0, registeredNames);
                    if (de != null) return "define." + dname + " 非法: " + de;
                }
            }
            if (!root.has("root")) {
                JSONObject r = new JSONObject();
                r.put("type", "column");
                r.put("children", new JSONArray().put(root));
                root = r;
            }
            JSONObject node = root.optJSONObject("root");
            // 与 render() 入口保持同一口径：render 在拿不到 root 时会退回把 root 本身当节点渲染
            // （见 render() 的 `if (node == null) node = root;`）。校验器此前在这里直接判
            // "root 必须是对象"，比渲染更严 —— 导致裸节点布局（{"type":"column","children":[...]}）
            // 能正常渲染却被 register_type / 画布创建校验误拒（真机实测复现）。
            if (node == null) node = root;
            // 与 render 入口同款兼容：layout 同时带 root+children 时，root 空壳无 children
            // 则用 layout.children 兜底（校验对象与渲染对象保持一致）
            if (!node.has("children")) {
                JSONArray layoutChildren = root.optJSONArray("children");
                if (layoutChildren != null && layoutChildren.length() > 0) {
                    try { node.put("children", layoutChildren); } catch (org.json.JSONException ignored) {}
                }
            }
            return validateNode(node, 0, registeredNames);
        } catch (Exception e) {
            return "layout 解析失败: " + e.getMessage();
        }
    }

    private static String validateNode(JSONObject node, int depth, java.util.Set<String> registeredNames) {
        if (depth > 8) return "布局嵌套过深（>8 层）";
        String type = node.optString("type", "column");
        // use 引用模板：类型校验延迟到渲染（define 模板已在入口校验）
        if (node.has("use")) return null;
        boolean isLayout = contains(LAYOUT_TYPES, type);
        boolean isControl = contains(CONTROL_TYPES, type);
        // 已注册组件类型名（register_type/插件/layout 模板）作为节点 type 嵌套：放行（渲染时展开）
        if (registeredNames != null && registeredNames.contains(type)) {
            // 递归校验其 children（若带），防止嵌套过度
            JSONArray kc = node.optJSONArray("children");
            if (kc != null) {
                for (int i = 0; i < kc.length(); i++) {
                    JSONObject c = kc.optJSONObject(i);
                    if (c == null) return "children[" + i + "] 不是对象";
                    String e = validateNode(c, depth + 1, registeredNames);
                    if (e != null) return e;
                }
            }
            return null;
        }
        // 未注册类型但节点自带 layout（现场定义，渲染时展开）：放行并递归校验其 layout
        if (!isLayout && !isControl && (node.has("layout") || node.has("render"))) {
            Object selfLayout = node.has("layout") ? node.opt("layout") : null;
            if (selfLayout == null && node.has("render")) {
                Object rObj = node.opt("render");
                if (rObj instanceof JSONObject) {
                    selfLayout = ((JSONObject) rObj).opt("layout");
                } else if (rObj != null) {
                    try {
                        selfLayout = new JSONObject(String.valueOf(rObj)).opt("layout");
                    } catch (Exception ignored) {
                    }
                }
            }
            if (selfLayout != null) {
                // 递归校验现场 layout（含其自身嵌套）
                JSONObject lo;
                try {
                    lo = selfLayout instanceof JSONObject
                            ? (JSONObject) selfLayout : new JSONObject(String.valueOf(selfLayout));
                } catch (Exception je) {
                    return "节点 " + type + " 的 layout 解析失败: " + je.getMessage();
                }
                JSONObject loRoot = lo.optJSONObject("root");
                JSONObject target = loRoot != null ? loRoot : lo;
                String e = validateNode(target, depth + 1, registeredNames);
                if (e != null) return "节点 " + type + " 的 layout 非法: " + e;
                return null;
            }
        }
        if (!isLayout && !isControl) {
            return "未知控件类型: " + type
                    + "（可选: 布局 column/row/scroll/card/wrap/grid/space/tabs/stack/accordion/carousel；展示 text/marquee/image/badge/avatar/avatar_group/quote/code/icon；媒体 video/audio/html；输入 input/number/password/multiline/otp/email/tel/url/search/search_bar/tag_input；选择 select/switch/checkbox/checkbox_group/radio/radio_group/date/time/datetime/color/rating/toggle/dropdown/stepper/slider_range；交互 button/link/slider/progress/spinner/progress_ring；数据 table/steps/timeline/alert/stat|metric/empty/notice；图表 line_chart/bar_chart/pie_chart/sparkline；工具 qrcode/barcode/countdown/calendar/breadcrumb；文件 file；装饰 divider/divider_v/separator；自定义模板 use=名字；已注册组件类型名可直接作 type 嵌套）";
        }
        JSONArray children = node.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject c = children.optJSONObject(i);
                if (c == null) return "children[" + i + "] 不是对象";
                String e = validateNode(c, depth + 1, registeredNames);
                if (e != null) return e;
            }
        }
        return null;
    }

    private static boolean contains(String[] arr, String v) {
        for (String s : arr) if (s.equals(v)) return true;
        return false;
    }

    /** 渲染 layout 树为真实 View；viewRefs 收集带 key 的控件引用/值引用。返回根 View。
     *  支持 define 控件模板 + use 引用（自定义控件自由扩展）。 */
    public static View render(Context context, Object layoutObj, JSONObject props,
                              Map<String, Object> viewRefs) {
        try {
            String layoutText = String.valueOf(layoutObj);
            // data:image base64 载荷不计入 64 KiB 结构预算（单张上限由 DataImageDecoder 把关），
            // 否则一张 512 KiB 的图永远过不了这道闸；结构本身仍然受 64 KiB 限制。
            if (!withinLayoutBudget(layoutText)) {
                return placeholder(context, "内容过多，已省略");
            }
            NODE_BUDGET.get()[0] = 0;
            JSONObject root = layoutObj instanceof JSONObject
                    ? (JSONObject) layoutObj : new JSONObject(String.valueOf(layoutObj));
            JSONObject localDefines = root.optJSONObject("define");
            // 合并全局 layout 模板库（Agent 注册的持久化模板）与局部 define（局部优先）
            JSONObject defines = LayoutTemplateRegistry.getInstance(context)
                    .mergeDefines(localDefines);
            if (!root.has("root")) {
                JSONObject r = new JSONObject();
                r.put("type", "column");
                r.put("children", new JSONArray().put(root));
                root = r;
            }
            JSONObject node = root.optJSONObject("root");
            if (node == null) node = root;
            // 兼容助手生成的结构瑕疵：layout 同时带 root 与 children 时
            // （如 {"root":{"spacing":14,"type":"column"},"children":[...] }），
            // root 是空壳（无 children）而 layout.children 才是真内容 → 把 children 兜底进 root，
            // 否则渲染器 root 优先会丢弃真内容渲染成空白画布。
            if (!node.has("children")) {
                JSONArray layoutChildren = root.optJSONArray("children");
                if (layoutChildren != null && layoutChildren.length() > 0) {
                    try { node.put("children", layoutChildren); } catch (org.json.JSONException ignored) {}
                }
            }
            // UI-06 顶层 style 参数（ui_component style=... 顶层传法）→ 合并进根节点：
            // 让"动态画布/临时layout"的最外层也吃得到 style（background/radius/border/padding 作用于画布根）
            // 节点已有同名属性优先，style 仅作兜底（与 buildNode 节点级 style 口径一致）
            JSONObject topStyle = props != null ? props.optJSONObject("style") : null;
            if (topStyle != null) {
                java.util.Iterator<String> sk = topStyle.keys();
                while (sk.hasNext()) {
                    String k = sk.next();
                    if (!node.has(k)) {
                        try { node.put(k, topStyle.get(k)); } catch (org.json.JSONException ignored) {}
                    }
                }
            }
            // 绑定作用域：props.data 里的值供 {"$":"data.a"} / {"=":"a+1"} 读取；
            // 输入控件变化时只刷新本棵树内登记的文本，不重建整张卡。
            LayoutBindingScope bindingScope = LayoutBindingScope.create(props);
            viewRefs.put(LayoutBindingScope.REF_KEY, bindingScope);
            View rootView = buildNode(context, node, props, viewRefs, 0, defines);
            bindingScope.attach(viewRefs);
            return rootView;
        } catch (Exception e) {
            android.util.Log.w(TAG, "layout 渲染失败: " + e.getMessage());
            TextView tv = new TextView(context);
            tv.setText("⚠ 原生布局渲染失败: " + e.getMessage());
            tv.setTextSize(12);
            return tv;
        }
    }

    private static View buildNode(Context context, JSONObject node, JSONObject props,
                                  Map<String, Object> viewRefs, int depth) {
        return buildNode(context, node, props, viewRefs, depth, null);
    }

    private static View buildNode(Context context, JSONObject node, JSONObject props,
                                  Map<String, Object> viewRefs, int depth,
                                  JSONObject defines) {
        // 渲染预算（2026-11-14，抄 dsh-Go 的 limits 思路）：节点数/深度超限只降级，不报错/不崩
        int[] budget = NODE_BUDGET.get();
        budget[0] += 1;
        if (budget[0] > MAX_NODES) return placeholder(context, "内容过多，已省略");
        if (depth > MAX_DEPTH) return placeholder(context, "层级过深，已省略");
        // UI-06 统一 style 对象：节点级 style={padding,radius,background,color,fontSize,...} 展开为顶层属性
        // （顶层同名属性优先，style 仅作兜底；展开后其余渲染逻辑按顶层属性读取）
        {
            JSONObject style = node.optJSONObject("style");
            if (style != null) {
                java.util.Iterator<String> sk = style.keys();
                while (sk.hasNext()) {
                    String k = sk.next();
                    if (!node.has(k)) {
                        try { node.put(k, style.get(k)); } catch (org.json.JSONException ignored) {}
                    }
                }
                node.remove("style");
            }
        }
        // 绑定/公式：{"$":"data.a"} 换成 props.data 的值；{"=":"a+1"} 只保留在
        // text / stat|metric.value 的取值位置（等渲染到那里再登记，输入变化可重算）。
        LayoutBindingScope bindingScope = LayoutBindingScope.from(viewRefs);
        if (bindingScope != null) {
            node = bindingScope.resolveNode(node);
        }
        // 自定义控件模板引用：use=模板名，模板内 {key} 由本节点 props 替换
        if (node.has("use")) {
            if (defines == null || !defines.has(node.optString("use", ""))) {
                TextView tv = new TextView(context);
                tv.setText("⚠ 未知控件模板: " + node.optString("use", ""));
                tv.setTextSize(12);
                return tv;
            }
            JSONObject tmpl = defines.optJSONObject(node.optString("use", ""));
            if (tmpl == null) {
                TextView tv = new TextView(context);
                tv.setText("⚠ 控件模板非法: " + node.optString("use", ""));
                tv.setTextSize(12);
                return tv;
            }
            // 合并 use 节点 props 为渲染 props（覆盖模板内同名 {key} 占位）
            JSONObject merged = new JSONObject();
            try {
                if (props != null) {
                    java.util.Iterator<String> ik = props.keys();
                    while (ik.hasNext()) {
                        String k = ik.next();
                        merged.put(k, props.get(k));
                    }
                }
                JSONObject useProps = node.optJSONObject("props");
                if (useProps != null) {
                    java.util.Iterator<String> uk = useProps.keys();
                    while (uk.hasNext()) {
                        String k = uk.next();
                        merged.put(k, useProps.get(k));
                    }
                }
                // 节点顶层额外属性（text/value 等直接传参）
                java.util.Iterator<String> nk = node.keys();
                while (nk.hasNext()) {
                    String k = nk.next();
                    if ("use".equals(k) || "props".equals(k) || "width".equals(k)
                            || "height".equals(k) || "margin".equals(k)
                            || "key".equals(k) || "weight".equals(k)
                            || "flex".equals(k) || "align".equals(k)) continue;
                    merged.put(k, node.get(k));
                }
            } catch (org.json.JSONException ignored) {
            }
            // 模板可能是 {"root": {...}} 完整结构或直接节点树：取 root（与渲染入口/注册类型展开一致）
            JSONObject tmplRoot = tmpl.optJSONObject("root");
            JSONObject tmplTarget = tmplRoot != null ? tmplRoot : tmpl;
            return buildNode(context, tmplTarget, merged, viewRefs, depth, defines);
        }
        String type = node.optString("type", "column");
        int density = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                context.getResources().getDisplayMetrics());
        switch (type) {
            case "column": {
                LinearLayout ll = new LinearLayout(context);
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setPadding(dp(8, density), dp(4, density), dp(8, density), dp(4, density));
                // UI-06 style 对象：padding/radius/background 覆盖默认
                applyContainerStyle(ll, node, density);
                // alignItems: start/center/end 控制子项水平对齐（column）
                applyContainerAlignment(ll, node, LinearLayout.VERTICAL);
                addChildren(context, ll, node, props, viewRefs, depth, defines);
                return ll;
            }
            case "row": {
                LinearLayout ll = new LinearLayout(context);
                ll.setOrientation(LinearLayout.HORIZONTAL);
                ll.setGravity(Gravity.CENTER_VERTICAL);
                ll.setPadding(dp(8, density), dp(4, density), dp(8, density), dp(4, density));
                // UI-06 style 对象：padding/radius/background 覆盖默认
                applyContainerStyle(ll, node, density);
                // alignItems: start/center/end 控制子项垂直对齐（row）
                applyContainerAlignment(ll, node, LinearLayout.HORIZONTAL);
                addChildren(context, ll, node, props, viewRefs, depth, defines);
                return ll;
            }
            case "scroll": {
                android.widget.ScrollView sv = new android.widget.ScrollView(context);
                // 焦点/触摸注入：ScrollView 不抢焦点（否则内部 EditText 点击不聚焦、不弹输入法），
                // DOWN 放行给子控件命中（EditText 聚焦依赖先收到 DOWN）
                sv.setFocusable(false);
                sv.setFocusableInTouchMode(false);
                sv.setOnTouchListener((v, event) -> {
                    if (event != null && event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }
                    return false; // 不消费：保留 ScrollView 默认滚动
                });
                // 支持多个 children：用纵向 LinearLayout 包裹（此前只渲染第一个子节点，其余丢失）
                JSONArray kids = node.optJSONArray("children");
                LinearLayout scrollContent = new LinearLayout(context);
                scrollContent.setOrientation(LinearLayout.VERTICAL);
                if (kids != null) {
                    for (int i = 0; i < kids.length(); i++) {
                        JSONObject child = kids.optJSONObject(i);
                        if (child == null) continue;
                        View v = buildNode(context, child, props, viewRefs, depth + 1, defines);
                        // divider 系列保留 buildNode 固定尺寸（divider 高 dp(1)），
                        // 避免 scroll 容器内被 WRAP_CONTENT 覆盖后塌陷/拉伸。
                        String childType = child.optString("type", "");
                        if (("divider".equals(childType) || "separator".equals(childType)
                                || "divider_v".equals(childType)) && v.getLayoutParams() != null) {
                            scrollContent.addView(v, new LinearLayout.LayoutParams(
                                    v.getLayoutParams().width, v.getLayoutParams().height));
                        } else {
                            scrollContent.addView(v, new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT));
                        }
                    }
                }
                sv.addView(scrollContent, new android.widget.ScrollView.LayoutParams(
                        android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                        android.widget.ScrollView.LayoutParams.WRAP_CONTENT));
                return sv;
            }
            case "card": {
                // 卡片容器：框架标准卡（MaterialCardView），可选标题，包裹 children。
                // children 需要 LinearLayout 的 LayoutParams（addChildren 口径），所以卡内套一层
                // 纵向 LinearLayout；样式（背景/圆角/描边）落在卡片本体上，padding 落在卡片上。
                MaterialCardView cardL = CanvasControls.card(context);
                // UI-06 style 对象：padding/radius/background 覆盖默认（applyContainerStyle 对
                // MaterialCardView 走 cardBackgroundColor/cardCornerRadius/stroke，不替换背景 drawable）
                applyContainerStyle(cardL, node, density);
                if (!node.has("padding")) {
                    // MaterialCardView 的内容内边距要用 setContentPadding：setPadding 只改卡片自身
                    // 的绘制区，内部 FrameLayout 子 view 仍贴边（真机实测：卡标题压在描边上）。
                    cardL.setContentPadding(Tokens.spaceLg(context), Tokens.spaceMd(context),
                            Tokens.spaceLg(context), Tokens.spaceMd(context));
                }
                LinearLayout cardColumn = new LinearLayout(context);
                cardColumn.setOrientation(LinearLayout.VERTICAL);
                String cTitle = interpolate(node.optString("title", ""), props);
                if (!cTitle.isEmpty()) {
                    TextView ctv = new TextView(context);
                    ctv.setText(cTitle);
                    Tokens.applyText(ctv, Tokens.TEXT_BODY_LG, Tokens.Weight.SEMIBOLD,
                            ComponentColors.textPrimary(context));
                    ctv.setPadding(0, 0, 0, dp(6, density));
                    cardColumn.addView(ctv);
                }
                addChildren(context, cardColumn, node, props, viewRefs, depth, defines);
                cardL.addView(cardColumn, new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
                return cardL;
            }
            case "wrap": {
                // 流式换行布局（Flexbox）：子项横向排列，超出自动换行
                com.google.android.flexbox.FlexboxLayout fl = new com.google.android.flexbox.FlexboxLayout(context);
                fl.setFlexDirection(com.google.android.flexbox.FlexDirection.ROW);
                fl.setFlexWrap(com.google.android.flexbox.FlexWrap.WRAP);
                fl.setAlignItems(com.google.android.flexbox.AlignItems.FLEX_START);
                String justify = node.optString("justify", "flex_start");
                int jc = justifyToConstant(justify);
                fl.setJustifyContent(jc);
                fl.setPadding(dp(8, density), dp(4, density), dp(8, density), dp(4, density));
                addFlexChildren(context, fl, node, props, viewRefs, depth, defines);
                return fl;
            }
            case "grid": {
                // 网格布局（Flexbox + 百分比宽度）：columns 列数，子项等宽
                com.google.android.flexbox.FlexboxLayout gl = new com.google.android.flexbox.FlexboxLayout(context);
                gl.setFlexDirection(com.google.android.flexbox.FlexDirection.ROW);
                gl.setFlexWrap(com.google.android.flexbox.FlexWrap.WRAP);
                gl.setAlignItems(com.google.android.flexbox.AlignItems.FLEX_START);
                gl.setPadding(dp(8, density), dp(4, density), dp(8, density), dp(4, density));
                int cols = Math.max(1, node.optInt("columns", 2));
                int gap = node.has("spacing") ? node.optInt("spacing", 0) : 0;
                JSONArray gKids = node.optJSONArray("children");
                if (gKids != null) {
                    for (int i = 0; i < gKids.length(); i++) {
                        JSONObject gc = gKids.optJSONObject(i);
                        if (gc == null) continue;
                        View gv = buildNode(context, gc, props, viewRefs, depth + 1, defines);
                        float basis = (100f / cols);
                        com.google.android.flexbox.FlexboxLayout.LayoutParams flp =
                                new com.google.android.flexbox.FlexboxLayout.LayoutParams(
                                        com.google.android.flexbox.FlexboxLayout.LayoutParams.MATCH_PARENT,
                                        com.google.android.flexbox.FlexboxLayout.LayoutParams.WRAP_CONTENT);
                        flp.setFlexBasisPercent(basis / 100f);
                        if (gap > 0 && i > 0) {
                            flp.setMarginStart(dp(gap, density));
                        }
                        gl.addView(gv, flp);
                    }
                }
                return gl;
            }
            case "space": {
                // 弹性空白：占据剩余空间（配合 weight/flex 使用）。
                // 标记 Tag=SPACE_TAG，addChildren 识别后保留 weight=1 弹性
                View sv = new View(context);
                sv.setTag(SPACE_TAG);
                sv.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
                return sv;
            }
            case "tabs": {
                // 标签页：tabs=[{label, content:{...}}]，点击标签切换内容面板
                JSONArray tabsArr = node.optJSONArray("tabs");
                if (tabsArr == null || tabsArr.length() == 0) {
                    // 兼容 children 形式：首个为标签行？简化——直接渲染全部 children
                    LinearLayout fallback = new LinearLayout(context);
                    fallback.setOrientation(LinearLayout.VERTICAL);
                    addChildren(context, fallback, node, props, viewRefs, depth, defines);
                    return fallback;
                }
                LinearLayout tabsWrap = new LinearLayout(context);
                tabsWrap.setOrientation(LinearLayout.VERTICAL);
                // 标签行
                LinearLayout tabBar = new LinearLayout(context);
                tabBar.setOrientation(LinearLayout.HORIZONTAL);
                android.graphics.drawable.GradientDrawable barBg = new android.graphics.drawable.GradientDrawable();
                barBg.setColor(ComponentColors.fieldBg(context));
                barBg.setCornerRadius(dp(8, density));
                tabBar.setBackground(barBg);
                tabBar.setPadding(dp(4, density), dp(3, density), dp(4, density), dp(3, density));
                final java.util.List<View> panels = new java.util.ArrayList<>();
                final java.util.List<TextView> labels = new java.util.ArrayList<>();
                for (int i = 0; i < tabsArr.length(); i++) {
                    JSONObject tabObj = tabsArr.optJSONObject(i);
                    if (tabObj == null) continue;
                    final int idx = labels.size();
                    TextView tabLabel = new TextView(context);
                    tabLabel.setText(interpolate(tabObj.optString("label", "标签" + (idx + 1)), props));
                    tabLabel.setTextSize(13);
                    tabLabel.setPadding(dp(10, density), dp(6, density), dp(10, density), dp(6, density));
                    tabLabel.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    tabLabel.setLayoutParams(tlLp);
                    final int finalIdx = idx;
                    tabLabel.setOnClickListener(v -> {
                        for (int k = 0; k < labels.size(); k++) {
                            labels.get(k).setTextColor(k == finalIdx
                                    ? ComponentColors.accent(context) : ComponentColors.textSecondary(context));
                            labels.get(k).setTypeface(k == finalIdx
                                    ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
                        }
                        for (int k = 0; k < panels.size(); k++) {
                            panels.get(k).setVisibility(k == finalIdx ? View.VISIBLE : View.GONE);
                        }
                    });
                    tabBar.addView(tabLabel);
                    labels.add(tabLabel);
                    // 内容面板
                    JSONObject content = tabObj.optJSONObject("content");
                    LinearLayout panel = new LinearLayout(context);
                    panel.setOrientation(LinearLayout.VERTICAL);
                    panel.setPadding(0, dp(8, density), 0, 0);
                    if (content != null) {
                        JSONObject contentNode = content;
                        if (!contentNode.has("type")) {
                            JSONObject wrapObj = new JSONObject();
                            try {
                                wrapObj.put("type", "column");
                                wrapObj.put("children", new JSONArray().put(contentNode));
                            } catch (Exception ignored) {
                            }
                            contentNode = wrapObj;
                        }
                        View pv = buildNode(context, contentNode, props, viewRefs, depth + 1, defines);
                        if (pv instanceof LinearLayout) {
                            ((LinearLayout) pv).setPadding(0, 0, 0, 0);
                        }
                        panel.addView(pv);
                    }
                    if (idx > 0) panel.setVisibility(View.GONE);
                    panels.add(panel);
                    tabsWrap.addView(panel);
                }
                // 默认选中第一个
                if (!labels.isEmpty()) {
                    labels.get(0).setTextColor(ComponentColors.accent(context));
                    labels.get(0).setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                }
                tabsWrap.addView(tabBar, 0);
                return tabsWrap;
            }
            case "stack": {
                // 层叠布局（FrameLayout）：children 依次叠放，可用 gravity 定位（center/top_left/top_right/bottom 等）
                android.widget.FrameLayout fl2 = new android.widget.FrameLayout(context);
                fl2.setPadding(dp(8, density), dp(4, density), dp(8, density), dp(4, density));
                JSONArray stKids = node.optJSONArray("children");
                if (stKids != null) {
                    for (int i = 0; i < stKids.length(); i++) {
                        JSONObject sc = stKids.optJSONObject(i);
                        if (sc == null) continue;
                        View sv2 = buildNode(context, sc, props, viewRefs, depth + 1, defines);
                        android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
                                resolveSize(sc.opt("width"), android.widget.FrameLayout.LayoutParams.MATCH_PARENT, density),
                                resolveSize(sc.opt("height"), android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, density));
                        String grav = sc.optString("gravity", sc.optString("align", ""));
                        int g = parseFrameGravity(grav);
                        if (g != 0) flp.gravity = g;
                        Object m = sc.opt("margin");
                        if (m != null) {
                            if (m instanceof JSONObject) {
                                JSONObject mo = (JSONObject) m;
                                flp.setMargins(
                                        mo.has("left") ? dp(mo.optInt("left", 0), density) : 0,
                                        mo.has("top") ? dp(mo.optInt("top", 0), density) : 0,
                                        mo.has("right") ? dp(mo.optInt("right", 0), density) : 0,
                                        mo.has("bottom") ? dp(mo.optInt("bottom", 0), density) : 0);
                            } else {
                                int all = dp((int) Double.parseDouble(String.valueOf(m)), density);
                                flp.setMargins(all, all, all, all);
                            }
                        }
                        fl2.addView(sv2, flp);
                    }
                }
                return fl2;
            }
            case "accordion": {
                // 折叠面板：items=[{title, content:{...}}] 或 children 带 title，点击展开/收起
                JSONArray acItems = node.optJSONArray("items");
                if (acItems == null || acItems.length() == 0) {
                    // 兼容 children 形式：每个 child 用 title 属性
                    acItems = node.optJSONArray("children");
                }
                LinearLayout acWrap = new LinearLayout(context);
                acWrap.setOrientation(LinearLayout.VERTICAL);
                if (acItems == null || acItems.length() == 0) {
                    TextView tv = new TextView(context);
                    tv.setText("（空折叠面板）");
                    tv.setTextSize(12);
                    return tv;
                }
                final java.util.List<LinearLayout> acBodies = new java.util.ArrayList<>();
                final java.util.List<TextView> acTitles = new java.util.ArrayList<>();
                for (int i = 0; i < acItems.length(); i++) {
                    JSONObject item = acItems.optJSONObject(i);
                    if (item == null) continue;
                    final String acTitle = interpolate(item.optString("title", "面板" + (i + 1)), props);
                    // 标题行
                    LinearLayout acHead = new LinearLayout(context);
                    acHead.setOrientation(LinearLayout.HORIZONTAL);
                    acHead.setGravity(Gravity.CENTER_VERTICAL);
                    android.graphics.drawable.GradientDrawable acHeadBg = new android.graphics.drawable.GradientDrawable();
                    acHeadBg.setColor(ComponentColors.fieldBg(context));
                    acHeadBg.setCornerRadius(dp(8, density));
                    acHead.setBackground(acHeadBg);
                    acHead.setPadding(dp(10, density), dp(8, density), dp(10, density), dp(8, density));
                    acHead.setClickable(true);
                    final TextView acTitleTv = new TextView(context);
                    acTitleTv.setText(acTitle);
                    acTitleTv.setTextSize(13);
                    acTitleTv.setTextColor(ComponentColors.textPrimary(context));
                    acHead.addView(acTitleTv, new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    final TextView acArrow = new TextView(context);
                    acArrow.setText("▾");
                    acArrow.setTextSize(13);
                    acArrow.setTextColor(ComponentColors.textSecondary(context));
                    acHead.addView(acArrow);
                    acTitles.add(acTitleTv);
                    acWrap.addView(acHead, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                    // 内容体
                    LinearLayout acBody = new LinearLayout(context);
                    acBody.setOrientation(LinearLayout.VERTICAL);
                    acBody.setPadding(dp(4, density), dp(6, density), dp(4, density), 0);
                    JSONObject content = item.optJSONObject("content");
                    if (content != null) {
                        JSONObject contentNode = content;
                        if (!contentNode.has("type")) {
                            JSONObject wrapObj = new JSONObject();
                            try {
                                wrapObj.put("type", "column");
                                wrapObj.put("children", new JSONArray().put(contentNode));
                            } catch (Exception ignored) {
                            }
                            contentNode = wrapObj;
                        }
                        View pv = buildNode(context, contentNode, props, viewRefs, depth + 1, defines);
                        if (pv instanceof LinearLayout) {
                            ((LinearLayout) pv).setPadding(0, 0, 0, 0);
                        }
                        acBody.addView(pv);
                    }
                    boolean firstOpen = i == 0 && node.optBoolean("first_open", true);
                    if (!firstOpen) acBody.setVisibility(View.GONE);
                    acBodies.add(acBody);
                    acWrap.addView(acBody);
                    final int idx = i;
                    acHead.setOnClickListener(v -> {
                        boolean nowVisible = acBodies.get(idx).getVisibility() == View.VISIBLE;
                        for (int k = 0; k < acBodies.size(); k++) {
                            acBodies.get(k).setVisibility(k == idx && !nowVisible ? View.VISIBLE : View.GONE);
                        }
                        // 箭头方向
                        for (int k = 0; k < acTitles.size(); k++) {
                            TextView arrow = (TextView) ((LinearLayout) acTitles.get(k).getParent())
                                    .getChildAt(1);
                            arrow.setText(k == idx && !nowVisible ? "▴" : "▾");
                        }
                    });
                }
                return acWrap;
            }
            case "carousel": {
                // 图片轮播：images=[url...]，左右箭头 + 指示点切换
                JSONArray carImages = node.optJSONArray("images");
                if (carImages == null || carImages.length() == 0) {
                    TextView tv = new TextView(context);
                    tv.setText("（无图片）");
                    tv.setTextSize(12);
                    return tv;
                }
                int carH = dp(node.has("height") ? (int) Math.round(node.optDouble("height", 160)) : 160, density);
                LinearLayout carWrap = new LinearLayout(context);
                carWrap.setOrientation(LinearLayout.VERTICAL);
                final ImageView[] carView = {new ImageView(context)};
                carView[0].setScaleType(ImageView.ScaleType.CENTER_CROP);
                android.graphics.drawable.ColorDrawable ph = new android.graphics.drawable.ColorDrawable(
                        ComponentColors.imagePlaceholder(context));
                carView[0].setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, carH));
                final int[] carIdx = {0};
                final Runnable[] renderCar = new Runnable[1];
                renderCar[0] = () -> {
                    String src = String.valueOf(carImages.opt(carIdx[0]));
                    try {
                        if (com.oilquiz.app.ai.chat.component.DataImageDecoder.isDataImage(src)) {
                            // data: base64 轮播图：本机解码；失败画浅底占位
                            android.graphics.Bitmap carBmp =
                                    com.oilquiz.app.ai.chat.component.DataImageDecoder.decode(src);
                            if (carBmp != null) {
                                carView[0].setImageBitmap(carBmp);
                            } else {
                                carView[0].setImageDrawable(ph);
                            }
                        } else if (src.startsWith("http://") || src.startsWith("https://")) {
                            com.bumptech.glide.Glide.with(context).load(src)
                                    .placeholder(ph).error(ph).into(carView[0]);
                        } else {
                            // 轮播图源同样要过工作区解析：模型常给裸文件名/相对路径，
                            // 原先只 new File(src) 判存在 → 解析不到就永远显示占位图
                            java.io.File f = com.oilquiz.app.ai.chat.component.MediaPathResolver
                                    .resolveToFile(context, src);
                            if (f == null) {
                                f = new java.io.File(src.startsWith("file://")
                                        ? android.net.Uri.parse(src).getPath() : src);
                            }
                            if (f.exists()) {
                                com.bumptech.glide.Glide.with(context).load(f)
                                        .placeholder(ph).error(ph).into(carView[0]);
                            } else {
                                carView[0].setImageDrawable(ph);
                            }
                        }
                    } catch (Throwable t) {
                        carView[0].setImageDrawable(ph);
                    }
                };
                renderCar[0].run();
                // 指示点更新（Runnable[] 形式：carPrev/carNext 内引用）
                final LinearLayout carDots = new LinearLayout(context);
                carDots.setOrientation(LinearLayout.HORIZONTAL);
                carDots.setGravity(Gravity.CENTER);
                final Runnable[] updateCarDots = new Runnable[1];
                updateCarDots[0] = () -> {
                    carDots.removeAllViews();
                    for (int i = 0; i < carImages.length(); i++) {
                        View dot = new View(context);
                        int dSize = dp(i == carIdx[0] ? 8 : 6, density);
                        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
                        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                        dotBg.setColor(i == carIdx[0] ? ComponentColors.accent(context)
                                : ComponentColors.border(context));
                        dot.setBackground(dotBg);
                        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dSize, dSize);
                        dotLp.setMarginStart(dp(4, density));
                        dotLp.setMarginEnd(dp(4, density));
                        carDots.addView(dot, dotLp);
                    }
                };
                // 控制条：左右箭头 + 指示点
                LinearLayout carCtrl = new LinearLayout(context);
                carCtrl.setOrientation(LinearLayout.HORIZONTAL);
                carCtrl.setGravity(Gravity.CENTER_VERTICAL);
                TextView carPrev = new TextView(context);
                carPrev.setText("◀");
                carPrev.setTextSize(16);
                carPrev.setTextColor(ComponentColors.accent(context));
                carPrev.setPadding(dp(8, density), 0, dp(8, density), 0);
                carPrev.setOnClickListener(v -> {
                    carIdx[0] = (carIdx[0] - 1 + carImages.length()) % carImages.length();
                    renderCar[0].run();
                    updateCarDots[0].run();
                });
                carCtrl.addView(carPrev);
                carCtrl.addView(carDots, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView carNext = new TextView(context);
                carNext.setText("▶");
                carNext.setTextSize(16);
                carNext.setTextColor(ComponentColors.accent(context));
                carNext.setPadding(dp(8, density), 0, dp(8, density), 0);
                carNext.setOnClickListener(v -> {
                    carIdx[0] = (carIdx[0] + 1) % carImages.length();
                    renderCar[0].run();
                    updateCarDots[0].run();
                });
                carCtrl.addView(carNext);
                updateCarDots[0].run();
                carWrap.addView(carView[0]);
                carWrap.addView(carCtrl);
                return carWrap;
            }
            case "text": {
                return buildTextNode(context, node, props, viewRefs);
            }            case "marquee": {
                TextView tv = new TextView(context);
                String raw = interpolate(node.optString("text", ""), props);
                tv.setText(raw);
                tv.setTextSize(node.has("size") ? (float) node.optDouble("size", 16) : 16);
                tv.setTypeface(node.optBoolean("bold", false)
                        ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
                if (node.has("color")) tv.setTextColor(parseColor(context, node.optString("color", "")));
                tv.setPadding(0, dp(4, density), 0, dp(4, density));
                int speed = Math.max(0, Math.min(3, node.optInt("speed", 1)));
                int rep = node.optInt("repeat", -1);
                // 位移动画实现跑马灯（不依赖系统 marquee 焦点机制，Dialog/任意容器内均可滚动）
                startMarquee(tv, raw, speed, rep < 0 ? -1 : rep);
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put("text_" + key, tv);
                return tv;
            }
            case "badge": {
                // 徽章/标签：圆角小色块 + 文字
                TextView bTv = new TextView(context);
                bTv.setText(interpolate(node.optString("text", node.optString("label", "")), props));
                bTv.setTextSize(node.has("size") ? (float) node.optDouble("size", 12) : 12);
                bTv.setPadding(dp(8, density), dp(2, density), dp(8, density), dp(2, density));
                int badgeColor;
                try {
                    badgeColor = parseColor(context, node.optString("color", ""));
                } catch (Exception e) {
                    badgeColor = ComponentColors.accent(context);
                }
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(ComponentColors.accentOverlay(context));
                bg.setCornerRadius(dp(12, density));
                bTv.setBackground(bg);
                bTv.setTextColor(badgeColor);
                String bkey = node.optString("key", "");
                if (!bkey.isEmpty()) viewRefs.put(bkey, bTv);
                return bTv;
            }
            case "avatar": {
                // 圆形头像：url 加载（Glide），fallback 显示首字母
                ImageView av = new ImageView(context);
                int size = dp(node.has("size") ? (int) Math.round(node.optDouble("size", 48)) : 48, density);
                android.widget.LinearLayout.LayoutParams avLp = new android.widget.LinearLayout.LayoutParams(size, size);
                av.setLayoutParams(avLp);
                String src = interpolate(node.optString("url", node.optString("src", "")), props);
                int radius = size / 2;
                if (com.oilquiz.app.ai.chat.component.DataImageDecoder.isDataImage(src)) {
                    // data: base64 头像：本机解码成圆形图；失败用浅色圆底占位（不显示裂图）
                    android.graphics.Bitmap avatarBmp =
                            com.oilquiz.app.ai.chat.component.DataImageDecoder.decode(src);
                    if (avatarBmp != null) {
                        androidx.core.graphics.drawable.RoundedBitmapDrawable round =
                                androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
                                        .create(context.getResources(), avatarBmp);
                        round.setCircular(true);
                        av.setImageDrawable(round);
                    } else {
                        android.graphics.drawable.GradientDrawable failBg =
                                new android.graphics.drawable.GradientDrawable();
                        failBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                        failBg.setColor(ComponentColors.imageError(context));
                        av.setBackground(failBg);
                    }
                    String dataKey = node.optString("key", "");
                    if (!dataKey.isEmpty()) viewRefs.put(dataKey, av);
                    return av;
                }
                if (!src.isEmpty()) {
                    try {
                        com.bumptech.glide.request.RequestOptions avOpts =
                                new com.bumptech.glide.request.RequestOptions()
                                        .override(size, size)
                                        .centerCrop()
                                        .circleCrop();
                        com.bumptech.glide.Glide.with(context)
                                .load(com.oilquiz.app.ai.chat.component.MediaPathResolver
                                        .resolve(context, src))
                                .apply(avOpts)
                                .into(av);
                    } catch (Throwable t) {
                        android.util.Log.w(TAG, "头像加载失败: " + t.getMessage());
                    }
                } else {
                    String letter = node.optString("text", "?");
                    if (letter.length() > 1) letter = letter.substring(0, 1);
                    android.graphics.drawable.GradientDrawable avBg = new android.graphics.drawable.GradientDrawable();
                    avBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    avBg.setColor(ComponentColors.accent(context));
                    av.setBackground(avBg);
                    av.setPadding(size / 4, size / 4, size / 4, size / 4);
                    av.setImageDrawable(null);
                    av.setImageAlpha(0);
                    TextView letterTv = new TextView(context);
                    letterTv.setText(letter);
                    letterTv.setTextColor(ThemeColors.get(R.color.hc_ffffffff));
                    letterTv.setTextSize(size / 3.0f);
                    letterTv.setGravity(Gravity.CENTER);
                    // 用 FrameLayout 包裹（ImageView 上叠字母）
                    android.widget.FrameLayout avWrap = new android.widget.FrameLayout(context);
                    avWrap.addView(av, new android.widget.FrameLayout.LayoutParams(size, size));
                    avWrap.addView(letterTv, new android.widget.FrameLayout.LayoutParams(size, size));
                    return avWrap;
                }
                String akey = node.optString("key", "");
                if (!akey.isEmpty()) viewRefs.put(akey, av);
                return av;
            }
            case "avatar_group": {
                // 头像组：urls=[...] 或 avatars=[{url,text}]，水平叠排（负 margin 重叠）
                JSONArray agUrls = node.optJSONArray("urls");
                if (agUrls == null) agUrls = node.optJSONArray("avatars");
                LinearLayout agWrap = new LinearLayout(context);
                agWrap.setOrientation(LinearLayout.HORIZONTAL);
                int agSize = dp(node.has("size") ? (int) Math.round(node.optDouble("size", 36)) : 36, density);
                int overlap = dp(node.has("overlap") ? node.optInt("overlap", 10) : 10, density);
                if (agUrls != null) {
                    for (int i = 0; i < agUrls.length(); i++) {
                        Object o = agUrls.opt(i);
                        String url = o instanceof JSONObject ? ((JSONObject) o).optString("url", "") : String.valueOf(o);
                        String letter = o instanceof JSONObject ? ((JSONObject) o).optString("text", "") : "";
                        ImageView agImg = new ImageView(context);
                        LinearLayout.LayoutParams agLp = new LinearLayout.LayoutParams(agSize, agSize);
                        if (i > 0) agLp.setMarginStart(-overlap);
                        agImg.setLayoutParams(agLp);
                        android.graphics.drawable.GradientDrawable ringBg = new android.graphics.drawable.GradientDrawable();
                        ringBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                        ringBg.setColor(ComponentColors.background(context));
                        ringBg.setStroke(dp(2, density), ComponentColors.background(context));
                        agImg.setBackground(ringBg);
                        if (com.oilquiz.app.ai.chat.component.DataImageDecoder.isDataImage(url)) {
                            android.graphics.Bitmap agBmp =
                                    com.oilquiz.app.ai.chat.component.DataImageDecoder.decode(url);
                            if (agBmp != null) {
                                androidx.core.graphics.drawable.RoundedBitmapDrawable agRound =
                                        androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
                                                .create(context.getResources(), agBmp);
                                agRound.setCircular(true);
                                agImg.setImageDrawable(agRound);
                            } else {
                                agImg.setBackgroundColor(ComponentColors.imageError(context));
                            }
                        } else if (!url.isEmpty()) {
                            try {
                                com.bumptech.glide.request.RequestOptions agOpts =
                                        new com.bumptech.glide.request.RequestOptions()
                                                .override(agSize, agSize).centerCrop().circleCrop();
                                com.bumptech.glide.Glide.with(context)
                                        .load(com.oilquiz.app.ai.chat.component.MediaPathResolver
                                                .resolve(context, url))
                                        .apply(agOpts).into(agImg);
                            } catch (Throwable t) {
                                agImg.setImageDrawable(null);
                            }
                        }
                        agWrap.addView(agImg);
                    }
                }
                // 追加数量徽标（total 大于 urls 长度时）
                int agTotal = node.optInt("total", agUrls != null ? agUrls.length() : 0);
                if (agUrls != null && agTotal > agUrls.length()) {
                    TextView moreTv = new TextView(context);
                    moreTv.setText("+" + (agTotal - agUrls.length()));
                    moreTv.setTextSize(10);
                    moreTv.setGravity(Gravity.CENTER);
                    moreTv.setTextColor(ThemeColors.get(R.color.hc_ffffffff));
                    android.graphics.drawable.GradientDrawable moreBg = new android.graphics.drawable.GradientDrawable();
                    moreBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    moreBg.setColor(ComponentColors.textSecondary(context));
                    moreTv.setBackground(moreBg);
                    LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(agSize, agSize);
                    moreLp.setMarginStart(-overlap);
                    moreTv.setLayoutParams(moreLp);
                    agWrap.addView(moreTv);
                }
                return agWrap;
            }
            case "quote": {
                // 引用块：左边框 + 斜体文本 + 可选作者
                LinearLayout qWrap = new LinearLayout(context);
                qWrap.setOrientation(LinearLayout.VERTICAL);
                qWrap.setPadding(dp(4, density), dp(2, density), dp(4, density), dp(2, density));
                android.graphics.drawable.GradientDrawable qBg = new android.graphics.drawable.GradientDrawable();
                qBg.setColor(ComponentColors.fieldBg(context));
                qBg.setCornerRadius(dp(6, density));
                qWrap.setBackground(qBg);
                qWrap.setPadding(dp(10, density), dp(6, density), dp(10, density), dp(6, density));
                TextView qTv = new TextView(context);
                qTv.setText(interpolate(node.optString("text", node.optString("content", "")), props));
                qTv.setTextSize(14);
                qTv.setTextColor(ComponentColors.textSecondary(context));
                qTv.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC);
                qWrap.addView(qTv);
                String author = interpolate(node.optString("author", ""), props);
                if (!author.isEmpty()) {
                    TextView aTv = new TextView(context);
                    aTv.setText("—— " + author);
                    aTv.setTextSize(12);
                    aTv.setTextColor(ComponentColors.textTertiary(context));
                    aTv.setGravity(Gravity.END);
                    qWrap.addView(aTv);
                }
                return qWrap;
            }
            case "code": {
                // 代码块：等宽字体 + 深色背景 + 可选语言标签
                LinearLayout cWrap = new LinearLayout(context);
                cWrap.setOrientation(LinearLayout.VERTICAL);
                android.graphics.drawable.GradientDrawable cBg = new android.graphics.drawable.GradientDrawable();
                cBg.setColor(ThemeColors.get(R.color.hc_ff1e1e2e));
                cBg.setCornerRadius(dp(8, density));
                cWrap.setBackground(cBg);
                cWrap.setPadding(dp(10, density), dp(8, density), dp(10, density), dp(8, density));
                String lang = node.optString("language", node.optString("lang", ""));
                if (!lang.isEmpty()) {
                    TextView lTv = new TextView(context);
                    lTv.setText(lang);
                    lTv.setTextSize(11);
                    lTv.setTextColor(ThemeColors.get(R.color.hc_ff7aa2f7));
                    lTv.setPadding(0, 0, 0, dp(4, density));
                    cWrap.addView(lTv);
                }
                TextView codeTv = new TextView(context);
                codeTv.setText(interpolate(node.optString("code", node.optString("text", "")), props));
                codeTv.setTextSize(12);
                codeTv.setTextColor(ThemeColors.get(R.color.hc_ffcdd6f4));
                codeTv.setTypeface(android.graphics.Typeface.MONOSPACE);
                cWrap.addView(codeTv);
                return cWrap;
            }
            case "icon": {
                // 图标：emoji/字体图标（当前渲染为 TextView，支持 size/color）
                TextView iTv = new TextView(context);
                iTv.setText(interpolate(node.optString("icon", node.optString("text", "•")), props));
                iTv.setTextSize(node.has("size") ? (float) node.optDouble("size", 20) : 20);
                if (node.has("color")) iTv.setTextColor(parseColor(context, node.optString("color", "")));
                iTv.setGravity(Gravity.CENTER);
                String ikey = node.optString("key", "");
                if (!ikey.isEmpty()) viewRefs.put(ikey, iTv);
                return iTv;
            }
            case "input":
                return buildTextInput(context, node, props, viewRefs, "请输入", 0, false, false);
            case "number":
                return buildTextInput(context, node, props, viewRefs, "请输入数字",
                        android.text.InputType.TYPE_CLASS_NUMBER
                                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL, false, false);
            case "password":
                return buildTextInput(context, node, props, viewRefs, "请输入密码",
                        android.text.InputType.TYPE_CLASS_TEXT
                                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, false, true);
            case "multiline":
                return buildTextInput(context, node, props, viewRefs, "请输入内容", 0, true, false);
            case "otp": {
                int len = Math.max(1, Math.min(12, node.optInt("length", 6)));
                TextInputLayout otpTil = buildTextInput(context, node, props, viewRefs,
                        "请输入" + len + "位验证码", android.text.InputType.TYPE_CLASS_NUMBER, false, false);
                EditText otpEt = CanvasControls.editOf(otpTil);
                if (otpEt != null) {
                    otpEt.setFilters(new android.text.InputFilter[]{
                            new android.text.InputFilter.LengthFilter(len)});
                }
                return otpTil;
            }
            case "email":
                return buildTextInput(context, node, props, viewRefs, "请输入邮箱",
                        android.text.InputType.TYPE_CLASS_TEXT
                                | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, false, false);
            case "tel":
                return buildTextInput(context, node, props, viewRefs, "请输入电话",
                        android.text.InputType.TYPE_CLASS_PHONE, false, false);
            case "url":
                return buildTextInput(context, node, props, viewRefs, "请输入网址",
                        android.text.InputType.TYPE_CLASS_TEXT
                                | android.text.InputType.TYPE_TEXT_VARIATION_URI, false, false);
            case "search": {
                TextInputLayout searchTil = buildTextInput(context, node, props, viewRefs, "搜索",
                        android.text.InputType.TYPE_CLASS_TEXT
                                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false, false);
                EditText searchEt = CanvasControls.editOf(searchTil);
                if (searchEt != null) {
                    searchEt.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
                }
                return searchTil;
            }
            case "search_bar": {
                // 搜索条：M2 outlined 输入框 + 前置搜索图标（点击/回车触发 action）
                TextInputLayout sbTil = CanvasControls.searchBar(context,
                        interpolate(node.optString("hint", "搜索"), props), "");
                EditText sbEt = CanvasControls.editOf(sbTil);
                if (sbEt != null) {
                    String sbVal = node.has("value") ? interpolate(node.optString("value", ""), props)
                            : (props != null ? props.optString(node.optString("key", ""), "") : "");
                    if (!sbVal.isEmpty()) sbEt.setText(sbVal);
                    setupInputView(sbEt);
                    String sbKey = node.optString("key", "");
                    if (!sbKey.isEmpty()) viewRefs.put(sbKey, sbEt);
                }
                return sbTil;
            }
            case "tag_input": {
                // 标签输入：M2 Chip 列表 + M2 输入框（回车添加 / 点 × 删除），key 收集标签数组
                LinearLayout tiWrap = new LinearLayout(context);
                tiWrap.setOrientation(LinearLayout.VERTICAL);
                final com.google.android.material.chip.ChipGroup tiChips =
                        CanvasControls.chipGroup(context, false);
                final java.util.List<String> tiTags = new java.util.ArrayList<>();
                JSONArray initTags = node.optJSONArray("tags");
                if (initTags == null) initTags = node.optJSONArray("value");
                if (initTags == null) initTags = node.optJSONArray("default");
                if (initTags != null) {
                    for (int i = 0; i < initTags.length(); i++) {
                        String t = String.valueOf(initTags.opt(i));
                        if (!t.trim().isEmpty()) tiTags.add(t.trim());
                    }
                }
                final Runnable[] renderChips = new Runnable[1];
                renderChips[0] = () -> {
                    tiChips.removeAllViews();
                    for (int i = 0; i < tiTags.size(); i++) {
                        final int idx = i;
                        final com.google.android.material.chip.Chip chip =
                                CanvasControls.chip(context, tiTags.get(i), false, false);
                        chip.setCloseIconVisible(true);
                        chip.setOnCloseIconClickListener(v -> {
                            tiTags.remove(idx);
                            renderChips[0].run();
                        });
                        tiChips.addView(chip);
                    }
                };
                renderChips[0].run();
                tiWrap.addView(tiChips);
                TextInputLayout tiTil = buildTextInput(context, node, props, null,
                        "输入后回车添加", 0, false, false);
                EditText tiInput = CanvasControls.editOf(tiTil);
                if (tiInput != null) {
                    tiInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
                    tiInput.setOnEditorActionListener((v, actionId, event) -> {
                        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                                || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                            String t = tiInput.getText().toString().trim();
                            if (!t.isEmpty()) {
                                tiTags.add(t);
                                tiInput.setText("");
                                renderChips[0].run();
                            }
                            return true;
                        }
                        return false;
                    });
                }
                tiWrap.addView(tiTil);
                String tiKey = node.optString("key", "");
                if (!tiKey.isEmpty()) viewRefs.put(tiKey, new TagInputRef(tiTags));
                return tiWrap;
            }
            case "checkbox": {
                com.google.android.material.checkbox.MaterialCheckBox cb =
                        CanvasControls.checkBox(context, interpolate(node.optString("text", ""), props),
                                node.optBoolean("checked", false));
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, cb);
return attachLabel(context, node, props, cb, density);
            }
            case "checkbox_group": {
                // 复选组：options=[{label,value}] 或字符串数组，key 收集选中值数组
                LinearLayout cgWrap = new LinearLayout(context);
                cgWrap.setOrientation(node.optString("direction", "vertical").equals("horizontal")
                        ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                JSONArray cgOpts = node.optJSONArray("options");
                final java.util.List<String> cgSel = new java.util.ArrayList<>();
                JSONArray cgInit = node.optJSONArray("value");
                if (cgInit != null) {
                    for (int i = 0; i < cgInit.length(); i++) cgSel.add(String.valueOf(cgInit.opt(i)));
                }
                final java.util.List<android.widget.CheckBox> cgBoxes = new java.util.ArrayList<>();
                if (cgOpts != null) {
                    for (int i = 0; i < cgOpts.length(); i++) {
                        Object o = cgOpts.opt(i);
                        String label = o instanceof JSONObject ? ((JSONObject) o).optString("label",
                                String.valueOf(((JSONObject) o).opt("value"))) : String.valueOf(o);
                        final String val = o instanceof JSONObject ? ((JSONObject) o).optString("value", label) : label;
                        final android.widget.CheckBox cb =
                                CanvasControls.checkBox(context, label, cgSel.contains(val));
                        cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                            if (isChecked) {
                                if (!cgSel.contains(val)) cgSel.add(val);
                            } else {
                                cgSel.remove(val);
                            }
                        });
                        cgBoxes.add(cb);
                        cgWrap.addView(cb);
                    }
                }
                String cgKey = node.optString("key", "");
                if (!cgKey.isEmpty()) viewRefs.put(cgKey, new CheckboxGroupRef(cgSel));
return attachLabel(context, node, props, cgWrap, density);
            }
            case "radio": {
                android.widget.RadioGroup rg = new android.widget.RadioGroup(context);
                rg.setOrientation(LinearLayout.VERTICAL);
                JSONArray opts = node.optJSONArray("options");
                String selected = node.optString("value", node.optString("default", ""));
                if (opts != null) {
                    for (int i = 0; i < opts.length(); i++) {
                        String label = String.valueOf(opts.opt(i));
                        android.widget.RadioButton rb = CanvasControls.radioButton(context, label);
                        rb.setId(android.view.View.generateViewId());
                        rg.addView(rb);
                        if (label.equals(selected)) rg.check(rb.getId());
                    }
                }
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, rg);
return attachLabel(context, node, props, rg, density);
            }
            case "radio_group": {
                // 单选组（同 radio，支持 {label,value} 对象选项与 direction）
                android.widget.RadioGroup rg = new android.widget.RadioGroup(context);
                rg.setOrientation(node.optString("direction", "vertical").equals("horizontal")
                        ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                JSONArray opts = node.optJSONArray("options");
                String selected = node.optString("value", node.optString("default", ""));
                final java.util.Map<Integer, String> idToVal = new java.util.HashMap<>();
                if (opts != null) {
                    for (int i = 0; i < opts.length(); i++) {
                        Object o = opts.opt(i);
                        String label = o instanceof JSONObject ? ((JSONObject) o).optString("label",
                                String.valueOf(((JSONObject) o).opt("value"))) : String.valueOf(o);
                        final String val = o instanceof JSONObject ? ((JSONObject) o).optString("value", label) : label;
                        android.widget.RadioButton rb = CanvasControls.radioButton(context, label);
                        rb.setId(android.view.View.generateViewId());
                        idToVal.put(rb.getId(), val);
                        rg.addView(rb);
                        if (val.equals(selected)) rg.check(rb.getId());
                    }
                }
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, new RadioGroupRef(rg, idToVal));
return attachLabel(context, node, props, rg, density);
            }
            case "slider": {
                // M2 Slider（替代 SeekBar）：轨道/滑块/拖动标签都是 Material 的
                int min = node.optInt("min", 0);
                int max = node.optInt("max", 100);
                int step = Math.max(1, node.optInt("step", 1));
                if (max <= min) max = min + 100;
                // Slider 要求步长整除区间，否则 setStepSize 直接抛异常
                max = min + ((max - min) / step) * step;
                int val = node.optInt("value", node.optInt("default", min));
                val = min + Math.round((float) (val - min) / step) * step;
                val = Math.max(min, Math.min(max, val));
                Slider slider = new Slider(context);
                slider.setValueFrom(min);
                slider.setValueTo(max);
                slider.setStepSize(step);
                slider.setValue(val);
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, slider);
                if (node.optBoolean("show_value", false)) {
                    final TextView valTv = new TextView(context);
                    Tokens.applyText(valTv, Tokens.TEXT_FOOTNOTE, Tokens.Weight.REGULAR,
                            ComponentColors.textSecondary(context));
                    valTv.setText(String.valueOf(val));
                    valTv.setPadding(0, dp(2, density), 0, 0);
                    slider.addOnChangeListener((s, v, fromUser) ->
                            valTv.setText(String.valueOf(Math.round(v))));
                    LinearLayout wrap = new LinearLayout(context);
                    wrap.setOrientation(LinearLayout.VERTICAL);
                    wrap.addView(slider);
                    wrap.addView(valTv);
                    return attachLabel(context, node, props, wrap, density);
                }
                return attachLabel(context, node, props, slider, density);
            }
            case "date": {
                final String[] cur = {pickerInitial(node, props)};
                final TextInputLayout dateTil = CanvasControls.pickerField(context,
                        pickerLabel(node, props, "点击选择日期"), cur[0]);
                final EditText dateEt = CanvasControls.editOf(dateTil);
                Runnable openDate = () -> showDatePicker(context, cur[0], picked -> {
                    cur[0] = picked;
                    if (dateEt != null) dateEt.setText(picked);
                });
                dateTil.setOnClickListener(v -> openDate.run());
                dateTil.setEndIconOnClickListener(v -> openDate.run());
                if (dateEt != null) dateEt.setOnClickListener(v -> openDate.run());
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, new DateRef(cur)); // 未选择时收集空串（不把占位文本当值）
                return dateTil;
            }
            case "time": {
                final String[] cur = {pickerInitial(node, props)};
                final TextInputLayout timeTil = CanvasControls.pickerField(context,
                        pickerLabel(node, props, "点击选择时间"), cur[0]);
                final EditText timeEt = CanvasControls.editOf(timeTil);
                Runnable openTime = () -> showTimePicker(context, cur[0], picked -> {
                    cur[0] = picked;
                    if (timeEt != null) timeEt.setText(picked);
                });
                timeTil.setOnClickListener(v -> openTime.run());
                timeTil.setEndIconOnClickListener(v -> openTime.run());
                if (timeEt != null) timeEt.setOnClickListener(v -> openTime.run());
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, new DateRef(cur)); // 未选择时收集空串
                return timeTil;
            }
            case "datetime": {
                // 日期+时间选择（依次弹出日期与时间选择器，value 格式 yyyy-MM-dd HH:mm）
                final String[] cur = {pickerInitial(node, props)};
                final TextInputLayout dtTil = CanvasControls.pickerField(context,
                        pickerLabel(node, props, "点击选择日期时间"), cur[0]);
                final EditText dtEt = CanvasControls.editOf(dtTil);
                Runnable openDt = () -> showDatePicker(context, cur[0], datePicked ->
                        showTimePicker(context, cur[0], timePicked -> {
                            cur[0] = datePicked + " " + timePicked;
                            if (dtEt != null) dtEt.setText(cur[0]);
                        }));
                dtTil.setOnClickListener(v -> openDt.run());
                dtTil.setEndIconOnClickListener(v -> openDt.run());
                if (dtEt != null) dtEt.setOnClickListener(v -> openDt.run());
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, new DateRef(cur));
                return dtTil;
            }
            case "color": {
                final String[] palette = {"#EF4444", "#F97316", "#F59E0B", "#84CC16",
                        "#10B981", "#06B6D4", "#3B82F6", "#8B5CF6", "#EC4899", "#6B7280"};
                LinearLayout grid = new LinearLayout(context);
                grid.setOrientation(LinearLayout.HORIZONTAL);
                final String[] picked = {node.optString("value", node.optString("default", "#EF4444"))};
                for (final String c : palette) {
                    android.view.View sw = new android.view.View(context);
                    android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                            dp(28, density), dp(28, density));
                    lp.setMargins(dp(3, density), dp(3, density), dp(3, density), dp(3, density));
                    sw.setLayoutParams(lp);
                    sw.setBackgroundColor(parseColor(context, c));
                    sw.setOnClickListener(v -> {
                        picked[0] = c;
                        for (int i = 0; i < grid.getChildCount(); i++) {
                            android.view.View gv = grid.getChildAt(i);
                            gv.setPadding(0, 0, 0, 0);
                        }
                        sw.setPadding(dp(2, density), dp(2, density), dp(2, density), dp(2, density));
                    });
                    grid.addView(sw);
                }
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, new ColorRef(picked));
                return attachLabel(context, node, props, grid, density);
            }
            case "rating": {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                final int[] score = {node.optInt("value", node.optInt("default", 0))};
                final TextView[] stars = new TextView[5];
                final int emptyColor = com.oilquiz.app.ai.chat.component.ComponentColors.ratingEmpty(context);
                final int filledColor = com.oilquiz.app.ai.chat.component.ComponentColors.warning(context);
                for (int i = 0; i < 5; i++) {
                    final int idx = i;
                    TextView star = new TextView(context);
                    star.setText("★");
                    star.setTextSize(30);
                    star.setTextColor(emptyColor);
                    star.setPadding(dp(4, density), 0, dp(4, density), 0);
                    star.setOnClickListener(v -> {
                        score[0] = idx + 1;
                        for (int k = 0; k < 5; k++) {
                            stars[k].setTextColor(k <= idx ? filledColor : emptyColor);
                        }
                    });
                    stars[i] = star;
                    row.addView(star);
                }
                if (score[0] >= 1 && score[0] <= 5) {
                    for (int k = 0; k < score[0]; k++) stars[k].setTextColor(filledColor);
                }
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, new RatingRef(score));
return attachLabel(context, node, props, row, density);
            }
            case "button": {
                // M2 MaterialButton（框架标准按钮样式）：涟漪/禁用态/触摸目标/主题色都跟着框架
                com.google.android.material.button.MaterialButton btn =
                        CanvasControls.button(context, interpolate(node.optString("text", "确定"), props));
                String bind = node.optString("action", "");
                if (node.has("tool")) {
                    try {
                        JSONObject bindObj = new JSONObject();
                        bindObj.put("action", node.optString("action", ""));
                        bindObj.put("tool", node.optString("tool", ""));
                        Object tp = node.opt("tool_params");
                        bindObj.put("tool_params", tp != null ? tp : new JSONObject());
                        bind = bindObj.toString();
                    } catch (Exception e) {
                        bind = node.optString("action", "");
                    }
                }
                btn.setTag(bind);
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, btn);
                wireActionClick(context, btn, node, props, node.optString("text", "打开"));
                return btn;
            }
            case "link": {
                // 超链接：text + url(点击打开浏览器) 或 action(回调)
                TextView lTv = new TextView(context);
                lTv.setText(interpolate(node.optString("text", node.optString("label", "链接")), props));
                lTv.setTextSize(node.has("size") ? (float) node.optDouble("size", 14) : 14);
                lTv.setTextColor(ComponentColors.accent(context));
                lTv.setPadding(0, dp(2, density), 0, dp(2, density));
                final String linkUrl = interpolate(node.optString("url", ""), props);
                final String linkAction = node.optString("action", "");
                if (!linkAction.isEmpty()) {
                    lTv.setTag(linkAction);
                    String lkey = node.optString("key", "");
                    if (!lkey.isEmpty()) viewRefs.put(lkey, lTv);
                } else if (!linkUrl.isEmpty()) {
                    lTv.setOnClickListener(v -> {
                        try {
                            com.oilquiz.app.ai.chat.component.ComponentActions.openLink(context, linkUrl);
                        } catch (Exception e) {
                            android.util.Log.w(TAG, "打开链接失败: " + e.getMessage());
                        }
                    });
                }
                return lTv;
            }
            case "spinner": {
                // 加载圈（不确定进度）
                android.widget.ProgressBar sp = new android.widget.ProgressBar(context);
                int spSize = dp(node.has("size") ? (int) Math.round(node.optDouble("size", 32)) : 32, density);
                sp.setLayoutParams(new LinearLayout.LayoutParams(spSize, spSize));
                sp.setIndeterminate(true);
                return sp;
            }
            case "image": {
                ImageView iv = new ImageView(context);
                int wDp = node.has("width") ? (int) Math.round(node.optDouble("width", -1)) : -1;
                int hDp = node.has("height") ? (int) Math.round(node.optDouble("height", -1)) : -1;
                if (wDp > 0 && hDp > 0) {
                    iv.setAdjustViewBounds(false);
                    iv.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp, density), dp(hDp, density)));
                } else {
                    iv.setAdjustViewBounds(true);
                    iv.setMaxHeight((int) (240 * density));
                }
                // 图源别名：本类控件文档写的是 "image(url 或 path)"，但代码原先只读 url ——
                // 文档承诺的 path 从未生效，而模型按其它卡片（Video/Audio/image_grid 都收
                // url/src/media/uri/value）的习惯常写 src → 结果 src 为空，
                // 连占位图都不画（真机实测：画布里只出现空白，不是裂图）。
                String src = interpolate(firstNonEmpty(node,
                        "url", "path", "src", "image", "uri"), props);
                // data:image/...;base64,...：本机解码后直接显示，不走 Glide/文件解析。
                // 单张 payload > 512 KiB 或单边 > 4096（含解码失败）→ 浅底"图片无法显示"占位。
                if (com.oilquiz.app.ai.chat.component.DataImageDecoder.isDataImage(src)) {
                    android.graphics.Bitmap dataBmp =
                            com.oilquiz.app.ai.chat.component.DataImageDecoder.decode(src);
                    if (dataBmp != null) {
                        iv.setImageBitmap(dataBmp);
                        String dataKey = node.optString("key", "");
                        if (!dataKey.isEmpty()) viewRefs.put(dataKey, iv);
                        return iv;
                    }
                    return imageUnavailable(context, wDp, hDp, density,
                            node.optString("key", ""), viewRefs);
                }
                final android.graphics.drawable.Drawable ph =
                        new android.graphics.drawable.ColorDrawable(
                                com.oilquiz.app.ai.chat.component.ComponentColors.imagePlaceholder(context));
                final android.graphics.drawable.Drawable err =
                        new android.graphics.drawable.ColorDrawable(
                                com.oilquiz.app.ai.chat.component.ComponentColors.imageError(context));
                if (!src.isEmpty()) {
                    try {
                        com.bumptech.glide.request.RequestOptions opts =
                                new com.bumptech.glide.request.RequestOptions();
                        if (wDp > 0 && hDp > 0) {
                            opts = opts.override(dp(wDp, density), dp(hDp, density));
                        }
                        opts = opts.centerCrop();
                        if (src.startsWith("http://") || src.startsWith("https://")) {
                            com.bumptech.glide.Glide.with(context)
                                    .load(src)
                                    .apply(opts)
                                    .placeholder(ph)
                                    .error(err)
                                    .into(iv);
                        } else {
                            // image 节点：先按工作区解析（裸文件名 / files/xxx 相对路径是模型常见写法），
                            // 解析不到再退回原值判存在 —— 与 ImageGridCardView / FileCardView 同口径
                            String path = src.startsWith("file://")
                                    ? android.net.Uri.parse(src).getPath() : src;
                            java.io.File f = com.oilquiz.app.ai.chat.component.MediaPathResolver
                                    .resolveToFile(context, src);
                            if (f == null) f = new java.io.File(path);
                            if (f.exists()) {
                                com.bumptech.glide.Glide.with(context)
                                        .load(f)
                                        .apply(opts)
                                        .placeholder(ph)
                                        .error(err)
                                        .into(iv);
                            } else {
                                iv.setBackground(err);
                            }
                        }
                    } catch (Throwable t) {
                        android.util.Log.w(TAG, "图片加载失败: " + t.getMessage());
                        iv.setBackground(err);
                    }
                } else {
                    iv.setBackground(err);
                }
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, iv);
                return iv;
            }
            case "html": {
                // HTML 富文本：WebView 渲染（复用聊天流 HtmlCardView 的 WebView 配置）。
                // 用 LinearLayout 包裹固定高度：addChildren/外层会重设 LayoutParams 为 WRAP_CONTENT，
                // 裸 WebView 在 WRAP_CONTENT 下高度会塌陷为 0（内容不显示）；容器固定高可保证可见。
                String htmlContent = interpolate(node.optString("html", node.optString("content", "")), props);
                int maxH = node.has("maxHeight") ? (int) Math.round(node.optDouble("maxHeight", 400)) : 400;
                LinearLayout htmlWrap = new LinearLayout(context);
                htmlWrap.setOrientation(LinearLayout.VERTICAL);
                android.webkit.WebView wv = new android.webkit.WebView(context);
                wv.setBackgroundColor(ThemeColors.get(R.color.hc_00000000));
                wv.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
                wv.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(maxH, density)));
                wv.getSettings().setJavaScriptEnabled(true);
                wv.getSettings().setLoadWithOverviewMode(true);
                wv.getSettings().setUseWideViewPort(true);
                wv.setWebViewClient(new android.webkit.WebViewClient());
                String htmlDoc = "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                        + "<style>html,body{margin:0;padding:0;background:transparent}"
                        + "body{font-family:sans-serif;font-size:14px;color:" + (isNightMode(context) ? "#D1D5DB" : "#333")
                        + ";line-height:1.5;word-break:break-word;padding:2px}"
                        + "img{max-width:100%}table{border-collapse:collapse;width:100%}"
                        + "td,th{border:1px solid #ddd;padding:4px 6px}pre{background:#f5f5f5;padding:8px;overflow-x:auto}"
                        + "code{background:#f0f0f0;padding:1px 4px;border-radius:4px}</style></head>"
                        + "<body>" + htmlContent + "</body></html>";
                wv.loadDataWithBaseURL(null, htmlDoc, "text/html", "UTF-8", null);
                htmlWrap.addView(wv);
                String hkey = node.optString("key", "");
                if (!hkey.isEmpty()) viewRefs.put(hkey, wv);
                return htmlWrap;
            }
            case "video": {
                // ExoPlayer 原生视频播放（复用聊天流视频组件：16:9 区域 + 控制条/手势/倍速/循环）
                String vUrl = firstNonEmpty(node, "url", "src", "media", "video_url", "uri", "value");
                vUrl = interpolate(vUrl, props);
                if (vUrl.isEmpty()) {
                    TextView tv = new TextView(context);
                    tv.setText("⚠ video 缺少 url");
                    tv.setTextSize(12);
                    return tv;
                }
                org.json.JSONObject p = new org.json.JSONObject();
                try {
                    p.put("url", vUrl);
                    if (node.has("title")) p.put("title", node.optString("title", ""));
                    if (node.has("autoPlay")) p.put("autoPlay", node.optBoolean("autoPlay", false));
                    if (node.has("loop")) p.put("loop", node.optBoolean("loop", false));
                    if (node.has("speed")) p.put("speed", node.optDouble("speed", 1.0));
                } catch (org.json.JSONException ignored) {
                }
                com.oilquiz.app.ai.chat.component.ComponentData vcd =
                        com.oilquiz.app.ai.chat.component.ComponentData.of("video", p);
                View vv = com.oilquiz.app.ai.chat.component.ComponentRegistry.getInstance()
                        .render(context, vcd);
                if (vv == null) {
                    TextView tv = new TextView(context);
                    tv.setText("⚠ 视频加载失败");
                    tv.setTextSize(12);
                    return tv;
                }
                String vkey = node.optString("key", "");
                if (!vkey.isEmpty()) viewRefs.put(vkey, vv);
                return vv;
            }
            case "audio": {
                // ExoPlayer 原生音频播放（复用聊天流音频组件：播放条 + 标题/歌手）
                String aUrl = firstNonEmpty(node, "url", "src", "media", "audio_url", "uri", "value");
                aUrl = interpolate(aUrl, props);
                if (aUrl.isEmpty()) {
                    TextView tv = new TextView(context);
                    tv.setText("⚠ audio 缺少 url");
                    tv.setTextSize(12);
                    return tv;
                }
                org.json.JSONObject ap = new org.json.JSONObject();
                try {
                    ap.put("url", aUrl);
                    if (node.has("title")) ap.put("title", node.optString("title", ""));
                    if (node.has("artist")) ap.put("artist", node.optString("artist", ""));
                    if (node.has("autoPlay")) ap.put("autoPlay", node.optBoolean("autoPlay", false));
                } catch (org.json.JSONException ignored) {
                }
                com.oilquiz.app.ai.chat.component.ComponentData acd =
                        com.oilquiz.app.ai.chat.component.ComponentData.of("audio", ap);
                View av = com.oilquiz.app.ai.chat.component.ComponentRegistry.getInstance()
                        .render(context, acd);
                if (av == null) {
                    TextView tv = new TextView(context);
                    tv.setText("⚠ 音频加载失败");
                    tv.setTextSize(12);
                    return tv;
                }
                String akey = node.optString("key", "");
                if (!akey.isEmpty()) viewRefs.put(akey, av);
                return av;
            }
            case "progress": {
                ProgressBar pb = new ProgressBar(context, null,
                        android.R.attr.progressBarStyleHorizontal);
                pb.setMax(node.optInt("max", 100));
                pb.setProgress(node.optInt("progress", 0));
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, pb);
                return pb;
            }
            case "progress_ring": {
                // 环形进度：自绘圆环（Canvas）显示百分比
                int ringSize = dp(node.has("size") ? (int) Math.round(node.optDouble("size", 64)) : 64, density);
                int ringProgress = Math.max(0, Math.min(100, node.optInt("progress", 0)));
                int ringMax = Math.max(1, node.optInt("max", 100));
                View ring = new View(context) {
                    @Override
                    protected void onDraw(android.graphics.Canvas canvas) {
                        try {
                        super.onDraw(canvas);
                        float cx = getWidth() / 2f;
                        float cy = getHeight() / 2f;
                        float radius = Math.min(getWidth(), getHeight()) / 2f - dp(4, density);
                        android.graphics.Paint bgPaint = new android.graphics.Paint();
                        bgPaint.setStyle(android.graphics.Paint.Style.STROKE);
                        bgPaint.setStrokeWidth(dp(5, density));
                        bgPaint.setAntiAlias(true);
                        bgPaint.setColor(ComponentColors.border(context));
                        canvas.drawCircle(cx, cy, radius, bgPaint);
                        android.graphics.Paint fgPaint = new android.graphics.Paint();
                        fgPaint.setStyle(android.graphics.Paint.Style.STROKE);
                        fgPaint.setStrokeWidth(dp(5, density));
                        fgPaint.setAntiAlias(true);
                        fgPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                        fgPaint.setColor(ComponentColors.accent(context));
                        float sweep = 360f * ringProgress / ringMax;
                        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                                -90, sweep, false, fgPaint);
                        android.graphics.Paint textPaint = new android.graphics.Paint();
                        textPaint.setAntiAlias(true);
                        textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
                        textPaint.setColor(ComponentColors.textPrimary(context));
                        textPaint.setTextSize(dp(13, density));
                        android.graphics.Paint.FontMetrics fm = textPaint.getFontMetrics();
                        float baseline = cy - (fm.ascent + fm.descent) / 2f;
                        canvas.drawText(ringProgress + "%", cx, baseline, textPaint);
                    
                        } catch (Throwable t) {
                            logDrawFailure("chart#0", t);
                        }
                    }
                };
                ring.setLayoutParams(new android.widget.FrameLayout.LayoutParams(ringSize, ringSize));
                String ringKey = node.optString("key", "");
                if (!ringKey.isEmpty()) viewRefs.put(ringKey, new ProgressRingRef(ringProgress, ringMax));
                // 固定尺寸容器：外层 WRAP_CONTENT 会覆盖裸 View 尺寸 → 0 尺寸不可见
                LinearLayout ringWrap = new LinearLayout(context);
                ringWrap.setOrientation(LinearLayout.VERTICAL);
                ringWrap.addView(ring);
                return ringWrap;
            }
            case "table": {
                // 表格：headers=[列名], rows=[[值]] 或 rows=[{col:值}]；支持 striped/bordered
                LinearLayout tblWrap = new LinearLayout(context);
                tblWrap.setOrientation(LinearLayout.VERTICAL);
                JSONArray headers = node.optJSONArray("headers");
                JSONArray rows = node.optJSONArray("rows");
                boolean striped = node.optBoolean("striped", true);
                int rowCount = rows != null ? rows.length() : 0;
                int colCount = headers != null ? headers.length() : 0;
                // 表头
                if (headers != null && headers.length() > 0) {
                    LinearLayout headRow = new LinearLayout(context);
                    headRow.setOrientation(LinearLayout.HORIZONTAL);
                    android.graphics.drawable.GradientDrawable headBg = new android.graphics.drawable.GradientDrawable();
                    headBg.setColor(ComponentColors.fieldBg(context));
                    headBg.setCornerRadii(new float[]{dp(6, density), dp(6, density), 0, 0, 0, 0, dp(6, density), dp(6, density)});
                    headRow.setBackground(headBg);
                    for (int i = 0; i < headers.length(); i++) {
                        TextView hTv = new TextView(context);
                        hTv.setText(interpolate(headers.optString(i, ""), props));
                        hTv.setTextSize(12);
                        hTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                        hTv.setTextColor(ComponentColors.textPrimary(context));
                        hTv.setPadding(dp(8, density), dp(6, density), dp(8, density), dp(6, density));
                        headRow.addView(hTv, new LinearLayout.LayoutParams(0,
                                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    }
                    tblWrap.addView(headRow);
                }
                // 数据行
                if (rows != null) {
                    for (int r = 0; r < rows.length(); r++) {
                        Object rowObj = rows.opt(r);
                        final int rr = r;
                        LinearLayout dataRow = new LinearLayout(context);
                        dataRow.setOrientation(LinearLayout.HORIZONTAL);
                        if (striped && r % 2 == 1) {
                            android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
                            rowBg.setColor(ComponentColors.fieldBg(context));
                            dataRow.setBackground(rowBg);
                        }
                        java.util.List<String> cells = new java.util.ArrayList<>();
                        if (rowObj instanceof JSONArray) {
                            JSONArray arr = (JSONArray) rowObj;
                            for (int c = 0; c < arr.length(); c++) {
                                cells.add(String.valueOf(arr.opt(c)));
                            }
                        } else if (rowObj instanceof JSONObject) {
                            JSONObject jo = (JSONObject) rowObj;
                            if (headers != null) {
                                for (int c = 0; c < headers.length(); c++) {
                                    cells.add(jo.optString(headers.optString(c, ""), ""));
                                }
                            } else {
                                java.util.Iterator<String> ik = jo.keys();
                                while (ik.hasNext()) cells.add(String.valueOf(jo.opt(ik.next())));
                            }
                        }
                        for (int c = 0; c < cells.size(); c++) {
                            final int fc = c;
                            TextView cTv = new TextView(context);
                            cTv.setText(cells.get(c));
                            cTv.setTextSize(12);
                            cTv.setTextColor(ComponentColors.textSecondary(context));
                            cTv.setPadding(dp(8, density), dp(5, density), dp(8, density), dp(5, density));
                            dataRow.addView(cTv, new LinearLayout.LayoutParams(0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                        }
                        tblWrap.addView(dataRow);
                    }
                }
                if (rowCount == 0) {
                    TextView emptyTv = new TextView(context);
                    emptyTv.setText("（空表格）");
                    emptyTv.setTextSize(12);
                    emptyTv.setTextColor(ComponentColors.textTertiary(context));
                    tblWrap.addView(emptyTv);
                }
                return tblWrap;
            }
            case "steps": {
                // 步骤条：steps=[{title,description,status:done|current|todo|failed}]，横向节点+连线
                JSONArray stepsArr = node.optJSONArray("steps");
                if (stepsArr == null || stepsArr.length() == 0) {
                    TextView tv = new TextView(context);
                    tv.setText("（无步骤）");
                    tv.setTextSize(12);
                    return tv;
                }
                LinearLayout stepsWrap = new LinearLayout(context);
                stepsWrap.setOrientation(LinearLayout.HORIZONTAL);
                for (int i = 0; i < stepsArr.length(); i++) {
                    JSONObject st = stepsArr.optJSONObject(i);
                    if (st == null) continue;
                    LinearLayout stepCol = new LinearLayout(context);
                    stepCol.setOrientation(LinearLayout.VERTICAL);
                    stepCol.setGravity(Gravity.CENTER_HORIZONTAL);
                    stepCol.setPadding(dp(4, density), 0, dp(4, density), 0);
                    String stStatus = st.optString("status", i == 0 ? "current" : "todo");
                    boolean done = "done".equals(stStatus) || "success".equals(stStatus);
                    boolean current = "current".equals(stStatus) || "active".equals(stStatus);
                    boolean failed = "failed".equals(stStatus) || "error".equals(stStatus);
                    TextView dot = new TextView(context);
                    dot.setText(done ? "✓" : (failed ? "✕" : String.valueOf(i + 1)));
                    dot.setTextSize(12);
                    dot.setGravity(Gravity.CENTER);
                    int dotColor = failed ? ComponentColors.error(context)
                            : (current ? ComponentColors.accent(context)
                            : (done ? ComponentColors.success(context) : ComponentColors.border(context)));
                    android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
                    dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    dotBg.setColor(dotColor);
                    dot.setBackground(dotBg);
                    dot.setTextColor(ThemeColors.get(R.color.hc_ffffffff));
                    LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(24, density), dp(24, density));
                    stepCol.addView(dot, dotLp);
                    TextView stTitle = new TextView(context);
                    stTitle.setText(interpolate(st.optString("title", "步骤" + (i + 1)), props));
                    stTitle.setTextSize(11);
                    stTitle.setGravity(Gravity.CENTER);
                    stTitle.setTextColor(done || current ? ComponentColors.textPrimary(context)
                            : ComponentColors.textTertiary(context));
                    stepCol.addView(stTitle);
                    stepsWrap.addView(stepCol, new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    // 连接线（非最后一项）
                    if (i < stepsArr.length() - 1) {
                        View line = new View(context);
                        line.setBackgroundColor(done ? ComponentColors.success(context) : ComponentColors.border(context));
                        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(0, dp(2, density), 1f);
                        lineLp.topMargin = dp(11, density);
                        stepsWrap.addView(line, lineLp);
                    }
                }
                return stepsWrap;
            }
            case "timeline": {
                // 时间线：items=[{title,description,time,color,icon}] 纵向时间轴
                JSONArray tlItems = node.optJSONArray("items");
                if (tlItems == null || tlItems.length() == 0) {
                    TextView tv = new TextView(context);
                    tv.setText("（空时间线）");
                    tv.setTextSize(12);
                    return tv;
                }
                LinearLayout tlWrap = new LinearLayout(context);
                tlWrap.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < tlItems.length(); i++) {
                    JSONObject it = tlItems.optJSONObject(i);
                    if (it == null) continue;
                    LinearLayout tlRow = new LinearLayout(context);
                    tlRow.setOrientation(LinearLayout.HORIZONTAL);
                    tlRow.setPadding(0, dp(4, density), 0, dp(4, density));
                    // 左侧：节点 + 竖线
                    LinearLayout tlLeft = new LinearLayout(context);
                    tlLeft.setOrientation(LinearLayout.VERTICAL);
                    tlLeft.setGravity(Gravity.CENTER_HORIZONTAL);
                    TextView tlDot = new TextView(context);
                    String tlIcon = it.optString("icon", "");
                    tlDot.setText(tlIcon.isEmpty() ? "●" : tlIcon);
                    tlDot.setTextSize(10);
                    tlDot.setTextColor(ComponentColors.accent(context));
                    tlLeft.addView(tlDot);
                    if (i < tlItems.length() - 1) {
                        View tlLine = new View(context);
                        tlLine.setBackgroundColor(ComponentColors.border(context));
                        LinearLayout.LayoutParams tlLineLp = new LinearLayout.LayoutParams(dp(2, density), dp(30, density));
                        tlLeft.addView(tlLine, tlLineLp);
                    }
                    tlRow.addView(tlLeft, new LinearLayout.LayoutParams(dp(22, density),
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                    // 右侧：标题 + 时间 + 描述
                    LinearLayout tlRight = new LinearLayout(context);
                    tlRight.setOrientation(LinearLayout.VERTICAL);
                    tlRight.setPadding(dp(8, density), 0, 0, 0);
                    LinearLayout tlHead = new LinearLayout(context);
                    tlHead.setOrientation(LinearLayout.HORIZONTAL);
                    TextView tlTitle = new TextView(context);
                    tlTitle.setText(interpolate(it.optString("title", ""), props));
                    tlTitle.setTextSize(13);
                    tlTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    tlTitle.setTextColor(ComponentColors.textPrimary(context));
                    tlHead.addView(tlTitle);
                    String tlTime = it.optString("time", "");
                    if (!tlTime.isEmpty()) {
                        TextView tlTimeTv = new TextView(context);
                        tlTimeTv.setText(tlTime);
                        tlTimeTv.setTextSize(11);
                        tlTimeTv.setTextColor(ComponentColors.textTertiary(context));
                        tlHead.addView(tlTimeTv, new LinearLayout.LayoutParams(0,
                                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                        ((LinearLayout.LayoutParams) tlTimeTv.getLayoutParams()).gravity = Gravity.END;
                    }
                    tlRight.addView(tlHead);
                    String tlDesc = it.optString("description", it.optString("content", ""));
                    if (!tlDesc.isEmpty()) {
                        TextView tlDescTv = new TextView(context);
                        tlDescTv.setText(interpolate(tlDesc, props));
                        tlDescTv.setTextSize(12);
                        tlDescTv.setTextColor(ComponentColors.textSecondary(context));
                        tlRight.addView(tlDescTv);
                    }
                    tlRow.addView(tlRight, new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    tlWrap.addView(tlRow);
                }
                return tlWrap;
            }
            case "alert": {
                // 提示条：样式 success|warning|error|info，title/content 文本。
                // 样式字段名：alert_type / variant 优先；兼容旧写法 type（仅当值为
                // success/warning/error/info 时视为样式——JSON 中 type 已被控件类型
                // 消费，重复键时后者覆盖会导致 alert 被误判为未知类型 success 等，
                // 故样式必须用独立字段名，不能与控件类型共用 type）。
                String aType = node.optString("alert_type", "");
                if (aType.isEmpty()) aType = node.optString("variant", "");
                if (aType.isEmpty()) {
                    String legacy = node.optString("type", "info");
                    if ("success".equals(legacy) || "warning".equals(legacy)
                            || "error".equals(legacy) || "info".equals(legacy)) {
                        aType = legacy;
                    } else {
                        aType = "info";
                    }
                }
                int aColor;
                String aIcon;
                switch (aType) {
                    case "success": aColor = ComponentColors.success(context); aIcon = "✅"; break;
                    case "warning": aColor = ComponentColors.warning(context); aIcon = "⚠️"; break;
                    case "error": aColor = ComponentColors.error(context); aIcon = "❌"; break;
                    default: aColor = ComponentColors.accent(context); aIcon = "ℹ️"; break;
                }
                LinearLayout aWrap = new LinearLayout(context);
                aWrap.setOrientation(LinearLayout.VERTICAL);
                android.graphics.drawable.GradientDrawable aBg = new android.graphics.drawable.GradientDrawable();
                aBg.setColor(ComponentColors.fieldBg(context));
                aBg.setCornerRadius(dp(8, density));
                aBg.setStroke(dp(1, density), aColor);
                aWrap.setBackground(aBg);
                aWrap.setPadding(dp(10, density), dp(8, density), dp(10, density), dp(8, density));
                String aTitle = interpolate(node.optString("title", node.optString("text", "")), props);
                if (!aTitle.isEmpty()) {
                    TextView aTitleTv = new TextView(context);
                    aTitleTv.setText(aIcon + " " + aTitle);
                    aTitleTv.setTextSize(13);
                    aTitleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    aTitleTv.setTextColor(aColor);
                    aWrap.addView(aTitleTv);
                }
                String aContent = interpolate(node.optString("content", node.optString("message", "")), props);
                if (!aContent.isEmpty()) {
                    TextView aContentTv = new TextView(context);
                    aContentTv.setText(aContent);
                    aContentTv.setTextSize(12);
                    aContentTv.setTextColor(ComponentColors.textSecondary(context));
                    aWrap.addView(aContentTv);
                }
                return aWrap;
            }
            case "stat":
            case "metric": {
                // 指标卡：label + value(+unit) + 可选 sub（对比/说明），可设 color
                // metric 是 stat 的别名（2026-11-15）：公式只在这两类的 value 位置生效。
                LinearLayout statWrap = new LinearLayout(context);
                statWrap.setOrientation(LinearLayout.VERTICAL);
                android.graphics.drawable.GradientDrawable statBg = new android.graphics.drawable.GradientDrawable();
                statBg.setColor(ComponentColors.fieldBg(context));
                statBg.setCornerRadius(dp(10, density));
                statWrap.setBackground(statBg);
                statWrap.setPadding(dp(12, density), dp(10, density), dp(12, density), dp(10, density));
                String statLabel = interpolate(node.optString("label", ""), props);
                if (!statLabel.isEmpty()) {
                    TextView statLabelTv = new TextView(context);
                    statLabelTv.setText(statLabel);
                    statLabelTv.setTextSize(11);
                    statLabelTv.setTextColor(ComponentColors.textTertiary(context));
                    statWrap.addView(statLabelTv);
                }
                LinearLayout statValRow = new LinearLayout(context);
                statValRow.setOrientation(LinearLayout.HORIZONTAL);
                statValRow.setGravity(Gravity.BOTTOM);
                Object statValueRaw = node.opt("value");
                int statColor = node.has("color") ? parseColor(context, node.optString("color", ""))
                        : ComponentColors.textPrimary(context);
                TextView statValTv = new TextView(context);
                if (LayoutFormula.isBinding(statValueRaw) || LayoutFormula.isFormula(statValueRaw)) {
                    // 绑定/公式：登记进作用域，输入变化时原地重算；非法公式显示 "—"
                    LayoutBindingScope statScope = LayoutBindingScope.from(viewRefs);
                    String statValue = statScope != null
                            ? statScope.bind(statValTv, statValueRaw)
                            : LayoutFormula.resolveDisplay(statValueRaw, null);
                    statValTv.setText(statValue);
                } else {
                    String statValue = interpolate(
                            statValueRaw != null ? String.valueOf(statValueRaw) : "", props);
                    statValTv.setText(statValue.isEmpty() ? "-" : statValue);
                }
                statValTv.setTextSize(24);
                statValTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                statValTv.setTextColor(statColor);
                statValRow.addView(statValTv);
                String statUnit = interpolate(node.optString("unit", ""), props);
                if (!statUnit.isEmpty()) {
                    TextView statUnitTv = new TextView(context);
                    statUnitTv.setText(statUnit);
                    statUnitTv.setTextSize(12);
                    statUnitTv.setTextColor(ComponentColors.textSecondary(context));
                    statUnitTv.setPadding(dp(4, density), dp(2, density), 0, dp(2, density));
                    statValRow.addView(statUnitTv);
                }
                statWrap.addView(statValRow);
                String statSub = interpolate(node.optString("sub", node.optString("description", "")), props);
                if (!statSub.isEmpty()) {
                    TextView statSubTv = new TextView(context);
                    statSubTv.setText(statSub);
                    statSubTv.setTextSize(11);
                    statSubTv.setTextColor(ComponentColors.textTertiary(context));
                    statSubTv.setPadding(0, dp(2, density), 0, 0);
                    statWrap.addView(statSubTv);
                }
                return statWrap;
            }
            case "empty": {
                // 空态：icon + title + description（列表/内容为空时的占位）
                LinearLayout emptyWrap = new LinearLayout(context);
                emptyWrap.setOrientation(LinearLayout.VERTICAL);
                emptyWrap.setGravity(Gravity.CENTER_HORIZONTAL);
                emptyWrap.setPadding(dp(16, density), dp(20, density), dp(16, density), dp(20, density));
                String eIcon = node.optString("icon", "📭");
                if (!eIcon.isEmpty()) {
                    TextView eIconTv = new TextView(context);
                    eIconTv.setText(eIcon);
                    eIconTv.setTextSize(36);
                    eIconTv.setGravity(Gravity.CENTER);
                    emptyWrap.addView(eIconTv);
                }
                String eTitle = interpolate(node.optString("title", node.optString("text", "暂无内容")), props);
                TextView eTitleTv = new TextView(context);
                eTitleTv.setText(eTitle);
                eTitleTv.setTextSize(14);
                eTitleTv.setGravity(Gravity.CENTER);
                eTitleTv.setTextColor(ComponentColors.textSecondary(context));
                eTitleTv.setPadding(0, dp(8, density), 0, 0);
                emptyWrap.addView(eTitleTv);
                String eDesc = interpolate(node.optString("description", ""), props);
                if (!eDesc.isEmpty()) {
                    TextView eDescTv = new TextView(context);
                    eDescTv.setText(eDesc);
                    eDescTv.setTextSize(12);
                    eDescTv.setGravity(Gravity.CENTER);
                    eDescTv.setTextColor(ComponentColors.textTertiary(context));
                    eDescTv.setPadding(0, dp(4, density), 0, 0);
                    emptyWrap.addView(eDescTv);
                }
                return emptyWrap;
            }
            case "notice": {
                // 通知条（横向，常驻）：icon + text + 可选 action 按钮
                LinearLayout nWrap = new LinearLayout(context);
                nWrap.setOrientation(LinearLayout.HORIZONTAL);
                nWrap.setGravity(Gravity.CENTER_VERTICAL);
                android.graphics.drawable.GradientDrawable nBg = new android.graphics.drawable.GradientDrawable();
                nBg.setColor(ComponentColors.accentOverlay(context));
                nBg.setCornerRadius(dp(8, density));
                nWrap.setBackground(nBg);
                nWrap.setPadding(dp(10, density), dp(8, density), dp(10, density), dp(8, density));
                String nIcon = node.optString("icon", "🔔");
                if (!nIcon.isEmpty()) {
                    TextView nIconTv = new TextView(context);
                    nIconTv.setText(nIcon);
                    nIconTv.setTextSize(14);
                    nIconTv.setPadding(0, 0, dp(8, density), 0);
                    nWrap.addView(nIconTv);
                }
                TextView nTextTv = new TextView(context);
                nTextTv.setText(interpolate(node.optString("text", node.optString("content", "")), props));
                nTextTv.setTextSize(12);
                nTextTv.setTextColor(ComponentColors.textPrimary(context));
                nWrap.addView(nTextTv, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                String nAction = node.optString("action", "");
                String nActionLabel = node.optString("action_label", "查看");
                if (!nAction.isEmpty()) {
                    TextView nActionTv = new TextView(context);
                    nActionTv.setText(nActionLabel);
                    nActionTv.setTextSize(12);
                    nActionTv.setTextColor(ComponentColors.accent(context));
                    nActionTv.setTag(nAction);
                    wireActionClick(context, nActionTv, node, props, nActionLabel);
                    nActionTv.setPadding(dp(8, density), 0, 0, 0);
                    String nKey = node.optString("key", "");
                    if (!nKey.isEmpty()) viewRefs.put(nKey, nActionTv);
                    nWrap.addView(nActionTv);
                }
                return nWrap;
            }
            case "line_chart": {
                // 折线图（Canvas 自绘）：categories=[x轴], series=[{name,data:[数值],color?}]
                final int chartH = dp(node.has("height") ? (int) Math.round(node.optDouble("height", 180)) : 180, density);
                final int chartW = dp(320, density);
                JSONArray lcCategories = node.optJSONArray("categories");
                JSONArray lcSeries = node.optJSONArray("series");
                if (lcSeries == null || lcSeries.length() == 0) {
                    TextView tv = new TextView(context);
                    tv.setText("（无数据）");
                    tv.setTextSize(12);
                    return tv;
                }
                final java.util.List<JSONObject> lcSeriesList = new java.util.ArrayList<>();
                for (int i = 0; i < lcSeries.length(); i++) {
                    JSONObject s = lcSeries.optJSONObject(i);
                    if (s != null) lcSeriesList.add(s);
                }
                final int lcCatCount = lcCategories != null ? lcCategories.length() : 0;
                View lcView = new View(context) {
                    @Override
                    protected void onDraw(android.graphics.Canvas canvas) {
                        try {
                        super.onDraw(canvas);
                        int w = getWidth() > 0 ? getWidth() : chartW;
                        int h = getHeight() > 0 ? getHeight() : chartH;
                        int padL = dp(34, density), padB = dp(22, density), padT = dp(14, density), padR = dp(8, density);
                        int plotW = w - padL - padR;
                        int plotH = h - padT - padB;
                        if (plotW <= 0 || plotH <= 0) return;
                        // 收集所有系列数据求 min/max
                        float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
                        for (JSONObject s : lcSeriesList) {
                            JSONArray data = s.optJSONArray("data");
                            if (data == null) continue;
                            for (int i = 0; i < data.length(); i++) {
                                float v = (float) data.optDouble(i, 0);
                                if (v < minV) minV = v;
                                if (v > maxV) maxV = v;
                            }
                        }
                        if (minV > maxV) { minV = 0; maxV = 100; }
                        if (minV == maxV) { maxV = minV + 1; }
                        // 网格线 + Y 轴刻度
                        android.graphics.Paint gridPaint = new android.graphics.Paint();
                        gridPaint.setColor(ComponentColors.border(context));
                        gridPaint.setStrokeWidth(dp(0.6f, density));
                        android.graphics.Paint textPaint = new android.graphics.Paint();
                        textPaint.setAntiAlias(true);
                        textPaint.setTextSize(dp(9, density));
                        textPaint.setColor(ComponentColors.textTertiary(context));
                        int gridLines = 4;
                        for (int gi = 0; gi <= gridLines; gi++) {
                            float y = padT + plotH * gi / gridLines;
                            canvas.drawLine(padL, y, w - padR, y, gridPaint);
                            float val = maxV - (maxV - minV) * gi / gridLines;
                            String label = formatNumber(val);
                            canvas.drawText(label, dp(2, density), y + dp(3, density), textPaint);
                        }
                        // 折线
                        int maxPoints = 0;
                        for (JSONObject s : lcSeriesList) {
                            JSONArray data = s.optJSONArray("data");
                            if (data != null && data.length() > maxPoints) maxPoints = data.length();
                        }
                        if (maxPoints <= 0) maxPoints = 1;
                        int si = 0;
                        for (JSONObject s : lcSeriesList) {
                            JSONArray data = s.optJSONArray("data");
                            if (data == null) continue;
                            int color = parseColor(context, s.optString("color", ""));
                            if (s.has("color")) {
                                color = parseColor(context, s.optString("color", ""));
                            } else {
                                color = ComponentColors.chartColor(context, si);
                            }
                            android.graphics.Paint linePaint = new android.graphics.Paint();
                            linePaint.setAntiAlias(true);
                            linePaint.setStrokeWidth(dp(2, density));
                            linePaint.setColor(color);
                            android.graphics.Path path = new android.graphics.Path();
                            android.graphics.Paint dotPaint = new android.graphics.Paint();
                            dotPaint.setAntiAlias(true);
                            dotPaint.setColor(color);
                            for (int i = 0; i < data.length(); i++) {
                                float v = (float) data.optDouble(i, 0);
                                float x = padL + plotW * (maxPoints == 1 ? 0.5f : i / (float) (maxPoints - 1));
                                float y = padT + plotH * (1 - (v - minV) / (maxV - minV));
                                if (i == 0) path.moveTo(x, y);
                                else path.lineTo(x, y);
                                canvas.drawCircle(x, y, dp(2.5f, density), dotPaint);
                            }
                            canvas.drawPath(path, linePaint);
                            si++;
                        }
                        // X 轴分类
                        if (lcCategories != null && lcCategories.length() > 0) {
                            for (int i = 0; i < lcCategories.length(); i++) {
                                float x = padL + plotW * (maxPoints == 1 ? 0.5f : i / (float) (maxPoints - 1));
                                String cat = String.valueOf(lcCategories.opt(i));
                                if (cat.length() > 4) cat = cat.substring(0, 4) + "…";
                                canvas.drawText(cat, x - dp(12, density), h - dp(6, density), textPaint);
                            }
                        }
                    
                        } catch (Throwable t) {
                            logDrawFailure("chart#1", t);
                        }
                    }
                };
                lcView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, chartH));
                // 标题
                String lcTitle = interpolate(node.optString("title", ""), props);
                LinearLayout lcWrap = new LinearLayout(context);
                lcWrap.setOrientation(LinearLayout.VERTICAL);
                if (!lcTitle.isEmpty()) {
                    TextView lcTitleTv = new TextView(context);
                    lcTitleTv.setText(lcTitle);
                    lcTitleTv.setTextSize(13);
                    lcTitleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    lcTitleTv.setTextColor(ComponentColors.textPrimary(context));
                    lcTitleTv.setPadding(0, 0, 0, dp(4, density));
                    lcWrap.addView(lcTitleTv);
                }
                lcWrap.addView(lcView);
                // 始终返回固定高度容器：外层挂载会重设 LayoutParams 为 WRAP_CONTENT，
                // 裸自绘 View 会塌陷为 0 高不可见（onDraw 提前 return）；容器固定 chartH 保证可见
                return lcWrap;
            }
            case "bar_chart": {
                // 柱状图（Canvas 自绘）：categories=[x轴], series=[{name,data:[数值]}]
                final int bcH = dp(node.has("height") ? (int) Math.round(node.optDouble("height", 180)) : 180, density);
                JSONArray bcCategories = node.optJSONArray("categories");
                JSONArray bcSeries = node.optJSONArray("series");
                if (bcSeries == null || bcSeries.length() == 0) {
                    TextView tv = new TextView(context);
                    tv.setText("（无数据）");
                    tv.setTextSize(12);
                    return tv;
                }
                final java.util.List<JSONObject> bcSeriesList = new java.util.ArrayList<>();
                for (int i = 0; i < bcSeries.length(); i++) {
                    JSONObject s = bcSeries.optJSONObject(i);
                    if (s != null) bcSeriesList.add(s);
                }
                View bcView = new View(context) {
                    @Override
                    protected void onDraw(android.graphics.Canvas canvas) {
                        try {
                        super.onDraw(canvas);
                        int w = getWidth();
                        int h = getHeight();
                        int padL = dp(34, density), padB = dp(22, density), padT = dp(14, density), padR = dp(8, density);
                        int plotW = w - padL - padR;
                        int plotH = h - padT - padB;
                        if (plotW <= 0 || plotH <= 0) return;
                        float minV = 0, maxV = -Float.MAX_VALUE;
                        int maxPoints = 0;
                        for (JSONObject s : bcSeriesList) {
                            JSONArray data = s.optJSONArray("data");
                            if (data == null) continue;
                            for (int i = 0; i < data.length(); i++) {
                                float v = (float) data.optDouble(i, 0);
                                if (v > maxV) maxV = v;
                            }
                            if (data.length() > maxPoints) maxPoints = data.length();
                        }
                        if (maxV <= 0) maxV = 100;
                        android.graphics.Paint gridPaint = new android.graphics.Paint();
                        gridPaint.setColor(ComponentColors.border(context));
                        gridPaint.setStrokeWidth(dp(0.6f, density));
                        android.graphics.Paint textPaint = new android.graphics.Paint();
                        textPaint.setAntiAlias(true);
                        textPaint.setTextSize(dp(9, density));
                        textPaint.setColor(ComponentColors.textTertiary(context));
                        int gridLines = 4;
                        for (int gi = 0; gi <= gridLines; gi++) {
                            float y = padT + plotH * gi / gridLines;
                            canvas.drawLine(padL, y, w - padR, y, gridPaint);
                            float val = maxV * (gridLines - gi) / gridLines;
                            canvas.drawText(formatNumber(val), dp(2, density), y + dp(3, density), textPaint);
                        }
                        if (maxPoints <= 0) maxPoints = 1;
                        float groupW = plotW / (float) maxPoints;
                        float barW = groupW / (bcSeriesList.size() + 0.5f);
                        int si = 0;
                        for (JSONObject s : bcSeriesList) {
                            JSONArray data = s.optJSONArray("data");
                            if (data == null) continue;
                            int color = ComponentColors.chartColor(context, si);
                            android.graphics.Paint barPaint = new android.graphics.Paint();
                            barPaint.setAntiAlias(true);
                            barPaint.setColor(color);
                            for (int i = 0; i < data.length(); i++) {
                                float v = (float) data.optDouble(i, 0);
                                float barH = plotH * (v / maxV);
                                float x = padL + groupW * i + groupW * 0.15f + barW * si;
                                float y = padT + plotH - barH;
                                canvas.drawRoundRect(x, y, x + barW, padT + plotH,
                                        dp(2, density), dp(2, density), barPaint);
                            }
                            si++;
                        }
                        if (bcCategories != null && bcCategories.length() > 0) {
                            for (int i = 0; i < bcCategories.length(); i++) {
                                float x = padL + groupW * i + groupW / 2;
                                String cat = String.valueOf(bcCategories.opt(i));
                                if (cat.length() > 4) cat = cat.substring(0, 4) + "…";
                                canvas.drawText(cat, x - dp(10, density), h - dp(6, density), textPaint);
                            }
                        }
                    
                        } catch (Throwable t) {
                            logDrawFailure("chart#2", t);
                        }
                    }
                };
                bcView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, bcH));
                // 固定高度容器：外层 WRAP_CONTENT 会覆盖裸 View 高度 → 0 高不可见
                LinearLayout bcWrap2 = new LinearLayout(context);
                bcWrap2.setOrientation(LinearLayout.VERTICAL);
                bcWrap2.addView(bcView);
                return bcWrap2;
            }
            case "pie_chart": {
                // 饼图/环形图（Canvas 自绘）：data=[{label,value,color?}]
                JSONArray pieData = node.optJSONArray("data");
                if (pieData == null || pieData.length() == 0) {
                    TextView tv = new TextView(context);
                    tv.setText("（无数据）");
                    tv.setTextSize(12);
                    return tv;
                }
                final int pieSize = dp(node.has("size") ? (int) Math.round(node.optDouble("size", 160)) : 160, density);
                View pieView = new View(context) {
                    @Override
                    protected void onDraw(android.graphics.Canvas canvas) {
                        try {
                        super.onDraw(canvas);
                        float cx = getWidth() / 2f;
                        float cy = getHeight() / 2f - dp(10, density); // 上移为底部图例留空间
                        // 半径缩小：给底部图例预留（图例每行 14dp）
                        int legendRows = pieData != null ? pieData.length() : 0;
                        float availH = getHeight() - dp(16, density) - legendRows * dp(14, density);
                        float radius = Math.min(getWidth() / 2f, availH / 2f) - dp(6, density);
                        if (radius <= 0) return;
                        float total = 0;
                        for (int i = 0; i < pieData.length(); i++) {
                            total += pieValue(pieData, i);
                        }
                        if (total <= 0) return;
                        float startAngle = -90;
                        android.graphics.Paint paint = new android.graphics.Paint();
                        paint.setAntiAlias(true);
                        for (int i = 0; i < pieData.length(); i++) {
                            JSONObject seg = pieData.optJSONObject(i);
                            float value = pieValue(pieData, i);
                            if (seg == null && value <= 0) continue;  // 纯数字形态：label 用序号
                            float sweep = 360f * value / total;
                            // seg 可能为 null（data 是纯数字数组）：取色也要兜底，否则又是一次 onDraw NPE
                            int color = (seg != null && seg.has("color"))
                                    ? parseColor(context, seg.optString("color", ""))
                                    : ComponentColors.chartColor(context, i);
                            paint.setColor(color);
                            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                                    startAngle, sweep, true, paint);
                            startAngle += sweep;
                        }
                        // 图例（底部）
                        android.graphics.Paint textPaint = new android.graphics.Paint();
                        textPaint.setAntiAlias(true);
                        textPaint.setTextSize(dp(10, density));
                        textPaint.setColor(ComponentColors.textSecondary(context));
                        float ly = radius * 1.15f + dp(14, density);
                        for (int i = 0; i < pieData.length(); i++) {
                            JSONObject seg = pieData.optJSONObject(i);
                            if (seg == null) continue;
                            float v = (float) seg.optDouble("value", 0);
                            int color = seg.has("color") ? parseColor(context, seg.optString("color", ""))
                                    : ComponentColors.chartColor(context, i);
                            android.graphics.Paint lp = new android.graphics.Paint();
                            lp.setColor(color);
                            canvas.drawRect(dp(8, density), ly - dp(7, density),
                                    dp(18, density), ly - dp(1, density), lp);
                            String label = seg.optString("label", "项" + (i + 1));
                            String pct = total > 0 ? " " + Math.round(v * 100 / total) + "%" : "";
                            canvas.drawText(label + pct, dp(24, density), ly, textPaint);
                            ly += dp(14, density);
                        }
                    
                        } catch (Throwable t) {
                            logDrawFailure("chart#3", t);
                        }
                    }
                };
                pieView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, pieSize));
                // 固定高度容器：外层 WRAP_CONTENT 会覆盖裸 View 高度 → 0 高不可见
                LinearLayout pieWrap2 = new LinearLayout(context);
                pieWrap2.setOrientation(LinearLayout.VERTICAL);
                pieWrap2.addView(pieView);
                return pieWrap2;
            }
            case "sparkline": {
                // 迷你趋势线：data=[数值]，单色细线 + 填充
                JSONArray spData = node.optJSONArray("data");
                final int spH = dp(32, density);
                if (spData == null || spData.length() == 0) {
                    TextView tv = new TextView(context);
                    tv.setText("-");
                    tv.setTextSize(11);
                    return tv;
                }
                final java.util.List<Float> spValues = new java.util.ArrayList<>();
                for (int i = 0; i < spData.length(); i++) {
                    spValues.add((float) spData.optDouble(i, 0));
                }
                View spView = new View(context) {
                    @Override
                    protected void onDraw(android.graphics.Canvas canvas) {
                        try {
                        super.onDraw(canvas);
                        int w = getWidth();
                        int h = getHeight();
                        if (w <= 0 || spValues.size() < 2) return;
                        float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
                        for (float v : spValues) {
                            if (v < minV) minV = v;
                            if (v > maxV) maxV = v;
                        }
                        if (minV == maxV) { maxV = minV + 1; }
                        android.graphics.Paint linePaint = new android.graphics.Paint();
                        linePaint.setAntiAlias(true);
                        linePaint.setStrokeWidth(dp(1.5f, density));
                        linePaint.setColor(ComponentColors.accent(context));
                        android.graphics.Path path = new android.graphics.Path();
                        for (int i = 0; i < spValues.size(); i++) {
                            float x = w * i / (float) (spValues.size() - 1);
                            float y = h * (1 - (spValues.get(i) - minV) / (maxV - minV));
                            if (i == 0) path.moveTo(x, y);
                            else path.lineTo(x, y);
                        }
                        canvas.drawPath(path, linePaint);
                        // 填充
                        android.graphics.Paint fillPaint = new android.graphics.Paint();
                        fillPaint.setStyle(android.graphics.Paint.Style.FILL);
                        fillPaint.setColor(ComponentColors.accent(context));
                        fillPaint.setAlpha(30);
                        android.graphics.Path fillPath = new android.graphics.Path(path);
                        fillPath.lineTo(w, h);
                        fillPath.lineTo(0, h);
                        fillPath.close();
                        canvas.drawPath(fillPath, fillPaint);
                    
                        } catch (Throwable t) {
                            logDrawFailure("chart#4", t);
                        }
                    }
                };
                spView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, spH));
                // 固定高度容器：外层 WRAP_CONTENT 会覆盖裸 View 高度 → 0 高不可见
                LinearLayout spWrap2 = new LinearLayout(context);
                spWrap2.setOrientation(LinearLayout.VERTICAL);
                spWrap2.addView(spView);
                return spWrap2;
            }
            case "select": {
                JSONArray opts = node.optJSONArray("options");
                java.util.List<String> items = new java.util.ArrayList<>();
                if (opts != null) {
                    for (int i = 0; i < opts.length(); i++) {
                        Object o = opts.opt(i);
                        items.add(o instanceof JSONObject ? ((JSONObject) o).optString("label",
                                String.valueOf(((JSONObject) o).opt("value"))) : String.valueOf(o));
                    }
                }
                if (items.isEmpty()) items.add("请选择");
                String sel = node.optString("value", "");
                if (sel.isEmpty()) sel = node.optString("default", "");
                String key = node.optString("key", "");
                if (sel.isEmpty() && !key.isEmpty() && props != null) {
                    sel = props.optString(key, "");
                }
                // M2 exposed dropdown：outlined 输入框 + 下拉箭头，值仍按 EditText 口径收集
                TextInputLayout selectTil = CanvasControls.dropdown(context,
                        interpolate(node.optString("label", ""), props), items, sel);
                EditText selectEt = CanvasControls.editOf(selectTil);
                if (!key.isEmpty() && selectEt != null) viewRefs.put(key, selectEt);
                return selectTil;
            }
            case "switch": {
                SwitchMaterial sw = CanvasControls.switchView(context,
                        interpolate(node.optString("text", ""), props), node.optBoolean("checked", false));
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, sw);
                return attachLabel(context, node, props, sw, density);
            }
            case "toggle": {
                // 分段选择：M2 ChipGroup 单选（替代手绘胶囊），选中态由 Chip 状态色表达
                JSONArray tgOpts = node.optJSONArray("options");
                if (tgOpts == null || tgOpts.length() == 0) {
                    tgOpts = new JSONArray();
                    try {
                        tgOpts.put("是").put("否");
                    } catch (Exception ignored) {
                    }
                }
                String tgSel = node.optString("value", node.optString("default", ""));
                final com.google.android.material.chip.ChipGroup tgGroup =
                        CanvasControls.chipGroup(context, true);
                final String[] tgCur = {tgSel};
                int checkedId = 0;
                for (int i = 0; i < tgOpts.length(); i++) {
                    Object o = tgOpts.opt(i);
                    String label = o instanceof JSONObject ? ((JSONObject) o).optString("label",
                            String.valueOf(((JSONObject) o).opt("value"))) : String.valueOf(o);
                    final String val = o instanceof JSONObject ? ((JSONObject) o).optString("value", label) : label;
                    com.google.android.material.chip.Chip chip =
                            CanvasControls.chip(context, label, true, val.equals(tgSel));
                    chip.setId(android.view.View.generateViewId());
                    chip.setTag(val);
                    tgGroup.addView(chip);
                    if (val.equals(tgSel)) checkedId = chip.getId();
                }
                if (checkedId != 0) tgGroup.check(checkedId);
                tgGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
                    if (checkedIds.isEmpty()) return;
                    android.view.View chip = group.findViewById(checkedIds.get(0));
                    if (chip != null && chip.getTag() != null) {
                        tgCur[0] = String.valueOf(chip.getTag());
                    }
                });
                String tgKey = node.optString("key", "");
                if (!tgKey.isEmpty()) viewRefs.put(tgKey, new ToggleRef(tgCur));
                return attachLabel(context, node, props, tgGroup, density);
            }
            case "dropdown": {
                // 下拉选择（同 select，语义化命名；支持 key/options/value/default）
                JSONArray ddOpts = node.optJSONArray("options");
                java.util.List<String> ddItems = new java.util.ArrayList<>();
                if (ddOpts != null) {
                    for (int i = 0; i < ddOpts.length(); i++) {
                        Object o = ddOpts.opt(i);
                        ddItems.add(o instanceof JSONObject ? ((JSONObject) o).optString("label",
                                String.valueOf(((JSONObject) o).opt("value"))) : String.valueOf(o));
                    }
                }
                if (ddItems.isEmpty()) ddItems.add("请选择");
                String ddSel = node.optString("value", "");
                if (ddSel.isEmpty()) ddSel = node.optString("default", "");
                TextInputLayout ddTil = CanvasControls.dropdown(context,
                        interpolate(node.optString("label", ""), props), ddItems, ddSel);
                EditText ddEt = CanvasControls.editOf(ddTil);
                String ddKey = node.optString("key", "");
                if (!ddKey.isEmpty() && ddEt != null) viewRefs.put(ddKey, ddEt);
                return ddTil;
            }
            case "stepper": {
                // 步进器：M2 描边按钮 − 数值 +（key 收集数值，min/max/step 控制）
                LinearLayout stWrap = new LinearLayout(context);
                stWrap.setOrientation(LinearLayout.HORIZONTAL);
                stWrap.setGravity(Gravity.CENTER_VERTICAL);
                int stMin = node.optInt("min", 0);
                int stMax = node.optInt("max", 100);
                int stStep = Math.max(1, node.optInt("step", 1));
                final int[] stVal = {node.optInt("value", node.optInt("default", stMin))};
                if (stVal[0] < stMin) stVal[0] = stMin;
                if (stVal[0] > stMax) stVal[0] = stMax;
                // secondaryButton 会打“画布自有交互”标记，宿主不会把它当提交按钮
                com.google.android.material.button.MaterialButton stMinus =
                        CanvasControls.secondaryButton(context, "−");
                stWrap.addView(stMinus);
                final TextView stNum = new TextView(context);
                stNum.setText(String.valueOf(stVal[0]));
                Tokens.applyText(stNum, Tokens.TEXT_BODY_LG, Tokens.Weight.MEDIUM,
                        ComponentColors.textPrimary(context));
                stNum.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams stNumLp = new LinearLayout.LayoutParams(
                        dp(50, density), LinearLayout.LayoutParams.WRAP_CONTENT);
                stNumLp.setMarginStart(dp(8, density));
                stNumLp.setMarginEnd(dp(8, density));
                stWrap.addView(stNum, stNumLp);
                com.google.android.material.button.MaterialButton stPlus =
                        CanvasControls.secondaryButton(context, "+");
                stWrap.addView(stPlus);
                stMinus.setOnClickListener(v -> {
                    if (stVal[0] - stStep >= stMin) {
                        stVal[0] -= stStep;
                        stNum.setText(String.valueOf(stVal[0]));
                    }
                });
                stPlus.setOnClickListener(v -> {
                    if (stVal[0] + stStep <= stMax) {
                        stVal[0] += stStep;
                        stNum.setText(String.valueOf(stVal[0]));
                    }
                });
                String stKey = node.optString("key", "");
                if (!stKey.isEmpty()) viewRefs.put(stKey, new StepperRef(stVal));
                return attachLabel(context, node, props, stWrap, density);
            }
            case "slider_range": {
                // 双滑块范围：M2 RangeSlider（替代两个 SeekBar）
                LinearLayout srWrap = new LinearLayout(context);
                srWrap.setOrientation(LinearLayout.VERTICAL);
                int srMin = node.optInt("min", 0);
                int srMax = node.optInt("max", 100);
                int srStep = Math.max(1, node.optInt("step", 1));
                if (srMax <= srMin) srMax = srMin + 100;
                srMax = srMin + ((srMax - srMin) / srStep) * srStep;
                int srLow = Math.max(srMin, node.optInt("low", srMin));
                int srHigh = Math.min(srMax, node.optInt("high", srMax));
                srLow = srMin + Math.round((float) (srLow - srMin) / srStep) * srStep;
                srHigh = srMin + Math.round((float) (srHigh - srMin) / srStep) * srStep;
                if (srLow > srHigh) {
                    int tmp = srLow;
                    srLow = srHigh;
                    srHigh = tmp;
                }
                RangeSlider rangeSlider = new RangeSlider(context);
                rangeSlider.setValueFrom(srMin);
                rangeSlider.setValueTo(srMax);
                rangeSlider.setStepSize(srStep);
                rangeSlider.setValues((float) srLow, (float) srHigh);
                srWrap.addView(rangeSlider);
                final int[] srLowV = {srLow};
                final int[] srHighV = {srHigh};
                final TextView srLabel = new TextView(context);
                Tokens.applyText(srLabel, Tokens.TEXT_FOOTNOTE, Tokens.Weight.REGULAR,
                        ComponentColors.textSecondary(context));
                srLabel.setText(srLow + " - " + srHigh);
                srWrap.addView(srLabel);
                rangeSlider.addOnChangeListener((s, v, fromUser) -> {
                    java.util.List<Float> vals = s.getValues();
                    if (vals.size() >= 2) {
                        srLowV[0] = Math.round(vals.get(0));
                        srHighV[0] = Math.round(vals.get(1));
                        srLabel.setText(srLowV[0] + " - " + srHighV[0]);
                    }
                });
                String srKey = node.optString("key", "");
                if (!srKey.isEmpty()) viewRefs.put(srKey, new RangeRef(srLowV, srHighV));
                return attachLabel(context, node, props, srWrap, density);
            }
            case "file": {
                // 文件路径输入：M2 输入框（label 缺失时默认"文件路径"），key 收集路径
                if (!node.has("label") && !node.has("hint")) {
                    try {
                        node.put("label", "文件路径");
                    } catch (org.json.JSONException ignored) {
                    }
                }
                return buildTextInput(context, node, props, viewRefs, "输入文件路径或URL",
                        0, false, false);
            }
            case "qrcode": {
                // 二维码（zxing）：content=内容, size=边长dp, color/bg_color
                String qrContent = interpolate(node.optString("content", node.optString("text", "")), props);
                if (qrContent.isEmpty()) {
                    TextView tv = new TextView(context);
                    tv.setText("（二维码内容为空）");
                    tv.setTextSize(11);
                    return tv;
                }
                int qrSize = dp(node.has("size") ? (int) Math.round(node.optDouble("size", 160)) : 160, density);
                int qrColor = node.has("color") ? parseColor(context, node.optString("color", ""))
                        : ThemeColors.get(R.color.hc_ff000000);
                try {
                    java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.HashMap<>();
                    hints.put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8");
                    hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);
                    com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
                    com.google.zxing.common.BitMatrix matrix = writer.encode(
                            qrContent, com.google.zxing.BarcodeFormat.QR_CODE, qrSize, qrSize, hints);
                    android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                            qrSize, qrSize, android.graphics.Bitmap.Config.ARGB_8888);
                    for (int x = 0; x < qrSize; x++) {
                        for (int y = 0; y < qrSize; y++) {
                            bmp.setPixel(x, y, matrix.get(x, y) ? qrColor : ThemeColors.get(R.color.hc_ffffffff));
                        }
                    }
                    // 用 LinearLayout 包裹 + 显式固定尺寸：
                    // 外层容器（聊天流 bindComponents / addChildren）会重设 LayoutParams 为
                    // WRAP_CONTENT/MATCH_PARENT，若直接返回裸 ImageView 其高度可能塌陷为 0（二维码空白）。
                    // 容器固定宽高可保证二维码始终按 size 显示。
                    LinearLayout qrWrap = new LinearLayout(context);
                    qrWrap.setOrientation(LinearLayout.VERTICAL);
                    android.graphics.drawable.GradientDrawable qrBox = new android.graphics.drawable.GradientDrawable();
                    qrBox.setColor(ThemeColors.get(R.color.hc_ffffffff));
                    qrBox.setCornerRadius(dp(8, density));
                    qrWrap.setBackground(qrBox);
                    qrWrap.setPadding(dp(8, density), dp(8, density), dp(8, density), dp(8, density));
                    ImageView qrImg = new ImageView(context);
                    qrImg.setImageBitmap(bmp);
                    qrImg.setAdjustViewBounds(true);
                    qrImg.setLayoutParams(new LinearLayout.LayoutParams(qrSize, qrSize));
                    qrWrap.addView(qrImg);
                    return qrWrap;
                } catch (Exception e) {
                    android.util.Log.w(TAG, "二维码生成失败: " + e.getMessage());
                    TextView tv = new TextView(context);
                    tv.setText("二维码生成失败: " + e.getMessage());
                    tv.setTextSize(11);
                    return tv;
                }
            }
            case "barcode": {
                // 条形码（Code128，zxing）
                String bcContent = interpolate(node.optString("content", node.optString("text", "")), props);
                if (bcContent.isEmpty()) {
                    TextView tv = new TextView(context);
                    tv.setText("（条形码内容为空）");
                    tv.setTextSize(11);
                    return tv;
                }
                int bcW = dp(node.has("width") ? (int) Math.round(node.optDouble("width", 260)) : 260, density);
                int bcH = dp(node.has("height") ? (int) Math.round(node.optDouble("height", 80)) : 80, density);
                try {
                    com.google.zxing.oned.Code128Writer writer = new com.google.zxing.oned.Code128Writer();
                    com.google.zxing.common.BitMatrix matrix = writer.encode(
                            bcContent, com.google.zxing.BarcodeFormat.CODE_128, bcW, bcH);
                    android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                            bcW, bcH, android.graphics.Bitmap.Config.ARGB_8888);
                    for (int x = 0; x < bcW; x++) {
                        for (int y = 0; y < bcH; y++) {
                            bmp.setPixel(x, y, matrix.get(x, y) ? ThemeColors.get(R.color.hc_ff000000) : ThemeColors.get(R.color.hc_ffffffff));
                        }
                    }
                    LinearLayout bcWrap = new LinearLayout(context);
                    bcWrap.setOrientation(LinearLayout.VERTICAL);
                    android.graphics.drawable.GradientDrawable bcBox = new android.graphics.drawable.GradientDrawable();
                    bcBox.setColor(ThemeColors.get(R.color.hc_ffffffff));
                    bcBox.setCornerRadius(dp(6, density));
                    bcWrap.setBackground(bcBox);
                    bcWrap.setPadding(dp(8, density), dp(8, density), dp(8, density), dp(4, density));
                    ImageView bcImg = new ImageView(context);
                    bcImg.setImageBitmap(bmp);
                    bcImg.setAdjustViewBounds(true);
                    bcImg.setLayoutParams(new LinearLayout.LayoutParams(bcW, bcH));
                    bcWrap.addView(bcImg);
                    TextView bcLabel = new TextView(context);
                    bcLabel.setText(bcContent);
                    bcLabel.setTextSize(11);
                    bcLabel.setGravity(Gravity.CENTER);
                    bcLabel.setTextColor(ComponentColors.textSecondary(context));
                    bcWrap.addView(bcLabel);
                    return bcWrap;
                } catch (Exception e) {
                    android.util.Log.w(TAG, "条形码生成失败: " + e.getMessage());
                    TextView tv = new TextView(context);
                    tv.setText("条形码生成失败: " + e.getMessage());
                    tv.setTextSize(11);
                    return tv;
                }
            }
            case "countdown": {
                // 倒计时：seconds 秒，倒计时结束显示"时间到"；label 前缀可选
                final int totalSec = Math.max(1, node.optInt("seconds", node.optInt("value", 60)));
                final String cdLabel = interpolate(node.optString("label", ""), props);
                final TextView cdTv = new TextView(context);
                cdTv.setTextSize(20);
                cdTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                cdTv.setTextColor(ComponentColors.accent(context));
                cdTv.setGravity(Gravity.CENTER);
                cdTv.setText((cdLabel.isEmpty() ? "" : cdLabel + " ") + formatCountdown(totalSec));
                final android.os.Handler cdHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                final int[] remaining = {totalSec};
                final Runnable cdRunnable = new Runnable() {
                    @Override
                    public void run() {
                        remaining[0]--;
                        if (remaining[0] > 0) {
                            cdTv.setText((cdLabel.isEmpty() ? "" : cdLabel + " ") + formatCountdown(remaining[0]));
                            cdHandler.postDelayed(this, 1000);
                        } else {
                            cdTv.setText(cdLabel.isEmpty() ? "时间到" : cdLabel + " 时间到");
                            cdTv.setTextColor(ComponentColors.success(context));
                        }
                    }
                };
                cdHandler.postDelayed(cdRunnable, 1000);
                cdTv.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) { }
                    @Override public void onViewDetachedFromWindow(View v) {
                        cdHandler.removeCallbacks(cdRunnable);
                    }
                });
                return cdTv;
            }
            case "calendar": {
                // 日历（当前月网格）：月份导航 + 周几表头 + 日期格，选中高亮
                final TextView calTitle = new TextView(context);
                calTitle.setTextSize(14);
                calTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                calTitle.setTextColor(ComponentColors.textPrimary(context));
                calTitle.setGravity(Gravity.CENTER);
                final java.util.Calendar calShown = java.util.Calendar.getInstance();
                try {
                    String initDate = node.optString("value", "");
                    if (!initDate.isEmpty()) {
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                                "yyyy-MM-dd", java.util.Locale.getDefault());
                        java.util.Date d = sdf.parse(initDate);
                        if (d != null) calShown.setTime(d);
                    }
                } catch (Exception ignored) {
                }
                final LinearLayout calGrid = new LinearLayout(context);
                calGrid.setOrientation(LinearLayout.VERTICAL);
                final String[] calSel = {node.optString("value", node.optString("selected", ""))};
                final String calKey = node.optString("key", "");
                // 重新渲染当前月
                final Runnable[] renderMonth = new Runnable[1];
                renderMonth[0] = () -> {
                    calTitle.setText(new java.text.SimpleDateFormat("yyyy年M月",
                            java.util.Locale.getDefault()).format(calShown.getTime()));
                    calGrid.removeAllViews();
                    // 周几表头
                    LinearLayout dowRow = new LinearLayout(context);
                    dowRow.setOrientation(LinearLayout.HORIZONTAL);
                    String[] dows = {"日", "一", "二", "三", "四", "五", "六"};
                    for (String d : dows) {
                        TextView dTv = new TextView(context);
                        dTv.setText(d);
                        dTv.setTextSize(11);
                        dTv.setGravity(Gravity.CENTER);
                        dTv.setTextColor(ComponentColors.textTertiary(context));
                        dowRow.addView(dTv, new LinearLayout.LayoutParams(0,
                                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    }
                    calGrid.addView(dowRow);
                    int year = calShown.get(java.util.Calendar.YEAR);
                    int month = calShown.get(java.util.Calendar.MONTH);
                    java.util.Calendar first = java.util.Calendar.getInstance();
                    first.clear();
                    first.set(year, month, 1);
                    int firstDow = first.get(java.util.Calendar.DAY_OF_WEEK) - 1; // 周日=0
                    int daysInMonth = first.getActualMaximum(java.util.Calendar.DAY_OF_MONTH);
                    java.util.Calendar today = java.util.Calendar.getInstance();
                    int todayY = today.get(java.util.Calendar.YEAR);
                    int todayM = today.get(java.util.Calendar.MONTH);
                    int todayD = today.get(java.util.Calendar.DAY_OF_MONTH);
                    int cellIndex = 0;
                    for (int row = 0; row < 6; row++) {
                        LinearLayout weekRow = new LinearLayout(context);
                        weekRow.setOrientation(LinearLayout.HORIZONTAL);
                        boolean hasCell = false;
                        for (int col = 0; col < 7; col++) {
                            int dayNum = cellIndex - firstDow + 1;
                            if (dayNum < 1 || dayNum > daysInMonth) {
                                TextView emptyCell = new TextView(context);
                                emptyCell.setTextSize(12);
                                weekRow.addView(emptyCell, new LinearLayout.LayoutParams(0,
                                        dp(36, density), 1f));
                            } else {
                                hasCell = true;
                                final int fDay = dayNum;
                                TextView dayTv = new TextView(context);
                                dayTv.setText(String.valueOf(dayNum));
                                dayTv.setTextSize(12);
                                dayTv.setGravity(Gravity.CENTER);
                                boolean isToday = (year == todayY && month == todayM && dayNum == todayD);
                                String dateStr = String.format(java.util.Locale.US, "%04d-%02d-%02d",
                                        year, month + 1, dayNum);
                                boolean isSel = dateStr.equals(calSel[0]);
                                if (isSel) {
                                    android.graphics.drawable.GradientDrawable selBg = new android.graphics.drawable.GradientDrawable();
                                    selBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                                    selBg.setColor(ComponentColors.accent(context));
                                    dayTv.setBackground(selBg);
                                    dayTv.setTextColor(ThemeColors.get(R.color.hc_ffffffff));
                                } else if (isToday) {
                                    dayTv.setTextColor(ComponentColors.accent(context));
                                    dayTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                                } else {
                                    dayTv.setTextColor(ComponentColors.textPrimary(context));
                                }
                                dayTv.setOnClickListener(v -> {
                                    calSel[0] = dateStr;
                                    renderMonth[0].run();
                                });
                                weekRow.addView(dayTv, new LinearLayout.LayoutParams(0,
                                        dp(36, density), 1f));
                            }
                            cellIndex++;
                        }
                        if (hasCell) calGrid.addView(weekRow);
                    }
                };
                renderMonth[0].run();
                // 月份导航
                LinearLayout calNav = new LinearLayout(context);
                calNav.setOrientation(LinearLayout.HORIZONTAL);
                calNav.setGravity(Gravity.CENTER_VERTICAL);
                TextView prevBtn = new TextView(context);
                prevBtn.setText("◀");
                prevBtn.setTextSize(16);
                prevBtn.setTextColor(ComponentColors.accent(context));
                prevBtn.setPadding(dp(10, density), dp(4, density), dp(10, density), dp(4, density));
                prevBtn.setOnClickListener(v -> {
                    calShown.add(java.util.Calendar.MONTH, -1);
                    renderMonth[0].run();
                });
                calNav.addView(prevBtn);
                calNav.addView(calTitle, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView nextBtn = new TextView(context);
                nextBtn.setText("▶");
                nextBtn.setTextSize(16);
                nextBtn.setTextColor(ComponentColors.accent(context));
                nextBtn.setPadding(dp(10, density), dp(4, density), dp(10, density), dp(4, density));
                nextBtn.setOnClickListener(v -> {
                    calShown.add(java.util.Calendar.MONTH, 1);
                    renderMonth[0].run();
                });
                calNav.addView(nextBtn);
                LinearLayout calWrap = new LinearLayout(context);
                calWrap.setOrientation(LinearLayout.VERTICAL);
                calWrap.addView(calNav);
                calWrap.addView(calGrid);
                if (!calKey.isEmpty()) viewRefs.put(calKey, new DateRef(calSel));
                return calWrap;
            }
            case "breadcrumb": {
                // 面包屑：items=[{label,action}] 或字符串数组，箭头分隔
                JSONArray bcItems = node.optJSONArray("items");
                if (bcItems == null || bcItems.length() == 0) {
                    TextView tv = new TextView(context);
                    tv.setText("（空面包屑）");
                    tv.setTextSize(12);
                    return tv;
                }
                LinearLayout bcWrap = new LinearLayout(context);
                bcWrap.setOrientation(LinearLayout.HORIZONTAL);
                bcWrap.setGravity(Gravity.CENTER_VERTICAL);
                for (int i = 0; i < bcItems.length(); i++) {
                    Object o = bcItems.opt(i);
                    String label = o instanceof JSONObject ? ((JSONObject) o).optString("label",
                            String.valueOf(((JSONObject) o).opt("text"))) : String.valueOf(o);
                    String action = o instanceof JSONObject ? ((JSONObject) o).optString("action", "") : "";
                    TextView crumbTv = new TextView(context);
                    crumbTv.setText(label);
                    crumbTv.setTextSize(12);
                    boolean last = (i == bcItems.length() - 1);
                    crumbTv.setTextColor(last ? ComponentColors.textPrimary(context)
                            : ComponentColors.accent(context));
                    if (!last) {
                        crumbTv.setTag(action);
                        crumbTv.setPadding(dp(2, density), 0, dp(2, density), 0);
                        String bKey = node.optString("key", "");
                        if (!bKey.isEmpty() && action.isEmpty()) {
                            // 面包屑整体 key（点击最后一级返回）
                            crumbTv.setOnClickListener(v -> { });
                        }
                    }
                    bcWrap.addView(crumbTv);
                    if (!last) {
                        TextView sepTv = new TextView(context);
                        sepTv.setText("›");
                        sepTv.setTextSize(12);
                        sepTv.setTextColor(ComponentColors.textTertiary(context));
                        sepTv.setPadding(dp(4, density), 0, dp(4, density), 0);
                        bcWrap.addView(sepTv);
                    }
                }
                return bcWrap;
            }
            case "divider":
            case "separator": {
                View v = new View(context);
                v.setBackgroundColor(com.oilquiz.app.ai.chat.component.ComponentColors.border(context));
                v.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1, density)));
                return v;
            }
            case "divider_v": {
                // 竖向分割线（row 内使用）
                View v = new View(context);
                v.setBackgroundColor(com.oilquiz.app.ai.chat.component.ComponentColors.border(context));
                v.setLayoutParams(new LinearLayout.LayoutParams(dp(1, density),
                        LinearLayout.LayoutParams.MATCH_PARENT));
                return v;
            }
            default: {
                // 已注册组件类型/插件作为节点 type 嵌套：展开其 render.layout 递归渲染
                View expanded = tryExpandRegisteredType(context, node, props, viewRefs, depth, defines, type);
                if (expanded != null) return expanded;
                // 第四条（2026-11-15）：ComponentRegistry 里注册的卡片类型（key_value/compare/alert_card…）
                // 作为 layout 子节点时内嵌渲染成卡片视图，不再落到「这部分无法显示」。
                View nestedCard = tryRenderRegisteredCard(context, node, viewRefs, type);
                if (nestedCard != null) return nestedCard;
                // 降级口径（抄 dsh-Go）：单个子节点画不出来只换成灰色占位，
                // 其余照常渲染 —— 不让一个不认识的 type 毁掉整张卡。
                android.util.Log.w(TAG, "未知控件 type=" + type
                        + "（可用 register_type 注册组件类型后嵌套，或用 use 引用 layout 模板）");
                return placeholder(context, "这部分无法显示");
            }
        }
    }

    /**
     * 已注册组件类型/插件作为节点 type 嵌套时的展开渲染：
     * - UIComponentTypeRegistry（ui_component action=register_type 注册）：取 render.layout 递归渲染，
     *   或 render.card 走内置卡片渲染（ComponentRegistry）。
     * - UIComponentPluginManager（ui_component_plugin 注册）：取 render.layout 递归渲染，
     *   或 render.card 走内置卡片渲染。
     * - 未注册类型但节点自带 layout（顶层 layout 字段或 render={layout:...}）：现场展开渲染
     *   （等效临时注册，与 create 路径的"未注册类型降级 layout 直渲"一致）。
     * 节点自身 props（node.props）合并进渲染 props（覆盖模板内同名 {key} 占位）。
     * 无法展开返回 null（调用方显示未知控件提示）。
     */
    private static View tryExpandRegisteredType(Context context, JSONObject node, JSONObject props,
                                                Map<String, Object> viewRefs, int depth,
                                                JSONObject defines, String type) {
        try {
            if (depth > 8) return null; // 防嵌套展开过深
            org.json.JSONObject render = null;
            String kind = "";
            // 优先级：注册类型 > 插件（两者都注册时以 register_type 为准，与创建逻辑一致）
            if (com.oilquiz.app.ai.tool.UIComponentTypeRegistry.getInstance(context).hasType(type)) {
                org.json.JSONObject typeDef = com.oilquiz.app.ai.tool.UIComponentTypeRegistry
                        .getInstance(context).getType(type);
                if (typeDef != null) {
                    render = typeDef.optJSONObject("render");
                    kind = "注册类型";
                }
            } else if (com.oilquiz.app.ai.tool.UIComponentPluginManager.getInstance(context).hasPlugin(type)) {
                org.json.JSONObject plugin = com.oilquiz.app.ai.tool.UIComponentPluginManager
                        .getInstance(context).getPlugin(type);
                if (plugin != null) {
                    render = plugin.optJSONObject("render");
                    kind = "插件";
                }
            } else {
                // 未注册类型：回退检查节点自带 layout（现场定义，等效临时注册）
                Object selfLayout = node.has("layout") ? node.opt("layout") : null;
                if (selfLayout == null && node.has("render")) {
                    Object rObj = node.opt("render");
                    if (rObj instanceof org.json.JSONObject) {
                        selfLayout = ((org.json.JSONObject) rObj).opt("layout");
                    } else if (rObj != null) {
                        try {
                            org.json.JSONObject ro = new org.json.JSONObject(String.valueOf(rObj));
                            selfLayout = ro.opt("layout");
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (selfLayout != null) {
                    JSONObject lo = selfLayout instanceof JSONObject
                            ? (JSONObject) selfLayout : new JSONObject(String.valueOf(selfLayout));
                    render = new org.json.JSONObject();
                    render.put("layout", lo);
                    kind = "现场定义";
                }
            }
            if (render == null) return null;
            // render.layout：递归渲染控件树
            if (render.has("layout")) {
                JSONObject layout = render.optJSONObject("layout");
                if (layout == null) return null;
                // layout 可能是 {"root": {...}} 完整结构或直接节点树：取 root（与渲染入口一致）
                JSONObject layoutRoot = layout.optJSONObject("root");
                JSONObject target = layoutRoot != null ? layoutRoot : layout;
                // 展开渲染前把节点自身 props 合并进渲染 props（覆盖模板内同名 {key} 占位）
                JSONObject merged = new JSONObject();
                if (props != null) {
                    java.util.Iterator<String> ik = props.keys();
                    while (ik.hasNext()) {
                        String k = ik.next();
                        merged.put(k, props.get(k));
                    }
                }
                JSONObject nodeProps = node.optJSONObject("props");
                if (nodeProps != null) {
                    java.util.Iterator<String> uk = nodeProps.keys();
                    while (uk.hasNext()) {
                        String k = uk.next();
                        merged.put(k, nodeProps.get(k));
                    }
                }
                // 节点顶层非布局属性（text/value 等）并入
                java.util.Iterator<String> nk = node.keys();
                while (nk.hasNext()) {
                    String k = nk.next();
                    if ("type".equals(k) || "props".equals(k) || "key".equals(k)
                            || "width".equals(k) || "height".equals(k) || "margin".equals(k)
                            || "weight".equals(k) || "flex".equals(k) || "align".equals(k)
                            || "alignItems".equals(k)) continue;
                    merged.put(k, node.get(k));
                }
                return buildNode(context, target, merged, viewRefs, depth + 1, defines);
            }
            // render.card：走内置卡片渲染（ComponentRegistry）
            String card = render.optString("card", "");
            if (!card.isEmpty()) {
                org.json.JSONObject cardProps = new org.json.JSONObject();
                try {
                    org.json.JSONObject rp = render.optJSONObject("props");
                    if (rp != null) {
                        java.util.Iterator<String> ck = rp.keys();
                        while (ck.hasNext()) {
                            String k = ck.next();
                            cardProps.put(k, rp.get(k));
                        }
                    }
                    JSONObject nodeProps = node.optJSONObject("props");
                    if (nodeProps != null) {
                        java.util.Iterator<String> uk = nodeProps.keys();
                        while (uk.hasNext()) {
                            String k = uk.next();
                            cardProps.put(k, nodeProps.get(k));
                        }
                    }
                } catch (Exception ignored) {
                }
                android.widget.LinearLayout wrap = new android.widget.LinearLayout(context);
                wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
                android.view.View cv = com.oilquiz.app.ai.chat.component.ComponentRegistry
                        .getInstance().render(context, com.oilquiz.app.ai.chat.component.ComponentData
                                .of(card, cardProps));
                if (cv != null) {
                    wrap.addView(cv, new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                    return wrap;
                }
                TextView tv = new TextView(context);
                tv.setText("⚠ " + kind + " " + type + " 卡片渲染失败");
                tv.setTextSize(12);
                return tv;
            }
            return null;
        } catch (Throwable t) {
            android.util.Log.w("NativeLayoutRenderer", "展开注册类型失败 " + type + ": " + t.getMessage());
            return null;
        }
    }

    /**
     * 已注册卡片类型（{@code ComponentRegistry}）作为 layout 子节点时的内嵌渲染（2026-11-15）。
     *
     * <p>为什么需要第四条：{@code key_value}/{@code compare}/{@code alert_card} 这类卡在
     * {@code ComponentRegistry} 里注册，但既不是 register_type 的 layout 模板、也不是插件
     * render.layout，节点又不自带 layout —— 此前直接落进「这部分无法显示」（真机
     * {@code {"type":"key_value","items":[["k1","v1"]]}} 的 k1 不出现）。</p>
     *
     * <p>口径：</p>
     * <ul>
     *   <li>判定：{@code ComponentTypes.resolve(type)} 归一后 {@code hasType} 为真（别名
     *       keyValue/dsh_command 一并认）；</li>
     *   <li>props：节点 props 对象打底、节点顶层属性覆盖；结构键 type/children/key 与父容器排版键
     *       （width/height/margin/weight/flex/align/alignItems/spacing）不进 props。卡片数据字段
     *       items 原样带过去，<b>不会被当 children 递归</b>（本渲染器只认 children）；</li>
     *   <li>绑定/公式：卡片只渲染一次、没有输入重算通道，props 里的 {"$":"data.x"} /
     *       {"=":"…"} 先按当前 props.data 求一次静态值；</li>
     *   <li>外壳：直接返回卡片自己的 View（CardShell 自带圆角/标题），不再额外套一层卡片外壳；
     *       宽高交给父容器 addChildren 的 LayoutParams（列里铺满、行里自适应）；</li>
     *   <li>深度：嵌套卡片深度超过 {@link #MAX_NESTED_CARD_DEPTH} → 灰字「嵌套太深」（卡片自己
     *       可能再走 layout 渲染，如 layout 卡，不设限会递归）；卡片节点本身只计 1 个节点预算；</li>
     *   <li>失败：render 返回 null 或抛异常 → 灰字「这部分无法显示」，日志带 type + 异常。</li>
     * </ul>
     *
     * @return 卡片 View；该 type 不在卡片注册表里返回 null（调用方继续走未知控件灰字）
     */
    private static View tryRenderRegisteredCard(Context context, JSONObject node,
                                                Map<String, Object> viewRefs, String type) {
        String resolvedType = com.oilquiz.app.ai.chat.component.ComponentTypes.resolve(type);
        com.oilquiz.app.ai.chat.component.ComponentRegistry registry =
                com.oilquiz.app.ai.chat.component.ComponentRegistry.getInstance();
        if (resolvedType == null || resolvedType.isEmpty() || !registry.hasType(resolvedType)) {
            return null;
        }
        // 深度闸：先判再进卡片（卡片内部可能回调本渲染器，例如 layout 卡）
        int[] nest = CARD_NEST_DEPTH.get();
        if (nest[0] > MAX_NESTED_CARD_DEPTH) {
            android.util.Log.w(TAG, "嵌套卡片过深 type=" + type + " 深度=" + nest[0]
                    + "（上限 " + MAX_NESTED_CARD_DEPTH + "），降级灰字");
            return placeholder(context, "嵌套太深");
        }
        JSONObject cardProps = collectCardProps(node);
        // 绑定/公式求一次值（卡片没有"输入变化重算"的通道，只能给静态值）
        LayoutBindingScope scope = LayoutBindingScope.from(viewRefs);
        if (scope != null) {
            try {
                Object resolvedProps = scope.resolveCardValue(cardProps, 0);
                if (resolvedProps instanceof JSONObject) cardProps = (JSONObject) resolvedProps;
            } catch (Throwable t) {
                android.util.Log.w(TAG, "嵌套卡片 props 绑定求值失败，按原值渲染 type=" + type
                        + ": " + t.getMessage());
            }
        }
        nest[0] += 1;
        View cardView;
        try {
            cardView = registry.render(context,
                    com.oilquiz.app.ai.chat.component.ComponentData.of(resolvedType, cardProps));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "嵌套卡片渲染异常 type=" + type + "（归一后 " + resolvedType + "）",
                    t);
            return placeholder(context, "这部分无法显示");
        } finally {
            nest[0] -= 1;
        }
        if (cardView == null) {
            android.util.Log.w(TAG, "嵌套卡片渲染返回 null type=" + type
                    + "（归一后 " + resolvedType + "）");
            return placeholder(context, "这部分无法显示");
        }
        // key 语义与其它节点一致：有 key 就登记 viewRefs（卡片是 ViewGroup，collectValues 会跳过它）
        String key = node.optString("key", "");
        if (!key.isEmpty() && viewRefs != null) viewRefs.put(key, cardView);
        return cardView;
    }

    /**
     * 卡片节点的 props：节点 props 对象打底、节点顶层属性覆盖（节点顶层优先）。
     * 数据字段（items/rows/series…）原样带过去，只有 type/children/key 与父容器排版键被排除。
     */
    private static JSONObject collectCardProps(JSONObject node) {
        JSONObject props = new JSONObject();
        if (node == null) return props;
        try {
            JSONObject nodeProps = node.optJSONObject("props");
            if (nodeProps != null) {
                java.util.Iterator<String> it = nodeProps.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    props.put(k, nodeProps.get(k));
                }
            }
            java.util.Iterator<String> nk = node.keys();
            while (nk.hasNext()) {
                String k = nk.next();
                if (isCardNodeOnlyKey(k)) continue;
                props.put(k, node.get(k));
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "嵌套卡片 props 组装失败: " + t.getMessage());
        }
        return props;
    }

    /** 卡片节点上不属于卡片 props 的键：结构键 + 父容器排版键（数据字段 items 不在此列） */
    private static boolean isCardNodeOnlyKey(String k) {
        if (k == null) return true;
        switch (k) {
            case "type":
            case "children":
            case "key":
            case "props":
            case "width":
            case "height":
            case "margin":
            case "weight":
            case "flex":
            case "align":
            case "alignItems":
            case "spacing":
                return true;
            default:
                return false;
        }
    }

    /** 当前是否深色模式（决定 HTML 基础样式的文字/背景配色） */
    private static boolean isNightMode(Context context) {        return (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** 容器子项对齐：alignItems 属性（column 内水平对齐 / row 内垂直对齐），兼容 alignItems 与 align 别名 */
    private static void applyContainerAlignment(LinearLayout container, JSONObject node, int orientation) {
        String ai = node.optString("alignItems", "");
        if (ai.isEmpty()) ai = node.optString("align", "");
        if (ai.isEmpty()) return;
        int g = 0;
        switch (ai.trim().toLowerCase()) {
            case "start":
            case "left":
                g = orientation == LinearLayout.HORIZONTAL
                        ? Gravity.CENTER_VERTICAL | Gravity.START : Gravity.START;
                break;
            case "center":
                g = orientation == LinearLayout.HORIZONTAL
                        ? Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL;
                break;
            case "end":
            case "right":
                g = orientation == LinearLayout.HORIZONTAL
                        ? Gravity.CENTER_VERTICAL | Gravity.END : Gravity.END;
                break;
            default:
                return;
        }
        container.setGravity(g);
    }

    /**
     * UI-06 统一 style 对象（容器级）：padding/radius/background 覆盖默认。
     * 节点顶层字段（含 style 展开后）读取：padding=整数 或 "t r b l" 或 4元素数组；
     * radius=圆角dp（整数）；background=颜色(#RRGGBB/#AARRGGBB/颜色名)。
     */
    /** 绘制期异常兜底：自绘图表在 onDraw 里读模型数据，任何形状问题都不该把整页带崩（视图片只记一次） */
    private static final java.util.Set<String> DRAW_FAIL_LOGGED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    private static void logDrawFailure(String what, Throwable t) {
        if (DRAW_FAIL_LOGGED.add(what)) {
            com.oilquiz.app.util.AILogger.w("NativeLayoutRenderer",
                    what + " onDraw 失败（已兜底，图表留白）：" + t);
        }
    }

    private static void applyContainerStyle(View v, JSONObject node, int density) {
        try {
            // padding：统一替换容器默认内边距
            if (node.has("padding")) {
                Object pad = node.opt("padding");
                int l, t, r, b;
                if (pad instanceof org.json.JSONArray) {
                    org.json.JSONArray pa = (org.json.JSONArray) pad;
                    if (pa.length() >= 4) {
                        l = dp((int) Math.round(pa.optDouble(0)), density);
                        t = dp((int) Math.round(pa.optDouble(1)), density);
                        r = dp((int) Math.round(pa.optDouble(2)), density);
                        b = dp((int) Math.round(pa.optDouble(3)), density);
                    } else if (pa.length() == 1) {
                        int p = dp((int) Math.round(pa.optDouble(0)), density);
                        l = t = r = b = p;
                    } else { return; }
                } else {
                    String ps = String.valueOf(pad).trim();
                    String[] parts = ps.split("\\s+");
                    if (parts.length == 4) {
                        l = dp(parseIntSafe(parts[0]), density); t = dp(parseIntSafe(parts[1]), density);
                        r = dp(parseIntSafe(parts[2]), density); b = dp(parseIntSafe(parts[3]), density);
                    } else if (parts.length == 1) {
                        int p = dp(parseIntSafe(parts[0]), density); l = t = r = b = p;
                    } else { return; }
                }
                v.setPadding(l, t, r, b);
            }
            // radius + background：圆角背景（有其一才设置，避免覆盖控件已有背景）
            boolean hasRadius = node.has("radius");
            boolean hasBg = node.has("background");
            boolean hasBorder = node.has("border") || node.has("border_width");
            if (hasRadius || hasBg || hasBorder) {
                int radiusDp = hasRadius ? dp(parseIntSafe(String.valueOf(node.opt("radius"))), density) : 0;
                int bgColor = 0;
                boolean bgOk = false;
                if (hasBg) {
                    int c = parseColorSafe(String.valueOf(node.opt("background")));
                    if (c != Integer.MIN_VALUE) { bgColor = c; bgOk = true; }
                }
                // UI-06 style 边框：border=宽度+颜色（如 "1 #CCCCCC"）或 border_width + border_color
                int borderWidth = 0;
                int borderColor = 0xFFFFFFFF;
                boolean borderOk = false;
                if (hasBorder) {
                    Object bObj = node.opt("border");
                    if (bObj != null) {
                        String bs = String.valueOf(bObj).trim();
                        if (bs.matches("\\d+(\\s+#?[0-9A-Fa-f]{6,8})?")) {
                            String[] bp = bs.split("\\s+");
                            borderWidth = parseIntSafe(bp[0]);
                            if (bp.length > 1) {
                                int c = parseColorSafe(bp[1]);
                                if (c != Integer.MIN_VALUE) { borderColor = c; borderOk = true; }
                            }
                        }
                    }
                    if (node.has("border_width")) {
                        borderWidth = parseIntSafe(String.valueOf(node.opt("border_width")));
                        int c = parseColorSafe(node.optString("border_color", ""));
                        if (c != Integer.MIN_VALUE) { borderColor = c; borderOk = true; }
                    }
                    if (borderWidth > 0) borderOk = true;
                }
                // 仅 radius（无 background/border）且控件已有背景（如 card 默认白底圆角）→ 跳过，保留原背景
                // MaterialCardView 例外：它的"背景"是卡片自身形状，radius/border 必须走卡片 API，
                // 直接 setBackground 会把卡片的形状/描边/圆角裁切全部破坏。
                if (v instanceof MaterialCardView) {
                    MaterialCardView card = (MaterialCardView) v;
                    if (bgOk) card.setCardBackgroundColor(bgColor);
                    if (hasRadius) card.setRadius(radiusDp);
                    if (borderOk && borderWidth > 0) {
                        card.setStrokeWidth(dp(borderWidth, density));
                        card.setStrokeColor(borderColor);
                    }
                    return;
                }
                if (hasRadius && !bgOk && !borderOk && v.getBackground() != null) {
                    return;
                }
                if (hasRadius || bgOk || borderOk) {
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    if (bgOk) gd.setColor(bgColor);
                    if (hasRadius) gd.setCornerRadius(radiusDp);
                    if (borderOk && borderWidth > 0) gd.setStroke(dp(borderWidth, density), borderColor);
                    v.setBackground(gd);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static int parseIntSafe(String s) {
        if (s == null) return 0;
        try { return (int) Math.round(Double.parseDouble(s.trim())); } catch (NumberFormatException e) { return 0; }
    }

    private static int parseColorSafe(String s) {
        if (s == null || s.isEmpty()) return Integer.MIN_VALUE;
        try { return android.graphics.Color.parseColor(s.trim()); } catch (IllegalArgumentException e) { return Integer.MIN_VALUE; }
    }

    /**
     * 给 layout 树里可点击的按钮/操作文字挂真实动作。
     *
     * <p>此前 button/notice 只 {@code setTag(action)}：弹窗链路（PythonToolManager）会在 render
     * 之后自己 setOnClickListener 覆盖掉 tag 语义，聊天流卡片却没人接管——于是 Agent 设计的
     * "打开微信""拨号"按钮点下去毫无反应（用户报"卡片点击后不能跳转应用"）。这里只认领
     * <b>明确可执行</b>的字段（link/url、copy、app/package、以及 {@link ComponentActions} 认识的
     * action），普通提交按钮（action 是自定义回调串）一律不挂，保持弹窗链路原样接管。</p>
     *
     * <p>action=callback/refresh 需要卡片有可寻址的 component_id（创建时带 props.actions 才有），
     * 取址口径与 layout/html 卡 submit 共用 {@link ComponentActions#resolveComponentId(JSONObject)}；
     * 没有则提示"需带 actions 创建"，不做静默失败。</p>
     */
    private static void wireActionClick(Context context, View target, JSONObject node,
                                        JSONObject props, String defaultLabel) {
        if (context == null || target == null || node == null) return;
        try {
            String link = interpolate(node.optString("link", node.optString("url", "")), props);
            String copy = interpolate(node.optString("copy", ""), props);
            String app = node.optString("app", "");
            String pkg = node.optString("package", "");
            String action = node.optString("action", "");
            String value = interpolate(node.optString("value", ""), props);
            boolean actionable = !link.isEmpty() || !copy.isEmpty() || !app.isEmpty() || !pkg.isEmpty()
                    || com.oilquiz.app.ai.chat.component.ComponentActions.isUsableTarget(value)
                    || "open_app".equals(action) || "launch_app".equals(action) || "app".equals(action)
                    || "am".equals(action) || "am_start".equals(action) || "dial".equals(action)
                    || "call".equals(action) || "sms".equals(action) || "email".equals(action)
                    || "map".equals(action) || "open_url".equals(action) || "url".equals(action)
                    || "copy".equals(action) || "refresh".equals(action) || "callback".equals(action);
            if (!actionable) return;
            // callback/refresh 缺 component_id 时不再在这里拦成一句 toast：
            // 按钮若还带 link/copy，统一交给 ComponentActions 先做真实动作；
            // 什么都没有时由 execute() 说明"没有回传地址"。
            JSONObject act = new JSONObject();
            act.put("label", defaultLabel == null ? "打开" : defaultLabel);
            if (!link.isEmpty()) act.put("link", link);
            if (!copy.isEmpty()) act.put("copy", copy);
            if (!app.isEmpty()) act.put("app", app);
            if (!pkg.isEmpty()) act.put("package", pkg);
            if (!action.isEmpty()) act.put("action", action);
            if (!value.isEmpty()) act.put("value", value);
            String cid = com.oilquiz.app.ai.chat.component.ComponentActions.resolveComponentId(props);
            if (cid != null && !cid.isEmpty()) act.put("component_id", cid);
            Runnable runner = com.oilquiz.app.ai.chat.component.ComponentActions.actionRunner(context, act);
            if (runner != null) target.setOnClickListener(v -> runner.run());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 给表单控件附加 label 标签：节点带 label 字段时，在控件上方渲染标签文字
     * （左对齐、12sp、secondary 色、底部 4dp 间距）。
     * 修复：input/tel/select/switch/slider 等控件带 label 时不显示标签，表单看起来"控件缺失"。
     */
    private static View attachLabel(Context context, JSONObject node, JSONObject props,
                                    View innerView, int density) {
        String label = interpolate(node.optString("label", ""), props);
        if (label.isEmpty()) return innerView;
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        TextView labelTv = new TextView(context);
        labelTv.setText(label);
        Tokens.applyText(labelTv, Tokens.TEXT_FOOTNOTE, Tokens.Weight.REGULAR,
                ComponentColors.textSecondary(context));
        labelTv.setPadding(0, 0, 0, dp(3, density));
        wrap.addView(labelTv);
        wrap.addView(innerView);
        return wrap;
    }

    /**
     * 画布文本输入的统一构建（2026-11-18）：M2 {@link TextInputLayout} + {@link EditText}。
     *
     * <p>与旧的裸 {@code new EditText} 相比：浮动标签、错误态、密码可见切换、主题描边都交给
     * 框架；layout JSON 契约不变（label/hint/value/key 语义一致），{@code viewRefs} 仍登记
     * {@link EditText}，所以 {@link #collectValues}/{@link #applyValues} 不受影响。</p>
     */
    private static TextInputLayout buildTextInput(Context context, JSONObject node, JSONObject props,
                                                  Map<String, Object> viewRefs, String defaultHint,
                                                  int inputType, boolean multiline,
                                                  boolean passwordToggle) {
        String label = interpolate(node.optString("label", ""), props);
        String hint = interpolate(node.optString("hint", defaultHint), props);
        String value = node.has("value") ? interpolate(node.optString("value", ""), props)
                : (props != null ? props.optString(node.optString("key", ""), "") : "");
        TextInputLayout til = CanvasControls.input(context, label, hint, value, inputType,
                multiline, passwordToggle);
        EditText et = CanvasControls.editOf(til);
        if (et != null) {
            setupInputView(et);
            String key = node.optString("key", "");
            if (!key.isEmpty() && viewRefs != null) {
                viewRefs.put(key, et);
            }
        }
        return til;
    }

    /** 日期/时间节点的初始值（value 优先，其次 default；支持 {占位符} 插值）。 */
    private static String pickerInitial(JSONObject node, JSONObject props) {
        String v = node.has("value") ? node.optString("value", "") : node.optString("default", "");
        return interpolate(v, props);
    }

    /** 日期/时间节点的标签：有 label 用 label，否则用"点击选择…"占位。 */
    private static String pickerLabel(JSONObject node, JSONObject props, String fallback) {
        String label = interpolate(node.optString("label", ""), props);
        return label.isEmpty() ? fallback : label;
    }

    /** 公历日 → MaterialDatePicker 需要的 UTC 日毫秒（该日 00:00 UTC）。 */
    private static long utcDayMillis(int year, int month, int day) {
        java.util.Calendar utc = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(year, month, day);
        return utc.getTimeInMillis();
    }

    /** MaterialDatePicker 回传的 UTC 日毫秒 → yyyy-MM-dd。 */
    private static String formatUtcDay(long millis) {
        java.util.Calendar utc = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(millis);
        return String.format(java.util.Locale.getDefault(), "%04d-%02d-%02d",
                utc.get(java.util.Calendar.YEAR), utc.get(java.util.Calendar.MONTH) + 1,
                utc.get(java.util.Calendar.DAY_OF_MONTH));
    }

    /**
     * 日期选择：宿主是 {@link androidx.fragment.app.FragmentActivity} 且状态允许时用 M2
     * {@code MaterialDatePicker}，否则退回系统 {@code DatePickerDialog}（保证功能不丢）。
     */
    private static void showDatePicker(final Context context, final String current,
                                       final java.util.function.Consumer<String> onPicked) {
        if (context == null || onPicked == null) return;
        final java.util.Calendar cal = java.util.Calendar.getInstance();
        try {
            if (current != null && current.length() >= 10) {
                java.util.Date d = new java.text.SimpleDateFormat("yyyy-MM-dd",
                        java.util.Locale.getDefault()).parse(current.substring(0, 10));
                if (d != null) cal.setTime(d);
            }
        } catch (Exception ignored) {
        }
        if (context instanceof androidx.fragment.app.FragmentActivity) {
            androidx.fragment.app.FragmentActivity fa =
                    (androidx.fragment.app.FragmentActivity) context;
            if (!fa.getSupportFragmentManager().isStateSaved()) {
                try {
                    com.google.android.material.datepicker.MaterialDatePicker<Long> picker =
                            com.google.android.material.datepicker.MaterialDatePicker.Builder
                                    .datePicker()
                                    .setTitleText("选择日期")
                                    .setSelection(utcDayMillis(
                                            cal.get(java.util.Calendar.YEAR),
                                            cal.get(java.util.Calendar.MONTH),
                                            cal.get(java.util.Calendar.DAY_OF_MONTH)))
                                    .build();
                    picker.addOnPositiveButtonClickListener(sel ->
                            onPicked.accept(formatUtcDay(sel)));
                    picker.show(fa.getSupportFragmentManager(), "qz_canvas_date");
                    return;
                } catch (Throwable ignored) {
                    // 主题/Fragment 状态不允许时退回系统选择器，不让画布交互直接失效
                }
            }
        }
        new android.app.DatePickerDialog(context,
                (view, year, month, dayOfMonth) -> onPicked.accept(String.format(
                        java.util.Locale.getDefault(), "%04d-%02d-%02d",
                        year, month + 1, dayOfMonth)),
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    /**
     * 时间选择：宿主支持时用 M2 {@code MaterialTimePicker}，否则退回系统
     * {@code TimePickerDialog}。{@code current} 可以是 "HH:mm" 或 "yyyy-MM-dd HH:mm"。
     */
    private static void showTimePicker(final Context context, final String current,
                                       final java.util.function.Consumer<String> onPicked) {
        if (context == null || onPicked == null) return;
        final java.util.Calendar cal = java.util.Calendar.getInstance();
        try {
            if (current != null && current.length() >= 5) {
                java.util.Date d = new java.text.SimpleDateFormat("HH:mm",
                        java.util.Locale.getDefault())
                        .parse(current.substring(current.length() - 5));
                if (d != null) cal.setTime(d);
            }
        } catch (Exception ignored) {
        }
        if (context instanceof androidx.fragment.app.FragmentActivity) {
            androidx.fragment.app.FragmentActivity fa =
                    (androidx.fragment.app.FragmentActivity) context;
            if (!fa.getSupportFragmentManager().isStateSaved()) {
                try {
                    com.google.android.material.timepicker.MaterialTimePicker picker =
                            new com.google.android.material.timepicker.MaterialTimePicker.Builder()
                                    .setTitleText("选择时间")
                                    .setTimeFormat(com.google.android.material.timepicker
                                            .TimeFormat.CLOCK_24H)
                                    .setHour(cal.get(java.util.Calendar.HOUR_OF_DAY))
                                    .setMinute(cal.get(java.util.Calendar.MINUTE))
                                    .build();
                    picker.addOnPositiveButtonClickListener(v -> onPicked.accept(String.format(
                            java.util.Locale.getDefault(), "%02d:%02d",
                            picker.getHour(), picker.getMinute())));
                    picker.show(fa.getSupportFragmentManager(), "qz_canvas_time");
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
        new android.app.TimePickerDialog(context,
                (view, hourOfDay, minute) -> onPicked.accept(String.format(
                        java.util.Locale.getDefault(), "%02d:%02d", hourOfDay, minute)),
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE), true).show();
    }

    /**
     * 输入控件 IME 注入：EditText 聚焦/点击时主动弹输入法。
     * 自定义 UI（聊天流卡片/弹窗/ScrollView 内）中 EditText 虽能获得焦点（光标出现），
     * 但系统可能不自动触发软键盘——这里显式调用 InputMethodManager.showSoftInput，
     * 保证"能聚焦就能弹键盘"。
     */
    /**
     * 输入控件 IME 注入：仅在用户真实触摸输入框（ACTION_UP）时强制唤起键盘。
     * 同时挂 OnTouchListener（Dialog/ScrollView 内 OnClickListener 可能不触发）与
     * OnClickListener（常规场景），双击保险。SHOW_FORCED 强制弹出（解决自定义
     * Dialog 中 SHOW_IMPLICIT 被忽略的问题）；乱弹已由"仅触摸触发"消除。
     */
    private static void setupInputView(final EditText et) {
        if (et == null) return;
        final boolean[] touchDown = {false};
        et.setOnTouchListener((v, event) -> {
            if (event == null) return false;
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    touchDown[0] = true;
                    break;
                case android.view.MotionEvent.ACTION_UP:
                    if (touchDown[0]) {
                        touchDown[0] = false;
                        showSoftInput(et);
                    }
                    break;
                default:
                    break;
            }
            return false; // 不消费：EditText 自身仍处理光标/选择
        });
        et.setOnClickListener(v -> showSoftInput(et));
    }

    /** 主动弹出软键盘：requestFocus + showSoftInput(SHOW_FORCED) 强制唤起。
     *  仅由用户触摸触发（OnTouch ACTION_UP），不会因焦点漂移乱弹；防抖 400ms。
     *  聊天流卡片宿主（RecyclerView）中 EditText 可能"is not served"（未与 IME 建立连接），
     *  这里等 attach 后重试 + 长延迟，确保焦点链路走通。 */
    private static void showSoftInput(final EditText et) {
        try {
            long now = System.currentTimeMillis();
            Long last = (Long) et.getTag(TAG_KEY_SOFT_INPUT_TIME);
            if (last != null && now - last < 400) return;
            et.setTag(TAG_KEY_SOFT_INPUT_TIME, now);
            final android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) et.getContext()
                            .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            // 未 attach 到窗口时先等 attach（RecyclerView 卡片可能刚渲染）
            if (et.getWindowToken() == null && !et.isAttachedToWindow()) {
                et.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) {
                        et.removeOnAttachStateChangeListener(this);
                        tryShowIme(et, imm, 150);
                    }
                    @Override public void onViewDetachedFromWindow(View v) {
                        et.removeOnAttachStateChangeListener(this);
                    }
                });
                return;
            }
            tryShowIme(et, imm, 80);
        } catch (Throwable ignored) {
        }
    }

    /** 实际唤起：requestFocus（含触摸模式）+ showSoftInput(flag=0)，长延迟等待窗口可交互。
     *  flag=0 与 DebugLayoutInjectActivity 验证成功的 injectIme 一致（SHOW_FORCED 在部分
     *  MIUI 上会被服务端忽略）；失败后 restartInput 强制以 et 为输入目标重建连接再重试。 */
    private static void tryShowIme(final EditText et,
                                   final android.view.inputmethod.InputMethodManager imm, long delayMs) {
        et.postDelayed(() -> {
            try {
                et.setFocusableInTouchMode(true);
                if (!et.hasFocus()) {
                    et.requestFocus();
                }
                boolean shown = imm.showSoftInput(et, 0);
                android.util.Log.i(TAG, "showSoftInput result=" + shown
                        + " focused=" + et.hasFocus() + " served=" + (imm.isActive(et)));
                if (!shown || !imm.isActive(et)) {
                    // 首次失败重试：restartInput 强制以 et 为输入目标重建 input connection
                    et.postDelayed(() -> {
                        try {
                            et.requestFocus();
                            try {
                                imm.restartInput(et);
                            } catch (Throwable ignored0) {
                            }
                            imm.showSoftInput(et, 0);
                        } catch (Throwable ignored2) {
                        }
                    }, 200);
                }
            } catch (Throwable ignored) {
            }
        }, delayMs);
    }

    private static void addChildren(Context context, LinearLayout parent, JSONObject node,
                                    JSONObject props, Map<String, Object> viewRefs, int depth,
                                    JSONObject defines) {
        JSONArray children = node.optJSONArray("children");
        if (children == null) return;
        int spacing = node.has("spacing") ? node.optInt("spacing", 0) : 0;
        int density = density(context);
        int count = children.length();
        for (int i = 0; i < count; i++) {
            JSONObject c = children.optJSONObject(i);
            if (c == null) continue;
            View v;
            try {
                v = buildNode(context, c, props, viewRefs, depth + 1, defines);
            } catch (Throwable t) {
                // 单控件渲染异常降级：跳过坏节点，保证其余节点正常渲染（避免"一坏全坏"整卡失败）
                android.util.Log.w(TAG, "控件渲染异常已跳过: "
                        + c.optString("type", "?") + " - " + t.getMessage());
                continue;
            }
            if (v instanceof ImageView && v.getLayoutParams() != null) {
                parent.addView(v);
                continue;
            }
            int w = resolveSize(c.opt("width"),
                    parent.getOrientation() == LinearLayout.HORIZONTAL
                            ? LinearLayout.LayoutParams.WRAP_CONTENT
                            : LinearLayout.LayoutParams.MATCH_PARENT, density);
            int h = resolveSize(c.opt("height"), LinearLayout.LayoutParams.WRAP_CONTENT, density);
            // divider 系列：保留 buildNode 的固定尺寸（divider 高 dp(1) / divider_v 宽 dp(1)）。
            // 关键修复：此前 addChildren 把 divider 高度覆盖成 WRAP_CONTENT，而裸 View 在
            // 父容器 EXACTLY 高度（AlertDialog 全屏拉伸 / 卡片容器）下 AT_MOST spec 会按
            // specSize 测量——divider 被拉满整卡、后续兄弟节点全部被挤出（divider Bug 根因）。
            String cTypeDiv = c.optString("type", "");
            if (("divider".equals(cTypeDiv) || "separator".equals(cTypeDiv)
                    || "divider_v".equals(cTypeDiv)) && v.getLayoutParams() != null) {
                // divider: (MATCH_PARENT, dp(1))；divider_v: (dp(1), MATCH_PARENT)。
                // 节点未显式指定 width/height 时整体复用 buildNode 的尺寸。
                if (!c.has("width")) w = v.getLayoutParams().width;
                if (!c.has("height")) h = v.getLayoutParams().height;
            }
            // 百分比尺寸仅在 flexbox(wrap/grid) 中有意义；LinearLayout 下回退为铺满/自适应，
            // 避免 0 宽度导致控件不可见（resolveSize 对 "50%" 返回 0）
            if (w == 0 && isPercent(c.opt("width"))) {
                w = LinearLayout.LayoutParams.MATCH_PARENT;
            }
            if (h == 0 && isPercent(c.opt("height"))) {
                h = LinearLayout.LayoutParams.WRAP_CONTENT;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            // 弹性权重：weight 或 flex（0~1 或整数比例）。
            // 注意：带 weight 时若 width 是 MATCH_PARENT（column 默认），LinearLayout 会
            // 先按 MATCH_PARENT 测量再被 weight 收缩——通常正常；row 中默认 WRAP_CONTENT 更稳。
            Object wObj = c.has("weight") ? c.opt("weight") : c.opt("flex");
            if (wObj != null) {
                try {
                    lp.weight = (float) Double.parseDouble(String.valueOf(wObj));
                } catch (NumberFormatException ignored) {
                }
            } else if (SPACE_TAG.equals(v.getTag())) {
                // space 控件：保留弹性 weight=1（addChildren 覆盖 LayoutParams 时兜底）
                lp.weight = 1f;
                if (parent.getOrientation() == LinearLayout.HORIZONTAL) {
                    lp.width = 0;
                } else {
                    lp.height = 0;
                }
            }
            // margin：数字(四边) 或 {top,left,bottom,right} 对象
            applyMargins(lp, c, density);
            // 子项在容器内的对齐（仅 row 时水平方向、column 时垂直方向有意义）
            int childGravity = parseAlign(c.optString("align", ""), parent.getOrientation());
            if (childGravity != 0) {
                lp.gravity = childGravity;
            }
            // 容器间距：非首个子项在主轴方向加 spacing
            if (spacing > 0 && i > 0) {
                if (parent.getOrientation() == LinearLayout.HORIZONTAL) {
                    lp.leftMargin += dp(spacing, density);
                } else {
                    lp.topMargin += dp(spacing, density);
                }
            }
            parent.addView(v, lp);
        }
    }

    /** margin：数字（四边同值）或对象 {top,left,bottom,right} */
    private static void applyMargins(LinearLayout.LayoutParams lp, JSONObject node, int density) {
        Object m = node.opt("margin");
        if (m == null) return;
        if (m instanceof JSONObject) {
            JSONObject mo = (JSONObject) m;
            lp.setMargins(
                    mo.has("left") ? dp(mo.optInt("left", 0), density) : lp.leftMargin,
                    mo.has("top") ? dp(mo.optInt("top", 0), density) : lp.topMargin,
                    mo.has("right") ? dp(mo.optInt("right", 0), density) : lp.rightMargin,
                    mo.has("bottom") ? dp(mo.optInt("bottom", 0), density) : lp.bottomMargin);
        } else {
            int all = dp((int) Double.parseDouble(String.valueOf(m)), density);
            lp.setMargins(all, all, all, all);
        }
    }

    /** align → gravity：left/start/right/end/center（相对容器主轴方向） */
    private static int parseAlign(String align, int orientation) {
        if (align == null || align.isEmpty()) return 0;
        switch (align.trim().toLowerCase()) {
            case "left":
            case "start":
                return orientation == LinearLayout.HORIZONTAL ? Gravity.CENTER_VERTICAL | Gravity.START : Gravity.START;
            case "right":
            case "end":
                return orientation == LinearLayout.HORIZONTAL ? Gravity.CENTER_VERTICAL | Gravity.END : Gravity.END;
            case "center":
                return orientation == LinearLayout.HORIZONTAL ? Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL;
            case "top":
                return Gravity.TOP;
            case "bottom":
                return Gravity.BOTTOM;
            case "center_vertical":
                return Gravity.CENTER_VERTICAL;
            case "center_horizontal":
                return Gravity.CENTER_HORIZONTAL;
            default:
                return 0;
        }
    }

    /** FrameLayout gravity 解析：center/top/top_left/top_right/bottom/bottom_left/bottom_right/left/right */
    private static int parseFrameGravity(String g) {
        if (g == null || g.isEmpty()) return 0;
        switch (g.trim().toLowerCase()) {
            case "center":
                return android.view.Gravity.CENTER;
            case "top":
                return android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            case "top_left":
                return android.view.Gravity.TOP | android.view.Gravity.START;
            case "top_right":
                return android.view.Gravity.TOP | android.view.Gravity.END;
            case "bottom":
                return android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            case "bottom_left":
                return android.view.Gravity.BOTTOM | android.view.Gravity.START;
            case "bottom_right":
                return android.view.Gravity.BOTTOM | android.view.Gravity.END;
            case "left":
            case "start":
                return android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
            case "right":
            case "end":
                return android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL;
            default:
                return 0;
        }
    }

    /** FlexboxLayout 子项布局：支持 margin/weight(flex)/百分比宽度（"50%"） */
    private static void addFlexChildren(Context context, com.google.android.flexbox.FlexboxLayout parent,
                                        JSONObject node, JSONObject props,
                                        Map<String, Object> viewRefs, int depth,
                                        JSONObject defines) {
        JSONArray children = node.optJSONArray("children");
        if (children == null) return;
        int density = density(context);
        for (int i = 0; i < children.length(); i++) {
            JSONObject c = children.optJSONObject(i);
            if (c == null) continue;
            View v;
            try {
                v = buildNode(context, c, props, viewRefs, depth + 1, defines);
            } catch (Throwable t) {
                // 单控件渲染异常降级：跳过坏节点，其余正常渲染
                android.util.Log.w(TAG, "控件渲染异常已跳过(wrap/grid): "
                        + c.optString("type", "?") + " - " + t.getMessage());
                continue;
            }
            int w = resolveSize(c.opt("width"), LinearLayout.LayoutParams.WRAP_CONTENT, density);
            int h = resolveSize(c.opt("height"), LinearLayout.LayoutParams.WRAP_CONTENT, density);
            // divider 系列保留 buildNode 固定尺寸（divider 高 dp(1) / divider_v 宽 dp(1)），
            // 避免 flexbox 容器下 WRAP_CONTENT 被拉伸成满高。
            String cTypeF = c.optString("type", "");
            if (("divider".equals(cTypeF) || "separator".equals(cTypeF)
                    || "divider_v".equals(cTypeF)) && v.getLayoutParams() != null) {
                if (!c.has("width")) w = v.getLayoutParams().width;
                if (!c.has("height")) h = v.getLayoutParams().height;
            }
            com.google.android.flexbox.FlexboxLayout.LayoutParams flp =
                    new com.google.android.flexbox.FlexboxLayout.LayoutParams(w, h);
            // 百分比宽度：flexBasisPercent
            String wStr = c.opt("width") != null ? String.valueOf(c.opt("width")).trim() : "";
            if (wStr.endsWith("%")) {
                try {
                    double pct = Double.parseDouble(wStr.substring(0, wStr.length() - 1));
                    flp.setFlexBasisPercent((float) (pct / 100.0));
                } catch (NumberFormatException ignored) {
                }
            }
            // flex 弹性比例（1/2/3...）→ flexGrow
            Object fObj = c.has("flex") ? c.opt("flex") : c.opt("weight");
            if (fObj != null) {
                try {
                    flp.setFlexGrow((float) Double.parseDouble(String.valueOf(fObj)));
                } catch (NumberFormatException ignored) {
                }
            }
            // margin
            applyFlexMargins(flp, c, density);
            parent.addView(v, flp);
        }
    }

    /** Flexbox 子项 margin（数字或 {top,left,bottom,right}） */
    private static void applyFlexMargins(com.google.android.flexbox.FlexboxLayout.LayoutParams lp,
                                         JSONObject node, int density) {
        Object m = node.opt("margin");
        if (m == null) return;
        if (m instanceof JSONObject) {
            JSONObject mo = (JSONObject) m;
            lp.setMargins(
                    mo.has("left") ? dp(mo.optInt("left", 0), density) : 0,
                    mo.has("top") ? dp(mo.optInt("top", 0), density) : 0,
                    mo.has("right") ? dp(mo.optInt("right", 0), density) : 0,
                    mo.has("bottom") ? dp(mo.optInt("bottom", 0), density) : 0);
        } else {
            int all = dp((int) Double.parseDouble(String.valueOf(m)), density);
            lp.setMargins(all, all, all, all);
        }
    }

    /** justify 字符串 → Flexbox JustifyContent 常量 */
    private static int justifyToConstant(String justify) {
        if (justify == null) return com.google.android.flexbox.JustifyContent.FLEX_START;
        switch (justify.trim().toLowerCase()) {
            case "flex_end":
            case "end":
            case "right":
                return com.google.android.flexbox.JustifyContent.FLEX_END;
            case "center":
                return com.google.android.flexbox.JustifyContent.CENTER;
            case "space_between":
                return com.google.android.flexbox.JustifyContent.SPACE_BETWEEN;
            case "space_around":
                return com.google.android.flexbox.JustifyContent.SPACE_AROUND;
            case "space_evenly":
                return com.google.android.flexbox.JustifyContent.SPACE_EVENLY;
            default:
                return com.google.android.flexbox.JustifyContent.FLEX_START;
        }
    }

    private static int resolveSize(Object val, int def, int density) {
        if (val == null) return def;
        String s = String.valueOf(val).trim();
        if ("match".equalsIgnoreCase(s) || "fill".equalsIgnoreCase(s) || "match_parent".equalsIgnoreCase(s)) {
            return LinearLayout.LayoutParams.MATCH_PARENT;
        }
        if ("wrap".equalsIgnoreCase(s) || "wrap_content".equalsIgnoreCase(s)) {
            return LinearLayout.LayoutParams.WRAP_CONTENT;
        }
        try {
            // 百分比（相对父容器）：非 flexbox 容器（column/row/card/stack 等）统一按铺满处理，
            // 避免返回 0 导致控件 0 宽/0 高不可见（此前 bug：stack 子项 width="50%" 显示空白）。
            // FlexboxLayout（wrap/grid）用 flexBasisPercent 实现真实百分比，不走本方法。
            if (s.endsWith("%")) {
                return LinearLayout.LayoutParams.MATCH_PARENT;
            }
            return (int) (Double.parseDouble(s) * density);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 值是否为百分比尺寸（如 "50%"） */
    private static boolean isPercent(Object val) {
        if (val == null) return false;
        String s = String.valueOf(val).trim();
        return s.endsWith("%");
    }

    /** 依次取节点首个非空字符串字段（兼容多字段别名） */
    private static String firstNonEmpty(JSONObject node, String... keys) {
        for (String k : keys) {
            String v = node.optString(k, "");
            if (!v.trim().isEmpty()) return v;
        }
        return "";
    }

    /** 数值格式化（图表刻度用）：整数不带小数，否则保留1位 */
    private static String formatNumber(float v) {
        if (Math.abs(v - Math.round(v)) < 0.05f) {
            return String.valueOf(Math.round(v));
        }
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    /** 倒计时格式：MM:SS 或 HH:MM:SS */
    private static String formatCountdown(int totalSec) {
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        int s = totalSec % 60;
        if (h > 0) {
            return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }

    private static int density(Context context) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                context.getResources().getDisplayMetrics());
    }

    private static String interpolate(String text, JSONObject props) {
        if (text == null || props == null) return text == null ? "" : text;
        String out = text;
        try {
            Iterator<String> keys = props.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = props.opt(k);
                out = out.replace("{" + k + "}", String.valueOf(v));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * 饼图单值：{@code data:[{value:5,label:"A"}]} 与 {@code data:[5,3,1]} 两种形态都收。
     *
     * <p>真机崩溃（2026-10-27 18:16）就出在这里：模型给的 data 里混了非对象元素时
     * {@code optJSONObject(i)} 返回 null，旧代码直接 {@code .optDouble(...)} → NPE 抛在 onDraw 里，
     * 整个页面崩溃。绘制层必须对"模型给的形状"做兜底，不能假设它一定合规。</p>
     */
    static float pieValue(JSONArray arr, int i) {   // 包内可见：供单测钉住"畸形 data 不崩"
        if (arr == null) return 0f;
        JSONObject seg = arr.optJSONObject(i);
        if (seg != null) {
            try {
                return (float) seg.optDouble("value", 0);
            } catch (Throwable t) {
                return 0f;
            }
        }
        // 兼容纯数字数组形态
        return (float) arr.optDouble(i, 0);
    }

    /** 文本节点：数字或语义档位字号、字重、色调、富文本 spans（都 optional，旧写法照旧） */
    private static TextView buildTextNode(Context context, JSONObject node, JSONObject props,
                                          Map<String, Object> viewRefs) {
        TextView tv = new TextView(context);
        float size = 14f;
        if (node.has("size")) {
            Object raw = node.opt("size");
            if (raw instanceof String) {
                float tok = sizeToken((String) raw, -1f);
                size = tok > 0 ? tok : 14f;
            } else {
                size = (float) node.optDouble("size", 14);
            }
        }
        if (node.has("sizeToken")) {
            float tok = sizeToken(node.optString("sizeToken", ""), -1f);
            if (tok > 0) size = tok;
        }
        // UI-06：fontSize（style 对象别名）与 size 等效，且优先（旧行为不变）
        if (node.has("fontSize")) size = (float) node.optDouble("fontSize", size);
        tv.setTextSize(size);

        Object rawTextValue = node.opt("text");
        CharSequence content = buildSpans(context, node.optJSONArray("spans"), size);
        if (content != null) {
            tv.setText(content);
        } else if (LayoutFormula.isBinding(rawTextValue) || LayoutFormula.isFormula(rawTextValue)) {
            // 绑定/公式取值：登记后输入变化时原地刷新（不必重建整棵树）
            LayoutBindingScope textScope = LayoutBindingScope.from(viewRefs);
            String text = textScope != null
                    ? textScope.bind(tv, rawTextValue)
                    : LayoutFormula.resolveDisplay(rawTextValue, null);
            tv.setText(text);
        } else {
            String rawText = rawTextValue != null ? String.valueOf(rawTextValue) : "";
            tv.setText(interpolate(rawText, props));
        }

        tv.setTypeface(weightTypeface(node.optString("weight", ""),
                node.optBoolean("bold", false)));
        Integer tone = toneColor(context, node.optString("tone", ""));
        if (tone != null && !node.has("color")) {
            tv.setTextColor(tone);
        } else if (node.has("color")) {
            tv.setTextColor(parseColor(context, node.optString("color", "")));
        }
        tv.setPadding(0, dp(2, 2), 0, dp(2, 2));
        String tAlign = node.optString("align", "");
        if (!tAlign.isEmpty()) {
            switch (tAlign.trim().toLowerCase()) {
                case "center":
                    tv.setGravity(Gravity.CENTER_HORIZONTAL);
                    break;
                case "right":
                case "end":
                    tv.setGravity(Gravity.END);
                    break;
                default:
                    tv.setGravity(Gravity.START);
                    break;
            }
        }
        if (node.has("key")) viewRefs.put("text_" + node.optString("key"), tv);
        return tv;
    }
    // ==================== 语义化样式令牌（抄 dsh-Go 的渲染思路，2026-11-14） ====================
    //
    // 插件/模型只给**语义**（字号档位、字重、色调、富文本片断），具体像素、颜色、
    // 深浅色适配由 App 决定。这样同一份 layout 在浅色/深色、不同字号设置下都不会崩。
    // 与 dsh-Go 的 v2 视图语言保持同名同义，方便以后互相对照。

    /** 字号档位：六档，与 dsh-Go 同名（caption < footnote < body < subtitle < title < headline） */
    private static float sizeToken(String token, float fallback) {
        if (token == null) return fallback;
        switch (token.trim().toLowerCase()) {
            case "caption": return 12f;
            case "footnote": return 13f;
            case "body": return 15f;
            case "subtitle": return 17f;
            case "title": return 20f;
            case "headline": return 24f;
            case "display": return 28f;
            default: return -1f; // 不是档位名，交给数字解析
        }
    }

    /** 字重：regular/medium/semibold/bold（bold 旧字段继续有效） */
    private static android.graphics.Typeface weightTypeface(String weight, boolean bold) {
        String w = weight == null ? "" : weight.trim().toLowerCase();
        switch (w) {
            case "regular":
            case "normal":
                return android.graphics.Typeface.DEFAULT;
            case "medium":
                return android.graphics.Typeface.create("sans-serif-medium",
                        android.graphics.Typeface.NORMAL);
            case "semibold":
                return android.graphics.Typeface.create("sans-serif-medium",
                        android.graphics.Typeface.BOLD);
            case "bold":
                return android.graphics.Typeface.DEFAULT_BOLD;
            default:
                return bold ? android.graphics.Typeface.DEFAULT_BOLD
                        : android.graphics.Typeface.DEFAULT;
        }
    }

    /**
     * 色调 → 主题色。good/warn/bad/info/muted 是语义名额，别名（success/warning/
     * danger/error/note/secondary）也认 —— 插件作者不用知道我们的资源名。
     */
    private static Integer toneColor(Context context, String tone) {
        if (tone == null || tone.trim().isEmpty()) return null;
        switch (tone.trim().toLowerCase()) {
            case "good":
            case "success":
            case "ok":
                return ComponentColors.success(context);
            case "warn":
            case "warning":
                return ComponentColors.warning(context);
            case "bad":
            case "danger":
            case "error":
            case "fail":
                return ComponentColors.error(context);
            case "info":
            case "note":
            case "accent":
                return ComponentColors.accent(context);
            case "muted":
            case "secondary":
                return ComponentColors.textSecondary(context);
            case "primary":
            case "text":
                return ComponentColors.textPrimary(context);
            default:
                return null;
        }
    }

    /**
     * spans：把一段文字切成带样式的片断。
     * <pre>[{"text":"还剩 ","color":"gray"},{"text":"3","tone":"bad","mono":true,"weight":"bold"}]</pre>
     * 每个片断支持 text / color / tone / bold / weight / mono / size。
     */
    private static CharSequence buildSpans(Context context, JSONArray spans, float baseSize) {
        if (spans == null || spans.length() == 0) return null;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (int i = 0; i < spans.length(); i += 1) {
            JSONObject span = spans.optJSONObject(i);
            if (span == null) {
                String raw = spans.optString(i, "");
                if (raw != null) sb.append(raw);
                continue;
            }
            String text = span.optString("text", "");
            int start = sb.length();
            sb.append(text);
            int end = sb.length();
            if (start == end) continue;
            Integer color = null;
            String tone = span.optString("tone", "");
            if (!tone.isEmpty()) color = toneColor(context, tone);
            if (color == null && span.has("color")) {
                color = parseColor(context, span.optString("color", ""));
            }
            if (color != null) {
                sb.setSpan(new android.text.style.ForegroundColorSpan(color), start, end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            boolean bold = span.optBoolean("bold", false)
                    || "bold".equalsIgnoreCase(span.optString("weight", ""));
            if (bold) {
                sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (span.optBoolean("mono", false)) {
                sb.setSpan(new android.text.style.TypefaceSpan("monospace"), start, end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (span.has("size")) {
                float sz = span.optDouble("size", baseSize) > 0
                        ? (float) span.optDouble("size", baseSize) : baseSize;
                float tok = sizeToken(span.optString("sizeToken", ""), -1f);
                if (tok > 0) sz = tok;
                sb.setSpan(new android.text.style.RelativeSizeSpan(baseSize > 0 ? sz / baseSize : 1f),
                        start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    /** 渲染预算：节点数/深度/JSON 体积（超限不崩，降级成一句说明） */
    private static final int MAX_NODES = 400;
    private static final int MAX_DEPTH = 9;
    private static final int MAX_LAYOUT_CHARS = 64 * 1024;
    /**
     * data:image base64 总载荷上限（4 张 × 512 KiB）。单张上限由
     * {@link com.oilquiz.app.ai.chat.component.DataImageDecoder} 把关；这里只防"一张卡塞几百张图"。
     */
    private static final int MAX_DATA_IMAGE_CHARS = 4 * 512 * 1024;
    private static final ThreadLocal<int[]> NODE_BUDGET = ThreadLocal.withInitial(() -> new int[]{0});
    /**
     * 嵌套卡片深度上限（2026-11-15）：layout 直接子卡片的深度记 0，卡片内部（卡片自己再走
     * layout 渲染，如 layout 卡）再出现的卡片深度 +1。计数 &gt; 本值的那一张换成灰字「嵌套太深」。
     * 只拦"卡片里再套卡片"这条环（ComponentRegistry 卡片可能回调本渲染器），普通节点层级仍由
     * {@link #MAX_DEPTH} 把关；两层嵌套（4 张连续卡片）已远超实际卡片树的形状。
     */
    private static final int MAX_NESTED_CARD_DEPTH = 2;
    /** 线程内嵌套卡片深度（渲染同步进行；进卡片前 +1、出卡片 finally -1，不跨请求残留） */
    private static final ThreadLocal<int[]> CARD_NEST_DEPTH = ThreadLocal.withInitial(() -> new int[]{0});

    /**
     * 结构体积闸门：data:image base64 载荷不计入 64 KiB，但总载荷仍受
     * {@link #MAX_DATA_IMAGE_CHARS} 限制。否则一张 512 KiB 的图永远过不了 layout 体积检查。
     */
    private static boolean withinLayoutBudget(String text) {
        if (text == null) return true;
        long total = text.length();
        long payload = 0;
        int from = 0;
        while (true) {
            int mark = text.indexOf("data:image/", from);
            if (mark < 0) break;
            int base64 = text.indexOf(";base64,", mark);
            if (base64 < 0) {
                from = mark + "data:image/".length();
                continue;
            }
            int start = base64 + ";base64,".length();
            int end = start;
            while (end < total) {
                char c = text.charAt(end);
                boolean base64Char = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
                if (!base64Char) break;
                end++;
            }
            payload += end - start;
            if (payload > MAX_DATA_IMAGE_CHARS) return false;
            from = Math.max(end, start + 1);
        }
        return total - payload <= MAX_LAYOUT_CHARS;
    }

    /** 图片占位：浅底 + 灰字，明确告诉用户"这张图显示不了"，不是空白/裂图。 */
    private static TextView imageUnavailable(Context context, int wDp, int hDp, int density,
                                             String key, Map<String, Object> viewRefs) {
        TextView tv = new TextView(context);
        tv.setText("图片无法显示");
        tv.setTextSize(12f);
        tv.setTextColor(ComponentColors.textTertiary(context));
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(ComponentColors.imageError(context));
        if (wDp > 0 && hDp > 0) {
            tv.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp, density), dp(hDp, density)));
        } else {
            tv.setMinHeight(dp(80, density));
        }
        if (key != null && !key.isEmpty() && viewRefs != null) {
            viewRefs.put(key, tv);
        }
        return tv;
    }

    /** 灰色占位：某一块画不出来时用它，聊天流其余部分照常 */
    private static TextView placeholder(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text == null ? "这部分无法显示" : text);
        tv.setTextSize(12f);
        tv.setTextColor(ComponentColors.textTertiary(context));
        tv.setPadding(dp(8, 2), dp(6, 2), dp(8, 2), dp(6, 2));
        return tv;
    }

    private static int dp(int base, int density) {
        return base * density;
    }

    /** float 版本（图表细线/圆点用，避免精度损失） */
    private static int dp(float base, int density) {
        return Math.round(base * density);
    }

    private static int parseColor(Context context, String hex) {
        try {
            if (hex != null && hex.startsWith("#") && hex.length() >= 7) {
                return Color.parseColor(hex);
            }
        } catch (Exception ignored) {
        }
        return com.oilquiz.app.ai.chat.component.ComponentColors.textPrimary(context);
    }

    /**
     * 跑马灯滚动：位移动画实现（不依赖系统 TextView marquee 的焦点机制，
     * 在 Dialog / 插件弹窗等无焦点容器内也能正常滚动）。
     *
     * @param tv     目标 TextView（必须已 setText）
     * @param text   原始文本（用于测量宽度）
     * @param speed  0=不滚动（单行截断） 1=慢 2=中 3=快
     * @param repeat 循环次数；-1 = 无限循环
     */
    public static void startMarquee(final TextView tv, final String text, final int speed, final int repeat) {
        if (tv == null) return;
        tv.setSingleLine(true);
        if (speed <= 0 || text == null || text.isEmpty()) {
            // 不滚动：单行尾部截断（保证布局稳定）
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setSelected(false);
            return;
        }
        // 关闭系统 marquee（selected/focus 机制），滚动完全交给位移动画，避免两者叠加冲突
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setSelected(false);
        tv.setFocusable(false);
        tv.setFocusableInTouchMode(false);
        tv.post(() -> {
            try {
                int viewWidth = tv.getWidth();
                if (viewWidth <= 0) {
                    viewWidth = tv.getResources().getDisplayMetrics().widthPixels / 2;
                }
                float textWidth = tv.getPaint().measureText(text);
                if (textWidth <= viewWidth) {
                    return; // 文本未超宽：静止展示即可，无需滚动
                }
                long duration = speed >= 3 ? 4000L : (speed == 2 ? 6500L : 10000L);
                android.view.animation.TranslateAnimation anim =
                        new android.view.animation.TranslateAnimation(
                                viewWidth, -textWidth, 0, 0); // 从右缘进入 → 完全移出左缘
                anim.setDuration(duration);
                anim.setRepeatMode(android.view.animation.Animation.RESTART);
                anim.setRepeatCount(repeat < 0
                        ? android.view.animation.Animation.INFINITE : Math.max(0, repeat));
                anim.setInterpolator(new android.view.animation.LinearInterpolator());
                tv.startAnimation(anim);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 收集带 key 的控件值（input/number/password/multiline/otp→文本、select→选中项、switch/checkbox→布尔、
     *  radio→选中项、slider→数值、date/time→文本、color→#RRGGBB、rating→分数、progress→数值） */
    public static Map<String, Object> collectValues(Map<String, Object> viewRefs) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : viewRefs.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("text_") || key.startsWith("_")) continue;
            Object v = e.getValue();
            if (v instanceof EditText) {
                out.put(key, ((EditText) v).getText() != null ? ((EditText) v).getText().toString() : "");
            } else if (v instanceof Spinner) {
                Spinner sp = (Spinner) v;
                out.put(key, sp.getSelectedItem() != null ? String.valueOf(sp.getSelectedItem()) : "");
            } else if (v instanceof android.widget.Switch) {
                out.put(key, ((android.widget.Switch) v).isChecked());
            } else if (v instanceof android.widget.CheckBox) {
                out.put(key, ((android.widget.CheckBox) v).isChecked());
            } else if (v instanceof android.widget.CompoundButton) {
                // SwitchMaterial / MaterialCheckBox / MaterialRadioButton 都继承 CompoundButton；
                // 只认 android.widget.Switch 的话，换成 M2 开关后值会静默收不到（2026-11-18）
                out.put(key, ((android.widget.CompoundButton) v).isChecked());
            } else if (v instanceof android.widget.RadioGroup) {
                android.widget.RadioGroup rg = (android.widget.RadioGroup) v;
                int checkedId = rg.getCheckedRadioButtonId();
                if (checkedId != -1) {
                    android.widget.RadioButton rb = rg.findViewById(checkedId);
                    out.put(key, rb != null ? rb.getText().toString() : "");
                } else {
                    out.put(key, "");
                }
            } else if (v instanceof android.widget.SeekBar) {
                android.widget.SeekBar sb = (android.widget.SeekBar) v;
                int min = 0;
                Object tag = sb.getTag();
                if (tag instanceof Integer) min = (Integer) tag;
                out.put(key, sb.getProgress() + min);
            } else if (v instanceof Slider) {
                // M2 Slider：getValue 已是实际值（valueFrom 偏移已含），取整口径与旧 SeekBar 一致
                out.put(key, Math.round(((Slider) v).getValue()));
            } else if (v instanceof ProgressBar) {
                out.put(key, ((ProgressBar) v).getProgress());
            } else if (v instanceof ColorRef) {
                out.put(key, ((ColorRef) v).getValue());
            } else if (v instanceof RatingRef) {
                out.put(key, ((RatingRef) v).getValue());
            } else if (v instanceof DateRef) {
                // date/time 选择器：返回实际选中值（未选择返回空串，不把占位文本当值）
                out.put(key, ((DateRef) v).getValue());
            } else if (v instanceof ToggleRef) {
                out.put(key, ((ToggleRef) v).getValue());
            } else if (v instanceof StepperRef) {
                out.put(key, ((StepperRef) v).getValue());
            } else if (v instanceof RangeRef) {
                out.put(key, ((RangeRef) v).getValue());
            } else if (v instanceof ProgressRingRef) {
                out.put(key, ((ProgressRingRef) v).getValue());
            } else if (v instanceof TagInputRef) {
                out.put(key, ((TagInputRef) v).getValue());
            } else if (v instanceof CheckboxGroupRef) {
                out.put(key, ((CheckboxGroupRef) v).getValue());
            } else if (v instanceof RadioGroupRef) {
                out.put(key, ((RadioGroupRef) v).getValue());
            } else if (v instanceof TextView && !(v instanceof android.widget.Button)
                    && !(v instanceof android.widget.CompoundButton)) {
                // 兼容其它纯文本值控件（按钮文本不收集；带 tag 的交互文本如 link 不收集）
                if (((TextView) v).getTag() != null) continue;
                out.put(key, ((TextView) v).getText() != null ? ((TextView) v).getText().toString() : "");
            }
        }
        return out;
    }

    /**
     * 把旧控件值回填到新渲染的控件（画布重渲染时保留用户输入）。
     * 按 key 匹配 viewRefs 中的控件，写回 oldValues[key]。
     * 仅覆盖最常见的可写控件（EditText/Spinner/Switch/CheckBox/RatingBar），
     * 其余（日期/取色/标签等）重建后由用户重新选择。
     */
    public static void applyValues(Map<String, Object> viewRefs, Map<String, Object> oldValues) {
        if (viewRefs == null || oldValues == null) return;
        for (Map.Entry<String, Object> e : viewRefs.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("text_") || key.startsWith("_")) continue;
            Object v = e.getValue();
            if (!oldValues.containsKey(key)) continue;
            Object oldVal = oldValues.get(key);
            String s = oldVal == null ? "" : String.valueOf(oldVal);
            try {
                if (v instanceof EditText) {
                    ((EditText) v).setText(s);
                } else if (v instanceof Spinner) {
                    Spinner sp = (Spinner) v;
                    for (int i = 0; i < sp.getAdapter().getCount(); i++) {
                        Object item = sp.getAdapter().getItem(i);
                        if (item != null && s.equals(String.valueOf(item))) {
                            sp.setSelection(i);
                            break;
                        }
                    }
                } else if (v instanceof android.widget.Switch) {
                    ((android.widget.Switch) v).setChecked(Boolean.parseBoolean(s));
                } else if (v instanceof android.widget.CheckBox) {
                    ((android.widget.CheckBox) v).setChecked(Boolean.parseBoolean(s));
                } else if (v instanceof android.widget.CompoundButton) {
                    // SwitchMaterial 等 M2 控件（不是 android.widget.Switch）走这里回填
                    ((android.widget.CompoundButton) v).setChecked(Boolean.parseBoolean(s));
                } else if (v instanceof Slider) {
                    Slider slider = (Slider) v;
                    try {
                        float from = slider.getValueFrom();
                        float step = slider.getStepSize();
                        float target = Float.parseFloat(s);
                        if (step > 0) {
                            target = from + Math.round((target - from) / step) * step;
                        }
                        slider.setValue(Math.max(slider.getValueFrom(),
                                Math.min(slider.getValueTo(), target)));
                    } catch (Exception ignored) {
                    }
                } else if (v instanceof android.widget.RatingBar) {
                    try {
                        ((android.widget.RatingBar) v).setRating(Float.parseFloat(s));
                    } catch (Exception ignored) {
                    }
                } else if (v instanceof android.widget.SeekBar) {
                    try {
                        ((android.widget.SeekBar) v).setProgress((int) Float.parseFloat(s));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 取色器值引用（key → 当前选中色 #RRGGBB） */
    static class ColorRef {
        private final String[] picked;
        ColorRef(String[] picked) { this.picked = picked; }
        String getValue() { return picked[0]; }
    }

    /** 评分值引用（key → 分数 1-5，0=未选） */
    static class RatingRef {
        private final int[] score;
        RatingRef(int[] score) { this.score = score; }
        int getValue() { return score[0]; }
    }

    /** 日期/时间选择器值引用（key → 实际选中值；未选择为空串，占位文本不参与收集） */
    static class DateRef {
        private final String[] val;
        DateRef(String[] val) { this.val = val; }
        String getValue() { return val[0]; }
    }

    /** 胶囊分段开关值引用（key → 当前选中值） */
    static class ToggleRef {
        private final String[] cur;
        ToggleRef(String[] cur) { this.cur = cur; }
        String getValue() { return cur[0]; }
    }

    /** 步进器值引用（key → 当前数值） */
    static class StepperRef {
        private final int[] val;
        StepperRef(int[] val) { this.val = val; }
        int getValue() { return val[0]; }
    }

    /** 双滑块范围值引用（key → {low,high}） */
    static class RangeRef {
        private final int[] low;
        private final int[] high;
        RangeRef(int[] low, int[] high) { this.low = low; this.high = high; }
        String getValue() { return "{\"low\":" + low[0] + ",\"high\":" + high[0] + "}"; }
    }

    /** 环形进度值引用（key → 当前百分比） */
    static class ProgressRingRef {
        private final int progress;
        private final int max;
        ProgressRingRef(int progress, int max) { this.progress = progress; this.max = max; }
        String getValue() { return "{\"progress\":" + progress + ",\"max\":" + max + "}"; }
    }

    /** 标签输入值引用（key → 标签数组） */
    static class TagInputRef {
        private final java.util.List<String> tags;
        TagInputRef(java.util.List<String> tags) { this.tags = tags; }
        String getValue() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < tags.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(tags.get(i).replace("\"", "\\\"")).append("\"");
            }
            return sb.append("]").toString();
        }
    }

    /** 复选组值引用（key → 选中值数组） */
    static class CheckboxGroupRef {
        private final java.util.List<String> sel;
        CheckboxGroupRef(java.util.List<String> sel) { this.sel = sel; }
        String getValue() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < sel.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(sel.get(i).replace("\"", "\\\"")).append("\"");
            }
            return sb.append("]").toString();
        }
    }

    /** 单选组值引用（key → 选中值，按 id 查 value） */
    static class RadioGroupRef {
        private final android.widget.RadioGroup rg;
        private final java.util.Map<Integer, String> idToVal;
        RadioGroupRef(android.widget.RadioGroup rg, java.util.Map<Integer, String> idToVal) {
            this.rg = rg;
            this.idToVal = idToVal;
        }
        String getValue() {
            int checkedId = rg.getCheckedRadioButtonId();
            if (checkedId == -1) return "";
            String v = idToVal.get(checkedId);
            if (v != null) return v;
            android.widget.RadioButton rb = rg.findViewById(checkedId);
            return rb != null ? rb.getText().toString() : "";
        }
    }
}
