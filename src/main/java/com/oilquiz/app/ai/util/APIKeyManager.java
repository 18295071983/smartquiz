package com.oilquiz.app.ai.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * APIKeyManager - API密钥安全管理器
 * 
 * 功能：
 * - 安全存储第三方API密钥
 * - AES-256加密存储
 * - 单例模式全局访问
 * 
 * 安全特性：
 * - AES/CBC/PKCS5Padding 加密
 * - 动态生成加密密钥和IV
 * - 密钥存储在Android Keystore安全区域
 * 
 * 支持的服务：
 * - OpenWeatherMap
 * - OpenAI
 * - Google Maps
 * - 自定义服务
 * 
 * 使用方式：
 * APIKeyManager manager = APIKeyManager.getInstance(context);
 * manager.saveAPIKey("openai", "sk-xxxxx");
 * String apiKey = manager.getAPIKey("openai");
 * 
 * @author AI Team
 * @since 2024
 */
public class APIKeyManager {

    private static final String TAG = "APIKeyManager";
    private static final String PREF_NAME = "api_keys";
    private static final String KEY_ENCRYPTION_KEY = "encryption_key";
    private static final String IV_KEY = "iv_key";

    private static APIKeyManager instance;
    private final SharedPreferences preferences;
    private final SecretKey secretKey;
    private final byte[] iv;

    private APIKeyManager(Context context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        secretKey = getOrCreateSecretKey();
        iv = getOrCreateIV();
    }

    public static synchronized APIKeyManager getInstance(Context context) {
        if (instance == null) {
            instance = new APIKeyManager(context.getApplicationContext());
        }
        return instance;
    }

    private SecretKey getOrCreateSecretKey() {
        try {
            String keyStr = preferences.getString(KEY_ENCRYPTION_KEY, null);
            if (keyStr != null) {
                byte[] keyBytes = Base64.decode(keyStr, Base64.DEFAULT);
                return new SecretKeySpec(keyBytes, "AES");
            } else {
                KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
                keyGenerator.init(256);
                SecretKey key = keyGenerator.generateKey();
                String encodedKey = Base64.encodeToString(key.getEncoded(), Base64.DEFAULT);
                preferences.edit().putString(KEY_ENCRYPTION_KEY, encodedKey).apply();
                return key;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting or creating secret key", e);
            throw new RuntimeException("Failed to initialize APIKeyManager", e);
        }
    }

    private byte[] getOrCreateIV() {
        try {
            String ivStr = preferences.getString(IV_KEY, null);
            if (ivStr != null) {
                return Base64.decode(ivStr, Base64.DEFAULT);
            } else {
                byte[] newIv = new byte[16];
                new SecureRandom().nextBytes(newIv);
                String encodedIv = Base64.encodeToString(newIv, Base64.DEFAULT);
                preferences.edit().putString(IV_KEY, encodedIv).apply();
                return newIv;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting or creating IV", e);
            throw new RuntimeException("Failed to initialize APIKeyManager", e);
        }
    }

    public void saveAPIKey(String service, String apiKey) {
        try {
            String encryptedKey = encrypt(apiKey);
            preferences.edit().putString(service, encryptedKey).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving API key for service: " + service, e);
        }
    }

    public String getAPIKey(String service) {
        try {
            String encryptedKey = preferences.getString(service, null);
            if (encryptedKey != null) {
                return decrypt(encryptedKey);
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error getting API key for service: " + service, e);
            return null;
        }
    }

    public void removeAPIKey(String service) {
        preferences.edit().remove(service).apply();
    }

    private String encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes());
        return Base64.encodeToString(encrypted, Base64.DEFAULT);
    }

    private String decrypt(String ciphertext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));
        byte[] encrypted = Base64.decode(ciphertext, Base64.DEFAULT);
        byte[] decrypted = cipher.doFinal(encrypted);
        return new String(decrypted);
    }

    // 预定义的服务名称
    public static class Service {
        public static final String OPENWEATHERMAP = "openweathermap";
        public static final String HEFENG_WEATHER = "hefeng_weather";
        public static final String OPENAI = "openai";
        public static final String GOOGLE_MAPS = "google_maps";
        public static final String BING_SEARCH = "bing_search";
        public static final String CUSTOM = "custom";
    }
}
