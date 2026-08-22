package com.oilquiz.app.ai.intent;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 智能意图识别器
 * 基于规则和关键词匹配实现意图识别
 */
public class IntelligentIntentRecognizer {
    
    // 意图关键词映射
    private static final Map<PrimaryIntent, String[]> INTENT_KEYWORDS = new HashMap<>();
    private static final Map<PrimaryIntent, Pattern[]> INTENT_PATTERNS = new HashMap<>();
    
    static {
        // OCR相关
        INTENT_KEYWORDS.put(PrimaryIntent.OCR, new String[]{
            "识别", "OCR", "文字识别", "提取文字", "读取图片", "图片文字"
        });
        
        // 图片处理相关
        INTENT_KEYWORDS.put(PrimaryIntent.IMAGE, new String[]{
            "图片", "照片", "截图", "裁剪", "缩放", "旋转", "生成图片", "保存图片"
        });
        
        // 文件处理相关
        INTENT_KEYWORDS.put(PrimaryIntent.FILE, new String[]{
            "文件", "读取文件", "解析文件", "CSV", "JSON", "文本", "打开文件"
        });
        
        // 网页解析相关
        INTENT_KEYWORDS.put(PrimaryIntent.WEB, new String[]{
            "网页", "网站", "HTML", "链接", "爬取", "解析网页", "获取链接"
        });
        
        // 天气相关
        INTENT_KEYWORDS.put(PrimaryIntent.WEATHER, new String[]{
            "天气", "气温", "温度", "预报", "天气情况", "今天天气"
        });
        
        // 搜索相关
        INTENT_KEYWORDS.put(PrimaryIntent.SEARCH, new String[]{
            "搜索", "查找", "查询", "搜索一下", "帮我找"
        });
        
        // 翻译相关
        INTENT_KEYWORDS.put(PrimaryIntent.TRANSLATE, new String[]{
            "翻译", "英文", "中文", "日语", "韩语", "译成", "翻译一下"
        });
        
        // 创意写作相关
        INTENT_KEYWORDS.put(PrimaryIntent.CREATIVE, new String[]{
            "写", "创作", "生成", "诗歌", "作文", "故事", "剧本", "邮件", "演讲"
        });
        
        // 数据库相关
        INTENT_KEYWORDS.put(PrimaryIntent.DATABASE, new String[]{
            "数据库", "查询数据", "数据", "SQL", "表格", "记录"
        });
        
        // 学习相关
        INTENT_KEYWORDS.put(PrimaryIntent.LEARNING, new String[]{
            "学习", "题目", "分析", "解答", "作业", "课程", "知识"
        });
        
        // 分析相关
        INTENT_KEYWORDS.put(PrimaryIntent.ANALYSIS, new String[]{
            "分析", "总结", "报告", "研究", "统计", "数据"
        });
        
        // 聊天相关
        INTENT_KEYWORDS.put(PrimaryIntent.CHAT, new String[]{
            "你好", "嗨", "hello", "hi", "聊天", "聊", "问"
        });
        
        // 计算器相关
        INTENT_KEYWORDS.put(PrimaryIntent.CALCULATOR, new String[]{
            "计算", "算一下", "等于多少", "运算", "math", "calculator",
            "+", "-", "*", "/", "加减乘除", "数学"
        });
        INTENT_KEYWORDS.put(PrimaryIntent.PYTHON, new String[]{
            "python", "Python", "写代码", "代码", "编程", "程序",
            "用python", "用Python", "生成代码", "执行代码", "写个函数",
            "阶乘", "排序", "算法", "脚本", ".py"
        });

        INTENT_PATTERNS.put(PrimaryIntent.CALCULATOR, new Pattern[]{
            Pattern.compile("[0-9]+[\\+\\-\\*/][0-9]+"),
            Pattern.compile("[0-9]+\\s*[\\+\\-\\*/]\\s*[0-9]+")
        });
    }
    
    /**
     * 识别用户意图
     * @param message 用户输入消息
     * @param context 上下文信息
     * @return 意图识别结果
     */
    public static IntentResult recognize(String message, Object context) {
        if (message == null || message.trim().isEmpty()) {
            return new IntentResult(PrimaryIntent.UNKNOWN, 0.0);
        }
        
        String lowerMessage = message.toLowerCase().trim();
        double highestConfidence = 0.0;
        PrimaryIntent bestIntent = PrimaryIntent.UNKNOWN;
        
        // 遍历所有意图进行匹配
        for (Map.Entry<PrimaryIntent, String[]> entry : INTENT_KEYWORDS.entrySet()) {
            PrimaryIntent intent = entry.getKey();
            String[] keywords = entry.getValue();
            
            double confidence = calculateConfidence(lowerMessage, intent, keywords);
            
            if (confidence > highestConfidence) {
                highestConfidence = confidence;
                bestIntent = intent;
            }
        }
        
        // 如果置信度太低，尝试更宽泛的匹配
        if (highestConfidence < 0.3) {
            bestIntent = fallbackIntentDetection(lowerMessage);
            highestConfidence = 0.3;
        }
        
        return new IntentResult(bestIntent, highestConfidence);
    }
    
