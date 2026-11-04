package com.oilquiz.app.ui.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.agent.online.mcp.McpManager;
import com.oilquiz.app.ai.agent.online.mcp.McpMarketCatalog;
import com.oilquiz.app.ai.agent.online.mcp.McpMarketEntry;
import com.oilquiz.app.ai.agent.online.mcp.McpMarketInstaller;
import com.oilquiz.app.ai.agent.online.mcp.McpOAuthFlow;
import com.oilquiz.app.ai.agent.online.mcp.McpServerConfig;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP 市场页：发现外部 MCP 服务，并把「能在这台手机上用的」装进 {@link McpManager}。
 *
 * <p>这一页只承担两件事——说清某个服务当前的接入能力，以及把可安装的那批装好。
 * 配置的唯一权威仍是 {@link McpManagerActivity}（改端点、引密钥、启停、删除），
 * 所以这里的"安装"是一次<b>带探测的写入</b>：先跑 initialize + tools/list，
 * 通了才默认启用；不通时保存为停用必须由用户再点一次，不给"看起来装好了其实连不上"的状态。</p>
 *
 * <p>列表按能力位分节（可直连 / 需开通 / 仅网页取端点 / 需浏览器授权 / 本地包），
 * 只有前两节给安装入口；后三节给开通路径、官方文档与"为什么现在加不了"。
 * 数据源是 {@code assets/mcp_market_catalog.json}，条目自带文档核对日期与一次真实协议自检的证据。</p>
 */
public class McpMarketActivity extends AppCompatActivity {

    private LinearLayout listContainer;
    private String query = "";
    private Set<String> installedIds = new HashSet<>();

