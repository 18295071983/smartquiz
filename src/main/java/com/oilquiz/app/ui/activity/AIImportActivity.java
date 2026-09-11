package com.oilquiz.app.ui.activity;

import com.oilquiz.app.SmartQuizApplication;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.ClipData;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.agent.AgentSession;
import com.oilquiz.app.ai.chat.AgentExecutionView;
import com.oilquiz.app.ai.chat.ui.ChatBottomSheet;
import com.oilquiz.app.ai.chat.ui.GuideStepFlowView;
import com.oilquiz.app.ai.importing.AgentImportStateMachine;
import com.oilquiz.app.ai.importing.AIImportOrchestrator;
import com.oilquiz.app.ai.importing.v2.ImportDirs;
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
import java.util.Map;

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
    /** 导入预览页请求码 */
    private static final int REQUEST_IMPORT_PREVIEW = 1102;
    /** 存储运行时权限请求码（Android 6~10 使用；Android 11+ 走"所有文件访问"设置页） */
    private static final int REQUEST_CODE_STORAGE_PERMISSION = 1201;

    // 预览页交互等待（onPreviewReady 阻塞等待用户决策）
    private final Object previewWaitLock = new Object();
    private boolean previewPending = false;
    private boolean previewResultSkipIncomplete = false;
    private volatile boolean previewResultReady = false;
    /** 用户是否在预览页点了"取消导入"（RESULT_CANCELED）——为 true 时取消整个导入 */
    private volatile boolean previewResultCancelled = false;
    /** 当前是否已选择"仅导入完整题目"（供最终确认文案显示） */
    private volatile boolean skipIncompleteVar = false;

    // 文件选择区
    private MaterialButton btnSelectFile;
    private TextView tvFileName;
    private MaterialButton btnStartImport;
    private MaterialButton btnCancel;

    // 智能体导入区
    private MaterialButton btnAgentImport;
    private MaterialButton btnAgentMic;
    private AgentSession agentSession;
    // 智能体导入状态机（独立于智能体专用 UI，驱动 GuideStepFlowView + 监控区）
    private AgentImportStateMachine agentImportStateMachine;

    // Agent 执行区
    private AgentExecutionView agentView;

    // 阶段指示（ChatKit GuideStepFlowView：检测/映射/解析/入库）
    private GuideStepFlowView guideStepFlow;

    // 流式指标区（tok/s、token、已入库数在 v2 流程无回调，已在布局隐藏；仅保留进度）
    private TextView tvProgress;
    // 实时监控：耗时 / 步骤 / 推理速度 / Token
    private TextView tvMonitorElapsed;
    private TextView tvMonitorStage;
    private TextView tvMonitorSpeed;
    private TextView tvMonitorTokens;
    private android.os.Handler monitorHandler;
    private Runnable monitorTick;
    private Runnable agentMonitorTick;
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

    // 模型选择区（UI 入口已移除，引擎默认 AUTO 模式继续工作）
    private OnlineModelManager onlineModelManager;
    // 用户题库说明输入区（可选，引导 AI 导入）
    private android.widget.EditText etUserGuide;
    // 缺失字段智能填充开关
    private androidx.appcompat.widget.SwitchCompat swFillMissing;
    // 与智能体直接对话区
    private android.widget.LinearLayout chatLog;
    private android.widget.ScrollView chatLogScroll;
    private android.widget.EditText etChatInput;
    private com.google.android.material.button.MaterialButton btnChatSend;
    private com.google.android.material.button.MaterialButton btnChatMic;
    private android.widget.TextView tvChatHint;
    private final StringBuilder aiStream = new StringBuilder();
    private android.widget.TextView aiBubble;

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

        // 智能体导入区
        btnAgentImport = findViewById(R.id.btnAgentImport);
        btnAgentMic = findViewById(R.id.btnAgentMic);
        if (btnAgentMic != null) {
            btnAgentMic.setOnClickListener(v -> startVoiceForAgent());
        }
        // agentSessionCard/agentSessionContainer 布局保留但不再渲染智能体专用视图（由状态机驱动）

        // Agent 执行区
        agentView = findViewById(R.id.agentView);
        agentView.hide();

        // 阶段指示行（ChatKit 组件）
        guideStepFlow = findViewById(R.id.guideStepFlow);
        guideStepFlow.setSteps(java.util.Arrays.asList("检测", "映射", "解析", "入库"));

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

        // 模型选择 UI 入口已移除（引擎默认 AUTO 模式）

        // 用户题库说明输入区
        etUserGuide = findViewById(R.id.etUserGuide);
        // 缺失字段智能填充开关
        swFillMissing = findViewById(R.id.swFillMissing);
        // 与智能体直接对话区
        chatLog = findViewById(R.id.chatLog);
        chatLogScroll = findViewById(R.id.chatLogScroll);
        etChatInput = findViewById(R.id.etChatInput);
        btnChatSend = findViewById(R.id.btnChatSend);
        btnChatMic = findViewById(R.id.btnChatMic);
        tvChatHint = findViewById(R.id.tvChatHint);
    }

    @Override
    protected void initData() {
        // 创建编排引擎
        orchestrator = new AIImportOrchestrator(this);
        onlineModelManager = OnlineModelManager.getInstance(this);

        // 设置代理错误回调，通知 UI 本地模型故障
        orchestrator.setAgentErrorCallback(msg -> {
            runOnUiThread(() -> showLongToast(msg));
        });
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
                showToast(getString(R.string.h_e74c3ec4));
                return;
            }
            // 导入管线 Python 需读写公共目录 /storage/emulated/0/OilQuiz/，
            // 先主动确认"所有文件访问"权限（不假设已授权），缺失则引导授权后重新导入
            if (!hasPublicStoragePermission()) {
                requestPublicStoragePermission();
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
            showToast(getString(R.string.h_2111ccbb));
        });

        // 智能体导入：AgentSession 驱动 v2 管线（import_start/import_status 工具）
        btnAgentImport.setOnClickListener(v -> {
            if (currentFile == null && selectedFiles.isEmpty()) {
                showToast(getString(R.string.h_e74c3ec4));
                return;
            }
            if (!hasPublicStoragePermission()) {
                requestPublicStoragePermission();
                return;
            }
            startAgentImport();
        });

        // 与智能体直接对话：在既有会话上下文上续聊
        if (btnChatSend != null) {
            btnChatSend.setOnClickListener(v -> sendChatToAgent());
        }
        // 与智能体直接对话：语音输入（识别后自动发送）
        if (btnChatMic != null) {
            btnChatMic.setOnClickListener(v -> startVoiceForChat());
        }
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        // 工具栏右侧"字段说明"菜单项：不依赖内容区布局，任何状态都可点击
        menu.add(android.view.Menu.NONE, 1, android.view.Menu.NONE, "📖 字段说明")
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == 1) {
            new com.oilquiz.app.ui.dialog.FieldGuideDialog(this).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 更新阶段指示（GuideStepFlowView）：当前阶段 RUNNING，其余已过 DONE / 未到 PENDING */
    private void updateStageIndicator(int stepNumber) {
        if (guideStepFlow == null) return;
        for (int i = 0; i < 4; i++) {
            GuideStepFlowView.StepState st = i < stepNumber
                    ? GuideStepFlowView.StepState.DONE
                    : i == stepNumber ? GuideStepFlowView.StepState.RUNNING
                    : GuideStepFlowView.StepState.PENDING;
            guideStepFlow.setStepState(i, st);
        }
    }

    // ======================== 模型选择 ========================

    /**
     * 公共目录权限是否已授予：
     * - Android 11+（R）：MANAGE_EXTERNAL_STORAGE（"所有文件访问"）
     * - Android 6~10（M~R）：READ/WRITE_EXTERNAL_STORAGE
     * 导入管线 Python 需读写公共目录 /storage/emulated/0/OilQuiz/（ImportDirs.publicRoot()），
     * 缺权限时 Python 打开文件会抛 PermissionError，必须在入口主动检查。
     */
    private boolean hasPublicStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager();
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return androidx.core.content.ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                    && androidx.core.content.ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** 未授权时引导授权：Android 11+ 弹说明框跳"所有文件访问"设置页；Android 6~10 走运行时权限弹窗 */
    private void requestPublicStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.h_a902a02e))
                    .setMessage(getString(R.string.h_a65def34)
                            + "未授予时 Python 无法读写该目录。\n"
                            + "请点击\"去授权\"开启\"所有文件访问\"权限，然后重新开始导入。")
                    .setPositiveButton(getString(R.string.h_4a1c90d8), (d, w) -> openAllFilesAccessSetting())
                    .setNegativeButton(getString(R.string.h_625fb26b), null)
                    .show();
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_CODE_STORAGE_PERMISSION);
        }
    }

    /** 跳转系统"所有文件访问"授权页（优先直达本应用，异常时 fallback 通用入口） */
    private void openAllFilesAccessSetting() {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ex) {
                showToast(getString(R.string.h_7bf382b4) + ex.getMessage());
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_STORAGE_PERMISSION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            showToast(granted ? getString(R.string.h_83e1601a) : getString(R.string.h_b0561685));
        }
    }

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

    /**
     * 启动智能体导入：AgentSession 驱动（在线模型推理 + import_start/import_status 工具），
     * 执行过程经 AgentImportStateMachine 状态机翻译为四步骤（检测/映射/解析/入库）+ 监控区展示，
     * 不渲染智能体专用视图（思考/工具调用流水）。
     */
    private void startAgentImport() {
        // 构建智能体指令：明确文件与目标，让 Agent 走 import_start → 轮询 import_status → 汇报
        StringBuilder prompt = new StringBuilder();
        prompt.append("请帮我完成题库智能导入。");
        if (currentFile != null) {
            prompt.append("\n目标文件: ").append(currentFile.getAbsolutePath());
        } else if (!selectedFiles.isEmpty()) {
            for (File f : selectedFiles) {
                prompt.append("\n目标文件: ").append(f.getAbsolutePath());
            }
        }
        String guide = etUserGuide != null ? etUserGuide.getText().toString().trim() : "";
        if (!guide.isEmpty()) {
            prompt.append("\n题库说明: ").append(guide);
        }
        prompt.append("\n请执行智能体全自动导入流程（含预处理）："
                + "0) 先调 import_list_files 确认目标文件存在并拿到完整路径（若上方已给路径可直接用）；"
                + "1) 预处理评估（推荐，多表/表头异常/数据乱时必做）："
                + "用 file_reader(parse_excel) 查看返回的 sheetSummaries（每张表 index/名称/数据行数），"
                + "由你判断哪些表是真正的题库表：选【数据行多、表头字段全】的表，"
                + "排除只有几行数据的示例/说明/目录表；单张有用→sheetMode=index + sheetIndex=<该表索引>；"
                + "多张都有用→sheetMode=multi + sheetIndexes=<JSON数组，如 [1,2]> 全部导入；"
                + "无法判断时才用 sheetMode=best/all；"
                + "用 file_reader(preview/parse_csv) 看数据质量与缺字段分布，决定 fillMissing/skipIncomplete；"
                + "表头脏乱、数据混排、编码异常时，可用 excel_tool 或 python_file_ops/python_execute 清洗修正，"
                + "但清洗产生的文件仅供评估，不要传给 import_start；"
                + "2) 调 import_start 启动导入：filePath 必填，必须传上方【目标文件】的源文件完整路径，"
                + "禁止传预处理产生的临时/清洗文件（Agent 工作区 tmp 每轮执行后自动清理，对话后即失效）；"
                + "预处理结论（用哪张表/是否填缺失/是否跳行/题型）通过 sheetMode/sheetIndex/docHint/fillMissing/skipIncomplete/questionType 参数表达；"
                + "3) 用 import_status 轮询直到 DONE 或 ERROR；"
                + "4) 完成后汇总新增/重复/失败数量并给出简短结论。"
                + "四个决策点（字段映射/数据预览/填充/入库）已全自动放行，无需用户确认。");

        // 状态机驱动导入页自有 UI（GuideStepFlowView 四步骤 + 监控区），不渲染智能体专用视图
        if (agentImportStateMachine == null) {
            agentImportStateMachine = new AgentImportStateMachine(buildAgentImportUi());
        } else {
            agentImportStateMachine.reset();
        }
        if (statsCard != null) statsCard.setVisibility(View.GONE);
        guideStepFlow.setSteps(java.util.Arrays.asList("检测", "映射", "解析", "入库"));
        for (int i = 0; i < 4; i++) {
            guideStepFlow.setStepState(i, i == 0
                    ? GuideStepFlowView.StepState.RUNNING : GuideStepFlowView.StepState.PENDING);
        }
        startAgentMonitor();
        if (agentSession != null) {
            agentSession.shutdown();
        }
        agentSession = AgentSession.create(this);
        // 每次导入开启全新会话：清空 messageHistory/持久化历史（新任务不带上一次上下文）
        agentSession.newSession();
        // 回调双通道：转发状态机（步骤/监控）+ 同步到对话区（流式正文/完成/错误）
        agentSession.setCallback(buildChatCallback(agentImportStateMachine));
        // 清空对话区（新任务从干净对话开始）
        if (chatLog != null) {
            chatLog.removeAllViews();
            if (tvChatHint != null) {
                chatLog.addView(tvChatHint);
                tvChatHint.setVisibility(View.VISIBLE);
            }
        }
        aiBubble = null;
        aiStream.setLength(0);
        agentSession.start(prompt.toString(), 8192);
    }

    /**
     * 与智能体直接对话：发送消息在既有会话上下文上续聊（引擎保留 messageHistory）。
     * 执行中（isBusy）禁止发送；发送后流式回复显示在对话区，工具动作继续更新步骤区。
     */
    private void sendChatToAgent() {
        if (etChatInput == null || agentSession == null) return;
        String text = etChatInput.getText().toString().trim();
        if (text.isEmpty()) return;
        if (agentSession.isBusy()) {
            showToast("智能体正在处理中，请稍候");
            return;
        }
        appendChat("你", text, true);
        etChatInput.setText("");
        aiBubble = null;
        aiStream.setLength(0);
        agentSession.sendMessage(text, 8192);
    }

    /** 追加一条用户/AI 对话气泡 */
    private void appendChat(String who, String text, boolean user) {
        if (chatLog == null) return;
        if (tvChatHint != null) tvChatHint.setVisibility(android.view.View.GONE);
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText((user ? "🙋 " : "🤖 ") + who + "：" + text);
        tv.setTextSize(13);
        tv.setPadding(dp(4), dp(2), dp(4), dp(2));
        tv.setTextColor(colorAttr(user
                ? android.R.attr.textColorPrimary : android.R.attr.textColorSecondary));
        chatLog.addView(tv);
        scrollChatToBottom();
    }

    /** 流式更新当前 AI 气泡（onToken 增量追加） */
    private void updateAiBubble(String text) {
        if (chatLog == null) return;
        if (tvChatHint != null) tvChatHint.setVisibility(android.view.View.GONE);
        if (aiBubble == null) {
            aiBubble = new android.widget.TextView(this);
            aiBubble.setTextSize(13);
            aiBubble.setPadding(dp(4), dp(2), dp(4), dp(2));
            aiBubble.setTextColor(colorAttr(android.R.attr.textColorSecondary));
            chatLog.addView(aiBubble);
        }
        aiBubble.setText("🤖 智能体：" + text);
        scrollChatToBottom();
    }

    private void scrollChatToBottom() {
        if (chatLogScroll != null) {
            chatLogScroll.post(() -> chatLogScroll.fullScroll(android.view.View.FOCUS_DOWN));
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private int colorAttr(int attrRes) {
        android.content.res.TypedArray a = getTheme().obtainStyledAttributes(new int[]{attrRes});
        try {
            return a.getColor(0, 0xFF000000);
        } finally {
            a.recycle();
        }
    }

    /**
     * 包装回调：全部转发给状态机（步骤/监控/结果），同时把正文/完成/错误同步到对话区。
     */
    private com.oilquiz.app.ai.agent.AgentCallback buildChatCallback(
            final com.oilquiz.app.ai.agent.AgentCallback inner) {
        return new com.oilquiz.app.ai.agent.AgentCallback() {
            @Override
            public void onToken(String token) {
                inner.onToken(token);
                aiStream.append(token);
                runOnUiThread(() -> updateAiBubble(aiStream.toString()));
            }

            @Override
            public void onThinkingToken(String token) {
                inner.onThinkingToken(token);
            }

            @Override
            public void onThinkingEnd() {
                inner.onThinkingEnd();
            }

            @Override
            public void onToolCallStart(String toolCallId, String toolName, String args) {
                inner.onToolCallStart(toolCallId, toolName, args);
            }

            @Override
            public void onToolCallComplete(String toolCallId, String toolName,
                                           com.oilquiz.app.ai.agent.online.OnlineToolResult result) {
                inner.onToolCallComplete(toolCallId, toolName, result);
            }

            @Override
            public void onStepUpdate(String step, String detail) {
                inner.onStepUpdate(step, detail);
            }

            @Override
            public void onComplete(String fullText) {
                inner.onComplete(fullText);
                runOnUiThread(() -> {
                    if (aiBubble == null) {
                        updateAiBubble(fullText == null || fullText.isEmpty() ? "（完成）" : fullText);
                    }
                    aiStream.setLength(0);
                });
            }

            @Override
            public void onError(String error) {
                inner.onError(error);
                runOnUiThread(() -> updateAiBubble("⚠ " + error));
            }
        };
    }

    /**
     * 语音指令智能体：点麦克风 → 权限申请 → ASR 检查 → 本地模型预热（如需）→
     * 录音对话框识别 → 语音文本追加到"题库说明"输入框（etUserGuide），供智能体理解。
     * 与对话页语音输入同管线（PermissionResourceProvider + SpeechManager + VoiceInputTool）。
     */
    private void startVoiceForAgent() {
        final com.oilquiz.app.resource.PermissionResourceProvider provider =
                com.oilquiz.app.resource.PermissionResourceProvider.getInstance(this);
        provider.requestMicrophonePermission(this,
                new com.oilquiz.app.resource.PermissionResourceProvider.PermissionCallback() {
                    @Override
                    public void onGranted() {
                        doVoiceForAgent();
                    }

                    @Override
                    public void onDenied(java.util.List<String> deniedPermissions) {
                        showToast("麦克风权限未授予，无法语音输入");
                    }
                });
    }

    /** 权限已授予后：检查 ASR → 本地预热 → 录音识别 → 追加到题库说明 */
    private void doVoiceForAgent() {
        final com.oilquiz.app.ai.speech.SpeechManager speech =
                com.oilquiz.app.ai.speech.SpeechManager.getInstance(this);
        if (!speech.isAnyAsrAvailable()) {
            showToast("语音识别服务不可用");
            return;
        }
        final boolean useLocal = !speech.isAsrAvailable();
        if (useLocal && !com.oilquiz.app.ai.speech.asr.SenseVoiceAsr.isReady()) {
            showToast("正在加载本地语音识别模型…");
            new Thread(() -> {
                try {
                    com.oilquiz.app.ai.speech.asr.SenseVoiceAsr.acquire(this);
                    com.oilquiz.app.ai.speech.asr.SenseVoiceAsr.release();
                    runOnUiThread(this::recordVoiceForAgent);
                } catch (Exception e) {
                    runOnUiThread(() -> showToast("本地语音识别模型加载失败: " + e.getMessage()));
                }
            }).start();
        } else {
            recordVoiceForAgent();
        }
    }

    /** 录音对话框识别（VoiceInputTool record，后台线程），识别文本追加到题库说明 */
    private void recordVoiceForAgent() {
        new Thread(() -> {
            try {
                java.util.Map<String, Object> params = new java.util.HashMap<>();
                params.put("action", "record");
                params.put("duration_seconds", 60);
                params.put("timeout_seconds", 90);
                com.oilquiz.app.ai.tool.VoiceInputTool tool =
                        new com.oilquiz.app.ai.tool.VoiceInputTool(this);
                com.oilquiz.app.ai.tool.AIToolResult r = tool.execute(params);
                if (r != null && r.isSuccess()) {
                    final String text = r.getAdditionalInfo() != null
                            ? String.valueOf(r.getAdditionalInfo().get("text")) : "";
                    runOnUiThread(() -> {
                        if (text != null && !text.isEmpty()) {
                            appendToUserGuide(text);
                            showToast("已识别语音指令，已加入题库说明");
                        } else {
                            showToast("未识别到语音内容");
                        }
                    });
                } else {
                    final String err = (r != null && r.getErrorMessage() != null)
                            ? r.getErrorMessage() : "语音识别失败";
                    runOnUiThread(() -> showToast(err));
                }
            } catch (Exception e) {
                runOnUiThread(() -> showToast("语音输入失败: " + e.getMessage()));
            }
        }).start();
    }

    /** 语音文本追加到题库说明输入框（保留已有内容） */
    private void appendToUserGuide(String text) {
        if (etUserGuide == null) return;
        String cur = etUserGuide.getText().toString();
        String merged = cur.trim().isEmpty() ? text : cur + "\n" + text;
        etUserGuide.setText(merged);
        etUserGuide.setSelection(etUserGuide.getText().length());
    }

    /**
     * 与智能体直接对话（语音）：点对话卡 🎤 → 权限申请 → ASR 检查 → 本地预热 →
     * 录音识别 → 文本追加到对话输入框并自动发送（与题库说明语音同管线）。
     */
    private void startVoiceForChat() {
        final com.oilquiz.app.resource.PermissionResourceProvider provider =
                com.oilquiz.app.resource.PermissionResourceProvider.getInstance(this);
        provider.requestMicrophonePermission(this,
                new com.oilquiz.app.resource.PermissionResourceProvider.PermissionCallback() {
                    @Override
                    public void onGranted() {
                        doVoiceForChat();
                    }

                    @Override
                    public void onDenied(java.util.List<String> deniedPermissions) {
                        showToast("麦克风权限未授予，无法语音对话");
                    }
                });
    }

    /** 权限已授予后：检查 ASR → 本地预热 → 录音识别 → 自动发送对话 */
    private void doVoiceForChat() {
        final com.oilquiz.app.ai.speech.SpeechManager speech =
                com.oilquiz.app.ai.speech.SpeechManager.getInstance(this);
        if (!speech.isAnyAsrAvailable()) {
            showToast("语音识别服务不可用");
            return;
        }
        final boolean useLocal = !speech.isAsrAvailable();
        if (useLocal && !com.oilquiz.app.ai.speech.asr.SenseVoiceAsr.isReady()) {
            showToast("正在加载本地语音识别模型…");
            new Thread(() -> {
                try {
                    com.oilquiz.app.ai.speech.asr.SenseVoiceAsr.acquire(this);
                    com.oilquiz.app.ai.speech.asr.SenseVoiceAsr.release();
                    runOnUiThread(this::recordVoiceForChat);
                } catch (Exception e) {
                    runOnUiThread(() -> showToast("本地语音识别模型加载失败: " + e.getMessage()));
                }
            }).start();
        } else {
            recordVoiceForChat();
        }
    }

    /** 录音识别（VoiceInputTool record，后台线程）：识别文本追加输入框并自动发送 */
    private void recordVoiceForChat() {
        new Thread(() -> {
            try {
                java.util.Map<String, Object> params = new java.util.HashMap<>();
                params.put("action", "record");
                params.put("duration_seconds", 60);
                params.put("timeout_seconds", 90);
                com.oilquiz.app.ai.tool.VoiceInputTool tool =
                        new com.oilquiz.app.ai.tool.VoiceInputTool(this);
                com.oilquiz.app.ai.tool.AIToolResult r = tool.execute(params);
                if (r != null && r.isSuccess()) {
                    final String text = r.getAdditionalInfo() != null
                            ? String.valueOf(r.getAdditionalInfo().get("text")) : "";
                    runOnUiThread(() -> {
                        if (text != null && !text.isEmpty()) {
                            appendChatInputAndSend(text);
                            showToast("已识别，正在发送给智能体");
                        } else {
                            showToast("未识别到语音内容");
                        }
                    });
                } else {
                    final String err = (r != null && r.getErrorMessage() != null)
                            ? r.getErrorMessage() : "语音识别失败";
                    runOnUiThread(() -> showToast(err));
                }
            } catch (Exception e) {
                runOnUiThread(() -> showToast("语音对话失败: " + e.getMessage()));
            }
        }).start();
    }

    /** 语音文本追加到对话输入框并自动发送 */
    private void appendChatInputAndSend(String text) {
        if (etChatInput == null) return;
        String cur = etChatInput.getText().toString();
        String merged = cur.trim().isEmpty() ? text : cur + "\n" + text;
        etChatInput.setText(merged);
        etChatInput.setSelection(etChatInput.getText().length());
        sendChatToAgent();
    }

    /**
     * 智能体导入监控：每秒从状态机读取真实数据（耗时/阶段/速度/token/进度）刷新监控区。
     * 与 v2 本地导入的 startMonitor()（读 LlamaHelper）不同：智能体在线推理没有 Llama 数据，
     * 数据源 = AgentImportStateMachine（token 累计 + import_status 进度解析）。
     */
    private void startAgentMonitor() {
        importStartTime = System.currentTimeMillis();
        if (tvMonitorStage != null) tvMonitorStage.setText("等待");
        agentMonitorTick = new Runnable() {
            @Override public void run() {
                if (agentImportStateMachine == null) return;
                applyMonitor(agentImportStateMachine.getElapsedSec(),
                        agentImportStateMachine.getStageMessage(),
                        agentImportStateMachine.getSpeed(),
                        agentImportStateMachine.getTotalTokens(),
                        agentImportStateMachine.getProgressCurrent(),
                        agentImportStateMachine.getProgressTotal());
                if (monitorHandler != null) {
                    monitorHandler.postDelayed(this, 1000);
                }
            }
        };
        monitorHandler.post(agentMonitorTick);
    }

    /** 智能体监控停止 */
    private void stopAgentMonitor() {
        importStartTime = 0;
        if (monitorHandler != null && agentMonitorTick != null) {
            monitorHandler.removeCallbacks(agentMonitorTick);
            agentMonitorTick = null;
        }
    }

    /** 监控区统一刷新（事件驱动 onMonitor 与每秒 tick 共用） */
    private void applyMonitor(long elapsedSec, String stage, float speed, long tokens,
                              long current, long total) {
        if (tvMonitorElapsed != null) {
            long s = Math.max(0, elapsedSec);
            tvMonitorElapsed.setText(String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60));
        }
        if (tvMonitorStage != null && stage != null && !stage.isEmpty()) tvMonitorStage.setText(stage);
        if (tvMonitorSpeed != null) {
            tvMonitorSpeed.setText(speed > 0 ? String.format(java.util.Locale.US, "%.1f", speed) : "-");
        }
        if (tvMonitorTokens != null) {
            tvMonitorTokens.setText(String.valueOf(Math.max(0, tokens)));
        }
        if (tvProgress != null && total > 0) {
            tvProgress.setText(String.format(java.util.Locale.US, "已处理 %d/%d 行", current, total));
        }
    }

    /** 状态机 UI 适配器：把导入阶段/步骤/监控/结果绑定到导入页自有控件 */
    private AgentImportStateMachine.Ui buildAgentImportUi() {
        return new AgentImportStateMachine.Ui() {
            @Override public void onPhase(AgentImportStateMachine.Phase phase, String message) {
                if (tvMonitorStage != null && message != null) tvMonitorStage.setText(message);
            }
            @Override public void onStep(int stepIndex, GuideStepFlowView.StepState state, String detail) {
                if (guideStepFlow != null) {
                    if (detail != null) guideStepFlow.setStepState(stepIndex, state, detail);
                    else guideStepFlow.setStepState(stepIndex, state);
                }
            }
            @Override public void onStepProcess(int stepIndex, GuideStepFlowView.ProcessItem item) {
                if (guideStepFlow != null) guideStepFlow.addStepProcess(stepIndex, item);
            }
            @Override public void onMonitor(long elapsedSec, String stage, float speed, long tokens,
                                            long current, long total) {
                applyMonitor(elapsedSec, stage, speed, tokens, current, total);
            }
            @Override public void onComplete(String fullText, int imported, int duplicated,
                                             int failed, int totalRows) {
                stopAgentMonitor();
                // 完成总结切换：步骤区保持全 DONE，监控区显示智能体汇总，结果卡亮出统计
                if (tvMonitorStage != null && fullText != null) tvMonitorStage.setText(fullText);
                if (statsCard != null) statsCard.setVisibility(View.VISIBLE);
                if (tvSuccessCount != null && imported >= 0) tvSuccessCount.setText(String.valueOf(imported));
                if (tvDupCount != null && duplicated >= 0) tvDupCount.setText(String.valueOf(duplicated));
                if (tvFailedCount != null && failed >= 0) tvFailedCount.setText(String.valueOf(failed));
                if (tvTotalCount != null && totalRows >= 0) tvTotalCount.setText(String.valueOf(totalRows));
                showToast(fullText != null ? fullText : "智能体导入完成");
            }
            @Override public void onError(String error) {
                stopAgentMonitor();
                showToast(error != null ? error : getString(R.string.h_9bc92f24));
            }
        };
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
        showToast(getString(R.string.h_f2e66a38));
        // 用独立线程池（不占 ExcelUtil 共享单线程池，避免与其他导入排队互相阻塞）
        java.util.concurrent.ExecutorService detectExecutor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        detectExecutor.execute(() -> {
            try {
                List<com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile> profiles =
                        com.oilquiz.app.ai.importing.ExcelSheetPicker.analyzeSheets(file);
                if (profiles.isEmpty()) {
                    runOnUiThread(() -> showToast(getString(R.string.h_b76bb7ef)));
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
                // 收集所有命中题库特征的 sheet（得分≥3 且确有其数据行）：
                // 分题型多 sheet 模板（如 单选/多选/判断/填空 各一个 sheet）应全部导入，
                // 而不是只选评分最高的一个漏掉其他题型。
                java.util.List<com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile> hits =
                        new java.util.ArrayList<>();
                for (com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile p : profiles) {
                    if (isDocSheet(p)) continue; // 说明/模板/示例表不参与选题
                    int score = scoreSheetProfile(p);
                    if (score >= 3 && p.rowCount > p.dataStartRowIndex + 1) {
                        hits.add(p);
                    }
                }

                if (hits.isEmpty()) {
                    // 无足够匹配（封面/说明表或纯说明表）：弹选择对话框兜底
                    runOnUiThread(() -> showSheetChoiceDialog(file, profiles, docHintHolder[0]));
                    return;
                }

                final String docHint = docHintHolder[0];
                if (hits.size() == 1) {
                    // 唯一题库表：直接把原文件 + 选定 sheet 索引传给 v2（Python 直接读取该 sheet，
                    // 不再中转 .md，保证数据完整）
                    final com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile selected = hits.get(0);
                    runOnUiThread(() -> {
                        showToast(getString(R.string.h_b36455c3) + selected.sheetName + "（" + selected.rowCount + getString(R.string.h_27b84e16)
                                + (docHint != null ? "，已解析题库说明" : ""));
                        currentFile = file;
                        runV2Import(selected.sheetIndex, docHint, selected.inferredQuestionType);
                    });
                } else {
                    // 多个题库 sheet（分题型模板/题库拆分等）：弹多选框默认全选，
                    // 用户可取消勾选后统一导入，避免只导一个 sheet 漏掉其他题型
                    runOnUiThread(() -> showSheetChoiceDialog(file, hits, docHint));
                }
            } catch (Exception e) {
                Log.e("AIImportActivity", "工作表检测失败: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    showToast(getString(R.string.h_9bc92f24));
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
                .setTitle(getString(R.string.h_5386c11e))
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton(getString(R.string.h_7d2ff42c), (dialog, which) -> {
                    // 收集选中的工作表
                    java.util.List<Integer> selected = new java.util.ArrayList<>();
                    for (int i = 0; i < profiles.size(); i++) {
                        if (checked[i]) selected.add(i);
                    }
                    if (selected.isEmpty()) {
                        showToast(getString(R.string.h_78442dae));
                        return;
                    }
                    currentFile = file;
                    if (selected.size() == 1) {
                        // 单选：走原单 sheet 流程
                        com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile p =
                                profiles.get(selected.get(0));
                        runV2Import(p.sheetIndex, docHint, p.inferredQuestionType);
                    } else {
                        // 多选：逐 sheet 完整导入，最后汇总（附带各 sheet 推断题型）
                        java.util.List<Integer> idxs = new java.util.ArrayList<>();
                        java.util.List<String> types = new java.util.ArrayList<>();
                        for (int i : selected) {
                            com.oilquiz.app.ai.importing.ExcelSheetPicker.SheetProfile p =
                                    profiles.get(i);
                            idxs.add(p.sheetIndex);
                            types.add(p.inferredQuestionType);
                        }
                        runV2ImportSheets(idxs, docHint, types);
                    }
                })
                .setNegativeButton(getString(R.string.h_625fb26b), null)
                .show();
    }

    /** 启动实时监控：计时器 + 每秒刷新耗时/推理速度/Token（真实数据，来自 LlamaHelper） */
    private void startMonitor() {
        importStartTime = System.currentTimeMillis();
        if (tvMonitorStage != null) tvMonitorStage.setText(getString(R.string.h_8e54ddfe));
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
        runV2Import(-1, null, null);
    }

    /**
     * 执行 v2 导入管线。
     * @param sheetIndex Excel 用户选定工作表索引（-1=自动扫全部）
     * @param docHint    题库说明/模板说明提取的字段约定（映射推理参考），可为 null
     * @param inferredType 工作表名推断的题型（如"单选题"），源表无题型列时填充 questionType
     */
    private void runV2Import(final int sheetIndex, final String docHint,
                             final String inferredType) {
        // 使用本地 AI 引擎创建 v2 导入管线（避免 sign6 错误）
        com.oilquiz.app.ai.importing.v2.ImportMain v2Main =
            new com.oilquiz.app.ai.importing.v2.ImportMain(this, orchestrator);
        String effectiveDocHint = mergeUserGuide(docHint);
        if (effectiveDocHint != null && !effectiveDocHint.isEmpty()) {
            v2Main.setDocHint(effectiveDocHint);
        }
        v2Main.setFillEnabled(swFillMissing == null || swFillMissing.isChecked());
        if (sheetIndex >= 0) {
            v2Main.setExcelSheetIndex(sheetIndex);
        }
        v2Main.setDefaultQuestionType(inferredType);
        // 注入交互处理器：字段映射确认 / 数据预览 / 填充确认 / 最终确认 4 个决策点
        v2Main.setInteractionHandler(createInteractionHandler());
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
                                   final String docHint,
                                   final java.util.List<String> inferredTypes) {
        com.oilquiz.app.ai.importing.v2.ImportMain v2Main =
            new com.oilquiz.app.ai.importing.v2.ImportMain(this, orchestrator);
        String effectiveDocHint = mergeUserGuide(docHint);
        if (effectiveDocHint != null && !effectiveDocHint.isEmpty()) {
            v2Main.setDocHint(effectiveDocHint);
        }
        v2Main.setFillEnabled(swFillMissing == null || swFillMissing.isChecked());
        v2Main.setInteractionHandler(createInteractionHandler());
        activeV2Main = v2Main;

        agentView.show();
        agentView.startExecution();

        com.oilquiz.app.ai.importing.v2.ImportMain.ImportListener v2Listener = createV2Listener();
        startMonitor();

        if (currentFile != null) {
            agentView.updateCurrentStep(1, "SHEET", "📑", "多工作表导入 " + sheetIndexes.size() + " 个");
            v2Main.runSheets(currentFile, sheetIndexes, inferredTypes, v2Listener);
        }
    }

    /**
     * 合并用户输入的题库说明（引导 AI 导入）与自动提取的题库说明。
     * 用户填写优先（放前面），自动提取内容补充在后；两者都为空返回原值。
     */
    private String mergeUserGuide(String docHint) {
        String user = etUserGuide != null ? etUserGuide.getText().toString().trim() : "";
        if (user.isEmpty()) {
            return docHint;
        }
        return "【用户填写】" + user
                + (docHint != null && !docHint.isEmpty() ? "\n【自动提取】" + docHint : "");
    }

    /**
     * 创建交互处理器：AI 导入从"一键跑到底"升级为"关键决策点暂停、用户确认后继续"。
     * 四个决策点：字段映射确认 / 数据预览与错误处理 / 智能填充确认 / 最终入库确认。
     */
    private com.oilquiz.app.ai.importing.v2.ImportMain.InteractionHandler createInteractionHandler() {
        return new com.oilquiz.app.ai.importing.v2.ImportMain.InteractionHandler() {
            private static final String DTAG = "AIImportInteraction";

            @Override
            public com.oilquiz.app.ai.importing.v2.ImportMain.InteractionHandler.Decision
                    onMappingReady(Map<String, String> mapping,
                                   java.util.List<String> headers, String sourceName,
                                   String docHint, String mappingSource) {
                final com.oilquiz.app.ai.importing.v2.ImportMain.InteractionHandler.Decision d =
                        new com.oilquiz.app.ai.importing.v2.ImportMain.InteractionHandler.Decision();
                final java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(1);
                runOnUiThread(() -> {
                    try {
                        new com.oilquiz.app.ui.dialog.ImportMappingConfirmDialog(
                                AIImportActivity.this, headers, mapping, docHint, mappingSource,
                                (confirmed, newMapping) -> {
                                    if (confirmed && newMapping != null && !newMapping.isEmpty()) {
                                        d.newMapping = newMapping;
                                    } else if (!confirmed) {
                                        d.action = com.oilquiz.app.ai.importing.v2.ImportMain
                                                .InteractionHandler.Decision.CANCEL;
                                    }
                                    latch.countDown();
                                }).show();
                    } catch (Exception e) {
                        android.util.Log.w("AIImportInteraction", "映射确认对话框异常: " + e.getMessage());
                        latch.countDown();
                    }
                });
                awaitLatch(latch);
                return d;
            }

            @Override
            public com.oilquiz.app.ai.importing.v2.ImportMain.InteractionHandler.Decision
                    onPreviewReady(com.oilquiz.app.ai.importing.v2.ImportMain.QualityPreview preview,
                                   java.util.List<java.io.File> chunks) {
                final com.oilquiz.app.ai.importing.v2.ImportMain.InteractionHandler.Decision d =
                        new com.oilquiz.app.ai.importing.v2.ImportMain.InteractionHandler.Decision();
                synchronized (previewWaitLock) {
                    previewPending = true;
                    previewResultSkipIncomplete = false;
                    previewResultReady = false;
                    previewResultCancelled = false;
                }
                runOnUiThread(() -> {
                    try {
                        String[] paths = new String[chunks.size()];
                        for (int i = 0; i < chunks.size(); i++) {
                            paths[i] = chunks.get(i).getAbsolutePath();
                        }
                        Intent intent = new Intent(AIImportActivity.this, ImportPreviewActivity.class);
                        intent.putExtra(ImportPreviewActivity.EXTRA_CHUNK_FILES, paths);
                        intent.putExtra(ImportPreviewActivity.EXTRA_PREVIEW, preview);
                        if (preview != null && preview.mappedFields != null) {
                            intent.putStringArrayListExtra(ImportPreviewActivity.EXTRA_MAPPED_FIELDS,
                                    new java.util.ArrayList<>(preview.mappedFields));
                        }
                        startActivityForResult(intent, REQUEST_IMPORT_PREVIEW);
                    } catch (Exception e) {
                        android.util.Log.w(DTAG, "启动预览页异常: " + e.getMessage());
                        synchronized (previewWaitLock) {
                            previewResultReady = true;
                            previewResultSkipIncomplete = false;
                            previewResultCancelled = true; // 预览页无法打开 → 取消导入，不静默继续
                            previewWaitLock.notifyAll();
                        }
                    }
                });
                // 等待预览页返回（onActivityResult 中 notifyAll）。
                // 超时兜底：预览页停留/无响应超过时限 → 取消导入，绝不静默按"全部导入"继续。
                synchronized (previewWaitLock) {
                    long deadline = System.currentTimeMillis() + 300_000; // 5 分钟浏览时间
                    while (!previewResultReady && System.currentTimeMillis() < deadline) {
                        try {
                            previewWaitLock.wait(5000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    if (previewResultCancelled || !previewResultReady) {
                        d.action = com.oilquiz.app.ai.importing.v2.ImportMain
                                .InteractionHandler.Decision.CANCEL;
                    }
                    d.skipIncomplete = previewResultSkipIncomplete;
                    skipIncompleteVar = previewResultSkipIncomplete;
                    previewPending = false;
                }
                return d;
            }

            @Override
            public com.oilquiz.app.ai.importing.v2.ImportMain.InteractionHandler.Decision
                    onFillReady(com.oilquiz.app.ai.importing.v2.ImportMain.QualityPreview preview,
                                int missingCount) {
                final com.oilquiz.app.ai.importing.v2.ImportMain.InteractionHandler.Decision d =
                        new com.oilquiz.app.ai.importing.v2.ImportMain.InteractionHandler.Decision();
                if (missingCount <= 0) return d; // 无缺失，直接继续
                final java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(1);
                runOnUiThread(() -> {
                    try {
                        // 动态缺失字段明细：按实际缺失的字段生成，不硬编码字段名单
                        StringBuilder missDetail = new StringBuilder();
                        if (preview != null && preview.missingByField != null) {
                            for (java.util.Map.Entry<String, Long> e : preview.missingByField.entrySet()) {
                                if (missDetail.length() > 0) missDetail.append("、");
                                missDetail.append(fieldLabel(e.getKey()));
                            }
                        }
                        if (preview != null && preview.emptyQuestionCount > 0) {
                            if (missDetail.length() > 0) missDetail.append("、");
                            missDetail.append(getString(R.string.h_9e264c43));
                        }
                        String msg = "共 " + missingCount + " 道题缺少字段"
                                + (missDetail.length() > 0 ? "（" + missDetail + "）" : "") + "。\n\n"
                                + "规则无法自动补充，是否用 AI 辅助推断缺失字段？\n"
                                + "· AI 辅助填充：更完整，但会调用本地/在线模型（耗时较长）\n"
                                + "· 不填充：缺失字段留空直接入库（更快，入库默认值兜底）";
                        new AlertDialog.Builder(AIImportActivity.this)
                                .setTitle(getString(R.string.h_89a9ad30))
                                .setMessage(msg)
                                .setPositiveButton(getString(R.string.h_72278bd2), (dialog, which) -> {
                                    d.fillEnabled = true;
                                    latch.countDown();
                                })
                                .setNegativeButton(getString(R.string.h_c2bdceb8), (dialog, which) -> {
                                    d.fillEnabled = false;
                                    latch.countDown();
                                })
                                .setNeutralButton(getString(R.string.h_b7e344ae), (dialog, which) -> {
                                    d.action = com.oilquiz.app.ai.importing.v2.ImportMain
                                            .InteractionHandler.Decision.CANCEL;
                                    latch.countDown();
                                })
                                .setCancelable(false)
                                .show();
                    } catch (Exception e) {
                        latch.countDown();
                    }
                });
                awaitLatch(latch);
                return d;
            }

            @Override
            public com.oilquiz.app.ai.importing.v2.ImportMain.InteractionHandler.Decision
                    onFinalConfirm(com.oilquiz.app.ai.importing.v2.ImportMain.QualityPreview preview,
                                   com.oilquiz.app.ai.importing.v2.ImportMain.ImportSummary summary) {
                final com.oilquiz.app.ai.importing.v2.ImportMain.InteractionHandler.Decision d =
                        new com.oilquiz.app.ai.importing.v2.ImportMain.InteractionHandler.Decision();
                final java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(1);
                runOnUiThread(() -> {
                    try {
                        StringBuilder msg = new StringBuilder();
                        msg.append(getString(R.string.h_cf5013ac));
                        if (preview != null) {
                            msg.append(getString(R.string.h_4560e686)).append(preview.completeCount()).append(getString(R.string.h_915b0f78));
                            if (preview.emptyQuestionCount > 0) {
                                msg.append(getString(R.string.h_35460ac0)).append(preview.emptyQuestionCount)
                                        .append(getString(R.string.h_4ccc9cf5));
                            }
                            long dup = preview.getDuplicateCount();
                            if (dup > 0) {
                                msg.append(getString(R.string.h_10f4b9d3)).append(dup).append(getString(R.string.h_c1a7e7fd));
                                int shown = 0;
                                for (com.oilquiz.app.ai.importing.v2.ImportMain
                                        .QualityPreview.DuplicateDetail det
                                        : preview.duplicateDetails) {
                                    if (shown++ >= 5) break;
                                    msg.append(getString(R.string.h_c55d2e95)).append(joinRows(det.rows))
                                            .append(getString(R.string.h_5c6640af)).append(truncate(det.question, 36))
                                            .append("」\n");
                                }
                                if (preview.duplicateDetails.size() > 5) {
                                    msg.append(getString(R.string.h_e163a37e)).append(preview.duplicateDetails.size())
                                            .append(getString(R.string.h_4416bbc7));
                                }
                            }
                            if (!preview.stemVariantDetails.isEmpty()) {
                                msg.append(getString(R.string.h_7cfecc53));
                                int shown = 0;
                                for (com.oilquiz.app.ai.importing.v2.ImportMain
                                        .QualityPreview.DuplicateDetail det
                                        : preview.stemVariantDetails) {
                                    if (shown++ >= 5) break;
                                    msg.append(getString(R.string.h_c55d2e95)).append(joinRows(det.rows))
                                            .append(getString(R.string.h_5c6640af)).append(truncate(det.question, 36))
                                            .append("」\n");
                                }
                            }
                            if (preview.incompleteCount > 0) {
                                // 动态缺字段明细
                                StringBuilder missDetail = new StringBuilder();
                                for (java.util.Map.Entry<String, Long> e : preview.missingByField.entrySet()) {
                                    if (missDetail.length() > 0) missDetail.append("、");
                                    missDetail.append(fieldLabel(e.getKey())).append(" ").append(e.getValue()).append(" 题");
                                }
                                msg.append(getString(R.string.h_d3e61377)).append(preview.incompleteCount).append(" 道")
                                        .append(missDetail.length() > 0 ? "（" + missDetail + "）" : "")
                                        .append(getString(R.string.h_cffaa30e)).append(skipIncompleteVar ? getString(R.string.h_92636e8c) : getString(R.string.h_8d9a071e)).append("）\n");
                            }
                        } else {
                            msg.append(getString(R.string.h_eeeda977)).append(summary.totalRows).append(getString(R.string.h_5c5e3bed));
                        }
                        msg.append(getString(R.string.h_e49edbf3));
                        new AlertDialog.Builder(AIImportActivity.this)
                                .setTitle(getString(R.string.h_3e36a94f))
                                .setMessage(msg.toString())
                                .setPositiveButton(getString(R.string.h_7d2ff42c), (dialog, which) -> {
                                    latch.countDown();
                                })
                                .setNegativeButton(getString(R.string.h_625fb26b), (dialog, which) -> {
                                    d.action = com.oilquiz.app.ai.importing.v2.ImportMain
                                            .InteractionHandler.Decision.CANCEL;
                                    latch.countDown();
                                })
                                .setCancelable(false)
                                .show();
                    } catch (Exception e) {
                        latch.countDown();
                    }
                });
                awaitLatch(latch);
                return d;
            }
        };
    }

    /** 标准字段 → 中文显示名（用于动态缺失字段文案） */
    private static String fieldLabel(String field) {
        switch (field == null ? "" : field) {
            case "questionType": return "题型";
            case "difficulty": return "难度";
            case "category": return "分类";
            case "explanation": return "解析";
            case "correctAnswer": return "答案";
            case "answerText": return "答案";
            case "knowledgePoint": return "知识点";
            case "subCategory": return "子分类";
            case "tags": return "标签";
            case "hint": return SmartQuizApplication.getAppContext().getString(R.string.h_02d9819d);
            case "points": return "分值";
            case "timeLimit": return "时限";
            case "author": return "作者";
            case "comment": return "备注";
            case "source": return "来源";
            default: return field;
        }
    }

    private void awaitLatch(java.util.concurrent.CountDownLatch latch) {        try {
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 行号列表 → "112、130"（中文顿号分隔） */
    private static String joinRows(java.util.List<Integer> rows) {
        if (rows == null || rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append('、');
            sb.append(rows.get(i));
        }
        return sb.toString();
    }

    /** 截断长文本（用于弹窗/摘要展示） */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
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
                btnCancel.setText(getString(R.string.h_b15d9127));
                agentView.completeExecution("智能导入");
                tvSuccessCount.setText(String.valueOf(result.imported));
                tvDupCount.setText(String.valueOf(result.duplicated));
                tvTotalCount.setText(String.valueOf(result.totalRows));
                tvFailedCount.setText(String.valueOf(result.failed));
                statsCard.setVisibility(View.VISIBLE);
                if (tvMonitorStage != null) tvMonitorStage.setText(getString(R.string.h_769d88e4));
                stopMonitor();
                String doneMsg = "导入完成: 新增 " + result.imported + " 题";
                if (result.duplicated > 0) {
                    doneMsg += "（" + result.duplicated + " 题与题库已有题目重复，未重复入库）";
                }
                showToast(doneMsg);
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                importFinished = true;
                btnCancel.setText(getString(R.string.h_b15d9127));
                agentView.failExecution(message);
                stopMonitor();
                showLongToast(getString(R.string.h_45332d13) + message);
            }
        };
    }

    /** 多文件批量导入 */
    private java.util.List<File> selectedFiles = new ArrayList<>();

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 导入预览页返回：写入等待锁，唤醒 onPreviewReady
        if (requestCode == REQUEST_IMPORT_PREVIEW) {
            synchronized (previewWaitLock) {
                previewResultReady = true;
                if (resultCode == RESULT_OK && data != null) {
                    previewResultSkipIncomplete = data.getBooleanExtra(
                            ImportPreviewActivity.EXTRA_DECISION_SKIP_INCOMPLETE, false);
                } else if (resultCode == RESULT_CANCELED) {
                    // 用户点了"取消导入"或按返回：整个导入取消，绝不静默继续
                    previewResultCancelled = true;
                    previewResultSkipIncomplete = false;
                } else {
                    previewResultSkipIncomplete = false;
                }
                previewWaitLock.notifyAll();
            }
            return;
        }
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
            tvFileName.setText(getString(R.string.h_f0409ecf));
            btnStartImport.setEnabled(false);
            return;
        }

        if (selectedFiles.size() == 1) {
            // 单文件：显示文件名
            tvFileName.setText(selectedFiles.get(0).getName());
            btnStartImport.setEnabled(true);
        } else {
            // 多文件：显示数量
            tvFileName.setText(getString(R.string.h_943b9226) + selectedFiles.size() + getString(R.string.h_7c645c81));
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

    /** 将 content Uri 内容拷贝到公共目录 source/（持久），保留原始文件名与后缀。
     *  私有 cache 副本会被系统低存储清理、且智能体对话后副本可能失效，故导入源必须落在公共目录。 */
    private File createTempFileFromUri(Uri uri) throws IOException {
        ContentResolver resolver = getContentResolver();
        InputStream input = resolver.openInputStream(uri);
        if (input == null) {
            return null;
        }
        // 取原始文件名以保留后缀
        String displayName = getFileNameFromUri(uri);
        if (displayName == null || displayName.isEmpty()) {
            displayName = "ai_import_" + System.currentTimeMillis() + ".xlsx";
        }
        String extension = "";
        if (displayName.lastIndexOf('.') > 0) {
            extension = displayName.substring(displayName.lastIndexOf('.'));
        }
        File dir = ImportDirs.sourceDir();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File tempFile = new File(dir, displayName);
        // 重名加时间戳后缀，避免覆盖用户已有文件
        if (tempFile.exists()) {
            String base = extension.isEmpty() ? displayName
                    : displayName.substring(0, displayName.length() - extension.length());
            tempFile = new File(dir, base + "_" + System.currentTimeMillis() + extension);
        }
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
        if (agentSession != null) {
            agentSession.shutdown();
            agentSession = null;
        }
        stopMonitor();
        stopAgentMonitor();
        // 预览页打开期间 Activity 被销毁（用户退出等）：唤醒等待锁并标记取消，
        // 避免导入线程永久阻塞在 previewWaitLock 上
        synchronized (previewWaitLock) {
            previewResultCancelled = true;
            previewResultReady = true;
            previewWaitLock.notifyAll();
        }
        if (orchestrator != null) {
            orchestrator.cancel();
        }
        if (activeV2Main != null) {
            activeV2Main.cancel();
        }
    }
}