    /**
     * 计算置信度
     */
    private static double calculateConfidence(String message, PrimaryIntent intent, String[] keywords) {
        int matchCount = 0;
        int totalKeywords = keywords.length;
        
        for (String keyword : keywords) {
            if (message.contains(keyword.toLowerCase())) {
                matchCount++;
            }
        }
        
        // 基础置信度：匹配的关键词比例
        double baseConfidence = (double) matchCount / totalKeywords;
        
        // 模式匹配增强
        if (INTENT_PATTERNS.containsKey(intent)) {
            Pattern[] patterns = INTENT_PATTERNS.get(intent);
            for (Pattern pattern : patterns) {
                if (pattern.matcher(message).find()) {
                    baseConfidence = Math.min(1.0, baseConfidence + 0.3);
                    break;
                }
            }
        }
        
        return baseConfidence;
    }
    
    /**
     * 回退意图检测
     */
    private static PrimaryIntent fallbackIntentDetection(String message) {
        // 检查是否是问候语
        if (message.contains("你好") || message.contains("嗨") || 
            message.contains("hello") || message.contains("hi") ||
            message.contains("您好") || message.contains("早上好") ||
            message.contains("下午好") || message.contains("晚上好")) {
            return PrimaryIntent.CHAT;
        }
        
        // 检查是否是数学计算
        if (message.matches(".*[0-9]+.*[\\+\\-\\*/].*[0-9]+.*")) {
            return PrimaryIntent.CALCULATOR;
        }

        // 检查是否是需要写代码的请求（自然语言）
        if (isCodeGenerationRequest(message)) {
            return PrimaryIntent.PYTHON;
        }
        
        // 检查是否包含文件名或路径
        if (message.contains(".txt") || message.contains(".json") ||
            message.contains(".csv") || message.contains(".pdf") ||
            message.contains("/") || message.contains("\\")) {
            return PrimaryIntent.FILE;
        }
        
        // 检查是否是网址
        if (message.contains("http://") || message.contains("https://") ||
            message.contains("www.") || message.contains(".com")) {
            return PrimaryIntent.WEB;
        }
        
        // 默认返回聊天
        return PrimaryIntent.CHAT;
    }

    /**
     * 检测是否是代码生成请求（自然语言）
     */
    private static boolean isCodeGenerationRequest(String message) {
        String lower = message.toLowerCase();
        // 代码生成相关动作词
        String[] actionWords = {"写", "生成", "实现", "编写", "创建", "做一个", "给我"};
        // 代码相关名词
        String[] codeWords = {"代码", "程序", "函数", "算法", "脚本"};
        // 算法相关
        String[] algoWords = {"排序", "查找", "搜索", "遍历", "递归", "迭代"};
        // 计算相关
        String[] calcWords = {"平均数", "求和", "最大值", "最小值", "中位数", "方差", "阶乘", "斐波那契"};

        // 检查是否是写代码的请求
        boolean hasAction = false;
        for (String word : actionWords) {
            if (lower.contains(word)) {
                hasAction = true;
                break;
            }
        }

        if (hasAction) {
            // 如果有动作词，检查是否跟代码/程序相关
            for (String word : codeWords) {
                if (lower.contains(word)) return true;
            }
            for (String word : algoWords) {
                if (lower.contains(word)) return true;
            }
            for (String word : calcWords) {
                if (lower.contains(word)) return true;
            }
        }

        return false;
    }
    
    /**
     * 获取推荐工具
     */
    public static String getRecommendedTool(PrimaryIntent intent) {
        switch (intent) {
            case OCR:
                return "app_toolkit";
            case IMAGE:
                return "app_toolkit";
            case FILE:
                return "file_reader";
            case WEB:
                return "app_toolkit";
            case WEATHER:
                return "ai_weather";
            case SEARCH:
                return "network_search";
            case TRANSLATE:
                // 翻译工具已移除，翻译请求由 LLM 直接完成
                return null;
            case CALCULATOR:
                return "calculator";
            case DATABASE:
                return "database";
            case PYTHON:
                return "python_execute";
            default:
                return null;
        }
    }
    
    /**
     * 构建工具调用参数
     */
    public static Map<String, Object> buildToolParameters(PrimaryIntent intent, String message) {
        Map<String, Object> params = new HashMap<>();
        
        switch (intent) {
            case OCR:
                params.put("action", "ocr_recognize");
                params.put("image_path", extractFilePath(message));
                params.put("language", detectLanguage(message));
                break;
                
            case IMAGE:
                params.put("action", detectImageAction(message));
                params.put("image_path", extractFilePath(message));
                params.put("output_path", extractOutputPath(message));
                break;
                
            case FILE:
                params.put("action", "read");
                params.put("file_path", extractFilePath(message));
                break;
                
            case WEB:
                params.put("action", detectWebAction(message));
                params.put("source", extractUrl(message));
                params.put("source_type", "string");
                break;
                
            case CALCULATOR:
                params.put("expression", extractMathExpression(message));
                break;

            case PYTHON:
                params.put("task", extractPythonTask(message));
                break;

            default:
                // 不需要参数
                break;
        }
        
        return params;
    }
    
