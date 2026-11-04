package com.oilquiz.app.ai.agent.online.mcp;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 某个 MCP 服务的一次 OAuth 授权记录（纯数据 + 序列化，不碰 Android/Keystore，便于 JVM 单测）。
 *
 * <p>为什么要连 authorize/token/register 端点一起存，而不是每次重新发现：授权服务元数据是服务商
 * 随时可改的，但续期必须在"改了也能用"的前提下活着——存快照 + 续期失败时重新发现一次，
 * 比要么写死要么每次联网发现都稳。</p>
 *
 * <p>{@link #accessToken} / {@link #refreshToken} 落盘前由 {@link McpTokenStore} 用
 * {@code ApiKeyCipher} 加密（Keystore AES-GCM，密文带 {@code enc:} 前缀）；本类只表示明文形态。</p>
 */
public final class McpOAuthRecord {

    public final String serverId;
    public final String issuer;
    public final String authorizationEndpoint;
    public final String tokenEndpoint;
    public final String registrationEndpoint;
    public final String revocationEndpoint;
    public final String clientId;
    /** 注册时被服务商接受的那个回调地址（自定义 scheme 或环回），续期与重授权都要用同一个 */
    public final String redirectUri;
    public final String scope;
    public final String accessToken;
    public final String refreshToken;
    /** access_token 的绝对到期时刻（毫秒）；0 = 服务商没给 expires_in */
    public final long expiresAtMs;
    /** 本次授权完成时刻（展示与排障用） */
    public final long authorizedAtMs;

    public McpOAuthRecord(String serverId, String issuer, String authorizationEndpoint,
                          String tokenEndpoint, String registrationEndpoint,
                          String revocationEndpoint, String clientId, String redirectUri,
                          String scope, String accessToken, String refreshToken,
                          long expiresAtMs, long authorizedAtMs) {
        this.serverId = nullToEmpty(serverId);
        this.issuer = nullToEmpty(issuer);
        this.authorizationEndpoint = nullToEmpty(authorizationEndpoint);
        this.tokenEndpoint = nullToEmpty(tokenEndpoint);
        this.registrationEndpoint = nullToEmpty(registrationEndpoint);
        this.revocationEndpoint = nullToEmpty(revocationEndpoint);
        this.clientId = nullToEmpty(clientId);
        this.redirectUri = nullToEmpty(redirectUri);
        this.scope = nullToEmpty(scope);
        this.accessToken = nullToEmpty(accessToken);
        this.refreshToken = nullToEmpty(refreshToken);
        this.expiresAtMs = expiresAtMs;
        this.authorizedAtMs = authorizedAtMs;
    }

    public boolean hasClient() {
        return !clientId.isEmpty() && !authorizationEndpoint.isEmpty()
                && !tokenEndpoint.isEmpty();
    }

    public boolean hasAccessToken() {
        return !accessToken.isEmpty();
    }

    /** 令牌还能不能用（含 60 秒提前量，与 {@link McpOAuthClient.TokenSet#isFresh} 同口径）。 */
    public boolean isAccessTokenFresh(long nowMs) {
        return hasAccessToken() && (expiresAtMs <= 0 || nowMs < expiresAtMs - 60_000L);
    }

    /** 该不该现在就续期（临期 5 分钟内、且手上有 refresh_token）。 */
    public boolean shouldRefresh(long nowMs) {
        return !refreshToken.isEmpty() && expiresAtMs > 0
                && nowMs >= expiresAtMs - 5 * 60_000L;
    }

    public boolean isLoopbackRedirect() {
        return redirectUri.contains("127.0.0.1");
    }

    /** 换成新令牌组；client / 端点 / 回调地址保持不变。 */
    public McpOAuthRecord withTokens(String newAccess, String newRefresh, long newExpiresAtMs,
                                     String newScope) {
        String keepRefresh = newRefresh == null || newRefresh.isEmpty() ? refreshToken : newRefresh;
        String keepScope = newScope == null || newScope.isEmpty() ? scope : newScope;
        return new McpOAuthRecord(serverId, issuer, authorizationEndpoint, tokenEndpoint,
                registrationEndpoint, revocationEndpoint, clientId, redirectUri, keepScope,
                newAccess, keepRefresh, newExpiresAtMs, authorizedAtMs);
    }

    /** 只改令牌，不动授权完成时间。 */
    public McpOAuthRecord withClientId(String newClientId, String newRedirectUri) {
        return new McpOAuthRecord(serverId, issuer, authorizationEndpoint, tokenEndpoint,
                registrationEndpoint, revocationEndpoint, newClientId,
                newRedirectUri == null || newRedirectUri.isEmpty() ? redirectUri : newRedirectUri,
                scope, accessToken, refreshToken, expiresAtMs, authorizedAtMs);
    }

    /** 授权服务元数据视图（续期时直接拿来用，不用再发现一次）。 */
    public McpOAuthEndpoints.AuthorizationServer asAuthorizationServer() {
        List<String> methods = new ArrayList<>();
        methods.add("none");
        List<String> grants = new ArrayList<>();
        grants.add("authorization_code");
        if (!refreshToken.isEmpty()) grants.add("refresh_token");
        List<String> responseTypes = new ArrayList<>();
        responseTypes.add("code");
        List<String> pkce = new ArrayList<>();
        pkce.add("S256");
        return new McpOAuthEndpoints.AuthorizationServer(issuer, authorizationEndpoint,
                tokenEndpoint, registrationEndpoint, revocationEndpoint, pkce, grants,
                responseTypes, methods);
    }

    // ==================== 序列化 ====================

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("serverId", serverId);
        o.put("issuer", issuer);
        o.put("authorizationEndpoint", authorizationEndpoint);
        o.put("tokenEndpoint", tokenEndpoint);
        o.put("registrationEndpoint", registrationEndpoint);
        o.put("revocationEndpoint", revocationEndpoint);
        o.put("clientId", clientId);
        o.put("redirectUri", redirectUri);
        o.put("scope", scope);
        o.put("accessToken", accessToken);
        o.put("refreshToken", refreshToken);
        o.put("expiresAtMs", expiresAtMs);
        o.put("authorizedAtMs", authorizedAtMs);
        return o;
    }

    public static McpOAuthRecord fromJson(JSONObject o) {
        if (o == null) return null;
        String serverId = o.optString("serverId", "");
        if (serverId.isEmpty()) return null;
        return new McpOAuthRecord(serverId,
                o.optString("issuer", ""),
                o.optString("authorizationEndpoint", ""),
                o.optString("tokenEndpoint", ""),
                o.optString("registrationEndpoint", ""),
                o.optString("revocationEndpoint", ""),
                o.optString("clientId", ""),
                o.optString("redirectUri", ""),
                o.optString("scope", ""),
                o.optString("accessToken", ""),
                o.optString("refreshToken", ""),
                o.optLong("expiresAtMs", 0),
                o.optLong("authorizedAtMs", 0));
    }

    /** 列表页展示用：不暴露令牌，只说"授权到哪一步"。 */
    public String describe(long nowMs) {
        if (!hasClient()) return "未注册";
        if (!hasAccessToken()) return "已注册，没拿到令牌";
        if (isAccessTokenFresh(nowMs)) {
            return expiresAtMs > 0
                    ? "令牌有效（剩 " + Math.max(0, (expiresAtMs - nowMs) / 60_000L) + " 分钟）"
                    : "令牌有效（服务商未给出有效期）";
        }
        return refreshToken.isEmpty() ? "令牌已过期且无法续期" : "令牌已过期，可自动续期";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
