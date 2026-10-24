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
import com.oilquiz.app.ai.chat.render.MarkdownLinkRouter;
import com.oilquiz.app.ai.chat.render.MarkdownStreamSplitter;
import com.oilquiz.app.ai.chat.render.MarkdownStreamTailFlag;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息级 Markdown WebView：把 AI/用户正文（markdown，或混排裸 HTML）用 WebView 渲染，
 * 解决 Markwon Span 路径在"正文混 HTML/表格、非标准 markdown"下的错位与逐 token 重排闪烁。
 *
 * 关键设计（针对 WebView 嵌 RecyclerView 的两大痛点）：
 * 1) 自动撑高、无内部滚动上限：注入 ResizeObserver 监听 body 尺寸变化，经
 *    {@code @JavascriptInterface onHeight(cssPx)} 回传，原生按内容高设本容器 LayoutParams，
 *    父布局随之增长（区别于旧 HtmlCardView 的 360dp 截断内滚）。
 * 2) 流式不整页重载：首次 loadDataWithBaseURL 建文档；后续 token 默认仍原地替换
 *    body.innerHTML（避免页面重加载导致闪烁/滚动复位）。{@code markdown_stream_tail=true}
 *    时改走块级尾巴：{@link MarkdownStreamSplitter} 切出“已闭合块 + 开放尾巴”，
 *    只追加新闭合块、替换尾巴节点；未闭合尾巴里的代码/公式等闭合后才高亮。
 * 3) 高度缓存：按内容 hash 缓存最终高度，RecyclerView 复用回绑已定稿消息时直接套用，
 *    不再重新测量 → 消除滚回时的抖动。
 *
 * 接线状态：已由 {@code ChatAdapter} 在 {@code webMarkdownEnabled} 时接管 AI 正文气泡
 * （与 messageText 二选一）。链接/图片点击统一走 {@link MarkdownLinkRouter}，与原生
 * Markwon 气泡同一套行为，不再各说各话。块级流式默认开（见 {@link MarkdownStreamTailFlag}），
 * 显式写 false 回退整篇路径；定稿/回退/主题重载仍走整篇路径。
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
    /**
     * 思考正文样式（2026-10-25）：true 时页面走 {@code qz-reasoning} 次级排版
     * （13px / --muted / 左侧竖线缩进），与回答正文 15px 主排版区分开。
     * 只在首次 {@link #setContent} 前设置（思考正文视图创建时就定死），不参与运行时切换。
     */
    private boolean reasoningStyle;
    private boolean pageReady;
    /**
     * 文档代号。每次真正发起整篇加载都 +1，用于回答两个布尔值答不出的问题：
     * <b>现在页面上的是哪一份文档、以及哪一份的加载已经完成</b>。
     *
     * <p>为什么必须有它（2026-10-19 修"进页面主气泡空白、有时永远出不来"）：
     * 原先只有一个 {@code pageReady} 布尔。绑定顺序是 setNight → setThemeColors → setContent，
     * 前两步在主题/变量变化时会用 {@code pendingHtml} 再发一次整篇加载，setContent 又发一次；
     * 而 RecyclerView 高频重绑（流式 token、状态 payload）时 {@code pageReady} 还是 false，
     * 于是<b>每一绑都 loadDataWithBaseURL 一次</b>，后一次取消前一次。
     * 被取消的那次可能仍回调 onPageFinished（把 ready 提前点亮，指向一份已成过去的文档），
     * 也可能一次回调都没有（后续绑定又立刻把在途加载取消掉）——
     * 前者让 {@code swapBody} 打在错页面上、后者是活锁，两种都表现为**气泡永久空白**，
     * 直到滚出屏幕触发 {@link #resetForReuse()} 才自愈。所以"有时过会儿出现、有时根本不出现"。
     */
    private int docGeneration;
    /** 最近一次发出的整篇加载的代号；onPageFinished 完成时把它记到 {@link #readyGeneration} */
    private int loadingGeneration;
    /** 已完成加载并渲染好的文档代号；-1 表示没有 */
    private int readyGeneration = -1;
    /** 已发起加载的那份文档的 hash：同一份文档在途时不再重复发加载 */
    private String issuedDocHash;
    /** 上一次请求内容的 hash，用于换内容时重置补发额度 */
    private String lastIssuedContentHash;
    /** 同一份文档最多补发一次加载（看门狗），避免"永远等不到 onPageFinished" */
    private boolean retryIssued;
    private boolean lastStreaming;
    private int lastAppliedPx = -1;
    private String currentContentHash;
    private String themeVars = "";
    private final int maxHeightPx;
    private OnContentHeightChanged heightListener;

    /** 轮内块级状态；null=下一 tick 从头重建。只在 UI 线程读写。 */
    private MarkdownStreamSplitter.Split lastStreamSplit;

    /** 每次发给 JS 的块更新序号：迟到的失败回调不能把更新的状态清掉。 */
    private int streamUpdateSeq;

    /**
     * 块 HTML 缓存：key 是 {@link MarkdownStreamSplitter.Block#key}（纯 HTML 哈希），
     * value 是本地图片/链接改写后的 HTML。换绑/回收时清空，避免工作区解析结果串台。
     */
    private final Map<String, String> blockHtmlCache =
            new LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 128;
                }
            };

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
        // 图片渲染修复（2026-10-13）：本项目 targetSdk=36 / minSdk=31，Android 11+ 起
        // WebView 的 allowFileAccess / allowContentAccess **默认 false**，混合内容默认 NEVER_ALLOW，
        // 而本页用 loadDataWithBaseURL("file:///android_asset/") 加载（file:// 属安全上下文）。
        // 后果：Agent 生成的本地截图（file:///绝对路径）、content:// 图、http:// 图
        // 在页内**必然裂图**，而同样的 markdown 走旧的 Markwon+Glide 路径却能显示
        // —— 这就是"切到 WebView 渲染后图片出错"的根因。
        // 安全性：本 WebView 只加载本地构造的 HTML（loadDataWithBaseURL），
        // 从不加载远程页面（正文里的链接走系统浏览器，见 e62feb6a），
        // 因此放开 file/content 访问不会给远程页读到本地文件的机会。
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadsImagesAutomatically(true);
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
                return handleNavigation(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 认"最后一次发出的加载"：被后续加载取消掉的旧加载若也回调，
                // 不能让它把就绪状态安在一份已成过去的文档上（那会让 swapBody 打空）。
                readyGeneration = loadingGeneration;
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

    /**
     * 标记本视图渲染的是思考正文（次级排版：13px / muted / 左侧竖线缩进）。
     *
     * <p>必须在第一次 {@link #setContent} 之前调用；样式烤进整篇文档的 {@code <body>} 类与
     * CSS 里，之后只随内容增量更新，不再参与运行时切换。</p>
     */
    public void setReasoningStyle(boolean reasoningStyle) {
        this.reasoningStyle = reasoningStyle;
    }

    /**
     * 气泡内导航的统一出口：除页内锚点（{@code #}）外一律不在气泡内导航，
     * 交 {@link MarkdownLinkRouter} 按 scheme 分流——应用内预览文件/图片、
     * 系统浏览器打开网页、{@code tel:}/{@code mailto:}/{@code geo:} 等交系统能力。
     *
     * <p>这里曾是"只认 http/https，其余 {@code return true} 静默吞掉"，于是主气泡里的
     * 文件链接、content:// 附件、工作区相对路径点了全无反应（原生 Markwon 路径本来有）。
     * 现在两条渲染路径共用同一个路由器，不会再分叉。</p>
     */
    private boolean handleNavigation(String url) {
        if (url == null || url.trim().isEmpty()) return true;
        String s = url.trim();
        if (s.startsWith("#")) return false; // 锚点：让 WebView 自己滚动
        routeLink(s);
        return true;
    }

    /** 把链接（含 android_asset 相对补全形态）交给路由器，主线程执行 */
    private void routeLink(String raw) {
        final String url = MarkdownLinkRouter.normalizeAssetHref(raw);
        if (url == null || url.isEmpty()) return;
        main.post(() -> {
            try {
                MarkdownLinkRouter.open(getContext(), url);
            } catch (Throwable ignore) { }
        });
    }

    public void setNight(boolean night) {
        if (this.night == night) return;
        this.night = night;
        // 主题变化需整篇重载（CSS 在 head）
        reloadForStyleChange();
    }

    /**
     * 设置主题 CSS 变量覆盖（对齐应用 ThemeColors token），如
     * "--fg:#111;--link:#2563eb;--muted:#666"。变化会触发整篇重载以套用新色。
     */
    public void setThemeColors(String cssVars) {
        String v = cssVars == null ? "" : cssVars;
        if (v.equals(this.themeVars)) return;
        this.themeVars = v;
        reloadForStyleChange();
    }

    /**
     * 主题/夜间变化要整篇重载，但<b>只重载"这份文档已经发出去过"的情况</b>：
     * holder 复用后 {@code pendingHtml} 可能还是上一条消息的文档，
     * 那时加载它等于凭空多插一次 loadDataWithBaseURL，会和随后 setContent 的加载互相取消。
     * {@link #resetForReuse()} 会把 pendingHtml 清空，正是为了让这条路径在复用后第一次绑定时不触发。
     */
    private void reloadForStyleChange() {
        pageReady = false;
        readyGeneration = -1;
        if (pendingHtml != null) {
            // 同一份文档换主题：允许重新发一次
            issuedDocHash = null;
            retryIssued = false;
            loadDocument(pendingHtml);
        }
    }

    private String pendingHtml;

    /**
     * 设置/更新正文内容。markdown 或混排 HTML 均可。streaming=true 时优先原地更新；
     * {@code markdown_stream_tail=true} 且页面就绪时走块级尾巴（只换未闭合块），否则
     * 仍整 body 替换；定稿/回退/主题重载保持整篇路径。
     */
    @SuppressLint("SetJavaScriptEnabled")
    public void setContent(String content, boolean streaming) {
        final String md = content == null ? "" : content;
        // hash 带样式前缀：思考正文（13px 次级排版）与回答正文（15px）即使内容相同，
        // 高度缓存也不能串用
        String hash = (reasoningStyle ? "r:" : "b:") + hashOf(content);
        // 内容一换，之前排队写缓存的动作作废（防止旧页迟到的测量写到新 hash 下）
        cancelPendingCacheWrite();
        currentContentHash = hash;
        boolean wasStreaming = lastStreaming;
        lastStreaming = streaming;
        // 内容变了，之前"这份文档正在加载"的判断与补发额度都要作废重来
        if (!hash.equals(lastIssuedContentHash)) {
            lastIssuedContentHash = hash;
            retryIssued = false;
        }
        // 定稿内容命中缓存 → 先套用缓存高，减少重测抖动
        Integer cached = HEIGHT_CACHE.get(hash);
        if (cached != null && lastAppliedPx < 0) {
            applyHeightPx((int) (cached * density));
        } else if (cached == null && lastAppliedPx < 0) {
            // 无缓存且未测量 → 先给占位高，避免 WebView 首帧塌陷为 0 高（随后 JS 实测再撑开）
            applyHeightPx(placeholderPx);
        }
        String bodyHtml = MarkdownHtmlConverter.toBodyHtml(md);
        // 正文里的相对/裸文件名图片 → file:// 绝对地址（WebView baseUrl 是 android_asset，
        // 不改写必然裂图；JS 侧拿不到工作区路径，只能在这里做）
        bodyHtml = MarkdownHtmlConverter.resolveLocalImageSrcs(bodyHtml, getContext());
        // 锚点同理：工作区相对路径的链接在这里解析成绝对 file://，
        // 解析不到的保留原串，交给点链接时的 MarkdownLinkRouter 给可读提示
        bodyHtml = MarkdownHtmlConverter.resolveLocalLinkHrefs(bodyHtml, getContext());
        String doc = MarkdownHtmlConverter.wrapDocument(bodyHtml, night, themeVars, reasoningStyle);
        pendingHtml = doc;
        boolean documentReady = pageReady && docGeneration > 0 && readyGeneration == docGeneration;
        // 表格在流式页面里按旧的 body/宽度算过列宽；完成时 swapBody 不一定重算表格布局，
        // 表现为“表格列宽挤在一起，退出重进才正常”。流式→定稿且含表格时整篇重载一次，
        // 让浏览器按最终气泡宽度重新排版。只发生一次，不会逐 token 重载。
        boolean finalTableNeedsReload = documentReady && wasStreaming && !streaming
                && bodyHtml.contains("<table");
        // 轮内流式（P7）：只在页面就绪、当前是流式、开关打开且切分器允许时走块级路径。
        // 定稿 / 表格定稿重载 / 切分器回退 / 主题重载都继续走整篇路径（逐字不变）。
        if (documentReady && !finalTableNeedsReload && streaming
                && MarkdownStreamTailFlag.enabled(getContext())) {
            MarkdownStreamSplitter.Split split = MarkdownStreamSplitter.split(md);
            if (split.streamable) {
                if (applyStreamingBlocks(split)) {
                    return;
                }
                // JSON/JS 发送失败：清掉块状态，下面用整篇 body 兜底，至少不会停更
                lastStreamSplit = null;
            } else {
                // 整篇 HTML / 公式跨块等要求回退：清掉块状态，下面走整篇 swap
                lastStreamSplit = null;
            }
        }
        if (documentReady && !finalTableNeedsReload) {
            // 页面就是这份文档且已就绪：原地换 body，不重载（流式逐 token 也走这里，避免闪烁）
            lastStreamSplit = null;
            swapBody(bodyHtml);
            return;
        }
        String docHash = hashOf(doc);
        if (docHash.equals(issuedDocHash)) {
            // 这份文档的整篇加载还在途：再发一次只会把上一次取消掉（真机上就是"反复空白 /
            // 有时永远出不来"的成因）。等它的 onPageFinished；超过阈值仍未回来则补发一次兜底。
            if (!retryIssued) {
                retryIssued = true;
                main.postDelayed(this::retryLoadIfNeeded, LOAD_SETTLE_MS);
            }
            return;
        }
        loadDocument(doc);
    }

    /**
     * 轮内流式块路径：首帧/回退后整体重建，否则只追加新闭合块并替换尾巴。
     *
     * <p>JS 侧 `__qzStream` 返回 false（例如 basePrefix 对不上）时，下一 tick 会走 reset；
     * 这比“猜哪一块变了”安全。prefixHash 与逐块 key 同源（都由块 HTML 派生）。</p>
     */
    private boolean applyStreamingBlocks(MarkdownStreamSplitter.Split split) {
        if (lastStreamSplit == null) {
            if (!sendStreamUpdate(true, split, split.closed, "")) {
                lastStreamSplit = null;
                return false;
            }
        } else {
            MarkdownStreamSplitter.Update update =
                    MarkdownStreamSplitter.diff(lastStreamSplit, split);
            if (update.reset) {
                if (!sendStreamUpdate(true, split, split.closed, "")) {
                    lastStreamSplit = null;
                    return false;
                }
            } else {
                List<MarkdownStreamSplitter.Block> appended =
                        update.closed.subList(update.fromIndex, update.closed.size());
                if (!appended.isEmpty()
                        || !sameTail(lastStreamSplit.tail, update.tail)) {
                    if (!sendStreamUpdate(false, split, appended,
                            lastStreamSplit.prefixHash)) {
                        lastStreamSplit = null;
                        return false;
                    }
                }
            }
        }
        lastStreamSplit = split;
        return true;
    }

    private static boolean sameTail(MarkdownStreamSplitter.Block a,
                                    MarkdownStreamSplitter.Block b) {
        if (a == null) return b == null;
        return b != null && a.key.equals(b.key);
    }

    /**
     * 把块 HTML 包成 base64 JSON 发给 JS；不新增 JavascriptInterface。
     *
     * @param reset true=整体重建（blocks 为全部闭块）；false=只追加 blocks + 换尾巴
     * @param base  仅 apply 用：上一次的 prefixHash，JS 用它确认前缀没变
     */
    private boolean sendStreamUpdate(boolean reset, MarkdownStreamSplitter.Split split,
                                     List<MarkdownStreamSplitter.Block> blocks,
                                     String base) {
        try {
            final int seq = ++streamUpdateSeq;
            JSONObject payload = new JSONObject();
            payload.put("mode", reset ? "reset" : "apply");
            payload.put("base", reset ? "" : base);
            payload.put("prefix", split.prefixHash);
            JSONArray html = new JSONArray();
            for (MarkdownStreamSplitter.Block block : blocks) {
                html.put(resolvedBlockHtml(block));
            }
            payload.put("blocks", html);
            payload.put("tail", split.tail == null ? "" : resolvedBlockHtml(split.tail));
            String b64 = Base64.encodeToString(
                    payload.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP);
            String js = "(function(){try{var p=JSON.parse(decodeURIComponent(escape(atob('"
                    + b64 + "'))));return window.__qzStream?window.__qzStream(p):false;}"
                    + "catch(e){return false;}})();";
            webView.evaluateJavascript(js, value -> {
                // apply 的 basePrefix 对不上 / JS 里 __qzStream 缺失：下一 tick 整体重建。
                // 只回滚“这次发出去的那份”状态，迟到的旧回调不能清掉更新的状态。
                if (("false".equals(value) || "null".equals(value))
                        && seq == streamUpdateSeq) {
                    lastStreamSplit = null;
                }
            });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 块 HTML 的本地图片/链接改写；按纯 HTML key 缓存，避免每 tick 重做正则。 */
    private String resolvedBlockHtml(MarkdownStreamSplitter.Block block) {
        String cached = blockHtmlCache.get(block.key);
        if (cached != null) return cached;
        String html = MarkdownHtmlConverter.resolveLocalImageSrcs(block.html, getContext());
        html = MarkdownHtmlConverter.resolveLocalLinkHrefs(html, getContext());
        if (html == null) html = "";
        blockHtmlCache.put(block.key, html);
        return html;
    }

    /** 看门狗：在途加载超时未就绪时补发一次（每份文档最多一次），保证不会永久空白。 */
    private void retryLoadIfNeeded() {
        if (pageReady && readyGeneration == docGeneration && docGeneration > 0) return;
        if (pendingHtml == null) return;
        issuedDocHash = null;
        loadDocument(pendingHtml);
    }

    private static final long LOAD_SETTLE_MS = 1200L;

    private void loadDocument(String fullHtml) {
        streamUpdateSeq++;
        lastStreamSplit = null;
        docGeneration++;
        loadingGeneration = docGeneration;
        readyGeneration = -1;
        pageReady = false;
        issuedDocHash = hashOf(fullHtml);
        // 每次真正发出加载都重新获得一次看门狗额度：保证"在途被取消"最坏情况只多等一帧
        retryIssued = false;
        webView.loadDataWithBaseURL("file:///android_asset/", fullHtml, "text/html", "UTF-8", null);
    }

    /** 原地替换 body：base64 传 HTML 规避引号/转义问题；ResizeObserver 会自动重报高度。 */
    private void swapBody(String bodyHtml) {
        streamUpdateSeq++;
        lastStreamSplit = null;
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
            // 高度缓存只在"内容已定稿且这一帧测量确实属于当前内容"时写入（2026-10-13 修串台）：
            // 原先在回调里直接 put，而 holder 复用后 currentContentHash 已被换成新消息的 hash，
            // 上一条消息迟到的测量就会把**旧高度缓存到新 hash 下** → 该消息以后每次滚回来都错高。
            // 这里改为延迟写：期间任何新绑定/换内容都会取消它（见 cancelPendingCacheWrite），
            // 且执行时二次校验 hash 未变，只缓存"稳定下来"的高度。
            if (!lastStreaming) {
                scheduleCacheWrite(cssPx);
            }
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

        // 桥方法刻意只有 onHeight/copy/previewImage 三个（2026-10-13 定下的"不扩桥"）。
        // 链接接 App 与系统这件事不需要新桥：真实点击一定会触发 WebViewClient 的
        // shouldOverrideUrlLoading，由那里交给 MarkdownLinkRouter；再开一个
        // QZ.openLink 等于让正文里的脚本可以不经点击就拉起文件预览/系统 Intent。
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
        cancelPendingCacheWrite();
        lastAppliedPx = -1;
        pageReady = false;
        currentContentHash = null;
        lastStreamSplit = null;
        streamUpdateSeq++;
        blockHtmlCache.clear();
        // 文档身份一并清零：复用到新消息时必须重新整篇加载，
        // 且不能让 setNight/setThemeColors 用上一条消息的 pendingHtml 去插一次加载。
        pendingHtml = null;
        issuedDocHash = null;
        retryIssued = false;
        readyGeneration = -1;
        loadingGeneration = 0;
        docGeneration = 0;
    }

    /** 延迟写高度缓存的等待时长：期间内容再变（新 token / 换绑）就作废这次写入 */
    private static final long CACHE_WRITE_DEBOUNCE_MS = 160L;
    private Runnable pendingCacheWrite;

    /**
     * 延迟写高度缓存：只有"测量落地后内容不再变化"的稳定高度才进 {@link #HEIGHT_CACHE}。
     * 执行时二次校验 hash 仍是当时那个 hash，双保险避免 holder 复用串台。
     */
    private void scheduleCacheWrite(final int cssPx) {
        final String hash = currentContentHash;
        if (hash == null) return;
        cancelPendingCacheWrite();
        pendingCacheWrite = () -> {
            pendingCacheWrite = null;
            if (hash.equals(currentContentHash)) {
                HEIGHT_CACHE.put(hash, cssPx);
            }
        };
        main.postDelayed(pendingCacheWrite, CACHE_WRITE_DEBOUNCE_MS);
    }

    private void cancelPendingCacheWrite() {
        if (pendingCacheWrite != null) {
            main.removeCallbacks(pendingCacheWrite);
            pendingCacheWrite = null;
        }
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
