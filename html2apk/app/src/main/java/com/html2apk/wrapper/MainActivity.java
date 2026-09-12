package com.html2apk.wrapper;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * html2apk WebView 壳。
 * <p>
 * 加载规则：
 * <ol>
 *   <li>如果 assets/start_url.txt 存在且非空，加载其中指定的远程 URL（http/https）；</li>
 *   <li>否则加载本地打包进 assets 的 file:///android_asset/index.html。</li>
 * </ol>
 */
public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        // 站内跳转保持在应用内；仅放行 http/https/file
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null
                        && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://"))) {
                    view.loadUrl(url);
                }
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        String startUrl = readAsset("start_url.txt");
        if (startUrl != null && !startUrl.trim().isEmpty()) {
            webView.loadUrl(startUrl.trim());
        } else {
            webView.loadUrl("file:///android_asset/index.html");
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
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    private String readAsset(String name) {
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(getAssets().open(name), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
