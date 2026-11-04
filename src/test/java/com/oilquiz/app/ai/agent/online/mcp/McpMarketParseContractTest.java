package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 清单解析器的容错契约：坏数据要么被丢（并说明为什么），要么被降级（并记进 problems）。
 *
 * <p>市场页的"能不能装"完全由 capability 驱动，所以这条线必须硬：
 * 认不出的能力位、可安装档却没端点、id 不能当配置 id 用——这三种都丢条目，
 * 因为它们会让用户面对一个"点了装但必然失败"的卡片。其余（缺自检、URL 写错、
 * 指引为空）只记问题、保留条目：少一条描述比少一条服务更可以接受。</p>
 */
public class McpMarketParseContractTest {

    private static McpMarketCatalog.Snapshot parse(String root) throws Exception {
        return McpMarketCatalog.parse(root);
    }

    private static String oneEntry(String body) {
        return "{\"schema\":1,\"version\":\"t\",\"entries\":[" + body + "]}";
    }

    private static final String GOOD = "{\"id\":\"demo\",\"title\":\"Demo\","
            + "\"provider\":\"示例\",\"description\":\"d\",\"capability\":\"DIRECT_REMOTE\","
            + "\"auth\":\"BEARER_TOKEN\",\"authHint\":\"h\","
            + "\"endpoints\":[{\"transport\":\"streamable_http\",\"url\":\"https://x.test/mcp\"}],"
            + "\"guide\":[\"step\"],\"docsUrl\":\"https://docs.test\","
            + "\"verifiedAt\":\"2026-11-04\","
            + "\"probe\":{\"checkedAt\":\"2026-11-04\",\"result\":\"ok\",\"evidence\":\"e\"}}";

    @Test
    public void wellFormedEntryParsesCleanly() throws Exception {
        McpMarketCatalog.Snapshot snap = parse(oneEntry(GOOD));
        assertEquals("好数据不该报问题：" + snap.problems, 0, snap.problems.size());
        assertEquals(1, snap.entries.size());
        McpMarketEntry e = snap.entries.get(0);
        assertEquals(McpMarketEntry.Capability.DIRECT_REMOTE, e.capability);
        assertEquals(McpServerConfig.Transport.STREAMABLE_HTTP, e.defaultEndpoint().transport);
        assertTrue(e.capability.isConnectable());
        assertEquals("t", snap.version);
    }

    @Test
    public void unknownCapabilityDropsTheEntry() throws Exception {
        McpMarketCatalog.Snapshot snap = parse(oneEntry(
                GOOD.replace("\"DIRECT_REMOTE\"", "\"TELEPORT_IF_POSSIBLE\"")));
        assertTrue(snap.entries.isEmpty());
        assertTrue("要说明为什么丢：" + snap.problems, snap.problems.get(0).contains("capability"));
    }

    @Test
    public void connectableWithoutEndpointDropsTheEntry() throws Exception {
        McpMarketCatalog.Snapshot snap = parse(oneEntry(
                GOOD.replace("\"endpoints\":[{\"transport\":\"streamable_http\","
                        + "\"url\":\"https://x.test/mcp\"}]", "\"endpoints\":[]")));
        assertTrue("可安装档没端点等于给用户一个必然失败的按钮", snap.entries.isEmpty());
        assertTrue(snap.problems.get(0).contains("没有可用端点"));
    }

    @Test
    public void unsafeIdDropsTheEntry() throws Exception {
        McpMarketCatalog.Snapshot snap = parse(oneEntry(GOOD.replace("\"id\":\"demo\"",
                "\"id\":\"示 例/服务\"")));
        assertTrue(snap.entries.isEmpty());
        assertTrue(snap.problems.get(0).contains("id"));
    }

