package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Assume;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 真页探针（默认跳过，`LIVE_NET=true` 时运行）。
 *
 * <p>WHY：前几轮的断言都跑在硬编码的 HTML 片段上——等于拿"我自己写的页面"证明"我能解析页面"，
 * 真机一上线就露馅（行内换行、模板导航、侧栏污染全是真实页面的形态）。这个测试直接联网抓
 * 公开页面，走与线上完全相同的 {@code extract_info} 管线，检查抽取结果的"卫生"。</p>
 *
 * <p>跑法：{@code $env:LIVE_NET="true"; ./gradlew testDebugUnitTest --tests "*LiveExtractionProbeTest"}</p>
 */
public class LiveExtractionProbeTest {

    /** 门户/列表页：只验"不脏"，不要求正文长度。 */
    private static final String[] PORTAL_URLS = {
            "https://www.people.com.cn/",
            "https://news.sina.com.cn/"
    };

    /** 文章页：正文长度也要像一篇文章。 */
    private static final String[] ARTICLE_URLS = {
            "https://www.iana.org/help/example-domains",
            "https://www.rfc-editor.org/rfc/rfc2606.txt"
    };

    /** JS/CSS 语法痕迹（不能只查单词：RFC 正文里本来就会出现 function 这个词）。 */
    private static final String[] JS_TOKENS = {
            "function(", "function (", "queryselector", "document.write", "window.",
            "=>", "{display", "var "
    };

    private static final Pattern TAG_LIKE = Pattern.compile("<[a-zA-Z/][^>]{0,20}>");

    private static String fetch(String url) throws Exception {
        // 与线上同一条解码路径：响应头 → meta → 严格 UTF-8 → GB18030
        org.jsoup.Connection.Response resp = Jsoup.connect(url)
                // 抓取用桌面 UA：移动 UA 常拿到另一套（更依赖 JS 的）DOM，测不出正文抽取的真实效果
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .timeout(20000)
                .maxBodySize(3 * 1024 * 1024)
                .ignoreContentType(true)
                .execute();
        return HtmlContentExtractor.decodeHtml(resp.bodyAsBytes(), resp.contentType());
    }

    /** 用线上同一条管线（PageContent）算出正文长度。 */
    private static int extractedTextLength(String html, String url) throws Exception {
        Class<?> pc = Class.forName("com.oilquiz.app.ai.tool.NetworkSearchTool$PageContent");
        Method of = pc.getDeclaredMethod("of", String.class, String.class);
        of.setAccessible(true);
        Object page = of.invoke(null, html, url);
        Field text = pc.getDeclaredField("text");
        text.setAccessible(true);
        return String.valueOf(text.get(page)).length();
    }

