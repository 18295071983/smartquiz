package com.oilquiz.app.ai.dsh;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 内置 DSH 的账号登录页（2026-11-19）。
 *
 * <p>为什么用 App 内 WebView 而不是外部浏览器：DSH 的 token 是一次性的、登录回调也只有一个，
 * 外部浏览器多标签/复用页面容易抢回调导致"鉴权失败"；内嵌单页可以保证只有一个实例，
 * 并且登录成功后自动关闭回到 App。</p>
 */
public class DshLoginActivity extends AppCompatActivity {

    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    private String token = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        token = EmbeddedDsh.token();
        if (token == null || token.isEmpty()) {
            Toast.makeText(this, "等待 DSH 启动…", Toast.LENGTH_SHORT).show();
            waitTokenThenLoad(0);
        } else {
            loadLoginPage();
        }
    }

    private void waitTokenThenLoad(int attempt) {
        if (stopped) return;
        token = EmbeddedDsh.token();
        if (token != null && !token.isEmpty()) {
            loadLoginPage();
            return;
        }
        if (attempt >= 60) {
            Toast.makeText(this, "DSH 还没起来，先在「本机 DSH」里点启动", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        handler.postDelayed(() -> waitTokenThenLoad(attempt + 1), 1000);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void loadLoginPage() {
        if (stopped) return;
        webView = new WebView(this);
        setContentView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl(EmbeddedDsh.loginUrl());
        pollSignedIn();
    }

    /** 每 3 秒问一次插件 /v2/llm/account，登录成功就自动关页。 */
    private void pollSignedIn() {
        if (stopped) return;
        new Thread(() -> {
            boolean signed = false;
            HttpURLConnection conn = null;
            try {
                String tok = EmbeddedDsh.pluginToken(this);
                if (tok != null && !tok.isEmpty()) {
                    conn = (HttpURLConnection) new URL("http://127.0.0.1:8218/v2/llm/account")
                            .openConnection();
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2500);
                    conn.setRequestProperty("Authorization", "Bearer " + tok);
                    if (conn.getResponseCode() == 200) {
                        signed = true;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
            final boolean ok = signed;
            handler.post(() -> {
                if (stopped) return;
                if (ok) {
                    Toast.makeText(this, "DeepSeek 账号已登录", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    handler.postDelayed(this::pollSignedIn, 3000);
                }
            });
        }, "dsh-login-poll").start();
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
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            try {
                webView.destroy();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }
}
