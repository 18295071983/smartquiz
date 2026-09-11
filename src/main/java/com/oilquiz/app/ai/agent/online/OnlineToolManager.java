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
    /** 工具结果截断上限：防止超大结果撑爆上下文/请求体（截断后带标记，提示模型分片读取） */
    private static final int RESULT_MAX_LENGTH = 16 * 1024;
    private static final Gson resultGson = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * 只读/幂等工具白名单：结果可安全缓存复用。
     * 写/交互/播放/有副作用工具（app_operation、speech_synthesis、voice_input、file_generator、
     * database 写操作、memory、workspace、image_gen、python_*、excel_tool 写等）一律不缓存，
     * 避免"同参数二次调用被静默跳过"或返回过期数据。
     */
    private static final java.util.Set<String> READONLY_CACHE_TOOLS = new java.util.HashSet<>(java.util.Arrays.asList(
            "ai_weather", "network_search", "webpage_reader", "smart_research",
            "location", "time_date", "calculator", "python_calculate",
            "file_reader", "file_analyzer", "tool_registry", "get_models_profile"
    ));

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
            try (java.io.FileWriter writer = new java.io.FileWriter(cacheFile)) {
                writer.write(root.toString());
            }
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
     * 意图 → 工具组映射（关键词匹配与模型意图识别共用）。
     * 意图名用英文短词，模型识别时输出这些意图名。
     */
    public static java.util.Map<String, java.util.List<String>> getIntentToToolMap() {
        java.util.Map<String, java.util.List<String>> map = new java.util.LinkedHashMap<>();
        map.put("weather", java.util.Arrays.asList("ai_weather"));
        map.put("search", java.util.Arrays.asList("smart_research", "webpage_reader"));
        map.put("file_read", java.util.Arrays.asList("file_reader", "file_analyzer"));
        map.put("file_write", java.util.Arrays.asList("file_generator"));
        map.put("image_gen", java.util.Arrays.asList("image_gen"));
        map.put("image_ocr", java.util.Arrays.asList("file_analyzer"));
        map.put("database", java.util.Arrays.asList("database"));
        map.put("python", java.util.Arrays.asList("python_execute", "python_analyze_data"));
        map.put("calc", java.util.Arrays.asList("python_calculate"));
        map.put("location", java.util.Arrays.asList("location"));
        map.put("time", java.util.Arrays.asList("time_date"));
        map.put("app", java.util.Arrays.asList("app_operation"));
        map.put("system", java.util.Arrays.asList("system_resource"));
        map.put("phone", java.util.Arrays.asList("app_toolkit"));
        map.put("study_plan", java.util.Arrays.asList("file_generator"));
        map.put("import", java.util.Arrays.asList("import_list_files", "import_start", "import_status", "import_cancel")); // AI导入
        return map;
    }

    /**
     * 将意图名集合映射为工具名集合（去重，与 coreTools 合并）。
     */
    private static void addToolsForIntents(java.util.Set<String> include,
                                           java.util.Set<String> intents) {
        if (intents == null || intents.isEmpty()) return;
        java.util.Map<String, java.util.List<String>> map = getIntentToToolMap();
        for (String intent : intents) {
            java.util.List<String> tools = map.get(intent);
            if (tools != null) include.addAll(tools);
        }
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
            // 按用户消息意图追加低频工具（多方向命中：一条消息可命中多个意图，全量注入相关工具）
            if (message != null && !message.trim().isEmpty()) {
                String msg = message.toLowerCase();
                if (containsAny(msg, "天气", "气温", "温度", "预报", "weather", "下雨", "晴天", "摄氏度")) {
                    include.add("ai_weather");
                }
                if (containsAny(msg, "搜索", "查一下", "查找", "最新", "新闻", "油价", "汇率", "search", "news",
                        "find", "读网页", "网页", "调研", "research", "资料", "资讯", "百科")) {
                    include.add("smart_research"); include.add("webpage_reader");
                }
                if (containsAny(msg, "读取文件", "打开文件", "解析文件", "文件内容", "读取", "file_reader", "读文件")) {
                    include.add("file_reader"); include.add("file_analyzer");
                }
                if (containsAny(msg, "生成文件", "写文件", "创建文件", "保存文件", "生成报告", "生成文档",
                        "导出", "report", "生成markdown", "生成md", "生成表格文件", "生成txt", "写入文件")) {
                    include.add("file_generator");
                }
                if (containsAny(msg, "生成图片", "生成图", "画图", "画一张", "image_gen", "图片生成", "绘制")) {
                    include.add("image_gen");
                }
                if (containsAny(msg, "识别图片", "图片里", "照片里", "看图", "ocr", "识别图像")) {
                    include.add("file_analyzer");
                }
                if (containsAny(msg, "数据库", "题库", "题目", "背诵", "测验", "刷题", "database", "records")) {
                    include.add("database");
                }
                if (containsAny(msg, "导入", "题库导入", "导入题目", "导入题库", "import", "导入文件")) {
                    include.add("import_list_files"); include.add("import_start");
                    include.add("import_status"); include.add("import_cancel");
                }
                if (containsAny(msg, "python", "代码", "脚本", "数据分析", "统计数据", "处理数据", "运行程序",
                        "写个程序", "爬虫", "自动化")) {
                    include.add("python_execute"); include.add("python_analyze_data");
                }
                if (containsAny(msg, "计算", "算一下", "数学", "calculator", "等于多少", "加减乘除")) {
                    include.add("python_calculate");
                }
                if (containsAny(msg, "定位", "位置", "坐标", "在哪里", "location", "gps", "附近")) {
                    include.add("location");
                }
                if (containsAny(msg, "现在几点", "当前时间", "今天日期", "今天是", "time", "date")) {
                    include.add("time_date");
                }
                if (containsAny(msg, "打开应用", "启动应用", "打开app", "运行应用", "app操作", "打开软件")) {
                    include.add("app_operation");
                }
                if (containsAny(msg, "系统信息", "设备信息", "内存", "存储空间", "电池", "wifi", "系统资源")) {
                    include.add("system_resource");
                }
                if (containsAny(msg, "打电话", "拨号", "联系人", "发短信", "通讯录", "call", "sms", "contact")) {
                    include.add("app_toolkit");
                }
                if (containsAny(msg, "学习计划", "备考", "复习计划", "学习安排", "考试计划")) {
                    include.add("file_generator");
                }
            }
            return registry.getToolDefinitionsForNames(include);
        } catch (Exception e) {
            AILogger.e(TAG, "getToolDefinitionsForMessageAndCore failed: " + e.getMessage(), e);
            return getToolDefinitionsForNames(coreTools);
        }
    }

    /**
     * 按模型识别的意图名获取工具定义（模型精准识别兜底）。
     * @param intents 模型识别出的意图名集合（weather/search/...）
     * @param coreTools 核心工具集
     */
    public String getToolDefinitionsForIntents(java.util.Collection<String> intents,
                                               java.util.Set<String> coreTools) {
        try {
            java.util.Set<String> include = new java.util.LinkedHashSet<>(coreTools);
            addToolsForIntents(include, new java.util.LinkedHashSet<>(intents));
            AILogger.i(TAG, "Model intent → tools: " + intents + " → " + include.size() + " tools");
            return registry.getToolDefinitionsForNames(include);
        } catch (Exception e) {
            AILogger.e(TAG, "getToolDefinitionsForIntents failed: " + e.getMessage(), e);
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

        // 只读/幂等工具才允许缓存复用；写/交互/播放类工具（app_operation、speech_synthesis、
        // voice_input、file_generator、database 写、memory、workspace、image_gen、python_* 等）
        // 一律不缓存——避免"同参数二次调用被静默跳过"或返回过期数据
        boolean noCacheTool = !READONLY_CACHE_TOOLS.contains(toolName);

        // 检查缓存（只读工具跳过权限/ui_component 等，带 TTL 过期）
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
        // - dashscope_media 文生视频（费用确认弹窗阻塞 20s + 提交短轮询，总耗时可超 30s）
        boolean isPermissionTool = "permission_manager".equals(toolName);
        boolean isPermissionRequest = isPermissionTool && arguments != null
                && (arguments.contains("\"request\"") || arguments.contains("\"request_and_wait\""));
        boolean isUserInteractionWait = "ui_component".equals(toolName)
                && arguments != null && arguments.contains("\"get_result\"");
        boolean isMediaSubmit = "dashscope_media".equals(toolName)
                && arguments != null
                && (arguments.contains("\"video\"") || arguments.contains("\"action\":\"video\"")
                        || arguments.contains("\"action\": \"video\""));
        int effectiveTimeout = (isPermissionRequest || isUserInteractionWait || isMediaSubmit)
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
                // 超时不代表未执行：非只读（有副作用）工具超时后不重试，
                // 避免数据库写入/文件生成/应用操作等被重复执行（只读工具超时重试是安全的）
                if (isPermissionRequest || noCacheTool) {
                    AILogger.w(TAG, "Non-idempotent tool timeout, skip retry: " + toolName);
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
            // 容错：模型生成的 arguments 可能括号不配对/含非法字符，尝试宽松解析。
            // 场景：构造较大 layout/嵌套 JSON 时工具调用参数易出格式错误。
            Map<String, Object> relaxed = tryRelaxedParse(arguments);
            if (relaxed != null) {
                AILogger.d(TAG, "arguments 严格解析失败，已用宽松解析恢复: " + e.getMessage());
                return relaxed;
            }
            AILogger.w(TAG, "Failed to parse arguments: " + arguments + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * 宽松解析工具参数：当严格 JSON 解析失败时尝试恢复。
     * 策略：① 用 Gson 宽松模式（容忍部分不严格语法）；② 去除尾部可见的残缺片段后逐段解析。
     * 若仍失败返回 null（调用方降级为明确错误）。
     */
    private Map<String, Object> tryRelaxedParse(String arguments) {
        try {
            // 策略1：Gson 宽松模式（容忍单引号/未闭合末端等常见模型错误）
            com.google.gson.JsonObject gsonObj = com.google.gson.JsonParser.parseString(arguments).getAsJsonObject();
            Map<String, Object> params = new HashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> en : gsonObj.entrySet()) {
                params.put(en.getKey(), en.getValue().toString());
            }
            return params;
        } catch (Throwable t1) {
            // 策略2：尝试从外层配对中提取每个顶层键值，容忍单个值内部的残缺
            try {
                // 找到最外层的 { ... } 区间
                int start = arguments.indexOf('{');
                int end = arguments.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    String outer = arguments.substring(start, end + 1);
                    JSONObject obj = new JSONObject(outer);
                    Map<String, Object> params = new HashMap<>();
                    Iterator<String> keys = obj.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Object value = obj.get(key);
                        params.put(key, value instanceof JSONObject || value instanceof JSONArray
                                ? value.toString() : value);
                    }
                    return params;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
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
