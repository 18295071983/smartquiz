package com.oilquiz.app.vnc;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.tool.TermuxEnvInstaller;

/**
 * 图形界面（VNC）：把 Linux 容器里的 X11 桌面显示在答题宝里。
 *
 * <p>整条链路：本页自研的 RFB 客户端 → 127.0.0.1:5900 → Termux 里常驻的 proot 会话
 * （Xvfb 1280x720 + x11vnc）。X11/VNC 服务端在容器里跑（App targetSdk 35 不能 execve
 * 私有目录二进制，放不进 App 进程），客户端完全内置，不用装任何第三方 VNC App。
 */
public class VncActivity extends AppCompatActivity implements VncClient.Listener {

    private static final String PREF = "vnc_prefs";
    private static final String KEY_PORT = "port";
    private static final int DEFAULT_PORT = 5900;

    private VncClient client;
    private VncView vncView;
    private TextView statusView;
    private TextView hintView;
    private EditText portEdit;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 自带状态行/工具栏，不再用系统 ActionBar：屏幕全留给桌面
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

        findViewById(R.id.btn_vnc_gui).setOnClickListener(v -> startGui());
        findViewById(R.id.btn_vnc_connect).setOnClickListener(v -> {
            awaitingGui = false;
            retryCount = 0;
            autoRetry = 0;
            connectNow();
        });
        findViewById(R.id.btn_vnc_disconnect).setOnClickListener(v -> {
            awaitingGui = false;
            client.stop();
            status("已断开");
        });
        findViewById(R.id.btn_vnc_keyboard).setOnClickListener(v -> {
            vncView.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(vncView, InputMethodManager.SHOW_IMPLICIT);
                status("键盘已打开：字符按 keysym 发给远端（中文请用「粘贴到远端」）");
            }
        });
        findViewById(R.id.btn_vnc_right).setOnClickListener(v -> {
            vncView.setRightClickArmed(!vncView.isRightClickArmed());
            status(vncView.isRightClickArmed() ? "右键已就绪：下一次点击发右键" : "已取消右键");
        });
        findViewById(R.id.btn_vnc_fit).setOnClickListener(v -> {
            vncView.setFitToScreen(true);
            status("已适应屏幕");
        });
        findViewById(R.id.btn_vnc_zoom_in).setOnClickListener(v -> vncView.zoomBy(1.25f));
        findViewById(R.id.btn_vnc_zoom_out).setOnClickListener(v -> vncView.zoomBy(0.8f));
        findViewById(R.id.btn_vnc_bars).setOnClickListener(v -> {
            immersive = !immersive;
            applyImmersive();
            applyInsetsPadding(findViewById(R.id.vnc_root));
            status(immersive ? "已隐藏系统状态栏（画面占满屏幕）" : "已显示系统状态栏");
        });
        applyInsetsPadding(findViewById(R.id.vnc_root));
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

        // 测试专用：给了 auto_finish_ms 就自己关掉（让它的客户端断开，不干扰后续用例）
        long autoFinish = getIntent().getLongExtra("auto_finish_ms", 0L);
        if (autoFinish > 0) {
            handler.postDelayed(this::finish, autoFinish);
        }
        hint("用法：点「启动图形界面」等它连上，就能在答题宝里看到 Linux 桌面。"
                + "轻点=左键，拖动=拖拽，双指滑=滚轮，双指捏合=缩放；「右键」后再点=右键。");
        handler.postDelayed(this::connectNow, 400);
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
        status("已让 Termux 启动图形界面（Xvfb + x11vnc），等待 5900 端口 …");
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
            vncView.invalidate();
            handler.postDelayed(this::tickStatus, 800);
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
            if (awaitingGui && retryCount < 15) {
                retryCount++;
                status("等待图形界面就绪…（第 " + retryCount + " 次重试）");
                handler.postDelayed(this::connectNow, 2000);
                return;
            }
            // x11vnc 偶发"接了连接却不发版本横幅"（真机实测约一半首次尝试会这样）：
            // 自动重连几次就通了，所以这里措辞是"正在连接第 N 次"而不是"失败"，避免用户以为连不上。
            if (autoRetry < 5) {
                autoRetry++;
                if (autoRetry == 2) {
                    // 服务端可能被反复连断弄脏（真机现象：banner 变 3.3 且安全类型返回 0，之后怎么连都被拒）
                    // 这时候唯一有效的办法是把它重启一遍 —— 自动做掉，别让用户去 Termux 敲命令
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

    private void status(String s) {
        statusView.setText(s);
    }

    private void hint(String s) {
        hintView.setText(s);
        hintView.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 回到页面：没连就自动连（后台时我们主动断开，回来再接上）
        if (client != null && !client.isRunning() && !destroyed) {
            handler.postDelayed(this::connectNow, 300);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 离开页面就断开：远程桌面在后台持续收帧既费电，也会和别的客户端抢服务端
        // （真机踩到过：页面留在前台占着一个客户端，后面新连接被服务端冷落，Framebuffer updates: 0）
        if (client != null && client.isRunning()) {
            client.stop();
            status("已暂停（回到本页会自动重连）");
        }
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
