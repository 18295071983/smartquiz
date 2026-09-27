package com.oilquiz.app.vnc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 让浮层控件（状态条 / 悬浮按钮）**可以拖动、松手吸附边缘、长按收起**，并把位置记住。
 *
 * <p>为什么要有它：之前这些浮层是用 {@code layout_gravity} 钉在左上角的固定位置，
 * 用户的原话是「动态按钮不能拖动啊，为何是固定位置」——屏幕那么长，手指习惯也不一样，
 * 浮层挡着的地方应该由用户自己决定。
 *
 * <p>四条行为约定（和主流远控客户端的浮动工具条一致）：
 * <ol>
 *   <li><b>拖超过 touchSlop 才算拖</b>，否则仍按点击处理 —— 不然按钮就点不动了；</li>
 *   <li><b>松手吸附到最近的左右边缘</b>（160ms 动画），不会停在屏幕正中间挡着内容；</li>
 *   <li><b>长按</b>触发 {@link LongPressAction}（本页用来把浮层收成小圆点 / 复位位置）；</li>
 *   <li><b>位置存进 SharedPreferences</b>（键 {@code <prefKey>_x/_y}），拖动与转屏后都会
 *       clamp 回屏幕内，不会把浮层甩到看不见的地方。</li>
 * </ol>
 */
public final class FloatingDrag {

    /** 没被拖动时算点击 */
    public interface TapAction {
        void onTap();
    }

    /** 长按（没移动的情况下按住超过系统长按时长） */
    public interface LongPressAction {
        void onLongPress();
    }

    private static final String PREF = "vnc_prefs";
    private static final long SNAP_MS = 160L;

    private FloatingDrag() {
    }

    public static void attach(final View v, final View root, final String prefKey,
                              final TapAction tap) {
        attach(v, root, prefKey, tap, null);
    }

    /**
     * 给 {@code v} 装上拖动 / 吸附 / 长按能力。
     *
     * @param v         要拖的浮层（父容器必须是 {@code root}，位置用 setX/setY 绝对定位）
     * @param root      屏幕根布局，用来算边界
     * @param prefKey   位置存储键前缀
     * @param tap       没拖动（视为点击）时的动作，可为 null
     * @param longPress 长按动作，可为 null
     */
    public static void attach(final View v, final View root, final String prefKey,
                              final TapAction tap, final LongPressAction longPress) {
        final Context ctx = v.getContext();
        final SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        final Handler handler = new Handler(Looper.getMainLooper());
        restore(v, root, sp, prefKey);

        v.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private float startX;
            private float startY;
            private boolean moved;
            private boolean longPressFired;

            private final Runnable longPressRunnable = () -> {
                if (!moved) {
                    longPressFired = true;
                    if (longPress != null) {
                        longPress.onLongPress();
                    }
                }
            };

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = view.getX();
                        startY = view.getY();
                        moved = false;
                        longPressFired = false;
                        handler.postDelayed(longPressRunnable,
                                ViewConfiguration.getLongPressTimeout());
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downRawX;
                        float dy = e.getRawY() - downRawY;
                        if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            moved = true;
                            handler.removeCallbacks(longPressRunnable);
                        }
                        if (moved) {
                            view.setX(clamp(startX + dx, root.getWidth() - view.getWidth()));
                            view.setY(clamp(startY + dy, root.getHeight() - view.getHeight()));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(longPressRunnable);
                        if (moved) {
                            snapAndSave(view, root, sp, prefKey);
                        } else if (!longPressFired && tap != null) {
                            tap.onTap();
                        }
                        moved = false;
                        longPressFired = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** 松手：吸附到最近的左右边缘（160ms 动画），并记住位置 */
    private static void snapAndSave(View v, View root, SharedPreferences sp, String prefKey) {
        int maxX = Math.max(0, root.getWidth() - v.getWidth());
        float targetX = (v.getX() + v.getWidth() / 2f) < root.getWidth() / 2f ? 0f : maxX;
        float targetY = clamp(v.getY(), root.getHeight() - v.getHeight());
        if (v.isAttachedToWindow()) {
            v.animate().x(targetX).y(targetY).setDuration(SNAP_MS).start();
        } else {
            v.setX(targetX);
            v.setY(targetY);
        }
        sp.edit().putFloat(prefKey + "_x", targetX).putFloat(prefKey + "_y", targetY).apply();
    }

    /** 回到默认位置（长按菜单用） */
    public static void reset(View v, View root, String prefKey) {
        v.getContext().getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .remove(prefKey + "_x").remove(prefKey + "_y").apply();
        v.animate().translationX(0f).translationY(0f).setDuration(SNAP_MS).start();
    }

    /** 转屏 / 尺寸变化后把浮层拉回新的屏幕范围内（位置偏好保留） */
    public static void reclamp(final View v, final View root, final String prefKey) {
        final SharedPreferences sp = v.getContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (!sp.contains(prefKey + "_x")) {
            return;
        }
        v.setX(clamp(v.getX(), root.getWidth() - v.getWidth()));
        v.setY(clamp(v.getY(), root.getHeight() - v.getHeight()));
        sp.edit().putFloat(prefKey + "_x", v.getX()).putFloat(prefKey + "_y", v.getY()).apply();
    }

    private static void restore(final View v, final View root, final SharedPreferences sp,
                                final String prefKey) {
        v.post(() -> {
            if (!sp.contains(prefKey + "_x")) {
                return;
            }
            v.setX(clamp(sp.getFloat(prefKey + "_x", 0f), root.getWidth() - v.getWidth()));
            v.setY(clamp(sp.getFloat(prefKey + "_y", 0f), root.getHeight() - v.getHeight()));
        });
    }

    private static float clamp(float value, int max) {
        if (max < 0) {
            return 0f;
        }
        return Math.max(0f, Math.min(value, max));
    }
}
