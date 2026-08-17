package com.oilquiz.app.ai.agent.online;

import android.content.Context;

import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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
    /** 权限请求类工具需要用户交互，超时时间设为 120 秒 */
    private static final int PERMISSION_TOOL_TIMEOUT_MS = 120_000;
    private static final int MAX_RETRY = 2;
    // 不再截断工具返回结果，保证数据完整性
    private static final int RESULT_MAX_LENGTH = Integer.MAX_VALUE;
    private static final Gson resultGson = new GsonBuilder().disableHtmlEscaping().create();

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

    /**
     * 按用户消息意图获取工具定义子集（省 token）。
     * 用关键词匹配工具类别，只注入相关工具：
     * - 无关键词命中 → 全量（保证能力完整）
     * - 命中 → 相关类别 + 基础类别（file/database/general 视场景）
     * 大幅减少每轮推理的工具定义 token（20+ 工具全量定义可达数千 token）。
     */
    public String getToolDefinitionsForMessage(String message) {
        if (message == null || message.trim().isEmpty()) return getToolDefinitions();
        String msg = message.toLowerCase();
        java.util.Set<String> matchedCategories = new java.util.LinkedHashSet<>();
        if (containsAny(msg, "天气", "气温", "温度", "预报", "weather")) matchedCategories.add("weather");
        if (containsAny(msg, "搜索", "查一下", "查找", "最新", "新闻", "油价", "汇率", "search", "news", "find", "查询", "读网页", "网页")) matchedCategories.add("search");
        if (containsAny(msg, "翻译", "translate", "译成", "英文", "日语", "韩语")) matchedCategories.add("translation");
        if (containsAny(msg, "计算", "算一下", "calculator", "calculate", "math")) matchedCategories.add("calculator");
        if (containsAny(msg, "文件", "目录", "读取", "file", "list", "打开文件", "解析")) matchedCategories.add("file");
        if (containsAny(msg, "时间", "日期", "现在几点", "time", "date", "今天")) matchedCategories.add("time");
        if (containsAny(msg, "定位", "位置", "坐标", "location", "gps", "where")) matchedCategories.add("location");
        if (containsAny(msg, "数据库", "题库", "database", "查询记录", "查一下记录")) matchedCategories.add("data");
        if (containsAny(msg, "图片", "生成图", "image", "画图", "照片", "识别")) matchedCategories.add("image");
        if (containsAny(msg, "应用", "打开", "app", "启动", "软件", "运行", "设备")) matchedCategories.add("app");

        if (matchedCategories.isEmpty()) {
            // 无明确意图：全量注入保证能力
            return getToolDefinitions();
        }
        return registry.getToolDefinitionsByCategories(matchedCategories);
    }

    private static boolean containsAny(String msg, String... keywords) {
        for (String k : keywords) {
            if (msg.contains(k)) return true;
        }
        return false;
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

        // 权限工具的状态是动态的，不应缓存（避免缓存到过期结果）
        boolean isPermissionTool = "permission_manager".equals(toolName);

        // 检查缓存（权限工具跳过缓存）
        String cacheKey = toolName + ":" + arguments;
        String cached = isPermissionTool ? null : resultCache.get(cacheKey);
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

        // 权限请求类工具需要用户交互，使用更长超时
        boolean isPermissionRequest = isPermissionTool && arguments != null
                && (arguments.contains("\"request\"") || arguments.contains("\"request_and_wait\""));
        int effectiveTimeout = isPermissionRequest ? PERMISSION_TOOL_TIMEOUT_MS : TOOL_TIMEOUT_MS;

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
                }).get(effectiveTimeout, TimeUnit.MILLISECONDS);

                if (result == null) {
                    throw new RuntimeException("工具执行返回 null");
                }

                long elapsed = System.currentTimeMillis() - startTime;
                String resultStr = formatResult(result);
                boolean success = result.isSuccess();

                // 缓存成功结果（权限工具不缓存，状态随时变化）
                if (success && !isPermissionTool) {
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
                // 权限请求工具超时后不重试（避免重复弹出权限对话框）
                if (isPermissionRequest) {
                    AILogger.w(TAG, "Permission tool timeout, skip retry");
                    break;
                }
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
        // Map/复杂对象用 Gson 序列化为合法 JSON，避免 Java toString() 产生非 JSON 格式
        String str;
        if (raw instanceof Map || raw instanceof com.google.gson.JsonElement) {
            try {
                str = resultGson.toJson(raw);
            } catch (Exception e) {
                str = raw.toString();
            }
        } else {
            str = raw.toString();
        }
        if (str.length() > RESULT_MAX_LENGTH) {
            str = str.substring(0, RESULT_MAX_LENGTH) + "...(已截断)";
        }
        return str;
    }
}
