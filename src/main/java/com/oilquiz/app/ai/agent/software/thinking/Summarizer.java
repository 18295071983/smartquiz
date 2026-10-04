package com.oilquiz.app.ai.agent.software.thinking;

import com.oilquiz.app.ai.agent.software.thinking.model.*;
import com.oilquiz.app.ai.engine.NpuEngineRouter;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.List;

public class Summarizer {
    private static final String TAG = "Summarizer";
    private final AIService aiService;

    public static class SummaryResult {
        private String summary;
        private List<String> keyPoints;
        private List<String> recommendations;
        private String nextAction;

        public SummaryResult(String summary, List<String> keyPoints,
                            List<String> recommendations, String nextAction) {
            this.summary = summary;
            this.keyPoints = keyPoints;
            this.recommendations = recommendations;
            this.nextAction = nextAction;
        }

        public String getSummary() { return summary; }
        public List<String> getKeyPoints() { return keyPoints; }
        public List<String> getRecommendations() { return recommendations; }
        public String getNextAction() { return nextAction; }
    }

    public Summarizer(AIService aiService) {
        this.aiService = aiService;
    }

    public SummaryResult summarize(ThinkingSession session) {
        if (session == null || session.getMessages().isEmpty()) {
            return new SummaryResult("暂无思考内容", null, null, null);
        }

        try {
            if (!LlamaHelper.isModelInitialized()) {
                return summarizeByRules(session);
            }

            String prompt = buildSummaryPrompt(session);
            String response = NpuEngineRouter.generate(prompt, 500, 0.3f, 0.9f, 40);

            if (response == null || response.trim().isEmpty()) {
                return summarizeByRules(session);
            }

            return parseSummaryResponse(response);

        } catch (Exception e) {
            AILogger.e(TAG, "Error summarizing: " + e.getMessage(), e);
            return summarizeByRules(session);
        }
    }

    private String buildSummaryPrompt(ThinkingSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个思考总结引擎，总结用户的思考过程和关键发现。\n\n");

        sb.append("【用户问题】\n");
        sb.append(session.getUserQuestion() != null ? session.getUserQuestion() : "（未指定）").append("\n\n");

        sb.append("【思考历史】\n");
        for (ThinkingMessage msg : session.getMessages()) {
            String role = msg.getRole() == ThinkingMessage.Role.USER ? "用户" : "AI";
            sb.append(role).append(": ").append(truncate(msg.getContent(), 150)).append("\n");
        }
        sb.append("\n");

        if (session.getContext() != null) {
            ThinkingContext ctx = session.getContext();
            if (!ctx.getKeyPoints().isEmpty()) {
                sb.append("【关键点】\n");
                for (String kp : ctx.getKeyPoints()) {
                    sb.append("- ").append(kp).append("\n");
                }
                sb.append("\n");
            }
            if (!ctx.getBlindSpots().isEmpty()) {
                sb.append("【盲点】\n");
                for (String bs : ctx.getBlindSpots()) {
                    sb.append("- ").append(bs).append("\n");
                }
                sb.append("\n");
            }
        }

        sb.append("【输出格式】\n");
        sb.append("SUMMARY: <整体思考总结，3-5句话>\n");
        sb.append("KEY_POINTS: <关键要点，用逗号分隔>\n");
        sb.append("RECOMMENDATIONS: <建议，用逗号分隔>\n");
        sb.append("NEXT_ACTION: <下一步行动建议>\n\n");
        sb.append("【输出】\n");

        return sb.toString();
    }

    private SummaryResult parseSummaryResponse(String response) {
        String summary = "", keyPointsStr = "", recommendationsStr = "", nextAction = "";

        try {
            int sumIdx = response.indexOf("SUMMARY:");
            int kpIdx = response.indexOf("KEY_POINTS:");
            int recIdx = response.indexOf("RECOMMENDATIONS:");
            int naIdx = response.indexOf("NEXT_ACTION:");

            if (sumIdx >= 0) {
                int end = kpIdx > sumIdx ? kpIdx : (recIdx > sumIdx ? recIdx : (naIdx > sumIdx ? naIdx : response.length()));
                summary = response.substring(sumIdx + 8, end).trim();
            }
            if (kpIdx >= 0) {
                int end = recIdx > kpIdx ? recIdx : (naIdx > kpIdx ? naIdx : response.length());
                keyPointsStr = response.substring(kpIdx + 11, end).trim();
            }
            if (recIdx >= 0) {
                int end = naIdx > recIdx ? naIdx : response.length();
                recommendationsStr = response.substring(recIdx + 16, end).trim();
            }
            if (naIdx >= 0) {
                nextAction = response.substring(naIdx + 11).trim();
            }

        } catch (Exception e) {
            AILogger.w(TAG, "Error parsing summary: " + e.getMessage());
        }

        List<String> keyPoints = splitAndClean(keyPointsStr);
        List<String> recommendations = splitAndClean(recommendationsStr);

        return new SummaryResult(summary, keyPoints, recommendations, nextAction);
    }

    private SummaryResult summarizeByRules(ThinkingSession session) {
        StringBuilder summary = new StringBuilder();
        List<String> keyPoints = new java.util.ArrayList<>();
        List<String> recommendations = new java.util.ArrayList<>();

        ThinkingContext ctx = session.getContext();

        if (ctx.getClarifiedQuestion() != null) {
            summary.append("你在思考关于「").append(ctx.getClarifiedQuestion()).append("」的问题。");
        } else if (session.getUserQuestion() != null) {
            summary.append("你在思考关于「").append(session.getUserQuestion()).append("」的问题。");
        }

        summary.append("经过 ").append(session.getDepth()).append(" 轮深度思考，");

        if (ctx.getClarity() >= 4) {
            summary.append("你对问题已经有了比较清晰的认识。");
        } else if (ctx.getClarity() >= 2) {
            summary.append("你正在逐步理清问题的脉络。");
        } else {
            summary.append("问题的某些方面还有待进一步澄清。");
        }

        if (!ctx.getKeyPoints().isEmpty()) {
            summary.append("核心要点包括：").append(String.join("、", ctx.getKeyPoints())).append("。");
        }

        if (!ctx.getBlindSpots().isEmpty()) {
            summary.append("需要进一步思考的方面有：").append(String.join("、", ctx.getBlindSpots())).append("。");
        }

        keyPoints.addAll(ctx.getKeyPoints());
        if (keyPoints.isEmpty()) {
            keyPoints.add("持续深入思考");
            keyPoints.add("考虑多方面因素");
        }

        recommendations.add("继续反思和验证你的假设");
        recommendations.add("尝试从不同角度看问题");
        if (!ctx.getNextSteps().isEmpty()) {
            recommendations.addAll(ctx.getNextSteps());
        }

        String nextAction = "建议先从最重要的一个方面开始行动";
        if (ctx.getBlindSpots().size() > 0) {
            nextAction = "建议进一步了解" + ctx.getBlindSpots().get(0);
        }

        return new SummaryResult(summary.toString(), keyPoints, recommendations, nextAction);
    }

    private List<String> splitAndClean(String s) {
        List<String> result = new java.util.ArrayList<>();
        if (s == null || s.trim().isEmpty()) return result;

        for (String part : s.split("[,，、;；\\n]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
