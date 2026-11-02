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
import java.util.Arrays;
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
        @Param(name = "maxLinks", type = "int", description = "最大链接数量(默认10)", required = false),
        // WEB-PAGE(2026-11-02)：长正文分页——一次拿不完就带 start_char/max_chars 续读，
        // 返回里带 total_chars/next_start，模型据此决定是否继续，不再靠猜。
        @Param(name = "start_char", type = "int", description = "read 正文起始字符偏移（默认 0；分页续读用）", required = false),
        @Param(name = "max_chars", type = "int", description = "read 本次返回的最大字符数（默认 0=整篇；分页建议 4000）", required = false),
        // TIMEOUT(2026-11-02)：真机实测政府站会挂 35s；默认 12s，超时如实报错而不是一直等
        @Param(name = "timeout", type = "int", description = "读取超时秒数（默认 12，最小 3；站点慢就调大）", required = false)
    }
)
public class WebPageReaderTool implements AITool {
    private static final String TAG = "WebPageReaderTool";
    private static final int MAX_FETCH_BYTES = 5 * 1024 * 1024; // 单页最大 5MB
    private static final int MAX_LINKS = 30;
    private final Context context;

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})|(\\d{1,2}[-/]\\d{1,2}[-/]\\d{4})|(\\d{4}年\\d{1,2}月\\d{1,2}日)");

    /** 英文停用词：英文页的关键词以前全是 are/the/as/not/we 这种，没有检索价值。 */
    private static final Set<String> EN_STOP_WORDS = new HashSet<>(Arrays.asList(
            "the", "of", "and", "to", "in", "is", "are", "for", "with", "that", "this",
            "as", "on", "by", "an", "be", "it", "or", "from", "at", "you", "your", "we",
            "not", "but", "they", "their", "has", "have", "was", "were", "will", "can",
            "if", "then", "than", "so", "such", "these", "those", "may", "use", "used"
    ));

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

    // ===== 类型安全的参数提取 =====

    /** 数值参数解析（字符串/数字都收；解析失败用默认值）。 */
    private int parseIntParam(Map<String, Object> parameters, String key, int defaultValue) {
        try {
            Object v = parameters.get(key);
            if (v == null) return defaultValue;
            if (v instanceof Number) return ((Number) v).intValue();
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? defaultValue : Integer.parseInt(s);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

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
     * 抓取结果：正文 HTML + 重定向后的最终 URL + HTTP 状态。
     *
     * <p>FINALURL(2026-11-02, 真机)：以前只返回 html，相对链接是按**请求 URL** 解析的，
     * 站点一旦重定向（`/tpxw/202610/` → 首页）就会拼出 `…/tpxw/202610/xwdt/tpxw/202610/…`
     * 这种重复前缀；软 404 也完全看不出来（站点 302 到首页仍是 200 + success）。
     * 现在拿到 OkHttp 的最终地址，链接按它解析、重定向单独标记。</p>
     */
    private static final class FetchResult {
        final String html;
        final String finalUrl;
        final int status;

        FetchResult(String html, String finalUrl, int status) {
            this.html = html;
            this.finalUrl = finalUrl;
            this.status = status;
        }
    }

    /** 默认读取超时：政府站偶发 30s+ 挂起，超时要如实报错而不是拖垮整条链路。 */
    private static final int DEFAULT_TIMEOUT_MS = 12_000;

    /**
     * 抓取网页：OkHttp + 编码检测（header charset → meta charset → UTF-8 → GB18030）。
     * 流式读取，超 5MB 拒绝（防 OOM）；callTimeout 防止站点挂起。
     * 抓取前过 {@link WebFetchPolicy}：robots.txt 检查 + 按域名限速（合规规则见 docs/LICENSING_AND_COMPLIANCE.md）。
     */
    private FetchResult fetch(String urlString, int timeoutMs) throws Exception {
        WebFetchPolicy.beforeFetch(urlString);
        try {
            return fetchUnchecked(urlString, timeoutMs);
        } finally {
            WebFetchPolicy.afterFetch(urlString);
        }
    }

    private FetchResult fetchUnchecked(String urlString, int timeoutMs) throws Exception {
        Request request = NetworkUtil.createRequestBuilder(urlString)
                .get()
                .build();

        okhttp3.OkHttpClient client = NetworkUtil.getClient();
        if (timeoutMs > 0) {
            client = client.newBuilder()
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build();
        }

        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            String finalUrl = response.request().url().toString();
            if (!response.isSuccessful()) {
                throw new Exception("HTTP " + code
                        + (finalUrl.equals(urlString) ? "" : "（重定向到 " + finalUrl + "）"));
            }
            if (response.body() == null) return new FetchResult("", finalUrl, code);

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
            return new FetchResult(decodeHtmlBytes(bytes, contentType), finalUrl, code);
        }
    }

    /** 只要正文 HTML 的调用方用这个（沿用默认超时）。 */
    private String fetchWebpage(String url) throws Exception {
        return fetch(url, DEFAULT_TIMEOUT_MS).html;
    }

    /** 根据 Content-Type / meta / 内容探测编码 —— 统一走 {@link HtmlContentExtractor#decodeHtml}。 */
    private String decodeHtmlBytes(byte[] bytes, String contentType) {
        return HtmlContentExtractor.decodeHtml(bytes, contentType);
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
     * 正文容器定位 —— 统一走 {@link HtmlContentExtractor#mainContent}。
     *
     * <p>REFACTOR(2026-11-02)：这一套 Readability 逻辑（语义标签 → 密度打分 → 父容器提升）
     * 原来在本类里，network_search 又各写了一份正则版；两边行为不一致，真机问题就出在走错分支。
     * 现在抽到 {@code HtmlContentExtractor} 单一实现，两个工具共用。</p>
     */
    private Element findMainContentElement(Document doc) {
        Element main = HtmlContentExtractor.mainContent(doc);
        return main == null ? doc : main;
    }

    /** 提取正文文本（段落间空行）—— 与 network_search 同一条输出路径。 */
    private String extractMainContent(Document doc) {
        return HtmlContentExtractor.toText(findMainContentElement(doc));
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
        // KEYWORD(2026-11-02, 真机)：IANA 页曾经输出 are/the/as/not/we，税局页输出 2026/10/09/30。
        // 两件事：① 英文停用词同样要滤；② 纯数字不是关键词（日期、条款号交给 dates 字段）。
        String[] words = clean.split("[^\\u4e00-\\u9fa5a-zA-Z]+");

        Map<String, Integer> wordCount = new HashMap<>();
        for (String word : words) {
            if (word.length() < 2) continue;
            if (STOP_WORDS.contains(word) || EN_STOP_WORDS.contains(word)) continue;
            if (word.matches("\\d+")) continue;
            wordCount.put(word, wordCount.getOrDefault(word, 0) + 1);
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

    /**
     * 是否真的是"靠 JS 状态渲染"的页面。
     *
     * <p>DYNAMIC-FLAG(2026-11-02, 真机)：以前把 `application/ld+json` 也算动态——那只是 SEO 元数据，
     * 静态文章页几乎都有，于是静态页也报 isDynamic=true。现在只认 hydration / state 容器标记。</p>
     */
    private boolean isDynamicPage(Document doc) {
        String html = doc.html().toLowerCase();
        return html.contains("window.__initial_state__")
                || html.contains("window.__next_data__")
                || html.contains("reactdom.hydrate")
                || html.contains("window.datalayer");
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

    /** 完整解析网页（含质量结论：page_type / quality / verdict） */
    private Map<String, Object> parseWebpage(String content, String url) {
        return parseWebpage(content, url, false, 200);
    }

    private Map<String, Object> parseWebpage(String content, String url, boolean redirected, int httpStatus) {
        Document doc = parseDoc(content, url);
        Element main = findMainContentElement(doc);
        String mainText = HtmlContentExtractor.toText(main);


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

        // QUALITY(2026-11-02)：让"读到的其实不是这篇文章"在字段层面露馅
        HtmlContentExtractor.Quality quality =
                HtmlContentExtractor.assess(doc, main, mainText, redirected, httpStatus);
        Map<String, Object> q = new HashMap<>();
        q.put("score", quality.score);
        q.put("page_type", quality.pageType);
        q.put("flags", quality.flags);
        result.put("quality", q);
        result.put("verdict", quality.verdict);

        // 结构化数据（ld+json）是元数据，不等于"动态页"，单独给字段；真挖到状态才放进 dynamicData
        Map<String, Object> dynamic = extractDynamicData(doc);
        result.put("hasStructuredData", dynamic.containsKey("jsonLd"));
        if (!dynamic.isEmpty()) {
            result.put("dynamicData", dynamic);
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
            int timeoutSec = getIntParam(parameters, "timeout", 12);
            if (timeoutSec <= 0) timeoutSec = 12;
            FetchResult fetched = fetch(url, timeoutSec * 1000);
            boolean redirected = fetched.finalUrl != null && !fetched.finalUrl.equals(url);
            // 相对链接按**最终 URL** 解析（重定向后站点目录变了，按请求 URL 会拼出重复前缀）
            Map<String, Object> result = parseWebpage(fetched.html, fetched.finalUrl, redirected, fetched.status);

            // WEB-PAGE(2026-11-02)：显式给 start_char/max_chars 时才切片；不带参数保持整篇老行为。
            int startChar = parseIntParam(parameters, "start_char", 0);
            int maxChars = parseIntParam(parameters, "max_chars", 0);
            if ((startChar > 0 || maxChars > 0) && result.get("text") instanceof String) {
                String full = (String) result.get("text");
                int total = full.length();
                if (startChar < 0) startChar = 0;
                if (startChar > total) startChar = total;
                int end = maxChars > 0 ? Math.min(total, startChar + maxChars) : total;
                result.put("text", full.substring(startChar, end));
                Map<String, Object> page = new HashMap<>();
                page.put("total_chars", total);
                page.put("start_char", startChar);
                page.put("end_char", end);
                page.put("truncated", end < total);
                page.put("next_start", end);
                result.put("page", page);
            }

            Map<String, Object> finalResult = new HashMap<>();
            finalResult.put("url", url);
            finalResult.put("requested_url", url);
            finalResult.put("final_url", fetched.finalUrl);
            finalResult.put("http_status", fetched.status);
            finalResult.put("status", "success");
            finalResult.put("content", result);
            addTrustFields(finalResult, url, fetched, result);
            return new AIToolResult(finalResult, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to read webpage: " + e.getMessage(), e);
            return new AIToolResult("读取网页失败: " + e.getMessage(), parameters);
        }
    }

    /**
     * 可信度字段：让"读到的其实不是这篇文章"这件事对上层可见，而不是伪装成 success。
     * 判定看四件客观事实：有没有被重定向、HTTP 状态、正文有多长、正文里链接占比多高。
     */
    private void addTrustFields(Map<String, Object> out, String requestedUrl, FetchResult fetched,
                                Map<String, Object> content) {
        List<String> warnings = new ArrayList<>();
        boolean redirected = fetched.finalUrl != null && !fetched.finalUrl.equals(requestedUrl);
        if (redirected) {
            warnings.add("请求地址被重定向到 " + fetched.finalUrl + "（常见于软 404 / 跳首页）");
        }

        String text = content.get("text") instanceof String ? (String) content.get("text") : "";
        if (text.trim().length() < 100) {
            warnings.add("正文只有 " + text.trim().length() + " 字，可能是反爬、跳转页或非文章页");
        }
        Object cat = content.get("category");
        if ("其他".equals(cat) && text.trim().length() < 400) {
            warnings.add("正文很短且无法归类，建议换来源");
        }

        Object verdict = content.get("verdict");
        if ("not_article".equals(verdict) || "rejected".equals(verdict)) {
            warnings.add("质量判定为 " + verdict + "（page_type="
                    + ((Map<?, ?>) content.get("quality")).get("page_type") + "），不建议当正文使用");
        }

        out.put("degraded", !warnings.isEmpty());
        out.put("warnings", warnings);
    }

    private AIToolResult extractInfo(Map<String, Object> parameters) {
        String url = getStringParam(parameters, "url", null);
        String content = getStringParam(parameters, "content", null);

        if (url == null && content == null) {
            return new AIToolResult("缺少参数: url 或 content", parameters);
        }
        try {
            FetchResult fetched = null;
            if (content == null) {
                int timeoutSec = getIntParam(parameters, "timeout", 12);
                if (timeoutSec <= 0) timeoutSec = 12;
                fetched = fetch(url, timeoutSec * 1000);
                content = fetched.html;
            }
            String baseUrl = fetched != null ? fetched.finalUrl : url;
            Document doc = parseDoc(content, baseUrl);
            Element main = findMainContentElement(doc);
            String mainText = HtmlContentExtractor.toText(main);

            Map<String, Object> extracted = new HashMap<>();
            extracted.put("url", url);
            extracted.put("title", extractTitle(doc));
            extracted.put("description", extractMetaDescription(doc));
            extracted.put("keywords", extractMetaKeywords(doc));
            extracted.put("headings", extractHeadings(doc));
            extracted.put("dates", extractDates(mainText));
            extracted.put("links", extractLinks(doc, baseUrl));
            extracted.put("mainContent", mainText);
            extracted.put("summary", generateSummary(content, null));

            // QUERY-FILTER(2026-11-02, 真机)：以前 query 只多回一个 keywords 字段，正文一点没筛，
            // 调用方还以为结果已经被筛过。现在按 query 在正文段落里检索，回命中段落 + 命中数。
            String query = getStringParam(parameters, "query", null);
            if (query != null && !query.trim().isEmpty()) {
                List<Map<String, Object>> matches = HtmlContentExtractor.passages(mainText, query, 5);
                extracted.put("query", query.trim());
                extracted.put("matches", matches);
                extracted.put("matchCount", matches.size());
            }

            boolean redirected = fetched != null && fetched.finalUrl != null && !fetched.finalUrl.equals(url);
            HtmlContentExtractor.Quality quality = HtmlContentExtractor.assess(
                    doc, main, mainText, redirected, fetched == null ? 200 : fetched.status);
            Map<String, Object> q = new HashMap<>();
            q.put("score", quality.score);
            q.put("page_type", quality.pageType);
            q.put("flags", quality.flags);
            extracted.put("quality", q);
            extracted.put("verdict", quality.verdict);

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            if (fetched != null) {
                result.put("requested_url", url);
                result.put("final_url", fetched.finalUrl);
                result.put("http_status", fetched.status);
                addTrustFields(result, url, fetched, extracted);
            }
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
        // DEPTH(2026-11-02, 真机)：以前 maxDepth=1 时一个子页都不跟（0 页），maxDepth=2 却跟 4 页。
        // 语义定为"maxDepth = 往下跟几层"：depth 0 是根页，maxDepth=1 就跟一层子页。
        if (depth > maxDepth || visitedUrls.size() >= maxLinks) return;
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

            // FOLLOW-CAP(2026-11-02, 真机)：单个子页曾原样返回 303,838 字符（协议注册表整页），
            // 靠写临时文件兜底。批量跟随里每页只留摘要段，超了就截断并标出来。
            String subText = parsed.get("text") instanceof String ? (String) parsed.get("text") : "";
            int followCap = 4000;
            result.put("text", subText.length() > followCap ? subText.substring(0, followCap) : subText);
            result.put("total_chars", subText.length());
            result.put("truncated", subText.length() > followCap);
            result.put("summary", parsed.get("summary"));

            if (query != null) {
                double relevance = calculateRelevance((String) parsed.get("title"),
                        (String) parsed.get("summary"), query);
                result.put("relevance", relevance);
            }

            results.add(result);

            if (links != null && depth < maxDepth) {
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
        descriptions.put("timeout", "读取超时秒数（默认12；站点慢就调大）");
        descriptions.put("start_char", "正文起始字符偏移（read 分页续读用，默认0）");
        descriptions.put("max_chars", "本次返回的最大字符数（read 分页用，默认0=整篇）");
        return descriptions;
    }
}
