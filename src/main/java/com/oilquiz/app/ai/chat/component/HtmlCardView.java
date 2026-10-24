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

    /**
     * {@code HtmlJsBridge.submit} 的 payload 上限（字符）。
     * 提交的是"用户操作"而不是数据本体，超限基本是页面在往结果槽里灌大对象；
     * 与 {@code HtmlSandbox.MAX_INLINE_HTML_CHARS} 同思路，用硬上限守住通道。
     */
    static final int MAX_SUBMIT_CHARS = 16 * 1024;

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
        // 放大档（全屏宿主 {@link ComponentFocusHost}）：那个键只存在于宿主自己的 props 副本上，
        // 气泡里那份永远不会有。为真时整卡按视口高一次定高，完全不进内容量高梯子——
        // 梯子是为气泡的"自适应"服务的，在全屏里只会碍事（改高度＝重测＝打断输入）。
        final boolean fillHeight = p.optBoolean(ComponentFocusHost.PROP_FILL_HEIGHT, false);

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
            // 回传地址由 native 绑定（JS 不能指定）；开关关或非交互卡 → null，submit 静默 no-op，
            // showToast/copy/openLink/previewImage 四个既有方法不受影响。
            String roundTripId = HtmlSubmitFlag.enabled(context)
                    ? ComponentActions.resolveComponentId(data) : null;
            webView.setBridge(new HtmlJsBridge(context, roundTripId), HtmlWebView.BRIDGE_NAME);
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

        final int maxHeightPx = fillHeight ? fillViewportHeightPx(context) : dp(context, maxHeightDp);
        // WebView 外包 FrameLayout：承载"← 返回"浮标（canGoBack 时显示，卡片内可返回上一页）
        final android.widget.FrameLayout webContainer = new android.widget.FrameLayout(context);
        final LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                fillHeight ? maxHeightPx : dp(context, FALLBACK_HEIGHT_DP));
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

        // 「放大」浮标：右下角（右上角是"←"返回，错开免得互相盖）。
        // 刻意不占 CardShell 的 footer 动作位——那里 MAX_INLINE_ACTIONS=2 的预算是留给
        // Agent 自己声明的 actions（提交/回传按钮）的，挤进去会把业务按钮推到「更多」里。
        if (!fillHeight && ComponentFocusHost.canOpen(context)) {
            final ComponentData focusData = data;
            android.widget.ImageView expandBtn = new android.widget.ImageView(context);
            expandBtn.setImageResource(R.drawable.ic_card_fullscreen);
            expandBtn.setContentDescription(context.getString(R.string.card_html_expand_desc));
            expandBtn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            expandBtn.setPadding(dp(context, 5), dp(context, 5), dp(context, 5), dp(context, 5));
            expandBtn.setBackground(roundedRect(context, dp(context, 18),
                    androidx.core.content.ContextCompat.getColor(context, R.color.card_scrim)));
            expandBtn.setOnClickListener(v -> ComponentFocusHost.open(context, focusData));
            final android.widget.FrameLayout.LayoutParams expandLp =
                    new android.widget.FrameLayout.LayoutParams(
                            dp(context, 30), dp(context, 30));
            expandLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
            expandLp.bottomMargin = dp(context, 8);
            expandLp.rightMargin = dp(context, 8);
            webContainer.addView(expandBtn, expandLp);
        }

        final boolean bridgeEnabled = HtmlSandbox.allowsBridge(trust);
        final SandboxWebViewClient sandboxClient = new SandboxWebViewClient(context, trust,
                webView, webContainer, backBtn, maxHeightPx, scriptEnabled, bridgeEnabled,
                fillHeight);
        webView.setWebViewClient(sandboxClient);
        // 页面进度到 100% 时再量一次：图片/字体/CSS 常在 onPageFinished 之后才把内容撑高，
        // 只按"加载完成"量一次会把长报告永久压在初始高度里（真机症状：卡片只有一条窗口）
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) {
                    sandboxClient.scheduleMeasure();
                }
            }
        });

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
        // 放大档例外：它不参与量高，所以这里直接给最终高度（视口高），一次到位。
        shell.getBody().addView(webContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                fillHeight ? maxHeightPx : dp(context, FALLBACK_HEIGHT_DP)));
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
        /** 放大档：整卡按视口高定死，不参与内容量高梯子（改高度会打断页面里的输入） */
        private final boolean fillHeight;

        SandboxWebViewClient(Context context, HtmlSandbox.Trust trust, HtmlWebView webView,
                            android.widget.FrameLayout webContainer,
                            android.widget.TextView backBtn, int maxHeightPx,
                            boolean scriptEnabled, boolean bridgeEnabled, boolean fillHeight) {
            this.context = context;
            this.trust = trust;
            this.webView = webView;
            this.webContainer = webContainer;
            this.backBtn = backBtn;
            this.maxHeightPx = maxHeightPx;
            this.scriptEnabled = scriptEnabled;
            this.bridgeEnabled = bridgeEnabled;
            this.fillHeight = fillHeight;
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
            scheduleMeasure();
        }

        /**
         * 量高时刻表（2026-10-24）。
         *
         * <p>此前只在 {@code onPageFinished} 后 150ms 量一次：内联档（JS 关闭）靠已废弃的
         * {@code getContentHeight()}，那一刻常常还是 0，于是容器永久停在初始 160dp，
         * 长报告只剩一条窗口；带图片/CSS 延后布局的页面同理。这里按几个时间点各量一次，
         * {@link #applyMeasuredHeight} 只增不减，所以后量到的更高值才会改写容器高度。</p>
         */
        private static final long[] MEASURE_DELAYS_MS = {120L, 450L, 1000L, 2000L};

        /** 进度回调会连发，用它给重复排期做一次节流 */
        private long lastScheduleAtMs;

        /** 已应用的内容高度（只增不减，避免每次量到更小值就来回抖） */
        private int appliedHeightPx;

        /** 排一次多时刻量高（页面加载完成、以及进度到 100% 时各来一轮） */
        void scheduleMeasure() {
            if (webView.isComponentReleased()) return;
            if (fillHeight) return;   // 放大档高度已按视口定死，量高只会打断输入
            long now = System.currentTimeMillis();
            if (now - lastScheduleAtMs < 250L) return;
            lastScheduleAtMs = now;
            for (long delay : MEASURE_DELAYS_MS) {
                webView.postDelayed(this::measureContentHeight, delay);
            }
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
                if (target <= appliedHeightPx) return;
                // 页面里有输入框正在编辑时**绝不**改容器高度：改高度会顺着
                // requestLayout 传到 WebView，Android 在重测时把内部编辑焦点弹掉
                // （真机实测：focus 拿到后同一秒 blur、keys:0，而点击/滑块类一次完成的
                // 交互不受影响——正是这条量高梯子的 1000ms / 2000ms 那两拍）。
                // 所以推迟到焦点释放后再量；超过重试上限就直接放弃这次高度更新，
                // 宁可卡片停在当前高度（内容超出可内部滚动），也不抢走用户正在打的字。
                if (hasFocusedInputInPage()) {
                    if (deferredMeasureRetries < MAX_DEFERRED_MEASURES) {
                        deferredMeasureRetries++;
                        webView.postDelayed(this::measureContentHeight, DEFERRED_MEASURE_MS);
                    }
                    return;
                }
                deferredMeasureRetries = 0;
                // 多轮量高：只接受更高的值，量到更小值（图片还没下来）不改回去，
                // 否则容器会在几轮回调之间来回抖
                appliedHeightPx = target;
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

        /** 量高推迟的重试节奏与上限：约 5 秒内还在编辑就放弃这次高度更新。 */
        private static final long DEFERRED_MEASURE_MS = 800L;
        private static final int MAX_DEFERRED_MEASURES = 6;
        private int deferredMeasureRetries;

        /**
         * WebView 子树里是否还有焦点（页面内 {@code <input>} 正在编辑时，焦点在 WebView
         * 内部的编辑视图上，{@code findFocus()} 非空；只有 WebView 自己拿到焦点时
         * {@code hasFocus()} 为真）。
         */
        private boolean hasFocusedInputInPage() {
            try {
                return webView.hasFocus() || webView.findFocus() != null;
            } catch (Throwable t) {
                return false;
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

    /** 放大档的一次定高：视口高减掉窗口顶部"关闭"栏与卡片自身 chrome（约 150dp）。 */
    private static int fillViewportHeightPx(Context context) {
        int heightPx = context.getResources().getDisplayMetrics().heightPixels;
        int chromePx = dp(context, 150);
        int min = dp(context, 320);
        return Math.max(min, heightPx - chromePx);
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
        /**
         * 本卡回传地址（{@code props.actions[].component_id}，由 native 在构造时绑定）。
         * null/空＝这张卡不是交互组件（创建时没带 actions），或 {@link HtmlSubmitFlag} 关着 → {@link #submit} no-op。
         * <b>故意不做成 JS 可传的参数</b>：否则任意页面都能往别的组件结果槽里写。
         */
        private final String componentId;

        public HtmlJsBridge(Context context) {
            this(context, null);
        }

        public HtmlJsBridge(Context context, String componentId) {
            this.context = context;
            this.componentId = componentId;
        }

        /**
         * 页面内提交用户操作 → 走既有唯一回传通道 {@link ComponentActions#dispatchResult}
         * → 按 id 的回调 / 全局兜底 → {@code get_result} 取回。<b>不另造第二套通道。</b>
         *
         * <p>口径（html submit 设计 §3.2）：payload 当<b>不透明字符串</b>——不解析、不执行、不进 openLink；
         * 上限 {@link #MAX_SUBMIT_CHARS} 字符，超限拒收（防超大内容刷爆结果槽）。形状沿用
         * {@code ComponentActions.perform} 的既有约定：纯值，或 {@code {"action":..., "inputs":{...}}}。</p>
         *
         * <p>安全边界：只有 {@code HtmlSandbox.allowsBridge} 为真的档位（{@code LOCAL_FILE}，
         * 即 Agent 产在工作区内的本地报表 HTML）才挂桥；模型直给的 HTML 字符串档（{@code INLINE_UNTRUSTED}）
         * 连 JS 都是关的，拿不到本方法。</p>
         */
        @android.webkit.JavascriptInterface
        public void submit(String payload) {
            try {
                if (componentId == null || componentId.isEmpty()) {
                    return; // 非交互卡 / 开关关：静默忽略
                }
                if (payload == null || payload.isEmpty()) {
                    return;
                }
                if (payload.length() > MAX_SUBMIT_CHARS) {
                    Log.w(TAG, "bridge submit payload too long: " + payload.length());
                    showToast("提交内容过长，请缩减后重试");
                    return;
                }
                ComponentActions.dispatchResult(context, componentId, payload);
                showToast("已提交");
            } catch (Throwable t) {
                Log.w(TAG, "bridge submit failed: " + t.getMessage());
            }
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
