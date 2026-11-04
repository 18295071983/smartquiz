package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * OAuth 发现层的解析契约。
 *
 * <p>里面的 JSON 与 header 是 2026-11-04 从两家服务商真实取回来的
 * （阿里云 OpenAPI MCP 的 {@code oauth-protected-resource} / {@code oauth-authorization-server}，
 * MaxCompute 的同源元数据与 401 头），不是照着规范想象出来的样本——
 * 这两家的给法本来就不一致，用理想样本测等于没测。</p>
 */
public class McpOAuthEndpointsTest {

    /** 实测：阿里云 OpenAPI MCP 的 401 头（带 resource_metadata，路径里含服务 ID）。 */
    private static final String ALIYUN_401 =
            "Bearer resource_metadata=\"https://openapi-mcp.cn-hangzhou.aliyuncs.com"
                    + "/.well-known/oauth-protected-resource/s/Cms/cms/abc123/mcp\"";

    /** 实测：MaxCompute 的 401 头（只给 scope，没给 resource_metadata）。 */
    private static final String MAXCOMPUTE_401 =
            "Bearer scope=\"maxcompute:read maxcompute:sql\"";

    /** 实测：阿里云受保护资源文档原文。 */
    private static final String ALIYUN_PR = "{\"resource\":\"https://openapi-mcp.cn-hangzhou"
            + ".aliyuncs.com/s/Cms/cms/abc123/mcp\","
            + "\"authorization_server_url\":[\"https://openapi-mcp.cn-hangzhou.aliyuncs.com"
            + "/.well-known/oauth-authorization-server\"],"
            + "\"bearer_methods_supported\":[\"header\"],"
            + "\"scopes_supported\":[\"openid\",\"/internal/acs/openapi\"],"
            + "\"authorization_servers\":[\"https://openapi-mcp.cn-hangzhou.aliyuncs.com\"]}";

    /** 实测：阿里云授权服务元数据原文。 */
    private static final String ALIYUN_AS = "{\"response_types_supported\":[\"code\"],"
            + "\"code_challenge_methods_supported\":[\"S256\"],"
            + "\"client_id_metadata_document_supported\":false,"
            + "\"grant_types_supported\":[\"authorization_code\",\"refresh_token\"],"
            + "\"revocation_endpoint\":\"https://oauth.aliyun.com/v1/revoke\","
            + "\"registration_endpoint\":\"https://openapi-mcp.cn-hangzhou.aliyuncs.com/register\","
            + "\"token_endpoint_auth_methods_supported\":[\"none\"],"
            + "\"response_modes_supported\":[\"query\"],"
            + "\"issuer\":\"https://openapi-mcp.cn-hangzhou.aliyuncs.com\","
            + "\"authorization_endpoint\":\"https://signin.aliyun.com/oauth2/v1/auth\","
            + "\"token_endpoint\":\"https://oauth.aliyun.com/v1/token\"}";

    /** 实测：MaxCompute 授权服务元数据原文（同域名自己当授权服务）。 */
    private static final String MAXCOMPUTE_AS = "{\"authorization_endpoint\":"
            + "\"https://mcp.cn-hangzhou.maxcompute.aliyun.com/oauth/authorize\","
            + "\"client_id_metadata_document_supported\":true,"
            + "\"code_challenge_methods_supported\":[\"S256\"],"
            + "\"grant_types_supported\":[\"authorization_code\",\"refresh_token\"],"
            + "\"issuer\":\"https://mcp.cn-hangzhou.maxcompute.aliyun.com\","
            + "\"registration_endpoint\":\"https://mcp.cn-hangzhou.maxcompute.aliyun.com/oauth/register\","
            + "\"response_modes_supported\":[\"query\"],\"response_types_supported\":[\"code\"],"
            + "\"revocation_endpoint\":\"https://mcp.cn-hangzhou.maxcompute.aliyun.com/oauth/revoke\","
            + "\"scopes_supported\":[\"maxcompute:read\",\"maxcompute:sql\"],"
            + "\"token_endpoint\":\"https://mcp.cn-hangzhou.maxcompute.aliyun.com/oauth/token\","
            + "\"token_endpoint_auth_methods_supported\":[\"none\"]}";

    // ==================== 401 头 ====================

    @Test
    public void aliyunStyleChallengeCarriesResourceMetadata() {
        McpOAuthEndpoints.Challenge c =
                McpOAuthEndpoints.parseChallenge(401, ALIYUN_401);
        assertTrue(c.hasResourceMetadata());
        assertEquals("https://openapi-mcp.cn-hangzhou.aliyuncs.com"
                + "/.well-known/oauth-protected-resource/s/Cms/cms/abc123/mcp",
                c.resourceMetadataUrl);
        assertEquals("", c.scope);
    }

    @Test
    public void maxComputeStyleChallengeOnlyGivesScope() {
        McpOAuthEndpoints.Challenge c =
                McpOAuthEndpoints.parseChallenge(401, MAXCOMPUTE_401);
        assertFalse("没给 resource_metadata 时要走同源兜底", c.hasResourceMetadata());
        assertEquals("maxcompute:read maxcompute:sql", c.scope);
    }

    @Test
    public void nonBearerAndMissingHeadersAreNotTreatedAsOAuth() {
        assertFalse(McpOAuthEndpoints.parseChallenge(401, "Basic realm=\"mcp\"")
                .hasResourceMetadata());
        assertFalse(McpOAuthEndpoints.parseChallenge(401, null).hasResourceMetadata());
        assertEquals(401, McpOAuthEndpoints.parseChallenge(401, MAXCOMPUTE_401).httpStatus);
    }