    @Test
    public void realPagesComeOutClean() throws Exception {
        Assume.assumeTrue("需要联网：设置 LIVE_NET=true 才跑", "true".equals(System.getenv("LIVE_NET")));

        Method extractInfo = NetworkSearchTool.class.getDeclaredMethod("extractInfo", Map.class);
        extractInfo.setAccessible(true);
        NetworkSearchTool tool = new NetworkSearchTool(null);

        java.util.LinkedHashMap<String, Boolean> targets = new java.util.LinkedHashMap<>();
        for (String u : ARTICLE_URLS) targets.put(u, true);
        for (String u : PORTAL_URLS) targets.put(u, false);
        // 从新闻列表页动态发现一篇中文正文页，避免"只挑好抓的固定页"。
        // 选 chinanews：正文是服务端直出的 <p>，适合验证"本地 DOM 抽取"这条路径；
        // （people.com.cn 的大多数正文页是 JS 渲染，本地抓不到内容 —— 那是 read_url 的活，不是抽取器的锅）
        StringBuilder report = new StringBuilder("\n==== live extraction ====\n");
        try {
            // 必须带 baseUri 解析：列表页里的链接多是 "/gn/2026/..." 这种相对路径，
            // 没有 base 时 absUrl() 返回空串（第一次就是这样漏掉所有文章链接的）。
            String listUrl = "https://www.chinanews.com.cn/scroll-news/news1.html";
            Document list = Jsoup.parse(fetch(listUrl), listUrl);
            for (org.jsoup.nodes.Element a : list.select("a[href]")) {
                String href = a.absUrl("href");
                if (href.isEmpty()) href = a.attr("href");
                if (href.matches("https?://www\\.chinanews\\.com\\.cn/[a-z]+/\\d{4}/\\d{2}-\\d{2}/\\d+\\.shtml")) {
                    targets.put(href, true);
                    break;
                }
            }
            if (targets.size() == ARTICLE_URLS.length + PORTAL_URLS.length) {
                report.append("discover: no article link matched on chinanews list\n");
            }
        } catch (Exception e) {
            report.append("discover failed: ").append(e).append('\n');
        }
        int checked = 0;

        for (Map.Entry<String, Boolean> entry : targets.entrySet()) {
            String url = entry.getKey();
            boolean isArticle = entry.getValue();
            String html;
            try {
                html = fetch(url);
            } catch (Exception e) {
                report.append(url).append(" -> SKIP ").append(e.getMessage()).append('\n');
                continue;
            }

            Map<String, Object> params = new HashMap<>();
            params.put("content", html);
            params.put("url", url);
            AIToolResult result = (AIToolResult) extractInfo.invoke(tool, params);
            assertTrue(url + " 抽取失败: " + result.getErrorMessage(), result.isSuccess());

            @SuppressWarnings("unchecked")
            Map<String, Object> extracted = (Map<String, Object>) ((Map<String, Object>) result.getResult()).get("extracted");
            assertNotNull(extracted);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> slices = (List<Map<String, Object>>) extracted.get("contentSlices");
            @SuppressWarnings("unchecked")
            List<String> keywords = (List<String>) extracted.get("keywords");
            @SuppressWarnings("unchecked")
            List<String> headings = (List<String>) extracted.get("headings");
            @SuppressWarnings("unchecked")
            List<String> dates = (List<String>) extracted.get("dates");

            for (Map<String, Object> slice : slices) {
                String content = String.valueOf(slice.get("content"));
                String lower = content.toLowerCase();
                for (String token : JS_TOKENS) {
                    assertFalse(url + " 正文切片残留脚本痕迹 [" + token + "]: " + content,
                            lower.contains(token));
                }
                assertFalse(url + " 正文切片里有 HTML 标签: " + content, TAG_LIKE.matcher(content).find());
            }

            for (String kw : keywords) {
                String lower = kw.toLowerCase();
                for (String token : new String[]{"function", "var", "return", "queryselector", "document"}) {
                    assertFalse(url + " keywords 出现 JS 词 [" + kw + "]", lower.equals(token));
                }
            }

            for (String d : dates) {
                assertTrue(url + " 日期不是 ISO: " + d, d.matches("\\d{4}-\\d{2}-\\d{2}"));
            }

            int textLen = extractedTextLength(html, url);
            if (isArticle) {
                assertTrue(url + " 正文太短（" + textLen + " 字），文章页不该只剩导航", textLen > 400);
                Object verdict = extracted.get("verdict");
                assertFalse(url + " 文章页被判成不可用: verdict=" + verdict,
                        "not_article".equals(verdict) || "rejected".equals(verdict));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> quality = (Map<String, Object>) extracted.get("quality");
            report.append(String.format(
                    "%-52s text=%5d slices=%2d heads=%2d dates=%2d verdict=%-11s type=%-8s kw=%s%n",
                    url, textLen, slices.size(), headings.size(), dates.size(),
                    extracted.get("verdict"), quality == null ? "?" : quality.get("page_type"),
                    keywords.subList(0, Math.min(6, keywords.size()))));
            if (!slices.isEmpty()) {
                String first = String.valueOf(slices.get(0).get("content")).replace('\n', ' ');
                report.append("      first: ")
                        .append(first, 0, Math.min(80, first.length()))
                        .append('\n');
            }
            checked++;
        }

        report.append("checked=").append(checked).append('\n');
        System.out.println(report);
        assertTrue("至少要有 3 个页面能抓下来做验证", checked >= 3);
    }
}
