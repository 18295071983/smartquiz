package com.oilquiz.app.vnc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 浮层（状态条 / 悬浮按钮）必须能拖动，并且位置被记住。
 *
 * <p>用户原话：「动态按钮不能拖动啊，为何是固定位置」—— 之前这些浮层是 layout_gravity 钉死的。
 *
 * <p>为什么不靠 adb 注入触摸来验：这台机器（MIUI）明确拒绝 shell 注入输入事件
 * （{@code SecurityException: Injecting input events requires the INJECT_EVENTS permission}），
 * 所以这里自己合成 MotionEvent 直接派发给 View —— 走的是同一条 onTouch 路径，可回归。
 */
@RunWith(AndroidJUnit4.class)
public class FloatingDragDeviceTest {

    private static final String PREF = "vnc_prefs";

    private static Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** 合成一次拖动：按下 → 5 个 MOVE → 抬起 */
    private static void drag(View v, float x1, float y1, float x2, float y2) {
        long t = SystemClock.uptimeMillis();
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x1, y1, 0));
        for (int i = 1; i <= 5; i++) {
            float x = x1 + (x2 - x1) * i / 5f;
            float y = y1 + (y2 - y1) * i / 5f;
            v.dispatchTouchEvent(MotionEvent.obtain(t, t + i * 16L, MotionEvent.ACTION_MOVE, x, y, 0));
        }
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 120L, MotionEvent.ACTION_UP, x2, y2, 0));
    }

    /** 原地按下抬起（不该被当成拖动） */
    private static void tap(View v, float x, float y) {
        long t = SystemClock.uptimeMillis();
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 60L, MotionEvent.ACTION_UP, x, y, 0));
    }

    @Test
    public void floatingLayerCanBeDraggedAndPositionIsRemembered() {
        Context base = ctx();
        Context themed = new android.view.ContextThemeWrapper(base, base.getApplicationInfo().theme);
        String key = "test-drag";
        SharedPreferences sp = base.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().remove(key + "_x").remove(key + "_y").commit();

        // 造一个 1000x2000 的"屏幕"和一个 200x100 的浮层，手动完成 measure/layout（测试环境没有真实窗口）
        FrameLayout root = new FrameLayout(themed);
        TextView chip = new TextView(themed);
        chip.setText("拖我");
        root.addView(chip, new FrameLayout.LayoutParams(200, 100));
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1000, 2000);

        final boolean[] tapped = {false};
        FloatingDrag.attach(chip, root, key, () -> tapped[0] = true);

        float x0 = chip.getX();
        drag(chip, 120, 140, 420, 340);   // 手指移动 (+300, +200)
        System.out.println("[FloatingDrag] 拖动前 x=" + x0 + " → 拖动后 x=" + chip.getX()
                + " y=" + chip.getY());
        assertEquals("浮层应跟着手指走（x）", x0 + 300f, chip.getX(), 2f);
        assertEquals("浮层应跟着手指走（y）", 200f, chip.getY(), 2f);
        assertFalse("拖动不应被当成点击", tapped[0]);

        float savedX = sp.getFloat(key + "_x", -1f);
        float savedY = sp.getFloat(key + "_y", -1f);
        System.out.println("[FloatingDrag] 记住的位置 x=" + savedX + " y=" + savedY);
        assertEquals("位置应写进 SharedPreferences（x）", chip.getX(), savedX, 1f);
        assertEquals("位置应写进 SharedPreferences（y）", chip.getY(), savedY, 1f);

        // 往屏幕外拖：必须被拉回来（不能甩到看不见的地方）
        drag(chip, 420, 340, 5000, 5000);
        assertEquals("超出右边界要 clamp", 1000f - 200f, chip.getX(), 2f);
        assertEquals("超出下边界要 clamp", 2000f - 100f, chip.getY(), 2f);

        // 没移动的一下仍然是点击（≡ 要能点开状态条）
        tap(chip, 500, 500);
        assertTrue("原地一下应算点击", tapped[0]);

        System.out.println("[FloatingDrag] 拖动 / 记忆 / 边界 / 点击 四项行为全部通过");
    }
}
