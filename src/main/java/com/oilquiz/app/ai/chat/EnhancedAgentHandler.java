package com.oilquiz.app.ai.chat;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.oilquiz.app.ai.agent.SmartIntentRecognizer;
import com.oilquiz.app.ai.agent.UnifiedAgentEngine;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EnhancedAgentHandler - 增强版 Agent 处理器
 * 
 * 改进点：
 * 1. 更清晰的思考链显示
 * 2. 更好的工具调用反馈
 * 3. 更健壮的错误处理
 * 4. 更好的 UI 通知机制
 */
public class EnhancedAgentHandler {
    
    private static final String TAG = "EnhancedAgentHandler";
    
    // ========== 状态管理 ==========
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private final AtomicInteger currentStep = new AtomicInteger(0);
    private final AtomicInteger totalSteps = new AtomicInteger(0);
    
    // ========== 回调接口 ==========
    public interface AgentCallback {
        // 思考相关
        void onThinkingStart(String stepTitle);
        void onThinkingUpdate(String content);
        void onThinkingEnd(String summary);
        
        // 工具调用相关
        void onToolCallStart(String toolName, String description);
        void onToolCallProgress(String toolName, int progress, String message);
        void onToolCallComplete(String toolName, boolean success, String result);
        
        // 步骤相关
        void onStepStart(int stepNumber, int totalSteps, String stepTitle);
        void onStepUpdate(int stepNumber, String content);
        void onStepComplete(int stepNumber, String summary);
        
        // 流式输出
        void onToken(String token);
        void onThinkingToken(String token);
        
        // 完成和错误
        void onComplete(String fullText, AgentStats stats);
        void onError(String error, boolean canRetry);
        
        // UI 更新
        void onUIUpdate(Runnable uiUpdate);
    }
    
    // ========== 统计信息 ==========
    public static class AgentStats {
        public int totalTokens;
        public long totalTimeMs;
        public int toolCallCount;
        public int thinkingSteps;
        public float tokensPerSecond;
        
        public String toSummary() {
            return String.format("生成完成: %d tokens, %.1fs, %.1f t/s, %d 次工具调用",
                totalTokens, totalTimeMs / 1000.0f, tokensPerSecond, toolCallCount);
        }
    }
    
    // ========== 成员变量 ==========
    private final Activity activity;
    private final AIService aiService;
    private final AgentService agentService;
    private final AgentCallback callback;
    private final UnifiedAgentEngine engine;
    private final SmartIntentRecognizer intentRecognizer;
    private final Handler mainHandler;
    
    private AgentStats stats;
    private long startTime;
    
    public EnhancedAgentHandler(Activity activity, AIService aiService, 
                               AgentService agentService, AgentCallback callback) {
        this.activity = activity;
        this.aiService = aiService;
        this.agentService = agentService;
        this.callback = callback;
        this.engine = new UnifiedAgentEngine(activity, aiService, null, agentService, false);
        this.intentRecognizer = SmartIntentRecognizer.getInstance(activity);
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        setupEngineCallbacks();
    }
    
