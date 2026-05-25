package com.oilquiz.app.ai.refactor;

import android.content.Context;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.infra.AppLogger;

import android.app.ActivityManager;
import android.os.Debug;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class UnifiedContextManager {

    private static final String TAG = "UnifiedContextManager";

    private static UnifiedContextManager instance;
    private final Context appContext;

    private final AtomicBoolean modelContextReady = new AtomicBoolean(false);
    private final AtomicBoolean chatContextReady = new AtomicBoolean(false);

    private volatile ContextType currentContextType = ContextType.NONE;
    private volatile ContextState lastNotifiedState = ContextState.UNINITIALIZED;
    private final Map<String, Object> contextData = new HashMap<>();
    
    private ContextStateListener stateListener;
    
    // 防抖动：避免短时间内频繁通知
    private volatile long lastStateNotifyTime = 0;
    private static final long STATE_NOTIFY_DEBOUNCE_MS = 100;

    public enum ContextType {
        NONE,
        MODEL,
        CHAT,
        FULL
    }

    public enum ContextState {
        UNINITIALIZED,
        MODEL_LOADING,
        MODEL_READY,
        CHAT_CREATING,
        CHAT_READY,
        RECOVERING,
        ERROR
    }

    public interface ContextStateListener {
        void onContextStateChanged(ContextState state, String message);
        void onContextError(String error);
    }

    private UnifiedContextManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static synchronized UnifiedContextManager getInstance(Context context) {
        if (instance == null) {
            instance = new UnifiedContextManager(context);
        }
        return instance;
    }

    public static UnifiedContextManager getInstance() {
        return instance;
    }

    public void setStateListener(ContextStateListener listener) {
        this.stateListener = listener;
    }

    public void notifyState(ContextState state, String message) {
        long now = System.currentTimeMillis();
        boolean shouldNotify = (now - lastStateNotifyTime) >= STATE_NOTIFY_DEBOUNCE_MS
            || state != lastNotifiedState;
        
        if (!shouldNotify) {
            return;
        }
        
        lastStateNotifyTime = now;
        lastNotifiedState = state;
        AppLogger.ai(TAG, "Context state changed: " + state + " - " + message);
        if (stateListener != null) {
            stateListener.onContextStateChanged(state, message);
        }
    }

    public void notifyError(String error) {
        AppLogger.aiE(TAG, "Context error: " + error);
        if (stateListener != null) {
            stateListener.onContextError(error);
        }
    }

    public void setModelContextReady(boolean ready) {
        boolean wasReady = modelContextReady.getAndSet(ready);
        if (wasReady == ready) {
            return;
        }
        updateCurrentContextType();
        if (ready) {
            notifyState(ContextState.MODEL_READY, "模型上下文已就绪");
        } else {
            notifyState(ContextState.MODEL_LOADING, "模型上下文未就绪");
        }
    }

    public void setChatContextReady(boolean ready) {
        boolean wasReady = chatContextReady.getAndSet(ready);
        if (wasReady == ready) {
            return;
        }
        updateCurrentContextType();
        if (ready) {
            notifyState(ContextState.CHAT_READY, "聊天上下文已就绪");
        } else {
            notifyState(ContextState.CHAT_CREATING, "聊天上下文未就绪");
        }
    }

    private void updateCurrentContextType() {
        boolean modelReady = modelContextReady.get();
        boolean chatReady = chatContextReady.get();

        if (modelReady && chatReady) {
            currentContextType = ContextType.FULL;
        } else if (chatReady) {
            currentContextType = ContextType.CHAT;
        } else if (modelReady) {
            currentContextType = ContextType.MODEL;
        } else {
            currentContextType = ContextType.NONE;
        }
    }

    public ContextType getCurrentContextType() {
        return currentContextType;
    }

    public boolean isModelContextReady() {
        return modelContextReady.get();
    }

    public boolean isChatContextReady() {
        return chatContextReady.get();
    }

    public boolean isFullContextReady() {
        return modelContextReady.get() && chatContextReady.get();
    }

    public boolean canGenerate() {
        return modelContextReady.get();
    }

    public boolean canChat() {
        return modelContextReady.get() && chatContextReady.get();
    }

    public void putContextData(String key, Object value) {
        if (key != null && value != null) {
            contextData.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getContextData(String key, T defaultValue) {
        Object value = contextData.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return (T) value;
        } catch (ClassCastException e) {
            AppLogger.aiE(TAG, "Context data type mismatch: " + key);
            return defaultValue;
        }
    }

    public boolean hasContextData(String key) {
        return contextData.containsKey(key);
    }

    public void clearContextData() {
        contextData.clear();
    }

    public void resetAll() {
        modelContextReady.set(false);
        chatContextReady.set(false);
        currentContextType = ContextType.NONE;
        contextData.clear();
        notifyState(ContextState.UNINITIALIZED, "所有上下文已重置");
    }

    public void resetChatContext() {
        chatContextReady.set(false);
        updateCurrentContextType();
        notifyState(ContextState.MODEL_READY, "聊天上下文已重置");
    }
    
    public void notifyChatHistoryCleared() {
        AppLogger.ai(TAG, "Chat history cleared, but chat context handle remains valid");
    }

    public String getContextStatusSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("上下文状态: ");
        sb.append("\n  模型上下文: ").append(modelContextReady.get() ? "就绪" : "未就绪");
        sb.append("\n  聊天上下文: ").append(chatContextReady.get() ? "就绪" : "未就绪");
        sb.append("\n  当前类型: ").append(currentContextType);
        sb.append("\n  数据项数: ").append(contextData.size());
        return sb.toString();
    }

    public boolean ensureModelContext(AIService aiService) {
        if (aiService == null) {
            return false;
        }

        if (modelContextReady.get()) {
            return true;
        }

        if (aiService.isInitialized() && LlamaHelper.isModelInitialized()) {
            modelContextReady.set(true);
            updateCurrentContextType();
            return true;
        }

        return false;
    }

    public boolean ensureChatContext(AIService aiService, String globalPrompt, String systemPrompt, String normalPrompt) {
        if (aiService == null) {
            return false;
        }

        if (!ensureModelContext(aiService)) {
            return false;
        }

        if (chatContextReady.get()) {
            return true;
        }

        boolean created = aiService.initChatContext(
            globalPrompt != null ? globalPrompt : "",
            systemPrompt != null ? systemPrompt : "",
            normalPrompt != null ? normalPrompt : ""
        );

        if (created) {
            chatContextReady.set(true);
            updateCurrentContextType();
            return true;
        }
        return false;
    }
    
    public boolean detectNativeCrash() {
        boolean chatHandleValid = LlamaHelper.isChatContextActive();
        boolean modelValid = LlamaHelper.isModelInitialized();
        
        AppLogger.ai(TAG, "Native crash detection: chatContextActive=" + chatHandleValid + 
            ", modelInitialized=" + modelValid + 
            ", expectedChatReady=" + chatContextReady.get());
        
        if (chatContextReady.get() && !chatHandleValid) {
            AppLogger.aiE(TAG, "Native chat context handle is invalid but marked as ready - possible crash!");
            return true;
        }
        
        if (modelContextReady.get() && !modelValid) {
            AppLogger.aiE(TAG, "Native model is invalid but marked as ready - possible crash!");
            return true;
        }
        
        return false;
    }
    
    public boolean checkAndRecoverIfNeeded(AIService aiService) {
        if (detectNativeCrash()) {
            AppLogger.aiW(TAG, "Native state mismatch detected, syncing state...");
            syncState();
            return false;
        }
        return true;
    }
    
    private void syncState() {
        boolean chatHandleValid = LlamaHelper.isChatContextActive();
        boolean modelValid = LlamaHelper.isModelInitialized();
        
        boolean changed = false;
        
        if (modelContextReady.get() && !modelValid) {
            modelContextReady.set(false);
            changed = true;
            AppLogger.ai(TAG, "Model state synced: READY -> NOT_READY");
        }
        
        if (chatContextReady.get() && !chatHandleValid) {
            chatContextReady.set(false);
            changed = true;
            AppLogger.ai(TAG, "Chat context state synced: READY -> NOT_READY");
        }
        
        if (changed) {
            updateCurrentContextType();
            notifyState(ContextState.ERROR, "Native状态不一致，已同步");
        }
    }
    
    public boolean detectModelCrash() {
        boolean nativeModelValid = LlamaHelper.isModelInitialized();
        boolean expectedReady = modelContextReady.get();
        
        AppLogger.ai(TAG, "Model crash detection: nativeValid=" + nativeModelValid + 
            ", expectedReady=" + expectedReady);
        
        if (expectedReady && !nativeModelValid) {
            AppLogger.aiE(TAG, "Model state mismatch: Java says ready but Native says invalid!");
            return true;
        }
        
        return false;
    }
    
    public boolean checkAndRecoverModelIfNeeded(AIService aiService) {
        if (!detectModelCrash()) {
            return true;
        }
        
        AppLogger.aiW(TAG, "Model state mismatch detected, syncing state...");
        syncModelState(aiService);
        return false;
    }
    
    private void syncModelState(AIService aiService) {
        boolean nativeModelValid = LlamaHelper.isModelInitialized();
        boolean javaStateReady = modelContextReady.get();
        
        AppLogger.ai(TAG, "Syncing model state: nativeValid=" + nativeModelValid + 
            ", javaStateReady=" + javaStateReady);
        
        if (nativeModelValid && !javaStateReady) {
            setModelContextReady(true);
            AppLogger.ai(TAG, "Native model is valid, synced Java state to READY");
        } else if (!nativeModelValid && javaStateReady) {
            modelContextReady.set(false);
            updateCurrentContextType();
            notifyState(ContextState.ERROR, "Native模型无效，Java状态已同步");
            AppLogger.ai(TAG, "Native model invalid, Java state synced to NOT_READY");
        }
    }
    
    public boolean checkAndRecoverAllIfNeeded(AIService aiService) {
        if (aiService == null) {
            return false;
        }
        
        if (detectModelCrash()) {
            AppLogger.aiW(TAG, "Model state mismatch detected, syncing state...");
            syncModelState(aiService);
        }
        
        if (detectNativeCrash()) {
            AppLogger.aiW(TAG, "Chat context state mismatch detected, syncing state...");
            syncState();
        }
        
        return true;
    }
    
    public String getFullStatusSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(getContextStatusSummary());
        sb.append("\n\n=== 稳定性状态 ===");
        sb.append("\n  健康检查: ").append(healthCheckEnabled.get() ? "启用" : "禁用");
        sb.append("\n  内存警告阈值: ").append(MEMORY_THRESHOLD * 100).append("%");
        sb.append("\n  停滞检测: ").append(stagnationCheckEnabled.get() ? "启用" : "禁用");
        return sb.toString();
    }
    
    // ===================== Stability Enhancement =====================
    
    private static final long HEALTH_CHECK_INTERVAL_MS = 30000;
    private static final long STAGNATION_TIMEOUT_MS = 30000;
    private static final float MEMORY_THRESHOLD = 0.85f;
    private static final int MAX_RETRY_COUNT = 3;
    
    private final AtomicBoolean healthCheckEnabled = new AtomicBoolean(false);
    private final AtomicBoolean stagnationCheckEnabled = new AtomicBoolean(false);
    private final AtomicLong lastTokenTime = new AtomicLong(0);
    private final AtomicInteger tokensGenerated = new AtomicInteger(0);
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private final AtomicReference<Timer> healthCheckTimer = new AtomicReference<>(null);
    
    private StabilityCallback stabilityCallback;
    
    public interface StabilityCallback {
        void onMemoryWarning(float usagePercent, long usedMB, long maxMB);
        void onGenerationStagnation(long stagnationTimeMs);
        void onHealthCheckFailed(String reason);
        void onHealthCheckPassed();
    }
    
    public void setStabilityCallback(StabilityCallback callback) {
        this.stabilityCallback = callback;
    }
    
    // ===================== Memory Protection =====================
    
    public static class MemoryInfo {
        public long usedMB;
        public long maxMB;
        public float usagePercent;
        public boolean isLowMemory;
        
        @Override
        public String toString() {
            return String.format("Memory: %dMB / %dMB (%.1f%%), Low=%s", 
                usedMB, maxMB, usagePercent * 100, isLowMemory);
        }
    }
    
    public MemoryInfo getMemoryInfo() {
        MemoryInfo info = new MemoryInfo();
        try {
            ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(memInfo);
            
            Debug.MemoryInfo debugMemInfo = new Debug.MemoryInfo();
            Debug.getMemoryInfo(debugMemInfo);
            
            Runtime runtime = Runtime.getRuntime();
            long usedBytes = runtime.totalMemory() - runtime.freeMemory();
            long maxBytes = runtime.maxMemory();
            
            info.usedMB = usedBytes / (1024 * 1024);
            info.maxMB = maxBytes / (1024 * 1024);
            info.usagePercent = (float) usedBytes / maxBytes;
            info.isLowMemory = memInfo.lowMemory || info.usagePercent > MEMORY_THRESHOLD;
            
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error getting memory info: " + e.getMessage());
            info.usedMB = 0;
            info.maxMB = 1;
            info.usagePercent = 0;
            info.isLowMemory = false;
        }
        return info;
    }
    
    public boolean isMemoryLow() {
        MemoryInfo info = getMemoryInfo();
        return info.isLowMemory;
    }
    
    public void checkMemoryAndWarn() {
        MemoryInfo info = getMemoryInfo();
        AppLogger.ai(TAG, info.toString());
        
        if (info.isLowMemory && stabilityCallback != null) {
            stabilityCallback.onMemoryWarning(info.usagePercent, info.usedMB, info.maxMB);
        }
    }
    
    // ===================== Generation Progress Tracking =====================
    
    public void startGenerationTracking() {
        isGenerating.set(true);
        tokensGenerated.set(0);
        lastTokenTime.set(System.currentTimeMillis());
        AppLogger.ai(TAG, "Generation tracking started");
    }
    
    public void recordTokenGenerated() {
        tokensGenerated.incrementAndGet();
        lastTokenTime.set(System.currentTimeMillis());
    }
    
    public void stopGenerationTracking() {
        isGenerating.set(false);
        AppLogger.ai(TAG, "Generation tracking stopped, total tokens: " + tokensGenerated.get());
    }
    
    public int getTokensGenerated() {
        return tokensGenerated.get();
    }
    
    public long getTimeSinceLastToken() {
        if (lastTokenTime.get() == 0) return 0;
        return System.currentTimeMillis() - lastTokenTime.get();
    }
    
    // ===================== Stagnation Detection =====================
    
    public boolean isGenerationStagnant() {
        if (!isGenerating.get()) return false;
        long timeSinceLastToken = getTimeSinceLastToken();
        boolean stagnant = timeSinceLastToken > STAGNATION_TIMEOUT_MS && tokensGenerated.get() > 0;
        
        if (stagnant) {
            AppLogger.aiW(TAG, "Generation stagnation detected: " + timeSinceLastToken + "ms since last token");
        }
        return stagnant;
    }
    
    public void checkStagnation() {
        if (stagnationCheckEnabled.get() && isGenerationStagnant()) {
            long stagnationTime = getTimeSinceLastToken();
            if (stabilityCallback != null) {
                stabilityCallback.onGenerationStagnation(stagnationTime);
            }
        }
    }
    
    public void setStagnationCheckEnabled(boolean enabled) {
        stagnationCheckEnabled.set(enabled);
        AppLogger.ai(TAG, "Stagnation check: " + enabled);
    }
    
    // ===================== Health Check =====================
    
    public void startHealthCheck() {
        if (healthCheckEnabled.compareAndSet(false, true)) {
            Timer timer = new Timer("UnifiedContextHealthCheck", true);
            healthCheckTimer.set(timer);
            
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    try {
                        performHealthCheck();
                    } catch (Exception e) {
                        AppLogger.aiE(TAG, "Health check error: " + e.getMessage());
                    }
                }
            }, HEALTH_CHECK_INTERVAL_MS, HEALTH_CHECK_INTERVAL_MS);
            
            AppLogger.ai(TAG, "Health check started, interval: " + HEALTH_CHECK_INTERVAL_MS + "ms");
        }
    }
    
    public void stopHealthCheck() {
        if (healthCheckEnabled.compareAndSet(true, false)) {
            Timer timer = healthCheckTimer.getAndSet(null);
            if (timer != null) {
                timer.cancel();
                timer.purge();
            }
            AppLogger.ai(TAG, "Health check stopped");
        }
    }
    
    private void performHealthCheck() {
        StringBuilder issues = new StringBuilder();
        boolean allOk = true;
        
        MemoryInfo memInfo = getMemoryInfo();
        if (memInfo.isLowMemory) {
            issues.append("High memory usage (").append(String.format("%.1f", memInfo.usagePercent * 100)).append("%)");
            allOk = false;
            if (stabilityCallback != null) {
                stabilityCallback.onMemoryWarning(memInfo.usagePercent, memInfo.usedMB, memInfo.maxMB);
            }
        }
        
        if (isGenerating.get()) {
            long timeSinceLastToken = getTimeSinceLastToken();
            if (timeSinceLastToken > STAGNATION_TIMEOUT_MS && tokensGenerated.get() > 0) {
                if (issues.length() > 0) issues.append(", ");
                issues.append("Generation stagnant (").append(timeSinceLastToken).append("ms)");
                allOk = false;
                if (stabilityCallback != null && stagnationCheckEnabled.get()) {
                    stabilityCallback.onGenerationStagnation(timeSinceLastToken);
                }
            }
        }
        
        if (!LlamaHelper.isModelInitialized() && modelContextReady.get()) {
            if (issues.length() > 0) issues.append(", ");
            issues.append("Model context invalid");
            allOk = false;
        }
        
        if (allOk) {
            if (stabilityCallback != null) {
                stabilityCallback.onHealthCheckPassed();
            }
        } else {
            AppLogger.aiW(TAG, "Health check failed: " + issues.toString());
            if (stabilityCallback != null) {
                stabilityCallback.onHealthCheckFailed(issues.toString());
            }
        }
    }
    
    // ===================== Retry Mechanism =====================
    
    public static class RetryResult<T> {
        public final boolean success;
        public final T result;
        public final int retryCount;
        public final Exception lastError;
        
        public RetryResult(boolean success, T result, int retryCount, Exception lastError) {
            this.success = success;
            this.result = result;
            this.retryCount = retryCount;
            this.lastError = lastError;
        }
    }
    
    public interface RetryableOperation<T> {
        T execute() throws Exception;
    }
    
    public <T> RetryResult<T> executeWithRetry(RetryableOperation<T> operation) {
        return executeWithRetry(operation, MAX_RETRY_COUNT);
    }
    
    public <T> RetryResult<T> executeWithRetry(RetryableOperation<T> operation, int maxRetries) {
        Exception lastError = null;
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                AppLogger.ai(TAG, "Operation attempt " + (attempt + 1) + "/" + (maxRetries + 1));
                T result = operation.execute();
                return new RetryResult<>(true, result, attempt, null);
            } catch (Exception e) {
                lastError = e;
                AppLogger.aiW(TAG, "Operation attempt " + (attempt + 1) + " failed: " + e.getMessage());
                
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(100 * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        AppLogger.aiE(TAG, "Operation failed after " + (maxRetries + 1) + " attempts");
        return new RetryResult<>(false, null, maxRetries, lastError);
    }
    
    public RetryResult<Boolean> executeChatSendWithRetry(AIService aiService, String message, 
            int maxTokens, boolean enableThinking, LlamaHelper.TokenCallback callback) {
        return executeWithRetry(() -> {
            aiService.chatSend(message, maxTokens, enableThinking, callback);
            return true;
        });
    }
    
    // ===================== Context State Validation =====================
    
    public boolean validateContextState() {
        boolean modelOk = !modelContextReady.get() || LlamaHelper.isModelInitialized();
        boolean chatOk = !chatContextReady.get() || LlamaHelper.isChatContextActive();
        
        if (!modelOk) {
            AppLogger.aiE(TAG, "Model context state mismatch: Java says ready, Native says not");
        }
        if (!chatOk) {
            AppLogger.aiE(TAG, "Chat context state mismatch: Java says ready, Native says not");
        }
        
        return modelOk && chatOk;
    }
    
    public void validateAndFixContextState(AIService aiService) {
        if (!validateContextState()) {
            AppLogger.aiW(TAG, "Context state mismatch detected, attempting to fix...");
            checkAndRecoverAllIfNeeded(aiService);
        }
    }
}
