package com.oilquiz.app.ai.agent.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.agent.online.AgentMemoryStore;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆管理视图：查看/新增/删除/清空 Agent 记忆。
 * 数据源：AgentMemoryStore（agent_memory.json）。
 */
public class AgentMemoryView {

    private static final String TAG = "AgentMemoryView";
    private final Context context;
    private LinearLayout listContainer;

    public AgentMemoryView(Context context) {
        this.context = context;
    }

    public View build(ViewGroup root) {
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), dp(16));
        page.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题 + 说明
        TextView title = new TextView(context);
        title.setText("🧠 长期记忆");
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.text_primary));
        page.addView(title);

        TextView desc = new TextView(context);
        desc.setText("Agent 跨会话记住的用户信息。可手动新增、删除，清空后 Agent 将忘记这些内容。");
        desc.setTextSize(12);
        desc.setTextColor(color(R.color.text_secondary));
        desc.setPadding(0, dp(4), 0, dp(12));
        page.addView(desc);

        // 新增行：key + value 输入 + 保存
        LinearLayout addRow = new LinearLayout(context);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);

        EditText keyInput = new EditText(context);
        keyInput.setHint("记忆键 (如 user_name)");
        keyInput.setTextSize(13);
        keyInput.setSingleLine(true);
        keyInput.setBackground(inputBackground());
        keyInput.setPadding(dp(8), dp(6), dp(8), dp(6));
        addRow.addView(keyInput, new LinearLayout.LayoutParams(0, dp(40), 0.35f));

        EditText valueInput = new EditText(context);
        valueInput.setHint("记忆内容 (如 小明)");
        valueInput.setTextSize(13);
        valueInput.setSingleLine(true);
        valueInput.setBackground(inputBackground());
        valueInput.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(0, dp(40), 0.45f);
        valueLp.setMargins(dp(6), 0, dp(6), 0);
        addRow.addView(valueInput, valueLp);

        TextView saveBtn = new TextView(context);
        saveBtn.setText("保存");
        saveBtn.setTextSize(13);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setTextColor(color(R.color.on_primary));
        saveBtn.setBackground(buttonBackground(R.color.primary));
        addRow.addView(saveBtn, new LinearLayout.LayoutParams(0, dp(40), 0.2f));
        page.addView(addRow);

        // 记忆列表容器
        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(listContainer);

        // 底部操作：清空
        TextView clearBtn = new TextView(context);
        clearBtn.setText("🗑 清空所有记忆");
        clearBtn.setTextSize(13);
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setTextColor(color(R.color.error));
        clearBtn.setPadding(0, dp(12), 0, dp(12));
        clearBtn.setBackground(buttonBackground(R.color.error_container));
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        clearLp.setMargins(0, dp(12), 0, 0);
        page.addView(clearBtn, clearLp);

        saveBtn.setOnClickListener(v -> {
            String key = keyInput.getText().toString().trim();
            String value = valueInput.getText().toString().trim();
            if (key.isEmpty() || value.isEmpty()) {
                Toast.makeText(context, "请填写记忆键和内容", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean ok = AgentMemoryStore.getInstance(context).save(key, value);
            Toast.makeText(context, ok ? "已保存记忆: " + key : "保存失败", Toast.LENGTH_SHORT).show();
            if (ok) {
                keyInput.setText("");
                valueInput.setText("");
                refresh();
            }
        });

        clearBtn.setOnClickListener(v -> {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle("清空所有记忆")
                .setMessage("将删除全部记忆，Agent 将忘记这些信息。确定继续吗？")
                .setPositiveButton("清空", (dialog, which) -> {
                    AgentMemoryStore.getInstance(context).clear();
                    Toast.makeText(context, "已清空记忆", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
        });

        refresh();
        return page;
    }

    /** 刷新记忆列表 */
    private void refresh() {
        listContainer.removeAllViews();
        List<AgentMemoryStore.MemoryEntry> all = AgentMemoryStore.getInstance(context).getAll();
        if (all.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("暂无记忆。\n对话中告知 Agent 你的偏好后，会自动保存到这里。");
            empty.setTextSize(13);
            empty.setTextColor(color(R.color.text_tertiary));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, 0);
            listContainer.addView(empty);
            return;
        }
        for (AgentMemoryStore.MemoryEntry entry : all) {
            listContainer.addView(createMemoryItem(entry));
        }
    }

    /** 单条记忆项：key + value + 删除按钮 */
    private View createMemoryItem(AgentMemoryStore.MemoryEntry entry) {
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
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textCol.setLayoutParams(textLp);

        TextView keyTv = new TextView(context);
        keyTv.setText(entry.key);
        keyTv.setTextSize(13);
        keyTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        keyTv.setTextColor(color(R.color.text_primary));
        textCol.addView(keyTv);

        TextView valueTv = new TextView(context);
        valueTv.setText(entry.value);
        valueTv.setTextSize(12);
        valueTv.setTextColor(color(R.color.text_secondary));
        valueTv.setPadding(0, dp(2), 0, 0);
        textCol.addView(valueTv);

        TextView timeTv = new TextView(context);
        timeTv.setText("更新于 " + formatTime(entry.updatedAt));
        timeTv.setTextSize(10);
        timeTv.setTextColor(color(R.color.text_tertiary));
        timeTv.setPadding(0, dp(2), 0, 0);
        textCol.addView(timeTv);

        row.addView(textCol);

        TextView delBtn = new TextView(context);
        delBtn.setText("删除");
        delBtn.setTextSize(12);
        delBtn.setGravity(Gravity.CENTER);
        delBtn.setTextColor(color(R.color.error));
        delBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        delBtn.setOnClickListener(v -> {
            AgentMemoryStore.getInstance(context).remove(entry.key);
            Toast.makeText(context, "已删除: " + entry.key, Toast.LENGTH_SHORT).show();
            refresh();
        });
        row.addView(delBtn);
        return row;
    }

    // ==================== UI 工具 ====================

    /** 时间戳 → 可读时间（旧格式无时间戳显示"较早"） */
    private String formatTime(long updatedAt) {
        if (updatedAt <= 0) return "较早";
        try {
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
            return sdf.format(new java.util.Date(updatedAt));
        } catch (Exception e) {
            return String.valueOf(updatedAt);
        }
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

    private android.graphics.drawable.Drawable inputBackground() {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color(R.color.surface_variant));
        gd.setCornerRadius(dp(6));
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
