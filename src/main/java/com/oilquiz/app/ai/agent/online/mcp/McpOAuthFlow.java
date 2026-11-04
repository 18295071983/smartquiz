package com.oilquiz.app.ai.agent.online.mcp;

import android.content.Context;

import com.oilquiz.app.util.AILogger;

/**
 * MCP 的 OAuth 授权编排：发现 → 动态注册 → 浏览器授权 → 换令牌 → 落盘。
 *
 * <p>整条链路都在后台线程跑（{@link #authorize} 会阻塞到浏览器回传为止），
 * 只有"打开浏览器"这一步通过 {@link Ui} 回调交给调用方的 Activity。</p>
 *
 * <p>几个刻意的设计：</p>
 * <ul>
 *   <li><b>已有 client_id 就复用</b>：动态注册出来的标识在服务商侧存了一年
 *       （阿里云 OpenAPI MCP 回的 {@code expires_at} 实测是 +1 年），每次授权都新注册
 *       会在对方账号下堆一堆幽灵客户端。</li>
 *   <li><b>回调地址按候选顺序试</b>：先自定义 scheme，被拒再绑环回端口——
 *       这两支的差别是服务商定的，不是一种风格。</li>
 *   <li><b>令牌没落盘就不算授权成功</b>：{@link McpTokenStore#save} 返回 false 时
 *       直接把结果报成失败，否则内存里有令牌、重启后没有，表现为"昨天还能用"。</li>
 * </ul>
 */
public final class McpOAuthFlow {

    private static final String TAG = "McpOAuthFlow";
    /** 等用户在浏览器里完成登录/授权的上限：手机上手输密码 + 短信验证，5 分钟够但不宜更长 */
    private static final long BROWSER_TIMEOUT_MS = 5 * 60_000L;

    private McpOAuthFlow() {}

    /** 交给 UI 层的两个动作。 */
    public interface Ui {
        /** 用系统浏览器打开授权页（必须在主线程调用）。 */
        void openBrowser(String authorizeUrl) throws Exception;

        /** 阶段性提示（市场向导用它更新状态行）。 */
        void stage(String text);
    }

    /** 一次授权/续期的结果。 */
    public static final class Outcome {
        public final boolean ok;
        public final String message;
        public final McpOAuthRecord record;

        private Outcome(boolean ok, String message, McpOAuthRecord record) {
            this.ok = ok;
            this.message = message == null ? "" : message;
            this.record = record;
        }

        static Outcome success(String message, McpOAuthRecord record) {
            return new Outcome(true, message, record);
        }

        static Outcome failure(String message) {
            return new Outcome(false, message, null);
        }
    }

    /** 编排之外（比如 McpManager 发现服务已被删除）也要能构造同样形状的结果。 */
    public static Outcome authorizeFailure(String message) {
        return Outcome.failure(message);
    }

    // ==================== 完整授权 ====================

