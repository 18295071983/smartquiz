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

        // 题目列表从内存持有器获取（Intent 序列化大列表会抛 TransactionTooLargeException）
        questions = ExportQuestionsHolder.get();

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

        // 简化：隐藏内容模板切换（内容模板体系与导出模板分离，避免双模板流程混乱），
        // 本页专注导出模板（场景化模板）选择
        if (templateTypeToggle != null) {
            templateTypeToggle.setVisibility(View.GONE);
        }
        isContentTemplateMode = false;

        nextButton.setOnClickListener(v -> proceedToFieldConfig());
    }

    private void initTemplateManager() {
        TemplateManager templateManager = TemplateManager.getInstance();
        templateManager.init(this);
    }

    /** 可用导出格式（与导出页 ConfigManager 的 9 种一致；删除无 UI 入口/无实际用途的
     *  ENHANCED_HTML/PYTHON/JAVA） */
    private static final ExportManager.ExportFormat[] AVAILABLE_FORMATS = {
            ExportManager.ExportFormat.CSV, ExportManager.ExportFormat.EXCEL,
            ExportManager.ExportFormat.PDF, ExportManager.ExportFormat.WORD,
            ExportManager.ExportFormat.HTML, ExportManager.ExportFormat.MARKDOWN,
            ExportManager.ExportFormat.JSON, ExportManager.ExportFormat.LONG_IMAGE,
            ExportManager.ExportFormat.WEBVIEW_APK
    };

    private void initFormatSpinner() {
        // 中文标签展示（position 对应 AVAILABLE_FORMATS 下标）
        List<String> formats = new ArrayList<>();
        for (ExportManager.ExportFormat format : AVAILABLE_FORMATS) {
            formats.add(formatLabel(format));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, formats);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        formatSpinner.setAdapter(adapter);

        formatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < AVAILABLE_FORMATS.length) {
                    selectedFormat = AVAILABLE_FORMATS[position];
                    updateTemplateList();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** 导出格式中文标签 */
    private String formatLabel(ExportManager.ExportFormat format) {
        switch (format) {
            case CSV: return "CSV";
            case EXCEL: return "Excel";
            case PDF: return "PDF";
            case WORD: return "Word";
            case HTML: return "HTML";
            case ENHANCED_HTML: return "增强HTML";
            case MARKDOWN: return "Markdown";
            case JSON: return "JSON";
            case LONG_IMAGE: return "长图片";
            case WEBVIEW_APK: return "WebView应用";
            case PYTHON: return "Python";
            case JAVA: return "Java";
            default: return format.name();
        }
    }

    private void initTemplateList() {
        // 兜底：Spinner setAdapter 后不保证立即触发 onItemSelected
        // （已有默认选中项时可能不回调），selectedFormat 为 null 会导致
        // loadExportTemplates 里 selectedFormat.name() 空指针
        if (selectedFormat == null) {
            selectedFormat = AVAILABLE_FORMATS[0];
        }
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
            templateName.setText(getString(R.string.h_395c7442));
            templateFormat.setText(getString(R.string.h_c3f9cc1b));
            templateDescription.setText(getString(R.string.h_dc5c8ec2));
            templateFields.setText("无");
        } else {
            // 导出模板模式下，更新UI提示
            templateName.setText(getString(R.string.h_50e5250f));
            templateFormat.setText(getString(R.string.h_b4ad854c) + selectedFormat.name());
            templateDescription.setText(getString(R.string.h_294b3945));
            templateFields.setText("无");
        }
    }

    private void updateTemplateDescription() {
        if (selectedTemplate != null) {
            // 显示导出模板信息
            templateName.setText(selectedTemplate.getName());
            
            // 显示模板格式
            templateFormat.setText(getString(R.string.h_b4ad854c) + selectedTemplate.getFormat());
            
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
            templateFormat.setText(getString(R.string.h_c3f9cc1b));
            templateDefault.setVisibility(View.GONE);
            templateDescription.setText(selectedContentTemplate.getDescription());
            templateFields.setText(getString(R.string.h_9b7536df) + selectedContentTemplate.getFilePath());
        } else {
            // 重置所有字段
            templateName.setText(getString(R.string.h_a5d1c511));
            templateFormat.setText(getString(R.string.h_dfda394d));
            templateDefault.setVisibility(View.GONE);
            templateDescription.setText(getString(R.string.h_8c289710));
            templateFields.setText("无");
        }
    }

    private void proceedToFieldConfig() {
        if (isContentTemplateMode) {
            // 处理内容模板
            if (selectedContentTemplate == null) {
                // 如果没有选择内容模板，显示提示
                android.widget.Toast.makeText(this, getString(R.string.h_4f51ac6e), android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(this, FieldConfigActivity.class);
            intent.putExtra("format", selectedFormat.name());
            intent.putExtra("contentTemplateId", selectedContentTemplate.getId());
            intent.putExtra("contentTemplateName", selectedContentTemplate.getName());
            intent.putExtra("contentTemplateFilePath", selectedContentTemplate.getFilePath());
            // questions 继续由 ExportQuestionsHolder 持有，不再放入 Intent
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
                // questions 继续由 ExportQuestionsHolder 持有，不再放入 Intent
                startActivity(intent);
        }
    }
}
