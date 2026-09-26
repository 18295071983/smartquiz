package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.net.Uri;
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

    private static final int REQUEST_OPEN_OFFICE = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_toolbox);

        // 设置标题
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(R.string.h_d1f4a2ca));
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
        // Office文档：选文件用官方查看器；Office界面：官方查看器原始浏览器界面；Pdfium：PDF；文件渲染：通用渲染
        String[] toolNames = {"Office文档", "Office界面", "Pdfium文件预览", "文件渲染"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.h_74e7f8c2))
                .setItems(toolNames, (dialog, which) -> {
                    switch (which) {
                        case 0: openOfficeDocument(); break;
                        case 1: openOfficialViewerUI(); break;
                        case 2: startActivity(new Intent(this, PdfiumPreviewActivity.class)); break;
                        case 3: startActivity(new Intent(this, FileRenderActivity.class)); break;
                    }
                })
                .setNegativeButton(getString(R.string.h_625fb26b), null)
                .show();
    }

    /** 打开官方 LibreOffice 查看器的原始界面（文件浏览/最近文件）。 */
    private void openOfficialViewerUI() {
        try {
            Intent intent = new Intent();
            intent.setClassName(getPackageName(), "org.libreoffice.ui.LibreOfficeUIActivity");
            startActivity(intent);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, getString(R.string.h_521f7d28) + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 打开 Office 文档：文件选择后交给集成官方查看器渲染。 */
    private void openOfficeDocument() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");   // 需配合 EXTRA_MIME_TYPES 才能按类型过滤
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/msword",
                "application/vnd.ms-excel",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.oasis.opendocument.text",
                "application/vnd.oasis.opendocument.spreadsheet",
                "application/vnd.oasis.opendocument.presentation",
                "text/csv",
                "application/rtf"
            });
            startActivityForResult(intent, REQUEST_OPEN_OFFICE);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, getString(R.string.h_ac1d1bd3) + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPEN_OFFICE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    Intent intent = new Intent();
                    intent.setClassName(getPackageName(), "org.libreoffice.LibreOfficeMainActivity");
                    intent.setDataAndType(uri, data.getType() != null ? data.getType() : "*/*");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(intent);
                } catch (Exception e) {
                    android.widget.Toast.makeText(this, getString(R.string.h_8a322723) + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void showAITools() {
        showToolDialog("AI工具", new String[]{
            "AI聊天",
            "AI中心",
            "文字识别",
            "AI服务状态"
        }, new Class<?>[]{
            AIChatActivity.class,
            AICenterActivity.class,
            OCRActivity.class,
            AIServiceStatusActivity.class
        });
    }

    private void showQuestionTools() {
        showToolDialog("题目工具", new String[]{
            "题目管理"
        }, new Class<?>[]{
            QuestionActivity.class
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
            "命令路由（Linux 工具箱）"
        }, new Class<?>[]{
            ThemeActivity.class,
            LanguageActivity.class,
            BackupActivity.class,
            LinuxRouteActivity.class
        });
    }

    private void showOtherTools() {
        String[] names = {"API配置", "环境检查", "系统日志", "天气详情"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.h_77d7cc1c))
                .setItems(names, (dialog, which) -> {
                    switch (which) {
                        case 0: startActivity(new Intent(this, ApiConfigActivity.class)); break;
                        case 1: startActivity(new Intent(this, EnvironmentCheckActivity.class)); break;
                        case 2: startActivity(new Intent(this, LogsActivity.class)); break;
                        case 3: openWeatherDetail(); break;
                    }
                })
                .setNegativeButton(getString(R.string.h_625fb26b), null)
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
            android.widget.Toast.makeText(this, getString(R.string.h_8a9e576d) + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
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
        builder.setNegativeButton(getString(R.string.h_625fb26b), null);
        builder.show();
    }
}