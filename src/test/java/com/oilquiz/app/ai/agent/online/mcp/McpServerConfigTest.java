package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 配置入口的防呆：id 规范化与端点可用性（占位符不能直接添加）。 */
public class McpServerConfigTest {

    @Test
    public void normalizeIdProducesStableSafeKey() {
        assertEquals("my_server", McpServerConfig.normalizeId("My Server!"));
        assertEquals("aliyun_maxcompute", McpServerConfig.normalizeId("Aliyun MaxCompute"));
        // 中文名会被规范成同一个占位 id；唯一性由 UI 的 newUniqueId 兜底
        assertEquals("server", McpServerConfig.normalizeId("阿里云"));
        assertEquals("server", McpServerConfig.normalizeId("腾讯云"));
    }

    @Test
    public void endpointMustBeHttpAndResolved() {
        assertTrue(McpServerConfig.isUsableEndpoint("https://example.com/mcp"));
        assertTrue(McpServerConfig.isUsableEndpoint("http://127.0.0.1:8765/mcp"));
        assertFalse("占位路径不能直接添加",
                McpServerConfig.isUsableEndpoint("https://dashscope.aliyuncs.com/api/v1/mcps/{mcpCode}/mcp"));
        assertFalse("示例 key 不能直接添加",
                McpServerConfig.isUsableEndpoint("https://mcp.map.qq.com/sse?key=你的Key"));
        assertFalse(McpServerConfig.isUsableEndpoint("ftp://example.com/mcp"));
        assertFalse(McpServerConfig.isUsableEndpoint(""));
    }

    @Test
    public void querySecretsAreMaskedForDisplay() {
        assertEquals("https://mcp.map.qq.com/sse?key=****",
                McpServerConfig.maskUrl("https://mcp.map.qq.com/sse?key=abc123"));
        assertEquals("https://x/mcp?token=****&a=1",
                McpServerConfig.maskUrl("https://x/mcp?token=secret&a=1"));
        assertEquals("https://x/mcp",
                McpServerConfig.maskUrl("https://x/mcp"));
    }

    @Test
    public void enabledToggleKeepsTokenRefAndApproval() throws Exception {
        McpServerConfig c = new McpServerConfig("id", "n", "https://x/mcp",
                McpServerConfig.Transport.STREAMABLE_HTTP, "", "my-key", true, false)
                .withToolApproval("write", "ask");
        McpServerConfig off = c.withEnabled(false);
        assertEquals("my-key", off.tokenRef);
        assertEquals("ask", off.approvalFor("write"));

        McpServerConfig round = McpServerConfig.fromJson(off.toJson());
        assertTrue(round != null);
        assertEquals("my-key", round.tokenRef);
        assertEquals("ask", round.approvalFor("write"));
        // 清除策略 = 跟随注解
        assertEquals("", round.withToolApproval("write", "").approvalFor("write"));
    }

    /**
     * 密钥在 URL 里的服务：端点存占位符，值单独加密。
     *
     * <p>这一组断言守的是"同一个文件里两类密钥不该有两种保密程度"——
     * Bearer 那支早就走 Keystore，query 那支曾经是明文写死在 URL 上（真机配置里能直接读出 Key）。</p>
     */
    @Test
    public void querySecretIsStoredAsPlaceholderAndInjectedLate() {
        String template = "https://mcp.amap.com/mcp?key={key}";
        assertTrue(McpServerConfig.urlNeedsSecret(template));
        assertFalse(McpServerConfig.urlNeedsSecret("https://mcp.amap.com/mcp?key=real"));

        String filled = McpServerConfig.injectSecret(template, "abc123");
        assertEquals("https://mcp.amap.com/mcp?key=abc123", filled);
        assertEquals("没密钥时不要把占位符原样发出去", template, McpServerConfig.injectSecret(template, ""));
        assertEquals("不含占位符的端点不该被动", "https://x.test/mcp",
                McpServerConfig.injectSecret("https://x.test/mcp", "abc"));
        assertTrue("含 + 与空格的 Key 必须转义",
                McpServerConfig.injectSecret(template, "a b+c").contains("key=a%20b%2Bc"));

        assertTrue("带占位符的模板可以保存", McpServerConfig.isUsableEndpointWithKeyPlaceholder(template));
        assertFalse("带别的占位符仍然不行",
                McpServerConfig.isUsableEndpointWithKeyPlaceholder(
                        "https://x.test/api/{mcpCode}/mcp"));
    }

    @Test
    public void migratingAPlaintextQuerySecretLeavesOnlyThePlaceholder() {
        String[] parts = McpServerConfig.splitQuerySecret(
                "https://mcp.amap.com/mcp?key=0123456789abcdef0123456789abcdef");
        assertEquals("https://mcp.amap.com/mcp?key={key}", parts[0]);
        assertEquals("0123456789abcdef0123456789abcdef", parts[1]);

        String[] none = McpServerConfig.splitQuerySecret("https://x.test/mcp");
        assertEquals("https://x.test/mcp", none[0]);
        assertEquals("", none[1]);

        // 已经是占位符的别再"迁移"一次，否则会把 {key} 当成密钥值存进去
        String[] again = McpServerConfig.splitQuerySecret(
                "https://mcp.amap.com/mcp?key={key}");
        assertEquals("", again[1]);

        String[] twoParams = McpServerConfig.splitQuerySecret(
                "https://mcp.map.qq.com/mcp?key=K1&format=0");
        assertEquals("https://mcp.map.qq.com/mcp?key={key}&format=0", twoParams[0]);
        assertEquals("K1", twoParams[1]);

        // 拆完之后原串仍要能脱敏显示（两处名单必须一致，否则一处遮一处漏）
        assertTrue(McpServerConfig.maskUrl("https://mcp.map.qq.com/mcp?key=K1&format=0")
                .contains("key=****"));
        assertFalse(McpServerConfig.maskUrl("https://mcp.map.qq.com/mcp?key=K1&format=0")
                .contains("K1"));
    }

    @Test
    public void urlAndTokenCanChangeTogether() {
        McpServerConfig c = new McpServerConfig("id", "n", "https://x/mcp?key=PLAIN",
                McpServerConfig.Transport.STREAMABLE_HTTP, "", "", true, true)
                .withToolApproval("maps_geo", "auto");
        McpServerConfig migrated = c.withUrlAndToken("https://x/mcp?key={key}", "PLAIN");
        assertEquals("https://x/mcp?key={key}", migrated.url);
        assertEquals("PLAIN", migrated.token);
        assertEquals("迁移不能丢审批策略", "auto", migrated.approvalFor("maps_geo"));
        assertEquals("迁移不能丢来源标记", true, migrated.builtin);
    }
}
