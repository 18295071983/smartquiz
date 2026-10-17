package com.oilquiz.app.ai.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 密钥落盘格式的**唯一**读写口径：Android Keystore AES-256-GCM。
 *
 * <p><b>只认一种格式</b>（用户明确要求：旧格式不需要兼容）。值是
 * {@code "enc:" + Base64(iv||ciphertext)}（{@link ApiKeyCipher}，主密钥不出芯片、每次随机 IV），
 * 其余一律视为"没有配置"。历史上散落在调用方的字符串判断（{@code endsWith}、
 * {@code startsWith("UNENC:")}、"解不开就把原文返回"）全部删除 —— 那种宽容正是
 * 密文冒充密钥被发出去、换来一个查不出原因的 401 的源头。</p>
 *
 * <p>旧的 AES-CBC 固定 IV 数据（主密钥与密文同存一个 SharedPreferences 文件）只在
 * {@link SecretMigrator} 的<b>一次性</b>升级里被读取；读不出就直接作废，不留永久兼容路径。</p>
 *
 * <p>纯 JVM 依赖，不触 Android API，便于单测跑真密码学。</p>
 */
public final class SecretCodec {

    /** Keystore AES-256-GCM 密文前缀（与 {@link ApiKeyCipher} 一致）。 */
    public static final String PREFIX_GCM = "enc:";

    /**
     * Keystore GCM 主密钥抽象。生产实现是 {@link ApiKeyCipher} 的薄包装；
     * 单测注入内存 AES-GCM，从而能真跑整条"解不开/密钥不可用"分支。
     */
    public interface GcmCipher {
        /**
         * @return 带 {@link #PREFIX_GCM} 前缀的密文；{@code null} 表示失败
         *         （调用方必须放弃本次写入，<b>不得</b>退回明文）
         */
        String tryEncrypt(String plaintext);

        /** @return 明文；{@code null} 表示解不开（主密钥丢失、跨设备恢复备份等） */
        String tryDecrypt(String storedWithPrefix);

        /** 主密钥当前是否可用；不可用时写入必须整笔放弃 */
        boolean isAvailable();
    }

    private SecretCodec() {
    }

    /**
     * 编码待落盘的值。
     *
     * @return 密文；Keystore 不可用或加密失败时返回 {@code null} —— 调用方据此放弃写入。
     *         本类不提供"降级写明文"的选项：一个解不开的条目只是"未配置"，
     *         而一条明文密钥是谁都能读的。
     */
    public static String encode(String plaintext, GcmCipher gcm) {
        if (plaintext == null) return null;
        if (gcm == null || !gcm.isAvailable()) return null;
        String enc = gcm.tryEncrypt(plaintext);
        // tryEncrypt 只有返回带前缀的值才算成功；"失败返回原文"那种宽容实现必须在此拦下，
        // 否则等于把明文当成已加密落盘。
        return enc != null && enc.startsWith(PREFIX_GCM) ? enc : null;
    }

    /**
     * 解码落盘值。
     *
     * @return 明文；{@code null} 表示"这条不算数据"（不是 {@code enc:} 格式，或主密钥解不开）。
     *         非 {@code enc:} 的值<b>不会</b>被当作明文返回 —— 旧格式已由 {@link SecretMigrator}
     *         一次性升级或作废，读取路径无需再认识它们。
     */
    public static String decode(String stored, GcmCipher gcm) {
        if (stored == null || !stored.startsWith(PREFIX_GCM)) {
            return null;
        }
        if (gcm == null || !gcm.isAvailable()) {
            return null;
        }
        String plain = gcm.tryDecrypt(stored);
        // 兜住"解密失败就原样返回"的实现：返回值仍带前缀即未解密成功
        if (plain == null || plain.equals(stored) || plain.startsWith(PREFIX_GCM)) {
            return null;
        }
        return plain;
    }

    /** 是否目标格式（迁移器据此判断"已经是 enc:，无需再动"）。 */
    public static boolean isTargetFormat(String stored) {
        return stored != null && stored.startsWith(PREFIX_GCM);
    }

    /**
     * 宽松 Base64 解码：兼容 {@code android.util.Base64.DEFAULT} 的折行与尾随换行。
     *
     * <p>旧数据由 Android API 写出（76 字符折行 + 尾随 {@code \n}），JDK 的基本解码器遇换行
     * 会抛异常，必须用 MIME 解码器；而 {@link ApiKeyCipher} 用 NO_WRAP，MIME 解码器同样吃得下。</p>
     *
     * @return {@code null} 表示不是合法 Base64
     */
    public static byte[] decodeBase64Tolerant(String value) {
        if (value == null) return null;
        try {
            return Base64.getMimeDecoder().decode(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 编码为 Base64（CRLF 归一成 LF，与 {@code android.util.Base64.DEFAULT} 同形）。 */
    public static String encodeBase64(byte[] bytes) {
        if (bytes == null) return null;
        return Base64.getMimeEncoder().encodeToString(bytes).replace("\r\n", "\n");
    }

    /** UTF-8 字节 → 字符串（旧格式解密结果统一走 UTF-8，不再依赖平台默认编码）。 */
    public static String utf8(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
