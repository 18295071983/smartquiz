package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.view.View;
import com.google.android.material.button.MaterialButton;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;
import com.oilquiz.app.model.Template;
import com.oilquiz.app.viewmodel.TemplateViewModel;

public class EditTemplateActivity extends AppCompatActivity {

    private EditText nameEditText;
    private EditText descriptionEditText;
    private EditText filePathEditText;
    private MaterialButton saveButton;
    private MaterialButton cancelButton;

    private TemplateViewModel templateViewModel;
    private long templateId;
    private Template template;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_template);

        initViews();
        setupListeners();
        loadTemplate();
    }

    private void initViews() {
        nameEditText = findViewById(R.id.nameEditText);
        descriptionEditText = findViewById(R.id.descriptionEditText);
        filePathEditText = findViewById(R.id.filePathEditText);
        saveButton = findViewById(R.id.saveButton);
        cancelButton = findViewById(R.id.cancelButton);

        templateViewModel = new TemplateViewModel(getApplication());
        templateId = getIntent().getLongExtra("template_id", -1);
    }

    private void setupListeners() {
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveTemplate();
            }
        });

        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void loadTemplate() {
        if (templateId != -1) {
            templateViewModel.getTemplateById(templateId, new TemplateViewModel.GetTemplateCallback() {
                @Override
                public void onSuccess(Template template) {
                    EditTemplateActivity.this.template = template;
                    populateFields(template);
                }

                @Override
                public void onFailure(String error) {
                    // 处理错误
                }
            });
        } else {
            template = new Template();
        }
    }

    private void populateFields(Template template) {
        nameEditText.setText(template.getName());
        descriptionEditText.setText(template.getDescription());
        filePathEditText.setText(template.getFilePath());
    }

    private void saveTemplate() {
        template.setName(nameEditText.getText().toString());
        template.setDescription(descriptionEditText.getText().toString());
        template.setFilePath(filePathEditText.getText().toString());
        template.setUpdatedAt(System.currentTimeMillis());

        if (templateId != -1) {
            templateViewModel.updateTemplate(template, new TemplateViewModel.UpdateTemplateCallback() {
                @Override
                public void onSuccess() {
                    finish();
                }

                @Override
                public void onFailure(String error) {
                    // 处理错误
                }
            });
        } else {
            template.setCreatedAt(System.currentTimeMillis());
            templateViewModel.addTemplate(template, new TemplateViewModel.AddTemplateCallback() {
                @Override
                public void onSuccess() {
                    finish();
                }

                @Override
                public void onFailure(String error) {
                    // 处理错误
                }
            });
        }
    }
}