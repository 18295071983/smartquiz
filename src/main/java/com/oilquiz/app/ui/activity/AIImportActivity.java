package com.oilquiz.app.ui.activity;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.ClipData;
import android.net.Uri;
import android.util.Log;
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
import com.oilquiz.app.util.render.ExcelUtil;

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
    // 实时监控：耗时 / 步骤 / 推理速度 / Token
    private TextView tvMonitorElapsed;
    private TextView tvMonitorStage;
    private TextView tvMonitorSpeed;
    private TextView tvMonitorTokens;
    private android.os.Handler monitorHandler;
    private Runnable monitorTick;
    private long importStartTime = 0;

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

        // 实时监控：耗时 / 步骤 / 推理速度 / Token
        tvMonitorElapsed = findViewById(R.id.tvMonitorElapsed);
        tvMonitorStage = findViewById(R.id.tvMonitorStage);
        tvMonitorSpeed = findViewById(R.id.tvMonitorSpeed);
        tvMonitorTokens = findViewById(R.id.tvMonitorTokens);
        monitorHandler = new android.os.Handler(android.os.Looper.getMainLooper());

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
        // Excel 多工作表智能检测：识别题库 sheet（表头字段匹配最多），单表直接导入，
        // 无匹配时弹选择对话框兜底。CSV/JSON 等无 sheet 概念，直接走 v2。
        if (currentFile != null && selectedFiles.size() == 1 && isExcelFile(currentFile)) {
            smartSelectSheetAndImport(currentFile);
            return;
        }
        runV2Import();
    }

    private boolean isExcelFile(File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase();
        return name.endsWith(".xlsx") || name.endsWith(".xls");
    }

    /**
     * Excel 工作表智能检测：分析所有 sheet，选字段匹配最多（题干/答案/选项关键词）的表，
     * 导出为 .md 后走 v2 导入；无匹配表时弹选择对话框兜底。
     */
    private void smartSelectSheetAndImport(File file) {
        showToast("正在检测工作表...");
        // 用独立线程池（不占 ExcelUtil 共享单线程池，避免与其他导入排队互相阻塞）
        java.util.concurrent.ExecutorService detectExecutor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        detectExecutor.execute(() -> {
            try {
                List<com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile> profiles =
                        com.oilquiz.app.ai.importing.ExcelSheetPicker.analyzeSheets(file);
                if (profiles.isEmpty()) {
                    runOnUiThread(() -> showToast("未能识别工作表，尝试直接导入"));
                    runV2Import();
                    return;
                }

                // 提取题库说明/模板说明 sheet 的字段约定（供映射推理参考），同时排除它们不参与选题
                final String[] docHintHolder = {null};
                StringBuilder docText = new StringBuilder();
                for (com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile p : profiles) {
                    if (isDocSheet(p)) {
                        String doc = com.oilquiz.app.ai.importing.ExcelSheetPicker.exportSheetAsMarkdown(
                                file, p.sheetIndex, p.headerRowIndex, p.subHeaderRowIndex, p.dataStartRowIndex);
                        if (doc != null && !doc.isEmpty()) {
                            if (docText.length() > 0) docText.append("\n\n");
                            docText.append("【").append(p.sheetName).append("】\n").append(doc);
                        }
                    }
                }
                if (docText.length() > 0) {
                    // 截断：说明文本只取前 2000 字符（避免撑爆映射提示词）
                    docHintHolder[0] = docText.length() > 2000
                            ? docText.substring(0, 2000) : docText.toString();
                }

                // 选字段匹配最多的 sheet（题干/答案/选项等关键词命中数）。
                // 说明/模板/示例类 sheet 已提取内容，不参与选题。
                com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile best = null;
                int bestScore = -1;
                for (com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile p : profiles) {
                    if (isDocSheet(p)) continue; // 说明/模板/示例表不参与选题
                    int score = scoreSheetProfile(p);
                    if (score > bestScore) {
                        bestScore = score;
                        best = p;
                    }
                }

                final com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile selected = best;
                // 得分≥3 且确有其数据行（rowCount 含表头，需 ≥ 表头+1 行真实数据）才算命中
                boolean hasRealData = selected != null
                        && selected.rowCount > selected.dataStartRowIndex + 1;
                if (selected == null || bestScore < 3 || !hasRealData) {
                    // 无足够匹配（封面/说明表或纯说明表）：弹选择对话框兜底
                    runOnUiThread(() -> showSheetChoiceDialog(file, profiles, docHintHolder[0]));
                    return;
                }

                // 命中题库表：直接把原文件 + 选定 sheet 索引传给 v2（Python 直接读取该 sheet，
                // 不再中转 .md，保证数据完整）
                final String docHint = docHintHolder[0];
                final int sheetIdx = selected.sheetIndex;
                runOnUiThread(() -> {
                    showToast("已检测到题库工作表: " + selected.sheetName + "（" + selected.rowCount + "行）"
                            + (docHint != null ? "，已解析题库说明" : ""));
                    currentFile = file;
                    runV2Import(sheetIdx, docHint);
                });
            } catch (Exception e) {
                Log.e("AIImportActivity", "工作表检测失败: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    showToast("工作表检测失败，尝试直接导入");
                    runV2Import();
                });
            } finally {
                detectExecutor.shutdown();
            }
        });
    }

    /** 评分：表头命中 题干/答案/选项 等题库关键词的次数 */
    private int scoreSheetProfile(com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile p) {
        int score = 0;
        if (p.headerColumns == null) return 0;
        String[] strong = {"题干", "题目", "问题", "答案", "正确答案", "选项", "解析", "类型", "难度", "分类"};
        for (String h : p.headerColumns) {
            if (h == null) continue;
            for (String kw : strong) {
                if (h.contains(kw)) {
                    score++;
                    break;
                }
            }
        }
        return score;
    }

    /**
     * 判断是否为说明/模板/示例类 sheet（"模板说明"、"题库说明"、"使用说明"等）。
     * 这类表整表是文字说明，不应作为题库自动选中；sheet 名或表头含说明特征即判为文档表。
     */
    private boolean isDocSheet(com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile p) {
        if (p == null) return false;
        String name = p.sheetName != null ? p.sheetName : "";
        // sheet 名特征：说明/模板/示例/帮助/使用/指南/目录/封面
        if (name.contains("说明") || name.contains("模板") || name.contains("示例")
                || name.contains("帮助") || name.contains("使用") || name.contains("指南")
                || name.contains("目录") || name.contains("封面") || name.contains("介绍")) {
            return true;
        }
        // 表头特征：整行都是"字段+说明"型单列文本（如 题目 | 填写说明 | 示例）
        if (p.headerColumns != null) {
            int docHit = 0;
            String[] docWords = {"说明", "填写", "示例", "举例", "注意", "提示", "请勿", "格式", "规范"};
            for (String h : p.headerColumns) {
                if (h == null) continue;
                for (String w : docWords) {
                    if (h.contains(w)) {
                        docHit++;
                        break;
                    }
                }
            }
            // 表头一半以上是说明词 → 判为文档表
            if (docHit > 0 && docHit * 2 >= p.headerColumns.size()) {
                return true;
            }
        }
        return false;
    }

    /** 多 sheet 且无自动命中：弹选择对话框 */
    private void showSheetChoiceDialog(File file, List<com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile> profiles) {
        showSheetChoiceDialog(file, profiles, null);
    }

    private void showSheetChoiceDialog(File file, List<com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile> profiles,
                                       String docHint) {
        final boolean[] checked = new boolean[profiles.size()];
        java.util.Arrays.fill(checked, true); // 默认全选，用户可取消勾选
        String[] names = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile p = profiles.get(i);
            names[i] = p.sheetName + "（" + p.rowCount + "行，表头第" + (p.headerRowIndex + 1) + "行）";
        }
        new AlertDialog.Builder(this)
                .setTitle("选择题库工作表（可多选）")
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton("开始导入", (dialog, which) -> {
                    // 收集选中的工作表
                    java.util.List<Integer> selected = new java.util.ArrayList<>();
                    for (int i = 0; i < profiles.size(); i++) {
                        if (checked[i]) selected.add(i);
                    }
                    if (selected.isEmpty()) {
                        showToast("请至少选择一个工作表");
                        return;
                    }
                    currentFile = file;
                    if (selected.size() == 1) {
                        // 单选：走原单 sheet 流程
                        com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile p =
                                profiles.get(selected.get(0));
                        runV2Import(p.sheetIndex, docHint);
                    } else {
                        // 多选：逐 sheet 完整导入，最后汇总
                        java.util.List<Integer> idxs = new java.util.ArrayList<>();
                        for (int i : selected) {
                            idxs.add(profiles.get(i).sheetIndex);
                        }
                        runV2ImportSheets(idxs, docHint);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 启动实时监控：计时器 + 每秒刷新耗时/推理速度/Token（真实数据，来自 LlamaHelper） */
    private void startMonitor() {
        importStartTime = System.currentTimeMillis();
        if (tvMonitorStage != null) tvMonitorStage.setText("启动");
        monitorTick = new Runnable() {
            @Override
            public void run() {
                if (importStartTime <= 0) return;
                // 耗时
                long elapsed = System.currentTimeMillis() - importStartTime;
                if (tvMonitorElapsed != null) {
                    long s = elapsed / 1000;
                    tvMonitorElapsed.setText(String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60));
                }
                // 推理速度与 Token：实时读取 LlamaHelper（真实数据）
                try {
                    float speed = com.oilquiz.app.ai.jni.LlamaHelper.getInferenceSpeed();
                    if (tvMonitorSpeed != null) {
                        tvMonitorSpeed.setText(speed > 0 ? String.format(java.util.Locale.US, "%.1f", speed) : "-");
                    }
                    int tokens = com.oilquiz.app.ai.jni.LlamaHelper.getTokenCount();
                    if (tvMonitorTokens != null) {
                        tvMonitorTokens.setText(String.valueOf(tokens));
                    }
                } catch (Throwable ignored) {
                }
                // 每秒刷新
                if (monitorHandler != null) {
                    monitorHandler.postDelayed(this, 1000);
                }
            }
        };
        monitorHandler.post(monitorTick);
    }

    /** 停止实时监控 */
    private void stopMonitor() {
        importStartTime = 0;
        if (monitorHandler != null && monitorTick != null) {
            monitorHandler.removeCallbacks(monitorTick);
            monitorTick = null;
        }
    }

    /** 执行 v2 导入管线（单文件/多文件，自动扫全部工作表） */
    private void runV2Import() {
        runV2Import(-1, null);
    }

    /**
     * 执行 v2 导入管线。
     * @param sheetIndex Excel 用户选定工作表索引（-1=自动扫全部）
     * @param docHint    题库说明/模板说明提取的字段约定（映射推理参考），可为 null
     */
    private void runV2Import(final int sheetIndex, final String docHint) {
        // 使用本地 AI 引擎创建 v2 导入管线（避免 sign6 错误）
        com.oilquiz.app.ai.importing.v2.ImportMain v2Main =
            new com.oilquiz.app.ai.importing.v2.ImportMain(this, orchestrator);
        if (docHint != null && !docHint.isEmpty()) {
            v2Main.setDocHint(docHint);
        }
        if (sheetIndex >= 0) {
            v2Main.setExcelSheetIndex(sheetIndex);
        }
        activeV2Main = v2Main;
        
        // 显示并启动 Agent 执行视图
        agentView.show();
        agentView.startExecution();
        
        com.oilquiz.app.ai.importing.v2.ImportMain.ImportListener v2Listener = createV2Listener();

        // 启动实时监控（计时 + 推理速度/Token 轮询）
        startMonitor();

        if (currentFile != null) {
            // 单文件导入（ImportMain 内部自行在后台线程执行）
            v2Main.run(currentFile, v2Listener);
        } else if (!selectedFiles.isEmpty()) {
            // 多文件批量导入
            agentView.updateCurrentStep(0, "BATCH", "📦", "批量导入 " + selectedFiles.size() + " 个文件");
            v2Main.runAllFromSourceFiles(selectedFiles, v2Listener);
        }
    }

    /** 多工作表导入：选定多个 sheet 逐 sheet 完整导入，ImportMain 内部串行执行并汇总 */
    private void runV2ImportSheets(final java.util.List<Integer> sheetIndexes,
                                   final String docHint) {
        com.oilquiz.app.ai.importing.v2.ImportMain v2Main =
            new com.oilquiz.app.ai.importing.v2.ImportMain(this, orchestrator);
        if (docHint != null && !docHint.isEmpty()) {
            v2Main.setDocHint(docHint);
        }
        activeV2Main = v2Main;

        agentView.show();
        agentView.startExecution();

        com.oilquiz.app.ai.importing.v2.ImportMain.ImportListener v2Listener = createV2Listener();
        startMonitor();

        if (currentFile != null) {
            agentView.updateCurrentStep(1, "SHEET", "📑", "多工作表导入 " + sheetIndexes.size() + " 个");
            v2Main.runSheets(currentFile, sheetIndexes, v2Listener);
        }
    }

    /** v2 导入公共回调：阶段指示/实时监控/统计卡/结果弹窗（单文件/多文件/多工作表共用） */
    private com.oilquiz.app.ai.importing.v2.ImportMain.ImportListener createV2Listener() {
        return new com.oilquiz.app.ai.importing.v2.ImportMain.ImportListener() {
            @Override
            public void onStage(String stage, String message) {
                int stepNumber = 1;
                String stageName = stage.toUpperCase();
                String emoji = "📄";

                if ("mapping".equals(stage)) { stepNumber = 1; emoji = "🔍"; }
                else if ("parse".equals(stage)) { stepNumber = 2; emoji = "📄"; }
                else if ("fill".equals(stage)) { stepNumber = 3; emoji = "⚙️"; }
                else if ("ingest".equals(stage)) { stepNumber = 4; emoji = "💾"; }
                else if ("sheet".equals(stage)) { stepNumber = 1; emoji = "📑"; }
                else if ("done".equals(stage) || "all-done".equals(stage)
                        || "sheet-done".equals(stage)) { stepNumber = 4; emoji = "✅"; }

                agentView.updateCurrentStep(stepNumber, stageName, emoji, message);
                updateStageIndicator(stepNumber);

                // 实时监控：当前步骤
                if (tvMonitorStage != null) {
                    String label = "检测";
                    if ("mapping".equals(stage)) label = "映射";
                    else if ("parse".equals(stage)) label = "解析";
                    else if ("fill".equals(stage)) label = "填充";
                    else if ("ingest".equals(stage)) label = "入库";
                    else if ("sheet".equals(stage)) label = "工作表";
                    else if ("done".equals(stage) || "all-done".equals(stage)
                            || "sheet-done".equals(stage)) label = "完成";
                    tvMonitorStage.setText(label);
                }

                if ("done".equals(stage) || "all-done".equals(stage)) {
                    statsCard.setVisibility(View.VISIBLE);
                    stopMonitor();
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
                if (tvMonitorStage != null) tvMonitorStage.setText("完成");
                stopMonitor();
                showToast("导入完成: 新增 " + result.imported + " 题");
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                importFinished = true;
                btnCancel.setText("关闭");
                agentView.failExecution(message);
                stopMonitor();
                showLongToast("导入失败: " + message);
            }
        };
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
        stopMonitor();
        if (orchestrator != null) {
            orchestrator.cancel();
        }
        if (activeV2Main != null) {
            activeV2Main.cancel();
        }
    }
}
