package com.oilquiz.app.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.webkit.WebView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowWebView;

import java.lang.reflect.Field;

/**
 * 协议转交的执行口径测试。
 *
 * <p>重点是"别把协议丢给 WebView"：{@code shouldOverrideUrlLoading} 对 http/https 必须放行、
 * 对自定义协议必须接管；{@code intent://} 带 {@code browser_fallback_url} 时先应用内打开回退网页。
 * 这些都不依赖真机（Robolectric 的 WebView 会记下 loadUrl）。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class BrowserSchemeHandlerTest {

    private Context appContext;
    private WebView webView;

    @Before
    public void setUp() throws Exception {
        appContext = ApplicationProvider.getApplicationContext();
        appContext.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
                .edit().clear().commit();
        Field instance = BrowserSettings.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        webView = new WebView(appContext);
    }

    @Test
    public void webUrlsStayInTheBrowser() {
        assertFalse(BrowserSchemeHandler.handleNavigation(webView, "https://example.com/a", true));
        assertFalse(BrowserSchemeHandler.handleNavigation(webView, "http://10.0.0.1:8080", true));
        assertFalse(BrowserSchemeHandler.handleNavigation(webView, "file:///sdcard/a.pdf", true));
        assertFalse(BrowserSchemeHandler.handleNavigation(webView, "about:blank", true));
    }

    @Test
    public void customSchemesAreOverridden() {
        assertTrue(BrowserSchemeHandler.handleNavigation(webView, "weixin://dl/business/?t=x", true));
        assertTrue(BrowserSchemeHandler.handleNavigation(webView, "magnet:?xt=urn:btih:abc", true));
        assertTrue(BrowserSchemeHandler.handleNavigation(webView, "tel:10086", true));
    }

    @Test
    public void intentFallbackLoadsInPlace() {
        String intent = "intent://m/#Intent;scheme=alipays;package=com.eg.android.AlipayGphone;"
                + "S.browser_fallback_url=https%3A%2F%2Frender.alipay.com%2Fp%2Fy.html;end";
        assertTrue(BrowserSchemeHandler.handleNavigation(webView, intent, true));
        ShadowWebView shadow = shadowOf(webView);
        assertEquals("https://render.alipay.com/p/y.html", shadow.getLastLoadedUrl());
    }

    @Test
    public void schemeIsNeverFedToTheWebView() {
        BrowserSchemeHandler.handleNavigation(webView, "myapp://open/page", true);
        // 没有能处理的应用、context 又不是 Activity：只提示，绝不 loadUrl 这条协议（那正是 -302 的来源）
        assertNull(shadowOf(webView).getLastLoadedUrl());
    }

    @Test
    public void asksBeforeLeavingTheBrowserWhenAnAppCouldOpenIt() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        String link = "weixin://dl/business/?t=x";
        registerHandler(activity, link, "com.tencent.mm", ".LaunchUI");
        BrowserSchemeHandler.handleNavigation(new WebView(activity), link, true);
        // 默认「每次询问」：网页不能凭空把人送出浏览器（tel/mailto 这类除外，见下面那条）
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertNotNull("该弹一个给出路的框", ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test
    public void launchModeOpensTheAppWithoutAsking() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        String link = "weixin://dl/business/?t=x";
        registerHandler(activity, link, "com.tencent.mm", ".LaunchUI");
        BrowserSettings.get(appContext).setUnknownSchemePolicy(BrowserScheme.POLICY_SYSTEM_APP);
        BrowserSchemeHandler.handleNavigation(new WebView(activity), link, true);
        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull("直接交应用档应真的启动 Intent", started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("weixin", started.getData().getScheme());
    }

    @Test
    public void browserModeSendsTheSchemeToTheSystemBrowser() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        registerBrowser(activity, "com.android.chrome");
        BrowserSettings.get(appContext).setUnknownSchemePolicy(BrowserScheme.POLICY_SYSTEM_BROWSER);
        // 没有任何应用能处理该协议：这一档该把它显式发给系统浏览器，由浏览器按自己的协议表处理
        BrowserSchemeHandler.handleNavigation(new WebView(activity), "myapp://open/page", true);
        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals("com.android.chrome", started.getPackage());
        assertEquals("myapp://open/page", started.getDataString());
    }

    @Test
    public void communicationSchemesLaunchWithoutPrompting() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        registerHandler(activity, "tel:10086", "com.android.dialer", ".DialtactsActivity");
        // 策略仍是默认的"每次询问"
        BrowserSchemeHandler.handleNavigation(new WebView(activity), "tel:10086", true);
        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull("拨号这类转交后本来就在系统界面里，不该再多问一次", started);
        assertEquals("tel", started.getData().getScheme());
    }

    @Test
    public void unknownSchemeErrorRestoresThePageInsteadOfAnErrorPage() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        WebView view = new WebView(activity);
        assertTrue(BrowserSchemeHandler.handleUnknownSchemeError(view, "weixin://x", "https://a.com/page"));
        assertEquals("https://a.com/page", shadowOf(view).getLastLoadedUrl());
        // 不是协议链接的失败（真网络错误）不该被这里接管，仍走通用错误页
        assertFalse(BrowserSchemeHandler.handleUnknownSchemeError(view, "https://a.com/x", "https://a.com/page"));
    }

    // ==================== 测试装置 ====================

    /**
     * 按**完整链接**注册一个能处理它的 Activity。
     *
     * <p>Robolectric 的 {@code addResolveInfoForIntent} 按 {@code Intent#filterEquals} 匹配
     * （action+data+type+category 全等），拿 {@code weixin://placeholder} 去登记、却用
     * {@code weixin://dl/business/?t=x} 查询是不会命中的——那样测出来的是"仪器没响"，
     * 不是产品没转交。</p>
     */
    private static void registerHandler(Activity activity, String url, String pkg, String cls) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        ResolveInfo info = new ResolveInfo();
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.packageName = pkg;
        activityInfo.name = cls;
        info.activityInfo = activityInfo;
        shadowOf(activity.getPackageManager()).addResolveInfoForIntent(intent, info);
    }

    private static void registerBrowser(Activity activity, String pkg) {
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"));
        probe.addCategory(Intent.CATEGORY_BROWSABLE);
        ResolveInfo info = new ResolveInfo();
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.packageName = pkg;
        activityInfo.name = ".BrowserActivity";
        info.activityInfo = activityInfo;
        shadowOf(activity.getPackageManager()).addResolveInfoForIntent(probe, info);
    }
}
