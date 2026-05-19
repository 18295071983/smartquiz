package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;

import com.oilquiz.app.util.AILogger;
import com.oilquiz.app.util.QWeatherIconMapper;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AIServiceState;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.ai.chat.AgentChatHandler;
import com.oilquiz.app.ai.chat.StreamingUpdateManager;
import com.oilquiz.app.ai.service.AIProcessingService;
import com.oilquiz.app.ai.tool.AIToolsManager;
import com.oilquiz.app.ai.tool.AIEntertainmentManager;
import com.oilquiz.app.ai.tool.AIWeatherManager;
import com.oilquiz.app.ai.tool.AIToolUsageGuide;
import com.oilquiz.app.ai.tool.LocationTool;
import com.oilquiz.app.ai.util.ChatHistoryManager;
import com.oilquiz.app.ai.util.AttachmentManager;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.chat.ChatAdapter;
import com.oilquiz.app.ai.chat.ChatModeManager;
import com.oilquiz.app.ai.chat.ModeInfo;
import com.oilquiz.app.ai.skill.SkillManager;
import com.oilquiz.app.ai.refactor.AIConfig;
import com.oilquiz.app.ai.refactor.CacheManager;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ui.adapter.ChatModeAdapter;
import com.oilquiz.app.ui.adapter.ChatHistoryAdapter;
import com.oilquiz.app.ui.adapter.ChatHistoryAdapter.ChatHistoryItem;
import com.oilquiz.app.ui.adapter.AttachmentAdapter;
import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.PermissionResourceProvider;
import com.oilquiz.app.manager.SpeechRecognizerManager;
import com.oilquiz.app.ui.base.BaseActivity;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class AIChatActivity extends BaseActivity {

    private static final String TAG = "AIChatActivity";
    private static final int BATCH_TOKEN_COUNT = 20;
    private static final long BATCH_INTERVAL_MS = 50;

    private MaterialButton btnBack;
    private MaterialButton btnModelSelect;
    private MaterialButton btnHistory;
    private MaterialButton btnClearChat;
    private MaterialButton btnStopGeneration;
    private MaterialButton btnLogViewer;
    private TextView modelNameText;
    private TextView currentModeText;
    private androidx.recyclerview.widget.RecyclerView messageList;
    private androidx.recyclerview.widget.RecyclerView modeList;
    private androidx.recyclerview.widget.RecyclerView attachmentList;
    private androidx.recyclerview.widget.RecyclerView historyList;
    private DrawerLayout drawerLayout;
    private EditText inputMessage;
    private MaterialButton btnSend;
    private MaterialButton btnAttach;
    private MaterialButton btnVoice;
    private MaterialButton btnGuide;
    private MaterialButton btnCloseHistory;
    private MaterialButton btnClearAllHistory;
    private View loadingLayout;
    private View thinkingIndicator;
    private TextView loadingMessage;
    private TextView loadingSubmessage;
    private MaterialButton btnCancel;
    private Chip chipSummary;
    private Chip chipTranslate;
    private Chip chipCodeExplain;
    private Chip chipOptimize;

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
    private List<ChatMessage> chatHistory;
    private ChatAdapter chatAdapter;
    private ChatHistoryManager chatHistoryManager;
    private ChatModeManager chatModeManager;
    private AttachmentManager attachmentManager;
    private ChatModeAdapter chatModeAdapter;
    private ChatHistoryAdapter chatHistoryAdapter;
    private AttachmentAdapter attachmentAdapter;
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

    private boolean isGenerating = false;
    private boolean isDirectStreaming = false;
    private volatile boolean needRebuildContext = false;
    private StringBuilder currentStreamingContent = null;
    private StringBuilder currentThinkingContent = null;
    private boolean isInThinking = false;
    private boolean isInTag = false;
    private StringBuilder tagBuffer = null;
    private int currentStreamingMessageIndex = -1;
    private String currentStreamingMessageId = null;
    private int agentToolLoopCount = 0;

    private int tokenCountSinceLastUpdate = 0;
    private long lastUpdateTime = 0;
    private boolean isUpdateScheduled = false;
    private android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private StreamingUpdateManager streamingUpdateManager = null;
    private long totalTokensGenerated = 0;
    private long generationStartTime = 0;
    private boolean isLoadingModel = false;
    private AIService.DetailedStatusObserver aiStatusObserver = null;

    private SpeechRecognizerManager speechManager;
    private boolean isVoiceInputActive = false;
    private ActivityResultLauncher<String[]> attachFileLauncher;
    private List<Uri> attachedFiles = new ArrayList<>();
    private List<ChatMessage.Attachment> currentAttachments = new ArrayList<>();

    private TextToSpeech ttsEngine;
    private boolean ttsInitialized = false;

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
            btnModelSelect = findViewById(R.id.btn_model_select);
            btnHistory = findViewById(R.id.btn_history);
            btnClearChat = findViewById(R.id.btn_clear_chat);
            btnStopGeneration = findViewById(R.id.btn_stop_generation);
            btnLogViewer = findViewById(R.id.btn_log_viewer);
            modelNameText = findViewById(R.id.model_name);
            currentModeText = findViewById(R.id.current_mode_text);
            messageList = findViewById(R.id.message_list);
            modeList = findViewById(R.id.mode_list);
            attachmentList = findViewById(R.id.attachment_list);
            historyList = findViewById(R.id.history_list);
            drawerLayout = findViewById(R.id.drawer_layout);
            inputMessage = findViewById(R.id.input_message);
            btnSend = findViewById(R.id.btn_send);
            btnAttach = findViewById(R.id.btn_attach);
            btnVoice = findViewById(R.id.btn_voice);
            btnGuide = findViewById(R.id.btn_guide);
            btnCloseHistory = findViewById(R.id.btn_close_history);
            btnClearAllHistory = findViewById(R.id.btn_clear_all_history);
            thinkingIndicator = findViewById(R.id.thinking_indicator);
            chipSummary = findViewById(R.id.chip_summary);
            chipTranslate = findViewById(R.id.chip_translate);
            chipCodeExplain = findViewById(R.id.chip_code_explain);
            chipOptimize = findViewById(R.id.chip_optimize);

            weatherBanner = findViewById(R.id.weather_banner);
            weatherIcon = findViewById(R.id.weather_icon);
            weatherCity = findViewById(R.id.weather_city);
            weatherTemp = findViewById(R.id.weather_temp);
            weatherDesc = findViewById(R.id.weather_desc);
            weatherHumidity = findViewById(R.id.weather_humidity);
            weatherWind = findViewById(R.id.weather_wind);
            btnWeatherRefresh = findViewById(R.id.btn_weather_refresh);
            btnWeatherClose = findViewById(R.id.btn_weather_close);

            loadingLayout = findViewById(R.id.loadingLayout);
            loadingMessage = findViewById(R.id.loading_message);
            loadingSubmessage = findViewById(R.id.loading_submessage);
            btnCancel = findViewById(R.id.btn_cancel);

            messageList.setLayoutManager(new LinearLayoutManager(this));
            chatHistory = new ArrayList<>();
            chatAdapter = new ChatAdapter(chatHistory, this::handleAction, null);
            chatAdapter.setRetryClickListener(messageId -> regenerateLastMessage());
            messageList.setAdapter(chatAdapter);

            initModeList();
            initAttachmentList();
            initHistoryList();

            if (btnLogViewer != null) {
                btnLogViewer.setOnClickListener(v -> startActivity(new Intent(AIChatActivity.this, LogViewerActivity.class)));
            }

            if (btnCancel != null) {
                btnCancel.setOnClickListener(v -> cancelGeneration());
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
            if (aiService == null) {
                showToast("AI服务初始化失败");
                return;
            }

            registerAIStatusObserver();

            chatHistoryManager = new ChatHistoryManager(this);
            chatModeManager = new ChatModeManager(this);
            chatModeManager.setOnModeChangeListener(new ChatModeManager.OnModeChangeListener() {
                @Override
                public void onModeChanged(ChatModeManager.ChatMode newMode, boolean isAuto) {
                    runOnUiThread(() -> {
                        if (currentModeText != null) {
                            ModeInfo modeInfo = findModeById(newMode.modeId);
                            if (modeInfo != null) updateModeDisplay(modeInfo);
                        }
                        if (chatModeAdapter != null) chatModeAdapter.setSelectedMode(newMode.modeId);
                    });
                }

                @Override
                public void onModeSwitchRequested(ChatModeManager.ChatMode requestedMode, boolean duringGeneration) {
                    runOnUiThread(() -> showToast("生成完成后将切换到" + requestedMode.displayName + "模式"));
                }

                @Override
                public void onContextNeedRebuild() {
                    needRebuildContext = true;
                    AppLogger.ai(TAG, "Context rebuild flag set");
                }
            });

            attachmentManager = new AttachmentManager(this);
            initAttachFileLauncher();

            aiToolsManager = new AIToolsManager(this);
            agentService = new AgentService(this);
            agentChatHandler = new AgentChatHandler(this, aiService, agentService, new AgentCallbackImpl());
            aiConfig = new AIConfig(this);
            cacheManager = new CacheManager(this);
            onlineModelManager = OnlineModelManager.getInstance(this);
            skillManager = new SkillManager(this);
            weatherManager = new AIWeatherManager(this, AIWeatherManager.WeatherProvider.HEFENG);
            aiEntertainmentManager = new AIEntertainmentManager(this);

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

            ChatModeManager.ChatMode currentMode = chatModeManager.getCurrentMode();
            if (chatModeAdapter != null) chatModeAdapter.setSelectedMode(currentMode.modeId);
            ModeInfo currentModeInfo = findModeById(currentMode.modeId);
            if (currentModeInfo != null) updateModeDisplay(currentModeInfo);

            refreshHistoryAdapter();

            if (chatHistory.isEmpty()) {
                addSystemMessage("欢迎使用AI对话功能！请输入您的问题，我会尽力回答。\n输入 '帮助' 查看更多功能。");
            } else {
                addSystemMessage("欢迎回来！继续我们的对话吧。");
            }

            if (LocationTool.hasLocationPermission(this)) {
                loadWeatherBanner();
            }

            initTTS();
            if (chatAdapter != null) {
                chatAdapter.setTTSClickListener(this::speakText);
            }
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error initializing data: " + e.getMessage());
            showToast("数据初始化失败: " + e.getMessage());
        }
    }

    private void initTTS() {
        try {
            ttsEngine = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS) {
                        int result = ttsEngine.setLanguage(java.util.Locale.CHINESE);
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            AppLogger.aiW(TAG, "TTS: 中文语音不支持，尝试使用默认语言");
                            ttsEngine.setLanguage(java.util.Locale.getDefault());
                        }
                        ttsInitialized = true;
                        AppLogger.ai(TAG, "TTS initialized successfully");
                    } else {
                        AppLogger.aiE(TAG, "TTS initialization failed with status: " + status);
                    }
                }
            });
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error initializing TTS: " + e.getMessage());
        }
    }

    private void speakText(String text) {
        if (!ttsInitialized || ttsEngine == null || text == null || text.isEmpty()) {
            return;
        }
        try {
            ttsEngine.stop();
            ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AI_CHAT_TTS_" + System.currentTimeMillis());
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error speaking text: " + e.getMessage());
        }
    }

    @Override
    protected void initListener() {
        btnBack.setOnClickListener(v -> finish());
        btnModelSelect.setOnClickListener(v -> startActivity(new Intent(AIChatActivity.this, ModelSelectorActivity.class)));
        btnClearChat.setOnClickListener(v -> clearChat());
        btnStopGeneration.setOnClickListener(v -> stopGeneration());
        btnSend.setOnClickListener(v -> sendMessage());
        btnAttach.setOnClickListener(v -> handleAttachFile());
        btnVoice.setOnClickListener(v -> handleVoiceInput());
        btnGuide.setOnClickListener(v -> handleShowGuideCommand());

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

        if (chipSummary != null) chipSummary.setOnClickListener(v -> handleQuickAction("总结对话"));
        if (chipTranslate != null) chipTranslate.setOnClickListener(v -> handleQuickAction("翻译"));
        if (chipCodeExplain != null) chipCodeExplain.setOnClickListener(v -> handleQuickAction("代码解释"));
        if (chipOptimize != null) chipOptimize.setOnClickListener(v -> handleQuickAction("优化文本"));

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

        if (chatModeManager != null && chatModeManager.isAutoModeEnabled()) {
            ChatModeManager.ChatMode determinedMode = chatModeManager.determineMode(message);
            if (chatModeAdapter != null) chatModeAdapter.setSelectedMode(determinedMode.modeId);
        }

        if (!currentAttachments.isEmpty()) {
            ChatMessage userMessage = ChatMessage.createUserMessage(message, new ArrayList<>(currentAttachments));
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
        } else if (message.equalsIgnoreCase("教程") || message.startsWith("显示使用教程") || message.startsWith("使用教程")) {
            handleShowGuideCommand(); return;
        }

        for (String[] pattern : COMMAND_PATTERNS) {
            if (message.startsWith(pattern[0])) {
                handlePrefixedCommand(message, pattern[0], pattern[1]);
                return;
            }
        }

        processChatMessage(message);
    }

    private void handlePrefixedCommand(String message, String prefix, String toolCategory) {
        String params = message.substring(prefix.length()).trim();
        if ("entertainment".equals(toolCategory)) {
            String type = mapEntertainmentType(prefix);
            if (type != null) executeEntertainment(type, params);
        } else if ("weather".equals(toolCategory)) {
            if (weatherBanner != null) { weatherBanner.setVisibility(View.VISIBLE); weatherBannerVisible = true; }
            if (params.isEmpty() && LocationTool.hasLocationPermission(this)) { loadWeatherBanner(); return; }
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
            if (aiService == null) { addSystemMessage("AI服务未初始化"); return; }

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

            final boolean enableThinking = chatModeManager != null
                && chatModeManager.getCurrentMode() == ChatModeManager.ChatMode.DEEP_THINKING;
            boolean isAgentMode = chatModeManager != null
                && chatModeManager.getCurrentMode() == ChatModeManager.ChatMode.AGENT;

            if (agentService != null && agentService.shouldUseAgent(message) && !isAgentMode) {
                chatModeManager.setManualMode(ChatModeManager.ChatMode.AGENT);
                if (currentModeText != null) currentModeText.setText("Agent");
                isAgentMode = true;
            }

            agentToolLoopCount = 0;
            currentStreamingContent = new StringBuilder();
            currentThinkingContent = new StringBuilder();
            isInThinking = enableThinking;
            currentStreamingMessageId = java.util.UUID.randomUUID().toString();
            resetStreamingState();

            ChatMessage initialMessage = ChatMessage.createAIMessage(currentStreamingMessageId, "", System.currentTimeMillis(), null, 0, 0);
            initialMessage.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.INITIALIZING);
            initialMessage.status = ChatMessage.MessageStatus.GENERATING;
            chatHistory.add(initialMessage);
            currentStreamingMessageIndex = chatHistory.size() - 1;
            if (chatAdapter != null) chatAdapter.notifyItemInserted(currentStreamingMessageIndex);
            scrollToBottom();

            beginGeneration();

            final String prompt = message;
            final int streamingIndex = currentStreamingMessageIndex;
            final String streamingId = currentStreamingMessageId;
            final boolean finalAgentMode = isAgentMode;
            final boolean finalEnableThinking = enableThinking;

            new Thread(() -> {
                try {
                    runOnUiThread(() -> updateInferencePhase(streamingIndex, ChatMessage.InferencePhase.INITIALIZING, null));
                    
                    if (!aiService.isInitialized()) {
                        if (!aiService.initializeSafe()) { handleGenerationError("AI服务初始化失败"); return; }
                    }

                    boolean shouldUseAgent = finalAgentMode;
                    boolean shouldEnableThinking = finalEnableThinking;
                    
                    if (!aiService.isChatContextActive() || needRebuildContext) {
                        runOnUiThread(() -> updateInferencePhase(streamingIndex, ChatMessage.InferencePhase.PREFILL, "正在构建上下文..."));
                        
                        shouldUseAgent = chatModeManager != null
                            && chatModeManager.getCurrentMode() == ChatModeManager.ChatMode.AGENT;
                        shouldEnableThinking = chatModeManager != null
                            && chatModeManager.getCurrentMode() == ChatModeManager.ChatMode.DEEP_THINKING;
                        
                        String systemPrompt = getSystemPromptForMode();
                        String globalPrompt = "你是一个AI助手，请用中文回答。";
                        String normalPrompt = getNormalPromptForMode();
                        
                        long startTime = System.currentTimeMillis();
                        AppLogger.ai(TAG, "Creating chat context: globalPromptLen=" + globalPrompt.length() + 
                                ", systemPromptLen=" + systemPrompt.length() + 
                                ", normalPromptLen=" + normalPrompt.length() +
                                ", currentMode=" + (chatModeManager != null ? chatModeManager.getCurrentMode().displayName : "null"));
                        
                        boolean ctxResult = aiService.initChatContext(globalPrompt, systemPrompt, normalPrompt);
                        
                        long elapsed = System.currentTimeMillis() - startTime;
                        AppLogger.ai(TAG, "Chat context created in " + elapsed + "ms, result=" + ctxResult);
                        
                        if (!ctxResult) {
                            runOnUiThread(() -> {
                                endGeneration();
                                addSystemMessage("创建对话上下文失败");
                            });
                            return;
                        }
                        
                        needRebuildContext = false;
                    }

                    if (shouldUseAgent && agentChatHandler != null) {
                        agentChatHandler.startAgentLoop(prompt, 4096, shouldEnableThinking);
                    } else {
                        runOnUiThread(() -> updateInferencePhase(streamingIndex, ChatMessage.InferencePhase.ENCODING, "正在编码输入..."));
                        long chatStartTime = System.currentTimeMillis();
                        AppLogger.ai(TAG, "Calling aiService.chatSend: promptLen=" + prompt.length() + 
                                ", maxTokens=4096, thinking=" + shouldEnableThinking);
                        
                        aiService.chatSend(prompt, 4096, shouldEnableThinking, new StreamingTokenHandler(streamingIndex, streamingId, prompt, chatStartTime));
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

        StreamingTokenHandler(int streamingIndex, String streamingId, String prompt, long chatStartTime) {
            this.streamingIndex = streamingIndex;
            this.streamingId = streamingId;
            this.prompt = prompt;
            this.chatStartTime = chatStartTime;
        }

        @Override
        public void onToken(String token) {
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
            if (fullText != null && fullText.contains("<|tool_call_begin|>") && agentService != null && agentToolLoopCount < agentService.getMaxToolLoops()) {
                List<AgentService.ToolCall> toolCalls = agentService.parseToolCalls(fullText);
                if (!toolCalls.isEmpty()) {
                    AgentService.ToolCall call = toolCalls.get(0);
                    agentToolLoopCount++;
                    runOnUiThread(() -> appendToolCallInProgress(call.name));

                    new Thread(() -> {
                        try {
                            AgentService.ToolResult result = agentService.executeTool(call);
                            String toolResultMsg = agentService.formatToolResultForContext(result);

                            runOnUiThread(() -> {
                                if (currentStreamingContent != null) {
                                    currentStreamingContent.append("\n" + (result.success ? "✅" : "❌") + " 工具结果\n");
                                    safeUpdateMessage();
                                }
                            });

                            aiService.chatSend(toolResultMsg, 4096, false, new LlamaHelper.TokenCallback() {
                                @Override
                                public void onToken(String token) {
                                    runOnUiThread(() -> handleStreamToken(token));
                                }
                                @Override
                                public void onComplete(String text) { completeGeneration(text); }
                                @Override
                                public void onError(String error) {
                                    if ("[TOOL_CALL]".equals(error)) return;
                                    runOnUiThread(() -> handleGenerationError("工具调用后出错: " + error));
                                }
                            });
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
            if ("[TOOL_CALL]".equals(error)) return;
            runOnUiThread(() -> {
                endGeneration();
                if (currentStreamingContent != null && currentStreamingContent.length() > 0 && currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                    ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
                    msg.content = currentStreamingContent.toString();
                    msg.status = ChatMessage.MessageStatus.COMPLETED;
                    if (chatAdapter != null) chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
                    addSystemMessage("生成中断，已保存部分内容");
                } else if (currentStreamingMessageIndex >= 0) {
                    chatHistory.remove(currentStreamingMessageIndex);
                    if (chatAdapter != null) chatAdapter.notifyItemRemoved(currentStreamingMessageIndex);
                }
                saveHistoryAsync();
                currentStreamingContent = null;
                currentStreamingMessageIndex = -1;
                currentStreamingMessageId = null;
                addErrorMessage("生成出错", error, true);
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
            endGeneration();
            agentToolLoopCount = 0;
            if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
                String content = currentStreamingContent != null ? currentStreamingContent.toString() : fullText;
                ChatMessage finalMsg = chatHistory.get(currentStreamingMessageIndex);
                finalMsg.content = content;
                finalMsg.status = ChatMessage.MessageStatus.COMPLETED;
                
                if (tokenCount > 0) {
                    finalMsg.tokensGenerated = tokenCount;
                } else if (content != null && !content.isEmpty()) {
                    finalMsg.tokensGenerated = content.length() / 4;
                }
                
                if (chatStartTime > 0) {
                    finalMsg.generationTimeMs = System.currentTimeMillis() - chatStartTime;
                }
                
                if (finalMsg.inferenceProgress != null) {
                    finalMsg.inferenceProgress.phase = ChatMessage.InferencePhase.COMPLETED;
                }
                
                if (currentThinkingContent != null && currentThinkingContent.length() > 0) finalMsg.thinkingContent = currentThinkingContent.toString();
                if (chatAdapter != null) chatAdapter.notifyItemChanged(currentStreamingMessageIndex);
                saveHistoryAsync();

                if (cacheManager != null && aiConfig != null && aiConfig.isCacheEnabled() && content != null && !content.isEmpty()) {
                    String prompt = currentStreamingMessageIndex >= 1 ? chatHistory.get(currentStreamingMessageIndex - 1).content : "";
                    if (!prompt.isEmpty()) cacheManager.cacheResponse(prompt, content);
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
            if (currentStreamingContent != null) currentStreamingContent.append("\n" + (result.success ? "✅" : "❌"));
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
            runOnUiThread(() -> { if (currentModeText != null) currentModeText.setText(mode); });
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

        if (chatModeManager != null) chatModeManager.setGeneratingState(true);
        if (btnStopGeneration != null) btnStopGeneration.setVisibility(View.VISIBLE);
        if (thinkingIndicator != null) thinkingIndicator.setVisibility(View.VISIBLE);
    }

    private void endGeneration() {
        isGenerating = false;
        isDirectStreaming = false;
        
        if (streamingUpdateManager != null) {
            streamingUpdateManager.flush();
            streamingUpdateManager = null;
        }
        
        hideLoadingUI();
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
        if (loadingLayout != null) { loadingMessage.setText("执行工具中"); loadingSubmessage.setText("正在处理..."); loadingLayout.setVisibility(View.VISIBLE); }
        aiToolsManager.executeTool(toolName, parameters).thenAccept(result -> runOnUiThread(() -> {
            if (loadingLayout != null) loadingLayout.setVisibility(View.GONE);
            addAIMessage(result);
        })).exceptionally(throwable -> {
            runOnUiThread(() -> {
                if (loadingLayout != null) loadingLayout.setVisibility(View.GONE);
                addSystemMessage("工具执行出错: " + throwable.getMessage());
            });
            return null;
        });
    }

    private void executeEntertainment(String type, String parameters) {
        if (loadingLayout != null) { loadingMessage.setText("娱乐中"); loadingSubmessage.setText("正在准备..."); loadingLayout.setVisibility(View.VISIBLE); }
        aiEntertainmentManager.executeEntertainment(type, parameters).thenAccept(result -> runOnUiThread(() -> {
            if (loadingLayout != null) loadingLayout.setVisibility(View.GONE);
            addAIMessage(result);
        })).exceptionally(throwable -> {
            runOnUiThread(() -> {
                if (loadingLayout != null) loadingLayout.setVisibility(View.GONE);
                addSystemMessage("娱乐功能出错: " + throwable.getMessage());
            });
            return null;
        });
    }

    // ===================== Mode Configuration =====================

    private String getSystemPromptForMode() {
        ChatModeManager.ChatMode currentMode = chatModeManager != null ? chatModeManager.getCurrentMode() : ChatModeManager.ChatMode.NORMAL;
        switch (currentMode) {
            case DEEP_THINKING:
                return "你是一位深度思考专家。请逐步推理，展示思考过程，给出清晰答案。";
            case CREATIVE:
                return "你是一位创意大师。写作注重文采和修辞，诗歌注重韵律意境，故事要有生动情节。请用富有艺术感的语言回应。";
            case AGENT:
                if (agentService != null) return agentService.buildToolSystemPrompt();
                return "你是一位智能任务执行专家，擅长分析问题、制定计划、分解任务。请以清晰、有条理的方式给出方案。";
            default:
                return "你是一位友好、专业的AI助手。回答准确简洁，保持礼貌耐心，必要时提供示例。请以自然易懂的方式回应。";
        }
    }

    private String getNormalPromptForMode() {
        ChatModeManager.ChatMode currentMode = chatModeManager != null ? chatModeManager.getCurrentMode() : ChatModeManager.ChatMode.NORMAL;
        switch (currentMode) {
            case DEEP_THINKING: return "鼓励详细推理，支持引用知识，可使用Markdown格式，保持逻辑严谨。";
            case CREATIVE: return "鼓励创意表达和艺术化语言，可适当打破常规思维，注重情感表达。";
            case AGENT: return "优先考虑使用工具完成任务，如需调用工具请使用指定格式，完成后提供详细总结。";
            default: return "";
        }
    }

    private ModeInfo findModeById(String modeId) {
        for (ModeInfo mode : ModeInfo.getDefaultModes()) {
            if (mode.id.equals(modeId)) return mode;
        }
        return ModeInfo.createNormalMode();
    }

    private void updateModeDisplay(ModeInfo mode) {
        if (currentModeText != null) currentModeText.setText(mode.icon + " " + mode.name);
    }

    private void updateModelNameDisplay() {
        if (aiService != null) {
            String name = aiService.getCurrentModelName();
            modelNameText.setText(name != null && !name.isEmpty() ? name : "未选择模型");
        } else modelNameText.setText("AI服务未初始化");
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
        } catch (Exception e) { AppLogger.aiE(TAG, "Error clearing chat: " + e.getMessage()); }
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
        if (loadingLayout != null) loadingLayout.setVisibility(View.GONE);
        if (btnStopGeneration != null) btnStopGeneration.setVisibility(View.GONE);
        if (thinkingIndicator != null) thinkingIndicator.setVisibility(View.GONE);
        if (chatModeManager != null) chatModeManager.setGeneratingState(false);
    }

    private boolean ensureModelLoaded(String pendingMessage) {
        if (aiService == null) { showToast("AI服务未初始化"); return false; }
        boolean modelInMemory = LlamaHelper.isModelInitialized();
        if (!modelInMemory || !aiService.isInitialized()) {
            isLoadingModel = true;
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
        if (loadingLayout != null) loadingLayout.setVisibility(View.VISIBLE);
        if (loadingMessage != null) loadingMessage.setText(message);
        if (loadingSubmessage != null) loadingSubmessage.setText(submessage);
        if (thinkingIndicator != null) thinkingIndicator.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        if (loadingLayout != null) loadingLayout.setVisibility(View.GONE);
        if (thinkingIndicator != null) thinkingIndicator.setVisibility(View.GONE);
    }

    // ===================== Message Adders =====================

    private void addUserMessage(String message) {
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
            if (chatAdapter != null && chatAdapter.getItemCount() > 0) messageList.scrollToPosition(chatAdapter.getItemCount() - 1);
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
            case SHOW_GUIDE: handleShowGuideCommand(); break;
            case VIEW_TOOL_DETAILS:
                showToast(action.messageId != null ? "查看工具详情: " + action.messageId : "查看工具详情");
                break;
            case EXPORT_SUMMARY:
                showToast(action.messageId != null ? "导出总结: " + action.messageId : "导出总结");
                break;
            case REPORT_ERROR:
                String errorMsg = action.content != null ? action.content : "未知错误";
                addSystemMessage("已收到错误报告: " + errorMsg);
                showToast("错误已报告，感谢您的反馈！");
                break;
        }
    }

    // ===================== Help & Guide =====================

    private void handleHelpCommand() {
        addAIMessage("可用功能：\n**应用功能：**生成题目、分析题目、翻译、学习计划、统计、搜索题目、天气、导入/导出题目、数据库操作\n**娱乐功能：**讲笑话、猜谜语、写诗、讲故事、知识问答、名言、游戏\n输入 '教程' 查看详细使用指南。");
    }

    private void handleShowGuideCommand() { addAIMessage(AIToolUsageGuide.getUsageGuide()); }

    // ===================== UI Init =====================

    private void initModeList() {
        if (modeList == null) return;
        modeList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        List<ModeInfo> modes = ModeInfo.getDefaultModes();
        chatModeAdapter = new ChatModeAdapter(modes, mode -> {
            if (chatModeManager != null) chatModeManager.setManualMode(ChatModeManager.ChatMode.fromModeId(mode.id));
            updateModeDisplay(mode);
        });
        modeList.setAdapter(chatModeAdapter);
    }

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
        new Thread(() -> {
            try {
                String result = weatherManager.getCurrentWeather("北京").get();
                if (result != null && !result.isEmpty()) {
                    runOnUiThread(() -> {
                        try {
                            org.json.JSONObject json = new org.json.JSONObject(result);
                            String cityName = json.optString("city", "北京");
                            String tempStr = json.optString("temperature", "--");
                            String condition = json.optString("condition", "--");
                            if (weatherIcon != null) weatherIcon.setText("");
                            if (weatherCity != null) weatherCity.setText(cityName);
                            if (weatherTemp != null) weatherTemp.setText(tempStr);
                            if (weatherDesc != null) weatherDesc.setText(condition);
                            weatherBanner.setVisibility(View.VISIBLE);
                            weatherBannerVisible = true;
                        } catch (Exception e) { /* ignore parse errors */ }
                    });
                }
            } catch (Exception ignored) {}
        }).start();
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

    // ===================== Voice Input =====================

    private void handleVoiceInput() {
        AppResourceManager resources = AppResourceManager.getInstance(this);
        if (!resources.hasMicrophonePermission()) {
            resources.permissions().requestMicrophonePermission(this, new PermissionResourceProvider.PermissionCallback() {
                @Override public void onGranted() { showToast("请再次点击语音按钮"); }
                @Override public void onDenied(List<String> denied) { showToast("录音权限被拒绝"); }
            });
            return;
        }
        if (speechManager == null) speechManager = new SpeechRecognizerManager(this);
        if (isVoiceInputActive) { speechManager.stopListening(); isVoiceInputActive = false; if (btnVoice != null) btnVoice.setBackgroundColor(ContextCompat.getColor(this, R.color.surface)); return; }
        speechManager.setCallback(new VoiceCallback());
        speechManager.startListening();
    }

    private class VoiceCallback implements SpeechRecognizerManager.SpeechCallback {
        @Override public void onReadyForSpeech() {
            runOnUiThread(() -> { isVoiceInputActive = true; if (btnVoice != null) btnVoice.setBackgroundColor(ContextCompat.getColor(AIChatActivity.this, R.color.primary)); showToast("请说话..."); });
        }
        @Override public void onBeginningOfSpeech() {}
        @Override public void onEndOfSpeech() { runOnUiThread(() -> { if (btnVoice != null) btnVoice.setBackgroundColor(ContextCompat.getColor(AIChatActivity.this, R.color.surface)); }); }
        @Override public void onPartialResult(String text) {
            runOnUiThread(() -> { if (inputMessage != null && text != null) { inputMessage.setText(text); inputMessage.setSelection(text.length()); } });
        }
        @Override public void onResult(String text, int resultCode) {
            runOnUiThread(() -> {
                isVoiceInputActive = false;
                if (btnVoice != null) btnVoice.setBackgroundColor(ContextCompat.getColor(AIChatActivity.this, R.color.surface));
                if (resultCode == SpeechRecognizerManager.RESULT_SUCCESS && text != null && !text.isEmpty()) { inputMessage.setText(text); inputMessage.setSelection(text.length()); }
                else if (resultCode == SpeechRecognizerManager.RESULT_NO_MATCH) showToast("未能识别");
                else showToast("语音识别失败");
            });
        }
        @Override public void onError(int errorCode, String errorMessage) {
            runOnUiThread(() -> { isVoiceInputActive = false; if (btnVoice != null) btnVoice.setBackgroundColor(ContextCompat.getColor(AIChatActivity.this, R.color.surface)); showToast("语音错误: " + errorMessage); });
        }
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
            unregisterAIStatusObserver();
            unregisterComponentCallbacks(memoryCallback);
            if (localBroadcastManager != null && aiResultReceiver != null) { try { localBroadcastManager.unregisterReceiver(aiResultReceiver); } catch (Exception e) {} }
            if (localBroadcastManager != null && aiTokenReceiver != null) { try { localBroadcastManager.unregisterReceiver(aiTokenReceiver); } catch (Exception e) {} }
            if (agentChatHandler != null && agentChatHandler.isGenerating()) agentChatHandler.cancel();
            if (aiService != null) aiService.chatStop();
            if (speechManager != null) { speechManager.stopListening(); speechManager.release(); }
            if (ttsEngine != null) { ttsEngine.stop(); ttsEngine.shutdown(); }
            uiHandler.removeCallbacksAndMessages(null);
            isGenerating = false; isDirectStreaming = false;
        } catch (Exception e) { AppLogger.aiE(TAG, "Error onDestroy: " + e.getMessage()); }
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
        if (!isLoadingModel) return;
        
        String stageIcon = getStageIcon(stage);
        String stageName = getStageDisplayName(stage);
        String formattedMessage = String.format("%s %s [%d%%]\n已耗时: %.1f秒", 
            stageIcon, message != null ? message : stageName, 
            progress, elapsedMs / 1000.0);
        
        updateLoadingUI(message, formattedMessage, progress);
    }

    private void handleAIServiceError(String errorMessage) {
        isLoadingModel = false;
        hideLoading();
        addErrorMessage("AI服务初始化失败", errorMessage, true);
        showToast("模型加载失败");
    }

    private void handleAIServiceInitialized(String modelName, long loadTimeMs) {
        isLoadingModel = false;
        hideLoading();
        String successMsg = String.format("✓ 模型加载完成\n模型: %s\n耗时: %.1f秒", 
            modelName != null ? modelName : "未知", loadTimeMs / 1000.0);
        addSystemMessage(successMsg, ChatMessage.SystemMessageType.SUCCESS);
        showToast("模型加载成功");
        updateModelNameDisplay();
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
        if (loadingLayout != null) loadingLayout.setVisibility(View.VISIBLE);
        if (loadingMessage != null) loadingMessage.setText(message);
        if (loadingSubmessage != null) loadingSubmessage.setText(submessage);
        if (thinkingIndicator != null) thinkingIndicator.setVisibility(View.VISIBLE);
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
        if (stats == null || currentStreamingMessageIndex < 0 || currentStreamingMessageIndex >= chatHistory.size()) {
            return;
        }
        
        if (chatAdapter != null) {
            chatAdapter.updateMessageGenerationStats(currentStreamingMessageIndex, stats.totalTokens, stats.elapsedMs);
        }
    }
}