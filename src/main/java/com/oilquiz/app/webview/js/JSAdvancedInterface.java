package com.oilquiz.app.webview.js;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 高级安全 JS 桥（主应用 WebView 能力更新，保持低风险安全模型）：
 * - request()          网络代理：http/https 无 CORS 读取（响应 ≤2MB）
 * - speakText/stopSpeak 系统 TTS 朗读
 * - shareText()        系统分享
 * - setStatusBarStyle() 状态栏图标明暗（浅色页面用 dark，深色页面用 light）
 * - getStatusBarHeight()/getNavBarHeight() 系统栏高度（物理 px，JS 侧需 ÷devicePixelRatio）
 *
 * 安全边界：不暴露文件读写/系统操作/权限请求；request 仅允许 http/https；
 * 与壳 APK 的 62 桥定位不同——主应用 WebView 加载外部在线内容，只开放低风险能力。
 */
public class JSAdvancedInterface {
    private static final String TAG = "JSAdvancedInterface";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024; // 2MB

    private final WeakReference<Context> contextRef;
    private final WeakReference<WebView> webViewRef;
    private final Handler mainHandler;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private TextToSpeech tts;

    public JSAdvancedInterface(Context context, WebView webView) {
        this.contextRef = new WeakReference<>(context != null ? context.getApplicationContext() : null);
        this.webViewRef = new WeakReference<>(webView);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // ==================== 网络代理（无 CORS） ====================

    /**
     * 网络请求代理：GET/POST http/https，回调 window[callbackName]({status, body})。
     * 网络错误 status=0 且带 error；业务状态码原样返回（status=404 等）。
     */
    @JavascriptInterface
    public void request(final String url, final String method, final String body, final String callbackName) {
        final String cb = callbackName == null ? "" : callbackName;
        if (url == null || url.trim().isEmpty()) {
            callback(cb, "{\"status\":0,\"error\":\"url 为空\"}");
            return;
        }
        executor.execute(() -> {
            JSONObject res = new JSONObject();
            HttpURLConnection conn = null;
            try {
                URL u = new URL(url.trim());
                String proto = u.getProtocol() == null ? "" : u.getProtocol().toLowerCase(Locale.US);
                if (!"http".equals(proto) && !"https".equals(proto)) {
                    callback(cb, "{\"status\":0,\"error\":\"仅支持 http/https\"}");
                    return;
                }
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(true);
                String m = (method == null || method.trim().isEmpty()) ? "GET" : method.trim().toUpperCase(Locale.US);
                if (!"GET".equals(m) && !"POST".equals(m)) m = "GET";
                conn.setRequestMethod(m);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SmartQuiz-WebView");
                conn.setRequestProperty("Accept", "*/*");
                if ("POST".equals(m) && body != null && !body.isEmpty()) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.getBytes("UTF-8"));
                    }
                }
                int status = conn.getResponseCode();
                InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                StringBuilder sb = new StringBuilder();
                int total = 0;
                if (is != null) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                        char[] buf = new char[8192];
                        int n;
                        while ((n = reader.read(buf)) > 0) {
                            total += n;
                            if (total > MAX_RESPONSE_BYTES) {
                                sb.setLength(0);
                                sb.append("{\"error\":\"响应超过 2MB 限制\"}");
                                break;
                            }
                            sb.append(buf, 0, n);
                        }
                    }
                }
                res.put("status", status);
                res.put("body", sb.toString());
            } catch (Exception e) {
                Log.w(TAG, "request 失败: " + url, e);
                try {
                    res.put("status", 0);
                    res.put("error", String.valueOf(e.getMessage()));
                } catch (Exception ignored) { }
            } finally {
                if (conn != null) conn.disconnect();
                callback(cb, res.toString());
            }
        });
    }

    // ==================== TTS 朗读 ====================

    @JavascriptInterface
    public void speakText(final String text) {
        if (text == null || text.isEmpty()) return;
        Context ctx = contextRef.get();
        if (ctx == null) return;
        if (tts == null) {
            tts = new TextToSpeech(ctx.getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(Locale.CHINA);
                    speak(text);
                } else {
                    Log.w(TAG, "TTS 初始化失败 status=" + status);
                }
            });
        } else {
            speak(text);
        }
    }

    @JavascriptInterface
    public void stopSpeak() {
        if (tts != null) tts.stop();
    }

    private void speak(String text) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "js_tts_" + System.currentTimeMillis());
            } else {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "TTS speak 失败", e);
        }
    }

    // ==================== 分享 ====================

    @JavascriptInterface
    public void shareText(final String text) {
        Context ctx = contextRef.get();
        if (ctx == null || text == null || text.isEmpty()) return;
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(Intent.createChooser(send, "分享到"));
        } catch (Exception e) {
            Log.w(TAG, "shareText 失败", e);
        }
    }

    // ==================== 状态栏适配 ====================

    /** 状态栏图标明暗：light=浅色图标（深色页面，默认），dark=深色图标（浅色页面） */
    @JavascriptInterface
    public void setStatusBarStyle(final String style) {
        Context ctx = contextRef.get();
        if (!(ctx instanceof Activity)) return;
        final Activity activity = (Activity) ctx;
        mainHandler.post(() -> {
            try {
                Window w = activity.getWindow();
                if (w == null) return;
                View decor = w.getDecorView();
                int flags = decor.getSystemUiVisibility();
                if ("dark".equals(style)) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                decor.setSystemUiVisibility(flags);
            } catch (Exception e) {
                Log.w(TAG, "setStatusBarStyle 失败", e);
            }
        });
    }

    /** 状态栏高度（物理 px；JS 侧需 ÷devicePixelRatio 得 CSS px） */
    @JavascriptInterface
    public String getStatusBarHeight() {
        Context ctx = contextRef.get();
        if (ctx == null) return "0";
        try {
            int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
            return id > 0 ? String.valueOf(ctx.getResources().getDimensionPixelSize(id)) : "0";
        } catch (Exception e) {
            return "0";
        }
    }

    /** 导航栏高度（物理 px；手势导航=0，三键返回实体高度；JS 侧需 ÷devicePixelRatio） */
    @JavascriptInterface
    public String getNavBarHeight() {
        Context ctx = contextRef.get();
        if (ctx == null) return "0";
        try {
            int id = ctx.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            return id > 0 ? String.valueOf(ctx.getResources().getDimensionPixelSize(id)) : "0";
        } catch (Exception e) {
            return "0";
        }
    }

    // ==================== 内部：回调 ====================

    private void callback(final String cb, final String json) {
        if (cb.isEmpty()) return;
        final WebView wv = webViewRef.get();
        if (wv == null) return;
        mainHandler.post(() -> {
            try {
                String escaped = json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
                wv.evaluateJavascript("try{window['" + cb + "'](" + escaped + ");}catch(e){console.error('AndroidAdvanced 回调失败',e);}", null);
            } catch (Exception e) {
                Log.w(TAG, "回调执行失败", e);
            }
        });
    }

    /** 释放资源（Activity onDestroy 时调用） */
    public void destroy() {
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
        }
        executor.shutdownNow();
    }
}
