package com.oilquiz.app.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.model.ApiModel;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.model.UsageInfo;
import com.oilquiz.app.ai.service.ModelListFetcher;
import com.oilquiz.app.ai.service.UsageTracker;
import com.oilquiz.app.ui.adapter.ModelListAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 在线模型配置对话框
 * 支持：API配置、模型列表获取、模型选择、使用量显示
 */
public class OnlineModelConfigDialog {

    private Dialog dialog;
    private Context context;
    private Handler mainHandler;
    
    private EditText nameInput;
    private EditText urlInput;
    private EditText keyInput;
    private EditText apiSecretInput;
    private EditText appIdInput;
    private AutoCompleteTextView serviceTypeSpinner;
    private View apiSecretLayout;
    private View appIdLayout;
    private TextView endpointHint;
    private MaterialButton testConnectionButton;
    private MaterialButton fetchModelsButton;
    private MaterialButton saveButton;
    private MaterialButton cancelButton;
    
    private RecyclerView modelsRecycler;
    private ModelListAdapter modelsAdapter;
    private ProgressBar loadingProgress;
    private TextView loadingText;
    
    private LinearLayout usageSection;
    private TextView usageText;
    private ProgressBar usageProgress;
    private TextView usagePercentText;
    
    private View modelsSection;
    private TextView modelsTitle;
    
    private ModelListFetcher modelListFetcher;
    private UsageTracker usageTracker;
    private OnlineModelManager modelManager;
    
    private String selectedModelId;
    private List<ApiModel> fetchedModels = new ArrayList<>();
    
    /** 正在编辑的已有配置（null 表示新建） */
    private OnlineModelManager.OnlineModelConfig editingConfig;
    
    private OnConfigSaveListener saveListener;
    
    public interface OnConfigSaveListener {
        void onConfigSaved(OnlineModelManager.OnlineModelConfig config);
        void onConfigCancelled();
    }

    public OnlineModelConfigDialog(@NonNull Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.modelListFetcher = ModelListFetcher.getInstance(context);
        this.usageTracker = UsageTracker.getInstance(context);
        this.modelManager = OnlineModelManager.getInstance(context);
    }

    public OnlineModelConfigDialog setExistingConfig(OnlineModelManager.OnlineModelConfig config) {
        if (config != null && nameInput != null) {
            nameInput.setText(config.name);
            urlInput.setText(config.apiUrl);
            keyInput.setText(config.apiKey);
            if (config.apiSecret != null && !config.apiSecret.isEmpty()) {
                apiSecretInput.setText(config.apiSecret);
            }
            if (config.appId != null && !config.appId.isEmpty()) {
                appIdInput.setText(config.appId);
            }
            selectedModelId = config.selectedModel;
        }
        return this;
    }

    public OnlineModelConfigDialog setSaveListener(OnConfigSaveListener listener) {
        this.saveListener = listener;
        return this;
    }

    public void show() {
        show(null);
    }

    public void show(OnlineModelManager.OnlineModelConfig existingConfig) {
        this.editingConfig = existingConfig;
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_online_model_config);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        initViews();
        setupListeners();
        setupServiceTypeSpinner();
        
        if (existingConfig != null) {
            nameInput.setText(existingConfig.name);
            urlInput.setText(existingConfig.apiUrl);
            keyInput.setText(existingConfig.apiKey);
            selectedModelId = existingConfig.selectedModel;

            // 填充 apiSecret 和 appId
            if (existingConfig.apiSecret != null && !existingConfig.apiSecret.isEmpty()) {
                apiSecretInput.setText(existingConfig.apiSecret);
            }
            if (existingConfig.appId != null && !existingConfig.appId.isEmpty()) {
                appIdInput.setText(existingConfig.appId);
            }

            // 触发端点类型检测，显示/隐藏扩展字段
            updateEndpointFields(existingConfig.apiUrl);

            // 如果有缓存的模型，尝试加载
            if (existingConfig.cachedModelsJson != null && !existingConfig.cachedModelsJson.isEmpty()) {
                loadCachedModels(existingConfig.cachedModelsJson);
            }
            
            // 显示使用量信息
            if (existingConfig.usageInfo != null) {
                updateUsageDisplay(existingConfig.usageInfo);
            }
        }
        
