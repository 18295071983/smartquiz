package com.oilquiz.app.ai.agent;

import com.oilquiz.app.ai.tool.AIToolsManager;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.util.AILogger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ServiceRouter {
    private static final String TAG = "ServiceRouter";

    private final AIAgentEngine agentEngine;
    private final AIToolsManager toolsManager;
    private final AIToolManager toolManager;

    public ServiceRouter(AIAgentEngine agentEngine) {
        this.agentEngine = agentEngine;
        this.toolsManager = agentEngine.getToolsManager();
        this.toolManager = agentEngine.getToolManager();
    }

    public AIAgentEngine.AgentResult executeWeatherTask(String message) {
        try {
            String city = extractCity(message);
            CompletableFuture<String> future = toolsManager.executeTool("get_weather", "city: " + city);
            String result = future.get(30, TimeUnit.SECONDS);
            return new AIAgentEngine.AgentResult(result, true);
        } catch (Exception e) {
            AILogger.e(TAG, "Weather task failed: " + e.getMessage());
            return new AIAgentEngine.AgentResult("天气查询失败: " + e.getMessage(), false);
        }
    }

    public AIAgentEngine.AgentResult executeSearchTask(String message) {
        try {
            String keyword = extractKeyword(message);
            CompletableFuture<String> future = toolsManager.executeTool("search_questions", "keyword: " + keyword);
            String result = future.get(30, TimeUnit.SECONDS);
            return new AIAgentEngine.AgentResult(result, true);
        } catch (Exception e) {
            AILogger.e(TAG, "Search task failed: " + e.getMessage());
            return new AIAgentEngine.AgentResult("搜索失败: " + e.getMessage(), false);
        }
    }

    public AIAgentEngine.AgentResult executeDatabaseTask(String message) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("action", "execute_query");
            params.put("query", message);
            AIToolResult result = toolManager.executeTool("database", params);
            if (result.isSuccess()) {
                return new AIAgentEngine.AgentResult(String.valueOf(result.getResult()), true);
            } else {
                return new AIAgentEngine.AgentResult(result.getErrorMessage(), false);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Database task failed: " + e.getMessage());
            return new AIAgentEngine.AgentResult("数据库操作失败: " + e.getMessage(), false);
        }
    }

    private String extractCity(String message) {
        String[] cities = {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京", "重庆", "西安"};
        for (String city : cities) {
            if (message.contains(city)) return city;
        }
        return "北京";
    }

    private String extractKeyword(String message) {
        String cleaned = message.replaceAll("(搜索|查找|查询|找一下|搜一下|关于)", "").trim();
        return cleaned.isEmpty() ? message : cleaned;
    }
}
