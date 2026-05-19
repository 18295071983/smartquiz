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

    private static final String TAG = "WeatherWebView";
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
        settings.setDatabaseEnabled(true);
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

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
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
                if (titleTextView != null && (weatherTitle == null || weatherTitle.equals("天气详情"))) {
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
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
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
