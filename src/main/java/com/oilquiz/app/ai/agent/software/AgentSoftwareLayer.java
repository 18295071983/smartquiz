package com.oilquiz.app.ai.agent.software;

import android.content.Context;

import com.oilquiz.app.ai.agent.software.engine.ExecutionEngine;
import com.oilquiz.app.ai.agent.software.engine.ThinkingChainEngine;
import com.oilquiz.app.ai.agent.software.model.*;
import com.oilquiz.app.ai.agent.software.recognizer.ComplexityAnalyzer;
import com.oilquiz.app.ai.agent.software.recognizer.IntentRecognizer;
import com.oilquiz.app.ai.agent.software.recognizer.TaskDecomposer;
import com.oilquiz.app.ai.agent.software.result.ResultIntegrator;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AgentSoftwareLayer - Agent 软件层架构
 * 
 * 职责：
 * 1. 意图识别 - 使用 LLM 识别用户意图
 * 2. 复杂度分析 - 使用 LLM 评估任务复杂度
 * 3. 任务分解 - 使用 LLM 分解复杂任务
 * 4. 执行引擎 - 管理工具调用和任务执行
 * 5. 思考链引擎 - 驱动 Agent 的思考过程
 * 6. 结果整合 - 整合结果生成最终回复
 * 
 * 硬件层调用：
 * - LLM.generate() - 本地 LLM 推理
 * - LLM.chatSend() - 聊天上下文推理
 * - Tool.execute() - 工具执行
 */
public class AgentSoftwareLayer {
    
    private static final String TAG = "AgentSoftwareLayer";
    
    // ========== 硬件层引用 ==========
    private final AIService aiService;
    
    // ========== 软件层模块 ==========
    private final IntentRecognizer intentRecognizer;
    private final ComplexityAnalyzer complexityAnalyzer;
    private final TaskDecomposer taskDecomposer;
    private final ExecutionEngine executionEngine;
    private final ThinkingChainEngine thinkingChainEngine;
    private final ResultIntegrator resultIntegrator;
    
    // ========== 状态管理 ==========
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    // ========== 回调 ==========
    public interface AgentCallback {
        void onStepUpdate(String step, String detail);
        void onThinkingUpdate(String thought);
        void onToolCallStart(String toolName, String args);
        void onToolCallComplete(String toolName, boolean success, String result);
        void onComplete(AgentResponse response);
        void onError(String error);
    }
    
    private AgentCallback callback;
    
    public AgentSoftwareLayer(Context context, AIService aiService) {
        this.aiService = aiService;
        
        // 初始化软件层模块
        this.intentRecognizer = new IntentRecognizer(aiService);
        this.complexityAnalyzer = new ComplexityAnalyzer(aiService);
        this.taskDecomposer = new TaskDecomposer(aiService);
        this.executionEngine = new ExecutionEngine(aiService);
        this.thinkingChainEngine = new ThinkingChainEngine(aiService);
        this.resultIntegrator = new ResultIntegrator(aiService);
        
        AILogger.i(TAG, "AgentSoftwareLayer initialized");
    }
    
    /**
     * 设置回调
     */
    public void setCallback(AgentCallback callback) {
        this.callback = callback;
    }
    
