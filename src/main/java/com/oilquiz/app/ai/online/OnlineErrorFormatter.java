package com.oilquiz.app.ai.online;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在线模型错误码 → 用户可读卡片（2026-10-30）。
 *
 * <p>在线链路抛出的原始异常形如
 * {@code API 请求失败: HTTP 401 - {"error":{"message":"invalid api key","code":"invalid_api_key"}}}。
 * 直接把这一串丢进对话既看不懂也不知道能不能重试。这里把 HTTP 码与常见业务码分成
 * 「标题 / 说明 / 可否重试」三件展示信息，原始文本仍由调用方写日志，便于排查。</p>
 *
 * <p>只做展示映射，不改变任何请求/重试策略；无法识别的码一律显示原码，不猜。</p>
 */
public final class OnlineErrorFormatter {

    private static final Pattern HTTP_CODE = Pattern.compile("HTTP\\s*(\\d{3})");

    public static final class Info {
        public final String title;
        public final String detail;
        public final boolean retryable;

        Info(String title, String detail, boolean retryable) {
            this.title = title;
            this.detail = detail;
            this.retryable = retryable;
        }
    }

    private OnlineErrorFormatter() {
    }

    /** 解析原始错误文本；不是 HTTP 错误时返回 null，调用方走原路径。 */
    public static Info parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        Matcher m = HTTP_CODE.matcher(raw);
        if (!m.find()) {
            // 没有 HTTP 状态码：尝试按网络异常归类（断网/DNS/超时/TLS/明文被拦）
            return parseNetwork(raw);
        }
        int code;
        try {
            code = Integer.parseInt(m.group(1));
        } catch (Throwable t) {
            return null;
        }
        String friendly = friendlyFor(code);
        boolean retryable = code == 408 || code == 429 || code >= 500;

        StringBuilder detail = new StringBuilder();
        detail.append(friendly);

        // 服务商错误体里的 message/code（有就带上，截断防刷屏）
        String providerMsg = null;
        String providerCode = null;
        int dash = raw.indexOf(" - ");
        if (dash >= 0) {
            String body = raw.substring(dash + 3).trim();
            try {
                JSONObject o = new JSONObject(body);
                JSONObject err = o.optJSONObject("error");
                if (err != null) {
                    providerMsg = err.optString("message", null);
                    providerCode = err.optString("code", null);
                    if (providerCode == null) providerCode = err.optString("type", null);
                } else {
                    providerMsg = o.optString("message", null);
                    providerCode = o.optString("code", null);
                }
            } catch (Throwable ignored) {
            }
        }
        if (providerMsg != null && !providerMsg.trim().isEmpty()) {
            String msg = providerMsg.trim();
            if (msg.length() > 300) msg = msg.substring(0, 300) + "…";
            detail.append("\n服务商说明：").append(msg);
        }
        if (providerCode != null && !providerCode.trim().isEmpty()) {
            detail.append("\n服务商错误码：").append(providerCode.trim());
        }
        detail.append("\nHTTP ").append(code);
        return new Info("在线请求失败（HTTP " + code + "）", detail.toString(), retryable);
    }

    /**
     * 网络类异常 → 结构化卡片（无 HTTP 码时使用）。
     * 判定只依赖常见异常文案/类名关键字，命中不了返回 null（继续走原文，不硬猜）。
     */
    private static Info parseNetwork(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        String title;
        String detail;
        boolean retryable = true;
        if (s.contains("cleartext") || s.contains("not permitted by network security policy")) {
            title = "明文 HTTP 被系统拦截";
            detail = "接口地址是 http://，Android 默认禁止明文流量；请改用 https，或在网络安全配置里放行该域名。";
            retryable = false;
        } else if (s.contains("ssl") || s.contains("certificate")
                || s.contains("handshake") || s.contains("trust anchor")) {
            title = "安全连接失败";
            detail = "TLS/证书校验未通过：检查系统时间、代理证书，或服务商的 HTTPS 配置。";
        } else if (s.contains("sockettimeout") || s.contains("timed out")
                || s.contains("timeout") || s.contains("超时")) {
            title = "连接超时";
            detail = "连接服务商超时：网络较慢或被代理拦截，可稍后重试。";
        } else if (s.contains("refused")) {
            title = "连接被拒绝";
            detail = "服务商端口拒绝连接：检查接口地址/端口与代理设置是否正确。";
        } else if (s.contains("reset") || s.contains("abort") || s.contains("broken pipe")) {
            title = "连接被中断";
            detail = "连接中途被关闭：可能是代理、网络切换或服务端断开，可重试。";
        } else if (s.contains("unable to resolve host") || s.contains("unknownhost")
                || s.contains("no address associated")) {
            title = "网络不可达（域名解析失败）";
            detail = "无法解析服务商域名：检查网络连接、代理/DNS，或接口地址是否写错。";
        } else if (s.contains("network is unreachable") || s.contains("no route to host")) {
            title = "网络不可达";
            detail = "设备当前没有到服务商的可用网络：检查 Wi-Fi/蜂窝网络或代理。";
        } else {
            return null;
        }
        StringBuilder body = new StringBuilder(detail);
        String shortRaw = raw == null ? "" : raw.trim();
        if (shortRaw.length() > 240) shortRaw = shortRaw.substring(0, 240) + "…";
        if (!shortRaw.isEmpty()) {
            body.append("\n原始信息：").append(shortRaw);
        }
        return new Info(title, body.toString(), retryable);
    }

    private static String friendlyFor(int code) {
        switch (code) {
            case 400:
                return "请求参数有误（模型名、消息格式或工具参数不被该服务商接受）。";
            case 401:
                return "鉴权失败：API Key 无效、已过期，或该服务商要求不同的 Key。";
            case 402:
                return "账户余额不足，请充值或切换模型。";
            case 403:
                return "无访问权限：该 Key 没有这个模型的权限，或所在地区被限制。";
            case 404:
                return "接口或模型不存在：检查模型名与「接口地址」配置。";
            case 408:
                return "请求超时，可稍后重试。";
            case 413:
                return "请求体过大：历史或附件超出服务商限制。";
            case 429:
                return "请求过于频繁，触发服务商限流，稍后重试或降低并发。";
            default:
                if (code >= 500) {
                    return "服务商服务异常（" + code + "），通常是对方暂时不可用，可稍后重试。";
                }
                return String.format(Locale.ROOT, "服务商返回未知状态（%d）。", code);
        }
    }
}
