package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import com.google.android.material.button.MaterialButton;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;
import com.oilquiz.app.adapter.TemplateAdapter;
import com.oilquiz.app.model.Template;
import com.oilquiz.app.viewmodel.TemplateViewModel;

import java.util.ArrayList;
import java.util.List;

public class TemplateManagerActivity extends AppCompatActivity {

    private ListView templateListView;
    private MaterialButton batchDeleteButton;
    private MaterialButton batchEnableButton;
    private MaterialButton batchDisableButton;
    private MaterialButton batchCopyButton;
    private TemplateAdapter templateAdapter;
    private TemplateViewModel templateViewModel;
    private boolean isSelectionMode = false;
    private List<Template> selectedTemplates = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_template_manager);

        initViews();
        setupListeners();
        loadTemplates();
    }

    private void initViews() {
        templateListView = findViewById(R.id.templateListView);
        batchDeleteButton = findViewById(R.id.btn_batch_delete);
        batchEnableButton = findViewById(R.id.btn_batch_enable);
        batchDisableButton = findViewById(R.id.btn_batch_disable);
        batchCopyButton = findViewById(R.id.btn_batch_copy);
        templateViewModel = new TemplateViewModel(getApplication());
        
        // 初始化批量操作按钮状态
        batchDeleteButton.setEnabled(false);
        batchEnableButton.setEnabled(false);
        batchDisableButton.setEnabled(false);
        batchCopyButton.setEnabled(false);
        
        // 设置批量操作按钮点击事件
        batchDeleteButton.setOnClickListener(v -> batchDeleteTemplates());
        batchEnableButton.setOnClickListener(v -> batchEnableTemplates());
        batchDisableButton.setOnClickListener(v -> batchDisableTemplates());
        batchCopyButton.setOnClickListener(v -> batchCopyTemplates());
    }

    private void setupListeners() {
        templateListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Template template = (Template) parent.getItemAtPosition(position);
                if (isSelectionMode) {
                    toggleTemplateSelection(template);
                } else {
                    navigateToTemplateEdit(template);
                }
            }
        });
        
        templateListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (!isSelectionMode) {
                    // 进入选择模式
                    isSelectionMode = true;
                    selectedTemplates.clear();
                    Template template = (Template) parent.getItemAtPosition(position);
                    toggleTemplateSelection(template);
                    updateBatchButtons();
                    return true;
                }
                return false;
            }
        });
    }

    private void loadTemplates() {
        templateViewModel.getTemplates(new TemplateViewModel.GetTemplatesCallback() {
            @Override
            public void onSuccess(List<Template> templates) {
                templateAdapter = new TemplateAdapter(TemplateManagerActivity.this, templates, isSelectionMode, selectedTemplates, new TemplateAdapter.OnTemplateClickListener() {
                    @Override
                    public void onTemplateClick(Template template) {
                        if (isSelectionMode) {
                            toggleTemplateSelection(template);
                        } else {
                            navigateToTemplateEdit(template);
                        }
                    }
                    
                    @Override
                    public void onTemplateLongClick(Template template) {
                        if (!isSelectionMode) {
                            isSelectionMode = true;
                            selectedTemplates.clear();
                            toggleTemplateSelection(template);
                            updateBatchButtons();
                        }
                    }
                });
                templateListView.setAdapter(templateAdapter);
            }

            @Override
            public void onFailure(String error) {
                Toast.makeText(TemplateManagerActivity.this, "加载模板失败：" + error, Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    // 切换模板的选择状态
    private void toggleTemplateSelection(Template template) {
        if (selectedTemplates.contains(template)) {
            selectedTemplates.remove(template);
        } else {
            selectedTemplates.add(template);
        }
        templateAdapter.notifyDataSetChanged();
        updateBatchButtons();
    }
    
    // 更新批量操作按钮状态
    private void updateBatchButtons() {
        boolean hasSelection = !selectedTemplates.isEmpty();
        batchDeleteButton.setEnabled(hasSelection);
        batchEnableButton.setEnabled(hasSelection);
        batchDisableButton.setEnabled(hasSelection);
        batchCopyButton.setEnabled(hasSelection);
    }
    
    // 批量删除模板
    private void batchDeleteTemplates() {
        if (selectedTemplates.isEmpty()) {
            Toast.makeText(this, "请选择要删除的模板", Toast.LENGTH_SHORT).show();
            return;
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("确认删除");
        builder.setMessage("确定要删除选中的 " + selectedTemplates.size() + " 个模板吗？");
        builder.setPositiveButton("确定", (dialog, which) -> {
            for (Template template : selectedTemplates) {
                templateViewModel.deleteTemplate(template.getId(), new TemplateViewModel.DeleteTemplateCallback() {
                    @Override
                    public void onSuccess() {
                        // 可以添加删除成功的提示
                    }
                    
                    @Override
                    public void onFailure(String error) {
                        Toast.makeText(TemplateManagerActivity.this, "删除失败：" + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
            
            // 退出选择模式
            isSelectionMode = false;
            selectedTemplates.clear();
            updateBatchButtons();
            loadTemplates();
            Toast.makeText(this, "删除成功", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }
    
    // 批量启用模板
    private void batchEnableTemplates() {
        if (selectedTemplates.isEmpty()) {
            Toast.makeText(this, "请选择要启用的模板", Toast.LENGTH_SHORT).show();
            return;
        }
        
        for (Template template : selectedTemplates) {
            template.setEnabled(true);
            templateViewModel.updateTemplate(template, new TemplateViewModel.UpdateTemplateCallback() {
                @Override
                public void onSuccess() {
                    // 可以添加更新成功的提示
                }
                
                @Override
                public void onFailure(String error) {
                    Toast.makeText(TemplateManagerActivity.this, "启用失败：" + error, Toast.LENGTH_SHORT).show();
                }
            });
        }
        
        // 退出选择模式
        isSelectionMode = false;
        selectedTemplates.clear();
        updateBatchButtons();
        loadTemplates();
        Toast.makeText(this, "启用成功", Toast.LENGTH_SHORT).show();
    }
    
    // 批量禁用模板
    private void batchDisableTemplates() {
        if (selectedTemplates.isEmpty()) {
            Toast.makeText(this, "请选择要禁用的模板", Toast.LENGTH_SHORT).show();
            return;
        }
        
        for (Template template : selectedTemplates) {
            template.setEnabled(false);
            templateViewModel.updateTemplate(template, new TemplateViewModel.UpdateTemplateCallback() {
                @Override
                public void onSuccess() {
                    // 可以添加更新成功的提示
                }
                
                @Override
                public void onFailure(String error) {
                    Toast.makeText(TemplateManagerActivity.this, "禁用失败：" + error, Toast.LENGTH_SHORT).show();
                }
            });
        }
        
        // 退出选择模式
        isSelectionMode = false;
        selectedTemplates.clear();
        updateBatchButtons();
        loadTemplates();
        Toast.makeText(this, "禁用成功", Toast.LENGTH_SHORT).show();
    }
    
    // 批量复制模板
    private void batchCopyTemplates() {
        if (selectedTemplates.isEmpty()) {
            Toast.makeText(this, "请选择要复制的模板", Toast.LENGTH_SHORT).show();
            return;
        }
        
        for (Template template : selectedTemplates) {
            Template copy = new Template();
            copy.setName(template.getName() + " (复制)");
            copy.setDescription(template.getDescription());
            copy.setFilePath(template.getFilePath());
            copy.setEnabled(template.isEnabled());
            copy.setCreatedAt(System.currentTimeMillis());
            copy.setUpdatedAt(System.currentTimeMillis());
            
            templateViewModel.addTemplate(copy, new TemplateViewModel.AddTemplateCallback() {
                @Override
                public void onSuccess() {
                    // 可以添加复制成功的提示
                }
                
                @Override
                public void onFailure(String error) {
                    Toast.makeText(TemplateManagerActivity.this, "复制失败：" + error, Toast.LENGTH_SHORT).show();
                }
            });
        }
        
        // 退出选择模式
        isSelectionMode = false;
        selectedTemplates.clear();
        updateBatchButtons();
        loadTemplates();
        Toast.makeText(this, "复制成功", Toast.LENGTH_SHORT).show();
    }

    private void navigateToTemplateEdit(Template template) {
        Intent intent = new Intent(TemplateManagerActivity.this, EditTemplateActivity.class);
        long templateId = -1;
        if (template != null) {
            templateId = template.getId();
        }
        intent.putExtra("template_id", templateId);
        startActivity(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.template_manager_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_add_template) {
            navigateToTemplateEdit(null);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadTemplates();
    }
}