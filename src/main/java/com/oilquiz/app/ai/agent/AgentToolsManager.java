package com.oilquiz.app.ai.agent;

import android.content.Context;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AgentToolsManager {
    private static final String TAG = "AgentToolsManager";

    private final Context context;
    private final AIToolManager aiToolManager;
    private AgentService agentService;

    private final Map<String, ToolInfo> cognitiveTools = new HashMap<>();
    private final Map<String, ToolInfo> executionToolsCache = new HashMap<>();
    private boolean initialized = false;

    public static class ToolInfo {
        public final String name;
        public final String description;
        public final String paramSchema;
        public final String category;
        public final boolean isCognitive;

        public ToolInfo(String name, String description, String paramSchema, String category, boolean isCognitive) {
            this.name = name;
            this.description = description;
            this.paramSchema = paramSchema;
            this.category = category;
            this.isCognitive = isCognitive;
        }
    }

    public AgentToolsManager(Context context) {
        this.context = context.getApplicationContext();
        this.aiToolManager = AIToolManager.getInstance(context);
    }

    public void setAgentService(AgentService agentService) {
        this.agentService = agentService;
        syncFromAgentService();
    }

    public synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        registerCognitiveTools();
        syncFromAIToolManager();
        AILogger.i(TAG, "AgentToolsManager initialized: " + cognitiveTools.size() + " cognitive + " + executionToolsCache.size() + " execution tools");
    }

    private void registerCognitiveTools() {
        cognitiveTools.put("intent_recognition",
            new ToolInfo("intent_recognition", "识别用户意图，判断问题类型", "message(用户消息,必填)", "reasoning", true));
        cognitiveTools.put("context_analysis",
            new ToolInfo("context_analysis", "分析对话上下文，提取关键信息", "context(对话上下文,必填)", "reasoning", true));
        cognitiveTools.put("plan_generation",
            new ToolInfo("plan_generation", "生成任务执行计划，分解复杂任务", "task(任务描述,必填)", "reasoning", true));
        cognitiveTools.put("reflection",
            new ToolInfo("reflection", "反思推理过程，检查答案合理性", "reasoning(推理过程,必填)", "reasoning", true));
        cognitiveTools.put("knowledge_retrieval",
            new ToolInfo("knowledge_retrieval", "从知识库检索相关信息", "query(查询问题,必填)", "knowledge", true));
        cognitiveTools.put("entity_extraction",
            new ToolInfo("entity_extraction", "提取文本中的实体信息", "text(文本内容,必填)", "knowledge", true));
        cognitiveTools.put("sentiment_analysis",
            new ToolInfo("sentiment_analysis", "分析文本情感倾向", "text(文本内容,必填)", "analysis", true));
        cognitiveTools.put("information_validation",
            new ToolInfo("information_validation", "验证信息来源和准确性", "information(待验证信息,必填)", "analysis", true));
    }

    private void syncFromAIToolManager() {
        executionToolsCache.clear();
        try {
            List<Map<String, Object>> toolDescs = aiToolManager.getToolDescriptions();
            for (Map<String, Object> desc : toolDescs) {
                String name = (String) desc.get("name");
                String description = (String) desc.get("description");
                @SuppressWarnings("unchecked")
                Map<String, String> params = (Map<String, String>) desc.get("parameters");

                StringBuilder paramSchema = new StringBuilder();
                if (params != null && !params.isEmpty()) {
                    for (Map.Entry<String, String> entry : params.entrySet()) {
                        if (paramSchema.length() > 0) paramSchema.append(", ");
                        paramSchema.append(entry.getKey()).append("(").append(entry.getValue()).append(")");
                    }
                } else {
                    paramSchema.append("无参数");
                }

                String category = categorizeTool(name, description);
                executionToolsCache.put(name,
                    new ToolInfo(name, description, paramSchema.toString(), category, false));
                AILogger.d(TAG, "Synced tool: " + name + " [" + category + "]");
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to sync from AIToolManager: " + e.getMessage());
        }
    }

    public void syncFromAgentService() {
        if (agentService == null) return;
        executionToolsCache.clear();
        for (AgentService.ToolSchema schema : agentService.getToolSchemas()) {
            String category = categorizeTool(schema.name, schema.description);
            executionToolsCache.put(schema.name,
                new ToolInfo(schema.name, schema.description, schema.paramDesc, category, false));
        }
        AILogger.i(TAG, "Synced from AgentService: " + executionToolsCache.size() + " tools");
    }

    private String categorizeTool(String name, String description) {
        String combined = (name + " " + description).toLowerCase();
        if (combined.contains("天气") || combined.contains("weather")) return "weather";
        if (combined.contains("搜索") || combined.contains("search") || combined.contains("find")) return "search";
        if (combined.contains("计算") || combined.contains("calc") || combined.contains("math")) return "calculate";
        if (combined.contains("翻译") || combined.contains("translat")) return "translate";
        if (combined.contains("数据库") || combined.contains("database") || combined.contains("db")) return "database";
        if (combined.contains("题目") || combined.contains("question") || combined.contains("quiz")) return "quiz";
        if (combined.contains("文件") || combined.contains("file")) return "file";
        if (combined.contains("网页") || combined.contains("web") || combined.contains("page") || combined.contains("url")) return "web";
        if (combined.contains("系统") || combined.contains("system") || combined.contains("resource")) return "system";
        if (combined.contains("权限") || combined.contains("permission")) return "permission";
        if (combined.contains("研究") || combined.contains("research")) return "research";
        return "general";
    }

    public List<ToolInfo> getAllTools() {
        List<ToolInfo> all = new ArrayList<>();
        all.addAll(cognitiveTools.values());
        all.addAll(executionToolsCache.values());
        return all;
    }

    public List<ToolInfo> getExecutionTools() {
        return new ArrayList<>(executionToolsCache.values());
    }

    public List<ToolInfo> getCognitiveTools() {
        return new ArrayList<>(cognitiveTools.values());
    }

    public List<ToolInfo> getToolsByCategory(String category) {
        List<ToolInfo> result = new ArrayList<>();
        for (ToolInfo tool : getAllTools()) {
            if (tool.category.equals(category)) {
                result.add(tool);
            }
        }
        return result;
    }

    public ToolInfo getTool(String name) {
        if (executionToolsCache.containsKey(name)) return executionToolsCache.get(name);
        return cognitiveTools.get(name);
    }

    public boolean hasTool(String name) {
        return executionToolsCache.containsKey(name) || cognitiveTools.containsKey(name);
    }

    public String buildToolPromptForLLM(String intentType) {
        StringBuilder sb = new StringBuilder();

        for (ToolInfo tool : cognitiveTools.values()) {
            sb.append("  [思考] ").append(tool.name).append(": ").append(tool.description).append("\n");
        }
        sb.append("\n");

        List<ToolInfo> prioritized;
        if (intentType != null && !intentType.isEmpty()) {
            prioritized = getToolsByCategory(intentType);
            for (ToolInfo tool : executionToolsCache.values()) {
                if (!prioritized.contains(tool)) {
                    prioritized.add(tool);
                }
            }
        } else {
            prioritized = new ArrayList<>(executionToolsCache.values());
        }

        sb.append("  【执行工具】\n");
        for (ToolInfo tool : prioritized) {
            sb.append("  - ").append(tool.name).append(": ").append(tool.description)
                .append(" [" + tool.category + "]")
                .append("\n    ").append(tool.paramSchema).append("\n");
        }

        return sb.toString();
    }

    public String buildFullToolPrompt() {
        return buildToolPromptForLLM(null);
    }

    public void refresh() {
        syncFromAIToolManager();
        if (agentService != null) {
            syncFromAgentService();
        }
        AILogger.i(TAG, "Tools refreshed: " + getAllTools().size() + " total");
    }

    public Context getContext() { return context; }
    public boolean isInitialized() { return initialized; }
}