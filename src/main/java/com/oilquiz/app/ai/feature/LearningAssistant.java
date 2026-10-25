package com.oilquiz.app.ai.feature;

import android.content.Context;

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
    /**
     * 统一出词入口（2026-10-25）：以前每个方法开头都 {@code initializeSafe()} 直接把本地 GGUF
     * 拉进内存；现在"有激活在线模型就先走在线，不为跑功能偷偷加载本地权重"。
     * 见 {@link FeatureTextCompleter} 的口径说明。
     */
    private final FeatureTextCompleter completer;

    public LearningAssistant(Context context) {
        this.completer = new FeatureTextCompleter(context);
    }
    
    public String getPersonalizedStudyPlan(String subject, int difficulty, int studyHoursPerWeek) {
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
        
        String out = completer.complete(prompt, 1000);
        return out != null ? out : AI_UNAVAILABLE;
    }
    
    public String explainConcept(String concept, int difficultyLevel) {
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
        
        String out = completer.complete(prompt, 800);
        return out != null ? out : AI_UNAVAILABLE;
    }
    
    public String getExamTips(String examType, int daysUntilExam) {
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
        
        String out = completer.complete(prompt, 800);
        return out != null ? out : AI_UNAVAILABLE;
    }

    /** 调用方按 {@code startsWith("Error:")} 判失败（见 ToolResultInterpreter 等），前缀必须保留。 */
    private static final String AI_UNAVAILABLE = FeatureTextCompleter.AI_UNAVAILABLE;

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
