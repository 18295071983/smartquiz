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

        // 设置 WebViewClient，防止跳转到系统浏览器
        webView.setWebViewClient(new WebViewClient() {
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

        // 设置 WebChromeClient 显示进度
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
     * 加载 HTML 文件
     */
    private void loadHtml() {
        Log.i(TAG, "Loading HTML from: " + htmlPath);
        webView.loadUrl("file://" + htmlPath);
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
