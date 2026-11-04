package com.oilquiz.app.ui.activity;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.graphics.drawable.GradientDrawable;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.ai.model.APIConfig;
import com.oilquiz.app.ai.model.ApiModel;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.agent.online.mcp.McpManager;
import com.oilquiz.app.ai.agent.online.mcp.McpServerConfig;
import com.oilquiz.app.ai.model.UsageInfo;
import com.oilquiz.app.ai.service.ModelListFetcher;
import com.oilquiz.app.ai.service.UsageTracker;
import com.oilquiz.app.ai.util.APIConfigParser;
import com.oilquiz.app.ai.util.APIKeyManager;
import com.oilquiz.app.ai.util.ApiKeyCipher;
import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.oilquiz.app.theme.ThemeColors;
public class ApiConfigActivity extends BaseActivity {

    private static final String TAG = "ApiConfigActivity";
    private static final int REQUEST_CODE_IMPORT_FILE = 1001;
    private static final int REQUEST_CODE_EXPORT_FILE = 1002;
    private static final int REQUEST_CODE_PERMISSION = 1003;
    private static final int REQUEST_CODE_SELECT_FOLDER = 1004;
    private static final int REQUEST_CODE_IMPORT_CONFIG_FILE = 1005;

    private EditText etSearch;
    private MaterialButton btnAdd;
    private LinearLayout llApiList;
    private LinearLayout llEmpty;
    private LinearLayout llHefengSection;
    private LinearLayout llMcpSection;
    private MaterialButton btnTestAll;
    private android.view.View btnMore;
    private com.google.android.material.tabs.TabLayout tabLayout;

    private EditText pendingPathEditText;

    private APIKeyManager apiKeyManager;
    private OnlineModelManager onlineModelManager;
    private ModelListFetcher modelListFetcher;
    private UsageTracker usageTracker;
    private Handler mainHandler;
    private List<APIConfig> allConfigs;
    private List<APIConfig> filteredConfigs;

    // METASO-SEARCH(2026-10-11)：新增"秘塔搜索"选项 —— 它才是 network_search 工具的实际数据源
    // （NetworkSearchTool 四个调用点都打 metaso.cn）。此前下拉里只有 "Bing Search"，
    // 而 NetworkSearchTool 读的是 APIKeyManager 的扁平键、UI 写的是 APIConfig 结构化配置，
    // 两套存储互不相通 → 用户即便填了 key 也读不到，永远走内置共享 key。
    // 现在工具侧已按 METASO_SEARCH / BING_SEARCH 两种 serviceType 解析（见 resolveApiKey）。
    // ⚠ serviceTypes 与 serviceTypeValues 必须**逐位对应**且长度相同：
    // 渲染用前者、取值用 serviceTypeValues[spinner.getSelectedItemPosition()]（见保存处）。
    // 任何一处增删都要同步改另一处，否则会存错 serviceType。
    private final String[] serviceTypes = {
        "OpenAI", "Anthropic", "Google",
        "和风天气", "秘塔搜索", "Bing Search", "Google Maps", "自定义"
    };

    private final String[] serviceTypeValues = {
        APIConfig.ServiceType.OPENAI, APIConfig.ServiceType.ANTHROPIC,
        APIConfig.ServiceType.GOOGLE,
        APIConfig.ServiceType.HEFENG_WEATHER, APIConfig.ServiceType.METASO_SEARCH,
        APIConfig.ServiceType.BING_SEARCH,
        APIConfig.ServiceType.GOOGLE_MAPS, APIConfig.ServiceType.CUSTOM
    };

    private final String[] categories = {
        "AI服务", "天气服务", "搜索服务", "地图服务", "其他"
    };
    
    private final String[] categoryValues = {
        APIConfig.Category.AI, APIConfig.Category.WEATHER,
        APIConfig.Category.SEARCH, APIConfig.Category.MAPS,
        APIConfig.Category.OTHER
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        apiKeyManager = APIKeyManager.getInstance(this);
        modelListFetcher = ModelListFetcher.getInstance(this);
        usageTracker = UsageTracker.getInstance(this);
        mainHandler = new Handler(Looper.getMainLooper());
        allConfigs = new ArrayList<>();
        filteredConfigs = new ArrayList<>();
        super.onCreate(savedInstanceState);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_api_config;
    }

    @Override
    protected void initView() {
        setupToolbar("API配置管理");

        etSearch = findViewById(R.id.et_search);
        btnAdd = findViewById(R.id.btn_add);
        llApiList = findViewById(R.id.ll_api_list);
        llEmpty = findViewById(R.id.ll_empty);
        llHefengSection = findViewById(R.id.ll_hefeng_section);
        llMcpSection = findViewById(R.id.ll_mcp_section);
        btnTestAll = findViewById(R.id.btn_test_all);
        btnMore = findViewById(R.id.btn_more);
        tabLayout = findViewById(R.id.tab_layout);
    }

    @Override
    protected void initData() {
        loadApiConfigs();
        renderHefengSection();
        renderKeystoreSection();
        renderMcpSection();
    }

