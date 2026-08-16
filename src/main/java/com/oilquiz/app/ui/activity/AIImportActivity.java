package com.oilquiz.app.ui.activity;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.ClipData;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.AgentExecutionView;
import com.oilquiz.app.ai.importing.AIImportOrchestrator;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.ui.dialog.OnlineModelConfigDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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

    // 流式指标区（tok/s、token、已入库数在 v2 流程无回调，已在布局隐藏；仅保留进度）
    private TextView tvProgress;

    // 结果统计区
    private MaterialCardView statsCard;
    private TextView tvSuccessCount;
    private TextView tvFailedCount;
    private TextView tvTotalCount;
    private TextView tvDupCount;

    /** 当前选中的文件（单文件模式；多文件模式使用 selectedFiles） */
    private File currentFile;
    /** 导入是否已结束（完成后「取消」按钮切换为“关闭”） */
    private boolean importFinished;
    /** AI 导入编排引擎（v2 管线用作本地字段映射/填充引擎） */
    private AIImportOrchestrator orchestrator;
    /** 当前活动的 v2 导入管线（取消/销毁时一并终止） */
    private com.oilquiz.app.ai.importing.v2.ImportMain activeV2Main;

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

        // 流式指标区（tok/s、token、已入库数已在布局隐藏，仅保留进度）
        tvProgress = findViewById(R.id.tvProgress);

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
    }

    @Override
    protected void initListener() {
        // 选择题库文件（支持多选）
        btnSelectFile.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_PICK_FILE);
        });

        // 开始智能导入（v2 管线：自动分析字段，单文件/多文件均支持）
        btnStartImport.setOnClickListener(v -> {
            if (currentFile == null && selectedFiles.isEmpty()) {
                showToast("请先选择题库文件");
                return;
            }
            startSmartImport();
        });

        // 取消导入（同时终止编排引擎与 v2 管线）；导入结束后按钮语义为“关闭”
        btnCancel.setOnClickListener(v -> {
            if (importFinished) {
                finish();
                return;
            }
            if (orchestrator != null) {
                orchestrator.cancel();
            }
            if (activeV2Main != null) {
                activeV2Main.cancel();
            }
            showToast("已取消");
        });

        // 模型切换
        btnSwitchModel.setOnClickListener(v -> showModelSwitchDialog());

        // 配置在线模型
        btnConfigOnline.setOnClickListener(v -> showOnlineModelConfig());
    }

    /** 更新阶段图标行激活状态:当前及之前 alpha=1,之后 alpha=0.3 */
    private void updateStageIndicator(int stepNumber) {
        // DONE 阶段 ordinal+1=4,刚好等于 stageViews.length,全部点亮
        int activeCount = Math.min(stepNumber, stageViews.length);
        for (int i = 0; i < stageViews.length; i++) {
            stageViews[i].setAlpha(i < activeCount ? 1f : 0.3f);
        }
    }

    // ======================== 模型选择 ========================

    /** 启动智能导入模式（使用 v2 导入管线，支持多文件 + 本地 AI 推理） */
    private void startSmartImport() {
        // 使用本地 AI 引擎创建 v2 导入管线（避免 sign6 错误）
        com.oilquiz.app.ai.importing.v2.ImportMain v2Main =
            new com.oilquiz.app.ai.importing.v2.ImportMain(this, orchestrator);
        activeV2Main = v2Main;
        
        // 显示并启动 Agent 执行视图
        agentView.show();
        agentView.startExecution();
        
        com.oilquiz.app.ai.importing.v2.ImportMain.ImportListener v2Listener = new com.oilquiz.app.ai.importing.v2.ImportMain.ImportListener() {
            @Override
            public void onStage(String stage, String message) {
                int stepNumber = 1;
                String stageName = stage.toUpperCase();
                String emoji = "📄";
                
                if ("mapping".equals(stage)) { stepNumber = 1; emoji = "🔍"; }
                else if ("parse".equals(stage)) { stepNumber = 2; emoji = "📄"; }
                else if ("fill".equals(stage)) { stepNumber = 3; emoji = "⚙️"; }
                else if ("ingest".equals(stage)) { stepNumber = 4; emoji = "💾"; }
                else if ("done".equals(stage) || "all-done".equals(stage)) { stepNumber = 4; emoji = "✅"; }
                
                agentView.updateCurrentStep(stepNumber, stageName, emoji, message);
                updateStageIndicator(stepNumber);
                
                if ("done".equals(stage) || "all-done".equals(stage)) {
                    statsCard.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onLog(String message) {
                agentView.addLogEntry("log", message);
            }

            @Override
            public void onProgress(long current, long total, String detail) {
                tvProgress.setText(current + "/" + total);
            }

            @Override
            public void onComplete(com.oilquiz.app.ai.importing.v2.ImportMain.ImportSummary result) {
                if (isFinishing() || isDestroyed()) return;
                importFinished = true;
                btnCancel.setText("关闭");
                agentView.completeExecution("智能导入");
                tvSuccessCount.setText(String.valueOf(result.imported));
                tvDupCount.setText(String.valueOf(result.duplicated));
                tvTotalCount.setText(String.valueOf(result.totalRows));
                tvFailedCount.setText(String.valueOf(result.failed));
                statsCard.setVisibility(View.VISIBLE);
                showToast("导入完成: 新增 " + result.imported + " 题");
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                importFinished = true;
                btnCancel.setText("关闭");
                agentView.failExecution(message);
                showLongToast("导入失败: " + message);
            }
        };

        if (currentFile != null) {
            // 单文件导入（ImportMain 内部自行在后台线程执行）
            v2Main.run(currentFile, v2Listener);
        } else if (!selectedFiles.isEmpty()) {
            // 多文件批量导入
            agentView.updateCurrentStep(0, "BATCH", "📦", "批量导入 " + selectedFiles.size() + " 个文件");
            v2Main.runAllFromSourceFiles(selectedFiles, v2Listener);
        }
    }

    /** 多文件批量导入 */
    private java.util.List<File> selectedFiles = new ArrayList<>();

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
            selectedFiles.clear();
            currentFile = null;

            // 多选：部分文件管理器把首个 Uri 同时放在 getData() 与 clipData，
            // 以 clipData 为准（>1 项）或 getData()（单文件）两种形态都要覆盖
            ClipData clipData = data.getClipData();
            if (clipData != null && clipData.getItemCount() > 1) {
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    Uri itemUri = clipData.getItemAt(i).getUri();
                    File file = pickFileFromUri(itemUri);
                    if (file != null && file.exists()) {
                        selectedFiles.add(file);
                    }
                }
            } else {
                Uri uri = data.getData();
                if (uri != null) {
                    File file = pickFileFromUri(uri);
                    if (file != null && file.exists()) {
                        selectedFiles.add(file);
                    }
                } else if (clipData != null && clipData.getItemCount() > 0) {
                    Uri itemUri = clipData.getItemAt(0).getUri();
                    File file = pickFileFromUri(itemUri);
                    if (file != null && file.exists()) {
                        selectedFiles.add(file);
                    }
                }
            }
            // 单文件：同时写入 currentFile（v2 单文件路径使用）
            if (selectedFiles.size() == 1) {
                currentFile = selectedFiles.get(0);
            }
            displaySelectedFiles();
        }
    }

    /** 显示已选择的文件列表 */
    private void displaySelectedFiles() {
        if (selectedFiles.isEmpty()) {
            tvFileName.setText("未选择");
            btnStartImport.setEnabled(false);
            return;
        }

        if (selectedFiles.size() == 1) {
            // 单文件：显示文件名
            tvFileName.setText(selectedFiles.get(0).getName());
            btnStartImport.setEnabled(true);
        } else {
            // 多文件：显示数量
            tvFileName.setText("已选择 " + selectedFiles.size() + " 个文件");
            btnStartImport.setEnabled(true);
        }
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
        if (activeV2Main != null) {
            activeV2Main.cancel();
        }
    }
}
