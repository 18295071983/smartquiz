package com.oilquiz.app.security.totp;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析 otpauth URI（Google Authenticator 兼容格式）。
 *
 * <p>只支持 TOTP；HOTP 需要计数器，和保险库模型不一致，明确拒绝而不是猜。</p>
 */
public final class TotpUri {

    private TotpUri() {
    }

    public static TotpAccount parse(String raw) throws IllegalArgumentException {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("二维码内容为空");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("otpauth 链接格式错误", e);
        }
        if (!"otpauth".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("不是 otpauth:// 二维码");
        }
        String type = uri.getHost();
        if (type == null && uri.getAuthority() != null) type = uri.getAuthority();
        if (type == null || !"totp".equalsIgnoreCase(type)) {
            throw new IllegalArgumentException("只支持 TOTP，不支持 " + type);
        }

        Map<String, String> params = parseQuery(uri.getRawQuery());
        String secret = params.get("secret");
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalArgumentException("二维码缺少 secret");
        }
        // 先验证 Base32，避免把坏密钥存进保险库。
        if (TotpGenerator.decodeBase32(secret).length == 0) {
            throw new IllegalArgumentException("secret 不是有效的 Base32");
        }

        String label = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (label.startsWith("/")) label = label.substring(1);
        label = urlDecode(label);

        String issuer = trimToEmpty(params.get("issuer"));
        String account = label;
        int colon = label.indexOf(':');
        if (colon >= 0) {
            if (issuer.isEmpty()) issuer = label.substring(0, colon).trim();
            account = label.substring(colon + 1).trim();
        } else {
            account = label.trim();
        }
        if (issuer.isEmpty() && account.contains(":")) {
            int c = account.indexOf(':');
            issuer = account.substring(0, c).trim();
            account = account.substring(c + 1).trim();
        }

        String algorithm = params.get("algorithm");
        int digits = parseInt(params.get("digits"), 6);
        int period = parseInt(params.get("period"), 30);
        return new TotpAccount(java.util.UUID.randomUUID().toString(),
                issuer, account, secret, algorithm, digits, period);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return out;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            out.put(urlDecode(k), urlDecode(v));
        }
        return out;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s == null ? "" : s;
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(trimToEmpty(s));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
