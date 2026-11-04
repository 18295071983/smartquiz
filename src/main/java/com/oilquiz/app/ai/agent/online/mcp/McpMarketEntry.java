package com.oilquiz.app.ai.agent.online.mcp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * MCP 市场条目：一条外部 MCP 服务「在这台手机上能不能用起来」的完整说明。
 *
 * <p>数据来自 {@code assets/mcp_market_catalog.json}（见 {@link McpMarketCatalog}），
 * 本类只负责结构与解析，不放清单内容。</p>
 *
 * <p><b>为什么用能力位而不是「可直连/需开通」两档</b>：旧二分把三类完全不同的情况
 * 混成了同一种「看起来能加」——端点固定但需要浏览器 OAuth 的（MaxCompute Remote）、
 * 端点其实是别人账号下发的一条分享链接的（OpenAPI MCP）、以及官方只有本地 npx/stdio 包的
 * （CloudBase、腾讯云广场多数条目）。加进来必然连不上，比不提供更糟。
 * 现在按「能不能在这台机器上连上」分五档，只有前两档给安装入口。</p>
 */
public final class McpMarketEntry {

    /** 接入能力位。 */
    public enum Capability {
        /** 端点固定、远程可达，填好密钥即可连接 */
        DIRECT_REMOTE,
        /** 远程端点存在，但要先在厂商控制台开通/替换占位符 */
        PROVISIONED_REMOTE,
        /** 只有网页广场，端点要在详情页现取，App 内无法预置 */
        PORTAL_ONLY,
        /** 官方只有本地 stdio/npx 分发，Android 端起不来 */
        LOCAL_ONLY,
        /** 端点固定，但要先在浏览器里授权一次（App 已支持这条链路） */
        NEEDS_OAUTH;

        public static Capability fromWire(String raw) {
            if (raw == null) return null;
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /**
         * 是否允许走安装向导。
         *
         * <p>{@link #NEEDS_OAUTH} 也算可安装：它不是"装不了"，而是"装的过程中要多点一次浏览器授权"，
         * 授权链（元数据发现 + 动态注册 + PKCE）已经接通。只有真正拿不到端点或跑不起进程的
         * 那三档才不给入口。</p>
         */
        public boolean isConnectable() {
            return this == DIRECT_REMOTE || this == PROVISIONED_REMOTE || this == NEEDS_OAUTH;
        }
    }

    /** 鉴权方式（决定向导里提示什么、要不要选密钥引用）。 */
    public enum Auth {
        /** 无需凭证 */
        NONE,
        /** 密钥写在 URL query 里（腾讯位置服务、高德） */
        QUERY_KEY,
        /** Bearer 令牌（阿里云百炼等） */
        BEARER_TOKEN,
        /** 浏览器 OAuth，本 App 不支持 */
        OAUTH,
        /** OAuth 或静态凭证二选一，App 只能用静态凭证那一支 */
        MIXED,
        /** 随服务而定 */
        UNKNOWN;

        /** 宽松解析：认不出的都当 {@link #UNKNOWN}——运行时不能因一条数据写错就崩。 */
        public static Auth fromWire(String raw) {
            Auth exact = parseExact(raw);
            return exact != null ? exact : UNKNOWN;
        }

