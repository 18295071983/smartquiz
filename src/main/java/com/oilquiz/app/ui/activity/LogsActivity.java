package com.oilquiz.app.ui.activity;

import com.oilquiz.app.theme.ThemeColors;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.tabs.TabLayout;

import com.oilquiz.app.R;
import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileWriter;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 统一日志中心
 * - 合并四大日志源：应用日志(AppLogger)、AI服务日志(AILogger)、崩溃报告、操作记录(Room)
 * - 实时日志流：新增日志自动出现、自动滚动、可暂停/恢复
 * - 关键字搜索 + 级别过滤(V/D/I/W/E)
 * - 点击条目查看完整多行详情
 */
public class LogsActivity extends BaseActivity {

    // 日志来源
    private static final String SOURCE_APP = "应用";
    private static final String SOURCE_AI = "AI服务";
    private static final String SOURCE_CRASH = "崩溃";
    private static final String SOURCE_OP = "操作";
    /** 推理引擎（native）日志：单独一个 Tab，不混进「全部/AI服务」 */
    private static final String SOURCE_ENGINE = "引擎";

    // Tab 类型
    private static final int TAB_ALL = 0;
    private static final int TAB_APP = 1;
    private static final int TAB_AI = 2;
    private static final int TAB_CRASH = 3;
    private static final int TAB_OP = 4;
    private static final int TAB_ENGINE = 5;

    private static final int MAX_ITEMS = 3000;      // 列表最大条目数（防内存膨胀）
    private static final int INITIAL_VISIBLE = 200; // 初始显示条数
    private static final int LOAD_MORE_STEP = 200;  // 每次"加载更多"条数

    // UI组件
    private RecyclerView rvLogs;
    private EditText etSearch;
    private Chip chipLive;
    private ChipGroup chipGroupLevels;
    private MaterialButton btnRefreshLogs;
    private MaterialButton btnClearLogs;
    private MaterialButton btnCopyLogs;
    private MaterialButton btnExportLogs;
    private MaterialButton btnShareLogs;
    private MaterialButton btnLoadMore;
    private TextView tvLogCount;
    private TabLayout tabLayout;

