package com.oilquiz.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.oilquiz.app.ui.widget.LiveDotsView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;

/**
 * Agent 执行动效的可证伪点（2026-11-18）。
 *
 * <p>为什么需要：这两处动效（{@link LiveDotsView} 的三点呼吸、{@link UiFx#shimmer} 的文本流光）
 * 出错的方式都很隐蔽——量错尺寸会撑高折叠头、{@code setShader} 漏清会污染控件配色、
 * 每帧重绑时重建 shader 会闪。这里钉住可断言的几条：</p>
 *
 * <ol>
 *   <li>点阵的测量尺寸 = 三颗点 + 两道间隔（21×14dp），不再随字体缩放撑高行；</li>
 *   <li>流光给 {@code Paint} 挂的是 {@link LinearGradient}（只做 alpha 调制，不写死颜色）；</li>
 *   <li>流光幂等：重复 {@code shimmer(tv, true)} 不叠新 shader，{@code stopShimmer} 摘干净；</li>
 *   <li>没开过就 stop 不创建状态；点阵 start/stop 在未 attach 时也不炸。</li>
 * </ol>
 *
 * <p>不做像素级渲染断言：本机 Robolectric 的 native runtime 缺
 * {@code robolectric-nativeruntime.dll}（Windows 4.10.3），位图绘制会直接
 * {@code UnsatisfiedLinkError}。渲染观感靠真机；这里只钉行为与几何。</p>
 */
@RunWith(AndroidJUnit4.class)
public class AgentMotionTest {

    // ---------------- LiveDotsView ----------------

    @Test
    public void liveDotsMeasuresToDotStripSize() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LiveDotsView view = new LiveDotsView(activity);
        measure(view);

        // 3 颗直径 5dp + 2 道 3dp 间隔 = 21dp；高度 14dp（见 LiveDotsView 常量）
        float density = activity.getResources().getDisplayMetrics().density;
        assertEquals("点阵宽度应等于 3 颗点 + 2 道间隔",
                Math.round(21 * density), view.getMeasuredWidth());
        assertEquals("点阵高度应固定 14dp（比 13sp 文字略高，但不撑行）",
                Math.round(14 * density), view.getMeasuredHeight());
    }

    @Test
    public void liveDotsStartStopIsIdempotent() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LiveDotsView view = new LiveDotsView(activity);
        view.setDotColor(0xFF7C5CFF);
        measure(view);

        view.start(); // 未 attach 时只记意图，不应抛异常
        view.stop();
        view.stop(); // 幂等：重复 stop 不应留下副作用
        assertEquals("stop 后不应留下 alpha 副作用", 1f, view.getAlpha(), 0f);
    }

    @Test
    public void singleDotModeMeasuresToStatusDotSize() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LiveDotsView view = new LiveDotsView(activity);
        view.setDotCount(1); // 工具行/命令卡/子代理卡头部的行内状态点
        measure(view);

        float density = activity.getResources().getDisplayMetrics().density;
        assertEquals("单点宽 = 一颗点 5dp", Math.round(5 * density), view.getMeasuredWidth());
        assertEquals("高度仍 14dp（与三点版共用同一行高）",
                Math.round(14 * density), view.getMeasuredHeight());
    }

    // ---------------- UiFx.shimmer ----------------

    @Test
    public void shimmerAttachesGradientOnceAndStopsCleanly() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout root = new LinearLayout(activity);
        TextView tv = new TextView(activity);
        tv.setText("正在运行 pwsh");
        tv.setTextColor(0xFF202020);
        root.addView(tv);
        activity.setContentView(root);
        measure(root);
        measure(tv);

        assertNull("初始不该有 shader", tv.getPaint().getShader());

        UiFx.shimmer(tv, true);
        Shader shader = tv.getPaint().getShader();
        assertNotNull("shimmer(true) 没给 Paint 挂 shader", shader);
        assertTrue("流光应该是 LinearGradient（只调制 alpha），实际是 " + shader.getClass(),
                shader instanceof LinearGradient);

        // 流式每 120ms 重绑一次：重复调用不能叠新 shader
        UiFx.shimmer(tv, true);
        assertSame("重复 shimmer(true) 重建了 shader（会每帧闪烁）", shader, tv.getPaint().getShader());

        UiFx.shimmer(tv, false);
        assertNull("stopShimmer 没摘掉 shader（会污染控件配色）", tv.getPaint().getShader());
    }

    @Test
    public void shimmerIsSkippedWhenItWasNeverStarted() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TextView tv = new TextView(activity);
        tv.setText("已运行 2 个工具");
        tv.setTextColor(0xFF202020);
        activity.setContentView(tv);

        UiFx.stopShimmer(tv); // 没开过就停：不应创建状态，也不应有 shader
        assertNull(tv.getPaint().getShader());
    }

    // ---------------- 工具 ----------------

    private static void measure(View view) {
        int widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        view.measure(widthSpec, heightSpec);
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }
}
