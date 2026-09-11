package com.oilquiz.app.ai.util;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * M12：API Key 加密存储（Android Keystore AES-256-GCM）。
 *
 * 存储格式："enc:" + Base64(iv || ciphertext)。
 * - 加密失败（Keystore 异常等）返回原文，保证可用性不因加密中断；
 * - 解密失败（密钥丢失/设备恢复备份）返回原文并标记，用户重新填 Key 即恢复。
 * 明文历史数据无需迁移：读取时非 "enc:" 前缀直接视为明文返回。
 */
public final class ApiKeyCipher {

    private static final String TAG = "ApiKeyCipher";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "oilquiz_api_key_v1";
    private static final String PREFIX = "enc:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private ApiKeyCipher() {
    }

    /** 加密 API Key；失败返回原文（不阻塞功能）。 */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Throwable t) {
            com.oilquiz.app.util.AILogger.w(TAG, "encrypt failed, storing plaintext: " + t.getMessage());
            return plain;
        }
    }

    /** 解密 API Key；非加密格式直接返回原文，解密失败返回原文。 */
    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return stored;
        if (!stored.startsWith(PREFIX)) {
            return stored; // 明文历史数据（兼容）
        }
        try {
            byte[] combined = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            if (combined.length < IV_BYTES + 1) return stored;
            byte[] iv = new byte[IV_BYTES];
            byte[] ciphertext = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            com.oilquiz.app.util.AILogger.w(TAG, "decrypt failed, returning raw (user may need to re-enter key): "
                    + t.getMessage());
            return stored;
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
        if (entry != null) {
            return entry.getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
