package com.oilquiz.app.vnc;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.tool.TermuxEnvInstaller;

/**
 * 图形界面（VNC）：把 Linux 容器里的 X11 桌面显示在答题宝里。
 *
 * <p>整条链路：本页自研的 RFB 客户端 → 127.0.0.1:5900 → Termux 里常驻的 proot 会话
 * （TigerVNC Xvnc）。X11/VNC 服务端在容器里跑（App targetSdk 35 不能 execve
 * 私有目录二进制，放不进 App 进程），客户端完全内置，不用装任何第三方 VNC App。
 *
 * <p><b>控件排布参考主流客户端（RealVNC Viewer / bVNC）</b>，三条原则：
 * <ol>
 *   <li><b>画面优先</b>：桌面铺满整屏，控件是浮层；一碰画面就自动收起，8 秒不动也自动收起
 *       （原来是一条常驻的横向滚动按钮栏，15 个按钮要横滑找，既挡画面又难用）。</li>
 *   <li><b>分组 + 状态可视</b>：连接 / 输入 / 显示 / 更多 四组，等宽按钮不滚动；
 *       右键、适应、系统栏、Ctrl、Alt、Shift 这类"开关"用选中态表示，不靠状态文字去猜。</li>
 *   <li><b>按钮可用性跟着连接状态走</b>：没连上时"连接/启动图形界面"可点、"断开"置灰；
 *       连上后反过来，避免用户去点一个没意义的按钮。</li>
 * </ol>
 */
public class VncActivity extends AppCompatActivity implements VncClient.Listener {

    private static final String PREF = "vnc_prefs";
    private static final String KEY_PORT = "port";
    private static final int DEFAULT_PORT = 5900;

    /** 修饰键与常用键的 X11 keysym（与 {@link VncKeysym} 里那套一致） */
    private static final int KS_CTRL = 0xFFE3;
    private static final int KS_ALT = 0xFFE9;
    private static final int KS_SHIFT = 0xFFE1;
    private static final int KS_ESC = 0xFF1B;
    private static final int KS_TAB = 0xFF09;
    private static final int KS_ENTER = 0xFF0D;

    /** 控制面板多久没动就自动收起（RealVNC/bVNC 都是"操作完就让画面干净"） */
    private static final long PANEL_AUTO_HIDE_MS = 8000L;

    private VncClient client;
    private VncView vncView;
    private TextView statusView;
    private TextView hintView;
    private EditText portEdit;

    private View panel;
    private TextView panelToggle;
    private View moreRow;
    private MaterialButton btnRight;
    private MaterialButton btnFit;
    private MaterialButton btnBars;
    private MaterialButton btnCtrl;
    private MaterialButton btnAlt;
    private MaterialButton btnShift;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean awaitingGui = false;
    private int retryCount = 0;
    private int autoRetry = 0;
    private boolean destroyed = false;
    /** 已收到的帧数 / 最后一帧时间：状态行直接显示，黑屏时一眼分清"没收到帧"还是"没画出来" */
    private volatile int frameCount = 0;
    private volatile long lastFrameAt = 0L;
    private int connectedW;
    private int connectedH;
    private String connectedName = "";

