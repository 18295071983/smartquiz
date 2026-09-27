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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("图形界面（VNC）");
        }
        setContentView(R.layout.activity_vnc);

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
            status("已连接 " + width + "x" + height
                    + (serverName == null || serverName.isEmpty() ? "" : "（" + serverName + "）"));
            vncView.setFitToScreen(true);
            vncView.invalidate();
        });
    }

    @Override
    public void onFrameReady() {
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

    private void status(String s) {
        statusView.setText(s);
    }

    private void hint(String s) {
        hintView.setText(s);
        hintView.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (client != null) {
            client.stop();
        }
        super.onDestroy();
    }
}
