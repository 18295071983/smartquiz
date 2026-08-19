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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    /** 重试退避基数（200ms → 400ms → …封顶 2s），避免失败风暴下立即重打 */
    private static final long RETRY_BACKOFF_BASE_MS = 200;
    private static final long RETRY_BACKOFF_MAX_MS = 2_000;
    /** 单次执行总预算 = 2×单次超时（初始尝试 + 一次完整重试），防止重试无上限阻塞 */
    private static final int TOTAL_BUDGET_TIMEOUT_MULTIPLIER = 2;
    /** 缓存值最大长度（仅限制入缓存副本，回传给 LLM 的结果保持完整） */
    private static final int CACHE_VALUE_MAX_LENGTH = 64 * 1024;
    // 不再截断工具返回结果，保证数据完整性
    private static final int RESULT_MAX_LENGTH = Integer.MAX_VALUE;
    /** 工具执行专用线程池：命名 + daemon，避免 Android 上 CompletableFuture 每任务新建线程/线程泄漏 */
    private static final ExecutorService TOOL_EXECUTOR = Executors.newFixedThreadPool(4,
            new java.util.concurrent.ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger seq =
                        new java.util.concurrent.atomic.AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "online-tool-executor-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });
    private static final Gson resultGson = new GsonBuilder().disableHtmlEscaping().create();

    private final AIToolManager aiToolManager;
    private final OnlineToolRegistry registry;
    private final OnlineToolChain chain;
    private final OnlineToolUsageTracker usageTracker;
    private final OnlineToolGuide guide;

    /** 上次观察到的共现模式版本（共现变化时刷新工具指南缓存，自进化经验即时生效） */
    private volatile long lastPatternVersion;

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

    // 工具结果缓存（toolName:canonicalArguments → 结果），带 TTL 与持久化：
    // 仅缓存白名单内确定性只读工具（calculator/tool_registry），权限类/易变/用户相关工具跳过
    // accessOrder=true：get/put 后按最近访问排序，淘汰时移除迭代首位 = 最久未使用（真 LRU）
    private final Map<String, CacheEntry> resultCache =
            Collections.synchronizedMap(new LinkedHashMap<String, CacheEntry>(16, 0.75f, true));
    /** 缓存有效期：5 分钟 */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;
    /** 缓存最大条目数（超出淘汰最久未使用） */
    private static final int CACHE_MAX_ENTRIES = 200;

    /**
     * 可缓存工具白名单：仅缓存"纯只读、结果确定、与用户/时间无关"的工具。
     * 排除 time_date/location/天气/搜索/数据库查询/文件等易变或用户相关结果——
     * 避免跨会话串数据（隐私）与过期结果（如"现在几点"返回 5 分钟前的时间）。
     */
    private static final java.util.Set<String> CACHEABLE_TOOLS = new java.util.HashSet<>(java.util.Arrays.asList(
            "calculator", "tool_registry"
    ));

    /** 工具结果是否可缓存（确定性只读工具） */
    private boolean isCacheableTool(String toolName) {
        return toolName != null && CACHEABLE_TOOLS.contains(toolName);
    }

    /**
     * 工具是否有副作用（写文件/发消息/导入/删除/权限请求等）。
     * 副作用工具重试会重复执行副作用（重复写文件/重复发送），不重试。
     */
    private boolean hasSideEffects(String toolName) {
        if (toolName == null) return false;
        String n = toolName.toLowerCase();
        return n.contains("permission")
                || n.contains("write") || n.contains("save") || n.contains("create")
                || n.contains("delete") || n.contains("remove") || n.contains("send")
                || n.contains("import") || n.contains("export") || n.contains("add_")
                || n.contains("clear") || n.contains("update") || n.contains("crop")
                || n.contains("scale") || n.contains("rotate") || n.contains("_set")
                || n.equals("memory") || n.equals("app_operation")
                || n.equals("create_dynamic_tool") || n.equals("ai_create_tool")
                || n.equals("file_generator") || n.equals("image_gen");
    }

    private final java.io.File cacheFile;

    public OnlineToolManager(Context context) {
        this.aiToolManager = AIToolManager.getInstance(context);
        this.registry = new OnlineToolRegistry(context);
        this.chain = new OnlineToolChain(registry);
        this.usageTracker = new OnlineToolUsageTracker();
        // 绑定统计持久化文件：共现模式跨重启累积，实现自进化
        this.usageTracker.attachStatsFile(new java.io.File(context.getFilesDir(), "agent_tool_stats.json"));
        this.guide = new OnlineToolGuide(registry, chain, usageTracker);
        // 记录当前共现模式版本，供工具执行后检测变化并刷新指南缓存
        this.lastPatternVersion = usageTracker.getPatternVersion();
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

    /** 读取缓存（过期返回 null；命中即更新 LRU 访问序） */
    private String getCached(String cacheKey) {
        synchronized (resultCache) {
            CacheEntry entry = resultCache.get(cacheKey);
            if (entry == null) return null;
            if (System.currentTimeMillis() - entry.timestamp > CACHE_TTL_MS) {
                resultCache.remove(cacheKey);
                return null;
            }
            return entry.value;
        }
    }

    /** 写入缓存（值超限截断、LRU 淘汰、原子持久化） */
    private void putCached(String cacheKey, String value) {
        // 仅限制入缓存副本的大小，避免超大结果占满内存/落盘膨胀
        if (value != null && value.length() > CACHE_VALUE_MAX_LENGTH) {
            value = value.substring(0, CACHE_VALUE_MAX_LENGTH) + "...(缓存截断)";
        }
        synchronized (resultCache) {
            if (resultCache.size() >= CACHE_MAX_ENTRIES && !resultCache.containsKey(cacheKey)) {
                // 真 LRU：accessOrder 下迭代首位即最久未使用
                Iterator<String> it = resultCache.keySet().iterator();
                if (it.hasNext()) resultCache.remove(it.next());
            }
            resultCache.put(cacheKey, new CacheEntry(value, System.currentTimeMillis()));
        }
        persistCache();
    }

    /** 缓存持久化到磁盘（跨重启保留；先写临时文件再 rename，避免崩溃产生损坏文件） */
    private synchronized void persistCache() {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            synchronized (resultCache) {
                for (Map.Entry<String, CacheEntry> e : resultCache.entrySet()) {
                    org.json.JSONObject entry = new org.json.JSONObject();
                    entry.put("v", e.getValue().value);
                    entry.put("t", e.getValue().timestamp);
                    root.put(e.getKey(), entry);
                }
            }
            java.io.File tmp = new java.io.File(cacheFile.getAbsolutePath() + ".tmp");
            java.io.FileWriter writer = new java.io.FileWriter(tmp);
            writer.write(root.toString());
            writer.close();
            if (!tmp.renameTo(cacheFile)) {
                // rename 失败（极少数情况）回退直接覆盖
                java.io.FileWriter direct = new java.io.FileWriter(cacheFile);
                direct.write(root.toString());
                direct.close();
                tmp.delete();
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
                    synchronized (resultCache) {
                        if (now - ts <= CACHE_TTL_MS) {
                            resultCache.put(key, new CacheEntry(entry.optString("v", ""), ts));
                            restored++;
                        }
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

    /**
     * 按用户消息意图获取工具定义子集（省 token）。
     * 用关键词匹配工具类别，只注入相关工具：
     * - 无关键词命中 → 全量（保证能力完整）
     * - 命中 → 相关类别 + 基础类别（file/database/general 视场景）
     * 大幅减少每轮推理的工具定义 token（20+ 工具全量定义可达数千 token）。
     */
    public String getToolDefinitionsForMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            com.oilquiz.app.util.AILogger.i(TAG, "Tool subset: empty message → minimal");
            return getMinimalToolDefinitions();
        }
        String msg = message.toLowerCase();
        java.util.Set<String> matchedCategories = new java.util.LinkedHashSet<>();
        if (containsAny(msg, "天气", "气温", "温度", "预报", "weather")) matchedCategories.add("weather");
        if (containsAny(msg, "搜索", "查一下", "查找", "最新", "新闻", "油价", "汇率", "search", "news", "find", "查询", "读网页", "网页")) matchedCategories.add("search");
        if (containsAny(msg, "翻译", "translate", "译成", "英文", "日语", "韩语")) matchedCategories.add("translation");
        if (containsAny(msg, "计算", "算一下", "calculator", "calculate", "math", "加减乘除")) matchedCategories.add("calculator");
        if (containsAny(msg, "文件", "目录", "读取", "file", "list", "打开文件", "解析", "保存", "生成文件", "写文件")) matchedCategories.add("file");
        if (containsAny(msg, "时间", "日期", "现在几点", "time", "date", "今天", "星期")) matchedCategories.add("time");
        if (containsAny(msg, "定位", "位置", "坐标", "location", "gps", "where", "在哪")) matchedCategories.add("location");
        if (containsAny(msg, "数据库", "题库", "database", "查询记录", "查一下记录", "题目", "导入题库")) matchedCategories.add("data");
        if (containsAny(msg, "图片", "生成图", "image", "画图", "照片", "识别", "图像")) matchedCategories.add("image");
        if (containsAny(msg, "应用", "打开", "app", "启动", "软件", "运行", "设备", "页面", "界面")) matchedCategories.add("app");
        if (containsAny(msg, "组件", "弹窗", "对话框", "进度条", "确认", "ui", "卡片", "显示信息", "输入框", "选择", "通知", "提示条")) matchedCategories.add("system");
        if (containsAny(msg, "python", "代码", "脚本", "执行", "程序", "数据分析", "统计")) matchedCategories.add("code");
        if (containsAny(msg, "记忆", "记住", "memory", "忘记", "回忆")) matchedCategories.add("memory");
        if (containsAny(msg, "生成图片", "图片生成", "image_gen", "画")) matchedCategories.add("image");
        if (containsAny(msg, "网页", "网址", "url", "链接", "web", "html")) matchedCategories.add("web");
        if (containsAny(msg, "工具", "tool", "schema", "有哪些", "能不能", "能力")) matchedCategories.add("meta");

        if (matchedCategories.isEmpty()) {
            // 无明确意图：注入核心最小集（file + tool_registry + 通用），避免全量 30 工具
            com.oilquiz.app.util.AILogger.i(TAG, "Tool subset: no intent matched → minimal, msg="
                    + (msg.length() > 40 ? msg.substring(0, 40) + "…" : msg));
            return getMinimalToolDefinitions();
        }
        com.oilquiz.app.util.AILogger.i(TAG, "Tool subset: categories=" + matchedCategories + " (from msg)");
        return registry.getToolDefinitionsByCategories(matchedCategories);
    }

    /**
     * 最小工具集（MCP 式兜底）：无明确意图时只注入核心工具，
     * 模型可先调 tool_registry 发现其他工具，避免全量定义浪费 token。
     * 包含：tool_registry（发现）、file（读写文件）、calculator（计算）、time_date（时间）。
     */
    public String getMinimalToolDefinitions() {
        try {
            java.util.Set<String> minimal = new java.util.LinkedHashSet<>(
                    java.util.Arrays.asList("tool_registry", "file", "calculator", "time_date"));
            String defs = registry.getToolDefinitionsByName(minimal);
            com.oilquiz.app.util.AILogger.i(TAG, "Tool minimal set: " + defs + " (len=" + (defs != null ? defs.length() : 0) + ")");
            return defs;
        } catch (Exception e) {
            return registry.getToolDefinitions();
        }
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

        // 检查缓存：仅确定性只读工具可缓存（calculator/tool_registry），
        // 排除 time_date/location/天气/搜索/数据库等易变或用户相关结果（隐私+过期）
        boolean cacheable = isCacheableTool(toolName);
        // 规范化缓存键仅对可缓存工具计算，避免每次执行都做一次 JSON 重解析（性能）
        String cacheKey = cacheable ? buildCacheKey(toolName, arguments) : null;
        String cached = cacheable ? getCached(cacheKey) : null;
        if (cached != null) {
            AILogger.d(TAG, "Tool cache HIT: " + toolName);
            long elapsed = 0;
            usageTracker.recordCall(toolName, arguments, true, elapsed, cached, TOOL_TIMEOUT_MS);
            return OnlineToolResult.success(toolCallId, toolName, cached, elapsed);
        }

        // 解析参数
        Map<String, Object> params = parseArguments(arguments);
        if (params == null) {
            String error = "参数解析失败: " + arguments;
            usageTracker.recordCall(toolName, arguments, false,
                System.currentTimeMillis() - startTime, error, TOOL_TIMEOUT_MS);
            return OnlineToolResult.failure(toolCallId, toolName, error,
                System.currentTimeMillis() - startTime);
        }

        // 权限请求类工具需要用户交互，使用更长超时
        boolean isPermissionTool = "permission_manager".equals(toolName);
        boolean isPermissionRequest = isPermissionTool && arguments != null
                && (arguments.contains("\"request\"") || arguments.contains("\"request_and_wait\""));
        int effectiveTimeout = isPermissionRequest ? PERMISSION_TOOL_TIMEOUT_MS : TOOL_TIMEOUT_MS;
        // 单次执行总预算 = 2×单次超时（初始尝试 + 一次完整重试），重试不再各自重置超时预算
        long totalBudgetMs = (long) effectiveTimeout * TOTAL_BUDGET_TIMEOUT_MULTIPLIER;

        // 带重试的执行
        Exception lastException = null;
        // 副作用工具（写文件/发消息/导入/删除等）重试会重复执行副作用，不重试
        boolean retryable = !hasSideEffects(toolName);
        int maxAttempts = retryable ? MAX_RETRY : 0;
        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            // 总预算控制：剩余预算耗尽则终止，避免无上限阻塞
            long remainingBudget = totalBudgetMs - (System.currentTimeMillis() - startTime);
            if (remainingBudget <= 0) {
                if (lastException == null) {
                    lastException = new RuntimeException("工具执行超过总预算 " + totalBudgetMs + "ms");
                }
                break;
            }
            long attemptTimeout = Math.min(effectiveTimeout, remainingBudget);
            final CompletableFuture<AIToolResult>[] futureHolder = new CompletableFuture[1];
            try {
                final int currentAttempt = attempt;
                futureHolder[0] = CompletableFuture.supplyAsync(() -> {
                    try {
                        return aiToolManager.executeTool(toolName, params);
                    } catch (Exception e) {
                        AILogger.e(TAG, "Tool execution error (attempt " + currentAttempt + "): " + e.getMessage(), e);
                        // 保留真实异常信息（原实现吞掉后用户只见"工具执行返回 null"）
                        throw new RuntimeException("工具执行异常: "
                                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
                    }
                }, TOOL_EXECUTOR);
                AIToolResult result;
                try {
                    result = futureHolder[0].get(attemptTimeout, TimeUnit.MILLISECONDS);
                } catch (ExecutionException e) {
                    // 解包异步异常，把真实原因透传给上层
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    throw new RuntimeException("工具执行失败: "
                            + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()), cause);
                }

                if (result == null) {
                    throw new RuntimeException("工具执行返回 null");
                }

                long elapsed = System.currentTimeMillis() - startTime;
                String resultStr = formatResult(result);
                boolean success = result.isSuccess();

                // 缓存成功结果（仅确定性只读工具可缓存）
                if (success && cacheable) {
                    putCached(cacheKey, resultStr);
                }

                AILogger.i(TAG, "Tool " + toolName + " completed in " + elapsed + "ms, success=" + success
                    + " attempt=" + attempt);

                // 记录使用链
                String summary = success ? resultStr
                    : (result.getErrorMessage() != null ? result.getErrorMessage() : "未知错误");
                usageTracker.recordCall(toolName, arguments, success, elapsed, summary, effectiveTimeout);
                // 自进化：持久化统计（共现模式跨重启累积）+ 共现变化时刷新指南缓存
                usageTracker.persistStats();
                afterUsageRecorded();

                if (success) {
                    return OnlineToolResult.success(toolCallId, toolName, resultStr, elapsed);
                } else {
                    // 工具执行失败（非异常），不重试
                    String error = result.getErrorMessage() != null ? result.getErrorMessage() : "未知错误";
                    return OnlineToolResult.failure(toolCallId, toolName, error, elapsed);
                }

            } catch (TimeoutException e) {
                AILogger.w(TAG, "Tool " + toolName + " timeout (attempt " + attempt + ")");
                // 取消超时的 future：避免底层线程继续运行导致副作用重复/线程泄漏
                if (futureHolder[0] != null) {
                    futureHolder[0].cancel(true);
                }
                lastException = e;
                // 权限请求/副作用工具超时后不重试（避免重复弹权限框/重复执行副作用）
                if (isPermissionRequest || !retryable) {
                    AILogger.w(TAG, "Tool timeout, skip retry (permission/side-effect): " + toolName);
                    break;
                }
                if (attempt < maxAttempts) sleepBackoff(attempt);
            } catch (InterruptedException e) {
                // 中断不再当作普通异常重试：恢复中断位并终止
                Thread.currentThread().interrupt();
                lastException = e;
                break;
            } catch (Exception e) {
                AILogger.w(TAG, "Tool " + toolName + " error (attempt " + attempt + "): " + e.getMessage());
                lastException = e;
                // 异常同样取消 future（若已启动）
                if (futureHolder[0] != null) {
                    futureHolder[0].cancel(true);
                }
                if (attempt < maxAttempts) sleepBackoff(attempt);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        String errorMsg = lastException != null ? lastException.getMessage() : "工具执行失败";
        usageTracker.recordCall(toolName, arguments, false, elapsed, errorMsg, effectiveTimeout);
        return OnlineToolResult.failure(toolCallId, toolName, errorMsg, elapsed);
    }

    // ==================== 工具描述查询（委托 Guide/Registry） ====================

    /**
     * 获取单个工具的描述（供提示词使用）。委托给 {@link OnlineToolGuide#buildToolDetail}。
     */
    public String getToolDescription(String toolName) {
        return guide.buildToolDetail(toolName);
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
        // 统计清空后，指南中的"经验提示"需重建
        guide.markCacheDirty();
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
                params.put(key, toJavaValue(json.get(key)));
            }
            return params;
        } catch (JSONException e) {
            AILogger.w(TAG, "Failed to parse arguments: " + arguments + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * JSON 节点递归转 Java 值：嵌套对象/数组保留结构（Map/List），
     * 而非 toString 拍平成字符串（避免依赖 Map/List 的工 ClassCastException）。
     */
    private static Object toJavaValue(Object value) {
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            Map<String, Object> map = new HashMap<>();
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                map.put(k, toJavaValue(obj.opt(k)));
            }
            return map;
        } else if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                list.add(toJavaValue(arr.opt(i)));
            }
            return list;
        } else if (value == JSONObject.NULL) {
            return null;
        }
        return value;
    }

    /** 构建缓存键：参数做规范化（键排序）后拼接，避免同一语义不同键序/空白导致缓存不命中 */
    private static String buildCacheKey(String toolName, String arguments) {
        return toolName + ":" + canonicalizeArguments(arguments);
    }

    /** 参数规范化：递归按键字典序重排 JSON，输出确定性字符串 */
    private static String canonicalizeArguments(String arguments) {
        if (arguments == null || arguments.isEmpty() || "null".equals(arguments)) return "";
        try {
            StringBuilder sb = new StringBuilder();
            appendCanonical(sb, new JSONObject(arguments));
            return sb.toString();
        } catch (JSONException e) {
            // 解析失败回退原始串（缓存键仍可用，仅命中率降低）
            return arguments == null ? "" : arguments;
        }
    }

    private static void appendCanonical(StringBuilder sb, Object node) throws JSONException {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            java.util.TreeSet<String> keys = new java.util.TreeSet<>();
            java.util.Iterator<String> it = obj.keys();
            while (it.hasNext()) keys.add(it.next());
            sb.append('{');
            boolean first = true;
            for (String k : keys) {
                if (!first) sb.append(',');
                first = false;
                sb.append(JSONObject.quote(k)).append(':');
                appendCanonical(sb, obj.get(k));
            }
            sb.append('}');
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            sb.append('[');
            for (int i = 0; i < arr.length(); i++) {
                if (i > 0) sb.append(',');
                appendCanonical(sb, arr.get(i));
            }
            sb.append(']');
        } else if (node == JSONObject.NULL) {
            sb.append("null");
        } else if (node instanceof String) {
            sb.append(JSONObject.quote((String) node));
        } else {
            // Number/Boolean 等标量
            sb.append(node.toString());
        }
    }

    /** 重试退避：200ms → 400ms → …封顶 2s；被中断则恢复中断位 */
    private static void sleepBackoff(int attempt) {
        long backoff = Math.min(RETRY_BACKOFF_MAX_MS, RETRY_BACKOFF_BASE_MS << Math.min(attempt, 8));
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** 使用链共现版本变化时刷新工具指南缓存（自进化经验即时生效） */
    private void afterUsageRecorded() {
        long v = usageTracker.getPatternVersion();
        if (v != lastPatternVersion) {
            lastPatternVersion = v;
            guide.markCacheDirty();
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
