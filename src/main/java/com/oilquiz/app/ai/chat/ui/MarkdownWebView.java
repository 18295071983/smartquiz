package com.oilquiz.app.ai.chat.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.oilquiz.app.ai.chat.render.MarkdownHtmlConverter;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息级 Markdown WebView：把 AI/用户正文（markdown，或混排裸 HTML）用 WebView 渲染，
 * 解决 Markwon Span 路径在"正文混 HTML/表格、非标准 markdown"下的错位与逐 token 重排闪烁。
 *
 * 关键设计（针对 WebView 嵌 RecyclerView 的两大痛点）：
 * 1) 自动撑高、无内部滚动上限：注入 ResizeObserver 监听 body 尺寸变化，经
 *    {@code @JavascriptInterface onHeight(cssPx)} 回传，原生按内容高设本容器 LayoutParams，
 *    父布局随之增长（区别于旧 HtmlCardView 的 360dp 截断内滚）。
 * 2) 流式不整页重载：首次 loadDataWithBaseURL 建文档；后续 token 增量用
 *    evaluateJavascript 原地替换 body.innerHTML，避免页面重加载导致的闪烁/滚动复位。
 * 3) 高度缓存：按内容 hash 缓存最终高度，RecyclerView 复用回绑已定稿消息时直接套用，
 *    不再重新测量 → 消除滚回时的抖动。
 *
 * 本组件独立于 ChatAdapter，暂不接线；接线（开关接管正文气泡 + 池化）为下一步。
 */
public class MarkdownWebView extends FrameLayout {

    /** 内容高度变化回调（供外部触发 RecyclerView item 重布局）。 */
    public interface OnContentHeightChanged {
        void onHeightChanged(int heightPx);
    }

    /** 未测量到内容高时的占位高（dp），避免首帧 0 高。 */
    private static final int PLACEHOLDER_HEIGHT_DP = 24;
    /** 高度变化小于该 dp 则忽略，抑制抖动。 */
    private static final int HEIGHT_EPSILON_DP = 2;
    /** 内容超高时的上限（dp），超出改内部滚动，防单条超长消息把 WebView 撑到巨大吃内存。 */
    private static final int MAX_HEIGHT_DP = 6000;

