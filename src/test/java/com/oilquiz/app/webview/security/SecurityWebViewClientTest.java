package com.oilquiz.app.webview.security;

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
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;

/**
 * {@link SecurityWebViewClient} 的协议口径测试。
 *
 * <p>重点是"只有一套判定"：https/file 留在 WebView，危险协议拦下，其余协议必须走
 * {@link com.oilquiz.app.browser.BrowserSchemeHandler}——没有应用时交系统浏览器，
 * 拨号这类通讯协议直接交系统。这个 client 以前自己写了一套 resolveActivity + toast，
 * 于是同一台手机上会出现"内置浏览器能开、这里只提示没有应用"。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class SecurityWebViewClientTest {

    private Context appContext;
    private SecurityWebViewClient client;

    @Before
    public void setUp() throws Exception {
        appContext = ApplicationProvider.getApplicationContext();
        appContext.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
                .edit().clear().commit();
        Field instance = com.oilquiz.app.browser.BrowserSettings.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        client = new SecurityWebViewClient();
    }

    @org.junit.After
    public void tearDown() throws Exception {
        // 别把绑在测试 Application 上的 Cookie 快照留给后面的测试类
        Field field = com.oilquiz.app.webview.AppCookieStore.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    public void webUrlsStayInTheWebView() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertFalse(client.shouldOverrideUrlLoading(new WebView(activity), req("https://example.com/a")));
        assertFalse(client.shouldOverrideUrlLoading(new WebView(activity), req("file:///sdcard/a.pdf")));
    }

    @Test
    public void dangerousSchemesAreBlockedWithoutLaunchingAnything() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertTrue(client.shouldOverrideUrlLoading(new WebView(activity), req("javascript:alert(1)")));
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertTrue(client.shouldOverrideUrlLoading(new WebView(activity), req("data:text/html,<p>a</p>")));
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void missingAppFallsBackToTheSystemBrowser() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        registerBrowser(activity, "com.android.chrome");
        // 默认「每次询问」，但系统里没有应用能处理：委托层该直接交系统浏览器，而不是弹框/只提示
        assertTrue(client.shouldOverrideUrlLoading(new WebView(activity), req("vendoronly://open")));
        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull("没有应用时也必须有一条能继续的出路", started);
        assertEquals("com.android.chrome", started.getPackage());
        assertEquals("vendoronly://open", started.getDataString());
    }

    @Test
    public void communicationSchemeGoesStraightToTheSystemApp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        registerHandler(activity, "tel:10086", "com.android.dialer", ".DialtactsActivity");
        assertTrue(client.shouldOverrideUrlLoading(new WebView(activity), req("tel:10086")));
        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals("com.android.dialer", started.getPackage());
    }

    @Test
    public void plainHttpWithStoredLoginWarnsButStillLoads() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        // 预置登录态快照（AppCookieStore 的存储格式：cookie.<host> = "k=v; k2=v2"）
        appContext.getSharedPreferences("app_cookie_store", Context.MODE_PRIVATE)
                .edit().putString("cookie.example.com", "SESSION=abc").commit();
        storeWithTestContext();

        assertTrue("测试装置：Cookie 快照应命中该 host",
                com.oilquiz.app.webview.AppCookieStore.getInstance()
                        .hasLogin("http://example.com/page"));
        assertFalse("带登录态的明文 HTTP 只警告，不阻断导航",
                client.shouldOverrideUrlLoading(new WebView(activity), req("http://example.com/page")));
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        String toast = org.robolectric.shadows.ShadowToast.getTextOfLatestToast();
        assertNotNull("应给出明文 HTTP 警告", toast);
        assertTrue("警告要说明是明文 HTTP：" + toast, toast.contains("明文 HTTP"));
    }

    @Test
    public void browserRedirectClientInheritsTheSecurityPolicy() {
        // 内置浏览器主界面用的客户端必须继承安全层——否则"接线"只是名义上的：
        // 主框架的 javascript:/协议转交仍会走自己那套判定。
        com.oilquiz.app.webview.RedirectWebViewClient redirect =
                new com.oilquiz.app.webview.RedirectWebViewClient();
        assertTrue(redirect instanceof SecurityWebViewClient);

        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        WebView view = new WebView(activity);
        assertTrue("javascript: 仍要拦", redirect.shouldOverrideUrlLoading(
                view, req("javascript:alert(1)")));
        assertFalse("普通网页留在 WebView", redirect.shouldOverrideUrlLoading(
                view, req("https://example.com/a")));
        assertTrue("data: 主框架导航拦截（与 Chrome 同口径）", redirect.shouldOverrideUrlLoading(
                view, req("data:text/html,<p>a</p>", true)));
        assertFalse("data: 子框架是页面正常内容，不该被主框架口径误伤",
                redirect.shouldOverrideUrlLoading(view, req("data:text/html,<p>a</p>", false)));
    }

    // ==================== 测试装置 ====================

    /**
     * 用测试 Application 的 Context 构造 {@code AppCookieStore} 并装到单例位。
     *
     * <p>纯 JVM 测试里 {@code SmartQuizApplication.getAppContext()} 是 null（robolectric.properties
     * 明确用裸 Application，避免 Chaquopy so 加载），所以不能走 {@code getInstance()} 的正常入口。
     * 这是测试装置，不碰生产代码。</p>
     */
    private void storeWithTestContext() throws Exception {
        java.lang.reflect.Constructor<com.oilquiz.app.webview.AppCookieStore> ctor =
                com.oilquiz.app.webview.AppCookieStore.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        com.oilquiz.app.webview.AppCookieStore store = ctor.newInstance(appContext);
        Field field = com.oilquiz.app.webview.AppCookieStore.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, store);
    }

    private static WebResourceRequest req(String url) {
        return req(url, true);
    }

    private static WebResourceRequest req(String url, boolean mainFrame) {
        return new WebResourceRequest() {
            @Override
            public Uri getUrl() {
                return Uri.parse(url);
            }

            @Override
            public boolean isForMainFrame() {
                return mainFrame;
            }

            @Override
            public boolean isRedirect() {
                return false;
            }

            @Override
            public boolean hasGesture() {
                return true;
            }

            @Override
            public String getMethod() {
                return "GET";
            }

            @Override
            public java.util.Map<String, String> getRequestHeaders() {
                return new java.util.HashMap<>();
            }
        };
    }

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
