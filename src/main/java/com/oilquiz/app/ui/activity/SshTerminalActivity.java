package com.oilquiz.app.ui.activity;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.tool.TermuxEnvInstaller;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * App 内 SSH 终端（完整版）：连本机 Termux sshd（127.0.0.1:8022），
 * 使用 Termux 官方 terminal-emulator + terminal-view 完整终端模拟器渲染，
 * 数据通道走 JSch（0.2.18，支持现代 openssh 算法）。
 * 2026-10-02：替换简易 TextView 终端；TerminalSession 加了 SSH 外部流模式（不碰 JNI/pty）。
 */
public class SshTerminalActivity extends Activity {

    private TerminalView termView;
    private TextView statusView;
    private EditText userView;
    private EditText passView;
    private View paramRow;

    private Session session;
    private ChannelShell channel;
    private TerminalSession termSession;
    private volatile boolean connecting;
    private int reconnectAttempts;
    private volatile boolean userDisconnect;

    private String host = "127.0.0.1";
    private String user = "";
    private String pass = "quiz2026";
    private int port = 8022;

    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ssh_terminal);

        termView = findViewById(R.id.ssh_term_view);
        statusView = findViewById(R.id.ssh_status);
        userView = findViewById(R.id.ssh_user);
        passView = findViewById(R.id.ssh_pass);
        paramRow = findViewById(R.id.ssh_param_row);
        userView.setText(user);
        passView.setText(pass);

        findViewById(R.id.ssh_connect).setOnClickListener(v -> {
            String u = userView.getText().toString().trim();
            String p = passView.getText().toString().trim();
            if (u.isEmpty() || p.isEmpty()) {
                toast("用户名和密码都要填");
                return;
            }
            user = u;
            pass = p;
            paramRow.setVisibility(View.GONE);
            connect();
        });
        findViewById(R.id.ssh_disconnect).setOnClickListener(v -> {
            if (connecting || termSession != null) {
                disconnect();
                connect();
            }
        });
        // 底部常用操作键（手机键盘没有的）
        findViewById(R.id.ssh_esc).setOnClickListener(v -> writeBytes(new byte[]{0x1b}));
        findViewById(R.id.ssh_tab).setOnClickListener(v -> writeString("\t"));
        findViewById(R.id.ssh_ctrl_c).setOnClickListener(v -> writeBytes(new byte[]{0x03}));
        findViewById(R.id.ssh_ctrl_d).setOnClickListener(v -> writeBytes(new byte[]{0x04}));
        findViewById(R.id.ssh_up).setOnClickListener(v -> writeString("\u001b[A"));
        findViewById(R.id.ssh_down).setOnClickListener(v -> writeString("\u001b[B"));
        findViewById(R.id.ssh_enter).setOnClickListener(v -> writeString("\r"));
        termView.setTerminalViewClient(new SshTerminalClients.SshViewClient(this));

        // 从 ssh_info.txt 自动取用户名/密码；空则自动查 whoami
        String info = TermuxEnvInstaller.readPublicText(this, TermuxEnvInstaller.SSH_INFO_NAME);
        if (info != null) {
            for (String l : info.split("\n")) {
                if (l.startsWith("user=")) user = l.substring(5).trim();
                if (l.startsWith("pass=")) pass = l.substring(5).trim();
                if (l.startsWith("ip=") && !l.substring(3).trim().isEmpty()) host = l.substring(3).trim();
                if (l.startsWith("port=")) {
                    try { port = Integer.parseInt(l.substring(5).trim()); } catch (Exception ignored) {}
                }
            }
            userView.setText(user);
            passView.setText(pass);
        }
        if (user.isEmpty()) {
            autoFetchUser();
        } else {
            connect();
        }
    }

    /** 没读到用户名时：自动调 Termux 单行 whoami 直写公共目录再读回（不依赖 $HOME/storage 软链） */
    private void autoFetchUser() {
        setStatus("未读到用户名，自动查询中…");
        String err = TermuxEnvInstaller.runInTermux(this,
                "whoami > /sdcard/Download/OilQuiz/termux_env/whoami.txt", true);
        if (err != null) {
            setStatus("自动查询下发失败");
            showManualRow();
            return;
        }
        uiHandler.postDelayed(() -> {
            String w = TermuxEnvInstaller.readPublicText(SshTerminalActivity.this, "whoami.txt");
            user = (w != null ? w.trim() : "");
            if (user.isEmpty()) {
                setStatus("自动查询没结果，请手动填写");
                showManualRow();
                return;
            }
            userView.setText(user);
            connect();
        }, 1500);
    }

    private void showManualRow() {
        paramRow.setVisibility(View.VISIBLE);
        toast("请在下方输入用户名和密码后点连接");
    }

    private void connect() {
        connecting = true;
        setStatus("正在连接 " + user + "@" + host + ":" + port + " …");
        new Thread(() -> {
            try {
                JSch jsch = new JSch();
                Session s = jsch.getSession(user, host, port);
                s.setPassword(pass);
                s.setConfig("StrictHostKeyChecking", "no");
                s.setConfig("PreferredAuthentications", "password,keyboard-interactive");
                // 心跳保活：每 15 秒发 alive，45 秒无响应才判定断开，避免空闲被回收
                s.setServerAliveInterval(15000);
                s.setServerAliveCountMax(3);
                s.connect(10000);
                ChannelShell c = (ChannelShell) s.openChannel("shell");
                c.setPtyType("xterm-256color");
                c.setPty(true);
                c.connect();
                InputStream in = c.getInputStream();
                OutputStream out = c.getOutputStream();

                runOnUiThread(() -> {
                    session = s;
                    channel = c;
                    connecting = false;
                    reconnectAttempts = 0;
                    userDisconnect = false;
                    // 必须先初始化渲染器（mRenderer 只有 setTextSize 才创建，否则 attachSession 空指针）
                    termView.setTextSize(30);
                    termSession = new TerminalSession(in, out,
                            new SshTerminalClients.SshSessionClient(termView, SshTerminalActivity.this), null);
                    termView.attachSession(termSession);
                    // 拿焦点 + 弹软键盘，用户可直接输入
                    termView.requestFocus();
                    setStatus("✅ 已连接。点击终端区弹出键盘；~/ubuntu 进容器");
                });
            } catch (Exception e) {
                connecting = false;
                runOnUiThread(() -> {
                    setStatus("❌ 连接失败：" + String.valueOf(e.getMessage()));
                    if (userDisconnect) return;
                    reconnectAttempts++;
                    if (reconnectAttempts > 5) {
                        setStatus("自动重连 5 次失败 — 点右上角「断开/重连」手动重连");
                        return;
                    }
                    // sshd 常被系统杀后台：自动拉起 Termux sshd 后递增间隔重连
                    TermuxEnvInstaller.runInTermux(SshTerminalActivity.this,
                            "pgrep -x sshd >/dev/null 2>&1 || sshd", true);
                    uiHandler.postDelayed(() -> {
                        if (!connecting && termSession == null) {
                            setStatus("重连中…");
                            connect();
                        }
                    }, 2000L * reconnectAttempts);
                });
            }
        }).start();
    }

    private void disconnect() {
        userDisconnect = true;
        try { if (termSession != null) termSession.finishIfRunning(); } catch (Exception ignored) {}
        try { if (channel != null) channel.disconnect(); } catch (Exception ignored) {}
        try { if (session != null) session.disconnect(); } catch (Exception ignored) {}
        termSession = null;
        channel = null;
        session = null;
    }

    /** TerminalSession 断开回调（SSH 通道关闭） */
    public void onSshDisconnected() {
        runOnUiThread(() -> {
            if (userDisconnect) return; // 用户主动断开不自动重连
            reconnectAttempts++;
            if (reconnectAttempts > 5) {
                setStatus("自动重连 5 次失败 — 点右上角「断开/重连」手动重连");
                toast("自动重连失败，请手动重连");
                return;
            }
            setStatus("SSH 已断开（第 " + reconnectAttempts + " 次）— " + (2 * reconnectAttempts) + " 秒后自动重连…");
            // sshd 常被系统杀后台：先拉起再重连，间隔递增
            TermuxEnvInstaller.runInTermux(SshTerminalActivity.this,
                    "pgrep -x sshd >/dev/null 2>&1 || sshd", true);
            uiHandler.postDelayed(() -> {
                if (!connecting && termSession == null) {
                    setStatus("重连中…");
                    connect();
                }
            }, 2000L * reconnectAttempts);
        });
    }

    public void copyText(String text) {
        runOnUiThread(() -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("term", text));
        });
    }

    public void pasteText(TerminalSession s) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && s != null) {
            CharSequence txt = cm.getPrimaryClip().getItemAt(0).getText();
            if (txt != null) s.write(txt.toString());
        }
    }

    private void setStatus(String s) {
        runOnUiThread(() -> statusView.setText(s));
    }

    /** 写入原始字节（Ctrl 组合等） */
    private void writeBytes(byte[] data) {
        if (termSession == null) return;
        new Thread(() -> termSession.write(data, 0, data.length)).start();
    }

    private void writeString(String s) {
        if (termSession == null) return;
        new Thread(() -> {
            byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            termSession.write(b, 0, b.length);
        }).start();
    }

    /** 点击终端区弹软键盘 */
    public void showKeyboard() {
        if (termView == null) return;
        termView.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(termView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    private void toast(String m) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onDestroy() {
        disconnect();
        super.onDestroy();
    }
}