    /**
     * 检测图片操作类型
     */
    private static String detectImageAction(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("裁剪")) return "image_crop";
        if (lower.contains("缩放")) return "image_scale";
        if (lower.contains("旋转")) return "image_rotate";
        if (lower.contains("保存")) return "image_save";
        if (lower.contains("生成")) {
            if (lower.contains("文字")) return "image_generate_text";
            return "image_generate_color";
        }
        return "image_save";
    }
    
    /**
     * 检测网页操作类型
     */
    private static String detectWebAction(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("标题")) return "web_get_title";
        if (lower.contains("链接")) return "web_get_links";
        if (lower.contains("图片")) return "web_get_images";
        if (lower.contains("正文") || lower.contains("内容")) return "web_get_text";
        return "web_parse_html";
    }
    
    /**
     * 提取文件路径
     */
    private static String extractFilePath(String message) {
        // 简单实现：查找包含常见扩展名的路径
        String[] extensions = {".jpg", ".jpeg", ".png", ".webp", ".pdf", ".txt", ".json", ".csv"};
        for (String ext : extensions) {
            int idx = message.toLowerCase().indexOf(ext);
            if (idx > 0) {
                // 向前查找路径开始位置
                int start = message.lastIndexOf('/', idx);
                if (start < 0) start = message.lastIndexOf('\\', idx);
                if (start < 0) start = 0;
                return message.substring(start, idx + ext.length());
            }
        }
        return null;
    }
    
    /**
     * 提取URL
     */
    private static String extractUrl(String message) {
        int httpStart = message.toLowerCase().indexOf("http://");
        if (httpStart < 0) httpStart = message.toLowerCase().indexOf("https://");
        if (httpStart < 0) httpStart = message.toLowerCase().indexOf("www.");
        
        if (httpStart >= 0) {
            int end = message.indexOf(' ', httpStart);
            if (end < 0) end = message.length();
            return message.substring(httpStart, end);
        }
        return message;
    }
    
    /**
     * 提取数学表达式
     */
    private static String extractMathExpression(String message) {
        // 提取数字和运算符
        StringBuilder expr = new StringBuilder();
        for (char c : message.toCharArray()) {
            if (Character.isDigit(c) || "+-*/.()".indexOf(c) >= 0 || Character.isSpaceChar(c)) {
                expr.append(c);
            }
        }
        return expr.toString().trim();
    }

    /**
     * 提取 Python 任务描述（支持自然语言）
     */
    private static String extractPythonTask(String message) {
        String task = message.trim();
        
        // 移除各种前缀
        String[] prefixes = {
            "(?i)用\\s*python", "(?i)用\\s*Python", "(?i)python", "(?i)Python",
            "(?i)写\\s*(一个|个)?\\s*代码", "(?i)编程", "(?i)生成\\s*代码",
            "(?i)给我\\s*写", "(?i)实现\\s*(一个|个)?", "(?i)创建\\s*(一个|个)?",
            "(?i)写\\s*(一个|个)?\\s*", "(?i)用代码\\s*"
        };
        for (String prefix : prefixes) {
            task = task.replaceAll(prefix, "");
        }
        task = task.trim();
        
        // 如果清理后太短，返回原始消息
        if (task.length() < 2) {
            return message;
        }
        return task;
    }

    /**
     * 检测语言
     */
    private static String detectLanguage(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("中文") || lower.contains("汉语")) return "chinese";
        if (lower.contains("英文") || lower.contains("英语")) return "english";
        if (lower.contains("日语") || lower.contains("日文")) return "japanese";
        if (lower.contains("韩语") || lower.contains("韩文")) return "korean";
        return "auto";
    }
    
    /**
     * 提取输出路径
     */
    private static String extractOutputPath(String message) {
        // 简单实现：返回默认路径
        return "/storage/emulated/0/output.png";
    }
    
    public enum PrimaryIntent {
        OCR("OCR识别"),
        IMAGE("图片处理"),
        FILE("文件处理"),
        WEB("网页解析"),
        WEATHER("天气"),
        SEARCH("搜索"),
        TRANSLATE("翻译"),
        CREATIVE("创意写作"),
        DATABASE("数据库"),
        LEARNING("学习"),
        ANALYSIS("分析"),
        CHAT("聊天"),
        CALCULATOR("计算器"),
        PYTHON("Python编程"),
        UNKNOWN("未知");
        
        private final String displayName;
        
        PrimaryIntent(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    public static class IntentResult {
        public PrimaryIntent primaryIntent;
        public double confidence;
        
        public IntentResult(PrimaryIntent primaryIntent, double confidence) {
            this.primaryIntent = primaryIntent;
            this.confidence = confidence;
        }
    }
}