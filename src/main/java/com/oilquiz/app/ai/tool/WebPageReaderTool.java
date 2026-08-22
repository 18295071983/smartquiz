package com.oilquiz.app.ai.tool;

import android.content.Context;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import com.oilquiz.app.ai.util.NetworkUtil;

/**
 * 网页阅读工具（Jsoup 解析版）
 *
 * 操作：
 * - read：抓取并解析网页（标题/描述/关键词/标题结构/链接/正文/摘要/分类/动态数据）
 * - extract：从 url 或已有 content 提取关键信息
 * - summarize：生成摘要（关键词/标题/日期/正文摘要）
 * - read_multiple：批量读取（并行抓取）
 * - follow_links：跟踪站内链接（深度/数量上限）
 *
 * 解析基于 Jsoup DOM：正确剥离 script/style/nav 等噪声，支持相对链接转绝对、meta 属性任意顺序。
 */
@Tool(
    value = "webpage_reader",
    description = "网页阅读工具，用于获取网页内容、提取关键信息、生成智能摘要",
    category = "web",
    actions = {
        @Action(name = "read", description = "读取网页内容"),
        @Action(name = "extract", description = "提取网页关键信息"),
        @Action(name = "summarize", description = "生成网页摘要"),
        @Action(name = "read_multiple", description = "批量读取网页"),
        @Action(name = "follow_links", description = "跟踪链接")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作类型: read, extract, summarize, read_multiple, follow_links", required = true),
        @Param(name = "url", type = "string", description = "网页URL", required = true),
        @Param(name = "content", type = "string", description = "网页内容(与url二选一)", required = false),
        @Param(name = "query", type = "string", description = "搜索查询词", required = false),
        @Param(name = "urls", type = "array", description = "URL列表(用于read_multiple)", required = false),
        @Param(name = "maxDepth", type = "int", description = "最大链接深度(默认2)", required = false),
        @Param(name = "maxLinks", type = "int", description = "最大链接数量(默认10)", required = false)
    }
)
public class WebPageReaderTool implements AITool {
    private static final String TAG = "WebPageReaderTool";
    private static final int MAX_FETCH_BYTES = 5 * 1024 * 1024; // 单页最大 5MB
    private static final int MAX_LINKS = 30;
    private final Context context;

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})|(\\d{1,2}[-/]\\d{1,2}[-/]\\d{4})|(\\d{4}年\\d{1,2}月\\d{1,2}日)");

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

    /** Readability 负向特征：类名/ID 命中即视为噪声容器（评论/导航/广告/推荐等） */
    private static final String[] NEGATIVE_HINTS = {
            "comment", "comments", "footer", "sidebar", "related", "breadcrumb",
            "menu", "nav", "share", "social", "advert", "ad-", "ads", "banner",
            "popup", "modal", "newsletter", "subscribe", "login", "signup",
            "recommend", "topbar", "toolbar", "sponsor", "promo", "hidden",
            "tags", "meta", "author", "date", "copyright"
    };

    /** Readability 正向特征：类名/ID 命中即加权（正文容器常见命名） */
    private static final String[] POSITIVE_HINTS = {
            "article", "content", "main", "post", "entry", "story", "body", "text"
    };

    public WebPageReaderTool(Context context) {
        this.context = context;
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

    @Override
    public String getName() {
        return "webpage_reader";
    }

    @Override
    public String getDescription() {
        return "网页阅读工具（Jsoup解析）：获取网页内容/标题/正文/链接/摘要/关键词。read=读取解析；extract=提取关键信息；summarize=生成摘要；read_multiple=并行批量读取；follow_links=跟踪链接。单页上限5MB。";
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        String action = getStringParam(parameters, "action", "read");
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

    // ==================== 抓取 ====================

    /**
     * 抓取网页：OkHttp + 编码检测（header charset → meta charset → UTF-8）。
     * 流式读取，超 5MB 拒绝（防 OOM）。
     */
    private String fetchWebpage(String urlString) throws Exception {
        Request request = NetworkUtil.createRequestBuilder(urlString)
                .get()
                .build();

        try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("HTTP Error: " + response.code());
            }
            if (response.body() == null) return "";

            String contentType = response.header("Content-Type", "");
            byte[] bytes;
            try (InputStream in = response.body().byteStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_FETCH_BYTES) {
                        throw new Exception("网页过大（>5MB），已停止读取，请直接给出 content 或换精简页面");
                    }
                    out.write(buf, 0, n);
                }
                bytes = out.toByteArray();
            }
            return decodeHtmlBytes(bytes, contentType);
        }
    }

    /** 根据Content-Type和HTML meta标签检测编码并解码 */
    private String decodeHtmlBytes(byte[] bytes, String contentType) {
        String charset = null;

        // 1. 优先从Content-Type header提取charset
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                part = part.trim().toLowerCase();
                if (part.startsWith("charset=")) {
                    charset = part.substring("charset=".length()).replace("\"", "").trim();
                    break;
                }
            }
        }

        // 2. header 无 charset：以 ISO-8859-1 预览 HTML 前 2KB，从 <meta charset> 提取
        if (charset == null || charset.isEmpty()) {
            String head = new String(bytes, 0, Math.min(bytes.length, 2048), java.nio.charset.StandardCharsets.ISO_8859_1);
            Matcher m1 = Pattern.compile(
                    "<meta[^>]+charset=[\"']?([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(head);
            if (m1.find()) {
                charset = m1.group(1);
            }
        }

        // 3. 默认 UTF-8
        if (charset == null || charset.isEmpty()) {
            charset = "UTF-8";
        }

        try {
            return new String(bytes, charset);
        } catch (Exception e) {
            AILogger.w(TAG, "Unsupported charset: " + charset + ", falling back to UTF-8");
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // ==================== Jsoup 解析 ====================

    /** 解析 HTML 为 DOM（baseUri 用于相对链接转绝对） */
    private Document parseDoc(String html, String baseUri) {
        return Jsoup.parse(html == null ? "" : html, baseUri == null ? "" : baseUri);
    }

    private String extractTitle(Document doc) {
        String t = doc.title();
        return t == null ? "" : t.trim();
    }

    /** 兼容 name=description / property=og:description（属性顺序任意） */
    private String extractMetaDescription(Document doc) {
        Element meta = doc.selectFirst("meta[name=description]");
        if (meta == null) meta = doc.selectFirst("meta[property=og:description]");
        return meta != null ? meta.attr("content").trim() : "";
    }

    private String extractMetaKeywords(Document doc) {
        Element meta = doc.selectFirst("meta[name=keywords]");
        return meta != null ? meta.attr("content").trim() : "";
    }

    private List<String> extractHeadings(Document doc) {
        List<String> headings = new ArrayList<>();
        for (Element h : doc.select("h1, h2, h3")) {
            String t = h.text().trim();
            if (!t.isEmpty()) {
                headings.add(h.tagName().toUpperCase() + ": " + t);
            }
        }
        return headings;
    }

    /** 从纯文本提取日期（去重） */
    private List<String> extractDates(String text) {
        List<String> dates = new ArrayList<>();
        if (text == null) return dates;
        Set<String> seen = new HashSet<>();
        Matcher matcher = DATE_PATTERN.matcher(text);
        while (matcher.find()) {
            String d = matcher.group(1);
            if (d != null && seen.add(d)) {
                dates.add(d);
            }
        }
        return dates;
    }

    /** 提取链接：Jsoup 解析 <a href>，相对链接转绝对，排除 javascript:/mailto:/# 空链 */
    private List<Map<String, Object>> extractLinks(Document doc, String baseUrl) {
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        String baseDomain = getDomain(baseUrl);

        for (Element a : doc.select("a[href]")) {
            String href = a.attr("abs:href");
            if (href == null || href.isEmpty()) continue;
            String lower = href.toLowerCase();
            if (lower.startsWith("javascript:") || lower.startsWith("mailto:") || lower.startsWith("tel:")
                    || href.startsWith("#")) continue;
            if (href.length() > 256 || seenUrls.contains(href)) continue;
            seenUrls.add(href);

            String linkDomain = getDomain(href);
            Map<String, Object> linkInfo = new HashMap<>();
            linkInfo.put("url", href);
            linkInfo.put("text", a.text().trim());
            linkInfo.put("isInternal", baseDomain != null && baseDomain.equals(linkDomain));
            linkInfo.put("domain", linkDomain);

            links.add(linkInfo);
            if (links.size() >= MAX_LINKS) break;
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

    /**
     * Readability 风格正文定位：
     * 1. 语义标签优先：article / main / [role=main]
     * 2. 否则文本密度评分：块级元素按"正文长度 × 标点密度 ÷ 链接密度"打分，取最高
     * 3. 小元素自动向上提升父容器（合并多段落）
     * 4. 得分过低回退 body
     */
    private Element findMainContentElement(Document doc) {
        Document clone = doc.clone();
        clone.select("script,style,noscript,iframe,svg,form,button,select,input,nav,header,footer,aside").remove();
        Element body = clone.body();
        if (body == null) return clone;

        // 语义标签优先（最可靠）
        Element semantic = clone.selectFirst("article, [role=main], main");
        if (semantic != null) return semantic;

        Element best = body;
        double bestScore = 0;
        for (Element el : body.select("div, section, td, blockquote, article")) {
            double score = scoreContentElement(el) * hintFactor(el);
            if (score > bestScore) {
                bestScore = score;
                best = el;
            }
        }
        // 全页无正文特征：回退 body
        if (bestScore < 50) return body;

        // 向上提升：父节点文本显著更长且密度相近时，合并为正文容器
        Element parent = best.parent();
        while (parent != null && parent != body) {
            String pt = parent.text();
            if (pt == null || pt.trim().length() < best.text().trim().length() * 1.5) break;
            double parentScore = scoreContentElement(parent) * hintFactor(parent);
            if (parentScore < bestScore * 0.8) break;
            best = parent;
            bestScore = parentScore;
            parent = parent.parent();
        }
        return best;
    }

    /**
     * 类名/ID 特征加权（Readability）：
     * - 命中负向词（comment/footer/sidebar/ad 等）→ 0.05 强降权（基本淘汰）
     * - 命中正向词（article/content/post 等）→ 1.5 加权
     * 用词边界匹配（类名精确相等 / 连字符-下划线分段），避免 "ad" 误伤 "adventure"。
     */
    private double hintFactor(Element el) {
        String id = el.id() == null ? "" : el.id().toLowerCase();
        String[] classes = el.className().toLowerCase().split("\\s+");

        for (String neg : NEGATIVE_HINTS) {
            if (idMatches(id, neg)) return 0.05;
        }
        for (String cls : classes) {
            if (cls.isEmpty()) continue;
            for (String neg : NEGATIVE_HINTS) {
                if (wordMatches(cls, neg)) return 0.05;
            }
        }
        for (String pos : POSITIVE_HINTS) {
            if (idMatches(id, pos) || id.contains(pos)) return 1.5;
            for (String cls : classes) {
                if (wordMatches(cls, pos) || cls.contains(pos)) return 1.5;
            }
        }
        return 1.0;
    }

    private boolean idMatches(String id, String hint) {
        return id.equals(hint) || id.contains(hint + "-") || id.contains("-" + hint) || id.contains("_" + hint);
    }

    private boolean wordMatches(String word, String hint) {
        return word.equals(hint) || word.contains(hint + "-") || word.contains("-" + hint) || word.contains("_" + hint);
    }

    /**
     * Readability 密度评分：
     * - 文本过短(<80字)或链接占比>50%（导航/列表）直接淘汰
     * - 得分 = (正文文本长度) × (0.3 + 标点密度×0.8) × (1 - 链接密度)
     *   正文通常长文本、标点密集、链接稀疏；导航反之。
     */
    private double scoreContentElement(Element el) {
        String text = el.text();
        if (text == null) return 0;
        int textLen = text.trim().length();
        if (textLen < 80) return 0;

        int linkLen = 0;
        for (Element a : el.select("a")) {
            String at = a.text();
            linkLen += at == null ? 0 : at.length();
        }
        double linkDensity = (double) linkLen / textLen;
        if (linkDensity > 0.5) return 0;

        int punct = 0;
        for (int i = 0; i < textLen; i++) {
            char c = text.charAt(i);
            if (c == '，' || c == '。' || c == ',' || c == '.' || c == '；' || c == ';'
                    || c == '：' || c == ':' || c == '!' || c == '！' || c == '?' || c == '？') {
                punct++;
            }
        }
        double punctDensity = (double) punct / Math.max(1, textLen) * 100; // 每百字符标点数
        return (textLen - linkLen) * (0.3 + punctDensity * 0.8) * (1 - linkDensity);
    }

    /**
     * 提取正文文本：从定位到的正文容器按段落输出。
     * p/h/li/blockquote 齐全时逐段输出；纯 div 布局时退化为整块文本。
     */
    private String extractMainContent(Document doc) {
        Element main = findMainContentElement(doc);
        StringBuilder sb = new StringBuilder();
        for (Element el : main.select("p, h1, h2, h3, h4, li, blockquote, pre")) {
            String t = el.text().trim();
            if (!t.isEmpty()) {
                sb.append(t).append("\n\n");
            }
        }
        if (sb.length() == 0) {
            String t = main.text().trim();
            if (!t.isEmpty()) {
                sb.append(t);
            }
        }
        return sb.toString().trim();
    }

    /** 正文分片（段落 → slice，含摘要判定） */
    private List<Map<String, Object>> sliceContent(Document doc) {
        List<Map<String, Object>> slices = new ArrayList<>();
        String text = extractMainContent(doc);
        String[] paragraphs = text.split("\\n\\n+");
        for (int i = 0; i < paragraphs.length; i++) {
            String paragraph = paragraphs[i].trim();
            if (paragraph.length() > 30) {
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

    private boolean isSummarySection(String text) {
        String lowerText = text.toLowerCase();
        return lowerText.contains("总结") || lowerText.contains("摘要") ||
               lowerText.contains("简介") || lowerText.contains("概述") ||
               lowerText.contains("summary") || lowerText.contains("abstract");
    }

    private String classifyContent(String text) {
        String lowerText = text == null ? "" : text.toLowerCase();
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
        if (text == null) return keywords;
        String clean = text.toLowerCase();
        String[] words = clean.split("[\\s\\p{Punct}]+");

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
        Document doc = parseDoc(content, null);
        String text = extractMainContent(doc);
        if (text.isEmpty()) return "";

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
        Document doc = parseDoc(content, url);
        Map<String, Object> summary = new HashMap<>();
        summary.put("url", url);
        summary.put("title", extractTitle(doc));
        summary.put("category", classifyContent(extractMainContent(doc)));
        summary.put("summary", generateSummary(content, query));
        summary.put("keywords", extractKeywords(extractMainContent(doc)));

        List<String> headings = extractHeadings(doc);
        if (!headings.isEmpty()) {
            summary.put("headings", headings);
        }
        List<String> dates = extractDates(extractMainContent(doc));
        if (!dates.isEmpty()) {
            summary.put("dates", dates);
        }
        return summary;
    }

    private boolean isDynamicPage(Document doc) {
        String html = doc.html().toLowerCase();
        return html.contains("application/ld+json") ||
               html.contains("window.__initial_state__") ||
               html.contains("reactdom.hydrate") ||
               html.contains("window.datalayer");
    }

    private Map<String, Object> extractDynamicData(Document doc) {
        Map<String, Object> dynamicData = new HashMap<>();
        for (Element script : doc.select("script[type=application/ld+json]")) {
            String data = script.data() == null ? "" : script.data().trim();
            if (data.isEmpty()) continue;
            try {
                // 兼容 JSON 数组形式
                if (data.startsWith("[")) {
                    dynamicData.put("jsonLd", new org.json.JSONArray(data));
                } else {
                    dynamicData.put("jsonLd", new org.json.JSONObject(data));
                }
                break;
            } catch (Exception e) {
                AILogger.w(TAG, "Failed to parse JSON-LD: " + e.getMessage());
            }
        }
        return dynamicData;
    }

    /** 完整解析网页 */
    private Map<String, Object> parseWebpage(String content, String url) {
        Document doc = parseDoc(content, url);
        String mainText = extractMainContent(doc);

        Map<String, Object> result = new HashMap<>();
        result.put("title", extractTitle(doc));
        result.put("description", extractMetaDescription(doc));
        result.put("keywords", extractMetaKeywords(doc));
        result.put("headings", extractHeadings(doc));
        result.put("dates", extractDates(mainText));
        result.put("links", extractLinks(doc, url));
        result.put("text", mainText);                 // 纯正文（Agent 阅读主字段）
        result.put("contentSlices", sliceContent(doc));
        result.put("category", classifyContent(mainText));
        result.put("extractedKeywords", extractKeywords(mainText));
        result.put("summary", generateSummary(content, null));
        result.put("isDynamic", isDynamicPage(doc));

        if (isDynamicPage(doc)) {
            result.put("dynamicData", extractDynamicData(doc));
        }
        return result;
    }

    // ==================== 操作实现 ====================

    private AIToolResult readWebpage(Map<String, Object> parameters) {
        String url = getStringParam(parameters, "url", null);
        if (url == null || url.isEmpty()) {
            return new AIToolResult("缺少参数: url", parameters);
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
        String url = getStringParam(parameters, "url", null);
        String content = getStringParam(parameters, "content", null);

        if (url == null && content == null) {
            return new AIToolResult("缺少参数: url 或 content", parameters);
        }
        try {
            if (content == null) {
                content = fetchWebpage(url);
            }
            Document doc = parseDoc(content, url);
            String mainText = extractMainContent(doc);

            Map<String, Object> extracted = new HashMap<>();
            extracted.put("url", url);
            extracted.put("title", extractTitle(doc));
            extracted.put("description", extractMetaDescription(doc));
            extracted.put("keywords", extractMetaKeywords(doc));
            extracted.put("headings", extractHeadings(doc));
            extracted.put("dates", extractDates(mainText));
            extracted.put("links", extractLinks(doc, url));
            extracted.put("mainContent", mainText);
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
        String url = getStringParam(parameters, "url", null);
        String content = getStringParam(parameters, "content", null);
        String query = getStringParam(parameters, "query", null);

        if (url == null && content == null) {
            return new AIToolResult("缺少参数: url 或 content", parameters);
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

    /** 批量读取（并行抓取，失败单项不影响其他） */
    private AIToolResult readMultiple(Map<String, Object> parameters) {
        List<String> urls = new ArrayList<>();
        Object raw = parameters.get("urls");
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o != null) urls.add(String.valueOf(o));
            }
        } else if (raw instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) raw;
            for (int i = 0; i < arr.length(); i++) {
                urls.add(String.valueOf(arr.opt(i)));
            }
        } else if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (s.startsWith("[")) {
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(s);
                    for (int i = 0; i < arr.length(); i++) {
                        urls.add(String.valueOf(arr.opt(i)));
                    }
                } catch (Exception ignored) {
                }
            } else {
                urls.add(s);
            }
        }

        if (urls.isEmpty()) {
            return new AIToolResult("缺少参数: urls（URL列表）", parameters);
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, urls.size()), r -> {
            Thread t = new Thread(r, "WebReader-Batch");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (String url : urls) {
                futures.add(pool.submit(() -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("url", url);
                    try {
                        String content = fetchWebpage(url);
                        result.put("content", parseWebpage(content, url));
                        result.put("success", true);
                    } catch (Exception e) {
                        result.put("error", e.getMessage());
                        result.put("success", false);
                    }
                    return result;
                }));
            }

            List<Map<String, Object>> results = new ArrayList<>();
            int successCount = 0;
            int failCount = 0;
            for (Future<Map<String, Object>> f : futures) {
                try {
                    Map<String, Object> r = f.get(30, TimeUnit.SECONDS);
                    if (Boolean.TRUE.equals(r.get("success"))) successCount++; else failCount++;
                    results.add(r);
                } catch (Exception e) {
                    failCount++;
                    Map<String, Object> r = new HashMap<>();
                    r.put("url", "unknown");
                    r.put("success", false);
                    r.put("error", "抓取超时或失败: " + e.getMessage());
                    results.add(r);
                }
            }

            Map<String, Object> finalResult = new HashMap<>();
            finalResult.put("total", urls.size());
            finalResult.put("successCount", successCount);
            finalResult.put("failCount", failCount);
            finalResult.put("results", results);
            finalResult.put("status", "completed");
            return new AIToolResult(finalResult, parameters);
        } finally {
            pool.shutdownNow();
        }
    }

    private AIToolResult followLinks(Map<String, Object> parameters) {
        String url = getStringParam(parameters, "url", null);
        int maxDepth = getIntParam(parameters, "maxDepth", 2);
        int maxLinks = getIntParam(parameters, "maxLinks", 10);
        String query = getStringParam(parameters, "query", null);

        if (url == null) {
            return new AIToolResult("缺少参数: url", parameters);
        }
        if (maxDepth <= 0) maxDepth = 2;
        if (maxLinks <= 0) maxLinks = 10;

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
        score /= Math.max(1, queryWords.length) * 3.0;
        return Math.min(1.0, score);
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: read(默认), extract, summarize, read_multiple, follow_links");
        descriptions.put("url", "网页URL（必填）");
        descriptions.put("content", "网页内容（与url二选一，可传已有HTML/文本）");
        descriptions.put("query", "搜索查询词（用于摘要相关性）");
        descriptions.put("urls", "URL列表（用于read_multiple操作，并行抓取）");
        descriptions.put("maxDepth", "最大链接深度（follow_links用，默认2）");
        descriptions.put("maxLinks", "最大链接数量（follow_links用，默认10）");
        return descriptions;
    }
}
