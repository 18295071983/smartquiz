package com.oilquiz.app.ai.chat;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;


import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 推理状态管理器
 * 
 * 职责：
 * 1. 管理每个消息ID的推理生命周期状态
 * 2. 提供Native层事件到UI状态的映射
 * 3. 实现超时检测和自动恢复机制
 * 4. 确保UI线程安全的状态更新
 */
public class InferenceStateManager {
    private static final String TAG = "InferenceStateManager";
    
    // 超时配置（毫秒）
    private static final long TIMEOUT_INITIALIZATION = 30000;  // 初始化30秒
    private static final long TIMEOUT_INFERENCE = 120000;      // 推理120秒
    private static final long TIMEOUT_HEARTBEAT = 15000;       // 心跳15秒
    private static final long TIMEOUT_MODEL_LOADING = 60000;   // 模型加载60秒
    
    // 状态映射表
    private final Map<String, StateDetails> stateMap = new ConcurrentHashMap<>();
    private final Map<String, TimeoutHandler> timeoutHandlers = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // 状态监听器
    private StateChangeListener stateChangeListener;
    private TimeoutListener timeoutListener;
    
    private static volatile InferenceStateManager instance;
    
    public static InferenceStateManager getInstance() {
        if (instance == null) {
            synchronized (InferenceStateManager.class) {
                if (instance == null) {
                    instance = new InferenceStateManager();
                }
            }
        }
        return instance;
    }
    
    private InferenceStateManager() {}
    
    /**
     * 推理状态枚举
     */
    public enum InferenceState {
        IDLE,                    // 空闲
        INITIALIZING,           // 初始化中
        MODEL_LOADING,          // 模型加载中
        PROMPT_ENCODING,        // 提示词编码中
        PREFILL,               // 预填充阶段
        GENERATING,            // 生成中
        THINKING,              // 思考中（DeepSeek等模型）
        DECODING,              // 解码中
        COMPLETED,             // 完成
        FAILED,                // 失败
        TIMEOUT,               // 超时
        CANCELLED;             // 已取消

        /**
         * 检查状态是否处于处理中
         */
        public boolean isProcessing() {
            return this != IDLE && this != COMPLETED && this != FAILED 
                && this != TIMEOUT && this != CANCELLED;
        }
    }
    
    /**
     * 状态详情类
     */
    public static class StateDetails {
        public final String messageId;
        public InferenceState currentState;
        public InferenceState previousState;
        public long stateStartTime;
        public long totalStartTime;
        public int progressPercent;
        public int processedTokens;
        public int totalTokens;
        public float tokensPerSecond;
        public String currentPhase;
        public String additionalInfo;
        public final AtomicLong lastHeartbeat;
        public final AtomicBoolean isActive;
        
        public StateDetails(String messageId) {
            this.messageId = messageId;
            this.currentState = InferenceState.IDLE;
            this.previousState = InferenceState.IDLE;
            this.stateStartTime = System.currentTimeMillis();
            this.totalStartTime = System.currentTimeMillis();
            this.progressPercent = 0;
            this.processedTokens = 0;
            this.totalTokens = 0;
            this.tokensPerSecond = 0;
            this.currentPhase = "";
            this.additionalInfo = "";
            this.lastHeartbeat = new AtomicLong(System.currentTimeMillis());
            this.isActive = new AtomicBoolean(true);
        }
        
        public long getElapsedTime() {
            return System.currentTimeMillis() - totalStartTime;
        }
        
        
        public long getTimeSinceLastHeartbeat() {
            return System.currentTimeMillis() - lastHeartbeat.get();
        }
        
    }
    
