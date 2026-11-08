package com.oilquiz.app.security.totp;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * TOTP 保险库专用 Keystore 加密。
 *
 * <p>和普通 API key 的 {@code ApiKeyCipher} 不同：这里**不允许明文回退**。
 * Keystore 不可用时直接抛异常，宁可不能保存，也不能把 TOTP 密钥落成明文。</p>
 */
final class TotpVaultCipher {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "oilquiz_totp_vault_v1";
    private static final String PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private TotpVaultCipher() {
    }

    static String encrypt(String plain) throws Exception {
        if (plain == null) throw new IllegalArgumentException("plain == null");
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) {
            throw new IllegalStateException("unexpected GCM IV length");
        }
        byte[] ct = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] packed = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
        return PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP);
    }

    static String decrypt(String stored) throws Exception {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalArgumentException("unsupported TOTP vault format");
        }
        byte[] packed = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
        if (packed.length <= IV_BYTES) throw new IllegalArgumentException("truncated TOTP vault");
        byte[] iv = new byte[IV_BYTES];
        byte[] ct = new byte[packed.length - IV_BYTES];
        System.arraycopy(packed, 0, iv, 0, IV_BYTES);
        System.arraycopy(packed, IV_BYTES, ct, 0, ct.length);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8);
    }

    static boolean available() {
        try {
            return getOrCreateKey() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}
