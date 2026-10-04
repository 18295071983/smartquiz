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
import com.oilquiz.app.ai.chat.component.QuickToolChipModule;
import com.oilquiz.app.ai.chat.coordination.AIChatCoordinator;
import com.oilquiz.app.ai.chat.viewmodel.AIChatViewModel;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import com.oilquiz.app.ui.activity.QuestionGenerateActivity;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AIServiceInitializer;
import com.oilquiz.app.ai.service.AIServiceState;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.ai.chat.AgentChatHandler;
import com.oilquiz.app.ai.chat.ChatIdDispatcher;
import com.oilquiz.app.ai.chat.StreamingUpdateManager;
import com.oilquiz.app.ai.service.AIProcessingService;
import com.oilquiz.app.ai.tool.AITool;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;
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
import com.oilquiz.app.ai.agent.software.engine.AgentLoopEngine;
import com.oilquiz.app.ai.chat.MessageAttachmentAdapter;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.chat.ChatAdapter;
import com.oilquiz.app.ai.refactor.AIConfig;
import com.oilquiz.app.ai.refactor.CacheManager;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.refactor.AIInferenceCore;
import com.oilquiz.app.ai.callback.StreamCallback;
import com.oilquiz.app.ai.chat.ChatModeManager;
import com.oilquiz.app.ai.stats.TokenStatsManager;
import com.oilquiz.app.ai.chat.history.ChatHistoryAdapter;
import com.oilquiz.app.ui.adapter.AttachmentAdapter;
import com.oilquiz.app.util.fileparser.FileContentExtractor;
import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.PermissionResourceProvider;
import com.oilquiz.app.ai.chat.status.ServiceStatusManager;
import com.oilquiz.app.ai.chat.ui.ChatDialogHelper;
import com.oilquiz.app.ai.chat.ui.ChatStatsBar;
import com.oilquiz.app.ai.chat.history.ChatHistoryController;
import com.oilquiz.app.ai.util.ConversationSession;
import com.oilquiz.app.ai.chat.recovery.NativeRecoveryHandler;
import com.oilquiz.app.ai.chat.input.ChatInputBar;
import com.oilquiz.app.ai.chat.input.ChatInputManager;
import com.oilquiz.app.ai.chat.input.AttachmentProcessor;
import com.oilquiz.app.ai.chat.lifecycle.GenerationLifecycleManager;
import com.oilquiz.app.ai.chat.streaming.StreamingTokenPipeline;
import com.oilquiz.app.ai.chat.parser.OutputRouter;
import com.oilquiz.app.ai.chat.parser.ThinkingTagConfig;
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

