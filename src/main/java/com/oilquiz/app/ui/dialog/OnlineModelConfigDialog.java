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
    /** 单价（$/百万 token）：唯一有能力含义却没有入口的注册字段 */
    private EditText costInput;
    /** 协议只读提示：展示推理链实际按 providers.json 反推出的协议 */
    private TextView protocolHint;
    /** 标题：新建与编辑必须区分，此前恒显示「配置在线模型」，用户不确定自己在改哪一条 */
    private TextView dialogTitle;
    /** 能力区标题：agent / 工具调用 改为只读后，在这里说明它们的来源 */
    private TextView servicesTitle;
    /** 协议快捷选择：只把端点填成该协议的标准值，本身不入库（协议由端点推导） */
    private AutoCompleteTextView protocolSpinner;
    /** chat / agent 端点覆盖输入框（留空 = 按服务商配置表） */
    private EditText chatEndpointInput;
    private EditText agentEndpointInput;
    /** 实时解析结果：当前实际会打哪个地址、这个地址是哪一层给的 */
    private TextView endpointResolveHint;
    private boolean capabilitiesUserTouched; // 用户是否手动调整过能力勾选
    private boolean programmaticCapabilityChange; // true=程序回填/预置勾选期间，屏蔽"用户手动调整"误标记
    /** 用户是否手动改过单价：改过之后，程序回填不再覆盖（此前 URL 每敲一个字符都会把它写回去） */
    private boolean costUserTouched;
    /** true=程序正在回填单价期间，屏蔽"用户手动改过"误标记（与能力勾选同一套做法） */
    private boolean programmaticCostChange;
    /** 能力由连通性探测确认过（区别于"用户手动设置"，但同样不该被配置表刷新覆盖） */
    private boolean capabilitiesProbed;
    /** 上一次做「能力/单价预置」时的服务商标识；同一服务商内不再重置，换服务商才重填 */
    private String lastPrefillProviderKey;
    /** 当前「可用模型」列表来自缓存而非本次 API 拉取（标题要标出来） */
    private boolean modelsFromCache;

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
            refreshModelsTitle();
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
        // 窗口高度取屏高的 86%，而不是 WRAP_CONTENT：本表单编辑态下各区块全展开，内容高度必然
        // 超过屏幕。给一个**确定**的高度，中间 NestedScrollView(0dp + weight) 才是在 EXACTLY
        // 规格下测量（wrap_content 根 + 加权子节点的组合在不同版本上表现不一致，最坏情况整块表单
        // 塌成 0 高），底部「取消/保存」因此永远在可视区内。
        // 与仓库里 dialog_voice_feature_info.xml 的「固定头 + 可滚中部 + 固定按钮」形状一致。
        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        dialog.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(0, (int) (screenHeight * 0.86f)));
        // 键盘弹出时压缩窗口而非平移：底部是固定的「取消/保存」，
        // 默认行为会让键盘直接盖住它们
        dialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        initViews();
        setupListeners();
        setupServiceTypeSpinner();

        setupProtocolPicker();
        prefillEndpointOverrides();

        if (dialogTitle != null) {
            dialogTitle.setText(existingConfig != null ? "编辑在线模型" : "添加在线模型");
        }

        if (existingConfig != null) {
            nameInput.setText(existingConfig.name);
            urlInput.setText(existingConfig.apiUrl);
            keyInput.setText(existingConfig.apiKey);
            selectedModelId = existingConfig.selectedModel;
            // 展示名的取数来源：编辑时列表尚未拉取，只能从这条配置自带的缓存里查
            cachedDisplaySource = existingConfig.cachedModelsJson;
            // 回填实际模型名到手动输入框（编辑时可查看/修改，列表未收录的免费模型也可见可改）
            if (selectedModelInput != null && selectedModelId != null) {
                selectedModelInput.setText(selectedModelId);
            }

            // 服务能力勾选统一由下方 updateEndpointFields → applyServiceDefaults 回填（编辑模式用已存值），
            // 此处不再重复 setChecked —— 程序回填须避免把能力误标为"用户手动设置"（见 programmaticCapabilityChange）。

            // 填充 apiSecret 和 appId
            if (existingConfig.apiSecret != null && !existingConfig.apiSecret.isEmpty()) {
                apiSecretInput.setText(existingConfig.apiSecret);
            }
            if (existingConfig.appId != null && !existingConfig.appId.isEmpty()) {
                appIdInput.setText(existingConfig.appId);
            }

            // 服务类型下拉回填：它不落盘（只是"自动填充地址"的助手），
            // 但编辑一条已有配置时留空会让人以为这个字段没存住 —— 按地址反查配置表显示出来
            backfillServiceType(existingConfig.apiUrl);

            // 触发端点类型检测，显示/隐藏扩展字段 + 首次回填能力/单价
            updateEndpointFields(existingConfig.apiUrl);

            // 如果有缓存的模型，尝试加载
            if (existingConfig.cachedModelsJson != null && !existingConfig.cachedModelsJson.isEmpty()) {
                loadCachedModels(existingConfig.cachedModelsJson);
            }
            // 标题必须在此之后写：loadCachedModels 只负责灌列表，谁显示"已选哪个模型"由这里统一收口
            refreshModelsTitle();
            // 端点预览最后刷一次：上面回填了地址/模型名与端点覆盖，早先那次算的是空地址
            refreshEndpointResolveHint();
            
            // 显示使用量信息
            if (existingConfig.usageInfo != null) {
                updateUsageDisplay(existingConfig.usageInfo);
            }
        }
        
        dialog.show();
    }

    // ==================== 端点 / 协议（唯一事实源＝端点，协议由端点推导） ====================

    /** 协议候选：选它只是把对应端点填成标准值，界面不提供"单独存协议"的入口 */
    private static final String[][] PROTOCOL_CHOICES = {
            {"OpenAI chat（/chat/completions）", "/chat/completions", "chat"},
            {"OpenAI Responses（/responses）", "/responses", "agent"},
            {"Anthropic Messages（/v1/messages）", "/v1/messages", "chat"},
            {"Ollama 原生（/api/chat）", "/api/chat", "chat"},
    };

    private void setupProtocolPicker() {
        if (protocolSpinner == null) return;
        String[] labels = new String[PROTOCOL_CHOICES.length];
        for (int i = 0; i < PROTOCOL_CHOICES.length; i++) labels[i] = PROTOCOL_CHOICES[i][0];
        protocolSpinner.setAdapter(new ArrayAdapter<>(context,
                android.R.layout.simple_dropdown_item_1line, labels));
        protocolSpinner.setOnItemClickListener((parent, view, position, id) -> {
            String[] choice = PROTOCOL_CHOICES[position];
            // 选协议＝填那条服务对应的端点标准值；用户仍可手改成别的路径
            if ("agent".equals(choice[2])) {
                if (agentEndpointInput != null) agentEndpointInput.setText(choice[1]);
            } else if (chatEndpointInput != null) {
                chatEndpointInput.setText(choice[1]);
            }
            refreshEndpointResolveHint();
        });
        if (chatEndpointInput != null) {
            chatEndpointInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) { refreshEndpointResolveHint(); }
            });
        }
        if (agentEndpointInput != null) {
            agentEndpointInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) { refreshEndpointResolveHint(); }
            });
        }
    }

    /** 编辑已有配置时回填端点覆盖（空=未覆盖），并提示各层默认值 */
    private void prefillEndpointOverrides() {
        String chatOv = editingConfig != null ? editingConfig.chatEndpoint : null;
        String agentOv = editingConfig != null ? editingConfig.agentEndpoint : null;
        if (chatEndpointInput != null && chatOv != null) chatEndpointInput.setText(chatOv);
        if (agentEndpointInput != null && agentOv != null) agentEndpointInput.setText(agentOv);
        refreshEndpointResolveHint();
    }

    /** 把"现在会打哪个地址、来源是哪一层"如实显示出来（与引擎同一解析出口，不会各说各话） */
    private void refreshEndpointResolveHint() {
        // 先做形态校验再谈预览：一个少斜杠的值（"responses"）确实能"解析"出一个地址
        // （https://…/v1responses），但那正是要拦的错误，把它当成正常预览显示出来等于给它背书。
        String chatError = com.oilquiz.app.ai.model.ProviderConfigManager
                .endpointOverrideError("chat ", textOf(chatEndpointInput));
        String agentError = com.oilquiz.app.ai.model.ProviderConfigManager
                .endpointOverrideError("agent ", textOf(agentEndpointInput));
        markEndpointError(chatEndpointInput, chatError);
        markEndpointError(agentEndpointInput, agentError);

        if (endpointResolveHint == null) return;
        try {
            String url = urlInput == null ? "" : urlInput.getText().toString().trim();
            if (url.isEmpty()) { endpointResolveHint.setText(""); return; }
            if (chatError != null || agentError != null) {
                endpointResolveHint.setText(chatError != null && agentError != null
                        ? chatError + "\n" + agentError
                        : (chatError != null ? chatError : agentError));
                return;
            }
            com.oilquiz.app.ai.model.ProviderConfigManager pcm =
                    com.oilquiz.app.ai.model.ProviderConfigManager.get();
            String model = selectedModelId;
            com.oilquiz.app.ai.model.ProviderConfigManager.ResolvedEndpoint chat =
                    pcm.resolve(url, "chat", model, textOf(chatEndpointInput));
            com.oilquiz.app.ai.model.ProviderConfigManager.ResolvedEndpoint agent =
                    pcm.resolve(url, "agent", model, textOf(agentEndpointInput));
            endpointResolveHint.setText("实际解析：chat " + chat.describe()
                    + "；agent " + agent.describe()
                    + agentFlowSuffix(pcm, url, model, agent));
        } catch (Throwable t) {
            endpointResolveHint.setText("端点解析预览失败：" + t.getMessage());
        }
    }

    /**
     * 预览末尾那句"到底走哪条流"。
     *
     * <p>它必须与引擎同一判据，而不是只看端点像不像 {@code /responses}：配置表里
     * {@code services.agent.endpoint} 现在给多家<b>登记</b>了 /responses（方舟/阶跃/MiMo/百炼…），
     * 但放量总闸是 {@code services.agent.protocol}，另有 {@code enabled} 与本条勾选三道门。
     * 只看端点形态就会出现"预览说走 Responses、请求照样打 chat"——正是这次收口要消灭的两套事实。</p>
     */
    private String agentFlowSuffix(com.oilquiz.app.ai.model.ProviderConfigManager pcm,
                                   String url, String model,
                                   com.oilquiz.app.ai.model.ProviderConfigManager.ResolvedEndpoint agent) {
        boolean agentChecked = svcAgent == null || svcAgent.isChecked();
        boolean routed = agentChecked
                && pcm.hasService(url, "agent", model)
                && com.oilquiz.app.ai.model.ProviderConfigManager.agentOnResponses(
                        textOf(agentEndpointInput), pcm.agentUsesResponses(url, model));
        if (routed) return " → Agent/工具流走 Responses";
        if (agent.isResponses()) {
            // 端点确实登记成 /responses，只是还没开闸：把"为什么不走"说清楚，别让人以为填了就用
            return " → 仍走 chat 工具流（/responses 只是登记，未过 enabled/protocol 总闸）";
        }
        return " → 走 chat 工具流";
    }

    /**
     * 给端点输入框挂/撤行内错误。与 {@link #refreshEndpointResolveHint()} 和保存拦截
     * 共用同一条判据（{@code ProviderConfigManager.isEndpointOverrideValid}），
     * 避免出现"框里红着却照样能保存"或反过来的两套事实。
     */
    private void markEndpointError(EditText field, String error) {
        if (field == null) return;
        try {
            field.setError(error);
        } catch (Throwable t) {
            // setError 需要窗口已 attach；预览失败不该把整个对话框带崩
            AILogger.d("OnlineModelConfigDialog",
                    "端点行内错误提示未显示: " + t.getMessage());
        }
    }

    private static String textOf(EditText e) {
        return e == null ? null : OnlineModelManager.blankToNull(e.getText().toString());
    }

    /** 按 API 地址反查服务商配置表，把「服务类型」下拉显示成对应的服务名（未识别则留空） */
    private void backfillServiceType(String apiUrl) {
        if (serviceTypeSpinner == null) return;
        com.oilquiz.app.ai.model.ProviderConfigManager.Provider p =
                com.oilquiz.app.ai.model.ProviderConfigManager.get().matchByUrl(apiUrl);
        // 第二个参数 false = 不触发过滤：否则这段文本会被当成用户输入去过滤下拉项
        serviceTypeSpinner.setText(p != null ? p.name : "", false);
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
        costInput = dialog.findViewById(R.id.input_cost_per_million);
        protocolHint = dialog.findViewById(R.id.text_protocol_hint);
        dialogTitle = dialog.findViewById(R.id.text_dialog_title);
        servicesTitle = dialog.findViewById(R.id.text_services_title);
        protocolSpinner = dialog.findViewById(R.id.spinner_protocol);
        chatEndpointInput = dialog.findViewById(R.id.input_chat_endpoint);
        agentEndpointInput = dialog.findViewById(R.id.input_agent_endpoint);
        endpointResolveHint = dialog.findViewById(R.id.text_endpoint_resolve_hint);
        // 能力勾选即视为用户手动设置（保存后配置表刷新不再覆盖）
        android.widget.CompoundButton.OnCheckedChangeListener userSet =
                (b, c) -> { if (!programmaticCapabilityChange) capabilitiesUserTouched = true; };
        if (svcAgent != null) { svcAgent.setOnCheckedChangeListener(userSet); }
        if (svcVision != null) { svcVision.setOnCheckedChangeListener(userSet); }
        if (svcWebSearch != null) { svcWebSearch.setOnCheckedChangeListener(userSet); }
        if (svcEmbedding != null) { svcEmbedding.setOnCheckedChangeListener(userSet); }
        if (svcRerank != null) { svcRerank.setOnCheckedChangeListener(userSet); }
        if (svcImageGen != null) { svcImageGen.setOnCheckedChangeListener(userSet); }
        if (svcFunctionCalling != null) { svcFunctionCalling.setOnCheckedChangeListener(userSet); }
        if (svcTts != null) { svcTts.setOnCheckedChangeListener(userSet); }
        if (svcAsr != null) { svcAsr.setOnCheckedChangeListener(userSet); }

        // 模型名手动输入框：内容变化时同步 selectedModelId（与列表点选/编辑回填保持一致）
        if (selectedModelInput != null) {
            selectedModelInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    if (s == null) return;
                    selectedModelId = s.toString().trim();
                    // 手输也要让列表的选中态跟着走：此前只改了内部字段，radio 仍停在上一次点选的行，
                    // 用户看到的是"我填了 A、列表里亮着 B"
                    if (modelsAdapter != null) {
                        modelsAdapter.setSelectedModel(selectedModelId);
                    }
                    refreshModelsTitle();
                }
            });
        }

        // 单价：一旦被用户手动改过，程序回填（换服务商、URL 逐字变化）就不得再覆盖它
        if (costInput != null) {
            costInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    if (!programmaticCostChange) costUserTouched = true;
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
                refreshModelsTitle();
            });
        modelsRecycler.setLayoutManager(new LinearLayoutManager(context));
        modelsRecycler.setAdapter(modelsAdapter);
    }

    /**
     * 「可用模型」标题的**唯一写者**：数量、数据来源（本次拉取/缓存）、已选模型的展示名与实际 id 一次成文。
     *
     * <p>此前这里叠了两个毛病：{@code loadCachedModels} 直接 {@code setText("可用模型 (N) - 缓存")}
     * 把"已选"覆盖掉，而 {@code fetchModels} 只在**未选中**时才写数量 —— 结果"数量"和"已选"
     * 永远只显示一个，编辑已有配置时更是整条"已选"都不出现。收成单一写者后，调用方不必再操心顺序。</p>
     *
     * <p>展示名与 id 必须同时可见：官方 {@code GET /models} 的 {@code name} 是展示名，
     * 与 id 不同（{@code deepseek-flash} ↔ {@code DeepSeek-V4.1-Flash}），而请求只认 id。
     * 只显示其一都会让人以为"选错了模型"。</p>
     */
    private void refreshModelsTitle() {
        if (modelsTitle == null) return;
        int n = fetchedModels != null ? fetchedModels.size() : 0;
        StringBuilder sb = new StringBuilder("可用模型");
        if (n > 0) {
            sb.append(" (").append(n).append(")");
            if (modelsFromCache) sb.append(" · 缓存");
        }
        if (selectedModelId != null && !selectedModelId.isEmpty()) {
            String display = findDisplayNameInFetched(selectedModelId);
            sb.append(display != null && !display.equals(selectedModelId)
                    ? " —— 已选：" + display + "（" + selectedModelId + "）"
                    : " —— 已选：" + selectedModelId);
        } else {
            sb.append(" —— 尚未选择");
        }
        modelsTitle.setText(sb.toString());
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

        // 服务能力/单价的**回填**只在服务商变化时做一次（见 applyServiceDefaults）；
        // 只读的协议与上下文说明随每次地址变化刷新
        applyServiceDefaults(url, editingConfig);
        applyCapabilityMeta(url, editingConfig);
        refreshEndpointResolveHint();
    }

    /**
     * 服务能力勾选 + 单价回填。
     *
     * <p><b>门控在"服务商是否变化"上，而不是每次地址变化都做</b>：本方法挂在 API 地址输入框的
     * TextWatcher 链上，此前每敲一个字符都会把 9 个能力勾选重写成**存档值**、把单价重写成
     * 存档值或空串。于是"勾完复选框又回头给地址补个斜杠"→ 刚才的勾选当场被吃掉；
     * 新建时填好单价再动一下地址 → 单价被清空。保存的自然是被覆盖前的旧值，
     * 用户看到的现象就是"改了没生效"。</p>
     *
     * <p>规则（新建与编辑一致）：① 同一服务商内不重置任何用户输入；② 换服务商才重新预置；
     * ③ 用户已手动改过的能力勾选或单价，任何情况下都不再被程序覆盖。</p>
     */
    private void applyServiceDefaults(String url, com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig config) {
        if (servicesSection == null || url == null || url.isEmpty()) return;
        servicesSection.setVisibility(View.VISIBLE);
        // agent / 工具调用 属于端点事实（服务商有没有 /responses、有没有工具端点），
        // 一旦让用户勾选，保存就会置 capabilitiesUserSet 从此屏蔽配置表刷新 —— 这正是
        // "表里已放量、存的却还是旧值"的成因，所以这两项锁成只读。
        lockEndpointCapabilities();

        String providerKey = providerKeyOf(url);
        boolean providerChanged = !providerKey.equals(lastPrefillProviderKey);
        lastPrefillProviderKey = providerKey;
        if (!providerChanged) return;

        // 本方法内的勾选与填值均为程序化回填：置标志位，避免触发"用户手动调整"标记
        programmaticCapabilityChange = true;
        programmaticCostChange = true;
        try {
            if (!capabilitiesUserTouched) {
                com.oilquiz.app.ai.model.ProviderConfigManager pcm =
                        com.oilquiz.app.ai.model.ProviderConfigManager.get();
                if (config != null) {
                    // 编辑模式：偏好项用已保存值；但 agent / 工具调用 是**端点事实**，
                    // 由 providers.json 的 services 决定 —— 用存档值回填会把"表已更新"这件事
                    // 藏起来（实测 deepseek 的 services.agent 早已放量，存的却是旧的 false）
                    boolean knownProvider = pcm.matchByUrl(url) != null;
                    svcAgent.setChecked(knownProvider
                            ? pcm.supportsModelCapability(url, config.modelName, "agent")
                            : config.supportsAgent);
                    svcFunctionCalling.setChecked(knownProvider
                            ? pcm.supportsModelCapability(url, config.modelName, "functionCalling")
                            : config.supportsFunctionCalling);
                    svcVision.setChecked(config.supportsVision);
                    svcWebSearch.setChecked(config.supportsWebSearch);
                    svcEmbedding.setChecked(config.supportsEmbedding);
                    svcRerank.setChecked(config.supportsRerank);
                    svcImageGen.setChecked(config.supportsImageGen);
                    svcTts.setChecked(config.supportsTts);
                    svcAsr.setChecked(config.supportsAsr);
                } else {
                    // 新建模式：按配置表预置（未知服务商全不勾选，用户可手动开）
                    boolean known = pcm.matchByUrl(url) != null;
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
            }
            if (!costUserTouched && costInput != null) {
                costInput.setText(config != null && config.costPerMillionTokens > 0
                        ? String.format(java.util.Locale.US, "%.4f", config.costPerMillionTokens)
                        : "");
            }
        } finally {
            programmaticCapabilityChange = false;
            programmaticCostChange = false;
        }
    }

    /**
     * 把「Agent 接口」「工具调用」锁成只读，并在能力区标题里写明来源。
     *
     * <p>这两项描述的是服务商有没有 {@code /responses}、有没有工具端点 —— 是<b>端点事实</b>，
     * 不是用户偏好。放开了让用户勾，保存就会把 {@code capabilitiesUserSet} 置 true，
     * 从此整条配置的表刷新被屏蔽；实测正是这个链条让 deepseek 明明在配置表里已放量 responses，
     * 存的却还是 {@code supportsAgent=false}。只读之后，勾选值始终等于配置表现值，
     * 界面也不会给出"我能改"的错误暗示。</p>
     */
    private void lockEndpointCapabilities() {
        if (svcAgent != null) svcAgent.setEnabled(false);
        if (svcFunctionCalling != null) svcFunctionCalling.setEnabled(false);
        if (servicesTitle != null) {
            servicesTitle.setText("服务能力（「Agent 接口」「工具调用」由服务商配置表决定，不可手改）");
        }
    }

    /** 服务商身份键：同一个键视为"同一家"，其间的地址变化不重做能力/单价预置 */
    private String providerKeyOf(String url) {
        com.oilquiz.app.ai.model.ProviderConfigManager.Provider p =
                com.oilquiz.app.ai.model.ProviderConfigManager.get().matchByUrl(url);
        if (p == null) return "unknown";
        return p.id != null ? p.id : String.valueOf(p.name);
    }

    /**
     * 协议/上下文的只读说明（随地址变化刷新；纯展示，不写任何用户输入）。
     *
     * <p>协议与上下文窗口此前从来没有界面露出过 —— 用户无法知道自己注册的端点实际走
     * chat/completions 还是 /responses，也看不出上下文数值是 API 检测来的还是按配置表推断的
     * （可信度完全不同）。单价的回填不在这里：它是输入框，归 applyServiceDefaults 门控。</p>
     */
    private void applyCapabilityMeta(String url,
                                     com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig config) {
        if (protocolHint == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(com.oilquiz.app.ui.modelcenter.ModelCapabilitySummary.protocolLabel(url,
                    editingConfig != null ? editingConfig.modelName : selectedModelId));
        if (config != null) {
            sb.append(" · ").append(
                    com.oilquiz.app.ui.modelcenter.ModelCapabilitySummary.contextLabel(config));
        }
        sb.append("（协议由服务商配置表按地址判定，不可手改）");
        protocolHint.setText(sb.toString());
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
                // 程序补勾：必须避开"用户手动调整"标记，否则一次探测就把这条配置标成
                // capabilitiesUserSet=true，此后服务商配置表刷新永远不再覆盖它（口径见 saveConfig）
                programmaticCapabilityChange = true;
                try {
                    box.setChecked(true);
                } finally {
                    programmaticCapabilityChange = false;
                }
                capabilitiesProbed = true;
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
                    modelsFromCache = false;
                    modelsAdapter.updateData(models, selectedModelId);
                    modelsSection.setVisibility(View.VISIBLE);
                    refreshModelsTitle();
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
        // 扩展凭据只在**它当前可见**时才读取：端点类型决定显隐（讯飞/百度/火山各要其一），
        // 此前无条件取值意味着"把地址从讯飞改成 OpenAI 再保存"会把旧的 APISecret/AppID
        // 一起静默留在配置里 —— 界面上看不见，却照样加密落盘并被推理链按存在与否判断。
        String apiSecret = (apiSecretLayout != null
                && apiSecretLayout.getVisibility() == View.VISIBLE)
                ? apiSecretInput.getText().toString().trim() : "";
        String appId = (appIdLayout != null
                && appIdLayout.getVisibility() == View.VISIBLE)
                ? appIdInput.getText().toString().trim() : "";

        if (name.isEmpty()) {
            Toast.makeText(context, "请填写配置名称", Toast.LENGTH_SHORT).show();
            return;
        }
        if (url.isEmpty()) {
            Toast.makeText(context, "请填写 API 地址", Toast.LENGTH_SHORT).show();
            return;
        }
        // 注册即校验地址形态（此前 OnlineModelManager.validateConfig 里的 http/https 判定从未被 UI 调用，
        // 用户可保存垃圾地址直到运行期才失败）。与 OnlineModelManager.validateConfig 同一口径。
        String urlLower = url.toLowerCase();
        if (!urlLower.startsWith("http://") && !urlLower.startsWith("https://")) {
            Toast.makeText(context, "API 地址需以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show();
            return;
        }
        // 端点覆盖的形态校验：留空是合法状态（＝不覆盖，回落服务商配置表）；一旦写了值，就必须是
        // 以 / 开头的路径或完整 http(s):// 地址。少一个斜杠的 "responses" 会被 buildUrl 直接拼成
        // https://…/v1responses，而 protocolForEndpoint 还把它判成 openai —— 表现是"这条配置突然
        // 不能用了"，没有任何一行日志指向那个漏掉的斜杠。判据与上方实时提示是同一条，
        // 不会出现"框里红着却照样保存成功"。
        String chatEndpointOverride = textOf(chatEndpointInput);
        String agentEndpointOverride = textOf(agentEndpointInput);
        String chatEndpointError = com.oilquiz.app.ai.model.ProviderConfigManager
                .endpointOverrideError("chat ", chatEndpointOverride);
        String agentEndpointError = com.oilquiz.app.ai.model.ProviderConfigManager
                .endpointOverrideError("agent ", agentEndpointOverride);
        if (chatEndpointError != null || agentEndpointError != null) {
            Toast.makeText(context, chatEndpointError != null ? chatEndpointError : agentEndpointError,
                    Toast.LENGTH_LONG).show();
            markEndpointError(chatEndpointInput, chatEndpointError);
            markEndpointError(agentEndpointInput, agentEndpointError);
            EditText badField = chatEndpointError != null ? chatEndpointInput : agentEndpointInput;
            if (badField != null) badField.requestFocus();
            return;
        }
        if (key.isEmpty()) {
            Toast.makeText(context, "请填写 API 密钥", Toast.LENGTH_SHORT).show();
            return;
        }

        // 名称必须唯一：列表侧的「编辑/删除/启用/获取模型」全靠按 name 反查配置
        // （findByName 系列），两条同名时用户点其中一条的编辑，改的却是另一条。
        String selfId = editingConfig != null ? editingConfig.id : null;
        for (OnlineModelManager.OnlineModelConfig c : modelManager.getModelList()) {
            if (c == null) continue;
            if (selfId != null && selfId.equals(c.id)) continue;
            if (name.equals(c.name)) {
                Toast.makeText(context, "已存在同名配置「" + name + "」，请换一个名称", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // 设置选择的模型：优先手动输入框（列表未收录的免费模型等可直接填写），其次列表点选值。
        // 不再兜底成 "gpt-3.5-turbo"：该输入框此前藏在「可用模型」块里、没有列表时整块不可见，
        // 用户根本没机会填，却被静默存成一个多半不存在于该端点的模型名。
        String selectedModel;
        if (selectedModelInput != null && !selectedModelInput.getText().toString().trim().isEmpty()) {
            selectedModel = selectedModelInput.getText().toString().trim();
        } else if (selectedModelId != null && !selectedModelId.isEmpty()) {
            selectedModel = selectedModelId;
        } else {
            Toast.makeText(context, "请填写实际模型名（发给 API 的 id，或点「获取模型列表」选择）",
                    Toast.LENGTH_LONG).show();
            return;
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

        // 端点覆盖：留空即清除覆盖（回到"按服务商配置表"）。刻意不存协议 ——
        // 协议由端点推导，存两份就会出现"界面说 A、引擎打 B"。
        // 这里写的是上面已通过形态校验的那两个局部变量，不再回读输入框：
        // 校验与落盘必须是同一个值，否则中间任何一次重读都可能拿到被改过的文本。
        config.chatEndpoint = chatEndpointOverride;
        config.agentEndpoint = agentEndpointOverride;
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
            // 保存即视为"这条配置的能力已人为确定"（配置表刷新不再覆盖）。
            // 探测确认过的能力同等对待 —— 它比配置表的猜测更可信；但二者在代码里仍分开记录，
            // 不再让一次连通性探测冒充"用户手动设置"。
            config.capabilitiesUserSet = !isEditing || editingConfig.capabilitiesUserSet
                    || capabilitiesUserTouched || capabilitiesProbed;
        }
        // 单价：AttachmentProcessor 按它给同能力模型排性价比择优；空/非法按 0（无价）处理
        if (costInput != null) {
            String rawCost = costInput.getText().toString().trim().replace(",", "");
            config.costPerMillionTokens = rawCost.isEmpty()
                    ? 0 : parseCostOrZero(rawCost);
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

    /** 单价解析：非数字/负数一律按 0（=无价），不让一个手抖的输入毁掉整次保存 */
    private static double parseCostOrZero(String raw) {
        try {
            double v = Double.parseDouble(raw);
            return v > 0 ? v : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
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
                // 回写目标必须取注册表里**当前**那份对象，而不是这里捕获的引用：本回调在对话框
                // dismiss 之后才到达，期间用户完全可能又编辑了一次同一条配置，写旧引用会把那次编辑整体盖掉。
                OnlineModelManager.OnlineModelConfig current =
                        config.id != null ? modelManager.getModel(config.id) : null;
                if (current == null) {
                    return; // 期间该配置已被删除：丢弃这次检测结果
                }
                if (window != null && window > 0) {
                    current.contextWindow = window;
                    current.contextWindowFromApi = true;
                } else {
                    // API 未返回真实值：回退配置表推断，避免残留旧死值（如名称推断的 4096）
                    int inferred = OnlineModelManager.getContextWindowForModel(current.apiUrl, current.modelName);
                    if (inferred > 0 && inferred != current.contextWindow) {
                        current.contextWindow = inferred;
                    }
                    current.contextWindowFromApi = false;
                }
                modelManager.updateModelConfig(current);
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
                    org.json.JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) continue;
                    // 与写侧 ModelListFetcher.modelToCacheJson 互为逆运算：思考档位、默认档位、
                    // 输出上限、输入模态都必须还原。此前这里只认 id/name/contextLength，而保存时
                    // 又用这份残缺列表重新序列化覆盖 cachedModelsJson —— 打开编辑点一次保存，
                    // 档位选择器与 max_tokens 上限就没了，界面上却毫无痕迹。
                    ApiModel m = ModelListFetcher.modelFromCacheJson(obj);
                    if (m.id != null) {
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
            modelsFromCache = true;
            modelsAdapter.updateData(models, selectedModelId);
            modelsSection.setVisibility(View.VISIBLE);
            refreshModelsTitle();
        }
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }
}