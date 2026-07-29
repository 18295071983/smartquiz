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
            
            // 调用 LLM 分析复杂度（generate 独占推理锁，不触碰 chat context，无需 destroy）
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
     * 包含角色约束、判断标准、few-shot 示例和严格输出格式
     */
    private String buildComplexityPrompt(String userMessage, IntentResult intent) {
        return "你是一个任务复杂度评估引擎，负责评估完成用户请求所需的步骤数和难度。\n\n" +
               "【角色约束】\n" +
               "- 只输出指定格式，不要解释、不要多余文字\n" +
               "- LEVEL 必须是 SIMPLE / MEDIUM / COMPLEX 之一\n" +
               "- STEPS 是预估的工具调用次数（0 表示直接回答，无需工具）\n\n" +
               "【判断标准】\n" +
               "- SIMPLE (STEPS 0-1): 单步任务，可直接回答或仅需一次工具调用\n" +
               "  例: 问候、单次天气查询、简单计算、翻译一句话\n" +
               "- MEDIUM (STEPS 2-3): 需要多次工具调用或简单推理\n" +
               "  例: 多日天气预报、搜索后总结、连续计算、多段翻译\n" +
               "- COMPLEX (STEPS 4+): 需要多次工具调用、复杂推理或多步骤协作\n" +
               "  例: 多城市天气对比、数据分析报告、多源信息整合、任务规划\n\n" +
               "【示例】\n" +
               "用户: 你好\n" +
               "意图: CHAT\n" +
               "LEVEL: SIMPLE\n" +
               "REASON: 闲聊无需工具\n" +
               "STEPS: 0\n\n" +
               "用户: 北京今天天气\n" +
               "意图: WEATHER\n" +
               "LEVEL: SIMPLE\n" +
               "REASON: 单次天气查询\n" +
               "STEPS: 1\n\n" +
               "用户: 查一下苹果公司最新财报并总结要点\n" +
               "意图: SEARCH\n" +
               "LEVEL: MEDIUM\n" +
               "REASON: 需搜索后整理总结\n" +
               "STEPS: 2\n\n" +
               "用户: 对比北京上海广州三地未来一周天气\n" +
               "意图: WEATHER\n" +
               "LEVEL: COMPLEX\n" +
               "REASON: 多城市多日数据对比\n" +
               "STEPS: 4\n\n" +
               "【输出格式（严格）】\n" +
               "LEVEL: <SIMPLE/MEDIUM/COMPLEX>\n" +
               "REASON: <简短理由>\n" +
               "STEPS: <整数>\n\n" +
               "【用户消息】\n" + userMessage + "\n" +
               "【识别意图】\n" + intent.type + "\n\n" +
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
     * 与 prompt 中的判断标准保持一致
     */
    private ComplexityLevel analyzeByKeywords(String message, IntentResult intent) {
        String lower = message.toLowerCase();

        // 复杂任务关键词：多步骤、对比、规划
        if (containsAny(lower, "对比", "比较", "分析报告", "规划", "设计方案", "多城市", "三地", "分别")) {
            return ComplexityLevel.COMPLEX;
        }

        // 中等任务关键词：需搜索后总结、多日预报、连续计算
        if (containsAny(lower, "总结", "一周", "未来几天", "之后", "再", "然后")) {
            return ComplexityLevel.MEDIUM;
        }

        // 简单任务关键词：问候、单次查询
        if (containsAny(lower, "你好", "谢谢", "是什么", "几点", "今天天气", "现在")) {
            return ComplexityLevel.SIMPLE;
        }

        // 根据意图类型判断（与 prompt 标准对齐）
        if (intent != null) {
            switch (intent.type) {
                case "CHAT":
                case "APP_OPERATION":
                    return ComplexityLevel.SIMPLE;
                case "WEATHER":
                case "SEARCH":
                case "CALCULATOR":
                case "TRANSLATE":
                case "QUIZ":
                case "FILE":
                case "DATABASE":
                    // 单次工具调用默认 SIMPLE
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
