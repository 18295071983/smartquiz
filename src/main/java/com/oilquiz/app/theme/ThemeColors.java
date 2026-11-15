package com.oilquiz.app.theme;

import com.oilquiz.app.R;

import android.content.Context;
import android.content.res.Resources;
import android.util.SparseIntArray;
import android.util.TypedValue;

import com.oilquiz.app.SmartQuizApplication;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 硬编码色资源化取色工具：读取 hc_* 颜色资源（values + values-night 双套）。
 * 历史硬编码色（Java 中 0xFFxxxxxx / Color.parseColor）统一迁移为资源引用后，
 * 通过本工具获取，使深色模式自动适配。
 *
 * <p><b>2026-11-16 主题基础设施（第一层）</b>：以前 {@link #get(Context, int)} 是 17 条手写
 * {@code if} 白名单 + 末尾静默 {@code getColor}，导致 313 个颜色资源里只有 17 个跟随换肤，
 * 其余换主题不变且**没有任何提示**（真机表现：换肤后个别位置颜色不对，查不到原因）。
 * 现在改成：</p>
 * <ul>
 *   <li><b>映射表</b> {@link #ATTR_BY_RES}：resId → attrId，映射关系与旧白名单**逐条等价**；</li>
 *   <li><b>debug 未命中告警</b>：未接主题的颜色在 DEBUG 包打一行
 *       {@code 颜色未接主题: <资源名> → 固定值}（同 resId 只打一次，不刷屏）；</li>
 *   <li><b>可观测 API</b>：{@link #isThemeAware(int)} / {@link #themeAwareResIds()} 供「主题自检页」统计跟随率。</li>
 * </ul>
 */
public final class ThemeColors {

    private static final String TAG = "ThemeColors";

    /** resId → attrId。NULL 语义：0 表示"这个颜色不跟随主题"（固定色）。 */
    private static final SparseIntArray ATTR_BY_RES = new SparseIntArray();

    /** DEBUG 下已告警过的 resId（避免流式/滚动时刷屏）。 */
    private static final Set<Integer> WARNED = ConcurrentHashMap.newKeySet();

    static {
        // 与旧实现的 17 条 if 一一对应（改这里之前先想清楚：动一条就是改一次外观语义）
        ATTR_BY_RES.put(R.color.primary, R.attr.colorPrimary);
        ATTR_BY_RES.put(R.color.primary_color, R.attr.colorPrimary);
        ATTR_BY_RES.put(R.color.primary_dark, R.attr.colorPrimaryDark);
        ATTR_BY_RES.put(R.color.primary_light, R.attr.colorPrimaryContainer);
        ATTR_BY_RES.put(R.color.primary_container, R.attr.colorPrimaryContainer);
        ATTR_BY_RES.put(R.color.on_primary, R.attr.colorOnPrimary);
        ATTR_BY_RES.put(R.color.on_primary_container, R.attr.colorOnPrimaryContainer);
        ATTR_BY_RES.put(R.color.secondary, R.attr.colorSecondary);
        ATTR_BY_RES.put(R.color.secondary_container, R.attr.colorSecondaryContainer);
        ATTR_BY_RES.put(R.color.surface, R.attr.colorSurface);
        ATTR_BY_RES.put(R.color.surface_variant, R.attr.colorSurfaceVariant);
        ATTR_BY_RES.put(R.color.on_surface, R.attr.colorOnSurface);
        ATTR_BY_RES.put(R.color.outline, R.attr.colorOutline);
        ATTR_BY_RES.put(R.color.card_background, R.attr.colorCardBackground);
        ATTR_BY_RES.put(R.color.text_primary, R.attr.colorControlText);
        ATTR_BY_RES.put(R.color.text_secondary, R.attr.colorControlTextSecondary);
        ATTR_BY_RES.put(R.color.text_tertiary, R.attr.colorControlTextHint);
        ATTR_BY_RES.put(R.color.text_hint, R.attr.colorControlTextHint);
        ATTR_BY_RES.put(R.color.background, R.attr.colorBackground);
        ATTR_BY_RES.put(R.color.on_background, R.attr.colorOnBackground);
        // 2026-11-16 补全：状态色 / 边框 / 分隔线 / 遮罩 / 图表色统一走 attr
        //（以前这些是"固定色"，换肤和深色都只能靠 @color 的 values-night，不能按主题覆盖）
        ATTR_BY_RES.put(R.color.success, R.attr.colorSuccess);
        ATTR_BY_RES.put(R.color.success_color, R.attr.colorSuccess);
        ATTR_BY_RES.put(R.color.success_light, R.attr.colorSuccessContainer);
        ATTR_BY_RES.put(R.color.success_container, R.attr.colorSuccessContainer);
        ATTR_BY_RES.put(R.color.on_success, R.attr.colorOnSuccess);
        ATTR_BY_RES.put(R.color.warning, R.attr.colorWarning);
        ATTR_BY_RES.put(R.color.warning_color, R.attr.colorWarning);
        ATTR_BY_RES.put(R.color.warning_light, R.attr.colorWarningContainer);
        ATTR_BY_RES.put(R.color.warning_container, R.attr.colorWarningContainer);
        ATTR_BY_RES.put(R.color.on_warning, R.attr.colorOnWarning);
        ATTR_BY_RES.put(R.color.error, R.attr.colorDanger);
        ATTR_BY_RES.put(R.color.error_color, R.attr.colorDanger);
        ATTR_BY_RES.put(R.color.error_light, R.attr.colorErrorContainer);
        ATTR_BY_RES.put(R.color.error_container, R.attr.colorErrorContainer);
        ATTR_BY_RES.put(R.color.on_error, R.attr.colorOnDanger);
        ATTR_BY_RES.put(R.color.on_error_container, R.attr.colorOnErrorContainer);
        ATTR_BY_RES.put(R.color.info_color, R.attr.colorInfo);
        ATTR_BY_RES.put(R.color.border, R.attr.colorBorder);
        ATTR_BY_RES.put(R.color.border_light, R.attr.colorBorder);
        ATTR_BY_RES.put(R.color.component_border, R.attr.colorBorder);
        ATTR_BY_RES.put(R.color.chat_v2_border, R.attr.colorBorder);
        ATTR_BY_RES.put(R.color.chat_v2_border_strong, R.attr.colorBorderStrong);
        ATTR_BY_RES.put(R.color.divider, R.attr.colorDivider);
        ATTR_BY_RES.put(R.color.agent_summary_divider, R.attr.colorDivider);
        ATTR_BY_RES.put(R.color.card_scrim, R.attr.colorScrim);
        ATTR_BY_RES.put(R.color.chart_1, R.attr.colorChart1);
        ATTR_BY_RES.put(R.color.chart_2, R.attr.colorChart2);
        ATTR_BY_RES.put(R.color.chart_3, R.attr.colorChart3);
        ATTR_BY_RES.put(R.color.chart_4, R.attr.colorChart4);
        ATTR_BY_RES.put(R.color.chart_5, R.attr.colorChart5);
        ATTR_BY_RES.put(R.color.chart_6, R.attr.colorChart6);
        ATTR_BY_RES.put(R.color.chart_7, R.attr.colorChart7);
        ATTR_BY_RES.put(R.color.chart_8, R.attr.colorChart8);
    }

    private ThemeColors() {
    }

    /** 读取颜色资源（自动适配深浅色） */
    public static int get(int resId) {
        return SmartQuizApplication.getAppContext().getColor(resId);
    }

    /** 供无 Application 上下文的静态场景使用 */
    public static int get(Resources res, int resId) {
        return res.getColor(resId, null);
    }

    /**
     * 从指定 context 的主题读取颜色属性（?attr/colorXxx）。
     * 跟随当前 Activity 已叠加的主题：换肤（31 档位）、深浅色、系统动态色都生效。
     * 属性未定义或解析失败返回 0。
     */
    public static int attr(Context context, int attrResId) {
        if (context == null) {
            return 0;
        }
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(attrResId, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
            if (tv.resourceId != 0) {
                return androidx.core.content.ContextCompat.getColor(context, tv.resourceId);
            }
        }
        return 0;
    }

    /**
     * 语义色取色总入口：传入 colors.xml 的语义色资源 id，
     * 命中映射表的颜色自动改从当前主题 attr 解析（跟随换肤/深浅/动态色），
     * 其余颜色（图表色、状态色等固定色）原样返回。
     * 供 Java 动态创建控件统一使用，替代 context.getColor()。
     */
    public static int get(Context context, int resId) {
        if (context == null) {
            return 0;
        }
        int attrId = ATTR_BY_RES.get(resId, 0);
        if (attrId == 0) {
            warnUnmapped(context, resId);
            return safeColor(context, resId);
        }
        int themed = attr(context, attrId);
        if (themed != 0) {
            return themed;
        }
        // 映射到了 attr 但当前主题没解析出来（换肤 overlay 缺项/自定义主题漏配）。
        // 以前这里直接返回 0（控件变透明），现在退回资源值并在 debug 报出来。
        warnMissingAttr(context, resId, attrId);
        return safeColor(context, resId);
    }

    /** 这个颜色资源是否接了主题（跟随换肤）。供主题自检页统计跟随率。 */
    public static boolean isThemeAware(int resId) {
        return ATTR_BY_RES.get(resId, 0) != 0;
    }

    /** 已接主题的颜色资源 id 快照（主题自检页用）。 */
    public static int[] themeAwareResIds() {
        int n = ATTR_BY_RES.size();
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = ATTR_BY_RES.keyAt(i);
        }
        return out;
    }

    /** 资源 → attr 的映射关系（0 = 不跟随主题）。供单测核对与旧白名单等价。 */
    static int attrForResId(int resId) {
        return ATTR_BY_RES.get(resId, 0);
    }

    /** 已接主题的颜色资源个数（映射表大小）。 */
    static int themeAwareCount() {
        return ATTR_BY_RES.size();
    }

    /**
     * 取资源色，但**不因为资源缺失而崩**（2026-11-16）。
     *
     * <p>真机踩过：{@code ThemeAuditActivity} 反射遍历 R.color 时，遇到只存在于
     * values-night 的同名色（白天模式下没有该资源），{@code context.getColor} 直接抛
     * {@code Resources$NotFoundException} 把整个页面干掉。颜色取不到应当是"显示不出来"，
     * 不该是"崩溃"——这里兜成透明并在 debug 记一笔。</p>
     */
    private static int safeColor(Context context, int resId) {
        try {
            return context.getColor(resId);
        } catch (Throwable t) {
            if (isDebuggable(context)) {
                android.util.Log.w(TAG, "颜色资源取不到（该配置下不存在）: " + nameOf(context, resId));
            }
            return 0;
        }
    }

    private static void warnUnmapped(Context context, int resId) {
        if (!isDebuggable(context)) return;
        if (!WARNED.add(resId)) return;
        android.util.Log.w(TAG, "颜色未接主题: " + nameOf(context, resId) + " → 固定值"
                + "（需要跟随换肤就把它加进 ThemeColors.ATTR_BY_RES）");
    }

    private static void warnMissingAttr(Context context, int resId, int attrId) {
        if (!isDebuggable(context)) return;
        // 用 resId 的去重键，避免同一颜色在缺 attr 时刷屏
        if (!WARNED.add(resId ^ 0x5F5F)) return;
        android.util.Log.w(TAG, "主题缺少属性: " + nameOf(context, resId)
                + " 映射到 attr " + nameOfAttr(context, attrId) + " 解析为 0，已退回资源值");
    }

    /** 是否可调试包（本项目未开 buildConfig 特性，用 ApplicationInfo 标志位判断）。 */
    private static boolean isDebuggable(Context context) {
        try {
            return (context.getApplicationInfo().flags
                    & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String nameOf(Context context, int resId) {
        try {
            return context.getResources().getResourceName(resId);
        } catch (Throwable t) {
            return "#" + Integer.toHexString(resId);
        }
    }

    private static String nameOfAttr(Context context, int attrId) {
        try {
            return context.getResources().getResourceName(attrId);
        } catch (Throwable t) {
            return "#" + Integer.toHexString(attrId);
        }
    }
}
