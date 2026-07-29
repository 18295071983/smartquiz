package com.oilquiz.app.ai.agent.software.thinking;

import com.oilquiz.app.ai.agent.software.thinking.model.*;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.List;

public class ReflectionEngine {
    private static final String TAG = "ReflectionEngine";
    private final AIService aiService;

    public static class ReflectionResult {
        private String reflection;
        private String blindSpot;
        private String assumption;

        public ReflectionResult(String reflection, String blindSpot, String assumption) {
            this.reflection = reflection;
            this.blindSpot = blindSpot;
            this.assumption = assumption;
        }

        public String getReflection() { return reflection; }
        public String getBlindSpot() { return blindSpot; }
        public String getAssumption() { return assumption; }
    }

    public ReflectionEngine(AIService aiService) {
        this.aiService = aiService;
    }

    public ReflectionResult reflect(ThinkingSession session, String userMessage) {
        if (session == null || userMessage == null || userMessage.trim().isEmpty()) {
            return new ReflectionResult("请提供更多信息以便反思", null, null);
        }

        try {
            if (!LlamaHelper.isModelInitialized()) {
                return reflectByRules(session, userMessage);
            }

            String prompt = buildReflectionPrompt(session, userMessage);
            String response = LlamaHelper.generate(prompt, 300, 0.5f);

            if (response == null || response.trim().isEmpty()) {
                return reflectByRules(session, userMessage);
            }

            return parseReflectionResponse(response);

        } catch (Exception e) {
            AILogger.e(TAG, "Error in reflection: " + e.getMessage(), e);
            return reflectByRules(session, userMessage);
        }
    }

    private String buildReflectionPrompt(ThinkingSession session, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个反思引擎，帮助用户审视自己的思考过程。\n\n");
        sb.append("【反思维度】\n");
        sb.append("1. 隐含假设：用户的思考基于什么假设？这些假设合理吗？\n");
        sb.append("2. 盲点：用户可能忽略了什么？\n");
        sb.append("3. 情绪影响：情绪是否影响了判断？\n\n");

        if (session.getMessages().size() > 0) {
            sb.append("【思考历史】\n");
            List<ThinkingMessage> recent = session.getRecentMessages(5);
            for (ThinkingMessage msg : recent) {
                String role = msg.getRole() == ThinkingMessage.Role.USER ? "用户" : "AI";
                sb.append(role).append(": ").append(truncate(msg.getContent(), 80)).append("\n");
            }
            sb.append("\n");
        }

        sb.append("【用户当前输入】\n").append(userMessage).append("\n\n");

        sb.append("【输出格式】\n");
        sb.append("REFLECTION: <反思内容>\n");
        sb.append("BLIND_SPOT: <可能的盲点，可为空>\n");
        sb.append("ASSUMPTION: <隐含的假设，可为空>\n\n");
        sb.append("【输出】\n");

        return sb.toString();
    }

    private ReflectionResult parseReflectionResponse(String response) {
        String reflection = "", blindSpot = "", assumption = "";

        try {
            int refIdx = response.indexOf("REFLECTION:");
            int blindIdx = response.indexOf("BLIND_SPOT:");
            int assumIdx = response.indexOf("ASSUMPTION:");

            if (refIdx >= 0) {
                int end = blindIdx > refIdx ? blindIdx : (assumIdx > refIdx ? assumIdx : response.length());
                reflection = response.substring(refIdx + 11, end).trim();
            }
            if (blindIdx >= 0) {
                int end = assumIdx > blindIdx ? assumIdx : response.length();
                blindSpot = response.substring(blindIdx + 11, end).trim();
            }
            if (assumIdx >= 0) {
                assumption = response.substring(assumIdx + 11).trim();
            }

        } catch (Exception e) {
            AILogger.w(TAG, "Error parsing reflection: " + e.getMessage());
        }

        if (reflection.isEmpty()) {
            reflection = "继续深入思考这个问题";
        }

        return new ReflectionResult(reflection, blindSpot, assumption);
    }

    private ReflectionResult reflectByRules(ThinkingSession session, String userMessage) {
        int depth = session.getDepth();

        if (depth <= 1) {
            return new ReflectionResult(
                    "你可以尝试更具体地描述你的感受和想法",
                    null,
                    null);
        }

        if (depth <= 3) {
            return new ReflectionResult(
                    "你已经做了一些思考，现在可以尝试审视：你的思考基于什么假设？这些假设成立吗？",
                    "可能忽略了某些方面的信息",
                    null);
        }

        return new ReflectionResult(
                "你已经深入思考了这个问题。现在可以尝试：\n1. 审视你的核心假设\n2. 考虑相反的观点\n3. 思考这个结论的实际影响",
                null,
                null);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
