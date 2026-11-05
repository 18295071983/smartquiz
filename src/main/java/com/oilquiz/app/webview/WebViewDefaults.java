package com.oilquiz.app.webview;

/**
 * WebView 全局默认值。
 *
 * 背景：Android WebView 默认 UA 带 "Version/4.0 ... wv" 标记，部分站点据此判断
 * "不是正常浏览器" 并拒绝下发/读取 Cookie（表现为页面 JS 的 document.cookie 被拒绝、
 * 登录态不生效）。统一伪装为桌面级 Chrome UA，让应用内浏览器与正常 Chrome 表现一致。
 */
public final class WebViewDefaults {

    private WebViewDefaults() {
    }

    /** 伪装 UA：Chrome 143 Mobile（与正常安卓 Chrome 一致，无 wv 标记） */
    public static final String CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; SM-G998U) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36";

    /**
     * 打开 Cookie 持久化与第三方 Cookie（所有会加载远程内容的 WebView 宿主统一调这一处）。
     *
     * <p>{@code setAcceptCookie} 是进程级开关、默认就开着，容易被忽略的是**第三方 Cookie 按
     * WebView 关着**：扫码登录 iframe、跨域 OAuth 回调、单点登录跳转都在写第一方之外的 Cookie，
     * 不显式打开就表现为"登录了，跳回来又掉线"。UA 伪装解决的是站点拒绝下发 Cookie，
     * 这条解决的是 Cookie 被拦在第三方之外，两者是不同的坑，都要开。</p>
     *
     * <p>幂等：回收/复用时开关不会自动丢，但池里的 WebView 会被不同宿主取走，所以每个宿主
     * 装好都设一遍。</p>
     */
    public static void enableCookiePersistence(android.webkit.WebView webView) {
        if (webView == null) return;
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (android.os.Build.VERSION.SDK_INT
                    >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(webView, true);
            }
        } catch (Throwable t) {
            android.util.Log.w("WebViewDefaults", "Cookie 策略设置失败: " + t.getMessage());
        }
    }
}
