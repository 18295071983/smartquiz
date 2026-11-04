package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 安装向导逻辑的契约：什么情况下允许写配置、写成什么、探测结果怎么向用户交代。
 *
 * <p>这一段是市场页唯一决定"用户点完之后得到什么"的代码，所以它必须能在 JVM 里跑：
 * 真机只能验样式，验不了"占位符没换却被标成已安装"这类正确性。</p>
 */
public class McpMarketInstallerTest {

    private static final List<String> NO_PROBLEMS = new ArrayList<>();

    private static McpMarketEntry entry(String capability, String auth, String url,
                                        String blockedReason) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", "demo-server");
            o.put("title", "示例服务");
            o.put("provider", "示例");
            o.put("description", "d");
            o.put("capability", capability);
            o.put("auth", auth);
            o.put("authHint", "要 Web 服务 Key");
            JSONArray eps = new JSONArray();
            if (url != null) {
                eps.put(new JSONObject().put("transport", "streamable_http").put("url", url));
            }
            o.put("endpoints", eps);
            o.put("guide", new JSONArray().put("step"));
            if (blockedReason != null) o.put("blockedReason", blockedReason);
            o.put("docsUrl", "https://docs.test");
            o.put("verifiedAt", "2026-11-04");
            o.put("probe", new JSONObject().put("checkedAt", "2026-11-04")
                    .put("result", "ok").put("evidence", "e"));
            McpMarketEntry e = McpMarketEntry.fromJson(o, NO_PROBLEMS);
            assertNotNull("测试用的条目本身必须合法：" + capability + "/" + auth, e);
            return e;
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static McpMarketEntry direct() {
        return entry("DIRECT_REMOTE", "BEARER_TOKEN",
                "https://mcp.test/mcp?key={key}", "");
    }

    private static McpMarketEntry portal() {
        return entry("PORTAL_ONLY", "UNKNOWN", null, "端点要现场取");
    }

    // ==================== 校验 ====================

    @Test
    public void nonConnectableEntriesAreRefusedWithTheirOwnReason() {
        String err = McpMarketInstaller.validateInstall(portal(), "https://x.test/mcp");
        assertNotNull(err);
        assertTrue("要说清为什么不能装：" + err, err.contains("端点要现场取"));
    }

    @Test
    public void unreplacedPlaceholderIsRejectedAndNamesThePlaceholder() {
        String err = McpMarketInstaller.validateInstall(direct(), "https://x.test/mcp?key={key}");
        assertNotNull(err);
        assertTrue(err + " 应该点名 {key}", err.contains("key"));
    }

    @Test
    public void nonHttpAndEmptyEndpointsAreRejected() {
        assertTrue(McpMarketInstaller.validateInstall(direct(), "") != null);
        assertTrue(McpMarketInstaller.validateInstall(direct(), "ftp://x.test/mcp") != null);
        assertTrue(McpMarketInstaller.validateInstall(direct(), null) != null);
        String err = McpMarketInstaller.validateInstall(direct(), "https://x.test/（自定义）");
        assertNotNull("带中文括号的伪端点也不能过：" + err, err);
    }

    @Test
    public void realEndpointPassesValidation() {
        assertNull(McpMarketInstaller.validateInstall(direct(), "https://mcp.test/mcp?key=abc"));
    }

    // ==================== 传输与配置 ====================

    @Test
    public void transportFollowsTheUrlShape() {
        assertEquals(McpServerConfig.Transport.SSE,
                McpMarketInstaller.transportFor("https://mcp.map.qq.com/sse?key=k",
                        McpServerConfig.Transport.STREAMABLE_HTTP));
        assertEquals(McpServerConfig.Transport.STREAMABLE_HTTP,
                McpMarketInstaller.transportFor("https://mcp.amap.com/mcp?key=k",
                        McpServerConfig.Transport.SSE));
        assertEquals("认不出来时按兜底档", McpServerConfig.Transport.SSE,
                McpMarketInstaller.transportFor("https://host.test/whatever",
                        McpServerConfig.Transport.SSE));
    }

    @Test
    public void builtConfigIsDisabledUntilTheUserConfirmsAndKeepsTheKeyRef() {
        McpServerConfig cfg = McpMarketInstaller.buildConfig(direct(),
                "https://mcp.test/mcp?key=abc", "",
                McpServerConfig.Transport.STREAMABLE_HTTP, "腾讯位置服务", false);
        assertEquals("demo-server", cfg.id);
        assertEquals("用户没改名就用清单标题", "示例服务", cfg.name);
        assertEquals("https://mcp.test/mcp?key=abc", cfg.url);
        assertEquals("安装时必须先建成停用，探测通过并由用户确认才启用", false, cfg.enabled);
        assertEquals("腾讯位置服务", cfg.tokenRef);
        assertEquals("配置里不许留明文令牌", "", cfg.token);
        assertTrue("市场装入要标来源，管理页据此与手填项区分", cfg.builtin);

        McpServerConfig enabled = cfg.withEnabled(true);
        assertTrue(enabled.enabled);
        assertEquals("withEnabled 不能丢 tokenRef", "腾讯位置服务", enabled.tokenRef);
    }

