package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * OAuth 客户端动作的纯逻辑契约：授权地址、回调候选、错误解读、令牌有效期判断。
 *
 * <p>网络那半（注册/换令牌）不在这里跑，但参数怎么拼、响应怎么判，
 * 恰恰是最容易"跑通了但下次就坏"的部分——比如少带一个 {@code resource} 参数，
 * 服务商就会在换令牌时才报错，而那时用户已经登录完了。</p>
 */
public class McpOAuthClientTest {

    private static McpOAuthEndpoints.AuthorizationServer server(String registration) {
        return new McpOAuthEndpoints.AuthorizationServer(
                "https://as.test",
                "https://as.test/oauth/authorize",
                "https://as.test/oauth/token",
                registration,
                "https://as.test/oauth/revoke",
                new ArrayList<>(Arrays.asList("S256")),
                new ArrayList<>(Arrays.asList("authorization_code", "refresh_token")),
                new ArrayList<>(Arrays.asList("code")),
                new ArrayList<>(Arrays.asList("none")));
    }

    @Test
    public void authorizeUrlCarriesEveryRequiredParameter() {
        String url = McpOAuthClient.buildAuthorizeUrl(server("https://as.test/register"),
                "client-1", "smartquiz-mcp-oauth://callback", "state-abc",
                "challenge-xyz", "openid /internal/acs/openapi",
                "https://mcp.test/s/x/mcp");
        assertTrue(url.startsWith("https://as.test/oauth/authorize?"));
        for (String pair : Arrays.asList("response_type=code", "client_id=client-1",
                "state=state-abc", "code_challenge=challenge-xyz", "code_challenge_method=S256",
                "redirect_uri=smartquiz-mcp-oauth", "scope=openid", "resource=https")) {
            assertTrue("少了 " + pair + "：" + url, url.contains(pair));
        }
    }

    @Test
    public void emptyOrNonHttpResourceIsNotSent() {
        String withEmpty = McpOAuthClient.buildAuthorizeUrl(server(""), "c", "r", "s", "ch", "", "");
        assertFalse("没 resource 就别猜：" + withEmpty, withEmpty.contains("resource="));
        String withRelative = McpOAuthClient.buildAuthorizeUrl(server(""), "c", "r", "s", "ch",
                "", "mcp.local");
        assertFalse(withRelative.contains("resource="));
    }

    @Test
    public void badAuthorizationEndpointYieldsEmptyUrlInsteadOfGarbage() {
        McpOAuthEndpoints.AuthorizationServer broken = new McpOAuthEndpoints.AuthorizationServer(
                "https://as.test", "不是 URL", "https://as.test/oauth/token", "", "",
                new ArrayList<String>(), new ArrayList<String>(), new ArrayList<String>(),
                new ArrayList<String>());
        assertEquals("", McpOAuthClient.buildAuthorizeUrl(broken, "c", "r", "s", "ch", "", ""));
    }

    @Test
    public void redirectCandidatesStartWithCustomSchemeAndAddLoopbackOnlyWhenAsked() {
        assertEquals(Arrays.asList("smartquiz-mcp-oauth://callback"),
                McpOAuthClient.redirectCandidates(0));
        assertEquals(Arrays.asList("smartquiz-mcp-oauth://callback", "http://127.0.0.1:45123/callback"),
                McpOAuthClient.redirectCandidates(45123));
    }

    @Test
    public void registrationRemembersWhichRedirectWorked() {
        McpOAuthClient.Registration custom = new McpOAuthClient.Registration(
                "cid", "", "smartquiz-mcp-oauth://callback", 0, 0);
        assertFalse(custom.isLoopbackRedirect());
        McpOAuthClient.Registration loop = new McpOAuthClient.Registration(
                "cid", "", "http://127.0.0.1:45123/callback", 0, 0);
        assertTrue("环回那一支必须能被识别出来，否则不会起本地监听", loop.isLoopbackRedirect());
    }

    @Test
    public void oauthErrorDescriptionsAreReadableNotRawHtml() {
        assertEquals("invalid_client: client not found",
                McpOAuthClient.describeOAuthError(
                        "{\"error\":\"invalid_client\",\"error_description\":\"client not found\"}", 400));
        assertEquals("server_error", McpOAuthClient.describeOAuthError("{\"error\":\"server_error\"}", 500));
        String html = McpOAuthClient.describeOAuthError(
                "<html><body>  <h1>502 Bad Gateway</h1>  </body></html>", 502);
        assertFalse("状态码不该当成正文返回", html.startsWith("502"));
        assertTrue("HTML 要压成一行：" + html, !html.contains("\n"));
        assertTrue(html.contains("502 Bad Gateway"));
        assertEquals("HTTP 401", McpOAuthClient.describeOAuthError("   ", 401));
    }

    @Test
    public void redirectRejectionIsTheOneCaseWorthRetrying() {
        assertTrue(McpOAuthClient.isRedirectRejected(
                "invalid_redirect_uri: redirect_uri must be RFC 8252 loopback"));
        assertTrue(McpOAuthClient.isRedirectRejected("Redirect URI 不符合白名单"));
        assertFalse("其他注册错误不该无脑重试第二遍",
                McpOAuthClient.isRedirectRejected("invalid_client_metadata"));
        assertFalse(McpOAuthClient.isRedirectRejected(null));
    }

    @Test
    public void tokenFreshnessKeepsSixtySecondsOfHeadroom() {
        long now = 1_000_000_000L;
        McpOAuthClient.TokenSet fresh = new McpOAuthClient.TokenSet(
                "a", "r", "Bearer", "scope", now + 3_600_000L);
        assertTrue(fresh.isFresh(now));
        assertFalse("已过期就不能算 fresh", fresh.isFresh(now + 3_700_000L));
        // 只剩 30 秒：还没过期，但已经不该再拿去发请求
        McpOAuthClient.TokenSet almost = new McpOAuthClient.TokenSet(
                "a", "r", "Bearer", "", now + 30_000L);
        assertFalse(almost.isFresh(now));
        assertTrue("临期要主动续期，别等 401", almost.shouldRenew(now));
        McpOAuthClient.TokenSet noExpiry = new McpOAuthClient.TokenSet(
                "a", "", "Bearer", "", 0);
        assertTrue("服务商没给 expires_in 时按可用处理", noExpiry.isFresh(now));
        assertFalse(noExpiry.shouldRenew(now));
        assertFalse("空令牌永远不算 fresh",
                new McpOAuthClient.TokenSet("", "", "Bearer", "", 0).isFresh(now));
    }
}
