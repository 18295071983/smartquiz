package com.oilquiz.app.ai.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class APIKeyManager {

    private static final String TAG = "APIKeyManager";
    private static final String PREF_NAME = "api_keys";
    private static final String KEY_ENCRYPTION_KEY = "encryption_key";
    private static final String IV_KEY = "iv_key";
    private static final String KEYSTORE_ALIAS = "api_key_store";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private static APIKeyManager instance;
    private final SharedPreferences preferences;
    private SecretKey secretKey;
    private byte[] iv;
    private boolean useKeystore = false;

    private APIKeyManager(Context context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        initEncryption();
    }

    public static synchronized APIKeyManager getInstance(Context context) {
        if (instance == null) {
            instance = new APIKeyManager(context.getApplicationContext());
        }
        return instance;
    }

    private void initEncryption() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
                keyStore.load(null);
                if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                    KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEYSTORE_ALIAS, null);
                    secretKey = entry.getSecretKey();
                } else {
                    KeyGenerator keyGenerator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE);
                    keyGenerator.init(new android.security.keystore.KeyGenParameterSpec.Builder(
                            KEYSTORE_ALIAS,
                            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes("GCM")
                            .setEncryptionPaddings("NoPadding")
                            .setKeySize(256)
                            .build());
                    secretKey = keyGenerator.generateKey();
                }
                useKeystore = true;
                iv = null;
                migrateFromLegacyIfNeeded();
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "Android Keystore not available, falling back to SharedPreferences encryption", e);
        }
        initLegacyEncryption();
    }

    private void initLegacyEncryption() {
        useKeystore = false;
        secretKey = getOrCreateSecretKeyLegacy();
        iv = getOrCreateIVLegacy();
    }

    private void migrateFromLegacyIfNeeded() {
        try {
            String legacyKey = preferences.getString(KEY_ENCRYPTION_KEY, null);
            if (legacyKey == null) return;

            String[] serviceKeys = new String[]{
                    Service.OPENWEATHERMAP, Service.HEFENG_WEATHER, Service.OPENAI,
                    Service.GOOGLE_MAPS, Service.BING_SEARCH, Service.CUSTOM
            };

            SecretKey legacySecretKey = null;
            byte[] legacyIv = null;

            String ivStr = preferences.getString(IV_KEY, null);
            if (ivStr != null) {
                legacyIv = Base64.decode(ivStr, Base64.DEFAULT);
                byte[] keyBytes = Base64.decode(legacyKey, Base64.DEFAULT);
                legacySecretKey = new SecretKeySpec(keyBytes, "AES");
            }

            if (legacySecretKey != null && legacyIv != null) {
                SharedPreferences.Editor editor = preferences.edit();
                for (String service : serviceKeys) {
                    String encryptedVal = preferences.getString(service, null);
                    if (encryptedVal != null) {
                        try {
                            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                            cipher.init(Cipher.DECRYPT_MODE, legacySecretKey, new IvParameterSpec(legacyIv));
                            byte[] encrypted = Base64.decode(encryptedVal, Base64.DEFAULT);
                            byte[] decrypted = cipher.doFinal(encrypted);
                            String plainKey = new String(decrypted);
                            saveAPIKey(service, plainKey);
                            editor.remove(service);
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to migrate key for service: " + service, e);
                        }
                    }
                }
                editor.remove(KEY_ENCRYPTION_KEY);
                editor.remove(IV_KEY);
                editor.apply();
                Log.i(TAG, "Legacy keys migrated to Android Keystore");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during migration", e);
        }
    }

    private SecretKey getOrCreateSecretKeyLegacy() {
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

    private byte[] getOrCreateIVLegacy() {
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

    public void saveAPIHost(String service, String host) {
        try {
            String encryptedHost = encrypt(host);
            preferences.edit().putString(service + "_host", encryptedHost).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving API host for service: " + service, e);
        }
    }

    public String getAPIHost(String service, String defaultHost) {
        try {
            String encryptedHost = preferences.getString(service + "_host", null);
            if (encryptedHost != null) {
                return decrypt(encryptedHost);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting API host for service: " + service, e);
        }
        return defaultHost;
    }

    public String getAPIHost(String service) {
        return getAPIHost(service, null);
    }

    private String encrypt(String plaintext) throws Exception {
        if (useKeystore) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] ivBytes = cipher.getIV();
            byte[] encrypted = cipher.doFinal(plaintext.getBytes("UTF-8"));
            byte[] combined = new byte[ivBytes.length + encrypted.length];
            System.arraycopy(ivBytes, 0, combined, 0, ivBytes.length);
            System.arraycopy(encrypted, 0, combined, ivBytes.length, encrypted.length);
            return Base64.encodeToString(combined, Base64.DEFAULT);
        } else {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes("UTF-8"));
            return Base64.encodeToString(encrypted, Base64.DEFAULT);
        }
    }

    private String decrypt(String ciphertext) throws Exception {
        if (useKeystore) {
            byte[] combined = Base64.decode(ciphertext, Base64.DEFAULT);
            int ivLen = 12;
            byte[] ivBytes = new byte[ivLen];
            byte[] encrypted = new byte[combined.length - ivLen];
            System.arraycopy(combined, 0, ivBytes, 0, ivLen);
            System.arraycopy(combined, ivLen, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, ivBytes);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, "UTF-8");
        } else {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));
            byte[] encrypted = Base64.decode(ciphertext, Base64.DEFAULT);
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, "UTF-8");
        }
    }

    public static class Service {
        public static final String OPENWEATHERMAP = "openweathermap";
        public static final String HEFENG_WEATHER = "hefeng_weather";
        public static final String OPENAI = "openai";
        public static final String GOOGLE_MAPS = "google_maps";
        public static final String BING_SEARCH = "bing_search";
        public static final String CUSTOM = "custom";
        public static final String ONLYOFFICE = "onlyoffice";
    }

    public static class DefaultHost {
        public static final String HEFENG_WEATHER = "https://devapi.qweather.com";
        public static final String ONLYOFFICE = "https://documentserver.onlyoffice.eu";
    }
}
