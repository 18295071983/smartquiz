package com.oilquiz.app.ai.agent;

import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class TaskDecompositionManager {
    private static final String TAG = "TaskDecompMgr";
    private static final int MAX_AUTO_DECOMPOSE_TASKS = 5;
    private static final int LONG_CONTENT_THRESHOLD = 500;

    private final AIAgentEngine agentEngine;

    public TaskDecompositionManager(AIAgentEngine agentEngine) {
        this.agentEngine = agentEngine;
    }

    public boolean isLongContentTask(String message) {
        return message != null && message.length() > LONG_CONTENT_THRESHOLD;
    }

    public boolean isUnclearTask(String message) {
        if (message == null) return false;
        String trimmed = message.trim();
        return trimmed.length() < 5 || trimmed.endsWith("？") && trimmed.length() < 10;
    }

    public List<String> decomposeTask(String message) {
        try {
            AIService aiService = agentEngine.getAIService();
            String prompt = "将以下任务分解为" + MAX_AUTO_DECOMPOSE_TASKS + "个以内的独立子任务，每行一个，格式: 序号. 子任务描述\n\n任务: " + message;
            String response = aiService.generateSync(prompt, 400);
            return parseSubTasks(response);
        } catch (Exception e) {
            AILogger.e(TAG, "Task decomposition failed: " + e.getMessage());
            List<String> fallback = new ArrayList<>();
            fallback.add(message);
            return fallback;
        }
    }

    public List<String> decomposeLongContent(String content) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) return chunks;

        int chunkSize = LONG_CONTENT_THRESHOLD;
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + chunkSize, content.length());
            if (end < content.length()) {
                int lastPeriod = content.lastIndexOf('。', end);
                int lastNewline = content.lastIndexOf('\n', end);
                int breakPoint = Math.max(lastPeriod, lastNewline);
                if (breakPoint > start) end = breakPoint + 1;
            }
            chunks.add(content.substring(start, end));
            start = end;
        }
        return chunks;
    }

    public String summarizeContent(String content) {
        if (content == null || content.isEmpty()) return "";
        String[] sentences = content.split("[。！？\n]");
        StringBuilder summary = new StringBuilder();
        int count = 0;
        for (String sentence : sentences) {
            if (sentence.trim().length() > 10) {
                summary.append(sentence.trim()).append("。");
                count++;
                if (count >= 3) break;
            }
        }
        return summary.toString();
    }

    public String combineChunks(String combinedContent) {
        if (combinedContent == null || combinedContent.length() < 300) return combinedContent;
        try {
            AIService aiService = agentEngine.getAIService();
            String prompt = "将以下分段内容整合为连贯完整的回答，去除重复:\n\n" + combinedContent;
            return aiService.generateSync(prompt, 500);
        } catch (Exception e) {
            return combinedContent;
        }
    }

    private List<String> parseSubTasks(String response) {
        List<String> tasks = new ArrayList<>();
        if (response == null || response.isEmpty()) return tasks;
        String[] lines = response.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            trimmed = trimmed.replaceAll("^\\d+[.、)）]\\s*", "");
            if (!trimmed.isEmpty()) tasks.add(trimmed);
            if (tasks.size() >= MAX_AUTO_DECOMPOSE_TASKS) break;
        }
        return tasks;
    }
}
