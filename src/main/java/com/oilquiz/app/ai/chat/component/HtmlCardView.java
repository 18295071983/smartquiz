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
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;

import org.json.JSONObject;

/**
 * HTML 富内容组件（系统原生 WebView 渲染，零第三方依赖）。
 *
 * <p>渲染 Agent/Python 生成的 HTML（CSS、表格、图片等），组件内可滚动/可点击，
 * 不与外层消息列表抢触摸；内容高度自适应（上限 maxHeight，超出内部滚动）。
 *
 * <p><b>安全模型（Track B0-1）</b>：能力不再由 props 决定，而由 {@link HtmlSandbox} 按
 * 内容来源分四档授予——内置模板 / 工作区本地文件 / https 网页 / 模型给的 HTML 字符串。
 * 默认档（模型内联 HTML）关闭脚本、不挂 Java 桥、禁文件与 content 访问、注入 CSP、
 * 限 512 KB，卡片内不做页面导航。依赖脚本的能力（自适应高度、图片点击预览、
 * 页内按钮回调）只在放开脚本的档位开启。
 *
 * <p>字段兼容：html/content/text/body/html_content/markdown/description/message/value
 * 及嵌套 props.data.html 等；url 字段加载网页或本地 HTML 文件（web 组件映射）。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "html": "&lt;h3&gt;标题&lt;/h3&gt;&lt;p&gt;内容&lt;/p&gt;",
 *   "url":  "https://... 或 file:///storage/.../report.html",
 *   "title": "可选卡片标题",
 *   "maxHeight": 360   // 可选，内容区最大高度(dp)，超出内部滚动
 * }
 * </pre>
 */
public class HtmlCardView implements ChatComponent {

    private static final String TAG = "HtmlCardView";

    /** 默认内容区最大高度(dp) */
    private static final int DEFAULT_MAX_HEIGHT_DP = 360;

    /** 脚本关闭时（默认档）WebView 的固定高度(dp)：读不到内容高度，靠内部滚动兜住 */
    private static final int FALLBACK_HEIGHT_DP = 160;

