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
 * HTML 富内容组件（系统原生 WebView 渲染，零第三方依赖）。
 *
 * 完整渲染 Agent/Python 生成的 HTML（支持 CSS、简单 JS、表格、图片等），
 * 交互已修复：组件内可滚动/可点击/链接跳转，不与外层消息列表抢触摸。
 * 内容高度自适应（JS 读取 scrollHeight，上限 maxHeight 内部滚动）。
 *
 * 字段兼容：html/content/text/body/html_content/markdown/description/message/value
 * 及嵌套 props.data.html 等；url 字段直接加载网页（web 组件映射）。
 * 设计升级：统一排版 CSS（标题/列表/表格/引用/代码块配色高亮/标签徽章/按钮/
 * 状态色/卡片容器），深色模式双主题。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "html": "&lt;h3&gt;标题&lt;/h3&gt;&lt;p&gt;内容&lt;/p&gt;",
 *   "title": "可选卡片标题",
 *   "maxHeight": 360   // 可选，内容区最大高度(dp)，超出内部滚动
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
        return data != null;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        // 兼容多字段名 + 嵌套结构提取 HTML 内容
        String html = extractHtml(p);
        // url 字段：直接加载网页（web 组件映射用），优先于 html 字符串
        String url = p.optString("url", "");
        String title = extractTitle(p);
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

        boolean hasWebUrl = url.startsWith("http://") || url.startsWith("https://")
                || url.startsWith("file://");
        if (html.trim().isEmpty() && !hasWebUrl) {
            // 空态：展示可读提示 + 原始数据预览（帮助排查字段映射问题）
            LinearLayout emptyBox = new LinearLayout(context);
            emptyBox.setOrientation(LinearLayout.VERTICAL);
            emptyBox.setPadding(0, dp(context, 4), 0, dp(context, 4));
            TextView emptyTv = new TextView(context);
            emptyTv.setText("⚠ 未获取到 HTML 内容");
            emptyTv.setTextSize(13);
            emptyTv.setTextColor(ComponentColors.warning(context));
            emptyBox.addView(emptyTv);
            // 有 url 但没有 html：提示用 url 加载（应走 web 类型）
            if (!url.isEmpty()) {
                TextView urlHint = new TextView(context);
                urlHint.setText("检测到 url 字段：" + url + "\n（网页链接建议用 component_type=web 加载）");
                urlHint.setTextSize(11);
                urlHint.setTextColor(ComponentColors.textTertiary(context));
                urlHint.setPadding(0, dp(context, 4), 0, 0);
                emptyBox.addView(urlHint);
            } else {
                // 预览 props 结构（截断），帮助定位内容放错字段
                String preview = p.toString();
                if (preview.length() > 200) preview = preview.substring(0, 200) + "...";
                TextView dataTv = new TextView(context);
                dataTv.setText("接收到的字段：" + preview);
                dataTv.setTextSize(11);
                dataTv.setTextColor(ComponentColors.textTertiary(context));
                dataTv.setPadding(0, dp(context, 4), 0, 0);
                emptyBox.addView(dataTv);
            }
            card.addView(emptyBox);
            Log.w("HtmlCardView", "html component: empty content, props=" + p.toString());
            return card;
        }

        WebView webView = new WebView(context);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setVerticalScrollBarEnabled(true);
        // 横向滚动开启：宽页面（表格/PC网页）overview 全览后可横滚查看，滚动条提示可滚
        webView.setHorizontalScrollBarEnabled(true);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        // 宽度适配（浏览器式）：useWideViewPort+overview 让无 viewport 的网页（PC 页/老页）先
        // 全览缩放到屏幕宽，再双指放大——否则按 980px 渲染右侧溢出显示不全
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        // 浏览器级体验：双指缩放 + 缩放按钮（类似浏览器可缩放内容）
        webView.getSettings().setSupportZoom(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        webView.setWebChromeClient(new WebChromeClient());
        // JS → Java 桥：HTML 组件内按钮可调用 Android.showToast/copy/openLink 回调应用
        webView.addJavascriptInterface(new HtmlJsBridge(context), "Android");

        // 组件可操作：阻止外层 RecyclerView 拦截触摸，WebView 内可滚动/点击
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setClickable(true);
        final boolean[] interactiveDown = {false};
        webView.setOnTouchListener((v, event) -> {
            // 防护：event 为 null（WebView 快速滑动/组件回收等场景）直接放行，避免 NPE
            if (event == null) {
                return false;
            }
            if (v.getParent() != null) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    interactiveDown[0] = false;
                    // 异步探测按下位置是否为可点击元素（a/button/onclick/表单控件）
                    probeInteractive(webView, (int) event.getX(), (int) event.getY(), interactiveDown);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                    // 命中可交互元素（链接/按钮/onclick）→ 放行给 WebView 执行页内 JS；
                    // 空白区域 → 放行让 WebView 自身处理（不跳全屏页）
                    if (interactiveDown[0]) {
                        return false; // 让 WebView 处理点击（执行 onclick / 打开链接）
                    }
                    return false;
                default:
                    break;
            }
            return false; // 不消费事件，由 WebView 自身处理滚动
        });

        // 初始高度：内容自适应前先用 160dp 占位，onPageFinished 后按内容高度调整
        final int maxHeightPx = dp(context, maxHeightDp);
        // WebView 外包 FrameLayout：承载"← 返回"浮标（canGoBack 时显示，卡片内可返回上一页）
        final android.widget.FrameLayout webContainer = new android.widget.FrameLayout(context);
        final LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 160));
        webContainer.setLayoutParams(containerLp);
        webView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 返回浮标：右上角小圆钮，canGoBack 时显示
        final android.widget.TextView backBtn = new android.widget.TextView(context);
        backBtn.setText("←");
        backBtn.setTextSize(16);
        backBtn.setTextColor(android.graphics.Color.WHITE);
        backBtn.setGravity(android.view.Gravity.CENTER);
        backBtn.setBackground(roundedRect(context, dp(context, 18),
                android.graphics.Color.parseColor("#99000000")));
        backBtn.setPadding(dp(context, 7), dp(context, 3), dp(context, 7), dp(context, 3));
        backBtn.setVisibility(android.view.View.GONE);
        backBtn.setOnClickListener(v -> {
            try {
                if (webView != null && webView.canGoBack()) webView.goBack();
            } catch (Throwable ignored) {
            }
        });
        final android.widget.FrameLayout.LayoutParams backLp =
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        backLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        backLp.topMargin = dp(context, 6);
        backLp.rightMargin = dp(context, 6);
        webContainer.addView(webView);
        webContainer.addView(backBtn, backLp);

        final WebView wvRef = webView;
        // 本地文件模式：file:// 导航在 WebView 内部加载（不拦截系统打开），否则外部打开
        final boolean isFileMode = url.startsWith("file://");
        webView.setWebViewClient(new WebViewClient() {
            // 页内链接点击：http/https 在卡片内 WebView 自身加载（return false，不跳全屏
            // WebViewActivity，用户可连续点链接在卡片内浏览）；file:// 非本地文件模式外部打开
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return true;
                if (url.startsWith("http://") || url.startsWith("https://")
                        || url.startsWith("/")) {
                    return false; // 卡片内加载
                }
                if (url.startsWith("file://")) {
                    // 本地文件模式：file:// 链接继续在 WebView 内加载；非本地文件模式外部打开
                    if (isFileMode) return false;
                    com.oilquiz.app.ai.chat.component.ComponentActions.openLink(view.getContext(), url);
                    return true;
                }
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                String url = request != null && request.getUrl() != null
                        ? request.getUrl().toString() : null;
                if (url == null) return true;
                if (url.startsWith("http://") || url.startsWith("https://")
                        || url.startsWith("/")) {
                    return false; // 卡片内加载
                }
                if (url.startsWith("file://")) {
                    if (isFileMode) return false;
                    com.oilquiz.app.ai.chat.component.ComponentActions.openLink(view.getContext(), url);
                    return true;
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 图片点击预览（浏览器级体验）：注入 JS 拦截 img 点击 → Android.previewImage
                try {
                    view.evaluateJavascript(
                            "(function(){"
                                    + "function bind(){var imgs=document.getElementsByTagName('img');"
                                    + "for(var i=0;i<imgs.length;i++){(function(img){"
                                    + "img.style.cursor='zoom-in';"
                                    + "img.onclick=function(e){e.preventDefault();e.stopPropagation();"
                                    + "var s=img.getAttribute('src');"
                                    + "if(s&&s.indexOf('data:')!==0){Android.previewImage(s);}};"
                                    + "})(imgs[i]);}}"
                                    + "if(document.readyState==='complete'){bind();}"
                                    + "else{document.addEventListener('DOMContentLoaded',bind);}"
                                    + "})()", null);
                } catch (Throwable ignored) {
                }
                // 延迟取高度：等 JS/CSS 渲染完成；失败保持初始高度（内容可滚动，不算失败）
                view.postDelayed(() -> {
                    if (wvRef == null) return;
                    try {
                        // 返回浮标：可返回上一页时显示
                        try {
                            backBtn.setVisibility(wvRef.canGoBack() ? android.view.View.VISIBLE
                                    : android.view.View.GONE);
                        } catch (Throwable ignored) {
                        }
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
                                            // 高度作用于外层容器（WebView 填满容器）
                                            ViewGroup.LayoutParams lp = webContainer.getLayoutParams();
                                            if (lp != null && lp.height != target) {
                                                lp.height = target;
                                                webContainer.setLayoutParams(lp);
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

        // 完整文档（含 <!DOCTYPE>/<html>）直接加载，否则包裹 viewport + 基础样式
        // （缺 viewport 时 WebView/浏览器按 980px 默认宽度渲染，手机上会显示成"横屏"）
        // url 模式：http(s) 加载网页 / file:// 加载本地文件（web 组件映射，
        // onPageFinished 自适应高度逻辑复用）。其余内容按 HTML 字符串渲染。
        if (!url.isEmpty()
                && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://"))) {
            // 本地文件需开启文件访问（WebView 默认允许 file:// 自身加载，但需允许相对资源）
            if (url.startsWith("file://")) {
                webView.getSettings().setAllowFileAccess(true);
                webView.getSettings().setAllowFileAccessFromFileURLs(true);
                webView.getSettings().setAllowUniversalAccessFromFileURLs(true);
            }
            webView.loadUrl(url);
        } else {
            // style→内容 CSS 桥接：组件 style（或顶层 fontSize/color/background 字段）注入
            // WebView 内容 CSS——html 内容像浏览器那样应用样式（不再只有外层卡片有样式）
            JSONObject contentStyle = new JSONObject();
            JSONObject styleObj = p.optJSONObject("style");
            if (styleObj != null) {
                String[] sk = {"fontSize", "color", "background", "background_color", "bg"};
                for (String s : sk) {
                    if (styleObj.has(s)) {
                        try { contentStyle.put(s, styleObj.get(s)); } catch (Exception ignored) {}
                    }
                }
            }
            String[] topk = {"fontSize", "color"};
            for (String tk : topk) {
                if (!contentStyle.has(tk) && p.has(tk)) {
                    try { contentStyle.put(tk, p.get(tk)); } catch (Exception ignored) {}
                }
            }
            String fullHtml = wrapHtml(html, isNightMode(context), contentStyle);
            // baseURL 指向 android_asset：HTML 内可引用本地 js/css（mermaid/katex 等）
            webView.loadDataWithBaseURL("file:///android_asset/", fullHtml, "text/html", "UTF-8", null);
        }

        card.addView(webContainer);
        Log.i("HtmlCardView", "html component created (WebView), htmlLen=" + html.length()
                + ", title=" + (title == null ? "" : title));
        return card;
    }

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

    /**
     * 异步探测坐标处是否为可交互元素（a/button/input/textarea/select/带 onclick 的元素）。
     * 通过 elementFromPoint 查找并向上遍历祖先，结果写入 interactive[0]。
     * WebView 未缩放时触摸坐标即 CSS 像素坐标；探测失败保持 false（不阻塞点击）。
     */
    private static void probeInteractive(final WebView webView, final int x, final int y,
                                        final boolean[] interactive) {
        try {
            String js = "(function(px,py){"
                    + "var el=document.elementFromPoint(px,py);"
                    + "while(el){"
                    + "var t=(el.tagName||'').toLowerCase();"
                    + "if(t==='a'||t==='button'||t==='input'||t==='textarea'||t==='select'"
                    + "||el.onclick||el.getAttribute&&el.getAttribute('onclick')"
                    + "||el.getAttribute&&el.getAttribute('role')==='button'){return true;}"
                    + "el=el.parentElement;}"
                    + "return false;})(" + x + "," + y + ")";
            webView.evaluateJavascript(js, value -> {
                if (value != null) {
                    interactive[0] = "true".equals(value.trim());
                }
            });
        } catch (Throwable t) {
            // 探测失败不影响 WebView 自身点击
        }
    }

    private static android.graphics.drawable.Drawable cardBackground(Context context) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(ComponentColors.background(context));
        gd.setCornerRadius(dp(context, 10));
        gd.setStroke(dp(context, 1), ComponentColors.border(context));
        return gd;
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
     * 让 Agent 生成的 HTML 组件按钮能真实回调应用（Toast/复制/打开链接），不再"点了没反应"。
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

        @android.webkit.JavascriptInterface
        public void openLink(String url) {
            try {
                if (url == null || url.isEmpty()) return;
                ComponentActions.openLink(context, url);
            } catch (Throwable ignored) {
            }
        }

        /** HTML 内图片点击全屏预览（浏览器级体验） */
        @android.webkit.JavascriptInterface
        public void previewImage(String url) {
            try {
                if (url == null || url.isEmpty()) return;
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    try {
                        // 本地相对路径（file:// 或 /storage）先解析为绝对路径
                        String imageUrl = url;
                        if (!imageUrl.startsWith("http://") && !imageUrl.startsWith("https://")
                                && !imageUrl.startsWith("content://")) {
                            String p = imageUrl.startsWith("file://")
                                    ? android.net.Uri.parse(imageUrl).getPath() : imageUrl;
                            java.io.File f = new java.io.File(p);
                            if (f.isFile()) {
                                imageUrl = f.getAbsolutePath();
                            } else {
                                java.io.File resolved = com.oilquiz.app.ai.agent.online.AgentWorkspace
                                        .getInstance(context).resolveExistingFile(p);
                                if (resolved != null && resolved.isFile()) imageUrl = resolved.getAbsolutePath();
                            }
                        }
                        final String finalUrl = imageUrl;
                        android.app.Dialog dialog = new android.app.Dialog(context);
                        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
                        android.widget.ImageView iv = new android.widget.ImageView(context);
                        iv.setAdjustViewBounds(true);
                        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                        iv.setOnClickListener(v -> dialog.dismiss());
                        dialog.setContentView(iv, new android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                                android.graphics.Color.parseColor("#CC000000")));
                        if (finalUrl.startsWith("http://") || finalUrl.startsWith("https://")) {
                            com.bumptech.glide.Glide.with(context).load(finalUrl).into(iv);
                        } else {
                            iv.setImageURI(android.net.Uri.fromFile(new java.io.File(finalUrl)));
                        }
                        dialog.show();
                    } catch (Throwable ignored) {
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }
}