        dialog.show();
    }

    private void initViews() {
        nameInput = dialog.findViewById(R.id.input_model_name);
        urlInput = dialog.findViewById(R.id.input_api_url);
        keyInput = dialog.findViewById(R.id.input_api_key);
        serviceTypeSpinner = dialog.findViewById(R.id.spinner_service_type);
        apiSecretInput = dialog.findViewById(R.id.input_api_secret);
        appIdInput = dialog.findViewById(R.id.input_app_id);
        apiSecretLayout = dialog.findViewById(R.id.layout_api_secret);
        appIdLayout = dialog.findViewById(R.id.layout_app_id);
        endpointHint = dialog.findViewById(R.id.endpoint_hint);

        testConnectionButton = dialog.findViewById(R.id.btn_test_connection);
        fetchModelsButton = dialog.findViewById(R.id.btn_fetch_models);
        saveButton = dialog.findViewById(R.id.btn_save);
        cancelButton = dialog.findViewById(R.id.btn_cancel);
        
        modelsRecycler = dialog.findViewById(R.id.models_recycler);
        loadingProgress = dialog.findViewById(R.id.loading_progress);
        loadingText = dialog.findViewById(R.id.loading_text);
        
        usageSection = dialog.findViewById(R.id.usage_section);
        usageText = dialog.findViewById(R.id.usage_text);
        usageProgress = dialog.findViewById(R.id.usage_progress);
        usagePercentText = dialog.findViewById(R.id.usage_percent_text);
        
        modelsSection = dialog.findViewById(R.id.models_section);
        modelsTitle = dialog.findViewById(R.id.models_title);
        
        // 设置 RecyclerView
        modelsAdapter = new ModelListAdapter(context, new ArrayList<>(), selectedModelId, 
            model -> {
                selectedModelId = model.id;
            });
        modelsRecycler.setLayoutManager(new LinearLayoutManager(context));
        modelsRecycler.setAdapter(modelsAdapter);
    }

    private void setupListeners() {
        testConnectionButton.setOnClickListener(v -> testConnection());
        fetchModelsButton.setOnClickListener(v -> fetchModels());
        
        saveButton.setOnClickListener(v -> saveConfig());
        cancelButton.setOnClickListener(v -> {
            if (saveListener != null) {
                saveListener.onConfigCancelled();
            }
            dialog.dismiss();
        });

        // API URL 变化时自动检测端点类型，显示/隐藏 apiSecret 和 appId 字段
        urlInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                updateEndpointFields(s.toString().trim());
            }
        });
    }

    /**
     * 初始化服务类型下拉框
     */
    private void setupServiceTypeSpinner() {
        String[] types = context.getResources().getStringArray(
            com.oilquiz.app.R.array.service_types_default);
        
        // 默认值和端点 URL 映射
        final String[][] mappings = {
            {"OpenAI 兼容", "https://api.openai.com/v1"},
            {"百炼 DashScope", "https://dashscope.aliyuncs.com/compatible-mode/v1"},
            {"讯飞", "https://api.xfyun.cn"},
            {"火山引擎", "https://openspeech.bytedance.com"},
            {"百度", "https://aip.baidubce.com"}
        };

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            context, android.R.layout.simple_dropdown_item_1line, types);
        serviceTypeSpinner.setAdapter(adapter);

        // 选择后自动填充 URL 和 API Key 占位符
        serviceTypeSpinner.setOnItemClickListener((parent, view, position, id) -> {
            String selectedType = parent.getItemAtPosition(position).toString();
            for (String[] mapping : mappings) {
                if (mapping[0].equals(selectedType)) {
                    if (!urlInput.hasFocus()) {
                        urlInput.setText(mapping[1]);
                    }
                    // 设置 API Key 占位符
                    if (keyInput.getText().toString().trim().isEmpty()) {
                        keyInput.setHint(mapping[0] + " API Key（sk-...）");
                    }
                    break;
                }
            }
            // 自动检测端点字段
            updateEndpointFields(urlInput.getText().toString().trim());
        });
    }

    /**
     * 根据 API URL 检测端点类型，动态显示/隐藏 apiSecret 和 appId 字段
     *
     * 讯飞：需要 appId + apiKey + apiSecret
     * 百度：需要 apiKey + apiSecret（Secret Key）
     * 火山引擎：需要 appId + apiKey（Access Token）
     * 其他：只需 apiKey
     */
    private void updateEndpointFields(String url) {
        if (url == null || url.isEmpty()) {
            apiSecretLayout.setVisibility(View.GONE);
            appIdLayout.setVisibility(View.GONE);
            endpointHint.setVisibility(View.GONE);
            return;
        }
        String lower = url.toLowerCase();
        boolean isXfyun = lower.contains("xfyun.cn") || lower.contains("iflytek");
        boolean isBaidu = lower.contains("baidu.com") || lower.contains("baidubce.com");
        boolean isVolcano = lower.contains("openspeech.bytedance.com") || lower.contains("volcengine.com")
                || lower.contains("bytedance");

        if (isXfyun) {
            apiSecretLayout.setVisibility(View.VISIBLE);
            appIdLayout.setVisibility(View.VISIBLE);
            ((com.google.android.material.textfield.TextInputLayout) apiSecretLayout)
                    .setHint("APISecret（在控制台获取）");
            ((com.google.android.material.textfield.TextInputLayout) appIdLayout)
                    .setHint("APPID（在控制台获取）");
            endpointHint.setVisibility(View.VISIBLE);
            endpointHint.setText("检测到讯飞端点，需填写 APPID、APIKey 和 APISecret");
        } else if (isBaidu) {
            apiSecretLayout.setVisibility(View.VISIBLE);
            appIdLayout.setVisibility(View.GONE);
            ((com.google.android.material.textfield.TextInputLayout) apiSecretLayout)
                    .setHint("Secret Key（百度智能云控制台获取）");
            endpointHint.setVisibility(View.VISIBLE);
            endpointHint.setText("检测到百度端点，API 密钥填 API Key，API Secret 填 Secret Key");
        } else if (isVolcano) {
            apiSecretLayout.setVisibility(View.GONE);
            appIdLayout.setVisibility(View.VISIBLE);
            ((com.google.android.material.textfield.TextInputLayout) appIdLayout)
                    .setHint("AppID（火山引擎控制台获取）");
            endpointHint.setVisibility(View.VISIBLE);
            endpointHint.setText("检测到火山引擎端点，API 密钥填 Access Token，需填写 AppID");
        } else {
            apiSecretLayout.setVisibility(View.GONE);
            appIdLayout.setVisibility(View.GONE);
            endpointHint.setVisibility(View.GONE);
        }
    }

    private void testConnection() {
        String url = urlInput.getText().toString().trim();
        String key = keyInput.getText().toString().trim();
        
        if (url.isEmpty() || key.isEmpty()) {
            Toast.makeText(context, "请填写 API 地址和密钥", Toast.LENGTH_SHORT).show();
            return;
        }
        
        testConnectionButton.setEnabled(false);
        testConnectionButton.setText("测试中...");
        
        modelListFetcher.testConnection(url, key).thenAccept(success -> {
            mainHandler.post(() -> {
                testConnectionButton.setEnabled(true);
                testConnectionButton.setText("测试连通性");
                
                if (success) {
                    Toast.makeText(context, "连接成功!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, "连接失败，请检查 API 地址和密钥", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void fetchModels() {
        String url = urlInput.getText().toString().trim();
        String key = keyInput.getText().toString().trim();
        
        if (url.isEmpty() || key.isEmpty()) {
            Toast.makeText(context, "请填写 API 地址和密钥", Toast.LENGTH_SHORT).show();
            return;
        }
        
        showLoading(true, "正在获取模型列表...");
        fetchModelsButton.setEnabled(false);
        
        modelListFetcher.fetchModels(url, key).thenAccept(models -> {
            mainHandler.post(() -> {
                showLoading(false, null);
                fetchModelsButton.setEnabled(true);
                
                if (models != null && !models.isEmpty()) {
                    fetchedModels = models;
                    modelsAdapter.updateData(models, selectedModelId);
                    modelsSection.setVisibility(View.VISIBLE);
                    modelsTitle.setText("可用模型 (" + models.size() + ")");
                    Toast.makeText(context, "获取到 " + models.size() + " 个模型", Toast.LENGTH_SHORT).show();
                    
                    // 同时获取使用量
                    fetchUsage();
                } else {
                    Toast.makeText(context, "未获取到可用模型", Toast.LENGTH_SHORT).show();
                }
            });
        }).exceptionally(e -> {
            mainHandler.post(() -> {
                showLoading(false, null);
                fetchModelsButton.setEnabled(true);
                Toast.makeText(context, "获取模型列表失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
            return null;
        });
    }

    private void fetchUsage() {
        String url = urlInput.getText().toString().trim();
        String key = keyInput.getText().toString().trim();
        
        usageTracker.fetchUsage(url, key).thenAccept(info -> {
            mainHandler.post(() -> updateUsageDisplay(info));
        });
    }

    private void updateUsageDisplay(UsageInfo info) {
        if (info == null) {
            return;
        }
        
        usageSection.setVisibility(View.VISIBLE);
        
        if (info.supported) {
            int percentage = info.getUsagePercentage();
            usageProgress.setProgress(Math.min(percentage, 100));
            usagePercentText.setText(percentage + "%");
            usageText.setText(String.format("使用量: %s / %s",
                info.getFormattedUsed(), info.getFormattedTotal()));
        } else {
            usageProgress.setProgress(0);
            usagePercentText.setText("-");
            if (info.errorMessage != null) {
                usageText.setText(info.errorMessage);
            } else {
                usageText.setText("使用量信息不可用");
            }
        }
    }

    private void showLoading(boolean show, String text) {
        if (show) {
            loadingProgress.setVisibility(View.VISIBLE);
            if (text != null) {
                loadingText.setVisibility(View.VISIBLE);
                loadingText.setText(text);
            }
        } else {
            loadingProgress.setVisibility(View.GONE);
            loadingText.setVisibility(View.GONE);
        }
    }

    private void saveConfig() {
        String name = nameInput.getText().toString().trim();
        String url = urlInput.getText().toString().trim();
        String key = keyInput.getText().toString().trim();
        String apiSecret = apiSecretInput.getText().toString().trim();
        String appId = appIdInput.getText().toString().trim();

        if (name.isEmpty()) {
            Toast.makeText(context, "请填写模型名称", Toast.LENGTH_SHORT).show();
            return;
        }
        if (url.isEmpty()) {
            Toast.makeText(context, "请填写 API 地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (key.isEmpty()) {
            Toast.makeText(context, "请填写 API 密钥", Toast.LENGTH_SHORT).show();
            return;
        }

        // 设置选择的模型
        String selectedModel;
        if (selectedModelId != null && !selectedModelId.isEmpty()) {
            selectedModel = selectedModelId;
        } else {
            selectedModel = "gpt-3.5-turbo"; // 默认模型
        }

        // 保存缓存的模型列表（使用 JSON 数组格式以保持与 OnlineModelAdapter.parseCachedModels 兼容）
        String cachedModelsJson = null;
        if (!fetchedModels.isEmpty()) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (ApiModel model : fetchedModels) {
                    org.json.JSONObject obj = new org.json.JSONObject();
                    obj.put("id", model.id);
                    obj.put("name", model.getName());
                    arr.put(obj);
                }
                cachedModelsJson = arr.toString();
            } catch (Exception e) {
                cachedModelsJson = null;
            }
        }

        OnlineModelManager.OnlineModelConfig config;
        boolean isEditing = editingConfig != null;

        if (isEditing) {
            // 编辑模式：更新已有配置
            modelManager.updateModel(editingConfig.id, name, url, selectedModel, key, editingConfig.enabled);
            config = modelManager.getModel(editingConfig.id);
            if (config == null) {
                Toast.makeText(context, "保存失败：配置不存在", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            // 新建模式
            config = modelManager.addModel(name, url, selectedModel, key);
        }

        // 更新扩展字段
        config.selectedModel = selectedModel;
        config.autoFetchModels = true;
        config.apiSecret = apiSecret.isEmpty() ? null : apiSecret;
        config.appId = appId.isEmpty() ? null : appId;
        if (cachedModelsJson != null) {
            config.cachedModelsJson = cachedModelsJson;
            config.lastFetchTime = System.currentTimeMillis();
        }
        // 持久化扩展字段
        modelManager.updateModelConfig(config);

        if (saveListener != null) {
            saveListener.onConfigSaved(config);
        }

        Toast.makeText(context, isEditing ? "配置已更新" : "配置已保存", Toast.LENGTH_SHORT).show();
        dialog.dismiss();
    }

    private void loadCachedModels(String cachedJson) {
        if (cachedJson == null || cachedJson.isEmpty()) {
            return;
        }

        List<ApiModel> models = new ArrayList<>();

        // 优先尝试 JSON 数组格式（新格式）
        boolean parsedAsJson = false;
        String trimmed = cachedJson.trim();
        if (trimmed.startsWith("[")) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(trimmed);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject obj = arr.getJSONObject(i);
                    String id = obj.optString("id", null);
                    String name = obj.optString("name", null);
                    if (id != null) {
                        ApiModel m = new ApiModel(id);
                        if (name != null) {
                            m.displayName = name;
                        }
                        models.add(m);
                    }
                }
                parsedAsJson = true;
            } catch (Exception ignored) {
                // 解析失败，回退到旧格式
            }
        }

        // 回退到逗号分隔格式（旧格式）
        if (!parsedAsJson) {
            String[] modelIds = cachedJson.split(",");
            for (String id : modelIds) {
                String trimmedId = id.trim();
                if (!trimmedId.isEmpty()) {
                    models.add(new ApiModel(trimmedId));
                }
            }
        }

        if (!models.isEmpty()) {
            fetchedModels = models;
            modelsAdapter.updateData(models, selectedModelId);
            modelsSection.setVisibility(View.VISIBLE);
            modelsTitle.setText("可用模型 (" + models.size() + ") - 缓存");
        }
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }
}