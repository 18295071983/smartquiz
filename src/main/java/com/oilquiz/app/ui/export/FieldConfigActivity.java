package com.oilquiz.app.ui.export;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import com.google.android.material.button.MaterialButton;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;
import com.oilquiz.app.util.export.ExportManager;
import com.oilquiz.app.util.export.template.FieldMapper;
import com.oilquiz.app.util.export.template.Template;
import com.oilquiz.app.util.export.template.TemplateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        questions = (java.util.List<com.oilquiz.app.model.Question>) intent.getSerializableExtra("questions");
        
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
            templateNameText.setText("内容模板：" + contentTemplateName);
        } else {
            // 显示导出模板信息
            TemplateManager templateManager = TemplateManager.getInstance();
            template = templateManager.getTemplateById(templateId);
            if (template != null) {
                templateNameText.setText("模板：" + template.getName());
            }
        }
    }

    private void initFieldList() {
        Map<String, String> allFields = FieldMapper.getAllAvailableFields();
        List<String> templateFields = new ArrayList<>();
        
        if (!isContentTemplateMode && template != null) {
            templateFields = template.getFields();
        } else {
            // 对于内容模板或APK格式，使用默认字段列表
            templateFields.add("questionText");
            templateFields.add("optionA");
            templateFields.add("optionB");
            templateFields.add("optionC");
            templateFields.add("optionD");
            templateFields.add("correctAnswer");
            templateFields.add("explanation");
            templateFields.add("questionType");
            templateFields.add("difficulty");
            templateFields.add("category");
        }

        fieldItems = new ArrayList<>();
        for (Map.Entry<String, String> entry : allFields.entrySet()) {
            String fieldName = entry.getKey();
            String displayName = entry.getValue();
            boolean selected = templateFields.contains(fieldName);
            fieldItems.add(new FieldItem(fieldName, displayName, selected));
        }

        FieldAdapter adapter = new FieldAdapter(this, fieldItems);
        fieldListView.setAdapter(adapter);
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
        intent.putExtra("questions", (java.io.Serializable) questions);
        
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

    /**
     * 字段适配器
     */
    private static class FieldAdapter extends ArrayAdapter<FieldItem> {

        public FieldAdapter(FieldConfigActivity context, List<FieldItem> items) {
            super(context, R.layout.item_field, R.id.field_name, items);
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            FieldItem item = getItem(position);
            TextView fieldNameText = view.findViewById(R.id.field_name);
            com.google.android.material.checkbox.MaterialCheckBox checkBox = view.findViewById(R.id.field_checkbox);
            
            fieldNameText.setText(item.getDisplayName());
            checkBox.setChecked(item.isSelected());
            
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                item.setSelected(isChecked);
            });
            
            return view;
        }
    }
}
