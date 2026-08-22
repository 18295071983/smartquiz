package com.oilquiz.app.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.importing.FieldMappingRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 题库字段说明对话框：从 {@link FieldMappingRegistry} 动态读取全部标准字段，
 * 分组展示（核心字段 / 填空题 / 扩展字段），含必填标记与常见别名。
 * 数据源与导入识别共用同一注册表，保证说明与实际支持一致。
 */
public class FieldGuideDialog extends Dialog {

    public FieldGuideDialog(@NonNull Context context) {
        super(context);
        setup(context);
    }

    private void setup(Context context) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_field_guide, null);
        ViewGroup container = view.findViewById(R.id.field_container);
        container.removeAllViews();

        // 分组：核心字段（题干/答案/题型/选项/分类/难度/解析）+ 填空答案 + 扩展字段
        Map<String, List<FieldMappingRegistry.FieldDef>> groups = new LinkedHashMap<>();
        groups.put("核心字段（导入必填/常用）", new ArrayList<>());
        groups.put("填空题专用", new ArrayList<>());
        groups.put("扩展字段（可选）", new ArrayList<>());

        List<FieldMappingRegistry.FieldDef> all = FieldMappingRegistry.getAllFields();
        for (FieldMappingRegistry.FieldDef def : all) {
            if (def.canonical != null && def.canonical.startsWith("blankAnswer")) {
                groups.get("填空题专用").add(def);
            } else if (isCore(def.canonical)) {
                groups.get("核心字段（导入必填/常用）").add(def);
            } else {
                groups.get("扩展字段（可选）").add(def);
            }
        }

        boolean first = true;
        for (Map.Entry<String, List<FieldMappingRegistry.FieldDef>> e : groups.entrySet()) {
            List<FieldMappingRegistry.FieldDef> list = e.getValue();
            if (list.isEmpty()) continue;

            // 分组标题
            TextView title = new TextView(context);
            title.setText(e.getKey());
            title.setTextSize(13f);
            title.setTextColor(context.getResources().getColor(R.color.primary));
            title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            title.setPadding(0, first ? 0 : 10, 0, 2);
            container.addView(title);
            first = false;

            for (FieldMappingRegistry.FieldDef def : list) {
                View row = LayoutInflater.from(context)
                        .inflate(R.layout.item_field_guide_row, container, false);
                TextView tvName = row.findViewById(R.id.tv_field_name);
                TextView tvRequired = row.findViewById(R.id.tv_field_required);
                TextView tvAliases = row.findViewById(R.id.tv_field_aliases);

                tvName.setText((def.displayName != null ? def.displayName + " " : "")
                        + "(" + def.canonical + ")");
                if (def.required) {
                    tvRequired.setVisibility(View.VISIBLE);
                }

                // 别名：去重、限制数量避免过长
                java.util.Set<String> aliasSet = new java.util.LinkedHashSet<>();
                if (def.aliases != null) {
                    for (String a : def.aliases) aliasSet.add(a);
                }
                StringBuilder aliases = new StringBuilder();
                int count = 0;
                for (String a : aliasSet) {
                    if (count >= 10) {
                        aliases.append("…");
                        break;
                    }
                    if (aliases.length() > 0) aliases.append("、");
                    aliases.append(a);
                    count++;
                }
                tvAliases.setText(aliases.length() > 0
                        ? "识别为: " + aliases : "无别名（需精确匹配）");
                container.addView(row);
            }
        }

        // 特殊说明
        TextView special = new TextView(context);
        special.setText("特殊形态：\n"
                + "· 合并选项列：单列“可选项/选项/备选答案”含多个选项（分号/竖线/A.前缀分隔）→ 自动拆分为选项A~L\n"
                + "· 多选题：答案填“A;B;C”分隔\n"
                + "· 判断题：选项“对/错”自动识别题型\n"
                + "· 填空题：空1~空12答案自动合并写入正确答案（分号分隔）");
        special.setTextSize(11f);
        special.setTextColor(context.getResources().getColor(R.color.text_secondary));
        special.setLineSpacing(0, 1.2f);
        special.setPadding(0, 10, 0, 0);
        container.addView(special);

        MaterialButton btnClose = view.findViewById(R.id.btn_close);
        btnClose.setOnClickListener(v -> dismiss());

        setContentView(view);

        // 窗口尺寸约束：宽取屏幕 92%，高由内容决定（ScrollView 固定高度保证可滚动）
        try {
            Window window = getWindow();
            if (window != null) {
                android.graphics.Point size = new android.graphics.Point();
                window.getWindowManager().getDefaultDisplay().getSize(size);
                android.view.WindowManager.LayoutParams lp = window.getAttributes();
                lp.width = (int) (size.x * 0.92f);
                lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
                window.setAttributes(lp);
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean isCore(String canonical) {
        if (canonical == null) return false;
        return canonical.equals("questionText")
                || canonical.equals("correctAnswer")
                || canonical.equals("questionType")
                || canonical.equals("category")
                || canonical.equals("difficulty")
                || canonical.equals("explanation")
                || (canonical.startsWith("option") && canonical.length() == 6
                    && canonical.charAt(5) >= 'A' && canonical.charAt(5) <= 'L');
    }
}