    @Test
    public void duplicateIdKeepsTheFirstAndReports() throws Exception {
        McpMarketCatalog.Snapshot snap = parse(
                "{\"schema\":1,\"version\":\"t\",\"entries\":[" + GOOD + "," + GOOD + "]}");
        assertEquals(1, snap.entries.size());
        assertTrue("重复 id 必须被点名：" + snap.problems,
                snap.problems.get(0).contains("重复 id"));
    }

    @Test
    public void unknownProbeResultDegradesToUnverified() throws Exception {
        McpMarketCatalog.Snapshot snap = parse(oneEntry(
                GOOD.replace("\"result\":\"ok\"", "\"result\":\"maybe\"")));
        assertEquals("只降级不丢条目", 1, snap.entries.size());
        assertEquals("unverified", snap.entries.get(0).probe.result);
        assertFalse(snap.entries.get(0).probe.isHandshakeOk());
        assertTrue(snap.problems.get(0).contains("probe.result"));
    }

    @Test
    public void missingProbeIsRecordedButEntrySurvives() throws Exception {
        McpMarketCatalog.Snapshot snap = parse(oneEntry(GOOD.replace(
                ",\"probe\":{\"checkedAt\":\"2026-11-04\",\"result\":\"ok\",\"evidence\":\"e\"}",
                "")));
        assertEquals(1, snap.entries.size());
        assertNull(snap.entries.get(0).probe);
        assertTrue(snap.problems.get(0).contains("没有自检记录"));
    }

    @Test
    public void nonHttpDocsUrlIsCleared() throws Exception {
        McpMarketCatalog.Snapshot snap = parse(oneEntry(
                GOOD.replace("\"https://docs.test\"", "\"文档在群里\"")));
        assertEquals(1, snap.entries.size());
        assertEquals("", snap.entries.get(0).docsUrl);
        assertTrue(snap.problems.get(0).contains("docsUrl"));
    }

    @Test
    public void blankEndpointInsideArrayIsIgnored() throws Exception {
        McpMarketCatalog.Snapshot snap = parse(oneEntry(GOOD.replace(
                "\"endpoints\":[{\"transport\":\"streamable_http\",\"url\":\"https://x.test/mcp\"}]",
                "\"endpoints\":[{\"transport\":\"sse\",\"url\":\"\"},"
                        + "{\"transport\":\"streamable_http\",\"url\":\"https://x.test/mcp\"}]")));
        assertEquals(1, snap.entries.size());
        assertEquals("空端点要跳过而不是把整条丢掉", 1, snap.entries.get(0).endpoints.size());
        assertEquals(McpServerConfig.Transport.STREAMABLE_HTTP,
                snap.entries.get(0).defaultEndpoint().transport);
        assertTrue(snap.problems.get(0).contains("端点为空"));
    }

    @Test
    public void missingTransportFallsBackToStreamableWithWarning() throws Exception {
        McpMarketCatalog.Snapshot snap = parse(oneEntry(GOOD.replace(
                "{\"transport\":\"streamable_http\",\"url\":\"https://x.test/mcp\"}",
                "{\"url\":\"https://x.test/mcp\"}")));
        assertEquals(1, snap.entries.size());
        assertEquals(McpServerConfig.Transport.STREAMABLE_HTTP,
                snap.entries.get(0).defaultEndpoint().transport);
        assertTrue(snap.problems.get(0).contains("未声明 transport"));
    }

