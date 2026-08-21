package com.oilquiz.app.ai.agent.online;

import android.content.Context;

import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.openai.ParamDefinition;
import com.oilquiz.app.ai.tool.openai.ToolDefinition;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线工具注册系统 —— 独立于 {@link AIToolManager} 的注册机制。
 *
 * 职责：
 * 1. 从 {@link AIToolManager} 同步工具元数据（名称、描述、参数、类别）
 * 2. 维护工具分类索引（按 category 分组，无 category 时按名称推断）
 * 3. 提供工具发现（按名称/类别/关键词搜索）
 * 4. 支持动态注册在线专用工具（不注册到 AIToolManager）和工具注销（临时禁用）
 * 5. 构建 OpenAI function calling 格式工具定义 JSON（带缓存）
 *
 * 线程安全：所有可变状态使用 {@link ConcurrentHashMap}。
 */
public class OnlineToolRegistry {

    private static final String TAG = "OnlineToolRegistry";

    private final AIToolManager aiToolManager;

    /** 工具元数据索引：toolName → ToolMeta */
    private final Map<String, ToolMeta> toolMetaIndex = new ConcurrentHashMap<>();

    /** 工具分类索引：category → List<toolName>（按注册顺序） */
    private final Map<String, List<String>> categoryIndex = new ConcurrentHashMap<>();

    /** 被禁用（注销）的工具集合 */
    private final Map<String, Boolean> disabledTools = new ConcurrentHashMap<>();

    /** 在线专用工具（不来自 AIToolManager） */
    private final Map<String, ToolMeta> onlineOnlyTools = new ConcurrentHashMap<>();

    /** OpenAI 格式工具定义 JSON 缓存 */
    private volatile String cachedDefinitions;
    private volatile int cachedToolCount = -1;
    private volatile boolean cacheDirty = true;

    public OnlineToolRegistry(Context context) {
        this.aiToolManager = AIToolManager.getInstance(context);
        syncFromAIToolManager();
    }

    // ==================== 同步 ====================

    /**
     * 从 AIToolManager 同步工具元数据。
     * 清空已有索引后重建（保留 onlineOnlyTools 和 disabledTools 状态）。
     */
    public synchronized void syncFromAIToolManager() {
        toolMetaIndex.clear();
        categoryIndex.clear();

        List<Map<String, Object>> descriptions = aiToolManager.getToolDescriptions();
        for (Map<String, Object> desc : descriptions) {
            String name = String.valueOf(desc.get("name"));
            if (name == null || name.isEmpty()) continue;

            ToolDefinition def = aiToolManager.getToolDefinition(name);
            String description = def != null ? def.getDescription() : String.valueOf(desc.get("description"));
            String category = inferCategory(def, name);

            ToolMeta meta = new ToolMeta(name, description, category, def, false);
            toolMetaIndex.put(name, meta);
            categoryIndex.computeIfAbsent(category, k -> Collections.synchronizedList(new ArrayList<>())).add(name);
        }

        // 合并在线专用工具
        for (ToolMeta meta : onlineOnlyTools.values()) {
            toolMetaIndex.put(meta.name, meta);
            categoryIndex.computeIfAbsent(meta.category, k -> Collections.synchronizedList(new ArrayList<>())).add(meta.name);
        }

        markCacheDirty();
        AILogger.i(TAG, "Synced " + toolMetaIndex.size() + " tools from AIToolManager");
    }