    /** 自动收起面板 */
    private final Runnable autoHide = () -> togglePanel(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 自带状态胶囊/控制面板，不再用系统 ActionBar：屏幕全留给桌面
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        setContentView(R.layout.activity_vnc);
        applyImmersive();

        vncView = findViewById(R.id.vnc_view);
        statusView = findViewById(R.id.vnc_status);
        hintView = findViewById(R.id.vnc_hint);
        portEdit = findViewById(R.id.vnc_port);

        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        portEdit.setText(String.valueOf(sp.getInt(KEY_PORT, DEFAULT_PORT)));
        portEdit.setInputType(InputType.TYPE_CLASS_NUMBER);

        client = new VncClient(this);
        vncView.setClient(client);
        // 手指一碰画面就把面板收起来 —— 面板永远不挡着正在操作的地方
        vncView.setOnCanvasTouchListener(() -> {
            if (panel != null && panel.getVisibility() == View.VISIBLE) {
                togglePanel(false);
            }
        });

        wireControls();
        applyInsetsPadding(findViewById(R.id.vnc_root));

        // 测试专用：给了 auto_finish_ms 就自己关掉（让它的客户端断开，不干扰后续用例）
        long autoFinish = getIntent().getLongExtra("auto_finish_ms", 0L);
        if (autoFinish > 0) {
            handler.postDelayed(this::finish, autoFinish);
        }
        hint("点「启动图形界面」等它连上即可。轻点=左键，拖动=拖拽，双指滑=滚轮，双指捏合=缩放；"
                + "「右键」开关打开后，下一次点击就是右键。点「帮助」看全部操作。");
        handler.postDelayed(this::connectNow, 400);
    }

