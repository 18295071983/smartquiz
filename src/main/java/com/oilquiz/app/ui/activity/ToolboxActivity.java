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

        // 学习工具
        findViewById(R.id.card_study).setOnClickListener(v -> showStudyTools());

        // 设置与数据
        findViewById(R.id.card_settings).setOnClickListener(v -> showSettingsDataTools());

        // 数据与维护
        findViewById(R.id.card_maintenance).setOnClickListener(v -> showMaintenanceTools());

        // 开发者工具
        findViewById(R.id.card_dev).setOnClickListener(v -> showDevTools());
        
        // 其他功能
        findViewById(R.id.card_other).setOnClickListener(v -> showOtherTools());
    }

    private void showDatabaseTools() {
        showToolDialog("数据库工具", new String[]{
            "数据库详情",
            "数据库管理"
        }, new Class<?>[]{
            DatabaseDetailActivity.class,
            DatabaseManagementActivity.class
        });
    }

    private void showImportExportTools() {
        showToolDialog("导入导出工具", new String[]{
            "导入指南",
            "导入",
            "导出"
        }, new Class<?>[]{
            ImportGuideActivity.class,
            ImportActivity.class,
            ExportActivity.class
        });
    }

    private void showFilePreviewTools() {
        String[] toolNames = {
            "TBS文件预览",
            "Pdfium文件预览",
            "通用文件预览"
        };

        Class<?>[] activities = {
            TBSFilePreviewActivity.class,
            PdfiumPreviewActivity.class,
            SimpleFilePreviewActivity.class
        };

        showToolDialog("文件预览工具", toolNames, activities);
    }

    private void showAITools() {
        showToolDialog("AI工具", new String[]{
            "AI聊天",
            "AI中心",
            "文字识别",
            "模型管理",
            "AI服务状态",
            "AI学习助手",
            "AI翻译",
            "题目分析",
            "AI出题",
            "AI生图/视频",
            "性能监控",
            "Agent管理",
            "AI日志查看"
        }, new Class<?>[]{
            AIChatActivity.class,
            AICenterActivity.class,
            OCRActivity.class,
            ModelSelectorActivity.class,
            AIServiceStatusActivity.class,
            LearningAssistantActivity.class,
            TranslateActivity.class,
            QuestionAnalyzeActivity.class,
            QuestionGenerateActivity.class,
            MediaGenActivity.class,
            PerformanceActivity.class,
            AgentManagerActivity.class,
            LogViewerActivity.class
        });
    }

    private void showQuestionTools() {
        showToolDialog("题目工具", new String[]{
            "题库管理",
            "题目修复",
            "模板管理",
            "字段管理",
            "智能映射",
            "映射编辑"
        }, new Class<?>[]{
            QuestionBankActivity.class,
            QuestionRepairActivity.class,
            TemplateManagerActivity.class,
            FieldManagementActivity.class,
            SmartMappingActivity.class,
            MappingEditorActivity.class
        });
    }

    private void showStudyTools() {
        showToolDialog("学习工具", new String[]{
            "学习笔记",
            "学习计划",
            "错题集",
            "学习历史"
        }, new Class<?>[]{
            NoteActivity.class,
            StudyPlanActivity.class,
            WrongQuestionActivity.class,
            HistoryActivity.class
        });
    }

    private void showSettingsDataTools() {
        showToolDialog("设置与数据", new String[]{
            "主题设置",
            "语言设置",
            "数据备份",
            "主题颜色",
            "主题切换",
            "用户",
            "设备信息"
        }, new Class<?>[]{
            ThemeActivity.class,
            LanguageActivity.class,
            BackupActivity.class,
            ThemeColorActivity.class,
            com.oilquiz.app.ui.ThemeSwitcherActivity.class,
            UserActivity.class,
            DeviceInfoActivity.class
        });
    }

    private void showMaintenanceTools() {
        showToolDialog("数据与维护", new String[]{
            "数据修复",
            "模型导入",
            "AI导入"
        }, new Class<?>[]{
            DataIssueFixActivity.class,
            ModelImportActivity.class,
            AIImportActivity.class
        });
    }

    private void showDevTools() {
        showToolDialog("开发者工具", new String[]{
            "测试",
            "JWT测试",
            "Tokenizer演示",
            "AI图标演示"
        }, new Class<?>[]{
            TestActivity.class,
            JwtTestActivity.class,
            TokenizerDemoActivity.class,
            AIIconDemoActivity.class
        });
    }

    private void showOtherTools() {
        String[] names = {"API配置", "文件渲染", "环境检查", "系统日志", "天气详情"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("其他工具")
                .setItems(names, (dialog, which) -> {
                    switch (which) {
                        case 0: startActivity(new Intent(this, ApiConfigActivity.class)); break;
                        case 1: startActivity(new Intent(this, FileRenderActivity.class)); break;
                        case 2: startActivity(new Intent(this, EnvironmentCheckActivity.class)); break;
                        case 3: startActivity(new Intent(this, LogsActivity.class)); break;
                        case 4: openWeatherDetail(); break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 打开天气详情（与主界面"天气详情"按钮一致：qweather 网页预览） */
    private void openWeatherDetail() {
        try {
            Intent intent = new Intent(this, com.oilquiz.app.WebViewActivity.class);
            intent.putExtra("url", "https://www.qweather.com");
            intent.putExtra("title", getString(R.string.weather_detail_title));
            startActivity(intent);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "打开天气详情失败: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
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