    @Override
    protected void initListener() {
        btnAdd.setOnClickListener(v -> showEditDialog(null));

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterConfigs(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnTestAll.setOnClickListener(v -> testAllConfigs());
        btnMore.setOnClickListener(v -> showPopupMenu());
        tabLayout.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                switchTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
        });
    }

    /** 三段控制器：0=API配置列表, 1=特殊凭证(和风JWT+Keystore), 2=MCP服务 */
    private void switchTab(int position) {
        boolean isApi = position == 0;
        // 搜索行(搜索框所在容器)只在 API 配置页签显示
        android.view.View searchRow = etSearch.getParent() instanceof android.view.View
                ? (android.view.View) etSearch.getParent() : null;
        if (searchRow != null) {
            searchRow.setVisibility(isApi ? View.VISIBLE : View.GONE);
        }
        if (llApiList != null) llApiList.setVisibility(isApi ? View.VISIBLE : View.GONE);
        if (llHefengSection != null) {
            llHefengSection.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
        }
        if (llMcpSection != null) {
            llMcpSection.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
        }
        if (llEmpty != null) {
            // 空态只在 API 页签且无配置时显示
            llEmpty.setVisibility(isApi && (filteredConfigs == null || filteredConfigs.isEmpty())
                    ? View.VISIBLE : View.GONE);
        }
    }

    /** 右上角溢出菜单：低频操作(导入/导出/存档)收进这里，底部只留批量测试 */
    private void showPopupMenu() {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, btnMore);
        pm.getMenu().add(0, 1, 0, "导入 JSON");
        pm.getMenu().add(0, 2, 0, "导出 JSON");
        pm.getMenu().add(0, 3, 0, "导入配置(存档)");
        pm.getMenu().add(0, 4, 0, "扫描存档目录");
        pm.getMenu().add(0, 5, 0, "设置存档路径");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: importFromJson(); return true;
                case 2: exportToJson(); return true;
                case 3: importFromConfigFile(); return true;
                case 4: scanArchiveDirectory(); return true;
                case 5: showArchivePathDialog(); return true;
                default: return false;
            }
        });
        pm.show();
    }

    private void loadApiConfigs() {
        if (apiKeyManager == null) {
            apiKeyManager = APIKeyManager.getInstance(this);
        }
        if (onlineModelManager == null) {
            onlineModelManager = OnlineModelManager.getInstance(this);
        }
        // 先按注册表回灌再读列表：同一条端点在两个存储里的名字/模型必须一致，
        // 否则本页显示"自定义 · DeepSeek-V4.1-Flash"而模型中心显示"深度求索 / deepseek-flash"
        onlineModelManager.reconcileApiConfigsFromRegistry();
        allConfigs = apiKeyManager.getAllAPIConfigs();
        if (allConfigs == null) {
            allConfigs = new ArrayList<>();
        }
        filteredConfigs.clear();
        filteredConfigs.addAll(allConfigs);
        refreshApiList();
    }

    private void filterConfigs(String query) {
        ensureListsInitialized();
        filteredConfigs.clear();
        if (query == null || query.isEmpty()) {
            filteredConfigs.addAll(allConfigs);
        } else {
            String lowerQuery = query.toLowerCase();
            for (APIConfig config : allConfigs) {
                if (config.getName() != null && config.getName().toLowerCase().contains(lowerQuery) ||
                    config.getServiceType() != null && config.getServiceType().toLowerCase().contains(lowerQuery) ||
                    config.getDescription() != null && config.getDescription().toLowerCase().contains(lowerQuery)) {
                    filteredConfigs.add(config);
                }
            }
        }
        refreshApiList();
    }

    private void refreshApiList() {
        ensureListsInitialized();
        llApiList.removeAllViews();

        if (filteredConfigs.isEmpty()) {
            llEmpty.setVisibility(View.VISIBLE);
            return;
        }

        llEmpty.setVisibility(View.GONE);

        for (APIConfig config : filteredConfigs) {
            View itemView = LayoutInflater.from(this).inflate(R.layout.item_api_config, llApiList, false);
            bindApiConfigItem(itemView, config);
            llApiList.addView(itemView);
        }
    }

    private void ensureListsInitialized() {
        if (allConfigs == null) {
            allConfigs = new ArrayList<>();
        }
        if (filteredConfigs == null) {
            filteredConfigs = new ArrayList<>();
        }
    }

    // ==================== MCP 服务（只做状态与跳转，不做第二套配置编辑） ====================

    /**
     * 渲染 MCP 区块：只报"当前装了几个、状态如何"，两个动作分别跳市场页与管理页。
     *
     * <p><b>为什么把这里的添加/删除删掉</b>：同一个服务曾经有两处写入
     * （本区块的「＋ 添加/🗑 删除」与管理页的完整编辑），两处规则不同——这里不做占位符校验、
     * 不引密钥就直接 enabled=true，于是用户可以在 API 配置页一屏之内造出"看着已添加、
     * 其实必然连不上"的条目，而管理页显示的错误又被认为是管理页的问题。
     * 配置的权威只保留 {@link McpManagerActivity} 一处；发现与安装走
     * {@link McpMarketActivity}（装之前会真跑一次 initialize + tools/list）。</p>
     */
    private void renderMcpSection() {
        if (llMcpSection == null) return;
        llMcpSection.removeAllViews();

        McpManager mcp = McpManager.getInstance(this);
        List<McpServerConfig> servers = mcp.getServers();
        Map<String, String> statuses = mcp.getStatus();
        int enabled = 0;
        int ok = 0;
        int failed = 0;
        int syncing = 0;
        for (McpServerConfig s : servers) {
            if (s.enabled) enabled++;
            String st = statuses.get(s.id);
            if (st == null) continue;
            if (st.startsWith("ok:")) ok++;
            else if (st.startsWith("error:")) failed++;
            else if ("syncing".equals(st)) syncing++;
        }

        TextView title = new TextView(this);
        title.setText("MCP 服务");
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        llMcpSection.addView(title);

        TextView sub = new TextView(this);
        sub.setText("已配置 " + servers.size() + " 个，启用 " + enabled
                + " 个 · 连接正常 " + ok + " · 失败 " + failed
                + (syncing > 0 ? " · 同步中 " + syncing : "")
                + "\n端点、密钥引用、启停、删除都在管理页改；新服务从市场装，装之前会先测一次连接。");
        sub.setTextSize(12);
        sub.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary));
        sub.setPadding(0, dp4(4), 0, dp4(8));
        llMcpSection.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(mcpPillBtn("🛒 市场 · 发现并安装", v ->
                startActivity(new Intent(this, McpMarketActivity.class))));
        row.addView(mcpPillBtn("⚙ 管理 · 端点/密钥/启停", v ->
                startActivity(new Intent(this, McpManagerActivity.class))));
        llMcpSection.addView(row);

        if (!servers.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            for (McpServerConfig s : servers) {
                String st = statuses.get(s.id);
                detail.append(s.enabled ? "●" : "○").append(" ")
                      .append(s.name).append("  ")
                      .append(st == null || st.isEmpty() ? "未同步" : st).append("\n");
            }
            TextView list = new TextView(this);
            list.setText(detail.toString().trim());
            list.setTextSize(11);
            list.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            list.setPadding(0, dp4(8), 0, 0);
            llMcpSection.addView(list);
        }
    }

    private TextView mcpPillBtn(String text, View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(this, R.color.primary_container));
        bg.setCornerRadius(dp4(16));
        btn.setBackground(bg);
        btn.setPadding(dp4(14), dp4(6), dp4(14), dp4(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp4(8), 0);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(listener);
        return btn;
    }

    // ==================== 系统 Keystore 自检 ====================

    /** Keystore 自检卡片：证明密钥真在系统 AndroidKeyStore，且系统"密钥与凭据"不显示属正常。 */
    private void renderKeystoreSection() {
        if (llHefengSection == null) return;
        // 插到和风卡片之前：首次渲染时移除旧的 keystore 卡片（只动第一个子项）
        boolean hasOld = llHefengSection.getChildCount() > 0
                && llHefengSection.getChildAt(0).getTag() != null
                && "keystore_card".equals(llHefengSection.getChildAt(0).getTag());
        if (hasOld) {
            llHefengSection.removeViewAt(0);
        }

        APIKeyManager mgr = APIKeyManager.getInstance(this);
        boolean ksOk = ApiKeyCipher.keystoreAvailable();
        int entryCount = 0;
        try {
            List<String> keys = mgr.getAllServiceKeys();
            entryCount = keys == null ? 0 : keys.size();
        } catch (Throwable th) {
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp4(12), dp4(10), dp4(12), dp4(10));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, dp4(4), 0, dp4(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(this, R.color.surface_variant));
        bg.setCornerRadius(dp4(10));
        card.setBackground(bg);
        card.setLayoutParams(cardLp);
        card.setTag("keystore_card");

        TextView t1 = new TextView(this);
        t1.setText("🔒 系统 Keystore 自检"
                + (ksOk ? " · ✅ AES-256-GCM 可用（主密钥不出芯片）" : " · ⚠️ 不可用（查日志）"));
        t1.setTextSize(13);
        t1.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t1.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        card.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText("受保护密钥条目：" + entryCount + " 条（enc: 加密落盘）");
        t2.setTextSize(12);
        t2.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        t2.setPadding(0, dp4(4), 0, dp4(4));
        card.addView(t2);

        TextView t3 = new TextView(this);
        t3.setText("说明：手机系统的「密钥与凭据」只展示证书/VPN 凭据，"
                + "不显示 App 级 Keystore 密钥——看不到属正常，并非没接入。");
        t3.setTextSize(11);
        t3.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary));
        card.addView(t3);

        llHefengSection.addView(card, 0);
    }

    // ==================== 和风天气 JWT 高级凭证（免内置共享） ====================

    /** 和风天气 JWT 凭证入口：配置自己的 Ed25519 私钥 / projectId / kid / host，工具即不再走内置共享凭证。 */
    private void renderHefengSection() {
        if (llHefengSection == null) return;
        llHefengSection.removeAllViews();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp4(12), dp4(10), dp4(12), dp4(10));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, dp4(4), 0, dp4(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(this, R.color.surface_variant));
        bg.setCornerRadius(dp4(10));
        card.setBackground(bg);
        card.setLayoutParams(cardLp);

        TextView info = new TextView(this);
        info.setText("🌤 和风天气 JWT 凭证\n配置你自己的 Ed25519 私钥 / projectId / kid，天气工具即用你的凭证，不再走内置共享");
        info.setTextSize(12);
        info.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        card.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView setBtn = new TextView(this);
        setBtn.setText(APIKeyManager.getInstance(this).isQWeatherJwtConfigured() ? "已配置 ✓" : "配置");
        setBtn.setTextSize(12);
        setBtn.setGravity(Gravity.CENTER);
        setBtn.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        GradientDrawable bg2 = new GradientDrawable();
        bg2.setColor(ContextCompat.getColor(this, R.color.primary_container));
        bg2.setCornerRadius(dp4(16));
        setBtn.setBackground(bg2);
        setBtn.setPadding(dp4(14), dp4(6), dp4(14), dp4(6));
        setBtn.setOnClickListener(v -> showHefengJwtDialog(setBtn));
        card.addView(setBtn);
        llHefengSection.addView(card);
    }

    private void showHefengJwtDialog(final TextView refreshBtn) {
        APIKeyManager mgr = APIKeyManager.getInstance(this);

        EditText keyInput = new EditText(this);
        keyInput.setSingleLine(false);
        keyInput.setMinLines(3);
        keyInput.setText(mgr.getQWeatherPrivateKey() == null ? "" : mgr.getQWeatherPrivateKey());
        keyInput.setHint("Ed25519 私钥（PEM，含 BEGIN/END 行）");
        keyInput.setTextColor(ContextCompat.getColor(this, R.color.text_primary));

        EditText pidInput = new EditText(this);
        pidInput.setSingleLine(true);
        pidInput.setText(mgr.getQWeatherProjectId() == null ? "" : mgr.getQWeatherProjectId());
        pidInput.setHint("Project ID");
        pidInput.setTextColor(ContextCompat.getColor(this, R.color.text_primary));

        EditText kidInput = new EditText(this);
        kidInput.setSingleLine(true);
        kidInput.setText(mgr.getQWeatherKid() == null ? "" : mgr.getQWeatherKid());
        kidInput.setHint("Key ID（kid）");
        kidInput.setTextColor(ContextCompat.getColor(this, R.color.text_primary));

        EditText hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setText(mgr.getQWeatherApiHost() == null ? "" : mgr.getQWeatherApiHost());
        hostInput.setHint("API Host（留空用默认）");
        hostInput.setTextColor(ContextCompat.getColor(this, R.color.text_primary));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(keyInput);
        LinearLayout.LayoutParams pad = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pad.setMargins(0, dp4(8), 0, 0);
        content.addView(pidInput, pad);
        content.addView(kidInput, pad);
        content.addView(hostInput, pad);

        new AlertDialog.Builder(this)
                .setTitle("和风天气 JWT 凭证")
                .setMessage("填自己的凭证后天气工具不再用内置共享 key（未填才兜底）。"
                        + "获取：和风天气控制台 → 项目凭证（Ed25519）。")
                .setView(content)
                .setPositiveButton("保存", (d, w) -> {
                    String pem = keyInput.getText().toString().trim();
                    String pid = pidInput.getText().toString().trim();
                    String kid = kidInput.getText().toString().trim();
                    if (pem.isEmpty() || pid.isEmpty() || kid.isEmpty()) {
                        Toast.makeText(this, "私钥 / Project ID / kid 不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    APIKeyManager.getInstance(this)
                            .saveQWeatherJwtCredentials(pem, pid, kid,
                                    hostInput.getText().toString().trim());
                    Toast.makeText(this, "和风 JWT 凭证已保存（天气工具将优先使用）", Toast.LENGTH_SHORT).show();
                    refreshBtn.setText("已配置 ✓");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp4(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void bindApiConfigItem(View itemView, APIConfig config) {
        TextView tvName = itemView.findViewById(R.id.tv_api_name);
        TextView tvServiceType = itemView.findViewById(R.id.tv_service_type);
        TextView tvApiKey = itemView.findViewById(R.id.tv_api_key);
        TextView tvApiHost = itemView.findViewById(R.id.tv_api_host);
        TextView tvModelName = itemView.findViewById(R.id.tv_model_name);
        TextView tvStatus = itemView.findViewById(R.id.tv_status);
        TextView tvUsagePercent = itemView.findViewById(R.id.tv_usage_percent);
        TextView tvUsageDetail = itemView.findViewById(R.id.tv_usage_detail);
        View statusIndicator = itemView.findViewById(R.id.status_indicator);
        LinearLayout llHost = itemView.findViewById(R.id.ll_api_host);
        LinearLayout llModel = itemView.findViewById(R.id.ll_model_name);
        LinearLayout llUsage = itemView.findViewById(R.id.ll_usage);
        ProgressBar pbUsage = itemView.findViewById(R.id.pb_usage);

        MaterialButton btnTest = itemView.findViewById(R.id.btn_test);
        MaterialButton btnEdit = itemView.findViewById(R.id.btn_edit);
        MaterialButton btnCopy = itemView.findViewById(R.id.btn_copy);
        MaterialButton btnDelete = itemView.findViewById(R.id.btn_delete);
        android.widget.Switch switchEnable =
                itemView.findViewById(R.id.switch_enable);

        // 标题优先用用户起的配置名 —— 它与模型中心列表是同一个字段，两页才会显示同一个名字；
        // 没有配置名时才退回「服务类型 · 模型名」。
        // 原先的判定方向反了：只要 modelName 非空就永远走"服务类型 · 模型名"，
        // 于是同一条端点在模型中心叫"深度求索"、在这里叫"OpenAI · deepseek-flash"。
        // 模型名本页已有独立的"已选模型"一行，标题里再放一遍本就是重复信息。
        String serviceType = getServiceTypeDisplay(config.getServiceType());
        String modelName = config.getModelName();
        String configName = config.getName();
        String displayName;

        if (configName != null && !configName.trim().isEmpty()) {
            displayName = configName;
        } else if (modelName != null && !modelName.isEmpty()) {
            displayName = serviceType + " · " + modelName;
        } else {
            displayName = serviceType;
        }
        
        tvName.setText(displayName);
        tvServiceType.setText(serviceType);
        tvApiKey.setText(config.getMaskedApiKey());

        if (config.getApiHost() != null && !config.getApiHost().isEmpty()) {
            tvApiHost.setText(config.getApiHost());
            llHost.setVisibility(View.VISIBLE);
        } else {
            llHost.setVisibility(View.GONE);
        }

        if (config.getModelName() != null && !config.getModelName().isEmpty()) {
            tvModelName.setText(config.getModelName());
            llModel.setVisibility(View.VISIBLE);
        } else {
            llModel.setVisibility(View.GONE);
        }

        tvStatus.setText(getStatusDisplay(config.getStatus()));

        updateStatusIndicator(statusIndicator, config.getStatus());

        // 服务商配额：列表行只读缓存（绝不在 bind 里发网络请求），
        // 真正的拉取发生在编辑对话框打开该条时（见 updateUsageDisplay）。
        // 原先这里还显示「使用次数 N」「最后使用时间」—— useCount/lastUsedAt 全项目无写入方，
        // 恒为 0 / 恒空，属于看着像数据其实是空壳的展示，已移除。
        applyUsageTo(llUsage, pbUsage, tvUsagePercent, tvUsageDetail,
                usageTracker != null ? usageTracker.getUsageInfo(config.getId()) : null);

        btnTest.setOnClickListener(v -> testSingleConfig(config));
        btnEdit.setOnClickListener(v -> {
            Log.d("ApiConfigActivity", "编辑按钮点击，config=" + (config != null ? config.getName() : "null"));
            if (config != null && config.getId() != null) {
                showEditDialog(config);
            } else {
                Log.e("ApiConfigActivity", "无法编辑：配置对象为空或ID为空");
                Toast.makeText(this, getString(R.string.h_d7d74150), Toast.LENGTH_SHORT).show();
            }
        });
        btnDelete.setOnClickListener(v -> confirmDelete(config));

        // 启用/停用开关：先置空监听再 setChecked，避免绑定回放触发监听递归
        switchEnable.setOnCheckedChangeListener(null);
        switchEnable.setChecked(config.isActive());
        switchEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (apiKeyManager != null && config.getId() != null) {
                apiKeyManager.toggleAPIConfigActive(config.getId());
                Log.d(TAG, "toggle active: " + config.getName() + " -> " + config.isActive());
            }
        });

        // 复制明文密钥到剪贴板
        btnCopy.setOnClickListener(v -> {
            String raw = config.getApiKey();
            if (raw == null || raw.isEmpty()) {
                Toast.makeText(this, "该配置没有密钥", Toast.LENGTH_SHORT).show();
                return;
            }
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("api_key", raw));
            Toast.makeText(this, "密钥已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateStatusIndicator(View indicator, String status) {
        if (indicator == null) return;
        
        int bgRes;
        switch (status != null ? status : APIConfig.Status.UNKNOWN) {
            case APIConfig.Status.VALID:
                bgRes = android.R.color.holo_green_dark;
                break;
            case APIConfig.Status.INVALID:
            case APIConfig.Status.EXPIRED:
                bgRes = android.R.color.holo_red_dark;
                break;
            case APIConfig.Status.RATE_LIMITED:
                bgRes = android.R.color.holo_orange_dark;
                break;
            default:
                bgRes = android.R.color.darker_gray;
                break;
        }
        indicator.setBackgroundColor(ContextCompat.getColor(this, bgRes));
    }

    private String getServiceTypeDisplay(String type) {
        for (int i = 0; i < serviceTypeValues.length; i++) {
            if (serviceTypeValues[i].equals(type)) {
                return serviceTypes[i];
            }
        }
        return "自定义";
    }

    /** 是否为"搜索服务"类目下的服务类型（用于自动归入 Category.SEARCH，见 onItemSelected 的 CATEGORY-AUTOSET 注释） */
    private boolean isSearchServiceType(String serviceType) {
        if (serviceType == null) return false;
        return APIConfig.ServiceType.METASO_SEARCH.equals(serviceType)
                || APIConfig.ServiceType.BING_SEARCH.equals(serviceType);
    }

    /** categoryValues 中某分类的下标；找不到返回 -1（下标与 categories 逐位对应） */
    private int indexOfCategory(String category) {
        for (int i = 0; i < categoryValues.length; i++) {
            if (categoryValues[i].equals(category)) return i;
        }
        return -1;
    }

    private String getStatusDisplay(String status) {
        switch (status != null ? status : APIConfig.Status.UNKNOWN) {
            case APIConfig.Status.VALID:
                return "有效";
            case APIConfig.Status.INVALID:
                return "无效";
            case APIConfig.Status.EXPIRED:
                return "已过期";
            case APIConfig.Status.RATE_LIMITED:
                return "频率受限";
            default:
                return "未知";
        }
    }

    private void showEditDialog(@Nullable APIConfig existingConfig) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(existingConfig == null ? getString(R.string.h_bff4d8eb) : getString(R.string.h_df1aee77));

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_api_config_edit, null);
        EditText etName = dialogView.findViewById(R.id.et_name);
        Spinner spinnerService = dialogView.findViewById(R.id.spinner_service_type);
        EditText etApiKey = dialogView.findViewById(R.id.et_api_key);
        EditText etApiHost = dialogView.findViewById(R.id.et_api_host);
        EditText etModelName = dialogView.findViewById(R.id.et_model_name);
        Spinner spinnerCategory = dialogView.findViewById(R.id.spinner_category);
        EditText etTimeout = dialogView.findViewById(R.id.et_timeout);
        EditText etDescription = dialogView.findViewById(R.id.et_description);

        // 新增的UI元素
        MaterialButton btnTestConnection = dialogView.findViewById(R.id.btn_test_connection);
        MaterialButton btnFetchModels = dialogView.findViewById(R.id.btn_fetch_models);
        LinearLayout llConnectionStatus = dialogView.findViewById(R.id.ll_connection_status);
        View statusIndicator = dialogView.findViewById(R.id.status_indicator);
        TextView tvConnectionStatus = dialogView.findViewById(R.id.tv_connection_status);
        ProgressBar pbTesting = dialogView.findViewById(R.id.pb_testing);
        LinearLayout llModelSection = dialogView.findViewById(R.id.ll_model_section);
        Spinner spinnerModel = dialogView.findViewById(R.id.spinner_model);
        TextView tvModelList = dialogView.findViewById(R.id.tv_model_list);
        LinearLayout llUsageSection = dialogView.findViewById(R.id.ll_usage_section);
        ProgressBar pbUsage = dialogView.findViewById(R.id.pb_usage);
        TextView tvUsagePercent = dialogView.findViewById(R.id.tv_usage_percent);
        TextView tvUsageDetail = dialogView.findViewById(R.id.tv_usage_detail);

        // 当前正在编辑的配置（用于测试和获取模型）
        APIConfig[] currentConfig = {existingConfig};

        ArrayAdapter<String> serviceAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, serviceTypes);
        spinnerService.setAdapter(serviceAdapter);

        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, categories);
        spinnerCategory.setAdapter(categoryAdapter);

        if (existingConfig != null) {
            if (etName != null) {
                etName.setText(existingConfig.getName());
            }
            if (etApiKey != null) {
                etApiKey.setText(existingConfig.getApiKey());
            }
            if (etApiHost != null) {
                etApiHost.setText(existingConfig.getApiHost());
            }
            if (etModelName != null) {
                etModelName.setText(existingConfig.getModelName());
            }
            if (etTimeout != null) {
                etTimeout.setText(String.valueOf(existingConfig.getTimeout()));
            }
            if (etDescription != null) {
                etDescription.setText(existingConfig.getDescription());
            }

            if (spinnerService != null) {
                for (int i = 0; i < serviceTypeValues.length; i++) {
                    if (serviceTypeValues[i].equals(existingConfig.getServiceType())) {
                        spinnerService.setSelection(i);
                        break;
                    }
                }
            }

            if (spinnerCategory != null) {
                for (int i = 0; i < categoryValues.length; i++) {
                    if (categoryValues[i].equals(existingConfig.getCategory())) {
                        spinnerCategory.setSelection(i);
                        break;
                    }
                }
            }

            // 使用量信息：可见性交给 updateUsageDisplay/applyUsageTo 判定
            // （此前这里无条件 VISIBLE，而配额数据又只在拉到时才有，会先露出一块空白进度条）
            updateUsageDisplay(llUsageSection, pbUsage, tvUsagePercent, tvUsageDetail, existingConfig.getId());

            // 与在线模型注册表重叠的字段锁住：同一条记录两处都能改就会互相覆盖，
            // 而推理链读的是注册表那一份
            if (com.oilquiz.app.ai.model.OnlineModelManager.isAIServiceConfig(existingConfig)) {
                TextView hint = dialogView.findViewById(R.id.text_online_model_hint);
                if (hint != null) {
                    hint.setText("这是一个 AI 服务条目：名称 / 地址 / 密钥 / 模型名由「模型中心」的模型配置管理，"
                            + "此处只保留超时、描述、分类与服务类型。");
                    hint.setVisibility(View.VISIBLE);
                }
                lockOnlineManagedField(etName);
                lockOnlineManagedField(etApiHost);
                lockOnlineManagedField(etApiKey);
                lockOnlineManagedField(etModelName);
            }
        }

        // 测试连通性按钮
        if (btnTestConnection != null) {
            btnTestConnection.setOnClickListener(v -> {
                if (etApiKey == null || etApiHost == null || spinnerService == null) {
                    return;
                }
                
                String apiKey = etApiKey.getText().toString().trim();
                String apiHost = etApiHost.getText().toString().trim();
                String serviceType = serviceTypeValues[spinnerService.getSelectedItemPosition()];

                if (apiKey.isEmpty()) {
                    Toast.makeText(this, getString(R.string.h_bbbab591), Toast.LENGTH_SHORT).show();
                    return;
                }

                if (llConnectionStatus != null) {
                    llConnectionStatus.setVisibility(View.VISIBLE);
                }
                if (tvConnectionStatus != null) {
                    tvConnectionStatus.setText(getString(R.string.h_8d701f1e));
                }
                if (pbTesting != null) {
                    pbTesting.setVisibility(View.VISIBLE);
                }
                btnTestConnection.setEnabled(false);

                // 创建临时配置用于测试
                APIConfig testConfig = existingConfig != null ? existingConfig : new APIConfig();
                testConfig.setApiKey(apiKey);
                testConfig.setApiHost(apiHost);
                testConfig.setServiceType(serviceType);

                apiKeyManager.testAPIConnection(testConfig).thenAccept(result -> runOnUiThread(() -> {
                    if (pbTesting != null) {
                        pbTesting.setVisibility(View.GONE);
                    }
                    btnTestConnection.setEnabled(true);

                    if (result.success) {
                        if (tvConnectionStatus != null) {
                            tvConnectionStatus.setText(getString(R.string.h_fa5bc5b3) + result.latency + "ms");
                        }
                        if (statusIndicator != null) {
                            statusIndicator.setBackgroundResource(R.drawable.status_indicator_valid);
                        }
                        // 更新配置状态
                        if (currentConfig[0] != null) {
                            currentConfig[0].setStatus(APIConfig.Status.VALID);
                        }
                    } else {
                        if (tvConnectionStatus != null) {
                            tvConnectionStatus.setText(getString(R.string.h_fcc15a0f) + result.message);
                        }
                        if (statusIndicator != null) {
                            statusIndicator.setBackgroundResource(R.drawable.status_indicator_invalid);
                        }
                        if (currentConfig[0] != null) {
                            currentConfig[0].setStatus(APIConfig.Status.INVALID);
                        }
                    }
                }));
            });
        }

        // 获取模型列表按钮
        if (btnFetchModels != null) {
            btnFetchModels.setOnClickListener(v -> {
                if (etApiKey == null || etApiHost == null || spinnerService == null) {
                    return;
                }
                
                String apiKey = etApiKey.getText().toString().trim();
                String apiHost = etApiHost.getText().toString().trim();
                String serviceType = serviceTypeValues[spinnerService.getSelectedItemPosition()];

                if (apiKey.isEmpty()) {
                    Toast.makeText(this, getString(R.string.h_bbbab591), Toast.LENGTH_SHORT).show();
                    return;
                }

                if (llModelSection != null) {
                    llModelSection.setVisibility(View.VISIBLE);
                }
                if (tvModelList != null) {
                    tvModelList.setText(getString(R.string.h_916702da));
                }
                btnFetchModels.setEnabled(false);

                modelListFetcher.fetchModels(apiHost, apiKey)
                    .thenAccept(models -> runOnUiThread(() -> {
                        btnFetchModels.setEnabled(true);

                        if (models != null && !models.isEmpty()) {
                            List<String> modelNames = new ArrayList<>();
                            for (ApiModel model : models) {
                                modelNames.add(model.getName());
                            }

                            ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(
                                ApiConfigActivity.this,
                                android.R.layout.simple_spinner_dropdown_item,
                                modelNames);
                            if (spinnerModel != null) {
                                spinnerModel.setAdapter(modelAdapter);
                            }

                            if (tvModelList != null) {
                                tvModelList.setText(getString(R.string.h_a7dfc193) + models.size() + " 个");
                            }

                            // 自动选择当前配置的模型
                            if (existingConfig != null && existingConfig.getModelName() != null && spinnerModel != null) {
                                for (int i = 0; i < modelNames.size(); i++) {
                                    if (modelNames.get(i).equals(existingConfig.getModelName())) {
                                        spinnerModel.setSelection(i);
                                        break;
                                    }
                                }
                            }
                        } else {
                            if (tvModelList != null) {
                                tvModelList.setText(getString(R.string.h_d1ef35f7));
                            }
                        }
                    }))
                    .exceptionally(e -> {
                        runOnUiThread(() -> {
                            btnFetchModels.setEnabled(true);
                            if (tvModelList != null) {
                                tvModelList.setText(getString(R.string.h_bdf02ef7) + e.getMessage());
                            }
                        });
                        return null;
                    });
            });
        }

        // 模型选择监听
        if (spinnerModel != null) {
            spinnerModel.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    if (spinnerModel == null) return;
                    String selectedModel = (String) spinnerModel.getSelectedItem();
                    if (selectedModel != null && !selectedModel.startsWith("获取") && etModelName != null) {
                        etModelName.setText(selectedModel);
                    }
                }

                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }

        // 服务类型变化时隐藏模型区域
        if (spinnerService != null) {
            spinnerService.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    String serviceType = serviceTypeValues[position];
                    // CATEGORY-AUTOSET(2026-10-11)：选中搜索类服务时自动把分类切到"搜索服务"。
                    // 原因：分类下拉默认停在 index 0 = "AI服务"（categories/categoryValues），
                    // 而 AI 分类的配置会被 OnlineModelManager 当作在线模型纳入列表
                    // （OnlineModelManager 按 category==AI 判定），该列表的启用/停用开关会写
                    // APIConfig.setActive(false)（ModelSelectorActivity/AICenterActivity），
                    // 于是 getAPIConfigByServiceType 的 `config.isActive()` 判断失败 → 返回 null
                    // → 用户明明填了搜索 key 却读不到。这里在选类型时强制归到"搜索服务"，堵住该路径。
                    // （旧的 Bing Search 配置同样受益，无需迁移。）
                    if (spinnerCategory != null && isSearchServiceType(serviceType)) {
                        int idx = indexOfCategory(APIConfig.Category.SEARCH);
                        if (idx >= 0 && spinnerCategory.getSelectedItemPosition() != idx) {
                            spinnerCategory.setSelection(idx);
                        }
                    }
                    // 只有AI服务类型显示模型选择
                    if (llModelSection == null) return;
                    if (APIConfig.ServiceType.OPENAI.equals(serviceType) ||
                        APIConfig.ServiceType.ANTHROPIC.equals(serviceType) ||
                        APIConfig.ServiceType.GOOGLE.equals(serviceType)) {
                        if (llModelSection.getVisibility() != View.VISIBLE) {
                            llModelSection.setVisibility(View.VISIBLE);
                        }
                    } else {
                        llModelSection.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }

        builder.setView(dialogView);
        builder.setPositiveButton(getString(R.string.h_be5fbbe3), (dialog, which) -> {
            if (etName == null || etApiKey == null || etApiHost == null || 
                etModelName == null || etTimeout == null || etDescription == null ||
                spinnerService == null || spinnerCategory == null) {
                Toast.makeText(this, getString(R.string.h_5d86d1c1), Toast.LENGTH_SHORT).show();
                return;
            }
            
            String name = etName.getText().toString().trim();
            String apiKey = etApiKey.getText().toString().trim();

            if (name.isEmpty() || apiKey.isEmpty()) {
                Toast.makeText(this, getString(R.string.h_23c73b77), Toast.LENGTH_SHORT).show();
                return;
            }

            APIConfig config = existingConfig != null ? existingConfig : new APIConfig();
            config.setName(name);
            // METASO 防呆(2026-11-02)：用户填了秘塔 key 但服务下拉不是"秘塔搜索"时自动纠正。
            // 否则 NetworkSearchTool.resolveApiKey 按 serviceType 匹配会落空、退回内置共享 key，
            // 表现就是"apimanager 的 apikey 应用不上"。
            String selectedServiceType = serviceTypeValues[spinnerService.getSelectedItemPosition()];
            String nameHostKey = (name + " " + apiKey + " " + etApiHost.getText()).toLowerCase();
            if (!APIConfig.ServiceType.METASO_SEARCH.equals(selectedServiceType)
                    && (nameHostKey.contains("metaso") || nameHostKey.contains("秘塔"))) {
                selectedServiceType = APIConfig.ServiceType.METASO_SEARCH;
                Toast.makeText(this, "已识别为秘塔搜索，服务类型已自动纠正", Toast.LENGTH_SHORT).show();
            }
            config.setServiceType(selectedServiceType);
            config.setApiKey(apiKey);
            config.setApiHost(etApiHost.getText().toString().trim());
            config.setModelName(etModelName.getText().toString().trim());
            config.setCategory(categoryValues[spinnerCategory.getSelectedItemPosition()]);
            config.setDescription(etDescription.getText().toString().trim());

            String timeoutStr = etTimeout.getText().toString().trim();
            if (!timeoutStr.isEmpty()) {
                try {
                    config.setTimeout(Integer.parseInt(timeoutStr));
                } catch (NumberFormatException e) {
                    config.setTimeout(30);
                }
            }

            apiKeyManager.saveAPIConfig(config);

            // 同步单个配置到在线模型管理器（增量同步：新增/更新/转非AI时移除）
            try {
                boolean changed = onlineModelManager.syncSingleAPIConfig(config.getId());
                if (changed) {
                    Log.d(TAG, "已同步配置到在线模型管理器: " + config.getName());
                }
            } catch (Exception e) {
                Log.e(TAG, "同步到在线模型管理器失败: " + e.getMessage());
            }

            loadApiConfigs();
            Toast.makeText(this, getString(R.string.h_3b108349), Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton(getString(R.string.h_625fb26b), null);
        builder.show();
    }

    private void updateUsageDisplay(LinearLayout llUsage, ProgressBar pbUsage, 
            TextView tvPercent, TextView tvDetail, String configId) {
        if (usageTracker == null || configId == null || configId.isEmpty()) {
            return;
        }
        UsageTracker.UsageInfo cached = usageTracker.getUsageInfo(configId);
        applyUsageTo(llUsage, pbUsage, tvPercent, tvDetail, cached);
        if (cached != null && cached.totalQuota > 0) {
            return; // 已有数据，不重复请求
        }
        // 缓存里没有配额：按该条配置的地址与密钥主动拉一次。
        // 此前这里只读缓存、而缓存从无写入方，导致这块配额显示永远不出现。
        try {
            APIConfig config = apiKeyManager.getAPIConfigById(configId);
            if (config == null || config.getApiHost() == null || config.getApiHost().isEmpty()
                    || config.getApiKey() == null || config.getApiKey().isEmpty()) {
                return;
            }
            usageTracker.fetchUsageForConfig(configId, config.getApiHost(),
                    config.getApiKey(), "monthly").thenAccept(info ->
                    runOnUiThread(() -> applyUsageTo(llUsage, pbUsage, tvPercent, tvDetail, info)));
        } catch (Exception e) {
            Log.w("ApiConfigActivity", "拉取服务商配额失败: " + e.getMessage());
        }
    }

    /** 锁住由模型中心管理的字段：保留可读（值仍会原样回写），但不可编辑 */
    private void lockOnlineManagedField(android.widget.EditText field) {
        if (field == null) return;
        field.setEnabled(false);
        field.setFocusable(false);
        field.setLongClickable(false);
        field.setAlpha(0.55f);
    }

    /** 配额渲染的唯一出口：无数据时整块隐藏，不显示 0/0 这种看起来像真数据的假值 */
    private void applyUsageTo(LinearLayout llUsage, ProgressBar pbUsage,
                              TextView tvPercent, TextView tvDetail,
                              UsageTracker.UsageInfo info) {
        if (llUsage == null) return;
        if (info != null && info.totalQuota > 0) {
            int progress = (int) ((info.usedQuota * 100) / info.totalQuota);
            pbUsage.setProgress(progress);
            tvPercent.setText(progress + "%");
            tvDetail.setText(String.format(Locale.getDefault(),
                "%.2fM / %.2fM tokens",
                info.usedQuota / 1000000.0,
                info.totalQuota / 1000000.0));
            llUsage.setVisibility(View.VISIBLE);
        } else {
            llUsage.setVisibility(View.GONE);
        }
    }

    private void confirmDelete(APIConfig config) {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.h_631cd220))
            .setMessage(getString(R.string.h_46d31be4) + config.getName() + getString(R.string.h_ee0b8b94))
            .setPositiveButton(getString(R.string.h_2f4aaddd), (dialog, which) -> {
                String configId = config.getId();
                apiKeyManager.deleteAPIConfig(configId);
                // 同步删除 OnlineModelManager 中对应的配置
                try {
                    onlineModelManager.removeByAPIConfigId(configId);
                } catch (Exception e) {
                    Log.e(TAG, "同步删除在线模型配置失败: " + e.getMessage());
                }
                loadApiConfigs();
                Toast.makeText(this, getString(R.string.h_5cc23262), Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(getString(R.string.h_625fb26b), null)
            .show();
    }

    private void testSingleConfig(APIConfig config) {
        Toast.makeText(this, getString(R.string.h_6446bdbb) + config.getName() + "...", Toast.LENGTH_SHORT).show();
        
        apiKeyManager.testAPIConnection(config)
            .thenAccept(result -> runOnUiThread(() -> {
                String message = result.success ? 
                    "测试成功! 延迟: " + result.latency + "ms" : 
                    getString(R.string.h_9d9b6ba9) + result.message;
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                loadApiConfigs();
            }));
    }

    private void testAllConfigs() {
        if (filteredConfigs.isEmpty()) {
            Toast.makeText(this, getString(R.string.h_3702ea82), Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, getString(R.string.h_95aab33a) + filteredConfigs.size() + getString(R.string.h_38b7cbce), Toast.LENGTH_SHORT).show();

        List<CompletableFuture<APIKeyManager.TestResult>> futures = new ArrayList<>();
        for (APIConfig config : filteredConfigs) {
            futures.add(apiKeyManager.testAPIConnection(config));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> runOnUiThread(() -> {
                int successCount = 0;
                for (CompletableFuture<APIKeyManager.TestResult> future : futures) {
                    try {
                        APIKeyManager.TestResult result = future.get();
                        if (result.success) successCount++;
                    } catch (Exception ignored) {}
                }
                loadApiConfigs();
                Toast.makeText(this, 
                    "批量测试完成: " + successCount + "/" + futures.size() + " 成功", 
                    Toast.LENGTH_LONG).show();
            }));
    }

    private void importFromJson() {
        if (!checkPermission()) {
            requestPermission();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "选择JSON文件"), REQUEST_CODE_IMPORT_FILE);
    }

    private void exportToJson() {
        if (!checkPermission()) {
            requestPermission();
            return;
        }

        if (allConfigs.isEmpty()) {
            Toast.makeText(this, getString(R.string.h_4ba325cc), Toast.LENGTH_SHORT).show();
            return;
        }

        String json = apiKeyManager.exportToJson();
        String fileName = "api_configs_" + 
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".json";

        try {
            // MediaStore 写入 Download/OilQuiz（无需"所有文件访问"；修复旧实现"写私有却提示公共路径"的不一致）
            String rel = com.oilquiz.app.util.PublicStorageWriter.writeText(
                    this, "", fileName, json);
            if (rel == null) {
                throw new java.io.IOException("导出写入公共目录失败（请检查存储权限）");
            }
            Toast.makeText(this, getString(R.string.h_38d4b4e6) + rel, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.h_2e0d8c60) + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void importFromConfigFile() {
        if (!checkPermission()) {
            requestPermission();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "选择配置文件"), REQUEST_CODE_IMPORT_CONFIG_FILE);
    }

    private void scanArchiveDirectory() {
        if (!checkPermission()) {
            requestPermission();
            return;
        }

        String archivePath = apiKeyManager.getArchivePath();
        apiKeyManager.ensureArchiveDirectoryExists();

        Toast.makeText(this, getString(R.string.h_dfd08aa7) + archivePath, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            List<APIConfig> foundConfigs = apiKeyManager.scanArchiveDirectory(archivePath);
            
            runOnUiThread(() -> {
                if (foundConfigs.isEmpty()) {
                    new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.h_59115b55))
                        .setMessage(getString(R.string.h_4ce90060) + archivePath)
                        .setPositiveButton(getString(R.string.h_38cf16f2), null)
                        .setNeutralButton(getString(R.string.h_f588210f), (dialog, which) -> {
                            showArchivePathDialog();
                        })
                        .show();
                    return;
                }

                new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.h_739cdfd3))
                    .setMessage(getString(R.string.h_ed3ee7c5) + foundConfigs.size() + getString(R.string.h_15ee0ae6) + archivePath)
                    .setPositiveButton(getString(R.string.h_8d9a071e), (dialog, which) -> {
                        for (APIConfig config : foundConfigs) {
                            apiKeyManager.saveAPIConfig(config);
                            // 同步单个配置到在线模型管理器
                            try {
                                onlineModelManager.syncSingleAPIConfig(config.getId());
                            } catch (Exception e) {
                                Log.e(TAG, "同步到在线模型管理器失败: " + e.getMessage());
                            }
                        }
                        loadApiConfigs();
                        Toast.makeText(this, getString(R.string.h_946262ed) + foundConfigs.size() + getString(R.string.h_e45bedbe), Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(getString(R.string.h_625fb26b), null)
                    .show();
            });
        }).start();
    }

    private void showArchivePathDialog() {
        String currentPath = apiKeyManager.getArchivePath();
        String defaultPath = apiKeyManager.getDefaultArchivePath();
        boolean isUsingDefault = apiKeyManager.isUsingDefaultArchivePath();
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.h_ef12580c));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 24, 32, 24);

        TextView tvInfo = new TextView(this);
        tvInfo.setText(getString(R.string.h_e475ca89));
        tvInfo.setTextSize(14);
        tvInfo.setPadding(0, 0, 0, 16);
        layout.addView(tvInfo);

        TextView tvCurrent = new TextView(this);
        String currentLabel = isUsingDefault ? "当前路径（默认）：" : "当前路径（自定义）：";
        tvCurrent.setText(currentLabel);
        tvCurrent.setTextSize(12);
        tvCurrent.setPadding(0, 8, 0, 4);
        layout.addView(tvCurrent);

        TextView tvCurrentPath = new TextView(this);
        tvCurrentPath.setText(currentPath);
        tvCurrentPath.setTextSize(12);
        tvCurrentPath.setPadding(16, 0, 0, 16);
        tvCurrentPath.setTextColor(ThemeColors.get(R.color.hc_ff666666));
        layout.addView(tvCurrentPath);

        TextView tvInputLabel = new TextView(this);
        tvInputLabel.setText(getString(R.string.h_1fb09821));
        tvInputLabel.setTextSize(12);
        tvInputLabel.setPadding(0, 8, 0, 4);
        layout.addView(tvInputLabel);

        LinearLayout inputLayout = new LinearLayout(this);
        inputLayout.setOrientation(LinearLayout.HORIZONTAL);
        inputLayout.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText etPath = new EditText(this);
        etPath.setHint(defaultPath);
        etPath.setSingleLine(true);
        etPath.setPadding(16, 12, 16, 12);
        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        etPath.setLayoutParams(etParams);
        inputLayout.addView(etPath);

        MaterialButton btnBrowse = new MaterialButton(this);
        btnBrowse.setText(getString(R.string.h_153fa67a));
        btnBrowse.setCornerRadius(8);
        btnBrowse.setPadding(20, 0, 20, 0);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(16, 0, 0, 0);
        btnBrowse.setLayoutParams(btnParams);
        btnBrowse.setOnClickListener(v -> {
            openFolderPicker(etPath);
        });
        inputLayout.addView(btnBrowse);

        layout.addView(inputLayout);

        if (!isUsingDefault) {
            TextView tvResetInfo = new TextView(this);
            tvResetInfo.setText(getString(R.string.h_7070b4ed));
            tvResetInfo.setTextSize(11);
            tvResetInfo.setPadding(0, 12, 0, 0);
            tvResetInfo.setTextColor(ThemeColors.get(R.color.hc_ff999999));
            layout.addView(tvResetInfo);
        }

        builder.setView(layout);
        
        builder.setPositiveButton(getString(R.string.h_be5fbbe3), (dialog, which) -> {
            String path = etPath.getText().toString().trim();
            if (path.isEmpty()) {
                apiKeyManager.resetToDefaultArchivePath();
                apiKeyManager.ensureArchiveDirectoryExists();
                Toast.makeText(this, getString(R.string.h_de9f352a), Toast.LENGTH_SHORT).show();
            } else {
                apiKeyManager.setArchivePath(path);
                apiKeyManager.ensureArchiveDirectoryExists();
                Toast.makeText(this, getString(R.string.h_79bc0be3), Toast.LENGTH_SHORT).show();
            }
        });

        if (!isUsingDefault) {
            builder.setNeutralButton(getString(R.string.h_7468f3e5), (dialog, which) -> {
                apiKeyManager.resetToDefaultArchivePath();
                apiKeyManager.ensureArchiveDirectoryExists();
                Toast.makeText(this, getString(R.string.h_de9f352a), Toast.LENGTH_SHORT).show();
            });
        }

        builder.setNegativeButton(getString(R.string.h_625fb26b), null);
        builder.show();
    }

    private void openFolderPicker(EditText etPath) {
        if (!checkPermission()) {
            requestPermission();
            return;
        }
        
        try {
            pendingPathEditText = etPath;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_CODE_SELECT_FOLDER);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.h_3360d889) + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String getRealPathFromTreeUri(Uri treeUri) {
        if (treeUri == null) {
            return null;
        }

        String path = treeUri.getPath();
        if (path != null && path.startsWith("/tree/")) {
            String treeId = path.substring(6);
            
            if (treeId.contains(":")) {
                String[] parts = treeId.split(":", 2);
                String storageType = parts[0];
                String relativePath = parts[1];
                
                if ("primary".equalsIgnoreCase(storageType)) {
                    return Environment.getExternalStorageDirectory() + "/" + relativePath;
                }
                
                try {
                    java.io.File[] externalDirs = getExternalFilesDirs(null);
                    if (externalDirs != null && externalDirs.length > 0) {
                        for (java.io.File dir : externalDirs) {
                            if (dir != null) {
                                String dirPath = dir.getAbsolutePath();
                                int androidIndex = dirPath.indexOf("/Android/");
                                if (androidIndex > 0) {
                                    String storageRoot = dirPath.substring(0, androidIndex);
                                    if (storageRoot.contains(storageType)) {
                                        return storageRoot + "/" + relativePath;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                }
            }
        }
        
        return null;
    }

    private boolean checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(this, 
                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, 
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this, getString(R.string.h_b4061470), Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                } catch (Exception ex) {
                    Toast.makeText(this, getString(R.string.h_91fb87bf), Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_CODE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, getString(R.string.h_5ded3757), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.h_5a217bd9), Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CODE_IMPORT_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(getContentResolver().openInputStream(uri)));
                    StringBuilder jsonBuilder = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        jsonBuilder.append(line);
                    }
                    reader.close();

                    int imported = apiKeyManager.importFromJson(jsonBuilder.toString());
                    // 同步到在线模型管理器
                    try {
                        onlineModelManager.syncFromAPIKeyManager();
                    } catch (Exception e) {
                        Log.e(TAG, "同步到在线模型管理器失败: " + e.getMessage());
                    }
                    loadApiConfigs();
                    Toast.makeText(this, getString(R.string.h_946262ed) + imported + getString(R.string.h_b6681207), Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, getString(R.string.h_45332d13) + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        } else if (requestCode == REQUEST_CODE_SELECT_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | 
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                
                String path = getRealPathFromTreeUri(treeUri);
                if (path != null && pendingPathEditText != null) {
                    pendingPathEditText.setText(path);
                    Toast.makeText(this, getString(R.string.h_926fa922) + path, Toast.LENGTH_SHORT).show();
                } else if (path != null) {
                    apiKeyManager.setArchivePath(path);
                    apiKeyManager.ensureArchiveDirectoryExists();
                    Toast.makeText(this, getString(R.string.h_9cf56df2) + path, Toast.LENGTH_SHORT).show();
                }
                pendingPathEditText = null;
            }
        } else if (requestCode == REQUEST_CODE_IMPORT_CONFIG_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(getContentResolver().openInputStream(uri)));
                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }
                    reader.close();

                    APIConfigParser.ParseResult parseResult = APIConfigParser.parse(content.toString());
                    
                    if (parseResult.configs.isEmpty()) {
                        String errorMsg = parseResult.errorMessage != null ? 
                            parseResult.errorMessage : "未检测到有效的API配置";
                        Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show();
                    } else {
                        showImportPreviewDialog(parseResult);
                    }
                } catch (Exception e) {
                    Toast.makeText(this, getString(R.string.h_4c368c6a) + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void showImportPreviewDialog(APIConfigParser.ParseResult parseResult) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.h_9dec4140));

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_import_preview, null);
        
        TextView tvDetectedFormat = dialogView.findViewById(R.id.tv_detected_format);
        TextView tvConfigCount = dialogView.findViewById(R.id.tv_config_count);
        RadioGroup rgConfigs = dialogView.findViewById(R.id.rg_configs);
        LinearLayout llNoConfig = dialogView.findViewById(R.id.ll_no_config);
        LinearLayout llPreview = dialogView.findViewById(R.id.ll_preview);
        TextView tvErrorMessage = dialogView.findViewById(R.id.tv_error_message);
        TextView tvPreviewServiceType = dialogView.findViewById(R.id.tv_preview_service_type);
        TextView tvPreviewApiKey = dialogView.findViewById(R.id.tv_preview_api_key);
        TextView tvPreviewApiHost = dialogView.findViewById(R.id.tv_preview_api_host);
        TextView tvPreviewModel = dialogView.findViewById(R.id.tv_preview_model);

        tvDetectedFormat.setText(parseResult.detectedFormat);
        tvConfigCount.setText(getString(R.string.h_482f035d) + parseResult.configs.size() + getString(R.string.h_e45bedbe));

        if (parseResult.configs.isEmpty()) {
            llNoConfig.setVisibility(View.VISIBLE);
            llPreview.setVisibility(View.GONE);
            tvErrorMessage.setText(parseResult.errorMessage);
        } else {
            llNoConfig.setVisibility(View.GONE);
            llPreview.setVisibility(View.VISIBLE);

            for (int i = 0; i < parseResult.configs.size(); i++) {
                APIConfig config = parseResult.configs.get(i);
                RadioButton radioButton = new RadioButton(this);
                radioButton.setText(config.getName() + " (" + getServiceTypeDisplay(config.getServiceType()) + ")");
                radioButton.setTag(config);
                radioButton.setPadding(8, 8, 8, 8);
                rgConfigs.addView(radioButton);
                
                if (i == 0) {
                    radioButton.setChecked(true);
                    tvPreviewServiceType.setText(getServiceTypeDisplay(config.getServiceType()));
                    tvPreviewApiKey.setText(config.getMaskedApiKey());
                    String apiHost = config.getApiHost();
                    tvPreviewApiHost.setText(apiHost != null && !apiHost.isEmpty() ? apiHost : getString(R.string.h_fe2d26a2));
                    String modelName = config.getModelName();
                    tvPreviewModel.setText(modelName != null && !modelName.isEmpty() ? modelName : getString(R.string.h_fe2d26a2));
                }
            }

            rgConfigs.setOnCheckedChangeListener((group, checkedId) -> {
                RadioButton selected = group.findViewById(checkedId);
                if (selected != null) {
                    APIConfig config = (APIConfig) selected.getTag();
                    tvPreviewServiceType.setText(getServiceTypeDisplay(config.getServiceType()));
                    tvPreviewApiKey.setText(config.getMaskedApiKey());
                    String apiHost = config.getApiHost();
                    tvPreviewApiHost.setText(apiHost != null && !apiHost.isEmpty() ? apiHost : getString(R.string.h_fe2d26a2));
                    String modelName = config.getModelName();
                    tvPreviewModel.setText(modelName != null && !modelName.isEmpty() ? modelName : getString(R.string.h_fe2d26a2));
                }
            });
        }

        builder.setView(dialogView);
        builder.setPositiveButton(getString(R.string.h_8d9a071e), (dialog, which) -> {
            if (!parseResult.configs.isEmpty()) {
                int selectedId = rgConfigs.getCheckedRadioButtonId();
                RadioButton selected = rgConfigs.findViewById(selectedId);
                if (selected != null) {
                    APIConfig config = (APIConfig) selected.getTag();
                    apiKeyManager.saveAPIConfig(config);
                    // 同步单个配置到在线模型管理器
                    try {
                        onlineModelManager.syncSingleAPIConfig(config.getId());
                    } catch (Exception e) {
                        Log.e(TAG, "同步到在线模型管理器失败: " + e.getMessage());
                    }
                    loadApiConfigs();
                    Toast.makeText(this, getString(R.string.h_5b2dccdb) + config.getName(), Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton(getString(R.string.h_625fb26b), null);
        builder.show();
    }
}
