package com.oilquiz.app.ai.agent.online;

import android.content.Context;

import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 在线模型专用工具管理系统。
 * 独立于 {@link com.oilquiz.app.ai.service.AgentService}，
 * 内部集成 {@link OnlineToolRegistry}（注册）、{@link OnlineToolChain}（调用链）、
 * {@link OnlineToolUsageTracker}（使用链）和 {@link OnlineToolGuide}（工具指南）。
 *
 * 职责：
 * 1. 工具定义构建委托给 {@link OnlineToolRegistry}（带缓存）
 * 2. 工具执行（30s 超时 + 2 次重试 + 结果缓存）
 * 3. 工具执行后通过 {@link OnlineToolUsageTracker} 记录使用链
 * 4. 返回 {@link OnlineToolResult}（不使用 AgentService.ToolResult）
 */
public class OnlineToolManager {

    private static final String TAG = "OnlineToolManager";
    private static final int TOOL_TIMEOUT_MS = 30_000;
    private static final int MAX_RETRY = 2;
    private static final int RESULT_MAX_LENGTH = 3000;

    private final AIToolManager aiToolManager;
    private final OnlineToolRegistry registry;
    private final OnlineToolChain chain;
    private final OnlineToolUsageTracker usageTracker;
    private final OnlineToolGuide guide;

    // 工具结果缓存（toolName:arguments → result string）
    private final Map<String, String> resultCache = new ConcurrentHashMap<>();

    public OnlineToolManager(Context context) {
        this.aiToolManager = AIToolManager.getInstance(context);
        this.registry = new OnlineToolRegistry(context);
        this.chain = new OnlineToolChain(registry);
        this.usageTracker = new OnlineToolUsageTracker();
        this.guide = new OnlineToolGuide(registry, chain);
    }

    // ==================== 工具定义（委托 Registry） ====================

    /**
     * 获取 OpenAI function calling 格式的工具定义 JSON。
     * 委托给 {@link OnlineToolRegistry#getToolDefinitions()}。
     */
    public String getToolDefinitions() {
        return registry.getToolDefinitions();
    }

    // ==================== 工具执行（带重试 + 缓存 + 使用链记录） ====================

