package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.ai.agent.online.AgentWorkspace;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 网页渲染浏览工具：WebView 加载 URL → 提取页面标题/正文文本/链接数（可选首屏截图保存）。
 * 对齐电脑端 Agent"打开网页看效果"的能力：此前手机端只有 requests 抓 HTML 文本（python_web_reader），
 * 无法看到 JS 渲染后的真实页面内容；本工具用真实 WebView 渲染（SPA/JS 动态内容可提取）。
 *
 * 安全：
 * - 仅允许 http/https URL（禁 file/content）；
 * - 禁文件访问、禁弹窗、禁 file URL 访问；
 * - 每次执行新建 WebView，用完销毁；并发上限 2。
 *
 * 参数：
 * - url: 要浏览的网页地址（必填，http/https）
 * - action: text(默认，提取标题+正文文本) / screenshot(仅截图) / both(文本+截图)
 * - timeout: 页面加载超时秒数（默认 12，最大 30）
 */
@Tool(value = "web_render", category = "search")
public class WebRenderTool implements AITool {

    private static final String TAG = "WebRenderTool";
    private static final int DEFAULT_TIMEOUT_SECONDS = 12;
    private static final int MAX_TIMEOUT_SECONDS = 30;
    private static final int MAX_CONCURRENCY = 2;
    private static final int TEXT_MAX_LENGTH = 8000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.Semaphore CONCURRENCY =
            new java.util.concurrent.Semaphore(MAX_CONCURRENCY);

    private final Context appContext;

    public WebRenderTool() {
        this.appContext = SmartQuizApplication.getAppContext();
    }

    public WebRenderTool(Context context) {
        this.appContext = context != null
                ? context.getApplicationContext()
                : SmartQuizApplication.getAppContext();
    }

    @Override
    public String getName() {
        return "web_render";
    }

    @Override
    public String getDescription() {
        return "网页渲染浏览工具：用真实 WebView 打开网页并提取内容（能看到 JS 渲染后的真实页面，"
                + "比 requests 抓 HTML 文本更完整）。"
                + "url=网页地址（必填）；action=text（默认，返回标题+正文文本+链接数）/screenshot（截图保存）/both（文本+截图）。"
                + "支持现代网页（含 SPA/JS 动态内容，加载完成后自动提取）；截图保存到工作区 files/screenshots/，返回路径。"
                + "限制：仅支持 http/https；不能操作页面（点击/填表/登录），只读浏览。"
                + "适合：查看网页实际内容、核对页面效果、调研资料。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("url", "要浏览的网页地址（必填，http/https）");
        params.put("action", "操作: text(默认，提取标题+正文) / screenshot(仅截图) / both(文本+截图)");
        params.put("timeout", "页面加载超时秒数（默认 12，最大 30）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        Object urlObj = parameters.get("url");
        if (urlObj == null || String.valueOf(urlObj).trim().isEmpty()) {
            return AIToolResult.fail("缺少参数: url（要浏览的网页地址）");
        }
        String url = String.valueOf(urlObj).trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return AIToolResult.fail("仅支持 http/https 地址: " + url);
        }
        String action = "text";
        Object actObj = parameters.get("action");
        if (actObj != null) {
            String a = String.valueOf(actObj).trim().toLowerCase();
            if (a.equals("screenshot") || a.equals("both")) action = a;
        }
        int timeout = DEFAULT_TIMEOUT_SECONDS;
        try {
            Object t = parameters.get("timeout");
            if (t != null) {
                double secs = Double.parseDouble(String.valueOf(t));
                timeout = (int) Math.min(MAX_TIMEOUT_SECONDS, Math.max(3, secs));
            }
        } catch (Exception ignored) {
        }

        if (!CONCURRENCY.tryAcquire()) {
            return AIToolResult.fail("网页浏览并发已满（同时最多 " + MAX_CONCURRENCY + " 个），请稍后重试");
        }
        try {
            return render(url, action, timeout);
        } finally {
            CONCURRENCY.release();
        }
    }

