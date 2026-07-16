package com.oilquiz.app.ai.chat;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native事件桥接器
 * 
 * 职责：
 * 1. 桥接Native层回调与Java层事件系统
 * 2. 处理Native层通信异常中断的恢复
 * 3. 提供心跳检测机制
 * 4. 管理Native层资源生命周期
 */
public class NativeEventBridge {
    private static final String TAG = "NativeEventBridge";
    
    // 心跳检测间隔（毫秒）
    private static final long HEARTBEAT_INTERVAL_MS = 5000;
    private static final long HEARTBEAT_TIMEOUT_MS = 15000;
    
    // 最大重试次数
    private static final int MAX_RETRY_COUNT = 3;
    
    private final InferenceStateManager stateManager;
    private final Handler mainHandler;
    private final Map<String, BridgeSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, NativeCallback> activeCallbacks = new ConcurrentHashMap<>();
    
    private volatile boolean isBridgeActive = false;
    private volatile NativeEventListener eventListener;
    private volatile CommunicationErrorListener errorListener;
    
    private static volatile NativeEventBridge instance;
    
    public static NativeEventBridge getInstance() {
        if (instance == null) {
            synchronized (NativeEventBridge.class) {
                if (instance == null) {
                    instance = new NativeEventBridge();
                }
            }
        }
        return instance;
    }
    
    private NativeEventBridge() {
        this.stateManager = InferenceStateManager.getInstance();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.isBridgeActive = true;
    }
    
