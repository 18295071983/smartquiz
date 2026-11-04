package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 授权记录（{@link McpOAuthRecord}）的序列化与有效期判断。
 *
 * <p>这张记录是"重启后还能不能继续用这个服务"的唯一依据：端点快照丢了就没法续期，
 * refresh_token 丢了就得让用户重新登录一遍。所以序列化必须无损，且过期判断只认时间不认文案。</p>
 */
public class McpOAuthRecordTest {

    private static McpOAuthRecord record(String access, String refresh, long expiresAtMs) {
        return new McpOAuthRecord("aliyun-maxcompute", "https://mcp.test",
                "https://mcp.test/oauth/authorize", "https://mcp.test/oauth/token",
                "https://mcp.test/oauth/register", "https://mcp.test/oauth/revoke",
                "cid-1", "http://127.0.0.1:45000/callback", "maxcompute:read",
                access, refresh, expiresAtMs, 1_700_000_000_000L);
    }

    @Test
    public void jsonRoundTripKeepsEveryField() throws Exception {
        McpOAuthRecord src = record("access-1", "refresh-1", 2_000_000_000_000L);
        McpOAuthRecord back = McpOAuthRecord.fromJson(new JSONObject(src.toJson().toString()));
        assertEquals(src.serverId, back.serverId);
        assertEquals("续期要用授权服务快照，不能重新猜", src.tokenEndpoint, back.tokenEndpoint);
        assertEquals(src.authorizationEndpoint, back.authorizationEndpoint);
        assertEquals(src.registrationEndpoint, back.registrationEndpoint);
        assertEquals(src.revocationEndpoint, back.revocationEndpoint);
        assertEquals(src.clientId, back.clientId);
        assertEquals(src.redirectUri, back.redirectUri);
        assertEquals(src.scope, back.scope);
        assertEquals(src.accessToken, back.accessToken);
        assertEquals(src.refreshToken, back.refreshToken);
        assertEquals(src.expiresAtMs, back.expiresAtMs);
        assertEquals(src.authorizedAtMs, back.authorizedAtMs);
    }

    @Test
    public void missingServerIdIsRejectedRatherThanKeyedByEmptyString() {
        assertNull("没有 serverId 的记录不能进表", McpOAuthRecord.fromJson(new JSONObject()));
        assertNull(McpOAuthRecord.fromJson(null));
        assertEquals("aliyun-maxcompute", record("a", "b", 0).serverId);
    }

    @Test
    public void freshnessAndRenewalUseTimeNotWording() {
        long now = 1_000_000_000_000L;
        McpOAuthRecord healthy = record("a", "r", now + 3_600_000L);
        assertTrue(healthy.isAccessTokenFresh(now));
        assertFalse(healthy.shouldRefresh(now));

        McpOAuthRecord expiring = record("a", "r", now + 120_000L);
        // 提前量 60 秒（还能发最后一个请求），续期窗口 5 分钟（有余量就换新的）——两段刻意不同
        assertTrue("还剩 2 分钟，仍可用", expiring.isAccessTokenFresh(now));
        assertTrue("但已进入续期窗口", expiring.shouldRefresh(now));

        McpOAuthRecord lastGasp = record("a", "r", now + 30_000L);
        assertFalse("只剩 30 秒就别再拿去发请求", lastGasp.isAccessTokenFresh(now));

        McpOAuthRecord unknownExpiry = record("a", "", 0);
        assertTrue("服务商没给有效期时不拦请求", unknownExpiry.isAccessTokenFresh(now));
        assertFalse(unknownExpiry.shouldRefresh(now));

        McpOAuthRecord empty = record("", "", 0);
        assertFalse("没令牌就不算可用", empty.isAccessTokenFresh(now));
        // hasClient 说的是"登记过没有"，与有没有令牌是两件事：
        // 注册成功但换令牌失败（用户在浏览器里关了页）就是 hasClient true + 无令牌
        assertTrue(record("", "", 0).hasClient());
        assertFalse(new McpOAuthRecord("s", "", "", "", "", "", "", "", "",
                "", "", 0, 0).hasClient());
    }

    @Test
    public void refreshWithoutNewRefreshTokenKeepsTheOldOne() {
        McpOAuthRecord src = record("old-access", "keep-me", 111L);
        McpOAuthRecord next = src.withTokens("new-access", "", 222L, "");
        assertEquals("new-access", next.accessToken);
        assertEquals("服务商不回新 refresh_token 是常见的，旧的不能丢",
                "keep-me", next.refreshToken);
        assertEquals("scope 同理", "maxcompute:read", next.scope);
        assertEquals(222L, next.expiresAtMs);
        assertEquals("授权完成时间不该被续期改写", src.authorizedAtMs, next.authorizedAtMs);
    }

    @Test
    public void loopbackRedirectIsRecognisedSoTheReceiverCanStart() {
        assertTrue(record("a", "b", 0).isLoopbackRedirect());
        assertFalse(record("a", "b", 0).withClientId("cid-2",
                "smartquiz-mcp-oauth://callback").isLoopbackRedirect());
    }

    @Test
    public void authorizationServerViewIsEnoughToRefreshOffline() {
        McpOAuthEndpoints.AuthorizationServer as = record("a", "r", 0).asAuthorizationServer();
        assertTrue(as.isUsable());
        assertTrue(as.supportsS256());
        assertTrue(as.acceptsPublicClient());
        assertTrue("记录里有 refresh_token 才声明支持续期", as.supportsRefresh());
        assertEquals("https://mcp.test/oauth/token", as.tokenEndpoint);
    }

    @Test
    public void describeReportsStateWithoutLeakingTokens() {
        long now = 1_000_000_000_000L;
        assertEquals("未注册", new McpOAuthRecord("s", "", "", "", "", "", "", "", "",
                "tok", "", 0, 0).describe(now));
        assertEquals("已注册，没拿到令牌", record("", "", 0).describe(now));
        String fresh = record("a", "r", now + 600_000L).describe(now);
        assertTrue(fresh.contains("令牌有效"));
        assertTrue("要给出剩余分钟数：" + fresh, fresh.contains("10"));
        assertTrue(record("a", "", now - 1).describe(now).contains("无法续期"));
        assertTrue(record("a", "r", now - 1).describe(now).contains("可自动续期"));
    }
}