    /**
     * 推断工具类别。优先使用 ToolDefinition.category，若为 null/empty/general 则按名称推断。
     */
    private String inferCategory(ToolDefinition def, String toolName) {
        String category = def != null ? def.getCategory() : null;
        if (category != null && !category.isEmpty() && !"general".equalsIgnoreCase(category)) {
            return category;
        }
        // 按名称推断
        String name = toolName.toLowerCase();
        if (name.contains("weather")) return "weather";
        if (name.contains("search") || name.contains("research") || name.contains("webpage") || name.contains("web_page")) return "search";
        if (name.startsWith("file") || name.contains("file_")) return "file";
        if (name.startsWith("python")) return "code";
        if (name.contains("translat")) return "translation";
        if (name.contains("location")) return "location";
        if (name.contains("database")) return "database";
        if (name.contains("permission")) return "system";
        if (name.contains("system_resource") || name.contains("app_operation")) return "system";
        if (name.contains("app_toolkit")) return "toolkit";
        if (name.contains("create_tool") || name.contains("dynamic_tool")) return "meta";
        return "general";
    }

    // ==================== 动态注册/注销 ====================

    /**
     * 动态注册一个在线专用工具（不注册到 AIToolManager）。
     */
    public synchronized boolean registerTool(String name, String description, String category, ToolDefinition def) {
        if (name == null || name.isEmpty()) return false;
        if (toolMetaIndex.containsKey(name) && !onlineOnlyTools.containsKey(name)) {
            AILogger.w(TAG, "Tool already exists from AIToolManager: " + name);
            return false;
        }
        String cat = (category == null || category.isEmpty()) ? inferCategory(def, name) : category;
        ToolMeta meta = new ToolMeta(name, description, cat, def, true);
        onlineOnlyTools.put(name, meta);
        toolMetaIndex.put(name, meta);
        categoryIndex.computeIfAbsent(cat, k -> Collections.synchronizedList(new ArrayList<>())).add(name);
        disabledTools.remove(name);
        markCacheDirty();
        AILogger.i(TAG, "Registered online-only tool: " + name);
        return true;
    }

    /**
     * 注销（禁用）工具。对 AIToolManager 来源的工具仅标记禁用，对在线专用工具则移除。
     */
    public synchronized boolean unregisterTool(String name) {
        if (!toolMetaIndex.containsKey(name)) return false;
        ToolMeta meta = toolMetaIndex.get(name);
        if (meta.onlineOnly) {
            onlineOnlyTools.remove(name);
            toolMetaIndex.remove(name);
            removeCategoryEntry(name, meta.category);
        } else {
            disabledTools.put(name, true);
        }
        markCacheDirty();
        AILogger.i(TAG, "Unregistered tool: " + name + " (onlineOnly=" + meta.onlineOnly + ")");
        return true;
    }

    /**
     * 重新启用被禁用的工具。
     */
    public synchronized boolean enableTool(String name) {
        if (disabledTools.remove(name) != null) {
            markCacheDirty();
            return true;
        }
        return false;
    }

    /** 工具是否启用（存在且未被禁用） */
    public boolean isToolEnabled(String name) {
        return toolMetaIndex.containsKey(name) && !disabledTools.containsKey(name);
    }

    /** 工具是否存在（含被禁用的） */
    public boolean hasTool(String name) {
        return toolMetaIndex.containsKey(name);
    }

    private void removeCategoryEntry(String toolName, String category) {
        List<String> list = categoryIndex.get(category);
        if (list != null) list.remove(toolName);
    }

    private void markCacheDirty() {
        cacheDirty = true;
    }

    // ==================== 查询 ====================

    /** 获取工具元数据 */
    public ToolMeta getToolMeta(String name) {
        return toolMetaIndex.get(name);
    }

