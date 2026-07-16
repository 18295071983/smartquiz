package com.oilquiz.app.ai.chat;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.oilquiz.app.ai.chat.event.StreamingEvent;

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
        
        public long getCurrentStateDuration() {
            return System.currentTimeMillis() - stateStartTime;
        }
        
        public long getTimeSinceLastHeartbeat() {
            return System.currentTimeMillis() - lastHeartbeat.get();
        }
        
        public void updateHeartbeat() {
            lastHeartbeat.set(System.currentTimeMillis());
        }
    }
    
    /**
     * 开始新的推理会话
     */
    public void startInference(String messageId) {
        Log.i(TAG, "Starting inference for message: " + messageId);
        
        StateDetails details = new StateDetails(messageId);
        stateMap.put(messageId, details);
        
        // 进入初始化状态
        transitionToState(messageId, InferenceState.INITIALIZING);
        
        // 启动超时检测
        startTimeoutMonitor(messageId);
    }
    
    /**
     * 处理Native层事件并映射到UI状态
     */
    public void handleNativeEvent(String messageId, NativeEvent event) {
        StateDetails details = stateMap.get(messageId);
        if (details == null) {
            Log.w(TAG, "No state found for message: " + messageId);
            return;
        }
        
        // 更新心跳
        details.updateHeartbeat();
        
        switch (event.type) {
            case MODEL_LOAD_START:
                transitionToState(messageId, InferenceState.MODEL_LOADING);
                break;
                
            case MODEL_LOAD_COMPLETE:
                transitionToState(messageId, InferenceState.PROMPT_ENCODING);
                break;
                
            case ENCODING_COMPLETE:
                transitionToState(messageId, InferenceState.PREFILL);
                break;
                
            case PREFILL_COMPLETE:
                transitionToState(messageId, InferenceState.GENERATING);
                break;
                
            case TOKEN_GENERATED:
                if (details.currentState != InferenceState.GENERATING && 
                    details.currentState != InferenceState.THINKING) {
                    transitionToState(messageId, InferenceState.GENERATING);
                }
                if (event.tokenCount > 0) {
                    details.processedTokens = event.tokenCount;
                }
                if (event.tokensPerSecond > 0) {
                    details.tokensPerSecond = event.tokensPerSecond;
                }
                updateProgress(messageId);
                break;
                
            case THINKING_START:
                transitionToState(messageId, InferenceState.THINKING);
                break;
                
            case THINKING_END:
                transitionToState(messageId, InferenceState.GENERATING);
                break;
                
            case GENERATION_COMPLETE:
                transitionToState(messageId, InferenceState.COMPLETED);
                cleanup(messageId);
                break;
                
            case ERROR:
                details.additionalInfo = event.errorMessage;
                transitionToState(messageId, InferenceState.FAILED);
                cleanup(messageId);
                break;
                
            case HEARTBEAT:
                // 仅更新心跳时间
                break;
        }
    }
    
    /**
     * 从StreamingEvent转换并处理
     */
    public void handleStreamingEvent(String messageId, StreamingEvent event) {
        StateDetails details = stateMap.get(messageId);
        if (details == null) return;
        
        details.updateHeartbeat();
        
        switch (event.type) {
            case MESSAGE_CREATED:
                transitionToState(messageId, InferenceState.PREFILL);
                break;
                
            case TOKEN_APPENDED:
                if (details.currentState != InferenceState.GENERATING) {
                    transitionToState(messageId, InferenceState.GENERATING);
                }
                details.processedTokens++;
                updateProgress(messageId);
                break;
                
            case INFERENCE_PROGRESS:
                StreamingEvent.InferenceProgressData progress = event.getInferenceProgressData();
                if (progress != null) {
                    details.processedTokens = progress.processedTokens;
                    details.tokensPerSecond = progress.tokensPerSecond;
                    details.totalTokens = progress.totalTokens;
                    if (progress.phase != null) {
                        details.currentPhase = progress.phase.getDisplayText();
                    }
                    updateProgress(messageId);
                }
                break;
                
            case MESSAGE_COMPLETED:
                transitionToState(messageId, InferenceState.COMPLETED);
                cleanup(messageId);
                break;
                
            case MESSAGE_FAILED:
                details.additionalInfo = event.getStringData();
                transitionToState(messageId, InferenceState.FAILED);
                cleanup(messageId);
                break;
                
            case MESSAGE_CANCELLED:
                transitionToState(messageId, InferenceState.CANCELLED);
                cleanup(messageId);
                break;
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
     * 检查是否正在推理中
     */
    public boolean isInferenceActive(String messageId) {
        StateDetails details = stateMap.get(messageId);
        return details != null && details.isActive.get();
    }
    
    /**
     * 取消推理
     */
    public void cancelInference(String messageId) {
        Log.i(TAG, "Cancelling inference for message: " + messageId);
        transitionToState(messageId, InferenceState.CANCELLED);
        cleanup(messageId);
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
     * 设置超时监听器
     */
    public void setTimeoutListener(TimeoutListener listener) {
        this.timeoutListener = listener;
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
    
    /**
     * Native层事件类型
     */
    public static class NativeEvent {
        public enum Type {
            MODEL_LOAD_START,
            MODEL_LOAD_COMPLETE,
            ENCODING_COMPLETE,
            PREFILL_COMPLETE,
            TOKEN_GENERATED,
            THINKING_START,
            THINKING_END,
            GENERATION_COMPLETE,
            ERROR,
            HEARTBEAT
        }
        
        public final Type type;
        public final int tokenCount;
        public final float tokensPerSecond;
        public final String errorMessage;
        public final long timestamp;
        
        public NativeEvent(Type type) {
            this(type, 0, 0, null);
        }
        
        public NativeEvent(Type type, int tokenCount, float tokensPerSecond, String errorMessage) {
            this.type = type;
            this.tokenCount = tokenCount;
            this.tokensPerSecond = tokensPerSecond;
            this.errorMessage = errorMessage;
            this.timestamp = System.currentTimeMillis();
        }
        
        // 便捷工厂方法
        public static NativeEvent modelLoadStart() {
            return new NativeEvent(Type.MODEL_LOAD_START);
        }
        
        public static NativeEvent modelLoadComplete() {
            return new NativeEvent(Type.MODEL_LOAD_COMPLETE);
        }
        
        public static NativeEvent encodingComplete() {
            return new NativeEvent(Type.ENCODING_COMPLETE);
        }
        
        public static NativeEvent prefillComplete() {
            return new NativeEvent(Type.PREFILL_COMPLETE);
        }
        
        public static NativeEvent tokenGenerated(int count, float tps) {
            return new NativeEvent(Type.TOKEN_GENERATED, count, tps, null);
        }
        
        public static NativeEvent thinkingStart() {
            return new NativeEvent(Type.THINKING_START);
        }
        
        public static NativeEvent thinkingEnd() {
            return new NativeEvent(Type.THINKING_END);
        }
        
        public static NativeEvent generationComplete() {
            return new NativeEvent(Type.GENERATION_COMPLETE);
        }
        
        public static NativeEvent error(String message) {
            return new NativeEvent(Type.ERROR, 0, 0, message);
        }
        
        public static NativeEvent heartbeat() {
            return new NativeEvent(Type.HEARTBEAT);
        }
    }
}
