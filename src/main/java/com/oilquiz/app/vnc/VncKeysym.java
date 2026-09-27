package com.oilquiz.app.vnc;

import android.view.KeyEvent;

/**
 * Android KeyEvent / 文本 → X11 keysym 映射。
 *
 * <p>X11 里 Latin-1 的 keysym 就是字符码本身（'a'=0x61、'A'=0x41、'!'=0x21），
 * 所以可打印字符直接用字符码；其余特殊键查表。我们只发**已解析好的** keysym，
 * 再按需包一层 Ctrl/Alt 修饰键（Shift 已经体现在字符本身，不再额外发 Shift）。
 */
public final class VncKeysym {

    public static final int XK_BackSpace = 0xFF08;
    public static final int XK_Tab = 0xFF09;
    public static final int XK_Return = 0xFF0D;
    public static final int XK_Escape = 0xFF1B;
    public static final int XK_Delete = 0xFFFF;
    public static final int XK_Home = 0xFF50;
    public static final int XK_Left = 0xFF51;
    public static final int XK_Up = 0xFF52;
    public static final int XK_Right = 0xFF53;
    public static final int XK_Down = 0xFF54;
    public static final int XK_Page_Up = 0xFF55;
    public static final int XK_Page_Down = 0xFF56;
    public static final int XK_End = 0xFF57;
    public static final int XK_Insert = 0xFF63;
    public static final int XK_Shift_L = 0xFFE1;
    public static final int XK_Control_L = 0xFFE3;
    public static final int XK_Alt_L = 0xFFE9;
    public static final int XK_Super_L = 0xFFEB;
    public static final int XK_Caps_Lock = 0xFFE5;
    public static final int XK_F1 = 0xFFBE;

    private VncKeysym() {
    }

    /** 把 Android 按键翻译成 X11 keysym；不认识则返回 0 */
    public static int keysymFor(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return XK_Return;
            case KeyEvent.KEYCODE_DEL:
                return XK_BackSpace;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return XK_Delete;
            case KeyEvent.KEYCODE_TAB:
                return XK_Tab;
            case KeyEvent.KEYCODE_ESCAPE:
                return XK_Escape;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return XK_Left;
            case KeyEvent.KEYCODE_DPAD_UP:
                return XK_Up;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return XK_Right;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return XK_Down;
            case KeyEvent.KEYCODE_MOVE_HOME:
                return XK_Home;
            case KeyEvent.KEYCODE_MOVE_END:
                return XK_End;
            case KeyEvent.KEYCODE_PAGE_UP:
                return XK_Page_Up;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return XK_Page_Down;
            case KeyEvent.KEYCODE_INSERT:
                return XK_Insert;
            case KeyEvent.KEYCODE_CAPS_LOCK:
                return XK_Caps_Lock;
            case KeyEvent.KEYCODE_F1:
            case KeyEvent.KEYCODE_F2:
            case KeyEvent.KEYCODE_F3:
            case KeyEvent.KEYCODE_F4:
            case KeyEvent.KEYCODE_F5:
            case KeyEvent.KEYCODE_F6:
            case KeyEvent.KEYCODE_F7:
            case KeyEvent.KEYCODE_F8:
            case KeyEvent.KEYCODE_F9:
            case KeyEvent.KEYCODE_F10:
            case KeyEvent.KEYCODE_F11:
            case KeyEvent.KEYCODE_F12:
                return XK_F1 + (e.getKeyCode() - KeyEvent.KEYCODE_F1);
            default:
                break;
        }
        int shiftOnly = e.getMetaState() & KeyEvent.META_SHIFT_ON;
        int c = e.getUnicodeChar(shiftOnly);
        return keysymForChar((char) c);
    }

    /** Ctrl / Alt 修饰键（Shift 不在此列，见类注释） */
    public static int[] modifiersFor(KeyEvent e) {
        int meta = e.getMetaState();
        int n = 0;
        if ((meta & KeyEvent.META_CTRL_ON) != 0) {
            n++;
        }
        if ((meta & KeyEvent.META_ALT_ON) != 0) {
            n++;
        }
        int[] out = new int[n];
        int i = 0;
        if ((meta & KeyEvent.META_CTRL_ON) != 0) {
            out[i++] = XK_Control_L;
        }
        if ((meta & KeyEvent.META_ALT_ON) != 0) {
            out[i] = XK_Alt_L;
        }
        return out;
    }

    /** 可打印的 Latin-1 字符 → keysym；中文等非 Latin-1 返回 0（X11 无对应 keysym，走剪贴板） */
    public static int keysymForChar(char c) {
        if (c >= 0x20 && c <= 0xFF) {
            return c;
        }
        return 0;
    }
}
