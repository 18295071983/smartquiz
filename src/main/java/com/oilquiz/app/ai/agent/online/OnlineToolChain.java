package com.oilquiz.app.ai.agent.online;

import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线工具调用链 —— 独立于 {@code UnifiedAgentEngine.TOOL_FALLBACK_MAP}。
 *
 * 维护四种链类型：
 * 1. 依赖链：工具执行前的前置依赖（如 ai_weather 缺少 city 时可先调用 location）
 * 2. 回退链：工具失败时的替代工具推荐（如 ai_weather 失败 → location / network_search）
 * 3. 组合链：工具组合使用建议（如 network_search → webpage_reader / smart_research）
 * 4. 并行链：可并行执行的工具组识别
 *
 * 链定义支持动态配置（addChain / removeChain），不硬编码。
 * 所有返回结果均经过 {@link OnlineToolRegistry} 验证工具确实存在。
 */
public class OnlineToolChain {

    private static final String TAG = "OnlineToolChain";

    /** 链类型 */
    public enum ChainType {
        DEPENDENCY("依赖链"),
        FALLBACK("回退链"),
        COMBINATION("组合链"),
        PARALLEL("并行链");

        public final String displayName;
        ChainType(String displayName) { this.displayName = displayName; }
    }

    private final OnlineToolRegistry registry;

    /** 链存储：ChainType → (toolName → relatedTools) */
    private final Map<ChainType, Map<String, List<String>>> chains = new ConcurrentHashMap<>();

    public OnlineToolChain(OnlineToolRegistry registry) {
        this.registry = registry;
        for (ChainType type : ChainType.values()) {
            chains.put(type, new ConcurrentHashMap<>());
        }
        initDefaultChains();
    }

    // ==================== 默认链初始化（从 TOOL_FALLBACK_MAP 迁移） ====================

    private void initDefaultChains() {
        // 回退链（从 UnifiedAgentEngine.TOOL_FALLBACK_MAP 迁移）
        addFallbackChainInternal("ai_weather", "location", "network_search");
        addFallbackChainInternal("network_search", "smart_research", "webpage_reader");
        addFallbackChainInternal("python_calculate", "python_execute");
        addFallbackChainInternal("location", "network_search");
        addFallbackChainInternal("smart_research", "network_search", "webpage_reader");
        addFallbackChainInternal("database", "network_search");

        // 依赖链：缺少必填参数时可先调用的前置工具
        addChainInternal(ChainType.DEPENDENCY, "ai_weather", "location"); // ai_weather 缺 city 时可先定位
        addChainInternal(ChainType.DEPENDENCY, "network_search", "location"); // 搜索本地信息可先定位

        // 组合链：常见组合使用建议
        addChainInternal(ChainType.COMBINATION, "network_search", "webpage_reader", "smart_research");
        addChainInternal(ChainType.COMBINATION, "location", "ai_weather");
        addChainInternal(ChainType.COMBINATION, "python_calculate", "file_generator");
        addChainInternal(ChainType.COMBINATION, "file_reader", "file_analyzer");

        // 并行链：可并行执行的工具组
        addChainInternal(ChainType.PARALLEL, "ai_weather", "network_search"); // 同时查天气和搜索

        AILogger.i(TAG, "Default chains initialized");
    }

    private void addFallbackChainInternal(String tool, String... fallbacks) {
        addChainInternal(ChainType.FALLBACK, tool, fallbacks);
    }

    private void addChainInternal(ChainType type, String tool, String... related) {
        Map<String, List<String>> typeChains = chains.get(type);
        List<String> existing = typeChains.get(tool);
        Set<String> merged = new LinkedHashSet<>();
        if (existing != null) merged.addAll(existing);
        merged.addAll(Arrays.asList(related));
        typeChains.put(tool, new ArrayList<>(merged));
    }

    // ==================== 动态配置 ====================

