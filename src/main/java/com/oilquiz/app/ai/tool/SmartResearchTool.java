package com.oilquiz.app.ai.tool;

import android.content.Context;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能研究工具，整合网络搜索和网页阅读功能
 * 提供一站式研究体验：搜索 → 智能选择 → 深度阅读 → 综合摘要
 */
public class SmartResearchTool implements AITool {
    private static final String TAG = "SmartResearchTool";
    private final Context context;
    private final NetworkSearchTool searchTool;
    private final WebPageReaderTool readerTool;
    
    public SmartResearchTool(Context context) {
        this.context = context;
        this.searchTool = new NetworkSearchTool(context);
        this.readerTool = new WebPageReaderTool(context);
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
    public AIToolResult execute(Map<String, Object> parameters) {
        String action = (String) parameters.get("action");
        if (action == null) {
            action = "research";
        }
        
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
            return new AIToolResult("智能研究失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult performResearch(Map<String, Object> parameters) {
        String query = (String) parameters.get("query");
        Integer maxResults = (Integer) parameters.get("maxResults");
        Boolean includeDetails = (Boolean) parameters.get("includeDetails");
        
        if (query == null || query.isEmpty()) {
            return new AIToolResult("Missing required parameter: query", parameters);
        }
        
        if (maxResults == null || maxResults <= 0) {
            maxResults = 5;
        }
        
        if (includeDetails == null) {
            includeDetails = true;
        }
        
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
            
            AIToolResult searchResult = searchTool.execute(searchParams);
            
            if (!searchResult.isSuccess()) {
                researchResult.put("status", "failed");
                researchResult.put("error", searchResult.getResult().toString());
                return new AIToolResult(researchResult, parameters);
            }
            
            Map<String, Object> searchData = (Map<String, Object>) searchResult.getResult();
            researchResult.put("searchResults", searchData.get("analyzedResults"));
            researchResult.put("selectedIndices", searchData.get("selectedIndices"));
            
            if (includeDetails && searchData.containsKey("detailedContents")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> detailedContents = 
                    (List<Map<String, Object>>) searchData.get("detailedContents");
                
                List<Map<String, Object>> processedContents = new ArrayList<>();
                
                for (Map<String, Object> detail : detailedContents) {
                    boolean success = (Boolean) detail.get("success");
                    if (success) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> content = (Map<String, Object>) detail.get("content");
                        
                        Map<String, Object> processed = new HashMap<>();
                        processed.put("url", detail.get("url"));
                        processed.put("title", content.get("title"));
                        processed.put("summary", content.get("summary"));
                        processed.put("category", content.get("category"));
                        processed.put("keywords", content.get("extractedKeywords"));
                        processed.put("headings", content.get("headings"));
                        
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
            
            researchResult.put("status", "completed");
            researchResult.put("totalResults", searchData.get("totalResults"));
            
            return new AIToolResult(researchResult, parameters);
            
        } catch (Exception e) {
            AILogger.e(TAG, "Research failed: " + e.getMessage(), e);
            researchResult.put("status", "failed");
            researchResult.put("error", e.getMessage());
            return new AIToolResult(researchResult, parameters);
        }
    }
    
    private AIToolResult quickSearch(Map<String, Object> parameters) {
        String query = (String) parameters.get("query");
        Integer maxResults = (Integer) parameters.get("maxResults");
        
        if (query == null || query.isEmpty()) {
            return new AIToolResult("Missing required parameter: query", parameters);
        }
        
        if (maxResults == null || maxResults <= 0) {
            maxResults = 3;
        }
        
        Map<String, Object> searchParams = new HashMap<>();
        searchParams.put("action", "search");
        searchParams.put("query", query);
        searchParams.put("limit", maxResults);
        
        return searchTool.execute(searchParams);
    }
    
    private AIToolResult deepRead(Map<String, Object> parameters) {
        @SuppressWarnings("unchecked")
        List<String> urls = (List<String>) parameters.get("urls");
        String query = (String) parameters.get("query");
        
        if (urls == null || urls.isEmpty()) {
            return new AIToolResult("Missing required parameter: urls", parameters);
        }
        
        Map<String, Object> readParams = new HashMap<>();
        readParams.put("action", "read_multiple");
        readParams.put("urls", urls);
        
        AIToolResult readResult = readerTool.execute(readParams);
        
        if (readResult.isSuccess()) {
            Map<String, Object> result = (Map<String, Object>) readResult.getResult();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
            
            List<Map<String, Object>> summaries = new ArrayList<>();
            for (Map<String, Object> item : results) {
                if ((Boolean) item.get("success")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> content = (Map<String, Object>) item.get("content");
                    
                    Map<String, Object> summary = new HashMap<>();
                    summary.put("url", item.get("url"));
                    summary.put("title", content.get("title"));
                    summary.put("summary", content.get("summary"));
                    summary.put("category", content.get("category"));
                    
                    summaries.add(summary);
                }
            }
            
            Map<String, Object> finalResult = new HashMap<>();
            finalResult.put("status", "completed");
            finalResult.put("summaries", summaries);
            finalResult.put("summary", generateFinalSummary(summaries, query));
            
            return new AIToolResult(finalResult, parameters);
        }
        
        return readResult;
    }
    
    private AIToolResult summarizeTopic(Map<String, Object> parameters) {
        String query = (String) parameters.get("query");
        Integer maxResults = (Integer) parameters.get("maxResults");
        
        if (query == null || query.isEmpty()) {
            return new AIToolResult("Missing required parameter: query", parameters);
        }
        
        if (maxResults == null) {
            maxResults = 3;
        }
        
        Map<String, Object> researchParams = new HashMap<>();
        researchParams.put("action", "research");
        researchParams.put("query", query);
        researchParams.put("maxResults", maxResults);
        researchParams.put("includeDetails", true);
        
        AIToolResult researchResult = performResearch(researchParams);
        
        if (researchResult.isSuccess()) {
            Map<String, Object> result = (Map<String, Object>) researchResult.getResult();
            Map<String, Object> summary = new HashMap<>();
            summary.put("query", query);
            
            if (result.containsKey("summary")) {
                summary.put("content", result.get("summary"));
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> contents = (List<Map<String, Object>>) result.get("detailedContents");
                summary.put("content", generateFinalSummary(contents, query));
            }
            
            summary.put("status", "completed");
            return new AIToolResult(summary, parameters);
        }
        
        return researchResult;
    }
    
    private Map<String, Object> generateFinalSummary(List<Map<String, Object>> contents, String query) {
        Map<String, Object> summary = new HashMap<>();
        List<String> keyPoints = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        
        for (Map<String, Object> content : contents) {
            String title = (String) content.get("title");
            String url = (String) content.get("url");
            String textSummary = (String) content.get("summary");
            
            if (title != null && !title.isEmpty()) {
                keyPoints.add(title);
            }
            if (url != null && !url.isEmpty()) {
                sources.add(url);
            }
            if (textSummary != null && !textSummary.isEmpty()) {
                fullText.append(textSummary).append("\n\n");
            }
        }
        
        summary.put("keyPoints", keyPoints);
        summary.put("sources", sources);
        summary.put("detailedSummary", fullText.toString().trim());
        
        StringBuilder concise = new StringBuilder();
        concise.append("关于 \"").append(query).append("\" 的研究结果：\n\n");
        
        if (!keyPoints.isEmpty()) {
            concise.append("主要发现：\n");
            for (int i = 0; i < keyPoints.size(); i++) {
                concise.append(i + 1).append(". ").append(keyPoints.get(i)).append("\n");
            }
        }
        
        String text = fullText.toString().trim();
        if (text.length() > 300) {
            text = text.substring(0, 300) + "...";
        }
        concise.append("\n摘要：").append(text);
        
        concise.append("\n\n参考来源：").append(sources.size()).append(" 个链接");
        
        summary.put("conciseSummary", concise.toString());
        
        return summary;
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: research, quick_search, deep_read, summarize_topic");
        descriptions.put("query", "研究主题/搜索查询词（必填）");
        descriptions.put("maxResults", "最大搜索结果数（默认5）");
        descriptions.put("includeDetails", "是否获取详情内容（默认true）");
        descriptions.put("urls", "URL列表（用于deep_read操作）");
        return descriptions;
    }
}