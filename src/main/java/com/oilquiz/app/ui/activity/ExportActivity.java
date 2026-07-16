package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import com.google.android.material.button.MaterialButton;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.content.DialogInterface;
import android.net.Uri;
import java.io.File;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.oilquiz.app.R;
import com.oilquiz.app.manager.ConfigManager;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.SystemUIResourceAdapter;

import com.oilquiz.app.util.export.ExportManager;
import com.oilquiz.app.util.export.ExportUtils;
import com.oilquiz.app.viewmodel.QuestionViewModel;

import java.util.List;
import java.util.Map;

public class ExportActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_STORAGE_PERMISSION = 1001;

    private QuestionViewModel questionViewModel;
    private Spinner spinnerExportFormat;
    private MaterialButton btnSelectFields;
    private MaterialButton btnStartExport;
    private MaterialButton btnSelectTemplate;
    private CheckBox cbIncludeAnswers;
    private CheckBox cbIncludeExplanations;
    private CheckBox cbIncludeDifficulty;
    private TextView tvSelectedFields;
    private TextView tvQuestionCount;
    private TextView tvExportDirectory;
    private List<String> selectedFields;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 应用系统UI主题
        SystemUIResourceAdapter uiAdapter = SystemUIResourceAdapter.getInstance(this);
        uiAdapter.applySystemTheme(this);
        
        setContentView(R.layout.activity_export);

        questionViewModel = new ViewModelProvider(this).get(QuestionViewModel.class);

        // 初始化UI组件
        spinnerExportFormat = findViewById(R.id.spinner_export_format);
        btnSelectFields = findViewById(R.id.btn_select_fields);
        btnStartExport = findViewById(R.id.btn_start_export);
        btnSelectTemplate = findViewById(R.id.btn_select_template);
        cbIncludeAnswers = findViewById(R.id.cb_include_answers);
        cbIncludeExplanations = findViewById(R.id.cb_include_explanations);
        cbIncludeDifficulty = findViewById(R.id.cb_include_difficulty);
        tvSelectedFields = findViewById(R.id.tv_selected_fields);
        tvQuestionCount = findViewById(R.id.textQuestionCount);
        tvExportDirectory = findViewById(R.id.tvExportDirectory);



        // 初始化导出格式选择器
        initExportFormatSpinner();

        // 初始化选中字段（默认全选）
        selectedFields = ExportUtils.getQuestionFields();
        updateSelectedFieldsText();
        
        // 加载题目数量
        loadQuestionCount();
        
        // 显示导出目录
        displayExportDirectory();

        // 设置选择字段按钮点击事件
        btnSelectFields.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFieldSelectionDialog();
            }
        });

        // 设置选择模板按钮点击事件
        btnSelectTemplate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 获取题目数据
                questionViewModel.getQuestions(new QuestionViewModel.GetQuestionsCallback() {
                    @Override
                    public void onSuccess(List<Question> questions) {
                        if (questions != null && !questions.isEmpty()) {
                            Intent intent = new Intent(ExportActivity.this, com.oilquiz.app.ui.export.TemplateSelectionActivity.class);
                            intent.putExtra("questions", (java.io.Serializable) questions);
                            startActivity(intent);
                        } else {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(ExportActivity.this, "没有题目可导出", Toast.LENGTH_SHORT).show();
                                }
                            });
                            return;
                        }
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(ExportActivity.this, "获取题目失败：" + error, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
            }
        });

        // 设置开始导出按钮点击事件
        btnStartExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startExport();
            }
        });
    }

    /**
     * 初始化导出格式选择器
     */
    private void initExportFormatSpinner() {
        // 获取导出格式列表
        ConfigManager configManager = ConfigManager.getInstance(this);
        List<Map<String, String>> exportFormats = configManager.getExportFormats();

        // 提取格式标签
        List<String> formatLabels = new java.util.ArrayList<>();
        for (Map<String, String> format : exportFormats) {
            formatLabels.add(format.get("label"));
        }

        // 创建适配器
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, formatLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerExportFormat.setAdapter(adapter);
    }

    /**
     * 显示字段选择对话框
     */
    private void showFieldSelectionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择导出字段");

        // 获取所有字段
        List<String> fields = ExportUtils.getQuestionFields();
        boolean[] checkedItems = new boolean[fields.size()];

        // 设置默认选中状态
        for (int i = 0; i < fields.size(); i++) {
            checkedItems[i] = selectedFields.contains(fields.get(i));
        }

        // 创建复选框列表
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);

        final List<String> tempSelectedFields = new java.util.ArrayList<>(selectedFields);

        for (int i = 0; i < fields.size(); i++) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(ExportUtils.getFieldDisplayName(fields.get(i)));
            checkBox.setChecked(checkedItems[i]);
            checkBox.setTag(fields.get(i));
            layout.addView(checkBox);
        }

        builder.setView(layout);

        builder.setPositiveButton("确定", (dialog, which) -> {
            // 收集选中的字段
            tempSelectedFields.clear();
            for (int i = 0; i < layout.getChildCount(); i++) {
                CheckBox checkBox = (CheckBox) layout.getChildAt(i);
                if (checkBox.isChecked()) {
                    tempSelectedFields.add((String) checkBox.getTag());
                }
            }

            if (tempSelectedFields.isEmpty()) {
                Toast.makeText(ExportActivity.this, "请至少选择一个字段", Toast.LENGTH_SHORT).show();
                return;
            }

            selectedFields = tempSelectedFields;
            updateSelectedFieldsText();
        });

        builder.setNegativeButton("取消", (dialog, which) -> dialog.dismiss());

        builder.show();
    }

    /**
     * 更新选中字段文本
     */
    private void updateSelectedFieldsText() {
        if (selectedFields.size() == ExportUtils.getQuestionFields().size()) {
            tvSelectedFields.setText("已选择所有字段");
        } else {
            tvSelectedFields.setText("已选择 " + selectedFields.size() + " 个字段");
        }
    }

    /**
     * 开始导出
     */
    private void startExport() {
        // 检查存储权限
        checkStoragePermissionAndStartExport();
    }

    /**
     * 检查存储权限并开始导出
     */
    private void checkStoragePermissionAndStartExport() {
        AppResourceManager resources = AppResourceManager.getInstance(this);
        if (resources.hasStoragePermission()) {
            proceedWithExport();
        } else {
            resources.permissions().requestStoragePermission(this);
        }
    }

    /**
     * 继续执行导出操作
     */
    private void proceedWithExport() {
        // 获取选择的导出格式
        String selectedFormatLabel = (String) spinnerExportFormat.getSelectedItem();
        ConfigManager configManager = ConfigManager.getInstance(this);
        List<Map<String, String>> exportFormats = configManager.getExportFormats();

        String formatValue = null;
        for (Map<String, String> format : exportFormats) {
            if (format.get("label").equals(selectedFormatLabel)) {
                formatValue = format.get("value");
                break;
            }
        }

        if (formatValue == null) {
            Toast.makeText(this, "请选择导出格式", Toast.LENGTH_SHORT).show();
            return;
        }

        ExportManager.ExportFormat exportFormat = ExportManager.ExportFormat.valueOf(formatValue);

        // 获取题目数据
        questionViewModel.getQuestions(new QuestionViewModel.GetQuestionsCallback() {
            @Override
            public void onSuccess(List<Question> questions) {
                if (questions != null && !questions.isEmpty()) {
                    exportQuestions(questions, exportFormat);
                } else {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(ExportActivity.this, "没有题目可导出", Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }
            }

            @Override
            public void onError(String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(ExportActivity.this, "获取题目失败：" + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void exportQuestions(List<Question> questions, ExportManager.ExportFormat format) {
        // 创建导出配置
        ExportManager.ExportConfig config = new ExportManager.ExportConfig();
        config.setFormat(format);
        config.setIncludeAnswers(cbIncludeAnswers.isChecked());
        config.setIncludeExplanations(cbIncludeExplanations.isChecked());
        config.setIncludeDifficulty(cbIncludeDifficulty.isChecked());
        config.setSelectedFields(selectedFields);

        // 根据导出格式选择对话框布局
        View progressView;
        AlertDialog.Builder progressBuilder = new AlertDialog.Builder(ExportActivity.this);
        ProgressBar progressBar;
        TextView progressText;
        final TextView[] exportLog = new TextView[1];
        final ScrollView[] logScrollView = new ScrollView[1];
        
        // 动态生成普通进度对话框布局
        progressBuilder.setTitle("导出中");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);
        
        // 添加进度条
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        LinearLayout.LayoutParams progressBarParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.addView(progressBar, progressBarParams);
        
        // 添加进度文本
        progressText = new TextView(this);
        progressText.setText("导出中...");
        progressText.setTextSize(16);
        progressText.setTextColor(SystemUIResourceAdapter.getInstance(this).getTextPrimaryColor());
        progressText.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams progressTextParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressTextParams.setMargins(0, 16, 0, 0);
        layout.addView(progressText, progressTextParams);
        
        progressView = layout;
        
        progressBar.setMax(100);
        progressBuilder.setView(progressView);
        progressBuilder.setCancelable(false);
        // 添加关闭按钮
        progressBuilder.setNegativeButton("关闭", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        AlertDialog progressDialog = progressBuilder.create();
        
        // 创建导出任务
        ExportManager.ExportTask task = new ExportManager.ExportTask();
        task.setConfig(config);
        task.setQuestions(questions);
        task.setContext(ExportActivity.this);
        task.setCallback(new ExportManager.ExportCallback() {
            @Override
            public void onExportStart() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        progressDialog.show();
                    }
                });
            }

            @Override
            public void onExportProgress(int progress) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        progressBar.setProgress(progress);
                    }
                });
            }

            @Override
            public void onExportLog(String message) {
                // 不需要处理日志
            }

            @Override
            public void onExportComplete(File file) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        progressDialog.dismiss();
                        showExportCompleteDialog(file);
                    }
                });
            }

            @Override
            public void onExportError(String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        progressDialog.dismiss();
                        Toast.makeText(ExportActivity.this, "导出失败：" + error, Toast.LENGTH_LONG).show();
                    }
                });
            }


        });

        // 开始导出
        ExportManager.getInstance().startExport(task);
    }

    /**
     * 显示导出完成对话框
     */
    private void showExportCompleteDialog(File file) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("导出完成");
        builder.setMessage("导出成功！\n\n文件路径: " + file.getPath() + "\n文件大小: " + (file.length() / 1024) + " KB");
        
        builder.setPositiveButton("查看文件", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                openFile(file);
            }
        });
        
        builder.setNeutralButton("分享文件", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                shareFile(file);
            }
        });
        
        builder.setNegativeButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        
        builder.show();
    }



    /**
     * 打开文件
     */
    private void openFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri;
            
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
                uri = Uri.fromFile(file);
            }
            
            String mimeType = getMimeType(file.getAbsolutePath());
            intent.setDataAndType(uri, mimeType);
            
            // 确保有应用可以处理此Intent
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(Intent.createChooser(intent, "选择打开方式"));
            } else {
                Toast.makeText(this, "没有找到可以打开此文件的应用", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
            Uri uri;
            
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
                uri = Uri.fromFile(file);
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
                Toast.makeText(this, "没有找到可以分享此文件的应用", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法分享文件: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }





    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        AppResourceManager.getInstance(this).permissions().onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        // 检查权限是否授予成功
        if (AppResourceManager.getInstance(this).hasStoragePermission()) {
            // 权限授予成功，继续执行导出操作
            Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show();
            proceedWithExport();
        } else {
            // 权限授予失败
            Toast.makeText(this, "存储权限被拒绝，无法执行导出操作", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 加载题目数量
     */
    private void loadQuestionCount() {
        questionViewModel.getQuestions(new QuestionViewModel.GetQuestionsCallback() {
            @Override
            public void onSuccess(List<Question> questions) {
                final int count = (questions != null) ? questions.size() : 0;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvQuestionCount.setText("当前题库共有 " + count + " 道题目");
                    }
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvQuestionCount.setText("当前题库共有 0 道题目");
                    }
                });
            }
        });
    }
    
    /**
     * 显示导出目录
     */
    private void displayExportDirectory() {
        File exportDir = com.oilquiz.app.util.export.ExportManager.getExportDirectory(this);
        if (exportDir != null) {
            tvExportDirectory.setText("• 导出目录: " + exportDir.getAbsolutePath());
        }
    }
}
