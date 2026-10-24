package com.oilquiz.app.ai.chat.render;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

/**
 * 整篇 Markdown 文档的阅读视图（2026-10-25）。
 *
 * <p>用途：开源许可 / 隐私政策这类"随包 md 文档"页面。此前用 Markwon 渲进 TextView，
 * 表格、长代码块、脚注、公式都会退化，而且没有页面级排版；这里直接复用
 * {@link MarkdownDocPage}（markdown-it + 插件 + KaTeX + highlight.js）生成的单文件 HTML，
 * 交 WebView 渲染，排版与 {@code markdown_doc} 卡片完全同源。</p>
 *
 * <p>页面自带全部 JS/CSS/字体，{@code loadDataWithBaseURL} 的 base 只用于相对图片；
 * 外链一律交给系统浏览器，file/javascript 等协议一律拦掉。</p>
 */
public class MarkdownDocView extends WebView {

    public MarkdownDocView(Context context) {
        super(context);
        init(context);
    }

    public MarkdownDocView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void init(Context context) {
        setBackgroundColor(Color.TRANSPARENT);
        setLongClickable(true);
        setVerticalScrollBarEnabled(true);
        setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        WebSettings s = getSettings();
        // 页面是本地自包含模板：脚本只服务于公式/代码高亮，不接任何桥
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(false);
        s.setAllowFileAccess(true);          // 文档里的本地图片
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(textZoom(context));
        setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request == null || request.getUrl() == null
                        ? null : request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }
        });
    }

    /** 渲染整篇 markdown（night=深色）。 */
    public void setMarkdown(String markdown, boolean night) {
        String page;
        try {
            page = MarkdownDocPage.buildForScreen(getContext(), markdown, night);
        } catch (Throwable t) {
            page = "<!DOCTYPE html><html><body style=\"padding:16px;font-family:sans-serif\">"
                    + "文档渲染失败：" + escape(t.getMessage()) + "</body></html>";
        }
        loadDataWithBaseURL("file:///android_asset/", page, "text/html", "UTF-8", null);
    }

    private boolean handleUrl(String url) {
        if (url == null || url.isEmpty()) return true;
        if (url.startsWith("#")) return false;                 // 页内锚点
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("mailto:") || lower.startsWith("tel:")) {
            try {
                getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable ignored) {
            }
            return true;
        }
        return true;                                           // file/javascript/data 一律不导航
    }

    private static int textZoom(Context context) {
        try {
            float scale = context.getResources().getConfiguration().fontScale;
            int zoom = Math.round(100 * scale);
            return Math.max(85, Math.min(160, zoom));
        } catch (Throwable t) {
            return 100;
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
