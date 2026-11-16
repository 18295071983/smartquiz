package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 抓取合规闸门：robots 规则解析与匹配、按域名限速。
 *
 * <p>对应 `docs/LICENSING_AND_COMPLIANCE.md` 第四节里"robots / 频率"两条——
 * 规则先做成可离线验证的纯函数，再接进抓取入口。</p>
 */
public class WebFetchPolicyTest {

    private static WebFetchPolicy.RobotsRules parse(String text) {
        WebFetchPolicy.RobotsRules rules = new WebFetchPolicy.RobotsRules();
        WebFetchPolicy.parseInto(text, rules);
        return rules;
    }

    @Test
    public void disallowAndAllowFollowLongestMatch() {
        WebFetchPolicy.RobotsRules rules = parse(
                "User-agent: *\n"
                        + "Disallow: /private/\n"
                        + "Allow: /private/ok\n"
                        + "Disallow: /tmp\n");

        assertFalse("私有路径要拒绝", rules.isAllowed("/private/list"));
        assertTrue("显式 Allow 的更长前缀要放行", rules.isAllowed("/private/ok/1"));
        assertTrue("未命中规则默认放行", rules.isAllowed("/news/1.html"));
        assertFalse("普通 Disallow 要拒绝", rules.isAllowed("/tmp/a"));
    }

    /** 只认 `*` 组：别的爬虫的规则不该套到我们头上（也不该被我们忽略后全拒）。 */
    @Test
    public void otherUserAgentGroupsAreIgnored() {
        WebFetchPolicy.RobotsRules rules = parse(
                "User-agent: Googlebot\n"
                        + "Disallow: /\n"
                        + "\n"
                        + "User-agent: *\n"
                        + "Disallow: /admin\n");

        assertFalse("自己的规则要生效", rules.isAllowed("/admin/x"));
        assertTrue("别的爬虫的 Disallow: / 不该影响我们", rules.isAllowed("/article/1"));
    }

    @Test
    public void emptyRobotsAllowsEverything() {
        WebFetchPolicy.RobotsRules rules = parse("");
        assertTrue("没有规则就是全放行", rules.isAllowed("/anything"));
        assertTrue("根路径也放行", rules.isAllowed("/"));
    }

    @Test
    public void commentsAndBlankLinesAreTolerated() {
        WebFetchPolicy.RobotsRules rules = parse(
                "# 这是注释\n"
                        + "\n"
                        + "User-agent: *   # 通配\n"
                        + "Disallow: /secret  \n");

        assertFalse("带注释的规则也要生效", rules.isAllowed("/secret/page"));
        assertTrue("其它路径放行", rules.isAllowed("/public/page"));
    }

    /**
     * 同一域名两次抓取之间要有间隔（默认 1s，测试里临时调小以免拖慢用例）。
     *
     * <p>robots 用桩（返回空＝无规则）：这条只测限速，不该去连 example.com ——
     * 真连会踩"同 JVM 里 Robolectric 把 Conscrypt 装成第一 JCA provider、JDK 的 TLS 初始化失败"
     * 的坑，见 {@link WebFetchPolicy.RobotsFetcher} 的注释。</p>
     */
    @Test
    public void sameHostRequestsAreSpacedOut() throws Exception {
        WebFetchPolicy.resetForTest();
        long original = WebFetchPolicy.MIN_INTERVAL_MS;
        WebFetchPolicy.MIN_INTERVAL_MS = 120;
        WebFetchPolicy.setRobotsFetcherForTest(url -> "");
        try {
            String url = "https://example.com/a";
            long start = System.currentTimeMillis();
            WebFetchPolicy.beforeFetch(url);
            WebFetchPolicy.afterFetch(url);
            WebFetchPolicy.beforeFetch(url);
            WebFetchPolicy.afterFetch(url);
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("两次请求间隔不足，实际 " + elapsed + "ms", elapsed >= 90);
        } finally {
            WebFetchPolicy.MIN_INTERVAL_MS = original;
            WebFetchPolicy.setRobotsFetcherForTest(null);
            WebFetchPolicy.resetForTest();
        }
    }

    /**
     * robots 拦截要真的落在抓取闸门上（不只是解析函数的单测）。
     *
     * <p>桩返回一条 Disallow：{@code beforeFetch} 必须拒绝并说明是合规拦截；
     * 同一份规则下放行路径不该被拦。全程不联网，所以不依赖测试 JVM 的 TLS/JCA 状态。</p>
     */
    @Test
    public void robotsDisallowBlocksTheFetch() {
        WebFetchPolicy.resetForTest();
        WebFetchPolicy.setRobotsFetcherForTest(url -> "User-agent: *\nDisallow: /private\n");
        try {
            try {
                WebFetchPolicy.beforeFetch("https://example.com/private/x");
                org.junit.Assert.fail("robots 明确 Disallow 时 beforeFetch 必须拒绝");
            } catch (Exception expected) {
                assertTrue("错误文案要说明是 robots 合规拦截，不是网络错误: " + expected.getMessage(),
                        expected.getMessage().contains("robots"));
                assertTrue("错误文案要带上被拒路径: " + expected.getMessage(),
                        expected.getMessage().contains("/private"));
            }
            WebFetchPolicy.beforeFetch("https://example.com/public/x");
            WebFetchPolicy.afterFetch("https://example.com/public/x");
        } catch (Exception e) {
            org.junit.Assert.fail("放行路径不该抛异常: " + e);
        } finally {
            WebFetchPolicy.setRobotsFetcherForTest(null);
            WebFetchPolicy.resetForTest();
        }
    }

    @Test
    public void hostParsingHandlesPortAndCase() {
        // 限速按主机维度，端口不参与；大小写统一成小写
        assertTrue("example.com".equals(WebFetchPolicy.hostOf("https://Example.COM:8080/a?b=1")));
        assertTrue(WebFetchPolicy.hostOf("not a url") == null);
    }
}
