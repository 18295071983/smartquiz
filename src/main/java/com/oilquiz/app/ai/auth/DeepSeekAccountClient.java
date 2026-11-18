package com.oilquiz.app.ai.auth;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.oilquiz.app.ai.agent.online.mcp.McpLoopbackReceiver;
import com.oilquiz.app.ai.agent.online.mcp.McpOAuthPending;
import com.oilquiz.app.ai.agent.online.mcp.McpPkce;
import com.oilquiz.app.util.AILogger;
import com.oilquiz.app.ai.util.ApiKeyCipher;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.TimeZone;
import java.util.UUID;

/**
 * DeepSeek 账号登录（B 方案，2026-11-18）——手机原生走 DSH 同一套授权协议。
 *
 * <p>协议（与 DSH `dsh-deepseek-account` 一致）：</p>
 * <pre>
 * POST platform.deepseek.com/auth-api/v0/dsh/auth_init
 *   {code_challenge, code_challenge_method:S256, state, redirect_uri, locale, login_source}
 *   → {authorize_url, authorize_id, expires_in}
 * 浏览器打开 authorize_url → 登录/授权 → 重定向到 redirect_uri?code&state
 * POST .../auth_exchange
 *   {code, code_verifier, redirect_uri, device_id, device_model, os_version}
 *   → {token, authorized_url, user}
 * </pre>
 *
 * <p>回调复用 MCP OAuth 的 loopback 接收器（127.0.0.1 临时端口 + state 校验），
 * token 用 Keystore（{@link ApiKeyCipher}）加密后存 ai_prefs；请求 api.deepseek.com 时
 * 走 {@code x-dsh-auth-token} 头（见 {@link #KEY_PREFIX}），不是 Bearer。
 * 过期即重新登录（DSH 同样没有 refresh token）。</p>
 */
@Deprecated
public final class DeepSeekAccountClient {

    private static final String TAG = "DeepSeekAccount";
    private static final String PLATFORM = "https://platform.deepseek.com";
    private static final String API = PLATFORM + "/auth-api/v0/dsh/";
    /** 推理基址：模型配置里的 apiUrl 用它。 */
    public static final String INFERENCE_BASE = "https://api.deepseek.com";
    /** 存在 apiKey 里的前缀：表示"这是账号 token，发 x-dsh-auth-token 头"。 */
    public static final String KEY_PREFIX = "dsh_acct:";

    private static final String PREF = "ai_prefs";
    private static final String K_TOKEN = "deepseek_account_token";
    private static final String K_USER = "deepseek_account_user";
    private static final String K_DEVICE = "deepseek_account_device_id";
    /** 客户端版本号：平台按 DSH 客户端识别；升级 DSH 时可同步。 */
    private static final String CLIENT_VERSION = "0.2.2-rc.3";

    private DeepSeekAccountClient() {
    }

    /** 登录回调（主线程）。 */
    public interface Listener {
        void onSuccess(String token, String userJson);

        void onError(String message);
    }

    public static boolean isSignedIn(Context c) {
        String t = token(c);
        return !t.isEmpty();
    }

    /** 读取解密后的 token；没有/解密失败返回空串。 */
    public static String token(Context c) {
        try {
            String enc = prefs(c).getString(K_TOKEN, "");
            if (TextUtils.isEmpty(enc)) return "";
            String t = ApiKeyCipher.decrypt(enc);
            return t == null ? "" : t;
        } catch (Throwable t) {
            return "";
        }
    }

    public static String userJson(Context c) {
        return prefs(c).getString(K_USER, "");
    }

    public static void signOut(Context c) {
        prefs(c).edit().remove(K_TOKEN).remove(K_USER).apply();
    }

    /** 账号 token → 给模型配置用的 apiKey（带前缀，ProviderConfigManager 据此换 header）。 */
    public static String apiKeyOf(Context c) {
        String t = token(c);
        return t.isEmpty() ? "" : KEY_PREFIX + t;
    }

