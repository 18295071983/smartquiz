package com.oilquiz.app.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.agent.online.mcp.McpClient;
import com.oilquiz.app.ai.agent.online.mcp.McpConstants;
import com.oilquiz.app.ai.agent.online.mcp.McpManager;
import com.oilquiz.app.ai.agent.online.mcp.McpMarketCatalog;
import com.oilquiz.app.ai.agent.online.mcp.McpMarketEntry;
import com.oilquiz.app.ai.agent.online.mcp.McpMarketInstaller;
import com.oilquiz.app.ai.agent.online.mcp.McpServerConfig;
import com.oilquiz.app.ai.model.APIConfig;
import com.oilquiz.app.ai.util.APIKeyManager;
import com.oilquiz.app.theme.ThemeColors;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 服务管理页：列表 / 启用 / 填 token / 查看工具与状态。
 *
 * <p>每个 server 一张卡片：状态 badge（ok:N / disabled / error）、传输类型与 URL、
 * 启用开关、令牌编辑、工具列表、删除；顶部提供「全部刷新」与「添加 server」。</p>
 *
 * <p>数据操作全部走 {@link McpManager}（后台执行），页面自动刷新拉取最新状态。</p>
 */
public class McpManagerActivity extends AppCompatActivity {

    private LinearLayout listContainer;      // 服务列表
    private LinearLayout keyContainer;       // 密钥列表
    private LinearLayout serviceSection;     // 「服务」分支容器
    private LinearLayout keySection;         // 「密钥」分支容器
    private TextView serviceTabBtn;
    private TextView keyTabBtn;
    private String currentSection = "service";
    private final Handler autoRefreshHandler = new Handler(Looper.getMainLooper());
    private static final long AUTO_REFRESH_INTERVAL = 2500L;
    private final Runnable autoRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshList();
            autoRefreshHandler.postDelayed(this, AUTO_REFRESH_INTERVAL);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(wrapInScrollView(buildPage()));
        // 管理页也可能是进程启动后的第一个入口：主动触发一次同步，状态才不是“未同步”
        McpManager.getInstance(this).init();
        refreshList();
    }

    /** 把页面包进可滚动容器（列表超出屏幕时可上下滚动）。 */
    private View wrapInScrollView(View page) {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(page);
        return sv;
    }

    @Override
    protected void onResume() {
        super.onResume();
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
        autoRefreshHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL);
    }

    @Override
    protected void onPause() {
        super.onPause();
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
    }

    // ==================== 页面构建 ====================

    private View buildPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), dp(16));
        page.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 顶部行：返回 + 标题
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView backBtn = new TextView(this);
        backBtn.setText("←");
        backBtn.setTextSize(18);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setTextColor(color(R.color.text_primary));
        backBtn.setBackground(buttonBackground(R.color.secondary_container));
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(40), dp(36));
        topRow.addView(backBtn, backLp);
        backBtn.setOnClickListener(v -> finish());

        TextView title = new TextView(this);
        title.setText("MCP 服务管理");
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.text_primary));
        title.setPadding(dp(10), 0, 0, 0);
        topRow.addView(title);
        page.addView(topRow);

        // 分支页签：服务（端点） / 密钥
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);

        serviceTabBtn = new TextView(this);
        serviceTabBtn.setText("🛰 服务（端点）");
        serviceTabBtn.setTextSize(13);
        serviceTabBtn.setGravity(Gravity.CENTER);
        serviceTabBtn.setTextColor(color(R.color.text_primary));
        serviceTabBtn.setBackground(pillBackground(R.color.primary_container));
        LinearLayout.LayoutParams svcTabLp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        tabRow.addView(serviceTabBtn, svcTabLp);
        serviceTabBtn.setOnClickListener(v -> switchSection("service"));

        keyTabBtn = new TextView(this);
        keyTabBtn.setText("🔑 密钥");
        keyTabBtn.setTextSize(13);
        keyTabBtn.setGravity(Gravity.CENTER);
        keyTabBtn.setTextColor(color(R.color.text_primary));
        keyTabBtn.setBackground(pillBackground(R.color.surface_variant));
        LinearLayout.LayoutParams keyTabLp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        keyTabLp.setMargins(dp(8), 0, 0, 0);
        tabRow.addView(keyTabBtn, keyTabLp);
        keyTabBtn.setOnClickListener(v -> switchSection("key"));
        page.addView(tabRow);

        // ===== 服务分支：描述 + 操作行 + 服务列表 =====
        serviceSection = new LinearLayout(this);
        serviceSection.setOrientation(LinearLayout.VERTICAL);

        TextView desc = new TextView(this);
        desc.setText("连接 Model Context Protocol 标准服务：启用后自动同步远端工具"
                + "（mcp__服务名__工具名），对话里即可调用。端点只管 URL/传输，密钥在「🔑 密钥」分支按服务商管理并引用。");
        desc.setTextSize(12);
        desc.setTextColor(color(R.color.text_secondary));
        desc.setPadding(0, dp(6), 0, dp(8));
        serviceSection.addView(desc);

        // 操作行：全部刷新 + 市场 + 添加
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView refreshBtn = new TextView(this);
        refreshBtn.setText("🔄 刷新");
        refreshBtn.setTextSize(13);
        refreshBtn.setGravity(Gravity.CENTER);
        refreshBtn.setTextColor(color(R.color.text_primary));
        refreshBtn.setBackground(buttonBackground(R.color.secondary_container));
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        actionRow.addView(refreshBtn, refreshLp);
        refreshBtn.setOnClickListener(v -> {
            McpManager.getInstance(this).refreshAll();
            Toast.makeText(this, "正在后台同步全部 MCP 服务…", Toast.LENGTH_SHORT).show();
        });

        TextView marketBtn = new TextView(this);
        marketBtn.setText("🛒 市场");
        marketBtn.setTextSize(13);
        marketBtn.setGravity(Gravity.CENTER);
        marketBtn.setTextColor(color(R.color.text_primary));
        marketBtn.setBackground(buttonBackground(R.color.tertiary_container));
        LinearLayout.LayoutParams marketLp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        marketLp.setMargins(dp(8), 0, 0, 0);
        actionRow.addView(marketBtn, marketLp);
        marketBtn.setOnClickListener(v ->
                startActivity(new Intent(this, McpMarketActivity.class)));

        TextView addBtn = new TextView(this);
        addBtn.setText("＋ 添加");
        addBtn.setTextSize(13);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setTextColor(color(R.color.text_primary));
        addBtn.setBackground(buttonBackground(R.color.primary_container));
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        addLp.setMargins(dp(8), 0, 0, 0);
        actionRow.addView(addBtn, addLp);
        addBtn.setOnClickListener(v -> showAddDialog());
        serviceSection.addView(actionRow);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.setMargins(0, dp(8), 0, 0);
        serviceSection.addView(listContainer, listLp);
        page.addView(serviceSection);

        // ===== 密钥分支：说明 + 添加按钮 + 分组列表 =====
        keySection = new LinearLayout(this);
        keySection.setOrientation(LinearLayout.VERTICAL);
        keySection.setVisibility(View.GONE);

        TextView keyDesc = new TextView(this);
        keyDesc.setText("密钥与 MCP 端点分离管理：按服务商存好密钥，服务只做「引用」"
                + "（自动匹配同服务商，避免跨平台 key 乱用）。密钥加密保存在系统 Keystore。"
                + "改完密钥后点顶部「🔄 刷新」重建连接，引用才会用上新值。");
        keyDesc.setTextSize(12);
        keyDesc.setTextColor(color(R.color.text_secondary));
        keyDesc.setPadding(0, dp(6), 0, dp(8));
        keySection.addView(keyDesc);

        TextView addKeyBtn = new TextView(this);
        addKeyBtn.setText("＋ 添加密钥");
        addKeyBtn.setTextSize(13);
        addKeyBtn.setGravity(Gravity.CENTER);
        addKeyBtn.setTextColor(color(R.color.text_primary));
        addKeyBtn.setBackground(buttonBackground(R.color.primary_container));
        LinearLayout.LayoutParams addKeyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        addKeyBtn.setLayoutParams(addKeyLp);
        keySection.addView(addKeyBtn);
        addKeyBtn.setOnClickListener(v -> showAddKeyDialog());

        keyContainer = new LinearLayout(this);
        keyContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams keyListLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        keyListLp.setMargins(0, dp(8), 0, 0);
        keySection.addView(keyContainer, keyListLp);
        page.addView(keySection);

        return page;
    }

    /** 切换「服务 / 密钥」分支。 */
    private void switchSection(String section) {
        if (currentSection.equals(section)) return;
        currentSection = section;
        serviceTabBtn.setBackground(pillBackground("service".equals(section)
                ? R.color.primary_container : R.color.surface_variant));
        keyTabBtn.setBackground(pillBackground("key".equals(section)
                ? R.color.primary_container : R.color.surface_variant));
        serviceSection.setVisibility("service".equals(section) ? View.VISIBLE : View.GONE);
        keySection.setVisibility("key".equals(section) ? View.VISIBLE : View.GONE);
        refreshList();
    }

    // ==================== 列表 ====================

    private void refreshList() {
        if ("key".equals(currentSection)) {
            renderKeyList();
            return;
        }
        if (listContainer == null) return;
        List<McpServerConfig> servers = McpManager.getInstance(this).getServers();
        Map<String, String> statusMap = McpManager.getInstance(this).getStatus();

        listContainer.removeAllViews();
        if (servers.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无 MCP 服务配置，点上方「＋ 添加服务」新增");
            empty.setTextSize(13);
            empty.setTextColor(color(R.color.text_tertiary));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(20), 0, 0);
            listContainer.addView(empty);
            return;
        }
        for (McpServerConfig cfg : servers) {
            listContainer.addView(buildServerCard(cfg, statusMap));
        }
    }

    // ==================== 密钥分支 ====================

    /** 按服务商分组渲染密钥列表（密钥库条目可增删；在线配置条目只读提示）。 */
    private void renderKeyList() {
        if (keyContainer == null) return;
        keyContainer.removeAllViews();

        APIKeyManager keys = APIKeyManager.getInstance(this);
        List<String[]> keyStore = new ArrayList<>();   // {键名, 脱敏值}
        for (String s : keys.getAllServiceKeys()) {
            String k = keys.getAPIKey(s);
            if (k != null && !k.isEmpty()) keyStore.add(new String[]{s, maskSecret(k)});
        }
        List<APIConfig> online = keys.getAllAPIConfigs();

        // —— 密钥库分组 ——
        keyContainer.addView(sectionHeader("密钥库（MCP 专用，Keystore 加密）"));
        if (keyStore.isEmpty()) {
            keyContainer.addView(emptyHint("暂无密钥，点上方「＋ 添加密钥」"));
        } else {
            for (String[] item : keyStore) {
                keyContainer.addView(buildKeyCard(item[0], item[1], true));
            }
        }

        // —— 在线配置分组（只读） ——
        keyContainer.addView(sectionHeader("在线配置（大模型 / 外部 API，来自「API 配置管理」）"));
        int onlineCount = 0;
        for (APIConfig c : online) {
            String k = c.getApiKey();
            if (k == null || k.isEmpty()) continue;
            onlineCount++;
            keyContainer.addView(buildKeyCard(
                    c.getName() + "  (" + c.getServiceType() + ")",
                    maskSecret(k), false));
        }
        if (onlineCount == 0) {
            keyContainer.addView(emptyHint("暂无在线配置，或配置中未填密钥"));
        }
        keyContainer.addView(sectionHeader("引用规则"));
        keyContainer.addView(emptyHint("服务分支的「🔗 密钥」只推荐引用与服务商匹配的密钥（阿里云系 ↔ 阿里云系…），"
                + "不会让大模型 key 混用到 MCP 服务，避免鉴权失败。"));
    }

    private TextView sectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextColor(color(R.color.text_primary));
        tv.setPadding(0, dp(8), 0, dp(4));
        return tv;
    }

    private TextView emptyHint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(color(R.color.text_tertiary));
        tv.setPadding(0, dp(4), 0, dp(4));
        return tv;
    }

    /** 一条密钥卡片：名称 + 脱敏值 + （密钥库条目可删除）。 */
    private View buildKeyCard(final String label, final String masked, final boolean removable) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(6));
        card.setBackground(cardBackground());
        card.setLayoutParams(cardLp);

        TextView nameTv = new TextView(this);
        nameTv.setText(label);
        nameTv.setTextSize(12);
        nameTv.setTextColor(color(R.color.text_primary));
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(nameTv);

        TextView valTv = new TextView(this);
        valTv.setText(masked);
        valTv.setTextSize(11);
        valTv.setTextColor(color(R.color.text_secondary));
        card.addView(valTv);

        if (removable) {
            TextView delBtn = new TextView(this);
            delBtn.setText("🗑");
            delBtn.setTextSize(13);
            delBtn.setGravity(Gravity.CENTER);
            delBtn.setTextColor(color(R.color.error));
            delBtn.setBackground(buttonBackground(R.color.error_container));
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(dp(36), dp(28));
            delLp.setMargins(dp(8), 0, 0, 0);
            card.addView(delBtn, delLp);
            delBtn.setOnClickListener(v -> confirmRemoveKey(label));
        }
        return card;
    }

    /** 新增密钥：键名 + 值 → APIKeyManager（Keystore 加密保存）。 */
    private void showAddKeyDialog() {
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint("密钥名称（如 aliyun-ak、tencent-map-key）");
        nameInput.setTextColor(color(R.color.text_primary));
        EditText valueInput = new EditText(this);
        valueInput.setSingleLine(true);
        valueInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        valueInput.setHint("密钥值");
        valueInput.setTextColor(color(R.color.text_primary));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(nameInput);
        valueInput.setPadding(0, dp(10), 0, 0);
        content.addView(valueInput);

        new AlertDialog.Builder(this)
                .setTitle("添加密钥")
                .setMessage("命名建议带上服务商（aliyun / tencent / dashscope …），"
                        + "「引用」时会按服务商自动匹配。")
                .setView(content)
                .setPositiveButton("保存", (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    String value = valueInput.getText().toString().trim();
                    if (name.isEmpty() || value.isEmpty()) {
                        Toast.makeText(this, "名称和密钥值都不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    APIKeyManager.getInstance(this).saveAPIKey(name, value);
                    McpManager.getInstance(this).refreshTokenRef(name);
                    Toast.makeText(this, "密钥「" + name + "」已加密保存，引用它的服务正在重新同步",
                            Toast.LENGTH_SHORT).show();
                    renderKeyList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRemoveKey(final String service) {
        new AlertDialog.Builder(this)
                .setTitle("删除密钥")
                .setMessage("确定删除密钥「" + service + "」？引用它的 MCP 服务将失去令牌，"
                        + "其他使用同名密钥的功能也会受影响。")
                .setPositiveButton("删除", (d, w) -> {
                    APIKeyManager.getInstance(this).removeAPIKey(service);
                    McpManager.getInstance(this).refreshTokenRef(service);
                    Toast.makeText(this, "密钥已删除，引用它的服务正在重新同步", Toast.LENGTH_SHORT).show();
                    renderKeyList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private View buildServerCard(final McpServerConfig cfg, Map<String, String> statusMap) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(8));
        card.setBackground(cardBackground());
        card.setLayoutParams(cardLp);

        // 行1：名称 + 状态 badge
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView nameTv = new TextView(this);
        nameTv.setText(cfg.name + (cfg.builtin ? "" : "  (手动添加)"));
        nameTv.setTextSize(15);
        nameTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        nameTv.setTextColor(color(R.color.text_primary));
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nameRow.addView(nameTv);

        TextView badge = new TextView(this);
        String status = statusMap.get(cfg.id);
        badge.setText(statusText(status, cfg.enabled));
        badge.setTextSize(11);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        badge.setTextColor(badgeTextColor(status, cfg.enabled));
        badge.setBackground(pillBackground(badgeBgColor(status, cfg.enabled)));
        badge.setOnClickListener(v -> showStatusDialog(cfg, status));
        nameRow.addView(badge);
        card.addView(nameRow);

        // 行2：URL + 传输
        TextView urlTv = new TextView(this);
        urlTv.setText(McpServerConfig.maskUrl(cfg.url) + "  ·  " + transportLabel(cfg.transport));
        urlTv.setTextSize(11);
        urlTv.setTextColor(color(R.color.text_tertiary));
        urlTv.setSingleLine(true);
        urlTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        urlTv.setPadding(0, dp(4), 0, 0);
        card.addView(urlTv);

        // 行3：令牌状态（已填 / 引用密钥 / 未填）
        TextView tokenStateTv = new TextView(this);
        String tokenState;
        if (cfg.token != null && !cfg.token.isEmpty()) {
            tokenState = cfg.token.startsWith("enc:")
                    ? "令牌：解密失败，请重新填写或改用引用"
                    : "令牌：已填写";
        } else if (cfg.tokenRef != null && !cfg.tokenRef.isEmpty()) {
            String resolved = McpManager.getInstance(this).resolveToken(cfg);
            String resolvedState;
            if (resolved.isEmpty()) {
                resolvedState = "（密钥库无值，需先填密钥）";
            } else if (resolved.startsWith("enc:")) {
                resolvedState = "（密钥解密失败，请到密钥分支重填）";
            } else {
                resolvedState = "（已从密钥库解析）";
            }
            tokenState = "令牌：引用「" + cfg.tokenRef + "」" + resolvedState;
        } else {
            tokenState = "令牌：未填写";
        }
        tokenStateTv.setText(tokenState);
        tokenStateTv.setTextSize(11);
        tokenStateTv.setTextColor(cfg.tokenRef != null && !cfg.tokenRef.isEmpty()
                ? color(R.color.primary) : color(R.color.text_tertiary));
        tokenStateTv.setPadding(0, dp(2), 0, 0);
        card.addView(tokenStateTv);

        // 行4：启用开关 + 操作按钮
        LinearLayout opsRow = new LinearLayout(this);
        opsRow.setOrientation(LinearLayout.HORIZONTAL);
        opsRow.setGravity(Gravity.CENTER_VERTICAL);
        opsRow.setPadding(0, dp(8), 0, 0);

        Switch enableSwitch = new Switch(this);
        enableSwitch.setText(cfg.enabled ? "已启用" : "已停用");
        enableSwitch.setTextSize(12);
        enableSwitch.setTextColor(color(R.color.text_secondary));
        enableSwitch.setChecked(cfg.enabled);
        enableSwitch.setLayoutParams(new LinearLayout.LayoutParams(0, dp(32), 1.2f));
        enableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            McpManager.getInstance(this).setEnabled(cfg.id, isChecked);
            Toast.makeText(this, (isChecked ? "已启用 " : "已停用 ") + cfg.name, Toast.LENGTH_SHORT).show();
            refreshList();
        });
        opsRow.addView(enableSwitch);

        boolean hasToken = cfg.token != null && !cfg.token.isEmpty();
        boolean hasTokenRef = cfg.tokenRef != null && !cfg.tokenRef.isEmpty();
        opsRow.addView(createOpButton("🔗 密钥",
                R.color.secondary_container, v -> showTokenDialog(cfg)));
        opsRow.addView(createOpButton("🔧 工具", R.color.primary_container,
                v -> showToolsDialog(cfg)));
        opsRow.addView(createOpButton("✏ 编辑", R.color.secondary_container,
                v -> showEditDialog(cfg)));
        opsRow.addView(createOpButton("🗑 删除", R.color.error_container,
                v -> confirmRemove(cfg)));
        card.addView(opsRow);

        return card;
    }

    private TextView createOpButton(String text, int bgColor, View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setSingleLine(true);
        btn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(color(R.color.text_primary));
        btn.setBackground(buttonBackground(bgColor));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1f);
        lp.setMargins(dp(4), 0, 0, 0);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(listener);
        return btn;
    }

    // ==================== 状态文案/颜色 ====================

    private String statusText(String status, boolean enabled) {
        if (!enabled) return "停用";
        if (status == null) return "未同步";
        if ("syncing".equals(status)) return "同步中";
        if ("disabled".equals(status)) return "未同步";
        if (status.startsWith("ok:")) return "已同步 " + status.substring(3) + " 工具";
        if (status.startsWith("error:")) return "异常";
        return status;
    }

    /** 状态详情：错误不能只显示“异常”，要能直接看到原因和下一步。 */
    private void showStatusDialog(final McpServerConfig cfg, String status) {
        StringBuilder sb = new StringBuilder();
        sb.append("端点: ").append(McpServerConfig.maskUrl(cfg.url)).append("\n");
        sb.append("传输: ").append(transportLabel(cfg.transport)).append("\n\n");
        if (!cfg.enabled) {
            sb.append("服务已停用，不会连接，也不会向模型暴露工具。");
        } else if (status == null || "disabled".equals(status)) {
            sb.append("尚未同步。点「立即刷新」或等待后台同步。");
        } else if ("syncing".equals(status)) {
            sb.append("正在连接服务并拉取 tools/list…");
        } else if (status.startsWith("ok:")) {
            sb.append("已同步 ").append(status.substring(3))
                    .append(" 个工具（按需注册：模型 get 命中后才注入工具集）。");
        } else if (status.startsWith("error:")) {
            String err = status.substring("error:".length());
            sb.append("同步失败：\n").append(err).append("\n\n下一步：\n");
            String lower = err.toLowerCase();
            if (lower.contains("401") || lower.contains("403") || lower.contains("鉴权")
                    || lower.contains("unauthorized") || lower.contains("forbidden")) {
                sb.append("• 检查「🔗 密钥」是否引用了该服务商可用的密钥（不同平台 key 不通用）\n");
            } else if (lower.contains("404")) {
                sb.append("• 检查端点 URL 是否正确，以及 SSE / Streamable HTTP 类型是否与端点匹配\n");
            } else if (lower.contains("timeout") || lower.contains("超时")) {
                sb.append("• 检查网络/代理；远端服务可能暂时不可用，稍后点「立即刷新」重试\n");
            } else if (lower.contains("sse")) {
                sb.append("• 如果是 SSE 端点，确认服务端能正常下发 endpoint 事件；必要时改用 Streamable HTTP\n");
            } else {
                sb.append("• 检查端点、传输类型与密钥；改完后点「立即刷新」重试\n");
            }
        } else {
            sb.append(status);
        }

        // 清单里认识这条服务时，把它登记的鉴权要求一起给出：
        // 管理页只有端点字符串，报错后用户最缺的是"这条服务到底要什么凭证"
        McpMarketEntry catalog = McpMarketCatalog.byId(this, cfg.id);
        if (catalog != null) {
            sb.append("\n\n市场清单（文档核对 ").append(catalog.verifiedAt.isEmpty()
                    ? "未标日期" : catalog.verifiedAt).append("）：")
              .append(McpMarketInstaller.capabilityLabel(catalog.capability))
              .append(" · ").append(McpMarketInstaller.authLabel(catalog.auth));
            if (!catalog.authHint.isEmpty()) {
                sb.append("\n").append(catalog.authHint);
            }
            if (!catalog.blockedReason.isEmpty()) {
                sb.append("\n清单判定：").append(catalog.blockedReason);
            }
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("同步状态 · " + cfg.name)
                .setMessage(sb.toString())
                .setNegativeButton("关闭", null);
        if (cfg.enabled) {
            b.setPositiveButton("立即刷新", (d, w) -> {
                McpManager.getInstance(this).refreshServer(cfg.id);
                refreshList();
            });
        }
        if (status != null && status.startsWith("ok:")) {
            b.setNeutralButton("查看工具", (d, w) -> showToolsDialog(cfg));
        }
        b.show();
    }

    private int badgeTextColor(String status, boolean enabled) {
        if (!enabled) return color(R.color.text_secondary);
        if (status == null) return color(R.color.text_secondary);
        if ("syncing".equals(status)) return color(R.color.primary);
        if (status.startsWith("ok:")) return color(R.color.success);
        if (status.startsWith("error:")) return color(R.color.error);
        return color(R.color.text_secondary);
    }

    private int badgeBgColor(String status, boolean enabled) {
        if (!enabled) return R.color.surface_variant;
        if (status == null) return R.color.surface_variant;
        if ("syncing".equals(status)) return R.color.primary_container;
        if (status.startsWith("ok:")) return R.color.success_container;
        if (status.startsWith("error:")) return R.color.error_container;
        return R.color.surface_variant;
    }

    // ==================== 对话框 ====================

    /** 填/改令牌。 */
    /** 服务 → 密钥入口：查看状态、引用匹配密钥、清除引用（密钥统一走密钥分支，不直接填明文）。 */
    private void showTokenDialog(final McpServerConfig cfg) {
        McpManager manager = McpManager.getInstance(this);
        String ref = cfg.tokenRef;
        String resolved = manager.resolveToken(cfg);
        String stateMsg;
        if (cfg.token != null && !cfg.token.isEmpty()) {
            stateMsg = cfg.token.startsWith("enc:")
                    ? "当前：直接令牌解密失败（Keystore 密钥丢失/设备迁移）。\n请改用「引用密钥」或清除后重填。"
                    : "当前：直接填写了令牌（旧方式）。\n建议改用「引用密钥」，统一在密钥分支管理，避免散落。";
        } else if (ref != null && !ref.isEmpty()) {
            String resolvedState;
            if (resolved.isEmpty()) {
                resolvedState = "（密钥库暂无值，请到密钥分支添加）";
            } else if (resolved.startsWith("enc:")) {
                resolvedState = "（密钥解密失败，请到密钥分支重填）";
            } else {
                resolvedState = "（已解析可用）";
            }
            stateMsg = "当前：引用密钥「" + ref + "」" + resolvedState;
        } else {
            stateMsg = "当前：未配置密钥。\n请从下方引用与服务商匹配的密钥（不同平台 key 不通用）。";
        }
        boolean hasDirect = cfg.token != null && !cfg.token.isEmpty();
        boolean hasRef = ref != null && !ref.isEmpty();
        String negativeLabel = hasRef ? "清除引用" : (hasDirect ? "清除直接令牌" : "取消");
        new AlertDialog.Builder(this)
                .setTitle("密钥 · " + cfg.name)
                .setMessage(stateMsg)
                .setPositiveButton("🔗 引用匹配密钥", (d, w) -> showKeyRefDialog(cfg))
                .setNeutralButton("📋 全部密钥", (d, w) -> showAllKeysDialog(cfg))
                .setNegativeButton(negativeLabel, (d, w) -> {
                    if (hasRef) {
                        McpManager.getInstance(this).updateTokenRef(cfg.id, "");
                        Toast.makeText(this, "已清除密钥引用", Toast.LENGTH_SHORT).show();
                        refreshList();
                    } else if (hasDirect) {
                        McpManager.getInstance(this).updateToken(cfg.id, "");
                        Toast.makeText(this, "已清除直接令牌", Toast.LENGTH_SHORT).show();
                        refreshList();
                    }
                })
                .show();
    }

    /** 列出可引用的密钥：先展示与当前 MCP 服务商匹配的（推荐），不匹配的走「查看全部」兜底。 */
    private void showKeyRefDialog(final McpServerConfig cfg) {
        McpManager manager = McpManager.getInstance(this);
        McpManager.McpProvider provider = manager.guessProvider(cfg);
        List<String[]> matches = manager.findMatchingKeyRefs(cfg);

        if (matches.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("引用已存密钥 · " + cfg.name)
                    .setMessage("未找到与「" + McpManager.providerLabel(provider) + "」匹配的密钥。\n\n"
                            + "该 MCP 服务需要「" + McpManager.providerLabel(provider) + "」的专用密钥"
                            + (provider == McpManager.McpProvider.UNKNOWN
                                ? "（未能自动判断服务商，请确认该服务需要什么密钥）" : "")
                            + "，不同平台的 key 互不通用，引用错误的密钥会导致鉴权失败（401/403）。\n\n"
                            + "请先在「API 配置 / 密钥管理」填写对应密钥，或点下方「查看全部密钥」确认是否有可用的。")
                    .setPositiveButton("知道了", null)
                    .setNeutralButton("查看全部密钥", (d, w) -> showAllKeysDialog(cfg))
                    .show();
            return;
        }

        String[] labels = new String[matches.size()];
        for (int i = 0; i < matches.size(); i++) {
            labels[i] = "✅ " + matches.get(i)[0];
        }
        new AlertDialog.Builder(this)
                .setTitle("引用密钥 · " + cfg.name + "\n（匹配" + McpManager.providerLabel(provider) + "）")
                .setMessage("仅列出与服务商匹配的密钥；其他密钥不适用，请勿引用。")
                .setItems(labels, (d, which) -> {
                    String ref = matches.get(which)[1];
                    McpManager.getInstance(this).updateTokenRef(cfg.id, ref);
                    Toast.makeText(this, "已引用密钥「" + ref + "」，正在重新同步…", Toast.LENGTH_SHORT).show();
                    refreshList();
                })
                .setNeutralButton("查看全部密钥", (d, w) -> showAllKeysDialog(cfg))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 全部密钥兜底（标注不保证适用）。 */
    private void showAllKeysDialog(final McpServerConfig cfg) {
        McpManager manager = McpManager.getInstance(this);
        List<String[]> all = manager.findAllKeyRefs();
        if (all.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("查看全部密钥")
                    .setMessage("密钥库里没有已存密钥。请先到「API 配置 / 密钥管理」填写。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        String[] labels = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            labels[i] = all.get(i)[0];
        }
        new AlertDialog.Builder(this)
                .setTitle("全部密钥（⚠ 仅当与服务匹配时才可引用）")
                .setMessage("请确认密钥平台与该 MCP 服务一致，否则鉴权会失败。")
                .setItems(labels, (d, which) -> {
                    String ref = all.get(which)[1];
                    McpManager.getInstance(this).updateTokenRef(cfg.id, ref);
                    Toast.makeText(this, "已引用密钥「" + ref + "」，正在重新同步…", Toast.LENGTH_SHORT).show();
                    refreshList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 传输枚举 → 用户可读名称（不要把 STREAMABLE_HTTP 这类内部值直接显示）。 */
    private static String transportLabel(McpServerConfig.Transport t) {
        if (t == McpServerConfig.Transport.SSE) return "SSE（经典）";
        if (t == McpServerConfig.Transport.STREAMABLE_HTTP) return "Streamable HTTP";
        return "未知";
    }

    /** 脱敏显示：保留首尾 4 位，中段 ****。 */
    private static String maskSecret(String key) {        if (key == null) return "";
        int n = key.length();
        if (n <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(n - 4);
    }

    /** 查看该 server 的工具目录。 */
    private void showToolsDialog(final McpServerConfig cfg) {
        List<String> lines = new ArrayList<>();
        int total = 0;
        try {
            // 直接读 McpManager 的目录：名称可能被规范化/截断，不能再从 registry 名字反推远端名
            List<McpClient.McpToolInfo> catalog =
                    McpManager.getInstance(this).getCatalogForServer(cfg.id);
            total = catalog.size();
            int shown = 0;
            for (McpClient.McpToolInfo t : catalog) {
                if (shown >= 80) break; // 大 server（GitHub 等）避免对话框撑爆
                String desc = t.description != null ? t.description : "";
                if (desc.length() > 240) desc = desc.substring(0, 240) + "…";
                lines.add("• " + t.name + (desc.isEmpty() ? "" : "\n    " + desc));
                shown++;
            }
            if (total > shown) {
                lines.add("… 共 " + total + " 个工具，仅显示前 " + shown + " 个");
            }
        } catch (Exception e) {
            lines.add("读取失败: " + e.getMessage());
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        if (lines.isEmpty()) sb.append("暂无工具（启用并同步后显示；模型命中时才按需注册）");
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("工具 · " + cfg.name)
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null);
        if (total > 0) {
            b.setNeutralButton("审批策略", (d, w) -> showPolicyList(cfg));
        }
        b.show();
    }

    /** 逐工具审批策略列表：远端工具名 → 当前策略，点一项修改。 */
    private void showPolicyList(final McpServerConfig cfg) {
        List<McpClient.McpToolInfo> catalog =
                McpManager.getInstance(this).getCatalogForServer(cfg.id);
        if (catalog.isEmpty()) {
            Toast.makeText(this, "暂无工具，先启用并同步", Toast.LENGTH_SHORT).show();
            return;
        }
        int n = Math.min(catalog.size(), 100);
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) {
            McpClient.McpToolInfo t = catalog.get(i);
            String policy = McpManager.getInstance(this).effectiveApproval(cfg.id, t);
            labels[i] = t.name + " · " + policyLabel(policy);
        }
        new AlertDialog.Builder(this)
                .setTitle("审批策略 · " + cfg.name)
                .setItems(labels, (d, which) ->
                        showPolicyDialog(cfg, catalog.get(which).name))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showPolicyDialog(final McpServerConfig cfg, final String toolName) {
        final String[] labels = {"跟随注解（默认）", "自动允许", "每次询问", "禁止"};
        String explicit = McpManager.getInstance(this).getToolApproval(cfg.id, toolName);
        int checked;
        if (McpConstants.APPROVAL_AUTO.equals(explicit)) checked = 1;
        else if (McpConstants.APPROVAL_ASK.equals(explicit)) checked = 2;
        else if (McpConstants.APPROVAL_DENY.equals(explicit)) checked = 3;
        else checked = 0;
        new AlertDialog.Builder(this)
                .setTitle(toolName)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    String policy;
                    if (which == 0) policy = "";
                    else if (which == 1) policy = McpConstants.APPROVAL_AUTO;
                    else if (which == 2) policy = McpConstants.APPROVAL_ASK;
                    else policy = McpConstants.APPROVAL_DENY;
                    McpManager.getInstance(this).setToolApproval(cfg.id, toolName, policy);
                    Toast.makeText(this, "已设为 " + labels[which], Toast.LENGTH_SHORT).show();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String policyLabel(String policy) {
        if (McpConstants.APPROVAL_AUTO.equals(policy)) return "自动允许";
        if (McpConstants.APPROVAL_DENY.equals(policy)) return "禁止";
        if (McpConstants.APPROVAL_ASK.equals(policy)) return "每次询问";
        return "跟随注解";
    }

    /** 自定义服务 id：中文名规范化后容易撞（阿里云/腾讯云都是 server），这里按现有配置去重。 */
    private String newUniqueId(String name) {
        String base = McpServerConfig.normalizeId(name);
        if (base == null || base.isEmpty()) base = "server";
        Set<String> used = new HashSet<>();
        for (McpServerConfig c : McpManager.getInstance(this).getServers()) used.add(c.id);
        String id = base;
        int n = 2;
        while (used.contains(id)) id = base + "-" + n++;
        return id;
    }

    /** 端点合法性：必须是 http(s) 且不能保留占位符（占位符直接添加只会得到 401/404）。 */
    private static boolean isValidEndpoint(String url) {
        return McpServerConfig.isUsableEndpoint(url);
    }

    /** 编辑服务：id 保持不变（工具本地名依赖它），只改名称/端点/传输；令牌与引用原样保留。 */
    private void showEditDialog(final McpServerConfig cfg) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), 0);

        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint("名称");
        nameInput.setText(cfg.name);
        form.addView(nameInput);

        EditText urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("端点 URL");
        urlInput.setText(cfg.url);
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        urlLp.setMargins(0, dp(6), 0, 0);
        form.addView(urlInput, urlLp);

        final int[] transportIndex = {cfg.transport == McpServerConfig.Transport.SSE ? 0 : 1};
        final String[] transports = {"SSE（经典）", "Streamable HTTP（推荐）"};
        TextView transportTv = new TextView(this);
        transportTv.setText("传输: " + transports[transportIndex[0]]);
        transportTv.setTextSize(14);
        transportTv.setTextColor(color(R.color.text_primary));
        transportTv.setPadding(0, dp(10), 0, dp(2));
        form.addView(transportTv);
        transportTv.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("传输类型")
                .setSingleChoiceItems(transports, transportIndex[0], (d, which) -> {
                    transportIndex[0] = which;
                    transportTv.setText("传输: " + transports[which]);
                    d.dismiss();
                })
                .setPositiveButton("确定", null)
                .show());

        new AlertDialog.Builder(this)
                .setTitle("编辑 MCP 服务")
                .setView(form)
                .setPositiveButton("保存", (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    String url = urlInput.getText().toString().trim();
                    if (name.isEmpty() || url.isEmpty()) {
                        Toast.makeText(this, "名称与端点 URL 必填", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!isValidEndpoint(url)) {
                        Toast.makeText(this, "端点需为 http(s) 且不能保留 {…}/你的Key 等占位符",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    McpServerConfig updated = new McpServerConfig(
                            cfg.id, name, url,
                            transportIndex[0] == 0 ? McpServerConfig.Transport.SSE
                                    : McpServerConfig.Transport.STREAMABLE_HTTP,
                            cfg.token, cfg.tokenRef, cfg.enabled, cfg.builtin);
                    McpManager.getInstance(this).upsertServer(updated);
                    Toast.makeText(this, "已保存，正在重新同步 " + name, Toast.LENGTH_SHORT).show();
                    refreshList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 添加自定义 server。 */
    private void showAddDialog() {        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), 0);

        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint("名称（如 我的服务）");
        form.addView(nameInput);

        EditText urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("端点 URL（SSE: .../sse  或  HTTP: https://.../mcp）");
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        urlLp.setMargins(0, dp(6), 0, 0);
        form.addView(urlInput, urlLp);

        TextView keyHint = new TextView(this);
        keyHint.setText("密钥不在添加时填写：添加后到服务卡片「🔗 密钥」按服务商引用已存密钥。");
        keyHint.setTextSize(12);
        keyHint.setTextColor(color(R.color.text_tertiary));
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, dp(6), 0, 0);
        form.addView(keyHint, hintLp);

        final int[] transportIndex = {1}; // 默认 Streamable HTTP：新服务的主流形态，SSE 仅兼容旧端点
        final String[] transports = {"SSE（经典）", "Streamable HTTP（推荐）"};
        TextView transportTv = new TextView(this);
        transportTv.setText("传输: " + transports[transportIndex[0]]);
        transportTv.setTextSize(14);
        transportTv.setTextColor(color(R.color.text_primary));
        transportTv.setPadding(0, dp(10), 0, dp(2));
        form.addView(transportTv);
        transportTv.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("传输类型")
                .setSingleChoiceItems(transports, transportIndex[0], (d, which) -> {
                    transportIndex[0] = which;
                    transportTv.setText("传输: " + transports[which]);
                    d.dismiss();
                })
                .setPositiveButton("确定", null)
                .show());

        new AlertDialog.Builder(this)
                .setTitle("添加 MCP 服务")
                .setView(form)
                .setPositiveButton("添加并启用", (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    String url = urlInput.getText().toString().trim();
                    if (name.isEmpty() || url.isEmpty()) {
                        Toast.makeText(this, "名称与端点 URL 必填", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!isValidEndpoint(url)) {
                        Toast.makeText(this, "端点需为 http(s) 且不能保留 {…}/你的Key 等占位符",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    String id = newUniqueId(name);
                    McpServerConfig cfg = new McpServerConfig(id, name, url,
                            transportIndex[0] == 0 ? McpServerConfig.Transport.SSE
                                    : McpServerConfig.Transport.STREAMABLE_HTTP,
                            "", true, false);
                    McpManager.getInstance(this).upsertServer(cfg);
                    Toast.makeText(this, "已添加并启用 " + name
                            + "，密钥可到卡片「🔗 密钥」引用", Toast.LENGTH_LONG).show();
                    refreshList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 删除确认。 */
    private void confirmRemove(final McpServerConfig cfg) {
        new AlertDialog.Builder(this)
                .setTitle("删除 " + cfg.name + "？")
                .setMessage("将同时注销该服务注册的全部工具。")
                .setPositiveButton("删除", (d, w) -> {
                    McpManager.getInstance(this).removeServer(cfg.id);
                    Toast.makeText(this, "已删除 " + cfg.name, Toast.LENGTH_SHORT).show();
                    refreshList();
                })
                .setNegativeButton("取消", null)
                .show();
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
