package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.util.Log;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.ui.base.BaseActivity;
import androidx.cardview.widget.CardView;

import com.oilquiz.app.R;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.render.ExcelUtil;
import com.oilquiz.app.util.render.ExcelUtil.ImportSettings;
import com.oilquiz.app.viewmodel.QuestionViewModel;
import com.oilquiz.app.resource.SystemUIResourceAdapter;
import com.oilquiz.app.model.ImportHistory;
import com.oilquiz.app.repository.ImportHistoryRepository;
import com.oilquiz.app.database.DatabaseManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public class ImportActivity extends BaseActivity {

    private static final String TAG = "ImportActivity";
    private static final int REQUEST_CODE_PICK_FILE = 1001;
    private static final int REQUEST_CODE_IMPORT_RESULT = 1007;
    private QuestionViewModel questionViewModel;
    private ImportHistoryRepository importHistoryRepository;
    
    // UI组件
    private CardView fileSelectionCard;
    private CardView progressCard;
    private CardView statsCard;
    private TextView statusText;
    private TextView progressText;
    private TextView statsText;
    private ProgressBar progressBarHorizontal;
    private MaterialButton selectFileButton;
    private MaterialButton buttonCancel;
    private MaterialButton buttonAIParse;
    
    // 统计信息
    private int totalQuestions = 0;
    private int validQuestions = 0;
    private int invalidQuestions = 0;
    private int currentProgress = 0;
    
    // 导入相关变量
    private File currentFile;
    private int currentSheetIndex;
    private ExcelUtil.ImportConfirmation currentConfirmation;
    private Map<String, String> questionTypeMapping;
    private ExcelUtil.DataIssueReport currentIssueReport;
    
    // 导入相关变量
    private DatabaseManager databaseManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 应用系统UI主题
        SystemUIResourceAdapter uiAdapter = SystemUIResourceAdapter.getInstance(this);
        uiAdapter.applySystemTheme(this);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_import;
    }

    @Override
    protected void initView() {
        // 设置Toolbar
        setupToolbar("导入文件");

        // 初始化UI组件
        progressBarHorizontal = findViewById(R.id.progressBarHorizontal);
        statusText = findViewById(R.id.importStatusText);
        progressText = findViewById(R.id.importProgressText);
        buttonCancel = findViewById(R.id.btnCancel);
        buttonAIParse = findViewById(R.id.buttonAIParse);
        
        // 初始化进度显示
        resetProgressDisplay();
    }

    @Override
    protected void initData() {
        questionViewModel = new QuestionViewModel(getApplication());
        importHistoryRepository = new ImportHistoryRepository(getApplication());
        databaseManager = DatabaseManager.getInstance(this);
    }

    @Override
    protected void initListener() {
        // 设置取消按钮点击事件
        buttonCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 取消导入操作
                ExcelUtil.cancelImport();
                Toast.makeText(ImportActivity.this, "导入已取消", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        
        // 直接开始导入
        importQuestions();
    }
    
    private void resetProgressDisplay() {
        currentProgress = 0;
        totalQuestions = 0;
        validQuestions = 0;
        invalidQuestions = 0;
        
        progressBarHorizontal.setProgress(0);
        statusText.setText("准备导入...");
        progressText.setText("等待文件选择...");
    }
    
    private void updateProgressDisplay(String status, int current, int total) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText(status);
                if (total > 0) {
                    int progress = (int) ((current * 100.0) / total);
                    progressBarHorizontal.setProgress(progress);
                    progressText.setText(String.format("进度: %d/%d (%d%%)", current, total, progress));
                    
                    // 更新状态图标
                    updateStatusIcon(status);
                } else {
                    progressText.setText("处理中...");
                }
            }
        });
    }
    
    private void updateStatusIcon(String status) {
        ImageView statusIcon = findViewById(R.id.statusIcon);
        if (statusIcon == null) return;
        
        if (status.contains("成功") || status.contains("完成")) {
            statusIcon.setImageResource(R.drawable.ic_check_circle);
            statusIcon.setColorFilter(getResources().getColor(R.color.success_color));
        } else if (status.contains("失败") || status.contains("错误")) {
            statusIcon.setImageResource(R.drawable.ic_error);
            statusIcon.setColorFilter(getResources().getColor(R.color.error_color));
        } else if (status.contains("分析") || status.contains("检测")) {
            statusIcon.setImageResource(R.drawable.ic_analyze);
            statusIcon.setColorFilter(getResources().getColor(R.color.primary_color));
        } else if (status.contains("保存")) {
            statusIcon.setImageResource(R.drawable.ic_save);
            statusIcon.setColorFilter(getResources().getColor(R.color.primary_color));
        } else {
            statusIcon.setImageResource(R.drawable.ic_import_file);
            statusIcon.setColorFilter(getResources().getColor(R.color.primary_color));
        }
    }
    
    private void updateStatsDisplay(int valid, int invalid, int total) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                validQuestions = valid;
                invalidQuestions = invalid;
                totalQuestions = total;
                
                // 显示统计信息卡片
                CardView statsCard = findViewById(R.id.statsCard);
                if (statsCard != null) {
                    statsCard.setVisibility(View.VISIBLE);
                }
                
                // 更新统计数字
                TextView successCount = findViewById(R.id.successCount);
                TextView failedCount = findViewById(R.id.failedCount);
                TextView totalCount = findViewById(R.id.totalCount);
                
                if (successCount != null) {
                    successCount.setText(String.valueOf(valid));
                }
                if (failedCount != null) {
                    failedCount.setText(String.valueOf(invalid));
                }
                if (totalCount != null) {
                    totalCount.setText(String.valueOf(total));
                }
            }
        });
    }
    
    private void showImportCompleteDialog(ExcelUtil.ImportResult result) {
        // 添加历史记录
        if (currentFile != null) {
            ImportHistory importHistory = new ImportHistory(
                currentFile.getName(),
                currentFile.getAbsolutePath(),
                result.validQuestions,
                result.invalidQuestions,
                System.currentTimeMillis(),
                "成功"
            );
            importHistoryRepository.addImportHistory(importHistory);
        }
        
        // 跳转到导入结果页面
        runOnUiThread(() -> {
            Intent intent = new Intent(ImportActivity.this, ImportResultActivity.class);
            intent.putExtra(ImportResultActivity.EXTRA_TOTAL_QUESTIONS, result.totalQuestions);
            intent.putExtra(ImportResultActivity.EXTRA_SUCCESS_COUNT, result.validQuestions);
            intent.putExtra(ImportResultActivity.EXTRA_FAILED_COUNT, result.invalidQuestions);
            intent.putExtra(ImportResultActivity.EXTRA_SKIPPED_COUNT, result.skippedQuestions);
            intent.putExtra(ImportResultActivity.EXTRA_IMPORT_TIME, result.importTime);
            intent.putExtra(ImportResultActivity.EXTRA_FILE_NAME, currentFile != null ? currentFile.getName() : null);
            intent.putStringArrayListExtra(ImportResultActivity.EXTRA_ERROR_MESSAGES, new ArrayList<>(result.errorMessages));
            startActivityForResult(intent, REQUEST_CODE_IMPORT_RESULT);
        });
    }

    private void importQuestions() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
    }
    
    private void showProgressCard(String status) {
        statusText.setText(status);
    }
    




    // 从Content URI创建临时文件
    private File createTempFileFromUri(Uri uri) throws IOException {
        ContentResolver contentResolver = getContentResolver();
        InputStream inputStream = contentResolver.openInputStream(uri);
        if (inputStream == null) {
            return null;
        }

        // 获取原始文件的后缀名
        String fileExtension = "";
        String originalFileName = getFileNameFromUri(uri);
        if (originalFileName != null && originalFileName.lastIndexOf('.') > 0) {
            fileExtension = originalFileName.substring(originalFileName.lastIndexOf('.'));
        }

        // 创建临时文件，保留原始文件的后缀名
        File tempFile = File.createTempFile("import", fileExtension, getCacheDir());
        tempFile.deleteOnExit();

        // 复制文件内容
        try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        } finally {
            inputStream.close();
        }

        return tempFile;
    }

    // 从URI获取文件名
    private String getFileNameFromUri(Uri uri) {
        String fileName = null;
        String scheme = uri.getScheme();
        if ("content".equals(scheme)) {
            ContentResolver contentResolver = getContentResolver();
            String[] projection = {android.provider.MediaStore.MediaColumns.DISPLAY_NAME};
            android.database.Cursor cursor = contentResolver.query(uri, projection, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME);
                if (columnIndex != -1) {
                    fileName = cursor.getString(columnIndex);
                }
                cursor.close();
            }
        } else if ("file".equals(scheme)) {
            fileName = new File(uri.getPath()).getName();
        }
        return fileName;
    }

    private void processFile(File file) {
        // 保存当前文件
        currentFile = file;
        
        // 直接使用传统导入
        proceedWithTraditionalImport(file);
    }
    
    private void showErrorDialog(String title, String message) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("❌ " + message)
            .setPositiveButton("确定", (dialog, which) -> finish())
            .setCancelable(false)
            .show();
    }
    
    private void proceedWithTraditionalImport(File file) {
        // 更新UI显示
        updateProgressDisplay("正在检测文件格式...", 0, 0);
        
        // 在后台线程中执行文件处理
        ExcelUtil.executorService.execute(() -> {
            try {
                // 检测文件格式
                ExcelUtil.FileFormat format = ExcelUtil.detectFileFormat(file);
                
                if (format == ExcelUtil.FileFormat.EXCEL) {
                    // 更新UI显示
                    updateProgressDisplay("正在读取Excel工作表...", 0, 0);
                    
                    // 如果是Excel文件，先获取工作表列表
                    List<ExcelUtil.SheetInfo> sheets = ExcelUtil.getExcelSheets(file);
                    if (sheets != null && !sheets.isEmpty()) {
                        // 显示工作表选择对话框
                        showSheetSelectionDialog(file, sheets);
                    } else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                new androidx.appcompat.app.AlertDialog.Builder(ImportActivity.this)
                                    .setTitle("文件错误")
                                    .setMessage("❌ Excel文件中没有工作表，请检查文件内容。")
                                    .setPositiveButton("确定", (dialog, which) -> finish())
                                    .setCancelable(false)
                                    .show();
                            }
                        });
                    }
                } else if (format == ExcelUtil.FileFormat.CSV) {
                    // CSV文件直接导入
                    updateProgressDisplay("检测到CSV文件，准备导入...", 0, 0);
                    currentSheetIndex = 0;
                    performImport(file, 0, null);
                } else if (format == ExcelUtil.FileFormat.JSON) {
                    // JSON文件直接导入
                    updateProgressDisplay("检测到JSON文件，准备导入...", 0, 0);
                    currentSheetIndex = 0;
                    performImport(file, 0, null);
                } else {
                    // 其他格式
                    updateProgressDisplay("检测到文件，准备导入...", 0, 0);
                    currentSheetIndex = 0;
                    performImport(file, 0, null);
                }
            } catch (Exception e) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        new androidx.appcompat.app.AlertDialog.Builder(ImportActivity.this)
                            .setTitle("处理失败")
                            .setMessage("❌ 处理文件失败:\n\n" + e.getMessage())
                            .setPositiveButton("确定", (dialog, which) -> finish())
                            .setCancelable(false)
                            .show();
                    }
                });
            }
        });
    }
    
    private static final int REQUEST_CODE_FILE_PREVIEW = 1004;
    private static final int REQUEST_CODE_SMART_MAPPING = 1005;
    
    private Map<String, String> difficultyMapping;
    private Map<String, String> categoryMapping;
    private Map<String, Integer> fieldMapping;
    
    private void showSheetSelectionDialog(File file, List<ExcelUtil.SheetInfo> sheets) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String[] sheetNames = new String[sheets.size()];
                for (int i = 0; i < sheets.size(); i++) {
                    sheetNames[i] = sheets.get(i).sheetName + " (" + sheets.get(i).rowCount + "行)";
                }
                
                new androidx.appcompat.app.AlertDialog.Builder(ImportActivity.this)
                        .setTitle("选择工作表")
                        .setItems(sheetNames, (dialog, which) -> {
                            // 选择工作表后，跳转到文件预览界面
                            startFilePreview(file, which);
                        })
                        .setNegativeButton("取消", (dialog, which) -> finish())
                        .show();
            }
        });
    }
    
    private void startFilePreview(File file, int sheetIndex) {
        // 保存当前工作表索引
        currentSheetIndex = sheetIndex;
        
        Intent intent = new Intent(this, WebViewFilePreviewActivity.class);
        intent.putExtra(WebViewFilePreviewActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
        intent.putExtra(WebViewFilePreviewActivity.EXTRA_SHEET_INDEX, sheetIndex);
        intent.putExtra(WebViewFilePreviewActivity.EXTRA_FIELD_MAPPING, (java.io.Serializable) fieldMapping);
        startActivityForResult(intent, REQUEST_CODE_FILE_PREVIEW);
    }
    
    private void startSmartMapping(File file, int sheetIndex, Map<String, Integer> fieldMapping, ImportSettings settings) {
        Intent intent = new Intent(this, SmartMappingActivity.class);
        intent.putExtra(SmartMappingActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
        intent.putExtra(SmartMappingActivity.EXTRA_SHEET_INDEX, sheetIndex);
        intent.putExtra(SmartMappingActivity.EXTRA_FIELD_MAPPING, (java.io.Serializable) fieldMapping);
        intent.putExtra(SmartMappingActivity.EXTRA_IMPORT_SETTINGS, (java.io.Serializable) settings);
        startActivityForResult(intent, REQUEST_CODE_SMART_MAPPING);
    }
    
    private void generateImportConfirmation(File file, int sheetIndex, Map<String, Integer> fieldMapping, ExcelUtil.ImportSettings settings) {
        // 更新UI显示
        updateProgressDisplay("正在分析文件内容...", 0, 0);
        
        if (settings == null) {
            settings = new ExcelUtil.ImportSettings();
        }
        
        ExcelUtil.generateImportConfirmation(file, sheetIndex, fieldMapping, settings, new ExcelUtil.ImportConfirmationCallback() {
            @Override
            public void onConfirmationReady(ExcelUtil.ImportConfirmation confirmation) {
                // 显示字段映射确认对话框
                showMappingConfirmationDialog(file, sheetIndex, confirmation);
            }
            
            @Override
            public void onError(String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        new androidx.appcompat.app.AlertDialog.Builder(ImportActivity.this)
                            .setTitle("分析失败")
                            .setMessage("❌ 生成导入确认信息失败:\n\n" + message)
                            .setPositiveButton("确定", (dialog, which) -> finish())
                            .setCancelable(false)
                            .show();
                    }
                });
            }
            
            @Override
            public void onProgress(int current, int total) {
                // 更新进度显示
                updateProgressDisplay("正在分析文件内容...", current, total);
            }
        });
    }
    
    private void generateImportConfirmation(File file, int sheetIndex, Map<String, Integer> fieldMapping) {
        generateImportConfirmation(file, sheetIndex, fieldMapping, new ExcelUtil.ImportSettings());
    }
    
    private static final int REQUEST_CODE_EDIT_MAPPING = 1002;
    private static final int REQUEST_CODE_QUESTION_TYPE_MAPPING = 1003;
    private static final int REQUEST_CODE_DATA_ISSUE_FIX = 1006;

    private void showMappingConfirmationDialog(File file, int sheetIndex, ExcelUtil.ImportConfirmation confirmation) {
        currentFile = file;
        currentSheetIndex = sheetIndex;
        currentConfirmation = confirmation;
        
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // 显示导入确认对话框
                StringBuilder message = new StringBuilder();
                message.append("工作表: " + confirmation.selectedSheetName + "\n");
                message.append("总题目数: " + confirmation.totalItems + "\n");
                message.append("有效题目: " + confirmation.validItems + "\n");
                message.append("无效题目: " + confirmation.invalidItems + "\n");
                message.append("重复题目: " + confirmation.duplicateItems + "\n");
                message.append("\n字段映射:");
                for (String field : confirmation.fieldMapping.keySet()) {
                    message.append("\n" + field + " -> 列 " + (confirmation.fieldMapping.get(field) + 1));
                }
                
                new androidx.appcompat.app.AlertDialog.Builder(ImportActivity.this)
                        .setTitle("导入确认")
                        .setMessage(message.toString())
                        .setPositiveButton("开始导入", (dialog, which) -> {
                            // 先检测数据问题，再进行题型映射
                            detectDataIssuesAndProceed(file, sheetIndex, confirmation);
                        })
                        .setNeutralButton("编辑映射", (dialog, which) -> {
                            // 编辑映射
                            editMapping(file, sheetIndex, confirmation);
                        })
                        .setNegativeButton("取消", (dialog, which) -> finish())
                        .show();
            }
        });
    }

    private void editMapping(File file, int sheetIndex, ExcelUtil.ImportConfirmation confirmation) {
        // 获取列标题
        List<String> columnHeaders = ExcelUtil.getExcelColumnHeaders(file, sheetIndex);
        
        // 先尝试AI自动映射
        tryAIAutoMapping(file, sheetIndex, columnHeaders, confirmation);
    }
    
    /**
     * 直接打开映射编辑界面
     */
    private void tryAIAutoMapping(File file, int sheetIndex, List<String> columnHeaders, ExcelUtil.ImportConfirmation confirmation) {
        Log.d(TAG, "直接打开映射编辑界面");
        openMappingEditor(file, sheetIndex, columnHeaders, confirmation);
    }
    

    
    /**
     * 打开映射编辑界面（不使用AI建议）
     */
    private void openMappingEditor(File file, int sheetIndex, List<String> columnHeaders, ExcelUtil.ImportConfirmation confirmation) {
        Intent intent = new Intent(this, MappingEditorActivity.class);
        intent.putExtra(MappingEditorActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
        intent.putExtra(MappingEditorActivity.EXTRA_SHEET_INDEX, sheetIndex);
        intent.putExtra(MappingEditorActivity.EXTRA_FIELD_MAPPING, (java.io.Serializable) confirmation.fieldMapping);
        intent.putExtra(MappingEditorActivity.EXTRA_QUESTION_TYPE_MAPPING, (java.io.Serializable) questionTypeMapping);
        intent.putExtra(MappingEditorActivity.EXTRA_COLUMN_HEADERS, new ArrayList<>(columnHeaders));
        startActivityForResult(intent, REQUEST_CODE_EDIT_MAPPING);
    }

    private void detectDataIssuesAndProceed(File file, int sheetIndex, ExcelUtil.ImportConfirmation confirmation) {
        // 更新UI显示
        updateProgressDisplay("正在检测数据问题...", 0, 0);
        
        // 在后台线程中检测数据问题
        ExcelUtil.executorService.execute(() -> {
            try {
                ExcelUtil.ImportSettings settings = new ExcelUtil.ImportSettings();
                ExcelUtil.DataIssueReport issueReport = ExcelUtil.detectDataIssues(file, sheetIndex, confirmation.fieldMapping, settings, questionTypeMapping);
                
                runOnUiThread(() -> {
                    if (issueReport != null && issueReport.totalIssues > 0) {
                        // 发现数据问题，启动数据修复界面
                        Intent intent = new Intent(this, DataIssueFixActivity.class);
                        intent.putExtra(DataIssueFixActivity.EXTRA_DATA_ISSUE_REPORT, issueReport);
                        startActivityForResult(intent, REQUEST_CODE_DATA_ISSUE_FIX);
                    } else {
                        // 没有数据问题，直接进行题型映射
                        editQuestionTypeMapping(file, sheetIndex, confirmation);
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Error detecting data issues: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    // 检测出错，直接进行题型映射
                    editQuestionTypeMapping(file, sheetIndex, confirmation);
                });
            }
        });
    }

    private void editQuestionTypeMapping(File file, int sheetIndex, ExcelUtil.ImportConfirmation confirmation) {
        // 检测文件中的题型
        List<String> detectedQuestionTypes = ExcelUtil.detectQuestionTypes(file, sheetIndex, confirmation.fieldMapping);
        
        // 即使没有检测到题型，也启动题型映射编辑界面
        Intent intent = new Intent(this, QuestionTypeMapperActivity.class);
        intent.putExtra(QuestionTypeMapperActivity.EXTRA_DETECTED_QUESTION_TYPES, new ArrayList<>(detectedQuestionTypes));
        intent.putExtra(QuestionTypeMapperActivity.EXTRA_QUESTION_TYPE_MAPPING, (java.io.Serializable) questionTypeMapping);
        startActivityForResult(intent, REQUEST_CODE_QUESTION_TYPE_MAPPING);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    // 检查URI类型
                    String scheme = uri.getScheme();
                    if ("file".equals(scheme)) {
                        // 直接文件URI
                        String filePath = uri.getPath();
                        if (filePath != null) {
                            File file = new File(filePath);
                            if (file.exists()) {
                                processFile(file);
                            } else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(ImportActivity.this, "文件不存在，请检查文件路径", Toast.LENGTH_SHORT).show();
                                finish();
                            }
                        });
                    }
                        } else {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(ImportActivity.this, "无法获取文件路径", Toast.LENGTH_SHORT).show();
                                    finish();
                                }
                            });
                        }
                    } else if ("content".equals(scheme)) {
                        // 内容URI，使用临时文件
                        File tempFile = createTempFileFromUri(uri);
                        if (tempFile != null) {
                            processFile(tempFile);
                        } else {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(ImportActivity.this, "无法创建临时文件", Toast.LENGTH_SHORT).show();
                                    finish();
                                }
                            });
                        }
                    } else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(ImportActivity.this, "不支持的文件类型，请选择本地文件", Toast.LENGTH_SHORT).show();
                                finish();
                            }
                        });
                    }
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(ImportActivity.this, "导入失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });
                }
            }
        } else if (requestCode == REQUEST_CODE_FILE_PREVIEW && resultCode == RESULT_OK && data != null) {
            // 处理文件预览结果
            this.fieldMapping = (Map<String, Integer>) data.getSerializableExtra(WebViewFilePreviewActivity.EXTRA_RESULT_FIELD_MAPPING);
            if (this.fieldMapping != null && currentFile != null) {
                // 获取设置参数
                boolean autoCorrectEmptyCells = data.getBooleanExtra("auto_correct_empty_cells", true);
                boolean skipEmptyQuestions = data.getBooleanExtra("skip_empty_questions", false);
                String defaultQuestionType = data.getStringExtra("default_question_type");
                String defaultDifficulty = data.getStringExtra("default_difficulty");
                String defaultQuestion = data.getStringExtra("default_question");
                String defaultOption = data.getStringExtra("default_option");
                String defaultAnswer = data.getStringExtra("default_answer");
                
                // 创建并配置 ImportSettings
                ImportSettings settings = new ImportSettings();
                settings.autoCorrectEmptyCells = autoCorrectEmptyCells;
                settings.skipEmptyQuestions = skipEmptyQuestions;
                if (defaultQuestionType != null) settings.defaultQuestionType = defaultQuestionType;
                if (defaultDifficulty != null) {
                    try {
                        settings.defaultDifficulty = Integer.parseInt(defaultDifficulty);
                    } catch (NumberFormatException e) {
                        settings.defaultDifficulty = 1; // 默认难度
                    }
                }
                if (defaultQuestion != null) settings.defaultQuestion = defaultQuestion;
                if (defaultOption != null) settings.defaultOption = defaultOption;
                if (defaultAnswer != null) settings.defaultAnswer = defaultAnswer;
                
                // 跳转到智能映射界面
                startSmartMapping(currentFile, currentSheetIndex, this.fieldMapping, settings);
            }
        } else if (requestCode == REQUEST_CODE_SMART_MAPPING && resultCode == RESULT_OK && data != null) {
            // 处理智能映射结果
            questionTypeMapping = (Map<String, String>) data.getSerializableExtra(SmartMappingActivity.EXTRA_RESULT_QUESTION_TYPE_MAPPING);
            difficultyMapping = (Map<String, String>) data.getSerializableExtra(SmartMappingActivity.EXTRA_RESULT_DIFFICULTY_MAPPING);
            categoryMapping = (Map<String, String>) data.getSerializableExtra(SmartMappingActivity.EXTRA_RESULT_CATEGORY_MAPPING);
            Map<String, Integer> newFieldMapping = (Map<String, Integer>) data.getSerializableExtra(SmartMappingActivity.EXTRA_FIELD_MAPPING);
            if (newFieldMapping != null) {
                fieldMapping = newFieldMapping;
            }
            
            // 生成导入确认信息
            if (currentFile != null && fieldMapping != null) {
                // 创建并配置 ImportSettings
                ImportSettings settings = new ImportSettings();
                // 这里可以从 data 中获取设置，或者使用默认设置
                generateImportConfirmation(currentFile, currentSheetIndex, fieldMapping, settings);
            }
        } else if (requestCode == REQUEST_CODE_EDIT_MAPPING && resultCode == RESULT_OK && data != null) {
            // 处理映射编辑结果
            Map<String, Integer> newFieldMapping = (Map<String, Integer>) data.getSerializableExtra(MappingEditorActivity.EXTRA_FIELD_MAPPING);
            Map<String, String> newQuestionTypeMapping = (Map<String, String>) data.getSerializableExtra(MappingEditorActivity.EXTRA_QUESTION_TYPE_MAPPING);
            
            if (newFieldMapping != null && currentFile != null && currentConfirmation != null) {
                // 重新生成导入确认信息
                generateImportConfirmationWithMapping(currentFile, currentSheetIndex, newFieldMapping);
            }
        } else if (requestCode == REQUEST_CODE_QUESTION_TYPE_MAPPING && resultCode == RESULT_OK && data != null) {
            // 处理题型映射结果
            Map<String, String> newQuestionTypeMapping = (Map<String, String>) data.getSerializableExtra(QuestionTypeMapperActivity.EXTRA_RESULT_QUESTION_TYPE_MAPPING);
            questionTypeMapping = newQuestionTypeMapping;
            
            // 执行导入
            if (currentFile != null && currentConfirmation != null) {
                performImport(currentFile, currentSheetIndex, currentConfirmation.fieldMapping);
            }
        } else if (requestCode == REQUEST_CODE_DATA_ISSUE_FIX && resultCode == RESULT_OK && data != null) {
            // 处理数据问题修复结果
            currentIssueReport = (ExcelUtil.DataIssueReport) data.getSerializableExtra(DataIssueFixActivity.EXTRA_RESULT_DATA_ISSUE_REPORT);
            boolean skipAndContinue = data.getBooleanExtra(DataIssueFixActivity.EXTRA_SKIP_AND_CONTINUE, false);
            
            // 继续进行题型映射
            if (currentFile != null && currentConfirmation != null) {
                editQuestionTypeMapping(currentFile, currentSheetIndex, currentConfirmation);
            }
        } else if (requestCode == REQUEST_CODE_IMPORT_RESULT) {
            // 导入结果页面返回
            setResult(resultCode);
            finish();
        } else {
            finish();
        }
    }

    private void generateImportConfirmationWithMapping(File file, int sheetIndex, Map<String, Integer> fieldMapping) {
        ExcelUtil.generateImportConfirmation(file, sheetIndex, fieldMapping, new ExcelUtil.ImportSettings(), new ExcelUtil.ImportConfirmationCallback() {
            @Override
            public void onConfirmationReady(ExcelUtil.ImportConfirmation confirmation) {
                // 显示字段映射确认对话框
                showMappingConfirmationDialog(file, sheetIndex, confirmation);
            }
            
            @Override
            public void onError(String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(ImportActivity.this, "生成导入确认信息失败: " + message, Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
            }
            
            @Override
            public void onProgress(int current, int total) {
                // 可以添加进度显示
            }
        });
    }
    
    private void performImport(File file, int sheetIndex, Map<String, Integer> fieldMapping) {
        // 更新UI显示
        updateProgressDisplay("正在导入文件...", 0, 0);
        
        ExcelUtil.ImportSettings settings = new ExcelUtil.ImportSettings();
        settings.enableBatchProcessing = true;
        settings.enableParallelProcessing = true;
        
        ExcelUtil.importExcel(file, sheetIndex, fieldMapping, settings, questionTypeMapping, difficultyMapping, categoryMapping, new ExcelUtil.ImportCallback() {
            @Override
            public void onProgress(int current, int total) {
                // 更新进度显示
                updateProgressDisplay("正在导入题目...", current, total);
            }

            @Override
            public void onError(String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 显示详细的错误信息
                        new androidx.appcompat.app.AlertDialog.Builder(ImportActivity.this)
                            .setTitle("导入失败")
                            .setMessage("❌ 导入过程中发生错误:\n\n" + message)
                            .setPositiveButton("确定", (dialog, which) -> finish())
                            .setCancelable(false)
                            .show();
                    }
                });
            }

            @Override
            public void onComplete(List<Question> questions, ExcelUtil.ImportResult result) {
                // 如果有数据问题报告，应用用户修复的数据
                if (currentIssueReport != null && questions != null) {
                    for (int i = 0; i < questions.size(); i++) {
                        Question question = questions.get(i);
                        // 行号从1开始，加上表头行
                        int rowNumber = i + 2;
                        ExcelUtil.applyCorrectionsToQuestion(question, currentIssueReport, rowNumber);
                    }
                }
                
                // 更新统计信息
                updateStatsDisplay(result.validQuestions, result.invalidQuestions, result.totalQuestions);
                
                if (questions != null && !questions.isEmpty()) {
                    // 直接保存题目
                    saveQuestionsToDatabase(questions, result);
                } else {
                    // 没有题目需要保存，直接显示结果
                    showImportCompleteDialog(result);
                }
            }
        });
    }
    
    private void saveQuestionsToDatabase(List<Question> questions, ExcelUtil.ImportResult result) {
        updateProgressDisplay("正在保存到数据库...", result.validQuestions, result.totalQuestions);
        
        // 使用DatabaseManager保存题目
        new Thread(() -> {
            try {
                boolean success = databaseManager.addQuestions(questions).get();
                runOnUiThread(() -> {
                    if (success) {
                        showImportCompleteDialog(result);
                    } else {
                        // 如果DatabaseManager保存失败，回退到原来的方法
                        questionViewModel.addQuestions(questions, new QuestionViewModel.BatchOperationCallback() {
                            @Override
                            public void onSuccess(int count) {
                                showImportCompleteDialog(result);
                            }

                            @Override
                            public void onError(String error) {
                                runOnUiThread(() -> {
                                    new androidx.appcompat.app.AlertDialog.Builder(ImportActivity.this)
                                        .setTitle("保存失败")
                                        .setMessage("❌ 保存题目到数据库失败:\n\n" + error)
                                        .setPositiveButton("确定", (dialog, which) -> finish())
                                        .setCancelable(false)
                                        .show();
                                });
                            }
                        });
                    }
                });
            } catch (Exception e) {
                // 如果DatabaseManager方法执行出错，回退到原来的方法
                runOnUiThread(() -> {
                    questionViewModel.addQuestions(questions, new QuestionViewModel.BatchOperationCallback() {
                        @Override
                        public void onSuccess(int count) {
                            showImportCompleteDialog(result);
                        }

                        @Override
                        public void onError(String error) {
                            runOnUiThread(() -> {
                                new androidx.appcompat.app.AlertDialog.Builder(ImportActivity.this)
                                    .setTitle("保存失败")
                                    .setMessage("❌ 保存题目到数据库失败:\n\n" + error)
                                    .setPositiveButton("确定", (dialog, which) -> finish())
                                    .setCancelable(false)
                                    .show();
                            });
                        }
                    });
                });
            }
        }).start();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }
}