import com.oilquiz.app.theme.ThemeColors;
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
    private TextView serviceStatusIcon;
    private TextView serviceStatusText;
    private android.widget.TextView tvApiBalance; // 在线 API 余额显示
    private android.widget.ProgressBar serviceStatusProgress;
    private TextView serviceStatusElapsed;
    private TextView tvGenPhase;
    private TextView tvKvStats;
    // 顶部单行滚动切换：思考内容按行切段，轮播/跟随最新段显示
    private java.util.List<String> thinkingSegments = new java.util.ArrayList<>();
    private int thinkingSegmentIndex = 0;
    private String lastThinkShown;
    private int lastThinkLineCount = 0;   // 已动画的思考行数：换新行才触发滑入，同段增长仅实时更新
    private final StringBuilder onlineThinkBuffer = new StringBuilder();  // 在线思考累积（顶部单行）

    // 状态条独立轮询：思考段不走 token 流式回调，需定时刷新 native 状态机 + KV
    private static final long STATE_POLL_INTERVAL_MS = 800L;
    private final android.os.Handler statePollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean statePolling = false;
    private final Runnable statePollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!statePolling) return;
            refreshNativeStateUI();
            statePollHandler.postDelayed(this, STATE_POLL_INTERVAL_MS);
        }
    };
    // ===== dsh 对齐：上下文仪表 / 发送队列 / 回底按钮 / 展示行（2026-09-23） =====
    // 上下文仪表已并入 session_stats_bar（ContextMeter pill），2026-09-23
    private android.widget.TextView btnScrollBottom;
    private android.widget.LinearLayout queueBar;
    private android.widget.TextView tvQueueInfo;
    private android.widget.TextView btnQueueCancel;
    /** 忙时发送模式：block=拒绝（默认）/ queue=排队 / steer=打断重发 */
    private static final String PREFS_BUSY_MODE = "ai_busy_send_mode";
    private String busySendMode = "block";
    /** 排队中的待发送消息（忙时 queue 模式入队，生成结束后自动发送） */
    private final java.util.List<String> pendingQueue = new java.util.ArrayList<>();
    /** 展示行用的上一 prompt 签名（SystemPromptRow 变更检测） */
    private String lastPromptSigForRow = null;
    private androidx.recyclerview.widget.RecyclerView messageList;
    private androidx.recyclerview.widget.RecyclerView attachmentList;
    private androidx.recyclerview.widget.RecyclerView historyList;
    private DrawerLayout drawerLayout;
    private EditText inputMessage;
    private MaterialButton btnSend;
    private MaterialButton btnAttach;
    private MaterialButton btnVoice; // 语音输入按钮（点击弹出语音识别组件，录音→ASR→填入输入框）
    private MaterialButton btnAutoTts; // 全局自动语音合成开关按钮
    private boolean autoTtsEnabled = false; // 自动语音合成是否开启（AI回复完成后自动朗读）
    private String lastAutoSpokenMessageId; // 已自动朗读的消息ID（防止重复朗读）
    private com.oilquiz.app.ai.speech.StreamingTtsSpeaker streamingTtsSpeaker; // 流式按句朗读器（边生成边朗读）
    private boolean streamTtsFed = false; // 本轮生成是否已进行过流式朗读
    /** 识别预览后待发送消息是否标记为语音消息（发送时消费并复位） */
    private volatile boolean pendingVoiceInputSource = false;
    private String speakingMessageId; // 当前正在朗读的消息 ID（再次点击可停止）
    private MaterialButton btnCloseHistory;
    private MaterialButton btnNewConversation;
    private MaterialButton btnClearAllHistory;
    private MaterialButton btnAgentManager;
    private MaterialButton btnAICenter;
    private MaterialButton btnAIService;
    /** 远程连接（电脑）：连接/断开/清除配置入口（工具抽屉「管理」组） */
    private MaterialButton btnRemoteDsh;
    /** 聊天页顶部「电脑连接」常驻状态条（点一下进连接界面） */
    private android.view.View remoteDshBar;
    private android.view.View remoteDshDot;
    private android.widget.TextView remoteDshText;
    private android.widget.TextView remoteDshAction;
    /** 状态条上的 ✕（隐藏；偏好可在「远程连接（电脑）」里重新打开） */
    private android.widget.ImageButton remoteDshHide;
    private MaterialButton btnModelDownload;
    private MaterialButton btnAiInit;
    private View thinkingIndicator;
    private Chip chipWeather;
    private Chip chipClear;
    /** 快捷工具栏：键盘弹出时自动折叠 */
    private ChipGroup quickActionsChipGroup;
    private ImageView ivQuickExpand;
    /** 快捷工具 chip 模块：统一管理快捷区工具入口 chip（静态绑定 + 动态构建） */
    private QuickToolChipModule quickToolModule;
    private boolean quickBarExpanded = true;
    /** 标记是否由键盘弹出自动折叠，键盘隐藏时仅恢复这种情况 */
    private boolean keyboardAutoCollapsed = false;
    /** 自动获取的环境上下文（如定位得到的city/lat/lon），供引导流程注入 */
    private Map<String, String> autoContext = new HashMap<>();

    private View emptyStateView;
    private com.google.android.material.chip.ChipGroup emptyStateChips;

    private AIChatCoordinator coordinator;
    private AIChatViewModel chatViewModel;
    private AIService aiService;
    private InferenceRouter inferenceRouter;
    private List<ChatMessage> chatHistory;
    // 历史分段加载状态（2026-09-25）：大文件只加载最新一页；长对话内存窗口化归档
    private static final int HISTORY_ARCHIVE_KEEP = 150;   // 内存窗口保留条数（超出归档到文件）
    private static final int HISTORY_ARCHIVE_CHUNK = 50;   // 每次归档条数
    private int sessionFrontSkipped = 0;      // 当前会话文件前端未加载条数
    private int aiHistoryFrontSkipped = 0;    // 单文件历史前端未加载条数
    private String historySourceSessionId = null; // 当前历史来源会话（null=单文件历史）
    private boolean historyLoadingMore = false;
    private TextView btnLoadMoreHistory;
    private ChatAdapter chatAdapter;
    private ChatStatsBar sessionStatsBar;
    private ChatHistoryManager chatHistoryManager;
    /** 当前会话的持久化 ID（用于更新而非重复创建；跨线程读写，需 volatile 保证可见性） */
    private volatile String currentSessionId;
    private AttachmentManager attachmentManager;
    private ChatHistoryAdapter chatHistoryAdapter;
    private AttachmentAdapter attachmentAdapter;
    private FileContentExtractor fileContentExtractor;
    private AIToolManager aiToolManager;
    private AIWeatherManager weatherManager;
    private AgentService agentService;
    private AgentChatHandler agentChatHandler;
    /** 当前 Agent 回调实例（随 AgentChatHandler 复用，每轮执行前需重置 completed 标志） */
    private AgentCallbackImpl agentCallback;
    /** 独立 Agent 执行面板已移除：Agent 过程改为组件插入式显示在 AI 消息内 */
    private ModelExecutionBridge modelBridge;
    private AIConfig aiConfig;
    /** 输入区上方的本地Agent开关（Chip，即时生效） */
    private com.google.android.material.chip.Chip chipLocalAgent;
    /** 输入区上方的联网搜索开关（Chip，切换当前在线模型 supportsWebSearch 即时生效） */
    private com.google.android.material.chip.Chip chipWebSearch;
    private CacheManager cacheManager;
    private OnlineModelManager onlineModelManager;
    private LocalBroadcastManager localBroadcastManager;
    private AIResultReceiver aiResultReceiver;
    private AITokenReceiver aiTokenReceiver;

    private volatile boolean isGenerating = false;
    private volatile boolean isDirectStreaming = false;
    /** UI 已分离标志（Activity 销毁/重建后置 true）：生成继续在桥/服务层运行，
     *  回调不再更新界面，只做结果落盘；重进界面通过文件恢复 + 热加载轮询 */
    private volatile boolean uiDetached = false;
    /** 重进热加载：退出时生成仍在进行 → 轮询会话文件把完成后的完整内容刷进界面 */
    private final android.os.Handler historyPollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable historyPollRunnable = () -> pollHistoryForHotReload();
    private int historyPollUnchangedCount = 0;
    /** 强制本地Agent执行标志：用户点击"🚀 强行使用本地Agent"后置位，processChatMessage 消费一次后清除 */
    private volatile boolean forceLocalAgentOnce = false;
    // 在线模型 API 返回的 Token 统计（由 onTokenStats 回调写入）
    // 当大于 0 时，UI 优先使用 API 数据，实现数据源自动切换
    private volatile int onlinePromptTokens = 0;
    private volatile int onlineCompletionTokens = 0;
    private volatile long onlineStatsReceiveTime = 0L;
    private volatile StringBuilder currentStreamingContent = null;
    private volatile StringBuilder currentThinkingContent = null;
    /** 当前回合 ID（消息对绑定，2026-09-14）：一次发送 = 一回合，user 消息与
     *  本回合 AI 回复（含思考/工具/汇总组件）共享同一 turnId，
     *  跨任务/会话恢复后仍可按 turnId 配对，不依赖顺序索引 */
    private volatile String currentTurnId = null;
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
    /** 模板思考标签（来自 chat template），供 legacy 兜底路径判断思考起止，不硬编码 */
    private volatile ThinkingTagConfig legacyThinkingTags = ThinkingTagConfig.empty();
    // 流式 UI 更新节流：避免高频 token 导致主线程过载卡顿
    private static final long UI_UPDATE_THROTTLE_MS = 120;
    private volatile long lastTokenUiUpdateTime = 0;
    /** 流式 token 统计（onToken 实时累计，节流刷新状态栏） */
    private volatile long streamingTokenCount = 0;
    private volatile long streamingStartTime = 0;
    private volatile long lastTokenStatsUiUpdateTime = 0;
    private volatile int currentStreamingMessageIndex = -1;
    private volatile String currentStreamingMessageId = null;
    private volatile int agentToolLoopCount = 0;
    /** 当前Agent执行组ID（null=非agent执行或本地模型） */
    private volatile String currentAgentGroupId = null;
    /** 上次使用的在线模型 ID（检测模型切换，切换后清引擎历史避免上下文污染） */
    private volatile String lastOnlineModelId = null;
    private volatile int agentGroupStepCount = 0;
    private volatile int agentGroupToolCount = 0;
    /** 本轮 Agent 用到的工具名集合（去重，用于汇总展示） */
    private final java.util.Set<String> agentToolNames = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Object streamingLock = new Object();

    /**
     * 历史持久化跨实例全局锁：旋转重建时旧 Activity 的异步保存线程与新 Activity 的
     * 保存/加载线程并发写读同一会话文件，synchronized(this) 实例锁不互斥会导致
     * 半写文件 → Gson 解析失败 → 会话被跳过/历史丢失。此锁跨 Activity 实例互斥。
     */
    private static final Object HISTORY_IO_LOCK = new Object();
    
    // isRecovering 和 pendingMessageForRecovery 已移至 NativeRecoveryHandler
    private volatile int recoveryProgressUpdateCount = 0;
    private static final int MAX_RECOVERY_FAILURES_NOTIFY = 3;

    private int tokenCountSinceLastUpdate = 0;
    private long lastUpdateTime = 0;
    private boolean isUpdateScheduled = false;
    private android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** 文件历史是否已加载完成：文件是权威持久化源（含最新组件），
     *  VM 的 chatMessages 是 initialize 时的一次性快照，旋转重建后不含本次会话新组件，
     *  故文件加载完成前禁止 VM 快照抢位填充 chatHistory（否则最新组件丢失）。 */
    private volatile boolean fileHistoryLoaded = false;
    /** 用户主动清空/新建对话后置位：作废尚未完成的异步历史加载结果，防止旧会话回灌顶掉新消息 */
    private volatile boolean historyLoadStale = false;

    // ===== 思考内容定时渲染（节流）：防止每 token notifyItemChanged 导致思考区画面抽搐 =====
    /** 思考区最小刷新间隔（ms）：思考 token 累积后批量刷新一次 */
    private static final long THINKING_REFRESH_INTERVAL_MS = 120;
    private final Object thinkingRefreshLock = new Object();
    private int pendingThinkingIndex = -1;
    private boolean thinkingRefreshScheduled = false;
    private final Runnable thinkingRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            int idx;
            synchronized (thinkingRefreshLock) {
                thinkingRefreshScheduled = false;
                idx = pendingThinkingIndex;
                pendingThinkingIndex = -1;
            }
            if (idx < 0 || idx >= chatHistory.size() || chatAdapter == null) return;
            try {
                ChatMessage msg = chatHistory.get(idx);
                chatAdapter.updateMessageThinkingContent(idx, msg.thinkingContent);
                // 顶部单行：thinking 事件驱动（120ms 节流），无 800ms 轮询限制。
                // 连续性策略：同段内容增长只实时更新文本（不重启动画，自然连续）；
                // 出现新行（换段）才刮刀式滑入动画，避免频繁重启造成的断续抖动。
                if (isInThinking && tvGenPhase != null && tvGenPhase.getVisibility() == View.VISIBLE
                        && msg.thinkingContent != null && !msg.thinkingContent.isEmpty()) {
                    String disp = "💭 " + buildThinkingSegment(msg.thinkingContent);
                    if (!disp.equals(lastThinkShown)) {
                        tvGenPhase.setText(disp);
                        lastThinkShown = disp;
                        int lineCount = countThinkingLines(msg.thinkingContent);
                        if (lineCount != lastThinkLineCount) {
                            lastThinkLineCount = lineCount;
                            startThinkingRollerAnim();
                        }
                    }
                }
            } catch (IndexOutOfBoundsException e) {
                currentStreamingMessageIndex = -1;
            }
        }
    };

    /** 思考内容更新（数据已同步到 msg.thinkingContent）：节流刷新 UI（120ms 批量一次） */
    private void scheduleThinkingRefresh(int idx) {
        if (idx < 0) return;
        synchronized (thinkingRefreshLock) {
            pendingThinkingIndex = idx;
            if (!thinkingRefreshScheduled) {
                thinkingRefreshScheduled = true;
                uiHandler.postDelayed(thinkingRefreshRunnable, THINKING_REFRESH_INTERVAL_MS);
            }
        }
    }

    /** 思考结束/生成结束：取消待执行的思考节流刷新（调用方随后会全量 notify，无需重复） */
    private void cancelThinkingRefresh() {
        synchronized (thinkingRefreshLock) {
            if (thinkingRefreshScheduled) {
                thinkingRefreshScheduled = false;
                uiHandler.removeCallbacks(thinkingRefreshRunnable);
            }
            pendingThinkingIndex = -1;
        }
    }

    private StreamingUpdateManager streamingUpdateManager = null;
    private long totalTokensGenerated = 0;
    private long generationStartTime = 0;
    // isLoadingModel 和 loadingProgressMessageIndex 已移至 ServiceStatusManager
    // aiStatusObserver, loadingTimerRunnable 已移至 ServiceStatusManager
    private static final long LOADING_TIMER_INTERVAL_MS = 500;
    private ActivityResultLauncher<String[]> attachFileLauncher;
    private ActivityResultLauncher<Uri> cameraCaptureLauncher; // 相机拍照
    private android.net.Uri currentPhotoUri; // 当前拍照的临时URI
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
    private NativeRecoveryHandler recoveryHandler;
    private ChatInputManager inputManager;
    private AttachmentProcessor attachmentProcessor;
    private GenerationLifecycleManager lifecycleManager;
    private com.oilquiz.app.ai.chat.parser.OutputRouter outputRouter;
    private StreamingTokenPipeline streamingPipeline;

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
            messageList = findViewById(R.id.message_list);
            sessionStatsBar = findViewById(R.id.session_stats_bar);
            attachmentList = findViewById(R.id.attachment_list);
            historyList = findViewById(R.id.history_list);
            drawerLayout = findViewById(R.id.drawer_layout);
            // 抽屉滑动逻辑：解锁 + 允许边缘滑动进入/退出（左右两侧）
            if (drawerLayout != null) {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
                // 右侧工具抽屉：从右边缘滑入打开
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, findViewById(R.id.tool_drawer));
                // 左侧历史抽屉：从左边缘滑入打开
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, findViewById(R.id.history_drawer));
                // 抽屉阴影高度（视觉分层）
                try {
                    drawerLayout.setDrawerElevation(
                            getResources().getDisplayMetrics().density * 16f);
                } catch (Throwable ignored) {
                }
            }
            inputMessage = findViewById(R.id.input_message);
            btnSend = findViewById(R.id.btn_send);
            btnAttach = findViewById(R.id.btn_attach);
            btnVoice = findViewById(R.id.btn_voice);
            btnAutoTts = findViewById(R.id.btn_auto_tts);
            btnCloseHistory = findViewById(R.id.btn_close_history);
            btnNewConversation = findViewById(R.id.btn_new_conversation);
            btnClearAllHistory = findViewById(R.id.btn_clear_all_history);
            btnAgentManager = findViewById(R.id.btn_agent_manager);
            btnAICenter = findViewById(R.id.btn_ai_center);
            btnAIService = findViewById(R.id.btn_ai_service);
        btnRemoteDsh = findViewById(R.id.btn_remote_dsh);
        remoteDshBar = findViewById(R.id.remote_dsh_bar);
        remoteDshDot = findViewById(R.id.remote_dsh_dot);
        remoteDshText = findViewById(R.id.remote_dsh_text);
        remoteDshAction = findViewById(R.id.remote_dsh_action);
        if (remoteDshBar != null) {
            remoteDshBar.setOnClickListener(v ->
                    startActivity(new Intent(AIChatActivity.this,
                            com.oilquiz.app.ai.tool.RemoteDshConnectActivity.class)));
        }
        remoteDshHide = findViewById(R.id.remote_dsh_hide);
        if (remoteDshHide != null) {
            remoteDshHide.setOnClickListener(v -> {
                com.oilquiz.app.ai.tool.RemoteDshTool.setBarEnabled(this, false);
                remoteDshBar.setVisibility(View.GONE);
                android.widget.Toast.makeText(this,
                        "已隐藏连接状态条（可在「远程连接（电脑）」里重新打开）",
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        }
        refreshRemoteDshBar();
            btnModelDownload = findViewById(R.id.btn_model_download);
            btnAiInit = findViewById(R.id.btn_ai_init);
            thinkingIndicator = findViewById(R.id.thinking_indicator);
            chipWeather = findViewById(R.id.chip_weather);
            chipClear = findViewById(R.id.chip_clear_chat2);

            // 快捷工具已移入左侧工具抽屉（view_tool_drawer）：chip 始终可见，无折叠逻辑
            quickActionsChipGroup = findViewById(R.id.quick_actions_chip_group);
            if (quickActionsChipGroup != null) {
                quickActionsChipGroup.setVisibility(View.VISIBLE);
            }
            quickBarExpanded = true;
            keyboardAutoCollapsed = false;

            emptyStateView = findViewById(R.id.empty_state_view);
            emptyStateChips = findViewById(R.id.empty_state_chips);

            serviceStatusBar = findViewById(R.id.service_status_bar);
            serviceStatusIcon = findViewById(R.id.service_status_icon);
            serviceStatusText = findViewById(R.id.service_status_text);
            tvApiBalance = findViewById(R.id.tv_api_balance);
            serviceStatusProgress = findViewById(R.id.service_status_progress);
            serviceStatusElapsed = findViewById(R.id.service_status_elapsed);
            tvGenPhase = findViewById(R.id.tv_gen_phase);
            tvKvStats = findViewById(R.id.tv_kv_stats);
            
            if (serviceStatusBar != null) {
                serviceStatusBar.setOnClickListener(v -> showServiceStatusDetails());
            }

            // dsh 对齐：上下文仪表 / 队列条 / 回底按钮绑定
            btnScrollBottom = findViewById(R.id.btn_scroll_bottom);
            btnLoadMoreHistory = findViewById(R.id.btn_load_more_history);
            if (btnLoadMoreHistory != null) {
                btnLoadMoreHistory.setOnClickListener(v -> loadMoreHistory());
            }
            queueBar = findViewById(R.id.queue_bar);
            tvQueueInfo = findViewById(R.id.tv_queue_info);
            btnQueueCancel = findViewById(R.id.btn_queue_cancel);
            busySendMode = getSharedPreferences("ai_prefs", MODE_PRIVATE).getString(PREFS_BUSY_MODE, "block");
            restorePendingQueue();
            // 上下文仪表 pill 点击 → 上下文明细（2026-09-23：并入统计条）
            if (sessionStatsBar != null) sessionStatsBar.setOnContextPillClick(this::showContextMeterDialog);
            if (btnScrollBottom != null) btnScrollBottom.setOnClickListener(v -> { scrollToBottom(true); updateScrollBottomButton(); });
            if (queueBar != null) queueBar.setOnClickListener(v -> cycleBusyMode());
            if (btnQueueCancel != null) btnQueueCancel.setOnClickListener(v -> { pendingQueue.clear(); updateQueueBar(); persistPendingQueue(); });

            messageList.setLayoutManager(new LinearLayoutManager(this));
            // 完全禁用 RecyclerView 动画：结构变化(insert/remove)不再被 postpone，
            // 从根上消除 pre-layout 失配窗口（RecyclerView Inconsistency "offset:-1" 崩溃的必要条件）。
            // 代价：消息插入/删除无动画，换取聊天列表在高频流式更新下的稳定。
            messageList.setItemAnimator(null);
            chatHistory = new ArrayList<>();
            chatAdapter = new ChatAdapter(chatHistory, this::handleAction);
            // dsh 对齐：滚动监听驱动回底按钮显隐
            messageList.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                @Override public void onScrolled(androidx.recyclerview.widget.RecyclerView rv, int dx, int dy) {
                    updateScrollBottomButton();
                    updateLoadMoreButton();
                }
            });
            // 初始刷新上下文仪表（历史加载完成后会再次刷新）
            messageList.post(() -> refreshContextMeter());
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
                        // 传完整附件（含本地路径），预览优先用本地文件，避免 content:// 权限过期转圈
                        showImagePreview(attachment.url, attachment.thumbnailPath, attachment.localFilePath);
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
            // 白名单页：显式开启 edge-to-edge + 透明系统栏（targetSdk 36 强制，但显式避免个别系统差异）
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarContrastEnforced(false);
            View mainContent = findViewById(R.id.main_content);
            if (mainContent != null) {
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(mainContent, (v, insets) -> {
                    int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                    int left = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).left;
                    int right = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).right;
                    // 2026-09-23：targetSdk 35+ 强制 edge-to-edge，windowSoftInputMode=adjustResize 被忽略
                    // （Android 15+ 弃用）。必须手动消费 ime insets 把输入栏撑到键盘上方：
                    // 键盘弹出时 bottom=键盘高度；收起时回退到导航栏高度（手势条区域）
                    int bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
                    if (bottom == 0) {
                        bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
                    }
                    if (bottom == 0) {
                        // MIUI 个别版本手势条 inset 报 0：systemGestures 底部始终报手势区高度
                        bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemGestures()).bottom;
                    }
                    v.setPadding(left, top, right, bottom);
                    return insets;
                });
            }

            if (btnLogViewer != null) {
                btnLogViewer.setOnClickListener(v -> startActivity(new Intent(AIChatActivity.this, LogViewerActivity.class)));
            }
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error initializing view: " + e.getMessage());
            showToast(getString(R.string.h_d8bd0728) + e.getMessage());
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

            // 本地Agent开关（Chip）：切换即写配置，processChatMessage 按 isLocalAgentEnabled() 路由，
            // 因此下一条消息自动切换本地Agent/普通对话模式，无需重启
            chipLocalAgent = findViewById(R.id.chip_local_agent);
            if (chipLocalAgent != null && aiConfig != null) {
                chipLocalAgent.setChecked(aiConfig.isLocalAgentEnabled());
                updateAgentChip(chipLocalAgent);
                chipLocalAgent.setOnClickListener(v -> {
                    boolean next = !aiConfig.isLocalAgentEnabled();
                    aiConfig.setLocalAgentEnabled(next);
                    chipLocalAgent.setChecked(next);
                    updateAgentChip(chipLocalAgent);
                    showToast(next
                            ? "智能助手已为您服务"
                            : "智能助手已关闭：已进入快速模式");
                });
            }

            // 联网搜索开关（Chip）：切换当前在线模型的 supportsWebSearch 并持久化，即时生效
            chipWebSearch = findViewById(R.id.chip_web_search);
            if (chipWebSearch != null) {
                updateWebSearchChip();
                chipWebSearch.setOnClickListener(v -> {
                    // 从配置状态取反（checkable=false，不受系统自动翻转干扰）
                    boolean on = false;
                    try {
                        OnlineModelManager.OnlineModelConfig cfg = onlineModelManager != null
                                ? onlineModelManager.getActiveModel() : null;
                        on = cfg != null && cfg.supportsWebSearch;
                    } catch (Exception ignored) {
                    }
                    boolean next = !on;
                    chipWebSearch.setChecked(next);
                    toggleWebSearch(next);
                    updateWebSearchChip();
                });
            }

            // 注册在线模型变更监听，确保模型切换后名称即时刷新
            registerModelChangeListener();

            // 创建模型执行桥接器 - UI与模型之间的唯一通道
            modelBridge = ModelExecutionBridge.getInstance(this, aiService, agentService, aiConfig);

            // 本地推理上下文独立化（2026-09-14）：恢复当前会话的推理历史
            //（Activity 重建/重启后本地模型上下文按会话恢复，不再清空从头开始）
            if (modelBridge != null) {
                modelBridge.setLocalSessionId(currentSessionId);
            }

            // NPU（GenieX）引擎不需要 llama.cpp 的本地服务：它自带模型加载与管理
            boolean npuEngineOn = isNpuEngineOn();
            if (aiService == null && !shouldUseOnlineModel() && !npuEngineOn) {
                showToast(getString(R.string.h_16b746be));
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
            weatherManager = new AIWeatherManager(this, AIWeatherManager.WeatherProvider.HEFENG);

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
                        // 分段加载：只取单文件历史的最新一页（大文件不再全量解析，避免卡顿/OOM）
                        final int[] aiSkipped = {0};
                        int aiTotal = chatHistoryManager.countAIHistoryMessages();
                        aiSkipped[0] = Math.max(0, aiTotal - ChatHistoryManager.HISTORY_PAGE_SIZE);
                        List<ChatMessage> loadedHistory =
                                chatHistoryManager.loadAIHistoryMessages(aiSkipped[0], ChatHistoryManager.HISTORY_PAGE_SIZE);
                        // 退出保存是异步线程（onStop/onDestroy），重建加载可能恰逢旧保存的
                        // delete→rename 窗口（文件暂时不存在/半写）→ 读到空。延迟 300ms 重读一次
                        // 避开保存窗口，避免把"正在保存中的历史"误判为"无历史"而 fallback 错会话。
                        if (loadedHistory == null || loadedHistory.isEmpty()) {
                            try {
                                Thread.sleep(300);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                            int aiTotal2 = chatHistoryManager.countAIHistoryMessages();
                            aiSkipped[0] = Math.max(0, aiTotal2 - ChatHistoryManager.HISTORY_PAGE_SIZE);
                            loadedHistory =
                                    chatHistoryManager.loadAIHistoryMessages(aiSkipped[0], ChatHistoryManager.HISTORY_PAGE_SIZE);
                        }
                        final List<ChatMessage> historyToLoad = loadedHistory;
                        if (historyToLoad != null && !historyToLoad.isEmpty()) {
                            // 重建后没有生成任务在跑：把持久化残留的 GENERATING 消息归一为
                            // COMPLETED，避免 UI 永久显示"生成中"转圈（退出时生成已中断）
                            // 注意：退出时生成不中断（2026-09-14）——若发现 GENERATING 残留说明
                            // 后台仍在生成，恢复后启动热加载轮询，完成后把完整内容刷进界面
                            final boolean[] hadGenerating = {false};
                            for (ChatMessage m : historyToLoad) {
                                if (m != null && m.status == ChatMessage.MessageStatus.GENERATING) {
                                    m.status = ChatMessage.MessageStatus.COMPLETED;
                                    hadGenerating[0] = true;
                                }
                            }
                            runOnUiThread(() -> {
                                // 用户已主动清空/新建对话：丢弃本次历史加载结果，防止回灌顶掉新消息
                                if (historyLoadStale) return;
                                // 文件历史是权威持久化源（含最新组件）。VM observe 可能已用
                                // 启动时快照填充（旋转重建时 VM 保留，快照缺本次会话新组件），
                                // 这里必须用文件数据替换而非跳过，否则最新组件/消息会丢失。
                                if (chatHistory != historyToLoad) {
                                    chatHistory.clear();
                                    chatHistory.addAll(historyToLoad);
                                }
                                // 恢复补发 id：旧数据组件/思考轮 id 缺失时按 turnId 前缀补发
                                ensureRecoveredIds(chatHistory);
                                fileHistoryLoaded = true;
                                // 分段加载状态：单文件历史，前端未加载条数
                                historySourceSessionId = null;
                                aiHistoryFrontSkipped = aiSkipped[0];
                                sessionFrontSkipped = 0;
                                updateLoadMoreButton();
                                if (chatAdapter != null) {
                                    chatAdapter.notifyDataSetChanged();
                                }
                                updateEmptyState();
                                // 滚动到最新消息
                                scrollToBottom(true);
                                if (hadGenerating[0]) startHistoryHotReload();
                            });
                        } else {
                            // 单文件历史为空，尝试从最新的会话文件中恢复
                            List<ConversationSession> sessions = chatHistoryManager.listConversationSessions();
                            if (sessions != null && !sessions.isEmpty()) {
                                // 列表已按更新时间降序排列，取第一个即为最新会话
                                ConversationSession latest = sessions.get(0);
                                final String sessionId = latest.id;
                                // 分段加载：只取最新会话的最新一页（大文件不再全量解析）
                                int sessTotal = chatHistoryManager.countConversationMessages(sessionId);
                                final int[] sessSkipped = {Math.max(0, sessTotal - ChatHistoryManager.HISTORY_PAGE_SIZE)};
                                List<ChatMessage> pageMsgs = chatHistoryManager.loadConversationSessionMessages(
                                        sessionId, sessSkipped[0], ChatHistoryManager.HISTORY_PAGE_SIZE);
                                if (pageMsgs != null && !pageMsgs.isEmpty()) {
                                    // 会话恢复同样清理 GENERATING 残留（退出时生成中断的消息）；
                                    // 退出不中断生成后：发现残留说明后台仍在生成 → 热加载轮询
                                    final boolean[] hadGenerating = {false};
                                    for (ChatMessage m : pageMsgs) {
                                        if (m != null && m.status == ChatMessage.MessageStatus.GENERATING) {
                                            m.status = ChatMessage.MessageStatus.COMPLETED;
                                            hadGenerating[0] = true;
                                        }
                                    }
                                    runOnUiThread(() -> {
                                        // 用户已主动清空/新建对话：丢弃本次历史加载结果，防止回灌顶掉新消息
                                        if (historyLoadStale) return;
                                        chatHistory.clear();
                                        chatHistory.addAll(pageMsgs);
                                        // 恢复补发 id：旧会话组件/思考轮 id 缺失时按 turnId 前缀补发
                                        ensureRecoveredIds(chatHistory);
                                        fileHistoryLoaded = true;
                                        currentSessionId = sessionId;
                                        // 分段加载状态：历史来源为会话，前端未加载条数
                                        historySourceSessionId = sessionId;
                                        sessionFrontSkipped = sessSkipped[0];
                                        aiHistoryFrontSkipped = 0;
                                        updateLoadMoreButton();
                                        // 同步引擎会话：恢复该会话的 Agent 上下文（如存在）
                                        if (agentChatHandler != null) {
                                            agentChatHandler.setSessionId(sessionId);
                                        }
                                        // 本地推理上下文独立化：跟随最新会话恢复推理历史
                                        //（Activity 重建后若不同步，推理历史会错位到 default）
                                        if (modelBridge != null) {
                                            modelBridge.setLocalSessionId(sessionId);
                                        }
                                        if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                                        updateEmptyState();
                                        scrollToBottom(true);
                                        showToast(getString(R.string.h_c753c564));
                                        if (hadGenerating[0]) startHistoryHotReload();
                                    });
                                } else {
                                    runOnUiThread(() -> {
                                        fileHistoryLoaded = true;
                                        showWelcomeGuide();
                                    });
                                }
                            } else {
                                // 历史为空，显示新手引导
                                runOnUiThread(() -> {
                                    fileHistoryLoaded = true;
                                    showWelcomeGuide();
                                });
                            }
                        }
                    }
                } catch (Exception e) {
                    AppLogger.aiE(TAG, "Error loading chat history: " + e.getMessage());
                    runOnUiThread(() -> fileHistoryLoaded = true);
                }
            }).start();

            // 3. 初始化模块化组件（必须在使用模块之前）
            initModules();

            // 4. 现在可以安全地使用模块了

        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error initializing data: " + e.getMessage());
            showToast(getString(R.string.h_8635e3bd) + e.getMessage());
        }
    }

    /**
     * 观察 ViewModel 的 LiveData
     */
    private void observeViewModel() {
        if (chatViewModel == null) return;

        // 观察聊天消息变化（VM 消息列表与 Activity chatHistory 双轨：仅当 chatHistory 为空且
        // 文件历史尚未加载完成时从 VM 恢复，避免两列表各自增长导致数据脱节；正常发送走 Activity 的 chatHistory）
        chatViewModel.getChatMessages().observe(this, messages -> {
            // fileHistoryLoaded：文件历史加载完成后不再接受 VM 快照（VM 是启动时一次性快照，
            // 旋转重建后缺本次会话新组件；以文件为准），且文件加载期间不抢位，避免最新组件被旧快照覆盖
            if (messages != null && !messages.isEmpty() && chatHistory.isEmpty() && !fileHistoryLoaded && chatAdapter != null) {
                chatHistory.addAll(messages);
                refreshSessionStats();
                chatAdapter.notifyDataSetChanged();
                scrollToBottom();
            }
        });

        // 观察生成状态变化（兼容旧版）
        chatViewModel.isGenerating().observe(this, isGenerating -> {
            if (isGenerating != null) {
                this.isGenerating = isGenerating;
                if (isGenerating) {
                    showLoading("正在思考...", null);
                } else {
                    hideLoading();
                    // dsh 对齐：生成结束后自动发送排队消息 + 刷新上下文仪表
                    drainQueueIfAny();
                    refreshContextMeter();
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
                updateModeButtonText();
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

        // 在线思考实时流 → 顶部单行显示（增量累积 + 段落切换动画；null 表示思考结束隐藏）
        chatViewModel.getOnlineThinkingStream().observe(this, s -> {
            try {
                if (s == null) {
                    onlineThinkBuffer.setLength(0);
                    lastThinkShown = null;
                    lastThinkLineCount = 0;
                    if (tvGenPhase != null) tvGenPhase.setVisibility(View.GONE);
                    return;
                }
                onlineThinkBuffer.append(s);
                String full = onlineThinkBuffer.toString();
                String disp = "💭 " + buildThinkingSegment(full);
                if (!disp.equals(lastThinkShown) && tvGenPhase != null) {
                    tvGenPhase.setVisibility(View.VISIBLE);
                    tvGenPhase.setText(disp);
                    lastThinkShown = disp;
                    int lc = countThinkingLines(full);
                    if (lc != lastThinkLineCount) {
                        lastThinkLineCount = lc;
                        startThinkingRollerAnim();
                    }
                }
            } catch (Exception ignored) {}
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
                updateSendButtonState(true, getString(R.string.h_1535fcfa));
                break;

            case LOADING:
                showLoading("AI模型加载中...", null);
                updateSendButtonState(false, getString(R.string.h_26b5bd49));
                break;

            case READY:
                hideLoading();
                updateSendButtonState(true, getString(R.string.h_1535fcfa));
                break;

            case INFERRING:
                showLoading("AI 正在推理...", null);
                updateSendButtonState(false, getString(R.string.h_095e938e));
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
                title = getString(R.string.h_ab94e2c3);
                advice = error.retryable ? "点击重新初始化" : "请重启应用";
                break;
            case "TIMEOUT":
                title = getString(R.string.h_cd903f81);
                advice = "模型可能计算较慢，可点击重试或降低上下文长度";
                break;
            case "NATIVE_CRASH":
                title = getString(R.string.h_888bdf08);
                advice = "本地推理引擎崩溃，建议降低上下文长度或切换在线模型";
                break;
            case "MEMORY":
                title = getString(R.string.h_d8e6a633);
                advice = "可用内存不足，建议释放后台应用或使用更小的模型";
                break;
            case "CANCELLED":
                title = getString(R.string.h_14b4c1f2);
                advice = "可重新发送消息";
                break;
            default:
                title = getString(R.string.h_c9887c1b);
                advice = error.retryable ? "可点击重试" : "请重启应用";
                break;
        }

        // 显示带重试按钮的错误 Dialog
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("⚠️ " + title)
                .setMessage(error.message + "\n\n💡 " + advice)
                .setPositiveButton(error.retryable ? getString(R.string.h_132c5cdc) : getString(R.string.h_ce26955a), (d, w) -> {
                    if (error.retryable) {
                        chatViewModel.initialize();
                    }
                })
                .setNegativeButton(getString(R.string.h_b15d9127), null)
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
                .setTitle(getString(R.string.h_b32d465f))
                .setMessage(getString(R.string.h_7a17bf3a))
                .setPositiveButton(getString(R.string.h_64ca9bab), (d, w) -> {
                    chatViewModel.initialize();
                })
                .setNegativeButton(getString(R.string.h_87e4d9ef), null)
                .setCancelable(true)
                .show();
    }

    /**
     * 更新发送按钮状态（可点击/文本）
     */
    /** 刷新会话统计胶囊（dsh StatsPills 风格）：数据直接来自当前消息列表 */
    private void refreshSessionStats() {
        if (sessionStatsBar == null) return;
        sessionStatsBar.update(chatHistory);
        // 2026-09-23 修复：上下文百分比 pill 与统计条同步刷新——
        // 在线引擎走自己的消息回调（不走 chatViewModel.isGenerating），
        // 原 refreshContextMeter 只在旧版路径触发 → 在线对话下 pill 一直 0。
        // 并入统计刷新后，任何消息变化（发送/回复/恢复历史）都实时更新。
        refreshContextMeter();
    }
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
                    final int idx = resolveStreamingIndex();
                    if (idx < 0) return;
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
                    // 定时渲染：正文 UI 统一交给 StreamingUpdateManager（50~200ms 批量 + 去抖），
                    // 流式期间不再每 token 同步 msg.content/notifyItemChanged（否则变化检测失效且画面抽搐）。
                    // 完成事件（全文重发）或节流器不可用（异常/未初始化）时立即刷新兜底。
                    if (isComplete || streamingUpdateManager == null) {
                        ChatMessage msg = chatHistory.get(idx);
                        msg.content = contentSnapshot;
                        if (chatAdapter != null) {
                            chatAdapter.updateAIMessageContent(idx, contentSnapshot);
                        }
                    }
                });
            }

            @Override
            public void onThinkingStart() {
                runOnUiThread(() -> {
                    isInThinking = true;
                    final int idx = resolveStreamingIndex();
                    synchronized (streamingLock) {
                        if (currentThinkingContent == null) {
                            currentThinkingContent = new StringBuilder();
                        }
                        // 立即显示思考状态，让用户感知到模型在思考
                        if (currentThinkingContent.length() == 0 && idx >= 0) {
                            currentThinkingContent.append(getString(R.string.h_17c53a77));
                        }
                    }
                    if (idx >= 0) {
                        String snapshot;
                        synchronized (streamingLock) {
                            snapshot = currentThinkingContent != null ? currentThinkingContent.toString() : "";
                        }
                        ChatMessage msg = chatHistory.get(idx);
                        msg.thinkingContent = snapshot;
                        if (chatAdapter != null) {
                            chatAdapter.updateMessageThinkingContent(idx, snapshot);
                        }
                    }
                });
            }

            @Override
            public void onThinkingContent(String content) {
                runOnUiThread(() -> {
                    final int idx = resolveStreamingIndex();
                    if (idx < 0) return;
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
                    ChatMessage msg = chatHistory.get(idx);
                    msg.thinkingContent = snapshot;
                    // 定时渲染（120ms 批量），防每 token notify 导致思考区抽搐
                    scheduleThinkingRefresh(idx);
                });
            }

            @Override
            public void onThinkingEnd() {
                runOnUiThread(() -> {
                    isInThinking = false;
                    final int idx = resolveStreamingIndex();
                    if (idx < 0) return;
                    String snapshot;
                    synchronized (streamingLock) {
                        snapshot = currentThinkingContent != null ? currentThinkingContent.toString() : "";
                    }
                    ChatMessage msg = chatHistory.get(idx);
                    msg.thinkingContent = snapshot;
                    // 思考结束自动折叠，用户可点击重新展开
                    msg.thinkingExpanded = false;
                    // 取消待执行的思考节流刷新（此处全量 notify 已带最新 thinkingContent）
                    cancelThinkingRefresh();
                    if (chatAdapter != null) {
                        chatAdapter.notifyItemChanged(idx);
                    }
                });
            }

            @Override
            public void onToolCall(String toolName, org.json.JSONObject parameters) {
                runOnUiThread(() -> {
                    showToast(getString(R.string.h_06c7a8f8) + toolName);
                    // TODO: 显示工具调用 UI
                });
            }

            @Override
            public void onStructuredData(String dataType, org.json.JSONObject data) {
                runOnUiThread(() -> {
                    // 根据数据类型显示不同的 UI
                    if ("天气".equals(dataType)) {
                        // 显示天气卡片
                        showToast(getString(R.string.h_3963dabd));
                    } else if ("代码".equals(dataType)) {
                        // 显示代码块
                        showToast(getString(R.string.h_e3f38d79));
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    showToast(getString(R.string.h_7449367f) + error);
                    final int idx = resolveStreamingIndex();
                    if (idx >= 0) {
                        ChatMessage msg = chatHistory.get(idx);
                        msg.content = "错误: " + error;
                        refreshSessionStats();
                        msg.status = ChatMessage.MessageStatus.FAILED;
                        if (chatAdapter != null) {
                            chatAdapter.notifyItemChanged(idx);
                        }
                    }
                });
            }

            @Override
            public void onStreamComplete(String fullContent) {
                runOnUiThread(() -> {
                    final int idx = resolveStreamingIndex();
                    if (idx >= 0) {
                        ChatMessage msg = chatHistory.get(idx);
                        msg.content = fullContent;
                        refreshSessionStats();
                        msg.status = ChatMessage.MessageStatus.COMPLETED;
                        if (chatAdapter != null) {
                            chatAdapter.notifyItemChanged(idx);
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
            @Override public void onStartNewConversation() { startNewConversation(); }
        });
        if (drawerLayout != null && historyList != null) {
            historyController.init(drawerLayout, historyList);
            refreshHistoryDrawer();
        }

        // 4. NativeRecoveryHandler - 原生层恢复管理
        recoveryHandler = new NativeRecoveryHandler(this, uiHandler, new NativeRecoveryHandler.Callback() {
            @Override public void onRecoveryStarted(String message) { addSystemMessage(message); }
            @Override public void onRecoveryProgress(String message, int progress) { if (serviceStatusManager != null) serviceStatusManager.updateRecoveryProgress(message, progress); }
            @Override public void onRecoveryComplete(String message) { addSystemMessage(message); showToast(getString(R.string.h_307f6be3)); }
            @Override public void onRecoveryFailed(String error) { addErrorMessage("恢复失败", error, true); }
            @Override public void onAddSystemMessage(String message) { addSystemMessage(message); }
            @Override public void onShowToast(String message) { showToast(message); }
            @Override public void onTriggerAutoRecovery() { recoveryHandler.triggerAutoRecovery(); }
        });
        recoveryHandler.setAIService(aiService);
        recoveryHandler.setupListener();

        // 6. ChatInputManager - 输入管理（经 ChatInputBar 组件接线）
        ChatInputBar chatInputBar = findViewById(R.id.chat_input_bar);
        if (chatInputBar != null) {
            inputManager = chatInputBar.attachManager(this, new ChatInputManager.Callback() {
                @Override public void onSendMessage(String text) { sendMessage(); }
                @Override public void onAttachFile() { handleAttachFile(); }
                @Override public void onShowToast(String message) { showToast(message); }
            }, attachmentList);
        } else {
            // 兜底：布局未替换时走原路径
            inputManager = new ChatInputManager(this, new ChatInputManager.Callback() {
                @Override public void onSendMessage(String text) { sendMessage(); }
                @Override public void onAttachFile() { handleAttachFile(); }
                @Override public void onShowToast(String message) { showToast(message); }
            });
            if (inputMessage != null && btnSend != null && btnAttach != null && attachmentList != null) {
                inputManager.init(inputMessage, btnSend, btnAttach, attachmentList);
            }
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
                runOnUiThread(() -> showToast(getString(R.string.h_c8e5b7a6) + error));
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
                    
                    showToast(getString(R.string.h_cbae4f39));
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
            @Override public void onAddAIMessage(ChatMessage message) { chatHistory.add(message); if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1); scrollToBottom(true); refreshSessionStats(); }
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
    }

    @Override
    protected void initListener() {
        // 抽屉按钮：打开左侧工具中心（模型/深度思考/快捷工具/管理）
        if (btnBack != null) btnBack.setOnClickListener(v -> {
            if (drawerLayout != null) {
                drawerLayout.openDrawer(findViewById(R.id.tool_drawer));
            }
        });
        // 模式切换：点击直接在 普通 ↔ 深度思考 间切换（简化，不再弹复杂对话框）
        if (btnModeSelect != null) btnModeSelect.setOnClickListener(v -> toggleMode());
        if (btnModelSelect != null) {
            btnModelSelect.setOnClickListener(v -> {
                // 打开模型选择页面
                Intent intent = new Intent(AIChatActivity.this, ModelSelectorActivity.class);
                startActivity(intent);
            });
        }
        if (btnClearChat != null) {
            btnClearChat.setOnClickListener(v -> clearChat());
            // 长按：压缩对话（模型生成摘要，保留最近 8 条，长对话省 tokens）
            btnClearChat.setOnLongClickListener(v -> {
                showCompressConversationDialog();
                return true;
            });
        }
        if (btnStopGeneration != null) btnStopGeneration.setOnClickListener(v -> stopGeneration());
        if (btnSend != null) btnSend.setOnClickListener(v -> sendMessage());
        if (btnAttach != null) {
            btnAttach.setOnClickListener(v -> showAttachmentOptionsDialog());
        }
        if (btnVoice != null) {
            // 语音输入工具模式：点击弹出语音识别组件（录音对话框），完成后识别为文字填入输入框
            btnVoice.setOnClickListener(v -> startVoiceRecognitionTool());
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
        if (btnNewConversation != null) {
            btnNewConversation.setOnClickListener(v -> {
                if (drawerLayout != null) drawerLayout.closeDrawer(findViewById(R.id.history_drawer));
                startNewConversation();
            });
        }
        if (btnAgentManager != null) {
            btnAgentManager.setOnClickListener(v ->
                    startActivity(new Intent(AIChatActivity.this, AgentManagerActivity.class)));
        }
        if (btnAICenter != null) {
            btnAICenter.setOnClickListener(v ->
                    startActivity(new Intent(AIChatActivity.this, AICenterActivity.class)));
        }
        if (btnAIService != null) {
            btnAIService.setOnClickListener(v ->
                    startActivity(new Intent(AIChatActivity.this, AIServiceStatusActivity.class)));
        }
        if (btnRemoteDsh != null) {
            btnRemoteDsh.setOnClickListener(v -> {
                // 工具抽屉入口：先收起抽屉再进连接界面
                if (drawerLayout != null) {
                    drawerLayout.closeDrawer(findViewById(R.id.history_drawer));
                }
                startActivity(new Intent(AIChatActivity.this,
                        com.oilquiz.app.ai.tool.RemoteDshConnectActivity.class));
            });
        }
        if (btnModelDownload != null) {
            btnModelDownload.setOnClickListener(v ->
                    startActivity(new Intent(AIChatActivity.this, ModelDownloadActivity.class)));
        }
        // AI 服务一键初始化：本地与在线均未配置时提供，点击进入精美引导界面
        if (btnAiInit != null) {
            updateAiInitButtonVisibility();
            btnAiInit.setOnClickListener(v -> {
                if (!AIServiceInitializer.needsInitialization(this)) {
                    updateAiInitButtonVisibility();
                    return;
                }
                startActivity(new Intent(AIChatActivity.this, AIServiceInitActivity.class));
            });
        }
        if (btnClearAllHistory != null) {
            btnClearAllHistory.setOnClickListener(v -> {
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.h_2e5be76a))
                    .setMessage(getString(R.string.h_fadd8853))
                    .setPositiveButton(getString(R.string.h_288f0c40), (dialog, which) -> {
                        clearChat();
                        refreshHistoryDrawer();
                        if (drawerLayout != null) drawerLayout.closeDrawer(findViewById(R.id.history_drawer));
                        showToast(getString(R.string.h_3683077f));
                    })
                    .setNegativeButton(getString(R.string.h_625fb26b), null)
                    .show();
            });
        }

        // 快捷工具 chip 模块：统一绑定抽屉静态 chip 引导 + 动态创建聚合方案 chip
        com.google.android.material.chip.ChipGroup quickGroup = findViewById(R.id.quick_actions_chip_group);
        quickToolModule = new QuickToolChipModule(this, new QuickToolChipModule.Callback() {
            @Override
            public void onToolGuide(String toolId) {
                showToolGuideDialog(toolId);
            }

            @Override
            public void onCompositeGuide(String flowId) {
                showCompositeGuideDialog(flowId);
            }

            @Override
            public void onClearChat() {
                clearChat();
            }
        });
        quickToolModule.bindStaticToolChips(
                findViewById(R.id.chip_weather),
                findViewById(R.id.chip_search),
                findViewById(R.id.chip_database),
                findViewById(R.id.chip_file),
                findViewById(R.id.chip_location),
                findViewById(R.id.chip_app),
                findViewById(R.id.chip_calc),
                findViewById(R.id.chip_clear_chat2));
        quickToolModule.addQuickChips(quickGroup);

        // 深度思考开关（对齐官方：独立开关，点击开↔关，开启高亮主色/关闭灰色）
        Chip chipDeepThink = findViewById(R.id.chip_deep_think);

        if (chipDeepThink != null) {
            chipDeepThink.setChecked(ChatModeManager.getInstance(this).isDeepThinkingEnabled());
            updateDeepThinkChip(chipDeepThink);
            chipDeepThink.setOnClickListener(v -> {
                if (isGenerating) {
                    showToast(getString(R.string.h_15261c3c));
                    chipDeepThink.setChecked(ChatModeManager.getInstance(this).isDeepThinkingEnabled());
                    return;
                }
                ChatModeManager manager = ChatModeManager.getInstance(this);
                boolean wasEnabled = manager.isDeepThinkingEnabled();
                boolean next = !wasEnabled;
                if (manager.setDeepThinkingEnabled(next)) {
                    injectModeSwitchInstruction(
                            wasEnabled ? ChatModeManager.ChatMode.DEEP_THINKING : ChatModeManager.ChatMode.NORMAL,
                            next ? ChatModeManager.ChatMode.DEEP_THINKING : ChatModeManager.ChatMode.NORMAL);
                }
                chipDeepThink.setChecked(next);
                updateDeepThinkChip(chipDeepThink);
                updateModeButtonText();
                showToast(next ? getString(R.string.h_72bc1b1d) : getString(R.string.h_814eed44));
            });
        }

        // 空状态快捷操作
        if (emptyStateChips != null) {
            com.google.android.material.chip.Chip chipExample1 = emptyStateChips.findViewById(R.id.chip_empty_example1);
            com.google.android.material.chip.Chip chipExample2 = emptyStateChips.findViewById(R.id.chip_empty_example2);
            com.google.android.material.chip.Chip chipExample3 = emptyStateChips.findViewById(R.id.chip_empty_example3);
            com.google.android.material.chip.Chip chipClearEmpty = emptyStateChips.findViewById(R.id.chip_clear_chat);
            if (chipExample1 != null) chipExample1.setOnClickListener(v -> {
                inputMessage.setText(getString(R.string.h_0d5a55ab));
                sendMessage();
            });
            if (chipExample2 != null) chipExample2.setOnClickListener(v -> {
                inputMessage.setText(getString(R.string.h_3985673f));
                sendMessage();
            });
            if (chipExample3 != null) chipExample3.setOnClickListener(v -> {
                inputMessage.setText(getString(R.string.h_b1d10199));
                sendMessage();
            });
            if (chipClearEmpty != null) chipClearEmpty.setOnClickListener(v -> {
                clearChat();
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
            showToast(getString(R.string.h_a45bac47));
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
            android.widget.Toast.makeText(this, getString(R.string.h_7c0a7cec), android.widget.Toast.LENGTH_SHORT).show();
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
        titleView.setText(flow.toolDisplayName + getString(R.string.h_1d081944) + (currentStepIdx[0] + 1) + "/" + activeSteps.size());
        titleView.setTextSize(16);
        titleView.setTextColor(ThemeColors.get(R.color.hc_ff333333));
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleView.setLayoutParams(titleParams);
        headerLayout.addView(titleView);

        if (currentStepIdx[0] > 0) {
            android.widget.Button prevBtn = new android.widget.Button(this);
            prevBtn.setText(getString(R.string.h_eeb69088));
            prevBtn.setBackgroundColor(ThemeColors.get(R.color.hc_ffeeeeee));
            prevBtn.setTextColor(ThemeColors.get(R.color.hc_ff666666));
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
        divider.setBackgroundColor(ThemeColors.get(R.color.hc_ffe0e0e0));
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
            ctxBg.setColor(ThemeColors.get(R.color.hc_ffe3f2fd));
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
            ctxText.setTextColor(ThemeColors.get(R.color.hc_ff1565c0));
            ctxText.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            ctxBar.addView(ctxText);

            // 右侧：修改按钮（清空自动上下文，回到手动输入）
            TextView modifyBtn = new TextView(this);
            modifyBtn.setText(getString(R.string.h_8347a927));
            modifyBtn.setTextSize(13);
            modifyBtn.setTextColor(ThemeColors.get(R.color.hc_ff1565c0));
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
            stepTitle.setText(getString(R.string.h_d575bfd2));
            stepTitle.setTextSize(15);
            stepTitle.setTextColor(ThemeColors.get(R.color.hc_ff3f51b5));
            stepTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            container.addView(stepTitle);
        } else {
            TextView stepTitle = new TextView(this);
            stepTitle.setText(step.title);
            stepTitle.setTextSize(15);
            stepTitle.setTextColor(ThemeColors.get(R.color.hc_ff3f51b5));
            stepTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            container.addView(stepTitle);

            if (step.description != null && !step.description.isEmpty()) {
                TextView descView = new TextView(this);
                descView.setText(step.description);
                descView.setTextSize(13);
                descView.setTextColor(ThemeColors.get(R.color.hc_ff666666));
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
                    cardBg.setColor(ThemeColors.get(R.color.hc_ffffffff));
                    cardBg.setCornerRadius(dp(16));
                    cardBg.setStroke(dp(1), ThemeColors.get(R.color.hc_ffe0e0e0));
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
                    labelTv.setTextColor(ThemeColors.get(R.color.hc_ff333333));
                    labelTv.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    card.addView(labelTv);

                    // 若该选项已被选中，使用选中色高亮
                    if (opt.value.equals(selectedParams.get(step.paramKey))) {
                        cardBg.setColor(ThemeColors.get(R.color.hc_ffe8eaf6));
                    }

                    card.setOnClickListener(v -> {
                        // 选中瞬间高亮，记录选择后重新计算 activeSteps 并前进
                        cardBg.setColor(ThemeColors.get(R.color.hc_ffe8eaf6));
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
            editText.setHint(step.hint != null ? step.hint : getString(R.string.h_02cc4f8f));
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
                loadingView.setText(getString(R.string.h_b5e17536));
                loadingView.setTextSize(12);
                loadingView.setTextColor(ThemeColors.attr(this, R.attr.colorControlTextSecondary));
                optionsBox.addView(loadingView);

                final ToolGuideFlow.GuideStep.DynamicOptionsSpec spec = step.dynamicOptions;
                new Thread(() -> {
                    java.util.List<String> values = loadGuideDynamicOptions(spec);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        optionsBox.removeAllViews();
                        if (values == null || values.isEmpty()) {
                            TextView failView = new TextView(this);
                            failView.setText(getString(R.string.h_561c8d80));
                            failView.setTextSize(12);
                            failView.setTextColor(ThemeColors.attr(this, R.attr.colorControlTextSecondary));
                            optionsBox.addView(failView);
                            return;
                        }
                        TextView tipView = new TextView(this);
                        tipView.setText(getString(R.string.h_5aff2265));
                        tipView.setTextSize(12);
                        tipView.setTextColor(ThemeColors.attr(this, R.attr.colorControlTextSecondary));
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
                            chip.setText(val.isEmpty() ? (spec.allOptionLabel != null ? spec.allOptionLabel : getString(R.string.h_a8b0c204)) : val);
                            chip.setTextSize(13);
                            chip.setTextColor(ThemeColors.get(R.color.hc_ff333333));
                            chip.setPadding(dp(12), dp(10), dp(12), dp(10));
                            android.graphics.drawable.GradientDrawable chipBg = new android.graphics.drawable.GradientDrawable();
                            chipBg.setColor(ThemeColors.get(R.color.hc_fff5f5f5));
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
                            moreView.setText(getString(R.string.h_3d2b3f43) + values.size() + getString(R.string.h_ccc34a10));
                            moreView.setTextSize(11);
                            moreView.setTextColor(ThemeColors.get(R.color.hc_ffaaaaaa));
                            optionsBox.addView(moreView);
                        }
                    });
                }).start();
            }

            // 下一步按钮
            android.widget.Button nextBtn = new android.widget.Button(this);
            nextBtn.setText(getString(R.string.h_38ce27d8));
            nextBtn.setBackgroundColor(ThemeColors.attr(this, R.attr.colorPrimary));
            nextBtn.setTextColor(ThemeColors.get(R.color.hc_ffffffff));
            LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nextLp.topMargin = 24;
            nextBtn.setLayoutParams(nextLp);
            nextBtn.setOnClickListener(v -> {
                String value = editText.getText().toString().trim();
                if (step.required && value.isEmpty()) {
                    android.widget.Toast.makeText(this, getString(R.string.h_879e7d1e), android.widget.Toast.LENGTH_SHORT).show();
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
                valueView.setText(getString(R.string.h_79203de5));
            }
            valueView.setTextSize(12);
            valueView.setTextColor(ThemeColors.get(R.color.hc_ff555555));
            valueView.setMaxLines(3);
            valueView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            int padDp = (int) (12 * getResources().getDisplayMetrics().density);
            valueView.setPadding(padDp, padDp / 2, padDp, padDp / 2);
            valueView.setBackgroundColor(ThemeColors.get(R.color.hc_fff5f5f5));
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
            manualBtn.setText(getString(R.string.h_732f6f55));
            LinearLayout.LayoutParams mbLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            manualBtn.setLayoutParams(mbLp);
            manualBtn.setOnClickListener(v -> showManualPathInput(step, valueView, pickerBtnRef));
            btnBar.addView(manualBtn);
            container.addView(btnBar);

            // 3. 下一步按钮（与 INPUT 分支一致，使用同一个 selectedParams 聚合）
            android.widget.Button nextBtn = new android.widget.Button(this);
            nextBtn.setText(getString(R.string.h_38ce27d8));
            nextBtn.setBackgroundColor(ThemeColors.attr(this, R.attr.colorPrimary));
            nextBtn.setTextColor(ThemeColors.get(R.color.hc_ffffffff));
            LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nextLp.topMargin = padDp * 2;
            nextBtn.setLayoutParams(nextLp);
            nextBtn.setOnClickListener(v -> {
                String value = (step.paramValue != null) ? step.paramValue : "";
                value = value.trim();
                if (step.required && value.isEmpty()) {
                    android.widget.Toast.makeText(this, getString(R.string.h_c9ca3cf6), android.widget.Toast.LENGTH_SHORT).show();
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
                sb.append(getString(R.string.h_c9f8beec));
            } else {
                for (Map.Entry<String, String> entry : selectedParams.entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                    }
                }
            }
            summaryView.setText(sb.toString().trim());
            summaryView.setTextSize(13);
            summaryView.setTextColor(ThemeColors.get(R.color.hc_ff333333));
            LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sumLp.topMargin = 16;
            summaryView.setLayoutParams(sumLp);
            container.addView(summaryView);

            // 执行工具按钮
            android.widget.Button execBtn = new android.widget.Button(this);
            execBtn.setText(getString(R.string.h_ce4655cc));
            execBtn.setBackgroundColor(ThemeColors.attr(this, R.attr.colorPrimary));
            execBtn.setTextColor(ThemeColors.get(R.color.hc_ffffffff));
            LinearLayout.LayoutParams execLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            execLp.topMargin = 24;
            execBtn.setLayoutParams(execLp);
            execBtn.setOnClickListener(v -> {
                // 防重复点击：首次点击后禁用按钮，避免并行重复执行工具
                if (!execBtn.isEnabled()) return;
                execBtn.setEnabled(false);
                execBtn.setText(getString(R.string.h_71b56fd8));

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

                final String finalToolName = toolName;
                // 执行前检查缺失的环境上下文
                List<String> missing = ToolContextProvider.getMissingContext(flow, execParams);
                if (missing.contains("location") && "ai_weather".equals(toolName)) {
                    // 缺少位置且为天气工具：异步定位后再执行
                    execBtn.setText(getString(R.string.h_6db9502a));
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
                            runGuideToolExecution(dialog, finalToolName, execParams);
                        }
                        @Override
                        public void onLocationFailed(String error) {
                            // 定位失败：恢复按钮，提示用户手动输入，不执行工具
                            execBtnRef.setEnabled(true);
                            execBtnRef.setText(getString(R.string.h_ce4655cc));
                            android.widget.Toast.makeText(AIChatActivity.this,
                                getString(R.string.h_fff71a52), android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }

                // 无缺失上下文，直接执行
                runGuideToolExecution(dialog, finalToolName, execParams);
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
        dialog.setTitle(getString(R.string.h_b573da98));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        TextView titleView = new TextView(this);
        titleView.setText(getString(R.string.h_1d3f6786));
        titleView.setTextSize(15);
        titleView.setTextColor(ThemeColors.get(R.color.hc_ff333333));
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
            label.setTextColor(ThemeColors.get(R.color.hc_ff666666));
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
                tempStep.hint = getString(R.string.h_1368c593);
                tempStep.multiline = false;
                if (params.containsKey(mp.key) && params.get(mp.key) != null) {
                    tempStep.paramValue = String.valueOf(params.get(mp.key));
                }
                missingParamPickerSteps.put(mp.key, tempStep);

                final TextView valueView = new TextView(this);
                valueView.setText((tempStep.paramValue != null && !tempStep.paramValue.isEmpty())
                        ? tempStep.paramValue : "（尚未选择）");
                valueView.setTextSize(12);
                valueView.setTextColor(ThemeColors.get(R.color.hc_ff555555));
                valueView.setMaxLines(3);
                valueView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                int padDp = (int) (12 * getResources().getDisplayMetrics().density);
                valueView.setPadding(padDp, padDp / 2, padDp, padDp / 2);
                valueView.setBackgroundColor(ThemeColors.get(R.color.hc_fff5f5f5));
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
                manualBtn.setText(getString(R.string.h_732f6f55));
                LinearLayout.LayoutParams mbLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                manualBtn.setLayoutParams(mbLp);
                manualBtn.setOnClickListener(v -> showManualPathInput(tempStep, valueView, pickerBtnRef));
                btnBar.addView(manualBtn);
                layout.addView(btnBar);
            } else {
                // 普通文本输入
                EditText input = new EditText(this);
                input.setHint(getString(R.string.h_02cc4f8f) + mp.description);
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
        submitBtn.setText(getString(R.string.h_dc4d6f05));
        submitBtn.setBackgroundColor(ThemeColors.attr(this, R.attr.colorPrimary));
        submitBtn.setTextColor(ThemeColors.get(R.color.hc_ffffffff));
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
                android.widget.Toast.makeText(this, getString(R.string.h_4730b746), android.widget.Toast.LENGTH_SHORT).show();
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

    /**
     * 显示聚合引导方案对话框：展示方案介绍 + 步骤预览 + "开始执行"按钮。
     *
     * @param flowId 聚合流程ID（如 "go_out"）
     */
    private void showCompositeGuideDialog(String flowId) {
        final CompositeGuideFlow flow = CompositeGuideFlow.getFlow(flowId);
        if (flow == null) {
            android.widget.Toast.makeText(this, getString(R.string.h_f97bc2aa), android.widget.Toast.LENGTH_SHORT).show();
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
        titleView.setTextColor(ThemeColors.get(R.color.hc_ff333333));
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        container.addView(titleView);

        // 方案描述
        if (flow.description != null && !flow.description.isEmpty()) {
            TextView descView = new TextView(this);
            descView.setText(flow.description);
            descView.setTextSize(13);
            descView.setTextColor(ThemeColors.get(R.color.hc_ff666666));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = 8;
            descView.setLayoutParams(dlp);
            container.addView(descView);
        }

        // 分隔线
        View divider = new View(this);
        divider.setBackgroundColor(ThemeColors.get(R.color.hc_ffe0e0e0));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divLp.topMargin = 16;
        divLp.bottomMargin = 16;
        divider.setLayoutParams(divLp);
        container.addView(divider);

        // 步骤列表预览
        TextView stepsTitle = new TextView(this);
        stepsTitle.setText(getString(R.string.h_00e6a7fc));
        stepsTitle.setTextSize(14);
        stepsTitle.setTextColor(ThemeColors.get(R.color.hc_ff3f51b5));
        stepsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        container.addView(stepsTitle);
        if (flow.steps != null) {
            for (int i = 0; i < flow.steps.size(); i++) {
                CompositeGuideFlow.CompositeStep s = flow.steps.get(i);
                TextView stepView = new TextView(this);
                stepView.setText((i + 1) + ". " + (s.icon != null ? s.icon + " " : "") + s.actionDescription);
                stepView.setTextSize(14);
                stepView.setTextColor(ThemeColors.get(R.color.hc_ff333333));
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                slp.topMargin = 8;
                stepView.setLayoutParams(slp);
                container.addView(stepView);
            }
        }

        // 开始执行按钮
        android.widget.Button startBtn = new android.widget.Button(this);
        startBtn.setText(getString(R.string.h_6c710397));
        startBtn.setBackgroundColor(ThemeColors.attr(this, R.attr.colorPrimary));
        startBtn.setTextColor(ThemeColors.get(R.color.hc_ffffffff));
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
        titleView.setText("🔧 " + step.actionDescription + getString(R.string.h_d7e9fc99));
        titleView.setTextSize(15);
        titleView.setTextColor(ThemeColors.get(R.color.hc_ff333333));
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(titleView);

        final java.util.Map<String, EditText> inputs = new java.util.HashMap<>();
        for (ToolErrorRecovery.MissingParam mp : userParams) {
            TextView label = new TextView(this);
            label.setText(mp.description);
            label.setTextSize(13);
            label.setTextColor(ThemeColors.get(R.color.hc_ff666666));
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            labelLp.topMargin = 16;
            label.setLayoutParams(labelLp);
            layout.addView(label);

            EditText input = new EditText(this);
            input.setHint(getString(R.string.h_02cc4f8f) + mp.description);
            input.setTextSize(14);
            LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            inputLp.topMargin = 4;
            input.setLayoutParams(inputLp);
            layout.addView(input);
            inputs.put(mp.key, input);
        }

        android.widget.Button submitBtn = new android.widget.Button(this);
        submitBtn.setText(getString(R.string.h_dc4d6f05));
        submitBtn.setBackgroundColor(ThemeColors.attr(this, R.attr.colorPrimary));
        submitBtn.setTextColor(ThemeColors.get(R.color.hc_ffffffff));
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
                android.widget.Toast.makeText(this, getString(R.string.h_242cc727), android.widget.Toast.LENGTH_SHORT).show();
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
        titleView.setTextColor(ThemeColors.get(R.color.hc_ff333333));
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        container.addView(titleView);

        // 描述
        if (gs.description != null && !gs.description.isEmpty()) {
            TextView descView = new TextView(this);
            descView.setText(gs.description);
            descView.setTextSize(13);
            descView.setTextColor(ThemeColors.get(R.color.hc_ff666666));
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
                    cardBg.setColor(ThemeColors.get(R.color.hc_ffffffff));
                    cardBg.setCornerRadius(dp(16));
                    cardBg.setStroke(dp(1), ThemeColors.get(R.color.hc_ffe0e0e0));
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
                    labelTv.setTextColor(ThemeColors.get(R.color.hc_ff333333));
                    labelTv.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    card.addView(labelTv);

                    card.setOnClickListener(v -> {
                        cardBg.setColor(ThemeColors.get(R.color.hc_ffe8eaf6));
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
            editText.setHint(gs.hint != null ? gs.hint : getString(R.string.h_02cc4f8f));
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
            nextBtn.setText(getString(R.string.h_38ce27d8));
            nextBtn.setBackgroundColor(ThemeColors.attr(this, R.attr.colorPrimary));
            nextBtn.setTextColor(ThemeColors.get(R.color.hc_ffffffff));
            LinearLayout.LayoutParams nbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nbLp.topMargin = dp(24);
            nextBtn.setLayoutParams(nbLp);
            nextBtn.setOnClickListener(v -> {
                String value = editText.getText().toString().trim();
                if (gs.required && value.isEmpty()) {
                    android.widget.Toast.makeText(this, getString(R.string.h_879e7d1e), android.widget.Toast.LENGTH_SHORT).show();
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
                valueView.setText(getString(R.string.h_79203de5));
            }
            valueView.setTextSize(12);
            valueView.setTextColor(ThemeColors.get(R.color.hc_ff555555));
            valueView.setMaxLines(3);
            valueView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            int padDp = dp(12);
            valueView.setPadding(padDp, padDp / 2, padDp, padDp / 2);
            valueView.setBackgroundColor(ThemeColors.get(R.color.hc_fff5f5f5));
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
            manualBtn.setText(getString(R.string.h_732f6f55));
            LinearLayout.LayoutParams mbLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            manualBtn.setLayoutParams(mbLp);
            manualBtn.setOnClickListener(v -> showManualPathInput(gs, valueView, pickerBtnRef));
            btnBar.addView(manualBtn);
            container.addView(btnBar);

            android.widget.Button nextBtn = new android.widget.Button(this);
            nextBtn.setText(getString(R.string.h_38ce27d8));
            nextBtn.setBackgroundColor(ThemeColors.attr(this, R.attr.colorPrimary));
            nextBtn.setTextColor(ThemeColors.get(R.color.hc_ffffffff));
            LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nextLp.topMargin = padDp * 2;
            nextBtn.setLayoutParams(nextLp);
            nextBtn.setOnClickListener(v -> {
                String value = (gs.paramValue != null) ? gs.paramValue : "";
                value = value.trim();
                if (gs.required && value.isEmpty()) {
                    android.widget.Toast.makeText(this, getString(R.string.h_c9ca3cf6), android.widget.Toast.LENGTH_SHORT).show();
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
        AppLogger.ai(TAG, "[sendMessage] 入口: isGenerating=" + isGenerating
                + ", inputLen=" + (inputMessage != null ? inputMessage.getText().length() : 0));
        if (isGenerating) {
            // dsh 对齐：忙时按用户偏好处理（block 拒绝 / queue 排队 / steer 打断重发）
            AppLogger.aiW(TAG, "[sendMessage] 忙时处理: mode=" + busySendMode);
            String msg = inputMessage.getText().toString().trim();
            if (msg.isEmpty()) { showToast(getString(R.string.h_b922f77a)); return; }
            if ("queue".equals(busySendMode)) {
                pendingQueue.add(msg);
                inputMessage.setText("");
                updateQueueBar();
                persistPendingQueue();
                showToast("已加入发送队列，当前回复完成后自动发送");
                return;
            }
            if ("steer".equals(busySendMode)) {
                cancelGeneration();
                // 取消后继续走下方正常发送流程（stopGeneration 已复位 isGenerating）
            } else {
                showToast(getString(R.string.h_05582e8e));
                return;
            }
        }
        // 新一轮对话开始：清空上一轮残留的工具组件收集，避免串轮
        com.oilquiz.app.ai.chat.component.ComponentCollector.clear();
        String message = inputMessage.getText().toString().trim();

        // 统一从 inputManager 取附件（图库/拍照/录音都经它添加，避免两套列表不一致导致附件丢失）
        List<ChatMessage.Attachment> savedAttachments = inputManager != null
                ? inputManager.getCurrentAttachments()
                : new ArrayList<>(currentAttachments);

        // 图片附件走 OCR 工具 + 在线模型分析，不依赖本地模型加载状态
        boolean hasImageAttachment = false;
        for (ChatMessage.Attachment att : savedAttachments) {
            if ("image".equals(att.type)) { hasImageAttachment = true; break; }
        }

        // 允许"无文字直接发送图片"（拍照后直接点发送）
        if (message.isEmpty() && savedAttachments.isEmpty()) { showToast(getString(R.string.h_b922f77a)); return; }

        if (!hasImageAttachment && !ensureModelLoaded(message)) {
            AppLogger.aiW(TAG, "[sendMessage] ensureModelLoaded 返回 false，仅加入历史不推理，msg=" + message);
            addUserMessage(message);
            inputMessage.setText("");
            return;
        }
        AppLogger.ai(TAG, "[sendMessage] 通过守卫，进入 processChatMessage: " + message);

        if (!savedAttachments.isEmpty()) {
            String userContent = message.isEmpty() ? DEFAULT_ATTACHMENT_MESSAGE : message;
            ChatMessage userMessage = ChatMessage.createUserMessage(userContent, savedAttachments);
            if (pendingVoiceInputSource) {
                userMessage.voiceInput = true;
                pendingVoiceInputSource = false;
            }
            chatHistory.add(userMessage);
            if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1);
            scrollToBottom(true);
            saveHistoryAsync();
            if (inputManager != null) inputManager.clearAttachments();
            currentAttachments.clear();
            resetAttachmentAdapter();
        } else {
            boolean fromVoice = pendingVoiceInputSource;
            pendingVoiceInputSource = false;
            addUserMessage(message, fromVoice);
        }

        inputMessage.setText("");

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
            // dsh 对齐：发送前插入 SystemPromptRow（prompt 变更时）/ ContextInjectionRow（本地 Agent 记忆任务注入）
            maybeInsertSystemPromptRow();
            maybeInsertContextInjectionRow();
            processChatMessage(message);
        }

        // 深度思考开关保持用户设定：打开后持续生效，不随本条消息发送后自动复位
        // （用户偏好：开了就保持，深度思考影响后续所有消息）
    }

    private void processMessageWithAttachmentsViaAgent(String originalMessage, List<ChatMessage.Attachment> attachments) {
        // 同步捕获本条消息的深度思考开关（开关保持用户设定，不自动复位）
        boolean thinkingFlag = false;
        try {
            thinkingFlag = com.oilquiz.app.ai.chat.ChatModeManager.getInstance(this).isDeepThinkingEnabled();
        } catch (Exception ignored) {
        }
        final boolean visionThinkingEnabled = thinkingFlag;
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
            showToast(getString(R.string.h_a3e17914) + skippedFiles.size() + getString(R.string.h_7c645c81));
        }

        if (filtered.isEmpty()) {
            showToast(getString(R.string.h_8cc7a175));
            processChatMessage(originalMessage);
            return;
        }

        showToast(getString(R.string.h_d3430199));

        List<Uri> uris = new ArrayList<>();
        for (ChatMessage.Attachment att : filtered) {
            uris.add(Uri.parse(att.url));
        }

        saveAttachmentsToLocal(uris).thenAccept(localFileMap -> {
            // 本地多模态优先：vision 模型 + mmproj 已加载 + 单张图片 → 直接视觉理解（不走 OCR/Agent）
            // 在线模型场景：不走此分支（在线走 OCR 文本 + Agent，见下方注释）
            boolean allImages = !filtered.isEmpty();
            for (ChatMessage.Attachment att : filtered) {
                if (!"image".equals(att.type)) { allImages = false; break; }
            }
            boolean multimodalReady = false;
            try {
                multimodalReady = LlamaHelper.isMultimodalLoaded() && LlamaHelper.isModelInitialized();
            } catch (Exception ignored) {}
            // 本地模型已加载但视觉未就绪（供 OCR 兜底提示使用，final 以便 lambda 捕获）
            final boolean localVisionNotReady = !multimodalReady && LlamaHelper.isModelInitialized();
            if (allImages && multimodalReady && filtered.size() == 1) {
                handleMultimodalImage(filtered.get(0), localFileMap, originalMessage, visionThinkingEnabled);
                return;
            }

            // 在线多模态：在线视觉模型 + 单图 → 直接看图（base64 注入 OpenAI 兼容消息），不走 OCR
            if (allImages && filtered.size() == 1 && isOnlineVisionModel()) {
                handleOnlineMultimodalImage(filtered.get(0), localFileMap, originalMessage);
                return;
            }

            runOnUiThread(() -> {
                // 1. 先展示上传状态（状态闭环起点：文件已落盘，开始解析）
                StringBuilder displayMsg = new StringBuilder();
                displayMsg.append(getString(R.string.h_5f76df07)).append(filtered.size()).append(getString(R.string.h_0cabf767));
                if (!skippedFiles.isEmpty()) {
                    displayMsg.append(getString(R.string.h_10aace1e)).append(String.join(", ", skippedFiles)).append("\n\n");
                }
                for (ChatMessage.Attachment att : filtered) {
                    Uri uri = Uri.parse(att.url);
                    boolean saved = localFileMap.get(uri) != null;
                    displayMsg.append("• ").append(att.name)
                            .append("（").append(formatFileSize(att.size)).append("）")
                            .append(saved ? getString(R.string.h_d7610fbf) : getString(R.string.h_21c163e1))
                            .append("\n");
                }
                // 本地模型已加载但视觉未就绪时，明确提示走 OCR 的原因（避免静默降级困惑）
                if (localVisionNotReady) {
                    displayMsg.append(getString(R.string.h_96262f69));
                }
                displayMsg.append(getString(R.string.h_a791b767));

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
                            int sysPos = chatHistory.indexOf(sysMsg);
                            if (chatAdapter != null && sysPos >= 0) {
                                chatAdapter.notifyItemChanged(sysPos);
                            }
                            scrollToBottom();
                        });
                    }

                    @Override
                    public void onAllCompleted(List<com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult> results) {
                        runOnUiThread(() -> {
                            // 3. 解析完成：展示确定性结果（成功/部分成功/失败+原因）
                            sysMsg.content = buildParseSummaryMsg(filtered, results).toString();
                            // indexOf 可能返回 -1（消息被删/清空）→ 保护后再通知，避免负位置 op
                            int sysPos = chatHistory.indexOf(sysMsg);
                            if (chatAdapter != null && sysPos >= 0) {
                                chatAdapter.notifyItemChanged(sysPos);
                            }
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
                                ocrMsg.append(getString(R.string.h_f984477c));
                                for (com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult r : results) {
                                    if (r.isUsable() && r.content != null) {
                                        String snippet = r.content.trim();
                                        if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "...";
                                        ocrMsg.append("• ").append(getAttachmentNameById(filtered, r.attachmentId))
                                                .append("：").append(snippet).append("\n");
                                    }
                                }
                                ocrMsg.append(getString(R.string.h_76c53bd7));
                                sysMsg.content = ocrMsg.toString();
                                sysPos = chatHistory.indexOf(sysMsg);
                            if (chatAdapter != null && sysPos >= 0) {
                                chatAdapter.notifyItemChanged(sysPos);
                            }
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
        sb.append(getString(R.string.h_5f76df07)).append(filtered.size()).append(getString(R.string.h_0cabf767));
        if (skippedFiles != null && !skippedFiles.isEmpty()) {
            sb.append(getString(R.string.h_10aace1e)).append(String.join(", ", skippedFiles)).append("\n\n");
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
        sb.append(getString(R.string.h_6cefb6c9)).append(doneCount).append("/").append(totalCount).append("）...");
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
        sb.append(getString(R.string.h_7341b0bb));
        for (ChatMessage.Attachment att : filtered) {
            com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult r = map.get(att.id);
            sb.append("• ").append(att.name);
            if (r == null) {
                sb.append(getString(R.string.h_eca8e315));
            } else if (r.isUsable()) {
                sb.append(" → ✅ ").append(r.status == com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseStatus.PARTIAL_SUCCESS ? getString(R.string.h_d5c4fa06) : getString(R.string.h_53de0123));
                sb.append("（").append(methodLabel(r.method)).append("）");
                if (r.fromCache) sb.append(getString(R.string.h_3b870198));
                if (r.errorMessage != null) sb.append("：").append(r.errorMessage);
                sb.append("\n");
            } else {
                sb.append(getString(R.string.h_f3ab0bec)).append(r.errorMessage != null ? r.errorMessage : getString(R.string.h_974e7484)).append("\n");
            }
        }
        sb.append(getString(R.string.h_1678e347));
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
        sb.append(getString(R.string.h_793176ee)).append(originalMessage).append("\n\n");
        sb.append(getString(R.string.h_11214c64));

        int successCount = 0;
        int idx = 1;
        for (ChatMessage.Attachment att : attachments) {
            Uri uri = Uri.parse(att.url);
            String localPath = localFileMap.get(uri);
            com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult r =
                    parseResults != null ? parseResults.get(att.id) : null;

            sb.append(getString(R.string.h_94e069c2)).append(idx).append("】").append(att.name).append("\n");
            sb.append(getString(R.string.h_1ee53933)).append(att.type).append(getString(R.string.h_176d6e45)).append(formatFileSize(att.size)).append("\n");
            if (localPath != null) {
                sb.append(getString(R.string.h_28c797b8)).append(localPath).append("\n");
            }

            if (r != null && r.isUsable()) {
                successCount++;
                sb.append(getString(R.string.h_764fab2b)).append(r.status == com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseStatus.PARTIAL_SUCCESS
                        ? "PARTIAL_SUCCESS" : "SUCCESS").append("\n");
                sb.append(getString(R.string.h_5f27c9db)).append(r.method).append(r.fromCache ? getString(R.string.h_2d8ed504) : "").append("\n");
                if (r.errorMessage != null) {
                    sb.append(getString(R.string.h_46bc48a9)).append(r.errorMessage).append("\n");
                }
                sb.append(getString(R.string.h_1e7d25f8));
                sb.append(getString(R.string.h_24984e0c));
                String content = r.content;
                int maxLen = 12000;
                if (content.length() > maxLen) {
                    sb.append(content, 0, maxLen);
                    sb.append(getString(R.string.h_55a2a38e)).append(content.length())
                      .append(getString(R.string.h_6b35290e));
                } else {
                    sb.append(content).append("\n");
                }
                sb.append(getString(R.string.h_dae5a2ac));
            } else {
                // 失败：明确错误码与原因，禁止模型笼统说"未检测到附件"
                sb.append(getString(R.string.h_bf63260f));
                if (r != null) {
                    sb.append(getString(R.string.h_5d89be82)).append(r.errorCode).append("\n");
                    sb.append(getString(R.string.h_41d16b3d)).append(r.errorMessage).append("\n");
                    if (localPath != null) {
                        sb.append(getString(R.string.h_cad33dfa));
                    }
                } else {
                    sb.append(getString(R.string.h_03670db5));
                }
            }
            sb.append("\n");
            idx++;
        }

        if (skippedFiles != null && !skippedFiles.isEmpty()) {
            sb.append(getString(R.string.h_d7824330)).append(String.join(", ", skippedFiles)).append("\n\n");
        }

        sb.append(getString(R.string.h_9e0ee86a));
        if (successCount > 0) {
            sb.append(getString(R.string.h_ee31e1e3)).append(successCount).append(getString(R.string.h_fe85860b));
        }
        sb.append(getString(R.string.h_f6a98d86));
        sb.append(getString(R.string.h_447064b9));
        sb.append(getString(R.string.h_30890ceb));

        sb.append(getString(R.string.h_b07b6bc9));
        sb.append(getString(R.string.h_db5abb31));
        sb.append("- file_parse_text: file_path | file_read_lines: file_path, start_line, line_count\n");
        sb.append("- ocr_recognize: image_path | ocr_recognize_pdf: pdf_path\n\n");

        // 直发附件的默认占位文案已表达“分析附件”意图，不再重复注入结尾指令
        if (!DEFAULT_ATTACHMENT_MESSAGE.equals(originalMessage)) {
            sb.append(getString(R.string.h_cf88fe4b));
        } else {
            sb.append(getString(R.string.h_926e80d4));
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
        // 同步捕获本条消息的深度思考开关（开关保持用户设定，不自动复位）
        boolean thinkingFlag = false;
        try {
            thinkingFlag = com.oilquiz.app.ai.chat.ChatModeManager.getInstance(this).isDeepThinkingEnabled();
        } catch (Exception ignored) {
        }
        final boolean visionThinkingEnabled = thinkingFlag;
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
            showToast(getString(R.string.h_a3e17914) + skippedFiles.size() + getString(R.string.h_7c645c81));
        }

        if (filtered.isEmpty()) {
            if (!originalMessage.isEmpty()) {
                processChatMessage(originalMessage);
            }
            return;
        }

        showToast(getString(R.string.h_d3430199));

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

            // 本地多模态优先：vision 模型 + mmproj 已加载 + 单张图片 → 直接视觉理解（不走 OCR）
            boolean allImages = !filtered.isEmpty();
            for (ChatMessage.Attachment att : filtered) {
                if (!"image".equals(att.type)) { allImages = false; break; }
            }
            boolean multimodalReady = false;
            try {
                multimodalReady = LlamaHelper.isMultimodalLoaded() && LlamaHelper.isModelInitialized();
            } catch (Exception ignored) {}
            // 本地模型已加载但视觉未就绪（供 OCR 兜底提示使用，final 以便 lambda 捕获）
            final boolean localVisionNotReady = !multimodalReady && LlamaHelper.isModelInitialized();
            if (allImages && multimodalReady && filtered.size() == 1) {
                handleMultimodalImage(filtered.get(0), localFileMap, originalMessage, visionThinkingEnabled);
                return;
            }

            // 图片等附件统一走 OCR 附件预解析路径（不交给本地模型多模态推理）
            runOnUiThread(() -> {
                // 1. 展示上传状态
                StringBuilder displayMsg = new StringBuilder();
                displayMsg.append(getString(R.string.h_5f76df07)).append(filtered.size()).append(getString(R.string.h_0cabf767));
                if (!skippedFiles.isEmpty()) {
                    displayMsg.append(getString(R.string.h_10aace1e)).append(String.join(", ", skippedFiles)).append("\n\n");
                }
                for (ChatMessage.Attachment att : filtered) {
                    Uri uri = Uri.parse(att.url);
                    boolean saved = localFileMap.get(uri) != null;
                    displayMsg.append("• ").append(att.name)
                            .append("（").append(formatFileSize(att.size)).append("）")
                            .append(saved ? getString(R.string.h_d7610fbf) : getString(R.string.h_21c163e1))
                            .append("\n");
                }
                // 本地模型已加载但视觉未就绪时，明确提示走 OCR 的原因（避免静默降级困惑）
                if (localVisionNotReady) {
                    displayMsg.append(getString(R.string.h_96262f69));
                }
                displayMsg.append(getString(R.string.h_a791b767));

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
                            int sysPos = chatHistory.indexOf(sysMsg);
                            if (chatAdapter != null && sysPos >= 0) {
                                chatAdapter.notifyItemChanged(sysPos);
                            }
                            scrollToBottom();
                        });
                    }

                    @Override
                    public void onAllCompleted(List<com.oilquiz.app.ai.chat.input.AttachmentPreParser.ParseResult> results) {
                        runOnUiThread(() -> {
                            // 3. 展示确定性解析结果
                            sysMsg.content = buildParseSummaryMsg(filtered, results).toString();
                            int sysPos = chatHistory.indexOf(sysMsg);
                            if (chatAdapter != null && sysPos >= 0) {
                                chatAdapter.notifyItemChanged(sysPos);
                            }
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
                                    allContent.append(getString(R.string.h_a234ecf9)).append(name).append(" ===\n");
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
     * 本地多模态推理：单张图片 + vision 模型（mmproj 已加载）→ generateWithImage 直接视觉理解。
     * 图片本地保存失败时回退 OCR 附件路径；任何异常仅提示，不崩溃。
     */
    private void handleMultimodalImage(ChatMessage.Attachment imageAtt,
                                       java.util.Map<android.net.Uri, String> localFileMap,
                                       String originalMessage,
                                       boolean visionThinkingEnabled) {
        try {
            android.net.Uri uri = android.net.Uri.parse(imageAtt.url);
            String localPath = localFileMap != null ? localFileMap.get(uri) : null;
            java.io.File localFile = localPath != null ? new java.io.File(localPath) : null;
            if (localFile == null || !localFile.exists()) {
                AppLogger.w(TAG, "Multimodal: image not saved locally, falling back to OCR path");
                processMessageWithAttachments(originalMessage, java.util.Collections.singletonList(imageAtt));
                return;
            }

            final String userText = originalMessage == null || originalMessage.trim().isEmpty()
                    ? "请描述这张图片的内容" : originalMessage;
            // 图片预处理：WebP/HEIC 等重编码为 JPEG + 长边降采样，
            // 解决原生 mtmd(stb_image) 不支持 WebP/HEIC、大图全尺寸解码 OOM 两个问题
            final String visionImagePath = com.oilquiz.app.ai.util.ImagePreprocessUtil.prepareVisionImage(this, localFile);
            // 多轮上下文：取当前图片消息之前的 USER/AI 文本消息（图片消息本身不入历史，
            // 其文本离开图片会误导模型）
            final java.util.List<ChatMessage> history = buildVisionHistory(1);
            // 采样参数读用户配置（不再硬编码 0.7/0.9/40）
            final int maxTokens = aiConfig != null ? aiConfig.getMaxTokens() : 1024;
            final float temperature = aiConfig != null ? aiConfig.getTemperature() : 0.7f;
            final float topP = aiConfig != null ? aiConfig.getTopP() : 0.9f;
            final int topK = aiConfig != null ? aiConfig.getTopK() : 40;

            startLocalVisionGeneration(history, userText, visionImagePath,
                    maxTokens, temperature, topP, topK, visionThinkingEnabled);
        } catch (Exception e) {
            AppLogger.e(TAG, "handleMultimodalImage error: " + e.getMessage(), e);
            showToast(getString(R.string.h_c7833a66));
        }
    }

    /** 构建本地视觉模型的多轮上下文：取 chatHistory 中除末尾 excludeLast 条外的 USER/AI 文本消息，最多 12 条 */
    private java.util.List<ChatMessage> buildVisionHistory(int excludeLast) {
        java.util.List<ChatMessage> hist = new java.util.ArrayList<>();
        int end = Math.max(0, chatHistory.size() - excludeLast);
        for (int i = 0; i < end; i++) {
            ChatMessage m = chatHistory.get(i);
            if ((m.type == ChatMessage.MessageType.USER || m.type == ChatMessage.MessageType.AI)
                    && m.content != null && !m.content.isEmpty()) {
                hist.add(m);
            }
        }
        if (hist.size() > 12) {
            hist = new java.util.ArrayList<>(hist.subList(hist.size() - 12, hist.size()));
        }
        return hist;
    }

    /**
     * 执行本地视觉推理（共用：新图首轮 / 纯文字追问复用上一张图）。
     * 含竞态缓解：调用前在工作线程再次确认多模态就绪，避免 UI 线程检查后、生成线程执行前用户切换模型。
     */
    private void startLocalVisionGeneration(java.util.List<ChatMessage> history, String userText,
                                            String visionImagePath, int maxTokens,
                                            float temperature, float topP, int topK,
                                            boolean visionThinkingEnabled) {
        final String msgId = java.util.UUID.randomUUID().toString();
        ChatMessage aiMsg = ChatMessage.createAIMessage(msgId, "", System.currentTimeMillis(), null, 0, 0);
        aiMsg.status = ChatMessage.MessageStatus.GENERATING;
        // 本方法可能被后台线程调用（附件保存回调链 AttachmentManager→CompletableFuture→handleMultimodalImage），
        // chatHistory/notifyItemInserted/scrollToBottom 必须在 UI 线程执行，否则抛
        // IllegalStateException "Cannot call this method while RecyclerView is computing a layout or scrolling"
        final int[] aiIndexRef = { -1 };
        runOnUiThread(() -> {
            chatHistory.add(aiMsg);
            aiIndexRef[0] = chatHistory.size() - 1;
            if (chatAdapter != null) chatAdapter.notifyItemInserted(aiIndexRef[0]);
            scrollToBottom();
            beginGeneration();
        });

        new Thread(() -> {
            final StringBuilder full = new StringBuilder();
            try {
                // 竞态缓解：工作线程再确认一次多模态就绪（期间用户可能切换模型）
                if (!LlamaHelper.isMultimodalLoaded() || !LlamaHelper.isModelInitialized()) {
                    runOnUiThread(() -> {
                        aiMsg.content = "图片识别失败: 本地视觉模型未就绪（mmproj 未加载或模型已切换），请稍后重试";
                        aiMsg.status = ChatMessage.MessageStatus.ERROR;
                        if (chatAdapter != null) chatAdapter.notifyItemChanged(aiIndexRef[0]);
                        endGeneration();
                    });
                    return;
                }
                LlamaHelper.generateWithImage(history, userText, visionImagePath,
                        maxTokens, temperature, topP, topK, visionThinkingEnabled,
                        new LlamaHelper.TokenCallback() {
                            @Override public void onToken(String token) {
                                if (token == null) return;
                                synchronized (full) { full.append(token); }
                                runOnUiThread(() -> {
                                    synchronized (full) { aiMsg.content = full.toString(); }
                                    if (chatAdapter != null) chatAdapter.notifyItemChanged(aiIndexRef[0]);
                                });
                            }
                            @Override public void onComplete(String fullText) {
                                runOnUiThread(() -> {
                                    aiMsg.content = fullText != null && !fullText.isEmpty() ? fullText : full.toString();
                                    aiMsg.status = ChatMessage.MessageStatus.COMPLETED;
                                    if (chatAdapter != null) chatAdapter.notifyItemChanged(aiIndexRef[0]);
                                    endGeneration();
                                    scrollToBottom();
                                    saveHistoryAsync();
                                });
                            }
                            @Override public void onError(String error) {
                                runOnUiThread(() -> {
                                    aiMsg.content = "图片识别失败: " + error;
                                    aiMsg.status = ChatMessage.MessageStatus.ERROR;
                                    if (chatAdapter != null) chatAdapter.notifyItemChanged(aiIndexRef[0]);
                                    endGeneration();
                                });
                            }
                        });
            } catch (Exception e) {
                AppLogger.e(TAG, "Multimodal generate error: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    aiMsg.content = "图片处理异常: " + e.getMessage();
                    aiMsg.status = ChatMessage.MessageStatus.ERROR;
                    if (chatAdapter != null) chatAdapter.notifyItemChanged(aiIndexRef[0]);
                    endGeneration();
                });
            }
        }).start();
    }

    /** 解析附件为真实本地文件（优先落盘路径，否则 content:// 复制到缓存） */
    private java.io.File resolveAttachmentFile(ChatMessage.Attachment att) {
        try {
            if (att.localFilePath != null) {
                java.io.File f = new java.io.File(att.localFilePath);
                if (f.exists()) return f;
            }
            if (att.url != null && !att.url.isEmpty()) {
                java.io.File f = com.oilquiz.app.util.UriPathResolver.resolveToFile(this, att.url);
                if (f != null && f.exists()) return f;
            }
        } catch (Exception e) {
            AppLogger.w(TAG, "resolveAttachmentFile failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * 本地多模态追问：上一轮发过图、当前为纯文字且本地视觉已就绪时，自动复用该图做视觉理解，
     * 解决"看完图后追问"失效问题。返回 true 表示已接管本轮回复。
     */
    private boolean tryFollowUpVision(String message) {
        try {
            if (aiService == null) return false;
            if (!LlamaHelper.isMultimodalLoaded() || !LlamaHelper.isModelInitialized()) return false;
            if (chatHistory == null || chatHistory.isEmpty()) return false;
            // 找最近一条带图片的用户消息（当前纯文字消息已在末尾、无图，会被跳过）
            ChatMessage.Attachment lastImage = null;
            int lastImageIndex = -1;
            for (int i = chatHistory.size() - 1; i >= 0; i--) {
                ChatMessage m = chatHistory.get(i);
                if (m.type == ChatMessage.MessageType.USER && m.attachments != null) {
                    for (ChatMessage.Attachment att : m.attachments) {
                        if (att != null && "image".equals(att.type)) {
                            lastImage = att;
                            lastImageIndex = i;
                            break;
                        }
                    }
                }
                if (lastImage != null) break;
            }
            if (lastImage == null || lastImageIndex < 0) return false;
            if (lastImageIndex == chatHistory.size() - 1) return false; // 当前消息本身就是带图消息，不应走到这
            java.io.File imageFile = resolveAttachmentFile(lastImage);
            if (imageFile == null || !imageFile.exists()) return false;

            final String visionImagePath = com.oilquiz.app.ai.util.ImagePreprocessUtil.prepareVisionImage(this, imageFile);
            // 历史：图片消息之前的 USER/AI 文本
            final java.util.List<ChatMessage> history = new java.util.ArrayList<>();
            for (int i = 0; i < lastImageIndex; i++) {
                ChatMessage m = chatHistory.get(i);
                if ((m.type == ChatMessage.MessageType.USER || m.type == ChatMessage.MessageType.AI)
                        && m.content != null && !m.content.isEmpty()) {
                    history.add(m);
                }
            }
            if (history.size() > 12) {
                java.util.List<ChatMessage> trimmed = new java.util.ArrayList<>(history.subList(history.size() - 12, history.size()));
                history.clear();
                history.addAll(trimmed);
            }

            final int maxTokens = aiConfig != null ? aiConfig.getMaxTokens() : 1024;
            final float temperature = aiConfig != null ? aiConfig.getTemperature() : 0.7f;
            final float topP = aiConfig != null ? aiConfig.getTopP() : 0.9f;
            final int topK = aiConfig != null ? aiConfig.getTopK() : 40;
            boolean thinkingFlag = false;
            try {
                thinkingFlag = com.oilquiz.app.ai.chat.ChatModeManager.getInstance(this).isDeepThinkingEnabled();
            } catch (Exception ignored) {
            }
            final boolean thinkingEnabled = thinkingFlag;
            AppLogger.ai(TAG, "Follow-up vision: reuse image " + imageFile.getName() + " for: " + message);
            startLocalVisionGeneration(history, message, visionImagePath,
                    maxTokens, temperature, topP, topK, thinkingEnabled);
            return true;
        } catch (Exception e) {
            AppLogger.w(TAG, "tryFollowUpVision failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 在线视觉追问：上一轮发过图、当前为纯文字且在线模型支持视觉时，
     * 自动复用该图（base64 注入 OpenAI 兼容消息）做视觉理解，
     * 解决"看完图后追问"失效问题（原 Agent 文本路径的历史序列化不含图片）。
     * 返回 true 表示已接管本轮回复。
     */
    private boolean tryFollowUpOnlineVision(String message) {
        try {
            if (!isOnlineVisionModel()) return false;
            if (chatHistory == null || chatHistory.isEmpty()) return false;
            // 找最近一条带图片的用户消息（当前纯文字消息已在末尾、无图，会被跳过）
            ChatMessage.Attachment lastImage = null;
            int lastImageIndex = -1;
            for (int i = chatHistory.size() - 1; i >= 0; i--) {
                ChatMessage m = chatHistory.get(i);
                if (m.type == ChatMessage.MessageType.USER && m.attachments != null) {
                    for (ChatMessage.Attachment att : m.attachments) {
                        if (att != null && "image".equals(att.type)) {
                            lastImage = att;
                            lastImageIndex = i;
                            break;
                        }
                    }
                }
                if (lastImage != null) break;
            }
            if (lastImage == null || lastImageIndex < 0) return false;
            if (lastImageIndex == chatHistory.size() - 1) return false; // 当前消息本身就是带图消息，不应走到这
            final ChatMessage.Attachment fImage = lastImage;
            java.io.File imageFile = resolveAttachmentFile(lastImage);
            if (imageFile == null || !imageFile.exists()) return false;
            if (imageFile.length() > 4L * 1024 * 1024) return false; // 防请求体过大，超 4MB 不走在线视觉

            // 读图转 base64
            byte[] bytes = java.nio.file.Files.readAllBytes(imageFile.toPath());
            final String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);

            // 历史：图片消息之前的 USER/AI 纯文本（跳过带附件消息，多轮发图时避免把旧图文本/默认文本混入）
            final java.util.List<ChatMessage> history = new java.util.ArrayList<>();
            for (int i = 0; i < lastImageIndex; i++) {
                ChatMessage m = chatHistory.get(i);
                if ((m.type == ChatMessage.MessageType.USER || m.type == ChatMessage.MessageType.AI)
                        && (m.attachments == null || m.attachments.isEmpty())
                        && m.content != null && !m.content.isEmpty()) {
                    history.add(m);
                }
            }
            if (history.size() > 12) {
                java.util.List<ChatMessage> trimmed = new java.util.ArrayList<>(history.subList(history.size() - 12, history.size()));
                history.clear();
                history.addAll(trimmed);
            }

            // 创建流式 AI 回复消息
            ChatMessage aiMsg = ChatMessage.createAIMessage(
                    java.util.UUID.randomUUID().toString(), "", System.currentTimeMillis(), null, 0, 0);
            aiMsg.status = ChatMessage.MessageStatus.GENERATING;
            chatHistory.add(aiMsg);
            final int aiIndex = chatHistory.size() - 1;
            if (chatAdapter != null) chatAdapter.notifyItemInserted(aiIndex);
            scrollToBottom();
            beginGeneration();

            final int maxTokens = aiConfig != null ? aiConfig.getMaxTokens() : 1024;
            com.oilquiz.app.ai.service.OnlineInferenceService ois =
                    com.oilquiz.app.ai.service.OnlineInferenceService.getInstance(this);
            OnlineModelManager.OnlineModelConfig active =
                    onlineModelManager != null ? onlineModelManager.getActiveModel() : null;
            if (ois == null || active == null) {
                aiMsg.content = "在线模型未配置";
                aiMsg.status = ChatMessage.MessageStatus.ERROR;
                if (chatAdapter != null) chatAdapter.notifyItemChanged(aiIndex);
                endGeneration();
                return true;
            }

            AppLogger.ai(TAG, "Follow-up online vision: reuse image " + imageFile.getName() + " for: " + message);
            // 深度思考开关：跟随 UI 独立开关，多模态思考模型（如 Qwen3-VL）reasoning 走思考区
            boolean visionThinking = false;
            try {
                visionThinking = com.oilquiz.app.ai.chat.ChatModeManager.getInstance(this).isDeepThinkingEnabled();
            } catch (Exception ignored) {
            }
            ois.generateStreamWithImages(message, java.util.Collections.singletonList(b64),
                    active, history, maxTokens, visionThinking, new com.oilquiz.app.ai.callback.StreamCallback() {
                        @Override public void onToken(String token) {
                            if (token == null) return;
                            runOnUiThread(() -> {
                                aiMsg.content = (aiMsg.content == null ? "" : aiMsg.content) + token;
                                if (chatAdapter != null) chatAdapter.notifyItemChanged(aiIndex);
                            });
                        }
                        @Override public void onThinkingToken(String token) {
                            if (token == null || token.isEmpty()) return;
                            runOnUiThread(() -> {
                                aiMsg.thinkingContent = (aiMsg.thinkingContent == null ? "" : aiMsg.thinkingContent) + token;
                                if (chatAdapter != null) chatAdapter.notifyItemChanged(aiIndex);
                            });
                        }
                        @Override public void onComplete(String fullText) {
                            runOnUiThread(() -> {
                                aiMsg.content = fullText != null && !fullText.isEmpty() ? fullText : aiMsg.content;
                                aiMsg.status = ChatMessage.MessageStatus.COMPLETED;
                                if (chatAdapter != null) chatAdapter.notifyItemChanged(aiIndex);
                                endGeneration();
                                scrollToBottom();
                                saveHistoryAsync();
                            });
                        }
                        @Override public void onError(String error) {
                            // 在线视觉失败：进入冷却（30s 内不走在线视觉），回退 OCR+Agent
                            lastOnlineVisionFailAt = System.currentTimeMillis();
                            runOnUiThread(() -> {
                                aiMsg.content = "在线图片识别失败: " + error + "\n自动改用本地高精度 OCR 识别...";
                                aiMsg.status = ChatMessage.MessageStatus.COMPLETED;
                                if (chatAdapter != null) chatAdapter.notifyItemChanged(aiIndex);
                                endGeneration();
                                // 回退 OCR 文本 + Agent（ViaAgent 因冷却标记不再走在线视觉）
                                processMessageWithAttachmentsViaAgent(message,
                                        java.util.Collections.singletonList(fImage));
                            });
                        }
                    });
            return true;
        } catch (Exception e) {
            AppLogger.w(TAG, "tryFollowUpOnlineVision failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 当前是否为支持视觉的在线模型（模型名含 vl/vision/4o/omni/gemini/glm-4v 等）。
     * 局限：模型名判断不绝对可靠——近期在线视觉失败（lastOnlineVisionFailAt）时返回 false，
     * 走 OCR 兜底，避免对不支持图片的 API 反复发图导致 400 循环。
     */
    private boolean isOnlineVisionModel() {
        try {
            if (!shouldUseOnlineModel()) return false;
            if (System.currentTimeMillis() - lastOnlineVisionFailAt < 30_000L) return false; // 失败冷却 30s
            // 1) 配置能力字段优先（supportsVision / capabilities.supportsImageInput，
            //    加载时按模型名自动填充并持久化，未来可支持用户手动覆盖）
            OnlineModelManager.OnlineModelConfig active =
                    onlineModelManager != null ? onlineModelManager.getActiveModel() : null;
            if (active != null && active.hasCapability("vision")) return true;
            // 2) 模型名关键词兜底推断（active 为 null 或能力字段未标记时）
            String modelName = null;
            if (inferenceRouter != null) {
                modelName = inferenceRouter.getCurrentModelName();
            }
            if ((modelName == null || modelName.isEmpty()) && active != null) modelName = active.modelName;
            if (modelName == null) return false;
            return com.oilquiz.app.ai.model.OnlineModelManager.isVisionModelName(modelName);
        } catch (Exception e) {
            return false;
        }
    }

    /** 在线视觉最近一次失败时间（用于冷却，避免对不支持图片的模型反复请求） */
    private volatile long lastOnlineVisionFailAt = 0;

    /**
     * 在线多模态推理：单张图片 + 在线视觉模型 → base64 注入 OpenAI 兼容消息直接看图。
     * 图片超过 4MB 或读取失败时回退 OCR+Agent 路径；任何异常仅提示，不崩溃。
     */
    private void handleOnlineMultimodalImage(ChatMessage.Attachment imageAtt,
                                             java.util.Map<android.net.Uri, String> localFileMap,
                                             String originalMessage) {
        try {
            android.net.Uri uri = android.net.Uri.parse(imageAtt.url);
            String localPath = localFileMap != null ? localFileMap.get(uri) : null;
            java.io.File localFile = localPath != null ? new java.io.File(localPath) : null;
            if (localFile == null || !localFile.exists()) {
                AppLogger.w(TAG, "Online multimodal: image not saved, falling back to OCR path");
                processMessageWithAttachmentsViaAgent(originalMessage, java.util.Collections.singletonList(imageAtt));
                return;
            }
            if (localFile.length() > 4L * 1024 * 1024) {
                AppLogger.w(TAG, "Online multimodal: image > 4MB, falling back to OCR path");
                showToast(getString(R.string.h_a8f5e4b0));
                processMessageWithAttachmentsViaAgent(originalMessage, java.util.Collections.singletonList(imageAtt));
                return;
            }

            // 读图转 base64
            byte[] bytes = java.nio.file.Files.readAllBytes(localFile.toPath());
            String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);

            final String userText = originalMessage == null || originalMessage.trim().isEmpty()
                    ? "请描述这张图片的内容" : originalMessage;
            // 用户消息已由 sendMessage 添加（含附件），此处不再重复添加，直接创建 AI 回复消息

            final String msgId = java.util.UUID.randomUUID().toString();
            ChatMessage aiMsg = ChatMessage.createAIMessage(msgId, "", System.currentTimeMillis(), null, 0, 0);
            aiMsg.status = ChatMessage.MessageStatus.GENERATING;
            final int[] aiIndexRef = { -1 };
            // 可能被后台线程调用（附件回调链 AttachmentManager→CompletableFuture→handleOnlineMultimodalImage），
            // notifyItemInserted/scrollToBottom 必须在 UI 线程执行
            runOnUiThread(() -> {
                chatHistory.add(aiMsg);
                aiIndexRef[0] = chatHistory.size() - 1;
                if (chatAdapter != null) chatAdapter.notifyItemInserted(aiIndexRef[0]);
                scrollToBottom();
                beginGeneration();
            });

            final int maxTokens = aiConfig != null ? aiConfig.getMaxTokens() : 1024;
            com.oilquiz.app.ai.service.OnlineInferenceService ois =
                    com.oilquiz.app.ai.service.OnlineInferenceService.getInstance(this);
            OnlineModelManager.OnlineModelConfig active =
                    onlineModelManager != null ? onlineModelManager.getActiveModel() : null;
            if (ois == null || active == null) {
                runOnUiThread(() -> {
                    aiMsg.content = "在线模型未配置";
                    aiMsg.status = ChatMessage.MessageStatus.ERROR;
                    if (aiIndexRef[0] >= 0 && chatAdapter != null) chatAdapter.notifyItemChanged(aiIndexRef[0]);
                    endGeneration();
                });
                return;
            }

            // 多轮上下文：图片消息之前的 USER/AI 纯文本（跳过带附件消息，避免旧图文本混入）
            final java.util.List<ChatMessage> onlineHistory = new java.util.ArrayList<>();
            for (int i = 0; i < chatHistory.size() - 1; i++) {
                ChatMessage m = chatHistory.get(i);
                if ((m.type == ChatMessage.MessageType.USER || m.type == ChatMessage.MessageType.AI)
                        && (m.attachments == null || m.attachments.isEmpty())
                        && m.content != null && !m.content.isEmpty()) {
                    onlineHistory.add(m);
                }
            }
            if (onlineHistory.size() > 12) {
                java.util.List<ChatMessage> trimmed = new java.util.ArrayList<>(onlineHistory.subList(onlineHistory.size() - 12, onlineHistory.size()));
                onlineHistory.clear();
                onlineHistory.addAll(trimmed);
            }

            // 深度思考开关：跟随 UI 独立开关，多模态思考模型（如 Qwen3-VL）reasoning 走思考区
            boolean visionThinking = false;
            try {
                visionThinking = com.oilquiz.app.ai.chat.ChatModeManager.getInstance(this).isDeepThinkingEnabled();
            } catch (Exception ignored) {
            }
            ois.generateStreamWithImages(userText, java.util.Collections.singletonList(b64),
                    active, onlineHistory,
                    maxTokens, visionThinking, new com.oilquiz.app.ai.callback.StreamCallback() {
                        @Override public void onToken(String token) {
                            if (token == null) return;
                            runOnUiThread(() -> {
                                aiMsg.content = (aiMsg.content == null ? "" : aiMsg.content) + token;
                                if (chatAdapter != null) chatAdapter.notifyItemChanged(aiIndexRef[0]);
                            });
                        }
                        @Override public void onThinkingToken(String token) {
                            if (token == null || token.isEmpty()) return;
                            runOnUiThread(() -> {
                                aiMsg.thinkingContent = (aiMsg.thinkingContent == null ? "" : aiMsg.thinkingContent) + token;
                                if (chatAdapter != null) chatAdapter.notifyItemChanged(aiIndexRef[0]);
                            });
                        }
                        @Override public void onComplete(String fullText) {
                            runOnUiThread(() -> {
                                aiMsg.content = fullText != null && !fullText.isEmpty() ? fullText : aiMsg.content;
                                aiMsg.status = ChatMessage.MessageStatus.COMPLETED;
                                if (chatAdapter != null) chatAdapter.notifyItemChanged(aiIndexRef[0]);
                                endGeneration();
                                scrollToBottom();
                                saveHistoryAsync();
                            });
                        }
                        @Override public void onError(String error) {
                            // 在线视觉失败：进入冷却（30s 内不走在线视觉），回退 OCR+Agent
                            lastOnlineVisionFailAt = System.currentTimeMillis();
                            runOnUiThread(() -> {
                                aiMsg.content = "在线图片识别失败: " + error + "\n自动改用本地高精度 OCR 识别...";
                                aiMsg.status = ChatMessage.MessageStatus.COMPLETED;
                                if (chatAdapter != null) chatAdapter.notifyItemChanged(aiIndexRef[0]);
                                endGeneration();
                                // 回退 OCR 文本 + Agent（ViaAgent 因冷却标记不再走在线视觉）
                                processMessageWithAttachmentsViaAgent(originalMessage,
                                        java.util.Collections.singletonList(imageAtt));
                            });
                        }
                    });
        } catch (Exception e) {
            AppLogger.e(TAG, "handleOnlineMultimodalImage error: " + e.getMessage(), e);
            lastOnlineVisionFailAt = System.currentTimeMillis();
            showToast(getString(R.string.h_2b587ccc));
            processMessageWithAttachmentsViaAgent(originalMessage, java.util.Collections.singletonList(imageAtt));
        }
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
            prompt.append(getString(R.string.h_8997e243)).append(fileCount).append(getString(R.string.h_feae15b8));

            if (originalMessage != null && !originalMessage.isEmpty() && !originalMessage.equals(DEFAULT_ATTACHMENT_MESSAGE)) {
                prompt.append(getString(R.string.h_14e8a21e)).append(originalMessage).append("\n\n");
            } else {
                prompt.append(getString(R.string.h_e4b86c39));
            }

            prompt.append(getString(R.string.h_4fe2794a));
            prompt.append(attachmentContent);
            prompt.append(getString(R.string.h_4b4e3db7));
            prompt.append(getString(R.string.h_48e86d79));

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
            prompt.append(getString(R.string.h_36623a6c)).append(userMessage).append("\n\n");
        } else {
            prompt.append(getString(R.string.h_a485628e));
        }

        prompt.append(getString(R.string.h_a4368a9d));
        prompt.append(parsedContent).append("\n\n");
        prompt.append(getString(R.string.h_b575ddeb));
        prompt.append(getString(R.string.h_bd78f6c8));
        prompt.append(getString(R.string.h_5529af52));
        prompt.append(getString(R.string.h_d6c806dd));
        prompt.append(getString(R.string.h_29d6d222));
        prompt.append(getString(R.string.h_dc9307dd));

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
        sb.append(getString(R.string.h_793176ee)).append(originalMessage).append("\n\n");
        sb.append(getString(R.string.h_4fe2794a));

        int totalUsed = 0;
        int idx = 1;
        for (java.util.Map.Entry<Uri, String> entry : extractedMap.entrySet()) {
            String fileName = getFileNameFromUri(entry.getKey());
            String content = entry.getValue();

            if (content == null || isExtractFailed(content)) {
                sb.append(getString(R.string.h_94e069c2)).append(idx++).append("】");
                if (fileName != null) sb.append(" ").append(fileName);
                sb.append(getString(R.string.h_68e672a0));
                continue;
            }

            int remaining = totalCharLimit - totalUsed;
            if (remaining <= 0) {
                sb.append(getString(R.string.h_86b05ff3));
                break;
            }

            int thisLimit = Math.min(singleCharLimit, remaining);
            if (content.length() > thisLimit) {
                content = content.substring(0, thisLimit) + "\n...[内容已截断]";
            }

            sb.append(getString(R.string.h_94e069c2)).append(idx++).append("】");
            if (fileName != null) sb.append(" ").append(fileName);
            sb.append("\n");
            sb.append(content).append("\n\n");
            totalUsed += content.length();
        }

        sb.append(getString(R.string.h_c65217ea));
        sb.append(getString(R.string.h_1575bdd9));

        return sb.toString();
    }

    /**
     * NPU-PRELOAD（方案 B）：进入聊天页时后台预加载 NPU 模型。
     * 设计要点：只在**聊天页**触发，绝不放进启动路径 —— 原生层（QNN/Genie/GenieX）偶发 abort
     * 时最多影响本页提示，不会变成"一打开就秒退"。已在加载/已加载/引擎关闭时不重复触发。
     */
    private void preloadNpuIfNeeded() {
        try {
            if (!isNpuEngineOn()) {
                return;
            }
            if (com.oilquiz.app.ai.engine.NpuLlmChat.isLoaded()) {
                return;
            }
            if ("LOADING".equals(com.oilquiz.app.ai.engine.NpuLlmChat.getStateName())) {
                return;   // 已在加载中
            }
            AppLogger.ai(TAG, "NPU 预加载：进入聊天页 → 交给 AIService 托管加载");
            // NPU-SERVICE-OWNED：加载统一由 AIService 托管（后台单线程 npu-load、幂等、180s 超时），
            // 这里只表达"用户已进入聊天页"这一**运行时意图** —— 用它区分"用户触发"与"冷启动路径"，
            // 服务内部因此不需要一刀切地拒绝加载。
            com.oilquiz.app.ai.service.AIService.getInstance(getApplicationContext())
                    .ensureNpuLoadedAsync(ok -> AppLogger.ai(TAG, "NPU 预加载结果(服务托管): " + ok));
        } catch (Throwable t) {
            AppLogger.aiW(TAG, "NPU 预加载异常: " + t);
        }
    }

    private void processChatMessage(String message) {
        try {
            // 简化路由：在线模型 → 完整 Agent（工具调用自动）；本地模型 → 普通对话
            // （模式精简为 普通/深度思考 两个，深度思考由普通对话路径注入思考指令+enableThinking）
            // R3-1/R8-2：本地 Agent 实验开关开启时，本地模型也走 Agent
            // 2026-10-05：NPU（GenieX）引擎下**同样**走 Agent —— 原先这里用 !npuEngineOn 直接
            // 落到普通对话（当时以为 NPU 没有工具能力），导致 NPU 模式永远 tools=0、Agent 永不启动。
            // 现在 NPU 已能驱动 Agent 循环（NpuEngineRouter.chatJson + 事件协议对齐）→ 放行。
            if (shouldUseOnlineModel()
                    || (aiConfig != null && aiConfig.isLocalAgentEnabled())) {
                showOnlineAgentFriendlyGuide(message);
                processChatMessageWithAgent(message);
                return;
            }

            // 其他：本地模型普通对话（深度思考模式在其中处理）
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
        // 轮次模式标记（历史/会话记录用；上下文构建共享完整历史，不再隔离）
        markLastUserMessageMode(ChatMessage.TURN_MODE_NORMAL);
        // NPU（GenieX）引擎不依赖 aiService：跳过下面的"请选择模型"弹窗，直接进 NPU 推理路由
        boolean npuOnForNormal = isNpuEngineOn();
        if (aiService == null && !npuOnForNormal) {
            runOnUiThread(() -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.h_c1d12ffc))
                        .setMessage(getString(R.string.h_f81e293e) +
                                "📥 本地Agent模型（推荐）\n" +
                                "• 支持工具调用（天气/搜索/记忆等）、思考链\n" +
                                "• 推荐 Qwen3.5-4B（Q4_0，约2.6GB，NPU 与 llama.cpp 通用）\n\n" +
                                "💬 本地普通对话模型\n" +
                                "• 轻量快速，仅普通对话\n" +
                                "• 推荐 Qwen3.5-0.8B，约0.5GB\n\n" +
                                "🌐 在线模型\n" +
                                "• 功能更强，完整Agent 40+工具\n" +
                                "• 支持豆包、DeepSeek、通义千问等")
                        .setPositiveButton(getString(R.string.h_f0597ad8), (d, w) -> {
                            startActivity(new Intent(AIChatActivity.this, ModelDownloadActivity.class));
                        })
                        .setNegativeButton(getString(R.string.h_87e4d9ef), null)
                        .setCancelable(true)
                        .show();
            });
            return;
        }
        
        synchronized (streamingLock) {
            if (isGenerating) {
                AppLogger.aiW(TAG, "processChatMessageNormal skipped, already generating");
                showToast(getString(R.string.h_05582e8e));
                return;
            }
        }
        
        // 检查 Native 层状态，如果无效则自动恢复
        // NPU（GenieX）引擎下跳过：那条路不用 llama.cpp 的 native 上下文，否则会被"自动恢复"
        // 截走（去加载本机不存在的本地模型），表现就是卡在"准备模型文件"。
        if (!isNpuEngineOn() && !modelBridge.isNativeStateValid()) {
            AppLogger.aiW(TAG, "Native state invalid, triggering auto-recovery");
            addSystemMessage("⚠️ 检测到AI模型状态异常，正在自动恢复...", ChatMessage.SystemMessageType.WARNING);
            if (recoveryHandler != null) {
                recoveryHandler.setPendingMessage(message);
                recoveryHandler.triggerAutoRecovery();
            }
            return;
        }

        // 本地多模态追问：上一轮发过图、当前纯文字且本地视觉已就绪 → 自动复用该图走视觉理解（不走纯文本/缓存）
        if (tryFollowUpVision(message)) {
            return;
        }

        if (cacheManager != null && aiConfig != null && aiConfig.isCacheEnabled()) {
            String cached = cacheManager.getCachedResponse(message);
            if (cached != null) { addAIMessage(cached); addSystemMessage("(来自缓存)"); return; }
        }

        synchronized (streamingLock) {
            agentToolLoopCount = 0;
            thinkingRoundEnded = false;
            thinkingRoundCount = 1;
            agentGroupStepCount = 0;
            agentGroupToolCount = 0;
            agentToolNames.clear();
            currentStreamingContent = new StringBuilder();
            currentThinkingContent = new StringBuilder();
            currentStreamingMessageId = java.util.UUID.randomUUID().toString();
            resetStreamingTts();
            resetStreamingState();

            ChatMessage initialMessage = ChatMessage.createAIMessage(currentStreamingMessageId, "", System.currentTimeMillis(), null, 0, 0);
            initialMessage.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.INITIALIZING);
            initialMessage.status = ChatMessage.MessageStatus.GENERATING;
            initialMessage.turnId = currentTurnId; // 消息对绑定：与本回合 user 消息共享 turnId
            initialMessage.subId = ChatIdDispatcher.getInstance().applySubId(ChatIdDispatcher.IdType.AI);
            chatHistory.add(initialMessage);
            currentStreamingMessageIndex = chatHistory.size() - 1;
            if (chatAdapter != null) chatAdapter.notifyItemInserted(currentStreamingMessageIndex);
            scrollToBottom();
        }

        beginGeneration();

        final String prompt = message;
        final int streamingIndex = currentStreamingMessageIndex;
        final String streamingId = currentStreamingMessageId;

        runOnUiThread(() -> updateInferencePhase(resolveStreamingIndex(), ChatMessage.InferencePhase.INITIALIZING, null));

        int actualMaxTokens = aiConfig.getMaxTokens();
        boolean enableThinking = ChatModeManager.getInstance(AIChatActivity.this).isDeepThinkingEnabled();
        if (outputRouter != null) {
            outputRouter.reset();
            outputRouter.setThinkingEnabled(enableThinking);
            outputRouter.setThinkingTags(LlamaHelper.getThinkingTags());
            legacyThinkingTags = LlamaHelper.getThinkingTags();
        }
        isInThinking = enableThinking;

        AppLogger.ai(TAG, "Bridge sendMessage: promptLen=" + prompt.length() + ", maxTokens=" + actualMaxTokens + ", thinking=" + enableThinking);
        // 本地推理上下文独立化（2026-09-14）：历史由 ModelExecutionBridge 按「会话×模型」
        // 持久化管理，不再每次从 UI chatHistory 重建。此处仅同步本轮临时的 system 段
        //（提示词变更标记/历史压缩要点），并记录本次提示词签名
        if (modelBridge != null) {
            try {
                // 本地推理上下文独立化：发送前锚定当前会话（幂等），历史由桥按会话管理
                modelBridge.setLocalSessionId(currentSessionId);
                modelBridge.setPendingExtraSystemSections(buildNormalHistoryExtras());
                getChatContextBuilder().snapshotPromptSignature();
            } catch (Exception e) {
                AppLogger.aiW(TAG, "set extra system sections failed: " + e.getMessage());
            }
        }
        // ==================== NPU（Qualcomm GenieX）引擎分支 ====================
        // 关键：对话页的本地普通对话走的是 modelBridge.execute(...) → AIService（llama.cpp），
        // 根本不经过 InferenceRouter。所以 NPU 必须在这里分流，否则开了 NPU 也会去加载本地
        // GGUF（本机没有 → "模型文件不存在" / 卡在"准备模型文件"）。
        BridgeCallback bridgeCallback = createBridgeCallback(streamingIndex, streamingId);
        // 能力感知：深度思考（思考链）请求留在 llama.cpp —— NPU 侧载的 Qwen3 是纯对话模型，
        // 走 NPU 会让思考链能力变差（功能不降级）。多模态/工具链本来就不走这条分支。
        // 【已关闭顶层拦截】NPU 不在这一层抢走消息：必须先让 modelBridge → AIService → Agent
        // 决定"要不要调工具"，需要工具时再由 NpuEngineRouter.generateWithTools 走 NPU。
        // 否则 NPU 一开，所有消息都被截成普通对话（Agent 永远不启动）——2026-10-05 实测确认。
        if (false && isNpuEngineOn()) {
            AppLogger.ai(TAG, "NPU（GenieX）引擎：改走 NPU 流式推理，跳过 modelBridge/AIService");
            bridgeCallback.onGenerationStarted(streamingId);
            final long npuStartMs = System.currentTimeMillis();
            final StringBuilder npuFull = new StringBuilder();
            com.oilquiz.app.ai.refactor.AIInferenceCore.InferenceConfig npuConfig =
                    new com.oilquiz.app.ai.refactor.AIInferenceCore.InferenceConfig();
            npuConfig.maxTokens = actualMaxTokens;
            npuConfig.temperature = 0.7f;
            // 深度思考透传给 GenieX 的 enable_thinking（applyChatTemplate 第 4 参）
            npuConfig.enableThinking = enableThinking;
            npuConfig.history = chatHistory == null ? null : new java.util.ArrayList<>(chatHistory);
            inferenceRouter.generateStream(prompt, npuConfig, new StreamCallback() {
                @Override
                public void onToken(String token) {
                    npuFull.append(token);
                    bridgeCallback.onToken(streamingId, token);
                }

                @Override
                public void onComplete(String fullText) {
                    String text = fullText != null ? fullText : npuFull.toString();
                    long elapsed = System.currentTimeMillis() - npuStartMs;
                    bridgeCallback.onGenerationComplete(streamingId, text,
                            com.oilquiz.app.ai.engine.NpuLlmChat.getLastTokens(), elapsed,
                            com.oilquiz.app.ai.engine.NpuLlmChat.getLastTps());
                }

                @Override
                public void onError(String error) {
                    bridgeCallback.onGenerationError(streamingId, error);
                }
            });
            return;
        }

        modelBridge.execute(ChatCommand.sendMessage(streamingId, prompt, actualMaxTokens, enableThinking),
            bridgeCallback);
    }

    /**
     * 强制走本地 Agent 流程（用户点击拦截提示中的"🚀 强行使用本地Agent"触发）。
     * 可重复调用：每次点击都会把原始问题重新发送到本地 Agent 引擎执行。
     */
    private void forceRunLocalAgent(String message) {
        // R8-2：复活分支——localAgentEnabled 时真正路由到本地 Agent（startAgentLoop 内分流）
        if (aiConfig != null && aiConfig.isLocalAgentEnabled()) {
            processChatMessageWithAgent(message);
            return;
        }
        addSystemMessage("🚫 本地 Agent 已禁用\n\n请使用在线模型体验完整的 Agent 功能。\n\n切换方式：菜单 → 模型设置 → 选择在线模型", ChatMessage.SystemMessageType.WARNING);
    }

    /**
     * 显示新手引导信息（聊天历史为空时显示）。
     * 按当前实际架构精简：在线模型=完整 Agent，本地模型=普通对话，深度思考=模式。
     */
    private void showWelcomeGuide() {
        // 检查是否已配置模型（本地或在线）
        boolean hasLocalModel = false;
        boolean hasOnlineModel = false;
        try {
            if (aiService != null && aiService.getCurrentModelName() != null) {
                hasLocalModel = true;
            }
            if (onlineModelManager != null && onlineModelManager.getActiveModel() != null) {
                hasOnlineModel = true;
            }
        } catch (Exception ignored) {}

        if (!hasLocalModel && !hasOnlineModel) {
            // 没有配置任何模型：用原生对话框引导
            runOnUiThread(() -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.h_c1d12ffc))
                        .setMessage(getString(R.string.h_f81e293e) +
                                "📥 本地模型（推荐）\n" +
                                "• 离线可用，无需网络，支持工具调用（天气/搜索/记忆等）\n" +
                                "• 推荐 Qwen3.5-4B（Q4_0，约2.6GB，NPU 与 llama.cpp 通用）\n\n" +
                                "🌐 在线模型\n" +
                                "• 功能更强，支持 Agent 工具调用\n" +
                                "• 支持豆包、DeepSeek、通义千问等")
                        .setPositiveButton(getString(R.string.h_f0597ad8), (d, w) -> {
                            startActivity(new Intent(AIChatActivity.this, ModelDownloadActivity.class));
                        })
                        .setNegativeButton(getString(R.string.h_87e4d9ef), null)
                        .setCancelable(true)
                        .show();
            });
            return;
        }

        // 已有模型：弹窗显示使用说明，不发送到对话流
        // 根据当前模型类型（在线/本地）动态显示不同的工具说明
        boolean isOnlineModel = false;
        try {
            if (onlineModelManager != null && onlineModelManager.getActiveModel() != null) {
                isOnlineModel = true;
            }
        } catch (Exception ignored) {}

        final boolean online = isOnlineModel;
        // 延迟500ms显示，确保Activity完全初始化
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            String toolsInfo;
            String examples;
            if (online) {
                toolsInfo = "🛠 完整工具集（在线Agent）\n" +
                        "• 天气 ☁️ 搜索 🔍 计算 🔢 时间 🕐 定位 📍\n" +
                        "• 文件 📄 图片生成 🎨 UI组件 🖼️ 数据库 🗄️\n" +
                        "• 长期记忆 🧠 权限管理 🔐 工作区管理 📁\n" +
                        "• 工具发现 🔧 共40+工具按需调用";
                examples = "💡 试试对我说：\n" +
                        "「今天天气怎么样？」\n" +
                        "「帮我写一份周报」\n" +
                        "「画一只橘猫」\n" +
                        "「搜索一下最新油价」";
            } else {
                toolsInfo = "🛠 核心工具（本地Agent）\n" +
                        "• 天气 ☁️ 时间 🕐 定位 📍 搜索 🔍 记忆 🧠\n" +
                        "（常驻5个核心工具，模型调用到其他工具时自动动态加入）";
                examples = "💡 试试对我说：\n" +
                        "「今天天气怎么样？」\n" +
                        "「现在几点了？」\n" +
                        "「搜索一下最新油价」\n" +
                        "「记住我叫小明」";
            }

            new androidx.appcompat.app.AlertDialog.Builder(AIChatActivity.this)
                    .setTitle(getString(R.string.h_9e1bb02b))
                    .setMessage(getString(R.string.h_557e17b6) +
                            "🚀 当前模式：" + (online ? "在线模型（完整Agent）" : "本地模型（Agent）") + "\n" +
                            "• 在线模型 — 完整 Agent：自动调用40+工具、多轮推理\n" +
                            "• 本地模型 — Agent：5个核心工具（天气/时间/定位/搜索/记忆），无需网络\n" +
                            "• 深度思考 — 切换模式后，回答前会先展示思考过程\n\n" +
                            toolsInfo + "\n\n" +
                            examples)
                    .setPositiveButton(getString(R.string.h_ce26955a), null)
                    .setCancelable(true)
                    .show();
        }, 500);
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

        guide.append(getString(R.string.h_ffc2890f));

        // 根据用户消息内容智能推荐可能用到的工具
        boolean matched = false;
        if (containsKeyword(lower, "天气", "气温", "下雨", "温度", "weather", "空气质量", "预警")) {
            guide.append(getString(R.string.h_cd6a30e5));
            matched = true;
        }
        if (containsKeyword(lower, "搜索", "搜一下", "查一下", "查找", "search", "百度", "google", "最新")) {
            guide.append(getString(R.string.h_dc182f66));
            matched = true;
        }
        if (containsKeyword(lower, "翻译", "translate", "英文", "日文", "韩文")) {
            guide.append(getString(R.string.h_4fde242e));
            matched = true;
        }
        if (containsKeyword(lower, "题", "题库", "题目", "quiz", "question", "考试")) {
            guide.append(getString(R.string.h_4fd18118));
            matched = true;
        }
        if (containsKeyword(lower, "位置", "定位", "在哪", "location", "坐标", "附近")) {
            guide.append(getString(R.string.h_4fbec1c3));
            matched = true;
        }
        if (containsKeyword(lower, "出行", "出门", "准备", "带伞")) {
            guide.append(getString(R.string.h_cf8f2e41));
            matched = true;
        }
        if (containsKeyword(lower, "计算", "算", "calculate", "+", "-", "×", "÷")) {
            guide.append(getString(R.string.h_f1ebef0a));
            matched = true;
        }
        if (containsKeyword(lower, "时间", "日期", "今天", "明天", "几点", "星期")) {
            guide.append(getString(R.string.h_21a651d8));
            matched = true;
        }

        if (matched) {
            guide.append("\n");
        } else {
            guide.append(getString(R.string.h_8b243ef2));
        }

        guide.append(getString(R.string.h_b8c0f495));

        addSystemMessage(guide.toString());
        scrollToBottom();
    }

    /** 防御性硬上限：即使上下文预算很大，也最多携带这么多条历史（防极端场景拼装过慢） */
    private static final int HISTORY_MAX_ENTRIES_HARD_CAP = 60;

    /**
     * 工具结果保留策略（数据优先，不做粗暴一刀切）：
     * 工具结果是模型回答的事实依据——最近的结果保留完整（对齐引擎 MAX_TOOL_RESULT_LENGTH=6000），
     * 更早的结果才按新旧渐进收缩（2500→1200），避免"结果被截断→追问时模型答不上来"。
     */
    private static final int TOOL_RESULT_KEEP_MAX = 6000;
    private static final int TOOL_RESULT_MID_MAX = 2500;
    private static final int TOOL_RESULT_OLD_MAX = 1200;

    /** 历史要点里单条工具结果的最大字符数（要点是压缩态，但优先保留数据而非对话废话） */
    private static final int KEY_POINT_RESULT_MAX = 300;



    /** 本轮本地 Agent 的用户消息（完成回调回写本地推理历史用；本地推理上下文独立化 2026-09-14） */
    private volatile String lastAgentUserMessage = null;



    /**
     * 给最后一条 user 消息标记轮次模式（普通/Agent）。
     * 仅作历史/会话记录的轮次标识（持久化保留）；上下文构建已不按此隔离，
     * 普通↔Agent 共享同一份完整对话历史（buildNormalHistoryEntries/buildAgentHistory）。
     */
    private void markLastUserMessageMode(int mode) {
        for (int i = chatHistory.size() - 1; i >= 0; i--) {
            ChatMessage m = chatHistory.get(i);
            if (m != null && "user".equals(m.getRole())) {
                m.turnMode = mode;
                return;
            }
        }
    }

    // ==================== 上下文组装（KV 预算 + 提示词变更标记 + 工具/思考痕迹） ====================

    /** 上下文组装器（ChatContextBuilder 抽取版，全局可复用）：KV 预算/历史压缩/变更标记统一由此计算 */
    private com.oilquiz.app.ai.chat.context.ChatContextBuilder chatContextBuilder;

    private com.oilquiz.app.ai.chat.context.ChatContextBuilder getChatContextBuilder() {
        if (chatContextBuilder == null) {
            chatContextBuilder = new com.oilquiz.app.ai.chat.context.ChatContextBuilder(this,
                    new com.oilquiz.app.ai.chat.context.ChatContextBuilder.Config() {
                        @Override public boolean isOnlineModel() { return shouldUseOnlineModel(); }
                        @Override public int getNativeContextSize() {
                            try { int a = LlamaHelper.getContextSize(); return a > 0 ? a : 0; }
                            catch (Throwable t) { return 0; }
                        }
                        @Override public int getSafeContextReference(int ctx) {
                            try { return LlamaHelper.getSafeContextReference(ctx); }
                            catch (Throwable t) { return 0; }
                        }
                        @Override public int getModelContextSize() {
                            return aiConfig != null ? aiConfig.getContextSize() : 0;
                        }
                        @Override public boolean isLocalAgentEnabled() {
                            return aiConfig != null && aiConfig.isLocalAgentEnabled();
                        }
                        @Override public String getSystemPrompt() {
                            return aiConfig != null ? aiConfig.getSystemPrompt() : null;
                        }
                        @Override public int getAgentContextWindow() {
                            if (agentChatHandler != null) {
                                try {
                                    int[] info = agentChatHandler.getContextWindowInfo();
                                    if (info != null && info.length > 0 && info[0] > 0) return info[0];
                                } catch (Exception ignored) {}
                            }
                            return 0;
                        }
                    });
        }
        return chatContextBuilder;
    }

    /** 粗略估算文本 token 数：中文约 2 字符/token 的保守估计 + 角色开销（宁多勿少，防溢出） */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return 4 + (text.length() + 1) / 2;
    }

    /**
     * 可给历史上下文使用的 token 预算：
     *
     * 在线模型：上下文由在线引擎自己的机制管理（会话历史 + 引擎内部按模型真实窗口
     * 动态预算 + 服务端 KV 前缀缓存），本组装层不设 token 限制、不设条数上限——
     * 完全放开，连续对话不截断。且在线路径本身忽略此处组装的历史，此值只是
     * "不干预"的语义。
     *
     * 本地模型：与 native chatJson 守卫对齐——守卫只校验 prompt 本体不超过
     * safeRef-512（生成余量固定 512，max_tokens 不算入 context 预算），
     * 这里再预留当前待发送消息与标记的余量（-1024），让窗口尽量大且稳定，
     * 跨轮保持前缀不变 → KV 缓存可命中。
     */
    private int getContextBudgetTokens() {
        return getChatContextBuilder().getContextBudgetTokens();
    }

    /** 历史条数硬上限：本地 60（token 预算已优先约束，此值仅作防御护栏）；在线不设上限 */
    private int getHistoryMaxEntries() {
        return getChatContextBuilder().getHistoryMaxEntries();
    }

    /** 当前提示词签名：思考开关 / 本地Agent开关 / 系统提示词 / 日期（这些变化都会改变生成行为） */
    private String currentPromptSignature() {
        return getChatContextBuilder().currentPromptSignature();
    }

    /** 与上次相比，提示词设置是否有变化（返回变化说明；无变化返回 null） */
    private String describePromptChange(String prev, String cur) {
        return getChatContextBuilder().describePromptChange(prev, cur);
    }

    /** 若提示词设置相对上次发送有变化，生成系统标记消息（普通对话路径注入；Agent 引擎每轮重建 system 无需） */
    private String getPromptChangeMarkerIfAny() {
        return getChatContextBuilder().getPromptChangeMarkerIfAny();
    }

    /**
     * 从 AI 消息的工具卡片组件提取工具痕迹（工具名 + 结果）。
     * maxResultChars：结果保留上限——最近结果传 6000 保持完整数据，旧结果传小值渐进压缩；
     * 只留已完成的 success/failed 卡片。返回 null 表示无工具痕迹；不修改原消息。
     */
    private String buildToolTrace(ChatMessage m, int maxResultChars) {
        return getChatContextBuilder().buildToolTrace(m, maxResultChars);
    }

    /** 消息是否携带工具调用组件（用于决定工具结果保留档位） */
    private boolean hasToolComponents(ChatMessage m) {
        if (m == null || m.components == null || m.components.isEmpty()) return false;
        for (com.oilquiz.app.ai.chat.component.ComponentData c : m.components) {
            if (c != null && "tool_call".equals(c.type)) return true;
        }
        return false;
    }

    /**
     * 组装消息进入上下文的文本：AI 消息附工具调用痕迹（思考内容可选，截断防膨胀）。
     * toolResultCap：本条消息工具结果的保留上限（见 TOOL_RESULT_* 档位）。
     * 只用于上下文构建，不修改原消息。返回 null 表示该消息不应进上下文。
     */
    private String buildContextContent(ChatMessage m, boolean includeThinking, int toolResultCap) {
        if (m == null) return null;
        String base = m.getContent();
        if (base == null) base = "";
        StringBuilder sb = new StringBuilder(base);
        if (m.type == ChatMessage.MessageType.AI) {
            String trace = buildToolTrace(m, toolResultCap);
            if (trace != null) {
                sb.append("\n\n[工具调用]\n").append(trace);
            }
            if (includeThinking && m.thinkingContent != null && !m.thinkingContent.trim().isEmpty()) {
                String th = m.thinkingContent.trim();
                if (th.length() > 512) th = th.substring(0, 512) + "…";
                sb.append("\n\n[思考过程]\n").append(th);
            }
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** 在 token 预算内倒序收集历史条目（返回 {role, content} 倒序列表）。
     *  数据优先：最近的工具结果保留完整（6000），旧结果按档位渐进压缩（2500→1200）；
     *  预算耗尽时把被挤掉的最近对话做成"历史要点"存到 evictedContextPoints
     *  （最新优先、最多 4 条；助手消息的要点优先保留工具结果数据而非对话废话），
     *  由调用方注入上下文——与本地 Agent 引擎 trimHistoryToFit 同款轻量压缩策略。 */
    private java.util.List<String[]> collectHistoryByBudget(List<ChatMessage> history,
                                                            int currentUserIdx,
                                                            boolean includeThinking) {
        return getChatContextBuilder().collectHistoryByBudget(history, currentUserIdx, includeThinking);
    }

    /** 合并相邻同角色（失败/中断导致缺回复时保持 user/assistant 严格交替） */
    private java.util.List<String[]> mergeConsecutiveSameRole(java.util.List<String[]> entries) {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        String lastRole = null;
        for (String[] entry : entries) {
            if (entry[0].equals(lastRole) && !result.isEmpty()) {
                String[] last = result.get(result.size() - 1);
                last[1] = last[1] + "\n\n" + entry[1];
            } else {
                result.add(new String[]{entry[0], entry[1]});
                lastRole = entry[0];
            }
        }
        return result;
    }

    /** 定位最后一条 user 消息索引（即当前待发送消息）；无则返回 -1 */
    private int lastUserMessageIndex(List<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (m != null && m.type == ChatMessage.MessageType.USER) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从 UI 会话历史构建普通对话的上下文轮次（{role, content} 对）。
     * 与本地 Agent（buildAgentHistory）同源：统一以 UI 历史为上下文真相源，
     * 普通↔Agent 来回切换上下文天然连续（两模式共享同一份完整对话，切换不失忆）。
     *
     * 健壮性（防上下文崩溃与提示词污染）：
     * 1. 仅取 USER/AI 类型消息，排除工具/汇总/系统等特殊消息（其 getRole 可能返回 "user"，
     *    混入会破坏 user/assistant 交替导致 native 模板畸形）；
     * 2. 跳过失败/错误/生成中的占位消息（"生成失败"文本不进上下文）；
     * 3. 合并连续同角色消息（某轮回复失败/中断时保持严格交替）；
     * 4. 按 KV 上下文预算（contextSize - maxTokens - 预留）组装，而非固定条数；
     * 5. 预算超限时把被挤掉的最近对话压缩成「历史要点」注入（历史压缩，不静默丢弃）；
     * 6. 提示词设置（思考/Agent/系统提示词/日期）变化时注入系统标记，防旧指令污染；
     * 7. AI 消息附紧凑工具调用痕迹（结果截断），保证跨模式追问时模型记得工具结论。
     * 排除当前待发送的 user 消息（buildChatJsonRequest 会追加它）。
     */
    /**
     * 本轮临时的 system 段（提示词变更标记 + 历史压缩要点）。
     * 本地推理上下文独立化（2026-09-14）后，对话历史由 ModelExecutionBridge
     * 按「会话×模型」持久化管理，UI chatHistory 只负责界面显示；这里只同步
     * "轮次性"的额外信息（设置变化标记/历史要点），仅本轮请求有效，不入持久化。
     */
    private java.util.List<String[]> buildNormalHistoryExtras() {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        // dsh 分段语义：动态附加段由组装器统一注册/排序（命名段、可插拔、同名覆盖），
        // 输出仍为独立 system 消息列表，与旧行为完全一致
        com.oilquiz.app.ai.prompt.PromptAssembler assembler = new com.oilquiz.app.ai.prompt.PromptAssembler();
        String marker = getPromptChangeMarkerIfAny();
        if (marker != null) {
            assembler.registerSection(com.oilquiz.app.ai.prompt.PromptSection.of(
                    "prompt_change", 0, marker));
        }
        java.util.List<String> evicted = getChatContextBuilder().getEvictedContextPoints();
        if (evicted != null && !evicted.isEmpty()) {
            StringBuilder pts = new StringBuilder("【历史对话要点】(较早对话已压缩，上下文有限)\n");
            for (String p : evicted) {
                pts.append("• ").append(p).append('\n');
            }
            assembler.registerSection(com.oilquiz.app.ai.prompt.PromptSection.of(
                    "history_points", 100, pts.toString().trim()));
        }
        com.oilquiz.app.ai.prompt.PromptAssembly assembly =
                assembler.assemble(com.oilquiz.app.ai.prompt.AssembleContext.global());
        for (com.oilquiz.app.ai.prompt.PromptAssembly.Section s : assembly.sections) {
            result.add(new String[]{"system", s.text});
        }
        return result;
    }

    /**
     * 平滑迁移（本地推理上下文独立化 2026-09-14）：从 UI 会话消息构建一次性迁移 entries。
     * 仅用于"旧版本聊过的会话首次切回且无独立推理历史文件"时；只取 USER/AI 干净正文，
     * 跳过失败/生成中占位，剥离 [工具调用]/[思考过程] 段；预算由桥侧裁剪。
     */
    private java.util.List<String[]> buildMigrationEntries(java.util.List<ChatMessage> messages) {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        if (messages == null) return result;
        String lastRole = null;
        for (ChatMessage m : messages) {
            if (m == null) continue;
            if (m.type != ChatMessage.MessageType.USER && m.type != ChatMessage.MessageType.AI) continue;
            if (m.status == ChatMessage.MessageStatus.GENERATING
                    || m.status == ChatMessage.MessageStatus.FAILED
                    || m.status == ChatMessage.MessageStatus.ERROR) continue;
            String content = m.getContent();
            if (content == null || content.trim().isEmpty()) continue;
            if (m.type == ChatMessage.MessageType.AI) {
                content = stripContextSections(content);
            }
            if (content == null || content.trim().isEmpty()) continue;
            String role = m.getRole();
            // 合并连续同角色，保持 user/assistant 严格交替（防畸形上下文）
            if (role != null && role.equals(lastRole) && !result.isEmpty()) {
                String[] last = result.get(result.size() - 1);
                last[1] = last[1] + "\n\n" + content;
            } else {
                result.add(new String[]{role, content});
                lastRole = role;
            }
        }
        return result;
    }

    /**
     * 剥离上下文文本中的 [工具调用] / [思考过程] 段落（本地 Agent 上下文过滤用）。
     * 在线 Agent 消息渲染时在正文后追加这两段，本地小模型不需要也不兼容这些格式。
     */
    private static String stripContextSections(String content) {        if (content == null || content.isEmpty()) return content;
        int cut = -1;
        int i = content.indexOf("\n\n[工具调用]");
        int j = content.indexOf("\n\n[思考过程]");
        if (i >= 0) cut = i;
        if (j >= 0) cut = (cut < 0) ? j : Math.min(cut, j);
        return cut >= 0 ? content.substring(0, cut).trim() : content;
    }

    private void processChatMessageWithAgent(String message) {
        try {
            synchronized (streamingLock) {
                if (isGenerating) {
                    AppLogger.aiW(TAG, "processChatMessageWithAgent skipped, already generating");
                    showToast(getString(R.string.h_05582e8e));
                    return;
                }
            }

            // 检测是否使用在线模型
            boolean useOnlineModel = inferenceRouter != null && inferenceRouter.isUsingOnlineModel();

            // 本地模型：默认降级普通对话；仅当显式开启"本地 Agent"实验开关时放行，
            // 由 AgentChatHandler.startAgentLoop 内部再分流到 AgentSoftwareLayer（本地可调用工具）
            boolean localAgentEnabled = aiConfig != null && aiConfig.isLocalAgentEnabled();
            if (!useOnlineModel && !localAgentEnabled) {
                addSystemMessage("🤖 本地 Agent 未开启，已使用普通对话；开启“本地 Agent”开关后，本地模型可调用天气/时间/位置/搜索/记忆等工具。");
                processChatMessageNormal(message);
                return;
            }

            // 在线视觉追问：上一轮发过图、当前纯文字且在线模型支持视觉 → 自动复用该图做视觉理解
            // （置于 Agent 引擎就绪检查前，看图问答不必走完整 Agent 工具循环）
            if (useOnlineModel && tryFollowUpOnlineVision(message)) {
                return;
            }

            // Agent 引擎就绪检查（在线路径）
            initAgentChatHandlerIfNeeded();
            if (agentChatHandler == null) {
                AppLogger.aiW(TAG, "AgentChatHandler 未就绪，降级为普通对话");
                addSystemMessage("🤖 Agent 引擎未就绪，已降级为普通对话");
                processChatMessageNormal(message);
                return;
            }

            // 确认走 Agent 路径：标记轮次模式（历史/会话记录用；降级/视觉追问已提前 return）
            markLastUserMessageMode(ChatMessage.TURN_MODE_AGENT);

            // 同步引擎会话：跟随当前 UI 会话（新对话/清空后 currentSessionId 可能已变化）
            if (agentChatHandler != null) {
                // 首次发送（尚无会话 ID）时同步创建会话，使引擎历史锚定到该会话文件。
                // 与 saveHistoryAsync 用同一把锁互斥，避免并发创建重复会话。
                synchronized (this) {
                    if ((currentSessionId == null || currentSessionId.isEmpty())
                            && chatHistoryManager != null && !chatHistory.isEmpty()) {
                        ConversationSession s = chatHistoryManager.saveCurrentChatAsSession(
                                new ArrayList<>(chatHistory), null);
                        if (s != null && s.id != null) {
                            currentSessionId = s.id;
                        }
                    }
                }
                agentChatHandler.setSessionId(currentSessionId);
                // 本地推理上下文独立化：同步锚定会话。
                // 首次创建会话（default→新会话）时先迁移推理历史，Agent 首轮不失忆
                if (modelBridge != null) {
                    modelBridge.migrateCurrentHistoryToSession(currentSessionId);
                    modelBridge.setLocalSessionId(currentSessionId); // 幂等，同会话不重载
                }
            }

            // 显示 Agent 模式激活提示
            addSystemMessage("🤖 Agent模式已激活，正在处理您的请求...");

            // 在线模型模式：无需等待本地模型初始化（本地/在线已解绑，避免"AI服务初始化"提示与无限等待拖慢进程）
            // 已确认 useOnlineModel=true 才走到这里

            // AI 服务已初始化，继续处理
            initAgentChatHandlerIfNeeded();
            // 模型切换兜底同步：若 onActiveModelChanged 时引擎正生成导致 setModelId 被忽略
            //（生成中切换历史文件会污染执行中的 messageHistory），这里在发送前补一次。
            // 幂等：模型未变化时 setModelId 内部直接 return，无额外开销
            if (useOnlineModel && agentChatHandler != null) {
                String curModelId = onlineModelManager != null && onlineModelManager.getActiveModel() != null
                        ? onlineModelManager.getActiveModel().id : null;
                agentChatHandler.setModelId(curModelId);
            }

            synchronized (streamingLock) {
                agentToolLoopCount = 0;
                thinkingRoundEnded = false;
                thinkingRoundCount = 1;
                agentGroupStepCount = 0;
                agentGroupToolCount = 0;
                agentToolNames.clear();
                currentStreamingContent = new StringBuilder();
                currentThinkingContent = new StringBuilder();
                currentStreamingMessageId = java.util.UUID.randomUUID().toString();
                resetStreamingTts();
                resetStreamingState();

                ChatMessage initialMessage = ChatMessage.createAIMessage(currentStreamingMessageId, "", System.currentTimeMillis(), null, 0, 0);
                initialMessage.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.INITIALIZING);
                initialMessage.status = ChatMessage.MessageStatus.GENERATING;
                initialMessage.turnId = currentTurnId; // 消息对绑定
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
            // 思考链：仅深度思考模式启用。
            // 本地 Agent 路径强制关闭 thinking——Qwen3-4B 在 thinking+FC 组合下
            // 思考完会"忘记"调用工具（直接回答"无法获取"而非输出 tool_call），
            // 非思考 FC 模式工具调用更稳定；在线 Agent 保留深度思考
            //（OnlineAgentEngine 有门控+reasoning_content 规范化，切换安全）。
            // 深度思考完整体验由普通对话路径承载。
            boolean localAgentRoute = !useOnlineModel && localAgentEnabled;
            boolean enableThinking = ChatModeManager.getInstance(this).isDeepThinkingEnabled()
                    && !localAgentRoute;
            if (outputRouter != null) {
                outputRouter.reset();
                outputRouter.setThinkingEnabled(enableThinking);
                outputRouter.setThinkingTags(LlamaHelper.getThinkingTags());
                legacyThinkingTags = LlamaHelper.getThinkingTags();
            }
            isInThinking = enableThinking;
            // 每轮新执行前重置回调完成标志（AgentChatHandler 复用，防止上一轮的 completed=true
            // 导致本轮 onComplete 被幂等保护跳过 → 回复不处理、UI 卡"处理中"）
            if (agentCallback != null) {
                agentCallback.resetForNewTurn();
            }
            // 生成状态监控：Agent 生成可能较慢（本地模型），启动即显示处理中状态，
            // 避免用户"一直等待"无反馈（onThinkingToken/onToolCallStart/onComplete 会覆盖更新）
            updateAgentStatusBar("⏳ 模型处理中...", true);
            // 多轮上下文：本地推理上下文独立化（2026-09-14）——从 ModelExecutionBridge 的
            // 会话级历史取（与普通对话同一真相源，普通↔Agent 来回切换不失忆）；
            // 在线引擎自带会话历史，忽略该参数
            java.util.List<AgentLoopEngine.HistoryEntry> agentHistory = new java.util.ArrayList<>();
            if (modelBridge != null) {
                for (String[] e : modelBridge.getLocalHistoryEntries()) {
                    agentHistory.add(new AgentLoopEngine.HistoryEntry(e[0], e[1]));
                }
            }
            lastAgentUserMessage = message; // 完成回调回写本地推理历史用
            agentChatHandler.startAgentLoop(message, maxTokens, enableThinking, agentHistory);

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
                    showToast(getString(R.string.h_05582e8e));
                    return;
                }
            }

            synchronized (streamingLock) {
                currentStreamingContent = new StringBuilder();
                currentStreamingMessageId = java.util.UUID.randomUUID().toString();
                resetStreamingTts();

                ChatMessage initialMessage = ChatMessage.createAIMessage(currentStreamingMessageId, "", System.currentTimeMillis(), null, 0, 0);
                initialMessage.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.INITIALIZING);
                initialMessage.status = ChatMessage.MessageStatus.GENERATING;
                initialMessage.turnId = currentTurnId; // 消息对绑定
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
                    runOnUiThread(() -> updateInferencePhase(resolveStreamingIndex(), ChatMessage.InferencePhase.ENCODING, "正在连接云端模型..."));

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
                            final int idx = resolveStreamingIndex();
                            if (chatAdapter != null && idx >= 0) {
                                ChatMessage msg = chatHistory.get(idx);
                                if (currentStreamingContent != null) {
                                    msg.content = currentStreamingContent.toString();
                                }
                                msg.status = ChatMessage.MessageStatus.GENERATING;
                                chatAdapter.updateAIMessageContent(idx, msg.content);
                                scrollToBottom();
                            }
                        };

                        @Override
                        public void onStart() {
                            // 重置在线统计字段
                            onlinePromptTokens = 0;
                            onlineCompletionTokens = 0;
                            onlineStatsReceiveTime = 0L;
                            runOnUiThread(() -> updateInferencePhase(resolveStreamingIndex(), ChatMessage.InferencePhase.ENCODING, "云端模型正在思考..."));
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
                                final int idx = resolveStreamingIndex();
                                if (idx >= 0) {
                                    ChatMessage msg = chatHistory.get(idx);
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
                                        chatAdapter.notifyItemChanged(idx);
                                        if (finalCompletionTokens > 0 && elapsedMs > 0) {
                                            chatAdapter.updateMessageGenerationStats(
                                                    idx, finalCompletionTokens, elapsedMs);
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
            final int idx = resolveStreamingIndex();
            // 生成仍存活(id 非空)才处理流式消息；结束后仅追加错误消息
            if (idx >= 0 && currentStreamingMessageId != null) {
                if (currentStreamingContent != null && currentStreamingContent.length() > 0) {
                    ChatMessage msg = chatHistory.get(idx);
                    msg.content = currentStreamingContent.toString();
                    msg.status = ChatMessage.MessageStatus.COMPLETED;
                    msg.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.FAILED);
                    if (chatAdapter != null) chatAdapter.notifyItemChanged(idx);
                    addSystemMessage(errorMsg);
                } else {
                    // 批量修改：先全部改数据，再一次 notifyDataSetChanged，
                    // 避免同一批次 insert+remove 混合 op 触发 RecyclerView Inconsistency 崩溃（offset:-1）
                    chatHistory.remove(idx);
                    chatHistory.add(ChatMessage.createSystemMessage(
                            java.util.UUID.randomUUID().toString(), errorMsg,
                            ChatMessage.SystemMessageType.INFO, System.currentTimeMillis()));
                    if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                    saveHistoryAsync();
                }
            } else {
                addSystemMessage(errorMsg);
            }
            currentStreamingContent = null;
            thinkingRoundEnded = false;
            thinkingRoundCount = 1;
            currentStreamingMessageIndex = -1;
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
                if (uiDetached) return;
                runOnUiThread(() -> updateInferencePhase(resolveStreamingIndex(), ChatMessage.InferencePhase.GENERATING, null));
            }

            @Override
            public void onToken(String messageId, String token) {
                if (uiDetached) return;
                tokenCount[0]++;
                if (tokenCount[0] % 10 == 0) {
                    // 优先使用 native 层统计，更准确
                    float nativeTps = LlamaHelper.getInferenceSpeed();
                    int nativeTokens = LlamaHelper.getTokenCount();
                    long elapsed = System.currentTimeMillis() - startTime[0];
                    float tps = nativeTps > 0 ? nativeTps : 
                        (elapsed > 0 ? (tokenCount[0] * 1000.0f) / elapsed : 0);
                    int tokens = nativeTokens > 0 ? nativeTokens : tokenCount[0];
                    runOnUiThread(() -> updateInferenceProgress(resolveStreamingIndex(), tokens, tps));
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
                    if (uiDetached) {
                        // UI 已分离（退出/重建）：只把已生成的部分内容落盘，不更新界面
                        final int idx = resolveStreamingIndex();
                        if (currentStreamingContent != null && currentStreamingContent.length() > 0
                                && idx >= 0 && idx < chatHistory.size()) {
                            ChatMessage msg = chatHistory.get(idx);
                            if (msg != null) {
                                msg.content = currentStreamingContent.toString();
                                msg.status = ChatMessage.MessageStatus.COMPLETED;
                            }
                        }
                        saveHistoryAsync();
                        currentStreamingContent = null;
                        currentStreamingMessageIndex = -1;
                        currentStreamingMessageId = null;
                        return;
                    }
                    endGeneration();
                    // NPU 引擎下不算"native 失效"：那条路不依赖 llama.cpp 的 native 上下文
                    boolean nativeInvalid = !isNpuEngineOn() && !modelBridge.isNativeStateValid();
                    boolean shouldRecover = nativeInvalid &&
                        (recoveryHandler == null || !recoveryHandler.isRecovering()) &&
                        (serviceStatusManager == null || !serviceStatusManager.isLoadingModel());

                    final int idx = resolveStreamingIndex();
                    if (currentStreamingContent != null && currentStreamingContent.length() > 0
                        && idx >= 0) {
                        ChatMessage msg = chatHistory.get(idx);
                        msg.content = currentStreamingContent.toString();
                        msg.status = ChatMessage.MessageStatus.COMPLETED;
                        if (nativeInvalid) {
                            msg.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.FAILED);
                        }
                        if (chatAdapter != null) chatAdapter.notifyItemChanged(idx);
                        if (!nativeInvalid) addSystemMessage("生成中断，已保存部分内容");
                    } else if (idx >= 0 && currentStreamingMessageId != null) {
                        // 批量修改：先移除 + 一次批量通知，后续 addErrorMessage 是纯队尾插入，
                        // 与 remove 分属不同批次，避免 insert+remove 混合 op 触发 Inconsistency
                        chatHistory.remove(idx);
                        if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
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
                    if (uiDetached) {
                        // UI 已分离：只落盘已生成的部分内容
                        final int idx = resolveStreamingIndex();
                        if (currentStreamingContent != null && currentStreamingContent.length() > 0
                                && idx >= 0 && idx < chatHistory.size()) {
                            ChatMessage msg = chatHistory.get(idx);
                            if (msg != null) {
                                msg.content = currentStreamingContent.toString();
                                msg.status = ChatMessage.MessageStatus.COMPLETED;
                            }
                        }
                        saveHistoryAsync();
                        currentStreamingContent = null;
                        currentStreamingMessageIndex = -1;
                        currentStreamingMessageId = null;
                        return;
                    }
                    endGeneration();
                    final int idx = resolveStreamingIndex();
                    if (currentStreamingContent != null && currentStreamingContent.length() > 0
                        && idx >= 0) {
                        ChatMessage msg = chatHistory.get(idx);
                        msg.content = currentStreamingContent.toString();
                        msg.status = ChatMessage.MessageStatus.COMPLETED;
                        if (chatAdapter != null) chatAdapter.notifyItemChanged(idx);
                    }
                    saveHistoryAsync();
                    currentStreamingContent = null;
                    currentStreamingMessageIndex = -1;
                    currentStreamingMessageId = null;
                });
            }

            @Override
            public void onInferenceProgress(String messageId, int tokens, float tps) {
                if (uiDetached) return;
                runOnUiThread(() -> updateInferenceProgress(streamingIndex, tokens, tps));
            }

            @Override
            public void onContextCleared() {
                if (uiDetached) return;
                runOnUiThread(() -> addSystemMessage("上下文已清除"));
            }

            @Override
            public void onContextInitialized(boolean success) {}

            @Override
            public void onModelInitialized(boolean success, String modelName) {
                if (uiDetached) return;
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
                                          String title, String content, int progress) {
                // 普通对话 chatJson reasoning 事件：思考内容写入思考区（折叠显示）
                if (content == null || content.isEmpty()) return;
                appendBridgeThinkingToken(content);
            }
        };
    }

    /**
     * Bridge 普通对话的思考更新（chatJson reasoning 事件）：写入思考区（msg.thinkingContent）。
     * 与 Agent 路径的 appendAgentThinkingToken 对应；思考默认折叠、可点击展开。
     */
    private void appendBridgeThinkingToken(String token) {
        if (token == null || token.isEmpty()) return;
        final int idx = resolveStreamingIndex();
        if (idx < 0) return;
        String snapshot;
        synchronized (streamingLock) {
            if (currentThinkingContent == null) {
                currentThinkingContent = new StringBuilder();
            }
            currentThinkingContent.append(token);
            snapshot = currentThinkingContent.toString();
        }
        isInThinking = true;
        ChatMessage msg = chatHistory.get(idx);
        msg.thinkingContent = snapshot;
        // 定时渲染（120ms 批量），防每 reasoning 事件 notify 导致思考区抽搐
        scheduleThinkingRefresh(idx);
    }

    /**
     * 更新 AI 消息气泡内的 Agent 执行步骤状态行。
     */
    private void setAgentStepStatus(String status) {
        if (status == null || status.isEmpty()) return;
        final int idx = resolveStreamingIndex();
        if (idx < 0 || idx >= chatHistory.size()) return;
        ChatMessage msg = chatHistory.get(idx);
        msg.agentStepStatus = status;
        if (chatAdapter != null) {
            chatAdapter.notifyItemChanged(idx, ChatAdapter.PAYLOAD_STATUS_UPDATE);
        }
    }

    /**
     * Agent 模式思考 token 直接路由到思考布局（msg.thinkingContent）。
     * 保证所有模式下思考内容都显示在思考布局中，不泄漏到主消息。
     */
    private void appendAgentThinkingToken(String token) {
        if (token == null || token.isEmpty()) return;
        final int idx = resolveStreamingIndex();
        if (idx < 0) return;
        String snapshot;
        synchronized (streamingLock) {
            if (currentThinkingContent == null) {
                currentThinkingContent = new StringBuilder();
            }
            currentThinkingContent.append(token);
            snapshot = currentThinkingContent.toString();
        }
        isInThinking = true;
        ChatMessage msg = chatHistory.get(idx);
        msg.thinkingContent = snapshot;
        // 注意：不在此处设置 thinkingExpanded=false（保持用户手动展开状态），
        // 折叠动作只在「新一轮思考开始」时执行一次（见 onThinkingToken）
        // 定时渲染（120ms 批量），防每 token notify 导致思考区抽搐
        scheduleThinkingRefresh(idx);
    }

    /**
     * Agent 思考结束：把最终思考内容写入 AI 消息内嵌思考区并折叠（用户可点击展开）。
     * 同时把本轮内容追加到 thinkingRounds（多轮独立展示）。
     */
    private void finalizeAgentThinking() {
        final int idx = resolveStreamingIndex();
        if (idx < 0) return;
        String snapshot;
        synchronized (streamingLock) {
            snapshot = currentThinkingContent != null ? currentThinkingContent.toString() : "";
        }
        ChatMessage msg = chatHistory.get(idx);
        if (msg == null) return;
        if (!snapshot.isEmpty()) {
            msg.thinkingContent = snapshot;
            msg.addThinkingRound(snapshot);
        }
        msg.thinkingExpanded = false; // 思考完毕自动折叠，用户可点击重新展开
        cancelThinkingRefresh(); // 取消待执行的思考节流刷新（下面全量 notify 已带最新 thinkingContent）
        if (chatAdapter != null) {
            chatAdapter.notifyItemChanged(idx);
        }
        // 顶部状态机恢复（思考预览清除，后续工具/完成状态会继续覆盖）
        if (serviceStatusText != null) serviceStatusText.setText("✅ 思考完成");
        if (serviceStatusProgress != null) serviceStatusProgress.setVisibility(View.GONE);
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
                runOnUiThread(() -> updateInferencePhase(resolveStreamingIndex(), ChatMessage.InferencePhase.GENERATING, null));
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
                // NPU 引擎下不算"native 失效"（不依赖 llama.cpp native 上下文）
                boolean nativeInvalid = !isNpuEngineOn() && !modelBridge.isNativeStateValid();
                boolean shouldRecover = nativeInvalid && (recoveryHandler == null || !recoveryHandler.isRecovering()) && (serviceStatusManager == null || !serviceStatusManager.isLoadingModel());
                
                if (currentStreamingContent != null && currentStreamingContent.length() > 0) {
                    final int idx = resolveStreamingIndex();
                    if (idx >= 0) {
                        ChatMessage msg = chatHistory.get(idx);
                        msg.content = currentStreamingContent.toString();
                        msg.status = ChatMessage.MessageStatus.COMPLETED;
                        if (nativeInvalid) {
                            msg.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.FAILED);
                        }
                        if (chatAdapter != null) chatAdapter.notifyItemChanged(idx);
                        if (!nativeInvalid) {
                            addSystemMessage("生成中断，已保存部分内容");
                        }
                    }
                } else {
                    final int idx = resolveStreamingIndex();
                    if (idx >= 0 && currentStreamingMessageId != null) {
                        // 批量修改 + 一次批量通知，避免 insert+remove 混合 op 触发 Inconsistency
                        chatHistory.remove(idx);
                        if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                    }
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
            final int idx = resolveStreamingIndex();
            if (idx >= 0) {
                String thinkingSnapshot;
                synchronized (streamingLock) {
                    thinkingSnapshot = currentThinkingContent != null ? currentThinkingContent.toString() : "";
                }
                ChatMessage msg = chatHistory.get(idx);
                msg.thinkingContent = thinkingSnapshot;
                cancelThinkingRefresh(); // 全量 notify 已带最新 thinkingContent，取消待执行的节流刷新
                if (chatAdapter != null) chatAdapter.notifyItemChanged(idx);
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
            final int idx = resolveStreamingIndex();
            if (idx >= 0) {
                ChatMessage msg = chatHistory.get(idx);
                msg.thinkingContent = thinkingSnapshot;
                // 定时渲染（120ms 批量），防每 token notify 导致思考区抽搐
                scheduleThinkingRefresh(idx);
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
        // 传 beginGeneration 记录的真实开始时间；未记录时传 0（内部有 >0 防御，
        // 避免 chatStartTime=0 导致 totalTime=当前时间戳 的"29787149m"天文耗时）
        completeGeneration(fullText, 0, generationStartTime > 0 ? generationStartTime : 0);
    }

    private void completeGeneration(String fullText, int tokenCount, long chatStartTime) {
        // 通知 OutputRouter 流式完成
        if (outputRouter != null) {
            outputRouter.complete();
        }

        runOnUiThread(() -> {
            // UI 已分离（界面退出/重建）：生成结果只落盘不更新界面；
            // 重进界面通过文件恢复 + 热加载轮询拿到完整内容
            if (uiDetached) {
                final int idx = resolveStreamingIndex();
                final String finalContent;
                synchronized (streamingLock) {
                    finalContent = currentStreamingContent != null ? currentStreamingContent.toString() : fullText;
                }
                if (idx >= 0 && idx < chatHistory.size()) {
                    ChatMessage msg = chatHistory.get(idx);
                    if (msg != null) {
                        msg.content = finalContent;
                        msg.status = ChatMessage.MessageStatus.COMPLETED;
                    }
                }
                saveHistoryAsync();
                currentStreamingContent = null;
                currentStreamingMessageIndex = -1;
                currentStreamingMessageId = null;
                return;
            }
            // 幂等保护：重复/迟到的完成事件（onComplete 后又补发 onError 等）直接忽略，
            // 避免二次执行导致消息索引错乱（RecyclerView Inconsistency 崩溃根因之一）
            if (currentStreamingMessageId == null) return;
            // 关键：按 streamingId 定位（不用索引），避免 thinking/tool 插入后索引漂移指向错误消息
            final String streamingIdAtComplete = currentStreamingMessageId;
            final int messageIndex = resolveStreamingIndex();
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
            // Agent 汇总的 token 数用引擎累计（含多轮工具调用全部输入/输出），
            // 避免 streamingUpdateManager 只统计 UI 流式 token 导致汇总卡片显示 0
            int agentTotalTokens = 0;
            if (agentChatHandler != null) {
                int inT = agentChatHandler.getExecTotalPromptTokens();
                int outT = agentChatHandler.getExecTotalCompletionTokens();
                if (inT > 0 || outT > 0) agentTotalTokens = inT + outT;
            }
            if (agentTotalTokens <= 0) agentTotalTokens = statsTokens;
            
            endGeneration();
            // Agent 汇总（在清空计数之前生成）：工具数 / 思考轮次 / 工具名 / 缓存命中
            // 缓存命中独立判断：即使本轮无工具/多轮思考（简单追问），只要 API 返回缓存命中就显示
            boolean isAgentModeRun = agentGroupToolCount > 0 || thinkingRoundCount > 1;
            int cacheHitTokens = agentChatHandler != null ? agentChatHandler.getLastCacheHitTokens() : 0;
            if (isAgentModeRun || cacheHitTokens > 0) {
                StringBuilder sum = new StringBuilder();
                if (agentGroupToolCount > 0) sum.append(getString(R.string.h_8680a44b)).append(agentGroupToolCount).append(" 次");
                if (thinkingRoundCount > 1) {
                    if (sum.length() > 0) sum.append(" · ");
                    sum.append(getString(R.string.h_c053d0b8)).append(thinkingRoundCount).append(" 轮");
                }
                if (!agentToolNames.isEmpty()) {
                    if (sum.length() > 0) sum.append("\n");
                    sum.append(getString(R.string.h_59f941ba)).append(String.join("、", agentToolNames));
                }
                // 输入/输出 token 统计（引擎累计的 API 真实 usage：含多轮工具调用全部消耗）
                if (agentChatHandler != null) {
                    int inTokens = agentChatHandler.getExecTotalPromptTokens();
                    int outTokens = agentChatHandler.getExecTotalCompletionTokens();
                    if (inTokens > 0 || outTokens > 0) {
                        if (sum.length() > 0) sum.append("\n");
                        sum.append(getString(R.string.h_2e38da21)).append(inTokens).append(getString(R.string.h_4522b4c6)).append(outTokens).append(getString(R.string.h_ea69c145));
                    }
                }
                // 缓存命中统计（API 返回 usage 时才有；OpenAI prompt_tokens_details.cached_tokens / DeepSeek prompt_cache_hit_tokens）
                if (cacheHitTokens > 0) {
                    int inTokens = agentChatHandler != null ? agentChatHandler.getLastPromptTokens() : 0;
                    if (sum.length() > 0) sum.append("\n");
                    if (inTokens > 0) {
                        int hitRate = (int) Math.round(cacheHitTokens * 100.0 / inTokens);
                        sum.append(getString(R.string.h_9efa4828)).append(hitRate).append("%（")
                            .append(cacheHitTokens).append("/").append(inTokens).append(" tokens）");
                    } else {
                        sum.append(getString(R.string.h_50a70621)).append(cacheHitTokens).append(getString(R.string.h_32a82817));
                    }
                }
                // 上下文窗口用量：窗口（配置时 API 检测/配置表推断）+ 已用（最近请求输入）
                if (agentChatHandler != null) {
                    try {
                        int[] ctx = agentChatHandler.getContextWindowInfo();
                        if (ctx != null && ctx.length == 3 && ctx[0] > 0) {
                            if (sum.length() > 0) sum.append("\n");
                            sum.append(getString(R.string.h_0d4eba40)).append(formatCtxWindow(ctx[0])).append(" 用 ")
                                .append(String.format(java.util.Locale.ROOT, "%.0f%%",
                                        Math.min(100.0, ctx[1] * 100.0 / ctx[0])))
                                .append(getString(R.string.h_8e24373f)).append(formatCtxWindow(ctx[2])).append("）");
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (sum.length() > 0 && messageIndex >= 0 && messageIndex < chatHistory.size()) {
                    chatHistory.get(messageIndex).agentSummary = sum.toString();
                    AppLogger.i(TAG, "Agent summary written: " + sum.toString()
                        + " (cacheHit=" + cacheHitTokens + ")");
                    // 独立展示：插入一条 AGENT_SUMMARY 系统消息（Agent 执行汇总卡片）
                    // 放到 AI 消息之后，展示耗时/步骤/工具/Token 统计
                    try {
                        // 防御：generationStart 必须 >0（=0 时 totalTime 变成 1970 以来毫秒时间戳，
                        // 显示 "29787xxxm 23s" 天文耗时），回退 statsTime 或 0
                        long totalTime = generationStart > 0
                                ? System.currentTimeMillis() - generationStart
                                : (statsTime > 0 ? statsTime : 0);
                        // totalSteps=Agent 工具步骤数（与详文本"调用工具N次"口径一致），
                        // 思考轮数在详文本单独展示，避免卡片"3步骤"与"工具4次"矛盾
                        int agentSteps = agentGroupStepCount > 0 ? agentGroupStepCount : thinkingRoundCount;
                        ChatMessage.AgentSummaryInfo summaryInfo =
                                new ChatMessage.AgentSummaryInfo(totalTime, agentSteps,
                                        agentGroupToolCount, agentTotalTokens, true);
                        summaryInfo.summary = sum.toString();
                        ChatMessage summaryMsg = ChatMessage.createAgentSummaryMessage(summaryInfo);
                        summaryMsg.timestamp = System.currentTimeMillis();
                        int insertPos = Math.min(messageIndex + 1, chatHistory.size());
                        chatHistory.add(insertPos, summaryMsg);
                        if (chatAdapter != null) chatAdapter.notifyItemInserted(insertPos);
                    } catch (Throwable t) {
                        AppLogger.aiW(TAG, "插入Agent汇总消息失败: " + t.getMessage());
                    }
                }
            }
            agentToolLoopCount = 0;
            thinkingRoundEnded = false;
            thinkingRoundCount = 1;
            agentGroupStepCount = 0;
            agentGroupToolCount = 0;
            agentToolNames.clear();
            if (messageIndex >= 0 && messageIndex < chatHistory.size()) {
                ChatMessage finalMsg = chatHistory.get(messageIndex);
                finalMsg.content = finalContent;
                // 轮次模式：继承本轮 user 消息的标记（历史/会话记录用；上下文构建共享完整历史）
                for (int i = chatHistory.size() - 1; i >= 0; i--) {
                    ChatMessage u = chatHistory.get(i);
                    if (u != null && "user".equals(u.getRole())) {
                        finalMsg.turnMode = u.turnMode;
                        break;
                    }
                }
                // 兜底：若思考段未在流式阶段被分离（本地 Agent/chatJson 路径原生未下发
                // reasoning 事件时，思考被当普通 token 流入正文），完成时从正文剥离思考并
                // 回填 thinkingContent，避免"思考被绑进主消息气泡"。正文干净时为无操作。
                ThinkingTagConfig embeddedT = LlamaHelper.getThinkingTags();
                if (embeddedT.isAvailable() && finalMsg.content != null
                        && finalMsg.content.contains(embeddedT.getStartTag())) {
                    int ts = finalMsg.content.indexOf(embeddedT.getStartTag());
                    int tcClose = -1, tcCloseLen = 0;
                    for (String et : embeddedT.getEndTags()) {
                        int p = finalMsg.content.indexOf(et, ts + embeddedT.getStartTag().length());
                        if (p >= 0 && (tcClose < 0 || p < tcClose)) { tcClose = p; tcCloseLen = et.length(); }
                    }
                    if (tcClose >= 0) {
                        String embeddedThinking =
                                finalMsg.content.substring(ts + embeddedT.getStartTag().length(), tcClose).trim();
                        finalMsg.content = (finalMsg.content.substring(0, ts)
                                + finalMsg.content.substring(tcClose + tcCloseLen)).trim();
                        if (embeddedThinking.length() > 0) {
                            synchronized (streamingLock) {
                                if (currentThinkingContent == null) currentThinkingContent = new StringBuilder();
                                if (currentThinkingContent.length() == 0) {
                                    currentThinkingContent.append(embeddedThinking);
                                    finalMsg.thinkingContent = embeddedThinking;
                                }
                            }
                        }
                    }
                }
                finalMsg.status = ChatMessage.MessageStatus.COMPLETED;
                // 工具侧桥接：取走本轮工具执行产生的结构化 UI 组件，**合并**到 AI 消息
                // （不覆盖执行中已插入的工具卡片组件，保证执行中与完成后渲染一致）
                java.util.List<com.oilquiz.app.ai.chat.component.ComponentData> drained =
                        com.oilquiz.app.ai.chat.component.ComponentCollector.drain();
                if (drained != null && !drained.isEmpty()) {
                    if (finalMsg.components == null) {
                        finalMsg.components = new java.util.ArrayList<>();
                    }
                    // 组件 ID 派发（2026-09-14）：工具生成的 UI 组件补发 COMPONENT 子 id（T1-C1…），
                    // 适配器按 id 定位/更新/移除单个组件；已有 id 的组件（恢复场景）保留
                    for (com.oilquiz.app.ai.chat.component.ComponentData cd : drained) {
                        if (cd != null && (cd.id == null || cd.id.isEmpty())) {
                            cd.id = ChatIdDispatcher.getInstance()
                                    .applySubId(ChatIdDispatcher.IdType.COMPONENT);
                        }
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
                // Agent 在线模式：消息 token 数用引擎累计输出（多轮工具调用全部输出）
                if (agentChatHandler != null && agentChatHandler.getExecTotalCompletionTokens() > 0) {
                    finalTokenCount = agentChatHandler.getExecTotalCompletionTokens();
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
                // Agent 在线模式：引擎累计真实 API usage（含多轮工具调用全部输入输出），
                // 即使最终回答为空（纯工具操作）也要统计；本地模型走 native/估算
                int agentExecIn = agentChatHandler != null ? agentChatHandler.getExecTotalPromptTokens() : 0;
                int agentExecOut = agentChatHandler != null ? agentChatHandler.getExecTotalCompletionTokens() : 0;
                if (finalTokenCount > 0 || agentExecIn > 0) {
                    int inputTokens = 0;
                    int outputTokens = finalTokenCount;
                    if (agentExecIn > 0) {
                        inputTokens = agentExecIn;
                        if (agentExecOut > 0) outputTokens = agentExecOut;
                    } else if (messageIndex >= 1 && chatHistory.get(messageIndex - 1) != null) {
                        String promptText = chatHistory.get(messageIndex - 1).content;
                        if (promptText != null && !promptText.isEmpty()) {
                            // 仅本地模型走 native 分词；在线模型用长度估算，避免 native 崩溃
                            inputTokens = shouldUseOnlineModel()
                                    ? Math.max(1, promptText.length() / 4)
                                    : LlamaHelper.countTokens(promptText);
                        }
                    }
                    TokenStatsManager.getInstance().updateRequestStats(inputTokens, outputTokens);
                    AppLogger.i(TAG, "Token统计 - 输入: " + inputTokens + ", 输出: " + outputTokens);
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
            // 2026-09-23：AI 回复落定后刷统计+上下文 pill——在线引擎不走旧版 isGenerating
            // 路径，此前 pill 卡在发送时数值不更新
            refreshSessionStats();
            scrollToBottom();
        });
    }

    private void processTagBuffer(String tagContent) {
        if (tagContent == null) return;
        ThinkingTagConfig cfg = legacyThinkingTags;
        if (cfg.isAvailable()) {
            if (tagContent.contains(cfg.getStartTag())) {
                isInThinking = true;
            } else {
                for (String end : cfg.getEndTags()) {
                    if (tagContent.contains(end)) { isInThinking = false; break; }
                }
            }
        }
        // 模板未提供标签时不切换思考状态：旧逻辑 tagContent.contains("think") 过于宽松，
        // 会把普通含 "think" 字样的文本误判为思考起止，已弃用。
        if (tagBuffer != null) tagBuffer.setLength(0);
        isInTag = false;
    }

    // ===================== Agent Callbacks =====================

    private class AgentCallbackImpl implements AgentChatHandler.AgentChatCallback {
        /** 本轮 Agent 是否已结束（onComplete 置位）：防引擎补发 onError 重复走错误路径触发崩溃 */
        private final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);

        /** 每轮新 Agent 执行前调用：重置本轮完成标志。
         *  根因：AgentCallbackImpl 随 AgentChatHandler 复用（多轮对话共享同一实例），
         *  completed 若跨轮保持 true，第二轮 onComplete 的 CAS 会失败 → 回复不处理、UI 卡"处理中"。 */
        void resetForNewTurn() {
            completed.set(false);
        }

        @Override
        public void onToolCallStart(String toolCallId, String toolName, String args) {
            // 工具调用开始：插入式组件显示到 AI 消息内（执行中卡片）+ 状态栏更新
            // 多轮回答在同一气泡内按轮次组装：工具调用 = 轮次边界
            runOnUiThread(() -> {
                // 记录正文轮次边界 + 落库本轮思考（思考/正文/工具卡片按轮次一一对应）
                synchronized (streamingLock) {
                    int idx = resolveStreamingIndex();
                    if (idx >= 0 && idx < chatHistory.size()) {
                        ChatMessage msg = chatHistory.get(idx);
                        if (msg.contentRoundBounds == null) {
                            msg.contentRoundBounds = new java.util.ArrayList<>();
                        }
                        int bound = currentStreamingContent != null ? currentStreamingContent.length() : 0;
                        if (msg.contentRoundBounds.isEmpty()
                                || msg.contentRoundBounds.get(msg.contentRoundBounds.size() - 1) != bound) {
                            msg.contentRoundBounds.add(bound);
                        }
                        // 工具调用前思考落库（若本轮思考尚未结束）：思考轮次与正文轮次一一对应
                        if (currentThinkingContent != null && currentThinkingContent.length() > 0 && !thinkingRoundEnded) {
                            msg.addThinkingRound(currentThinkingContent.toString());
                            thinkingRoundEnded = true; // 落库即视为结束，防 finalize 重复
                        }
                        if (currentThinkingContent != null) currentThinkingContent = new StringBuilder();
                    }
                }
                appendAgentToolCall(toolCallId, toolName, "running", args, null);
                setAgentStepStatus("🔧 调用 " + toolName + "...");
                updateAgentStatusBar("🔧 调用 " + toolName + "...", true);
                addToolCallMessage(toolCallId, toolName, args);
                scrollToBottom();
            });
        }

        @Override
        public void onToolCallComplete(String toolCallId, String toolName, OnlineToolResult result) {
            // 工具调用完成：更新插入式卡片为成功/失败 + 状态栏恢复
            runOnUiThread(() -> {
                boolean success = result != null && result.success;
                String resultStr = result != null ? result.result : "无结果";
                // 聊天记录存完整工具结果（可追溯）；卡片显示时由 ToolCallCardView 自行 summarize 成单行摘要
                appendAgentToolCall(toolCallId, toolName, success ? "success" : "failed", null,
                        resultStr);
                setAgentStepStatus(success ? "✅ " + toolName + " 完成" : "⚠️ " + toolName + " 失败");
                updateAgentStatusBar("✅ " + toolName + " 完成", false);
                // Agent 模式：过程已插入 AI 消息组件，无独立工具卡片消息，跳过消息更新
                // （否则 findLastSpecialMessage 返回 -1 时回退到最后一条消息，结果会写到错误消息上）
                if (currentAgentGroupId == null) {
                    int pos = findToolCallMessageById(toolCallId);
                    updateToolCallResult(pos >= 0 ? pos : -1, success, resultStr);
                }
                scrollToBottom();
            });
        }

        @Override
        public void onToolPresent(String toolName, Map<String, Object> card) {
            // 工具声明化卡片意图（dsh presentCall/presentResult 对齐，2026-09-23）：
            // 挂到最新匹配的 TOOL_CALL 消息上，ChatAdapter 渲染优先读 presentCard
            if (card == null) return;
            runOnUiThread(() -> {
                synchronized (streamingLock) {
                    for (int i = chatHistory.size() - 1; i >= 0; i--) {
                        ChatMessage m = chatHistory.get(i);
                        if (m != null && m.type == ChatMessage.MessageType.TOOL_CALL
                                && m.toolCallInfo != null && toolName.equals(m.toolCallInfo.toolName)) {
                            m.toolCallInfo.presentCard = card;
                            if (chatAdapter != null) chatAdapter.notifyItemChanged(i);
                            return;
                        }
                    }
                }
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
            if (uiDetached) return; // UI 已分离：内容持续累积供落盘，跳过所有界面更新
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
            if (uiDetached) return; // UI 已分离：思考区仅界面展示，跳过
            // 思考 token：直接写入 AI 消息内嵌思考区（与本地模型一致，不创建独立消息）
            // 注意：本回调已通过 OnlineAgentEngine.runOnUiThread 在UI线程调用
            boolean onUi = Looper.myLooper() == Looper.getMainLooper();
            // 新一轮思考开始：状态栏显示思考中；若上一轮已有内容，先存入多轮列表（分块展示）
            if (thinkingRoundEnded || currentThinkingContent == null) {
                // 保存上一轮思考（轮次切换时，避免被覆盖丢失）
                if (currentThinkingContent != null && currentThinkingContent.length() > 0) {
                    final int curIdx = resolveStreamingIndex();
                    if (curIdx >= 0 && curIdx < chatHistory.size()) {
                        ChatMessage prevMsg = chatHistory.get(curIdx);
                        prevMsg.addThinkingRound(currentThinkingContent.toString());
                    }
                }
                thinkingRoundEnded = false;
                thinkingRoundCount++;
                setAgentStepStatus("🔍 思考中...（第" + thinkingRoundCount + "轮）");
                // 新一轮思考开始：默认折叠思考区（不打扰正文阅读），用户可点击展开实时查看
                final int newRoundIdx = resolveStreamingIndex();
                if (newRoundIdx >= 0 && newRoundIdx < chatHistory.size()) {
                    chatHistory.get(newRoundIdx).thinkingExpanded = false;
                }
                if (onUi) {
                    updateAgentStatusBar("🧠 思考中...", true);
                } else {
                    runOnUiThread(() -> updateAgentStatusBar("🧠 思考中...", true));
                }
                synchronized (streamingLock) {
                    currentThinkingContent = new StringBuilder();
                }
            }
            if (onUi) {
                appendAgentThinkingToken(token);
                updateThinkingStatusBarPreview();
            } else {
                runOnUiThread(() -> {
                    appendAgentThinkingToken(token);
                    updateThinkingStatusBarPreview();
                });
            }
        }

        /**
         * 顶部状态机实时显示思考内容预览（气泡思考区折叠时也能看到思考过程）。
         * 节流 200ms，避免高频 token 刷新卡顿；思考结束时恢复状态文本。
         */
        private long lastThinkingPreviewUiTime = 0;
        private void updateThinkingStatusBarPreview() {
            long now = System.currentTimeMillis();
            if (now - lastThinkingPreviewUiTime < 200) return;
            lastThinkingPreviewUiTime = now;
            String content;
            synchronized (streamingLock) {
                content = currentThinkingContent != null ? currentThinkingContent.toString() : "";
            }
            if (content.isEmpty()) return;
            String preview = content.length() > 80 ? "…" + content.substring(content.length() - 80) : content;
            if (serviceStatusText != null) serviceStatusText.setText("🧠 思考中：" + preview);
            if (serviceStatusProgress != null) {
                serviceStatusProgress.setVisibility(View.VISIBLE);
                serviceStatusProgress.setIndeterminate(true);
            }
        }

        @Override
        public void onThinkingEnd() {
            if (uiDetached) return; // UI 已分离：思考收尾仅界面展示，跳过
            isInThinking = false;
            // 思考结束：标记本轮结束，下一轮 onThinkingToken 时重置思考内容
            // （思考内容保留在 AI 消息内嵌思考区，随后自动折叠，用户可点击展开）
            thinkingRoundEnded = true;
            boolean onUi = Looper.myLooper() == Looper.getMainLooper();
            if (onUi) {
                finalizeAgentThinking();
            } else {
                runOnUiThread(() -> finalizeAgentThinking());
            }
        }

        @Override
        public void onComplete(String fullText) {
            // 幂等：只处理第一次完成事件（重复/迟到回调直接忽略）
            if (!completed.compareAndSet(false, true)) return;
            // 本地推理上下文独立化（2026-09-14）：Agent 轮次完成后回写该轮对话到本地推理历史，
            // 与普通对话共享同一真相源（普通↔Agent 来回切换不失忆）
            if (modelBridge != null) {
                String userMsg = lastAgentUserMessage;
                if (userMsg != null) {
                    modelBridge.appendExternalChatTurn(userMsg, fullText);
                    lastAgentUserMessage = null;
                }
            }
            completeGeneration(fullText);
            // 清理组ID（执行完成，不插入系统消息）
            runOnUiThread(() -> {
                if (uiDetached) return; // UI 已分离：结果已落盘，跳过界面更新
                // 状态栏恢复（执行完成）
                updateAgentStatusBar("✅ 执行完成", false);
                // 气泡内步骤状态（汇总文本已在 completeGeneration 中写入 agentSummary）
                setAgentStepStatus("✅ 执行完成");
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
            // 已完成后再报错：忽略（避免 completeGeneration 后又走 handleGenerationError 删错消息）
            if (completed.get()) return;
            // 本轮失败：清除待回写标记，防止下一轮 onComplete 误回写旧用户消息
            lastAgentUserMessage = null;
            runOnUiThread(() -> {
                if (uiDetached) return; // UI 已分离：跳过界面错误处理（结果留待下次进入从文件恢复）
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
                final int idx = resolveStreamingIndex();
                if (idx >= 0) {
                    ChatMessage msg = chatHistory.get(idx);
                    if (msg.inferenceProgress == null) {
                        msg.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.GENERATING);
                    }
                    msg.inferenceProgress.processedTokens = tokenCount;
                    msg.inferenceProgress.tokensPerSecond = tokensPerSecond;

                    // 使用与普通模式相同的payload更新UI
                    if (chatAdapter != null) {
                        chatAdapter.notifyItemChanged(idx, ChatAdapter.PAYLOAD_STATUS_UPDATE);
                    }
                }
            });
        }
    }

    /** 按消息 id 在 chatHistory 中查找位置（-1 表示未找到）。所有流式更新以此定位，避免索引漂移。 */
    private int findMessageIndexById(String messageId) {
        if (messageId == null || chatHistory == null) return -1;
        for (int i = 0; i < chatHistory.size(); i++) {
            if (messageId.equals(chatHistory.get(i).id)) return i;
        }
        return -1;
    }

    /** 按 toolCallId 查找独立工具卡片消息位置（-1 表示未找到） */
    private int findToolCallMessageById(String toolCallId) {
        if (toolCallId == null || chatHistory == null) return -1;
        for (int i = 0; i < chatHistory.size(); i++) {
            ChatMessage msg = chatHistory.get(i);
            if (msg != null && msg.toolCallInfo != null && toolCallId.equals(msg.toolCallInfo.toolCallId)) {
                return i;
            }
        }
        return -1;
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
        // 复位开始时间：completeGeneration 里已把 generationStartTime 拷成局部变量使用，
        // 此处复位避免异常路径下跨轮串扰（下一轮 beginGeneration 会重新置值）
        generationStartTime = 0;

        if (streamingUpdateManager != null) {
            streamingUpdateManager.flush();
            streamingUpdateManager = null;
        }

        // 清理待执行的思考节流刷新（生成结束，避免残留定时任务刷新过期索引）
        cancelThinkingRefresh();

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

    /**
     * 解析当前流式 AI 消息的位置。
     * 优先按 currentStreamingMessageId 在 chatHistory 中查找（抗插入漂移）；
     * 找不到时回退 currentStreamingMessageIndex（并校验范围）；均无效返回 -1。
     */
    private int resolveStreamingIndex() {
        // 生成已结束(id 为空)时任何陈旧索引都不可用：返回 -1，避免错误路径
        // 用过期 currentStreamingMessageIndex 删除错误消息（RecyclerView Inconsistency 崩溃根因之一）
        if (currentStreamingMessageId == null) return -1;
        // 纯 id 定位（2026-09-14）：实时按 messageId 查找，杜绝"id 新增/删除后
        // 缓存索引漂移导致 UI 位置错乱"；找不到即 -1（调用点均已有守卫）
        return findMessageIndexById(currentStreamingMessageId);
    }

    private void safeUpdateMessage() {
        try {
            final int idx = resolveStreamingIndex();
            if (idx < 0) return;
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
            ChatMessage msg = chatHistory.get(idx);
            // 节流：内容与思考均无变化时跳过刷新，避免重复渲染相同内容
            boolean thinkingSame = (thinkingSnapshot == null)
                    || (msg.thinkingContent != null && thinkingSnapshot.equals(msg.thinkingContent));
            if (contentSnapshot.equals(msg.content) && thinkingSame) {
                return;
            }
            msg.content = contentSnapshot;
            msg.status = ChatMessage.MessageStatus.GENERATING;
            if (thinkingSnapshot != null) msg.thinkingContent = thinkingSnapshot;
            if (chatAdapter != null) chatAdapter.updateAIMessageContent(idx, contentSnapshot);
        } catch (IndexOutOfBoundsException e) { currentStreamingMessageIndex = -1; }
    }

    private void safeUpdateMessageFromStreamingManager(String accumulatedContent, int tokensSinceLastUpdate) {
        try {
            final int idx = resolveStreamingIndex();
            if (idx < 0) return;
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
            ChatMessage msg = chatHistory.get(idx);
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
                    chatAdapter.updateAIMessageContent(idx, contentSnapshot);
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

    // ===================== 历史分段加载 / 内存窗口化（2026-09-25） =====================

    /** 当前历史来源前端未加载条数（会话或单文件） */
    private int historyFrontSkippedCount() {
        return historySourceSessionId != null ? sessionFrontSkipped : aiHistoryFrontSkipped;
    }

    /** 保存当前历史窗口（保留文件未加载头部；新会话时按需合并单文件头部） */
    private void saveCurrentHistory(boolean mergeSingleFileHead) {
        if (chatHistoryManager == null || chatHistory == null || chatHistory.isEmpty()) return;
        final List<ChatMessage> copy = new ArrayList<>(chatHistory);
        if (currentSessionId != null && !currentSessionId.isEmpty()) {
            chatHistoryManager.saveCurrentChatAsSession(copy, currentSessionId, sessionFrontSkipped, false);
        } else {
            chatHistoryManager.saveCurrentChatAsSession(copy, null, aiHistoryFrontSkipped, mergeSingleFileHead);
        }
    }

    /** 点击"加载更早消息"：分段加载上一页并前插，保持视口 */
    private void loadMoreHistory() {
        if (historyLoadingMore || historyFrontSkippedCount() <= 0) return;
        if (isGenerating) {
            showToast(getString(R.string.h_9447f530));
            return;
        }
        historyLoadingMore = true;
        updateLoadMoreButton();
        final int skipped = historyFrontSkippedCount();
        final int skip = Math.max(0, skipped - ChatHistoryManager.HISTORY_PAGE_SIZE);
        final int limit = skipped - skip;
        final String srcSession = historySourceSessionId;
        new Thread(() -> {
            List<ChatMessage> older;
            try {
                older = srcSession != null
                        ? chatHistoryManager.loadConversationSessionMessages(srcSession, skip, limit)
                        : chatHistoryManager.loadAIHistoryMessages(skip, limit);
            } catch (Exception e) {
                older = null;
            }
            final List<ChatMessage> olderFinal = older;
            runOnUiThread(() -> {
                historyLoadingMore = false;
                if (olderFinal != null && !olderFinal.isEmpty()) {
                    final int added = olderFinal.size();
                    chatHistory.addAll(0, olderFinal);
                    ensureRecoveredIds(chatHistory);
                    if (chatAdapter != null) chatAdapter.notifyItemRangeInserted(0, added);
                    if (srcSession != null) sessionFrontSkipped = skip;
                    else aiHistoryFrontSkipped = skip;
                    // 保持视口：滚动到原首条（现位于 added 处）
                    if (messageList != null && messageList.getLayoutManager() instanceof LinearLayoutManager) {
                        ((LinearLayoutManager) messageList.getLayoutManager()).scrollToPositionWithOffset(added, 0);
                    }
                } else {
                    if (srcSession != null) sessionFrontSkipped = 0;
                    else aiHistoryFrontSkipped = 0;
                }
                updateLoadMoreButton();
            });
        }).start();
    }

    /** 顶部"加载更早消息"按钮显隐：有未加载历史且滚到顶部时显示 */
    private void updateLoadMoreButton() {
        if (btnLoadMoreHistory == null) return;
        if (historyLoadingMore) {
            btnLoadMoreHistory.setText("加载中…");
            btnLoadMoreHistory.setVisibility(View.VISIBLE);
            return;
        }
        boolean atTop = false;
        if (messageList != null && messageList.getLayoutManager() instanceof LinearLayoutManager) {
            atTop = ((LinearLayoutManager) messageList.getLayoutManager()).findFirstVisibleItemPosition() <= 0;
        }
        int skipped = historyFrontSkippedCount();
        if (skipped > 0 && atTop) {
            btnLoadMoreHistory.setText("↑ 加载更早的 " + skipped + " 条");
            btnLoadMoreHistory.setVisibility(View.VISIBLE);
        } else {
            btnLoadMoreHistory.setVisibility(View.GONE);
        }
    }

    /**
     * 正在对话时把较早消息归档到文件（内存窗口化），保证长对话内存健康；
     * 归档后划到顶部点"加载更早消息"可分段恢复。仅在空闲、用户在底部时执行。
     */
    private void maybeArchiveAfterSave() {
        if (uiDetached || chatHistory == null || chatAdapter == null) return;
        if (isGenerating || historyLoadingMore) return;
        if (chatHistory.size() <= HISTORY_ARCHIVE_KEEP) return;
        // 只在用户停留在底部时归档，避免回读旧消息时列表被抽走
        if (!isUserAtBottom()) return;
        int drop = Math.min(HISTORY_ARCHIVE_CHUNK, chatHistory.size() - HISTORY_ARCHIVE_KEEP);
        if (drop <= 0) return;
        chatHistory.subList(0, drop).clear();
        if (historySourceSessionId != null) sessionFrontSkipped += drop;
        else aiHistoryFrontSkipped += drop;
        chatAdapter.notifyItemRangeRemoved(0, drop);
        updateLoadMoreButton();
        AppLogger.ai(TAG, "历史已归档: 移出内存 " + drop + " 条，内存窗口=" + chatHistory.size()
                + "，滚动顶部可加载更早");
    }

    private void saveHistoryAsync() {
        if (chatHistoryManager != null && chatHistory != null) {
            final List<ChatMessage> copy = new ArrayList<>(chatHistory);
            final int aiSkipped = aiHistoryFrontSkipped;
            final int sessSkipped = sessionFrontSkipped;
            new Thread(() -> {
                chatHistoryManager.saveAIHistoryWithHead(copy, aiSkipped);
                // 同步保存为会话（确保历史不丢失；保留未加载头部）
                if (copy.size() >= 2) {
                    synchronized (HISTORY_IO_LOCK) {
                        ConversationSession session;
                        if (currentSessionId != null && !currentSessionId.isEmpty()) {
                            session = chatHistoryManager.saveCurrentChatAsSession(copy, currentSessionId, sessSkipped, false);
                        } else {
                            session = chatHistoryManager.saveCurrentChatAsSession(copy, null, aiSkipped, true);
                        }
                        // 保存后更新 currentSessionId，下次更新同一文件而非重复创建
                        if (session != null && session.id != null) {
                            currentSessionId = session.id;
                            historySourceSessionId = session.id;
                            sessionFrontSkipped = 0;
                        }
                    }
                }
                // 归档检查：消息已在此落盘，安全把较早消息移出内存（窗口化）
                runOnUiThread(AIChatActivity.this::maybeArchiveAfterSave);
            }).start();
        }
    }

    // ===================== 恢复补发 id（2026-09-14） =====================

    /** 历史恢复后补齐 id 体系：旧数据组件的 COMPONENT id / 思考轮的 THINKING id 可能缺失，
     *  按消息自身 turnId 为前缀补发（组件/思考轮 id 与消息对的主 id 保持一致，适配器按前缀聚合不脱节）；
     *  已存在 id 的组件/轮次不动（幂等）。 */
    private void ensureRecoveredIds(java.util.List<ChatMessage> messages) {
        if (messages == null) return;
        for (ChatMessage m : messages) {
            if (m == null) continue;
            // 无回合锚点的旧消息（turnId 为空）不补发：补发会消耗派发器主 id（推进 T 序号），
            // 且组件/思考轮 id 前缀与消息回合脱节。待消息自身持久化 turnId 后再补。
            if (m.turnId == null || m.turnId.isEmpty()) continue;
            // 组件 id 补发（挂到消息 turnId 下）
            if (m.components != null) {
                for (com.oilquiz.app.ai.chat.component.ComponentData cd : m.components) {
                    if (cd != null && (cd.id == null || cd.id.isEmpty())) {
                        cd.id = ChatIdDispatcher.getInstance()
                                .applySubId(ChatIdDispatcher.IdType.COMPONENT, m.turnId);
                    }
                }
            }
            // 思考轮 id 补发（与 thinkingRounds 一一对应）
            if (m.thinkingRounds != null && !m.thinkingRounds.isEmpty()) {
                if (m.thinkingRoundIds == null) {
                    m.thinkingRoundIds = new java.util.ArrayList<>();
                }
                while (m.thinkingRoundIds.size() < m.thinkingRounds.size()) {
                    m.thinkingRoundIds.add(ChatIdDispatcher.getInstance()
                            .applySubId(ChatIdDispatcher.IdType.THINKING, m.turnId));
                }
            }
        }
    }

    // ===================== 热加载（退出不中断生成后重进恢复完整内容） =====================

    /**
     * 启动热加载轮询：恢复历史时发现 GENERATING 残留（说明退出时后台仍在生成），
     * 轮询会话文件，把后台生成完成后的完整内容刷进当前界面。
     * 轮询 1.5s 一次；文件消息完成/出错且内容稳定（连续 3 次无变化）或超过 60 次后停止。
     */
    private void startHistoryHotReload() {
        if (uiDetached || chatHistoryManager == null) return;
        AppLogger.ai(TAG, "热加载启动：后台生成仍在进行，轮询文件更新内容");
        historyPollUnchangedCount = 0;
        historyPollHandler.removeCallbacks(historyPollRunnable);
        historyPollHandler.postDelayed(historyPollRunnable, 1500);
    }

    private void pollHistoryForHotReload() {
        if (uiDetached || chatHistoryManager == null || chatHistory == null) return;
        final String sid = currentSessionId;
        new Thread(() -> {
            List<ChatMessage> fresh = null;
            try {
                if (sid != null && !sid.isEmpty()) {
                    ConversationSession s = chatHistoryManager.loadConversationSession(sid);
                    if (s != null && s.messages != null) fresh = s.messages;
                } else {
                    fresh = chatHistoryManager.loadAIChatHistory();
                }
            } catch (Exception ignored) {
            }
            final List<ChatMessage> f = fresh;
            runOnUiThread(() -> {
                if (uiDetached || f == null || f.isEmpty()) {
                    historyPollHandler.removeCallbacks(historyPollRunnable);
                    return;
                }
                // 定位当前界面最后一条 AI 消息（退出时生成中的那条）
                ChatMessage uiLast = null;
                int uiIdx = -1;
                for (int i = chatHistory.size() - 1; i >= 0; i--) {
                    ChatMessage m = chatHistory.get(i);
                    if (m != null && m.type == ChatMessage.MessageType.AI) {
                        uiLast = m;
                        uiIdx = i;
                        break;
                    }
                }
                if (uiLast == null) {
                    historyPollHandler.removeCallbacks(historyPollRunnable);
                    return;
                }
                // 文件里最后一条 AI 消息（后台生成的结果）
                ChatMessage fileLast = null;
                for (int i = f.size() - 1; i >= 0; i--) {
                    ChatMessage m = f.get(i);
                    if (m != null && m.type == ChatMessage.MessageType.AI) {
                        fileLast = m;
                        break;
                    }
                }
                boolean updated = false;
                if (fileLast != null && fileLast.content != null) {
                    String cur = uiLast.content != null ? uiLast.content : "";
                    // 内容变长 → 更新显示（后台生成在推进）
                    if (fileLast.content.length() > cur.length() + 4) {
                        uiLast.content = fileLast.content;
                        updated = true;
                    }
                    // 状态变为已完成/出错 → 同步状态
                    if ((fileLast.status == ChatMessage.MessageStatus.COMPLETED
                            || fileLast.status == ChatMessage.MessageStatus.ERROR
                            || fileLast.status == ChatMessage.MessageStatus.FAILED)
                            && uiLast.status != fileLast.status) {
                        uiLast.status = fileLast.status;
                        updated = true;
                    }
                }
                if (updated && chatAdapter != null) {
                    chatAdapter.notifyItemChanged(uiIdx);
                }
                // 停止条件：文件已终态（完成/出错）或内容连续 3 次无变化；上限 60 次防死循环
                boolean fileDone = fileLast != null
                        && (fileLast.status == ChatMessage.MessageStatus.COMPLETED
                            || fileLast.status == ChatMessage.MessageStatus.ERROR
                            || fileLast.status == ChatMessage.MessageStatus.FAILED);
                if (fileDone || !updated) {
                    historyPollUnchangedCount++;
                } else {
                    historyPollUnchangedCount = 0;
                }
                if (historyPollUnchangedCount >= 3 || historyPollUnchangedCount >= 60) {
                    historyPollHandler.removeCallbacks(historyPollRunnable);
                } else {
                    historyPollHandler.postDelayed(historyPollRunnable, 1500);
                }
            });
        }).start();
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

    /**
     * 注册在线模型变更监听，模型切换时自动刷新模式按钮
     */
    private void registerModelChangeListener() {
        if (onlineModelManager == null) return;
        modelChangeListener = new OnlineModelManager.ModelChangeListener() {
            @Override
            public void onModelListChanged() {
                // 模型列表变化时刷新模式按钮
                runOnUiThread(() -> updateModeButtonText());
            }
            @Override
            public void onActiveModelChanged(String activeModelId) {
                // 激活模型变化时立即刷新模式按钮
                runOnUiThread(() -> {
                    // 检测在线模型切换（如 deepseek → qwen）：把引擎历史切换到新模型的专属文件，
                    // 旧模型的 system 提示词/工具调用记录不再污染新模型（UI 会话消息保留，用户可见历史不动）
                    if (lastOnlineModelId != null && !lastOnlineModelId.equals(activeModelId)) {
                        AppLogger.ai(TAG, "Online model switched: " + lastOnlineModelId + " -> " + activeModelId
                                + ", switching agent engine history by model");
                        if (agentChatHandler != null) {
                            agentChatHandler.setModelId(activeModelId);
                        }
                        addSystemMessage("已切换模型，对话上下文已按模型切换");
                    }
                    lastOnlineModelId = activeModelId;
                    updateModeButtonText();
                    updateApiBalanceDisplay();
                    updateWebSearchChip();
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
        // 2026-10-05：原先这里在 NPU 引擎下直接 return（当时认为 NPU 做不了工具调用），
        // 结果 Agent 永远不初始化 → 每次都降级成"Agent 引擎未就绪，已降级为普通对话"。
        // 现在 NPU 已能驱动 Agent 循环（NpuEngineRouter.chatJson + 事件协议对齐）→ 不再拦截。
        if (!useOnlineModel && !isNpuEngineOn() && (modelBridge == null || !modelBridge.isModelInitialized())) {
            // M13：补充原因日志——在线路径无需本地模型；本地路径未就绪时说明是"等待初始化"而非错误，
            // 模型就绪后 modelChangeListener 会再次触发本方法完成初始化
            AppLogger.aiW(TAG, "Agent模式需要AI服务已初始化，当前AI服务未就绪"
                    + "（在线=" + useOnlineModel + ", modelBridge=" + (modelBridge != null)
                    + ", initialized=" + (modelBridge != null && modelBridge.isModelInitialized())
                    + "），等待模型就绪后自动初始化 Agent 引擎");
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
        boolean typeChanged = lastUseOnlineModel != null && lastUseOnlineModel != useOnlineModel;
        if (useOnlineModel) {
            agentCallback = new AgentCallbackImpl();
            agentChatHandler = new AgentChatHandler(this, aiService, inferenceRouter, agentService, agentCallback, true);
        } else {
            agentCallback = new AgentCallbackImpl();
            agentChatHandler = new AgentChatHandler(this, aiService, agentService, agentCallback);
        }
        // 同步引擎会话：跟随当前 UI 会话（启动恢复/切换会话后保持一致）。
        // 在线路径先设置模型 ID（历史文件按「会话 × 模型」隔离），再设置会话 ID 触发目标文件恢复
        if (useOnlineModel && agentChatHandler != null) {
            String activeModelId = onlineModelManager != null && onlineModelManager.getActiveModel() != null
                    ? onlineModelManager.getActiveModel().id : null;
            agentChatHandler.setModelId(activeModelId);
            if (currentSessionId != null) {
                agentChatHandler.setSessionId(currentSessionId);
            }
        }
        lastUseOnlineModel = useOnlineModel;
        AppLogger.ai(TAG, "AgentChatHandler 已初始化，使用模型类型: " + (useOnlineModel ? "在线" : "本地"));
        // 在线/本地模式切换提示（首次初始化不提示，避免冷启动刷屏）
        if (typeChanged) {
            addSystemMessage(useOnlineModel
                    ? "已切换到在线模型，对话上下文已切换"
                    : "已切换到本地模型，对话上下文已切换");
        }
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

    /**
     * 压缩对话确认框：长按清空按钮触发。
     * 调用在线模型把早期对话生成摘要，引擎保留摘要+最近 8 条消息（省 tokens），
     * UI 消息列表保留完整显示（不影响阅读），后续提问基于摘要+近期上下文。
     */
    private void showCompressConversationDialog() {
        if (!shouldUseOnlineModel()) {
            showToast(getString(R.string.h_7100aac1));
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.h_246bc2af))
            .setMessage(getString(R.string.h_c0728fc0))
            .setPositiveButton(getString(R.string.h_6612548a), (dialog, which) -> {
                // 2026-09-23：生成中直接提示，避免等 60s 超时后才"压缩失败"
                if (agentChatHandler != null && agentChatHandler.isGenerating()) {
                    showToast("正在生成中，请等本轮回复完成后再压缩");
                    return;
                }
                showToast(getString(R.string.h_f29a225b));
                if (agentChatHandler != null) {
                    agentChatHandler.compressHistory(8, summary -> {
                        runOnUiThread(() -> {
                            if (summary != null && !summary.isEmpty()) {
                                // 消息列表顶部插入摘要提示，说明早期内容已压缩
                                addSystemMessage("✂️ 对话已压缩：早期内容已生成摘要，后续对话更省 tokens。\n\n📋 摘要：\n" + summary);
                                scrollToBottom();
                                showToast(getString(R.string.h_36430ddc));
                            } else {
                                // 对话太短（<4条用户消息）或模型不可用
                                showToast("无法压缩：对话太短（至少 4 条用户消息）或在线模型不可用");
                            }
                        });
                    });
                }
            })
            .setNegativeButton(getString(R.string.h_625fb26b), null)
            .show();
    }

    private void clearChat() {
        // NPU-SESSION-RESET: 清空对话时同步重置 NPU 会话（GenieX LlmWrapper.reset()）——
        // 官方 demo 在新会话时调用（MainActivity.kt:1136）；不调用会让 KV 里残留旧上下文。
        try {
            com.oilquiz.app.ai.engine.NpuLlmChat.resetIncrementalSession();
        } catch (Throwable t) {
            AppLogger.aiW(TAG, "NPU 会话重置异常: " + t);
        }
        try {
            if (isGenerating) {
                if (agentChatHandler != null && agentChatHandler.isGenerating()) agentChatHandler.cancel();
                if (modelBridge != null) modelBridge.execute(ChatCommand.stopGeneration(), null);
            }
            // 同步清理所有数据源
            // 用户主动清空：作废异步历史加载结果，防止旧会话回灌顶掉新对话
            historyLoadStale = true;
            fileHistoryLoaded = true;
            chatHistory.clear();
            refreshSessionStats();
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
            // 本地推理上下文独立化：清空本地推理历史（内存 + 当前会话文件）
            if (modelBridge != null) modelBridge.clearLocalHistory();
            // 清空在线 Agent 引擎的全部历史（所有会话，与 UI 全清一致）
            if (agentChatHandler != null) agentChatHandler.clearAllHistory();
            // 会话 ID 重置：下次发送时创建新会话
            currentSessionId = null;
            clearStreamingState();
            endGeneration();
            // 组件暂存缓存同步清除（2026-09-14）：防止上一轮/上一会话残留组件
            // 在下一轮 drain 时混入，导致组件 id 冲突/旧组件串新回合
            com.oilquiz.app.ai.chat.component.ComponentCollector.clear();
            // ID 派发器重置：对话历史清空，主/子 id 从 T1 重新发放
            ChatIdDispatcher.getInstance().reset();
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
            // 自动保存当前会话到历史（拷贝 + 更新当前会话，避免重复创建与清空竞态）
            if (chatHistoryManager != null && !chatHistory.isEmpty()) {
                final List<ChatMessage> copy = new ArrayList<>(chatHistory);
                final String existingId = currentSessionId;
                new Thread(() -> {
                    chatHistoryManager.saveCurrentChatAsSession(copy, existingId);
                    runOnUiThread(this::refreshHistoryDrawer);
                }).start();
            }
            // 清空当前对话上下文和页面消息
            // 用户主动新建对话：作废异步历史加载结果，防止旧会话回灌顶掉新对话
            historyLoadStale = true;
            fileHistoryLoaded = true;
            currentSessionId = null; // 重置会话 ID，下次保存时创建新会话
            chatHistory.clear();
            if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
            if (chatHistoryManager != null) new Thread(() -> chatHistoryManager.clearAIChatHistory()).start();
            if (modelBridge != null) modelBridge.execute(ChatCommand.clearContext(), null);
            // 引擎：先保存当前会话历史（切回原会话不失忆），再清空内存与默认文件，保证新对话干净
            if (agentChatHandler != null) {
                agentChatHandler.setSessionId(null);
                agentChatHandler.clearHistory();
            }
            // 本地推理上下文独立化：切到新会话（null=新建，历史从空开始）
            if (modelBridge != null) {
                modelBridge.setLocalSessionId(null);
            }
            // 组件暂存缓存同步清除（2026-09-14）：新对话隔离上一轮残留组件，防 id 冲突
            com.oilquiz.app.ai.chat.component.ComponentCollector.clear();
            // 注意：新建对话【不】重置 ID 派发器——旧会话仍保留在历史抽屉，
            // 若归零重发会导致切回旧会话后 turnId 与旧消息重复；
            // 派发器仅在 clearChat（清空全部历史）时 reset
            currentTurnId = null;
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
            // 先保存当前会话（更新当前会话而非重复创建；保留未加载头部）
            if (chatHistoryManager != null && !chatHistory.isEmpty()) {
                saveCurrentHistory(true);
            }
            // 异步分段加载目标会话：只取最新一页（大文件不再全量解析）
            new Thread(() -> {
                int tTotal = chatHistoryManager.countConversationMessages(session.id);
                final int[] tSkipped = {Math.max(0, tTotal - ChatHistoryManager.HISTORY_PAGE_SIZE)};
                List<ChatMessage> pageMsgs = chatHistoryManager.loadConversationSessionMessages(
                        session.id, tSkipped[0], ChatHistoryManager.HISTORY_PAGE_SIZE);
                if (pageMsgs != null && !pageMsgs.isEmpty()) {
                    runOnUiThread(() -> {
                        // 停止当前生成
                        if (isGenerating) {
                            if (agentChatHandler != null && agentChatHandler.isGenerating()) agentChatHandler.cancel();
                            if (modelBridge != null) modelBridge.execute(ChatCommand.stopGeneration(), null);
                            clearStreamingState();
                            endGeneration();
                        }
                        // 替换当前聊天历史
                        currentSessionId = session.id; // 跟踪当前加载的会话 ID
                        historySourceSessionId = session.id;
                        sessionFrontSkipped = tSkipped[0];
                        aiHistoryFrontSkipped = 0;
                        chatHistory.clear();
                        chatHistory.addAll(pageMsgs);
                        ensureRecoveredIds(chatHistory);
                        if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                        updateLoadMoreButton();
                        // 保存到单文件历史（兼容现有逻辑）
                        chatHistoryManager.saveAIHistoryWithHead(new ArrayList<>(chatHistory), 0);
                        // 引擎按会话隔离历史：保存当前 → 恢复目标会话的上下文（不再清空失忆）
                        if (agentChatHandler != null) {
                            agentChatHandler.setSessionId(session.id);
                        }
                        // 本地推理上下文独立化（2026-09-14）：按会话加载目标历史，不再清空从头开始
                        if (modelBridge != null) {
                            modelBridge.setLocalSessionId(session.id);
                            // 平滑迁移：旧版本聊过的会话（无独立推理历史文件）首次切回时，
                            // 从 UI 会话消息一次性重建推理历史；之后完全独立于 UI
                            if (modelBridge.getLocalHistoryEntries().isEmpty()
                                    && pageMsgs != null && !pageMsgs.isEmpty()) {
                                modelBridge.rebuildChatJsonHistoryFromExternal(
                                        buildMigrationEntries(pageMsgs));
                                AILogger.i(TAG, "Local context migrated from UI session: "
                                        + pageMsgs.size() + " msgs");
                            }
                        }
                        updateEmptyState();
                        scrollToBottom(true);
                        showToast(getString(R.string.h_ab98c004) + session.title);
                    });
                } else {
                    runOnUiThread(() -> showToast(getString(R.string.h_c59cad21)));
                }
            }).start();
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error switching session: " + e.getMessage());
        }
    }

    /**
     * 删除指定的历史会话。
     * 若删除的是当前会话，同步清空引擎历史与页面上下文。
     */
    private void deleteSession(ConversationSession session) {
        if (session == null || session.id == null) return;
        boolean isCurrent = session.id.equals(currentSessionId);
        new Thread(() -> {
            chatHistoryManager.deleteConversationSession(session.id);
            // 本地推理上下文独立化：同步删除该会话的本地推理历史文件（含全部模型隔离文件）
            if (modelBridge != null) modelBridge.deleteLocalHistory(session.id);
            runOnUiThread(() -> {
                if (isCurrent) {
                    // 当前会话被删除：清空页面 + 引擎历史（含其历史文件）
                    currentSessionId = null;
                    chatHistory.clear();
                    if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                    if (agentChatHandler != null) agentChatHandler.clearHistory();
                    if (modelBridge != null) modelBridge.execute(ChatCommand.clearContext(), null);
                    updateEmptyState();
                }
                refreshHistoryDrawer();
                showToast(getString(R.string.h_5cc23262));
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
            runOnUiThread(() -> {
                historyController.setCurrentSessionId(currentSessionId);
                historyController.refresh(sessions);
            });
        }).start();
    }

    /**
     * 重新生成：找到指定 AI 消息前面的最后一条用户消息，删除该 AI 消息（及其后的所有非用户消息），
     * 然后重新发送该用户消息。
     */
    private void regenerateMessage(String aiMessageId) {
        if (isGenerating) { showToast(getString(R.string.h_05582e8e)); return; }
        try {
            // 1. 找到目标 AI 消息的索引
            int aiIndex = -1;
            for (int i = 0; i < chatHistory.size(); i++) {
                ChatMessage m = chatHistory.get(i);
                if (m != null && aiMessageId.equals(m.id)) { aiIndex = i; break; }
            }
            if (aiIndex < 0) { showToast(getString(R.string.h_631678c9)); return; }

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
            if (userContent == null) { showToast(getString(R.string.h_2130ef69)); return; }

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
            showToast(getString(R.string.h_0b7ce83c));
        }
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
     * 点击切换深度思考开关：开 ↔ 关
     */
    private void toggleMode() {
        if (isGenerating) {
            showToast(getString(R.string.h_15261c3c));
            return;
        }
        ChatModeManager manager = ChatModeManager.getInstance(AIChatActivity.this);
        boolean next = !manager.isDeepThinkingEnabled();
        ChatModeManager.ChatMode oldMode = manager.getCurrentMode();
        if (manager.setDeepThinkingEnabled(next)) {
            injectModeSwitchInstruction(oldMode, manager.getCurrentMode());
        }
        updateModeButtonText();
        // 同步快捷区深度思考 chip 状态
        com.google.android.material.chip.Chip chip = findViewById(R.id.chip_deep_think);
        if (chip != null) {
            chip.setChecked(next);
            updateDeepThinkChip(chip);
        }
        showToast(next ? getString(R.string.h_72bc1b1d) : getString(R.string.h_814eed44));
    }

    /**
     * 更新一键初始化按钮显隐（本地与在线均未配置时显示）
     */
    private void updateAiInitButtonVisibility() {
        if (btnAiInit == null) return;
        boolean show = AIServiceInitializer.needsInitialization(this);
        btnAiInit.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            btnAiInit.setEnabled(true);
            btnAiInit.setText(getString(R.string.h_f696b417));
        }
    }

    /**
     * 更新模式按钮显示文本（深度思考开关状态）
     */
    private void updateModeButtonText() {
        if (btnModeSelect != null) {
            ChatModeManager manager = ChatModeManager.getInstance(AIChatActivity.this);
            String btnText = (manager.isDeepThinkingEnabled() ? "🧠" : "💬")
                    + (manager.isDeepThinkingEnabled() ? "深度思考" : "普通")
                    + (manager.isDeepThinkingEnabled() ? " ON" : "");
            btnModeSelect.setText(btnText);
        }
    }

    /** 本地Agent开关 chip 高亮状态：开启=主色底白字, 关闭=灰色底灰字 */
    private void updateAgentChip(com.google.android.material.chip.Chip chip) {
        if (chip == null) return;
        boolean on = aiConfig != null && aiConfig.isLocalAgentEnabled();
        if (on) {
            chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                    ThemeColors.attr(this, R.attr.colorPrimary)));
            chip.setTextColor(getColor(R.color.white));
            chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(
                    ThemeColors.attr(this, R.attr.colorPrimary)));
            chip.setText(getString(R.string.h_43435c9f));
            chip.setChipIconTint(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.white)));
        } else {
            chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.chip_gray_bg)));
            chip.setTextColor(getColor(R.color.chip_gray_text));
            chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.chip_gray_stroke)));
            chip.setText(getString(R.string.h_e224c0b3));
            chip.setChipIconTint(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.chip_gray_text)));
        }
    }

    /** 切换当前在线模型的联网搜索能力（写配置持久化，即时生效）。仅服务商声明 webSearch 参数时可用 */
    private void toggleWebSearch(boolean enabled) {
        try {
            OnlineModelManager.OnlineModelConfig cfg = onlineModelManager != null
                    ? onlineModelManager.getActiveModel() : null;
            if (cfg == null) {
                showToast("未配置在线模型，无法开启联网搜索");
                if (chipWebSearch != null) chipWebSearch.setChecked(false);
                return;
            }
            // 联网搜索可用性：自有 network_search 工具（内置秘塔 Key 兜底）优先，官方 webSearch 支持兜底——
            // 不被官方服务商能力捆绑（防止"服务商不支持 → 置灰"但自有工具明明可用）
            boolean supported = com.oilquiz.app.ai.tool.NetworkSearchTool.isAvailable()
                    || com.oilquiz.app.ai.model.ProviderConfigManager.get()
                            .supportsModelCapability(cfg.apiUrl, cfg.modelName, "webSearch");
            if (enabled && !supported) {
                showToast("当前没有可用的联网搜索能力");
                if (chipWebSearch != null) chipWebSearch.setChecked(false);
                updateWebSearchChip();
                return;
            }
            cfg.supportsWebSearch = enabled;
            // 标记用户手动调整过能力：配置表刷新/能力探测不再覆盖（否则关闭后又被探测回填为默认开）
            cfg.capabilitiesUserSet = true;
            if (onlineModelManager != null) {
                onlineModelManager.updateModelConfig(cfg);
            }
            showToast(enabled ? "已开启联网搜索" : "已关闭联网搜索");
        } catch (Exception e) {
            showToast("联网搜索切换失败: " + e.getMessage());
            if (chipWebSearch != null) chipWebSearch.setChecked(false);
        }
    }

    /**
     * 联网搜索 chip 状态：三态。
     * - 不可用（未配置在线模型 / 服务商未声明 webSearch 参数）→ 置灰禁用，不可点击，防止意外启用；
     * - 可用且开启 → 主色底白字；
     * - 可用且关闭 → 灰色底灰字。
     */
    private void updateWebSearchChip() {
        if (chipWebSearch == null) return;
        boolean available = false;
        boolean on = false;
        try {
            OnlineModelManager.OnlineModelConfig cfg = onlineModelManager != null
                    ? onlineModelManager.getActiveModel() : null;
            if (cfg != null && cfg.apiUrl != null && !cfg.apiUrl.isEmpty()) {
                // 与注入/探测同源 + 自有工具优先：自有 network_search 可用则恒可用（不捆绑官方能力）
                available = com.oilquiz.app.ai.tool.NetworkSearchTool.isAvailable()
                        || com.oilquiz.app.ai.model.ProviderConfigManager.get()
                                .supportsModelCapability(cfg.apiUrl, cfg.modelName, "webSearch");
                on = available && cfg.supportsWebSearch;
            }
        } catch (Exception ignored) {
        }
        chipWebSearch.setEnabled(true); // 保持可点击：不可用时点击给原因提示（不切换状态）
        chipWebSearch.setChecked(on);
        if (!available) {
            // 不可用：灰底灰字 + 半透明 + "不可用"标记，点击仅提示原因，不会意外启用
            chipWebSearch.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.chip_gray_bg)));
            chipWebSearch.setTextColor(getColor(R.color.chip_gray_text));
            chipWebSearch.setChipStrokeColor(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.chip_gray_stroke)));
            chipWebSearch.setAlpha(0.5f);
            chipWebSearch.setText("🔍 联网搜索·不可用");
        } else if (on) {
            chipWebSearch.setAlpha(1f);
            chipWebSearch.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                    ThemeColors.attr(this, R.attr.colorPrimary)));
            chipWebSearch.setTextColor(getColor(R.color.white));
            chipWebSearch.setChipStrokeColor(android.content.res.ColorStateList.valueOf(
                    ThemeColors.attr(this, R.attr.colorPrimary)));
        } else {
            chipWebSearch.setAlpha(1f);
            chipWebSearch.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.chip_gray_bg)));
            chipWebSearch.setTextColor(getColor(R.color.chip_gray_text));
            chipWebSearch.setChipStrokeColor(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.chip_gray_stroke)));
            chipWebSearch.setText("🔍 联网搜索");
        }
    }

    /** 深度思考开关 chip 高亮状态：开启=主色底白字, 关闭=灰色底灰字 */
    private void updateDeepThinkChip(com.google.android.material.chip.Chip chip) {        if (chip == null) return;
        boolean on = ChatModeManager.getInstance(this).isDeepThinkingEnabled();
        if (on) {
            chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                    ThemeColors.attr(this, R.attr.colorPrimary)));
            chip.setTextColor(getColor(R.color.white));
            chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(
                    ThemeColors.attr(this, R.attr.colorPrimary)));
            chip.setText(getString(R.string.h_931c3c94));
        } else {
            chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.chip_gray_bg)));
            chip.setTextColor(getColor(R.color.chip_gray_text));
            chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.chip_gray_stroke)));
            chip.setText(getString(R.string.h_89093f20));
        }
    }

    /**
     * 注入模式切换指令到上下文
     * 保留对话历史，通过更新 system 提示词改变模型行为（不污染对话历史、不生成回复）。
     */
    private void injectModeSwitchInstruction(ChatModeManager.ChatMode oldMode, ChatModeManager.ChatMode newMode) {
        if (oldMode == newMode) return;

        String instruction = ChatModeManager.getModeSwitchInstruction(oldMode, newMode);
        if (instruction == null || instruction.isEmpty()) return; // 普通模式无指令
        AppLogger.ai(TAG, "Injecting mode switch instruction: " + oldMode.displayName + " -> " + newMode.displayName);

        // 本地模型：通过 appendSystemInstruction 追加到 system 提示词
        // （原实现用 chatSend 会把指令当用户消息污染历史并实际生成 token，是错误的）
        new Thread(() -> {
            try {
                if (aiService != null && aiService.isInitialized()) {
                    boolean ok = aiService.appendSystemInstruction(instruction);
                    AppLogger.ai(TAG, "Mode switch instruction appended to system prompt: " + ok);
                } else {
                    AppLogger.aiW(TAG, "AI service not ready, skip mode instruction injection");
                }
            } catch (Exception e) {
                AppLogger.aiE(TAG, "Error injecting mode switch instruction: " + e.getMessage());
            }
        }).start();
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
        refreshNativeStateUI();
        TextView tvTokenStats = findViewById(R.id.tv_token_stats);
        if (tvTokenStats != null && stats != null) {
            if (stats.requestTotalTokens > 0) {
                tvTokenStats.setVisibility(View.VISIBLE);
                // 输入/输出分开统计：请求级（本轮）输入 prompt + 输出 completion
                String text = String.format(getString(R.string.h_986cd3e8),
                        stats.requestPromptTokens, stats.requestCompletionTokens);
                // Agent 在线模式：追加缓存命中率（引擎透传 API usage）
                if (agentChatHandler != null) {
                    int hit = agentChatHandler.getLastCacheHitTokens();
                    int in = agentChatHandler.getLastPromptTokens();
                    if (hit > 0 && in > 0) {
                        int hitRate = (int) Math.round(hit * 100.0 / in);
                        text += String.format(getString(R.string.h_83afc322), hitRate);
                    }
                    // 思考 token 数（reasoning_tokens）
                    int reasoningTokens = agentChatHandler.getLastReasoningTokens();
                    if (reasoningTokens > 0) {
                        text += " · 思考" + reasoningTokens + "t";
                    }
                    // 追加上下文用量（窗口/已用/剩余）——来自模型 API 上下文大小推断 + 最近请求输入
                    try {
                        int[] ctx = agentChatHandler.getContextWindowInfo();
                        if (ctx != null && ctx.length == 3 && ctx[0] > 0) {
                            text += String.format(getString(R.string.h_d7a0f347),
                                    formatCtxWindow(ctx[0]),
                                    Math.min(100.0, ctx[1] * 100.0 / ctx[0]));
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (stats.sessionTotalTokens > 0) {
                    text += String.format(getString(R.string.h_b557980d), stats.sessionTotalTokens);
                }
                tvTokenStats.setText(text);
            } else {
                tvTokenStats.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 刷新顶部 native 状态条：生成流程状态机阶段（GenPhase）+ KV 增量缓存状态。
     *
     * <p>本地推理时实时展示思考段/正文生成阶段；KV 命中率与上下文占用来自
     * AgentKvCache 统计（JNI nativeGetKvCacheStats）。在线模型不适用 native 状态，
     * 自动隐藏。</p>
     */
    /**
     * 单行滚动切换：把思考内容按行切段，轮播/跟随最新段。
     * 内容增长（新段出现）→ 跳到最新段（实时显示最新进展）；
     * 内容停顿 → 轮播各段（滚动切换效果）。
     */
    private String buildThinkingSegment(String think) {
        java.util.List<String> segs = new java.util.ArrayList<>();
        for (String line : think.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) segs.add(t);
        }
        if (segs.isEmpty()) {
            segs.add(think);
        }
        int oldSize = thinkingSegments.size();
        thinkingSegments = segs;
        if (segs.size() > oldSize) {
            // 内容增长：显示最新段（最新进展）
            thinkingSegmentIndex = segs.size() - 1;
        } else {
            // 内容停顿：轮播到下一段（滚动切换）
            thinkingSegmentIndex = (thinkingSegmentIndex + 1) % segs.size();
        }
        String seg = segs.get(thinkingSegmentIndex);
        // 单行截断：保留最新尾部（最新思考）
        if (seg.length() > 34) seg = seg.substring(seg.length() - 34);
        return seg;
    }

    /** 思考文本按 \n 计行数（用于判断是否出现新段） */
    private int countThinkingLines(String content) {
        if (content == null) return 0;
        int n = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') n++;
        }
        return n;
    }

    /** 单行段落切换动画：新内容从左往右刮刀式滑入 + 柔和淡入（非跑马灯/打字机） */
    private void startThinkingRollerAnim() {
        try {
            if (tvGenPhase == null) return;
            android.view.animation.AnimationSet set = new android.view.animation.AnimationSet(true);
            android.view.animation.TranslateAnimation ta = new android.view.animation.TranslateAnimation(
                    android.view.animation.Animation.RELATIVE_TO_SELF, -0.6f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0f);
            ta.setDuration(320);
            ta.setInterpolator(new android.view.animation.DecelerateInterpolator());
            android.view.animation.AlphaAnimation aa = new android.view.animation.AlphaAnimation(0.3f, 1f);
            aa.setDuration(320);
            set.addAnimation(ta);
            set.addAnimation(aa);
            tvGenPhase.startAnimation(set);
        } catch (Exception ignored) {}
    }

    /** 状态机阶段英文 → 中文显示 */
    private String phaseToCn(String phase) {
        if ("PREPROCESS".equals(phase)) return "预处理";
        if ("THINKING".equals(phase)) return "思考中";
        if ("GENERATING".equals(phase)) return "生成中";
        if ("COMPLETE".equals(phase)) return "完成";
        if ("ERROR".equals(phase)) return "出错";
        if ("IDLE".equals(phase)) return "空闲";
        return phase;
    }

    private void refreshNativeStateUI() {
        try {
            // 在线模型：native 状态机不适用，隐藏
            boolean useOnline = inferenceRouter != null && inferenceRouter.isUsingOnlineModel();
            if (useOnline) {
                if (tvKvStats != null) tvKvStats.setVisibility(View.GONE);
                // tvGenPhase 在线由 onlineThinkingStream observe 实时驱动（思考时显示/结束后隐藏），
                // 此处不强制 GONE，避免 800ms 轮询与实时 observe 互相覆盖闪烁；在线无 native 状态机/速度
                return;
            }
            // 状态机栏：仅推理进行中显示；空闲/完成/无数据一律隐藏
            if (tvGenPhase != null) {
                String j = LlamaHelper.getGenPhase();
                boolean show = false;
                if (j != null && !j.isEmpty()) {
                    org.json.JSONObject o = new org.json.JSONObject(j);
                    String phase = o.optString("phase", "IDLE");
                    boolean running = o.optBoolean("running", false);
                    if (running) {
                        // 正文生成阶段附上纯 decode 速度（思考段不计，native getDecodeSpeed）
                        if ("GENERATING".equals(phase)) {
                            float ds = LlamaHelper.getDecodeSpeed();
                            if (ds > 0) {
                                tvGenPhase.setText(String.format(getString(R.string.h_743faf7e), ds));
                            } else {
                                tvGenPhase.setText(getString(R.string.h_ad0acdc5));
                            }
                        } else if ("PREPROCESS".equals(phase)) {
                            // prefill 阶段：显示进度 + 吞吐（native 分块 decode 逐块统计）
                            String pp = LlamaHelper.getPrefillProgress();
                            if (pp != null && !pp.isEmpty()) {
                                try {
                                    org.json.JSONObject po = new org.json.JSONObject(pp);
                                    int done = po.optInt("done", 0);
                                    int total = po.optInt("total", 0);
                                    int pct = po.optInt("pct", 0);
                                    int prompt = po.optInt("prompt", 0);
                                    if (total > 0) {
                                        float ps = LlamaHelper.getPhaseSpeed();
                                        if (ps > 0) {
                                            if (prompt > 0) {
                                                tvGenPhase.setText(String.format(getString(R.string.h_2a8f9dc2), prompt, pct, ps));
                                            } else {
                                                tvGenPhase.setText(String.format(getString(R.string.h_f8a477f6), pct, ps));
                                            }
                                        } else {
                                            if (prompt > 0) {
                                                tvGenPhase.setText(String.format(getString(R.string.h_e3ad3e92), prompt, pct));
                                            } else {
                                                tvGenPhase.setText(String.format(getString(R.string.h_2dcef5d6), pct));
                                            }
                                        }
                                    } else {
                                        tvGenPhase.setText(getString(R.string.h_803f889b));
                                    }
                                } catch (Exception ignored) {
                                    tvGenPhase.setText(getString(R.string.h_803f889b));
                                }
                            } else {
                                tvGenPhase.setText(getString(R.string.h_803f889b));
                            }
                            lastThinkShown = null;   // 非思考阶段重置，下次思考重新开始
                            lastThinkLineCount = 0;
                        } else if ("THINKING".equals(phase)) {
                            // 顶部单行由 thinking 事件驱动（120ms 节流，无 800ms 轮询限制）：
                            // 这里只负责首次进入思考时初始化显示，内容段落切换/动画交给 thinkingRefreshRunnable
                            if (lastThinkShown == null) {
                                tvGenPhase.setText("💭 ");
                                lastThinkShown = "💭 ";
                            }
                        } else {
                            tvGenPhase.setText("⏳ " + phaseToCn(phase));
                            lastThinkShown = null;   // 非思考阶段重置，下次思考重新开始
                            lastThinkLineCount = 0;
                        }
                        tvGenPhase.setVisibility(View.VISIBLE);
                        show = true;
                    }
                }
                if (!show) tvGenPhase.setVisibility(View.GONE);
            }
            // KV 缓存栏：思考/生成（推理中）隐藏——位置留给思考内容/生成状态显示；
            // 空闲时显示缓存状态（监控用，性能面板亦有完整卡片）。
            // 2026-09-23：在线模型下隐藏——KV cache 是本地 Llama 引擎的上下文统计，
            // 与统计条「📊 上下文占用」（在线 token 估算）口径不同，并存会造成数据不一致
            if (tvKvStats != null) {
                boolean runningNow = false;
                String gp = LlamaHelper.getGenPhase();
                if (gp != null && !gp.isEmpty()) {
                    try {
                        runningNow = new org.json.JSONObject(gp).optBoolean("running", false);
                    } catch (Exception ignored) {}
                }
                if (runningNow || shouldUseOnlineModel()) {
                    tvKvStats.setVisibility(View.GONE);
                } else {
                    String j = LlamaHelper.getKvCacheStats();
                    boolean show = false;
                    if (j != null && !j.isEmpty()) {
                        org.json.JSONObject o = new org.json.JSONObject(j);
                        double hit = o.optDouble("hit_rate_pct", -1);
                        double usage = o.optDouble("ctx_usage_pct", -1);
                        int plans = o.optInt("plans", 0);
                        if (hit >= 0 && plans > 0) {
                            tvKvStats.setText(String.format(getString(R.string.h_3e238a20),
                                    hit, usage >= 0 ? usage : 0));
                            tvKvStats.setVisibility(View.VISIBLE);
                            show = true;
                        }
                    }
                    if (!show) tvKvStats.setVisibility(View.GONE);
                }
            }
        } catch (Throwable t) {
            // 解析失败/异常：隐藏状态条，不影响主流程
            if (tvGenPhase != null) tvGenPhase.setVisibility(View.GONE);
            if (tvKvStats != null) tvKvStats.setVisibility(View.GONE);
        }
    }

    /** 启动状态条轮询（onResume 时）：立即刷新一次 + 周期刷新，覆盖思考段无 token 回调的场景 */
    private void startStatePolling() {
        if (statePolling) return;
        statePolling = true;
        refreshNativeStateUI();
        statePollHandler.removeCallbacks(statePollRunnable);
        statePollHandler.postDelayed(statePollRunnable, STATE_POLL_INTERVAL_MS);
    }

    /** 停止状态条轮询（onDestroy 时） */
    private void stopStatePolling() {
        statePolling = false;
        statePollHandler.removeCallbacksAndMessages(null);
    }

    /**
     * 上下文窗口格式化：>=1M 显示 "1M"（如 deepseek-v4 的 1048576），>=1K 显示 "64K"，否则原值。
     */
    private String formatCtxWindow(int window) {
        if (window >= 1000000) {
            return (window / 1000000) + "M";
        }
        if (window >= 1000) {
            return (window / 1000) + "K";
        }
        return String.valueOf(window);
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
        // native 状态条（状态机阶段 + KV 缓存）随推理 token 流式更新实时刷新
        refreshNativeStateUI();
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
                // 本地模式：优先使用 native 层；TPS 按状态机阶段切换（思考速度/解码速度）
                float phaseSpeed = LlamaHelper.getPhaseSpeed();
                float nativeTps = LlamaHelper.getInferenceSpeed();
                int nativeTokens = LlamaHelper.getTokenCount();
                displayTps = phaseSpeed > 0 ? phaseSpeed : (nativeTps > 0 ? nativeTps : tokensPerSecond);
                displayTokens = nativeTokens > 0 ? nativeTokens : totalTokens;
                sourceTag = "⚡"; // 本地 native
                // 阶段标签：THINKING 思考段用 🧠，GENERATING 正文用 ⚡
                try {
                    String pj = LlamaHelper.getGenPhase();
                    if (pj != null && !pj.isEmpty()) {
                        String ph = new org.json.JSONObject(pj).optString("phase", "");
                        if ("THINKING".equals(ph)) sourceTag = "🧠";
                        else if ("GENERATING".equals(ph)) sourceTag = "⚡";
                    }
                } catch (Throwable ignored) {}
            }

            if (isGenerating && displayTps > 0) {
                String statsText = String.format("%s %.1f t/s | %d tokens", sourceTag, displayTps, displayTokens);
                tvTokenStats.setText(statsText);
            } else {
                // 完成态：在线模式优先显示 API 输入/输出统计（本请求）
                if (useOnline && (onlinePromptTokens > 0 || onlineCompletionTokens > 0)) {
                    String statsText = String.format(getString(R.string.h_c5d9a5e1),
                            onlinePromptTokens, onlineCompletionTokens);
                    tvTokenStats.setText(statsText);
                } else {
                    String statsText = String.format("✅ %d tokens", displayTokens);
                    tvTokenStats.setText(statsText);
                }
            }
        }
    }

    private void stopGeneration() {
        try {
            if (agentChatHandler != null && agentChatHandler.isGenerating()) agentChatHandler.cancel();
            if (modelBridge != null) modelBridge.execute(ChatCommand.stopGeneration(), null);
            endGeneration();
            showToast(getString(R.string.h_9b2011e3));
            addSystemMessage("生成已停止");
        } catch (Exception e) { AppLogger.aiE(TAG, "Error stopping: " + e.getMessage()); }
    }

    private void regenerateLastMessage() {
        if (isGenerating) { showToast(getString(R.string.h_9447f530)); return; }
        String lastUserMsg = null;
        int lastUserIdx = -1;
        int lastAiIdx = -1;
        for (int i = chatHistory.size() - 1; i >= 0; i--) {
            ChatMessage msg = chatHistory.get(i);
            if (msg.type == ChatMessage.MessageType.AI && lastAiIdx < 0) lastAiIdx = i;
            else if (msg.type == ChatMessage.MessageType.USER) { lastUserMsg = msg.content; lastUserIdx = i; break; }
        }
        if (lastUserMsg == null || lastAiIdx < 0) { showToast(getString(R.string.h_687b9420)); return; }
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

    /** 查询在线 API 余额并显示到状态栏（仅在线模型；DeepSeek 官方 API 支持余额接口） */
    private void updateApiBalanceDisplay() {
        if (tvApiBalance == null) return;
        if (!shouldUseOnlineModel()) {
            tvApiBalance.setVisibility(View.GONE);
            return;
        }
        OnlineModelManager.OnlineModelConfig active =
                onlineModelManager != null ? onlineModelManager.getActiveModel() : null;
        if (active == null) {
            tvApiBalance.setVisibility(View.GONE);
            return;
        }
        com.oilquiz.app.ai.util.ApiBalanceChecker.checkAsync(active, (balance, error) ->
                runOnUiThread(() -> {
                    if (tvApiBalance == null) return;
                    if (balance != null && !balance.isEmpty()) {
                        tvApiBalance.setText("💰 " + balance);
                        tvApiBalance.setVisibility(View.VISIBLE);
                    } else {
                        tvApiBalance.setVisibility(View.GONE);
                    }
                }));
    }

    /**
     * NPU（Qualcomm GenieX）引擎是否开启。
     *
     * <p>开启后，所有"llama.cpp 本地模型"的状态检查（模型文件是否存在 / native 上下文是否有效 /
     * 自动恢复）都必须跳过 —— 否则发送会被自动恢复逻辑截走，去加载本机并不存在的本地模型，
     * 界面就卡在「准备模型文件」。
     */
    private boolean isNpuEngineOn() {
        try {
            return com.oilquiz.app.ai.inference.InferenceRouter
                    .getInstance(this).isNpuEngineEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean ensureModelLoaded(String pendingMessage) {
        if (shouldUseOnlineModel()) {
            return true;
        }
        // NPU（Qualcomm GenieX）引擎：模型由 GenieX 自己按需加载（本地侧载 GGUF），
        // 跟 llama.cpp 那套"本地模型文件必须在"的检查无关 —— 否则开了 NPU 也会被这里拦住，
        // 报"模型文件不存在，请重新导入或切换模型"。
        if (isNpuEngineOn()) {
            return true;
        }
        if (aiService == null) { showToast(getString(R.string.h_24e21aa7)); return false; }
        // 模型文件不存在：明确提示并停止（避免每次发送都触发初始化失败反复报错）
        if (!aiService.isCurrentModelFileExists()) {
            showToast(getString(R.string.h_db87c7aa));
            addSystemMessage("⚠️ 模型文件不存在：当前模型文件可能已被删除，请到模型设置中重新导入模型或切换到在线模型",
                    ChatMessage.SystemMessageType.WARNING);
            return false;
        }
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
                            showToast(getString(R.string.h_5eb96784));
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
        addUserMessage(message, false);
    }

    private void addUserMessage(String message, boolean voiceInput) {
        if (chatHistory == null) return;

        // 隐藏空状态
        updateEmptyState();
        // 回合 id（消息对绑定）：由 ID 派发器发放主 id，本回合所有 UI 组件消息共享；
        // 消息自身申请子 id（对内序号）。清空对话时派发器重置（见 clearChat/startNewConversation）
        currentTurnId = ChatIdDispatcher.getInstance().applyMasterId();
        ChatMessage msg = ChatMessage.createUserMessage(java.util.UUID.randomUUID().toString(), message, System.currentTimeMillis());
        msg.turnId = currentTurnId;
        msg.subId = ChatIdDispatcher.getInstance().applySubId(ChatIdDispatcher.IdType.USER);
        msg.voiceInput = voiceInput;
        chatHistory.add(msg);
        if (chatAdapter != null) {
            chatAdapter.notifyItemInserted(chatHistory.size() - 1);
        }
        scrollToBottom(true);
        // 2026-09-23：用户消息上屏即刷统计+上下文 pill（发送后立即反映上下文占用）
        refreshSessionStats();
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

    private int addToolCallMessage(String toolName, String parameters) {
        return addToolCallMessage(null, toolName, parameters);
    }

    private int addToolCallMessage(String toolCallId, String toolName, String parameters) {
        if (chatHistory == null) return -1;
        // Agent 模式：执行过程已由独立 Agent 面板展示，消息流不再插入工具卡片（避免双通道渲染）
        if (currentAgentGroupId != null) return -1;

        ChatMessage msg = ChatMessage.createToolCallMessage(toolCallId, toolName, parameters);
        // 消息对绑定：与本回合 user/AI 消息共享 turnId（跨任务/恢复后配对不丢）+ 子 id（工具类）
        msg.turnId = currentTurnId;
        msg.subId = ChatIdDispatcher.getInstance().applySubId(ChatIdDispatcher.IdType.TOOL);
        // 标记所属agent组
        if (currentAgentGroupId != null) {
            msg.agentGroupId = currentAgentGroupId;
            agentGroupToolCount++;
            chatAdapter.updateAgentGroupCounts(currentAgentGroupId, agentGroupStepCount, agentGroupToolCount);
        }
        // 插入到流式AI消息前面，使AI气泡始终显示在agent执行UI的最后面。
        // 按 messageId 实时定位插入点（2026-09-14）：不再依赖缓存索引，
        // 多轮工具/步骤消息穿插后 AI 消息位置由 id 确定，杜绝插入错位
        int aiPos = resolveStreamingIndex();
        int insertPos = (aiPos >= 0) ? aiPos : chatHistory.size();
        chatHistory.add(insertPos, msg);
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
        // 消息对绑定：与本回合 user/AI 消息共享 turnId + 子 id（工具执行步骤类）
        msg.turnId = currentTurnId;
        msg.subId = ChatIdDispatcher.getInstance().applySubId(ChatIdDispatcher.IdType.TOOL);
        // 标记所属agent组
        if (currentAgentGroupId != null) {
            msg.agentGroupId = currentAgentGroupId;
            agentGroupStepCount++;
            chatAdapter.updateAgentGroupCounts(currentAgentGroupId, agentGroupStepCount, agentGroupToolCount);
        }
        // 按 messageId 实时定位插入点（2026-09-14）：不依赖缓存索引
        int aiPos = resolveStreamingIndex();
        int insertPos = (aiPos >= 0) ? aiPos : chatHistory.size();
        chatHistory.add(insertPos, msg);
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

    // ==================== dsh 对齐：上下文仪表 / 队列 / 回底 / 展示行（2026-09-23） ====================

    /** 回读离开底部时显示回底按钮，回到底部时隐藏 */
    private void updateScrollBottomButton() {
        if (btnScrollBottom == null || messageList == null) return;
        boolean atBottom = isUserAtBottom();
        btnScrollBottom.setVisibility(atBottom ? View.GONE : View.VISIBLE);
    }

    /** 估算当前对话历史 token 用量（与 ChatContextBuilder.estimateTokens 同口径，用于仪表展示） */
    private long estimateHistoryTokens() {
        long total = 0;
        if (chatHistory != null) {
            synchronized (chatHistory) {
                for (ChatMessage m : chatHistory) {
                    if (m == null) continue;
                    if (m.type == ChatMessage.MessageType.USER || m.type == ChatMessage.MessageType.AI) {
                        String c = m.getContent();
                        if (c != null && !c.isEmpty()) total += 4 + (c.length() + 1) / 2;
                    }
                }
            }
        }
        return total;
    }

    /** 在线模型上下文窗口：优先取模型真实 contextWindow/contextLength
     *  （deepseek 1M 等），无则回退本地配置，兜底 32K。
     *  2026-09-23：此前误取本地 aiConfig（本地 KV 尺寸）→ 与在线模型窗口不一致。 */
    private int resolveOnlineContextWindow() {
        int ctx = 0;
        if (onlineModelManager != null) {
            try {
                com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig active =
                        onlineModelManager.getActiveModel();
                if (active != null && active.contextWindow > 0) {
                    ctx = active.contextWindow;
                }
            } catch (Throwable ignored) {}
        }
        if (ctx <= 0 && aiConfig != null) ctx = aiConfig.getContextSize();
        if (ctx <= 0) ctx = 32768;
        return ctx;
    }

    /** 刷新上下文仪表百分比（发送后 / 生成结束后调用）；2026-09-23 并入 session_stats_bar。
     *  2026-09-23 改：在线模型不再用字符估算（estimateHistoryTokens），
     *  直接用引擎 API 真实 usage（getContextWindowInfo → lastPromptTokens，引擎最近一次
     *  请求实际发送的 prompt tokens）——字符估算会与模型真实 token 不一致。 */
    private void refreshContextMeter() {
        if (sessionStatsBar == null) return;
        try {
            long window;
            long used;
            if (shouldUseOnlineModel() && agentChatHandler != null) {
                int[] ctx = agentChatHandler.getContextWindowInfo();
                if (ctx != null && ctx.length == 3 && ctx[0] > 0) {
                    window = ctx[0];
                    used = Math.max(0, ctx[1]);   // API 真实输入 token（上次请求）
                } else {
                    window = resolveOnlineContextWindow();
                    used = estimateHistoryTokens();
                }
            } else {
                // 本地模型：优先本地引擎 KV 上下文真实 token（推理缓存实际占用），
                // 未加载/不可用时回退预算+字符估算
                long nativeUsed = 0;
                long nativeSize = 0;
                try {
                    nativeUsed = LlamaHelper.getContextUsedTokens();
                    nativeSize = LlamaHelper.getContextSize();
                } catch (Throwable ignored) {}
                if (nativeUsed > 0) {
                    used = nativeUsed;
                    window = nativeSize > 0 ? nativeSize : 0;
                    if (window <= 0) {
                        long budget = getChatContextBuilder().getContextBudgetTokens();
                        window = budget > 0 ? budget : 0;
                    }
                } else {
                    long budget = getChatContextBuilder().getContextBudgetTokens();
                    window = budget > 0 ? budget : resolveOnlineContextWindow();
                    used = estimateHistoryTokens();
                }
            }
            AppLogger.i(TAG, "ContextMeter refresh: used=" + used + " window=" + window
                    + " chatHistory=" + (chatHistory != null ? chatHistory.size() : -1)
                    + " online=" + shouldUseOnlineModel());
            if (window <= 0) { sessionStatsBar.setContextPercent(-1, 0); return; }
            int percent = (int) Math.min(100, used * 100 / window);
            sessionStatsBar.setContextPercent(percent, used);
        } catch (Throwable t) {
            AppLogger.aiW(TAG, "refreshContextMeter failed: " + t.getMessage());
            sessionStatsBar.setContextPercent(-1, 0);
        }
    }

    /** 上下文仪表点击：弹出用量明细（used/window + system/messages 分段条） */
    private void showContextMeterDialog() {
        try {
            long window;
            boolean online = shouldUseOnlineModel();
            int apiCacheHit = -1;   // API 返回的缓存命中量（在线模型）
            long apiUsed = -1;      // API 返回的真实输入 token
            if (online && agentChatHandler != null) {
                // 2026-09-23：与 pill 统一口径——用 API 真实 usage（引擎最近一次请求
                // prompt_tokens），不再字符估算；缓存命中量取 API 返回的 cache hit tokens
                int[] ctx = agentChatHandler.getContextWindowInfo();
                if (ctx != null && ctx.length == 3 && ctx[0] > 0) {
                    window = ctx[0];
                    apiUsed = Math.max(0, ctx[1]);
                    apiCacheHit = agentChatHandler.getLastCacheHitTokens();
                } else {
                    window = resolveOnlineContextWindow();
                }
            } else if (online) {
                window = resolveOnlineContextWindow();
            } else {
                long b = getChatContextBuilder().getContextBudgetTokens(); window = b > 0 ? b : 0;
            }
            long used = apiUsed >= 0 ? apiUsed : estimateHistoryTokens();
            if (window <= 0) { showToast("上下文窗口未知"); return; }
            int percent = (int) Math.min(100, used * 100 / window);
            android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
            panel.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = (int) (18 * getResources().getDisplayMetrics().density);
            panel.setPadding(pad, pad, pad, pad);
            android.widget.TextView header = new android.widget.TextView(this);
            header.setText("上下文用量：" + percent + "%（" + (online ? "在线模型" : "本地模型") + "）");
            header.setTextSize(14f);
            header.setTextColor(ThemeColors.attr(this, R.attr.colorOnSurface));
            panel.addView(header);
            android.widget.TextView figures = new android.widget.TextView(this);
            figures.setText((apiUsed >= 0 ? "" : "~") + used + " / " + window + " tokens"
                    + (apiUsed >= 0 ? "（API 真实输入）" : "（估算）"));
            figures.setTextSize(12f);
            figures.setPadding(0, (int)(4 * getResources().getDisplayMetrics().density), 0, (int)(8 * getResources().getDisplayMetrics().density));
            figures.setTextColor(ThemeColors.attr(this, R.attr.colorControlTextSecondary));
            panel.addView(figures);
            // API 缓存命中行：deepseek prompt_cache_hit_tokens / prompt_cache_miss_tokens
            // （2026-09-23 全部 API 直读，无返回时推算）
            if (online && apiCacheHit > 0) {
                android.widget.TextView cacheLine = new android.widget.TextView(this);
                int miss = agentChatHandler != null ? agentChatHandler.getLastCacheMissTokens() : 0;
                if (miss <= 0) miss = (int) Math.max(0, used - apiCacheHit);
                int hitRate = used > 0 ? (int) Math.round(apiCacheHit * 100.0 / used) : 0;
                cacheLine.setText("API 缓存命中 " + apiCacheHit + " · 新增 " + miss
                        + " tokens（命中率 " + hitRate + "%）");
                cacheLine.setTextSize(12f);
                cacheLine.setPadding(0, (int)(4 * getResources().getDisplayMetrics().density), 0, (int)(4 * getResources().getDisplayMetrics().density));
                cacheLine.setTextColor(0xFF059669);
                panel.addView(cacheLine);
            }
            // 分段条：system（本次组装注入）/ messages（历史）（估算口径，仅作构成示意）
            android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
            bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            int h = (int) (6 * getResources().getDisplayMetrics().density);
            long sysTokens = estimateSystemInjectTokens();
            long msgTokens = used - sysTokens;
            if (msgTokens < 0) msgTokens = 0;
            int sysW = percent > 0 && used > 0 ? (int) (percent * sysTokens / used) : 0;
            android.view.View sysSeg = new android.view.View(this);
            sysSeg.setBackgroundColor(0xFF4F46E5);
            bar.addView(sysSeg, new android.widget.LinearLayout.LayoutParams(sysW > 0 ? sysW : 0, h, 0));
            android.view.View msgSeg = new android.view.View(this);
            msgSeg.setBackgroundColor(0xFF10B981);
            int msgW = percent - sysW;
            bar.addView(msgSeg, new android.widget.LinearLayout.LayoutParams(msgW > 0 ? msgW : 0, h, 0));
            android.widget.LinearLayout.LayoutParams barLp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, h);
            bar.setLayoutParams(barLp);
            panel.addView(bar);
            android.widget.LinearLayout legend = new android.widget.LinearLayout(this);
            legend.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            legend.setPadding(0, (int)(8 * getResources().getDisplayMetrics().density), 0, 0);
            android.widget.TextView l1 = new android.widget.TextView(this);
            l1.setText("■ 系统注入 " + sysTokens);
            l1.setTextSize(11f);
            l1.setTextColor(0xFF4F46E5);
            legend.addView(l1);
            android.widget.TextView l2 = new android.widget.TextView(this);
            l2.setText("  ■ 对话历史 " + msgTokens);
            l2.setTextSize(11f);
            l2.setTextColor(0xFF10B981);
            l2.setPadding((int)(8 * getResources().getDisplayMetrics().density), 0, 0, 0);
            legend.addView(l2);
            panel.addView(legend);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("上下文占用")
                    .setView(panel)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            AILogger.w(TAG, "showContextMeterDialog failed: " + t.getMessage());
        }
    }

    /** 估算本轮系统注入文本 token（persona + 环境段；用于仪表分段展示） */
    private long estimateSystemInjectTokens() {
        long total = 0;
        String persona = aiConfig != null && aiConfig.getSystemPrompt() != null ? aiConfig.getSystemPrompt() : "";
        if (!persona.isEmpty()) total += 4 + (persona.length() + 1) / 2;
        total += 4 + 40; // 环境段（当前日期行）估算
        return total;
    }

    /** 发送队列持久化（Activity 重建/进程恢复后不丢排队消息；对齐 dsh Inbox 的跨生命周期语义） */
    private void persistPendingQueue() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String s : pendingQueue) {
                if (s != null) arr.put(s);
            }
            getSharedPreferences("ai_prefs", MODE_PRIVATE)
                    .edit().putString("pending_queue", arr.toString()).apply();
        } catch (Throwable t) {
            AppLogger.aiW(TAG, "persistPendingQueue failed: " + t.getMessage());
        }
    }

    /** 恢复发送队列（onCreate 调用；队列条随后按 updateQueueBar 刷新） */
    private void restorePendingQueue() {
        try {
            String raw = getSharedPreferences("ai_prefs", MODE_PRIVATE)
                    .getString("pending_queue", null);
            if (raw == null || raw.isEmpty()) return;
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            pendingQueue.clear();
            for (int i = 0; i < arr.length(); i++) {
                pendingQueue.add(arr.getString(i));
            }
            if (!pendingQueue.isEmpty()) {
                AppLogger.ai(TAG, "[restorePendingQueue] 恢复 " + pendingQueue.size() + " 条排队消息");
                updateQueueBar();
            }
        } catch (Throwable t) {
            AppLogger.aiW(TAG, "restorePendingQueue failed: " + t.getMessage());
        }
    }

    /** 队列条刷新 */
    private void updateQueueBar() {
        if (queueBar == null || tvQueueInfo == null) return;
        if (pendingQueue.isEmpty()) {
            queueBar.setVisibility(View.GONE);
            return;
        }
        queueBar.setVisibility(View.VISIBLE);
        String modeHint;
        if ("queue".equals(busySendMode)) modeHint = "排队模式";
        else if ("steer".equals(busySendMode)) modeHint = "打断模式";
        else modeHint = "点击切换模式";
        tvQueueInfo.setText("⏳ " + pendingQueue.size() + " 条待发送 · " + modeHint);
    }

    /** 生成结束自动发送下一条排队消息 */
    private void drainQueueIfAny() {
        if (pendingQueue.isEmpty() || isGenerating) return;
        String next = pendingQueue.remove(0);
        updateQueueBar();
        persistPendingQueue();
        inputMessage.setText(next);
        sendMessage();
    }

    /** 循环切换忙时模式：拒绝 → 排队 → 打断 → 拒绝 */
    private void cycleBusyMode() {
        if ("block".equals(busySendMode)) busySendMode = "queue";
        else if ("queue".equals(busySendMode)) busySendMode = "steer";
        else busySendMode = "block";
        getSharedPreferences("ai_prefs", MODE_PRIVATE).edit().putString(PREFS_BUSY_MODE, busySendMode).apply();
        String label = "block".equals(busySendMode) ? "拒绝" : ("queue".equals(busySendMode) ? "排队" : "打断");
        showToast("忙时发送模式：" + label);
        updateQueueBar();
    }

    /** 发送前插入 SystemPromptRow：prompt 签名变化时展示变更说明 + 完整 system 文本 */
    private void maybeInsertSystemPromptRow() {
        try {
            String cur = getChatContextBuilder().currentPromptSignature();
            if (lastPromptSigForRow == null) {
                lastPromptSigForRow = cur;
                return;
            }
            String desc = getChatContextBuilder().describePromptChange(lastPromptSigForRow, cur);
            lastPromptSigForRow = cur;
            if (desc == null) return;
            StringBuilder sys = new StringBuilder();
            String persona = aiConfig != null && aiConfig.getSystemPrompt() != null ? aiConfig.getSystemPrompt() : "";
            if (!persona.isEmpty()) sys.append(persona).append("\n");
            sys.append("【环境上下文】当前日期：").append(new java.text.SimpleDateFormat("yyyy年M月d日 EEEE", java.util.Locale.CHINA).format(new java.util.Date()));
            String content = "系统提示词已更新：" + desc + "\n\n" + sys.toString().trim();
            ChatMessage row = ChatMessage.createSystemMessage(
                    java.util.UUID.randomUUID().toString(), content,
                    ChatMessage.SystemMessageType.SYSTEM_PROMPT, System.currentTimeMillis());
            chatHistory.add(row);
            if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1);
            scrollToBottom(true);
        } catch (Throwable t) {
            AILogger.w(TAG, "maybeInsertSystemPromptRow failed: " + t.getMessage());
        }
    }

    /** 本地 Agent 消息发送前插入 ContextInjectionRow：本轮注入的记忆/任务摘要 */
    private void maybeInsertContextInjectionRow() {
        if (aiConfig == null || !aiConfig.isLocalAgentEnabled()) return;
        try {
            StringBuilder sb = new StringBuilder();
            String memory = com.oilquiz.app.ai.agent.online.AgentMemoryStore.getInstance(this).buildMemorySummary();
            if (memory != null && !memory.trim().isEmpty()) {
                sb.append("已注入长期记忆：").append(memory.trim()).append("\n");
            }
            String task = com.oilquiz.app.ai.tool.TaskStateTracker.getInstance(this).buildTaskSummary();
            if (task != null && !task.trim().isEmpty()) {
                sb.append("已注入活跃任务：").append(task.trim()).append("\n");
            }
            if (sb.length() == 0) return;
            String content = "本轮已注入上下文（记忆/任务自动携带，模型可直接使用）\n\n" + sb.toString().trim();
            ChatMessage row = ChatMessage.createSystemMessage(
                    java.util.UUID.randomUUID().toString(), content,
                    ChatMessage.SystemMessageType.CONTEXT_INJECTION, System.currentTimeMillis());
            chatHistory.add(row);
            if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1);
            scrollToBottom(true);
        } catch (Throwable t) {
            AILogger.w(TAG, "maybeInsertContextInjectionRow failed: " + t.getMessage());
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
            if (force || isStreaming) {
                // force（重进页面/加载完成定位）与流式中：直接定位（立即生效，不依赖平滑滚动动画）
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
                // 键盘弹出：快捷工具在抽屉内不受影响，仅滚动到底部 + 确保输入框可见
                scrollToBottom();
                // 确保输入框区域可见：延迟等待布局稳定后滚动
                if (inputMessage != null) {
                    inputMessage.postDelayed(() -> {
                        inputMessage.requestFocus();
                        scrollToBottom();
                    }, 100);
                }
            } else {
                // 键盘隐藏：快捷工具在抽屉内无需恢复
            }
        });
    }

    private void handleAction(ChatMessage.Action action) {
        // 处理getString(R.string.h_17f74c43)按钮：优先处理，避免被 dialogHelper 当作未知 Action
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
        if (url != null) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); } catch (Exception e) { showToast(getString(R.string.h_4b33aa1b)); } }
    }

    /** 应用内图片预览（PhotoView 双指缩放，点击关闭）——避免依赖系统图片查看器 */
    private void showImagePreview(String url) {
        showImagePreview(url, null, null);
    }

    /**
     * 图片预览：优先使用本地文件路径（thumbnailPath/localFilePath），
     * Glide 加载 file:// 稳定；避免 content:// 权限过期导致一直转圈。
     */
    private void showImagePreview(String url, String thumbnailPath, String localFilePath) {
        // 统一全屏预览（ImagePreviewUtil）：原图优先，缩略图兜底，全 App 同一套全屏流
        String target = url;
        if (localFilePath != null && !localFilePath.isEmpty()
                && new java.io.File(localFilePath).isFile()) {
            target = localFilePath;
        } else if (thumbnailPath != null && !thumbnailPath.isEmpty()
                && new java.io.File(thumbnailPath).isFile()) {
            target = thumbnailPath;
        }
        com.oilquiz.app.ai.chat.component.ImagePreviewUtil.show(this, target);
        return;
        // ===================== 旧实现（已由 ImagePreviewUtil 取代，保留备查） =====================
        /*
        try {
            // 解析可用的本地路径（优先级：localFilePath 原图 → thumbnailPath 缩略图 → url）
            // 注意：预览要看原图，缩略图只在原图缺失时兜底（否则小图显示出来像"不是全屏"）
            String loadTarget = null;
            if (localFilePath != null && !localFilePath.isEmpty()) {
                java.io.File f = new java.io.File(localFilePath);
                if (f.exists()) loadTarget = Uri.fromFile(f).toString();
            }
            if (loadTarget == null && thumbnailPath != null && !thumbnailPath.isEmpty()) {
                java.io.File f = new java.io.File(thumbnailPath);
                if (f.exists()) loadTarget = Uri.fromFile(f).toString();
            }
            if (loadTarget == null && url != null && !url.isEmpty()) {
                // content:// 或 file:// 或 http(s):// 原样传 Glide；纯文件路径转 file://
                if (url.startsWith("/")) {
                    java.io.File f = new java.io.File(url);
                    if (f.exists()) loadTarget = Uri.fromFile(f).toString();
                    else loadTarget = url;
                } else {
                    loadTarget = url;
                }
            }
            if (loadTarget == null) return;
            android.util.Log.i("AIChatActivity", "Image preview target: " + loadTarget
                    + " (thumb=" + thumbnailPath + ", local=" + localFilePath + ", url=" + url + ")");

            android.app.Dialog dialog = new android.app.Dialog(this);
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

            android.widget.FrameLayout root = new android.widget.FrameLayout(this);
            root.setBackgroundColor(android.graphics.Color.BLACK);

            com.github.chrisbanes.photoview.PhotoView photoView = new com.github.chrisbanes.photoview.PhotoView(this);
            photoView.setBackgroundColor(android.graphics.Color.BLACK);
            // 初始 fit 全屏（Attacher 接管前先声明），图片按比例撑满可视区
            photoView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
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
                // 强制全屏布局（Dialog window 默认 wrap，避免显示成小窗）
                dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK));
            }

            // show 之后再加载（View 已 attach），loading 占位 + 失败提示
            // 优先直接解码本地文件（BitmapFactory），彻底绕开 Glide 对 file:// 的可能问题
            boolean decoded = false;
            try {
                java.io.File localFile = null;
                if (loadTarget != null && loadTarget.startsWith("file://")) {
                    localFile = new java.io.File(Uri.parse(loadTarget).getPath());
                }
                if (localFile == null && localFilePath != null && !localFilePath.isEmpty()) {
                    java.io.File f = new java.io.File(localFilePath);
                    if (f.exists()) localFile = f;
                }
                if (localFile == null && thumbnailPath != null && !thumbnailPath.isEmpty()) {
                    java.io.File f = new java.io.File(thumbnailPath);
                    if (f.exists()) localFile = f;
                }
                if (localFile != null && localFile.exists()) {
                    // 采样解码避免大图 OOM
                    android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeFile(localFile.getAbsolutePath(), opts);
                    int sample = 1;
                    while (opts.outWidth / sample > 2048 || opts.outHeight / sample > 2048) {
                        sample *= 2;
                    }
                    opts.inJustDecodeBounds = false;
                    opts.inSampleSize = sample;
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(localFile.getAbsolutePath(), opts);
                    if (bmp != null) {
                        photoView.setImageBitmap(bmp);
                        loading.setVisibility(android.view.View.GONE);
                        decoded = true;
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("AIChatActivity", "Bitmap decode failed, fallback Glide: " + e.getMessage());
            }
            if (!decoded) {
                com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> listener =
                        new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                            @Override
                            public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                                loading.setVisibility(android.view.View.GONE);
                                showToast(getString(R.string.h_b3b83e12));
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
                com.bumptech.glide.Glide.with(this).load(loadTarget).timeout(15000).listener(listener).into(photoView);
            }
        } catch (Exception e) {
            android.util.Log.w("AIChatActivity", "Image preview failed: " + e.getMessage());
        }
        */
    }

    // ===================== Agent 执行过程（插入式组件显示） =====================

    /**
     * 将工具执行过程以组件形式插入到当前流式 AI 消息内（组件容器在回答文本上方）。
     * 工具开始 → running 卡片；工具完成 → 按 toolCallId 精确更新为 success/failed 卡片
     * （不再"找最后一张 running"，避免并行工具/乱序回调更新错位）。
     * 消息定位：按 currentStreamingMessageId 在 chatHistory 中查找，
     * 不依赖可漂移的 currentStreamingMessageIndex（插入 thinking/tool 卡片后索引会变化）。
     */
    private void appendAgentToolCall(String toolCallId, String toolName, String status, String args, String result) {
        final int msgIndex = findMessageIndexById(currentStreamingMessageId);
        if (msgIndex < 0 || msgIndex >= chatHistory.size()) return;
        ChatMessage msg = chatHistory.get(msgIndex);
        if (msg == null) return;

        try {
            java.util.List<com.oilquiz.app.ai.chat.component.ComponentData> comps =
                    msg.components != null ? new java.util.ArrayList<>(msg.components) : new java.util.ArrayList<>();

            // 工具完成：按 toolCallId 精确匹配对应卡片（无 id 时兼容回退：匹配同工具名最后一张 running）
            if ("success".equals(status) || "failed".equals(status)) {
                for (int i = comps.size() - 1; i >= 0; i--) {
                    com.oilquiz.app.ai.chat.component.ComponentData c = comps.get(i);
                    if (c == null || !"tool_call".equals(c.type)) continue;
                    String cardStatus = c.props != null ? c.props.optString("status", "") : "";
                    if (!"running".equals(cardStatus)) continue;
                    String cardCallId = c.props != null ? c.props.optString("toolCallId", "") : "";
                    // 优先按 id 匹配；id 缺失时按工具名匹配（兼容旧回调）
                    if (toolCallId != null && !toolCallId.isEmpty()) {
                        if (!toolCallId.equals(cardCallId)) continue;
                    } else if (toolName != null && !toolName.isEmpty()) {
                        if (!toolName.equals(c.props.optString("toolName", ""))) continue;
                    }
                    if (c.props == null) c.props = new org.json.JSONObject();
                    c.props.put("status", status);
                    if (result != null) c.props.put("result", result);
                    // 附加该工具通过 withComponent 产生的结构化组件（list_card 等），实时显示不等 Agent 完成
                    java.util.List<com.oilquiz.app.ai.chat.component.ComponentData> drained =
                            com.oilquiz.app.ai.chat.component.ComponentCollector.drain();
                    if (drained != null && !drained.isEmpty()) {
                        comps.addAll(drained);
                    }
                    // 新引用触发 ChatAdapter 组件容器重建
                    msg.components = new java.util.ArrayList<>(comps);
                    if (chatAdapter != null) {
                        chatAdapter.notifyItemChanged(msgIndex, ChatAdapter.PAYLOAD_CONTENT_UPDATE);
                    }
                    // 工具完成即落盘：防止生成尚未结束时刷新界面导致已合并组件丢失
                    saveHistoryAsync();
                    scrollToBottom();
                    return;
                }
            }

            // 新工具调用：追加 running 卡片（携带 toolCallId 供完成时精确匹配）
            org.json.JSONObject props = new org.json.JSONObject();
            props.put("toolCallId", toolCallId != null ? toolCallId : "");
            props.put("toolName", toolName != null ? toolName : "工具");
            props.put("status", status != null ? status : "running");
            if (args != null) props.put("args", args);
            if (result != null) props.put("result", result);
            comps.add(com.oilquiz.app.ai.chat.component.ComponentData.of("tool_call", props));
            // 在线 Agent 模式：工具卡片走组件通道（addToolCallMessage 被拦截），此处补记工具计数用于汇总
            agentGroupToolCount++;
            if (toolName != null) agentToolNames.add(toolName);
            msg.components = new java.util.ArrayList<>(comps);
            if (chatAdapter != null) {
                chatAdapter.notifyItemChanged(msgIndex, ChatAdapter.PAYLOAD_CONTENT_UPDATE);
            }
            // 新工具卡片（running）也立即落盘，刷新不丢
            saveHistoryAsync();
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
        else showToast(getString(R.string.h_3f036f4f));
    }

    /** 显示附件选项对话框 */
    private void showAttachmentOptionsDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.h_a9c3646a))
            .setItems(new String[]{getString(R.string.h_bbd24cc2), getString(R.string.h_7cfb7c97), getString(R.string.h_e5417e44), getString(R.string.h_0fdd25cd)}, (dialog, which) -> {
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
            .setNegativeButton(getString(R.string.h_625fb26b), null)
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
                    showToast(getString(R.string.h_3f8b8981));
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
                showToast(getString(R.string.h_c4eb53c8));
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
                showToast(getString(R.string.h_b09e35b4));
            }
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error taking photo: " + e.getMessage());
            showToast(getString(R.string.h_bcde7221) + e.getMessage());
        }
    }

    /** 长按消息弹出语音操作菜单 */
    private void showMessageSpeechOptions(ChatMessage message) {
        if (message == null) return;
        String content = message.getContent();
        boolean canSpeak = message.isAIMessage() && content != null && !content.trim().isEmpty();
        if (!canSpeak) return;

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.h_87b4d00f))
            .setItems(new String[]{getString(R.string.h_5511e113), getString(R.string.h_c79f16c4)}, (dialog, which) -> {
                if (which == 0) {
                    speakMessage(message);
                } else {
                    com.oilquiz.app.ai.speech.SpeechManager.getInstance(this).stopSpeaking();
                    showToast(getString(R.string.h_a660a638));
                }
            })
            .setNegativeButton(getString(R.string.h_625fb26b), null)
            .show();
    }

    /** 使用 TTS 朗读 AI 消息（优先在线模型，未配置时自动回退系统TTS） */
    private void speakMessage(ChatMessage message) {
        speakMessage(message, message != null ? message.id : null);
    }

    private void speakMessage(ChatMessage message, final String messageId) {
        String text = toSpeakableText(message.getContent());
        if (text.isEmpty()) {
            showToast(getString(R.string.h_6d66ba3b));
            return;
        }
        if (text.length() > 2000) {
            text = text.substring(0, 2000);
            showToast(getString(R.string.h_4bc876d2));
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
            showToast(getString(R.string.h_f3df22c9));
        }
        com.oilquiz.app.ai.speech.SpeechManager.getInstance(this).speakLocked(text,
                new com.oilquiz.app.ai.speech.TTSService.PlaybackCallback() {
            @Override
            public void onStart() {
                if (!silent) {
                    runOnUiThread(() -> showToast(getString(R.string.h_c5d49541)));
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
                        showToast(getString(R.string.h_f9154462));
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
            showToast(getString(R.string.h_715ae415));
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

    /**
     * 语音输入（语音识别工具模式）：点击麦克风按钮 → 弹出语音识别组件
     * （录音对话框，与 agent 的 voice_input 工具同款组件）→ 用户说完点"完成" →
     * 按 voice_input 的模型选择逻辑识别（用户显式选本地 SenseVoice 则本地优先）→
     * 文字填入输入框待确认。替代旧的"按住说话/模式切换"那套 UI。
     */
    private void startVoiceRecognitionTool() {
        com.oilquiz.app.resource.PermissionResourceProvider provider =
                com.oilquiz.app.resource.PermissionResourceProvider.getInstance(this);
        provider.requestMicrophonePermission(this, new com.oilquiz.app.resource.PermissionResourceProvider.PermissionCallback() {
            @Override
            public void onGranted() {
                doVoiceRecognitionTool();
            }

            @Override
            public void onDenied(java.util.List<String> deniedPermissions) {
                showToast(getString(R.string.h_b6cf53e9));
                setVoiceButtonEnabled(false);
            }
        });
    }

    /** 执行语音识别工具流程：本地模型未就绪时先预热加载（有加载提示），就绪后再调用 voice_input 工具 */
    private void doVoiceRecognitionTool() {
        final com.oilquiz.app.ai.speech.SpeechManager speech =
                com.oilquiz.app.ai.speech.SpeechManager.getInstance(this);
        // 在线/本地任一可用即可识别（与 VoiceInputTool 一致）
        if (!speech.isAnyAsrAvailable()) {
            showToast(getString(R.string.h_b2f5500b));
            setVoiceButtonEnabled(false);
            return;
        }
        // 预判识别路径（用于本地模型加载提示）：用户显式选本地 SenseVoice 或在线不可用 → 本地
        final boolean useLocal = isLocalAsrSelected() || !speech.isAsrAvailable();
        if (useLocal && !com.oilquiz.app.ai.speech.asr.SenseVoiceAsr.isReady()) {
            // 本地模型按需加载（首次加载约数秒）：提示 + 后台预热，加载完成后再弹录音组件
            showToast(getString(R.string.h_a2b3c4d5));
            new Thread(() -> {
                try {
                    com.oilquiz.app.ai.speech.asr.SenseVoiceAsr.acquire(this);
                    com.oilquiz.app.ai.speech.asr.SenseVoiceAsr.release(); // 模型保留（TTL 缓存）
                    runOnUiThread(() -> {
                        showToast(getString(R.string.h_e6f7a8b9));
                        startVoiceRecorderTool();
                    });
                } catch (Exception e) {
                    AppLogger.aiE(TAG, "本地语音识别模型预热失败: " + e.getMessage());
                    runOnUiThread(() -> showToast("本地语音识别模型加载失败: " + e.getMessage()));
                }
            }).start();
        } else {
            startVoiceRecorderTool();
        }
    }

    /** 调用 agent 语音识别工具 voice_input 的 record 动作（后台线程：录音组件→识别→填入输入框） */
    private void startVoiceRecorderTool() {
        new Thread(() -> {
            try {
                // 直接调用 agent 语音识别工具（voice_input）的 record 动作：
                // 工具内部完成 权限检查→录音组件(录音对话框)→用户点完成→模型选择(preferLocal)→识别→返回文本
                java.util.Map<String, Object> params = new HashMap<>();
                params.put("action", "record");
                params.put("duration_seconds", 60);   // 最长录音 60 秒
                params.put("timeout_seconds", 90);    // 等待用户操作上限 90 秒
                com.oilquiz.app.ai.tool.VoiceInputTool tool =
                        new com.oilquiz.app.ai.tool.VoiceInputTool(this);
                com.oilquiz.app.ai.tool.AIToolResult r = tool.execute(params);
                if (r != null && r.isSuccess()) {
                    final String text = r.getAdditionalInfo() != null
                            ? String.valueOf(r.getAdditionalInfo().get("text")) : "";
                    runOnUiThread(() -> previewRecognizedText(text));
                } else {
                    final String err = (r != null && r.getErrorMessage() != null)
                            ? r.getErrorMessage() : "语音识别失败";
                    runOnUiThread(() -> showToast(err));
                }
            } catch (Exception e) {
                AppLogger.aiE(TAG, "语音识别工具失败: " + e.getMessage());
                runOnUiThread(() -> showToast("语音识别失败: " + e.getMessage()));
            }
        }).start();
    }

    /** 用户是否在"功能专用模型"中显式选择了本地 SenseVoice（→ 识别本地优先），与 VoiceInputTool 一致 */
    private boolean isLocalAsrSelected() {
        try {
            return com.oilquiz.app.ai.speech.SpeechManager.LOCAL_ASR_ID.equals(
                    com.oilquiz.app.ai.model.OnlineModelManager.getInstance(this)
                            .getFeatureModelId(com.oilquiz.app.ai.model.OnlineModelManager.FEATURE_ASR));
        } catch (Exception e) {
            return false;
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
            showToast(getString(R.string.h_33978d22));
        } else {
            lastAutoSpokenMessageId = null;
            // 关闭开关：停止当前朗读并清空待播队列（保留 streamTtsFed，避免完成时重复整条朗读）
            if (streamingTtsSpeaker != null) {
                streamingTtsSpeaker.reset();
            }
            com.oilquiz.app.ai.speech.SpeechManager.getInstance(this).stopSpeaking();
            showToast(getString(R.string.h_220ccbd9));
        }
    }

    /** 更新自动语音合成按钮样式（开启时高亮） */
    private void updateAutoTtsButtonUI() {
        if (btnAutoTts == null) return;
        btnAutoTts.setText(autoTtsEnabled ? "🔊" : "🔇");
        btnAutoTts.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ThemeColors.get(this, autoTtsEnabled ? R.color.primary_container : R.color.surface_variant)));
        btnAutoTts.setTextColor(ThemeColors.get(this, autoTtsEnabled ? R.color.on_primary_container : R.color.text_secondary));
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
     * - 权限已授予：在线ASR 或 本地/系统离线识别可用才启用，否则禁用（不弹引导对话框）
     */
    private void updateVoiceButtonAvailability() {
        if (btnVoice == null) return;
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


    /**
     * 自动预览：识别结果填入输入框并自动切回键盘模式，让用户看到结果、可修改后再点发送。
     * 避免"识别错误直接发出"的盲盒体验；语音模式下输入框隐藏，故识别完成必须切回键盘模式展示。
     */
    private void previewRecognizedText(String text) {
        if (text == null || text.trim().isEmpty()) {
            showToast(getString(R.string.h_4b5fe010));
            return;
        }
        final String clean = text.trim();
        // 标记本次识别预览的消息来源为语音，发送时在 USER 消息上打语音标识
        pendingVoiceInputSource = true;
        runOnUiThread(() -> {
            if (inputMessage != null) {
                inputMessage.setText(clean);
                inputMessage.requestFocus();
                inputMessage.setSelection(inputMessage.getText().length());
            } else if (inputManager != null) {
                inputManager.appendText(clean);
            }
            showToast(getString(R.string.h_a3d12e2b));
        });
    }

    /** 语音模型设置：配置语音识别/语音合成专用模型 */
    private void handleSpeechModelConfig() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.h_eddfcc8d))
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
            .setNegativeButton(getString(R.string.h_625fb26b), null)
            .show();
    }

    /** 处理相机拍摄的照片 */
    private void handleCameraPhoto(Uri photoUri) {
        try {
            // 复制照片到应用缓存目录，得到真实文件路径（FileProvider 的 content:// URI 直接使用会失效）
            String localPath = copyUriToCacheFile(photoUri);
            if (localPath == null) {
                showToast(getString(R.string.h_2a33a288));
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

            // 统一走 inputManager 添加（sendMessage 从 inputManager 取附件）
            if (inputManager != null) {
                inputManager.addAttachment(attachment);
            } else {
                currentAttachments.add(attachment);
            }
            resetAttachmentAdapter();
            showToast(getString(R.string.h_a2f2a147));

        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error handling camera photo: " + e.getMessage());
            showToast(getString(R.string.h_76cf127f) + e.getMessage());
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
                    showToast(getString(R.string.h_997a1a2c));
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
                showToast(getString(R.string.h_24f55c59));
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
            showToast(getString(R.string.h_c3f0e02f));
            
            // 更新按钮状态（可选：显示录音中提示）
            updateRecordingUI(true);
            
        } catch (Exception e) {
            e.printStackTrace();
            showToast(getString(R.string.h_8339b334) + e.getMessage());
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
                // 统一走 inputManager 添加
                if (inputManager != null) {
                    inputManager.addAttachment(attachment);
                } else {
                    currentAttachments.add(attachment);
                }
                
                // 刷新附件列表显示
                refreshAttachmentsUI();
                
                showToast(getString(R.string.h_e2a65c56));
            } else {
                showToast(getString(R.string.h_ba8cd317));
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            showToast(getString(R.string.h_61a16521) + e.getMessage());
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
            .setTitle(getString(R.string.h_de440817))
            .setMessage(getString(R.string.h_372868fa))
            .setPositiveButton(getString(R.string.h_24114160), (dialog, which) -> {
                // 打开应用设置页面
                android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            })
            .setNegativeButton(getString(R.string.h_625fb26b), null)
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
                showToast(getString(R.string.h_b1c1d48c));
            } else {
                showToast(getString(R.string.h_f4854afd));
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
                    showToast(getString(R.string.h_71ba40c1));
            }
        } catch (Exception e) {
            AILogger.e("[AIChat]", "启动选择器失败", e);
            showToast(getString(R.string.h_fcc5458b));
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
            showToast(getString(R.string.h_53437401));
            showManualPathInput(step, valueView, pickerBtn);
            return;
        }

        if (path == null || path.isEmpty()) {
            showToast(getString(R.string.h_7c1a5043));
            showManualPathInput(step, valueView, pickerBtn);
            return;
        }
        // 写回步骤 + 刷新显示
        step.paramValue = path;
        if (valueView != null) {
            runOnUiThread(() -> valueView.setText(path));
        }
        if (pickerBtn != null) {
            runOnUiThread(() -> pickerBtn.setText(getString(R.string.h_ee1de131)));
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

        String title = step.type == ToolGuideFlow.GuideStep.StepType.IMAGE_PICKER ? getString(R.string.h_e1e280eb)
                : step.type == ToolGuideFlow.GuideStep.StepType.DIRECTORY_PICKER ? "手动输入目录路径"
                : "手动输入文件路径";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(step.description)
                .setView(et)
                .setNegativeButton(getString(R.string.h_625fb26b), null)
                .setPositiveButton(getString(R.string.h_38cf16f2), (d, w) -> {
                    String v = et.getText() == null ? "" : et.getText().toString().trim();
                    if (v.isEmpty() && step.required) {
                        showToast(getString(R.string.h_60aa379b));
                        return;
                    }
                    step.paramValue = v;
                    if (valueView != null) valueView.setText(v);
                    if (pickerBtn != null) {
                        pickerBtn.setText(v.isEmpty() ? getString(R.string.h_e3cf912f) : getString(R.string.h_ee1de131));
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
        showToast(getString(R.string.h_24c5bd08) + uris.size() + getString(R.string.h_e74f5dd7));
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
        if (!hasImageAttachment && !isAIReady()) { showToast(getString(R.string.h_efd0d08e)); return; }

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

/**
     * 刷新聊天页顶部的「电脑连接」状态条。
     *
     * <p>为什么要常驻这一条：连接状态以前只在 AI 的文字回复里出现，聊天界面上没有任何提示
     * （用户反馈 2026-09-27："AI 对话界面没有任何 UI 提示，只能在对话流中显示"）。
     * 三种状态：未配对（红）/ 已连接（绿）/ 已断开（黄），点一下进「远程连接（电脑）」。
     */
    private void refreshRemoteDshBar() {
        if (remoteDshBar == null) {
            return;
        }
        // 用户可关（✕ 或「远程连接（电脑）」里的开关）：关了就不再常驻显示
        if (!com.oilquiz.app.ai.tool.RemoteDshTool.isBarEnabled(this)) {
            remoteDshBar.setVisibility(View.GONE);
            return;
        }
        boolean configured = com.oilquiz.app.ai.tool.RemoteDshTool.isConfigured(this);
        boolean connected = com.oilquiz.app.ai.tool.RemoteDshTool.isConnected(this);
        int dotRes;
        String text;
        String action;
        if (!configured) {
            dotRes = R.drawable.circle_red;
            text = "电脑未配对：可远程控制电脑（扫码即可）";
            action = "去配对 ›";
        } else if (connected) {
            dotRes = R.drawable.circle_green;
            text = "电脑已连接：" + com.oilquiz.app.ai.tool.RemoteDshTool.configValue(this, "base_url")
                    + "（AI 可远程操作）";
            action = "管理 ›";
        } else {
            dotRes = R.drawable.circle_yellow;
            text = "电脑已断开：AI 会被拒绝执行";
            action = "去连接 ›";
        }
        remoteDshDot.setBackground(androidx.core.content.ContextCompat.getDrawable(this, dotRes));
        remoteDshText.setText(text);
        remoteDshAction.setText(action);
        remoteDshBar.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // NPU-PRELOAD: 方案 B —— 只在聊天页后台预加载 NPU 模型（不进启动路径，
        // 避免原生层 abort 演变成"打开就秒退"；用户打字的时间用来加载）。
        preloadNpuIfNeeded();
        // 电脑连接状态可能在「远程连接（电脑）」界面被改过：回来自动刷新顶部状态条
        refreshRemoteDshBar();
        // 同步本地Agent开关状态（可能在其他页面切换过）
        if (chipLocalAgent != null && aiConfig != null) {
            chipLocalAgent.setChecked(aiConfig.isLocalAgentEnabled());
            updateAgentChip(chipLocalAgent);
        }
        updateModeButtonText();
        updateApiBalanceDisplay();
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

        // 再次进入页面时定位到最新消息。
        // 延迟执行：确保列表完成 layout（历史可能刚异步加载完成），直接 scrollToPosition 定位到底部
        messageList.postDelayed(() -> scrollToBottom(true), 80);

        // 启动状态条轮询：思考段（THINKING）不产出正文 token，只能靠定时刷新拿到
        startStatePolling();
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
            final int idx = resolveStreamingIndex();
            if (idx < 0 || chatHistory == null || currentStreamingContent == null) return;
            ChatMessage msg = chatHistory.get(idx);
            msg.content = currentStreamingContent.toString();
            msg.status = ChatMessage.MessageStatus.GENERATING;
            if (chatAdapter != null) chatAdapter.updateAIMessageContent(idx, currentStreamingContent.toString());
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
            final int idx = resolveStreamingIndex();
            if (error != null) {
                if (currentStreamingContent != null && currentStreamingContent.length() > 0 && idx >= 0) {
                    ChatMessage msg = chatHistory.get(idx);
                    msg.content = currentStreamingContent.toString();
                    msg.status = ChatMessage.MessageStatus.COMPLETED;
                    if (chatAdapter != null) chatAdapter.notifyItemChanged(idx);
                    saveHistoryAsync(); addSystemMessage("生成中断: " + error);
                } else if (idx >= 0 && currentStreamingMessageId != null) {
                    // 批量修改 + 一次批量通知，避免 insert+remove 混合 op 触发 Inconsistency
                    chatHistory.remove(idx);
                    if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                    addSystemMessage(error);
                }
            } else if (result != null) {
                if (currentStreamingContent != null && idx >= 0) {
                    ChatMessage msg = chatHistory.get(idx);
                    msg.content = result;
                    msg.status = ChatMessage.MessageStatus.COMPLETED;
                    if (chatAdapter != null) chatAdapter.notifyItemChanged(idx);
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
        stopStatePolling();
        try {
            // 释放 TTS 播放资源
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

            // 取消所有生成任务 —— 不再取消！AI 对话界面只是显示/操作界面，
            // 退出/重建不应中断模型生成；生成继续跑并落盘，回调仅跳过界面更新（uiDetached）
            //（agentChatHandler.cancel() 与 modelBridge.stopGeneration() 已移除）

            // 清理附件管理器
            if (attachmentManager != null) {
                attachmentManager.clearAllAttachments();
            }

            // 会话关闭：清理残留的动态 UI 组件 + 临时组件插件（防 dialog 卡界面/临时插件残留）
            try {
                com.oilquiz.app.ai.python.PythonToolManager.getInstance(this)
                        .closeAllUiComponents();
            } catch (Throwable ignored) {
            }

            unregisterAIStatusObserver();
            // 注销在线模型变更监听
            unregisterModelChangeListener();
            unregisterComponentCallbacks(memoryCallback);
            TokenStatsManager.getInstance().unregisterCallback(tokenStatsCallback);
            if (localBroadcastManager != null && aiResultReceiver != null) { try { localBroadcastManager.unregisterReceiver(aiResultReceiver); } catch (Exception e) {} }
            if (localBroadcastManager != null && aiTokenReceiver != null) { try { localBroadcastManager.unregisterReceiver(aiTokenReceiver); } catch (Exception e) {} }
            // 不再执行 stopGeneration：生成在桥/服务层继续（界面退出不中断模型工作）
            historyPollHandler.removeCallbacksAndMessages(null);
            // 标记 UI 已分离：此后生成回调只落盘、不再更新界面
            uiDetached = true;
            uiHandler.removeCallbacksAndMessages(null);
            isGenerating = false; isDirectStreaming = false;
        } catch (Exception e) { AppLogger.aiE(TAG, "Error onDestroy: " + e.getMessage()); }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 停止时保存当前会话到历史（更新当前会话而非重复创建副本；与 saveHistoryAsync 互斥）
        if (chatHistoryManager != null && chatHistory != null && !chatHistory.isEmpty()) {
            final List<ChatMessage> copy = new ArrayList<>(chatHistory);
            new Thread(() -> {
                synchronized (HISTORY_IO_LOCK) {
                    chatHistoryManager.saveCurrentChatAsSession(copy, currentSessionId);
                }
            }).start();
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

        // 更新 Adapter 中的统计（用 resolveStreamingIndex：id 为空/索引漂移时自动跳过，防 notify 错消息）
        int idx = resolveStreamingIndex();
        if (idx >= 0 && chatAdapter != null) {
            chatAdapter.updateMessageGenerationStats(idx, stats.totalTokens, stats.elapsedMs);
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Agent 发起的系统选择器（file_picker/image_picker/contact_picker）结果转发给组件系统
        try {
            com.oilquiz.app.ai.python.PythonToolManager.getInstance(this)
                    .onAgentPickerResult(requestCode, resultCode, data);
        } catch (Throwable t) {
            android.util.Log.w("AIChatActivity", "Agent picker result dispatch failed: " + t.getMessage());
        }
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
