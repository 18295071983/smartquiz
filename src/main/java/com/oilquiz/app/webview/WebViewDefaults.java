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
}
