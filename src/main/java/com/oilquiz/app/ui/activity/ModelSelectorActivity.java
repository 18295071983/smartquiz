package com.oilquiz.app.ui.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.model.APIConfig;
import com.oilquiz.app.ai.model.ApiModel;
import com.oilquiz.app.ai.model.InferenceType;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AIServiceInitializer;
import com.oilquiz.app.ai.service.ModelListFetcher;
import com.oilquiz.app.ai.util.APIKeyManager;
import com.oilquiz.app.ui.adapter.ModelAdapter;
import com.oilquiz.app.ui.adapter.OnlineModelAdapter;
import com.oilquiz.app.ui.dialog.OnlineModelConfigDialog;

import java.util.ArrayList;
import java.util.List;

public class ModelSelectorActivity extends AppCompatActivity 
        implements ModelAdapter.OnModelClickListener, OnlineModelAdapter.OnOnlineModelClickListener {

    private static final String TAG = "ModelSelectorActivity";
    private static final int REQUEST_IMPORT_MODEL = 1002;

    private AIService aiService;
    private OnlineModelManager onlineModelManager;
    private InferenceRouter inferenceRouter;
    private ModelListFetcher modelListFetcher;
    private RecyclerView modelsRecycler;
    private RecyclerView onlineModelsRecycler;
    private ModelAdapter modelAdapter;
    private OnlineModelAdapter onlineModelAdapter;
    private TextView currentModelNameTextView;
    private TextView currentModelTypeTextView;
    private MaterialButton refreshButton;
    private MaterialButton addOnlineModelButton;
    private MaterialButton importLocalModelButton;
    private MaterialButton btnApiConfig;
    private LinearLayout onlineModelsSection;
    private View onlineModelsEmptyView;
    private View localModelsEmptyView;
    private View serviceStatusBar;
    private View statusIndicator;
    private TextView tvServiceStatus;
    private TextView tvUsageInfo;
    private TextView tvAsrModelValue;  // 语音识别模型显示
    private TextView tvTtsModelValue;  // 语音合成模型显示
    private TextView tvTtsVoiceValue;  // TTS 音色显示
    private LinearLayout rowFeatureModelsHeader;  // 功能专用模型标题行
    private View aiInitCard;                      // AI 服务一键初始化卡片
    private MaterialButton btnAiInit;             // 一键初始化按钮

    /** 在线模型配置变更监听器（需在 onDestroy 中注销避免内存泄漏） */
    private OnlineModelManager.ModelChangeListener modelChangeListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_selector);

        try {
            aiService = AIService.getInstance(this);
            onlineModelManager = OnlineModelManager.getInstance(this);
            inferenceRouter = InferenceRouter.getInstance(this);
            modelListFetcher = ModelListFetcher.getInstance(this);

            currentModelNameTextView = findViewById(R.id.current_model_name);
            currentModelTypeTextView = findViewById(R.id.current_model_type);
            modelsRecycler = findViewById(R.id.models_recycler);
            onlineModelsRecycler = findViewById(R.id.online_models_recycler);
            refreshButton = findViewById(R.id.refresh_button);
            addOnlineModelButton = findViewById(R.id.add_online_model_button);
            importLocalModelButton = findViewById(R.id.import_local_model_button);
            btnApiConfig = findViewById(R.id.btn_api_config);
            aiInitCard = findViewById(R.id.ai_init_card);
            btnAiInit = findViewById(R.id.btn_ai_init);
            onlineModelsSection = findViewById(R.id.online_models_section);
            onlineModelsEmptyView = findViewById(R.id.online_models_empty);
            localModelsEmptyView = findViewById(R.id.local_models_empty);

            // 初始化服务状态栏
            serviceStatusBar = findViewById(R.id.service_status_bar);
            statusIndicator = findViewById(R.id.status_indicator);
            tvServiceStatus = findViewById(R.id.tv_service_status);
            tvUsageInfo = findViewById(R.id.tv_usage_info);
            updateServiceStatus();

            View toolbar = findViewById(R.id.toolbar);
            if (toolbar != null) {
                toolbar.setOnClickListener(v -> finish());
            }

            // 功能专用模型标题行：点击弹出语音功能介绍
            rowFeatureModelsHeader = findViewById(R.id.row_feature_models_header);
            if (rowFeatureModelsHeader != null) {
                rowFeatureModelsHeader.setOnClickListener(v -> showVoiceFeatureInfoDialog());
            }

            if (refreshButton != null) {
                refreshButton.setOnClickListener(v -> {
                    // 强制从数据源重新同步，再刷新 UI
                    forceRefreshModels();
                });
            }
            if (addOnlineModelButton != null) {
                addOnlineModelButton.setOnClickListener(v -> showAddOnlineModelDialog());
            }
            if (btnApiConfig != null) {
                btnApiConfig.setOnClickListener(v -> {
                    startActivity(new Intent(ModelSelectorActivity.this, ApiConfigActivity.class));
                });
            }

            // AI 服务一键初始化入口：仅当本地与在线模型均未配置时显示
            if (aiInitCard != null && btnAiInit != null) {
                updateAiInitCardVisibility();
                btnAiInit.setOnClickListener(v -> {
                    if (!AIServiceInitializer.needsInitialization(this)) {
                        updateAiInitCardVisibility();
                        return;
                    }
                    startActivity(new Intent(ModelSelectorActivity.this, AIServiceInitActivity.class));
                });
            }

            // 功能专用模型：语音识别 / 语音合成（点击弹出模型选择器）
            tvAsrModelValue = findViewById(R.id.tv_asr_model_value);
            tvTtsModelValue = findViewById(R.id.tv_tts_model_value);
            tvTtsVoiceValue = findViewById(R.id.tv_tts_voice_value);
            View rowAsrModel = findViewById(R.id.row_asr_model);
            View rowTtsModel = findViewById(R.id.row_tts_model);
            View rowTtsVoice = findViewById(R.id.row_tts_voice);
            if (rowAsrModel != null) {
                rowAsrModel.setOnClickListener(v -> showSpeechModelSelector(
                        com.oilquiz.app.ui.dialog.SpeechModelSelectorDialog.Mode.ASR));
            }
            if (rowTtsModel != null) {
                rowTtsModel.setOnClickListener(v -> showSpeechModelSelector(
                        com.oilquiz.app.ui.dialog.SpeechModelSelectorDialog.Mode.TTS));
            }
            if (rowTtsVoice != null) {
                rowTtsVoice.setOnClickListener(v ->
                        new com.oilquiz.app.ui.dialog.TTSVoiceSelectorDialog(this).show());
            }
            updateFeatureModelsDisplay();

            // ===== NPU（Qualcomm GenieX）推理引擎开关 =====
            // 开启后对话页的问答直接走 Hexagon NPU（本地侧载 GGUF，无需联网）；关闭则回到 llama.cpp。
            final TextView tvNpuEngineValue = findViewById(R.id.tv_npu_engine_value);
            View rowNpuEngine = findViewById(R.id.row_npu_engine);
            refreshNpuEngineRow(tvNpuEngineValue);
            if (rowNpuEngine != null) {
                rowNpuEngine.setOnClickListener(v -> showNpuEngineDialog(tvNpuEngineValue));
            }

            if (importLocalModelButton != null) {
                importLocalModelButton.setOnClickListener(v -> importModel());
            }

            // 初始化在线模型列表
            setupOnlineModelsRecycler();

            // 启动时同步一次 API 配置，确保显示名称与 APIKeyManager 一致
            try {
                onlineModelManager.syncFromAPIKeyManager();
            } catch (Exception e) {
                android.util.Log.w(TAG, "启动同步在线模型配置失败: " + e.getMessage());
            }

            refreshModels();
            
            // 注册监听器（保存引用以便 onDestroy 中注销）
            modelChangeListener = new OnlineModelManager.ModelChangeListener() {
                @Override
                public void onModelListChanged() {
                    refreshOnlineModels();
                }

                @Override
                public void onActiveModelChanged(String activeModelId) {
                    updateCurrentModelDisplay();
                }
            };
            onlineModelManager.addListener(modelChangeListener);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, getString(R.string.h_58c10e4c) + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setupOnlineModelsRecycler() {
        if (onlineModelsRecycler != null) {
            onlineModelAdapter = new OnlineModelAdapter(this, new ArrayList<>(), 
                onlineModelManager.getActiveModel() != null ? onlineModelManager.getActiveModel().id : null, this);
            onlineModelsRecycler.setAdapter(onlineModelAdapter);
        }
    }

    private void refreshModels() {
        try {
            // 刷新本地模型
            String[] availableModels = aiService.getAvailableModels();
            final List<String> modelList = new ArrayList<>();
            if (availableModels != null) {
                for (String model : availableModels) {
                    modelList.add(model);
                }
            }

            final String currentModel = aiService.getCurrentModelName();

            // 更新顶部“当前模型”卡片：优先显示在线模型名称
            String activeDisplayName = null;
            if (inferenceRouter != null && inferenceRouter.isUsingOnlineModel()) {
                activeDisplayName = inferenceRouter.getCurrentModelName();
            } else if (currentModel != null) {
                activeDisplayName = currentModel;
            }
            if (currentModelNameTextView != null) {
                currentModelNameTextView.setText(activeDisplayName != null ? activeDisplayName : getString(R.string.h_be4bb9e0));
            }

            // 本地模型列表的"当前使用"高亮：仅当实际使用本地模型时才标记，
            // 避免在线模型激活时本地列表也显示"当前使用"（两个都高亮的歧义）。
            // aiService.getCurrentModelName() 可能残留上次加载的本地模型名，
            // 即使路由已切到在线——必须用路由实际类型判断。
            String localCurrentModel = null;
            if (inferenceRouter != null && !inferenceRouter.isUsingOnlineModel()) {
                localCurrentModel = currentModel;
            }

            // 更新本地模型列表
            if (modelsRecycler != null) {
                if (modelAdapter == null) {
                    modelAdapter = new ModelAdapter(this, modelList, localCurrentModel, this);
                    modelsRecycler.setAdapter(modelAdapter);
                } else {
                    updateLocalModelAdapterSafe(modelList, localCurrentModel);
                }
                modelsRecycler.setVisibility(modelList.isEmpty() ? View.GONE : View.VISIBLE);
            }
            if (localModelsEmptyView != null) {
                localModelsEmptyView.setVisibility(modelList.isEmpty() ? View.VISIBLE : View.GONE);
            }

            // 刷新在线模型列表
            refreshOnlineModels();

            // 确保当前模型显示是最新的
            updateCurrentModelDisplay();

            if (modelList.isEmpty() && !onlineModelManager.hasModels()) {
                Toast.makeText(this, getString(R.string.h_04ab5378), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, getString(R.string.h_a4d0e139) + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 更新 AI 服务一键初始化卡片显隐（本地与在线均未配置时显示）
     */
    private void updateAiInitCardVisibility() {
        if (aiInitCard == null) return;
        boolean show = AIServiceInitializer.needsInitialization(this);
        aiInitCard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (btnAiInit != null) {
            btnAiInit.setEnabled(true);
            btnAiInit.setText(getString(R.string.h_4d24d2a1));
        }
    }

    /**
     * 强制从数据源重新同步模型列表，然后刷新 UI。
     * 用于刷新按钮，确保从 APIKeyManager / SharedPreferences 重新加载最新数据。
     */
    private void forceRefreshModels() {
        try {
            // 从 APIKeyManager 重新同步在线模型配置
            int synced = onlineModelManager.importFromAPIKeyManager();
            if (synced > 0) {
                AppLogger.i("ModelSelector", "强制同步了 " + synced + " 个在线模型配置");
            }
        } catch (Exception e) {
            AppLogger.w("ModelSelector", "强制同步失败: " + e.getMessage());
        }
        // 刷新所有 UI
        refreshModels();
        Toast.makeText(this, getString(R.string.h_fd08d15f), Toast.LENGTH_SHORT).show();
    }

    /**
     * 安全地更新本地模型adapter，避免RecyclerView正在布局或动画时更新导致崩溃
     */
    private void updateLocalModelAdapterSafe(final List<String> modelList, final String currentModel) {
        if (modelsRecycler == null || modelAdapter == null) {
            return;
        }
        if (modelsRecycler.isComputingLayout() || modelsRecycler.isAnimating()) {
            // RecyclerView正在计算布局或动画中，延迟到下一帧再更新
            modelsRecycler.post(() -> {
                if (modelAdapter != null) {
                    modelAdapter.updateData(modelList, currentModel);
                }
            });
        } else {
            modelAdapter.updateData(modelList, currentModel);
        }
    }

    private void refreshOnlineModels() {
        if (onlineModelsSection == null || onlineModelAdapter == null) {
            return;
        }

        // 标题区始终可见，仅切换列表与空提示
        onlineModelsSection.setVisibility(View.VISIBLE);

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

        if (onlineModelsRecycler != null && (onlineModelsRecycler.isComputingLayout() || onlineModelsRecycler.isAnimating())) {
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

        // 同步更新顶部“当前模型”卡片
        updateCurrentModelDisplay();
    }

    private void updateCurrentModelDisplay() {
        if (currentModelTypeTextView == null) {
            return;
        }
        
        InferenceType type = inferenceRouter.getCurrentInferenceType();
        String modelName = inferenceRouter.getCurrentModelName();
        
        if (modelName != null) {
            currentModelNameTextView.setText(modelName);
            currentModelTypeTextView.setText(getString(R.string.h_d46380d7) + type.getDisplayName());
        } else {
            currentModelNameTextView.setText(getString(R.string.h_be4bb9e0));
            currentModelTypeTextView.setText(getString(R.string.h_b555f97c));
        }
    }

    private void importModel() {
        Intent intent = new Intent(this, ModelImportActivity.class);
        startActivityForResult(intent, REQUEST_IMPORT_MODEL);
    }

    private void showAddOnlineModelDialog() {
        OnlineModelConfigDialog dialog = new OnlineModelConfigDialog(this);
        dialog.setSaveListener(new OnlineModelConfigDialog.OnConfigSaveListener() {
            @Override
            public void onConfigSaved(OnlineModelManager.OnlineModelConfig config) {
                if (config != null) {
                    // 通过 InferenceRouter 切换，确保路由状态同步
                    inferenceRouter.switchModel(config.id);
                    Toast.makeText(ModelSelectorActivity.this, getString(R.string.h_8a7f6c2f) + config.name, Toast.LENGTH_SHORT).show();
                    refreshModels();
                }
            }

            @Override
            public void onConfigCancelled() {
                // 用户取消，无需处理
            }
        });
        dialog.show();
    }

    @Override
    public void onModelClick(String modelName) {
        OnlineModelManager.OnlineModelConfig onlineConfig = findOnlineModelByName(modelName);
        if (onlineConfig != null) {
            // 在线模型：直接切换并即时刷新 UI
            inferenceRouter.switchModel(onlineConfig.id);
            Toast.makeText(this, getString(R.string.h_9569dd13) + modelName, Toast.LENGTH_SHORT).show();
            // 即时刷新所有显示
            refreshOnlineModels();
            updateCurrentModelDisplay();
            // 延迟刷新本地模型区域（可能不需要）
            safeDelayedRefreshModels(200);
        } else {
            Toast.makeText(this, getString(R.string.h_154f0dc4) + modelName, Toast.LENGTH_SHORT).show();
            onlineModelManager.stopActiveModel();

            // 使用热切换，带进度回调
            aiService.hotSwitchModel(modelName, new AIService.HotSwitchCallback() {
                @Override
                public void onSwitchStarted(String fromModel, String toModel) {
                    runOnUiThread(() -> {
                        Toast.makeText(ModelSelectorActivity.this,
                            "开始切换: " + (fromModel != null ? fromModel : "无") + " → " + toModel,
                            Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onSwitchProgress(int progress, String message) {
                    runOnUiThread(() -> {
                        // 可以在这里更新进度条
                    });
                }

                @Override
                public void onSwitchCompleted(boolean success, String model) {
                    runOnUiThread(() -> {
                        if (success) {
                            Toast.makeText(ModelSelectorActivity.this,
                                getString(R.string.h_24d9c18f) + model, Toast.LENGTH_SHORT).show();
                            refreshModels();
                        } else {
                            Toast.makeText(ModelSelectorActivity.this,
                                getString(R.string.h_b9c8e7b7), Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                @Override
                public void onSwitchFailed(String reason) {
                    runOnUiThread(() -> {
                        Toast.makeText(ModelSelectorActivity.this,
                            getString(R.string.h_70a7d4d9) + reason, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        }
    }

    private OnlineModelManager.OnlineModelConfig findOnlineModelByName(String modelName) {
        for (OnlineModelManager.OnlineModelConfig config : onlineModelManager.getModelList()) {
            if (config.name.equals(modelName)) {
                return config;
            }
        }
        return null;
    }

    @Override
    public void onDeleteClick(String modelName) {
        // 查找对应的在线模型配置
        OnlineModelManager.OnlineModelConfig config = null;
        for (OnlineModelManager.OnlineModelConfig c : onlineModelManager.getModelList()) {
            if (c.name.equals(modelName)) {
                config = c;
                break;
            }
        }
        if (config == null) return;

        final String configId = config.id; // 提取为 final 变量以用于 lambda
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.h_50eaf94d))
            .setMessage(getString(R.string.h_bf14db74) + config.name + getString(R.string.h_2957e496))
            .setPositiveButton(getString(R.string.h_2f4aaddd), (dialog, which) -> {
                // 先同步删除 APIKeyManager 中对应的配置（如果存在）
                try {
                    APIKeyManager manager = APIKeyManager.getInstance(this);
                    if (manager.getAPIConfigById(configId) != null) {
                        manager.deleteAPIConfig(configId);
                    }
                } catch (Exception ignored) {
                }
                onlineModelManager.removeModel(configId);
                Toast.makeText(this, getString(R.string.h_6c0d7a63), Toast.LENGTH_SHORT).show();
                refreshModels();
            })
            .setNegativeButton(getString(R.string.h_625fb26b), null)
            .show();
    }

    @Override
    public void onEnableToggle(String modelName, boolean enabled) {
        // 查找对应的在线模型配置
        for (OnlineModelManager.OnlineModelConfig config : onlineModelManager.getModelList()) {
            if (config.name.equals(modelName)) {
                config.enabled = enabled;
                onlineModelManager.save();
                // 同步启用/禁用状态到 APIKeyManager
                try {
                    APIKeyManager manager = APIKeyManager.getInstance(this);
                    com.oilquiz.app.ai.model.APIConfig apiConfig = manager.getAPIConfigById(config.id);
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
                updateCurrentModelDisplay();
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

    private void showEditOnlineModelDialog(OnlineModelManager.OnlineModelConfig config) {
        OnlineModelConfigDialog dialog = new OnlineModelConfigDialog(this);
        dialog.setSaveListener(new OnlineModelConfigDialog.OnConfigSaveListener() {
            @Override
            public void onConfigSaved(OnlineModelManager.OnlineModelConfig updatedConfig) {
                Toast.makeText(ModelSelectorActivity.this, getString(R.string.h_454706a6) + updatedConfig.name, Toast.LENGTH_SHORT).show();
                refreshModels();
            }

            @Override
            public void onConfigCancelled() {
                // 用户取消
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
                            // 共用序列化（含 thinkingEffortLevels / maxOutputTokens），
                            // 避免各入口各写一份、漏字段导致档位丢失
                            modelsJson.put(
                                com.oilquiz.app.ai.service.ModelListFetcher.modelToCacheJson(model));
                        }
                        onlineModelManager.saveCachedModels(targetConfigId, modelsJson.toString());
                        Toast.makeText(this, getString(R.string.h_96daed33) + models.size() + getString(R.string.h_44850b04), Toast.LENGTH_SHORT).show();
                        // 延迟 300ms 刷新，避免与 RecyclerView 布局/动画状态冲突
                        safeDelayedRefreshOnlineModels(300);
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

    /**
     * 延迟刷新在线模型列表，避开 RecyclerView 正在布局/计算/动画的时间窗口
     */
    private void safeDelayedRefreshOnlineModels(long delayMs) {
        if (onlineModelsRecycler == null) {
            refreshOnlineModels();
            return;
        }
        onlineModelsRecycler.postDelayed(this::refreshOnlineModels, delayMs);
    }

    /**
     * 延迟刷新所有模型列表
     */
    private void safeDelayedRefreshModels(long delayMs) {
        if (modelsRecycler == null && onlineModelsRecycler == null) {
            refreshModels();
            return;
        }
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(this::refreshModels, delayMs);
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
        updateCurrentModelDisplay();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_MODEL) {
            refreshModels();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshModels();
        updateServiceStatus();
        updateFeatureModelsDisplay();
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
            // 进入页面时对每个配置重新检测一次，保证状态与实际同步；
            // testAPIConnection 内部会写回 config.setStatus 并持久化
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
            android.util.Log.w(TAG, "自动检测API状态失败: " + e.getMessage());
        }
    }

    /** 弹出语音模型选择器（ASR/TTS） */
    private void showSpeechModelSelector(com.oilquiz.app.ui.dialog.SpeechModelSelectorDialog.Mode mode) {
        new com.oilquiz.app.ui.dialog.SpeechModelSelectorDialog(this, mode)
                .setListener((modelId, modelName) -> updateFeatureModelsDisplay())
                .show();
    }

    // ==================== NPU（Qualcomm GenieX）推理引擎 ====================

    /** 刷新 NPU 引擎行的显示（未启用 / 已启用（模型名）/ 加载中） */
    private void refreshNpuEngineRow(TextView tv) {
        if (tv == null) return;
        boolean enabled = inferenceRouter != null && inferenceRouter.isNpuEngineEnabled();
        if (!enabled) {
            tv.setText("未启用（点此开启）");
            return;
        }
        String state = com.oilquiz.app.ai.engine.NpuLlmChat.getStateName();
        String model = com.oilquiz.app.ai.engine.NpuLlmChat.getCurrentModel();
        float tps = com.oilquiz.app.ai.engine.NpuLlmChat.getLastTps();
        StringBuilder sb = new StringBuilder("已启用");
        if (model != null && !model.isEmpty()) {
            sb.append(" · ").append(model);
        } else {
            String pref = com.oilquiz.app.ai.engine.NpuLlmChat.preferredModelName();
            sb.append(" · ").append(pref == null || pref.isEmpty() ? "自动选模型" : "指定：" + pref);
        }
        if (!"READY".equals(state)) sb.append(" · ").append(state);
        if (tps > 0) sb.append(String.format(java.util.Locale.US, " · %.1f t/s", tps));
        tv.setText(sb.toString());
    }

    /** NPU 引擎操作菜单：开启 / 关闭 / 选择模型 / 重新加载 / 去下载页 */
    private void showNpuEngineDialog(TextView tvValue) {
        boolean enabled = inferenceRouter != null && inferenceRouter.isNpuEngineEnabled();
        final String[] items = enabled
                ? new String[]{"关闭 NPU 引擎（回退本地 llama.cpp）", "选择 NPU 模型…",
                               "重新加载 NPU 模型", "打开「模型下载」页（下载 Q4_0 模型）"}
                : new String[]{"开启 NPU 引擎（Hexagon NPU，模型走 App 模型库）", "选择 NPU 模型…",
                               "打开「模型下载」页（下载 Q4_0 模型）"};

        new AlertDialog.Builder(this)
                .setTitle("NPU 引擎（Qualcomm GenieX）")
                .setItems(items, (d, which) -> {
                    if (enabled) {
                        if (which == 0) {
                            inferenceRouter.disableNpuEngine();
                            Toast.makeText(this, "已关闭 NPU 引擎，回退本地 llama.cpp", Toast.LENGTH_SHORT).show();
                            refreshNpuEngineRow(tvValue);
                        } else if (which == 1) {
                            showNpuModelDialog(tvValue);
                        } else if (which == 2) {
                            reloadNpuModel(tvValue);
                        } else {
                            startActivity(new android.content.Intent(this,
                                    com.oilquiz.app.ui.activity.ModelDownloadActivity.class));
                        }
                    } else {
                        if (which == 0) {
                            enableNpuEngineNow(tvValue);
                        } else if (which == 1) {
                            showNpuModelDialog(tvValue);
                        } else {
                            startActivity(new android.content.Intent(this,
                                    com.oilquiz.app.ui.activity.ModelDownloadActivity.class));
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 开启 NPU 引擎（首次会加载模型） */
    private void enableNpuEngineNow(TextView tvValue) {
        Toast.makeText(this, "正在开启 NPU 引擎（首次会加载模型）…", Toast.LENGTH_SHORT).show();
        inferenceRouter.enableNpuEngine(new com.oilquiz.app.ai.engine.NpuLlmChat.LoadListener() {
            @Override
            public void onLoaded(String modelName) {
                runOnUiThread(() -> {
                    Toast.makeText(ModelSelectorActivity.this,
                            "NPU 引擎已就绪：" + modelName, Toast.LENGTH_LONG).show();
                    refreshNpuEngineRow(tvValue);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(ModelSelectorActivity.this,
                            "NPU 不可用：" + message, Toast.LENGTH_LONG).show();
                    refreshNpuEngineRow(tvValue);
                });
            }
        });
    }

    /** 重新加载 NPU 模型（换模型后立即生效） */
    private void reloadNpuModel(TextView tvValue) {
        Toast.makeText(this, "正在重新加载 NPU 模型…", Toast.LENGTH_SHORT).show();
        try {
            // NPU-SESSION-RESET: 换模型前重置会话上下文（旧模板/旧 KV 不能复用）
        try {
            com.oilquiz.app.ai.engine.NpuLlmChat.resetIncrementalSession();
        } catch (Throwable ignored) {
        }
        com.oilquiz.app.ai.engine.NpuLlmChat.release();   // 先释放旧权重，避免两份常驻
        } catch (Throwable ignored) {
        }
        inferenceRouter.enableNpuEngine(new com.oilquiz.app.ai.engine.NpuLlmChat.LoadListener() {
            @Override
            public void onLoaded(String modelName) {
                runOnUiThread(() -> {
                    Toast.makeText(ModelSelectorActivity.this,
                            "NPU 模型已加载：" + modelName, Toast.LENGTH_LONG).show();
                    refreshNpuEngineRow(tvValue);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(ModelSelectorActivity.this,
                            "NPU 加载失败：" + message, Toast.LENGTH_LONG).show();
                    refreshNpuEngineRow(tvValue);
                });
            }
        });
    }

    /**
     * 选择 NPU 用哪个模型：自动（优先 Q4_0 / App 模型库）或显式指定某个 gguf。
     * 依据：多个同量化同尺寸模型并存时自动打分并列，会出现"挑错模型"。
     */
    private void showNpuModelDialog(TextView tvValue) {
        final java.util.List<String> models = new java.util.ArrayList<>();
        models.add("自动（推荐：优先 Q4_0、优先 App 模型库）");
        try {
            models.addAll(com.oilquiz.app.ai.engine.NpuLlmChat.listAvailableModels(this));
        } catch (Throwable ignored) {
        }
        final String cur = com.oilquiz.app.ai.engine.NpuLlmChat.preferredModelName();
        int checked = 0;
        for (int i = 1; i < models.size(); i++) {
            if (models.get(i).equals(cur)) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("选择 NPU 模型（当前：" + (cur == null || cur.isEmpty() ? "自动" : cur) + "）")
                .setSingleChoiceItems(models.toArray(new String[0]), checked, (d, which) -> {
                    boolean auto = (which == 0);
                    String pick = auto ? null : models.get(which);
                    com.oilquiz.app.ai.engine.NpuLlmChat.setPreferredModelName(pick);
                    d.dismiss();
                    if (inferenceRouter != null && inferenceRouter.isNpuEngineEnabled()) {
                        reloadNpuModel(tvValue);   // 立即按新选择重新加载
                    } else {
                        Toast.makeText(this, "已记录：" + (auto ? "自动" : pick), Toast.LENGTH_SHORT).show();
                        refreshNpuEngineRow(tvValue);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 刷新功能专用模型的当前配置显示 */
    private void updateFeatureModelsDisplay() {
        if (tvAsrModelValue != null) {
            tvAsrModelValue.setText(com.oilquiz.app.ai.speech.SpeechManager
                    .getInstance(this).getCurrentAsrModelDisplay());
        }
        if (tvTtsModelValue != null) {
            tvTtsModelValue.setText(com.oilquiz.app.ai.speech.SpeechManager
                    .getInstance(this).getCurrentTtsModelDisplay());
        }
        if (tvTtsVoiceValue != null) {
            // 未保存过音色时不回填默认值，显示"跟随模型默认"避免误导
            String savedVoice = com.oilquiz.app.ai.speech.SpeechManager
                    .getInstance(this).getSavedTtsVoice();
            if (savedVoice == null || savedVoice.isEmpty()) {
                tvTtsVoiceValue.setText(getString(R.string.h_6871533f));
            } else if (savedVoice.startsWith(com.oilquiz.app.ai.speech.TTSService.SYS_VOICE_PREFIX)) {
                tvTtsVoiceValue.setText(getString(R.string.h_89e2a0f3)
                        + savedVoice.substring(com.oilquiz.app.ai.speech.TTSService.SYS_VOICE_PREFIX.length()));
            } else {
                tvTtsVoiceValue.setText(savedVoice);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 注销监听器，避免 Activity 泄漏
        if (modelChangeListener != null) {
            onlineModelManager.removeListener(modelChangeListener);
            modelChangeListener = null;
        }
    }

    private void updateServiceStatus() {
        if (serviceStatusBar == null || statusIndicator == null || tvServiceStatus == null) {
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
            serviceStatusBar.setVisibility(View.GONE);
        } else {
            serviceStatusBar.setVisibility(View.VISIBLE);
            if (validCount == totalCount) {
                statusIndicator.setBackgroundResource(R.drawable.status_indicator_valid);
                tvServiceStatus.setText(validCount + "/" + totalCount + getString(R.string.h_46b389d6));
            } else if (validCount > 0) {
                statusIndicator.setBackgroundResource(R.drawable.status_indicator_rate_limited);
                tvServiceStatus.setText(validCount + "/" + totalCount + getString(R.string.h_46b389d6));
            } else {
                statusIndicator.setBackgroundResource(R.drawable.status_indicator_invalid);
                tvServiceStatus.setText(getString(R.string.h_5556b5e7));
            }
        }
    }

    /** 弹出语音功能介绍对话框 */
    private void showVoiceFeatureInfoDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_voice_feature_info, null);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        dialog.show();

        // 设置对话框宽度与页面一致
        dialog.getWindow().setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        dialog.getWindow().setGravity(android.view.Gravity.BOTTOM);

        dialogView.findViewById(R.id.bt_close).setOnClickListener(v -> dialog.dismiss());
    }
}
