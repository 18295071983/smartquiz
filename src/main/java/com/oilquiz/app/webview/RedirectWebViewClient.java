package com.oilquiz.app.webview;

import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 支持文件重定向 + 壳级 WebView 能力的 WebViewClient
 * - 导航策略（壳源码）：http/https 应用内加载；tel/mailto/sms 交系统；
 *   intent:// 智能解析（回退网页/启动应用）；其他 App 协议有应用则系统打开
 * - 错误处理（壳源码）：主框架加载失败 → 友好的"重新加载"错误页（不显示系统/Chrome 错误页）
 * - 渲染进程崩溃保护（壳源码）：不杀应用，重置并提示
 * - 保留：Google 广告过滤、hf-mirror DNS 代理、文件重定向
 */
public class RedirectWebViewClient extends WebViewClient {

    private static final String TAG = "RedirectWebViewClient";

    // 文件重定向管理器
    private final FileRedirectManager redirectManager;

    // 是否启用文件重定向
    private boolean fileRedirectEnabled = true;

    // 页面加载回调
    private PageLoadCallback pageLoadCallback;

    public RedirectWebViewClient() {
        this.redirectManager = FileRedirectManager.getInstance();
    }

    /**
     * 设置页面加载回调
     */
    public void setPageLoadCallback(@Nullable PageLoadCallback callback) {
        this.pageLoadCallback = callback;
    }

    /**
     * 设置是否启用文件重定向
     */
    public void setFileRedirectEnabled(boolean enabled) {
        this.fileRedirectEnabled = enabled;
    }

