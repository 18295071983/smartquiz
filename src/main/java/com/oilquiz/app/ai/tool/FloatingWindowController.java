package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 悬浮窗控制器：创建/关闭可拖动的系统级悬浮窗（需 SYSTEM_ALERT_WINDOW 权限）。
 * 供 SystemConnectTool 使用；单例持有当前悬浮窗 View，重复创建先关闭旧的。
 */
public class FloatingWindowController {

    private static View currentView;
    private static WindowManager.LayoutParams currentParams;
    private static WindowManager windowManager;

    /** 创建悬浮窗（text=内容，x/y=初始位置）。无权限时调用方应已引导授权。 */
    public static synchronized void show(Context context, String text, int x, int y) {
        hide(context);
        if (context == null) return;
        windowManager = (WindowManager) context.getApplicationContext()
                .getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return;

        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
        tv.setTextColor(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xCC1E293B);
        bg.setCornerRadius(dp(context, 10));
        tv.setBackground(bg);
        tv.setElevation(dp(context, 6));
        tv.setGravity(Gravity.CENTER);

        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = x;
        lp.y = y;
        lp.setTitle("AI 悬浮窗");

        // 拖动支持
        final int[] lastX = {x};
        final int[] lastY = {y};
        tv.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX[0] = (int) event.getRawX();
                    lastY[0] = (int) event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) event.getRawX() - lastX[0];
                    int dy = (int) event.getRawY() - lastY[0];
                    lp.x += dx;
                    lp.y += dy;
                    lastX[0] = (int) event.getRawX();
                    lastY[0] = (int) event.getRawY();
                    if (currentParams != null && windowManager != null) {
                        try {
                            windowManager.updateViewLayout(currentView, currentParams);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                default:
                    return false;
            }
        });

        try {
            windowManager.addView(tv, lp);
            currentView = tv;
            currentParams = lp;
        } catch (Exception e) {
            android.util.Log.w("FloatingWindowController", "创建悬浮窗失败: " + e.getMessage());
        }
    }

    /** 关闭悬浮窗 */
    public static synchronized void hide(Context context) {
        if (currentView != null && windowManager != null) {
            try {
                windowManager.removeView(currentView);
            } catch (Exception ignored) {
            }
        }
        currentView = null;
        currentParams = null;
        windowManager = null;
    }

    private static int dp(Context context, float value) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
