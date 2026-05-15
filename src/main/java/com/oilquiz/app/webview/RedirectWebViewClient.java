package com.oilquiz.app.webview;

import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
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
        String url = request.getUrl().toString();
        return handleUrlLoading(url);
    }

    // 兼容旧版 API
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return handleUrlLoading(url);
    }

    /**
     * 处理 URL 加载，拦截非标准协议
     */
    private boolean handleUrlLoading(String url) {
        // 拦截非 http/https 协议的 URL（如 baiduboxapp://, weixin:// 等）
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) {
            Log.w(TAG, "拦截非标准协议 URL: " + url);
            // 返回 true 表示已处理，阻止 WebView 加载此 URL
            return true;
        }

        // 不拦截标准 URL 加载，让 WebView 正常处理
        return false;
    }

    @Override
    @Nullable
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (!fileRedirectEnabled) {
            return super.shouldInterceptRequest(view, request);
        }

        Uri uri = request.getUrl();
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
        super.onReceivedError(view, errorCode, description, failingUrl);
        Log.e(TAG, "页面加载错误 [" + errorCode + "]: " + description + " - " + failingUrl);
        if (pageLoadCallback != null) {
            pageLoadCallback.onError(errorCode, description, failingUrl);
        }
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
