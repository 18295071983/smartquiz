package com.oilquiz.app.browser;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebView;
import android.widget.Toast;

import com.oilquiz.app.R;

import java.util.List;
import java.util.Locale;

/**
 * 未知/自定义 scheme 的**执行层**：把浏览器里点出来的协议地址交出去，而不是甩一张"页面加载失败"。
 *
 * <p>三条入口：</p>
 * <ul>
 *   <li>{@link #handleNavigation} —— {@code shouldOverrideUrlLoading}，页面点「打开 App」；
 *       主框架与 iframe 里的协议跳转都接管，http/https/file 一律放行不动。</li>
 *   <li>{@link #handleUnknownSchemeError} —— 表单 POST、{@code location.href} 这类**不经过
 *       回调**的导航，内核会以 {@code ERR_UNKNOWN_URL_SCHEME(-2)} 失败；旧实现把它当普通网络错误
 *       画成"请检查网络连接 + 重新加载"，用户照着点重试只会反复失败。这里改成：先把页面从错误页
 *       上退回原页面，再按协议给出路。</li>
 *   <li>{@link #handoff} —— 卡片按钮、地址栏等宿主直接请求转交。</li>
 * </ul>
 *
 * <p>安全边界：网页发起的 {@code intent://} 会补 {@code CATEGORY_BROWSABLE} 再解析（与 Chrome
 * 对 web-intent 的要求一致），并且**剥掉指向本应用自身的 component**——否则网页可以用一条
 * intent 串把内部 Activity 当自己的入口拉起来。{@code javascript:} 永不交给 WebView 执行。</p>
 */
public final class BrowserSchemeHandler {

    private static final String TAG = "BrowserSchemeHandler";

    /**
     * 内核错误码：协议不认识（Chromium {@code NET_ERROR(UNKNOWN_URL_SCHEME, -302)}）。
     *
     * <p>判据以**失败地址本身是不是协议链接**为准，这个码只作旁证：不同内核版本/不同触发路径
     * （表单 POST、{@code location.href}、iframe 跳转）报的码并不一致，抱着一个数字判会把
     * 能转交的链接漏回普通错误页。</p>
     */
    public static final int ERR_UNKNOWN_URL_SCHEME = -302;

    private BrowserSchemeHandler() {
    }

    // ==================== 导航接管 ====================

    /**
     * @param fromWeb true＝由页面/iframe 发起（补 BROWSABLE 约束）；false＝用户自己在地址栏或卡片里点
     * @return true 表示已接管，调用方**不要**再让 WebView 加载这条 URL
     */
    public static boolean handleNavigation(WebView view, String url, boolean fromWeb) {
        if (view == null || url == null || url.trim().isEmpty()) return false;
        BrowserScheme.Kind kind = BrowserScheme.classify(url);
        if (kind == BrowserScheme.Kind.WEB
                || kind == BrowserScheme.Kind.IN_PAGE
                || kind == BrowserScheme.Kind.NOT_A_SCHEME
                || kind == BrowserScheme.Kind.SCRIPT) {
            return false;
        }
        if (kind == BrowserScheme.Kind.INTENT) {
            Intent parsed = parseIntentUri(view.getContext(), url);
            if (parsed != null && openWeb(view.getContext(), view,
                    parsed.getStringExtra("browser_fallback_url"))) {
                return true;
            }
        }
        handoff(view.getContext(), url, view, fromWeb);
        return true;
    }

    /**
     * 失败地址本身是不是「该转交的协议链接」。
     *
     * <p>错误码因触发路径而异（表单 POST / {@code location.href} / iframe 各自报的码不完全一致），
     * 所以判据用 URL 本身，不用错误码。</p>
     */
    public static boolean isSchemeNavigation(String url) {
        BrowserScheme.Kind kind = BrowserScheme.classify(url);
        return kind == BrowserScheme.Kind.SYSTEM_APP || kind == BrowserScheme.Kind.INTENT;
    }

