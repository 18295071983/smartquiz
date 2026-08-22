package com.oilquiz.app.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入字段映射确认对话框：展示 AI 识别的字段映射，允许用户对每个字段改选列。
 * 用于 AI 导入交互流程的"映射确认"步骤（准确率优先：AI 建议 + 人工核对）。
 *
 * 输入：fileHeaders 表头列表；aiMapping 标准字段→源列名
 * 输出：用户确认/修改后的映射（标准字段→源列名），或 null（用户接受原样 / 取消）
 */
public class ImportMappingConfirmDialog extends Dialog {

    public interface OnMappingResultListener {
        /** @param confirmed true=用户确认（含修改后的映射）；false=用户取消 */
        void onResult(boolean confirmed, Map<String, String> mapping);
    }

    private final List<String> fileHeaders;
    private final Map<String, String> aiMapping;
    private final String docHint;
    private final String mappingSource;
    private final OnMappingResultListener listener;
    private final Map<String, Integer> fieldToHeaderIndex = new HashMap<>();
    private final List<Spinner> spinners = new ArrayList<>();

    public ImportMappingConfirmDialog(@NonNull Context context,
                                      List<String> fileHeaders,
                                      Map<String, String> aiMapping,
                                      String docHint,
                                      String mappingSource,
                                      OnMappingResultListener listener) {
        super(context);
        this.fileHeaders = fileHeaders != null ? fileHeaders : new ArrayList<>();
        this.aiMapping = aiMapping != null ? aiMapping : new LinkedHashMap<>();
        this.docHint = docHint;
        this.mappingSource = mappingSource;
        this.listener = listener;
        setup();
    }

    private void setup() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_import_mapping_confirm, null);

        // 表头→索引映射（用于把 AI 的列名转成下拉索引）
        for (int i = 0; i < fileHeaders.size(); i++) {
            fieldToHeaderIndex.put(fileHeaders.get(i), i);
        }

        ViewGroup container = view.findViewById(R.id.mapping_container);
        container.removeAllViews();

        // 展示映射来源（帮助用户判断是否需要手动修正）：缓存/规则/LLM/词典兜底
        String sourceText = mappingSource == null ? "未知" : mappingSource;
        switch (sourceText) {
            case "cache": sourceText = "历史映射缓存（同表头文件复用）"; break;
            case "rules": sourceText = "本地词典规则识别（零模型调用）"; break;
            case "ai": sourceText = "AI 模型智能识别"; break;
            case "fallback": sourceText = "本地词典兜底（AI 不可用/失败）"; break;
            default: break;
        }
        TextView srcTitle = new TextView(getContext());
        srcTitle.setText("🔎 映射来源：" + sourceText + "（可修改下方映射后确认）");
        srcTitle.setTextSize(12f);
        srcTitle.setTextColor(getContext().getResources().getColor(R.color.primary));
        srcTitle.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        container.addView(srcTitle);

        // 展示检测到的题库说明（若有）：让用户确认说明被正确识别
        if (docHint != null && !docHint.trim().isEmpty()) {
            TextView docTitle = new TextView(getContext());
            docTitle.setText("📖 已检测到题库说明（用于优化识别）：");
            docTitle.setTextSize(12f);
            docTitle.setTextColor(getContext().getResources().getColor(R.color.primary));
            docTitle.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            container.addView(docTitle);

            TextView docBody = new TextView(getContext());
            docBody.setText(docHint.trim());
            docBody.setTextSize(11f);
            docBody.setTextColor(getContext().getResources().getColor(R.color.text_secondary));
            docBody.setLineSpacing(0, 1.2f);
            docBody.setPadding(0, 2, 0, 8);
            container.addView(docBody);
        }

        // 为每个 AI 映射字段生成一行：字段名 + 列下拉选择
        for (Map.Entry<String, String> e : aiMapping.entrySet()) {
            String field = e.getKey();
            String columnName = e.getValue();

            View row = LayoutInflater.from(getContext())
                    .inflate(R.layout.item_mapping_confirm_row, container, false);
            TextView tvField = row.findViewById(R.id.tv_field_name);
            Spinner spinner = row.findViewById(R.id.spinner_column);

            tvField.setText(fieldLabel(field) + "  (" + field + ")");

            // 下拉选项：不映射 + 各列
            List<String> options = new ArrayList<>();
            options.add("（不映射）");
            for (int i = 0; i < fileHeaders.size(); i++) {
                options.add("列" + (i + 1) + ": " + fileHeaders.get(i));
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    getContext(), android.R.layout.simple_spinner_item, options);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);

            // 选中 AI 建议的列
            Integer idx = fieldToHeaderIndex.get(columnName);
            spinner.setSelection(idx != null ? idx + 1 : 0);

            fieldToHeaderIndex.put(field, idx != null ? idx : -1); // 记录当前选择
            final int spinnerIdx = spinners.size();
            spinners.add(spinner);

            container.addView(row);
        }

        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);

        btnCancel.setOnClickListener(v -> {
            if (listener != null) listener.onResult(false, null);
            dismiss();
        });

        btnConfirm.setOnClickListener(v -> {
            Map<String, String> result = new LinkedHashMap<>();
            int i = 0;
            for (Map.Entry<String, String> e : aiMapping.entrySet()) {
                Spinner sp = spinners.get(i++);
                int pos = sp.getSelectedItemPosition();
                if (pos > 0) {
                    result.put(e.getKey(), fileHeaders.get(pos - 1));
                }
                // 不映射的字段直接跳过
            }
            if (listener != null) listener.onResult(true, result);
            dismiss();
        });

        setContentView(view);

        // 窗口尺寸约束：宽取屏幕 92%（不超内容区），高由内容决定（ScrollView 固定高度保证可滚动）
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

    private static String fieldLabel(String field) {
        switch (field == null ? "" : field) {
            case "questionText": return "题干";
            case "correctAnswer": return "答案";
            case "answerText": return "答案文本";
            case "category": return "分类";
            case "difficulty": return "难度";
            case "explanation": return "解析";
            case "questionType": return "题型";
            case "optionsCombined": return "选项(合并)";
            case "source": return "来源";
            case "tags": return "标签";
            default:
                if (field != null && field.toLowerCase().startsWith("option")) {
                    return "选项" + field.substring("option".length()).toUpperCase();
                }
                return field != null ? field : "";
        }
    }
}