    /**
     * 走一次浏览器授权。会阻塞当前线程直到回传、超时或出错。
     *
     * @param challenge 401 里解析出的挑战（可为 null，没有就按同源发现）
     */
    public static Outcome authorize(Context context, McpServerConfig cfg,
                                    McpOAuthEndpoints.Challenge challenge, Ui ui) {
        if (cfg == null) return Outcome.failure("配置为空");
        McpTokenStore store = new McpTokenStore(context);
        McpOAuthRecord existing = store.get(cfg.id);

        McpLoopbackReceiver receiver = null;
        String state = null;
        try {
            ui.stage("正在获取授权服务信息…");
            McpOAuthEndpoints.Plan plan =
                    McpOAuthEndpoints.discover(cfg.url, challenge);
            if (!plan.server.supportsS256()) {
                return Outcome.failure("该授权服务不支持 PKCE S256，公开客户端不能安全使用它");
            }
            if (!plan.server.acceptsPublicClient()) {
                return Outcome.failure("该授权服务要求 client 密钥（token_endpoint_auth_method 不含 none），"
                        + "手机里存不住密钥");
            }

            Registration registered = registerClient(cfg, existing, plan, ui);
            McpOAuthClient.Registration registration = registered.client;
            receiver = registered.loopback;
            if (registration == null) {
                return Outcome.failure("没能拿到 client_id："
                        + (plan.server.registrationEndpoint.isEmpty()
                        ? "该授权服务没开动态注册" : "动态注册被服务商拒绝"));
            }
            if (registration.isLoopbackRedirect() && receiver == null) {
                return Outcome.failure("环回回调端口没绑上，无法接收授权码");
            }

            ui.stage("请在浏览器里完成登录并同意授权（最多等 5 分钟）");
            String verifier = McpPkce.newVerifier();
            state = McpPkce.newState();
            McpOAuthPending.Waiter waiter = new McpOAuthPending.Waiter(state, cfg.id, verifier,
                    registration.clientId, registration.redirectUri, plan.protectedResource.resource,
                    plan.server);
            if (!McpOAuthPending.begin(waiter)) {
                return Outcome.failure("已经有一条授权在等浏览器回传，先把它做完或取消");
            }
            if (receiver != null) receiver.start(state);

            String authorizeUrl = McpOAuthClient.buildAuthorizeUrl(plan.server,
                    registration.clientId, registration.redirectUri, state,
                    McpPkce.challengeOf(verifier),
                    plan.scopes.isEmpty() ? challengeScope(challenge) : String.join(" ", plan.scopes),
                    plan.protectedResource.resource);
            if (authorizeUrl.isEmpty()) {
                return Outcome.failure("授权地址拼不出来（authorization_endpoint 非法）");
            }
            ui.openBrowser(authorizeUrl);

            McpOAuthPending.Result result = McpOAuthPending.awaitResult(state, BROWSER_TIMEOUT_MS);
            if (result == null) {
                return Outcome.failure("等回传超时（5 分钟）。"
                        + "如果浏览器里已经点了同意，多半是回调地址被系统浏览器拦下了");
            }
            if (result.hasError()) {
                return Outcome.failure(describeBrowserError(result));
            }

            McpOAuthClient.TokenSet tokens = McpOAuthClient.exchangeCode(plan.server,
                    registration.clientId, result.code, verifier, registration.redirectUri,
                    plan.protectedResource.resource);
            McpOAuthRecord record = new McpOAuthRecord(cfg.id, plan.server.issuer,
                    plan.server.authorizationEndpoint, plan.server.tokenEndpoint,
                    plan.server.registrationEndpoint, plan.server.revocationEndpoint,
                    registration.clientId, registration.redirectUri, tokens.scope,
                    tokens.accessToken, tokens.refreshToken, tokens.expiresAtMs,
                    System.currentTimeMillis());
            if (!store.save(record)) {
                return Outcome.failure("令牌没能落盘（Keystore/存储失败），这次授权不算完成");
            }
            AILogger.audit(TAG, "OAuth 授权完成: " + cfg.id + " scope=" + tokens.scope
                    + " 可续期=" + !tokens.refreshToken.isEmpty());
            return Outcome.success("授权成功" + (tokens.scope.isEmpty() ? "" : "（作用域 " + tokens.scope + "）"),
                    record);
        } catch (Exception e) {
            AILogger.e(TAG, "授权失败 (" + cfg.id + "): " + e.getMessage(), e);
            return Outcome.failure(shortMessage(e));
        } finally {
            if (state != null) McpOAuthPending.finish(state);
            if (receiver != null) receiver.close();
        }
    }

    /** 动态注册阶段的产物：拿到的 client_id，以及（若走环回回调）已绑定的那条监听。 */
    private static final class Registration {
        final McpOAuthClient.Registration client;
        final McpLoopbackReceiver loopback;

        Registration(McpOAuthClient.Registration client, McpLoopbackReceiver loopback) {
            this.client = client;
            this.loopback = loopback;
        }
    }

    /**
     * 复用已有 client_id，或按候选回调地址动态注册。
     *
     * <p>环回监听一旦绑定就随结果带回，授权阶段用的是<b>同一条</b>监听——
     * 注册时申报的端口和真正收码的端口必须一致，所以不能"先取号再重开"。</p>
     */
    private static Registration registerClient(McpServerConfig cfg, McpOAuthRecord existing,
                                               McpOAuthEndpoints.Plan plan, Ui ui) {
        if (existing != null && existing.hasClient()) {
            AILogger.i(TAG, "复用已登记的 client_id: " + cfg.id);
            return new Registration(new McpOAuthClient.Registration(
                    existing.clientId, "", existing.redirectUri, 0, 0), null);
        }
        if (plan.server.registrationEndpoint.isEmpty()) {
            return new Registration(null, null);
        }
        try {
            ui.stage("正在向服务商登记本 App（动态注册）…");
            McpOAuthClient.Registration client = McpOAuthClient.register(plan.server,
                    McpOAuthClient.redirectCandidates(0), cfg.name, scopeOf(plan, existing));
            return new Registration(client, null);
        } catch (Exception first) {
            String msg = first.getMessage() == null ? "" : first.getMessage();
            // MaxCompute 只认 RFC 8252 环回；其余服务商一般是回调地址格式没被接受。
            // 两种都换环回再试一次，第二支也不行就报失败，不死缠服务商的注册接口
            boolean tryLoopback = McpOAuthClient.isRedirectRejected(msg)
                    || plan.server.issuer.contains("maxcompute");
            if (!tryLoopback) {
                AILogger.w(TAG, "动态注册失败: " + msg);
                return new Registration(null, null);
            }
            McpLoopbackReceiver receiver = McpLoopbackReceiver.open();
            if (receiver == null) return new Registration(null, null);
            try {
                ui.stage("服务商不接自定义回调，改用本机环回端口重登记…");
                McpOAuthClient.Registration client = McpOAuthClient.register(plan.server,
                        McpOAuthClient.redirectCandidates(receiver.port()), cfg.name,
                        scopeOf(plan, existing));
                return new Registration(client, receiver);
            } catch (Exception second) {
                AILogger.e(TAG, "环回回调注册也失败: " + second.getMessage());
                receiver.close();
                return new Registration(null, null);
            }
        }
    }

