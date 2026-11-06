package com.oilquiz.app.webview.security;

import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * 安全 WebViewClient：只做"该拦的拦"，协议转交一律委托
 * {@link com.oilquiz.app.browser.BrowserSchemeHandler}。
 *
 * <p>这个类曾经自己写了一套非标准协议处置（{@code resolveActivity} 能解析就 startActivity，
 * 解析不到就 toast“系统没有能打开此链接的应用”），于是同一台手机上：内置浏览器能交系统浏览器，
 * 走这个 client 的页面却只能看到一句"没有应用"。现在口径只有一处——危险协议（脚本/内联文档）
 * 拦截，{@code http(s)/file} 交 WebView，其余全部走统一转交（有应用开应用、没装应用交
 * 系统浏览器、再不行复制链接）。带登录态的明文 HTTP 只提示不拦截（内网系统要能用）。</p>
 *
 * <p>接入方式：内置浏览器的 {@code RedirectWebViewClient} 继承本类，主框架与子框架的协议
 * 判定都先过这一层；其它宿主也可以直接 new 本类当基座。别在宿主里再写第二套 resolveActivity
 * / toast——那会重新长出"有的入口能开、有的入口只提示没有应用"。</p>
 */
public class SecurityWebViewClient extends WebViewClient {
    private static final String TAG = "SecurityWebViewClient";

    // 危险协议：脚本与内联文档一律不执行（值是不带冒号的 scheme 名）
    private static final Set<String> DANGEROUS_SCHEMES = new java.util.HashSet<>();
    
    // 应用内部协议
    private static final Set<String> APP_SCHEMES = new java.util.HashSet<>();

    /** 已提示过"明文 HTTP 带登录态"的 host：同站点重复导航不再反复弹提示 */
    private final Set<String> warnedPlainHttpHosts = new java.util.HashSet<>();
        
    // 自定义协议处理器
    private CustomSchemeHandler schemeHandler;

    static {
        // 危险协议
        DANGEROUS_SCHEMES.add("javascript");
        DANGEROUS_SCHEMES.add("data");
        
        // 应用内部协议（如果有的话）
        // APP_SCHEMES.add("oilquiz");
    }

    public SecurityWebViewClient() {
    }

    /**
     * 设置自定义协议处理器
     */
    public void setCustomSchemeHandler(@Nullable CustomSchemeHandler handler) {
        this.schemeHandler = handler;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            return true; // 拿不到目标地址：不让内核继续（与原 null 口径一致）
        }
        return handleUrlLoading(view, request.getUrl().toString(),
                request.getUrl().getScheme(), request.isForMainFrame());
    }


    /**
     * 处理 URL 加载
     *
     * @param mainFrame 主框架导航。{@code data:} 只在主框架拦（子框架内联帧放行）；
     *                  "带登录态的明文 HTTP"只对主框架提示（同 host 一次），不拦截
     */
    private boolean handleUrlLoading(WebView view, String url, String scheme, boolean mainFrame) {
        if (url == null) {
            return true; // 阻止加载 null URL
        }
        String lower = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);

        // 1. 危险协议：脚本不执行；data: 主框架导航拦（Chrome 同口径），子框架内联帧放行
        if (DANGEROUS_SCHEMES.contains(lower)) {
            if (!"data".equals(lower) || mainFrame) {
                Log.w(TAG, "拦截危险协议: " + url);
                return true;
            }
            return false;
        }

        // 2. 检查应用内部协议
        if (APP_SCHEMES.contains(lower)) {
            Log.d(TAG, "处理应用内部协议: " + url);
            if (schemeHandler != null) {
                return schemeHandler.handleScheme(view, url);
            }
            return true;
        }

        // 3. 明文 HTTP + 登录态域名 → 只警告、不拦截。
        //    内网 http 系统（NAS/路由器/自建后台）恰恰最常带登录态，硬拦会把它们打成打不开；
        //    但要让用户知道"登录 Cookie 会随明文请求裸传"，由他自己决定要不要改用 https。
        if ("http".equals(lower) && mainFrame) {
            try {
                boolean hasLogin = com.oilquiz.app.webview.AppCookieStore.getInstance().hasLogin(url);
                if (hasLogin) {
                    Log.w(TAG, "明文 HTTP 承载登录态（仅警告，不拦截）: " + url);
                    warnPlainHttp(view, url);
                }
            } catch (Throwable t) {
                Log.w(TAG, "明文 HTTP 登录态检查异常: " + t.getMessage());
            }
        }

        // 4. 非 http(s)/file：统一委托协议转交层（含 intent://）
        //    有应用就打开；没装应用交系统浏览器；连浏览器都不行才复制链接并说明。
        if (!lower.isEmpty() && !lower.startsWith("http") && !"file".equals(lower)) {
            if (com.oilquiz.app.browser.BrowserSchemeHandler.handleNavigation(view, url, true)) {
                Log.d(TAG, "协议已转交: " + lower + " - " + url);
                return true;
            }
            // 不是"该转交的协议"（about:/blob:/content: 等页内资源）：交宿主决定，
            // 由具体客户端按能力加载——这里不替宿主做"一律拦截"的决定。
            return false;
        }

        // 5. 标准 HTTP/HTTPS/file URL，让 WebView 正常处理
        return false;
    }

    /**
     * 明文 HTTP 承载登录态：提示一次（同 host 只提示一次），<b>不阻断</b>导航。
     *
     * <p>为什么不是拦截：这类站点多为内网 http 系统（NAS、路由器、自建后台），登录态本来就是
     * 在这套明文环境里产生的；硬拦等于把用户自己的系统锁在门外。真实风险（Cookie 随明文请求
     * 裸传）通过提示告知，改不改由用户决定。</p>
     */
    private void warnPlainHttp(WebView view, String url) {
        String host = hostOf(url);
        synchronized (warnedPlainHttpHosts) {
            if (!warnedPlainHttpHosts.add(host)) return;
        }
        try {
            // 用主线程 Handler 而不是 view.post：后者在 WebView 尚未 attach 时只会进
            // RunQueue（等 attach 才执行），而这条提示必须在导航当下就出现。
            final android.content.Context toastCtx = view.getContext();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    android.widget.Toast.makeText(toastCtx,
                            "注意：该站点存有登录 Cookie，当前是明文 HTTP，登录态可能被窃听，建议改用 https://",
                            android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static String hostOf(String url) {
        try {
            String host = android.net.Uri.parse(url).getHost();
            return host == null ? url : host.toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return url;
        }
    }

    /**
     * 自定义协议处理器接口
     */
    public interface CustomSchemeHandler {
        /**
         * 处理自定义协议
         * @return true 如果已处理，false 让 WebView 继续处理
         */
        boolean handleScheme(WebView view, String url);
    }
}
