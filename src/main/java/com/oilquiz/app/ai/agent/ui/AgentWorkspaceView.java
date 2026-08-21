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

        // 标题行：标题 + 刷新按钮（Agent 后台新增文件后可手动刷新）
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText("📁 Agent 工作区");
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.text_primary));
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView refreshBtn = new TextView(context);
        refreshBtn.setText("🔄 刷新");
        refreshBtn.setTextSize(12);
        refreshBtn.setGravity(Gravity.CENTER);
        refreshBtn.setTextColor(color(R.color.text_secondary));
        refreshBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        refreshBtn.setOnClickListener(v -> refresh());
        titleRow.addView(refreshBtn);
        page.addView(titleRow);

        TextView desc = new TextView(context);
        AgentWorkspace ws = AgentWorkspace.getInstance(context);
        boolean isPublic = ws.isPublicWorkspace();
        desc.setText("📁 长期文件区(files/) 保留用户产物\n⚙️ 临时执行区(tmp/) 任务结束自动清理，不跨任务保留\n"
                + "位置: " + (isPublic ? "🌐 公共目录(Download/OilQuiz，所有App可见)" : "🔒 应用私有目录")
                + "\n路径: " + ws.getWorkspacePath());
        desc.setTextSize(11);
        desc.setTextColor(color(R.color.text_secondary));
        desc.setPadding(0, dp(4), 0, dp(4));
        page.addView(desc);

        // 未授权公共目录时：提示 + 一键跳转授权（授予后工作区自动切换公共目录并迁移旧文件）
        if (!isPublic) {
            TextView permBtn = new TextView(context);
            permBtn.setText("🔓 授予文件访问权限（工作区切换到公共目录，文件对所有App可见）");
            permBtn.setTextSize(11);
            permBtn.setGravity(Gravity.CENTER);
            permBtn.setTextColor(color(R.color.primary));
            permBtn.setBackground(buttonBackground(R.color.primary_container));
            LinearLayout.LayoutParams permLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
            permLp.setMargins(0, dp(4), 0, dp(8));
            page.addView(permBtn, permLp);
            permBtn.setOnClickListener(v -> requestPublicStoragePermission());
        }
        // 分隔留白
        page.addView(new android.view.View(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        // 清空按钮行：清空长期文件 / 清理临时缓存
        LinearLayout clearRow = new LinearLayout(context);
        clearRow.setOrientation(LinearLayout.HORIZONTAL);
        clearRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams clearRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clearRowLp.setMargins(0, 0, 0, dp(10));
        clearRow.setLayoutParams(clearRowLp);

        TextView clearBtn = new TextView(context);
        clearBtn.setText("🗑 清空长期文件");
        clearBtn.setTextSize(12);
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setTextColor(color(R.color.error));
        clearBtn.setBackground(buttonBackground(R.color.error_container));
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                0, dp(40));
        clearLp.weight = 1;
        clearLp.setMargins(0, 0, dp(4), 0);
        clearRow.addView(clearBtn, clearLp);

        clearBtn.setOnClickListener(v -> {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle("清空长期文件")
                .setMessage("将删除 files/ 下所有长期文件（用户保留的产物）。确定继续吗？")
                .setPositiveButton("清空", (dialog, which) -> {
                    int removed = 0;
                    for (AgentWorkspace.WorkspaceFile f : ws.listFiles()) {
                        if ("files".equals(f.zone) && ws.deleteFile(f.name)) removed++;
                    }
                    Toast.makeText(context, "已清空长期文件，删除 " + removed + " 个", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
        });

        TextView clearTmpBtn = new TextView(context);
        clearTmpBtn.setText("🧹 清理临时缓存");
        clearTmpBtn.setTextSize(12);
        clearTmpBtn.setGravity(Gravity.CENTER);
        clearTmpBtn.setTextColor(color(R.color.text_secondary));
        clearTmpBtn.setBackground(buttonBackground(R.color.secondary_container));
        LinearLayout.LayoutParams clearTmpLp = new LinearLayout.LayoutParams(
                0, dp(40));
        clearTmpLp.weight = 1;
        clearTmpLp.setMargins(dp(4), 0, 0, 0);
        clearRow.addView(clearTmpBtn, clearTmpLp);

        clearTmpBtn.setOnClickListener(v -> {
            int removed = AgentWorkspace.getInstance(context).clearTmp();
            Toast.makeText(context, "已清理临时缓存，删除 " + removed + " 个", Toast.LENGTH_SHORT).show();
            refresh();
        });

        page.addView(clearRow);

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
            empty.setText("工作区为空。\n📁 长期文件：Agent 生成要保留的报告/图片等\n⚙️ 临时缓存：任务结束自动清理");
            empty.setTextSize(13);
            empty.setTextColor(color(R.color.text_tertiary));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, 0);
            listContainer.addView(empty);
            return;
        }

        // 分区标题
        TextView filesTitle = new TextView(context);
        filesTitle.setText("📁 长期文件");
        filesTitle.setTextSize(13);
        filesTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        filesTitle.setTextColor(color(R.color.text_primary));
        filesTitle.setPadding(0, dp(8), 0, dp(4));
        listContainer.addView(filesTitle);

        boolean hasFiles = false, hasTmp = false;
        for (AgentWorkspace.WorkspaceFile f : files) {
            if ("files".equals(f.zone)) {
                listContainer.addView(createFileItem(f));
                hasFiles = true;
            }
        }
        if (!hasFiles) {
            TextView emptyFiles = new TextView(context);
            emptyFiles.setText("暂无长期文件");
            emptyFiles.setTextSize(12);
            emptyFiles.setTextColor(color(R.color.text_tertiary));
            emptyFiles.setPadding(dp(4), dp(2), 0, dp(6));
            listContainer.addView(emptyFiles);
        }

        TextView tmpTitle = new TextView(context);
        tmpTitle.setText("⚙️ 临时缓存（任务结束自动清理）");
        tmpTitle.setTextSize(13);
        tmpTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tmpTitle.setTextColor(color(R.color.text_primary));
        tmpTitle.setPadding(0, dp(12), 0, dp(4));
        listContainer.addView(tmpTitle);

        for (AgentWorkspace.WorkspaceFile f : files) {
            if ("tmp".equals(f.zone)) {
                listContainer.addView(createFileItem(f));
                hasTmp = true;
            }
        }
        if (!hasTmp) {
            TextView emptyTmp = new TextView(context);
            emptyTmp.setText("暂无临时缓存");
            emptyTmp.setTextSize(12);
            emptyTmp.setTextColor(color(R.color.text_tertiary));
            emptyTmp.setPadding(dp(4), dp(2), 0, dp(6));
            listContainer.addView(emptyTmp);
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

    /** 跳转系统"所有文件访问"授权页；授权后工作区自动切换到公共目录并迁移旧文件 */
    private void requestPublicStoragePermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                Toast.makeText(context, "当前 Android 版本无需额外授权，工作区已使用公共目录",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            android.content.Intent intent = new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(android.net.Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
            } catch (Exception e) {
                android.content.Intent fallback = new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                fallback.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            }
            // 返回后检查：授权则重建工作区实例（切公共目录）+ 迁移旧文件
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (AgentWorkspace.hasPublicStoragePermission()) {
                    AgentWorkspace.rebuildInstance(context);
                    int migrated = AgentWorkspace.getInstance(context).migrateFromPrivate(context);
                    Toast.makeText(context, "已切换到公共目录，迁移 " + migrated + " 个文件",
                            Toast.LENGTH_SHORT).show();
                    refresh();
                } else {
                    Toast.makeText(context, "未授予文件访问权限，工作区仍在私有目录", Toast.LENGTH_SHORT).show();
                }
            }, 2000);
        } catch (Exception e) {
            Toast.makeText(context, "无法打开授权页: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
