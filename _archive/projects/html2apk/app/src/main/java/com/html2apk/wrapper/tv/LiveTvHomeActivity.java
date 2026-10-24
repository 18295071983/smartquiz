package com.html2apk.wrapper.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 原生直播首页（v9.12）。
 *
 * <p>给"一键直播 APK"用：manifest.json 里配置 {@code live.url} 后，壳启动直接进本页，
 * 不加载 HTML。页面原生完成频道源抓取、频道列表、搜索、收藏、刷新、错误重试；点频道
 * 进入 {@link LivePlayerActivity}，播放器再负责解码/直播窗口自愈/遥控器。</p>
 *
 * <p>如果 {@code live.autoplay=true} 或源里只有一个频道，加载完成后直接进播放器。</p>
 */
public final class LiveTvHomeActivity extends Activity {

    public static final String EXTRA_URL = "live_home_url";
    public static final String EXTRA_TEXT = "live_home_text";
    public static final String EXTRA_BASE = "live_home_base";
    public static final String EXTRA_NAME = "live_home_name";
    public static final String EXTRA_START = "live_home_start";
    public static final String EXTRA_AUTOPLAY = "live_home_autoplay";

    private static final int BG = Color.parseColor("#0B0F14");
    private static final int CARD = Color.parseColor("#151C24");
    private static final int CARD_HI = Color.parseColor("#1C2733");
    private static final int LINE = Color.parseColor("#24313D");
    private static final int TEXT = Color.parseColor("#E8EEF5");
    private static final int MUTED = Color.parseColor("#8FA3B5");
    private static final int ACCENT = Color.parseColor("#3EA6FF");
    private static final int GOLD = Color.parseColor("#FFC94D");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private LivePlaylistRepository repo;
    private LiveStore store;

    private final List<LiveChannel> channels = new ArrayList<>();
    private final Set<String> favoriteIds = new HashSet<>();
    private final List<LiveChannel> shown = new ArrayList<>();
    private final List<Integer> shownIndex = new ArrayList<>();
    private final List<String> groups = new ArrayList<>();
    private String query = "";
    private String selectedGroup = "";
    private boolean onlyFavorites;

    private String sourceUrl = "";
    private String sourceText = "";
    private String sourceBase = "";
    private String sourceName = "";
    private String startChannel = "";
    private boolean loadedOnce;

    private TextView titleView;
    private TextView subtitleView;
    private TextView countView;
    private EditText searchView;
    private TextView favFilterButton;
    private LinearLayout groupBar;
    private ListView listView;
    private LinearLayout statusBox;
    private ProgressBar spinner;
    private TextView statusText;
    private TextView retryButton;
    private TextView emptyView;
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new LiveStore(this);
        repo = new LivePlaylistRepository(this);

        buildUi();
        applyWindow();

        Intent intent = getIntent();
        sourceUrl = intent == null ? "" : safe(intent.getStringExtra(EXTRA_URL));
        sourceText = intent == null ? "" : safe(intent.getStringExtra(EXTRA_TEXT));
        sourceBase = intent == null ? "" : safe(intent.getStringExtra(EXTRA_BASE));
        sourceName = intent == null ? "" : safe(intent.getStringExtra(EXTRA_NAME));
        startChannel = intent == null ? "" : safe(intent.getStringExtra(EXTRA_START));
        boolean autoplayRequested = intent != null && intent.getBooleanExtra(EXTRA_AUTOPLAY, false);

