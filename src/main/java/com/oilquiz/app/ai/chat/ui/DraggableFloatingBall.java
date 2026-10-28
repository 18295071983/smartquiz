package com.oilquiz.app.ai.chat.ui;

import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 可拖拽悬浮球控制器：把一个普通 View 变成"可来回拖动 + 点击触发"的悬浮控件。
 *
 * 设计意图（避免把触摸/拖拽/持久化逻辑堆进 AIChatActivity 主对话页）：
 * - 位移超过 touchSlop 判定为拖拽，否则判定为点击（点击回调 {@link Runnable} 由外部注入）；
 * - 拖拽范围夹取在父容器可视区内（用 translationX/Y 相对布局位移动，不改布局参数）；
 * - 拖拽结束把位移持久化到 {@link SharedPreferences}（按 {@code keyPrefix} 区分多个悬浮球），
 *   下次 {@link #attach()} 时恢复。
 *
 * 用法：{@code new DraggableFloatingBall(ballView, prefs, "history_fab", onClick).attach();}
 */
public class DraggableFloatingBall {

    private final View ball;
    private final SharedPreferences prefs;
    private final String keyPrefix;
    private final Runnable onClick;
    private final int touchSlop;

    public DraggableFloatingBall(View ball, SharedPreferences prefs, String keyPrefix, Runnable onClick) {
        this.ball = ball;
        this.prefs = prefs;
        this.keyPrefix = keyPrefix;
        this.onClick = onClick;
        this.touchSlop = ViewConfiguration.get(ball.getContext()).getScaledTouchSlop();
    }

    /** 绑定触摸监听并恢复上次位置（父容器 layout 完成后再夹取）。 */
    public void attach() {
        if (ball == null) return;
        final float savedX = prefs.getFloat(keyPrefix + "_tx", Float.MIN_VALUE);
        final float savedY = prefs.getFloat(keyPrefix + "_ty", Float.MIN_VALUE);
        if (savedX != Float.MIN_VALUE && savedY != Float.MIN_VALUE) {
            ball.post(() -> clamp(savedX, savedY));
        }
        ball.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY, startTx, startTy;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startTx = v.getTranslationX();
                        startTy = v.getTranslationY();
                        dragging = false;
                        // 消费 DOWN，避免被父级可滚动容器拦截；UP 时再区分点击/拖拽
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downRawX;
                        float dy = e.getRawY() - downRawY;
                        if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            dragging = true;
                        }
                        if (dragging) clamp(startTx + dx, startTy + dy);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!dragging) {
                            v.performClick();
                            if (onClick != null) onClick.run();
                        } else {
                            prefs.edit()
                                 .putFloat(keyPrefix + "_tx", v.getTranslationX())
                                 .putFloat(keyPrefix + "_ty", v.getTranslationY())
                                 .apply();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        dragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** 把 translation 夹取在父容器可视范围内（getLeft/getTop 为不含 translation 的布局位）。 */
    private void clamp(float tx, float ty) {
        if (!(ball.getParent() instanceof View)) return;
        View parent = (View) ball.getParent();
        int pw = parent.getWidth(), ph = parent.getHeight();
        int vw = ball.getWidth(), vh = ball.getHeight();
        if (pw == 0 || ph == 0 || vw == 0) return;
        float baseX = ball.getLeft();
        float baseY = ball.getTop();
        float minTx = -baseX;
        float maxTx = pw - vw - baseX;
        float minTy = -baseY;
        float maxTy = ph - vh - baseY;
        if (minTx > maxTx) { float t = minTx; minTx = maxTx; maxTx = t; }
        if (minTy > maxTy) { float t = minTy; minTy = maxTy; maxTy = t; }
        ball.setTranslationX(Math.max(minTx, Math.min(maxTx, tx)));
        ball.setTranslationY(Math.max(minTy, Math.min(maxTy, ty)));
    }
}
