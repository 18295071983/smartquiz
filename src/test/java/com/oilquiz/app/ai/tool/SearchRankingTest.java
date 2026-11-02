package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索结果重排（栏目索引页降权）与 isDynamic 口径。
 *
 * <p>真机提过两条：① 栏目索引页（`col/col`、`index.html`）关键词密度高，容易压过具体内容页；
 * ② `get_dynamic_content` 对静态页也回 `isDynamic=true`，但 `dynamicData` 是空的。
 * 这两条都不是"看起来对不对"，而是能用固定输入钉住的判定。</p>
 */
public class SearchRankingTest {

    // ==================== 栏目页判定 ====================

    @Test
    public void channelAndIndexPagesAreDetected() {
        assertTrue(NetworkSearchTool.isLikelyListPage("https://guangxi.chinatax.gov.cn/col/col1234/index.html"));
        assertTrue(NetworkSearchTool.isLikelyListPage("https://www.gov.cn/zhengce/"));
        assertTrue(NetworkSearchTool.isLikelyListPage("https://news.example.com/channel/tech"));
        assertTrue(NetworkSearchTool.isLikelyListPage("https://example.com/list/"));
    }

    @Test
    public void concreteArticlesAreNotTreatedAsListPages() {
        assertFalse("带长数字 ID 的具体文章",
                NetworkSearchTool.isLikelyListPage("https://www.chinanews.com.cn/gn/2026/11-02/10724687.shtml"));
        assertFalse("栏目路径下但带条目 ID",
                NetworkSearchTool.isLikelyListPage("https://guangxi.chinatax.gov.cn/xwdt/tpxw/202610/t20261028_441843.html"));
        assertFalse("/content/ 明确是内容页",
                NetworkSearchTool.isLikelyListPage("https://www.gov.cn/zhengce/content/202511/content_1.html"));
        assertFalse("百度百科型条目", NetworkSearchTool.isLikelyListPage("https://baike.example.com/item/abc"));
    }

    @Test
    public void demotePutsListPagesLastAndKeepsOriginalOrder() {
        List<Map<String, String>> results = new ArrayList<>();
        results.add(item("文章A", "https://a.com/news/2026/11-02/10724687.shtml"));
        results.add(item("栏目", "https://a.com/col/col12/index.html"));
        results.add(item("文章B", "https://a.com/article/998877"));

        int demoted = NetworkSearchTool.demoteListPages(results);

        assertEquals("只有一条栏目页被挪动", 1, demoted);
        assertEquals("文章A", results.get(0).get("title"));
        assertEquals("文章B", results.get(1).get("title"));
        assertEquals("栏目", results.get(2).get("title"));
        assertEquals("原序号要留着可回溯", "1", results.get(2).get("original_index"));
    }

    @Test
    public void demoteIsNoOpWhenEverythingIsArticle() {
        List<Map<String, String>> results = new ArrayList<>();
        results.add(item("A", "https://a.com/news/2026/11-02/10724687.shtml"));
        results.add(item("B", "https://b.com/article/123456"));

        assertEquals("没有栏目页就不动手", 0, NetworkSearchTool.demoteListPages(results));
        assertEquals("A", results.get(0).get("title"));
    }

    private static Map<String, String> item(String title, String url) {
        Map<String, String> m = new HashMap<>();
        m.put("title", title);
        m.put("url", url);
        return m;
    }

    // ==================== isDynamic 口径 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String html) throws Exception {
        WebPageReaderTool tool = new WebPageReaderTool(null);
        Method m = WebPageReaderTool.class.getDeclaredMethod(
                "parseWebpage", String.class, String.class, boolean.class, int.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(tool, html, "https://example.com/p.html", false, 200);
    }

    /** 静态页只有 ld+json 时：isDynamic=false，但结构化数据要如实给。 */
    @Test
    public void jsonLdAloneDoesNotMakePageDynamic() throws Exception {
        String html = "<html><head>"
                + "<script type=\"application/ld+json\">{\"@type\":\"Article\",\"headline\":\"x\"}</script>"
                + "</head><body><article><p>这是一篇普通静态文章，正文足够长，用来占位测试解析行为。</p>"
                + "</article></body></html>";

        Map<String, Object> parsed = parse(html);

        assertEquals("静态页不该被标成动态", Boolean.FALSE, parsed.get("isDynamic"));
        assertEquals("结构化数据要单独给", Boolean.TRUE, parsed.get("hasStructuredData"));
    }

    @Test
    public void hydrationStateMarksPageAsDynamic() throws Exception {
        String html = "<html><body><div id=\"app\"></div>"
                + "<script>window.__INITIAL_STATE__ = {\"a\":1};</script></body></html>";

        Map<String, Object> parsed = parse(html);

        assertEquals("带 hydration state 的页要标动态", Boolean.TRUE, parsed.get("isDynamic"));
    }
}
