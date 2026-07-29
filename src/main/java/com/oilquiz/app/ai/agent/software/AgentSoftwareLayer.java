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

import java.util.List;
import java.util.Map;
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
        void onInferenceProgress(int tokenCount, float tokensPerSecond);
    }
    
    private AgentCallback callback;
    
    public AgentSoftwareLayer(Context context, AIService aiService) {
        this.aiService = aiService;
        
        // 初始化软件层模块
        this.intentRecognizer = new IntentRecognizer(aiService);
        this.complexityAnalyzer = new ComplexityAnalyzer(aiService);
        this.taskDecomposer = new TaskDecomposer(aiService);
        this.executionEngine = new ExecutionEngine(context, aiService);
        this.thinkingChainEngine = new ThinkingChainEngine();
        this.resultIntegrator = new ResultIntegrator(aiService);
        
        // 设置思考链引擎回调
        this.thinkingChainEngine.setCallback(new ThinkingChainEngine.ThinkingCallback() {
            @Override
            public void onThinkingToken(String token) {
                if (callback != null && token != null) {
                    callback.onThinkingUpdate(token);
                }
            }
            
            @Override
            public void onThinkingComplete(String thought) {
                if (callback != null && thought != null) {
                    callback.onThinkingUpdate(thought);
                }
            }
            
            @Override
            public void onStepUpdate(int step, String thought, String action, String observation) {
                if (callback != null) {
                    callback.onStepUpdate("思考步骤 " + step, "思考: " + thought);
                }
            }
            
            @Override
            public void onInferenceProgress(int tokenCount, float tokensPerSecond) {
                if (callback != null) {
                    callback.onInferenceProgress(tokenCount, tokensPerSecond);
                }
            }
        });
        
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
            } catch (Throwable t) {
                AILogger.e(TAG, "Error processing message: " + t.getMessage(), t);
                if (callback != null) {
                    String errorMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    callback.onError("处理失败: " + errorMsg);
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
        int totalTokenCount = 0;
        // 记录起始 chat 上下文占用，用于检测 agent 执行是否导致上下文异常增长
        // （所有模块用 generate 单次推理，不增长 chat KV cache，增长量应为 ~0）
        int startContextUsed = getActualUsedTokens();
        // 统计输入 token：用 countTokens 累计实际处理的文本量
        totalTokenCount += countTokensSafe(userMessage);

        // Step 1: 意图识别 + 复杂度分析（合并为单次 LLM 调用，节省一次推理）
        notifyStep("意图分析", "正在分析用户意图和任务复杂度...");
        IntentRecognizer.IntentAnalysis analysis = intentRecognizer.analyzeWithComplexity(userMessage, null);
        IntentResult intent = analysis.intent;
        ComplexityLevel complexity = analysis.complexity;
        totalTokenCount += countTokensSafe(intent.type + " " + intent.confidence + " " + (intent.entity != null ? intent.entity : ""));
        notifyInferenceProgress(totalTokenCount, calculateTps(totalTokenCount, startTime));
        notifyStep("意图分析完成",
                String.format("类型: %s | 置信度: %.0f%% | 实体: %s | 复杂度: %s | 预估步骤: %d",
                        intent.type,
                        intent.confidence * 100,
                        intent.entity != null && !intent.entity.isEmpty() ? intent.entity : "无",
                        complexity,
                        analysis.estimatedSteps));
        AILogger.i(TAG, "Intent: " + intent.type + " (confidence: " + intent.confidence
                + "), Complexity: " + complexity + ", Steps: " + analysis.estimatedSteps);

        // Step 2: （已合并到 Step 1，保留步骤编号便于后续扩展）

        // Step 3: 任务分解
        notifyStep("任务分解", "正在分解任务...");
        TaskPlan taskPlan = taskDecomposer.decompose(userMessage, intent, complexity);
        totalTokenCount += countTokensSafe(buildTaskPlanDetail(taskPlan));
        notifyInferenceProgress(totalTokenCount, calculateTps(totalTokenCount, startTime));
        notifyStep("任务分解完成", buildTaskPlanDetail(taskPlan));
        AILogger.i(TAG, "Task plan created: " + taskPlan.getTasks().size() + " tasks");

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
        totalTokenCount += countTokensSafe(buildExecutionResultDetail(executionResult));
        notifyInferenceProgress(totalTokenCount, calculateTps(totalTokenCount, startTime));
        notifyStep("任务执行完成", buildExecutionResultDetail(executionResult));
        AILogger.i(TAG, "Execution completed: " + executionResult.getTaskResults().size() + " results");

        // Step 5: 思考链处理
        notifyStep("深度思考", "正在深度分析...");
        ThinkingChain thinkingChain = thinkingChainEngine.process(executionResult, userMessage, complexity);
        totalTokenCount += countTokensSafe(buildThinkingChainSummary(thinkingChain));
        notifyInferenceProgress(totalTokenCount, calculateTps(totalTokenCount, startTime));
        notifyStep("深度思考完成",
                String.format("思考步骤: %d 步 (复杂度: %s)", thinkingChain.getSteps().size(), complexity));
        AILogger.i(TAG, "Thinking chain: " + thinkingChain.getSteps().size() + " steps");

        // Step 6: 结果整合
        notifyStep("生成回复", "正在生成最终回复...");
        AgentResponse response = resultIntegrator.integrate(thinkingChain, executionResult, userMessage);
        totalTokenCount += countTokensSafe(response.finalAnswer);
        notifyInferenceProgress(totalTokenCount, calculateTps(totalTokenCount, startTime));
        notifyStep("回复生成完成",
                String.format("Token: %d | 速度: %.1f t/s | 用时: %.1fs | 回复长度: %d 字符",
                        totalTokenCount,
                        calculateTps(totalTokenCount, startTime),
                        (System.currentTimeMillis() - startTime) / 1000.0f,
                        response.finalAnswer != null ? response.finalAnswer.length() : 0));

        long totalTime = System.currentTimeMillis() - startTime;

        // 检测 agent 执行是否导致 chat 上下文异常增长（所有模块用 generate，不应增长）
        int contextGrowth = getActualUsedTokens() - startContextUsed;
        if (contextGrowth > 100) {
            AILogger.w(TAG, "Chat context grew by " + contextGrowth
                    + " tokens during agent execution (expected ~0), possible chatSend leak");
        }

        response.stats = new AgentStats(
            totalTokenCount,
            totalTime,
            executionResult.getTaskResults().size(),
            thinkingChain.getSteps().size()
        );

        // 上下文预警：使用率 >= 80% 时提醒
        float usagePercent = LlamaHelper.getContextUsagePercent();
        if (usagePercent >= 80.0f && usagePercent < 100.0f) {
            AILogger.w(TAG, String.format("Context usage warning: %.1f%% (%d/%d)",
                    usagePercent, getActualUsedTokens(), LlamaHelper.getContextSize()));
            if (callback != null) {
                callback.onStepUpdate("上下文预警",
                        String.format("上下文已使用 %.0f%%，接近上限，建议开启新会话", usagePercent));
            }
        }

        AILogger.i(TAG, "Agent processing completed in " + totalTime + "ms");

        return response;
    }

    /**
     * 获取 LLM 上下文实际已用 token 数
     */
    private int getActualUsedTokens() {
        return LlamaHelper.getContextUsedTokens();
    }

    /**
     * 安全地统计文本 token 数（native 层不可用时回退到字符数估算）
     */
    private int countTokensSafe(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            int n = LlamaHelper.countTokens(text);
            return n > 0 ? n : Math.max(1, text.length() / 4);
        } catch (Exception e) {
            return Math.max(1, text.length() / 4);
        }
    }

    /**
     * 构建思考链摘要文本（用于 token 统计和日志）
     */
    private String buildThinkingChainSummary(ThinkingChain chain) {
        if (chain == null || chain.getSteps() == null || chain.getSteps().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ThinkingStep step : chain.getSteps()) {
            if (step.getThought() != null && step.getThought().getContent() != null) {
                sb.append(step.getThought().getContent()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 构建任务计划详情：任务数 + 每个任务描述/工具（最多展示 5 条避免过长）
     */
    private String buildTaskPlanDetail(TaskPlan taskPlan) {
        List<Task> tasks = taskPlan.getTasks();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("共 %d 个任务", tasks.size()));
        int limit = Math.min(tasks.size(), 5);
        for (int i = 0; i < limit; i++) {
            Task t = tasks.get(i);
            sb.append("\n  ").append(i + 1).append(". ");
            if (t.getDescription() != null && !t.getDescription().isEmpty()) {
                sb.append(truncate(t.getDescription(), 40));
            } else {
                sb.append("(无描述)");
            }
            if (t.getToolName() != null && !t.getToolName().isEmpty()) {
                sb.append("  [工具: ").append(t.getToolName()).append("]");
            }
        }
        if (tasks.size() > limit) {
            sb.append("\n  ...还有 ").append(tasks.size() - limit).append(" 个任务");
        }
        return sb.toString();
    }

    /**
     * 构建执行结果详情：成功/失败数 + 错误信息
     */
    private String buildExecutionResultDetail(ExecutionResult executionResult) {
        Map<String, TaskResult> results = executionResult.getTaskResults();
        int success = 0, failed = 0;
        StringBuilder errors = new StringBuilder();
        for (TaskResult r : results.values()) {
            if (r.isSuccess()) {
                success++;
            } else {
                failed++;
                if (r.getError() != null && !r.getError().isEmpty()) {
                    if (errors.length() > 0) errors.append("; ");
                    errors.append(truncate(r.getError(), 60));
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("成功 %d / 失败 %d / 共 %d", success, failed, results.size()));
        if (executionResult.hasError()) {
            sb.append("\n执行错误: ").append(truncate(executionResult.getError(), 80));
        }
        if (errors.length() > 0) {
            sb.append("\n失败原因: ").append(errors);
        }
        return sb.toString();
    }

    /**
     * 截断字符串到指定长度，超出加省略号
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }

    private float calculateTps(int tokenCount, long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        return elapsed > 0 ? (tokenCount * 1000.0f) / elapsed : 0;
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
    
    private void notifyInferenceProgress(int tokenCount, float tokensPerSecond) {
        if (callback != null) {
            try {
                callback.onInferenceProgress(tokenCount, tokensPerSecond);
            } catch (Exception e) {
                AILogger.e(TAG, "Error in inference progress callback: " + e.getMessage());
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
