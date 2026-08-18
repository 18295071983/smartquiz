package com.oilquiz.app.ai.agent.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.agent.online.AgentWorkspace;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 工作区管理视图：查看/删除 Agent 产生的文件。
 * 数据源：AgentWorkspace（filesDir/agent_workspace/）。支持深浅色主题。
 */
public class AgentWorkspaceView {

    private final Context context;
    private LinearLayout listContainer;

    public AgentWorkspaceView(Context context) {
        this.context = context;
    }

    public View build(ViewGroup root) {
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), dp(16));
        page.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(context);
        title.setText("📁 Agent 工作区");
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.text_primary));
        page.addView(title);

        TextView desc = new TextView(context);
        AgentWorkspace ws = AgentWorkspace.getInstance(context);
        desc.setText("Agent 生成的文件存放于此（图片/导出等）。路径: " + ws.getWorkspacePath());
        desc.setTextSize(11);
        desc.setTextColor(color(R.color.text_secondary));
        desc.setPadding(0, dp(4), 0, dp(12));
        page.addView(desc);

        // 清空按钮
        TextView clearBtn = new TextView(context);
        clearBtn.setText("🗑 清空工作区");
        clearBtn.setTextSize(13);
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setTextColor(color(R.color.error));
        clearBtn.setBackground(buttonBackground(R.color.error_container));
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        clearLp.setMargins(0, 0, 0, dp(10));
        page.addView(clearBtn, clearLp);

        clearBtn.setOnClickListener(v -> {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle("清空工作区")
                .setMessage("将删除工作区内所有文件。确定继续吗？")
                .setPositiveButton("清空", (dialog, which) -> {
                    int removed = 0;
                    for (AgentWorkspace.WorkspaceFile f : ws.listFiles()) {
                        if (ws.deleteFile(f.name)) removed++;
                    }
                    Toast.makeText(context, "已清空工作区，删除 " + removed + " 个文件", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
        });

        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(listContainer);

        refresh();
        return page;
    }

    private void refresh() {
        listContainer.removeAllViews();
        AgentWorkspace ws = AgentWorkspace.getInstance(context);
        List<AgentWorkspace.WorkspaceFile> files = ws.listFiles();

        if (files.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("工作区为空。\nAgent 生成图片/文件后会存放在这里。");
            empty.setTextSize(13);
            empty.setTextColor(color(R.color.text_tertiary));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, 0);
            listContainer.addView(empty);
            return;
        }

        for (AgentWorkspace.WorkspaceFile f : files) {
            listContainer.addView(createFileItem(f));
        }
    }

    /** 单文件项：名称 + 大小 + 时间 + 删除 */
    private View createFileItem(AgentWorkspace.WorkspaceFile f) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(8), dp(8));
        row.setBackground(cardBackground());
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, dp(6));
        row.setLayoutParams(rowLp);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView nameTv = new TextView(context);
        nameTv.setText("📄 " + f.name);
        nameTv.setTextSize(13);
        nameTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        nameTv.setTextColor(color(R.color.text_primary));
        textCol.addView(nameTv);

        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        TextView metaTv = new TextView(context);
        metaTv.setText(formatSize(f.size) + " · " + sdf.format(new Date(f.lastModified)));
        metaTv.setTextSize(11);
        metaTv.setTextColor(color(R.color.text_secondary));
        metaTv.setPadding(0, dp(2), 0, 0);
        textCol.addView(metaTv);

        row.addView(textCol);

        TextView delBtn = new TextView(context);
        delBtn.setText("删除");
        delBtn.setTextSize(12);
        delBtn.setGravity(Gravity.CENTER);
        delBtn.setTextColor(color(R.color.error));
        delBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        delBtn.setOnClickListener(v -> {
            AgentWorkspace.getInstance(context).deleteFile(f.name);
            Toast.makeText(context, "已删除: " + f.name, Toast.LENGTH_SHORT).show();
            refresh();
        });
        row.addView(delBtn);
        return row;
    }

    private static String formatSize(long size) {
        if (size < 1024) return size + "B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1fKB", size / 1024.0);
        return String.format(Locale.US, "%.1fMB", size / 1024.0 / 1024.0);
    }

    private int color(int resId) {
        return context.getColor(resId);
    }

    private android.graphics.drawable.Drawable cardBackground() {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color(R.color.surface_variant));
        gd.setCornerRadius(dp(8));
        gd.setStroke(dp(1), color(R.color.outline));
        return gd;
    }

    private android.graphics.drawable.Drawable buttonBackground(int colorResId) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color(colorResId));
        gd.setCornerRadius(dp(6));
        return gd;
    }

    private int dp(float value) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