    /**
     * 处理用户消息 - 主入口
     */
    public void processMessage(String userMessage) {
        if (isProcessing.getAndSet(true)) {
            AILogger.w(TAG, "Already processing a message");
            return;
        }
        
        executor.execute(() -> {
            try {
                AgentResponse response = processMessageInternal(userMessage);
                if (callback != null) {
                    callback.onComplete(response);
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Error processing message: " + e.getMessage(), e);
                if (callback != null) {
                    callback.onError("处理失败: " + e.getMessage());
                }
            } finally {
                isProcessing.set(false);
            }
        });
    }
    
    /**
     * 内部处理流程
     */
    private AgentResponse processMessageInternal(String userMessage) {
        long startTime = System.currentTimeMillis();
        
        // Step 1: 意图识别
        notifyStep("意图识别", "正在分析用户意图...");
        IntentResult intent = intentRecognizer.recognize(userMessage);
        AILogger.i(TAG, "Intent recognized: " + intent.type + " (confidence: " + intent.confidence + ")");
        notifyStep("意图识别完成", "意图类型: " + intent.type + ", 置信度: " + String.format("%.0f%%", intent.confidence * 100));
        
        // Step 2: 复杂度分析
        notifyStep("复杂度分析", "正在评估任务复杂度...");
        ComplexityLevel complexity = complexityAnalyzer.analyze(userMessage, intent);
        AILogger.i(TAG, "Complexity analyzed: " + complexity);
        notifyStep("复杂度分析完成", "复杂度级别: " + complexity);
        
        // Step 3: 任务分解
        notifyStep("任务分解", "正在分解任务...");
        TaskPlan taskPlan = taskDecomposer.decompose(userMessage, intent, complexity);
        AILogger.i(TAG, "Task plan created: " + taskPlan.getTasks().size() + " tasks");
        notifyStep("任务分解完成", "任务数量: " + taskPlan.getTasks().size());
        
        // Step 4: 执行任务
        notifyStep("任务执行", "正在执行任务...");
        ExecutionResult executionResult = executionEngine.execute(taskPlan, new ExecutionEngine.ToolCallback() {
            @Override
            public void onToolCallStart(String toolName, String args) {
                if (callback != null) callback.onToolCallStart(toolName, args);
            }
            
            @Override
            public void onToolCallComplete(String toolName, boolean success, String result) {
                if (callback != null) callback.onToolCallComplete(toolName, success, result);
            }
        });
        AILogger.i(TAG, "Execution completed: " + executionResult.getTaskResults().size() + " results");
        notifyStep("任务执行完成", "执行结果数量: " + executionResult.getTaskResults().size());
        
        // Step 5: 思考链处理
        notifyStep("思考链", "正在处理思考链...");
        ThinkingChain thinkingChain = thinkingChainEngine.process(executionResult, userMessage);
        AILogger.i(TAG, "Thinking chain: " + thinkingChain.getSteps().size() + " steps");
        notifyStep("思考链完成", "思考步骤数: " + thinkingChain.getSteps().size());
        
        // Step 6: 结果整合
        notifyStep("结果整合", "正在生成最终回复...");
        AgentResponse response = resultIntegrator.integrate(thinkingChain, executionResult, userMessage);
        
        // 计算统计
        long totalTime = System.currentTimeMillis() - startTime;
        response.stats = new AgentStats(
            response.finalAnswer.length(),
            totalTime,
            executionResult.getTaskResults().size(),
            thinkingChain.getSteps().size()
        );
        
        AILogger.i(TAG, "Agent processing completed in " + totalTime + "ms");
        notifyStep("处理完成", "总耗时: " + totalTime + "ms");
        
        return response;
    }
    
    /**
     * 取消当前处理
     */
    public void cancel() {
        isProcessing.set(false);
        executionEngine.cancel();
    }
    
    /**
     * 检查是否正在处理
     */
    public boolean isProcessing() {
        return isProcessing.get();
    }
    
    /**
     * 通知步骤更新
     */
    private void notifyStep(String step, String detail) {
        AILogger.i(TAG, "Step: " + step + " - " + detail);
        AILogger.i(TAG, "Callback is null: " + (callback == null));
        if (callback != null) {
            try {
                callback.onStepUpdate(step, detail);
                AILogger.i(TAG, "Step callback triggered successfully");
            } catch (Exception e) {
                AILogger.e(TAG, "Error in step callback: " + e.getMessage());
            }
        }
    }
    
    /**
     * 释放资源
     */
    public void destroy() {
        isProcessing.set(false);
        executor.shutdown();
    }
}
