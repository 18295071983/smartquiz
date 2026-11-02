package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络搜索工具的正文清洗与抽取质量（2026-11-02 手机实测回归的 D-1 / D-2 / R-14~R-16）。
 *
 * <p>手机实测（用户让模型把每个 action 都试一遍）暴露出：结构抽取跑在原始 HTML 上，
 * `<script>` / `<style>` 里的文本被当成正文 —— keywords 抽出 `if/var/function/return`，
 * phones 抽出脚本里的数字，contentSlices 里整段 JS；同一段正文还会因 PC/移动两套 DOM 重复计入。</p>
 *
 * <p>这些断言都跑在**离线**：直接给 {@code content} 参数，不发起任何网络请求。</p>
 */
public class NetworkSearchCleanupTest {

    private static final String PAGE = "<html><head><title>购置税减免政策问答</title>"
            + "<style>.nav_navcon{display:none}.module-cmt-list{color:red}</style>"
            + "<script>function init(){var toc=document.querySelector('.article-toc');return toc;}</script>"
            + "</head><body>"
            + "<h1>购置税减免</h1><h2></h2><h2> </h2><h2>适用车型</h2>"
            + "<p>自2026年1月1日起，对符合条件的新能源汽车减半征收车辆购置税，每辆减税上限为1.5万元。</p>"
            + "<p>同一段正文内容只应该出现一次，用于验证 PC 版与移动版 DOM 去重是否生效，这一句要够长才能过切片门槛。</p>"
            + "<p>同一段正文内容只应该出现一次，用于验证 PC 版与移动版 DOM 去重是否生效，这一句要够长才能过切片门槛。</p>"
            + "<iframe src=\"https://ads.example.com/x\"></iframe>"
            + "<svg><path d=\"M0 0\"/></svg>"
            + "<div>联系电话 15222862899，发布日期 2026/10/57。</div>"
            + "</body></html>";

