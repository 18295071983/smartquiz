package com.oilquiz.app.ai.agent.software.recognizer;

import com.oilquiz.app.ai.agent.software.model.IntentResult;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

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
    
    // 意图类型常量
    public static final String INTENT_WEATHER = "WEATHER";
    public static final String INTENT_SEARCH = "SEARCH";
    public static final String INTENT_CALCULATOR = "CALCULATOR";
    public static final String INTENT_QUIZ = "QUIZ";
    public static final String INTENT_TRANSLATE = "TRANSLATE";
    public static final String INTENT_FILE = "FILE";
    public static final String INTENT_DATABASE = "DATABASE";
    public static final String INTENT_CHAT = "CHAT";
    public static final String INTENT_UNKNOWN = "UNKNOWN";
    
    public IntentRecognizer(AIService aiService) {
        this.aiService = aiService;
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
            
            // 如果聊天上下文活跃，先关闭再使用 generate
            boolean contextWasActive = LlamaHelper.isChatContextActive();
            if (contextWasActive) {
                AILogger.i(TAG, "Chat context active, destroying before intent recognition");
                try {
                    LlamaHelper.chatDestroy();
                    Thread.sleep(100); // 等待释放
                } catch (Exception e) {
                    AILogger.w(TAG, "Error destroying chat context: " + e.getMessage());
                }
            }
            
            // 调用 LLM 识别意图
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
     */
    private String buildIntentPrompt(String userMessage) {
        return "你是一个智能意图识别助手。请分析用户的问题，判断其意图类型。\n\n" +
               "【意图类型说明】\n" +
               "- WEATHER: 天气查询（查询天气、气温、是否下雨等）\n" +
               "- SEARCH: 搜索查询（查找资料、新闻、信息等）\n" +
               "- CALCULATOR: 数学计算（计算、换算等）\n" +
               "- QUIZ: 题目相关（出题、练习、测试等）\n" +
               "- TRANSLATE: 翻译（翻译文字等）\n" +
               "- FILE: 文件处理（读取、解析文件等）\n" +
               "- DATABASE: 数据库操作（查询记录、统计等）\n" +
               "- CHAT: 普通对话（闲聊、问候等）\n\n" +
               "【输出格式】\n" +
               "INTENT: 意图类型\n" +
               "CONFIDENCE: 0.0-1.0\n" +
               "ENTITY: 关键实体（可选）\n\n" +
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
        
        // 天气关键词
        if (containsAny(lower, "天气", "气温", "下雨", "温度", "weather", "forecast")) {
            return new IntentResult(INTENT_WEATHER, 0.7, extractCity(message), message);
        }
        
        // 搜索关键词
        if (containsAny(lower, "搜索", "查找", "查询", "search", "find", "look")) {
            return new IntentResult(INTENT_SEARCH, 0.7, message, message);
        }
        
        // 计算关键词
        if (containsAny(lower, "计算", "等于", "多少", "calculate", "compute")) {
            return new IntentResult(INTENT_CALCULATOR, 0.7, message, message);
        }
        
        // 题目关键词
        if (containsAny(lower, "题目", "练习", "测试", "quiz", "exercise", "test")) {
            return new IntentResult(INTENT_QUIZ, 0.7, message, message);
        }
        
        // 翻译关键词
        if (containsAny(lower, "翻译", "translate", "转换")) {
            return new IntentResult(INTENT_TRANSLATE, 0.7, message, message);
        }
        
        // 文件关键词
        if (containsAny(lower, "文件", "读取", "解析", "file", "read", "parse")) {
            return new IntentResult(INTENT_FILE, 0.7, message, message);
        }
        
        // 数据库关键词
        if (containsAny(lower, "记录", "统计", "数据", "database", "record", "statistics")) {
            return new IntentResult(INTENT_DATABASE, 0.7, message, message);
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
