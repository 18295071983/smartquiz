package com.html2apk.wrapper;

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
import android.content.pm.ActivityInfo;
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
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
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
import android.media.AudioAttributes;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * SmartQuiz 设备端 APK 导出壳 v8（专业版）。
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
    private static final String SHELL_VERSION = "v8.1";
    /** 桥 API 版本（新增/变更桥方法时递增，HTML 可据此做能力探测） */
    private static final int BRIDGE_API = 5;
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
    /** v8：沉浸全屏状态（失焦后重新应用） */
    private boolean fsImmersive = false;
    /** v8：TTS 朗读器（speakText 使用，延迟初始化） */
    private android.speech.tts.TextToSpeech tts;
    /** TTS 引擎是否就绪（异步初始化完成标志） */
    private volatile boolean ttsReady = false;
    /** 引擎初始化期间待播文本（初始化完成后自动补播） */
    private volatile String pendingTts = null;
    private volatile String pendingVoiceId = null;
    private String appliedVoiceName = null;
    /** TTS 错误描述（初始化失败/语言缺失等；null=正常） */
    private volatile String ttsError = null;

    private String mainFile = "index.html";
    private String remoteUrl = "";

    /* ==================== TTS 增强（v8.1）：预热 + 音频属性 + 失败可感知 ==================== */
    private void warmupTts() { try { if (tts == null) initTts(); } catch (Exception ignored) { } }

    private void initTts() {
        try {
            tts = new android.speech.tts.TextToSpeech(this, status -> {
                if (status == android.speech.tts.TextToSpeech.SUCCESS && tts != null) {
                    try {
                        int r = tts.setLanguage(Locale.CHINA);
                        if (r == android.speech.tts.TextToSpeech.LANG_MISSING_DATA
                                || r == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                            tts.setLanguage(Locale.getDefault());
                        }
                        tts.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                        ttsReady = true; ttsError = null;
                        applyVoice(pendingVoiceId);
                        if (pendingTts != null) { speakNow(pendingTts); pendingTts = null; }
                    } catch (Exception e) { ttsError = "lang:" + e.getMessage(); }
                } else {
                    ttsReady = false; ttsError = "init:status=" + status + ";" + engineSummary();
                    scheduleTtsRetry();
                }
            });
        } catch (Exception e) { ttsError = "init_ex:" + e.getMessage(); scheduleTtsRetry(); }
    }

    /** 系统 TTS 引擎摘要（诊断用）：列出可用引擎 */
    private String engineSummary() {
        try {
            if (tts == null) return "无TTS引擎实例";
            java.util.List<android.speech.tts.TextToSpeech.EngineInfo> es = tts.getEngines();
            if (es == null || es.isEmpty()) return "无TTS引擎";
            StringBuilder sb = new StringBuilder();
            for (android.speech.tts.TextToSpeech.EngineInfo e : es) {
                if (sb.length() > 0) sb.append(",");
                sb.append(e.name);
            }
            return "引擎[" + sb + "]";
        } catch (Exception e) { return "engine_query_fail"; }
    }

    /** TTS 初始化失败后延迟重试（引擎可能未就绪/被杀） */
    private void scheduleTtsRetry() {
        if (tts != null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (tts == null) { try { initTts(); } catch (Exception ignored) { } }
        }, 2000);
    }

    /** 应用 TTS 音色：sys: 前缀指定；否则锁定稳定中文音色（避免音色漂移/无声） */
    private void applyVoice(String voiceId) {
        if (tts == null || !ttsReady) return;
        String target = null;
        if (voiceId != null && voiceId.startsWith("sys:")) target = voiceId.substring(4);
        else target = findDefaultZhVoice();
        if (target == null || target.equals(appliedVoiceName)) return;
        try {
            java.util.Set<android.speech.tts.Voice> voices = tts.getVoices();
            if (voices == null) return;
            for (android.speech.tts.Voice v : voices) {
                if (v != null && target.equals(v.getName())) { tts.setVoice(v); appliedVoiceName = target; return; }
            }
        } catch (Exception ignored) { }
    }

    /** 寻找稳定的中文默认音色（zh/cmn 优先，回退任意） */
    private String findDefaultZhVoice() {
        try {
            java.util.Set<android.speech.tts.Voice> voices = tts.getVoices();
            if (voices == null) return null;
            String fallback = null;
            for (android.speech.tts.Voice v : voices) {
                if (v == null || v.getName() == null || v.getName().isEmpty()) continue;
                if (fallback == null) fallback = v.getName();
                if (v.getLocale() != null) {
                    String l = v.getLocale().toString().toLowerCase();
                    if (l.contains("zh") || l.contains("cmn")) return v.getName();
                }
            }
            return fallback;
        } catch (Exception e) { return null; }
    }

    private boolean speakNow(String text) {
        try {
            int rc = tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                    null, "tts_" + System.currentTimeMillis());
            if (rc != android.speech.tts.TextToSpeech.SUCCESS && ttsReady) {
                // 引擎可能失活：复位并延迟重试一次
                ttsReady = false; ttsError = "speak_rc=" + rc;
                android.speech.tts.TextToSpeech old = tts; tts = null;
                try { old.shutdown(); } catch (Exception ignored) { }
                if (pendingTts == null) pendingTts = text;
                scheduleTtsRetry();
                return false;
            }
            return rc == android.speech.tts.TextToSpeech.SUCCESS;
        } catch (Exception e) { return false; }
    }

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

        // 默认边缘到边：状态栏/导航栏透明，内容延伸到系统栏后（HTML 用 safe-area/--sa-* 撑开）
        applyStatusBarDefault();

        new Thread(this::bootstrap, "ShellBootstrap").start();
        // TTS 预热：避免首次报时/朗读等待引擎初始化
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::warmupTts, 1200);
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
                    + out.toString() + ");";
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
                String reason = granted ? "granted" : "denied";
                if (!granted && permissions != null) {
                    boolean forever = true;
                    for (String p : permissions) {
                        if (shouldShowRequestPermissionRationale(p)) forever = false;
                    }
                    if (forever) reason = "denied_forever";
                }
                dispatchPermissionResult(pendingBridgePermission, granted, reason);
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

    /** v8：窗口失焦/回来时，沉浸全屏状态被系统清除则重新应用（弹窗/通知栏/切后台后自动恢复） */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fsImmersive) applyImmersive(true);
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
        // TTS 清理
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
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
                    + "{\"url\":" + JSONObject.quote(url) + ",\"ok\":" + success + "}" + ");";
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

        // ============ 状态栏适配（"light"=浅色图标/深色页面，默认；"dark"=深色图标/浅色页面） ============

        @JavascriptInterface
        public void setStatusBarStyle(String style) {
            final String st = (style == null || style.trim().isEmpty()) ? "light" : style.trim().toLowerCase(Locale.US);
            runOnUiThread(() -> applyStatusBarStyle(st));
        }


        @JavascriptInterface
        public String getStatusBarHeight() {
            return String.valueOf(statusBarHeightPx());
        }

        @JavascriptInterface
        public String getNavBarHeight() {
            return String.valueOf(navBarHeightPx());
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
                dispatchPermissionResult(name, false, "unknown");
                return;
            }
            if (perms.length == 0) {
                dispatchPermissionResult(name, true, "granted");
                return;
            }
            // 防重复弹窗：已有待授权请求时直接返回 busy，不再叠加系统权限框
            if (pendingBridgePermission != null) {
                dispatchPermissionResult(name, false, "busy");
                return;
            }
            boolean allGranted = true;
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }
            if (allGranted) {
                dispatchPermissionResult(name, true, "granted");
                return;
            }
            // 已被永久拒绝（不再询问）：不弹窗，返回 denied_forever，JS 可引导打开权限设置页
            boolean deniedForever = true;
            for (String p : perms) {
                if (shouldShowRequestPermissionRationale(p)) deniedForever = false;
            }
            if (deniedForever) {
                dispatchPermissionResult(name, false, "denied_forever");
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
                        + out.toString() + ");";
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
                                            + "{\"text\":" + JSONObject.quote(t) + "}" + ");", null);
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
                        + result + ");";
                runOnUiThread(() -> {
                    if (webView != null) webView.evaluateJavascript(js, null);
                });
            });
        }

        // ============ v8（bridge_api 4）：沉浸全屏 + 屏幕方向 + 亮度 + TTS + 壳内文件 + 原生选择器/对话框 + 系统信息 ============

        /** 沉浸全屏：true=隐藏系统状态栏/导航栏进入沉浸；false=恢复。失焦后自动重新应用 */
        @JavascriptInterface
        public void enterFullscreen(final boolean on) {
            runOnUiThread(() -> { fsImmersive = on; applyImmersive(on); });
        }

        /** 当前是否沉浸全屏（供页面初始化对齐） */
        @JavascriptInterface
        public boolean isFullscreen() {
            return fsImmersive;
        }

        /** 屏幕方向：landscape / portrait / auto */
        @JavascriptInterface
        public void setOrientation(final String mode) {
            runOnUiThread(() -> {
                int req;
                if ("landscape".equals(mode)) req = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                else if ("portrait".equals(mode)) req = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                else req = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; // auto
                try { setRequestedOrientation(req); } catch (Exception ignored) { }
            });
        }

        /** 当前物理方向：landscape / portrait */
        @JavascriptInterface
        public String getOrientation() {
            return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                    ? "landscape" : "portrait";
        }

        /** 设置屏幕亮度（0-255；-1 恢复跟随系统） */
        @JavascriptInterface
        public void setBrightness(final int value) {
            runOnUiThread(() -> {
                try {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    if (value < 0) lp.screenBrightness = -1f;
                    else lp.screenBrightness = Math.max(0.01f, Math.min(1f, value / 255f));
                    getWindow().setAttributes(lp);
                } catch (Exception ignored) { }
            });
        }

        /** 当前屏幕亮度（0-255；-1 表示跟随系统） */
        @JavascriptInterface
        public float getBrightness() {
            try {
                float b = getWindow().getAttributes().screenBrightness;
                return b < 0 ? -1 : Math.round(b * 255);
            } catch (Exception e) {
                return -1;
            }
        }

        /** 语音朗读（TTS，中文；重复调用会打断上一次）
         *  返回 JSON 状态：{"ok":true} / {"ok":false,"error":...} / "pending"（初始化中，完成后自动补播）
         *  老壳返回 undefined（void）——JS 侧需兼容 */
        @JavascriptInterface
        public String speakText(final String text) { return speakText(text, null); }

        /** 语音朗读（TTS，中文；voiceId 可选：sys: 前缀指定系统音色，null=锁定默认中文音色）
         *  返回 {"ok":true} / {"ok":false,"error":...} / "pending"（初始化中，完成后自动补播） */
        @JavascriptInterface
        public String speakText(final String text, final String voiceId) {
            if (text == null || text.isEmpty()) return "{\"ok\":false,\"error\":\"empty\"}";
            try {
                if (voiceId != null && !voiceId.isEmpty()) pendingVoiceId = voiceId;
                if (tts == null) { pendingTts = text; initTts(); return "pending"; }
                else if (ttsReady) {
                    applyVoice(pendingVoiceId);
                    return speakNow(text) ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"speak_fail\"}";
                }
                else { pendingTts = text; return "pending"; }
            } catch (Exception e) { return "{\"ok\":false,\"error\":\"exception\"}"; }
        }

        /** TTS 状态查询：{"ready":true/false,"error":null|"..."} */
        @JavascriptInterface
        public String ttsState() {
            return "{\"ready\":" + ttsReady + ",\"error\":" + (ttsError == null ? "null" : org.json.JSONObject.quote(ttsError)) + ",\"engines\":" + org.json.JSONObject.quote(engineSummary()) + "}";
        }

        /** 系统 TTS 音色列表（JSON：{"ok":true,"voices":[{id:"sys:xxx",name:"xxx（系统·locale）"}]}，中文优先） */
        @JavascriptInterface
        public String ttsVoices() {
            try {
                if (tts == null || !ttsReady) {
                    if (tts == null) initTts();
                    return "{\"ok\":false,\"error\":\"tts_not_ready\",\"voices\":[]}";
                }
                java.util.Set<android.speech.tts.Voice> voices = tts.getVoices();
                org.json.JSONArray arr = new org.json.JSONArray();
                if (voices != null) {
                    java.util.HashSet<String> seen = new java.util.HashSet<>();
                    java.util.List<String[]> list = new java.util.ArrayList<>();
                    for (android.speech.tts.Voice v : voices) {
                        if (v == null || v.getName() == null || v.getName().isEmpty()) continue;
                        if (!seen.add(v.getName())) continue;
                        String loc = v.getLocale() != null ? v.getLocale().toString() : "";
                        list.add(new String[]{ "sys:" + v.getName(), v.getName() + "（系统·" + loc + "）", loc.toLowerCase() });
                    }
                    list.sort((a, b) -> {
                        boolean az = a[2].contains("zh") || a[2].contains("cmn");
                        boolean bz = b[2].contains("zh") || b[2].contains("cmn");
                        return Boolean.compare(bz, az);
                    });
                    for (String[] s : list) {
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("id", s[0]); o.put("name", s[1]);
                        arr.put(o);
                    }
                }
                return "{\"ok\":true,\"voices\":" + arr.toString() + "}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"exception\"}";
            }
        }

        /** 设置 TTS 音色（sys: 前缀；立即应用，后续 speakText 默认使用；null=回到自动锁定中文音色） */
        @JavascriptInterface
        public void setTtsVoice(final String voiceId) {
            pendingVoiceId = (voiceId != null && !voiceId.isEmpty()) ? voiceId : null;
            runOnUiThread(() -> applyVoice(pendingVoiceId));
        }

        /** 停止语音朗读 */
        @JavascriptInterface
        public void stopSpeak() {
            pendingTts = null;
            runOnUiThread(() -> {
                try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
            });
        }

        /** 保存文件到壳内目录（相对 htmlDir，如 "data/a.txt"；base64 内容；≤8MB） */
        @JavascriptInterface
        public String saveFile(String relativePath, String dataBase64) {
            if (relativePath == null || dataBase64 == null) return "{\"ok\":false,\"error\":\"参数为空\"}";
            try {
                File f = resolveShellFile(relativePath);
                if (f == null) return "{\"ok\":false,\"error\":\"路径非法\"}";
                byte[] data = android.util.Base64.decode(dataBase64, android.util.Base64.DEFAULT);
                if (data.length > 8 * 1048576L) return "{\"ok\":false,\"error\":\"超过8MB限制\"}";
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(data); }
                return "{\"ok\":true,\"size\":" + data.length + "}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
            }
        }

        /** 读取壳内文件（相对 htmlDir；返回 base64；≤8MB） */
        @JavascriptInterface
        public String readFile(String relativePath) {
            try {
                File f = resolveShellFile(relativePath);
                if (f == null || !f.isFile()) return "{\"ok\":false,\"error\":\"文件不存在\"}";
                if (f.length() > 8 * 1048576L) return "{\"ok\":false,\"error\":\"超过8MB限制\"}";
                byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("size", data.length);
                o.put("dataBase64", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
                return o.toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
            }
        }

        /** 列出壳内目录（相对 htmlDir；空/缺省=根） */
        @JavascriptInterface
        public String listFiles(String relativeDir) {
            try {
                File dir = (relativeDir == null || relativeDir.isEmpty()) ? htmlDir : resolveShellFile(relativeDir);
                if (dir == null || !dir.isDirectory()) return "{\"ok\":false,\"error\":\"目录不存在\"}";
                File[] fs = dir.listFiles();
                org.json.JSONArray arr = new org.json.JSONArray();
                if (fs != null) {
                    for (File f : fs) {
                        JSONObject o = new JSONObject();
                        o.put("name", f.getName());
                        o.put("isDir", f.isDirectory());
                        o.put("size", f.isDirectory() ? 0 : f.length());
                        arr.put(o);
                    }
                }
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("files", arr);
                return out.toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
            }
        }

        /** 删除壳内文件/空目录（相对 htmlDir） */
        @JavascriptInterface
        public String deleteFile(String relativePath) {
            File f = resolveShellFile(relativePath);
            if (f == null) return "{\"ok\":false,\"error\":\"路径非法\"}";
            if (!f.exists()) return "{\"ok\":false,\"error\":\"不存在\"}";
            if (f.isDirectory()) {
                File[] children = f.listFiles();
                if (children != null && children.length > 0) return "{\"ok\":false,\"error\":\"目录非空\"}";
            }
            return f.delete() ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"删除失败\"}";
        }

        /** 原生日期选择器：回调 window[callbackName]({"year","month","day"}) */
        @JavascriptInterface
        public void pickDate(final String callbackName) {
            if (callbackName == null || !callbackName.matches("[A-Za-z0-9_]{1,64}")) return;
            runOnUiThread(() -> {
                try {
                    java.util.Calendar c = java.util.Calendar.getInstance();
                    new android.app.DatePickerDialog(MainActivity.this,
                            (view, y, m, d) -> {
                                JSONObject o = new JSONObject();
                                try { o.put("year", y); o.put("month", m + 1); o.put("day", d); } catch (Exception ignored) { }
                                callJsCallback(callbackName, o.toString());
                            },
                            c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH),
                            c.get(java.util.Calendar.DAY_OF_MONTH)).show();
                } catch (Exception e) {
                    callJsCallback(callbackName, "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}");
                }
            });
        }

        /** 原生时间选择器：回调 window[callbackName]({"hour","minute"}) */
        @JavascriptInterface
        public void pickTime(final String callbackName) {
            if (callbackName == null || !callbackName.matches("[A-Za-z0-9_]{1,64}")) return;
            runOnUiThread(() -> {
                try {
                    java.util.Calendar c = java.util.Calendar.getInstance();
                    new android.app.TimePickerDialog(MainActivity.this,
                            (view, h, m) -> {
                                JSONObject o = new JSONObject();
                                try { o.put("hour", h); o.put("minute", m); } catch (Exception ignored) { }
                                callJsCallback(callbackName, o.toString());
                            },
                            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), true).show();
                } catch (Exception e) {
                    callJsCallback(callbackName, "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}");
                }
            });
        }

        /** 原生确认对话框：确定→{"result":"ok"}，取消→{"result":"cancel"} */
        @JavascriptInterface
        public void showDialog(final String title, final String message, final String callbackName) {
            if (callbackName == null || !callbackName.matches("[A-Za-z0-9_]{1,64}")) return;
            runOnUiThread(() -> {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(title == null ? "" : title)
                        .setMessage(message == null ? "" : message)
                        .setPositiveButton("确定", (d, w) -> callJsCallback(callbackName, "{\"result\":\"ok\"}"))
                        .setNegativeButton("取消", (d, w) -> callJsCallback(callbackName, "{\"result\":\"cancel\"}"))
                        .show();
            });
        }

        /** 系统信息：语言/时区/Android版本/品牌/设备/状态栏导航栏高度 */
        @JavascriptInterface
        public String getSystemInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("language", Locale.getDefault().toLanguageTag());
                o.put("timeZone", java.util.TimeZone.getDefault().getID());
                o.put("androidVersion", Build.VERSION.RELEASE);
                o.put("brand", Build.BRAND);
                o.put("device", Build.DEVICE);
                int statusBar = 0, navBar = 0;
                try {
                    int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
                    if (resId > 0) statusBar = getResources().getDimensionPixelSize(resId);
                    resId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
                    if (resId > 0) navBar = getResources().getDimensionPixelSize(resId);
                } catch (Exception ignored) { }
                o.put("statusBarHeight", statusBar);
                o.put("navigationBarHeight", navBar);
            } catch (Exception ignored) { }
            return o.toString();
        }

        /** 检查系统是否安装了指定应用 */
        @JavascriptInterface
        public boolean isAppInstalled(String packageName) {
            if (packageName == null || packageName.isEmpty()) return false;
            try {
                getPackageManager().getPackageInfo(packageName, 0);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** 打开其他应用（按包名） */
        @JavascriptInterface
        public void openApp(final String packageName) {
            if (packageName == null || packageName.isEmpty()) return;
            runOnUiThread(() -> {
                try {
                    Intent i = getPackageManager().getLaunchIntentForPackage(packageName);
                    if (i != null) {
                        startActivity(i);
                    } else {
                        Toast.makeText(MainActivity.this, "未找到应用: " + packageName, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开应用", Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** 在壳内打开指定 URL（远程页面/本地路径，路由到主 WebView） */
        @JavascriptInterface
        public void openInApp(String url) {
            if (url == null || url.isEmpty()) return;
            runOnUiThread(() -> { try { loadUrl(url); } catch (Exception ignored) { } });
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
    private static String permissionHumanName(String p) {
        if (p == null) return "";
        switch (p) {
            case "camera": return "相机";
            case "mic": return "麦克风";
            case "storage": return "存储";
            case "notification": return "通知";
            case "location": return "位置";
            default: return p;
        }
    }

    private void dispatchPermissionResult(String permission, boolean granted, String reason) {
        JSONObject out = new JSONObject();
        try {
            String r = reason == null ? "" : reason;
            String hn = permissionHumanName(permission);
            String human;
            if (granted) human = "已获得" + hn + "权限，可以正常使用相关功能";
            else if ("denied_forever".equals(r)) human = hn + "权限已被系统拒绝且不再询问，请到系统设置中手动开启（设置 → 应用 → 权限）";
            else if ("busy".equals(r)) human = "已有" + hn + "权限请求正在处理中，请稍后再试";
            else if ("unknown".equals(r)) human = "无法识别权限：" + hn;
            else human = hn + "权限被拒绝，相关功能将不可用，可在需要时重新申请";
            out.put("permission", permission);
            out.put("granted", granted);
            out.put("reason", r);
            out.put("human", human);
        } catch (Exception ignored) { }
        final String cb = pendingBridgeCallback == null ? "" : pendingBridgeCallback;
        final String js = "window['" + cb + "'] && window['" + cb + "'](" + out.toString() + ");";
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    // ==================== v8.1 状态栏适配：边缘到边 + 图标明暗 ====================

    /** 默认边缘到边：状态栏/导航栏透明，内容延伸到系统栏后面（HTML 用 safe-area / --sa-* 撑开） */
    private void applyStatusBarDefault() {
        Window w = getWindow();
        if (w == null) return;
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            try { w.setNavigationBarColor(android.graphics.Color.TRANSPARENT); } catch (Exception ignored) { }
        }
        /* 关键：清除 FLAG_LAYOUT_INSET_DECOR（系统/主题默认加入，会让内容避开状态栏、状态栏区域由系统画黑条），
           并确保 LAYOUT_IN_SCREEN|LAYOUT_FULLSCREEN，让页面真正延伸到状态栏/导航栏后 */
        w.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        View dv = w.getDecorView();
        int flags = dv.getSystemUiVisibility();
        flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        dv.setSystemUiVisibility(flags);
        applyStatusBarStyle("light");
    }

    /** 状态栏/导航栏图标明暗：style="light"=浅色图标（深色页面，默认）；"dark"=深色图标（浅色页面） */
    private void applyStatusBarStyle(String style) {
        Window w = getWindow();
        if (w == null) return;
        boolean darkIcons = "dark".equalsIgnoreCase(style);
        View dv = w.getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                int appear = darkIcons ? mask : 0;
                c.setSystemBarsAppearance(appear, mask);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            int flags = dv.getSystemUiVisibility();
            if (darkIcons) {
                flags |= 0x00002000; /* SYSTEM_UI_FLAG_LIGHT_STATUS_BARS (API 23+) */
            } else {
                flags &= ~0x00002000;
            }
            dv.setSystemUiVisibility(flags);
        }
    }

    /** 状态栏高度（px；HTML 可据此做沉浸适配，优先于 env(safe-area-inset-top)） */
    private int statusBarHeightPx() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Exception ignored) { }
        return 0;
    }

    /** 导航栏高度（px；手势导航为 0，三键导航返回实体高度） */
    private int navBarHeightPx() {
        try {
            int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Exception ignored) { }
        return 0;
    }

    // ==================== v8 辅助：沉浸全屏 / 壳内文件解析 / JS 回调 ====================

    /** 沉浸实现：API 30+ 走 WindowInsetsController，26-29 走老 flag；并通知页面（window.__shellFullscreen + shellfullscreenchange 事件） */
    private void applyImmersive(boolean on) {
        Window w = getWindow();
        if (w == null) return;
        /* 全屏进入/退出都保持状态栏/导航栏背景透明（防小米等系统在隐藏不完全时显示黑色条：竖屏顶部/横屏左侧） */
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            try { w.setNavigationBarColor(android.graphics.Color.TRANSPARENT); } catch (Exception ignored) { }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                if (on) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            View dv = w.getDecorView();
            int flags = dv.getSystemUiVisibility();
            if (on) {
                flags |= View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            } else {
                flags &= ~(View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
            dv.setSystemUiVisibility(flags);
        }
        try {
            webView.evaluateJavascript(
                    "window.__shellFullscreen=" + on + ";"
                            + "try{window.dispatchEvent(new Event('shellfullscreenchange'));}catch(e){}", null);
        } catch (Exception ignored) { }
    }

    /** 安全解析壳内文件路径（相对 htmlDir，拒绝 .. 穿越）；返回 null 表示非法 */
    private File resolveShellFile(String relativePath) {
        try {
            if (relativePath == null || htmlDir == null) return null;
            File f = new File(htmlDir, relativePath);
            String canonical = f.getCanonicalPath();
            String root = htmlDir.getCanonicalPath();
            if (!canonical.startsWith(root + File.separator) && !canonical.equals(root)) return null;
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    /** 通用 JS 回调：window[callbackName](json 字符串)（UI 线程执行） */
    private void callJsCallback(String callbackName, String json) {
        final String js = "window['" + callbackName + "'] && window['" + callbackName + "']("
                + json + ");";
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
