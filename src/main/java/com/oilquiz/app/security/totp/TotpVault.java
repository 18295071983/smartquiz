package com.oilquiz.app.security.totp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TOTP 保险库：JSON → Keystore AES-GCM → SharedPreferences。
 *
 * <p>安全边界：</p>
 * <ul>
 *   <li>加密失败绝不回退明文；</li>
 *   <li>解密失败不静默清空（避免把用户密钥“恢复”成空库）；</li>
 *   <li>备份规则排除该 prefs；</li>
 *   <li>不写日志、不暴露给 AI/Agent。</li>
 * </ul>
 */
public final class TotpVault {

    private static final String PREFS = "totp_vault";
    private static final String KEY_ACCOUNTS = "accounts";

    private static volatile TotpVault instance;

    private final SharedPreferences prefs;

    private TotpVault(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static TotpVault getInstance(Context context) {
        if (instance == null) {
            synchronized (TotpVault.class) {
                if (instance == null) instance = new TotpVault(context);
            }
        }
        return instance;
    }

    public synchronized boolean isAvailable() {
        return TotpVaultCipher.available();
    }

    public synchronized int count() {
        try {
            return loadLocked().size();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 是否存在密文（即使解密失败也 true）；用于打开时先要求设备锁屏。 */
    public synchronized boolean hasStoredData() {
        return prefs.contains(KEY_ACCOUNTS);
    }

    public synchronized List<TotpAccount> list() throws VaultException {
        return Collections.unmodifiableList(new ArrayList<>(loadLocked()));
    }

    public synchronized void add(TotpAccount account) throws VaultException {
        if (account == null) throw new VaultException("账号为空");
        if (TotpGenerator.decodeBase32(account.secret).length == 0) {
            throw new VaultException("secret 不是有效的 Base32");
        }
        List<TotpAccount> accounts = loadLocked();
        for (TotpAccount a : accounts) {
            if (a.secret.equals(account.secret)
                    && a.issuer.equalsIgnoreCase(account.issuer)
                    && a.account.equalsIgnoreCase(account.account)) {
                throw new VaultException("这个账号已经在保险库里了");
            }
        }
        accounts.add(account);
        saveLocked(accounts);
    }

    public synchronized void remove(String id) throws VaultException {
        List<TotpAccount> accounts = loadLocked();
        boolean removed = false;
        for (int i = accounts.size() - 1; i >= 0; i--) {
            if (accounts.get(i).id.equals(id)) {
                accounts.remove(i);
                removed = true;
            }
        }
        if (removed) saveLocked(accounts);
    }

    /** 明确重置：只在用户确认后调用；解密失败时也允许用户重建空库。 */
    public synchronized void clearAll() throws VaultException {
        saveLocked(new ArrayList<>());
    }

    private List<TotpAccount> loadLocked() throws VaultException {
        List<TotpAccount> out = new ArrayList<>();
        String stored = prefs.getString(KEY_ACCOUNTS, null);
        if (stored == null || stored.isEmpty()) return out;
        try {
            String json = TotpVaultCipher.decrypt(stored);
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                TotpAccount a = TotpAccount.fromJson(o);
                if (a != null) out.add(a);
            }
            return out;
        } catch (Exception e) {
            throw new VaultException("保险库解密失败，可能设备迁移或 Keystore 密钥丢失；"
                    + "不会自动清空。请从备份恢复或明确重置。", e);
        }
    }

    private void saveLocked(List<TotpAccount> accounts) throws VaultException {
        if (!TotpVaultCipher.available()) {
            throw new VaultException("系统 Keystore 不可用，拒绝以明文保存 TOTP 密钥");
        }
        try {
            JSONArray arr = new JSONArray();
            for (TotpAccount a : accounts) arr.put(a.toJson());
            String encrypted = TotpVaultCipher.encrypt(arr.toString());
            if (!prefs.edit().putString(KEY_ACCOUNTS, encrypted).commit()) {
                throw new VaultException("保险库写入失败（SharedPreferences.commit=false）");
            }
        } catch (VaultException e) {
            throw e;
        } catch (Exception e) {
            throw new VaultException("保险库加密/写入失败：" + e.getMessage(), e);
        }
    }

    public static final class VaultException extends Exception {
        public VaultException(String message) {
            super(message);
        }

        public VaultException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
