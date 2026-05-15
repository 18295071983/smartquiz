package com.oilquiz.app.ai.tool;

import android.content.Context;
import com.oilquiz.app.ai.util.APIKeyManager;
import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 网络搜索工具，用于网络搜索操作
 * 支持 Bing Search API 真实搜索和模拟数据模式
 * 增强功能：网页内容切片、关键信息提取、内容分类、智能摘要、动态网页支持
 */
public class NetworkSearchTool implements AITool {
    private static final String TAG = "NetworkSearchTool";
    private static final String BING_SEARCH_API_URL = "https://api.bing.microsoft.com/v7.0/search";
    private final Context context;
    
    private static final Pattern TITLE_PATTERN = Pattern.compile("<title[^>]*>([^<]*)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_DESCRIPTION_PATTERN = Pattern.compile("<meta\\s+name\\s*=\\s*[\"']description[\"']\\s+content\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_KEYWORDS_PATTERN = Pattern.compile("<meta\\s+name\\s*=\\s*[\"']keywords[\"']\\s+content\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern H1_PATTERN = Pattern.compile("<h1[^>]*>([^<]*)</h1>", Pattern.CASE_INSENSITIVE);
    private static final Pattern H2_PATTERN = Pattern.compile("<h2[^>]*>([^<]*)</h2>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})|(\\d{1,2}[-/]\\d{1,2}[-/]\\d{4})|(\\d{4}年\\d{1,2}月\\d{1,2}日)");
    private static final Pattern PHONE_PATTERN = Pattern.compile("1[3-9]\\d{9}|0\\d{2,3}-\\d{7,8}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+");
    private static final Pattern JSON_LD_PATTERN = Pattern.compile("<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>([^<]*)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SCRIPT_CONTENT_PATTERN = Pattern.compile("<script[^>]*>([^<]*)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    
    private static final Set<String> DYNAMIC_CONTENT_KEYWORDS = new HashSet<>(Arrays.asList(
        "window.__INITIAL_STATE__", "window.dataLayer", "window.__REDUX_STATE__",
        "window.APP_DATA", "window.__NEXT_DATA__", "window.__APOLLO_STATE__",
        "hydration", "ReactDOM.hydrate", "SSR_DATA"
    ));
    
    public NetworkSearchTool(Context context) {
        this.context = context;
    }
    
    @Override
    public String getName() {
        return "network_search";
    }
    
    @Override
    public String getDescription() {
        return "网络搜索工具，支持搜索网络信息、获取网页内容、内容切片、关键信息提取、智能摘要生成、动态网页解析和搜索结果详情阅读";
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = (String) parameters.get("action");
            if (action == null) {
                return new AIToolResult("Missing required parameter: action", parameters);
            }
            
            switch (action) {
                case "search":
                    return search(parameters);
                case "get_webpage":
                    return getWebpage(parameters);
                case "extract_info":
                    return extractInfo(parameters);
                case "summarize":
                    return summarizeResults(parameters);
                case "search_and_read":
                    return searchAndRead(parameters);
                case "get_dynamic_content":
                    return getDynamicContent(parameters);
                case "smart_search":
                    return smartSearch(parameters);
                case "smart_read":
                    return smartRead(parameters);
                case "get_weather":
                    return getWeather(parameters);
                default:
                    return new AIToolResult("Unknown action: " + action, parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error executing network search tool: " + e.getMessage(), e);
            return new AIToolResult("Error: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult search(Map<String, Object> parameters) {
        String query = (String) parameters.get("query");
        Integer limit = (Integer) parameters.get("limit");

        if (query == null) {
            return new AIToolResult("Missing required parameter: query", parameters);
        }

        if (limit == null || limit <= 0) {
            limit = 5;
        }

        try {
            APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);
            String apiKey = apiKeyManager.getAPIKey(APIKeyManager.Service.BING_SEARCH);

            List<Map<String, String>> searchResults;
            String note = null;

            if (apiKey == null || apiKey.isEmpty()) {
                AILogger.w(TAG, "Bing Search API 密钥未配置，使用模拟数据");
                searchResults = generateMockResults(query, limit);
                note = "提示：当前使用模拟数据，配置 Bing Search API 密钥后可获取真实搜索结果";
            } else {
                searchResults = bingSearch(query, limit, apiKey);
            }

            if (searchResults == null || searchResults.isEmpty()) {
                return new AIToolResult(
                    "未找到与 '" + query + "' 相关的搜索结果。",
                    parameters
                );
            }

            Map<String, Object> result = new HashMap<>();
            result.put("query", query);
            result.put("count", searchResults.size());
            result.put("status", "success");
            result.put("results", searchResults);
            if (note != null) {
                result.put("note", note);
            }

            return new AIToolResult(result, parameters);

        } catch (Exception e) {
            AILogger.e(TAG, "Search failed: " + e.getMessage(), e);
            return new AIToolResult("搜索失败: " + e.getMessage(), parameters);
        }
    }

    private List<Map<String, String>> generateMockResults(String query, int count) {
        List<Map<String, String>> results = new ArrayList<>();
        
        String[] topics = {"技术文章", "新闻资讯", "学术研究", "行业报告", "科普知识"};
        String[] domains = {"example.com", "knowledge.com", "techinfo.cn", "study.edu", "news.net"};
        
        for (int i = 0; i < count; i++) {
            Map<String, String> item = new HashMap<>();
            item.put("title", "关于 \"" + query + "\" 的" + topics[i % topics.length] + " - 第" + (i + 1) + "篇");
            item.put("snippet", "这是关于 \"" + query + "\" 的搜索结果摘要，包含相关的信息和内容介绍。");
            item.put("url", "https://www." + domains[i % domains.length] + "/search?q=" + query + "&id=" + (i + 1));
            results.add(item);
        }
        
        return results;
    }

    private List<Map<String, String>> bingSearch(String query, int count, String apiKey) throws Exception {
        List<Map<String, String>> results = new ArrayList<>();

        try {
            String encodedQuery = URLEncoder.encode(query, "UTF-8");
            String urlString = BING_SEARCH_API_URL + "?q=" + encodedQuery + "&count=" + count + "&responseFilter=WebPages";

            AILogger.i(TAG, "Bing Search URL: " + urlString);

            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Ocp-Apim-Subscription-Key", apiKey);
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            int responseCode = connection.getResponseCode();
            AILogger.i(TAG, "Bing Search Response Code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8")
                );
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                results = parseBingSearchResponse(response.toString());
                AILogger.i(TAG, "Parsed " + results.size() + " search results");
            } else if (responseCode == 401) {
                AILogger.e(TAG, "Bing Search API 认证失败");
                throw new Exception("Bing Search API 认证失败，请检查API密钥是否正确");
            } else if (responseCode == 429) {
                AILogger.e(TAG, "Bing Search API 请求频率超限");
                throw new Exception("Bing Search API 请求频率超限，请稍后再试");
            } else {
                AILogger.e(TAG, "Bing Search API 返回错误码: " + responseCode);
                throw new Exception("Bing Search API 返回错误码: " + responseCode);
            }

            connection.disconnect();

        } catch (Exception e) {
            AILogger.e(TAG, "Bing Search API 调用失败: " + e.getMessage(), e);
            throw e;
        }

        return results;
    }

    private List<Map<String, String>> parseBingSearchResponse(String jsonResponse) {
        List<Map<String, String>> results = new ArrayList<>();

        try {
            JSONObject responseJson = new JSONObject(jsonResponse);
            JSONObject webPages = responseJson.optJSONObject("webPages");
            if (webPages == null) {
                AILogger.w(TAG, "未在响应中找到webPages");
                return results;
            }

            JSONArray values = webPages.optJSONArray("value");
            if (values == null) {
                AILogger.w(TAG, "未在响应中找到搜索结果");
                return results;
            }

            for (int i = 0; i < values.length(); i++) {
                JSONObject item = values.getJSONObject(i);
                Map<String, String> resultItem = new HashMap<>();
                resultItem.put("title", item.optString("name", ""));
                resultItem.put("url", item.optString("url", ""));
                resultItem.put("snippet", item.optString("snippet", ""));

                if (!resultItem.get("title").isEmpty() && !resultItem.get("url").isEmpty()) {
                    results.add(resultItem);
                }
            }

        } catch (Exception e) {
            AILogger.e(TAG, "解析 Bing Search 响应失败: " + e.getMessage(), e);
        }

        return results;
    }
    
    private AIToolResult getWebpage(Map<String, Object> parameters) {
        String url = (String) parameters.get("url");

        if (url == null) {
            return new AIToolResult("Missing required parameter: url", parameters);
        }

        try {
            String content = fetchWebpage(url);

            Map<String, Object> result = new HashMap<>();
            result.put("url", url);
            result.put("status", "success");
            result.put("content", content);
            result.put("length", content.length());

            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取网页失败: " + e.getMessage(), parameters);
        }
    }

    private String fetchWebpage(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; OilQuizApp/1.0)");

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new Exception("HTTP Error: " + responseCode);
        }

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(connection.getInputStream(), "UTF-8")
        );
        StringBuilder content = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            content.append(line).append("\n");
        }
        reader.close();
        connection.disconnect();

        return content.toString();
    }
    
