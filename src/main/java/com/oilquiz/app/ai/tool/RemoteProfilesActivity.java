package com.oilquiz.app.ai.tool;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 连接管理（2026-11-14）。
 *
 * <p>独立页面，**整屏代码构建**（和 RemoteDeckListActivity 同一套做法）——不做任何
 * "往别的页面里插 View"，所以不存在找不到容器、插错父容器的问题。</p>
 *
 * <p>数据来自 {@link RemoteProfiles}；首次进入时如果列表为空，用当前正在用的
 * 配置播种一条，保证页面上永远有东西。</p>
 */
public class RemoteProfilesActivity extends Activity {

    private LinearLayout list;

    public static void open(Context ctx) {
        Intent it = new Intent(ctx, RemoteProfilesActivity.class);
        if (!(ctx instanceof Activity)) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(it);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF7F7FA);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        // 顶部：返回
        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextSize(15);
        back.setTextColor(0xFF2F5BD6);
        back.setPadding(0, 0, 0, dp(8));
        back.setOnClickListener(v -> finish());
        root.addView(back);

        TextView title = new TextView(this);
        title.setText("连接管理");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF1A1A1A);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("点一条切换过去，长按删除。家里走局域网、出门走远程，各存一条即可。");
        hint.setTextSize(12.5f);
        hint.setTextColor(0xFF7A7A7A);
        hint.setPadding(0, dp(4), 0, dp(12));
        root.addView(hint);

        // 静态度量：一个可滚动的列表容器（内容在 render() 里填）
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部：保存当前配置为一条
        TextView saveBtn = new TextView(this);
        saveBtn.setText("＋ 把当前连接存为一条");
        saveBtn.setTextSize(14);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setTextColor(0xFFFFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0xFF2F5BD6);
        saveBtn.setBackground(bg);
        saveBtn.setPadding(0, dp(12), 0, dp(12));
        saveBtn.setOnClickListener(v -> {
            String url = new RemoteDshTool(this).getBaseUrl();
            String token = new RemoteDshTool(this).getToken();
            if (url == null || url.trim().isEmpty()) {
                Toast.makeText(this, "当前还没有配置好的连接", Toast.LENGTH_SHORT).show();
                return;
            }
            RemoteProfiles.save(this, url.trim(), token, "");
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            render();
        });
        root.addView(saveBtn);

        setContentView(root);
        setTitle("连接管理");
        render();
    }

    private void render() {
        if (list == null) return;
        list.removeAllViews();
        String current = safeUrl();
        List<RemoteProfiles.Profile> all = RemoteProfiles.list(this);
        // 首次进入：用当前连接播种，保证页面上有东西
        if (all.isEmpty() && !current.isEmpty()) {
            RemoteProfiles.save(this, current, safeToken(), "当前连接");
            all = RemoteProfiles.list(this);
        }
        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有保存的连接。先在上一页配好地址和令牌，再回来点底部按钮存一条。");
            empty.setTextSize(13);
            empty.setTextColor(0xFF8A8A8A);
            empty.setPadding(dp(4), dp(12), dp(4), dp(12));
            list.addView(empty);
            return;
        }
        for (RemoteProfiles.Profile p : all) list.addView(row(p, p.url.equalsIgnoreCase(current)));
    }

    private View row(final RemoteProfiles.Profile p, boolean isCurrent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(isCurrent ? 0x142F5BD6 : 0x14000000);
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText((isCurrent ? "● " : "") + p.label());
        name.setTextSize(15);
        name.setTextColor(0xFF1F1F1F);
        if (isCurrent) name.setTypeface(null, Typeface.BOLD);
        col.addView(name);
        TextView url = new TextView(this);
        url.setText(p.url);
        url.setTextSize(12);
        url.setTextColor(0xFF7A7A7A);
        url.setPadding(0, dp(2), 0, 0);
        col.addView(url);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView use = new TextView(this);
        use.setText(isCurrent ? "当前" : "切换");
        use.setTextSize(13);
        use.setTextColor(isCurrent ? 0xFF8A8A8A : 0xFF2F5BD6);
        use.setPadding(dp(10), dp(6), dp(10), dp(6));
        if (!isCurrent) {
            use.setOnClickListener(v -> {
                String tc = safeToken().isEmpty() ? p.token : p.token;
                RemoteDshTool.saveManualConfig(this, p.url, tc);
                Toast.makeText(this, "已切到 " + p.label(), Toast.LENGTH_SHORT).show();
                finish();
            });
        }
        row.addView(use);
        // 长按删除（2026-11-14）：当前正在用的那条也允许删，删完列表为空会重新播种
        row.setOnLongClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("删除这条连接？")
                    .setMessage(p.url)
                    .setPositiveButton("删除", (d, w) -> {
                        RemoteProfiles.remove(this, p.url);
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                        render();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });
        return row;
    }

    private String safeUrl() {
        try {
            String u = new RemoteDshTool(this).getBaseUrl();
            return u == null ? "" : u.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private String safeToken() {
        try {
            String t = new RemoteDshTool(this).getToken();
            return t == null ? "" : t;
        } catch (Throwable th) {
            return "";
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
