package com.oilquiz.app.ai.agent.software.thinking;

import com.oilquiz.app.ai.agent.software.thinking.model.*;
import com.oilquiz.app.ai.engine.NpuEngineRouter;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ThinkingAnalyzer {
    private static final String TAG = "ThinkingAnalyzer";
    private final AIService aiService;

    public static class AnalysisResult {
        public int clarity;
        public int depth;
        public int information;
        public String emotion;
        public String suggestion;
        public String thinkingStyle;

        public AnalysisResult(int clarity, int depth, int information, String emotion,
                             String suggestion, String thinkingStyle) {
            this.clarity = clarity;
            this.depth = depth;
            this.information = information;
            this.emotion = emotion;
            this.suggestion = suggestion;
            this.thinkingStyle = thinkingStyle;
        }
    }

    public ThinkingAnalyzer(AIService aiService) {
        this.aiService = aiService;
    }

    public AnalysisResult analyze(String userMessage, ThinkingSession session) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return new AnalysisResult(3, 1, 1, "平静", "请告诉我你想思考的问题", "SOCRATIC");
        }

        try {
            if (!LlamaHelper.isModelInitialized()) {
                AILogger.w(TAG, "Model not initialized, using keyword analysis");
                return analyzeByKeywords(userMessage, session);
            }

            String prompt = buildAnalyzePrompt(userMessage, session);
            String response = NpuEngineRouter.generate(prompt, 200, 0.3f, 0.9f, 40);

            if (response == null || response.trim().isEmpty()) {
                return analyzeByKeywords(userMessage, session);
            }

            return parseAnalysisResponse(response);

        } catch (Exception e) {
            AILogger.e(TAG, "Error in thinking analysis: " + e.getMessage(), e);
            return analyzeByKeywords(userMessage, session);
        }
    }

    private String buildAnalyzePrompt(String userMessage, ThinkingSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个思考状态分析引擎，分析用户当前的思考状态。\n\n");
        sb.append("【分析维度】\n");
        sb.append("1. 问题清晰度：用户的问题是否明确？(1-5分，5=非常明确)\n");
        sb.append("2. 思考深度：用户已经深入思考了吗？(1-5分，5=非常深入)\n");
        sb.append("3. 信息完备度：用户是否有足够信息？(1-5分，5=信息充足)\n");
        sb.append("4. 情绪状态：用户的情绪如何？(焦虑/平静/兴奋/困惑/沮丧)\n");
        sb.append("5. 思考风格：适合哪种思考方式？(SOCRATIC/MULTI_PERSPECTIVE/REFLECTIVE/CREATIVE/STRUCTURED)\n\n");

        if (session != null && session.getMessages().size() > 0) {
            sb.append("【思考历史】\n");
            List<ThinkingMessage> recentMessages = session.getRecentMessages(5);
            for (ThinkingMessage msg : recentMessages) {
                String role = msg.getRole() == ThinkingMessage.Role.USER ? "用户" : "AI";
                sb.append(role).append(": ").append(truncate(msg.getContent(), 100)).append("\n");
            }
            sb.append("\n");
        }

        sb.append("【输出格式（严格）】\n");
        sb.append("CLEARITY: <1-5>\n");
        sb.append("DEPTH: <1-5>\n");
        sb.append("INFORMATION: <1-5>\n");
        sb.append("EMOTION: <情绪>\n");
        sb.append("SUGGESTION: <建议下一步>\n");
        sb.append("STYLE: <思考风格>\n\n");

        sb.append("【用户当前消息】\n");
        sb.append(userMessage).append("\n\n");
        sb.append("【输出】\n");

        return sb.toString();
    }

    private AnalysisResult parseAnalysisResponse(String response) {
        int clarity = 3, depth = 1, information = 1;
        String emotion = "平静";
        String suggestion = "继续思考";
        String thinkingStyle = "SOCRATIC";

        try {
            Pattern clarityPattern = Pattern.compile("CLEARITY:\\s*(\\d+)");
            Matcher m = clarityPattern.matcher(response);
            if (m.find()) clarity = Integer.parseInt(m.group(1));

            Pattern depthPattern = Pattern.compile("DEPTH:\\s*(\\d+)");
            m = depthPattern.matcher(response);
            if (m.find()) depth = Integer.parseInt(m.group(1));

            Pattern infoPattern = Pattern.compile("INFORMATION:\\s*(\\d+)");
            m = infoPattern.matcher(response);
            if (m.find()) information = Integer.parseInt(m.group(1));

            Pattern emotionPattern = Pattern.compile("EMOTION:\\s*(\\w+)");
            m = emotionPattern.matcher(response);
            if (m.find()) emotion = m.group(1);

            Pattern suggestionPattern = Pattern.compile("SUGGESTION:\\s*(.+?)(?=\\n|$)");
            m = suggestionPattern.matcher(response);
            if (m.find()) suggestion = m.group(1).trim();

            Pattern stylePattern = Pattern.compile("STYLE:\\s*(\\w+)");
            m = stylePattern.matcher(response);
            if (m.find()) thinkingStyle = m.group(1).toUpperCase();

        } catch (Exception e) {
            AILogger.w(TAG, "Error parsing analysis response: " + e.getMessage());
        }

        return new AnalysisResult(clarity, depth, information, emotion, suggestion, thinkingStyle);
    }

    private AnalysisResult analyzeByKeywords(String message, ThinkingSession session) {
        String lower = message.toLowerCase();
        int clarity = 3, depth = 1, information = 1;
        String emotion = "平静";
        String suggestion = "继续思考";
        String thinkingStyle = "SOCRATIC";

        if (containsAny(lower, "为什么", "原因", "怎么回事", "what", "why")) {
            clarity = 2;
            thinkingStyle = "SOCRATIC";
            suggestion = "澄清问题";
        } else if (containsAny(lower, "对比", "比较", "利弊", "优缺点", "compare")) {
            clarity = 4;
            thinkingStyle = "MULTI_PERSPECTIVE";
            suggestion = "多角度分析";
        } else if (containsAny(lower, "决定", "选择", "要不要", "该不该", "decide")) {
            clarity = 3;
            thinkingStyle = "REFLECTIVE";
            suggestion = "反思式思考";
        } else if (containsAny(lower, "创意", "想法", "灵感", "brainstorm")) {
            clarity = 3;
            thinkingStyle = "CREATIVE";
            suggestion = "创造性思考";
        } else if (containsAny(lower, "整理", "总结", "梳理", "summarize")) {
            clarity = 4;
            thinkingStyle = "STRUCTURED";
            suggestion = "结构化整理";
        }

        if (containsAny(lower, "焦虑", "担心", "害怕", "压力", "困惑")) {
            emotion = "焦虑";
        } else if (containsAny(lower, "兴奋", "开心", "期待")) {
            emotion = "兴奋";
        } else if (containsAny(lower, "累", "疲劳", "沮丧", "失望")) {
            emotion = "沮丧";
        }

        if (session != null && session.getDepth() >= 3) {
            depth = Math.min(5, session.getDepth() + 1);
        }

        return new AnalysisResult(clarity, depth, information, emotion, suggestion, thinkingStyle);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
