package com.oilquiz.app.ai.feature;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.service.AIService;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 学习助手 - AI驱动的个性化学习规划
 * 
 * 功能：
 * - 根据科目、难度、学习时间生成个性化学习计划
 * - 包含每周学习安排、关键知识点、推荐资源、进度跟踪方法
 * 
 * 使用方式：
 * LearningAssistant assistant = new LearningAssistant(context);
 * String plan = assistant.getPersonalizedStudyPlan("数学", 2, 10);
 * 
 * @author AI Team
 * @since 2024
 */
public class LearningAssistant {
    private static final String TAG = "LearningAssistant";
    private final AIService aiService;
    
    public LearningAssistant(Context context) {
        this.aiService = AIService.getInstance(context);
    }
    
    public String getPersonalizedStudyPlan(String subject, int difficulty, int studyHoursPerWeek) {
        if (!aiService.isInitialized()) {
            boolean initialized = aiService.initializeSafe();
            if (!initialized) {
                Log.e(TAG, "Failed to initialize AI service");
                return "Error: AI service not initialized";
            }
        }
        
        String prompt = String.format(
            "Create a personalized study plan for learning %s at %s difficulty level.\n" +
            "Study time available: %d hours per week.\n" +
            "Please include:\n" +
            "1. Weekly study schedule\n" +
            "2. Key topics to focus on\n" +
            "3. Recommended learning resources\n" +
            "4. Progress tracking methods\n" +
            "5. Tips for effective learning",
            subject,
            getDifficultyString(difficulty),
            studyHoursPerWeek
        );
        
        try {
            Future<String> future = aiService.generateAsync(prompt, 1000);
            return future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Log.e(TAG, "Error generating study plan: " + e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }
    
    public String explainConcept(String concept, int difficultyLevel) {
        if (!aiService.isInitialized()) {
            boolean initialized = aiService.initializeSafe();
            if (!initialized) {
                Log.e(TAG, "Failed to initialize AI service");
                return "Error: AI service not initialized";
            }
        }
        
        String prompt = String.format(
            "Explain the concept of '%s' in a way that is appropriate for a %s level student.\n" +
            "Please include:\n" +
            "1. A clear definition\n" +
            "2. Key points and principles\n" +
            "3. Real-world examples\n" +
            "4. Common misconceptions\n" +
            "5. How this concept relates to other topics",
            concept,
            getDifficultyString(difficultyLevel)
        );
        
        try {
            Future<String> future = aiService.generateAsync(prompt, 800);
            return future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Log.e(TAG, "Error explaining concept: " + e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }
    
    public String getExamTips(String examType, int daysUntilExam) {
        if (!aiService.isInitialized()) {
            boolean initialized = aiService.initializeSafe();
            if (!initialized) {
                Log.e(TAG, "Failed to initialize AI service");
                return "Error: AI service not initialized";
            }
        }
        
        String prompt = String.format(
            "Provide exam preparation tips for a %s exam with %d days remaining.\n" +
            "Please include:\n" +
            "1. Daily study schedule\n" +
            "2. Priority topics to focus on\n" +
            "3. Effective study techniques\n" +
            "4. Tips for memorization\n" +
            "5. Test-taking strategies\n" +
            "6. Relaxation and stress management techniques",
            examType,
            daysUntilExam
        );
        
        try {
            Future<String> future = aiService.generateAsync(prompt, 800);
            return future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Log.e(TAG, "Error getting exam tips: " + e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }
    
    private String getDifficultyString(int difficulty) {
        switch (difficulty) {
            case 1:
                return "beginner";
            case 2:
                return "intermediate";
            case 3:
                return "advanced";
            default:
                return "intermediate";
        }
    }
}