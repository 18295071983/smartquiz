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

import java.io.File;
import java.util.Locale;

/**
 * 整篇 Markdown 文档的阅读视图（2026-10-25）。
 *
 * <p>用途：开源许可 / 隐私政策这类"随包 md 文档"页面，以及 2026-10-25 起的整篇 md
 * 文件预览（FileRenderActivity，{@link #setMarkdownForFile(String, java.io.File)}）。
 * 此前用 Markwon 渲进 TextView，
 * 表格、长代码块、脚注、公式都会退化，而且没有页面级排版；这里直接复用
 * {@link MarkdownDocPage}（markdown-it + 插件 + KaTeX + highlight.js）生成的单文件 HTML，
 * 交 WebView 渲染，排版与 {@code markdown_doc} 卡片完全同源。</p>
 *
 * <p>页面自带全部 JS/CSS/字体，{@code loadDataWithBaseURL} 的 base 只用于相对图片；
 * 外链一律交给系统浏览器，file/javascript 等协议一律拦掉。</p>
 */
public class MarkdownDocView extends WebView {

    private static final String ASSET_BASE = "file:///android_asset/";

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

    /** 渲染整篇 markdown（night=深色）；base 为 assets，相对图片按工作区解析。 */
    public void setMarkdown(String markdown, boolean night) {
        load(pageOf(markdown, night, true), ASSET_BASE);
    }

    /**
     * 文件预览：整篇 md 文件，相对图片按 md 所在目录解析（{@link #fileBaseUrl(File)}），
     * 主题跟随系统深浅色。
     *
     * <p>与 {@link #setMarkdown(String, boolean)} 的区别：文件里的 {@code ![](./a.png)} 是相对
     * md 自己所在目录，而不是应用工作区，所以不能走工作区改写，交给 baseUrl 解析。</p>
     */
    public void setMarkdownForFile(String markdown, File mdFile) {
        load(pageOf(markdown, isNightMode(getContext()), false), fileBaseUrl(mdFile));
    }

    private String pageOf(String markdown, boolean night, boolean resolveWorkspaceImages) {
        try {
            return MarkdownDocPage.buildForScreen(getContext(), markdown, night, resolveWorkspaceImages);
        } catch (Throwable t) {
            return "<!DOCTYPE html><html><body style=\"padding:16px;font-family:sans-serif\">"
                    + "文档渲染失败：" + escape(t.getMessage()) + "</body></html>";
        }
    }

    private void load(String page, String baseUrl) {
        loadDataWithBaseURL(baseUrl, page, "text/html", "UTF-8", null);
    }

    /** md 文件所在目录的 file:// base（相对图片用）；无父目录时退回 assets。 */
    static String fileBaseUrl(File mdFile) {
        try {
            File dir = mdFile == null ? null : mdFile.getAbsoluteFile().getParentFile();
            if (dir == null) return ASSET_BASE;
            String url = Uri.fromFile(dir).toString();
            return url.endsWith("/") ? url : url + "/";
        } catch (Throwable t) {
            return ASSET_BASE;
        }
    }

    /** 系统是否深色主题（文件预览没有自己的主题开关，跟随系统）。 */
    static boolean isNightMode(Context context) {
        try {
            return (context.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
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
