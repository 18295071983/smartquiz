package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.JsonObject;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.util.APIKeyManager;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public class WeatherDetailActivity extends AppCompatActivity {

    private static final String TAG = "WeatherDetail";
    private static final String API_HOST = "https://m278m2y7ak.re.qweatherapi.com";
    private static final String DEFAULT_API_KEY = "be2af1f8490344feb8a7125ab46608dd";

    private String city;
    private double lat = 0;
    private double lon = 0;
    private boolean hasLocation = false;
    private String apiKey;
    private String fxLink;

    private WebView webView;
    private ProgressBar progressBar;
    private TextView tvLoading;
    private ImageView backBtn;
    private ImageView refreshBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_weather_detail_webview);

        city = getIntent().getStringExtra("city");
        lat = getIntent().getDoubleExtra("lat", 0);
        lon = getIntent().getDoubleExtra("lon", 0);
        hasLocation = (lat != 0 && lon != 0);
        fxLink = getIntent().getStringExtra("fxLink");
        if (city == null || city.isEmpty()) {
            city = "北京";
        }

        apiKey = DEFAULT_API_KEY;
        APIKeyManager apiKeyManager = APIKeyManager.getInstance(this);
        String savedKey = apiKeyManager.getAPIKey(APIKeyManager.Service.HEFENG_WEATHER);
        if (savedKey != null && !savedKey.isEmpty()) {
            apiKey = savedKey;
        }

        initViews();
        loadWeatherDetail();
    }

    private void initViews() {
        webView = findViewById(R.id.web_view);
        progressBar = findViewById(R.id.progress_bar);
        tvLoading = findViewById(R.id.tv_loading);
        backBtn = findViewById(R.id.btn_back);
        refreshBtn = findViewById(R.id.btn_refresh);

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

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
                tvLoading.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                tvLoading.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
            }
        });

        backBtn.setOnClickListener(v -> finish());

        refreshBtn.setOnClickListener(v -> {
            if (webView.getVisibility() == View.VISIBLE) {
                webView.reload();
            } else {
                loadWeatherDetail();
            }
        });
    }

    private String getLocationParam() {
        if (hasLocation) {
            return String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
        }
        try {
            return URLEncoder.encode(city, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return city;
        }
    }

    private void loadWeatherDetail() {
        webView.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        tvLoading.setText("正在加载天气数据...");
        tvLoading.setVisibility(View.VISIBLE);

        if (fxLink != null && !fxLink.isEmpty()) {
            runOnUiThread(() -> {
                tvLoading.setText("正在加载天气详情...");
                webView.loadUrl(fxLink);
            });
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                String location = getLocationParam();
                String url = API_HOST + "/v7/weather/now?location=" + location;
                String response = httpGet(url);
                com.google.gson.Gson gson = new com.google.gson.Gson();
                JsonObject json = gson.fromJson(response, JsonObject.class);

                if (!"200".equals(json.get("code").getAsString())) {
                    Log.e(TAG, "Weather now failed: " + json.get("code"));
                    runOnUiThread(() -> {
                        tvLoading.setText("加载失败，请重试");
                        progressBar.setVisibility(View.GONE);
                    });
                    return;
                }

                if (json.has("fxLink") && !json.get("fxLink").isJsonNull()) {
                    fxLink = json.get("fxLink").getAsString();
                }

                if (fxLink == null || fxLink.isEmpty()) {
                    runOnUiThread(() -> {
                        tvLoading.setText("无法获取天气链接");
                        progressBar.setVisibility(View.GONE);
                    });
                    return;
                }

                runOnUiThread(() -> {
                    tvLoading.setText("正在加载天气详情...");
                    webView.loadUrl(fxLink);
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading weather", e);
                runOnUiThread(() -> {
                    tvLoading.setText("加载失败: " + e.getMessage());
                    progressBar.setVisibility(View.GONE);
                });
            }
        });
    }

    private String httpGet(String urlString) throws Exception {
        java.net.URL url = new java.net.URL(urlString);
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("X-QW-Api-Key", apiKey);
        connection.setRequestProperty("Accept-Encoding", "gzip, deflate");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP " + responseCode);
        }

        String encoding = connection.getContentEncoding();
        java.io.InputStream inputStream;
        if ("gzip".equalsIgnoreCase(encoding)) {
            inputStream = new java.util.zip.GZIPInputStream(connection.getInputStream());
        } else if ("deflate".equalsIgnoreCase(encoding)) {
            inputStream = new java.util.zip.InflaterInputStream(connection.getInputStream());
        } else {
            inputStream = connection.getInputStream();
        }

        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(inputStream, "UTF-8"));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        connection.disconnect();
        return response.toString();
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
    protected void onDestroy() {
        if (webView != null) {
            webView.removeAllViews();
            webView.destroy();
        }
        super.onDestroy();
    }
}
