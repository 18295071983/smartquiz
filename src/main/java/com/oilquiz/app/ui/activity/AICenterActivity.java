package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.model.APIConfig;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.stats.TokenStatsManager;
import com.oilquiz.app.ai.util.APIKeyManager;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.inference.InferenceRouter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class AICenterActivity extends AppCompatActivity {

    private MaterialCardView cardServiceStatus;
    private TextView tvServiceStatus;

    private MaterialButton btnQuestionGenerator;
    private MaterialButton btnQuestionAnalyzer;
    private MaterialButton btnLearningAssistant;
    private MaterialButton btnTranslator;
    private MaterialButton btnAIChat;
    private MaterialButton btnIconDemo;

    // 在线模型组件
    private SwitchMaterial switchOnlineMode;
    private TextView tvCurrentOnlineModel;
    private TextView tvCurrentOnlineProvider;
    private MaterialButton btnAddApiConfig;
    private LinearLayout layoutOnlineConfigs;

    // 本地模型组件
    private SwitchMaterial switchLocalMode;
    private TextView tvCurrentLocalModel;
    private TextView tvLocalModelInfo;
    private MaterialButton btnManageLocalModels;
    private MaterialButton btnDownloadLocalModels;
    private LinearLayout layoutLocalModels;

    // Token统计组件
    private TextView tvInputTokens;
    private TextView tvOutputTokens;
    private TextView tvTotalTokens;
    private TextView tvTokenCost;

    // 服务组件
    private AIService aiService;
    private OnlineModelManager onlineModelManager;
    private InferenceRouter inferenceRouter;

    private TokenStatsManager.TokenStatsCallback tokenStatsCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_center);

        initServices();
        initViews();
        setupClickListeners();
        setupTokenStats();
    }

    private void initServices() {
        aiService = AIService.getInstance(this);
        onlineModelManager = OnlineModelManager.getInstance(this);
        inferenceRouter = InferenceRouter.getInstance(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
        refreshOnlineModels();
        refreshLocalModels();
        updateTokenStatsUI();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        TokenStatsManager.getInstance().unregisterCallback(tokenStatsCallback);
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
                    tvTokenCost.setText(String.format("约 ¥%.2f", cost * 7.2));
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

        // 在线模型组件初始化
        switchOnlineMode = findViewById(R.id.switch_online_mode);
        tvCurrentOnlineModel = findViewById(R.id.tv_current_online_model);
        tvCurrentOnlineProvider = findViewById(R.id.tv_current_online_provider);
        btnAddApiConfig = findViewById(R.id.btn_add_api_config);
        layoutOnlineConfigs = findViewById(R.id.layout_online_configs);

        // 本地模型组件初始化
        switchLocalMode = findViewById(R.id.switch_local_mode);
        tvCurrentLocalModel = findViewById(R.id.tv_current_local_model);
        tvLocalModelInfo = findViewById(R.id.tv_local_model_info);
        btnManageLocalModels = findViewById(R.id.btn_manage_local_models);
        btnDownloadLocalModels = findViewById(R.id.btn_download_local_models);
        layoutLocalModels = findViewById(R.id.layout_local_models);

        // Token统计组件初始化
        tvInputTokens = findViewById(R.id.tv_input_tokens);
        tvOutputTokens = findViewById(R.id.tv_output_tokens);
        tvTotalTokens = findViewById(R.id.tv_total_tokens);
        tvTokenCost = findViewById(R.id.tv_token_cost);

        updateServiceStatus();
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

        // 在线模型按钮点击事件
        if (btnAddApiConfig != null) {
            btnAddApiConfig.setOnClickListener(v -> {
                startActivity(new Intent(AICenterActivity.this, ApiConfigActivity.class));
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

        // 模式切换开关
        if (switchOnlineMode != null) {
            switchOnlineMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    switchLocalMode.setChecked(false);
                }
            });
        }

        if (switchLocalMode != null) {
            switchLocalMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    switchOnlineMode.setChecked(false);
                }
            });
        }
    }

    private void refreshOnlineModels() {
        OnlineModelManager.OnlineModelConfig activeConfig = onlineModelManager.getActiveModel();

        if (tvCurrentOnlineModel != null) {
            if (activeConfig != null) {
                tvCurrentOnlineModel.setText(activeConfig.modelName != null ? activeConfig.modelName : "GPT-4o");
                tvCurrentOnlineProvider.setText("OpenAI");
            } else {
                tvCurrentOnlineModel.setText("未选择模型");
                tvCurrentOnlineProvider.setText("点击添加配置");
            }
        }

        // 刷新在线模型配置列表
        if (layoutOnlineConfigs != null) {
            layoutOnlineConfigs.removeAllViews();
            List<OnlineModelManager.OnlineModelConfig> configs = onlineModelManager.getModelList();
            for (OnlineModelManager.OnlineModelConfig config : configs) {
                View itemView = createOnlineModelItemView(config);
                layoutOnlineConfigs.addView(itemView);
            }
        }
    }

    private View createOnlineModelItemView(OnlineModelManager.OnlineModelConfig config) {
        View view = LayoutInflater.from(this).inflate(R.layout.item_online_model_simple, layoutOnlineConfigs, false);

        TextView tvName = view.findViewById(R.id.tv_model_name);
        TextView tvStatus = view.findViewById(R.id.tv_model_status);
        MaterialButton btnSwitch = view.findViewById(R.id.btn_switch_model);
        Spinner spinnerModels = view.findViewById(R.id.spinner_models);

        tvName.setText(config.name);
        tvStatus.setText(config.enabled ? "✓ 已启用" : "○ 已禁用");
        tvStatus.setTextColor(config.enabled ? getColor(R.color.success) : getColor(R.color.text_secondary));

        // 设置模型选择器
        List<String> modelList = new ArrayList<>();
        String cachedModels = onlineModelManager.getCachedModels(config.id);
        if (cachedModels != null && !cachedModels.isEmpty()) {
            try {
                JSONArray array = new JSONArray(cachedModels);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    modelList.add(obj.getString("id"));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (!modelList.isEmpty()) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modelList);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerModels.setAdapter(adapter);

            // 设置当前选中的模型
            if (config.selectedModel != null) {
                int position = modelList.indexOf(config.selectedModel);
                if (position >= 0) {
                    spinnerModels.setSelection(position);
                }
            }
        }

        btnSwitch.setOnClickListener(v -> {
            onlineModelManager.setActiveModel(config.id);
            Toast.makeText(this, "已切换到: " + config.name, Toast.LENGTH_SHORT).show();
            refreshOnlineModels();
        });

        return view;
    }

    private void refreshLocalModels() {
        String currentModel = aiService.getCurrentModelName();

        if (tvCurrentLocalModel != null) {
            if (currentModel != null && !currentModel.isEmpty()) {
                tvCurrentLocalModel.setText(currentModel);
                tvLocalModelInfo.setText("本地模型已加载");
            } else {
                tvCurrentLocalModel.setText("未加载模型");
                tvLocalModelInfo.setText("点击管理或下载模型");
            }
        }

        // 刷新本地模型列表
        if (layoutLocalModels != null) {
            layoutLocalModels.removeAllViews();
            String[] availableModels = aiService.getAvailableModels();
            if (availableModels != null) {
                for (String modelName : availableModels) {
                    View itemView = createLocalModelItemView(modelName, modelName.equals(currentModel));
                    layoutLocalModels.addView(itemView);
                }
            }
        }
    }

    private View createLocalModelItemView(String modelName, boolean isCurrent) {
        View view = LayoutInflater.from(this).inflate(R.layout.item_local_model_simple, layoutLocalModels, false);

        TextView tvName = view.findViewById(R.id.tv_model_name);
        TextView tvStatus = view.findViewById(R.id.tv_model_status);
        MaterialButton btnSwitch = view.findViewById(R.id.btn_switch_model);

        tvName.setText(modelName);
        tvStatus.setText(isCurrent ? "✓ 当前使用" : "○ 可用");
        tvStatus.setTextColor(isCurrent ? getColor(R.color.success) : getColor(R.color.text_secondary));

        btnSwitch.setText(isCurrent ? "使用中" : "切换");
        btnSwitch.setEnabled(!isCurrent);

        btnSwitch.setOnClickListener(v -> {
            Toast.makeText(this, "正在切换到: " + modelName, Toast.LENGTH_SHORT).show();
            onlineModelManager.stopActiveModel();

            // 使用热切换，带进度回调
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
                    runOnUiThread(() -> {
                        // 可以在这里更新进度条
                    });
                }

                @Override
                public void onSwitchCompleted(boolean success, String model) {
                    runOnUiThread(() -> {
                        if (success) {
                            Toast.makeText(AICenterActivity.this,
                                "模型切换成功: " + model, Toast.LENGTH_SHORT).show();
                            refreshLocalModels();
                        } else {
                            Toast.makeText(AICenterActivity.this,
                                "模型切换失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                @Override
                public void onSwitchFailed(String reason) {
                    runOnUiThread(() -> {
                        Toast.makeText(AICenterActivity.this,
                            "模型切换失败: " + reason, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });

        return view;
    }

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
            tvServiceStatus.setText("点击配置API服务");
        } else if (validCount == totalCount) {
            tvServiceStatus.setText("已配置 " + totalCount + " 个服务，全部正常");
        } else if (validCount > 0) {
            tvServiceStatus.setText(validCount + "/" + totalCount + " 个服务正常");
        } else {
            tvServiceStatus.setText("已配置 " + totalCount + " 个服务，全部异常");
        }
    }
}
