package com.oilquiz.app.webview;

import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
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
 * 支持文件重定向的 WebViewClient
 * 拦截文件请求并通过 FileRedirectManager 进行重定向
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

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        // 只处理主框架导航；子框架/内嵌资源（iframe、JS 内嵌请求）的非标准 scheme
        // 交由 WebView 自行处理，避免五花八门的 scheme 错误阻塞主页面
        if (request != null && !request.isForMainFrame()) {
            return super.shouldOverrideUrlLoading(view, request);
        }
        String url = request.getUrl().toString();
        return handleUrlLoading(view, url);
    }

    // 兼容旧版 API（旧版只对主框架导航触发）
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return handleUrlLoading(view, url);
    }

    /**
     * 处理 URL 加载，拦截非标准协议并交给系统处理
     */
    private boolean handleUrlLoading(WebView view, String url) {
        // 标准协议放行，让 WebView 正常加载
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
            return false;
        }
        // WebView 原生支持的 scheme 直接放行（about:/data:/blob:/javascript:）
        if (url.startsWith("about:") || url.startsWith("data:") || url.startsWith("blob:")
                || url.startsWith("javascript:")) {
            return false;
        }
        Log.w(TAG, "拦截非标准协议 URL: " + url);
        // 非标准 scheme（tel:/mailto:/sms:/intent:/自定义协议）用系统 Intent 打开，避免 ERR_UNSUPPORTED_SCHEME
        return openExternalScheme(view, url);
    }

    /**
     * 用系统 Intent 打开非标准 scheme（tel/mailto/sms/intent/自定义协议）
     */
    private boolean openExternalScheme(WebView view, String url) {
        android.content.Context ctx = view != null ? view.getContext() : null;
        if (ctx == null) return true; // 无上下文则拦截
        try {
            android.content.Intent intent = null;
            String scheme = Uri.parse(url).getScheme();
            if (scheme != null) {
                switch (scheme.toLowerCase(java.util.Locale.US)) {
                    case "tel":
                        intent = new android.content.Intent(android.content.Intent.ACTION_DIAL, Uri.parse(url));
                        break;
                    case "mailto":
                        intent = new android.content.Intent(android.content.Intent.ACTION_SENDTO, Uri.parse(url));
                        break;
                    case "sms":
                    case "smsto":
                        intent = new android.content.Intent(android.content.Intent.ACTION_SENDTO, Uri.parse(url));
                        break;
                    case "ftp":
                    case "rtsp":
                        // 交给系统浏览器/播放器处理
                        intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url));
                        break;
                    case "intent":
                        try {
                            intent = android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME);
                            if (intent != null) {
                                String fallback = intent.getStringExtra("browser_fallback_url");
                                if (fallback != null && !fallback.isEmpty() && view != null) {
                                    view.loadUrl(fallback); // intent 无对应应用时跳 fallback 网页
                                    return true;
                                }
                            }
                        } catch (Exception ignored) { }
                        break;
                    default:
                        // 自定义 scheme：交给系统尝试（有应用能处理则打开，否则提示）
                        intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url));
                        break;
                }
            }
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                if (ctx.getPackageManager().resolveActivity(intent, 0) != null) {
                    ctx.startActivity(intent);
                    return true;
                }
            }
            // 系统无应用可处理：提示用户
            android.widget.Toast.makeText(ctx, "无法打开链接: " + url, android.widget.Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "处理非标准协议失败: " + url, e);
            return true;
        }
    }

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

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        Log.d(TAG, "页面开始加载: " + url);
        if (pageLoadCallback != null) {
            pageLoadCallback.onPageStarted(url);
        }
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        Log.d(TAG, "页面加载完成: " + url);
        if (pageLoadCallback != null) {
            pageLoadCallback.onPageFinished(url);
        }
    }

    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        // 忽略不支持的 URL scheme 错误（已在 shouldOverrideUrlLoading 拦截处理，避免错误页干扰）
        // ERROR_UNSUPPORTED_SCHEME=-10；ERR_UNKNOWN_URL_SCHEME=-14（Chromium 错误码，部分内核仍上报）
        if (errorCode == ERROR_UNSUPPORTED_SCHEME || errorCode == -14) {
            Log.d(TAG, "忽略不支持的 scheme 错误: " + failingUrl);
            return;
        }
        super.onReceivedError(view, errorCode, description, failingUrl);
        Log.e(TAG, "页面加载错误 [" + errorCode + "]: " + description + " - " + failingUrl);
        if (pageLoadCallback != null) {
            pageLoadCallback.onError(errorCode, description, failingUrl);
        }
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        // 新版 API（API 23+）：子资源/子框架错误不阻塞主页面；主框架 scheme 错误忽略
        if (request != null && !request.isForMainFrame()) {
            super.onReceivedError(view, request, error);
            return;
        }
        if (error != null && (error.getErrorCode() == ERROR_UNSUPPORTED_SCHEME || error.getErrorCode() == -14)) {
            Log.d(TAG, "忽略不支持的 scheme 错误(新API): " + (request != null ? request.getUrl() : ""));
            return;
        }
        super.onReceivedError(view, request, error);
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