    /** 内容 hash → css 像素高度缓存（LRU）。跨复用/滚动保留，定稿消息套用免重测。 */
    private static final Map<String, Integer> HEIGHT_CACHE =
            java.util.Collections.synchronizedMap(new LinkedHashMap<String, Integer>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> e) {
                    return size() > 120;
                }
            });

    private final WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private final int placeholderPx;
    private final int epsilonPx;

    private boolean night;
    private boolean pageReady;
    private boolean lastStreaming;
    private int lastAppliedPx = -1;
    private String currentContentHash;
    private String themeVars = "";
    private final int maxHeightPx;
    private OnContentHeightChanged heightListener;

    public MarkdownWebView(Context context) {
        this(context, null);
    }

    public MarkdownWebView(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        placeholderPx = dp(PLACEHOLDER_HEIGHT_DP);
        epsilonPx = dp(HEIGHT_EPSILON_DP);
        maxHeightPx = dp(MAX_HEIGHT_DP);

        webView = new WebView(context);
        webView.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        // 正文按设备宽渲染，禁用户缩放避免误触
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        LayoutParams wlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        addView(webView, wlp);

        webView.addJavascriptInterface(new Bridge(), "QZ");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request != null && request.getUrl() != null ? request.getUrl().toString() : null;
                if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) {
                    try {
                        com.oilquiz.app.ai.chat.component.ComponentActions.openLink(getContext(), u);
                    } catch (Throwable ignore) { }
                    return true; // 外部打开，不在气泡内导航
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageReady = true;
                injectResizeObserver();
            }
        });

        // 触摸：不让外层 RecyclerView 抢事件（气泡内可滚/可点），但正文一般不滚
        setOnTouchListener((v, event) -> {
            if (event != null && v.getParent() != null) {
                v.getParent().requestDisallowInterceptTouchEvent(
                        event.getActionMasked() != MotionEvent.ACTION_UP
                                && event.getActionMasked() != MotionEvent.ACTION_CANCEL);
            }
            return false;
        });
    }

    public void setOnContentHeightChanged(OnContentHeightChanged l) {
        this.heightListener = l;
    }

    public void setNight(boolean night) {
        if (this.night == night) return;
        this.night = night;
        // 主题变化需整篇重载（CSS 在 head）
        pageReady = false;
        if (pendingHtml != null) loadDocument(pendingHtml);
    }

    /**
     * 设置主题 CSS 变量覆盖（对齐应用 ThemeColors token），如
     * "--fg:#111;--link:#2563eb;--muted:#666"。变化会触发整篇重载以套用新色。
     */
    public void setThemeColors(String cssVars) {
        String v = cssVars == null ? "" : cssVars;
        if (v.equals(this.themeVars)) return;
        this.themeVars = v;
        pageReady = false;
        if (pendingHtml != null) loadDocument(pendingHtml);
    }

    private String pendingHtml;

    /**
     * 设置/更新正文内容。markdown 或混排 HTML 均可。streaming=true 时优先原地换 body 不重载。
     */
    @SuppressLint("SetJavaScriptEnabled")
    public void setContent(String content, boolean streaming) {
        String hash = hashOf(content);
        currentContentHash = hash;
        lastStreaming = streaming;
        // 定稿内容命中缓存 → 先套用缓存高，减少重测抖动
        Integer cached = HEIGHT_CACHE.get(hash);
        if (cached != null && lastAppliedPx < 0) {
            applyHeightPx((int) (cached * density));
        } else if (cached == null && lastAppliedPx < 0) {
            // 无缓存且未测量 → 先给占位高，避免 WebView 首帧塌陷为 0 高（随后 JS 实测再撑开）
            applyHeightPx(placeholderPx);
        }
        String bodyHtml = MarkdownHtmlConverter.toBodyHtml(content == null ? "" : content);
        String doc = MarkdownHtmlConverter.wrapDocument(bodyHtml, night, themeVars);
        pendingHtml = doc;
        if (!pageReady) {
            loadDocument(doc);
        } else if (streaming) {
            swapBody(bodyHtml);
        } else {
            // 非流式的整篇更新也走原地换 body，避免重载闪烁；主题变化才走 loadDocument
            swapBody(bodyHtml);
        }
    }

    private void loadDocument(String fullHtml) {
        webView.loadDataWithBaseURL("file:///android_asset/", fullHtml, "text/html", "UTF-8", null);
    }

    /** 原地替换 body：base64 传 HTML 规避引号/转义问题；ResizeObserver 会自动重报高度。 */
    private void swapBody(String bodyHtml) {
        String b64 = Base64.encodeToString(
                (bodyHtml == null ? "" : bodyHtml).getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
        String js = "(function(){try{var h=decodeURIComponent(escape(atob('" + b64 + "')));"
                + "document.body.innerHTML=h;if(window.__qzBoot)window.__qzBoot();}catch(e){}})();";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable ignore) { }
    }

    /** 注入 ResizeObserver：body 尺寸变化 → 回传高度；并首帧主动报一次。 */
    private void injectResizeObserver() {
        String js = "(function(){"
                + "function rep(){var b=document.body,d=document.documentElement;"
                + "var h=Math.max(b?b.scrollHeight:0,d?d.scrollHeight:0);"
                + "try{QZ.onHeight(h);}catch(e){}}"
                + "window.__rep=rep;"
                + "if(window.ResizeObserver){var ro=new ResizeObserver(function(){rep();});"
                + "if(document.body)ro.observe(document.body);}"
                + "rep();"
                + "window.addEventListener('load',rep);"
                + "})();";
        webView.post(() -> { try { webView.evaluateJavascript(js, null); } catch (Throwable ignore) { } });
    }

    private final class Bridge {
        @JavascriptInterface
        public void onHeight(final int cssPx) {
            if (cssPx <= 0) return;
            // 仅定稿(非流式)内容写缓存：流式中间态每 token 变，缓存会污染 LRU 且无复用价值
            if (currentContentHash != null && !lastStreaming) HEIGHT_CACHE.put(currentContentHash, cssPx);
            main.post(() -> applyHeightPx((int) (cssPx * density)));
        }

        @JavascriptInterface
        public void copy(final String text) {
            main.post(() -> {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("code", text));
                    android.widget.Toast.makeText(getContext(), "已复制", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignore) { }
            });
        }

        @JavascriptInterface
        public void previewImage(final String url) {
            if (url == null || url.isEmpty()) return;
            main.post(() -> {
                try {
                    com.oilquiz.app.ai.chat.component.ImagePreviewUtil.show(getContext(), url);
                } catch (Throwable ignore) { }
            });
        }
    }

    private void applyHeightPx(int px) {
        if (px <= 0) return;
        // 超高兜底：超过上限则封顶并允许 WebView 内部滚动，防单条超长消息撑爆内存
        boolean capped = px > maxHeightPx;
        if (capped) px = maxHeightPx;
        try {
            webView.setVerticalScrollBarEnabled(capped);
        } catch (Throwable ignore) { }
        if (lastAppliedPx >= 0 && Math.abs(px - lastAppliedPx) < epsilonPx) return;
        lastAppliedPx = px;
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null && lp.height != px) {
            lp.height = px;
            setLayoutParams(lp);
        }
        if (heightListener != null) heightListener.onHeightChanged(px);
    }

    /** 复用/回收时重置：清占位高，允许下次测量。 */
    public void resetForReuse() {
        lastAppliedPx = -1;
        pageReady = false;
        currentContentHash = null;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private static String hashOf(String s) {
        if (s == null) return "null";
        // 轻量内容指纹：长度 + 首尾各 64 字符，避免整篇 hashCode 碰撞且开销低
        int n = s.length();
        if (n <= 128) return n + ":" + s;
        return n + ":" + s.substring(0, 64) + "~" + s.substring(n - 64);
    }
}
