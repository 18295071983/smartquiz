package com.oilquiz.app.ai.agent.software.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 程序化意图引擎：本地 Agent 的确定性编排层（对模型依赖最小化）。
 *
 * 覆盖全部常用注册工具：意图 = 工具链 + 槽位定义 + 追问 + 默认值。
 * 程序负责"做什么/用什么工具/缺什么"，模型只负责把结果表达成自然语言；
 * 程序不确定时自行调用工具补齐（定位、列文件等），补不齐才追问用户。
 */
public class IntentEngine {

    /** 编排步骤：工具 + action + 参数映射（工具参数名 → "$槽位名" 或 字面值）+ 是否可失败跳过 */
    public static class FlowStep {
        public final String tool;
        public final String action;                        // 工具 action 参数（可空）
        public final Map<String, String> params;           // 工具参数名 → "$槽位名" 或 字面值
        public final boolean optional;                     // true: 失败不阻断流程

        public FlowStep(String tool, String action, Map<String, String> params, boolean optional) {
            this.tool = tool;
            this.action = action;
            this.params = params;
            this.optional = optional;
        }
    }

    /** 意图定义 */
    public static class Intent {
        public final String name;
        public final String description;            // 工具/意图描述
        public final List<String> toolChain;        // 单工具意图：顺序执行的工具链（兼容）
        public final List<FlowStep> flow;           // 组合意图：预置执行方向（多工具/多 action）
        public final List<String> requiredSlots;    // 必填槽位（缺失→追问）
        public final List<String> optionalSlots;    // 可选槽位
        public final Map<String, String> slotQuestions; // 槽位→追问文案
        public final Map<String, String> defaultSlots;  // 槽位→程序默认值

        Intent(String name, String description, List<String> toolChain, List<String> requiredSlots,
               List<String> optionalSlots, Map<String, String> slotQuestions,
               Map<String, String> defaultSlots) {
            this(name, description, toolChain, null, requiredSlots, optionalSlots, slotQuestions, defaultSlots);
        }

        Intent(String name, String description, List<String> toolChain, List<FlowStep> flow,
               List<String> requiredSlots, List<String> optionalSlots,
               Map<String, String> slotQuestions, Map<String, String> defaultSlots) {
            this.name = name;
            this.description = description;
            this.toolChain = toolChain;
            this.flow = flow;
            this.requiredSlots = requiredSlots;
            this.optionalSlots = optionalSlots;
            this.slotQuestions = slotQuestions;
            this.defaultSlots = defaultSlots;
        }
    }

