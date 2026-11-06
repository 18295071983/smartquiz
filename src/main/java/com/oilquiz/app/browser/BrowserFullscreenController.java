package com.oilquiz.app.browser;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.widget.FrameLayout;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

/**
 * HTML5 视频全屏的宿主实现。
 *
 * <p>WebView 播放视频时点全屏会回调 {@link WebChromeClient#onShowCustomView}，
 * 不给它一个承载容器，全屏按钮就会"点了没反应"。这里在 content 根上盖一层黑色
 * 覆盖层、隐藏系统栏与 ActionBar；退出时原样恢复。</p>
 */
public final class BrowserFullscreenController {

    private final Activity activity;
    private final FrameLayout overlay;

    private View customView;
    private WebChromeClient.CustomViewCallback callback;
    private boolean actionBarWasShowing;

    public BrowserFullscreenController(Activity activity) {
        this.activity = activity;
        this.overlay = new FrameLayout(activity);
        this.overlay.setBackgroundColor(Color.BLACK);
        this.overlay.setVisibility(View.GONE);
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content != null) {
            content.addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    public boolean isFullscreen() {
        return customView != null;
    }

    /** WebView 请求进入全屏 */
    public void show(View view, WebChromeClient.CustomViewCallback cb) {
        if (view == null) {
            if (cb != null) cb.onCustomViewHidden();
            return;
        }
        if (customView != null) {
            // 已有全屏内容：按 WebView 约定告知这次请求被拒绝
            if (cb != null) cb.onCustomViewHidden();
            return;
        }
        customView = view;
        callback = cb;
        overlay.removeAllViews();
        overlay.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
        overlay.setKeepScreenOn(true);
        hideSystemUi();

        if (activity instanceof AppCompatActivity) {
            try {
                ActionBar bar = ((AppCompatActivity) activity).getSupportActionBar();
                if (bar != null) {
                    actionBarWasShowing = bar.isShowing();
                    if (actionBarWasShowing) bar.hide();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** WebView 主动结束全屏（onHideCustomView） */
    public void hide() {
        hide(false);
    }

    /**
     * @param notifyWebView 用户按返回键等宿主侧退出时传 true，调用 callback 通知页面退出全屏；
     *                      页面自己退出时传 false，避免回调循环
     */
    public void hide(boolean notifyWebView) {
        if (customView == null) return;
        customView = null;
        overlay.setKeepScreenOn(false);
        overlay.removeAllViews();
        overlay.setVisibility(View.GONE);
        showSystemUi();

        if (activity instanceof AppCompatActivity) {
            try {
                ActionBar bar = ((AppCompatActivity) activity).getSupportActionBar();
                if (bar != null && actionBarWasShowing) bar.show();
            } catch (Throwable ignored) {
            }
        }
        actionBarWasShowing = false;

        WebChromeClient.CustomViewCallback cb = callback;
        callback = null;
        if (notifyWebView && cb != null) {
            try {
                cb.onCustomViewHidden();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Activity 销毁前调用：移除覆盖层，不再回调 WebView */
    public void destroy() {
        hide(false);
        try {
            ViewGroup parent = (ViewGroup) overlay.getParent();
            if (parent != null) parent.removeView(overlay);
        } catch (Throwable ignored) {
        }
    }

    private void hideSystemUi() {
        Window window = activity.getWindow();
        if (window == null) return;
        // minSdk 31：FLAG_FULLSCREEN 已废弃，统一用 InsetsController
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void showSystemUi() {
        Window window = activity.getWindow();
        if (window == null) return;
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            controller.show(WindowInsets.Type.systemBars());
        }
    }
}
