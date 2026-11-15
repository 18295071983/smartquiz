package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Git 凭据管理（2026-11-16）：把 push/pull 需要的用户名与 token 存进
 * {@link EncryptedSharedPreferences}，模型只传"凭据别名"，永远看不到明文。
 *
 * <p><b>为什么这么做</b>：Agent 现在能执行 shell、能提交代码，如果 push 需要把 token
 * 放在工具参数里，等于每次调用都把密钥写进对话记录与日志。所以：</p>
 * <ul>
 *   <li>用户在设置里保存凭据（别名 → 用户名 + token），落盘走 AES256-GCM 加密；</li>
 *   <li>模型调用 git push 时只传 {@code credential: "github"} 这样的别名；</li>
 *   <li>取不到加密存储时（个别 ROM/降级）回退普通 Prefs 并打日志 —— 宁可可用也不要崩，
 *       但会在日志里明确标记"未加密"。</li>
 * </ul>
 *
 * <p>SSH 私钥暂不支持：JGit 需要额外 sshd/jsch 工厂，且私钥解析面更大；
 * 当前版本对 ssh:// 地址直接返回明确错误，避免模型误以为能推。</p>
 */
public final class GitCredentials {

    private static final String PREF_FILE = "git_credentials_enc";
    private static final String PREF_FILE_FALLBACK = "git_credentials";
    private static final String KEY_PREFIX = "cred_";

    private GitCredentials() {
    }

    /** 保存一份凭据（别名 → 用户名 + token）。已存在则覆盖。 */
    public static void save(Context context, String alias, String username, String token) {
        if (context == null || alias == null || alias.trim().isEmpty()) {
            return;
        }
        SharedPreferences prefs = prefs(context);
        prefs.edit()
                .putString(KEY_PREFIX + alias + "_user", username == null ? "" : username)
                .putString(KEY_PREFIX + alias + "_token", token == null ? "" : token)
                .apply();
    }

    /** 读取一份凭据；不存在返回 null。 */
    public static Credential load(Context context, String alias) {
        if (context == null || alias == null) {
            return null;
        }
        SharedPreferences prefs = prefs(context);
        String user = prefs.getString(KEY_PREFIX + alias + "_user", null);
        String token = prefs.getString(KEY_PREFIX + alias + "_token", null);
        if (token == null || token.isEmpty()) {
            return null;
        }
        return new Credential(alias, user == null ? "" : user, token);
    }

    /** 列出所有别名（不含密钥本身，供 UI 展示）。 */
    public static Map<String, String> listAliases(Context context) {
        Map<String, String> out = new LinkedHashMap<>();
        if (context == null) {
            return out;
        }
        SharedPreferences prefs = prefs(context);
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String key = e.getKey();
            if (key.startsWith(KEY_PREFIX) && key.endsWith("_user")) {
                String alias = key.substring(KEY_PREFIX.length(), key.length() - "_user".length());
                out.put(alias, String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    /** 删除一份凭据。 */
    public static void remove(Context context, String alias) {
        if (context == null || alias == null) {
            return;
        }
        prefs(context).edit()
                .remove(KEY_PREFIX + alias + "_user")
                .remove(KEY_PREFIX + alias + "_token")
                .apply();
    }

    // ------------------------------------------------------------------

    /** 取加密存储；失败回退普通存储（并打日志标记未加密）。 */
    private static SharedPreferences prefs(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context,
                    PREF_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Throwable t) {
            android.util.Log.w("GitCredentials",
                    "加密存储不可用，回退普通 Prefs（未加密！）: " + t.getMessage());
            return context.getSharedPreferences(PREF_FILE_FALLBACK, Context.MODE_PRIVATE);
        }
    }

    /** 一份凭据（别名 + 用户名 + token）。 */
    public static final class Credential {
        public final String alias;
        public final String username;
        public final String token;

        Credential(String alias, String username, String token) {
            this.alias = alias;
            this.username = username;
            this.token = token;
        }

        /** 转成 JGit 的凭据提供者。 */
        public org.eclipse.jgit.transport.CredentialsProvider toProvider() {
            return new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(username, token);
        }

        @Override
        public String toString() {
            // 故意不输出 token：避免进日志/对话
            return "Credential{" + alias + ", user=" + username + ", token=***}";
        }
    }
}
