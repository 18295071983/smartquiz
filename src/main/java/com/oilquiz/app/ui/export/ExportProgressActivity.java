package com.oilquiz.app.ui.export;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import com.google.android.material.button.MaterialButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.ExportManager;

import java.io.File;
import java.util.List;

/**
 * 导出进度Activity
 * 用于显示导出过程的进度和结果
 */
public class ExportProgressActivity extends AppCompatActivity {

    private com.google.android.material.progressindicator.CircularProgressIndicator progressIndicator;
    private TextView progressText;
    private MaterialButton finishButton;
    private MaterialButton viewFileButton;
    private MaterialButton shareFileButton;

    private ExportManager.ExportConfig config;
    private List<Question> questions;
    private File exportedFile;
    private long contentTemplateId;
    private String contentTemplateName;
    private String contentTemplateFilePath;
    private boolean isContentTemplateMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export_progress);

        initViews();
        getIntentData();
        startExport();
    }

    private void initViews() {
        progressIndicator = findViewById(R.id.progress_indicator);
        progressText = findViewById(R.id.progress_text);
        finishButton = findViewById(R.id.finish_button);
        viewFileButton = findViewById(R.id.view_file_button);
        shareFileButton = findViewById(R.id.share_file_button);

        finishButton.setOnClickListener(v -> finish());
        viewFileButton.setOnClickListener(v -> openFile(exportedFile));
        shareFileButton.setOnClickListener(v -> shareFile(exportedFile));
        finishButton.setVisibility(View.GONE);
        viewFileButton.setVisibility(View.GONE);
        shareFileButton.setVisibility(View.GONE);
    }

    private void getIntentData() {
        Intent intent = getIntent();
        config = (ExportManager.ExportConfig) intent.getSerializableExtra("config");
        // 这里应该从Intent中获取questions，或者从全局状态中获取
        // 暂时使用空列表，实际应用中需要传递真实的问题列表
        questions = (List<Question>) intent.getSerializableExtra("questions");
        
        // 检查是否是内容模板
        if (intent.hasExtra("isContentTemplateMode")) {
            isContentTemplateMode = intent.getBooleanExtra("isContentTemplateMode", false);
            contentTemplateId = intent.getLongExtra("contentTemplateId", 0);
            contentTemplateName = intent.getStringExtra("contentTemplateName");
            contentTemplateFilePath = intent.getStringExtra("contentTemplateFilePath");
        }
    }

    private void startExport() {
        progressText.setText("开始导出...");

        // 设置内容模板信息到配置中
        if (isContentTemplateMode) {
            config.setContentTemplateId(contentTemplateId);
            config.setContentTemplateName(contentTemplateName);
            config.setContentTemplateFilePath(contentTemplateFilePath);
            config.setContentTemplateMode(true);
        }

        // 启动导出任务
        ExportManager.getInstance().startExport(new ExportManager.ExportTask() {
            {
                setConfig(config);
                setQuestions(questions != null ? questions : new java.util.ArrayList<>());
                setContext(ExportProgressActivity.this);
                setCallback(new ExportManager.ExportCallback() {
                    @Override
                    public void onExportStart() {
                        runOnUiThread(() -> {
                            progressText.setText("导出中...");
                            progressIndicator.setVisibility(View.VISIBLE);
                        });
                    }

                    @Override
                    public void onExportProgress(int progress) {
                        runOnUiThread(() -> {
                            progressText.setText("导出中... " + progress + "%");
                        });
                    }

                    @Override
                    public void onExportComplete(File file) {
                        exportedFile = file;
                        runOnUiThread(() -> {
                            progressIndicator.setVisibility(View.GONE);
                            progressText.setText("导出完成！文件已保存到：" + file.getAbsolutePath());
                            finishButton.setVisibility(View.VISIBLE);
                            viewFileButton.setVisibility(View.VISIBLE);
                            shareFileButton.setVisibility(View.VISIBLE);
                        });
                    }

                    @Override
                    public void onExportError(String error) {
                        runOnUiThread(() -> {
                            progressIndicator.setVisibility(View.GONE);
                            progressText.setText("导出失败：" + error);
                            finishButton.setVisibility(View.VISIBLE);
                        });
                    }
                });
            }
        });
    }

    /**
     * 打开文件
     */
    private void openFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            android.net.Uri uri;
            
            // 使用FileProvider创建Uri，避免FileUriExposedException
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "com.oilquiz.app.fileprovider",
                    file
                );
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                // 旧版本Android使用传统方式
                uri = android.net.Uri.fromFile(file);
            }
            
            String mimeType = getMimeType(file.getAbsolutePath());
            intent.setDataAndType(uri, mimeType);
            
            // 确保有应用可以处理此Intent
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(Intent.createChooser(intent, "选择打开方式"));
            } else {
                android.widget.Toast.makeText(this, "没有找到可以打开此文件的应用", android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "无法打开文件: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    /**
     * 获取文件MIME类型
     */
    private String getMimeType(String filePath) {
        String extension = filePath.substring(filePath.lastIndexOf(".") + 1).toLowerCase();
        switch (extension) {
            case "csv":
                return "text/csv";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls":
                return "application/vnd.ms-excel";
            case "pdf":
                return "application/pdf";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "doc":
                return "application/msword";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "ppt":
                return "application/vnd.ms-powerpoint";
            case "html":
                return "text/html";
            case "md":
                return "text/markdown";
            case "json":
                return "application/json";
            default:
                return "application/octet-stream";
        }
    }

    /**
     * 分享文件
     */
    private void shareFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            android.net.Uri uri;
            
            // 使用FileProvider创建Uri，避免FileUriExposedException
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "com.oilquiz.app.fileprovider",
                    file
                );
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                // 旧版本Android使用传统方式
                uri = android.net.Uri.fromFile(file);
            }
            
            String mimeType = getMimeType(file.getAbsolutePath());
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, "导出文件");
            intent.putExtra(Intent.EXTRA_TEXT, "这是从OilQuiz应用导出的文件：" + file.getName());
            
            // 确保有应用可以处理此Intent
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(Intent.createChooser(intent, "分享文件"));
            } else {
                android.widget.Toast.makeText(this, "没有找到可以分享此文件的应用", android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "无法分享文件: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }
}
