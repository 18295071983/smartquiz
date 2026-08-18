package com.oilquiz.app;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import com.oilquiz.app.ui.base.BaseActivity;

/**
 * 简易 WebView Activity（系统内核）
 * 专门用于加载导出的 HTML 文件 / html 组件全屏预览。
 * 含标题栏、JS 启用、软件渲染（防 GPU 截断）、链接处理、JS 交互诊断。
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
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        // WebViewClient：链接处理 + 页面进度 + JS 交互诊断
        webView.setWebViewClient(new WebViewClient() {
            // 链接点击：http/https 用系统浏览器打开（页内相对链接由 WebView 内部导航）
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request != null && request.getUrl() != null
                        ? request.getUrl().toString() : null);
            }

            private boolean handleUrl(String url) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    // App 内打开（WebViewActivity 完整界面，标题栏/前进后退），不再跳系统浏览器
                    try {
                        android.content.Intent intent = new android.content.Intent(
                                SimpleWebViewActivity.this, com.oilquiz.app.WebViewActivity.class);
                        intent.putExtra("url", url);
                        startActivity(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "App 内打开链接失败: " + url + " - " + e.getMessage());
                    }
                    return true;
                }
                if (url != null && url.startsWith("file://")) {
                    // 本地文件链接：html 由 WebView 内部渲染；md/txt/json/pdf/office 等交给文件预览
                    try {
                        String path = android.net.Uri.parse(url).getPath();
                        if (path != null) {
                            String lower = path.toLowerCase();
                            if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                                return false; // WebView 内部渲染
                            }
                            java.io.File f = new java.io.File(path);
                            if (f.exists() && f.isFile()) {
                                Log.i(TAG, "文件链接 → 文件预览: " + path);
                                com.oilquiz.app.ui.activity.WebViewFilePreviewActivity.start(
                                        SimpleWebViewActivity.this, path);
                                return true;
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "文件链接处理失败: " + url + " - " + e.getMessage());
                    }
                }
                // 相对链接：交给 WebView 内部导航（baseUrl 指向文件目录，可打开同目录资源）
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
                // 诊断按钮/JS 交互：注入脚本检查按钮数量、onclick 绑定、脚本数量
                view.postDelayed(() -> {
                    try {
                        String js = "(function(){"
                                + "var btns=document.querySelectorAll('button,[onclick],a,[class*=btn],[class*=Btn]');"
                                + "var b=document.querySelector('button,[onclick]');"
                                + "return JSON.stringify({btns:btns.length,"
                                + "firstOnclick:b?(b.onclick?'bound':'null'):'none',"
                                + "scripts:document.scripts.length,"
                                + "ready:document.readyState});})()";
                        view.evaluateJavascript(js, value ->
                                Log.i(TAG + "-JS", "页面诊断: " + value));
                    } catch (Throwable t) {
                        Log.w(TAG + "-JS", "诊断注入失败: " + t.getMessage());
                    }
                }, 600);
            }
        });

        // WebChromeClient 显示进度
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar != null) {
                    progressBar.setProgress(newProgress);
                }
            }
        });
    }

    /**
     * 加载 HTML 文件：读取内容后用 loadDataWithBaseURL 渲染
     * （baseUrl 指向文件目录，相对路径资源可正常加载）。
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
            // baseUrl 指向 Agent 工作区（相对链接自动解析到工作区文件：
            // <a href="report.md"> → 工作区/report.md，点击可预览）
            String baseUrl = "file://" + com.oilquiz.app.ai.agent.online.AgentWorkspace
                    .getInstance(this).getWorkspacePath() + "/";
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