    // ==================== 导航策略（壳源码） ====================

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        // 只处理主框架导航；子框架/内嵌资源交 WebView 自行处理
        if (request != null && !request.isForMainFrame()) {
            return super.shouldOverrideUrlLoading(view, request);
        }
        String url = request.getUrl().toString();
        return handleUrl(view, url);
    }

    // 兼容旧版 API（旧版只对主框架导航触发）
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return handleUrl(view, url);
    }

    /**
     * 导航策略（壳源码）：
     * http/https/file/about/data 等 → 应用内加载；
     * tel/mailto/sms → 交系统；
     * intent:// → 智能解析（回退网页应用内加载 / 启动对应应用）；
     * 其他 App 协议（weixin:// 等）→ 有应用则系统打开，无则应用内（错误重试页兜底）
     */
    private boolean handleUrl(WebView view, String url) {
        if (url == null) return false;
        if (url.startsWith("http://") || url.startsWith("https://")
                || url.startsWith("file://") || url.startsWith("about:")
                || url.startsWith("data:") || url.startsWith("blob:")
                || url.startsWith("javascript:")) {
            return false; // 应用内加载
        }
        try {
            String scheme = Uri.parse(url).getScheme();
            if (scheme != null) {
                String s = scheme.toLowerCase(java.util.Locale.US);
                // tel/mailto/sms 交系统（壳源码）
                if (s.equals("tel") || s.equals("mailto") || s.equals("sms") || s.equals("smsto")) {
                    try {
                        view.getContext().startActivity(
                                new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignored) { }
                    return true;
                }
                // intent:// 智能解析：优先回退网页（应用内加载），再尝试启动对应应用
                if (s.equals("intent")) {
                    try {
                        android.content.Intent intent = android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME);
                        if (intent != null) {
                            String fb = intent.getStringExtra("browser_fallback_url");
                            if (fb != null && !fb.isEmpty()) {
                                Log.d(TAG, "intent 回退网页: " + fb);
                                view.loadUrl(fb);
                                return true;
                            }
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            if (view.getContext().getPackageManager().resolveActivity(intent, 0) != null) {
                                view.getContext().startActivity(intent);
                                return true;
                            }
                        }
                    } catch (Exception ignored) { }
                    // 解析失败/无对应应用：提取 URL 中编码网页链接，应用内加载
                    String fb2 = extractFallbackUrl(url);
                    if (fb2 != null) {
                        Log.d(TAG, "intent 无应用，回退编码网页: " + fb2);
                        view.loadUrl(fb2);
                        return true;
                    }
                    // 都无 → 应用内加载（错误重试页兜底），不交 Chrome 报 scheme 错误
                    return false;
                }
            }
        } catch (Exception ignored) { }
        // 其他 App 跳转协议/自定义 scheme：有应用则系统打开
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            if (view.getContext().getPackageManager().resolveActivity(i, 0) != null) {
                view.getContext().startActivity(i);
                return true;
            }
        } catch (Exception ignored) { }
        // 无对应应用：弹确认框尝试用系统其他应用打开（跳系统浏览器/系统处理）；
        // 用户取消则留当前页，不让 WebView 显示 scheme 错误页
        try {
            android.content.Context ctx = view.getContext();
            final String rawUrl = url;
            if (ctx instanceof android.app.Activity) {
                android.app.Activity act = (android.app.Activity) ctx;
                String schemeHint = "";
                try {
                    String sc = Uri.parse(url).getScheme();
                    if (sc != null) schemeHint = sc + "://";
                } catch (Exception ignored) { }
                new android.app.AlertDialog.Builder(act)
                        .setTitle("无法在当前页面打开")
                        .setMessage("当前链接协议（" + schemeHint + "）无法在本浏览器内解析，是否尝试用系统其他应用打开？\n\n" + rawUrl)
                        .setPositiveButton("用其他应用打开", (d, w) -> {
                            try {
                                android.content.Intent i2 = new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(rawUrl));
                                act.startActivity(i2);
                            } catch (Exception e) {
                                android.widget.Toast.makeText(act, "系统没有能打开此链接的应用", android.widget.Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                android.widget.Toast.makeText(ctx, "当前链接无法解析：" + url, android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Throwable ignored) { }
        return true;
    }

    /** 从 App 跳转 URL 中提取网页回退地址（url/u/link/browser_fallback_url 等参数或编码链接） */
    private static String extractFallbackUrl(String url) {
        try {
            android.net.Uri u = Uri.parse(url);
            String query = u.getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    int idx = pair.indexOf('=');
                    if (idx <= 0) continue;
                    String key = pair.substring(0, idx);
                    String val = android.net.Uri.decode(pair.substring(idx + 1));
                    if (key.equals("url") || key.equals("u") || key.equals("link")
                            || key.equals("target") || key.equals("browser_fallback_url")
                            || key.equals("redirect") || key.equals("fallback")) {
                        if (val.startsWith("http://") || val.startsWith("https://")) return val;
                    }
                }
            }
            // URL 内嵌编码链接（scheme://...?url=https%3A%2F%2Fxxx）
            String s = url;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("https?%3A%2F%2F[^&%]+")
                    .matcher(s);
            if (m.find()) {
                String dec = android.net.Uri.decode(m.group());
                if (dec.startsWith("http://") || dec.startsWith("https://")) return dec;
            }
            // 双重编码兼容（%25253A%25252F...）：先解码一层再匹配
            try {
                String once = android.net.Uri.decode(s);
                java.util.regex.Matcher m2 = java.util.regex.Pattern
                        .compile("https?%3A%2F%2F[^&%]+")
                        .matcher(once);
                if (m2.find()) {
                    String dec2 = android.net.Uri.decode(m2.group());
                    if (dec2.startsWith("http://") || dec2.startsWith("https://")) return dec2;
                }
            } catch (Exception ignored) { }
        } catch (Exception ignored) { }
        return null;
    }

    // ==================== 资源拦截（保留：广告过滤 + hf 代理 + 文件重定向） ====================

    @Override
    @Nullable
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        // Google 广告网络过滤（googlesyndication/doubleclick/googleadservices/adservice）
        Uri uri = request.getUrl();
        if (isGoogleAdRequest(uri)) {
            Log.d(TAG, "屏蔽 Google 广告请求: " + uri);
            return new WebResourceResponse("text/plain", "UTF-8",
                    new java.io.ByteArrayInputStream(new byte[0]));
        }
        // hf-mirror.com / huggingface.co：运营商 DNS 污染（解析到 127.0.0.1），
        // 用带 SafeDns 的 OkHttp 代理请求，绕过 DNS 污染
        String host = uri.getHost();
        String urlStr = uri.toString();
        // .gguf 模型文件 / resolve 下载链接：不拦截，让 WebView 触发 onDownloadStart
        // （否则 shouldInterceptRequest 返回 application/octet-stream，WebView 尝试显示而不下载）
        if (host != null && (host.contains("hf-mirror.com") || host.contains("huggingface.co"))) {
            String path = uri.getPath() != null ? uri.getPath().toLowerCase() : "";
            if (path.endsWith(".gguf") || path.contains("/resolve/") || path.endsWith(".bin") || path.endsWith(".safetensors")) {
                Log.d(TAG, "跳过拦截模型文件下载: " + urlStr);
                return null; // 不拦截，让 WebView 触发 onDownloadStart
            }
            try {
                okhttp3.Request okRequest = new okhttp3.Request.Builder()
                        .url(uri.toString())
                        .header("User-Agent", view.getSettings().getUserAgentString())
                        .build();
                okhttp3.Response response = com.oilquiz.app.ai.model.ModelDownloadManager
                        .getHttpClient().newCall(okRequest).execute();
                okhttp3.ResponseBody body = response.body();
                String mimeType = response.header("Content-Type", "text/html");
                if (mimeType.contains(";")) {
                    mimeType = mimeType.substring(0, mimeType.indexOf(';')).trim();
                }
                String encoding = "UTF-8";
                int statusCode = response.code();
                String reasonPhrase = response.message() != null ? response.message() : "OK";
                Map<String, String> respHeaders = new HashMap<>();
                for (String name : response.headers().names()) {
                    respHeaders.put(name, response.header(name));
                }
                InputStream inputStream = body != null ? body.byteStream() : new java.io.ByteArrayInputStream(new byte[0]);
                return new WebResourceResponse(mimeType, encoding, statusCode, reasonPhrase, respHeaders, inputStream);
            } catch (Exception e) {
                Log.e(TAG, "hf-mirror 代理请求失败: " + uri + " - " + e.getMessage());
            }
        }

        if (!fileRedirectEnabled) {
            return super.shouldInterceptRequest(view, request);
        }

        String url = uri.toString();

        // 只处理文件协议请求
        if (!"file".equals(uri.getScheme())) {
            return super.shouldInterceptRequest(view, request);
        }

        // 获取文件路径
        String filePath = uri.getPath();
        if (filePath == null || filePath.isEmpty()) {
            return super.shouldInterceptRequest(view, request);
        }

        try {
            // 使用重定向管理器获取重定向后的路径
            String redirectedPath = redirectManager.getRedirectedPath(filePath);

            Log.d(TAG, "文件重定向: " + filePath + " -> " + redirectedPath);

            // 检查重定向后的文件是否存在
            File redirectedFile = new File(redirectedPath);
            if (!redirectedFile.exists()) {
                Log.e(TAG, "重定向后的文件不存在: " + redirectedPath);
                return createErrorResponse("文件不存在: " + redirectedPath);
            }

            // 返回重定向后的文件
            return createFileResponse(redirectedFile);

        } catch (FileRedirectException e) {
            // 未配置重定向规则，记录错误并返回错误响应
            Log.e(TAG, "文件重定向错误: " + e.getMessage());
            return createErrorResponse(e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "处理文件请求时发生错误: " + e.getMessage(), e);
            return createErrorResponse("处理文件请求时发生错误: " + e.getMessage());
        }
    }

    /**
     * 判断是否为 Google 广告网络请求（googleads/doubleclick/adsense 等广告投放域名）
     */
    private boolean isGoogleAdRequest(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.US);
        return h.endsWith(".googlesyndication.com")
                || h.endsWith(".doubleclick.net")
                || h.endsWith(".googleadservices.com")
                || h.equals("adservice.google.com")
                || h.equals("pagead2.googleadservices.com")
                || h.equals("pagead2.googlesyndication.com")
                || h.startsWith("googleads.g.doubleclick.net");
    }

    /**
     * 创建文件响应
     */
    @Nullable
    private WebResourceResponse createFileResponse(@NonNull File file) {
        try {
            InputStream inputStream = new FileInputStream(file);
            String mimeType = getMimeType(file.getName());

            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");

            return new WebResourceResponse(mimeType, "UTF-8", 200, "OK", headers, inputStream);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "文件未找到: " + file.getAbsolutePath());
            return null;
        }
    }

    /**
     * 创建错误响应
     */
    @NonNull
    private WebResourceResponse createErrorResponse(@NonNull String errorMessage) {
        String errorHtml = "<!DOCTYPE html>" +
                "<html>" +
                "<head><title>文件重定向错误</title></head>" +
                "<body style='font-family: sans-serif; padding: 20px;'>" +
                "<h2 style='color: #d32f2f;'>文件重定向错误</h2>" +
                "<p style='color: #666;'>" + errorMessage + "</p>" +
                "<hr>" +
                "<p style='font-size: 12px; color: #999;'>" +
                "请在 FileRedirectManager 中配置相应的文件重定向规则。" +
                "</p>" +
                "</body>" +
                "</html>";

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/html; charset=utf-8");

        return new WebResourceResponse(
                "text/html",
                "UTF-8",
                404,
                "Not Found",
                headers,
                new java.io.ByteArrayInputStream(errorHtml.getBytes())
        );
    }

    /**
     * 获取 MIME 类型
     */
    @NonNull
    private String getMimeType(@NonNull String fileName) {
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileName.substring(dotIndex + 1).toLowerCase();
        }

        switch (extension) {
            case "html":
            case "htm":
                return "text/html";
            case "css":
                return "text/css";
            case "js":
                return "application/javascript";
            case "json":
                return "application/json";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "svg":
                return "image/svg+xml";
            case "xml":
                return "application/xml";
            case "txt":
                return "text/plain";
            case "pdf":
                return "application/pdf";
            case "mp4":
                return "video/mp4";
            case "mp3":
                return "audio/mpeg";
            default:
                return "application/octet-stream";
        }
    }

    // ==================== 页面生命周期 ====================

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        Log.d(TAG, "页面开始加载: " + url);
        // JS 壳：Chrome 兼容（window.chrome + 桥 polyfill），让网页识别为 Chrome 环境
        injectChromeCompat(view);
        if (pageLoadCallback != null) {
            pageLoadCallback.onPageStarted(url);
        }
    }

    /**
     * JS 壳：注入 Chrome 特征与 API polyfill，解决"网页无法识别 WebView"问题。
     * - window.chrome 对象（网站特征检测，WebView 默认缺失）
     * - navigator.share → Android 桥分享（如有）
     * - navigator.clipboard.writeText → AndroidClipboard 桥（如有）
     */
    private void injectChromeCompat(WebView view) {
        if (view == null) return;
        String js = "(function(){try{"
                + "if(!window.chrome){window.chrome={csi:function(){return{}},loadTimes:function(){return{}},runtime:{},app:{isInstalled:false},webstore:{}};}"
                + "if(!navigator.share&&window.Android&&window.Android.shareText){navigator.share=function(d){try{if(d&&(d.text||d.title))window.Android.shareText(d.title||'',d.text||d.title||'');}catch(e){}return Promise.resolve();};}"
                + "if(navigator.clipboard&&!navigator.clipboard.writeText&&window.AndroidClipboard&&window.AndroidClipboard.setClipboardText){navigator.clipboard.writeText=function(t){try{window.AndroidClipboard.setClipboardText(t||'');}catch(e){}return Promise.resolve();};}"
                + "}catch(e){}})();";
        try {
            view.evaluateJavascript(js, null);
        } catch (Exception ignored) { }
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        Log.d(TAG, "页面加载完成: " + url);
        if (pageLoadCallback != null) {
            pageLoadCallback.onPageFinished(url);
        }
    }

    // ==================== 错误处理（壳源码：友好错误重试页，不显示系统/Chrome 错误页） ====================

    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        // ERR_ABORTED=-3：重定向/新导航中断旧加载的正常信号，不显示错误页
        if (errorCode == -3) {
            Log.d(TAG, "加载被中断(ABORTED)，忽略: " + failingUrl);
            return;
        }
        super.onReceivedError(view, errorCode, description, failingUrl);
        Log.e(TAG, "页面加载错误 [" + errorCode + "]: " + description + " - " + failingUrl);
        // 主框架加载失败 → 友好的"重新加载"错误页（壳源码）
        if (failingUrl != null) {
            showErrorPage(view, failingUrl);
        }
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        // 新版 API（API 23+）：子资源/子框架错误不阻塞主页面
        if (request != null && !request.isForMainFrame()) {
            super.onReceivedError(view, request, error);
            return;
        }
        int code = error != null ? error.getErrorCode() : 0;
        // ERR_ABORTED=-3：重定向/导航中断的正常信号，不显示错误页
        if (code == -3) {
            Log.d(TAG, "加载被中断(ABORTED)，忽略(新API): " + (request != null ? request.getUrl() : ""));
            return;
        }
        super.onReceivedError(view, request, error);
        Log.e(TAG, "页面加载错误 [新API " + code + "]: "
                + (error != null ? error.getDescription() : "") + " - " + (request != null ? request.getUrl() : ""));
        // 主框架加载失败 → 友好的"重新加载"错误页（壳源码）
        if (request != null) {
            showErrorPage(view, request.getUrl().toString());
        }
    }

    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
        if (request != null && request.isForMainFrame()) {
            Log.e(TAG, "HTTP 错误: " + (errorResponse != null ? errorResponse.getStatusCode() : 0)
                    + " - " + request.getUrl());
            showErrorPage(view, request.getUrl().toString());
        }
        super.onReceivedHttpError(view, request, errorResponse);
    }

    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        // 渲染进程崩溃保护（壳源码）：不杀掉整个应用，回到错误重试页（API 26+）
        if (detail != null && detail.didCrash()) {
            view.post(() -> {
                android.widget.Toast.makeText(view.getContext(), "页面渲染进程异常，已重置", android.widget.Toast.LENGTH_SHORT).show();
                showErrorPage(view, view.getUrl());
            });
            return true;
        }
        return super.onRenderProcessGone(view, detail);
    }

    /** 主框架加载失败 → 友好的错误重试页（壳源码） */
    private void showErrorPage(WebView view, String url) {
        if (view == null) return;
        String retry = (url != null && !url.isEmpty()) ? url : "about:blank";
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

    /**
     * 页面加载回调接口
     */
    public interface PageLoadCallback {
        void onPageStarted(@NonNull String url);
        void onPageFinished(@NonNull String url);
        void onError(int errorCode, @NonNull String description, @NonNull String failingUrl);
    }
}
