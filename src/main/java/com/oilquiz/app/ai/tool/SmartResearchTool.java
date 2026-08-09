package com.oilquiz.app.ai.tool;

import android.content.Context;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tool(
    value = "smart_research",
    description = "智能研究工具，整合搜索和阅读功能，自动完成搜索→选择→阅读→摘要的完整研究流程",
    category = "research",
    actions = {
        @Action(name = "research", description = "执行完整研究流程"),
        @Action(name = "quick_search", description = "快速搜索"),
        @Action(name = "deep_read", description = "深度阅读"),
        @Action(name = "summarize_topic", description = "主题摘要")
    },
    params = {
        @Param(name = "topic", type = "string", description = "研究主题", required = true),
        @Param(name = "depth", type = "int", description = "研究深度(默认1)", required = false),
        @Param(name = "maxResults", type = "int", description = "最大结果数(默认5)", required = false)
    }
)
public class SmartResearchTool implements AITool {
    private static final String TAG = "SmartResearchTool";
    private final Context context;
    private NetworkSearchTool searchTool;
    private WebPageReaderTool readerTool;

    public SmartResearchTool(Context context) {
        this.context = context;
    }

    /** 懒加载搜索工具（避免重复创建，优先从AIToolManager获取） */
    private NetworkSearchTool getSearchTool() {
        if (searchTool == null) {
            AITool tool = AIToolManager.getInstance(context).getTool("network_search");
            if (tool instanceof NetworkSearchTool) {
                searchTool = (NetworkSearchTool) tool;
            } else {
                searchTool = new NetworkSearchTool(context);
            }
        }
        return searchTool;
    }

    /** 懒加载网页阅读工具 */
    private WebPageReaderTool getReaderTool() {
        if (readerTool == null) {
            AITool tool = AIToolManager.getInstance(context).getTool("webpage_reader");
            if (tool instanceof WebPageReaderTool) {
                readerTool = (WebPageReaderTool) tool;
            } else {
                readerTool = new WebPageReaderTool(context);
            }
        }
        return readerTool;
    }

    // ===== 类型安全的参数提取 =====

