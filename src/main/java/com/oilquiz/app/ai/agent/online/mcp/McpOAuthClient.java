package com.oilquiz.app.ai.agent.online.mcp;

import com.oilquiz.app.ai.util.NetworkUtil;
import com.oilquiz.app.util.AILogger;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * MCP 的 OAuth 客户端动作：动态注册（RFC 7591）、授权 URL 组装、授权码换令牌、续期。
 *
 * <p>本 App 是公开客户端，所以：</p>
 * <ul>
 *   <li>不申请也不保存 client_secret（两家服务商的元数据都只声明 {@code token_endpoint_auth_method=none}）；</li>
 *   <li>授权码必须配 PKCE（{@link McpPkce}），否则中途被劫持就能换到令牌；</li>
 *   <li>回调地址只有两种合法形状——自定义 scheme（{@code smartquiz-mcp-oauth://callback}）
 *       与 RFC 8252 环回（{@code http://127.0.0.1:port/callback}）。实测差异就在这：
 *       阿里云 OpenAPI MCP 接受自定义 scheme，MaxCompute 直接回
 *       {@code invalid_redirect_uri: redirect_uri must be RFC 8252 loopback…}，
 *       所以注册要按候选地址依次试，不能只写一种。</li>
 * </ul>
 *
 * <p>所有方法都会发真实请求，必须在后台线程调用。</p>
 */
public final class McpOAuthClient {

    private static final String TAG = "McpOAuth";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 提前 60 秒判定"该续期了"，避免正好卡在过期边界上发一个必 401 的请求。 */
    private static final long EXPIRY_SAFETY_MS = 60_000L;

    private McpOAuthClient() {}

    // ==================== 数据类 ====================

    /** 动态注册结果。 */
    public static final class Registration {
        public final String clientId;
        /** 公开客户端应为空；服务商给了也只用一次，不落盘明文 */
        public final String clientSecret;
        /** 注册成功时服务商接受的那个回调地址 */
        public final String redirectUri;
        public final long clientIdIssuedAtMs;
        public final long clientIdExpiresAtMs;

        Registration(String clientId, String clientSecret, String redirectUri,
                     long clientIdIssuedAtMs, long clientIdExpiresAtMs) {
            this.clientId = clientId;
            this.clientSecret = clientSecret == null ? "" : clientSecret;
            this.redirectUri = redirectUri;
            this.clientIdIssuedAtMs = clientIdIssuedAtMs;
            this.clientIdExpiresAtMs = clientIdExpiresAtMs;
        }

        public boolean isLoopbackRedirect() {
            return redirectUri != null && redirectUri.contains("127.0.0.1");
        }
    }

    /** 一次授权拿到的令牌组。 */
    public static final class TokenSet {
        public final String accessToken;
        public final String refreshToken;
        public final String tokenType;
        public final String scope;
        /** 绝对到期时刻（毫秒）；服务商没给 expires_in 时为 0 = 未知 */
        public final long expiresAtMs;

        TokenSet(String accessToken, String refreshToken, String tokenType,
                 String scope, long expiresAtMs) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken == null ? "" : refreshToken;
            this.tokenType = tokenType == null || tokenType.isEmpty() ? "Bearer" : tokenType;
            this.scope = scope == null ? "" : scope;
            this.expiresAtMs = expiresAtMs;
        }

        /** 现在还能直接用吗（含提前量）。 */
        public boolean isFresh(long nowMs) {
            if (accessToken == null || accessToken.isEmpty()) return false;
            return expiresAtMs <= 0 || nowMs < expiresAtMs - EXPIRY_SAFETY_MS;
        }

        /** 距到期不足 5 分钟（用来决定要不要顺手续期）。 */
        public boolean shouldRenew(long nowMs) {
            if (expiresAtMs <= 0) return false;
            return nowMs >= expiresAtMs - 5 * 60_000L;
        }
    }

    // ==================== 回调地址候选 ====================

    /** 自定义 scheme 回调（值取自 {@link McpConstants#OAUTH_CALLBACK_SCHEME}，与 manifest 同源）。 */
    public static final String CUSTOM_SCHEME_REDIRECT =
            McpConstants.OAUTH_CALLBACK_SCHEME + "://callback";

    /**
     * 注册用的回调候选顺序：先自定义 scheme（不用起本地服务，最省事），
     * 服务商拒了就换环回（RFC 8252，MaxCompute 只认这支）。
     */
    public static List<String> redirectCandidates(int loopbackPort) {
        List<String> out = new ArrayList<>();
        out.add(CUSTOM_SCHEME_REDIRECT);
        if (loopbackPort > 0) {
            out.add("http://127.0.0.1:" + loopbackPort + "/callback");
        }
        return out;
    }

    // ==================== 动态注册 ====================

    /**
     * 按候选回调地址逐个尝试动态注册，第一个成功的就返回。
     *
     * @param as        授权服务元数据（要 {@code registration_endpoint} 非空）
     * @param candidates 回调地址候选
     * @return 注册结果；全部被拒时抛异常，消息里带上每个地址被拒的原因
     */
    public static Registration register(McpOAuthEndpoints.AuthorizationServer as,
                                        List<String> candidates, String clientName,
                                        String scope) throws Exception {
        if (as == null || as.registrationEndpoint.isEmpty()) {
            throw new java.io.IOException("该授权服务没开动态注册（registration_endpoint 缺失），"
                    + "需要你自己填 client_id");
        }
        List<String> failures = new ArrayList<>();
        for (String redirect : candidates) {
            if (redirect == null || redirect.isEmpty()) continue;
            String body = buildRegistrationBody(clientName, redirect, scope);
            Request req = new Request.Builder()
                    .url(as.registrationEndpoint)
                    .header("Accept", "application/json")
                    .post(RequestBody.create(body, JSON))
                    .build();
            try (Response resp = httpClient().newCall(req).execute()) {
                String text = resp.body() == null ? "" : resp.body().string();
                if (resp.isSuccessful()) {
                    JSONObject o = new JSONObject(text);
                    String clientId = o.optString("client_id", "");
                    if (clientId.isEmpty()) {
                        failures.add(redirect + " → 响应里没有 client_id");
                        continue;
                    }
                    long issuedAt = o.optLong("client_id_issued_at", 0) * 1000L;
                    long expiresAt = o.optLong("expires_in", 0) > 0
                            ? System.currentTimeMillis() + o.optLong("expires_in") * 1000L
                            : (issuedAt > 0 ? issuedAt + o.optLong("expires_in", 0) * 1000L : 0);
                    AILogger.i(TAG, "OAuth 动态注册成功: client_id=*** " + mask(clientId)
                            + " redirect=" + redirect);
                    return new Registration(clientId, o.optString("client_secret", ""),
                            redirect, issuedAt, expiresAt);
                }
                String err = describeOAuthError(text, resp.code());
                failures.add(redirect + " → " + err);
                AILogger.w(TAG, "OAuth 注册被拒（" + redirect + "）: HTTP " + resp.code() + " " + err);
            } catch (Exception e) {
                failures.add(redirect + " → " + e.getMessage());
                AILogger.w(TAG, "OAuth 注册请求失败（" + redirect + "）: " + e.getMessage());
            }
        }
        throw new java.io.IOException("动态注册没成功：" + String.join("；", failures));
    }

    private static String buildRegistrationBody(String clientName, String redirect, String scope) {
        JSONObject o = new JSONObject();
        try {
            o.put("client_name", clientName == null || clientName.isEmpty()
                    ? "SmartQuiz Android" : clientName);
            o.put("redirect_uris", new java.util.ArrayList<>(Arrays.asList(redirect)));
            o.put("grant_types", new java.util.ArrayList<>(
                    Arrays.asList("authorization_code", "refresh_token")));
            o.put("response_types", new java.util.ArrayList<>(Arrays.asList("code")));
            // 公开客户端：手机里存不住密钥，也不该存
            o.put("token_endpoint_auth_method", "none");
            if (scope != null && !scope.isEmpty()) o.put("scope", scope);
        } catch (Exception e) {
            AILogger.e(TAG, "注册请求体构造失败: " + e.getMessage());
        }
        return o.toString();
    }

    // ==================== 授权 URL ====================

    /**
     * 组装 authorize URL。
     *
     * <p>{@code resource} 参数来自受保护资源文档里的 {@code resource} 字段（RFC 8707 资源指示）：
     * 授权服务用它把令牌绑到具体 MCP 端点上。没有就不带，别猜。</p>
     */
    public static String buildAuthorizeUrl(McpOAuthEndpoints.AuthorizationServer as,
                                           String clientId, String redirectUri, String state,
                                           String codeChallenge, String scope, String resource) {
        okhttp3.HttpUrl base = okhttp3.HttpUrl.parse(as.authorizationEndpoint);
        if (base == null) return "";
        okhttp3.HttpUrl.Builder b = base.newBuilder()
                .addQueryParameter("response_type", "code")
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("redirect_uri", redirectUri)
                .addQueryParameter("state", state)
                .addQueryParameter("code_challenge", codeChallenge)
                .addQueryParameter("code_challenge_method", "S256");
        if (scope != null && !scope.isEmpty()) {
            b.addQueryParameter("scope", scope);
        }
        if (resource != null && !resource.isEmpty() && resource.startsWith("http")) {
            b.addQueryParameter("resource", resource);
        }
        return b.build().toString();
    }

    // ==================== 换令牌 / 续期 ====================

    /** 授权码换令牌。 */
    public static TokenSet exchangeCode(McpOAuthEndpoints.AuthorizationServer as, String clientId,
                                        String code, String verifier, String redirectUri,
                                        String resource) throws Exception {
        FormBody.Builder form = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code == null ? "" : code)
                .add("client_id", clientId)
                .add("redirect_uri", redirectUri)
                .add("code_verifier", verifier == null ? "" : verifier);
        addResource(form, resource);
        return postToken(as, form.build(), null);
    }

    /** 用 refresh_token 续期；服务商没回新 refresh_token 时沿用旧的。 */
    public static TokenSet refresh(McpOAuthEndpoints.AuthorizationServer as, String clientId,
                                   String refreshToken, String resource,
                                   String previousRefreshToken) throws Exception {
        FormBody.Builder form = new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken == null ? "" : refreshToken)
                .add("client_id", clientId);
        addResource(form, resource);
        return postToken(as, form.build(), previousRefreshToken);
    }

    private static void addResource(FormBody.Builder form, String resource) {
        if (resource != null && !resource.isEmpty() && resource.startsWith("http")) {
            form.add("resource", resource);
        }
    }

    private static TokenSet postToken(McpOAuthEndpoints.AuthorizationServer as, FormBody form,
                                      String fallbackRefresh) throws Exception {
        if (as == null || as.tokenEndpoint.isEmpty()) {
            throw new java.io.IOException("授权服务没给 token 端点");
        }
        Request req = new Request.Builder()
                .url(as.tokenEndpoint)
                .header("Accept", "application/json")
                .post(form)
                .build();
        try (Response resp = httpClient().newCall(req).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new java.io.IOException("换令牌失败：HTTP " + resp.code()
                        + " " + describeOAuthError(text, resp.code()));
            }
            JSONObject o = new JSONObject(text);
            String access = o.optString("access_token", "");
            if (access.isEmpty()) {
                throw new java.io.IOException("换令牌响应里没有 access_token");
            }
            String refresh = o.optString("refresh_token", "");
            if (refresh.isEmpty()) refresh = fallbackRefresh == null ? "" : fallbackRefresh;
            long expiresIn = o.optLong("expires_in", 0);
            long expiresAt = expiresIn > 0 ? System.currentTimeMillis() + expiresIn * 1000L : 0;
            AILogger.i(TAG, "OAuth 令牌到手: type=" + o.optString("token_type", "Bearer")
                    + " expires_in=" + expiresIn + "s refresh=" + (!refresh.isEmpty()));
            return new TokenSet(access, refresh, o.optString("token_type", "Bearer"),
                    o.optString("scope", ""), expiresAt);
        }
    }

    /**
     * 令牌失效（401 再试仍不过）时清掉授权记录用；服务商没公布 revocation 端点就只是本地丢弃。
     */
    public static void revoke(McpOAuthEndpoints.AuthorizationServer as, String clientId,
                              String token) {
        if (as == null || as.revocationEndpoint.isEmpty() || token == null || token.isEmpty()) {
            return;
        }
        FormBody form = new FormBody.Builder()
                .add("token", token)
                .add("client_id", clientId == null ? "" : clientId)
                .build();
        Request req = new Request.Builder().url(as.revocationEndpoint).post(form).build();
        try (Response resp = httpClient().newCall(req).execute()) {
            AILogger.i(TAG, "OAuth 撤销令牌: HTTP " + resp.code());
        } catch (Exception e) {
            AILogger.w(TAG, "OAuth 撤销失败（忽略，本地已丢弃）: " + e.getMessage());
        }
    }

    // ==================== 内部 ====================

    private static OkHttpClient httpClient() {
        return NetworkUtil.getClient().newBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    /** 从 OAuth 错误响应里取 {@code error}/{@code error_description}，取不到就回原文片段。 */
    static String describeOAuthError(String body, int httpStatus) {
        if (body == null || body.trim().isEmpty()) return "HTTP " + httpStatus;
        try {
            JSONObject o = new JSONObject(body);
            String err = o.optString("error", "");
            String desc = o.optString("error_description", "");
            if (!err.isEmpty() && !desc.isEmpty()) return err + ": " + desc;
            if (!err.isEmpty()) return err;
            if (!desc.isEmpty()) return desc;
        } catch (Exception ignored) {
            // 不是 JSON（网关页/HTML），按原文截断给出
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 160 ? flat.substring(0, 160) + "..." : flat;
    }

    /** client_id 也可能是敏感标识，日志只留前后各 4 位。 */
    private static String mask(String value) {
        if (value == null || value.length() <= 8) return "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    /** 是否"回调地址被拒"——决定要不要换下一个候选地址。 */
    public static boolean isRedirectRejected(String errorMessage) {
        if (errorMessage == null) return false;
        // 服务商写法不一：invalid_redirect_uri（错误码）/ "Redirect URI ..."（人话）都得认
        String e = errorMessage.toLowerCase(Locale.ROOT).replace('_', ' ');
        return e.contains("redirect uri");
    }
}
