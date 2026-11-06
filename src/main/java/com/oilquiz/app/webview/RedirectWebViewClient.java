package com.oilquiz.app.webview;

import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.util.Log;
import android.webkit.HttpAuthHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
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

    /**
     * 最后一个成功加载完成的地址：协议导航（{@code ERR_UNKNOWN_URL_SCHEME}）失败时，
     * 内核会把当前页面替换成自己的错误页，用这个值才能把用户送回原来那页。
     */
    private volatile String lastGoodUrl;

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
        String url = request != null && request.getUrl() != null ? request.getUrl().toString() : null;
        if (request != null && !request.isForMainFrame()) {
            // 子框架里的 http/图片/接口照常交 WebView；但广告 iframe 里的「打开 App」协议
            // 同样要接管——否则内核直接在 iframe 里报 scheme 错误，用户点什么都没反应。
            return com.oilquiz.app.browser.BrowserSchemeHandler.handleNavigation(view, url, true);
        }
        return handleUrl(view, url);
    }

    /**
     * 导航策略：http/https/file/about/data/blob 应用内加载；
     * tel/mailto/sms/geo/ftp/market 及 App 私有协议、{@code intent://} 交
     * {@link com.oilquiz.app.browser.BrowserSchemeHandler} 处置
     * （有应用则打开、有回退网页则应用内加载、都没有则给用户选路子）。
     */
    private boolean handleUrl(WebView view, String url) {
        return com.oilquiz.app.browser.BrowserSchemeHandler.handleNavigation(view, url, true);
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
            // 没有配置重定向规则 = 正常加载原文件（旧实现返回 404 错误页，
            // 会把所有 file:// 页面/本地相对资源都打断）
            Log.d(TAG, "无文件重定向规则，按原路径加载: " + filePath);
            return super.shouldInterceptRequest(view, request);
        } catch (IllegalStateException e) {
            // 管理器未初始化（WebViewRedirectHelper 未接入）时同样按原路径加载
            Log.d(TAG, "文件重定向管理器未初始化，按原路径加载: " + filePath);
            return super.shouldInterceptRequest(view, request);
        } catch (Exception e) {
            Log.e(TAG, "处理文件请求时发生错误: " + e.getMessage(), e);
            return super.shouldInterceptRequest(view, request);
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
        // 记住最后一个真正常出现的页面：协议导航失败时要靠它把内核错误页顶回去
        if (url != null && !url.startsWith("about:") && !url.startsWith("data:")) {
            lastGoodUrl = url;
        }
        // onPageStarted 时 document 可能还不存在，这里再注入一次保证 window.chrome / 分享桥可用
        injectChromeCompat(view);
        if (pageLoadCallback != null) {
            pageLoadCallback.onPageFinished(url);
        }
    }

    // ==================== 错误处理（壳源码：友好错误重试页，不显示系统/Chrome 错误页） ====================

    /**
     * 站点要 HTTP Basic/Digest 认证（401/407）。
     *
     * <p>不覆盖这个回调时内核的默认行为是<b>取消</b>：路由器后台、NAS、内网系统这类站点
     * 在内置浏览器里就是一片空白，而系统浏览器会给输入框。这里交给鉴权控制器弹框/复用已存凭据。</p>
     */
    @Override
    public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                                         String host, String realm) {
        com.oilquiz.app.browser.BrowserAuthController.handleHttpAuth(view, handler, host, realm);
    }

    /** 证书校验失败：列原因并让用户显式选"仅本次继续"，不静默拒绝也不永久放行 */
    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        com.oilquiz.app.browser.BrowserAuthController.handleSslError(view, handler, error);
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        int code = error != null ? error.getErrorCode() : 0;
        String failingUrl = request != null && request.getUrl() != null ? request.getUrl().toString() : null;
        // 子资源/子框架的协议失败（广告 iframe 里的「打开 App」）也要接管，
        // 但绝不能因此把主页面顶成错误页：只在成功转交后返回。
        if (request != null && !request.isForMainFrame()) {
            if (com.oilquiz.app.browser.BrowserSchemeHandler
                    .handleNavigation(view, failingUrl, true)) {
                Log.i(TAG, "子框架协议已转交处置: " + failingUrl);
                return;
            }
            super.onReceivedError(view, request, error);
            return;
        }
        // ERR_ABORTED=-3：重定向/导航中断的正常信号，不显示错误页
        if (code == -3) {
            Log.d(TAG, "加载被中断(ABORTED)，忽略(新API): " + failingUrl);
            return;
        }
        if (com.oilquiz.app.browser.BrowserSchemeHandler
                .handleUnknownSchemeError(view, failingUrl, lastGoodUrl)) {
            Log.i(TAG, "协议导航失败，已转交处置(新API): " + failingUrl + " code=" + code);
            return;
        }
        super.onReceivedError(view, request, error);
        Log.e(TAG, "页面加载错误 [新API " + code + "]: "
                + (error != null ? error.getDescription() : "") + " - " + failingUrl);
        // 主框架加载失败 → 友好的"重新加载"错误页（壳源码）
        if (request != null) {
            showErrorPage(view, failingUrl, code);
        }
    }

    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
        if (request != null && request.isForMainFrame()) {
            // HTTP 状态错误（404/500 等）仍可能有正常的站点错误页，交给页面自己展示；
            // 只有网络层失败才用内置重试页，否则会把站点自带的 404 页面顶掉。
            Log.e(TAG, "HTTP 错误: " + (errorResponse != null ? errorResponse.getStatusCode() : 0)
                    + " - " + request.getUrl());
        }
        super.onReceivedHttpError(view, request, errorResponse);
    }

    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        // 渲染进程崩溃/被系统回收：返回 true 表示应用自己收尾。旧实现继续使用已死亡的
        // WebView 去加载错误页，等于在死对象上操作；改为回调宿主，由宿主换掉这个标签页。
        String url = null;
        try {
            url = view != null ? view.getUrl() : null;
        } catch (Throwable ignored) {
            // 渲染进程已结束时 getUrl 可能不可用
        }
        boolean crashed = detail != null && detail.didCrash();
        Log.e(TAG, "渲染进程结束 crashed=" + crashed + " url=" + url);
        if (pageLoadCallback != null) {
            pageLoadCallback.onRendererGone(view, url);
        }
        return true;
    }

    /**
     * 内核错误码翻成人话。
     *
     * <p>只认这几个反复出现、含义确定的连接类错误；其余一律带<b>原始码</b>回显，
     * 不猜语义——猜错的原因会把人引去查 WiFi，而真问题在证书或站点那边。</p>
     */
    private static String netErrorReason(int errorCode) {
        switch (errorCode) {
            case -2:
                return "加载失败（内核未给出具体原因）";
            case -101:
                return "连接被对方重置（站点中断了这次连接）";
            case -102:
                return "站点拒绝连接（端口没在监听或被防火墙挡住）";
            case -105:
                return "域名解析失败（DNS 没把这个域名解析出地址）";
            case -106:
                return "设备当前未联网";
            case -118:
                return "连接超时（站点响应太慢或已下线）";
            default:
                return "加载失败（错误码 " + errorCode + "）";
        }
    }

    /** 主框架加载失败 → 友好的错误重试页（壳源码）。
     *
     * <p>原因行按内核错误码翻成人话：只写"请检查网络连接"会让用户去查 WiFi，
     * 而真正常见的是 DNS 没解析出来、站点拒绝连接、证书没配对。</p>
     */
    private void showErrorPage(WebView view, String url, int errorCode) {
        if (view == null) return;
        String retry = (url != null && !url.isEmpty()) ? url : "about:blank";
        String esc = retry.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
        String reason = netErrorReason(errorCode);
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{font-family:sans-serif;background:#f7f8fa;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}"
                + ".box{text-align:center;color:#666;padding:24px}"
                + "h2{color:#333;font-size:18px;margin:0 0 8px}"
                + "p{font-size:14px;margin:4px 0;color:#999}"
                + "a{display:inline-block;margin-top:16px;padding:10px 28px;background:#4338ca;color:#fff;border-radius:8px;text-decoration:none;font-size:14px}</style>"
                + "</head><body><div class='box'><h2>页面加载失败</h2><p>" + reason + "</p>"
                + "<p style='font-size:12px;word-break:break-all'>" + esc + "</p>"
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

        /** 渲染进程崩溃/被回收；宿主应销毁该 WebView 并重建标签页 */
        default void onRendererGone(WebView view, String url) {
        }
    }
}