    private static String scopeOf(McpOAuthEndpoints.Plan plan, McpOAuthRecord existing) {
        if (!plan.scopes.isEmpty()) return String.join(" ", plan.scopes);
        if (existing != null && !existing.scope.isEmpty()) return existing.scope;
        return "";
    }

    private static String challengeScope(McpOAuthEndpoints.Challenge challenge) {
        return challenge == null ? "" : challenge.scope;
    }

    private static String describeBrowserError(McpOAuthPending.Result result) {
        String e = result.error.toLowerCase(java.util.Locale.ROOT);
        if (e.contains("access_denied") || e.contains("consent_required")) {
            return "你在浏览器里取消了授权（" + result.error + "），需要重新点一次同意";
        }
        if (e.contains("login_required") || e.contains("interaction_required")) {
            return "服务商要求先登录阿里云账号（" + result.error + "），登录后重试";
        }
        return "浏览器回传了错误：" + result.error
                + (result.errorDescription.isEmpty() ? "" : "（" + result.errorDescription + "）");
    }

    // ==================== 静默续期 / 取令牌 ====================

    /**
     * 连接前要用的令牌：有效直接给；临期或有 401 史则续期；拿不到返回空串（调用方按未授权处理）。
     */
    public static String accessTokenFor(Context context, McpServerConfig cfg) {
        McpOAuthRecord record = new McpTokenStore(context).get(cfg.id);
        if (record == null || !record.hasAccessToken()) return "";
        long now = System.currentTimeMillis();
        if (record.isAccessTokenFresh(now) && !record.shouldRefresh(now)) {
            return record.accessToken;
        }
        if (record.refreshToken.isEmpty()) return record.accessToken;
        Outcome refreshed = refresh(context, cfg, record);
        return refreshed.ok && refreshed.record != null ? refreshed.record.accessToken : record.accessToken;
    }

    /** 用 refresh_token 续期（不发浏览器请求，可在同步线程里跑）。 */
    public static Outcome refresh(Context context, McpServerConfig cfg, McpOAuthRecord record) {
        if (record == null || record.refreshToken.isEmpty() || record.tokenEndpoint.isEmpty()) {
            return Outcome.failure("没有可续期的 refresh_token");
        }
        try {
            McpOAuthClient.TokenSet tokens = McpOAuthClient.refresh(record.asAuthorizationServer(),
                    record.clientId, record.refreshToken, resourceOf(cfg, record), record.refreshToken);
            McpOAuthRecord next = record.withTokens(tokens.accessToken, tokens.refreshToken,
                    tokens.expiresAtMs, tokens.scope);
            if (!new McpTokenStore(context).save(next)) {
                return Outcome.failure("续期令牌没能落盘");
            }
            AILogger.i(TAG, "OAuth 续期成功: " + cfg.id);
            return Outcome.success("已续期", next);
        } catch (Exception e) {
            AILogger.w(TAG, "OAuth 续期失败 (" + cfg.id + "): " + e.getMessage());
            return Outcome.failure(shortMessage(e));
        }
    }

    /** 令牌撤销：能撤销就撤销，然后清本地记录。 */
    public static void clearAuthorization(Context context, String serverId) {
        McpTokenStore store = new McpTokenStore(context);
        McpOAuthRecord record = store.get(serverId);
        if (record != null) {
            McpOAuthClient.revoke(record.asAuthorizationServer(), record.clientId, record.accessToken);
        }
        store.remove(serverId);
    }

    private static String resourceOf(McpServerConfig cfg, McpOAuthRecord record) {
        // 授权时绑定的资源就是那个 MCP 端点；端点后来被改了就不带，让服务商按 client 判定
        return cfg != null && cfg.url != null && cfg.url.startsWith("http") ? cfg.url : "";
    }

    private static String shortMessage(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }
}
