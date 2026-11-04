package com.oilquiz.app.ai.agent.online.mcp;

import com.oilquiz.app.ai.util.NetworkUtil;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * MCP 的 OAuth 授权发现层（MCP Authorization 规范那套元数据链）。
 *
 * <p>链路是标准三段：</p>
 * <ol>
 *   <li>连 MCP 端点被 {@code 401} 挡下，从 {@code WWW-Authenticate: Bearer resource_metadata="..."}
 *       拿到受保护资源文档地址；</li>
 *   <li>读 {@code /.well-known/oauth-protected-resource} 拿授权服务（authorization server）地址与作用域；</li>
 *   <li>读该授权服务的 {@code /.well-known/oauth-authorization-server} 拿
 *       authorize/token/register/revocation 端点、PKCE 方法与支持的 grant type。</li>
 * </ol>
 *
 * <p>为什么要走完这三段而不是把端点写死：服务商说改就改。本轮实测（2026-11-04）
 * 两家形态就不同——阿里云 OpenAPI MCP 在 401 里给了 {@code resource_metadata}，
 * 授权服务是 {@code signin.aliyun.com/oauth2/v1/auth} + {@code oauth.aliyun.com/v1/token}
 * + 自家 {@code /register}；MaxCompute 的 401 只给 {@code scope}，但同源就公布了
 * {@code /.well-known/oauth-authorization-server}。写死任一家都会在对方调整后哑掉。</p>
 *
 * <p>解析函数都是纯静态（喂字符串就能测），只有 {@link #discover} 走网络。</p>
 */
public final class McpOAuthEndpoints {

    private static final String TAG = "McpOAuth";
    private static final String WELL_KNOWN_PR = "/.well-known/oauth-protected-resource";
    private static final String WELL_KNOWN_AS = "/.well-known/oauth-authorization-server";
    private static final String WELL_KNOWN_OIDC = "/.well-known/openid-configuration";

    private McpOAuthEndpoints() {}

    // ==================== 数据类 ====================

    /** 401 挑战里能读到的东西。 */
    public static final class Challenge {
        public final int httpStatus;
        /** {@code resource_metadata} 参数指向的受保护资源文档；没给就是空串 */
        public final String resourceMetadataUrl;
        /** {@code scope} 参数；没给就是空串 */
        public final String scope;
        public final String rawHeader;

        Challenge(int httpStatus, String resourceMetadataUrl, String scope, String rawHeader) {
            this.httpStatus = httpStatus;
            this.resourceMetadataUrl = resourceMetadataUrl == null ? "" : resourceMetadataUrl;
            this.scope = scope == null ? "" : scope;
            this.rawHeader = rawHeader == null ? "" : rawHeader;
        }

        public boolean hasResourceMetadata() {
            return this.resourceMetadataUrl.startsWith("http");
        }
    }

    /** 受保护资源文档。 */
    public static final class ProtectedResource {
        public final String resource;
        /** 授权服务标识或元数据文档地址（两种写法规范里都出现过，发现阶段逐个试） */
        public final List<String> authorizationCandidates;
        public final List<String> scopesSupported;

        ProtectedResource(String resource, List<String> candidates, List<String> scopes) {
            this.resource = resource;
            this.authorizationCandidates = Collections.unmodifiableList(candidates);
            this.scopesSupported = Collections.unmodifiableList(scopes);
        }
    }

    /** 授权服务元数据。 */
    public static final class AuthorizationServer {
        public final String issuer;
        public final String authorizationEndpoint;
        public final String tokenEndpoint;
        /** 空串 = 不支持动态注册，得用户自己填 client_id */
        public final String registrationEndpoint;
        public final String revocationEndpoint;
        public final List<String> codeChallengeMethods;
        public final List<String> grantTypes;
        public final List<String> responseTypes;
        public final List<String> tokenEndpointAuthMethods;

        AuthorizationServer(String issuer, String authorizationEndpoint, String tokenEndpoint,
                            String registrationEndpoint, String revocationEndpoint,
                            List<String> codeChallengeMethods, List<String> grantTypes,
                            List<String> responseTypes, List<String> tokenEndpointAuthMethods) {
            this.issuer = nullToEmpty(issuer);
            this.authorizationEndpoint = nullToEmpty(authorizationEndpoint);
            this.tokenEndpoint = nullToEmpty(tokenEndpoint);
            this.registrationEndpoint = nullToEmpty(registrationEndpoint);
            this.revocationEndpoint = nullToEmpty(revocationEndpoint);
            this.codeChallengeMethods = immutable(codeChallengeMethods);
            this.grantTypes = immutable(grantTypes);
            this.responseTypes = immutable(responseTypes);
            this.tokenEndpointAuthMethods = immutable(tokenEndpointAuthMethods);
        }

        public boolean supportsS256() {
            return McpPkce.supportsS256(codeChallengeMethods);
        }

        /** 元数据齐到什么程度才敢真去授权。 */
        public boolean isUsable() {
            return authorizationEndpoint.startsWith("https://") && tokenEndpoint.startsWith("https://");
        }

        public boolean supportsRefresh() {
            return containsIgnoreCase(grantTypes, "refresh_token");
        }

        /** 公开客户端（无 client_secret）是否被接受——手机必须走这一支。 */
        public boolean acceptsPublicClient() {
            return tokenEndpointAuthMethods.isEmpty()
                    || containsIgnoreCase(tokenEndpointAuthMethods, "none");
        }

        private static List<String> immutable(List<String> in) {
            return in == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(in));
        }
    }

    /** 一次发现的结果：资源 + 授权服务 + 建议作用域。 */
    public static final class Plan {
        public final ProtectedResource protectedResource;
        public final AuthorizationServer server;
        public final List<String> scopes;

        Plan(ProtectedResource protectedResource, AuthorizationServer server, List<String> scopes) {
            this.protectedResource = protectedResource;
            this.server = server;
            this.scopes = Collections.unmodifiableList(new ArrayList<>(scopes));
        }

        public String scopeText() {
            return String.join(" ", scopes);
        }
    }

    // ==================== 纯解析（可 JVM 单测） ====================

    /**
     * 解析 {@code WWW-Authenticate}。
     *
     * <p>规范形态是 {@code Bearer resource_metadata="https://..."}（MCP 授权规范用它指出
     * 受保护资源文档）；也有服务端只写 {@code Bearer scope="..."}（MaxCompute 就这样）。
     * 非 Bearer（Basic/Negotiate）不当 OAuth 处理。</p>
     */
    public static Challenge parseChallenge(int httpStatus, String header) {
        String raw = header == null ? "" : header.trim();
        String scheme = raw.isEmpty() ? "" : raw.split("\\s", 2)[0];
        if (!"bearer".equalsIgnoreCase(scheme)) {
            return new Challenge(httpStatus, "", "", raw);
        }
        return new Challenge(httpStatus, extractParam(raw, "resource_metadata"),
                extractParam(raw, "scope"), raw);
    }

    /**
     * 取 {@code key="value"} 或 {@code key=value} 形式参数；找不到返回空串。
     *
     * <p>不加引号的参数值按 RFC 6750 是 permit-only 字符集，服务商常把带空格的 scope
     * 写成 {@code scope=a%20b}——不还原就会把 "a%20b" 当成一个作用域名发给授权服务。</p>
     */
    static String extractParam(String header, String key) {
        if (header == null) return "";
        String lower = header.toLowerCase(Locale.ROOT);
        String needle = key.toLowerCase(Locale.ROOT) + "=";
        int i = lower.indexOf(needle);
        if (i < 0) return "";
        int v = i + needle.length();
        if (v >= header.length()) return "";
        String raw;
        if (header.charAt(v) == '"') {
            int end = header.indexOf('"', v + 1);
            raw = end < 0 ? "" : header.substring(v + 1, end).trim();
        } else {
            int end = v;
            while (end < header.length() && header.charAt(end) != ',' && header.charAt(end) != ' ') end++;
            raw = header.substring(v, end).trim();
        }
        return percentDecode(raw);
    }

    private static String percentDecode(String value) {
        if (value.isEmpty() || value.indexOf('%') < 0) return value;
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;   // 解不动就原样返回，别把授权链卡在解析一个 header 参数上
        }
    }

    /** 取 scheme://host[:port]；默认端口不带（服务商元数据里的 issuer 都是这种形式）。 */
    public static String originOf(String url) {
        if (url == null) return "";
        try {
            java.net.URI uri = new java.net.URI(url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) return "";
            StringBuilder sb = new StringBuilder();
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            sb.append(scheme).append("://").append(uri.getHost());
            int port = uri.getPort();
            if (port > 0 && !isDefaultPort(scheme, port)) sb.append(':').append(port);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
    }

    /** 解析受保护资源文档。{@code authorization_servers} 与 {@code authorization_server_url} 都要读。 */
    public static ProtectedResource parseProtectedResource(String json) {
        List<String> candidates = new ArrayList<>();
        List<String> scopes = new ArrayList<>();
        String resource = "";
        try {
            JSONObject o = new JSONObject(json);
            resource = o.optString("resource", "");
            collect(o.optJSONArray("authorization_server_url"), candidates);
            collect(o.optJSONArray("authorization_servers"), candidates);
            collect(o.optJSONArray("scopes_supported"), scopes);
        } catch (Exception e) {
            AILogger.w(TAG, "受保护资源文档解析失败: " + e.getMessage());
        }
        return new ProtectedResource(resource, dedupe(candidates), dedupe(scopes));
    }

    /** 解析授权服务元数据（{@code oauth-authorization-server} 与 {@code openid-configuration} 同字段名）。 */
    public static AuthorizationServer parseAuthorizationServer(String json) {
        try {
            JSONObject o = new JSONObject(json);
            return new AuthorizationServer(
                    o.optString("issuer", ""),
                    o.optString("authorization_endpoint", ""),
                    o.optString("token_endpoint", ""),
                    o.optString("registration_endpoint", ""),
                    o.optString("revocation_endpoint", ""),
                    toStringList(o.optJSONArray("code_challenge_methods_supported")),
                    toStringList(o.optJSONArray("grant_types_supported")),
                    toStringList(o.optJSONArray("response_types_supported")),
                    toStringList(o.optJSONArray("token_endpoint_auth_methods_supported")));
        } catch (Exception e) {
            AILogger.w(TAG, "授权服务元数据解析失败: " + e.getMessage());
            return new AuthorizationServer("", "", "", "", "",
                    new ArrayList<String>(), new ArrayList<String>(),
                    new ArrayList<String>(), new ArrayList<String>());
        }
    }

    /** 从 MCP 端点推出同源授权服务元数据地址（MaxCompute 走这支：401 只给 scope）。 */
    public static List<String> sameOriginMetadataUrls(String mcpEndpointUrl) {
        List<String> out = new ArrayList<>();
        String origin = originOf(mcpEndpointUrl);
        if (origin.isEmpty()) return out;
        out.add(origin + WELL_KNOWN_AS);
        out.add(origin + WELL_KNOWN_OIDC);
        return out;
    }

    /**
     * 把元数据文档地址补成可请求的 URL。
     *
     * <p>服务商两种给法都见过：直接给文档 URL（{@code .../.well-known/oauth-authorization-server}）
     * 或只给授权服务标识（{@code https://host}）。后者必须补路径，否则请求打到站点根上拿 404。</p>
    */
    public static List<String> authorizationMetadataUrls(String candidate) {
        List<String> out = new ArrayList<>();
        if (candidate == null || candidate.trim().isEmpty()) return out;
        String c = candidate.trim();
        if (c.contains(WELL_KNOWN_AS) || c.contains(WELL_KNOWN_OIDC)) {
            out.add(c);
            return out;
        }
        String origin = originOf(c);
        if (origin.isEmpty()) return out;
        out.add(origin + WELL_KNOWN_AS);
        out.add(origin + WELL_KNOWN_OIDC);
        return out;
    }

    // ==================== 发现（走网络，必须后台线程） ====================

    /**
     * 走完三段发现。
     *
     * @param endpointUrl MCP 端点
     * @param challenge   401 里解析出的挑战（可为 null，表示没拿到 header，直接按同源试）
     * @throws java.io.IOException 拿不到可用授权服务元数据时，消息里写清试过哪些地址
     */
    public static Plan discover(String endpointUrl, Challenge challenge) throws Exception {
        List<String> tried = new ArrayList<>();
        ProtectedResource pr = null;

        if (challenge != null && challenge.hasResourceMetadata()) {
            String doc = getJson(challenge.resourceMetadataUrl, tried);
            if (doc != null) pr = parseProtectedResource(doc);
        }
        if (pr == null || pr.authorizationCandidates.isEmpty()) {
            // 没给 resource_metadata（或那文档没列授权服务）：按同源规范路径再试一次
            String path = pathOf(endpointUrl);
            String origin = originOf(endpointUrl);
            List<String> prUrls = new ArrayList<>();
            if (!origin.isEmpty()) {
                if (!path.isEmpty() && !"/".equals(path)) {
                    prUrls.add(origin + WELL_KNOWN_PR + path);
                }
                prUrls.add(origin + WELL_KNOWN_PR);
            }
            for (String url : prUrls) {
                String doc = getJson(url, tried);
                if (doc == null) continue;
                ProtectedResource maybe = parseProtectedResource(doc);
                if (!maybe.authorizationCandidates.isEmpty()) {
                    pr = maybe;
                    break;
                }
                if (pr == null) pr = maybe;
            }
        }

        List<String> scopes = new ArrayList<>();
        if (pr != null && !pr.scopesSupported.isEmpty()) {
            scopes.addAll(pr.scopesSupported);
        }
        if (scopes.isEmpty() && challenge != null && !challenge.scope.isEmpty()) {
            for (String s : challenge.scope.split("\\s+")) {
                if (!s.trim().isEmpty()) scopes.add(s.trim());
            }
        }

        List<String> asCandidates = new ArrayList<>();
        if (pr != null) asCandidates.addAll(pr.authorizationCandidates);
        asCandidates.addAll(sameOriginMetadataUrls(endpointUrl));

        for (String candidate : dedupe(asCandidates)) {
            for (String metaUrl : authorizationMetadataUrls(candidate)) {
                String doc = getJson(metaUrl, tried);
                if (doc == null) continue;
                AuthorizationServer as = parseAuthorizationServer(doc);
                if (!as.isUsable()) continue;
                AILogger.i(TAG, "OAuth 发现成功: as=" + as.issuer + " register="
                        + (as.registrationEndpoint.isEmpty() ? "无" : as.registrationEndpoint)
                        + "（试过 " + tried.size() + " 个地址）");
                return new Plan(pr == null ? new ProtectedResource(endpointUrl,
                                Collections.<String>emptyList(), Collections.<String>emptyList())
                                : pr, as, scopes);
            }
        }
        throw new java.io.IOException("没找到可用的授权服务元数据（试过 " + tried.size()
                + " 个地址：" + preview(tried) + "）");
    }

    /** 同源路径（用于把 protected-resource 文档地址补成带路径的形式）；解析失败返回空串。 */
    private static String pathOf(String url) {
        if (url == null) return "";
        try {
            java.net.URI uri = new java.net.URI(url.trim());
            String p = uri.getPath();
            return p == null ? "" : p;
        } catch (Exception e) {
            return "";
        }
    }

    private static OkHttpClient discoveryClient() {
        return NetworkUtil.getClient().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    /** GET 一段元数据文档；非 2xx / 非 JSON / 异常都返回 null 并把地址记进 tried。 */
    private static String getJson(String url, List<String> tried) {
        if (url == null || url.isEmpty()) return null;
        tried.add(url);
        Request req = new Request.Builder().url(url)
                .header("Accept", "application/json, text/event-stream")
                .get().build();
        try (Response resp = discoveryClient().newCall(req).execute()) {
            if (!resp.isSuccessful()) return null;
            String body = resp.body() == null ? "" : resp.body().string();
            if (body.isEmpty() || body.trim().charAt(0) != '{') return null;
            return body;
        } catch (Exception e) {
            AILogger.d(TAG, "元数据地址不可用 " + url + ": " + e.getMessage());
            return null;
        }
    }

    // ==================== 小工具 ====================

    private static void collect(JSONArray arr, List<String> into) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String v = arr.optString(i, "").trim();
            if (!v.isEmpty()) into.add(v);
        }
    }

    private static List<String> toStringList(JSONArray arr) {
        List<String> out = new ArrayList<>();
        collect(arr, out);
        return out;
    }

    private static List<String> dedupe(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (s != null && !s.trim().isEmpty() && !out.contains(s.trim())) out.add(s.trim());
        }
        return out;
    }

    private static boolean containsIgnoreCase(List<String> list, String needle) {
        for (String s : list) {
            if (s != null && needle.equalsIgnoreCase(s.trim())) return true;
        }
        return false;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String preview(List<String> urls) {
        int n = Math.min(urls.size(), 4);
        return String.join(", ", urls.subList(0, n)) + (urls.size() > n ? " …" : "");
    }
}
