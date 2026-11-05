package com.oilquiz.app.browser;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.net.http.SslError;
import android.text.InputType;
import android.view.View;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.util.ApiKeyCipher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 浏览器鉴权：HTTP Basic/Digest 挑战、HTTPS 证书错误、站点账号密码的处置与存放。
 *
 * <p>这两件事此前在仓库里<b>一处都没实现</b>：内核默认行为是"没人接就取消"，所以访问路由器后台、
 * NAS、内网系统这类要 401 认证的站点时不是弹框输密码，而是一片空白；自签证书站点则直接被拒，
 * 用户连"看一眼证书再决定"的机会都没有。系统浏览器会给输入框和证书警告页，内置浏览器不给，
 * 看起来就像"浏览器坏了"。</p>
 *
 * <p>三条刻意保守的口径：</p>
 * <ul>
 *   <li><b>账号密码默认不落盘</b>——只有勾了"记住"才写，写的是 Keystore AES-GCM 密文；
 *       Keystore 不可用时宁可不存也不落明文。</li>
 *   <li><b>证书例外只对当次导航有效</b>——不做"以后这个站都放行"的永久开关，避免一次手滑把
 *       中间人攻击常态化。</li>
 *   <li><b>没有 Activity 就拒绝而不是猜</b>——非界面上下文弹不出框，此时 proceed 等于静默放行，
 *       所以取消并记日志。</li>
 * </ul>
 */
public final class BrowserAuthController {

    private static final String TAG = "BrowserAuthController";
    private static final String PREFS = "browser_auth";
    private static final String KEY_PREFIX = "cred:";

    private BrowserAuthController() {
    }

    // ==================== HTTP Basic / Digest 挑战 ====================

    /** 站点要求认证（401/407）：有已保存的账号就用，没有就问用户 */
    public static void handleHttpAuth(WebView view, HttpAuthHandler handler,
                                      String host, String realm) {
        if (view == null || handler == null) return;
        Context context = view.getContext();
        String key = credentialKey(host, realm);
        String[] saved = credentials(context).get(key);
        if (saved != null && saved.length == 2 && !saved[0].isEmpty()) {
            // 已保存的直接续上，别再打扰用户（系统浏览器同样是静默重放 Authorization）
            android.util.Log.i(TAG, "使用已保存的凭据: " + key);
            handler.proceed(saved[0], saved[1]);
            return;
        }
        if (!(context instanceof Activity)) {
            android.util.Log.w(TAG, "无界面上下文，取消认证请求: " + key);
            handler.cancel();
            return;
        }
        Activity activity = (Activity) context;
        if (activity.isFinishing() || activity.isDestroyed()) {
            handler.cancel();
            return;
        }

        final EditText user = new EditText(activity);
        user.setHint(R.string.browser_auth_user);
        user.setInputType(InputType.TYPE_CLASS_TEXT);
        user.setSingleLine(true);
        final EditText pass = new EditText(activity);
        pass.setHint(R.string.browser_auth_password);
        pass.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setSingleLine(true);
        final CheckBox remember = new CheckBox(activity);
        remember.setText(R.string.browser_auth_remember);
        remember.setChecked(false); // 落盘必须是显式选择

        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);
        form.addView(user);
        form.addView(pass);
        form.addView(remember);