    /** 探测走真实网络，单独一条后台线程；页面销毁即关。 */
    private ExecutorService probeExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        probeExecutor = Executors.newSingleThreadExecutor();
        setContentView(wrapInScrollView(buildPage()));
        renderList();
    }

    @Override
    protected void onDestroy() {
        if (probeExecutor != null) {
            probeExecutor.shutdownNow();
            probeExecutor = null;
        }
        super.onDestroy();
    }

    /** 把页面包进可滚动容器（列表超出屏幕时可上下滚动）。 */
    private View wrapInScrollView(View page) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(page);
        return sv;
    }

    // ==================== 页面构建 ====================

    private View buildPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), dp(16));
        page.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        McpMarketCatalog.Snapshot snap = McpMarketCatalog.load(this);

        // 顶部：返回 + 标题 + 管理页入口
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView backBtn = new TextView(this);
        backBtn.setText("←");
        backBtn.setTextSize(18);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setTextColor(color(R.color.text_primary));
        backBtn.setBackground(buttonBackground(R.color.secondary_container));
        topRow.addView(backBtn, new LinearLayout.LayoutParams(dp(40), dp(36)));
        backBtn.setOnClickListener(v -> finish());

        TextView title = new TextView(this);
        title.setText("MCP 市场");
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.text_primary));
        title.setPadding(dp(10), 0, 0, 0);
        topRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(topRow);

        TextView managerBtn = makeOpButton("⚙ 已配置服务 · 管理", R.color.secondary_container);
        LinearLayout.LayoutParams mgrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mgrLp.setMargins(0, dp(6), 0, 0);
        page.addView(managerBtn, mgrLp);
        managerBtn.setOnClickListener(v ->
                startActivity(new Intent(this, McpManagerActivity.class)));

        TextView desc = new TextView(this);
        desc.setText("内置清单 " + (snap.version.isEmpty() ? "（读取失败）" : snap.version)
                + " · " + snap.entries.size() + " 条\n"
                + "· 前三节可安装：装之前先跑一次 initialize + tools/list，通了才启用\n"
                + "· 「需授权」这条会再打开一次浏览器登录，登记与续期都由 App 完成\n"
                + "· 后两节只给开通路径与官方文档（端点现取 / 本地包跑不起来）\n"
                + (snap.disclaimer.isEmpty() ? "" : "\n" + snap.disclaimer));
        desc.setTextSize(12);
        desc.setTextColor(color(R.color.text_secondary));
        desc.setPadding(0, dp(6), 0, dp(8));
        page.addView(desc);

        // 搜索框
        EditText searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("🔍 搜索服务（名称 / 描述 / 服务商 / 标签）");
        searchInput.setTextSize(13);
        searchInput.setPadding(dp(10), dp(4), dp(10), dp(4));
        searchInput.setBackground(buttonBackground(R.color.surface_variant));
        searchInput.setTextColor(color(R.color.text_primary));
        searchInput.setHintTextColor(color(R.color.text_tertiary));
        page.addView(searchInput);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
            }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                renderList();
            }
        });

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.setMargins(0, dp(8), 0, 0);
        page.addView(listContainer, listLp);

        return page;
    }

    // ==================== 列表渲染 ====================

    private void refreshInstalledIds() {
        installedIds = new HashSet<>();
        for (McpServerConfig cfg : McpManager.getInstance(this).getServers()) {
            installedIds.add(cfg.id);
        }
    }

    private void renderList() {
        refreshInstalledIds();
        listContainer.removeAllViews();

        List<McpMarketEntry> hits = McpMarketCatalog.search(this, query);
        if (hits.isEmpty()) {
            listContainer.addView(emptyView("没有匹配「" + query + "」的服务"));
            return;
        }
        boolean anySection = false;
        for (Map.Entry<McpMarketEntry.Capability, List<McpMarketEntry>> g
                : McpMarketCatalog.groupByCapability(hits).entrySet()) {
            if (g.getValue().isEmpty()) continue;
            anySection = true;
            listContainer.addView(sectionHeader(g.getKey(), g.getValue().size()));
            for (McpMarketEntry e : g.getValue()) {
                listContainer.addView(buildCard(e));
            }
        }
        if (!anySection) {
            listContainer.addView(emptyView("匹配到的条目缺能力位，清单数据有问题"));
        }
    }

    private TextView emptyView(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(color(R.color.text_tertiary));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(24), 0, 0);
        return tv;
    }

    private View sectionHeader(McpMarketEntry.Capability capability, int count) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(6));

        TextView head = new TextView(this);
        head.setText(McpMarketInstaller.capabilityLabel(capability) + " · " + count + " 条");
        head.setTextSize(13);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.setTextColor(color(R.color.text_primary));
        box.addView(head);

        TextView hint = new TextView(this);
        hint.setText(McpMarketInstaller.capabilityHint(capability));
        hint.setTextSize(11);
        hint.setTextColor(color(R.color.text_tertiary));
        hint.setPadding(0, dp(2), 0, 0);
        box.addView(hint);
        return box;
    }

    private View buildCard(final McpMarketEntry e) {
        final boolean installed = installedIds.contains(e.id);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(8));
        card.setBackground(cardBackground());
        card.setLayoutParams(cardLp);

        // 行1：标题
        TextView titleTv = new TextView(this);
        titleTv.setText(e.title);
        titleTv.setTextSize(14);
        titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleTv.setTextColor(color(R.color.text_primary));
        card.addView(titleTv);

        // 行2：徽标组（能力位 + 鉴权 + 已安装）
        LinearLayout badgeRow = new LinearLayout(this);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(Gravity.CENTER_VERTICAL);
        badgeRow.setPadding(0, dp(4), 0, 0);
        badgeRow.addView(badge(McpMarketInstaller.capabilityLabel(e.capability),
                e.capability.isConnectable() ? R.color.primary_container : R.color.surface_variant,
                e.capability.isConnectable() ? R.color.primary : R.color.text_secondary));
        badgeRow.addView(badge(McpMarketInstaller.authLabel(e.auth),
                R.color.surface_variant, R.color.text_secondary), badgeMargins());
        if (installed) {
            badgeRow.addView(badge("已安装", R.color.secondary_container, R.color.text_primary),
                    badgeMargins());
        }
        card.addView(badgeRow);

        // 行3：端点（脱敏）
        McpMarketEntry.Endpoint ep = e.defaultEndpoint();
        if (ep != null) {
            TextView endpointTv = new TextView(this);
            endpointTv.setText(McpServerConfig.maskUrl(ep.url));
            endpointTv.setTextSize(11);
            endpointTv.setTextColor(color(R.color.text_tertiary));
            endpointTv.setSingleLine(true);
            endpointTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            endpointTv.setPadding(0, dp(4), 0, 0);
            card.addView(endpointTv);
            if (!ep.note.isEmpty()) {
                TextView noteTv = new TextView(this);
                noteTv.setText(ep.note);
                noteTv.setTextSize(11);
                noteTv.setTextColor(color(R.color.text_tertiary));
                noteTv.setPadding(0, dp(2), 0, 0);
                card.addView(noteTv);
            }
        }

        // 行4：描述
        if (!e.description.isEmpty()) {
            TextView descTv = new TextView(this);
            descTv.setText(e.description);
            descTv.setTextSize(12);
            descTv.setTextColor(color(R.color.text_secondary));
            descTv.setPadding(0, dp(4), 0, 0);
            card.addView(descTv);
        }

        // 行5：为什么现在加不了
        if (!e.capability.isConnectable() && !e.blockedReason.isEmpty()) {
            TextView blockedTv = new TextView(this);
            blockedTv.setText("⛔ " + e.blockedReason);
            blockedTv.setTextSize(11);
            blockedTv.setTextColor(color(R.color.error));
            blockedTv.setPadding(0, dp(4), 0, 0);
            card.addView(blockedTv);
        }

        // 行6：来源与自检
        TextView sourceTv = new TextView(this);
        sourceTv.setText(McpMarketInstaller.sourceLine(e));
        sourceTv.setTextSize(11);
        sourceTv.setTextColor(color(R.color.text_tertiary));
        sourceTv.setPadding(0, dp(4), 0, 0);
        card.addView(sourceTv);

        // 行7：操作
        LinearLayout opsRow = new LinearLayout(this);
        opsRow.setOrientation(LinearLayout.HORIZONTAL);
        opsRow.setGravity(Gravity.CENTER_VERTICAL);
        opsRow.setPadding(0, dp(8), 0, 0);

        if (e.capability.isConnectable()) {
            TextView installBtn = makeOpButton(installed ? "已在管理页" : "＋ 安装",
                    installed ? R.color.surface_variant : R.color.primary_container);
            installBtn.setOnClickListener(v -> {
                if (installed) {
                    startActivity(new Intent(this, McpManagerActivity.class));
                } else {
                    startInstall(e);
                }
            });
            opsRow.addView(installBtn);
        }

        TextView guideBtn = makeOpButton("💡 指引", R.color.secondary_container);
        guideBtn.setOnClickListener(v -> showGuide(e));
        opsRow.addView(guideBtn, badgeMargins());

        if (ep != null) {
            TextView copyBtn = makeOpButton("📋 复制端点", R.color.secondary_container);
            copyBtn.setOnClickListener(v -> copyText(e.title, ep.url));
            opsRow.addView(copyBtn, badgeMargins());
        }

        if (!e.docsUrl.isEmpty()) {
            TextView docBtn = makeOpButton("🌐 文档", R.color.secondary_container);
            docBtn.setOnClickListener(v -> openUrl(e.docsUrl));
            opsRow.addView(docBtn, badgeMargins());
        }

        card.addView(opsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private LinearLayout.LayoutParams badgeMargins() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), 0, 0, 0);
        return lp;
    }

    private TextView badge(String text, int bgResId, int fgResId) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(10);
        tv.setPadding(dp(6), dp(2), dp(6), dp(2));
        tv.setTextColor(color(fgResId));
        tv.setBackground(pillBackground(bgResId));
        return tv;
    }

    // ==================== 安装向导 ====================

    /** 一次安装过程中的可编辑草稿（表单弹框与探测结果弹框之间传递）。 */
    private static final class InstallDraft {
        String name = "";
        String endpoint = "";
        String secret = "";
        String tokenRef = "";
        String tokenRefLabel = "";
        McpServerConfig.Transport transport = McpServerConfig.Transport.STREAMABLE_HTTP;
    }

    private void startInstall(McpMarketEntry e) {
        InstallDraft draft = new InstallDraft();
        draft.name = e.title;
        McpMarketEntry.Endpoint ep = e.defaultEndpoint();
        if (ep != null) {
            draft.endpoint = ep.url;
            draft.transport = ep.transport;
        }
        showInstallForm(e, draft);
    }

    private void showInstallForm(final McpMarketEntry e, final InstallDraft draft) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), 0);

        form.addView(labeledField("显示名称"));
        final EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setText(draft.name);
        nameInput.setTextSize(13);
        form.addView(nameInput);

        form.addView(labeledField("端点 URL"));
        final EditText endpointInput = new EditText(this);
        endpointInput.setHint("https://…");
        endpointInput.setText(draft.endpoint);
        endpointInput.setTextSize(12);
        form.addView(endpointInput);

        String placeholder = McpMarketEntry.placeholderName(draft.endpoint);
        boolean needsSecret = !e.secretPlaceholder.isEmpty();
        if (!placeholder.isEmpty()) {
            TextView ph = new TextView(this);
            ph.setText(needsSecret
                    ? "端点里的 {" + e.secretPlaceholder + "} 保留不动，Key 填在下面单独的框里（加密保存）"
                    : "要把 {" + placeholder + "} 换成你自己的值：" + e.authHint);
            ph.setTextSize(11);
            ph.setTextColor(needsSecret ? color(R.color.text_tertiary) : color(R.color.error));
            ph.setPadding(0, dp(4), 0, 0);
            form.addView(ph);
        }

        final EditText secretInput = new EditText(this);
        if (needsSecret) {
            secretInput.setSingleLine(true);
            secretInput.setHint("粘贴 Key，不要带空格");
            secretInput.setTextSize(13);
            secretInput.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            secretInput.setText(draft.secret);
            form.addView(labeledField(McpMarketInstaller.secretFieldLabel(e)));
            form.addView(secretInput);
        }

        form.addView(labeledField("🔗 密钥引用"));
        final TextView keyRow = new TextView(this);
        keyRow.setTextSize(12);
        keyRow.setTextColor(color(R.color.text_primary));
        keyRow.setBackground(buttonBackground(R.color.surface_variant));
        keyRow.setPadding(dp(10), dp(8), dp(10), dp(8));
        keyRow.setSingleLine(true);
        keyRow.setEllipsize(android.text.TextUtils.TruncateAt.END);
        keyRow.setClickable(true);
        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        keyLp.setMargins(0, dp(2), 0, 0);
        form.addView(keyRow, keyLp);
        final Runnable refreshKeyRow = () -> keyRow.setText(draft.tokenRefLabel.isEmpty()
                ? "未引用（装好后可在管理页设置）" : draft.tokenRefLabel);
        refreshKeyRow.run();
        keyRow.setOnClickListener(v -> pickKeyRef(e, endpointInput.getText().toString().trim(),
                draft, refreshKeyRow));

        final TextView status = new TextView(this);
        status.setTextSize(11);
        status.setTextColor(color(R.color.text_tertiary));
        status.setPadding(0, dp(8), 0, 0);
        List<String> tips = McpMarketInstaller.advisories(e, draft.endpoint);
        status.setText(String.join("\n", tips));
        form.addView(status);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("安装 " + e.title)
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("测试连接", null)
                .show();
        // 正按钮自己接管：校验不过就原地报错，不能让弹框"点了没反应还顺手关掉"
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String endpoint = endpointInput.getText().toString().trim();
            String secret = needsSecret ? secretInput.getText().toString().trim() : "";
            String error = McpMarketInstaller.validateInstall(e, endpoint, secret);
            if (error != null) {
                status.setText(error);
                status.setTextColor(color(R.color.error));
                return;
            }
            draft.name = McpMarketInstaller.displayName(nameInput.getText().toString(), e);
            draft.endpoint = endpoint;
            draft.secret = secret;
            draft.transport = McpMarketInstaller.transportFor(endpoint,
                    e.defaultEndpoint() != null ? e.defaultEndpoint().transport
                            : McpServerConfig.Transport.STREAMABLE_HTTP);
            runProbe(e, draft, dialog, status);
        });
    }

    private TextView labeledField(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setTextColor(color(R.color.text_tertiary));
        tv.setPadding(0, dp(8), 0, 0);
        return tv;
    }

    /** 密钥候选：先给与该服务商匹配的，再列其余全部（与管理页同一套匹配机制）。 */
    private void pickKeyRef(final McpMarketEntry e, final String endpoint,
                            final InstallDraft draft, final Runnable refresh) {
        McpServerConfig probe = McpMarketInstaller.buildConfig(e, endpoint, draft.name,
                draft.transport, draft.tokenRef, draft.secret, false);
        McpManager mgr = McpManager.getInstance(this);
        final List<String[]> items = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        labels.add("（不引用密钥）");
        items.add(new String[]{"", ""});
        List<String[]> matches = mgr.findMatchingKeyRefs(probe);
        for (String[] m : matches) {
            labels.add(m[0]);
            items.add(m);
        }
        for (String[] a : mgr.findAllKeyRefs()) {
            boolean dup = false;
            for (String[] m : matches) {
                if (m[1].equals(a[1])) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                labels.add(a[0]);
                items.add(a);
            }
        }
        if (matches.isEmpty()) {
            labels.set(0, "（不引用密钥）· 没识别出该服务商的已有密钥，下面是全部");
        }
        new AlertDialog.Builder(this)
                .setTitle("选择要引用的密钥")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String[] picked = items.get(which);
                    draft.tokenRef = picked[1];
                    draft.tokenRefLabel = picked[1].isEmpty() ? "" : picked[0];
                    refresh.run();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 授权成功后的复测：没有表单可挂进度，用 toast 起、结果弹框收。 */
    private void runProbe(final McpMarketEntry e, final InstallDraft draft) {
        runProbe(e, draft, null, null);
    }

    /**
     * 先探测再落盘：网络在后台跑，回到主线程只改界面。
     *
     * <p>进度就写在表单自己的状态行里，不再额外弹一个"正在连接…"的模态框——
     * 那等于让用户在一次安装里连点三下、还盯着一个只有"中止"的空框。
     * 传进来的表单在探测期间禁用按钮，关掉表单即视为放弃这次结果。</p>
     */
    private void runProbe(final McpMarketEntry e, final InstallDraft draft,
                          final AlertDialog form, final TextView status) {
        if (form != null) {
            status.setText("正在连接并枚举工具…（关掉本框即中止）\n"
                    + McpServerConfig.maskUrl(draft.endpoint));
            status.setTextColor(color(R.color.text_tertiary));
            form.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            form.getButton(AlertDialog.BUTTON_NEGATIVE).setText("中止");
        } else {
            Toast.makeText(this, "正在复测连接…", Toast.LENGTH_SHORT).show();
        }
        // 中止/销毁只丢掉这一次结果，不能关掉整条执行器——否则第二次安装会静默无响应
        final boolean[] cancelled = {false};
        if (form != null) form.setOnDismissListener(d -> cancelled[0] = true);

        final McpServerConfig cfg = McpMarketInstaller.buildConfig(e, draft.endpoint, draft.name,
                draft.transport, draft.tokenRef, draft.secret, false);
        final McpManager mgr = McpManager.getInstance(this);
        final ExecutorService exec = probeExecutor;
        if (exec == null || exec.isShutdown()) {
            if (form != null) form.dismiss();
            Toast.makeText(this, "页面正在关闭，没发起测试", Toast.LENGTH_SHORT).show();
            return;
        }
        exec.execute(() -> {
            final McpManager.ProbeResult result = mgr.probe(cfg);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed() || cancelled[0]) return;
                if (form != null) {
                    try {
                        form.dismiss();
                    } catch (Exception ignored) {
                    }
                }
                showProbeResult(e, draft, cfg, result);
            });
        });
    }

    private void showProbeResult(final McpMarketEntry e, final InstallDraft draft,
                                 final McpServerConfig probed,
                                 final McpManager.ProbeResult result) {
        StringBuilder msg = new StringBuilder();
        msg.append(McpMarketInstaller.describeProbe(result));
        if (result.authRequired) {
            msg.append("\n\n").append(McpMarketInstaller.authorizationHint(e));
        } else if (!result.ok) {
            String hints = McpMarketInstaller.failureHintsText(e, result);
            if (!hints.isEmpty()) msg.append("\n\n").append(hints);
        } else if (draft.tokenRef.isEmpty()
                && e.auth != McpMarketEntry.Auth.NONE
                && e.auth != McpMarketEntry.Auth.QUERY_KEY) {
            msg.append("\n\n没引用密钥就连通了，说明这个端点当前免凭证；"
                    + "调用工具时若报鉴权错误，到管理页补引用。");
        }
        msg.append("\n\n传输：").append(probed.transport == McpServerConfig.Transport.SSE
                ? "SSE" : "Streamable HTTP");
        msg.append("\n名称：").append(probed.name);

        if (result.authRequired) {
            // 授权必须先有 serverId 才能存令牌，所以这一步会把这条落盘成停用项——
            // 说清楚，否则用户会以为"还没点启用怎么就多了条配置"
            new AlertDialog.Builder(this)
                    .setTitle("要走一次浏览器授权")
                    .setMessage(msg + "\n\n点「去授权」会先把这条存成停用项，再打开浏览器；"
                            + "你在浏览器里同意后会回到这里自动复测并启用。")
                    .setPositiveButton("🔐 去授权", (d, w) -> {
                        McpManager.getInstance(this).upsertServer(probed.withEnabled(false));
                        renderList();
                        startAuthorization(e, draft, probed);
                    })
                    .setNeutralButton("存为停用", (d, w) ->
                            finishInstall(e, probed.withEnabled(false), false))
                    .setNegativeButton("返回修改", (d, w) -> showInstallForm(e, draft))
                    .show();
            return;
        }
        if (result.ok) {
            new AlertDialog.Builder(this)
                    .setTitle("可以启用")
                    .setMessage(msg)
                    .setPositiveButton("启用并保存", (d, w) ->
                            finishInstall(e, probed.withEnabled(true), true))
                    .setNegativeButton("返回修改", (d, w) -> showInstallForm(e, draft))
                    .show();
            return;
        }
        // 失败时的三档都是显式动作：默认不写"启用"，停用保存与强行启用各点一次
        new AlertDialog.Builder(this)
                .setTitle("没连上")
                .setMessage(msg.toString() + "\n\n默认不启用。可以先存成停用项，改好再到管理页开。")
                .setPositiveButton("存为停用", (d, w) ->
                        finishInstall(e, probed.withEnabled(false), false))
                .setNeutralButton("仍要启用", (d, w) ->
                        finishInstall(e, probed.withEnabled(true), false))
                .setNegativeButton("返回修改", (d, w) -> showInstallForm(e, draft))
                .show();
    }

    /**
     * 从市场直接发起一次浏览器授权，成功后自动复测。
     *
     * <p>"打开浏览器"必须回主线程做（Activity 启动不是线程安全的入口），授权本身在
     * {@link McpManager} 的授权线程上跑——它最长阻塞 5 分钟，不能占用同步线程。</p>
     */
    private void startAuthorization(final McpMarketEntry e, final InstallDraft draft,
                                    final McpServerConfig probed) {
        Toast.makeText(this, "正在准备授权：会先向服务商登记本机，再打开浏览器",
                Toast.LENGTH_LONG).show();
        McpManager.getInstance(this).authorizeServer(probed.id, new McpOAuthFlow.Ui() {
            @Override
            public void openBrowser(String authorizeUrl) {
                mainHandler.post(() -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl)));
                    } catch (Exception ex) {
                        Toast.makeText(McpMarketActivity.this,
                                "打不开授权页：" + ex.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void stage(String text) {
                mainHandler.post(() -> Toast.makeText(McpMarketActivity.this,
                        text, Toast.LENGTH_SHORT).show());
            }
        }, outcome -> mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (outcome.ok) {
                Toast.makeText(this, "授权成功，正在复测连接", Toast.LENGTH_LONG).show();
                runProbe(e, draft);
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("授权没完成")
                    .setMessage(outcome.message
                            + "\n\n这条已存成停用项，可以到管理页再点「🔐 授权」重试。")
                    .setPositiveButton("知道了", (d, w) -> renderList())
                    .show();
        }));
    }

    private void finishInstall(McpMarketEntry e, McpServerConfig cfg, boolean probeOk) {
        McpManager.getInstance(this).upsertServer(cfg);
        AILogger.audit("McpMarket", "安装 " + e.title + "（" + cfg.id + "）端点 "
                + McpServerConfig.maskUrl(cfg.url) + " 传输=" + cfg.transport
                + " 启用=" + cfg.enabled + " 探测=" + (probeOk ? "通过" : "未通过"));
        Toast.makeText(this, cfg.enabled
                        ? "已启用：" + cfg.name + "，工具进目录后由模型按需取用"
                        : "已保存为停用：修好端点/密钥后到管理页启用",
                Toast.LENGTH_LONG).show();
        renderList();
    }

    // ==================== 指引 / 复制 / 文档 ====================

    private void showGuide(McpMarketEntry e) {
        StringBuilder sb = new StringBuilder();
        if (!e.guide.isEmpty()) {
            sb.append(e.guideText());
        } else {
            sb.append("清单里这条没写开通步骤。");
        }
        McpMarketEntry.Endpoint ep = e.defaultEndpoint();
        if (ep != null) {
            sb.append("\n\n端点：").append(ep.url);
            if (!ep.note.isEmpty()) sb.append("\n说明：").append(ep.note);
        }
        sb.append("\n鉴权：").append(e.authHint.isEmpty() ? "未标注" : e.authHint);
        if (!e.capability.isConnectable()) {
            sb.append("\n\n为什么不能在这里安装：")
              .append(e.blockedReason.isEmpty()
                      ? McpMarketInstaller.capabilityHint(e.capability) : e.blockedReason);
        }
        if (e.probe != null && !e.probe.evidence.isEmpty()) {
            sb.append("\n\n自检（").append(e.probe.checkedAt).append("）：").append(e.probe.evidence);
        }
        if (!e.consoleUrl.isEmpty()) {
            sb.append("\n控制台：").append(e.consoleUrl);
        }
        if (!e.tags.isEmpty()) {
            sb.append("\n标签：").append(String.join(" / ", e.tags));
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(e.title + " · 接入指引")
                .setMessage(sb.toString());
        if (ep != null) {
            builder.setNeutralButton("复制端点", (d, w) -> copyText(e.title, ep.url));
        }
        if (!e.docsUrl.isEmpty()) {
            builder.setPositiveButton("打开文档", (d, w) -> openUrl(e.docsUrl));
        }
        if (!e.consoleUrl.isEmpty()) {
            builder.setNegativeButton("打开控制台", (d, w) -> openUrl(e.consoleUrl));
        } else {
            builder.setNegativeButton("关闭", null);
        }
        builder.show();
    }

    private void copyText(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(this, "端点已复制到剪贴板", Toast.LENGTH_SHORT).show();
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ex) {
            Toast.makeText(this, "打不开该链接：" + ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private TextView makeOpButton(String text, int colorResId) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(11);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(color(R.color.text_primary));
        btn.setBackground(buttonBackground(colorResId));
        btn.setPadding(dp(8), dp(6), dp(8), dp(6));
        return btn;
    }

    // ==================== 样式辅助 ====================

    private int color(int resId) {
        return ThemeColors.get(this, resId);
    }

    private GradientDrawable cardBackground() {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color(R.color.surface_variant));
        gd.setCornerRadius(dp(10));
        gd.setStroke(dp(1), color(R.color.outline));
        return gd;
    }

    private GradientDrawable pillBackground(int containerResId) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color(containerResId));
        gd.setCornerRadius(dp(8));
        return gd;
    }

    private GradientDrawable buttonBackground(int colorResId) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color(colorResId));
        gd.setCornerRadius(dp(6));
        return gd;
    }

    private int dp(float value) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
