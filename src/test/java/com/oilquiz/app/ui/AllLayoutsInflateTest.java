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
 * <p>口径：应用主题 {@code Theme_OilQuizApp} + {@code FrameLayout} 作父容器（不 attach），
 * 逐个 inflate {@code R.layout} 里的每个布局。已知不可在纯 JVM 环境 inflate 的布局
 * （依赖真实 Activity/Window/WebView 容器）列在 {@link #ALLOWED_FAILURES}，新增要写理由。</p>
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

    /** 取最内层 cause，方便定位真实原因（缺 attr / 缺资源 / 自定义 View 构造抛错）。 */
    private static String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    @Test
    public void everyLayoutInflatesWithAppTheme() {
        Context ctx = com.oilquiz.app.testsupport.ThemedAppContext.get();
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
            try {
                View v = inflater.inflate(layoutId, root, false);
                if (v == null) {
                    failures.add(field.getName() + " → inflate 返回 null");
                }
            } catch (Throwable t) {
                String name = field.getName();
                if (ALLOWED_FAILURES.stream().anyMatch(s -> s.startsWith(name + ":"))) {
                    continue;
                }
                failures.add(name + " → " + t.getClass().getSimpleName() + ": " + t.getMessage()
                        + " | cause: " + rootCause(t));
            }
        }
        assertTrue("有布局在应用主题下 inflate 失败（多半是 ?attr 未定义 / drawable 引用错误 / "
                + "主题里缺资源）：\n  " + String.join("\n  ", failures), failures.isEmpty());
    }
}
