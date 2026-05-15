package com.oilquiz.app.ai.agent;

import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;

public class CreativeWritingEngine {
    private static final String TAG = "CreativeWriter";
    private static final int MAX_TASK_ITERATIONS = 15;

    private final AIAgentEngine agentEngine;
    private final AIService aiService;

    public CreativeWritingEngine(AIAgentEngine agentEngine) {
        this.agentEngine = agentEngine;
        this.aiService = agentEngine.getAIService();
    }

    public boolean isCreativeTask(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("写") || lower.contains("创作") || lower.contains("故事") ||
            lower.contains("小说") || lower.contains("诗歌") || lower.contains("作文") ||
            lower.contains("散文") || lower.contains("剧本") || lower.contains("演讲") ||
            lower.contains("广告") || lower.contains("标语") || lower.contains("邮件");
    }

    public AIAgentEngine.AgentResult executeCreativeTask(String message) {
        try {
            String theme = extractTheme(message);
            String outline = generateOutline(message, theme);
            if (outline == null || outline.isEmpty()) {
                return new AIAgentEngine.AgentResult("创作失败: 无法生成大纲", false);
            }

            List<String> sections = parseOutline(outline);
            StringBuilder fullContent = new StringBuilder();

            for (int i = 0; i < sections.size() && i < MAX_TASK_ITERATIONS; i++) {
                String sectionPrompt = "根据大纲，撰写第" + (i + 1) + "部分:\n大纲: " + sections.get(i) +
                    "\n主题: " + theme + "\n\n请直接撰写内容，不要重复大纲:";
                String section = aiService.generateSync(sectionPrompt, 400);
                fullContent.append(section).append("\n\n");
            }

            return new AIAgentEngine.AgentResult(fullContent.toString().trim(), true);
        } catch (Exception e) {
            AILogger.e(TAG, "Creative writing failed: " + e.getMessage());
            return new AIAgentEngine.AgentResult("创作失败: " + e.getMessage(), false);
        }
    }

    private String generateOutline(String message, String theme) {
        try {
            String prompt = "为以下创作任务生成大纲（列出3-6个部分）:\n任务: " + message + "\n主题: " + theme;
            return aiService.generateSync(prompt, 300);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractTheme(String message) {
        if (message.contains("科幻")) return "科幻";
        if (message.contains("奇幻")) return "奇幻";
        if (message.contains("悬疑")) return "悬疑";
        if (message.contains("爱情")) return "爱情";
        if (message.contains("历史")) return "历史";
        if (message.contains("武侠")) return "武侠";
        if (message.contains("恐怖")) return "恐怖";
        if (message.contains("童话")) return "童话";
        if (message.contains("励志")) return "励志";
        if (message.contains("幽默")) return "幽默";
        return "通用";
    }

    private List<String> parseOutline(String outline) {
        List<String> sections = new ArrayList<>();
        String[] lines = outline.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            trimmed = trimmed.replaceAll("^\\d+[.、)）]\\s*", "");
            if (!trimmed.isEmpty()) sections.add(trimmed);
        }
        return sections;
    }
}
