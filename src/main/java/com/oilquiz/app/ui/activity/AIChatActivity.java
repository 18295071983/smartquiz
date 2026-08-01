package com.oilquiz.app.ui.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import com.google.android.material.button.MaterialButton;

import com.oilquiz.app.util.AILogger;
import com.oilquiz.app.util.QWeatherIconMapper;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModel;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.coordination.AIChatCoordinator;
import com.oilquiz.app.ai.chat.viewmodel.AIChatViewModel;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import com.oilquiz.app.ui.activity.QuestionGenerateActivity;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AIServiceState;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.ai.chat.AgentChatHandler;
import com.oilquiz.app.ai.chat.StreamingUpdateManager;
import com.oilquiz.app.ai.service.AIProcessingService;
import com.oilquiz.app.ai.tool.AITool;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.AIEntertainmentManager;
import com.oilquiz.app.ai.tool.AIWeatherManager;
import com.oilquiz.app.ai.tool.LocationTool;
import com.oilquiz.app.ai.util.ChatHistoryManager;
import com.oilquiz.app.ai.util.AttachmentManager;
import com.oilquiz.app.ai.bridge.ChatCommand;
import com.oilquiz.app.ai.bridge.ModelExecutionBridge;
import com.oilquiz.app.ai.bridge.BridgeCallback;
import com.oilquiz.app.ai.agent.AgentExecutionEngine;
import com.oilquiz.app.ai.agent.AgentExecutionState;
import com.oilquiz.app.ai.agent.ExecutionEvent;
import com.oilquiz.app.ai.agent.ExecutionEventListener;
import com.oilquiz.app.ai.agent.ToolGuideFlow;
import com.oilquiz.app.ai.agent.ToolContextProvider;
import com.oilquiz.app.ai.agent.CompositeGuideFlow;
import com.oilquiz.app.ai.agent.ToolResultStore;
import com.oilquiz.app.ai.agent.ToolResultInterpreter;
import com.oilquiz.app.ai.agent.ToolErrorRecovery;
import com.oilquiz.app.ai.agent.ToolPreChecker;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;
import com.oilquiz.app.ai.chat.ChatOrchestrator;
import com.oilquiz.app.ai.chat.NativeEventBridge;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.chat.ChatAdapter;
import com.oilquiz.app.ai.skill.SkillManager;
import com.oilquiz.app.ai.refactor.AIConfig;
import com.oilquiz.app.ai.refactor.CacheManager;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.refactor.AIInferenceCore;
import com.oilquiz.app.ai.callback.StreamCallback;
import com.oilquiz.app.ai.chat.ChatModeManager;
import com.oilquiz.app.ai.chat.ModeSelectorDialog;
import com.oilquiz.app.ai.stats.TokenStatsManager;
import com.oilquiz.app.ui.adapter.ChatHistoryAdapter;
import com.oilquiz.app.ui.adapter.ChatHistoryAdapter.ChatHistoryItem;
import com.oilquiz.app.ui.adapter.AttachmentAdapter;
import com.oilquiz.app.util.fileparser.FileContentExtractor;
import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.PermissionResourceProvider;
import com.oilquiz.app.ai.chat.status.ServiceStatusManager;
import com.oilquiz.app.ai.chat.ui.ChatDialogHelper;
import com.oilquiz.app.ai.chat.history.ChatHistoryController;
import com.oilquiz.app.ai.chat.weather.WeatherBannerController;
import com.oilquiz.app.ai.chat.recovery.NativeRecoveryHandler;
import com.oilquiz.app.ai.chat.input.ChatInputManager;
import com.oilquiz.app.ai.chat.input.AttachmentProcessor;
import com.oilquiz.app.ai.chat.lifecycle.GenerationLifecycleManager;
import com.oilquiz.app.ai.chat.streaming.StreamingTokenPipeline;
import com.oilquiz.app.ai.chat.processor.MessageProcessor;
import com.oilquiz.app.ai.chat.parser.OutputRouter;
import com.oilquiz.app.ai.chat.parser.StructuredOutput;
import com.oilquiz.app.ui.base.BaseActivity;

