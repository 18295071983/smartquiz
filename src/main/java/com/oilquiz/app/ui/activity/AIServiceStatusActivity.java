package com.oilquiz.app.ui.activity;

import com.oilquiz.app.theme.ThemeColors;

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
                Toast.makeText(this, "正在初始化AI服务...", Toast.LENGTH_SHORT).show();

                new Thread(() -> {
                    boolean success = aiService.initializeSafe();

                    runOnUiThread(() -> {
                        if (success) {
                            Toast.makeText(this, "AI服务初始化成功", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "AI服务初始化失败，请先导入模型", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "AI功能已" + (isChecked ? "启用" : "禁用"), Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(AIServiceStatusActivity.this, "优化模式: " + selected.displayName + "，重启AI对话后生效", Toast.LENGTH_SHORT).show();
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
                        agentLight.setBackgroundResource(isChecked ? R.drawable.circle_green : R.drawable.circle_red);
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
                        : "已回退旧推理接口（重启AI对话后生效）", Toast.LENGTH_SHORT).show();
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
                        : "本地Agent已禁用（新消息即时生效）", Toast.LENGTH_SHORT).show();
            });
        }

        // FC 开关已合并到本地 Agent，隐藏独立开关
        if (localFcSwitch != null) {
            localFcSwitch.setVisibility(View.GONE);
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
            Toast.makeText(this, "已恢复自动 GPU 层数", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "请输入 0-36 的整数或“自动”", Toast.LENGTH_SHORT).show();
            return;
        }
        if (target < 0 || target > 36) {
            Toast.makeText(this, "GPU 层数范围 0-36（0=纯CPU，36=全量GPU）", Toast.LENGTH_SHORT).show();
            return;
        }
        int current = 0;
        try { current = LlamaHelper.getGPULayers(); } catch (Exception ignored) {}
        boolean modelLoaded = LlamaHelper.isModelInitialized();

        if (modelLoaded && target == current) {
            Toast.makeText(this, "GPU 层数未变化（当前 " + current + "）", Toast.LENGTH_SHORT).show();
            return;
        }

        String msg = modelLoaded
                ? "GPU 层数将从 " + current + " 改为 " + target + "，需重新加载模型（数十秒）。继续？"
                : "GPU 层数将设为 " + target + "（模型未加载，下次加载时生效）。";
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("GPU 层数设置")
                .setMessage(msg)
                .setPositiveButton(modelLoaded ? "重载模型" : "确定", (d, w) -> {
                    // 持久化保存手动值（独立 key，加载时优先于自动计算；"恢复自动"删除此 key）
                    getSharedPreferences("model_state_cache", MODE_PRIVATE)
                            .edit().putInt("gpu_layers_manual", target).apply();
                    LlamaHelper.setGPULayers(target);
                    Toast.makeText(this, "GPU 层数已设为 " + target, Toast.LENGTH_SHORT).show();

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
                .setNeutralButton("恢复自动", (d, w) -> {
                    // 删除手动值，恢复自动计算（按设备/模型/内存）
                    getSharedPreferences("model_state_cache", MODE_PRIVATE)
                            .edit().remove("gpu_layers_manual").apply();
                    Toast.makeText(this, "已恢复自动 GPU 层数", Toast.LENGTH_SHORT).show();
                    if (modelLoaded && aiService != null) {
                        aiService.reloadModelAsync(null);
                    }
                    refreshStatus();
                })
                .setNegativeButton("取消", null)
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
                    Toast.makeText(AIServiceStatusActivity.this, "最大Token数: " + selected, Toast.LENGTH_SHORT).show();
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
                onlineApiLight.setBackgroundResource(R.drawable.circle_green);
            }
            if (onlineApiStatus != null) {
                onlineApiStatus.setText("已连接");
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
                onlineApiLight.setBackgroundResource(R.drawable.circle_red);
            }
            if (onlineApiStatus != null) {
                onlineApiStatus.setText("未配置");
                onlineApiStatus.setTextColor(getResources().getColor(R.color.error));
            }
            if (onlineModelInfo != null) {
                onlineModelInfo.setText("请在 AI 中心配置");
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
                gpuLayersInput.setText(isGpuLayersManual() ? String.valueOf(gpuLayers) : "自动");
            }
            
            if (openclLight != null) {
                openclLight.setBackgroundResource(openclLoaded ? R.drawable.circle_green : R.drawable.circle_red);
            }
            if (openclStatus != null) {
                openclStatus.setText(openclLoaded ? "已加载" : "未加载");
                openclStatus.setTextColor(openclLoaded ? getResources().getColor(R.color.success) : getResources().getColor(R.color.error));
            }
            
            if (gpuLight != null) {
                gpuLight.setBackgroundResource(gpuWorking ? R.drawable.circle_green : (openclLoaded ? R.drawable.circle_yellow : R.drawable.circle_red));
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
                
                gpuStatus.setText(gpuMode);
                gpuStatus.setTextColor(gpuColor);
            }
        } else {
            if (openclLight != null) {
                openclLight.setBackgroundResource(R.drawable.circle_red);
            }
            if (openclStatus != null) {
                openclStatus.setText("未加载");
                openclStatus.setTextColor(getResources().getColor(R.color.error));
            }
            
            if (gpuLight != null) {
                gpuLight.setBackgroundResource(R.drawable.circle_red);
            }
            if (gpuStatus != null) {
                gpuStatus.setText("未启用");
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
                statusLight.setBackgroundResource(R.drawable.circle_green);
            } else if (isInitialized) {
                statusLight.setBackgroundResource(R.drawable.circle_yellow);
            } else {
                statusLight.setBackgroundResource(R.drawable.circle_red);
            }
        }
        if (statusText != null) {
            if (modelLoaded) {
                statusText.setText("运行中");
                statusText.setTextColor(successColor);
            } else if (isInitialized) {
                statusText.setText("初始化中...");
                statusText.setTextColor(warningColor);
            } else {
                statusText.setText("未运行");
                statusText.setTextColor(errorColor);
            }
        }
        
        if (statusHint != null) {
            if (modelLoaded) {
                statusHint.setText("AI服务已就绪，所有功能可用");
                statusHint.setTextColor(successColor);
            } else if (isInitialized) {
                statusHint.setText("正在加载模型，请稍候...");
                statusHint.setTextColor(warningColor);
            } else {
                statusHint.setText("点击下方按钮启动AI服务");
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
            engineInfo.setText(engineDetail);
        }
    }

    private void updateModelStatus(String currentModel, boolean modelLoaded) {
        if (modelLight != null) {
            if (modelLoaded) {
                modelLight.setBackgroundResource(R.drawable.circle_green);
            } else if (currentModel != null) {
                modelLight.setBackgroundResource(R.drawable.circle_yellow);
            } else {
                modelLight.setBackgroundResource(R.drawable.circle_red);
            }
        }
        if (modelName != null) {
            modelName.setText(currentModel != null ? currentModel : "未选择");
        }
    }

    private void updateLibraryStatus() {
        boolean isLibraryLoaded = LlamaHelper.isLibraryLoaded();
        
        if (libLight != null) {
            libLight.setBackgroundResource(isLibraryLoaded ? R.drawable.circle_green : R.drawable.circle_red);
        }
        if (libStatus != null) {
            libStatus.setText(isLibraryLoaded ? "已加载" : "未加载");
            libStatus.setTextColor(isLibraryLoaded ? getResources().getColor(R.color.success) : getResources().getColor(R.color.error));
        }
    }
    
    private void updateContextStatus() {
        boolean contextActive = aiService.isChatContextActive();
        
        if (contextLight != null) {
            contextLight.setBackgroundResource(contextActive ? R.drawable.circle_green : R.drawable.circle_red);
        }
        if (contextInfo != null) {
            contextInfo.setText(contextActive ? "已激活" : "未激活");
            contextInfo.setTextColor(contextActive ? getResources().getColor(R.color.success) : getResources().getColor(R.color.error));
        }
    }
    
    private void updateFunctionStatus(boolean modelLoaded) {
        int green = R.drawable.circle_green;
        int red = R.drawable.circle_red;
        
        AIConfig aiConfig = new AIConfig(this);
        
        if (chatLight != null) {
            chatLight.setBackgroundResource(modelLoaded ? green : red);
        }
        if (qaLight != null) {
            qaLight.setBackgroundResource(modelLoaded ? green : red);
        }
        if (deepLight != null) {
            deepLight.setBackgroundResource(modelLoaded ? green : red);
        }
        if (creativeLight != null) {
            creativeLight.setBackgroundResource(modelLoaded ? green : red);
        }
        if (agentLight != null) {
            agentLight.setBackgroundResource(modelLoaded && aiConfig.isAgentEnabled() ? green : red);
        }
        if (summarizeLight != null) {
            summarizeLight.setBackgroundResource(modelLoaded ? green : red);
        }
        if (codeLight != null) {
            codeLight.setBackgroundResource(modelLoaded ? green : red);
        }
        if (analysisLight != null) {
            analysisLight.setBackgroundResource(modelLoaded ? green : red);
        }
    }

    private void updateContextStats() {
        boolean contextActive = aiService.isChatContextActive();
        
        if (contextActive) {
            int total = aiService.getContextSize();
            int used = aiService.getContextUsedTokens();
            int remaining = aiService.getContextRemainingTokens();
            float percent = aiService.getContextUsagePercent();
            
            if (contextTotal != null) {
                contextTotal.setText(String.valueOf(total));
            }
            if (contextUsed != null) {
                contextUsed.setText(String.valueOf(used));
            }
            if (contextRemaining != null) {
                contextRemaining.setText(String.valueOf(remaining));
            }
            if (contextPercent != null) {
                contextPercent.setText(String.format("%d%%", (int) percent));
            }
            if (contextProgress != null) {
                contextProgress.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, percent / 100));
            }
        } else {
            if (contextTotal != null) {
                contextTotal.setText("0");
            }
            if (contextUsed != null) {
                contextUsed.setText("0");
            }
            if (contextRemaining != null) {
                contextRemaining.setText("0");
            }
            if (contextPercent != null) {
                contextPercent.setText("0%");
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
            Toast.makeText(this, "AI服务未初始化，请先初始化AI服务", Toast.LENGTH_SHORT).show();
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
        MaterialButton btnCancel = dialogView.findViewById(R.id.btn_cancel);
        MaterialButton btnSend = dialogView.findViewById(R.id.btn_send);

        // 设置取消按钮点击事件
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        // 设置发送按钮点击事件
        if (btnSend != null) {
            btnSend.setOnClickListener(v -> {
                if (etTestInput != null) {
                    String inputText = etTestInput.getText().toString().trim();
                    if (inputText.isEmpty()) {
                        Toast.makeText(AIServiceStatusActivity.this, "请输入测试文本", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 显示加载动画
                    if (loadingContainer != null) {
                        loadingContainer.setVisibility(android.view.View.VISIBLE);
                    }
                    if (resultContainer != null) {
                        resultContainer.setVisibility(android.view.View.GONE);
                    }

                    // 调用AI服务生成回答
                    aiService.generate(inputText, new AIService.GenerateCallback() {
                        @Override
                        public void onSuccess(String response) {
                            runOnUiThread(() -> {
                                // 隐藏加载动画，显示结果
                                if (loadingContainer != null) {
                                    loadingContainer.setVisibility(android.view.View.GONE);
                                }
                                if (resultContainer != null) {
                                    resultContainer.setVisibility(android.view.View.VISIBLE);
                                }
                                if (tvTestResult != null) {
                                    tvTestResult.setText(response);
                                }
                            });
                        }

                        @Override
                        public void onError(Exception e) {
                            runOnUiThread(() -> {
                                // 隐藏加载动画，显示错误信息
                                if (loadingContainer != null) {
                                    loadingContainer.setVisibility(android.view.View.GONE);
                                }
                                if (resultContainer != null) {
                                    resultContainer.setVisibility(android.view.View.VISIBLE);
                                }
                                if (tvTestResult != null) {
                                    tvTestResult.setText("生成失败: " + e.getMessage());
                                }
                            });
                        }
                    });
                }
            });
        }

        // 显示模态框
        dialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SELECT_MODEL) {
            // 选择模型后刷新状态
            refreshStatus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次返回此页面时刷新状态
        refreshStatus();
        // 启动实时指标定时刷新
        metricsHandler.postDelayed(metricsRunnable, METRICS_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
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
        if (modelParams == null) return;

        try {
            LlamaHelper.ModelMeta meta = LlamaHelper.getModelMeta();
            boolean modelOk = LlamaHelper.isModelInitialized();

            if (modelOk && meta != null && meta.valid) {
                // 格式化参数量（7B/1.8B 等）
                String paramsStr = formatParams(meta.nParams);
                modelParams.setText(paramsStr);
                modelLayers.setText(meta.nLayer > 0 ? String.valueOf(meta.nLayer) : "-");
                modelHeads.setText(meta.nHead > 0 ? String.valueOf(meta.nHead) : "-");
                modelEmbd.setText(meta.nEmbd > 0 ? String.valueOf(meta.nEmbd) : "-");
                modelCtxTrain.setText(meta.nCtxTrain > 0 ? String.valueOf(meta.nCtxTrain) : "-");

                // 实时数据
                float memoryMB = LlamaHelper.getMemoryUsage();
                modelMemory.setText(memoryMB > 0 ? String.format("%.0f", memoryMB) : "-");
                float speed = LlamaHelper.getInferenceSpeed();
                modelSpeed.setText(speed > 0 ? String.format("%.1f t/s", speed) : "-");
                int tokens = LlamaHelper.getTokenCount();
                modelTokens.setText(tokens > 0 ? String.valueOf(tokens) : "-");

                // 模型文件名
                String modelName = aiService.getCurrentModelName();
                if (modelName != null && !modelName.isEmpty()) {
                    modelFilename.setText(modelName);
                } else if (meta.modelName != null && !meta.modelName.isEmpty()) {
                    modelFilename.setText(meta.modelName);
                } else {
                    modelFilename.setText("-");
                }

                modelParamsInfo.setText(paramsStr + " / " + meta.nLayer + "层 / ctx:" + meta.nCtxTrain);
            } else {
                modelParams.setText("-");
                modelLayers.setText("-");
                modelHeads.setText("-");
                modelEmbd.setText("-");
                modelCtxTrain.setText("-");
                modelMemory.setText("-");
                modelSpeed.setText("-");
                modelTokens.setText("-");
                modelFilename.setText("-");
                modelParamsInfo.setText("未加载");
            }
        } catch (Exception e) {
            Log.w(TAG, "updateModelArchitectureInfo failed: " + e.getMessage());
        }
    }

    /** 定时刷新实时指标：推理速度、Token计数、内存占用
     *  数据源自动切换：在线模式使用 API 累计统计；本地模式使用 native 统计 */
    private void updateRealtimeMetrics() {
        if (modelSpeed == null) return;
        try {
            boolean useOnline = inferenceRouter != null && inferenceRouter.isUsingOnlineModel();
            if (useOnline) {
                // 在线模式：使用 TokenStatsManager 累计的 API 统计
                TokenStatsManager.TokenStats stats = TokenStatsManager.getInstance().getCurrentSnapshot();
                int sessionTotal = (stats != null) ? stats.sessionTotalTokens : 0;
                int sessionCompletion = (stats != null) ? stats.sessionCompletionTokens : 0;
                modelSpeed.setText(sessionCompletion > 0
                        ? String.format("API · 累计 %d", sessionCompletion) : "API");
                modelTokens.setText(sessionTotal > 0 ? String.valueOf(sessionTotal) : "-");
                modelMemory.setText("-"); // 在线模式不占用本地内存
                return;
            }

            // 本地模式：使用 native 层统计
            if (!LlamaHelper.isModelInitialized()) return;
            float speed = LlamaHelper.getInferenceSpeed();
            modelSpeed.setText(speed > 0 ? String.format("%.1f t/s", speed) : "-");

            int tokens = LlamaHelper.getTokenCount();
            modelTokens.setText(tokens > 0 ? String.valueOf(tokens) : "-");

            float memoryMB = LlamaHelper.getMemoryUsage();
            modelMemory.setText(memoryMB > 0 ? String.format("%.0f", memoryMB) : "-");
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
}
