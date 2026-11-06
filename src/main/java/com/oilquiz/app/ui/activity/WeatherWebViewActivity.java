package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;

public class WeatherWebViewActivity extends AppCompatActivity {

    private WebView webView;
    private ProgressBar progressBar;
    private ImageView backBtn;
    private ImageView refreshBtn;
    private TextView titleTextView;

    private String weatherUrl;
    private String weatherTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (webView != null && webView.canGoBack()) {
                            webView.goBack();
                        } else {
                            setEnabled(false);
                            getOnBackPressedDispatcher().onBackPressed();
                        }
                    }
                });
        setContentView(R.layout.activity_weather_webview);

        Intent intent = getIntent();
        weatherUrl = intent.getStringExtra("url");
        weatherTitle = intent.getStringExtra("title");
        if (weatherTitle == null || weatherTitle.isEmpty()) {
            weatherTitle = "天气详情";
        }

        initViews();
        loadWeatherUrl();
    }

    private void initViews() {
        webView = findViewById(R.id.web_view);
        progressBar = findViewById(R.id.progress_bar);
        backBtn = findViewById(R.id.btn_back);
        refreshBtn = findViewById(R.id.btn_refresh);
        titleTextView = findViewById(R.id.tv_title);

        titleTextView.setText(weatherTitle);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // 和风网页要登录/跨域资源：第三方 Cookie 不开会表现为"登录了，跳回来又掉线"
        com.oilquiz.app.webview.WebViewDefaults.enableCookiePersistence(webView);

        webView.setWebViewClient(new WebViewClient() {
            /** 站点要 401 认证：内核默认"没人接就取消"，这里交给浏览器同一套鉴权处置弹输入框 */
            @Override
            public void onReceivedHttpAuthRequest(WebView view,
                                                  android.webkit.HttpAuthHandler handler,
                                                  String host, String realm) {
                com.oilquiz.app.browser.BrowserAuthController.handleHttpAuth(view, handler, host, realm);
            }

            /** 证书校验失败：列事实并让用户显式选"仅本次继续"，不静默拒绝 */
            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                                           android.net.http.SslError error) {
                com.oilquiz.app.browser.BrowserAuthController.handleSslError(view, handler, error);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                                                    android.webkit.WebResourceRequest request) {
                String url = request != null && request.getUrl() != null
                        ? request.getUrl().toString() : null;
                if (url != null) {
                    // 页面里的自定义协议（地图/支付类）不要塞回 WebView：交统一转交层，
                    // 有应用开应用、没装应用交系统浏览器；普通网页仍在本页加载。
                    if (com.oilquiz.app.browser.BrowserSchemeHandler.isSchemeNavigation(url)) {
                        com.oilquiz.app.browser.BrowserSchemeHandler.handoff(
                                WeatherWebViewActivity.this, url, true);
                        return true;
                    }
                    view.loadUrl(url);
                }
                return true;
            }
        });

        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar != null) {
                    progressBar.setProgress(newProgress);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (titleTextView != null && (weatherTitle == null || weatherTitle.equals(getString(R.string.h_419b74b2)))) {
                    titleTextView.setText(title);
                }
            }
        });

        backBtn.setOnClickListener(v -> finish());

        refreshBtn.setOnClickListener(v -> {
            if (webView != null) {
                webView.reload();
            }
        });
    }

    private void loadWeatherUrl() {
        if (weatherUrl != null && !weatherUrl.isEmpty()) {
            if (weatherUrl.startsWith("http://")) {
                weatherUrl = weatherUrl.replaceFirst("http://", "https://");
            }
            webView.loadUrl(weatherUrl);
        }
    }


    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