    /** 一次性把所有控件的点击/选中态接好（原来是一长串 findViewById，散落在 onCreate 里不好维护） */
    private void wireControls() {
        panel = findViewById(R.id.vnc_controls);
        panelToggle = findViewById(R.id.btn_vnc_controls);
        moreRow = findViewById(R.id.vnc_more);
        btnRight = findViewById(R.id.btn_vnc_right);
        btnFit = findViewById(R.id.btn_vnc_fit);
        btnBars = findViewById(R.id.btn_vnc_bars);
        btnCtrl = findViewById(R.id.btn_vnc_ctrl);
        btnAlt = findViewById(R.id.btn_vnc_alt);
        btnShift = findViewById(R.id.btn_vnc_shift);

        for (MaterialButton b : new MaterialButton[]{btnRight, btnFit, btnBars, btnCtrl, btnAlt, btnShift}) {
            b.setCheckable(true);
            b.setChecked(false);
            setToggleLook(b, false);
        }
        panelToggle.setText("≡");

        panelToggle.setOnClickListener(v -> togglePanel(panel.getVisibility() != View.VISIBLE));
        findViewById(R.id.btn_vnc_more).setOnClickListener(v -> {
            boolean show = moreRow.getVisibility() != View.VISIBLE;
            moreRow.setVisibility(show ? View.VISIBLE : View.GONE);
            ((MaterialButton) v).setText(show ? "收起" : "更多");
            scheduleAutoHide();
        });

        // ① 连接
        findViewById(R.id.btn_vnc_gui).setOnClickListener(v -> startGui());
        findViewById(R.id.btn_vnc_connect).setOnClickListener(v -> {
            awaitingGui = false;
            retryCount = 0;
            autoRetry = 0;
            connectNow();
        });
        findViewById(R.id.btn_vnc_disconnect).setOnClickListener(v -> {
            awaitingGui = false;
            releaseModifiers();
            client.stop();
            status("已断开");
            updateConnectionButtons();
        });

        // ② 输入
        findViewById(R.id.btn_vnc_keyboard).setOnClickListener(v -> {
            vncView.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(vncView, InputMethodManager.SHOW_IMPLICIT);
                status("键盘已打开：字符按 keysym 发给远端（中文请用「粘贴」）");
            }
        });
        btnRight.setOnClickListener(v -> {
            boolean armed = !vncView.isRightClickArmed();
            vncView.setRightClickArmed(armed);
            setToggleLook(btnRight, armed);
            status(armed ? "右键已就绪：下一次点击发右键" : "已取消右键");
        });
        findViewById(R.id.btn_vnc_paste).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) {
                Toast.makeText(this, "剪贴板是空的", Toast.LENGTH_SHORT).show();
                return;
            }
            CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            client.sendClipboard(String.valueOf(text));
            status("已把剪贴板发给远端（在远端 Ctrl+V 粘贴）");
        });
        // 修饰键做成"开关"：开着就等于一直按住，配合触摸点击能发 Ctrl+点击 这类组合
        btnCtrl.setOnClickListener(v -> toggleModifier(btnCtrl, KS_CTRL, "Ctrl"));
        btnAlt.setOnClickListener(v -> toggleModifier(btnAlt, KS_ALT, "Alt"));
        btnShift.setOnClickListener(v -> toggleModifier(btnShift, KS_SHIFT, "Shift"));

        // ③ 显示
        btnFit.setOnClickListener(v -> {
            boolean fit = !vncView.isFitToScreen();
            vncView.setFitToScreen(fit);
            setToggleLook(btnFit, fit);
            status(fit ? "已适应屏幕" : "已切到 1:1（可拖动查看）");
        });
        findViewById(R.id.btn_vnc_zoom_in).setOnClickListener(v -> vncView.zoomBy(1.25f));
        findViewById(R.id.btn_vnc_zoom_out).setOnClickListener(v -> vncView.zoomBy(0.8f));
        findViewById(R.id.btn_vnc_rotate).setOnClickListener(v -> {
            boolean nowLandscape = getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            setRequestedOrientation(nowLandscape
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            status(nowLandscape ? "切到竖屏…" : "切到横屏…");
            handler.postDelayed(this::syncDesktopSize, 900);
        });
        btnBars.setOnClickListener(v -> {
            immersive = !immersive;
            applyImmersive();
            applyInsetsPadding(findViewById(R.id.vnc_root));
            setToggleLook(btnBars, !immersive);
            status(immersive ? "已隐藏系统状态栏（画面占满屏幕）" : "已显示系统状态栏");
        });

        // ④ 更多：滚轮 + 常用键 + 帮助
        findViewById(R.id.btn_vnc_wheel_up).setOnClickListener(v -> vncView.sendWheel(true));
        findViewById(R.id.btn_vnc_wheel_down).setOnClickListener(v -> vncView.sendWheel(false));
        findViewById(R.id.btn_vnc_esc).setOnClickListener(v -> tapKey(KS_ESC, "Esc"));
        findViewById(R.id.btn_vnc_tab).setOnClickListener(v -> tapKey(KS_TAB, "Tab"));
        findViewById(R.id.btn_vnc_enter).setOnClickListener(v -> tapKey(KS_ENTER, "Enter"));
        findViewById(R.id.btn_vnc_help).setOnClickListener(v -> showHelp());

        // 面板里任何一次按下都重新计时，别让面板在用户正点的时候收起来
        resetTimerOnTouch(panel);
        // 顶部状态胶囊也能拖（拖状态文字/空白处；里面的按钮照常点）
        FloatingDrag.attach(findViewById(R.id.vnc_topbar), findViewById(R.id.vnc_root), "topbar", null);
        updateConnectionButtons();
    }

    private void setToggleLook(MaterialButton b, boolean on) {
        b.setChecked(on);
        b.setAlpha(on ? 1f : 0.62f);
    }

    private void toggleModifier(MaterialButton b, int keysym, String name) {
        if (!client.isRunning()) {
            Toast.makeText(this, "还没连上远端", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean on = !b.isChecked();
        setToggleLook(b, on);
        client.sendKey(keysym, on);
        status(name + (on ? " 已按住（再点一次松开）" : " 已松开"));
    }

    private void tapKey(int keysym, String name) {
        if (!client.isRunning()) {
            Toast.makeText(this, "还没连上远端", Toast.LENGTH_SHORT).show();
            return;
        }
        // 常用键按下前先把修饰键松开，否则会变成组合键（bVNC 也是这个行为）
        releaseModifiers();
        client.sendKeyTap(keysym);
        status("已发送 " + name);
    }

    /** 松开所有修饰键：断开前/发普通按键前调用，避免远端"卡住 Ctrl" */
    private void releaseModifiers() {
        for (MaterialButton b : new MaterialButton[]{btnCtrl, btnAlt, btnShift}) {
            if (b != null && b.isChecked()) {
                setToggleLook(b, false);
                if (client != null && client.isRunning()) {
                    client.sendKey(b == btnCtrl ? KS_CTRL : (b == btnAlt ? KS_ALT : KS_SHIFT), false);
                }
            }
        }
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("怎么操作这块桌面")
                .setMessage("触摸：\n"
                        + "· 轻点 = 左键单击，拖动 = 按住拖拽\n"
                        + "· 双指上下滑 = 滚轮，双指捏合 = 缩放画面\n"
                        + "· 双击画面 = 适应屏幕 / 1:1 来回切\n"
                        + "· 想右键：先点「右键」，再点一次屏幕\n\n"
                        + "键盘：\n"
                        + "· 「键盘」弹出输入法，字符按 keysym 发给远端\n"
                        + "· 中文等复杂输入用「粘贴」：手机复制 → 点「粘贴」→ 远端 Ctrl+V\n"
                        + "· Ctrl/Alt/Shift 是开关，开着就等于一直按住\n\n"
                        + "面板：\n"
                        + "· 顶部胶囊里的 ≡ 展开/收起控制面板\n"
                        + "· 一碰画面、或 8 秒不动，面板会自动收起\n"
                        + "· 「适应」让整块桌面缩进屏幕，「1:1」看原始像素")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void togglePanel(boolean show) {
        if (panel == null) {
            return;
        }
        View target = panel;
        target.setVisibility(show ? View.VISIBLE : View.GONE);
        panelToggle.setText(show ? "▾" : "≡");
        if (show) {
            scheduleAutoHide();
        } else {
            cancelAutoHide();
        }
    }

    private void scheduleAutoHide() {
        cancelAutoHide();
        handler.postDelayed(autoHide, PANEL_AUTO_HIDE_MS);
    }

    private void cancelAutoHide() {
        handler.removeCallbacks(autoHide);
    }

    /** 面板里的按下都会重新计时（返回 false，不拦截按钮自己的点击） */
    private void resetTimerOnTouch(View v) {
        v.setOnTouchListener((view, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                scheduleAutoHide();
            }
            return false;
        });
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                resetTimerOnTouch(g.getChildAt(i));
            }
        }
    }

    /** 按钮可用性跟着连接状态走：没连上时"断开"没有意义，就连上后"连接"没有意义 */
    private void updateConnectionButtons() {
        boolean up = client != null && client.isRunning();
        View gui = findViewById(R.id.btn_vnc_gui);
        View con = findViewById(R.id.btn_vnc_connect);
        View dis = findViewById(R.id.btn_vnc_disconnect);
        if (gui != null) {
            gui.setEnabled(!up);
        }
        if (con != null) {
            con.setEnabled(!up);
        }
        if (dis != null) {
            dis.setEnabled(up);
        }
    }

    private int parsePort() {
        try {
            int p = Integer.parseInt(portEdit.getText().toString().trim());
            return (p > 0 && p < 65536) ? p : DEFAULT_PORT;
        } catch (Exception e) {
            return DEFAULT_PORT;
        }
    }

    private void connectNow() {
        int port = parsePort();
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt(KEY_PORT, port).apply();
        status("正在连接 127.0.0.1:" + port + " …");
        client.start("127.0.0.1", port, 4000);
    }

    private void startGui() {
        String err = TermuxEnvInstaller.startGuiInTermux(this);
        if (err != null) {
            status("启动图形界面失败：" + err);
            return;
        }
        awaitingGui = true;
        retryCount = 0;
        status("已让 Termux 启动图形界面（TigerVNC Xvnc），等待 5900 端口 …");
        handler.postDelayed(this::connectNow, 2000);
    }

    // ---------------- VncClient.Listener（reader 线程回调） ----------------

    @Override
    public void onConnected(final int width, final int height, final String serverName) {
        awaitingGui = false;
        retryCount = 0;
        runOnUiThread(() -> {
            android.util.Log.i("VncActivity", "已连接 " + width + "x" + height + " name=" + serverName);
            connectedW = width;
            connectedH = height;
            connectedName = serverName == null ? "" : serverName;
            status("已连接 " + width + "x" + height
                    + (connectedName.isEmpty() ? "" : "（" + connectedName + "）"));
            vncView.setFitToScreen(true);
            setToggleLook(btnFit, true);
            vncView.invalidate();
            updateConnectionButtons();
            handler.postDelayed(this::tickStatus, 800);
            // 连上后立刻把远端桌面改成贴合手机屏幕的尺寸（竖屏/横屏各一个桌面）
            handler.postDelayed(this::syncDesktopSize, 400);
            // 连上后先把面板收起来，让用户直接看到桌面
            handler.postDelayed(() -> togglePanel(false), 1200);
        });
    }

    @Override
    public void onFrameReady() {
        frameCount++;
        lastFrameAt = System.currentTimeMillis();
        vncView.postInvalidate();
    }

    @Override
    public void onClipboard(final String text) {
        runOnUiThread(() -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("vnc", text));
                Toast.makeText(this, "已同步远端剪贴板", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onDisconnected(final String reason) {
        runOnUiThread(() -> {
            if (destroyed) {
                return;
            }
            updateConnectionButtons();
            if (awaitingGui && retryCount < 15) {
                retryCount++;
                status("等待图形界面就绪…（第 " + retryCount + " 次重试）");
                handler.postDelayed(this::connectNow, 2000);
                return;
            }
            // Xvnc 偶发"接了连接却不发版本横幅"：自动重连几次就通了，
            // 所以这里措辞是"正在连接第 N 次"而不是"失败"，避免用户以为连不上。
            if (autoRetry < 5) {
                autoRetry++;
                if (autoRetry == 2) {
                    // 服务端可能被反复连断弄脏时，唯一有效的办法是把它重启一遍 —— 自动做掉
                    String err = TermuxEnvInstaller.restartGuiInTermux(this);
                    status(err == null
                            ? "图形界面没响应，正在自动重启服务端…（第 " + autoRetry + " 次尝试）"
                            : "自动重启服务端失败：" + err);
                    handler.postDelayed(this::connectNow, 5000);
                    return;
                }
                status("正在连接 127.0.0.1:" + parsePort() + " …（第 " + autoRetry + " 次尝试，最多 5 次）");
                handler.postDelayed(this::connectNow, 800);
                return;
            }
            android.util.Log.i("VncActivity", "已断开: " + reason);
            status("已断开：" + reason);
            hint("连不上 127.0.0.1:5900。点「启动图形界面」让 Termux 起服务端（约 5~10 秒），"
                    + "或到 Termux 里执行 ~/ubuntu-gui status 看状态、~/.quiz_gui.log 看日志。");
            if (retryCount >= 15) {
                hint("连不上 127.0.0.1:5900。可能是：① 容器里还没装 X11/VNC 组件"
                        + "（去「完整 Python 环境」页点一次「一键准备」）；"
                        + "② 图形界面没起来（Termux 里执行 ~/ubuntu-gui start 看报错，日志在 ~/.quiz_gui.log）。");
            }
        });
    }

    /** 是否沉浸（隐藏系统状态栏/导航栏）；默认沉浸，画面才不会被状态栏压住 */
    private boolean immersive = true;

    /**
     * 沉浸/非沉浸切换。
     *
     * <p>targetSdk 35 在 Android 15+ 会被强制"边到边"，内容默认画到系统状态栏底下 ——
     * 远程桌面这种要占满屏幕的界面，正确做法是隐藏系统栏；用户想看时间/电量时再切回来，
     * 切回来时用 insets 给内容留出安全区（见 {@link #applyInsetsPadding(View)}）。
     */
    private void applyImmersive() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.WindowInsetsControllerCompat c = androidx.core.view.WindowCompat
                .getInsetsController(getWindow(), getWindow().getDecorView());
        if (c == null) {
            return;
        }
        if (immersive) {
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            c.setSystemBarsBehavior(
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        }
    }

    /** 系统栏可见时给根布局留出 insets，避免画面被压住 */
    private void applyInsetsPadding(final View root) {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top;
            int bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(0, immersive ? 0 : top, 0, immersive ? 0 : bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    /** 每秒刷新一次状态行：已收帧数 + 最后帧时间（黑屏时能立刻判断卡在哪） */
    private void tickStatus() {
        if (destroyed || !client.isRunning()) {
            return;
        }
        int n = frameCount;
        String tail;
        if (n == 0) {
            tail = "· 还没收到画面（服务端没回更新，正在自动重发请求…）";
        } else {
            long age = (System.currentTimeMillis() - lastFrameAt) / 1000;
            tail = "· 已收 " + n + " 帧，最后一帧 " + age + " 秒前（画面静止时不再刷新是正常的）";
        }
        status("已连接 " + connectedW + "x" + connectedH
                + (connectedName.isEmpty() ? "" : "（" + connectedName + "）") + " " + tail);
        handler.postDelayed(this::tickStatus, 1000);
    }

    /**
     * 让远端桌面尺寸贴合当前可视区域：竖屏就是竖屏桌面、横屏就是横屏桌面。
     *
     * <p>这是画面太小的根治办法：以前 1280x720 的横屏桌面被硬缩进竖屏，只剩中间一条。
     * TigerVNC 支持动态改分辨率（SetDesktopSize），XFCE 会自动重排。
     */
    private void syncDesktopSize() {
        int w = vncView.getWidth();
        int h = vncView.getHeight();
        if (w < 200 || h < 200 || !client.isRunning()) {
            return;
        }
        float k = Math.min(1f, 1920f / Math.max(w, h));
        int tw = Math.max(480, (int) (w * k));
        int th = Math.max(320, (int) (h * k));
        client.requestDesktopSize(tw, th);
        android.util.Log.i("VncActivity", "requestDesktopSize " + tw + "x" + th);
        status("已请求桌面适配为 " + tw + "x" + th + "（画面会填满屏幕）");
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        handler.postDelayed(this::syncDesktopSize, 600);
        handler.postDelayed(this::applyImmersive, 200);
        handler.postDelayed(() -> FloatingDrag.reclamp(findViewById(R.id.vnc_topbar),
                findViewById(R.id.vnc_root), "topbar"), 400);
    }

    private void status(String s) {
        statusView.setText(s);
    }

    /** 顶部胶囊保持一行；提示要有存在感时就展开面板让用户看见 */
    private void hint(String s) {
        hintView.setText(s);
        hintView.setVisibility(View.VISIBLE);
        togglePanel(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 回到页面：没连就自动连（后台时我们主动断开，回来再接上）
        if (client != null && !client.isRunning() && !destroyed) {
            handler.postDelayed(this::connectNow, 300);
        }
        updateConnectionButtons();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 离开页面就断开：远程桌面在后台持续收帧既费电，也会和别的客户端抢服务端
        // （真机踩到过：页面留在前台占着一个客户端，后面新连接被服务端冷落，Framebuffer updates: 0）
        releaseModifiers();
        if (client != null && client.isRunning()) {
            client.stop();
            status("已暂停（回到本页会自动重连）");
        }
        cancelAutoHide();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // MIUI 下只在 onCreate 里申请隐藏系统栏经常不生效，这里和拿到焦点时再各申请一次
        applyImmersive();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        frameCount = 0;
        handler.removeCallbacksAndMessages(null);
        if (client != null) {
            client.stop();
        }
        super.onDestroy();
    }
}
