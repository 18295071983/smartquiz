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
import com.oilquiz.app.ai.agent.ToolGuideFlow;
import com.oilquiz.app.ai.agent.ToolContextProvider;
import com.oilquiz.app.ai.agent.CompositeGuideFlow;
import com.oilquiz.app.ai.agent.ToolResultStore;
import com.oilquiz.app.ai.agent.ToolResultInterpreter;
import com.oilquiz.app.ai.agent.ToolErrorRecovery;
import com.oilquiz.app.ai.agent.ToolPreChecker;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;
import com.oilquiz.app.ai.chat.ChatOrchestrator;
import com.oilquiz.app.ai.chat.MessageAttachmentAdapter;
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
import com.oilquiz.app.ai.chat.history.ChatHistoryAdapter;
import com.oilquiz.app.ui.adapter.AttachmentAdapter;
import com.oilquiz.app.util.fileparser.FileContentExtractor;
import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.PermissionResourceProvider;
import com.oilquiz.app.ai.chat.status.ServiceStatusManager;
import com.oilquiz.app.ai.chat.ui.ChatDialogHelper;
import com.oilquiz.app.ai.chat.history.ChatHistoryController;
import com.oilquiz.app.ai.util.ConversationSession;
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import android.media.MediaRecorder;
import android.os.Build;

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
    private MaterialButton btnVoice; // 语音输入按钮（录音→ASR→填入输入框）
    private MaterialButton btnAutoTts; // 全局自动语音合成开关按钮
    private boolean autoTtsEnabled = false; // 自动语音合成是否开启（AI回复完成后自动朗读）
    private String lastAutoSpokenMessageId; // 已自动朗读的消息ID（防止重复朗读）
    private com.oilquiz.app.ai.speech.StreamingTtsSpeaker streamingTtsSpeaker; // 流式按句朗读器（边生成边朗读）
    private boolean streamTtsFed = false; // 本轮生成是否已进行过流式朗读
    private String voiceInputBaseText = ""; // 系统识别开始前输入框已有文本（实时展示部分结果时作为前缀）
    private View voiceRecordingBar; // 录音状态横幅
    private android.widget.TextView tvVoiceRecordingTime; // 录音计时
    private android.widget.TextView tvVoiceRecordingDot; // 录音红点
    private final android.os.Handler speechTimerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable speechTimerRunnable;
    private int speechRecordingSeconds = 0;
    private String speakingMessageId; // 当前正在朗读的消息 ID（再次点击可停止）
    private MaterialButton btnCloseHistory;
    private MaterialButton btnClearAllHistory;
    private View thinkingIndicator;
    private Chip chipNormalChat;
    private Chip chipAgentMode;
    private Chip chipThinkingAssist;
    private Chip chipWeather;
    private Chip chipClear;
    /** 快捷工具栏：键盘弹出时自动折叠 */
    private ChipGroup quickActionsChipGroup;
    private ImageView ivQuickExpand;
    private boolean quickBarExpanded = true;
    /** 标记是否由键盘弹出自动折叠，键盘隐藏时仅恢复这种情况 */
    private boolean keyboardAutoCollapsed = false;
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
    /** 当前会话的持久化 ID（用于更新而非重复创建） */
    private String currentSessionId;
    private AttachmentManager attachmentManager;
    private ChatHistoryAdapter chatHistoryAdapter;
    private AttachmentAdapter attachmentAdapter;
    private FileContentExtractor fileContentExtractor;
    private AIToolManager aiToolManager;
    private AIEntertainmentManager aiEntertainmentManager;
    private AgentService agentService;
    private AgentChatHandler agentChatHandler;
    /** 独立 Agent 执行面板已移除：Agent 过程改为组件插入式显示在 AI 消息内 */
    private ModelExecutionBridge modelBridge;
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
    /** 强制本地Agent执行标志：用户点击"🚀 强行使用本地Agent"后置位，processChatMessage 消费一次后清除 */
    private volatile boolean forceLocalAgentOnce = false;
    // 在线模型 API 返回的 Token 统计（由 onTokenStats 回调写入）
    // 当大于 0 时，UI 优先使用 API 数据，实现数据源自动切换
    private volatile int onlinePromptTokens = 0;
    private volatile int onlineCompletionTokens = 0;
    private volatile long onlineStatsReceiveTime = 0L;
    private volatile StringBuilder currentStreamingContent = null;
    private volatile StringBuilder currentThinkingContent = null;
    private volatile boolean isInThinking = false;
    /** 标记上一轮思考已结束，用于在下一轮思考开始时插入分隔符 */
    private volatile boolean thinkingRoundEnded = false;
    /** 思考轮次计数（agent多轮迭代时递增） */
    private volatile int thinkingRoundCount = 0;
    private volatile Boolean lastUseOnlineModel = null;
    /** 在线模型变更监听器，用于实时更新模型名称显示 */
    private OnlineModelManager.ModelChangeListener modelChangeListener;
    private volatile boolean isInTag = false;
    private volatile StringBuilder tagBuffer = null;
    // 流式 UI 更新节流：避免高频 token 导致主线程过载卡顿
    private static final long UI_UPDATE_THROTTLE_MS = 120;
    private volatile long lastTokenUiUpdateTime = 0;
    /** 流式 token 统计（onToken 实时累计，节流刷新状态栏） */
    private volatile long streamingTokenCount = 0;
    private volatile long streamingStartTime = 0;
    private volatile long lastTokenStatsUiUpdateTime = 0;
    private volatile long lastThinkingUiUpdateTime = 0;
    private volatile int currentStreamingMessageIndex = -1;
    private volatile String currentStreamingMessageId = null;
    /** 当前Agent思考消息在chatHistory中的位置（-1表示无活跃思考消息） */
    private volatile int currentThinkingMessageIndex = -1;
    private volatile int agentToolLoopCount = 0;
    /** 当前Agent执行组ID（null=非agent执行或本地模型） */
    private volatile String currentAgentGroupId = null;
    private volatile int agentGroupStepCount = 0;
    private volatile int agentGroupToolCount = 0;
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
    private ActivityResultLauncher<Uri> cameraCaptureLauncher; // 相机拍照
    private android.net.Uri currentPhotoUri; // 当前拍照的临时URI
    private MediaRecorder speechMediaRecorder; // 语音输入录音器（ASR）
    private String speechRecordingFilePath; // 语音输入临时录音文件路径
    private boolean isSpeechRecording = false; // 是否正在语音输入录音
    private boolean isOfflineAsrMode = false; // 语音输入是否处于离线/系统识别模式（在线ASR不可用时的兜底）
    private MediaRecorder mediaRecorder; // 音频录制器
    private String recordingFilePath; // 录音文件路径
    private boolean isRecording = false; // 是否正在录音
    private List<Uri> attachedFiles = new ArrayList<>();
    private List<ChatMessage.Attachment> currentAttachments = new ArrayList<>();

    // 工具引导用的文件/图片/目录选择器 launcher
    private ActivityResultLauncher<String[]> guideFilePickerLauncher;   // 单文件 OpenDocument
    private ActivityResultLauncher<String[]> guideImagePickerLauncher;  // 图片 OpenDocument
    private ActivityResultLauncher<Uri>       guideDirectoryPickerLauncher; // 目录 OpenDocumentTree
    // 等待选择器结果的 pending 上下文
    private Object pendingPickerLock = new Object();
    private ToolGuideFlow.GuideStep pendingPickerStep = null;  // 当前正在选择的步骤
    private TextView   pendingPickerValueView = null;          // 显示路径的文本视图
    private MaterialButton pendingPickerButton = null;         // 选择按钮（更新已选择状态）
    // 缺失参数弹窗：存储 key → 临时 GuideStep（值保存在 paramValue 中），以便提交时取回
    private java.util.Map<String, ToolGuideFlow.GuideStep> missingParamPickerSteps = null;

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
            btnVoice = findViewById(R.id.btn_voice);
            btnAutoTts = findViewById(R.id.btn_auto_tts);
            voiceRecordingBar = findViewById(R.id.voice_recording_bar);
            tvVoiceRecordingTime = findViewById(R.id.tv_voice_recording_time);
            tvVoiceRecordingDot = findViewById(R.id.tv_voice_recording_dot);
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
            quickActionsChipGroup = findViewById(R.id.quick_actions_chip_group);
            ivQuickExpand = findViewById(R.id.iv_quick_expand);

            // 快捷工具栏折叠/展开功能（默认折叠，节省底部空间；14 个快捷入口展开查看）
            quickBarExpanded = false;
            if (ivQuickExpand != null) {
                ivQuickExpand.setImageResource(R.drawable.ic_expand);
            }
            if (quickActionsChipGroup != null) {
                quickActionsChipGroup.setVisibility(View.GONE);
            }
            if (quickBarHeader != null) {
                quickBarHeader.setOnClickListener(v -> {
                    quickBarExpanded = !quickBarExpanded;
                    keyboardAutoCollapsed = false; // 用户手动操作，清除键盘自动折叠标记
                    if (quickActionsChipGroup != null) {
                        quickActionsChipGroup.setVisibility(quickBarExpanded ? View.VISIBLE : View.GONE);
                    }
                    if (ivQuickExpand != null) {
                        ivQuickExpand.setImageResource(quickBarExpanded ? R.drawable.ic_collapse : R.drawable.ic_expand);
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
            // 消息点击/长按：长按 AI 消息弹出朗读等操作
            chatAdapter.setMessageClickListener(new ChatAdapter.OnMessageClickListener() {
                @Override
                public void onMessageClick(ChatMessage message) {
                    // 单击不做处理
                }

                @Override
                public void onMessageLongClick(ChatMessage message) {
                    showMessageSpeechOptions(message);
                }
            });
            // 附件图片/文件点击：应用内预览（不依赖系统图片查看器）
            chatAdapter.setOnAttachmentClickListener(new MessageAttachmentAdapter.OnAttachmentClickListener() {
                @Override
                public void onPreview(ChatMessage.Attachment attachment) {
                    if (attachment != null && attachment.url != null) {
                        showImagePreview(attachment.url);
                    }
                }

                @Override
                public void onSave(ChatMessage.Attachment attachment) {
                }

                @Override
                public void onShare(ChatMessage.Attachment attachment) {
                }

                @Override
                public void onModelLinkClick(String modelName, String apiUrl) {
                }
            });
            messageList.setAdapter(chatAdapter);

            setupKeyboardListener();
            updateEmptyState();

            // 程序化处理状态栏内边距（替代布局中的 fitsSystemWindows）
            View mainContent = findViewById(R.id.main_content);
            if (mainContent != null) {
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(mainContent, (v, insets) -> {
                    int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                    int left = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).left;
                    int right = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).right;
                    v.setPadding(left, top, right, 0);
                    return insets;
                });
            }

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

            // 注册在线模型变更监听，确保模型切换后名称即时刷新
            registerModelChangeListener();

            // 创建模型执行桥接器 - UI与模型之间的唯一通道
            modelBridge = ModelExecutionBridge.getInstance(this, aiService, agentService, aiConfig);

            if (aiService == null && !shouldUseOnlineModel()) {
                showToast("AI服务初始化失败");
                return;
            }

            // 0.3 初始化输出路由器
            initOutputRouter();

            // 1. 初始化基础管理器（不依赖模块）
            attachmentManager = new AttachmentManager(this);
            fileContentExtractor = new FileContentExtractor(this);
            initAttachFileLauncher();
            initGuideFilePickers();

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
                            // 单文件历史为空，尝试从最新的会话文件中恢复
                            List<ConversationSession> sessions = chatHistoryManager.listConversationSessions();
                            if (sessions != null && !sessions.isEmpty()) {
                                // 列表已按更新时间降序排列，取第一个即为最新会话
                                ConversationSession latest = sessions.get(0);
                                ConversationSession fullSession = chatHistoryManager.loadConversationSession(latest.id);
                                if (fullSession != null && fullSession.messages != null && !fullSession.messages.isEmpty()) {
                                    final String sessionId = fullSession.id;
                                    runOnUiThread(() -> {
                                        chatHistory.addAll(fullSession.messages);
                                        currentSessionId = sessionId;
                                        if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                                        updateEmptyState();
                                        scrollToBottom(true);
                                        showToast("已恢复上次对话");
                                    });
                                } else {
                                    runOnUiThread(() -> showWelcomeGuide());
                                }
                            } else {
                                // 历史为空，显示新手引导
                                runOnUiThread(() -> showWelcomeGuide());
                            }
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

        // 观察生成状态变化（兼容旧版）
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

        // 观察错误信息（兼容旧版 toast）
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

        // ========== 状态机观察：UI 渲染的主数据源 ==========

        // AI 状态变化 → 渲染不同 UI 状态
        chatViewModel.getAIState().observe(this, state -> {
            if (state == null) return;
            renderAIState(state);
        });

        // 结构化错误 → 渲染错误卡片（替代简单 toast）
        chatViewModel.getAIError().observe(this, error -> {
            if (error == null) return;
            renderAIError(error);
        });

        // 推理进度心跳 → 更新进度条/计时
        chatViewModel.getInferenceProgress().observe(this, progress -> {
            if (progress == null) return;
            updateInferenceProgressUI(progress);
        });
    }

    // ========== 状态机 UI 渲染 ==========

    /**
     * 根据 AIState 渲染 UI —— 这是 UI 层与状态机的唯一对接点
     */
    private void renderAIState(AIChatViewModel.AIState state) {
        switch (state) {
            case IDLE:
                hideLoading();
                updateSendButtonState(true, "发送");
                break;

            case LOADING:
                showLoading("AI模型加载中...", null);
                updateSendButtonState(false, "加载中...");
                break;

            case READY:
                hideLoading();
                updateSendButtonState(true, "发送");
                break;

            case INFERRING:
                showLoading("AI 正在推理...", null);
                updateSendButtonState(false, "停止");
                break;

            case ERROR:
                hideLoading();
                // 错误状态由 aiError LiveData 处理
                break;

            case UNLOADED:
                hideLoading();
                showModelUnloadedCard();
                break;
        }
    }

    /**
     * 渲染结构化错误卡片 —— 根据错误类型显示不同操作建议
     */
    private void renderAIError(AIChatViewModel.AIError error) {
        String title;
        String advice;
        int iconRes;

        switch (error.type) {
            case "INIT":
                title = "初始化失败";
                advice = error.retryable ? "点击重新初始化" : "请重启应用";
                break;
            case "TIMEOUT":
                title = "AI 响应超时";
                advice = "模型可能计算较慢，可点击重试或降低上下文长度";
                break;
            case "NATIVE_CRASH":
                title = "AI 引擎异常";
                advice = "本地推理引擎崩溃，建议降低上下文长度或切换在线模型";
                break;
            case "MEMORY":
                title = "内存不足";
                advice = "可用内存不足，建议释放后台应用或使用更小的模型";
                break;
            case "CANCELLED":
                title = "推理已取消";
                advice = "可重新发送消息";
                break;
            default:
                title = "AI 推理异常";
                advice = error.retryable ? "可点击重试" : "请重启应用";
                break;
        }

        // 显示带重试按钮的错误 Dialog
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("⚠️ " + title)
                .setMessage(error.message + "\n\n💡 " + advice)
                .setPositiveButton(error.retryable ? "重试" : "知道了", (d, w) -> {
                    if (error.retryable) {
                        chatViewModel.initialize();
                    }
                })
                .setNegativeButton("关闭", null)
                .setCancelable(true)
                .show();
    }

    /**
     * 更新推理进度 UI —— 显示 elapsedMs / tokenCount 等
     */
    private void updateInferenceProgressUI(AIChatViewModel.InferenceProgress progress) {
        // 在 loading 指示器中显示推理进度
        String phaseText;
        switch (progress.phase) {
            case "thinking":
                phaseText = "🤔 正在思考";
                break;
            case "generating":
                phaseText = "✍️ 正在生成";
                break;
            case "complete":
                phaseText = "✅ 完成";
                break;
            default:
                phaseText = "⏳ 正在推理";
        }
        String text = String.format("%s · %.1fs · %d tokens",
                phaseText, progress.elapsedMs / 1000.0, progress.tokenCount);
        updateLoadingText(text);
    }

    /**
     * 显示模型已卸载卡片 —— 提示用户重新加载
     */
    private void showModelUnloadedCard() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("📦 AI 模型已卸载")
                .setMessage("因内存不足或系统回收，AI 模型已从内存释放。点击确定重新加载模型。")
                .setPositiveButton("重新加载", (d, w) -> {
                    chatViewModel.initialize();
                })
                .setNegativeButton("稍后再说", null)
                .setCancelable(true)
                .show();
    }

    /**
     * 更新发送按钮状态（可点击/文本）
     */
    private void updateSendButtonState(boolean enabled, String text) {
        try {
            if (btnSend != null) {
                btnSend.setEnabled(enabled);
                btnSend.setText(text);
            }
        } catch (Exception ignored) {}
    }

    /**
     * 停止/发送按钮切换：生成中显示停止、隐藏发送；空闲反之（同位置切换）
     */
    private void toggleStopButton(boolean show) {
        if (btnStopGeneration != null) {
            btnStopGeneration.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (btnSend != null) {
            btnSend.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * 更新 loading 文本（通过 thinkingIndicator 或 loading view）
     */
    private void updateLoadingText(String text) {
        try {
            showLoading(text, null);
        } catch (Exception ignored) {}
    }

    /**
     * 初始化输出路由器
     */
    private void initOutputRouter() {
        outputRouter = new OutputRouter(new OutputRouter.OutputHandler() {
            @Override
            public void onTextOutput(String text, boolean isComplete) {
                runOnUiThread(() -> {
                    if (currentStreamingMessageIndex < 0 || currentStreamingMessageIndex >= chatHistory.size()) return;
                    // 加锁快照
                    String contentSnapshot;
                    synchronized (streamingLock) {
                        if (currentStreamingContent == null) return;
                        if (isComplete) {
                            // OutputRouter.complete() 会在流式结束时把完整正文再整体发送一次
                            // （isComplete=true，含思考兜底移入主回复的场景）。
                            // 流式期间 token 已逐个追加，此处必须用替换而非追加，否则正文翻倍重复。
                            // text 为空时保留已有内容，避免误清空工具结果等直接追加的内容。
                            if (!text.isEmpty()) {
                                currentStreamingContent.setLength(0);
                                currentStreamingContent.append(text);
                            }
                        } else {
                            currentStreamingContent.append(text);
                        }
                        contentSnapshot = currentStreamingContent.toString();
                    }
                    // 完成事件只是全文重发，流式期间已逐 token 投喂过朗读，不能重复投喂
                    if (!isComplete) {
                        feedStreamingTts(text);
                    }
                    ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                    msg.content = contentSnapshot;
                    if (chatAdapter != null) {
                        chatAdapter.updateAIMessageContent(currentStreamingMessageIndex, contentSnapshot);
                    }
                });
            }

            @Override
            public void onThinkingStart() {
                runOnUiThread(() -> {
                    isInThinking = true;
                    synchronized (streamingLock) {
                        if (currentThinkingContent == null) {
                            currentThinkingContent = new StringBuilder();
                        }
                        // 立即显示思考状态，让用户感知到模型在思考
                        if (currentThinkingContent.length() == 0 && currentStreamingMessageIndex >= 0
                                && currentStreamingMessageIndex < chatHistory.size()) {
                            currentThinkingContent.append("正在思考...");
                        }
                    }
                    if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                        String snapshot;
                        synchronized (streamingLock) {
                            snapshot = currentThinkingContent != null ? currentThinkingContent.toString() : "";
                        }
                        ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                        msg.thinkingContent = snapshot;
                        if (chatAdapter != null) {
                            chatAdapter.updateMessageThinkingContent(currentStreamingMessageIndex, snapshot);
                        }
                    }
                });
            }

            @Override
            public void onThinkingContent(String content) {
                runOnUiThread(() -> {
                    if (currentStreamingMessageIndex < 0 || currentStreamingMessageIndex >= chatHistory.size()) return;
                    String snapshot;
                    synchronized (streamingLock) {
                        if (currentThinkingContent == null) return;
                        // 首次收到真实思考内容时，清掉占位的"正在思考..."
                        if (currentThinkingContent.toString().equals("正在思考...")) {
                            currentThinkingContent.setLength(0);
                        }
                        currentThinkingContent.append(content);
                        snapshot = currentThinkingContent.toString();
                    }
                    ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                    msg.thinkingContent = snapshot;
                    if (chatAdapter != null) {
                        chatAdapter.updateMessageThinkingContent(currentStreamingMessageIndex, snapshot);
                    }
                });
            }

            @Override
            public void onThinkingEnd() {
                runOnUiThread(() -> {
                    isInThinking = false;
                    if (currentStreamingMessageIndex < 0 || currentStreamingMessageIndex >= chatHistory.size()) return;
                    String snapshot;
                    synchronized (streamingLock) {
                        snapshot = currentThinkingContent != null ? currentThinkingContent.toString() : "";
                    }
                    ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                    msg.thinkingContent = snapshot;
                    // 思考结束自动折叠，用户可点击重新展开
                    msg.thinkingExpanded = false;
                    if (chatAdapter != null) {
                        chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
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
                        finishAutoSpeak(msg);
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
            @Override public void onStartNewConversation() { startNewConversation(); }
            @Override public void onRegenerate(String messageId) { regenerateMessage(messageId); }
        });

        // 3. ChatHistoryController - 历史记录管理
        historyController = new ChatHistoryController(this, new ChatHistoryController.Callback() {
            @Override public void onClearChat() { clearChat(); }
            @Override public void onShowToast(String message) { showToast(message); }
            @Override public void onSwitchToSession(ConversationSession session) { switchToSession(session); }
            @Override public void onDeleteSession(ConversationSession session) { deleteSession(session); }
        });
        if (drawerLayout != null && historyList != null) {
            historyController.init(drawerLayout, historyList);
            refreshHistoryDrawer();
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
        attachmentProcessor.setCallback(new AttachmentProcessor.Callback() {
            @Override
            public void onContentExtracted(String attachmentId, String content) {
                Log.d(TAG, "Content extracted for attachment: " + attachmentId);
            }

            @Override
            public void onExtractionFailed(String attachmentId, String error) {
                Log.e(TAG, "Extraction failed for attachment: " + attachmentId + ", error: " + error);
                runOnUiThread(() -> showToast("附件解析失败: " + error));
            }

            @Override
            public void onSummaryGenerated(String attachmentId, String summary) {
                Log.i(TAG, "Summary generated for attachment: " + attachmentId);
                
                // 在主线程更新UI
                runOnUiThread(() -> {
                    // 查找对应的附件并更新摘要
                    if (inputManager != null) {
                        List<ChatMessage.Attachment> attachments = inputManager.getCurrentAttachments();
                        for (ChatMessage.Attachment attachment : attachments) {
                            if (attachment.id.equals(attachmentId)) {
                                attachment.aiSummary = summary;
                                break;
                            }
                        }
                    }
                    
                    // 刷新适配器显示新摘要
                    if (chatAdapter != null) {
                        chatAdapter.notifyDataSetChanged();
                    }
                    
                    showToast("✅ AI摘要生成完成");
                });
            }
        });

        // 8. GenerationLifecycleManager - 生成生命周期管理
        lifecycleManager = new GenerationLifecycleManager(this, uiHandler, new GenerationLifecycleManager.Callback() {
            @Override public void onShowThinkingIndicator() { showLoading("正在思考...", null); }
            @Override public void onHideThinkingIndicator() { hideLoading(); }
            @Override public void onShowStopButton() { toggleStopButton(true); }
            @Override public void onHideStopButton() { toggleStopButton(false); }
            @Override public void onUpdateMessageContent(int index, String content) { if (chatAdapter != null && index >= 0 && index < chatHistory.size()) { chatHistory.get(index).content = content; chatAdapter.notifyItemChanged(index, ChatAdapter.PAYLOAD_CONTENT_UPDATE); } }
            @Override public void onUpdateMessageThinking(int index, String thinkingContent) { if (chatAdapter != null && index >= 0 && index < chatHistory.size()) { chatAdapter.updateMessageThinkingContent(index, thinkingContent); } }
            @Override public void onAddAIMessage(ChatMessage message) { chatHistory.add(message); if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1); scrollToBottom(true); }
            @Override public void onAddSystemMessage(String message) { addSystemMessage(message); }
            @Override public void onScrollToBottom() { scrollToBottom(); }
            @Override public void onSaveHistoryAsync() { saveHistoryAsync(); }
            @Override public void onShowToast(String message) { showToast(message); }
        });

        // 9. StreamingTokenPipeline - 流式Token处理管道
        streamingPipeline = new StreamingTokenPipeline(new StreamingTokenPipeline.TokenListener() {
            @Override public void onContentToken(String token) { lifecycleManager.handleToken(token); }
            @Override public void onThinkingToken(String token) { lifecycleManager.handleThinkingToken(token); }
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
        // 模型名称文字也可点击，打开模型选择页面
        if (modelNameText != null) {
            modelNameText.setOnClickListener(v -> {
                Intent intent = new Intent(AIChatActivity.this, ModelSelectorActivity.class);
                startActivity(intent);
            });
        }
        if (btnClearChat != null) btnClearChat.setOnClickListener(v -> clearChat());
        if (btnStopGeneration != null) btnStopGeneration.setOnClickListener(v -> stopGeneration());
        if (btnSend != null) btnSend.setOnClickListener(v -> sendMessage());
        if (btnAttach != null) {
            btnAttach.setOnClickListener(v -> showAttachmentOptionsDialog());
        }
        if (btnVoice != null) {
            btnVoice.setOnClickListener(v -> handleSpeechInput());
        }

        // 自动语音合成开关：状态持久化，开启后 AI 回复流式按句自动朗读
        streamingTtsSpeaker = new com.oilquiz.app.ai.speech.StreamingTtsSpeaker(this);
        autoTtsEnabled = getSharedPreferences("ai_chat_prefs", MODE_PRIVATE)
                .getBoolean("auto_tts_enabled", false);
        if (btnAutoTts != null) {
            updateAutoTtsButtonUI();
            btnAutoTts.setOnClickListener(v -> toggleAutoTts());
        }
        updateVoiceButtonAvailability();

        if (btnHistory != null) {
            btnHistory.setOnClickListener(v -> {
                if (drawerLayout != null && historyController != null) {
                    refreshHistoryDrawer();
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
                refreshHistoryDrawer();
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
            // 状态机双通道取消
            if (chatViewModel != null) chatViewModel.stopGeneration();

            // 旧版路径兼容
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
            // 文本输入框（动态列表步骤同时保留手动输入兜底）
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

            // 动态选项：声明了 dynamicOptions 时异步拉取列表渲染为可点选项，减少手动输入
            if (step.dynamicOptions != null) {
                final LinearLayout optionsBox = new LinearLayout(this);
                optionsBox.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams obLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                obLp.topMargin = dp(12);
                optionsBox.setLayoutParams(obLp);
                container.addView(optionsBox);

                TextView loadingView = new TextView(this);
                loadingView.setText("⏳ 正在获取可选列表...");
                loadingView.setTextSize(12);
                loadingView.setTextColor(0xFF888888);
                optionsBox.addView(loadingView);

                final ToolGuideFlow.GuideStep.DynamicOptionsSpec spec = step.dynamicOptions;
                new Thread(() -> {
                    java.util.List<String> values = loadGuideDynamicOptions(spec);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        optionsBox.removeAllViews();
                        if (values == null || values.isEmpty()) {
                            TextView failView = new TextView(this);
                            failView.setText("ℹ️ 未能获取列表，请直接输入");
                            failView.setTextSize(12);
                            failView.setTextColor(0xFF888888);
                            optionsBox.addView(failView);
                            return;
                        }
                        TextView tipView = new TextView(this);
                        tipView.setText("👇 点击选择（或在上方直接输入）");
                        tipView.setTextSize(12);
                        tipView.setTextColor(0xFF666666);
                        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                        tipLp.bottomMargin = dp(6);
                        tipView.setLayoutParams(tipLp);
                        optionsBox.addView(tipView);

                        // 可点选项：点击后回填输入框并自动进入下一步
                        final int MAX_OPTIONS = 60;
                        int shown = Math.min(values.size(), MAX_OPTIONS);
                        for (int i = 0; i < shown; i++) {
                            final String val = values.get(i);
                            TextView chip = new TextView(this);
                            chip.setText(val.isEmpty() ? (spec.allOptionLabel != null ? spec.allOptionLabel : "全部") : val);
                            chip.setTextSize(13);
                            chip.setTextColor(0xFF333333);
                            chip.setPadding(dp(12), dp(10), dp(12), dp(10));
                            android.graphics.drawable.GradientDrawable chipBg = new android.graphics.drawable.GradientDrawable();
                            chipBg.setColor(0xFFF5F5F5);
                            chipBg.setCornerRadius(dp(8));
                            chip.setBackground(chipBg);
                            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                            chipLp.bottomMargin = dp(6);
                            chip.setLayoutParams(chipLp);
                            chip.setOnClickListener(cv -> {
                                editText.setText(val);
                                selectedParams.put(step.paramKey, val);
                                activeStepsHolder[0] = flow.getActiveSteps(selectedParams);
                                currentStepIdx[0]++;
                                renderStep(dialog, flow, toolName, selectedParams, currentStepIdx, activeStepsHolder);
                            });
                            optionsBox.addView(chip);
                        }
                        if (values.size() > MAX_OPTIONS) {
                            TextView moreView = new TextView(this);
                            moreView.setText("… 共 " + values.size() + " 项，其余请直接输入");
                            moreView.setTextSize(11);
                            moreView.setTextColor(0xFFAAAAAA);
                            optionsBox.addView(moreView);
                        }
                    });
                }).start();
            }

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
        } else if (step.type == ToolGuideFlow.GuideStep.StepType.FILE_PICKER
                || step.type == ToolGuideFlow.GuideStep.StepType.IMAGE_PICKER
                || step.type == ToolGuideFlow.GuideStep.StepType.DIRECTORY_PICKER) {
            // 文件/图片/目录选择器UI + 手动输入兜底

            // 1. 显示当前路径的只读文本
            final TextView valueView = new TextView(this);
            if (step.paramValue != null && !step.paramValue.isEmpty()) {
                valueView.setText(step.paramValue);
            } else if (selectedParams.containsKey(step.paramKey)) {
                step.paramValue = selectedParams.get(step.paramKey);
                valueView.setText(step.paramValue);
            } else {
                valueView.setText("（尚未选择）");
            }
            valueView.setTextSize(12);
            valueView.setTextColor(0xFF555555);
            valueView.setMaxLines(3);
            valueView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            int padDp = (int) (12 * getResources().getDisplayMetrics().density);
            valueView.setPadding(padDp, padDp / 2, padDp, padDp / 2);
            valueView.setBackgroundColor(0xFFF5F5F5);
            LinearLayout.LayoutParams vvLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            vvLp.topMargin = padDp;
            valueView.setLayoutParams(vvLp);
            container.addView(valueView);

            // 2. 按钮栏横向排列：「选择文件/图片/目录」 与 「手动输入路径」
            LinearLayout btnBar = new LinearLayout(this);
            btnBar.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bbLp.topMargin = padDp;
            btnBar.setLayoutParams(bbLp);

            MaterialButton pickerBtn = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            String pickBtnTitle;
            if (step.type == ToolGuideFlow.GuideStep.StepType.IMAGE_PICKER) pickBtnTitle = "选择图片";
            else if (step.type == ToolGuideFlow.GuideStep.StepType.DIRECTORY_PICKER) pickBtnTitle = "选择目录";
            else pickBtnTitle = "选择文件";
            if (step.paramValue != null && !step.paramValue.isEmpty()) pickBtnTitle = "已选择 ✓";
            pickerBtn.setText(pickBtnTitle);
            LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            pbLp.setMarginEnd(padDp);
            pickerBtn.setLayoutParams(pbLp);
            final MaterialButton pickerBtnRef = pickerBtn;
            pickerBtn.setOnClickListener(v -> launchGuidePicker(step, valueView, pickerBtnRef));
            btnBar.addView(pickerBtn);

            MaterialButton manualBtn = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            manualBtn.setText("手动输入");
            LinearLayout.LayoutParams mbLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            manualBtn.setLayoutParams(mbLp);
            manualBtn.setOnClickListener(v -> showManualPathInput(step, valueView, pickerBtnRef));
            btnBar.addView(manualBtn);
            container.addView(btnBar);

            // 3. 下一步按钮（与 INPUT 分支一致，使用同一个 selectedParams 聚合）
            android.widget.Button nextBtn = new android.widget.Button(this);
            nextBtn.setText("下一步");
            nextBtn.setBackgroundColor(0xFF6200EE);
            nextBtn.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nextLp.topMargin = padDp * 2;
            nextBtn.setLayoutParams(nextLp);
            nextBtn.setOnClickListener(v -> {
                String value = (step.paramValue != null) ? step.paramValue : "";
                value = value.trim();
                if (step.required && value.isEmpty()) {
                    android.widget.Toast.makeText(this, "请选择或输入路径", android.widget.Toast.LENGTH_SHORT).show();
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
                // 防重复点击：首次点击后禁用按钮，避免并行重复执行工具
                if (!execBtn.isEnabled()) return;
                execBtn.setEnabled(false);
                execBtn.setText("正在执行...");

                // 收集参数：仅保留当前活跃步骤涉及的参数，
                // 避免回退改选后旧分支的残留参数被一并提交
                final Map<String, Object> execParams = new HashMap<>();
                java.util.Set<String> activeParamKeys = new java.util.HashSet<>();
                for (ToolGuideFlow.GuideStep s : activeSteps) {
                    if (s != null && s.paramKey != null && !s.paramKey.isEmpty()) {
                        activeParamKeys.add(s.paramKey);
                    }
                }
                for (Map.Entry<String, String> entry : selectedParams.entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isEmpty()
                            && activeParamKeys.contains(entry.getKey())) {
                        execParams.put(entry.getKey(), entry.getValue());
                    }
                }
                // 注入已自动获取的上下文（不覆盖用户已填值）
                injectAutoContext(execParams);

                // app_toolkit 部分分类（计算/获取信息）无 action 子步骤，直接用分类名作为 action
                if ("app_toolkit".equals(toolName)
                        && execParams.containsKey("category") && !execParams.containsKey("action")) {
                    String cat = String.valueOf(execParams.get("category"));
                    if ("calculate".equals(cat) || "get_info".equals(cat)) {
                        execParams.put("action", cat);
                    }
                }

                // 执行前检查缺失的环境上下文
                List<String> missing = ToolContextProvider.getMissingContext(flow, execParams);
                if (missing.contains("location") && "ai_weather".equals(toolName)) {
                    // 缺少位置且为天气工具：异步定位后再执行
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
                    // === 成功：工具卡片更新 + 主气泡显示结果（自然语言摘要） ===
                    Object rawResult = result.getResult();
                    String resultStr = ToolResultInterpreter.formatForUi(toolName, rawResult);
                    // 保存原始结果数据 + canInterpret（乐观策略）
                    if (msgPos >= 0 && msgPos < chatHistory.size()) {
                        ChatMessage msg = chatHistory.get(msgPos);
                        if (msg != null && msg.toolCallInfo != null) {
                            msg.toolCallInfo.rawResult = rawResult;
                            msg.toolCallInfo.canInterpret = true;
                        }
                    }
                    // 1) 更新工具卡片自身的结果区（保留详细结构 + 折叠区里的深度解读按钮）
                    updateToolCallResult(msgPos, true, resultStr);
                    addSystemMessage("✅ 工具执行完成");
                    scrollToBottom();
                    // 保存到ToolResultStore
                    try {
                        ToolResultStore.save(toolName, params, rawResult, 0);
                    } catch (Exception ignore) { }
                    // 2) 自动解读：①写入 toolCallInfo.interpretedMessage（工具卡片内展示）
                    //              ②再添加一条新的独立 AI 主气泡（addAIMessage）——两处都显示
                    // 本地模型解读可能耗时数十秒，先给进度提示避免用户以为功能卡死
                    addSystemMessage("💡 正在AI解读结果...");
                    scrollToBottom();
                    // 记录进度消息位置，无模型可用时关闭提示避免"正在解读"文案残留
                    final int progressMsgPos = chatHistory.size() - 1;
                    final Runnable doContinue = onComplete;
                    final String fallback = resultStr;
                    final int targetMsgPos = msgPos;
                    ToolResultInterpreter.interpret(this, toolName, rawResult,
                        new ToolResultInterpreter.InterpretCallback() {
                            @Override
                            public void onInterpreted(String summary) {
                                boolean hasInterpret = summary != null && !summary.isEmpty();
                                String content = hasInterpret ? summary : fallback;
                                writeInterpretedToMessage(targetMsgPos, content);
                                // 只有真正的 AI 解读才额外加 AI 主气泡；
                                // fallback（模板文本）已在工具卡片展示，不再重复显示
                                if (hasInterpret) {
                                    addAIMessage(content);
                                    // 同步注入本地模型主对话上下文，使后续追问可连续对话
                                    injectInterpretToLocalContext(content);
                                } else {
                                    // 无任何可用模型（在线/本地均不可用）：关闭进度提示，
                                    // 工具结果已以模板形式展示在工具卡片，功能不中断
                                    updateSystemMessageText(progressMsgPos, "ℹ️ 无可用AI模型解读，已展示模板结果");
                                }
                                scrollToBottom();
                                if (doContinue != null) doContinue.run();
                            }
                            @Override
                            public void onError(String error) {
                                writeInterpretedToMessage(targetMsgPos, fallback);
                                // 同上：模板兜底内容已在工具卡片内，不重复加 AI 气泡
                                updateSystemMessageText(progressMsgPos, "ℹ️ AI解读失败，已展示模板结果");
                                scrollToBottom();
                                if (doContinue != null) doContinue.run();
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
            executeToolWithRecovery(toolName, params, msgPos, retryCount + 1, onComplete);
            return;
        }

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        dialog.setTitle("🔧 需要补充信息");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        TextView titleView = new TextView(this);
        titleView.setText("工具执行缺少参数，请补充：");
        titleView.setTextSize(15);
        titleView.setTextColor(0xFF333333);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(titleView);

        // 普通文本输入 EditText
        final java.util.Map<String, EditText> inputs = new java.util.HashMap<>();
        // 路径类参数对应的临时 GuideStep（包含 paramValue 真实值）
        if (missingParamPickerSteps == null) missingParamPickerSteps = new java.util.HashMap<>();
        missingParamPickerSteps.clear();

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

            if (isPathLikeParam(mp.key, mp.description)) {
                // 路径类参数：使用选择器 + 手动输入兜底
                ToolGuideFlow.GuideStep.StepType pickerType = deducePickerType(mp.key, mp.description);
                ToolGuideFlow.GuideStep tempStep = new ToolGuideFlow.GuideStep();
                tempStep.type = pickerType;
                tempStep.title = mp.description;
                tempStep.description = "请选择" + mp.description + "，或手动输入路径";
                tempStep.paramKey = mp.key;
                tempStep.required = mp.required;
                tempStep.hint = "输入路径";
                tempStep.multiline = false;
                if (params.containsKey(mp.key) && params.get(mp.key) != null) {
                    tempStep.paramValue = String.valueOf(params.get(mp.key));
                }
                missingParamPickerSteps.put(mp.key, tempStep);

                final TextView valueView = new TextView(this);
                valueView.setText((tempStep.paramValue != null && !tempStep.paramValue.isEmpty())
                        ? tempStep.paramValue : "（尚未选择）");
                valueView.setTextSize(12);
                valueView.setTextColor(0xFF555555);
                valueView.setMaxLines(3);
                valueView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                int padDp = (int) (12 * getResources().getDisplayMetrics().density);
                valueView.setPadding(padDp, padDp / 2, padDp, padDp / 2);
                valueView.setBackgroundColor(0xFFF5F5F5);
                LinearLayout.LayoutParams vvLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                vvLp.topMargin = 4;
                valueView.setLayoutParams(vvLp);
                layout.addView(valueView);

                LinearLayout btnBar = new LinearLayout(this);
                btnBar.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                bbLp.topMargin = padDp;
                btnBar.setLayoutParams(bbLp);

                MaterialButton pickerBtn = new MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
                String pickBtnTitle;
                if (pickerType == ToolGuideFlow.GuideStep.StepType.IMAGE_PICKER) pickBtnTitle = "选择图片";
                else if (pickerType == ToolGuideFlow.GuideStep.StepType.DIRECTORY_PICKER) pickBtnTitle = "选择目录";
                else pickBtnTitle = "选择文件";
                if (tempStep.paramValue != null && !tempStep.paramValue.isEmpty()) pickBtnTitle = "已选择 ✓";
                pickerBtn.setText(pickBtnTitle);
                LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                pbLp.setMarginEnd(padDp);
                pickerBtn.setLayoutParams(pbLp);
                final MaterialButton pickerBtnRef = pickerBtn;
                pickerBtn.setOnClickListener(v -> launchGuidePicker(tempStep, valueView, pickerBtnRef));
                btnBar.addView(pickerBtn);

                MaterialButton manualBtn = new MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
                manualBtn.setText("手动输入");
                LinearLayout.LayoutParams mbLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                manualBtn.setLayoutParams(mbLp);
                manualBtn.setOnClickListener(v -> showManualPathInput(tempStep, valueView, pickerBtnRef));
                btnBar.addView(manualBtn);
                layout.addView(btnBar);
            } else {
                // 普通文本输入
                EditText input = new EditText(this);
                input.setHint("请输入" + mp.description);
                input.setTextSize(14);
                LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                inputLp.topMargin = 4;
                input.setLayoutParams(inputLp);
                // 如果参数对象中已有值（用户之前填过但不对），预填
                if (params.containsKey(mp.key) && params.get(mp.key) != null) {
                    input.setText(String.valueOf(params.get(mp.key)));
                }
                layout.addView(input);
                inputs.put(mp.key, input);
            }
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
                String value = null;
                EditText et = inputs.get(mp.key);
                if (et != null) {
                    value = et.getText().toString().trim();
                } else if (missingParamPickerSteps != null) {
                    ToolGuideFlow.GuideStep tempStep = missingParamPickerSteps.get(mp.key);
                    if (tempStep != null && tempStep.paramValue != null) {
                        value = tempStep.paramValue.trim();
                    }
                }
                if (value == null || value.isEmpty()) {
                    if (mp.required) allFilled = false;
                } else {
                    params.put(mp.key, value);
                }
            }
            if (!allFilled) {
                android.widget.Toast.makeText(this, "请填写所有必填参数", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            if (missingParamPickerSteps != null) missingParamPickerSteps.clear();
            addSystemMessage("📝 已补充参数，正在重试...");
            scrollToBottom();
            executeToolWithRecovery(toolName, params, msgPos, retryCount + 1, onComplete);
        });
        layout.addView(submitBtn);

        dialog.setOnDismissListener(d -> {
            if (missingParamPickerSteps != null) missingParamPickerSteps.clear();
        });
        dialog.setContentView(layout);
        dialog.show();
    }

    /** 判断参数是否属于路径/文件类（适合使用文件选择器） */
    private boolean isPathLikeParam(String key, String desc) {
        if (key == null) return false;
        String k = key.toLowerCase();
        if (k.contains("file_path") || k.contains("image_path") || k.contains("directory_path")
                || k.contains("source_path") || k.contains("target_path") || k.contains("output_path")
                || k.contains("save_path") || k.equals("path")
                || k.contains("folder") || k.contains("dir_path")) {
            return true;
        }
        // file_name 只有在 desc 暗示路径时才使用选择器
        if (k.equals("file_name") && desc != null) {
            String d = desc.toLowerCase();
            if (d.contains("路径") || d.contains("目录") || d.contains("文件夹")) return true;
        }
        if (desc != null) {
            String d = desc.toLowerCase();
            if (d.contains("路径") || d.contains("文件路径") || d.contains("目录路径")
                    || d.contains("文件夹") || d.contains("图片路径")) {
                return true;
            }
        }
        return false;
    }

    /** 聚合步骤参数收集回调 */
    private interface CompositeParamCollector {
        void onCollected(Map<String, String> params);
    }

    /** 根据参数键名和描述推断选择器类型（文件/图片/目录） */
    private ToolGuideFlow.GuideStep.StepType deducePickerType(String key, String desc) {
        if (key != null) {
            String k = key.toLowerCase();
            if (k.contains("image_path") || k.contains("picture") || k.contains("photo") || k.contains("img")) {
                return ToolGuideFlow.GuideStep.StepType.IMAGE_PICKER;
            }
            if (k.contains("directory") || k.contains("folder") || k.contains("dir_path")) {
                return ToolGuideFlow.GuideStep.StepType.DIRECTORY_PICKER;
            }
        }
        if (desc != null) {
            String d = desc.toLowerCase();
            if (d.contains("图片") || d.contains("照片")) {
                return ToolGuideFlow.GuideStep.StepType.IMAGE_PICKER;
            }
            if (d.contains("目录") || d.contains("文件夹")) {
                return ToolGuideFlow.GuideStep.StepType.DIRECTORY_PICKER;
            }
        }
        return ToolGuideFlow.GuideStep.StepType.FILE_PICKER;
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
                            Object rawResult = result.getResult();
                            String resultStr = ToolResultInterpreter.formatForUi(step.toolName, rawResult);
                            // 先保存 rawResult + canInterpret（乐观策略，不在主线程调用 isAnyModelAvailable）
                            if (msgPos >= 0 && msgPos < chatHistory.size()) {
                                ChatMessage msg = chatHistory.get(msgPos);
                                if (msg != null && msg.toolCallInfo != null) {
                                    msg.toolCallInfo.rawResult = rawResult;
                                    msg.toolCallInfo.canInterpret = true;
                                }
                            }
                            // 1) 更新工具卡片自身的结果区（保留详细结构 + 折叠区里的深度解读按钮）
                            updateToolCallResult(msgPos, true, resultStr);
                            addSystemMessage("✅ " + step.actionDescription + " 完成");
                            scrollToBottom();
                            // 保存结果
                            try {
                                ToolResultStore.save(step.toolName, params, rawResult, stepIndex);
                            } catch (Exception ignore) { }
                            // 2) 自动解读：①写工具卡片 + ②新的独立 AI 主气泡（仅真正解读时）
                            // 解读可能耗时较长，先给进度提示
                            addSystemMessage("💡 正在AI解读结果...");
                            scrollToBottom();
                            // 记录进度消息位置，无模型可用时关闭提示避免"正在解读"文案残留
                            final int progressMsgPos2 = chatHistory.size() - 1;
                            final int nextIndex = stepIndex + 1;
                            final String fallback = resultStr;
                            final int targetMsgPos2 = msgPos;
                            ToolResultInterpreter.interpret(AIChatActivity.this, step.toolName, rawResult,
                                new ToolResultInterpreter.InterpretCallback() {
                                    @Override
                                    public void onInterpreted(String summary) {
                                        boolean hasInterpret = summary != null && !summary.isEmpty();
                                        String content = hasInterpret ? summary : fallback;
                                        writeInterpretedToMessage(targetMsgPos2, content);
                                        // 仅真正 AI 解读才加主气泡，模板兜底已在工具卡片展示
                                        if (hasInterpret) {
                                            addAIMessage(content);
                                            // 同步注入本地模型主对话上下文，使后续追问可连续对话
                                            injectInterpretToLocalContext(content);
                                        } else {
                                            // 无任何可用模型：关闭进度提示，组合流程继续执行下一步
                                            updateSystemMessageText(progressMsgPos2, "ℹ️ 无可用AI模型解读，已展示模板结果");
                                        }
                                        scrollToBottom();
                                        executeCompositeFlow(flow, nextIndex);
                                    }
                                    @Override
                                    public void onError(String error) {
                                        writeInterpretedToMessage(targetMsgPos2, fallback);
                                        // 模板兜底内容已在工具卡片内，不重复加 AI 气泡
                                        updateSystemMessageText(progressMsgPos2, "ℹ️ AI解读失败，已展示模板结果");
                                        scrollToBottom();
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
                    Object rawResult = result.getResult();
                    String resultStr = ToolResultInterpreter.formatForUi(step.toolName, rawResult);
                    // 先保存 rawResult + canInterpret（乐观策略，不在主线程调用 isAnyModelAvailable）
                    if (msgPos >= 0 && msgPos < chatHistory.size()) {
                        ChatMessage msg = chatHistory.get(msgPos);
                        if (msg != null && msg.toolCallInfo != null) {
                            msg.toolCallInfo.rawResult = rawResult;
                            msg.toolCallInfo.canInterpret = true;
                        }
                    }
                    // 1) 更新工具卡片自身的结果区（保留详细结构 + 折叠区里的深度解读按钮）
                    updateToolCallResult(msgPos, true, resultStr);
                    addSystemMessage("✅ " + step.actionDescription + " 完成");
                    scrollToBottom();
                    try {
                        ToolResultStore.save(step.toolName, params, rawResult, stepIndex);
                    } catch (Exception ignore) { }
                    // 2) 自动解读：①写工具卡片 + ②仅真正解读时加独立 AI 主气泡（模板兜底不重复显示）
                    final int nextIndex = stepIndex + 1;
                    final String fallback = resultStr;
                    final int targetMsgPos3 = msgPos;
                    ToolResultInterpreter.interpret(this, step.toolName, rawResult,
                        new ToolResultInterpreter.InterpretCallback() {
                            @Override
                            public void onInterpreted(String summary) {
                                boolean hasInterpret = summary != null && !summary.isEmpty();
                                String content = hasInterpret ? summary : fallback;
                                writeInterpretedToMessage(targetMsgPos3, content);
                                if (hasInterpret) {
                                    addAIMessage(content);
                                    // 同步注入本地模型主对话上下文，使后续追问可连续对话
                                    injectInterpretToLocalContext(content);
                                }
                                scrollToBottom();
                                executeCompositeFlow(flow, nextIndex);
                            }
                            @Override
                            public void onError(String error) {
                                writeInterpretedToMessage(targetMsgPos3, fallback);
                                // 模板兜底已在工具卡片展示，不重复加 AI 气泡
                                scrollToBottom();
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
        } else if (gs.type == ToolGuideFlow.GuideStep.StepType.FILE_PICKER
                || gs.type == ToolGuideFlow.GuideStep.StepType.IMAGE_PICKER
                || gs.type == ToolGuideFlow.GuideStep.StepType.DIRECTORY_PICKER) {
            // 聚合步骤收集的文件/图片/目录选择器 UI
            final TextView valueView = new TextView(this);
            if (collected.containsKey(gs.paramKey)) {
                gs.paramValue = collected.get(gs.paramKey);
            }
            if (gs.paramValue != null && !gs.paramValue.isEmpty()) {
                valueView.setText(gs.paramValue);
            } else {
                valueView.setText("（尚未选择）");
            }
            valueView.setTextSize(12);
            valueView.setTextColor(0xFF555555);
            valueView.setMaxLines(3);
            valueView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            int padDp = dp(12);
            valueView.setPadding(padDp, padDp / 2, padDp, padDp / 2);
            valueView.setBackgroundColor(0xFFF5F5F5);
            LinearLayout.LayoutParams vvLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            vvLp.topMargin = padDp;
            valueView.setLayoutParams(vvLp);
            container.addView(valueView);

            LinearLayout btnBar = new LinearLayout(this);
            btnBar.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bbLp.topMargin = padDp;
            btnBar.setLayoutParams(bbLp);

            MaterialButton pickerBtn = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            String pickBtnTitle;
            if (gs.type == ToolGuideFlow.GuideStep.StepType.IMAGE_PICKER) pickBtnTitle = "选择图片";
            else if (gs.type == ToolGuideFlow.GuideStep.StepType.DIRECTORY_PICKER) pickBtnTitle = "选择目录";
            else pickBtnTitle = "选择文件";
            if (gs.paramValue != null && !gs.paramValue.isEmpty()) pickBtnTitle = "已选择 ✓";
            pickerBtn.setText(pickBtnTitle);
            LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            pbLp.setMarginEnd(padDp);
            pickerBtn.setLayoutParams(pbLp);
            final MaterialButton pickerBtnRef = pickerBtn;
            pickerBtn.setOnClickListener(v -> launchGuidePicker(gs, valueView, pickerBtnRef));
            btnBar.addView(pickerBtn);

            MaterialButton manualBtn = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            manualBtn.setText("手动输入");
            LinearLayout.LayoutParams mbLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            manualBtn.setLayoutParams(mbLp);
            manualBtn.setOnClickListener(v -> showManualPathInput(gs, valueView, pickerBtnRef));
            btnBar.addView(manualBtn);
            container.addView(btnBar);

            android.widget.Button nextBtn = new android.widget.Button(this);
            nextBtn.setText("下一步");
            nextBtn.setBackgroundColor(0xFF6200EE);
            nextBtn.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nextLp.topMargin = padDp * 2;
            nextBtn.setLayoutParams(nextLp);
            nextBtn.setOnClickListener(v -> {
                String value = (gs.paramValue != null) ? gs.paramValue : "";
                value = value.trim();
                if (gs.required && value.isEmpty()) {
                    android.widget.Toast.makeText(this, "请选择或输入路径", android.widget.Toast.LENGTH_SHORT).show();
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
        // 新一轮对话开始：清空上一轮残留的工具组件收集，避免串轮
        com.oilquiz.app.ai.chat.component.ComponentCollector.clear();
        String message = inputMessage.getText().toString().trim();

        List<ChatMessage.Attachment> savedAttachments = new ArrayList<>(currentAttachments);

        // 图片附件走 OCR 工具 + 在线模型分析，不依赖本地模型加载状态
        boolean hasImageAttachment = false;
        for (ChatMessage.Attachment att : savedAttachments) {
            if ("image".equals(att.type)) { hasImageAttachment = true; break; }
        }

        // 允许"无文字直接发送图片"（拍照后直接点发送）
        if (message.isEmpty() && savedAttachments.isEmpty()) { showToast("请输入消息"); return; }

        if (!hasImageAttachment && !ensureModelLoaded(message)) {
            addUserMessage(message);
            inputMessage.setText("");
            return;
        }

        if (!savedAttachments.isEmpty()) {
            String userContent = message.isEmpty() ? DEFAULT_ATTACHMENT_MESSAGE : message;
            ChatMessage userMessage = ChatMessage.createUserMessage(userContent, savedAttachments);
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

        if (!message.isEmpty()) {
            if (message.equalsIgnoreCase("帮助") || message.equalsIgnoreCase("help")) {
                if (dialogHelper != null) dialogHelper.showGuideDialog(); return;
            }
            for (String[] pattern : COMMAND_PATTERNS) {
                if (message.startsWith(pattern[0])) {
                    handlePrefixedCommand(message, pattern[0], pattern[1]);
                    return;
                }
            }
        }

        if (!savedAttachments.isEmpty()) {
            String agentMessage = message.isEmpty() ? DEFAULT_ATTACHMENT_MESSAGE : message;
            if (hasImageAttachment || shouldUseOnlineModel()) {
                processMessageWithAttachmentsViaAgent(agentMessage, savedAttachments);
            } else if (fileContentExtractor != null) {
                processMessageWithAttachments(agentMessage, savedAttachments);
            } else {
                processChatMessage(agentMessage);
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
                // 1. 先展示上传状态（状态闭环起点：文件已落盘，开始解析）
                StringBuilder displayMsg = new StringBuilder();
                displayMsg.append("📎 已收到 ").append(filtered.size()).append(" 个附件\n\n");
                if (!skippedFiles.isEmpty()) {
                    displayMsg.append("跳过: ").append(String.join(", ", skippedFiles)).append("\n\n");
                }
                for (ChatMessage.Attachment att : filtered) {
                    Uri uri = Uri.parse(att.url);
                    boolean saved = localFileMap.get(uri) != null;
                    displayMsg.append("• ").append(att.name)
                            .append("（").append(formatFileSize(att.size)).append("）")
                            .append(saved ? " → 正在解析..." : " → ❗保存失败")
                            .append("\n");
                }
                displayMsg.append("\n⏳ 系统正在解析附件内容，请稍候...");

                ChatMessage sysMsg = ChatMessage.createSystemMessage(
                        java.util.UUID.randomUUID().toString(),
                        displayMsg.toString(),
                        ChatMessage.SystemMessageType.INFO,
                        System.currentTimeMillis()
                );
                chatHistory.add(sysMsg);
                if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1);
                scrollToBottom();

                // 2. 异步预解析（带进度更新 + 缓存 + 错误分类）
                getAttachmentPreParser().parseAllAsync(filtered, localFileMap,
                        new com.oilquiz.app.ai.chat.input.AttachmentPreParser.ProgressCallback() {
                    @Override
                    public void onProgress(int doneCount, int totalCount, String currentFile, String stage) {
                        runOnUiThread(() -> {
                            StringBuilder sb = rebuildParseProgressMsg(filtered, localFileMap, skippedFiles,
                                    doneCount, totalCount, currentFile, stage);
                            sysMsg.content = sb.toString();
                            if (chatAdapter != null) chatAdapter.notifyItemChanged(chatHistory.indexOf(sysMsg));
                            scrollToBottom();
                        });
                    }

                    @Override
                    public void onAllCompleted(List<com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult> results) {
                        runOnUiThread(() -> {
                            // 3. 解析完成：展示确定性结果（成功/部分成功/失败+原因）
                            sysMsg.content = buildParseSummaryMsg(filtered, results).toString();
                            if (chatAdapter != null) chatAdapter.notifyItemChanged(chatHistory.indexOf(sysMsg));
                            scrollToBottom();
                            saveHistoryAsync();

                            // 4. 构建确定性状态的增强消息，交给 Agent
                            java.util.Map<String, com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult> resultMap =
                                    new java.util.HashMap<>();
                            for (com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult r : results) {
                                resultMap.put(r.attachmentId, r);
                            }
                            String augmentedMessage = buildAgentAugmentedMessage(
                                    originalMessage, filtered, localFileMap, skippedFiles, resultMap);
                            AppLogger.ai(TAG, "Agent augmented message with " + localFileMap.size()
                                    + " local files, " + results.size() + " parse results");
                            // 附件分析需要可用的 AI 模型（图片走 OCR + 在线模型分析）
                            if (inferenceRouter == null || !inferenceRouter.isCurrentModelAvailable()) {
                                // OCR 识别结果不依赖 AI 模型，先展示给用户；模型不可用时仅提示配置，不阻断
                                StringBuilder ocrMsg = new StringBuilder();
                                ocrMsg.append(buildParseSummaryMsg(filtered, results));
                                ocrMsg.append("\n\n📄 OCR 识别内容预览：\n");
                                for (com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult r : results) {
                                    if (r.isUsable() && r.content != null) {
                                        String snippet = r.content.trim();
                                        if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "...";
                                        ocrMsg.append("• ").append(getAttachmentNameById(filtered, r.attachmentId))
                                                .append("：").append(snippet).append("\n");
                                    }
                                }
                                ocrMsg.append("\n⚠️ 图片已通过 OCR 识别完成。如需 AI 智能分析，请先在模型设置中启用在线模型，或先加载本地模型。");
                                sysMsg.content = ocrMsg.toString();
                                if (chatAdapter != null) chatAdapter.notifyItemChanged(chatHistory.indexOf(sysMsg));
                                scrollToBottom();
                                saveHistoryAsync();
                                return;
                            }
                            // 附件处理必须走 Agent 引擎（含工具调用），不能因聊天模式不同而退化为纯文本推理
                            processChatMessageWithAgent(augmentedMessage);
                        });
                    }
                });
            });
        });
    }

    /**
     * 附件预解析器（懒加载）
     */
    private com.oilquiz.app.ai.chat.input.AttachmentPreParser attachmentPreParser;
    private com.oilquiz.app.ai.chat.input.AttachmentPreParser getAttachmentPreParser() {
        if (attachmentPreParser == null) {
            attachmentPreParser = new com.oilquiz.app.ai.chat.input.AttachmentPreParser(this);
        }
        return attachmentPreParser;
    }

    /**
     * 构建解析进度中的系统消息
     */
    private StringBuilder rebuildParseProgressMsg(List<ChatMessage.Attachment> filtered,
            java.util.Map<Uri, String> localFileMap, List<String> skippedFiles,
            int doneCount, int totalCount, String currentFile, String stage) {
        StringBuilder sb = new StringBuilder();
        sb.append("📎 已收到 ").append(filtered.size()).append(" 个附件\n\n");
        if (skippedFiles != null && !skippedFiles.isEmpty()) {
            sb.append("跳过: ").append(String.join(", ", skippedFiles)).append("\n\n");
        }
        int idx = 0;
        for (ChatMessage.Attachment att : filtered) {
            String statusText;
            if (idx < doneCount) {
                statusText = att.isExtracted ? "✅ 解析完成" : "❌ 解析失败";
            } else if (idx == doneCount) {
                statusText = "⏳ " + stage;
            } else {
                statusText = "⏸ 排队中";
            }
            sb.append("• ").append(att.name).append(" → ").append(statusText).append("\n");
            idx++;
        }
        sb.append("\n⏳ 正在解析附件（").append(doneCount).append("/").append(totalCount).append("）...");
        return sb;
    }

    /**
     * 构建解析完成的确定性结果摘要（用户可见）
     */
    private StringBuilder buildParseSummaryMsg(List<ChatMessage.Attachment> filtered,
            List<com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult> results) {
        java.util.Map<String, com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult> map = new java.util.HashMap<>();
        for (com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult r : results) {
            map.put(r.attachmentId, r);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📎 附件解析结果\n\n");
        for (ChatMessage.Attachment att : filtered) {
            com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult r = map.get(att.id);
            sb.append("• ").append(att.name);
            if (r == null) {
                sb.append(" → ❓ 状态未知\n");
            } else if (r.isUsable()) {
                sb.append(" → ✅ ").append(r.status == com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseStatus.PARTIAL_SUCCESS ? "部分解析成功" : "解析成功");
                sb.append("（").append(methodLabel(r.method)).append("）");
                if (r.fromCache) sb.append("（缓存）");
                if (r.errorMessage != null) sb.append("：").append(r.errorMessage);
                sb.append("\n");
            } else {
                sb.append(" → ❌ 解析失败：").append(r.errorMessage != null ? r.errorMessage : "未知错误").append("\n");
            }
        }
        sb.append("\n✅ AI 开始分析附件内容...");
        return sb;
    }

    /**
     * 解析方式可读标签
     */
    private String methodLabel(String method) {
        if (method == null) return "未知";
        switch (method) {
            case "text_extract": return "文本提取";
            case "online_ocr": return "在线视觉模型OCR";
            case "local_ocr": return "本地OCR";
            case "pdf_pages_ocr": return "PDF逐页OCR";
            default: return method;
        }
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
                                               java.util.Map<Uri, String> localFileMap, List<String> skippedFiles,
                                               java.util.Map<String, com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult> parseResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户消息: ").append(originalMessage).append("\n\n");
        sb.append("=== 附件解析状态（系统已预处理，状态确定） ===\n\n");

        int successCount = 0;
        int idx = 1;
        for (ChatMessage.Attachment att : attachments) {
            Uri uri = Uri.parse(att.url);
            String localPath = localFileMap.get(uri);
            com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult r =
                    parseResults != null ? parseResults.get(att.id) : null;

            sb.append("【附件").append(idx).append("】").append(att.name).append("\n");
            sb.append("  类型: ").append(att.type).append("，大小: ").append(formatFileSize(att.size)).append("\n");
            if (localPath != null) {
                sb.append("  本地路径: ").append(localPath).append("\n");
            }

            if (r != null && r.isUsable()) {
                successCount++;
                sb.append("  解析状态: ").append(r.status == com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseStatus.PARTIAL_SUCCESS
                        ? "PARTIAL_SUCCESS" : "SUCCESS").append("\n");
                sb.append("  解析方式: ").append(r.method).append(r.fromCache ? "（缓存命中）" : "").append("\n");
                if (r.errorMessage != null) {
                    sb.append("  备注: ").append(r.errorMessage).append("\n");
                }
                sb.append("  内容如下:\n");
                sb.append("--- 附件内容开始 ---\n");
                String content = r.content;
                int maxLen = 12000;
                if (content.length() > maxLen) {
                    sb.append(content, 0, maxLen);
                    sb.append("\n...(内容过长已截断，完整内容共 ").append(content.length())
                      .append(" 字符，可用 file_read_lines 工具读取本地路径获取剩余部分)\n");
                } else {
                    sb.append(content).append("\n");
                }
                sb.append("--- 附件内容结束 ---\n");
            } else {
                // 失败：明确错误码与原因，禁止模型笼统说"未检测到附件"
                sb.append("  解析状态: FAILED\n");
                if (r != null) {
                    sb.append("  错误码: ").append(r.errorCode).append("\n");
                    sb.append("  失败原因: ").append(r.errorMessage).append("\n");
                    if (localPath != null) {
                        sb.append("  可选操作: 可尝试用工具重新解析（file_parse_text/ocr_recognize）\n");
                    }
                } else {
                    sb.append("  失败原因: 未获取到解析结果\n");
                }
            }
            sb.append("\n");
            idx++;
        }

        if (skippedFiles != null && !skippedFiles.isEmpty()) {
            sb.append("⚠️ 以下文件已跳过: ").append(String.join(", ", skippedFiles)).append("\n\n");
        }

        sb.append("=== 重要规则 ===\n");
        if (successCount > 0) {
            sb.append("- 以上 ").append(successCount).append(" 个附件的内容已由系统解析完成并直接提供，请基于上述内容直接回答，无需再调用解析工具\n");
        }
        sb.append("- 附件确实已上传，严禁说“未检测到附件”或要求用户重新上传\n");
        sb.append("- 若某附件解析失败，向用户说明具体失败原因（见错误码），并给出建议\n");
        sb.append("- 若内容被截断且需要完整内容，可用 file_read_lines 工具按行读取本地路径\n\n");

        sb.append("=== 备用工具（仅在内容截断或需重试时使用） ===\n");
        sb.append("调用格式: <|tool_call_begin|>app_toolkit|{\"action\": \"名称\", ...}<|tool_call_end|>\n");
        sb.append("- file_parse_text: file_path | file_read_lines: file_path, start_line, line_count\n");
        sb.append("- ocr_recognize: image_path | ocr_recognize_pdf: pdf_path\n\n");

        // 直发附件的默认占位文案已表达“分析附件”意图，不再重复注入结尾指令
        if (!DEFAULT_ATTACHMENT_MESSAGE.equals(originalMessage)) {
            sb.append("请基于附件内容回答用户问题。");
        } else {
            sb.append("开始分析。");
        }

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

    /** 直发附件（无文字输入）时的默认占位文案 */
    private static final String DEFAULT_ATTACHMENT_MESSAGE = "请分析这些附件的内容";

    private void processMessageWithAttachments(String originalMessage, List<ChatMessage.Attachment> attachments) {
        // 过滤有效附件（限制数量与大小）
        List<ChatMessage.Attachment> filtered = new ArrayList<>();
        List<String> skippedFiles = new ArrayList<>();
        for (ChatMessage.Attachment att : attachments) {
            if (filtered.size() >= MAX_CONCURRENT_ATTACHMENTS) {
                skippedFiles.add(att.name + "(超出数量限制)");
            } else if (att.size > MAX_ATTACHMENT_FILE_SIZE) {
                skippedFiles.add(att.name + "(" + formatFileSize(att.size) + ")");
            } else {
                filtered.add(att);
            }
        }

        if (!skippedFiles.isEmpty()) {
            showToast("跳过 " + skippedFiles.size() + " 个文件");
        }

        if (filtered.isEmpty()) {
            if (!originalMessage.isEmpty()) {
                processChatMessage(originalMessage);
            }
            return;
        }

        showToast("正在准备附件供AI处理...");

        List<Uri> uris = new ArrayList<>();
        for (ChatMessage.Attachment att : filtered) {
            uris.add(Uri.parse(att.url));
        }

        // 与在线 Agent 路径同一套闭环：落盘 → 预解析（缓存+错误分类）→ 确定性注入 → 调用本地模型推理
        saveAttachmentsToLocal(uris).thenAccept(localFileMap -> {
            if (isFinishing() || isDestroyed()) {
                AppLogger.w(TAG, "Activity已销毁，取消附件发送");
                return;
            }

            // 图片等附件统一走 OCR 附件预解析路径（不交给本地模型多模态推理）
            runOnUiThread(() -> {
                // 1. 展示上传状态
                StringBuilder displayMsg = new StringBuilder();
                displayMsg.append("📎 已收到 ").append(filtered.size()).append(" 个附件\n\n");
                if (!skippedFiles.isEmpty()) {
                    displayMsg.append("跳过: ").append(String.join(", ", skippedFiles)).append("\n\n");
                }
                for (ChatMessage.Attachment att : filtered) {
                    Uri uri = Uri.parse(att.url);
                    boolean saved = localFileMap.get(uri) != null;
                    displayMsg.append("• ").append(att.name)
                            .append("（").append(formatFileSize(att.size)).append("）")
                            .append(saved ? " → 正在解析..." : " → ❗保存失败")
                            .append("\n");
                }
                displayMsg.append("\n⏳ 系统正在解析附件内容，请稍候...");

                ChatMessage sysMsg = ChatMessage.createSystemMessage(
                        java.util.UUID.randomUUID().toString(),
                        displayMsg.toString(),
                        ChatMessage.SystemMessageType.INFO,
                        System.currentTimeMillis()
                );
                chatHistory.add(sysMsg);
                if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1);
                scrollToBottom();

                // 2. 异步预解析（带进度更新 + 缓存 + 错误分类）
                getAttachmentPreParser().parseAllAsync(filtered, localFileMap,
                        new com.oilquiz.app.ai.chat.input.AttachmentPreParser.ProgressCallback() {
                    @Override
                    public void onProgress(int doneCount, int totalCount, String currentFile, String stage) {
                        runOnUiThread(() -> {
                            StringBuilder sb = rebuildParseProgressMsg(filtered, localFileMap, skippedFiles,
                                    doneCount, totalCount, currentFile, stage);
                            sysMsg.content = sb.toString();
                            if (chatAdapter != null) chatAdapter.notifyItemChanged(chatHistory.indexOf(sysMsg));
                            scrollToBottom();
                        });
                    }

                    @Override
                    public void onAllCompleted(List<com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult> results) {
                        runOnUiThread(() -> {
                            // 3. 展示确定性解析结果
                            sysMsg.content = buildParseSummaryMsg(filtered, results).toString();
                            if (chatAdapter != null) chatAdapter.notifyItemChanged(chatHistory.indexOf(sysMsg));
                            scrollToBottom();
                            saveHistoryAsync();

                            // 4. 拼接解析内容，真正调用本地模型推理
                            StringBuilder allContent = new StringBuilder();
                            int successCount = 0;
                            List<String> failedDesc = new ArrayList<>();
                            for (com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult r : results) {
                                if (r.isUsable()) {
                                    successCount++;
                                    String name = getAttachmentNameById(filtered, r.attachmentId);
                                    allContent.append("=== 文件: ").append(name).append(" ===\n");
                                    allContent.append(r.content).append("\n\n");
                                } else if (r.errorMessage != null) {
                                    failedDesc.add(getAttachmentNameById(filtered, r.attachmentId)
                                            + "[" + r.errorCode + "]: " + r.errorMessage);
                                }
                            }

                            if (successCount == 0) {
                                String reason = failedDesc.isEmpty() ? "未知原因" : String.join("; ", failedDesc);
                                addSystemMessage("所有附件解析失败，无法交给AI分析：" + reason,
                                        ChatMessage.SystemMessageType.ERROR);
                                return;
                            }

                            String prompt = buildSafeAttachmentAnalysisPrompt(
                                    originalMessage, allContent.toString(), successCount);
                            if (prompt == null) {
                                addSystemMessage("构建附件分析提示词失败", ChatMessage.SystemMessageType.ERROR);
                                return;
                            }
                            AppLogger.ai(TAG, "Local model attachment analysis: " + successCount
                                    + " files parsed, prompt_len=" + prompt.length());
                            // 调用本地模型推理（不再只展示解析结果）
                            processChatMessage(prompt);
                        });
                    }
                });
            });
        });
    }

    /**
     * 根据附件 ID 查找文件名
     */
    private String getAttachmentNameById(List<ChatMessage.Attachment> attachments, String id) {
        for (ChatMessage.Attachment att : attachments) {
            if (att.id != null && att.id.equals(id)) return att.name;
        }
        return "未知文件";
    }

    /**
     * 构建安全的附件分析提示词（带长度限制和异常保护）
     */
    private String buildSafeAttachmentAnalysisPrompt(String originalMessage, String attachmentContent, int fileCount) {
        try {
            // 防崩溃：附件内容上限随当前模式的上下文窗口动态计算
            // 中文约 1 字 ≈ 1 token，预留输出与历史对话空间，附件内容最多占上下文 40%
            int contextSize = aiConfig.getContextSize();
            int contentLimit = Math.min((int) (contextSize * 0.4), 16000);
            if (attachmentContent != null && attachmentContent.length() > contentLimit) {
                AppLogger.w(TAG, "附件内容过长(" + attachmentContent.length() + " 字符, 上下文="
                        + contextSize + ")，截断到 " + contentLimit);
                attachmentContent = attachmentContent.substring(0, contentLimit)
                        + "\n...[附件内容过长已截断，请基于已提供内容分析]";
            }

            StringBuilder prompt = new StringBuilder();
            prompt.append("用户上传了 ").append(fileCount).append(" 个附件。");

            if (originalMessage != null && !originalMessage.isEmpty() && !originalMessage.equals(DEFAULT_ATTACHMENT_MESSAGE)) {
                prompt.append("用户问题：").append(originalMessage).append("\n\n");
            } else {
                prompt.append("请分析这些附件的主要内容，并提供摘要。\n\n");
            }

            prompt.append("=== 附件内容 ===\n\n");
            prompt.append(attachmentContent);
            prompt.append("\n=== 附件内容结束 ===\n\n");
            prompt.append("请根据附件内容回答。如果附件内容不足，请说明。");

            String finalPrompt = prompt.toString();

            // 最终硬保护：整个提示词不超过上下文 60%（给输出和历史留空间），防 native 层 SIGSEGV
            int hardLimit = (int) (contextSize * 0.6);
            if (finalPrompt.length() > hardLimit) {
                AppLogger.w(TAG, "提示词仍超限(" + finalPrompt.length() + "/" + hardLimit + ")，强制截断");
                finalPrompt = finalPrompt.substring(0, hardLimit)
                        + "\n...[内容过长已截断]\n请基于以上内容回答。";
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

            // Agent 模式优先：统一使用在线引擎，本地模型通过回退机制支持
            if (currentMode == ChatModeManager.ChatMode.AGENT) {
                // 不需要强制本地标志，防止残留影响后续消息
                forceLocalAgentOnce = false;
                
                if (shouldUseOnlineModel()) {
                    // 在线模型：先显示友好引导，再走ReAct
                    showOnlineAgentFriendlyGuide(message);
                } else {
                    // 本地模型：静默降级到普通对话（不显示提示）
                    processChatMessageNormal(message);
                    return;
                }
                
                // 统一走 Agent 路由（在线引擎会自动处理本地回退）
                processChatMessageWithAgent(message);
                return;
            }

            // 非 Agent 模式：检查是否应该使用在线模型（直接流式，无工具调用）
            if (shouldUseOnlineModel()) {
                processChatMessageWithOnlineModel(message);
                return;
            }

            // 其他模式：使用普通聊天
            processChatMessageNormal(message);
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error in processChatMessage: " + e.getMessage());
            endGeneration();
            addSystemMessage("处理消息时出错: " + e.getMessage());
        }
    }

    /**
     * 处理普通对话（本地模型，无 Agent 工具调用）
     * 被 processChatMessage 和 processChatMessageWithAgent（降级时）调用
     */
    private void processChatMessageNormal(String message) {
        if (aiService == null) { addSystemMessage("未选择本地模型，请切换到在线模型"); return; }
        
        synchronized (streamingLock) {
            if (isGenerating) {
                AppLogger.aiW(TAG, "processChatMessageNormal skipped, already generating");
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
            resetStreamingTts();
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

        AppLogger.ai(TAG, "Bridge sendMessage: promptLen=" + prompt.length() + ", maxTokens=" + actualMaxTokens + ", thinking=" + enableThinking);
        modelBridge.execute(ChatCommand.sendMessage(streamingId, prompt, actualMaxTokens, enableThinking),
            createBridgeCallback(streamingIndex, streamingId));
    }

    /**
     * 强制走本地 Agent 流程（用户点击拦截提示中的"🚀 强行使用本地Agent"触发）。
     * 可重复调用：每次点击都会把原始问题重新发送到本地 Agent 引擎执行。
     */
    private void forceRunLocalAgent(String message) {
        addSystemMessage("🚫 本地 Agent 已禁用\n\n请使用在线模型体验完整的 Agent 功能。\n\n切换方式：菜单 → 模型设置 → 选择在线模型", ChatMessage.SystemMessageType.WARNING);
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

            // 检测是否使用在线模型
            boolean useOnlineModel = inferenceRouter != null && inferenceRouter.isUsingOnlineModel();
            
            // 本地模型 Agent 模式 → 静默降级到普通对话
            if (!useOnlineModel) {
                AppLogger.aiW(TAG, "Agent mode with local model → downgrade to normal chat");
                processChatMessageNormal(message);
                return;
            }

            // 显示 Agent 模式激活提示
            addSystemMessage("🤖 Agent模式已激活，正在处理您的请求...");

            // 在线模型模式：无需等待本地模型初始化（本地/在线已解绑，避免"AI服务初始化"提示与无限等待拖慢进程）
            // 已确认 useOnlineModel=true 才走到这里

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
                resetStreamingTts();
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

            // Agent 执行组 ID（用于标记过程消息；执行过程已由独立面板展示，
            // 不再创建"🤖 Agent执行过程"组 header 系统消息，避免消息流冗余）
            currentAgentGroupId = java.util.UUID.randomUUID().toString();
            agentGroupStepCount = 0;
            agentGroupToolCount = 0;

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
                resetStreamingTts();

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
                            // 重置在线统计字段
                            onlinePromptTokens = 0;
                            onlineCompletionTokens = 0;
                            onlineStatsReceiveTime = 0L;
                            runOnUiThread(() -> updateInferencePhase(streamingIndex, ChatMessage.InferencePhase.ENCODING, "云端模型正在思考..."));
                        }

                        @Override
                        public void onTokenStats(int promptTokens, int completionTokens) {
                            // 接收在线模型 API 返回的 Token 统计
                            // 实现"自动数据源切换"：API 数据优先于本地估算
                            onlinePromptTokens = promptTokens;
                            onlineCompletionTokens = completionTokens;
                            onlineStatsReceiveTime = System.currentTimeMillis();
                            AppLogger.ai(TAG, "Online API token stats: prompt=" + promptTokens
                                    + ", completion=" + completionTokens);
                            runOnUiThread(() -> {
                                // 优先使用 API 返回的 completion tokens，并基于耗时计算速度
                                long elapsedMs = System.currentTimeMillis() - chatStartTime;
                                float apiTps = (elapsedMs > 0 && completionTokens > 0)
                                        ? (completionTokens * 1000.0f) / elapsedMs : 0f;
                                updateStreamingTokenStats(completionTokens, apiTps);
                            });
                        }

                        @Override
                        public void onToken(String token) {
                            synchronized (streamingLock) {
                                if (currentStreamingContent != null) {
                                    currentStreamingContent.append(token);
                                }
                            }
                            feedStreamingTts(token);
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

                                    long elapsedMs = System.currentTimeMillis() - chatStartTime;
                                    // 数据源自动切换：API 统计优先
                                    int finalCompletionTokens = onlineCompletionTokens > 0
                                            ? onlineCompletionTokens
                                            : (fullText != null ? fullText.length() / 4 : 0);
                                    int finalPromptTokens = onlinePromptTokens > 0
                                            ? onlinePromptTokens
                                            : (prompt != null ? prompt.length() / 4 : 0);

                                    if (finalCompletionTokens > 0) {
                                        msg.tokensGenerated = finalCompletionTokens;
                                    }
                                    if (elapsedMs > 0) {
                                        msg.generationTimeMs = elapsedMs;
                                    }

                                    if (chatAdapter != null) {
                                        chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
                                        if (finalCompletionTokens > 0 && elapsedMs > 0) {
                                            chatAdapter.updateMessageGenerationStats(
                                                    currentStreamingMessageIndex, finalCompletionTokens, elapsedMs);
                                        }
                                    }
                                    saveHistoryAsync();
                                    scrollToBottom();

                                    // 自动语音合成：在线回复完成后自动朗读（流式已朗读则冲刷收尾）
                                    finishAutoSpeak(msg);

                                    // 在线模式直接使用 API 返回的 token 统计累加到 session
                                    if (finalCompletionTokens > 0 || finalPromptTokens > 0) {
                                        TokenStatsManager.getInstance()
                                                .updateRequestStats(finalPromptTokens, finalCompletionTokens);
                                        AppLogger.ai(TAG, "Online token stats accumulated: prompt="
                                                + finalPromptTokens + ", completion=" + finalCompletionTokens);
                                    }

                                    // 更新底部 token 统计显示
                                    float finalTps = (elapsedMs > 0 && finalCompletionTokens > 0)
                                            ? (finalCompletionTokens * 1000.0f) / elapsedMs : 0f;
                                    updateStreamingTokenStats(finalCompletionTokens, finalTps);

                                    AppLogger.ai(TAG, "Online inference completed: elapsed=" + elapsedMs
                                            + "ms, completionTokens=" + finalCompletionTokens
                                            + ", promptTokens=" + finalPromptTokens);
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
                    // 优先使用 native 层统计，更准确
                    float nativeTps = LlamaHelper.getInferenceSpeed();
                    int nativeTokens = LlamaHelper.getTokenCount();
                    long elapsed = System.currentTimeMillis() - startTime[0];
                    float tps = nativeTps > 0 ? nativeTps : 
                        (elapsed > 0 ? (tokenCount[0] * 1000.0f) / elapsed : 0);
                    int tokens = nativeTokens > 0 ? nativeTokens : tokenCount[0];
                    runOnUiThread(() -> updateInferenceProgress(streamingIndex, tokens, tps));
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
                // 工具调用：创建工具卡片消息（不污染 AI 回答文本）
                runOnUiThread(() -> addToolCallMessage(toolName, args != null ? args : "{}"));
            }

            @Override
            public void onToolCallComplete(String messageId, String toolName, boolean success, String result) {
                // 工具结果：更新工具卡片（不再把结果追加进 AI 回答文本）
                runOnUiThread(() -> {
                    int pos = findLastSpecialMessage(ChatMessage.MessageType.TOOL_CALL);
                    updateToolCallResult(pos >= 0 ? pos : -1, success, result);
                });
            }

            @Override
            public void onThinkingUpdate(String messageId, int stepNumber, String stepType,
                                          String title, String content, int progress) {}
        };
    }

    /**
     * Agent 模式思考 token 直接路由到思考布局（msg.thinkingContent）。
     * 保证所有模式下思考内容都显示在思考布局中，不泄漏到主消息。
     */
    private void appendAgentThinkingToken(String token) {
        if (token == null || token.isEmpty()) return;
        if (currentStreamingMessageIndex < 0 || currentStreamingMessageIndex >= chatHistory.size()) return;
        String snapshot;
        synchronized (streamingLock) {
            if (currentThinkingContent == null) {
                currentThinkingContent = new StringBuilder();
            }
            currentThinkingContent.append(token);
            snapshot = currentThinkingContent.toString();
        }
        isInThinking = true;
        ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
        msg.thinkingContent = snapshot;
        if (chatAdapter != null) {
            chatAdapter.updateMessageThinkingContent(currentStreamingMessageIndex, snapshot);
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
                // 优先使用 native 层统计，更准确
                float nativeTps = LlamaHelper.getInferenceSpeed();
                int nativeTokens = LlamaHelper.getTokenCount();
                float tps = nativeTps > 0 ? nativeTps : 
                    (System.currentTimeMillis() - chatStartTime > 0 ? 
                     (tokenCount * 1000.0f) / (System.currentTimeMillis() - chatStartTime) : 0);
                int tokens = nativeTokens > 0 ? nativeTokens : tokenCount;
                runOnUiThread(() -> updateInferenceProgress(streamingIndex, tokens, tps));
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
                    // 工具调用：创建工具卡片消息（不污染 AI 回答文本）
                    runOnUiThread(() -> addToolCallMessage(call.name, call.arguments != null ? call.arguments : "{}"));

                    new Thread(() -> {
                        try {
                            AgentService.ToolResult result = agentService.executeTool(call);
                            boolean success = result != null && result.success;
                            String toolResultMsg = agentService.formatToolResultForContext(result);

                            runOnUiThread(() -> {
                                // 工具结果：更新工具卡片（不再追加进 AI 回答文本）
                                int pos = findLastSpecialMessage(ChatMessage.MessageType.TOOL_CALL);
                                updateToolCallResult(pos >= 0 ? pos : -1, success, toolResultMsg);
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
                String thinkingSnapshot;
                synchronized (streamingLock) {
                    thinkingSnapshot = currentThinkingContent != null ? currentThinkingContent.toString() : "";
                }
                ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                msg.thinkingContent = thinkingSnapshot;
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

        if (isInThinking) {
            String thinkingSnapshot;
            synchronized (streamingLock) {
                if (currentThinkingContent == null) return;
                currentThinkingContent.append(token);
                thinkingSnapshot = currentThinkingContent.toString();
            }
            if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                msg.thinkingContent = thinkingSnapshot;
                if (chatAdapter != null) chatAdapter.updateMessageThinkingContent(currentStreamingMessageIndex, thinkingSnapshot);
            }
            return;
        }

        synchronized (streamingLock) {
            if (currentStreamingContent != null) {
                currentStreamingContent.append(token);
                totalTokensGenerated++;
            }
        }
        feedStreamingTts(token);
        if (streamingUpdateManager != null) {
            streamingUpdateManager.addToken(token);
        } else {
            tokenCountSinceLastUpdate++;
            scheduleStreamingUpdate();
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
            final String finalContent;
            synchronized (streamingLock) {
                finalContent = currentStreamingContent != null ? currentStreamingContent.toString() : fullText;
            }
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
                // 工具侧桥接：取走本轮工具执行产生的结构化 UI 组件，**合并**到 AI 消息
                // （不覆盖执行中已插入的工具卡片组件，保证执行中与完成后渲染一致）
                java.util.List<com.oilquiz.app.ai.chat.component.ComponentData> drained =
                        com.oilquiz.app.ai.chat.component.ComponentCollector.drain();
                if (drained != null && !drained.isEmpty()) {
                    if (finalMsg.components == null) {
                        finalMsg.components = new java.util.ArrayList<>();
                    }
                    finalMsg.components.addAll(drained);
                }
                
                int finalTokenCount = tokenCount;
                // 优先使用 native 层 token 计数（仅本地模型；在线模式不调用 native，避免未初始化崩溃）
                if (!shouldUseOnlineModel()) {
                    int nativeTokens = LlamaHelper.getTokenCount();
                    if (nativeTokens > 0) {
                        finalTokenCount = nativeTokens;
                    }
                }
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

                // 设置 GPU 加速信息（仅本地模型；在线模式跳过 native 调用）
                if (!shouldUseOnlineModel()) {
                    try {
                        int gpuLayers = LlamaHelper.getGPULayers();
                        finalMsg.gpuLayers = gpuLayers;
                        finalMsg.usingGPU = gpuLayers > 0 && LlamaHelper.isGPUWorking();
                    } catch (Exception e) {
                        finalMsg.gpuLayers = 0;
                        finalMsg.usingGPU = false;
                    }
                }

                synchronized (streamingLock) {
                    if (currentThinkingContent != null && currentThinkingContent.length() > 0) {
                        finalMsg.thinkingContent = currentThinkingContent.toString();
                    }
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
                            // 仅本地模型走 native 分词；在线模型用长度估算，避免 native 崩溃
                            inputTokens = shouldUseOnlineModel()
                                    ? Math.max(1, promptText.length() / 4)
                                    : LlamaHelper.countTokens(promptText);
                        }
                    }
                    TokenStatsManager.getInstance().updateRequestStats(inputTokens, finalTokenCount);
                    AppLogger.i(TAG, "Token统计 - 输入: " + inputTokens + ", 输出: " + finalTokenCount);
                }

                if (cacheManager != null && aiConfig != null && aiConfig.isCacheEnabled() && finalContent != null && !finalContent.isEmpty()) {
                    String prompt = messageIndex >= 1 ? chatHistory.get(messageIndex - 1).content : "";
                    if (!prompt.isEmpty()) cacheManager.cacheResponse(prompt, finalContent);
                }

                // 自动语音合成：开启时 AI 回复完成后自动朗读（流式已朗读则冲刷收尾）
                finishAutoSpeak(finalMsg);
            }
            synchronized (streamingLock) {
                currentStreamingContent = null;
                currentThinkingContent = null;
            }
            isInThinking = false;
            isInTag = false;
            if (tagBuffer != null) tagBuffer.setLength(0);
            currentStreamingMessageIndex = -1;
            currentStreamingMessageId = null;
            scrollToBottom();
        });
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
            // 工具调用开始：插入式组件显示到 AI 消息内（执行中卡片）+ 状态栏更新
            runOnUiThread(() -> {
                appendAgentToolCall(toolName, "running", args, null);
                updateAgentStatusBar("🔧 调用 " + toolName + "...", true);
                addToolCallMessage(toolName, args);
                scrollToBottom();
            });
        }

        @Override
        public void onToolCallComplete(String toolName, OnlineToolResult result) {
            // 工具调用完成：更新插入式卡片为成功/失败 + 状态栏恢复
            runOnUiThread(() -> {
                boolean success = result != null && result.success;
                String resultStr = result != null ? result.result : "无结果";
                // 完成时传简略摘要（如"北京 26℃ 晴"），执行后的工具行不再空白
                appendAgentToolCall(toolName, success ? "success" : "failed", null,
                        com.oilquiz.app.ai.chat.component.ToolCallCardView.summarize(resultStr));
                updateAgentStatusBar("✅ " + toolName + " 完成", false);
                // Agent 模式：过程已插入 AI 消息组件，无独立工具卡片消息，跳过消息更新
                // （否则 findLastSpecialMessage 返回 -1 时回退到最后一条消息，结果会写到错误消息上）
                if (currentAgentGroupId == null) {
                    int pos = findLastSpecialMessage(ChatMessage.MessageType.TOOL_CALL);
                    updateToolCallResult(pos >= 0 ? pos : chatHistory.size() - 1, success, resultStr);
                }
                scrollToBottom();
            });
        }

        @Override
        public void onToken(String token) {
            // 流式 token：追加到当前流式内容（始终累积，不丢失）
            // 必须加锁：onToken 来自后台线程，safeUpdateMessage 在 UI 线程读取
            synchronized (streamingLock) {
                if (currentStreamingContent != null) {
                    currentStreamingContent.append(token);
                }
            }
            feedStreamingTts(token);
            // 流式 token 统计：实时累计 + 节流刷新状态栏（解决 tokens 显示不及时）
            streamingTokenCount++;
            long now = System.currentTimeMillis();
            if (streamingStartTime <= 0) streamingStartTime = now;
            if (now - lastTokenStatsUiUpdateTime >= 300) {
                lastTokenStatsUiUpdateTime = now;
                final long elapsed = now - streamingStartTime;
                final int tokens = (int) streamingTokenCount;
                runOnUiThread(() -> {
                    float tps = elapsed > 0 ? tokens * 1000f / elapsed : 0;
                    updateStreamingTokenStats(tokens, tps);
                });
            }
            // 节流：距上次 UI 更新 ≥ 80ms 才刷新，避免高频 token 卡顿主线程
            if (now - lastTokenUiUpdateTime >= UI_UPDATE_THROTTLE_MS) {
                lastTokenUiUpdateTime = now;
                runOnUiThread(() -> {
                    safeUpdateMessage();
                    scrollToBottom();
                });
            }
        }

        @Override
        public void onThinkingToken(String token) {
            // 思考 token：每轮创建独立的思考消息块，自由插入到agent执行流中
            // 注意：本回调已通过 OnlineAgentEngine.runOnUiThread 在UI线程调用，
            // 内部不能再 runOnUiThread（否则会post到队列，导致下一轮token先于addThinkingMessage执行）
            boolean onUi = Looper.myLooper() == Looper.getMainLooper();
            // 新一轮思考开始：状态栏显示思考中
            if (thinkingRoundEnded || currentThinkingMessageIndex < 0) {
                thinkingRoundEnded = false;
                thinkingRoundCount++;
                if (onUi) {
                    updateAgentStatusBar("🧠 思考中...", true);
                } else {
                    runOnUiThread(() -> updateAgentStatusBar("🧠 思考中...", true));
                }
                synchronized (streamingLock) {
                    currentThinkingContent = new StringBuilder();
                }
                if (onUi) {
                    addThinkingMessage(thinkingRoundCount);
                } else {
                    runOnUiThread(() -> addThinkingMessage(thinkingRoundCount));
                }
            }
            // 加锁防止与 updateThinkingMessageUi/finalizeThinkingMessage 的读取冲突
            synchronized (streamingLock) {
                if (currentThinkingContent != null) {
                    currentThinkingContent.append(token);
                }
            }
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

        @Override
        public void onThinkingEnd() {
            isInThinking = false;
            // 思考内容不再单独显示（精简视觉；Agent 过程仅工具卡片插入式展示）
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
            // 清理组ID（执行完成，不插入系统消息）
            runOnUiThread(() -> {
                // 状态栏恢复（执行完成）
                updateAgentStatusBar("✅ 执行完成", false);
                if (currentAgentGroupId != null && chatAdapter != null) {
                    chatAdapter.updateAgentGroupCounts(currentAgentGroupId, agentGroupStepCount, agentGroupToolCount);
                }
                currentAgentGroupId = null;
                scrollToBottom();
            });
        }

        @Override
        public void onError(String error) {
            if ("[TOOL_CALL]".equals(error)) return;
            runOnUiThread(() -> {
                if (currentAgentGroupId != null && chatAdapter != null) {
                    chatAdapter.updateAgentGroupCounts(currentAgentGroupId, agentGroupStepCount, agentGroupToolCount);
                }
                currentAgentGroupId = null;
                handleGenerationError("Agent出错: " + error);
            });
        }

        @Override
        public void onModeSwitched(String mode) {
        }

        @Override
        public void onAgentStep(ChatMessage.AgentStepInfo stepInfo) {
            // Agent 步骤：执行过程已由独立 Agent 面板展示，消息流不再插入步骤消息（精简视觉）
        }

        @Override
        public void onToolCallUI(String toolName, String args, int position) {
            // 工具调用 UI：添加工具调用消息（Agent 模式下过程已由独立面板展示，跳过）
            runOnUiThread(() -> {
                addToolCallMessage(toolName, args);
                scrollToBottom();
            });
        }

        @Override
        public void onToolCallResultUI(int position, boolean success, String result) {
            // 工具调用结果 UI：更新工具调用结果（Agent 模式下跳过，避免回退写错消息）
            runOnUiThread(() -> {
                if (currentAgentGroupId != null) return;
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
        // 重置流式 token 统计
        streamingTokenCount = 0;
        streamingStartTime = 0;
        lastTokenStatsUiUpdateTime = 0;
        
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

        toggleStopButton(true);
        if (serviceStatusManager != null) serviceStatusManager.showThinkingIndicator();

        // 显示 Token 统计
        showTokenStats(true);
    }

    private void endGeneration() {
        isGenerating = false;
        isDirectStreaming = false;

        // 清理Agent执行组
        if (currentAgentGroupId != null && chatAdapter != null) {
            chatAdapter.updateAgentGroupCounts(currentAgentGroupId, agentGroupStepCount, agentGroupToolCount);
        }
        currentAgentGroupId = null;

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
            if (currentStreamingMessageIndex < 0 || currentStreamingMessageIndex >= chatHistory.size()) return;
            // 加锁快照：防止与 onToken 的 append 并发导致 StringBuilder 内部数据错乱
            String contentSnapshot;
            String thinkingSnapshot = null;
            synchronized (streamingLock) {
                if (currentStreamingContent == null) return;
                contentSnapshot = currentStreamingContent.toString();
                if (currentThinkingContent != null && currentThinkingContent.length() > 0) {
                    thinkingSnapshot = currentThinkingContent.toString();
                }
            }
            ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
            // 节流：内容与思考均无变化时跳过刷新，避免重复渲染相同内容
            boolean thinkingSame = (thinkingSnapshot == null)
                    || (msg.thinkingContent != null && thinkingSnapshot.equals(msg.thinkingContent));
            if (contentSnapshot.equals(msg.content) && thinkingSame) {
                return;
            }
            msg.content = contentSnapshot;
            msg.status = ChatMessage.MessageStatus.GENERATING;
            if (thinkingSnapshot != null) msg.thinkingContent = thinkingSnapshot;
            if (chatAdapter != null) chatAdapter.updateAIMessageContent(currentStreamingMessageIndex, contentSnapshot);
        } catch (IndexOutOfBoundsException e) { currentStreamingMessageIndex = -1; }
    }

    private void safeUpdateMessageFromStreamingManager(String accumulatedContent, int tokensSinceLastUpdate) {
        try {
            if (currentStreamingMessageIndex < 0 || currentStreamingMessageIndex >= chatHistory.size()) return;
            // 加锁快照
            String contentSnapshot;
            String thinkingSnapshot = null;
            synchronized (streamingLock) {
                if (currentStreamingContent == null) return;
                contentSnapshot = currentStreamingContent.toString();
                if (currentThinkingContent != null && currentThinkingContent.length() > 0) {
                    thinkingSnapshot = currentThinkingContent.toString();
                }
            }
            ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
            if (msg != null) {
                // 节流：内容与思考均无变化时跳过刷新
                boolean thinkingSame = (thinkingSnapshot == null)
                        || (msg.thinkingContent != null && thinkingSnapshot.equals(msg.thinkingContent));
                if (contentSnapshot.equals(msg.content) && thinkingSame) {
                    return;
                }
                msg.status = ChatMessage.MessageStatus.GENERATING;
                msg.content = contentSnapshot;
                if (thinkingSnapshot != null) msg.thinkingContent = thinkingSnapshot;
                if (chatAdapter != null) {
                    chatAdapter.updateAIMessageContent(currentStreamingMessageIndex, contentSnapshot);
                }
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
            final String existingId = currentSessionId;
            new Thread(() -> {
                chatHistoryManager.saveAIChatHistory(copy);
                // 同步保存为会话（确保历史不丢失）
                if (copy.size() >= 2) {
                    ConversationSession session = chatHistoryManager.saveCurrentChatAsSession(copy, existingId);
                    // 保存后更新 currentSessionId，下次更新同一文件而非重复创建
                    if (session != null && session.id != null) {
                        currentSessionId = session.id;
                    }
                }
            }).start();
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
                String modelName = "";
                if (inferenceRouter != null) {
                    modelName = inferenceRouter.getCurrentModelName();
                }
                String display = "☁️ " + (modelName != null && !modelName.isEmpty() ? modelName : "在线模型");
                modelNameText.setText(display);
                // 异步查询 API 余额并显示（仅 DeepSeek 官方 API 支持）
                OnlineModelManager.OnlineModelConfig active =
                        onlineModelManager != null ? onlineModelManager.getActiveModel() : null;
                if (active != null) {
                    com.oilquiz.app.ai.util.ApiBalanceChecker.checkAsync(active, (balance, error) ->
                            runOnUiThread(() -> {
                                if (modelNameText != null && balance != null && !balance.isEmpty()) {
                                    modelNameText.setText(display + " · " + balance);
                                }
                            }));
                }
            } else if (aiService != null) {
                String name = modelBridge != null ? modelBridge.getCurrentModelName() : "";
                modelNameText.setText("📱 " + (name != null && !name.isEmpty() ? name : "未选择模型"));
            } else {
                // 本地服务未初始化 ≠ "AI服务未初始化"：可能只是未选择模型，避免误导（在线模型仍可用）
                modelNameText.setText("未选择模型");
            }
        } catch (Exception e) {
            AppLogger.aiW(TAG, "Error updating model name display: " + e.getMessage());
            modelNameText.setText("模型加载中...");
        }
    }

    /**
     * 注册在线模型变更监听，模型切换时自动刷新名称显示
     */
    private void registerModelChangeListener() {
        if (onlineModelManager == null) return;
        modelChangeListener = new OnlineModelManager.ModelChangeListener() {
            @Override
            public void onModelListChanged() {
                // 模型列表变化时刷新名称显示
                runOnUiThread(() -> updateModelNameDisplay());
            }
            @Override
            public void onActiveModelChanged(String activeModelId) {
                // 激活模型变化时立即刷新名称和模式按钮
                runOnUiThread(() -> {
                    updateModelNameDisplay();
                    updateModeButtonText();
                });
            }
        };
        onlineModelManager.addListener(modelChangeListener);
    }

    /**
     * 注销在线模型变更监听，避免内存泄漏
     */
    private void unregisterModelChangeListener() {
        if (onlineModelManager != null && modelChangeListener != null) {
            onlineModelManager.removeListener(modelChangeListener);
            modelChangeListener = null;
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
            if (chatHistoryManager != null) {
                new Thread(() -> {
                    chatHistoryManager.clearAIChatHistory();
                    chatHistoryManager.clearAllConversationSessions();
                    runOnUiThread(this::refreshHistoryDrawer);
                }).start();
            }
            if (modelBridge != null) modelBridge.execute(ChatCommand.clearContext(), null);
            // 清空在线 Agent 引擎的对话历史，确保下次是全新对话
            if (agentChatHandler != null) agentChatHandler.clearHistory();
            clearStreamingState();
            endGeneration();
            updateEmptyState();
            // 重置 Token 统计
            TokenStatsManager.getInstance().resetSession();
            updateTokenStatsUI(TokenStatsManager.getInstance().getCurrentSnapshot());
        } catch (Exception e) { AppLogger.aiE(TAG, "Error clearing chat: " + e.getMessage()); }
    }

    /**
     * 开始新对话：先自动保存当前会话到历史，再清空上下文。
     */
    private void startNewConversation() {
        try {
            if (isGenerating) {
                if (agentChatHandler != null && agentChatHandler.isGenerating()) agentChatHandler.cancel();
                if (modelBridge != null) modelBridge.execute(ChatCommand.stopGeneration(), null);
                clearStreamingState();
                endGeneration();
            }
            // 自动保存当前会话到历史
            if (chatHistoryManager != null && !chatHistory.isEmpty()) {
                new Thread(() -> {
                    chatHistoryManager.saveCurrentChatAsSession(chatHistory);
                    runOnUiThread(this::refreshHistoryDrawer);
                }).start();
            }
            // 清空当前对话上下文和页面消息
            currentSessionId = null; // 重置会话 ID，下次保存时创建新会话
            chatHistory.clear();
            if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
            if (chatHistoryManager != null) new Thread(() -> chatHistoryManager.clearAIChatHistory()).start();
            if (modelBridge != null) modelBridge.execute(ChatCommand.clearContext(), null);
            if (agentChatHandler != null) agentChatHandler.clearHistory();
            updateEmptyState();
            AILogger.i(TAG, "New conversation started: current session saved, context cleared");
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error starting new conversation: " + e.getMessage());
        }
    }

    /**
     * 切换到指定历史会话：先保存当前会话，再加载目标会话。
     */
    private void switchToSession(ConversationSession session) {
        if (session == null || session.id == null) return;
        try {
            // 先保存当前会话
            if (chatHistoryManager != null && !chatHistory.isEmpty()) {
                chatHistoryManager.saveCurrentChatAsSession(chatHistory);
            }
            // 异步加载目标会话
            new Thread(() -> {
                ConversationSession loaded = chatHistoryManager.loadConversationSession(session.id);
                if (loaded != null && loaded.messages != null && !loaded.messages.isEmpty()) {
                    runOnUiThread(() -> {
                        // 停止当前生成
                        if (isGenerating) {
                            if (agentChatHandler != null && agentChatHandler.isGenerating()) agentChatHandler.cancel();
                            if (modelBridge != null) modelBridge.execute(ChatCommand.stopGeneration(), null);
                            clearStreamingState();
                            endGeneration();
                        }
                        // 替换当前聊天历史
                        currentSessionId = loaded.id; // 跟踪当前加载的会话 ID
                        chatHistory.clear();
                        chatHistory.addAll(loaded.messages);
                        if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                        // 保存到单文件历史（兼容现有逻辑）
                        chatHistoryManager.saveAIChatHistory(new ArrayList<>(chatHistory));
                        // 清空模型上下文，让新会话从头开始
                        if (modelBridge != null) modelBridge.execute(ChatCommand.clearContext(), null);
                        if (agentChatHandler != null) agentChatHandler.clearHistory();
                        updateEmptyState();
                        scrollToBottom(true);
                        showToast("已切换到: " + loaded.title);
                    });
                } else {
                    runOnUiThread(() -> showToast("加载会话失败"));
                }
            }).start();
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error switching session: " + e.getMessage());
        }
    }

    /**
     * 删除指定的历史会话。
     */
    private void deleteSession(ConversationSession session) {
        if (session == null || session.id == null) return;
        new Thread(() -> {
            chatHistoryManager.deleteConversationSession(session.id);
            runOnUiThread(() -> {
                refreshHistoryDrawer();
                showToast("已删除");
            });
        }).start();
    }

    /**
     * 刷新历史抽屉：从持久化存储加载会话列表。
     */
    private void refreshHistoryDrawer() {
        if (historyController == null || chatHistoryManager == null) return;
        new Thread(() -> {
            List<ConversationSession> sessions = chatHistoryManager.listConversationSessions();
            runOnUiThread(() -> historyController.refresh(sessions));
        }).start();
    }

    /**
     * 重新生成：找到指定 AI 消息前面的最后一条用户消息，删除该 AI 消息（及其后的所有非用户消息），
     * 然后重新发送该用户消息。
     */
    private void regenerateMessage(String aiMessageId) {
        if (isGenerating) { showToast("AI正在生成中，请稍候"); return; }
        try {
            // 1. 找到目标 AI 消息的索引
            int aiIndex = -1;
            for (int i = 0; i < chatHistory.size(); i++) {
                ChatMessage m = chatHistory.get(i);
                if (m != null && aiMessageId.equals(m.id)) { aiIndex = i; break; }
            }
            if (aiIndex < 0) { showToast("未找到对应的消息"); return; }

            // 2. 向前找最后一条用户消息
            String userContent = null;
            int userIndex = -1;
            for (int i = aiIndex - 1; i >= 0; i--) {
                ChatMessage m = chatHistory.get(i);
                if (m != null && m.type == ChatMessage.MessageType.USER && m.content != null) {
                    userContent = m.content;
                    userIndex = i;
                    break;
                }
            }
            if (userContent == null) { showToast("未找到对应的用户消息"); return; }

            // 3. 删除 userIndex 之后的所有消息（保留用户消息本身）
            int removeStart = userIndex + 1;
            for (int i = chatHistory.size() - 1; i >= removeStart; i--) {
                chatHistory.remove(i);
            }
            if (chatAdapter != null) chatAdapter.notifyDataSetChanged();

            // 4. 清空上下文并重新发送用户消息
            if (modelBridge != null) modelBridge.execute(ChatCommand.clearContext(), null);
            if (agentChatHandler != null) agentChatHandler.clearHistory();

            // 重新生成时失效缓存，确保重新推理而非返回缓存结果
            if (cacheManager != null) cacheManager.invalidateCache(userContent);

            addUserMessage(userContent);
            processChatMessage(userContent);
            AILogger.i(TAG, "Regenerating from user message at index " + userIndex);
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error regenerating message: " + e.getMessage());
            showToast("重新生成失败");
        }
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
     *
     * 数据源自动切换逻辑：
     *   - 在线模式：优先使用 API 返回的 onTokenStats（onlineCompletionTokens）
     *   - 本地模式：优先使用 native 层 LlamaHelper.getInferenceSpeed() / getTokenCount()
     *   - 回退：使用传入的估算值（基于本地累计 / 耗时计算）
     */
    private void updateStreamingTokenStats(int totalTokens, float tokensPerSecond) {
        TextView tvTokenStats = findViewById(R.id.tv_token_stats);
        if (tvTokenStats != null) {
            tvTokenStats.setVisibility(View.VISIBLE);

            boolean useOnline = inferenceRouter != null && inferenceRouter.isUsingOnlineModel();
            float displayTps;
            int displayTokens;
            String sourceTag;

            if (useOnline && onlineCompletionTokens > 0) {
                // 在线模式 + API 统计可用：使用 API 返回的 completion tokens
                displayTokens = onlineCompletionTokens;
                displayTps = tokensPerSecond; // 已基于 API completion tokens 计算的速度
                sourceTag = "🌐"; // 云端 API
            } else if (useOnline) {
                // 在线模式但 API 未返回统计：用估算值（不调用 native，避免未初始化崩溃）
                displayTokens = totalTokens > 0 ? totalTokens : (int) streamingTokenCount;
                displayTps = tokensPerSecond;
                sourceTag = "🌐";
            } else {
                // 本地模式：优先使用 native 层
                float nativeTps = LlamaHelper.getInferenceSpeed();
                int nativeTokens = LlamaHelper.getTokenCount();
                displayTps = nativeTps > 0 ? nativeTps : tokensPerSecond;
                displayTokens = nativeTokens > 0 ? nativeTokens : totalTokens;
                sourceTag = "⚡"; // 本地 native
            }

            if (isGenerating && displayTps > 0) {
                String statsText = String.format("%s %.1f t/s | %d tokens", sourceTag, displayTps, displayTokens);
                tvTokenStats.setText(statsText);
            } else {
                String statsText = String.format("✅ %d tokens", displayTokens);
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
        // 重新生成时失效缓存，确保重新推理
        if (cacheManager != null) cacheManager.invalidateCache(lastUserMsg);
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
        toggleStopButton(false);
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
        if (aiService == null) { showToast("未选择本地模型，请切换到在线模型"); return false; }
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

    /**
     * 将工具结果的 AI 解读注入本地模型的主对话上下文（native chatMessages）。
     * 本地多轮历史由 native KV 侧维护而非 chatHistory，若不注入，
     * 用户追问时本地模型看不到解读内容，无法连续对话。
     * 仅在当前使用本地模型且主上下文激活时执行；在线模型不需要（history 由 chatHistory 重建）。
     */
    private void injectInterpretToLocalContext(String interpretation) {
        try {
            if (interpretation == null || interpretation.isEmpty()) return;
            if (shouldUseOnlineModel()) return;
            if (!com.oilquiz.app.ai.jni.LlamaHelper.isChatContextActive()) return;
            boolean ok = com.oilquiz.app.ai.jni.LlamaHelper.chatAddAssistant(interpretation);
            AppLogger.ai(TAG, "Inject interpretation to local context: " + ok + ", len=" + interpretation.length());
        } catch (Throwable t) {
            AppLogger.aiE(TAG, "injectInterpretToLocalContext error: " + t.getMessage());
        }
    }

    /**
     * 更新指定位置系统消息的文案（用于把"正在解读"进度提示改为终态）。
     * 位置无效或该位置已不是系统消息（可能因后续插入发生偏移）时静默跳过。
     */
    private void updateSystemMessageText(int pos, String text) {
        try {
            if (chatHistory == null || pos < 0 || pos >= chatHistory.size()) return;
            ChatMessage msg = chatHistory.get(pos);
            if (msg == null || msg.type != ChatMessage.MessageType.SYSTEM) return;
            msg.content = text;
            if (chatAdapter != null) chatAdapter.notifyItemChanged(pos);
        } catch (Throwable ignore) { }
    }

    /**
     * 拉取引导步骤的动态选项列表（后台线程调用）。
     * 执行 spec 声明的工具 action，从结果 Map 的 listKey 中提取字符串列表；
     * 元素为 Map 时取 itemField 字段。spec 声明了 allOptionLabel 时列表顶部加空值"全部"项。
     * 任何失败返回 null，调用方退化为纯手动输入。
     */
    private java.util.List<String> loadGuideDynamicOptions(ToolGuideFlow.GuideStep.DynamicOptionsSpec spec) {
        try {
            if (spec == null || spec.toolName == null || spec.action == null || spec.listKey == null) return null;
            Map<String, Object> params = new HashMap<>();
            params.put("action", spec.action);
            AIToolResult result = AIToolManager.getInstance(this).executeTool(spec.toolName, params);
            if (result == null || !result.isSuccess()) return null;
            Object raw = result.getResult();
            if (!(raw instanceof Map)) return null;
            Object listObj = ((Map<?, ?>) raw).get(spec.listKey);
            if (!(listObj instanceof List)) return null;

            java.util.List<String> values = new ArrayList<>();
            if (spec.allOptionLabel != null) values.add(""); // 空值 = "全部/不限"，CONFIRM 收集时自动跳过空值
            for (Object item : (List<?>) listObj) {
                String v = null;
                if (item instanceof Map && spec.itemField != null) {
                    Object f = ((Map<?, ?>) item).get(spec.itemField);
                    v = f != null ? f.toString() : null;
                } else if (item != null) {
                    v = item.toString();
                }
                if (v != null && !v.trim().isEmpty()) values.add(v.trim());
            }
            return values;
        } catch (Throwable t) {
            AppLogger.aiE(TAG, "loadGuideDynamicOptions error: " + t.getMessage());
            return null;
        }
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
        // Agent 模式：思考过程已由独立 Agent 面板展示，消息流不再插入思考消息（避免双通道渲染）
        if (currentAgentGroupId != null) return -1;

        ChatMessage msg = ChatMessage.createThinkingRoundMessage(round);
        // 标记所属agent组
        if (currentAgentGroupId != null) {
            msg.agentGroupId = currentAgentGroupId;
        }
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
            // 加锁快照：防止与 onThinkingToken 的 append 并发
            String thinkingSnapshot;
            synchronized (streamingLock) {
                thinkingSnapshot = currentThinkingContent != null ? currentThinkingContent.toString() : "";
            }
            ChatMessage msg = chatHistory.get(currentThinkingMessageIndex);
            msg.thinkingContent = thinkingSnapshot;
            if (chatAdapter != null) {
                chatAdapter.updateMessageThinkingContent(currentThinkingMessageIndex, thinkingSnapshot);
            }
        }
    }

    /** 完成当前思考消息：设置最终内容、标记完成、折叠 */
    private void finalizeThinkingMessage() {
        if (currentThinkingMessageIndex < 0 || currentThinkingMessageIndex >= chatHistory.size() || chatAdapter == null) {
            currentThinkingMessageIndex = -1;
            return;
        }
        String thinkingSnapshot;
        synchronized (streamingLock) {
            thinkingSnapshot = currentThinkingContent != null ? currentThinkingContent.toString() : "";
        }
        ChatMessage msg = chatHistory.get(currentThinkingMessageIndex);
        msg.thinkingContent = thinkingSnapshot;
        msg.status = ChatMessage.MessageStatus.COMPLETED;
        msg.thinkingExpanded = false;
        chatAdapter.updateMessageThinkingContent(currentThinkingMessageIndex, thinkingSnapshot);
        chatAdapter.notifyItemChanged(currentThinkingMessageIndex);
        // 重置思考消息索引，下轮创建新消息
        currentThinkingMessageIndex = -1;
    }

    private int addToolCallMessage(String toolName, String parameters) {
        if (chatHistory == null) return -1;
        // Agent 模式：执行过程已由独立 Agent 面板展示，消息流不再插入工具卡片（避免双通道渲染）
        if (currentAgentGroupId != null) return -1;

        ChatMessage msg = ChatMessage.createToolCallMessage(toolName, parameters);
        // 标记所属agent组
        if (currentAgentGroupId != null) {
            msg.agentGroupId = currentAgentGroupId;
            agentGroupToolCount++;
            chatAdapter.updateAgentGroupCounts(currentAgentGroupId, agentGroupStepCount, agentGroupToolCount);
        }
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
        // 标记所属agent组
        if (currentAgentGroupId != null) {
            msg.agentGroupId = currentAgentGroupId;
            agentGroupStepCount++;
            chatAdapter.updateAgentGroupCounts(currentAgentGroupId, agentGroupStepCount, agentGroupToolCount);
        }
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

    /**
     * 将自动解读的自然语言摘要写入对应 TOOL_CALL 消息的 toolCallInfo.interpretedMessage 字段，
     * 不再新增独立 AI 主气泡；通过 notifyItemChanged 触发 RecyclerView 局部刷新。
     */
    private void writeInterpretedToMessage(final int position, final String content) {
        if (position < 0 || chatHistory == null || position >= chatHistory.size()) return;
        ChatMessage msg = chatHistory.get(position);
        if (msg == null || msg.toolCallInfo == null) return;
        msg.toolCallInfo.interpretedMessage = (content != null) ? content : "";
        msg.toolCallInfo.interpretationDone = true;
        if (chatAdapter != null) {
            // 使用 RecyclerView 三层保护：主线程检查 + 布局动画检查 + post 延迟
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                if (messageList != null && messageList.isComputingLayout()) {
                    messageList.post(() -> {
                        if (chatAdapter != null && position < chatAdapter.getItemCount()) {
                            chatAdapter.notifyItemChanged(position);
                        }
                    });
                } else {
                    chatAdapter.notifyItemChanged(position);
                }
            } else {
                runOnUiThread(() -> {
                    if (chatAdapter != null && position < chatAdapter.getItemCount()) {
                        if (messageList != null && messageList.isComputingLayout()) {
                            messageList.post(() -> chatAdapter.notifyItemChanged(position));
                        } else {
                            chatAdapter.notifyItemChanged(position);
                        }
                    }
                });
            }
        }
        saveHistoryAsync();
    }

    private void updateAgentStepResult(int position, String thought, String action, String observation, boolean isCompleted) {
        if (chatAdapter != null && position >= 0 && position < chatHistory.size()) {
            chatAdapter.updateAgentStep(position, thought, action, observation, isCompleted);
        }
    }

    private volatile boolean scrollPending = false;

    private void scrollToBottom() {
        scrollToBottom(false);
    }

    private void scrollToBottom(boolean force) {
        if (messageList == null || chatAdapter == null || chatAdapter.getItemCount() == 0) return;
        if (!force && !isUserAtBottom()) return;
        // 防抖：已有滚动任务在队列中时不再重复 post，避免高频 token 刷新堆积大量滚动任务卡住主线程
        if (!force && scrollPending) return;
        scrollPending = true;
        int lastPosition = chatAdapter.getItemCount() - 1;
        messageList.post(() -> {
            scrollPending = false;
            boolean isStreaming;
            synchronized (streamingLock) {
                isStreaming = isInThinking || (currentStreamingContent != null && currentStreamingContent.length() > 0);
            }
            if (isStreaming) {
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
     * 设置键盘弹出时自动滚动到底部，并确保输入框可见
     * 键盘弹出时自动折叠快捷工具栏，避免挤压输入框
     */
    private void setupKeyboardListener() {
        final android.view.View rootView = findViewById(android.R.id.content);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            android.graphics.Rect r = new android.graphics.Rect();
            rootView.getWindowVisibleDisplayFrame(r);
            int screenHeight = rootView.getRootView().getHeight();
            int keypadHeight = screenHeight - r.bottom;
            if (keypadHeight > screenHeight * 0.15) {
                // 键盘弹出：自动折叠快捷工具栏，为输入框腾出空间
                if (quickBarExpanded && quickActionsChipGroup != null && quickActionsChipGroup.getVisibility() == View.VISIBLE) {
                    quickBarExpanded = false;
                    keyboardAutoCollapsed = true; // 标记为键盘自动折叠
                    quickActionsChipGroup.setVisibility(View.GONE);
                    if (ivQuickExpand != null) ivQuickExpand.setImageResource(R.drawable.ic_expand);
                }
                // 滚动到底部
                scrollToBottom();
                // 确保输入框区域可见：延迟等待布局稳定后滚动
                if (inputMessage != null) {
                    inputMessage.postDelayed(() -> {
                        inputMessage.requestFocus();
                        scrollToBottom();
                    }, 100);
                }
            } else {
                // 键盘隐藏：仅恢复被键盘自动折叠的工具栏，不影响用户手动折叠的状态
                if (keyboardAutoCollapsed && quickActionsChipGroup != null) {
                    keyboardAutoCollapsed = false;
                    quickBarExpanded = true;
                    quickActionsChipGroup.setVisibility(View.VISIBLE);
                    if (ivQuickExpand != null) ivQuickExpand.setImageResource(R.drawable.ic_collapse);
                }
            }
        });
    }

    private void handleAction(ChatMessage.Action action) {
        // 处理"强行使用本地Agent"按钮：优先处理，避免被 dialogHelper 当作未知 Action
        if (action != null && action.type == ChatMessage.ActionType.FORCE_LOCAL_AGENT) {
            forceRunLocalAgent(action.content);
            return;
        }
        // 处理 AI 深度解读按钮：优先于 dialogHelper，避免被 dialogHelper 当作未知 Action 吞掉
        if (action != null && action.type == ChatMessage.ActionType.AI_INTERPRET_RESULT) {
            handleAiInterpretAction(action);
            return;
        }
        // 处理 AI 消息"朗读"按钮：再次点击同一消息则停止朗读
        if (action != null && action.type == ChatMessage.ActionType.SPEAK) {
            handleSpeakAction(action);
            return;
        }
        if (dialogHelper != null) dialogHelper.handleAction(action, chatHistory);
    }

    /**
     * 用户点击工具结果气泡中的"AI深度解读"按钮时触发。
     * 两级兜底（在线→本地 LLM），均不可用时提示用户。
     * 任何 Throwable 均被捕获以确保 LLM 异常不导致功能崩溃。
     */
    private void handleAiInterpretAction(ChatMessage.Action action) {
        try {
            // 1) 根据 messageId 找到对应 tool call 消息
            ChatMessage target = null;
            for (ChatMessage m : chatHistory) {
                if (m != null && m.id != null && m.id.equals(action.messageId)) {
                    target = m;
                    break;
                }
            }
            if (target == null || target.toolCallInfo == null) {
                addSystemMessage("⚠️ 未找到对应的工具调用结果");
                scrollToBottom();
                return;
            }
            final ChatMessage.ToolCallInfo info = target.toolCallInfo;
            final String toolName = info.toolName;
            final Object rawResult = info.rawResult;
            if (toolName == null || rawResult == null) {
                addSystemMessage("⚠️ 工具结果为空，无法解读");
                scrollToBottom();
                return;
            }
            // 直接触发 LLM 异步解读（内部在线→本地→null 三级兜底，在后台线程执行）
            // 不在主线程调用 isAnyModelAvailable，避免可能的阻塞
            addSystemMessage("💡 正在用AI解读结果...");
            scrollToBottom();
            ToolResultInterpreter.interpret(this, toolName, rawResult,
                new ToolResultInterpreter.InterpretCallback() {
                    @Override
                    public void onInterpreted(String summary) {
                        try {
                            if (summary != null && !summary.isEmpty()) {
                                addAIMessage(summary);
                                scrollToBottom();
                            } else {
                                // 两级 LLM 均无法解读时，不影响用户体验
                                addSystemMessage("ℹ️ 当前模型暂时无法解读此结果，可稍后再试");
                                scrollToBottom();
                            }
                        } catch (Exception e) {
                            android.util.Log.w("AIChatActivity", "解读回调UI异常: " + e.getMessage());
                        }
                    }
                    @Override
                    public void onError(String error) {
                        try {
                            addSystemMessage("ℹ️ 解读遇到问题，可稍后再试");
                            scrollToBottom();
                        } catch (Exception ignore) { }
                    }
                });
        } catch (Throwable t) {
            android.util.Log.e("AIChatActivity", "handleAiInterpretAction 异常: " + t.getMessage(), t);
            try {
                addSystemMessage("⚠️ 解读请求失败，请重试");
                scrollToBottom();
            } catch (Exception ignore) { }
        }
    }



    // ===================== UI Init =====================

    private void openUri(String url) {
        if (url != null) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); } catch (Exception e) { showToast("无法打开"); } }
    }

    /** 应用内图片预览（PhotoView 双指缩放，点击关闭）——避免依赖系统图片查看器 */
    private void showImagePreview(String url) {
        try {
            if (url == null || url.isEmpty()) return;
            android.app.Dialog dialog = new android.app.Dialog(this);
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

            android.widget.FrameLayout root = new android.widget.FrameLayout(this);
            root.setBackgroundColor(android.graphics.Color.BLACK);

            com.github.chrisbanes.photoview.PhotoView photoView = new com.github.chrisbanes.photoview.PhotoView(this);
            photoView.setBackgroundColor(android.graphics.Color.BLACK);
            root.addView(photoView, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

            android.widget.ProgressBar loading = new android.widget.ProgressBar(this);
            android.widget.FrameLayout.LayoutParams loadingLp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER);
            root.addView(loading, loadingLp);

            dialog.setContentView(root, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            photoView.setOnClickListener(v -> dialog.dismiss());
            dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK));
            }

            // show 之后再加载（View 已 attach），loading 占位 + 失败提示
            com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> listener =
                    new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                        @Override
                        public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                            loading.setVisibility(android.view.View.GONE);
                            showToast("图片加载失败");
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(android.graphics.drawable.Drawable resource,
                                                       Object model,
                                                       com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                       com.bumptech.glide.load.DataSource dataSource,
                                                       boolean isFirstResource) {
                            loading.setVisibility(android.view.View.GONE);
                            return false;
                        }
                    };
            com.bumptech.glide.Glide.with(this).load(url).timeout(15000).listener(listener).into(photoView);
        } catch (Exception e) {
            android.util.Log.w("AIChatActivity", "Image preview failed: " + e.getMessage());
        }
    }

    // ===================== Agent 执行过程（插入式组件显示） =====================

    /**
     * 将工具执行过程以组件形式插入到当前流式 AI 消息内（组件容器在回答文本上方）。
     * 工具开始 → running 卡片；工具完成 → 更新为 success/failed 卡片。
     */
    private void appendAgentToolCall(String toolName, String status, String args, String result) {
        if (currentStreamingMessageIndex < 0 || currentStreamingMessageIndex >= chatHistory.size()) return;
        ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
        if (msg == null) return;

        try {
            java.util.List<com.oilquiz.app.ai.chat.component.ComponentData> comps =
                    msg.components != null ? new java.util.ArrayList<>(msg.components) : new java.util.ArrayList<>();

            // 工具完成：更新最后一张 running 卡片
            if ("success".equals(status) || "failed".equals(status)) {
                for (int i = comps.size() - 1; i >= 0; i--) {
                    com.oilquiz.app.ai.chat.component.ComponentData c = comps.get(i);
                    if (c != null && "tool_call".equals(c.type)
                            && "running".equals(c.props != null ? c.props.optString("status", "") : "")) {
                        if (c.props == null) c.props = new org.json.JSONObject();
                        c.props.put("status", status);
                        if (result != null) c.props.put("result", result);
                        // 新引用触发 ChatAdapter 组件容器重建
                        msg.components = new java.util.ArrayList<>(comps);
                        if (chatAdapter != null) {
                            chatAdapter.notifyItemChanged(currentStreamingMessageIndex,
                                    ChatAdapter.PAYLOAD_CONTENT_UPDATE);
                        }
                        scrollToBottom();
                        return;
                    }
                }
            }

            // 新工具调用：追加 running 卡片
            org.json.JSONObject props = new org.json.JSONObject();
            props.put("toolName", toolName != null ? toolName : "工具");
            props.put("status", status != null ? status : "running");
            if (args != null) props.put("args", args);
            if (result != null) props.put("result", result);
            comps.add(com.oilquiz.app.ai.chat.component.ComponentData.of("tool_call", props));
            msg.components = new java.util.ArrayList<>(comps);
            if (chatAdapter != null) {
                chatAdapter.notifyItemChanged(currentStreamingMessageIndex, ChatAdapter.PAYLOAD_CONTENT_UPDATE);
            }
            scrollToBottom();
        } catch (Exception e) {
            AppLogger.aiW(TAG, "appendAgentToolCall failed: " + e.getMessage());
        }
    }

    /**
     * 更新状态栏：Agent 执行状态（工具调用/思考）实时反映，busy=true 显示不确定进度。
     * Agent 执行中服务状态不变，ServiceStatusManager 不会覆盖。
     */
    private void updateAgentStatusBar(String text, boolean busy) {
        if (serviceStatusText != null && text != null) {
            serviceStatusText.setText(text);
        }
        if (serviceStatusProgress != null) {
            serviceStatusProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (busy) serviceStatusProgress.setIndeterminate(true);
        }
    }

    // ===================== Weather Banner =====================

    // ===================== Attachments =====================

    private void handleAttachFile() {
        if (attachFileLauncher != null) attachFileLauncher.launch(new String[]{"image/*", "application/pdf", "text/plain", "*/*"});
        else showToast("附件功能初始化中");
    }

    /** 显示附件选项对话框 */
    private void showAttachmentOptionsDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("选择附件类型")
            .setItems(new String[]{"📷 拍照", "📁 选择文件", "🎤 录制语音（作为附件）", "⚙️ 语音模型设置"}, (dialog, which) -> {
                switch (which) {
                    case 0: // 拍照
                        handleTakePhoto();
                        break;
                    case 1: // 选择文件
                        handleAttachFile();
                        break;
                    case 2: // 录制语音附件
                        handleRecordAudio();
                        break;
                    case 3: // 语音模型设置
                        handleSpeechModelConfig();
                        break;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 打开相机拍照 */
    private void handleTakePhoto() {
        // ✅ 使用统一的权限管理工具请求相机权限（与OCR界面保持一致）
        AppResourceManager resources = AppResourceManager.getInstance(this);
        if (!resources.hasCameraPermission()) {
            resources.permissions().requestCameraPermission(this, new PermissionResourceProvider.PermissionCallback() {
                @Override
                public void onGranted() {
                    launchCamera();
                }

                @Override
                public void onDenied(java.util.List<String> deniedPermissions) {
                    showToast("❌ 需要相机权限才能拍照");
                    // 如果用户选择了“不再询问”，引导去设置页面
                    if (!resources.permissions().shouldShowRequestPermissionRationale(
                            AIChatActivity.this, android.Manifest.permission.CAMERA)) {
                        showPermissionSettingsDialog();
                    }
                }
            });
        } else {
            launchCamera();
        }
    }

    /** 权限已授予后实际启动相机 */
    private void launchCamera() {
        try {
            // 创建临时文件存储照片
            File photoFile = createImageFile();
            if (photoFile == null) {
                showToast("无法创建照片文件");
                return;
            }
            
            currentPhotoUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                photoFile
            );
            
            if (cameraCaptureLauncher != null) {
                cameraCaptureLauncher.launch(currentPhotoUri);
            } else {
                showToast("相机功能未初始化");
            }
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error taking photo: " + e.getMessage());
            showToast("打开相机失败: " + e.getMessage());
        }
    }

    /** 长按消息弹出语音操作菜单 */
    private void showMessageSpeechOptions(ChatMessage message) {
        if (message == null) return;
        String content = message.getContent();
        boolean canSpeak = message.isAIMessage() && content != null && !content.trim().isEmpty();
        if (!canSpeak) return;

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("消息操作")
            .setItems(new String[]{"🔊 朗读此消息", "⏹️ 停止朗读"}, (dialog, which) -> {
                if (which == 0) {
                    speakMessage(message);
                } else {
                    com.oilquiz.app.ai.speech.SpeechManager.getInstance(this).stopSpeaking();
                    showToast("已停止朗读");
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 使用 TTS 朗读 AI 消息（优先在线模型，未配置时自动回退系统TTS） */
    private void speakMessage(ChatMessage message) {
        speakMessage(message, message != null ? message.id : null);
    }

    private void speakMessage(ChatMessage message, final String messageId) {
        String text = toSpeakableText(message.getContent());
        if (text.isEmpty()) {
            showToast("消息内容为空或不可朗读");
            return;
        }
        if (text.length() > 2000) {
            text = text.substring(0, 2000);
            showToast("内容较长，仅朗读前 2000 字");
        }
        speakTextInternal(text, messageId, false);
    }

    /**
     * 将 AI 回复转换为可朗读文本：去除代码块、表格、Markdown 符号、
     * 工具调用痕迹、链接与 emoji；清洗后为空说明内容不适合朗读
     */
    private String toSpeakableText(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String t = raw;
        // 代码块整体省略
        t = t.replaceAll("(?s)```.*?```", "");
        // 行内代码去反引号
        t = t.replaceAll("`([^`]*)`", "$1");
        // 表格行与分隔行
        t = t.replaceAll("(?m)^\\s*\\|.*$\\n?", "");
        // 工具调用/思考痕迹行
        t = t.replaceAll("(?m)^\\s*🔧.*$\\n?", "");
        // 图片与链接（保留链接文字）
        t = t.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "");
        t = t.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        // Markdown 格式符号
        t = t.replaceAll("[*_#>~|\\\\]", "");
        // emoji 与其他特殊符号
        t = t.replaceAll("[\\p{So}\\p{Cn}]", "");
        // 压缩空白
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    /** 实际朗读入口：silent=true 时不弹任何提示（自动朗读模式） */
    private void speakTextInternal(String text, final String messageId, final boolean silent) {
        speakingMessageId = messageId;
        if (!silent) {
            showToast("🔊 开始合成语音...");
        }
        com.oilquiz.app.ai.speech.SpeechManager.getInstance(this).speakLocked(text,
                new com.oilquiz.app.ai.speech.TTSService.PlaybackCallback() {
            @Override
            public void onStart() {
                if (!silent) {
                    runOnUiThread(() -> showToast("🔊 正在朗读，再次点击朗读按钮可停止"));
                }
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    if (messageId != null && messageId.equals(speakingMessageId)) {
                        speakingMessageId = null;
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (messageId != null && messageId.equals(speakingMessageId)) {
                        speakingMessageId = null;
                    }
                    if (!silent) {
                        showToast("朗读已结束");
                    }
                });
            }
        });
    }

    /** 处理 AI 消息操作行的"朗读"按钮：同一条消息再次点击则停止 */
    private void handleSpeakAction(ChatMessage.Action action) {
        com.oilquiz.app.ai.speech.SpeechManager speech =
                com.oilquiz.app.ai.speech.SpeechManager.getInstance(this);
        if (action.messageId != null && action.messageId.equals(speakingMessageId) && speech.isSpeaking()) {
            speech.stopSpeaking();
            speakingMessageId = null;
            showToast("⏹️ 已停止朗读");
            return;
        }
        ChatMessage target = null;
        if (chatHistory != null) {
            for (ChatMessage m : chatHistory) {
                if (m != null && m.id != null && m.id.equals(action.messageId)) {
                    target = m;
                    break;
                }
            }
        }
        if (target == null) {
            // 兼容：直接用 Action 携带的内容构造临时消息
            target = ChatMessage.createAIMessage(action.content != null ? action.content : "");
        }
        speakMessage(target, action.messageId);
    }

    /** 语音输入：录音 → ASR 识别 → 文字填入输入框；在线不可用时自动兜底到系统识别 */
    private void handleSpeechInput() {
        com.oilquiz.app.ai.speech.SpeechManager speech =
                com.oilquiz.app.ai.speech.SpeechManager.getInstance(this);
        // 离线/系统识别模式：再次点击停止识别并出结果
        if (isOfflineAsrMode) {
            speech.stopOfflineRecognition();
            return;
        }
        if (isSpeechRecording) {
            stopSpeechRecording();
            return;
        }
        // ✅ 使用统一的权限管理工具请求麦克风权限
        com.oilquiz.app.resource.PermissionResourceProvider provider =
            com.oilquiz.app.resource.PermissionResourceProvider.getInstance(this);
        provider.requestMicrophonePermission(this, new com.oilquiz.app.resource.PermissionResourceProvider.PermissionCallback() {
            @Override
            public void onGranted() {
                startSpeechInputFlow();
            }

            @Override
            public void onDenied(java.util.List<String> deniedPermissions) {
                showToast("需要录音权限才能使用语音输入");
                setVoiceButtonEnabled(false);
            }
        });
    }

    /** 选择语音输入路径：在线ASR可用→录音上传识别；否则兜底系统语音识别 */
    private void startSpeechInputFlow() {
        com.oilquiz.app.ai.speech.SpeechManager speech =
                com.oilquiz.app.ai.speech.SpeechManager.getInstance(this);
        boolean asrAvail = speech.isAsrAvailable();
        boolean offlineAvail = speech.isOfflineAsrAvailable();
        AppLogger.aiD(TAG, "startSpeechInputFlow: asrAvailable=" + asrAvail + ", offlineAvailable=" + offlineAvail);
        if (asrAvail) {
            startSpeechRecording();
        } else if (offlineAvail) {
            showToast("使用系统语音识别");
            startOfflineSpeechRecognition(false);
        } else {
            showToast("语音识别暂不可用，可在模型管理中配置语音识别模型");
            setVoiceButtonEnabled(false);
        }
    }

    // ===================== 自动语音合成开关 =====================

    /** 切换自动语音合成开关（持久化），关闭时同时停止当前朗读 */
    private void toggleAutoTts() {
        autoTtsEnabled = !autoTtsEnabled;
        getSharedPreferences("ai_chat_prefs", MODE_PRIVATE).edit()
                .putBoolean("auto_tts_enabled", autoTtsEnabled).apply();
        updateAutoTtsButtonUI();
        if (autoTtsEnabled) {
            showToast("已开启自动语音合成");
        } else {
            lastAutoSpokenMessageId = null;
            // 关闭开关：停止当前朗读并清空待播队列（保留 streamTtsFed，避免完成时重复整条朗读）
            if (streamingTtsSpeaker != null) {
                streamingTtsSpeaker.reset();
            }
            com.oilquiz.app.ai.speech.SpeechManager.getInstance(this).stopSpeaking();
            showToast("已关闭自动语音合成");
        }
    }

    /** 更新自动语音合成按钮样式（开启时高亮） */
    private void updateAutoTtsButtonUI() {
        if (btnAutoTts == null) return;
        btnAutoTts.setText(autoTtsEnabled ? "🔊自动" : "🔇自动");
        btnAutoTts.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(autoTtsEnabled ? R.color.primary_container : R.color.surface_variant, getTheme())));
        btnAutoTts.setTextColor(getResources().getColor(autoTtsEnabled ? R.color.on_primary_container : R.color.text_secondary, getTheme()));
    }

    /** AI 消息完成后，若自动朗读开启则自动朗读（清洗后无有效内容则跳过，同一消息不重复朗读） */
    private void maybeAutoSpeak(ChatMessage msg) {
        if (!autoTtsEnabled || msg == null || !msg.isAIMessage()) return;
        if (msg.id != null && msg.id.equals(lastAutoSpokenMessageId)) return;
        String text = toSpeakableText(msg.getContent());
        if (text.length() < 2) return; // 纯代码/表格/空内容不自动朗读
        if (text.length() > 2000) {
            text = text.substring(0, 2000);
        }
        lastAutoSpokenMessageId = msg.id;
        speakTextInternal(text, msg.id, true);
    }

    /** 向流式朗读器投喂 token（仅自动朗读开启时生效） */
    private void feedStreamingTts(String token) {
        if (!autoTtsEnabled || token == null || token.isEmpty()) return;
        if (streamingTtsSpeaker == null) return;
        streamTtsFed = true;
        streamingTtsSpeaker.feed(token);
    }

    /** 新一轮生成开始：重置流式朗读状态 */
    private void resetStreamingTts() {
        streamTtsFed = false;
        if (streamingTtsSpeaker != null) {
            streamingTtsSpeaker.reset();
        }
    }

    /**
     * 生成完成时的自动朗读收尾：
     * - 本轮已流式朗读：冲刷剩余缓冲继续播完（若开关已关则静默），不重复整条朗读
     * - 本轮未流式朗读（如开关中途才开启前已完成）：走原有整条朗读逻辑
     */
    private void finishAutoSpeak(ChatMessage msg) {
        if (streamTtsFed) {
            if (autoTtsEnabled && streamingTtsSpeaker != null) {
                streamingTtsSpeaker.finish();
            }
            if (msg != null) {
                lastAutoSpokenMessageId = msg.id;
            }
            return;
        }
        maybeAutoSpeak(msg);
    }

    // ===================== 语音输入按钮可用性 =====================

    /** 设置语音输入按钮启用/禁用样式 */
    private void setVoiceButtonEnabled(boolean enabled) {
        if (btnVoice == null) return;
        btnVoice.setEnabled(enabled);
        btnVoice.setAlpha(enabled ? 1f : 0.4f);
    }

    /**
     * 刷新语音输入按钮可用性：
     * - 麦克风权限未授予：保持可点（用于触发授权，被拒后再禁用）
     * - 权限已授予：在线ASR 或 系统离线识别可用才启用，否则禁用（不弹引导对话框）
     */
    private void updateVoiceButtonAvailability() {
        if (btnVoice == null || isSpeechRecording || isOfflineAsrMode) return;
        boolean micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean available;
        if (!micGranted) {
            available = true; // 未授权时保留可点，点击后走授权流程；被拒时再禁用
        } else {
            com.oilquiz.app.ai.speech.SpeechManager speech =
                    com.oilquiz.app.ai.speech.SpeechManager.getInstance(this);
            available = speech.isAsrAvailable() || speech.isOfflineAsrAvailable();
        }
        setVoiceButtonEnabled(available);
    }

    /** 启动离线/系统语音识别（在线ASR不可用或失败时的兜底，需用户重新说话） */
    private void startOfflineSpeechRecognition(boolean isFallbackAfterOnlineFail) {
        isOfflineAsrMode = true;
        // 记录识别前输入框已有文本，部分结果实时拼接展示
        voiceInputBaseText = (inputMessage != null && inputMessage.getText() != null)
                ? inputMessage.getText().toString() : "";
        updateVoiceRecordingUI(true);
        com.oilquiz.app.ai.speech.SpeechManager.getInstance(this).startOfflineRecognition(
                new com.oilquiz.app.ai.speech.SystemSpeechRecognizer.RecognitionCallback() {
            @Override
            public void onResult(String text) {
                appendRecognizedText(text);
                showToast("✅ 识别完成（系统识别）");
            }

            @Override
            public void onPartialResult(String text) {
                // 说话过程中实时把部分结果写入输入框（保留原有前缀）
                if (inputMessage != null && text != null) {
                    inputMessage.setText(voiceInputBaseText + text);
                    inputMessage.setSelection(inputMessage.getText().length());
                    inputMessage.requestFocus();
                }
            }

            @Override
            public void onError(String error) {
                showToast("语音识别暂不可用，可在模型管理中配置语音识别模型");
            }

            @Override
            public void onEnd() {
                isOfflineAsrMode = false;
                updateVoiceRecordingUI(false);
            }
        });
        if (isFallbackAfterOnlineFail) {
            showToast("使用系统语音识别，请重新说话");
        }
    }

    /** 开始语音输入录音 */
    private void startSpeechRecording() {
        try {
            File audioFile = createAudioFile();
            if (audioFile == null) {
                showToast("无法创建录音文件");
                return;
            }
            speechRecordingFilePath = audioFile.getAbsolutePath();

            speechMediaRecorder = new MediaRecorder();
            speechMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            speechMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            speechMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            speechMediaRecorder.setAudioSamplingRate(44100);
            speechMediaRecorder.setAudioEncodingBitRate(128000);
            speechMediaRecorder.setOutputFile(speechRecordingFilePath);
            speechMediaRecorder.prepare();
            speechMediaRecorder.start();

            isSpeechRecording = true;
            updateVoiceRecordingUI(true);
            showToast("🎙️ 正在录音，说完后点击 🎤 按钮结束并识别");
        } catch (Exception e) {
            AppLogger.aiE(TAG, "语音输入录音启动失败: " + e.getMessage());
            showToast("录音启动失败: " + e.getMessage());
            releaseSpeechRecorder();
            updateVoiceRecordingUI(false);
        }
    }

    /** 更新语音输入的录音状态 UI（按钮图标/横幅/计时） */
    private void updateVoiceRecordingUI(boolean recording) {
        if (btnVoice != null) {
            btnVoice.setText(recording ? "⏹️" : "🎤");
            btnVoice.setTextColor(recording ? 0xFFE53935 : getResources().getColor(R.color.text_secondary, getTheme()));
        }
        if (voiceRecordingBar != null) {
            voiceRecordingBar.setVisibility(recording ? View.VISIBLE : View.GONE);
        }
        if (recording) {
            speechRecordingSeconds = 0;
            if (tvVoiceRecordingTime != null) tvVoiceRecordingTime.setText("00:00");
            if (speechTimerRunnable != null) speechTimerHandler.removeCallbacks(speechTimerRunnable);
            speechTimerRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!isSpeechRecording) return;
                    speechRecordingSeconds++;
                    if (tvVoiceRecordingTime != null) {
                        tvVoiceRecordingTime.setText(String.format(java.util.Locale.US, "%02d:%02d",
                                speechRecordingSeconds / 60, speechRecordingSeconds % 60));
                    }
                    // 红点闪烁
                    if (tvVoiceRecordingDot != null) {
                        tvVoiceRecordingDot.setAlpha(speechRecordingSeconds % 2 == 0 ? 1f : 0.3f);
                    }
                    speechTimerHandler.postDelayed(this, 1000);
                }
            };
            speechTimerHandler.postDelayed(speechTimerRunnable, 1000);
        } else {
            if (speechTimerRunnable != null) {
                speechTimerHandler.removeCallbacks(speechTimerRunnable);
                speechTimerRunnable = null;
            }
            if (tvVoiceRecordingDot != null) tvVoiceRecordingDot.setAlpha(1f);
        }
    }

    /** 停止语音输入录音并执行 ASR 识别 */
    private void stopSpeechRecording() {
        if (!isSpeechRecording || speechMediaRecorder == null) return;
        try {
            speechMediaRecorder.stop();
            isSpeechRecording = false;
            updateVoiceRecordingUI(false);
            releaseSpeechRecorder();

            File audioFile = new File(speechRecordingFilePath);
            if (!audioFile.exists() || audioFile.length() == 0) {
                showToast("录音文件无效");
                return;
            }

            showToast("🔄 正在识别语音...");
            com.oilquiz.app.ai.speech.SpeechManager.getInstance(this)
                .recognizeSpeech(audioFile, null)
                .whenComplete((result, error) -> runOnUiThread(() -> {
                    audioFile.delete();
                    if (error != null) {
                        Throwable cause = error instanceof java.util.concurrent.CompletionException
                                && error.getCause() != null ? error.getCause() : error;
                        AppLogger.aiE(TAG, "在线语音识别失败: " + cause.getMessage());
                        // 兜底：在线识别失败时自动切换系统/离线识别（需重新说话）
                        if (com.oilquiz.app.ai.speech.SpeechManager.getInstance(AIChatActivity.this)
                                .isOfflineAsrAvailable()) {
                            startOfflineSpeechRecognition(true);
                        } else {
                            showToast("语音识别暂不可用，可在模型管理中配置语音识别模型");
                        }
                    } else if (result != null && result.text != null && !result.text.isEmpty()) {
                        appendRecognizedText(result.text);
                        showToast("✅ 识别完成（" + result.modelName + "）");
                    } else {
                        showToast("未识别到语音内容");
                    }
                }));
        } catch (Exception e) {
            isSpeechRecording = false;
            updateVoiceRecordingUI(false);
            releaseSpeechRecorder();
            showToast("录音处理失败: " + e.getMessage());
        }
    }

    /**
     * 将识别结果写入输入框：直接操作 EditText（不依赖 ChatInputManager 内部状态，
     * 避免其未 init 时文本丢失），并让输入框获焦、光标定位到末尾
     */
    private void appendRecognizedText(String text) {
        if (text == null || text.isEmpty()) return;
        runOnUiThread(() -> {
            if (inputMessage != null) {
                inputMessage.append(text);
                inputMessage.requestFocus();
                inputMessage.setSelection(inputMessage.getText().length());
            } else if (inputManager != null) {
                inputManager.appendText(text);
            }
        });
    }

    /** 释放语音输入录音器 */
    private void releaseSpeechRecorder() {
        if (speechMediaRecorder != null) {
            try {
                speechMediaRecorder.release();
            } catch (Exception ignored) {
            }
            speechMediaRecorder = null;
        }
    }

    /** 语音模型设置：配置语音识别/语音合成专用模型 */
    private void handleSpeechModelConfig() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("语音模型设置")
            .setItems(new String[]{"🎙️ 语音识别模型（语音转文字）", "🔊 语音合成模型（文字转语音）"},
                (dialog, which) -> {
                    if (which == 0) {
                        new com.oilquiz.app.ui.dialog.SpeechModelSelectorDialog(
                                AIChatActivity.this,
                                com.oilquiz.app.ui.dialog.SpeechModelSelectorDialog.Mode.ASR)
                            .show();
                    } else {
                        new com.oilquiz.app.ui.dialog.SpeechModelSelectorDialog(
                                AIChatActivity.this,
                                com.oilquiz.app.ui.dialog.SpeechModelSelectorDialog.Mode.TTS)
                            .show();
                    }
                })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 处理相机拍摄的照片 */
    private void handleCameraPhoto(Uri photoUri) {
        try {
            // 复制照片到应用缓存目录，得到真实文件路径（FileProvider 的 content:// URI 直接使用会失效）
            String localPath = copyUriToCacheFile(photoUri);
            if (localPath == null) {
                showToast("照片保存失败");
                return;
            }
            java.io.File localFile = new java.io.File(localPath);
            long fileSize = localFile.exists() ? localFile.length() : 0;

            // 创建附件：url 使用 file:// URI 保证路径正确传递，thumbnailPath 用于缩略图显示
            ChatMessage.Attachment attachment = new ChatMessage.Attachment(
                "image",
                Uri.fromFile(localFile).toString(),
                "photo_" + System.currentTimeMillis() + ".jpg",
                fileSize
            );
            attachment.localFilePath = localPath;
            attachment.thumbnailPath = localPath;

            currentAttachments.add(attachment);
            resetAttachmentAdapter();
            showToast("照片已添加");

        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error handling camera photo: " + e.getMessage());
            showToast("处理照片失败: " + e.getMessage());
        }
    }

    /** 处理录音功能 */
    private void handleRecordAudio() {
        if (isRecording) {
            stopRecording();
        } else {
            // ✅ 使用统一的权限管理工具
            com.oilquiz.app.resource.PermissionResourceProvider provider = 
                com.oilquiz.app.resource.PermissionResourceProvider.getInstance(this);
            
            provider.requestMicrophonePermission(this, new com.oilquiz.app.resource.PermissionResourceProvider.PermissionCallback() {
                @Override
                public void onGranted() {
                    // 权限已授予，开始录音
                    startRecording();
                }
                
                @Override
                public void onDenied(java.util.List<String> deniedPermissions) {
                    showToast("需要录音权限才能使用语音功能");
                    setVoiceButtonEnabled(false);
                }
            });
        }
    }

    /** 开始录音 */
    private void startRecording() {
        try {
            // 创建临时音频文件
            File audioFile = createAudioFile();
            if (audioFile == null) {
                showToast("无法创建音频文件");
                return;
            }
            recordingFilePath = audioFile.getAbsolutePath();
            
            // 初始化 MediaRecorder
            mediaRecorder = new MediaRecorder();
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ 使用新的 API
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setAudioSamplingRate(44100);
                mediaRecorder.setAudioEncodingBitRate(128000);
            } else {
                // 旧版本兼容
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.DEFAULT);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);
            }
            
            mediaRecorder.setOutputFile(recordingFilePath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            
            isRecording = true;
            showToast("🎤 开始录音...");
            
            // 更新按钮状态（可选：显示录音中提示）
            updateRecordingUI(true);
            
        } catch (Exception e) {
            e.printStackTrace();
            showToast("录音启动失败: " + e.getMessage());
            releaseMediaRecorder();
        }
    }

    /** 停止录音并添加到附件 */
    private void stopRecording() {
        if (!isRecording || mediaRecorder == null) {
            return;
        }
        
        try {
            mediaRecorder.stop();
            isRecording = false;
            
            // 创建音频附件
            File audioFile = new File(recordingFilePath);
            if (audioFile.exists() && audioFile.length() > 0) {
                Uri audioUri = Uri.fromFile(audioFile);
                ChatMessage.Attachment attachment = new ChatMessage.Attachment(
                    "audio",
                    audioUri.toString(),
                    "语音消息"
                );
                currentAttachments.add(attachment);
                
                // 刷新附件列表显示
                refreshAttachmentsUI();
                
                showToast("✅ 录音已添加");
            } else {
                showToast("录音文件为空");
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            showToast("录音保存失败: " + e.getMessage());
        } finally {
            releaseMediaRecorder();
            updateRecordingUI(false);
        }
    }

    /** 释放 MediaRecorder 资源 */
    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mediaRecorder = null;
        }
    }

    /** 显示权限设置对话框，引导用户去系统设置页面 */
    private void showPermissionSettingsDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("需要录音权限")
            .setMessage("语音功能需要录音权限，请在设置中授予权限")
            .setPositiveButton("去设置", (dialog, which) -> {
                // 打开应用设置页面
                android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 创建临时音频文件 */
    private File createAudioFile() throws IOException {
        String timeStamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(new java.util.Date());
        String audioFileName = "AUDIO_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC);
        if (storageDir == null) {
            storageDir = getCacheDir();
        }
        return File.createTempFile(audioFileName, ".mp4", storageDir);
    }

    /** 更新录音 UI 状态 */
    private void updateRecordingUI(boolean recording) {
        runOnUiThread(() -> {
            if (recording) {
                showToast("🔴 录音中...再次点击停止");
            } else {
                showToast("⏹️ 录音结束");
            }
        });
    }

    /** 刷新附件列表 UI */
    private void refreshAttachmentsUI() {
        // 如果有附件 RecyclerView，刷新它
        // 这里假设有一个 attachmentRecyclerView
        // attachmentRecyclerView.getAdapter().notifyDataSetChanged();
    }

    /** 创建临时图片文件 */
    private File createImageFile() throws IOException {
        String timeStamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(new java.util.Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        
        File storageDir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
        if (storageDir == null) {
            storageDir = getCacheDir();
        }
        
        File image = File.createTempFile(imageFileName, ".jpg", storageDir);
        return image;
    }

    private void initAttachFileLauncher() {
        attachFileLauncher = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
            if (uris != null && !uris.isEmpty()) handleAttachedFiles(uris);
        });
        
        // 相机拍照 launcher
        cameraCaptureLauncher = registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            if (success && currentPhotoUri != null) {
                handleCameraPhoto(currentPhotoUri);
            }
        });
    }

    // ===================== 工具引导文件选择器 =====================

    private void initGuideFilePickers() {
        // 单文件选择（支持过滤 mimeTypes）
        guideFilePickerLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            onGuidePickerResult(uri, ToolGuideFlow.GuideStep.StepType.FILE_PICKER);
        });
        // 图片选择
        guideImagePickerLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            onGuidePickerResult(uri, ToolGuideFlow.GuideStep.StepType.IMAGE_PICKER);
        });
        // 目录选择（Android SAF OpenDocumentTree）
        guideDirectoryPickerLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            if (uri == null) return;
            try {
                // 申请长期访问权限
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
            onGuidePickerResult(uri, ToolGuideFlow.GuideStep.StepType.DIRECTORY_PICKER);
        });
    }

    /** 启动文件/图片/目录选择器 */
    private void launchGuidePicker(ToolGuideFlow.GuideStep step,
                                   TextView valueView,
                                   MaterialButton pickerBtn) {
        if (step == null) return;
        synchronized (pendingPickerLock) {
            pendingPickerStep = step;
            pendingPickerValueView = valueView;
            pendingPickerButton = pickerBtn;
        }
        try {
            switch (step.type) {
                case FILE_PICKER:
                    String[] mimeTypes = step.mimeTypes;
                    if (mimeTypes == null || mimeTypes.length == 0) mimeTypes = new String[]{"*/*"};
                    guideFilePickerLauncher.launch(mimeTypes);
                    break;
                case IMAGE_PICKER:
                    String[] imgMime = (step.mimeTypes != null && step.mimeTypes.length > 0)
                            ? step.mimeTypes : new String[]{"image/*"};
                    guideImagePickerLauncher.launch(imgMime);
                    break;
                case DIRECTORY_PICKER:
                    guideDirectoryPickerLauncher.launch(null);
                    break;
                default:
                    showToast("不支持的选择器类型");
            }
        } catch (Exception e) {
            AILogger.e("[AIChat]", "启动选择器失败", e);
            showToast("打开文件管理器失败，请尝试手动输入路径");
        }
    }

    /** 处理选择器返回结果 */
    private void onGuidePickerResult(Uri uri, ToolGuideFlow.GuideStep.StepType type) {
        ToolGuideFlow.GuideStep step;
        TextView valueView;
        MaterialButton pickerBtn;
        synchronized (pendingPickerLock) {
            step = pendingPickerStep;
            valueView = pendingPickerValueView;
            pickerBtn = pendingPickerButton;
            pendingPickerStep = null;
            pendingPickerValueView = null;
            pendingPickerButton = null;
        }
        if (step == null) return;

        String path;
        try {
            if (type == ToolGuideFlow.GuideStep.StepType.DIRECTORY_PICKER) {
                // 目录：尝试推断真实路径；拿不到则用 SAF 路径
                path = resolveDirectoryPath(uri);
            } else {
                // 文件：需要可直接访问的真实路径，优先复制到缓存
                path = resolveOrCopyToCacheFile(uri);
            }
        } catch (Exception e) {
            AILogger.w("[AIChat]", "路径解析失败，回退手动输入: " + e.getMessage());
            showToast("无法解析所选路径，请手动输入");
            showManualPathInput(step, valueView, pickerBtn);
            return;
        }

        if (path == null || path.isEmpty()) {
            showToast("无法获取路径，请手动输入");
            showManualPathInput(step, valueView, pickerBtn);
            return;
        }
        // 写回步骤 + 刷新显示
        step.paramValue = path;
        if (valueView != null) {
            runOnUiThread(() -> valueView.setText(path));
        }
        if (pickerBtn != null) {
            runOnUiThread(() -> pickerBtn.setText("已选择 ✓"));
        }
    }

    /** 手动输入路径弹窗（作为 SAF 的兜底，以及目录路径的常见情况） */
    private void showManualPathInput(final ToolGuideFlow.GuideStep step,
                                     final TextView valueView,
                                     final MaterialButton pickerBtn) {
        final EditText et = new EditText(this);
        et.setHint(step.hint != null && !step.hint.isEmpty() ? step.hint
                : (step.type == ToolGuideFlow.GuideStep.StepType.DIRECTORY_PICKER
                ? "例如 /sdcard/Download" : "例如 /sdcard/Download/test.txt"));
        if (step.paramValue != null) et.setText(step.paramValue);
        et.setSingleLine(!step.multiline);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad / 2, pad, pad / 2);

        String title = step.type == ToolGuideFlow.GuideStep.StepType.IMAGE_PICKER ? "手动输入图片路径"
                : step.type == ToolGuideFlow.GuideStep.StepType.DIRECTORY_PICKER ? "手动输入目录路径"
                : "手动输入文件路径";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(step.description)
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    String v = et.getText() == null ? "" : et.getText().toString().trim();
                    if (v.isEmpty() && step.required) {
                        showToast("路径不能为空");
                        return;
                    }
                    step.paramValue = v;
                    if (valueView != null) valueView.setText(v);
                    if (pickerBtn != null) {
                        pickerBtn.setText(v.isEmpty() ? "选择路径" : "已选择 ✓");
                    }
                }).show();
    }

    /** 把文件 URI 解析为可直接访问的真实路径；必要时复制到 app 缓存 */
    private String resolveOrCopyToCacheFile(Uri uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme();
        // 1. file:// 直接返回绝对路径
        if ("file".equalsIgnoreCase(scheme)) {
            return uri.getPath();
        }
        // 2. content:// —— 尝试直接取 data 列真实路径（部分 ROM 可拿到）
        if ("content".equalsIgnoreCase(scheme)) {
            String direct = queryDataColumnForPath(uri);
            if (direct != null && new java.io.File(direct).exists()) {
                return direct;
            }
        }
        // 3. 兜底：复制流到应用缓存目录的临时文件
        return copyUriToCacheFile(uri);
    }

    /** 目录 URI 解析为尽可能真实的路径 */
    private String resolveDirectoryPath(Uri treeUri) {
        if (treeUri == null) return null;
        try {
            // 从 tree URI 的 document id 中推断 /storage/emulated/0/xxx 形式
            // Android DocumentsProvider 对 primary 存储通常返回 primary:DCIM 这种
            String docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
            if (docId != null) {
                if (docId.startsWith("primary:")) {
                    String rel = docId.substring("primary:".length());
                    String ext = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
                    java.io.File f = new java.io.File(ext, rel);
                    if (f.exists() && f.isDirectory()) return f.getAbsolutePath();
                }
                // 尝试冒号分割第二个作为相对路径
                int colon = docId.indexOf(':');
                if (colon > 0) {
                    String rel = docId.substring(colon + 1);
                    String ext = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
                    java.io.File f = new java.io.File(ext, rel);
                    if (f.exists() && f.isDirectory()) return f.getAbsolutePath();
                }
            }
        } catch (Exception ignored) {}
        // 拿不到真实路径：返回 treeUri 字符串，由上层提示用户手动输入
        return null;
    }

    /** 尝试通过 MediaStore _data 列拿真实文件路径（不保证所有 ROM 都可用） */
    private String queryDataColumnForPath(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME, "_data"},
                    null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex("_data");
                        if (idx >= 0) {
                            String v = c.getString(idx);
                            if (v != null && !v.isEmpty()) return v;
                        }
                    }
                } finally { c.close(); }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 把 content:// 流复制到 app 缓存，返回临时文件的绝对路径 */
    private String copyUriToCacheFile(Uri uri) {
        try {
            String name = getFileNameFromUri(uri);
            if (name == null || name.isEmpty()) name = "picked_" + System.currentTimeMillis();
            // 确保文件名安全
            name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
            java.io.File dir = getExternalCacheDir();
            if (dir == null) dir = getCacheDir();
            java.io.File out = new java.io.File(dir, name);
            // 避免同名覆盖
            if (out.exists()) {
                String base = name;
                String ext = "";
                int dot = base.lastIndexOf('.');
                if (dot > 0) { ext = base.substring(dot); base = base.substring(0, dot); }
                int i = 1;
                while (out.exists()) {
                    out = new java.io.File(dir, base + "_" + (i++) + ext);
                }
            }
            java.io.InputStream is = null;
            java.io.OutputStream os = null;
            try {
                is = getContentResolver().openInputStream(uri);
                if (is == null) return null;
                os = new java.io.FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            } finally {
                try { if (is != null) is.close(); } catch (Exception ignored) {}
                try { if (os != null) os.close(); } catch (Exception ignored) {}
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            AILogger.w("[AIChat]", "复制缓存文件失败: " + e.getMessage());
            return null;
        }
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

            String attachmentUrl = uri.toString();
            String localFilePath = null;  // 本地文件路径（用于 thumbnailPath）

            // 对 content:// URI 复制到本地，避免权限过期后无法访问
            if ("content".equals(uri.getScheme())) {
                String localPath = copyUriToCacheFile(uri);
                if (localPath != null) {
                    localFilePath = localPath;
                    attachmentUrl = Uri.fromFile(new java.io.File(localPath)).toString();  // 使用 file:// URI
                }
            }

            if (inputManager != null) {
                ChatMessage.Attachment attachment = new ChatMessage.Attachment(type, attachmentUrl, fileName, getFileSizeFromUri(uri));
                // 如果是图片且已复制到本地，设置 thumbnailPath 便于快速显示
                if ("image".equals(type) && localFilePath != null) {
                    attachment.thumbnailPath = localFilePath;  // 纯文件路径
                }
                inputManager.addAttachment(attachment);
            }
        }
        showToast("已添加 " + uris.size() + " 个附件，可预览后发送");
        // 附件已添加到输入区（attachmentList 预览），由用户确认后点发送，
        // 不再自动发送（sendMessage 发送时会携带 currentAttachments）
    }

    /**
     * 发送带附件的消息（无需文字输入）
     */
    private void sendMessageWithAttachments() {
        if (inputManager == null || !inputManager.hasAttachments()) return;

        List<ChatMessage.Attachment> savedAttachments = inputManager.getCurrentAttachments();

        // 图片附件走 OCR 工具 + 在线模型分析，不依赖本地模型加载状态
        boolean hasImageAttachment = false;
        for (ChatMessage.Attachment att : savedAttachments) {
            if ("image".equals(att.type)) { hasImageAttachment = true; break; }
        }
        if (!hasImageAttachment && !isAIReady()) { showToast("AI服务未就绪，请稍后重试"); return; }

        inputManager.clearAttachments();

        String defaultMessage = DEFAULT_ATTACHMENT_MESSAGE;
        ChatMessage userMessage = ChatMessage.createUserMessage(defaultMessage, savedAttachments);
        chatHistory.add(userMessage);
        if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1);
        scrollToBottom();
        saveHistoryAsync();

        // 图片附件统一走 OCR + Agent 路径（在线模型优先）
        if (hasImageAttachment || shouldUseOnlineModel()) {
            processMessageWithAttachmentsViaAgent(defaultMessage, savedAttachments);
        } else if (fileContentExtractor != null) {
            processMessageWithAttachments(defaultMessage, savedAttachments);
        } else {
            processChatMessage(defaultMessage);
        }
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

    /**
     * 更新附件提取状态并触发AI摘要生成
     */
    private void updateAttachmentExtractionStatus(Uri uri, String extractedContent) {
        if (inputManager == null || attachmentProcessor == null) return;
        
        List<ChatMessage.Attachment> attachments = inputManager.getCurrentAttachments();
        String uriString = uri.toString();
        
        for (ChatMessage.Attachment attachment : attachments) {
            if (uriString.equals(attachment.url)) {
                // 更新附件的提取内容和状态
                attachment.extractedContent = extractedContent;
                attachment.isExtracted = true;
                attachment.isExtracting = false;
                
                // 通知适配器刷新UI（显示解析完成图标）
                runOnUiThread(() -> {
                    if (chatAdapter != null) {
                        chatAdapter.notifyDataSetChanged();
                    }
                });
                
                // 异步生成AI摘要
                Log.i(TAG, "Starting AI summary generation for: " + attachment.name);
                attachmentProcessor.generateAISummary(attachment);
                break;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateModelNameDisplay();
        updateModeButtonText();
        initAgentChatHandler();
        // 权限/模型配置可能已变化，刷新语音输入按钮可用性
        updateVoiceButtonAvailability();

        // 检测模型类型是否变化（在线↔本地），及时更新状态
        boolean nowOnline = shouldUseOnlineModel();
        if (lastUseOnlineModel != null && lastUseOnlineModel != nowOnline) {
            // 模型类型已切换，重新初始化 Agent 处理器并提示用户
            String modelDesc = nowOnline ? "在线模型" : "本地模型";
            addSystemMessage("🔄 已切换到" + modelDesc, ChatMessage.SystemMessageType.INFO);
        }
        lastUseOnlineModel = nowOnline;

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
                if (currentStreamingContent == null) {
                    currentStreamingContent = new StringBuilder();
                    resetStreamingTts();
                }
                currentStreamingContent.append(token);
                feedStreamingTts(token);
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
                    // 自动语音合成：后台服务返回结果后自动朗读（流式已朗读则冲刷收尾）
                    finishAutoSpeak(msg);
                } else addAIMessage(result);
            }
            currentStreamingContent = null; currentStreamingMessageIndex = -1; currentStreamingMessageId = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            // 释放语音输入录音器与 TTS 播放资源
            releaseSpeechRecorder();
            if (speechTimerRunnable != null) {
                speechTimerHandler.removeCallbacks(speechTimerRunnable);
                speechTimerRunnable = null;
            }
            com.oilquiz.app.ai.speech.SpeechManager speechManagerRef =
                    com.oilquiz.app.ai.speech.SpeechManager.getInstance(this);
            speechManagerRef.cancelOfflineRecognition();
            speechManagerRef.stopSpeaking();
            if (streamingTtsSpeaker != null) {
                streamingTtsSpeaker.reset();
            }

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
            // 注销在线模型变更监听
            unregisterModelChangeListener();
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
        // 停止时保存当前会话到历史
        if (chatHistoryManager != null && chatHistory != null && !chatHistory.isEmpty()) {
            final List<ChatMessage> copy = new ArrayList<>(chatHistory);
            new Thread(() -> chatHistoryManager.saveCurrentChatAsSession(copy)).start();
        }
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
        
        // ✅ 所有权限请求结果统一由 PermissionResourceProvider 处理
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
