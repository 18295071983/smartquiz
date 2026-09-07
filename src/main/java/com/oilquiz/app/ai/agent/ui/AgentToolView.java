package com.oilquiz.app.ai.agent.ui;

import com.oilquiz.app.theme.ThemeColors;

import android.content.Context;
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
 * 动态工具 & 组件注册管理视图：
 *   - 动态工具（create_dynamic_tool / ai_create_tool 创建，dynamic_tools.json 持久化）
 *   - 组件插件（ui_component_plugin create，ui_component_plugins.json 持久化）
 *   - 自定义组件类型（ui_component register_type，ui_component_types.json 持久化）
 *   - layout 模板（ui_component_plugin register_layout，layout_templates.json 持久化）
 * 均可查看、删除；Agent 注册默认 persist=true（跨重启永久保留）。支持自动刷新。
 */
public class AgentToolView {

    private final Context context;
    private LinearLayout listContainer;
    private static final long AUTO_REFRESH_INTERVAL = 3000L;
    private final android.os.Handler autoRefreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autoRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (listContainer != null && listContainer.isAttachedToWindow()) {
                autoRefreshHandler.postDelayed(this, AUTO_REFRESH_INTERVAL);
            }
        }
    };

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
        title.setText("🔧 动态工具 & 组件注册");
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.text_primary));
        page.addView(title);

        TextView desc = new TextView(context);
        desc.setText("Agent 创建的工具/插件/类型/模板默认持久化保存（跨重启保留），可在此查看与删除。");
        desc.setTextSize(12);
        desc.setTextColor(color(R.color.text_secondary));
        desc.setPadding(0, dp(4), 0, dp(8));
        page.addView(desc);

        // 清空临时按钮：一键清除所有临时注册（任务残留），长久注册不受影响
        TextView clearTmpBtn = new TextView(context);
        clearTmpBtn.setText("🧹 清除全部临时注册（任务残留）");
        clearTmpBtn.setTextSize(12);
        clearTmpBtn.setGravity(Gravity.CENTER);
        clearTmpBtn.setTextColor(color(R.color.text_secondary));
        clearTmpBtn.setBackground(buttonBackground(R.color.secondary_container));
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        clearLp.setMargins(0, 0, 0, dp(8));
        page.addView(clearTmpBtn, clearLp);
        clearTmpBtn.setOnClickListener(v -> {
            com.oilquiz.app.ai.tool.UIComponentPluginManager pm =
                    com.oilquiz.app.ai.tool.UIComponentPluginManager.getInstance(context);
            com.oilquiz.app.ai.tool.UIComponentTypeRegistry tr =
                    com.oilquiz.app.ai.tool.UIComponentTypeRegistry.getInstance(context);
            com.oilquiz.app.ai.python.LayoutTemplateRegistry lr =
                    com.oilquiz.app.ai.python.LayoutTemplateRegistry.getInstance(context);
            int pBefore = pm.listPlugins().size();
            int tBefore = tr.listTypes().size();
            int lBefore = lr.listTemplates().length();
            pm.clearTemporaryPlugins();
            tr.clearTemporaryTypes();
            lr.clearTemporary();
            int pAfter = pm.listPlugins().size();
            int tAfter = tr.listTypes().size();
            int lAfter = lr.listTemplates().length();
            Toast.makeText(context, "已清除临时注册: 插件 " + (pBefore - pAfter) + " 个, 类型 "
                    + (tBefore - tAfter) + " 个, 模板 " + (lBefore - lAfter) + " 个",
                    Toast.LENGTH_SHORT).show();
            refresh();
        });

        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(listContainer);

        // 自动刷新：Agent 后台注册/删除时列表自动更新
        page.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
                autoRefreshHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL);
            }
            @Override public void onViewDetachedFromWindow(View v) {
                autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
            }
        });

        refresh();
        return page;
    }

    private void refresh() {
        // 重新从磁盘加载（单例可能先于 Agent 注册初始化，reload 保证显示最新持久化注册）
        try {
            com.oilquiz.app.ai.tool.UIComponentPluginManager.getInstance(context).reload();
            com.oilquiz.app.ai.tool.UIComponentTypeRegistry.getInstance(context).reload();
            com.oilquiz.app.ai.python.LayoutTemplateRegistry.getInstance(context).reload();
        } catch (Throwable ignored) {
        }
        listContainer.removeAllViews();

        // ===== 1. 动态工具 =====
        addSectionTitle("🔧 动态工具（dynamic_tools.json 持久化）");
        AIToolManager manager = AIToolManager.getInstance(context);
        List<String> toolNames = manager.getDynamicTools();
        if (toolNames.isEmpty()) {
            addEmpty("暂无动态工具（对话中让 Agent「创建一个工具」后出现）");
        } else {
            for (String toolName : toolNames) {
                AITool tool = manager.getTool(toolName);
                if (tool == null) continue;
                addToolItem(toolName, tool);
            }
        }

        // ===== 2. 组件插件 =====
        addSectionTitle("🧩 组件插件（ui_component_plugin create 持久化）");
        java.util.List<org.json.JSONObject> plugins =
                com.oilquiz.app.ai.tool.UIComponentPluginManager.getInstance(context).listPlugins();
        if (plugins.isEmpty()) {
            addEmpty("暂无组件插件");
        } else {
            for (org.json.JSONObject p : plugins) {
                final String pName = p.optString("name", "");
                addRegistryItem(
                        "🧩 " + pName,
                        p.optString("description", ""),
                        p.optString("mode", "persistent"),
                        p.optString("card", ""),
                        "插件",
                        () -> com.oilquiz.app.ai.tool.UIComponentPluginManager.getInstance(context)
                                .removePlugin(pName));
            }
        }

        // ===== 3. 自定义组件类型 =====
        addSectionTitle("📦 自定义组件类型（ui_component register_type 持久化）");
        java.util.List<org.json.JSONObject> types =
                com.oilquiz.app.ai.tool.UIComponentTypeRegistry.getInstance(context).listTypes();
        if (types.isEmpty()) {
            addEmpty("暂无自定义类型");
        } else {
            for (org.json.JSONObject t : types) {
                final String tName = t.optString("name", "");
                String kind = t.optBoolean("has_layout", false) ? "layout类型" : "card类型";
                addRegistryItem(
                        "📦 " + tName,
                        t.optString("description", ""),
                        t.optString("mode", "persistent"),
                        t.optString("card", ""),
                        kind,
                        () -> com.oilquiz.app.ai.tool.UIComponentTypeRegistry.getInstance(context)
                                .removeType(tName));
            }
        }

        // ===== 4. layout 模板 =====
        addSectionTitle("🧱 layout 模板（register_layout 持久化）");
        org.json.JSONArray templates =
                com.oilquiz.app.ai.python.LayoutTemplateRegistry.getInstance(context).listTemplates();
        if (templates.length() == 0) {
            addEmpty("暂无 layout 模板");
        } else {
            for (int i = 0; i < templates.length(); i++) {
                org.json.JSONObject t = templates.optJSONObject(i);
                if (t == null) continue;
                final String tName = t.optString("name", "");
                addRegistryItem(
                        "🧱 " + tName,
                        t.optString("description", ""),
                        t.optString("mode", "persistent"),
                        "",
                        "layout模板",
                        () -> com.oilquiz.app.ai.python.LayoutTemplateRegistry.getInstance(context)
                                .removeTemplate(tName));
            }
        }
    }

    /** 分区标题 */
    private void addSectionTitle(String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextColor(color(R.color.text_primary));
        tv.setPadding(0, dp(10), 0, dp(4));
        listContainer.addView(tv);
    }

    private void addEmpty(String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(color(R.color.text_tertiary));
        tv.setPadding(dp(4), dp(2), 0, dp(6));
        listContainer.addView(tv);
    }

    /** 通用注册项：名称 + 描述 + mode 标记 + 删除 */
    private void addRegistryItem(String name, String desc, String mode, String cardType,
                                 String kind, Runnable deleteAction) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(10), dp(8), dp(8), dp(8));
        item.setBackground(cardBackground());
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(6));
        item.setLayoutParams(cardLp);

        LinearLayout headRow = new LinearLayout(context);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView nameTv = new TextView(context);
        nameTv.setText(name);
        nameTv.setTextSize(14);
        nameTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        nameTv.setTextColor(color(R.color.text_primary));
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headRow.addView(nameTv);

        boolean isPersistent = "persistent".equals(mode);
        TextView modeTag = new TextView(context);
        modeTag.setText(isPersistent ? "永久" : "临时");
        modeTag.setTextSize(10);
        modeTag.setTextColor(color(isPersistent ? R.color.primary : R.color.warning));
        modeTag.setBackground(pillBackground(isPersistent
                ? R.color.primary_container : R.color.warning_container));
        modeTag.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.setMargins(0, 0, dp(6), 0);
        headRow.addView(modeTag, tagLp);

        TextView delBtn = new TextView(context);
        delBtn.setText("删除");
        delBtn.setTextSize(12);
        delBtn.setGravity(Gravity.CENTER);
        delBtn.setTextColor(color(R.color.error));
        delBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        delBtn.setOnClickListener(v -> {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle("删除注册")
                .setMessage("确定删除「" + name + "」吗？（" + (isPersistent ? "持久化注册，删除后不再保留" : "临时注册") + "）")
                .setPositiveButton("删除", (dialog, which) -> {
                    try {
                        deleteAction.run();
                        Toast.makeText(context, "已删除: " + name, Toast.LENGTH_SHORT).show();
                        refresh();
                    } catch (Exception e) {
                        Toast.makeText(context, "删除失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        });
        headRow.addView(delBtn);
        item.addView(headRow);

        if (desc != null && !desc.isEmpty()) {
            TextView descTv = new TextView(context);
            descTv.setText(desc);
            descTv.setTextSize(12);
            descTv.setTextColor(color(R.color.text_secondary));
            descTv.setPadding(0, dp(3), 0, 0);
            item.addView(descTv);
        }
        if (kind != null && !kind.isEmpty()) {
            TextView metaTv = new TextView(context);
            metaTv.setText(kind + (cardType == null || cardType.isEmpty() ? "" : " · " + cardType));
            metaTv.setTextSize(11);
            metaTv.setTextColor(color(R.color.text_tertiary));
            metaTv.setPadding(0, dp(2), 0, 0);
            item.addView(metaTv);
        }
        listContainer.addView(item);
    }

    /** 单工具项：名称 + 描述 + 参数 + 类型 + 删除 */
    private void addToolItem(String toolName, AITool tool) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(8), dp(8));
        card.setBackground(cardBackground());
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(6));
        card.setLayoutParams(cardLp);

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
                .setMessage("确定删除动态工具「" + toolName + "」吗？（持久化，删除后不再保留）")
                .setPositiveButton("删除", (dialog, which) -> {
                    AIToolManager.getInstance(context).unregisterDynamicTool(toolName);
                    Toast.makeText(context, "已删除工具: " + toolName, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
        });
        headRow.addView(delBtn);
        card.addView(headRow);

        String desc = tool.getDescription();
        if (desc != null && !desc.isEmpty()) {
            TextView descTv = new TextView(context);
            descTv.setText(desc);
            descTv.setTextSize(12);
            descTv.setTextColor(color(R.color.text_secondary));
            descTv.setPadding(0, dp(4), 0, 0);
            card.addView(descTv);
        }

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
        listContainer.addView(card);
    }

    private int color(int resId) {
        return ThemeColors.get(context, resId);
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