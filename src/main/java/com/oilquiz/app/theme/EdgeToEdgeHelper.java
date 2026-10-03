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
 * Edge-to-edge 全局适配器（Android 16 强制 edge-to-edge 的统一方案）。
 *
 * <p>targetSdk 36（Android 16）强制 edge-to-edge 不可退出，本项目 247 个布局均为旧式布局
 * （无 fitsSystemWindows）。本工具为每个普通界面主动做真适配：
 * <ol>
 *   <li>setDecorFitsSystemWindows(false)：内容延伸到系统栏之后</li>
 *   <li>顶层 insets 策略（状态栏区显示页面自己的背景，而非窗口背景）：
 *       <ul>
 *         <li><b>带 AppBar 的页面</b>：AppBarLayout 顶部 padding=状态栏高度 → 标题栏背景
 *             上顶到状态栏（状态栏区显示 AppBar 背景色），内容由布局自然顶到 AppBar 之下，
 *             「AppBar 位置留出来」的效果。</li>
 *         <li><b>无 AppBar 的页面</b>：根容器背景（壁纸/背景色）延伸到状态栏，
 *             只给内容子 View 顶部 padding → 状态栏区显示壁纸/背景，内容不被遮挡。</li>
 *       </ul></li>
 *   <li>底部/左右 inset：根容器 bottom/left/right padding（背景仍全屏延伸，内容避开导航栏与挖孔）</li>
 *   <li>状态栏/导航栏图标深浅色：按状态栏区实际背景（AppBar 背景 / 窗口背景）亮度自动切换</li>
 * </ol>
 *
 * <p>不接管（白名单）：AIChatActivity（自有
 * 状态栏/IME insets 逻辑）；透明主题悬浮窗（无自有背景）亦跳过。
 *
 * <p>在 setContentView 之后调用，由 SmartQuizApplication 的 ActivityLifecycleCallbacks
 * onActivityCreated 统一接入，无需逐个界面改动。
 */
public final class EdgeToEdgeHelper {

    /** 自有 insets/沉浸逻辑的界面类名（全类名），不接管以免覆盖其自定义监听。 */
    private static final String[] SKIP_OWNERS = {
            "com.oilquiz.app.ui.activity.AIChatActivity"
    };

    /**
     * AppBar 保持原位（不上顶）的界面：标题栏留在状态栏下方，
     * 状态栏区显示窗口背景（壁纸/渐变背景延伸），用于与「无 AppBar 页」统一风格。
     */
    private static final String[] SKIP_APPBAR_INSET = {
            "com.oilquiz.app.ui.activity.ToolboxActivity"
    };

