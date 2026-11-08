package com.oilquiz.app.security.totp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP 生成器（纯 JVM，无 Android/网络依赖）。
 *
 * <p>只负责算法；密钥的存储/加密在 {@link TotpVault}。这里不写日志。</p>
 */
public final class TotpGenerator {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpGenerator() {
    }

    public static String normalizeAlgorithm(String algorithm) {
        if (algorithm == null) return "SHA1";
        String a = algorithm.trim().toUpperCase(java.util.Locale.US).replace("-", "");
        if ("SHA256".equals(a)) return "SHA256";
        if ("SHA512".equals(a)) return "SHA512";
        return "SHA1";
    }

    /** 去掉空格/连字符/等号并转大写；不做合法性判断。 */
    public static String normalizeSecret(String secret) {
        if (secret == null) return "";
        StringBuilder sb = new StringBuilder(secret.length());
        for (int i = 0; i < secret.length(); i++) {
            char c = secret.charAt(i);
            if (Character.isWhitespace(c) || c == '-' || c == '=') continue;
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    public static String generate(TotpAccount account, long epochSeconds) {
        if (account == null) throw new IllegalArgumentException("account == null");
        return generate(account.secret, account.algorithm, account.digits,
                account.period, epochSeconds);
    }

    public static String generate(String secretBase32, String algorithm, int digits,
                                  int period, long epochSeconds) {
        String normalized = normalizeSecret(secretBase32);
        byte[] key = decodeBase32(normalized);
        if (key.length == 0) throw new IllegalArgumentException("empty TOTP secret");
        int d = digits >= 6 && digits <= 8 ? digits : 6;
        int p = period >= 1 ? period : 30;
        long counter = epochSeconds / p;
        byte[] hash = hmac(normalizeAlgorithm(algorithm), key, counter);
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int otp = binary % pow10(d);
        return String.format(java.util.Locale.US, "%0" + d + "d", otp);
    }

    public static int remainingSeconds(int period, long epochSeconds) {
        int p = period >= 1 ? period : 30;
        long rem = p - (epochSeconds % p);
        return (int) (rem == 0 ? p : rem);
    }

    /** 0..1，用于进度条；刚换码时为 1。 */
    public static float progress(int period, long epochSeconds) {
        int p = period >= 1 ? period : 30;
        return (float) remainingSeconds(p, epochSeconds) / p;
    }

    private static byte[] hmac(String algorithm, byte[] key, long counter) {
        String macAlg;
        switch (algorithm) {
            case "SHA256":
                macAlg = "HmacSHA256";
                break;
            case "SHA512":
                macAlg = "HmacSHA512";
                break;
            case "SHA1":
            default:
                macAlg = "HmacSHA1";
                break;
        }
        try {
            Mac mac = Mac.getInstance(macAlg);
            mac.init(new SecretKeySpec(key, macAlg));
            byte[] msg = ByteBuffer.allocate(8).putLong(counter).array();
            return mac.doFinal(msg);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("TOTP HMAC unavailable: " + macAlg, e);
        }
    }

    public static byte[] decodeBase32(String input) {
        String s = normalizeSecret(input);
        if (s.isEmpty()) return new byte[0];
        int buffer = 0;
        int bitsLeft = 0;
        byte[] out = new byte[s.length() * 5 / 8 + 1];
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int val = BASE32_ALPHABET.indexOf(c);
            if (val < 0) {
                throw new IllegalArgumentException("invalid Base32 character: " + c);
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[count++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        if (count == out.length) return out;
        byte[] trimmed = new byte[count];
        System.arraycopy(out, 0, trimmed, 0, count);
        return trimmed;
    }

    private static int pow10(int digits) {
        int v = 1;
        for (int i = 0; i < digits; i++) v *= 10;
        return v;
    }

    /** 用于自检/测试：ASCII secret 转 Base32（不是给 UI 用的）。 */
    public static String encodeBase32(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return sb.toString();
    }

    public static String asciiSecretToBase32(String ascii) {
        return encodeBase32(ascii.getBytes(StandardCharsets.US_ASCII));
    }
}