    /**
     * 协议导航失败的收尾：不要在错误页上留"请检查网络连接"——那不是网络问题。
     *
     * @param lastGoodUrl 失败前最后成功加载的地址，可为 null
     * @return true 表示已处理（调用方不必再画通用错误页）
     */
    public static boolean handleUnknownSchemeError(WebView view, String failingUrl, String lastGoodUrl) {
        if (view == null || !isSchemeNavigation(failingUrl)) {
            return false;
        }
        // 先把内核错误页顶掉：有历史就退回原页，否则回到上一个成功地址，再不行回起始页
        try {
            if (view.canGoBack()) {
                view.goBack();
            } else if (BrowserScheme.isHttp(lastGoodUrl) || lastGoodUrl != null && lastGoodUrl.startsWith("file://")) {
                view.loadUrl(lastGoodUrl);
            } else {
                view.loadUrl(BrowserUrlNormalizer.HOME_URL);
            }
        } catch (Throwable t) {
            Log.w(TAG, "错误页回退失败: " + t.getMessage());
        }
        handoff(view.getContext(), failingUrl, view, true);
        return true;
    }

    // ==================== 转交执行 ====================

    /** 无 WebView 宿主的便捷入口（卡片按钮、地址栏）。 */
    public static void handoff(Context context, String url, boolean fromWeb) {
        handoff(context, url, null, fromWeb);
    }

    /**
     * 把协议地址交出去。三档策略（浏览器菜单里可改，默认「每次询问」）：
     * <ul>
     *   <li>{@code ask}：先问用户——网页凭空把人送出浏览器是钓鱼面，所以默认要确认；
     *       但 {@code tel:/mailto:/sms:/geo:} 这类转交后本来就在系统界面里，不打扰。</li>
     *   <li>{@code system}：直接交给能处理的应用；没有则回退网页或系统选择器。</li>
     *   <li>{@code browser}：应用优先，没有应用时显式发给系统浏览器，由它按自己的协议表处理。</li>
     * </ul>
     * 询问时列出的出路来自 {@link BrowserScheme#optionsFor}，其中"打开对应网页"靠协议链接里
     * 带的回退地址（{@code browser_fallback_url}、{@code url=}、{@code link=} 等）。
     */
    public static void handoff(Context context, String url, WebView view, boolean fromWeb) {
        if (context == null || url == null || url.trim().isEmpty()) return;
        Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        BrowserScheme.Kind kind = BrowserScheme.classify(url);
        if (kind != BrowserScheme.Kind.SYSTEM_APP && kind != BrowserScheme.Kind.INTENT) {
            return;
        }
        String policy = BrowserSettings.get(appCtx).getUnknownSchemePolicy();

        Intent launch = null;
        if (kind == BrowserScheme.Kind.INTENT) {
            Intent parsed = parseIntentUri(context, url);
            if (parsed != null) {
                // intent 串自带的回退网页优先：那是站点自己声明的"没装 App 就看这个页"
                if (openWeb(context, view, parsed.getStringExtra("browser_fallback_url"))) return;
                launch = parsed;
            }
        }
        if (launch == null) {
            launch = buildViewIntent(appCtx, url, fromWeb);
        }
        if (launch == null) {
            toast(appCtx, R.string.browser_scheme_bad_url, url);
            return;
        }

        boolean appResolved = hasResolver(appCtx, launch);
        String fallback = BrowserScheme.webFallback(url);
        if (BrowserScheme.POLICY_ASK.equals(policy)
                && !(appResolved && BrowserScheme.isCommunicationScheme(url))) {
            askUser(context, url, view, launch, fallback, appResolved);
            return;
        }
        if (appResolved && startIfResolvable(appCtx, launch, url)) return;
        if (BrowserScheme.POLICY_SYSTEM_BROWSER.equals(policy)
                && sendToSystemBrowser(appCtx, view, url, fallback)) {
            return;
        }
        if (openWeb(context, view, fallback)) return;
        startChooser(appCtx, launch, url);
    }

    // ==================== 内部 ====================

