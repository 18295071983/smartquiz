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
import android.widget.CheckBox;
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
import com.oilquiz.app.ai.service.OnlineInferenceService;
import com.oilquiz.app.ai.service.UsageTracker;
import com.oilquiz.app.ui.adapter.ModelListAdapter;
import com.oilquiz.app.util.AILogger;

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
    private EditText selectedModelInput;   // 实际模型名手动输入（列表未收录模型也可用）

    // 服务能力设置区（agent/多模态/网络搜索等差异化服务）
    private View servicesSection;
    private CheckBox svcAgent;
    private CheckBox svcVision;
    private CheckBox svcWebSearch;
    private CheckBox svcEmbedding;
    private CheckBox svcRerank;
    private CheckBox svcImageGen;
    private CheckBox svcFunctionCalling;
    private CheckBox svcTts;
    private CheckBox svcAsr;
    private boolean capabilitiesUserTouched; // 用户是否手动调整过能力勾选

    private ModelListFetcher modelListFetcher;
    private UsageTracker usageTracker;
    private OnlineModelManager modelManager;
    
    private String selectedModelId;
    private List<ApiModel> fetchedModels = new ArrayList<>();
    /** 编辑已有配置时带入的持久化模型缓存（用于取展示名，无需重新拉取） */
    private String cachedDisplaySource;
    
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
            if (selectedModelInput != null && selectedModelId != null) {
                selectedModelInput.setText(selectedModelId);
            }
            // 编辑已有配置时同样标出展示名（取缓存里的 name，无需重新拉取）
            this.cachedDisplaySource = config.cachedModelsJson;
            updateSelectedModelLabel(selectedModelId);
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
            // 回填实际模型名到手动输入框（编辑时可查看/修改，列表未收录的免费模型也可见可改）
            if (selectedModelInput != null && selectedModelId != null) {
                selectedModelInput.setText(selectedModelId);
            }

            // 编辑模式：按已有配置回填服务能力勾选并显示设置区
            if (servicesSection != null && svcAgent != null) {
                servicesSection.setVisibility(View.VISIBLE);
                svcAgent.setChecked(existingConfig.supportsAgent);
                svcVision.setChecked(existingConfig.supportsVision);
                svcWebSearch.setChecked(existingConfig.supportsWebSearch);
                svcEmbedding.setChecked(existingConfig.supportsEmbedding);
                svcRerank.setChecked(existingConfig.supportsRerank);
                svcImageGen.setChecked(existingConfig.supportsImageGen);
                svcFunctionCalling.setChecked(existingConfig.supportsFunctionCalling);
                svcTts.setChecked(existingConfig.supportsTts);
                svcAsr.setChecked(existingConfig.supportsAsr);
            }

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
        selectedModelInput = dialog.findViewById(R.id.input_selected_model);

        // 服务能力设置区
        servicesSection = dialog.findViewById(R.id.services_section);
        svcAgent = dialog.findViewById(R.id.svc_agent);
        svcVision = dialog.findViewById(R.id.svc_vision);
        svcWebSearch = dialog.findViewById(R.id.svc_web_search);
        svcEmbedding = dialog.findViewById(R.id.svc_embedding);
        svcRerank = dialog.findViewById(R.id.svc_rerank);
        svcImageGen = dialog.findViewById(R.id.svc_image_gen);
        svcFunctionCalling = dialog.findViewById(R.id.svc_function_calling);
        svcTts = dialog.findViewById(R.id.svc_tts);
        svcAsr = dialog.findViewById(R.id.svc_asr);
        // 能力勾选即视为用户手动设置（保存后配置表刷新不再覆盖）
        android.widget.CompoundButton.OnCheckedChangeListener userSet = (b, c) -> capabilitiesUserTouched = true;
        if (svcAgent != null) { svcAgent.setOnCheckedChangeListener(userSet); }
        if (svcVision != null) { svcVision.setOnCheckedChangeListener(userSet); }
        if (svcWebSearch != null) { svcWebSearch.setOnCheckedChangeListener(userSet); }
        if (svcEmbedding != null) { svcEmbedding.setOnCheckedChangeListener(userSet); }
        if (svcRerank != null) { svcRerank.setOnCheckedChangeListener(userSet); }
        if (svcImageGen != null) { svcImageGen.setOnCheckedChangeListener(userSet); }
        if (svcFunctionCalling != null) { svcFunctionCalling.setOnCheckedChangeListener(userSet); }
        if (svcTts != null) { svcTts.setOnCheckedChangeListener(userSet); }
        if (svcAsr != null) { svcAsr.setOnCheckedChangeListener(userSet); }

        // 服务能力设置区
        servicesSection = dialog.findViewById(R.id.services_section);
        svcAgent = dialog.findViewById(R.id.svc_agent);
        svcVision = dialog.findViewById(R.id.svc_vision);
        svcWebSearch = dialog.findViewById(R.id.svc_web_search);
        svcEmbedding = dialog.findViewById(R.id.svc_embedding);
        svcRerank = dialog.findViewById(R.id.svc_rerank);
        svcImageGen = dialog.findViewById(R.id.svc_image_gen);
        svcFunctionCalling = dialog.findViewById(R.id.svc_function_calling);
        svcTts = dialog.findViewById(R.id.svc_tts);
        svcAsr = dialog.findViewById(R.id.svc_asr);

        // 模型名手动输入框：内容变化时同步 selectedModelId（与列表点选/编辑回填保持一致）
        if (selectedModelInput != null) {
            selectedModelInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    if (s != null) {
                        selectedModelId = s.toString().trim();
                    }
                }
            });
        }

        // 设置 RecyclerView
        modelsAdapter = new ModelListAdapter(context, new ArrayList<>(), selectedModelId, 
            model -> {
                selectedModelId = model.id;
                // 点选列表项时回填**实际发给 API 的模型 id**（用户仍可改成任意模型名）。
                // 注意 id 与列表展示名不同：官方 GET /models 的 name 是展示名
                // （deepseek-flash → "DeepSeek-V4.1-Flash"），而 API 只接受 id。
                // 因此输入框写 id、标签同时标出展示名，避免"点了展示名却看到 id"的困惑。
                if (selectedModelInput != null) {
                    selectedModelInput.setText(model.id);
                }
                updateSelectedModelLabel(model.id);
            });
        modelsRecycler.setLayoutManager(new LinearLayoutManager(context));
        modelsRecycler.setAdapter(modelsAdapter);
    }

    /**
     * 在"可用模型"标题处标出**已选模型的展示名与实际 id**，使两者同时可见。
     *
     * <p>背景：官方 {@code GET /models} 的 {@code name} 是展示名，与 id 不同
     * （{@code deepseek-flash} ↔ {@code DeepSeek-V4.1-Flash}），而请求必须用 id。
     * 只显示其一都会让人以为"选错了模型"。</p>
     */
    private void updateSelectedModelLabel(String modelId) {
        if (modelsTitle == null || modelId == null) return;
        String display = findDisplayNameInFetched(modelId);
        if (display != null && !display.equals(modelId)) {
            modelsTitle.setText("可用模型 —— 已选：" + display + "（" + modelId + "）");
        } else {
            modelsTitle.setText("可用模型 —— 已选：" + modelId);
        }
    }

    /** 从已获取的模型列表里按 id 取展示名；取不到返回 null */
    private String findDisplayNameInFetched(String modelId) {
        try {
            for (ApiModel m : fetchedModels) {
                if (m != null && modelId.equals(m.id)) {
                    String n = m.getName();
                    return n != null && !n.isEmpty() ? n : null;
                }
            }
        } catch (Throwable ignored) {
        }
        // 编辑已有配置时 fetchedModels 为空：回落到持久化缓存里的 name
        try {
            return com.oilquiz.app.ai.service.ModelListFetcher
                    .findDisplayNameInCache(cachedDisplaySource, modelId);
        } catch (Throwable ignored) {
        }
        return null;
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
     * 初始化服务类型下拉框（服务列表与地址来自 ProviderConfigManager 配置表，
     * 更新 assets/providers.json 或外部覆盖文件即可增删服务，无需改代码）
     */
    private void setupServiceTypeSpinner() {
        java.util.List<com.oilquiz.app.ai.model.ProviderConfigManager.Provider> providers =
            com.oilquiz.app.ai.model.ProviderConfigManager.get().getProviders();
        if (providers.isEmpty()) {
            // 配置表缺失时回退资源数组，避免空列表
            String[] fallback = context.getResources().getStringArray(
                com.oilquiz.app.R.array.service_types_default);
            ArrayAdapter<String> fbAdapter = new ArrayAdapter<>(
                context, android.R.layout.simple_dropdown_item_1line, fallback);
            serviceTypeSpinner.setAdapter(fbAdapter);
            return;
        }

        // 下拉显示名与地址映射（全部来自配置表）
        final String[] types = new String[providers.size()];
        final String[][] mappings = new String[providers.size()][2];
        for (int i = 0; i < providers.size(); i++) {
            com.oilquiz.app.ai.model.ProviderConfigManager.Provider p = providers.get(i);
            types[i] = p.name;
            mappings[i][0] = p.name;
            mappings[i][1] = p.baseUrl != null ? p.baseUrl : "";
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            context, android.R.layout.simple_dropdown_item_1line, types);
        serviceTypeSpinner.setAdapter(adapter);

        // 选择后自动填充 URL，不自动设置默认模型
        serviceTypeSpinner.setOnItemClickListener((parent, view, position, id) -> {
            String selectedType = parent.getItemAtPosition(position).toString();
            for (String[] mapping : mappings) {
                if (mapping[0].equals(selectedType)) {
                    if (!urlInput.hasFocus()) {
                        urlInput.setText(mapping[1]);
                    }
                    // 设置 API Key 占位符
                    if (keyInput.getText().toString().trim().isEmpty()) {
                        if ("小米 MiMo".equals(mapping[0]) || "DeepSeek".equals(mapping[0])) {
                            keyInput.setHint(mapping[0] + " API Key");
                        } else {
                            keyInput.setHint(mapping[0] + " API Key（sk-...）");
                        }
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
            if (servicesSection != null) {
                servicesSection.setVisibility(View.GONE);
            }
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

        // 服务能力设置区：编辑模式用配置值，新建模式按服务商配置表预置（可手动调整）
        applyServiceDefaults(url, editingConfig);
    }

    /**
     * 服务能力勾选区：编辑已有配置 → 用配置里保存的能力值；
     * 新建 → 按服务商配置表 providers.json 的 services 声明预置勾选。
     * 用户勾选后保存即持久化（capabilitiesUserSet=true，配置表刷新不再覆盖）。
     */
    private void applyServiceDefaults(String url, com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig config) {
        if (servicesSection == null || url == null || url.isEmpty()) return;
        com.oilquiz.app.ai.model.ProviderConfigManager pcm =
                com.oilquiz.app.ai.model.ProviderConfigManager.get();
        boolean known = pcm.matchByUrl(url) != null;
        if (config != null) {
            // 编辑模式：用已保存的能力值
            svcAgent.setChecked(config.supportsAgent);
            svcVision.setChecked(config.supportsVision);
            svcWebSearch.setChecked(config.supportsWebSearch);
            svcEmbedding.setChecked(config.supportsEmbedding);
            svcRerank.setChecked(config.supportsRerank);
            svcImageGen.setChecked(config.supportsImageGen);
            svcFunctionCalling.setChecked(config.supportsFunctionCalling);
            svcTts.setChecked(config.supportsTts);
            svcAsr.setChecked(config.supportsAsr);
        } else {
            // 新建模式：按配置表预置（未知服务商全不勾选，用户可手动开）
            svcAgent.setChecked(known && pcm.hasService(url, "agent"));
            svcVision.setChecked(known && pcm.hasService(url, "vision"));
            svcWebSearch.setChecked(known && pcm.hasService(url, "webSearch"));
            svcEmbedding.setChecked(known && pcm.hasService(url, "embedding"));
            svcRerank.setChecked(known && pcm.hasService(url, "rerank"));
            svcImageGen.setChecked(known && pcm.hasService(url, "imageGen"));
            svcFunctionCalling.setChecked(known && pcm.hasService(url, "functionCalling"));
            svcTts.setChecked(known && pcm.hasService(url, "tts"));
            svcAsr.setChecked(known && pcm.hasService(url, "asr"));
        }
        servicesSection.setVisibility(View.VISIBLE);
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
                    // 连接成功后自动探测模型能力（embedding/vision/webSearch/agent），
                    // 真实请求验证替代配置表硬编码，结果回填能力勾选框
                    probeCapabilitiesAfterConnect(url, key);
                } else {
                    Toast.makeText(context, "连接失败，请检查 API 地址和密钥", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /** 连接成功后异步探测模型能力并回填能力勾选框（仅补勾探测确认的能力，不强制取消） */
    private void probeCapabilitiesAfterConnect(String url, String key) {
        String model = selectedModelId;
        if (model == null || model.isEmpty()) return;
        com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig cfg =
                new com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig();
        cfg.apiUrl = url;
        cfg.apiKey = key;
        cfg.modelName = model;
        com.oilquiz.app.ai.service.OnlineInferenceService.getInstance(context)
                .probeCapabilities(cfg).thenAccept(map -> mainHandler.post(() -> {
            if (map == null || map.isEmpty()) return;
            StringBuilder sb = new StringBuilder("能力探测: ");
            applyProbeResult(svcEmbedding, map.get("embedding"), "embedding", sb);
            applyProbeResult(svcVision, map.get("vision"), "vision", sb);
            applyProbeResult(svcWebSearch, map.get("webSearch"), "webSearch", sb);
            applyProbeResult(svcAgent, map.get("agent"), "agent", sb);
            Toast.makeText(context, sb.toString().trim(), Toast.LENGTH_LONG).show();
        })).exceptionally(e -> {
            AILogger.w("OnlineModelConfigDialog", "能力探测失败: " + e.getMessage());
            return null;
        });
    }

    /** 探测到 true 的能力补勾选（用户已手动设置的保持不动） */
    private void applyProbeResult(android.widget.CheckBox box, Boolean value, String name, StringBuilder sb) {
        if (box == null || value == null) return;
        if (value) {
            sb.append(" ").append(name).append("✓");
            if (!box.isChecked()) {
                box.setChecked(true);
            }
        }
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
                    // 标题同时反映"数量 + 已选模型的展示名与 id"，避免只显示 id 让人误判
                    updateSelectedModelLabel(selectedModelId);
                    if (selectedModelId == null || selectedModelId.isEmpty()) {
                        modelsTitle.setText("可用模型 (" + models.size() + ")");
                    }
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

        // 非"已用/总量"语义的额度（如 DeepSeek 余额）：直显文本，不套用百分比进度
        if (info.supported && info.note != null && !info.note.isEmpty()) {
            usageProgress.setProgress(0);
            usagePercentText.setText("-");
            usageText.setText(info.note);
            return;
        }

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

        // 设置选择的模型：优先手动输入框（列表未收录的免费模型等可直接填写），
        // 其次列表点选值，最后默认
        String selectedModel;
        if (selectedModelInput != null && !selectedModelInput.getText().toString().trim().isEmpty()) {
            selectedModel = selectedModelInput.getText().toString().trim();
        } else if (selectedModelId != null && !selectedModelId.isEmpty()) {
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
                    // 与"AI 中心自动获取"共用同一序列化（含思考档位/max_output_tokens），
                    // 避免两处各写一份、其中一处漏字段导致档位丢失
                    arr.put(ModelListFetcher.modelToCacheJson(model));
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
        // 服务能力（差异化服务设置：agent/多模态/网络搜索/embedding/rerank/文生图/工具调用/语音）
        if (svcAgent != null) {
            config.supportsAgent = svcAgent.isChecked();
            config.supportsVision = svcVision.isChecked();
            config.supportsWebSearch = svcWebSearch.isChecked();
            config.supportsEmbedding = svcEmbedding.isChecked();
            config.supportsRerank = svcRerank.isChecked();
            config.supportsImageGen = svcImageGen.isChecked();
            config.supportsFunctionCalling = svcFunctionCalling.isChecked();
            config.supportsTts = svcTts.isChecked();
            config.supportsAsr = svcAsr.isChecked();
            // 保存即视为用户手动设置能力（配置表刷新不再覆盖；新建必置 true）
            config.capabilitiesUserSet = !isEditing || editingConfig.capabilitiesUserSet || capabilitiesUserTouched;
        }
        if (cachedModelsJson != null) {
            config.cachedModelsJson = cachedModelsJson;
            config.lastFetchTime = System.currentTimeMillis();
        }
        // 配置时直接请求上下文大小：优先用本次「获取模型列表」解析出的真实窗口（context_length 等字段），
        // 列表接口未带字段时异步补查一次 /models/{name} 详情并持久化（命中全局缓存，Agent 执行时零额外请求）
        applyDetectedContextWindow(config, selectedModel);
        // 持久化扩展字段
        modelManager.updateModelConfig(config);

        if (saveListener != null) {
            saveListener.onConfigSaved(config);
        }

        Toast.makeText(context, isEditing ? "配置已更新" : "配置已保存", Toast.LENGTH_SHORT).show();
        dialog.dismiss();
    }

    /**
     * 配置时直接请求上下文大小并写入配置：
     * 1) 若本次「获取模型列表」解析出选中模型的**真实 API 字段**（context_length 等，
     *    contextLengthFromApi=true）→ 直接写入；
     * 2) 否则（列表接口未带字段，或仅名称推断值）异步补查一次 /models/{name} 详情
     *    （queryContextWindowFromAPI 带持久化缓存，Agent 执行时命中缓存零额外请求），
     *    成功后回写 config 并持久化。
     */
    private void applyDetectedContextWindow(final OnlineModelManager.OnlineModelConfig config, final String selectedModel) {
        if (config == null || selectedModel == null || selectedModel.isEmpty()) {
            return;
        }
        // 1) 仅当列表值为服务商 API 返回的真实字段时才直接采用（避免名称推断死值短路真实查询）
        ApiModel picked = modelsAdapter.findModelById(selectedModel);
        if (picked != null && picked.contextLength > 0 && picked.contextLengthFromApi) {
            config.contextWindow = picked.contextLength;
            config.contextWindowFromApi = true;
            return;
        }
        // 2) 列表接口未带上下文字段：异步补查一次并回写
        try {
            CompletableFuture.supplyAsync(() -> {
                try {
                    return com.oilquiz.app.ai.service.OnlineInferenceService
                            .getInstance(context).queryContextWindowFromAPI(config);
                } catch (Throwable t) {
                    return null;
                }
            }).thenAccept(window -> {
                if (window != null && window > 0) {
                    config.contextWindow = window;
                    config.contextWindowFromApi = true;
                } else {
                    // API 未返回真实值：回退配置表推断，避免残留旧死值（如名称推断的 4096）
                    int inferred = OnlineModelManager.getContextWindowForModel(config.apiUrl, config.modelName);
                    if (inferred > 0 && inferred != config.contextWindow) {
                        config.contextWindow = inferred;
                    }
                    config.contextWindowFromApi = false;
                }
                modelManager.updateModelConfig(config);
            });
        } catch (Throwable t) {
            // 查询失败静默，保留配置表推断值
        }
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
                        int ctx = obj.optInt("contextLength", 0); // 还原配置时检测到的真实窗口
                        if (ctx > 0) {
                            m.contextLength = ctx;
                            m.contextLengthFromApi = obj.optBoolean("contextLengthFromApi", false);
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