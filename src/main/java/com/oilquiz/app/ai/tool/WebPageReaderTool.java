package com.oilquiz.app.ai.tool;

import android.content.Context;
import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
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
 * 网页阅读工具，用于获取和解析网页内容
 * 支持与网络搜索工具配合使用，提供智能阅读能力
 */
public class WebPageReaderTool implements AITool {
    private static final String TAG = "WebPageReaderTool";
    private final Context context;
    
    private static final Pattern TITLE_PATTERN = Pattern.compile("<title[^>]*>([^<]*)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_DESCRIPTION_PATTERN = Pattern.compile("<meta\\s+name\\s*=\\s*[\"']description[\"']\\s+content\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_KEYWORDS_PATTERN = Pattern.compile("<meta\\s+name\\s*=\\s*[\"']keywords[\"']\\s+content\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern H1_PATTERN = Pattern.compile("<h1[^>]*>([^<]*)</h1>", Pattern.CASE_INSENSITIVE);
    private static final Pattern H2_PATTERN = Pattern.compile("<h2[^>]*>([^<]*)</h2>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})|(\\d{1,2}[-/]\\d{1,2}[-/]\\d{4})|(\\d{4}年\\d{1,2}月\\d{1,2}日)");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+");
    private static final Pattern JSON_LD_PATTERN = Pattern.compile("<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>([^<]*)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    
    private static final Set<String> STOP_WORDS = new HashSet<>();
    static {
        STOP_WORDS.add("的"); STOP_WORDS.add("是"); STOP_WORDS.add("在"); STOP_WORDS.add("有"); STOP_WORDS.add("和");
        STOP_WORDS.add("了"); STOP_WORDS.add("我"); STOP_WORDS.add("你"); STOP_WORDS.add("他"); STOP_WORDS.add("她");
        STOP_WORDS.add("它"); STOP_WORDS.add("这"); STOP_WORDS.add("那"); STOP_WORDS.add("这些"); STOP_WORDS.add("那些");
        STOP_WORDS.add("什么"); STOP_WORDS.add("怎么"); STOP_WORDS.add("为什么"); STOP_WORDS.add("因为"); STOP_WORDS.add("所以");
        STOP_WORDS.add("但是"); STOP_WORDS.add("如果"); STOP_WORDS.add("可以"); STOP_WORDS.add("可能"); STOP_WORDS.add("应该");
        STOP_WORDS.add("需要"); STOP_WORDS.add("会"); STOP_WORDS.add("不会"); STOP_WORDS.add("能"); STOP_WORDS.add("不");
        STOP_WORDS.add("一个"); STOP_WORDS.add("一些"); STOP_WORDS.add("所有"); STOP_WORDS.add("每个"); STOP_WORDS.add("没有");
        STOP_WORDS.add("我们"); STOP_WORDS.add("你们"); STOP_WORDS.add("他们"); STOP_WORDS.add("它们"); STOP_WORDS.add("这个");
        STOP_WORDS.add("那个"); STOP_WORDS.add("非常"); STOP_WORDS.add("很"); STOP_WORDS.add("更"); STOP_WORDS.add("最");
        STOP_WORDS.add("也"); STOP_WORDS.add("还"); STOP_WORDS.add("再"); STOP_WORDS.add("又"); STOP_WORDS.add("都");
        STOP_WORDS.add("就"); STOP_WORDS.add("要"); STOP_WORDS.add("去"); STOP_WORDS.add("来"); STOP_WORDS.add("上");
        STOP_WORDS.add("下"); STOP_WORDS.add("出"); STOP_WORDS.add("进"); STOP_WORDS.add("过"); STOP_WORDS.add("到");
    }
    
    public WebPageReaderTool(Context context) {
        this.context = context;
    }
    
    @Override
    public String getName() {
        return "webpage_reader";
    }
    
    @Override
    public String getDescription() {
        return "网页阅读工具，用于获取网页内容、提取关键信息、生成智能摘要，可与网络搜索工具配合使用";
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        String action = (String) parameters.get("action");
        if (action == null) {
            action = "read";
        }
        
        try {
            switch (action) {
                case "read":
                    return readWebpage(parameters);
                case "extract":
                    return extractInfo(parameters);
                case "summarize":
                    return summarizeWebpage(parameters);
                case "read_multiple":
                    return readMultiple(parameters);
                case "follow_links":
                    return followLinks(parameters);
                default:
                    return readWebpage(parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error executing webpage reader: " + e.getMessage(), e);
            return new AIToolResult("网页阅读失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult readWebpage(Map<String, Object> parameters) {
        String url = (String) parameters.get("url");
        if (url == null || url.isEmpty()) {
            return new AIToolResult("Missing required parameter: url", parameters);
        }
        
        try {
            String content = fetchWebpage(url);
            Map<String, Object> result = parseWebpage(content, url);
            
            Map<String, Object> finalResult = new HashMap<>();
            finalResult.put("url", url);
            finalResult.put("status", "success");
            finalResult.put("content", result);
            
            return new AIToolResult(finalResult, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to read webpage: " + e.getMessage(), e);
            return new AIToolResult("读取网页失败: " + e.getMessage(), parameters);
        }
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
            
            Map<String, Object> extracted = new HashMap<>();
            extracted.put("url", url);
            extracted.put("title", extractTitle(content));
            extracted.put("description", extractMetaDescription(content));
            extracted.put("keywords", extractMetaKeywords(content));
            extracted.put("headings", extractHeadings(content));
            extracted.put("dates", extractDates(content));
            extracted.put("links", extractLinks(content, url));
            extracted.put("mainContent", extractMainContent(content));
            extracted.put("summary", generateSummary(content, null));
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("extracted", extracted);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("提取信息失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult summarizeWebpage(Map<String, Object> parameters) {
        String url = (String) parameters.get("url");
        String content = (String) parameters.get("content");
        String query = (String) parameters.get("query");
        
        if (url == null && content == null) {
            return new AIToolResult("Missing required parameter: url or content", parameters);
        }
        
        try {
            if (content == null) {
                content = fetchWebpage(url);
            }
            
            Map<String, Object> summary = generateDetailedSummary(content, url, query);
            summary.put("status", "success");
            
            return new AIToolResult(summary, parameters);
        } catch (Exception e) {
            return new AIToolResult("生成摘要失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult readMultiple(Map<String, Object> parameters) {
        @SuppressWarnings("unchecked")
        List<String> urls = (List<String>) parameters.get("urls");
        
        if (urls == null || urls.isEmpty()) {
            return new AIToolResult("Missing required parameter: urls", parameters);
        }
        
        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;
        
        for (String url : urls) {
            Map<String, Object> result = new HashMap<>();
            result.put("url", url);
            
            try {
                String content = fetchWebpage(url);
                Map<String, Object> parsed = parseWebpage(content, url);
                result.put("content", parsed);
                result.put("success", true);
                successCount++;
            } catch (Exception e) {
                result.put("error", e.getMessage());
                result.put("success", false);
                failCount++;
            }
            
            results.add(result);
        }
        
        Map<String, Object> finalResult = new HashMap<>();
        finalResult.put("total", urls.size());
        finalResult.put("successCount", successCount);
        finalResult.put("failCount", failCount);
        finalResult.put("results", results);
        finalResult.put("status", "completed");
        
        return new AIToolResult(finalResult, parameters);
    }
    
    private AIToolResult followLinks(Map<String, Object> parameters) {
        String url = (String) parameters.get("url");
        Integer maxDepth = (Integer) parameters.get("maxDepth");
        Integer maxLinks = (Integer) parameters.get("maxLinks");
        String query = (String) parameters.get("query");
        
        if (url == null) {
            return new AIToolResult("Missing required parameter: url", parameters);
        }
        
        if (maxDepth == null) maxDepth = 2;
        if (maxLinks == null) maxLinks = 10;
        
        try {
            Set<String> visitedUrls = new HashSet<>();
            List<Map<String, Object>> results = new ArrayList<>();
            
            followLinksRecursive(url, 0, maxDepth, maxLinks, visitedUrls, results, query);
            
            Map<String, Object> finalResult = new HashMap<>();
            finalResult.put("baseUrl", url);
            finalResult.put("visitedCount", visitedUrls.size());
            finalResult.put("results", results);
            finalResult.put("status", "completed");
            
            return new AIToolResult(finalResult, parameters);
        } catch (Exception e) {
            return new AIToolResult("跟踪链接失败: " + e.getMessage(), parameters);
        }
    }
    
    private void followLinksRecursive(String url, int depth, int maxDepth, int maxLinks, 
                                      Set<String> visitedUrls, List<Map<String, Object>> results, String query) {
        if (depth >= maxDepth || visitedUrls.size() >= maxLinks) return;
        if (visitedUrls.contains(url)) return;
        
        visitedUrls.add(url);
        
        try {
            String content = fetchWebpage(url);
            Map<String, Object> parsed = parseWebpage(content, url);
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> links = (List<Map<String, Object>>) parsed.get("links");
            
            Map<String, Object> result = new HashMap<>();
            result.put("url", url);
            result.put("depth", depth);
            result.put("title", parsed.get("title"));
            result.put("summary", parsed.get("summary"));
            
            if (query != null) {
                double relevance = calculateRelevance((String) parsed.get("title"), 
                                                     (String) parsed.get("summary"), query);
                result.put("relevance", relevance);
            }
            
            results.add(result);
            
            if (links != null && depth + 1 < maxDepth) {
                for (Map<String, Object> link : links) {
                    String linkUrl = (String) link.get("url");
                    if (!visitedUrls.contains(linkUrl) && visitedUrls.size() < maxLinks) {
                        followLinksRecursive(linkUrl, depth + 1, maxDepth, maxLinks, visitedUrls, results, query);
                    }
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to follow link: " + url);
        }
    }
    
    private String fetchWebpage(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        
        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new Exception("HTTP Error: " + responseCode);
        }
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
        StringBuilder content = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            content.append(line).append("\n");
        }
        reader.close();
        connection.disconnect();
        
        return content.toString();
    }
    
    private Map<String, Object> parseWebpage(String content, String url) {
        Map<String, Object> result = new HashMap<>();
        
        result.put("title", extractTitle(content));
        result.put("description", extractMetaDescription(content));
        result.put("keywords", extractMetaKeywords(content));
        result.put("headings", extractHeadings(content));
        result.put("dates", extractDates(content));
        result.put("links", extractLinks(content, url));
        result.put("contentSlices", sliceContent(content));
        result.put("category", classifyContent(content));
        result.put("extractedKeywords", extractKeywords(content));
        result.put("summary", generateSummary(content, null));
        result.put("isDynamic", isDynamicPage(content));
        
        if (isDynamicPage(content)) {
            result.put("dynamicData", extractDynamicData(content));
        }
        
        return result;
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
    
    private List<Map<String, Object>> extractLinks(String html, String baseUrl) {
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        
        Matcher matcher = URL_PATTERN.matcher(html);
        while (matcher.find()) {
            String url = matcher.group();
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
            
            if (links.size() >= 30) break;
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
    
    private String extractMainContent(String html) {
        String text = removeHtmlTags(html);
        String[] paragraphs = text.split("\\n\\n+");
        
        StringBuilder mainContent = new StringBuilder();
        for (String paragraph : paragraphs) {
            paragraph = paragraph.trim();
            if (paragraph.length() > 100) {
                mainContent.append(paragraph).append("\n\n");
            }
        }
        
        return mainContent.toString().trim();
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
        
        String cleanText = removeHtmlTags(text).toLowerCase();
        String[] words = cleanText.split("[\\s\\p{Punct}]+");
        
        Map<String, Integer> wordCount = new HashMap<>();
        for (String word : words) {
            if (word.length() >= 2 && !STOP_WORDS.contains(word)) {
                wordCount.put(word, wordCount.getOrDefault(word, 0) + 1);
            }
        }
        
        List<Map.Entry<String, Integer>> sortedEntries = new ArrayList<>(wordCount.entrySet());
        sortedEntries.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        for (int i = 0; i < Math.min(15, sortedEntries.size()); i++) {
            keywords.add(sortedEntries.get(i).getKey());
        }
        
        return keywords;
    }
    
    private String generateSummary(String content, String query) {
        String text = removeHtmlTags(content);
        
        if (text.length() <= 500) {
            return text;
        }
        
        String[] sentences = text.split("[。！？]");
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
            relevantSentences.addAll(java.util.Arrays.asList(sentences).subList(0, Math.min(5, sentences.length)));
        }
        
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < Math.min(5, relevantSentences.size()); i++) {
            if (i > 0) summary.append("。");
            summary.append(relevantSentences.get(i));
        }
        
        return summary.toString() + "。";
    }
    
    private Map<String, Object> generateDetailedSummary(String content, String url, String query) {
        Map<String, Object> summary = new HashMap<>();
        
        summary.put("url", url);
        summary.put("title", extractTitle(content));
        summary.put("category", classifyContent(content));
        
        String textSummary = generateSummary(content, query);
        summary.put("summary", textSummary);
        summary.put("keywords", extractKeywords(content));
        
        @SuppressWarnings("unchecked")
        List<String> headings = extractHeadings(content);
        if (!headings.isEmpty()) {
            summary.put("headings", headings);
        }
        
        List<String> dates = extractDates(content);
        if (!dates.isEmpty()) {
            summary.put("dates", dates);
        }
        
        return summary;
    }
    
    private boolean isDynamicPage(String content) {
        return content.contains("application/ld+json") || 
               content.contains("window.__INITIAL_STATE__") ||
               content.contains("ReactDOM.hydrate") ||
               content.contains("window.dataLayer");
    }
    
    private Map<String, Object> extractDynamicData(String content) {
        Map<String, Object> dynamicData = new HashMap<>();
        
        String jsonLdData = extractJsonLd(content);
        if (jsonLdData != null && !jsonLdData.isEmpty()) {
            try {
                JSONObject jsonObject = new JSONObject(jsonLdData);
                dynamicData.put("jsonLd", jsonObject);
            } catch (Exception e) {
                AILogger.w(TAG, "Failed to parse JSON-LD");
            }
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
    
    private double calculateRelevance(String title, String summary, String query) {
        if (title == null || summary == null || query == null) {
            return 0.0;
        }
        
        double score = 0.0;
        String lowerTitle = title.toLowerCase();
        String lowerSummary = summary.toLowerCase();
        String lowerQuery = query.toLowerCase();
        
        String[] queryWords = lowerQuery.split("\\s+");
        
        for (String word : queryWords) {
            if (lowerTitle.contains(word)) score += 2.0;
            if (lowerSummary.contains(word)) score += 1.0;
        }
        
        score /= queryWords.length * 3.0;
        return Math.min(1.0, score);
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: read, extract, summarize, read_multiple, follow_links");
        descriptions.put("url", "网页URL（必填）");
        descriptions.put("content", "网页内容（与url二选一）");
        descriptions.put("query", "搜索查询词（用于相关性计算）");
        descriptions.put("urls", "URL列表（用于read_multiple操作）");
        descriptions.put("maxDepth", "最大链接深度（用于follow_links操作，默认2）");
        descriptions.put("maxLinks", "最大链接数量（用于follow_links操作，默认10）");
        return descriptions;
    }
}