    @Test
    public void unquotedParamsAreAlsoRead() {
        assertEquals("https://x.test/metadata", McpOAuthEndpoints.extractParam(
                "Bearer resource_metadata=https://x.test/metadata", "resource_metadata"));
        assertEquals("a b", McpOAuthEndpoints.extractParam("Bearer scope=a%20b, charset=utf-8", "scope"));
    }

    // ==================== 元数据文档 ====================

    @Test
    public void protectedResourceReadsBothAuthorizationServerFields() {
        McpOAuthEndpoints.ProtectedResource pr = McpOAuthEndpoints.parseProtectedResource(ALIYUN_PR);
        assertTrue(pr.resource.startsWith("https://openapi-mcp"));
        assertEquals("两种写法都要收进来", 2, pr.authorizationCandidates.size());
        assertEquals(Arrays.asList("openid", "/internal/acs/openapi"), pr.scopesSupported);
    }

    @Test
    public void authorizationServerExposesWhatTheFlowNeeds() {
        McpOAuthEndpoints.AuthorizationServer as =
                McpOAuthEndpoints.parseAuthorizationServer(ALIYUN_AS);
        assertTrue(as.isUsable());
        assertTrue(as.supportsS256());
        assertTrue("公开客户端必须被接受，否则手机存不住密钥", as.acceptsPublicClient());
        assertTrue("没有续期就每次都要重新登录", as.supportsRefresh());
        assertEquals("https://signin.aliyun.com/oauth2/v1/auth", as.authorizationEndpoint);
        assertEquals("https://oauth.aliyun.com/v1/token", as.tokenEndpoint);
        assertEquals("https://openapi-mcp.cn-hangzhou.aliyuncs.com/register",
                as.registrationEndpoint);
    }

    @Test
    public void maxComputeServerIsUsableWithoutResourceMetadata() {
        McpOAuthEndpoints.AuthorizationServer as =
                McpOAuthEndpoints.parseAuthorizationServer(MAXCOMPUTE_AS);
        assertTrue(as.isUsable());
        assertTrue(as.supportsS256());
        assertEquals("https://mcp.cn-hangzhou.maxcompute.aliyun.com/oauth/register",
                as.registrationEndpoint);
    }

    @Test
    public void garbageMetadataDegradesToUnusableInsteadOfThrowing() {
        assertFalse(McpOAuthEndpoints.parseAuthorizationServer("<html>502</html>").isUsable());
        assertTrue(McpOAuthEndpoints.parseProtectedResource("{").authorizationCandidates.isEmpty());
    }

    // ==================== 地址补全 ====================

    @Test
    public void bareAuthorizationServerOriginGetsWellKnownPaths() {
        assertEquals(Arrays.asList(
                        "https://openapi-mcp.cn-hangzhou.aliyuncs.com/.well-known/oauth-authorization-server",
                        "https://openapi-mcp.cn-hangzhou.aliyuncs.com/.well-known/openid-configuration"),
                McpOAuthEndpoints.authorizationMetadataUrls(
                        "https://openapi-mcp.cn-hangzhou.aliyuncs.com"));
    }

    @Test
    public void fullMetadataUrlIsUsedAsIs() {
        String full = "https://h.test/.well-known/oauth-authorization-server";
        assertEquals(Arrays.asList(full), McpOAuthEndpoints.authorizationMetadataUrls(full));
    }

    @Test
    public void sameOriginFallbackCoversServersWithoutResourceMetadata() {
        assertEquals(Arrays.asList(
                        "https://mcp.cn-hangzhou.maxcompute.aliyun.com/.well-known/oauth-authorization-server",
                        "https://mcp.cn-hangzhou.maxcompute.aliyun.com/.well-known/openid-configuration"),
                McpOAuthEndpoints.sameOriginMetadataUrls(
                        "https://mcp.cn-hangzhou.maxcompute.aliyun.com/mcp"));
    }

    @Test
    public void originKeepsSchemeHostAndPort() {
        assertEquals("https://h.test", McpOAuthEndpoints.originOf("https://h.test:443/a/b?x=1"));
        assertEquals("http://127.0.0.1:8765", McpOAuthEndpoints.originOf("http://127.0.0.1:8765/mcp"));
        assertEquals("", McpOAuthEndpoints.originOf("not a url"));
        assertEquals("", McpOAuthEndpoints.originOf(null));
    }

    @Test
    public void authRequiredExceptionSeparatesKeyProblemFromAuthorizationProblem() {
        // 401 + resource_metadata → 该给"去授权"；401 没给 → 该先怀疑密钥；403 → 权限问题
        McpAuthRequiredException oauth = McpAuthRequiredException.fromFailure(
                "https://openapi-mcp.test/s/x/mcp", 401, ALIYUN_401);
        assertTrue(oauth.hasResourceMetadata());
        assertTrue(oauth.getMessage().contains("需要浏览器授权"));

        McpAuthRequiredException plain = McpAuthRequiredException.fromFailure(
                "https://h.test/mcp", 401, MAXCOMPUTE_401);
        assertFalse(plain.hasResourceMetadata());
        assertTrue(plain.getMessage().contains("401"));

        McpAuthRequiredException forbidden = McpAuthRequiredException.fromFailure(
                "https://h.test/mcp", 403, null);
        assertTrue(forbidden.getMessage().contains("403"));

        assertEquals("其余状态码不当授权问题处理", null,
                McpAuthRequiredException.fromFailure("https://h.test/mcp", 500, null));
    }

    @Test
    public void maskedEndpointNeverLeaksQueryKeyInAuthErrors() {
        McpAuthRequiredException e = McpAuthRequiredException.fromFailure(
                "https://mcp.map.qq.com/mcp?key=SECRET123&format=0", 401, "Bearer scope=\"x\"");
        assertTrue(e.getMessage().contains("key=****"));
        assertFalse(e.getMessage().contains("SECRET123"));
    }
}
