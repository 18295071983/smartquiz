package com.oilquiz.app.ai.chat.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.oilquiz.app.ai.chat.component.ComponentLifecycle;
import com.oilquiz.app.ai.chat.parts.AgentPanelBudget;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 执行活动区的 WebView 壳（PoC，2026-11-18）。
 *
 * <p>只做三件事：加载 {@code assets/agent_panel/agent_panel.html}、把
 * {@link com.oilquiz.app.ai.chat.parts.AgentPanelPayload} 生成的 JSON 喂给
 * {@code window.qzAgent.update(state)}、把页内高度与性能指标回传原生。</p>
 *
 * <p>为什么用 {@code loadDataWithBaseURL} 而不是 {@code loadUrl(file://…)}：页面必须离线可用、
 * 且以后要能把主题变量直接内联进 HTML；{@code file://} 的 module/CORS 限制也一并绕开。
 * 页面里没有任何远程资源，只有本包 assets。</p>
 *
 * <p>性能口径（PoC 要量三件事）：</p>
 * <ul>
 *   <li>页内 {@code update()} 的耗时（JS 侧 performance.now，面板右下角 + onStats 回传）；</li>
 *   <li>页内 FPS（rAF 计数）；</li>
 *   <li>原生侧首帧/更新延迟：由 {@code AgentPanelPoCActivity} 打点。</li>
 * </ul>
 */
public final class AgentPanelView extends FrameLayout
        implements com.oilquiz.app.ai.chat.component.ComponentLifecycle.Releasable {

    private static final String TAG = "AgentPanelView";
    private static final String ASSET = "agent_panel/agent_panel.html";
    private static final String BASE_URL = "file:///android_asset/";

    public interface OnHeightChanged {
        void onHeight(int heightPx);
    }

    public interface OnStats {
        void onStats(JSONObject stats);
    }

    private final WebView webView;
    private final float density;
    private final boolean holdsBudgetSlot;
    private boolean pageReady;
    private boolean released;
    private JSONObject pending;

    private OnHeightChanged heightListener;
    private OnStats statsListener;

    public AgentPanelView(Context context) {
        this(context, false);
    }

    /**
     * @param holdsBudgetSlot 是否已经通过 {@code AgentPanelBudget.acquire()} 占了一个存活额度；
     *                        释放时配对归还（调试页直建时传 false，不占额度）
     */
    public AgentPanelView(Context context, boolean holdsBudgetSlot) {
        super(context);
        this.holdsBudgetSlot = holdsBudgetSlot;
        density = getResources().getDisplayMetrics().density;
        webView = createWebView(context);
        addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        loadAsset();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView(Context context) {
        WebView view = new WebView(context);
        view.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // 与 MarkdownWebView 同口径：Android 11+ 默认禁止 file/content 访问，面板虽不加载图片，
        // 但保留可访问性，后续要放本地图标时不用再改一次。
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);
        view.addJavascriptInterface(new Bridge(), "Android");
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                pageReady = true;
                if (pending != null) {
                    JSONObject p = pending;
                    pending = null;
                    update(p);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest request) {
                // 面板里没有可点跳转的链接；出现就吞掉，别把聊天页顶走
                return request != null && request.getUrl() != null;
            }
        });
        return view;
    }

    private void loadAsset() {
        try (InputStream in = getContext().getAssets().open(ASSET);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            String html = new String(out.toByteArray(), StandardCharsets.UTF_8);
            webView.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null);
        } catch (Throwable t) {
            Log.w(TAG, "agent panel 资产读取失败: " + t);
        }
    }

    /** 推送一帧状态；页面没就绪时只保留最后一帧（流式场景等效于合并中间帧）。 */
    public void update(JSONObject state) {
        if (state == null || released) {
            return;
        }
        if (!pageReady) {
            pending = state;
            return;
        }
        String js = "(function(){try{window.qzAgent&&window.qzAgent.update("
                + state.toString() + ");}catch(e){}})();";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "update 注入失败: " + t);
        }
    }

    public void setOnHeightChanged(OnHeightChanged listener) {
        this.heightListener = listener;
    }

    public void setOnStats(OnStats listener) {
        this.statsListener = listener;
    }

    /** 页面是否已加载完成（PoC 打点用）。 */
    public boolean isPageReady() {
        return pageReady;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!released) {
            try {
                webView.onResume();
            } catch (Throwable ignored) {
                // 恢复失败不影响已渲染内容
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        // 离屏只暂停，不销毁：列表复用回来还要继续用；销毁由 releaseComponent() 负责
        try {
            if (!released) {
                webView.onPause();
            }
        } catch (Throwable ignored) {
            // pause 失败不影响后续释放
        }
        super.onDetachedFromWindow();
    }

    /**
     * 释放 WebView（renderer {@code releaseView} 与组件深度释放两条路都会走到，必须幂等）。
     *
     * <p>释放顺序有讲究，缺一步都可能留下 Chromium 侧残留：</p>
     * <ol>
     *   <li>摘掉 JS 桥——它是 {@code Bridge} 内部类，持有 Activity Context；</li>
     *   <li>{@code stopLoading} 掐断在途请求；</li>
     *   <li>切到 {@code about:blank} 并 {@code clearHistory}，让当前文档与 JS 堆先被替换掉；</li>
     *   <li>从父容器摘除，再 {@code destroy()}；</li>
     *   <li>置空监听器（它们持有 ChatPartsHost / Activity），归还额度。</li>
     * </ol>
     */
    public void release() {
        releaseComponent();
    }

    @Override
    public void releaseComponent() {
        if (released) {
            return;
        }
        released = true;
        pageReady = false;
        pending = null;
        heightListener = null;
        statsListener = null;
        try {
            webView.removeJavascriptInterface("Android");
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.clearFormData();
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
        } catch (Throwable t) {
            Log.w(TAG, "releaseComponent 失败: " + t);
        }
        if (holdsBudgetSlot) {
            AgentPanelBudget.release();
        }
    }

    @Override
    public boolean isComponentReleased() {
        return released;
    }

    private final class Bridge {
        @JavascriptInterface
        public void onHeight(final int cssPx) {
            if (cssPx <= 0 || released) {
                return;
            }
            post(() -> {
                int px = Math.round(cssPx * density);
                ViewGroup.LayoutParams lp = webView.getLayoutParams();
                if (lp != null && lp.height != px) {
                    lp.height = px;
                    webView.setLayoutParams(lp);
                }
                if (heightListener != null) {
                    heightListener.onHeight(px);
                }
            });
        }

        @JavascriptInterface
        public void onStats(final String json) {
            if (json == null || json.isEmpty() || released) {
                return;
            }
            post(() -> {
                if (statsListener == null) {
                    return;
                }
                try {
                    statsListener.onStats(new JSONObject(json));
                } catch (Throwable ignored) {
                    // 指标坏一帧不影响功能
                }
            });
        }
    }
}
