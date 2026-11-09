package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.model.APIConfig;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.stats.TokenStatsManager;
import com.oilquiz.app.ai.util.APIKeyManager;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.inference.InferenceRouter;


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
    private MaterialButton btnAIImage;
    private MaterialButton btnAIVideo;

    // 当前模型卡（MODEL-HUB-ONE-PLACE 2026-11-02）：只显示 + 一跳，不再挂模型列表。
    // 删掉的成员：switchOnlineMode / switchLocalMode（两个只互相取消勾选的假开关）、
    // onlineModelsRecycler + onlineModelAdapter + onlineModelsEmptyView、
    // modelsRecycler + modelAdapter + localModelsEmptyView、两个刷新按钮、
    // 以及"添加在线模型"按钮（添加对话框在「模型」页，那里才是唯一点选与添加的地方）。
    private TextView tvCurrentModelEngine;
    private TextView tvCurrentModelName;
    private TextView tvCurrentModelDetail;
    private MaterialButton btnGoModelPage;
    private MaterialButton btnManageLocalModels;
    private MaterialButton btnDownloadLocalModels;
    /** SAMPLING-PREFS(2026-11-01)：本地引擎采样参数入口（温度/top_p/top_k） */
    private MaterialButton btnLocalParams;

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

        updateCurrentModelDisplay();

        // 注册监听器（保存引用以便 onDestroy 中注销）：本页只显示当前模型，
        // 所以两个回调都只重画这一张卡
        modelChangeListener = new OnlineModelManager.ModelChangeListener() {
            @Override
            public void onModelListChanged() {
                updateCurrentModelDisplay();
            }

            @Override
            public void onActiveModelChanged(String activeModelId) {
                updateCurrentModelDisplay();
            }
        };
        onlineModelManager.addListener(modelChangeListener);
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
        updateCurrentModelDisplay();
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

        // 当前模型卡：三个显示位 + 四个动作按钮（切换/下载/管理/参数）
        tvCurrentModelEngine = findViewById(R.id.tv_current_model_engine);
        tvCurrentModelName = findViewById(R.id.tv_current_model_name);
        tvCurrentModelDetail = findViewById(R.id.tv_current_model_detail);
        btnGoModelPage = findViewById(R.id.btn_go_model_page);
        btnManageLocalModels = findViewById(R.id.btn_manage_local_models);
        btnDownloadLocalModels = findViewById(R.id.btn_download_local_models);
        btnLocalParams = findViewById(R.id.btn_local_params);

        // Token统计组件初始化
        tvInputTokens = findViewById(R.id.tv_input_tokens);
        tvOutputTokens = findViewById(R.id.tv_output_tokens);
        tvTotalTokens = findViewById(R.id.tv_total_tokens);
        tvTokenCost = findViewById(R.id.tv_token_cost);

        updateServiceStatus();
    }

    /**
     * SAMPLING-PREFS(2026-11-01)：本地引擎采样参数编辑入口。
     *
     * <p>值的权威存储是 {@link com.oilquiz.app.ai.config.UserModelParamsManager}
     * （与既有温度/上下文/层数同一处，不新建配置）；这里只提供编辑面。默认档
     * 温度 0.7 / top_p 0.9 / top_k 40 与硬编码时代逐值相同，所以"没动过=行为不变"。
     * 保存后从**下一轮请求**生效（本轮的 tools 前缀与采样在生成中不会改）。</p>
     */
    private void showSamplingDialog() {
        final com.oilquiz.app.ai.config.UserModelParamsManager u =
                com.oilquiz.app.ai.config.UserModelParamsManager.getInstance(this);
        final int pad = (int) (16 * getResources().getDisplayMetrics().density);

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);

        final android.widget.EditText etTemp = addNumberField(box,
                "温度 temperature（0.1–1.5）", String.valueOf(u.getAnswerTemperature()));
        final android.widget.EditText etTopP = addNumberField(box,
                "top_p（0.05–1.0）", String.valueOf(u.getTopP()));
        final android.widget.EditText etTopK = addNumberField(box,
                "top_k（0–100，0 = 关闭）", String.valueOf(u.getTopK()));
        // CONTEXT-SIZE(2026-11-01)：上下文长度（AIService 的手动覆盖，512–32768；留空=自动）
        final android.widget.EditText etCtx = addNumberField(box,
                "上下文长度（512–32768，留空 = 自动）",
                u.getContextSize() > 0 ? String.valueOf(u.getContextSize()) : "");

        // PERSONA(2026-11-01)：人设（AIConfig.system_prompt）。本地 Agent 与普通对话现在都会注入；
        // 默认档"请用中文回答。"视为未设置（跟内置角色行的"用中文"重复，注入只白吃前缀）。
        final com.oilquiz.app.ai.refactor.AIConfig aiCfg =
                new com.oilquiz.app.ai.refactor.AIConfig(this);
        String personaNow = aiCfg.getSystemPrompt();
        if (personaNow == null || personaNow.trim().isEmpty()
                || "请用中文回答。".equals(personaNow.trim())) {
            personaNow = "";
        }
        android.widget.TextView tvPersona = new android.widget.TextView(this);
        tvPersona.setText("人设 / 系统提示词（留空 = 用内置身份）");
        box.addView(tvPersona);
        final android.widget.EditText etPersona = new android.widget.EditText(this);
        etPersona.setText(personaNow);
        etPersona.setHint("例：你是一位化工厂工艺工程师助手，回答要带单位与量程。");
        etPersona.setMinLines(3);
        etPersona.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        etPersona.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.addView(etPersona);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("本地引擎参数")
                .setView(scroll)
                .setMessage("默认：温度 0.7 / top_p 0.9 / top_k 40。温度越低越稳（工具调用更可靠），"
                        + "越高越发散；人设留空则用内置身份。采样与人设下一轮生效；"
                        + "**上下文长度要重新装载模型才生效**（512–32768，留空=自动）。")
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        u.setTemperature(Float.parseFloat(etTemp.getText().toString().trim()));
                    } catch (NumberFormatException ignored) {
                    }
                    try {
                        u.setTopP(Float.parseFloat(etTopP.getText().toString().trim()));
                    } catch (NumberFormatException ignored) {
                    }
                    try {
                        u.setTopK(Integer.parseInt(etTopK.getText().toString().trim()));
                    } catch (NumberFormatException ignored) {
                    }
                    try {
                        String ctxStr = etCtx.getText().toString().trim();
                        if (ctxStr.isEmpty() || Integer.parseInt(ctxStr) <= 0) {
                            u.clearContextOverride();
                        } else {
                            int v = Integer.parseInt(ctxStr);
                            if (v < 512) v = 512;
                            if (v > 32768) v = 32768;
                            u.setContextSize(v);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                    try {
                        String persona = etPersona.getText().toString().trim();
                        aiCfg.setSystemPrompt(persona.isEmpty() ? "请用中文回答。" : persona);
                    } catch (Throwable ignored) {
                    }
                    Toast.makeText(this, "已保存：" + u.describeEffectiveSampling(),
                            Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("恢复默认", (d, w) -> {
                    u.resetSamplingToDefault();
                    u.clearContextOverride();
                    Toast.makeText(this, "已恢复默认（" + u.describeEffectiveSampling() + "）",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 一行「标签 + 数字输入」，返回 EditText 供保存时读取。 */
    private android.widget.EditText addNumberField(android.widget.LinearLayout parent,
                                                   String label, String value) {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(label);
        parent.addView(tv);
        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(value);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        parent.addView(et);
        return et;
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
                // 统一入口：复用已存在的聊天页实例，避免叠层导致历史会话被重复保存
                com.oilquiz.app.ai.chat.ui.ChatNavigation.openChat(AICenterActivity.this);
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

        // 当前模型卡：切换一律跳「模型」页（本地与在线都在那页点选，也在那里加在线模型）
        if (btnGoModelPage != null) {
            btnGoModelPage.setOnClickListener(v -> {
                startActivity(new Intent(AICenterActivity.this, ModelSelectorActivity.class));
            });
        }

        // 本地文件管理 / 下载 / 采样参数：这三个动作不是"选模型"，留在原目的地
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
        if (btnLocalParams != null) {
            btnLocalParams.setOnClickListener(v -> showSamplingDialog());
        }

        // 原来这里还有两个开关 switch_online_mode / switch_local_mode，监听只做"互相取消勾选"：
        // 既不写偏好、也不调路由（路由只看哪个模型激活，见 ROUTE-UNIFIED）。
        // 用户拨了它却什么都没发生，比没有这个开关更糟，所以随两份模型列表一起删掉。
    }

    // ==================== 当前模型显示 ====================

    /**
     * 只显示"现在用的是什么"，不做切换。
     *
     * <p>2026-11-02（MODEL-HUB-ONE-PLACE）：这一页原本还挂着在线模型列表与本地模型列表
     * （两份 adapter、两个只互相取消勾选的假开关、两个刷新按钮、以及切换/删除/启用/添加/
     * 拉取模型/选模型六个回调），与「模型」页、模型中心各挂一份同源数据。列表点选的唯一去处
     * 应是「模型」页——那里本地与在线都能点选切换，也带添加/导入/下载/一键初始化/语音模型。
     * 所以本卡的按钮只负责"显示 + 一跳"。</p>
     *
     * <p>引擎口径取 {@code getCurrentInferenceType()}：与聊天页顶部横幅、工具抽屉同一判据，
     * 避免出现"横幅写本地模型、这页写在线模型"的分叉。</p>
     */
    private void updateCurrentModelDisplay() {
        if (tvCurrentModelEngine == null || tvCurrentModelName == null) {
            return;
        }
        try {
            com.oilquiz.app.ai.model.InferenceType type =
                    inferenceRouter != null ? inferenceRouter.getCurrentInferenceType()
                            : com.oilquiz.app.ai.model.InferenceType.LOCAL;
            tvCurrentModelEngine.setText("引擎：" + type.getDisplayName());

            String name = inferenceRouter != null ? inferenceRouter.getCurrentModelName() : null;
            tvCurrentModelName.setText(name == null || name.isEmpty() ? "未选择模型" : name);

            if (tvCurrentModelDetail != null) {
                String detail;
                if (type == com.oilquiz.app.ai.model.InferenceType.ONLINE) {
                    OnlineModelManager.OnlineModelConfig active =
                            onlineModelManager != null ? onlineModelManager.getActiveModel() : null;
                    detail = active == null ? "未选择在线模型"
                            : (active.name + (active.apiUrl == null ? "" : " · " + active.apiUrl));
                } else {
                    String model = aiService != null ? aiService.getCurrentModelName() : null;
                    detail = model == null || model.isEmpty()
                            ? "本地模型未加载，点「🔀 切换模型」去选"
                            : "已加载：" + model;
                }
                tvCurrentModelDetail.setText(detail);
            }
        } catch (Throwable t) {
            android.util.Log.w("AICenterActivity", "当前模型显示刷新失败: " + t.getMessage());
        }
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
