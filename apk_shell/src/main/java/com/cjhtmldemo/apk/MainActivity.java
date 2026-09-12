package com.cjhtmldemo.apk;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * SmartQuiz 设备端 APK 导出壳 v7（专业版）。
 *
 * <p>加载规则（读取 assets/manifest.json）：</p>
 * <ol>
 *   <li>若含非空 "url" 字段 → 直接加载该远程地址（远程 URL 模式）；</li>
 *   <li>否则解密 assets/dt.jet（AES-128-CBC，key="MyHtmlEditorKey1"，IV 在文件头16字节）
 *       得到 ZIP → 解压到 filesDir/html → 本地 HTTP 服务(localhost:8099) → 加载入口。</li>
 * </ol>
 *
 * <p>专业能力（HTML 通过 window.AndroidApp 调用，详见 apk_shell/HTML_DESIGN_RULES.md）：</p>
 * <ul>
 *   <li>v7 新增：shellVersion/bridgeApi 进 dex；剪贴板监听；通知栏；前台服务；
 *       深链读取；权限状态面板；JS 注入通道（自动注入器 + 即时注入）；
 *       离线缓存（本地优先/仅离线 + 单 URL 预缓存）；渲染进程崩溃保护（onRenderProcessGone）；</li>
 *   <li>原有：Toast / 振动 / 退出 / 版本 / 设备信息 / 网络类型 / 复制 / 分享文本 / 分享文件 /
 *       打开浏览器 / 无 CORS 网络代理 request() / 启动模式 getLaunchMode()</li>
 *   <li>文件上传（input[type=file]）、相机/录音权限（getUserMedia）、定位授权</li>
 *   <li>下载管理（DownloadManager）、window.open 路由回主视图、错误重试页</li>
 *   <li>返回键：壳内历史回退 + 双击退出</li>
 * </ul>
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    /** 壳版本（真正编译进 dex；getVersion()/getShellVersion() 返回，打包后无需改资源即可识别） */
    private static final String SHELL_VERSION = "v7";
    /** 桥 API 版本（新增/变更桥方法时递增，HTML 可据此做能力探测） */
    private static final int BRIDGE_API = 3;
    /** 与 ApkPacker 一致的 AES 密钥 */
    private static final String AES_KEY_STR = "MyHtmlEditorKey1";
    private static final String MANIFEST_ASSET = "manifest.json";
    private static final String DT_JET_ASSET = "dt.jet";
    private static final String HTML_DIR_NAME = "html";
    private static final int[] CANDIDATE_PORTS = {8099, 18099, 28099};
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_RUNTIME_PERMS = 1002;
    private static final int REQ_FILE_PICKER = 1003;
    /** 通知渠道 ID（通知栏桥 / 前台服务共用） */
    private static final String NOTIFY_CHANNEL = "shell_notify";
    private static final int NOTIFY_ID_BASE = 2000;
    /** 离线缓存模式：0=默认走网络，1=本地优先（命中缓存不回源），2=仅离线 */
    public static final int CACHE_DEFAULT = 0;
    public static final int CACHE_LOCAL_FIRST = 1;
    public static final int CACHE_OFFLINE = 2;
    /** 前台服务通知 ID */
    private static final int FG_NOTIFY_ID = 1999;

    private WebView webView;
    private View loadingLayout;
    private HtmlHttpServer httpServer;
    private File htmlDir;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingPermissionRequest;
    private String pendingBridgePermission;   // 桥 requestPermission 待授权的权限名
    private String pendingBridgeCallback;     // 桥 requestPermission 回调函数名
    private String currentUrl = "about:blank";
    private long lastBackTime = 0;
    private final ExecutorService netExecutor = Executors.newCachedThreadPool();
    /** JS 注入通道：页面加载完成后自动执行的脚本（addScriptInjector 注册） */
    private final List<String> scriptInjectors = new ArrayList<>();
    /** 剪贴板监听器（startClipboardWatch 注册） */
    private ClipboardManager.OnPrimaryClipChangedListener clipboardListener;
    /** 离线缓存模式（setCacheMode 设置） */
    private volatile int cacheMode = CACHE_DEFAULT;
    /** 深链：最近一次外部 VIEW 打开的数据 */
    private String deepLink = "";

    private String mainFile = "index.html";
    private String remoteUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        loadingLayout = findViewById(R.id.loadingLayout);

        // 通知渠道（通知栏桥 / 前台服务共用；8.0+ 必须）
        createNotifyChannel();
        // 深链：记录外部 VIEW 打开时的数据
        if (getIntent() != null && getIntent().getData() != null) {
            deepLink = getIntent().getData().toString();
        }

        setupWebView();
        // 原生能力桥：HTML 中通过 window.AndroidApp.xxx() 调用
        webView.addJavascriptInterface(new AppBridge(), "AndroidApp");

        new Thread(this::bootstrap, "ShellBootstrap").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null) {
            deepLink = intent.getData().toString();
            // 通知 HTML：外部深链已到达（页面通过 AndroidApp.getDeepLink() 读取）
            final String link = deepLink;
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.evaluateJavascript(
                            "window.dispatchEvent(new CustomEvent('deeplink',{detail:" + JSONObject.quote(link) + "}))",
                            null);
                }
            });
        }
    }

    /** 创建通知渠道（通知栏桥 / 前台服务共用） */
    private void createNotifyChannel() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        NOTIFY_CHANNEL, "壳应用通知", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("HTML 应用通知 / 前台服务常驻");
                nm.createNotificationChannel(ch);
            }
        } catch (Exception ignored) { }
    }

    /** 后台线程：解析 manifest → 解密解压 → 起本地服务；WebView 操作回主线程 */
    private void bootstrap() {
        try {
            readManifest();
            if (!remoteUrl.isEmpty()) {
                runOnUiThread(() -> loadUrl(remoteUrl));
                return;
            }
            htmlDir = new File(getFilesDir(), HTML_DIR_NAME);
            byte[] jet = readAsset(DT_JET_ASSET);
            if (jet != null && jet.length > 0) {
                byte[] zip = aesDecrypt(jet);
                if (zip != null && zip.length > 2 && zip[0] == 'P' && zip[1] == 'K') {
                    extractZip(zip, htmlDir);
                }
            }
            // 注入内置前端库（assets/libs/* → htmlDir/libs/，不覆盖已存在的同名文件）
            injectBundledLibs(htmlDir);
            int port = startServer(htmlDir);
            runOnUiThread(() -> loadUrl("http://localhost:" + port + "/" + mainFile));
        } catch (Exception e) {
            runOnUiThread(() -> {
                Toast.makeText(this, "加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                loadingLayout.setVisibility(View.GONE);
            });
        }
    }

    /** 读取 assets/manifest.json：main 入口 + 可选 url 远程加载 */
    private void readManifest() throws Exception {
        String json = readAssetString(MANIFEST_ASSET);
        if (json != null && !json.isEmpty()) {
            JSONObject obj = new JSONObject(json);
            if (obj.has("main") && !obj.isNull("main")) {
                mainFile = obj.getString("main");
            }
            if (obj.has("url") && !obj.isNull("url")) {
                remoteUrl = obj.getString("url").trim();
            }
        }
    }

    /** 尝试在候选端口启动本地 HTTP 服务，返回实际端口 */
    private int startServer(File dir) throws Exception {
        if (!dir.exists()) dir.mkdirs();
        Exception last = null;
        for (int port : CANDIDATE_PORTS) {
            try {
                httpServer = new HtmlHttpServer(dir, port);
                httpServer.start();
                return port;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IllegalStateException("本地服务启动失败", last);
    }

    /** 把内置前端库递归复制到 htmlDir/libs/（设计规则：HTML 用相对路径 libs/xxx 引用）；不覆盖用户文件 */
    private void injectBundledLibs(File dir) {
        try {
            File libDir = new File(dir, "libs");
            copyAssetTree("libs", libDir);
        } catch (Exception ignored) { }
    }

    private void copyAssetTree(String assetPath, File targetDir) {
        try {
            String[] names = getAssets().list(assetPath);
            if (names == null) return;
            for (String name : names) {
                String childAsset = assetPath + "/" + name;
                String[] sub = getAssets().list(childAsset);
                if (sub != null && sub.length > 0) {
                    // 子目录递归
                    copyAssetTree(childAsset, new File(targetDir, name));
                } else {
                    byte[] data = readAsset(childAsset);
                    if (data == null || data.length == 0) continue;
                    File target = new File(targetDir, name);
                    if (target.exists()) continue; // 不覆盖用户文件
                    if (!targetDir.exists()) targetDir.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(target)) {
                        fos.write(data);
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    /** 统一入口：记录当前 URL 并加载 */
    private void loadUrl(String url) {
        currentUrl = url;
        webView.loadUrl(url);
    }

    /** WebView 配置（现代壳：JS/DOM存储/媒体自动播放/缩放/混合内容兼容） */
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBlockNetworkImage(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 远程 http 链接 + 页面内 http 资源兼容
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    return false; // 应用内加载
                }
                if (url != null && (url.startsWith("tel:") || url.startsWith("mailto:")
                        || url.startsWith("sms:") || url.startsWith("intent:"))) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                currentUrl = url;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                currentUrl = url;
                loadingLayout.setVisibility(View.GONE);
                // JS 注入通道：自动注入器（HTML 通过 AndroidApp.addScriptInjector 注册）
                if (!scriptInjectors.isEmpty()) {
                    StringBuilder js = new StringBuilder();
                    for (String s : scriptInjectors) js.append(s).append('\n');
                    view.evaluateJavascript(js.toString(), null);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    showErrorPage(view);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request != null && request.isForMainFrame()) {
                    showErrorPage(view);
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // 离线缓存：本地优先 / 仅离线 模式下拦截 http(s) 子资源
                if (cacheMode != CACHE_DEFAULT && request != null && request.getUrl() != null) {
                    String url = request.getUrl().toString();
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        WebResourceResponse cached = serveCached(url);
                        if (cached != null) return cached;
                        if (cacheMode == CACHE_OFFLINE) {
                            // 仅离线且未命中：返回空响应避免长时间等待
                            return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("offline".getBytes(StandardCharsets.UTF_8)));
                        }
                    }
                }
                return null;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // 渲染进程崩溃保护：不杀掉整个应用，回到错误重试页（API 26+）
                if (detail != null && detail.didCrash()) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "页面渲染进程异常，已重置", Toast.LENGTH_SHORT).show();
                        showErrorPage(view);
                    });
                    return true;
                }
                return super.onRenderProcessGone(view, detail);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    startActivityForResult(Intent.createChooser(params.createIntent(), "选择文件"), REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                pendingPermissionRequest = request;
                List<String> perms = new ArrayList<>();
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) perms.add(Manifest.permission.CAMERA);
                    else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) perms.add(Manifest.permission.RECORD_AUDIO);
                }
                if (!perms.isEmpty() && Build.VERSION.SDK_INT >= 23) {
                    requestPermissions(perms.toArray(new String[0]), REQ_RUNTIME_PERMS);
                } else {
                    request.grant(request.getResources());
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // window.open / target=_blank 统一路由回主 WebView，避免新窗口丢失
                WebView child = new WebView(view.getContext());
                child.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        if (url != null) {
                            runOnUiThread(() -> loadUrl(url));
                        }
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(child);
                resultMsg.sendToTarget();
                return true;
            }
        });

        // 下载：<a download> / Content-Disposition 附件 → 系统下载管理器
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                String filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype);
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setTitle(filename);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
                Toast.makeText(this, "开始下载: " + filename, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 主框架加载失败 → 友好的错误重试页 */
    private void showErrorPage(WebView view) {
        String retry = currentUrl == null || currentUrl.isEmpty() ? "about:blank" : currentUrl;
        String esc = retry.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{font-family:sans-serif;background:#f7f8fa;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}"
                + ".box{text-align:center;color:#666;padding:24px}"
                + "h2{color:#333;font-size:18px;margin:0 0 8px}"
                + "p{font-size:14px;margin:4px 0;color:#999}"
                + "a{display:inline-block;margin-top:16px;padding:10px 28px;background:#4338ca;color:#fff;border-radius:8px;text-decoration:none;font-size:14px}</style>"
                + "</head><body><div class='box'><h2>页面加载失败</h2><p>请检查网络连接后重试</p>"
                + "<a href='" + esc + "'>重新加载</a></div></body></html>";
        view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                String dataString = data.getDataString();
                ClipData clipData = data.getClipData();
                if (clipData != null) {
                    results = new Uri[clipData.getItemCount()];
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        results[i] = clipData.getItemAt(i).getUri();
                    }
                } else if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        } else if (requestCode == REQ_FILE_PICKER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            handleFilePickerResult(data.getData());
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /** SAF 文件选择结果：读名称/大小，≤2MB 转 base64 回调给 HTML */
    private void handleFilePickerResult(Uri uri) {
        netExecutor.execute(() -> {
            JSONObject out = new JSONObject();
            try {
                android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
                String name = "file";
                long size = -1;
                String mime = "*/*";
                if (c != null) {
                    int ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    int si = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (ni >= 0 && c.moveToFirst()) name = c.getString(ni);
                    if (si >= 0 && c.moveToFirst() && !c.isNull(si)) size = c.getLong(si);
                    c.close();
                }
                mime = getContentResolver().getType(uri);
                if (mime == null) mime = "*/*";
                InputStream is = getContentResolver().openInputStream(uri);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                    total += n;
                    if (total > 2 * 1048576L) throw new IllegalStateException("文件超过 2MB 限制");
                }
                is.close();
                out.put("name", name);
                out.put("size", total);
                out.put("mimeType", mime);
                out.put("dataBase64", android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP));
            } catch (Exception e) {
                try { out.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { }
            }
            final String js = "window['" + pendingBridgeCallback + "'] && window['" + pendingBridgeCallback + "']("
                    + JSONObject.quote(out.toString()) + ");";
            runOnUiThread(() -> {
                if (webView != null) webView.evaluateJavascript(js, null);
            });
            pendingBridgeCallback = null;
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_RUNTIME_PERMS) {
            if (pendingPermissionRequest != null) {
                boolean granted = grantResults.length > 0;
                for (int r : grantResults) {
                    if (r != PackageManager.PERMISSION_GRANTED) granted = false;
                }
                if (granted) {
                    pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                } else {
                    pendingPermissionRequest.deny();
                }
                pendingPermissionRequest = null;
                return;
            }
            if (pendingBridgePermission != null) {
                boolean granted = grantResults.length > 0;
                for (int r : grantResults) {
                    if (r != PackageManager.PERMISSION_GRANTED) granted = false;
                }
                dispatchPermissionResult(pendingBridgePermission, granted);
                pendingBridgePermission = null;
                pendingBridgeCallback = null;
                return;
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackTime < 2000) {
            super.onBackPressed();
        } else {
            lastBackTime = now;
            Toast.makeText(this, "再按一次返回键退出应用", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        // 清理剪贴板监听
        try {
            if (clipboardListener != null) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.removePrimaryClipChangedListener(clipboardListener);
                clipboardListener = null;
            }
        } catch (Exception ignored) { }
        // 停止前台服务（若由本页启动）
        try {
            if (ForegroundBridgeService.isRunning()) {
                stopService(new Intent(this, ForegroundBridgeService.class));
            }
        } catch (Exception ignored) { }
        if (httpServer != null) {
            try { httpServer.stop(); } catch (Exception ignored) { }
            httpServer = null;
        }
        netExecutor.shutdownNow();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    // ==================== 离线缓存辅助 ====================

    private File cacheFileFor(String url) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : sha) sb.append(String.format(Locale.US, "%02x", b));
            File dir = new File(getCacheDir(), "http_cache");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, sb.substring(0, 40) + ".bin");
        } catch (Exception e) {
            return null;
        }
    }

    private WebResourceResponse serveCached(String url) {
        File f = cacheFileFor(url);
        if (f == null || !f.isFile()) return null;
        try {
            String mime = HtmlHttpServer.mimeOf(f.getName());
            return new WebResourceResponse(mime, "utf-8", new FileInputStream(f));
        } catch (Exception e) {
            return null;
        }
    }

    private void saveToCache(String url, byte[] data) {
        File f = cacheFileFor(url);
        if (f == null) return;
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        } catch (Exception ignored) { }
    }

    /** 下载单 URL 并写入离线缓存（网络线程执行，回调 bridge 通知完成） */
    private void precacheUrlAsync(String url, String callbackName) {
        netExecutor.execute(() -> {
            boolean ok = false;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(12000);
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code >= 200 && code < 400) {
                    InputStream is = conn.getInputStream();
                    byte[] data = is.readAllBytes();
                    is.close();
                    conn.disconnect();
                    saveToCache(url, data);
                    ok = true;
                } else {
                    conn.disconnect();
                }
            } catch (Exception ignored) { }
            final boolean success = ok;
            final String js = "window['" + callbackName + "'] && window['" + callbackName + "']("
                    + JSONObject.quote("{\"url\":" + JSONObject.quote(url) + ",\"ok\":" + success + "}") + ");";
            runOnUiThread(() -> {
                if (webView != null) webView.evaluateJavascript(js, null);
            });
        });
    }

    /** 更新前台服务通知文案（由前台服务回调到主线程时使用） */
    static void updateForeground(String title, String text) {
        ForegroundBridgeService.update(title, text);
    }

    // ==================== 原生能力桥（window.AndroidApp） ====================

    /** HTML 可调用的原生能力；回调均在 JS 线程，UI 操作显式回主线程 */
    private class AppBridge {

        @JavascriptInterface
        public void showToast(String msg) {
            runOnUiThread(() -> {
                if (msg != null && !msg.isEmpty()) Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void vibrate(long ms) {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            long dur = Math.max(1, Math.min(ms, 5000));
            v.vibrate(VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE)); // minSdk 26+
        }

        @JavascriptInterface
        public void haptic() {
            vibrate(20);
        }

        @JavascriptInterface
        public void exit() {
            runOnUiThread(MainActivity.this::finish);
        }

        @JavascriptInterface
        public String getVersion() {
            JSONObject o = new JSONObject();
            try {
                o.put("shellVersion", SHELL_VERSION);
                o.put("bridgeApi", BRIDGE_API);
                o.put("versionName", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
                o.put("versionCode", getPackageManager().getPackageInfo(getPackageName(), 0).versionCode);
            } catch (Exception ignored) { }
            return o.toString();
        }

        /** v7：壳版本（直接进 dex 的常量，不依赖资源/manifest） */
        @JavascriptInterface
        public String getShellVersion() {
            return SHELL_VERSION;
        }

        /** v7：桥 API 版本号（能力探测） */
        @JavascriptInterface
        public int getBridgeApi() {
            return BRIDGE_API;
        }

        @JavascriptInterface
        public String getDeviceInfo() {
            JSONObject o = new JSONObject();
            try {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                o.put("screenWidth", dm.widthPixels);
                o.put("screenHeight", dm.heightPixels);
                o.put("density", dm.density);
                o.put("densityDpi", dm.densityDpi);
                o.put("sdkInt", Build.VERSION.SDK_INT);
                o.put("model", Build.MODEL);
                o.put("manufacturer", Build.MANUFACTURER);
                o.put("launchMode", remoteUrl.isEmpty() ? "local" : "remote");
            } catch (Exception ignored) { }
            return o.toString();
        }

        @JavascriptInterface
        public String getNetworkType() {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
                if (ni == null || !ni.isConnected()) return "none";
                int t = ni.getType();
                if (t == ConnectivityManager.TYPE_WIFI) return "wifi";
                if (t == ConnectivityManager.TYPE_MOBILE) return "mobile";
                return "other";
            } catch (Exception e) {
                return "unknown";
            }
        }

        @JavascriptInterface
        public void copyText(String text) {
            if (text == null) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("text", text));
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "已复制到剪贴板", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void shareText(String text) {
            if (text == null) return;
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, "分享到"));
                } catch (Exception ignored) { }
            });
        }

        /** 分享壳内文件（相对 filesDir/html 的路径，如 "result/score.png"） */
        @JavascriptInterface
        public void shareFile(String relativePath) {
            if (relativePath == null || relativePath.isEmpty()) return;
            runOnUiThread(() -> {
                try {
                    if (htmlDir == null) { Toast.makeText(MainActivity.this, "本地模式才可分享文件", Toast.LENGTH_SHORT).show(); return; }
                    File f = new File(htmlDir, relativePath);
                    String canonical = f.getCanonicalPath();
                    String root = htmlDir.getCanonicalPath();
                    if (!canonical.startsWith(root + File.separator)) {
                        Toast.makeText(MainActivity.this, "非法路径", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!f.isFile()) { Toast.makeText(MainActivity.this, "文件不存在: " + relativePath, Toast.LENGTH_SHORT).show(); return; }
                    Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", f);
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(HtmlHttpServer.mimeOf(f.getName()));
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "分享文件"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "分享失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void openBrowser(String url) {
            if (url == null || url.isEmpty()) return;
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开链接", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // ============ 权限管理（"camera"/"mic"/"storage"/"notification"/"location"） ============

        @JavascriptInterface
        public String checkPermission(String name) {
            String[] perms = permissionMapping(name);
            if (perms == null) return "false";
            if (perms.length == 0) return "true"; // 当前系统无需此权限
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return "false";
            }
            return "true";
        }

        @JavascriptInterface
        public void requestPermission(String name, String callbackName) {
            if (callbackName == null || !callbackName.matches("[A-Za-z0-9_]{1,64}")) return;
            final String cb = callbackName;
            final String[] perms = permissionMapping(name);
            if (perms == null) {
                dispatchPermissionResult(name, false);
                return;
            }
            if (perms.length == 0) {
                dispatchPermissionResult(name, true);
                return;
            }
            boolean allGranted = true;
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }
            if (allGranted) {
                dispatchPermissionResult(name, true);
                return;
            }
            pendingBridgePermission = name;
            pendingBridgeCallback = cb;
            runOnUiThread(() -> requestPermissions(perms, REQ_RUNTIME_PERMS));
        }

        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开设置", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // ============ 系统信息与交互（最新 API） ============

        @JavascriptInterface
        public String getBatteryLevel() {
            try {
                android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(Context.BATTERY_SERVICE);
                int level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                return String.valueOf(Math.max(0, Math.min(100, level)));
            } catch (Exception e) {
                return "-1";
            }
        }

        @JavascriptInterface
        public String isCharging() {
            try {
                android.content.IntentFilter filter = new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                android.content.Intent status = registerReceiver(null, filter);
                int plugged = status == null ? 0 : status.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1);
                return String.valueOf(plugged != 0);
            } catch (Exception e) {
                return "false";
            }
        }

        @JavascriptInterface
        public String getStorageInfo() {
            JSONObject o = new JSONObject();
            try {
                java.io.File data = Environment.getDataDirectory();
                android.os.StatFs sf = new android.os.StatFs(data.getPath());
                long total = sf.getTotalBytes(), free = sf.getAvailableBytes();
                o.put("internalTotalMB", total / 1048576L);
                o.put("internalFreeMB", free / 1048576L);
                java.io.File ext = Environment.getExternalStorageDirectory();
                if (ext != null && ext.exists()) {
                    android.os.StatFs ef = new android.os.StatFs(ext.getPath());
                    o.put("externalTotalMB", ef.getTotalBytes() / 1048576L);
                    o.put("externalFreeMB", ef.getAvailableBytes() / 1048576L);
                } else {
                    o.put("externalTotalMB", 0);
                    o.put("externalFreeMB", 0);
                }
            } catch (Exception ignored) { }
            return o.toString();
        }

        @JavascriptInterface
        public void setKeepScreenOn(boolean keepOn) {
            runOnUiThread(() -> {
                if (webView != null) webView.setKeepScreenOn(keepOn);
            });
        }

        /** 系统文件选择（SAF）：回调 window[callbackName]({name,size,mimeType,dataBase64})，≤2MB */
        @JavascriptInterface
        public void openFilePicker(String callbackName) {
            if (callbackName == null || !callbackName.matches("[A-Za-z0-9_]{1,64}")) return;
            pendingBridgeCallback = callbackName;
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, "选择文件"), REQ_FILE_PICKER);
                } catch (Exception e) {
                    pendingBridgeCallback = null;
                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** 当前页面截图：回调 window[callbackName]({dataBase64,width,height})；配合分享/保存 */
        @JavascriptInterface
        public void screenshot(String callbackName) {
            if (callbackName == null || !callbackName.matches("[A-Za-z0-9_]{1,64}")) return;
            runOnUiThread(() -> {
                JSONObject out = new JSONObject();
                try {
                    if (webView == null || webView.getWidth() <= 0 || webView.getHeight() <= 0) {
                        throw new IllegalStateException("页面尚未就绪");
                    }
                    android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                            webView.getWidth(), webView.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
                    webView.draw(canvas);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos);
                    out.put("dataBase64", android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP));
                    out.put("width", bmp.getWidth());
                    out.put("height", bmp.getHeight());
                } catch (Exception e) {
                    try { out.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { }
                }
                final String js = "window['" + callbackName + "'] && window['" + callbackName + "']("
                        + JSONObject.quote(out.toString()) + ");";
                if (webView != null) webView.evaluateJavascript(js, null);
            });
        }

        /** 读取剪贴板文本（前台应用可直接读；Android 13+ 系统会显示剪贴板提示条） */
        @JavascriptInterface
        public String readClipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                        && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
                    return text == null ? "" : text.toString();
                }
            } catch (Exception ignored) { }
            return "";
        }

        // ============ v7 新增：剪贴板监听 / 通知栏 / 前台服务 / 深链 / 权限面板 / JS 注入 / 离线缓存 ============

        /**
         * 剪贴板监听：开始监听剪贴板变化，回调 window[callbackName]({"text":"..."})。
         * 同一时刻仅一个监听；前台可见时有效（Android 10+ 后台剪贴板受限）。
         */
        @JavascriptInterface
        public void startClipboardWatch(String callbackName) {
            if (callbackName == null || !callbackName.matches("[A-Za-z0-9_]{1,64}")) return;
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return;
                stopClipboardWatch();
                final String cb = callbackName;
                clipboardListener = () -> {
                    String text = "";
                    try {
                        if (cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                                && cm.getPrimaryClip().getItemCount() > 0) {
                            CharSequence c = cm.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
                            if (c != null) text = c.toString();
                        }
                    } catch (Exception ignored) { }
                    final String t = text;
                    runOnUiThread(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript(
                                    "window['" + cb + "'] && window['" + cb + "']("
                                            + JSONObject.quote("{\"text\":" + JSONObject.quote(t) + "}") + ");", null);
                        }
                    });
                };
                cm.addPrimaryClipChangedListener(clipboardListener);
            } catch (Exception ignored) { }
        }

        /** 剪贴板监听：停止监听 */
        @JavascriptInterface
        public void stopClipboardWatch() {
            try {
                if (clipboardListener != null) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.removePrimaryClipChangedListener(clipboardListener);
                    clipboardListener = null;
                }
            } catch (Exception ignored) { }
        }

        /** 通知栏：显示一条通知（33+ 需先 requestPermission("notification")） */
        @JavascriptInterface
        public void showNotification(String title, String body) {
            if (title == null) title = "";
            if (body == null) body = "";
            final String t = title, b = body;
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(MainActivity.this, "未授予通知权限，请先 AndroidApp.requestPermission(\"notification\", cb)", Toast.LENGTH_LONG).show();
                        return;
                    }
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm == null) return;
                    PendingIntent pi = PendingIntent.getActivity(MainActivity.this, 0,
                            new Intent(MainActivity.this, MainActivity.class),
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    Notification n = new NotificationCompat.Builder(MainActivity.this, NOTIFY_CHANNEL)
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle(t)
                            .setContentText(b)
                            .setAutoCancel(true)
                            .setContentIntent(pi)
                            .build();
                    nm.notify(NOTIFY_ID_BASE, n);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "通知失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        /** 通知栏：取消指定 id 的通知（默认 2000） */
        @JavascriptInterface
        public void cancelNotification(int id) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(NOTIFY_ID_BASE + id);
            } catch (Exception ignored) { }
        }

        /** 通知栏：取消全部壳通知 */
        @JavascriptInterface
        public void cancelAllNotifications() {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancelAll();
            } catch (Exception ignored) { }
        }

        /**
         * 前台服务：常驻通知栏（如后台任务/计时/状态展示）。
         * Android 8.0+ 必须走 startForegroundService；调用后应尽快 stopForeground() 释放。
         * 返回是否已启动。
         */
        @JavascriptInterface
        public boolean startForeground(String title, String text) {
            if (title == null) title = "前台服务运行中";
            if (text == null) text = "";
            final String t = title, b = text;
            try {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(MainActivity.this, "前台服务需要通知权限，请先 requestPermission(\"notification\")", Toast.LENGTH_LONG).show();
                    return false;
                }
                Intent i = new Intent(MainActivity.this, ForegroundBridgeService.class);
                i.putExtra("title", t);
                i.putExtra("text", b);
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(i);
                } else {
                    startService(i);
                }
                return true;
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "前台服务失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                return false;
            }
        }

        /** 前台服务：停止并移除通知 */
        @JavascriptInterface
        public void stopForeground() {
            try {
                stopService(new Intent(MainActivity.this, ForegroundBridgeService.class));
            } catch (Exception ignored) { }
        }

        /** 深链：返回最近一次外部 VIEW 打开的数据（无则空串） */
        @JavascriptInterface
        public String getDeepLink() {
            return deepLink;
        }

        /** 权限管理面板：列出常用权限状态，可一键跳系统设置 */
        @JavascriptInterface
        public void openPermissionPanel() {
            runOnUiThread(() -> {
                try {
                    String[] names = {"camera", "mic", "storage", "notification", "location"};
                    StringBuilder sb = new StringBuilder();
                    for (String n : names) {
                        sb.append("• ").append(n).append(": ")
                                .append("true".equals(checkPermission(n)) ? "已授权" : "未授权").append('\n');
                    }
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("权限状态")
                            .setMessage(sb.toString())
                            .setPositiveButton("去系统设置", (d, w) -> openAppSettings())
                            .setNegativeButton("关闭", null)
                            .show();
                } catch (Exception ignored) { }
            });
        }

        // ============ v7 新增：JS 注入通道 ============

        /** 注册自动注入脚本：每次页面加载完成后执行（onPageFinished 时注入） */
        @JavascriptInterface
        public void addScriptInjector(String code) {
            if (code != null && !code.isEmpty() && code.length() <= 200_000) {
                scriptInjectors.add(code);
            }
        }

        /** 清空自动注入脚本 */
        @JavascriptInterface
        public void clearScriptInjectors() {
            scriptInjectors.clear();
        }

        /** 即时注入：立即在当前页面执行一段 JS（等价 evaluateJavascript） */
        @JavascriptInterface
        public void injectNow(String code) {
            if (code == null || code.isEmpty()) return;
            runOnUiThread(() -> {
                if (webView != null) webView.evaluateJavascript(code, null);
            });
        }

        // ============ v7 新增：离线缓存 ============

        /** 设置缓存模式：0=默认(网络优先) 1=本地优先(命中缓存不回源) 2=仅离线 */
        @JavascriptInterface
        public void setCacheMode(int mode) {
            cacheMode = (mode == CACHE_LOCAL_FIRST || mode == CACHE_OFFLINE) ? mode : CACHE_DEFAULT;
        }

        /** 当前缓存模式 */
        @JavascriptInterface
        public int getCacheMode() {
            return cacheMode;
        }

        /** 预缓存：把指定 URL 内容下载并存入本地离线缓存，完成回调 window[callbackName]({"url","ok"}) */
        @JavascriptInterface
        public void precacheUrl(String url, String callbackName) {
            if (url == null || callbackName == null || !callbackName.matches("[A-Za-z0-9_]{1,64}")) return;
            precacheUrlAsync(url, callbackName);
        }

        /** 清空离线缓存 */
        @JavascriptInterface
        public void clearCache() {
            runOnUiThread(() -> {
                try {
                    File dir = new File(getCacheDir(), "http_cache");
                    if (dir.isDirectory()) {
                        File[] fs = dir.listFiles();
                        if (fs != null) for (File f : fs) f.delete();
                    }
                    if (webView != null) webView.clearCache(true);
                } catch (Exception ignored) { }
            });
        }

        /**
         * 无 CORS 限制的网络代理：request(url, method, headersJson, body, callbackName)。
         * 回调：window[callbackName]({"status":200,"body":"..."})；网络错误时 status=0 且带 error。
         * 用于远程 API 调用（壳原生 HttpURLConnection，不受浏览器同源策略限制）。
         */
        @JavascriptInterface
        public void request(String url, String method, String headersJson, String body, String callbackName) {
            if (url == null || callbackName == null || !callbackName.matches("[A-Za-z0-9_]{1,64}")) return;
            final String u = url;
            final String m = method == null || method.isEmpty() ? "GET" : method.toUpperCase(Locale.US);
            final String h = headersJson;
            final String b = body;
            netExecutor.execute(() -> {
                String result;
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
                    conn.setRequestMethod(m);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("Accept", "*/*");
                    if (h != null && !h.isEmpty()) {
                        try {
                            JSONObject headers = new JSONObject(h);
                            Iterator<String> it = headers.keys();
                            while (it.hasNext()) {
                                String k = it.next();
                                conn.setRequestProperty(k, headers.getString(k));
                            }
                        } catch (Exception ignored) { }
                    }
                    if (b != null && !b.isEmpty()) {
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        try (OutputStream os = conn.getOutputStream()) {
                            os.write(b.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    int code = conn.getResponseCode();
                    InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    String text = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    conn.disconnect();
                    JSONObject out = new JSONObject();
                    out.put("status", code);
                    out.put("body", text);
                    result = out.toString();
                } catch (Exception e) {
                    JSONObject out = new JSONObject();
                    try {
                        out.put("status", 0);
                        out.put("error", String.valueOf(e.getMessage()));
                    } catch (Exception ignored) { }
                    result = out.toString();
                }
                final String js = "window['" + callbackName + "'] && window['" + callbackName + "']("
                        + JSONObject.quote(result) + ");";
                runOnUiThread(() -> {
                    if (webView != null) webView.evaluateJavascript(js, null);
                });
            });
        }
    }

    // ==================== 权限映射与桥回调 ====================

    /** 友好权限名 → 实际权限数组；返回空数组表示当前系统无需授权；null 表示未知权限名 */
    private String[] permissionMapping(String name) {
        if (name == null) return null;
        switch (name.trim().toLowerCase(Locale.US)) {
            case "camera":
                return new String[]{Manifest.permission.CAMERA};
            case "mic":
            case "microphone":
                return new String[]{Manifest.permission.RECORD_AUDIO};
            case "storage":
            case "media":
                if (Build.VERSION.SDK_INT >= 33) {
                    return new String[]{
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_MEDIA_AUDIO};
                }
                return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
            case "notification":
                if (Build.VERSION.SDK_INT >= 33) {
                    return new String[]{Manifest.permission.POST_NOTIFICATIONS};
                }
                return new String[0]; // 8.0-12 无需授权
            case "location":
                return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
            default:
                return null;
        }
    }

    /** 把授权结果回调给 HTML */
    private void dispatchPermissionResult(String permission, boolean granted) {
        JSONObject out = new JSONObject();
        try {
            out.put("permission", permission);
            out.put("granted", granted);
        } catch (Exception ignored) { }
        final String cb = pendingBridgeCallback == null ? "" : pendingBridgeCallback;
        final String js = "window['" + cb + "'] && window['" + cb + "'](" + JSONObject.quote(out.toString()) + ");";
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    // ==================== dt.jet 解密与解压（与 ApkPacker 对称） ====================

    private static byte[] aesDecrypt(byte[] jet) throws Exception {
        if (jet.length <= 16) throw new IllegalStateException("dt.jet 数据损坏");
        byte[] key = AES_KEY_STR.getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[16];
        System.arraycopy(jet, 0, iv, 0, 16);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(jet, 16, jet.length - 16);
    }

    private static void extractZip(byte[] zip, File outDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip));
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            File target = new File(outDir, entry.getName());
            // 路径穿越防护
            String canonical = target.getCanonicalPath();
            if (!canonical.startsWith(outDir.getCanonicalPath() + File.separator)
                    && !canonical.equals(outDir.getCanonicalPath())) {
                throw new SecurityException("非法路径: " + entry.getName());
            }
            if (entry.isDirectory()) {
                target.mkdirs();
            } else {
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
                }
            }
            zis.closeEntry();
        }
        zis.close();
    }

    private String readAssetString(String name) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open(name), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] readAsset(String name) {
        try {
            InputStream is = getAssets().open(name);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            is.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
