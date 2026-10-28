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

    /**
     * 文件类卡片教给模型的 props 形状（<b>模型可见文案唯一出处</b>）。
     *
     * <p>这段文字此前在 {@code ui_component} 的 {@code @Param(props)} 注解、
     * {@code SystemUIComponentTool#getParameterDescriptions()} 与
     * {@code AIToolManager} 的 ToolDefinition 里各抄了一份，改一处漏两处。
     * 2026-10-23 真机 bug 就出在这里：三处都只写 {@code files:[{name,path,size,type}]}，
     * 没说什么形态，模型于是把 {@code path} 写成目录、文件名只留在 {@code name}，
     * 卡片渲染正常但每条链接都打不开。现在把口径写进同一句，三处引用本常量。</p>
     */
    public static final String PROPS_DOC_FILE_CARDS =
            "file_list:{title,files:[{name,path,size,type}]}; file_card:{name,size,type,path}"
                    + "（两者 path 都必须是**含文件名的完整路径**，不能只给目录；"
                    + "name 只放文件名；type 填 file 或 dir；"
                    + "file_list 浏览目录可另给顶层 path=目录，点目录行在卡内进下一级）";

    /**
     * 题库卡（含 2026-10-23 补的填空/简答）的形状，三处 props 长说明共用。
     * 填空 {@code answer} 可用 {@code |} 分隔多个可接受答案；简答给参考答案后由用户自评。
     */
    public static final String PROPS_DOC_QUIZ_CARD =
            "quiz_card:{type:'single|multiple|judge|fill|short',question,options:[],answer,analysis,placeholder}"
                    + "（fill=填空题：answer 可用 | 分隔多个可接受答案，忽略空白/全半角/大小写；"
                    + "short=简答题：提交后展示参考答案由用户自评，不自动判分）";

    /**
     * 图表卡形状（三处 props 长说明共用）。2026-10-23 扩型：生产者不再只有 python_chart
     * 的 PNG——表格类结果会自动挑数值列出图，python_chart 也能 render=card 直出原生图表。
     */
    public static final String PROPS_DOC_CHART =
            "chart:{chartType:'bar|line|area|stacked_bar|pie|scatter|radar',title,categories:[分类],"
                    + "series:[{name,data:[数值]}]}（scatter 用 series:[{name,points:[[x,y],...]}] 给数值坐标，"
                    + "缺省时用 data 下标做 x；stacked_bar 多系列按分类堆叠；"
                    + "radar 以 categories 为轴、每个系列一条闭合折线；pie 用 series[0].data 对应 categories）";

    /**
     * html 卡的 props 形状与**按来源分档的能力口径**（模型可见文案唯一出处，三处 props 长说明共用）。
     *
     * <p>此前三份 props 抄本（外加在线定义里 component_type 那段单独抄的一句）都在售卖
     * "图片点击全屏预览 / 卡片内链接自身加载"，可这两项只在
     * {@code LOCAL_FILE}（工作区本地网页）与 {@code REMOTE_PAGE} 成立：模型直给的 HTML 字符串
     * 走 {@code INLINE_UNTRUSTED}——JS 关、{@code on*}/script 被净化剥掉、卡片内不导航，
     * 页内按钮必然点不动（真机口径见 {@link HtmlSandbox}）。文案照实现写，模型才不会给用户画死路。</p>
     *
     * <p>页内回传（{@code Android.submit}）的用法 2026-10-24 写进来，同日把
     * {@code html_component_submit} 的出厂默认从 false 翻成 true（真机端到端验通：页面按钮 →
     * 本卡结果槽 → {@code get_result}）。它今天仍只对这几个条件成立：档位是 {@code LOCAL_FILE}、
     * 卡片创建时带 {@code actions}（否则没有可寻址的 component_id）、开关没被关掉。
     * 所以文案把"被关掉时静默无效、只会等到 pending"一起写清——模型拿到空结果时知道退回 actions，
     * 而不是以为链路坏了反复重试。</p>
     */
    public static final String PROPS_DOC_HTML =
            "html:{html:'<h3>标题</h3>…'(内联串) 或 url:'file:///…/report.html'(工作区内本地网页)"
                    + " 或 'https://…'(远程页), title, maxHeight(默认360dp,超出内部滚动),"
                    + " style(内容级:{background,color,fontSize})}"
                    + "（能力按来源分档、不由 props 决定：内联 HTML 会剥掉 script/onclick/表单、"
                    + "卡片内不导航(外链交回应用浏览器)，页内按钮点了没反应；"
                    + "工作区 .html 文件与 https 页可跑页面脚本(图表能画)、可卡内导航；"
                    + "Agent 产在工作区里的 .html/.htm 会被自动挂成 html 卡，不用手动建。"
                    + "收集用户操作两条路：①任何卡都行——create 时带 actions:[{label,value,action:'callback'}]，"
                    + "按钮回传写死的 value；②只有工作区本地网页文件、且卡带 actions——页面 JS 调"
                    + "Android.submit(payload) 把运行时值(选中项/表单值/图表 drill-down)送进**本卡**结果槽，"
                    + "payload 当不透明字符串、上限 16KB、形状用纯值或 {\"action\":..,\"inputs\":{..}}，"
                    + "随后 ui_component(action=get_result, component_id=…) 取回；"
                    + "component_id 由 native 绑定、页面 JS 改不了，值来自页面内容需自行校验；"
                    + "页内回传出厂已启用(应用侧可关；被关掉时 submit 静默无效、get_result 只等到 pending，"
                    + "那就退回 actions)）";

    /**
     * markdown_doc 卡的 props 形状（与 {@link #PROPS_DOC_HTML} 同一处 props 长说明共用）。
     *
     * <p>与 markdown_card 的分工写进模型可见文案：小段富文本走 markdown_card（Markwon/TextView），
     * 整篇 .md 文档走 markdown_doc（markdown-it + 插件 + KaTeX + 代码高亮），否则模型会拿
     * 小文本卡去渲染整篇文档、脚注/公式/表格一类的语法全退化。</p>
     */
    public static final String PROPS_DOC_MARKDOWN =
            "markdown_doc:{path:'files/报告.md'(工作区 .md 文件) 或 content:'# 标题…'(markdown 原文),"
                    + " title, maxHeight(默认560dp,超出内部滚动)}"
                    + "（整篇文档渲染：markdown-it + 脚注/定义列表/任务列表 + KaTeX 公式 + 代码高亮，"
                    + "按内容量高、超出内部滚动；文件不存在/读取失败会返回错误卡，不会静默空白。"
                    + "正文里的一小段富文本仍用 markdown_card，整篇 .md 才用 markdown_doc）";

    /**
     * playlist / agenda / location / diff 四张卡的 props 形状（三处 props 长说明共用）。
     * playlist 与播放器单例配套（队列 + 上下首）；diff 由写文件工具在
     * additionalInfo.diff 里产出（file_generator：写/追加/改/预览差异）。
     */
    public static final String PROPS_DOC_EXTRA_CARDS =
            "playlist:{title,items:[{name,url,artist,size}]}（音频队列：多曲目用一张卡，行点击从该首开始，"
                    + "通知栏/锁屏可上下首）; "
                    + "agenda:{title,items:[{date,time,title,desc,status:'done|current|todo|failed'}]}"
                    + "（按天分组的时间轴/日程）; "
                    + "location:{title,address,city,latitude,longitude,accuracy,provider}"
                    + "（地址+坐标，自带「在地图中打开/复制坐标」动作）; "
                    + "diff:{title,path,before,after 或 hunks:[{type:'ctx|add|del',text}]}"
                    + "（变更对比，+/- 着色；before/after 由渲染端本地算行级差异）";

    /** canonical 名 → 注册表里原生实现占用的 key（即该组件 getType() 的返回值） */
    public static final Map<String, String> CANONICAL_TARGET;

    /**
     * 场景短名 → 实现自报名（与 {@link #CANONICAL_TARGET} 同样需要在注册表里占上短名 key）。
     * 目录教模型用 weather/contact/todo 短名，但实现 {@code getType()} 报的是 *_card；
     * 不注册短名 key 时，模型照目录用短名会解析不到实现而掉进 DynamicCardView 原始 JSON 兜底。
     */
    public static final Map<String, String> SCENE_TARGET;

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

        Map<String, String> scene = new LinkedHashMap<>();
        scene.put("weather", "weather_card");
        scene.put("contact", "contact_card");
        scene.put("todo", "todo_card");
        scene.put("file_list", "file_list");
        scene.put("layout", "layout");
        SCENE_TARGET = Collections.unmodifiableMap(scene);

        Map<String, String> alias = new LinkedHashMap<>();
        // 2026-10-25：NoteCardView/GridCardView 从 baafa107^ 恢复（用户要求捞回）。
        // note_card/grid_card 不再归一到 info/list；短名 note/grid 指向各自卡片。
        // 历史会话里的 type="note_card"/"grid_card" 现在会命中恢复后的原生实现。
        alias.put("note", "note_card");
        alias.put("grid", "grid_card");
        alias.put("info_card", "info");
        alias.put("list_card", "list");
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
        // 2026-10-23 扩型：面积/堆叠柱/散点/雷达（产物可以是 python_chart render=card 或表格自动出图）
        alias.put("area_chart", "chart");
        alias.put("stacked_bar_chart", "chart");
        alias.put("scatter_chart", "chart");
        alias.put("radar_chart", "chart");
        alias.put("code_card", "code");
        alias.put("json_viewer", "json");
        alias.put("markdown_card", "markdown");
        // 2026-10-25：整篇文档卡（.md 文件/全文）。短名 md 方便模型直接写 component_type=md
        alias.put("md", "markdown_doc");
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
        // info 的必填是 items：InfoCardView.canRender 就是 optJSONArray("items") != null，
        // title 反而可选。此前必填/可选写反（目录教"必填 title"），派生式守卫抓到后改正。
        SCHEMA.put("info", new String[]{CATEGORY_CONTENT, "条目 items[{label,value}]", "标题 title", ""});
        SCHEMA.put("note_card", new String[]{CATEGORY_CONTENT, "正文 content", "类型 type=note|quote|tip|summary", "作者 author"});
        // list 的可选字段按 ListCardView 真读的字段写（icon/title/description/value/url）；
        // 此前目录里承诺的 "布局 variant=list|grid, columns" 渲染器一个字都不读，2026-10-20 删掉。
        SCHEMA.put("list", new String[]{CATEGORY_CONTENT, "条目 items[{icon,title,description,value}]", "标题 title",
                "网格长相请直接用 grid 之外的 list 卡片；variant/columns 未被 ListCardView 支持"});
        SCHEMA.put("grid_card", new String[]{CATEGORY_CONTENT, "条目 items[{icon,label,link,copy}]", "标题 title", "列数 columns"});
        SCHEMA.put("steps", new String[]{CATEGORY_CONTENT, "步骤 steps[{title,status,description}]", "标题 title", "status=done|current|todo|failed"});
        SCHEMA.put("alert", new String[]{CATEGORY_CONTENT, "正文 content", "级别 level=info|success|warning|error", "标题 title, actions[]"});
        SCHEMA.put("markdown", new String[]{CATEGORY_CONTENT, "正文 content", "标题 title", ""});
        // 2026-10-25：整篇 markdown 文档（.md 文件/全文）独立成卡，避免与"正文小段"共用一条弱管线
        SCHEMA.put("markdown_doc", new String[]{CATEGORY_CONTENT, "path 或 content", "标题 title, maxHeight(dp)",
                "整篇 .md 文档：markdown-it + 脚注/定义列表/任务列表 + KaTeX 公式 + 代码高亮"});
        SCHEMA.put("code", new String[]{CATEGORY_CONTENT, "代码 code", "语言 language", "标题 title"});
        SCHEMA.put("html", new String[]{CATEGORY_CONTENT, "html 或 url", "标题 title",
                "maxHeight(dp)；脚本/回传按来源分档——内联串禁脚本、页内按钮无效；"
                        + "工作区 .html 文件可跑脚本，页内可用 Android.submit(payload) 回传"
                        + "（需卡带 actions，见 PROPS_DOC_HTML）"});
        // table 两个都是必填：TableCardView.canRender 同时要求 headers 与 rows 数组存在，
        // rows 此前被放进"可选"，模型只给 headers 就会掉原始 JSON 兜底。
        SCHEMA.put("table", new String[]{CATEGORY_DATA, "表头 headers[], 数据 rows[[]]",
                "标题 title, align=left|center", ""});
        // chart 两个都是必填：ChartCardView.canRender 要求 chartType 非空 **且** series 非空数组，
        // 此前 series 被归到"可选"、categories 混在注释里 → 模型只给 chartType+categories 就渲不出卡。
        SCHEMA.put("chart", new String[]{CATEGORY_DATA,
                "类型 chartType=bar|line|area|stacked_bar|pie|scatter|radar, 系列 series[{name,data[]}]",
                "分类 categories[], 标题 title, 散点 points[[x,y]]",
                "scatter 可用 points 给数值坐标；stacked_bar 多系列按分类堆叠；radar 以 categories 为轴"});
        SCHEMA.put("metric", new String[]{CATEGORY_DATA, "指标 metrics[{label,value,color}]", "标题 title",
                "单位 unit, trend"});
        // progress 的必填名必须是 progress：ProgressCardView.canRender 就是 props.has("progress")，
        // 目录此前教的是 percent（0 处读取）→ 模型照目录传就掉进 DynamicCardView 原始 JSON 兜底。
        SCHEMA.put("progress", new String[]{CATEGORY_DATA, "进度 progress(0-100)", "标题 title",
                "文案 description, 状态 status"});
        // 模型可通过工具调用参数声明 display/collapsed 控制卡片显隐。
        SCHEMA.put("json", new String[]{CATEGORY_DATA, "数据 data(对象/数组，或字符串形态的 JSON 也会美化)",
                "标题 title, 展示 display=inline|peek|hidden",
                "旧字段 collapsed=true 等效 peek；交互/正文类仍强制 inline"});
        SCHEMA.put("image", new String[]{CATEGORY_MEDIA,
                "图片 images[]（每项完整 http(s)、content://、file:// 或绝对路径；不要只给文件名/猜目录）",
                "标题 title", "columns"});
        SCHEMA.put("video", new String[]{CATEGORY_MEDIA, "地址 url", "标题 title", "封面 poster"});
        SCHEMA.put("audio", new String[]{CATEGORY_MEDIA, "地址 url", "标题 title", "时长 duration"});
        SCHEMA.put("file", new String[]{CATEGORY_MEDIA, "文件名 name", "路径 path 或 uri", "大小 size, 类型 type"});
        SCHEMA.put("link", new String[]{CATEGORY_MEDIA, "地址 url", "标题 title", "描述 description, 图标 icon"});
        SCHEMA.put("quiz", new String[]{CATEGORY_TASK, "题干 question", "选项 options[]", "答案 answer, 解析 analysis, 题型 type=single|multiple|judge"});
        // contact 必填必须是 value：ContactCardView.canRender 读 optString("value") 非空，
        // 目录此前教 name/phone（卡片一个字都不读）→ 照目录填会渲染不出联系卡。
        SCHEMA.put("contact", new String[]{CATEGORY_SCENE, "号码/地址 value",
                "类型 type=phone|sms|email", "标题 title, 描述 description, actions[]"});
        // weather 必填必须是 temp：WeatherCardView.canRender 读 optString("temp") 非空；
        // 目录此前写必填 city + 可选 data，而 data 没有任何一处被读取。
        SCHEMA.put("weather", new String[]{CATEGORY_SCENE, "温度 temp", "城市 city",
                "天气 text, 图标 icon, 湿度 humidity, 风向 windDir, 风级 windScale, 预报 forecast[{date,text,tempMin,tempMax}]"});
        // file_list 是场景卡但以前不进目录 → 模型只能从工具那行长名单里猜字段
        // 2026-10-23：目录里只列字段名不列形态，模型就把 path 写成目录、文件名留在 name，
        // 卡片每条链接都打不开（真机实测）；note 位补上路径口径，与 PROPS_DOC_FILE_CARDS 同说
        SCHEMA.put("file_list", new String[]{CATEGORY_MEDIA, "文件 files[{name,path,size,type}]",
                "标题 title, 当前目录 path",
                "条目 path 必须是含文件名的完整路径（只给目录时卡片按 name 补齐）；type=file|dir"});
        // 2026-10-23 新增：队列 / 日程 / 位置 / 变更对比
        SCHEMA.put("playlist", new String[]{CATEGORY_MEDIA, "曲目 items[{name,url,artist,size}]",
                "标题 title",
                "url 支持 http(s)/本地路径/工作区相对路径；多曲目进同一队列，通知栏可上下首"});
        SCHEMA.put("agenda", new String[]{CATEGORY_SCENE, "日程 items[{date,time,title,desc,status}]",
                "标题 title",
                "按 date 首次出现顺序分组；status=done|current|todo|failed（默认 todo）"});
        SCHEMA.put("location", new String[]{CATEGORY_SCENE, "地址 address",
                "城市 city, 纬度 latitude, 经度 longitude, 精度 accuracy, 来源 provider",
                "有经纬度时提供「在地图中打开 / 复制坐标」动作；0,0 视为未定位"});
        SCHEMA.put("diff", new String[]{CATEGORY_CONTENT, "改动 hunks[{type,text}]",
                "标题 title, 路径 path, 旧内容 before, 新内容 after",
                "hunks 优先；否则用 before/after 本地算行级差异；type=ctx|add|del。"
                        + "文件真实改动不要手写这张卡：用 file_generator(action=diff) 出预览卡"
                        + "（按文件实际内容算），手写 hunks 只是示意图、会和真实内容不一致"});
        SCHEMA.put("todo", new String[]{CATEGORY_SCENE, "待办 items[{text,done}]", "标题 title", ""});
        // 注意：长任务活动卡 type=job 不在"面向模型的目录"里——它是 linux_shell(termux_submit) 经
        // withComponent 直接挂载的工具产物卡，不由模型 create_component 生成；渲染只在 ComponentRegistry。
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
        return SCENE_TARGET.containsKey(type);
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
