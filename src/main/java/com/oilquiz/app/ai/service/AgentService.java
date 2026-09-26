package com.oilquiz.app.ai.service;

import android.content.Context;
import com.oilquiz.app.ai.tool.AITool;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.ToolSchemaExtractor;
import com.oilquiz.app.ai.tool.ToolSchemaExtractor.ExtractedSchema;
import com.oilquiz.app.ai.tool.ToolDependencyChecker;
import com.oilquiz.app.ai.tool.ToolDependencyChecker.DependencyCheckResult;
import com.oilquiz.app.ai.tool.ToolDependencyChecker.PreToolCall;
import com.oilquiz.app.util.AILogger;
import com.google.gson.Gson;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AgentService {
    private static final String TAG = "AgentService";
    private static final int MAX_TOOL_LOOPS = 5;
    private static final long TOOL_TIMEOUT_MS = 30000;
    private static final long CACHE_EXPIRY_MS = 300000;
    private static final int MAX_RETRY_COUNT = 2;
    
    private static volatile AgentService instance;
    private static final Object LOCK = new Object();

    private static final Pattern TOOL_CALL_PATTERN_STANDARD = Pattern.compile(
        "<\\|tool_call_begin\\|>[\\s\\S]*?function\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?arguments\\s*:\\s*(\\{[^}]*\\})[\\s\\S]*?<\\|tool_call_end\\|>",
        Pattern.DOTALL
    );
    private static final Pattern TOOL_CALL_PATTERN_NO_END = Pattern.compile(
        "<\\|tool_call_begin\\|>.*?function\\s*:\\s*\"([^\"]+)\".*?arguments\\s*:\\s*(\\{[^}]*(?:\\{[^}]*\\}[^}]*)*\\})",
        Pattern.DOTALL
    );
    private static final Pattern TOOL_CALL_PATTERN_TOOLS_BLOCK = Pattern.compile(
        "TOOLS_CALL[\\s\\S]*?\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"arguments\"\\s*:\\s*(\\{[^}]*(?:\\{[^}]*\\}[^}]*)*\\})[\\s\\S]*?TOOLS_END",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TOOL_CALL_PATTERN_TOOLS_NO_END = Pattern.compile(
        "TOOLS_CALL[\\s\\S]*?\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"arguments\"\\s*:\\s*(\\{[^}]*(?:\\{[^}]*\\}[^}]*)*\\})",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TOOL_CALL_PATTERN_JSON = Pattern.compile(
        "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{[^}]*(?:\\{[^}]*\\}[^}]*)*\\})\\s*\\}",
        Pattern.DOTALL
    );
    private static final Pattern TOOL_CALL_PATTERN_MARKDOWN = Pattern.compile(
        "```json\\s*\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{[^}]*(?:\\{[^}]*\\}[^}]*)*\\})\\s*\\}\\s*```",
        Pattern.DOTALL
    );
    private static final Pattern TOOL_CALL_PATTERN_SIMPLE = Pattern.compile(
        "\"name\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"arguments\"\\s*:\\s*(\\{[^}]*(?:\\{[^}]*\\}[^}]*)*\\})",
        Pattern.DOTALL
    );
    private static final Pattern TOOL_CALL_PATTERN_FUNCTION = Pattern.compile(
        "function\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?arguments\\s*:\\s*(\\{[^}]*(?:\\{[^}]*\\}[^}]*)*\\})",
        Pattern.DOTALL
    );
    
    private static final Pattern TOOL_CALL_PATTERN_OPENAI = Pattern.compile(
        "\"tool_calls\"\\s*:\\s*\\[\\s*\\{[\\s\\S]*?\"function\"\\s*:\\s*\\{[\\s\\S]*?\"name\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"arguments\"\\s*:\\s*(\\{[^}]*(?:\\{[^}]*\\}[^}]*)*\\})[\\s\\S]*?\\}[\\s\\S]*?\\}[\\s\\S]*?\\]",
        Pattern.DOTALL
    );

    // Qwen2.5 原生工具调用格式（instruct 模型在系统提示含工具定义时自发输出）。
    // 注：结束标记字面量通过拼接构造，避免源码处理工具截断。
    private static final String QWEN_CALL_START = "<|tool_call_begin|>";
    private static final String QWEN_CALL_END = "<|im_" + "end|>";
    private static final Pattern TOOL_CALL_PATTERN_QWEN_NATIVE = Pattern.compile(
        Pattern.quote(QWEN_CALL_START)
            + "[\\s\\S]*?\"name\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"arguments\"\\s*:\\s*(\\{[\\s\\S]*?\\})[\\s\\S]*?"
            + "(?:" + Pattern.quote(QWEN_CALL_END) + "|$)",
        Pattern.DOTALL
    );

    // 通用块格式：<tool_call>...AgentLoopEngine 降级标签、部分模型自发输出）
    private static final Pattern TOOL_CALL_PATTERN_GENERIC_BLOCK = Pattern.compile(
        "<tool_call>[\\s\\S]*?\\{[\\s\\S]*?\"name\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"arguments\"\\s*:\\s*(\\{[\\s\\S]*?\\})[\\s\\S]*?</tool_call>",
        Pattern.DOTALL
    );

    // Qwen3.5 XML 形态：tool_call 块内 function=工具名 + parameter=参数名 标签
    // 完整块：<tool_call>...<function=...>......</tool_call>
    private static final Pattern QWEN35_TOOL_CALL_BLOCK = Pattern.compile(
        "<tool_call>\\s*<function=([^>\\n]+)>\\s*([\\s\\S]*?)\\s*\\s*" + "</" + "tool_call>",
        Pattern.DOTALL
    );
    // 兜底：<tool_call> 包裹缺失或未闭合（Qwen3-Coder 习惯直接输出 <function=，截断时 </tool_call> 丢失）
    private static final Pattern QWEN35_FUNCTION_BARE_BLOCK = Pattern.compile(
        "<function=([^>\\n]+)>\\s*([\\s\\S]*?)\\s*(?:" + "</" + "function>|\\z)",
        Pattern.DOTALL
    );
    // 参数：值捕获到 parameter 闭合标签、下一个 parameter 标签或 function 闭合标签为止（vLLM 同款容错，截断不丢参数）
    private static final Pattern QWEN35_PARAM_BLOCK = Pattern.compile(
        "<parameter=([^>\\n]+)>\\s*([\\s\\S]*?)(?=<parameter=|<" + "/parameter>|<" + "/function>|\\z)",
        Pattern.DOTALL
    );

    private final Context context;
    private final AIToolManager toolManager;
    private final ToolDependencyChecker dependencyChecker;
    private final List<ToolSchema> toolSchemas = new ArrayList<>();
    private final Map<String, ToolParamSchema> paramSchemas = new LinkedHashMap<>();
    private final Map<String, String> toolNameAliases = new HashMap<>();
    private final ConcurrentHashMap<String, String> toolResultCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> toolTimestamps = new ConcurrentHashMap<>();
    private ToolSelectionStrategy currentStrategy = ToolSelectionStrategy.STANDARD;
    private boolean useDynamicTools = true;
    
    private AgentService(Context context) {
        Context appContext;
        try {
            appContext = context.getApplicationContext();
        } catch (Exception e) {
            appContext = context;
        }
        this.context = appContext;
        this.toolManager = AIToolManager.getInstance(appContext);
        this.dependencyChecker = new ToolDependencyChecker(appContext, toolManager);
        buildAliasMap();
        autoDiscoverToolSchemas();
        registerDefaultTools();
    }
    
    public static AgentService getInstance(Context context) {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new AgentService(context);
                }
            }
        }
        return instance;
    }

    public enum ToolSelectionStrategy {
        MINIMAL,
        STANDARD,
        FULL
    }

    public static class ToolSchema {
        public final String name;
        public final String description;
        public final String paramDesc;

        public ToolSchema(String name, String description, String paramDesc) {
            this.name = name;
            this.description = description;
            this.paramDesc = paramDesc;
        }
    }

    public static class ToolParamSchema {
        public final String paramName;
        public final String type;
        public final String description;
        public final boolean required;
        public final Object defaultValue;

        public ToolParamSchema(String paramName, String type, String description, boolean required, Object defaultValue) {
            this.paramName = paramName;
            this.type = type;
            this.description = description;
            this.required = required;
            this.defaultValue = defaultValue;
        }
    }

    public static class ToolCall {
        public final String id;
        public final String name;
        public final String arguments;
        public Map<String, Object> resolvedArgs;

        public ToolCall(String name, String arguments) {
            this.id = java.util.UUID.randomUUID().toString();
            this.name = name;
            this.arguments = arguments;
            this.resolvedArgs = new HashMap<>();
        }

        public ToolCall(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
            this.resolvedArgs = new HashMap<>();
        }

        public ToolCall(String id, String name, Map<String, Object> resolvedArgs, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
            this.resolvedArgs = resolvedArgs != null ? resolvedArgs : new HashMap<>();
        }
    }

    public static class ToolResult {
        public final String toolName;
        public final String result;
        public final String errorMessage;
        public final boolean success;
        public final long executionTimeMs;
        public final int retryCount;

        public ToolResult(String toolName, String result, boolean success) {
            this.toolName = toolName;
            this.result = result;
            this.errorMessage = success ? null : result;
            this.success = success;
            this.executionTimeMs = 0;
            this.retryCount = 0;
        }

        public ToolResult(String toolName, String result, boolean success, long executionTimeMs, int retryCount) {
            this.toolName = toolName;
            this.result = result;
            this.errorMessage = success ? null : result;
            this.success = success;
            this.executionTimeMs = executionTimeMs;
            this.retryCount = retryCount;
        }
    }

    private void buildAliasMap() {
        toolNameAliases.put("get_weather", "ai_weather");
        toolNameAliases.put("weather_query", "ai_weather");
        toolNameAliases.put("weather", "ai_weather");
        toolNameAliases.put("get_location", "location");
        toolNameAliases.put("location_query", "location");
        toolNameAliases.put("get_current_location", "location");
        toolNameAliases.put("get_city", "location");
        toolNameAliases.put("get_gps", "location");
        toolNameAliases.put("network_search", "network_search");
        toolNameAliases.put("search", "network_search");
        toolNameAliases.put("web_search", "network_search");
        toolNameAliases.put("calculate", "python_calculate");
        toolNameAliases.put("calculator", "python_calculate");
        toolNameAliases.put("database_query", "database");
        toolNameAliases.put("database", "database");
        toolNameAliases.put("search_questions", "database");
        toolNameAliases.put("web_page_reader", "webpage_reader");
        toolNameAliases.put("read_webpage", "webpage_reader");
        toolNameAliases.put("read_url", "webpage_reader");
        toolNameAliases.put("file_analysis", "file_analyzer");
        toolNameAliases.put("app_op", "app_operation");
        toolNameAliases.put("navigate", "app_operation");
        toolNameAliases.put("go_to", "app_operation");
        toolNameAliases.put("open_page", "app_operation");
        toolNameAliases.put("create_tool", "create_dynamic_tool");
        toolNameAliases.put("dynamic_tool", "create_dynamic_tool");
        toolNameAliases.put("ocr", "ocr_recognize");
        toolNameAliases.put("ocr_recognize_pdf", "ocr_recognize");
        toolNameAliases.put("文字识别", "ocr_recognize");
        toolNameAliases.put("图片识别", "ocr_recognize");
        toolNameAliases.put("盯梢", "screen_watch");
        toolNameAliases.put("盯屏", "screen_watch");
        toolNameAliases.put("监控屏幕", "screen_watch");
        toolNameAliases.put("识别图片文字", "ocr_recognize");
        toolNameAliases.put("chat_history", "chat_history");
        toolNameAliases.put("历史记录", "chat_history");
        toolNameAliases.put("对话历史", "chat_history");
        toolNameAliases.put("历史", "chat_history");
        // 系统UI组件控制：遗留名 → ui_component（对话框/提示条/进度条/输入等）
        toolNameAliases.put("system_ui_control", "ui_component");
        toolNameAliases.put("ui_control", "ui_component");
    }

    private void registerDefaultTools() {
        registerToolSchema("ai_weather", "天气查询工具：获取城市实时天气/预报/空气质量等。current返回温度/体感/天气现象/风力湿度/紫外线；forecast返回逐日预报；air_quality返回AQI/PM2.5。天气是实时数据，用本工具获取，训练数据不采纳", "action(操作类型: current实时/forecast预报/hourly逐小时/air_quality空气质量/alerts预警/indices生活指数/all全部，按需求选), city(城市名 或 和风城市编码，与经纬度二选一), lat(纬度,可选), lon(经度,可选)");
        registerToolSchema("get_weather", "查询天气", "action(操作类型: current/forecast/hourly/air_quality/alerts/indices/all), city(城市名称,可选), lat(纬度,可选), lon(经度,可选)");
        registerToolSchema("weather", "查询天气", "action(操作类型: current/forecast/hourly/air_quality/alerts/indices/all), city(城市名称,可选), lat(纬度,可选), lon(经度,可选)");
        registerToolSchema("location", "位置查询工具：获取当前位置（经纬度/城市/地址）。get_current返回经纬度+城市+地址；get_city返回城市名；get_coordinates返回经纬度。位置服务未开启自动引导开启", "action(操作类型: get_current默认/get_city/get_coordinates)");
        registerToolSchema("get_location", "获取位置信息", "action(操作类型: get_current/get_city/get_coordinates)");
        registerToolSchema("network_search", "网络搜索工具（秘塔搜索引擎）：联网搜索+智能问答+网页读取。search返回标题/链接/摘要；ask返回答案+引用来源；read_url读取网页正文。实时/最新信息必须联网搜索，禁止凭训练知识编造", "action(操作类型: search/ask/read_url/get_webpage/extract_info/summarize/search_and_read/smart_search,默认search), query(搜索关键词,search用), question(问答问题,ask用), url(网页URL,read_url/get_webpage用), model(问答模式:concise/detail/research,ask用,默认concise), limit(结果数量,默认5), num_results(结果数量别名,默认5)");
        registerToolSchema("search", "搜索网络信息", "query(搜索关键词,必填), limit(结果数量限制,默认5)");
        registerToolSchema("python_calculate", "使用Python进行数学计算", "expression(数学表达式,必填), task(任务描述,可选)");
        registerToolSchema("calculate", "执行数学计算", "expression(数学表达式,必填)");
        registerToolSchema("calculator", "数学计算器：计算算术表达式(支持+ - * / % ^ 括号、小数)。除零/非法表达式返回明确错误。简单计算用本工具，复杂数据分析用python_calculate", "expression(算术表达式,必填,如 3.5*(2+4)/7 或 2^10)");
        registerToolSchema("database", "数据库操作工具，支持任意SQL、表结构查看、题目查询与管理、用户管理、分数记录等", "action(操作类型: execute_sql/list_tables/get_table_schema/execute_query/get_questions/search_questions/get_question_count/get_question_statistics/get_question_by_id/add_questions/update_question/delete_question/get_user/add_user/get_score_history/add_score/get_average_score,必填), sql(SQL语句,execute_sql用), table_name(表名,get_table_schema用), query(SQL查询语句,可选), keyword(搜索关键词,可选), id(题目/用户ID,可选), category(题目分类,可选), type(题目类型,可选), difficulty(难度:1-简单,2-中等,3-困难,可选), page(页码,可选), page_size(每页数量,可选)");
        registerToolSchema("webpage_reader", "网页阅读工具，用于获取网页内容、提取关键信息、生成智能摘要", "action(操作类型: read/extract/summarize/read_multiple/follow_links,默认read), url(网页URL,必填), content(网页内容,可选), query(搜索查询词,可选), maxDepth(最大链接深度,默认2), maxLinks(最大链接数量,默认10)");
        registerToolSchema("read_webpage", "读取网页内容", "url(网页URL,必填)");
        registerToolSchema("smart_research", "智能研究工具，整合搜索和阅读功能", "action(操作类型: research/quick_search/deep_read/summarize_topic,默认research), topic(研究主题,research用), query(搜索关键词,quick_search用), url(网页URL,deep_read用), depth(研究深度,默认1), maxResults(最大结果数,默认5), includeDetails(是否包含详情,默认false)");
        registerToolSchema("linux_shell", "内置 Linux 命令行工具箱（无需 Termux/权限）：busybox、openssl(真TLS)、ssh/scp/sftp/ssh-keygen、curl、aria2c、rg、jq、sqlite3、zstd、zip/unzip、file、tree、ncdu、htop/ps/free、tmux、nano、gawk", "action(exec执行命令/tools列出工具/download下载URL/route改命令路由,默认exec), command(命令,exec用;route时是命令名), url(下载地址,download用), path(保存路径,download用), order(route用:b/s/k/t顺序,如bskt;reset恢复)。单条命令25秒超时，默认不拦截");
        registerToolSchema("app_operation", "应用内部页面跳转工具，支持跳转到用户、题库、答题、学习计划、错题本等各种页面；navigate用params动态注入页面参数（如media_gen AI生图页预填描述）", "action(操作类型: navigate/list_pages/go_home/go_back/get_info/open_settings/share,默认navigate), page(页面名称:user/question/quiz/study_plan/wrong_question/note/ocr/ai/media_gen等,可选), params(动态注入页面参数JSON,navigate用,如{\"mode\":\"image\",\"prompt\":\"一只橘猫\",\"size\":\"1024*1024\"},可选)");
        registerToolSchema("file_reader", "文件阅读工具：读取全文/按行/区间提取/搜索/实体提取/预览/解析结构化文件(Excel/CSV/JSON/XML)/列目录(list)。自动检测编码(UTF-8/GB18030等)，支持content:// URI", "action(read/read_lines/extract_text/search_text/extract_entities/preview/parse_structured/parse_excel/parse_csv/parse_json/parse_xml/list,默认read), file_path(文件路径,支持content://开头URI), file_uri(content://URI), encoding(编码,留空自动检测), startLine(起始行号), endLine(结束行号), startMarker(起始标记), endMarker(结束标记), pattern(搜索关键词), keyword(搜索关键词别名), regex(是否正则,默认false), entity_pattern(自定义实体正则), maxLength(预览长度,默认1000), delimiter(CSV分隔符,默认逗号), max_rows(最大行数,默认500), sheet_index(Excel工作表,默认0), json_path(JSON子路径), target_tag(XML标签), max_items(XML最大条目,默认200), directory_path(目录路径,list用)");
        registerToolSchema("file_analyzer", "分析文件", "file_path(文件路径,必填), analysis_type(分析类型,可选)");
        registerToolSchema("file_generator", "生成文件", "file_name(文件名,必填), content(内容,必填), format(格式,可选)");
        registerToolSchema("permission_manager", "智能权限管理工具，支持权限检查、请求和管理功能", "action(操作类型: check/check_all/request/request_and_wait/get_status/list_permissions/explain_permission/can_request,默认check), permission(权限名称:camera/位置/录音/存储/拨打电话/发送短信等,可选), permissions(权限列表,可选)");
        registerToolSchema("create_dynamic_tool", "动态创建和管理AI工具", "action(操作类型: create/update/delete/list/show/test,默认list), tool_name(工具名称,可选), description(工具描述,可选), parameters(参数定义JSON,可选;支持三种格式:1.简单{\"参数名\":\"描述\"} 2.属性级{\"参数名\":{\"type\":\"string\",\"description\":\"...\",\"required\":true}} 3.完整JSON Schema{\"type\":\"object\",\"properties\":{...},\"required\":[...]};类型支持string/number/integer/boolean/array/object), logic(执行逻辑脚本,可选;自动识别三类:1.Python脚本,脚本内用script_args['参数名']读取参数,print输出/顶层return作为结果;2.JavaScript脚本,脚本内用script_args.参数名或script_args['参数名']读取参数(特殊字符参数名用引号访问),console.log输出/末尾表达式值/顶层return作为结果(顶层return已自动包装兼容);引擎=系统WebView JS引擎,无Java互操作(非Rhino/Nashorn),ES6核心语法+Promise/async-await可用(异步结果受超时限制),支持受限网络window.__http.get(url)/post(url,body)(返回Promise,解析后为{ok,status,body}对象;仅http/https,6s超时,响应≤512KB),支持受限文件window.__fs.read/write/list/delete/exists(返回Promise,解析后为对象;仅限工作区files/内,防穿越,≤512KB),ES能力以实测为准:现代机型实测支持ES2020+(可选链?. / ?? / BigInt / .at()等),老机型WebView可能缺失,跨机型稳妥写法仍建议ES6;并发上限:单批同时执行≤4路(超出等待2s后明确报「JS执行并发已满」,不静默丢值),脚本内多次桥接调用请串行await,勿Promise.all并发打桥;3.DSL命令echo/set/if/call_tool等), test_params(试运行参数JSON,action=test时使用,可选)");
        registerToolSchema("dashscope_media", "百炼DashScope文生图/文生视频（通义万相）", "action(image文生图/video文生视频/query按task_id查询,默认image), prompt(画面/视频描述,必填), model(模型:image=wan2.2-t2i-flash默认/plus,video=wan2.2-t2v-plus默认), size(尺寸:image默认1024*1024;video白名单1080*1920/1920*1080/1440*1440/1632*1248/1248*1632/480*832/832*480/624*624,默认832*480), duration(视频秒数,默认5), task_id(任务ID,query用), api_key(百炼Key,可选默认取当前在线模型配置)");
        registerToolSchema("ui_component", "创建UI组件：系统原生(dialog/progress/input/choice/multi_choice/date/time/snackbar/list/notification/custom动态表单/file_picker文件选择/image_picker选图/contact_picker联系人/rating评分/color取色/otp验证码/number数字/marquee跑马灯/media_task任务监控)或内置卡片(chart/info_card/table_card等)。参数可放顶层或props内(等效,自动合并)。自定义UI可传layout树(顶层/render/props等效)。握手:create→component_id→update/close→get_result取用户操作", "action(create/update/close/get_result,必填), component_type(组件类型,create用), component_id(组件ID), title(标题), message(内容), dialog_type(info/confirm/warning), options(选项列表), default_value(默认值), input_hint(输入提示), items(列表项), url(网址), progress(进度值,update用), max或max_value(进度最大值), layout(自定义控件树), props(内置组件参数;custom的fields;otp的length;number的min/max;marquee的text/speed;media_task的task_id/type), fields(custom动态表单字段定义数组,如[{\"key\":\"name\",\"label\":\"姓名\",\"type\":\"text\",\"required\":true}],字段类型:text/number/password/multiline/select/radio/checkbox/switch/slider/date), wait_seconds(等待秒数)");
        registerToolSchema("tool_registry", "工具注册表(MCP式工具发现)：列出可用工具(list)、按关键词搜索工具(search)、获取单个工具完整参数schema(get)。模型不确定有哪些工具或需要某工具详细参数时调用", "action(list/search/get,必填), keyword(搜索关键词,search用), tool(工具名,get用)");
        registerToolSchema("ocr_recognize", "图片理解工具：OCR文字识别 + 视觉问答（看图理解）。识别图片/PDF文字，或看图回答用户问题", "action(操作类型: ocr_recognize识别图片文字/ocr_recognize_pdf识别PDF文字/image_understand图片理解视觉问答/ocr_set_language设置语言/ocr_get_language获取语言,默认ocr_recognize), image_path(图片路径:绝对路径或content://或file://URI,ocr_recognize/image_understand用), pdf_path(PDF路径:绝对路径或content://或file://URI,ocr_recognize_pdf用), question(关于图片的问题,image_understand用), language(识别语言:auto/chinese/english/japanese/korean,可选)");
        registerToolSchema("screen_watch", "盯梢工具（哨兵）：持续监控手机屏幕，直到指定内容出现/消失或画面发生变化才返回。target=要等待的目标文字(可选,如:下载完成/支付成功/加载中)，watch_for=appear(默认,出现即报)/disappear(消失即报)；不填target则检测画面变化。action=start(默认)开始盯梢/stop停止/status状态；seconds=总时长(默认60,最大600)、interval=轮询间隔(默认3,最小2)。首次使用弹『共享屏幕』授权框需用户点『立即开始』(工具会等待用户操作)。命中或画面变化时自动OCR关键帧并直接返回画面文字内容，无需再调OCR", "action(操作类型: start/stop/status,默认start), target(目标文字,可选,如:下载完成), watch_for(appear出现即报默认/disappear消失即报), seconds(总时长秒,默认60,最大600), interval(轮询间隔秒,默认3,最小2)");
        registerToolSchema("python_execute", "执行Python代码。脚本内置android_ui模块(真实显示在手机界面)：show_toast提示条；系统UI组件API：create_component('dialog'/'progress',...)创建系统对话框/进度条→component_id，update_component更新进度，get_component_result阻塞获取用户点击，close_component关闭。环境预装库(可直接import，无需安装)：requests、beautifulsoup4(bs4)、jieba、lxml、regex、numpy(np)、pandas(pd)、matplotlib(plt)、Pillow(PIL)、openpyxl、yaml、tabulate、python-dateutil、chardet、xlrd、reportlab、python-docx(docx→Word读写)、python-pptx(pptx→PPT读写)、pypdf(PDF读取/合并/拆分)、XlsxWriter(xlsxwriter→Excel写入，pptx图表依赖)；读写 Word/PPT/PDF 直接用 docx/pptx/pypdf，无需再装；绘制图表用matplotlib(先设中文字体)或Pillow", "code(Python代码,可选), task(任务描述,可选), context(上下文数据,可选)");
        registerToolSchema("python_analyze_data", "使用Python分析数据", "data(数据,可选), task(任务描述,可选)");
        registerToolSchema("ai_create_tool", "AI创建工具，使用AI自动生成新工具", "tool_name(工具名称,必填), description(工具描述,必填), parameters(参数定义,可选), logic(执行逻辑,可选)");
        registerToolSchema("chat_history", "对话历史：读取本地保存的聊天历史（跨会话）。新会话里需要回忆之前说过的话、上次创建的工具/组件/文件时使用（如用户说\"之前让你创建过xx\"\"上次那个组件\"\"历史里找\"）。action=recent(最近消息,source=ai|agent,limit条数默认30最大200)/search(关键词搜索,keyword必填)/count(条数)。返回带序号与角色的消息内容，按时间倒序。历史只读", "action(操作类型: recent默认/search/count), source(历史来源: ai(AI对话历史,默认)/agent(Agent对话历史)), limit(最近消息条数,recent用,默认30,最大200), keyword(搜索关键词,search用)");

        registerToolParamSchema("ai_weather",
            new ToolParamSchema("action", "string", "操作类型：current(当前天气), forecast(未来预报), hourly(小时预报), air_quality(空气质量), alerts(天气预警), indices(生活指数), all(全部信息)", false, "current"),
            new ToolParamSchema("city", "string", "城市名称", false, null),
            new ToolParamSchema("lat", "number", "纬度", false, 0),
            new ToolParamSchema("lon", "number", "经度", false, 0));

        registerToolParamSchema("location",
            new ToolParamSchema("action", "string", "操作类型：get_current(获取完整位置), get_city(获取城市), get_coordinates(获取坐标)", false, "get_current"));

        registerToolParamSchema("network_search",
            new ToolParamSchema("action", "string", "操作类型：search(搜索)/ask(智能问答)/read_url(网页读取)/get_webpage(获取网页)/extract_info(提取信息)/summarize(摘要)/search_and_read(搜索并阅读)/smart_search(智能搜索)", false, "search"),
            new ToolParamSchema("query", "string", "搜索关键词（search/smart_search等用）", false, null),
            new ToolParamSchema("question", "string", "智能问答问题（ask用）", false, null),
            new ToolParamSchema("url", "string", "网页URL（read_url/get_webpage用）", false, null),
            new ToolParamSchema("keyword", "string", "搜索关键词（query的别名）", false, null),
            new ToolParamSchema("limit", "int", "结果数量限制", false, 5),
            new ToolParamSchema("num_results", "int", "返回结果数量（limit的别名）", false, 5),
            new ToolParamSchema("model", "string", "问答模式：concise/detail/research（ask用）", false, "concise"));

        registerToolParamSchema("python_calculate",
            new ToolParamSchema("expression", "string", "数学表达式，如：2+3*4", true, null),
            new ToolParamSchema("task", "string", "任务描述（可选）", false, null));

        registerToolParamSchema("smart_research",
            new ToolParamSchema("action", "string", "操作类型：research(完整研究)/quick_search(快速搜索)/deep_read(深度阅读)/summarize_topic(主题摘要)", false, "research"),
            new ToolParamSchema("topic", "string", "研究主题（research/summarize_topic用）", false, null),
            new ToolParamSchema("query", "string", "搜索关键词（quick_search用）", false, null),
            new ToolParamSchema("url", "string", "网页URL（deep_read用）", false, null),
            new ToolParamSchema("depth", "int", "研究深度(默认1)", false, 1),
            new ToolParamSchema("maxResults", "int", "最大结果数(默认5)", false, 5),
            new ToolParamSchema("includeDetails", "boolean", "是否包含详情", false, false));

        registerToolParamSchema("app_operation",
            new ToolParamSchema("action", "string", "操作类型：navigate(导航), list_pages(列出页面), go_home(返回主页), go_back(返回上一页)", false, "navigate"),
            new ToolParamSchema("page", "string", "页面名称(如user/question/quiz/study_plan等)", false, null));

        registerToolParamSchema("permission_manager",
            new ToolParamSchema("action", "string", "操作类型：check(检查), check_all(批量检查), request(请求), request_and_wait(请求并等待), get_status(获取状态), list_permissions(列出权限), explain_permission(解释权限), can_request(是否可请求)", false, "check"),
            new ToolParamSchema("permission", "string", "权限名称（如camera/位置/录音/存储/拨打电话/发送短信等）", false, null),
            new ToolParamSchema("permissions", "array", "权限列表（用于check_all操作）", false, null));
    }

    private void buildDynamicToolSchemas() {
        autoDiscoverToolSchemas();
    }
    
    /**
     * P0: 自动从 AIToolManager 发现所有已注册工具的 schema
     * 优先使用注解信息，没有注解时使用工具接口提供的信息
     * 新工具只需在 AIToolManager 注册，此处会自动同步 schema
     * 优化：通过反射获取注解，不实例化工具，节省内存
     */
    private void autoDiscoverToolSchemas() {
        try {
            List<String> toolNames = toolManager.getRegisteredToolNames();
            for (String name : toolNames) {
                try {
                    Class<? extends com.oilquiz.app.ai.tool.AITool> toolClass = toolManager.getToolClass(name);
                    if (toolClass != null) {
                        com.oilquiz.app.ai.tool.annotation.Tool toolAnnotation = 
                            toolClass.getAnnotation(com.oilquiz.app.ai.tool.annotation.Tool.class);
                        if (toolAnnotation != null) {
                            registerToolSchemasFromAnnotation(name, toolAnnotation);
                            if (toolAnnotation.aliases().length > 0) {
                                for (String alias : toolAnnotation.aliases()) {
                                    if (!toolNameAliases.containsKey(alias)) {
                                        toolNameAliases.put(alias, name);
                                    }
                                }
                            }
                        } else {
                            com.oilquiz.app.ai.tool.AITool tool = toolManager.getToolsMap().get(name);
                            if (tool != null) {
                                registerToolSchemaFromTool(tool);
                            }
                        }
                    }
                } catch (Exception e) {
                    AILogger.w(TAG, "Failed to extract schema for tool: " + name + ", error: " + e.getMessage());
                }
            }
            AILogger.i(TAG, "Auto-discovered " + toolSchemas.size() + " tool schemas from AIToolManager");
        } catch (Exception e) {
            AILogger.e(TAG, "Auto-discovery failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * 从注解注册工具 schema（不实例化工具）
     */
    private void registerToolSchemasFromAnnotation(String toolName, com.oilquiz.app.ai.tool.annotation.Tool annotation) {
        String name = annotation.value().isEmpty() ? toolName : annotation.value();
        String description = annotation.description();
        
        StringBuilder paramDesc = new StringBuilder();
        
        com.oilquiz.app.ai.tool.annotation.Action[] actions = annotation.actions();
        if (actions.length > 0) {
            List<String> actionNames = new ArrayList<>();
            for (com.oilquiz.app.ai.tool.annotation.Action action : actions) {
                actionNames.add(action.name());
            }
            paramDesc.append("action(操作类型,必填: ").append(String.join("/", actionNames)).append(")");
            
            for (com.oilquiz.app.ai.tool.annotation.Action action : actions) {
                com.oilquiz.app.ai.tool.annotation.Param[] actionParams = action.params();
                for (com.oilquiz.app.ai.tool.annotation.Param param : actionParams) {
                    paramDesc.append(", ").append(param.name())
                        .append("(").append(param.description())
                        .append(param.required() ? ",必填" : ",可选").append(")");
                }
            }
        }
        
        com.oilquiz.app.ai.tool.annotation.Param[] params = annotation.params();
        if (params.length > 0) {
            if (paramDesc.length() > 0) paramDesc.append(", ");
            for (com.oilquiz.app.ai.tool.annotation.Param param : params) {
                StringBuilder desc = new StringBuilder(param.description());
                if (param.required()) desc.append(",必填");
                else desc.append(",可选");
                if (param.options().length > 0) {
                    desc.append(": ").append(String.join("/", param.options()));
                }
                if (paramDesc.length() > 0 && !paramDesc.toString().contains(param.name())) {
                    paramDesc.append(", ").append(param.name()).append("(").append(desc).append(")");
                }
            }
        }
        
        registerToolSchema(name, description, paramDesc.toString());
        AILogger.i(TAG, "Auto-registered (annotation): " + name + " -> " + description);
    }
    
    /**
     * 从注解注册工具 schema（保留原方法用于向后兼容）
     */
    private void registerToolSchemasFromAnnotation(com.oilquiz.app.ai.tool.AITool tool, com.oilquiz.app.ai.tool.annotation.Tool annotation) {
        registerToolSchemasFromAnnotation(tool.getName(), annotation);
    }
    
    /**
     * 从工具接口注册 schema（无注解时）
     */
    private void registerToolSchemaFromTool(AITool tool) {
        String name = tool.getName();
        String description = tool.getDescription();
        String paramDesc = ToolSchemaExtractor.formatParamDescriptions(tool.getParameterDescriptions());
        registerToolSchema(name, description, paramDesc);
        AILogger.i(TAG, "Auto-registered (reflection): " + name + " -> " + description);
    }

    public void refreshToolSchemas() {
        toolSchemas.clear();
        paramSchemas.clear();
        buildAliasMap();
        autoDiscoverToolSchemas();
        registerDefaultTools();
        AILogger.i(TAG, "Tool schemas refreshed, total: " + toolSchemas.size());
    }

    private void registerToolSchema(String name, String description, String paramDesc) {
        // 如果已有同名schema，更新（用于手动注册覆盖自动发现）
        for (int i = 0; i < toolSchemas.size(); i++) {
            if (toolSchemas.get(i).name.equals(name)) {
                toolSchemas.set(i, new ToolSchema(name, description, paramDesc));
                return;
            }
        }
        toolSchemas.add(new ToolSchema(name, description, paramDesc));
    }

    private void registerToolParamSchema(String toolName, ToolParamSchema... schemas) {
        for (ToolParamSchema schema : schemas) {
            paramSchemas.put(toolName + "." + schema.paramName, schema);
        }
    }

    private boolean hasToolSchema(String name) {
        for (ToolSchema schema : toolSchemas) {
            if (schema.name.equals(name)) return true;
        }
        return false;
    }

    public String buildToolSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("[工具使用说明]\n\n");
        sb.append("当需要使用工具时，使用原生 function calling 直接输出工具调用（无需任何 JSON 封装或文本标记）。\n\n");
        sb.append("常用工具示例（调用时以原生 function calling 输出，不要构造 JSON 封装；同一需求可用不同工具，由你判断）：\n");
        sb.append("  • 查天气：可用 ai_weather 工具（参数 city=北京，action 按需选 current/forecast/hourly/air_quality/indices；无城市可先 location 定位拿 lat/lon 配合查询），也可用 network_search 搜索\n");
        sb.append("  • 计算：可用 calculator 或 python_calculate（参数 expression=3+5）\n");
        sb.append("  • 搜索：可用 network_search 或 smart_research（参数 query=人工智能）\n\n");
        sb.append("可用工具列表：\n\n");
        Map<String, ToolSchema> uniqueTools = deduplicateTools();
        for (ToolSchema tool : uniqueTools.values()) {
            sb.append("- ").append(tool.name).append(": ").append(tool.description).append("\n");
            sb.append("  ").append(tool.paramDesc).append("\n\n");
        }
        sb.append("重要规则：\n");
        sb.append("1. 需要工具时，直接以原生 function calling 格式输出工具调用\n");
        sb.append("2. 按需调用工具，可多轮/并行调用直到拿到足够信息\n");
        sb.append("3. 不需要工具时，直接回答用户问题\n");
        sb.append("4. 工具返回结果后，基于结果回答用户\n");
        sb.append("5. 参数名必须与工具定义一致\n");
        return sb.toString();
    }

    private Map<String, ToolSchema> deduplicateTools() {
        Map<String, ToolSchema> unique = new LinkedHashMap<>();
        for (ToolSchema schema : toolSchemas) {
            String mappedName = resolveToolName(schema.name);
            if (mappedName != null && !unique.containsKey(mappedName)) {
                unique.put(mappedName, schema);
            }
        }
        return unique;
    }

    public List<ToolCall> parseToolCalls(String output) {
        List<ToolCall> calls = new ArrayList<>();
        if (output == null || output.isEmpty()) return calls;

        AILogger.d(TAG, "Parsing tool calls from: " + output.substring(0, Math.min(200, output.length())));

        // Qwen3.5 XML 参数形态优先探测（tool_call 块内是 function= / parameter= 标签而非 JSON）
        if (output.contains("<function=")) {
            List<ToolCall> xmlCalls = parseQwen35XmlToolCalls(output);
            if (!xmlCalls.isEmpty()) {
                AILogger.d(TAG, "Parsed " + xmlCalls.size() + " Qwen3.5 XML tool calls");
                return xmlCalls;
            }
            AILogger.w(TAG, "Qwen3.5 XML markers found but no valid tool calls parsed, falling back");
        }

        // 优先匹配 Qwen2.5 原生格式（小模型最常自发输出的格式）
        Matcher mq = TOOL_CALL_PATTERN_QWEN_NATIVE.matcher(output);
        while (mq.find()) {
            ToolCall call = resolveCall(mq.group(1), mq.group(2));
            if (call != null) {
                AILogger.d(TAG, "Found QWEN_NATIVE pattern: " + call.name);
                calls.add(call);
            }
        }

        if (calls.isEmpty()) {
            Matcher mg = TOOL_CALL_PATTERN_GENERIC_BLOCK.matcher(output);
            while (mg.find()) {
                ToolCall call = resolveCall(mg.group(1), mg.group(2));
                if (call != null) {
                    AILogger.d(TAG, "Found GENERIC_BLOCK pattern: " + call.name);
                    calls.add(call);
                }
            }
        }

        Matcher m0 = TOOL_CALL_PATTERN_OPENAI.matcher(output);
        while (m0.find()) {
            String toolName = m0.group(1);
            if (isKnownOrAliasedTool(toolName)) {
                AILogger.d(TAG, "Found OPENAI pattern: " + toolName);
                calls.add(new ToolCall(toolName, m0.group(2)));
            }
        }

        if (calls.isEmpty()) {
            Matcher m1 = TOOL_CALL_PATTERN_TOOLS_BLOCK.matcher(output);
            while (m1.find()) {
                String toolName = m1.group(1);
                if (isKnownOrAliasedTool(toolName)) {
                    AILogger.d(TAG, "Found TOOLS_BLOCK pattern: " + toolName);
                    calls.add(new ToolCall(toolName, m1.group(2)));
                }
            }
        }

        if (calls.isEmpty()) {
            Matcher m2 = TOOL_CALL_PATTERN_TOOLS_NO_END.matcher(output);
            while (m2.find()) {
                String toolName = m2.group(1);
                if (isKnownOrAliasedTool(toolName)) {
                    AILogger.d(TAG, "Found TOOLS_NO_END pattern: " + toolName);
                    calls.add(new ToolCall(toolName, m2.group(2)));
                }
            }
        }

        if (calls.isEmpty()) {
            Matcher m3 = TOOL_CALL_PATTERN_STANDARD.matcher(output);
            while (m3.find()) {
                AILogger.d(TAG, "Found standard pattern");
                calls.add(new ToolCall(m3.group(1), m3.group(2)));
            }
        }

        if (calls.isEmpty()) {
            Matcher m4 = TOOL_CALL_PATTERN_NO_END.matcher(output);
            while (m4.find()) {
                AILogger.d(TAG, "Found no-end pattern");
                calls.add(new ToolCall(m4.group(1), m4.group(2)));
            }
        }

        if (calls.isEmpty()) {
            Matcher m5 = TOOL_CALL_PATTERN_MARKDOWN.matcher(output);
            while (m5.find()) {
                AILogger.d(TAG, "Found markdown pattern");
                calls.add(new ToolCall(m5.group(1), m5.group(2)));
            }
        }

        if (calls.isEmpty()) {
            Matcher m6 = TOOL_CALL_PATTERN_JSON.matcher(output);
            while (m6.find()) {
                String toolName = m6.group(1);
                if (isKnownOrAliasedTool(toolName)) {
                    AILogger.d(TAG, "Found JSON pattern: " + toolName);
                    calls.add(new ToolCall(toolName, m6.group(2)));
                }
            }
        }

        if (calls.isEmpty()) {
            Matcher m7 = TOOL_CALL_PATTERN_FUNCTION.matcher(output);
            while (m7.find()) {
                String toolName = m7.group(1);
                if (isKnownOrAliasedTool(toolName)) {
                    AILogger.d(TAG, "Found function pattern: " + toolName);
                    calls.add(new ToolCall(toolName, m7.group(2)));
                }
            }
        }

        if (calls.isEmpty()) {
            Matcher m8 = TOOL_CALL_PATTERN_SIMPLE.matcher(output);
            while (m8.find()) {
                String toolName = m8.group(1);
                if (isKnownOrAliasedTool(toolName)) {
                    AILogger.d(TAG, "Found simple pattern: " + toolName);
                    calls.add(new ToolCall(toolName, m8.group(2)));
                }
            }
        }

        AILogger.d(TAG, "Total tool calls found: " + calls.size());
        return calls;
    }

    /**
     * 解析 Qwen3.5 原生 XML 参数形态工具调用。
     * 官方模板格式（参考 Qwen/Qwen3.5-4B tokenizer_config.json）：
     * tool_call 块内嵌套 function=工具名 与多个 parameter=参数名 值对，
     * 参数值可为纯文本或 JSON（对象/数组/数字/布尔/null）。
     * 与 Qwen3 的 JSON 形态（tool_call 块内 {"name":..., "arguments":{...}}）并存，
     * 由 parseToolCalls 按输出形态自适应分发。
     */
    private List<ToolCall> parseQwen35XmlToolCalls(String output) {
        List<ToolCall> calls = new ArrayList<>();
        Matcher block = QWEN35_TOOL_CALL_BLOCK.matcher(output);
        while (block.find()) {
            ToolCall call = buildQwen35XmlToolCall(block.group(1), block.group(2));
            if (call != null) calls.add(call);
        }
        if (calls.isEmpty()) {
            // 兜底：<tool_call> 包裹缺失或未闭合（Qwen3-Coder 直接输出 <function=，截断丢 </tool_call>）
            Matcher bare = QWEN35_FUNCTION_BARE_BLOCK.matcher(output);
            while (bare.find()) {
                ToolCall call = buildQwen35XmlToolCall(bare.group(1), bare.group(2));
                if (call != null) calls.add(call);
            }
        }
        return calls;
    }

    /**
     * 构建单个 Qwen3.5 XML 工具调用：函数名容错匹配 + 参数提取 + 归一化 JSON arguments。
     */
    private ToolCall buildQwen35XmlToolCall(String rawName, String body) {
        if (rawName == null || rawName.trim().isEmpty()) return null;
        String resolved = resolveToolNameFlexible(rawName.trim());
        if (resolved == null) {
            AILogger.w(TAG, "Unrecognized tool name in Qwen3.5 XML output: " + rawName);
            return null;
        }
        JSONObject args = new JSONObject();
        Matcher pm = QWEN35_PARAM_BLOCK.matcher(body != null ? body : "");
        boolean anyParam = false;
        while (pm.find()) {
            String paramName = pm.group(1).trim();
            String paramValue = pm.group(2).trim();
            if (paramName.isEmpty()) continue;
            Object parsed = tryParseXmlParamValue(paramValue);
            try {
                args.put(paramName, parsed);
            } catch (JSONException ignored) {
                // 参数名非法键，跳过
            }
            anyParam = true;
        }
        if (!anyParam) {
            AILogger.w(TAG, "Qwen3.5 XML tool call has no parameters: " + resolved);
        }
        return new ToolCall(resolved, args.toString());
    }

    /**
     * Qwen3.5 XML 参数值解析：对象/数组按 JSON 解析（含嵌套），
     * 标量走 stringToValue（数字/布尔/null/字符串），失败保留为字符串。
     * 与 tinker-cookbook qwen3_5.py 的 json.loads 语义一致。
     */
    private static Object tryParseXmlParamValue(String raw) {
        String v = raw.trim();
        if (v.isEmpty()) return "";
        if (v.startsWith("{")) {
            try {
                return new JSONObject(v);
            } catch (JSONException ignored) {
                // 非 JSON 对象，按普通文本处理
            }
        } else if (v.startsWith("[")) {
            try {
                return new JSONArray(v);
            } catch (JSONException ignored) {
                // 非 JSON 数组，按普通文本处理
            }
        }
        try {
            return new JSONTokener(v).nextValue();
        } catch (JSONException e) {
            return v;
        }
    }

    /**
     * 解析单个工具调用：先用括号平衡提取 arguments（容错嵌套与截断），
     * 再做工具名容错匹配。解析失败返回 null。
     */
    private ToolCall resolveCall(String rawName, String rawArgs) {
        if (rawName == null || rawName.trim().isEmpty()) return null;
        String resolvedName = resolveToolNameFlexible(rawName.trim());
        if (resolvedName == null) {
            AILogger.w(TAG, "Unrecognized tool name in model output: " + rawName);
            return null;
        }
        if (!resolvedName.equals(rawName.trim())) {
            AILogger.i(TAG, "Fuzzy matched tool name: " + rawName + " -> " + resolvedName);
        }
        String args = extractBalancedJson(rawArgs);
        if (args == null) {
            AILogger.w(TAG, "Failed to extract arguments JSON for tool: " + resolvedName);
            args = "{}";
        }
        return new ToolCall(resolvedName, args);
    }

    /**
     * 工具名容错匹配：精确 → 别名 → 忽略大小写 → 前后缀包含。
     * 小模型（3B/7B）常出现大小写或前缀拼写偏差，直接丢弃会导致工具不触发。
     */
    private String resolveToolNameFlexible(String name) {
        if (name == null || name.isEmpty()) return null;
        // 1. 精确匹配（含原有别名机制）
        if (isKnownOrAliasedTool(name)) {
            String alias = toolNameAliases.get(name);
            return alias != null ? alias : name;
        }
        String lower = name.toLowerCase();
        // 2. 忽略大小写精确匹配
        for (ToolSchema schema : toolSchemas) {
            if (schema.name.toLowerCase().equals(lower)) return schema.name;
        }
        for (Map.Entry<String, String> e : toolNameAliases.entrySet()) {
            if (e.getKey().toLowerCase().equals(lower)) return e.getValue();
        }
        // 3. 前后缀包含匹配（如模型输出 "tool_ai_weather" 或 "ai_weather_tool"）
        for (ToolSchema schema : toolSchemas) {
            String schemaLower = schema.name.toLowerCase();
            if (lower.contains(schemaLower) || schemaLower.contains(lower)) return schema.name;
        }
        return null;
    }

    /**
     * 从文本中提取第一个括号平衡的 JSON 对象。
     * 比固定深度正则更稳健：支持任意嵌套，且对截断输出可回退到最后一个 '}'。
     */
    static String extractBalancedJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        int lastClose = -1;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                lastClose = i;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        // 不平衡（可能被 max_tokens 截断）：取到最后一个右括号
        if (lastClose > start) return text.substring(start, lastClose + 1);
        return null;
    }

    private boolean isKnownOrAliasedTool(String name) {
        if (isKnownTool(name)) return true;
        String resolved = toolNameAliases.get(name);
        return resolved != null && toolManager.hasTool(resolved);
    }

    private boolean isKnownTool(String name) {
        for (ToolSchema schema : toolSchemas) {
            if (schema.name.equals(name)) return true;
        }
        return toolNameAliases.containsKey(name);
    }

    public ToolResult executeTool(String toolName, Map<String, Object> params) {
        String arguments = params != null ? new Gson().toJson(params) : "{}";
        return executeTool(new ToolCall(toolName, arguments));
    }

    public ToolResult executeTool(ToolCall call) {
        AILogger.i(TAG, "Executing tool: " + call.name + " with args: " + call.arguments);
        long startTime = System.currentTimeMillis();

        // ui_component 不缓存：create 必须重新执行（注册新组件/新 id），
        // get_result 超时后的 "pending" 若被缓存，二次调用瞬间返回 pending 永远取不回点击（Bug2/3）
        boolean noCacheTool = "ui_component".equals(call.name);
        String cacheKey = call.name + ":" + call.arguments;
        String cached = noCacheTool ? null : getCachedResult(cacheKey);
        if (cached != null) {
            AILogger.d(TAG, "Tool cache HIT: " + call.name);
            return new ToolResult(call.name, cached, true, 0, 0);
        }

        Map<String, Object> params = parseArguments(call.arguments);
        call.resolvedArgs = params;

        String realToolName = resolveToolName(call.name);
        if (realToolName == null) {
            AILogger.w(TAG, "Unknown tool: " + call.name + ", attempting direct execution");
            realToolName = call.name;
        }

        // P2: 检查并执行工具依赖
        DependencyCheckResult depResult = dependencyChecker.checkDependencies(realToolName, params);
        if (!depResult.requiredPreCalls.isEmpty()) {
            AILogger.i(TAG, "Tool " + realToolName + " has " + depResult.requiredPreCalls.size() + " dependencies, executing...");
            boolean depsOk = dependencyChecker.executePreCalls(depResult.requiredPreCalls);
            if (!depsOk && !depResult.unresolvableDeps.isEmpty()) {
                return new ToolResult(call.name, "工具依赖检查失败: " + String.join(", ", depResult.unresolvableDeps), false, 0, 0);
            }
        }

        AILogger.i(TAG, "Resolved tool: " + call.name + " -> " + realToolName);

        ValidationResult validation = validateParams(realToolName, params);
        if (!validation.valid) {
            String errorMsg = "参数验证失败: " + String.join(", ", validation.errors);
            AILogger.e(TAG, errorMsg);
            return new ToolResult(call.name, errorMsg, false, 0, 0);
        }
        if (!validation.warnings.isEmpty()) {
            AILogger.w(TAG, "Params warnings: " + String.join(", ", validation.warnings));
        }

        Map<String, Object> transformedParams = transformToolParams(call.name, realToolName, params);

        ToolResult result = executeWithRetry(realToolName, call.name, transformedParams, MAX_RETRY_COUNT);

        long elapsed = System.currentTimeMillis() - startTime;
        AILogger.i(TAG, "Tool " + call.name + " completed in " + elapsed + "ms, success=" + result.success);

        if (result.success && !noCacheTool) {
            cacheResult(cacheKey, result.result);
        }

        return new ToolResult(result.toolName, result.result, result.success, elapsed, result.retryCount);
    }

    private ToolResult executeWithRetry(String realToolName, String displayName, Map<String, Object> params, int maxRetries) {
        // 保护：toolManager可能为null
        if (toolManager == null) {
            AILogger.e(TAG, "toolManager is null, cannot execute tool: " + realToolName);
            return new ToolResult(displayName, "工具管理器未初始化: " + realToolName, false, 0, 0);
        }
        
        Exception lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            final int currentAttempt = attempt;
            try {
                CompletableFuture<ToolResult> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        AIToolResult toolResult = toolManager.executeTool(realToolName, params);
                        // 保护：toolResult可能为null
                        if (toolResult == null) {
                            AILogger.e(TAG, "toolManager.executeTool returned null for: " + realToolName);
                            return new ToolResult(displayName, "工具执行返回null: " + realToolName, false, 0, currentAttempt);
                        }
                        
                        if (toolResult.isSuccess()) {
                            return new ToolResult(displayName, formatToolOutput(toolResult.getResult()), true, 0, currentAttempt);
                        } else {
                            String errorMsg = toolResult.getErrorMessage() != null ? toolResult.getErrorMessage() : "未知错误";
                            return new ToolResult(displayName, "工具执行失败: " + errorMsg, false, 0, currentAttempt);
                        }
                    } catch (Exception e) {
                        AILogger.e(TAG, "Exception in tool execution: " + realToolName, e);
                        throw new RuntimeException(e);
                    }
                });

                // get_result 是阻塞等待用户点击组件（交互可能持续较久），用 120s 长超时，
                // 避免 30s 默认超时中断等待导致 Agent"越过交互"直接继续（Bug2）
                boolean isInteractionWait = "ui_component".equals(realToolName)
                        && "get_result".equals(String.valueOf(params.get("action")));
                long effectiveTimeout = isInteractionWait ? 120000L : TOOL_TIMEOUT_MS;
                return future.get(effectiveTimeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                lastException = e;
                AILogger.w(TAG, "Tool " + realToolName + " timeout on attempt " + (attempt + 1));
                if (attempt < maxRetries) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return new ToolResult(displayName, "工具执行被中断", false, 0, attempt);
                    }
                }
            } catch (ExecutionException e) {
                lastException = e;
                AILogger.w(TAG, "Tool " + realToolName + " execution error on attempt " + (attempt + 1) + ": " + e.getMessage());
                if (attempt < maxRetries) {
                    try { Thread.sleep(300); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return new ToolResult(displayName, "工具执行被中断", false, 0, attempt);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ToolResult(displayName, "工具执行被中断", false, 0, attempt);
            } catch (Exception e) {
                lastException = e;
                break;
            }
        }
        String errorMsg = lastException != null ? lastException.getMessage() : "未知错误";
        return new ToolResult(displayName, "工具执行出错(重试" + maxRetries + "次后): " + errorMsg, false, 0, maxRetries);
    }

    // ========== 工具结果智能摘要 ==========

    private static final int TOOL_RESULT_SUMMARY_THRESHOLD = 1500; // 超过此长度触发摘要

    /**
     * 对长工具结果进行智能摘要
     * 当结果超过阈值时，提取关键信息进行压缩
     */
    public String summarizeToolResult(String toolName, String result) {
        if (result == null || result.length() <= TOOL_RESULT_SUMMARY_THRESHOLD) {
            return result; // 短结果直接返回
        }

        // 天气等结构化结果特殊处理
        if (toolName != null && toolName.contains("weather")) {
            return formatWeatherResult(result);
        }

        // 搜索结果特殊处理 - 提取标题和摘要
        if (toolName != null && (toolName.contains("search") || toolName.contains("web"))) {
            return formatSearchResult(result);
        }

        // 数据库结果特殊处理 - 保留表头和前几行
        if (toolName != null && toolName.contains("database")) {
            return formatDatabaseResult(result);
        }

        // 默认：智能截断 - 保留开头和结尾
        return smartTruncate(result, TOOL_RESULT_MAX_LENGTH);
    }

    /**
     * 搜索结果格式化 - 提取标题和摘要
     */
    private String formatSearchResult(String result) {
        StringBuilder sb = new StringBuilder();
        String[] lines = result.split("\n");
        int totalLen = 0;
        int lineCount = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // 保留标题行（通常以数字、#、**开头）
            if (trimmed.startsWith("#") || trimmed.startsWith("**") || trimmed.matches("^\\d+\\..*")
                || trimmed.startsWith("标题") || trimmed.startsWith("Title")) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(trimmed);
                totalLen += trimmed.length();
                lineCount++;
            }
            // 保留内容摘要（非空行）
            else if (!trimmed.startsWith("http") && !trimmed.startsWith("[") && trimmed.length() > 20) {
                if (sb.length() > 0) sb.append("\n");
                String truncated = trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
                sb.append(truncated);
                totalLen += truncated.length();
                lineCount++;
            }

            if (totalLen > 1000 || lineCount > 10) break;
        }

        if (sb.length() == 0) {
            return smartTruncate(result, TOOL_RESULT_MAX_LENGTH);
        }
        return sb.toString();
    }

    /**
     * 数据库结果格式化 - 保留表头和前几行
     */
    private String formatDatabaseResult(String result) {
        // 不再截断数据库结果，全量返回
        return result;
    }

    /**
     * 天气结果特殊处理 - 直接提取关键信息而非摘要
     */
    public String formatWeatherResult(String result) {
        try {
            // 尝试解析JSON格式的天气结果
            if (result.contains("{") && result.contains("}")) {
                org.json.JSONObject json = new org.json.JSONObject(result);
                String city = json.optString("city", json.optString("location", "未知"));
                int temp = json.optInt("temp", json.optInt("temperature", 0));
                String desc = json.optString("description", json.optString("text", ""));
                int humidity = json.optInt("humidity", 0);
                if (!desc.isEmpty() || temp != 0) {
                    return String.format("%s天气: %s, 温度%d°C, 湿度%d%%", city, desc, temp, humidity);
                }
            }
        } catch (Exception e) {
            // JSON解析失败
        }

        // 非JSON格式，全量返回
        return result;
    }

    /**
     * 智能截断 - 不再截断，全量返回
     */
    private String smartTruncate(String result, int maxLength) {
        return result;
    }
    
    // 不再截断工具返回结果，保证数据完整性
    private static final int TOOL_RESULT_MAX_LENGTH = Integer.MAX_VALUE;
    private static final int TOOL_CONTENT_PREVIEW_LENGTH = 400;
    private static final int WEATHER_CONTENT_PREVIEW_LENGTH = 1500;

    private String formatToolOutput(Object result) {
        if (result == null) return "工具返回空结果";
        
        if (result instanceof String) {
            String strResult = (String) result;
            int previewLength = TOOL_CONTENT_PREVIEW_LENGTH;
            if (strResult.contains("weather") || strResult.contains("forecast") || strResult.contains("air_quality") || 
                strResult.contains("alerts") || strResult.contains("indices") || strResult.contains("hourly")) {
                previewLength = WEATHER_CONTENT_PREVIEW_LENGTH;
            }
            if (strResult.length() > previewLength) {
                return strResult.substring(0, previewLength) + "\n[内容过长，已截断，共 " + strResult.length() + " 字符]";
            }
            return strResult;
        }

        if (result instanceof Map) {
            Map<?, ?> resultMap = (Map<?, ?>) result;
            StringBuilder sb = new StringBuilder();

            Object statusObj = resultMap.get("status");
            boolean isSuccess = true;
            if (statusObj != null) {
                if ("success".equals(statusObj.toString())) {
                    isSuccess = true;
                } else if ("failed".equals(statusObj.toString()) || "error".equals(statusObj.toString())) {
                    isSuccess = false;
                } else if (statusObj instanceof Boolean) {
                    isSuccess = (Boolean) statusObj;
                }
            }

            if (!isSuccess) {
                sb.append("[工具执行失败]\n");
                if (resultMap.containsKey("error")) {
                    sb.append("错误信息: ").append(resultMap.get("error")).append("\n");
                }
                if (resultMap.containsKey("error_message")) {
                    sb.append("详细错误: ").append(resultMap.get("error_message")).append("\n");
                }
                if (resultMap.containsKey("message")) {
                    sb.append("信息: ").append(resultMap.get("message")).append("\n");
                }
                return sb.toString();
            }

            sb.append("[工具执行成功]\n\n");

            for (Map.Entry<?, ?> entry : resultMap.entrySet()) {
                Object key = entry.getKey();
                Object value = entry.getValue();
                
                if ("status".equals(key)) continue;
                
                sb.append(key).append(": ");
                
                if (value == null) {
                    sb.append("null\n");
                } else if (value instanceof String) {
                    String strValue = (String) value;
                    if (strValue.length() > TOOL_CONTENT_PREVIEW_LENGTH) {
                        sb.append(strValue.substring(0, TOOL_CONTENT_PREVIEW_LENGTH));
                        sb.append("\n[内容过长，已截断，共 ").append(strValue.length()).append(" 字符]\n");
                    } else {
                        sb.append(strValue).append("\n");
                    }
                } else if (value instanceof List || value instanceof Map) {
                    sb.append("\n").append(formatNestedData(value, "  ")).append("\n");
                } else {
                    sb.append(value.toString()).append("\n");
                }
            }

            return sb.toString();
        }

        if (result instanceof List) {
            List<?> list = (List<?>) result;
            StringBuilder sb = new StringBuilder();
            sb.append("[工具执行成功]\n\n");
            for (int i = 0; i < Math.min(list.size(), 10); i++) {
                sb.append(i + 1).append(". ");
                Object item = list.get(i);
                if (item instanceof Map || item instanceof List) {
                    sb.append("\n").append(formatNestedData(item, "  "));
                } else {
                    sb.append(item.toString());
                }
                sb.append("\n");
            }
            if (list.size() > 10) {
                sb.append("... 还有 ").append(list.size() - 10).append(" 条结果\n");
            }
            return sb.toString();
        }

        return result.toString();
    }
    
    private String formatNestedData(Object data, String indent) {
        StringBuilder sb = new StringBuilder();
        if (data == null) return "null";
        
        if (data instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) data;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof Map || value instanceof List) {
                    sb.append(indent).append(key).append(":\n");
                    sb.append(formatNestedData(value, indent + "  "));
                } else {
                    sb.append(indent).append(key).append(": ").append(value).append("\n");
                }
            }
        } else if (data instanceof List) {
            List<?> list = (List<?>) data;
            for (int i = 0; i < Math.min(list.size(), 10); i++) {
                Object item = list.get(i);
                sb.append(indent).append("[").append(i).append("]: ");
                if (item instanceof Map || item instanceof List) {
                    sb.append("\n").append(formatNestedData(item, indent + "  "));
                } else {
                    sb.append(item).append("\n");
                }
            }
            if (list.size() > 10) {
                sb.append(indent).append("... 还有 ").append(list.size() - 10).append(" 项\n");
            }
        } else {
            sb.append(data.toString());
        }
        return sb.toString();
    }

    public String resolveToolName(String alias) {
        String resolved = toolNameAliases.get(alias);
        if (resolved != null && toolManager.hasTool(resolved)) {
            return resolved;
        }
        if (toolManager.hasTool(alias)) {
            return alias;
        }
        return resolved;
    }

    private Map<String, Object> transformToolParams(String originalName, String realName, Map<String, Object> params) {
        Map<String, Object> transformed = new HashMap<>(params);
        
        return transformed;
    }

    public Map<String, Object> parseArguments(String argsJson) {
        Map<String, Object> params = new HashMap<>();
        if (argsJson == null || argsJson.trim().isEmpty()) return params;

        try {
            JSONObject obj = new JSONObject(argsJson.trim());
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = obj.get(key);
                params.put(key, value);
            }
        } catch (JSONException e) {
            AILogger.w(TAG, "Failed to parse JSON arguments, trying key:value format: " + e.getMessage());
            String cleaned = argsJson.replaceAll("[{}\"]", "").trim();
            String[] pairs = cleaned.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    params.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
        return params;
    }

    public String extractStringParam(String argsJson, String key) {
        try {
            JSONObject obj = new JSONObject(argsJson);
            return obj.optString(key, null);
        } catch (Exception e) {
            return null;
        }
    }

    public ValidationResult validateParams(String toolName, Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<String, ToolParamSchema> entry : paramSchemas.entrySet()) {
            if (entry.getKey().startsWith(toolName + ".")) {
                ToolParamSchema schema = entry.getValue();
                Object value = params.get(schema.paramName);

                if (schema.required && (value == null || value.toString().trim().isEmpty())) {
                    if (schema.defaultValue != null) {
                        params.put(schema.paramName, schema.defaultValue);
                        warnings.add("参数 " + schema.paramName + " 使用默认值: " + schema.defaultValue);
                    } else {
                        errors.add("缺少必填参数: " + schema.paramName + " (" + schema.description + ")");
                    }
                }
            }
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    public static class ValidationResult {
        public final boolean valid;
        public final List<String> errors;
        public final List<String> warnings;

        public ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = errors;
            this.warnings = warnings;
        }
    }

    public String formatToolResultForContext(ToolResult result) {
        // 保护：result可能为null
        if (result == null) {
            return "<tool_result>\n工具: 未知\n状态: 失败\n结果: 工具执行返回null\n</tool_result>\n" +
                "请基于以上工具返回的结果回答用户的问题。如果工具执行失败，请告知用户并建议其他方式。";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("<tool_result>\n");
        sb.append("工具: ").append(result.toolName != null ? result.toolName : "未知").append("\n");
        sb.append("状态: ").append(result.success ? "成功" : "失败").append("\n");
        if (result.executionTimeMs > 0) {
            sb.append("耗时: ").append(result.executionTimeMs).append("ms\n");
        }
        String truncatedResult = result.result != null ? result.result : "无结果";
        sb.append("结果: ").append(truncatedResult).append("\n");
        sb.append("</tool_result>\n");
        sb.append("请基于以上工具返回的结果回答用户的问题。如果工具执行失败，请告知用户并建议其他方式。");
        return sb.toString();
    }

    public boolean shouldUseAgent(String userMessage) {
        if (userMessage == null || userMessage.isEmpty()) return false;
        try {
            String lower = userMessage.toLowerCase();

            String[][] intentPatterns = {
                {"天气", "气温", "下雨", "下雪", "温度多少", "天气预报"},
                {"搜索", "查找资料", "检索", "网上查", "帮我搜索", "搜索一下"},
                {"计算", "算一下", "等于多少", "加起来", "乘以"},
                {"数据库", "题库", "错题", "学习统计", "做题记录"},
                {"生成题目", "出题", "练习题"},
                {"搜索题目", "找题", "查找题目"},
                {"打开", "启动应用", "打开应用", "启动"},
                {"文件", "读取文件", "分析文件", "生成文件"},
                {"网页", "打开网页", "读取链接"},
            };

            String[][] excludePatterns = {
                {"冷吗", "热吗", "好冷", "好热", "太冷", "太热", "冷死", "热死"},
                {"查一下", "看一下", "想一下", "觉得"}
            };

            for (String[] patterns : excludePatterns) {
                for (String pattern : patterns) {
                    if (lower.contains(pattern)) {
                        return false;
                    }
                }
            }

            for (String[] patterns : intentPatterns) {
                for (String pattern : patterns) {
                    if (lower.contains(pattern)) return true;
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error checking if agent should be used: " + e.getMessage());
        }
        return false;
    }

    public String getIntentType(String userMessage) {
        if (userMessage == null || userMessage.isEmpty()) return "chat";
        try {
            String lower = userMessage.toLowerCase();
            if (lower.contains("天气") || lower.contains("气温") || lower.contains("下雨") || lower.contains("温度"))
                return "weather";
            if (lower.contains("我在哪") || lower.contains("我的位置") || lower.contains("我在什么地方") || 
                lower.contains("定位") || lower.contains("我的城市") || lower.contains("gps") || 
                lower.contains("我所在") || (lower.contains("我在") && lower.length() < 10))
                return "location";
            if (lower.contains("搜索") || lower.contains("查找") || lower.contains("检索"))
                return "search";
            if (lower.contains("计算") || lower.contains("算一下") || lower.matches(".*[\\d+\\-*/=().\\s]+.*"))
                return "calculate";
            if (lower.contains("数据库") || lower.contains("题库") || lower.contains("错题"))
                return "database";
            if (lower.contains("生成题目") || lower.contains("出题"))
                return "generate_questions";
            if (lower.contains("搜索题目") || lower.contains("找题"))
                return "search_questions";
            if (lower.contains("打开") || lower.contains("启动"))
                return "system";
            if (lower.contains("网页") || lower.contains("链接") || lower.contains("http"))
                return "web";
            if (lower.contains("文件") || lower.contains("读取") || lower.contains("生成文件"))
                return "file_read";
        } catch (Exception e) {
            AILogger.e(TAG, "Error determining intent type: " + e.getMessage());
        }
        return "chat";
    }

    public ToolSelectionStrategy determineStrategy(String userMessage) {
        if (userMessage == null || userMessage.isEmpty()) {
            return ToolSelectionStrategy.STANDARD;
        }

        try {
            int complexityScore = 0;
            String lower = userMessage.toLowerCase();

            if (userMessage.length() > 100) {
                complexityScore += 2;
            } else if (userMessage.length() <= 30) {
                complexityScore -= 1;
            }

            String[] complexIndicators = {"如何", "怎么", "为什么", "分析", "总结", "详细", "步骤", "方法"};
            for (String indicator : complexIndicators) {
                if (lower.contains(indicator)) {
                    complexityScore++;
                }
            }

            String[] intentPatterns = {"搜索", "计算", "翻译", "天气", "数据库", "生成", "分析", "打开", "网页", "文件"};
            int intentCount = 0;
            for (String pattern : intentPatterns) {
                if (lower.contains(pattern)) {
                    intentCount++;
                }
            }
            if (intentCount >= 2) {
                complexityScore += 2;
            }

            AILogger.i(TAG, "Complexity score: " + complexityScore);

            if (complexityScore >= 3) {
                return ToolSelectionStrategy.FULL;
            } else if (complexityScore <= 0) {
                return ToolSelectionStrategy.MINIMAL;
            } else {
                return ToolSelectionStrategy.STANDARD;
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error determining strategy: " + e.getMessage());
            return ToolSelectionStrategy.STANDARD;
        }
    }

    public List<ToolSchema> selectToolsByIntent(String userMessage) {
        try {
            ToolSelectionStrategy strategy = determineStrategy(userMessage);
            this.currentStrategy = strategy;
            List<ToolSchema> selectedTools = new ArrayList<>();
            Map<String, ToolSchema> unique = deduplicateTools();

            switch (strategy) {
                case MINIMAL:
                    for (ToolSchema tool : unique.values()) {
                        if ("calculate".equals(tool.name) || "network_search".equals(tool.name)) {
                            selectedTools.add(tool);
                        }
                    }
                    break;
                case FULL:
                    selectedTools.addAll(unique.values());
                    break;
                case STANDARD:
                default:
                    for (ToolSchema tool : unique.values()) {
                        if ("get_weather".equals(tool.name) || "network_search".equals(tool.name) ||
                            "calculate".equals(tool.name) ||
                            "database".equals(tool.name) || "web_page_reader".equals(tool.name) ||
                            "smart_research".equals(tool.name) || "system_resource".equals(tool.name) ||
                            "file_reader".equals(tool.name) || "file_analyzer".equals(tool.name) ||
                            "file_generator".equals(tool.name)) {
                            selectedTools.add(tool);
                        }
                    }
                    break;
            }

            String intentType = getIntentType(userMessage);
            selectedTools = prioritizeToolsByIntent(selectedTools, intentType);

            AILogger.i(TAG, "Selected " + selectedTools.size() + " tools for intent: " + intentType);
            return selectedTools;
        } catch (Exception e) {
            AILogger.e(TAG, "Error selecting tools: " + e.getMessage());
            return new ArrayList<>(deduplicateTools().values());
        }
    }

    private List<ToolSchema> prioritizeToolsByIntent(List<ToolSchema> tools, String intentType) {
        if (tools == null || tools.isEmpty()) return new ArrayList<>();

        List<ToolSchema> prioritized = new ArrayList<>();
        List<ToolSchema> remaining = new ArrayList<>(tools);

        String primaryToolName = getPrimaryToolForIntent(intentType);
        if (primaryToolName != null) {
            for (int i = 0; i < remaining.size(); i++) {
                ToolSchema tool = remaining.get(i);
                if (tool != null && tool.name != null && tool.name.equals(primaryToolName)) {
                    prioritized.add(remaining.remove(i));
                    break;
                }
            }
        }

        prioritized.addAll(remaining);
        return prioritized;
    }

    private String getPrimaryToolForIntent(String intentType) {
        switch (intentType) {
            case "weather": return "ai_weather";
            case "location": return "location";
            case "search": return "network_search";
            case "calculate": return "python_calculate";
            case "database": return "database";
            case "generate_questions": return "database";
            case "search_questions": return "database";
            case "system": return "system_resource";
            case "web": return "webpage_reader";
            case "file": case "file_read": return "file_reader";
            default: return null;
        }
    }

    public String buildToolSystemPromptForStrategy(String userMessage) {
        try {
            List<ToolSchema> selectedTools = selectToolsByIntent(userMessage);
            StringBuilder sb = new StringBuilder();
            sb.append("你可以使用以下工具来帮助回答问题。当需要使用工具时，请以原生 function calling 直接输出工具调用（无需 JSON 封装或文本标记）。\n");
            sb.append("可用工具列表（按优先级排序）：\n\n");
            for (ToolSchema tool : selectedTools) {
                if (tool != null && tool.name != null && tool.description != null) {
                    sb.append("- ").append(tool.name).append(": ").append(tool.description).append("\n");
                    if (tool.paramDesc != null) {
                        sb.append("  ").append(tool.paramDesc).append("\n\n");
                    }
                }
            }
            sb.append("重要规则：\n");
            sb.append("1. 当需要查询实时信息（如天气）时，必须使用工具\n");
            sb.append("2. 工具调用后，你会收到工具返回的结果，基于结果回答用户\n");
            sb.append("3. 如果不需要工具，直接回答即可\n");
            sb.append("4. 每次只调用一个工具\n");
            return sb.toString();
        } catch (Exception e) {
            AILogger.e(TAG, "Error building tool system prompt: " + e.getMessage());
            return buildToolSystemPrompt();
        }
    }

    public ToolSelectionStrategy getCurrentStrategy() {
        return currentStrategy;
    }

    private void cacheResult(String key, String result) {
        toolResultCache.put(key, result);
        toolTimestamps.put(key, System.currentTimeMillis());
    }

    private String getCachedResult(String key) {
        Long timestamp = toolTimestamps.get(key);
        if (timestamp == null) return null;
        if (System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS) {
            toolResultCache.remove(key);
            toolTimestamps.remove(key);
            return null;
        }
        return toolResultCache.get(key);
    }

    public void clearCache() {
        toolResultCache.clear();
        toolTimestamps.clear();
    }

    public List<ToolSchema> getToolSchemas() { return toolSchemas; }

    /**
     * 只返回在 AIToolManager 注册的主工具 schema（不含别名），用于节约提示词 tokens。
     * 别名仍通过 toolNameAliases 被 isKnownOrAliasedTool 识别。
     */
    public List<ToolSchema> getMainToolSchemas() {
        if (toolManager == null) return toolSchemas;
        java.util.Set<String> registered = new java.util.HashSet<>(toolManager.getRegisteredToolNames());
        List<ToolSchema> main = new ArrayList<>();
        for (ToolSchema schema : toolSchemas) {
            if (registered.contains(schema.name)) {
                main.add(schema);
            }
        }
        return main;
    }
    public int getMaxToolLoops() { return MAX_TOOL_LOOPS; }
    public Map<String, String> getToolNameAliases() { return new HashMap<>(toolNameAliases); }
    public boolean isToolAvailable(String toolName) {
        if (isKnownTool(toolName)) return true;
        String resolved = toolNameAliases.get(toolName);
        return resolved != null && toolManager.hasTool(resolved);
    }
    public ToolDependencyChecker getDependencyChecker() { return dependencyChecker; }

    /**
     * 全部工具的 OpenAI function calling 格式定义（JSON 字符串）。
     * 供本地 Agent 的 chatJson + tools 路径使用：C++ 层按模型模板注入工具定义
     * （Qwen3.5 模板自动渲染 <tools> + XML 示例），模型按原生格式输出，
     * common_chat_parse 负责解析——Java 侧无需再拼 system prompt 工具描述和正则解析。
     */
    public String getAgentOpenAIToolDefinitions() {
        if (toolManager == null) return "[]";
        return toolManager.getOpenAIToolDefinitions();
    }
}