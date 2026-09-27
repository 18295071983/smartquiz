package com.oilquiz.app.vnc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.text.InputType;

/**
 * VNC 画面控件：绘制远端帧缓冲 + 触摸/键盘输入。
 *
 * <p>手势约定（比"猜长按"更可预测）：
 * <ul>
 *   <li>单指按下/拖动/抬起 = 鼠标左键按下/移动/抬起（轻点即单击）；</li>
 *   <li>双指上下滑 = 滚轮；双指捏合 = 缩放；</li>
 *   <li>工具栏「右键」按下后，下一次点击发右键（button 3）；</li>
 *   <li>软键盘通过 {@link #onCreateInputConnection} 接收，逐字翻译成 keysym。</li>
 * </ul>
 */
public class VncView extends View {

    private VncClient client;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    private float scale = 1f;
    private float panX = 0f;
    private float panY = 0f;
    private boolean fitToScreen = true;

    private boolean rightClickArmed = false;
    private boolean pointerDown = false;
    private int buttonMask = 1;

    private float lastX, lastY;
    private float twoFingerLastDist = 0f;
    private float twoFingerLastMidY = 0f;
    private boolean twoFingerActive = false;
    private float wheelAccum = 0f;
    private boolean drawLogged = false;

    public VncView(Context context) {
        super(context);
        init();
    }

    public VncView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VncView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    /**
     * 双击画面 = 在"适应屏幕"和"1:1 原始比例"之间切换（小屏上很实用）。
     *
     * <p>必须**懒创建**：GestureDetector 内部会创建 Handler，字段初始化时若所在线程没有 Looper
     * （例如仪器化用例在测试线程里 inflate 布局）会抛 "Can't create handler inside thread ..." 直接崩。
     */
    private android.view.GestureDetector gestures;

