package com.oilquiz.app.ai.chat.component;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 组件类型目录（B1-2）：canonical 类型 + alias + 字段说明。
 *
 * <p>问题：注册表里同时有 {@code table_card}（原生卡）与 {@code table}（Layout 原语）、
 * {@code steps_card} 与 {@code steps}、{@code alert_card} 与 {@code alert}、
 * {@code metric_card} 与 {@code stat}……加上 32 个 Layout 原语，模型能选的名字约 63 个，
 * 同一个语义两种长相，还经常名字与参数对不上。
 *
 * <p>口径：
 * <ul>
 *   <li><b>canonical</b>：面向模型的名字，18 个通用类型 + 4 个场景类型；</li>
 *   <li><b>alias</b>：旧名/重复名一律解析到 canonical，历史数据与旧提示词继续可用，
 *       持久化结构不动（{@code ComponentData.type} 存什么都能渲染）；</li>
 *   <li><b>原生实现赢名字</b>：{@code table}/{@code steps}/{@code alert}/{@code stat} 等
 *       原先被 Layout 原语占用的名字，现在指向原生卡片；原语仍可用
 *       {@code primitive_<名字>} 访问（如 {@code primitive_timeline}）。</li>
 * </ul>
 *
 * <p>{@link #catalogJson()} 是给模型看的目录（名字/分类/必填/可选），
 * 由 {@code ui_component(action=list_types)} 等出口复用，避免再维护第二份名单。
 */
public final class ComponentTypes {

    /** canonical 名 → 注册表里原生实现占用的 key（即该组件 getType() 的返回值） */
    public static final Map<String, String> CANONICAL_TARGET;

    /** 旧名 / 重复名 / 场景别名 → canonical */
    public static final Map<String, String> ALIAS;

    /** 分类（给目录分组用） */
    public static final String CATEGORY_CONTENT = "content";
    public static final String CATEGORY_DATA = "data";
    public static final String CATEGORY_MEDIA = "media";
    public static final String CATEGORY_TASK = "task";
    public static final String CATEGORY_SCENE = "scene";

    /** canonical → {category, required[], optional[]} */
    private static final Map<String, String[]> SCHEMA = new LinkedHashMap<>();

    static {
        Map<String, String> target = new LinkedHashMap<>();
        // 内容
        target.put("info", "info_card");
        target.put("list", "list_card");
        target.put("steps", "steps_card");
        target.put("alert", "alert_card");
        target.put("markdown", "markdown_card");
        target.put("code", "code_card");
        target.put("html", "html");
        // 数据
        target.put("table", "table_card");
        target.put("chart", "chart");
        target.put("metric", "metric_card");
        target.put("progress", "progress_card");
        target.put("json", "json_viewer");
        // 媒体
        target.put("image", "image_grid");
        target.put("video", "video");
        target.put("audio", "audio");
        target.put("file", "file_card");
        target.put("link", "link_card");
        // 任务/学习
        target.put("quiz", "quiz_card");
        CANONICAL_TARGET = Collections.unmodifiableMap(target);

        Map<String, String> alias = new LinkedHashMap<>();
        // 2026-10-20：note_card / grid_card 的原生实现（NoteCardView/GridCardView）已删——
        // 下面这两条 alias 让 render() 先归一到 info/list，那两个注册键从建立起就永不被命中。
        // alias 必须保留：历史会话里持久化的 type="note_card"/"grid_card" 仍要能渲染
        // （note_card 落到 info 卡，props 里的 content 由 DynamicCardView 兜底通道显示）。
        alias.put("info_card", "info");
        alias.put("note_card", "info");
        alias.put("note", "info");
        alias.put("list_card", "list");
        alias.put("grid_card", "list");
        alias.put("grid", "list");
        alias.put("table_card", "table");
        alias.put("steps_card", "steps");
        alias.put("timeline", "steps");
        alias.put("alert_card", "alert");
        alias.put("metric_card", "metric");
        alias.put("stat", "metric");
        alias.put("progress_card", "progress");
        alias.put("progress_ring", "progress");
        alias.put("bar_chart", "chart");
        alias.put("pie_chart", "chart");
        alias.put("sparkline", "chart");
        alias.put("code_card", "code");
        alias.put("json_viewer", "json");
        alias.put("markdown_card", "markdown");
        alias.put("image_grid", "image");
        alias.put("file_card", "file");
        alias.put("link_card", "link");
        alias.put("quiz_card", "quiz");
        alias.put("question_card", "quiz");
        alias.put("web", "html");
        alias.put("weather_card", "weather");
        alias.put("contact_card", "contact");
        alias.put("todo_card", "todo");
        ALIAS = Collections.unmodifiableMap(alias);

        // required/optional 只列真正决定渲染的字段，其余按 props 自由扩展
        SCHEMA.put("info", new String[]{CATEGORY_CONTENT, "标题 title", "条目 items[{label,value}]", ""});
        // list 的可选字段按 ListCardView 真读的字段写（icon/title/description/value/url）；
        // 此前目录里承诺的 "布局 variant=list|grid, columns" 渲染器一个字都不读，2026-10-20 删掉。
        SCHEMA.put("list", new String[]{CATEGORY_CONTENT, "条目 items[{icon,title,description,value}]", "标题 title",
                "网格长相请直接用 grid 之外的 list 卡片；variant/columns 未被 ListCardView 支持"});
        SCHEMA.put("steps", new String[]{CATEGORY_CONTENT, "步骤 steps[{title,status,description}]", "标题 title", "status=done|current|todo|failed"});
        SCHEMA.put("alert", new String[]{CATEGORY_CONTENT, "正文 content", "级别 level=info|success|warning|error", "标题 title, actions[]"});
        SCHEMA.put("markdown", new String[]{CATEGORY_CONTENT, "正文 content", "标题 title", ""});
        SCHEMA.put("code", new String[]{CATEGORY_CONTENT, "代码 code", "语言 language", "标题 title"});
        SCHEMA.put("html", new String[]{CATEGORY_CONTENT, "html 或 url", "标题 title", "maxHeight(dp)；脚本按来源分档，见 HtmlSandbox"});
        SCHEMA.put("table", new String[]{CATEGORY_DATA, "表头 headers[]", "数据 rows[[]]", "标题 title, align=left|center"});
        SCHEMA.put("chart", new String[]{CATEGORY_DATA, "类型 chartType=bar|line|pie", "系列 series[{name,data[]}]", "分类 categories[], 标题 title"});
        SCHEMA.put("metric", new String[]{CATEGORY_DATA, "指标 items[{label,value}]", "标题 title", "单位 unit, trend"});
        SCHEMA.put("progress", new String[]{CATEGORY_DATA, "百分比 percent", "标题 title", "文案 label"});
        // 模型可通过工具调用参数声明 display/collapsed 控制卡片显隐。
        SCHEMA.put("json", new String[]{CATEGORY_DATA, "数据 data",
                "标题 title, 展示 display=inline|peek|hidden",
                "旧字段 collapsed=true 等效 peek；交互/正文类仍强制 inline"});
        SCHEMA.put("image", new String[]{CATEGORY_MEDIA, "图片 images[]", "标题 title", "columns"});
        SCHEMA.put("video", new String[]{CATEGORY_MEDIA, "地址 url", "标题 title", "封面 poster"});
        SCHEMA.put("audio", new String[]{CATEGORY_MEDIA, "地址 url", "标题 title", "时长 duration"});
        SCHEMA.put("file", new String[]{CATEGORY_MEDIA, "文件名 name", "路径 path 或 uri", "大小 size, 类型 type"});
        SCHEMA.put("link", new String[]{CATEGORY_MEDIA, "地址 url", "标题 title", "描述 description, 图标 icon"});
        SCHEMA.put("quiz", new String[]{CATEGORY_TASK, "题干 question", "选项 options[]", "答案 answer, 解析 analysis, 题型 type=single|multiple|judge"});
        SCHEMA.put("weather", new String[]{CATEGORY_SCENE, "城市 city", "数据 data", "标题 title"});
        SCHEMA.put("contact", new String[]{CATEGORY_SCENE, "姓名 name", "电话 phone", "actions[]"});
        SCHEMA.put("todo", new String[]{CATEGORY_SCENE, "待办 items[{text,done}]", "标题 title", ""});
    }

    private ComponentTypes() {
    }

    /**
     * 原语逃生口说明（2026-10-20 加）：{@code table}/{@code steps}/{@code alert}/{@code stat}/
     * {@code timeline}/{@code bar_chart}/{@code pie_chart}/{@code sparkline}/{@code progress_ring}
     * 这些短名按"原生实现赢名字"被原生卡片认领了，Layout 原语的同名长相只能走
     * {@code primitive_<名字>}。以前这件事只写在类注释里，模型侧看不到，逃生口等于不存在
     * ——全项目 grep {@code primitive_} 除了注册那一行，没有任何调用方用过它。
     * 目录（{@link #catalogJson()} 的使用方）与 {@code ui_component(action=list_types)} 都要带上这句。
     */
    public static final String PRIMITIVE_HINT =
            "短名 table/steps/alert/stat 指向原生卡片实现（table_card/steps_card/alert_card/metric_card）；"
                    + "timeline/bar_chart/pie_chart/sparkline/progress_ring 也已被卡片认领。"
                    + "想要 Layout 原语那一套长相，名字前加 primitive_（如 primitive_timeline、primitive_stat）";

    /**
     * 把任意输入名解析成 canonical 名。
     *
     * @return 已知类型返回 canonical；未知类型原样返回（交给注册表/兜底渲染）
     */
    public static String resolve(String type) {
        if (type == null || type.isEmpty()) return type;
        String canonical = lookup(type);
        if (canonical == null) {
            // 模型常把 info_card 写成 info.card / info-card；先归一再看一次。
            // 归一后的名字仍不认识时保留原样，交给注册表/兜底渲染，不吞掉未知类型。
            String normalized = normalize(type);
            if (!normalized.equals(type)) canonical = lookup(normalized);
        }
        return canonical != null ? canonical : type;
    }

    /** 类型名归一：去空白、转小写、点/横线/空格统一成下划线 */
    public static String normalize(String type) {
        if (type == null) return null;
        String t = type.trim().toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder(t.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            char out = (c == '.' || c == '-' || c == ' ' || c == '\t' || c == '\n') ? '_' : c;
            if (out == '_') {
                if (lastUnderscore) continue;
                lastUnderscore = true;
            } else {
                lastUnderscore = false;
            }
            sb.append(out);
        }
        return sb.toString();
    }

    private static String lookup(String type) {
        if (CANONICAL_TARGET.containsKey(type)) return type;
        return ALIAS.get(type);
    }

    /** 注册表实际存放该组件用的 key（canonical → 原生实现的类型名） */
    public static String targetKey(String canonical) {
        return CANONICAL_TARGET.getOrDefault(canonical, canonical);
    }

    /** 是否面向模型的 canonical 类型 */
    public static boolean isCanonical(String type) {
        return CANONICAL_TARGET.containsKey(type);
    }

    /** 场景类型（有专用渲染器，不进 18 个通用目录） */
    public static boolean isScene(String type) {
        return "weather".equals(type) || "contact".equals(type) || "todo".equals(type)
                || "layout".equals(type) || "file_list".equals(type);
    }

    /**
     * 给模型看的类型目录：[{type,category,required,optional}, …]。
     * 未注册的字段留空字符串，避免在提示词里写出"看起来像必填"的噪音。
     */
    public static JSONArray catalogJson() {
        JSONArray out = new JSONArray();
        for (Map.Entry<String, String[]> e : SCHEMA.entrySet()) {
            try {
                JSONObject o = new JSONObject();
                o.put("type", e.getKey());
                o.put("category", e.getValue()[0]);
                o.put("required", e.getValue()[1]);
                o.put("optional", e.getValue()[2]);
                o.put("note", e.getValue()[3]);
                out.put(o);
            } catch (org.json.JSONException ignored) {
                // 单条组装失败不影响目录整体可用
            }
        }
        return out;
    }

    /** 目录的一行式文本（提示词/工作区速查表用） */
    public static String catalogLine() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String[]> e : SCHEMA.entrySet()) {
            sb.append(e.getKey()).append('(').append(e.getValue()[1]);
            if (!e.getValue()[2].isEmpty()) sb.append(" / ").append(e.getValue()[2]);
            sb.append(") ");
        }
        return sb.toString().trim();
    }
}
