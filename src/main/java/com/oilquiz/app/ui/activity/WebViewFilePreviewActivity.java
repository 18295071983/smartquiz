package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import com.google.android.material.button.MaterialButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.oilquiz.app.R;
import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.util.render.WebViewRenderer;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class WebViewFilePreviewActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_SHEET_INDEX = "sheet_index";
    public static final String EXTRA_FIELD_MAPPING = "field_mapping";
    public static final String EXTRA_RESULT_FIELD_MAPPING = "result_field_mapping";
    private static final int REQUEST_CODE_FILE_PICKER = 1001;

    /**
     * 启动WebView文件预览活动
     * @param activity 发起活动的上下文
     * @param filePath 要预览的文件路径
     */
    public static void start(android.app.Activity activity, String filePath) {
        Intent intent = new Intent(activity, WebViewFilePreviewActivity.class);
        intent.putExtra(EXTRA_FILE_PATH, filePath);
        activity.startActivity(intent);
    }

    /**
     * 启动WebView文件预览活动（带字段映射）
     * @param activity 发起活动的上下文
     * @param filePath 要预览的文件路径
     * @param sheetIndex 工作表索引
     * @param fieldMapping 字段映射
     */
    public static void start(android.app.Activity activity, String filePath, int sheetIndex, java.util.Map<String, Integer> fieldMapping) {
        Intent intent = new Intent(activity, WebViewFilePreviewActivity.class);
        intent.putExtra(EXTRA_FILE_PATH, filePath);
        intent.putExtra(EXTRA_SHEET_INDEX, sheetIndex);
        intent.putExtra(EXTRA_FIELD_MAPPING, (java.io.Serializable) fieldMapping);
        activity.startActivity(intent);
    }

    private File file;
    private int sheetIndex;
    private Map<String, Integer> fieldMapping;
    private Map<Integer, String> columnToFieldMap;

    private ProgressBar progressBar;
    private TextView statusText;
    private TextView progressDetailText;
    private WebView previewWebView;
    private MaterialButton btnNext;
    private MaterialButton btnCancel;
    private MaterialButton btnSettings;
    private MaterialButton btnAutoMap;
    private MaterialButton btnClearMap;
    private TextView mappedFieldCount;
    private LinearLayout loadingContainer;
    
    // 导入设置
    private boolean autoCorrectEmptyCells = false;
    private boolean skipEmptyQuestions = false;
    private String defaultQuestionType = "未识别的题型";
    private String defaultDifficulty = "难度未识别";
    private String defaultQuestion = "数据题目为空";
    private String defaultOption = "选项为空";
    private String defaultAnswer = "答案为空";

    // 映射字段选项
    private java.util.ArrayList<String> fieldOptions = new java.util.ArrayList<>(java.util.Arrays.asList(
        "不映射", "题目", "选项A", "选项B", "选项C", "选项D", 
        "正确答案", "解析", "难度", "分类", "题型"
    ));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview_file_preview);

        initViews();
        loadData();
        setupListeners();
        loadFilePreview();
    }

    private void initViews() {
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        progressDetailText = findViewById(R.id.progressDetailText);
        previewWebView = findViewById(R.id.previewWebView);
        btnNext = findViewById(R.id.btnNext);
        btnCancel = findViewById(R.id.btnCancel);
        btnSettings = findViewById(R.id.btnSettings);
        btnAutoMap = findViewById(R.id.btnAutoMap);
        btnClearMap = findViewById(R.id.btnClearMap);
        mappedFieldCount = findViewById(R.id.mappedFieldCount);
        loadingContainer = findViewById(R.id.loadingContainer);
        
        // 设置Toolbar
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private void loadData() {
        Intent intent = getIntent();
        if (intent != null) {
            String filePath = intent.getStringExtra(EXTRA_FILE_PATH);
            if (filePath != null) {
                file = new File(filePath);
            }
            sheetIndex = intent.getIntExtra(EXTRA_SHEET_INDEX, 0);
            fieldMapping = (Map<String, Integer>) intent.getSerializableExtra(EXTRA_FIELD_MAPPING);
            if (fieldMapping == null) {
                fieldMapping = new HashMap<>();
            }
            // 初始化列到字段的映射
            columnToFieldMap = new HashMap<>();
            for (Map.Entry<String, Integer> entry : fieldMapping.entrySet()) {
                columnToFieldMap.put(entry.getValue(), entry.getKey());
            }
        }
        
        // 如果文件路径为空，启动文件选择器
        if (file == null) {
            AppLogger.i("WebViewFilePreviewActivity", "文件路径为空，启动文件选择器");
            launchFilePicker();
        }
    }

    private void setupListeners() {
        btnNext.setOnClickListener(v -> {
            showMappingResult();
        });

        btnCancel.setOnClickListener(v -> finish());

        btnSettings.setOnClickListener(v -> {
            showSettingsDialog();
        });

        btnAutoMap.setOnClickListener(v -> {
            performAutoMapping();
        });

        btnClearMap.setOnClickListener(v -> {
            clearAllMappings();
        });
    }

    private void loadFilePreview() {
        showLoadingState("正在加载文件内容...", "准备中...");

        // 检查file对象是否为null
        if (file == null) {
            showErrorState("文件路径为空");
            return;
        }

        // 检查文件是否存在
        if (!file.exists()) {
            showErrorState("文件不存在");
            return;
        }

        // 先启用WebView的JavaScript
        previewWebView.getSettings().setJavaScriptEnabled(true);
        previewWebView.getSettings().setDomStorageEnabled(true);
        
        // 设置WebView的JavaScript接口
        previewWebView.addJavascriptInterface(this, "Android");

        // 使用WebViewRenderer渲染Excel文件为HTML
        WebViewRenderer.renderExcelToHtml(file, sheetIndex, fieldMapping, fieldOptions, new WebViewRenderer.RenderCallback() {
            @Override
            public void onRenderStart() {
                updateLoadingProgress("开始渲染文件...", "");
            }

            @Override
            public void onRenderProgress(int current, int total) {
                updateLoadingProgress("渲染中...", String.format("已处理 %d/%d 行", current, total));
                if (total > 0) {
                    progressBar.setProgress((int) ((float) current / total * 100));
                }
            }

            @Override
            public void onRenderComplete(String htmlContent) {
                runOnUiThread(() -> {
                    loadingContainer.setVisibility(View.GONE);
                    previewWebView.setVisibility(View.VISIBLE);
                    
                    // 在WebView中显示渲染结果
                    WebViewRenderer.displayInWebView(previewWebView, htmlContent);
                    
                    // 更新统计信息
                    updateMappedFieldCount();
                    
                    // 显示成功提示
                    Toast.makeText(WebViewFilePreviewActivity.this, "文件加载成功", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onRenderError(String message) {
                showErrorState("加载失败: " + message);
            }
        });
    }
    
    private void showLoadingState(String status, String detail) {
        runOnUiThread(() -> {
            loadingContainer.setVisibility(View.VISIBLE);
            previewWebView.setVisibility(View.GONE);
            statusText.setText(status);
            progressDetailText.setText(detail);
            progressBar.setVisibility(View.VISIBLE);
        });
    }
    
    private void updateLoadingProgress(String status, String detail) {
        runOnUiThread(() -> {
            statusText.setText(status);
            progressDetailText.setText(detail);
        });
    }
    
    private void showErrorState(String message) {
        runOnUiThread(() -> {
            loadingContainer.setVisibility(View.VISIBLE);
            previewWebView.setVisibility(View.GONE);
            progressBar.setVisibility(View.GONE);
            statusText.setText("加载失败");
            progressDetailText.setText(message);
            
            // 更改图标为错误图标
            ImageView iconView = loadingContainer.findViewById(android.R.id.icon);
            if (iconView != null) {
                iconView.setImageResource(R.drawable.ic_error);
                iconView.setColorFilter(getResources().getColor(R.color.error_color));
            }
            
            Toast.makeText(WebViewFilePreviewActivity.this, message, Toast.LENGTH_SHORT).show();
        });
    }
    
    private void updateMappedFieldCount() {
        int count = fieldMapping.size();
        mappedFieldCount.setText(String.valueOf(count));
        
        // 根据映射数量更新下一步按钮状态
        boolean hasRequiredFields = fieldMapping.containsKey("题型") && 
                                    (fieldMapping.containsKey("题目") || fieldMapping.containsKey("题目内容")) &&
                                    (fieldMapping.containsKey("正确答案") || fieldMapping.containsKey("答案"));
        
        btnNext.setEnabled(hasRequiredFields);
        if (hasRequiredFields) {
            btnNext.setAlpha(1.0f);
        } else {
            btnNext.setAlpha(0.5f);
        }
    }
    
    private void performAutoMapping() {
        // 自动映射功能
        Toast.makeText(this, "正在执行自动映射...", Toast.LENGTH_SHORT).show();
        // 这里可以实现自动映射逻辑
        updateMappedFieldCount();
    }
    
    private void clearAllMappings() {
        // 清除所有映射
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("清除映射")
            .setMessage("确定要清除所有字段映射吗？")
            .setPositiveButton("确定", (dialog, which) -> {
                fieldMapping.clear();
                columnToFieldMap.clear();
                updateMappedFieldCount();
                loadFilePreview(); // 重新加载预览
                Toast.makeText(this, "已清除所有映射", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /**
     * 更新字段映射
     * @param columnIndex 列索引
     * @param fieldName 字段名称
     */
    @android.webkit.JavascriptInterface
    public void updateFieldMapping(int columnIndex, String fieldName) {
        if (fieldName.equals("不映射")) {
            // 移除映射
            for (Map.Entry<String, Integer> entry : fieldMapping.entrySet()) {
                if (entry.getValue().equals(columnIndex)) {
                    fieldMapping.remove(entry.getKey());
                    columnToFieldMap.remove(columnIndex);
                    break;
                }
            }
        } else {
            // 首先检查该列是否已被其他字段映射，如果是，先移除旧的映射
            String existingField = columnToFieldMap.get(columnIndex);
            if (existingField != null) {
                fieldMapping.remove(existingField);
            }
            // 添加或更新映射
            fieldMapping.put(fieldName, columnIndex);
            columnToFieldMap.put(columnIndex, fieldName);
        }
        
        // 更新UI
        runOnUiThread(() -> updateMappedFieldCount());
    }

    /**
     * 显示映射结果
     */
    private void showMappingResult() {
        // 检查必填字段是否有映射
        boolean hasQuestionType = fieldMapping.containsKey("题型");
        boolean hasQuestion = fieldMapping.containsKey("题目") || fieldMapping.containsKey("题目内容");
        boolean hasCorrectAnswer = fieldMapping.containsKey("正确答案") || fieldMapping.containsKey("答案");
        
        if (!hasQuestionType || !hasQuestion || !hasCorrectAnswer) {
            StringBuilder message = new StringBuilder();
            message.append("请完成以下必填字段的映射：\n\n");
            
            if (!hasQuestionType) {
                message.append("❌ 题型（请为\"题型\"字段选择对应的Excel列）\n");
            } else {
                message.append("✅ 题型（已映射到列 " + (fieldMapping.get("题型") + 1) + "）\n");
            }
            
            if (!hasQuestion) {
                message.append("❌ 题目（请为\"题目\"或\"题目内容\"字段选择对应的Excel列）\n");
            } else {
                String questionField = fieldMapping.containsKey("题目") ? "题目" : "题目内容";
                message.append("✅ " + questionField + "（已映射到列 " + (fieldMapping.get(questionField) + 1) + "）\n");
            }
            
            if (!hasCorrectAnswer) {
                message.append("❌ 正确答案（请为\"正确答案\"或\"答案\"字段选择对应的Excel列）\n");
            } else {
                String answerField = fieldMapping.containsKey("正确答案") ? "正确答案" : "答案";
                message.append("✅ " + answerField + "（已映射到列 " + (fieldMapping.get(answerField) + 1) + "）\n");
            }
            
            message.append("\n当前已配置的映射：\n");
            if (fieldMapping.isEmpty()) {
                message.append("（暂无映射）\n");
            } else {
                for (Map.Entry<String, Integer> entry : fieldMapping.entrySet()) {
                    message.append("  • " + entry.getKey() + " → 列 " + (entry.getValue() + 1) + "\n");
                }
            }
            
            message.append("\n💡 提示：选项、解析、难度、分类为可选字段，不配置也能正常导入。");
            
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("映射配置不完整")
                .setMessage(message.toString())
                .setPositiveButton("知道了", null)
                .show();
            return;
        }
        
        StringBuilder mappingResult = new StringBuilder();
        mappingResult.append("📋 映射结果：\n\n");
        
        for (Map.Entry<String, Integer> entry : fieldMapping.entrySet()) {
            mappingResult.append("✅ ").append(entry.getKey()).append(" → 列 ").append(entry.getValue() + 1).append("\n");
        }
        
        // 显示映射结果对话框
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("确认映射配置")
            .setMessage(mappingResult.toString())
            .setPositiveButton("确认并继续", (dialog, which) -> {
                // 保存映射结果和设置
                Intent resultIntent = new Intent();
                resultIntent.putExtra(EXTRA_RESULT_FIELD_MAPPING, (java.io.Serializable) fieldMapping);
                resultIntent.putExtra("auto_correct_empty_cells", autoCorrectEmptyCells);
                resultIntent.putExtra("skip_empty_questions", skipEmptyQuestions);
                resultIntent.putExtra("default_question_type", defaultQuestionType);
                resultIntent.putExtra("default_difficulty", defaultDifficulty);
                resultIntent.putExtra("default_question", defaultQuestion);
                resultIntent.putExtra("default_option", defaultOption);
                resultIntent.putExtra("default_answer", defaultAnswer);
                setResult(RESULT_OK, resultIntent);
                finish();
            })
            .setNegativeButton("返回修改", null)
            .show();
    }

    /**
     * 显示设置对话框
     */
    private void showSettingsDialog() {
        // 创建设置对话框
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("⚙️ 导入设置");
        
        // 自定义设置布局
        android.view.View settingsView = getLayoutInflater().inflate(R.layout.dialog_import_settings, null);
        builder.setView(settingsView);
        
        // 获取设置控件
        androidx.appcompat.widget.SwitchCompat checkBoxAutoCorrect = settingsView.findViewById(R.id.checkBoxAutoCorrect);
        androidx.appcompat.widget.SwitchCompat checkBoxSkipEmpty = settingsView.findViewById(R.id.checkBoxSkipEmpty);
        // 使用 TextInputEditText 或者 EditText 都可以，为了避免类型转换问题，用 EditText
        android.widget.EditText editTextQuestionType = settingsView.findViewById(R.id.editTextQuestionType);
        android.widget.EditText editTextDifficulty = settingsView.findViewById(R.id.editTextDifficulty);
        android.widget.EditText editTextQuestion = settingsView.findViewById(R.id.editTextQuestion);
        android.widget.EditText editTextOption = settingsView.findViewById(R.id.editTextOption);
        android.widget.EditText editTextAnswer = settingsView.findViewById(R.id.editTextAnswer);
        
        // 设置默认值
        checkBoxAutoCorrect.setChecked(autoCorrectEmptyCells);
        checkBoxSkipEmpty.setChecked(skipEmptyQuestions);
        editTextQuestionType.setText(defaultQuestionType);
        editTextDifficulty.setText(defaultDifficulty);
        editTextQuestion.setText(defaultQuestion);
        editTextOption.setText(defaultOption);
        editTextAnswer.setText(defaultAnswer);
        
        // 保存设置
        builder.setPositiveButton("💾 保存", (dialog, which) -> {
            autoCorrectEmptyCells = checkBoxAutoCorrect.isChecked();
            skipEmptyQuestions = checkBoxSkipEmpty.isChecked();
            defaultQuestionType = editTextQuestionType.getText().toString().trim();
            defaultDifficulty = editTextDifficulty.getText().toString().trim();
            defaultQuestion = editTextQuestion.getText().toString().trim();
            defaultOption = editTextOption.getText().toString().trim();
            defaultAnswer = editTextAnswer.getText().toString().trim();
            
            Toast.makeText(this, "✅ 设置已保存", Toast.LENGTH_SHORT).show();
        });
        
        builder.setNegativeButton("取消", null);
        builder.show();
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_file_preview, menu);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_refresh) {
            loadFilePreview();
            return true;
        } else if (id == R.id.action_add_option) {
            addNewOptionField();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    /**
     * 添加新的选项字段
     */
    private void addNewOptionField() {
        // 生成下一个选项字母
        char nextOptionChar = getNextOptionChar();
        if (nextOptionChar != 0) {
            String newOptionField = "选项" + nextOptionChar;
            // 添加到字段选项列表
            fieldOptions.add(newOptionField);
            // 重新加载文件预览以更新下拉选项
            loadFilePreview();
            Toast.makeText(this, "✅ 已添加" + newOptionField, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "❌ 已达到最大选项数量", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 获取下一个选项字母
     * @return 下一个选项字母，如 E, F, G 等
     */
    private char getNextOptionChar() {
        // 查找现有的选项字段
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("选项([A-Z])");
        int maxChar = 'D'; // 默认从 D 开始
        
        for (String field : fieldOptions) {
            java.util.regex.Matcher matcher = pattern.matcher(field);
            if (matcher.matches()) {
                char optionChar = matcher.group(1).charAt(0);
                if (optionChar > maxChar) {
                    maxChar = optionChar;
                }
            }
        }
        
        // 生成下一个字母
        char nextChar = (char) (maxChar + 1);
        if (nextChar <= 'Z') { // 最多支持到选项Z
            return nextChar;
        } else {
            return 0; // 超过Z，返回0表示无法添加
        }
    }
    
    /**
     * 启动文件选择器
     */
    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        
        // 支持的文件类型
        String[] mimeTypes = {
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv",
            "application/json",
            "text/plain"
        };
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        
        try {
            startActivityForResult(Intent.createChooser(intent, "选择要预览的文件"), REQUEST_CODE_FILE_PICKER);
        } catch (android.content.ActivityNotFoundException ex) {
            AppLogger.e("WebViewFilePreviewActivity", "没有找到文件选择器应用", ex);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("错误")
                    .setMessage("没有找到文件选择器应用，请安装文件管理器")
                    .setPositiveButton("确定", (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CODE_FILE_PICKER && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    // 将 Uri 转换为文件路径
                    String path = getPathFromUri(uri);
                    if (path != null) {
                        AppLogger.i("WebViewFilePreviewActivity", "选择的文件路径: " + path);
                        // 重新启动预览
                        Intent intent = new Intent(this, WebViewFilePreviewActivity.class);
                        intent.putExtra(EXTRA_FILE_PATH, path);
                        startActivity(intent);
                        finish();
                    } else {
                        AppLogger.e("WebViewFilePreviewActivity", "无法获取文件路径");
                        Toast.makeText(this, "无法获取文件路径", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                } catch (Exception e) {
                    AppLogger.e("WebViewFilePreviewActivity", "处理文件选择结果失败", e);
                    Toast.makeText(this, "处理文件选择结果失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    finish();
                }
            } else {
                AppLogger.e("WebViewFilePreviewActivity", "文件选择返回空 Uri");
                Toast.makeText(this, "文件选择返回空 Uri", Toast.LENGTH_SHORT).show();
                finish();
            }
        } else if (requestCode == REQUEST_CODE_FILE_PICKER) {
            // 用户取消了文件选择
            AppLogger.i("WebViewFilePreviewActivity", "用户取消了文件选择");
            finish();
        }
    }
    
    /**
     * 从 Uri 获取文件路径
     */
    private String getPathFromUri(Uri uri) {
        try {
            if (uri.getScheme().equals("content")) {
                // 对于 content:// 类型的 Uri
                // 尝试多种方式获取文件路径
                String[] projections = {
                    android.provider.MediaStore.Images.Media.DATA,
                    android.provider.MediaStore.MediaColumns.DATA,
                    android.provider.MediaStore.Files.FileColumns.DATA
                };
                
                for (String projection : projections) {
                    try {
                        android.database.Cursor cursor = getContentResolver().query(uri, new String[]{projection}, null, null, null);
                        if (cursor != null) {
                            if (cursor.moveToFirst()) {
                                int columnIndex = cursor.getColumnIndexOrThrow(projection);
                                String path = cursor.getString(columnIndex);
                                cursor.close();
                                if (path != null && !path.isEmpty()) {
                                    return path;
                                }
                            }
                            cursor.close();
                        }
                    } catch (Exception e) {
                        // 尝试下一种方式
                        AppLogger.w("WebViewFilePreviewActivity", "尝试获取文件路径失败: " + e.getMessage());
                    }
                }
                
                // 如果以上方法都失败，尝试使用临时文件方式
                return getPathFromContentUri(uri);
            } else if (uri.getScheme().equals("file")) {
                // 对于 file:// 类型的 Uri
                return uri.getPath();
            }
        } catch (Exception e) {
            AppLogger.e("WebViewFilePreviewActivity", "从 Uri 获取文件路径失败", e);
        }
        return null;
    }
    
    /**
     * 从 content:// Uri 获取文件路径（通过创建临时文件）
     */
    private String getPathFromContentUri(Uri uri) {
        try {
            // 创建临时文件
            java.io.File tempFile = createTempFileFromUri(uri);
            if (tempFile != null) {
                return tempFile.getAbsolutePath();
            }
        } catch (Exception e) {
            AppLogger.e("WebViewFilePreviewActivity", "从 content Uri 创建临时文件失败", e);
        }
        return null;
    }
    
    /**
     * 从 Uri 创建临时文件
     */
    private java.io.File createTempFileFromUri(Uri uri) throws java.io.IOException {
        // 获取文件类型
        String mimeType = getContentResolver().getType(uri);
        String extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (extension == null) {
            extension = "tmp";
        }
        
        // 创建临时文件
        java.io.File tempFile = java.io.File.createTempFile("webview_", "." + extension, getExternalFilesDir(null));
        tempFile.deleteOnExit();
        
        // 复制文件内容
        try (java.io.InputStream inputStream = getContentResolver().openInputStream(uri);
             java.io.FileOutputStream outputStream = new java.io.FileOutputStream(tempFile)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
        }
        
        return tempFile;
    }
}
