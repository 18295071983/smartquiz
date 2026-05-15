package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
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
import com.oilquiz.app.ai.model.ModelManager;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.jni.LlamaHelper;

import java.util.Arrays;
import java.util.List;

public class AIServiceStatusActivity extends AppCompatActivity implements AIService.StatusObserver {

    private static final String TAG = "AIServiceStatusActivity";
    private static final int REQUEST_SELECT_MODEL = 1003;

    private AIService aiService;
    private ModelManager modelManager;

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
    
    private MaterialButton btnSelectModel;
    private MaterialButton btnTestAi;
    private MaterialButton btnDeviceInfo;
    private SwitchMaterial aiEnableSwitch;
    private SwitchMaterial optimizationSwitch;
    private AppCompatSpinner tokenSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_service_status);

        // 初始化服务
        aiService = AIService.getInstance(this);
        modelManager = new ModelManager(this);

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

        // 按钮
        btnSelectModel = findViewById(R.id.btn_select_model);

        // 配置选项
        tokenSpinner = findViewById(R.id.token_spinner);
        aiEnableSwitch = findViewById(R.id.ai_enable_switch);
        optimizationSwitch = findViewById(R.id.optimization_switch);

        // 初始化按钮
        MaterialButton btnInitializeModel = findViewById(R.id.btn_initialize_model);
        if (btnInitializeModel != null) {
            btnInitializeModel.setOnClickListener(v -> {
                // 手动初始化AI服务
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

        if (optimizationSwitch != null) {
            // 先设置当前状态
            optimizationSwitch.setChecked(aiService.isOptimizationEnabled());
            // 设置监听
            optimizationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                aiService.setOptimizationEnabled(isChecked);
                Toast.makeText(this, "优化设置已" + (isChecked ? "启用" : "禁用"), Toast.LENGTH_SHORT).show();
            });
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
    }

    private void initTokenSpinner() {
        if (tokenSpinner != null) {
            List<String> tokenOptions = Arrays.asList("128", "256", "512", "1024", "2048");
            android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, tokenOptions);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            tokenSpinner.setAdapter(adapter);
            tokenSpinner.setSelection(1); // 默认选择256
        }
    }

    private void refreshStatus() {
        // 刷新服务状态
        boolean isInitialized = aiService.isInitialized();
        boolean modelLoaded = isInitialized && LlamaHelper.isModelInitialized();
        
        updateMainStatus(modelLoaded, isInitialized);
        updateLibraryStatus();
        updateModelStatus(aiService.getCurrentModelName(), modelLoaded);
        updateContextStatus();
        updateFunctionStatus(modelLoaded);
        updateContextStats();
    }

    private void updateMainStatus(boolean modelLoaded, boolean isInitialized) {
        int successColor = getResources().getColor(R.color.success);
        int warningColor = getResources().getColor(R.color.warning);
        int errorColor = getResources().getColor(R.color.error);
        
        // 更新主状态灯和文本
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
        
        // 更新状态提示
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
            engineInfo.setText("Llama CPP");
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
            agentLight.setBackgroundResource(modelLoaded ? green : red);
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
            
            // 刷新推理库状态
            updateLibraryStatus();
            
            // 刷新上下文状态
            updateContextStatus();
            
            // 刷新功能状态
            updateFunctionStatus(modelLoaded);
            
            // 刷新上下文统计
            updateContextStats();
        });
    }
}