        if (!sourceText.isEmpty()) {
            android.util.Log.i("LiveTvHome", "onCreate text mode, len=" + sourceText.length()
                    + ", start=" + startChannel + ", autoplay=" + autoplayRequested);
            loadText(sourceText, sourceBase, sourceName, startChannel, autoplayRequested);
        } else if (sourceUrl.isEmpty()) {
            android.util.Log.w("LiveTvHome", "onCreate no source");
            showError("还没有配置频道源");
        } else {
            android.util.Log.i("LiveTvHome", "onCreate url=" + sourceUrl
                    + ", start=" + startChannel + ", autoplay=" + autoplayRequested);
            loadSource(sourceUrl, sourceName, startChannel, autoplayRequested);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    // ==================== 加载 ====================

    private void loadSource(String url, String name, String start, boolean autoplay) {
        final String u = safe(url);
        if (!LivePlaylistRepository.isHttpUrl(u)) {
            showError("频道源必须是 http:// 或 https:// 地址");
            return;
        }
        sourceUrl = u;
        sourceText = "";
        sourceBase = "";
        sourceName = safe(name).isEmpty() ? deriveSourceName(u) : name.trim();
        startChannel = safe(start);
        titleView.setText(sourceName);

        showLoading();
        executor.execute(() -> {
            final LivePlaylistRepository.LoadResult r = repo.load(u, sourceName);
            handler.post(() -> handleResult(r, startChannel, autoplay, u, false));
        });
    }

    private void loadText(String text, String baseUrl, String name, String start, boolean autoplay) {
        sourceText = text;
        sourceBase = safe(baseUrl);
        sourceUrl = "";
        sourceName = safe(name).isEmpty() ? "直播源" : name.trim();
        startChannel = safe(start);
        titleView.setText(sourceName);

        showLoading();
        executor.execute(() -> {
            final LivePlaylistRepository.LoadResult r = repo.parse(text, sourceBase, sourceName);
            handler.post(() -> handleResult(r, startChannel, autoplay, "", true));
        });
    }

    private void handleResult(LivePlaylistRepository.LoadResult r, String start,
                              boolean autoplay, String sourceForCount, boolean textMode) {
        if (isFinishing() || isDestroyed()) return;
        if (!r.ok()) {
            android.util.Log.w("LiveTvHome", "load failed: " + r.error);
            showError(r.error == null ? "频道源加载失败" : r.error);
            return;
        }
        android.util.Log.i("LiveTvHome", "load ok: channels=" + r.channels.size()
                + ", cached=" + r.cached + ", autoplay=" + autoplay);
        channels.clear();
        channels.addAll(r.channels);
        loadedOnce = true;
        if (!textMode && !sourceForCount.isEmpty()) {
            store.updateSourceCount(sourceForCount, channels.size());
        }
        reloadFavorites();
        updateGroups();
        rebuild();
        showList();
        countView.setText("共 " + channels.size() + " 个频道"
                + (r.cached ? " · 缓存" : "")
                + (r.error != null ? " · 已离线" : ""));
        subtitleView.setText("共 " + channels.size() + " 个频道 · 点击频道播放"
                + (r.cached ? " · 缓存" : ""));
        if (autoplay || channels.size() == 1) {
            openPlayer(findIndex(start));
        }
    }

    private void reloadFavorites() {
        favoriteIds.clear();
        for (LiveChannel c : store.loadFavorites()) favoriteIds.add(c.id);
    }

    /** 重建分组 chips（全部 + 源里出现的分组，保持出现顺序） */
    private void updateGroups() {
        groups.clear();
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (LiveChannel c : channels) {
            String g = c.group == null ? "" : c.group.trim();
            if (!g.isEmpty()) set.add(g);
        }
        groups.addAll(set);
        if (!selectedGroup.isEmpty() && !groups.contains(selectedGroup)) {
            selectedGroup = "";
        }
        rebuildGroupChips();
    }

    private void rebuildGroupChips() {
        if (groupBar == null) return;
        groupBar.removeAllViews();
        addGroupChip("全部", "");
        for (String g : groups) addGroupChip(g, g);
    }

    private void addGroupChip(String label, String group) {
        boolean active = selectedGroup.equals(group);
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chip.setTextColor(active ? Color.parseColor("#06121F") : TEXT);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), dp(6), dp(14), dp(6));
        chip.setBackground(rounded(active ? ACCENT : CARD, active ? ACCENT : LINE, 20));
        chip.setClickable(true);
        chip.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            selectedGroup = group;
            rebuildGroupChips();
            rebuild();
        });
        groupBar.addView(chip);
    }

    // ==================== 频道列表 ====================

    private void rebuild() {
        shown.clear();
        shownIndex.clear();
        String q = safe(query).toLowerCase(Locale.US);
        for (int i = 0; i < channels.size(); i++) {
            LiveChannel c = channels.get(i);
            if (onlyFavorites && !favoriteIds.contains(c.id)) continue;
            if (!selectedGroup.isEmpty() && !selectedGroup.equals(c.group)) continue;
            if (!q.isEmpty()) {
                String hay = (c.name + " " + c.group).toLowerCase(Locale.US);
                if (!hay.contains(q)) continue;
            }
            shown.add(c);
            shownIndex.add(i);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        emptyView.setVisibility(shown.isEmpty() && loadedOnce ? View.VISIBLE : View.GONE);
        listView.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showList() {
        statusBox.setVisibility(View.GONE);
        emptyView.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        listView.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showLoading() {
        statusBox.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.VISIBLE);
        statusText.setText("正在加载频道源…");
        statusText.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);
        listView.setVisibility(View.GONE);
        if (subtitleView != null) subtitleView.setText("正在加载频道源…");
    }

    private void showError(String message) {
        statusBox.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.GONE);
        statusText.setText(message == null ? "加载失败" : message);
        statusText.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.VISIBLE);
        retryButton.setText((sourceUrl.isEmpty() && sourceText.isEmpty()) ? "输入频道源" : "重试");
        emptyView.setVisibility(View.GONE);
        listView.setVisibility(View.GONE);
        if (subtitleView != null) subtitleView.setText("源加载失败");
    }

    private void openPlayer(int index) {
        if (channels.isEmpty()) {
            Toast.makeText(this, "没有可播放的频道", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!LiveSessionLauncher.launch(this, channels, index, sourceName)) {
            Toast.makeText(this, "启动播放器失败", Toast.LENGTH_SHORT).show();
        }
    }

    private int findIndex(String wanted) {
        if (wanted == null || wanted.trim().isEmpty()) return 0;
        String w = wanted.trim();
        for (int i = 0; i < channels.size(); i++) {
            LiveChannel c = channels.get(i);
            if (c.id.equals(w) || c.name.equalsIgnoreCase(w)) return i;
        }
        String lower = w.toLowerCase(Locale.US);
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).name.toLowerCase(Locale.US).contains(lower)) return i;
        }
        return 0;
    }

    private void toggleFavorite(LiveChannel c) {
        if (c == null) return;
        boolean fav = store.toggleFavorite(c);
        if (fav) favoriteIds.add(c.id);
        else favoriteIds.remove(c.id);
        if (adapter != null) adapter.notifyDataSetChanged();
        if (onlyFavorites) rebuild();
    }

    private void askForSource() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://example.com/live.m3u");
        input.setText(sourceUrl);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("频道源")
                .setView(box)
                .setPositiveButton("加载", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String u = input.getText() == null ? "" : input.getText().toString().trim();
            if (!LivePlaylistRepository.isHttpUrl(u)) {
                Toast.makeText(this, "请输入 http/https 地址", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            loadSource(u, sourceName, startChannel, false);
        }));
        dialog.show();
    }

    // ==================== 界面 ====================

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#0B0F14"), Color.parseColor("#101A24")}));

        // 顶栏
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(18), dp(16), dp(12), dp(10));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(this);
        titleView.setText("电视直播");
        titleView.setTextColor(TEXT);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleCol.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        subtitleView = new TextView(this);
        subtitleView.setText("正在加载频道…");
        subtitleView.setTextColor(MUTED);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        subtitleView.setSingleLine(true);
        subtitleView.setPadding(0, dp(2), 0, 0);
        titleCol.addView(subtitleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        top.addView(titleCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView sourceBtn = makeButton("换源");
        sourceBtn.setOnClickListener(v -> askForSource());
        top.addView(sourceBtn);

        TextView refresh = makeButton("刷新");
        refresh.setOnClickListener(v -> {
            if (!sourceText.isEmpty()) {
                loadText(sourceText, sourceBase, sourceName, startChannel, false);
            } else if (sourceUrl.isEmpty()) {
                askForSource();
            } else {
                loadSource(sourceUrl, sourceName, startChannel, false);
            }
        });
        top.addView(refresh);

        TextView close = makeButton("关闭");
        close.setOnClickListener(v -> finish());
        top.addView(close);
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 搜索 + 收藏筛选
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(14), dp(2), dp(14), dp(8));

        searchView = new EditText(this);
        searchView.setSingleLine(true);
        searchView.setHint("搜索频道 / 分组");
        searchView.setTextColor(TEXT);
        searchView.setHintTextColor(MUTED);
        searchView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        searchView.setBackground(rounded(CARD, LINE, 12));
        searchView.setPadding(dp(14), dp(9), dp(14), dp(9));
        searchView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString();
                rebuild();
            }
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        searchLp.setMargins(0, 0, dp(8), 0);
        searchRow.addView(searchView, searchLp);

        favFilterButton = makeButton("收藏");
        favFilterButton.setOnClickListener(v -> {
            onlyFavorites = !onlyFavorites;
            favFilterButton.setText(onlyFavorites ? "全部" : "收藏");
            favFilterButton.setTextColor(onlyFavorites ? GOLD : TEXT);
            rebuild();
        });
        searchRow.addView(favFilterButton);
        root.addView(searchRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 分组 chips（全部 / 央视 / 卫视 / 地方…）
        android.widget.HorizontalScrollView groupScroll = new android.widget.HorizontalScrollView(this);
        groupScroll.setHorizontalScrollBarEnabled(false);
        groupScroll.setPadding(dp(14), 0, dp(14), dp(8));
        groupBar = new LinearLayout(this);
        groupBar.setOrientation(LinearLayout.HORIZONTAL);
        groupScroll.addView(groupBar, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(groupScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 中间：列表 + 状态
        FrameLayout center = new FrameLayout(this);
        listView = new ListView(this);
        listView.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        listView.setDividerHeight(dp(8));
        listView.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        listView.setBackgroundColor(Color.TRANSPARENT);
        listView.setPadding(dp(10), dp(2), dp(10), 0);
        listView.setClipToPadding(false);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int p) { return shown.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View convertView, ViewGroup parent) {
                return channelRow(shown.get(p), shownIndex.get(p), convertView);
            }
        };
        listView.setAdapter(adapter);
        listView.setItemsCanFocus(false);
        // 行本身处理点击（行内收藏按钮是 clickable 子 View，会让 ListView 的 item click 失效）
        listView.setOnItemClickListener(null);
        center.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = new TextView(this);
        emptyView.setText("没有匹配的频道");
        emptyView.setTextColor(MUTED);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        center.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setGravity(Gravity.CENTER);
        statusBox.setPadding(dp(28), dp(26), dp(28), dp(26));
        statusBox.setBackground(rounded(CARD, LINE, 18));
        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        statusBox.addView(spinner, new LinearLayout.LayoutParams(dp(42), dp(42)));

        statusText = new TextView(this);
        statusText.setTextColor(TEXT);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusText.setGravity(Gravity.CENTER);
        statusText.setMaxWidth(dp(360));
        statusText.setPadding(0, dp(12), 0, dp(12));
        statusBox.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        retryButton = makeButton("重试");
        retryButton.setPadding(dp(28), dp(9), dp(28), dp(9));
        retryButton.setOnClickListener(v -> {
            if (!sourceText.isEmpty()) {
                loadText(sourceText, sourceBase, sourceName, startChannel, false);
            } else if (sourceUrl.isEmpty()) {
                askForSource();
            } else {
                loadSource(sourceUrl, sourceName, startChannel, false);
            }
        });
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryLp.gravity = Gravity.CENTER_HORIZONTAL;
        statusBox.addView(retryButton, retryLp);
        center.addView(statusBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        root.addView(center, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        countView = new TextView(this);
        countView.setTextColor(MUTED);
        countView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        countView.setPadding(dp(16), dp(6), dp(16), dp(12));
        root.addView(countView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        applyInsets(root);
    }

    private View channelRow(LiveChannel c, int originalIndex, View convertView) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(11), dp(10), dp(11));
        row.setMinimumHeight(dp(66));
        StateListDrawable rowBg = new StateListDrawable();
        rowBg.addState(new int[]{android.R.attr.state_focused},
                rounded(CARD_HI, ACCENT, 14));
        rowBg.addState(new int[]{}, rounded(CARD, LINE, 14));
        row.setBackground(rowBg);
        row.setClickable(true);
        row.setFocusable(true);
        row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        row.setOnClickListener(v -> openPlayer(originalIndex));

        ChannelLogoView logo = new ChannelLogoView(this, 46);
        logo.bind(c);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(46), dp(46));
        logoLp.setMargins(0, 0, dp(12), 0);
        row.addView(logo, logoLp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(c.name);
        name.setTextColor(TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView meta = new TextView(this);
        String g = c.group.isEmpty() ? "未分组" : c.group;
        meta.setText(g + "  ·  " + (originalIndex + 1) + " / " + channels.size());
        meta.setTextColor(MUTED);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        meta.setPadding(0, dp(2), 0, 0);
        text.addView(meta, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean fav = favoriteIds.contains(c.id);
        TextView star = makeButton(fav ? "已藏" : "收藏");
        star.setTextColor(fav ? GOLD : MUTED);
        star.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        star.setFocusable(false);
        star.setOnClickListener(v -> toggleFavorite(c));
        row.addView(star);
        return row;
    }

    private TextView makeButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(TEXT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(11), dp(7), dp(11), dp(7));
        v.setClickable(true);
        v.setFocusable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused},
                rounded(Color.argb(220, 255, 255, 255), Color.WHITE, 8));
        bg.addState(new int[]{}, rounded(CARD, LINE, 8));
        v.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), 0, dp(3), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private void applyWindow() {
        Window w = getWindow();
        if (w == null) return;
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                // 深色底 → 浅色图标（去掉 LIGHT_* 外观）
                c.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(0);
        }
    }

    private void applyInsets(View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        } else {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets;
            });
        }
        root.requestApplyInsets();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String deriveSourceName(String url) {
        String s = safe(url);
        int cut = s.indexOf('?');
        if (cut >= 0) s = s.substring(0, cut);
        cut = s.lastIndexOf('/');
        if (cut >= 0 && cut + 1 < s.length()) s = s.substring(cut + 1);
        cut = s.lastIndexOf('.');
        if (cut > 0) s = s.substring(0, cut);
        if (s.isEmpty()) return "直播源";
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
