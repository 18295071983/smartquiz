package com.oilquiz.app.ai.feature;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.model.Question;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

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
    private static final String TAG = "QuestionAnalyzer";
    private final AIService aiService;
    
    public QuestionAnalyzer(Context context) {
        this.aiService = AIService.getInstance(context);
    }
    
    public String analyzeQuestion(Question question) {
        if (!aiService.isInitialized()) {
            boolean initialized = aiService.initializeSafe();
            if (!initialized) {
                Log.e(TAG, "Failed to initialize AI service");
                return "Error: AI service not initialized";
            }
        }
        
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
        
        try {
            Future<String> future = aiService.generateAsync(prompt, 800);
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Error analyzing question: " + e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }
    
    public String getLearningSuggestions(Question question) {
        if (!aiService.isInitialized()) {
            boolean initialized = aiService.initializeSafe();
            if (!initialized) {
                Log.e(TAG, "Failed to initialize AI service");
                return "Error: AI service not initialized";
            }
        }
        
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
        
        try {
            Future<String> future = aiService.generateAsync(prompt, 600);
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Error getting learning suggestions: " + e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }
}