    /**
     * 设置引擎回调
     */
    private void setupEngineCallbacks() {
        engine.setCallback(new UnifiedAgentEngine.AgentCallback() {
            @Override
            public void onToken(String token) {
                if (callback != null) {
                    callback.onToken(token);
                }
            }
            
            @Override
            public void onThinkingToken(String token) {
                if (callback != null) {
                    callback.onThinkingToken(token);
                }
            }
            
            @Override
            public void onThinkingEnd() {
                // 思考结束
            }
            
            @Override
            public void onToolCallStart(String toolName, String args) {
                if (callback != null) {
                    mainHandler.post(() -> {
                        callback.onThinkingStart("调用工具: " + toolName);
                        callback.onToolCallStart(toolName, args);
                    });
                }
            }
            
            @Override
            public void onToolCallComplete(String toolName, OnlineToolResult result) {
                if (callback != null) {
                    mainHandler.post(() -> {
                        boolean success = result != null && result.success;
                        String resultStr = result != null ? result.result : "无结果";
                        callback.onToolCallComplete(toolName, success, resultStr);
                        
                        if (stats != null) {
                            stats.toolCallCount++;
                        }
                    });
                }
            }
            
            @Override
            public void onStepUpdate(String step, String detail) {
                if (callback != null) {
                    mainHandler.post(() -> {
                        int stepNum = currentStep.incrementAndGet();
                        callback.onStepStart(stepNum, totalSteps.get(), step);
                        callback.onStepUpdate(stepNum, detail);
                    });
                }
            }
            
            @Override
            public void onComplete(String fullText) {
                if (callback != null) {
                    mainHandler.post(() -> {
                        if (stats != null) {
                            stats.totalTimeMs = System.currentTimeMillis() - startTime;
                            stats.tokensPerSecond = stats.totalTimeMs > 0 ? 
                                (stats.totalTokens * 1000.0f) / stats.totalTimeMs : 0;
                        }
                        callback.onComplete(fullText, stats);
                    });
                }
                isGenerating.set(false);
            }
            
            @Override
            public void onError(String error) {
                if (callback != null) {
                    mainHandler.post(() -> {
                        callback.onError(error, true);
                    });
                }
                isGenerating.set(false);
            }
            
            @Override
            public void onNeedMoreInfo(String missingInfo, String context, List<String> suggestions) {
                // 处理需要更多信息的情况
                AILogger.i(TAG, "Need more info: " + missingInfo);
            }
            
            @Override
            public void onExecutionPaused(String reason, String currentState) {
                // 处理执行暂停
                AILogger.i(TAG, "Execution paused: " + reason);
            }
            
            @Override
            public void onExecutionResuming(String userInput) {
                // 处理执行恢复
                AILogger.i(TAG, "Execution resuming");
            }
            
            @Override
            public void onThinking(String thought) {
                if (callback != null) {
                    mainHandler.post(() -> {
                        callback.onThinkingUpdate(thought);
                    });
                }
            }
            
            @Override
            public void onThinkingStage(String stage) {
                if (callback != null) {
                    mainHandler.post(() -> {
                        callback.onThinkingStart(stage);
                    });
                }
            }
            
            @Override
            public void onInputValidationResult(String paramName, 
                com.oilquiz.app.ai.agent.InputValidator.ValidationResult result) {
                // 处理输入验证结果
                AILogger.i(TAG, "Input validation: " + paramName + ", valid=" + result.valid);
            }
        });
    }
    
    /**
     * 启动 Agent 处理
     */
    public void startAgent(String message) {
        if (isGenerating.getAndSet(true)) {
            AILogger.w(TAG, "Agent already generating");
            return;
        }
        
        // 初始化统计
        stats = new AgentStats();
        startTime = System.currentTimeMillis();
        currentStep.set(0);
        
        // 意图识别
        SmartIntentRecognizer.IntentResult intent = intentRecognizer.recognize(message);
        if (intent != null && intent.intent != null) {
            totalSteps.set(intent.needsTool() ? 5 : 3);
            
            // 显示意图识别结果
            if (callback != null) {
                mainHandler.post(() -> {
                    callback.onStepStart(1, totalSteps.get(), "意图识别");
                    callback.onStepUpdate(1, "识别到任务类型: " + intent.intent.displayName 
                        + " (置信度: " + String.format("%.0f%%", intent.confidence * 100) + ")");
                    callback.onStepComplete(1, "意图识别完成");
                });
            }
        } else {
            totalSteps.set(3);
        }
        
        // 启动引擎
        engine.execute(message, 4096, true);
    }
    
    /**
     * 取消当前处理
     */
    public void cancel() {
        if (isGenerating.get()) {
            engine.cancel();
            isGenerating.set(false);
            if (callback != null) {
                mainHandler.post(() -> {
                    callback.onError("用户取消", false);
                });
            }
        }
    }
    
    /**
     * 检查是否正在生成
     */
    public boolean isGenerating() {
        return isGenerating.get();
    }
    
    /**
     * 获取当前统计
     */
    public AgentStats getStats() {
        return stats;
    }
}