    private String getStringParam(Map<String, Object> params, String key, String def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v == null) return def;
        return String.valueOf(v);
    }

    private int getIntParam(Map<String, Object> params, String key, int def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private boolean getBoolParam(Map<String, Object> params, String key, boolean def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.equals("true") || s.equals("1") || s.equals("yes")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no")) return false;
        return def;
    }

    private void normalizeParameters(Map<String, Object> parameters) {
        if (parameters == null) return;
        // topic作为query的别名
        if (parameters.containsKey("topic") && !parameters.containsKey("query")) {
            parameters.put("query", parameters.get("topic"));
        }
    }

    @Override
    public String getName() {
        return "smart_research";
    }

    @Override
    public String getDescription() {
        return "智能研究工具，整合搜索和阅读功能，自动完成搜索→选择→阅读→摘要的完整研究流程";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作类型：research(完整研究)、quick_search(快速搜索)、deep_read(深度阅读)、summarize_topic(主题摘要)");
        params.put("topic", "研究主题(必填)");
        params.put("depth", "研究深度(默认1)");
        params.put("maxResults", "最大结果数(默认5)");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        normalizeParameters(parameters);

        String action = getStringParam(parameters, "action", "research");

        try {
            switch (action) {
                case "research":
                    return performResearch(parameters);
                case "quick_search":
                    return quickSearch(parameters);
                case "deep_read":
                    return deepRead(parameters);
                case "summarize_topic":
                    return summarizeTopic(parameters);
                default:
                    return performResearch(parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Smart research failed: " + e.getMessage(), e);
            return AIToolResult.fail("智能研究失败: " + e.getMessage(), parameters);
        }
    }

    @SuppressWarnings("unchecked")
    private AIToolResult performResearch(Map<String, Object> parameters) {
        String query = getStringParam(parameters, "query", null);
        int maxResults = getIntParam(parameters, "maxResults", 5);
        boolean includeDetails = getBoolParam(parameters, "includeDetails", true);

        if (query == null || query.trim().isEmpty()) {
            return AIToolResult.fail("缺少必需参数: query（研究主题）", parameters);
        }

        if (maxResults <= 0) maxResults = 5;

        AILogger.i(TAG, "Starting smart research for: " + query);

        Map<String, Object> researchResult = new HashMap<>();
        researchResult.put("query", query);
        researchResult.put("status", "in_progress");

        try {
            Map<String, Object> searchParams = new HashMap<>();
            searchParams.put("action", "smart_search");
            searchParams.put("query", query);
            searchParams.put("maxResults", maxResults);
            searchParams.put("autoRead", includeDetails);

            AIToolResult searchResult = getSearchTool().execute(searchParams);

            if (!searchResult.isSuccess()) {
                String errMsg = searchResult.getErrorMessage();
                return AIToolResult.fail("智能研究失败: " + (errMsg != null ? errMsg : "搜索失败"), parameters);
            }

            Object resultObj = searchResult.getResult();
            if (!(resultObj instanceof Map)) {
                return AIToolResult.fail("搜索返回结果格式异常", parameters);
            }

            Map<String, Object> searchData = (Map<String, Object>) resultObj;
            researchResult.put("searchResults", searchData.get("analyzedResults"));
            researchResult.put("selectedIndices", searchData.get("selectedIndices"));

            if (includeDetails && searchData.containsKey("detailedContents")) {
                Object dcObj = searchData.get("detailedContents");
                if (dcObj instanceof List) {
                    List<Map<String, Object>> detailedContents = (List<Map<String, Object>>) dcObj;
                    List<Map<String, Object>> processedContents = new ArrayList<>();

                    for (Map<String, Object> detail : detailedContents) {
                        boolean success = getBoolValue(detail, "success", false);
                        if (success) {
                            Object contentObj = detail.get("content");
                            Map<String, Object> processed = new HashMap<>();
                            processed.put("url", detail.get("url"));
                            if (contentObj instanceof Map) {
                                Map<String, Object> content = (Map<String, Object>) contentObj;
                                processed.put("title", content.get("title"));
                                processed.put("summary", content.get("summary"));
                                processed.put("category", content.get("category"));
                                processed.put("keywords", content.get("extractedKeywords"));
                                processed.put("headings", content.get("headings"));
                            }
                            processedContents.add(processed);
                        }
                    }

                    researchResult.put("detailedContents", processedContents);

                    if (searchData.containsKey("summary")) {
                        researchResult.put("summary", searchData.get("summary"));
                    } else {
                        researchResult.put("summary", generateFinalSummary(processedContents, query));
                    }
                }
            }

            researchResult.put("status", "completed");
            researchResult.put("totalResults", searchData.get("totalResults"));

            return AIToolResult.success(researchResult, parameters);

        } catch (Exception e) {
            AILogger.e(TAG, "Research failed: " + e.getMessage(), e);
            return AIToolResult.fail("智能研究失败: " + e.getMessage(), parameters);
        }
    }

    private AIToolResult quickSearch(Map<String, Object> parameters) {
        String query = getStringParam(parameters, "query", null);
        int maxResults = getIntParam(parameters, "maxResults", 3);

        if (query == null || query.trim().isEmpty()) {
            return AIToolResult.fail("缺少必需参数: query", parameters);
        }

        if (maxResults <= 0) maxResults = 3;

        Map<String, Object> searchParams = new HashMap<>();
        searchParams.put("action", "search");
        searchParams.put("query", query);
        searchParams.put("limit", maxResults);

        return getSearchTool().execute(searchParams);
    }

    @SuppressWarnings("unchecked")
    private AIToolResult deepRead(Map<String, Object> parameters) {
        String query = getStringParam(parameters, "query", null);
        List<String> urls = null;
        try {
            Object raw = parameters.get("urls");
            if (raw instanceof List) {
                urls = (List<String>) raw;
            } else if (raw instanceof String && !((String) raw).trim().isEmpty()) {
                // 兼容单个 url 字符串（引导卡片/模型只传了 url 参数）
                urls = new ArrayList<>();
                urls.add(((String) raw).trim());
            }
        } catch (ClassCastException e) {
            AILogger.w(TAG, "urls参数类型不匹配: " + e.getMessage());
        }
        // 兼容单个 url 参数（引导卡片收集的 paramKey 为 url）
        if ((urls == null || urls.isEmpty())) {
            String singleUrl = getStringParam(parameters, "url", null);
            if (singleUrl != null && !singleUrl.trim().isEmpty()) {
                urls = new ArrayList<>();
                urls.add(singleUrl.trim());
            }
        }

        if (urls == null || urls.isEmpty()) {
            return AIToolResult.fail("缺少必需参数: urls（要深度阅读的URL列表）", parameters);
        }

        Map<String, Object> readParams = new HashMap<>();
        readParams.put("action", "read_multiple");
        readParams.put("urls", urls);

        AIToolResult readResult = getReaderTool().execute(readParams);

        if (readResult.isSuccess()) {
            Object resultObj = readResult.getResult();
            if (!(resultObj instanceof Map)) {
                return readResult;
            }

            Map<String, Object> result = (Map<String, Object>) resultObj;
            Object resultsObj = result.get("results");
            if (!(resultsObj instanceof List)) {
                return readResult;
            }

            List<Map<String, Object>> results = (List<Map<String, Object>>) resultsObj;
            List<Map<String, Object>> summaries = new ArrayList<>();

            for (Map<String, Object> item : results) {
                if (getBoolValue(item, "success", false)) {
                    Object contentObj = item.get("content");
                    Map<String, Object> summary = new HashMap<>();
                    summary.put("url", item.get("url"));
                    if (contentObj instanceof Map) {
                        Map<String, Object> content = (Map<String, Object>) contentObj;
                        summary.put("title", content.get("title"));
                        summary.put("summary", content.get("summary"));
                        summary.put("category", content.get("category"));
                    }
                    summaries.add(summary);
                }
            }

            Map<String, Object> finalResult = new HashMap<>();
            finalResult.put("status", "completed");
            finalResult.put("summaries", summaries);
            finalResult.put("summary", generateFinalSummary(summaries, query));

            return AIToolResult.success(finalResult, parameters);
        }

        return readResult;
    }

    private AIToolResult summarizeTopic(Map<String, Object> parameters) {
        String query = getStringParam(parameters, "query", null);
        int maxResults = getIntParam(parameters, "maxResults", 5);

        if (query == null || query.trim().isEmpty()) {
            return AIToolResult.fail("缺少必需参数: query", parameters);
        }

        if (maxResults <= 0) maxResults = 5;

        Map<String, Object> searchParams = new HashMap<>();
        searchParams.put("action", "smart_search");
        searchParams.put("query", query);
        searchParams.put("maxResults", maxResults);
        searchParams.put("autoRead", true);

        AIToolResult searchResult = getSearchTool().execute(searchParams);

        if (!searchResult.isSuccess()) {
            return searchResult;
        }

        Object resultObj = searchResult.getResult();
        if (!(resultObj instanceof Map)) {
            return searchResult;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> searchData = (Map<String, Object>) resultObj;

        Map<String, Object> finalResult = new HashMap<>();
        finalResult.put("query", query);
        finalResult.put("status", "completed");
        finalResult.put("totalResults", searchData.get("totalResults"));
        finalResult.put("searchResults", searchData.get("analyzedResults"));

        if (searchData.containsKey("summary")) {
            finalResult.put("summary", searchData.get("summary"));
        }

        return AIToolResult.success(finalResult, parameters);
    }

    // ===== 辅助方法 =====

    /** 从Map中安全提取布尔值（避免null拆箱NPE） */
    private boolean getBoolValue(Map<String, Object> map, String key, boolean def) {
        if (map == null) return def;
        Object v = map.get(key);
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v).trim().toLowerCase();
        return s.equals("true") || s.equals("1");
    }

    private String generateFinalSummary(List<Map<String, Object>> contents, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("关于「").append(query).append("」的研究摘要：\n\n");

        if (contents == null || contents.isEmpty()) {
            sb.append("未找到相关内容。");
            return sb.toString();
        }

        for (int i = 0; i < contents.size(); i++) {
            Map<String, Object> content = contents.get(i);
            sb.append(i + 1).append(". ");
            Object title = content.get("title");
            if (title != null) {
                sb.append(title);
            }
            Object summary = content.get("summary");
            if (summary != null) {
                sb.append("\n   ").append(summary);
            }
            sb.append("\n\n");
        }

        return sb.toString().trim();
    }
}
