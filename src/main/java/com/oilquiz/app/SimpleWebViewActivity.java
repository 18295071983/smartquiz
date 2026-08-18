package com.oilquiz.app;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import com.oilquiz.app.ui.base.BaseActivity;

/**
 * 简易 WebView Activity
 * 专门用于加载导出的 HTML 文件
 * 最小化实现，类似"背题"类应用
 */
public class SimpleWebViewActivity extends BaseActivity {

    private static final String TAG = "SimpleWebViewActivity";
    private WebView webView;
    private ProgressBar progressBar;
    private String htmlPath;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_simple_webview;
    }

    @Override
    protected void initView() {
        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        // 标题栏：优先取 intent title，默认"HTML 预览"
        String title = getIntent().getStringExtra("title");
        setupToolbar(title != null && !title.isEmpty() ? title : "HTML 预览");

        // 配置 WebView
        setupWebView();

        // 获取 Intent 参数并加载 HTML
        htmlPath = getIntent().getStringExtra("html_path");
        if (htmlPath == null || htmlPath.isEmpty()) {
            Log.e(TAG, "No HTML path provided");
            finish();
            return;
        }

        loadHtml();
    }

    @Override
    protected void initData() {
        // 无需额外数据初始化
    }

    @Override
    protected void initListener() {
        // 无需额外监听器
    }

    /**
     * 配置 WebView 设置
     */
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        // 软件渲染：规避设备 GPU tile 内存超限导致长页面下半部分不绘制（空白/点击失效）
        // 对静态 HTML 渲染性能足够；硬件加速留给系统 WebView/其他页面
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        // 设置 WebViewClient，防止跳转到系统浏览器
        webView.setWebViewClient(new WebViewClient() {
            // 链接点击：http/https 用系统浏览器打开（页内相对链接由 WebView 内部导航）
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return handleUrl(request != null && request.getUrl() != null
                        ? request.getUrl().toString() : null);
            }

            private boolean handleUrl(String url) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    try {
                        startActivity(new android.content.Intent(
                                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                    } catch (Exception e) {
                        Log.e(TAG, "打开链接失败: " + url + " - " + e.getMessage());
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (progressBar != null) {
                    progressBar.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });

        // 设置 WebChromeClient 显示进度 + 捕获 JS console（诊断按钮/交互失效）
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar != null) {
                    progressBar.setProgress(newProgress);
                }
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                String msg = consoleMessage.message();
                if (consoleMessage.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR
                        || consoleMessage.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING) {
                    Log.w(TAG + "-JS", "[" + consoleMessage.messageLevel() + "] " + msg
                            + " (" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber() + ")");
                }
                return true;
            }
        });
    }

    /**
     * 加载 HTML 文件：读取内容后用 loadDataWithBaseURL 渲染
     * （baseUrl 指向文件目录，相对路径资源可正常加载；与聊天内预览渲染方式一致）。
     */
    private void loadHtml() {
        Log.i(TAG, "Loading HTML from: " + htmlPath);
        try {
            java.io.File f = new java.io.File(htmlPath);
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            String content = sb.toString();
            if (content.trim().isEmpty()) {
                webView.loadDataWithBaseURL(null, "<html><body><h1>空文件</h1></body></html>",
                        "text/html", "UTF-8", null);
                return;
            }
            // baseUrl 指向文件所在目录（末尾补 /），相对路径图片/资源可加载
            String dir = f.getParent();
            String baseUrl = "file://" + dir + (dir.endsWith("/") ? "" : "/");
            webView.loadDataWithBaseURL(baseUrl, content, "text/html", "UTF-8", null);
        } catch (Exception e) {
            Log.e(TAG, "读取 HTML 文件失败: " + e.getMessage(), e);
            webView.loadDataWithBaseURL(null,
                    "<html><body><h1>加载失败</h1><p>" + e.getMessage() + "</p></body></html>",
                    "text/html", "UTF-8", null);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
