package com.oilquiz.app.ui.activity;

import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
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
import com.oilquiz.app.ai.agent.online.mcp.McpChinaCatalog;
import com.oilquiz.app.ai.agent.online.mcp.McpManager;
import com.oilquiz.app.ai.agent.online.mcp.McpServerConfig;
import com.oilquiz.app.theme.ThemeColors;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MCP 市场页（国内精选）。
 *
 * <p>数据源为内置国内精选清单（{@link McpChinaCatalog}，阿里云 / 腾讯官方 MCP，2026-11 快照）。
 * 每个条目：可直连（URL_READY）一键添加，需开通（GUIDE_ONLY）提供开通指引与端点复制。
 * 添加走 {@link McpManager#upsertServer}，与管理页共用配置。</p>
 */
public class McpMarketActivity extends AppCompatActivity {

    private LinearLayout listContainer;
    private String query = "";
    private Set<String> addedIds = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(wrapInScrollView(buildPage()));
        renderList();
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

        // 顶部：返回 + 标题
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
        title.setText("MCP 市场 · 国内精选");
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.text_primary));
        title.setPadding(dp(10), 0, 0, 0);
        topRow.addView(title);
        page.addView(topRow);

        TextView desc = new TextView(this);
        desc.setText("来源：阿里云 / 腾讯 / 魔搭（ModelScope）官方 MCP 文档与广场（2026-11）。\n"
                + "· 「可直连」→ 直接「＋ 添加」，再到管理页「🔗 密钥」引用对应服务商密钥\n"
                + "· 「需开通」→ 看「💡 开通指引」拿专属端点/密钥后添加");
        desc.setTextSize(12);
        desc.setTextColor(color(R.color.text_secondary));
        desc.setPadding(0, dp(6), 0, dp(8));
        page.addView(desc);

        // 搜索框
        EditText searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("🔍 搜索国内服务（名称 / 描述）");
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
                query = s.toString().trim().toLowerCase();
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

    private void refreshAddedIds() {
        addedIds = new HashSet<>();
        for (McpServerConfig cfg : McpManager.getInstance(this).getServers()) {
            addedIds.add(cfg.id);
        }
    }

    private void renderList() {
        refreshAddedIds();
        listContainer.removeAllViews();

        List<McpChinaCatalog.ChinaEntry> shown = new ArrayList<>();
        for (McpChinaCatalog.ChinaEntry e : McpChinaCatalog.entries()) {
            if (matches(e)) shown.add(e);
        }
        if (shown.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("没有匹配「" + query + "」的国内服务");
            tv.setTextSize(13);
            tv.setTextColor(color(R.color.text_tertiary));
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(24), 0, 0);
            listContainer.addView(tv);
            return;
        }
        for (McpChinaCatalog.ChinaEntry e : shown) {
            listContainer.addView(buildCard(e));
        }
    }

    private boolean matches(McpChinaCatalog.ChinaEntry e) {
        if (query.isEmpty()) return true;
        return e.title.toLowerCase().contains(query)
                || (e.description != null && e.description.toLowerCase().contains(query))
                || e.id.contains(query);
    }

    private View buildCard(final McpChinaCatalog.ChinaEntry e) {
        final boolean alreadyAdded = addedIds.contains(e.id);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(8));
        card.setBackground(cardBackground());
        card.setLayoutParams(cardLp);

        // 行1：标题 + 类型徽标
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleTv = new TextView(this);
        titleTv.setText(e.title);
        titleTv.setTextSize(14);
        titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleTv.setTextColor(color(R.color.text_primary));
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(titleTv);

        TextView kindBadge = new TextView(this);
        kindBadge.setText(e.kind == McpChinaCatalog.Kind.URL_READY ? "可直连" : "需开通");
        kindBadge.setTextSize(10);
        kindBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
        kindBadge.setTextColor(e.kind == McpChinaCatalog.Kind.URL_READY
                ? color(R.color.primary) : color(R.color.error));
        kindBadge.setBackground(pillBackground(e.kind == McpChinaCatalog.Kind.URL_READY
                ? R.color.primary_container : R.color.error_container));
        titleRow.addView(kindBadge);
        card.addView(titleRow);

        // 行2：端点格式
        TextView endpointTv = new TextView(this);
        endpointTv.setText(e.endpointText);
        endpointTv.setTextSize(11);
        endpointTv.setTextColor(color(R.color.text_tertiary));
        endpointTv.setSingleLine(true);
        endpointTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        endpointTv.setPadding(0, dp(2), 0, 0);
        card.addView(endpointTv);

        // 行3：描述
        TextView descTv = new TextView(this);
        descTv.setText(e.description);
        descTv.setTextSize(12);
        descTv.setTextColor(color(R.color.text_secondary));
        descTv.setMaxLines(2);
        descTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        descTv.setPadding(0, dp(4), 0, 0);
        card.addView(descTv);

        // 行4：鉴权说明
        TextView authTv = new TextView(this);
        authTv.setText("鉴权: " + e.authNote);
        authTv.setTextSize(11);
        authTv.setTextColor(color(R.color.text_secondary));
        authTv.setSingleLine(true);
        authTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        authTv.setPadding(0, dp(3), 0, 0);
        card.addView(authTv);

        // 行5：操作按钮
        LinearLayout opsRow = new LinearLayout(this);
        opsRow.setOrientation(LinearLayout.HORIZONTAL);
        opsRow.setGravity(Gravity.CENTER_VERTICAL);
        opsRow.setPadding(0, dp(8), 0, 0);

        TextView copyBtn = makeOpButton("📋 复制端点", R.color.secondary_container);
        copyBtn.setOnClickListener(v -> copyText(e.title, e.endpointText));
        opsRow.addView(copyBtn);

        TextView guideBtn = makeOpButton("💡 开通指引", R.color.secondary_container);
        LinearLayout.LayoutParams guideLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        guideLp.setMargins(dp(6), 0, 0, 0);
        opsRow.addView(guideBtn, guideLp);
        guideBtn.setOnClickListener(v -> showGuide(e));

        if (e.kind == McpChinaCatalog.Kind.URL_READY) {
            TextView addBtn = makeOpButton(alreadyAdded ? "✓ 已添加" : "＋ 添加",
                    alreadyAdded ? R.color.surface_variant : R.color.primary_container);
            LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            addLp.setMargins(dp(6), 0, 0, 0);
            addBtn.setEnabled(!alreadyAdded);
            opsRow.addView(addBtn, addLp);
            addBtn.setOnClickListener(v -> confirmAddChina(e));
        }

        LinearLayout.LayoutParams opsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.addView(opsRow, opsLp);
        return card;
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

    private void copyText(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
            Toast.makeText(this, "端点已复制到剪贴板", Toast.LENGTH_SHORT).show();
        }
    }

    private void showGuide(McpChinaCatalog.ChinaEntry e) {
        new AlertDialog.Builder(this)
                .setTitle(e.title + " · 开通指引")
                .setMessage(e.guide + "\n\n端点: " + e.endpointText + "\n鉴权: " + e.authNote)
                .setPositiveButton("复制端点", (d, w) -> copyText(e.title, e.endpointText))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void confirmAddChina(final McpChinaCatalog.ChinaEntry e) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), 0);

        EditText endpointInput = new EditText(this);
        endpointInput.setSingleLine(true);
        endpointInput.setHint("端点 URL");
        endpointInput.setText(e.endpointText);
        form.addView(endpointInput);

        TextView note = new TextView(this);
        note.setText("端点里的 {mcpCode} / 你的Key 等占位符必须替换成真实值；"
                + "也可先添加，之后到管理页「✏ 编辑」修改。");
        note.setTextSize(12);
        note.setTextColor(color(R.color.text_tertiary));
        note.setPadding(0, dp(6), 0, 0);
        form.addView(note);

        new AlertDialog.Builder(this)
                .setTitle("添加并启用 " + e.title + "？")
                .setMessage("传输: " + (e.transport == McpServerConfig.Transport.SSE
                                ? "SSE" : "Streamable HTTP")
                        + "\n鉴权: " + e.authNote)
                .setView(form)
                .setPositiveButton("添加", (d, w) -> {
                    String url = endpointInput.getText().toString().trim();
                    if (!McpServerConfig.isUsableEndpoint(url)) {
                        Toast.makeText(this, "端点需为 http(s) 且不能保留 {…}/你的Key 等占位符",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    boolean builtin = false;
                    for (McpServerConfig c : McpManager.getInstance(this).getServers()) {
                        if (c.id.equals(e.id)) builtin = c.builtin;
                    }
                    McpServerConfig cfg = new McpServerConfig(e.id, e.title, url,
                            e.transport != null ? e.transport
                                    : McpServerConfig.Transport.STREAMABLE_HTTP,
                            "", true, builtin);
                    McpManager.getInstance(this).upsertServer(cfg);
                    Toast.makeText(this, "已添加并启用，密钥到管理页「🔗 密钥」引用后再同步",
                            Toast.LENGTH_LONG).show();
                    renderList();
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