        final String shownHost = host == null || host.isEmpty()
                ? activity.getString(R.string.browser_auth_host_unknown) : host;
        final String shownRealm = realm == null || realm.isEmpty()
                ? activity.getString(R.string.browser_auth_host_unknown) : realm;
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.browser_auth_title)
                .setMessage(activity.getString(R.string.browser_auth_message, shownHost, shownRealm))
                .setView(form)
                .setPositiveButton(R.string.browser_auth_login, (dialog, which) -> {
                    String u = user.getText().toString().trim();
                    String p = pass.getText().toString();
                    if (u.isEmpty()) {
                        toast(activity, R.string.browser_auth_need_user);
                        handler.cancel();
                        return;
                    }
                    handler.proceed(u, p);
                    if (remember.isChecked()) {
                        saveCredentials(activity, key, u, p);
                    }
                })
                .setNegativeButton(R.string.browser_cancel, (dialog, which) -> handler.cancel())
                .setOnCancelListener(dialog -> handler.cancel())
                .show();
    }

    // ==================== HTTPS 证书错误 ====================

    /** 证书校验失败：列清原因，用户显式选择"仅本次继续"，不留永久例外 */
    public static void handleSslError(WebView view, SslErrorHandler handler, SslError error) {
        if (view == null || handler == null) return;
        Context context = view.getContext();
        if (!(context instanceof Activity)) {
            android.util.Log.w(TAG, "无界面上下文，拒绝证书异常页面");
            handler.cancel();
            return;
        }
        Activity activity = (Activity) context;
        if (activity.isFinishing() || activity.isDestroyed()) {
            handler.cancel();
            return;
        }
        int code = error != null ? error.getPrimaryError() : -1;
        String url = error != null ? error.getUrl() : null;
        String host = BrowserUrlNormalizer.hostOf(url == null ? "" : url);
        if (host.isEmpty()) host = activity.getString(R.string.browser_auth_host_unknown);
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.browser_ssl_title)
                .setMessage(activity.getString(R.string.browser_ssl_message,
                        host, code, certificateFacts(error, System.currentTimeMillis())))
                .setPositiveButton(R.string.browser_ssl_continue_once,
                        (dialog, which) -> handler.proceed())
                .setNegativeButton(R.string.browser_cancel, (dialog, which) -> handler.cancel())
                .setOnCancelListener(dialog -> handler.cancel())
                .show();
    }

    /**
     * 从 {@link SslError} 里能**确证**的事实拼一句说明。
     *
     * <p>{@code SslError.ERROR_*} 那组常量不在公开 SDK 里（拿 android-36 的 android.jar 验过：
     * 类里只有 getPrimaryError/getUrl/getCertificate，没有那些字段），所以不去反推"4=不受信任"
     * 这种含义，只报原始码。证书的主体/签发者/有效期是公开 API（{@code getIssuedTo}、
     * {@code getValidNotAfterDate}），过期与还没生效这两条是能算出来的事实，可以老实写出来。</p>
     */
    public static String certificateFacts(SslError error, long nowMillis) {
        if (error == null) return "";
        try {
            android.net.http.SslCertificate cert = error.getCertificate();
            if (cert == null) return "";
            String issuedTo = cert.getIssuedTo() != null ? cert.getIssuedTo().getCName() : "";
            String issuedBy = cert.getIssuedBy() != null ? cert.getIssuedBy().getCName() : "";
            java.util.Date notBefore = null;
            java.util.Date notAfter = null;
            try {
                notBefore = cert.getValidNotBeforeDate();
                notAfter = cert.getValidNotAfterDate();
            } catch (Throwable ignored) {
            }
            return describeCertificate(issuedTo, issuedBy,
                    notBefore == null ? 0L : notBefore.getTime(),
                    notAfter == null ? 0L : notAfter.getTime(),
                    nowMillis);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "读取证书信息失败: " + t.getMessage());
            return "";
        }
    }

    /**
     * 证书事实的纯文本版（可单测）：主体／签发者／有效期，外加"已过期""还没生效"两条能算出来的结论。
     *
     * @param notBeforeOrNotAfter 传 0 表示这项取不到，直接不写，不拿 1970 当日期显示
     */
    public static String describeCertificate(String issuedTo, String issuedBy,
                                            long notBefore, long notAfter, long now) {
        StringBuilder sb = new StringBuilder();
        if (issuedTo != null && !issuedTo.trim().isEmpty()) {
            sb.append("主体：").append(issuedTo.trim()).append('\n');
        }
        if (issuedBy != null && !issuedBy.trim().isEmpty()) {
            sb.append("签发者：").append(issuedBy.trim()).append('\n');
        }
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
        if (notBefore > 0 && notAfter > 0) {
            sb.append("有效期：").append(sdf.format(new java.util.Date(notBefore)))
                    .append(" ~ ").append(sdf.format(new java.util.Date(notAfter))).append('\n');
        }
        if (notAfter > 0 && now > notAfter) {
            sb.append("已过期 ").append(daysBetween(notAfter, now)).append(" 天\n");
        } else if (notBefore > 0 && now < notBefore) {
            sb.append("还没生效（设备时间或签发日期不对）\n");
        }
        return sb.toString().trim();
    }

    private static long daysBetween(long fromMillis, long toMillis) {
        long days = (toMillis - fromMillis) / (24L * 60 * 60 * 1000);
        return days > 0 ? days : 1;
    }

    // ==================== 凭据存放（显式记住才落盘） ====================

    private static void saveCredentials(Context context, String key, String user, String pass) {
        if (!ApiKeyCipher.keystoreAvailable()) {
            // 存不了密文就绝不存：明文账号密码落进 prefs 等于把凭据摊在 /data/data 里
            toast(context, R.string.browser_auth_no_keystore);
            android.util.Log.w(TAG, "Keystore 不可用，未保存站点密码");
            return;
        }
        try {
            String cipher = ApiKeyCipher.encrypt(user + "\n" + pass);
            if (cipher == null || cipher.isEmpty()) {
                toast(context, R.string.browser_auth_no_keystore);
                return;
            }
            credentials(context).put(key, cipher);
            toast(context, R.string.browser_auth_saved);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "保存站点凭据失败: " + t.getMessage());
        }
    }

    /** 凭据仓库：prefs 里只存密文，key 用 {@code host|realm} */
    private static Store credentials(Context context) {
        Context app = context != null && context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        return new Store(app);
    }

    private static final class Store {
        private final Context context;

        Store(Context context) {
            this.context = context;
        }

        private SharedPreferences prefs() {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }

        /** @return {@code [user, pass]}；没有或解不开返回 null（解不开绝不退回当明文用） */
        String[] get(String key) {
            String stored = prefs().getString(KEY_PREFIX + key, null);
            if (stored == null || stored.isEmpty()) return null;
            String plain = ApiKeyCipher.decrypt(stored);
            if (plain == null || plain.isEmpty()) return null;
            int split = plain.indexOf('\n');
            if (split < 0) return null;
            return new String[]{plain.substring(0, split), plain.substring(split + 1)};
        }

        void put(String key, String cipherValue) {
            prefs().edit().putString(KEY_PREFIX + key, cipherValue).apply();
        }

        Set<String> keys() {
            Set<String> out = new LinkedHashSet<>();
            for (Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
                String k = entry.getKey();
                if (k != null && k.startsWith(KEY_PREFIX)) out.add(k.substring(KEY_PREFIX.length()));
            }
            return out;
        }

        void remove(String key) {
            prefs().edit().remove(KEY_PREFIX + key).apply();
        }

        void clear() {
            SharedPreferences.Editor editor = prefs().edit();
            for (String key : keys()) editor.remove(KEY_PREFIX + key);
            editor.apply();
        }
    }

    /** 已保存凭据的站点列表（管理页与"共几条"提示用） */
    public static List<String> listSavedSites(Context context) {
        return new ArrayList<>(credentials(context).keys());
    }

    public static void forgetSite(Context context, String key) {
        credentials(context).remove(key);
    }

    /** 清除浏览数据时一并抹掉站点密码 */
    public static void clearAll(Context context) {
        credentials(context).clear();
    }

    // ==================== 纯判定（可单测） ====================

    /**
     * 凭据 key：{@code host|realm}。
     *
     * <p>host 小写（域名不区分大小写），realm <b>保留原样</b>——按 RFC 2617 realm 是服务器给的
     * 不透明字符串、区分大小写，小写化会把两个不同保护域混成一个，于是拿 A 域的密码去试 B 域。</p>
     */
    public static String credentialKey(String host, String realm) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.US);
        String r = realm == null ? "" : realm.trim();
        return r.isEmpty() ? h : h + "|" + r;
    }

    private static void toast(Context context, int resId) {
        try {
            Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    /** 供"清除浏览数据"以外的入口复用：管理已保存站点 */
    public static void showSavedSitesDialog(Activity activity, Runnable onAfterChange) {
        List<String> sites = listSavedSites(activity);
        if (sites.isEmpty()) {
            toast(activity, R.string.browser_auth_none);
            return;
        }
        final String[] items = sites.toArray(new String[0]);
        new android.app.AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.browser_auth_manage_title, items.length))
                .setItems(items, (dialog, which) -> {
                    forgetSite(activity, items[which]);
                    toast(activity, R.string.browser_auth_forgotten);
                    if (onAfterChange != null) onAfterChange.run();
                })
                .setNeutralButton(R.string.browser_auth_clear_all, (dialog, which) -> {
                    clearAll(activity);
                    toast(activity, R.string.browser_auth_all_cleared);
                    if (onAfterChange != null) onAfterChange.run();
                })
                .setNegativeButton(R.string.browser_cancel, (DialogInterface.OnClickListener) null)
                .show();
    }
}
