package com.oilquiz.app.ai.agent.software.thinking;

import com.oilquiz.app.ai.agent.software.thinking.model.*;
import com.oilquiz.app.ai.engine.NpuEngineRouter;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;

public class PerspectiveEngine {
    private static final String TAG = "PerspectiveEngine";
    private final AIService aiService;

    public static class PerspectiveResult {
        private String aspect;
        private String content;
        private String suggestion;

        public PerspectiveResult(String aspect, String content, String suggestion) {
            this.aspect = aspect;
            this.content = content;
            this.suggestion = suggestion;
        }

        public String getAspect() { return aspect; }
        public String getContent() { return content; }
        public String getSuggestion() { return suggestion; }
    }

    public PerspectiveEngine(AIService aiService) {
        this.aiService = aiService;
    }

    public PerspectiveResult suggestPerspective(ThinkingSession session, String userQuestion) {
        if (session == null || userQuestion == null || userQuestion.trim().isEmpty()) {
            return new PerspectiveResult("目标", "明确思考的目标是什么", "从目标角度思考");
        }

        try {
            if (!LlamaHelper.isModelInitialized()) {
                return suggestByRules(session, userQuestion);
            }

            String prompt = buildPerspectivePrompt(session, userQuestion);
            String response = NpuEngineRouter.generate(prompt, 300, 0.8f, 0.9f, 40);

            if (response == null || response.trim().isEmpty()) {
                return suggestByRules(session, userQuestion);
            }

            return parsePerspectiveResponse(response);

        } catch (Exception e) {
            AILogger.e(TAG, "Error suggesting perspective: " + e.getMessage(), e);
            return suggestByRules(session, userQuestion);
        }
    }

    private String buildPerspectivePrompt(ThinkingSession session, String userQuestion) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个思考视角引擎，为用户提供新颖的思考角度。\n\n");
        sb.append("【可用视角】\n");
        sb.append("- 目标视角：这件事的核心目标是什么？\n");
        sb.append("- 利益相关者：谁会受影响？各方的利益是什么？\n");
        sb.append("- 时间维度：短期/中期/长期分别会怎样？\n");
        sb.append("- 风险视角：可能的风险和最坏情况是什么？\n");
        sb.append("- 资源视角：需要哪些资源？有什么约束？\n");
        sb.append("- 替代方案：有没有其他方法？\n");
        sb.append("- 反面思考：如果反过来会怎样？\n\n");

        sb.append("【用户问题】\n").append(userQuestion).append("\n\n");

        if (session.getContext() != null && !session.getContext().getExploredAspects().isEmpty()) {
            sb.append("【已探索的视角】\n");
            for (String aspect : session.getContext().getExploredAspects()) {
                sb.append("- ").append(aspect).append("\n");
            }
            sb.append("\n");
            sb.append("【请选择一个尚未探索的视角，生成引导思考的问题】\n");
        }

        sb.append("【输出格式】\n");
        sb.append("ASPECT: <视角名称>\n");
        sb.append("CONTENT: <该视角的引导内容>\n");
        sb.append("SUGGESTION: <具体思考建议>\n\n");
        sb.append("【输出】\n");

        return sb.toString();
    }

    private PerspectiveResult parsePerspectiveResponse(String response) {
        String aspect = "", content = "", suggestion = "";

        try {
            int aspectIdx = response.indexOf("ASPECT:");
            int contentIdx = response.indexOf("CONTENT:");
            int suggestionIdx = response.indexOf("SUGGESTION:");

            if (aspectIdx >= 0 && contentIdx > aspectIdx) {
                aspect = response.substring(aspectIdx + 7, contentIdx).trim();
            }
            if (contentIdx >= 0 && suggestionIdx > contentIdx) {
                content = response.substring(contentIdx + 8, suggestionIdx).trim();
            }
            if (suggestionIdx >= 0) {
                suggestion = response.substring(suggestionIdx + 11).trim();
            }

        } catch (Exception e) {
            AILogger.w(TAG, "Error parsing perspective: " + e.getMessage());
        }

        if (aspect.isEmpty()) aspect = "目标";
        if (content.isEmpty()) content = "从新角度思考这个问题";

        return new PerspectiveResult(aspect, content, suggestion);
    }

    private PerspectiveResult suggestByRules(ThinkingSession session, String userQuestion) {
        List<String> explored = session.getContext() != null ?
                session.getContext().getExploredAspects() : new ArrayList<>();

        String[][] perspectives = {
                {"目标", "这件事的核心目标是什么？", "从目标角度思考"},
                {"利益相关者", "谁会受影响？各方的利益是什么？", "考虑各方利益"},
                {"时间维度", "短期/中期/长期分别会怎样？", "考虑时间影响"},
                {"风险", "可能的风险和最坏情况是什么？", "评估潜在风险"},
                {"资源", "需要哪些资源？有什么约束？", "盘点资源约束"},
                {"替代方案", "有没有其他方法？", "探索替代方案"},
                {"反面思考", "如果反过来会怎样？", "尝试逆向思考"},
                {"类比", "类似的情况是怎么处理的？", "寻找类比参考"}
        };

        for (String[] p : perspectives) {
            if (!explored.contains(p[0])) {
                return new PerspectiveResult(p[0], p[1], p[2]);
            }
        }

        return new PerspectiveResult("创新", "跳出框架思考，有没有全新的可能性？", "尝试突破现有思路");
    }
}
