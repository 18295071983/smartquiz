package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 页面质量评估（page_type / quality / verdict）与 query 段落检索。
 *
 * <p>背景：真机上税局文章失效后 301 回首页，工具把 3705 字导航当正文返回、category 还标成
 * "新闻资讯"，调用方毫无察觉。这一组用例钉的是"字段层面要能看出这页不是文章"，
 * 以及 `extract` 的 query 必须真的筛出命中段落（以前只多回一个字段）。</p>
 */
public class PageQualityTest {

    private static HtmlContentExtractor.Quality assess(String html, boolean redirected) {
        Document doc = HtmlContentExtractor.parse(html, "https://example.com/a/b.html");
        Element main = HtmlContentExtractor.mainContent(doc);
        String text = HtmlContentExtractor.toText(main);
        return HtmlContentExtractor.assess(doc, main, text, redirected, 200);
    }

    private static String articlePage() {
        String[] paragraphs = {
                "自2026年1月1日起，对符合条件的新能源汽车减半征收车辆购置税，每辆新能源乘用车减税额不超过1.5万元。",
                "适用车型以工业和信息化部发布的目录为准，购置日期以机动车销售统一发票载明的日期确定。",
                "纳税人需要在办理车辆登记前完成申报手续，逾期申报的按照征管法有关规定处理。",
                "对购置日期在2027年12月31日之前的车辆，可以享受本公告规定的减免政策，无需另行备案。",
                "税务机关将通过电子税务局推送办理提醒，纳税人也可以携带资料到办税服务厅现场办理。",
                "本公告由财政部会同税务总局、工业和信息化部负责解释，自发布之日起施行。"
        };
        StringBuilder sb = new StringBuilder("<html><head><title>购置税政策问答</title></head><body><article>");
        sb.append("<h1>新能源汽车购置税减免政策解答</h1>");
        for (String p : paragraphs) {
            sb.append("<p>").append(p).append("</p>");
        }
        return sb.append("</article></body></html>").toString();
    }

    private static String listPage() {
        StringBuilder sb = new StringBuilder("<html><body><div class='nav'>");
        for (int i = 0; i < 40; i++) {
            sb.append("<a href='https://example.com/n/").append(i).append("'>新闻标题").append(i).append("</a> ");
        }
        return sb.append("</div></body></html>").toString();
    }

    @Test
    public void articlePageScoresHighAndPasses() {
        HtmlContentExtractor.Quality q = assess(articlePage(), false);

        assertEquals("article", q.pageType);
        assertEquals("pass", q.verdict);
        // 短文章（200~600 字）落在 pass 的下沿即可，0.7 以上是给 1500 字以上的长文留的
        assertTrue("像文章就不该被判成低质，实际 " + q.score, q.score >= 0.5);
    }

    /** 软 404 的典型形态：跳回首页后拿到一屏导航，必须判成 list/not_article，而不是"新闻资讯"。 */
    @Test
    public void linkFarmIsNotArticle() {
        HtmlContentExtractor.Quality q = assess(listPage(), false);

        assertEquals("list", q.pageType);
        assertEquals("not_article", q.verdict);
        assertFalse("列表页不该通过", "pass".equals(q.verdict));
    }

    @Test
    public void emptyPageIsNotArticle() {
        HtmlContentExtractor.Quality q = assess("<html><body><div>知乎，让每一次点击都充满意义</div></body></html>", false);

        assertEquals("empty", q.pageType);
        assertEquals("not_article", q.verdict);
    }

    /** 301 回首页时，即使抓到的是篇文章也要标 degraded（重定向本身就是可信度问题）。 */
    @Test
    public void redirectedPageIsDegradedEvenIfContentLooksGood() {
        HtmlContentExtractor.Quality q = assess(articlePage(), true);

        assertEquals("article", q.pageType);
        assertEquals("degraded", q.verdict);
        assertTrue("要带上 redirected 标记", q.flags.contains("redirected"));
    }

    @Test
    public void queryPassagesReturnHitParagraphsInScoreOrder() {
        String text = "第一段讲的是别的事情，与问题无关，只是用来占位的一段话。\n\n"
                + "对购置日期在2026年1月1日至2027年12月31日期间的新能源汽车，减半征收车辆购置税，"
                + "每辆减税上限为1.5万元。\n\n"
                + "申请流程：纳税人需要在购车后六十日内申报，逾期不再受理。";

        List<Map<String, Object>> hits = HtmlContentExtractor.passages(text, "减半征收、减税上限", 5);

        assertFalse("query 应该能命中段落", hits.isEmpty());
        assertTrue("命中的是政策那段",
                String.valueOf(hits.get(0).get("content")).contains("减半征收"));
        assertTrue("命中段落要带分值", ((Integer) hits.get(0).get("score")) >= 2);
    }

    @Test
    public void queryWithNoHitReturnsNothing() {
        List<Map<String, Object>> hits = HtmlContentExtractor.passages(
                "今天天气不错，适合出门散步。\n\n顺便买点水果回家。", "量子计算、光刻机", 5);
        assertTrue("没有命中就该是空的，不要硬凑", hits.isEmpty());
    }

    /** extract 的 query 要真的筛正文：带 query 时回 matches/matchCount，不是只多一个字段。 */
    @Test
    public void extractAppliesQueryToMainContent() throws Exception {
        String html = "<html><body><article>"
                + "<p>本文介绍汽车保养的常见误区，包括机油更换周期和轮胎气压检查。</p>"
                + "<p>2026年起新能源汽车购置税减半征收，每辆减税上限1.5万元，适用纯电动与插电混动车型。</p>"
                + "<p>最后提醒按时年检。</p></article></body></html>";

        WebPageReaderTool tool = new WebPageReaderTool(null);
        Map<String, Object> params = new HashMap<>();
        params.put("content", html);
        params.put("url", "https://example.com/news/1.html");
        params.put("query", "购置税、减税上限");

        java.lang.reflect.Method m = WebPageReaderTool.class.getDeclaredMethod("extractInfo", Map.class);
        m.setAccessible(true);
        AIToolResult r = (AIToolResult) m.invoke(tool, params);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.getResult();
        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) result.get("extracted");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) extracted.get("matches");
        assertFalse("query 没有真的筛正文", matches == null || matches.isEmpty());
        assertTrue("命中段落应是购置税那段",
                String.valueOf(matches.get(0).get("content")).contains("购置税"));
        assertEquals("matchCount 要和 matches 数量一致", matches.size(), extracted.get("matchCount"));

        @SuppressWarnings("unchecked")
        Map<String, Object> quality = (Map<String, Object>) extracted.get("quality");
        assertTrue("quality 要带 page_type", quality.containsKey("page_type"));
        assertTrue("quality 要带 score", quality.containsKey("score"));
        assertTrue("要给出 verdict", extracted.containsKey("verdict"));
    }
}
