package com.oilquiz.app.theme;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Edge-to-edge 全局适配器（Android 16 强制 edge-to-edge 前的统一方案）。
 *
 * <p>targetSdk 35 起 Android 15 强制 edge-to-edge（状态栏透明、内容上顶），本项目 247 个布局
 * 均为旧式布局（无 fitsSystemWindows），主题已用 android:windowOptOutEdgeToEdgeEnforcement=true
 * 在 Android 15 上退出强制行为作为兜底；本工具为每个普通界面主动开启真 edge-to-edge：
 * <ol>
 *   <li>setDecorFitsSystemWindows(false)：内容延伸到系统栏之后</li>
 *   <li>根容器 OnApplyWindowInsetsListener：把状态栏/导航栏/挖孔区域 inset 转成 padding，
 *       内容自动避开系统栏（不会遮挡）</li>
 *   <li>状态栏/导航栏图标深浅色：按窗口背景亮度自动切换（亮背景→深色图标）</li>
 * </ol>
 *
 * <p>不接管（白名单）：VncActivity/VncWebActivity（已全屏沉浸）、AIChatActivity（自有
 * 状态栏/IME insets 逻辑）；透明主题悬浮窗（无自有背景）亦跳过。
 *
 * <p>在 setContentView 之后调用，由 SmartQuizApplication 的 ActivityLifecycleCallbacks
 * onActivityCreated 统一接入，无需逐个界面改动。
 */
public final class EdgeToEdgeHelper {

    /** 自有 insets/沉浸逻辑的界面类名（全类名），不接管以免覆盖其自定义监听。 */
    private static final String[] SKIP_OWNERS = {
            "com.oilquiz.app.vnc.VncActivity",
            "com.oilquiz.app.vnc.VncWebActivity",
            "com.oilquiz.app.ui.activity.AIChatActivity"
    };

    private EdgeToEdgeHelper() {
    }

    /**
     * 对单个 Activity 应用真 edge-to-edge。任何失败都不影响界面正常显示（静默降级为旧行为）。
     */
    public static void apply(Activity activity) {
        try {
            if (activity == null || isSkipped(activity) || isTranslucent(activity)) {
                return;
            }

            WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);

            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null) {
                return;
            }
            View root = content.getChildCount() > 0 ? content.getChildAt(0) : content;
            if (root == null) {
                return;
            }

            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
            // 主动请求一次 insets 分发（部分设备首次不自动回调，避免内容漏到状态栏下）
            ViewCompat.requestApplyInsets(root);

            // 深浅图标：edge-to-edge 后系统栏区域显示窗口背景，按其亮度决定图标颜色
            boolean light = isLightBackground(activity);
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                    activity.getWindow(), activity.getWindow().getDecorView());
            controller.setAppearanceLightStatusBars(light);
            controller.setAppearanceLightNavigationBars(light);
        } catch (Throwable ignored) {
            // 静默降级：保持旧行为
        }
    }

    private static boolean isSkipped(Activity activity) {
        String name = activity.getClass().getName();
        for (String s : SKIP_OWNERS) {
            if (s.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 透明主题悬浮窗（无自有背景）不接管。 */
    private static boolean isTranslucent(Activity activity) {
        TypedArray ta = activity.obtainStyledAttributes(new int[]{
                android.R.attr.windowIsTranslucent,
                android.R.attr.windowBackground
        });
        try {
            if (ta.getBoolean(0, false)) {
                return true;
            }
            ColorStateList bg = ta.getColorStateList(1);
            return bg == null || bg.getDefaultColor() == Color.TRANSPARENT;
        } finally {
            ta.recycle();
        }
    }

    /** 按窗口背景（colorBackground）亮度判断：亮背景 → 深色图标。 */
    private static boolean isLightBackground(Activity activity) {
        try {
            TypedValue tv = new TypedValue();
            if (activity.getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true)) {
                return ColorUtils.calculateLuminance(tv.data) > 0.5;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }
}
