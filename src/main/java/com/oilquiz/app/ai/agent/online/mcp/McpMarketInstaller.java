package com.oilquiz.app.ai.agent.online.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 市场安装向导的纯逻辑（不碰 Android/View，单测直接喂条目和字符串就能校）。
 *
 * <p>把"能不能装 / 装成什么配置 / 探测结果怎么说"从 Activity 里搬出来的原因：
 * 这三件事是市场页唯一的正确性来源，放在 View 层就只能靠真机点，
 * 搬出来后才能用 {@code McpMarketCatalog} 的真实数据跑契约测试。</p>
 */
public final class McpMarketInstaller {

    private McpMarketInstaller() {}

    /** 用户可改名的兜底名。 */
    public static String displayName(String userTyped, McpMarketEntry entry) {
        if (userTyped != null && !userTyped.trim().isEmpty()) return userTyped.trim();
        return entry != null ? entry.title : "MCP 服务";
    }

    // ==================== 文案 ====================

    /** 能力位短标签（卡片右上角徽标）。 */
    public static String capabilityLabel(McpMarketEntry.Capability c) {
        if (c == null) return "未知";
        switch (c) {
            case DIRECT_REMOTE:
                return "可直连";
            case PROVISIONED_REMOTE:
                return "需开通";
            case PORTAL_ONLY:
                return "仅网页取端点";
            case NEEDS_OAUTH:
                return "需浏览器授权";
            case LOCAL_ONLY:
            default:
                return "本地包·不兼容";
        }
    }

    /** 能力位的一句话解释（"这一档意味着什么"）。 */
    public static String capabilityHint(McpMarketEntry.Capability c) {
        if (c == null) return "清单没标接入方式";
        switch (c) {
            case DIRECT_REMOTE:
                return "端点固定，填好密钥就能在这台手机上连";
            case PROVISIONED_REMOTE:
                return "先去厂商控制台开通，把端点里的占位符换成你自己的";
            case PORTAL_ONLY:
                return "只有网页广场：端点要在服务详情页现取，取到后到管理页手动添加";
            case NEEDS_OAUTH:
                return "端点可达，但每次都要浏览器完成厂商 OAuth，App 还没实现授权流程";
            case LOCAL_ONLY:
            default:
                return "官方只有本地 stdio/npx 分发，Android 端起不来这个进程";
        }
    }

    public static String authLabel(McpMarketEntry.Auth a) {
        if (a == null) return "鉴权未知";
        switch (a) {
            case NONE:
                return "免密钥";
            case QUERY_KEY:
                return "密钥在 URL 里";
            case BEARER_TOKEN:
                return "Bearer 令牌";
            case OAUTH:
                return "浏览器 OAuth";
            case MIXED:
                return "Bearer 或 OAuth";
            case UNKNOWN:
            default:
                return "鉴权随服务而定";
        }
    }

    /** 卡片/对话框里的来源与自检行：核对日期 + 一次真实探测的结论。 */
    public static String sourceLine(McpMarketEntry e) {
        if (e == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("文档核对 ").append(e.verifiedAt.isEmpty() ? "未标日期" : e.verifiedAt);
        if (e.probe != null) {
            sb.append(" · 自检 ");
            sb.append(e.probe.checkedAt.isEmpty() ? "未标日期" : e.probe.checkedAt);
            sb.append("：");
            sb.append(probeResultLabel(e.probe));
        } else {
            sb.append(" · 无自检记录");
        }
        return sb.toString();
    }

    private static String probeResultLabel(McpMarketEntry.Probe probe) {
        if (probe.isHandshakeOk()) return "握手成功";
        if ("auth_required".equals(probe.result)) return "差凭证";
        return "未验证";
    }

    // ==================== 校验 ====================

    /**
     * 安装请求校验。
     *
     * @return 通过返回 {@code null}；否则返回给用户看的错误文案
     */
    public static String validateInstall(McpMarketEntry entry, String endpoint) {
        if (entry == null) return "清单里没有这条服务";
        if (!entry.capability.isConnectable()) {
            String reason = entry.blockedReason.isEmpty()
                    ? capabilityHint(entry.capability) : entry.blockedReason;
            return "这条不能在这里安装：" + reason;
        }
        String url = endpoint == null ? "" : endpoint.trim();
        if (url.isEmpty()) return "端点不能为空";
        if (McpMarketEntry.hasPlaceholder(url)) {
            return "端点里还有占位符 {"
                    + McpMarketEntry.placeholderName(url) + "}，先换成真实值";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "端点必须以 http:// 或 https:// 开头";
        }
        if (!McpServerConfig.isUsableEndpoint(url)) {
            return "端点不是合法的 http(s) 地址，请整条粘贴控制台下发的 URL";
        }
        return null;
    }

    /**
     * 非阻塞提示：用户填的端点与条目声明的鉴权方式看起来不匹配时给出。
     *
     * <p>只在向导里灰字提示，不拦"下一步"——服务商文档也会变，硬拦会把能连的用户挡在门外。</p>
     */
    public static List<String> advisories(McpMarketEntry entry, String endpoint) {
        List<String> tips = new ArrayList<>();
        if (entry == null || endpoint == null) return tips;
        String url = endpoint.trim().toLowerCase(Locale.ROOT);
        if (entry.auth == McpMarketEntry.Auth.QUERY_KEY && !url.contains("key=")) {
            tips.add("该服务把密钥写在 URL 里（" + entry.authHint + "），当前端点没看到 key= 参数");
        }
        if ((entry.auth == McpMarketEntry.Auth.OAUTH) && entry.capability.isConnectable()) {
            tips.add("该服务声明需要浏览器 OAuth，App 暂未实现授权流程");
        }
        if (url.startsWith("http://")) {
            tips.add("明文 HTTP 端点：密钥会随请求裸传，除非在可信内网，否则别用");
        }
        return tips;
    }

    // ==================== 配置构造 ====================

    /**
     * 按 URL 推断传输方式。
     *
     * <p>约定：SSE 入口的路径以 {@code /sse} 结尾（腾讯位置服务、魔搭生成的 URL 都是这个形状），
     * 以 {@code /mcp} 结尾按 Streamable HTTP；两条都对不上的（自建服务爱怎么起路径怎么起）
     * 保持调用方给的兜底档，不猜。用户粘贴 URL 后不该再让他二选一并猜错。</p>
     */
    public static McpServerConfig.Transport transportFor(String url,
                                                          McpServerConfig.Transport fallback) {
        if (url == null || url.trim().isEmpty()) return fallback;
        String u = url.trim().toLowerCase(Locale.ROOT);
        int query = u.indexOf('?');
        String path = query >= 0 ? u.substring(0, query) : u;
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith("/sse")) return McpServerConfig.Transport.SSE;
        if (path.endsWith("/mcp")) return McpServerConfig.Transport.STREAMABLE_HTTP;
        return fallback;
    }