    private android.view.GestureDetector gestures() {
        if (gestures == null) {
            gestures = new android.view.GestureDetector(getContext(),
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            setFitToScreen(!fitToScreen);
                            invalidate();
                            return true;
                        }
                    });
        }
        return gestures;
    }

    public boolean isFitToScreen() {
        return fitToScreen;
    }

    /** 工具栏「滚轮↑/↓」用：在视图中心发一次滚轮事件（手指不好控制时的替代操作） */
    public void sendWheel(boolean up) {
        VncClient c = client;
        if (c == null) {
            return;
        }
        int[] pos = new int[2];
        toRemote(getWidth() / 2f, getHeight() / 2f, pos);
        int button = up ? 8 : 16;
        c.sendPointer(button, pos[0], pos[1]);
        c.sendPointer(0, pos[0], pos[1]);
    }

    private void init() {
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(0xFF000000);
    }

    /** 画布被手指按下时回调：VNC 客户端惯例是"一碰画面就把工具条收起来"，别挡着正在操作的地方 */
    public interface OnCanvasTouchListener {
        void onCanvasTouch();
    }

    private OnCanvasTouchListener canvasTouchListener;

    public void setOnCanvasTouchListener(OnCanvasTouchListener l) {
        this.canvasTouchListener = l;
    }

    public void setClient(VncClient c) {
        this.client = c;
        invalidate();
    }

    public void setRightClickArmed(boolean armed) {
        this.rightClickArmed = armed;
    }

    public boolean isRightClickArmed() {
        return rightClickArmed;
    }

    public void setFitToScreen(boolean fit) {
        this.fitToScreen = fit;
        if (fit) {
            panX = 0;
            panY = 0;
        }
        invalidate();
    }

    public void zoomBy(float factor) {
        fitToScreen = false;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float old = scale;
        scale = clamp(scale * factor, 0.15f, 6f);
        // 以屏幕中心为锚点缩放
        float k = scale / old;
        panX = cx - (cx - panX) * k;
        panY = cy - (cy - panY) * k;
        invalidate();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------------- 绘制 ----------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        VncClient c = client;
        if (c == null) {
            return;
        }
        Bitmap bmp = c.getBitmap();
        int bmpW = c.getWidth();
        int bmpH = c.getHeight();
        if (bmp == null || bmpW <= 0 || bmpH <= 0) {
            return;
        }
        if (fitToScreen) {
            float sx = getWidth() / (float) bmpW;
            float sy = getHeight() / (float) bmpH;
            scale = Math.min(sx, sy);
            panX = (getWidth() - bmpW * scale) / 2f;
            panY = (getHeight() - bmpH * scale) / 2f;
        }
        srcRect.set(0, 0, bmpW, bmpH);
        dstRect.set(panX, panY, panX + bmpW * scale, panY + bmpH * scale);
        if (!drawLogged) {
            drawLogged = true;
            android.util.Log.i("VncView", "首帧绘制 view=" + getWidth() + "x" + getHeight()
                    + " bmp=" + bmpW + "x" + bmpH + " scale=" + scale);
        }
        synchronized (c.frameLock) {
            canvas.drawBitmap(bmp, srcRect, dstRect, paint);
        }
    }

    /** 视图坐标 → 远端帧缓冲坐标（越界会被夹到边界内） */
    private void toRemote(float vx, float vy, int[] out) {
        VncClient c = client;
        if (c == null) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        int rx = (int) ((vx - panX) / (scale == 0 ? 1 : scale));
        int ry = (int) ((vy - panY) / (scale == 0 ? 1 : scale));
        out[0] = Math.max(0, Math.min(c.getWidth() - 1, rx));
        out[1] = Math.max(0, Math.min(c.getHeight() - 1, ry));
    }

    // ---------------- 触摸 ----------------

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && canvasTouchListener != null) {
            canvasTouchListener.onCanvasTouch();
        }
        VncClient c = client;
        if (c == null || !c.isRunning()) {
            return true;
        }
        gestures().onTouchEvent(ev);
        int[] pos = new int[2];
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                toRemote(ev.getX(), ev.getY(), pos);
                buttonMask = rightClickArmed ? 4 : 1;
                if (rightClickArmed) {
                    rightClickArmed = false;
                }
                c.sendPointer(buttonMask, pos[0], pos[1]);
                pointerDown = true;
                twoFingerActive = false;
                lastX = ev.getX();
                lastY = ev.getY();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // 第二根手指进来：先松开鼠标键，改成滚动/缩放
                if (pointerDown) {
                    toRemote(ev.getX(), ev.getY(), pos);
                    c.sendPointer(0, pos[0], pos[1]);
                    pointerDown = false;
                }
                twoFingerActive = true;
                twoFingerLastDist = fingerDistance(ev);
                twoFingerLastMidY = midY(ev);
                wheelAccum = 0f;
                break;
            case MotionEvent.ACTION_MOVE:
                if (twoFingerActive && ev.getPointerCount() >= 2) {
                    handleTwoFinger(ev);
                } else if (pointerDown) {
                    toRemote(ev.getX(), ev.getY(), pos);
                    c.sendPointer(buttonMask, pos[0], pos[1]);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                twoFingerActive = false;
                wheelAccum = 0f;
                if (ev.getPointerCount() - 1 == 1) {
                    // 还剩一根手指：不重新按下按钮，避免误拖
                    pointerDown = false;
                }
                break;
            case MotionEvent.ACTION_UP:
                toRemote(ev.getX(), ev.getY(), pos);
                if (pointerDown) {
                    c.sendPointer(0, pos[0], pos[1]);
                    pointerDown = false;
                }
                performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
                if (pointerDown) {
                    toRemote(ev.getX(), ev.getY(), pos);
                    c.sendPointer(0, pos[0], pos[1]);
                    pointerDown = false;
                }
                twoFingerActive = false;
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private static float fingerDistance(MotionEvent ev) {
        float dx = ev.getX(0) - ev.getX(1);
        float dy = ev.getY(0) - ev.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private static float midY(MotionEvent ev) {
        return (ev.getY(0) + ev.getY(1)) / 2f;
    }

    private void handleTwoFinger(MotionEvent ev) {
        VncClient c = client;
        if (c == null) {
            return;
        }
        float dist = fingerDistance(ev);
        float mid = midY(ev);
        float distDelta = Math.abs(dist - twoFingerLastDist);
        float moveDelta = mid - twoFingerLastMidY;
        float threshold = 12f * getResources().getDisplayMetrics().density;

        if (distDelta > threshold) {
            // 捏合缩放
            float k = dist / (twoFingerLastDist == 0 ? dist : twoFingerLastDist);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float old = scale;
            scale = clamp(scale * k, 0.15f, 6f);
            float kk = scale / old;
            panX = cx - (cx - panX) * kk;
            panY = cy - (cy - panY) * kk;
            fitToScreen = false;
            twoFingerLastDist = dist;
            invalidate();
            return;
        }
        if (Math.abs(moveDelta) > threshold) {
            int[] pos = new int[2];
            toRemote(ev.getX(0), ev.getY(0), pos);
            int button = moveDelta < 0 ? 8 : 16;      // 8=滚轮上, 16=滚轮下
            c.sendPointer(button, pos[0], pos[1]);
            c.sendPointer(0, pos[0], pos[1]);
            twoFingerLastMidY = mid;
        }
    }

    // ---------------- 键盘 ----------------

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        out.inputType = InputType.TYPE_NULL;
        out.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                sendText(text);
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                sendAndroidKey(event);
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < beforeLength; i++) {
                    VncClient c = client;
                    if (c != null) {
                        c.sendKeyTap(VncKeysym.XK_BackSpace);
                    }
                }
                return true;
            }
        };
    }

    /** 文本 → 逐字符 keysym（仅 Latin-1；中文请用工具栏「粘贴」走剪贴板） */
    public void sendText(CharSequence text) {
        VncClient c = client;
        if (c == null || text == null) {
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            int ks = VncKeysym.keysymForChar(text.charAt(i));
            if (ks != 0) {
                c.sendKeyTap(ks);
            }
        }
    }

    public void sendAndroidKey(KeyEvent event) {
        VncClient c = client;
        if (c == null) {
            return;
        }
        int ks = VncKeysym.keysymFor(event);
        if (ks == 0) {
            return;
        }
        int[] mods = VncKeysym.modifiersFor(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            for (int m : mods) {
                c.sendKey(m, true);
            }
            c.sendKey(ks, true);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            c.sendKey(ks, false);
            for (int i = mods.length - 1; i >= 0; i--) {
                c.sendKey(mods[i], false);
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        sendAndroidKey(event);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        sendAndroidKey(event);
        return true;
    }

    @SuppressWarnings("unused")
    private static boolean isFromSource(KeyEvent e) {
        return (e.getSource() & InputDevice.SOURCE_KEYBOARD) != 0;
    }
}