import androidx.drawerlayout.widget.DrawerLayout;
import androidx.activity.result.ActivityResultLauncher;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class AIChatActivity extends BaseActivity {

    private static final String TAG = "AIChatActivity";
    private static final int BATCH_TOKEN_COUNT = 20;
    private static final long BATCH_INTERVAL_MS = 50;

    private MaterialButton btnBack;
    private MaterialButton btnModeSelect;
    private MaterialButton btnModelSelect;
    private MaterialButton btnHistory;
    private MaterialButton btnClearChat;
    private MaterialButton btnStopGeneration;
    private MaterialButton btnLogViewer;
    private View serviceStatusBar;
    private TextView modelNameText;
    private TextView serviceStatusIcon;
    private TextView serviceStatusText;
    private android.widget.ProgressBar serviceStatusProgress;
    private TextView serviceStatusElapsed;
    private androidx.recyclerview.widget.RecyclerView messageList;
    private androidx.recyclerview.widget.RecyclerView attachmentList;
    private androidx.recyclerview.widget.RecyclerView historyList;
    private DrawerLayout drawerLayout;
    private EditText inputMessage;
    private MaterialButton btnSend;
    private MaterialButton btnAttach;
    private MaterialButton btnCloseHistory;
    private MaterialButton btnClearAllHistory;
    private View thinkingIndicator;
    private Chip chipNormalChat;
    private Chip chipAgentMode;
    private Chip chipThinkingAssist;
    private Chip chipWeather;
    private Chip chipClear;
    /** 自动获取的环境上下文（如定位得到的city/lat/lon），供引导流程注入 */
    private Map<String, String> autoContext = new HashMap<>();

    private View emptyStateView;
    private com.google.android.material.chip.ChipGroup emptyStateChips;

    private View weatherBanner;
    private TextView weatherIcon;
    private TextView weatherCity;
    private TextView weatherTemp;
    private TextView weatherDesc;
    private TextView weatherHumidity;
    private TextView weatherWind;
    private MaterialButton btnWeatherRefresh;
    private MaterialButton btnWeatherClose;
    private MaterialButton btnWeatherDetail;
    // weatherBannerVisible, weatherBannerCity, weatherBannerLat, weatherBannerLon 已移至 WeatherBannerController

    private AIChatCoordinator coordinator;
    private AIChatViewModel chatViewModel;
    private AIService aiService;
    private InferenceRouter inferenceRouter;
    private List<ChatMessage> chatHistory;
    private ChatAdapter chatAdapter;
    private ChatHistoryManager chatHistoryManager;
    private AttachmentManager attachmentManager;
    private ChatHistoryAdapter chatHistoryAdapter;
    private AttachmentAdapter attachmentAdapter;
    private FileContentExtractor fileContentExtractor;
    private AIToolManager aiToolManager;
    private AIEntertainmentManager aiEntertainmentManager;
    private AgentService agentService;
    private AgentChatHandler agentChatHandler;
    private ModelExecutionBridge modelBridge;
    private AgentExecutionEngine agentEngine;
    private SkillManager skillManager;
    private AIConfig aiConfig;
    private CacheManager cacheManager;
    private OnlineModelManager onlineModelManager;
    private AIWeatherManager weatherManager;
    private LocalBroadcastManager localBroadcastManager;
    private AIResultReceiver aiResultReceiver;
    private AITokenReceiver aiTokenReceiver;

    private volatile boolean isGenerating = false;
    private volatile boolean isDirectStreaming = false;
    private volatile StringBuilder currentStreamingContent = null;
    private volatile StringBuilder currentThinkingContent = null;
    private volatile boolean isInThinking = false;
    /** 标记上一轮思考已结束，用于在下一轮思考开始时插入分隔符 */
    private volatile boolean thinkingRoundEnded = false;
    /** 思考轮次计数（agent多轮迭代时递增） */
    private volatile int thinkingRoundCount = 0;
    private volatile Boolean lastUseOnlineModel = null;
    private volatile boolean isInTag = false;
    private volatile StringBuilder tagBuffer = null;
    // 流式 UI 更新节流：避免高频 token 导致主线程过载卡顿
    private static final long UI_UPDATE_THROTTLE_MS = 80;
    private volatile long lastTokenUiUpdateTime = 0;
    private volatile long lastThinkingUiUpdateTime = 0;
    private volatile int currentStreamingMessageIndex = -1;
    private volatile String currentStreamingMessageId = null;
    /** 当前Agent思考消息在chatHistory中的位置（-1表示无活跃思考消息） */
    private volatile int currentThinkingMessageIndex = -1;
    private volatile int agentToolLoopCount = 0;
    private final Object streamingLock = new Object();
    
    // isRecovering 和 pendingMessageForRecovery 已移至 NativeRecoveryHandler
    private volatile int recoveryProgressUpdateCount = 0;
    private static final int MAX_RECOVERY_FAILURES_NOTIFY = 3;

    private int tokenCountSinceLastUpdate = 0;
    private long lastUpdateTime = 0;
    private boolean isUpdateScheduled = false;
    private android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private StreamingUpdateManager streamingUpdateManager = null;
    private long totalTokensGenerated = 0;
    private long generationStartTime = 0;
    // isLoadingModel 和 loadingProgressMessageIndex 已移至 ServiceStatusManager
    // aiStatusObserver, loadingTimerRunnable 已移至 ServiceStatusManager
    private static final long LOADING_TIMER_INTERVAL_MS = 500;
    private ActivityResultLauncher<String[]> attachFileLauncher;
    private List<Uri> attachedFiles = new ArrayList<>();
    private List<ChatMessage.Attachment> currentAttachments = new ArrayList<>();

    // Modular components
    private ServiceStatusManager serviceStatusManager;
    private ChatDialogHelper dialogHelper;
    private ChatHistoryController historyController;
    private WeatherBannerController weatherBannerController;
    private NativeRecoveryHandler recoveryHandler;
    private ChatInputManager inputManager;
    private AttachmentProcessor attachmentProcessor;
    private GenerationLifecycleManager lifecycleManager;
    private com.oilquiz.app.ai.chat.parser.OutputRouter outputRouter;
    private StreamingTokenPipeline streamingPipeline;
    private MessageProcessor messageProcessor;

    private final android.content.ComponentCallbacks2 memoryCallback = new android.content.ComponentCallbacks2() {
        @Override
        public void onTrimMemory(int level) {
            if (aiService != null) {
                int result = com.oilquiz.app.ai.jni.LlamaHelper.handleMemoryPressure(level);
                if (result > 0) {
                    runOnUiThread(() -> addSystemMessage("内存紧张，已自动裁剪上下文"));
                } else if (result < 0) {
                    runOnUiThread(() -> addSystemMessage("内存严重不足，已释放模型资源"));
                }
            }
        }
        @Override
        public void onConfigurationChanged(android.content.res.Configuration newConfig) {}
        @Override
        public void onLowMemory() {
            if (aiService != null) {
                com.oilquiz.app.ai.jni.LlamaHelper.handleMemoryPressure(80);
            }
        }
    };

    private static final String[][] COMMAND_PATTERNS = {
        {"生成题目", "app_toolkit"},
        {"分析题目", "app_toolkit"},
        {"翻译", "translation"},
        {"学习计划", "app_toolkit"},
        {"统计", "app_toolkit"},
        {"搜索题目", "app_toolkit"},
        {"天气", "weather"},
        {"定位", "app_toolkit"},
        {"我的位置", "app_toolkit"},
        {"当前位置", "app_toolkit"},
        {"导入题目", "app_toolkit"},
        {"导出题目", "app_toolkit"},
        {"数据库操作", "database"},
        {"讲笑话", "entertainment"},
        {"猜谜语", "entertainment"},
        {"写诗", "entertainment"},
        {"讲故事", "entertainment"},
        {"知识问答", "entertainment"},
        {"名言", "entertainment"},
        {"游戏", "entertainment"},
    };

    @Override
    protected int getLayoutId() {
        return R.layout.activity_ai_chat;
    }

    @Override
    protected void initView() {
        try {
            registerComponentCallbacks(memoryCallback);
            btnBack = findViewById(R.id.btn_back);
            btnModeSelect = findViewById(R.id.btn_mode_select);
            btnModelSelect = findViewById(R.id.btn_model_select);
            btnHistory = findViewById(R.id.btn_history);
            btnClearChat = findViewById(R.id.btn_clear_chat);
            btnStopGeneration = findViewById(R.id.btn_stop_generation);
            btnLogViewer = findViewById(R.id.btn_log_viewer);
            modelNameText = findViewById(R.id.model_name);
            messageList = findViewById(R.id.message_list);
            attachmentList = findViewById(R.id.attachment_list);
            historyList = findViewById(R.id.history_list);
            drawerLayout = findViewById(R.id.drawer_layout);
            inputMessage = findViewById(R.id.input_message);
            btnSend = findViewById(R.id.btn_send);
            btnAttach = findViewById(R.id.btn_attach);
            btnCloseHistory = findViewById(R.id.btn_close_history);
            btnClearAllHistory = findViewById(R.id.btn_clear_all_history);
            thinkingIndicator = findViewById(R.id.thinking_indicator);
            chipNormalChat = findViewById(R.id.chip_normal_chat);
            chipAgentMode = findViewById(R.id.chip_agent_mode);
            chipThinkingAssist = findViewById(R.id.chip_thinking_assist);
            chipWeather = findViewById(R.id.chip_weather);
            chipClear = findViewById(R.id.chip_clear_chat2);

            // 快捷工具栏相关视图
            View quickBarHeader = findViewById(R.id.quick_bar_header);
            ChipGroup quickActionsChipGroup = findViewById(R.id.quick_actions_chip_group);
            ImageView ivQuickExpand = findViewById(R.id.iv_quick_expand);

            // 快捷工具栏折叠/展开功能（默认展开）
            final boolean[] isExpanded = {true};
            if (ivQuickExpand != null) {
                ivQuickExpand.setImageResource(R.drawable.ic_collapse);
            }
            if (quickBarHeader != null) {
                quickBarHeader.setOnClickListener(v -> {
                    isExpanded[0] = !isExpanded[0];
                    if (quickActionsChipGroup != null) {
                        quickActionsChipGroup.setVisibility(isExpanded[0] ? View.VISIBLE : View.GONE);
                    }
                    if (ivQuickExpand != null) {
                        ivQuickExpand.setImageResource(isExpanded[0] ? R.drawable.ic_collapse : R.drawable.ic_expand);
                    }
                });
            }

            emptyStateView = findViewById(R.id.empty_state_view);
            emptyStateChips = findViewById(R.id.empty_state_chips);

            weatherBanner = findViewById(R.id.weather_banner);
            weatherIcon = findViewById(R.id.weather_icon);
            weatherCity = findViewById(R.id.weather_city);
            weatherTemp = findViewById(R.id.weather_temp);
            weatherDesc = findViewById(R.id.weather_desc);
            weatherHumidity = findViewById(R.id.weather_humidity);
            weatherWind = findViewById(R.id.weather_wind);
            btnWeatherRefresh = findViewById(R.id.btn_weather_refresh);
            btnWeatherClose = findViewById(R.id.btn_weather_close);
            btnWeatherDetail = findViewById(R.id.btn_weather_detail);
            serviceStatusBar = findViewById(R.id.service_status_bar);
            serviceStatusIcon = findViewById(R.id.service_status_icon);
            serviceStatusText = findViewById(R.id.service_status_text);
            serviceStatusProgress = findViewById(R.id.service_status_progress);
            serviceStatusElapsed = findViewById(R.id.service_status_elapsed);
            
            if (serviceStatusBar != null) {
                serviceStatusBar.setOnClickListener(v -> showServiceStatusDetails());
            }

            messageList.setLayoutManager(new LinearLayoutManager(this));
            // 禁用 RecyclerView 的默认动画，避免消息更新时的闪烁
            androidx.recyclerview.widget.DefaultItemAnimator animator = new androidx.recyclerview.widget.DefaultItemAnimator();
            animator.setSupportsChangeAnimations(false);
            messageList.setItemAnimator(animator);
            chatHistory = new ArrayList<>();
            chatAdapter = new ChatAdapter(chatHistory, this::handleAction);
            chatAdapter.setRetryClickListener(messageId -> regenerateLastMessage());
            messageList.setAdapter(chatAdapter);

            setupKeyboardListener();
            updateEmptyState();

            if (btnLogViewer != null) {
                btnLogViewer.setOnClickListener(v -> startActivity(new Intent(AIChatActivity.this, LogViewerActivity.class)));
            }
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error initializing view: " + e.getMessage());
            showToast("界面初始化失败: " + e.getMessage());
            finish();
        }
    }

    @Override
    protected void initData() {
        try {
            // 0. 初始化协调器（统一管理所有数据源）
            coordinator = new AIChatCoordinator(this);
            coordinator.initialize();

            // 0.1 获取 Hilt 注入的 ViewModel
            chatViewModel = new ViewModelProvider(this).get(AIChatViewModel.class);
            chatViewModel.initialize();

            // 0.2 观察 ViewModel 的 LiveData
            observeViewModel();

            // 从协调器获取服务引用
            aiService = coordinator.getAIService();
            inferenceRouter = coordinator.getInferenceRouter();
            onlineModelManager = coordinator.getOnlineModelManager();
            chatHistoryManager = coordinator.getChatHistoryManager();
            aiConfig = coordinator.getAIConfig();

            // 创建模型执行桥接器 - UI与模型之间的唯一通道
            modelBridge = ModelExecutionBridge.getInstance(this, aiService, agentService, aiConfig);

            // 创建Agent执行引擎 - agent模式专用，后台执行独立于UI
            agentEngine = new AgentExecutionEngine(this, aiService, agentService, aiConfig);

            if (aiService == null) {
                showToast("AI服务初始化失败");
                return;
            }

            // 0.3 初始化输出路由器
            initOutputRouter();

            // 1. 初始化基础管理器（不依赖模块）
            attachmentManager = new AttachmentManager(this);
            fileContentExtractor = new FileContentExtractor(this);
            initAttachFileLauncher();

            aiToolManager = AIToolManager.getInstance(this);

            if (aiConfig.isAgentEnabled()) {
                agentService = AgentService.getInstance(this);
                initAgentChatHandler();
            }

            cacheManager = new CacheManager(this);
            skillManager = new SkillManager(this);
            weatherManager = new AIWeatherManager(this, AIWeatherManager.WeatherProvider.HEFENG);
            aiEntertainmentManager = new AIEntertainmentManager(this);

            // 初始化 Token 统计管理器
            TokenStatsManager.getInstance().registerCallback(tokenStatsCallback);

            // 更新模式按钮显示
            updateModeButtonText();

            localBroadcastManager = LocalBroadcastManager.getInstance(this);
            aiResultReceiver = new AIResultReceiver();
            localBroadcastManager.registerReceiver(aiResultReceiver, new IntentFilter(AIProcessingService.ACTION_AI_TASK_COMPLETED));
            aiTokenReceiver = new AITokenReceiver();
            localBroadcastManager.registerReceiver(aiTokenReceiver, new IntentFilter(AIProcessingService.ACTION_AI_TOKEN_UPDATE));

            // 2. 异步加载聊天历史 - 避免主线程 I/O
            new Thread(() -> {
                try {
                    if (chatHistoryManager != null) {
                        List<ChatMessage> loadedHistory = chatHistoryManager.loadAIChatHistory();
                        if (loadedHistory != null && !loadedHistory.isEmpty()) {
                            runOnUiThread(() -> {
                                chatHistory.addAll(loadedHistory);
                                if (chatAdapter != null) {
                                    chatAdapter.notifyDataSetChanged();
                                }
                                updateEmptyState();
                                // 滚动到最新消息
                                scrollToBottom(true);
                            });
                        } else {
                            // 历史为空，显示新手引导
                            runOnUiThread(() -> showWelcomeGuide());
                        }
                    }
                } catch (Exception e) {
                    AppLogger.aiE(TAG, "Error loading chat history: " + e.getMessage());
                }
            }).start();

            // 3. 初始化模块化组件（必须在使用模块之前）
            initModules();

            // 4. 现在可以安全地使用模块了
            updateModelNameDisplay();

        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error initializing data: " + e.getMessage());
            showToast("数据初始化失败: " + e.getMessage());
        }
    }

    /**
     * 观察 ViewModel 的 LiveData
     */
    private void observeViewModel() {
        if (chatViewModel == null) return;

        // 观察聊天消息变化
        chatViewModel.getChatMessages().observe(this, messages -> {
            if (messages != null && chatAdapter != null) {
                chatAdapter.notifyDataSetChanged();
                scrollToBottom();
            }
        });

        // 观察模型名称变化
        chatViewModel.getModelName().observe(this, modelName -> {
            updateModelNameDisplay();
        });

        // 观察生成状态变化
        chatViewModel.isGenerating().observe(this, isGenerating -> {
            if (isGenerating != null) {
                this.isGenerating = isGenerating;
                if (isGenerating) {
                    showLoading("正在思考...", null);
                } else {
                    hideLoading();
                }
            }
        });

        // 观察错误信息
        chatViewModel.getError().observe(this, error -> {
            if (error != null && !error.isEmpty()) {
                showToast(error);
            }
        });

        // 观察初始化状态
        chatViewModel.isInitialized().observe(this, initialized -> {
            if (initialized != null && initialized) {
                updateModelNameDisplay();
            }
        });
    }

    /**
     * 初始化输出路由器
     */
    private void initOutputRouter() {
        outputRouter = new OutputRouter(new OutputRouter.OutputHandler() {
            @Override
            public void onTextOutput(String text, boolean isComplete) {
                runOnUiThread(() -> {
                    if (currentStreamingContent != null && currentStreamingMessageIndex >= 0 
                        && currentStreamingMessageIndex < chatHistory.size()) {
                        currentStreamingContent.append(text);
                        ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                        msg.content = currentStreamingContent.toString();
                        if (chatAdapter != null) {
                            chatAdapter.updateAIMessageContent(currentStreamingMessageIndex, currentStreamingContent.toString());
                        }
                        scrollToBottom();
                    }
                });
            }

            @Override
            public void onThinkingStart() {
                runOnUiThread(() -> {
                    isInThinking = true;
                    if (currentThinkingContent == null) {
                        currentThinkingContent = new StringBuilder();
                    }
                    // 立即显示思考状态，让用户感知到模型在思考
                    if (currentThinkingContent.length() == 0 && currentStreamingMessageIndex >= 0
                            && currentStreamingMessageIndex < chatHistory.size()) {
                        currentThinkingContent.append("正在思考...");
                        ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                        msg.thinkingContent = currentThinkingContent.toString();
                        if (chatAdapter != null) {
                            chatAdapter.updateMessageThinkingContent(currentStreamingMessageIndex, currentThinkingContent.toString());
                        }
                    }
                });
            }

            @Override
            public void onThinkingContent(String content) {
                runOnUiThread(() -> {
                    if (currentThinkingContent != null) {
                        // 首次收到真实思考内容时，清掉占位的"正在思考..."
                        if (currentThinkingContent.toString().equals("正在思考...")) {
                            currentThinkingContent.setLength(0);
                        }
                        currentThinkingContent.append(content);
                        if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                            ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                            msg.thinkingContent = currentThinkingContent.toString();
                            if (chatAdapter != null) {
                                chatAdapter.updateMessageThinkingContent(currentStreamingMessageIndex, currentThinkingContent.toString());
                            }
                            // 思考内容更新时也跟随滚动（思考区域展开状态下）
                            if (msg.thinkingExpanded) {
                                scrollToBottom();
                            }
                        }
                    }
                });
            }

            @Override
            public void onThinkingEnd() {
                runOnUiThread(() -> {
                    isInThinking = false;
                    if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                        ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                        msg.thinkingContent = currentThinkingContent != null ? currentThinkingContent.toString() : "";
                        // 思考结束自动折叠，用户可点击重新展开
                        msg.thinkingExpanded = false;
                        if (chatAdapter != null) {
                            chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
                        }
                    }
                });
            }

            @Override
            public void onToolCall(String toolName, org.json.JSONObject parameters) {
                runOnUiThread(() -> {
                    showToast("工具调用: " + toolName);
                    // TODO: 显示工具调用 UI
                });
            }

            @Override
            public void onStructuredData(String dataType, org.json.JSONObject data) {
                runOnUiThread(() -> {
                    // 根据数据类型显示不同的 UI
                    if ("天气".equals(dataType)) {
                        // 显示天气卡片
                        showToast("收到天气数据");
                    } else if ("代码".equals(dataType)) {
                        // 显示代码块
                        showToast("收到代码数据");
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    showToast("错误: " + error);
                    if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                        ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                        msg.content = "错误: " + error;
                        msg.status = ChatMessage.MessageStatus.FAILED;
                        if (chatAdapter != null) {
                            chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
                        }
                    }
                });
            }

            @Override
            public void onStreamComplete(String fullContent) {
                runOnUiThread(() -> {
                    if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                        ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                        msg.content = fullContent;
                        msg.status = ChatMessage.MessageStatus.COMPLETED;
                        if (chatAdapter != null) {
                            chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
                        }
                    }
                    saveHistoryAsync();
                });
            }
        });
    }

    private void initModules() {
        // 1. ServiceStatusManager - 状态栏管理
        serviceStatusManager = new ServiceStatusManager(this, uiHandler, new ServiceStatusManager.Callback() {
            @Override public void onAddSystemMessage(String message, ChatMessage.SystemMessageType type) { addSystemMessage(message, type); }
            @Override public void onAddErrorMessage(String title, String detail, boolean withRetry) { addErrorMessage(title, detail, withRetry); }
            @Override public void onShowToast(String message) { showToast(message); }
            @Override public void onShouldUseOnlineModel() {}
            @Override public void onUpdateModelNameDisplay() { updateModelNameDisplay(); }
            @Override public void onHideLoading() { hideLoading(); }
        });
        // 绑定视图（带空检查）
        if (serviceStatusBar != null) {
            serviceStatusManager.bindViews(serviceStatusBar, serviceStatusIcon, serviceStatusText, serviceStatusProgress, serviceStatusElapsed, thinkingIndicator);
        }
        serviceStatusManager.setServices(aiService, inferenceRouter, chatHistory);
        serviceStatusManager.registerObserver();
        serviceStatusManager.updateInitialStatus();

        // 2. ChatDialogHelper - 对话框管理
        dialogHelper = new ChatDialogHelper(this, new ChatDialogHelper.Callback() {
            @Override public void onShowToast(String message) { showToast(message); }
            @Override public void onAddSystemMessage(String message) { addSystemMessage(message); }
            @Override public void onAddAIMessage(String content) { addAIMessage(content); }
            @Override public void onClearChat() { clearChat(); }
        });

        // 3. ChatHistoryController - 历史记录管理
        historyController = new ChatHistoryController(this, new ChatHistoryController.Callback() {
            @Override public void onClearChat() { clearChat(); }
            @Override public void onShowToast(String message) { showToast(message); }
        });
        if (drawerLayout != null && historyList != null) {
            historyController.init(drawerLayout, historyList);
            historyController.refresh(chatHistory);
        }

        // 4. WeatherBannerController - 天气横幅管理
        weatherBannerController = new WeatherBannerController(this, message -> showToast(message));
        // 绑定基本视图
        if (weatherBanner != null && weatherIcon != null && weatherCity != null 
            && weatherTemp != null && weatherDesc != null) {
            weatherBannerController.bindViews(weatherBanner, weatherIcon, weatherCity, weatherTemp, weatherDesc, weatherHumidity, weatherWind);
        }
        // 绑定详情区域
        View weatherDetailContainer = findViewById(R.id.weather_detail_container);
        TextView weatherFeelsLike = findViewById(R.id.weather_feels_like);
        TextView weatherWindDir = findViewById(R.id.weather_wind_dir);
        TextView weatherVisibility = findViewById(R.id.weather_visibility);
        TextView weatherPressure = findViewById(R.id.weather_pressure);
        if (weatherDetailContainer != null && weatherFeelsLike != null && weatherWindDir != null 
            && weatherVisibility != null && weatherPressure != null) {
            weatherBannerController.bindDetailViews(weatherDetailContainer, weatherFeelsLike,
                    weatherHumidity, weatherWind, weatherWindDir, weatherVisibility, weatherPressure);
        }

        // 5. NativeRecoveryHandler - 原生层恢复管理
        recoveryHandler = new NativeRecoveryHandler(this, uiHandler, new NativeRecoveryHandler.Callback() {
            @Override public void onRecoveryStarted(String message) { addSystemMessage(message); }
            @Override public void onRecoveryProgress(String message, int progress) { if (serviceStatusManager != null) serviceStatusManager.updateRecoveryProgress(message, progress); }
            @Override public void onRecoveryComplete(String message) { addSystemMessage(message); showToast("恢复完成"); }
            @Override public void onRecoveryFailed(String error) { addErrorMessage("恢复失败", error, true); }
            @Override public void onAddSystemMessage(String message) { addSystemMessage(message); }
            @Override public void onShowToast(String message) { showToast(message); }
            @Override public void onTriggerAutoRecovery() { recoveryHandler.triggerAutoRecovery(); }
        });
        recoveryHandler.setAIService(aiService);
        recoveryHandler.setupListener();

        // 6. ChatInputManager - 输入管理
        inputManager = new ChatInputManager(this, new ChatInputManager.Callback() {
            @Override public void onSendMessage(String text) { sendMessage(); }
            @Override public void onAttachFile() { handleAttachFile(); }
            @Override public void onShowToast(String message) { showToast(message); }
        });
        if (inputMessage != null && btnSend != null && btnAttach != null && attachmentList != null) {
            inputManager.init(inputMessage, btnSend, btnAttach, attachmentList);
        }

        // 7. AttachmentProcessor - 附件处理
        attachmentProcessor = new AttachmentProcessor(this);

        // 8. GenerationLifecycleManager - 生成生命周期管理
        lifecycleManager = new GenerationLifecycleManager(this, uiHandler, new GenerationLifecycleManager.Callback() {
            @Override public void onShowThinkingIndicator() { showLoading("正在思考...", null); }
            @Override public void onHideThinkingIndicator() { hideLoading(); }
            @Override public void onShowStopButton() { if (btnStopGeneration != null) btnStopGeneration.setVisibility(View.VISIBLE); }
            @Override public void onHideStopButton() { if (btnStopGeneration != null) btnStopGeneration.setVisibility(View.GONE); }
            @Override public void onUpdateMessageContent(int index, String content) { if (chatAdapter != null && index >= 0 && index < chatHistory.size()) { chatHistory.get(index).content = content; chatAdapter.notifyItemChanged(index); } }
            @Override public void onUpdateMessageThinking(int index, String thinkingContent) {}
            @Override public void onAddAIMessage(ChatMessage message) { chatHistory.add(message); if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1); scrollToBottom(true); }
            @Override public void onAddSystemMessage(String message) { addSystemMessage(message); }
            @Override public void onScrollToBottom() { scrollToBottom(); }
            @Override public void onSaveHistoryAsync() { saveHistoryAsync(); }
            @Override public void onShowToast(String message) { showToast(message); }
        });

        // 9. StreamingTokenPipeline - 流式Token处理管道
        streamingPipeline = new StreamingTokenPipeline(new StreamingTokenPipeline.TokenListener() {
            @Override public void onContentToken(String token) { lifecycleManager.handleToken(token); }
            @Override public void onThinkingToken(String token) {}
            @Override public void onToolCall(String toolCallData) {}
            @Override public void onGenerationComplete(String fullContent) {}
        });

        // 10. MessageProcessor - 消息处理器
        messageProcessor = new MessageProcessor(new MessageProcessor.Callback() {
            @Override public void onCacheHit(String cachedResponse) {
                addAIMessage(cachedResponse);
                addSystemMessage("(来自缓存)");
            }
            @Override public void onSkillMatched(String skillPrompt) {
                // 技能匹配处理
            }
            @Override public void onLocalModelCall(String prompt) {
                processChatMessage(prompt);
            }
            @Override public void onOnlineModelCall(String prompt) {
                processChatMessageWithOnlineModel(prompt);
            }
            @Override public void onToolExecution(String toolName, String params) {
                executeTool(toolName, params);
            }
            @Override public void onEntertainmentRequest(String type) {
                executeEntertainment(type, "");
            }
            @Override public void onUnknownCommand(String command) {
                addSystemMessage("未知命令: " + command);
            }
        });
        if (aiService != null && inferenceRouter != null && cacheManager != null && skillManager != null) {
            messageProcessor.setServices(aiService, inferenceRouter, cacheManager, skillManager);
        }
    }

    @Override
    protected void initListener() {
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (btnModeSelect != null) btnModeSelect.setOnClickListener(v -> showModeSelectorDialog());
        if (btnModelSelect != null) {
            btnModelSelect.setOnClickListener(v -> {
                // 打开模型选择页面
                Intent intent = new Intent(AIChatActivity.this, ModelSelectorActivity.class);
                startActivity(intent);
            });
        }
        if (btnClearChat != null) btnClearChat.setOnClickListener(v -> clearChat());
        if (btnStopGeneration != null) btnStopGeneration.setOnClickListener(v -> stopGeneration());
        if (btnSend != null) btnSend.setOnClickListener(v -> sendMessage());
        if (btnAttach != null) btnAttach.setOnClickListener(v -> handleAttachFile());

        if (btnHistory != null) {
            btnHistory.setOnClickListener(v -> {
                if (drawerLayout != null && historyController != null) {
                    historyController.refresh(chatHistory);
                    drawerLayout.openDrawer(findViewById(R.id.history_drawer));
                }
            });
        }
        if (btnCloseHistory != null) {
            btnCloseHistory.setOnClickListener(v -> {
                if (drawerLayout != null) drawerLayout.closeDrawer(findViewById(R.id.history_drawer));
            });
        }
        if (btnClearAllHistory != null) {
            btnClearAllHistory.setOnClickListener(v -> { 
                clearChat(); 
                if (historyController != null) historyController.refresh(chatHistory); 
                if (drawerLayout != null) drawerLayout.closeDrawer(findViewById(R.id.history_drawer)); 
                showToast("已清空"); 
            });
        }

        // 普通对话入口
        if (chipNormalChat != null) chipNormalChat.setOnClickListener(v -> {
            animateModeSwitch(() -> {
                ChatModeManager.ChatMode oldMode = ChatModeManager.getInstance(this).getCurrentMode();
                ChatModeManager.getInstance(this).setManualMode(ChatModeManager.ChatMode.NORMAL);
                updateModeButtonText();
                // 注入模式切换指令到上下文
                injectModeSwitchInstruction(oldMode, ChatModeManager.ChatMode.NORMAL);
                showToast("已切换到普通对话模式");
            });
        });

        // Agent 功能入口
        if (chipAgentMode != null) chipAgentMode.setOnClickListener(v -> {
            // 添加模式切换动画
            animateModeSwitch(() -> {
                ChatModeManager.ChatMode oldMode = ChatModeManager.getInstance(this).getCurrentMode();
                ChatModeManager.getInstance(this).setManualMode(ChatModeManager.ChatMode.AGENT);
                updateModeButtonText();
                // 注入模式切换指令到上下文
                injectModeSwitchInstruction(oldMode, ChatModeManager.ChatMode.AGENT);
                addSystemMessage("🤖 Agent模式已启用\n\n功能特性：\n• 工具智能选择与执行\n• ReAct推理循环\n• 思考链可视化\n• 快捷输入引导\n\n请点击下方工具快捷按钮或直接输入问题。");
            });
        });
        if (chipWeather != null) chipWeather.setOnClickListener(v -> showToolGuideDialog("ai_weather"));

        if (chipClear != null) chipClear.setOnClickListener(v -> {
            clearChat();
        });

        // 工具快捷输入引导：点击chip弹出参数引导表单
        Chip chipSearch = findViewById(R.id.chip_search);
        Chip chipTranslate = findViewById(R.id.chip_translate);
        Chip chipDatabase = findViewById(R.id.chip_database);
        Chip chipFile = findViewById(R.id.chip_file);
        Chip chipLocation = findViewById(R.id.chip_location);
        Chip chipApp = findViewById(R.id.chip_app);
        Chip chipCalc = findViewById(R.id.chip_calc);

        if (chipSearch != null) chipSearch.setOnClickListener(v -> showToolGuideDialog("network_search"));
        if (chipTranslate != null) chipTranslate.setOnClickListener(v -> showToolGuideDialog("translation"));
        if (chipDatabase != null) chipDatabase.setOnClickListener(v -> showToolGuideDialog("database"));
        if (chipFile != null) chipFile.setOnClickListener(v -> showToolGuideDialog("file"));
        if (chipLocation != null) chipLocation.setOnClickListener(v -> showToolGuideDialog("location"));
        if (chipApp != null) chipApp.setOnClickListener(v -> showToolGuideDialog("app_operation"));
        if (chipCalc != null) chipCalc.setOnClickListener(v -> showToolGuideDialog("app_toolkit"));

        // 动态新增聚合方案入口 chip（出行准备🚗/学习查询📚/网页研究🔍），添加到快捷按钮 ChipGroup
        com.google.android.material.chip.ChipGroup quickGroup = findViewById(R.id.quick_actions_chip_group);
        if (quickGroup != null) {
            addCompositeChip(quickGroup, "🚗 出行准备", "go_out");
            addCompositeChip(quickGroup, "📚 学习查询", "study");
            addCompositeChip(quickGroup, "🔍 网页研究", "research");
        }

        // 模式切换快捷按钮
        Chip chipDeepThink = findViewById(R.id.chip_deep_think);
        Chip chipCreative = findViewById(R.id.chip_creative);
        
        if (chipDeepThink != null) chipDeepThink.setOnClickListener(v -> {
            animateModeSwitch(() -> {
                ChatModeManager.ChatMode oldMode = ChatModeManager.getInstance(this).getCurrentMode();
                ChatModeManager.getInstance(this).setManualMode(ChatModeManager.ChatMode.DEEP_THINKING);
                updateModeButtonText();
                injectModeSwitchInstruction(oldMode, ChatModeManager.ChatMode.DEEP_THINKING);
                showToast("已切换到深度思考模式");
            });
        });

        if (chipCreative != null) chipCreative.setOnClickListener(v -> {
            animateModeSwitch(() -> {
                ChatModeManager.ChatMode oldMode = ChatModeManager.getInstance(this).getCurrentMode();
                ChatModeManager.getInstance(this).setManualMode(ChatModeManager.ChatMode.CREATIVE);
                updateModeButtonText();
                injectModeSwitchInstruction(oldMode, ChatModeManager.ChatMode.CREATIVE);
                showToast("已切换到创意写作模式");
            });
        });

        if (chipThinkingAssist != null) chipThinkingAssist.setOnClickListener(v -> {
            animateModeSwitch(() -> {
                ChatModeManager.ChatMode oldMode = ChatModeManager.getInstance(this).getCurrentMode();
                ChatModeManager.getInstance(this).setManualMode(ChatModeManager.ChatMode.THINKING_ASSIST);
                updateModeButtonText();
                injectModeSwitchInstruction(oldMode, ChatModeManager.ChatMode.THINKING_ASSIST);
                showToast("已切换到思考辅助模式");
            });
        });

        // 空状态快捷操作
        if (emptyStateChips != null) {
            com.google.android.material.chip.Chip chipExample1 = emptyStateChips.findViewById(R.id.chip_empty_example1);
            com.google.android.material.chip.Chip chipExample2 = emptyStateChips.findViewById(R.id.chip_empty_example2);
            com.google.android.material.chip.Chip chipExample3 = emptyStateChips.findViewById(R.id.chip_empty_example3);
            com.google.android.material.chip.Chip chipClearEmpty = emptyStateChips.findViewById(R.id.chip_clear_chat);
            if (chipExample1 != null) chipExample1.setOnClickListener(v -> {
                inputMessage.setText("帮我总结这段文字");
                sendMessage();
            });
            if (chipExample2 != null) chipExample2.setOnClickListener(v -> {
                inputMessage.setText("解释这段代码");
                sendMessage();
            });
            if (chipExample3 != null) chipExample3.setOnClickListener(v -> {
                inputMessage.setText("今天天气如何");
                sendMessage();
            });
            if (chipClearEmpty != null) chipClearEmpty.setOnClickListener(v -> {
                clearChat();
            });
        }

        if (btnWeatherRefresh != null) btnWeatherRefresh.setOnClickListener(v -> { if (weatherBannerController != null) weatherBannerController.loadWeather(true); });
        if (btnWeatherClose != null) btnWeatherClose.setOnClickListener(v -> { if (weatherBannerController != null) weatherBannerController.hide(); });
        if (btnWeatherDetail != null) btnWeatherDetail.setOnClickListener(v -> { if (weatherBannerController != null) weatherBannerController.toggleDetail(); });
        if (weatherBanner != null) {
            weatherBanner.setOnClickListener(v -> {
                if (weatherBannerController == null) return;
                Intent intent = new Intent(AIChatActivity.this, WeatherDetailActivity.class);
                intent.putExtra("city", weatherBannerController.getCurrentCity());
                if (weatherBannerController.getCurrentLat() != 0 && weatherBannerController.getCurrentLon() != 0) { intent.putExtra("lat", weatherBannerController.getCurrentLat()); intent.putExtra("lon", weatherBannerController.getCurrentLon()); }
                startActivity(intent);
            });
        }

        inputMessage.setOnEditorActionListener((v, actionId, event) -> { sendMessage(); return true; });
        
        // 长按输入框显示更多选项
        inputMessage.setOnLongClickListener(v -> {
            if (dialogHelper != null) dialogHelper.showInputOptions(inputMessage);
            return true;
        });
    }

    private void cancelGeneration() {
        try {
            if (agentChatHandler != null && agentChatHandler.isGenerating()) agentChatHandler.cancel();
            if (modelBridge != null) modelBridge.execute(ChatCommand.stopGeneration(), null);
            isGenerating = false;
            isDirectStreaming = false;
            hideLoadingUI();
            showToast("操作已取消");
            addSystemMessage("生成已取消");
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error cancelling: " + e.getMessage());
        }
    }

    /**
     * 快捷输入引导：填充模板到输入框并聚焦，光标置于末尾
     */
    private void fillQuickInput(String template) {
        if (inputMessage == null) return;
        inputMessage.setText(template);
        inputMessage.requestFocus();
        inputMessage.setSelection(template.length());
        // 弹出软键盘
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
            getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(inputMessage, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    }

    /** dp 转 px 工具方法，供引导流程动态构建 UI 使用 */
    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 显示工具引导对话框：多步骤向导式UI，一次只展示一个步骤
     */
    private void showToolGuideDialog(String toolName) {
        ToolGuideFlow flow = ToolGuideFlow.getFlow(toolName);
        if (flow == null) {
            android.widget.Toast.makeText(this, "暂未提供该工具的引导流程", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // 每次新开工具引导流程，清空上一轮自动获取的上下文
        autoContext.clear();

        // 状态管理
        final Map<String, String> selectedParams = new HashMap<>();
        final int[] currentStepIdx = {0};
        @SuppressWarnings("unchecked")
        final List<ToolGuideFlow.GuideStep>[] activeStepsHolder = new List[]{flow.getActiveSteps(selectedParams)};

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
            new com.google.android.material.bottomsheet.BottomSheetDialog(this);

        renderStep(dialog, flow, toolName, selectedParams, currentStepIdx, activeStepsHolder);
        dialog.show();
    }

    /**
     * 渲染当前向导步骤。根据步骤类型（OPTION/INPUT/CONFIRM）动态构建内容并 setContentView。
     */
    @SuppressWarnings("unchecked")
    private void renderStep(final com.google.android.material.bottomsheet.BottomSheetDialog dialog,
                            final ToolGuideFlow flow, final String toolName,
                            final Map<String, String> selectedParams,
                            final int[] currentStepIdx,
                            final List<ToolGuideFlow.GuideStep>[] activeStepsHolder) {
        List<ToolGuideFlow.GuideStep> activeSteps = activeStepsHolder[0];

        // 重新计算后若索引超出范围，跳到最后一个 CONFIRM 步骤
        if (currentStepIdx[0] >= activeSteps.size()) {
            int confirmIdx = -1;
            for (int i = activeSteps.size() - 1; i >= 0; i--) {
                if (activeSteps.get(i).type == ToolGuideFlow.GuideStep.StepType.CONFIRM) {
                    confirmIdx = i;
                    break;
                }
            }
            if (confirmIdx >= 0) {
                currentStepIdx[0] = confirmIdx;
            } else {
                dialog.dismiss();
                return;
            }
        }

        final ToolGuideFlow.GuideStep step = activeSteps.get(currentStepIdx[0]);

        // 内容容器
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 48, 48, 48);

        // 顶部：工具名 + 步骤进度 + 上一步按钮（非第一步才显示）
        LinearLayout headerLayout = new LinearLayout(this);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText(flow.toolDisplayName + "  步骤 " + (currentStepIdx[0] + 1) + "/" + activeSteps.size());
        titleView.setTextSize(16);
        titleView.setTextColor(0xFF333333);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleView.setLayoutParams(titleParams);
        headerLayout.addView(titleView);

        if (currentStepIdx[0] > 0) {
            android.widget.Button prevBtn = new android.widget.Button(this);
            prevBtn.setText("上一步");
            prevBtn.setBackgroundColor(0xFFEEEEEE);
            prevBtn.setTextColor(0xFF666666);
            LinearLayout.LayoutParams prevParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            prevBtn.setLayoutParams(prevParams);
            prevBtn.setOnClickListener(v -> {
                if (currentStepIdx[0] > 0) currentStepIdx[0]--;
                // 重新计算 activeSteps，保留已选参数
                activeStepsHolder[0] = flow.getActiveSteps(selectedParams);
                if (currentStepIdx[0] < 0) currentStepIdx[0] = 0;
                renderStep(dialog, flow, toolName, selectedParams, currentStepIdx, activeStepsHolder);
            });
            headerLayout.addView(prevBtn);
        }
        container.addView(headerLayout);

        // 分隔线
        View divider = new View(this);
        divider.setBackgroundColor(0xFFE0E0E0);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divParams.topMargin = 16;
        divParams.bottomMargin = 16;
        divider.setLayoutParams(divParams);
        container.addView(divider);

        // 上下文提示条：若已有自动获取的上下文（如定位），在步骤内容顶部展示蓝色提示条
        if (!autoContext.isEmpty()) {
            LinearLayout ctxBar = new LinearLayout(this);
            ctxBar.setOrientation(LinearLayout.HORIZONTAL);
            ctxBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
            ctxBar.setPadding(dp(12), dp(10), dp(12), dp(10));
            android.graphics.drawable.GradientDrawable ctxBg = new android.graphics.drawable.GradientDrawable();
            ctxBg.setColor(0xFFE3F2FD);
            ctxBg.setCornerRadius(dp(8));
            ctxBar.setBackground(ctxBg);
            LinearLayout.LayoutParams ctxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ctxLp.bottomMargin = dp(12);
            ctxBar.setLayoutParams(ctxLp);

            // 左侧：已自动获取的上下文摘要
            TextView ctxText = new TextView(this);
            String ctxSummary;
            if (autoContext.containsKey("city") && autoContext.get("city") != null && !autoContext.get("city").isEmpty()) {
                ctxSummary = "📍 已自动获取: " + autoContext.get("city");
            } else {
                ctxSummary = "📍 已自动获取: " + autoContext.toString();
            }
            ctxText.setText(ctxSummary);
            ctxText.setTextSize(13);
            ctxText.setTextColor(0xFF1565C0);
            ctxText.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            ctxBar.addView(ctxText);

            // 右侧：修改按钮（清空自动上下文，回到手动输入）
            TextView modifyBtn = new TextView(this);
            modifyBtn.setText("修改");
            modifyBtn.setTextSize(13);
            modifyBtn.setTextColor(0xFF1565C0);
            modifyBtn.setTypeface(null, android.graphics.Typeface.BOLD);
            modifyBtn.setPadding(dp(8), dp(4), dp(4), dp(4));
            modifyBtn.setOnClickListener(v -> {
                autoContext.clear();
                renderStep(dialog, flow, toolName, selectedParams, currentStepIdx, activeStepsHolder);
            });
            ctxBar.addView(modifyBtn);

            container.addView(ctxBar);
        }

        // 步骤标题与描述（CONFIRM 步骤使用专用标题"确认执行"）
        if (step.type == ToolGuideFlow.GuideStep.StepType.CONFIRM) {
            TextView stepTitle = new TextView(this);
            stepTitle.setText("确认执行");
            stepTitle.setTextSize(15);
            stepTitle.setTextColor(0xFF3F51B5);
            stepTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            container.addView(stepTitle);
        } else {
            TextView stepTitle = new TextView(this);
            stepTitle.setText(step.title);
            stepTitle.setTextSize(15);
            stepTitle.setTextColor(0xFF3F51B5);
            stepTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            container.addView(stepTitle);

            if (step.description != null && !step.description.isEmpty()) {
                TextView descView = new TextView(this);
                descView.setText(step.description);
                descView.setTextSize(13);
                descView.setTextColor(0xFF666666);
                LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                descParams.topMargin = 8;
                descView.setLayoutParams(descParams);
                container.addView(descView);
            }
        }

        // 根据步骤类型渲染中部内容
        if (step.type == ToolGuideFlow.GuideStep.StepType.OPTION) {
            // 选项卡片列表：每个选项为带图标+文字的卡片
            if (step.options != null) {
                String[] optEmojis = {"🌤️","🔍","🌐","🗄️","📁","📍","📱","➗","💡","✨","📋","⚙️"};
                for (int oi = 0; oi < step.options.size(); oi++) {
                    final ToolGuideFlow.GuideStep.Option opt = step.options.get(oi);
                    // 卡片容器（水平）：左侧emoji + 右侧label
                    LinearLayout card = new LinearLayout(this);
                    card.setOrientation(LinearLayout.HORIZONTAL);
                    card.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    card.setPadding(dp(16), dp(16), dp(16), dp(16));
                    card.setClickable(true);
                    // 卡片背景：圆角16dp 白底 边框1dp
                    final android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
                    cardBg.setColor(0xFFFFFFFF);
                    cardBg.setCornerRadius(dp(16));
                    cardBg.setStroke(dp(1), 0xFFE0E0E0);
                    card.setBackground(cardBg);
                    card.setElevation(dp(2));
                    LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    cardLp.topMargin = (oi == 0) ? 0 : dp(12);
                    card.setLayoutParams(cardLp);

                    // 左侧 emoji 图标
                    TextView iconTv = new TextView(this);
                    iconTv.setText(oi < optEmojis.length ? optEmojis[oi] : "🔹");
                    iconTv.setTextSize(20);
                    LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    iconLp.rightMargin = dp(12);
                    iconTv.setLayoutParams(iconLp);
                    card.addView(iconTv);

                    // 右侧 label 文字
                    TextView labelTv = new TextView(this);
                    labelTv.setText(opt.label);
                    labelTv.setTextSize(15);
                    labelTv.setTextColor(0xFF333333);
                    labelTv.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    card.addView(labelTv);

                    // 若该选项已被选中，使用选中色高亮
                    if (opt.value.equals(selectedParams.get(step.paramKey))) {
                        cardBg.setColor(0xFFE8EAF6);
                    }

                    card.setOnClickListener(v -> {
                        // 选中瞬间高亮，记录选择后重新计算 activeSteps 并前进
                        cardBg.setColor(0xFFE8EAF6);
                        selectedParams.put(step.paramKey, opt.value);
                        activeStepsHolder[0] = flow.getActiveSteps(selectedParams);
                        currentStepIdx[0]++;
                        renderStep(dialog, flow, toolName, selectedParams, currentStepIdx, activeStepsHolder);
                    });
                    container.addView(card);
                }
            }
        } else if (step.type == ToolGuideFlow.GuideStep.StepType.INPUT) {
            // 文本输入框
            final EditText editText = new EditText(this);
            editText.setHint(step.hint != null ? step.hint : "请输入");
            editText.setTextSize(14);
            if (step.multiline) {
                editText.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                editText.setMinLines(3);
            }
            // 回退后重新进入时预填已选值
            if (selectedParams.containsKey(step.paramKey)) {
                editText.setText(selectedParams.get(step.paramKey));
            }
            LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            etParams.topMargin = 16;
            editText.setLayoutParams(etParams);
            container.addView(editText);

            // 下一步按钮
            android.widget.Button nextBtn = new android.widget.Button(this);
            nextBtn.setText("下一步");
            nextBtn.setBackgroundColor(0xFF6200EE);
            nextBtn.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nextLp.topMargin = 24;
            nextBtn.setLayoutParams(nextLp);
            nextBtn.setOnClickListener(v -> {
                String value = editText.getText().toString().trim();
                if (step.required && value.isEmpty()) {
                    android.widget.Toast.makeText(this, "此项为必填，请输入内容", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                selectedParams.put(step.paramKey, value);
                activeStepsHolder[0] = flow.getActiveSteps(selectedParams);
                currentStepIdx[0]++;
                renderStep(dialog, flow, toolName, selectedParams, currentStepIdx, activeStepsHolder);
            });
            container.addView(nextBtn);
        } else if (step.type == ToolGuideFlow.GuideStep.StepType.CONFIRM) {
            // 展示已选参数摘要
            TextView summaryView = new TextView(this);
            StringBuilder sb = new StringBuilder();
            if (selectedParams.isEmpty()) {
                sb.append("（无参数）");
            } else {
                for (Map.Entry<String, String> entry : selectedParams.entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                    }
                }
            }
            summaryView.setText(sb.toString().trim());
            summaryView.setTextSize(13);
            summaryView.setTextColor(0xFF333333);
            LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sumLp.topMargin = 16;
            summaryView.setLayoutParams(sumLp);
            container.addView(summaryView);

            // 执行工具按钮
            android.widget.Button execBtn = new android.widget.Button(this);
            execBtn.setText("⚡ 执行工具");
            execBtn.setBackgroundColor(0xFF6200EE);
            execBtn.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams execLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            execLp.topMargin = 24;
            execBtn.setLayoutParams(execLp);
            execBtn.setOnClickListener(v -> {
                // 收集参数
                final Map<String, Object> execParams = new HashMap<>();
                for (Map.Entry<String, String> entry : selectedParams.entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        execParams.put(entry.getKey(), entry.getValue());
                    }
                }
                // 注入已自动获取的上下文（不覆盖用户已填值）
                injectAutoContext(execParams);

                // 执行前检查缺失的环境上下文
                List<String> missing = ToolContextProvider.getMissingContext(flow, execParams);
                if (missing.contains("location") && "ai_weather".equals(toolName)) {
                    // 缺少位置且为天气工具：异步定位后再执行
                    execBtn.setEnabled(false);
                    execBtn.setText("正在定位...");
                    addSystemMessage("📍 正在定位...");
                    final android.widget.Button execBtnRef = execBtn;
                    ToolContextProvider.getCurrentLocation(this, new ToolContextProvider.LocationCallback() {
                        @Override
                        public void onLocationReady(String city, double lat, double lon) {
                            // 定位成功：注入坐标到执行参数与自动上下文，更新提示条后执行
                            autoContext.put("city", city);
                            autoContext.put("lat", String.valueOf(lat));
                            autoContext.put("lon", String.valueOf(lon));
                            execParams.put("city", city);
                            execParams.put("lat", lat);   // Double类型，天气工具需要Double/Number
                            execParams.put("lon", lon);   // Double类型，天气工具需要Double/Number
                            addSystemMessage("📍 已定位到: " + city);
                            runGuideToolExecution(dialog, toolName, execParams);
                        }
                        @Override
                        public void onLocationFailed(String error) {
                            // 定位失败：恢复按钮，提示用户手动输入，不执行工具
                            execBtnRef.setEnabled(true);
                            execBtnRef.setText("⚡ 执行工具");
                            android.widget.Toast.makeText(AIChatActivity.this,
                                "定位失败,请手动输入城市", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }

                // 无缺失上下文，直接执行
                runGuideToolExecution(dialog, toolName, execParams);
            });
            container.addView(execBtn);
        }

        dialog.setContentView(container);
    }

    /** 将已自动获取的上下文合并进执行参数（不覆盖用户已填的值） */
    private void injectAutoContext(Map<String, Object> execParams) {
        if (autoContext == null || autoContext.isEmpty() || execParams == null) return;
        for (Map.Entry<String, String> e : autoContext.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty() && !execParams.containsKey(e.getKey())) {
                // lat/lon 用 Double 类型注入，天气工具需要 Double/Number
                if ("lat".equals(e.getKey()) || "lon".equals(e.getKey())) {
                    try { execParams.put(e.getKey(), Double.parseDouble(e.getValue())); }
                    catch (NumberFormatException ex) { execParams.put(e.getKey(), e.getValue()); }
                } else {
                    execParams.put(e.getKey(), e.getValue());
                }
            }
        }
    }

    /**
     * 执行引导流程收集到的工具：关闭对话框→显示工具调用气泡→后台执行→
     * 保存结果到 ToolResultStore→调用 ToolResultInterpreter 解析并显示摘要。
     *
     * @param dialog      引导对话框（可为 null，执行前会关闭）
     * @param toolName    工具名
     * @param execParams  执行参数
     */
    private void runGuideToolExecution(
            final com.google.android.material.bottomsheet.BottomSheetDialog dialog,
            final String toolName, final Map<String, Object> execParams) {
        if (dialog != null) dialog.dismiss();

        // 在聊天中显示工具调用消息（气泡占位）
        String paramsStr = execParams.isEmpty() ? "{}" : new com.google.gson.Gson().toJson(execParams);
        final int msgPos = addToolCallMessage(toolName, paramsStr);

        // 使用预检+错误恢复的执行（预知性补充缺失参数，避免错误）
        preCheckThenExecute(toolName, execParams, msgPos, 0, null);
    }

    /**
     * 预检工具参数后执行（预知性补充缺失参数，避免错误）。
     * 第一层：预检 - 工具执行前预知性补充参数
     * 第二层：执行 - 预检通过后执行工具
     * 第三层：错误恢复 - 执行失败后动态纠错（executeToolWithRecovery内部处理）
     */
    private void preCheckThenExecute(final String toolName,
                                     final Map<String, Object> params,
                                     final int msgPos,
                                     final int retryCount,
                                     final Runnable onComplete) {
        ToolPreChecker.preCheck(this, toolName, params, new ToolPreChecker.PreCheckCallback() {
            @Override
            public void onReady(Map<String, Object> params, String autoFilledInfo) {
                // 预检回调在后台线程，UI操作需切回主线程
                runOnUiThread(() -> {
                    // 预检完成，显示自动补充的信息
                    if (autoFilledInfo != null && !autoFilledInfo.isEmpty()) {
                        addSystemMessage("🔧 已预检补充: " + autoFilledInfo);
                        scrollToBottom();
                    }
                    // 执行工具（带错误恢复）
                    executeToolWithRecovery(toolName, params, msgPos, retryCount, onComplete);
                });
            }
            @Override
            public void onNeedUserInput(List<ToolErrorRecovery.MissingParam> missing) {
                // 需要用户输入的参数缺失，弹出输入框（UI操作切回主线程）
                runOnUiThread(() -> promptUserForMissingParams(toolName, params, msgPos, missing, retryCount, onComplete));
            }
        });
    }

    /**
     * 执行工具，失败时智能检测错误原因并自动补充参数重试。
     * 最多重试2次：第1次自动获取位置等可自动补充的参数，第2次弹出输入框让用户补充。
     *
     * @param toolName    工具名
     * @param params      执行参数（会被修改：补充缺失参数）
     * @param msgPos      工具调用气泡在聊天列表中的位置
     * @param retryCount  当前重试次数（从0开始）
     * @param onComplete  完成回调（可为null，聚合流程用）
     */
    private void executeToolWithRecovery(final String toolName,
                                         final Map<String, Object> params,
                                         final int msgPos,
                                         final int retryCount,
                                         final Runnable onComplete) {
        if (retryCount > 0) {
            addSystemMessage("🔧 检测到问题，正在自动修复并重试(" + retryCount + "/2)...");
            scrollToBottom();
        }
        new Thread(() -> {
            final AIToolResult result = AIToolManager.getInstance(this).executeTool(toolName, params);
            runOnUiThread(() -> {
                final boolean success = result != null && result.isSuccess();
                if (success) {
                    // === 成功：显示结果 + LLM解析 ===
                    String resultStr = String.valueOf(result.getResult());
                    updateToolCallResult(msgPos, true, resultStr);
                    addSystemMessage("✅ 工具执行完成");
                    scrollToBottom();
                    // 保存到ToolResultStore
                    try {
                        ToolResultStore.save(toolName, params, result.getResult(), 0);
                    } catch (Exception ignore) { }
                    // LLM解析结果
                    addSystemMessage("💡 正在用AI解读结果...");
                    scrollToBottom();
                    ToolResultInterpreter.interpret(this, toolName, result.getResult(),
                        new ToolResultInterpreter.InterpretCallback() {
                            @Override
                            public void onInterpreted(String summary) {
                                if (summary != null && !summary.isEmpty()) {
                                    addAIMessage(summary);
                                    scrollToBottom();
                                }
                                if (onComplete != null) onComplete.run();
                            }
                            @Override
                            public void onError(String error) {
                                if (onComplete != null) onComplete.run();
                            }
                        });
                } else if (retryCount < 2) {
                    // === 失败但可重试：智能恢复 ===
                    String error = result != null ? result.getErrorMessage() : "未知错误";
                    attemptToolRecovery(toolName, params, msgPos, error, retryCount, onComplete);
                } else {
                    // === 超过重试次数：最终失败 ===
                    String error = result != null ? result.getErrorMessage() : "未知错误";
                    updateToolCallResult(msgPos, false, error);
                    addSystemMessage("❌ 工具执行失败: " + error);
                    scrollToBottom();
                    if (onComplete != null) onComplete.run();
                }
            });
        }).start();
    }

    /**
     * 智能恢复：分析错误原因，自动补充可获取的参数或弹出输入框让用户补充。
     */
    private void attemptToolRecovery(final String toolName,
                                     final Map<String, Object> params,
                                     final int msgPos,
                                     final String errorMsg,
                                     final int retryCount,
                                     final Runnable onComplete) {
        java.util.List<ToolErrorRecovery.MissingParam> missing =
                ToolErrorRecovery.analyzeMissingParams(toolName, errorMsg, params);

        if (missing.isEmpty()) {
            // 无法识别缺失参数，直接失败
            updateToolCallResult(msgPos, false, errorMsg);
            addSystemMessage("❌ 工具执行失败: " + errorMsg);
            scrollToBottom();
            if (onComplete != null) onComplete.run();
            return;
        }

        // 构建缺失参数描述
        StringBuilder descSb = new StringBuilder("🔧 检测到缺失参数: ");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) descSb.append("、");
            descSb.append(missing.get(i).description);
        }
        addSystemMessage(descSb.toString());
        scrollToBottom();

        if (ToolErrorRecovery.allAutoFillable(missing)) {
            // 全部可自动获取（如位置类参数）
            addSystemMessage("📍 正在自动获取...");
            scrollToBottom();
            autoFillMissingParams(missing, params, new Runnable() {
                @Override
                public void run() {
                    // 补充完成，重试执行
                    executeToolWithRecovery(toolName, params, msgPos, retryCount + 1, onComplete);
                }
            });
        } else {
            // 需要用户输入，弹出输入框
            promptUserForMissingParams(toolName, params, msgPos, missing, retryCount, onComplete);
        }
    }

    /**
     * 自动获取缺失参数（定位、时间等），补充到params中。
     */
    private void autoFillMissingParams(final java.util.List<ToolErrorRecovery.MissingParam> missing,
                                       final Map<String, Object> params,
                                       final Runnable onComplete) {
        if (ToolErrorRecovery.needsLocation(missing)) {
            // 需要定位
            ToolContextProvider.getCurrentLocation(this, new ToolContextProvider.LocationCallback() {
                @Override
                public void onLocationReady(String city, double lat, double lon) {
                    if (!hasParamValue(params, "city")) params.put("city", city);
                    if (!hasParamValue(params, "lat")) params.put("lat", lat);
                    if (!hasParamValue(params, "lon")) params.put("lon", lon);
                    addSystemMessage("📍 已自动获取位置: " + city);
                    scrollToBottom();
                    // 补充时间类参数
                    fillTimeParams(missing, params);
                    onComplete.run();
                }
                @Override
                public void onLocationFailed(String error) {
                    addSystemMessage("⚠️ 自动定位失败: " + error + "，使用默认城市北京");
                    if (!hasParamValue(params, "city")) params.put("city", "北京");
                    // 北京坐标
                    if (!hasParamValue(params, "lat")) params.put("lat", 39.9042);
                    if (!hasParamValue(params, "lon")) params.put("lon", 116.4074);
                    scrollToBottom();
                    fillTimeParams(missing, params);
                    onComplete.run();
                }
            });
        } else {
            // 不需要定位，只补充时间类参数
            fillTimeParams(missing, params);
            onComplete.run();
        }
    }

    /** 补充时间类参数 */
    private void fillTimeParams(java.util.List<ToolErrorRecovery.MissingParam> missing, Map<String, Object> params) {
        for (ToolErrorRecovery.MissingParam mp : missing) {
            if ("time".equals(mp.key) || "datetime".equals(mp.key)) {
                if (!hasParamValue(params, mp.key)) {
                    params.put(mp.key, ToolContextProvider.getCurrentDateTime());
                }
            } else if ("date".equals(mp.key)) {
                if (!hasParamValue(params, "date")) {
                    params.put("date", ToolContextProvider.getCurrentDate());
                }
            }
        }
    }

    /** 检查参数是否已有非空值 */
    private boolean hasParamValue(Map<String, Object> params, String key) {
        if (params == null) return false;
        Object v = params.get(key);
        if (v == null) return false;
        if (v instanceof String) return !((String) v).trim().isEmpty();
        return true;
    }

    /**
     * 弹出输入框让用户补充缺失参数，补充后重试执行。
     */
    private void promptUserForMissingParams(final String toolName,
                                            final Map<String, Object> params,
                                            final int msgPos,
                                            final java.util.List<ToolErrorRecovery.MissingParam> missing,
                                            final int retryCount,
                                            final Runnable onComplete) {
        // 过滤出需要用户输入的参数
        java.util.List<ToolErrorRecovery.MissingParam> userParams = new java.util.ArrayList<>();
        for (ToolErrorRecovery.MissingParam mp : missing) {
            if (!mp.autoFillable) {
                userParams.add(mp);
            }
        }
        if (userParams.isEmpty()) {
            // 没有需要用户输入的，直接重试
            executeToolWithRecovery(toolName, params, msgPos, retryCount + 1, onComplete);
            return;
        }

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        dialog.setTitle("🔧 需要补充信息");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        // 提示标题
        TextView titleView = new TextView(this);
        titleView.setText("工具执行缺少参数，请补充：");
        titleView.setTextSize(15);
        titleView.setTextColor(0xFF333333);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(titleView);

        // 为每个缺失参数创建输入框
        final java.util.Map<String, EditText> inputs = new java.util.HashMap<>();
        for (ToolErrorRecovery.MissingParam mp : userParams) {
            TextView label = new TextView(this);
            label.setText(mp.description);
            label.setTextSize(13);
            label.setTextColor(0xFF666666);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            labelLp.topMargin = 16;
            label.setLayoutParams(labelLp);
            layout.addView(label);

            EditText input = new EditText(this);
            input.setHint("请输入" + mp.description);
            input.setTextSize(14);
            LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            inputLp.topMargin = 4;
            input.setLayoutParams(inputLp);
            layout.addView(input);
            inputs.put(mp.key, input);
        }

        // 提交按钮
        android.widget.Button submitBtn = new android.widget.Button(this);
        submitBtn.setText("提交并重试");
        submitBtn.setBackgroundColor(0xFF6200EE);
        submitBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = 24;
        submitBtn.setLayoutParams(btnLp);
        submitBtn.setOnClickListener(v -> {
            boolean allFilled = true;
            for (ToolErrorRecovery.MissingParam mp : userParams) {
                EditText et = inputs.get(mp.key);
                if (et != null) {
                    String value = et.getText().toString().trim();
                    if (value.isEmpty()) {
                        allFilled = false;
                    } else {
                        params.put(mp.key, value);
                    }
                }
            }
            if (!allFilled) {
                android.widget.Toast.makeText(this, "请填写所有参数", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            addSystemMessage("📝 已补充参数，正在重试...");
            scrollToBottom();
            executeToolWithRecovery(toolName, params, msgPos, retryCount + 1, onComplete);
        });
        layout.addView(submitBtn);

        dialog.setContentView(layout);
        dialog.show();
    }

    /** 聚合步骤参数收集回调 */
    private interface CompositeParamCollector {
        void onCollected(Map<String, String> params);
    }

    /** 动态创建一个聚合方案入口 Chip 并加入 ChipGroup */
    private void addCompositeChip(com.google.android.material.chip.ChipGroup group,
                                  String label, final String flowId) {
        com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
        chip.setText(label);
        chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(0xFFE8F5E9));
        chip.setTextColor(0xFF1B5E20);
        chip.setChipStrokeWidth(0f);
        chip.setClickable(true);
        chip.setOnClickListener(v -> showCompositeGuideDialog(flowId));
        group.addView(chip);
    }

    /**
     * 显示聚合引导方案对话框：展示方案介绍 + 步骤预览 + "开始执行"按钮。
     *
     * @param flowId 聚合流程ID（如 "go_out"）
     */
    private void showCompositeGuideDialog(String flowId) {
        final CompositeGuideFlow flow = CompositeGuideFlow.getFlow(flowId);
        if (flow == null) {
            android.widget.Toast.makeText(this, "未找到该聚合方案", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
            new com.google.android.material.bottomsheet.BottomSheetDialog(this);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 48, 48, 48);

        // 方案介绍：图标 + 名称
        TextView titleView = new TextView(this);
        titleView.setText((flow.icon != null ? flow.icon + " " : "") + flow.displayName);
        titleView.setTextSize(18);
        titleView.setTextColor(0xFF333333);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        container.addView(titleView);

        // 方案描述
        if (flow.description != null && !flow.description.isEmpty()) {
            TextView descView = new TextView(this);
            descView.setText(flow.description);
            descView.setTextSize(13);
            descView.setTextColor(0xFF666666);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = 8;
            descView.setLayoutParams(dlp);
            container.addView(descView);
        }

        // 分隔线
        View divider = new View(this);
        divider.setBackgroundColor(0xFFE0E0E0);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divLp.topMargin = 16;
        divLp.bottomMargin = 16;
        divider.setLayoutParams(divLp);
        container.addView(divider);

        // 步骤列表预览
        TextView stepsTitle = new TextView(this);
        stepsTitle.setText("执行步骤:");
        stepsTitle.setTextSize(14);
        stepsTitle.setTextColor(0xFF3F51B5);
        stepsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        container.addView(stepsTitle);
        if (flow.steps != null) {
            for (int i = 0; i < flow.steps.size(); i++) {
                CompositeGuideFlow.CompositeStep s = flow.steps.get(i);
                TextView stepView = new TextView(this);
                stepView.setText((i + 1) + ". " + (s.icon != null ? s.icon + " " : "") + s.actionDescription);
                stepView.setTextSize(14);
                stepView.setTextColor(0xFF333333);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                slp.topMargin = 8;
                stepView.setLayoutParams(slp);
                container.addView(stepView);
            }
        }

        // 开始执行按钮
        android.widget.Button startBtn = new android.widget.Button(this);
        startBtn.setText("🚀 开始执行");
        startBtn.setBackgroundColor(0xFF6200EE);
        startBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        startLp.topMargin = 24;
        startBtn.setLayoutParams(startLp);
        startBtn.setOnClickListener(v -> {
            dialog.dismiss();
            addSystemMessage("🚀 开始聚合方案: " + flow.displayName);
            // 清空上一次聚合流程的中间结果
            ToolResultStore.clear();
            executeCompositeFlow(flow, 0);
        });
        container.addView(startBtn);

        dialog.setContentView(container);
        dialog.show();
    }

    /**
     * 递归执行聚合流程。每执行完一步（含结果解析）后递归进入下一步，
     * 全部完成后给出综合提示并清空中间结果存储。
     *
     * @param flow      聚合流程
     * @param stepIndex 当前步骤索引
     */
    private void executeCompositeFlow(final CompositeGuideFlow flow, final int stepIndex) {
        // 全部步骤完成
        if (flow.steps == null || stepIndex >= flow.steps.size()) {
            addSystemMessage("✅ " + flow.displayName + " 全部完成");
            ToolResultStore.clear();
            return;
        }
        final CompositeGuideFlow.CompositeStep step = flow.steps.get(stepIndex);
        // 顶部进度提示
        addSystemMessage("📋 [" + flow.displayName + "] 步骤 " + (stepIndex + 1) + "/" + flow.steps.size()
                + " - 当前: " + step.actionDescription);
        scrollToBottom();

        // 需要用户补充参数时先弹出引导收集
        if (step.guideSteps != null && !step.guideSteps.isEmpty() && !step.autoExecute) {
            collectCompositeStepParams(step, collected -> {
                Map<String, Object> params = buildCompositeParams(step, collected);
                executeCompositeStep(flow, step, stepIndex, params);
            });
        } else {
            // 自动执行或无引导步骤：直接构建参数执行
            Map<String, Object> params = buildCompositeParams(step, null);
            executeCompositeStep(flow, step, stepIndex, params);
        }
    }

    /**
     * 构建聚合步骤执行参数：合并 fixedParams + 解析 paramRefs（用 ToolResultStore.resolveRef）+ 引导收集的参数。
     */
    private Map<String, Object> buildCompositeParams(CompositeGuideFlow.CompositeStep step,
                                                     Map<String, String> collected) {
        Map<String, Object> params = new HashMap<>();
        // 固定参数
        if (step.fixedParams != null) {
            for (Map.Entry<String, String> e : step.fixedParams.entrySet()) {
                if (e.getValue() != null) params.put(e.getKey(), e.getValue());
            }
        }
        // 参数引用：运行时解析（如 $prev.city）
        if (step.paramRefs != null) {
            for (Map.Entry<String, String> e : step.paramRefs.entrySet()) {
                String resolved = ToolResultStore.resolveRef(e.getValue());
                if (resolved != null && !resolved.isEmpty()) {
                    params.put(e.getKey(), resolved);
                }
            }
        }
        // 引导步骤收集的参数
        if (collected != null) {
            for (Map.Entry<String, String> e : collected.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    params.put(e.getKey(), e.getValue());
                }
            }
        }
        return params;
    }

    /**
     * 执行单个聚合步骤：显示工具调用气泡→后台执行→保存结果→解析摘要→递归下一步。
     */
    private void executeCompositeStep(final CompositeGuideFlow flow,
                                      final CompositeGuideFlow.CompositeStep step,
                                      final int stepIndex, final Map<String, Object> params) {
        // 显示工具调用气泡与"执行中"状态
        String paramsStr = params.isEmpty() ? "{}" : new com.google.gson.Gson().toJson(params);
        final int msgPos = addToolCallMessage(step.toolName, paramsStr);
        addSystemMessage("⚡ 正在执行: " + step.actionDescription + "...");
        scrollToBottom();

        // 预检参数后执行（预知性补充缺失参数）
        ToolPreChecker.preCheck(this, step.toolName, params, new ToolPreChecker.PreCheckCallback() {
            @Override
            public void onReady(Map<String, Object> params, String autoFilledInfo) {
                runOnUiThread(() -> {
                if (autoFilledInfo != null && !autoFilledInfo.isEmpty()) {
                    addSystemMessage("🔧 已预检补充: " + autoFilledInfo);
                    scrollToBottom();
                }
                // 预检通过，执行工具
                new Thread(() -> {
                    final AIToolResult result = AIToolManager.getInstance(AIChatActivity.this).executeTool(step.toolName, params);
                    runOnUiThread(() -> {
                        final boolean success = result != null && result.isSuccess();
                        if (success) {
                            // 成功：显示结果
                            String resultStr = String.valueOf(result.getResult());
                            updateToolCallResult(msgPos, true, resultStr);
                            addSystemMessage("✅ " + step.actionDescription + " 完成");
                            scrollToBottom();
                            // 保存结果
                            try {
                                ToolResultStore.save(step.toolName, params, result.getResult(), stepIndex);
                            } catch (Exception ignore) { }
                            // LLM解析
                            final int nextIndex = stepIndex + 1;
                            ToolResultInterpreter.interpret(AIChatActivity.this, step.toolName, result.getResult(),
                                new ToolResultInterpreter.InterpretCallback() {
                                    @Override
                                    public void onInterpreted(String summary) {
                                        if (summary != null && !summary.isEmpty()) {
                                            addAIMessage(summary);
                                            scrollToBottom();
                                        }
                                        executeCompositeFlow(flow, nextIndex);
                                    }
                                    @Override
                                    public void onError(String error) {
                                        executeCompositeFlow(flow, nextIndex);
                                    }
                                });
                        } else {
                            // 失败：尝试智能恢复
                            String error = result != null ? result.getErrorMessage() : "未知错误";
                            recoverCompositeStep(flow, step, stepIndex, params, msgPos, error, 0);
                        }
                    });
                }).start();
                }); // runOnUiThread end
            }
            @Override
            public void onNeedUserInput(List<ToolErrorRecovery.MissingParam> missing) {
                // 聚合步骤需要用户输入参数
                runOnUiThread(() -> promptUserForCompositeParams(flow, step, stepIndex, params, msgPos, missing, 0));
            }
        });
    }

    /**
     * 聚合流程中的工具错误恢复。
     */
    private void recoverCompositeStep(final CompositeGuideFlow flow,
                                      final CompositeGuideFlow.CompositeStep step,
                                      final int stepIndex,
                                      final Map<String, Object> params,
                                      final int msgPos,
                                      final String errorMsg,
                                      final int retryCount) {
        if (retryCount >= 2) {
            // 超过重试次数
            updateToolCallResult(msgPos, false, errorMsg);
            addSystemMessage("❌ " + step.actionDescription + " 失败: " + errorMsg);
            addSystemMessage("❌ " + flow.displayName + " 因步骤失败而终止");
            ToolResultStore.clear();
            scrollToBottom();
            return;
        }

        java.util.List<ToolErrorRecovery.MissingParam> missing =
                ToolErrorRecovery.analyzeMissingParams(step.toolName, errorMsg, params);

        if (missing.isEmpty()) {
            // 无法识别缺失参数，终止
            updateToolCallResult(msgPos, false, errorMsg);
            addSystemMessage("❌ " + step.actionDescription + " 失败: " + errorMsg);
            addSystemMessage("❌ " + flow.displayName + " 因步骤失败而终止");
            ToolResultStore.clear();
            scrollToBottom();
            return;
        }

        StringBuilder descSb = new StringBuilder("🔧 检测到缺失参数: ");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) descSb.append("、");
            descSb.append(missing.get(i).description);
        }
        addSystemMessage(descSb.toString());
        scrollToBottom();

        final int nextRetry = retryCount + 1;
        if (ToolErrorRecovery.allAutoFillable(missing)) {
            addSystemMessage("📍 正在自动获取...");
            scrollToBottom();
            autoFillMissingParams(missing, params, new Runnable() {
                @Override
                public void run() {
                    // 重试执行该步骤
                    retryCompositeStepExecution(flow, step, stepIndex, params, msgPos, nextRetry);
                }
            });
        } else {
            // 需要用户输入
            promptUserForCompositeParams(flow, step, stepIndex, params, msgPos, missing, nextRetry);
        }
    }

    /** 重试执行聚合步骤 */
    private void retryCompositeStepExecution(final CompositeGuideFlow flow,
                                             final CompositeGuideFlow.CompositeStep step,
                                             final int stepIndex,
                                             final Map<String, Object> params,
                                             final int msgPos,
                                             final int retryCount) {
        addSystemMessage("🔧 正在重试" + step.actionDescription + "...");
        scrollToBottom();
        new Thread(() -> {
            final AIToolResult result = AIToolManager.getInstance(this).executeTool(step.toolName, params);
            runOnUiThread(() -> {
                final boolean success = result != null && result.isSuccess();
                if (success) {
                    String resultStr = String.valueOf(result.getResult());
                    updateToolCallResult(msgPos, true, resultStr);
                    addSystemMessage("✅ " + step.actionDescription + " 完成");
                    scrollToBottom();
                    try {
                        ToolResultStore.save(step.toolName, params, result.getResult(), stepIndex);
                    } catch (Exception ignore) { }
                    final int nextIndex = stepIndex + 1;
                    ToolResultInterpreter.interpret(this, step.toolName, result.getResult(),
                        new ToolResultInterpreter.InterpretCallback() {
                            @Override
                            public void onInterpreted(String summary) {
                                if (summary != null && !summary.isEmpty()) {
                                    addAIMessage(summary);
                                    scrollToBottom();
                                }
                                executeCompositeFlow(flow, nextIndex);
                            }
                            @Override
                            public void onError(String error) {
                                executeCompositeFlow(flow, nextIndex);
                            }
                        });
                } else {
                    // 还是失败，继续恢复
                    String error = result != null ? result.getErrorMessage() : "未知错误";
                    recoverCompositeStep(flow, step, stepIndex, params, msgPos, error, retryCount);
                }
            });
        }).start();
    }

    /** 聚合步骤参数用户输入 */
    private void promptUserForCompositeParams(final CompositeGuideFlow flow,
                                              final CompositeGuideFlow.CompositeStep step,
                                              final int stepIndex,
                                              final Map<String, Object> params,
                                              final int msgPos,
                                              final java.util.List<ToolErrorRecovery.MissingParam> missing,
                                              final int retryCount) {
        java.util.List<ToolErrorRecovery.MissingParam> userParams = new java.util.ArrayList<>();
        for (ToolErrorRecovery.MissingParam mp : missing) {
            if (!mp.autoFillable) userParams.add(mp);
        }
        if (userParams.isEmpty()) {
            retryCompositeStepExecution(flow, step, stepIndex, params, msgPos, retryCount);
            return;
        }

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        TextView titleView = new TextView(this);
        titleView.setText("🔧 " + step.actionDescription + " 需要补充信息：");
        titleView.setTextSize(15);
        titleView.setTextColor(0xFF333333);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(titleView);

        final java.util.Map<String, EditText> inputs = new java.util.HashMap<>();
        for (ToolErrorRecovery.MissingParam mp : userParams) {
            TextView label = new TextView(this);
            label.setText(mp.description);
            label.setTextSize(13);
            label.setTextColor(0xFF666666);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            labelLp.topMargin = 16;
            label.setLayoutParams(labelLp);
            layout.addView(label);

            EditText input = new EditText(this);
            input.setHint("请输入" + mp.description);
            input.setTextSize(14);
            LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            inputLp.topMargin = 4;
            input.setLayoutParams(inputLp);
            layout.addView(input);
            inputs.put(mp.key, input);
        }

        android.widget.Button submitBtn = new android.widget.Button(this);
        submitBtn.setText("提交并重试");
        submitBtn.setBackgroundColor(0xFF6200EE);
        submitBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = 24;
        submitBtn.setLayoutParams(btnLp);
        submitBtn.setOnClickListener(v -> {
            boolean allFilled = true;
            for (ToolErrorRecovery.MissingParam mp : userParams) {
                EditText et = inputs.get(mp.key);
                if (et != null) {
                    String value = et.getText().toString().trim();
                    if (value.isEmpty()) {
                        allFilled = false;
                    } else {
                        params.put(mp.key, value);
                    }
                }
            }
            if (!allFilled) {
                android.widget.Toast.makeText(this, "请填写所有参数", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            addSystemMessage("📝 已补充参数，正在重试...");
            scrollToBottom();
            retryCompositeStepExecution(flow, step, stepIndex, params, msgPos, retryCount);
        });
        layout.addView(submitBtn);

        dialog.setContentView(layout);
        dialog.show();
    }

    /**
     * 弹出引导对话框收集聚合步骤所需参数（OPTION/INPUT），收集完成后回调。
     */
    private void collectCompositeStepParams(final CompositeGuideFlow.CompositeStep step,
                                            final CompositeParamCollector callback) {
        if (step.guideSteps == null || step.guideSteps.isEmpty()) {
            callback.onCollected(new HashMap<>());
            return;
        }
        final com.google.android.material.bottomsheet.BottomSheetDialog dialog =
            new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        final Map<String, String> collected = new HashMap<>();
        final int[] idx = {0};
        renderCompositeCollectStep(dialog, step, collected, idx, callback);
        dialog.show();
    }

    /** 渲染聚合步骤参数收集的当前引导子步骤 */
    private void renderCompositeCollectStep(final com.google.android.material.bottomsheet.BottomSheetDialog dialog,
                                            final CompositeGuideFlow.CompositeStep step,
                                            final Map<String, String> collected, final int[] idx,
                                            final CompositeParamCollector callback) {
        // 全部子步骤收集完成
        if (idx[0] >= step.guideSteps.size()) {
            dialog.dismiss();
            callback.onCollected(collected);
            return;
        }
        final ToolGuideFlow.GuideStep gs = step.guideSteps.get(idx[0]);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 48, 48, 48);

        // 标题：步骤图标 + 子步骤标题 + 进度
        TextView titleView = new TextView(this);
        titleView.setText((step.icon != null ? step.icon + " " : "") + gs.title
                + "  (" + (idx[0] + 1) + "/" + step.guideSteps.size() + ")");
        titleView.setTextSize(16);
        titleView.setTextColor(0xFF333333);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        container.addView(titleView);

        // 描述
        if (gs.description != null && !gs.description.isEmpty()) {
            TextView descView = new TextView(this);
            descView.setText(gs.description);
            descView.setTextSize(13);
            descView.setTextColor(0xFF666666);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = 8;
            descView.setLayoutParams(dlp);
            container.addView(descView);
        }

        if (gs.type == ToolGuideFlow.GuideStep.StepType.OPTION) {
            // 选项卡片（与主引导流程保持一致的卡片样式）
            if (gs.options != null) {
                String[] optEmojis = {"🌤️", "🔍", "🌐", "🗄️", "📁", "📍", "📱", "➗", "💡", "✨", "📋", "⚙️"};
                for (int oi = 0; oi < gs.options.size(); oi++) {
                    final ToolGuideFlow.GuideStep.Option opt = gs.options.get(oi);
                    LinearLayout card = new LinearLayout(this);
                    card.setOrientation(LinearLayout.HORIZONTAL);
                    card.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    card.setPadding(dp(16), dp(16), dp(16), dp(16));
                    card.setClickable(true);
                    final android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
                    cardBg.setColor(0xFFFFFFFF);
                    cardBg.setCornerRadius(dp(16));
                    cardBg.setStroke(dp(1), 0xFFE0E0E0);
                    card.setBackground(cardBg);
                    card.setElevation(dp(2));
                    LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    cardLp.topMargin = (oi == 0) ? dp(16) : dp(12);
                    card.setLayoutParams(cardLp);

                    TextView iconTv = new TextView(this);
                    iconTv.setText(oi < optEmojis.length ? optEmojis[oi] : "🔹");
                    iconTv.setTextSize(20);
                    LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    iconLp.rightMargin = dp(12);
                    iconTv.setLayoutParams(iconLp);
                    card.addView(iconTv);

                    TextView labelTv = new TextView(this);
                    labelTv.setText(opt.label);
                    labelTv.setTextSize(15);
                    labelTv.setTextColor(0xFF333333);
                    labelTv.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    card.addView(labelTv);

                    card.setOnClickListener(v -> {
                        cardBg.setColor(0xFFE8EAF6);
                        collected.put(gs.paramKey, opt.value);
                        idx[0]++;
                        renderCompositeCollectStep(dialog, step, collected, idx, callback);
                    });
                    container.addView(card);
                }
            }
        } else if (gs.type == ToolGuideFlow.GuideStep.StepType.INPUT) {
            // 文本输入
            final EditText editText = new EditText(this);
            editText.setHint(gs.hint != null ? gs.hint : "请输入");
            editText.setTextSize(14);
            if (gs.multiline) {
                editText.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                editText.setMinLines(3);
            }
            if (collected.containsKey(gs.paramKey)) {
                editText.setText(collected.get(gs.paramKey));
            }
            LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            etLp.topMargin = dp(16);
            editText.setLayoutParams(etLp);
            container.addView(editText);

            android.widget.Button nextBtn = new android.widget.Button(this);
            nextBtn.setText("下一步");
            nextBtn.setBackgroundColor(0xFF6200EE);
            nextBtn.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams nbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nbLp.topMargin = dp(24);
            nextBtn.setLayoutParams(nbLp);
            nextBtn.setOnClickListener(v -> {
                String value = editText.getText().toString().trim();
                if (gs.required && value.isEmpty()) {
                    android.widget.Toast.makeText(this, "此项为必填，请输入内容", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                collected.put(gs.paramKey, value);
                idx[0]++;
                renderCompositeCollectStep(dialog, step, collected, idx, callback);
            });
            container.addView(nextBtn);
        }

        dialog.setContentView(container);
    }

    private void sendMessage() {
        if (isGenerating) { showToast("AI正在生成中，请稍候"); return; }
        String message = inputMessage.getText().toString().trim();
        if (message.isEmpty()) { showToast("请输入消息"); return; }

        if (!ensureModelLoaded(message)) {
            addUserMessage(message);
            inputMessage.setText("");
            return;
        }

        List<ChatMessage.Attachment> savedAttachments = new ArrayList<>(currentAttachments);

        if (!savedAttachments.isEmpty()) {
            ChatMessage userMessage = ChatMessage.createUserMessage(message, savedAttachments);
            chatHistory.add(userMessage);
            if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1);
            scrollToBottom(true);
            saveHistoryAsync();
            currentAttachments.clear();
            resetAttachmentAdapter();
        } else {
            addUserMessage(message);
        }

        inputMessage.setText("");

        if (message.equalsIgnoreCase("帮助") || message.equalsIgnoreCase("help")) {
            if (dialogHelper != null) dialogHelper.showGuideDialog(); return;
        }

        for (String[] pattern : COMMAND_PATTERNS) {
            if (message.startsWith(pattern[0])) {
                handlePrefixedCommand(message, pattern[0], pattern[1]);
                return;
            }
        }

        if (!savedAttachments.isEmpty()) {
            if (shouldUseOnlineModel()) {
                processMessageWithAttachmentsViaAgent(message, savedAttachments);
            } else if (fileContentExtractor != null) {
                processMessageWithAttachments(message, savedAttachments);
            } else {
                processChatMessage(message);
            }
        } else {
            processChatMessage(message);
        }
    }

    private void processMessageWithAttachmentsViaAgent(String originalMessage, List<ChatMessage.Attachment> attachments) {
        List<ChatMessage.Attachment> filtered = new ArrayList<>();
        List<String> skippedFiles = new ArrayList<>();
        for (ChatMessage.Attachment att : attachments) {
            if (filtered.size() >= MAX_ATTACHMENTS) {
                skippedFiles.add(att.name);
            } else if (att.size > MAX_ATTACHMENT_FILE_SIZE) {
                skippedFiles.add(att.name + "(大文件)");
            } else {
                filtered.add(att);
            }
        }

        if (!skippedFiles.isEmpty()) {
            showToast("跳过 " + skippedFiles.size() + " 个文件");
        }

        if (filtered.isEmpty()) {
            showToast("没有可处理的附件，仅发送文字");
            processChatMessage(originalMessage);
            return;
        }

        showToast("正在准备附件供AI处理...");

        List<Uri> uris = new ArrayList<>();
        for (ChatMessage.Attachment att : filtered) {
            uris.add(Uri.parse(att.url));
        }

        saveAttachmentsToLocal(uris).thenAccept(localFileMap -> {
            runOnUiThread(() -> {
                StringBuilder displayMsg = new StringBuilder();
                displayMsg.append("📎 已上传 ").append(filtered.size()).append(" 个附件\n\n");
                if (!skippedFiles.isEmpty()) {
                    displayMsg.append("跳过: ").append(String.join(", ", skippedFiles)).append("\n\n");
                }
                displayMsg.append("---\n\n");
                int idx = 1;
                int totalLen = 0;
                for (ChatMessage.Attachment att : filtered) {
                    displayMsg.append("【文件").append(idx++).append("】").append(att.name).append("\n");
                    displayMsg.append("  类型: ").append(att.type).append("\n");
                    displayMsg.append("  大小: ").append(formatFileSize(att.size)).append("\n");
                    Uri uri = Uri.parse(att.url);
                    String localPath = localFileMap.get(uri);
                    if (localPath != null) {
                        displayMsg.append("  状态: 已准备就绪\n");
                    } else {
                        displayMsg.append("  状态: 保存失败\n");
                    }
                    displayMsg.append("\n");
                    totalLen += att.name.length() + att.type.length();
                    if (totalLen > 2000) {
                        displayMsg.append("... 更多文件已省略\n");
                        break;
                    }
                }
                displayMsg.append("AI 正在分析附件内容，请稍候...");

                ChatMessage sysMsg = ChatMessage.createSystemMessage(
                        java.util.UUID.randomUUID().toString(),
                        displayMsg.toString(),
                        ChatMessage.SystemMessageType.INFO,
                        System.currentTimeMillis()
                );
                chatHistory.add(sysMsg);
                if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1);
                scrollToBottom();
                saveHistoryAsync();

                String augmentedMessage = buildAgentAugmentedMessage(originalMessage, filtered, localFileMap, skippedFiles);
                AppLogger.ai(TAG, "Agent augmented message with " + localFileMap.size() + " local files");
                processChatMessage(augmentedMessage);
            });
        });
    }

    private java.util.concurrent.CompletableFuture<java.util.Map<Uri, String>> saveAttachmentsToLocal(List<Uri> uris) {
        java.util.concurrent.CompletableFuture<java.util.Map<Uri, String>> future =
                new java.util.concurrent.CompletableFuture<>();
        java.util.Map<Uri, String> localPaths = new java.util.concurrent.ConcurrentHashMap<>();

        if (uris.isEmpty()) {
            future.complete(localPaths);
            return future;
        }

        List<Uri> uriList = new ArrayList<>(uris);
        attachmentManager.saveAttachments(this, uriList, new AttachmentManager.AttachmentCallback() {
            @Override
            public void onSuccess(List<AttachmentManager.AttachmentFile> savedFiles) {
                for (AttachmentManager.AttachmentFile file : savedFiles) {
                    localPaths.put(file.uri, file.path);
                }
                future.complete(localPaths);
            }

            @Override
            public void onError(String error) {
                AppLogger.aiE(TAG, "Save attachments failed: " + error);
                future.complete(localPaths);
            }
        });

        return future;
    }

    private String buildAgentAugmentedMessage(String originalMessage, List<ChatMessage.Attachment> attachments,
                                               java.util.Map<Uri, String> localFileMap, List<String> skippedFiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户消息: ").append(originalMessage).append("\n\n");
        sb.append("=== 已上传附件 ===\n\n");

        int idx = 1;
        for (ChatMessage.Attachment att : attachments) {
            Uri uri = Uri.parse(att.url);
            String localPath = localFileMap.get(uri);

            sb.append("【附件").append(idx).append("】\n");
            sb.append("  文件名: ").append(att.name).append("\n");
            sb.append("  类型: ").append(att.type).append("\n");
            sb.append("  大小: ").append(formatFileSize(att.size)).append("\n");
            if (localPath != null) {
                sb.append("  本地路径: ").append(localPath).append("\n");
            } else {
                sb.append("  本地路径: (未保存成功，跳过)\n");
            }
            sb.append("\n");
            idx++;
        }

        if (skippedFiles != null && !skippedFiles.isEmpty()) {
            sb.append("⚠️ 以下文件已跳过: ").append(String.join(", ", skippedFiles)).append("\n\n");
        }

        sb.append("=== 工具调用格式 ===\n");
        sb.append("必须使用以下格式调用工具，参数用JSON格式：\n");
        sb.append("  <|tool_call_begin|>app_toolkit|{\"action\": \"action_name\", \"param_name\": \"value\"}<|tool_call_end|>\n\n");

        sb.append("=== 可用工具及参数 ===\n");
        sb.append("1. file_parse_text - 解析文本文件、PDF\n");
        sb.append("   参数: file_path=本地文件路径 (必填)\n");
        sb.append("2. file_parse_csv - 解析CSV表格\n");
        sb.append("   参数: file_path=本地文件路径 (必填)\n");
        sb.append("3. file_parse_json - 解析JSON文件\n");
        sb.append("   参数: file_path=本地文件路径 (必填)\n");
        sb.append("4. file_read_lines - 按行读取文件\n");
        sb.append("   参数: file_path=本地文件路径 (必填), start_line, line_count\n");
        sb.append("5. ocr_recognize - 图片文字识别\n");
        sb.append("   参数: image_path=图片本地路径 (必填), language(可选)\n");
        sb.append("6. ocr_recognize_pdf - PDF文字识别\n");
        sb.append("   参数: pdf_path=PDF本地路径 (必填)\n\n");

        sb.append("=== 重要提示 ===\n");
        sb.append("- 从附件列表中复制完整的「本地路径」作为参数值\n");
        sb.append("- 文本/PDF文件优先使用 file_parse_text，失败再试 ocr_recognize_pdf\n");
        sb.append("- 图片必须使用 ocr_recognize\n");
        sb.append("- 工具返回的内容可能很长，先读取摘要再决定是否继续\n");
        sb.append("- 解析完所有需要的附件后再回答用户问题\n\n");

        sb.append("请开始处理附件。");

        return sb.toString();
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / (1024.0 * 1024.0));
    }

    // 附件处理并发控制
    private final java.util.concurrent.atomic.AtomicBoolean isProcessingAttachments = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final int MAX_CONCURRENT_ATTACHMENTS = 3; // 最大并发处理附件数

    private void processMessageWithAttachments(String originalMessage, List<ChatMessage.Attachment> attachments) {
        // 并发控制：检查是否正在处理附件
        if (isProcessingAttachments.compareAndSet(false, true)) {
            showToast("正在解析附件内容...");
        } else {
            showToast("正在处理其他附件，请稍候...");
            return;
        }

        // 限制并发处理的附件数量
        List<ChatMessage.Attachment> limitedAttachments = attachments;
        if (attachments.size() > MAX_CONCURRENT_ATTACHMENTS) {
            limitedAttachments = attachments.subList(0, MAX_CONCURRENT_ATTACHMENTS);
            showToast("附件过多，仅处理前 " + MAX_CONCURRENT_ATTACHMENTS + " 个");
        }

        // 过滤有效附件
        List<Uri> uris = new ArrayList<>();
        List<String> skippedFiles = new ArrayList<>();
        List<ChatMessage.Attachment> validAttachments = new ArrayList<>();
        for (ChatMessage.Attachment att : limitedAttachments) {
            if (att.size > MAX_ATTACHMENT_FILE_SIZE) {
                skippedFiles.add(att.name + "(" + formatFileSize(att.size) + ")");
                continue;
            }
            uris.add(Uri.parse(att.url));
            validAttachments.add(att);
        }

        if (!skippedFiles.isEmpty()) {
            showToast("跳过 " + skippedFiles.size() + " 个大文件");
        }

        if (uris.isEmpty()) {
            // 没有有效附件，直接发送文本
            isProcessingAttachments.set(false);
            if (!originalMessage.isEmpty()) {
                processChatMessage(originalMessage);
            }
            return;
        }

        final String displayMessage = originalMessage;
        final List<String> finalSkippedFiles = skippedFiles;
        final List<ChatMessage.Attachment> finalValidAttachments = validAttachments;

        // 使用自定义线程池限制并发
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);

        extractAllAttachmentContents(uris).thenAcceptAsync(extractedMap -> {
            // 检查Activity状态
            if (isFinishing() || isDestroyed()) {
                AppLogger.w(TAG, "Activity已销毁，取消附件发送");
                isProcessingAttachments.set(false);
                executor.shutdown();
                return;
            }

            runOnUiThread(() -> {
                try {
                    int successCount = 0;
                    StringBuilder allParsedContent = new StringBuilder();
                    java.util.List<java.util.Map.Entry<Uri, String>> successfulExtracts = new ArrayList<>();

                    for (java.util.Map.Entry<Uri, String> entry : extractedMap.entrySet()) {
                        String content = entry.getValue();
                        if (content != null && !isExtractFailed(content)) {
                            successCount++;
                            successfulExtracts.add(entry);
                            String fileName = getFileNameFromUri(entry.getKey());
                            allParsedContent.append("=== 文件: ").append(fileName != null ? fileName : "未知文件").append(" ===\n");
                            allParsedContent.append(content).append("\n\n");
                        }
                    }

                    if (successCount == 0 && finalSkippedFiles.isEmpty()) {
                        showToast("所有附件解析失败");
                        addSystemMessage("附件解析失败，请检查文件格式或稍后重试。", ChatMessage.SystemMessageType.ERROR);
                        return;
                    }

                    if (successCount > 0) {
                        showToast("已解析 " + successCount + " 个附件");
                    }

                    StringBuilder parsedContentDisplay = new StringBuilder();
                    parsedContentDisplay.append("附件解析结果\n\n");
                    parsedContentDisplay.append("共解析 ").append(successCount).append(" 个文件\n");
                    if (!finalSkippedFiles.isEmpty()) {
                        parsedContentDisplay.append("跳过: ").append(String.join(", ", finalSkippedFiles)).append("\n");
                    }
                    parsedContentDisplay.append("\n---\n\n");

                    int idx = 1;
                    int totalContentLength = 0;
                    for (java.util.Map.Entry<Uri, String> entry : successfulExtracts) {
                        String fileName = getFileNameFromUri(entry.getKey());
                        String content = entry.getValue();
                        if (content == null) continue;

                        int displayLimit = 500;
                        if (totalContentLength + content.length() > 3000) {
                            displayLimit = Math.max(100, 3000 - totalContentLength);
                        }

                        parsedContentDisplay.append("【文件").append(idx++).append("】");
                        if (fileName != null) parsedContentDisplay.append(" ").append(fileName);
                        parsedContentDisplay.append("\n");

                        if (content.length() > displayLimit) {
                            parsedContentDisplay.append(content, 0, displayLimit)
                                    .append("\n...(内容过长，已截断，共").append(content.length()).append("字符)\n\n");
                            totalContentLength += displayLimit;
                        } else {
                            parsedContentDisplay.append(content).append("\n\n");
                            totalContentLength += content.length();
                        }
                    }

                    chatHistory.add(ChatMessage.createSystemMessage(
                            java.util.UUID.randomUUID().toString(),
                            parsedContentDisplay.toString(),
                            ChatMessage.SystemMessageType.INFO,
                            System.currentTimeMillis()
                    ));
                    if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1);
                    scrollToBottom();
                    saveHistoryAsync();

                    // 附件已解析显示，不调用AI推理
                    AppLogger.i(TAG, "Attachments parsed: " + successCount + " files, no AI inference");

                } catch (Exception e) {
                    AppLogger.e(TAG, "附件处理异常: " + e.getMessage(), e);
                    showToast("附件处理失败: " + e.getMessage());
                } finally {
                    // 释放并发控制
                    isProcessingAttachments.set(false);
                    executor.shutdown();
                }
            });
        }, executor).exceptionally(ex -> {
            // 异常处理
            AppLogger.e(TAG, "附件内容提取失败: " + ex.getMessage());
            isProcessingAttachments.set(false);
            executor.shutdown();
            runOnUiThread(() -> showToast("附件处理失败: " + ex.getMessage()));
            return null;
        });
    }

    /**
     * 构建安全的附件分析提示词（带长度限制和异常保护）
     */
    private String buildSafeAttachmentAnalysisPrompt(String originalMessage, String attachmentContent, int fileCount) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("用户上传了 ").append(fileCount).append(" 个附件。");

            if (originalMessage != null && !originalMessage.isEmpty() && !originalMessage.equals("请分析这些附件的内容")) {
                prompt.append("用户问题：").append(originalMessage).append("\n\n");
            } else {
                prompt.append("请分析这些附件的主要内容，并提供摘要。\n\n");
            }

            prompt.append("=== 附件内容 ===\n\n");
            prompt.append(attachmentContent);
            prompt.append("\n=== 附件内容结束 ===\n\n");
            prompt.append("请根据附件内容回答。如果附件内容不足，请说明。");

            String finalPrompt = prompt.toString();

            // 长度限制
            if (finalPrompt.length() > 32768) {
                AppLogger.w(TAG, "提示词过长(" + finalPrompt.length() + ")，截断到32768字符");
                finalPrompt = finalPrompt.substring(0, 32768) + "...[内容过长已截断]";
            }

            return finalPrompt;
        } catch (Exception e) {
            AppLogger.e(TAG, "构建提示词异常: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 构建附件分析提示词
     */
    private String buildAttachmentAnalysisPrompt(String originalMessage, String attachmentContent, int fileCount) {
        return buildSafeAttachmentAnalysisPrompt(originalMessage, attachmentContent, fileCount);
    }

    private boolean canSummarize(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }

        int minCharsForSummary = 100;
        int wordCount = content.trim().split("\\s+").length;
        int charCount = content.length();

        return charCount >= minCharsForSummary || wordCount >= 30;
    }

    private String buildSummaryPrompt(String userMessage, String parsedContent) {
        int contextSize = aiConfig.getContextSize();
        int safeLimit = (int) (contextSize * 0.35);
        int contentLimit = Math.min(safeLimit, 5000);

        if (parsedContent.length() > contentLimit) {
            parsedContent = parsedContent.substring(0, contentLimit) + "\n...[内容已截断]";
        }

        StringBuilder prompt = new StringBuilder();

        if (userMessage != null && !userMessage.trim().isEmpty()) {
            prompt.append("用户需求: ").append(userMessage).append("\n\n");
        } else {
            prompt.append("用户需求: 请总结以下附件的主要内容\n\n");
        }

        prompt.append("=== 附件解析内容 ===\n\n");
        prompt.append(parsedContent).append("\n\n");
        prompt.append("=== 总结要求 ===\n");
        prompt.append("1. 请用清晰的结构总结附件的主要内容\n");
        prompt.append("2. 提取关键点和重要信息\n");
        prompt.append("3. 如果是表格数据，请给出数据概览\n");
        prompt.append("4. 如果用户有特定需求，请优先回答用户的问题\n\n");
        prompt.append("请开始总结：");

        return prompt.toString();
    }

    private boolean isExtractFailed(String content) {
        if (content == null) return true;
        return content.startsWith("解析失败:")
                || content.startsWith("文件解析失败:")
                || content.startsWith("无法加载图片")
                || content.startsWith("OCR识别失败:")
                || content.startsWith("不支持的文件类型:")
                || content.startsWith("PDF文件需要")
                || content.startsWith("Word文件需要")
                || content.startsWith("Excel文件需要")
                || content.startsWith("无法确定文件类型");
    }

    private java.util.concurrent.CompletableFuture<java.util.Map<Uri, String>> extractAllAttachmentContents(List<Uri> uris) {
        java.util.concurrent.CompletableFuture<java.util.Map<Uri, String>> allFutures =
                new java.util.concurrent.CompletableFuture<>();
        java.util.Map<Uri, String> results = new java.util.concurrent.ConcurrentHashMap<>();

        if (uris.isEmpty()) {
            allFutures.complete(results);
            return allFutures;
        }

        int total = uris.size();
        int[] completed = {0};

        for (Uri uri : uris) {
            fileContentExtractor.extractContent(uri).whenComplete((content, throwable) -> {
                if (throwable != null) {
                    AppLogger.aiE(TAG, "Extract content fail: " + throwable.getMessage());
                    results.put(uri, "解析失败: " + throwable.getMessage());
                } else {
                    results.put(uri, content);
                }
                synchronized (completed) {
                    completed[0]++;
                    if (completed[0] == total) {
                        allFutures.complete(results);
                    }
                }
            });
        }

        return allFutures;
    }

    private static final int MAX_ATTACHMENTS = 10;
    private static final int MAX_SINGLE_ATTACHMENT_CHARS = 2000;
    private static final int MAX_TOTAL_ATTACHMENT_CHARS = 6000;
    private static final long MAX_ATTACHMENT_FILE_SIZE = 5 * 1024 * 1024;

    private String buildAugmentedMessage(String originalMessage, java.util.Map<Uri, String> extractedMap) {
        if (extractedMap.isEmpty()) return originalMessage;

        int contextSize = aiConfig.getContextSize();
        int safeLimit = (int) (contextSize * 0.4);
        int totalCharLimit = Math.min(safeLimit, MAX_TOTAL_ATTACHMENT_CHARS);
        int singleCharLimit = Math.min(totalCharLimit / Math.max(1, extractedMap.size()), MAX_SINGLE_ATTACHMENT_CHARS);

        StringBuilder sb = new StringBuilder();
        sb.append("用户消息: ").append(originalMessage).append("\n\n");
        sb.append("=== 附件内容 ===\n\n");

        int totalUsed = 0;
        int idx = 1;
        for (java.util.Map.Entry<Uri, String> entry : extractedMap.entrySet()) {
            String fileName = getFileNameFromUri(entry.getKey());
            String content = entry.getValue();

            if (content == null || isExtractFailed(content)) {
                sb.append("【附件").append(idx++).append("】");
                if (fileName != null) sb.append(" ").append(fileName);
                sb.append("\n(解析失败，跳过该文件)\n\n");
                continue;
            }

            int remaining = totalCharLimit - totalUsed;
            if (remaining <= 0) {
                sb.append("... 附件过多，其余已跳过\n");
                break;
            }

            int thisLimit = Math.min(singleCharLimit, remaining);
            if (content.length() > thisLimit) {
                content = content.substring(0, thisLimit) + "\n...[内容已截断]";
            }

            sb.append("【附件").append(idx++).append("】");
            if (fileName != null) sb.append(" ").append(fileName);
            sb.append("\n");
            sb.append(content).append("\n\n");
            totalUsed += content.length();
        }

        sb.append("=== 附件内容结束 ===\n\n");
        sb.append("请根据以上附件内容回答用户消息。");

        return sb.toString();
    }

    private void handlePrefixedCommand(String message, String prefix, String toolCategory) {
        String params = message.substring(prefix.length()).trim();
        if ("entertainment".equals(toolCategory)) {
            String type = mapEntertainmentType(prefix);
            if (type != null) executeEntertainment(type, params);
        } else if ("weather".equals(toolCategory)) {
            if (weatherBanner != null) {
                weatherBanner.setVisibility(View.VISIBLE);
                if (weatherBannerController != null) weatherBannerController.show();
            }

            // 如果参数为空且有定位权限，加载当前天气横幅
            if (params.isEmpty() && LocationTool.hasLocationPermission(this) && weatherBannerController != null) {
                weatherBannerController.loadWeather();
                return;
            }
            
            // 使用标准的工具调用方式
            executeTool("ai_weather", params);
        } else {
            executeToolByPrefix(prefix, params);
        }
    }
    
    private String mapEntertainmentType(String prefix) {
        switch (prefix) {
            case "讲笑话": return AIEntertainmentManager.EntertainmentType.JOKE;
            case "猜谜语": return AIEntertainmentManager.EntertainmentType.RIDDLE;
            case "写诗": return AIEntertainmentManager.EntertainmentType.POEM;
            case "讲故事": return AIEntertainmentManager.EntertainmentType.STORY;
            case "知识问答": return AIEntertainmentManager.EntertainmentType.TRIVIA;
            case "名言": return AIEntertainmentManager.EntertainmentType.QUOTE;
            case "游戏": return AIEntertainmentManager.EntertainmentType.GAME;
            default: return null;
        }
    }

    private void executeToolByPrefix(String prefix, String params) {
        String toolName = null;
        if ("翻译".equals(prefix)) toolName = "translation";
        else if ("生成题目".equals(prefix)) toolName = "database";
        else if ("分析题目".equals(prefix)) toolName = "python_calculate";
        else if ("学习计划".equals(prefix)) toolName = "python_execute";
        else if ("统计".equals(prefix)) toolName = "python_calculate";
        else if ("搜索题目".equals(prefix)) toolName = "network_search";
        else if ("导入题目".equals(prefix)) toolName = "file_reader";
        else if ("导出题目".equals(prefix)) toolName = "database";
        else if ("数据库操作".equals(prefix)) toolName = "database";
        else if ("定位".equals(prefix) || "我的位置".equals(prefix) || "当前位置".equals(prefix)) toolName = "ai_weather";

        if (toolName != null) executeTool(toolName, params);
        else processChatMessage(prefix + " " + params);
    }

    private void handleQuickAction(String action) {
        if ("总结对话".equals(action)) {
            addUserMessage("总结对话");
            processChatMessage("总结我们的对话内容，提供一个简洁的概述");
        } else showToast("请输入需要" + action + "的内容");
    }

    private void processChatMessage(String message) {
        try {
            // 根据当前模式决定处理方式
            ChatModeManager.ChatMode currentMode = ChatModeManager.getInstance(this).getCurrentMode();

            // Agent 模式优先：在线AGENT走ReAct（含友好引导），本地AGENT走友好引导（不走ReAct）
            if (currentMode == ChatModeManager.ChatMode.AGENT) {
                if (shouldUseOnlineModel()) {
                    // 在线agent：先显示友好引导，再走ReAct
                    showOnlineAgentFriendlyGuide(message);
                    processChatMessageWithAgent(message);
                } else {
                    // 本地agent：不走ReAct，显示友好引导提示
                    showLocalAgentFriendlyGuide(message);
                }
                return;
            }

            // 非 Agent 模式：检查是否应该使用在线模型（直接流式，无工具调用）
            if (shouldUseOnlineModel()) {
                processChatMessageWithOnlineModel(message);
                return;
            }

            // 其他模式：使用普通聊天
            // 使用本地模型
            if (aiService == null) { addSystemMessage("AI服务未初始化"); return; }
            
            synchronized (streamingLock) {
                if (isGenerating) {
                    AppLogger.aiW(TAG, "processChatMessage skipped, already generating");
                    showToast("AI正在生成中，请稍候");
                    return;
                }
            }
            
            // 检查 Native 层状态，如果无效则自动恢复
            if (!modelBridge.isNativeStateValid()) {
                AppLogger.aiW(TAG, "Native state invalid, triggering auto-recovery");
                addSystemMessage("⚠️ 检测到AI模型状态异常，正在自动恢复...", ChatMessage.SystemMessageType.WARNING);
                if (recoveryHandler != null) {
                    recoveryHandler.setPendingMessage(message);
                    recoveryHandler.triggerAutoRecovery();
                }
                return;
            }

            if (cacheManager != null && aiConfig != null && aiConfig.isCacheEnabled()) {
                String cached = cacheManager.getCachedResponse(message);
                if (cached != null) { addAIMessage(cached); addSystemMessage("(来自缓存)"); return; }
            }

            if (skillManager != null) {
                List<SkillManager.Skill> matchedSkills = skillManager.matchSkills(message);
                if (!matchedSkills.isEmpty()) {
                    message = skillManager.buildSkillPrompt(matchedSkills.get(0).id, message);
                }
            }

            synchronized (streamingLock) {
                agentToolLoopCount = 0;
                thinkingRoundEnded = false;
                thinkingRoundCount = 1;
                currentThinkingMessageIndex = -1;
                currentStreamingContent = new StringBuilder();
                currentThinkingContent = new StringBuilder();
                currentStreamingMessageId = java.util.UUID.randomUUID().toString();
                resetStreamingState();

                ChatMessage initialMessage = ChatMessage.createAIMessage(currentStreamingMessageId, "", System.currentTimeMillis(), null, 0, 0);
                initialMessage.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.INITIALIZING);
                initialMessage.status = ChatMessage.MessageStatus.GENERATING;
                chatHistory.add(initialMessage);
                currentStreamingMessageIndex = chatHistory.size() - 1;
                if (chatAdapter != null) chatAdapter.notifyItemInserted(currentStreamingMessageIndex);
                scrollToBottom();
            }

            beginGeneration();

            final String prompt = message;
            final int streamingIndex = currentStreamingMessageIndex;
            final String streamingId = currentStreamingMessageId;

            runOnUiThread(() -> updateInferencePhase(streamingIndex, ChatMessage.InferencePhase.INITIALIZING, null));

            int actualMaxTokens = aiConfig.getMaxTokens();
            boolean enableThinking = ChatModeManager.getInstance(AIChatActivity.this).getCurrentMode() == ChatModeManager.ChatMode.DEEP_THINKING;
            if (outputRouter != null) {
                outputRouter.reset();
                outputRouter.setThinkingEnabled(enableThinking);
            }
            isInThinking = enableThinking;

            // Agent模式走AgentExecutionEngine，支持步骤更新和工具调用
            // 其他模式走Bridge，普通对话生成
            boolean isAgentMode = ChatModeManager.getInstance(AIChatActivity.this).getCurrentMode() == ChatModeManager.ChatMode.AGENT;

            if (isAgentMode && agentEngine != null && aiConfig.isAgentEnabled()) {
                AppLogger.ai(TAG, "Agent mode: using AgentExecutionEngine");
                // 启动agent执行面板
                if (chatAdapter != null) {
                    chatAdapter.startAgentExecution(streamingId);
                }
                // 委托给agent引擎执行，通过事件回调更新UI
                agentEngine.execute(streamingId, prompt, new ExecutionEventListener() {
                    @Override
                    public void onExecutionEvent(ExecutionEvent event) {
                        runOnUiThread(() -> handleAgentEvent(event, streamingIndex, streamingId));
                    }
                });
            } else {
                AppLogger.ai(TAG, "Bridge sendMessage: promptLen=" + prompt.length() + ", maxTokens=" + actualMaxTokens + ", thinking=" + enableThinking);
                modelBridge.execute(ChatCommand.sendMessage(streamingId, prompt, actualMaxTokens, enableThinking),
                    createBridgeCallback(streamingIndex, streamingId));
            }
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error in processChatMessage: " + e.getMessage());
            endGeneration();
            addSystemMessage("处理消息时出错: " + e.getMessage());
        }
    }

    /**
     * 显示新手引导信息（聊天历史为空时显示）。
     * 帮助用户了解AI对话界面的各项功能和使用方式。
     */
    private void showWelcomeGuide() {
        StringBuilder guide = new StringBuilder();
        guide.append("👋 欢迎使用AI助手！\n\n");
        guide.append("我是你的智能助手，可以帮你查天气、搜索、翻译、查题库等。\n\n");

        guide.append("📋 功能使用指南\n");
        guide.append("──────────────\n\n");

        guide.append("🔝 顶部工具栏\n");
        guide.append("  • 「💬普通 / 🤖Agent」— 切换对话模式\n");
        guide.append("  • 「🤖模型」— 选择/配置AI模型\n");
        guide.append("  • 「📋历史」— 查看历史对话\n");
        guide.append("  • 「🗑️清空」— 清空当前对话\n\n");

        guide.append("🔧 底部工具按钮（Agent模式）\n");
        guide.append("  • 🚗 出行准备 — 一键查天气+空气+预警\n");
        guide.append("  • 🌤 查天气 — 天气/预报/空气质量/预警\n");
        guide.append("  • 🔍 搜索 — 联网搜索/智能问答/读网页\n");
        guide.append("  • 📚 题库 — 搜索题目/分类统计\n");
        guide.append("  • 🌐 翻译 — 多语言翻译\n");
        guide.append("  • 📍 定位 — 获取当前位置\n");
        guide.append("  • 📂 文件 — 文件操作\n");
        guide.append("  • 🔧 计算 — 数学计算\n\n");

        guide.append("💬 两种使用方式\n");
        guide.append("──────────────\n\n");
        guide.append("1️⃣ 离线引导模式（默认，无需网络）\n");
        guide.append("  点击底部工具按钮，一步步引导你完成操作\n");
        guide.append("  适合：明确知道要做什么的操作\n");
        guide.append("  配置：点击顶部「🤖模型」选择本地模型即可离线使用\n\n");
        guide.append("2️⃣ 在线Agent模式（完整功能）\n");
        guide.append("  点击顶部「🤖模型」配置在线模型后\n");
        guide.append("  直接输入需求，Agent自动推理+工具调用\n");
        guide.append("  适合：复杂任务、多轮对话、智能组合工具\n\n");

        guide.append("⚙️ 模型配置\n");
        guide.append("──────────────\n");
        guide.append("  点击顶部「🤖模型」按钮：\n");
        guide.append("  • 本地模型 — 离线使用，无需网络，工具引导模式\n");
        guide.append("  • 在线模型 — 联网使用，完整Agent，智能推理\n\n");

        guide.append("💡 快速开始\n");
        guide.append("──────────────\n");
        guide.append("  • 点击下方工具按钮，立即开始操作\n");
        guide.append("  • 或直接输入消息，我会帮你选择工具\n");
        guide.append("  • 配置在线模型后，享受完整Agent体验\n\n");

        guide.append("试试问我：「今天天气怎么样？」「帮我搜索最新油价」\n");
        guide.append("或者直接点击下方工具按钮开始吧！🎯");

        addAIMessage(guide.toString());
        scrollToBottom();
    }

    private void showLocalAgentFriendlyGuide(String userMessage) {
        StringBuilder guide = new StringBuilder();
        String lower = userMessage.toLowerCase();

        guide.append("你好！我是你的AI助手 🤖\n\n");

        // 根据用户消息内容智能推荐工具
        boolean matched = false;
        if (containsKeyword(lower, "天气", "气温", "下雨", "温度", "weather", "空气质量", "预警")) {
            guide.append("🌤 想查天气？点击下方「查天气」按钮\n");
            guide.append("   可以查当前天气、预报、空气质量、预警等\n\n");
            matched = true;
        }
        if (containsKeyword(lower, "搜索", "搜一下", "查一下", "查找", "search", "百度", "google")) {
            guide.append("🔍 想搜索？点击下方「搜索」按钮\n");
            guide.append("   支持联网搜索、智能问答、网页读取\n\n");
            matched = true;
        }
        if (containsKeyword(lower, "翻译", "translate", "英文", "日文", "韩文")) {
            guide.append("🌐 想翻译？点击下方「翻译」按钮\n");
            guide.append("   支持中英日韩多语言互译\n\n");
            matched = true;
        }
        if (containsKeyword(lower, "题", "题库", "题目", "quiz", "question", "考试")) {
            guide.append("📚 想查题？点击下方「数据库」按钮\n");
            guide.append("   可以搜索题目、查看分类统计\n\n");
            matched = true;
        }
        if (containsKeyword(lower, "位置", "定位", "在哪", "location", "坐标")) {
            guide.append("📍 想定位？点击下方「定位」按钮\n\n");
            matched = true;
        }
        if (containsKeyword(lower, "出行", "出门", "准备", "带伞")) {
            guide.append("🚗 准备出行？点击下方「出行准备」按钮\n");
            guide.append("   一键查询定位→天气→空气→预警\n\n");
            matched = true;
        }
        if (containsKeyword(lower, "计算", "算", "calculate", "+", "-", "×", "÷")) {
            guide.append("🔧 想计算？点击下方「计算」按钮\n\n");
            matched = true;
        }

        if (!matched) {
            guide.append("你可以点击下方工具按钮来执行操作：\n\n");
            guide.append("🚗 出行准备 — 一键查天气+空气+预警\n");
            guide.append("🌤 查天气 — 天气/预报/空气质量/预警\n");
            guide.append("🔍 搜索 — 联网搜索/智能问答/读网页\n");
            guide.append("📚 题库 — 搜索题目/分类统计\n");
            guide.append("🌐 翻译 — 多语言翻译\n");
            guide.append("📍 定位 — 获取当前位置\n");
            guide.append("📂 文件 — 文件操作\n");
            guide.append("🔧 计算 — 数学计算\n\n");
        }

        guide.append("💡 提示：直接点击工具按钮，我会一步步引导你完成操作！\n\n");
        guide.append("──────────────\n");
        guide.append("🚀 想用完整Agent功能？\n");
        guide.append("当前是离线引导模式（工具分步操作）。\n");
        guide.append("点击顶部「🤖模型」按钮配置在线模型，即可启用完整Agent：\n");
        guide.append("  • 直接输入需求，Agent自动推理+工具调用\n");
        guide.append("  • 支持多轮对话和复杂任务\n");
        guide.append("  • 智能组合多个工具完成需求\n");

        addAIMessage(guide.toString());
        scrollToBottom();
    }

    /** 关键词匹配辅助方法 */
    private boolean containsKeyword(String text, String... keywords) {
        if (text == null) return false;
        for (String kw : keywords) {
            if (kw != null && text.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在线agent友好引导：在ReAct开始前显示，根据用户消息智能推荐可能用到的工具。
     * 与本地agent不同，在线agent显示引导后仍走ReAct模式。
     */
    private void showOnlineAgentFriendlyGuide(String userMessage) {
        StringBuilder guide = new StringBuilder();
        String lower = userMessage.toLowerCase();

        guide.append("收到你的消息，我正在处理 🤖\n\n");

        // 根据用户消息内容智能推荐可能用到的工具
        boolean matched = false;
        if (containsKeyword(lower, "天气", "气温", "下雨", "温度", "weather", "空气质量", "预警")) {
            guide.append("🌤 检测到你想查天气，我可能会调用天气工具\n");
            matched = true;
        }
        if (containsKeyword(lower, "搜索", "搜一下", "查一下", "查找", "search", "百度", "google", "最新")) {
            guide.append("🔍 需要联网搜索最新信息，我可能会调用搜索工具\n");
            matched = true;
        }
        if (containsKeyword(lower, "翻译", "translate", "英文", "日文", "韩文")) {
            guide.append("🌐 需要翻译，我可能会调用翻译工具\n");
            matched = true;
        }
        if (containsKeyword(lower, "题", "题库", "题目", "quiz", "question", "考试")) {
            guide.append("📚 需要查询题库，我可能会调用数据库工具\n");
            matched = true;
        }
        if (containsKeyword(lower, "位置", "定位", "在哪", "location", "坐标", "附近")) {
            guide.append("📍 需要位置信息，我可能会调用定位工具\n");
            matched = true;
        }
        if (containsKeyword(lower, "出行", "出门", "准备", "带伞")) {
            guide.append("🚗 准备出行，我可能会组合调用定位+天气+空气质量工具\n");
            matched = true;
        }
        if (containsKeyword(lower, "计算", "算", "calculate", "+", "-", "×", "÷")) {
            guide.append("🔧 需要计算，我可能会调用计算工具\n");
            matched = true;
        }
        if (containsKeyword(lower, "时间", "日期", "今天", "明天", "几点", "星期")) {
            guide.append("🕐 需要时间信息，我会自动获取当前时间\n");
            matched = true;
        }

        if (matched) {
            guide.append("\n");
        } else {
            guide.append("我会根据你的问题选择合适的工具来处理\n\n");
        }

        guide.append("⏳ 正在思考中，请稍候...");

        addSystemMessage(guide.toString());
        scrollToBottom();
    }

    private void processChatMessageWithAgent(String message) {
        try {
            synchronized (streamingLock) {
                if (isGenerating) {
                    AppLogger.aiW(TAG, "processChatMessageWithAgent skipped, already generating");
                    showToast("AI正在生成中，请稍候");
                    return;
                }
            }

            // 显示 Agent 模式激活提示
            addSystemMessage("🤖 Agent模式已激活，正在处理您的请求...");

            // 检查AI服务是否已初始化，如果没有则等待初始化
            boolean useOnlineModel = inferenceRouter != null && inferenceRouter.isUsingOnlineModel();
            if (!useOnlineModel) {
                if (modelBridge == null || !modelBridge.isModelInitialized()) {
                    addSystemMessage("⏳ AI服务正在初始化，请稍候...");
                    // 在后台线程等待 AI 服务初始化（无限等待）
                    new Thread(() -> {
                        int waitCount = 0;
                        
                        while (true) {
                            if (modelBridge != null && modelBridge.isModelInitialized()) {
                                // AI 服务已初始化，继续处理
                                runOnUiThread(() -> {
                                    initAgentChatHandler();
                                    processChatMessageWithAgent(message);
                                });
                                return;
                            }
                            
                            try {
                                Thread.sleep(1000); // 每秒检查一次
                                waitCount++;
                                
                                // 更新等待提示
                                final int currentWait = waitCount;
                                runOnUiThread(() -> {
                                    // 更新最后一条系统消息
                                    if (!chatHistory.isEmpty()) {
                                        ChatMessage lastMsg = chatHistory.get(chatHistory.size() - 1);
                                        if (lastMsg.type == ChatMessage.MessageType.SYSTEM) {
                                            lastMsg.content = "⏳ AI服务正在初始化... (" + currentWait + "秒)";
                                            if (chatAdapter != null) {
                                                chatAdapter.notifyItemChanged(chatHistory.size() - 1);
                                            }
                                        }
                                    }
                                });
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                runOnUiThread(() -> addErrorMessage("初始化被中断", "用户取消了等待", false));
                                return;
                            }
                        }
                    }).start();
                    return;
                }
            }

            // AI 服务已初始化，继续处理
            initAgentChatHandlerIfNeeded();

            synchronized (streamingLock) {
                agentToolLoopCount = 0;
                thinkingRoundEnded = false;
                thinkingRoundCount = 1;
                currentThinkingMessageIndex = -1;
                currentStreamingContent = new StringBuilder();
                currentThinkingContent = new StringBuilder();
                currentStreamingMessageId = java.util.UUID.randomUUID().toString();
                resetStreamingState();

                ChatMessage initialMessage = ChatMessage.createAIMessage(currentStreamingMessageId, "", System.currentTimeMillis(), null, 0, 0);
                initialMessage.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.INITIALIZING);
                initialMessage.status = ChatMessage.MessageStatus.GENERATING;
                chatHistory.add(initialMessage);
                currentStreamingMessageIndex = chatHistory.size() - 1;
                if (chatAdapter != null) chatAdapter.notifyItemInserted(currentStreamingMessageIndex);
                scrollToBottom();
            }

            beginGeneration();

            int maxTokens = aiConfig != null ? aiConfig.getMaxTokens() : 4096;
            // 深度思考模式启用模型思考链
            boolean enableThinking = ChatModeManager.getInstance(this).getCurrentMode() == ChatModeManager.ChatMode.DEEP_THINKING;
            if (outputRouter != null) {
                outputRouter.reset();
                outputRouter.setThinkingEnabled(enableThinking);
            }
            isInThinking = enableThinking;
            agentChatHandler.startAgentLoop(message, maxTokens, enableThinking);

        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error in processChatMessageWithAgent: " + e.getMessage());
            endGeneration();
            addSystemMessage("Agent处理消息时出错: " + e.getMessage());
        }
    }

    private void processChatMessageWithOnlineModel(String message) {
        try {
            synchronized (streamingLock) {
                if (isGenerating) {
                    AppLogger.aiW(TAG, "processChatMessageWithOnlineModel skipped, already generating");
                    showToast("AI正在生成中，请稍候");
                    return;
                }
            }

            if (skillManager != null) {
                List<SkillManager.Skill> matchedSkills = skillManager.matchSkills(message);
                if (!matchedSkills.isEmpty()) {
                    message = skillManager.buildSkillPrompt(matchedSkills.get(0).id, message);
                }
            }

            synchronized (streamingLock) {
                currentStreamingContent = new StringBuilder();
                currentStreamingMessageId = java.util.UUID.randomUUID().toString();

                ChatMessage initialMessage = ChatMessage.createAIMessage(currentStreamingMessageId, "", System.currentTimeMillis(), null, 0, 0);
                initialMessage.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.INITIALIZING);
                initialMessage.status = ChatMessage.MessageStatus.GENERATING;
                chatHistory.add(initialMessage);
                currentStreamingMessageIndex = chatHistory.size() - 1;
                if (chatAdapter != null) chatAdapter.notifyItemInserted(currentStreamingMessageIndex);
                scrollToBottom();
            }

            beginGeneration();

            final String prompt = message;
            final int streamingIndex = currentStreamingMessageIndex;
            final String streamingId = currentStreamingMessageId;
            final long chatStartTime = System.currentTimeMillis();

            new Thread(() -> {
                try {
                    runOnUiThread(() -> updateInferencePhase(streamingIndex, ChatMessage.InferencePhase.ENCODING, "正在连接云端模型..."));

                    AIInferenceCore.InferenceConfig config = new AIInferenceCore.InferenceConfig();
                    config.maxTokens = aiConfig != null ? aiConfig.getMaxTokens() : 8192;
                    config.temperature = 0.7f;
                    
                    List<ChatMessage> historyForOnline = new ArrayList<>();
                    for (ChatMessage msg : chatHistory) {
                        if (msg.type == ChatMessage.MessageType.USER || msg.type == ChatMessage.MessageType.AI) {
                            if (msg != chatHistory.get(chatHistory.size() - 1)) {
                                historyForOnline.add(msg);
                            }
                        }
                    }
                    if (historyForOnline.size() > 20) {
                        historyForOnline = historyForOnline.subList(historyForOnline.size() - 20, historyForOnline.size());
                    }
                    config.history = historyForOnline;

                    AppLogger.ai(TAG, "Calling online inference: promptLen=" + prompt.length() + ", maxTokens=" + config.maxTokens);
                    
                    inferenceRouter.generateStream(prompt, config, new StreamCallback() {
                        private int onlineTokenCount = 0;
                        private boolean onlineUpdateScheduled = false;
                        private final Runnable onlineUpdateRunnable = () -> {
                            onlineUpdateScheduled = false;
                            if (chatAdapter != null && currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                                ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                                if (currentStreamingContent != null) {
                                    msg.content = currentStreamingContent.toString();
                                }
                                msg.status = ChatMessage.MessageStatus.GENERATING;
                                chatAdapter.updateAIMessageContent(currentStreamingMessageIndex, msg.content);
                                scrollToBottom();
                            }
                        };

                        @Override
                        public void onStart() {
                            runOnUiThread(() -> updateInferencePhase(streamingIndex, ChatMessage.InferencePhase.ENCODING, "云端模型正在思考..."));
                        }

                        @Override
                        public void onToken(String token) {
                            synchronized (streamingLock) {
                                if (currentStreamingContent != null) {
                                    currentStreamingContent.append(token);
                                }
                            }
                            onlineTokenCount++;
                            // 每 5 个 token 或每 80ms 更新一次 UI，避免刷屏
                            if (onlineTokenCount % 5 == 0) {
                                runOnUiThread(onlineUpdateRunnable);
                            } else if (!onlineUpdateScheduled) {
                                onlineUpdateScheduled = true;
                                uiHandler.postDelayed(onlineUpdateRunnable, 80);
                            }
                        }

                        @Override
                        public void onComplete(String fullText) {
                            runOnUiThread(() -> {
                                uiHandler.removeCallbacks(onlineUpdateRunnable);
                                onlineUpdateScheduled = false;
                                endGeneration();
                                if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                                    ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                                    msg.content = fullText != null ? fullText : "";
                                    msg.status = ChatMessage.MessageStatus.COMPLETED;
                                    msg.inferenceProgress = null;
                                    if (chatAdapter != null) {
                                        chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
                                    }
                                    saveHistoryAsync();
                                    scrollToBottom();
                                    
                                    long elapsedMs = System.currentTimeMillis() - chatStartTime;
                                    AppLogger.ai(TAG, "Online inference completed: elapsed=" + elapsedMs + "ms, tokens=" + (fullText != null ? fullText.length() : 0));
                                }
                            });
                        }

                        @Override
                        public void onError(String error) {
                            runOnUiThread(() -> {
                                endGeneration();
                                handleGenerationError("云端模型推理失败: " + error);
                            });
                        }
                    });
                } catch (Exception e) {
                    AppLogger.aiE(TAG, "Error in online chat: " + e.getMessage());
                    runOnUiThread(() -> handleGenerationError("云端模型推理失败: " + e.getMessage()));
                }
            }).start();
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error in processChatMessageWithOnlineModel: " + e.getMessage());
            endGeneration();
            addSystemMessage("处理消息时出错: " + e.getMessage());
        }
    }

    private void updateInferencePhase(int messageIndex, ChatMessage.InferencePhase phase, String additionalInfo) {
        if (messageIndex < 0 || messageIndex >= chatHistory.size()) return;
        
        ChatMessage msg = chatHistory.get(messageIndex);
        if (msg.inferenceProgress == null) {
            msg.inferenceProgress = new ChatMessage.InferenceProgress(phase);
        } else {
            msg.inferenceProgress.phase = phase;
        }
        
        if (additionalInfo != null) {
            msg.inferenceProgress.additionalInfo = additionalInfo;
        }
        
        if (chatAdapter != null) {
            chatAdapter.notifyItemChanged(messageIndex, ChatAdapter.PAYLOAD_STATUS_UPDATE);
        }
    }
    
    private void updateInferenceProgress(int messageIndex, int processedTokens, float tokensPerSecond) {
        if (messageIndex < 0 || messageIndex >= chatHistory.size()) return;
        
        ChatMessage msg = chatHistory.get(messageIndex);
        if (msg.inferenceProgress != null) {
            msg.inferenceProgress.processedTokens = processedTokens;
            msg.inferenceProgress.tokensPerSecond = tokensPerSecond;
            if (chatAdapter != null) {
                chatAdapter.notifyItemChanged(messageIndex, ChatAdapter.PAYLOAD_STATUS_UPDATE);
            }
        }
    }

    private void handleGenerationError(String errorMsg) {
        runOnUiThread(() -> {
            endGeneration();
            addSystemMessage(errorMsg);
            if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                if (currentStreamingContent != null && currentStreamingContent.length() > 0) {
                    ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                    msg.content = currentStreamingContent.toString();
                    msg.status = ChatMessage.MessageStatus.COMPLETED;
                    msg.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.FAILED);
                    if (chatAdapter != null) chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
                } else {
                    chatHistory.remove(currentStreamingMessageIndex);
                    if (chatAdapter != null) chatAdapter.notifyItemRemoved(currentStreamingMessageIndex);
                    saveHistoryAsync();
                }
            }
            currentStreamingContent = null;
            thinkingRoundEnded = false;
            thinkingRoundCount = 1;
            currentStreamingMessageIndex = -1;
            currentThinkingMessageIndex = -1;
            currentStreamingMessageId = null;
        });
    }

    /**
     * 创建Bridge回调 - 将模型执行结果路由到UI更新方法
     * 这是UI与模型之间的唯一回调通道
     */
    private BridgeCallback createBridgeCallback(int streamingIndex, String streamingId) {
        final long[] startTime = {System.currentTimeMillis()};
        final int[] tokenCount = {0};

        return new BridgeCallback() {
            @Override
            public void onGenerationStarted(String messageId) {
                runOnUiThread(() -> updateInferencePhase(streamingIndex, ChatMessage.InferencePhase.GENERATING, null));
            }

            @Override
            public void onToken(String messageId, String token) {
                tokenCount[0]++;
                if (tokenCount[0] % 10 == 0) {
                    long elapsed = System.currentTimeMillis() - startTime[0];
                    float tps = elapsed > 0 ? (tokenCount[0] * 1000.0f) / elapsed : 0;
                    runOnUiThread(() -> updateInferenceProgress(streamingIndex, tokenCount[0], tps));
                }
                runOnUiThread(() -> handleStreamToken(token));
            }

            @Override
            public void onGenerationComplete(String messageId, String fullContent,
                                              int tokens, long elapsedMs, float tps) {
                completeGeneration(fullContent, tokens, startTime[0]);
            }

            @Override
            public void onGenerationError(String messageId, String error) {
                runOnUiThread(() -> {
                    endGeneration();
                    boolean nativeInvalid = !modelBridge.isNativeStateValid();
                    boolean shouldRecover = nativeInvalid &&
                        (recoveryHandler == null || !recoveryHandler.isRecovering()) &&
                        (serviceStatusManager == null || !serviceStatusManager.isLoadingModel());

                    if (currentStreamingContent != null && currentStreamingContent.length() > 0
                        && currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                        ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                        msg.content = currentStreamingContent.toString();
                        msg.status = ChatMessage.MessageStatus.COMPLETED;
                        if (nativeInvalid) {
                            msg.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.FAILED);
                        }
                        if (chatAdapter != null) chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
                        if (!nativeInvalid) addSystemMessage("生成中断，已保存部分内容");
                    } else if (currentStreamingMessageIndex >= 0) {
                        chatHistory.remove(currentStreamingMessageIndex);
                        if (chatAdapter != null) chatAdapter.notifyItemRemoved(currentStreamingMessageIndex);
                    }
                    saveHistoryAsync();
                    currentStreamingContent = null;
                    currentStreamingMessageIndex = -1;
                    currentStreamingMessageId = null;

                    if (shouldRecover) {
                        addSystemMessage("⚠️ 生成失败，检测到AI模型状态异常，尝试自动恢复...",
                            ChatMessage.SystemMessageType.WARNING);
                        if (recoveryHandler != null) {
                            recoveryHandler.setPendingMessage(null);
                            recoveryHandler.triggerAutoRecovery();
                        }
                    } else {
                        addErrorMessage("生成出错", error, true);
                    }
                });
            }

            @Override
            public void onGenerationStopped(String messageId) {
                runOnUiThread(() -> {
                    endGeneration();
                    if (currentStreamingContent != null && currentStreamingContent.length() > 0
                        && currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                        ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                        msg.content = currentStreamingContent.toString();
                        msg.status = ChatMessage.MessageStatus.COMPLETED;
                        if (chatAdapter != null) chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
                    }
                    saveHistoryAsync();
                    currentStreamingContent = null;
                    currentStreamingMessageIndex = -1;
                    currentStreamingMessageId = null;
                });
            }

            @Override
            public void onInferenceProgress(String messageId, int tokens, float tps) {
                runOnUiThread(() -> updateInferenceProgress(streamingIndex, tokens, tps));
            }

            @Override
            public void onContextCleared() {
                runOnUiThread(() -> addSystemMessage("上下文已清除"));
            }

            @Override
            public void onContextInitialized(boolean success) {}

            @Override
            public void onModelInitialized(boolean success, String modelName) {
                if (success) {
                    runOnUiThread(() -> addSystemMessage("模型已加载: " + modelName));
                }
            }

            @Override
            public void onModelReloaded(boolean success) {}

            @Override
            public void onModelInfo(String modelName, boolean isInitialized, boolean usingGPU, int gpuLayers) {}

            @Override
            public void onTokenCount(int count) {}

            @Override
            public void onNativeStateChecked(boolean isValid) {}

            @Override
            public void onMemoryPressureHandled(int result) {}

            @Override
            public void onToolCallStart(String messageId, String toolName, String args) {
                runOnUiThread(() -> appendToolCallInProgress(toolName));
            }

            @Override
            public void onToolCallComplete(String messageId, String toolName, boolean success, String result) {
                runOnUiThread(() -> {
                    if (currentStreamingContent != null) {
                        currentStreamingContent.append("\n" + (success ? "✅" : "❌") + " 工具结果\n");
                        safeUpdateMessage();
                    }
                });
            }

            @Override
            public void onThinkingUpdate(String messageId, int stepNumber, String stepType,
                                          String title, String content, int progress) {}
        };
    }

    /**
     * 处理AgentExecutionEngine的事件，路由到ChatAdapter更新UI
     */
    private void handleAgentEvent(ExecutionEvent event, int streamingIndex, String streamingId) {
        if (chatAdapter == null) return;

        switch (event.type) {
            case EXECUTION_STARTED:
                updateInferencePhase(streamingIndex, ChatMessage.InferencePhase.GENERATING, null);
                break;

            case TOKEN_GENERATED:
                if (event.text != null) {
                    handleStreamToken(event.text);
                }
                break;

            case THINKING_TOKEN:
                if (event.text != null) {
                    handleStreamToken(event.text);
                }
                break;

            case STEP_STARTED:
                chatAdapter.updateAgentExecutionStep(streamingId, event.stepNumber,
                    event.stepType, event.stepTitle, event.stepContent);
                break;

            case TOOL_CALL_STARTED:
                chatAdapter.updateAgentToolCall(streamingId, event.toolName, event.toolArgs);
                break;

            case TOOL_CALL_COMPLETED:
                chatAdapter.updateAgentToolResult(streamingId, event.toolName,
                    event.success, event.toolResult);
                break;

            case INFERENCE_PROGRESS:
                chatAdapter.updateAgentInferenceProgress(streamingId,
                    event.tokenCount, event.tokensPerSecond);
                updateInferenceProgress(streamingIndex, event.tokenCount, event.tokensPerSecond);
                break;

            case EXECUTION_COMPLETED:
                completeGeneration(event.text, 0, System.currentTimeMillis());
                chatAdapter.completeAgentExecution(streamingId, event.text);
                break;

            case EXECUTION_FAILED:
                endGeneration();
                chatAdapter.failAgentExecution(streamingId, event.errorMessage);
                if (currentStreamingContent != null && currentStreamingContent.length() > 0
                    && streamingIndex >= 0 && streamingIndex < chatHistory.size()) {
                    ChatMessage msg = chatHistory.get(streamingIndex);
                    msg.content = currentStreamingContent.toString();
                    msg.status = ChatMessage.MessageStatus.COMPLETED;
                    chatAdapter.notifyItemChanged(streamingIndex);
                }
                saveHistoryAsync();
                currentStreamingContent = null;
                currentStreamingMessageIndex = -1;
                currentStreamingMessageId = null;
                addErrorMessage("Agent执行出错", event.errorMessage, true);
                break;

            case EXECUTION_CANCELLED:
                endGeneration();
                chatAdapter.failAgentExecution(streamingId, "已取消");
                currentStreamingContent = null;
                currentStreamingMessageIndex = -1;
                currentStreamingMessageId = null;
                break;

            default:
                break;
        }
    }

    private class StreamingTokenHandler implements LlamaHelper.TokenCallback {
        private final int streamingIndex;
        private final String streamingId;
        private final String prompt;
        private final long chatStartTime;
        private boolean isFirstToken = true;
        private int tokenCount = 0;
        private volatile boolean isCompleted = false;

        StreamingTokenHandler(int streamingIndex, String streamingId, String prompt, long chatStartTime) {
            this.streamingIndex = streamingIndex;
            this.streamingId = streamingId;
            this.prompt = prompt;
            this.chatStartTime = chatStartTime;
        }

        @Override
        public void onToken(String token) {
            if (isCompleted) {
                AppLogger.aiW(TAG, "onToken called after completion, ignoring");
                return;
            }
            if (isFirstToken) {
                isFirstToken = false;
                runOnUiThread(() -> updateInferencePhase(streamingIndex, ChatMessage.InferencePhase.GENERATING, null));
            }
            tokenCount++;
            
            if (tokenCount % 10 == 0) {
                long elapsed = System.currentTimeMillis() - chatStartTime;
                float tokensPerSecond = elapsed > 0 ? (tokenCount * 1000.0f) / elapsed : 0;
                runOnUiThread(() -> updateInferenceProgress(streamingIndex, tokenCount, tokensPerSecond));
            }
            
            runOnUiThread(() -> handleStreamToken(token));
        }

        @Override
        public void onComplete(String fullText) {
            if (isCompleted) {
                AppLogger.aiW(TAG, "onComplete called multiple times, ignoring duplicate call");
                return;
            }
            isCompleted = true;
            
            if (fullText != null && fullText.contains("<|tool_call_begin|>") && agentService != null && agentToolLoopCount < agentService.getMaxToolLoops()) {
                List<AgentService.ToolCall> toolCalls = agentService.parseToolCalls(fullText);
                if (!toolCalls.isEmpty()) {
                    AgentService.ToolCall call = toolCalls.get(0);
                    agentToolLoopCount++;
                    runOnUiThread(() -> appendToolCallInProgress(call.name));

                    new Thread(() -> {
                        try {
                            AgentService.ToolResult result = agentService.executeTool(call);
                            boolean success = result != null && result.success;
                            String toolResultMsg = agentService.formatToolResultForContext(result);

                            runOnUiThread(() -> {
                                if (currentStreamingContent != null) {
                                    currentStreamingContent.append("\n" + (success ? "✅" : "❌") + " 工具结果\n");
                                    safeUpdateMessage();
                                }
                            });

                            aiService.chatSend(toolResultMsg, aiConfig.getMaxTokens(), false, new ProtectedTokenCallback("工具调用", text -> completeGeneration(text)));
                        } catch (Exception e) {
                            runOnUiThread(() -> handleGenerationError("工具执行出错: " + e.getMessage()));
                        }
                    }).start();
                    return;
                }
            }
            completeGeneration(fullText, tokenCount, chatStartTime);
        }

        @Override
        public void onError(String error) {
            if (isCompleted) {
                AppLogger.aiW(TAG, "onError called after completion, ignoring");
                return;
            }
            isCompleted = true;
            runOnUiThread(() -> {
                endGeneration();
                
                // 检查是否因 Native 状态无效导致错误
                boolean nativeInvalid = !modelBridge.isNativeStateValid();
                boolean shouldRecover = nativeInvalid && (recoveryHandler == null || !recoveryHandler.isRecovering()) && (serviceStatusManager == null || !serviceStatusManager.isLoadingModel());
                
                if (currentStreamingContent != null && currentStreamingContent.length() > 0 && currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                    ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                    msg.content = currentStreamingContent.toString();
                    msg.status = ChatMessage.MessageStatus.COMPLETED;
                    if (nativeInvalid) {
                        msg.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.FAILED);
                    }
                    if (chatAdapter != null) chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
                    if (!nativeInvalid) {
                        addSystemMessage("生成中断，已保存部分内容");
                    }
                } else if (currentStreamingMessageIndex >= 0) {
                    chatHistory.remove(currentStreamingMessageIndex);
                    if (chatAdapter != null) chatAdapter.notifyItemRemoved(currentStreamingMessageIndex);
                }
                saveHistoryAsync();
                currentStreamingContent = null;
                currentStreamingMessageIndex = -1;
                currentStreamingMessageId = null;
                
                if (shouldRecover) {
                    addSystemMessage("⚠️ 生成失败，检测到AI模型状态异常，尝试自动恢复...",
                        ChatMessage.SystemMessageType.WARNING);
                    if (recoveryHandler != null) {
                        recoveryHandler.setPendingMessage(null);
                        recoveryHandler.triggerAutoRecovery();
                    }
                } else {
                    addErrorMessage("生成出错", error, true);
                }
            });
        }
    }

    private void handleStreamToken(String token) {
        // 使用 OutputRouter 处理 token
        if (outputRouter != null) {
            outputRouter.processToken(token);
        } else {
            // 回退到旧的处理方式
            handleStreamTokenLegacy(token);
        }

        // 同时更新 streamingUpdateManager 以计算 token 速度
        if (streamingUpdateManager != null) {
            streamingUpdateManager.addToken(token);
        } else {
            tokenCountSinceLastUpdate++;
            long now = System.currentTimeMillis();
            if (tokenCountSinceLastUpdate >= BATCH_TOKEN_COUNT || now - lastUpdateTime >= BATCH_INTERVAL_MS || !isUpdateScheduled) {
                safeUpdateMessage();
            } else if (!isUpdateScheduled) {
                isUpdateScheduled = true;
                uiHandler.postDelayed(() -> { if (isUpdateScheduled) safeUpdateMessage(); }, BATCH_INTERVAL_MS - (now - lastUpdateTime));
            }
        }
    }

    /**
     * 旧的 token 处理方式（兼容）
     */
    private void handleStreamTokenLegacy(String token) {
        if (token.equals("[TOOL_CALL]")) return;
        if (token.equals("[THINK_END]")) {
            isInThinking = false;
            if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                msg.thinkingContent = currentThinkingContent != null ? currentThinkingContent.toString() : "";
                if (chatAdapter != null) chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
            }
            return;
        }

        if (token.contains("<")) {
            isInTag = true;
            if (tagBuffer == null) tagBuffer = new StringBuilder();
            tagBuffer.append(token);
            if (token.contains(">")) processTagBuffer(tagBuffer.toString());
            return;
        }
        if (isInTag) {
            if (tagBuffer != null) tagBuffer.append(token);
            if (token.contains(">")) processTagBuffer(tagBuffer.toString());
            return;
        }

        if (isInThinking && currentThinkingContent != null) {
            currentThinkingContent.append(token);
            if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                msg.thinkingContent = currentThinkingContent.toString();
                if (chatAdapter != null) chatAdapter.updateMessageThinkingContent(currentStreamingMessageIndex, currentThinkingContent.toString());
            }
            return;
        }

        if (currentStreamingContent != null) {
            currentStreamingContent.append(token);
            totalTokensGenerated++;
            
            if (streamingUpdateManager != null) {
                streamingUpdateManager.addToken(token);
            } else {
                tokenCountSinceLastUpdate++;
                scheduleStreamingUpdate();
            }
        }
    }

    private void scheduleStreamingUpdate() {
        if (streamingUpdateManager != null) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        long timeSinceLastUpdate = currentTime - lastUpdateTime;
        boolean shouldUpdateNow = tokenCountSinceLastUpdate >= BATCH_TOKEN_COUNT || timeSinceLastUpdate >= BATCH_INTERVAL_MS || !isUpdateScheduled;

        if (shouldUpdateNow) {
            safeUpdateMessage();
            scrollToBottom();
            tokenCountSinceLastUpdate = 0;
            lastUpdateTime = currentTime;
            isUpdateScheduled = false;
        } else if (!isUpdateScheduled) {
            isUpdateScheduled = true;
            uiHandler.postDelayed(() -> {
                if (isUpdateScheduled) {
                    safeUpdateMessage();
                    scrollToBottom();
                    tokenCountSinceLastUpdate = 0;
                    lastUpdateTime = System.currentTimeMillis();
                    isUpdateScheduled = false;
                }
            }, BATCH_INTERVAL_MS - timeSinceLastUpdate);
        }
    }

    private void completeGeneration(String fullText) {
        completeGeneration(fullText, 0, 0);
    }

    private void completeGeneration(String fullText, int tokenCount, long chatStartTime) {
        // 通知 OutputRouter 流式完成
        if (outputRouter != null) {
            outputRouter.complete();
        }

        runOnUiThread(() -> {
            final int messageIndex = currentStreamingMessageIndex;
            final String finalContent = currentStreamingContent != null ? currentStreamingContent.toString() : fullText;
            final long generationStart = chatStartTime;
            
            // 先获取统计信息，再刷新流内容（在 endGeneration 之前）
            int statsTokens = 0;
            long statsTime = 0;
            if (streamingUpdateManager != null) {
                statsTokens = streamingUpdateManager.getTotalTokensGenerated();
                statsTime = streamingUpdateManager.getElapsedTimeMs();
                streamingUpdateManager.flush();
            }
            
            endGeneration();
            agentToolLoopCount = 0;
            thinkingRoundEnded = false;
            thinkingRoundCount = 1;
            if (messageIndex >= 0 && messageIndex < chatHistory.size()) {
                ChatMessage finalMsg = chatHistory.get(messageIndex);
                finalMsg.content = finalContent;
                finalMsg.status = ChatMessage.MessageStatus.COMPLETED;
                
                int finalTokenCount = tokenCount;
                if (finalTokenCount <= 0 && finalContent != null && !finalContent.isEmpty()) {
                    if (statsTokens > 0) {
                        finalTokenCount = statsTokens;
                    } else {
                        finalTokenCount = finalContent.length() / 4;
                    }
                }
                if (finalTokenCount > 0) {
                    finalMsg.tokensGenerated = finalTokenCount;
                }
                
                long finalGenerationTime = generationStart > 0 ? System.currentTimeMillis() - generationStart : 0;
                if (finalGenerationTime <= 0 && statsTime > 0) {
                    finalGenerationTime = statsTime;
                }
                if (finalGenerationTime > 0) {
                    finalMsg.generationTimeMs = finalGenerationTime;
                }
                
                if (finalMsg.inferenceProgress != null) {
                    finalMsg.inferenceProgress.phase = ChatMessage.InferencePhase.COMPLETED;
                }

                // 设置 GPU 加速信息
                try {
                    int gpuLayers = LlamaHelper.getGPULayers();
                    finalMsg.gpuLayers = gpuLayers;
                    finalMsg.usingGPU = gpuLayers > 0 && LlamaHelper.isGPUWorking();
                } catch (Exception e) {
                    finalMsg.gpuLayers = 0;
                    finalMsg.usingGPU = false;
                }

                if (currentThinkingContent != null && currentThinkingContent.length() > 0) {
                    finalMsg.thinkingContent = currentThinkingContent.toString();
                }
                
                if (chatAdapter != null) {
                    chatAdapter.notifyItemChanged(messageIndex);
                    if (finalTokenCount > 0 && finalGenerationTime > 0) {
                        chatAdapter.updateMessageGenerationStats(messageIndex, finalTokenCount, finalGenerationTime);
                    }
                }
                saveHistoryAsync();

                // 更新 Token 统计（生成完成时累加到 session）
                if (finalTokenCount > 0) {
                    int inputTokens = 0;
                    if (messageIndex >= 1 && chatHistory.get(messageIndex - 1) != null) {
                        String promptText = chatHistory.get(messageIndex - 1).content;
                        if (promptText != null && !promptText.isEmpty()) {
                            inputTokens = LlamaHelper.countTokens(promptText);
                        }
                    }
                    TokenStatsManager.getInstance().updateRequestStats(inputTokens, finalTokenCount);
                    AppLogger.i(TAG, "Token统计 - 输入: " + inputTokens + ", 输出: " + finalTokenCount);
                }

                if (cacheManager != null && aiConfig != null && aiConfig.isCacheEnabled() && finalContent != null && !finalContent.isEmpty()) {
                    String prompt = messageIndex >= 1 ? chatHistory.get(messageIndex - 1).content : "";
                    if (!prompt.isEmpty()) cacheManager.cacheResponse(prompt, finalContent);
                }
            }
            currentStreamingContent = null;
            currentThinkingContent = null;
            isInThinking = false;
            isInTag = false;
            if (tagBuffer != null) tagBuffer.setLength(0);
            currentStreamingMessageIndex = -1;
            currentStreamingMessageId = null;
            scrollToBottom();
        });
    }

    private void appendToolCallInProgress(String toolName) {
        if (currentStreamingContent != null) {
            currentStreamingContent.append("\n🔧 调用: " + toolName + " ...");
            if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                ChatMessage toolMsg = chatHistory.get(currentStreamingMessageIndex);
                toolMsg.content = currentStreamingContent.toString();
                toolMsg.status = ChatMessage.MessageStatus.GENERATING;
                if (currentThinkingContent != null && currentThinkingContent.length() > 0) toolMsg.thinkingContent = currentThinkingContent.toString();
                if (chatAdapter != null) chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
            }
            scrollToBottom();
        }
    }

    private void processTagBuffer(String tagContent) {
        if (tagContent == null) return;
        if (tagContent.contains("think")) {
            isInThinking = !tagContent.contains("/");
        }
        if (tagBuffer != null) tagBuffer.setLength(0);
        isInTag = false;
    }

    // ===================== Agent Callbacks =====================

    private class AgentCallbackImpl implements AgentChatHandler.AgentChatCallback {
        @Override
        public void onToolCallStart(String toolName, String args) {
            // 工具调用开始：添加工具调用消息到聊天
            runOnUiThread(() -> {
                addToolCallMessage(toolName, args);
                scrollToBottom();
            });
        }

        @Override
        public void onToolCallComplete(String toolName, OnlineToolResult result) {
            // 工具调用完成：更新工具调用结果
            runOnUiThread(() -> {
                int pos = findLastSpecialMessage(ChatMessage.MessageType.TOOL_CALL);
                boolean success = result != null && result.success;
                String resultStr = result != null ? result.result : "无结果";
                updateToolCallResult(pos >= 0 ? pos : chatHistory.size() - 1, success, resultStr);
                scrollToBottom();
            });
        }

        @Override
        public void onToken(String token) {
            // 流式 token：追加到当前流式内容（始终累积，不丢失）
            if (currentStreamingContent != null) {
                currentStreamingContent.append(token);
                // 节流：距上次 UI 更新 ≥ 80ms 才刷新，避免高频 token 卡顿主线程
                long now = System.currentTimeMillis();
                if (now - lastTokenUiUpdateTime >= UI_UPDATE_THROTTLE_MS) {
                    lastTokenUiUpdateTime = now;
                    runOnUiThread(() -> {
                        safeUpdateMessage();
                        scrollToBottom();
                    });
                }
            }
        }

        @Override
        public void onThinkingToken(String token) {
            // 思考 token：每轮创建独立的思考消息块，自由插入到agent执行流中
            // 注意：本回调已通过 OnlineAgentEngine.runOnUiThread 在UI线程调用，
            // 内部不能再 runOnUiThread（否则会post到队列，导致下一轮token先于addThinkingMessage执行）
            boolean onUi =Looper.myLooper() == Looper.getMainLooper();
            // 新一轮思考开始：上一轮已结束或还没有思考消息时，创建新消息
            if (thinkingRoundEnded || currentThinkingMessageIndex < 0) {
                thinkingRoundEnded = false;
                thinkingRoundCount++;
                currentThinkingContent = new StringBuilder();
                if (onUi) {
                    addThinkingMessage(thinkingRoundCount);
                } else {
                    runOnUiThread(() -> addThinkingMessage(thinkingRoundCount));
                }
            }
            if (currentThinkingContent != null) {
                currentThinkingContent.append(token);
                // 节流：思考链可能很长，每 80ms 更新一次 UI
                long now = System.currentTimeMillis();
                if (now - lastThinkingUiUpdateTime >= UI_UPDATE_THROTTLE_MS) {
                    lastThinkingUiUpdateTime = now;
                    if (onUi) {
                        updateThinkingMessageUi();
                    } else {
                        runOnUiThread(() -> updateThinkingMessageUi());
                    }
                }
            }
        }

        @Override
        public void onThinkingEnd() {
            isInThinking = false;
            // 标记本轮思考结束，下一轮 onThinkingToken 时创建新的思考消息
            thinkingRoundEnded = true;
            // 思考结束：强制最终更新 + 折叠当前思考消息
            // 注意：必须在当前线程同步执行，不能post到队列，
            // 否则下一轮 onThinkingToken 会先执行并重置 currentThinkingContent，导致本轮内容丢失
            boolean onUi = Looper.myLooper() == Looper.getMainLooper();
            if (onUi) {
                finalizeThinkingMessage();
            } else {
                runOnUiThread(() -> finalizeThinkingMessage());
            }
        }

        @Override
        public void onComplete(String fullText) {
            completeGeneration(fullText);
            // Agent执行完成提示
            runOnUiThread(() -> {
                addSystemMessage("✅ Agent执行完成");
                scrollToBottom();
            });
        }

        @Override
        public void onError(String error) {
            if ("[TOOL_CALL]".equals(error)) return;
            runOnUiThread(() -> handleGenerationError("Agent出错: " + error));
        }

        @Override
        public void onModeSwitched(String mode) {
        }

        @Override
        public void onAgentStep(ChatMessage.AgentStepInfo stepInfo) {
            // Agent 步骤：添加步骤消息到聊天
            runOnUiThread(() -> {
                addAgentStepMessage(stepInfo);
                scrollToBottom();
            });
        }

        @Override
        public void onToolCallUI(String toolName, String args, int position) {
            // 工具调用 UI：添加工具调用消息
            runOnUiThread(() -> {
                addToolCallMessage(toolName, args);
                scrollToBottom();
            });
        }

        @Override
        public void onToolCallResultUI(int position, boolean success, String result) {
            // 工具调用结果 UI：更新工具调用结果
            runOnUiThread(() -> {
                int pos = findLastSpecialMessage(ChatMessage.MessageType.TOOL_CALL);
                updateToolCallResult(pos >= 0 ? pos : chatHistory.size() - 1, success, result);
                scrollToBottom();
            });
        }

        @Override
        public void onAgentStepUpdateUI(int position, String thought, String action,
                                        String observation, boolean isCompleted) {
            // Agent 步骤更新 UI：更新步骤结果
            runOnUiThread(() -> {
                int pos = findLastSpecialMessage(ChatMessage.MessageType.AGENT_STEP);
                updateAgentStepResult(pos >= 0 ? pos : chatHistory.size() - 1,
                    thought, action, observation, isCompleted);
                scrollToBottom();
            });
        }

        @Override
        public void onInferenceProgress(int tokenCount, float tokensPerSecond) {
            // 更新推理速度显示 - 与普通模式使用相同的方式
            runOnUiThread(() -> {
                // 更新底部统计栏
                updateStreamingTokenStats(tokenCount, tokensPerSecond);

                // 更新当前消息的推理进度 - 与普通模式相同
                if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                    ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                    if (msg.inferenceProgress == null) {
                        msg.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.GENERATING);
                    }
                    msg.inferenceProgress.processedTokens = tokenCount;
                    msg.inferenceProgress.tokensPerSecond = tokensPerSecond;

                    // 使用与普通模式相同的payload更新UI
                    if (chatAdapter != null) {
                        chatAdapter.notifyItemChanged(currentStreamingMessageIndex, ChatAdapter.PAYLOAD_STATUS_UPDATE);
                    }
                }
            });
        }
    }

    private int findLastSpecialMessage(ChatMessage.MessageType type) {
        for (int i = chatHistory.size() - 1; i >= 0; i--) {
            ChatMessage msg = chatHistory.get(i);
            if (type == ChatMessage.MessageType.TOOL_CALL && msg.isToolCallMessage()) return i;
            if (type == ChatMessage.MessageType.AGENT_STEP && msg.isAgentStepMessage()) return i;
        }
        return -1;
    }

    // ===================== Protected Callback =====================

    /**
     * 保护性的 TokenCallback 包装类，防止回调被重复调用
     * Native层可能在 onComplete 后继续调用 onToken/onError，导致崩溃
     */
    private class ProtectedTokenCallback implements LlamaHelper.TokenCallback {
        private final String callbackName;
        private final java.util.function.Consumer<String> onCompleteHandler;
        private volatile boolean completed = false;
        private static final java.util.concurrent.atomic.AtomicInteger instanceCount = new java.util.concurrent.atomic.AtomicInteger(0);
        private final int instanceId;

        ProtectedTokenCallback(String callbackName, java.util.function.Consumer<String> onCompleteHandler) {
            this.callbackName = callbackName;
            this.onCompleteHandler = onCompleteHandler;
            this.instanceId = instanceCount.incrementAndGet();
            AppLogger.ai("ProtectedCallback", "Created #" + instanceId + " (" + callbackName + ")");
        }

        @Override
        public void onToken(String token) {
            if (completed) {
                AppLogger.aiW(TAG, "#" + instanceId + " onToken after completion, ignoring");
                return;
            }
            runOnUiThread(() -> handleStreamToken(token));
        }

        @Override
        public void onComplete(String fullText) {
            if (completed) {
                AppLogger.aiW(TAG, "#" + instanceId + " onComplete called multiple times, ignoring");
                return;
            }
            completed = true;
            AppLogger.ai(TAG, "#" + instanceId + " onComplete");
            if (onCompleteHandler != null) {
                onCompleteHandler.accept(fullText);
            }
        }

        @Override
        public void onError(String error) {
            if (completed) {
                AppLogger.aiW(TAG, "#" + instanceId + " onError after completion, ignoring");
                return;
            }
            completed = true;
            if ("[TOOL_CALL]".equals(error)) return;
            AppLogger.aiW(TAG, "#" + instanceId + " onError: " + error);
            runOnUiThread(() -> handleGenerationError(callbackName + "出错: " + error));
        }
    }

    // ===================== UI Helpers =====================

    private void beginGeneration() {
        isGenerating = true;
        isDirectStreaming = true;
        totalTokensGenerated = 0;
        generationStartTime = System.currentTimeMillis();
        
        streamingUpdateManager = new StreamingUpdateManager(new StreamingUpdateManager.UpdateCallback() {
            @Override
            public void onUpdate(String accumulatedContent, int totalTokensSinceLastUpdate) {
                safeUpdateMessageFromStreamingManager(accumulatedContent, totalTokensSinceLastUpdate);
            }
            
            @Override
            public void onStatsUpdate(StreamingUpdateManager.StreamingStats stats) {
                updateGenerationStats(stats);
            }
        }, 50, 200, 5);

        if (btnStopGeneration != null) btnStopGeneration.setVisibility(View.VISIBLE);
        if (serviceStatusManager != null) serviceStatusManager.showThinkingIndicator();

        // 显示 Token 统计
        showTokenStats(true);
    }

    private void endGeneration() {
        isGenerating = false;
        isDirectStreaming = false;

        if (streamingUpdateManager != null) {
            streamingUpdateManager.flush();
            streamingUpdateManager = null;
        }

        hideLoadingUI();

        // 显示最终 Token 统计（由 tokenStatsCallback 更新为 "🔵 X tokens" 格式）
        showTokenStats(true);
    }

    private void resetStreamingState() {
        tokenCountSinceLastUpdate = 0;
        lastUpdateTime = System.currentTimeMillis();
        isUpdateScheduled = false;
        uiHandler.removeCallbacksAndMessages(null);
        
        if (streamingUpdateManager != null) {
            streamingUpdateManager.reset();
        }
    }

    private void safeUpdateMessage() {
        try {
            if (currentStreamingMessageIndex < 0 || currentStreamingMessageIndex >= chatHistory.size() || currentStreamingContent == null) return;
            ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
            msg.content = currentStreamingContent.toString();
            msg.status = ChatMessage.MessageStatus.GENERATING;
            if (currentThinkingContent != null && currentThinkingContent.length() > 0) msg.thinkingContent = currentThinkingContent.toString();
            if (chatAdapter != null) chatAdapter.updateAIMessageContent(currentStreamingMessageIndex, currentStreamingContent.toString());
            scrollToBottom();
        } catch (IndexOutOfBoundsException e) { currentStreamingMessageIndex = -1; }
    }

    private void safeUpdateMessageFromStreamingManager(String accumulatedContent, int tokensSinceLastUpdate) {
        try {
            if (currentStreamingMessageIndex < 0 || currentStreamingMessageIndex >= chatHistory.size() || currentStreamingContent == null) return;
            
            ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
            if (msg != null) {
                msg.status = ChatMessage.MessageStatus.GENERATING;
                
                if (currentThinkingContent != null && currentThinkingContent.length() > 0) {
                    msg.thinkingContent = currentThinkingContent.toString();
                }
                
                if (chatAdapter != null) {
                    chatAdapter.updateAIMessageContent(currentStreamingMessageIndex, currentStreamingContent.toString());
                }
                scrollToBottom();
            }
        } catch (IndexOutOfBoundsException e) {
            currentStreamingMessageIndex = -1;
        }
    }

    private void resetAttachmentAdapter() {
        if (attachmentAdapter != null && attachmentList != null) {
            attachmentList.setVisibility(View.GONE);
        }
    }

    private void saveHistoryAsync() {
        if (chatHistoryManager != null && chatHistory != null) {
            final List<ChatMessage> copy = new ArrayList<>(chatHistory);
            new Thread(() -> chatHistoryManager.saveAIChatHistory(copy)).start();
        }
    }

    // ===================== Mode / Tool Execution =====================

    private void executeTool(String toolName, String parameters) {
        Map<String, Object> params = parseParameters(parameters);
        new Thread(() -> {
            try {
                AIToolResult result = aiToolManager.executeTool(toolName, params);
                String resultStr = result.isSuccess() ? String.valueOf(result.getResult()) : result.getErrorMessage();
                runOnUiThread(() -> addAIMessage(resultStr));
            } catch (Exception e) {
                runOnUiThread(() -> addSystemMessage("工具执行出错: " + e.getMessage()));
            }
        }).start();
    }

    private void executeEntertainment(String type, String parameters) {
        aiEntertainmentManager.executeEntertainment(type, parameters).thenAccept(result -> runOnUiThread(() -> {
            addAIMessage(result);
        })).exceptionally(throwable -> {
            runOnUiThread(() -> {
                addSystemMessage("娱乐功能出错: " + throwable.getMessage());
            });
            return null;
        });
    }
    
    private Map<String, Object> parseParameters(String parameters) {
        Map<String, Object> params = new HashMap<>();
        if (parameters == null || parameters.isEmpty()) {
            return params;
        }
        String[] pairs = parameters.split(",");
        for (String pair : pairs) {
            String[] keyValue = pair.split(":", 2);
            if (keyValue.length == 2) {
                String key = keyValue[0].trim();
                String value = keyValue[1].trim();
                params.put(key, value);
            }
        }
        return params;
    }

    private void updateModelNameDisplay() {
        if (modelNameText == null) return;

        try {
            if (shouldUseOnlineModel()) {
                if (inferenceRouter != null) {
                    String modelName = inferenceRouter.getCurrentModelName();
                    modelNameText.setText("☁️ " + (modelName != null && !modelName.isEmpty() ? modelName : "在线模型"));
                } else {
                    modelNameText.setText("☁️ 在线模型");
                }
            } else if (aiService != null) {
                String name = modelBridge != null ? modelBridge.getCurrentModelName() : "";
                modelNameText.setText("📱 " + (name != null && !name.isEmpty() ? name : "未选择模型"));
            } else {
                modelNameText.setText("AI服务未初始化");
            }
        } catch (Exception e) {
            AppLogger.aiW(TAG, "Error updating model name display: " + e.getMessage());
            modelNameText.setText("模型加载中...");
        }
    }

    private void initAgentChatHandler() {
        // 确保 agentService 已初始化
        if (agentService == null) {
            agentService = AgentService.getInstance(this);
        }
        if (aiConfig == null || !aiConfig.isAgentEnabled() || agentService == null) {
            return;
        }

        // 检查AI服务是否可用
        boolean useOnlineModel = inferenceRouter != null && inferenceRouter.isUsingOnlineModel();
        if (!useOnlineModel && (modelBridge == null || !modelBridge.isModelInitialized())) {
            AppLogger.aiW(TAG, "Agent模式需要AI服务已初始化，当前AI服务未就绪");
            return;
        }

        if (lastUseOnlineModel != null && lastUseOnlineModel == useOnlineModel && agentChatHandler != null) {
            return;
        }
        if (agentChatHandler != null) {
            try {
                agentChatHandler.shutdown();
            } catch (Exception e) {
                AppLogger.aiW(TAG, "关闭旧的AgentChatHandler出错: " + e.getMessage());
            }
            agentChatHandler = null;
        }
        if (useOnlineModel) {
            agentChatHandler = new AgentChatHandler(this, aiService, inferenceRouter, agentService, new AgentCallbackImpl(), true);
        } else {
            agentChatHandler = new AgentChatHandler(this, aiService, agentService, new AgentCallbackImpl());
        }
        lastUseOnlineModel = useOnlineModel;
        AppLogger.ai(TAG, "AgentChatHandler 已初始化，使用模型类型: " + (useOnlineModel ? "在线" : "本地"));
    }

    /**
     * 如果 AgentChatHandler 未初始化，则初始化
     */
    private void initAgentChatHandlerIfNeeded() {
        if (agentChatHandler == null) {
            // 确保 agentService 已初始化
            if (agentService == null) {
                agentService = AgentService.getInstance(this);
            }
            initAgentChatHandler();
        }
    }

    // ===================== Chat Actions =====================

    private void clearChat() {
        try {
            if (isGenerating) {
                if (agentChatHandler != null && agentChatHandler.isGenerating()) agentChatHandler.cancel();
                if (modelBridge != null) modelBridge.execute(ChatCommand.stopGeneration(), null);
            }
            // 同步清理所有数据源
            chatHistory.clear();
            if (chatViewModel != null) {
                chatViewModel.clearChatHistory();
            }
            if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
            if (chatHistoryManager != null) new Thread(() -> chatHistoryManager.clearAIChatHistory()).start();
            if (modelBridge != null) modelBridge.execute(ChatCommand.clearContext(), null);
            clearStreamingState();
            endGeneration();
            updateEmptyState();
            // 重置 Token 统计
            TokenStatsManager.getInstance().resetSession();
            updateTokenStatsUI(TokenStatsManager.getInstance().getCurrentSnapshot());
        } catch (Exception e) { AppLogger.aiE(TAG, "Error clearing chat: " + e.getMessage()); }
    }
    
    /**
     * 显示模式选择对话框
     */
    private void showModeSelectorDialog() {
        ModeSelectorDialog dialog = new ModeSelectorDialog(this);
        dialog.setOnModeSelectedListener(new ModeSelectorDialog.OnModeSelectedListener() {
            @Override
            public void onModeSelected(ChatModeManager.ChatMode mode) {
                ChatModeManager.getInstance(AIChatActivity.this).setManualMode(mode);
                updateModeButtonText();
                String modeName;
                switch (mode) {
                    case NORMAL: modeName = "普通模式"; break;
                    case DEEP_THINKING: modeName = "深度思考模式"; break;
                    case CREATIVE: modeName = "创意写作模式"; break;
                    case AGENT: modeName = "Agent模式"; break;
                    case THINKING_ASSIST: modeName = "思考辅助模式"; break;
                    default: modeName = "未知模式"; break;
                }
                showToast("已切换到" + modeName);
            }
            
            @Override
            public void onAutoModeChanged(boolean enabled) {
                ChatModeManager.getInstance(AIChatActivity.this).setAutoModeEnabled(enabled);
                showToast(enabled ? "已开启AI智能模式切换" : "已关闭AI智能模式切换");
            }
        });
        dialog.show();
    }
    
    /**
     * 模式切换动画
     * 淡出当前内容 -> 执行切换 -> 淡入新内容
     */
    private void animateModeSwitch(Runnable switchAction) {
        if (messageList == null) {
            switchAction.run();
            return;
        }
        
        // 淡出当前内容
        messageList.animate()
            .alpha(0.3f)
            .setDuration(150)
            .withEndAction(() -> {
                // 执行模式切换
                switchAction.run();
                // 淡入新内容
                messageList.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start();
            })
            .start();
    }

    /**
     * 更新模式按钮显示文本
     */
    private void updateModeButtonText() {
        if (btnModeSelect != null) {
            ChatModeManager.ChatMode currentMode = ChatModeManager.getInstance(AIChatActivity.this).getCurrentMode();
            String btnText = currentMode.icon + currentMode.displayName + " ▼";
            btnModeSelect.setText(btnText);
        }
    }

    /**
     * 注入模式切换指令到上下文
     * 保留对话历史，通过指令改变模型行为
     */
    private void injectModeSwitchInstruction(ChatModeManager.ChatMode oldMode, ChatModeManager.ChatMode newMode) {
        if (oldMode == newMode) return;

        String instruction = ChatModeManager.getModeSwitchInstruction(oldMode, newMode);
        AppLogger.ai(TAG, "Injecting mode switch instruction: " + oldMode.displayName + " -> " + newMode.displayName);

        // 如果使用本地模型，通过chatSend注入指令
        if (modelBridge != null && modelBridge.isModelInitialized() && modelBridge.isChatContextActive()) {
            // 注入到上下文，但不生成回复
            new Thread(() -> {
                try {
                    // 通过Bridge注入系统指令
                    modelBridge.execute(ChatCommand.sendMessage("system-inject", "[系统指令] " + instruction, 1, false),
                        new BridgeCallback() {
                            @Override public void onGenerationStarted(String messageId) {}
                            @Override public void onToken(String messageId, String token) {}
                            @Override public void onGenerationComplete(String messageId, String fullContent, int tokens, long elapsedMs, float tps) {
                                AppLogger.ai(TAG, "Mode switch instruction injected successfully");
                            }
                            @Override public void onGenerationError(String messageId, String error) {
                                AppLogger.aiE(TAG, "Mode switch instruction injection failed: " + error);
                            }
                            @Override public void onGenerationStopped(String messageId) {}
                            @Override public void onInferenceProgress(String messageId, int tokens, float tps) {}
                            @Override public void onContextCleared() {}
                            @Override public void onContextInitialized(boolean success) {}
                            @Override public void onModelInitialized(boolean success, String modelName) {}
                            @Override public void onModelReloaded(boolean success) {}
                            @Override public void onModelInfo(String modelName, boolean isInitialized, boolean usingGPU, int gpuLayers) {}
                            @Override public void onTokenCount(int count) {}
                            @Override public void onNativeStateChecked(boolean isValid) {}
                            @Override public void onMemoryPressureHandled(int result) {}
                            @Override public void onToolCallStart(String messageId, String toolName, String args) {}
                            @Override public void onToolCallComplete(String messageId, String toolName, boolean success, String result) {}
                            @Override public void onThinkingUpdate(String messageId, int stepNumber, String stepType, String title, String content, int progress) {}
                        });
                } catch (Exception e) {
                    AppLogger.aiE(TAG, "Error injecting mode switch instruction: " + e.getMessage());
                }
            }).start();
        }
    }
    
    /**
     * Token 统计回调
     */
    private TokenStatsManager.TokenStatsCallback tokenStatsCallback = stats -> {
        runOnUiThread(() -> updateTokenStatsUI(stats));
    };
    
    /**
     * 更新 Token 统计 UI（来自 TokenStatsManager 回调）
     */
    private void updateTokenStatsUI(TokenStatsManager.TokenStats stats) {
        TextView tvTokenStats = findViewById(R.id.tv_token_stats);
        if (tvTokenStats != null && stats != null) {
            if (stats.requestTotalTokens > 0) {
                tvTokenStats.setVisibility(View.VISIBLE);
                tvTokenStats.setText(String.format("🔵 %d tokens", stats.requestTotalTokens));
            } else {
                tvTokenStats.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 显示/隐藏 Token 统计
     */
    private void showTokenStats(boolean show) {
        TextView tvTokenStats = findViewById(R.id.tv_token_stats);
        if (tvTokenStats != null) {
            tvTokenStats.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 更新实时生成统计（从 StreamingUpdateManager）
     * 仅更新 UI 显示，不更新 TokenStatsManager（避免重复累加）
     */
    private void updateStreamingTokenStats(int totalTokens, float tokensPerSecond) {
        TextView tvTokenStats = findViewById(R.id.tv_token_stats);
        if (tvTokenStats != null) {
            tvTokenStats.setVisibility(View.VISIBLE);
            if (isGenerating && tokensPerSecond > 0) {
                String statsText = String.format("⚡ %.1f t/s | %d tokens", tokensPerSecond, totalTokens);
                tvTokenStats.setText(statsText);
            } else {
                String statsText = String.format("✅ %d tokens", totalTokens);
                tvTokenStats.setText(statsText);
            }
        }
    }

    private void stopGeneration() {
        try {
            if (agentChatHandler != null && agentChatHandler.isGenerating()) agentChatHandler.cancel();
            if (modelBridge != null) modelBridge.execute(ChatCommand.stopGeneration(), null);
            endGeneration();
            showToast("已停止生成");
            addSystemMessage("生成已停止");
        } catch (Exception e) { AppLogger.aiE(TAG, "Error stopping: " + e.getMessage()); }
    }

    private void regenerateLastMessage() {
        if (isGenerating) { showToast("正在生成中"); return; }
        String lastUserMsg = null;
        int lastUserIdx = -1;
        int lastAiIdx = -1;
        for (int i = chatHistory.size() - 1; i >= 0; i--) {
            ChatMessage msg = chatHistory.get(i);
            if (msg.type == ChatMessage.MessageType.AI && lastAiIdx < 0) lastAiIdx = i;
            else if (msg.type == ChatMessage.MessageType.USER) { lastUserMsg = msg.content; lastUserIdx = i; break; }
        }
        if (lastUserMsg == null || lastAiIdx < 0) { showToast("没有可重新生成的消息"); return; }
        int removeStart = lastUserIdx + 1;
        int originalSize = chatHistory.size();
        int systemMsgCount = 0;
        for (int i = removeStart; i < originalSize; i++) {
            if (chatHistory.get(i).type == ChatMessage.MessageType.SYSTEM) systemMsgCount++;
        }
        chatHistory.subList(removeStart, chatHistory.size()).removeIf(m -> m.type != ChatMessage.MessageType.SYSTEM);
        int actualRemoved = originalSize - chatHistory.size();
        if (chatAdapter != null && actualRemoved > 0) chatAdapter.notifyItemRangeRemoved(removeStart, actualRemoved);
        saveHistoryAsync();
        if (aiService != null) aiService.chatClear();
        processChatMessage(lastUserMsg);
    }

    private void clearStreamingState() {
        currentStreamingContent = null;
        currentThinkingContent = null;
        isInThinking = false;
        thinkingRoundEnded = false;
        thinkingRoundCount = 1;
        isInTag = false;
        if (tagBuffer != null) tagBuffer.setLength(0);
        currentStreamingMessageIndex = -1;
        currentThinkingMessageIndex = -1;
        currentStreamingMessageId = null;
        isGenerating = false;
        isDirectStreaming = false;
        hideLoadingUI();
    }

    private void hideLoadingUI() {
        if (btnStopGeneration != null) btnStopGeneration.setVisibility(View.GONE);
        if (serviceStatusManager != null) serviceStatusManager.hideThinkingIndicator();
    }

    // ===================== Native 状态自动恢复 =====================

    private boolean shouldUseOnlineModel() {
        if (inferenceRouter != null) {
            return inferenceRouter.isUsingOnlineModel();
        }
        return false;
    }

    private boolean ensureModelLoaded(String pendingMessage) {
        if (shouldUseOnlineModel()) {
            return true;
        }
        if (aiService == null) { showToast("AI服务未初始化"); return false; }
        boolean modelInMemory = modelBridge != null && modelBridge.isModelInMemory();
        if (!modelInMemory || !modelBridge.isModelInitialized()) {
            if (serviceStatusManager != null) serviceStatusManager.setLoadingModel(true);
            showLoading("初始化AI服务...", "正在准备模型，这可能需要几秒钟...");
            addSystemMessage("⏳ 开始加载AI模型...", ChatMessage.SystemMessageType.INFO);

            final String msg = pendingMessage;
            modelBridge.execute(ChatCommand.initModel(), new BridgeCallback() {
                @Override public void onGenerationStarted(String messageId) {}
                @Override public void onToken(String messageId, String token) {}
                @Override public void onGenerationComplete(String messageId, String fullContent, int tokens, long elapsedMs, float tps) {}
                @Override public void onGenerationError(String messageId, String error) {}
                @Override public void onGenerationStopped(String messageId) {}
                @Override public void onInferenceProgress(String messageId, int tokens, float tps) {}
                @Override public void onContextCleared() {}
                @Override public void onContextInitialized(boolean success) {}
                @Override public void onModelReloaded(boolean success) {}
                @Override public void onModelInfo(String modelName, boolean isInitialized, boolean usingGPU, int gpuLayers) {}
                @Override public void onTokenCount(int count) {}
                @Override public void onNativeStateChecked(boolean isValid) {}
                @Override public void onMemoryPressureHandled(int result) {}
                @Override public void onToolCallStart(String messageId, String toolName, String args) {}
                @Override public void onToolCallComplete(String messageId, String toolName, boolean success, String result) {}
                @Override public void onThinkingUpdate(String messageId, int stepNumber, String stepType, String title, String content, int progress) {}

                @Override
                public void onModelInitialized(boolean success, String modelName) {
                    runOnUiThread(() -> {
                        if (success) {
                            if (msg != null && !msg.isEmpty()) {
                                processChatMessage(msg);
                            }
                        } else {
                            if (serviceStatusManager != null) serviceStatusManager.setLoadingModel(false);
                            addErrorMessage("模型加载失败", "无法初始化AI模型，请检查模型文件是否正确导入", true);
                            showToast("模型加载失败");
                        }
                    });
                }
            });
            return false;
        }
        return true;
    }

    private void showLoading(String message, String submessage) {
        if (serviceStatusManager != null) serviceStatusManager.showThinkingIndicator();
    }

    private void hideLoading() {
        if (serviceStatusManager != null) serviceStatusManager.hideThinkingIndicator();
    }

    // ===================== Message Adders =====================

    private void addUserMessage(String message) {
        if (chatHistory == null) return;

        // 隐藏空状态
        updateEmptyState();
        chatHistory.add(ChatMessage.createUserMessage(java.util.UUID.randomUUID().toString(), message, System.currentTimeMillis()));
        if (chatAdapter != null) {
            chatAdapter.notifyItemInserted(chatHistory.size() - 1);
        }
        scrollToBottom(true);
        saveHistoryAsync();
    }

    private void addAIMessage(String message) {
        if (chatHistory == null) return;

        chatHistory.add(ChatMessage.createAIMessage(java.util.UUID.randomUUID().toString(), message, System.currentTimeMillis(), null, 0, 0));
        if (chatAdapter != null) {
            chatAdapter.notifyItemInserted(chatHistory.size() - 1);
        }
        scrollToBottom(true);
        saveHistoryAsync();
    }

    private void addSystemMessage(String message) {
        if (chatHistory == null) return;

        chatHistory.add(ChatMessage.createSystemMessage(java.util.UUID.randomUUID().toString(), message, ChatMessage.SystemMessageType.INFO, System.currentTimeMillis()));
        if (chatAdapter != null) {
            chatAdapter.notifyItemInserted(chatHistory.size() - 1);
        }
        scrollToBottom(true);
        saveHistoryAsync();
    }

    private void addErrorMessage(String title, String detail, boolean retryable) {
        if (chatHistory == null) return;

        chatHistory.add(ChatMessage.createErrorMessage(title, detail, retryable));
        if (chatAdapter != null) {
            chatAdapter.notifyItemInserted(chatHistory.size() - 1);
        }
        scrollToBottom();
    }

    /**
     * 添加Agent思考消息（每轮思考独立一个消息块，插入到流式AI消息前面）
     * @param round 当前思考轮次
     * @return 消息在chatHistory中的位置
     */
    private int addThinkingMessage(int round) {
        if (chatHistory == null) return -1;

        ChatMessage msg = ChatMessage.createThinkingRoundMessage(round);
        // 插入到流式AI消息前面，使AI气泡始终显示在agent执行UI的最后面
        int insertPos = (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size())
                ? currentStreamingMessageIndex : chatHistory.size();
        chatHistory.add(insertPos, msg);
        if (currentStreamingMessageIndex >= 0) {
            currentStreamingMessageIndex++;
        }
        int pos = insertPos;
        currentThinkingMessageIndex = pos;
        if (chatAdapter != null) {
            chatAdapter.notifyItemInserted(pos);
        }
        scrollToBottom();
        return pos;
    }

    /** 更新当前思考消息的UI显示（节流调用） */
    private void updateThinkingMessageUi() {
        if (currentThinkingMessageIndex >= 0 && currentThinkingMessageIndex < chatHistory.size()) {
            ChatMessage msg = chatHistory.get(currentThinkingMessageIndex);
            msg.thinkingContent = currentThinkingContent != null ? currentThinkingContent.toString() : "";
            if (chatAdapter != null) {
                chatAdapter.updateMessageThinkingContent(currentThinkingMessageIndex, msg.thinkingContent);
            }
            scrollToBottom();
        }
    }

    /** 完成当前思考消息：设置最终内容、标记完成、折叠 */
    private void finalizeThinkingMessage() {
        if (currentThinkingContent != null && currentThinkingMessageIndex >= 0
            && currentThinkingMessageIndex < chatHistory.size() && chatAdapter != null) {
            ChatMessage msg = chatHistory.get(currentThinkingMessageIndex);
            msg.thinkingContent = currentThinkingContent.toString();
            msg.status = ChatMessage.MessageStatus.COMPLETED;
            msg.thinkingExpanded = false;
            chatAdapter.updateMessageThinkingContent(currentThinkingMessageIndex, currentThinkingContent.toString());
            chatAdapter.notifyItemChanged(currentThinkingMessageIndex);
        }
        // 重置思考消息索引，下轮创建新消息
        currentThinkingMessageIndex = -1;
    }

    private int addToolCallMessage(String toolName, String parameters) {
        if (chatHistory == null) return -1;

        ChatMessage msg = ChatMessage.createToolCallMessage(toolName, parameters);
        // 插入到流式AI消息前面，使AI气泡始终显示在agent执行UI的最后面
        int insertPos = (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size())
                ? currentStreamingMessageIndex : chatHistory.size();
        chatHistory.add(insertPos, msg);
        if (currentStreamingMessageIndex >= 0) {
            currentStreamingMessageIndex++;
        }
        int pos = insertPos;
        if (chatAdapter != null) {
            chatAdapter.notifyItemInserted(pos);
        }
        scrollToBottom();
        return pos;
    }

    private int addAgentStepMessage(ChatMessage.AgentStepInfo stepInfo) {
        if (chatHistory == null) return -1;

        ChatMessage msg = ChatMessage.createAgentStepMessage(stepInfo);
        // 插入到流式AI消息前面，保持AI气泡在最后面
        int insertPos = (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size())
                ? currentStreamingMessageIndex : chatHistory.size();
        chatHistory.add(insertPos, msg);
        if (currentStreamingMessageIndex >= 0) {
            currentStreamingMessageIndex++;
        }
        int pos = insertPos;
        if (chatAdapter != null) {
            chatAdapter.notifyItemInserted(pos);
        }
        scrollToBottom();
        return pos;
    }

    private void updateToolCallResult(int position, boolean success, String result) {
        if (chatAdapter != null && position >= 0 && position < chatHistory.size()) {
            chatAdapter.updateToolCallStatus(position, success ? ChatMessage.ToolCallInfo.ToolCallStatus.COMPLETED : ChatMessage.ToolCallInfo.ToolCallStatus.FAILED, result);
        }
    }

    private void updateAgentStepResult(int position, String thought, String action, String observation, boolean isCompleted) {
        if (chatAdapter != null && position >= 0 && position < chatHistory.size()) {
            chatAdapter.updateAgentStep(position, thought, action, observation, isCompleted);
        }
    }

    private void scrollToBottom() {
        scrollToBottom(false);
    }

    private void scrollToBottom(boolean force) {
        if (messageList == null || chatAdapter == null || chatAdapter.getItemCount() == 0) return;
        int lastPosition = chatAdapter.getItemCount() - 1;
        // 非强制滚动时，只有用户在底部附近才自动滚动，避免打断用户查看历史
        if (!force && !isUserAtBottom()) return;

        messageList.post(() -> {
            if (isInThinking || (currentStreamingContent != null && currentStreamingContent.length() > 0)) {
                messageList.scrollToPosition(lastPosition);
            } else {
                messageList.smoothScrollToPosition(lastPosition);
            }
        });
    }

    private boolean isUserAtBottom() {
        if (messageList == null) return false;
        androidx.recyclerview.widget.LinearLayoutManager layoutManager =
            (androidx.recyclerview.widget.LinearLayoutManager) messageList.getLayoutManager();
        if (layoutManager == null) return false;
        int lastVisible = layoutManager.findLastCompletelyVisibleItemPosition();
        int total = chatAdapter != null ? chatAdapter.getItemCount() : 0;
        // 最后一项完全可见，或离底部 2 项以内，认为用户在底部
        return lastVisible >= total - 2;
    }

    /**
     * 滚动到指定位置并确保该项完全可见
     */
    private void scrollToPositionWithOffset(int position) {
        if (messageList != null && chatAdapter != null && position >= 0 && position < chatAdapter.getItemCount()) {
            messageList.post(() -> {
                androidx.recyclerview.widget.LinearLayoutManager layoutManager =
                    (androidx.recyclerview.widget.LinearLayoutManager) messageList.getLayoutManager();
                if (layoutManager != null) {
                    // 使用 scrollToPositionWithOffset 将指定项滚动到顶部
                    layoutManager.scrollToPositionWithOffset(position, 0);
                } else {
                    messageList.scrollToPosition(position);
                }
            });
        }
    }

    /**
     * 设置键盘弹出时自动滚动到底部
     */
    private void setupKeyboardListener() {
        final android.view.View rootView = findViewById(android.R.id.content);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            android.graphics.Rect r = new android.graphics.Rect();
            rootView.getWindowVisibleDisplayFrame(r);
            int screenHeight = rootView.getRootView().getHeight();
            int keypadHeight = screenHeight - r.bottom;
            if (keypadHeight > screenHeight * 0.15) {
                // 键盘弹出，滚动到底部
                scrollToBottom();
            }
        });
    }

    private void handleAction(ChatMessage.Action action) {
        if (dialogHelper != null) dialogHelper.handleAction(action, chatHistory);
    }



    // ===================== UI Init =====================

    private void openUri(String url) {
        if (url != null) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); } catch (Exception e) { showToast("无法打开"); } }
    }

    // ===================== Weather Banner =====================

    // ===================== Attachments =====================

    private void handleAttachFile() {
        if (attachFileLauncher != null) attachFileLauncher.launch(new String[]{"image/*", "application/pdf", "text/plain", "*/*"});
        else showToast("附件功能初始化中");
    }

    private void initAttachFileLauncher() {
        attachFileLauncher = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
            if (uris != null && !uris.isEmpty()) handleAttachedFiles(uris);
        });
    }

    private void handleAttachedFiles(List<Uri> uris) {
        for (Uri uri : uris) {
            String fileName = getFileNameFromUri(uri);
            String mimeType = getContentResolver().getType(uri);
            String type = "file";
            if (mimeType != null) {
                if (mimeType.startsWith("image/")) type = "image";
                else if (mimeType.startsWith("video/")) type = "video";
                else if (mimeType.startsWith("audio/")) type = "audio";
                else if (mimeType.contains("pdf")) type = "pdf";
            }
            if (inputManager != null) inputManager.addAttachment(new ChatMessage.Attachment(type, uri.toString(), fileName, getFileSizeFromUri(uri)));
        }
        showToast("已添加 " + uris.size() + " 个附件");
        sendMessageWithAttachments();
    }

    /**
     * 发送带附件的消息（无需文字输入）
     */
    private void sendMessageWithAttachments() {
        if (inputManager == null || !inputManager.hasAttachments()) return;
        if (!isAIReady()) { showToast("AI服务未就绪，请稍后重试"); return; }

        List<ChatMessage.Attachment> savedAttachments = inputManager.getCurrentAttachments();
        inputManager.clearAttachments();

        String defaultMessage = "请分析这些附件的内容";
        ChatMessage userMessage = ChatMessage.createUserMessage(defaultMessage, savedAttachments);
        chatHistory.add(userMessage);
        if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1);
        scrollToBottom();
        saveHistoryAsync();
        processMessageWithAttachments(defaultMessage, savedAttachments);
    }

    /**
     * 检查AI服务是否就绪
     */
    private boolean isAIReady() {
        if (shouldUseOnlineModel()) {
            return inferenceRouter != null && inferenceRouter.isCurrentModelAvailable();
        }
        return modelBridge != null && modelBridge.isModelInitialized();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateModelNameDisplay();
        initAgentChatHandler();
        // 再次进入页面时滚动到最新消息
        scrollToBottom(true);
    }

    private String getFileNameFromUri(Uri uri) {
        String result = null;
        try {
            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) result = cursor.getString(idx);
                cursor.close();
            }
        } catch (Exception e) { /* ignore */ }
        if (result == null) { result = uri.getLastPathSegment(); if (result != null && result.contains("/")) result = result.substring(result.lastIndexOf("/") + 1); }
        return result != null ? result : "未知文件";
    }

    private long getFileSizeFromUri(Uri uri) {
        try {
            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                long size = idx >= 0 ? cursor.getLong(idx) : 0;
                cursor.close();
                return size;
            }
        } catch (Exception e) { /* ignore */ }
        return 0;
    }

    // ===================== Broadcast Receivers =====================

    private class AITokenReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            if (isDirectStreaming || !AIProcessingService.ACTION_AI_TOKEN_UPDATE.equals(intent.getAction())) return;
            String token = intent.getStringExtra(AIProcessingService.EXTRA_TOKEN);
            if (token != null) {
                if (currentStreamingContent == null) currentStreamingContent = new StringBuilder();
                currentStreamingContent.append(token);
                tokenCountSinceLastUpdate++;
                long now = System.currentTimeMillis();
                if (tokenCountSinceLastUpdate >= BATCH_TOKEN_COUNT || now - lastUpdateTime >= BATCH_INTERVAL_MS || !isUpdateScheduled) {
                    updateReceiverUI();
                } else if (!isUpdateScheduled) {
                    isUpdateScheduled = true;
                    uiHandler.postDelayed(() -> { if (isUpdateScheduled) updateReceiverUI(); }, BATCH_INTERVAL_MS - (now - lastUpdateTime));
                }
            }
        }
        private void updateReceiverUI() {
            if (currentStreamingMessageIndex < 0 || chatHistory == null || currentStreamingContent == null || currentStreamingMessageIndex >= chatHistory.size()) return;
            ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
            msg.content = currentStreamingContent.toString();
            msg.status = ChatMessage.MessageStatus.GENERATING;
            if (chatAdapter != null) chatAdapter.updateAIMessageContent(currentStreamingMessageIndex, currentStreamingContent.toString());
            scrollToBottom();
            tokenCountSinceLastUpdate = 0; lastUpdateTime = System.currentTimeMillis(); isUpdateScheduled = false;
        }
    }

    private class AIResultReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            if (isDirectStreaming || !AIProcessingService.ACTION_AI_TASK_COMPLETED.equals(intent.getAction())) return;
            String result = intent.getStringExtra(AIProcessingService.EXTRA_RESULT);
            String error = intent.getStringExtra(AIProcessingService.EXTRA_ERROR);
            endGeneration();
            uiHandler.removeCallbacksAndMessages(null);
            if (error != null) {
                if (currentStreamingContent != null && currentStreamingContent.length() > 0 && currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                    ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                    msg.content = currentStreamingContent.toString();
                    msg.status = ChatMessage.MessageStatus.COMPLETED;
                    if (chatAdapter != null) chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
                    saveHistoryAsync(); addSystemMessage("生成中断: " + error);
                } else { if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) { chatHistory.remove(currentStreamingMessageIndex); chatAdapter.notifyItemRemoved(currentStreamingMessageIndex); } addSystemMessage(error); }
            } else if (result != null) {
                if (currentStreamingContent != null && currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                    ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                    msg.content = result;
                    msg.status = ChatMessage.MessageStatus.COMPLETED;
                    if (chatAdapter != null) chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
                    saveHistoryAsync();
                } else addAIMessage(result);
            }
            currentStreamingContent = null; currentStreamingMessageIndex = -1; currentStreamingMessageId = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            // 保存聊天历史
            saveHistoryAsync();

            // 清理协调器
            if (coordinator != null) {
                coordinator.cleanup();
            }

            // 清理正在处理的附件
            if (isProcessingAttachments.get()) {
                AppLogger.w(TAG, "Activity销毁时仍有附件在处理");
                isProcessingAttachments.set(false);
            }

            // 清理附件引用
            if (currentAttachments != null) {
                currentAttachments.clear();
            }

            // 取消所有生成任务
            if (agentChatHandler != null && agentChatHandler.isGenerating()) agentChatHandler.cancel();

            // 清理NativeEventBridge（只停止当前会话，不销毁单例，避免影响其他组件）
            NativeEventBridge.getInstance().stopAllSessions();

            // 清理附件管理器
            if (attachmentManager != null) {
                attachmentManager.clearAllAttachments();
            }

            unregisterAIStatusObserver();
            unregisterComponentCallbacks(memoryCallback);
            TokenStatsManager.getInstance().unregisterCallback(tokenStatsCallback);
            if (localBroadcastManager != null && aiResultReceiver != null) { try { localBroadcastManager.unregisterReceiver(aiResultReceiver); } catch (Exception e) {} }
            if (localBroadcastManager != null && aiTokenReceiver != null) { try { localBroadcastManager.unregisterReceiver(aiTokenReceiver); } catch (Exception e) {} }
            if (modelBridge != null) modelBridge.execute(ChatCommand.stopGeneration(), null);
            uiHandler.removeCallbacksAndMessages(null);
            isGenerating = false; isDirectStreaming = false;
        } catch (Exception e) { AppLogger.aiE(TAG, "Error onDestroy: " + e.getMessage()); }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 停止时取消未完成的操作
        if (isProcessingAttachments.get()) {
            AppLogger.i(TAG, "Activity停止，取消附件处理");
            isProcessingAttachments.set(false);
        }
    }

    private void registerAIStatusObserver() {
        if (serviceStatusManager != null) {
            serviceStatusManager.registerObserver();
        }
    }

    private void unregisterAIStatusObserver() {
        if (serviceStatusManager != null) {
            serviceStatusManager.unregisterObserver();
        }
    }


    

    

    





    
    private void updateInitialServiceStatus() {
        if (serviceStatusManager != null) {
            serviceStatusManager.updateInitialStatus();
        }
    }

    private void showServiceStatusDetails() {
        if (serviceStatusManager != null) {
            serviceStatusManager.showStatusDetails();
        }
    }




    

    


    private void addSystemMessage(String message, ChatMessage.SystemMessageType type) {
        if (chatHistory == null) return;

        chatHistory.add(ChatMessage.createSystemMessage(
            java.util.UUID.randomUUID().toString(),
            message,
            type,
            System.currentTimeMillis()));
        if (chatAdapter != null) {
            chatAdapter.notifyItemInserted(chatHistory.size() - 1);
        }
        scrollToBottom(true);
        saveHistoryAsync();
    }

    private void updateGenerationStats(StreamingUpdateManager.StreamingStats stats) {
        if (stats == null) {
            return;
        }

        // 更新 Adapter 中的统计
        if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
            if (chatAdapter != null) {
                chatAdapter.updateMessageGenerationStats(currentStreamingMessageIndex, stats.totalTokens, stats.elapsedMs);
            }
        }

        // 更新顶部 Token 统计显示
        updateStreamingTokenStats(stats.totalTokens, stats.tokensPerSecond);
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @androidx.annotation.NonNull String[] permissions,
                                           @androidx.annotation.NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        com.oilquiz.app.resource.PermissionResourceProvider.getInstance(this)
            .onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /**
     * 更新空状态显示（已移除欢迎界面，始终显示消息列表）
     */
    private void updateEmptyState() {
        if (emptyStateView != null) {
            emptyStateView.setVisibility(View.GONE);
        }
        if (messageList != null) {
            messageList.setVisibility(View.VISIBLE);
        }
    }

}