    /**
     * 执行工具调用。
     * 直接调用 AIToolManager.executeTool()，带超时、重试和结果缓存。
     * 执行完成后通过 {@link OnlineToolUsageTracker} 记录使用链。
     *
     * @param toolCallId 工具调用 ID（来自 API 的 tool_calls[].id）
     * @param toolName   工具名称
     * @param arguments  参数 JSON 字符串
     * @return 工具执行结果
     */
    public OnlineToolResult executeTool(String toolCallId, String toolName, String arguments) {
        AILogger.i(TAG, "Executing tool: " + toolName + " args: " + arguments);
        long startTime = System.currentTimeMillis();

        // 检查缓存
        String cacheKey = toolName + ":" + arguments;
        String cached = resultCache.get(cacheKey);
        if (cached != null) {
            AILogger.d(TAG, "Tool cache HIT: " + toolName);
            long elapsed = 0;
            usageTracker.recordCall(toolName, arguments, true, elapsed, cached);
            return OnlineToolResult.success(toolCallId, toolName, cached, elapsed);
        }

        // 解析参数
        Map<String, Object> params = parseArguments(arguments);
        if (params == null) {
            String error = "参数解析失败: " + arguments;
            usageTracker.recordCall(toolName, arguments, false,
                System.currentTimeMillis() - startTime, error);
            return OnlineToolResult.failure(toolCallId, toolName, error,
                System.currentTimeMillis() - startTime);
        }

        // 带重试的执行
        Exception lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            try {
                final int currentAttempt = attempt;
                AIToolResult result = CompletableFuture.supplyAsync(() -> {
                    try {
                        return aiToolManager.executeTool(toolName, params);
                    } catch (Exception e) {
                        AILogger.e(TAG, "Tool execution error (attempt " + currentAttempt + "): " + e.getMessage(), e);
                        return null;
                    }
                }).get(TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                if (result == null) {
                    throw new RuntimeException("工具执行返回 null");
                }

                long elapsed = System.currentTimeMillis() - startTime;
                String resultStr = formatResult(result);
                boolean success = result.isSuccess();

                // 缓存成功结果
                if (success) {
                    resultCache.put(cacheKey, resultStr);
                }

                AILogger.i(TAG, "Tool " + toolName + " completed in " + elapsed + "ms, success=" + success
                    + " attempt=" + attempt);

                // 记录使用链
                String summary = success ? resultStr
                    : (result.getErrorMessage() != null ? result.getErrorMessage() : "未知错误");
                usageTracker.recordCall(toolName, arguments, success, elapsed, summary);

                if (success) {
                    return OnlineToolResult.success(toolCallId, toolName, resultStr, elapsed);
                } else {
                    // 工具执行失败（非异常），不重试
                    String error = result.getErrorMessage() != null ? result.getErrorMessage() : "未知错误";
                    return OnlineToolResult.failure(toolCallId, toolName, error, elapsed);
                }

            } catch (TimeoutException e) {
                AILogger.w(TAG, "Tool " + toolName + " timeout (attempt " + attempt + ")");
                lastException = e;
            } catch (Exception e) {
                AILogger.w(TAG, "Tool " + toolName + " error (attempt " + attempt + "): " + e.getMessage());
                lastException = e;
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        String errorMsg = lastException != null ? lastException.getMessage() : "工具执行失败";
        usageTracker.recordCall(toolName, arguments, false, elapsed, errorMsg);
        return OnlineToolResult.failure(toolCallId, toolName, errorMsg, elapsed);
    }

    // ==================== 工具描述查询（委托 Guide/Registry） ====================

    /**
     * 获取单个工具的描述（供提示词使用）。委托给 {@link OnlineToolGuide#buildToolDetail}。
     */
    public String getToolDescription(String toolName) {
        return guide.buildToolDetail(toolName);
    }

    /**
     * 获取完整工具指南。委托给 {@link OnlineToolGuide#buildGuide}。
     */
    public String getToolGuide() {
        return guide.buildGuide();
    }

    // ==================== 组件访问 ====================

    /** 返回 {@link OnlineToolRegistry} */
    public OnlineToolRegistry getRegistry() {
        return registry;
    }

    /** 返回 {@link OnlineToolChain} */
    public OnlineToolChain getToolChain() {
        return chain;
    }

    /** 返回 {@link OnlineToolUsageTracker} */
    public OnlineToolUsageTracker getUsageTracker() {
        return usageTracker;
    }

    /** 返回 {@link OnlineToolGuide} */
    public OnlineToolGuide getToolGuideInstance() {
        return guide;
    }

    /**
     * 获取使用统计摘要。
     */
    public String getUsageStats() {
        return usageTracker.getGlobalStatsSummary();
    }

    // ==================== 缓存管理 ====================

    /**
     * 清除结果缓存
     */
    public void clearCache() {
        resultCache.clear();
    }

    /**
     * 刷新工具注册系统（从 AIToolManager 重新同步元数据）。
     */
    public void refreshRegistry() {
        registry.syncFromAIToolManager();
        guide.markCacheDirty();
    }

    /**
     * 重置使用统计（开始新会话时调用）。
     */
    public void resetUsageStats() {
        usageTracker.resetStats();
    }

    // ========== 内部方法 ==========

    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isEmpty() || "null".equals(arguments)) {
            return new HashMap<>();
        }
        try {
            JSONObject json = new JSONObject(arguments);
            Map<String, Object> params = new HashMap<>();
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = json.get(key);
                // 处理 JSON 类型到 Java 类型
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    params.put(key, value.toString());
                } else {
                    params.put(key, value);
                }
            }
            return params;
        } catch (JSONException e) {
            AILogger.w(TAG, "Failed to parse arguments: " + arguments + " - " + e.getMessage());
            return null;
        }
    }

    private String formatResult(AIToolResult result) {
        if (!result.isSuccess()) {
            String err = result.getErrorMessage();
            return err != null ? err : "执行失败";
        }
        Object raw = result.getResult();
        if (raw == null) {
            return "执行成功，无返回值";
        }
        String str = raw.toString();
        if (str.length() > RESULT_MAX_LENGTH) {
            str = str.substring(0, RESULT_MAX_LENGTH) + "...(已截断)";
        }
        return str;
    }
}
