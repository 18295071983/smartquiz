package com.oilquiz.app.ai.agent.software.thinking;

import com.oilquiz.app.ai.agent.software.thinking.model.*;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QuestionGenerator {
    private static final String TAG = "QuestionGenerator";
    private final AIService aiService;

    public static class GeneratedQuestion {
        private String question;
        private String questionType;
        private String reason;

        public GeneratedQuestion(String question, String questionType, String reason) {
            this.question = question;
            this.questionType = questionType;
            this.reason = reason;
        }

        public String getQuestion() { return question; }
        public String getQuestionType() { return questionType; }
        public String getReason() { return reason; }
    }

    public QuestionGenerator(AIService aiService) {
        this.aiService = aiService;
    }

    public GeneratedQuestion generate(ThinkingSession session, ThinkingAnalyzer.AnalysisResult analysis) {
        if (session == null || analysis == null) {
            return new GeneratedQuestion("你想聊些什么？", "CLARIFICATION", "开启思考对话");
        }

        try {
            if (!LlamaHelper.isModelInitialized()) {
                return generateByRules(session, analysis);
            }

            String prompt = buildQuestionPrompt(session, analysis);
            String response = LlamaHelper.generate(prompt, 200, 0.7f);

            if (response == null || response.trim().isEmpty()) {
                return generateByRules(session, analysis);
            }

            return parseQuestionResponse(response);

        } catch (Exception e) {
            AILogger.e(TAG, "Error generating question: " + e.getMessage(), e);
            return generateByRules(session, analysis);
        }
    }

    private String buildQuestionPrompt(ThinkingSession session, ThinkingAnalyzer.AnalysisResult analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个思考引导引擎，根据用户的思考状态生成引导性问题。\n\n");
        sb.append("【问题类型说明】\n");
        sb.append("- CLARIFICATION: 澄清型问题（问题模糊时使用）\n");
        sb.append("- EXPANSION: 拓展型问题（引导深入思考）\n");
        sb.append("- REFLECTION: 反思型问题（检验假设）\n");
        sb.append("- CHALLENGE: 挑战型问题（打破思维定式）\n");
        sb.append("- SUGGESTION: 给出思考建议\n\n");

        sb.append("【规则】\n");
        sb.append("1. 一次只问一个问题\n");
        sb.append("2. 问题要具体，不要太宽泛\n");
        sb.append("3. 问题要引导思考，而非测试知识\n");
        sb.append("4. 根据思考深度调整问题类型：深度低用CLARIFICATION/EXPANSION，深度高用REFLECTION/CHALLENGE\n\n");

        sb.append("【分析结果】\n");
        sb.append("清晰度: ").append(analysis.clarity).append("/5\n");
        sb.append("深度: ").append(analysis.depth).append("/5\n");
        sb.append("情绪: ").append(analysis.emotion).append("\n");
        sb.append("建议: ").append(analysis.suggestion).append("\n");
        sb.append("风格: ").append(analysis.thinkingStyle).append("\n\n");

        if (session.getContext() != null) {
            ThinkingContext ctx = session.getContext();
            if (ctx.getClarifiedQuestion() != null) {
                sb.append("【已澄清的问题】\n").append(ctx.getClarifiedQuestion()).append("\n\n");
            }
            if (!ctx.getKeyPoints().isEmpty()) {
                sb.append("【已知关键点】\n");
                for (String point : ctx.getKeyPoints()) {
                    sb.append("- ").append(point).append("\n");
                }
                sb.append("\n");
            }
            if (!ctx.getBlindSpots().isEmpty()) {
                sb.append("【待思考方面】\n");
                for (String spot : ctx.getBlindSpots()) {
                    sb.append("- ").append(spot).append("\n");
                }
                sb.append("\n");
            }
        }

        sb.append("【输出格式（严格）】\n");
        sb.append("QUESTION_TYPE: <类型>\n");
        sb.append("QUESTION: <问题内容>\n");
        sb.append("REASON: <为什么问这个问题>\n\n");
        sb.append("【输出】\n");

        return sb.toString();
    }

    private GeneratedQuestion parseQuestionResponse(String response) {
        String question = "";
        String questionType = "EXPANSION";
        String reason = "";

        try {
            Pattern typePattern = Pattern.compile("QUESTION_TYPE:\\s*(\\w+)");
            Matcher m = typePattern.matcher(response);
            if (m.find()) questionType = m.group(1).toUpperCase();

            Pattern questionPattern = Pattern.compile("QUESTION:\\s*(.+?)(?=\\nREASON|$)", Pattern.DOTALL);
            m = questionPattern.matcher(response);
            if (m.find()) question = m.group(1).trim();

            Pattern reasonPattern = Pattern.compile("REASON:\\s*(.+)", Pattern.DOTALL);
            m = reasonPattern.matcher(response);
            if (m.find()) reason = m.group(1).trim();

        } catch (Exception e) {
            AILogger.w(TAG, "Error parsing question response: " + e.getMessage());
        }

        if (question.isEmpty()) {
            question = "你能具体说说你的想法吗？";
        }

        return new GeneratedQuestion(question, questionType, reason);
    }

    private GeneratedQuestion generateByRules(ThinkingSession session, ThinkingAnalyzer.AnalysisResult analysis) {
        int clarity = analysis.clarity;
        int depth = analysis.depth;

        if (clarity <= 2) {
            return new GeneratedQuestion("你能具体说说你想思考的是什么吗？", "CLARIFICATION",
                    "帮助澄清问题");
        }

        if (depth <= 1) {
            String lastMsg = session.getLastUserMessage();
            if (lastMsg != null && lastMsg.length() < 10) {
                return new GeneratedQuestion("能多说说你的想法吗？比如具体的情况是什么？", "EXPANSION",
                        "引导展开思考");
            }
            return new GeneratedQuestion("关于这个问题，你已经考虑了哪些方面？", "EXPANSION",
                    "引导梳理已有思考");
        }

        if (depth >= 3) {
            return new GeneratedQuestion("有没有什么是你还没考虑到的角度或可能性？", "REFLECTION",
                    "引导反思盲点");
        }

        return new GeneratedQuestion("你觉得这个问题的核心是什么？", "EXPANSION",
                "帮助聚焦思考");
    }
}