    /**
     * 发起一次浏览器授权：绑定 loopback → auth_init → 打开浏览器 → 等回调 → auth_exchange → 落盘。
     * 阻塞在后台线程，回调切主线程。
     */
    public static void startLogin(Context context, Listener listener) {
        final Context app = context.getApplicationContext();
        final Activity activity = findActivity(context);
        final Handler main = new Handler(Looper.getMainLooper());
        if (activity == null) {
            main.post(() -> listener.onError("需要一个前台页面才能打开登录"));
            return;
        }
        new Thread(() -> {
            McpLoopbackReceiver receiver = null;
            String state = null;
            try {
                receiver = McpLoopbackReceiver.open();
                if (receiver == null) {
                    fail(main, listener, "本机回调端口没绑上，稍后重试");
                    return;
                }
                // 与 DSH 一致的回调路径：平台侧对 /oauth/callback 有单独处理
                String redirect = receiver.redirectUri("/oauth/callback");
                String verifier = McpPkce.newVerifier();
                state = McpPkce.newState();
                String challenge = McpPkce.challengeOf(verifier);
                if (!McpOAuthPending.begin(McpOAuthPending.waiter(state, "deepseek-account",
                        verifier, redirect))) {
                    fail(main, listener, "已有一条授权在等浏览器回调，先完成或取消它");
                    return;
                }
                receiver.start(state);

                JSONObject initBody = new JSONObject();
                initBody.put("code_challenge", challenge);
                initBody.put("code_challenge_method", "S256");
                initBody.put("state", state);
                initBody.put("redirect_uri", redirect);
                initBody.put("locale", "zh_CN");
                initBody.put("login_source", "web");
                JSONObject init = post(API + "auth_init", initBody);
                String authorizeUrl = init.optString("authorize_url", "");
                if (authorizeUrl.isEmpty()) {
                    fail(main, listener, "平台没返回授权地址: " + init);
                    return;
                }
                final String url = authorizeUrl;
                main.post(() -> {
                    try {
                        Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        app.startActivity(it);
                    } catch (Throwable t) {
                        AILogger.e(TAG, "打开浏览器失败: " + t.getMessage());
                    }
                });

                McpOAuthPending.Result result = McpOAuthPending.awaitResult(state, 5 * 60_000L);
                if (result == null) {
                    fail(main, listener, "等浏览器回调超时（5 分钟）");
                    return;
                }
                if (result.hasError()) {
                    fail(main, listener, "授权被拒绝: " + result.error
                            + (result.errorDescription.isEmpty() ? "" : " " + result.errorDescription));
                    return;
                }
                if (!result.hasCode()) {
                    fail(main, listener, "回调里没有授权码");
                    return;
                }

                JSONObject exBody = new JSONObject();
                exBody.put("code", result.code);
                exBody.put("code_verifier", verifier);
                exBody.put("redirect_uri", redirect);
                exBody.put("device_id", deviceId(app));
                exBody.put("device_model", "android-" + firstAbi());
                exBody.put("os_version", Build.VERSION.RELEASE);
                JSONObject ex = post(API + "auth_exchange", exBody);
                String token = ex.optString("token", "");
                if (token.isEmpty()) {
                    fail(main, listener, "兑换失败: " + ex);
                    return;
                }
                String user = ex.optJSONObject("user") == null ? "" : ex.optJSONObject("user").toString();
                boolean saved;
                try {
                    prefs(app).edit()
                            .putString(K_TOKEN, ApiKeyCipher.encrypt(token))
                            .putString(K_USER, user)
                            .apply();
                    saved = true;
                } catch (Throwable t) {
                    saved = false;
                }
                if (!saved) {
                    fail(main, listener, "token 没能加密落盘（Keystore 不可用）");
                    return;
                }
                AILogger.audit(TAG, "DeepSeek 账号登录成功");
                main.post(() -> listener.onSuccess(token, user));
            } catch (Throwable t) {
                AILogger.e(TAG, "登录失败: " + t.getMessage(), t);
                fail(main, listener, "登录失败: " + t.getMessage());
            } finally {
                if (state != null) McpOAuthPending.finish(state);
                if (receiver != null) receiver.close();
            }
        }, "deepseek-account-login").start();
    }

    // ==================== HTTP ====================

    private static JSONObject post(String url, JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(30_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("content-type", "application/json");
            for (java.util.Map.Entry<String, String> e : clientHeaders().entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(in);
            if (code < 200 || code >= 300) {
                AILogger.w(TAG, "POST " + url + " → HTTP " + code + " " + trim(text));
                throw new IllegalStateException("HTTP " + code + " " + trim(text));
            }
            AILogger.i(TAG, "POST " + url + " → HTTP " + code + " " + trim(text));
            JSONObject obj = new JSONObject(text);
            JSONObject data = obj.optJSONObject("data");
            if (data != null) {
                JSONObject biz = data.optJSONObject("biz_data");
                if (biz != null) return biz;
                return data;
            }
            return obj;
        } finally {
            conn.disconnect();
        }
    }

    private static java.util.Map<String, String> clientHeaders() {
        java.util.Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put("x-client-bundle-id", "");
        h.put("x-client-platform", "web");
        h.put("x-client-version", CLIENT_VERSION);
        h.put("x-client-locale", "zh_CN");
        int minutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000;
        h.put("x-client-timezone-offset", String.valueOf(minutes));
        // 2026-11-18：平台 WAF 会拦"没有浏览器特征"的请求（实测 curl 缺 UA 直接 429
        // Request Blocked）。这两个头不是鉴权，只是让请求看起来像平台网页发出的。
        h.put("accept", "application/json");
        h.put("user-agent", "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + ") "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");
        h.put("origin", PLATFORM);
        h.put("referer", PLATFORM + "/");
        return h;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    // ==================== 工具 ====================

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String deviceId(Context c) {
        String id = prefs(c).getString(K_DEVICE, "");
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs(c).edit().putString(K_DEVICE, id).apply();
        }
        return id;
    }

    private static String firstAbi() {
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            return abis != null && abis.length > 0 ? abis[0] : "arm64";
        } catch (Throwable t) {
            return "arm64";
        }
    }

    private static Activity findActivity(Context ctx) {
        Context c = ctx;
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        try {
            return com.oilquiz.app.SmartQuizApplication.getCurrentActivity();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void fail(Handler main, Listener l, String msg) {
        main.post(() -> l.onError(msg));
    }
}
