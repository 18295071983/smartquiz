package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.webkit.WebView;

/**
 * 受沙箱管控的 HTML 组件 WebView（Track B0-1）。
 *
 * <p>两件事：
 * <ol>
 *   <li>记住自己的信任档与桥对象，释放时把 JS 接口一并摘掉；</li>
 *   <li>提供确定的释放入口 {@link #releaseComponent()}——WebView 不销毁就一直占着
 *       渲染器进程与原生内存，靠 GC 收不掉，所以必须有人显式调它
 *       （调用点：ChatAdapter 组件段指纹缓存判定旧 View 不再复用时）。</li>
 * </ol>
 *
 * <p>detach 时只 pause/stopLoading，<b>不</b> destroy：聊天流会在同一帧内
 * removeAllViews → 重新 addView 同一个缓存 View，detach 即销毁会把复用中的卡片打死。
 */
public class HtmlWebView extends WebView implements ComponentLifecycle.Releasable {

    /** JS 桥对象名（HtmlCardView 侧保持一致） */
    public static final String BRIDGE_NAME = "Android";

    private final HtmlSandbox.Trust trust;
    private Object bridge;
    private boolean released;

    public HtmlWebView(Context context, HtmlSandbox.Trust trust) {
        super(context);
        this.trust = trust;
        HtmlSandbox.configure(this, trust);
        setBackgroundColor(android.graphics.Color.TRANSPARENT);
        setVerticalScrollBarEnabled(true);
        setHorizontalScrollBarEnabled(true);
    }

    public HtmlSandbox.Trust getTrust() {
        return trust;
    }

    /** 记录桥对象，release 时移除（只有 LOCAL_FILE 档会挂桥） */
    public void setBridge(Object bridge, String name) {
        this.bridge = bridge;
        if (bridge != null && !released) {
            addJavascriptInterface(bridge, name);
        }
    }

    /** 该卡片是否可执行页面脚本（决定自适应高度/图片预览等依赖 JS 的能力是否启用） */
    public boolean isScriptEnabled() {
        return HtmlSandbox.scriptsEnabled(trust);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (released) return;
        try {
            // pause/resume 不是公开 API，公开的是这对（API 28 起废弃但仍可用）：
            // 卡片滑出屏幕时停掉页面动画/定时器，回来再恢复
            onResume();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        // 见类注释：这里只暂停，销毁由 ComponentLifecycle 触发
        if (!released) {
            try {
                onPause();
            } catch (Throwable ignored) {
            }
        }
        super.onDetachedFromWindow();
    }

    @Override
    public void releaseComponent() {
        if (released) return;
        released = true;
        try {
            stopLoading();
            loadUrl("about:blank");
        } catch (Throwable ignored) {
        }
        try {
            if (bridge != null) {
                removeJavascriptInterface(BRIDGE_NAME);
                bridge = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            removeAllViews();
        } catch (Throwable ignored) {
        }
        try {
            destroy();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean isComponentReleased() {
        return released;
    }
}