    /**
     * 状态转换
     */
    private void transitionToState(String messageId, InferenceState newState) {
        StateDetails details = stateMap.get(messageId);
        if (details == null) return;
        
        InferenceState oldState = details.currentState;
        if (oldState == newState) return;

        // 合法转移校验（业内标准转移表）：
        // - 终态（COMPLETED/FAILED/TIMEOUT/CANCELLED）后禁止任何转移（cleanup 会移除）
        // - 运行态之间的转移按事件路径校验，非法迁移拒绝并告警（防御性，不打断推理）
        if (isTerminal(oldState)) {
            Log.w(TAG, String.format("Illegal transition from terminal state: %s -> %s (message: %s)",
                    oldState, newState, messageId));
            return;
        }
        if (!isValidTransition(oldState, newState)) {
            Log.w(TAG, String.format("Illegal transition: %s -> %s (message: %s)",
                    oldState, newState, messageId));
            return;
        }
        
        details.previousState = oldState;
        details.currentState = newState;
        details.stateStartTime = System.currentTimeMillis();
        
        Log.d(TAG, String.format("State transition: %s -> %s (message: %s)", 
            oldState, newState, messageId));
        
        // 通知监听器（主线程）
        if (stateChangeListener != null) {
            mainHandler.post(() -> {
                if (stateChangeListener != null) {
                    stateChangeListener.onStateChanged(messageId, oldState, newState, details);
                }
            });
        }
    }

    /** 终态判定：终态后不允许任何转移 */
    private static boolean isTerminal(InferenceState s) {
        return s == InferenceState.COMPLETED
                || s == InferenceState.FAILED
                || s == InferenceState.TIMEOUT
                || s == InferenceState.CANCELLED;
    }

    /**
     * 合法转移表（宽松式）：覆盖全部事件驱动路径（Native/流式/超时/取消）。
     * 终态为 sink，不在此表中；运行态间迁移按此校验。
     */
    private static boolean isValidTransition(InferenceState from, InferenceState to) {
        if (to == InferenceState.COMPLETED || to == InferenceState.FAILED
                || to == InferenceState.TIMEOUT || to == InferenceState.CANCELLED) {
            return true; // 任何运行态都可进入终态（完成/失败/超时/取消）
        }
        switch (from) {
            case IDLE:
                return to == InferenceState.INITIALIZING;
            case INITIALIZING:
                return to == InferenceState.MODEL_LOADING
                        || to == InferenceState.PROMPT_ENCODING
                        || to == InferenceState.PREFILL
                        || to == InferenceState.GENERATING
                        || to == InferenceState.THINKING;
            case MODEL_LOADING:
                return to == InferenceState.PROMPT_ENCODING
                        || to == InferenceState.GENERATING
                        || to == InferenceState.THINKING;
            case PROMPT_ENCODING:
                return to == InferenceState.PREFILL
                        || to == InferenceState.GENERATING
                        || to == InferenceState.THINKING;
            case PREFILL:
                return to == InferenceState.GENERATING
                        || to == InferenceState.THINKING;
            case GENERATING:
                return to == InferenceState.THINKING
                        || to == InferenceState.DECODING;
            case THINKING:
                return to == InferenceState.GENERATING
                        || to == InferenceState.DECODING;
            case DECODING:
                return to == InferenceState.GENERATING;
            default:
                return false;
        }
    }
    
    /**
     * 更新进度
     */
    private void updateProgress(String messageId) {
        StateDetails details = stateMap.get(messageId);
        if (details == null) return;
        
        // 计算进度百分比
        if (details.totalTokens > 0) {
            details.progressPercent = Math.min(100, 
                (details.processedTokens * 100) / details.totalTokens);
        } else {
            // 基于状态的默认进度
            details.progressPercent = calculateDefaultProgress(details.currentState);
        }
        
        // 通知进度更新
        if (stateChangeListener != null) {
            mainHandler.post(() -> {
                if (stateChangeListener != null) {
                    stateChangeListener.onProgressUpdated(messageId, details);
                }
            });
        }
    }
    
    /**
     * 计算默认进度（当总token数未知时）
     */
    private int calculateDefaultProgress(InferenceState state) {
        switch (state) {
            case INITIALIZING: return 5;
            case MODEL_LOADING: return 15;
            case PROMPT_ENCODING: return 25;
            case PREFILL: return 35;
            case THINKING: return 50;
            case GENERATING: return 70;
            case DECODING: return 90;
            case COMPLETED: return 100;
            default: return 0;
        }
    }
    
