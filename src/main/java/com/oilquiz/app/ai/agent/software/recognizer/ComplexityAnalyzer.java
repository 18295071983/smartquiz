package com.oilquiz.app.ai.agent.software.recognizer;

import com.oilquiz.app.ai.agent.software.model.ComplexityLevel;
import com.oilquiz.app.ai.agent.software.model.IntentResult;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.util.AILogger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ComplexityAnalyzer - 复杂度分析模块
 * 
 * 使用 LLM 评估任务复杂度
 * 硬件层调用: LLM.generate()
 */
public class ComplexityAnalyzer {
    
    private static final String TAG = "ComplexityAnalyzer";
    private final com.oilquiz.app.ai.service.AIService aiService;
    
    public ComplexityAnalyzer(com.oilquiz.app.ai.service.AIService aiService) {
        this.aiService = aiService;
    }
    
    /**
     * 分析任务复杂度
     * 使用 LLM 评估任务难度
     */
    public ComplexityLevel analyze(String userMessage, IntentResult intent) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return ComplexityLevel.SIMPLE;
        }
        
        try {
            // 检查模型是否已初始化
            if (!LlamaHelper.isModelInitialized()) {
                AILogger.w(TAG, "Model not initialized, using keyword analysis");
                return analyzeByKeywords(userMessage, intent);
            }
            
            // 构建复杂度分析 Prompt
            String prompt = buildComplexityPrompt(userMessage, intent);
            
            // 如果聊天上下文活跃，先关闭再使用 generate
            boolean contextWasActive = LlamaHelper.isChatContextActive();
            if (contextWasActive) {
                AILogger.i(TAG, "Chat context active, destroying before complexity analysis");
                try {
                    LlamaHelper.chatDestroy();
                    Thread.sleep(100);
                } catch (Exception e) {
                    AILogger.w(TAG, "Error destroying chat context: " + e.getMessage());
                }
            }
            
            // 调用 LLM 分析复杂度
            String response = LlamaHelper.generate(prompt, 100, 0.2f);
            
            if (response == null || response.trim().isEmpty()) {
                AILogger.w(TAG, "LLM returned empty response for complexity analysis");
                return analyzeByKeywords(userMessage, intent);
            }
            
            return parseComplexityResponse(response);
            
        } catch (Exception e) {
            AILogger.e(TAG, "Error in complexity analysis: " + e.getMessage(), e);
            return analyzeByKeywords(userMessage, intent);
        }
    }
    
    /**
     * 构建复杂度分析 Prompt
     */
    private String buildComplexityPrompt(String userMessage, IntentResult intent) {
        return "你是一个任务复杂度分析助手。\n\n" +
               "【用户消息】\n" + userMessage + "\n\n" +
               "【识别意图】\n" + intent.type + "\n\n" +
               "【任务复杂度判断标准】\n" +
               "- SIMPLE: 单步任务，可直接回答或一次工具调用完成\n" +
               "  例如: 问候、简单问题、单次查询\n\n" +
               "- MEDIUM: 需要1-2个工具调用或简单推理\n" +
               "  例如: 天气查询、信息搜索、简单计算\n\n" +
               "- COMPLEX: 需要多个工具调用、复杂推理或多步骤任务\n" +
               "  例如: 多条件查询、数据分析、任务规划\n\n" +
               "【输出格式】\n" +
               "LEVEL: SIMPLE/MEDIUM/COMPLEX\n" +
               "REASON: 分析理由\n" +
               "STEPS: 预估步骤数\n\n" +
               "【输出】\n";
    }
    
    /**
     * 解析复杂度分析响应
     */
    private ComplexityLevel parseComplexityResponse(String response) {
        try {
            Pattern levelPattern = Pattern.compile("LEVEL:\\s*(\\w+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = levelPattern.matcher(response);
            
            if (matcher.find()) {
                String level = matcher.group(1).toUpperCase();
                switch (level) {
                    case "SIMPLE":
                        return ComplexityLevel.SIMPLE;
                    case "MEDIUM":
                        return ComplexityLevel.MEDIUM;
                    case "COMPLEX":
                        return ComplexityLevel.COMPLEX;
                    default:
                        return ComplexityLevel.SIMPLE;
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Error parsing complexity response: " + e.getMessage());
        }
        
        return ComplexityLevel.SIMPLE;
    }
    
    /**
     * 基于关键词的复杂度分析（备用方案）
     */
    private ComplexityLevel analyzeByKeywords(String message, IntentResult intent) {
        String lower = message.toLowerCase();
        
        // 复杂任务关键词
        if (containsAny(lower, "分析", "比较", "总结", "规划", "设计", "优化")) {
            return ComplexityLevel.COMPLEX;
        }
        
        // 中等任务关键词
        if (containsAny(lower, "搜索", "查询", "计算", "翻译", "读取")) {
            return ComplexityLevel.MEDIUM;
        }
        
        // 简单任务关键词
        if (containsAny(lower, "你好", "谢谢", "是什么", "几点")) {
            return ComplexityLevel.SIMPLE;
        }
        
        // 根据意图类型判断
        if (intent != null) {
            switch (intent.type) {
                case "WEATHER":
                case "SEARCH":
                case "CALCULATOR":
                    return ComplexityLevel.MEDIUM;
                case "CHAT":
                    return ComplexityLevel.SIMPLE;
                default:
                    return ComplexityLevel.SIMPLE;
            }
        }
        
        return ComplexityLevel.SIMPLE;
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
}