    @Override
    public String getType() {
        return "html";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        // 兼容多字段名 + 嵌套结构提取 HTML 内容
        String html = extractHtml(p);
        String url = p.optString("url", "");
        String title = extractTitle(p);
        int maxHeightDp = p.optInt("maxHeight", DEFAULT_MAX_HEIGHT_DP);
        if (maxHeightDp <= 0) maxHeightDp = DEFAULT_MAX_HEIGHT_DP;

        CardShell shell = CardShell.create(context).setTitle(title);

        // —— 信任档判定：拦得下的先给说明卡，连 WebView 都不建 ——
        HtmlSandbox.Decision decision = HtmlSandbox.resolve(context, data, html, url);
        if (decision.isBlocked()) {
            shell.setState(CardShell.State.ERROR, blockedText(context, decision));
            Log.w(TAG, "html load blocked by sandbox, url=" + safeUrl(url));
            return shell;
        }

        boolean hasContent = !TextUtils.isEmpty(decision.html)
                || !TextUtils.isEmpty(decision.loadUrl);
        if (!hasContent) {
            // 空态：展示可读提示 + 原始数据预览（帮助排查字段映射问题）
            shell.setState(CardShell.State.EMPTY, context.getString(R.string.card_html_empty));
            if (!url.isEmpty()) {
                shell.addBodyText(context.getString(R.string.card_html_url_hint, url),
                        ComponentTheme.metaTextSize(),
                        ComponentTheme.textTertiary(context), false);
            } else {
                String preview = p.toString();
                if (preview.length() > 200) preview = preview.substring(0, 200) + "...";
                shell.addBodyText(context.getString(R.string.card_html_fields, preview),
                        ComponentTheme.metaTextSize(),
                        ComponentTheme.textTertiary(context), false);
            }
            Log.w(TAG, "html component: empty content, props=" + p.toString());
            return shell;
        }

        final HtmlSandbox.Trust trust = decision.trust;
        final HtmlWebView webView = new HtmlWebView(context, trust);
        final boolean scriptEnabled = webView.isScriptEnabled();

        // 桥只给工作区本地文件档：模型内联内容永远拿不到 Android.* 回调
        if (HtmlSandbox.allowsBridge(trust)) {
            webView.setBridge(new HtmlJsBridge(context), HtmlWebView.BRIDGE_NAME);
        }

        // 组件可操作：阻止外层 RecyclerView 拦截触摸，WebView 内可滚动/点击。
        // 事件本身一律 return false 放行给 WebView（此前按 ACTION_DOWN 做一次 JS 探测
        // "是否点在可交互元素上"，但两个分支返回值相同——纯开销、无行为差异，已删除）
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setClickable(true);
        webView.setOnTouchListener((v, event) -> {
            // 防护：event 为 null（WebView 快速滑动/组件回收等场景）直接放行，避免 NPE
            if (event == null) {
                return false;
            }
            if (v.getParent() != null) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false; // 不消费事件，由 WebView 自身处理滚动与点击
        });

        final int maxHeightPx = dp(context, maxHeightDp);
        // WebView 外包 FrameLayout：承载"← 返回"浮标（canGoBack 时显示，卡片内可返回上一页）
        final android.widget.FrameLayout webContainer = new android.widget.FrameLayout(context);
        final LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, FALLBACK_HEIGHT_DP));
        webContainer.setLayoutParams(containerLp);
        webView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 返回浮标：右上角小圆钮，canGoBack 时显示
        final android.widget.TextView backBtn = new android.widget.TextView(context);
        backBtn.setText("←");
        backBtn.setTextSize(16);
        backBtn.setTextColor(androidx.core.content.ContextCompat.getColor(context,
                R.color.card_scrim_text));
        backBtn.setGravity(android.view.Gravity.CENTER);
        backBtn.setBackground(roundedRect(context, dp(context, 18),
                androidx.core.content.ContextCompat.getColor(context, R.color.card_scrim)));
        backBtn.setPadding(dp(context, 7), dp(context, 3), dp(context, 7), dp(context, 3));
        backBtn.setVisibility(android.view.View.GONE);
        backBtn.setOnClickListener(v -> {
            try {
                if (webView.canGoBack()) webView.goBack();
            } catch (Throwable ignored) {
            }
        });
        final android.widget.FrameLayout.LayoutParams backLp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        backLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        backLp.topMargin = dp(context, 6);
        backLp.rightMargin = dp(context, 6);
        webContainer.addView(webView);
        webContainer.addView(backBtn, backLp);

        webView.setWebChromeClient(new WebChromeClient());
        final boolean bridgeEnabled = HtmlSandbox.allowsBridge(trust);
        webView.setWebViewClient(new SandboxWebViewClient(context, trust, webView,
                webContainer, backBtn, maxHeightPx, scriptEnabled, bridgeEnabled));

        if (!TextUtils.isEmpty(decision.loadUrl)) {
            webView.loadUrl(decision.loadUrl);
        } else {
            // style→内容 CSS 桥接：组件 style（或顶层 fontSize/color/background 字段）注入
            // WebView 内容 CSS——html 内容像浏览器那样应用样式（不再只有外层卡片有样式）
            JSONObject contentStyle = extractContentStyle(p);
            String fullHtml = wrapHtml(decision.html, isNightMode(context), contentStyle);
            // 默认档：JS 已关，再叠一层 CSP 做纵深防御（脚本/外发/表单/嵌套框架全禁）
            fullHtml = HtmlSandbox.injectCsp(fullHtml, trust);
            // baseURL 指向 android_asset：内置模板的 js/css（mermaid/katex）按相对路径加载；
            // 该路径不受 setAllowFileAccess 影响，所以关掉文件访问仍渲得出图
            webView.loadDataWithBaseURL("file:///android_asset/", fullHtml,
                    "text/html", "UTF-8", null);
        }

        // 注意：不能走 shell.addBody()——那会把参数换成 WRAP_CONTENT，
        // WebView 在 wrap_content 下会塌成一条，量高失败时用户就看不到任何内容。
        // 这里显式给一个初始高度，自适应成功后由 WebViewClient 改写容器高度。
        shell.getBody().addView(webContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, FALLBACK_HEIGHT_DP)));
        if (decision.truncated) {
            shell.setState(CardShell.State.TRUNCATED, context.getString(
                    R.string.card_html_truncated, HtmlSandbox.maxInlineHtmlKb()));
        }
        Log.i(TAG, "html component created (WebView), trust=" + trust
                + ", htmlLen=" + (decision.html == null ? 0 : decision.html.length())
                + ", title=" + (title == null ? "" : title));
        return shell;
    }

    /** 拦截原因 → 用户可见文案（判定层只出枚举，文案归 strings.xml） */
    private static String blockedText(Context context, HtmlSandbox.Decision decision) {
        if (decision.blocked == HtmlSandbox.Block.SCHEME_UNSUPPORTED) {
            return context.getString(R.string.card_html_blocked_scheme,
                    decision.blockedDetail);
        }
        if (decision.blocked == HtmlSandbox.Block.HTTP_NOT_ALLOWED) {
            return context.getString(R.string.card_html_blocked_http);
        }
        return context.getString(R.string.card_html_blocked_local);
    }

    // ---------------------------------------------------------------- 加载与测量

    /**
     * 按信任档管控导航，并在加载完成后确定卡片高度。
     *
     * <p>导航口径：卡片内只允许该档必需的目标，其余一律不外溢——
     * http(s) 链接交回应用内浏览器，file 目标只允许工作区内，其它协议直接吞掉。
     */
    private static class SandboxWebViewClient extends WebViewClient {
        private final Context context;
        private final HtmlSandbox.Trust trust;
        private final HtmlWebView webView;
        private final android.widget.FrameLayout webContainer;
        private final android.widget.TextView backBtn;
        private final int maxHeightPx;
        private final boolean scriptEnabled;
        private final boolean bridgeEnabled;

        SandboxWebViewClient(Context context, HtmlSandbox.Trust trust, HtmlWebView webView,
                            android.widget.FrameLayout webContainer,
                            android.widget.TextView backBtn, int maxHeightPx,
                            boolean scriptEnabled, boolean bridgeEnabled) {
            this.context = context;
            this.trust = trust;
            this.webView = webView;
            this.webContainer = webContainer;
            this.backBtn = backBtn;
            this.maxHeightPx = maxHeightPx;
            this.scriptEnabled = scriptEnabled;
            this.bridgeEnabled = bridgeEnabled;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(view, url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request != null && request.getUrl() != null
                    ? request.getUrl().toString() : null;
            return handleNavigation(view, url);
        }

        /** @return true 表示卡片内不加载（已交回应用或被拦下） */
        private boolean handleNavigation(WebView view, String url) {
            if (url == null) return true;
            String lower = url.toLowerCase(java.util.Locale.US);
            boolean webScheme = lower.startsWith("http://") || lower.startsWith("https://");
            boolean localScheme = lower.startsWith("file://");

            if (trust == HtmlSandbox.Trust.REMOTE_PAGE) {
                // 网页浏览档：http(s) 留在卡片内连续浏览，其它协议拦下
                return !(webScheme || url.startsWith("/"));
            }
            if (trust == HtmlSandbox.Trust.LOCAL_FILE) {
                // 本地报表档：目录内相对链接继续留在卡片内；外链交回应用
                if (localScheme) {
                    String path = HtmlSandbox.filePathFromUrl(url);
                    if (path != null && !path.isEmpty()
                            && HtmlSandbox.isWithinAllowedRoots(context, new java.io.File(path))) {
                        return false;
                    }
                    Log.w(TAG, "local file navigation outside workspace blocked: " + safeUrl(url));
                    return true;
                }
                if (webScheme) {
                    ComponentActions.openLink(context, url);
                    return true;
                }
                return true;
            }
            // 内置模板 / 模型内联：卡片内不导航，外链交回应用
            if (webScheme) {
                ComponentActions.openLink(context, url);
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (webView.isComponentReleased()) return;
            // 图片点击预览依赖 Android.previewImage：只有挂了桥的档位才注入，
            // 否则页内 JS 会因 Android 未定义而报错
            if (scriptEnabled && bridgeEnabled) {
                injectImagePreview(view);
            }
            // 延迟取高度：等 CSS/JS 渲染完成；失败保持初始高度（内容可滚动，不算失败）
            view.postDelayed(this::measureContentHeight, 150L);
        }

        /** 图片点击预览（浏览器级体验）：仅放开脚本的档位注入 */
        private void injectImagePreview(WebView view) {
            try {
                view.evaluateJavascript(
                        "(function(){"
                                + "function bind(){"
                                + "document.addEventListener('click',function(e){"
                                + "var el=e.target;"
                                + "while(el&&el.tagName!=='IMG'&&el!==document){el=el.parentElement;}"
                                + "if(el&&el.tagName==='IMG'){"
                                + "var s=el.getAttribute('src');"
                                + "if(s&&s.indexOf('data:')!==0){"
                                + "e.preventDefault();e.stopPropagation();"
                                + "Android.previewImage(s);"
                                + "}}},true);"
                                + "var imgs=document.getElementsByTagName('img');"
                                + "for(var i=0;i<imgs.length;i++){imgs[i].style.cursor='zoom-in';}"
                                + "}"
                                + "if(document.readyState==='complete'||document.readyState==='interactive'){bind();}"
                                + "else{document.addEventListener('DOMContentLoaded',bind);}"
                                + "})()", null);
            } catch (Throwable ignored) {
            }
        }

        /**
         * 内容自适应高度。
         *
         * <p>放开脚本时用 scrollHeight（最准）；默认档 JS 已关，退回 getContentHeight()
         * ——它不依赖页面脚本，返回 CSS 布局高度（单位与 dp 同阶，需乘 density）。
         */
        private void measureContentHeight() {
            if (webView.isComponentReleased()) return;
            try {
                backBtn.setVisibility(webView.canGoBack()
                        ? View.VISIBLE : View.GONE);
            } catch (Throwable ignored) {
            }
            if (scriptEnabled) {
                try {
                    webView.evaluateJavascript(
                            "(function(){var b=document.body;var d=document.documentElement;"
                                    + "var h=Math.max(b.scrollHeight,d.scrollHeight,200);"
                                    + "return String(h);})()",
                            value -> applyMeasuredHeight(parseScrollHeight(value)));
                } catch (Throwable t) {
                    Log.w(TAG, "js height measure failed(keep initial): " + t.getMessage());
                }
            } else {
                applyMeasuredHeight(Math.round(webView.getContentHeight()
                        * context.getResources().getDisplayMetrics().density));
            }
        }

        private void applyMeasuredHeight(Integer heightPx) {
            if (heightPx == null || heightPx <= 0 || webView.isComponentReleased()) return;
            try {
                int target = Math.min(heightPx + dp(context, 16), maxHeightPx);
                webView.post(() -> {
                    if (webView.isComponentReleased()) return;
                    ViewGroup.LayoutParams lp = webContainer.getLayoutParams();
                    if (lp != null && lp.height != target) {
                        lp.height = target;
                        webContainer.setLayoutParams(lp);
                    }
                });
                Log.i(TAG, "html rendered, height=" + target + "px (max " + maxHeightPx + "px)");
            } catch (Throwable ignored) {
            }
        }

        private Integer parseScrollHeight(String value) {
            if (value == null) return null;
            try {
                String v = value.replace("\"", "").trim();
                return (int) (Float.parseFloat(v)
                        * context.getResources().getDisplayMetrics().density);
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ---------------------------------------------------------------- props 提取

    /**
     * 从 props 提取 HTML 内容：兼容多字段名与嵌套结构。
     * 字段优先级：html > content > text > body > html_content > markdown > description > message；
     * 嵌套支持 props.data.html / props.data.content 等（模型常把内容包在 data 里）。
     */
    private static String extractHtml(JSONObject p) {
        if (p == null) return "";
        String[] keys = {"html", "content", "text", "body", "html_content", "markdown",
                "description", "message", "value"};
        for (String k : keys) {
            String v = p.optString(k, "");
            if (!v.trim().isEmpty()) return v;
        }
        // 嵌套提取：data / result / item 对象里的 html/content
        String[] nested = {"data", "result", "item", "content_obj"};
        for (String nk : nested) {
            JSONObject obj = p.optJSONObject(nk);
            if (obj == null) continue;
            String v = extractHtml(obj);
            if (!v.trim().isEmpty()) return v;
        }
        return "";
    }

    /** 提取标题：title > heading > name（兼容多字段） */
    private static String extractTitle(JSONObject p) {
        if (p == null) return "";
        String[] keys = {"title", "heading", "name", "label"};
        for (String k : keys) {
            String v = p.optString(k, "");
            if (!v.trim().isEmpty()) return v;
        }
        return "";
    }

    /** style 对象（或顶层 fontSize/color）→ 内容 CSS 变量 */
    private static JSONObject extractContentStyle(JSONObject p) {
        JSONObject contentStyle = new JSONObject();
        JSONObject styleObj = p.optJSONObject("style");
        if (styleObj != null) {
            String[] sk = {"fontSize", "color", "background", "background_color", "bg"};
            for (String s : sk) {
                if (styleObj.has(s)) {
                    try {
                        contentStyle.put(s, styleObj.get(s));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        String[] topk = {"fontSize", "color"};
        for (String tk : topk) {
            if (!contentStyle.has(tk) && p.has(tk)) {
                try {
                    contentStyle.put(tk, p.get(tk));
                } catch (Exception ignored) {
                }
            }
        }
        return contentStyle;
    }

    /**
     * 包裹完整 HTML：非完整文档时补 viewport（手机竖屏适配）+ 基础样式。
     * 预览与全屏页（临时文件）共用，保证两处渲染一致。
     * 深色模式使用深色文字/表格/代码配色，避免白底刺眼。
     * 设计升级：统一排版（标题/段落/列表间距）、代码块配色高亮、引用块、表格美化、
     * 标签/徽章、按钮样式、链接配色、响应式图片。
     */
    private static String wrapHtml(String html, boolean dark, JSONObject style) {
        if (html == null) return "";
        String lower = html.toLowerCase();
        if (lower.contains("<!doctype") || lower.contains("<html")) {
            return html;
        }
        // 配色（浅色/深色双主题）
        String bg = dark ? "transparent" : "transparent";
        String bodyColor = dark ? "#D1D5DB" : "#333333";
        String headingColor = dark ? "#F3F4F6" : "#1F2937";
        String borderColor = dark ? "#4B5563" : "#E5E7EB";
        String preBg = dark ? "#1E293B" : "#F8FAFC";
        String codeBg = dark ? "#334155" : "#F1F5F9";
        String codeColor = dark ? "#E2E8F0" : "#475569";
        String linkColor = dark ? "#7DD3FC" : "#2563EB";
        String quoteBg = dark ? "#1E293B" : "#F0F7FF";
        String quoteBorder = dark ? "#3B82F6" : "#93C5FD";
        String tagBg = dark ? "#312E81" : "#E0E7FF";
        String tagColor = dark ? "#C7D2FE" : "#4338CA";
        String okColor = dark ? "#86EFAC" : "#16A34A";
        String warnColor = dark ? "#FDE68A" : "#B45309";
        String errColor = dark ? "#FCA5A5" : "#DC2626";
        // style → 内容 CSS（像浏览器应用组件 style）：fontSize/color/background 覆盖默认
        String bodyFontSize = "14px";
        if (style != null && style.has("fontSize")) {
            Object fs = style.opt("fontSize");
            if (fs instanceof Number) {
                bodyFontSize = fs + "px";
            } else {
                String fss = String.valueOf(fs).trim().replaceAll("[^0-9.]", "");
                if (!fss.isEmpty()) bodyFontSize = fss + "px";
            }
        }
        if (style != null && style.has("color")) {
            bodyColor = String.valueOf(style.opt("color"));
        }
        if (style != null && style.has("background")) {
            bg = String.valueOf(style.opt("background"));
        } else if (style != null && style.has("background_color")) {
            bg = String.valueOf(style.opt("background_color"));
        } else if (style != null && style.has("bg")) {
            bg = String.valueOf(style.opt("bg"));
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>"
                + "<style>"
                + "html,body{margin:0;padding:0;background:" + bg + ";}"
                + "body{font-family:-apple-system,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;"
                + "font-size:" + bodyFontSize + ";line-height:1.6;color:" + bodyColor + ";word-break:break-word;padding:4px;}"
                // 标题层级
                + "h1,h2,h3,h4{color:" + headingColor + ";line-height:1.35;margin:14px 0 8px;font-weight:600;}"
                + "h1{font-size:20px;border-bottom:2px solid " + borderColor + ";padding-bottom:6px;}"
                + "h2{font-size:17px;}h3{font-size:15px;}h4{font-size:14px;}"
                // 段落/列表
                + "p{margin:6px 0;}ul,ol{margin:6px 0;padding-left:22px;}"
                + "li{margin:3px 0;}b,strong{color:" + headingColor + ";}"
                // 图片
                + "img{max-width:100%;height:auto;border-radius:8px;margin:6px 0;}"
                // 表格
                + "table{border-collapse:collapse;width:100%;margin:8px 0;font-size:13px;}"
                + "th{background:" + (dark ? "#1F2937" : "#F3F4F6") + ";color:" + headingColor + ";"
                + "border:1px solid " + borderColor + ";padding:7px 9px;text-align:left;font-weight:600;}"
                + "td{border:1px solid " + borderColor + ";padding:6px 9px;}"
                + "tr:nth-child(even){background:" + (dark ? "#111827" : "#FAFAFA") + ";}"
                // 代码
                + "pre{background:" + preBg + ";padding:10px 12px;border-radius:8px;overflow-x:auto;"
                + "border:1px solid " + borderColor + ";font-size:12.5px;line-height:1.5;}"
                + "pre code{background:transparent;padding:0;color:" + codeColor + ";font-family:'JetBrains Mono',Consolas,monospace;}"
                + "code{background:" + codeBg + ";padding:1.5px 5px;border-radius:4px;"
                + "font-size:12.5px;color:" + codeColor + ";font-family:'JetBrains Mono',Consolas,monospace;}"
                // 引用块
                + "blockquote{margin:8px 0;padding:8px 12px;background:" + quoteBg + ";"
                + "border-left:4px solid " + quoteBorder + ";border-radius:0 8px 8px 0;color:" + bodyColor + ";}"
                + "blockquote p{margin:2px 0;}"
                // 链接
                + "a{color:" + linkColor + ";text-decoration:none;}a:hover{text-decoration:underline;}"
                // 分割线
                + "hr{border:none;border-top:1px solid " + borderColor + ";margin:10px 0;}"
                // 按钮
                + "button{padding:7px 16px;border:none;border-radius:8px;cursor:pointer;font-size:13px;"
                + "background:" + (dark ? "#334155" : "#E2E8F0") + ";color:" + headingColor + ";margin:3px 4px 3px 0;}"
                + "button:hover{opacity:0.85;}"
                // 标签/徽章（class="tag" 或 <mark>）
                + ".tag,mark{display:inline-block;padding:1px 8px;border-radius:10px;font-size:11px;"
                + "background:" + tagBg + ";color:" + tagColor + ";margin:1px 3px 1px 0;}"
                // 状态色（class="ok"/"warn"/"err"）
                + ".ok{color:" + okColor + ";font-weight:600;}.warn{color:" + warnColor + ";font-weight:600;}"
                + ".err{color:" + errColor + ";font-weight:600;}"
                // 卡片容器（class="box"）
                + ".box{background:" + (dark ? "#1E293B" : "#F8FAFC") + ";border:1px solid " + borderColor
                + ";border-radius:10px;padding:10px 12px;margin:8px 0;}"
                + "</style></head><body>" + html + "</body></html>";
    }

    /** 当前是否深色模式（决定 HTML 基础样式的文字/背景配色） */
    private static boolean isNightMode(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    // ---------------------------------------------------------------- 小工具

    /** 日志里不打印完整 url（可能带 token/路径隐私），只留协议+主机+文件名 */
    private static String safeUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            android.net.Uri u = android.net.Uri.parse(url);
            String host = u.getHost();
            String last = u.getLastPathSegment();
            return (u.getScheme() != null ? u.getScheme() : "?") + "://"
                    + (host == null ? "" : host) + (last == null ? "" : "/…" + last);
        } catch (Throwable t) {
            return url.length() > 40 ? url.substring(0, 40) + "…" : url;
        }
    }

    /** 圆角纯色背景（返回浮标用） */
    private static android.graphics.drawable.Drawable roundedRect(Context context, int radiusPx, int color) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(radiusPx);
        gd.setColor(color);
        return gd;
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    /**
     * HTML 组件 JS → Java 桥：按钮 onclick 里可调用
     * Android.showToast('...') / Android.copy('...') / Android.openLink('url')
     * 让本地 HTML 报表里的按钮能真实回调应用（Toast/复制/打开链接），不再"点了没反应"。
     *
     * <p>只挂到 {@link HtmlSandbox.Trust#LOCAL_FILE} 档的 WebView 上——
     * 模型直接给的 HTML 拿不到本桥，注入脚本也就无法读写剪贴板、打开链接或取本地文件。
     */
    public static class HtmlJsBridge {
        private final Context context;

        public HtmlJsBridge(Context context) {
            this.context = context;
        }

        @android.webkit.JavascriptInterface
        public void showToast(String message) {
            try {
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> android.widget.Toast.makeText(context,
                        message != null ? message : "", android.widget.Toast.LENGTH_SHORT).show());
            } catch (Throwable ignored) {
            }
        }

        @android.webkit.JavascriptInterface
        public void copy(String text) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && text != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("组件内容", text));
                    showToast("已复制");
                }
            } catch (Throwable ignored) {
            }
        }

        /** 打开链接：外链交回应用内浏览器；本地路径只允许工作区内（不再任意路径打开） */
        @android.webkit.JavascriptInterface
        public void openLink(String url) {
            try {
                if (url == null || url.isEmpty()) return;
                String lower = url.toLowerCase(java.util.Locale.US);
                if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                    String path = lower.startsWith("file://")
                            ? HtmlSandbox.filePathFromUrl(url) : url;
                    if (path == null || !HtmlSandbox.isWithinAllowedRoots(context, new java.io.File(path))) {
                        Log.w(TAG, "bridge openLink blocked: " + safeUrl(url));
                        showToast("仅允许打开应用工作区内的文件");
                        return;
                    }
                }
                ComponentActions.openLink(context, url);
            } catch (Throwable ignored) {
            }
        }

        /**
         * HTML 内图片点击全屏预览（浏览器级体验）——对齐项目标准链路：
         * 本地文件 BitmapFactory 采样解码（不用 Glide，file:// 路径 Glide 易不回调/灰屏），
         * 网络 URL 才走原生下载（15s 超时+失败 Toast），全屏用 PhotoView（双指缩放+点击关闭）。
         *
         * <p>路径口径：只接受 https 图片，或按相对名解析后仍落在工作区内的文件；
         * 任意绝对路径（如 /data/data/… 、/sdcard/DCIM/…）一律拒绝——
         * 报表引图靠相对路径，不需要任意路径解析能力。
         */
        @android.webkit.JavascriptInterface
        public void previewImage(String url) {
            try {
                if (url == null || url.isEmpty()) return;
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    try {
                        String imageUrl = url;
                        final boolean isNetwork = imageUrl.startsWith("http://")
                                || imageUrl.startsWith("https://");
                        if (!isNetwork && !imageUrl.startsWith("content://")) {
                            String p = imageUrl.startsWith("file://")
                                    ? HtmlSandbox.filePathFromUrl(imageUrl) : imageUrl;
                            if (p == null) return;
                            java.io.File f = new java.io.File(p);
                            if (!f.isFile()) {
                                java.io.File resolved = com.oilquiz.app.ai.agent.online.AgentWorkspace
                                        .getInstance(context).resolveExistingFile(p);
                                if (resolved != null && resolved.isFile()) f = resolved;
                            }
                            if (!f.isFile() || !HtmlSandbox.isWithinAllowedRoots(context, f)) {
                                Log.w(TAG, "bridge previewImage blocked: " + safeUrl(url));
                                showToast("仅允许预览应用工作区内的图片");
                                return;
                            }
                            imageUrl = f.getAbsolutePath();
                        }
                        final String finalUrl = imageUrl;
                        // 非 Activity 上下文无法弹 Dialog（BadTokenException 静默失败），给提示
                        if (!(context instanceof android.app.Activity)) {
                            android.widget.Toast.makeText(context, "图片: " + finalUrl,
                                    android.widget.Toast.LENGTH_LONG).show();
                            return;
                        }
                        final boolean isLocalFile = !isNetwork
                                && !finalUrl.startsWith("content://")
                                && new java.io.File(finalUrl).isFile();

                        android.app.Dialog dialog = new android.app.Dialog(context);
                        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
                        android.widget.FrameLayout root = new android.widget.FrameLayout(context);
                        root.setBackgroundColor(android.graphics.Color.BLACK);
                        com.github.chrisbanes.photoview.PhotoView photoView =
                                new com.github.chrisbanes.photoview.PhotoView(context);
                        photoView.setBackgroundColor(android.graphics.Color.BLACK);
                        photoView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                        root.addView(photoView, new android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
                        android.widget.ProgressBar loading = new android.widget.ProgressBar(context);
                        root.addView(loading, new android.widget.FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.Gravity.CENTER));
                        dialog.setContentView(root, new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        photoView.setOnClickListener(v -> dialog.dismiss());
                        dialog.show();
                        if (dialog.getWindow() != null) {
                            dialog.getWindow().setLayout(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT);
                            dialog.getWindow().setBackgroundDrawable(
                                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK));
                        }
                        if (isLocalFile) {
                            // 本地文件：BitmapFactory 采样解码（防 OOM），失败 Toast 明确提示
                            try {
                                android.graphics.BitmapFactory.Options opts =
                                        new android.graphics.BitmapFactory.Options();
                                opts.inJustDecodeBounds = true;
                                android.graphics.BitmapFactory.decodeFile(finalUrl, opts);
                                int sample = 1;
                                while (opts.outWidth / sample > 2048 || opts.outHeight / sample > 2048) {
                                    sample *= 2;
                                }
                                opts.inJustDecodeBounds = false;
                                opts.inSampleSize = sample;
                                android.graphics.Bitmap bmp =
                                        android.graphics.BitmapFactory.decodeFile(finalUrl, opts);
                                if (bmp != null) {
                                    photoView.setImageBitmap(bmp);
                                    loading.setVisibility(View.GONE);
                                } else {
                                    if (dialog.isShowing()) dialog.dismiss();
                                    android.widget.Toast.makeText(context, "图片解码失败（文件损坏或非图片）",
                                            android.widget.Toast.LENGTH_LONG).show();
                                }
                            } catch (Throwable t) {
                                if (dialog.isShowing()) dialog.dismiss();
                                android.widget.Toast.makeText(context, "图片加载失败: " + t.getMessage(),
                                        android.widget.Toast.LENGTH_LONG).show();
                            }
                        } else {
                            // 网络图：直接原生下载+BitmapFactory（系统解码），不走 Glide——
                            // 设备上 Glide 对 https/重定向 URL 可能长时间不回调（转圈十几秒），
                            // 原生下载 1~3s 出图（手机实测同 URL curl 1.6s）
                            downloadAndShow(dialog, photoView, loading, finalUrl);
                        }
                    } catch (Throwable t) {
                        try {
                            android.widget.Toast.makeText(context, "图片预览失败: " + t.getMessage(),
                                    android.widget.Toast.LENGTH_LONG).show();
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        /** 网络图兜底：HttpURLConnection 下载 + BitmapFactory 采样解码（防 OOM），完全绕开 Glide */
        private void downloadAndShow(final android.app.Dialog dialog,
                                     final com.github.chrisbanes.photoview.PhotoView photoView,
                                     final android.widget.ProgressBar loading,
                                     final String url) {
            new Thread(() -> {
                try {
                    // 第一遍：读尺寸（采样用）
                    android.graphics.BitmapFactory.Options bounds =
                            new android.graphics.BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    java.net.HttpURLConnection conn =
                            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(10000);
                    conn.connect();
                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) {
                        throw new java.io.IOException("HTTP " + code);
                    }
                    try (java.io.InputStream is = conn.getInputStream()) {
                        android.graphics.BitmapFactory.decodeStream(is, null, bounds);
                    }
                    int sample = 1;
                    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
                        sample *= 2;
                    }
                    // 第二遍：正式解码
                    java.net.HttpURLConnection conn2 =
                            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    conn2.setInstanceFollowRedirects(true);
                    conn2.setConnectTimeout(8000);
                    conn2.setReadTimeout(10000);
                    conn2.connect();
                    android.graphics.Bitmap bmp = null;
                    try (java.io.InputStream is2 = conn2.getInputStream()) {
                        android.graphics.BitmapFactory.Options opts =
                                new android.graphics.BitmapFactory.Options();
                        opts.inSampleSize = sample;
                        bmp = android.graphics.BitmapFactory.decodeStream(is2, null, opts);
                    }
                    final android.graphics.Bitmap fbmp = bmp;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        try {
                            if (fbmp != null) {
                                if (dialog.isShowing()) {
                                    photoView.setImageBitmap(fbmp);
                                    loading.setVisibility(View.GONE);
                                }
                            } else {
                                if (dialog.isShowing()) dialog.dismiss();
                                android.widget.Toast.makeText(context, "图片解码失败（格式不支持或数据损坏）",
                                        android.widget.Toast.LENGTH_LONG).show();
                            }
                        } catch (Throwable ignored) {
                        }
                    });
                } catch (Throwable t) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        try {
                            if (dialog.isShowing()) dialog.dismiss();
                            android.widget.Toast.makeText(context, "图片加载失败: " + t.getMessage(),
                                    android.widget.Toast.LENGTH_LONG).show();
                        } catch (Throwable ignored) {
                        }
                    });
                }
            }).start();
        }
    }
}
