package com.oilquiz.app.ai.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.oilquiz.app.util.AILogger;
import com.oilquiz.app.ai.util.PromptBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI处理服务 - 异步AI任务处理器
 * 
 * 功能：
 * - 处理AI聊天、Agent、Tool等异步任务
 * - 提供实时日志广播功能
 * - 管理CompletableFuture异步回调
 * 
 * 任务类型：
 * - TASK_TYPE_CHAT: 聊天对话
 * - TASK_TYPE_AGENT: Agent代理任务
 * - TASK_TYPE_TOOL: 工具调用任务
 * 
 * 广播Action：
 * - ACTION_PROCESS_AI_TASK: 处理AI任务
 * - ACTION_AI_TASK_COMPLETED: 任务完成
 * - ACTION_AI_LOG_UPDATE: 日志更新
 * 
 * 使用方式：
 * Intent intent = new Intent(AIProcessingService.ACTION_PROCESS_AI_TASK);
 * intent.putExtra(AIProcessingService.EXTRA_TASK_TYPE, AIProcessingService.TASK_TYPE_CHAT);
 * intent.putExtra(AIProcessingService.EXTRA_PROMPT, "Hello AI");
 * intent.putExtra(AIProcessingService.EXTRA_MAX_TOKENS, 1000);
 * startService(intent);
 * 
 * @author AI Team
 * @since 2024
 */
public class AIProcessingService extends Service {

    private static final String TAG = "AIProcessingService";
    public static final String ACTION_PROCESS_AI_TASK = "com.oilquiz.app.ACTION_PROCESS_AI_TASK";
    public static final String ACTION_AI_TASK_COMPLETED = "com.oilquiz.app.ACTION_AI_TASK_COMPLETED";
    public static final String ACTION_AI_LOG_UPDATE = "com.oilquiz.app.ACTION_AI_LOG_UPDATE";
    public static final String ACTION_AI_TOKEN_UPDATE = "com.oilquiz.app.ACTION_AI_TOKEN_UPDATE"; // 流式token更新
    public static final String EXTRA_TASK_TYPE = "task_type";
    public static final String EXTRA_PROMPT = "prompt";
    public static final String EXTRA_MAX_TOKENS = "max_tokens";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_LOG_MESSAGE = "log_message";
    public static final String EXTRA_LOG_LEVEL = "log_level";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_HISTORY_USER = "history_user";
    public static final String EXTRA_HISTORY_ASSISTANT = "history_assistant";
    public static final String EXTRA_SYSTEM_PROMPT = "system_prompt";
    public static final String EXTRA_THINKING_INSTRUCTION = "thinking_instruction";
    public static final String EXTRA_MODE_ID = "mode_id";

    public static final int TASK_TYPE_CHAT = 1;
    public static final int TASK_TYPE_AGENT = 2;
    public static final int TASK_TYPE_TOOL = 3;

    public static final String LOG_LEVEL_INFO = "INFO";
    public static final String LOG_LEVEL_ERROR = "ERROR";
    public static final String LOG_LEVEL_WARN = "WARN";

    // 前台服务通知常量
    private static final String CHANNEL_ID = "ai_processing_service";
    private static final String CHANNEL_NAME = "AI 处理服务";
    private static final String CHANNEL_DESCRIPTION = "AI 模型处理和推理服务";
    private static final int NOTIFICATION_ID = 1001;

    private AIService aiService;