    private static Map<String, String> q(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, String> d(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ========== 意图定义表（覆盖全部常用注册工具） ==========

    private static final List<Intent> INTENTS = Arrays.asList(
        // ---- 信息查询类 ----
        new Intent("weather", "天气查询：查任意城市当前天气/预报（缺城市自动定位）",
                Arrays.asList("ai_weather"), Arrays.asList(), Arrays.asList("city"),
                q("city", "请告诉我城市名称，如 北京/上海"), d()),
        new Intent("location", "位置查询：获取当前位置/城市/坐标",
                Arrays.asList("location"), Arrays.asList(), Arrays.asList(),
                d(), d()),
        new Intent("search", "网络搜索：搜索→自动读首个结果页（预置组合）",
                Arrays.asList("network_search"),
                Arrays.asList(
                        new FlowStep("network_search", "search", d("query", "$query"), false),
                        new FlowStep("webpage_reader", "read_url", d("url", "$__first_url"), true)
                ),
                Arrays.asList("query"), Arrays.asList(),
                q("query", "您想搜索什么内容？"), d()),
        new Intent("webpage", "网页读取：读取指定链接的网页内容",
                Arrays.asList("webpage_reader"), Arrays.asList("url"), Arrays.asList(),
                q("url", "请提供要读取的网页链接（http/https）"), d()),
        new Intent("research", "深度调研：搜集资料→自动生成调研报告（预置组合）",
                Arrays.asList("smart_research"),
                Arrays.asList(
                        new FlowStep("smart_research", "research", d("topic", "$topic"), false),
                        new FlowStep("file_generator", "generate", d("content", "$__report"), true)
                ),
                Arrays.asList("topic"), Arrays.asList(),
                q("topic", "您想调研什么主题？"), d()),
        new Intent("quiz", "题库查询：从答题宝题库检索题目/知识点",
                Arrays.asList("database"), Arrays.asList(), Arrays.asList("query"),
                q("query", "想查什么题目或知识点？"), d()),
        new Intent("system_resource", "系统资源：查看内存/CPU/电量/存储",
                Arrays.asList("system_resource"), Arrays.asList(), Arrays.asList(),
                d(), d()),
        new Intent("time", "时间日期：当前日期/时间/星期",
                Arrays.asList("dynamic_clock"), Arrays.asList(), Arrays.asList("format"),
                d(), d()),

        // ---- 计算与分析类 ----
        new Intent("calculate", "数学计算：表达式求值",
                Arrays.asList("calculator"), Arrays.asList("expression"), Arrays.asList(),
                q("expression", "请告诉我要计算的内容，例如：3.5 * 2 + 1"), d()),
        new Intent("unit_converter", "单位换算：长度/重量/温度/面积/体积/速度单位转换",
                Arrays.asList("unit_converter"), Arrays.asList(), Arrays.asList("value", "from", "to"),
                q("from", "请告诉我要换算的内容，如：100公里等于多少英里"), d()),
        new Intent("analyze_data", "数据分析：对数据做统计/分析（action 程序化）",
                Arrays.asList("python_analyze_data"),
                Arrays.asList(
                        new FlowStep("python_analyze_data", "analyze", d("data", "$data"), false)
                ),
                Arrays.asList(), Arrays.asList("data"),
                q("data", "请提供要分析的数据或文件"), d()),
        new Intent("chart", "数据可视化：用 Python 生成柱状/折线/饼图/散点图",
                Arrays.asList("python_chart"), Arrays.asList(), Arrays.asList("data", "title"),
                q("data", "请提供图表数据（如：[3,5,2,8] 或 {系列:数据}）"), d("title", "数据图表")),
        new Intent("text_tools", "文本处理：JSON 格式化/校验、Base64、URL 编码、正则提取、大小写等",
                Arrays.asList("text_tools"), Arrays.asList(), Arrays.asList("action", "text"),
                q("action", "要做什么处理？(json格式化/json校验/base64/正则提取/转大写/转小写等)"), d("action", "json_format")),

        // ---- 文件类 ----
        new Intent("read_file", "读取文件：查看文本/配置/表格内容",
                Arrays.asList("file_reader"), Arrays.asList("path"), Arrays.asList(),
                q("path", "请告诉我文件路径或文件名"), d()),
        new Intent("analyze_file", "分析文件：读取→自动分析提取（预置组合）",
                Arrays.asList("file_analyzer"),
                Arrays.asList(
                        new FlowStep("file_reader", "read", d("file_path", "$path"), true),
                        new FlowStep("file_analyzer", "analyze", d("file_path", "$path"), false)
                ),
                Arrays.asList("path"), Arrays.asList(),
                q("path", "请告诉我要分析哪个文件"), d()),
        new Intent("generate_file", "生成文件：写文本/JSON/Markdown/网页并保存",
                Arrays.asList("file_generator"), Arrays.asList(), Arrays.asList("content"),
                d(), d()),
        new Intent("excel", "Excel 处理：读写/解析 Excel 表格",
                Arrays.asList("excel_tool"), Arrays.asList(), Arrays.asList("action", "path"),
                q("path", "请提供 Excel 文件路径"), d("action", "read")),
        new Intent("workspace", "工作区管理：查看/读取 Agent 生成的文件",
                Arrays.asList("workspace"), Arrays.asList(), Arrays.asList("action"),
                d(), d("action", "list")),

        // ---- 创作类 ----
        new Intent("image_gen", "图片生成：AI 文生图",
                Arrays.asList("image_gen"), Arrays.asList("prompt"), Arrays.asList(),
                q("prompt", "您想画什么？请描述一下画面内容"), d()),
        new Intent("video_gen", "视频生成：AI 文生视频（百炼通义万相）",
                Arrays.asList("dashscope_media"), Arrays.asList("prompt"), Arrays.asList("action"),
                q("prompt", "您想生成什么视频？请描述画面与运镜"), d("action", "video")),
        new Intent("write_code", "执行代码：运行 Python 脚本",
                Arrays.asList("python_execute"), Arrays.asList(), Arrays.asList("code"),
                q("code", "请提供要执行的代码或描述任务"), d()),

        // ---- 语音与媒体类 ----
        new Intent("speech", "朗读播报：把文字用语音读出来（弹播放组件，可停止）",
                Arrays.asList("speech_synthesis"), Arrays.asList("text"), Arrays.asList(),
                q("text", "您想让我朗读什么内容？"), d("action", "speak")),
        new Intent("voice_input", "语音输入：录音转文字（弹出录音组件）",
                Arrays.asList("voice_input"), Arrays.asList(), Arrays.asList(),
                d(), d("action", "record")),

        // ---- 能力查询类 ----
        new Intent("tool_list", "工具列表：查看本机可用的 AI 工具",
                Arrays.asList("tool_registry"), Arrays.asList(), Arrays.asList(),
                d(), d("action", "list")),
        new Intent("model_list", "模型信息：查看支持的模型与上下文窗口",
                Arrays.asList("get_models_profile"), Arrays.asList(), Arrays.asList(),
                d(), d()),

        // ---- 应用操作类 ----
        new Intent("open_app", "打开应用：启动设备上的应用",
                Arrays.asList("app_operation"), Arrays.asList("app"), Arrays.asList(),
                q("app", "您想打开哪个应用？"), d()),
        new Intent("ui_control", "系统 UI：创建对话框/提示条/进度条/选择/输入/通知/日期/时间/列表等原生组件",
                Arrays.asList("ui_component"), Arrays.asList(), Arrays.asList("action", "message", "component_type"),
                q("action", "请告诉我 UI 类型（对话框/提示条/进度条/选择/输入/通知/日期/时间/列表）"),
                d("action", "show_toast", "message", "这是一条提示")),
        new Intent("permission", "权限管理：查看/请求系统权限",
                Arrays.asList("permission_manager"), Arrays.asList(), Arrays.asList("permission"),
                q("permission", "要查看哪个权限（如 位置/录音/存储）？"), d("action", "list_permissions")),

        // ---- 记忆与账户类 ----
        new Intent("memory", "长期记忆：记住/回忆用户信息",
                Arrays.asList("memory"), Arrays.asList(), Arrays.asList("action", "key", "value"),
                q("key", "要记住什么内容？"), d("action", "save")),
        new Intent("balance", "DeepSeek 余额：查询 API 账户余额",
                Arrays.asList("deepseek_balance"), Arrays.asList(), Arrays.asList(),
                d(), d()),
        new Intent("usage", "DeepSeek 用量：统计 token 用量与费用",
                Arrays.asList("deepseek_usage_calc"), Arrays.asList(), Arrays.asList(),
                d(), d()),

        // ---- 系统维护类 ----
        new Intent("clean_models", "清理模型：预览/删除离线 AI 模型文件",
                Arrays.asList("clean_import_files"), Arrays.asList(), Arrays.asList("action"),
                d(), d("action", "list"))
    );

    /** 意图关键词表：意图名 → 触发关键词（逗号分隔） */
    private static final String[][] KEYWORDS = {
            {"weather", "天气,气温,温度,下雨,下雪,刮风,湿度,空气质量,紫外线,预报,雾霾,台风,热不热,冷不冷,天气怎么样"},
            {"location", "位置,定位,我在哪,附近,周边,坐标,经纬度,地址,城市,去哪里,在哪"},
            {"search", "搜索,搜一下,查一下,查询,新闻,资讯,热点,最新,油价,百度,谷歌"},
            {"webpage", "网页,链接,网址,url,http,打开网页,看网页,读网页"},
            {"research", "调研,研究报告,深度调研,查资料,搜集资料,写报告,调查"},
            {"quiz", "题库,查题,题目,知识点,刷题,搜索题目,考题"},
            {"system_resource", "内存,cpu,电量,存储空间,系统信息,手机信息,运行内存"},
            {"ui_control", "弹窗,弹个框,弹框,提示条,提示框,进度条,进度显示,通知我,选择一下,选一个,选一下,输入框,输入一下,日期选择,时间选择,下拉选择,列表展示,弹个提示,显示进度,弹对话框,弹窗显示"},
            {"time", "时间,日期,现在几点,时钟,星期几,几号"},
            {"unit_converter", "单位换算,换算成,换成,是多少,公里等于,英里等于,华氏度,摄氏度,公斤,千克,磅,英尺,英寸,加仑,公顷,亩,毫升,换算"},
            {"calculate", "计算,算一下,算算,数学,求和,平均,统计,等于多少,多少钱一共"},
            {"chart", "柱状图,折线图,饼图,散点图,数据可视化,画个图,生成图表,图表,画图表,柱形图"},
            {"text_tools", "json格式化,json校验,校验json,base64,url编码,url解码,正则提取,转大写,转小写,文本处理,编码解码,格式化json"},
            {"analyze_data", "数据分析,统计一下数据,分析数据,处理数据,表格分析"},
            {"read_file", "读文件,读取文件,打开文件,文件内容,查看文件,读一下,看看文件"},
            {"analyze_file", "分析文件,解析文件,文件分析,提取信息,解析"},
            {"generate_file", "生成文件,写文件,创建文件,生成网页,写一个,保存为,导出文档,写报告,生成报告,生成md,写markdown"},
            {"excel", "excel,表格文件,xlsx,xls,电子表格"},
            {"workspace", "工作区,保存的文件,生成的文件,工作区文件,看看我生成的文件"},
            {"image_gen", "画图,画一张,生成图片,生成图像,画个,画一只,画一幅,帮我画,ai绘图,画一张图"},
            {"write_code", "执行代码,运行脚本,写代码,跑python,python脚本,执行python,帮我写个脚本"},
            {"speech", "朗读,读出来,念出来,播报,语音播报,语音朗读,帮我读,读出这段,读这段话"},
            {"voice_input", "语音输入,听写,录音识别,说话转文字,语音转文字,语音打字"},
            {"tool_list", "工具列表,有哪些工具,工具介绍,会什么,可用工具,工具箱,你能做什么"},
            {"model_list", "模型列表,有哪些模型,模型信息,支持什么模型,模型文件,模型上下文"},
            {"open_app", "打开应用,打开app,启动应用,打开微信,打开浏览器,打开设置"},
            {"permission", "权限,授权,权限设置,开启权限,权限管理,权限检查"},
            {"memory", "记住,记一下,别忘了,我的名字,我的喜好,记住我"},
            {"balance", "余额,deepseek余额,账户余额,还有多少钱"},
            {"usage", "用量,费用,花费,计费,统计用量,花了多少,费用计算"},
            {"clean_models", "清理模型,删除模型,模型清理,清理文件,清理ai模型"},
    };

    // ========== 槽位提取规则 ==========

    /** 常见城市名 */
    private static final String[] KNOWN_CITIES = {
            "北京", "上海", "广州", "深圳", "杭州", "南京", "武汉", "成都", "重庆", "西安",
            "苏州", "天津", "长沙", "郑州", "青岛", "大连", "厦门", "福州", "合肥", "济南",
            "哈尔滨", "长春", "沈阳", "石家庄", "太原", "兰州", "昆明", "贵阳", "南宁", "海口"
    };

    /** 匹配意图：按关键词规则返回第一个命中的意图 */
    public Intent match(String message) {
        if (message == null) return null;
        String msg = message.toLowerCase();
        for (String[] kw : KEYWORDS) {
            for (String k : kw[1].split(",")) {
                if (!k.isEmpty() && msg.contains(k.toLowerCase())) {
                    for (Intent it : INTENTS) {
                        if (it.name.equals(kw[0])) return it;
                    }
                }
            }
        }
        return null;
    }

    /** 从用户消息提取槽位（按意图类型规则） */
    public Map<String, String> extractSlots(String message, Intent intent) {
        Map<String, String> slots = new HashMap<>();
        if (message == null || intent == null) return slots;
        String msg = message;

        switch (intent.name) {
            case "weather": {
                String city = findCity(msg);
                if (city != null) slots.put("city", city);
                break;
            }
            case "search":
            case "webpage":
            case "research":
            case "quiz": {
                String v = stripKeywords(msg, intent.name);
                if (v != null && !v.trim().isEmpty()) {
                    if ("webpage".equals(intent.name)) {
                        // 提取链接
                        String url = findUrl(msg);
                        if (url != null) slots.put("url", url);
                        else slots.put("url", v.trim());
                    } else {
                        slots.put("query", v.trim());
                        if ("research".equals(intent.name)) slots.put("topic", v.trim());
                    }
                }
                break;
            }
            case "calculate": {
                String v = stripKeywords(msg, intent.name);
                if (v != null && !v.trim().isEmpty()) slots.put("expression", v.trim());
                break;
            }
            case "unit_converter": {
                // "100公里等于多少英里" / "36.5摄氏度换成华氏度" → value/from/to
                java.util.regex.Matcher numM = java.util.regex.Pattern
                        .compile("(\\d+(?:\\.\\d+)?)").matcher(msg);
                if (numM.find()) slots.put("value", numM.group(1));
                int sep = indexOfAny(msg, "等于", "换成", "是多少");
                if (sep < 0) sep = msg.length();
                String fromPart = msg.substring(0, sep);
                String toPart = msg.substring(sep);
                String from = findUnit(fromPart);
                String to = findUnit(toPart);
                if (from != null) slots.put("from", from);
                if (to != null) slots.put("to", to);
                break;
            }
            case "chart": {
                String v = stripKeywords(msg, intent.name);
                if (v != null && !v.trim().isEmpty()) {
                    slots.put("data", v.trim());
                    slots.put("title", v.trim());
                }
                break;
            }
            case "text_tools": {
                String v = stripKeywords(msg, intent.name);
                if (v != null && !v.trim().isEmpty()) slots.put("text", v.trim());
                if (msg.contains("校验") || msg.contains("验证")) slots.put("action", "json_validate");
                else if (msg.contains("base64") || msg.contains("解码")) slots.put("action", "base64_decode");
                else if (msg.contains("编码")) slots.put("action", "base64_encode");
                else if (msg.contains("大写")) slots.put("action", "upper");
                else if (msg.contains("小写")) slots.put("action", "lower");
                else if (msg.contains("正则")) slots.put("action", "regex_extract");
                else if (msg.contains("url")) slots.put("action", "url_decode");
                else slots.put("action", "json_format");
                break;
            }
            case "read_file":
            case "analyze_file": {
                String v = stripKeywords(msg, intent.name);
                if (v != null && !v.trim().isEmpty()) slots.put("path", v.trim());
                break;
            }
            case "excel": {
                String v = stripKeywords(msg, intent.name);
                if (v != null && !v.trim().isEmpty() && !v.equals("表格")) slots.put("path", v.trim());
                break;
            }
            case "image_gen": {
                String v = stripKeywords(msg, intent.name);
                if (v != null && !v.trim().isEmpty()) slots.put("prompt", v.trim());
                break;
            }
            case "open_app": {
                String v = stripKeywords(msg, intent.name);
                if (v != null && !v.trim().isEmpty()) slots.put("app", v.trim());
                break;
            }
            case "time": {
                if (msg.contains("日期") || msg.contains("星期") || msg.contains("几号")) {
                    slots.put("format", "%Y年%m月%d日 %A");
                }
                break;
            }
            case "clean_models": {
                slots.put("action", msg.contains("确认") || msg.contains("删除") || msg.contains("清理")
                        ? "clean" : "list");
                break;
            }
            case "ui_control": {
                // component_type 直接识别（对话框/进度/提示/选择/输入/通知/日期/时间/列表），
                // 不依赖模糊的 action 字符串映射
                String ct = findComponentType(msg);
                if (ct != null) slots.put("component_type", ct);
                String m = stripKeywords(msg, intent.name);
                if (m != null && !m.trim().isEmpty()) slots.put("message", m.trim());
                break;
            }
            case "memory": {
                // "记住我叫小明" → key=user_name, value=小明；"记住我喜欢篮球" → preference
                if (msg.contains("忘") || msg.contains("删")) {
                    slots.put("action", "delete");
                } else {
                    String afterJiao = extractAfter(msg, "叫");
                    if (afterJiao != null && !afterJiao.trim().isEmpty()) {
                        slots.put("action", "save");
                        slots.put("key", "user_name");
                        slots.put("value", afterJiao.trim());
                    } else {
                        String afterXihuan = extractAfter(msg, "喜欢");
                        if (afterXihuan != null && !afterXihuan.trim().isEmpty()) {
                            slots.put("action", "save");
                            slots.put("key", "preference");
                            slots.put("value", "喜欢" + afterXihuan.trim());
                        } else {
                            String v = stripKeywords(msg, intent.name);
                            if (v != null && !v.trim().isEmpty()) {
                                slots.put("action", "save");
                                slots.put("key", "user_note");
                                slots.put("value", v.trim());
                            }
                        }
                    }
                }
                break;
            }
            case "speech": {
                String v = stripKeywords(msg, intent.name);
                if (v != null && !v.trim().isEmpty()) slots.put("text", v.trim());
                break;
            }
            case "permission": {
                String v = stripKeywords(msg, intent.name);
                if (v != null && !v.trim().isEmpty()
                        && !"权限".equals(v.trim()) && !v.trim().contains("管理")) {
                    slots.put("permission", v.trim());
                    slots.put("action", "check");
                }
                break;
            }
            default:
                break;
        }
        // 意图默认值兜底
        if (intent.defaultSlots != null) {
            for (Map.Entry<String, String> e : intent.defaultSlots.entrySet()) {
                if (!slots.containsKey(e.getKey())) slots.put(e.getKey(), e.getValue());
            }
        }
        return slots;
    }

    /** 缺失的必填槽位列表 */
    public List<String> missingRequired(Intent intent, Map<String, String> slots) {
        List<String> missing = new ArrayList<>();
        if (intent == null || intent.requiredSlots == null) return missing;
        for (String s : intent.requiredSlots) {
            String v = slots.get(s);
            if (v == null || v.trim().isEmpty()) missing.add(s);
        }
        return missing;
    }

    /** 槽位追问文案 */
    public String questionFor(Intent intent, String slot) {
        if (intent != null && intent.slotQuestions != null) {
            String qq = intent.slotQuestions.get(slot);
            if (qq != null && !qq.isEmpty()) return qq;
        }
        return "请补充必要信息（" + slot + "）";
    }

    /** 已知城市表匹配 */
    private static String findCity(String message) {
        if (message == null) return null;
        for (String c : KNOWN_CITIES) {
            if (message.contains(c)) return c;
        }
        return null;
    }

    /** URL 提取（http/https 链接） */
    private static String findUrl(String message) {
        if (message == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https?://[^\\s，。]+", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(message);
        return m.find() ? m.group() : null;
    }

    /** UI 组件类型识别（直接产出 ui_component 的 component_type） */
    private static String findComponentType(String message) {
        if (message == null) return null;
        if (message.contains("进度")) return "progress";
        if (message.contains("对话框") || message.contains("弹窗") || message.contains("dialog")) return "dialog";
        if (message.contains("提示") || message.contains("toast")) return "snackbar";
        if (message.contains("选择") || message.contains("选一个")) return "choice";
        if (message.contains("输入") || message.contains("填写")) return "input";
        if (message.contains("通知")) return "notification";
        if (message.contains("日期")) return "date";
        if (message.contains("时间")) return "time";
        if (message.contains("列表")) return "list";
        return null;
    }

    /** 单位词 → 工具单位码（长度/重量/温度/面积/体积/速度） */
    private static final String[][] UNIT_WORDS = {
            {"公里", "km"}, {"千米", "km"}, {"米", "m"}, {"厘米", "cm"}, {"毫米", "mm"},
            {"英里", "mile"}, {"英尺", "ft"}, {"英寸", "inch"}, {"码", "yd"},
            {"公斤", "kg"}, {"千克", "kg"}, {"克", "g"}, {"毫克", "mg"}, {"吨", "t"},
            {"磅", "lb"}, {"盎司", "oz"},
            {"华氏", "fahrenheit"}, {"摄氏", "celsius"}, {"开尔文", "kelvin"},
            {"平方米", "m2"}, {"公顷", "hectare"}, {"亩", "acre"},
            {"升", "l"}, {"毫升", "ml"}, {"加仑", "gallon"},
    };

    /** 从文本中找已知单位词（返回工具单位码） */
    private static String findUnit(String text) {
        if (text == null) return null;
        for (String[] u : UNIT_WORDS) {
            if (text.contains(u[0])) return u[1];
        }
        return null;
    }

    /** 首个出现位置（任一关键词），无则 -1 */
    private static int indexOfAny(String text, String... needles) {
        int best = -1;
        for (String n : needles) {
            int idx = text.indexOf(n);
            if (idx >= 0 && (best < 0 || idx < best)) best = idx;
        }
        return best;
    }

    /** 提取关键词之后的剩余文本（用于"我叫小明"→"小明"） */
    private static String extractAfter(String text, String keyword) {
        int idx = text.indexOf(keyword);
        if (idx < 0) return null;
        String rest = text.substring(idx + keyword.length()).trim();
        return rest.replaceAll("[，。！？!?\\s]+$", "");
    }

    /** 去掉意图关键词后的剩余文本（query/expression/path/prompt 等槽位） */
    private static String stripKeywords(String message, String intentName) {
        if (message == null) return "";
        String result = message;
        for (String[] kw : KEYWORDS) {
            if (!kw[0].equals(intentName)) continue;
            for (String k : kw[1].split(",")) {
                if (k.isEmpty()) continue;
                result = result.replace(k, " ");
            }
        }
        // 去掉常见语气词/标点
        result = result.replace("帮我", " ").replace("请", " ").replace("一下", " ")
                .replace("呢", " ").replace("吗", " ").replace("？", " ").replace("?", " ")
                .replace("？", " ").replace("！", " ").replace("!", " ");
        return result.replaceAll("\\s+", " ").trim();
    }
}