    /**
     * 启动新的生成会话
     */
    public void startGeneration(String messageId, String prompt, int maxTokens,
                                 GenerationCallback callback) {
        // 检查桥接器状态
        if (!isBridgeActive) {
            Log.e(TAG, "Bridge is not active, cannot start generation");
            if (callback != null) {
                mainHandler.post(() -> callback.onError("Native桥接器未激活"));
            }
            return;
        }

        // 检查native层状态
        if (!LlamaHelper.isNativeStateValid()) {
            Log.e(TAG, "Native state is invalid, cannot start generation");
            if (callback != null) {
                mainHandler.post(() -> callback.onError("Native层状态异常"));
            }
            return;
        }

        Log.i(TAG, "Starting generation for message: " + messageId);
        
        // 创建会话
        BridgeSession session = new BridgeSession(messageId);
        sessions.put(messageId, session);
        
        // 初始化状态
        stateManager.startInference(messageId);
        
        // 设置生成标志
        LlamaHelper.setHasActiveGeneration(true);
        
        // 创建Native回调包装器
        NativeCallback nativeCallback = new NativeCallback(messageId, callback);
        activeCallbacks.put(messageId, nativeCallback);
        
        // 启动心跳检测
        startHeartbeatMonitor(messageId);
        
        // 发送模型加载开始事件
        notifyNativeEvent(messageId, InferenceStateManager.NativeEvent.modelLoadStart());
        
        try {
            // 调用Native层生成
            LlamaHelper.chatSend(prompt, maxTokens, 0.7f, 0.9f, 40, false, 
                new LlamaHelper.TokenCallback() {
                    private final StringBuilder fullResponse = new StringBuilder();
                    private int tokenCount = 0;
                    private long startTime = System.currentTimeMillis();
                    private volatile boolean isCallbackActive = true;
                    
                    @Override
                    public void onToken(String token) {
                        if (!isBridgeActive || !session.isActive.get() || !isCallbackActive) {
                            return;
                        }
                        try {
                            session.updateHeartbeat();
                            tokenCount++;
                            fullResponse.append(token);
                            
                            // 计算生成速度
                            long elapsed = System.currentTimeMillis() - startTime;
                            float tps = elapsed > 0 ? (tokenCount * 1000f) / elapsed : 0;
                            
                            // 通知token生成
                            notifyNativeEvent(messageId, 
                                InferenceStateManager.NativeEvent.tokenGenerated(tokenCount, tps));
                            
                            // 回调到上层
                            if (callback != null) {
                                mainHandler.post(() -> {
                                    try {
                                        callback.onToken(token);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Exception in token callback: " + e.getMessage());
                                    }
                                });
                            }
                            
                            // 通知事件监听器
                            if (eventListener != null) {
                                mainHandler.post(() -> {
                                    try {
                                        eventListener.onTokenGenerated(messageId, token);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Exception in event listener: " + e.getMessage());
                                    }
                                });
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Exception in onToken: " + e.getMessage());
                            isCallbackActive = false;
                        }
                    }
                    
                    @Override
                    public void onComplete(String fullText) {
                        if (!isBridgeActive) return;
                        isCallbackActive = false;
                        session.isActive.set(false);
                        LlamaHelper.setHasActiveGeneration(false);
                        
                        String finalText = fullText != null ? fullText : fullResponse.toString();
                        
                        try {
                            notifyNativeEvent(messageId, 
                                InferenceStateManager.NativeEvent.generationComplete());
                            
                            if (callback != null) {
                                mainHandler.post(() -> {
                                    try {
                                        callback.onComplete(finalText);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Exception in complete callback: " + e.getMessage());
                                    }
                                });
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Exception in onComplete: " + e.getMessage());
                        }
                        
                        cleanupSession(messageId);
                    }
                    
                    @Override
                    public void onError(String error) {
                        if (!isBridgeActive) return;
                        isCallbackActive = false;
                        session.isActive.set(false);
                        LlamaHelper.setHasActiveGeneration(false);
                        
                        Log.e(TAG, "Native generation error for message " + messageId + ": " + error);
                        
                        try {
                            notifyNativeEvent(messageId, 
                                InferenceStateManager.NativeEvent.error(error));
                            
                            if (callback != null) {
                                mainHandler.post(() -> {
                                    try {
                                        callback.onError(error);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Exception in error callback: " + e.getMessage());
                                    }
                                });
                            }
                            
                            // 通知错误监听器
                            if (errorListener != null) {
                                mainHandler.post(() -> {
                                    try {
                                        errorListener.onNativeError(messageId, error);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Exception in error listener: " + e.getMessage());
                                    }
                                });
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Exception in onError: " + e.getMessage());
                        }
                        
                        cleanupSession(messageId);
                    }
                });
                
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to start generation (UnsatisfiedLinkError): " + e.getMessage(), e);
            session.isActive.set(false);
            LlamaHelper.setHasActiveGeneration(false);
            
            notifyNativeEvent(messageId, 
                InferenceStateManager.NativeEvent.error("Native链接错误: " + e.getMessage()));
            
            if (callback != null) {
                mainHandler.post(() -> callback.onError("Native层错误: " + e.getMessage()));
            }
            
            cleanupSession(messageId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start generation: " + e.getMessage(), e);
            session.isActive.set(false);
            LlamaHelper.setHasActiveGeneration(false);
            
            notifyNativeEvent(messageId, 
                InferenceStateManager.NativeEvent.error(e.getMessage()));
            
            if (callback != null) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
            
            cleanupSession(messageId);
        }
    }
    
    /**
     * 取消生成
     */
    public void cancelGeneration(String messageId) {
        Log.i(TAG, "Cancelling generation for message: " + messageId);
        
        BridgeSession session = sessions.get(messageId);
        if (session != null) {
            session.isActive.set(false);
        }
        
        stateManager.cancelInference(messageId);
        
        // 尝试取消Native层生成
        try {
            LlamaHelper.stopGeneration();
        } catch (Exception e) {
            Log.w(TAG, "Error stopping generation: " + e.getMessage());
        }
        
        cleanupSession(messageId);
    }
    
    /**
     * 启动心跳监控
     */
    private void startHeartbeatMonitor(String messageId) {
        Runnable heartbeatCheck = new Runnable() {
            @Override
            public void run() {
                BridgeSession session = sessions.get(messageId);
                if (session == null || !session.isActive.get()) {
                    return;
                }
                
                long timeSinceHeartbeat = session.getTimeSinceHeartbeat();
                
                if (timeSinceHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                    Log.w(TAG, String.format("Heartbeat timeout for message %s: %dms", 
                        messageId, timeSinceHeartbeat));
                    
                    handleCommunicationBreakdown(messageId, 
                        "Heartbeat timeout after " + timeSinceHeartbeat + "ms");
                    return;
                }
                
                // 继续监控
                if (session.isActive.get()) {
                    mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
                }
            }
        };
        
        mainHandler.postDelayed(heartbeatCheck, HEARTBEAT_INTERVAL_MS);
    }
    
    /**
     * 处理通信中断
     */
    private void handleCommunicationBreakdown(String messageId, String reason) {
        Log.e(TAG, "Communication breakdown for message " + messageId + ": " + reason);
        
        BridgeSession session = sessions.get(messageId);
        if (session != null) {
            session.isActive.set(false);
        }
        
        // 通知状态管理器
        notifyNativeEvent(messageId, InferenceStateManager.NativeEvent.error(
            "Communication breakdown: " + reason));
        
        // 通知错误监听器
        if (errorListener != null) {
            mainHandler.post(() -> errorListener.onCommunicationBreakdown(messageId, reason));
        }
        
        // 尝试恢复
        attemptRecovery(messageId);
    }
    
    /**
     * 尝试恢复通信
     */
    private void attemptRecovery(String messageId) {
        BridgeSession session = sessions.get(messageId);
        if (session == null) return;
        
        if (session.retryCount.incrementAndGet() > MAX_RETRY_COUNT) {
            Log.e(TAG, "Max retry count exceeded for message: " + messageId);
            
            if (errorListener != null) {
                mainHandler.post(() -> errorListener.onRecoveryFailed(messageId, 
                    "Max retry count exceeded"));
            }
            
            cleanupSession(messageId);
            return;
        }
        
        Log.i(TAG, "Attempting recovery for message: " + messageId + 
            " (attempt " + session.retryCount.get() + ")");
        
        if (errorListener != null) {
            mainHandler.post(() -> errorListener.onRecoveryStarted(messageId, 
                session.retryCount.get()));
        }
        
        // 延迟后尝试恢复
        mainHandler.postDelayed(() -> {
            // 检查Native层状态
            try {
                boolean isModelReady = LlamaHelper.isModelInitialized();
                
                if (!isModelReady) {
                    Log.w(TAG, "Model not initialized during recovery");
                    
                    if (errorListener != null) {
                        mainHandler.post(() -> errorListener.onRecoveryFailed(messageId,
                            "Model not available"));
                    }
                    
                    cleanupSession(messageId);
                    return;
                }
                
                // 恢复成功
                Log.i(TAG, "Recovery successful for message: " + messageId);
                session.updateHeartbeat();
                session.isActive.set(true);
                
                if (errorListener != null) {
                    mainHandler.post(() -> errorListener.onRecoverySuccess(messageId));
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Recovery failed: " + e.getMessage(), e);
                
                if (errorListener != null) {
                    mainHandler.post(() -> errorListener.onRecoveryFailed(messageId, 
                        e.getMessage()));
                }
                
                cleanupSession(messageId);
            }
        }, 1000);
    }
    
    /**
     * 通知Native事件
     */
    private void notifyNativeEvent(String messageId, InferenceStateManager.NativeEvent event) {
        stateManager.handleNativeEvent(messageId, event);
        
        if (eventListener != null) {
            mainHandler.post(() -> eventListener.onNativeEvent(messageId, event));
        }
    }
    
    /**
     * 清理会话
     */
    private void cleanupSession(String messageId) {
        sessions.remove(messageId);
        activeCallbacks.remove(messageId);
    }
    
    /**
     * 检查是否有活动的生成
     */
    public boolean hasActiveGeneration(String messageId) {
        BridgeSession session = sessions.get(messageId);
        return session != null && session.isActive.get();
    }
    
    /**
     * 获取活动会话数
     */
    public int getActiveSessionCount() {
        int count = 0;
        for (BridgeSession session : sessions.values()) {
            if (session.isActive.get()) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * 停止所有活动会话
     */
    public void stopAllSessions() {
        Log.i(TAG, "Stopping all sessions");
        
        for (String messageId : sessions.keySet()) {
            cancelGeneration(messageId);
        }
        
        sessions.clear();
        activeCallbacks.clear();
    }
    
    /**
     * 设置事件监听器
     */
    public void setEventListener(NativeEventListener listener) {
        this.eventListener = listener;
    }
    
    /**
     * 设置错误监听器
     */
    public void setErrorListener(CommunicationErrorListener listener) {
        this.errorListener = listener;
    }
    
    /**
     * 销毁桥接器
     */
    public void destroy() {
        Log.i(TAG, "Destroying NativeEventBridge");
        isBridgeActive = false;
        stopAllSessions();
    }
    
    /**
     * 桥接会话
     */
    private static class BridgeSession {
        final String messageId;
        final AtomicBoolean isActive = new AtomicBoolean(true);
        final AtomicInteger retryCount = new AtomicInteger(0);
        volatile long lastHeartbeat;
        
        BridgeSession(String messageId) {
            this.messageId = messageId;
            this.lastHeartbeat = System.currentTimeMillis();
        }
        
        void updateHeartbeat() {
            lastHeartbeat = System.currentTimeMillis();
        }
        
        long getTimeSinceHeartbeat() {
            return System.currentTimeMillis() - lastHeartbeat;
        }
    }
    
    /**
     * Native回调包装器
     */
    private static class NativeCallback {
        final String messageId;
        final GenerationCallback callback;
        
        NativeCallback(String messageId, GenerationCallback callback) {
            this.messageId = messageId;
            this.callback = callback;
        }
    }
    
    /**
     * 生成回调接口
     */
    public interface GenerationCallback {
        void onToken(String token);
        void onComplete(String fullText);
        void onError(String error);
    }
    
    /**
     * Native事件监听器
     */
    public interface NativeEventListener {
        void onNativeEvent(String messageId, InferenceStateManager.NativeEvent event);
        void onTokenGenerated(String messageId, String token);
    }
    
    /**
     * 通信错误监听器
     */
    public interface CommunicationErrorListener {
        void onNativeError(String messageId, String error);
        void onCommunicationBreakdown(String messageId, String reason);
        void onRecoveryStarted(String messageId, int attemptNumber);
        void onRecoverySuccess(String messageId);
        void onRecoveryFailed(String messageId, String reason);
    }
}