    /** 获取所有启用工具的名称（按类别分组） */
    public Map<String, List<String>> getCategoryIndex() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : categoryIndex.entrySet()) {
            List<String> enabled = new ArrayList<>();
            for (String name : e.getValue()) {
                if (isToolEnabled(name)) enabled.add(name);
            }
            if (!enabled.isEmpty()) result.put(e.getKey(), enabled);
        }
        return result;
    }

    /** 获取所有启用工具的元数据（按名称排序，保证 JSON 序列化顺序稳定，避免破坏前缀缓存命中） */
    public Collection<ToolMeta> getAllToolMetas() {
        List<ToolMeta> result = new ArrayList<>();
        for (ToolMeta meta : toolMetaIndex.values()) {
            if (isToolEnabled(meta.name)) result.add(meta);
        }
        // 确定性排序：ConcurrentHashMap 迭代顺序不稳定，同批工具每次构建 tools JSON 顺序必须一致，
        // 否则 tools 参数字节变化 → 前缀缓存 miss
        Collections.sort(result, (a, b) -> a.name.compareTo(b.name));
        return result;
    }

    /** 启用工具数量 */
    public int getEnabledToolCount() {
        int count = 0;
        for (String name : toolMetaIndex.keySet()) {
            if (isToolEnabled(name)) count++;
        }
        return count;
    }

    // ---------- 工具发现 ----------

    /** 按名称搜索（前缀 + 包含匹配） */
    public List<ToolMeta> searchByName(String query) {
        List<ToolMeta> result = new ArrayList<>();
        if (query == null || query.isEmpty()) return result;
        String q = query.toLowerCase();
        for (ToolMeta meta : toolMetaIndex.values()) {
            if (!isToolEnabled(meta.name)) continue;
            if (meta.name.toLowerCase().contains(q)) result.add(meta);
        }
        return result;
    }

    /** 按类别搜索 */
    public List<ToolMeta> searchByCategory(String category) {
        List<ToolMeta> result = new ArrayList<>();
        if (category == null) return result;
        List<String> names = categoryIndex.get(category);
        if (names == null) return result;
        for (String name : names) {
            if (isToolEnabled(name)) {
                ToolMeta meta = toolMetaIndex.get(name);
                if (meta != null) result.add(meta);
            }
        }
        return result;
    }

    /** 按关键词搜索（匹配名称或描述） */
    public List<ToolMeta> searchByKeyword(String keyword) {
        List<ToolMeta> result = new ArrayList<>();
        if (keyword == null || keyword.isEmpty()) return result;
        String q = keyword.toLowerCase();
        for (ToolMeta meta : toolMetaIndex.values()) {
            if (!isToolEnabled(meta.name)) continue;
            if (meta.name.toLowerCase().contains(q)
                || (meta.description != null && meta.description.toLowerCase().contains(q))) {
                result.add(meta);
            }
        }
        return result;
    }

    // ==================== OpenAI 格式工具定义 ====================

    /**
     * 构建 OpenAI function calling 格式工具定义 JSON（带缓存）。
     * 工具数量变化或缓存脏时重建。
     */
    public String getToolDefinitions() {
        int currentCount = getEnabledToolCount();
        if (!cacheDirty && cachedDefinitions != null && currentCount == cachedToolCount) {
            return cachedDefinitions;
        }

        try {
            JSONArray tools = new JSONArray();
            for (ToolMeta meta : getAllToolMetas()) {
                JSONObject tool = buildToolJsonObject(meta);
                if (tool != null) tools.put(tool);
            }
            cachedDefinitions = tools.toString();
            cachedToolCount = currentCount;
            cacheDirty = false;
            AILogger.i(TAG, "Built " + tools.length() + " tool definitions");
            return cachedDefinitions;
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to build tool definitions: " + e.getMessage(), e);
            return cachedDefinitions != null ? cachedDefinitions : "[]";
        }
    }

    /**
     * 按类别子集构建工具定义 JSON（省 token）。
     * 只包含指定类别 + 始终保留的基础类别（file/database 等通用能力）。
     */
    public String getToolDefinitionsByCategories(java.util.Set<String> categories) {
        if (categories == null || categories.isEmpty()) return getToolDefinitions();
        try {
            // 基础类别始终包含（通用能力，避免模型无法处理文件/查询）
            java.util.Set<String> include = new java.util.LinkedHashSet<>(categories);
            include.add("file");
            include.add("data");
            include.add("general");
            include.add("meta");
            include.add("toolkit");
            include.add("system");
            include.add("app");
            include.add("tool");

            // 类别细分映射：搜索意图包含 search/research/web；python 归 code 类
            if (include.contains("search")) {
                include.add("research");
                include.add("web");
            }
            if (include.contains("code")) {
                include.add("python");
            }
            if (include.contains("image")) {
                include.add("image");
            }
            if (include.contains("time")) {
                include.add("time");
            }

            JSONArray tools = new JSONArray();
            for (ToolMeta meta : getAllToolMetas()) {
                if (include.contains(meta.category)) {
                    JSONObject tool = buildToolJsonObject(meta);
                    if (tool != null) tools.put(tool);
                }
            }
            AILogger.i(TAG, "Built subset tool definitions: " + tools.length() + " tools for categories " + include);
            return tools.toString();
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to build subset tool definitions: " + e.getMessage(), e);
            return getToolDefinitions();
        }
    }

    /**
     * 按工具名列表构建工具定义 JSON（MCP 式动态扩展用）。
     * 只包含指定名称的工具；名称不存在/被禁用时跳过。
     */
    public String getToolDefinitionsForNames(java.util.Collection<String> names) {
        if (names == null || names.isEmpty()) return "[]";
        try {
            JSONArray tools = new JSONArray();
            for (String name : names) {
                ToolMeta meta = toolMetaIndex.get(name);
                if (meta == null || !isToolEnabled(name)) continue;
                JSONObject tool = buildToolJsonObject(meta);
                if (tool != null) tools.put(tool);
            }
            AILogger.i(TAG, "Built tool definitions for names: " + tools.length() + "/" + names.size());
            return tools.toString();
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to build named tool definitions: " + e.getMessage(), e);
            return "[]";
        }
    }

    private JSONObject buildToolJsonObject(ToolMeta meta) throws JSONException {
        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        JSONObject function = new JSONObject();
        function.put("name", meta.name);
        function.put("description", meta.description != null ? meta.description : "");

        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();

        if (meta.definition != null && meta.definition.getParameters() != null) {
            for (ParamDefinition param : meta.definition.getParameters()) {
                JSONObject prop = new JSONObject();
                String pType = param.getType();
                if (pType == null || pType.isEmpty()) pType = "string";
                prop.put("type", pType);
                prop.put("description", param.getDescription() != null ? param.getDescription() : "");
                if (param.getDefaultValue() != null) {
                    prop.put("default", param.getDefaultValue());
                }
                if (param.getEnumValues() != null && !param.getEnumValues().isEmpty()) {
                    JSONArray enumArray = new JSONArray();
                    for (String enumVal : param.getEnumValues()) enumArray.put(enumVal);
                    prop.put("enum", enumArray);
                }
                properties.put(param.getName(), prop);
                if (param.isRequired()) required.put(param.getName());
            }
        }
        parameters.put("properties", properties);
        if (required.length() > 0) parameters.put("required", required);
        function.put("parameters", parameters);

        tool.put("function", function);
        return tool;
    }

    // ==================== 工具元数据 ====================

    /**
     * 工具元数据。
     */
    public static class ToolMeta {
        public final String name;
        public final String description;
        public final String category;
        public final ToolDefinition definition;
        public final boolean onlineOnly;

        public ToolMeta(String name, String description, String category,
                        ToolDefinition definition, boolean onlineOnly) {
            this.name = name;
            this.description = description;
            this.category = category;
            this.definition = definition;
            this.onlineOnly = onlineOnly;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("description", description);
            m.put("category", category);
            Map<String, String> params = new LinkedHashMap<>();
            if (definition != null && definition.getParameters() != null) {
                for (ParamDefinition p : definition.getParameters()) {
                    StringBuilder desc = new StringBuilder(p.getDescription() != null ? p.getDescription() : "");
                    if (p.isRequired()) desc.append("(必填)");
                    if (p.getDefaultValue() != null) desc.append("(默认:").append(p.getDefaultValue()).append(")");
                    params.put(p.getName(), desc.toString());
                }
            }
            m.put("parameters", params);
            return m;
        }
    }
}
