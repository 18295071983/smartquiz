package com.oilquiz.app.ui.activity;

import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.AgentExecutionView;
import com.oilquiz.app.ai.importing.AIImportOrchestrator;
import com.oilquiz.app.ai.importing.ExcelSheetPicker;
import com.oilquiz.app.ai.importing.model.AIImportResult;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.ui.dialog.OnlineModelConfigDialog;
import com.oilquiz.app.util.render.ExcelUtil;
import com.oilquiz.app.util.render.ExcelUtil.FileFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * 题库 AI 导入页面。
 * <p>
 * 串联 {@link AIImportOrchestrator} 四阶段流水线(PROFILE/FORMAT/INGEST/DONE),通过 {@link AgentExecutionView}
 * 实时展示推理过程、阶段切换、流式指标与抽取预览,完成后给出统计与字段覆盖率。
 * <p>
 * v4: 混合管道 — 规则优先 + AI 兜底，4 阶段流水线。
 * <p>
 * 加载动画复用 {@link AgentExecutionView} 内置能力,不单独实现机器人脉冲。
 */
@AndroidEntryPoint
public class AIImportActivity extends BaseActivity {

    /** 文件选择请求码 */
    private static final int REQUEST_PICK_FILE = 1101;

    // 文件选择区
    private MaterialButton btnSelectFile;
    private TextView tvFileName;
    private MaterialButton btnStartImport;
    private MaterialButton btnCancel;

    // Agent 执行区
    private AgentExecutionView agentView;

    // 阶段图标行(PROFILE/FORMAT/INGEST/DONE 共 4 个)
    private final TextView[] stageViews = new TextView[4];

    // 流式指标区
    private TextView tvTokPerSec;
    private TextView tvTokenCount;
    private TextView tvQuestionCount;
    private TextView tvProgress;

    // 字段覆盖率区
    private ProgressBar coverageBar;
    private TextView tvCoveragePercent;

    // 预览区
    private RecyclerView rvPreview;

    // 结果统计区
    private MaterialCardView statsCard;
    private TextView tvSuccessCount;
    private TextView tvFailedCount;
    private TextView tvTotalCount;
    private TextView tvDupCount;

    /** 当前选中的文件 */
    private File currentFile;
    /** 原始文件名(Excel 选定 sheet 后记录为"原名 > sheetName",供 tvFileName 显示) */
    private String originalFileName;
    /** v6: Excel 选定的工作表参数（非 Excel 时为 null，导入时传原文件给 Orchestrator 直读） */
    private ExcelSheetPicker.SheetProfile selectedSheet;
    /** AI 导入编排引擎 */
    private AIImportOrchestrator orchestrator;
    /** 预览题目列表(由编排引擎累计快照替换填充) */
    private final List<Question> previewList = new ArrayList<>();
    /** 预览列表适配器 */
    private PreviewAdapter previewAdapter;