    private final ExecutorService workExecutor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "AIProcess-Worker");
        t.setDaemon(true);
        return t;
    });

    private void sendLogBroadcast(String level, String message) {
        Intent logIntent = new Intent(ACTION_AI_LOG_UPDATE);
        logIntent.putExtra(EXTRA_LOG_LEVEL, level);
        logIntent.putExtra(EXTRA_LOG_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(logIntent);
    }
    
    private void sendTokenBroadcast(String token) {
        Intent tokenIntent = new Intent(ACTION_AI_TOKEN_UPDATE);
        tokenIntent.putExtra(EXTRA_TOKEN, token);
        LocalBroadcastManager.getInstance(this).sendBroadcast(tokenIntent);
    }

    /**
     * 创建通知通道（Android 8.0+）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(CHANNEL_DESCRIPTION);
            channel.setSound(null, null); // 静默通知
            channel.setVibrationPattern(new long[]{0});
            channel.enableVibration(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 启动前台服务
     */
    private void startForegroundService() {
        createNotificationChannel();
        
        Notification notification = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("AI 处理服务")
                    .setContentText("正在处理 AI 任务...")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setContentTitle("AI 处理服务")
                    .setContentText("正在处理 AI 任务...")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .build();
        }
        
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 绑定式服务，暂时返回 null
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 启动前台服务
        startForegroundService();
        
        String msg = "========== [onStartCommand] START ==========";
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);

        msg = "Intent: " + intent;
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);

        if (intent != null && intent.hasExtra(EXTRA_TASK_TYPE)) {
            int taskType = intent.getIntExtra(EXTRA_TASK_TYPE, TASK_TYPE_CHAT);
            String prompt = intent.getStringExtra(EXTRA_PROMPT);
            int maxTokens = intent.getIntExtra(EXTRA_MAX_TOKENS, 1000);
            ArrayList<String> historyUser = intent.getStringArrayListExtra(EXTRA_HISTORY_USER);
            ArrayList<String> historyAssistant = intent.getStringArrayListExtra(EXTRA_HISTORY_ASSISTANT);
            String systemPrompt = intent.getStringExtra(EXTRA_SYSTEM_PROMPT);
            String thinkingInstruction = intent.getStringExtra(EXTRA_THINKING_INSTRUCTION);
            String modeId = intent.getStringExtra(EXTRA_MODE_ID);

            msg = "Task Type: " + getTaskTypeName(taskType);
            Log.i(TAG, msg);
            AILogger.i(TAG, msg);
            sendLogBroadcast(LOG_LEVEL_INFO, msg);

            msg = "Max Tokens: " + maxTokens;
            Log.i(TAG, msg);
            AILogger.i(TAG, msg);
            sendLogBroadcast(LOG_LEVEL_INFO, msg);

            msg = "Prompt length: " + (prompt != null ? prompt.length() : 0);
            Log.i(TAG, msg);
            AILogger.i(TAG, msg);
            sendLogBroadcast(LOG_LEVEL_INFO, msg);

            msg = "Prompt preview: " + (prompt != null && prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt);
            Log.i(TAG, msg);
            AILogger.i(TAG, msg);
            sendLogBroadcast(LOG_LEVEL_INFO, msg);

            if (prompt == null || prompt.trim().isEmpty()) {
                msg = "ERROR: Prompt is null or empty!";
                Log.e(TAG, msg);
                AILogger.e(TAG, msg);
                sendLogBroadcast(LOG_LEVEL_ERROR, msg);
                sendTaskCompletedBroadcast(taskType, null, "错误: 消息内容为空");
                msg = "========== [onStartCommand] END (empty prompt) ==========";
                Log.i(TAG, msg);
                AILogger.i(TAG, msg);
                sendLogBroadcast(LOG_LEVEL_INFO, msg);
                return START_STICKY;
            }

            msg = "Calling processAITask...";
            Log.i(TAG, msg);
            AILogger.i(TAG, msg);
            sendLogBroadcast(LOG_LEVEL_INFO, msg);

            // 在新线程中处理任务
            workExecutor.execute(() -> processAITask(taskType, prompt, maxTokens, historyUser, historyAssistant, systemPrompt, thinkingInstruction, modeId));
            
            msg = "processAITask started in new thread";
            Log.i(TAG, msg);
            AILogger.i(TAG, msg);
            sendLogBroadcast(LOG_LEVEL_INFO, msg);
        } else {
            msg = "Unknown intent or no task type";
            Log.w(TAG, msg);
            AILogger.w(TAG, msg);
            sendLogBroadcast(LOG_LEVEL_WARN, msg);
        }
        msg = "========== [onStartCommand] END ==========";
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);

        // START_STICKY: 如果服务被系统杀了，会自动重启
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        String msg = "========== [AIProcessingService] onCreate ==========";
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);

        msg = "Service thread: " + Thread.currentThread().getName();
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);

        msg = "Service PID: " + android.os.Process.myPid();
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);

        msg = "Initializing AI service...";
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);

        aiService = AIService.getInstance(this);
        if (aiService != null) {
            msg = "AIService instance obtained";
            Log.i(TAG, msg);
            AILogger.i(TAG, msg);
            sendLogBroadcast(LOG_LEVEL_INFO, msg);
        } else {
            msg = "Failed to get AIService instance!";
            Log.e(TAG, msg);
            AILogger.e(TAG, msg);
            sendLogBroadcast(LOG_LEVEL_ERROR, msg);
        }

        msg = "==================================================";
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);
    }

    private String getTaskTypeName(int taskType) {
        switch (taskType) {
            case TASK_TYPE_CHAT: return "CHAT";
            case TASK_TYPE_AGENT: return "AGENT";
            case TASK_TYPE_TOOL: return "TOOL";
            default: return "UNKNOWN(" + taskType + ")";
        }
    }

    private void processAITask(int taskType, String prompt, int maxTokens, ArrayList<String> historyUser, ArrayList<String> historyAssistant, String systemPrompt, String thinkingInstruction, String modeId) {
        String msg = "========== [processAITask] START ==========";
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);

        msg = "TaskType: " + getTaskTypeName(taskType) + ", MaxTokens: " + maxTokens;
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);

        try {
            msg = "Checking AIService initialization...";
            Log.i(TAG, msg);
            AILogger.i(TAG, msg);
            sendLogBroadcast(LOG_LEVEL_INFO, msg);

            msg = "AIService instance: " + (aiService != null ? "OK" : "NULL");
            Log.i(TAG, msg);
            AILogger.i(TAG, msg);
            sendLogBroadcast(LOG_LEVEL_INFO, msg);

            if (aiService == null) {
                msg = "ERROR: AIService is null!";
                Log.e(TAG, msg);
                AILogger.e(TAG, msg);
                sendLogBroadcast(LOG_LEVEL_ERROR, msg);
                sendTaskCompletedBroadcast(taskType, null, "AI服务实例为空");
                return;
            }

            msg = "AIService.isInitialized(): " + aiService.isInitialized();
            Log.i(TAG, msg);
            AILogger.i(TAG, msg);
            sendLogBroadcast(LOG_LEVEL_INFO, msg);

            if (!aiService.isInitialized()) {
                msg = "AI service not initialized, attempting to initialize...";
                Log.i(TAG, msg);
                AILogger.i(TAG, msg);
                sendLogBroadcast(LOG_LEVEL_INFO, msg);

                boolean initialized = aiService.initializeSafe();
                msg = "Initialize result: " + initialized;
                Log.i(TAG, msg);
                AILogger.i(TAG, msg);
                sendLogBroadcast(LOG_LEVEL_INFO, msg);

                if (!initialized) {
                    msg = "ERROR: AI service initialization failed!";
                    Log.e(TAG, msg);
                    AILogger.e(TAG, msg);
                    sendLogBroadcast(LOG_LEVEL_ERROR, msg);
                    sendTaskCompletedBroadcast(taskType, null, "AI服务初始化失败，请先选择模型");
                    return;
                }

                msg = "AI service initialized successfully";
                Log.i(TAG, msg);
                AILogger.i(TAG, msg);
                sendLogBroadcast(LOG_LEVEL_INFO, msg);
            }

            msg = "Calling aiService.generateStream() for streaming...";
            Log.i(TAG, msg);
            AILogger.i(TAG, msg);
            sendLogBroadcast(LOG_LEVEL_INFO, msg);

            long startTime = System.currentTimeMillis();
            
            // 使用流式生成
            String generateMsg = "Calling aiService.generateStream...";
            Log.i(TAG, generateMsg);
            AILogger.i(TAG, generateMsg);
            sendLogBroadcast(LOG_LEVEL_INFO, generateMsg);
            
            List<PromptBuilder.Message> history = PromptBuilder.createHistoryFromAlternating(
                    historyUser != null ? historyUser : new ArrayList<>(), 
                    historyAssistant != null ? historyAssistant : new ArrayList<>());

            PromptBuilder.PromptRequest promptRequest = new PromptBuilder.PromptRequest()
                .history(history)
                .query(prompt);
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                promptRequest.system(systemPrompt);
            }
            if (thinkingInstruction != null && !thinkingInstruction.isEmpty()) {
                promptRequest.thinking(thinkingInstruction);
            }

            aiService.generateStream(prompt, history, maxTokens, promptRequest, new AIService.GenerateStreamCallback() {
                private StringBuilder fullResult = new StringBuilder();
                private int tokenLogCounter = 0;

                @Override
                public void onToken(String token) {
                    fullResult.append(token);
                    sendTokenBroadcast(token);
                    // 避免每个 token 都打日志/广播拖垮主线程与 LogViewer
                    if ((++tokenLogCounter & 0x3f) == 0) {
                        Log.d(TAG, "onToken stream progress, chars=" + fullResult.length());
                    }
                }
                
                @Override
                public void onSuccess(String fullText) {
                    long elapsed = System.currentTimeMillis() - startTime;

                    String localMsg = "onSuccess called! Elapsed: " + elapsed + "ms";
                    Log.i(TAG, localMsg);
                    AILogger.i(TAG, localMsg);
                    sendLogBroadcast(LOG_LEVEL_INFO, localMsg);

                    localMsg = "Result length: " + (fullText != null ? fullText.length() : 0);
                    Log.i(TAG, localMsg);
                    AILogger.i(TAG, localMsg);
                    sendLogBroadcast(LOG_LEVEL_INFO, localMsg);

                    localMsg = "Result preview: " + (fullText != null && fullText.length() > 100 ? fullText.substring(0, 100) + "..." : fullText);
                    Log.i(TAG, localMsg);
                    AILogger.i(TAG, localMsg);
                    sendLogBroadcast(LOG_LEVEL_INFO, localMsg);

                    sendTaskCompletedBroadcast(taskType, fullText, null);
                    localMsg = "Broadcast sent successfully";
                    Log.i(TAG, localMsg);
                    AILogger.i(TAG, localMsg);
                    sendLogBroadcast(LOG_LEVEL_INFO, localMsg);
                }
                
                @Override
                public void onError(Exception error) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    String localMsg = "onError called! Error after " + elapsed + "ms: " + error.getMessage();
                    Log.e(TAG, localMsg);
                    AILogger.e(TAG, localMsg);
                    sendLogBroadcast(LOG_LEVEL_ERROR, localMsg);
                    
                    if (error.getCause() != null) {
                        String causeMsg = "Cause: " + error.getCause().getMessage();
                        Log.e(TAG, causeMsg);
                        AILogger.e(TAG, causeMsg);
                        sendLogBroadcast(LOG_LEVEL_ERROR, causeMsg);
                    }
                    
                    sendTaskCompletedBroadcast(taskType, null, "生成失败: " + error.getMessage());
                }
            });
            
            msg = "CompletableFuture callbacks set up, service will continue processing";
            Log.i(TAG, msg);
            AILogger.i(TAG, msg);
            sendLogBroadcast(LOG_LEVEL_INFO, msg);

        } catch (Exception e) {
            msg = "Exception in processAITask: " + e.getMessage();
            Log.e(TAG, msg);
            AILogger.e(TAG, msg);
            sendLogBroadcast(LOG_LEVEL_ERROR, msg);
            sendTaskCompletedBroadcast(taskType, null, "处理AI任务时出错: " + e.getMessage());

        } catch (Throwable t) {
            msg = "Throwable caught in processAITask - possible native crash: " + t.getMessage();
            Log.e(TAG, msg);
            AILogger.e(TAG, msg, t);
            sendLogBroadcast(LOG_LEVEL_ERROR, msg);
            sendTaskCompletedBroadcast(taskType, null, "处理AI任务时发生严重错误，可能是模型或内存问题");
        }

        msg = "========== [processAITask] END ==========";
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);
    }

    private void sendTaskCompletedBroadcast(int taskType, String result, String error) {
        String msg = "========== [sendTaskCompletedBroadcast] START ==========";
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);

        msg = "TaskType: " + getTaskTypeName(taskType);
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);

        msg = "Result: " + (result != null ? "OK(length=" + result.length() + ")" : "null");
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);

        msg = "Error: " + (error != null ? error : "null");
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(error != null ? LOG_LEVEL_ERROR : LOG_LEVEL_INFO, msg);

        Intent broadcastIntent = new Intent(ACTION_AI_TASK_COMPLETED);
        broadcastIntent.putExtra(EXTRA_TASK_TYPE, taskType);
        broadcastIntent.putExtra(EXTRA_RESULT, result);
        broadcastIntent.putExtra(EXTRA_ERROR, error);

        msg = "Sending broadcast...";
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);
        msg = "Broadcast sent successfully";
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);

        msg = "========== [sendTaskCompletedBroadcast] END ==========";
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        sendLogBroadcast(LOG_LEVEL_INFO, msg);
    }

    @Override
    public void onDestroy() {
        String msg = "========== [AIProcessingService] onDestroy ==========";
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        msg = "Service being destroyed";
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        msg = "Thread: " + Thread.currentThread().getName();
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        msg = "==================================================";
        Log.i(TAG, msg);
        AILogger.i(TAG, msg);
        super.onDestroy();
    }
}
