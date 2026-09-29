package com.oilquiz.app.ai.tool;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.oilquiz.app.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 「远程连接（电脑）」界面：手动连接 / 断开 / 清除配置 / 扫码配对 / 手动填地址令牌。
 *
 * <p>为什么要有这个界面（2026-09-27，用户要求）：能自己决定"什么时候把手机接到电脑上"。
 * 之前只有扫码配对、连上后没法停用，想临时切断只能清 App 数据。这里把三件事显式化：
 * <ul>
 *   <li><b>连接</b>：用已保存的地址+令牌探测桥接（GET /status），通了才置为"已连接"，工具才允许执行；</li>
 *   <li><b>断开</b>：本机停用（工具一律拒绝执行并提示回本页重连），配置与令牌保留，电脑端不受影响；</li>
 *   <li><b>清除配置</b>：地址/令牌/会话/连接状态全部清空（二次确认）。</li>
 * </ul>
 *
 * <p>入口：① AI 对话页工具抽屉「管理」组的「🖥️ 远程连接（电脑）」；② 工具集 → 设置与系统环境 → 远程连接（电脑）。
 * 本页只影响手机本地的"用不用"；探测就是一次 GET /status，不发任何控制指令。
 */
public class RemoteDshConnectActivity extends AppCompatActivity {

    private View statusDot;
    private TextView statusText;
    private TextView statusHint;
    private TextView valueUrl;
    private TextView valueToken;
    private TextView valueSession;
    private TextView probeText;
    private TextView probeTime;
    private MaterialButton connectBtn;
    private MaterialButton disconnectBtn;
    private MaterialButton scanBtn;
    private MaterialButton clearBtn;
    private MaterialButton saveBtn;
    private TextInputEditText urlInput;
    private TextInputEditText tokenInput;
    /** 聊天页顶部状态条开关 */
    private com.google.android.material.switchmaterial.SwitchMaterial switchChatBar;

    private volatile boolean busy;
    /** 是否做过首次自动探测（onResume 会被重复调用，避免每次返回都打网络） */
    private boolean probedOnce;

