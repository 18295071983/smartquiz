package com.oilquiz.app.ai.chat.component;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * 工具结果卡片的「怎么显示」决策（2026-10-20）。
 *
 * <p>问题：一次工具调用会在消息流里落一张产物卡（weather_card / table_card / list_card /
 * image_grid / file_card …，由 {@code ToolResultCardBinder} 自动补挂或模型用 ui_component 主动创建），
 * 而渲染端此前对它们<b>一律整卡完整显示</b>（{@code ChatAdapter#bindComponents} 的 others 通道、
 * {@code renderRoundAssembled} 的 roundProds/tailProds）。长表格、图集、JSON 一多，
 * 正文被自己的过程数据顶出屏幕；模型也没有任何入口说"这条不重要，收起来"。
 *
 * <p>口径（2026-10-21 修正）：<b>工具与内置卡片由模型管理，模型通过工具调用参数控制显隐</b>。
 * 展示档位优先级从高到低：
 * <ol>
 *   <li><b>模型显式声明</b>：{@code props.display = "inline" | "peek" | "hidden"}；</li>
 *   <li><b>历史声明</b>：{@code props.collapsed = true/false}；</li>
 *   <li><b>类型底线</b>：交互类与正文类组件永远 {@link Mode#INLINE}
 *       （按钮/表单/进度/问答卡收起后用户点不到，markdown/html/code 收起等于正文消失）；</li>
 *   <li><b>用户默认档</b>：SharedPreferences
 *       {@code ai_chat_prefs.tool_result_display_default}，默认 {@code peek}。</li>
 * </ol>
 *
 * <p>展开状态不放 View、也不进持久化：{@code ChatMessage.componentBodyExpanded}
 * 记"哪些卡片被用户点开过"，与思考轮展开（{@code thinkingExpandedRounds}）同一套路子；
 * RecyclerView 复用/重建时按这个集合重新决定初始档位，不会像 v2 工具行那样
 * 把状态只挂在 View 上一次绑定。
 *
 * <p>档位为 {@link Mode#HIDDEN} 的卡片<b>完全不挂载</b>，所以渲染侧三处循环
 * （指纹段、增量跳位、重建挂载）必须都走 {@link #countMounting} 与 {@link #resolve}，
 * 否则 contentHost 的子 View 计数与 {@code expect} 会对不上，增量更新错位。
 */
public final class ComponentDisplay {

    /** 展示档位 */
    public enum Mode {
        /** 整卡完整显示（2026-10-20 之前的唯一行为） */
        INLINE,
        /** 收成一行摘要（标题 + 一个关键值），点击展开完整卡 */
        PEEK,
        /** 不挂载（卡片数据仍在消息里，可复盘） */
        HIDDEN
    }

    /** 与 {@code ToolResultCardBinder} 同一个 prefs 文件，方便设置页并列 */
    public static final String PREFS_NAME = "ai_chat_prefs";
    /** 用户默认档：inline / peek / hidden，缺省 peek */
    public static final String PREF_KEY_DEFAULT_MODE = "tool_result_display_default";
    /** 模型可声明的展示档位字段（放 props 内或顶层，与其它组件参数同口径自动合并） */
    public static final String PROP_DISPLAY = "display";
    /** 历史字段：true=摘要行，false=整卡 */
    public static final String PROP_COLLAPSED = "collapsed";

    /** 一句话说明，供 ui_component 工具定义复用（模型靠它决定卡片显隐）。 */
    public static final String DISPLAY_DOC =
            "display:'inline|peek|hidden' 通用可选（放 props 内或顶层）：结果本身就是答案用 inline，"
                    + "过程性数据/长表格/图集用 peek（收成一行，用户点开才展开），"
                    + "刷屏又无价值用 hidden（不挂载）；交互类（按钮/表单/进度/答题）与正文类"
                    + "（markdown/html/code）会被强制 inline，声明无效";

    /**
     * 永远整卡显示的类型：交互控件（收起就点不到）+ 实时状态（进度/任务监控折叠会丢可见反馈）
     * + 正文型卡片（折叠等于正文消失）。canonical 名与 alias 名都列进来，
     * 因为 {@code ComponentData.type} 存的是注册表 key（如 info_card），不保证是 canonical。
     */
    private static final java.util.Set<String> ALWAYS_INLINE_TYPES = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    // 交互：弹窗/取输入/取选择
                    "dialog", "input", "choice", "multi_choice", "date", "time", "datetime",
                    "file_picker", "image_picker", "contact_picker", "rating", "color",
                    "otp", "number", "password", "multiline", "search", "custom",
                    "snackbar", "notification", "toast",
                    // 实时状态
                    "progress", "progress_card", "progress_ring", "media_task", "steps_card",
                    "agent_status", "feedback_bar", "app_card",
                    // Layout 原语里的输入/选择控件（ui_component 用 primitive_ 名或直接命中被归一的名字时，
                    // 用户要拨的就是卡片本体，收起来等于把控件藏掉）
                    "toggle", "stepper", "slider_range", "tag_input",
                    "checkbox_group", "radio_group", "search_bar",
                    // 正文与学习交互
                    "markdown", "markdown_card", "html", "web", "code", "code_card",
                    "quiz", "quiz_card", "marquee"));

    /**
     * 摘要行的兜底中文名：props 里没有 title 时用（{@code ToolMetaRegistry} 只管工具名，
     * 组件类型名此前只在各渲染器内部硬编码，折叠行需要一个不依赖渲染器的来源）。
     *
     * <p>里面保留了 note_card/grid_card 两条：它们的原生实现已删（被 ComponentTypes 归一到
     * info/list），但历史会话持久化的 {@code type} 仍是旧名，摘要行必须还能念得出中文名。
     */
    private static final java.util.Map<String, String> TYPE_CN;
    static {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("weather_card", "天气");
        m.put("weather", "天气");
        m.put("table_card", "数据表");
        m.put("table", "数据表");
        m.put("chart", "图表");
        m.put("list_card", "列表");
        m.put("grid_card", "网格");
        m.put("todo_card", "待办");
        m.put("contact_card", "联系人");
        m.put("image_grid", "图集");
        m.put("image", "图集");
        m.put("file_card", "文件");
        m.put("file_list", "文件列表");
        m.put("link_card", "链接");
        m.put("json_viewer", "数据");
        m.put("code_card", "代码");
        m.put("metric_card", "指标");
        m.put("steps_card", "步骤");
        m.put("progress_card", "进度");
        m.put("info_card", "信息");
        m.put("alert_card", "提醒");
        m.put("note_card", "笔记");
        m.put("markdown_card", "文本");
        m.put("html", "网页");
        m.put("quiz_card", "题目");
        m.put("playlist", "播放列表");
        m.put("agenda", "日程");
        m.put("location", "位置");
        m.put("diff", "变更");
        TYPE_CN = java.util.Collections.unmodifiableMap(m);
    }

    private ComponentDisplay() {
    }

    // ==================== 档位决策 ====================

    /** 解析任意输入（含 null/大小写/别名）成档位，解不出返回 fallback */
    public static Mode parse(String raw, Mode fallback) {
        if (raw == null) return fallback;
        switch (raw.trim().toLowerCase(Locale.US)) {
            case "inline":
            case "show":
            case "expand":
            case "full":
                return Mode.INLINE;
            case "peek":
            case "collapse":
            case "collapsed":
            case "fold":
            case "summary":
                return Mode.PEEK;
            case "hidden":
            case "hide":
            case "none":
            case "silent":
                return Mode.HIDDEN;
            default:
                return fallback;
        }
    }

    /** 用户默认档（Context 为空/读不到都按 peek——与"新能力默认最小副作用"一致） */
    public static Mode defaultTier(Context context) {
        if (context == null) return Mode.PEEK;
        try {
            String raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_KEY_DEFAULT_MODE, null);
            return parse(raw, Mode.PEEK);
        } catch (Throwable t) {
            return Mode.PEEK;
        }
    }

    /** 写默认档（设置页/调试用）；非法值按 peek 落盘 */
    public static void setDefaultTier(Context context, String modeName) {
        if (context == null) return;
        Mode m = parse(modeName, Mode.PEEK);
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(PREF_KEY_DEFAULT_MODE, name(m)).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 档位的字符串形式（写用户设置 prefs 用；不再暴露给模型） */
    public static String name(Mode mode) {
        switch (mode) {
            case INLINE: return "inline";
            case HIDDEN: return "hidden";
            default: return "peek";
        }
    }

    /**
     * 该组件这次要不要真的挂到消息流里（{@link #resolve} 的非 HIDDEN 判断，三处渲染循环共用）。
     */
    public static boolean mounts(Mode mode) {
        return mode != Mode.HIDDEN;
    }

    /** 需要挂载的组件个数（指纹段的 expect 与增量跳位都用它，保证同口径） */
    public static int countMounting(List<ComponentData> comps, Context context) {
        if (comps == null) return 0;
        Mode tier = defaultTier(context);
        int n = 0;
        for (ComponentData c : comps) {
            if (c != null && mounts(resolveMode(c, tier))) n++;
        }
        return n;
    }

    /** 完整决策：模型声明 → 历史 collapsed → 类型底线 → 用户默认档 */
    public static Mode resolve(Context context, ComponentData comp) {
        return resolveMode(comp, defaultTier(context));
    }

    /** 决策本体（不碰 Context，单测直接调这条） */
    public static Mode resolveMode(ComponentData comp, Mode tier) {
        if (comp == null) return tier;
        // 底线先算：交互/正文类不受声明与默认档影响
        if (isAlwaysInline(comp)) return Mode.INLINE;
        Mode fallback = tier != null ? tier : Mode.PEEK;
        JSONObject props = comp.props;
        if (props == null) return fallback;
        // 模型通过工具调用参数控制显隐
        Mode declaredMode = parse(props.optString(PROP_DISPLAY, ""), null);
        if (declaredMode != null) return declaredMode;
        // 历史字段：json 类型的 collapsed（true=摘要行，false=整卡）
        if (props.has(PROP_COLLAPSED)) {
            Boolean collapsed = optBoolean(props, PROP_COLLAPSED);
            if (collapsed != null) return collapsed ? Mode.PEEK : Mode.INLINE;
        }
        return fallback;
    }

    /** props 里的布尔容错：模型常写 "true"/1/0 字符串 */
    private static Boolean optBoolean(JSONObject props, String key) {
        try {
            Object v = props.opt(key);
            if (v == null || v == JSONObject.NULL) return null;
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof Number) return ((Number) v).intValue() != 0;
            String s = String.valueOf(v).trim().toLowerCase(Locale.US);
            if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) return Boolean.TRUE;
            if ("false".equals(s) || "0".equals(s) || "no".equals(s)) return Boolean.FALSE;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 交互类 / 正文类 / 带按钮的卡片：一律整卡显示，用户默认档也不能把它们收起 */
    public static boolean isAlwaysInline(ComponentData comp) {
        if (comp == null) return false;
        String type = comp.type;
        if (type != null && ALWAYS_INLINE_TYPES.contains(type)) return true;
        JSONObject props = comp.props;
        if (props == null) return false;
        // 任何带交互动作的卡片（提交/回调/链接按钮）都不可收起——收起后用户点不到，
        // 依赖 get_result 的链路会直接卡死
        return props.optJSONArray("actions") != null
                || props.optJSONArray("buttons") != null;
    }

    /**
     * 展开状态在 {@code ChatMessage} 上的键：优先用组件子 id（T1-C1…，Gson 持久化、跨重建稳定），
     * 没有 id（旧数据/现场拼装）时退回"类型 + 序号"，同一消息内不会撞。
     */
    public static String stateKey(ComponentData comp, int index) {
        if (comp == null) return "c#" + index;
        if (comp.id != null && !comp.id.isEmpty()) return comp.id;
        return "idx:" + (comp.type == null ? "?" : comp.type) + "#" + index;
    }

    // ==================== 摘要行文案 ====================

    /**
     * peek 档的一行摘要：标题（或类型中文名）+ 一个最能说明规模的关键值。
     *
     * <p>刻意做成不碰 Context 的纯函数：ChatAdapter 在 RecyclerView 绑定里调用，
     * 任何资源查询/异常都不该出现在这条路径上。
     */
    public static String peekLabel(ComponentData comp) {
        if (comp == null) return "内容";
        JSONObject props = comp.props;
        String title = props != null ? props.optString("title", "").trim() : "";
        String detail = peekDetail(comp.type, props);
        if (title.isEmpty()) {
            title = TYPE_CN.getOrDefault(comp.type == null ? "" : comp.type, "内容");
        }
        if (detail == null || detail.isEmpty()) return title;
        return title.isEmpty() ? detail : title + " · " + detail;
    }

    /** 关键值：按类型取"规模"类字段，取不到就退回首个短文本，再取不到返回 null */
    private static String peekDetail(String type, JSONObject props) {
        if (type == null || props == null) return null;
        switch (type) {
            case "weather_card":
            case "weather": {
                String city = props.optString("city", "");
                String temp = firstNonEmpty(props.optString("temp", ""),
                        nested(props, "now", "temp"));
                String text = firstNonEmpty(props.optString("text", ""),
                        nested(props, "now", "text"));
                StringBuilder sb = new StringBuilder();
                if (!city.isEmpty()) sb.append(city);
                if (!temp.isEmpty()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(temp);
                    // 工具侧的 temp 自带单位（"26°C"），只有裸数字才补 ℃，否则会写成 "26°C℃"
                    if (!temp.contains("°") && !temp.contains("℃")) sb.append("℃");
                }
                if (!text.isEmpty()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(text);
                }
                if (sb.length() == 0) {
                    JSONArray forecast = props.optJSONArray("forecast");
                    if (forecast != null && forecast.length() > 0) {
                        sb.append("预报 ").append(forecast.length()).append(" 天");
                    }
                }
                return sb.length() == 0 ? null : sb.toString();
            }
            case "table_card":
            case "table": {
                JSONArray rows = props.optJSONArray("rows");
                JSONArray headers = props.optJSONArray("headers");
                int n = rows != null ? rows.length() : 0;
                int m = headers != null ? headers.length() : 0;
                if (n == 0 && m == 0) return null;
                return m > 0 ? n + " 行 × " + m + " 列" : n + " 行";
            }
            case "chart": {
                JSONArray series = props.optJSONArray("series");
                JSONArray categories = props.optJSONArray("categories");
                String chartType = props.optString("chartType", "");
                StringBuilder sb = new StringBuilder();
                if (!chartType.isEmpty()) sb.append(chartType);
                if (series != null && series.length() > 0) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(series.length()).append(" 组数据");
                }
                if (categories != null && categories.length() > 0) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(categories.length()).append(" 类");
                }
                return sb.length() == 0 ? null : sb.toString();
            }
            case "image_grid":
            case "image": {
                JSONArray images = props.optJSONArray("images");
                if (images == null) images = props.optJSONArray("urls");
                return images != null && images.length() > 0 ? images.length() + " 张图" : null;
            }
            case "file_card": {
                String name = props.optString("name", "");
                if (!name.isEmpty()) return name;
                String path = props.optString("path", "");
                return path.isEmpty() ? null : shortTail(path);
            }
            case "file_list": {
                JSONArray files = props.optJSONArray("files");
                return files != null && files.length() > 0 ? files.length() + " 个文件" : null;
            }
            case "link_card":
            case "link": {
                String url = props.optString("url", "");
                if (url.isEmpty()) return null;
                try {
                    java.net.URI uri = java.net.URI.create(url);
                    String host = uri.getHost();
                    if (host != null && !host.isEmpty()) return host;
                } catch (Throwable ignored) {
                }
                return shortTail(url);
            }
            case "metric_card":
            case "metric":
            case "stat": {
                JSONArray items = firstArray(props, "metrics", "items");
                if (items != null && items.length() > 0) {
                    JSONObject first = items.optJSONObject(0);
                    if (first != null) {
                        String label = first.optString("label", "");
                        String value = first.optString("value", "");
                        if (!label.isEmpty() || !value.isEmpty()) {
                            return label + (value.isEmpty() ? "" : "=" + value)
                                    + (items.length() > 1 ? " 等 " + items.length() + " 项" : "");
                        }
                    }
                }
                return null;
            }
            case "list_card":
            case "list":
            case "grid_card":
            case "grid":
            case "todo_card":
            case "todo":
            case "contact_card":
            case "contact": {
                JSONArray items = firstArray(props, "items", "files", "options");
                return items != null && items.length() > 0 ? items.length() + " 项" : null;
            }
            case "steps_card":
            case "steps":
            case "timeline": {
                JSONArray steps = firstArray(props, "steps", "items");
                return steps != null && steps.length() > 0 ? steps.length() + " 步" : null;
            }
            case "json_viewer":
            case "json": {
                Object data = props.opt("data");
                if (data instanceof JSONObject) {
                    return ((JSONObject) data).length() + " 个字段";
                }
                if (data instanceof JSONArray) {
                    return ((JSONArray) data).length() + " 条";
                }
                String s = data != null ? String.valueOf(data) : null;
                return s != null && !s.trim().isEmpty() ? shortTail(s) : null;
            }
            case "code_card":
            case "code": {
                String lang = props.optString("language", "");
                String code = props.optString("code", "");
                int lineCount = code.isEmpty() ? 0 : code.split("\n", -1).length;
                if (!lang.isEmpty() || lineCount > 0) {
                    return (lang.isEmpty() ? "代码" : lang)
                            + (lineCount > 0 ? " · " + lineCount + " 行" : "");
                }
                return null;
            }
            case "agenda": {
                JSONArray items = props.optJSONArray("items");
                return items != null && items.length() > 0 ? items.length() + " 项" : null;
            }
            case "playlist": {
                JSONArray items = props.optJSONArray("items");
                return items != null && items.length() > 0 ? items.length() + " 首" : null;
            }
            case "location": {
                String address = firstNonEmpty(props.optString("address", ""),
                        props.optString("city", ""));
                return address.isEmpty() ? null : clip(address, 40);
            }
            case "diff": {
                JSONArray hunks = props.optJSONArray("hunks");
                if (hunks != null && hunks.length() > 0) return hunks.length() + " 行";
                return null; // 有标题（文件变更 · x）时不再重复路径
            }
            case "progress_card":
            case "progress":
            case "progress_ring": {
                int percent = props.optInt("percent", props.optInt("progress", -1));
                String label = props.optString("label", "");
                if (percent >= 0) return percent + "%";
                return label.isEmpty() ? null : label;
            }
            case "alert_card":
            case "alert":
            case "note_card":
            case "note":
            case "quote":
            case "markdown_card":
            case "markdown":
            case "html":
            case "web":
            case "info_card":
            case "info": {
                String content = firstNonEmpty(props.optString("content", ""),
                        firstNonEmpty(props.optString("text", ""), props.optString("message", "")));
                if (!content.isEmpty()) return clip(content, 40);
                JSONArray items = props.optJSONArray("items");
                return items != null && items.length() > 0 ? items.length() + " 项" : null;
            }
            default: {
                String content = props.optString("content", "");
                if (!content.trim().isEmpty()) return clip(content, 40);
                int n = props.length();
                return n > 0 ? n + " 项数据" : null;
            }
        }
    }

    private static JSONArray firstArray(JSONObject props, String... keys) {
        for (String k : keys) {
            JSONArray arr = props.optJSONArray(k);
            if (arr != null && arr.length() > 0) return arr;
        }
        return null;
    }

    private static String nested(JSONObject props, String obj, String key) {
        JSONObject inner = props.optJSONObject(obj);
        return inner != null ? inner.optString(key, "") : "";
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a.trim() : (b != null ? b.trim() : "");
    }

    private static String clip(String s, int max) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= max) return oneLine;
        return oneLine.substring(0, max) + "…";
    }

    private static String shortTail(String path) {
        String p = path.replaceAll("/+$", "");
        int i = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return i >= 0 && i < p.length() - 1 ? p.substring(i + 1) : p;
    }
}
