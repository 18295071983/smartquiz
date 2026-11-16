package com.oilquiz.app.ui;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import com.oilquiz.app.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 全量布局 inflate 冒烟（2026-11-16）。
 *
 * <p><b>为什么需要</b>：本轮把大量布局从固定色改成了 {@code ?attr/colorXxx}、把尺寸改成
 * {@code @dimen}、把 drawable 改成主题感知。这类改动的失败模式都是"运行期才炸"：
 * attr 未定义 → inflate 抛 {@code Failed to resolve attribute}；drawable 引错 → inflate 失败；
 * 资源名拼错 → 编译过但运行崩。JVM 里没有真机页面巡检，就用这个测试把
 * <b>所有 layout 都按应用主题 inflate 一遍</b>，任何异常都算失败——成本几秒，能挡住绝大多数低级事故。</p>
 *
 * <p>覆盖两种环境：<b>浅色</b>与<b>夜间</b>（{@code values-night} 资源），因为 attrs 在夜间主题里
 * 也要能解析；夜间只跑一遍即可发现"只在 values 定义、night 缺失"的坑。</p>
 *
 * <p>口径：应用主题 {@code Theme_OilQuizApp} + {@code FrameLayout} 作父容器（不 attach），
 * 逐个 inflate {@code R.layout} 里的每个布局。已知不可在纯 JVM 环境 inflate 的布局
 * （依赖真实 Activity/Window/Application 静态上下文）列在 {@link #ALLOWED_FAILURES}，新增要写理由。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AllLayoutsInflateTest {

    /** 需要真实 Activity/Window 环境、JVM 里无法 inflate 的布局（每个都写理由）。 */
    private static final List<String> ALLOWED_FAILURES = List.of(
            // 以下 4 个布局的自定义 View 构造期会走 ThemeColors 的 app-context 兜底
            // （SmartQuizApplication.getAppContext()），而单测环境按 robolectric.properties 用的是
            // 裸 android.app.Application（真 Application 会 System.loadLibrary("crypto_chaquopy")），
            // 静态 instance 为 null → NPE。属测试环境限制，真机不受影响。
            "activity_ai_service_init: 自定义 View 依赖 SmartQuizApplication 静态上下文（Robolectric 用裸 Application）",
            "activity_weather_detail: CircularGaugeView 同上",
            "item_weather_daily: TempRangeBarView 同上",
            "view_download_progress: GradientRingProgress 同上"
    );

    @Test
    public void everyLayoutInflatesWithAppTheme() {
        assertNoFailures(inflateAll(ThemedContext.get()), "浅色");
    }

    /** 夜间（values-night）同样要把每个布局 inflate 通；缺 night 资源的坑在这里暴露。 */
    @Test
    @Config(qualifiers = "night")
    public void everyLayoutInflatesWithAppThemeAtNight() {
        assertNoFailures(inflateAll(ThemedContext.get()), "夜间");
    }

    /**
     * 换预设色（ThemeOverlay）后，所有布局仍必须能 inflate。
     *
     * <p>主题换肤在运行时是 {@code theme.applyStyle(ThemeOverlay.SmartQuiz.Xxx, true)}，
     * overlay 只覆盖一部分 attr；如果某个布局用到的 attr 在所有 overlay 里都缺、又没在基础主题里兜底，
     * 换肤后打开该页面就会崩。这里抽 5 个档位（蓝/绿/Hue00/Hue15/Hue23）做代表，
     * 覆盖冷/暖/中性色相。</p>
     */
    @Test
    public void everyLayoutInflatesUnderPresetOverlays() {
        int[] overlays = {
                R.style.ThemeOverlay_SmartQuiz_Blue,
                R.style.ThemeOverlay_SmartQuiz_Green,
                R.style.ThemeOverlay_SmartQuiz_Hue00,
                R.style.ThemeOverlay_SmartQuiz_Hue15,
                R.style.ThemeOverlay_SmartQuiz_Hue23
        };
        List<String> failures = new ArrayList<>();
        for (int overlay : overlays) {
            android.view.ContextThemeWrapper ctx = new android.view.ContextThemeWrapper(
                    ThemedContext.get(), R.style.Theme_OilQuizApp);
            ctx.getTheme().applyStyle(overlay, true);
            try {
                for (String f : inflateAll(ctx)) {
                    failures.add(ctx.getResources().getResourceEntryName(overlay) + " → " + f);
                }
            } catch (Throwable t) {
                failures.add("overlay " + overlay + " 应用失败: " + t);
            }
        }
        assertNoFailures(failures, "换肤");
    }

    /** 夜间 + 换肤（values-night/theme_overlays.xml）同样要能 inflate 全部布局。 */
    @Test
    @Config(qualifiers = "night")
    public void everyLayoutInflatesUnderPresetOverlaysAtNight() {
        int[] overlays = {
                R.style.ThemeOverlay_SmartQuiz_Blue,
                R.style.ThemeOverlay_SmartQuiz_Hue00,
                R.style.ThemeOverlay_SmartQuiz_Hue23
        };
        List<String> failures = new ArrayList<>();
        for (int overlay : overlays) {
            android.view.ContextThemeWrapper ctx = new android.view.ContextThemeWrapper(
                    ThemedContext.get(), R.style.Theme_OilQuizApp);
            ctx.getTheme().applyStyle(overlay, true);
            try {
                for (String f : inflateAll(ctx)) {
                    failures.add(ctx.getResources().getResourceEntryName(overlay) + " → " + f);
                }
            } catch (Throwable t) {
                failures.add("overlay " + overlay + " 应用失败: " + t);
            }
        }
        assertNoFailures(failures, "夜间换肤");
    }

    // ---------------- 工具 ----------------

    private static void assertNoFailures(List<String> failures, String mode) {
        assertTrue("有布局在应用主题（" + mode + "）下 inflate 失败（多半是 ?attr 未定义 / drawable 引用错误 / "
                + "主题里缺资源）：\n  " + String.join("\n  ", failures), failures.isEmpty());
    }

    private static List<String> inflateAll(Context ctx) {
        LayoutInflater inflater = LayoutInflater.from(ctx);
        FrameLayout root = new FrameLayout(ctx);
        List<String> failures = new ArrayList<>();
        for (Field field : R.layout.class.getFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            int layoutId;
            try {
                layoutId = field.getInt(null);
            } catch (Exception e) {
                continue;
            }
            String name = field.getName();
            if (ALLOWED_FAILURES.stream().anyMatch(s -> s.startsWith(name + ":"))) {
                continue;
            }
            try {
                View v = inflater.inflate(layoutId, root, false);
                if (v == null) {
                    failures.add(name + " → inflate 返回 null");
                }
            } catch (Throwable t) {
                failures.add(name + " → " + t.getClass().getSimpleName() + ": " + t.getMessage()
                        + " | cause: " + rootCause(t));
            }
        }
        return failures;
    }

    /** 取最内层 cause，方便定位真实原因（缺 attr / 缺资源 / 自定义 View 构造抛错）。 */
    private static String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    /** 已套应用主题的应用上下文（见 ThemedAppContext 的说明）。 */
    private static final class ThemedContext {
        static Context get() {
            return com.oilquiz.app.testsupport.ThemedAppContext.get();
        }
    }
}
