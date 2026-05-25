package com.oilquiz.app.ui.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import com.google.android.material.button.MaterialButton;

import com.oilquiz.app.util.AILogger;
import com.oilquiz.app.util.QWeatherIconMapper;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.oilquiz.app.R;
import com.oilquiz.app.ui.activity.QuestionGenerateActivity;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AIServiceState;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.ai.chat.AgentChatHandler;
import com.oilquiz.app.ai.chat.StreamingUpdateManager;
import com.oilquiz.app.ai.service.AIProcessingService;
import com.oilquiz.app.ai.tool.AIToolsManager;
import com.oilquiz.app.ai.tool.AIEntertainmentManager;
import com.oilquiz.app.ai.tool.AIWeatherManager;
import com.oilquiz.app.ai.tool.LocationTool;
import com.oilquiz.app.ai.util.ChatHistoryManager;
import com.oilquiz.app.ai.util.AttachmentManager;
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
import com.oilquiz.app.ui.base.BaseActivity;

import androidx.drawerlayout.widget.DrawerLayout;
import androidx.activity.result.ActivityResultLauncher;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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
    private Chip chipSummary;
    private Chip chipTranslate;
    private Chip chipCodeExplain;
    private Chip chipOptimize;
    private Chip chipWeather;
    private Chip chipClear;

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
    private boolean weatherBannerVisible = false;
    private String weatherBannerCity = "";
    private double weatherBannerLat = 0;
    private double weatherBannerLon = 0;

    private AIService aiService;
    private InferenceRouter inferenceRouter;
    private List<ChatMessage> chatHistory;
    private ChatAdapter chatAdapter;
    private ChatHistoryManager chatHistoryManager;
    private AttachmentManager attachmentManager;
    private ChatHistoryAdapter chatHistoryAdapter;
    private AttachmentAdapter attachmentAdapter;
    private FileContentExtractor fileContentExtractor;
    private AIToolsManager aiToolsManager;
    private AIEntertainmentManager aiEntertainmentManager;
    private AgentService agentService;
    private AgentChatHandler agentChatHandler;
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
    private volatile Boolean lastUseOnlineModel = null;
    private volatile boolean isInTag = false;
    private volatile StringBuilder tagBuffer = null;
    private volatile int currentStreamingMessageIndex = -1;
    private volatile String currentStreamingMessageId = null;
    private volatile int agentToolLoopCount = 0;
    private final Object streamingLock = new Object();
    
    // Native 状态恢复相关
    private volatile boolean isRecovering = false;
    private volatile String pendingMessageForRecovery = null;
    private volatile int recoveryProgressUpdateCount = 0;
    private static final int MAX_RECOVERY_FAILURES_NOTIFY = 3;

    private int tokenCountSinceLastUpdate = 0;
    private long lastUpdateTime = 0;
    private boolean isUpdateScheduled = false;
    private android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private StreamingUpdateManager streamingUpdateManager = null;
    private long totalTokensGenerated = 0;
    private long generationStartTime = 0;
    private boolean isLoadingModel = false;
    private int loadingProgressMessageIndex = -1;
    private AIService.DetailedStatusObserver aiStatusObserver = null;
    private Runnable loadingTimerRunnable = null;
    private static final long LOADING_TIMER_INTERVAL_MS = 500;
    private ActivityResultLauncher<String[]> attachFileLauncher;
    private List<Uri> attachedFiles = new ArrayList<>();
    private List<ChatMessage.Attachment> currentAttachments = new ArrayList<>();

    private final android.content.ComponentCallbacks2 memoryCallback = new android.content.ComponentCallbacks2() {
        @Override
        public void onTrimMemory(int level) {
            if (aiService != null) {
                int result = LlamaHelper.handleMemoryPressure(level);
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
                LlamaHelper.handleMemoryPressure(80);
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
            chipSummary = findViewById(R.id.chip_explain_concept);
            chipTranslate = findViewById(R.id.chip_translate);
            chipCodeExplain = findViewById(R.id.chip_summarize);
            chipOptimize = findViewById(R.id.chip_code_review);
            chipWeather = findViewById(R.id.chip_rewrite);
            chipClear = findViewById(R.id.chip_weather);

            // 快捷工具栏相关视图
            View quickBarHeader = findViewById(R.id.quick_bar_header);
            ChipGroup quickActionsChipGroup = findViewById(R.id.quick_actions_chip_group);
            ImageView ivQuickExpand = findViewById(R.id.iv_quick_expand);

            // 快捷工具栏折叠/展开功能
            final boolean[] isExpanded = {false};
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

            initAttachmentList();
            initHistoryList();

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
            aiService = AIService.getInstance(this);
            inferenceRouter = InferenceRouter.getInstance(this);
            if (aiService == null) {
                showToast("AI服务初始化失败");
                return;
            }

            registerAIStatusObserver();

            chatHistoryManager = new ChatHistoryManager(this);
            attachmentManager = new AttachmentManager(this);
            fileContentExtractor = new FileContentExtractor(this);
            initAttachFileLauncher();

            aiToolsManager = new AIToolsManager(this);
            aiConfig = new AIConfig(this);

            if (aiConfig.isAgentEnabled()) {
                agentService = AgentService.getInstance(this);
                initAgentChatHandler();
            }

            cacheManager = new CacheManager(this);
            onlineModelManager = OnlineModelManager.getInstance(this);
            
            // 尝试从APIKeyManager导入在线模型配置
            try {
                int imported = onlineModelManager.importFromAPIKeyManager();
                if (imported > 0) {
                    AppLogger.ai(TAG, "从APIKeyManager导入了" + imported + "个在线模型配置");
                }
            } catch (Exception e) {
                AppLogger.aiW(TAG, "导入在线模型配置失败: " + e.getMessage());
            }
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

            List<ChatMessage> loadedHistory = chatHistoryManager.loadAIChatHistory();
            if (loadedHistory != null && !loadedHistory.isEmpty()) {
                chatHistory.addAll(loadedHistory);
                if (chatAdapter != null) chatAdapter.notifyItemRangeInserted(0, loadedHistory.size());
            }

            updateModelNameDisplay();
            refreshHistoryAdapter();
            updateInitialServiceStatus();
            
            // 设置 Native 状态恢复监听器
            setupNativeStateRecoveryListener();

            if (chatHistory.isEmpty()) {
                addSystemMessage("欢迎使用AI对话功能！请输入您的问题，我会尽力回答。\n输入 '帮助' 查看更多功能。");
            } else {
                addSystemMessage("欢迎回来！继续我们的对话吧。");
            }

            if (LocationTool.hasLocationPermission(this)) {
                loadWeatherBanner();
            }
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error initializing data: " + e.getMessage());
            showToast("数据初始化失败: " + e.getMessage());
        }
    }

    @Override
    protected void initListener() {
        btnBack.setOnClickListener(v -> finish());
        btnModeSelect.setOnClickListener(v -> showModeSelectorDialog());
        if (btnModelSelect != null) {
            btnModelSelect.setOnClickListener(v -> {
                // 打开模型选择页面
                Intent intent = new Intent(AIChatActivity.this, ModelSelectorActivity.class);
                startActivity(intent);
            });
        }
        btnClearChat.setOnClickListener(v -> clearChat());
        btnStopGeneration.setOnClickListener(v -> stopGeneration());
        btnSend.setOnClickListener(v -> sendMessage());
        btnAttach.setOnClickListener(v -> handleAttachFile());

        if (btnHistory != null) {
            btnHistory.setOnClickListener(v -> {
                if (drawerLayout != null) {
                    refreshHistoryAdapter();
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
            btnClearAllHistory.setOnClickListener(v -> { clearChat(); refreshHistoryAdapter(); drawerLayout.closeDrawer(findViewById(R.id.history_drawer)); showToast("已清空"); });
        }

        if (chipSummary != null) chipSummary.setOnClickListener(v -> {
            // 解释
            inputMessage.setText("请帮我解释这个概念：");
            inputMessage.setSelection(inputMessage.getText().length());
        });
        if (chipTranslate != null) chipTranslate.setOnClickListener(v -> {
            // 翻译
            inputMessage.setText("请帮我翻译成中文：");
            inputMessage.setSelection(inputMessage.getText().length());
        });
        if (chipCodeExplain != null) chipCodeExplain.setOnClickListener(v -> {
            // 总结
            inputMessage.setText("请帮我总结这段内容的要点：");
            inputMessage.setSelection(inputMessage.getText().length());
        });
        if (chipOptimize != null) chipOptimize.setOnClickListener(v -> {
            // 代码
            inputMessage.setText("请帮我检查这段代码：");
            inputMessage.setSelection(inputMessage.getText().length());
        });
        if (chipWeather != null) chipWeather.setOnClickListener(v -> {
            // 改写
            inputMessage.setText("请帮我改写这段文字，使其更简洁清晰：");
            inputMessage.setSelection(inputMessage.getText().length());
        });
        if (chipClear != null) chipClear.setOnClickListener(v -> {
            // 天气
            handleQuickAction("天气");
        });
        
        // 模式切换快捷按钮
        Chip chipDeepThink = findViewById(R.id.chip_deep_think);
        Chip chipCreative = findViewById(R.id.chip_creative);
        
        if (chipDeepThink != null) chipDeepThink.setOnClickListener(v -> {
            ChatModeManager.getInstance(this).setManualMode(ChatModeManager.ChatMode.DEEP_THINKING);
            updateModeButtonText();
            showToast("已切换到深度思考模式");
        });
        
        if (chipCreative != null) chipCreative.setOnClickListener(v -> {
            ChatModeManager.getInstance(this).setManualMode(ChatModeManager.ChatMode.CREATIVE);
            updateModeButtonText();
            showToast("已切换到创意写作模式");
        });

        // 空状态快捷操作
        if (emptyStateChips != null) {
            com.google.android.material.chip.Chip chipExample1 = emptyStateChips.findViewById(R.id.chip_empty_example1);
            com.google.android.material.chip.Chip chipExample2 = emptyStateChips.findViewById(R.id.chip_empty_example2);
            com.google.android.material.chip.Chip chipExample3 = emptyStateChips.findViewById(R.id.chip_empty_example3);
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
        }

        if (btnWeatherRefresh != null) btnWeatherRefresh.setOnClickListener(v -> refreshWeatherBanner());
        if (btnWeatherClose != null) btnWeatherClose.setOnClickListener(v -> { weatherBannerVisible = false; if (weatherBanner != null) weatherBanner.setVisibility(View.GONE); });
        if (weatherBanner != null) {
            weatherBanner.setOnClickListener(v -> {
                Intent intent = new Intent(AIChatActivity.this, WeatherDetailActivity.class);
                intent.putExtra("city", weatherBannerCity);
                if (weatherBannerLat != 0 && weatherBannerLon != 0) { intent.putExtra("lat", weatherBannerLat); intent.putExtra("lon", weatherBannerLon); }
                startActivity(intent);
            });
        }

        inputMessage.setOnEditorActionListener((v, actionId, event) -> { sendMessage(); return true; });
        
        // 长按输入框显示更多选项
        inputMessage.setOnLongClickListener(v -> {
            showInputOptions();
            return true;
        });
    }

    private void cancelGeneration() {
        try {
            if (agentChatHandler != null && agentChatHandler.isGenerating()) agentChatHandler.cancel();
            if (aiService != null) aiService.chatStop();
            isGenerating = false;
            isDirectStreaming = false;
            hideLoadingUI();
            showToast("操作已取消");
            addSystemMessage("生成已取消");
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error cancelling: " + e.getMessage());
        }
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
            scrollToBottom();
            saveHistoryAsync();
            currentAttachments.clear();
            resetAttachmentAdapter();
        } else {
            addUserMessage(message);
        }

        inputMessage.setText("");

        if (message.equalsIgnoreCase("帮助") || message.equalsIgnoreCase("help")) {
            handleHelpCommand(); return;
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
                weatherBannerVisible = true;
            }
            
            // 如果参数为空且有定位权限，加载当前天气横幅
            if (params.isEmpty() && LocationTool.hasLocationPermission(this)) {
                loadWeatherBanner();
                return;
            }
            
            // 使用标准的工具调用方式
            executeTool(AIToolsManager.Tool.GET_WEATHER, params);
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
        if ("翻译".equals(prefix)) toolName = AIToolsManager.Tool.TRANSLATE_TEXT;
        else if ("生成题目".equals(prefix)) toolName = AIToolsManager.Tool.GENERATE_QUESTIONS;
        else if ("分析题目".equals(prefix)) toolName = AIToolsManager.Tool.ANALYZE_QUESTION;
        else if ("学习计划".equals(prefix)) toolName = AIToolsManager.Tool.CREATE_STUDY_PLAN;
        else if ("统计".equals(prefix)) toolName = AIToolsManager.Tool.GET_STATISTICS;
        else if ("搜索题目".equals(prefix)) toolName = AIToolsManager.Tool.SEARCH_QUESTIONS;
        else if ("导入题目".equals(prefix)) toolName = AIToolsManager.Tool.IMPORT_QUESTIONS;
        else if ("导出题目".equals(prefix)) toolName = AIToolsManager.Tool.EXPORT_QUESTIONS;
        else if ("数据库操作".equals(prefix)) toolName = AIToolsManager.Tool.DATABASE_OPERATIONS;
        else if ("定位".equals(prefix) || "我的位置".equals(prefix) || "当前位置".equals(prefix)) toolName = AIToolsManager.Tool.GET_WEATHER;

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
            // 检查是否应该使用在线模型
            if (shouldUseOnlineModel()) {
                processChatMessageWithOnlineModel(message);
                return;
            }
            
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
            if (!LlamaHelper.isNativeStateValid()) {
                AppLogger.aiW(TAG, "Native state invalid, triggering auto-recovery");
                addSystemMessage("⚠️ 检测到AI模型状态异常，正在自动恢复...", ChatMessage.SystemMessageType.WARNING);
                triggerAutoRecovery(message);
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

            new Thread(() -> {
                try {
                    runOnUiThread(() -> updateInferencePhase(streamingIndex, ChatMessage.InferencePhase.INITIALIZING, null));
                    
                    if (!aiService.isInitialized()) {
                        if (!aiService.initializeSafe()) { handleGenerationError("AI服务初始化失败"); return; }
                    }

                    {
                        runOnUiThread(() -> updateInferencePhase(streamingIndex, ChatMessage.InferencePhase.ENCODING, "正在编码输入..."));
                        long chatStartTime = System.currentTimeMillis();
                        int actualMaxTokens = aiConfig.getMaxTokens();
                        AppLogger.ai(TAG, "Calling aiService.chatSend: promptLen=" + prompt.length() + ", maxTokens=" + actualMaxTokens);
                        
                        aiService.chatSend(prompt, actualMaxTokens, false, new StreamingTokenHandler(streamingIndex, streamingId, prompt, chatStartTime));
                    }
                } catch (Exception e) {
                    AppLogger.aiE(TAG, "Error in chat: " + e.getMessage());
                    runOnUiThread(() -> handleGenerationError("发送消息失败: " + e.getMessage()));
                }
            }).start();
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error in processChatMessage: " + e.getMessage());
            endGeneration();
            addSystemMessage("处理消息时出错: " + e.getMessage());
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
                            runOnUiThread(() -> {
                                if (chatAdapter != null && currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                                    ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                                    if (currentStreamingContent != null) {
                                        msg.content = currentStreamingContent.toString();
                                    }
                                    chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
                                    scrollToBottom();
                                }
                            });
                        }

                        @Override
                        public void onComplete(String fullText) {
                            runOnUiThread(() -> {
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
                }
            }
            currentStreamingContent = null;
            currentStreamingMessageIndex = -1;
            currentStreamingMessageId = null;
        });
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
                boolean nativeInvalid = !LlamaHelper.isNativeStateValid();
                boolean shouldRecover = nativeInvalid && !isRecovering && !isLoadingModel;
                
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
                    triggerAutoRecovery(null);
                } else {
                    addErrorMessage("生成出错", error, true);
                }
            });
        }
    }

    private void handleStreamToken(String token) {
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
            if (currentStreamingContent != null) currentStreamingContent.append("\n🔧 " + toolName);
            updateAndScrollUI();
        }

        @Override
        public void onToolCallComplete(String toolName, AgentService.ToolResult result) {
            if (currentStreamingContent != null) {
                // 保护：result可能为null
                boolean success = result != null && result.success;
                currentStreamingContent.append("\n" + (success ? "✅" : "❌"));
            }
            updateAndScrollUI();
        }

        @Override
        public void onToken(String token) {
            if (currentStreamingContent != null) currentStreamingContent.append(token);
            updateAndScrollUI();
        }

        @Override
        public void onThinkingToken(String token) {
            if (currentThinkingContent != null) currentThinkingContent.append(token);
        }

        @Override
        public void onThinkingEnd() { isInThinking = false; }

        @Override
        public void onComplete(String fullText) { completeGeneration(fullText); }

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
            runOnUiThread(() -> addAgentStepMessage(stepInfo));
        }

        @Override
        public void onToolCallUI(String toolName, String args, int position) {
            runOnUiThread(() -> addToolCallMessage(toolName, args));
        }

        @Override
        public void onToolCallResultUI(int position, boolean success, String result) {
            runOnUiThread(() -> {
                int pos = findLastSpecialMessage(ChatMessage.MessageType.TOOL_CALL);
                updateToolCallResult(pos >= 0 ? pos : chatHistory.size() - 1, success, result);
            });
        }

        @Override
        public void onAgentStepUpdateUI(int position, String thought, String action, String observation, boolean isCompleted) {
            runOnUiThread(() -> {
                int pos = findLastSpecialMessage(ChatMessage.MessageType.AGENT_STEP);
                updateAgentStepResult(pos >= 0 ? pos : chatHistory.size() - 1, thought, action, observation, isCompleted);
            });
        }

        private void updateAndScrollUI() {
            runOnUiThread(() -> { safeUpdateMessage(); scrollToBottom(); });
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
        if (thinkingIndicator != null) thinkingIndicator.setVisibility(View.VISIBLE);

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

        // 隐藏 Token 统计
        showTokenStats(false);
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
        aiToolsManager.executeTool(toolName, parameters).thenAccept(result -> runOnUiThread(() -> {
            addAIMessage(result);
        })).exceptionally(throwable -> {
            runOnUiThread(() -> {
                addSystemMessage("工具执行出错: " + throwable.getMessage());
            });
            return null;
        });
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

    private void updateModelNameDisplay() {
        if (shouldUseOnlineModel()) {
            if (inferenceRouter != null) {
                String modelName = inferenceRouter.getCurrentModelName();
                modelNameText.setText("☁️ " + (modelName != null && !modelName.isEmpty() ? modelName : "在线模型"));
            } else {
                modelNameText.setText("☁️ 在线模型");
            }
        } else if (aiService != null) {
            String name = aiService.getCurrentModelName();
            modelNameText.setText("📱 " + (name != null && !name.isEmpty() ? name : "未选择模型"));
        } else {
            modelNameText.setText("AI服务未初始化");
        }
    }

    private void initAgentChatHandler() {
        if (aiConfig == null || !aiConfig.isAgentEnabled() || agentService == null) {
            return;
        }
        boolean useOnlineModel = inferenceRouter != null && inferenceRouter.isUsingOnlineModel();
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

    // ===================== Chat Actions =====================

    private void clearChat() {
        try {
            if (isGenerating) {
                if (agentChatHandler != null && agentChatHandler.isGenerating()) agentChatHandler.cancel();
                if (aiService != null) aiService.chatStop();
            }
            chatHistory.clear();
            if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
            if (chatHistoryManager != null) new Thread(() -> chatHistoryManager.clearAIChatHistory()).start();
            if (aiService != null) aiService.chatClear();
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
                String modeName = mode == ChatModeManager.ChatMode.NORMAL ? "普通模式" :
                                  mode == ChatModeManager.ChatMode.DEEP_THINKING ? "深度思考模式" : "创意写作模式";
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
     * Token 统计回调
     */
    private TokenStatsManager.TokenStatsCallback tokenStatsCallback = stats -> {
        runOnUiThread(() -> updateTokenStatsUI(stats));
    };
    
    /**
     * 更新 Token 统计 UI
     */
    private void updateTokenStatsUI(TokenStatsManager.TokenStats stats) {
        TextView tvTokenStats = findViewById(R.id.tv_token_stats);
        if (tvTokenStats != null && stats != null) {
            tvTokenStats.setVisibility(View.VISIBLE);
            // 显示当前请求的 token 统计（prompt + completion）
            tvTokenStats.setText("🔵 " + stats.requestTotalTokens + " tokens");
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
     */
    private void updateStreamingTokenStats(int totalTokens, float tokensPerSecond) {
        TextView tvTokenStats = findViewById(R.id.tv_token_stats);
        if (tvTokenStats != null) {
            tvTokenStats.setVisibility(View.VISIBLE);
            String statsText = String.format("🔵 %d tokens", totalTokens);
            if (tokensPerSecond > 0) {
                statsText += String.format(" (%.1f t/s)", tokensPerSecond);
            }
            tvTokenStats.setText(statsText);
        }
    }

    private void stopGeneration() {
        try {
            if (agentChatHandler != null && agentChatHandler.isGenerating()) agentChatHandler.cancel();
            if (aiService != null) aiService.chatStop();
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
        if (aiService != null) aiService.chatClear();
        processChatMessage(lastUserMsg);
    }

    private void clearStreamingState() {
        currentStreamingContent = null;
        currentThinkingContent = null;
        isInThinking = false;
        isInTag = false;
        if (tagBuffer != null) tagBuffer.setLength(0);
        currentStreamingMessageIndex = -1;
        currentStreamingMessageId = null;
        isGenerating = false;
        isDirectStreaming = false;
        hideLoadingUI();
    }

    private void hideLoadingUI() {
        if (btnStopGeneration != null) btnStopGeneration.setVisibility(View.GONE);
        if (thinkingIndicator != null) thinkingIndicator.setVisibility(View.GONE);
    }

    // ===================== Native 状态自动恢复 =====================

    private void setupNativeStateRecoveryListener() {
        if (aiService == null) return;
        aiService.setNativeStateRecoveryListener(new AIService.NativeStateRecoveryListener() {
            @Override
            public void onRecoveryStarted(int attemptCount) {
                runOnUiThread(() -> {
                    isRecovering = true;
                    recoveryProgressUpdateCount = 0;
                    lastRecoveryProgressShown = -1;
                    showLoading("正在恢复AI模型...", "第 " + attemptCount + " 次尝试");
                    addSystemMessage("🔄 正在重新加载模型（第 " + attemptCount + " 次）...", 
                        ChatMessage.SystemMessageType.WARNING);
                });
            }

            @Override
            public void onRecoverySuccess(long recoveryTimeMs) {
                runOnUiThread(() -> {
                    isRecovering = false;
                    hideLoading();
                    addSystemMessage("✅ 模型恢复成功，耗时 " + (recoveryTimeMs / 1000.0f) + " 秒", 
                        ChatMessage.SystemMessageType.SUCCESS);
                    showToast("模型已恢复");
                    
                    // 恢复成功，重新发送待处理消息
                    if (pendingMessageForRecovery != null) {
                        String msg = pendingMessageForRecovery;
                        pendingMessageForRecovery = null;
                        processChatMessage(msg);
                    }
                });
            }

            @Override
            public void onRecoveryFailed(int attemptCount, int maxAttempts, String reason) {
                runOnUiThread(() -> {
                    isRecovering = false;
                    hideLoading();
                    
                    if (attemptCount >= MAX_RECOVERY_FAILURES_NOTIFY) {
                        addErrorMessage(
                            "模型恢复失败", 
                            "已连续 " + attemptCount + " 次恢复失败，" + reason + "。\n建议：请尝试重新选择模型或重启应用。",
                            true
                        );
                        showToast("模型恢复失败，请重启应用");
                    } else {
                        addSystemMessage("❌ 模型恢复失败（第 " + attemptCount + " 次）: " + reason, 
                            ChatMessage.SystemMessageType.ERROR);
                        showToast("恢复失败: " + reason);
                    }
                    
                    pendingMessageForRecovery = null;
                });
            }
        });
    }

    private void triggerAutoRecovery(String pendingMessage) {
        if (isRecovering) {
            showToast("模型正在恢复中，请稍候");
            return;
        }
        
        if (aiService == null) {
            addSystemMessage("AI服务未初始化，无法恢复");
            return;
        }
        
        pendingMessageForRecovery = pendingMessage;
        aiService.autoRecoverNativeState(pendingMessage, new AIService.NativeStateRecoveryCallback() {
            @Override
            public void onRecoverySuccess(String msg) {
                // 实际处理在监听器中完成
            }

            @Override
            public void onRecoveryFailed(String reason) {
                // 实际处理在监听器中完成
            }
        });
    }

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
        boolean modelInMemory = LlamaHelper.isModelInitialized();
        if (!modelInMemory || !aiService.isInitialized()) {
            isLoadingModel = true;
            loadingProgressMessageIndex = -1;
            lastLoadingProgressShown = -1;
            showLoading("初始化AI服务...", "正在准备模型，这可能需要几秒钟...");
            addSystemMessage("⏳ 开始加载AI模型...", ChatMessage.SystemMessageType.INFO);
            
            final String msg = pendingMessage;
            new Thread(() -> {
                long startTime = System.currentTimeMillis();
                final boolean success = !aiService.isInitialized() ? aiService.initializeSafe() : aiService.reloadCurrentModelSafe();
                
                runOnUiThread(() -> {
                    if (success) {
                        if (msg != null && !msg.isEmpty()) {
                            processChatMessage(msg);
                        }
                    } else {
                        isLoadingModel = false;
                        addErrorMessage("模型加载失败", "无法初始化AI模型，请检查模型文件是否正确导入", true);
                        showToast("模型加载失败");
                    }
                });
            }).start();
            return false;
        }
        return true;
    }

    private void showLoading(String message, String submessage) {
        if (thinkingIndicator != null) thinkingIndicator.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        if (thinkingIndicator != null) thinkingIndicator.setVisibility(View.GONE);
    }

    // ===================== Message Adders =====================

    private void addUserMessage(String message) {
        // 隐藏空状态
        updateEmptyState();
        chatHistory.add(ChatMessage.createUserMessage(java.util.UUID.randomUUID().toString(), message, System.currentTimeMillis()));
        if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1);
        scrollToBottom();
        saveHistoryAsync();
    }

    private void addAIMessage(String message) {
        chatHistory.add(ChatMessage.createAIMessage(java.util.UUID.randomUUID().toString(), message, System.currentTimeMillis(), null, 0, 0));
        if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1);
        scrollToBottom();
        saveHistoryAsync();
    }

    private void addSystemMessage(String message) {
        chatHistory.add(ChatMessage.createSystemMessage(java.util.UUID.randomUUID().toString(), message, ChatMessage.SystemMessageType.INFO, System.currentTimeMillis()));
        if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1);
        scrollToBottom();
        saveHistoryAsync();
    }

    private void addErrorMessage(String title, String detail, boolean retryable) {
        chatHistory.add(ChatMessage.createErrorMessage(title, detail, retryable));
        if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1);
        scrollToBottom();
    }

    private int addToolCallMessage(String toolName, String parameters) {
        ChatMessage msg = ChatMessage.createToolCallMessage(toolName, parameters);
        chatHistory.add(msg);
        int pos = chatHistory.size() - 1;
        if (chatAdapter != null) chatAdapter.notifyItemInserted(pos);
        scrollToBottom();
        return pos;
    }

    private int addAgentStepMessage(ChatMessage.AgentStepInfo stepInfo) {
        ChatMessage msg = ChatMessage.createAgentStepMessage(stepInfo);
        chatHistory.add(msg);
        int pos = chatHistory.size() - 1;
        if (chatAdapter != null) chatAdapter.notifyItemInserted(pos);
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
        if (messageList != null) messageList.post(() -> {
            if (chatAdapter != null && chatAdapter.getItemCount() > 0) {
                int lastPosition = chatAdapter.getItemCount() - 1;
                // 使用 smoothScrollToPosition 确保平滑滚动到最新消息
                messageList.smoothScrollToPosition(lastPosition);
                // 同时调用 scrollToPosition 确保最终位置正确
                messageList.scrollToPosition(lastPosition);
            }
        });
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
        switch (action.type) {
            case COPY:
                if (action.content != null) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("AI Message", action.content));
                    showToast("已复制");
                }
                break;
            case REGENERATE: regenerateLastMessage(); break;
            case NEW_CHAT: clearChat(); addSystemMessage("已开始新对话"); break;
            case LIKE: showToast("感谢您的喜欢！"); break;
            case DISLIKE: showToast("我们会努力改进！"); break;
            case SHOW_HELP: handleHelpCommand(); break;
            case VIEW_TOOL_DETAILS:
                showToolDetailsDialog(action.messageId);
                break;
            case EXPORT_SUMMARY:
                exportSummary(action.messageId);
                break;
            case SHOW_GUIDE:
                showGuideDialog();
                break;
            case REPORT_ERROR:
                String errorMsg = action.content != null ? action.content : "未知错误";
                addSystemMessage("已收到错误报告: " + errorMsg);
                showToast("错误已报告，感谢您的反馈！");
                break;
        }
    }

    private void showToolDetailsDialog(String messageId) {
        if (messageId == null) {
            showToast("未找到工具调用信息");
            return;
        }
        
        ChatMessage toolMessage = null;
        for (ChatMessage msg : chatHistory) {
            if (messageId.equals(msg.id)) {
                toolMessage = msg;
                break;
            }
        }
        
        if (toolMessage == null || toolMessage.toolCallInfo == null) {
            showToast("未找到工具调用详情");
            return;
        }
        
        ChatMessage.ToolCallInfo info = toolMessage.toolCallInfo;
        StringBuilder details = new StringBuilder();
        
        details.append("📋 **工具调用详情**\n\n");
        details.append("**工具名称**: ").append(info.toolDisplayName != null ? info.toolDisplayName : info.toolName).append("\n");
        details.append("**工具标识**: ").append(info.toolName).append("\n");
        details.append("**执行状态**: ").append(info.getStatusText()).append("\n");
        if (info.executionTimeMs > 0) {
            details.append("**执行耗时**: ").append(info.executionTimeMs).append("ms\n");
        }
        
        if (info.parameters != null && !info.parameters.isEmpty()) {
            details.append("\n📝 **输入参数**:\n```json\n").append(formatJson(info.parameters)).append("\n```\n");
        }
        
        if (info.result != null && !info.result.isEmpty()) {
            String resultPreview = info.result;
            if (resultPreview.length() > 500) {
                resultPreview = resultPreview.substring(0, 500) + "\n...(内容已截断)";
            }
            details.append("\n📊 **执行结果**:\n").append(resultPreview).append("\n");
        }
        
        new AlertDialog.Builder(this)
            .setTitle("工具详情: " + (info.toolDisplayName != null ? info.toolDisplayName : info.toolName))
            .setMessage(details.toString())
            .setPositiveButton("复制结果", (dialog, which) -> {
                if (info.result != null) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Tool Result", info.result));
                    showToast("已复制结果");
                }
            })
            .setNeutralButton("复制全部", (dialog, which) -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Tool Details", details.toString()));
                showToast("已复制全部信息");
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    private void exportSummary(String messageId) {
        if (messageId == null) {
            showToast("未找到总结信息");
            return;
        }
        
        ChatMessage summaryMessage = null;
        for (ChatMessage msg : chatHistory) {
            if (messageId.equals(msg.id)) {
                summaryMessage = msg;
                break;
            }
        }
        
        if (summaryMessage == null) {
            showToast("未找到总结内容");
            return;
        }
        
        StringBuilder exportContent = new StringBuilder();
        exportContent.append("# AI 对话总结\n\n");
        exportContent.append("生成时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())).append("\n");
        
        if (summaryMessage.summaryInfo != null) {
            ChatMessage.SummaryInfo summaryInfo = summaryMessage.summaryInfo;
            exportContent.append("步骤数量: ").append(summaryInfo.stepsCount).append("\n");
            exportContent.append("总耗时: ").append(summaryInfo.totalTimeMs).append("ms\n");
            if (summaryInfo.keyPoints != null && !summaryInfo.keyPoints.isEmpty()) {
                exportContent.append("\n## 关键要点\n").append(summaryInfo.keyPoints).append("\n");
            }
            if (summaryInfo.nextSteps != null && !summaryInfo.nextSteps.isEmpty()) {
                exportContent.append("\n## 后续建议\n").append(summaryInfo.nextSteps).append("\n");
            }
        }
        
        exportContent.append("\n## 总结内容\n\n").append(summaryMessage.content != null ? summaryMessage.content : "");
        
        final String finalContent = exportContent.toString();
        
        new AlertDialog.Builder(this)
            .setTitle("导出总结")
            .setMessage("选择导出方式：\n\n" + 
                "📋 复制到剪贴板\n" +
                "📤 分享到其他应用\n" +
                "📝 预览内容")
            .setPositiveButton("复制", (dialog, which) -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("AI Summary", finalContent));
                showToast("已复制到剪贴板");
            })
            .setNeutralButton("分享", (dialog, which) -> {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TITLE, "AI对话总结");
                shareIntent.putExtra(Intent.EXTRA_TEXT, finalContent);
                startActivity(Intent.createChooser(shareIntent, "分享总结"));
            })
            .setNegativeButton("预览", (dialog, which) -> {
                showPreviewDialog("总结预览", finalContent);
            })
            .show();
    }

    private void showGuideDialog() {
        StringBuilder guide = new StringBuilder();
        guide.append("🤖 **AI助手使用指南**\n\n");
        guide.append("### 📚 基础功能\n");
        guide.append("• **提问**: 在输入框输入问题，点击发送\n");
        guide.append("• **连续对话**: AI会记住上下文，支持多轮对话\n");
        guide.append("• **停止生成**: 点击停止按钮中断当前回复\n\n");
        
        guide.append("### 🔧 快捷操作\n");
        guide.append("• **复制**: 点击消息下方的复制按钮\n");
        guide.append("• **重新生成**: 点击重新生成获取不同回复\n");
        guide.append("• **新对话**: 清除历史，开始新的对话\n\n");
        
        guide.append("### 🛠️ 智能工具\n");
        guide.append("• **天气查询**: 询问天气信息\n");
        guide.append("• **位置定位**: 获取当前位置\n");
        guide.append("• **文件操作**: 读取、分析文件\n");
        guide.append("• **网络搜索**: 搜索网络信息\n\n");
        
        guide.append("### 💡 使用技巧\n");
        guide.append("• 描述问题时尽量详细\n");
        guide.append("• 可以要求AI解释某个概念\n");
        guide.append("• 可以让AI总结之前的对话\n");
        guide.append("• 使用工具帮助完成复杂任务\n");
        
        new AlertDialog.Builder(this)
            .setTitle("AI助手使用指南")
            .setMessage(guide.toString())
            .setPositiveButton("我知道了", null)
            .setNeutralButton("复制指南", (dialog, which) -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("AI Guide", guide.toString()));
                showToast("已复制使用指南");
            })
            .show();
    }

    private void showPreviewDialog(String title, String content) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(content)
            .setPositiveButton("复制", (dialog, which) -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText(title, content));
                showToast("已复制");
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    private String formatJson(String jsonStr) {
        if (jsonStr == null) return "";
        try {
            org.json.JSONObject json = new org.json.JSONObject(jsonStr);
            return json.toString(2);
        } catch (Exception e) {
            return jsonStr;
        }
    }

    // ===================== Help & Guide =====================

    private void handleHelpCommand() {
        addAIMessage("可用功能：\n**应用功能：**生成题目、分析题目、翻译、学习计划、统计、搜索题目、天气、导入/导出题目、数据库操作\n**娱乐功能：**讲笑话、猜谜语、写诗、讲故事、知识问答、名言、游戏");
    }

    // ===================== UI Init =====================

    private void initAttachmentList() {
        if (attachmentList == null) return;
        attachmentList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        attachmentAdapter = new AttachmentAdapter(this, currentAttachments, new AttachmentAdapter.OnAttachmentClickListener() {
            @Override public void onImageClick(ChatMessage.Attachment a, int p) { openUri(a.url); }
            @Override public void onFileClick(ChatMessage.Attachment a, int p) { showToast("文件: " + a.name); }
            @Override public void onAttachmentRemove(ChatMessage.Attachment a, int p) {
                if (attachmentAdapter != null) { attachmentAdapter.removeAttachment(p); if (attachmentAdapter.isEmpty()) attachmentList.setVisibility(View.GONE); }
            }
        });
        attachmentList.setAdapter(attachmentAdapter);
    }

    private void openUri(String url) {
        if (url != null) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); } catch (Exception e) { showToast("无法打开"); } }
    }

    private void initHistoryList() {
        if (historyList == null) return;
        historyList.setLayoutManager(new LinearLayoutManager(this));
    }

    private void refreshHistoryAdapter() {
        if (historyList == null || chatHistory == null) return;
        chatHistoryAdapter = new ChatHistoryAdapter(this, chatHistory, new ChatHistoryAdapter.OnHistoryItemClickListener() {
            @Override public void onItemClick(ChatHistoryItem item, int p) { if (drawerLayout != null) drawerLayout.closeDrawer(findViewById(R.id.history_drawer)); }
            @Override public void onItemLongClick(ChatHistoryItem item, int p) {}
            @Override public void onItemDelete(ChatHistoryItem item, int p) { clearChat(); refreshHistoryAdapter(); showToast("已删除"); }
            @Override public void onItemShare(ChatHistoryItem item, int p) { showToast("分享功能开发中"); }
            @Override public void onItemExport(ChatHistoryItem item, int p) { showToast("导出功能开发中"); }
            @Override public void onClearAllHistory() { clearChat(); }
        });
        historyList.setAdapter(chatHistoryAdapter);
    }

    // ===================== Weather Banner =====================

    private void loadWeatherBanner() {
        if (weatherManager == null || weatherBanner == null) return;
        
        // 显示加载状态
        if (weatherCity != null) weatherCity.setText("正在获取天气...");
        if (weatherTemp != null) weatherTemp.setText("--°C");
        if (weatherDesc != null) weatherDesc.setText("加载中...");
        
        new Thread(() -> {
            try {
                String result = weatherManager.getCurrentWeather("北京").get();
                if (result != null && !result.isEmpty() && !result.contains("失败")) {
                    runOnUiThread(() -> {
                        try {
                            // 解析天气数据
                            org.json.JSONObject weatherJson = parseWeatherResponse(result);
                            
                            if (weatherJson != null) {
                                String cityName = weatherJson.optString("city", "北京");
                                String tempStr = weatherJson.optString("temperature", "--");
                                String condition = weatherJson.optString("condition", "--");
                                
                                if (weatherIcon != null) {
                                    String icon = weatherJson.optString("icon", "");
                                    weatherIcon.setText(icon.isEmpty() ? "" : icon);
                                }
                                if (weatherCity != null) weatherCity.setText(cityName);
                                if (weatherTemp != null) weatherTemp.setText(tempStr + "°C");
                                if (weatherDesc != null) weatherDesc.setText(condition);
                                
                                // 保存城市信息用于后续查询
                                weatherBannerCity = cityName;
                                
                                weatherBanner.setVisibility(View.VISIBLE);
                                weatherBannerVisible = true;
                            } else {
                                // 解析失败，隐藏横幅
                                weatherBanner.setVisibility(View.GONE);
                                weatherBannerVisible = false;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing weather response", e);
                            weatherBanner.setVisibility(View.GONE);
                            weatherBannerVisible = false;
                        }
                    });
                } else {
                    // 获取天气失败，隐藏横幅
                    runOnUiThread(() -> {
                        weatherBanner.setVisibility(View.GONE);
                        weatherBannerVisible = false;
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading weather banner", e);
                runOnUiThread(() -> {
                    weatherBanner.setVisibility(View.GONE);
                    weatherBannerVisible = false;
                });
            }
        }).start();
    }
    
    // 解析天气响应
    private org.json.JSONObject parseWeatherResponse(String weatherText) {
        try {
            org.json.JSONObject result = new org.json.JSONObject();
            
            if (weatherText == null || weatherText.isEmpty()) {
                return null;
            }
            
            // 检查是否包含错误信息
            if (weatherText.contains("失败") || weatherText.contains("错误")) {
                return null;
            }
            
            String[] lines = weatherText.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("城市:")) {
                    result.put("city", line.substring(3).trim());
                } else if (line.startsWith("天气:")) {
                    result.put("condition", line.substring(3).trim());
                } else if (line.startsWith("温度:")) {
                    String temp = line.substring(3).trim().replace("°C", "").replace("°", "");
                    result.put("temperature", temp);
                } else if (line.startsWith("图标:")) {
                    result.put("icon", line.substring(3).trim());
                }
            }
            
            // 如果没有解析到城市名称，使用默认值
            if (!result.has("city")) {
                result.put("city", "北京");
            }
            
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing weather response", e);
            return null;
        }
    }

    private void refreshWeatherBanner() { loadWeatherBanner(); }

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
        currentAttachments.clear();
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
            currentAttachments.add(new ChatMessage.Attachment(type, uri.toString(), fileName, getFileSizeFromUri(uri)));
        }
        if (attachmentList != null) {
            initAttachmentList();
            attachmentList.setVisibility(View.VISIBLE);
        }
        showToast("已添加 " + uris.size() + " 个附件");

        // 附件添加后自动发送（无需输入文字）
        sendMessageWithAttachments();
    }

    /**
     * 发送带附件的消息（无需文字输入）
     */
    private void sendMessageWithAttachments() {
        if (currentAttachments.isEmpty()) {
            return;
        }

        // 检查AI服务状态
        if (!isAIReady()) {
            showToast("AI服务未就绪，请稍后重试");
            return;
        }

        // 保存附件列表并清空当前列表
        List<ChatMessage.Attachment> savedAttachments = new ArrayList<>(currentAttachments);
        currentAttachments.clear();
        resetAttachmentAdapter();

        // 构建默认消息
        String defaultMessage = "请分析这些附件的内容";

        // 创建用户消息
        ChatMessage userMessage = ChatMessage.createUserMessage(defaultMessage, savedAttachments);
        chatHistory.add(userMessage);
        if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1);
        scrollToBottom();
        saveHistoryAsync();

        // 处理带附件的消息
        processMessageWithAttachments(defaultMessage, savedAttachments);
    }

    /**
     * 检查AI服务是否就绪
     */
    private boolean isAIReady() {
        if (shouldUseOnlineModel()) {
            return inferenceRouter != null && inferenceRouter.isCurrentModelAvailable();
        }
        return aiService != null && aiService.isInitialized();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateModelNameDisplay();
        initAgentChatHandler();
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

            // 清理NativeEventBridge
            NativeEventBridge.getInstance().destroy();

            // 清理附件管理器
            if (attachmentManager != null) {
                attachmentManager.clearAllAttachments();
            }

            unregisterAIStatusObserver();
            unregisterComponentCallbacks(memoryCallback);
            if (localBroadcastManager != null && aiResultReceiver != null) { try { localBroadcastManager.unregisterReceiver(aiResultReceiver); } catch (Exception e) { AILogger.w(TAG, "unregisterReceiver aiResultReceiver failed", e); } }
            if (localBroadcastManager != null && aiTokenReceiver != null) { try { localBroadcastManager.unregisterReceiver(aiTokenReceiver); } catch (Exception e) { AILogger.w(TAG, "unregisterReceiver aiTokenReceiver failed", e); } }
            if (aiService != null) aiService.chatStop();
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
        if (aiService == null) return;
        aiStatusObserver = new AIService.DetailedStatusObserver() {
            @Override
            public void onStateChanged(AIServiceState.ServiceStage stage, String message, int progress, long elapsedMs) {
                runOnUiThread(() -> handleAIStatusChange(stage, message, progress, elapsedMs));
            }
            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> handleAIServiceError(errorMessage));
            }
            @Override
            public void onInitialized(String modelName, long loadTimeMs) {
                runOnUiThread(() -> handleAIServiceInitialized(modelName, loadTimeMs));
            }
        };
        aiService.registerDetailedStatusObserver(aiStatusObserver);
    }

    private void unregisterAIStatusObserver() {
        if (aiService != null && aiStatusObserver != null) {
            aiService.unregisterDetailedStatusObserver(aiStatusObserver);
            aiStatusObserver = null;
        }
    }

    private void handleAIStatusChange(AIServiceState.ServiceStage stage, String message, int progress, long elapsedMs) {
        // 如果使用在线模型，忽略本地模型状态变化
        if (shouldUseOnlineModel()) {
            return;
        }
        
        updateServiceStatusDisplay(stage, message, progress, elapsedMs);
        
        if (!isLoadingModel) return;
        
        String stageName = getStageDisplayName(stage);
        
        // 更新聊天界面中的加载进度消息
        updateLoadingProgressMessage(stageName, message, progress, elapsedMs);
        
        updateLoadingUI(message, null, progress);
    }
    
    private void updateServiceStatusDisplay(AIServiceState.ServiceStage stage, String message, int progress, long elapsedMs) {
        if (serviceStatusIcon == null || serviceStatusText == null) return;
        
        // 检查是否使用在线模型
        if (shouldUseOnlineModel()) {
            stopLoadingTimer();
            if (serviceStatusProgress != null) {
                serviceStatusProgress.setVisibility(View.GONE);
            }
            
            String modelName = inferenceRouter != null ? inferenceRouter.getCurrentModelName() : null;
            String displayName = modelName != null && !modelName.isEmpty() ? modelName : "在线模型";
            serviceStatusIcon.setText("☁️");
            serviceStatusText.setText("云端推理就绪 · " + displayName);
            return;
        }
        
        String stageIcon = getStageIcon(stage);
        String stageName = getStageDisplayName(stage);
        String displayMessage = message != null ? message : stageName;
        
        boolean isLoading = stage == AIServiceState.ServiceStage.NATIVE_LIBRARY_LOADING
            || stage == AIServiceState.ServiceStage.MODEL_FILE_PREPARING
            || stage == AIServiceState.ServiceStage.MODEL_LOADING
            || stage == AIServiceState.ServiceStage.GPU_INITIALIZATION
            || stage == AIServiceState.ServiceStage.CPU_FALLBACK
            || stage == AIServiceState.ServiceStage.CHAT_CONTEXT_CREATING;
        
        if (isLoading) {
            startLoadingTimer(stage, message, progress);
            
            if (serviceStatusProgress != null) {
                serviceStatusProgress.setVisibility(View.VISIBLE);
                serviceStatusProgress.setProgress(progress);
            }
        } else {
            stopLoadingTimer();
            
            if (serviceStatusProgress != null) {
                serviceStatusProgress.setVisibility(View.GONE);
            }
        }
        
        // 为本地模型添加标识
        if (stage == AIServiceState.ServiceStage.INITIALIZED) {
            stageIcon = "📱";
            displayMessage = "本地推理就绪 · " + (message != null ? message : "AI服务已就绪");
        }
        
        serviceStatusIcon.setText(stageIcon);
        serviceStatusText.setText(displayMessage);
    }
    
    private void startLoadingTimer(AIServiceState.ServiceStage stage, String message, int progress) {
        if (uiHandler == null) return;
        stopLoadingTimer();
        
        final String stageIcon = getStageIcon(stage);
        final String baseMessage = message != null ? message : getStageDisplayName(stage);
        
        loadingTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (aiService == null || aiService.getServiceState() == null) {
                    return;
                }
                
                long currentElapsed = aiService.getServiceState().getElapsedTimeMs();
                String displayMessage = baseMessage;
                if (currentElapsed > 0) {
                    displayMessage = String.format("%s (已耗时: %.1fs)", baseMessage, currentElapsed / 1000.0);
                }
                
                if (serviceStatusIcon != null) {
                    serviceStatusIcon.setText(stageIcon);
                }
                if (serviceStatusText != null) {
                    serviceStatusText.setText(displayMessage);
                }
                
                if (aiService.getServiceState().isLoading()) {
                    uiHandler.postDelayed(this, LOADING_TIMER_INTERVAL_MS);
                }
            }
        };
        
        uiHandler.post(loadingTimerRunnable);
    }
    
    private void stopLoadingTimer() {
        if (uiHandler != null && loadingTimerRunnable != null) {
            uiHandler.removeCallbacks(loadingTimerRunnable);
            loadingTimerRunnable = null;
        }
    }

    private void handleAIServiceError(String errorMessage) {
        isLoadingModel = false;
        loadingProgressMessageIndex = -1;
        lastLoadingProgressShown = -1;
        hideLoading();
        addErrorMessage("AI服务初始化失败", errorMessage, true);
        showToast("模型加载失败");
    }

    private void handleAIServiceInitialized(String modelName, long loadTimeMs) {
        isLoadingModel = false;
        loadingProgressMessageIndex = -1;
        lastLoadingProgressShown = -1;
        hideLoading();
        String successMsg = String.format("✓ 模型加载完成\n模型: %s\n耗时: %.1f秒", 
            modelName != null ? modelName : "未知", loadTimeMs / 1000.0);
        addSystemMessage(successMsg, ChatMessage.SystemMessageType.SUCCESS);
        showToast("模型加载成功");
        updateModelNameDisplay();
        updateServiceStatusDisplay(AIServiceState.ServiceStage.INITIALIZED, "AI服务已就绪", 100, 0);
    }
    
    private void updateInitialServiceStatus() {
        // 检查是否使用在线模型
        if (shouldUseOnlineModel()) {
            if (serviceStatusIcon != null && serviceStatusText != null) {
                String modelName = inferenceRouter != null ? inferenceRouter.getCurrentModelName() : null;
                String displayName = modelName != null && !modelName.isEmpty() ? modelName : "在线模型";
                serviceStatusIcon.setText("☁️");
                serviceStatusText.setText("云端推理就绪 · " + displayName);
            }
            if (serviceStatusProgress != null) {
                serviceStatusProgress.setVisibility(View.GONE);
            }
            return;
        }
        
        if (aiService == null) return;
        AIServiceState state = aiService.getServiceState();
        if (state == null) {
            updateServiceStatusDisplay(AIServiceState.ServiceStage.UNINITIALIZED, "AI服务未初始化", 0, 0);
            return;
        }
        AIServiceState.ServiceStage stage = state.getCurrentStage();
        String message = state.getStageDescription();
        if (message == null || message.isEmpty()) {
            message = getStageDisplayName(stage);
        }
        updateServiceStatusDisplay(stage, message, state.getProgressPercent(), 0);
    }

    private void showServiceStatusDetails() {
        // 检查是否使用在线模型
        if (shouldUseOnlineModel()) {
            android.view.View dialogView = getLayoutInflater().inflate(R.layout.dialog_service_status_detail, null);

            TextView statusValue = dialogView.findViewById(R.id.detail_status_value);
            TextView descValue = dialogView.findViewById(R.id.detail_desc_value);
            TextView progressValue = dialogView.findViewById(R.id.detail_progress_value);
            TextView libValue = dialogView.findViewById(R.id.detail_lib_value);
            TextView modelValue = dialogView.findViewById(R.id.detail_model_value);
            TextView optValue = dialogView.findViewById(R.id.detail_opt_value);
            
            // 隐藏本地模型特有的行
            View memRow = dialogView.findViewById(R.id.detail_mem_row);
            View memDivider = dialogView.findViewById(R.id.detail_mem_divider);
            View speedRow = dialogView.findViewById(R.id.detail_speed_row);
            View speedDivider = dialogView.findViewById(R.id.detail_speed_divider);
            View tokenRow = dialogView.findViewById(R.id.detail_token_row);
            View errorRow = dialogView.findViewById(R.id.detail_error_row);
            
            if (memRow != null) memRow.setVisibility(android.view.View.GONE);
            if (memDivider != null) memDivider.setVisibility(android.view.View.GONE);
            if (speedRow != null) speedRow.setVisibility(android.view.View.GONE);
            if (speedDivider != null) speedDivider.setVisibility(android.view.View.GONE);
            if (tokenRow != null) tokenRow.setVisibility(android.view.View.GONE);
            if (errorRow != null) errorRow.setVisibility(android.view.View.GONE);
            
            com.google.android.material.card.MaterialCardView modelInfoCard = dialogView.findViewById(R.id.detail_model_info_card);
            TextView modelInfoText = dialogView.findViewById(R.id.detail_model_info_text);

            String modelName = inferenceRouter != null ? inferenceRouter.getCurrentModelName() : null;
            String displayName = modelName != null && !modelName.isEmpty() ? modelName : "在线模型";
            
            statusValue.setText("☁️ 云端推理");
            descValue.setText("使用远程AI模型进行推理");
            progressValue.setText("100%");
            libValue.setText("-");
            modelValue.setText(displayName);
            optValue.setText("云端服务");
            
            // 添加在线模型详细信息
            if (modelInfoCard != null && modelInfoText != null) {
                StringBuilder info = new StringBuilder();
                info.append("推理类型: 云端在线推理\n");
                info.append("模型名称: ").append(displayName).append("\n");
                info.append("推理优势: 强大的模型能力、无需本地资源\n");
                if (inferenceRouter != null) {
                    info.append("是否可用: ").append(inferenceRouter.isCurrentModelAvailable() ? "是" : "否").append("\n");
                }
                modelInfoText.setText(info.toString());
                modelInfoCard.setVisibility(android.view.View.VISIBLE);
            }
            
            String fullDetails = "☁️ 云端推理服务\n\n" +
                "当前状态: 云端推理就绪\n" +
                "模型名称: " + displayName + "\n" +
                "推理类型: 在线/云端\n" +
                "优势: 强大的模型能力、无需本地资源\n";

            new AlertDialog.Builder(this)
                .setTitle("AI服务状态详情（云端）")
                .setView(dialogView)
                .setPositiveButton("复制详情", (dialog, which) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("AI Status Details", fullDetails));
                    showToast("已复制到剪贴板");
                })
                .setNegativeButton("关闭", null)
                .show();
            return;
        }
        
        // 本地模型逻辑
        if (aiService == null) {
            showToast("AI服务未初始化");
            return;
        }

        AIServiceState state = aiService.getServiceState();
        android.view.View dialogView = getLayoutInflater().inflate(R.layout.dialog_service_status_detail, null);

        TextView statusValue = dialogView.findViewById(R.id.detail_status_value);
        TextView descValue = dialogView.findViewById(R.id.detail_desc_value);
        TextView progressValue = dialogView.findViewById(R.id.detail_progress_value);
        View errorRow = dialogView.findViewById(R.id.detail_error_row);
        TextView errorValue = dialogView.findViewById(R.id.detail_error_value);

        TextView libValue = dialogView.findViewById(R.id.detail_lib_value);
        TextView modelValue = dialogView.findViewById(R.id.detail_model_value);
        TextView optValue = dialogView.findViewById(R.id.detail_opt_value);
        View memRow = dialogView.findViewById(R.id.detail_mem_row);
        View memDivider = dialogView.findViewById(R.id.detail_mem_divider);
        TextView memValue = dialogView.findViewById(R.id.detail_mem_value);
        View speedRow = dialogView.findViewById(R.id.detail_speed_row);
        View speedDivider = dialogView.findViewById(R.id.detail_speed_divider);
        TextView speedValue = dialogView.findViewById(R.id.detail_speed_value);
        View tokenRow = dialogView.findViewById(R.id.detail_token_row);
        TextView tokenValue = dialogView.findViewById(R.id.detail_token_value);

        com.google.android.material.card.MaterialCardView modelInfoCard = dialogView.findViewById(R.id.detail_model_info_card);
        TextView modelInfoText = dialogView.findViewById(R.id.detail_model_info_text);

        if (state != null) {
            AIServiceState.ServiceStage stage = state.getCurrentStage();
            statusValue.setText("📱 " + getStageDisplayName(stage));
            descValue.setText(state.getStageMessage() != null ? state.getStageMessage() : "无");
            progressValue.setText(state.getProgressPercent() + "%");
            if (state.isError() && state.getErrorMessage() != null) {
                errorRow.setVisibility(android.view.View.VISIBLE);
                errorValue.setText(state.getErrorMessage());
            }
        } else {
            statusValue.setText("未知");
            descValue.setText("-");
            progressValue.setText("-");
        }

        boolean libLoaded = com.oilquiz.app.ai.jni.LlamaHelper.isLibraryLoaded();
        libValue.setText(libLoaded ? "✓ 已加载" : "✗ 未加载");
        libValue.setTextColor(libLoaded ? 0xFF4CAF50 : 0xFFF44336);

        String modelName = aiService.getCurrentModelName();
        modelValue.setText(modelName != null ? modelName : "未选择");

        AIConfig.OptimizationMode optMode = aiService.getOptimizationMode();
        optValue.setText(optMode != null ? optMode.displayName : "平衡模式");

        try {
            float memUsage = com.oilquiz.app.ai.jni.LlamaHelper.getMemoryUsage();
            if (memUsage > 0) {
                memRow.setVisibility(android.view.View.VISIBLE);
                memDivider.setVisibility(android.view.View.VISIBLE);
                memValue.setText(String.format("%.1f MB", memUsage));
            }
        } catch (Exception e) { AILogger.w(TAG, "Failed to get memory usage", e); }

        try {
            float speed = com.oilquiz.app.ai.jni.LlamaHelper.getInferenceSpeed();
            if (speed > 0) {
                speedRow.setVisibility(android.view.View.VISIBLE);
                speedDivider.setVisibility(android.view.View.VISIBLE);
                speedValue.setText(String.format("%.2f token/s", speed));
            }
        } catch (Exception e) { AILogger.w(TAG, "Failed to get inference speed", e); }

        try {
            int tokenCount = com.oilquiz.app.ai.jni.LlamaHelper.getTokenCount();
            if (tokenCount > 0) {
                tokenRow.setVisibility(android.view.View.VISIBLE);
                tokenValue.setText(String.valueOf(tokenCount));
            }
        } catch (Exception e) { AILogger.w(TAG, "Failed to get token count", e); }

        try {
            String modelInfo = com.oilquiz.app.ai.jni.LlamaHelper.getModelInfo();
            if (modelInfo != null && !modelInfo.isEmpty() && !modelInfo.startsWith("Error:")) {
                modelInfoCard.setVisibility(android.view.View.VISIBLE);
                modelInfoText.setText(modelInfo);
            }
        } catch (Exception e) { AILogger.w(TAG, "Failed to get model info", e); }

        String fullDetails = buildFullDetailsText();

        new AlertDialog.Builder(this)
            .setTitle("AI服务状态详情（本地）")
            .setView(dialogView)
            .setPositiveButton("复制详情", (dialog, which) -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("AI Status Details", fullDetails));
                showToast("已复制到剪贴板");
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    private String buildFullDetailsText() {
        StringBuilder details = new StringBuilder();
        details.append("🤖 AI服务状态详情\n\n");

        AIServiceState state = aiService.getServiceState();
        if (state != null) {
            AIServiceState.ServiceStage stage = state.getCurrentStage();
            details.append("📊 当前状态: ").append(getStageDisplayName(stage)).append("\n");
            details.append("📝 状态描述: ").append(state.getStageMessage() != null ? state.getStageMessage() : "无").append("\n");
            details.append("📈 进度: ").append(state.getProgressPercent()).append("%\n");
            if (state.getCurrentModelName() != null) {
                details.append("📦 当前模型: ").append(state.getCurrentModelName()).append("\n");
            }
            if (state.isError() && state.getErrorMessage() != null) {
                details.append("❌ 错误信息: ").append(state.getErrorMessage()).append("\n");
            }
        } else {
            details.append("📊 当前状态: 未知\n");
        }

        details.append("\n🔧 运行信息\n");
        details.append("Native库: ").append(com.oilquiz.app.ai.jni.LlamaHelper.isLibraryLoaded() ? "✓ 已加载" : "✗ 未加载").append("\n");

        String modelName = aiService.getCurrentModelName();
        details.append("模型名称: ").append(modelName != null ? modelName : "未选择").append("\n");

        AIConfig.OptimizationMode optMode = aiService.getOptimizationMode();
        details.append("优化模式: ").append(optMode != null ? optMode.displayName : "平衡模式").append("\n");

        try {
            float memUsage = com.oilquiz.app.ai.jni.LlamaHelper.getMemoryUsage();
            if (memUsage > 0) details.append("内存使用: ").append(String.format("%.1f MB", memUsage)).append("\n");
        } catch (Exception e) { }

        try {
            float speed = com.oilquiz.app.ai.jni.LlamaHelper.getInferenceSpeed();
            if (speed > 0) details.append("推理速度: ").append(String.format("%.2f token/s", speed)).append("\n");
        } catch (Exception e) { AILogger.w(TAG, "Failed to get inference speed for details", e); }

        try {
            int tokenCount = com.oilquiz.app.ai.jni.LlamaHelper.getTokenCount();
            if (tokenCount > 0) details.append("Token计数: ").append(tokenCount).append("\n");
        } catch (Exception e) { AILogger.w(TAG, "Failed to get token count for details", e); }

        try {
            String modelInfo = com.oilquiz.app.ai.jni.LlamaHelper.getModelInfo();
            if (modelInfo != null && !modelInfo.isEmpty() && !modelInfo.startsWith("Error:")) {
                details.append("\n📋 模型信息\n").append(modelInfo).append("\n");
            }
        } catch (Exception e) { AILogger.w(TAG, "Failed to get model info for details", e); }

        return details.toString();
    }

    private String getStageIcon(AIServiceState.ServiceStage stage) {
        if (stage == null) return "⚙️";
        switch (stage) {
            case UNINITIALIZED: return "⏳";
            case NATIVE_LIBRARY_LOADING: return "📦";
            case MODEL_FILE_PREPARING: return "📁";
            case MODEL_LOADING: return "📥";
            case GPU_INITIALIZATION: return "🎮";
            case CPU_FALLBACK: return "💻";
            case CHAT_CONTEXT_CREATING: return "🔧";
            case INITIALIZED: return "✓";
            case ERROR: return "✗";
            default: return "⚙️";
        }
    }

    private String getStageDisplayName(AIServiceState.ServiceStage stage) {
        if (stage == null) return "处理中";
        switch (stage) {
            case UNINITIALIZED: return "未初始化";
            case NATIVE_LIBRARY_LOADING: return "加载原生库";
            case MODEL_FILE_PREPARING: return "准备模型文件";
            case MODEL_LOADING: return "加载模型";
            case GPU_INITIALIZATION: return "初始化GPU";
            case CPU_FALLBACK: return "切换到CPU模式";
            case CHAT_CONTEXT_CREATING: return "创建对话上下文";
            case INITIALIZED: return "已就绪";
            case ERROR: return "错误";
            default: return "处理中";
        }
    }

    private void updateLoadingUI(String message, String submessage, int progress) {
        if (thinkingIndicator != null) thinkingIndicator.setVisibility(View.VISIBLE);
        
        // 在恢复过程中，实时更新进度消息
        if (isRecovering && submessage != null) {
            recoveryProgressUpdateCount++;
            // 避免频繁更新，每5个进度阶段更新一次消息
            if (recoveryProgressUpdateCount % 5 == 0 || progress >= 100) {
                updateRecoveryProgressMessage(message, progress);
            }
        }
    }
    
    // 用于跟踪上次显示的进度，避免重复消息
    private int lastRecoveryProgressShown = -1;
    private int lastLoadingProgressShown = -1;
    
    private void updateRecoveryProgressMessage(String message, int progress) {
        if (progress == lastRecoveryProgressShown) return;
        lastRecoveryProgressShown = progress;
        
        // 找到最后一条恢复进度消息并更新它
        for (int i = chatHistory.size() - 1; i >= 0; i--) {
            ChatMessage msg = chatHistory.get(i);
            if (msg.type == ChatMessage.MessageType.SYSTEM && 
                msg.content != null && 
                msg.content.contains("🔄 正在重新加载模型")) {
                // 更新进度显示
                String newContent = msg.content.replaceAll("\\[\\d+%\\]", "[" + progress + "%]");
                if (!newContent.contains("[")) {
                    newContent = msg.content + " [" + progress + "%]";
                }
                msg.content = newContent;
                if (chatAdapter != null) chatAdapter.notifyItemChanged(i);
                break;
            }
        }
    }
    
    /**
     * 更新模型加载进度到聊天界面
     */
    private void updateLoadingProgressMessage(String stageName, String message, int progress, long elapsedMs) {
        if (progress == lastLoadingProgressShown) return;
        lastLoadingProgressShown = progress;
        
        if (loadingProgressMessageIndex < 0 || loadingProgressMessageIndex >= chatHistory.size()) {
            // 查找或创建进度消息
            for (int i = chatHistory.size() - 1; i >= 0; i--) {
                ChatMessage msg = chatHistory.get(i);
                if (msg.type == ChatMessage.MessageType.SYSTEM && 
                    msg.content != null && 
                    msg.content.contains("⏳")) {
                    loadingProgressMessageIndex = i;
                    break;
                }
            }
        }
        
        if (loadingProgressMessageIndex >= 0 && loadingProgressMessageIndex < chatHistory.size()) {
            ChatMessage msg = chatHistory.get(loadingProgressMessageIndex);
            String progressStr = progress > 0 ? String.format("[%d%%]", progress) : "";
            String timeStr = String.format("%.1f秒", elapsedMs / 1000.0);
            String icon = progress >= 100 ? "✅" : "⏳";
            msg.content = String.format("%s %s %s\n已耗时: %s", icon, stageName, progressStr, timeStr);
            if (chatAdapter != null) chatAdapter.notifyItemChanged(loadingProgressMessageIndex);
        }
    }

    private void addSystemMessage(String message, ChatMessage.SystemMessageType type) {
        chatHistory.add(ChatMessage.createSystemMessage(
            java.util.UUID.randomUUID().toString(), 
            message, 
            type, 
            System.currentTimeMillis()));
        if (chatAdapter != null) chatAdapter.notifyItemInserted(chatHistory.size() - 1);
        scrollToBottom();
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
     * 更新空状态显示
     */
    private void updateEmptyState() {
        if (emptyStateView != null) {
            boolean isEmpty = chatHistory == null || chatHistory.isEmpty();
            emptyStateView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            messageList.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * 导出当前对话
     */
    private void exportChat() {
        if (chatHistory == null || chatHistory.isEmpty()) {
            showToast("没有可导出的对话内容");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("AI对话导出\n");
        sb.append("时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date())).append("\n");
        sb.append("===================\n\n");
        for (ChatMessage msg : chatHistory) {
            String role = "未知";
            if (msg.type == ChatMessage.MessageType.USER) role = "用户";
            else if (msg.type == ChatMessage.MessageType.AI) role = "AI";
            else if (msg.type == ChatMessage.MessageType.SYSTEM) role = "系统";
            String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date(msg.timestamp));
            sb.append("[").append(time).append("] ").append(role).append(":\n");
            sb.append(msg.content != null ? msg.content : "").append("\n\n");
        }
        
        // 复制到剪贴板
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("AI对话", sb.toString());
        clipboard.setPrimaryClip(clip);
        showToast("对话已复制到剪贴板");
    }

    /**
     * 显示输入框选项菜单
     */
    private void showInputOptions() {
        String[] options = {"粘贴", "清空输入", "导出对话"};
        new AlertDialog.Builder(this)
            .setTitle("选项")
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: // 粘贴
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null) {
                            android.content.ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
                            String text = item.getText() != null ? item.getText().toString() : "";
                            inputMessage.append(text);
                        }
                        break;
                    case 1: // 清空输入
                        inputMessage.setText("");
                        break;
                    case 2: // 导出对话
                        exportChat();
                        break;
                }
            })
            .show();
    }
}