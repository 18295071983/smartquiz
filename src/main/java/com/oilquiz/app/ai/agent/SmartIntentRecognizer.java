package com.oilquiz.app.ai.agent;

import android.content.Context;
import com.oilquiz.app.ai.agent.ToolResultInterpreter;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class SmartIntentRecognizer {

    private static final String TAG = "SmartIntentRecognizer";
    private static final double CONFIDENCE_HIGH = 0.70;
    private static final double CONFIDENCE_MEDIUM = 0.50;
    private static final double CONFIDENCE_LOW = 0.30;
    private static final int LLM_MAX_TOKENS = 80;
    private static final float LLM_TEMPERATURE = 0.2f;
    private static final int CACHE_MAX_SIZE = 200;

    private static SmartIntentRecognizer instance;
    private final Context context;
    private AgentService agentService;
    private final Map<String, Intent> toolNameToIntent = new HashMap<>();
    private final Map<String, Intent> toolDescToIntent = new HashMap<>();
    private final Map<String, IntentResult> resultCache = new ConcurrentHashMap<>();
    private final Map<String, MultiIntentResult> multiIntentCache = new ConcurrentHashMap<>();

    // LLM 意图识别开关。
    // 本地 Agent 模式下必须关闭：recognizeByLLM 调用 LlamaHelper.generate（nativeGenerate），
    // 会破坏 chat context 的 KV cache，导致后续 chatSend（nativeChatSend）解码时 SIGSEGV 崩溃。
    private volatile boolean llmRecognitionEnabled = true;

    public enum Intent {
        WEATHER("weather", "天气查询", true),
        SEARCH("search", "搜索查询", true),
        DATABASE("database", "数据库操作", true),
        CALCULATOR("calculator", "数学计算", true),
        TRANSLATE("translate", "翻译", false),
        CREATIVE("creative", "创意写作", false),
        LEARNING("learning", "学习辅助", false),
        ANALYSIS("analysis", "深度分析", false),
        OCR("ocr", "图片识别", true),
        FILE("file", "文件处理", true),
        WEB("web", "网页浏览", true),
        QUIZ("quiz", "题目相关", true),
        TIME("time", "时间日期", true),
        LOCATION("location", "位置地图", true),
        TIMER("timer", "计时器", true),
        CODE("code", "代码执行", true),
        CONTACT("contact", "联系人", true),
        MEDIA("media", "媒体控制", true),
        SETTINGS("settings", "系统设置", true),
        CHAT("chat", "普通对话", false),
        UNKNOWN("unknown", "未知", false);

        public final String id;
        public final String displayName;
        public final boolean needsTool;

        Intent(String id, String displayName, boolean needsTool) {
            this.id = id;
            this.displayName = displayName;
            this.needsTool = needsTool;
        }

        public static Intent fromId(String id) {
            if (id == null) return UNKNOWN;
            for (Intent intent : values()) {
                if (intent.id.equalsIgnoreCase(id)) return intent;
            }
            return UNKNOWN;
        }
    }

    public static class IntentItem {
        public final Intent intent;
        public final double confidence;
        public final String entity;
        public final String reason;

        public IntentItem(Intent intent, double confidence, String entity, String reason) {
            this.intent = intent;
            this.confidence = Math.max(0.0, Math.min(1.0, confidence));
            this.entity = entity;
            this.reason = reason;
        }

        public IntentItem(Intent intent, double confidence, String entity) {
            this(intent, confidence, entity, null);
        }
    }

    public static class MultiIntentResult {
        public final List<IntentItem> intents;
        public final boolean needsAgent;
        public final String analysis;

        public MultiIntentResult(List<IntentItem> intents, String analysis) {
            this.intents = intents != null ? intents : Collections.emptyList();
            this.needsAgent = !this.intents.isEmpty() && 
                this.intents.stream().anyMatch(i -> i.intent.needsTool && i.confidence >= CONFIDENCE_LOW);
            this.analysis = analysis;
        }

        public IntentItem getPrimaryIntent() {
            if (intents.isEmpty()) return new IntentItem(Intent.CHAT, 0.5, null);
            return intents.get(0);
        }

        public boolean hasMultipleIntents() {
            return intents.size() > 1;
        }
    }

    public static class IntentResult {
        public final Intent intent;
        public final double confidence;
        public final String extractedEntity;
        public final Map<String, Object> parameters;
        public final String source;
        public final List<IntentItem> allIntents;

        public IntentResult(Intent intent, double confidence, String extractedEntity, 
                           Map<String, Object> parameters, String source, List<IntentItem> allIntents) {
            this.intent = intent;
            this.confidence = Math.max(0.0, Math.min(1.0, confidence));
            this.extractedEntity = extractedEntity;
            this.parameters = parameters != null ? parameters : new HashMap<>();
            this.source = source;
            this.allIntents = allIntents != null ? allIntents : Collections.emptyList();
        }

        public IntentResult(Intent intent, double confidence, String source) {
            this(intent, confidence, null, new HashMap<>(), source, Collections.emptyList());
        }

        public boolean needsTool() {
            return intent.needsTool && confidence >= CONFIDENCE_LOW;
        }

        public static IntentResult defaultResult() {
            return new IntentResult(Intent.CHAT, CONFIDENCE_LOW, "default");
        }
    }

    private static final Map<Intent, String[]> KEYWORDS = new LinkedHashMap<>();
    private static final Map<Intent, Pattern[]> PATTERNS = new HashMap<>();
    private static final Map<Intent, String[]> EXCLUDE_KEYWORDS = new HashMap<>();

    static {
        KEYWORDS.put(Intent.CALCULATOR, new String[]{
            "计算", "算一下", "等于多少", "等于几", "加起来", "乘以", "除以",
            "开方", "平方", "百分比", "求和",
            "1+", "2+", "3+", "4+", "5+", "6+", "7+", "8+", "9+",
            "1×", "2×", "3×", "1÷", "2÷", "3÷"
        });
        KEYWORDS.put(Intent.WEB, new String[]{
            "网页", "网站", "链接", "爬取", "解析网页", "获取链接",
            "打开网页", "访问网址", "网页内容", "http", "https", "www."
        });
        KEYWORDS.put(Intent.TRANSLATE, new String[]{
            "翻译", "translate", "翻译成", "译成", "用英语说", "用中文说",
            "英文怎么说", "日文怎么说", "韩文怎么说", "法语怎么说", "德语怎么说",
            "译成英文", "译成中文"
        });
        KEYWORDS.put(Intent.OCR, new String[]{
            "识别", "OCR", "文字识别", "提取文字", "读取图片", "图片文字",
            "拍照识别", "图片转文字", "识别图片中的文字"
        });
        KEYWORDS.put(Intent.WEATHER, new String[]{
            "天气", "气温", "温度多少", "天气预报", "下雨吗", "下雪吗",
            "穿什么", "带伞", "紫外线", "湿度", "风力", "空气质量",
            "PM2.5", "AQI", "天气怎么样", "天气如何", "温度怎么样",
            "会不会下雨", "今天天气", "明天天气", "后天天气", "周末天气",
            "天气好吗", "外面天气", "今天气温", "温度如何", "气候"
        });
        KEYWORDS.put(Intent.QUIZ, new String[]{
            "题目", "出题", "练习题", "考试题", "错题", "题库",
            "做题", "答题", "搜题", "找题", "解答题",
            "选择题", "填空题", "判断题", "找一道题", "出一道题"
        });
        KEYWORDS.put(Intent.FILE, new String[]{
            "文件", "读取文件", "解析文件", "CSV", "JSON",
            "打开文件", "导出", "导入", "处理文件", "文件内容",
            "读取CSV", "读取JSON"
        });
        KEYWORDS.put(Intent.DATABASE, new String[]{
            "数据库", "学习统计", "做题记录", "成绩", "正确率",
            "学习进度", "统计数据", "查看记录"
        });
        KEYWORDS.put(Intent.SEARCH, new String[]{
            "搜索", "查找资料", "检索", "网上查", "帮我搜",
            "搜索一下", "百度", "谷歌", "查一下", "搜一下",
            "帮我查一下", "网上搜索", "搜索资料", "搜索一下",
            "最新", "最近", "新闻", "资讯", "最新消息",
            "查资料", "查找", "搜素", "上网查", "查下",
            "搜索信息", "搜索内容", "找一下", "找找", "了解一下"
        });
        KEYWORDS.put(Intent.LEARNING, new String[]{
            "学习", "作业", "课程", "知识点", "讲解", "解释一下",
            "什么是", "为什么", "怎么理解", "帮我学", "教我",
            "概念", "原理", "理论", "定义"
        });
        KEYWORDS.put(Intent.ANALYSIS, new String[]{
            "分析一下", "总结", "报告", "研究", "统计", "对比",
            "评估", "优缺点", "利弊", "分析报告", "综合分析"
        });
        KEYWORDS.put(Intent.CREATIVE, new String[]{
            "写一篇", "创作", "写个", "写一首", "诗歌", "作文",
            "故事", "剧本", "邮件", "演讲稿", "文案", "小说",
            "写一封", "写一段"
        });
        KEYWORDS.put(Intent.CHAT, new String[]{
            "你好", "嗨", "hello", "hi", "聊天", "谢谢", "再见",
            "早上好", "晚上好", "在吗", "你是谁", "你好吗"
        });
        KEYWORDS.put(Intent.TIME, new String[]{
            "现在几点", "几点了", "什么时间", "今天几号", "今天星期几",
            "现在时间", "日期", "星期", "几号", "周几",
            "现在是什么时间", "今天是几号", "今天星期几"
        });
        KEYWORDS.put(Intent.LOCATION, new String[]{
            "位置", "在哪", "在哪里", "我的位置", "定位", "导航",
            "地图", "附近有什么", "附近的", "怎么走", "路线",
            "距离", "多远", "附近餐厅", "附近银行", "附近医院"
        });
        KEYWORDS.put(Intent.TIMER, new String[]{
            "倒计时", "定时器", "计时", "闹钟", "提醒", "提醒我",
            "5分钟后", "10分钟后", "半小时后", "几点提醒", "设置闹钟",
            "帮我计时", "开始计时", "设置倒计时", "几点叫我",
            "每天早上", "每天提醒", "每日提醒", "每周提醒", "每周一", "每周二",
            "每周三", "每周四", "每周五", "每周六", "每周日", "每天叫我", "定时闹钟"
        });
        KEYWORDS.put(Intent.CODE, new String[]{
            "运行代码", "执行代码", "运行这个", "测试代码", "代码运行",
            "这段代码", "执行这段", "运行一下", "测试一下", "调试",
            "运行python", "运行js", "运行java", "运行C++", "代码执行"
        });
        KEYWORDS.put(Intent.CONTACT, new String[]{
            "打电话", "联系", "联系人", "电话", "发短信", "发消息",
            "拨打", "拨通", "谁的电话", "电话号码", "手机号码",
            "给张三打电话", "发消息给", "通讯录", "电话簿"
        });
        KEYWORDS.put(Intent.MEDIA, new String[]{
            "播放音乐", "听歌", "播放视频", "下一首", "上一首",
            "暂停", "继续播放", "音量", "调大音量", "调小音量",
            "静音", "播放", "停止播放", "放首歌", "随机播放"
        });
        KEYWORDS.put(Intent.SETTINGS, new String[]{
            "设置", "亮度", "Wi-Fi", "蓝牙", "飞行模式", "铃声",
            "震动", "显示", "壁纸", "主题", "设置闹钟",
            "打开蓝牙", "关闭蓝牙", "打开Wi-Fi", "连接", "开启"
        });

        EXCLUDE_KEYWORDS.put(Intent.WEATHER, new String[]{
            "冷吗", "热吗", "好冷", "好热", "太冷", "太热",
            "冷死", "热死", "冷不冷", "热不热",
            "天气真好", "天气不错", "天气很好",
            "关于寒冷", "关于天气", "天气的诗", "天气的文章",
            "写一首", "写一篇", "写个", "写一段",
            "关于冷", "关于热", "寒冷的诗", "炎热的",
            "比喻天气", "形容天气", "天气的描述",
            "天气变化", "天气预报的"
        });
        EXCLUDE_KEYWORDS.put(Intent.SEARCH, new String[]{
            "想一下", "觉得", "我觉得", "你觉得", "我想一下", "你想一下",
            "让我想想", "你想想", "思考一下", "你认为", "我认为",
            "写一首", "写一篇", "创作", "解释一下", "什么是",
            "帮我写", "帮我创作", "帮我分析", "分析一下",
            "搜索算法", "搜索技巧", "搜索引擎", "搜索原理"
        });
        EXCLUDE_KEYWORDS.put(Intent.CALCULATOR, new String[]{
            "算不算", "划算", "计算一下成本", "计算一下时间", "计算一下距离", "算算",
            "人生的意义", "时间的价值", "计算一下这个问题",
            "写一首", "写一篇", "关于数学", "数学的诗",
            "解释一下", "什么是", "分析一下", "数学原理",
            "计算方法", "计算公式", "计算器的", "数学基础",
            "数据分析", "计算模型", "计算思维"
        });
        EXCLUDE_KEYWORDS.put(Intent.LEARNING, new String[]{
            "学习成绩", "学习进度", "学习统计", "学习记录",
            "写一首", "写一篇", "创作", "搜索一下", "查一下",
            "帮我搜索", "帮我查", "最新", "新闻"
        });
        EXCLUDE_KEYWORDS.put(Intent.TRANSLATE, new String[]{
            "翻译成中文解释", "翻译一下解释",
            "翻译的", "翻译的艺术", "翻译技巧", "翻译方法",
            "翻译理论", "翻译学", "翻译史"
        });
        EXCLUDE_KEYWORDS.put(Intent.QUIZ, new String[]{
            "题目很难", "题目很简单", "这道题", "那道题",
            "关于题目", "题目的", "解题", "解题思路",
            "写一首", "写一篇", "创作", "分析一下",
            "题库", "题型", "题目类型"
        });
        EXCLUDE_KEYWORDS.put(Intent.TIME, new String[]{
            "时间的", "关于时间", "时间的诗", "时间的流逝",
            "写一首", "写一篇", "创作", "分析一下", "什么是",
            "时间管理", "时间规划", "时间的价值",
            "时间复杂度", "时间线", "时间轴",
            "学习时间", "工作时间", "时间安排"
        });
        EXCLUDE_KEYWORDS.put(Intent.LOCATION, new String[]{
            "关于位置", "位置的", "地理位置", "写一首", "写一篇",
            "关于地图", "地图的", "分析一下", "什么是",
            "位置信息", "位置服务", "定位原理",
            "地图投影", "地理信息", "空间位置"
        });
        EXCLUDE_KEYWORDS.put(Intent.TIMER, new String[]{
            "关于时间", "时间管理", "写一首", "写一篇", "创作",
            "分析一下", "什么是", "时间的价值",
            "计时器原理", "定时器原理"
        });
        EXCLUDE_KEYWORDS.put(Intent.CODE, new String[]{
            "关于代码", "代码的", "代码艺术", "写一首", "写一篇",
            "解释一下", "什么是", "分析一下", "学习代码",
            "代码原理", "编程思想", "关于编程",
            "代码规范", "代码审查", "代码质量",
            "编程范式", "编程语言", "编程技巧"
        });
        EXCLUDE_KEYWORDS.put(Intent.CONTACT, new String[]{
            "关于联系", "联系人的", "写一首", "写一篇", "创作",
            "分析一下", "什么是",
            "联系方式", "联系方法", "联系我们"
        });
        EXCLUDE_KEYWORDS.put(Intent.MEDIA, new String[]{
            "关于音乐", "音乐的", "音乐理论", "写一首", "写一篇",
            "创作", "分析一下", "什么是", "音乐的历史",
            "音乐的魅力", "音乐的作用",
            "音乐理论", "音乐教育", "音乐欣赏",
            "媒体理论", "媒体研究", "新媒体"
        });
        EXCLUDE_KEYWORDS.put(Intent.SETTINGS, new String[]{
            "关于设置", "设置的", "系统设置", "写一首", "写一篇",
            "分析一下", "什么是",
            "设置选项", "设置界面", "系统配置"
        });
        EXCLUDE_KEYWORDS.put(Intent.FILE, new String[]{
            "关于文件", "文件的", "文件管理", "写一首", "写一篇",
            "分析一下", "什么是", "文件系统",
            "文件格式", "文件类型", "文件压缩",
            "文件组织", "文件结构"
        });
        EXCLUDE_KEYWORDS.put(Intent.DATABASE, new String[]{
            "关于数据库", "数据库的", "数据库原理", "写一首", "写一篇",
            "分析一下", "什么是", "学习数据库",
            "数据库设计", "数据库模型", "SQL语句",
            "数据结构", "数据模型", "数据分析"
        });
        EXCLUDE_KEYWORDS.put(Intent.OCR, new String[]{
            "关于识别", "识别的", "模式识别", "写一首", "写一篇",
            "分析一下", "什么是", "学习识别",
            "图像识别", "文字识别", "识别算法",
            "计算机视觉", "模式识别"
        });
        EXCLUDE_KEYWORDS.put(Intent.WEB, new String[]{
            "关于网页", "网页的", "网页设计", "写一首", "写一篇",
            "分析一下", "什么是", "学习网页",
            "网页开发", "HTML", "CSS", "前端开发",
            "网络原理", "互联网技术"
        });

        PATTERNS.put(Intent.CALCULATOR, new Pattern[]{
            Pattern.compile("^\\s*[0-9]+\\s*[+\\-*/×÷]\\s*[0-9]+"),
            Pattern.compile("^\\s*[0-9]+\\.?[0-9]*\\s*[+\\-*/×÷]\\s*[0-9]+\\.?[0-9]*"),
            Pattern.compile("等于多少\\s*[？?]?\\s*$"),
            Pattern.compile("等于几\\s*[？?]?\\s*$")
        });
        PATTERNS.put(Intent.WEB, new Pattern[]{
            Pattern.compile("https?://\\S+"),
            Pattern.compile("www\\.\\S+\\.\\w+"),
        });
        PATTERNS.put(Intent.TRANSLATE, new Pattern[]{
            Pattern.compile("(翻译|translate|译成|翻成).*(英文|中文|日文|韩文|法语|德语|英语|日语|韩语)"),
            Pattern.compile("(英文|中文|日文|韩文|法语|德语)怎么说"),
        });
        PATTERNS.put(Intent.WEATHER, new Pattern[]{
            Pattern.compile("([\u4e00-\u9fa5]{2,4})(的?天气|气温|温度|下雨|下雪|穿什么)"),
            Pattern.compile("天气(怎么样|如何|怎样)"),
        });
        PATTERNS.put(Intent.QUIZ, new Pattern[]{
            Pattern.compile("(出|找|搜)(一道|几道|一些)(题|题目|练习题)"),
        });
        PATTERNS.put(Intent.TIME, new Pattern[]{
            Pattern.compile("(现在|今天)(几点|几号|星期几|时间|日期)"),
            Pattern.compile("几点(了|钟)"),
            Pattern.compile("今天(是|号|日期)"),
            Pattern.compile("星期(几|一|二|三|四|五|六|日)"),
        });
        PATTERNS.put(Intent.TIMER, new Pattern[]{
            Pattern.compile("(设置|定|闹|提醒)(个|个)(闹钟|提醒|定时器)"),
            Pattern.compile("(\\d+)(分钟|小时|秒)(后|提醒|闹钟)"),
            Pattern.compile("(提醒我|叫我)(\\d+)"),
        });
        PATTERNS.put(Intent.CONTACT, new Pattern[]{
            Pattern.compile("(打|拨)(个|电话)(给|给)"),
            Pattern.compile("(发|发送)(个|条)(短信|消息)"),
            Pattern.compile("(电话|号码)是多少"),
        });
        PATTERNS.put(Intent.MEDIA, new Pattern[]{
            Pattern.compile("(播放|放|来一首|听)"),
            Pattern.compile("(下一首|上一首|暂停|继续|静音|音量)"),
        });
        PATTERNS.put(Intent.LOCATION, new Pattern[]{
            Pattern.compile("(在哪里|在什么位置|定位|附近的|附近有)"),
            Pattern.compile("(怎么(走|去)|路线|导航)(到|去)"),
        });
        PATTERNS.put(Intent.SETTINGS, new Pattern[]{
            Pattern.compile("(打开|关闭|开启|设置)(蓝牙|WiFi|Wi-Fi|飞行模式|热点)"),
            Pattern.compile("(调|把)(亮度|音量)"),
        });
    }

    private SmartIntentRecognizer(Context context) {
        this.context = context.getApplicationContext();
        buildToolMapping();
    }

    public static synchronized SmartIntentRecognizer getInstance(Context context) {
        if (instance == null) {
            instance = new SmartIntentRecognizer(context);
        }
        return instance;
    }

    public void setAgentService(AgentService agentService) {
        this.agentService = agentService;
        buildToolMapping();
    }

    /**
     * 启用/禁用 LLM 意图识别。
     * 本地 Agent 模式下应禁用：recognizeByLLM 调用 nativeGenerate 会破坏 chat context。
     */
    public void setLLMRecognitionEnabled(boolean enabled) {
        this.llmRecognitionEnabled = enabled;
    }

    private void buildToolMapping() {
        toolNameToIntent.clear();
        toolDescToIntent.clear();

        toolNameToIntent.put("weather", Intent.WEATHER);
        toolNameToIntent.put("get_weather", Intent.WEATHER);
        toolNameToIntent.put("weather_query", Intent.WEATHER);
        toolNameToIntent.put("network_search", Intent.SEARCH);
        toolNameToIntent.put("search", Intent.SEARCH);
        toolNameToIntent.put("web_search", Intent.SEARCH);
        toolNameToIntent.put("search_questions", Intent.QUIZ);
        toolNameToIntent.put("calculate", Intent.CALCULATOR);
        toolNameToIntent.put("calculator", Intent.CALCULATOR);
        toolNameToIntent.put("database", Intent.DATABASE);
        toolNameToIntent.put("database_query", Intent.DATABASE);
        toolNameToIntent.put("generate_questions", Intent.QUIZ);
        toolNameToIntent.put("file_analysis", Intent.FILE);
        toolNameToIntent.put("web_page_reader", Intent.WEB);
        toolNameToIntent.put("read_webpage", Intent.WEB);
        toolNameToIntent.put("read_url", Intent.WEB);
        toolNameToIntent.put("smart_research", Intent.SEARCH);
        toolNameToIntent.put("system_resource", Intent.SEARCH);
        toolNameToIntent.put("file_reader", Intent.FILE);
        toolNameToIntent.put("file_analyzer", Intent.FILE);
        toolNameToIntent.put("file_generator", Intent.FILE);
        toolNameToIntent.put("permission_manager", Intent.SEARCH);

        toolDescToIntent.put("天气", Intent.WEATHER);
        toolDescToIntent.put("搜索", Intent.SEARCH);
        toolDescToIntent.put("计算", Intent.CALCULATOR);
        toolDescToIntent.put("数据库", Intent.DATABASE);
        toolDescToIntent.put("题目", Intent.QUIZ);
        toolDescToIntent.put("文件", Intent.FILE);
        toolDescToIntent.put("网页", Intent.WEB);
        toolDescToIntent.put("识别", Intent.OCR);

        if (agentService != null) {
            for (AgentService.ToolSchema schema : agentService.getToolSchemas()) {
                Intent mapped = mapToolNameToIntent(schema.name);
                if (mapped != null) {
                    toolNameToIntent.put(schema.name, mapped);
                }
                Intent descMapped = mapToolDescToIntent(schema.description);
                if (descMapped != null && !toolDescToIntent.containsValue(descMapped)) {
                    toolDescToIntent.put(schema.description, descMapped);
                }
            }
        }
    }

    private Intent mapToolNameToIntent(String toolName) {
        return mapToolNameToIntentPublic(toolName);
    }

    public Intent mapToolNameToIntentPublic(String toolName) {
        if (toolName == null) return null;
        String lower = toolName.toLowerCase();
        if (lower.contains("weather") || lower.contains("天气")) return Intent.WEATHER;
        if (lower.contains("search") || lower.contains("搜索") || lower.contains("find")) return Intent.SEARCH;
        if (lower.contains("calc") || lower.contains("math") || lower.contains("计算")) return Intent.CALCULATOR;
        if (lower.contains("database") || lower.contains("db") || lower.contains("数据")) return Intent.DATABASE;
        if (lower.contains("translat") || lower.contains("翻译")) return Intent.TRANSLATE;
        if (lower.contains("question") || lower.contains("quiz") || lower.contains("题")) return Intent.QUIZ;
        if (lower.contains("file") || lower.contains("文件")) return Intent.FILE;
        if (lower.contains("web") || lower.contains("url") || lower.contains("page")) return Intent.WEB;
        if (lower.contains("ocr") || lower.contains("识别")) return Intent.OCR;
        if (lower.contains("time") || lower.contains("时间") || lower.contains("clock") || lower.contains("日期")) return Intent.TIME;
        if (lower.contains("location") || lower.contains("位置") || lower.contains("map") || lower.contains("地图") || lower.contains("gps")) return Intent.LOCATION;
        if (lower.contains("timer") || lower.contains("闹钟") || lower.contains("提醒") || lower.contains("alarm")) return Intent.TIMER;
        if (lower.contains("code") || lower.contains("执行") || lower.contains("run") || lower.contains("代码")) return Intent.CODE;
        if (lower.contains("contact") || lower.contains("电话") || lower.contains("联系人") || lower.contains("call")) return Intent.CONTACT;
        if (lower.contains("media") || lower.contains("音乐") || lower.contains("播放") || lower.contains("player")) return Intent.MEDIA;
        if (lower.contains("setting") || lower.contains("设置") || lower.contains("config")) return Intent.SETTINGS;
        return null;
    }

    private Intent mapToolDescToIntent(String desc) {
        if (desc == null) return null;
        if (desc.contains("天气")) return Intent.WEATHER;
        if (desc.contains("搜索")) return Intent.SEARCH;
        if (desc.contains("计算")) return Intent.CALCULATOR;
        if (desc.contains("数据库")) return Intent.DATABASE;
        if (desc.contains("翻译")) return Intent.TRANSLATE;
        if (desc.contains("题目") || desc.contains("练习")) return Intent.QUIZ;
        if (desc.contains("文件")) return Intent.FILE;
        if (desc.contains("网页")) return Intent.WEB;
        if (desc.contains("识别")) return Intent.OCR;
        if (desc.contains("时间") || desc.contains("日期") || desc.contains("时钟")) return Intent.TIME;
        if (desc.contains("位置") || desc.contains("地图") || desc.contains("导航")) return Intent.LOCATION;
        if (desc.contains("闹钟") || desc.contains("提醒") || desc.contains("计时")) return Intent.TIMER;
        if (desc.contains("代码") || desc.contains("运行") || desc.contains("执行")) return Intent.CODE;
        if (desc.contains("电话") || desc.contains("联系人") || desc.contains("通讯录")) return Intent.CONTACT;
        if (desc.contains("音乐") || desc.contains("播放") || desc.contains("媒体")) return Intent.MEDIA;
        if (desc.contains("设置") || desc.contains("系统") || desc.contains("配置")) return Intent.SETTINGS;
        return null;
    }

    public IntentResult recognize(String message) {
        if (message == null || message.trim().isEmpty()) {
            return new IntentResult(Intent.CHAT, 0.0, "empty");
        }

        String trimmed = message.trim();
        if (resultCache.containsKey(trimmed)) {
            IntentResult cached = resultCache.get(trimmed);
            return new IntentResult(cached.intent, cached.confidence, cached.extractedEntity,
                new HashMap<>(cached.parameters), cached.source + "_cache", cached.allIntents);
        }

        IntentResult result = recognizeInternal(trimmed);

        if (resultCache.size() > CACHE_MAX_SIZE) {
            String oldestKey = resultCache.keySet().iterator().next();
            resultCache.remove(oldestKey);
        }
        resultCache.put(trimmed, result);

        return result;
    }

    public MultiIntentResult recognizeMultiIntent(String message) {
        if (message == null || message.trim().isEmpty()) {
            return new MultiIntentResult(Arrays.asList(new IntentItem(Intent.CHAT, 0.5, null)), "空消息");
        }

        String trimmed = message.trim();
        if (multiIntentCache.containsKey(trimmed)) {
            return multiIntentCache.get(trimmed);
        }

        MultiIntentResult result = analyzeMultiIntent(trimmed);

        if (multiIntentCache.size() > CACHE_MAX_SIZE) {
            String oldestKey = multiIntentCache.keySet().iterator().next();
            multiIntentCache.remove(oldestKey);
        }
        multiIntentCache.put(trimmed, result);

        return result;
    }

    public IntentResult recognizeWithContext(String message, String contextSummary) {
        return recognize(message);
    }

    private IntentResult recognizeInternal(String message) {
        List<IntentItem> allMatches = new ArrayList<>();
        boolean modelAvailable = LlamaHelper.isModelInitialized();

        // LLM 意图识别会调用 nativeGenerate，破坏 chat context 的 KV cache，
        // 在本地 Agent 模式下必须禁用，否则后续 chatSend 会 SIGSEGV 崩溃
        if (modelAvailable && llmRecognitionEnabled) {
            try {
                IntentResult aiResult = recognizeByLLM(message);
                if (aiResult != null && aiResult.confidence >= CONFIDENCE_LOW) {
                    allMatches.add(new IntentItem(aiResult.intent, aiResult.confidence,
                        aiResult.extractedEntity, "ai"));
                }
            } catch (Exception e) {
                AILogger.w(TAG, "AI intent recognition failed: " + e.getMessage());
            }
        }

        if (!modelAvailable || allMatches.isEmpty() || 
            (allMatches.size() == 1 && allMatches.get(0).confidence < CONFIDENCE_MEDIUM)) {
            IntentResult patternResult = recognizeByPatternsSimple(message);
            if (patternResult.intent != Intent.UNKNOWN && patternResult.confidence > 0) {
                boolean alreadyExists = allMatches.stream()
                    .anyMatch(m -> m.intent == patternResult.intent);
                if (!alreadyExists) {
                    allMatches.add(new IntentItem(patternResult.intent, patternResult.confidence,
                        patternResult.extractedEntity, "pattern"));
                }
            }

            IntentResult keywordResult = recognizeByKeywordsSimple(message);
            if (keywordResult.intent != Intent.UNKNOWN && keywordResult.confidence > 0) {
                boolean alreadyExists = allMatches.stream()
                    .anyMatch(m -> m.intent == keywordResult.intent);
                if (!alreadyExists) {
                    allMatches.add(new IntentItem(keywordResult.intent, keywordResult.confidence,
                        keywordResult.extractedEntity, "keyword"));
                }
            }
        }

        if (allMatches.isEmpty()) {
            return new IntentResult(Intent.CHAT, 0.5, null, new HashMap<>(), "default", allMatches);
        }

        Collections.sort(allMatches, (a, b) -> {
            if (a.confidence != b.confidence) {
                return Double.compare(b.confidence, a.confidence);
            }
            if ("ai".equals(a.reason) && !"ai".equals(b.reason)) return -1;
            if (!"ai".equals(a.reason) && "ai".equals(b.reason)) return 1;
            if (a.intent.needsTool && !b.intent.needsTool) return -1;
            if (!a.intent.needsTool && b.intent.needsTool) return 1;
            return 0;
        });

        IntentItem best = allMatches.get(0);
        Map<String, Object> params = new HashMap<>();
        if (best.entity != null) {
            params.put(getEntityKey(best.intent), best.entity);
        }

        AILogger.i(TAG, "Intent recognized: " + best.intent.id + " conf=" + best.confidence +
            " source=" + best.reason + ", allMatches=" + allMatches.size());

        return new IntentResult(best.intent, best.confidence, best.entity, params, best.reason, allMatches);
    }

    private MultiIntentResult analyzeMultiIntent(String message) {
        List<IntentItem> intents = new ArrayList<>();
        StringBuilder analysis = new StringBuilder();

        String[] parts = splitIntoSubTasks(message);

        for (String part : parts) {
            IntentResult result = recognizeInternal(part);
            if (result.confidence >= CONFIDENCE_LOW) {
                intents.add(new IntentItem(result.intent, result.confidence, result.extractedEntity,
                    "子任务: " + part));
            }
        }

        if (intents.isEmpty()) {
            IntentResult mainResult = recognizeInternal(message);
            intents.add(new IntentItem(mainResult.intent, mainResult.confidence, mainResult.extractedEntity,
                "主任务"));
        }

        Collections.sort(intents, (a, b) -> {
            if (a.confidence != b.confidence) {
                return Double.compare(b.confidence, a.confidence);
            }
            if (a.intent.needsTool && !b.intent.needsTool) return -1;
            return 0;
        });

        analysis.append("检测到 ").append(intents.size()).append(" 个意图: ");
        for (int i = 0; i < intents.size(); i++) {
            if (i > 0) analysis.append(", ");
            analysis.append(intents.get(i).intent.id);
        }

        return new MultiIntentResult(intents, analysis.toString());
    }

    private IntentResult recognizeByLLM(String message) {
        try {
            String prompt = buildLLMPrompt(message);
            // LlamaHelper.generate 内部有推理锁保护，会等待 chatSend 完成
            String response = LlamaHelper.generate(prompt, LLM_MAX_TOKENS, LLM_TEMPERATURE);
            response = ToolResultInterpreter.cleanModelOutput(response);

            if (response == null || response.trim().isEmpty()) {
                return null;
            }

            return parseLLMResponse(response.trim(), message);
        } catch (Exception e) {
            AILogger.w(TAG, "AI intent recognition failed: " + e.getMessage());
            return null;
        }
    }

    private String buildLLMPrompt(String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能意图识别助手。请分析用户的问题，判断其意图类型。\n\n");
        sb.append("【意图类型说明】\n\n");
        sb.append("== 工具类意图（需要调用工具）==\n\n");
        sb.append("WEATHER:天气查询\n");
        sb.append("  - 用户询问天气、气温、天气预报、穿什么、是否下雨等\n");
        sb.append("  - 示例：北京天气怎么样？今天会下雨吗？明天温度多少？\n\n");
        sb.append("SEARCH:搜索查询\n");
        sb.append("  - 用户想查找资料、新闻、信息、了解最新动态等\n");
        sb.append("  - 示例：最新科技新闻？帮我查一下AI发展趋势？搜索一下最近有什么热点？\n\n");
        sb.append("CALCULATOR:数学计算\n");
        sb.append("  - 用户需要进行数学运算、单位换算等\n");
        sb.append("  - 示例：123+456等于多少？圆周率乘以2是多少？1公里等于多少米？\n\n");
        sb.append("QUIZ:题目相关\n");
        sb.append("  - 用户想要题目、练习、测试题等\n");
        sb.append("  - 示例：出一道数学题？找几道英语练习题？给我一些选择题？\n\n");
        sb.append("OCR:图片识别\n");
        sb.append("  - 用户想要识别图片中的文字、读取图片内容\n");
        sb.append("  - 示例：识别这张图片的文字？帮我看看图片里写了什么？\n\n");
        sb.append("FILE:文件处理\n");
        sb.append("  - 用户想要读取、解析、处理文件\n");
        sb.append("  - 示例：读取这个CSV文件？帮我看看这个JSON的内容？\n\n");
        sb.append("WEB:网页浏览\n");
        sb.append("  - 用户提供了网址，想要获取网页内容\n");
        sb.append("  - 示例：帮我看看这个网页 https://example.com 写了什么？\n\n");
        sb.append("DATABASE:数据库操作\n");
        sb.append("  - 用户想要查看学习记录、成绩、统计数据等\n");
        sb.append("  - 示例：我做了多少道题？我的正确率是多少？学习进度怎么样？\n\n");
        sb.append("TIME:时间日期\n");
        sb.append("  - 用户询问现在几点、今天几号、星期几等\n");
        sb.append("  - 示例：现在几点了？今天几号？明天是星期几？\n\n");
        sb.append("LOCATION:位置地图\n");
        sb.append("  - 用户询问位置、附近有什么、导航等\n");
        sb.append("  - 示例：附近有什么餐厅？我现在在哪？怎么去火车站？\n\n");
        sb.append("TIMER:计时器\n");
        sb.append("  - 用户想要设置闹钟、提醒、倒计时等\n");
        sb.append("  - 示例：提醒我5分钟后开会？设置一个10分钟的倒计时？\n\n");
        sb.append("CODE:代码执行\n");
        sb.append("  - 用户想要运行代码、测试代码、执行程序等\n");
        sb.append("  - 示例：运行这段Python代码？帮我执行一下这个程序？\n\n");
        sb.append("CONTACT:联系人\n");
        sb.append("  - 用户想要打电话、发消息、联系某人\n");
        sb.append("  - 示例：给张三打电话？发消息给李四？小王的电话是多少？\n\n");
        sb.append("MEDIA:媒体控制\n");
        sb.append("  - 用户想要播放音乐、控制播放器等\n");
        sb.append("  - 示例：放首歌？下一首？暂停播放？调大音量？\n\n");
        sb.append("SETTINGS:系统设置\n");
        sb.append("  - 用户想要调整设置、开关功能\n");
        sb.append("  - 示例：打开蓝牙？调亮屏幕？开启飞行模式？\n\n");
        sb.append("== 非工具类意图（直接回答）==\n\n");
        sb.append("TRANSLATE:翻译\n");
        sb.append("  - 用户想要翻译文字\n");
        sb.append("  - 示例：这句话用英语怎么说？把这个翻译成中文？\n\n");
        sb.append("LEARNING:学习辅助\n");
        sb.append("  - 用户想要学习、理解概念、获取知识\n");
        sb.append("  - 示例：什么是量子力学？帮我解释一下相对论？教我怎么写代码？\n\n");
        sb.append("CREATIVE:创意写作\n");
        sb.append("  - 用户想要创作内容\n");
        sb.append("  - 示例：写一首关于春天的诗？帮我写一封邮件？编一个故事？\n\n");
        sb.append("ANALYSIS:深度分析\n");
        sb.append("  - 用户想要分析、总结、对比、评估\n");
        sb.append("  - 示例：分析一下这个方案的优缺点？总结一下这段文字？\n\n");
        sb.append("CHAT:普通对话\n");
        sb.append("  - 用户只是聊天、问候、闲聊\n");
        sb.append("  - 示例：你好？早上好？你是谁？谢谢？\n\n");
        sb.append("【核心判断原则】\n");
        sb.append("1. 理解完整语境：关注用户真正想做什么，而不是句子中的某个词\n");
        sb.append("2. 识别意图主次：一个句子可能包含多个概念，判断哪个是核心需求\n");
        sb.append("3. 区分动作和主题：\n");
        sb.append("   - 写一首关于X的诗 → 核心动作是\"写\" → CREATIVE\n");
        sb.append("   - 解释一下X是什么 → 核心动作是\"解释\" → LEARNING\n");
        sb.append("   - 分析一下X → 核心动作是\"分析\" → ANALYSIS\n");
        sb.append("4. 只有当用户明确要执行某个操作时，才选择工具类意图\n");
        sb.append("5. 置信度范围：0.0-1.0，越确定数值越高\n\n");
        sb.append("【正面示例 - 正确识别】\n");
        sb.append("北京天气怎么样？ → WEATHER:0.95（明确查询天气）\n");
        sb.append("123+456等于多少？ → CALCULATOR:0.98（明确数学运算）\n");
        sb.append("帮我查一下最新科技新闻 → SEARCH:0.90（明确搜索）\n");
        sb.append("现在几点了？ → TIME:0.95（明确查询时间）\n");
        sb.append("写一首关于春天的诗 → CREATIVE:0.92（明确创作）\n");
        sb.append("什么是量子力学？ → LEARNING:0.88（明确学习）\n\n");
        sb.append("【反面示例 - 常见误区】\n");
        sb.append("今天天气冷，我要写一首关于寒冷的诗 → CREATIVE:0.90\n");
        sb.append("  说明：提到\"天气\"但核心需求是\"写诗\"，不是查询天气\n");
        sb.append("用Python计算1+1，帮我解释一下这个代码 → LEARNING:0.85\n");
        sb.append("  说明：提到\"计算\"但核心需求是\"解释代码\"，不是执行计算\n");
        sb.append("关于时间的故事 → CREATIVE:0.90\n");
        sb.append("  说明：提到\"时间\"但核心需求是\"故事\"，不是查询时间\n");
        sb.append("搜索一下人工智能是什么 → LEARNING:0.80\n");
        sb.append("  说明：用户其实是想学习，不是要外部搜索\n");
        sb.append("音乐的历史？ → LEARNING:0.85\n");
        sb.append("  说明：询问知识，不是播放音乐\n");
        sb.append("什么是数据库？ → LEARNING:0.90\n");
        sb.append("  说明：询问概念，不是操作数据库\n\n");
        sb.append("【特别注意】\n");
        sb.append("- 如果句子包含\"写一首/写一篇/创作/编一个\" → 优先考虑 CREATIVE\n");
        sb.append("- 如果句子包含\"什么是/解释一下/帮我理解/教我\" → 优先考虑 LEARNING\n");
        sb.append("- 如果句子包含\"分析一下/对比/评估/总结\" → 优先考虑 ANALYSIS\n");
        sb.append("- 如果句子包含\"翻译/译成\" → 优先考虑 TRANSLATE\n");
        sb.append("- 工具类意图通常需要明确的动作指令，如\"查一下天气\"而不是\"天气的诗\"\n\n");
        sb.append("【输出格式】\n");
        sb.append("只输出一行：类型:置信度\n\n");
        sb.append("用户问题：");
        sb.append(userMessage);
        sb.append("\n\n输出：");
        return sb.toString();
    }

    private IntentResult parseLLMResponse(String response, String originalMessage) {
        String trimmed = response.trim();
        double confidenceFromLLM = 0.6;
        Intent intent = Intent.UNKNOWN;

        if (trimmed.contains(":")) {
            String[] parts = trimmed.split(":");
            if (parts.length >= 2) {
                intent = Intent.fromId(parts[0].trim().toUpperCase());
                try {
                    confidenceFromLLM = Double.parseDouble(parts[1].trim());
                    confidenceFromLLM = Math.max(0.0, Math.min(1.0, confidenceFromLLM));
                } catch (NumberFormatException e) {
                    confidenceFromLLM = 0.6;
                }
            }
        } else {
            intent = Intent.fromId(trimmed.toUpperCase());
        }

        if (intent == Intent.UNKNOWN) {
            String lower = trimmed.toLowerCase();
            if (lower.contains("weather") || trimmed.contains("天气")) intent = Intent.WEATHER;
            else if (lower.contains("search") || trimmed.contains("搜索")) intent = Intent.SEARCH;
            else if (lower.contains("calculator") || trimmed.contains("计算")) intent = Intent.CALCULATOR;
            else if (lower.contains("quiz") || trimmed.contains("题目")) intent = Intent.QUIZ;
            else if (lower.contains("translate") || trimmed.contains("翻译")) intent = Intent.TRANSLATE;
            else if (lower.contains("learning") || trimmed.contains("学习")) intent = Intent.LEARNING;
            else if (lower.contains("creative") || trimmed.contains("写作") || trimmed.contains("创作")) intent = Intent.CREATIVE;
            else if (lower.contains("analysis") || trimmed.contains("分析")) intent = Intent.ANALYSIS;
            else if (lower.contains("chat") || trimmed.contains("对话")) intent = Intent.CHAT;
            else if (lower.contains("time") || trimmed.contains("时间") || trimmed.contains("日期") || trimmed.contains("几点") || trimmed.contains("星期")) intent = Intent.TIME;
            else if (lower.contains("location") || trimmed.contains("位置") || trimmed.contains("地图") || trimmed.contains("附近")) intent = Intent.LOCATION;
            else if (lower.contains("timer") || trimmed.contains("闹钟") || trimmed.contains("提醒") || trimmed.contains("计时")) intent = Intent.TIMER;
            else if (lower.contains("code") || trimmed.contains("代码") || trimmed.contains("运行") || trimmed.contains("执行")) intent = Intent.CODE;
            else if (lower.contains("contact") || trimmed.contains("电话") || trimmed.contains("联系") || trimmed.contains("联系人")) intent = Intent.CONTACT;
            else if (lower.contains("media") || trimmed.contains("音乐") || trimmed.contains("播放") || trimmed.contains("媒体")) intent = Intent.MEDIA;
            else if (lower.contains("setting") || trimmed.contains("设置") || trimmed.contains("系统") || trimmed.contains("蓝牙") || trimmed.contains("wifi")) intent = Intent.SETTINGS;
            else if (lower.contains("ocr") || trimmed.contains("识别") || trimmed.contains("图片")) intent = Intent.OCR;
            else if (lower.contains("file") || trimmed.contains("文件")) intent = Intent.FILE;
            else if (lower.contains("web") || trimmed.contains("网页") || trimmed.contains("网址")) intent = Intent.WEB;
            else if (lower.contains("database") || trimmed.contains("数据库") || trimmed.contains("记录") || trimmed.contains("统计")) intent = Intent.DATABASE;
        }

        if (intent == Intent.UNKNOWN) {
            return null;
        }

        String entity = extractEntityForIntent(intent, originalMessage);
        Map<String, Object> params = new HashMap<>();
        if (entity != null) {
            params.put(getEntityKey(intent), entity);
        }

        AILogger.i(TAG, "LLM intent: " + intent.id + " conf=" + confidenceFromLLM + " raw=" + response);
        return new IntentResult(intent, confidenceFromLLM, entity, params, "ai", Collections.emptyList());
    }

    private String extractEntityForIntent(Intent intent, String message) {
        switch (intent) {
            case WEATHER: return extractCityName(message);
            case SEARCH: return extractSearchQuery(message);
            case QUIZ: return extractSubject(message);
            case CALCULATOR: return extractMathExpression(message);
            case TRANSLATE: return extractTargetLanguage(message);
            case WEB: return extractUrl(message);
            case TIME: return extractTimeInfo(message);
            case TIMER: return extractTimerInfo(message);
            case LOCATION: return extractLocationInfo(message);
            case CONTACT: return extractContactName(message);
            case CODE: return extractCodeLanguage(message);
            default: return null;
        }
    }

    private String[] splitIntoSubTasks(String message) {
        String[] separators = {"然后", "接着", "之后", "再", "然后再", "同时", "并", "并且", "，", ",", ";", "；"};
        String temp = message;
        for (String sep : separators) {
            temp = temp.replace(sep, "|||");
        }
        String[] parts = temp.split("\\|\\|\\|");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.length() >= 3) {
                result.add(trimmed);
            }
        }
        if (result.isEmpty()) {
            result.add(message);
        }
        return result.toArray(new String[0]);
    }

    private IntentResult recognizeByKeywordsSimple(String message) {
        String lower = message.toLowerCase();
        Intent bestIntent = Intent.UNKNOWN;
        double bestConfidence = 0;
        String bestEntity = null;

        String[] actionVerbs = {"写一首", "写一篇", "创作", "编一个", "编一段",
            "解释一下", "什么是", "什么叫", "帮我理解", "教我",
            "分析一下", "对比一下", "评估一下", "总结一下",
            "翻译", "译成", "翻成", "怎么说"
        };
        boolean hasCreativeAction = lower.contains("写一首") || lower.contains("写一篇") || 
            lower.contains("创作") || lower.contains("编一个") || lower.contains("编一段");
        boolean hasLearningAction = lower.contains("解释一下") || lower.contains("什么是") || 
            lower.contains("什么叫") || lower.contains("帮我理解") || lower.contains("教我");
        boolean hasAnalysisAction = lower.contains("分析一下") || lower.contains("对比一下") ||
            lower.contains("评估一下") || lower.contains("总结一下");
        boolean hasTranslateAction = lower.contains("翻译") || lower.contains("译成") ||
            lower.contains("翻成") || lower.contains("怎么说");

        for (Map.Entry<Intent, String[]> entry : KEYWORDS.entrySet()) {
            Intent intent = entry.getKey();
            String[] keywords = entry.getValue();

            if (intent.needsTool) {
                if (hasCreativeAction) continue;
                if (hasLearningAction) continue;
                if (hasAnalysisAction) continue;
            }

            if (EXCLUDE_KEYWORDS.containsKey(intent)) {
                boolean excluded = false;
                for (String ex : EXCLUDE_KEYWORDS.get(intent)) {
                    if (lower.contains(ex)) {
                        excluded = true;
                        break;
                    }
                }
                if (excluded) continue;
            }

            int matchCount = 0;
            String matchedKeyword = null;
            for (String keyword : keywords) {
                if (lower.contains(keyword.toLowerCase())) {
                    matchCount++;
                    if (matchedKeyword == null) matchedKeyword = keyword;
                }
            }

            if (matchCount > 0) {
                double matchRatio = (double) matchCount / Math.max(1, keywords.length);
                double confidence = 0.25 + 0.35 * Math.min(1.0, matchRatio * 2);

                if (matchedKeyword != null && matchedKeyword.length() >= 2) {
                    confidence += 0.03;
                }

                String entity = null;
                if (intent == Intent.WEATHER) {
                    entity = extractCityName(message);
                    if (entity != null) confidence += 0.08;
                } else if (intent == Intent.QUIZ) {
                    entity = extractSubject(message);
                    if (entity != null) confidence += 0.06;
                } else if (intent == Intent.CALCULATOR) {
                    entity = extractMathExpression(message);
                    if (entity != null) confidence += 0.1;
                } else if (intent == Intent.SEARCH) {
                    entity = extractSearchQuery(message);
                } else if (intent == Intent.TRANSLATE) {
                    entity = extractTargetLanguage(message);
                    if (entity != null) confidence += 0.06;
                } else if (intent == Intent.WEB) {
                    entity = extractUrl(message);
                    if (entity != null) confidence += 0.08;
                }

                if (intent.needsTool) {
                    confidence *= 0.85;
                }

                confidence = Math.min(0.8, confidence);

                if (confidence > bestConfidence) {
                    bestConfidence = confidence;
                    bestIntent = intent;
                    bestEntity = entity;
                }
            }
        }

        if (hasCreativeAction && bestIntent.needsTool) {
            bestIntent = Intent.CREATIVE;
            bestConfidence = 0.8;
        } else if (hasLearningAction && bestIntent.needsTool) {
            bestIntent = Intent.LEARNING;
            bestConfidence = 0.75;
        } else if (hasAnalysisAction && bestIntent.needsTool) {
            bestIntent = Intent.ANALYSIS;
            bestConfidence = 0.75;
        } else if (hasTranslateAction) {
            bestIntent = Intent.TRANSLATE;
            bestConfidence = 0.8;
        }

        Map<String, Object> params = new HashMap<>();
        if (bestEntity != null) {
            params.put(getEntityKey(bestIntent), bestEntity);
        }

        return new IntentResult(bestIntent, bestConfidence, bestEntity, params, "keyword", Collections.emptyList());
    }

    private IntentResult recognizeByPatternsSimple(String message) {
        String lower = message.toLowerCase();
        Intent bestIntent = Intent.UNKNOWN;
        double bestConfidence = 0;
        String bestEntity = null;

        boolean hasCreativeAction = lower.contains("写一首") || lower.contains("写一篇") || 
            lower.contains("创作") || lower.contains("编一个") || lower.contains("编一段");
        boolean hasLearningAction = lower.contains("解释一下") || lower.contains("什么是") || 
            lower.contains("什么叫") || lower.contains("帮我理解") || lower.contains("教我");
        boolean hasAnalysisAction = lower.contains("分析一下") || lower.contains("对比一下") ||
            lower.contains("评估一下") || lower.contains("总结一下");

        for (Map.Entry<Intent, Pattern[]> entry : PATTERNS.entrySet()) {
            Intent intent = entry.getKey();
            Pattern[] patterns = entry.getValue();

            if (intent.needsTool) {
                if (hasCreativeAction) continue;
                if (hasLearningAction) continue;
                if (hasAnalysisAction) continue;
            }

            for (Pattern pattern : patterns) {
                java.util.regex.Matcher matcher = pattern.matcher(message);
                if (matcher.find()) {
                    double confidence = 0.5;

                    String entity = null;
                    if (intent == Intent.WEATHER) {
                        entity = extractCityName(message);
                        if (entity != null) confidence += 0.18;
                    } else if (intent == Intent.CALCULATOR) {
                        entity = extractMathExpression(message);
                        if (entity != null) confidence += 0.22;
                    } else if (intent == Intent.WEB) {
                        entity = extractUrl(message);
                        if (entity != null) confidence += 0.18;
                    } else if (intent == Intent.TRANSLATE) {
                        entity = extractTargetLanguage(message);
                        if (entity != null) confidence += 0.13;
                    } else if (intent == Intent.QUIZ) {
                        entity = extractSubject(message);
                        if (entity != null) confidence += 0.08;
                    }

                    if (intent.needsTool) {
                        confidence *= 0.85;
                    }

                    confidence = Math.min(0.85, confidence);

                    if (confidence > bestConfidence) {
                        bestConfidence = confidence;
                        bestIntent = intent;
                        bestEntity = entity;
                    }
                }
            }
        }

        if (hasCreativeAction && bestIntent.needsTool) {
            bestIntent = Intent.CREATIVE;
            bestConfidence = 0.8;
        } else if (hasLearningAction && bestIntent.needsTool) {
            bestIntent = Intent.LEARNING;
            bestConfidence = 0.75;
        } else if (hasAnalysisAction && bestIntent.needsTool) {
            bestIntent = Intent.ANALYSIS;
            bestConfidence = 0.75;
        }

        Map<String, Object> params = new HashMap<>();
        if (bestEntity != null) {
            params.put(getEntityKey(bestIntent), bestEntity);
        }

        return new IntentResult(bestIntent, bestConfidence, bestEntity, params, "pattern", Collections.emptyList());
    }

    private String getEntityKey(Intent intent) {
        switch (intent) {
            case WEATHER: return "city";
            case SEARCH: return "query";
            case QUIZ: return "subject";
            case CALCULATOR: return "expression";
            case TRANSLATE: return "target_language";
            case WEB: return "url";
            case TIME: return "time_type";
            case TIMER: return "timer_duration";
            case LOCATION: return "location_query";
            case CONTACT: return "contact_name";
            case CODE: return "language";
            case MEDIA: return "media_action";
            case SETTINGS: return "setting_action";
            default: return "entity";
        }
    }

    public boolean shouldUseAgent(String message) {
        IntentResult result = recognize(message);
        return result.intent.needsTool && result.confidence >= CONFIDENCE_LOW;
    }

    public boolean needsMultipleTools(String message) {
        MultiIntentResult result = recognizeMultiIntent(message);
        return result.hasMultipleIntents() && result.needsAgent;
    }

    public List<IntentItem> getAllIntents(String message) {
        IntentResult result = recognize(message);
        if (!result.allIntents.isEmpty()) {
            return result.allIntents;
        }
        return Arrays.asList(new IntentItem(result.intent, result.confidence, result.extractedEntity, result.source));
    }

    public String getIntentType(String message) {
        return recognize(message).intent.id;
    }

    public String getRecommendedTool(Intent intent) {
        if (agentService != null) {
            for (AgentService.ToolSchema schema : agentService.getToolSchemas()) {
                Intent mapped = toolNameToIntent.get(schema.name);
                if (mapped == intent) {
                    return schema.name;
                }
            }
            for (AgentService.ToolSchema schema : agentService.getToolSchemas()) {
                Intent descMapped = mapToolDescToIntent(schema.description);
                if (descMapped == intent) {
                    return schema.name;
                }
            }
        }
        return getIntentFallbackTool(intent);
    }

    private String getIntentFallbackTool(Intent intent) {
        switch (intent) {
            case WEATHER: return "weather";
            case SEARCH: return "network_search";
            case QUIZ: return "search_questions";
            case CALCULATOR: return "calculator";
            case DATABASE: return "database";
            case FILE: return "file_reader";
            case WEB: return "read_webpage";
            case TIME: return "system_resource";
            case LOCATION: return "system_resource";
            case TIMER: return "system_resource";
            case CODE: return "system_resource";
            case CONTACT: return "system_resource";
            case MEDIA: return "system_resource";
            case SETTINGS: return "system_resource";
            default: return null;
        }
    }

    public void clearCache() {
        resultCache.clear();
        multiIntentCache.clear();
    }

    private String extractCityName(String message) {
        String[] suffixes = {"市", "县", "区", "镇", "省"};
        for (String suffix : suffixes) {
            int idx = message.indexOf(suffix);
            if (idx > 0) {
                int start = Math.max(0, idx - 3);
                return message.substring(start, idx + 1);
            }
        }
        String[] knownCities = {"北京", "上海", "广州", "深圳", "杭州", "成都", "重庆", "武汉",
            "南京", "西安", "长沙", "天津", "苏州", "郑州", "东莞", "青岛", "沈阳", "宁波", "昆明",
            "大连", "厦门", "福州", "无锡", "合肥", "济南", "佛山", "哈尔滨", "长春", "石家庄",
            "贵阳", "南宁", "太原", "兰州", "海口", "三亚", "拉萨", "呼和浩特", "银川", "西宁",
            "乌鲁木齐", "南昌", "温州", "珠海", "中山", "惠州", "常州", "徐州", "烟台", "洛阳"};
        for (String city : knownCities) {
            if (message.contains(city)) return city;
        }
        return null;
    }

    private String extractSearchQuery(String message) {
        String[] prefixes = {"搜索", "查找", "检索", "搜一下", "查一下", "帮我搜", "帮我查", "帮我找",
            "网上查", "百度", "谷歌", "搜索一下"};
        for (String prefix : prefixes) {
            int idx = message.indexOf(prefix);
            if (idx >= 0) {
                String query = message.substring(idx + prefix.length()).trim();
                if (!query.isEmpty()) {
                    return query.replaceAll("[的了吗？?！!。.，,]", "");
                }
            }
        }
        return null;
    }

    private String extractSubject(String message) {
        String[] subjects = {"数学", "语文", "英语", "物理", "化学", "生物", "历史", "地理", "政治",
            "计算机", "编程", "法律", "医学", "经济", "金融", "会计"};
        for (String subject : subjects) {
            if (message.contains(subject)) return subject;
        }
        return null;
    }

    private String extractMathExpression(String message) {
        StringBuilder expr = new StringBuilder();
        boolean foundDigit = false;
        boolean foundOperator = false;

        for (char c : message.toCharArray()) {
            if (Character.isDigit(c)) {
                expr.append(c);
                foundDigit = true;
            } else if ("+-*/.()×÷".indexOf(c) >= 0) {
                expr.append(c == '×' ? '*' : (c == '÷' ? '/' : c));
                foundOperator = true;
            } else if (Character.isSpaceChar(c)) {
                expr.append(c);
            } else if (foundDigit && foundOperator) {
                break;
            }
        }

        String result = expr.toString().trim();
        if (result.contains("+") || result.contains("-") || result.contains("*") || result.contains("/")) {
            return result.isEmpty() ? null : result;
        }
        return null;
    }

    private String extractTargetLanguage(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("英文") || lower.contains("英语")) return "english";
        if (lower.contains("中文") || lower.contains("汉语")) return "chinese";
        if (lower.contains("日文") || lower.contains("日语")) return "japanese";
        if (lower.contains("韩文") || lower.contains("韩语")) return "korean";
        if (lower.contains("法语")) return "french";
        if (lower.contains("德语")) return "german";
        return null;
    }

    private String extractUrl(String message) {
        int httpStart = message.toLowerCase().indexOf("http");
        if (httpStart >= 0) {
            int end = message.indexOf(' ', httpStart);
            if (end < 0) end = message.length();
            return message.substring(httpStart, end);
        }
        int wwwStart = message.toLowerCase().indexOf("www.");
        if (wwwStart >= 0) {
            int end = message.indexOf(' ', wwwStart);
            if (end < 0) end = message.length();
            return message.substring(wwwStart, end);
        }
        return null;
    }

    private String extractTimeInfo(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("几点") || lower.contains("时间")) return "current_time";
        if (lower.contains("几号") || lower.contains("日期")) return "current_date";
        if (lower.contains("星期")) return "current_weekday";
        return "current_time";
    }

    private String extractTimerInfo(String message) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)(分钟|小时|秒|点)").matcher(message);
        if (m.find()) {
            return m.group(1) + m.group(2);
        }
        return null;
    }

    private String extractLocationInfo(String message) {
        String[] locationPrefixes = {"附近", "附近的", "离我最近的", "最近的", "哪里有", "在哪有"};
        String[] locationSuffixes = {"餐厅", "饭店", "银行", "医院", "药店", "超市", "商店",
            "加油站", "停车场", "公交站", "地铁站", "火车站", "机场"};
        
        for (String prefix : locationPrefixes) {
            for (String suffix : locationSuffixes) {
                if (message.contains(prefix + suffix) || message.contains(prefix + "的" + suffix)) {
                    return suffix;
                }
            }
        }
        
        if (message.contains("我的位置") || message.contains("我在哪")) {
            return "my_location";
        }
        if (message.contains("导航") || message.contains("怎么走")) {
            return "navigation";
        }
        return null;
    }

    private String extractContactName(String message) {
        String[] contactPrefixes = {"给", "打电话给", "发消息给", "联系", "拨打"};
        for (String prefix : contactPrefixes) {
            int idx = message.indexOf(prefix);
            if (idx >= 0) {
                String rest = message.substring(idx + prefix.length()).trim();
                if (!rest.isEmpty()) {
                    int endIdx = rest.indexOf("打电话");
                    if (endIdx < 0) endIdx = rest.indexOf("发消息");
                    if (endIdx < 0) endIdx = rest.length();
                    String name = rest.substring(0, endIdx).trim();
                    if (!name.isEmpty()) {
                        return name.replaceAll("[的了吗？?！!。.，,]", "").trim();
                    }
                }
            }
        }
        return null;
    }

    private String extractCodeLanguage(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("python")) return "python";
        if (lower.contains("javascript") || lower.contains("js")) return "javascript";
        if (lower.contains("java") && !lower.contains("javascript")) return "java";
        if (lower.contains("c++") || lower.contains("cpp")) return "cpp";
        if (lower.contains("c#") || lower.contains("csharp")) return "csharp";
        if (lower.contains("go") || lower.contains("golang")) return "go";
        if (lower.contains("rust")) return "rust";
        if (lower.contains("typescript") || lower.contains("ts")) return "typescript";
        if (lower.contains("php")) return "php";
        if (lower.contains("ruby")) return "ruby";
        if (lower.contains("swift")) return "swift";
        if (lower.contains("kotlin")) return "kotlin";
        return "python";
    }
}
