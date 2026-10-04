package com.oilquiz.app.ui.activity;

import com.oilquiz.app.theme.ThemeColors;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatSpinner;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.oilquiz.app.R;
import com.oilquiz.app.infra.AgentDebugBridge;
import android.content.ClipData;
import android.content.ClipboardManager;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.model.ModelManager;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AIServiceInitializer;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.refactor.AIConfig;
import com.oilquiz.app.ai.refactor.AIConfig.OptimizationMode;
import com.oilquiz.app.ai.stats.TokenStatsManager;

import java.util.Arrays;
import java.util.List;

public class AIServiceStatusActivity extends AppCompatActivity implements AIService.StatusObserver {

    private static final String TAG = "AIServiceStatusActivity";
    private static final int REQUEST_SELECT_MODEL = 1003;

    private AIService aiService;
    private ModelManager modelManager;
    private InferenceRouter inferenceRouter;

    // 状态指示灯
    private View statusLight;
    private TextView statusText;
    private TextView statusHint;
    private View libLight;
    private TextView libStatus;
    private View modelLight;
    private TextView modelName;
    private View contextLight;
    private TextView contextInfo;
    private View openclLight;
    private TextView openclStatus;
    private View gpuLight;
    private TextView gpuStatus;
    private TextView engineInfo;
    
    // GenieX NPU 推理入口
    private TextView npuGeniexStatus;
    
    // 功能状态指示灯
    private View chatLight;
    private View qaLight;
    private View deepLight;
    private View creativeLight;
    private View agentLight;
    private View summarizeLight;
    private View codeLight;
    private View analysisLight;
    
    // 上下文统计
    private TextView contextTotal;
    private TextView contextUsed;
    private TextView contextRemaining;
    private TextView contextPercent;
    private View contextProgress;

    // 模型架构信息
    private TextView modelParams;
    private TextView modelLayers;
    private TextView modelHeads;
    private TextView modelEmbd;
    private TextView modelCtxTrain;
    private TextView modelMemory;
    private TextView modelSpeed;
    private TextView modelTokens;
    private TextView modelFilename;
    private TextView modelParamsInfo;
    
    // 在线模型相关 UI
    private TextView onlineApiStatus;
    private View onlineApiLight;
    private TextView onlineModelInfo;
    private TextView onlineContextWindow;
    private TextView onlineTokenTotal;
    private TextView onlineTokenCompletion;
    private TextView onlineLatency;
    
    // 卡片容器（用于在线/离线模式切换时显示/隐藏）
    private View modelArchitectureCard;  // 模型架构卡片
    private View offlineOnlyRow1;  // 仅离线模式显示的行（OpenCL/GPU）
    private View offlineOnlyRow2;  // 仅离线模式显示的行（推理引擎/推理库）

    // 实时指标定时刷新
    private final Handler metricsHandler = new Handler(Looper.getMainLooper());
    private static final int METRICS_INTERVAL_MS = 2000;
    private final Runnable metricsRunnable = new Runnable() {
        @Override
        public void run() {
            updateRealtimeMetrics();
            metricsHandler.postDelayed(this, METRICS_INTERVAL_MS);
        }
    };
    
