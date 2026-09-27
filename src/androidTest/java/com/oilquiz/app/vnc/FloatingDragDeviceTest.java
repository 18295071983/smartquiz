package com.oilquiz.app.vnc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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
 * 浮层的拖动行为：**跟手 → 松手吸附最近边缘 → 记住位置 → 越界 clamp → 原地仍算点击 → 长按回调**。
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
    private static final int SCREEN_W = 1000;
    private static final int SCREEN_H = 2000;
    private static final int CHIP_W = 200;
    private static final int CHIP_H = 100;

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

    /** 造一个 1000x2000 的"屏幕"和一个 200x100 的浮层，并手动完成 measure/layout（测试环境没有真实窗口） */
    private static FrameLayout makeScreen(Context themed, TextView chip) {
        FrameLayout root = new FrameLayout(themed);
        root.addView(chip, new FrameLayout.LayoutParams(CHIP_W, CHIP_H));
        root.measure(View.MeasureSpec.makeMeasureSpec(SCREEN_W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(SCREEN_H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, SCREEN_W, SCREEN_H);
        return root;
    }

    @Test
    public void dragSnapsToNearestEdgeAndPositionIsRemembered() {
        Context base = ctx();
        Context themed = new android.view.ContextThemeWrapper(base, base.getApplicationInfo().theme);
        String key = "test-drag";
        SharedPreferences sp = base.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().remove(key + "_x").remove(key + "_y").commit();

        TextView chip = new TextView(themed);
        chip.setText("拖我");
        FrameLayout root = makeScreen(themed, chip);

        final boolean[] tapped = {false};
        FloatingDrag.attach(chip, root, key, () -> tapped[0] = true);

        // ① 跟手 + 松手吸附：手指从 (120,140) 拖到 (420,340)，浮层中心落在左半边 → 吸附左边缘 x=0
        float x0 = chip.getX();
        drag(chip, 120, 140, 420, 340);
        System.out.println("[FloatingDrag] 起始 x=" + x0 + "，拖 (+300,+200) 后 x=" + chip.getX()
                + " y=" + chip.getY() + "（应吸附左边缘 0，y 保持 200）");
        assertEquals("松手应吸附到左边缘", 0f, chip.getX(), 2f);
        assertEquals("纵向不吸附，应停在手指位置", 200f, chip.getY(), 2f);
        assertFalse("拖动不应被当成点击", tapped[0]);

        float savedX = sp.getFloat(key + "_x", -1f);
        float savedY = sp.getFloat(key + "_y", -1f);
        System.out.println("[FloatingDrag] 记住的位置 x=" + savedX + " y=" + savedY);
        assertEquals("位置应写进 SharedPreferences（x）", chip.getX(), savedX, 1f);
        assertEquals("位置应写进 SharedPreferences（y）", chip.getY(), savedY, 1f);

        // ② 拖到右半边 → 吸附右边缘 800
        drag(chip, 200, 200, 700, 200);
        System.out.println("[FloatingDrag] 右半边松手 → x=" + chip.getX() + "（应吸附右边缘 800）");
        assertEquals("松手应吸附到右边缘", (float) (SCREEN_W - CHIP_W), chip.getX(), 2f);

        // ③ 往屏幕外拖：先 clamp 再吸附，不能跑出屏幕
        drag(chip, 700, 200, 5000, 5000);
        System.out.println("[FloatingDrag] 拖出屏幕 → x=" + chip.getX() + " y=" + chip.getY()
                + "（应 clamp 到 800 / 1900）");
        assertEquals("超出右边界要 clamp", (float) (SCREEN_W - CHIP_W), chip.getX(), 2f);
        assertEquals("超出下边界要 clamp", (float) (SCREEN_H - CHIP_H), chip.getY(), 2f);

        // ④ 没移动的一下仍然是点击（≡ 要能点开状态条）
        tap(chip, 500, 500);
        assertTrue("原地一下应算点击", tapped[0]);
        System.out.println("[FloatingDrag] 跟手 / 吸附 / 记忆 / clamp / 点击 全部通过");
    }

    @Test
    public void longPressFiresAndIsNotTreatedAsTap() throws Exception {
        Context base = ctx();
        Context themed = new android.view.ContextThemeWrapper(base, base.getApplicationInfo().theme);
        String key = "test-longpress";
        base.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .remove(key + "_x").remove(key + "_y").commit();

        TextView chip = new TextView(themed);
        FrameLayout root = makeScreen(themed, chip);

        final boolean[] tapped = {false};
        final boolean[] longPressed = {false};
        FloatingDrag.attach(chip, root, key, () -> tapped[0] = true, () -> longPressed[0] = true);

        long t = SystemClock.uptimeMillis();
        chip.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 300, 300, 0));
        Thread.sleep(ViewConfiguration.getLongPressTimeout() + 250L);
        chip.dispatchTouchEvent(MotionEvent.obtain(t, t + 900L, MotionEvent.ACTION_UP, 300, 300, 0));

        System.out.println("[FloatingDrag] 长按回调=" + longPressed[0] + "，是否被误判点击=" + tapped[0]);
        assertTrue("按住不动超过长按时长应触发长按", longPressed[0]);
        assertFalse("长按不应同时算点击", tapped[0]);
    }
}