    @Test
    public void displayNameFallsBackToCatalogTitle() {
        assertEquals("示例服务", McpMarketInstaller.displayName("   ", direct()));
        assertEquals("我自己起的名字", McpMarketInstaller.displayName(" 我自己起的名字 ", direct()));
    }

    // ==================== 探测结果交代 ====================

    @Test
    public void probeSummaryListsOnlyASampleOfToolNames() {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            names.add("tool_" + i);
        }
        McpManager.ProbeResult r = McpManager.ProbeResult.ok(30, names, "DemoMCP 1.0", "2025-06-18");
        String text = McpMarketInstaller.describeProbe(r);
        assertTrue(text.contains("30 个工具"));
        assertTrue(text.contains("2025-06-18"));
        assertTrue(text.contains("DemoMCP 1.0"));
        assertTrue("样例要截断：" + text, text.contains("tool_8"));
        assertTrue(text.contains("…"));
        assertEquals("展示上限 8 个", 8, McpMarketInstaller.PROBE_NAME_LIMIT);
    }

    @Test
    public void zeroToolProbeIsCalledOutInsteadOfCheering() {
        String text = McpMarketInstaller.describeProbe(McpManager.ProbeResult.ok(
                0, new ArrayList<String>(), "DemoMCP 1.0", "2025-06-18"));
        assertTrue("连通但没工具必须点出来：" + text, text.contains("没有对外工具"));
    }

    @Test
    public void failureHintsAreDrivenByTheActualError() {
        McpMarketEntry bailian = entry("PROVISIONED_REMOTE", "BEARER_TOKEN",
                "https://dashscope.test/api/v1/mcps/{mcpCode}/mcp", "");
        String hints = McpMarketInstaller.failureHintsText(bailian,
                McpManager.ProbeResult.fail("HTTP 401 Unauthorized"));
        assertTrue("401 要指到密钥：" + hints, hints.contains("密钥"));
        assertTrue("本条目鉴权要求要一起给：" + hints, hints.contains("要 Web 服务 Key"));

        String notFound = McpMarketInstaller.failureHintsText(direct(),
                McpManager.ProbeResult.fail("HTTP 404 Not Found"));
        assertTrue("404 要说明整条粘贴：" + notFound, notFound.contains("整条粘贴"));

        String net = McpMarketInstaller.failureHintsText(direct(),
                McpManager.ProbeResult.fail("Failed to connect to mcp.test"));
        assertTrue(net.contains("主机"));

        String garbled = McpMarketInstaller.failureHintsText(direct(),
                McpManager.ProbeResult.fail("response is not a JSON-RPC message"));
        assertTrue("报文不对要指向传输/占位符：" + garbled, garbled.contains("传输选错"));
    }

    @Test
    public void successfulProbeProducesNoFailureHints() {
        assertTrue(McpMarketInstaller.failureHints(direct(),
                McpManager.ProbeResult.ok(1, Arrays.asList("a"), "", "")).isEmpty());
    }

    // ==================== 提示与文案 ====================

    @Test
    public void advisoriesWarnWithoutBlocking() {
        List<String> missingKey = McpMarketInstaller.advisories(
                entry("DIRECT_REMOTE", "QUERY_KEY", "https://x.test/mcp?key={key}", ""),
                "https://x.test/mcp");
        assertTrue("URL 里没 key= 要提示：" + missingKey, missingKey.get(0).contains("key="));

        List<String> plainHttp = McpMarketInstaller.advisories(direct(), "http://x.test/mcp");
        assertTrue("明文 HTTP 要提示裸传：" + plainHttp, plainHttp.get(0).contains("明文"));

        assertTrue("正常端点不该啰嗦",
                McpMarketInstaller.advisories(direct(), "https://x.test/mcp?key=abc").isEmpty());
    }

    @Test
    public void everyCapabilityAndAuthHasDistinctWording() {
        List<String> labels = new ArrayList<>();
        for (McpMarketEntry.Capability c : McpMarketEntry.Capability.values()) {
            String label = McpMarketInstaller.capabilityLabel(c);
            String hint = McpMarketInstaller.capabilityHint(c);
            assertTrue("能力位标签不能空：" + c, !label.isEmpty());
            assertTrue("能力位解释不能空：" + c, hint.length() >= 8);
            assertTrue("标签不能撞车：" + label + " / " + labels, !labels.contains(label));
            labels.add(label);
        }
        List<String> authLabels = new ArrayList<>();
        for (McpMarketEntry.Auth a : McpMarketEntry.Auth.values()) {
            String label = McpMarketInstaller.authLabel(a);
            assertTrue("鉴权标签不能空：" + a, !label.isEmpty());
            assertTrue("鉴权标签不能撞车：" + label, !authLabels.contains(label));
            authLabels.add(label);
        }
        assertEquals(McpMarketEntry.Capability.values().length, labels.size());
    }

    @Test
    public void sourceLineCarriesDocDateAndProbeVerdict() {
        String line = McpMarketInstaller.sourceLine(direct());
        assertTrue(line.contains("2026-11-04"));
        assertTrue("自检结论要翻译成人话：" + line, line.contains("握手成功"));
        assertTrue(McpMarketInstaller.sourceLine(null).isEmpty());
    }

    @Test
    public void nullEntryNeverThrowsInValidation() {
        assertNotNull(McpMarketInstaller.validateInstall(null, "https://x.test/mcp"));
        assertEquals("没跑探测", McpMarketInstaller.describeProbe(null));
        assertEquals("未知", McpMarketInstaller.capabilityLabel(null));
    }

    // ==================== 密钥单独填（不再明文写进端点） ====================

    private static McpMarketEntry queryKeyEntry() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", "amap-maps");
            o.put("title", "高德地图 MCP");
            o.put("capability", "DIRECT_REMOTE");
            o.put("auth", "QUERY_KEY");
            o.put("authHint", "Web 服务 Key");
            o.put("secretPlaceholder", "key");
            o.put("endpoints", new JSONArray().put(new JSONObject()
                    .put("transport", "streamable_http")
                    .put("url", "https://mcp.amap.com/mcp?key={key}")));
            o.put("guide", new JSONArray().put("step"));
            o.put("docsUrl", "https://lbs.amap.com/api/mcp-server/gettingstarted");
            o.put("verifiedAt", "2026-11-04");
            o.put("probe", new JSONObject().put("checkedAt", "2026-11-04")
                    .put("result", "auth_required").put("evidence", "e"));
            McpMarketEntry e = McpMarketEntry.fromJson(o, NO_PROBLEMS);
            assertNotNull(e);
            assertEquals("声明了 QUERY_KEY 就必须有 secretPlaceholder", "key", e.secretPlaceholder);
            return e;
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    public void secretEntryIsRejectedWithoutAKeyAndTellsYouWhereToPutIt() {
        McpMarketEntry e = queryKeyEntry();
        String err = McpMarketInstaller.validateInstall(e,
                "https://mcp.amap.com/mcp?key={key}", "");
        assertNotNull("没填 Key 不能装", err);
        assertTrue(err + " 要指到密钥框", err.contains("密钥"));

        String wrong = McpMarketInstaller.validateInstall(e,
                "https://mcp.amap.com/mcp?key=0123456789abcdef0123456789abcdef", "0123456789abcdef0123456789abcdef");
        assertNotNull("把 Key 直接写进端点必须被拒：" + wrong);
        assertTrue(wrong.contains("加密"));

        String missingTemplate = McpMarketInstaller.validateInstall(e,
                "https://mcp.amap.com/mcp", "abc");
        assertNotNull("端点里丢了占位符也要被拒：" + missingTemplate);
    }

    @Test
    public void secretEntryBuildsTemplateUrlAndKeepsTheKeyInTokenField() {
        McpMarketEntry e = queryKeyEntry();
        String template = "https://mcp.amap.com/mcp?key={key}";
        assertNull(McpMarketInstaller.validateInstall(e, template, "abc123"));

        McpServerConfig cfg = McpMarketInstaller.buildConfig(e, template, "",
                McpServerConfig.Transport.STREAMABLE_HTTP, "", "abc123", false);
        assertEquals("端点里只该留占位符", template, cfg.url);
        assertEquals("密钥进 token 字段（落盘时由 McpConfigStore 加密）", "abc123", cfg.token);
        assertEquals("", cfg.tokenRef);
        assertFalse(cfg.enabled);

        // 拼回真实请求地址是 McpManager#resolvedCfg 的事，这里只保证存储形态
        assertEquals("https://mcp.amap.com/mcp?key=abc123",
                McpServerConfig.injectSecret(cfg.url, cfg.token));
        // 存储形态本身就不含真实 Key：显示层再遮一次也遮不出别的
        assertEquals("https://mcp.amap.com/mcp?key=****", McpServerConfig.maskUrl(cfg.url));
        assertEquals(template, cfg.url);
    }

    @Test
    public void secretFieldLabelOnlyAppearsForUrlKeyServices() {
        assertEquals("🔑 密钥（key）", McpMarketInstaller.secretFieldLabel(queryKeyEntry()));
        assertEquals("Bearer 类服务不该出现这个框", "",
                McpMarketInstaller.secretFieldLabel(direct()));
        assertEquals("", McpMarketInstaller.secretFieldLabel(null));
    }

    @Test
    public void oldTwoArgCallersStillWorkWithoutASecret() {
        // 市场页对非密钥条目仍走两参口径；别为了加密钥把已有校验路径改断
        assertNull(McpMarketInstaller.validateInstall(direct(), "https://mcp.test/mcp?key=abc"));
        assertNotNull(McpMarketInstaller.validateInstall(direct(), "https://mcp.test/mcp?key={key}"));
    }
}
