package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.model.ModelComparisonActivity;
import com.oilquiz.app.ai.model.ModelSelectionActivity;
import com.oilquiz.app.ui.export.FieldConfigActivity;
import com.oilquiz.app.ui.export.ExportProgressActivity;
import com.oilquiz.app.ui.export.TemplateSelectionActivity;

public class ToolboxActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_toolbox);

        // 设置标题
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("工具集");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        setupCategoryListeners();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void setupCategoryListeners() {
        // 数据库相关
        findViewById(R.id.card_database).setOnClickListener(v -> showDatabaseTools());
        
        // 导入导出相关
        findViewById(R.id.card_import_export).setOnClickListener(v -> showImportExportTools());
        
        // 文件预览相关
        findViewById(R.id.card_file_preview).setOnClickListener(v -> showFilePreviewTools());
        
        // AI相关
        findViewById(R.id.card_ai).setOnClickListener(v -> showAITools());
        
        // 题目相关
        findViewById(R.id.card_question).setOnClickListener(v -> showQuestionTools());
        
        // 其他功能
        findViewById(R.id.card_other).setOnClickListener(v -> showOtherTools());
    }

    private void showDatabaseTools() {
        showToolDialog("数据库工具", new String[]{
            "数据库详情"
        }, new Class<?>[]{
            DatabaseDetailActivity.class
        });
    }

    private void showImportExportTools() {
        showToolDialog("导入导出工具", new String[]{
            "导入指南"
        }, new Class<?>[]{
            ImportGuideActivity.class
        });
    }

    private void showFilePreviewTools() {
        String[] toolNames = {
            "TBS文件预览",
            "Pdfium文件预览"
        };

        Class<?>[] activities = {
            TBSFilePreviewActivity.class,
            PdfiumPreviewActivity.class
        };

        showToolDialog("文件预览工具", toolNames, activities);
    }

    private void showAITools() {
        showToolDialog("AI工具", new String[]{
            "AI聊天",
            "Agent聊天"
        }, new Class<?>[]{
            AIChatActivity.class,
            AgentChatActivity.class
        });
    }

    private void showQuestionTools() {
        showToolDialog("题目工具", new String[]{
            "题库管理"
        }, new Class<?>[]{
            QuestionBankActivity.class
        });
    }

    private void showOtherTools() {
        showToolDialog("其他工具", new String[]{
            "API配置",
            "文件渲染"
        }, new Class<?>[]{
            ApiConfigActivity.class,
            FileRenderActivity.class
        });
    }

    private void showToolDialog(String title, String[] toolNames, Class<?>[] activities) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setItems(toolNames, (dialog, which) -> {
            if (which < activities.length) {
                startActivity(new Intent(this, activities[which]));
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }
}