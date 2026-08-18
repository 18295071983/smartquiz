package com.oilquiz.app.ai.agent.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.python.PythonDynamicTool;
import com.oilquiz.app.ai.tool.AITool;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.DynamicAITool;

import java.util.List;
import java.util.Map;

/**
 * 动态工具管理视图：查看/删除模型创建的动态工具（支持 Java 脚本工具与 Python 工具）。
 * 数据源：AIToolManager（dynamic_tools.json 持久化）。
 */
public class AgentToolView {

    private final Context context;
    private LinearLayout listContainer;

    public AgentToolView(Context context) {
        this.context = context;
    }

    public View build(ViewGroup root) {
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), dp(16));
        page.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(context);
        title.setText("🔧 动态工具");
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.text_primary));
        page.addView(title);

        TextView desc = new TextView(context);
        desc.setText("Agent 通过 create_dynamic_tool / ai_create_tool 创建的工具，持久化保存，重启后仍可用。可在此删除不需要的工具。");
        desc.setTextSize(12);
        desc.setTextColor(color(R.color.text_secondary));
        desc.setPadding(0, dp(4), 0, dp(12));
        page.addView(desc);

        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(listContainer);

        refresh();
        return page;
    }

    private void refresh() {
        listContainer.removeAllViews();
        AIToolManager manager = AIToolManager.getInstance(context);
        List<String> toolNames = manager.getDynamicTools();

        if (toolNames.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("暂无动态工具。\n对话中让 Agent「创建一个工具」后，会出现在这里。");
            empty.setTextSize(13);
            empty.setTextColor(color(R.color.text_tertiary));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, 0);
            listContainer.addView(empty);
            return;
        }

        for (String toolName : toolNames) {
            AITool tool = manager.getTool(toolName);
            if (tool == null) continue;
            listContainer.addView(createToolItem(toolName, tool));
        }
    }

    /** 单工具项：名称 + 描述 + 参数 + 类型 + 删除 */
    private View createToolItem(String toolName, AITool tool) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(8), dp(8));
        card.setBackground(cardBackground());
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(6));
        card.setLayoutParams(cardLp);

        // 第一行：名称 + 类型标记 + 删除
        LinearLayout headRow = new LinearLayout(context);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView nameTv = new TextView(context);
        nameTv.setText("🔧 " + toolName);
        nameTv.setTextSize(14);
        nameTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        nameTv.setTextColor(color(R.color.text_primary));
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headRow.addView(nameTv);

        TextView typeTag = new TextView(context);
        boolean isPython = tool instanceof PythonDynamicTool;
        typeTag.setText(isPython ? "Python" : "脚本");
        typeTag.setTextSize(10);
        typeTag.setTextColor(color(isPython ? R.color.primary : R.color.secondary));
        typeTag.setBackground(pillBackground(isPython ? R.color.primary_container : R.color.secondary_container));
        typeTag.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.setMargins(0, 0, dp(6), 0);
        headRow.addView(typeTag, tagLp);

        TextView delBtn = new TextView(context);
        delBtn.setText("删除");
        delBtn.setTextSize(12);
        delBtn.setGravity(Gravity.CENTER);
        delBtn.setTextColor(color(R.color.error));
        delBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        delBtn.setOnClickListener(v -> {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle("删除工具")
                .setMessage("确定删除动态工具「" + toolName + "」吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    managerOf().unregisterDynamicTool(toolName);
                    Toast.makeText(context, "已删除工具: " + toolName, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
        });
        headRow.addView(delBtn);
        card.addView(headRow);

        // 描述
        String desc = tool.getDescription();
        if (desc != null && !desc.isEmpty()) {
            TextView descTv = new TextView(context);
            descTv.setText(desc);
            descTv.setTextSize(12);
            descTv.setTextColor(color(R.color.text_secondary));
            descTv.setPadding(0, dp(4), 0, 0);
            card.addView(descTv);
        }

        // 参数
        Map<String, String> params = tool.getParameterDescriptions();
        if (params != null && !params.isEmpty()) {
            TextView paramsTv = new TextView(context);
            StringBuilder sb = new StringBuilder("参数: ");
            for (String key : params.keySet()) {
                if (sb.length() > 4) sb.append("、");
                sb.append(key);
            }
            paramsTv.setText(sb.toString());
            paramsTv.setTextSize(11);
            paramsTv.setTextColor(color(R.color.text_tertiary));
            paramsTv.setPadding(0, dp(3), 0, 0);
            card.addView(paramsTv);
        }

        // 逻辑/代码摘要
        if (tool instanceof PythonDynamicTool) {
            String code = ((PythonDynamicTool) tool).getCode();
            if (code != null && !code.isEmpty()) {
                TextView codeTv = new TextView(context);
                String preview = code.length() > 80 ? code.substring(0, 80) + "..." : code;
                codeTv.setText("代码: " + preview);
                codeTv.setTextSize(11);
                codeTv.setTextColor(color(R.color.text_tertiary));
                codeTv.setPadding(0, dp(3), 0, 0);
                card.addView(codeTv);
            }
        } else if (tool instanceof DynamicAITool) {
            String logic = ((DynamicAITool) tool).getExecutionLogic();
            if (logic != null && !logic.isEmpty()) {
                TextView logicTv = new TextView(context);
                String preview = logic.length() > 80 ? logic.substring(0, 80) + "..." : logic;
                logicTv.setText("逻辑: " + preview);
                logicTv.setTextSize(11);
                logicTv.setTextColor(color(R.color.text_tertiary));
                logicTv.setPadding(0, dp(3), 0, 0);
                card.addView(logicTv);
            }
        }
        return card;
    }

    private AIToolManager managerOf() {
        return AIToolManager.getInstance(context);
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

    private android.graphics.drawable.Drawable pillBackground(int containerResId) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color(containerResId));
        gd.setCornerRadius(dp(8));
        return gd;
    }

    private int dp(float value) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