        /**
         * 严格解析：只有枚举名本身才算数，空值/错值返回 {@code null}。
         *
         * <p>清单校核必须用它：把 {@code "MAGIC"} 和 {@code "UNKNOWN"} 都当 UNKNOWN 的话，
         * "auth 写错档"和"auth 明说了随服务而定"就分不出来，数据问题会被静默吞掉。</p>
         */
        public static Auth parseExact(String raw) {
            if (raw == null || raw.trim().isEmpty()) return null;
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** 自检结论（内置清单里记录的是一次真实协议探测的结果，不是"官方说可以"）。 */
    public static final class Probe {
        public final String checkedAt;
        /** {@code ok} / {@code auth_required} / {@code unverified} */
        public final String result;
        public final String evidence;

        Probe(String checkedAt, String result, String evidence) {
            this.checkedAt = checkedAt;
            this.result = result;
            this.evidence = evidence;
        }

        public boolean isHandshakeOk() {
            return "ok".equals(result);
        }
    }

    /** 一个可用端点（同服务常同时给 Streamable HTTP 与 SSE 两条）。 */
    public static final class Endpoint {
        public final McpServerConfig.Transport transport;
        public final String url;
        public final String note;

        Endpoint(McpServerConfig.Transport transport, String url, String note) {
            this.transport = transport;
            this.url = url;
            this.note = note;
        }
    }

    public final String id;
    public final String title;
    public final String provider;
    public final String providerKey;
    public final String description;
    public final Capability capability;
    public final Auth auth;
    public final String authHint;
    /**
     * 端点里代表<b>密钥</b>的占位符名（如 {@code key}）。
     *
     * <p>与 {@code {mcpCode}} 那类"资源编号"占位符区分开：编号要用户从控制台抄、不是秘密，
     * 直接写在端点里没问题；密钥则必须单独填一次并加密落盘，端点里只留 {@code {key}}。
     * 空串表示这条服务没有"密钥在 URL 里"的形态。</p>
     */
    public final String secretPlaceholder;
    /**
     * 计费/配额提示（如"按调用次数计费"）。
     *
     * <p>外部服务是按次花钱的，而模型一次自主调用用户是看不见的——不写清这一点，
     * "Key 被盗刷/配额被吃光"只能事后发现。空串表示厂商文档没写明或免费。</p>
     */
    public final String costNote;
    public final List<Endpoint> endpoints;
    public final List<String> guide;
    /** 非可连接档说明"为什么现在加不了"；可连接档为空 */
    public final String blockedReason;
    public final String docsUrl;
    public final String consoleUrl;
    public final List<String> tags;
    /** 清单条目最后核对文档的日期 */
    public final String verifiedAt;
    public final Probe probe;

    private McpMarketEntry(String id, String title, String provider, String providerKey,
                           String description, Capability capability, Auth auth,
                           String authHint, String secretPlaceholder, String costNote,
                           List<Endpoint> endpoints, List<String> guide,
                           String blockedReason, String docsUrl, String consoleUrl,
                           List<String> tags, String verifiedAt, Probe probe) {
        this.id = id;
        this.title = title;
        this.provider = provider;
        this.providerKey = providerKey;
        this.description = description;
        this.capability = capability;
        this.auth = auth;
        this.authHint = authHint;
        this.secretPlaceholder = secretPlaceholder;
        this.costNote = costNote;
        this.endpoints = Collections.unmodifiableList(endpoints);
        this.guide = Collections.unmodifiableList(guide);
        this.blockedReason = blockedReason;
        this.docsUrl = docsUrl;
        this.consoleUrl = consoleUrl;
        this.tags = Collections.unmodifiableList(tags);
        this.verifiedAt = verifiedAt;
        this.probe = probe;
    }

    public Endpoint defaultEndpoint() {
        return endpoints.isEmpty() ? null : endpoints.get(0);
    }

    public String guideText() {
        return String.join("\n", guide);
    }

    /** 端点里是否还有 {@code {占位符}}——有的话必须先替换才能连。 */
    public static boolean hasPlaceholder(String url) {
        return url != null && url.contains("{") && url.contains("}");
    }

    /** 取 URL 里第一个 {@code {name}} 的名字；无占位符返回空串。 */
    public static String placeholderName(String url) {
        if (url == null) return "";
        int open = url.indexOf('{');
        int close = open >= 0 ? url.indexOf('}', open + 1) : -1;
        if (open < 0 || close < 0) return "";
        return url.substring(open + 1, close).trim();
    }

    // ==================== 解析 ====================

    /**
     * 从 JSON 解析一条条目。
     *
     * <p>数据质量问题的处理原则：<b>会让"能不能装"判断出错的问题直接丢条目</b>
     * （能力位认不出、可连接档却没端点、id 不能当配置 id 用），其余只记进 {@code problems}
     * 并保留条目——市场页少一条比少一条描述更值得关注。</p>
     *
     * @param o        条目 JSON
     * @param problems 输出参数：本条的数据问题（供自检/测试断言）
     * @return 解析结果；返回 {@code null} 表示该条目被丢弃
     */
    public static McpMarketEntry fromJson(JSONObject o, List<String> problems) {
        if (o == null) return null;
        String id = o.optString("id").trim();
        if (!isConfigIdSafe(id)) {
            problems.add("丢弃条目：id 非法或缺失（" + id + "）");
            return null;
        }
        String title = o.optString("title").trim();
        if (title.isEmpty()) {
            problems.add("丢弃条目 " + id + "：缺 title");
            return null;
        }
        String rawCap = o.optString("capability").trim();
        Capability capability = Capability.fromWire(rawCap);
        if (capability == null) {
            problems.add("丢弃条目 " + id + "：capability 无法识别（" + rawCap + "）");
            return null;
        }

        List<Endpoint> endpoints = new ArrayList<>();
        JSONArray eps = o.optJSONArray("endpoints");
        if (eps != null) {
            for (int i = 0; i < eps.length(); i++) {
                JSONObject eo = eps.optJSONObject(i);
                if (eo == null) continue;
                String url = eo.optString("url").trim();
                if (url.isEmpty()) {
                    problems.add("条目 " + id + "：第 " + (i + 1) + " 个端点为空，已忽略");
                    continue;
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    problems.add("条目 " + id + "：端点不是 http(s)（" + url + "），已忽略");
                    continue;
                }
                String transport = eo.optString("transport").trim().toLowerCase(Locale.ROOT);
                McpServerConfig.Transport t = "sse".equals(transport)
                        ? McpServerConfig.Transport.SSE
                        : McpServerConfig.Transport.STREAMABLE_HTTP;
                if (transport.isEmpty()) {
                    problems.add("条目 " + id + "：端点未声明 transport，按 streamable_http 处理");
                }
                endpoints.add(new Endpoint(t, url, eo.optString("note", "")));
            }
        }
        if (capability.isConnectable() && endpoints.isEmpty()) {
            problems.add("丢弃条目 " + id + "：" + capability + " 却没有可用端点");
            return null;
        }

        List<String> guide = new ArrayList<>();
        JSONArray g = o.optJSONArray("guide");
        if (g != null) {
            for (int i = 0; i < g.length(); i++) {
                String line = g.optString(i).trim();
                if (!line.isEmpty()) guide.add(line);
            }
        }
        if (guide.isEmpty()) {
            problems.add("条目 " + id + "：没有开通指引，卡片只能显示端点");
        }

        List<String> tags = new ArrayList<>();
        JSONArray tg = o.optJSONArray("tags");
        if (tg != null) {
            for (int i = 0; i < tg.length(); i++) {
                String v = tg.optString(i).trim();
                if (!v.isEmpty()) tags.add(v);
            }
        }

        Probe probe = null;
        JSONObject po = o.optJSONObject("probe");
        if (po != null) {
            String result = po.optString("result").trim().toLowerCase(Locale.ROOT);
            if (result.isEmpty()) {
                problems.add("条目 " + id + "：probe 缺 result，按 unverified 处理");
                result = "unverified";
            } else if(!"ok".equals(result) && !"auth_required".equals(result)
                    && !"unverified".equals(result)) {
                problems.add("条目 " + id + "：probe.result 无法识别（" + result + "），按 unverified 处理");
                result = "unverified";
            }
            probe = new Probe(po.optString("checkedAt").trim(), result,
                    po.optString("evidence").trim());
        } else {
            problems.add("条目 " + id + "：没有自检记录");
        }

        String authRaw = o.optString("auth").trim();
        Auth auth = Auth.fromWire(authRaw);
        if (Auth.parseExact(authRaw) == null) {
            problems.add(authRaw.isEmpty()
                    ? "条目 " + id + "：没写 auth，按 UNKNOWN 处理"
                    : "条目 " + id + "：auth 无法识别（" + authRaw + "）");
        }

        String docsUrl = o.optString("docsUrl").trim();
        String consoleUrl = o.optString("consoleUrl").trim();
        if (!docsUrl.isEmpty() && !docsUrl.startsWith("http")) {
            problems.add("条目 " + id + "：docsUrl 不是 URL，已清空");
            docsUrl = "";
        }
        if (!consoleUrl.isEmpty() && !consoleUrl.startsWith("http")) {
            problems.add("条目 " + id + "：consoleUrl 不是 URL，已清空");
            consoleUrl = "";
        }
        if (docsUrl.isEmpty()) {
            problems.add("条目 " + id + "：缺 docsUrl，无法核对来源");
        }

        String verifiedAt = o.optString("verifiedAt").trim();
        if (verifiedAt.isEmpty()) {
            problems.add("条目 " + id + "：缺 verifiedAt，卡片只能显示「未标核对日期」");
        }

        String secretPlaceholder = o.optString("secretPlaceholder").trim();
        if (auth == Auth.QUERY_KEY && secretPlaceholder.isEmpty()) {
            problems.add("条目 " + id + "：声明密钥写在 URL 里却没标 secretPlaceholder，"
                    + "这样装进去只能是明文落盘");
        }

        return new McpMarketEntry(id, title,
                o.optString("provider").trim(),
                o.optString("providerKey").trim(),
                o.optString("description").trim(),
                capability, auth, o.optString("authHint").trim(), secretPlaceholder,
                o.optString("costNote").trim(),
                endpoints, guide, o.optString("blockedReason").trim(),
                docsUrl, consoleUrl, tags, verifiedAt, probe);
    }

    /** id 会直接当 {@link McpServerConfig} 的配置 id 用，必须限定字符集。 */
    public static boolean isConfigIdSafe(String id) {
        if (id == null || id.isEmpty() || id.length() > 48) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "McpMarketEntry{" + id + ", " + capability + ", endpoints=" + endpoints.size() + "}";
    }
}
