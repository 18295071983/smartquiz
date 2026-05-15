package com.oilquiz.app.ai.feature;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.model.Question;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 题目生成器 - AI驱动的题目批量生成
 * 
 * 功能：
 * - 根据主题生成指定数量和难度的题目
 * - 生成包含选项、正确答案、解析的完整题目
 * - 支持多种题型和难度级别
 * 
 * 使用方式：
 * QuestionGenerator generator = new QuestionGenerator(context);
 * List<Question> questions = generator.generateQuestions("数学", 10, 2);
 * 
 * @author AI Team
 * @since 2024
 */
public class QuestionGenerator {
    private static final String TAG = "QuestionGenerator";
    private final AIService aiService;
    
    public QuestionGenerator(Context context) {
        this.aiService = AIService.getInstance(context);
    }
    
    public List<Question> generateQuestions(String topic, int count, int difficulty) {
        List<Question> questions = new ArrayList<>();
        
        if (!aiService.isInitialized()) {
            boolean initialized = aiService.initializeSafe();
            if (!initialized) {
                Log.e(TAG, "Failed to initialize AI service");
                return questions;
            }
        }
        
        String prompt = String.format(
            "Generate %d %s difficulty questions about %s. Each question should include:\n" +
            "- Question text\n" +
            "- Multiple choice options (4 options)\n" +
            "- Correct answer\n" +
            "- Explanation\n" +
            "Format each question as:\n" +
            "QUESTION: [question text]\n" +
            "OPTIONS: [option A], [option B], [option C], [option D]\n" +
            "ANSWER: [correct option]\n" +
            "EXPLANATION: [explanation]\n",
            count, getDifficultyString(difficulty), topic
        );
        
        try {
            Future<String> future = aiService.generateAsync(prompt, 1000);
            String response = future.get(30, TimeUnit.SECONDS);
            questions = parseGeneratedQuestions(response);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Log.e(TAG, "Error generating questions: " + e.getMessage(), e);
        }
        
        return questions;
    }
    
    private String getDifficultyString(int difficulty) {
        switch (difficulty) {
            case 1:
                return "easy";
            case 2:
                return "medium";
            case 3:
                return "hard";
            default:
                return "medium";
        }
    }
    
    private List<Question> parseGeneratedQuestions(String response) {
        List<Question> questions = new ArrayList<>();
        
        String[] questionBlocks = response.split("QUESTION:");
        for (int i = 1; i < questionBlocks.length; i++) {
            String block = questionBlocks[i];
            try {
                Question question = new Question();
                
                // 解析题目文本
                String[] lines = block.split("\n");
                StringBuilder questionText = new StringBuilder();
                int lineIndex = 0;
                
                while (lineIndex < lines.length && !lines[lineIndex].trim().startsWith("OPTIONS:")) {
                    questionText.append(lines[lineIndex].trim()).append(" ");
                    lineIndex++;
                }
                question.setQuestionText(questionText.toString().trim());
                
                // 解析选项
                if (lineIndex < lines.length && lines[lineIndex].trim().startsWith("OPTIONS:")) {
                    String optionsLine = lines[lineIndex].trim().substring(8);
                    String[] options = optionsLine.split(",");
                    if (options.length >= 4) {
                        question.setOptionA(options[0].trim());
                        question.setOptionB(options[1].trim());
                        question.setOptionC(options[2].trim());
                        question.setOptionD(options[3].trim());
                    }
                    lineIndex++;
                }
                
                // 解析答案
                if (lineIndex < lines.length && lines[lineIndex].trim().startsWith("ANSWER:")) {
                    String answer = lines[lineIndex].trim().substring(7).trim();
                    question.setCorrectAnswer(answer);
                    lineIndex++;
                }
                
                // 解析解释
                if (lineIndex < lines.length && lines[lineIndex].trim().startsWith("EXPLANATION:")) {
                    StringBuilder explanation = new StringBuilder();
                    while (lineIndex < lines.length) {
                        explanation.append(lines[lineIndex].trim().replaceFirst("EXPLANATION:", "")).append(" ");
                        lineIndex++;
                    }
                    question.setExplanation(explanation.toString().trim());
                }
                
                question.setQuestionType("multiple_choice");
                question.setDifficulty(2); // 默认中等难度
                questions.add(question);
                
            } catch (Exception e) {
                Log.e(TAG, "Error parsing question: " + e.getMessage());
            }
        }
        
        return questions;
    }
}