    /** 记录各 View 的原始 padding（insets 回调会重复触发，需绝对增量而非累积）。 */
    private static final java.util.WeakHashMap<View, int[]> ORIG_PADDING = new java.util.WeakHashMap<>();

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
            // 系统栏区域由应用绘制：状态栏/导航栏透明（否则旧式着色层会盖住 AppBar 背景/壁纸）
            activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
            activity.getWindow().setNavigationBarColor(Color.TRANSPARENT);
            // MIUI 等系统会给导航栏区域强加对比 scrim（白色遮罩），滚动时与内容交替导致闪烁
            // 官方文档：edge-to-edge 后应关闭 navigationBarContrastEnforced 去除该遮罩
            activity.getWindow().setNavigationBarContrastEnforced(false);

            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null) {
                return;
            }
            View root = content.getChildCount() > 0 ? content.getChildAt(0) : content;
            if (root == null) {
                return;
            }
            // AppBar 沉浸前置：AppBarLayout 无背景（背景在内部 Toolbar 上）时补 colorSurface，
            // 让标题栏背景真正延伸到状态栏区
            View appBarPre = findAppBar(root);
            if (appBarPre != null && !(appBarPre.getBackground() instanceof android.graphics.drawable.ColorDrawable)) {
                TypedValue tv = new TypedValue();
                if (activity.getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface, tv, true)) {
                    appBarPre.setBackgroundColor(tv.data);
                }
            }

            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                // 官方推荐：systemBars 含状态栏/导航栏/标题栏；叠加 displayCutout 覆盖挖孔（横屏挖孔在左右侧）
                androidx.core.graphics.Insets bars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                applyInsets(root, bars, insets, activity);
                return insets;
            });
            // 主动请求一次 insets 分发（部分设备首次不自动回调，避免内容漏到状态栏下）
            ViewCompat.requestApplyInsets(root);

            // 深浅图标：按状态栏区实际背景亮度决定
            boolean light = isLightStatusArea(root, activity);
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                    activity.getWindow(), activity.getWindow().getDecorView());
            controller.setAppearanceLightStatusBars(light);
            controller.setAppearanceLightNavigationBars(light);
        } catch (Throwable ignored) {
            // 静默降级：保持旧行为
        }
    }

    /**
     * 顶部 insets 策略：
     * 带 AppBar → AppBar 顶部 padding（标题栏上顶）；AppBar 保持原位名单 →
     * 给 AppBar 顶部 padding 让标题栏留在状态栏下方（状态栏区显示窗口背景=壁纸/渐变延伸）；
     * 无 AppBar → 内容子 View 顶部 padding（背景延伸）。
     * 底部/左右 → 根容器 padding（背景仍全屏）。
     */
    private static void applyInsets(View root, androidx.core.graphics.Insets bars,
                                    WindowInsetsCompat insets, Activity activity) {
        View appBar = findAppBar(root);
        boolean keepInPlace = appBar != null && isSkipAppBarInset(activity);
        if (appBar != null && !keepInPlace) {
            // AppBar 上顶：状态栏区显示 AppBar 背景，内容自然被顶到 AppBar 之下
            int[] oa = orig(appBar);
            appBar.setPadding(oa[0], oa[1] + bars.top, oa[2], oa[3]);
        } else if (root instanceof ViewGroup && ((ViewGroup) root).getChildCount() > 0) {
            // 背景（壁纸/背景色）延伸，只把内容顶下来（AppBar 保持原位的页面同样如此）
            View child = ((ViewGroup) root).getChildAt(0);
            int[] oc = orig(child);
            child.setPadding(oc[0], oc[1] + bars.top, oc[2], oc[3]);
        } else {
            int[] or = orig(root);
            root.setPadding(or[0], or[1] + bars.top, or[2], or[3]);
        }

        // 底部兜底：部分系统（如个别 MIUI 版本）手势导航模式下 navigationBars 的底部 inset 可能为 0，
        // 但 systemGestures 底部始终会报手势条区域 → 取两者最大值，确保小白条不遮挡内容
        int bottom = Math.max(bars.bottom,
                insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom);

        // 底部/左右：根容器（背景全屏延伸不受 padding 影响）
        int[] o = orig(root);
        root.setPadding(o[0] + bars.left, root.getPaddingTop(), o[2] + bars.right, o[3] + bottom);
    }

    /**
     * 底部贴边弹窗（Gravity.BOTTOM / 全屏 Dialog）的 edge-to-edge 适配：
     * Android 15+（targetSdk 35+）强制 edge-to-edge 同样作用于 Dialog 窗口，
     * 贴边弹窗的底部内容会被导航栏/手势条遮挡。透明系统栏 + 根容器 insets padding。
     */
    public static void applyDialog(android.app.Dialog dialog) {
        try {
            if (dialog == null || dialog.getWindow() == null) return;
            android.view.Window window = dialog.getWindow();
            WindowCompat.setDecorFitsSystemWindows(window, false);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            window.setNavigationBarContrastEnforced(false);

            ViewGroup content = window.findViewById(android.R.id.content);
            View root = content != null && content.getChildCount() > 0 ? content.getChildAt(0) : null;
            if (root == null) return;
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                androidx.core.graphics.Insets bars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                int bottom = Math.max(bars.bottom,
                        insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom);
                int[] o = orig(root);
                root.setPadding(o[0] + bars.left, o[1] + bars.top, o[2] + bars.right, o[3] + bottom);
                return insets;
            });
            ViewCompat.requestApplyInsets(root);
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window,
                    window.getDecorView());
            controller.setAppearanceLightStatusBars(isLightDialogArea(root));
            controller.setAppearanceLightNavigationBars(isLightDialogArea(root));
        } catch (Throwable ignored) {
            // 静默降级：不影响弹窗显示
        }
    }

    /** Dialog 根背景亮度：亮 → 深色图标。 */
    private static boolean isLightDialogArea(View root) {
        try {
            int c = sampleColor(root.getBackground());
            if (c != 0) return ColorUtils.calculateLuminance(c) > 0.5;
            TypedValue tv = new TypedValue();
            if (root.getContext().getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true)) {
                return ColorUtils.calculateLuminance(tv.data) > 0.5;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** AppBar 是否保持原位（标题栏不顶到状态栏）。 */
    private static boolean isSkipAppBarInset(Activity activity) {
        String name = activity.getClass().getName();
        for (String s : SKIP_APPBAR_INSET) {
            if (s.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static int[] orig(View v) {
        int[] o = ORIG_PADDING.get(v);
        if (o == null) {
            o = new int[]{v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom()};
            ORIG_PADDING.put(v, o);
        }
        return o;
    }

    /** 递归查找 AppBarLayout（MaterialComponents）。 */
    private static View findAppBar(View root) {
        if (root instanceof com.google.android.material.appbar.AppBarLayout) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findAppBar(vg.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 把窗口背景同步为页面 root 背景（壁纸/背景图/背景色）：
     * 状态栏/导航栏区（edge-to-edge 下由窗口背景垫底）因此显示与页面一致的背景，
     * 实现「壁纸/背景延伸到系统栏」的统一观感。在 AppWallpaperManager 应用壁纸后调用。
     */
    public static void syncWindowBackground(Activity activity, View root) {
        try {
            if (activity == null || root == null || isWindowBgSyncSkipped(activity)) {
                return;
            }
            android.graphics.drawable.Drawable bg = root.getBackground();
            if (bg == null) {
                return;
            }
            activity.getWindow().setBackgroundDrawable(bg);
        } catch (Throwable ignored) {
            // 静默：失败不影响界面
        }
    }

    /**
     * 状态栏/导航栏图标深浅色全局同步（在壁纸应用后调用，含 AIChatActivity 等 insets 白名单页）：
     * 按状态栏区实际背景（壁纸/背景图多点采样亮度）决定图标颜色，
     * 避免「深色壁纸+深色图标」或「浅色壁纸+浅色图标」看不清。
     */
    public static void syncStatusBarIcons(Activity activity, View root) {
        try {
            if (activity == null || isWindowBgSyncSkipped(activity)) {
                return;
            }
            int c = 0;
            if (root != null) {
                c = sampleColor(root.getBackground());
            }
            if (c == 0) {
                TypedValue tv = new TypedValue();
                if (activity.getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true)) {
                    c = tv.data;
                }
            }
            boolean light = c == 0 || ColorUtils.calculateLuminance(c) > 0.5;
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                    activity.getWindow(), activity.getWindow().getDecorView());
            controller.setAppearanceLightStatusBars(light);
            controller.setAppearanceLightNavigationBars(light);
        } catch (Throwable ignored) {
        }
    }

    /** 窗口背景同步只跳过透明悬浮窗；AIChatActivity 虽为 insets 白名单，
     *  但窗口背景同步仅改系统栏垫底背景，不与 main_content 的自有 insets 冲突，需同步壁纸。 */
    private static boolean isWindowBgSyncSkipped(Activity activity) {
        return isTranslucent(activity);
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

    /**
     * 状态栏区背景亮度：优先 AppBar 背景色（有 AppBar 时状态栏区显示它的背景），
     * 否则按窗口背景（colorBackground）。亮背景 → 深色图标。
     */
    private static boolean isLightStatusArea(View root, Activity activity) {
        try {
            View appBar = findAppBar(root);
            // AppBar 保持原位的页面：状态栏区显示窗口背景（壁纸/渐变），不采样 AppBar 背景
            if (appBar != null && !isSkipAppBarInset(activity)) {
                int c = sampleColor(appBar.getBackground());
                if (c != 0) {
                    return ColorUtils.calculateLuminance(c) > 0.5;
                }
            }
            int rc = sampleColor(root.getBackground());
            if (rc != 0) {
                return ColorUtils.calculateLuminance(rc) > 0.5;
            }
            TypedValue tv = new TypedValue();
            if (activity.getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true)) {
                return ColorUtils.calculateLuminance(tv.data) > 0.5;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** 从背景 Drawable 采样代表色（0 表示无法采样）：Color/Bitmap/Gradient/Layer 逐层递归。 */
    private static int sampleColor(android.graphics.drawable.Drawable d) {
        if (d == null) {
            return 0;
        }
        try {
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                return ((android.graphics.drawable.ColorDrawable) d).getColor();
            }
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                android.graphics.Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                if (bmp != null && bmp.getWidth() > 0 && bmp.getHeight() > 0) {
                    // 顶部区域多点平均采样（对应状态栏区），抗壁纸图案/单点异常干扰
                    int[] xs = {3, 10, 20, 30, 40, 50, 60, 70, 80, 90, 97};
                    int[] ys = {1, 3, 6, 9, 13};
                    long rr = 0, gg = 0, bb = 0;
                    int n = 0;
                    for (int yy : ys) {
                        int py = Math.min(bmp.getHeight() - 1, bmp.getHeight() * yy / 100);
                        for (int xx : xs) {
                            int px = Math.min(bmp.getWidth() - 1, bmp.getWidth() * xx / 100);
                            int c = bmp.getPixel(px, py);
                            rr += android.graphics.Color.red(c);
                            gg += android.graphics.Color.green(c);
                            bb += android.graphics.Color.blue(c);
                            n++;
                        }
                    }
                    return android.graphics.Color.rgb((int) (rr / n), (int) (gg / n), (int) (bb / n));
                }
            }
            if (d instanceof android.graphics.drawable.GradientDrawable) {
                try {
                    Object c = ((android.graphics.drawable.GradientDrawable) d).getColor();
                    if (c instanceof Integer) {
                        return (Integer) c;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (d instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) d;
                for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                    int c = sampleColor(ld.getDrawable(i));
                    if (c != 0) {
                        return c;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }
}
