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

    /** 常见 App 跳转协议 → 应用名（未安装时提示用） */
    private static String appNameOfScheme(String scheme) {
        if (scheme == null) return null;
        switch (scheme.toLowerCase(java.util.Locale.US)) {
            case "weixin": case "wechat": return "微信";
            case "taobao": case "tbopen": return "淘宝";
            case "tmall": return "天猫";
            case "jd": case "openapp.jdmobile": case "openapp.jd": return "京东";
            case "zhihu": return "知乎";
            case "bilibili": return "哔哩哔哩";
            case "douyin": case "snssdk1128": return "抖音";
            case "weibo": case "sinaweibo": return "微博";
            case "alipays": case "alipay": return "支付宝";
            case "mqq": case "qq": return "QQ";
            case "xhsdiscover": case "xhsmessage": return "小红书";
            case "baiduboxapp": return "百度";
            case "pinduoduo": return "拼多多";
            case "meituan": case "imeituan": return "美团";
            case "dianping": return "大众点评";
            case "vipshop": return "唯品会";
            case "tenvideo": case "qqlive": return "腾讯视频";
            case "iqiyi": return "爱奇艺";
            case "youku": return "优酷";
            case "didi": return "滴滴出行";
            case "ctrip": return "携程";
            case "eleme": return "饿了么";
            default: return null;
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
        // ERROR_UNSUPPORTED_SCHEME=-10；ERR_UNKNOWN_URL_SCHEME=-14（Chromium 错误码）
        // 不在应用内显示错误页：交给系统浏览器/系统处理该链接
        if (errorCode == ERROR_UNSUPPORTED_SCHEME || errorCode == -14) {
            Log.d(TAG, "scheme 错误交系统处理: " + failingUrl);
            launchInSystemBrowser(view, failingUrl);
            return;
        }
        // ERR_ABORTED=-3：重定向/新导航中断旧加载的正常信号，不显示错误页
        if (errorCode == -3) {
            Log.d(TAG, "加载被中断(ABORTED)，忽略: " + failingUrl);
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
        // 新版 API（API 23+）：子资源/子框架错误不阻塞主页面
        if (request != null && !request.isForMainFrame()) {
            super.onReceivedError(view, request, error);
            return;
        }
        int code = error != null ? error.getErrorCode() : 0;
        if (code == ERROR_UNSUPPORTED_SCHEME || code == -14) {
            Log.d(TAG, "scheme 错误交系统处理(新API): " + (request != null ? request.getUrl() : ""));
            launchInSystemBrowser(view, request != null ? request.getUrl().toString() : null);
            return;
        }
        // ERR_ABORTED=-3：重定向/导航中断的正常信号，不显示错误页
        if (code == -3) {
            Log.d(TAG, "加载被中断(ABORTED)，忽略(新API): " + (request != null ? request.getUrl() : ""));
            return;
        }
        super.onReceivedError(view, request, error);
    }

    /** 上次交给系统处理 scheme 的时间（2 秒节流，防止重复触发） */
    private static volatile long lastSchemeBrowserTs = 0L;

    /**
     * 把 scheme 链接交给系统处理（不显示应用内错误页）：
     * 有能处理的应用（含浏览器/商店）→ 系统打开；
     * 无应用 → 提取网页回退地址在应用内加载；再无 → 按协议友好提示。
     */
    private static void launchInSystemBrowser(WebView view, String url) {
        if (view == null || url == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSchemeBrowserTs < 2000) return; // 节流，防重复弹
        lastSchemeBrowserTs = now;
        android.content.Context ctx = view.getContext();
        try {
            String scheme = Uri.parse(url).getScheme();
            // intent:// 协议特殊处理：解析 Intent，优先回退网页（应用内加载），避免交 Chrome 报 scheme 错误
            if (scheme != null && scheme.equalsIgnoreCase("intent")) {
                try {
                    android.content.Intent intent = android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME);
                    if (intent != null) {
                        String fb = intent.getStringExtra("browser_fallback_url");
                        if (fb != null && !fb.isEmpty()) {
                            Log.d(TAG, "intent 回退网页: " + fb);
                            view.loadUrl(fb);
                            return;
                        }
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        if (ctx.getPackageManager().resolveActivity(intent, 0) != null) {
                            ctx.startActivity(intent);
                            return;
                        }
                    }
                } catch (Exception ignored) { }
                // intent 解析失败/无对应应用：提取 URL 中编码网页链接，应用内加载
                String fb2 = extractFallbackUrl(url);
                if (fb2 != null) {
                    Log.d(TAG, "intent 无应用，回退编码网页: " + fb2);
                    view.loadUrl(fb2);
                    return;
                }
                String appName2 = appNameOfScheme(scheme);
                android.widget.Toast.makeText(ctx,
                        appName2 != null ? "未安装" + appName2 + "，请在应用商店下载" : "该链接需要安装对应应用才能打开",
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            if (ctx.getPackageManager().resolveActivity(i, 0) != null) {
                ctx.startActivity(i);
                return;
            }
            // 无任何应用可处理：优先提取 URL 中网页回退地址（网页版/下载页），在应用内加载
            String fallback = extractFallbackUrl(url);
            if (fallback != null) {
                Log.d(TAG, "无应用处理，回退网页版: " + fallback);
                view.loadUrl(fallback);
                return;
            }
            // 无回退地址：按协议提示对应应用未安装（不交系统浏览器，避免 Chrome 也打不开显示错误页）
            String appName = appNameOfScheme(android.net.Uri.parse(url).getScheme());
            String tip = appName != null
                    ? "未安装" + appName + "，请在应用商店下载"
                    : "该链接需要安装对应应用才能打开";
            android.widget.Toast.makeText(ctx, tip, android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "系统打开 scheme 失败: " + url, e);
        }
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

    /**
     * 页面加载回调接口
     */
    public interface PageLoadCallback {
        void onPageStarted(@NonNull String url);
        void onPageFinished(@NonNull String url);
        void onError(int errorCode, @NonNull String description, @NonNull String failingUrl);
    }
}