    // 数据
    /** 引擎日志轮询（native 环 drain 即清空，1.2s 一次） */
    private final android.os.Handler engineLogHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable engineLogPoller = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            // drain + 解析放到后台，批量插回 UI（一次 notify，避免列表错位/抖动）
            new Thread(() -> {
                try {
                    List<LogItem> items = drainEngineItems();
                    if (!items.isEmpty() && !destroyed) {
                        runOnUiThread(() -> {
                            if (!destroyed) addLiveItems(items, SOURCE_ENGINE);
                        });
                    }
                } catch (Throwable ignored) {
                }
            }, "engine-log-drain").start();
            engineLogHandler.postDelayed(this, 1200);
        }
    };

    private final List<LogItem> allItems = new ArrayList<>();      // 当前Tab全部数据（含实时追加）
    private final List<LogItem> filteredItems = new ArrayList<>(); // 过滤后数据
    private final LogAdapter logAdapter = new LogAdapter();
    private final Object listLock = new Object();

    private int currentTab = TAB_ALL;
    private String currentLevelFilter = "ALL"; // ALL / V / D / I / W / E
    private String currentSearchQuery = "";
    private boolean livePaused = false;
    private int pendingLiveCount = 0;
    private int visibleCount = INITIAL_VISIBLE;
    private boolean destroyed = false;

    private final AppLogger.LogListener appLogListener = new AppLogger.LogListener() {
        @Override
        public void onLogAdded(AppLogger.LogRecord record) {
            if (record == null) return;
            addLiveItem(new LogItem(record.timestamp, normalizeLevel(record.level), record.tag,
                    record.message, record.message, SOURCE_APP), SOURCE_APP);
        }
    };

    private final AILogger.OnLogListener aiLogListener = new AILogger.OnLogListener() {
        @Override
        public void onNewLog(AILogger.LogEntry entry) {
            if (entry == null) return;
            addLiveItem(new LogItem(parseTimestamp(entry.timestamp), normalizeLevel(entry.level),
                    entry.tag, entry.message,
                    entry.fullText != null ? entry.fullText : entry.message, SOURCE_AI), SOURCE_AI);
        }
    };

    @Override
    protected int getLayoutId() {
        return R.layout.activity_logs;
    }

    @Override
    protected void initView() {
        rvLogs = findViewById(R.id.rv_logs);
        etSearch = findViewById(R.id.et_search);
        chipLive = findViewById(R.id.chip_live);
        chipGroupLevels = findViewById(R.id.chip_group_levels);
        btnRefreshLogs = findViewById(R.id.btn_refresh_logs);
        btnClearLogs = findViewById(R.id.btn_clear_logs);
        btnCopyLogs = findViewById(R.id.btn_copy_logs);
        btnExportLogs = findViewById(R.id.btn_export_logs);
        btnShareLogs = findViewById(R.id.btn_share_logs);
        btnLoadMore = findViewById(R.id.btn_load_more);
        tvLogCount = findViewById(R.id.tv_log_count);
        tabLayout = findViewById(R.id.tab_layout);

        rvLogs.setLayoutManager(new LinearLayoutManager(this));
        rvLogs.setAdapter(logAdapter);

        initTabs();
        engineLogHandler.removeCallbacks(engineLogPoller);
        engineLogHandler.postDelayed(engineLogPoller, 1200);
    }

    private void initTabs() {
        if (tabLayout == null) return;
        tabLayout.removeAllTabs();
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.h_a8b0c204)).setTag(TAB_ALL));
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.h_391cf35a)).setTag(TAB_APP));
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.h_b1821dcb)).setTag(TAB_AI));
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.h_9bf26c4f)).setTag(TAB_CRASH));
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.h_cf8e1f09)).setTag(TAB_OP));
        tabLayout.addTab(tabLayout.newTab().setText("引擎").setTag(TAB_ENGINE));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                Object tag = tab.getTag();
                if (tag instanceof Integer) {
                    currentTab = (Integer) tag;
                    loadData();
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    @Override
    protected void initData() {
        // 初始化日志基础设施
        try {
            AppLogger.init(this);
        } catch (Exception ignored) {
        }
        try {
            AILogger.init(this);
        } catch (Exception ignored) {
        }

        // 注册实时监听器
        AppLogger.addLogListener(appLogListener);
        AILogger.addLogListener(aiLogListener);

        loadData();
    }

    @Override
    protected void initListener() {
        // 搜索：实时过滤
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s == null ? "" : s.toString().trim();
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // 级别过滤
        bindLevelChip(R.id.chip_level_all, "ALL");
        bindLevelChip(R.id.chip_level_v, "V");
        bindLevelChip(R.id.chip_level_d, "D");
        bindLevelChip(R.id.chip_level_i, "I");
        bindLevelChip(R.id.chip_level_w, "W");
        bindLevelChip(R.id.chip_level_e, "E");

        // 实时开关
        chipLive.setOnCheckedChangeListener((buttonView, isChecked) -> {
            livePaused = !isChecked;
            if (!livePaused) {
                pendingLiveCount = 0;
                applyFilter();
                updateLiveChip();
            }
        });

        btnRefreshLogs.setOnClickListener(v -> loadData());
        btnClearLogs.setOnClickListener(v -> confirmClearLogs());
        btnCopyLogs.setOnClickListener(v -> copyLogs());
        btnExportLogs.setOnClickListener(v -> exportLogs());
        btnShareLogs.setOnClickListener(v -> shareLogs());
        btnLoadMore.setOnClickListener(v -> {
            visibleCount += LOAD_MORE_STEP;
            updateList();
        });
    }

    private void bindLevelChip(int chipId, final String level) {
        Chip chip = findViewById(chipId);
        if (chip == null) return;
        chip.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                currentLevelFilter = level;
                applyFilter();
            }
        });
    }

    private void updateLiveChip() {
        if (chipLive == null) return;
        if (livePaused && pendingLiveCount > 0) {
            chipLive.setText(getString(R.string.h_0af87f8b) + pendingLiveCount + ")");
        } else {
            chipLive.setText(getString(R.string.h_2843e2f6));
        }
    }

    // ==================== 数据加载 ====================

    private void loadData() {
        showToast(getString(R.string.h_f34fa10e));
        new Thread(() -> {
            try {
                final List<LogItem> newItems = new ArrayList<>();
                switch (currentTab) {
                    case TAB_ALL:
                        newItems.addAll(loadAppItems());
                        newItems.addAll(loadAiItems());
                        newItems.addAll(loadCrashItems());
                        newItems.addAll(loadOpItems());
                        break;
                    case TAB_APP:
                        newItems.addAll(loadAppItems());
                        break;
                    case TAB_AI:
                        newItems.addAll(loadAiItems());
                        break;
                    case TAB_CRASH:
                        newItems.addAll(loadCrashItems());
                        break;
                    case TAB_OP:
                        newItems.addAll(loadOpItems());
                        break;
                    case TAB_ENGINE:
                        newItems.addAll(loadEngineItemsFromFile());
                        break;
                }

                Collections.sort(newItems, (a, b) -> Long.compare(b.timestamp, a.timestamp));
                if (newItems.size() > MAX_ITEMS) {
                    newItems.subList(MAX_ITEMS, newItems.size()).clear();
                }

                runOnUiThread(() -> {
                    if (destroyed) return;
                    synchronized (listLock) {
                        allItems.clear();
                        allItems.addAll(newItems);
                    }
                    pendingLiveCount = 0;
                    visibleCount = INITIAL_VISIBLE;
                    applyFilter();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!destroyed) {
                        showToast(getString(R.string.h_a8be487e) + e.getMessage());
                    }
                });
            }
        }).start();
    }

    private List<LogItem> loadAppItems() {
        List<LogItem> items = new ArrayList<>();
        for (AppLogger.LogRecord record : AppLogger.getStructuredLogs()) {
            items.add(new LogItem(record.timestamp, normalizeLevel(record.level),
                    record.tag, record.message, record.message, SOURCE_APP));
        }
        return items;
    }

    private List<LogItem> loadAiItems() {
        List<LogItem> items = new ArrayList<>();
        for (AILogger.LogEntry entry : AILogger.getAllLogs()) {
            items.add(new LogItem(parseTimestamp(entry.timestamp), normalizeLevel(entry.level),
                    entry.tag, entry.message,
                    entry.fullText != null ? entry.fullText : entry.message, SOURCE_AI));
        }
        return items;
    }

    /**
     * 引擎日志历史：native 落盘文件（oilquiz/logs/engine_logs.txt）尾部。
     * 引擎 Tab 打开/切回时用文件，避免"环被实时 drain 清空后切 Tab 变空"。
     */
    private List<LogItem> loadEngineItemsFromFile() {
        List<LogItem> items = new ArrayList<>();
        try {
            File f = new File(getFilesDir(), "oilquiz/logs/engine_logs.txt");
            if (!f.exists()) return items;
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f),
                            java.nio.charset.StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                items.add(parseEngineLine(line));
            }
            reader.close();
            if (items.size() > 2000) {
                items = new ArrayList<>(items.subList(items.size() - 2000, items.size()));
            }
        } catch (Throwable ignored) {
        }
        return items;
    }

    /** 解析 "MM-dd HH:mm:ss [I][tag] msg"（native 落盘格式） */
    private LogItem parseEngineLine(String line) {
        long ts = System.currentTimeMillis();
        String level = "I";
        String tag = "引擎";
        String msg = line;
        try {
            if (line.length() > 15 && line.charAt(2) == '-' && line.charAt(5) == ' ') {
                String time = line.substring(0, 14);
                String rest = line.substring(15).trim();
                try {
                    SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
                    Date d = fmt.parse(time);
                    if (d != null) {
                        java.util.Calendar c = java.util.Calendar.getInstance();
                        c.setTime(d);
                        c.set(java.util.Calendar.YEAR, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR));
                        ts = c.getTimeInMillis();
                    }
                } catch (Throwable ignored) {
                }
                int levelEnd = rest.startsWith("[") ? rest.indexOf(']') : -1;
                if (levelEnd == 2) {
                    level = String.valueOf(rest.charAt(1));
                    int tagStart = rest.indexOf('[', levelEnd + 1);
                    int tagEnd = tagStart >= 0 ? rest.indexOf(']', tagStart) : -1;
                    if (tagEnd > tagStart) {
                        tag = rest.substring(tagStart + 1, tagEnd);
                        msg = rest.substring(tagEnd + 1).trim();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new LogItem(ts, normalizeLevel(level), tag, msg, line, SOURCE_ENGINE);
    }

    /**
     * 推理引擎消息：native 日志环 drain 出来的行（"[I][LlamaJNI] ..."），
     * 归到「AI服务」来源，便于和 AILogger 一起搜索/过滤。
     */
    private List<LogItem> drainEngineItems() {
        List<LogItem> items = new ArrayList<>();
        try {
            String raw = com.oilquiz.app.ai.jni.LlamaHelper.drainEngineLogs();
            if (raw == null || raw.isEmpty()) return items;
            long now = System.currentTimeMillis();
            for (String line : raw.split("\\n")) {
                if (line == null || line.isEmpty()) continue;
                String level = "I";
                String tag = "引擎";
                String msg = line;
                int levelEnd = line.startsWith("[") ? line.indexOf(']') : -1;
                if (levelEnd == 2) {
                    level = String.valueOf(line.charAt(1));
                    int tagStart = line.indexOf('[', levelEnd + 1);
                    int tagEnd = tagStart >= 0 ? line.indexOf(']', tagStart) : -1;
                    if (tagEnd > tagStart) {
                        tag = line.substring(tagStart + 1, tagEnd);
                        msg = line.substring(tagEnd + 1).trim();
                    }
                }
                items.add(new LogItem(now, normalizeLevel(level), tag, msg, line, SOURCE_AI));
            }
        } catch (Throwable ignored) {
        }
        return items;
    }

    private List<LogItem> loadCrashItems() {
        List<LogItem> items = new ArrayList<>();
        for (AppLogger.LogRecord report : AppLogger.getCrashReports()) {
            items.add(new LogItem(report.timestamp, "E", report.tag, report.message, report.message, SOURCE_CRASH));
        }
        return items;
    }

    private List<LogItem> loadOpItems() {
        List<LogItem> items = new ArrayList<>();
        try {
            List<com.oilquiz.app.model.LogEntry> entries =
                    AppDatabase.getDatabase(this).logEntryDao().getRecentLogEntries(1000);
            for (com.oilquiz.app.model.LogEntry entry : entries) {
                items.add(new LogItem(entry.getTimestamp(), normalizeLevel(entry.getLevel()),
                        entry.getAction(), entry.getDetail(), entry.getDetail(), SOURCE_OP));
            }
        } catch (Exception e) {
            items.add(new LogItem(System.currentTimeMillis(), "E", "操作记录",
                    "读取操作记录失败: " + e.getMessage(), "读取操作记录失败: " + e.getMessage(), SOURCE_OP));
        }
        return items;
    }

    // ==================== 实时日志流 ====================

    /**
     * 实时追加一条日志。数据始终写入 allItems（暂停时只计数不刷 UI），
     * 恢复实时后统一重建过滤列表。
     */
    private void addLiveItem(LogItem item, String source) {
        boolean tabAccepts = tabSourceMatches(currentTab, source);
        synchronized (listLock) {
            allItems.add(0, item);
            if (allItems.size() > MAX_ITEMS) {
                allItems.subList(MAX_ITEMS, allItems.size()).clear();
            }
        }

        if (destroyed || !tabAccepts) return;

        if (livePaused) {
            pendingLiveCount++;
            runOnUiThread(this::updateLiveChip);
            return;
        }

        if (matchesFilter(item)) {
            runOnUiThread(() -> {
                if (destroyed) return;
                synchronized (listLock) {
                    filteredItems.add(0, item);
                }
                // 适配器持有的是 filteredItems 的拷贝：只 notifyItemInserted 会让
                // itemCount 与位置错位（RecyclerView 报 Invalid view holder adapter position）。
                // 统一走 updateList() 重建 + notifyDataSetChanged。
                updateList();
                // 用户位于顶部附近时自动滚动到最新
                LinearLayoutManager lm = (LinearLayoutManager) rvLogs.getLayoutManager();
                if (lm != null && lm.findFirstVisibleItemPosition() <= 1) {
                    rvLogs.scrollToPosition(0);
                }
            });
        }
    }

    /**
     * 批量追加（引擎日志一次 drain 可能几十行）：一次性写列表、一次 updateList，
     * 避免每行都 notifyDataSetChanged 引起布局抖动/错位。
     */
    private void addLiveItems(List<LogItem> items, String source) {
        if (items == null || items.isEmpty()) return;
        synchronized (listLock) {
            for (LogItem item : items) {
                allItems.add(0, item);
            }
            if (allItems.size() > MAX_ITEMS) {
                allItems.subList(MAX_ITEMS, allItems.size()).clear();
            }
        }
        boolean tabAccepts = tabSourceMatches(currentTab, source);
        if (destroyed || !tabAccepts) return;
        if (livePaused) {
            pendingLiveCount += items.size();
            runOnUiThread(this::updateLiveChip);
            return;
        }
        runOnUiThread(() -> {
            if (destroyed) return;
            synchronized (listLock) {
                for (LogItem item : items) {
                    if (matchesFilter(item)) filteredItems.add(0, item);
                }
            }
            updateList();
            LinearLayoutManager lm = (LinearLayoutManager) rvLogs.getLayoutManager();
            if (lm != null && lm.findFirstVisibleItemPosition() <= 1) {
                rvLogs.scrollToPosition(0);
            }
        });
    }

    private boolean tabSourceMatches(int tab, String source) {
        switch (tab) {
            case TAB_APP: return SOURCE_APP.equals(source);
            case TAB_AI: return SOURCE_AI.equals(source);
            case TAB_CRASH: return SOURCE_CRASH.equals(source);
            case TAB_OP: return SOURCE_OP.equals(source);
            case TAB_ENGINE: return SOURCE_ENGINE.equals(source);
            case TAB_ALL:
            default:
                // 「全部」只放应用/AI/崩溃/操作；引擎日志单独 Tab，避免刷屏
                return !SOURCE_ENGINE.equals(source);
        }
    }

    // ==================== 过滤与展示 ====================

    private boolean matchesFilter(LogItem item) {
        if (!"ALL".equals(currentLevelFilter) && !currentLevelFilter.equals(item.level)) {
            return false;
        }
        if (!currentSearchQuery.isEmpty()) {
            String tag = item.tag == null ? "" : item.tag.toLowerCase(Locale.getDefault());
            String msg = item.message == null ? "" : item.message.toLowerCase(Locale.getDefault());
            String detail = item.detail == null ? "" : item.detail.toLowerCase(Locale.getDefault());
            String query = currentSearchQuery.toLowerCase(Locale.getDefault());
            if (!tag.contains(query) && !msg.contains(query) && !detail.contains(query)) {
                return false;
            }
        }
        return true;
    }

    private void applyFilter() {
        synchronized (listLock) {
            filteredItems.clear();
            for (LogItem item : allItems) {
                if (tabSourceMatches(currentTab, item.source) && matchesFilter(item)) {
                    filteredItems.add(item);
                }
            }
        }
        updateList();
    }

    private void updateList() {
        List<LogItem> visible = new ArrayList<>();
        synchronized (listLock) {
            int end = Math.min(visibleCount, filteredItems.size());
            visible.addAll(filteredItems.subList(0, end));
        }
        logAdapter.setItems(visible);
        updateCountText();
    }

    private void updateCountText() {
        if (tvLogCount == null) return;
        int total;
        synchronized (listLock) {
            total = filteredItems.size();
        }
        int shown = Math.min(visibleCount, total);
        tvLogCount.setText(getString(R.string.h_a1a93bdb) + shown + getString(R.string.h_071f6f15) + total + " 条");
        if (btnLoadMore != null) {
            btnLoadMore.setEnabled(total > visibleCount);
        }
    }

    // ==================== 操作：清空/复制/导出/分享 ====================

    private void confirmClearLogs() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.h_a15a9ef1))
                .setMessage(getString(R.string.h_8cff227d) + currentTabName() + getString(R.string.h_2513de9f))
                .setPositiveButton(getString(R.string.h_288f0c40), (dialog, which) -> clearLogs())
                .setNegativeButton(getString(R.string.h_625fb26b), null)
                .show();
    }

    private String currentTabName() {
        switch (currentTab) {
            case TAB_APP: return "应用日志";
            case TAB_AI: return "AI服务日志";
            case TAB_CRASH: return "崩溃日志";
            case TAB_OP: return "操作记录";
            case TAB_ENGINE: return "引擎日志";
            default: return "全部日志";
        }
    }

    private void clearLogs() {
        showToast(getString(R.string.h_503686fc));
        new Thread(() -> {
            boolean ok = false;
            try {
                switch (currentTab) {
                    case TAB_ALL:
                        AppLogger.clearAppLogs();
                        AppLogger.clearCrashLogs();
                        AILogger.clearLogs();
                        AppDatabase.getDatabase(this).logEntryDao().deleteAll();
                        ok = true;
                        break;
                    case TAB_APP:
                        ok = AppLogger.clearAppLogs();
                        break;
                    case TAB_AI:
                        ok = AILogger.clearLogs();
                        break;
                    case TAB_CRASH:
                        ok = AppLogger.clearCrashLogs();
                        break;
                    case TAB_OP:
                        AppDatabase.getDatabase(this).logEntryDao().deleteAll();
                        ok = true;
                        break;
                    case TAB_ENGINE:
                        // 引擎日志只在进程内存环里，drain 即清空；这里标记成功即可
                        ok = true;
                        break;
                }
            } catch (Exception e) {
                ok = false;
            }
            final boolean finalOk = ok;
            runOnUiThread(() -> {
                if (destroyed) return;
                showToast(finalOk ? currentTabName() + getString(R.string.h_3683077f) : getString(R.string.h_288f0c40) + currentTabName() + getString(R.string.h_acd5cb84));
                loadData();
            });
        }).start();
    }

    private void copyLogs() {
        List<LogItem> items = snapshotFiltered();
        if (items.isEmpty()) {
            showToast(getString(R.string.h_04a8a976));
            return;
        }
        new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                for (LogItem item : items) {
                    sb.append(formatItem(item)).append("\n");
                }
                final String text = sb.toString();
                runOnUiThread(() -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("日志", text);
                    clipboard.setPrimaryClip(clip);
                    showToast(getString(R.string.h_7babe509));
                });
            } catch (Exception e) {
                runOnUiThread(() -> showToast(getString(R.string.h_cde5f392) + e.getMessage()));
            }
        }).start();
    }

    private void exportLogs() {
        List<LogItem> items = snapshotFiltered();
        if (items.isEmpty()) {
            showToast(getString(R.string.h_c2d5d466));
            return;
        }
        new Thread(() -> {
            try {
                File exportDir = new File(getExternalFilesDir(null), "exported_logs");
                if (!exportDir.exists() && !exportDir.mkdirs()) {
                    throw new java.io.IOException("创建导出目录失败");
                }
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
                File exportFile = new File(exportDir, "logs_" + sdf.format(new Date()) + ".txt");
                StringBuilder sb = new StringBuilder();
                for (LogItem item : items) {
                    sb.append(formatItem(item)).append("\n");
                }
                try (FileWriter writer = new FileWriter(exportFile)) {
                    writer.write(sb.toString());
                }
                runOnUiThread(() -> showToast(getString(R.string.h_aefe3830) + exportFile.getAbsolutePath()));
            } catch (Exception e) {
                runOnUiThread(() -> showToast(getString(R.string.h_df3a5427) + e.getMessage()));
            }
        }).start();
    }

    private void shareLogs() {
        List<LogItem> items = snapshotFiltered();
        if (items.isEmpty()) {
            showToast(getString(R.string.h_a997993d));
            return;
        }
        new Thread(() -> {
            try {
                File exportDir = new File(getExternalFilesDir(null), "exported_logs");
                if (!exportDir.exists() && !exportDir.mkdirs()) {
                    throw new java.io.IOException("创建导出目录失败");
                }
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
                File exportFile = new File(exportDir, "logs_" + sdf.format(new Date()) + ".txt");
                StringBuilder sb = new StringBuilder();
                for (LogItem item : items) {
                    sb.append(formatItem(item)).append("\n");
                }
                try (FileWriter writer = new FileWriter(exportFile)) {
                    writer.write(sb.toString());
                }
                runOnUiThread(() -> {
                    android.net.Uri fileUri = androidx.core.content.FileProvider.getUriForFile(
                            LogsActivity.this, getPackageName() + ".fileprovider", exportFile);
                    android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, fileUri);
                    shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, "应用日志");
                    shareIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(android.content.Intent.createChooser(shareIntent, "分享日志"));
                });
            } catch (Exception e) {
                runOnUiThread(() -> showToast(getString(R.string.h_74d30689) + e.getMessage()));
            }
        }).start();
    }

    private List<LogItem> snapshotFiltered() {
        synchronized (listLock) {
            return new ArrayList<>(filteredItems);
        }
    }

    private String formatItem(LogItem item) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        return sdf.format(new Date(item.timestamp)) + " | " + item.level + " | [" + item.source + "] "
                + (item.tag == null ? "" : item.tag) + " | " + (item.message == null ? "" : item.message);
    }

    // ==================== 工具方法 ====================

    private static String normalizeLevel(String level) {
        if (level == null) return "I";
        String l = level.trim().toUpperCase(Locale.getDefault());
        if (l.startsWith("V")) return "V";
        if (l.startsWith("D")) return "D";
        if (l.startsWith("W")) return "W";
        if (l.startsWith("E") || l.startsWith("C") || l.startsWith("F")) return "E";
        return "I";
    }

    private static long parseTimestamp(String timestampStr) {
        if (timestampStr == null || timestampStr.isEmpty()) {
            return System.currentTimeMillis();
        }
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
            Date date = sdf.parse(timestampStr);
            return date != null ? date.getTime() : System.currentTimeMillis();
        } catch (ParseException e) {
            return System.currentTimeMillis();
        }
    }

    // ==================== 菜单 ====================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.logs_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_clear_logs) {
            confirmClearLogs();
            return true;
        } else if (id == R.id.action_copy_logs) {
            copyLogs();
            return true;
        } else if (id == R.id.action_export_logs) {
            exportLogs();
            return true;
        } else if (id == R.id.action_share_logs) {
            shareLogs();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        engineLogHandler.removeCallbacks(engineLogPoller);
        destroyed = true;
        AppLogger.removeLogListener(appLogListener);
        AILogger.removeLogListener(aiLogListener);
        super.onDestroy();
    }

    // ==================== 数据模型与适配器 ====================

    private static class LogItem {
        final long timestamp;
        final String level;
        final String tag;
        final String message; // 列表展示：单行摘要
        final String detail;  // 详情弹窗：完整多行内容
        final String source;

        LogItem(long timestamp, String level, String tag, String message, String detail, String source) {
            this.timestamp = timestamp;
            this.level = level;
            this.tag = tag;
            this.message = message;
            this.detail = detail;
            this.source = source;
        }
    }

    private class LogAdapter extends RecyclerView.Adapter<LogAdapter.LogViewHolder> {
        private List<LogItem> items = new ArrayList<>();

        void setItems(List<LogItem> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        @Override
        public LogViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_log_entry, parent, false);
            return new LogViewHolder(view);
        }

        @Override
        public void onBindViewHolder(LogViewHolder holder, int position) {
            try {
                LogItem item = items.get(position);
                if (item == null) return;

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
                holder.tvTimestamp.setText(sdf.format(new Date(item.timestamp)));

                // 来源标签
                holder.tvSource.setText(item.source);
                try {
                    if (SOURCE_CRASH.equals(item.source)) {
                        holder.tvSource.setTextColor(getResources().getColor(R.color.error_color, null));
                    } else if (SOURCE_AI.equals(item.source)) {
                        holder.tvSource.setTextColor(getResources().getColor(R.color.ai_color, null));
                    } else {
                        holder.tvSource.setTextColor(ThemeColors.get(LogsActivity.this, R.color.text_secondary));
                    }
                } catch (Exception ignored) {
                }

                // 级别标签
                holder.tvLevel.setText(item.level);
                try {
                    if ("E".equals(item.level)) {
                        holder.tvLevel.setTextColor(getResources().getColor(R.color.error_color, null));
                        holder.tvLevel.setBackgroundResource(R.drawable.rounded_tag_error);
                    } else if ("W".equals(item.level)) {
                        holder.tvLevel.setTextColor(getResources().getColor(R.color.warning_color, null));
                        holder.tvLevel.setBackgroundResource(R.drawable.rounded_tag_warning);
                    } else {
                        holder.tvLevel.setTextColor(ThemeColors.get(LogsActivity.this, R.color.primary_color));
                        holder.tvLevel.setBackgroundResource(R.drawable.rounded_tag);
                    }
                } catch (Exception ignored) {
                }

                holder.tvAction.setText(item.tag != null ? item.tag : "");
                String detail = item.message != null ? item.message : "";
                holder.tvDetail.setText(detail);

                // 点击查看完整详情
                holder.itemView.setOnClickListener(v -> showDetailDialog(item));
            } catch (Exception ignored) {
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class LogViewHolder extends RecyclerView.ViewHolder {
            TextView tvTimestamp;
            TextView tvSource;
            TextView tvLevel;
            TextView tvAction;
            TextView tvDetail;

            LogViewHolder(View itemView) {
                super(itemView);
                tvTimestamp = itemView.findViewById(R.id.tv_log_timestamp);
                tvSource = itemView.findViewById(R.id.tv_log_source);
                tvLevel = itemView.findViewById(R.id.tv_log_level);
                tvAction = itemView.findViewById(R.id.tv_log_action);
                tvDetail = itemView.findViewById(R.id.tv_log_detail);
            }
        }
    }

    /**
     * 展示单条日志的完整多行详情
     */
    private void showDetailDialog(LogItem item) {
        if (item == null) return;

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        String header = sdf.format(new Date(item.timestamp)) + "  [" + item.source + "]  " + item.level + "  "
                + (item.tag == null ? "" : item.tag);

        ScrollView scrollView = new ScrollView(this);
        TextView textView = new TextView(this);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setTextIsSelectable(true);
        textView.setTextSize(12);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        textView.setPadding(padding, padding, padding, padding);
        String body = (item.detail != null && !item.detail.isEmpty()) ? item.detail
                : (item.message != null ? item.message : "");
        scrollView.addView(textView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        textView.setText(header + "\n\n" + body);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.h_e0d9b6f4))
                .setView(scrollView)
                .setPositiveButton(getString(R.string.h_b15d9127), null)
                .show();
    }
}
