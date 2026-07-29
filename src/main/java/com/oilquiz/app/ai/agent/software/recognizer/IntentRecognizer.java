package com.oilquiz.app.ai.agent.software.recognizer;

import com.oilquiz.app.ai.agent.software.model.ComplexityLevel;
import com.oilquiz.app.ai.agent.software.model.IntentResult;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IntentRecognizer - 意图识别模块
 *
 * 使用 LLM 识别用户意图
 * 硬件层调用: LLM.generate()
 */
public class IntentRecognizer {

    private static final String TAG = "IntentRecognizer";
    private final AIService aiService;

    /**
     * 合并分析结果：意图 + 复杂度（一次 LLM 调用完成）
     */
    public static class IntentAnalysis {
        public final IntentResult intent;
        public final ComplexityLevel complexity;
        public final int estimatedSteps;

        public IntentAnalysis(IntentResult intent, ComplexityLevel complexity, int estimatedSteps) {
            this.intent = intent;
            this.complexity = complexity;
            this.estimatedSteps = estimatedSteps;
        }
    }

    // 意图类型常量
    public static final String INTENT_WEATHER = "WEATHER";
    public static final String INTENT_SEARCH = "SEARCH";
    public static final String INTENT_CALCULATOR = "CALCULATOR";
    public static final String INTENT_QUIZ = "QUIZ";
    public static final String INTENT_TRANSLATE = "TRANSLATE";
    public static final String INTENT_FILE = "FILE";
    public static final String INTENT_DATABASE = "DATABASE";
    public static final String INTENT_APP_OPERATION = "APP_OPERATION";
    public static final String INTENT_CHAT = "CHAT";
    public static final String INTENT_UNKNOWN = "UNKNOWN";

    public IntentRecognizer(AIService aiService) {
        this.aiService = aiService;
    }

