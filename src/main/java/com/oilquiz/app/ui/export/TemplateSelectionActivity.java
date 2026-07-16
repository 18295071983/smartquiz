package com.oilquiz.app.ui.export;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import com.google.android.material.button.MaterialButton;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.RadioGroup;

import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;
import com.oilquiz.app.util.export.ExportManager;
import com.oilquiz.app.util.export.template.Template;
import com.oilquiz.app.util.export.template.TemplateManager;
import com.oilquiz.app.viewmodel.TemplateViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模板选择Activity
 * 用于选择导出模板和配置导出字段
 */
public class TemplateSelectionActivity extends AppCompatActivity {

    private Spinner formatSpinner;
    private ListView templateListView;
    private MaterialButton nextButton;
    private TextView templateName;
    private TextView templateFormat;
    private TextView templateDefault;
    private TextView templateDescription;
    private TextView templateFields;
    private RadioGroup templateTypeToggle;

    private ExportManager.ExportFormat selectedFormat;
    private Template selectedTemplate;
    private com.oilquiz.app.model.Template selectedContentTemplate;
    private List<Template> templates;
    private List<com.oilquiz.app.model.Template> contentTemplates;
    private java.util.List<com.oilquiz.app.model.Question> questions;
    private boolean isContentTemplateMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_template_selection);

        // 获取Intent中的questions参数
        Intent intent = getIntent();
        questions = (java.util.List<com.oilquiz.app.model.Question>) intent.getSerializableExtra("questions");

        initViews();
        initTemplateManager();
        initFormatSpinner();
        initTemplateList();
    }

    private void initViews() {
        formatSpinner = findViewById(R.id.format_spinner);
        templateListView = findViewById(R.id.template_list);
        nextButton = findViewById(R.id.next_button);
        templateName = findViewById(R.id.template_name);
        templateFormat = findViewById(R.id.template_format);
        templateDefault = findViewById(R.id.template_default);
        templateDescription = findViewById(R.id.template_description);
        templateFields = findViewById(R.id.template_fields);
        templateTypeToggle = findViewById(R.id.template_type_toggle);

        nextButton.setOnClickListener(v -> proceedToFieldConfig());
        templateTypeToggle.setOnCheckedChangeListener((group, checkedId) -> {
            isContentTemplateMode = (checkedId == R.id.radio_content_template);
            if (isContentTemplateMode) {
                // 内容模板模式下，强制使用HTML格式
                for (int i = 0; i < ExportManager.ExportFormat.values().length; i++) {
                    if (ExportManager.ExportFormat.values()[i] == ExportManager.ExportFormat.HTML) {
                        formatSpinner.setSelection(i);
                        formatSpinner.setEnabled(false);
                        break;
                    }
                }
            } else {
                // 导出模板模式下，启用格式选择器
                formatSpinner.setEnabled(true);
            }
            updateUIForTemplateType();
            updateTemplateList();
        });
    }

    private void initTemplateManager() {
        TemplateManager templateManager = TemplateManager.getInstance();
        templateManager.init(this);
    }

    private void initFormatSpinner() {
        List<String> formats = new ArrayList<>();
        for (ExportManager.ExportFormat format : ExportManager.ExportFormat.values()) {
            formats.add(format.name());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, formats);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        formatSpinner.setAdapter(adapter);

        formatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedFormat = ExportManager.ExportFormat.values()[position];
                updateTemplateList();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void initTemplateList() {
        selectedFormat = ExportManager.ExportFormat.HTML;
        updateTemplateList();
    }

    private void updateTemplateList() {
        if (isContentTemplateMode) {
            loadContentTemplates();
        } else {
            loadExportTemplates();
        }
    }

    private void loadExportTemplates() {
        TemplateManager templateManager = TemplateManager.getInstance();
        
        // 使用当前选择的格式获取模板
        String formatKey = selectedFormat.name();
        templates = templateManager.getTemplatesByFormat(formatKey);
        
        android.util.Log.d("TemplateSelection", "Format: " + formatKey + ", Templates count: " + templates.size());

        List<String> templateNames = new ArrayList<>();
        for (Template template : templates) {
            templateNames.add(template.getName() + (template.isDefault() ? " (默认)" : ""));
            android.util.Log.d("TemplateSelection", "Template: " + template.getName() + ", Format: " + template.getFormat());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, templateNames);
        templateListView.setAdapter(adapter);

        templateListView.setOnItemClickListener((parent, view, position, id) -> {
            selectedTemplate = templates.get(position);
            selectedContentTemplate = null;
            updateTemplateDescription();
        });

        // 默认为第一个模板
        if (!templates.isEmpty()) {
            selectedTemplate = templates.get(0);
            selectedContentTemplate = null;
            updateTemplateDescription();
        } else {
            // 如果没有模板，显示提示
            selectedTemplate = null;
            selectedContentTemplate = null;
            updateTemplateDescription();
        }
    }

    private void loadContentTemplates() {
        TemplateViewModel templateViewModel = new TemplateViewModel(getApplication());
        templateViewModel.getTemplates(new TemplateViewModel.GetTemplatesCallback() {
            @Override
            public void onSuccess(List<com.oilquiz.app.model.Template> templates) {
                // 根据当前选择的导出格式过滤内容模板
                List<com.oilquiz.app.model.Template> filteredTemplates = filterTemplatesByFormat(templates, selectedFormat);
                contentTemplates = filteredTemplates;
                
                List<String> templateNames = new ArrayList<>();
                for (com.oilquiz.app.model.Template template : filteredTemplates) {
                    templateNames.add(template.getName());
                    android.util.Log.d("TemplateSelection", "Content Template: " + template.getName() + ", Description: " + template.getDescription());
                }

                ArrayAdapter<String> adapter = new ArrayAdapter<>(TemplateSelectionActivity.this, android.R.layout.simple_list_item_1, templateNames);
                templateListView.setAdapter(adapter);

                templateListView.setOnItemClickListener((parent, view, position, id) -> {
                    selectedContentTemplate = filteredTemplates.get(position);
                    selectedTemplate = null;
                    updateTemplateDescription();
                });

                // 默认为第一个模板
                if (!filteredTemplates.isEmpty()) {
                    selectedContentTemplate = filteredTemplates.get(0);
                    selectedTemplate = null;
                    updateTemplateDescription();
                } else {
                    // 如果没有模板，显示提示
                    selectedContentTemplate = null;
                    selectedTemplate = null;
                    updateTemplateDescription();
                }
            }

            @Override
            public void onFailure(String error) {
                android.util.Log.e("TemplateSelection", "Failed to load content templates: " + error);
                contentTemplates = new ArrayList<>();
                ArrayAdapter<String> adapter = new ArrayAdapter<>(TemplateSelectionActivity.this, android.R.layout.simple_list_item_1, new ArrayList<>());
                templateListView.setAdapter(adapter);
                selectedContentTemplate = null;
                updateTemplateDescription();
            }
        });
    }
    
    private List<com.oilquiz.app.model.Template> filterTemplatesByFormat(List<com.oilquiz.app.model.Template> templates, ExportManager.ExportFormat format) {
        List<com.oilquiz.app.model.Template> filtered = new ArrayList<>();
        String formatName = format.name().toLowerCase();
        
        for (com.oilquiz.app.model.Template template : templates) {
            // 根据模板名称或文件路径判断是否匹配当前格式
            String templateName = template.getName().toLowerCase();
            String templatePath = template.getFilePath() != null ? template.getFilePath().toLowerCase() : "";
            
            // 检查模板是否与当前格式匹配
            if (templateName.contains(formatName) || templatePath.contains(formatName)) {
                filtered.add(template);
            }
        }
        
        // 如果没有匹配的模板，返回所有模板
        if (filtered.isEmpty()) {
            return templates;
        }
        
        return filtered;
    }
    
    /**
     * 当切换模板类型时，更新UI显示
     */
    private void updateUIForTemplateType() {
        if (isContentTemplateMode) {
            // 内容模板模式下，更新UI提示
            templateName.setText("请选择内容模板");
            templateFormat.setText("类型: 内容模板");
            templateDescription.setText("内容模板用于生成特定格式的学习材料");
            templateFields.setText("无");
        } else {
            // 导出模板模式下，更新UI提示
            templateName.setText("请选择导出模板");
            templateFormat.setText("格式: " + selectedFormat.name());
            templateDescription.setText("导出模板用于定义导出文件的格式和布局");
            templateFields.setText("无");
        }
    }

    private void updateTemplateDescription() {
        if (selectedTemplate != null) {
            // 显示导出模板信息
            templateName.setText(selectedTemplate.getName());
            
            // 显示模板格式
            templateFormat.setText("格式: " + selectedTemplate.getFormat());
            
            // 显示是否默认模板
            if (selectedTemplate.isDefault()) {
                templateDefault.setVisibility(View.VISIBLE);
            } else {
                templateDefault.setVisibility(View.GONE);
            }
            
            // 显示模板描述
            templateDescription.setText(selectedTemplate.getDescription());
            
            // 显示包含的字段
            StringBuilder fieldsBuilder = new StringBuilder();
            if (selectedTemplate.getFields() != null && !selectedTemplate.getFields().isEmpty()) {
                Map<String, String> fieldMappings = selectedTemplate.getFieldMappings();
                for (String field : selectedTemplate.getFields()) {
                    if (fieldMappings != null && fieldMappings.containsKey(field)) {
                        fieldsBuilder.append(fieldMappings.get(field)).append("、");
                    } else {
                        fieldsBuilder.append(field).append("、");
                    }
                }
                // 移除最后的顿号
                if (fieldsBuilder.length() > 0) {
                    fieldsBuilder.deleteCharAt(fieldsBuilder.length() - 1);
                }
            } else {
                fieldsBuilder.append("无");
            }
            templateFields.setText(fieldsBuilder.toString());
        } else if (selectedContentTemplate != null) {
            // 显示内容模板信息
            templateName.setText(selectedContentTemplate.getName());
            templateFormat.setText("类型: 内容模板");
            templateDefault.setVisibility(View.GONE);
            templateDescription.setText(selectedContentTemplate.getDescription());
            templateFields.setText("文件: " + selectedContentTemplate.getFilePath());
        } else {
            // 重置所有字段
            templateName.setText("模板名称");
            templateFormat.setText("格式: 无");
            templateDefault.setVisibility(View.GONE);
            templateDescription.setText("请选择一个模板");
            templateFields.setText("无");
        }
    }

    private void proceedToFieldConfig() {
        if (isContentTemplateMode) {
            // 处理内容模板
            if (selectedContentTemplate == null) {
                // 如果没有选择内容模板，显示提示
                android.widget.Toast.makeText(this, "请选择一个内容模板", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(this, FieldConfigActivity.class);
            intent.putExtra("format", selectedFormat.name());
            intent.putExtra("contentTemplateId", selectedContentTemplate.getId());
            intent.putExtra("contentTemplateName", selectedContentTemplate.getName());
            intent.putExtra("contentTemplateFilePath", selectedContentTemplate.getFilePath());
            intent.putExtra("questions", (java.io.Serializable) questions);
            startActivity(intent);
        } else {
            // 处理导出模板
            if (selectedTemplate == null) {
                // 如果没有选择模板，使用默认模板
                TemplateManager templateManager = TemplateManager.getInstance();
                selectedTemplate = templateManager.getDefaultTemplate(selectedFormat.name());
            }


                Intent intent = new Intent(this, FieldConfigActivity.class);
                intent.putExtra("format", selectedFormat.name());
                intent.putExtra("templateId", selectedTemplate != null ? selectedTemplate.getId() : null);
                intent.putExtra("questions", (java.io.Serializable) questions);
                startActivity(intent);
        }
    }
}