    /**
     * 组装配置。
     *
     * @param enabled 只有探测通过后、由用户显式确认才传 true（失败时另存为停用）
     */
    public static McpServerConfig buildConfig(McpMarketEntry entry, String endpoint,
                                              String displayName, McpServerConfig.Transport transport,
                                              String tokenRef, boolean enabled) {
        String url = endpoint == null ? "" : endpoint.trim();
        McpServerConfig.Transport t = transport != null ? transport : transportFor(url,
                McpServerConfig.Transport.STREAMABLE_HTTP);
        return new McpServerConfig(
                entry.id,
                displayName(displayName, entry),
                url,
                t,
                "",
                tokenRef == null ? "" : tokenRef.trim(),
                enabled,
                // 来源标"清单装入"：管理页据此在手填项后标"(手动添加)"
                true);
    }

    // ==================== 探测结果 ====================

    /** 最多展示几个工具名（再多就只是刷屏）。 */
    public static final int PROBE_NAME_LIMIT = 8;

    /** 探测结果 → 给用户看的一行结论（含协议/服务端信息与工具名样例）。 */
    public static String describeProbe(McpManager.ProbeResult r) {
        if (r == null) return "没跑探测";
        if (!r.ok) return "连不上：" + r.error;
        StringBuilder sb = new StringBuilder();
        sb.append("连接成功，发现 ").append(r.toolCount).append(" 个工具");
        if (!r.protocolVersion.isEmpty()) sb.append("（协议 ").append(r.protocolVersion).append("）");
        if (!r.serverInfo.isEmpty()) sb.append(" 服务端 ").append(r.serverInfo);
        if (r.toolCount == 0) {
            sb.append("\n注意：这个服务没有对外工具，启用后模型也拿不到能力。");
            return sb.toString();
        }
        sb.append("\n");
        int n = Math.min(r.toolNames.size(), PROBE_NAME_LIMIT);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append("、");
            sb.append(r.toolNames.get(i));
        }
        if (r.toolNames.size() > n) sb.append(" …");
        return sb.toString();
    }

    /**
     * 探测失败时的下一步建议（按真实报错特征给，不发通用"请检查网络"）。
     *
     * <p>写这一段是因为市场条目最常见的两种失败——占位符没换/密钥没引——
     * 都不该让用户自己从 HTTP 状态码反推。</p>
     */
    public static List<String> failureHints(McpMarketEntry entry, McpManager.ProbeResult r) {
        List<String> hints = new ArrayList<>();
        if (entry == null || r == null || r.ok) return hints;
        String err = r.error == null ? "" : r.error.toLowerCase(Locale.ROOT);
        if (err.contains("401") || err.contains("unauthorized") || err.contains("api-key")
                || err.contains("apikey") || err.contains("token") || err.contains("invalid_user_key")) {
            hints.add("鉴权没过：确认密钥已存进密钥库并在管理页引用，或把 URL 里的 key= 换成真实 Key");
            if (entry.auth == McpMarketEntry.Auth.OAUTH
                    || entry.auth == McpMarketEntry.Auth.MIXED) {
                hints.add("该服务也支持浏览器 OAuth，但 App 目前只走 Bearer/URL Key 这一支");
            }
        }
        if (err.contains("404") || err.contains("not found")) {
            hints.add("端点 404：开通后下发的完整 URL 要整条粘贴，别只换域名");
        }
        if (err.contains("timeout") || err.contains("failed to connect")
                || err.contains("unable to resolve") || err.contains("connect")) {
            hints.add("连不上主机：确认手机网络可达该域名（部分厂商端点只在国内网络可用）");
        }
        if (err.contains("json") || err.contains("parse") || err.contains("not an mcp")) {
            hints.add("返回的不是 MCP 报文：多半是占位符没替换，或传输选错了（/sse 结尾走 SSE，其余走 Streamable HTTP）");
        }
        if (!entry.authHint.isEmpty()) {
            hints.add("本条目的鉴权要求：" + entry.authHint);
        }
        return hints;
    }

    /** 失败建议合成一段文本（对话框用）。 */
    public static String failureHintsText(McpMarketEntry entry, McpManager.ProbeResult r) {
        List<String> hints = failureHints(entry, r);
        if (hints.isEmpty()) return "";
        return String.join("\n", hints);
    }
}
