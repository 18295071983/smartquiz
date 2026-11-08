package com.oilquiz.app.security.totp;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * TOTP 账号（不含 UI）。secret 只应存在于 {@link TotpVault} 的加密载荷中，
 * 不应进入日志、聊天历史或 AI 工具。
 */
public final class TotpAccount {

    public final String id;
    public final String issuer;
    public final String account;
    /** Base32 secret，不带空格；绝不写日志。 */
    public final String secret;
    public final String algorithm;
    public final int digits;
    public final int period;

    public TotpAccount(String id, String issuer, String account, String secret,
                       String algorithm, int digits, int period) {
        this.id = id == null || id.isEmpty() ? java.util.UUID.randomUUID().toString() : id;
        this.issuer = issuer == null ? "" : issuer.trim();
        this.account = account == null ? "" : account.trim();
        this.secret = TotpGenerator.normalizeSecret(secret);
        this.algorithm = TotpGenerator.normalizeAlgorithm(algorithm);
        this.digits = digits >= 6 && digits <= 8 ? digits : 6;
        this.period = period >= 15 && period <= 120 ? period : 30;
    }

    public String displayLabel() {
        if (!issuer.isEmpty() && !account.isEmpty()) return issuer + " · " + account;
        if (!issuer.isEmpty()) return issuer;
        if (!account.isEmpty()) return account;
        return "未命名账号";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("issuer", issuer);
        o.put("account", account);
        o.put("secret", secret);
        o.put("algorithm", algorithm);
        o.put("digits", digits);
        o.put("period", period);
        return o;
    }

    public static TotpAccount fromJson(JSONObject o) throws JSONException {
        if (o == null) return null;
        String secret = o.optString("secret", "");
        if (secret.isEmpty()) return null;
        return new TotpAccount(
                o.optString("id", ""),
                o.optString("issuer", ""),
                o.optString("account", ""),
                secret,
                o.optString("algorithm", "SHA1"),
                o.optInt("digits", 6),
                o.optInt("period", 30));
    }

    /** 给 UI/日志用的脱敏描述；不含 secret。 */
    public String safeDescription() {
        return String.format(Locale.US, "%s [%s/%d/%ds]",
                displayLabel(), algorithm, digits, period);
    }
}