    private static NetworkSearchTool tool() {
        return new NetworkSearchTool(null);
    }

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = NetworkSearchTool.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(tool(), args);
    }

    /** D-1：脚本/样式/iframe/svg 连同内部文本一起剥掉，正文保留（现在走 Jsoup DOM，不再正则切）。 */
    @Test
    public void htmlExtractionDropsScriptStyleIframeSvg() throws Exception {
        java.lang.reflect.Method m = NetworkSearchTool.class.getDeclaredMethod("removeHtmlTags", String.class);
        m.setAccessible(true);
        String cleaned = (String) m.invoke(tool(), PAGE);

        assertFalse("脚本内容不许留下", cleaned.contains("querySelector"));
        assertFalse("脚本关键字不许留下", cleaned.contains("function init"));
        assertFalse("样式内容不许留下", cleaned.contains("nav_navcon"));
        assertFalse("iframe 整块要删掉", cleaned.contains("ads.example.com"));
        assertFalse("svg 整块要删掉", cleaned.contains("M0 0"));
        assertTrue("正文要保留", cleaned.contains("减半征收"));
        assertTrue("标题结构要保留", cleaned.contains("购置税减免"));
    }

    /** D-1 的落地效果：extract_info 的元数据字段不再被脚本污染（R-13~R-16）。 */
    @Test
    public void extractInfoMetadataIsCleanOfScriptNoise() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("content", PAGE);
        params.put("url", "https://example.com/policy");

        AIToolResult result = (AIToolResult) call("extractInfo",
                new Class<?>[]{Map.class}, params);
        assertTrue("离线走 content 参数，不该失败: " + result.getErrorMessage(), result.isSuccess());

        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) ((Map<String, Object>) result.getResult()).get("extracted");
        assertNotNull(extracted);

        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) extracted.get("keywords");
        for (String kw : keywords) {
            assertFalse("keywords 不该出现 JS 语法词: " + kw,
                    kw.equalsIgnoreCase("function") || kw.equalsIgnoreCase("var")
                            || kw.equalsIgnoreCase("queryselector") || kw.equalsIgnoreCase("return"));
        }

        @SuppressWarnings("unchecked")
        List<String> headings = (List<String>) extracted.get("headings");
        for (String h : headings) {
            assertFalse("空标题不该出现（R-14）: " + h, h.equals("H1: ") || h.equals("H2: "));
        }
        assertTrue("真标题要留", headings.contains("H1: 购置税减免"));
        assertTrue("第二个真 H2 要留", headings.contains("H2: 适用车型"));

        @SuppressWarnings("unchecked")
        List<String> dates = (List<String>) extracted.get("dates");
        assertFalse("2026/10/57 不是合法日期，不许出现（R-16）", dates.contains("2026/10/57"));

        @SuppressWarnings("unchecked")
        List<String> phones = (List<String>) extracted.get("phones");
        assertTrue("正文里的真实手机号要留", phones.contains("15222862899"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slices = (List<Map<String, Object>>) extracted.get("contentSlices");
        int same = 0;
        for (Map<String, Object> slice : slices) {
            String content = String.valueOf(slice.get("content"));
            assertFalse("正文切片里不许有脚本（R-08）", content.contains("querySelector"));
            assertFalse("正文切片里不许有样式（R-02）", content.contains("nav_navcon"));
            if (content.contains("同一段正文内容只应该出现一次")) same++;
        }
        assertEquals("重复段落只许留一份（D-2/R-17）", 1, same);
    }

    /** D-4：extract_info 的 query 不再是摆设，要回按问题命中的段落（R-10~R-12）。 */
    @Test
    public void extractInfoHonorsQuery() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("content", PAGE);
        params.put("url", "https://example.com/policy");
        params.put("query", "减半征收、减税上限");

        AIToolResult result = (AIToolResult) call("extractInfo", new Class<?>[]{Map.class}, params);
        assertTrue(result.isSuccess());

        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) ((Map<String, Object>) result.getResult()).get("extracted");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) extracted.get("queryMatches");
        assertNotNull("给了 query 就应该有 queryMatches 字段", matches);
        assertFalse("query 命中的段落不该是空的", matches.isEmpty());
        assertTrue("命中段落要包含问到的内容",
                String.valueOf(matches.get(0).get("content")).contains("减半征收"));
    }

    /**
     * query 命中不能只认"整词包含"：问"减免幅度、适用期限"，正文写的是"减半征收""减税额"，
     * 手机复测时 queryMatches 永远是 []（等于 query 还是没生效）。两字片段也要能命中。
     */
    @Test
    public void queryMatchesSurviveDifferentWording() throws Exception {
        String page = "<p>对购置日期在2026年1月1日至2027年12月31日期间的新能源汽车减半征收车辆购置税，"
                + "其中每辆新能源乘用车减税额不超过1.5万元。</p>"
                + "<p>减免政策适用纯电动与插电式混合动力车型，具体以工信部目录为准。</p>";

        Map<String, Object> params = new HashMap<>();
        params.put("content", page);
        params.put("url", "https://example.com/tax");
        params.put("query", "减免幅度、适用期限、减税上限、适用车型");

        AIToolResult result = (AIToolResult) call("extractInfo", new Class<?>[]{Map.class}, params);
        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) ((Map<String, Object>) result.getResult()).get("extracted");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) extracted.get("queryMatches");

        assertNotNull(matches);
        assertFalse("换了说法也要能命中，不能永远返回 []", matches.isEmpty());
        assertTrue("命中的应该是讲政策的那段",
                String.valueOf(matches.get(0).get("content")).contains("减半征收")
                        || String.valueOf(matches.get(0).get("content")).contains("减免政策"));
    }

    /** 不给 query 时不许多出一个空字段（老调用方的返回结构保持不变）。 */
    @Test
    public void extractInfoWithoutQueryKeepsOldShape() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("content", PAGE);
        params.put("url", "https://example.com/policy");

        AIToolResult result = (AIToolResult) call("extractInfo", new Class<?>[]{Map.class}, params);

        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) ((Map<String, Object>) result.getResult()).get("extracted");
        assertFalse("没传 query 就不该有 queryMatches", extracted.containsKey("queryMatches"));
    }

    /**
     * R-02：门户页首句常是站点模板导航（"简体/繁体/无障碍/长者专区…"），
     * 以前摘要直接从这句开始，正文被挤掉。模板句要被过滤、正文要进摘要。
     */
    @Test
    public void summarySkipsSiteNavigationBoilerplate() throws Exception {
        String page = "<div>简体 繁体 无障碍 长者专区 抖音 微信 客户端 首页 新闻 政务公开 政务服务 网站地图</div>"
                + "<p>自2026年1月1日起，对符合条件的新能源汽车减半征收车辆购置税，每辆减税上限为1.5万元。</p>";

        String summary = (String) call("generateSmartSummary",
                new Class<?>[]{String.class, String.class}, page, "政策要点");

        assertFalse("模板导航串不许进摘要: " + summary, summary.contains("长者专区"));
        assertTrue("正文要进摘要: " + summary, summary.contains("减半征收"));
    }

    /** 关键词不许被小数点和百分号切断成 "5万元"/"04万元" 这种碎片（手机复测）。 */
    @Test
    public void keywordsAreNotCutApartByDecimals() throws Exception {
        String page = "<p>优惠幅度：1.5万元；2.04万元；实际缴纳2.5万元；70%或75%；"
                + "30万元以上车型稳定节省1.05万元。购置税减半政策适用范围以工信部目录为准。</p>";

        Map<String, Object> params = new HashMap<>();
        params.put("content", page);
        params.put("url", "https://example.com/price");
        AIToolResult result = (AIToolResult) call("extractInfo", new Class<?>[]{Map.class}, params);

        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) ((Map<String, Object>) result.getResult()).get("extracted");
        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) extracted.get("keywords");

        assertFalse("关键词不该是空的", keywords.isEmpty());
        for (String kw : keywords) {
            assertFalse("关键词不许以数字开头（会被切断）: " + kw, Character.isDigit(kw.charAt(0)));
        }
    }

    /** HTML 实体要真解码：&emsp; 不许留成 "emsp"，&copy; &times; 数字实体同理。 */
    @Test
    public void htmlEntitiesAreDecoded() throws Exception {
        java.lang.reflect.Method m = NetworkSearchTool.class.getDeclaredMethod("removeHtmlTags", String.class);
        m.setAccessible(true);
        String text = (String) m.invoke(tool(), "<p>甲&emsp;乙 &copy; &times; &#39;q&#x27; &amp; &mdash;</p>");

        assertFalse("emsp 不该作为字面量留下", text.contains("emsp"));
        assertFalse("命名实体不该原样留下", text.contains("&copy;") || text.contains("&times;"));
        assertTrue("版权号要还原", text.contains("©"));
        assertTrue("乘号要还原", text.contains("×"));
        assertTrue("单引号（十进制）要还原", text.contains("'q'"));
        assertTrue("破折号要还原", text.contains("—"));
    }

    /** 英文正文的句子边界与连接符：不许出现 "maintained。applications" 这种混排残句。 */
    @Test
    public void englishSummaryKeepsSentenceBoundaries() throws Exception {
        String text = "A number of domains such as example.com and example.org are maintained "
                + "for documentation purposes. We strongly recommend you do not design applications "
                + "that rely on these domains. Use example.com only when you need a documentation example.";

        String summary = (String) call("generateSmartSummary",
                new Class<?>[]{String.class, String.class}, text, null);

        assertTrue("整句要完整（不能切在 maintained 后面）: " + summary,
                summary.contains("for documentation purposes"));
        assertFalse("英文句子之间不许用中文句号连接: " + summary, summary.contains("purposes。"));
        assertTrue("英文句子之间要用 \". \" 连接: " + summary, summary.contains("purposes. "));
    }

    /**
     * 真机第三轮的根因：英文站源码每 ~70 字符一个硬换行，只删标签会把行尾 `\n` 留下，
     * 后面按 `\n` 切句就把一句英文劈成三段。行内换行必须折成空格，只有连续换行才算段落。
     */
    @Test
    public void inlineHardWrapsAreFoldedIntoOneLine() throws Exception {
        String html = "<p>A number of domains such as example.com and example.org are maintained\n"
                + "for documentation purposes. These domains may be used as illustrative\n"
                + "examples in documents.</p><p>Second paragraph stays separate.</p>";

        java.lang.reflect.Method m = NetworkSearchTool.class.getDeclaredMethod("removeHtmlTags", String.class);
        m.setAccessible(true);
        String text = (String) m.invoke(tool(), html);

        assertFalse("行内硬换行要折成空格: " + text, text.contains("maintained\nfor"));
        assertTrue("整句要连起来: " + text, text.contains("maintained for documentation purposes"));
        assertTrue("段落边界要保留（切片靠它）: " + text, text.contains("\n\n"));
    }

    /** 日期统一成 ISO，字间带空格的 "2026 年 1 月 1 日" 也要能抓到；非法日期仍然拒绝。 */
    @Test
    public void datesAreNormalizedToIso() throws Exception {
        String page = "<p>2026年1月1日至2027年12月31日；2026 年 1 月 1 日；2026-05-27；2026/10/57。</p>";

        Map<String, Object> params = new HashMap<>();
        params.put("content", page);
        params.put("url", "https://example.com/dates");
        AIToolResult result = (AIToolResult) call("extractInfo", new Class<?>[]{Map.class}, params);

        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) ((Map<String, Object>) result.getResult()).get("extracted");
        @SuppressWarnings("unchecked")
        List<String> dates = (List<String>) extracted.get("dates");

        assertTrue("ISO 格式要保留", dates.contains("2026-05-27"));
        assertTrue("中文格式要转 ISO 并抓得到", dates.contains("2026-01-01"));
        assertTrue("日期区间要抓到", dates.contains("2027-12-31"));
        assertFalse("非法日期仍然要拒", dates.contains("2026-10-57"));
        for (String d : dates) {
            assertTrue("输出统一为 yyyy-MM-dd: " + d, d.matches("\\d{4}-\\d{2}-\\d{2}"));
        }
        assertEquals("同一日期只留一份", 1, dates.stream().filter("2026-01-01"::equals).count());
    }

    /** 英文 query 按整词匹配：侧边栏栏目名不该靠零散字母拿分。 */
    @Test
    public void latinQueryMatchesWholeWordsOnly() throws Exception {
        String page = "<p>Instructions and Guides</p><p>Example Domains</p>"
                + "<p>This page explains how example domains are reserved for documentation.</p>";

        Map<String, Object> params = new HashMap<>();
        params.put("content", page);
        params.put("url", "https://example.com/doc");
        params.put("query", "Example Domains");

        AIToolResult result = (AIToolResult) call("extractInfo", new Class<?>[]{Map.class}, params);
        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) ((Map<String, Object>) result.getResult()).get("extracted");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) extracted.get("queryMatches");

        assertFalse("英文 query 不该零命中", matches.isEmpty());
        for (Map<String, Object> one : matches) {
            assertFalse("侧边栏栏目名不该被当成命中: " + one.get("content"),
                    String.valueOf(one.get("content")).contains("Instructions and Guides"));
        }
    }

    /** 关键词里不该留"日至""日期间"这种剥掉数字后的虚词残渣。 */
    @Test
    public void keywordsDropDateGlueResidue() throws Exception {
        String page = "<p>对购置日期在2026年1月1日至2027年12月31日期间的新能源汽车减半征收车辆购置税。</p>";

        Map<String, Object> params = new HashMap<>();
        params.put("content", page);
        params.put("url", "https://example.com/kw");
        AIToolResult result = (AIToolResult) call("extractInfo", new Class<?>[]{Map.class}, params);

        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) ((Map<String, Object>) result.getResult()).get("extracted");
        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) extracted.get("keywords");

        assertFalse("关键词不该是空的", keywords.isEmpty());
        for (String kw : keywords) {
            assertFalse("虚词残渣要清掉: " + kw,
                    kw.equals("日至") || kw.equals("日期间") || kw.equals("月至") || kw.equals("年月"));
        }
    }

    /** summary 不许再出现"整段 CSS/JS"，也不许把同一句重复三遍（D-3/R-01）。 */
    @Test
    public void summarySkipsScriptAndDuplicateSentences() throws Exception {
        String summary = (String) call("generateSmartSummary",
                new Class<?>[]{String.class, String.class}, PAGE, null);

        assertFalse("摘要里不许有 JS", summary.contains("querySelector"));
        assertFalse("摘要里不许有 CSS", summary.contains("nav_navcon"));
        int first = summary.indexOf("减半征收");
        assertTrue("摘要要落在正文上: " + summary, first >= 0);
        int dup = summary.indexOf("同一段正文");
        if (dup >= 0) {
            assertTrue("同一句不许在摘要里出现两次", summary.indexOf("同一段正文", dup + 5) < 0);
        }
    }
}