    /**
     * 动态添加链。已存在则合并去重。
     */
    public synchronized void addChain(ChainType type, String toolName, String... relatedTools) {
        if (type == null || toolName == null || relatedTools == null) return;
        addChainInternal(type, toolName, relatedTools);
        AILogger.i(TAG, "Added " + type.displayName + " for " + toolName + ": " + Arrays.toString(relatedTools));
    }

    /**
     * 动态移除链。
     */
    public synchronized boolean removeChain(ChainType type, String toolName) {
        if (type == null || toolName == null) return false;
        Map<String, List<String>> typeChains = chains.get(type);
        if (typeChains == null) return false;
        boolean removed = typeChains.remove(toolName) != null;
        if (removed) AILogger.i(TAG, "Removed " + type.displayName + " for " + toolName);
        return removed;
    }

    /**
     * 清空某类型的所有链。
     */
    public synchronized void clearChains(ChainType type) {
        Map<String, List<String>> typeChains = chains.get(type);
        if (typeChains != null) typeChains.clear();
    }

    // ==================== 查询 ====================

    /**
     * 获取回退工具（验证工具存在且启用）。
     */
    public List<String> getFallbackTools(String failedTool) {
        return getValidatedRelated(ChainType.FALLBACK, failedTool);
    }

    /**
     * 获取组合建议（验证工具存在且启用）。
     */
    public List<String> getCombinations(String toolName) {
        return getValidatedRelated(ChainType.COMBINATION, toolName);
    }

    /**
     * 获取依赖工具（验证工具存在且启用）。
     */
    public List<String> getDependencies(String toolName) {
        return getValidatedRelated(ChainType.DEPENDENCY, toolName);
    }

    /**
     * 获取可并行执行的工具（验证工具存在且启用）。
     */
    public List<String> getParallelTools(String toolName) {
        return getValidatedRelated(ChainType.PARALLEL, toolName);
    }

    /**
     * 获取某工具某类型的关联工具（经存在性验证）。
     */
    private List<String> getValidatedRelated(ChainType type, String toolName) {
        if (toolName == null) return Collections.emptyList();
        Map<String, List<String>> typeChains = chains.get(type);
        if (typeChains == null) return Collections.emptyList();
        List<String> related = typeChains.get(toolName);
        if (related == null || related.isEmpty()) return Collections.emptyList();

        List<String> result = new ArrayList<>();
        for (String name : related) {
            if (registry != null && registry.isToolEnabled(name)) {
                result.add(name);
            }
        }
        return result;
    }

    /**
     * 获取所有链（用于工具指南生成）。
     */
    public Map<ChainType, Map<String, List<String>>> getAllChains() {
        Map<ChainType, Map<String, List<String>>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<ChainType, Map<String, List<String>>> e : chains.entrySet()) {
            // 仅保留经验证存在的链
            Map<String, List<String>> validated = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : e.getValue().entrySet()) {
                List<String> valid = new ArrayList<>();
                for (String name : entry.getValue()) {
                    if (registry == null || registry.isToolEnabled(name)) valid.add(name);
                }
                if (!valid.isEmpty()) validated.put(entry.getKey(), valid);
            }
            if (!validated.isEmpty()) snapshot.put(e.getKey(), validated);
        }
        return snapshot;
    }

    /**
     * 获取组合链中所有有意义的组合（用于工具指南）。
     */
    public List<String[]> getCombinationPairs() {
        Map<String, List<String>> combos = chains.get(ChainType.COMBINATION);
        List<String[]> pairs = new ArrayList<>();
        if (combos == null) return pairs;
        for (Map.Entry<String, List<String>> e : combos.entrySet()) {
            String from = e.getKey();
            for (String to : e.getValue()) {
                if (registry == null || (registry.isToolEnabled(from) && registry.isToolEnabled(to))) {
                    pairs.add(new String[]{from, to});
                }
            }
        }
        return pairs;
    }
}