    /**
     * 在 WebView 宿主里打开网页；没有宿主（卡片、地址栏发起）就起内置浏览器页面。
     *
     * @return 地址不是 http/https 或起不来时返回 false，调用方继续给下一条出路
     */
    private static boolean openWeb(Context context, WebView view, String url) {
        if (!BrowserScheme.isHttp(url)) return false;
        if (view != null) {
            try {
                Log.i(TAG, "应用内加载回退网页: " + url);
                view.loadUrl(url);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "宿主 WebView 加载失败，改起内置浏览器: " + t.getMessage());
            }
        }
        try {
            Intent intent = new Intent(context, com.oilquiz.app.WebViewActivity.class);
            intent.putExtra("url", url);
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.i(TAG, "回退网页交给内置浏览器: " + url);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "内置浏览器启动失败: " + t.getMessage());
            return false;
        }
    }

    /** 有没有应用能处理该协议（先按 BROWSABLE 问，问不到再去掉约束兜底） */
    private static boolean hasResolver(Context context, Intent intent) {
        try {
            PackageManager pm = context.getPackageManager();
            if (!pm.queryIntentActivities(intent, 0).isEmpty()) return true;
            Intent relaxed = new Intent(intent);
            relaxed.removeCategory(Intent.CATEGORY_BROWSABLE);
            return !pm.queryIntentActivities(relaxed, 0).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** intent:// 串 → Intent；指向本应用自身的 component 一律剥掉 */
    private static Intent parseIntentUri(Context context, String url) {
        try {
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            if (intent == null) return null;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setSelector(null);
            android.content.ComponentName component = intent.getComponent();
            if (component != null && component.getPackageName() != null
                    && component.getPackageName().equals(context.getPackageName())) {
                // 网页不该能用一条 intent 串把本应用的内部 Activity 当入口拉起来
                Log.w(TAG, "剥掉指向本应用的 component: " + component);
                intent.setComponent(null);
            }
            return intent;
        } catch (Throwable t) {
            Log.w(TAG, "intent 解析失败: " + t.getMessage());
            return null;
        }
    }

    private static Intent buildViewIntent(Context context, String url, boolean fromWeb) {
        try {
            Uri uri = Uri.parse(url);
            if (uri.getScheme() == null) return null;
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (fromWeb) intent.addCategory(Intent.CATEGORY_BROWSABLE);
            return intent;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 能解析就启动；多个候选走系统选择器；成功返回 true */
    private static boolean startIfResolvable(Context context, Intent intent, String url) {
        try {
            PackageManager pm = context.getPackageManager();
            List<ResolveInfo> matches = pm.queryIntentActivities(intent, 0);
            if (matches == null || matches.isEmpty()) {
                // 带 BROWSABLE 找不到时，不带该约束再问一次：部分国产 App 的拉起 Activity 没声明 BROWSABLE
                Intent relaxed = new Intent(intent);
                relaxed.removeCategory(Intent.CATEGORY_BROWSABLE);
                matches = pm.queryIntentActivities(relaxed, 0);
                if (matches != null && !matches.isEmpty()) {
                    intent = relaxed;
                } else {
                    return false;
                }
            }
            if (matches.size() == 1) {
                ResolveInfo info = matches.get(0);
                intent.setPackage(info.activityInfo.packageName);
                context.startActivity(intent);
                CharSequence label = info.loadLabel(pm);
                String name = label != null && label.length() > 0
                        ? label.toString() : info.activityInfo.packageName;
                // 转交是"看不见的过程"（切到别的 App 了），要给一条能对上号的提示
                toast(context, R.string.browser_scheme_launched, name);
                Log.i(TAG, "协议已交应用 " + info.activityInfo.packageName + " (" + name + "): " + url);
                return true;
            }
            startChooser(context, intent, url);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "启动失败: " + e.getMessage());
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "协议转交异常: " + t.getMessage());
            return false;
        }
    }

    private static void startChooser(Context context, Intent intent, String url) {
        try {
            Intent chooser = Intent.createChooser(intent, context.getString(R.string.browser_scheme_chooser));
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
        } catch (Throwable t) {
            toast(context, R.string.browser_scheme_no_app, schemeHint(url));
        }
    }

    /**
     * 交给系统浏览器：把 URL 显式发给默认浏览器包名，由它按自己的协议表处理
     * （这正是"系统浏览器能识别、内置浏览器却报错"的那些链接该有的出路）。
     */
    private static boolean sendToSystemBrowser(Context context, WebView view, String url, String fallback) {
        String browser = findBrowserPackage(context);
        if (browser != null) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.setPackage(browser);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Log.i(TAG, "协议已交系统浏览器 " + browser + ": " + url);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "系统浏览器接不住该协议: " + t.getMessage());
            }
        }
        // 浏览器也帮不上（或没装）：有回退网页就用内置浏览器打开，否则交不回
        return openWeb(context, view, fallback);
    }

    private static String findBrowserPackage(Context context) {
        try {
            Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"));
            probe.addCategory(Intent.CATEGORY_BROWSABLE);
            List<ResolveInfo> browsers = context.getPackageManager()
                    .queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY);
            String self = context.getPackageName();
            if (browsers != null) {
                for (ResolveInfo info : browsers) {
                    String pkg = info.activityInfo != null ? info.activityInfo.packageName : null;
                    if (pkg != null && !pkg.equals(self)) return pkg;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 询问用户：出路顺序 = 对应应用 → 对应网页 → 系统浏览器 → 复制链接 */
    private static void askUser(Context context, String url, WebView view,
                                Intent viewIntent, String fallback, boolean appResolved) {
        List<String> keys = BrowserScheme.optionsFor(url,
                BrowserScheme.classify(url), appResolved, fallback);
        String[] labels = new String[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            labels[i] = optionLabel(context, keys.get(i));
        }
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (!(context instanceof Activity)) {
            // 没有 Activity 就弹不了框：给一条能照着做的提示，不做静默失败
            toast(appCtx, R.string.browser_scheme_no_app, schemeHint(url));
            return;
        }
        String scheme = schemeHint(url);
        new android.app.AlertDialog.Builder(context)
                .setTitle(R.string.browser_scheme_title)
                .setMessage(appCtx.getString(R.string.browser_scheme_message, scheme, url))
                .setItems(labels, (dialog, which) -> {
                    String key = keys.get(which);
                    if (BrowserScheme.OPT_SYSTEM_APP.equals(key)) {
                        if (!startIfResolvable(appCtx, viewIntent, url)) {
                            startChooser(appCtx, viewIntent, url);
                        }
                    } else if (BrowserScheme.OPT_WEB_FALLBACK.equals(key)) {
                        if (!openWeb(context, view, fallback)) {
                            toast(appCtx, R.string.browser_scheme_no_browser, "");
                        }
                    } else if (BrowserScheme.OPT_SYSTEM_BROWSER.equals(key)) {
                        if (!sendToSystemBrowser(appCtx, view, url, fallback)) {
                            toast(appCtx, R.string.browser_scheme_no_browser, "");
                        }
                    } else {
                        copy(appCtx, url);
                    }
                })
                .setNegativeButton(R.string.browser_cancel, null)
                .show();
    }

    private static String optionLabel(Context context, String key) {
        int res;
        switch (key) {
            case BrowserScheme.OPT_SYSTEM_APP:
                res = R.string.browser_scheme_open_app;
                break;
            case BrowserScheme.OPT_WEB_FALLBACK:
                res = R.string.browser_scheme_open_web;
                break;
            case BrowserScheme.OPT_SYSTEM_BROWSER:
                res = R.string.browser_open_external;
                break;
            default:
                res = R.string.browser_copy_link;
                break;
        }
        return context.getString(res);
    }

    private static String schemeHint(String url) {
        String scheme = BrowserScheme.schemeOf(url);
        return scheme.isEmpty() ? url : scheme + "://";
    }

    private static void copy(Context context, String url) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("链接", url));
                toast(context, R.string.browser_link_copied, "");
                return;
            }
        } catch (Throwable ignored) {
        }
        toast(context, R.string.browser_scheme_copy_failed, "");
    }

    private static void toast(Context context, int resId, String arg) {
        try {
            String text = arg == null || arg.isEmpty()
                    ? context.getString(resId)
                    : String.format(Locale.CHINA, context.getString(resId), arg);
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.w(TAG, "提示失败: " + t.getMessage());
        }
    }
}
