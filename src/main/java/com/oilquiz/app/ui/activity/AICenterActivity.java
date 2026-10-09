package com.oilquiz.app.ui.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.model.APIConfig;
import com.oilquiz.app.ai.model.ApiModel;
import com.oilquiz.app.ai.model.InferenceType;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.stats.TokenStatsManager;
import com.oilquiz.app.ai.util.APIKeyManager;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.ModelListFetcher;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ui.adapter.ModelAdapter;
import com.oilquiz.app.ui.adapter.OnlineModelAdapter;
import com.oilquiz.app.ui.dialog.OnlineModelConfigDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class AICenterActivity extends AppCompatActivity
        implements ModelAdapter.OnModelClickListener, OnlineModelAdapter.OnOnlineModelClickListener {

    private MaterialCardView cardServiceStatus;
    private TextView tvServiceStatus;

    private MaterialButton btnQuestionGenerator;
    private MaterialButton btnQuestionAnalyzer;
    private MaterialButton btnLearningAssistant;
    private MaterialButton btnTranslator;
    private MaterialButton btnAIChat;
    private MaterialButton btnIconDemo;
    private MaterialButton btnAIImage;
    private MaterialButton btnAIVideo;

    // 在线模型组件
    private SwitchMaterial switchOnlineMode;
    private TextView tvCurrentOnlineModel;
    private TextView tvCurrentOnlineProvider;
    private MaterialButton btnAddApiConfig;
    private MaterialButton btnRefreshModels;
    private RecyclerView onlineModelsRecycler;
    private View onlineModelsEmptyView;
    private OnlineModelAdapter onlineModelAdapter;

    // 本地模型组件
    private SwitchMaterial switchLocalMode;
    private TextView tvCurrentLocalModel;
    private TextView tvLocalModelInfo;
    private MaterialButton btnManageLocalModels;
    private MaterialButton btnDownloadLocalModels;
    private MaterialButton btnRefreshLocalModels;
    private RecyclerView modelsRecycler;
    private View localModelsEmptyView;
    private ModelAdapter modelAdapter;

    // Token统计组件
    private TextView tvInputTokens;
    private TextView tvOutputTokens;
    private TextView tvTotalTokens;
    private TextView tvTokenCost;

    // 服务组件
    private AIService aiService;
    private OnlineModelManager onlineModelManager;
    private InferenceRouter inferenceRouter;
    private ModelListFetcher modelListFetcher;

    private TokenStatsManager.TokenStatsCallback tokenStatsCallback;

    /** 在线模型配置变更监听器（需在 onDestroy 中注销避免内存泄漏） */
    private OnlineModelManager.ModelChangeListener modelChangeListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_center);

        initServices();
        initViews();
        setupClickListeners();
        setupTokenStats();

        // 启动时同步一次 API 配置，确保显示名称与 APIKeyManager 一致
        try {
            onlineModelManager.syncFromAPIKeyManager();
        } catch (Exception e) {
            android.util.Log.w("AICenterActivity", "启动同步在线模型配置失败: " + e.getMessage());
        }

        refreshOnlineModels();
        refreshLocalModels();

        // 注册监听器（保存引用以便 onDestroy 中注销）
        modelChangeListener = new OnlineModelManager.ModelChangeListener() {
            @Override
            public void onModelListChanged() {
                refreshOnlineModels();
            }

            @Override
            public void onActiveModelChanged(String activeModelId) {
                refreshOnlineModels();
                refreshLocalModels();
            }
        };
        onlineModelManager.addListener(modelChangeListener);
    }

    private void initServices() {
        aiService = AIService.getInstance(this);
        onlineModelManager = OnlineModelManager.getInstance(this);
        inferenceRouter = InferenceRouter.getInstance(this);
        modelListFetcher = ModelListFetcher.getInstance(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
        refreshOnlineModels();
        refreshLocalModels();
        updateTokenStatsUI();
        // 进入页面自动检测各 API 配置连通性：不依赖用户手动"测试"，
        // 检测结果写回 APIConfig 状态并刷新状态栏（解决"实际与显示不同步"）
        autoDetectApiStatuses();
    }

    /**
     * 自动检测所有 API 配置的连通性并刷新状态显示。
     * 使用 APIKeyManager.testAPIConnection（内部自动写回 config.setStatus 并持久化），
     * 逐个异步检测，全部完成后刷新状态栏；避免与用户手动测试重复。
     */
    private void autoDetectApiStatuses() {
        try {
            final APIKeyManager manager = APIKeyManager.getInstance(this);
            final List<APIConfig> configs = manager.getAllAPIConfigs();
            if (configs == null || configs.isEmpty()) {
                updateServiceStatus();
                return;
            }
            final java.util.concurrent.atomic.AtomicInteger pending =
                    new java.util.concurrent.atomic.AtomicInteger(configs.size());
            for (APIConfig config : configs) {
                if (config == null) continue;
                manager.testAPIConnection(config).thenAccept(result -> {
                    if (pending.decrementAndGet() <= 0) {
                        runOnUiThread(this::updateServiceStatus);
                    }
                });
            }
        } catch (Exception e) {
            android.util.Log.w("AICenterActivity", "自动检测API状态失败: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        TokenStatsManager.getInstance().unregisterCallback(tokenStatsCallback);
        // 注销监听器，避免 Activity 泄漏
        if (modelChangeListener != null) {
            onlineModelManager.removeListener(modelChangeListener);
            modelChangeListener = null;
        }
    }

    private void setupTokenStats() {
        tokenStatsCallback = stats -> runOnUiThread(() -> {
            if (tvInputTokens != null && stats != null) {
                tvInputTokens.setText(String.valueOf(stats.sessionPromptTokens));
                tvOutputTokens.setText(String.valueOf(stats.sessionCompletionTokens));
                tvTotalTokens.setText(String.valueOf(stats.sessionTotalTokens));

                double cost = stats.sessionPromptTokens * 0.03 / 1000 +
                              stats.sessionCompletionTokens * 0.06 / 1000;
                if (tvTokenCost != null) {
                    tvTokenCost.setText(String.format(getString(R.string.h_12f136f2), cost * 7.2));
                }
            }
        });
        TokenStatsManager.getInstance().registerCallback(tokenStatsCallback);
    }

    private void updateTokenStatsUI() {
        TokenStatsManager.TokenStats stats = TokenStatsManager.getInstance().getCurrentSnapshot();
        if (tvInputTokens != null && stats != null) {
            tvInputTokens.setText(String.valueOf(stats.sessionPromptTokens));
            tvOutputTokens.setText(String.valueOf(stats.sessionCompletionTokens));
            tvTotalTokens.setText(String.valueOf(stats.sessionTotalTokens));
        }
    }

    private void initViews() {
        cardServiceStatus = findViewById(R.id.card_service_status);
        tvServiceStatus = findViewById(R.id.tv_service_status);
        btnQuestionGenerator = findViewById(R.id.btn_question_generator);
        btnQuestionAnalyzer = findViewById(R.id.btn_question_analyzer);
        btnLearningAssistant = findViewById(R.id.btn_learning_assistant);
        btnTranslator = findViewById(R.id.btn_translator);
        btnAIChat = findViewById(R.id.btn_ai_chat);
        btnIconDemo = findViewById(R.id.btn_icon_demo);
        btnAIImage = findViewById(R.id.btn_ai_image);
        btnAIVideo = findViewById(R.id.btn_ai_video);

        // 在线模型组件初始化
        switchOnlineMode = findViewById(R.id.switch_online_mode);
        tvCurrentOnlineModel = findViewById(R.id.tv_current_online_model);
        tvCurrentOnlineProvider = findViewById(R.id.tv_current_online_provider);
        btnAddApiConfig = findViewById(R.id.btn_add_api_config);
        btnRefreshModels = findViewById(R.id.btn_refresh_models);
        onlineModelsRecycler = findViewById(R.id.online_models_recycler);
        onlineModelsEmptyView = findViewById(R.id.online_models_empty);

        // 本地模型组件初始化
        switchLocalMode = findViewById(R.id.switch_local_mode);
        tvCurrentLocalModel = findViewById(R.id.tv_current_local_model);
        tvLocalModelInfo = findViewById(R.id.tv_local_model_info);
        btnManageLocalModels = findViewById(R.id.btn_manage_local_models);
        btnDownloadLocalModels = findViewById(R.id.btn_download_local_models);
        btnRefreshLocalModels = findViewById(R.id.btn_refresh_local_models);
        modelsRecycler = findViewById(R.id.models_recycler);
        localModelsEmptyView = findViewById(R.id.local_models_empty);

        // Token统计组件初始化
        tvInputTokens = findViewById(R.id.tv_input_tokens);
        tvOutputTokens = findViewById(R.id.tv_output_tokens);
        tvTotalTokens = findViewById(R.id.tv_total_tokens);
        tvTokenCost = findViewById(R.id.tv_token_cost);

        // 在线模型列表（复用模型管理页的 OnlineModelAdapter，UI 与操作一致）
        if (onlineModelsRecycler != null) {
            onlineModelAdapter = new OnlineModelAdapter(this, this);
            onlineModelsRecycler.setLayoutManager(new LinearLayoutManager(this));
            onlineModelsRecycler.setAdapter(onlineModelAdapter);
        }

        // 本地模型列表（复用模型管理页的 ModelAdapter，UI 与操作一致）
        if (modelsRecycler != null) {
            modelAdapter = new ModelAdapter(this, new ArrayList<>(), null, this);
            modelsRecycler.setLayoutManager(new LinearLayoutManager(this));
            modelsRecycler.setAdapter(modelAdapter);
        }

        updateServiceStatus();
    }

    private void openMediaGen(String mode) {
        Intent intent = new Intent(AICenterActivity.this, MediaGenActivity.class);
        intent.putExtra("mode", mode);
        startActivity(intent);
    }

    private void setupClickListeners() {
        cardServiceStatus.setOnClickListener(v -> {
            startActivity(new Intent(AICenterActivity.this, ApiConfigActivity.class));
        });

        // AI功能按钮点击事件
        if (btnQuestionGenerator != null) {
            btnQuestionGenerator.setOnClickListener(v -> {
                startActivity(new Intent(AICenterActivity.this, QuestionGenerateActivity.class));
            });
        }

        if (btnQuestionAnalyzer != null) {
            btnQuestionAnalyzer.setOnClickListener(v -> {
                startActivity(new Intent(AICenterActivity.this, QuestionAnalyzeActivity.class));
            });
        }

        if (btnLearningAssistant != null) {
            btnLearningAssistant.setOnClickListener(v -> {
                startActivity(new Intent(AICenterActivity.this, LearningAssistantActivity.class));
            });
        }

        if (btnTranslator != null) {
            btnTranslator.setOnClickListener(v -> {
                startActivity(new Intent(AICenterActivity.this, TranslateActivity.class));
            });
        }

        if (btnAIChat != null) {
            btnAIChat.setOnClickListener(v -> {
                startActivity(new Intent(AICenterActivity.this, AIChatActivity.class));
            });
        }

        if (btnIconDemo != null) {
            btnIconDemo.setOnClickListener(v -> {
                startActivity(new Intent(AICenterActivity.this, AIIconDemoActivity.class));
            });
        }

        if (btnAIImage != null) {
            btnAIImage.setOnClickListener(v -> openMediaGen("image"));
        }
        if (btnAIVideo != null) {
            btnAIVideo.setOnClickListener(v -> openMediaGen("video"));
        }

        // 在线模型按钮点击事件
        if (btnAddApiConfig != null) {
            btnAddApiConfig.setOnClickListener(v -> showAddOnlineModelDialog());
        }
        if (btnRefreshModels != null) {
            btnRefreshModels.setOnClickListener(v -> {
                // 刷新当前在线模型列表（各配置的模型下拉框数据来自缓存）
                refreshOnlineModels();
                Toast.makeText(this, getString(R.string.h_fd08d15f), Toast.LENGTH_SHORT).show();
            });
        }

        // 本地模型按钮点击事件
        if (btnManageLocalModels != null) {
            btnManageLocalModels.setOnClickListener(v -> {
                startActivity(new Intent(AICenterActivity.this, ModelImportActivity.class));
            });
        }

        if (btnDownloadLocalModels != null) {
            btnDownloadLocalModels.setOnClickListener(v -> {
                startActivity(new Intent(AICenterActivity.this, ModelDownloadActivity.class));
            });
        }
        if (btnRefreshLocalModels != null) {
            btnRefreshLocalModels.setOnClickListener(v -> {
                refreshLocalModels();
                Toast.makeText(this, getString(R.string.h_676572ba), Toast.LENGTH_SHORT).show();
            });
        }

        // 模式切换开关
        if (switchOnlineMode != null) {
            switchOnlineMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked && switchLocalMode != null) {
                    switchLocalMode.setChecked(false);
                }
            });
        }

        if (switchLocalMode != null) {
            switchLocalMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked && switchOnlineMode != null) {
                    switchOnlineMode.setChecked(false);
                }
            });
        }
    }

    // ==================== 在线模型 ====================

    private void refreshOnlineModels() {
        if (onlineModelsRecycler == null || onlineModelAdapter == null) {
            return;
        }

        List<OnlineModelManager.OnlineModelConfig> onlineModels = onlineModelManager.getModelList();
        final String activeId = onlineModelManager.getActiveModel() != null
                ? onlineModelManager.getActiveModel().id : null;
        final List<OnlineModelManager.OnlineModelConfig> finalOnlineModels = new ArrayList<>(onlineModels);

        // 安全更新：确保在 UI 线程执行，并避开 RecyclerView 布局冲突
        Runnable updateRunnable = () -> {
            if (onlineModelAdapter == null) return;
            onlineModelAdapter.updateData(finalOnlineModels, activeId);
            // DiffUtil 可能因引用相同而跳过更新，强制刷新确保 UI 同步
            onlineModelAdapter.notifyDataSetChanged();
        };

        if (onlineModelsRecycler.isComputingLayout() || onlineModelsRecycler.isAnimating()) {
            onlineModelsRecycler.post(updateRunnable);
        } else {
            updateRunnable.run();
        }

        if (onlineModelsEmptyView != null) {
            onlineModelsEmptyView.setVisibility(onlineModels.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (onlineModelsRecycler != null) {
            onlineModelsRecycler.setVisibility(onlineModels.isEmpty() ? View.GONE : View.VISIBLE);
        }

        // 顶部"当前模型"卡片：显示真实当前使用的在线模型
        if (tvCurrentOnlineModel != null) {
            if (inferenceRouter.isUsingOnlineModel()) {
                String modelName = inferenceRouter.getCurrentModelName();
                tvCurrentOnlineModel.setText(modelName != null ? modelName : getString(R.string.h_be4bb9e0));
                tvCurrentOnlineProvider.setText(getString(R.string.h_7fc75577));
            } else {
                OnlineModelManager.OnlineModelConfig active = onlineModelManager.getActiveModel();
                if (active != null) {
                    tvCurrentOnlineModel.setText(active.selectedModel != null ? active.selectedModel : active.modelName);
                    tvCurrentOnlineProvider.setText(active.name);
                } else {
                    tvCurrentOnlineModel.setText(getString(R.string.h_be4bb9e0));
                    tvCurrentOnlineProvider.setText(getString(R.string.h_fdb64631));
                }
            }
        }
    }

    // ==================== 本地模型 ====================

    private void refreshLocalModels() {
        String currentModel = aiService.getCurrentModelName();

        if (tvCurrentLocalModel != null) {
            if (currentModel != null && !currentModel.isEmpty()) {
                tvCurrentLocalModel.setText(currentModel);
                tvLocalModelInfo.setText(getString(R.string.h_dd7aa8f7));
            } else {
                tvCurrentLocalModel.setText(getString(R.string.h_7bb4fd0e));
                tvLocalModelInfo.setText(getString(R.string.h_2ef7cb00));
            }
        }

        if (modelsRecycler == null || modelAdapter == null) {
            return;
        }

        String[] availableModels = aiService.getAvailableModels();
        final List<String> modelList = new ArrayList<>();
        if (availableModels != null) {
            for (String model : availableModels) {
                modelList.add(model);
            }
        }

        // 本地模型列表的"当前使用"高亮：仅当实际使用本地模型时才标记，
        // 避免在线模型激活时本地列表也显示"当前使用"（两个都高亮的歧义）。
        String localCurrentModel = null;
        if (inferenceRouter != null && !inferenceRouter.isUsingOnlineModel()) {
            localCurrentModel = currentModel;
        }
        final String finalLocalCurrentModel = localCurrentModel;

        Runnable updateRunnable = () -> {
            if (modelAdapter == null) return;
            modelAdapter.updateData(modelList, finalLocalCurrentModel);
            modelAdapter.notifyDataSetChanged();
        };
        if (modelsRecycler.isComputingLayout() || modelsRecycler.isAnimating()) {
            modelsRecycler.post(updateRunnable);
        } else {
            updateRunnable.run();
        }

        if (localModelsEmptyView != null) {
            localModelsEmptyView.setVisibility(modelList.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (modelsRecycler != null) {
            modelsRecycler.setVisibility(modelList.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    // ==================== 在线模型回调（与模型管理页一致） ====================

    private OnlineModelManager.OnlineModelConfig findOnlineModelByName(String modelName) {
        for (OnlineModelManager.OnlineModelConfig config : onlineModelManager.getModelList()) {
            if (config.name.equals(modelName)) {
                return config;
            }
        }
        return null;
    }

    @Override
    public void onModelClick(String modelName) {
        OnlineModelManager.OnlineModelConfig onlineConfig = findOnlineModelByName(modelName);
        if (onlineConfig != null) {
            // 在线模型：直接切换并即时刷新 UI
            inferenceRouter.switchModel(onlineConfig.id);
            Toast.makeText(this, getString(R.string.h_9569dd13) + modelName, Toast.LENGTH_SHORT).show();
            refreshOnlineModels();
        } else {
            Toast.makeText(this, getString(R.string.h_154f0dc4) + modelName, Toast.LENGTH_SHORT).show();
            onlineModelManager.stopActiveModel();
            aiService.hotSwitchModel(modelName, new AIService.HotSwitchCallback() {
                @Override
                public void onSwitchStarted(String fromModel, String toModel) {
                    runOnUiThread(() -> {
                        Toast.makeText(AICenterActivity.this,
                            "开始切换: " + (fromModel != null ? fromModel : "无") + " → " + toModel,
                            Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onSwitchProgress(int progress, String message) {
                }

                @Override
                public void onSwitchCompleted(boolean success, String model) {
                    runOnUiThread(() -> {
                        if (success) {
                            Toast.makeText(AICenterActivity.this,
                                getString(R.string.h_24d9c18f) + model, Toast.LENGTH_SHORT).show();
                            refreshLocalModels();
                        } else {
                            Toast.makeText(AICenterActivity.this,
                                getString(R.string.h_b9c8e7b7), Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                @Override
                public void onSwitchFailed(String reason) {
                    runOnUiThread(() -> {
                        Toast.makeText(AICenterActivity.this,
                            getString(R.string.h_70a7d4d9) + reason, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        }
    }

    @Override
    public void onDeleteClick(String modelName) {
        OnlineModelManager.OnlineModelConfig config = findOnlineModelByName(modelName);
        if (config == null) return;

        final String configId = config.id;
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.h_50eaf94d))
            .setMessage(getString(R.string.h_bf14db74) + config.name + getString(R.string.h_2957e496))
            .setPositiveButton(getString(R.string.h_2f4aaddd), (dialog, which) -> {
                try {
                    APIKeyManager manager = APIKeyManager.getInstance(this);
                    if (manager.getAPIConfigById(configId) != null) {
                        manager.deleteAPIConfig(configId);
                    }
                } catch (Exception ignored) {
                }
                onlineModelManager.removeModel(configId);
                Toast.makeText(this, getString(R.string.h_6c0d7a63), Toast.LENGTH_SHORT).show();
                refreshOnlineModels();
            })
            .setNegativeButton(getString(R.string.h_625fb26b), null)
            .show();
    }

    @Override
    public void onEnableToggle(String modelName, boolean enabled) {
        for (OnlineModelManager.OnlineModelConfig config : onlineModelManager.getModelList()) {
            if (config.name.equals(modelName)) {
                config.enabled = enabled;
                onlineModelManager.save();
                try {
                    APIKeyManager manager = APIKeyManager.getInstance(this);
                    APIConfig apiConfig = manager.getAPIConfigById(config.id);
                    if (apiConfig != null && apiConfig.isActive() != enabled) {
                        apiConfig.setActive(enabled);
                        manager.saveAPIConfig(apiConfig);
                    }
                } catch (Exception ignored) {
                }
                // 如果禁用了当前活跃的在线模型，停止使用它
                if (!enabled) {
                    OnlineModelManager.OnlineModelConfig active = onlineModelManager.getActiveModel();
                    if (active != null && active.id.equals(config.id)) {
                        onlineModelManager.stopActiveModel();
                    }
                }
                refreshOnlineModels();
                Toast.makeText(this, enabled ? getString(R.string.h_53ace430) : getString(R.string.h_1c1ed981), Toast.LENGTH_SHORT).show();
                break;
            }
        }
    }

    @Override
    public void onAddClick() {
        showAddOnlineModelDialog();
    }

    @Override
    public void onEditClick(String modelName) {
        OnlineModelManager.OnlineModelConfig config = findOnlineModelByName(modelName);
        if (config == null) {
            Toast.makeText(this, getString(R.string.h_aed6aa70), Toast.LENGTH_SHORT).show();
            return;
        }
        showEditOnlineModelDialog(config);
    }

    private void showAddOnlineModelDialog() {
        OnlineModelConfigDialog dialog = new OnlineModelConfigDialog(this);
        dialog.setSaveListener(new OnlineModelConfigDialog.OnConfigSaveListener() {
            @Override
            public void onConfigSaved(OnlineModelManager.OnlineModelConfig config) {
                if (config != null) {
                    // 通过 InferenceRouter 切换，确保路由状态同步
                    inferenceRouter.switchModel(config.id);
                    Toast.makeText(AICenterActivity.this,
                        getString(R.string.h_8a7f6c2f) + config.name, Toast.LENGTH_SHORT).show();
                    refreshOnlineModels();
                }
            }

            @Override
            public void onConfigCancelled() {
            }
        });
        dialog.show();
    }

    private void showEditOnlineModelDialog(OnlineModelManager.OnlineModelConfig config) {
        OnlineModelConfigDialog dialog = new OnlineModelConfigDialog(this);
        dialog.setSaveListener(new OnlineModelConfigDialog.OnConfigSaveListener() {
            @Override
            public void onConfigSaved(OnlineModelManager.OnlineModelConfig updatedConfig) {
                Toast.makeText(AICenterActivity.this,
                    getString(R.string.h_454706a6) + updatedConfig.name, Toast.LENGTH_SHORT).show();
                refreshOnlineModels();
            }

            @Override
            public void onConfigCancelled() {
            }
        });
        dialog.show(config);
    }

    @Override
    public void onFetchModelsClick(String modelName) {
        OnlineModelManager.OnlineModelConfig config = findOnlineModelByName(modelName);
        if (config == null) {
            Toast.makeText(this, getString(R.string.h_aed6aa70), Toast.LENGTH_SHORT).show();
            return;
        }

        if (config.apiKey == null || config.apiKey.isEmpty()) {
            Toast.makeText(this, getString(R.string.h_1571dfd4), Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, getString(R.string.h_916702da), Toast.LENGTH_SHORT).show();

        final String targetConfigId = config.id;
        modelListFetcher.fetchModels(config.apiUrl, config.apiKey)
            .thenAccept(models -> runOnUiThread(() -> {
                if (models != null && !models.isEmpty()) {
                    try {
                        JSONArray modelsJson = new JSONArray();
                        for (ApiModel model : models) {
                            modelsJson.put(
                                com.oilquiz.app.ai.service.ModelListFetcher.modelToCacheJson(model));
                        }
                        onlineModelManager.saveCachedModels(targetConfigId, modelsJson.toString());
                        Toast.makeText(this, getString(R.string.h_96daed33) + models.size() + getString(R.string.h_44850b04), Toast.LENGTH_SHORT).show();
                        refreshOnlineModels();
                    } catch (Exception e) {
                        Toast.makeText(this, getString(R.string.h_157fa13a) + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(this, getString(R.string.h_d1ef35f7), Toast.LENGTH_SHORT).show();
                }
            }))
            .exceptionally(e -> {
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.h_bdf02ef7) + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
                return null;
            });
    }

    @Override
    public void onModelSelected(String modelName, String selectedModel) {
        OnlineModelManager.OnlineModelConfig config = findOnlineModelByName(modelName);
        if (config == null) {
            Toast.makeText(this, getString(R.string.h_aed6aa70), Toast.LENGTH_SHORT).show();
            return;
        }

        onlineModelManager.saveSelectedModel(config.id, selectedModel);
        // 切换模型后异步补查一次该模型的真实上下文窗口（带持久化缓存，命中则零请求），
        // 回写配置供 Agent 压缩阈值与 UI 展示使用
        final OnlineModelManager.OnlineModelConfig cfg = config;
        try {
            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return com.oilquiz.app.ai.service.OnlineInferenceService
                            .getInstance(this).queryContextWindowFromAPI(cfg);
                } catch (Throwable t) {
                    return null;
                }
            }).thenAccept(window -> {
                if (window != null && window > 0) {
                    cfg.contextWindow = window;
                    cfg.contextWindowFromApi = true;
                    onlineModelManager.updateModelConfig(cfg);
                }
            });
        } catch (Throwable ignored) {
            // 查询失败静默，保留配置表推断值
        }
        // 自动切换到该在线模型
        inferenceRouter.switchModel(config.id);
        Toast.makeText(this, getString(R.string.h_f70a7dc7) + selectedModel, Toast.LENGTH_SHORT).show();
        refreshOnlineModels();
    }

    // ==================== 服务状态 ====================

    private void updateServiceStatus() {
        if (tvServiceStatus == null) {
            return;
        }

        APIKeyManager manager = APIKeyManager.getInstance(this);
        List<APIConfig> configs = manager.getAllAPIConfigs();

        int validCount = 0;
        int totalCount = configs.size();

        for (APIConfig config : configs) {
            if (APIConfig.STATUS_VALID.equals(config.getStatus())) {
                validCount++;
            }
        }

        if (totalCount == 0) {
            tvServiceStatus.setText(getString(R.string.h_c5e73d8b));
        } else if (validCount == totalCount) {
            tvServiceStatus.setText(getString(R.string.h_0d296461) + totalCount + getString(R.string.h_329c5719));
        } else if (validCount > 0) {
            tvServiceStatus.setText(validCount + "/" + totalCount + getString(R.string.h_46b389d6));
        } else {
            tvServiceStatus.setText(getString(R.string.h_0d296461) + totalCount + getString(R.string.h_07f016ca));
        }
    }
}
