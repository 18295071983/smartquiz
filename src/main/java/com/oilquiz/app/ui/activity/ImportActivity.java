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
import com.oilquiz.app.ai.importing.SqlFileImporter;
import com.oilquiz.app.ai.importing.v2.ImportDirs;
import com.oilquiz.app.ai.importing.v2.ImportMain;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
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

    // 流式日志与结果摘要组件
    private TextView importLogText;
    private android.widget.ScrollView logScrollView;
    private CardView summaryCard;
    private TextView summaryText;
    /** 导入已结束（完成/出错）：页面停留展示摘要，底部按钮变为“关闭”，由用户手动退出 */
    private volatile boolean v2Finished;
    private int logLineCount;
    /** 进度日志节流：相同百分比不重复刷日志，避免海量进度回调淹没关键信息 */
    private String lastLoggedProgress = "";
    private static final int MAX_LOG_LINES = 400;
    private final SimpleDateFormat logTimeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
    
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
        importLogText = findViewById(R.id.importLogText);
        logScrollView = findViewById(R.id.logScrollView);
        summaryCard = findViewById(R.id.summaryCard);
        summaryText = findViewById(R.id.summaryText);
        
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
                // 导入已结束后按钮语义为“关闭”：页面停留展示摘要，用户手动退出
                if (v2Finished) {
                    finish();
                    return;
                }
                // 取消导入操作（传统流程 + v2 批量管线均支持中途取消）
                ExcelUtil.cancelImport();
                if (v2ImportMain != null) {
                    v2ImportMain.cancel();
                }
                appendLog("用户取消导入");
                Toast.makeText(ImportActivity.this, "导入已取消", Toast.LENGTH_SHORT).show();
                finish();
            }
        });

        // 底部「开始导入」按钮：重新打开文件选择器（此前为无监听死按钮）
        MaterialButton startBtn = findViewById(R.id.btnStartImport);
        if (startBtn != null) {
            startBtn.setOnClickListener(v -> {
                if (v2Finished) {
                    finish();
                    return;
                }
                importQuestions();
            });
        }

        // 「AI解析」按钮：对当前文件走 v2 智能管线（AI 字段映射+解析+填充+入库）；
        // 无文件时先引导选择文件
        if (buttonAIParse != null) {
            buttonAIParse.setOnClickListener(v -> {
                if (v2Finished) return;
                if (currentFile == null || !currentFile.exists()) {
                    Toast.makeText(this, "请先选择要导入的文件", Toast.LENGTH_SHORT).show();
                    importQuestions();
                    return;
                }
                if (tryV2PipelineImport(currentFile)) {
                    // v2 管线已接管（source 目录内文件）
                } else {
                    // 目录外文件：同样可直接走 v2 智能管线
                    resetImportLogState();
                    updateProgressDisplay("AI 解析启动...", 0, 0);
                    v2ImportMain = new ImportMain(this);
                    v2ImportMain.run(currentFile, new ImportMain.ImportListener() {
                        @Override
                        public void onStage(String stage, String message) {
                            appendLog(message);
                            updateProgressDisplay(message, 0, 0);
                        }

                        @Override
                        public void onLog(String message) {
                            appendLog(message);
                            updateProgressDisplay(message, 0, 0);
                        }

                        @Override
                        public void onProgress(long current, long total, String detail) {
                            int c = (int) Math.min(current, Integer.MAX_VALUE);
                            int t = total > Integer.MAX_VALUE ? 0 : (int) Math.max(total, 1);
                            appendProgressLog(detail, current, total);
                            updateProgressDisplay(detail + " " + current + "/" + total, c, t);
                        }

                        @Override
                        public void onComplete(ImportMain.ImportSummary summary) {
                            String msg = "✅ 新增 " + summary.imported + " 题\n"
                                    + "重复跳过 " + summary.duplicated + " 题\n"
                                    + "失败 " + summary.failed + " 题\n"
                                    + "映射来源: " + summary.mappingSource
                                    + (summary.resumed ? "（断点续导）" : "") + "\n"
                                    + "耗时 " + (summary.elapsedMs / 1000) + " 秒";
                            appendLog("══ AI 解析完成：新增 " + summary.imported + " / 重复 "
                                    + summary.duplicated + " / 失败 " + summary.failed + " ══");
                            showImportResultSummary("✅ AI 解析完成", msg);
                        }

                        @Override
                        public void onError(String message) {
                            showErrorDialog("导入失败", message);
                        }
                    });
                }
            });
        }

        // 直接开始导入
        importQuestions();
    }

    /**
     * 页内展示导入结果摘要并切换到“已结束”状态：
     * 进度条满格、显示摘要卡片、底部按钮变“关闭”，页面停留等待用户手动退出。
     */
    private void showImportResultSummary(String title, String msg) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            v2Finished = true;
            statusText.setText(title);
            progressText.setText("导入结束，请查看结果摘要");
            progressBarHorizontal.setProgress(100);
            if (summaryText != null) summaryText.setText(msg);
            if (summaryCard != null) summaryCard.setVisibility(View.VISIBLE);
            buttonCancel.setText("关闭");
            // “开始导入”按钮在该页未绑定逻辑，结束后隐藏避免误解
            MaterialButton startBtn = findViewById(R.id.btnStartImport);
            if (startBtn != null) startBtn.setVisibility(View.GONE);
        });
    }

    /** 新导入启动前重置流式日志/摘要/按钮状态，避免上一次导入的残留展示 */
    private void resetImportLogState() {
        v2Finished = false;
        logLineCount = 0;
        lastLoggedProgress = "";
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (importLogText != null) importLogText.setText("等待导入开始...");
            if (summaryCard != null) summaryCard.setVisibility(View.GONE);
            buttonCancel.setText("取消");
            MaterialButton startBtn = findViewById(R.id.btnStartImport);
            if (startBtn != null) startBtn.setVisibility(View.VISIBLE);
        });
    }

    /**
     * 流式日志追加：带时间戳写入日志控制台并自动滚动到底部。
     * 超过 MAX_LOG_LINES 时裁剪最早行，防止长时间批量导入内存/渲染压力。
     */
    private void appendLog(String message) {
        if (message == null || message.isEmpty()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || importLogText == null) return;
            CharSequence cur = importLogText.getText();
            String base = (cur == null || "等待导入开始...".contentEquals(cur)) ? "" : cur + "\n";
            String line = "[" + logTimeFmt.format(new Date()) + "] " + message;
            logLineCount++;
            String next = base + line;
            if (logLineCount > MAX_LOG_LINES) {
                int idx = next.indexOf('\n');
                if (idx > 0 && idx < next.length() - 1) {
                    next = next.substring(idx + 1);
                    logLineCount--;
                }
            }
            importLogText.setText(next);
            if (logScrollView != null) {
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    /** 进度回调节流写日志：同一百分比只记录一次，保留关键节点不刷屏 */
    private void appendProgressLog(String detail, long current, long total) {
        int pct = total > 0 ? (int) (current * 100 / total) : -1;
        String key = detail + "#" + pct;
        if (key.equals(lastLoggedProgress)) return;
        lastLoggedProgress = key;
        appendLog(detail + " " + current + "/" + total + (pct >= 0 ? " (" + pct + "%)" : ""));
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
        // SAF 规范：优先 ACTION_OPEN_DOCUMENT；部分厂商（如小米文件管理器 Provider）
        // 可能拒绝或崩溃，捕获后降级为 ACTION_GET_CONTENT，禁止硬编码第三方 Provider URI
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            // 只允许可导入格式：xlsx/xls/csv/json/txt/db
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel",
                    "text/csv", "text/comma-separated-values", "text/plain",
                    "application/json",
                    "application/x-sqlite3", "application/octet-stream",
                    "application/x-sqlite", "application/vnd.sqlite3"
            });
            startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
        } catch (Exception e) {
            android.util.Log.w(TAG, "ACTION_OPEN_DOCUMENT 启动失败，降级为 ACTION_GET_CONTENT: " + e.getMessage());
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                fallback.setType("*/*");
                fallback.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel",
                        "text/csv", "text/comma-separated-values", "text/plain",
                        "application/json",
                        "application/x-sqlite3", "application/octet-stream"
                });
                startActivityForResult(fallback, REQUEST_CODE_PICK_FILE);
            } catch (Exception ex) {
                Toast.makeText(this, "无法打开文件选择器: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
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
            // 兜底 try-catch SecurityException：部分厂商 Provider 可能拒绝查询
            try {
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
            } catch (Exception e) {
                android.util.Log.w(TAG, "查询文件名失败，改用路径推断: " + e.getMessage());
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
        appendLog("⚠ " + title + ": " + message);
        // 回调可能来自后台线程（mainHandler.post），页面销毁后弹窗会 BadTokenException：
        // 切主线程 + 生命周期保护
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("❌ " + message)
                .setPositiveButton("继续查看", null)
                .setNegativeButton("关闭页面", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
        });
    }
    
    private void proceedWithTraditionalImport(File file) {
        // 专用离线导入管线路由：位于公共 source 目录的题库文件走 v2 管线
        // （独立轻量化推理引擎 + Python 预处理 + 断点续导）
        if (tryV2PipelineImport(file)) {
            return;
        }
        // 更新UI显示
        updateProgressDisplay("正在检测文件格式...", 0, 0);
        
        // 在后台线程中执行文件处理
        ExcelUtil.executorService.execute(() -> {
            try {
                // SQL 文件（.sql 脚本 / SQLite .db）走专用导入通道（含字段映射防错校验）
                if (SqlFileImporter.isSqlFile(file)) {
                    performSqlImport(file);
                    return;
                }

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
                    // CSV文件直接导入（自动检测字段映射，避免 fieldMapping=null 时题目全判无效）
                    updateProgressDisplay("检测到CSV文件，自动识别字段...", 0, 0);
                    currentSheetIndex = 0;
                    Map<String, Integer> autoMap = ExcelUtil.buildDefaultCsvFieldMapping(file);
                    if (autoMap != null && !autoMap.isEmpty()) {
                        this.fieldMapping = autoMap;
                        performImport(file, 0, autoMap);
                    } else {
                        performImport(file, 0, null);
                    }
                } else if (format == ExcelUtil.FileFormat.JSON) {
                    // JSON文件直接导入（importJson 用 FieldMappingRegistry 自动别名提取，fieldMapping 可空）
                    updateProgressDisplay("检测到JSON文件，准备导入...", 0, 0);
                    currentSheetIndex = 0;
                    performImport(file, 0, null);
                } else {
                    // 不支持的格式（Word/PDF/Markdown 等）：提前拦截，避免进入 Excel 解析报错
                    updateProgressDisplay("不支持的文件格式", 0, 0);
                    runOnUiThread(() -> showErrorDialog("不支持的文件格式",
                            "当前仅支持导入 Excel(.xlsx/.xls)、CSV、JSON 或 SQL 题库文件"));
                    return;
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

    /** v2 专用离线导入管线实例 */
    private ImportMain v2ImportMain;

    /**
     * 新功能：导入启动前检查未完成的导入进度（断点）。
     * 存在断点 → 弹窗让用户选择「继续续导」或「删除断点重新开始」；
     * 不存在 → 直接继续。
     */
    private void confirmBreakpointBeforeImport(Runnable proceed) {
        try {
            com.oilquiz.app.ai.importing.v2.ImportBreakpointStore.State bp =
                    com.oilquiz.app.ai.importing.v2.ImportBreakpointStore.load();
            if (bp == null) {
                proceed.run();
                return;
            }
            String srcName = bp.sourceName != null && !bp.sourceName.isEmpty()
                    ? bp.sourceName : "（未知文件）";
            String detail = bp.ingestOffset > 0
                    ? "已入库 " + bp.ingestOffset + " 行" : "解析到第 " + bp.parseRowIndex + " 行";
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("检测到未完成的导入")
                    .setMessage("上次导入「" + srcName + "」未完成（" + detail + "）。\n\n"
                            + "「继续续导」：从上次进度继续，已导入题目不会重复；\n"
                            + "「删除断点重来」：清除进度后从头导入。")
                    .setPositiveButton("继续续导", (d, w) -> proceed.run())
                    .setNegativeButton("删除断点重来", (d, w) -> {
                        com.oilquiz.app.ai.importing.v2.ImportBreakpointStore.clear();
                        appendLog("已清除导入断点，重新开始导入");
                        proceed.run();
                    })
                    .show();
        } catch (Exception e) {
            // 断点读取异常不阻断导入
            proceed.run();
        }
    }

    /**
     * 尝试使用 v2 专用离线导入管线：仅接管公共 source 目录
     * （/storage/emulated/0/OilQuiz/source/）内的题库文件，其余文件维持传统流程。
     */
    private boolean tryV2PipelineImport(File file) {
        try {
            String srcRoot = ImportDirs.sourceDir().getCanonicalPath();
            String filePath = file.getCanonicalPath();
            // 目录边界：必须是 srcRoot 内（排除 source_backup/source_old 等兄弟目录）
            if (!filePath.startsWith(srcRoot + File.separator)) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }

        // 新功能：启动前检查未完成进度（续导或删除断点重来）
        confirmBreakpointBeforeImport(() -> startV2SingleImport(file));
        return true;
    }

    /** 单文件 v2 离线导入启动（tryV2PipelineImport 的实际执行体） */
    private void startV2SingleImport(File file) {
        resetImportLogState();
        updateProgressDisplay("专用离线导入管线启动...", 0, 0);
        appendLog("专用离线导入管线启动: " + file.getName());
        v2ImportMain = new ImportMain(this);
        v2ImportMain.run(file, new ImportMain.ImportListener() {
            @Override
            public void onStage(String stage, String message) {
                appendLog(message);
                updateProgressDisplay(message, 0, 0);
            }

            @Override
            public void onLog(String message) {
                appendLog(message);
                updateProgressDisplay(message, 0, 0);
            }

            @Override
            public void onProgress(long current, long total, String detail) {
                int c = (int) Math.min(current, Integer.MAX_VALUE);
                int t = total > Integer.MAX_VALUE ? 0 : (int) Math.max(total, 1);
                appendProgressLog(detail, current, total);
                updateProgressDisplay(detail + " " + current + "/" + total, c, t);
            }

            @Override
            public void onComplete(ImportMain.ImportSummary summary) {
                // 完成后页内停留展示结果摘要，不再弹框后自动退出，用户手动关闭页面
                String msg = "✅ 新增 " + summary.imported + " 题\n"
                        + "重复跳过 " + summary.duplicated + " 题\n"
                        + "失败 " + summary.failed + " 题\n"
                        + "映射来源: " + summary.mappingSource
                        + (summary.resumed ? "（断点续导）" : "") + "\n"
                        + "耗时 " + (summary.elapsedMs / 1000) + " 秒"
                        + (summary.issuesMessage != null ? "\n\n" + summary.issuesMessage : "");
                appendLog("══ 导入完成：新增 " + summary.imported + " / 重复 " + summary.duplicated
                        + " / 失败 " + summary.failed + " ══");
                showImportResultSummary("✅ 导入完成", msg);
            }

            @Override
            public void onError(String message) {
                showErrorDialog("导入失败", message);
            }
        });
    }
    
    private Map<String, String> difficultyMapping;
    private Map<String, String> categoryMapping;
    private Map<String, Integer> fieldMapping;
    /** 预览阶段配置的导入设置（智能映射回程复用，避免默认值丢失） */
    private ExcelUtil.ImportSettings pendingImportSettings;
    /** 选项分隔符（用于"选项(拆分)"拆分模式） */
    private String optionsDelimiter;
    /** 拆分部分自定义映射：拆分部分索引(0-based) → 选项字段名（如"选项A"） */
    private Map<Integer, String> splitPartMapping;
    
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
                            // 快速路径：字段映射已确认，直接导入（不再强制跳题型映射/数据修复，
                            // 简单文件一键导入；需要高级处理用"高级处理"）
                            performImport(file, sheetIndex, confirmation.fieldMapping);
                        })
                        .setNeutralButton("编辑映射", (dialog, which) -> {
                            // 编辑映射（含 AI 自动映射）
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
        // 后台线程检测题型（整表解析耗时，避免主线程 ANR）
        new Thread(() -> {
            final List<String> detectedQuestionTypes = ExcelUtil.detectQuestionTypes(file, sheetIndex, confirmation.fieldMapping);
            runOnUiThread(() -> {
                // 即使没有检测到题型，也启动题型映射编辑界面
                Intent intent = new Intent(this, QuestionTypeMapperActivity.class);
                intent.putExtra(QuestionTypeMapperActivity.EXTRA_DETECTED_QUESTION_TYPES, new ArrayList<>(detectedQuestionTypes));
                intent.putExtra(QuestionTypeMapperActivity.EXTRA_QUESTION_TYPE_MAPPING, (java.io.Serializable) questionTypeMapping);
                startActivityForResult(intent, REQUEST_CODE_QUESTION_TYPE_MAPPING);
            });
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    // 尝试获取持久化读取权限（失败不影响本次导入，仅影响下次重新选择）
                    try {
                        getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception permEx) {
                        android.util.Log.w(TAG, "takePersistableUriPermission 失败（忽略）: " + permEx.getMessage());
                    }
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
                // 选项分隔符（用于"选项(拆分)"拆分模式）
                String optionsDelimiter = data.getStringExtra("options_delimiter");
                if (optionsDelimiter != null) {
                    settings.optionsDelimiter = optionsDelimiter;
                    this.optionsDelimiter = optionsDelimiter;
                }
                // 拆分部分自定义映射（用户在拆分预览中重新映射的结果）
                @SuppressWarnings("unchecked")
                Map<Integer, String> partMapping = (Map<Integer, String>) data.getSerializableExtra("split_part_mapping");
                if (partMapping != null && !partMapping.isEmpty()) {
                    settings.splitPartMapping = partMapping;
                    this.splitPartMapping = partMapping;
                }
                
                // 拆分模式：恢复"选项(拆分)"映射，移除虚拟列（拆分部分映射通过 splitPartMapping 传递给导入逻辑）
                int splitColIdx = data.getIntExtra("options_split_col", -1);
                if (splitColIdx >= 0 && optionsDelimiter != null) {
                    this.fieldMapping.put("选项(拆分)", splitColIdx);
                    java.util.Iterator<Map.Entry<String, Integer>> it = this.fieldMapping.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, Integer> e = it.next();
                        if (e.getValue() >= 1000) it.remove();
                    }
                    Log.d(TAG, "拆分模式已还原: 列" + (splitColIdx + 1) + ", 分隔符=" + optionsDelimiter
                            + ", 自定义部分映射=" + (partMapping != null ? partMapping.size() : 0) + "项");
                }
                
                // 跳转到智能映射界面（保存预览配置的设置，回程复用，避免配置丢失）
                pendingImportSettings = settings;
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
            
            // 生成导入确认信息（复用预览阶段配置的 ImportSettings，不再重建丢失配置）
            if (currentFile != null && fieldMapping != null) {
                ImportSettings settings = pendingImportSettings != null
                        ? pendingImportSettings : new ImportSettings();
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
            // 取消或未处理的返回：不关闭导入页，保留当前文件/映射状态（用户可重新操作）
            Log.d(TAG, "未处理的返回: requestCode=" + requestCode + ", resultCode=" + resultCode);
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
    
    /**
     * SQL 文件导入（后台线程执行）：
     * 解析 .sql 脚本 INSERT 语句或 SQLite .db 文件，
     * 自动做列名→标准字段映射，并通过防错校验后才入库。
     */
    private void performSqlImport(File file) {
        updateProgressDisplay("检测到SQL文件，正在解析并做字段映射校验...", 0, 0);
        SqlFileImporter.SqlImportResult sqlResult = SqlFileImporter.importFromFile(file);

        ExcelUtil.ImportResult result = new ExcelUtil.ImportResult();
        result.totalQuestions = sqlResult.totalRows;
        result.validQuestions = sqlResult.validQuestions.size();
        result.invalidQuestions = sqlResult.skippedRows;
        result.skippedQuestions = sqlResult.dedupedRows;
        result.importTime = sqlResult.importTimeMs;
        result.errorMessages.addAll(sqlResult.messages);
        result.summary = "SQL导入(表:" + sqlResult.tableUsed + ")";

        if (!sqlResult.validQuestions.isEmpty()) {
            updateProgressDisplay("字段映射: " + sqlResult.mappingDesc, 0, 0);
            updateStatsDisplay(result.validQuestions, result.invalidQuestions, result.totalQuestions);
            saveQuestionsToDatabase(sqlResult.validQuestions, result);
        } else {
            final StringBuilder msg = new StringBuilder("未提取到有效题目。\n");
            for (String m : sqlResult.messages) {
                msg.append("\n").append(m);
            }
            runOnUiThread(() -> new androidx.appcompat.app.AlertDialog.Builder(ImportActivity.this)
                    .setTitle("SQL导入失败")
                    .setMessage("❌ " + msg.toString())
                    .setPositiveButton("确定", (dialog, which) -> finish())
                    .setCancelable(false)
                    .show());
        }
    }

    private void performImport(File file, int sheetIndex, Map<String, Integer> fieldMapping) {
        // 更新UI显示
        updateProgressDisplay("正在导入文件...", 0, 0);
        
        ExcelUtil.ImportSettings settings = new ExcelUtil.ImportSettings();
        settings.enableBatchProcessing = true;
        settings.enableParallelProcessing = true;
        // 传递选项分隔符（用于"选项(拆分)"拆分模式）
        if (this.optionsDelimiter != null) {
            settings.optionsDelimiter = this.optionsDelimiter;
        }
        // 传递拆分部分自定义映射
        if (this.splitPartMapping != null) {
            settings.splitPartMapping = this.splitPartMapping;
        }
        
        ExcelUtil.importExcel(file, sheetIndex, fieldMapping, settings, questionTypeMapping, difficultyMapping, categoryMapping, currentIssueReport, new ExcelUtil.ImportCallback() {
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
                // 回调来自后台线程，需切回主线程操作 UI
                runOnUiThread(() -> {
                    // 用户取消：丢弃已解析的部分数据，不保存任何内容
                    if (result != null && result.cancelled) {
                        showErrorDialog("导入已取消", "已取消导入，未保存任何数据");
                        return;
                    }

                    // 数据修正已在 importExcel 内部循环中应用，无需此处再处理
                    // 更新统计信息
                    updateStatsDisplay(result.validQuestions, result.invalidQuestions, result.totalQuestions);

                    if (questions != null && !questions.isEmpty()) {
                        // 直接保存题目
                        saveQuestionsToDatabase(questions, result);
                    } else {
                        // 没有题目需要保存，直接显示结果
                        showImportCompleteDialog(result);
                    }
                });
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
        // 页面退出时终止后台 v2 导入任务（取消标志由 ImportMain 各阶段检查，
        // 避免销毁后仍向已失效页面回调/继续占用资源）
        if (v2ImportMain != null) {
            v2ImportMain.cancel();
        }
        // 注意：DatabaseManager 是全局单例，不能在此关闭线程池，
        // 否则会导致 AI 工具（get_question_count 等）报 RejectedExecutionException。
        // DatabaseManager 内部已支持 shutdown 后自动重建，但最佳实践是不主动关闭。
    }
}