package com.oilquiz.app.ai.chat.component;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
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
        return data != null && data.props != null && data.props.has("html");
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props;
        String html = p.optString("html", "");
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

        WebView webView = new WebView(context);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setVerticalScrollBarEnabled(true);
        webView.setHorizontalScrollBarEnabled(false);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(false);
        webView.setWebChromeClient(new WebChromeClient());

        // 初始高度：内容自适应前先用 120dp 占位，onPageFinished 后按内容高度调整
        final int maxHeightPx = dp(context, maxHeightDp);
        final LinearLayout.LayoutParams wvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 120));
        webView.setLayoutParams(wvLp);

        final WebView wvRef = webView;
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 内容高度自适应：JS 读取 scrollHeight，限制在 maxHeight 内，超出内部滚动
                view.evaluateJavascript(
                        "(function(){var b=document.body;var d=document.documentElement;" +
                                "var h=Math.max(b.scrollHeight,d.scrollHeight,200);" +
                                "return String(h);})()",
                        value -> {
                            if (value == null || wvRef == null) return;
                            try {
                                String v = value.replace("\"", "").trim();
                                int h = (int) (Float.parseFloat(v) * context.getResources().getDisplayMetrics().density);
                                int target = Math.min(h + dp(context, 16), maxHeightPx);
                                wvRef.post(() -> {
                                    ViewGroup.LayoutParams lp = wvRef.getLayoutParams();
                                    if (lp != null) {
                                        lp.height = target;
                                        wvRef.setLayoutParams(lp);
                                    }
                                });
                            } catch (Exception ignored) {
                            }
                        });
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 120)));
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
