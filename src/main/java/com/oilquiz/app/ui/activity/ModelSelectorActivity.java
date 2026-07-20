package com.oilquiz.app.ui.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
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
    private LinearLayout onlineModelsSection;
    private View serviceStatusBar;
    private View statusIndicator;
    private TextView tvServiceStatus;
    private TextView tvUsageInfo;

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
            onlineModelsSection = findViewById(R.id.online_models_section);

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
                refreshButton.setOnClickListener(v -> refreshModels());
            }
            if (addOnlineModelButton != null) {
                addOnlineModelButton.setOnClickListener(v -> {
                    Intent intent = new Intent(this, ApiConfigActivity.class);
                    startActivity(intent);
                });
            }

            // 初始化在线模型列表
            setupOnlineModelsRecycler();
            
            refreshModels();
            
            // 注册监听器
            onlineModelManager.addListener(new OnlineModelManager.ModelChangeListener() {
                @Override
                public void onModelListChanged() {
                    refreshOnlineModels();
                }

                @Override
                public void onActiveModelChanged(String activeModelId) {
                    updateCurrentModelDisplay();
                }
            });
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
            List<String> modelList = new ArrayList<>();
            if (availableModels != null) {
                for (String model : availableModels) {
                    modelList.add(model);
                }
            }

            String currentModel = aiService.getCurrentModelName();
            if (currentModelNameTextView != null) {
                if (currentModel != null) {
                    currentModelNameTextView.setText(currentModel);
                } else {
                    currentModelNameTextView.setText("未初始化");
                }
            }

            if (modelsRecycler != null) {
                if (modelAdapter == null) {
                    modelAdapter = new ModelAdapter(this, modelList, currentModel, this);
                    modelsRecycler.setAdapter(modelAdapter);
                } else {
                    modelAdapter.updateData(modelList, currentModel);
                }
            }

            // 刷新在线模型
            refreshOnlineModels();
            
            // 更新当前模型显示
            updateCurrentModelDisplay();

            if (modelList.isEmpty() && !onlineModelManager.hasModels()) {
                Toast.makeText(this, "暂无可用模型，请先导入模型或添加在线模型", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "刷新模型列表失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshOnlineModels() {
        if (onlineModelsSection == null || onlineModelAdapter == null) {
            return;
        }
        
        List<OnlineModelManager.OnlineModelConfig> onlineModels = onlineModelManager.getModelList();
        if (onlineModels.isEmpty()) {
            onlineModelsSection.setVisibility(View.GONE);
        } else {
            onlineModelsSection.setVisibility(View.VISIBLE);
            String activeId = onlineModelManager.getActiveModel() != null ? 
                onlineModelManager.getActiveModel().id : null;
            onlineModelAdapter.updateData(onlineModels, activeId);
        }
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
                    onlineModelManager.setActiveModel(config.id);
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
            Toast.makeText(this, "正在切换到在线模型: " + modelName, Toast.LENGTH_SHORT).show();
            inferenceRouter.switchModel(onlineConfig.id);
            runOnUiThread(() -> {
                Toast.makeText(this, "在线模型切换成功: " + modelName, Toast.LENGTH_SHORT).show();
                refreshModels();
            });
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
                refreshOnlineModels();
                Toast.makeText(this, enabled ? "已启用" : "已禁用", Toast.LENGTH_SHORT).show();
                break;
            }
        }
    }

    @Override
    public void onAddClick() {
        Intent intent = new Intent(this, ApiConfigActivity.class);
        startActivity(intent);
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
                        onlineModelManager.saveCachedModels(config.id, modelsJson.toString());
                        Toast.makeText(this, "获取成功，共 " + models.size() + " 个模型", Toast.LENGTH_SHORT).show();
                        refreshOnlineModels();
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

    @Override
    public void onModelSelected(String modelName, String selectedModel) {
        OnlineModelManager.OnlineModelConfig config = findOnlineModelByName(modelName);
        if (config == null) {
            Toast.makeText(this, "未找到在线模型配置", Toast.LENGTH_SHORT).show();
            return;
        }

        onlineModelManager.saveSelectedModel(config.id, selectedModel);
        Toast.makeText(this, "已选择模型: " + selectedModel, Toast.LENGTH_SHORT).show();
        refreshOnlineModels();
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
