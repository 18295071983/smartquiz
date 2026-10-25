package com.oilquiz.app.ai.feature;

import android.content.Context;

import com.oilquiz.app.model.Question;

/**
 * 题目分析器 - AI驱动的题目解析和解答
 * 
 * 功能：
 * - 分析题目并提供详细解答
 * - 正确答案及详细解释
 * - 错误选项分析
 * - 涉及的知识点说明
 * 
 * 使用方式：
 * QuestionAnalyzer analyzer = new QuestionAnalyzer(context);
 * String analysis = analyzer.analyzeQuestion(question);
 * 
 * @author AI Team
 * @since 2024
 */
public class QuestionAnalyzer {
    /** 统一出词入口（2026-10-25，见 {@link FeatureTextCompleter}）：不再一进来就 initializeSafe()。 */
    private final FeatureTextCompleter completer;

    /** 调用方按 {@code startsWith("Error:")} 判失败（见 ToolResultInterpreter 等），前缀必须保留。 */
    private static final String AI_UNAVAILABLE = FeatureTextCompleter.AI_UNAVAILABLE;
    
    public QuestionAnalyzer(Context context) {
        this.completer = new FeatureTextCompleter(context);
    }
    
    public String analyzeQuestion(Question question) {
        String prompt = String.format(
            "Analyze the following question and provide a detailed solution:\n" +
            "Question: %s\n" +
            "Options:\n" +
            "A. %s\n" +
            "B. %s\n" +
            "C. %s\n" +
            "D. %s\n" +
            "Please provide:\n" +
            "1. The correct answer\n" +
            "2. A detailed explanation of why this answer is correct\n" +
            "3. Explanations of why the other options are incorrect\n" +
            "4. Key concepts tested by this question",
            question.getQuestionText(),
            question.getOptionA(),
            question.getOptionB(),
            question.getOptionC(),
            question.getOptionD()
        );
        
        String out = completer.complete(prompt, 800);
        return out != null ? out : AI_UNAVAILABLE;
    }
    
    public String getLearningSuggestions(Question question) {
        String prompt = String.format(
            "Based on the following question, provide learning suggestions:\n" +
            "Question: %s\n" +
            "Options:\n" +
            "A. %s\n" +
            "B. %s\n" +
            "C. %s\n" +
            "D. %s\n" +
            "Correct answer: %s\n" +
            "Please provide:\n" +
            "1. Key concepts that need to be mastered\n" +
            "2. Related topics to study\n" +
            "3. Study resources or methods\n" +
            "4. Common mistakes to avoid",
            question.getQuestionText(),
            question.getOptionA(),
            question.getOptionB(),
            question.getOptionC(),
            question.getOptionD(),
            question.getCorrectAnswer()
        );
        
        String out = completer.complete(prompt, 600);
        return out != null ? out : AI_UNAVAILABLE;
    }
}