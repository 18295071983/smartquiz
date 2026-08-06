package com.oilquiz.app.ui.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
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
    private LinearLayout onlineModelsSection;
    private View onlineModelsEmptyView;
    private View localModelsEmptyView;
    private View serviceStatusBar;
    private View statusIndicator;
    private TextView tvServiceStatus;
    private TextView tvUsageInfo;

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

            if (refreshButton != null) {
                refreshButton.setOnClickListener(v -> {
                    // 强制从数据源重新同步，再刷新 UI
                    forceRefreshModels();
                });
            }
            if (addOnlineModelButton != null) {
                addOnlineModelButton.setOnClickListener(v -> showAddOnlineModelDialog());
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
            Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
                currentModelNameTextView.setText(activeDisplayName != null ? activeDisplayName : "未选择模型");
            }

            // 更新本地模型列表
            if (modelsRecycler != null) {
                if (modelAdapter == null) {
                    modelAdapter = new ModelAdapter(this, modelList, currentModel, this);
                    modelsRecycler.setAdapter(modelAdapter);
                } else {
                    updateLocalModelAdapterSafe(modelList, currentModel);
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
                Toast.makeText(this, "暂无可用模型，请先导入模型或添加在线模型", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "刷新模型列表失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, "模型列表已刷新", Toast.LENGTH_SHORT).show();
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
            currentModelTypeTextView.setText("类型: " + type.getDisplayName());
        } else {
            currentModelNameTextView.setText("未选择模型");
            currentModelTypeTextView.setText("类型: 未知");
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
                    Toast.makeText(ModelSelectorActivity.this, "在线模型已添加并激活: " + config.name, Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "已切换到在线模型: " + modelName, Toast.LENGTH_SHORT).show();
            // 即时刷新所有显示
            refreshOnlineModels();
            updateCurrentModelDisplay();
            // 延迟刷新本地模型区域（可能不需要）
            safeDelayedRefreshModels(200);
        } else {
            Toast.makeText(this, "正在切换到本地模型: " + modelName, Toast.LENGTH_SHORT).show();
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
                                "模型切换成功: " + model, Toast.LENGTH_SHORT).show();
                            refreshModels();
                        } else {
                            Toast.makeText(ModelSelectorActivity.this,
                                "模型切换失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                @Override
                public void onSwitchFailed(String reason) {
                    runOnUiThread(() -> {
                        Toast.makeText(ModelSelectorActivity.this,
                            "模型切换失败: " + reason, Toast.LENGTH_SHORT).show();
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
            .setTitle("删除确认")
            .setMessage("确定要删除在线模型 \"" + config.name + "\" 吗？")
            .setPositiveButton("删除", (dialog, which) -> {
                onlineModelManager.removeModel(configId);
                // 同步删除 APIKeyManager 中对应的配置（如果存在）
                try {
                    APIKeyManager manager = APIKeyManager.getInstance(this);
                    if (manager.getAPIConfigById(configId) != null) {
                        manager.deleteAPIConfig(configId);
                    }
                } catch (Exception ignored) {
                }
                Toast.makeText(this, "在线模型已删除", Toast.LENGTH_SHORT).show();
                refreshModels();
            })
            .setNegativeButton("取消", null)
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
                Toast.makeText(this, enabled ? "已启用" : "已禁用", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "未找到在线模型配置", Toast.LENGTH_SHORT).show();
            return;
        }
        showEditOnlineModelDialog(config);
    }

    private void showEditOnlineModelDialog(OnlineModelManager.OnlineModelConfig config) {
        OnlineModelConfigDialog dialog = new OnlineModelConfigDialog(this);
        dialog.setSaveListener(new OnlineModelConfigDialog.OnConfigSaveListener() {
            @Override
            public void onConfigSaved(OnlineModelManager.OnlineModelConfig updatedConfig) {
                Toast.makeText(ModelSelectorActivity.this, "配置已更新: " + updatedConfig.name, Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "未找到在线模型配置", Toast.LENGTH_SHORT).show();
            return;
        }

        if (config.apiKey == null || config.apiKey.isEmpty()) {
            Toast.makeText(this, "API Key 不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "正在获取模型列表...", Toast.LENGTH_SHORT).show();

        final String targetConfigId = config.id;
        modelListFetcher.fetchModels(config.apiUrl, config.apiKey)
            .thenAccept(models -> runOnUiThread(() -> {
                if (models != null && !models.isEmpty()) {
                    try {
                        JSONArray modelsJson = new JSONArray();
                        for (ApiModel model : models) {
                            JSONObject obj = new JSONObject();
                            obj.put("id", model.id);
                            obj.put("name", model.getName());
                            modelsJson.put(obj);
                        }
                        onlineModelManager.saveCachedModels(targetConfigId, modelsJson.toString());
                        Toast.makeText(this, "获取成功，共 " + models.size() + " 个模型", Toast.LENGTH_SHORT).show();
                        // 延迟 300ms 刷新，避免与 RecyclerView 布局/动画状态冲突
                        safeDelayedRefreshOnlineModels(300);
                    } catch (Exception e) {
                        Toast.makeText(this, "保存模型列表失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(this, "未获取到模型列表", Toast.LENGTH_SHORT).show();
                }
            }))
            .exceptionally(e -> {
                runOnUiThread(() -> {
                    Toast.makeText(this, "获取失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "未找到在线模型配置", Toast.LENGTH_SHORT).show();
            return;
        }

        onlineModelManager.saveSelectedModel(config.id, selectedModel);
        // 自动切换到该在线模型
        inferenceRouter.switchModel(config.id);
        Toast.makeText(this, "已切换到模型: " + selectedModel, Toast.LENGTH_SHORT).show();
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
                tvServiceStatus.setText(validCount + "/" + totalCount + " 个服务正常");
            } else if (validCount > 0) {
                statusIndicator.setBackgroundResource(R.drawable.status_indicator_rate_limited);
                tvServiceStatus.setText(validCount + "/" + totalCount + " 个服务正常");
            } else {
                statusIndicator.setBackgroundResource(R.drawable.status_indicator_invalid);
                tvServiceStatus.setText("全部服务异常");
            }
        }
    }
}
