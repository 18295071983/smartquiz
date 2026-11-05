package com.oilquiz.app.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.WebSettings;

import com.oilquiz.app.webview.WebViewDefaults;

/**
 * 内置浏览器偏好：桌面版 UA、文字缩放。
 *
 * <p>UA 统一走 {@link WebViewDefaults#CHROME_USER_AGENT}（无 wv 标记），
 * 桌面版只替换 UA 字符串，不动 viewport —— 由站点自己的响应式布局决定。</p>
 */
public final class BrowserSettings {

    private static final String PREFS = "browser_settings";
    private static final String KEY_DESKTOP = "desktop_mode";
    private static final String KEY_TEXT_ZOOM = "text_zoom";
    private static final String KEY_SCHEME_POLICY = "unknown_scheme_policy";

    public static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";

    private static volatile BrowserSettings instance;

    private final SharedPreferences prefs;

    private BrowserSettings(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static BrowserSettings get(Context context) {
        if (instance == null) {
            synchronized (BrowserSettings.class) {
                if (instance == null) {
                    instance = new BrowserSettings(context);
                }
            }
        }
        return instance;
    }

    public boolean isDesktopMode() {
        return prefs.getBoolean(KEY_DESKTOP, false);
    }

    public void setDesktopMode(boolean enabled) {
        prefs.edit().putBoolean(KEY_DESKTOP, enabled).apply();
    }

    public int getTextZoom() {
        int zoom = prefs.getInt(KEY_TEXT_ZOOM, 100);
        return Math.max(70, Math.min(200, zoom));
    }

    public void setTextZoom(int zoom) {
        prefs.edit().putInt(KEY_TEXT_ZOOM, Math.max(70, Math.min(200, zoom))).apply();
    }

    /** 把当前偏好应用到某个 WebView 的 settings */
    public void apply(WebSettings settings) {
        if (settings == null) return;
        settings.setUserAgentString(isDesktopMode() ? DESKTOP_USER_AGENT : WebViewDefaults.CHROME_USER_AGENT);
        settings.setTextZoom(getTextZoom());
    }

    /**
     * 未知协议的处理策略：{@code ask}（每次询问，默认）/ {@code system}（直接交给应用）/
     * {@code browser}（交给系统浏览器）。
     *
     * <p>默认必须是"问"：网页能凭空把用户送出浏览器，自动跳转就等于给钓鱼链接开了绿灯；
     * 想要省事的人在菜单里显式改档，改的是设备偏好而不是代码默认值。</p>
     */
    public String getUnknownSchemePolicy() {
        return BrowserScheme.normalizePolicy(prefs.getString(KEY_SCHEME_POLICY, BrowserScheme.POLICY_ASK));
    }

    public void setUnknownSchemePolicy(String policy) {
        prefs.edit().putString(KEY_SCHEME_POLICY, BrowserScheme.normalizePolicy(policy)).apply();
    }

    /** 策略是否已是该档（菜单勾选态用） */
    public boolean isSchemePolicy(String policy) {
        return getUnknownSchemePolicy().equals(BrowserScheme.normalizePolicy(policy));
    }
}