    /**
     * 合并识别意图和复杂度（单次 LLM 调用，节省一次推理）
     * @param userMessage 用户消息
     * @param chatHistory 最近对话历史（可为 null 或空列表）
     */
    public IntentAnalysis analyzeWithComplexity(String userMessage, List<String> chatHistory) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return new IntentAnalysis(
                    new IntentResult(INTENT_CHAT, 1.0, "", null),
                    ComplexityLevel.SIMPLE, 0);
        }

        try {
            if (!LlamaHelper.isModelInitialized()) {
                AILogger.w(TAG, "Model not initialized, using keyword recognition");
                return analyzeByKeywordsCombined(userMessage);
            }

            String prompt = buildCombinedPrompt(userMessage, chatHistory);
            AILogger.i(TAG, "Combined analysis prompt length: " + prompt.length());

            String response = LlamaHelper.generate(prompt, 200, 0.2f);
            AILogger.i(TAG, "LLM response: " + (response != null
                    ? response.substring(0, Math.min(100, response.length())) : "null"));

            if (response == null || response.trim().isEmpty()) {
                AILogger.w(TAG, "LLM returned empty response, falling back to keywords");
                return analyzeByKeywordsCombined(userMessage);
            }

            return parseCombinedResponse(response, userMessage);

        } catch (Exception e) {
            AILogger.e(TAG, "Error in combined analysis: " + e.getMessage(), e);
            return analyzeByKeywordsCombined(userMessage);
        }
    }

    /**
     * 构建合并 Prompt：同时输出意图和复杂度
     */
    private String buildCombinedPrompt(String userMessage, List<String> chatHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的意图识别与复杂度评估引擎，一次分析同时输出意图和复杂度。\n\n");
        sb.append("【角色约束】\n");
        sb.append("- 严格输出指定格式，不要解释、不要多余文字\n");
        sb.append("- 意图类型必须是下面列出的常量之一\n");
        sb.append("- 复杂度基于工具调用次数判断：SIMPLE(0-1次)、MEDIUM(2-3次)、COMPLEX(4+次)\n\n");

        // 对话历史（帮助理解上下文追问）
        if (chatHistory != null && !chatHistory.isEmpty()) {
            sb.append("【最近对话历史】\n");
            int start = Math.max(0, chatHistory.size() - 6); // 最多展示最近6条
            for (int i = start; i < chatHistory.size(); i++) {
                sb.append(chatHistory.get(i)).append("\n");
            }
            sb.append("\n");
        }

        sb.append("【意图类型】\n");
        sb.append("- WEATHER: 天气查询（天气、气温、降水、空气质量、预警等）\n");
        sb.append("- SEARCH: 网络搜索（查找资料、新闻、百科等）\n");
        sb.append("- CALCULATOR: 数学计算\n");
        sb.append("- QUIZ: 题目相关\n");
        sb.append("- TRANSLATE: 翻译\n");
        sb.append("- FILE: 文件处理\n");
        sb.append("- DATABASE: 数据库操作\n");
        sb.append("- APP_OPERATION: 系统操作（打开应用、返回主页等）\n");
        sb.append("- CHAT: 普通对话\n\n");

        sb.append("【示例】\n");
        sb.append("用户: 北京今天天气\n");
        sb.append("INTENT: WEATHER\n");
        sb.append("CONFIDENCE: 0.95\n");
        sb.append("ENTITY: 北京\n");
        sb.append("LEVEL: SIMPLE\n");
        sb.append("STEPS: 1\n\n");
        sb.append("用户: 对比北京上海未来一周天气\n");
        sb.append("INTENT: WEATHER\n");
        sb.append("CONFIDENCE: 0.92\n");
        sb.append("ENTITY: 北京,上海\n");
        sb.append("LEVEL: COMPLEX\n");
        sb.append("STEPS: 4\n\n");
        sb.append("用户: 那明天呢\n");
        sb.append("INTENT: WEATHER\n");
        sb.append("CONFIDENCE: 0.85\n");
        sb.append("ENTITY: -\n");
        sb.append("LEVEL: SIMPLE\n");
        sb.append("STEPS: 1\n\n");

        sb.append("【输出格式（严格）】\n");
        sb.append("INTENT: <意图类型>\n");
        sb.append("CONFIDENCE: <0.0-1.0>\n");
        sb.append("ENTITY: <关键实体或->\n");
        sb.append("LEVEL: <SIMPLE/MEDIUM/COMPLEX>\n");
        sb.append("STEPS: <整数>\n\n");
        sb.append("【用户消息】\n");
        sb.append(userMessage).append("\n\n");
        sb.append("【输出】\n");
        return sb.toString();
    }

    /**
     * 解析合并响应
     */
    private IntentAnalysis parseCombinedResponse(String response, String originalMessage) {
        String intentType = INTENT_UNKNOWN;
        double confidence = 0.5;
        String entity = "";
        ComplexityLevel complexity = ComplexityLevel.SIMPLE;
        int steps = 0;

        try {
            Pattern intentPattern = Pattern.compile("INTENT:\\s*(\\w+)", Pattern.CASE_INSENSITIVE);
            Matcher m = intentPattern.matcher(response);
            if (m.find()) intentType = m.group(1).toUpperCase();

            Pattern confPattern = Pattern.compile("CONFIDENCE:\\s*(\\d+\\.?\\d*)", Pattern.CASE_INSENSITIVE);
            m = confPattern.matcher(response);
            if (m.find()) {
                confidence = Math.max(0.0, Math.min(1.0, Double.parseDouble(m.group(1))));
            }

            Pattern entityPattern = Pattern.compile("ENTITY:\\s*(.+)", Pattern.CASE_INSENSITIVE);
            m = entityPattern.matcher(response);
            if (m.find()) {
                entity = m.group(1).trim();
                if ("-".equals(entity)) entity = "";
            }

            Pattern levelPattern = Pattern.compile("LEVEL:\\s*(\\w+)", Pattern.CASE_INSENSITIVE);
            m = levelPattern.matcher(response);
            if (m.find()) {
                String level = m.group(1).toUpperCase();
                switch (level) {
                    case "MEDIUM": complexity = ComplexityLevel.MEDIUM; break;
                    case "COMPLEX": complexity = ComplexityLevel.COMPLEX; break;
                    default: complexity = ComplexityLevel.SIMPLE;
                }
            }

            Pattern stepsPattern = Pattern.compile("STEPS:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
            m = stepsPattern.matcher(response);
            if (m.find()) {
                steps = Integer.parseInt(m.group(1));
            }

        } catch (Exception e) {
            AILogger.w(TAG, "Error parsing combined response: " + e.getMessage());
        }

        IntentResult intent = new IntentResult(intentType, confidence, entity, originalMessage);
        return new IntentAnalysis(intent, complexity, steps);
    }

    /**
     * 关键词备用方案（合并意图+复杂度）
     */
    private IntentAnalysis analyzeByKeywordsCombined(String message) {
        IntentResult intent = recognizeByKeywords(message);
        ComplexityLevel complexity;
        if (message != null) {
            String lower = message.toLowerCase();
            if (lower.contains("对比") || lower.contains("比较") || lower.contains("分别")
                    || lower.contains("多城市") || lower.contains("规划")) {
                complexity = ComplexityLevel.COMPLEX;
            } else if (lower.contains("总结") || lower.contains("一周") || lower.contains("然后")
                    || lower.contains("之后") || lower.contains("再")) {
                complexity = ComplexityLevel.MEDIUM;
            } else {
                complexity = ComplexityLevel.SIMPLE;
            }
        } else {
            complexity = ComplexityLevel.SIMPLE;
        }
        int steps = complexity == ComplexityLevel.COMPLEX ? 4
                : complexity == ComplexityLevel.MEDIUM ? 2 : 1;
        return new IntentAnalysis(intent, complexity, steps);
    }

    /**
     * 识别用户意图
     * 使用 LLM 进行意图分类
     */
    public IntentResult recognize(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return new IntentResult(INTENT_CHAT, 1.0, "", null);
        }

        try {
            // 检查模型是否已初始化
            if (!LlamaHelper.isModelInitialized()) {
                AILogger.w(TAG, "Model not initialized, using keyword recognition");
                return recognizeByKeywords(userMessage);
            }
            
            // 构建意图识别 Prompt
            String prompt = buildIntentPrompt(userMessage);
            AILogger.i(TAG, "Intent recognition prompt length: " + prompt.length());
            
            // 调用 LLM 识别意图（generate 独占推理锁，不触碰 chat context，无需 destroy）
            String response = LlamaHelper.generate(prompt, 150, 0.2f);
            AILogger.i(TAG, "LLM response: " + (response != null ? response.substring(0, Math.min(100, response.length())) : "null"));
            
            // 解析意图
            if (response == null || response.trim().isEmpty()) {
                AILogger.w(TAG, "LLM returned empty response for intent recognition");
                return recognizeByKeywords(userMessage);
            }
            
            return parseIntentResponse(response, userMessage);
            
        } catch (Exception e) {
            AILogger.e(TAG, "Error in intent recognition: " + e.getMessage(), e);
            return recognizeByKeywords(userMessage);
        }
    }
    
    /**
     * 构建意图识别 Prompt
     * 包含角色约束、意图说明、few-shot 示例和严格输出格式
     */
    private String buildIntentPrompt(String userMessage) {
        return "你是一个专业的意图识别引擎，负责分析用户输入并分类到唯一意图类型。\n\n" +
               "【角色约束】\n" +
               "- 只输出指定格式，不要解释、不要多余文字\n" +
               "- 意图类型必须是下面列出的常量之一，大小写敏感\n" +
               "- 置信度反映你对分类的把握，0.9 以上表示非常确定\n" +
               "- 实体是用户消息中的关键信息（城市名、计算表达式、文件路径、搜索词等），没有则填 -\n\n" +
               "【意图类型说明】\n" +
               "- WEATHER: 天气查询（天气、气温、降水、空气质量、生活指数、预警等）\n" +
               "- SEARCH: 网络搜索（查找资料、新闻、百科、实时信息等）\n" +
               "- CALCULATOR: 数学计算（算式、换算、统计运算等）\n" +
               "- QUIZ: 题目相关（出题、练习、测试、解题等）\n" +
               "- TRANSLATE: 翻译（中英互译、多语言翻译等）\n" +
               "- FILE: 文件处理（读取、解析、写入文件等）\n" +
               "- DATABASE: 数据库操作（查询记录、统计数据等）\n" +
               "- APP_OPERATION: 系统操作（打开应用、返回主页、切换设置等设备操作）\n" +
               "- CHAT: 普通对话（闲聊、问候、求助、情感交流等无法归入以上类别的输入）\n\n" +
               "【示例】\n" +
               "用户: 北京今天天气怎么样\n" +
               "INTENT: WEATHER\n" +
               "CONFIDENCE: 0.95\n" +
               "ENTITY: 北京\n\n" +
               "用户: 3.14乘以2.5等于多少\n" +
               "INTENT: CALCULATOR\n" +
               "CONFIDENCE: 0.98\n" +
               "ENTITY: 3.14*2.5\n\n" +
               "用户: 帮我打开设置界面\n" +
               "INTENT: APP_OPERATION\n" +
               "CONFIDENCE: 0.92\n" +
               "ENTITY: 打开设置\n\n" +
               "用户: 你好，能聊聊吗\n" +
               "INTENT: CHAT\n" +
               "CONFIDENCE: 0.90\n" +
               "ENTITY: -\n\n" +
               "【输出格式（严格，不要偏差）】\n" +
               "INTENT: <意图类型>\n" +
               "CONFIDENCE: <0.0-1.0>\n" +
               "ENTITY: <关键实体或->\n\n" +
               "【用户消息】\n" +
               userMessage + "\n\n" +
               "【输出】\n";
    }
    
    /**
     * 解析意图识别响应
     */
    private IntentResult parseIntentResponse(String response, String originalMessage) {
        String intentType = INTENT_UNKNOWN;
        double confidence = 0.5;
        String entity = "";
        
        try {
            // 解析 INTENT
            Pattern intentPattern = Pattern.compile("INTENT:\\s*(\\w+)", Pattern.CASE_INSENSITIVE);
            Matcher intentMatcher = intentPattern.matcher(response);
            if (intentMatcher.find()) {
                intentType = intentMatcher.group(1).toUpperCase();
            }
            
            // 解析 CONFIDENCE
            Pattern confPattern = Pattern.compile("CONFIDENCE:\\s*(\\d+\\.?\\d*)", Pattern.CASE_INSENSITIVE);
            Matcher confMatcher = confPattern.matcher(response);
            if (confMatcher.find()) {
                confidence = Double.parseDouble(confMatcher.group(1));
                confidence = Math.max(0.0, Math.min(1.0, confidence));
            }
            
            // 解析 ENTITY
            Pattern entityPattern = Pattern.compile("ENTITY:\\s*(.+)", Pattern.CASE_INSENSITIVE);
            Matcher entityMatcher = entityPattern.matcher(response);
            if (entityMatcher.find()) {
                entity = entityMatcher.group(1).trim();
            }
            
        } catch (Exception e) {
            AILogger.w(TAG, "Error parsing intent response: " + e.getMessage());
        }
        
        return new IntentResult(intentType, confidence, entity, originalMessage);
    }
    
    /**
     * 基于关键词的意图识别（备用方案）
     */
    private IntentResult recognizeByKeywords(String message) {
        String lower = message.toLowerCase();

        // APP_OPERATION 关键词（优先匹配，避免被 SEARCH 截胡）
        if (containsAny(lower, "打开", "返回主页", "回到桌面", "退出应用", "打开设置",
                "open ", "go home", "go back", "launch")) {
            return new IntentResult(INTENT_APP_OPERATION, 0.7, message, message);
        }

        // 天气关键词
        if (containsAny(lower, "天气", "气温", "下雨", "温度", "weather", "forecast",
                "空气质量", "降水", "预警")) {
            return new IntentResult(INTENT_WEATHER, 0.7, extractCity(message), message);
        }

        // 计算关键词（优先于搜索，避免"计算3+5"被识别为搜索）
        if (containsAny(lower, "计算", "等于", "多少", "calculate", "compute", "乘", "除", "加", "减")
                || lower.matches(".*[\\d\\s]+[+\\-*/×÷][\\d\\s]+.*")) {
            return new IntentResult(INTENT_CALCULATOR, 0.7, message, message);
        }

        // 翻译关键词
        if (containsAny(lower, "翻译", "translate", "译成")) {
            return new IntentResult(INTENT_TRANSLATE, 0.7, message, message);
        }

        // 题目关键词
        if (containsAny(lower, "题目", "练习", "出题", "quiz", "exercise", "考试")) {
            return new IntentResult(INTENT_QUIZ, 0.7, message, message);
        }

        // 文件关键词
        if (containsAny(lower, "文件", "读取", "解析文件", "file", "read", "parse")) {
            return new IntentResult(INTENT_FILE, 0.7, message, message);
        }

        // 数据库关键词
        if (containsAny(lower, "记录", "统计数据", "database", "查询记录")) {
            return new IntentResult(INTENT_DATABASE, 0.7, message, message);
        }

        // 搜索关键词（放后面，避免误匹配）
        if (containsAny(lower, "搜索", "查找资料", "查一下", "search", "find", "look up")) {
            return new IntentResult(INTENT_SEARCH, 0.7, message, message);
        }

        // 默认为普通对话
        return new IntentResult(INTENT_CHAT, 0.6, "", message);
    }
    
    /**
     * 检查是否包含任意关键词
     */
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 提取城市名称
     */
    private String extractCity(String message) {
        // 简单的城市提取逻辑
        String[] cities = {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京"};
        for (String city : cities) {
            if (message.contains(city)) {
                return city;
            }
        }
        return "";
    }
}
