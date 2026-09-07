package com.oilquiz.app.ui.activity;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.model.ModelDownloadManager;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AIServiceInitializer;
import com.oilquiz.app.ai.service.AIServiceState;
import com.oilquiz.app.ui.adapter.ModelAdapter;
import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.ui.widget.DownloadProgressView;
import com.oilquiz.app.ui.widget.InitStepIndicatorView;
import com.oilquiz.app.ui.widget.ModelPickerView;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 服务初始化 — 单屏引导（功能控件组合）
 *
 * 单屏内完成：检测 → 选模型 → 下载 → 选择启用 → 初始化 → 完成。
 * UI 由功能控件组合，Activity 仅做编排（状态机 + 复用已有机制）：
 *   - 步骤指示器：{@link InitStepIndicatorView}
 *   - 选下载模型：{@link ModelPickerView}（数据 ModelDownloadManager）
 *   - 下载/初始化进度：{@link DownloadProgressView}
 *   - 已下载模型列表：ModelAdapter（数据 AIService.getAvailableModels）
 *   - 初始化状态：AIService.DetailedStatusObserver（阶段/消息/进度/耗时）
 *   - 检测：AIServiceInitializer.needsInitialization（内部整合 AIService + OnlineModelManager）
 */
public class AIServiceInitActivity extends BaseActivity
        implements ModelAdapter.OnModelClickListener, ModelPickerView.OnModelSelectedListener {

    private static final String TAG = "AIServiceInitActivity";

    private static final String[] STEP_LABELS = {"选模型", "下载", "启用", "完成"};
    private static final String[] STEP_EMOJIS = {"🎯", "📥", "🚀", "✅"};

    private static final int STEP_MODEL = 0;
    private static final int STEP_DOWNLOAD = 1;
    private static final int STEP_SELECT = 2;
    private static final int STEP_DONE = 3;

    private TextView tvTitle;
    private TextView tvSubtitle;
    private LinearLayout checkSection;
    private TextView tvCheckStatus;
    private LinearLayout modelPickerSection;
    private TextView tvPickHint;
    private LinearLayout selectSection;
    private TextView tvSelectStatus;
    private LinearLayout doneSection;
    private TextView tvDoneModel;
    private RecyclerView modelListRecycler;
    private MaterialButton btnPrev;
    private MaterialButton btnAction;
    private TextView tvHint;
    private FrameLayout logoContainer;
    private ObjectAnimator logoPulse;

    private InitStepIndicatorView stepIndicator;
    private ModelPickerView modelPicker;
    private DownloadProgressView downloadProgress;

    private ModelAdapter modelAdapter;
    /** 复用 AIService 完整初始化状态监控（阶段/消息/进度/耗时） */
    private AIService.DetailedStatusObserver detailedObserver;

    private boolean running = false;
    private boolean done = false;
    private volatile boolean destroyed = false;
    /** 检测任务代数：重试时递增，防止旧检测线程晚到覆盖新状态 */
    private int checkGeneration = 0;
    /** 后台下载监控模式：上次退出时初始化任务未完成，进入本页时监控其进度 */
    private boolean monitoring = false;
    private final Handler monitorHandler = new Handler(Looper.getMainLooper());

    @Override
    protected int getLayoutId() {
        return R.layout.activity_ai_service_init;
    }

    @Override
    protected void initView() {
        tvTitle = findViewById(R.id.tv_title);
        tvSubtitle = findViewById(R.id.tv_subtitle);
        checkSection = findViewById(R.id.check_section);
        tvCheckStatus = findViewById(R.id.tv_check_status);
        modelPickerSection = findViewById(R.id.model_picker_section);
        tvPickHint = findViewById(R.id.tv_pick_hint);
        selectSection = findViewById(R.id.select_section);
        tvSelectStatus = findViewById(R.id.tv_select_status);
        doneSection = findViewById(R.id.done_section);
        tvDoneModel = findViewById(R.id.tv_done_model);
        modelListRecycler = findViewById(R.id.model_list_recycler);
        btnPrev = findViewById(R.id.btn_prev);
        btnAction = findViewById(R.id.btn_action);
        tvHint = findViewById(R.id.tv_hint);
        logoContainer = findViewById(R.id.logo_container);
        stepIndicator = findViewById(R.id.step_indicator);
        modelPicker = findViewById(R.id.model_picker);
        downloadProgress = findViewById(R.id.download_progress);

        stepIndicator.setSteps(STEP_LABELS, STEP_EMOJIS);
        modelPicker.setOnModelSelectedListener(this);
        startLogoPulse();
    }

    @Override
    protected void initData() {
        // 检测当前配置状态（复用 AIServiceInitializer，内部整合 AIService + OnlineModelManager）
        final int gen = ++checkGeneration;
        hideAllSections();
        checkSection.setVisibility(View.VISIBLE);
        btnPrev.setVisibility(View.GONE);
        btnAction.setVisibility(View.GONE);
        tvCheckStatus.setText(getString(R.string.h_6c4acb63));

        new Thread(() -> {
            // 后台已有下载/初始化任务进行中（上次退出未完成）→ 进入监控模式，
            // 而不是让用户重新选模型（此时 AIServiceInitializer.start 会被全局互斥拒绝）。
            if (AIServiceInitializer.isInitializing()) {
                runOnUiThread(() -> {
                    // 代数不一致说明已被新的检测任务取代，丢弃本次结果
                    if (destroyed || gen != checkGeneration) return;
                    enterMonitorMode();
                });
                return;
            }
            boolean need = AIServiceInitializer.needsInitialization(this);
            runOnUiThread(() -> {
                // 代数不一致说明已被新的检测任务取代，丢弃本次结果
                if (destroyed || gen != checkGeneration) return;
                if (!need) {
                    done = true;
                    checkSection.setVisibility(View.GONE);
                    tvTitle.setText(getString(R.string.h_92970f05));
                    tvSubtitle.setText(describeConfiguredState());
                    btnAction.setVisibility(View.VISIBLE);
                    btnAction.setText(getString(R.string.h_d48da8e6));
                    btnAction.setOnClickListener(v -> finish());
                    tvHint.setText(getString(R.string.h_446ec1d0));
                } else {
                    showModelPicker();
                }
            });
        }, "ai-init-check").start();
    }

    // ==================== 后台下载监控模式 ====================

    private final Runnable monitorRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !monitoring) return;
            refreshMonitorProgress();
            if (!AIServiceInitializer.isInitializing()) {
                // 后台任务结束：重新检测配置状态，决定进入选择页或完成态
                monitoring = false;
                recheckAfterMonitor();
                return;
            }
            monitorHandler.postDelayed(this, 500);
        }
    };

    /** 进入监控模式：显示下载进度控件，轮询 ModelDownloadManager 活动任务刷新进度 */
    private void enterMonitorMode() {
        monitoring = true;
        hideAllSections();
        downloadProgress.setVisibility(View.VISIBLE);
        stepIndicator.setActiveStep(STEP_DOWNLOAD, 1);
        btnPrev.setVisibility(View.GONE);
        btnAction.setVisibility(View.GONE);
        tvTitle.setText(getString(R.string.h_996093fd));
        tvSubtitle.setText(getString(R.string.h_07aaf362));
        downloadProgress.reset();
        downloadProgress.setStatus("正在同步后台下载进度…");
        tvHint.setText(getString(R.string.h_353c7371));
        monitorHandler.post(monitorRunnable);
    }

    /** 轮询刷新：主模型为主进度环，mmproj 视觉模块为独立副进度 */
    private void refreshMonitorProgress() {
        try {
            ModelDownloadManager m = ModelDownloadManager.getInstance(this);
            List<ModelDownloadManager.DownloadProgress> list = m.getActiveDownloadProgress();
            if (list == null || list.isEmpty()) {
                downloadProgress.setStatus("正在等待下载任务…");
                return;
            }
            for (ModelDownloadManager.DownloadProgress p : list) {
                if (p.modelId != null && p.modelId.endsWith("_mmproj")) {
                    downloadProgress.updateSecondaryProgress("视觉模块",
                            p.getProgressPercent(), p.downloadedBytes / (1024 * 1024), p.totalBytes / (1024 * 1024));
                } else {
                    String label = p.modelId != null ? p.modelId : "模型";
                    downloadProgress.updateProgress(p.getProgressPercent(),
                            "正在下载 " + label + "（" + (p.downloadedBytes / (1024 * 1024))
                                    + "/" + (p.totalBytes / (1024 * 1024)) + " MB）");
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "refreshMonitorProgress failed: " + e.getMessage());
        }
    }

    /** 后台任务结束后重新检测：已配置→完成态；已下载未配置→选择页；否则回选模型 */
    private void recheckAfterMonitor() {
        new Thread(() -> {
            boolean need = AIServiceInitializer.needsInitialization(this);
            runOnUiThread(() -> {
                if (destroyed) return;
                if (!need) {
                    done = true;
                    hideAllSections();
                    doneSection.setVisibility(View.VISIBLE);
                    stepIndicator.setActiveStep(STEP_DONE, 2);
                    tvTitle.setText(getString(R.string.h_741e41f5));
                    tvSubtitle.setText(getString(R.string.h_92970f05));
                    if (tvDoneModel != null) {
                        String cur = null;
                        try {
                            cur = AIService.getInstance(this).getCurrentModelName();
                        } catch (Exception ignored) {
                        }
                        tvDoneModel.setText(cur != null ? cur : describeConfiguredState());
                    }
                    btnPrev.setVisibility(View.GONE);
                    btnAction.setVisibility(View.VISIBLE);
                    btnAction.setText(getString(R.string.h_85d22db7));
                    btnAction.setOnClickListener(v -> finish());
                    tvHint.setText(getString(R.string.h_55fa40f7));
                    playCompleteBounce();
                } else if (AIServiceInitializer.isInitializing()) {
                    // 又起新任务（如重试下载）→ 继续监控
                    enterMonitorMode();
                } else {
                    showSelectModel();
                }
            });
        }, "ai-init-recheck").start();
    }

    @Override
    protected void initListener() {
        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        if (btnPrev != null) {
            btnPrev.setOnClickListener(v -> {
                if (running) return;
                // 从"选择启用"返回"选下载模型"
                if (selectSection.getVisibility() == View.VISIBLE) {
                    showModelPicker();
                }
            });
        }
    }

    // ==================== 功能区切换（单屏内流转） ====================

    private void hideAllSections() {
        checkSection.setVisibility(View.GONE);
        modelPickerSection.setVisibility(View.GONE);
        downloadProgress.setVisibility(View.GONE);
        selectSection.setVisibility(View.GONE);
        doneSection.setVisibility(View.GONE);
    }

    /** Step 1：选下载模型 */
    private void showModelPicker() {
        hideAllSections();
        modelPickerSection.setVisibility(View.VISIBLE);
        stepIndicator.setActiveStep(STEP_MODEL, 1);
        tvTitle.setText(getString(R.string.h_f2d3731b));
        tvSubtitle.setText(getString(R.string.h_f233a9c6));
        btnPrev.setVisibility(View.GONE);
        btnAction.setVisibility(View.VISIBLE);
        btnAction.setText(getString(R.string.h_d7f0d336));
        btnAction.setOnClickListener(v -> startDownload(modelPicker.getSelectedId()));
        tvHint.setText(getString(R.string.h_6e536ed3));

        try {
            ModelDownloadManager manager = ModelDownloadManager.getInstance(this);
            List<ModelDownloadManager.ModelPresetInfo> list = manager.getPresetDomesticModels();
            String def = AIServiceInitializer.DEFAULT_MODEL_ID;
            modelPicker.setModels(list, def);
            if (modelPicker.isEmpty()) {
                tvTitle.setText(getString(R.string.h_6787f8b5));
                tvSubtitle.setText(getString(R.string.h_4d0da61b));
                btnAction.setText(getString(R.string.h_132c5cdc));
                btnAction.setOnClickListener(v -> initData());
                return;
            }
            updatePickHint();
        } catch (Exception e) {
            AILogger.e(TAG, "加载模型列表失败", e);
            tvTitle.setText(getString(R.string.h_6787f8b5));
            tvSubtitle.setText(getString(R.string.h_4d0da61b));
            btnAction.setText(getString(R.string.h_132c5cdc));
            btnAction.setOnClickListener(v -> initData());
        }
    }

    @Override
    public void onModelSelected(String modelId) {
        updatePickHint();
    }

    private void updatePickHint() {
        if (tvPickHint == null || modelPicker == null) return;
        String nm = modelPicker.getSelectedName();
        tvPickHint.setText(getString(R.string.h_37470051) + nm + getString(R.string.h_670c9e3b));
    }

    /** Step 2：下载（复用 AIServiceInitializer） */
    private void startDownload(String modelId) {
        if (running) return;
        running = true;
        hideAllSections();
        downloadProgress.setVisibility(View.VISIBLE);
        stepIndicator.setActiveStep(STEP_DOWNLOAD, 1);
        btnPrev.setVisibility(View.GONE);
        btnAction.setVisibility(View.GONE);

        String name = modelPicker.getSelectedName();
        tvTitle.setText(getString(R.string.h_e4090eb7));
        tvSubtitle.setText(name != null ? name : modelId);
        downloadProgress.reset();
        downloadProgress.setStatus("准备下载…");
        tvHint.setText(getString(R.string.h_521de9dd));

        final java.util.concurrent.atomic.AtomicBoolean downloadHandled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        AIServiceInitializer.start(this,
                modelId != null && !modelId.isEmpty() ? modelId : AIServiceInitializer.DEFAULT_MODEL_ID,
                new AIServiceInitializer.InitCallback() {
                    @Override
                    public void onProgress(String message, int percent) {
                        runOnUiThread(() -> {
                            if (destroyed) return;
                            downloadProgress.updateProgress(percent, message);
                        });
                    }

                    @Override
                    public void onSecondaryProgress(String label, int percent, long downloadedMB, long totalMB) {
                        runOnUiThread(() -> {
                            if (destroyed) return;
                            downloadProgress.updateSecondaryProgress(label, percent, downloadedMB, totalMB);
                        });
                    }

                    @Override
                    public void onDownloadReady(String modelName, String modelFileName) {
                        // 下载完成（两步分离第一步）→ 进入已下载列表选择启用
                        if (downloadHandled.compareAndSet(false, true)) {
                            runOnUiThread(() -> {
                                if (destroyed) return;
                                onDownloadDone();
                            });
                        }
                    }

                    @Override
                    public void onComplete(String modelName, boolean downloaded) {
                        // 防御：与 onDownloadReady 二选一处理，避免重复进入选择页
                        if (downloadHandled.compareAndSet(false, true)) {
                            runOnUiThread(() -> {
                                if (destroyed) return;
                                onDownloadDone();
                            });
                        }
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            if (destroyed) return;
                            running = false;
                            downloadProgress.updateProgress(0, "❌ " + friendlyError(error));
                            btnAction.setVisibility(View.VISIBLE);
                            btnAction.setText(getString(R.string.h_132c5cdc));
                            btnAction.setOnClickListener(v -> startDownload(modelPicker.getSelectedId()));
                            tvHint.setText(getString(R.string.h_58c9ec40));
                        });
                    }
                });
    }

    private void onDownloadDone() {
        running = false;
        downloadProgress.updateProgress(100, "✅ 下载完成");
        showSelectModel();
    }

    /** Step 3：选择已下载模型启用（复用 ModelAdapter） */
    private void showSelectModel() {
        hideAllSections();
        selectSection.setVisibility(View.VISIBLE);
        stepIndicator.setActiveStep(STEP_DOWNLOAD, 2);
        stepIndicator.setActiveStep(STEP_SELECT, 1);
        tvTitle.setText(getString(R.string.h_4209cb13));
        tvSubtitle.setText(getString(R.string.h_b0f6aae0));
        btnPrev.setVisibility(View.VISIBLE);
        btnAction.setVisibility(View.GONE);
        tvHint.setText(getString(R.string.h_d80bc678));

        modelListRecycler.setLayoutManager(new LinearLayoutManager(this));
        modelListRecycler.setNestedScrollingEnabled(false);
        try {
            AIService aiService = AIService.getInstance(this);
            String[] available = aiService.getAvailableModels();
            List<String> modelNames = new ArrayList<>();
            if (available != null) {
                for (String m : available) {
                    if (m != null && !m.trim().isEmpty()) modelNames.add(m);
                }
            }
            String cur = aiService.getCurrentModelName();
            modelAdapter = new ModelAdapter(this, modelNames, cur, this);
            modelListRecycler.setAdapter(modelAdapter);
            tvSelectStatus.setText(modelNames.isEmpty()
                    ? "未检测到已下载模型，请返回重新下载"
                    : "共 " + modelNames.size() + " 个模型，点击开始初始化");
        } catch (Exception e) {
            AILogger.e(TAG, "加载已下载模型列表失败", e);
            tvSelectStatus.setText(getString(R.string.h_5aa13619) + e.getMessage());
        }
    }

    @Override
    public void onModelClick(String modelName) {
        if (running || done) return;
        initModel(modelName);
    }

    // ==================== 初始化（复用 AIService 完整状态监控） ====================

    private void initModel(String modelName) {
        running = true;
        hideAllSections();
        downloadProgress.setVisibility(View.VISIBLE);
        stepIndicator.setActiveStep(STEP_SELECT, 1);
        btnPrev.setVisibility(View.GONE);
        btnAction.setVisibility(View.GONE);
        tvTitle.setText(getString(R.string.h_dddc76ed));
        tvSubtitle.setText(modelName);
        downloadProgress.reset();
        downloadProgress.setStatus("正在初始化…");
        tvHint.setText(getString(R.string.h_f37794e3));

        try {
            final AIService aiService = AIService.getInstance(this);

            // 复用 AI 服务自带的状态监控：阶段 + 消息 + 进度 + 耗时（主线程回调）
            detailedObserver = new AIService.DetailedStatusObserver() {
                @Override
                public void onStateChanged(AIServiceState.ServiceStage stage, String message, int progress, long elapsedMs) {
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        String msg = (message != null && !message.isEmpty()) ? message : stageName(stage);
                        String extra = progress >= 0 ? " " + progress + "%" : "";
                        if (elapsedMs > 0) extra += " · " + (elapsedMs / 1000) + "s";
                        downloadProgress.updateProgress(progress, msg + extra);
                    });
                }

                @Override
                public void onError(String errorMessage) {
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        failInit(errorMessage != null && !errorMessage.isEmpty() ? errorMessage : "初始化失败");
                    });
                }

                @Override
                public void onInitialized(String model, long loadTimeMs) {
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        finishInitDone(model);
                    });
                }
            };
            aiService.registerDetailedStatusObserver(detailedObserver);

            // 触发模型加载（状态由 detailedObserver 驱动 UI）
            // 注意：hotSwitchModel 对"目标 == 当前模型"走快速路径直接回调成功而不真正加载，
            // 而初始化场景可能因当前模型文件损坏进入此页（needsInitialization=true），
            // 若恰好点击它会导致"假完成"——必须强制完整加载。
            String curName = aiService.getCurrentModelName();
            boolean sameAsCurrent = modelName != null && modelName.equals(curName);
            if (sameAsCurrent && !aiService.isInitialized()) {
                new Thread(() -> {
                    boolean ok = aiService.switchModel(modelName);
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        if (ok && !done) {
                            finishInitDone(modelName);
                        } else if (!ok && !done) {
                            failInit("模型初始化失败，请重试或检查模型文件");
                        }
                    });
                }, "ai-init-force-load").start();
                return;
            }

            boolean started = aiService.hotSwitchModel(modelName, new AIService.HotSwitchCallback() {
                @Override
                public void onSwitchStarted(String fromModel, String toModel) {
                }

                @Override
                public void onSwitchProgress(int progress, String message) {
                }

                @Override
                public void onSwitchCompleted(boolean success, String model) {
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        if (success && !done) {
                            finishInitDone(model);
                        } else if (!success && !done) {
                            failInit("模型初始化失败，请重试或检查模型文件");
                        }
                    });
                }

                @Override
                public void onSwitchFailed(String reason) {
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        if (!done) failInit("模型初始化失败：" + reason);
                    });
                }
            });
            // 防御：hotSwitchModel 返回 false 但未回调（如目标名为空），避免 UI 卡在"正在初始化"
            if (!started) {
                runOnUiThread(() -> {
                    if (destroyed || done) return;
                    failInit("模型切换未启动，请重试");
                });
            }
        } catch (Exception e) {
            AILogger.e(TAG, "初始化模型异常", e);
            failInit("模型初始化异常：" + e.getMessage());
        }
    }

    /** ServiceStage 枚举 → 中文阶段名（兜底显示） */
    private String stageName(AIServiceState.ServiceStage stage) {
        if (stage == null) return "初始化中";
        switch (stage) {
            case UNINITIALIZED: return "AI服务未初始化";
            case NATIVE_LIBRARY_LOADING: return "加载原生库…";
            case MODEL_FILE_PREPARING: return "准备模型文件…";
            case MODEL_LOADING: return "加载模型…";
            case GPU_INITIALIZATION: return "初始化 GPU 加速…";
            case CPU_FALLBACK: return "GPU 初始化失败，切换 CPU 模式…";
            case CHAT_CONTEXT_CREATING: return "创建对话上下文…";
            case INITIALIZED: return "AI 服务已就绪";
            case ERROR: return "初始化失败";
            default: return "初始化中";
        }
    }

    private void finishInitDone(String model) {
        // 完成可能由两条通道触发（detailedObserver.onInitialized / hotSwitchModel.onSwitchCompleted），
        // 第一条已置 done=true，这里直接丢弃后续，避免重复弹跳/重复收尾。
        if (done) return;
        running = false;
        done = true;
        unregisterDetailedObserver();
        downloadProgress.updateProgress(100, "✅ 初始化完成");
        if (tvDoneModel != null) tvDoneModel.setText(model);
        showDone();
    }

    private void failInit(String error) {
        // 失败可能由两条通道触发（detailedObserver.onError / hotSwitchModel.onSwitchFailed），
        // 已成功（done=true）后到达的失败回调直接丢弃，避免覆盖完成态。
        if (done) return;
        running = false;
        unregisterDetailedObserver();
        downloadProgress.updateProgress(0, "❌ " + error);
        btnAction.setVisibility(View.VISIBLE);
        btnAction.setText(getString(R.string.h_e6210140));
        btnAction.setOnClickListener(v -> showSelectModel());
    }

    /** Step 4：完成 */
    private void showDone() {
        hideAllSections();
        doneSection.setVisibility(View.VISIBLE);
        stepIndicator.setActiveStep(STEP_SELECT, 2);
        stepIndicator.setActiveStep(STEP_DONE, 2);
        tvTitle.setText(getString(R.string.h_741e41f5));
        tvSubtitle.setText(getString(R.string.h_92970f05));
        btnPrev.setVisibility(View.GONE);
        btnAction.setVisibility(View.VISIBLE);
        btnAction.setText(getString(R.string.h_85d22db7));
        btnAction.setOnClickListener(v -> finish());
        tvHint.setText(getString(R.string.h_55fa40f7));
        playCompleteBounce();
    }

    private void unregisterDetailedObserver() {
        try {
            if (detailedObserver != null) {
                AIService.getInstance(this).unregisterDetailedStatusObserver(detailedObserver);
                detailedObserver = null;
            }
        } catch (Exception e) {
            AILogger.w(TAG, "注销状态观察者失败: " + e.getMessage());
        }
    }

    /** 复用 AIService / OnlineModelManager 检测机制，返回已配置详情 */
    private String describeConfiguredState() {
        try {
            AIService ai = AIService.getInstance(this);
            if (ai != null && ai.isInitialized()) {
                String m = ai.getCurrentModelName();
                return "本地模型已加载" + (m != null ? "：" + m : "") + "，可直接使用";
            }
            OnlineModelManager om = OnlineModelManager.getInstance(this);
            if (om != null && om.hasActiveOnlineModel()) {
                OnlineModelManager.OnlineModelConfig act = om.getActiveModel();
                return "在线模型已配置" + (act != null && act.name != null ? "：" + act.name : "")
                        + "，可直接使用";
            }
            if (ai != null && ai.getCurrentModelName() != null) {
                return "本地模型已配置：" + ai.getCurrentModelName();
            }
        } catch (Exception e) {
            AILogger.w(TAG, "describeConfiguredState failed: " + e.getMessage());
        }
        return "本地或在线模型已配置，可直接使用";
    }

    /** 把底层错误映射为可读提示 */
    private String friendlyError(String error) {
        if (error == null || error.trim().isEmpty()) return "未知错误，请重试";
        String e = error.toLowerCase();
        if (e.contains("finalize") || e.contains("rename") || e.contains("校验") || e.contains("sha-256")) {
            return "文件校验/保存失败，已自动重新下载，请稍后重试";
        }
        if (e.contains("connect") || e.contains("timeout") || e.contains("reset")
                || e.contains("refused") || e.contains("unreachable") || e.contains("network")
                || e.contains("网络") || e.contains("socket") || e.contains("dns")) {
            return "网络连接失败，已自动切换备用下载源，请检查网络后重试";
        }
        if (e.contains("空间") || e.contains("no space") || e.contains("enospc")) {
            return "存储空间不足，请清理后重试";
        }
        if (e.contains("cancel") || e.contains("取消")) {
            return "已取消初始化";
        }
        return error;
    }

    // ==================== 动效 ====================

    private void startLogoPulse() {
        if (logoContainer == null) return;
        logoPulse = ObjectAnimator.ofPropertyValuesHolder(
                logoContainer,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.06f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.06f));
        logoPulse.setDuration(1400);
        logoPulse.setRepeatCount(ObjectAnimator.INFINITE);
        logoPulse.setRepeatMode(ObjectAnimator.REVERSE);
        logoPulse.start();
    }

    private void playCompleteBounce() {
        if (logoContainer == null) return;
        if (logoPulse != null) logoPulse.cancel();
        logoContainer.animate()
                .scaleX(1.15f).scaleY(1.15f)
                .setDuration(250)
                .withEndAction(() -> logoContainer.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(450)
                        .start())
                .start();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        monitoring = false;
        monitorHandler.removeCallbacks(monitorRunnable);
        unregisterDetailedObserver();
        if (logoPulse != null) logoPulse.cancel();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        destroyed = true;
        finish();
    }
}
