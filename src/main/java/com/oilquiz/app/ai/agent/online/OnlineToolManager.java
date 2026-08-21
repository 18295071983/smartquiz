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

    /** 全局实例（管理页访问统计/缓存用）；引擎创建时注入，避免重复构建 */
    private static volatile OnlineToolManager instance;

    /** 获取全局实例（不存在则创建） */
    public static OnlineToolManager getInstance(Context context) {
        if (instance == null) {
            synchronized (OnlineToolManager.class) {
                if (instance == null) {
                    instance = new OnlineToolManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /** 由引擎持有并共享（保证统计/缓存单一） */
    public static void setInstance(OnlineToolManager manager) {
        instance = manager;
    }

    // 工具结果缓存（toolName:arguments → 结果），带 TTL 与持久化：
    // 只读工具（天气/搜索/图片等）结果可复用，减少重复请求；权限类动态工具跳过
    private final Map<String, CacheEntry> resultCache = new ConcurrentHashMap<>();
    /** 缓存有效期：5 分钟（天气/搜索等实时数据不宜过长） */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;
    /** 缓存最大条目数（超出淘汰最旧） */
    private static final int CACHE_MAX_ENTRIES = 200;
    private final java.io.File cacheFile;

    public OnlineToolManager(Context context) {
        this.aiToolManager = AIToolManager.getInstance(context);
        this.registry = new OnlineToolRegistry(context);
        this.chain = new OnlineToolChain(registry);
        this.usageTracker = new OnlineToolUsageTracker();
        // 绑定统计持久化文件：共现模式跨重启累积，实现自进化
        this.usageTracker.attachStatsFile(new java.io.File(context.getFilesDir(), "agent_tool_stats.json"));
        this.guide = new OnlineToolGuide(registry, chain, usageTracker);
        this.cacheFile = new java.io.File(context.getFilesDir(), "agent_tool_cache.json");
        loadCache();
    }

    // ==================== 工具结果缓存（TTL + 持久化） ====================

    /** 缓存条目 */
    private static class CacheEntry {
        final String value;
        final long timestamp;

        CacheEntry(String value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }

    /** 读取缓存（过期返回 null） */
    private String getCached(String cacheKey) {
        CacheEntry entry = resultCache.get(cacheKey);
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.timestamp > CACHE_TTL_MS) {
            resultCache.remove(cacheKey);
            return null;
        }
        return entry.value;
    }

    /** 写入缓存（含淘汰与持久化） */
    private void putCached(String cacheKey, String value) {
        if (resultCache.size() >= CACHE_MAX_ENTRIES) {
            // 淘汰最旧（ConcurrentHashMap 无序，随机移除一个）
            String oldest = resultCache.keySet().iterator().next();
            resultCache.remove(oldest);
        }
        resultCache.put(cacheKey, new CacheEntry(value, System.currentTimeMillis()));
        persistCache();
    }

    /** 缓存持久化到磁盘（跨重启保留） */
    private synchronized void persistCache() {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            for (Map.Entry<String, CacheEntry> e : resultCache.entrySet()) {
                org.json.JSONObject entry = new org.json.JSONObject();
                entry.put("v", e.getValue().value);
                entry.put("t", e.getValue().timestamp);
                root.put(e.getKey(), entry);
            }
            java.io.FileWriter writer = new java.io.FileWriter(cacheFile);
            writer.write(root.toString());
            writer.close();
            AILogger.d(TAG, "Tool cache persisted: " + resultCache.size() + " entries");
        } catch (Exception e) {
            AILogger.w(TAG, "Persist tool cache failed: " + e.getMessage());
        }
    }

    /** 启动时从磁盘恢复缓存（跳过过期条目） */
    private void loadCache() {
        try {
            if (!cacheFile.exists()) return;
            java.io.FileReader reader = new java.io.FileReader(cacheFile);
            org.json.JSONObject root = new org.json.JSONObject(new String(
                    readAllBytes(cacheFile), "UTF-8"));
            reader.close();
            java.util.Iterator<String> keys = root.keys();
            long now = System.currentTimeMillis();
            int restored = 0;
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    org.json.JSONObject entry = root.optJSONObject(key);
                    if (entry == null) continue;
                    long ts = entry.optLong("t", 0);
                    if (now - ts <= CACHE_TTL_MS) {
                        resultCache.put(key, new CacheEntry(entry.optString("v", ""), ts));
                        restored++;
                    }
                } catch (Exception ignored) {
                }
            }
            AILogger.i(TAG, "Tool cache restored: " + restored + " entries");
        } catch (Exception e) {
            AILogger.w(TAG, "Load tool cache failed: " + e.getMessage());
        }
    }

    private static byte[] readAllBytes(java.io.File file) throws java.io.IOException {
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        byte[] bytes = new byte[(int) file.length()];
        int read = 0;
        while (read < bytes.length) {
            int r = fis.read(bytes, read, bytes.length - read);
            if (r < 0) break;
            read += r;
        }
        fis.close();
        return bytes;
    }

    // ==================== 工具定义（委托 Registry） ====================

    /**
     * 获取 OpenAI function calling 格式的工具定义 JSON。
     * 委托给 {@link OnlineToolRegistry#getToolDefinitions()}。
     */
    public String getToolDefinitions() {
        return registry.getToolDefinitions();
    }

    /** 按工具名列表获取定义（MCP 式动态扩展：模型经 tool_registry 发现后注入） */
    public String getToolDefinitionsForNames(java.util.Collection<String> names) {
        return registry.getToolDefinitionsForNames(names);
    }

    /**
     * 按用户消息意图获取工具定义（缓存安全的 MCP 式注入）。
     * 固定核心工具集 + 意图匹配追加低频工具：
     * - 核心工具始终包含（顺序固定 → 前缀缓存稳定）
     * - 用户消息明确指向某类任务时追加该类工具（同一次请求的所有 Agent 轮次 tools 相同，
     *   任务内缓存稳定）
     */
    public String getToolDefinitionsForMessageAndCore(String message, java.util.Set<String> coreTools) {
        try {
            java.util.Set<String> include = new java.util.LinkedHashSet<>(coreTools);
            // 按用户消息意图追加低频工具
            if (message != null && !message.trim().isEmpty()) {
                String msg = message.toLowerCase();
                if (containsAny(msg, "天气", "气温", "温度", "预报", "weather")) {
                    include.add("ai_weather");
                }
                if (containsAny(msg, "搜索", "查一下", "查找", "最新", "新闻", "油价", "汇率", "search", "news", "find", "查询", "读网页", "网页", "调研", "research")) {
                    include.add("smart_research");
                    include.add("webpage_reader");
                }
                if (containsAny(msg, "翻译", "translate", "译成", "英文", "日语", "韩语", "翻译成")) {
                    include.add("translation");
                }
                if (containsAny(msg, "文件", "目录", "读取", "打开文件", "解析", "file", "list")) {
                    include.add("file_reader");
                    include.add("file_analyzer");
                }
                if (containsAny(msg, "生成文件", "写文件", "创建文件", "保存文件", "报告", "文档", "导出", "markdown", "md文件")) {
                    include.add("file_generator");
                }
                if (containsAny(msg, "图片", "生成图", "画图", "image", "照片", "识别图片", "图片生成")) {
                    include.add("image_gen");
                }
                if (containsAny(msg, "数据库", "题库", "database", "查询记录")) {
                    include.add("database");
                }
                if (containsAny(msg, "python", "代码", "计算", "脚本", "运行", "数据分析", "统计", "算一下")) {
                    include.add("python_execute");
                    include.add("python_calculate");
                    include.add("python_analyze_data");
                }
                if (containsAny(msg, "定位", "位置", "坐标", "location", "gps")) {
                    include.add("location");
                }
                if (containsAny(msg, "时间", "日期", "现在几点", "time", "date")) {
                    include.add("time_date");
                }
                if (containsAny(msg, "应用", "打开app", "启动", "app操作", "运行应用")) {
                    include.add("app_operation");
                }
                if (containsAny(msg, "电话", "联系人", "短信", "call", "contact", "sms")) {
                    include.add("app_toolkit");
                }
            }
            return registry.getToolDefinitionsForNames(include);
        } catch (Exception e) {
            AILogger.e(TAG, "getToolDefinitionsForMessageAndCore failed: " + e.getMessage(), e);
            return getToolDefinitionsForNames(coreTools);
        }
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
        // ui_component 也不应缓存：create 必须重新执行（注册新组件/返回新 id），
        // get_result 超时后的 "pending" 若被缓存，二次调用会瞬间返回 pending（Bug2/3）
        boolean noCacheTool = isPermissionTool || "ui_component".equals(toolName);

        // 检查缓存（权限/ui_component 工具跳过缓存，带 TTL 过期）
        String cacheKey = toolName + ":" + arguments;
        String cached = noCacheTool ? null : getCached(cacheKey);
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

        // 需要用户交互的工具使用更长超时：
        // - permission_manager 权限请求（用户授权弹窗）
        // - ui_component 的 get_result（阻塞等待用户点击组件按钮/对话框，交互可能持续较久，
        //   30s 默认超时会中断等待导致 Agent"越过交互"直接继续）
        boolean isPermissionRequest = isPermissionTool && arguments != null
                && (arguments.contains("\"request\"") || arguments.contains("\"request_and_wait\""));
        boolean isUserInteractionWait = "ui_component".equals(toolName)
                && arguments != null && arguments.contains("\"get_result\"");
        int effectiveTimeout = (isPermissionRequest || isUserInteractionWait)
                ? PERMISSION_TOOL_TIMEOUT_MS : TOOL_TIMEOUT_MS;

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

                // 缓存成功结果（权限/ui_component 工具不缓存，状态随时变化；带 TTL 过期）
                if (success && !noCacheTool) {
                    putCached(cacheKey, resultStr);
                }

                AILogger.i(TAG, "Tool " + toolName + " completed in " + elapsed + "ms, success=" + success
                    + " attempt=" + attempt);

                // 记录使用链
                String summary = success ? resultStr
                    : (result.getErrorMessage() != null ? result.getErrorMessage() : "未知错误");
                usageTracker.recordCall(toolName, arguments, success, elapsed, summary);
                // 自进化：持久化统计（共现模式跨重启累积）
                usageTracker.persistStats();

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
        if (cacheFile != null && cacheFile.exists()) {
            cacheFile.delete();
        }
        AILogger.i(TAG, "Tool cache cleared");
    }

    /** 当前缓存条目数 */
    public int getCacheSize() {
        return resultCache.size();
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
