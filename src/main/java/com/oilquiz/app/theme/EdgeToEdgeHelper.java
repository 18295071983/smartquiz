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


    /** 记录各 View 的原始 padding（insets 回调会重复触发，需绝对增量而非累积）。 */
    private static final java.util.WeakHashMap<View, int[]> ORIG_PADDING = new java.util.WeakHashMap<>();

    /** 记录各 View 的原始 top margin（顶部 insets 用 margin 下移 AppBar，需绝对增量而非累积）。 */
    private static final java.util.WeakHashMap<View, int[]> ORIG_MARGIN = new java.util.WeakHashMap<>();

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
            // 系统栏区域由应用绘制：状态栏与导航条都**透明**，让页面背景（壁纸/渐变）真正
            // 铺到底 —— 与壁纸扩展功能一致（AIChatActivity 一直是这么做的）。
            //
            // 历史：这里曾是"导航条固定为主题 surface 色的不透明色带"（方案 A），目的是压掉
            // MIUI 滚动时的动态对比遮罩闪烁。但那条色带与「壁纸扩展」打架：底部永远露一条白条，
            // 壁纸铺不到底。2026-10-04 按需求删除色带；防闪烁改由
            // navigationBarContrastEnforced=false 承担（系统不再叠加对比 scrim）。
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

            // 深浅图标：状态栏/导航条都按壁纸区的实际亮度（透明栏下两者看到的是同一层背景）
            boolean light = isLightStatusArea(root, activity);
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                    activity.getWindow(), activity.getWindow().getDecorView());
            controller.setAppearanceLightStatusBars(light);
            controller.setAppearanceLightNavigationBars(light);
        } catch (Throwable ignored) {
            // 静默降级：保持旧行为
        }
    }

    /** 主题 surface 色（浅色/深色模式自动切换），导航条固定色带用。 */
    private static int resolveSurfaceColor(android.content.Context context) {
        try {
            TypedValue tv = new TypedValue();
            if (context.getTheme().resolveAttribute(
                    com.google.android.material.R.attr.colorSurface, tv, true)) {
                return tv.data;
            }
            if (context.getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true)) {
                return tv.data;
            }
        } catch (Throwable ignored) {
        }
        return 0xFFFEFBFF;
    }

    /**
     * 固定导航条为**透明**（与壁纸扩展一致），避免滚动/滑动时系统动态叠加对比遮罩闪烁。
     *
     * <p>历史：这里原来把导航条设成"主题 surface 色不透明色带"（方案 A）。那条色带与
     * 壁纸扩展冲突（底部永远一条白条，壁纸铺不到底），2026-10-04 按要求删除；
     * 防闪烁由 {@code navigationBarContrastEnforced=false} 承担。
     *
     * <p>在 onResume 壁纸同步（syncStatusBarIcons）之后调用，避免图标深浅被状态栏区亮度覆盖。
     */
    public static void stabilizeNavBar(Activity activity, View root) {
        try {
            if (activity == null || isTranslucent(activity)) {
                return;
            }
            activity.getWindow().setNavigationBarColor(Color.TRANSPARENT);
            activity.getWindow().setNavigationBarContrastEnforced(false);
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                    activity.getWindow(), activity.getWindow().getDecorView());
            boolean light = (root != null) ? isLightStatusArea(root, activity) : true;
            controller.setAppearanceLightNavigationBars(light);
        } catch (Throwable ignored) {
            // 静默降级
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
        // 顶部策略（2026-10-04 按需求调整）：**带标题栏的页面，状态栏区也要显示页面背景
        //（壁纸/渐变延伸）** —— 所以把 AppBar 整体下移状态栏高度，而不是只给它内部加 padding
        //（后者会让标题栏背景继续盖住状态栏区，用户看到的是"状态栏=标题栏颜色"）。
        if (appBar != null) {
            ViewGroup.LayoutParams lp = appBar.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                // 首选：top margin 下移（LinearLayout / FrameLayout / CoordinatorLayout /
                // RelativeLayout / ConstraintLayout 都支持 margin）
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                int[] om = origMargin(appBar);
                mlp.topMargin = om[0] + bars.top;
                appBar.setLayoutParams(mlp);
            } else {
                // 容器不支持 margin：退化为"给 AppBar 内部加 padding"，背景仍由 root 延伸
                int[] oa = orig(appBar);
                appBar.setPadding(oa[0], oa[1] + bars.top, oa[2], oa[3]);
            }
        } else if (root instanceof ViewGroup && ((ViewGroup) root).getChildCount() > 0) {
            // 无 AppBar：背景（壁纸/背景色）延伸，只把内容顶下来
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
            // 导航条固定主题 surface 色（同 Activity 方案 A），避免贴边弹窗底部遮罩闪烁
            int navColor = resolveSurfaceColor(dialog.getContext());
            window.setNavigationBarColor(navColor);
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


    private static int[] orig(View v) {
        int[] o = ORIG_PADDING.get(v);
        if (o == null) {
            o = new int[]{v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom()};
            ORIG_PADDING.put(v, o);
        }
        return o;
    }

    /** 原始 top margin（绝对增量，避免 insets 回调重复触发时累积） */
    private static int[] origMargin(View v) {
        int[] o = ORIG_MARGIN.get(v);
        if (o == null) {
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            int top = (lp instanceof android.view.ViewGroup.MarginLayoutParams)
                    ? ((android.view.ViewGroup.MarginLayoutParams) lp).topMargin : 0;
            o = new int[]{top};
            ORIG_MARGIN.put(v, o);
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
     *
     * <p>修复：窗口背景若为位图类 Drawable（壁纸），受原始尺寸限制在底部系统栏区域
     * 铺不满（露出 DecorView 默认黑色块）——强制 FILL 铺满，并把 DecorView 背景一并
     * 设为同一背景（覆盖全窗口含系统栏）；root 无背景时用壁纸/主题背景色兜底。
     */
    public static void syncWindowBackground(Activity activity, View root) {
        try {
            if (activity == null || root == null || isWindowBgSyncSkipped(activity)) {
                return;
            }

            // 目标：**页面背景（不管是什么）都要延伸到系统栏区域**，同时
            // Window / DecorView / 根布局 各持**独立** Drawable 实例。
            //
            // 历史 bug ①：直接拿 root.getBackground() 又 setBackgroundDrawable 给 Window 和
            // DecorView —— 同一实例被三个尺寸不同的 owner 共用，滑动时互相重设 bounds → 重绘"打架"。
            // 历史 bug ②（我上一版引入）：只把纯色复制给窗口、其它一律换"全局壁纸"，
            // 导致**页面自己的背景（各界面单独壁纸/专属图片）不再延伸**。现在统一走「按页面背景
            // 深拷贝一份独立实例」，是纯色就复制颜色，是图片就复用位图新建 Drawable。
            android.graphics.drawable.Drawable rootBg = root.getBackground();
            android.graphics.drawable.Drawable windowBg =
                    independentCopy(rootBg, activity.getResources());

            if (windowBg == null) {
                // 页面自身没有背景：壁纸模式下用全局壁纸，否则用主题背景色（系统栏区不露黑）
                windowBg = com.oilquiz.app.theme.AppWallpaperManager.getWallpaperDrawableForWindow(activity);
                if (windowBg == null) {
                    TypedValue tv = new TypedValue();
                    if (activity.getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true)) {
                        windowBg = new android.graphics.drawable.ColorDrawable(tv.data);
                    }
                }
            }
            if (windowBg == null) {
                return;
            }
            // 位图类 Drawable 强制铺满（BitmapDrawable 默认 gravity FILL，但经 LayerDrawable
            // 包装/部分 ROM 下可能按原始尺寸绘制，底部系统栏区域漏黑 → 显式 FILL）
            ensureFill(windowBg);
            activity.getWindow().setBackgroundDrawable(windowBg);
            // DecorView 背景同步：覆盖整个窗口（含系统栏区域），同样必须是另一份独立实例
            android.graphics.drawable.Drawable decorBg =
                    independentCopy(windowBg, activity.getResources());
            if (decorBg != null) {
                ensureFill(decorBg);
                activity.getWindow().getDecorView().setBackgroundDrawable(decorBg);
            }
            // 诊断（与壁纸模块同 tag）：确认"页面背景 → 窗口背景"确实同步了、且各为独立实例
            android.util.Log.i("WallpaperDebug", "系统栏背景同步: rootBg="
                    + (rootBg == null ? "null" : rootBg.getClass().getSimpleName())
                    + " → windowBg=" + windowBg.getClass().getSimpleName()
                    + " → decorBg=" + (decorBg != null ? decorBg.getClass().getSimpleName() : "null")
                    + ", mode=" + com.oilquiz.app.theme.AppWallpaperManager.getMode(activity));
        } catch (Throwable ignored) {
            // 静默：失败不影响界面
        }
    }

    /**
     * 深拷贝一份**独立** Drawable（保留视觉，但不与源共享 bounds/状态）。
     *
     * <p>顺序：① ConstantState（BitmapDrawable/ColorDrawable/多数 shape 都支持）；
     * ② LayerDrawable 逐层递归复制后重建外层（壁纸=位图+暗化遮罩就是这种）；
     * ③ 都不行返回 null（由调用方兜底）。
     */
    private static android.graphics.drawable.Drawable independentCopy(
            android.graphics.drawable.Drawable src, android.content.res.Resources res) {
        if (src == null) {
            return null;
        }
        try {
            android.graphics.drawable.Drawable.ConstantState cs = src.getConstantState();
            if (cs != null) {
                return cs.newDrawable(res).mutate();
            }
        } catch (Throwable ignored) {
        }
        if (src instanceof android.graphics.drawable.LayerDrawable) {
            try {
                android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) src;
                int n = ld.getNumberOfLayers();
                android.graphics.drawable.Drawable[] layers = new android.graphics.drawable.Drawable[n];
                boolean any = false;
                for (int i = 0; i < n; i++) {
                    android.graphics.drawable.Drawable layer = ld.getDrawable(i);
                    android.graphics.drawable.Drawable c = independentCopy(layer, res);
                    layers[i] = (c != null) ? c : layer;
                    any |= (c != null);
                }
                if (any) {
                    android.graphics.drawable.LayerDrawable out = new android.graphics.drawable.LayerDrawable(layers);
                    // 保留各层原始的 gravity/inset（壁纸遮罩层通常无所谓，但别丢信息）
                    for (int i = 0; i < n; i++) {
                        int g = ld.getLayerGravity(i);
                        if (g != 0) {
                            out.setLayerGravity(i, g);
                        }
                    }
                    return out;
                }
            } catch (Throwable ignored) {
            }
        }
        if (src instanceof android.graphics.drawable.ColorDrawable) {
            return new android.graphics.drawable.ColorDrawable(
                    ((android.graphics.drawable.ColorDrawable) src).getColor());
        }
        return null;
    }

    /** 递归强制位图 Drawable 铺满 bounds（FILL），保证系统栏区域也被背景覆盖。 */
    private static android.graphics.drawable.Drawable ensureFill(android.graphics.drawable.Drawable d) {
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            ((android.graphics.drawable.BitmapDrawable) d).setGravity(android.view.Gravity.FILL);
        } else if (d instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) d;
            for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                ensureFill(ld.getDrawable(i));
            }
        }
        return d;
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
     * 状态栏区背景亮度：一律按**页面根背景**（壁纸/渐变/背景色）采样。
     *
     * <p>2026-10-04：AppBar 已整体下移（状态栏区显示页面背景），不再采样 AppBar 背景
     * —— 那会算出与真实背景相反的图标颜色。亮背景 → 深色图标。
     */
    private static boolean isLightStatusArea(View root, Activity activity) {
        try {
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
