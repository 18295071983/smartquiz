package com.oilquiz.app.ai.chat.component;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * HTML 富内容组件：渲染 Agent/Python 生成的 HTML（支持 CSS、简单 JS）。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "html": "&lt;h3&gt;标题&lt;/h3&gt;&lt;p&gt;内容&lt;/p&gt;&lt;table&gt;...&lt;/table&gt;",
 *   "title": "可选卡片标题",
 *   "maxHeight": 360   // 可选，内容区最大高度(dp)，超出滚动
 * }
 * </pre>
 */
public class HtmlCardView implements ChatComponent {

    /** 默认内容区最大高度(dp) */
    private static final int DEFAULT_MAX_HEIGHT_DP = 360;

    @Override
    public String getType() {
        return "html";
    }

    @Override
    public boolean canRender(ComponentData data) {
        // 恒可渲染：props 为空时展示"未获取到内容"提示，避免显示"空内容"
        return data != null;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        // html 键优先，兼容 content/text 键（Agent 可能用不同键名）
        String html = p.optString("html", "");
        if (html.isEmpty()) html = p.optString("content", "");
        if (html.isEmpty()) html = p.optString("text", "");
        if (html.isEmpty() && p.length() > 0) html = p.toString();
        String title = p.optString("title", "");
        int maxHeightDp = p.optInt("maxHeight", DEFAULT_MAX_HEIGHT_DP);
        if (maxHeightDp <= 0) maxHeightDp = DEFAULT_MAX_HEIGHT_DP;

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8));
        card.setBackground(cardBackground(context));

        if (!TextUtils.isEmpty(title)) {
            TextView titleTv = new TextView(context);
            titleTv.setText(title);
            titleTv.setTextSize(14);
            titleTv.setTextColor(ComponentColors.textPrimary(context));
            titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleTv.setPadding(0, 0, 0, dp(context, 6));
            card.addView(titleTv);
        }

        if (html.isEmpty()) {
            // props 未解析出内容：给出可读提示，不显示空白 WebView
            TextView emptyTv = new TextView(context);
            emptyTv.setText("⚠ 未获取到组件内容（html 数据解析为空）");
            emptyTv.setTextSize(12);
            emptyTv.setTextColor(ComponentColors.textTertiary(context));
            card.addView(emptyTv);
            Log.w("HtmlCardView", "html component: empty content, props=" + p.toString());
            return card;
        }

        WebView webView = new WebView(context);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setVerticalScrollBarEnabled(true);
        webView.setHorizontalScrollBarEnabled(false);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(false);
        webView.setWebChromeClient(new WebChromeClient());

        // 初始高度：内容自适应前先用 160dp 占位，onPageFinished 后按内容高度调整
        final int maxHeightPx = dp(context, maxHeightDp);
        final LinearLayout.LayoutParams wvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 160));
        webView.setLayoutParams(wvLp);

        final WebView wvRef = webView;
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 延迟取高度：等 JS/CSS 渲染完成，失败时保持初始高度（内容可滚动，不算渲染失败）
                view.postDelayed(() -> {
                    if (wvRef == null) return;
                    try {
                        wvRef.evaluateJavascript(
                                "(function(){var b=document.body;var d=document.documentElement;" +
                                        "var h=Math.max(b.scrollHeight,d.scrollHeight,200);" +
                                        "return String(h);})()",
                                value -> {
                                    if (value == null || wvRef == null) return;
                                    try {
                                        String v = value.replace("\"", "").trim();
                                        int h = (int) (Float.parseFloat(v)
                                                * context.getResources().getDisplayMetrics().density);
                                        int target = Math.min(h + dp(context, 16), maxHeightPx);
                                        wvRef.post(() -> {
                                            ViewGroup.LayoutParams lp = wvRef.getLayoutParams();
                                            if (lp != null && lp.height != target) {
                                                lp.height = target;
                                                wvRef.setLayoutParams(lp);
                                            }
                                        });
                                        Log.i("HtmlCardView", "html rendered, height=" + target
                                                + "px (max " + maxHeightPx + "px)");
                                    } catch (Exception ignored) {
                                    }
                                });
                    } catch (Throwable t) {
                        Log.w("HtmlCardView", "height measure failed(keep initial): " + t.getMessage());
                    }
                }, 150L);
            }
        });

        // 包裹完整 HTML：基础样式适配深色/浅色背景
        String fullHtml = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>"
                + "<style>html,body{margin:0;padding:0;background:transparent;}"
                + "body{font-family:sans-serif;font-size:14px;line-height:1.5;"
                + "color:#333333;word-break:break-word;padding:2px;}"
                + "img{max-width:100%;height:auto;}table{border-collapse:collapse;width:100%;}"
                + "td,th{border:1px solid #cccccc;padding:4px 6px;font-size:13px;}"
                + "pre{background:#f5f5f5;padding:8px;border-radius:6px;overflow-x:auto;}"
                + "code{background:#f0f0f0;padding:1px 4px;border-radius:4px;font-size:13px;}"
                + "</style></head><body>" + html + "</body></html>";
        webView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null);

        card.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 160)));
        Log.i("HtmlCardView", "html component created, htmlLen=" + html.length()
                + ", title=" + (title == null ? "" : title));
        return card;
    }

    private static android.graphics.drawable.Drawable cardBackground(Context context) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(ComponentColors.background(context));
        gd.setCornerRadius(dp(context, 10));
        gd.setStroke(dp(context, 1), ComponentColors.border(context));
        return gd;
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
