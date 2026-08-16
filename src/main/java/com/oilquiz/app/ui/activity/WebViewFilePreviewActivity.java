package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class WebViewFilePreviewActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_SHEET_INDEX = "sheet_index";
    public static final String EXTRA_FIELD_MAPPING = "field_mapping";
    public static final String EXTRA_RESULT_FIELD_MAPPING = "result_field_mapping";
    private static final int REQUEST_CODE_FILE_PICKER = 1001;
    private static final String TAG = "WebViewFilePreview";
    /** 页面加载完成后是否需要同步虚拟列默认映射 */
    private volatile boolean pendingVirtualSync = false;

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
    private MaterialButton btnSplitOptions;
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
    /** 选项分隔符（用于拆分单列多选项） */
    private String optionsDelimiter = null;
    /** 记录分隔符对应的拆分列索引，用于判断是否需要重新弹出分隔符对话框 */
    private int optionsDelimiterSplitCol = -1;
    /** 是否正在显示拆分预览模式 */
    private boolean showSplitPreview = false;
    /** 独立拆分列索引（通过"拆分选项"按钮设置，不依赖fieldMapping） */
    private int independentSplitCol = -1;

    // 映射字段选项（与 WebView 下拉及 v1 导入实际支持的字段严格一致，单一数据源）
    private java.util.ArrayList<String> fieldOptions = new java.util.ArrayList<>(
            com.oilquiz.app.util.render.WebViewRenderer.getFieldOptions());

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
        btnSplitOptions = findViewById(R.id.btnSplitOptions);
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

        btnSplitOptions.setOnClickListener(v -> {
            showSplitColumnDialog();
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

        // 页面加载完成后同步虚拟列默认映射（拆分预览模式）
        previewWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (pendingVirtualSync) {
                    pendingVirtualSync = false;
                    registerDefaultVirtualMappings();
                }
            }
        });

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
                    // 渲染线程回调时页面可能已销毁：不再操作视图/弹 Toast
                    if (isFinishing() || isDestroyed()) return;
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
        if (file == null || !file.exists()) {
            Toast.makeText(this, "请先加载文件", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在执行自动映射...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(file)) {
                org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(sheetIndex);
                org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(0);
                if (headerRow == null) {
                    runOnUiThread(() -> Toast.makeText(this, "表头为空，无法自动映射", Toast.LENGTH_SHORT).show());
                    return;
                }
                // 清除现有映射
                fieldMapping.clear();
                columnToFieldMap.clear();
                optionsDelimiter = null;
                optionsDelimiterSplitCol = -1;
                independentSplitCol = -1;
                showSplitPreview = false;
                
                int mapped = 0;
                int lastCol = headerRow.getLastCellNum();
                for (int c = 0; c < lastCol; c++) {
                    org.apache.poi.ss.usermodel.Cell cell = headerRow.getCell(c);
                    if (cell == null) continue;
                    String header = com.oilquiz.app.util.render.ExcelUtil.getCellValue(cell);
                    if (header == null || header.trim().isEmpty()) continue;
                    
                    com.oilquiz.app.model.QuestionField suggested = com.oilquiz.app.model.QuestionField.suggestField(header.trim());
                    if (suggested != null) {
                        String displayName = suggested.getDisplayName();
                        // 避免重复映射同一字段（后者保留）
                        Integer prevCol = fieldMapping.get(displayName);
                        if (prevCol != null) {
                            columnToFieldMap.remove(prevCol);
                        }
                        fieldMapping.put(displayName, c);
                        columnToFieldMap.put(c, displayName);
                        mapped++;
                    }
                }
                final int finalMapped = mapped;
                runOnUiThread(() -> {
                    // 同步WebView显示
                    for (Map.Entry<Integer, String> entry : columnToFieldMap.entrySet()) {
                        syncColumnDisplay(entry.getKey(), entry.getValue());
                    }
                    // 将未映射的列同步为"不映射"
                    for (int c = 0; c < lastCol; c++) {
                        if (!columnToFieldMap.containsKey(c)) {
                            syncColumnDisplay(c, "不映射");
                        }
                    }
                    updateMappedFieldCount();
                    Toast.makeText(this, "自动映射完成，已映射 " + finalMapped + " 个字段", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "自动映射失败", e);
                runOnUiThread(() -> Toast.makeText(this, "自动映射失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
    
    private void clearAllMappings() {
        // 清除所有映射
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("清除映射")
            .setMessage("确定要清除所有字段映射吗？")
            .setPositiveButton("确定", (dialog, which) -> {
                fieldMapping.clear();
                columnToFieldMap.clear();
                // 同步重置分隔符状态和独立拆分状态
                optionsDelimiter = null;
                optionsDelimiterSplitCol = -1;
                independentSplitCol = -1;
                showSplitPreview = false;
                updateMappedFieldCount();
                loadFilePreview(); // 重新加载预览
                Toast.makeText(this, "已清除所有映射", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 独立选项字段名列表（用于与"选项(拆分)"互斥检测，支持A~L全部12个选项） */
    private static final String[] INDIVIDUAL_OPTION_FIELDS = {
        "选项A", "选项B", "选项C", "选项D", "选项E", "选项F",
        "选项G", "选项H", "选项I", "选项J", "选项K", "选项L"
    };

    /**
     * 更新字段映射
     * @param columnIndex 列索引
     * @param fieldName 字段名称
     */
    @android.webkit.JavascriptInterface
    public void updateFieldMapping(int columnIndex, String fieldName) {
        // 虚拟列（索引 >= 1000）：拆分预览模式下的拆分部分列
        if (columnIndex >= 1000) {
            if (!"不映射".equals(fieldName)) {
                // 若该字段已被其他虚拟列占用，先释放并同步更新其显示
                Integer occupiedCol = fieldMapping.get(fieldName);
                if (occupiedCol != null && occupiedCol >= 1000 && occupiedCol != columnIndex) {
                    columnToFieldMap.put(occupiedCol, "不映射");
                    syncColumnDisplay(occupiedCol, "不映射");
                }
                fieldMapping.put(fieldName, columnIndex);
                columnToFieldMap.put(columnIndex, fieldName);
            } else {
                String existingField = columnToFieldMap.get(columnIndex);
                if (existingField != null) fieldMapping.remove(existingField);
                columnToFieldMap.put(columnIndex, "不映射");
            }
            runOnUiThread(() -> updateMappedFieldCount());
            return;
        }

        // ── 冲突处理："选项(拆分)" 与独立选项列互斥 ──
        // 拆分预览模式下跳过，避免破坏已建立的拆分状态（此时映射仍可自由修改）
        if (!showSplitPreview) {
            if ("选项(拆分)".equals(fieldName)) {
                // 映射"选项(拆分)"时，自动移除已映射的独立选项列
                for (String optField : INDIVIDUAL_OPTION_FIELDS) {
                    if (fieldMapping.containsKey(optField)) {
                        Integer optCol = fieldMapping.get(optField);
                        fieldMapping.remove(optField);
                        columnToFieldMap.remove(optCol);
                        syncColumnDisplay(optCol, "不映射");
                    }
                }
                // 拆分列变更，重置分隔符和独立拆分状态
                optionsDelimiter = null;
                optionsDelimiterSplitCol = -1;
                independentSplitCol = -1;
            } else if (isIndividualOptionField(fieldName)) {
                // 映射独立选项列时，自动移除已映射的"选项(拆分)"
                if (fieldMapping.containsKey("选项(拆分)")) {
                    Integer splitCol = fieldMapping.get("选项(拆分)");
                    fieldMapping.remove("选项(拆分)");
                    columnToFieldMap.remove(splitCol);
                    syncColumnDisplay(splitCol, "不映射");
                    // 拆分模式被取消，重置分隔符和独立拆分状态
                    optionsDelimiter = null;
                    optionsDelimiterSplitCol = -1;
                    independentSplitCol = -1;
                }
            }
        }

        if (fieldName.equals("不映射")) {
            // 移除映射
            for (Map.Entry<String, Integer> entry : fieldMapping.entrySet()) {
                if (entry.getValue().equals(columnIndex)) {
                    String removedField = entry.getKey();
                    fieldMapping.remove(removedField);
                    columnToFieldMap.remove(columnIndex);
                    // 如果移除的是"选项(拆分)"，重置分隔符
                    if ("选项(拆分)".equals(removedField)) {
                        optionsDelimiter = null;
                        optionsDelimiterSplitCol = -1;
                    }
                    break;
                }
            }
        } else {
            // 若该字段已映射到其他列，先解除旧列的映射并同步其显示
            Integer prevCol = fieldMapping.get(fieldName);
            if (prevCol != null && prevCol != columnIndex && prevCol < 1000) {
                columnToFieldMap.remove(prevCol);
                syncColumnDisplay(prevCol, "不映射");
            }
            // 检查该列是否已被其他字段映射，如果是，先移除旧的映射
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
     * 同步更新WebView中指定列的下拉显示状态（用于映射冲突时动态刷新旧列）
     * @param colIdx 列索引（含虚拟列）
     * @param fieldName 要显示的字段名
     */
    private void syncColumnDisplay(int colIdx, String fieldName) {
        final String js = "(function(){var th=document.getElementById('col-" + colIdx + "');"
                + "if(!th)return;var t=th.querySelector('.mdd-trig');"
                + "if(t&&t.childNodes[0])t.childNodes[0].nodeValue='" + fieldName + " ';"
                + "var items=th.querySelectorAll('.mdd-item');"
                + "for(var i=0;i<items.length;i++){items[i].classList.remove('sel');"
                + "if(items[i].getAttribute('data-v')==='" + fieldName + "')items[i].classList.add('sel');}"
                + "if('" + fieldName + "'!=='不映射')th.classList.add('mapped');else th.classList.remove('mapped');})();";
        runOnUiThread(() -> previewWebView.evaluateJavascript(js, null));
    }

    /**
     * 判断字段名是否为独立选项字段
     */
    private boolean isIndividualOptionField(String fieldName) {
        if (fieldName == null) return false;
        for (String f : INDIVIDUAL_OPTION_FIELDS) {
            if (f.equals(fieldName)) return true;
        }
        return false;
    }

    /**
     * 显示映射结果
     */
    private void showMappingResult() {
        // 拆分预览模式下，点击"下一步"直接进入确认
        if (showSplitPreview) {
            showMappingResultConfirm();
            return;
        }
        // 独立拆分模式已激活，直接进入确认
        if (independentSplitCol >= 0) {
            showMappingResultConfirm();
            return;
        }
        // 检查是否通过字段映射设置了"选项(拆分)" → 自动检测分隔符并提示
        if (fieldMapping.containsKey("选项(拆分)")) {
            int splitCol = fieldMapping.get("选项(拆分)");
            if (optionsDelimiterSplitCol != splitCol) {
                optionsDelimiter = null;
                String detected = detectColumnDelimiter(splitCol);
                showDelimiterDialog(detected, splitCol);
                return;
            }
        }
        showMappingResultConfirm();
    }

    /**
     * 加载拆分预览：重新渲染WebView，在拆分列后显示拆分后的虚拟选项列
     */
    private void loadSplitPreview() {
        if (file == null || !file.exists() || optionsDelimiter == null) return;
        // 优先使用独立拆分列，否则从 fieldMapping 获取
        int splitCol = independentSplitCol >= 0 ? independentSplitCol
                : (fieldMapping.containsKey("选项(拆分)") ? fieldMapping.get("选项(拆分)") : -1);
        if (splitCol < 0) return;

        showLoadingState("正在渲染拆分预览...", "准备中...");
        WebViewRenderer.renderExcelToHtml(file, sheetIndex, fieldMapping, fieldOptions, splitCol, optionsDelimiter, new WebViewRenderer.RenderCallback() {
            @Override
            public void onRenderStart() {
                updateLoadingProgress("渲染拆分预览中...", "");
            }
            @Override
            public void onRenderProgress(int current, int total) {
                updateLoadingProgress("渲染拆分预览中...", String.format("已处理 %d/%d 行", current, total));
            }
            @Override
            public void onRenderComplete(String htmlContent) {
                runOnUiThread(() -> {
                    loadingContainer.setVisibility(View.GONE);
                    previewWebView.setVisibility(View.VISIBLE);
                    WebViewRenderer.displayInWebView(previewWebView, htmlContent);
                    updateMappedFieldCount();
                    // 页面加载完成后，将虚拟列的默认映射同步到数据结构，保证后续冲突检测和结果传递一致
                    pendingVirtualSync = true;
                    Toast.makeText(WebViewFilePreviewActivity.this,
                        "拆分预览已加载，可调整虚拟列映射后点击“下一步”", Toast.LENGTH_LONG).show();
                });
            }
            @Override
            public void onRenderError(String message) {
                showErrorState("拆分预览渲染失败: " + message);
            }
        });
    }

    /**
     * 拆分预览渲染完成后，将虚拟列的默认映射同步到 fieldMapping/columnToFieldMap
     */
    private void registerDefaultVirtualMappings() {
        previewWebView.evaluateJavascript(
            "(function(){var r=[];document.querySelectorAll('.mdd').forEach(function(d){"
            + "var c=parseInt(d.getAttribute('data-c'),10);"
            + "if(c>=1000){var t=d.querySelector('.mdd-trig');"
            + "if(t&&t.childNodes[0])r.push(c+':'+t.childNodes[0].nodeValue.trim());}});"
            + "return JSON.stringify(r);})();",
            value -> runOnUiThread(() -> {
                try {
                    if (value == null || value.isEmpty() || "null".equals(value)) return;
                    String json = value;
                    if (json.startsWith("\"") && json.endsWith("\"")) {
                        json = json.substring(1, json.length() - 1).replace("\\\"", "\"");
                    }
                    org.json.JSONArray arr = new org.json.JSONArray(json);
                    for (int i = 0; i < arr.length(); i++) {
                        String item = arr.getString(i);
                        int sep = item.indexOf(':');
                        if (sep <= 0) continue;
                        int col = Integer.parseInt(item.substring(0, sep));
                        String field = item.substring(sep + 1);
                        if (!"不映射".equals(field) && !field.isEmpty()) {
                            fieldMapping.put(field, col);
                            columnToFieldMap.put(col, field);
                        } else {
                            columnToFieldMap.put(col, "不映射");
                        }
                    }
                    updateMappedFieldCount();
                } catch (Exception e) {
                    Log.w(TAG, "同步虚拟列默认映射失败: " + e.getMessage());
                }
            }));
    }

    /**
     * 扫描指定列的前几行数据，自动检测常见分隔符
     */
    private String detectColumnDelimiter(int colIdx) {
        if (file == null || !file.exists()) return null;
        try (Workbook wb = WorkbookFactory.create(file)) {
            Sheet sheet = wb.getSheetAt(sheetIndex);
            // 常见分隔符候选
            String[] candidates = {"|", "||", ";", "；", "\t", "///", "###", "@@@"};
            // 检查前 10 行数据
            int checkRows = Math.min(sheet.getLastRowNum(), 10);
            for (String cand : candidates) {
                int matchCount = 0;
                for (int r = 1; r <= checkRows; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    Cell cell = row.getCell(colIdx);
                    if (cell == null) continue;
                    String val = com.oilquiz.app.util.render.ExcelUtil.getCellValue(cell);
                    if (val != null && val.contains(cand)) matchCount++;
                }
                // 超过一半的行包含该分隔符，认为是有效分隔符
                if (matchCount >= Math.max(2, checkRows / 2)) return cand;
            }
            // 尝试正则模式：A. B. C. 或 ①②③ 等
            int letterMatch = 0;
            for (int r = 1; r <= checkRows; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Cell cell = row.getCell(colIdx);
                if (cell == null) continue;
                String val = com.oilquiz.app.util.render.ExcelUtil.getCellValue(cell);
                if (val != null && val.matches("(?s).*[A-D][.、．].*[A-D][.、．].*")) letterMatch++;
            }
            if (letterMatch >= Math.max(2, checkRows / 2)) return "LETTER_PATTERN";
        } catch (Exception e) {
            // 忽略解析错误
        }
        return null;
    }

    /**
     * 显示拆分列选择对话框（独立入口，不依赖字段映射）
     */
    private void showSplitColumnDialog() {
        if (file == null || !file.exists()) {
            Toast.makeText(this, "请先加载文件", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try (Workbook wb = WorkbookFactory.create(file)) {
                Sheet sheet = wb.getSheetAt(sheetIndex);
                Row headerRow = sheet.getRow(0);
                if (headerRow == null) {
                    runOnUiThread(() -> Toast.makeText(this, "表头为空", Toast.LENGTH_SHORT).show());
                    return;
                }
                int lastCol = headerRow.getLastCellNum();
                String[] colNames = new String[lastCol];
                for (int c = 0; c < lastCol; c++) {
                    Cell cell = headerRow.getCell(c);
                    String name = (cell != null) ? com.oilquiz.app.util.render.ExcelUtil.getCellValue(cell) : null;
                    colNames[c] = (name != null && !name.trim().isEmpty()) ? name.trim() : ("列" + (c + 1));
                }
                runOnUiThread(() -> {
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("选择要拆分的列")
                        .setItems(colNames, (dialog, which) -> {
                            // 记录独立拆分列索引（不写入 fieldMapping）
                            independentSplitCol = which;
                            // 自动检测分隔符，直接拆分预览，不弹对话框
                            String detected = detectColumnDelimiter(which);
                            optionsDelimiter = (detected != null) ? detected : "|";
                            optionsDelimiterSplitCol = which;
                            showSplitPreview = true;
                            loadSplitPreview();
                        })
                        .setNegativeButton("取消", null)
                        .show();
                });
            } catch (Exception e) {
                Log.e(TAG, "读取表头失败", e);
                runOnUiThread(() -> Toast.makeText(this, "读取表头失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /**
     * 显示分隔符确认对话框
     * @param detected 自动检测到的分隔符，可为null
     * @param colIdx 拆分列索引
     */
    private void showDelimiterDialog(String detected, int colIdx) {
        String displayDelim;
        if (detected == null) {
            displayDelim = "未检测到";
        } else if ("LETTER_PATTERN".equals(detected)) {
            displayDelim = "字母标号(如 A. B. C.)";
        } else {
            displayDelim = "\"" + detected + "\"";
        }
        String[] presetDelims = {"|", ";", "；", "\t", "||", "///", "###"};
        
        StringBuilder msg = new StringBuilder();
        msg.append("检测到列 ").append(colIdx + 1).append(" 的选项内容使用分隔符：\n");
        msg.append(displayDelim).append("\n\n");
        msg.append("确认后将自动拆分该列内容到选项A/B/C/D...\n");
        msg.append("也可选择其他分隔符或输入自定义分隔符。");

        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad/2, pad, 0);

        // 预设分隔符单选
        android.widget.RadioGroup radioGroup = new android.widget.RadioGroup(this);
        radioGroup.setOrientation(android.widget.RadioGroup.VERTICAL);
        
        if (detected != null) {
            android.widget.RadioButton rbAuto = new android.widget.RadioButton(this);
            rbAuto.setText("自动检测: " + displayDelim);
            rbAuto.setTag(detected);
            rbAuto.setChecked(true);
            radioGroup.addView(rbAuto);
        }
        for (String d : presetDelims) {
            if (d.equals(detected)) continue;
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText("\"" + d + "\"");
            rb.setTag(d);
            if (detected == null && d.equals("|")) rb.setChecked(true); // 默认选中|
            radioGroup.addView(rb);
        }
        android.widget.RadioButton rbLetter = new android.widget.RadioButton(this);
        rbLetter.setText("字母标号(如 A. B. C. D.)");
        rbLetter.setTag("LETTER_PATTERN");
        radioGroup.addView(rbLetter);
        android.widget.RadioButton rbNone = new android.widget.RadioButton(this);
        rbNone.setText("不拆分（每行内容作为一个完整选项）");
        rbNone.setTag("NONE");
        radioGroup.addView(rbNone);
        container.addView(radioGroup);

        // 自定义输入框
        android.widget.EditText customInput = new android.widget.EditText(this);
        customInput.setHint("或输入自定义分隔符");
        customInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad/2;
        customInput.setLayoutParams(lp);
        container.addView(customInput);

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选项分隔符")
            .setMessage(msg.toString())
            .setView(container)
            .setPositiveButton("确认", (dlg, w) -> {
                String custom = customInput.getText().toString();
                if (!custom.isEmpty()) {
                    optionsDelimiter = custom;
                } else {
                    int checkedId = radioGroup.getCheckedRadioButtonId();
                    android.widget.RadioButton checkedRb = radioGroup.findViewById(checkedId);
                    String tag = checkedRb != null ? (String) checkedRb.getTag() : "NONE";
                    if ("NONE".equals(tag)) {
                        optionsDelimiter = null;
                    } else if ("LETTER_PATTERN".equals(tag)) {
                        optionsDelimiter = "LETTER_PATTERN";
                    } else {
                        optionsDelimiter = tag;
                    }
                }
                // 记录分隔符对应的拆分列索引
                optionsDelimiterSplitCol = colIdx;
                if (optionsDelimiter == null) {
                    // 用户选择"不拆分"
                    showSplitPreview = false;
                    independentSplitCol = -1;
                    showMappingResultConfirm();
                } else {
                    // 有分隔符，重新渲染WebView显示拆分预览
                    showSplitPreview = true;
                    // 判断来源：如果是独立拆分按钮触发的，不写 fieldMapping
                    if (independentSplitCol >= 0) {
                        // 独立拆分路径：不修改 fieldMapping，直接预览
                        loadSplitPreview();
                    } else {
                        // 字段映射路径：设置"选项(拆分)"映射
                        fieldMapping.put("选项(拆分)", colIdx);
                        columnToFieldMap.put(colIdx, "选项(拆分)");
                        loadSplitPreview();
                    }
                }
            })
            .setNegativeButton("返回修改", null)
            .show();
    }

    /**
     * 显示映射确认对话框（最终确认）
     */
    private void showMappingResultConfirm() {
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
            int colIdx = entry.getValue();
            if (colIdx >= 1000) {
                // 虚拟列：显示为拆分列的第N部分
                mappingResult.append("✅ ").append(entry.getKey()).append(" → 拆分部分 ").append(colIdx - 999).append("\n");
            } else {
                mappingResult.append("✅ ").append(entry.getKey()).append(" → 列 ").append(colIdx + 1).append("\n");
            }
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
                if (optionsDelimiter != null) {
                    resultIntent.putExtra("options_delimiter", optionsDelimiter);
                }
                // 传递拆分列索引（用于虚拟列还原）—— 优先使用独立拆分列
                int splitColForResult = independentSplitCol >= 0 ? independentSplitCol : optionsDelimiterSplitCol;
                resultIntent.putExtra("options_split_col", splitColForResult);
                // 传递拆分部分自定义映射（虚拟列索引-1000 → 选项字段名），使重新映射生效
                if (showSplitPreview) {
                    java.util.HashMap<Integer, String> partMapping = new java.util.HashMap<>();
                    for (Map.Entry<Integer, String> e : columnToFieldMap.entrySet()) {
                        if (e.getKey() >= 1000 && e.getValue() != null && !"不映射".equals(e.getValue())) {
                            partMapping.put(e.getKey() - 1000, e.getValue());
                        }
                    }
                    if (!partMapping.isEmpty()) {
                        resultIntent.putExtra("split_part_mapping", partMapping);
                    }
                }
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
        
        // 生成下一个字母（导入仅支持到选项L，超出提示已达上限）
        char nextChar = (char) (maxChar + 1);
        if (nextChar <= 'L') { // A~L 全部支持
            return nextChar;
        } else {
            return 0; // 超过L，返回0表示无法添加
        }
    }
    
    /**
     * 启动文件选择器
     */
    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
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
