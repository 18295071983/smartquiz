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
}
