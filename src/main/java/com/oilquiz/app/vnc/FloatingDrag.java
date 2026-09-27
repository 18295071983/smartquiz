package com.oilquiz.app.vnc;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 让浮层控件（状态条 / 悬浮按钮）**可以拖动**，并把位置记住。
 *
 * <p>为什么要有它：之前这些浮层是用 {@code layout_gravity} 钉在左上角的固定位置，
 * 用户的原话是「动态按钮不能拖动啊，为何是固定位置」——屏幕那么长，手指习惯也不一样，
 * 浮层挡着的地方应该由用户自己决定。
 *
 * <p>三条行为约定（和主流远控客户端的浮动工具条一致）：
 * <ol>
 *   <li><b>拖超过 touchSlop 才算拖</b>，否则仍按点击处理 —— 不然按钮就点不动了；</li>
 *   <li><b>位置存进 SharedPreferences</b>（键 {@code <prefKey>_x/_y}），下次进来还在老地方；</li>
 *   <li><b>始终拉回屏幕内</b>：拖动时和转屏后都会 clamp，不会把浮层甩到看不见的地方。</li>
 * </ol>
 */
public final class FloatingDrag {

    /** 没被拖动时算点击 */
    public interface TapAction {
        void onTap();
    }

    private static final String PREF = "vnc_prefs";

    private FloatingDrag() {
    }

    /**
     * 给 {@code v} 装上拖动能力。
     *
     * @param v       要拖的浮层（父容器必须是 {@code root}，位置用 setX/setY 绝对定位）
     * @param root    屏幕根布局，用来算边界
     * @param prefKey 位置存储键前缀
     * @param tap     没拖动（视为点击）时的动作，可为 null
     */
    public static void attach(final View v, final View root, final String prefKey,
                              final TapAction tap) {
        final Context ctx = v.getContext();
        final SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        restore(v, root, sp, prefKey);

        v.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private float startX;
            private float startY;
            private boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = view.getX();
                        startY = view.getY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downRawX;
                        float dy = e.getRawY() - downRawY;
                        if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            moved = true;
                        }
                        if (moved) {
                            view.setX(clamp(startX + dx, root.getWidth() - view.getWidth()));
                            view.setY(clamp(startY + dy, root.getHeight() - view.getHeight()));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) {
                            save(sp, prefKey, view);
                        } else if (tap != null) {
                            tap.onTap();
                        }
                        moved = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** 转屏后把浮层拉回新的屏幕范围内（位置仍然保留用户的偏好） */
    public static void reclamp(final View v, final View root, final String prefKey) {
        final SharedPreferences sp = v.getContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (!sp.contains(prefKey + "_x")) {
            return;
        }
        v.setX(clamp(v.getX(), root.getWidth() - v.getWidth()));
        v.setY(clamp(v.getY(), root.getHeight() - v.getHeight()));
        save(sp, prefKey, v);
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

    private static void save(SharedPreferences sp, String prefKey, View v) {
        sp.edit().putFloat(prefKey + "_x", v.getX()).putFloat(prefKey + "_y", v.getY()).apply();
    }

    private static float clamp(float value, int max) {
        if (max < 0) {
            return 0f;
        }
        return Math.max(0f, Math.min(value, max));
    }
}
