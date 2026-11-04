package com.oilquiz.app.ai.agent.online.mcp;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * OAuth 2.0 PKCE（RFC 7636）与 state 生成。
 *
 * <p>本 App 是<b>公开客户端</b>（public client，两家服务商的元数据都只声明
 * {@code token_endpoint_auth_methods_supported=["none"]}），拿不到也不该存 client_secret。
 * 授权码流程唯一的防护就是 PKCE：verifier 只留在本机，challenge 发给服务商，
 * 换令牌时再对上——浏览器把 code 递给别的 App 也换不到令牌。state 用来挡请求伪造，
 * 回传时按 state 找等待中的请求，对不上直接丢。</p>
 *
 * <p>纯静态工具，无 Android 依赖，可直接 JVM 单测（含 RFC 7636 附录 B 的已知向量）。</p>
 */
public final class McpPkce {

    private McpPkce() {}

    /** RFC 7636 §4.1：43–128 字符；这里取 64 字节随机 → base64url 去填充正好 86 字符。 */
    private static final int VERIFIER_BYTES = 64;
    private static final Base64.Encoder URL_NO_PADDING =
            Base64.getUrlEncoder().withoutPadding();

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 生成 code_verifier（[A-Za-z0-9._~-] 字符集，base64url 天然满足）。 */
    public static String newVerifier() {
        byte[] raw = new byte[VERIFIER_BYTES];
        RANDOM.nextBytes(raw);
        return URL_NO_PADDING.encodeToString(raw);
    }

    /** code_challenge = BASE64URL(SHA256(ASCII(code_verifier)))，即 S256 方法。 */
    public static String challengeOf(String verifier) {
        if (verifier == null || verifier.isEmpty()) {
            throw new IllegalArgumentException("verifier 不能为空");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return URL_NO_PADDING.encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 是 JCA 必备算法；真缺失时宁可抛错，也不能退化成"不发 challenge"
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 授权请求的 state（32 位十六进制，够随机且好比对）。 */
    public static String newState() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 常量时间比较，避免按字符提前返回带来的旁路（虽然这里攻击面很小）。 */
    public static boolean stateMatches(String expected, String received) {
        if (expected == null || received == null) return false;
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] b = received.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(a, b);
    }

    /** 服务商是否支持我们唯一能用的 S256。 */
    public static boolean supportsS256(java.util.List<String> methods) {
        if (methods == null) return false;
        for (String m : methods) {
            if (m != null && "S256".equalsIgnoreCase(m.trim())) return true;
        }
        return false;
    }
}
