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
    private TextView valueSyncTarget;
    private android.app.AlertDialog sessionDialog;
    private android.widget.ArrayAdapter<String> sessionAdapter;
    private final java.util.List<java.util.Map<String, Object>> sessionRows = new java.util.ArrayList<>();
    private final android.os.Handler pickerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pickerTick;
    private String lastLightSig = "";
    private MaterialButton pickSessionBtn;
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

        valueSyncTarget = findViewById(R.id.value_sync_target);
        pickSessionBtn = findViewById(R.id.btn_pick_session);
        if (pickSessionBtn != null) {

        android.widget.Button btnImportHistory = findViewById(R.id.btn_import_history);
        if (btnImportHistory != null) {
            btnImportHistory.setOnClickListener(v -> importRemoteHistory());
        }
            pickSessionBtn.setOnClickListener(v -> pickSession());
        }

        bindActions();
        refresh();
        if (RemoteDshTool.isConfigured(this)) {
            probe(false, false);
        }
    }

    /**
     * 选择同步目标：拉电脑上的会话列表，选一条后设为 sync 目标并切到同步模式。
     *
     * 之前只有 AI 工具动作（list_sessions/attach_session），界面上没有入口 ——
     * 用户「选不了电脑会话」就是缺这一块。网络调用放后台线程，主线程只弹对话框。
     */
    /**
     * 安全弹窗：activity 已 finish/destroyed 时 show() 会抛 BadTokenException，
     * 这里统一挡掉（回页面加载会话列表是异步的，很容易晚到）。
     */
    private void showDialogSafe(android.app.AlertDialog dialog) {
        if (dialog == null) return;
        if (isFinishing() || isDestroyed()) return;
        try {
            if (!dialog.isShowing()) dialog.show();
        } catch (Throwable ignored) {
            // 窗口 token 失效（切页/旋转）时静默丢弃
        }
    }

    /**
     * 把电脑端当前选中会话的**整条历史**导入成本机的一条对话历史。
     *
     * 走历史文件而不是持续镜像：导入后在历史抽屉里可见、可离线回看，
     * 不再依赖补拉把内容重新拼出来。
     */
    /** 导入进度对话框（不确定进度条 + 文案） */
    private android.app.AlertDialog buildImportProgressDialog() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText("正在接收电脑端历史…");
        tv.setTextSize(14f);
        box.addView(tv);

        android.widget.ProgressBar bar = new android.widget.ProgressBar(this);
        bar.setIndeterminate(true);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        box.addView(bar, lp);

        return new android.app.AlertDialog.Builder(this)
                .setTitle("导入电脑对话历史")
                .setView(box)
                .setCancelable(false)
                .create();
    }

    private void updateImportProgress(android.app.AlertDialog dialog, String text) {
        if (dialog == null || !dialog.isShowing()) return;
        try {
            android.view.View root = dialog.getWindow() == null ? null : dialog.getWindow().getDecorView();
            android.widget.TextView tv = root == null ? null : findFirstTextView(root);
            if (tv != null) tv.setText(text);
        } catch (Throwable ignored) { /* 进度文案更新失败不影响导入 */ }
    }

    private android.widget.TextView findFirstTextView(android.view.View root) {
        if (root instanceof android.widget.TextView) return (android.widget.TextView) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i += 1) {
                android.widget.TextView tv = findFirstTextView(g.getChildAt(i));
                if (tv != null) return tv;
            }
        }
        return null;
    }

    private void dismissDialogSafe(android.app.AlertDialog dialog) {
        if (dialog == null) return;
        try {
            if (dialog.isShowing() && !isFinishing() && !isDestroyed()) dialog.dismiss();
        } catch (Throwable ignored) { /* 已不可用 */ }
    }

    private void importRemoteHistory() {
        final String target = RemoteDshTool.getSyncTarget(this);
        if (target.isEmpty()) {
            toast("先选择一条电脑会话");
            return;
        }
        if (!RemoteDshTool.isConfigured(this) || !RemoteDshTool.isConnected(this)) {
            toast("电脑未连接：请先配对/连接");
            return;
        }
        final String title = RemoteDshTool.getSyncTitle(this);

        // 进度对话框：导入是"取事件 → 逐条转消息 → 写历史"，长会话要几秒，
        // 没有反馈用户会以为没点到。
        final android.app.AlertDialog progressDialog = buildImportProgressDialog();
        showDialogSafe(progressDialog);

        new Thread(() -> {
            try {
                DshHistoryImporter.Result r = DshHistoryImporter.importSession(this, target, title,
                        new DshHistoryImporter.Progress() {
                            private long lastAt = 0L;

                            @Override public void onEvents(int count) {
                                long now = System.currentTimeMillis();
                                if (now - lastAt < 200) return;
                                lastAt = now;
                                runOnUiThread(() -> updateImportProgress(progressDialog,
                                        "正在接收电脑端历史… " + count + " 条事件"));
                            }

                            @Override public void onBuilding(int events) {
                                runOnUiThread(() -> updateImportProgress(progressDialog,
                                        "已收到 " + events + " 条事件，正在生成对话…"));
                            }
                        });
                runOnUiThread(() -> {
                    dismissDialogSafe(progressDialog);
                    if (r.count <= 0) {
                        toast("没有可导入的消息（会话可能为空）");
                    } else {
                        toast("已导入 " + r.count + " 条消息到本机历史：" + (r.title == null ? "" : r.title));
                        refresh();
                        if (r.sessionId != null && !r.sessionId.isEmpty()) {
                            try {
                                android.content.Intent open = new android.content.Intent(
                                        RemoteDshConnectActivity.this,
                                        com.oilquiz.app.ui.activity.AIChatActivity.class);
                                open.putExtra("open_session_id", r.sessionId);
                                startActivity(open);
                            } catch (Throwable ignored) { /* 打不开就让用户从历史里点 */ }
                        }
                    }
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    dismissDialogSafe(progressDialog);
                    toast("导入失败：" + e.getMessage());
                });
            }
        }, "dsh-history-import").start();
    }

    private void pickSession() {
        if (!RemoteDshTool.isConfigured(this)) {
            toast("还没配对电脑：先扫码配对");
            return;
        }
        if (pickSessionBtn != null) pickSessionBtn.setEnabled(false);
        new Thread(() -> {
            try {
                java.util.List<java.util.Map<String, Object>> list = RemoteDshTool.fetchSessions(RemoteDshConnectActivity.this, 20);
                runOnUiThread(() -> {
                    if (pickSessionBtn != null) pickSessionBtn.setEnabled(true);
                    if (list.isEmpty()) {
                        toast("电脑上没有可选的对话（先在电脑上聊一句，或检查插件版本）");
                        return;
                    }
                    showSessionDialog(list);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (pickSessionBtn != null) pickSessionBtn.setEnabled(true);
                    toast("读取电脑会话失败: " + e.getMessage());
                });
            }
        }, "pick-remote-session").start();
    }

    /**
     * 会话选择框：**每 3 秒自动对齐一次电脑端**。
     *
     * 先用轻量接口（只 stat 目录拿 mtime）判断"有没有变化"，变了才拉完整列表，
     * 所以既可以实时更新，又不会每 3 秒读一遍 120 条会话日志。
     */
    private void showSessionDialog(java.util.List<java.util.Map<String, Object>> list) {
        stopPickerPolling();
        sessionRows.clear();
        sessionRows.addAll(list);
        sessionAdapter = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1,
                new java.util.ArrayList<>(java.util.Arrays.asList(labelsFor(sessionRows))));
        sessionDialog = new AlertDialog.Builder(this)
                .setTitle("选择电脑会话（同步目标）")
                .setAdapter(sessionAdapter, (d, which) -> {
                    if (which < 0 || which >= sessionRows.size()) return;
                    java.util.Map<String, Object> m = sessionRows.get(which);
                    String sid = str(m.get("session_id"));
                    String title = str(m.get("title"));
                    RemoteDshTool.setSyncTarget(this, sid);
                    RemoteDshTool.setSyncTitle(this, title);
                    RemoteDshTool.setMode(this, RemoteDshTool.MODE_SYNC);
                    refresh();
                    toast("已选择「" + (title.isEmpty() ? shortId(sid) : title) + "」，并切到同步模式");
                    d.dismiss();
                })
                .setNeutralButton("刷新", (d, w) -> pickSession())
                .setNegativeButton("取消", null)
                .create();
        sessionDialog.setOnDismissListener(d -> stopPickerPolling());
        showDialogSafe(sessionDialog);
        startPickerPolling();
    }

    private String[] labelsFor(java.util.List<java.util.Map<String, Object>> rows) {
        String[] labels = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            java.util.Map<String, Object> m = rows.get(i);
            String sid = str(m.get("session_id"));
            String title = str(m.get("title"));
            String cwd = str(m.get("cwd"));
            String last = str(m.get("last"));
            if (last.length() > 36) last = last.substring(0, 36) + "…";
            String when = ago(m.get("updated_at"));
            labels[i] = (title.isEmpty() ? shortId(sid) : title)
                    + (when.isEmpty() ? "" : "  · " + when)
                    + (cwd.isEmpty() ? "" : "  @" + baseName(cwd))
                    + (last.isEmpty() ? "" : "\n" + last);
        }
        return labels;
    }

    private void startPickerPolling() {
        stopPickerPolling();
        pickerTick = new Runnable() {
            @Override public void run() {
                if (sessionDialog == null || !sessionDialog.isShowing()) return;
                new Thread(() -> {
                    try {
                        java.util.List<java.util.Map<String, Object>> light = RemoteDshTool.fetchSessionsLight(RemoteDshConnectActivity.this, 20);
                        StringBuilder sb = new StringBuilder();
                        for (java.util.Map<String, Object> m : light) {
                            sb.append(str(m.get("session_id"))).append(':').append(str(m.get("updated_at"))).append(';');
                        }
                        String sig = sb.toString();
                        if (sig.equals(lastLightSig)) return;
                        lastLightSig = sig;
                        java.util.List<java.util.Map<String, Object>> full = RemoteDshTool.fetchSessions(RemoteDshConnectActivity.this, 20);
                        runOnUiThread(() -> {
                            if (sessionDialog == null || !sessionDialog.isShowing()) return;
                            if (full.isEmpty()) return;
                            sessionRows.clear();
                            sessionRows.addAll(full);
                            if (sessionAdapter != null) {
                                sessionAdapter.clear();
                                sessionAdapter.addAll(labelsFor(sessionRows));
                                sessionAdapter.notifyDataSetChanged();
                            }
                        });
                    } catch (Exception ignored) {
                        // 轮询失败不打扰用户，下个周期再试
                    }
                }, "poll-remote-sessions").start();
                pickerHandler.postDelayed(this, 3000);
            }
        };
        pickerHandler.postDelayed(pickerTick, 3000);
    }

    private void stopPickerPolling() {
        if (pickerTick != null) {
            pickerHandler.removeCallbacks(pickerTick);
            pickerTick = null;
        }
        lastLightSig = "";
    }


    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String shortId(String id) {
        String s = str(id);
        return s.length() <= 12 ? s : "…" + s.substring(s.length() - 10);
    }

    private static String baseName(String path) {
        String p = str(path).replace('\\', '/');
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    /** 把「最后活动时间（毫秒）」写成"3 分钟前"这种；拿不到就返回空串 */
    private static String ago(Object msObj) {
        if (msObj == null) return "";
        long ms;
        try {
            ms = (long) Double.parseDouble(String.valueOf(msObj));
        } catch (Exception e) {
            return "";
        }
        if (ms <= 0) return "";
        long diff = System.currentTimeMillis() - ms;
        if (diff < 0) diff = 0;
        long min = diff / 60000L;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long hour = min / 60;
        if (hour < 24) return hour + " 小时前";
        long day = hour / 24;
        return day + " 天前";
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

        clearBtn.setOnClickListener(v -> showDialogSafe(new AlertDialog.Builder(this)
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
                .create()));

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
        if (valueSyncTarget != null) {
            String t = RemoteDshTool.getSyncTarget(this);
            String title = RemoteDshTool.getSyncTitle(this);
            valueSyncTarget.setText(t.isEmpty() ? "(未选择)" : (title.isEmpty() ? shortId(t) : title));
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
                        + "\n排查：① 电脑端已安装 DSH 插件（插件管理器 → 从文件夹安装 plugin 文件夹 → 重启 DSH）"
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