    private AIToolResult searchAndRead(Map<String, Object> parameters) {
        String query = (String) parameters.get("query");
        Integer limit = (Integer) parameters.get("limit");
        Integer detailIndex = (Integer) parameters.get("detailIndex");

        if (query == null) {
            return new AIToolResult("Missing required parameter: query", parameters);
        }

        if (limit == null || limit <= 0) {
            limit = 3;
        }

        try {
            Map<String, Object> searchParams = new HashMap<>();
            searchParams.put("query", query);
            searchParams.put("limit", limit);
            
            AIToolResult searchResult = search(searchParams);
            if (!searchResult.isSuccess()) {
                return searchResult;
            }

            Map<String, Object> searchData = (Map<String, Object>) searchResult.getResult();
            List<Map<String, String>> results = (List<Map<String, String>>) searchData.get("results");
            
            if (detailIndex != null && detailIndex >= 0 && detailIndex < results.size()) {
                Map<String, String> selectedResult = results.get(detailIndex);
                String detailUrl = selectedResult.get("url");
                
                Map<String, Object> detailResult = new HashMap<>();
                detailResult.put("query", query);
                detailResult.put("selectedIndex", detailIndex);
                detailResult.put("selectedTitle", selectedResult.get("title"));
                detailResult.put("selectedUrl", detailUrl);
                
                try {
                    String content = fetchWebpage(detailUrl);
                    Map<String, Object> extracted = extractWebpageContent(content, detailUrl);
                    detailResult.put("content", extracted);
                    detailResult.put("status", "success_with_detail");
                } catch (Exception e) {
                    AILogger.w(TAG, "Failed to fetch detail page: " + e.getMessage());
                    detailResult.put("content", "无法获取详情页内容: " + e.getMessage());
                    detailResult.put("status", "success_without_detail");
                }
                
                return new AIToolResult(detailResult, parameters);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("query", query);
            result.put("count", results.size());
            result.put("status", "success");
            result.put("results", results);
            result.put("note", "请使用 detailIndex 参数指定要查看的搜索结果索引（从0开始）");

            return new AIToolResult(result, parameters);

        } catch (Exception e) {
            AILogger.e(TAG, "Search and read failed: " + e.getMessage(), e);
            return new AIToolResult("搜索并阅读失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult getDynamicContent(Map<String, Object> parameters) {
        String url = (String) parameters.get("url");
        String content = (String) parameters.get("content");

        if (url == null && content == null) {
            return new AIToolResult("Missing required parameter: url or content", parameters);
        }

        try {
            if (content == null) {
                content = fetchWebpage(url);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("url", url);
            result.put("isDynamic", isDynamicPage(content));
            
            if (isDynamicPage(content)) {
                result.put("dynamicData", extractDynamicData(content));
            }
            
            result.put("content", extractWebpageContent(content, url));
            result.put("status", "success");

            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "Get dynamic content failed: " + e.getMessage(), e);
            return new AIToolResult("获取动态内容失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult smartSearch(Map<String, Object> parameters) {
        String query = (String) parameters.get("query");
        Integer maxResults = (Integer) parameters.get("maxResults");
        Boolean autoRead = (Boolean) parameters.get("autoRead");

        if (query == null) {
            return new AIToolResult("Missing required parameter: query", parameters);
        }

        if (maxResults == null || maxResults <= 0) {
            maxResults = 5;
        }

        if (autoRead == null) {
            autoRead = true;
        }

        try {
            AILogger.i(TAG, "Starting smart search for: " + query);
            
            Map<String, Object> searchParams = new HashMap<>();
            searchParams.put("query", query);
            searchParams.put("limit", maxResults);
            
            AIToolResult searchResult = search(searchParams);
            if (!searchResult.isSuccess()) {
                return searchResult;
            }

            Map<String, Object> searchData = (Map<String, Object>) searchResult.getResult();
            List<Map<String, String>> results = (List<Map<String, String>>) searchData.get("results");
            
            if (results == null || results.isEmpty()) {
                return new AIToolResult("未找到与 '" + query + "' 相关的搜索结果。", parameters);
            }

            List<Map<String, Object>> analyzedResults = analyzeSearchResults(results, query);
            List<Integer> selectedIndices = selectRelevantResults(analyzedResults);

            Map<String, Object> finalResult = new HashMap<>();
            finalResult.put("query", query);
            finalResult.put("totalResults", results.size());
            finalResult.put("analyzedResults", analyzedResults);
            finalResult.put("selectedIndices", selectedIndices);

            if (autoRead && !selectedIndices.isEmpty()) {
                List<Map<String, Object>> detailedContents = new ArrayList<>();
                
                for (Integer index : selectedIndices) {
                    try {
                        Map<String, String> result = results.get(index);
                        String url = result.get("url");
                        
                        Map<String, Object> detail = new HashMap<>();
                        detail.put("index", index);
                        detail.put("title", result.get("title"));
                        detail.put("url", url);
                        detail.put("originalSnippet", result.get("snippet"));
                        
                        try {
                            String content = fetchWebpage(url);
                            Map<String, Object> extracted = extractWebpageContent(content, url);
                            detail.put("content", extracted);
                            detail.put("success", true);
                        } catch (Exception e) {
                            AILogger.w(TAG, "Failed to fetch detail for URL: " + url);
                            detail.put("error", "无法获取详情: " + e.getMessage());
                            detail.put("success", false);
                        }
                        
                        detailedContents.add(detail);
                    } catch (Exception e) {
                        AILogger.e(TAG, "Error processing result index " + index + ": " + e.getMessage());
                    }
                }
                
                finalResult.put("detailedContents", detailedContents);
                finalResult.put("summary", generateSmartSummaryFromDetails(detailedContents, query));
                finalResult.put("status", "success_with_details");
            } else {
                finalResult.put("status", "success");
                finalResult.put("note", "使用 autoRead=true 可自动获取详情内容");
            }

            return new AIToolResult(finalResult, parameters);

        } catch (Exception e) {
            AILogger.e(TAG, "Smart search failed: " + e.getMessage(), e);
            return new AIToolResult("智能搜索失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult smartRead(Map<String, Object> parameters) {
        List<Map<String, Object>> results = (List<Map<String, Object>>) parameters.get("results");
        String query = (String) parameters.get("query");

        if (results == null || results.isEmpty()) {
            return new AIToolResult("Missing required parameter: results", parameters);
        }

        try {
            List<Map<String, Object>> analyzedResults = new ArrayList<>();
            
            for (int i = 0; i < results.size(); i++) {
                Map<String, Object> result = results.get(i);
                String title = (String) result.get("title");
                String snippet = (String) result.get("snippet");
                String url = (String) result.get("url");
                
                Map<String, Object> analysis = analyzeResult(title, snippet, url, query, i);
                analyzedResults.add(analysis);
            }

            List<Integer> selectedIndices = selectRelevantResults(analyzedResults);
            List<Map<String, Object>> detailedContents = new ArrayList<>();

            for (Integer index : selectedIndices) {
                try {
                    Map<String, Object> result = results.get(index);
                    String url = (String) result.get("url");
                    
                    Map<String, Object> detail = new HashMap<>();
                    detail.put("index", index);
                    detail.put("title", result.get("title"));
                    detail.put("url", url);
                    
                    try {
                        String content = fetchWebpage(url);
                        Map<String, Object> extracted = extractWebpageContent(content, url);
                        detail.put("content", extracted);
                        detail.put("success", true);
                    } catch (Exception e) {
                        detail.put("error", "无法获取详情: " + e.getMessage());
                        detail.put("success", false);
                    }
                    
                    detailedContents.add(detail);
                } catch (Exception e) {
                    AILogger.e(TAG, "Error reading result index " + index + ": " + e.getMessage());
                }
            }

            Map<String, Object> finalResult = new HashMap<>();
            finalResult.put("query", query);
            finalResult.put("selectedCount", selectedIndices.size());
            finalResult.put("selectedIndices", selectedIndices);
            finalResult.put("detailedContents", detailedContents);
            finalResult.put("summary", generateSmartSummaryFromDetails(detailedContents, query));
            finalResult.put("status", "success");

            return new AIToolResult(finalResult, parameters);

        } catch (Exception e) {
            AILogger.e(TAG, "Smart read failed: " + e.getMessage(), e);
            return new AIToolResult("智能阅读失败: " + e.getMessage(), parameters);
        }
    }
    
    private List<Map<String, Object>> analyzeSearchResults(List<Map<String, String>> results, String query) {
        List<Map<String, Object>> analyzed = new ArrayList<>();
        
        for (int i = 0; i < results.size(); i++) {
            Map<String, String> result = results.get(i);
            String title = result.get("title");
            String snippet = result.get("snippet");
            String url = result.get("url");
            
            Map<String, Object> analysis = analyzeResult(title, snippet, url, query, i);
            analyzed.add(analysis);
        }
        
        return analyzed;
    }
    
    private Map<String, Object> analyzeResult(String title, String snippet, String url, String query, int index) {
        Map<String, Object> analysis = new HashMap<>();
        analysis.put("index", index);
        analysis.put("title", title);
        analysis.put("snippet", snippet);
        analysis.put("url", url);
        
        double relevanceScore = calculateRelevance(title, snippet, query);
        analysis.put("relevanceScore", relevanceScore);
        
        boolean isHighQuality = isHighQualityResult(title, snippet, url);
        analysis.put("isHighQuality", isHighQuality);
        
        String contentCategory = classifyUrl(url);
        analysis.put("contentCategory", contentCategory);
        
        analysis.put("trustworthiness", calculateTrustworthiness(url));
        
        return analysis;
    }
    
    private double calculateRelevance(String title, String snippet, String query) {
        if (title == null || snippet == null || query == null) {
            return 0.0;
        }
        
        double score = 0.0;
        String lowerTitle = title.toLowerCase();
        String lowerSnippet = snippet.toLowerCase();
        String lowerQuery = query.toLowerCase();
        
        String[] queryWords = lowerQuery.split("\\s+");
        
        for (String word : queryWords) {
            if (lowerTitle.contains(word)) {
                score += 2.0;
            }
            if (lowerSnippet.contains(word)) {
                score += 1.0;
            }
        }
        
        score /= queryWords.length * 3.0;
        
        if (lowerTitle.startsWith(lowerQuery)) {
            score += 0.2;
        }
        
        return Math.min(1.0, score);
    }
    
    private boolean isHighQualityResult(String title, String snippet, String url) {
        if (title == null || title.length() < 5) return false;
        if (snippet == null || snippet.length() < 20) return false;
        if (url == null || !url.startsWith("https://")) return false;
        
        String[] trustedDomains = {"gov.cn", "edu.cn", "org.cn", ".com.cn", ".net.cn"};
        for (String domain : trustedDomains) {
            if (url.contains(domain)) {
                return true;
            }
        }
        
        return snippet.length() > 100;
    }
    
    private String classifyUrl(String url) {
        if (url == null) return "unknown";
        
        if (url.contains(".gov.")) return "government";
        if (url.contains(".edu.")) return "education";
        if (url.contains(".org")) return "organization";
        if (url.contains("news.") || url.contains(".news.")) return "news";
        if (url.contains("blog.") || url.contains(".blog.")) return "blog";
        if (url.contains("github.com") || url.contains("gitcode.com")) return "code";
        if (url.contains("baike.") || url.contains("wiki")) return "encyclopedia";
        
        return "general";
    }
    
    private double calculateTrustworthiness(String url) {
        if (url == null) return 0.0;
        
        double score = 0.5;
        
        if (url.startsWith("https://")) score += 0.1;
        if (url.contains(".gov.")) score += 0.2;
        if (url.contains(".edu.")) score += 0.2;
        if (url.contains(".org")) score += 0.1;
        
        return Math.min(1.0, score);
    }
    
    private List<Integer> selectRelevantResults(List<Map<String, Object>> analyzedResults) {
        List<Integer> selected = new ArrayList<>();
        
        analyzedResults.sort((a, b) -> {
            double scoreA = (Double) a.get("relevanceScore");
            double scoreB = (Double) b.get("relevanceScore");
            return Double.compare(scoreB, scoreA);
        });
        
        for (int i = 0; i < Math.min(3, analyzedResults.size()); i++) {
            Map<String, Object> result = analyzedResults.get(i);
            double relevance = (Double) result.get("relevanceScore");
            boolean highQuality = (Boolean) result.get("isHighQuality");
            
            if (relevance > 0.3 || highQuality) {
                selected.add((Integer) result.get("index"));
            }
        }
        
        if (selected.isEmpty() && !analyzedResults.isEmpty()) {
            selected.add((Integer) analyzedResults.get(0).get("index"));
        }
        
        return selected;
    }
    
    private Map<String, Object> generateSmartSummaryFromDetails(List<Map<String, Object>> details, String query) {
        Map<String, Object> summary = new HashMap<>();
        List<String> keyPoints = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        
        for (Map<String, Object> detail : details) {
            boolean success = (Boolean) detail.get("success");
            if (!success) continue;
            
            String title = (String) detail.get("title");
            String url = (String) detail.get("url");
            
            @SuppressWarnings("unchecked")
            Map<String, Object> content = (Map<String, Object>) detail.get("content");
            if (content != null) {
                String summaryText = (String) content.get("summary");
                @SuppressWarnings("unchecked")
                List<String> keywords = (List<String>) content.get("keywords");
                
                if (title != null) {
                    keyPoints.add(title);
                }
                if (url != null) {
                    sources.add(url);
                }
                if (summaryText != null) {
                    fullText.append(summaryText).append("\n\n");
                }
            }
        }
        
        summary.put("keyPoints", keyPoints);
        summary.put("sources", sources);
        summary.put("detailedSummary", fullText.toString().trim());
        summary.put("conciseSummary", generateConciseSummary(keyPoints, fullText.toString(), query));
        
        return summary;
    }
    
    private String generateConciseSummary(List<String> keyPoints, String fullText, String query) {
        StringBuilder summary = new StringBuilder();
        
        if (keyPoints != null && !keyPoints.isEmpty()) {
            summary.append("搜索到以下相关信息：\n");
            for (int i = 0; i < keyPoints.size(); i++) {
                summary.append(i + 1).append(". ").append(keyPoints.get(i)).append("\n");
            }
        }
        
        if (fullText != null && !fullText.isEmpty()) {
            String text = removeHtmlTags(fullText);
            if (text.length() > 500) {
                text = text.substring(0, 500) + "...";
            }
            summary.append("\n详细摘要：").append(text);
        }
        
        if (query != null) {
            summary.append("\n\n如需了解更多关于 \"").append(query).append("\" 的信息，请告诉我。");
        }
        
        return summary.toString();
    }
    
    private boolean isDynamicPage(String content) {
        for (String keyword : DYNAMIC_CONTENT_KEYWORDS) {
            if (content.contains(keyword)) {
                AILogger.i(TAG, "Detected dynamic page with keyword: " + keyword);
                return true;
            }
        }
        return content.contains("application/ld+json") || 
               content.contains("window.") && content.contains("=") && content.contains(";");
    }
    
    private Map<String, Object> extractDynamicData(String content) {
        Map<String, Object> dynamicData = new HashMap<>();
        
        String jsonLdData = extractJsonLd(content);
        if (jsonLdData != null && !jsonLdData.isEmpty()) {
            try {
                JSONObject jsonObject = new JSONObject(jsonLdData);
                dynamicData.put("jsonLd", jsonObject);
            } catch (Exception e) {
                AILogger.w(TAG, "Failed to parse JSON-LD: " + e.getMessage());
            }
        }
        
        Map<String, String> windowVariables = extractWindowVariables(content);
        if (!windowVariables.isEmpty()) {
            dynamicData.put("windowVariables", windowVariables);
        }
        
        return dynamicData;
    }
    
    private String extractJsonLd(String content) {
        Matcher matcher = JSON_LD_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
    
    private Map<String, String> extractWindowVariables(String content) {
        Map<String, String> variables = new HashMap<>();
        
        for (String keyword : DYNAMIC_CONTENT_KEYWORDS) {
            int index = content.indexOf(keyword);
            if (index != -1) {
                int endIndex = content.indexOf(";", index);
                if (endIndex == -1) {
                    endIndex = content.indexOf("</script>", index);
                }
                if (endIndex != -1) {
                    String variableContent = content.substring(index, endIndex).trim();
                    if (variableContent.contains("=")) {
                        String[] parts = variableContent.split("=", 2);
                        if (parts.length == 2) {
                            variables.put(parts[0].trim(), parts[1].trim());
                        }
                    }
                }
            }
        }
        
        return variables;
    }
    
    private Map<String, Object> extractWebpageContent(String content, String url) {
        Map<String, Object> extracted = new HashMap<>();
        
        extracted.put("title", extractTitle(content));
        extracted.put("metaDescription", extractMetaDescription(content));
        extracted.put("metaKeywords", extractMetaKeywords(content));
        extracted.put("headings", extractHeadings(content));
        extracted.put("contentSlices", sliceContent(content));
        extracted.put("category", classifyContent(content));
        extracted.put("keywords", extractKeywords(content));
        extracted.put("summary", generateSmartSummary(removeHtmlTags(content), null));
        
        List<Map<String, Object>> links = extractInternalLinks(content, url);
        extracted.put("relatedLinks", links);
        
        return extracted;
    }
    
    private List<Map<String, Object>> extractInternalLinks(String content, String baseUrl) {
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        
        Matcher matcher = URL_PATTERN.matcher(content);
        while (matcher.find()) {
            String url = matcher.group(1);
            
            if (url.length() > 256) continue;
            if (seenUrls.contains(url)) continue;
            
            String baseDomain = getDomain(baseUrl);
            String linkDomain = getDomain(url);
            
            Map<String, Object> linkInfo = new HashMap<>();
            linkInfo.put("url", url);
            linkInfo.put("isInternal", baseDomain != null && linkDomain != null && baseDomain.equals(linkDomain));
            linkInfo.put("domain", linkDomain);
            
            links.add(linkInfo);
            seenUrls.add(url);
            
            if (links.size() >= 20) break;
        }
        
        return links;
    }
    
    private String getDomain(String url) {
        try {
            URL uri = new URL(url);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }
    
    private AIToolResult getWeather(Map<String, Object> parameters) {
        return new AIToolResult(
            "天气查询功能请使用 get_weather 工具",
            parameters
        );
    }
    
    private AIToolResult extractInfo(Map<String, Object> parameters) {
        String url = (String) parameters.get("url");
        String content = (String) parameters.get("content");

        if (url == null && content == null) {
            return new AIToolResult("Missing required parameter: url or content", parameters);
        }

        try {
            if (content == null) {
                content = fetchWebpage(url);
            }

            Map<String, Object> extractedInfo = new HashMap<>();
            extractedInfo.put("url", url);
            
            extractedInfo.put("title", extractTitle(content));
            extractedInfo.put("metaDescription", extractMetaDescription(content));
            extractedInfo.put("metaKeywords", extractMetaKeywords(content));
            extractedInfo.put("headings", extractHeadings(content));
            extractedInfo.put("dates", extractDates(content));
            extractedInfo.put("phones", extractPhones(content));
            extractedInfo.put("emails", extractEmails(content));
            extractedInfo.put("urls", extractUrls(content));
            extractedInfo.put("contentSlices", sliceContent(content));
            extractedInfo.put("category", classifyContent(content));
            extractedInfo.put("keywords", extractKeywords(content));

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("extracted", extractedInfo);

            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "Extract info failed: " + e.getMessage(), e);
            return new AIToolResult("提取信息失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult summarizeResults(Map<String, Object> parameters) {
        List<Map<String, Object>> results = (List<Map<String, Object>>) parameters.get("results");
        String query = (String) parameters.get("query");

        if (results == null || results.isEmpty()) {
            return new AIToolResult("Missing required parameter: results", parameters);
        }

        try {
            Map<String, Object> summary = new HashMap<>();
            summary.put("query", query);
            summary.put("totalResults", results.size());
            
            List<String> keyPoints = new ArrayList<>();
            List<String> sources = new ArrayList<>();
            StringBuilder fullSummary = new StringBuilder();

            for (int i = 0; i < results.size(); i++) {
                Map<String, Object> item = results.get(i);
                String title = (String) item.get("title");
                String snippet = (String) item.get("snippet");
                String url = (String) item.get("url");

                if (title != null && !title.isEmpty()) {
                    keyPoints.add((i + 1) + ". " + title);
                    sources.add(url);
                }
                
                if (snippet != null && !snippet.isEmpty()) {
                    fullSummary.append(snippet).append("\n\n");
                }
            }

            summary.put("keyPoints", keyPoints);
            summary.put("sources", sources);
            summary.put("summary", generateSmartSummary(fullSummary.toString(), query));
            summary.put("detailedSummary", fullSummary.toString().trim());

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("summary", summary);

            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "Summarize failed: " + e.getMessage(), e);
            return new AIToolResult("生成摘要失败: " + e.getMessage(), parameters);
        }
    }
    
    private String extractTitle(String html) {
        Matcher matcher = TITLE_PATTERN.matcher(html);
        if (matcher.find()) {
            return cleanText(matcher.group(1));
        }
        return "";
    }
    
    private String extractMetaDescription(String html) {
        Matcher matcher = META_DESCRIPTION_PATTERN.matcher(html);
        if (matcher.find()) {
            return cleanText(matcher.group(1));
        }
        return "";
    }
    
    private String extractMetaKeywords(String html) {
        Matcher matcher = META_KEYWORDS_PATTERN.matcher(html);
        if (matcher.find()) {
            return cleanText(matcher.group(1));
        }
        return "";
    }
    
    private List<String> extractHeadings(String html) {
        List<String> headings = new ArrayList<>();
        
        Matcher h1Matcher = H1_PATTERN.matcher(html);
        while (h1Matcher.find()) {
            headings.add("H1: " + cleanText(h1Matcher.group(1)));
        }
        
        Matcher h2Matcher = H2_PATTERN.matcher(html);
        while (h2Matcher.find()) {
            headings.add("H2: " + cleanText(h2Matcher.group(1)));
        }
        
        return headings;
    }
    
    private List<String> extractDates(String html) {
        List<String> dates = new ArrayList<>();
        Matcher matcher = DATE_PATTERN.matcher(html);
        while (matcher.find()) {
            dates.add(matcher.group(1));
        }
        return dates;
    }
    
    private List<String> extractPhones(String html) {
        List<String> phones = new ArrayList<>();
        Matcher matcher = PHONE_PATTERN.matcher(html);
        while (matcher.find()) {
            phones.add(matcher.group(1));
        }
        return phones;
    }
    
    private List<String> extractEmails(String html) {
        List<String> emails = new ArrayList<>();
        Matcher matcher = EMAIL_PATTERN.matcher(html);
        while (matcher.find()) {
            emails.add(matcher.group(1));
        }
        return emails;
    }
    
    private List<String> extractUrls(String html) {
        List<String> urls = new ArrayList<>();
        Matcher matcher = URL_PATTERN.matcher(html);
        while (matcher.find()) {
            urls.add(matcher.group(1));
        }
        return urls;
    }
    
    private List<Map<String, Object>> sliceContent(String html) {
        List<Map<String, Object>> slices = new ArrayList<>();
        
        String text = removeHtmlTags(html);
        String[] paragraphs = text.split("\\n\\n+");
        
        for (int i = 0; i < paragraphs.length; i++) {
            String paragraph = paragraphs[i].trim();
            if (paragraph.length() > 50) {
                Map<String, Object> slice = new HashMap<>();
                slice.put("index", i + 1);
                slice.put("content", paragraph);
                slice.put("length", paragraph.length());
                slice.put("isSummary", isSummarySection(paragraph));
                slices.add(slice);
            }
        }
        
        return slices;
    }
    
    private String removeHtmlTags(String html) {
        String text = html.replaceAll("<[^>]+>", "");
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }
    
    private String cleanText(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }
    
    private boolean isSummarySection(String text) {
        String lowerText = text.toLowerCase();
        return lowerText.contains("总结") || lowerText.contains("摘要") || 
               lowerText.contains("简介") || lowerText.contains("概述") ||
               lowerText.contains("summary") || lowerText.contains("abstract");
    }
    
    private String classifyContent(String text) {
        String lowerText = text.toLowerCase();
        
        if (lowerText.contains("新闻") || lowerText.contains("报道") || lowerText.contains("最新")) {
            return "新闻资讯";
        }
        if (lowerText.contains("技术") || lowerText.contains("开发") || lowerText.contains("编程")) {
            return "技术文章";
        }
        if (lowerText.contains("研究") || lowerText.contains("论文") || lowerText.contains("学术")) {
            return "学术研究";
        }
        if (lowerText.contains("报告") || lowerText.contains("分析") || lowerText.contains("市场")) {
            return "行业报告";
        }
        if (lowerText.contains("科普") || lowerText.contains("知识") || lowerText.contains("百科")) {
            return "科普知识";
        }
        
        return "其他";
    }
    
    private List<String> extractKeywords(String text) {
        List<String> keywords = new ArrayList<>();
        Set<String> stopWords = new HashSet<>(Arrays.asList(
            "的", "是", "在", "有", "和", "了", "我", "你", "他", "她", "它",
            "这", "那", "这些", "那些", "什么", "怎么", "为什么", "因为", "所以",
            "但是", "如果", "可以", "可能", "应该", "需要", "会", "不会", "能"
        ));
        
        String cleanText = removeHtmlTags(text).toLowerCase();
        String[] words = cleanText.split("[\\s\\p{Punct}]+");
        
        Map<String, Integer> wordCount = new HashMap<>();
        for (String word : words) {
            if (word.length() >= 2 && !stopWords.contains(word)) {
                wordCount.put(word, wordCount.getOrDefault(word, 0) + 1);
            }
        }
        
        List<Map.Entry<String, Integer>> sortedEntries = new ArrayList<>(wordCount.entrySet());
        sortedEntries.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        for (int i = 0; i < Math.min(10, sortedEntries.size()); i++) {
            keywords.add(sortedEntries.get(i).getKey());
        }
        
        return keywords;
    }
    
    private String generateSmartSummary(String content, String query) {
        if (content == null || content.isEmpty()) {
            return "暂无摘要";
        }
        
        String[] sentences = content.split("[。！？]");
        List<String> relevantSentences = new ArrayList<>();
        
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.length() > 20) {
                boolean relevant = false;
                if (query != null && !query.isEmpty()) {
                    String[] queryWords = query.split("\\s+");
                    for (String word : queryWords) {
                        if (sentence.contains(word)) {
                            relevant = true;
                            break;
                        }
                    }
                } else {
                    relevant = true;
                }
                
                if (relevant) {
                    relevantSentences.add(sentence);
                }
            }
        }
        
        if (relevantSentences.isEmpty()) {
            relevantSentences.addAll(Arrays.asList(sentences).subList(0, Math.min(3, sentences.length)));
        }
        
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < Math.min(3, relevantSentences.size()); i++) {
            if (i > 0) summary.append("。");
            summary.append(relevantSentences.get(i));
        }
        
        return summary.toString() + "。";
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: search, get_webpage, extract_info, summarize, search_and_read, get_dynamic_content, smart_search, smart_read, get_weather");
        descriptions.put("query", "搜索查询（用于search、search_and_read、smart_search和smart_read操作）");
        descriptions.put("limit", "搜索结果数量限制（用于search和search_and_read操作，默认5）");
        descriptions.put("maxResults", "最大结果数（用于smart_search操作，默认5）");
        descriptions.put("autoRead", "是否自动读取详情（用于smart_search操作，默认true）");
        descriptions.put("url", "网页URL（用于get_webpage、extract_info和get_dynamic_content操作）");
        descriptions.put("content", "网页内容（用于extract_info和get_dynamic_content操作，与url二选一）");
        descriptions.put("results", "搜索结果列表（用于summarize和smart_read操作）");
        descriptions.put("location", "位置（用于get_weather操作）");
        descriptions.put("detailIndex", "搜索结果索引（用于search_and_read操作，从0开始，指定要阅读的详情）");
        return descriptions;
    }
}