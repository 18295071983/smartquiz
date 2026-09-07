package com.oilquiz.app.ui.export;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;

import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;
import com.oilquiz.app.util.export.ExportManager;
import com.oilquiz.app.util.export.template.FieldMapper;
import com.oilquiz.app.util.export.template.Template;
import com.oilquiz.app.util.export.template.TemplateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.oilquiz.app.theme.ThemeColors;
/**
 * 字段配置Activity
 * 用于配置导出字段
 */
public class FieldConfigActivity extends AppCompatActivity {

    private ListView fieldListView;
    private MaterialButton exportButton;
    private TextView templateNameText;

    private String format;
    private String templateId;
    private Template template;
    private long contentTemplateId;
    private String contentTemplateName;
    private String contentTemplateFilePath;
    private boolean isContentTemplateMode = false;
    private List<FieldItem> fieldItems;
    private java.util.List<com.oilquiz.app.model.Question> questions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_field_config);

        initViews();
        getIntentData();
        loadTemplate();
        initFieldList();
    }

    private void initViews() {
        fieldListView = findViewById(R.id.field_list);
        exportButton = findViewById(R.id.export_button);
        templateNameText = findViewById(R.id.template_name);

        exportButton.setOnClickListener(v -> startExport());
    }

    private void getIntentData() {
        Intent intent = getIntent();
        format = intent.getStringExtra("format");
        templateId = intent.getStringExtra("templateId");
        // 题目列表从内存持有器获取（Intent 序列化大列表会抛 TransactionTooLargeException）
        questions = ExportQuestionsHolder.get();
        
        // 检查是否是内容模板
        if (intent.hasExtra("contentTemplateId")) {
            isContentTemplateMode = true;
            contentTemplateId = intent.getLongExtra("contentTemplateId", 0);
            contentTemplateName = intent.getStringExtra("contentTemplateName");
            contentTemplateFilePath = intent.getStringExtra("contentTemplateFilePath");
        }
    }

    private void loadTemplate() {
        if (isContentTemplateMode) {
            // 显示内容模板信息
            templateNameText.setText(getString(R.string.h_92f00bd2) + contentTemplateName);
        } else {
            // 显示导出模板信息
            TemplateManager templateManager = TemplateManager.getInstance();
            template = templateManager.getTemplateById(templateId);
            if (template != null) {
                templateNameText.setText(getString(R.string.h_c906734a) + template.getName());
            }
        }
    }

    private void initFieldList() {
        Map<String, String> allFields = FieldMapper.getAllAvailableFields();
        List<String> templateFields;
        
        if (!isContentTemplateMode && template != null && template.getFields() != null && !template.getFields().isEmpty()) {
            // 从模板字段创建独立副本，避免修改原始数据
            templateFields = new ArrayList<>(template.getFields());
        } else {
            // 默认核心字段（包含所有选项 A-L）
            templateFields = new ArrayList<>();
            templateFields.add("questionText");
            templateFields.add("optionA");
            templateFields.add("optionB");
            templateFields.add("optionC");
            templateFields.add("optionD");
            templateFields.add("optionE");
            templateFields.add("optionF");
            templateFields.add("optionG");
            templateFields.add("optionH");
            templateFields.add("optionI");
            templateFields.add("optionJ");
            templateFields.add("optionK");
            templateFields.add("optionL");
            templateFields.add("correctAnswer");
            templateFields.add("explanation");
            templateFields.add("questionType");
            templateFields.add("difficulty");
            templateFields.add("category");
        }

        fieldItems = new ArrayList<>();
        // 字段列表与主导出流程（ExportUtils/FieldMapper）对齐：含解析/分类/知识点等全部可导出字段
        String[] coreFieldNames = {
            "questionType", "questionText",
            "optionA", "optionB", "optionC", "optionD",
            "optionE", "optionF", "optionG", "optionH",
            "optionI", "optionJ", "optionK", "optionL",
            "correctAnswer", "difficulty", "explanation", "analysis",
            "category", "subCategory", "knowledgePoint", "tags", "hint",
            "source", "author", "comment", "relatedQuestion",
            "points", "timeLimit", "favorite",
            "usageCount", "correctCount", "incorrectCount"
        };
        
        for (String fieldName : coreFieldNames) {
            if (allFields.containsKey(fieldName)) {
                String displayName = allFields.get(fieldName);
                boolean selected = templateFields.contains(fieldName);
                fieldItems.add(new FieldItem(fieldName, displayName, selected));
            }
        }

        FieldAdapter adapter = new FieldAdapter(this, fieldItems);
        fieldListView.setAdapter(adapter);
    }

    /**
     * 字段适配器
     */
    private static class FieldAdapter extends ArrayAdapter<FieldItem> {

        public FieldAdapter(FieldConfigActivity context, List<FieldItem> items) {
            super(context, 0, items);
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            FieldItem item = getItem(position);
            
            if (convertView == null) {
                convertView = new android.widget.LinearLayout(getContext());
                android.widget.LinearLayout layout = (android.widget.LinearLayout) convertView;
                layout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                layout.setPadding(32, 16, 32, 16);
                layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
                
                com.google.android.material.checkbox.MaterialCheckBox checkBox = 
                    new com.google.android.material.checkbox.MaterialCheckBox(getContext());
                checkBox.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ));
                checkBox.setTextSize(15, android.util.TypedValue.COMPLEX_UNIT_SP);
                checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(ThemeColors.attr(getContext(), R.attr.colorPrimary)));
                layout.addView(checkBox);
                
                convertView.setTag(checkBox);
            }
            
            com.google.android.material.checkbox.MaterialCheckBox checkBox = 
                (com.google.android.material.checkbox.MaterialCheckBox) convertView.getTag();
            checkBox.setText(item.getDisplayName());
            checkBox.setChecked(item.isSelected());
            
            checkBox.setOnCheckedChangeListener(null);
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                FieldItem fieldItem = (FieldItem) buttonView.getTag();
                fieldItem.setSelected(isChecked);
            });
            checkBox.setTag(item);
            
            return convertView;
        }
    }

    private void startExport() {
        // 收集选中的字段
        List<String> selectedFields = new ArrayList<>();
        for (FieldItem item : fieldItems) {
            if (item.isSelected()) {
                selectedFields.add(item.getFieldName());
            }
        }

        // 创建导出配置
        ExportManager.ExportConfig config = new ExportManager.ExportConfig();
        config.setFormat(ExportManager.ExportFormat.valueOf(format));
        config.setTemplateId(templateId);
        config.setSelectedFields(selectedFields);
        config.setIncludeAnswers(true);
        config.setIncludeExplanations(true);

        // 启动导出任务
        Intent intent = new Intent(this, ExportProgressActivity.class);
        intent.putExtra("config", config);
        // questions 继续由 ExportQuestionsHolder 持有，不再放入 Intent
        
        // 传递内容模板信息
        if (isContentTemplateMode) {
            intent.putExtra("contentTemplateId", contentTemplateId);
            intent.putExtra("contentTemplateName", contentTemplateName);
            intent.putExtra("contentTemplateFilePath", contentTemplateFilePath);
            intent.putExtra("isContentTemplateMode", true);
        }
        
        startActivity(intent);
    }

    /**
     * 字段项类
     */
    public static class FieldItem {
        private String fieldName;
        private String displayName;
        private boolean selected;

        public FieldItem(String fieldName, String displayName, boolean selected) {
            this.fieldName = fieldName;
            this.displayName = displayName;
            this.selected = selected;
        }

        public String getFieldName() {
            return fieldName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean isSelected() {
            return selected;
        }

        public void setSelected(boolean selected) {
            this.selected = selected;
        }
    }
}