    private MaterialButton btnSelectModel;
    private MaterialButton btnTestAi;
    private MaterialButton btnDeviceInfo;
    private MaterialButton btnApplyGpuLayers;
    private android.widget.RadioGroup gpuBackendGroup;
    private android.widget.RadioButton backendAuto;
    private android.widget.RadioButton backendOpencl;
    private android.widget.RadioButton backendVulkan;
    private android.widget.EditText gpuLayersInput;
    private SwitchMaterial aiEnableSwitch;
    private AppCompatSpinner optimizationModeSpinner;
    private SwitchMaterial agentSwitch;
    private SwitchMaterial useJsonProtocolSwitch;
    private SwitchMaterial localAgentSwitch;
    private SwitchMaterial localFcSwitch;
    private AppCompatSpinner tokenSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_service_status);

        // 初始化服务
        aiService = AIService.getInstance(this);
        modelManager = new ModelManager(this);
        inferenceRouter = InferenceRouter.getInstance(this);

        // 初始化UI组件
        initUI();

        // 设置按钮点击事件
        setButtonListeners();

        // 初始化token选择器
        initTokenSpinner();

        // 刷新状态
        refreshStatus();
        
        // 注册状态观察者
        aiService.registerStatusObserver(this);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 停止定时刷新
        metricsHandler.removeCallbacks(metricsRunnable);
        // 取消注册状态观察者，避免内存泄漏
        aiService.unregisterStatusObserver(this);
    }

    private void initUI() {
        // 状态指示灯
        statusLight = findViewById(R.id.status_light);
        statusText = findViewById(R.id.status_text);
        statusHint = findViewById(R.id.status_hint);
        libLight = findViewById(R.id.lib_light);
        libStatus = findViewById(R.id.lib_status);
        modelLight = findViewById(R.id.model_light);
        modelName = findViewById(R.id.model_name);
        contextLight = findViewById(R.id.context_light);
        contextInfo = findViewById(R.id.context_info);
        openclLight = findViewById(R.id.opencl_light);
        openclStatus = findViewById(R.id.opencl_status);
        gpuLight = findViewById(R.id.gpu_light);
        gpuStatus = findViewById(R.id.gpu_status);
        engineInfo = findViewById(R.id.engine_info);
        npuGeniexStatus = findViewById(R.id.npu_geniex_status);

        // 功能状态指示灯
        chatLight = findViewById(R.id.chat_light);
        qaLight = findViewById(R.id.qa_light);
        deepLight = findViewById(R.id.deep_light);
        creativeLight = findViewById(R.id.creative_light);
        agentLight = findViewById(R.id.agent_light);
        summarizeLight = findViewById(R.id.summarize_light);
        codeLight = findViewById(R.id.code_light);
        analysisLight = findViewById(R.id.analysis_light);

        // 上下文统计
        contextTotal = findViewById(R.id.context_total);
        contextUsed = findViewById(R.id.context_used);
        contextRemaining = findViewById(R.id.context_remaining);
        contextPercent = findViewById(R.id.context_percent);
        contextProgress = findViewById(R.id.context_progress);

        // 模型架构信息
        modelParams = findViewById(R.id.model_params);
        modelLayers = findViewById(R.id.model_layers);
        modelHeads = findViewById(R.id.model_heads);
        modelEmbd = findViewById(R.id.model_embd);
        modelCtxTrain = findViewById(R.id.model_ctx_train);
        modelMemory = findViewById(R.id.model_memory);
        modelSpeed = findViewById(R.id.model_speed);
        modelTokens = findViewById(R.id.model_tokens);
        modelFilename = findViewById(R.id.model_filename);
        modelParamsInfo = findViewById(R.id.model_params_info);
        
        // 在线模型相关 UI
        onlineApiStatus = findViewById(R.id.online_api_status);
        onlineApiLight = findViewById(R.id.online_api_light);
        onlineModelInfo = findViewById(R.id.online_model_info);
        onlineContextWindow = findViewById(R.id.online_context_window);
        onlineTokenTotal = findViewById(R.id.online_token_total);
        onlineTokenCompletion = findViewById(R.id.online_token_completion);
        onlineLatency = findViewById(R.id.online_latency);
        
        // 卡片容器（用于在线/离线模式切换时显示/隐藏）
        modelArchitectureCard = findViewById(R.id.online_model_card);

        // 按钮
        btnSelectModel = findViewById(R.id.btn_select_model);

        // 配置选项
        tokenSpinner = findViewById(R.id.token_spinner);
        aiEnableSwitch = findViewById(R.id.ai_enable_switch);
        optimizationModeSpinner = findViewById(R.id.optimization_mode_spinner);
        agentSwitch = findViewById(R.id.agent_switch);
        useJsonProtocolSwitch = findViewById(R.id.use_json_protocol_switch);
        localAgentSwitch = findViewById(R.id.local_agent_switch);
        localFcSwitch = findViewById(R.id.local_fc_switch);

        // 初始化按钮：未配置本地且未配置在线模型 → 进入一键初始化精美引导界面
        MaterialButton btnInitializeModel = findViewById(R.id.btn_initialize_model);
        if (btnInitializeModel != null) {
            btnInitializeModel.setOnClickListener(v -> {
                if (AIServiceInitializer.needsInitialization(this)) {
                    startActivity(new Intent(AIServiceStatusActivity.this, AIServiceInitActivity.class));
                    return;
                }

                // 已配置（本地或在线）→ 手动初始化本地 AI 服务
                Toast.makeText(this, getString(R.string.h_c9fe41a6), Toast.LENGTH_SHORT).show();

                new Thread(() -> {
                    boolean success = aiService.initializeSafe();

                    runOnUiThread(() -> {
                        if (success) {
                            Toast.makeText(this, getString(R.string.h_05396f2b), Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, getString(R.string.h_f559a2f7), Toast.LENGTH_SHORT).show();
                        }
                        refreshStatus();
                    });
                }).start();
            });
        }

        // 测试AI功能按钮
        btnTestAi = findViewById(R.id.btn_test_ai);
        
        // 设备信息按钮
        btnDeviceInfo = findViewById(R.id.btn_device_info);

        // GPU 层数设置（修改后需重载模型才生效）
        // GPU backend switch (OpenCL / Vulkan / auto): saved to default SP key gpu_backend, applied to native on model load
        gpuBackendGroup = findViewById(R.id.gpu_backend_group);
        backendAuto = findViewById(R.id.backend_auto);
        backendOpencl = findViewById(R.id.backend_opencl);
        backendVulkan = findViewById(R.id.backend_vulkan);
        if (gpuBackendGroup != null) {
            // restore last choice
            String savedBackend = android.preference.PreferenceManager.getDefaultSharedPreferences(this)
                    .getString("gpu_backend", "auto");
            if ("opencl".equals(savedBackend)) backendOpencl.setChecked(true);
            else if ("vulkan".equals(savedBackend)) backendVulkan.setChecked(true);
            else backendAuto.setChecked(true);
            gpuBackendGroup.setOnCheckedChangeListener((group, checkedId) -> {
                String choice = "auto";
                if (checkedId == R.id.backend_opencl) choice = "opencl";
                else if (checkedId == R.id.backend_vulkan) choice = "vulkan";
                android.preference.PreferenceManager.getDefaultSharedPreferences(this)
                        .edit().putString("gpu_backend", choice).apply();
                LlamaHelper.setBackend(choice);
                android.widget.Toast.makeText(this, "GPU backend switched to " + choice + ", reload model to take effect", android.widget.Toast.LENGTH_SHORT).show();
            });
        }

        gpuLayersInput = findViewById(R.id.gpu_layers_input);
        btnApplyGpuLayers = findViewById(R.id.btn_apply_gpu_layers);
        if (gpuLayersInput != null) {
            // 默认"自动"：仅当存在手动覆盖（gpu_layers_manual）时才显示具体数值
            gpuLayersInput.setText(isGpuLayersManual()
                    ? String.valueOf(LlamaHelper.getGPULayers())
                    : "自动");
        }
    }

    /** 是否处于手动 GPU 层数模式（model_state_cache 中存在 gpu_layers_manual 键） */
    private boolean isGpuLayersManual() {
        return getSharedPreferences("model_state_cache", MODE_PRIVATE)
                .contains("gpu_layers_manual");
    }

    private void setButtonListeners() {
        if (btnSelectModel != null) {
            btnSelectModel.setOnClickListener(v -> {
                Intent intent = new Intent(this, ModelSelectorActivity.class);
                startActivityForResult(intent, REQUEST_SELECT_MODEL);
            });
        }

        if (aiEnableSwitch != null) {
            aiEnableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // 这里可以保存AI功能启用状态
                Toast.makeText(this, getString(R.string.h_d0f2a568) + (isChecked ? getString(R.string.h_7854b52a) : getString(R.string.h_710ad08b)), Toast.LENGTH_SHORT).show();
            });
        }

        AIConfig aiConfig = new AIConfig(this);
        if (optimizationModeSpinner != null) {
            OptimizationMode[] modes = OptimizationMode.values();
            String[] modeNames = new String[modes.length];
            for (int i = 0; i < modes.length; i++) modeNames[i] = modes[i].displayName;

            android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, modeNames);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            optimizationModeSpinner.setAdapter(adapter);

            OptimizationMode currentMode = aiService.getOptimizationMode();
            int currentPosition = currentMode != null ? currentMode.id : OptimizationMode.BALANCED.id;
            optimizationModeSpinner.setSelection(currentPosition);

            optimizationModeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                    OptimizationMode selected = OptimizationMode.fromId(position);
                    aiService.setOptimizationMode(selected);
                    Toast.makeText(AIServiceStatusActivity.this, getString(R.string.h_ecf8ff4c) + selected.displayName + getString(R.string.h_76c9800c), Toast.LENGTH_SHORT).show();
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }

        if (agentSwitch != null) {
            // 先设置当前状态
            agentSwitch.setChecked(aiConfig.isAgentEnabled());
            // 设置监听
            agentSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                aiConfig.setAgentEnabled(isChecked);
                String msg = isChecked ? "Agent代理已启用，重启AI对话后生效" : "Agent代理已禁用";
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                // 同步更新功能状态指示灯
                runOnUiThread(() -> {
                    if (agentLight != null) {
                        if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                            agentLight.setBackgroundResource(isChecked ? R.drawable.circle_green : R.drawable.circle_red);
                        }
                    }
                });
            });
        }

        // 本地推理 JSON 协议开关（spec §10.2 回退用；默认 true 新协议优先）
        if (useJsonProtocolSwitch != null) {
            useJsonProtocolSwitch.setChecked(aiConfig.isUseJsonProtocol());
            useJsonProtocolSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                aiConfig.setUseJsonProtocol(isChecked);
                Toast.makeText(this, isChecked
                        ? "已启用本地推理JSON协议（重启AI对话后生效）"
                        : getString(R.string.h_ce44f945), Toast.LENGTH_SHORT).show();
            });
        }

        // 本地 Agent 开关：启用后本地模型走 AgentSoftwareLayer，自动启用 FC 工具调用循环
        if (localAgentSwitch != null) {
            localAgentSwitch.setChecked(aiConfig.isLocalAgentEnabled());
            localAgentSwitch.setEnabled(true);
            localAgentSwitch.setAlpha(1.0f);
            localAgentSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                aiConfig.setLocalAgentEnabled(isChecked);
                Toast.makeText(this, isChecked
                        ? "本地Agent已启用（含工具调用，新消息即时生效）"
                        : getString(R.string.h_5d022097), Toast.LENGTH_SHORT).show();
            });
        }

        // FC 开关已合并到本地 Agent，隐藏独立开关
        if (localFcSwitch != null) {
            localFcSwitch.setVisibility(View.GONE);
        }

        // ===== 开发者调试通道（AgentDebugBridge）：可视化开关 + Token 显示/复制 =====
        SwitchMaterial bridgeEnabledSwitch = findViewById(R.id.bridge_enabled_switch);
        TextView tvBridgeToken = findViewById(R.id.tv_bridge_token);
        MaterialButton btnCopyBridgeToken = findViewById(R.id.btn_copy_bridge_token);
        if (bridgeEnabledSwitch != null && tvBridgeToken != null) {
            bridgeEnabledSwitch.setChecked(AgentDebugBridge.isEnabled(this));
            tvBridgeToken.setText(AgentDebugBridge.getToken(this));
            bridgeEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                AgentDebugBridge.setEnabled(AIServiceStatusActivity.this, isChecked);
                Toast.makeText(AIServiceStatusActivity.this,
                        isChecked ? "外部注入通道已开启" : "外部注入通道已关闭",
                        Toast.LENGTH_SHORT).show();
            });
            if (btnCopyBridgeToken != null) {
                btnCopyBridgeToken.setOnClickListener(v -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("AgentBridgeToken", tvBridgeToken.getText()));
                        Toast.makeText(AIServiceStatusActivity.this, "Token 已复制", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            // 打开调试控制台（本地运行智能体 + 观测外部注入）
            MaterialButton btnOpenDebug = findViewById(R.id.btn_open_debug);
            if (btnOpenDebug != null) {
                btnOpenDebug.setOnClickListener(v -> {
                    Intent i = new Intent(AIServiceStatusActivity.this, AgentDebugActivity.class);
                    startActivity(i);
                });
            }
        }

        if (btnTestAi != null) {
            btnTestAi.setOnClickListener(v -> {
                showTestAiDialog();
            });
        }
        
        if (btnDeviceInfo != null) {
            btnDeviceInfo.setOnClickListener(v -> {
                Intent intent = new Intent(this, DeviceInfoActivity.class);
                startActivity(intent);
            });
        }

        if (btnApplyGpuLayers != null) {
            btnApplyGpuLayers.setOnClickListener(v -> applyGpuLayersAndReload());
        }

        MaterialButton btnPerformance = findViewById(R.id.btn_performance);
        if (btnPerformance != null) {
            btnPerformance.setOnClickListener(v ->
                    startActivity(new Intent(this, PerformanceActivity.class)));
        }

        // GenieX NPU 推理入口
        MaterialButton btnOpenNpuInfer = findViewById(R.id.btn_open_npu_infer);
        if (btnOpenNpuInfer != null) {
            btnOpenNpuInfer.setOnClickListener(v ->
                    startActivity(new Intent(this, com.oilquiz.app.ui.activity.ModelSelectorActivity.class)));
        }
        if (npuGeniexStatus != null) {
            try {
                npuGeniexStatus.setText("GenieX SDK 已接入 · 状态 "
                        + com.oilquiz.app.ai.engine.NpuLlmChat.getStateName()
                        + " · Hexagon NPU（SM8850）");
            } catch (Throwable t) {
                npuGeniexStatus.setText("GenieX SDK 已接入 · Hexagon NPU（SM8850）");
            }
        }
    }

    /**
     * 应用 GPU 层数设置并重载模型（GPU 层数仅在 initModel 时生效，改后必须重载）
     * 输入"自动"/"auto"/空 = 恢复自动计算；输入 0-36 整数 = 手动指定
     */
    private void applyGpuLayersAndReload() {
        if (gpuLayersInput == null) return;
        String inputText = gpuLayersInput.getText().toString().trim();

        // 自动模式：删除手动键，恢复按设备/模型/内存自动计算
        if (inputText.isEmpty() || "自动".equals(inputText) || "auto".equalsIgnoreCase(inputText)) {
            getSharedPreferences("model_state_cache", MODE_PRIVATE)
                    .edit().remove("gpu_layers_manual").apply();
            Toast.makeText(this, getString(R.string.h_5501692f), Toast.LENGTH_SHORT).show();
            boolean modelLoaded = LlamaHelper.isModelInitialized();
            if (modelLoaded && aiService != null) {
                aiService.reloadModelAsync(null);
            }
            refreshStatus();
            return;
        }

        final int target;
        try {
            target = Integer.parseInt(inputText);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.h_76df4b83), Toast.LENGTH_SHORT).show();
            return;
        }
        if (target < 0 || target > 36) {
            Toast.makeText(this, getString(R.string.h_6271e9b8), Toast.LENGTH_SHORT).show();
            return;
        }
        int current = 0;
        try { current = LlamaHelper.getGPULayers(); } catch (Exception ignored) {}
        boolean modelLoaded = LlamaHelper.isModelInitialized();

        if (modelLoaded && target == current) {
            Toast.makeText(this, getString(R.string.h_4c2eb49a) + current + "）", Toast.LENGTH_SHORT).show();
            return;
        }

        String msg = modelLoaded
                ? "GPU 层数将从 " + current + " 改为 " + target + "，需重新加载模型（数十秒）。继续？"
                : "GPU 层数将设为 " + target + "（模型未加载，下次加载时生效）。";
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.h_38673981))
                .setMessage(msg)
                .setPositiveButton(modelLoaded ? getString(R.string.h_bb00d535) : getString(R.string.h_38cf16f2), (d, w) -> {
                    // 持久化保存手动值（独立 key，加载时优先于自动计算；"恢复自动"删除此 key）
                    getSharedPreferences("model_state_cache", MODE_PRIVATE)
                            .edit().putInt("gpu_layers_manual", target).apply();
                    LlamaHelper.setGPULayers(target);
                    Toast.makeText(this, getString(R.string.h_66c80f21) + target, Toast.LENGTH_SHORT).show();

                    if (modelLoaded && aiService != null) {
                        // 原子重载：释放旧模型 + 用新 GPU 层数重新加载（同一串行执行器，避免竞态）
                        aiService.reloadModelAsync(new AIService.InitializeCallback() {
                            @Override
                            public void onResult(boolean success) {
                                runOnUiThread(() -> {
                                    Toast.makeText(AIServiceStatusActivity.this,
                                            success ? "模型已按新 GPU 层数重新加载" : "模型重载失败",
                                            Toast.LENGTH_SHORT).show();
                                    refreshStatus();
                                });
                            }
                        });
                    } else {
                        refreshStatus();
                    }
                })
                .setNeutralButton(getString(R.string.h_946091b8), (d, w) -> {
                    // 删除手动值，恢复自动计算（按设备/模型/内存）
                    getSharedPreferences("model_state_cache", MODE_PRIVATE)
                            .edit().remove("gpu_layers_manual").apply();
                    Toast.makeText(this, getString(R.string.h_5501692f), Toast.LENGTH_SHORT).show();
                    if (modelLoaded && aiService != null) {
                        aiService.reloadModelAsync(null);
                    }
                    refreshStatus();
                })
                .setNegativeButton(getString(R.string.h_625fb26b), null)
                .show();
    }

    private void initTokenSpinner() {
        if (tokenSpinner != null) {
            // 最大Token数选项：在线模型通常支持大输出，默认 16384；
            // 小档位保留给本地小模型/低端设备使用
            final List<String> tokenOptions = Arrays.asList("2048", "4096", "8192", "16384", "32768");
            android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, tokenOptions);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            tokenSpinner.setAdapter(adapter);

            AIConfig aiConfig = new AIConfig(this);
            int savedValue = aiConfig.getMaxTokens();
            int position = tokenOptions.indexOf(String.valueOf(savedValue));
            tokenSpinner.setSelection(position >= 0 ? position : 3); // 默认 16384

            tokenSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int pos, long id) {
                    int selected = Integer.parseInt(tokenOptions.get(pos));
                    AIConfig config = new AIConfig(AIServiceStatusActivity.this);
                    config.setMaxTokens(selected);
                    Toast.makeText(AIServiceStatusActivity.this, getString(R.string.h_54ff0e50) + selected, Toast.LENGTH_SHORT).show();
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }
    }

    private void refreshStatus() {
        // 判断当前使用的是在线还是离线模型
        boolean usingOnline = inferenceRouter != null && inferenceRouter.isUsingOnlineModel();
        
        if (usingOnline) {
            // 在线模式
            updateOnlineModelStatus();
            // 隐藏离线模型专属信息
            if (modelArchitectureCard != null) {
                // 模型架构卡片保持可见但显示离线状态
            }
            // 隐藏 GPU/OpenCL 等仅离线模式显示的元素
            hideOfflineOnlyElements();
        } else {
            // 离线模式
            // 刷新服务状态
            boolean isInitialized = aiService.isInitialized();
            boolean modelLoaded = isInitialized && LlamaHelper.isModelInitialized();
            
            updateMainStatus(modelLoaded, isInitialized);
            updateLibraryStatus();
            updateModelStatus(aiService.getCurrentModelName(), modelLoaded);
            updateModelArchitectureInfo();
            updateContextStatus();
            updateOpenCLStatus();
            updateFunctionStatus(modelLoaded);
            updateContextStats();
            
            // 显示在线模型状态（如果有的话）
            updateOnlineModelPreview();
            // 显示离线模型专属元素
            showOfflineOnlyElements();
        }
    }
    
    /** 更新在线模型详细状态 */
    private void updateOnlineModelStatus() {
        OnlineModelManager onlineManager = OnlineModelManager.getInstance(this);
        OnlineModelManager.OnlineModelConfig activeConfig = onlineManager.getActiveModel();
        
        // 显示在线模型卡片
        if (modelArchitectureCard != null) {
            modelArchitectureCard.setVisibility(View.VISIBLE);
        }
        
        if (activeConfig != null && activeConfig.enabled) {
            // API 状态：绿色
            if (onlineApiLight != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    onlineApiLight.setBackgroundResource(R.drawable.circle_green);
                }
            }
            if (onlineApiStatus != null) {
                onlineApiStatus.setText(getString(R.string.h_c5ea9c6a));
                onlineApiStatus.setTextColor(getResources().getColor(R.color.success));
            }
            // 模型信息
            String modelName = activeConfig.selectedModel != null && !activeConfig.selectedModel.isEmpty()
                    ? activeConfig.selectedModel : activeConfig.modelName;
            if (onlineModelInfo != null) {
                onlineModelInfo.setText(modelName != null ? modelName : activeConfig.name);
                onlineModelInfo.setTextColor(ThemeColors.attr(this, R.attr.colorControlText));
            }
            // 上下文窗口：配置时 API 检测到的真实值或配置表推断值（实际驱动历史压缩阈值）
            if (onlineContextWindow != null) {
                int window = activeConfig.contextWindow;
                if (window <= 0) {
                    window = OnlineModelManager.getContextWindowForModel(activeConfig.apiUrl,
                            modelName != null ? modelName : activeConfig.modelName);
                }
                onlineContextWindow.setText(formatWindow(window));
            }
            // Token 统计
            TokenStatsManager.TokenStats stats = TokenStatsManager.getInstance().getCurrentSnapshot();
            if (onlineTokenTotal != null) {
                onlineTokenTotal.setText(stats != null && stats.sessionTotalTokens > 0
                        ? String.valueOf(stats.sessionTotalTokens) : "-");
            }
            if (onlineTokenCompletion != null) {
                onlineTokenCompletion.setText(stats != null && stats.sessionCompletionTokens > 0
                        ? String.valueOf(stats.sessionCompletionTokens) : "-");
            }
            if (onlineLatency != null) {
                onlineLatency.setText("-"); // API 延迟暂不显示
            }
        } else {
            // 没有可用的在线模型
            if (onlineApiLight != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    onlineApiLight.setBackgroundResource(R.drawable.circle_red);
                }
            }
            if (onlineApiStatus != null) {
                onlineApiStatus.setText(getString(R.string.h_71dc8feb));
                onlineApiStatus.setTextColor(getResources().getColor(R.color.error));
            }
            if (onlineModelInfo != null) {
                onlineModelInfo.setText(getString(R.string.h_a8076f9b));
                onlineModelInfo.setTextColor(ThemeColors.attr(this, R.attr.colorControlTextHint));
            }
            if (onlineContextWindow != null) {
                onlineContextWindow.setText("-");
            }
            if (onlineTokenTotal != null) onlineTokenTotal.setText("-");
            if (onlineTokenCompletion != null) onlineTokenCompletion.setText("-");
            if (onlineLatency != null) onlineLatency.setText("-");
        }
    }
    
    /** 上下文窗口格式化：>=1M 显示 "1M"，>=1K 显示 "64K"，否则原值 */
    private String formatWindow(int window) {
        if (window >= 1000000) {
            return (window / 1000000) + "M";
        }
        if (window >= 1000) {
            return (window / 1000) + "K";
        }
        return String.valueOf(window);
    }

    /** 更新在线模型预览（离线模式下简要显示） */
    private void updateOnlineModelPreview() {
        OnlineModelManager onlineManager = OnlineModelManager.getInstance(this);
        OnlineModelManager.OnlineModelConfig activeConfig = onlineManager.getActiveModel();
        
        if (activeConfig != null && activeConfig.enabled) {
            // 有可用的在线模型，简要提示
            String modelName = activeConfig.selectedModel != null && !activeConfig.selectedModel.isEmpty()
                    ? activeConfig.selectedModel : activeConfig.modelName;
            // 可以在这里添加在线模型的简要提示
        }
    }
    
    /** 隐藏仅离线模式显示的元素 */
    private void hideOfflineOnlyElements() {
        if (openclLight != null) openclLight.setVisibility(View.GONE);
        if (openclStatus != null) openclStatus.setVisibility(View.GONE);
        if (gpuLight != null) gpuLight.setVisibility(View.GONE);
        if (gpuStatus != null) gpuStatus.setVisibility(View.GONE);
        if (libLight != null) libLight.setVisibility(View.GONE);
        if (libStatus != null) libStatus.setVisibility(View.GONE);
    }
    
    /** 显示仅离线模式显示的元素 */
    private void showOfflineOnlyElements() {
        if (openclLight != null) openclLight.setVisibility(View.VISIBLE);
        if (openclStatus != null) openclStatus.setVisibility(View.VISIBLE);
        if (gpuLight != null) gpuLight.setVisibility(View.VISIBLE);
        if (gpuStatus != null) gpuStatus.setVisibility(View.VISIBLE);
        if (libLight != null) libLight.setVisibility(View.VISIBLE);
        if (libStatus != null) libStatus.setVisibility(View.VISIBLE);
    }
    
    private void updateOpenCLStatus() {
        boolean libLoaded = LlamaHelper.isLibraryLoaded();
        
        if (libLoaded) {
            boolean openclLoaded = LlamaHelper.isOpenCLLoaded();
            boolean gpuWorking = LlamaHelper.isGPUWorking();
            int gpuLayers = 0;
            try {
                gpuLayers = LlamaHelper.getGPULayers();
            } catch (Exception e) {
                Log.e(TAG, "Error getting GPU layers: " + e.getMessage());
            }
            // 同步 GPU 层数输入框（重载后显示新值；自动模式下保持"自动"）
            if (gpuLayersInput != null) {
                gpuLayersInput.setText(isGpuLayersManual() ? String.valueOf(gpuLayers) : getString(R.string.h_3aed2c11));
            }
            
            if (openclLight != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    openclLight.setBackgroundResource(openclLoaded ? R.drawable.circle_green : R.drawable.circle_red);
                }
            }
            if (openclStatus != null) {
                String backendPref2 = android.preference.PreferenceManager.getDefaultSharedPreferences(this).getString("gpu_backend", "auto");
                String backendName2 = "vulkan".equals(backendPref2) ? "Vulkan" : ("opencl".equals(backendPref2) ? "OpenCL" : "自动");
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    openclStatus.setText(backendName2 + (openclLoaded ? " · 已启用" : " · 未启用"));
                }
                openclStatus.setTextColor(openclLoaded ? getResources().getColor(R.color.success) : getResources().getColor(R.color.error));
            }
            
            if (gpuLight != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    gpuLight.setBackgroundResource(gpuWorking ? R.drawable.circle_green : (openclLoaded ? R.drawable.circle_yellow : R.drawable.circle_red));
                }
            }
            if (gpuStatus != null) {
                String gpuMode = "";
                int gpuColor = 0;
                
                if (gpuWorking) {
                    if (gpuLayers > 0) {
                        gpuMode = "GPU加速 (" + gpuLayers + "层)";
                    } else {
                        gpuMode = "CPU模式";
                    }
                    gpuColor = getResources().getColor(R.color.success);
                } else if (openclLoaded) {
                    gpuMode = "已就绪";
                    gpuColor = getResources().getColor(R.color.warning);
                } else {
                    gpuMode = "未启用";
                    gpuColor = getResources().getColor(R.color.error);
                }
                
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    gpuStatus.setText(gpuMode);
                }
                gpuStatus.setTextColor(gpuColor);
            }
        } else {
            if (openclLight != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    openclLight.setBackgroundResource(R.drawable.circle_red);
                }
            }
            if (openclStatus != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    openclStatus.setText(getString(R.string.h_467b3e03));
                }
                openclStatus.setTextColor(getResources().getColor(R.color.error));
            }
            
            if (gpuLight != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    gpuLight.setBackgroundResource(R.drawable.circle_red);
                }
            }
            if (gpuStatus != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    gpuStatus.setText(getString(R.string.h_4637765b));
                }
                gpuStatus.setTextColor(getResources().getColor(R.color.error));
            }
        }
    }

    private void updateMainStatus(boolean modelLoaded, boolean isInitialized) {
        int successColor = getResources().getColor(R.color.success);
        int warningColor = getResources().getColor(R.color.warning);
        int errorColor = getResources().getColor(R.color.error);
        
        if (statusLight != null) {
            if (modelLoaded) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    statusLight.setBackgroundResource(R.drawable.circle_green);
                }
            } else if (isInitialized) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    statusLight.setBackgroundResource(R.drawable.circle_yellow);
                }
            } else {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    statusLight.setBackgroundResource(R.drawable.circle_red);
                }
            }
        }
        if (statusText != null) {
            if (modelLoaded) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    statusText.setText(getString(R.string.h_d679aea3));
                }
                statusText.setTextColor(successColor);
            } else if (isInitialized) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    statusText.setText(getString(R.string.h_2da32f3c));
                }
                statusText.setTextColor(warningColor);
            } else {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    statusText.setText(getString(R.string.h_4f8a2f0b));
                }
                statusText.setTextColor(errorColor);
            }
        }
        
        if (statusHint != null) {
            if (modelLoaded) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    statusHint.setText(getString(R.string.h_25d14de1));
                }
                statusHint.setTextColor(successColor);
            } else if (isInitialized) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    statusHint.setText(getString(R.string.h_33ed774e));
                }
                statusHint.setTextColor(warningColor);
            } else {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    statusHint.setText(getString(R.string.h_6fb79429));
                }
                statusHint.setTextColor(errorColor);
            }
        }
        
        if (engineInfo != null) {
            String engineDetail = "Llama CPP";
            if (LlamaHelper.isLibraryLoaded()) {
                int gpuLayers = 0;
                try {
                    gpuLayers = LlamaHelper.getGPULayers();
                } catch (Exception e) {
                }
                if (gpuLayers > 0) {
                    engineDetail += " (GPU加速, " + gpuLayers + "层)";
                } else {
                    engineDetail += " (CPU模式)";
                }
            } else {
                engineDetail += " (库未加载)";
            }
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                engineInfo.setText(engineDetail);
            }
        }
    }

    private void updateModelStatus(String currentModel, boolean modelLoaded) {
        if (modelLight != null) {
            if (modelLoaded) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelLight.setBackgroundResource(R.drawable.circle_green);
                }
            } else if (currentModel != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelLight.setBackgroundResource(R.drawable.circle_yellow);
                }
            } else {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelLight.setBackgroundResource(R.drawable.circle_red);
                }
            }
        }
        if (modelName != null) {
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                modelName.setText(currentModel != null ? currentModel : getString(R.string.h_f0409ecf));
            }
        }
    }

    private void updateLibraryStatus() {
        boolean isLibraryLoaded = LlamaHelper.isLibraryLoaded();
        
        if (libLight != null) {
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                libLight.setBackgroundResource(isLibraryLoaded ? R.drawable.circle_green : R.drawable.circle_red);
            }
        }
        if (libStatus != null) {
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                libStatus.setText(isLibraryLoaded ? getString(R.string.h_bec33d31) : getString(R.string.h_467b3e03));
            }
            libStatus.setTextColor(isLibraryLoaded ? getResources().getColor(R.color.success) : getResources().getColor(R.color.error));
        }
    }
    
    private void updateContextStatus() {
        boolean contextActive = aiService.isChatContextActive();
        
        if (contextLight != null) {
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                contextLight.setBackgroundResource(contextActive ? R.drawable.circle_green : R.drawable.circle_red);
            }
        }
        if (contextInfo != null) {
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                contextInfo.setText(contextActive ? getString(R.string.h_f6ebf8f5) : getString(R.string.h_d70e9bdf));
            }
            contextInfo.setTextColor(contextActive ? getResources().getColor(R.color.success) : getResources().getColor(R.color.error));
        }
    }
    
    private void updateFunctionStatus(boolean modelLoaded) {
        int green = R.drawable.circle_green;
        int red = R.drawable.circle_red;
        
        AIConfig aiConfig = new AIConfig(this);
        
        if (chatLight != null) {
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                chatLight.setBackgroundResource(modelLoaded ? green : red);
            }
        }
        if (qaLight != null) {
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                qaLight.setBackgroundResource(modelLoaded ? green : red);
            }
        }
        if (deepLight != null) {
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                deepLight.setBackgroundResource(modelLoaded ? green : red);
            }
        }
        if (creativeLight != null) {
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                creativeLight.setBackgroundResource(modelLoaded ? green : red);
            }
        }
        if (agentLight != null) {
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                agentLight.setBackgroundResource(modelLoaded && aiConfig.isAgentEnabled() ? green : red);
            }
        }
        if (summarizeLight != null) {
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                summarizeLight.setBackgroundResource(modelLoaded ? green : red);
            }
        }
        if (codeLight != null) {
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                codeLight.setBackgroundResource(modelLoaded ? green : red);
            }
        }
        if (analysisLight != null) {
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                analysisLight.setBackgroundResource(modelLoaded ? green : red);
            }
        }
    }

    private void updateContextStats() {
        // NPU 模式：模型架构/上下文/实时指标统一由 refreshEngineRows() 负责，
        // 这里早退避免两套逻辑互相覆盖（实测会出现"待加载"与"未加载"来回跳变）。2026-10-05
        if (com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
            return;
        }
        boolean contextActive = aiService.isChatContextActive();
        
        if (contextActive) {
            int total = aiService.getContextSize();
            int used = aiService.getContextUsedTokens();
            int remaining = aiService.getContextRemainingTokens();
            float percent = aiService.getContextUsagePercent();
            
            if (contextTotal != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    contextTotal.setText(String.valueOf(total));
                }
            }
            if (contextUsed != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    contextUsed.setText(String.valueOf(used));
                }
            }
            if (contextRemaining != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    contextRemaining.setText(String.valueOf(remaining));
                }
            }
            if (contextPercent != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    contextPercent.setText(String.format("%d%%", (int) percent));
                }
            }
            if (contextProgress != null) {
                contextProgress.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, percent / 100));
            }
        } else {
            if (contextTotal != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    contextTotal.setText("0");
                }
            }
            if (contextUsed != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    contextUsed.setText("0");
                }
            }
            if (contextRemaining != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    contextRemaining.setText("0");
                }
            }
            if (contextPercent != null) {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    contextPercent.setText("0%");
                }
            }
            if (contextProgress != null) {
                contextProgress.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0));
            }
        }
    }

    private void showTestAiDialog() {
        // 检查AI服务是否已初始化
        if (!aiService.isInitialized()) {
            Toast.makeText(this, getString(R.string.h_1c70ded4), Toast.LENGTH_SHORT).show();
            return;
        }

        // 创建模态框
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_test_ai, null);
        builder.setView(dialogView);
        android.app.AlertDialog dialog = builder.create();

        // 初始化UI组件
        android.widget.EditText etTestInput = dialogView.findViewById(R.id.et_test_input);
        android.widget.LinearLayout loadingContainer = dialogView.findViewById(R.id.loading_container);
        android.widget.ScrollView resultContainer = dialogView.findViewById(R.id.result_container);
        android.widget.TextView tvTestResult = dialogView.findViewById(R.id.tv_test_result);
        android.widget.TextView tvTestStatus = dialogView.findViewById(R.id.tv_test_status);
        MaterialButton btnCancel = dialogView.findViewById(R.id.btn_cancel);
        MaterialButton btnSend = dialogView.findViewById(R.id.btn_send);

        // 运行标记：对话框关闭时取消计时/打字机
        final java.util.concurrent.atomic.AtomicBoolean alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        final java.util.concurrent.atomic.AtomicLong startMs = new java.util.concurrent.atomic.AtomicLong(0);
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable timer = new Runnable() {
            @Override
            public void run() {
                if (!alive.get() || tvTestStatus == null) return;
                long sec = (System.currentTimeMillis() - startMs.get()) / 1000;
                tvTestStatus.setText("本地推理中 · " + sec + "s");
                ui.postDelayed(this, 1000);
            }
        };

        // 设置取消按钮点击事件
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> {
                alive.set(false);
                dialog.dismiss();
            });
        }
        dialog.setOnDismissListener(d -> alive.set(false));

        // 设置发送按钮点击事件
        if (btnSend != null) {
            btnSend.setOnClickListener(v -> {
                if (etTestInput != null) {
                    String inputText = etTestInput.getText().toString().trim();
                    if (inputText.isEmpty()) {
                        Toast.makeText(AIServiceStatusActivity.this, getString(R.string.h_dfb7ed74), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 显示加载动画 + 状态行（模型名 / 阶段 / 耗时）
                    if (loadingContainer != null) {
                        loadingContainer.setVisibility(android.view.View.VISIBLE);
                    }
                    if (resultContainer != null) {
                        resultContainer.setVisibility(android.view.View.GONE);
                    }
                    if (tvTestStatus != null) {
                        tvTestStatus.setVisibility(android.view.View.VISIBLE);
                        String model = aiService.getCurrentModelName();
                        tvTestStatus.setText("本地推理中 · " + (model == null || model.isEmpty() ? "本地模型" : model));
                    }
                    startMs.set(System.currentTimeMillis());
                    ui.removeCallbacks(timer);
                    ui.post(timer);

                    // 后台线程执行（本地 generate 为同步阻塞，避免主线程 ANR）
                    java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                        if (!alive.get()) return;
                        aiService.generate(inputText, new AIService.GenerateCallback() {
                            @Override
                            public void onSuccess(String response) {
                                ui.post(() -> {
                                    alive.set(false);
                                    ui.removeCallbacks(timer);
                                    if (loadingContainer != null) {
                                        loadingContainer.setVisibility(android.view.View.GONE);
                                    }
                                    if (resultContainer != null) {
                                        resultContainer.setVisibility(android.view.View.VISIBLE);
                                    }
                                    if (tvTestStatus != null && tvTestResult != null) {
                                        long sec = (System.currentTimeMillis() - startMs.get()) / 1000;
                                        String model = aiService.getCurrentModelName();
                                        tvTestStatus.setText("✓ 完成 · " + (model == null || model.isEmpty() ? "本地模型" : model)
                                                + " · " + response.length() + " 字符 · " + sec + "s");
                                    }
                                    if (tvTestResult != null) {
                                        // 打字机逐字渲染（每帧 3 字符），模拟流式输出感
                                        final String text = response;
                                        final int[] idx = {0};
                                        tvTestResult.setText("");
                                        final Runnable type = new Runnable() {
                                            @Override
                                            public void run() {
                                                idx[0] = Math.min(text.length(), idx[0] + 3);
                                                tvTestResult.setText(text.substring(0, idx[0]));
                                                if (idx[0] < text.length()) {
                                                    ui.postDelayed(this, 12);
                                                }
                                            }
                                        };
                                        ui.post(type);
                                    }
                                });
                            }

                            @Override
                            public void onError(Exception e) {
                                ui.post(() -> {
                                    alive.set(false);
                                    ui.removeCallbacks(timer);
                                    if (loadingContainer != null) {
                                        loadingContainer.setVisibility(android.view.View.GONE);
                                    }
                                    if (resultContainer != null) {
                                        resultContainer.setVisibility(android.view.View.VISIBLE);
                                    }
                                    if (tvTestStatus != null) {
                                        long sec = (System.currentTimeMillis() - startMs.get()) / 1000;
                                        tvTestStatus.setText("✖ 失败 · " + sec + "s");
                                    }
                                    if (tvTestResult != null) {
                                        String detail = e == null || e.getMessage() == null ? "未知错误" : e.getMessage();
                                        tvTestResult.setText("生成失败：" + detail
                                                + "\n\n可尝试：检查模型是否已加载、切换模型后重试、或查看服务状态页日志。");
                                    }
                                });
                            }
                        });
                    });
                }
            });
        }

        // 显示模态框
        dialog.show();
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SELECT_MODEL) {
            // 选择模型后刷新状态
            refreshStatus();
        }
    }

    @Override
    protected void onResume() {
        refreshEngineRows();   // 按实际引擎刷新（NPU/llama.cpp）
        super.onResume();
        startEngineAutoRefresh();
        // 每次返回此页面时刷新状态
        refreshStatus();
        // 启动实时指标定时刷新
        metricsHandler.postDelayed(metricsRunnable, METRICS_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        stopEngineAutoRefresh();
        super.onPause();
        // 停止定时刷新
        metricsHandler.removeCallbacks(metricsRunnable);
    }
    
    @Override
    public void onStatusChanged(boolean isInitialized, String modelName) {
        // 在UI线程更新状态
        runOnUiThread(() -> {
            boolean modelLoaded = isInitialized && LlamaHelper.isModelInitialized();
            
            // 刷新主状态
            updateMainStatus(modelLoaded, isInitialized);
            
            // 刷新模型状态
            updateModelStatus(modelName, modelLoaded);
            
            // 刷新模型架构信息
            updateModelArchitectureInfo();
            
            // 刷新推理库状态
            updateLibraryStatus();
            
            // 刷新上下文状态
            updateContextStatus();
            
            // 刷新OpenCL状态
            updateOpenCLStatus();
            
            // 刷新功能状态
            updateFunctionStatus(modelLoaded);
            
            // 刷新上下文统计
            updateContextStats();
        });
    }

    private void updateModelArchitectureInfo() {
        // NPU 模式：模型架构/上下文/实时指标统一由 refreshEngineRows() 负责，
        // 这里早退避免两套逻辑互相覆盖（实测会出现"待加载"与"未加载"来回跳变）。2026-10-05
        if (com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
            return;
        }
        if (modelParams == null) return;

        try {
            LlamaHelper.ModelMeta meta = LlamaHelper.getModelMeta();
            boolean modelOk = LlamaHelper.isModelInitialized();

            if (modelOk && meta != null && meta.valid) {
                // 格式化参数量（7B/1.8B 等）
                String paramsStr = formatParams(meta.nParams);
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelParams.setText(paramsStr);
                }
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelLayers.setText(meta.nLayer > 0 ? String.valueOf(meta.nLayer) : "-");
                }
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelHeads.setText(meta.nHead > 0 ? String.valueOf(meta.nHead) : "-");
                }
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelEmbd.setText(meta.nEmbd > 0 ? String.valueOf(meta.nEmbd) : "-");
                }
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelCtxTrain.setText(meta.nCtxTrain > 0 ? String.valueOf(meta.nCtxTrain) : "-");
                }

                // 实时数据
                float memoryMB = LlamaHelper.getMemoryUsage();
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelMemory.setText(memoryMB > 0 ? String.format("%.0f", memoryMB) : "-");
                }
                float speed = LlamaHelper.getInferenceSpeed();
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelSpeed.setText(speed > 0 ? String.format("%.1f t/s", speed) : "-");
                }
                int tokens = LlamaHelper.getTokenCount();
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelTokens.setText(tokens > 0 ? String.valueOf(tokens) : "-");
                }

                // 模型文件名
                String modelName = aiService.getCurrentModelName();
                if (modelName != null && !modelName.isEmpty()) {
                    if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                        modelFilename.setText(modelName);
                    }
                } else if (meta.modelName != null && !meta.modelName.isEmpty()) {
                    if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                        modelFilename.setText(meta.modelName);
                    }
                } else {
                    if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                        modelFilename.setText("-");
                    }
                }

                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelParamsInfo.setText(paramsStr + " / " + meta.nLayer + getString(R.string.h_13933709) + meta.nCtxTrain);
                }
            } else {
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelParams.setText("-");
                }
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelLayers.setText("-");
                }
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelHeads.setText("-");
                }
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelEmbd.setText("-");
                }
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelCtxTrain.setText("-");
                }
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelMemory.setText("-");
                }
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelSpeed.setText("-");
                }
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelTokens.setText("-");
                }
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelFilename.setText("-");
                }
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelParamsInfo.setText(getString(R.string.h_467b3e03));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "updateModelArchitectureInfo failed: " + e.getMessage());
        }
    }

    /** 定时刷新实时指标：推理速度、Token计数、内存占用
     *  数据源自动切换：在线模式使用 API 累计统计；本地模式使用 native 统计 */
    private void updateRealtimeMetrics() {
        // NPU 模式：模型架构/上下文/实时指标统一由 refreshEngineRows() 负责，
        // 这里早退避免两套逻辑互相覆盖（实测会出现"待加载"与"未加载"来回跳变）。2026-10-05
        if (com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
            return;
        }
        if (modelSpeed == null) return;
        try {
            boolean useOnline = inferenceRouter != null && inferenceRouter.isUsingOnlineModel();
            if (useOnline) {
                // 在线模式：使用 TokenStatsManager 累计的 API 统计
                TokenStatsManager.TokenStats stats = TokenStatsManager.getInstance().getCurrentSnapshot();
                int sessionTotal = (stats != null) ? stats.sessionTotalTokens : 0;
                int sessionCompletion = (stats != null) ? stats.sessionCompletionTokens : 0;
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelSpeed.setText(sessionCompletion > 0
                        ? String.format(getString(R.string.h_cffe3407), sessionCompletion) : "API");
                }
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelTokens.setText(sessionTotal > 0 ? String.valueOf(sessionTotal) : "-");
                }
                if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                    modelMemory.setText("-");
                } // 在线模式不占用本地内存
                return;
            }

            // 本地模式：使用 native 层统计
            if (!LlamaHelper.isModelInitialized()) return;
            float speed = LlamaHelper.getInferenceSpeed();
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                modelSpeed.setText(speed > 0 ? String.format("%.1f t/s", speed) : "-");
            }

            int tokens = LlamaHelper.getTokenCount();
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                modelTokens.setText(tokens > 0 ? String.valueOf(tokens) : "-");
            }

            float memoryMB = LlamaHelper.getMemoryUsage();
            if (!com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                modelMemory.setText(memoryMB > 0 ? String.format("%.0f", memoryMB) : "-");
            }
        } catch (Exception e) {
            Log.w(TAG, "updateRealtimeMetrics failed: " + e.getMessage());
        }
    }

    private static String formatParams(long nParams) {
        if (nParams <= 0) return "-";
        if (nParams >= 1_000_000_000L) {
            double b = nParams / 1_000_000_000.0;
            return String.format("%.1fB", b);
        } else if (nParams >= 1_000_000L) {
            double m = nParams / 1_000_000.0;
            return String.format("%.0fM", m);
        } else if (nParams >= 1_000L) {
            double k = nParams / 1_000.0;
            return String.format("%.0fK", k);
        }
        return String.valueOf(nParams);
    }

    /**
     * 按**实际引擎**刷新页面：NPU 引擎开启时整页显示 NPU 信息，
     * 而不是布局里写死的 "Llama CPP"（原先引擎名是 XML 静态文案，切了引擎也不变）。
     */
    private void refreshEngineRows() {
        try {
            boolean npu = com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled();
            android.widget.TextView engine = findViewById(R.id.engine_info);
            android.widget.TextView lib = findViewById(R.id.lib_status);
            android.widget.TextView model = findViewById(R.id.model_name);
            android.widget.TextView ctx = findViewById(R.id.context_info);
            android.widget.TextView opencl = findViewById(R.id.opencl_status);
            android.widget.TextView gpu = findViewById(R.id.gpu_status);
            android.widget.TextView st = findViewById(R.id.status_text);
            android.widget.TextView hint = findViewById(R.id.status_hint);
            if (!npu) {
                // 关闭 NPU：恢复 llama.cpp 视图，并重新启用 GPU 设置控件
                setGpuControlsEnabled(true);
                return;
            }
            // NPU 模式下 GPU 层数 / GPU 后端无意义（NPU 引擎不读这些参数）→ 禁用，避免误操作
            setGpuControlsEnabled(false);

            boolean loaded = com.oilquiz.app.ai.engine.NpuLlmChat.isLoaded();
            java.io.File mf = bestModelFile();
            String scanned = (mf != null) ? (mf.getName() + "\uff08App \u6a21\u578b\u5e93\uff09") : null;
            boolean hasModel = scanned != null;
            android.util.Log.i("AIServiceStatus", "refreshEngineRows(NPU): hasModel=" + hasModel
                    + ", loaded=" + loaded);

            if (engine != null) engine.setText("NPU\uff08GenieX \u00b7 Hexagon HTP\uff09");
            if (lib != null) lib.setText("GenieX SDK\uff08libggml-hexagon + htp skel\uff09");
            if (opencl != null) opencl.setText("\u4e0d\u9002\u7528\uff08NPU \u6a21\u5f0f\uff09");
            if (gpu != null) gpu.setText("\u4e0d\u9002\u7528\uff08NPU \u6a21\u5f0f\uff09");
            if (st != null) {
                st.setText(loaded
                        ? "NPU \u5f15\u64ce\u8fd0\u884c\u4e2d"
                        : "NPU \u5f15\u64ce\u5df2\u542f\u7528\uff08\u6a21\u578b\u5f85\u52a0\u8f7d\uff09");
            }
            if (hint != null) {
                hint.setText(loaded
                        ? "\u6a21\u578b\u5df2\u52a0\u8f7d\u5230 Hexagon NPU\uff08HTP0\uff09"
                        : "\u6a21\u578b\u6309\u9700\u52a0\u8f7d\uff1a\u9996\u6b21\u53d1\u9001\u6d88\u606f\u65f6\u8f7d\u5165\uff0c\u52a0\u8f7d\u5b8c\u6210\u540e\u6b64\u5904\u81ea\u52a8\u663e\u793a\u771f\u5b9e\u4fe1\u606f");
            }

            if (loaded) {
                // \u52a0\u8f7d\u5b8c\u6210\u540e\u624d\u663e\u793a\u771f\u5b9e\u503c
                String name = com.oilquiz.app.ai.engine.NpuLlmChat.currentOrPreferredModelName();
                if (model != null) {
                    model.setText((name == null || name.isEmpty()) ? (hasModel ? scanned : "\u2014") : name);
                }
                if (ctx != null) {
                    long sz = npuModelSizeBytes();
                    ctx.setText("NPU \u5f15\u64ce\uff08HTP0\uff09"
                            + (sz > 0 ? " \u00b7 \u6743\u91cd "
                            + String.format(java.util.Locale.US, "%.2f", sz / 1024.0 / 1024 / 1024) + " GB" : ""));
                }
                // NPU-STATS-FILLED: 加载完成后显示 NPU 运行统计（NpuLlmChat 暴露的最近一次生成数据）
                float nTps = com.oilquiz.app.ai.engine.NpuLlmChat.getLastTps();
                int nTok = com.oilquiz.app.ai.engine.NpuLlmChat.getLastTokens();
                if (modelSpeed != null) modelSpeed.setText(nTps > 0 ? String.format(java.util.Locale.US, "%.1f t/s", nTps) : "-");
                if (modelTokens != null) modelTokens.setText(nTok > 0 ? String.valueOf(nTok) : "-");
                // 用内存预算规划出的真实 nCtx（原来写死 4096，与引擎实际值不一致）
                int nCtx = com.oilquiz.app.ai.engine.NpuLlmChat.plannedNCtxValue();
                if (nCtx <= 0) nCtx = 8192;
                int nUsed = Math.max(0, Math.min(nTok, nCtx));
                if (contextTotal != null) contextTotal.setText(String.valueOf(nCtx));
                if (contextUsed != null) contextUsed.setText(String.valueOf(nUsed));
                if (contextRemaining != null) contextRemaining.setText(String.valueOf(nCtx - nUsed));
                if (contextPercent != null) contextPercent.setText((nUsed * 100 / nCtx) + "%");

                refreshModelArch();
            } else {
                // \u672a\u52a0\u8f7d\uff1a\u53ea\u7ed9\u5360\u4f4d\uff0c\u907f\u514d\u8bef\u62a5\u4e5f\u907f\u514d\u7a7a\u6307\u9488
                if (model != null) {
                    model.setText(hasModel ? (mf.getName() + "\uff08\u5f85\u52a0\u8f7d\uff09")
                            : "\u672a\u4e0b\u8f7d\u6a21\u578b\uff08\u8bf7\u5230\u6a21\u578b\u4e0b\u8f7d\u9875\u9009 Q4_0\uff09");
                }
                if (ctx != null) ctx.setText("\u5f85\u52a0\u8f7d\uff08\u9996\u6b21\u53d1\u9001\u6d88\u606f\u65f6\u8f7d\u5165\uff09");
                if (modelParams != null) modelParams.setText("\u2014");
                if (modelLayers != null) modelLayers.setText("\u2014");
                if (modelHeads != null) modelHeads.setText("\u2014");
                if (modelParamsInfo != null) modelParamsInfo.setText("\u5f85\u52a0\u8f7d");
            }

            if (statusLight != null) statusLight.setBackgroundResource(R.drawable.circle_green);
            if (libLight != null) libLight.setBackgroundResource(R.drawable.circle_green);
            if (modelLight != null) {
                modelLight.setBackgroundResource(loaded ? R.drawable.circle_green
                        : (hasModel ? R.drawable.circle_yellow : R.drawable.circle_red));
            }
            if (contextLight != null) contextLight.setBackgroundResource(R.drawable.circle_green);
            if (openclLight != null) openclLight.setBackgroundResource(R.drawable.status_dot_gray);
            if (gpuLight != null) gpuLight.setBackgroundResource(R.drawable.status_dot_gray);
            if (st != null) st.setText(loaded ? "NPU 引擎运行中" : "NPU 引擎已启用");
            if (model != null && !hasModel) {
                model.setText("未下载模型（请到模型下载页选 Q4_0）");
            }
        } catch (Throwable ignored) {
            // 静默：刷新失败不影响页面
        }
    }

    /**
     * GPU 相关控件（GPU 层数输入 / 应用并重载 / GPU 后端单选）启用状态。
     *
     * <p>NPU 引擎模式下这些参数不生效（NPU 走 GenieX + HTP，不读 nGpuLayers/后端选择），
     * 继续可选会误导用户 → 置灰禁用；关闭 NPU 时恢复（2026-10-05）。
     */
    private void setGpuControlsEnabled(boolean enabled) {
        try {
            float alpha = enabled ? 1.0f : 0.45f;
            android.view.View layers = findViewById(R.id.gpu_layers_input);
            if (layers != null) {
                layers.setEnabled(enabled);
                layers.setAlpha(alpha);
            }
            android.view.View apply = findViewById(R.id.btn_apply_gpu_layers);
            if (apply != null) {
                apply.setEnabled(enabled);
                apply.setAlpha(alpha);
            }
            android.view.View group = findViewById(R.id.gpu_backend_group);
            if (group != null) {
                group.setEnabled(enabled);
                group.setAlpha(alpha);
                if (group instanceof android.view.ViewGroup) {
                    android.view.ViewGroup vg = (android.view.ViewGroup) group;
                    for (int i = 0; i < vg.getChildCount(); i++) {
                        android.view.View child = vg.getChildAt(i);
                        child.setEnabled(enabled);
                        child.setAlpha(alpha);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 扫 App 模型库里的 gguf（Q4_0 优先，其次体积最大），返回展示名；没有返回 null */
    private String scanNpuModelFile() {
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "ai_models");
            java.io.File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".gguf"));
            if (files == null || files.length == 0) {
                return null;
            }
            java.io.File best = null;
            for (java.io.File f : files) {
                if (best == null) {
                    best = f;
                    continue;
                }
                boolean fq4 = f.getName().toLowerCase().contains("q4_0");
                boolean bq4 = best.getName().toLowerCase().contains("q4_0");
                if (fq4 != bq4) {
                    if (fq4) best = f;
                } else if (f.length() > best.length()) {
                    best = f;
                }
            }
            return best.getName() + "（App 模型库）";
        } catch (Throwable t) {
            return null;
        }
    }

    /** NPU 将使用的模型文件大小（字节）；找不到返回 0 */
    private long npuModelSizeBytes() {
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "ai_models");
            java.io.File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".gguf"));
            if (files == null || files.length == 0) {
                return 0;
            }
            long best = 0;
            for (java.io.File f : files) {
                if (f.length() > best) best = f.length();
            }
            return best;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** App 模型库里最合适的 gguf（Q4_0 优先，其次体积最大）；没有返回 null */
    private java.io.File bestModelFile() {
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "ai_models");
            java.io.File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".gguf"));
            if (files == null || files.length == 0) {
                return null;
            }
            java.io.File best = null;
            for (java.io.File f : files) {
                if (best == null) {
                    best = f;
                    continue;
                }
                boolean fq4 = f.getName().toLowerCase().contains("q4_0");
                boolean bq4 = best.getName().toLowerCase().contains("q4_0");
                if (fq4 != bq4) {
                    if (fq4) best = f;
                } else if (f.length() > best.length()) {
                    best = f;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 模型架构信息：**从磁盘上的 gguf 头部解析**（参数量/层数/注意力头/架构/隐藏维度）。
     *
     * <p>原先这块只在"本地 llama.cpp 模型已加载"时才有值，NPU 模式永远显示"未加载"；
     * 现在两种模式都能显示真实架构信息（数据来源与是否加载无关）。
     */
    private void refreshModelArch() {
        try {
            java.io.File f = bestModelFile();
            if (f == null) {
                return;
            }
            com.oilquiz.app.ai.model.GgufMeta meta = com.oilquiz.app.ai.model.GgufMeta.read(f);
            if (meta == null) {
                return;
            }
            if (modelParams != null) modelParams.setText(meta.parameterText());
            if (modelLayers != null) modelLayers.setText(meta.blockCount > 0 ? String.valueOf(meta.blockCount) : "—");
            if (modelHeads != null) modelHeads.setText(meta.headText());
            if (modelParamsInfo != null) {
                String arch = meta.architecture == null || meta.architecture.isEmpty()
                        ? "GGUF" : meta.architecture.toUpperCase(java.util.Locale.US);
                String hidden = meta.embeddingLength > 0 ? " · hidden " + meta.embeddingLength : "";
                modelParamsInfo.setText(arch + hidden + " · " + f.getName());
            // NPU-ARCH-FILLED: 以下字段在 NPU 模式下由 gguf 头部提供（原有逻辑已被门禁跳过）
            if (modelEmbd != null) modelEmbd.setText(meta.embeddingLength > 0 ? String.valueOf(meta.embeddingLength) : "-");
            if (modelCtxTrain != null) modelCtxTrain.setText(meta.contextLength > 0 ? String.valueOf(meta.contextLength) : "-");
            if (modelMemory != null) modelMemory.setText(String.valueOf(f.length() / 1024 / 1024));
            if (modelFilename != null) modelFilename.setText(f.getName());
            }
        } catch (Throwable ignored) {
            // 静默：解析失败保留原显示
        }
    }

    /** NPU 模型加载是"首次发送时"触发的，页面需要自动刷新才能显示加载后的真实信息 */
    private final android.os.Handler engineRefreshHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable engineRefreshTask = new Runnable() {
        @Override
        public void run() {
            try {
                refreshEngineRows();
            } catch (Throwable ignored) {
            }
            engineRefreshHandler.postDelayed(this, 1500);
        }
    };

    private void startEngineAutoRefresh() {
        engineRefreshHandler.removeCallbacks(engineRefreshTask);
        engineRefreshHandler.post(engineRefreshTask);
    }

    private void stopEngineAutoRefresh() {
        engineRefreshHandler.removeCallbacks(engineRefreshTask);
    }

    /** 安全设置某个字段文案（view 不存在时静默，避免空指针） */
    private void setRowText(int viewId, String value) {
        try {
            android.widget.TextView tv = findViewById(viewId);
            if (tv != null) {
                tv.setText(value);
            }
        } catch (Throwable ignored) {
        }
    }
}
