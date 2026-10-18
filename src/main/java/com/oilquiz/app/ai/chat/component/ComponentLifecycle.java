package com.oilquiz.app.ai.chat.component;

import android.view.View;
import android.view.ViewGroup;

/**
 * 组件 View 的释放通道（Track B0-1 生命周期部分）。
 *
 * 为什么不用 onDetachedFromWindow 直接 destroy：
 * ChatAdapter 对组件段做了指纹缓存（AIMessageViewHolder#componentSegmentViews），
 * 重绑时先 removeAllViews() 再把缓存里的同一个 View 重新 addView 回来
 * （见 ChatAdapter#renderMermaidMath 与组件标记渲染分支）。若在 detach 时销毁 WebView，
 * 下一次复用就是一个已销毁的 WebView——卡片变空白甚至抛异常。
 * 因此销毁时机定为「缓存判定这条 View 不会再被复用」，由调用方显式 release。
 *
 * 实现方（如 {@link HtmlWebView}）需保证 releaseComponent() 幂等。
 */
public final class ComponentLifecycle {

    private ComponentLifecycle() {
    }

    /** 需要显式释放资源的组件 View（WebView、播放器、定时器等）。 */
    public interface Releasable {
        /** 释放底层资源；重复调用无副作用。 */
        void releaseComponent();

        /** 是否已释放（释放后不得再复用）。 */
        boolean isComponentReleased();
    }

    /**
     * 释放一棵 View 树：深度优先找出所有 {@link Releasable} 并释放。
     *
     * @param root 组件渲染出来的根 View，可为 null
     */
    public static void release(View root) {
        if (root == null) return;
        if (root instanceof Releasable) {
            try {
                ((Releasable) root).releaseComponent();
            } catch (Throwable ignored) {
                // 释放失败不应影响调用方的视图回收流程
            }
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                release(vg.getChildAt(i));
            }
        }
    }
}