    private static final int COLOR_OK = 0xFF15803D;
    private static final int COLOR_WARN = 0xFFB45309;
    private static final int COLOR_ERR = 0xFFB91C1C;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("远程连接（电脑）");
        }
        setContentView(R.layout.activity_remote_dsh_connect);

        statusDot = findViewById(R.id.status_dot);
        statusText = findViewById(R.id.status_text);
        statusHint = findViewById(R.id.status_hint);
        valueUrl = findViewById(R.id.value_url);
        valueToken = findViewById(R.id.value_token);
        valueSession = findViewById(R.id.value_session);
        probeText = findViewById(R.id.probe_text);
        probeTime = findViewById(R.id.probe_time);
        connectBtn = findViewById(R.id.btn_connect);
        disconnectBtn = findViewById(R.id.btn_disconnect);
        scanBtn = findViewById(R.id.btn_scan);
        clearBtn = findViewById(R.id.btn_clear);
        saveBtn = findViewById(R.id.btn_save);
        urlInput = findViewById(R.id.url_input);
        tokenInput = findViewById(R.id.token_input);
        switchChatBar = findViewById(R.id.switch_chat_bar);
        if (switchChatBar != null) {
            // 先设状态再挂监听，避免初始化时误触发保存
            switchChatBar.setChecked(RemoteDshTool.isBarEnabled(this));
            switchChatBar.setOnCheckedChangeListener((btn, checked) -> {
                RemoteDshTool.setBarEnabled(this, checked);
                toast(checked ? "聊天页会显示连接状态条" : "聊天页不再显示连接状态条");
            });
        }

        android.view.View guideBtn = findViewById(R.id.btn_guide);
        if (guideBtn != null) {
            guideBtn.setOnClickListener(v ->
                    startActivity(new Intent(this, RemoteDshGuideActivity.class)));
        }

        bindActions();
        refresh();
        if (RemoteDshTool.isConfigured(this)) {
            probe(false, false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从扫码页返回：重读配置（扫码成功 = 已连接）并自动探测一次
        if (probedOnce) {
            refresh();
            if (RemoteDshTool.isConfigured(this)) {
                probe(false, false);
            }
        }
    }

    private void bindActions() {
        connectBtn.setOnClickListener(v -> {
            if (!RemoteDshTool.isConfigured(this)) {
                toast("还没配对过电脑：先「扫码配对」或在下面手动填写");
                return;
            }
            probe(true, true);
        });

        disconnectBtn.setOnClickListener(v -> {
            if (!RemoteDshTool.isConfigured(this)) {
                toast("还没配对过电脑");
                return;
            }
            RemoteDshTool.disconnect(this);
            refresh();
            showProbe(COLOR_WARN, "已断开：AI 现在不能在电脑上执行任务。\n配置保留，点「连接」即可恢复。", null);
            toast("已断开电脑连接");
        });

        scanBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, RemoteDshPairScanActivity.class));
            } catch (Exception e) {
                toast("打不开扫码页: " + e.getMessage());
            }
        });

        clearBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("清除配置")
                .setMessage("将删除保存的电脑地址、访问令牌、会话记录与连接状态。\n"
                        + "电脑端不受影响；清除后要重新扫码或手动填写才能再用。")
                .setPositiveButton("清除", (d, w) -> {
                    RemoteDshTool.clearConfig(this);
                    urlInput.setText("");
                    tokenInput.setText("");
                    refresh();
                    showProbe(COLOR_WARN, "配置已清除：现在处于未配置状态。", null);
                    toast("已清除电脑连接配置");
                })
                .setNegativeButton("取消", null)
                .show());

        saveBtn.setOnClickListener(v -> {
            String err = RemoteDshTool.saveManualConfig(this,
                    text(urlInput), text(tokenInput));
            if (err != null) {
                toast(err);
                return;
            }
            refresh();
            probe(true, true);
        });
    }

    /** 刷新状态区（不联网）：状态点/文案、地址令牌会话、按钮可用性 */
    private void refresh() {
        boolean configured = RemoteDshTool.isConfigured(this);
        boolean connected = RemoteDshTool.isConnected(this);

        if (!configured) {
            dot(R.drawable.circle_red);
            statusText.setText("未配置");
            statusText.setTextColor(COLOR_ERR);
            statusHint.setText("还没有电脑地址与令牌：扫电脑配对页二维码，或在下面手动填写。");
            valueUrl.setText("(未配置)");
            valueToken.setText("(未配置)");
            valueSession.setText("(未创建)");
        } else {
            valueUrl.setText(RemoteDshTool.configValue(this, "base_url"));
            valueToken.setText(mask(RemoteDshTool.configValue(this, "token")));
            valueSession.setText(orDash(RemoteDshTool.configValue(this, "session_id")));
            if (connected) {
                dot(R.drawable.circle_green);
                statusText.setText("已连接");
                statusText.setTextColor(COLOR_OK);
                statusHint.setText("AI 可以在电脑上执行任务；点「断开」可临时停用（配置保留）。");
            } else {
                dot(R.drawable.circle_yellow);
                statusText.setText("已断开");
                statusText.setTextColor(COLOR_WARN);
                statusHint.setText("已停用：AI 会被拒绝执行。点「连接」重新接上。");
            }
        }
        if (configured && text(urlInput).isEmpty()) {
            urlInput.setText(RemoteDshTool.configValue(this, "base_url"));
        }
        setEnabled(connectBtn, configured && !busy);
        setEnabled(disconnectBtn, configured && connected && !busy);
        setEnabled(scanBtn, !busy);
        setEnabled(clearBtn, configured && !busy);
        setEnabled(saveBtn, !busy);
    }

    /**
     * 探测电脑端桥接。
     *
     * @param setIntent   true = 用户按了「连接」：成功置为已连接、失败置为已断开（意图明确）；
     *                    false = 进页面/返回时自动探测：只刷新探测结果，不改用户的连接开关
     * @param toastResult 是否弹提示
     */
    private void probe(boolean setIntent, boolean toastResult) {
        if (busy) {
            return;
        }
        busy = true;
        probedOnce = true;
        refresh();
        showProbe(COLOR_WARN, "正在探测电脑端桥接…", null);

        new Thread(() -> {
            final long t0 = System.currentTimeMillis();
            String text;
            boolean ok;
            try {
                text = RemoteDshTool.probeStatusText(getApplicationContext());
                ok = true;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                text = "不可达: " + msg
                        + "\n地址: " + RemoteDshTool.configValue(this, "base_url")
                        + "\n排查：① 电脑端已双击 tools\\start_dsh_bridge.bat（含 ACP serve）"
                        + " ② 手机与电脑同网/隧道可用 ③ 令牌是否与桥接一致";
                ok = false;
            }
            final long ms = System.currentTimeMillis() - t0;
            final String fText = text;
            final boolean fOk = ok;
            runOnUiThread(() -> {
                busy = false;
                if (setIntent) {
                    RemoteDshTool.setConnected(this, fOk);
                }
                String stamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                if (fOk) {
                    showProbe(COLOR_OK, "电脑端在线 ✓\n" + fText, "最近探测 " + stamp + "（" + ms + "ms）");
                } else {
                    showProbe(COLOR_ERR, "连接失败 ✗\n" + fText, "最近探测 " + stamp + "（" + ms + "ms）");
                }
                refresh();
                if (toastResult) {
                    toast(fOk ? "已连接电脑" : "连接失败，看下方原因");
                }
            });
        }, "remote-dsh-probe").start();
    }

    private void showProbe(int color, String text, String timeText) {
        probeText.setTextColor(color);
        probeText.setText(text);
        probeTime.setText(timeText == null ? "" : timeText);
    }

    private void dot(int drawableId) {
        Drawable d = ContextCompat.getDrawable(this, drawableId);
        statusDot.setBackground(d);
    }

    private void setEnabled(MaterialButton b, boolean enabled) {
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.5f);
    }

    private static String text(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private static String mask(String token) {
        if (token == null || token.trim().isEmpty()) {
            return "(未配置)";
        }
        String t = token.trim();
        return t.substring(0, Math.min(4, t.length())) + "***（" + t.length() + " 位）";
    }

    private static String orDash(String s) {
        return (s == null || s.trim().isEmpty()) ? "(未创建)" : s;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 供其它界面直接跳转 */
    public static void open(Context context) {
        Intent i = new Intent(context, RemoteDshConnectActivity.class);
        if (!(context instanceof android.app.Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(i);
    }
}