    private AIToolResult render(String url, String action, int timeoutSeconds) {
        final CountDownLatch pageLoaded = new CountDownLatch(1);
        final CountDownLatch textDone = new CountDownLatch(1);
        final AtomicReference<String> pageError = new AtomicReference<>();
        final AtomicReference<String> pageJson = new AtomicReference<>();
        final WebView[] holder = new WebView[1];
        final Throwable[] setupError = new Throwable[1];

        MAIN.post(() -> {
            try {
                WebView webView = new WebView(appContext);
                holder[0] = webView;
                WebSettings s = webView.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setAllowFileAccess(false);
                s.setAllowContentAccess(false);
                s.setAllowFileAccessFromFileURLs(false);
                s.setAllowUniversalAccessFromFileURLs(false);
                s.setJavaScriptCanOpenWindowsAutomatically(false);
                s.setLoadWithOverviewMode(true);
                s.setUseWideViewPort(true);
                s.setSupportZoom(false);
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        pageLoaded.countDown();
                    }

                    @Override
                    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                        // 仅当主页面加载失败才记录（子资源错误忽略）
                        if (failingUrl != null && failingUrl.equalsIgnoreCase(url)) {
                            pageError.set("页面加载失败: code=" + errorCode + " " + description);
                            pageLoaded.countDown();
                        }
                    }
                });
                webView.loadUrl(url);
            } catch (Throwable t) {
                setupError[0] = t;
                pageLoaded.countDown();
                textDone.countDown();
            }
        });

        try {
            // 等页面加载（超时后仍尝试提取已渲染部分）
            boolean finished = pageLoaded.await(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished && pageError.get() == null) {
                pageError.set("页面加载超时（" + timeoutSeconds + "s），已尝试提取当前已渲染内容");
            }
            if (setupError[0] != null) {
                return AIToolResult.fail("网页浏览环境初始化失败: " + setupError[0].getMessage());
            }
            final WebView webView = holder[0];
            if (webView == null) {
                return AIToolResult.fail("网页浏览环境不可用");
            }

            // 等 JS 渲染（SPA 常见，短延迟提高正文完整度；页面失败则跳过）
            if (pageError.get() == null || finished) {
                Thread.sleep(600);
            }

            // 提取 DOM 文本
            final boolean needText = !action.equals("screenshot");
            if (needText) {
                MAIN.post(() -> {
                    try {
                        webView.evaluateJavascript(
                                "(function(){var t=document.title||'';var b=document.body?document.body.innerText:'';"
                                        + "return JSON.stringify({title:t,text:b,links:document.links?document.links.length:0});})()",
                                value -> {
                                    pageJson.set(value != null ? value : "null");
                                    textDone.countDown();
                                });
                    } catch (Throwable t) {
                        setupError[0] = t;
                        textDone.countDown();
                    }
                });
                if (!textDone.await(8, TimeUnit.SECONDS)) {
                    pageError.set("提取页面文本超时");
                }
            } else {
                textDone.countDown();
            }

            StringBuilder sb = new StringBuilder();
            Map<String, Object> info = new HashMap<>();
            String screenshotPath = null;
            if (action.equals("screenshot") || action.equals("both")) {
                screenshotPath = captureScreenshot(webView, url);
            }

            String title = "";
            String text = "";
            long links = 0;
            if (pageJson.get() != null && !"null".equals(pageJson.get())) {
                try {
                    Object inner = new JSONTokener(pageJson.get()).nextValue();
                    JSONObject obj = new JSONObject(String.valueOf(inner));
                    title = obj.optString("title", "");
                    text = obj.optString("text", "");
                    links = obj.optLong("links", 0);
                } catch (Exception ignored) {
                }
            }

            info.put("url", url);
            info.put("title", title);
            info.put("text", text.length() > TEXT_MAX_LENGTH ? text.substring(0, TEXT_MAX_LENGTH) : text);
            info.put("truncated", text.length() > TEXT_MAX_LENGTH);
            info.put("links", links);
            if (screenshotPath != null) info.put("screenshot", screenshotPath);

            sb.append("页面: ").append(title.isEmpty() ? url : title);
            if (pageError.get() != null) sb.append("\n⚠ ").append(pageError.get());
            if (screenshotPath != null) {
                sb.append("\n截图已保存: ").append(screenshotPath);
            }
            if (needText) {
                String shown = text.trim();
                if (shown.isEmpty()) {
                    sb.append("\n（未提取到正文文本，页面可能依赖脚本加载或需登录）");
                } else {
                    if (shown.length() > TEXT_MAX_LENGTH) shown = shown.substring(0, TEXT_MAX_LENGTH) + "...(已截断)";
                    sb.append("\n链接数: ").append(links);
                    sb.append("\n--- 正文 ---\n").append(shown);
                }
            }
            return AIToolResult.success(sb.toString(), info);
        } catch (Exception e) {
            AILogger.e(TAG, "web render failed: " + e.getMessage(), e);
            return AIToolResult.fail("网页浏览失败: " + e.getMessage());
        } finally {
            if (holder[0] != null) {
                final WebView wv = holder[0];
                MAIN.post(() -> {
                    try {
                        wv.stopLoading();
                        wv.loadUrl("about:blank");
                        wv.destroy();
                    } catch (Throwable ignored) {
                    }
                });
            }
        }
    }

    /** 首屏截图：离屏 WebView draw 到 Bitmap，存工作区 files/screenshots/ */
    private String captureScreenshot(WebView webView, String url) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> pathRef = new AtomicReference<>();
        MAIN.post(() -> {
            try {
                DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
                int width = Math.max(360, dm.widthPixels);
                int height = Math.max(640, dm.heightPixels * 2 / 3);
                // 软件层渲染保证 draw 到 Canvas 有效
                webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                webView.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                webView.layout(0, 0, width, height);
                Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                webView.draw(new Canvas(bmp));

                AgentWorkspace workspace = AgentWorkspace.getInstance(appContext);
                File shotsDir = new File(workspace.getFilesDir(), "screenshots");
                if (!shotsDir.exists() && !shotsDir.mkdirs()) return;
                String name = "web_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
                File out = new File(shotsDir, name);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 90, fos);
                }
                bmp.recycle();
                pathRef.set(out.getAbsolutePath());
            } catch (Throwable t) {
                AILogger.w(TAG, "截图失败: " + t.getMessage());
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(10, TimeUnit.SECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return pathRef.get();
    }
}
