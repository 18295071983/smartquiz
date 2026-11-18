package com.oilquiz.app.ai.tool;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
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
    private TextView valueDirect;
    private androidx.appcompat.app.AlertDialog sessionDialog;
    /** 分组列表容器（2026-11-14 重做：按工作区分组，见 {@link #showSessionDialog}） */
    private android.widget.LinearLayout sessionListBox;
    /** 是否展开"其他工作区"：默认只看当前工作区，和电脑上看到的条数一致 */
    private boolean showAllWorkspaces;
    /**
     * 是否显示"电脑账本之外"的历史会话（2026-11-14）。
     *
     * <p>默认 false：只列 {@code /sessions.json} 里 {@code desktop=true} 的那些 ——
     * 即桌面端侧栏真的有的会话，条数跟电脑一致。磁盘上还躺着历史项目/CLI 遗留的几十条，
     * 收进"显示全部会话"，免得用户以为手机在乱造会话。</p>
     */
    private boolean showAllSessions;
    /** 电脑当前工作区（/sessions.json 的 current_workspace = 最近活动会话所在的 cwd） */
    private String currentWorkspace = "";
    /** 电脑上"正在聊的那条"会话 id（/sessions.json 的 current_session_id） */
    private String currentPcSessionId = "";
    private final java.util.List<java.util.Map<String, Object>> sessionRows = new java.util.ArrayList<>();
    private final android.os.Handler pickerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pickerTick;
    private String lastLightSig = "";
    private MaterialButton pickSessionBtn;
    private TextView probeText;
    private TextView probeTime;
    private MaterialButton selfCheckBtn;
    private TextView selfCheckText;
    private android.widget.LinearLayout selfCheckCards;
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

    /**
     * 进来就弹「选电脑会话」（2026-11-14，M8-P0-3）。
     *
     * <p>聊天页顶栏点标题需要"挑一条电脑会话"，这里复用本页现成的
     * {@code pickSession()}（拉 /v2/sessions → 列表 → attach），不再复制一份列表逻辑。
     * 启动时带 {@code EXTRA_PICK_SESSION=true} 即可。</p>
     */
    public static final String EXTRA_PICK_SESSION = "pick_session";
    private boolean pickOnResume;
    /** 本次是"从聊天页来选会话"：选完 finish() 回聊天页（设计稿 §9-3） */
    private boolean finishAfterPick;

    private static final int COLOR_OK = 0xFF15803D;
    private static final int COLOR_WARN = 0xFFB45309;
    private static final int COLOR_ERR = 0xFFB91C1C;
    // 2026-11-16：颜色改为跟随主题（预设/动态色/夜间/OLED 自动联动），
    // 不再用写死的浅色值 —— 之前深色模式下这几个标签仍是浅色底上的深灰，几乎看不见。
    private int okColor() { return com.oilquiz.app.ai.chat.component.ComponentColors.success(this); }
    private int warnColor() { return com.oilquiz.app.ai.chat.component.ComponentColors.warning(this); }
    private int errColor() { return com.oilquiz.app.ai.chat.component.ComponentColors.error(this); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("远程连接（电脑）");
        }
        setContentView(R.layout.activity_remote_dsh_connect);
        pickOnResume = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_PICK_SESSION, false);
        finishAfterPick = pickOnResume;
        // 消费掉这个 extra：旋转/重建时 onCreate 会再读一次 intent，不去掉就会又弹一遍列表
        if (pickOnResume && getIntent() != null) getIntent().removeExtra(EXTRA_PICK_SESSION);

        statusDot = findViewById(R.id.status_dot);
        statusText = findViewById(R.id.status_text);
        statusHint = findViewById(R.id.status_hint);
        valueUrl = findViewById(R.id.value_url);
        valueToken = findViewById(R.id.value_token);
        valueSession = findViewById(R.id.value_session);
        probeText = findViewById(R.id.probe_text);
        probeTime = findViewById(R.id.probe_time);
        selfCheckBtn = findViewById(R.id.btn_self_check);
        selfCheckText = findViewById(R.id.self_check_text);
        selfCheckCards = findViewById(R.id.self_check_cards);
        if (selfCheckBtn != null) {
            selfCheckBtn.setOnClickListener(v -> runSelfCheck());
        android.view.View quickBtn = findViewById(R.id.btn_quick_test);
        if (quickBtn != null) quickBtn.setOnClickListener(v -> runQuickSelfTest());
        }
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

        // 插件总开关（2026-11-18）：手机端默认关闭；关闭时禁用本页所有操作按钮并断开连接
        com.google.android.material.switchmaterial.SwitchMaterial pluginSwitch =
                findViewById(R.id.switch_remote_plugin);
        if (pluginSwitch != null) {
            pluginSwitch.setChecked(RemoteDshTool.isPluginEnabled(this));
            pluginSwitch.setOnCheckedChangeListener((btn, checked) -> {
                RemoteDshTool.setPluginEnabled(this, checked);
                applyPluginGate();
                toast(checked ? "远程 DSH 插件已启用" : "远程 DSH 插件已关闭");
            });
        }
        applyPluginGate();
        // 打开即停在顶部：地址输入框会自动聚焦并把页面滚到中部
        android.view.View rootScroll = findViewById(R.id.root_scroll);
        if (rootScroll != null) {
            rootScroll.post(() -> {
                rootScroll.clearFocus();
                if (rootScroll instanceof android.widget.ScrollView) {
                    ((android.widget.ScrollView) rootScroll).scrollTo(0, 0);
                }
            });
        }

        android.view.View guideBtn = findViewById(R.id.btn_guide);
        if (guideBtn != null) {
            guideBtn.setOnClickListener(v ->
                    startActivity(new Intent(this, RemoteDshGuideActivity.class)));
        }

        android.view.View insightBtn = findViewById(R.id.btn_insight);
        if (insightBtn != null) {
            insightBtn.setOnClickListener(v ->
                    startActivity(new Intent(this, RemoteDshInsightActivity.class)));
        }

        valueSyncTarget = findViewById(R.id.value_sync_target);
        valueDirect = findViewById(R.id.value_direct);
        pickSessionBtn = findViewById(R.id.btn_pick_session);
        if (pickSessionBtn != null) {

        android.widget.Button btnImportHistory = findViewById(R.id.btn_import_history);
        if (btnImportHistory != null) {
            btnImportHistory.setOnClickListener(v -> importRemoteHistory());
        }
            pickSessionBtn.setOnClickListener(v -> pickSession());
        }


        bindActions();
        // 「连接管理」按钮在 onCreate 里绑（之前放进 bindActions 的替换位置落到了
        // 保存按钮的 handler 里，导致按钮点了没反应）
        try {
            View profilesBtn = findViewById(R.id.btn_profiles);
            if (profilesBtn != null) {
                profilesBtn.setOnClickListener(v -> RemoteProfilesActivity.open(this));
            } else {
                android.util.Log.w("RemoteDshConnect", "找不到 btn_profiles");
            }
        } catch (Throwable t) {
            android.util.Log.w("RemoteDshConnect", "绑定连接管理按钮失败: " + t.getMessage());
        }
        refresh();
        if (RemoteDshTool.isConfigured(this)) {
            probe(false, false);
        }
    }

    /**
     * 「自检 / 对账」：一次拿到电脑端 v8 插件的 /health 五项检查 + /reconcile 摘要。
     *
     * 放这里的价值：手机端遇到"电脑没反应"时，不用再猜是网络、令牌还是电脑端工具挂了——
     * host_cwd 红就说明电脑上所有起子进程的工具都会报 ENOENT。
     */
    private void runSelfCheck() {
        if (!RemoteDshTool.isConfigured(this)) {
            toast("还没配对电脑：先扫码或在下面填地址和令牌");
            return;
        }
        if (selfCheckText != null) selfCheckText.setVisibility(View.GONE);
        if (selfCheckCards != null) {
            selfCheckCards.setVisibility(View.VISIBLE);
            selfCheckCards.removeAllViews();
        }
        new Thread(() -> {
            java.util.Map<String, Object> v = null;
            String err = null;
            try {
                // v2：一次拿"状态卡 + 自检卡 + 会话卡 + 时间线"，与服务端/电脑面板同源
                v = RemoteDshTool.fetchView(getApplicationContext(), RemoteDshTool.getDirectSessionId(this));
                int code = asInt(v.get("_http_code"));
                if (code >= 400) {
                    // 统一走人话提示：401=令牌、404=端口/版本、5xx=插件出错
                    err = com.oilquiz.app.ai.tool.RemoteErrorHints
                            .ofHttp(code, String.valueOf(v.get("error"))).display();
                } else if (!(v.get("cards") instanceof java.util.List)) {
                    err = "电脑端插件太旧：没有 cards（需要 ≥ v8 / 协议 v2）\n请重启电脑上的 DSH 再试";
                }
            } catch (Exception e) {
                err = com.oilquiz.app.ai.tool.RemoteErrorHints.of(e).display();
            }

            boolean online = false;
            long rtt = 0;
            Object gap = null;
            String pulseErr = null;
            try {
                long t0 = System.currentTimeMillis();
                java.util.Map<String, Object> p = new RemoteSdk(getApplicationContext())
                        .pulse(RemoteDshTool.getDirectSessionId(this), 0);
                rtt = System.currentTimeMillis() - t0;
                gap = p.get("gap");
                online = true;
            } catch (Exception pe) {
                pulseErr = pe.getMessage();
            }

            final java.util.Map<String, Object> fv = v;
            final String fErr = err;
            final boolean fOnline = online;
            final long fRtt = rtt;
            final Object fGap = gap;
            final String fPulseErr = pulseErr;
            runOnUiThread(() -> {
                if (fErr != null || fv == null) {
                    if (selfCheckCards != null) selfCheckCards.setVisibility(View.GONE);
                    if (selfCheckText != null) {
                        selfCheckText.setVisibility(View.VISIBLE);
                        selfCheckText.setText(fErr == null ? "自检失败" : fErr);
                    }
                    return;
                }
                if (selfCheckCards != null) {
                    // 原生卡片渲染（对齐电脑面板的设计语言），不再是等宽文本
                    RemoteCardView.render(selfCheckCards, fv);
                    RemoteCardView.appendBreath(selfCheckCards, fOnline, fRtt, fGap, fPulseErr);
                }
            });
        }).start();
    }

    /** 宽松整数解析（HTTP 字段可能是 Integer/Long/String） */
    private static int asInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o == null ? "" : o).trim()); } catch (Exception e) { return 0; }
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
    private void showDialogSafe(androidx.appcompat.app.AlertDialog dialog) {
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
    private androidx.appcompat.app.AlertDialog buildImportProgressDialog() {
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

        return com.oilquiz.app.ui.modal.AppDialog.builder(this)
                .setTitle("导入电脑对话历史")
                .setView(box)
                .setCancelable(false)
                .create();
    }

    private void updateImportProgress(androidx.appcompat.app.AlertDialog dialog, String text) {
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

    private void dismissDialogSafe(androidx.appcompat.app.AlertDialog dialog) {
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
        final androidx.appcompat.app.AlertDialog progressDialog = buildImportProgressDialog();
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
                        toast("已同步 " + r.count + " 条消息（覆盖本机那条历史）："
                                + (r.title == null ? "" : r.title));
                        refresh();
                    }
                    // 导入=强制同步到锚点那条会话，直接回聊天页打开它（别叠一个新实例）
                    openChatAndFinish(r.sessionId);
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
                // 2026-11-14：要分组就得知道"电脑当前在哪个工作区"，所以拿带元信息的整包
                java.util.Map<String, Object> meta = RemoteDshTool.fetchSessionsWithMeta(this, PICKER_LIMIT);
                java.util.List<java.util.Map<String, Object>> list = extractSessions(meta);
                final String ws = str(meta.get("current_workspace"));
                final String curSid = str(meta.get("current_session_id"));
                runOnUiThread(() -> {
                    if (pickSessionBtn != null) pickSessionBtn.setEnabled(true);
                    if (list.isEmpty()) {
                        toast("电脑上没有可选的对话（先在电脑上聊一句，或检查插件版本）");
                        return;
                    }
                    showSessionDialog(list, ws, curSid);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (pickSessionBtn != null) pickSessionBtn.setEnabled(true);
                    toast("读取电脑会话失败: " + e.getMessage());
                });
            }
        }, "pick-remote-session").start();
    }

    /** 一次拉多少条：分组要够用，但每条都要读会话头/尾，100 条在局域网约 1~2 秒 */
    private static final int PICKER_LIMIT = 100;

    private static java.util.List<java.util.Map<String, Object>> extractSessions(java.util.Map<String, Object> meta) {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        Object arr = meta == null ? null : meta.get("sessions");
        if (arr instanceof java.util.List) {
            for (Object o : (java.util.List<?>) arr) {
                if (o instanceof java.util.Map) {
                    //noinspection unchecked
                    out.add((java.util.Map<String, Object>) o);
                }
            }
        }
        return out;
    }

    /**
     * 会话选择框（2026-11-14 重做）：**按工作区分组**，当前工作区置顶。
     *
     * <p>旧版是一行一条的 {@code simple_list_item_1}：标题、时间、目录、最后一句全挤在一行，
     * 而且把电脑上所有工作区的会话平铺出来 —— 电脑上只看到 6 条，手机上却有三十多条，
     * 用户当然觉得"不一致"。现在按 {@code cwd} 分组：默认只展开"当前工作区"（和电脑上看到的一致），
     * 其余收成一行「其他工作区 · N 条」，点开才展开。刷新走 3 秒轮询，签名不变就不重绘。</p>
     */
    private void showSessionDialog(java.util.List<java.util.Map<String, Object>> list, String workspace,
                                   String pcCurrentSessionId) {
        stopPickerPolling();
        sessionRows.clear();
        sessionRows.addAll(list);
        if (pcCurrentSessionId != null && !pcCurrentSessionId.isEmpty()) currentPcSessionId = pcCurrentSessionId;
        if (workspace != null && !workspace.isEmpty()) currentWorkspace = workspace;
        if (currentWorkspace.isEmpty()) currentWorkspace = workspaceOfCurrentFollow();

        sessionListBox = new android.widget.LinearLayout(this);
        sessionListBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        renderSessionGroups();

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(sessionListBox, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        scroll.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, maxH));

        sessionDialog = com.oilquiz.app.ui.modal.AppDialog.builder(this)
                .setTitle("选择电脑对话")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .create();
        sessionDialog.setOnDismissListener(d -> stopPickerPolling());
        showDialogSafe(sessionDialog);
        startPickerPolling();
    }

    /** 当前"正在跟随"的电脑会话所在工作区（列表里查不到就用电脑上报的 cwd） */
    private String workspaceOfCurrentFollow() {
        String follow = RemoteDshTool.getSyncTarget(this);
        if (!follow.isEmpty()) {
            for (java.util.Map<String, Object> m : sessionRows) {
                if (follow.equals(str(m.get("session_id")))) {
                    String cwd = str(m.get("cwd"));
                    if (!cwd.isEmpty()) return cwd;
                }
            }
        }
        return "";
    }

    /** 按工作区分组重绘列表：当前工作区第一，其余按最近活动排序（排序规则在 RemoteSessionGroups，有单测） */
    private void renderSessionGroups() {
        if (sessionListBox == null) return;
        sessionListBox.removeAllViews();
        // 2026-11-14（第三版）：默认只显示**桌面端账本里有的**会话（/sessions.json 的 desktop 标记）。
        // 电脑上侧栏看到的就那么几条，磁盘上却躺着几十条历史项目/CLI 遗留 —— 以前全列出来，
        // 用户自然觉得"手机上怎么一堆"。其余收进"显示全部会话"。
        java.util.List<java.util.Map<String, Object>> rows = sessionRows;
        int hiddenByDesktop = 0;
        if (!showAllSessions) {
            // "电脑上有的" + "我正在跟随的那条"（跟随的可能不在桌面账本里，但它必须可见，
            // 否则用户看不到自己在跟哪条 —— 那条会带「正在跟随」标记，不会认错）
            final String followId = RemoteDshTool.getSyncTarget(this);
            java.util.List<java.util.Map<String, Object>> kept = new java.util.ArrayList<>();
            for (java.util.Map<String, Object> m : sessionRows) {
                String sid = str(m.get("session_id"));
                boolean desktop = Boolean.TRUE.equals(m.get("desktop"));
                boolean following = !followId.isEmpty() && followId.equals(sid);
                // 口径收敛在一处：桌面账本里的 + 正在跟随那条（规则见 RemoteSessionGroups）
                if (RemoteSessionGroups.defaultVisible(desktop, following)) {
                    kept.add(m);
                }
            }
            if (!kept.isEmpty()) {
                hiddenByDesktop = sessionRows.size() - kept.size();
                rows = kept;
            }
            // 桌面一条都没登记（新电脑/账本还没写）：退回显示全部，别给空框
        }
        java.util.LinkedHashMap<String, java.util.List<java.util.Map<String, Object>>> groups =
                RemoteSessionGroups.group(rows);
        if (groups.isEmpty()) return;
        final String cur = currentWorkspace;
        // 正在跟随的会话可能不在当前工作区：它那组必须可见，否则用户看不到"我在跟哪条"
        final String followGroup = workspaceOfCurrentFollow();
        java.util.List<String> order = RemoteSessionGroups.orderedKeys(groups, cur);
        if (!followGroup.isEmpty() && !followGroup.equals(cur)) {
            order.remove(followGroup);
            int at = (!cur.isEmpty() && order.contains(cur)) ? order.indexOf(cur) + 1 : 0;
            order.add(at, followGroup);
        }

        android.view.View.OnClickListener expandAll = v -> {
            showAllWorkspaces = true;
            renderSessionGroups();
        };
        android.view.View.OnClickListener collapse = v -> {
            showAllWorkspaces = false;
            renderSessionGroups();
        };
        android.view.View.OnClickListener showEverything = v -> {
            showAllSessions = true;
            renderSessionGroups();
        };
        android.view.View.OnClickListener showDesktopOnly = v -> {
            showAllSessions = false;
            showAllWorkspaces = true;   // 回到"只看电脑有的"时，按工作区分组的折叠也一并复位
            renderSessionGroups();
        };

        java.util.Set<String> visible = new java.util.HashSet<>();
        for (String key : order) {
            boolean isCur = !cur.isEmpty() && cur.equals(key);
            boolean isFollow = !followGroup.isEmpty() && followGroup.equals(key);
            boolean show = showAllWorkspaces || isCur || isFollow;
            if (show) visible.add(key);
            if (!show) continue;                                  // 默认只展开当前工作区 + 正在跟随的那组
            java.util.List<java.util.Map<String, Object>> bucket = groups.get(key);
            if (bucket == null || bucket.isEmpty()) continue;
            sessionListBox.addView(buildGroupHeader(isCur, isFollow, key, bucket.size()));
            for (java.util.Map<String, Object> m : bucket) {
                sessionListBox.addView(buildSessionRow(m, isCur));
            }
        }
        // 两个可见组都为空（比如刚换电脑）：至少把全部列出来，别给空框
        if (visible.isEmpty()) {
            for (String key : order) {
                java.util.List<java.util.Map<String, Object>> bucket = groups.get(key);
                if (bucket == null || bucket.isEmpty()) continue;
                sessionListBox.addView(buildGroupHeader(false, false, key, bucket.size()));
                for (java.util.Map<String, Object> m : bucket) {
                    sessionListBox.addView(buildSessionRow(m, false));
                }
            }
        }
        int others = 0;
        for (String key : order) {
            if (visible.contains(key)) continue;
            others += groups.get(key) == null ? 0 : groups.get(key).size();
        }
        if (others > 0) {
            sessionListBox.addView(buildToggleRow(showAllWorkspaces
                    ? "只看当前工作区"
                    : "其他工作区 · 还有 " + others + " 条",
                    showAllWorkspaces ? collapse : expandAll));
        } else if (showAllWorkspaces) {
            sessionListBox.addView(buildToggleRow("只看当前工作区", collapse));
        }
        if (!showAllSessions && hiddenByDesktop > 0) {
            sessionListBox.addView(buildToggleRow(
                    "显示全部会话 · 还有 " + hiddenByDesktop + " 条（电脑上没打开的）", showEverything));
        } else if (showAllSessions) {
            sessionListBox.addView(buildToggleRow("只看电脑上有的会话", showDesktopOnly));
        }
    }

    /** 分组标题：当前工作区高亮；路径太长时保留末两级（看得出是哪个项目） */
    private View buildGroupHeader(boolean isCurrent, boolean isFollowing, String cwd, int count) {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padH = dp(16);
        box.setPadding(padH, dp(12), padH, dp(4));

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText((isCurrent ? "当前工作区 · " : "")
                + RemoteSessionGroups.label(cwd) + "（" + count + "）"
                + (isFollowing ? " · 正在跟随" : ""));
        title.setTextSize(13f);
        title.setTextColor(isCurrent || isFollowing ? okColor() : com.oilquiz.app.ai.chat.component.ComponentColors.textTertiary(this));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(title);

        android.widget.TextView path = new android.widget.TextView(this);
        path.setText(cwd.isEmpty() ? "（电脑没上报目录）" : cwd);
        path.setTextSize(10f);
        path.setTextColor(com.oilquiz.app.ai.chat.component.ComponentColors.textTertiary(this));
        path.setMaxLines(1);
        path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        box.addView(path);
        return box;
    }

    /** 一条会话：第一行标题（与电脑一致），第二行「时间 · 最后一句」 */
    private View buildSessionRow(java.util.Map<String, Object> m, boolean inCurrentGroup) {
        final String sid = str(m.get("session_id"));
        final String title = str(m.get("title"));
        final boolean following = sid.equals(RemoteDshTool.getSyncTarget(this));
        final boolean pcCurrent = !currentPcSessionId.isEmpty() && currentPcSessionId.equals(sid);

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padH = dp(16);
        row.setPadding(padH, dp(10), padH, dp(10));
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            row.setBackgroundResource(tv.resourceId);
        }
        row.setClickable(true);

        android.widget.LinearLayout titleLine = new android.widget.LinearLayout(this);
        titleLine.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.TextView name = new android.widget.TextView(this);
        name.setText(title.isEmpty() ? shortId(sid) : title);
        name.setTextSize(15f);
        name.setTextColor(com.oilquiz.app.ai.chat.component.ComponentColors.textPrimary(this));
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.LinearLayout.LayoutParams nameLp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLine.addView(name, nameLp);
        if (pcCurrent) {
            // 电脑上正在聊的就是这条：手机上以前根本看不出来（还被折进"其他工作区"）
            android.widget.TextView badge = new android.widget.TextView(this);
            badge.setText("电脑当前");
            badge.setTextSize(10f);
            badge.setTextColor(com.oilquiz.app.ai.chat.component.ComponentColors.accent(this));
            badge.setPadding(dp(6), 0, 0, 0);
            titleLine.addView(badge);
        }
        if (following) {
            android.widget.TextView badge = new android.widget.TextView(this);
            badge.setText("正在跟随");
            badge.setTextSize(10f);
            badge.setTextColor(okColor());
            badge.setPadding(dp(6), 0, 0, 0);
            titleLine.addView(badge);
        }
        row.addView(titleLine);

        StringBuilder meta = new StringBuilder();
        String when = ago(m.get("updated_at"));
        if (!when.isEmpty()) meta.append(when);
        String last = str(m.get("last")).replaceAll("\\s+", " ");
        if (last.length() > 60) last = last.substring(0, 60) + "…";
        if (!last.isEmpty()) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(last);
        }
        if (meta.length() > 0) {
            android.widget.TextView sub = new android.widget.TextView(this);
            sub.setText(meta.toString());
            sub.setTextSize(12f);
            sub.setTextColor(inCurrentGroup ? com.oilquiz.app.ai.chat.component.ComponentColors.textSecondary(this) : com.oilquiz.app.ai.chat.component.ComponentColors.textTertiary(this));
            sub.setMaxLines(1);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(sub);
        }

        android.view.View divider = new android.view.View(this);
        divider.setBackgroundColor(com.oilquiz.app.ai.chat.component.ComponentColors.border(this));
        row.addView(divider, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));

        row.setOnClickListener(v -> pickSessionRow(m));
        // 长按 = 归档（2026-11-17）：只从两端列表隐藏，不删记录；可恢复
        row.setOnLongClickListener(v -> { confirmArchiveSession(m); return true; });
        return row;
    }

    /**
     * 长按一条电脑会话 → 归档（插件 /v2/session action=archive）。
     *
     * <p>归档不影响记录本身：只从手机抽屉与电脑侧栏隐藏，电脑端可恢复。
     * 当前正在跟随的会话不允许直接归档 —— 否则手机会跟着一条列表里不存在的会话。</p>
     */
    private void confirmArchiveSession(java.util.Map<String, Object> m) {
        final String sid = str(m.get("session_id"));
        if (sid.isEmpty()) return;
        if (sid.equals(RemoteDshTool.getSyncTarget(this))) {
            toast("这是当前跟随的会话：先切到别的会话再归档");
            return;
        }
        String title = str(m.get("title"));
        if (title.isEmpty()) title = sid;
        // 走标准弹层（2026-11-17 对齐 docs/ui/UI-标准-模态框-弹层-容器.md）：
        // 以前这里自己 new AlertDialog.Builder，内边距/按钮色/圆角都跟别处不一致
        com.oilquiz.app.ui.modal.AppDialog.confirm(this,
                "归档这条电脑会话？",
                title + "\n\n归档后它会从手机与电脑的列表里隐藏；不删记录，可在电脑端恢复。",
                "归档", (d, w) -> doArchive(sid));
    }

    private void doArchive(final String sid) {
        new Thread(() -> {
            boolean ok = false;
            String msg;
            try {
                java.util.Map<String, Object> r = new RemoteSdk(getApplicationContext()).archive(sid, true);
                ok = r != null && Boolean.TRUE.equals(r.get("ok"));
                msg = ok ? "已归档（可在电脑端恢复）"
                        : "归档失败：" + (r == null ? "电脑端无响应" : str(r.get("error")));
            } catch (Exception e) {
                msg = "归档失败：" + e.getMessage();
            }
            final String fmsg = msg;
            final boolean fok = ok;
            runOnUiThread(() -> {
                toast(fmsg);
                if (fok) lastLightSig = "";   // 让选择框下一轮轮询立刻刷新
            });
        }, "archive-session").start();
    }

    private View buildToggleRow(String text, android.view.View.OnClickListener onClick) {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(com.oilquiz.app.ai.chat.component.ComponentColors.accent(this));
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        tv.setClickable(true);
        tv.setOnClickListener(onClick);
        return tv;
    }

    /**
     * 选中一条电脑会话（2026-11-14：**切换即强制同步**）。
     *
     * <p>用户问的"切换会话时怎么落盘"——答案是：切过去就向电脑要一次整条历史，
     * 用锚点 id 覆盖本地那条会话（标题也用电脑的），然后把传输/渲染游标推到末尾，
     * 最后把聊天页拉回前台显示它。这样手机上看到的就是电脑上那条，不会出现
     * "切了会话但页面还是空的/旧的"。</p>
     */
    private void pickSessionRow(java.util.Map<String, Object> m) {
        final String sid = str(m.get("session_id"));
        if (sid.isEmpty()) return;
        final String title = str(m.get("title"));
        stopPickerPolling();
        dismissDialogSafe(sessionDialog);

        final androidx.appcompat.app.AlertDialog progressDialog = buildImportProgressDialog();
        updateImportProgress(progressDialog, "正在从电脑同步「" + (title.isEmpty() ? shortId(sid) : title) + "」…");
        showDialogSafe(progressDialog);

        // 与聊天页历史抽屉共用同一条执行路径（RemoteSessionSwitcher），
        // 避免"选择框切法"和"抽屉切法"两套逻辑漂移
        RemoteSessionSwitcher.switchTo(this, sid, title, new RemoteSessionSwitcher.Callback() {
            @Override public void onProgress(String text) {
                runOnUiThread(() -> updateImportProgress(progressDialog, text));
            }

            @Override public void onDone(String localSessionId, int count, String t) {
                dismissDialogSafe(progressDialog);
                if (count > 0) {
                    toast("已同步 " + count + " 条消息：" + (t == null ? "" : t));
                } else {
                    toast("这条会话在电脑上还是空的，已切过去");
                }
                refresh();
                openChatAndFinish(localSessionId == null ? "" : localSessionId);
            }

            @Override public void onFailed(String message) {
                dismissDialogSafe(progressDialog);
                toast("同步失败：" + message + "（已切换跟随，后台会继续补）");
                openChatAndFinish("");
            }
        });
    }

    /** 回到聊天页并指定要打开的会话（走 REORDER_TO_FRONT，不叠新实例） */
    private void openChatAndFinish(String localSessionId) {
        try {
            android.content.Intent i = new android.content.Intent(this,
                    com.oilquiz.app.ui.activity.AIChatActivity.class);
            if (localSessionId != null && !localSessionId.isEmpty()) {
                i.putExtra("open_session_id", localSessionId);
            }
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable t) {
            android.util.Log.w("RemoteDshConnect", "打开聊天页失败: " + t.getMessage());
        }
        finish();
    }

    private void startPickerPolling() {
        stopPickerPolling();
        pickerTick = new Runnable() {
            @Override public void run() {
                if (sessionDialog == null || !sessionDialog.isShowing()) return;
                new Thread(() -> {
                    try {
                        java.util.List<java.util.Map<String, Object>> light = RemoteDshTool.fetchSessionsLight(RemoteDshConnectActivity.this, PICKER_LIMIT);
                        StringBuilder sb = new StringBuilder();
                        for (java.util.Map<String, Object> m : light) {
                            sb.append(str(m.get("session_id"))).append(':').append(str(m.get("updated_at"))).append(';');
                        }
                        String sig = sb.toString();
                        if (sig.equals(lastLightSig)) return;
                        lastLightSig = sig;
                        java.util.Map<String, Object> meta = RemoteDshTool.fetchSessionsWithMeta(RemoteDshConnectActivity.this, PICKER_LIMIT);
                        java.util.List<java.util.Map<String, Object>> full = extractSessions(meta);
                        String ws = str(meta.get("current_workspace"));
                        String curSid = str(meta.get("current_session_id"));
                        runOnUiThread(() -> {
                            if (sessionDialog == null || !sessionDialog.isShowing()) return;
                            if (full.isEmpty()) return;
                            sessionRows.clear();
                            sessionRows.addAll(full);
                            if (!ws.isEmpty()) currentWorkspace = ws;
                            if (!curSid.isEmpty()) currentPcSessionId = curSid;
                            renderSessionGroups();
                        });
                    } catch (Exception ignored) {
                        // 轮询失败不打扰用户，下个周期再试
                    }
                }, "poll-remote-sessions").start();
            }
        };
        pickerHandler.post(pickerTick);
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
        // 每次回到页面都重读配置：以前只有"探测过"才 refresh，
        // 首次进入时地址/令牌显示停留在布局默认的"(未配置)"（实测 2026-11-13）
        refresh();
        attachConnection();
        // 电脑状态卡自动加载（2026-11-17）：进页面就能看到电脑状态，不必先点「自检 / 对账」。
        // 只在"已连接"时自动跑，避免离线时页面一进来就挂一条红色失败文案。
        if (RemoteDshTool.isConfigured(this) && RemoteDshTool.isConnected(this)) {
            getWindow().getDecorView().postDelayed(() -> {
                try {
                    runSelfCheck();
                } catch (Throwable t) {
                    android.util.Log.w("RemoteDshConnect", "auto self-check: " + t.getMessage());
                }
            }, 250L);
        }
        // 从聊天页顶栏「选电脑会话」进来的：稍等布局稳一下直接弹列表（M8-P0-3）
        if (pickOnResume) {
            pickOnResume = false;
            getWindow().getDecorView().postDelayed(() -> {
                try {
                    pickSession();
                } catch (Throwable t) {
                    android.util.Log.w("RemoteDshConnect", "pickSession on enter: " + t.getMessage());
                }
            }, 300L);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopProbeTicker();
        detachConnection();
    }

    // ===== 订阅全 App 唯一通道（2026-11-12）：页面不再自己开连接 =====

    private com.oilquiz.app.ai.tool.RemoteConnection.Listener connListener;

    private void attachConnection() {
        com.oilquiz.app.ai.tool.RemoteConnection conn =
                com.oilquiz.app.ai.tool.RemoteConnection.get(this);
        if (connListener == null) {
            connListener = new com.oilquiz.app.ai.tool.RemoteConnection.Listener() {
                @Override
                public void onState(java.util.Map<String, Object> state) {
                    runOnUiThread(() -> applyChannelState(state));
                }

                @Override
                public void onView(java.util.Map<String, Object> view) {
                    runOnUiThread(() -> {
                        if (selfCheckCards != null) {
                            RemoteCardView.render(selfCheckCards, view);
                            RemoteCardView.appendBreath(selfCheckCards, true, 0, null, null);
                            selfCheckCards.setVisibility(View.VISIBLE);
                        }
                        if (selfCheckText != null) selfCheckText.setVisibility(View.GONE);
                    });
                }

                @Override
                public void onPulse(long serverTs, int seq) {
                    runOnUiThread(() -> {
                        // 真实心跳帧到达 → 连接灯跳一次（2026-11-17：动画绑定服务，不是装饰）
                        try {
                            com.oilquiz.app.ui.UiFx.pulseOnce(statusDot);
                        } catch (Throwable ignored) { /* 动画失败不影响状态 */ }
                        if (selfCheckCards != null) {
                            RemoteCardView.appendBreath(selfCheckCards, true, 0, null, null);
                        }
                    });
                }

                @Override
                public void onConnection(boolean online, String detail) {
                    runOnUiThread(() -> {
                        if (online) {
                            dot(R.drawable.circle_green);
                            statusText.setText("已连接");
                            statusText.setTextColor(okColor());
                            if (statusHint != null) {
                                statusHint.setText("电脑端推送已连接"
                                        + (detail == null || detail.isEmpty() ? "" : " · " + detail));
                            }
                        } else {
                            boolean noSession = detail != null && detail.contains("还没有绑定会话");
                            dot(noSession ? R.drawable.circle_yellow : R.drawable.circle_yellow);
                            statusText.setText(noSession ? "待建立会话" : "连接中断");
                            statusText.setTextColor(warnColor());
                            if (statusHint != null) {
                                statusHint.setText(noSession
                                        ? "电脑端还没有绑定会话：发第一条消息时由电脑创建并下发 id"
                                        : "正在重连：" + (detail == null ? "" : detail));
                            }
                            if (noSession) applyNoSessionState();
                        }
                    });
                }
            };
        }
        conn.addListener(connListener);
        if (!conn.isStarted() && RemoteDshTool.isConfigured(this)) conn.start();
        if (conn.state() == null) applyNoSessionState();
    }

    private void detachConnection() {
        if (connListener != null) {
            com.oilquiz.app.ai.tool.RemoteConnection.get(this).removeListener(connListener);
        }
    }

    private void applyNoSessionState() {
        if (valueSession != null) valueSession.setText("(未建立)");
        if (valueSyncTarget != null) valueSyncTarget.setText("(首次发送时由电脑建立)");
        if (valueDirect != null) {
            String d = RemoteDshTool.getDirectSessionId(this);
            valueDirect.setText(d.isEmpty() ? "(首次直连时建立，手机专属)" : shortId(d));
        }
        if (statusHint != null) {
            statusHint.setText("电脑端还没有绑定会话：发第一条消息时由电脑创建并下发 id");
        }
    }

    /**
     * 服务端下发的权威状态：会话 id / 标题 / 是否 live / 执行位置。
     * 客户端本地不再有"默认会话"这种东西 —— 有就是有，没有就显示"首次发送时由电脑建立"。
     */
    private void applyChannelState(java.util.Map<String, Object> state) {
        if (state == null) return;
        String id = str(state.get("session_id"));
        String title = str(state.get("title"));
        boolean live = Boolean.TRUE.equals(state.get("live"));
        if (!id.isEmpty()) {
            RemoteDshTool.setSyncTarget(this, id);
            if (!title.isEmpty()) RemoteDshTool.setSyncTitle(this, title);
        }
        if (valueSyncTarget != null) {
            valueSyncTarget.setText(id.isEmpty()
                    ? "(由电脑下发：首次发送时建立)"
                    : (title.isEmpty() ? shortId(id) : title + " · " + shortId(id)));
        }
        // valueSession 是本机历史会话（local），不再被电脑端 id 覆盖
        if (valueSession != null) {
            valueSession.setText(orDash(RemoteDshTool.configValue(this, "session_id")));
        }
        if (statusHint != null && id.isEmpty()) {
            statusHint.setText("会话尚未建立：发第一条消息时由电脑创建并下发 id");
        } else if (statusHint != null) {
            statusHint.setText(live
                    ? "会话正在电脑端运行（实时推送）"
                    : "会话未在电脑端打开（电脑会用 CLI 续写）");
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
        // 「输入配对码」按钮：扫码不方便时的备选（6 位数字，一次性）
        try {
            android.view.View scan = findViewById(R.id.btn_scan);
            if (scan != null && scan.getParent() instanceof LinearLayout) {
                LinearLayout bar = (LinearLayout) scan.getParent();
                // bindActions() 会被多次调用（刷新时重绑），必须先查重再添加，
                // 否则每刷一次就多一个「输入配对码」按钮（真机复现过两个）。
                if (bar.findViewWithTag("pair_btn") != null) return;
                com.google.android.material.button.MaterialButton pairBtn =
                        new com.google.android.material.button.MaterialButton(this);
                pairBtn.setTag("pair_btn");
                pairBtn.setText("输入配对码");
                pairBtn.setTextSize(13);
                pairBtn.setAllCaps(false);
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                plp.leftMargin = dp(8);
                pairBtn.setLayoutParams(plp);
                pairBtn.setOnClickListener(v -> promptPairingCode());
                bar.addView(pairBtn);
            }
        } catch (Throwable ignored) { /* 加不上也不影响扫码 */ }

        disconnectBtn.setOnClickListener(v -> {
            if (!RemoteDshTool.isConfigured(this)) {
                toast("还没配对过电脑");
                return;
            }
            RemoteDshTool.disconnect(this);
            // 断开 = 停掉唯一通道（前台服务一并停），否则后台还会继续收
            com.oilquiz.app.ai.tool.RemoteConnection.get(this).stop();
            stopService(new android.content.Intent(this,
                    com.oilquiz.app.ai.tool.RemoteSyncService.class));
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

        clearBtn.setOnClickListener(v -> showDialogSafe(com.oilquiz.app.ui.modal.AppDialog.builder(this)
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
            // 配置列表：保存成功就落一条（同一地址覆盖，不重复）
            try {
                RemoteProfiles.save(this, text(urlInput), text(tokenInput), "");
        
        renderProfiles();
            } catch (Throwable ignored) { /* 存不了不影响连接 */ }
            refresh();
            probe(true, true);
        });
        // 「输入配对码」按钮：扫码不方便时的备选（6 位数字，一次性）
        try {
            android.view.View scan = findViewById(R.id.btn_scan);
            if (scan != null && scan.getParent() instanceof LinearLayout) {
                LinearLayout bar = (LinearLayout) scan.getParent();
                // bindActions() 会被多次调用（刷新时重绑），必须先查重再添加，
                // 否则每刷一次就多一个「输入配对码」按钮（真机复现过两个）。
                if (bar.findViewWithTag("pair_btn") != null) return;
                com.google.android.material.button.MaterialButton pairBtn =
                        new com.google.android.material.button.MaterialButton(this);
                pairBtn.setTag("pair_btn");
                pairBtn.setText("输入配对码");
                pairBtn.setTextSize(13);
                pairBtn.setAllCaps(false);
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                plp.leftMargin = dp(8);
                pairBtn.setLayoutParams(plp);
                pairBtn.setOnClickListener(v -> promptPairingCode());
                bar.addView(pairBtn);
            }
        } catch (Throwable ignored) { /* 加不上也不影响扫码 */ }

        // 「连接管理」静态按钮：打开独立页面（不再往本页动态插 chips）
        try {
            View profilesBtn = findViewById(R.id.btn_profiles);
            if (profilesBtn != null) {
                profilesBtn.setOnClickListener(v ->
                        com.oilquiz.app.ai.tool.RemoteProfilesActivity.open(this));
            }
        } catch (Throwable t) {
            android.util.Log.w("RemoteDshConnect", "绑定连接管理按钮失败: " + t.getMessage());
        }
        renderProfiles();
    }

    /**
     * 连接配置列表（2026-11-14）：在「手动配置」上方插一行 chips，
     * 点一下就切到那条（填地址+令牌并直接连接），长按删除。
     * 解决"只保存一份配置、局域网与 Tailscale 互相覆盖"的问题。
     */
    private void renderProfiles() {
        try {
            // 固定容器（布局里的 profiles_host）：不再猜父容器结构
            ViewGroup box = findViewById(R.id.profiles_host);
            if (box == null) {
                android.util.Log.w("RemoteDshConnect", "布局里没有 profiles_host");
                return;
            }
            box.removeAllViews();
            View old = box.findViewWithTag("profiles_row");
            if (old != null) box.removeView(old);

            String cur0 = new RemoteDshTool(this).getBaseUrl();
            if (cur0 != null && !cur0.trim().isEmpty()
                    && RemoteProfiles.list(this).isEmpty()) {
                // 首次升级：把当前正在用的这条配置补进列表，否则用户看不到任何可切换项
                RemoteProfiles.save(this, cur0.trim(), new RemoteDshTool(this).getToken(), "当前连接");
            }
            java.util.List<RemoteProfiles.Profile> list = RemoteProfiles.list(this);
            if (list.isEmpty()) return;

            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setTag("profiles_row");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 0, 0, dp(2));
            String current = new RemoteDshTool(this).getBaseUrl();
            for (final RemoteProfiles.Profile p : list) {
                boolean isCurrent = current != null && current.equalsIgnoreCase(p.url);
                com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
                chip.setText(isCurrent ? ("● " + p.label()) : p.label());
                chip.setCheckable(false);
                chip.setClickable(true);
                chip.setTextSize(11);
                chip.setEnsureMinTouchTargetSize(false);
                chip.setOnClickListener(v -> {
                    urlInput.setText(p.url);
                    if (!p.token.isEmpty()) tokenInput.setText(p.token);
                    toast("切到 " + p.label());
                    saveBtn.performClick();
                });
                chip.setOnLongClickListener(v -> {
                    RemoteProfiles.remove(this, p.url);
                    toast("已删除 " + p.label());
            
        renderProfiles();
                    return true;
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                row.addView(chip, lp);
            }
            scroll.addView(row);
            int index = 0;   // 顶部
            box.addView(scroll, Math.max(0, index),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (Throwable t) { android.util.Log.w("RemoteDshConnect", "renderProfiles 失败: " + t, t); }
    }

    /** 输入 6 位配对码 → 换设备令牌 → 保存并连接 */
    private void promptPairingCode() {
        // 2026-11-16：只让用户输 6 位码 —— 地址自动带入。
        // 之前弹框里同时要求「电脑地址 + 配对码」，但地址本来就已经配置过（页面上的地址栏/
        // 上次配对保存的 profile 里都有），让用户再抄一遍纯属多余，也容易抄错端口。
        // 现在：能确定地址时只显示「将连接到 …」一行只读提示；只有首次配对（完全没有地址）才让填地址。
        String known = text(urlInput);
        if (known.isEmpty()) {
            known = com.oilquiz.app.ai.tool.RemoteDshTool.configValue(this, "base_url");
        }
        if (known == null) known = "";
        known = known.trim();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(8), pad, 0);

        final android.widget.EditText urlEt;
        if (known.isEmpty()) {
            urlEt = new android.widget.EditText(this);
            urlEt.setHint("电脑地址，如 http://192.168.1.2:8218");
            box.addView(urlEt);
        } else {
            urlEt = null;
            android.widget.TextView target = new android.widget.TextView(this);
            target.setText("将连接到：" + known);
            target.setTextSize(12f);
            target.setPadding(0, 0, 0, dp(6));
            int hint = com.oilquiz.app.ai.chat.component.ComponentColors.textTertiary(this);
            target.setTextColor(hint);
            box.addView(target);
        }

        final android.widget.EditText codeEt = new android.widget.EditText(this);
        codeEt.setHint("6 位配对码");
        codeEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        box.addView(codeEt);

        final String targetUrl = known;
        com.oilquiz.app.ui.modal.AppDialog.builder(this)
                .setTitle("输入配对码")
                .setView(box)
                .setPositiveButton("连接", (d, w) -> {
                    final String url = (urlEt == null ? targetUrl : urlEt.getText().toString().trim());
                    final String code = codeEt.getText().toString().trim();
                    if (url.isEmpty()) {
                        toast("先填电脑地址（首次配对需要一次，之后只需配对码）");
                        return;
                    }
                    new Thread(() -> {
                        try {
                            String token = RemoteDshTool.claimPairingCode(this, url, code, "Android");
                            runOnUiThread(() -> {
                                urlInput.setText(url);
                                tokenInput.setText(token);
                                RemoteProfiles.save(this, url, token, "");
                                saveBtn.performClick();
                                toast("配对成功");
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> toast("配对失败：" + e.getMessage()));
                        }
                    }, "pair-claim").start();
                })
                .setNegativeButton("取消", null)
                .show();
    }
    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /**
     * 和电脑对齐"当前是哪条电脑会话"。
     *
     * 电脑端是唯一权威（会话 id 在电脑落盘、由插件回传），手机这边只是缓存 ——
     * 这就是"两端说的是同一条对话"的来源。重装/清数据后也靠它接回原来那条。
     */
    private void syncDirectState(boolean toastResult) {
        if (!RemoteDshTool.isConfigured(this)) {
            refresh();
            return;
        }
        new Thread(() -> {
            String id = "";
            String title = "";
            String err = null;
            try {
                java.util.Map<String, Object> st = RemoteDshTool.syncDirectState(getApplicationContext());
                id = st.get("direct_session_id") == null ? "" : String.valueOf(st.get("direct_session_id"));
                title = st.get("title") == null ? "" : String.valueOf(st.get("title"));
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String fId = id;
            final String fTitle = title;
            final String fErr = err;
            runOnUiThread(() -> {
                refresh();
                if (!toastResult) return;
                if (fErr != null) {
                    toast("同步直连会话失败：" + fErr);
                } else if (fId.isEmpty()) {
                    toast("电脑端还没有当前会话：发一条消息后会自动建立");
                } else {
                    toast("已与电脑同步：" + (fTitle.isEmpty() ? shortId(fId) : fTitle));
                }
            });
        }, "remote-dsh-direct-state").start();
    }

    /** 刷新状态区（不联网）：状态点/文案、地址令牌会话、按钮可用性 */
    private void refresh() {
        boolean configured = RemoteDshTool.isConfigured(this);
        boolean connected = RemoteDshTool.isConnected(this);

        if (!configured) {
            dot(R.drawable.circle_red);
            statusText.setText("未配置");
            statusText.setTextColor(errColor());
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
                statusText.setTextColor(okColor());
                statusHint.setText("AI 可以在电脑上执行任务；点「断开」可临时停用（配置保留）。");
            } else {
                dot(R.drawable.circle_yellow);
                statusText.setText("已断开");
                statusText.setTextColor(warnColor());
                statusHint.setText("已停用：AI 会被拒绝执行。点「连接」重新接上。");
            }
        }
        if (configured && text(urlInput).isEmpty()) {
            urlInput.setText(RemoteDshTool.configValue(this, "base_url"));
        }
        if (valueSyncTarget != null) {
            String t = RemoteDshTool.getSyncTarget(this);
            String title = RemoteDshTool.getSyncTitle(this);
            valueSyncTarget.setText(t.isEmpty()
                    ? "(由电脑下发：首次发送时建立)"
                    : (title.isEmpty() ? shortId(t) : title + " · " + shortId(t)));
        }
        if (valueDirect != null) {
            String d = RemoteDshTool.getDirectSessionId(this);
            String dt = RemoteDshTool.getDirectTitle(this);
            valueDirect.setText(d.isEmpty()
                    ? "(首次直连时建立，手机专属)"
                    : (dt.isEmpty() ? shortId(d) : dt + " · " + shortId(d)));
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
        probe(setIntent, toastResult, false);
    }

    /**
     * @param silent true = 自动刷新用：不先写"正在探测…"，只把结果换上去（避免每 5 秒闪一次）
     */
    private void probe(boolean setIntent, boolean toastResult, boolean silent) {
        if (busy) {
            return;
        }
        busy = true;
        probedOnce = true;
        refresh();
        if (!silent) {
            showProbe(COLOR_WARN, "正在探测电脑端桥接…", null);
        }

        new Thread(() -> {
            final long t0 = System.currentTimeMillis();
            String text;
            boolean ok;
            try {
                text = RemoteDshTool.probeStatusText(getApplicationContext());
                ok = true;
            } catch (Exception e) {
                // 2026-11-14：不再把异常原文甩给用户，翻译成「一句话原因 + 现在怎么做」
                com.oilquiz.app.ai.tool.RemoteErrorHints.Hint hint =
                        com.oilquiz.app.ai.tool.RemoteErrorHints.of(e);
                String base = RemoteDshTool.configValue(this, "base_url");
                text = hint.headline + "\n" + (base.isEmpty() ? "地址：(未配置)" : "地址：" + base)
                        + (hint.action.isEmpty() ? "" : "\n" + hint.action);
                ok = false;
                com.oilquiz.app.util.AILogger.w("RemoteDshConnect", "probe failed: " + hint.raw);
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
                    lastProbeDurationMs = ms;
            showProbe(COLOR_OK, "电脑端在线 ✓\n" + fText, null);
                    // 探测成功 = 可以开唯一通道（服务端推送 state/view/事件）
                    com.oilquiz.app.ai.tool.RemoteConnection.get(this).start();
                } else {
                    lastProbeDurationMs = ms;
            showProbe(COLOR_ERR, "连接失败 ✗\n" + fText, null);
                }
                refresh();
                if (fOk) syncDirectState(false);
                if (toastResult) {
                    toast(fOk ? "已连接电脑" : "连接失败，看下方原因");
                }
            });
        }, "remote-dsh-probe").start();
    }


    /**
     * 快捷自测（2026-11-14）：按连接链路逐步检查，直接告诉用户卡在哪一步、为什么、怎么修。
     * 与「自检 / 对账」的区别：那个查连上之后的电脑状态，这个查"为什么连不上"。
     */
    private void runQuickSelfTest() {
        final String url = RemoteDshTool.configValue(this, "base_url");
        String tok = RemoteDshTool.configValue(this, "device_token");
        if (tok == null || tok.trim().isEmpty()) tok = RemoteDshTool.configValue(this, "token");
        final String token = tok == null ? "" : tok.trim();
        showProbe(COLOR_WARN, "正在自测连接链路…", null);
        new Thread(() -> {
            com.oilquiz.app.ai.tool.RemoteSelfTest.Report report =
                    com.oilquiz.app.ai.tool.RemoteSelfTest.run(getApplicationContext(), url, token);
            runOnUiThread(() -> {
                showProbe(report.allOk() ? COLOR_OK : COLOR_ERR,
                        report.allOk() ? "快捷自测：全链路通过 ✅" : "快捷自测发现问题：\n" + report.firstFailureHint(),
                        "自测耗时 " + report.elapsedMs + "ms");
                com.oilquiz.app.ui.modal.AppDialog.builder(this)
                        .setTitle("快捷自测")
                        .setMessage(report.text())
                        .setPositiveButton("复制诊断", (d, w) -> {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                        "dsh-remote-selftest", report.diagnostic()));
                                toast("诊断已复制");
                            }
                        })
                        .setNeutralButton("使用说明", (d, w) -> showHelpOrHint())
                        .setNegativeButton("关闭", null)
                        .show();
            });
        }, "remote-quick-test").start();
    }

    /** 使用说明入口：本页有 guide 就复用，没有就弹一段文本。 */
    /** 连不上时的说明：只给用户能做的动作，不甩命令。 */
    private void showHelpOrHint() {
        com.oilquiz.app.ui.modal.AppDialog.builder(this)
                .setTitle("连不上？按这两步做")
                .setMessage("第 1 步（最常见的原因）\n"
                        + "在电脑上双击 tools\\fix-remote-lan.cmd，弹窗里点「是」，等它自己跑完。\n"
                        + "它只做两件事：允许手机访问电脑、放行 DSH 的端口，不改别的东西。\n"
                        + "\n第 2 步\n"
                        + "确认手机和电脑连的是同一个 WiFi（不是访客 WiFi），然后点「快捷自测」。\n"
                        + "\n还是不行？\n"
                        + "· 「DSH 没在运行」→ 在电脑上打开 DSH，等 10 秒\n"
                        + "· 手机 IP 是 192.168.101.x → 无线路由器要改成桥接，找人帮你改一次就好\n"
                        + "· 换了新手机/重装了 App → 用「扫码配对」重新配一次")
                .setPositiveButton("知道了", null)
                .show();
    }
    private void showProbe(int color, String text, String timeText) {
        probeText.setTextColor(color);
        probeText.setText(text);
        // 2026-11-16：探测时间改成"每秒重算的相对时间"。
        // 旧实现写死 HH:mm:ss，页面静止时看着像时间卡死（用户原话）。
        lastProbeMs = System.currentTimeMillis();
        probeTime.setText(relativeProbeText());
        startProbeTicker();
    }

    /** 最近一次探测距今多少秒（0 = 还没探测过）。 */
    private long lastProbeMs = 0L;
    private long lastProbeDurationMs = 0L;
    private final android.os.Handler probeTicker = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable probeTickTask = new Runnable() {
        @Override
        public void run() {
            if (probeTime != null) {
                probeTime.setText(relativeProbeText());
            }
            probeTicker.postDelayed(this, 1000);
        }
    };

    private String relativeProbeText() {
        if (lastProbeMs <= 0) {
            return "";
        }
        // 文案抽到 RemoteTimeText（可单测）：连接页与扩展面板共用同一套"n 秒前/分钟前"。
        return RemoteTimeText.probeLine(System.currentTimeMillis() - lastProbeMs, lastProbeDurationMs);
    }

    private void startProbeTicker() {
        probeTicker.removeCallbacks(probeTickTask);
        probeTicker.postDelayed(probeTickTask, 1000);
    }

    private void stopProbeTicker() {
        probeTicker.removeCallbacks(probeTickTask);
    }

    private void dot(int drawableId) {
        Drawable d = ContextCompat.getDrawable(this, drawableId);
        statusDot.setBackground(d);
        // 2026-11-17：连接灯由**真实探测/心跳**驱动 —— 每次探测成功（渲染绿色）调一次单脉冲；
        // 不再用无限循环动画，服务没响应时灯就该静止。
        try {
            if (drawableId == R.drawable.circle_green) {
                com.oilquiz.app.ui.UiFx.pulseOnce(statusDot);
            } else {
                com.oilquiz.app.ui.UiFx.stopBreathing(statusDot);
            }
        } catch (Throwable ignored) {
            // 动画失败不影响状态显示
        }
    }

    private void setEnabled(MaterialButton b, boolean enabled) {
        if (b == null) {
            return;
        }
        // 插件总开关优先：关着的时候任何按钮都不该可点（开关本身除外）
        boolean effective = enabled && RemoteDshTool.isPluginEnabled(this);
        b.setEnabled(effective);
        b.setAlpha(effective ? 1f : 0.5f);
    }

    /**
     * 插件总开关的界面联动（2026-11-18）：关闭时禁用本页全部操作按钮、断开已有连接。
     * 开关本身始终可用，否则关了就再也打不开了。
     */
    private void applyPluginGate() {
        boolean on = RemoteDshTool.isPluginEnabled(this);
        int[] ids = {R.id.btn_profiles, R.id.btn_insight, R.id.btn_connect, R.id.btn_disconnect,
                R.id.btn_scan, R.id.btn_clear, R.id.btn_save, R.id.btn_quick_test,
                R.id.btn_self_check, R.id.btn_import_history, R.id.btn_pick_session};
        for (int id : ids) {
            android.view.View v = findViewById(id);
            if (v != null) {
                v.setEnabled(on);
                v.setAlpha(on ? 1f : 0.5f);
            }
        }
        android.view.View hint = findViewById(R.id.plugin_switch_hint);
        if (hint instanceof android.widget.TextView) {
            ((android.widget.TextView) hint).setText(on
                    ? "已启用：可导出/安装电脑端插件并连接，AI 可用 remote_dsh 控制电脑。"
                    : "已关闭（默认）：不导出/不连接电脑，AI 也不再调用 remote_dsh。");
        }
        if (!on) {
            try {
                if (RemoteDshTool.isConnected(this)) {
                    RemoteDshTool.disconnect(this);
                }
            } catch (Throwable ignored) {
                // 断开失败不影响开关本身
            }
            // 停掉常驻同步前台服务：否则通知栏那条"电脑连接"还在，界面却没连
            try {
                stopService(new android.content.Intent(this,
                        com.oilquiz.app.ai.tool.RemoteSyncService.class));
            } catch (Throwable ignored) {
            }
        }
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