    @Test
    public void explicitUnknownAuthIsLegalButGarbageIsReported() throws Exception {
        // 广场类条目的鉴权"随服务而定"是合法声明，不能被当成数据错误；
        // 但写错的档位名必须报出来，否则真数据问题会混在这里没人看见
        McpMarketCatalog.Snapshot legal = parse(oneEntry(GOOD.replace(
                "\"auth\":\"BEARER_TOKEN\"", "\"auth\":\"UNKNOWN\"")));
        assertEquals("显式 UNKNOWN 不该报问题：" + legal.problems, 0, legal.problems.size());
        assertEquals(McpMarketEntry.Auth.UNKNOWN, legal.entries.get(0).auth);

        McpMarketCatalog.Snapshot garbage = parse(oneEntry(GOOD.replace(
                "\"auth\":\"BEARER_TOKEN\"", "\"auth\":\"MAGIC\"")));
        assertEquals(1, garbage.entries.size());
        assertEquals("错值降级为 UNKNOWN 但必须留痕", McpMarketEntry.Auth.UNKNOWN,
                garbage.entries.get(0).auth);
        assertTrue(garbage.problems.get(0).contains("auth 无法识别"));

        McpMarketCatalog.Snapshot missing = parse(oneEntry(GOOD.replace(
                "\"auth\":\"BEARER_TOKEN\",", "")));
        assertTrue("漏写 auth 也要留痕：" + missing.problems,
                missing.problems.get(0).contains("没写 auth"));
    }

    @Test
    public void urlKeyServiceWithoutSecretPlaceholderIsReported() throws Exception {
        // 声明"密钥写在 URL 里"却没给占位符名，装进去就只能明文落盘——这条必须报数据问题
        McpMarketCatalog.Snapshot snap = parse(oneEntry(GOOD.replace(
                "\"auth\":\"BEARER_TOKEN\"", "\"auth\":\"QUERY_KEY\"")));
        assertEquals(1, snap.entries.size());
        assertEquals("", snap.entries.get(0).secretPlaceholder);
        assertTrue("要点名 secretPlaceholder：" + snap.problems,
                snap.problems.get(0).contains("secretPlaceholder"));

        McpMarketCatalog.Snapshot fixed = parse(oneEntry(GOOD.replace(
                "\"auth\":\"BEARER_TOKEN\"", "\"auth\":\"QUERY_KEY\",\"secretPlaceholder\":\"key\"")));
        assertEquals("补了占位符就该干净：" + fixed.problems, 0, fixed.problems.size());
        assertEquals("key", fixed.entries.get(0).secretPlaceholder);
    }

    @Test
    public void unknownSchemaIsWarnedNotFatal() throws Exception {
        McpMarketCatalog.Snapshot snap = parse(
                "{\"schema\":7,\"version\":\"t\",\"entries\":[" + GOOD + "]}");
        assertEquals(1, snap.entries.size());
        assertTrue(snap.problems.get(0).contains("schema"));
    }

    @Test
    public void missingEntriesArrayYieldsEmptySnapshot() throws Exception {
        McpMarketCatalog.Snapshot snap = parse("{\"schema\":1,\"version\":\"t\"}");
        assertTrue(snap.entries.isEmpty());
        assertTrue(snap.problems.get(0).contains("entries"));
    }

    @Test
    public void placeholderHelpersAreExact() {
        assertTrue(McpMarketEntry.hasPlaceholder("https://x.test/mcp?key={key}"));
        assertFalse(McpMarketEntry.hasPlaceholder("https://x.test/mcp?key=real"));
        assertEquals("mcpCode", McpMarketEntry.placeholderName(
                "https://x.test/api/v1/mcps/{mcpCode}/mcp"));
        assertEquals("", McpMarketEntry.placeholderName("https://x.test/mcp"));
    }

    @Test
    public void capabilityAndAuthEnumsCoverEverythingInCatalog() {
        // 枚举加档时这里要一起加，否则新档位会被 default 当成"本地包·不兼容"渲染
        assertEquals(5, McpMarketEntry.Capability.values().length);
        assertEquals(6, McpMarketEntry.Auth.values().length);
        List<McpMarketEntry.Capability> installable = new ArrayList<>();
        for (McpMarketEntry.Capability c : McpMarketEntry.Capability.values()) {
            if (c.isConnectable()) installable.add(c);
        }
        // 可安装档 = 直连 + 需开通 + 需授权（授权链已接通，"要登录"不等于"装不了"）
        assertEquals(3, installable.size());
    }
}
