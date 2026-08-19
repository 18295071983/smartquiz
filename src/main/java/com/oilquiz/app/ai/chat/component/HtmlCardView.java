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
        String html = p.optString("html", "");
        if (html.isEmpty()) html = p.optString("content", "");
        if (html.isEmpty()) html = p.optString("text", "");
        // url 字段：直接加载网页（web 组件映射用），优先于 html 字符串
        String url = p.optString("url", "");
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

        if (html.trim().isEmpty()) {
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
        // JS → Java 桥：HTML 组件内按钮可调用 Android.showToast/copy/openLink 回调应用
        webView.addJavascriptInterface(new HtmlJsBridge(context), "Android");

        // 组件可操作：阻止外层 RecyclerView 拦截触摸，WebView 内可滚动/点击
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setClickable(true);
        final String htmlFinal = html;
        final String urlFinal = url;
        final String titleFinal = title;
        final long[] downTime = {0};
        final float[] downPos = {0, 0};
        final boolean[] interactiveDown = {false};
        final int touchSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
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
                    downTime[0] = event.getEventTime();
                    downPos[0] = event.getX();
                    downPos[1] = event.getY();
                    interactiveDown[0] = false;
                    // 异步探测按下位置是否为可点击元素（a/button/onclick/表单控件）
                    probeInteractive(webView, (int) event.getX(), (int) event.getY(), interactiveDown);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                    float dx = event.getX() - downPos[0];
                    float dy = event.getY() - downPos[1];
                    long dt = event.getEventTime() - downTime[0];
                    // 轻点（位移小、时间短）：
                    // 命中可交互元素（链接/按钮/onclick）→ 放行给 WebView 执行页内 JS，不跳全屏；
                    // 其余区域 → 打开全屏页完整查看/交互
                    if (Math.abs(dx) < touchSlop && Math.abs(dy) < touchSlop && dt < 500) {
                        if (interactiveDown[0]) {
                            return false; // 让 WebView 处理点击（执行 onclick / 打开链接）
                        }
                        if (!urlFinal.isEmpty()) {
                            // 本地文件模式：点空白 → 全屏加载原文件完整查看；http(s) → 应用内打开
                            if (urlFinal.startsWith("file://")) {
                                openLocalFileFullScreen(context, urlFinal, titleFinal);
                            } else {
                                com.oilquiz.app.ai.chat.component.ComponentActions.openLink(context, urlFinal);
                            }
                        } else {
                            openFullScreen(context, htmlFinal, titleFinal);
                        }
                    }
                    break;
                default:
                    break;
            }
            return false; // 不消费事件，由 WebView 自身处理滚动
        });

        // 初始高度：内容自适应前先用 160dp 占位，onPageFinished 后按内容高度调整
        final int maxHeightPx = dp(context, maxHeightDp);
        final LinearLayout.LayoutParams wvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 160));
        webView.setLayoutParams(wvLp);

        final WebView wvRef = webView;
        // 本地文件模式：file:// 导航在 WebView 内部加载（不拦截系统打开），否则外部打开
        final boolean isFileMode = url.startsWith("file://");
        webView.setWebViewClient(new WebViewClient() {
            // 页内链接点击：真实打开（http/https 应用内 WebView；本地文件模式 file:// 内部导航）
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return true;
                if (url.startsWith("http://") || url.startsWith("https://")
                        || url.startsWith("/")) {
                    com.oilquiz.app.ai.chat.component.ComponentActions.openLink(view.getContext(), url);
                    return true;
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
                    com.oilquiz.app.ai.chat.component.ComponentActions.openLink(view.getContext(), url);
                    return true;
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
                // 延迟取高度：等 JS/CSS 渲染完成；失败保持初始高度（内容可滚动，不算失败）
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
            String fullHtml = wrapHtml(html);
            webView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null);
        }

        card.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 160)));
        Log.i("HtmlCardView", "html component created (WebView), htmlLen=" + html.length()
                + ", title=" + (title == null ? "" : title));
        return card;
    }

    /**
     * 包裹完整 HTML：非完整文档时补 viewport（手机竖屏适配）+ 基础样式。
     * 预览与全屏页（临时文件）共用，保证两处渲染一致。
     */
    private static String wrapHtml(String html) {
        if (html == null) return "";
        String lower = html.toLowerCase();
        if (lower.contains("<!doctype") || lower.contains("<html")) {
            return html;
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>"
                + "<style>html,body{margin:0;padding:0;background:transparent;}"
                + "body{font-family:sans-serif;font-size:14px;line-height:1.5;"
                + "color:#333333;word-break:break-word;padding:2px;}"
                + "img{max-width:100%;height:auto;}table{border-collapse:collapse;width:100%;}"
                + "td,th{border:1px solid #cccccc;padding:4px 6px;font-size:13px;}"
                + "pre{background:#f5f5f5;padding:8px;border-radius:6px;overflow-x:auto;}"
                + "code{background:#f0f0f0;padding:1px 4px;border-radius:4px;font-size:13px;}"
                + "</style></head><body>" + html + "</body></html>";
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

    /**
     * 点击组件 → 打开全屏页完整查看/交互：
     * HTML 写入临时文件，交给 SimpleWebViewActivity（受控 WebView：JS 启用、
     * 软件渲染防 GPU 截断、链接处理、标题栏返回；规避 WebViewActivity 的
     * X5/文件重定向/硬件加速链路对 file:// 页面 JS 交互的干扰）。
     */
    /** 本地文件全屏查看：直接加载原文件路径到 SimpleWebViewActivity（保留相对资源解析） */
    private static void openLocalFileFullScreen(Context context, String fileUrl, String title) {
        try {
            String path = fileUrl;
            if (path.startsWith("file://")) {
                path = android.net.Uri.parse(path).getPath();
            }
            if (path == null || path.isEmpty() || !new java.io.File(path).exists()) {
                Log.w("HtmlCardView", "本地文件不存在: " + fileUrl);
                return;
            }
            android.content.Intent intent = new android.content.Intent(context,
                    com.oilquiz.app.SimpleWebViewActivity.class);
            intent.putExtra("html_path", path);
            if (title != null && !title.isEmpty()) {
                intent.putExtra("title", title);
            }
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            Log.i("HtmlCardView", "opened local file full screen: " + path);
        } catch (Exception e) {
            Log.w("HtmlCardView", "打开本地文件全屏失败: " + e.getMessage());
        }
    }

    private static void openFullScreen(Context context, String html, String title) {        try {
            if (html == null || html.isEmpty()) return;
            java.io.File dir = new java.io.File(context.getCacheDir(), "html_preview");
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w("HtmlCardView", "无法创建预览目录");
                return;
            }
            java.io.File f = new java.io.File(dir,
                    "preview_" + System.currentTimeMillis() + ".html");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            try {
                // 与预览一致的包裹逻辑：补 viewport，全屏页不再"横屏"
                fos.write(wrapHtml(html).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                fos.close();
            }
            android.content.Intent intent = new android.content.Intent(context,
                    com.oilquiz.app.SimpleWebViewActivity.class);
            intent.putExtra("html_path", f.getAbsolutePath());
            if (title != null && !title.isEmpty()) {
                intent.putExtra("title", title);
            }
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            Log.i("HtmlCardView", "opened SimpleWebViewActivity: " + f.getAbsolutePath());
        } catch (Exception e) {
            Log.w("HtmlCardView", "打开全屏失败: " + e.getMessage());
        }
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
    }
}