    /**
     * 启动超时监控
     */
    private void startTimeoutMonitor(String messageId) {
        TimeoutHandler handler = new TimeoutHandler(messageId);
        timeoutHandlers.put(messageId, handler);
        handler.start();
    }
    
    /**
     * 超时处理器
     */
    private class TimeoutHandler {
        private final String messageId;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private Runnable timeoutRunnable;
        private volatile boolean isRunning = true;
        
        TimeoutHandler(String messageId) {
            this.messageId = messageId;
        }
        
        void start() {
            // 心跳检测（每5秒检查一次）
            Runnable heartbeatCheck = new Runnable() {
                @Override
                public void run() {
                    if (!isRunning) return;
                    
                    StateDetails details = stateMap.get(messageId);
                    if (details == null || !details.isActive.get()) {
                        return;
                    }
                    
                    // 检查心跳超时
                    long timeSinceHeartbeat = details.getTimeSinceLastHeartbeat();
                    long timeoutThreshold = getTimeoutForState(details.currentState);
                    
                    if (timeSinceHeartbeat > timeoutThreshold) {
                        Log.w(TAG, String.format("Timeout detected for message %s: state=%s, " +
                            "timeSinceHeartbeat=%dms, threshold=%dms", 
                            messageId, details.currentState, timeSinceHeartbeat, timeoutThreshold));
                        
                        handleTimeout(messageId, details);
                        return;
                    }
                    
                    // 继续检测
                    if (isRunning) {
                        handler.postDelayed(this, 5000);
                    }
                }
            };
            
            handler.postDelayed(heartbeatCheck, 5000);
            this.timeoutRunnable = heartbeatCheck;
        }
        
        void stop() {
            isRunning = false;
            if (timeoutRunnable != null) {
                handler.removeCallbacks(timeoutRunnable);
            }
        }
    }
    
    /**
     * 获取当前状态的超时时间
     */
    private long getTimeoutForState(InferenceState state) {
        switch (state) {
            case INITIALIZING:
                return TIMEOUT_INITIALIZATION;
            case MODEL_LOADING:
                return TIMEOUT_MODEL_LOADING;
            case GENERATING:
            case THINKING:
                return TIMEOUT_INFERENCE;
            default:
                return TIMEOUT_HEARTBEAT;
        }
    }
    
    /**
     * 处理超时
     */
    private void handleTimeout(String messageId, StateDetails details) {
        details.isActive.set(false);
        transitionToState(messageId, InferenceState.TIMEOUT);
        
        // 通知超时监听器
        if (timeoutListener != null) {
            mainHandler.post(() -> {
                if (timeoutListener != null) {
                    timeoutListener.onTimeout(messageId, details.currentState, 
                        details.getTimeSinceLastHeartbeat());
                }
            });
        }
        
        cleanup(messageId);
    }
    
    /**
     * 获取当前状态详情
     */
    public StateDetails getStateDetails(String messageId) {
        return stateMap.get(messageId);
    }
    
    /**
     * 获取当前状态
     */
    public InferenceState getCurrentState(String messageId) {
        StateDetails details = stateMap.get(messageId);
        return details != null ? details.currentState : InferenceState.IDLE;
    }
    
    /**
     * 清理资源
     */
    private void cleanup(String messageId) {
        TimeoutHandler handler = timeoutHandlers.remove(messageId);
        if (handler != null) {
            handler.stop();
        }
        
        // 延迟清理状态映射，允许UI获取最终状态
        mainHandler.postDelayed(() -> {
            stateMap.remove(messageId);
            Log.d(TAG, "Cleaned up state for message: " + messageId);
        }, 5000);
    }
    
    /**
     * 设置状态变更监听器
     */
    public void setStateChangeListener(StateChangeListener listener) {
        this.stateChangeListener = listener;
    }
    
    /**
     * 状态变更监听器接口
     */
    public interface StateChangeListener {
        void onStateChanged(String messageId, InferenceState oldState, 
                           InferenceState newState, StateDetails details);
        void onProgressUpdated(String messageId, StateDetails details);
    }
    
    /**
     * 超时监听器接口
     */
    public interface TimeoutListener {
        void onTimeout(String messageId, InferenceState lastState, long elapsedTimeMs);
    }
    
}
