package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 内置市场清单（{@code src/main/assets/mcp_market_catalog.json}）的数据契约。
 *
 * <p>读的是随包那份真文件、走的是和产品同一条解析路径（{@link McpMarketCatalog#parse}），
 * 不是测试目录里的副本——副本会漂，验副本等于没验。</p>
 *
 * <p>断言的着力点放在"这一条会不会让人白装一次"：可安装档必须有端点、有鉴权说明、
 * 有自检证据；旧版里三条其实装不上的（MaxCompute 要 OAuth、OpenAPI MCP 那条是别人账号的分享端点）
 * 现在必须被明确标成不可安装。</p>
 */
public class McpMarketCatalogTest {

    private static final Path CATALOG =
            Paths.get("src/main/assets/mcp_market_catalog.json");

    private static McpMarketCatalog.Snapshot snapshot() throws Exception {
        assertTrue("清单文件必须存在：" + CATALOG, Files.exists(CATALOG));
        String json = new String(Files.readAllBytes(CATALOG), StandardCharsets.UTF_8);
        return McpMarketCatalog.parse(json);
    }

    private static McpMarketEntry byId(List<McpMarketEntry> entries, String id) {
        for (McpMarketEntry e : entries) {
            if (e.id.equals(id)) return e;
        }
        return null;
    }

    @Test
    public void realCatalogCarriesNoDataProblems() throws Exception {
        McpMarketCatalog.Snapshot snap = snapshot();
        assertEquals("清单数据问题应为 0，实际：" + snap.problems, 0, snap.problems.size());
        assertTrue("条目太少不算清单", snap.entries.size() >= 6);
        assertFalse("缺版本号", snap.version.isEmpty());
        assertFalse("缺免责说明（用户会以为是长期保证）", snap.disclaimer.isEmpty());
    }

    @Test
    public void everyEntryIsIdentifiableAndSourced() throws Exception {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (McpMarketEntry e : snapshot().entries) {
            assertTrue("id 必须能直接当配置 id：" + e.id, McpMarketEntry.isConfigIdSafe(e.id));
            assertTrue("id 撞车：" + e.id, ids.add(e.id));
            assertFalse("缺标题：" + e.id, e.title.isEmpty());
            assertFalse("缺服务商：" + e.id, e.provider.isEmpty());
            assertFalse("缺描述：" + e.id, e.description.isEmpty());
            assertFalse("缺开通指引：" + e.id, e.guide.isEmpty());
            assertTrue("docsUrl 必须是 http(s)：" + e.id, e.docsUrl.startsWith("http"));
            assertFalse("必须标文档核对日期：" + e.id, e.verifiedAt.isEmpty());
            assertNotNull("必须有自检记录：" + e.id, e.probe);
            assertFalse("自检要写清看了什么：" + e.id, e.probe.evidence.isEmpty());
            assertFalse("自检要写日期：" + e.id, e.probe.checkedAt.isEmpty());
        }
    }

    @Test
    public void installableEntriesHaveEndpointAndAuthStory() throws Exception {
        int installable = 0;
        for (McpMarketEntry e : snapshot().entries) {
            if (!e.capability.isConnectable()) {
                assertFalse("不可安装档必须写为什么不行：" + e.id, e.blockedReason.isEmpty());
                continue;
            }
            installable++;
            assertNotNull("可安装档必须有端点：" + e.id, e.defaultEndpoint());
            assertFalse("端点模板为空：" + e.id, e.defaultEndpoint().url.isEmpty());
            assertFalse("必须说明怎么拿密钥：" + e.id, e.authHint.isEmpty());
            assertTrue("鉴权方式不许是 UNKNOWN：" + e.id,
                    e.auth != McpMarketEntry.Auth.UNKNOWN);
            assertFalse("自检必须真跑过协议（unverified 不能当可安装）：" + e.id,
                    "unverified".equals(e.probe.result));
            // 端点模板要么已可用，要么把要替换的部分明写在 {占位符} 里
            String url = e.defaultEndpoint().url;
            if (!McpServerConfig.isUsableEndpoint(url)) {
                assertTrue("不可用的端点必须以 {占位符} 形式给出：" + e.id + " → " + url,
                        McpMarketEntry.hasPlaceholder(url));
            }
        }
        assertTrue("至少要有两条能直接装的服务", installable >= 2);
    }

    @Test
    public void maxComputeIsNoLongerOfferedAsInstallable() throws Exception {
        // 旧版把这条标成「可直连」，但官方 Remote MCP 只走浏览器 OAuth，装了必然连不上
        McpMarketEntry mc = byId(snapshot().entries, "aliyun-maxcompute");
        assertNotNull("MaxCompute 条目应保留在清单里作为说明", mc);
        assertEquals(McpMarketEntry.Capability.NEEDS_OAUTH, mc.capability);
        assertEquals(McpMarketEntry.Auth.OAUTH, mc.auth);
        assertFalse(mc.capability.isConnectable());
        assertFalse("必须写明被挡的原因", mc.blockedReason.isEmpty());
    }

    @Test
    public void borrowedShareEndpointIsGone() throws Exception {
        // 旧版把一条控制台为别的账号下发的分享端点当公共入口内置，这是"别人的服务实例"
        McpMarketCatalog.Snapshot snap = snapshot();
        String raw = new String(Files.readAllBytes(CATALOG), StandardCharsets.UTF_8);
        assertFalse("不得内置他人分享端点的 hash：" + snap.version,
                raw.contains("9fe79ff0bf5748e6923530c5097ec359"));
        assertNull("aliyun-cms 这条已换成占位模板条目", byId(snap.entries, "aliyun-cms"));
        McpMarketEntry openapi = byId(snap.entries, "aliyun-openapi-mcp");
        assertNotNull(openapi);
        assertTrue("必须是带占位符的模板：" + openapi.defaultEndpoint().url,
                McpMarketEntry.hasPlaceholder(openapi.defaultEndpoint().url));
        assertEquals(McpMarketEntry.Capability.PROVISIONED_REMOTE, openapi.capability);
    }

    @Test
    public void groupingPutsWhatCanBeInstalledNowFirst() throws Exception {
        Map<McpMarketEntry.Capability, List<McpMarketEntry>> grouped =
                McpMarketCatalog.groupByCapability(snapshot().entries);
        List<McpMarketEntry.Capability> order = McpMarketCatalog.CAPABILITY_ORDER;
        assertEquals(5, order.size());
        assertEquals(McpMarketEntry.Capability.DIRECT_REMOTE, order.get(0));
        assertEquals(McpMarketEntry.Capability.PROVISIONED_REMOTE, order.get(1));
        assertFalse(grouped.get(McpMarketEntry.Capability.DIRECT_REMOTE).isEmpty());
        assertFalse(grouped.get(McpMarketEntry.Capability.PORTAL_ONLY).isEmpty());
        for (McpMarketEntry e : grouped.get(McpMarketEntry.Capability.DIRECT_REMOTE)) {
            assertTrue("直连档不该有 blockedReason：" + e.id, e.blockedReason.isEmpty());
        }
    }

    @Test
    public void sseAndStreamableVariantsAreBothCarriedForTencentMap() throws Exception {
        McpMarketEntry tencent = byId(snapshot().entries, "tencent-map");
        assertNotNull(tencent);
        assertEquals(2, tencent.endpoints.size());
        assertEquals(McpServerConfig.Transport.STREAMABLE_HTTP, tencent.endpoints.get(0).transport);
        assertEquals(McpServerConfig.Transport.SSE, tencent.endpoints.get(1).transport);
        assertTrue(tencent.probe.isHandshakeOk());
    }
}