    // 模型选择区
    private TextView tvModelInfo;
    private MaterialButton btnSwitchModel;
    private MaterialButton btnConfigOnline;
    private OnlineModelManager onlineModelManager;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_ai_import;
    }

    @Override
    protected void initView() {
        // 设置工具栏
        setupToolbar("AI 导入题库");

        // 文件选择区
        btnSelectFile = findViewById(R.id.btnSelectFile);
        tvFileName = findViewById(R.id.tvFileName);
        btnStartImport = findViewById(R.id.btnStartImport);
        btnCancel = findViewById(R.id.btnCancel);

        // Agent 执行区
        agentView = findViewById(R.id.agentView);
        agentView.hide();

        // 阶段图标行
        stageViews[0] = findViewById(R.id.stage1);
        stageViews[1] = findViewById(R.id.stage2);
        stageViews[2] = findViewById(R.id.stage3);
        stageViews[3] = findViewById(R.id.stage4);

        // 流式指标区
        tvTokPerSec = findViewById(R.id.tvTokPerSec);
        tvTokenCount = findViewById(R.id.tvTokenCount);
        tvQuestionCount = findViewById(R.id.tvQuestionCount);
        tvProgress = findViewById(R.id.tvProgress);

        // 字段覆盖率区
        coverageBar = findViewById(R.id.coverageBar);
        tvCoveragePercent = findViewById(R.id.tvCoveragePercent);

        // 预览区
        rvPreview = findViewById(R.id.rvPreview);

        // 结果统计区
        statsCard = findViewById(R.id.statsCard);
        tvSuccessCount = findViewById(R.id.tvSuccessCount);
        tvFailedCount = findViewById(R.id.tvFailedCount);
        tvTotalCount = findViewById(R.id.tvTotalCount);
        tvDupCount = findViewById(R.id.tvDupCount);

        // 模型选择区
        tvModelInfo = findViewById(R.id.tvModelInfo);
        btnSwitchModel = findViewById(R.id.btnSwitchModel);
        btnConfigOnline = findViewById(R.id.btnConfigOnline);
    }

    @Override
    protected void initData() {
        // 创建编排引擎
        orchestrator = new AIImportOrchestrator(this);
        onlineModelManager = OnlineModelManager.getInstance(this);

        // 设置代理错误回调，通知 UI 本地模型故障
        orchestrator.setAgentErrorCallback(msg -> {
            runOnUiThread(() -> {
                showLongToast(msg);
                refreshModelInfo();
            });
        });

        // 刷新模型信息显示
        refreshModelInfo();

        // 预览 RecyclerView:线性布局 + 内置简单适配器(TextView 展示题号+题干预览)
        previewAdapter = new PreviewAdapter(previewList);
        rvPreview.setLayoutManager(new LinearLayoutManager(this));
        rvPreview.setAdapter(previewAdapter);
    }

    @Override
    protected void initListener() {
        // 选择题库文件
        btnSelectFile.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_PICK_FILE);
        });

        // 开始 AI 导入
        btnStartImport.setOnClickListener(v -> {
            if (currentFile == null) {
                showToast("请先选择题库文件");
                return;
            }
            startImport();
        });

        // 取消导入
        btnCancel.setOnClickListener(v -> {
            if (orchestrator != null) {
                orchestrator.cancel();
            }
            showToast("已取消");
        });

        // 模型切换
        btnSwitchModel.setOnClickListener(v -> showModelSwitchDialog());

        // 配置在线模型
        btnConfigOnline.setOnClickListener(v -> showOnlineModelConfig());
    }

    /** 启动导入流水线并绑定 8 个回调 */
    private void startImport() {
        // 刷新模型信息
        refreshModelInfo();

        // 重置预览与统计
        previewList.clear();
        previewAdapter.notifyDataSetChanged();
        tvQuestionCount.setText("0 入库");
        statsCard.setVisibility(View.GONE);

        // 显示并启动 Agent 执行视图
        agentView.show();
        agentView.startExecution();

        // 绑定监听器(8 个回调齐全)
        AIImportOrchestrator.ImportListener listener = new AIImportOrchestrator.ImportListener() {
            @Override
            public void onStage(AIImportOrchestrator.Stage stage, String message) {
                int stepNumber = stage.ordinal() + 1;
                agentView.updateCurrentStep(stepNumber, stage.name(), stageEmoji(stage), message);
                updateStageIndicator(stepNumber);
                // DONE 阶段显示统计卡片
                if (stage == AIImportOrchestrator.Stage.DONE) {
                    statsCard.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onProgress(int current, int total, String detail) {
                tvProgress.setText(current + "/" + total);
            }

            @Override
            public void onTokenStream(String delta, int tokenCount, float tokPerSec) {
                agentView.appendToken(delta);
                agentView.updateInferenceProgress(tokenCount, tokPerSec);
                tvTokPerSec.setText(String.format("%.1f t/s", tokPerSec));
                tvTokenCount.setText(tokenCount + " token");
            }

            @Override
            public void onThinking(String text) {
                agentView.addLogEntry("thinking", text);
            }

            @Override
            public void onPreviewQuestions(List<Question> partial) {
                // 编排引擎回调传入的是累计快照,故用替换而非追加以避免重复
                previewList.clear();
                if (partial != null) {
                    previewList.addAll(partial);
                }
                previewAdapter.notifyDataSetChanged();
            }

            @Override
            public void onImportedBatch(int imported, int duplicated, int failed) {
                // 复用 tvQuestionCount 展示实时入库数(含重复),chunk 内闭环可见
                tvQuestionCount.setText(imported + " 入库(重复 " + duplicated + ")");
            }

            @Override
            public void onComplete(AIImportResult result) {
                // 显示解析方法信息
                String parseMethod = "AI 解析";
                if (result.getExtraInfo() != null) {
                    String method = result.getExtraInfo().get("parseMethod");
                    if ("rule".equals(method)) parseMethod = "规则引擎";
                    else if ("hybrid".equals(method)) parseMethod = "混合模式";
                    else if ("rule_fallback".equals(method)) parseMethod = "规则引擎兜底";
                }
                // v7: 导入质量报告（数量对账 + 方法分布）
                StringBuilder qa = new StringBuilder();
                if (result.getSourceEstimate() > 0) {
                    qa.append(" 对账:").append(result.getValidCount())
                            .append("/").append(result.getSourceEstimate())
                            .append(result.isQaPassed() ? "✓" : "⚠有缺口");
                }
                if (result.getRuleCount() > 0 || result.getAiCount() > 0) {
                    qa.append(" 规则").append(result.getRuleCount())
                            .append("题/AI").append(result.getAiCount()).append("题");
                }
                if (result.getRetriedChunks() > 0) {
                    qa.append(" 重试").append(result.getRetriedChunks()).append("块");
                }
                agentView.completeExecution("导入完成 (" + parseMethod + ")" + qa.toString());
                updateStats(result);
                updateCoverage(result);
                refreshModelInfo();
                // 存在无效题目时弹窗提示错误数
                if (result.getInvalidCount() > 0) {
                    showInvalidDialog(result.getInvalidCount());
                }
                // v7: 对账未通过时提醒人工复核
                if (result.getSourceEstimate() > 0 && !result.isQaPassed()) {
                    showLongToast("数量对账存在缺口，建议人工复核题目数量");
                }
            }

            @Override
            public void onError(String message, Throwable error) {
                agentView.failExecution(message);
                refreshModelInfo();
                showLongToast("导入失败:" + message);
            }
        };

        dispatchStart(listener);
    }

    /** 启动导入：Excel 已选定工作表时走原文件直读，否则走通用入口 */
    private void dispatchStart(AIImportOrchestrator.ImportListener listener) {
        if (selectedSheet != null && currentFile != null) {
            orchestrator.startWithSheet(currentFile, listener,
                    selectedSheet.sheetIndex,
                    selectedSheet.headerRowIndex,
                    selectedSheet.subHeaderRowIndex,
                    selectedSheet.dataStartRowIndex,
                    selectedSheet.sheetName,
                    selectedSheet.inferredQuestionType);
        } else {
            orchestrator.start(currentFile, listener);
        }
    }

    /** 更新阶段图标行激活状态:当前及之前 alpha=1,之后 alpha=0.3 */
    private void updateStageIndicator(int stepNumber) {
        // DONE 阶段 ordinal+1=4,刚好等于 stageViews.length,全部点亮
        int activeCount = Math.min(stepNumber, stageViews.length);
        for (int i = 0; i < stageViews.length; i++) {
            stageViews[i].setAlpha(i < activeCount ? 1f : 0.3f);
        }
    }

    /** 更新结果统计四项数字 */
    private void updateStats(AIImportResult result) {
        statsCard.setVisibility(View.VISIBLE);
        tvSuccessCount.setText(String.valueOf(result.getValidCount()));
        tvFailedCount.setText(String.valueOf(result.getInvalidCount()));
        tvTotalCount.setText(String.valueOf(result.getTotalCount()));
        tvDupCount.setText(String.valueOf(result.getDuplicatedCount()));
    }

    /** 更新字段覆盖率(取各字段覆盖率均值,最小实现用水平进度条) */
    private void updateCoverage(AIImportResult result) {
        Map<String, Float> coverage = result.getFieldCoverage();
        float avg = 0f;
        if (coverage != null && !coverage.isEmpty()) {
            float sum = 0f;
            for (Float v : coverage.values()) {
                if (v != null) {
                    sum += v;
                }
            }
            avg = sum / coverage.size();
        }
        int percent = Math.round(avg * 100f);
        coverageBar.setProgress(percent);
        tvCoveragePercent.setText(percent + "%");
    }

    /** 弹窗显示无效题目数量 */
    private void showInvalidDialog(int invalidCount) {
        new AlertDialog.Builder(this)
                .setTitle("校验提示")
                .setMessage("共有 " + invalidCount + " 道题目校验失败,已跳过入库。")
                .setPositiveButton("确定", null)
                .setCancelable(true)
                .show();
    }

    /** 阶段 emoji 映射(PROFILE/FORMAT/INGEST/DONE 4 阶段) */
    private String stageEmoji(AIImportOrchestrator.Stage stage) {
        switch (stage) {
            case PROFILE:
                return "📄";
            case FORMAT:
                return "🔍";
            case INGEST:
                return "⚙️";
            case DONE:
                return "✅";
            default:
                return "📄";
        }
    }

    // ======================== 模型选择 ========================

    /** 刷新模型信息显示 */
    private void refreshModelInfo() {
        if (orchestrator == null || tvModelInfo == null) return;
        String info = orchestrator.getCurrentModelInfo();
        tvModelInfo.setText(info);

        // 根据模式显示/隐藏配置按钮
        AIImportOrchestrator.ModelMode mode = orchestrator.getModelMode();
        boolean isOnlineMode = (mode == AIImportOrchestrator.ModelMode.ONLINE_ONLY
                || mode == AIImportOrchestrator.ModelMode.ONLINE_PREFERRED);
        btnConfigOnline.setVisibility(isOnlineMode ? View.VISIBLE : View.GONE);
    }

    /** 弹出模型切换对话框 */
    private void showModelSwitchDialog() {
        final AIImportOrchestrator.ModelMode currentMode = orchestrator.getModelMode();
        final boolean hasOnline = onlineModelManager.getActiveModel() != null;

        String[] items = new String[]{
                "自动选择 (推荐)",
                "优先在线模型",
                "仅使用在线模型",
                "仅使用本地模型"
        };

        int checked = 0;
        switch (currentMode) {
            case AUTO: checked = 0; break;
            case ONLINE_PREFERRED: checked = 1; break;
            case ONLINE_ONLY: checked = 2; break;
            case LOCAL_ONLY: checked = 3; break;
        }

        new AlertDialog.Builder(this)
                .setTitle("选择 AI 模型模式")
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    AIImportOrchestrator.ModelMode newMode;
                    switch (which) {
                        case 0: newMode = AIImportOrchestrator.ModelMode.AUTO; break;
                        case 1: newMode = AIImportOrchestrator.ModelMode.ONLINE_PREFERRED; break;
                        case 2: newMode = AIImportOrchestrator.ModelMode.ONLINE_ONLY; break;
                        case 3: newMode = AIImportOrchestrator.ModelMode.LOCAL_ONLY; break;
                        default: newMode = AIImportOrchestrator.ModelMode.AUTO; break;
                    }

                    // 检查在线模型可用性
                    if ((newMode == AIImportOrchestrator.ModelMode.ONLINE_ONLY
                            || newMode == AIImportOrchestrator.ModelMode.ONLINE_PREFERRED)
                            && !hasOnline) {
                        showLongToast("当前没有已激活的在线模型，请先配置在线模型");
                    }

                    orchestrator.setModelMode(newMode);
                    refreshModelInfo();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 弹出在线模型配置对话框 */
    private void showOnlineModelConfig() {
        OnlineModelConfigDialog dialog = new OnlineModelConfigDialog(this);
        dialog.setSaveListener(new OnlineModelConfigDialog.OnConfigSaveListener() {
            @Override
            public void onConfigSaved(OnlineModelManager.OnlineModelConfig config) {
                // 激活新配置的模型
                onlineModelManager.setActiveModel(config.id);
                refreshModelInfo();
                showToast("在线模型已配置并激活");
            }

            @Override
            public void onConfigCancelled() {
                // 取消
            }
        });
        dialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) {
                showToast("未获取到文件");
                return;
            }
            File file = pickFileFromUri(uri);
            if (file != null && file.exists()) {
                currentFile = file;
                // Excel 检测:命中则进入"选工作表 + 自动识别表头"流程;异常当非 Excel 处理
                boolean isExcel = false;
                try {
                    isExcel = ExcelUtil.detectFileFormat(file) == FileFormat.EXCEL;
                } catch (Exception e) {
                    isExcel = false;
                }
                if (isExcel) {
                    // 先禁用导入按钮并提示解析中,等用户选定 sheet 后再启用
                    btnStartImport.setEnabled(false);
                    tvFileName.setText("正在解析工作表...");
                    handleExcelSheetSelection(file);
                } else {
                    // 非 Excel:保持原流程
                    selectedSheet = null;
                    originalFileName = file.getName();
                    tvFileName.setText(file.getName());
                    btnStartImport.setEnabled(true);
                }
            } else {
                showToast("无法读取所选文件");
            }
        }
    }

    /**
     * Excel 工作表选择流程:后台分析所有 sheet(POI 耗时),主线程弹单选对话框。
     * 解析失败清空 currentFile;用户选定后导出为 .md 临时文件并切换 currentFile。
     */
    private void handleExcelSheetSelection(File excelFile) {
        ExecutorService es = Executors.newSingleThreadExecutor();
        es.execute(() -> {
            final List<ExcelSheetPicker.SheetProfile> profiles =
                    ExcelSheetPicker.analyzeSheets(excelFile);
            runOnUiThread(() -> {
                if (profiles == null || profiles.isEmpty()) {
                    showToast("无法读取工作表,请检查文件");
                    currentFile = null;
                    originalFileName = null;
                    tvFileName.setText("未选择");
                    btnStartImport.setEnabled(false);
                    return;
                }
                showSheetPickerDialog(excelFile, profiles);
            });
        });
        es.shutdown();
    }

    /** 弹出工作表单选对话框,确认后记录选定 SheetProfile（v6: 导入时由 Orchestrator 内存直读原文件,不再写临时 .md） */
    private void showSheetPickerDialog(File excelFile,
                                        final List<ExcelSheetPicker.SheetProfile> profiles) {
        final int n = profiles.size();
        String[] items = new String[n];
        for (int i = 0; i < n; i++) {
            ExcelSheetPicker.SheetProfile p = profiles.get(i);
            items[i] = p.sheetName + " (" + p.rowCount + "行 × " + p.columnCount
                    + "列,表头第" + (p.headerRowIndex + 1) + "行)";
        }
        // 默认选中第 0 个
        final int[] checked = {0};
        new AlertDialog.Builder(this)
                .setTitle("选择工作表(共 " + n + " 个)")
                .setSingleChoiceItems(items, 0, (dialog, which) -> checked[0] = which)
                .setPositiveButton("导入此表", (dialog, which) -> {
                    final ExcelSheetPicker.SheetProfile selected = profiles.get(checked[0]);
                    // v6: currentFile 保持为 Excel 原文件，只记录选定 sheet 参数，
                    // 导入时 Orchestrator 在 PROFILE 阶段用 POI 内存直读，不再写临时 .md 文件
                    final String displayName = excelFile.getName() + " > " + selected.sheetName;
                    currentFile = excelFile;
                    selectedSheet = selected;
                    originalFileName = displayName;
                    tvFileName.setText(displayName);
                    btnStartImport.setEnabled(true);
                    showToast("已选择工作表:" + selected.sheetName
                            + ",表头第" + (selected.headerRowIndex + 1) + "行,可开始导入");
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    currentFile = null;
                    selectedSheet = null;
                    originalFileName = null;
                    tvFileName.setText("未选择");
                    btnStartImport.setEnabled(false);
                })
                .setCancelable(false)
                .show();
    }

    /** 从 Uri 解析出可用文件:file 协议直接取路径,content 协议拷贝为缓存临时文件 */
    private File pickFileFromUri(Uri uri) {
        String scheme = uri.getScheme();
        if ("file".equals(scheme)) {
            String path = uri.getPath();
            if (path != null) {
                return new File(path);
            }
            return null;
        } else if ("content".equals(scheme)) {
            try {
                return createTempFileFromUri(uri);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    /** 将 content Uri 内容拷贝到缓存临时文件,保留原始后缀名 */
    private File createTempFileFromUri(Uri uri) throws IOException {
        ContentResolver resolver = getContentResolver();
        InputStream input = resolver.openInputStream(uri);
        if (input == null) {
            return null;
        }
        // 取原始文件名以保留后缀
        String displayName = getFileNameFromUri(uri);
        String extension = "";
        if (displayName != null && displayName.lastIndexOf('.') > 0) {
            extension = displayName.substring(displayName.lastIndexOf('.'));
        }
        File tempFile = File.createTempFile("ai_import", extension, getCacheDir());
        tempFile.deleteOnExit();
        try (FileOutputStream output = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        } finally {
            input.close();
        }
        return tempFile;
    }

    /** 从 Uri 获取显示文件名 */
    private String getFileNameFromUri(Uri uri) {
        String scheme = uri.getScheme();
        if ("content".equals(scheme)) {
            ContentResolver resolver = getContentResolver();
            String[] projection = {android.provider.MediaStore.MediaColumns.DISPLAY_NAME};
            android.database.Cursor cursor = resolver.query(uri, projection, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(
                                android.provider.MediaStore.MediaColumns.DISPLAY_NAME);
                        if (idx != -1) {
                            return cursor.getString(idx);
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
            return null;
        } else if ("file".equals(scheme)) {
            String path = uri.getPath();
            return path != null ? new File(path).getName() : null;
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (orchestrator != null) {
            orchestrator.cancel();
        }
    }

    /**
     * 预览列表适配器(最小实现)。
     * ViewHolder 仅用一个 TextView 展示"题号. 题干预览",不引入额外 item 布局。
     */
    private static class PreviewAdapter extends RecyclerView.Adapter<PreviewAdapter.PreviewViewHolder> {

        private final List<Question> items;

        PreviewAdapter(List<Question> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public PreviewViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setPadding(8, 8, 8, 8);
            tv.setTextSize(13f);
            tv.setMaxLines(2);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            return new PreviewViewHolder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull PreviewViewHolder holder, int position) {
            Question q = items.get(position);
            String text = q.getQuestionText();
            if (text == null) {
                text = "";
            }
            ((TextView) holder.itemView).setText((position + 1) + ". " + text);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class PreviewViewHolder extends RecyclerView.ViewHolder {
            PreviewViewHolder(@NonNull View itemView) {
                super(itemView);
            }
        }
    }
}
