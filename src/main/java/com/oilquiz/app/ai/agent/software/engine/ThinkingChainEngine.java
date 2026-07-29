package com.oilquiz.app.ai.agent.software.engine;

import com.oilquiz.app.ai.agent.software.model.ComplexityLevel;
import com.oilquiz.app.ai.agent.software.model.ExecutionResult;
import com.oilquiz.app.ai.agent.software.model.ThinkingChain;
import com.oilquiz.app.ai.agent.software.model.ThinkingStep;
import com.oilquiz.app.ai.agent.software.model.Thought;
import com.oilquiz.app.ai.agent.software.model.Action;
import com.oilquiz.app.ai.agent.software.model.Observation;
import com.oilquiz.app.util.AILogger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ThinkingChainEngine - 思考链引擎
 *
 * 驱动 Agent 的思考过程
 * 实现 Thought → Action → Observation 循环
 *
 * 支持按复杂度动态调整步数：
 * - SIMPLE: 跳过思考链
 * - MEDIUM: 最多 2 步
 * - COMPLEX: 最多 5 步
 */
public class ThinkingChainEngine {

    private static final String TAG = "ThinkingChainEngine";
    private static final int MAX_STEPS_SIMPLE = 0;
    private static final int MAX_STEPS_MEDIUM = 2;
    private static final int MAX_STEPS_COMPLEX = 5;

    private ThinkingCallback callback;

    public interface ThinkingCallback {
        void onThinkingToken(String token);
        void onThinkingComplete(String thought);
        void onStepUpdate(int step, String thought, String action, String observation);
        void onInferenceProgress(int tokenCount, float tokensPerSecond);
    }

    public ThinkingChainEngine() {
    }

    public void setCallback(ThinkingCallback callback) {
        this.callback = callback;
    }

    /**
     * 处理执行结果，生成思考链
     * @param executionResult 执行结果
     * @param userMessage 用户原始消息
     * @param complexity 任务复杂度，决定思考深度
     */
    public ThinkingChain process(ExecutionResult executionResult, String userMessage,
                                 ComplexityLevel complexity) {
        ThinkingChain chain = new ThinkingChain();

        // 简单任务跳过思考链，节省 token
        int maxSteps = getMaxSteps(complexity);
        if (maxSteps <= 0) {
            AILogger.i(TAG, "Skipping thinking chain for SIMPLE task");
            // 仍记录一步初始思考，便于 ResultIntegrator 有上下文
            chain.addStep(new ThinkingStep(
                    new Thought("简单任务，直接生成回复。", true),
                    new Action(Action.ActionType.FINISH, "直接回复", null),
                    Observation.complete("跳过深度思考")));
            return chain;
        }

        int stepCount = 0;

        // 初始思考
        Thought initialThought = generateInitialThought(userMessage, executionResult);
        chain.addStep(new ThinkingStep(initialThought, null, null));
        stepCount++;

        while (stepCount < maxSteps) {
            // Thought: 分析当前状态
            Thought thought = generateThought(executionResult, chain, userMessage);

            // Action: 决定下一步
            Action action = decideAction(thought, executionResult);

            // Observation: 执行动作并观察
            Observation observation = executeAction(action);

            // 添加步骤
            ThinkingStep step = new ThinkingStep(thought, action, observation);
            chain.addStep(step);
            stepCount++;

            // 发送步骤更新回调
            if (callback != null) {
                callback.onStepUpdate(stepCount,
                    thought != null ? thought.getContent() : "",
                    action != null ? action.getDescription() : "",
                    observation != null ? observation.getContent() : "");
            }

            // 检查是否完成
            if (thought.isComplete() || observation.isFinal()) {
                AILogger.i(TAG, "Thinking chain completed at step " + stepCount);
                break;
            }
        }

        if (stepCount >= maxSteps) {
            AILogger.i(TAG, "Thinking chain reached max steps " + maxSteps);
        }

        return chain;
    }

    /**
     * 根据复杂度获取最大思考步数
     */
    private int getMaxSteps(ComplexityLevel complexity) {
        if (complexity == null) return MAX_STEPS_MEDIUM;
        switch (complexity) {
            case SIMPLE: return MAX_STEPS_SIMPLE;
            case MEDIUM: return MAX_STEPS_MEDIUM;
            case COMPLEX: return MAX_STEPS_COMPLEX;
            default: return MAX_STEPS_MEDIUM;
        }
    }

    /**
     * 生成初始思考
     */
    private Thought generateInitialThought(String userMessage, ExecutionResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户请求: ").append(userMessage).append("\n");

        if (result != null && !result.getTaskResults().isEmpty()) {
            sb.append("执行结果: 已完成 ").append(result.getTaskResults().size()).append(" 个任务\n");
            if (result.hasError()) {
                sb.append("存在错误: ").append(result.getError()).append("\n");
            }
        }

        sb.append("我需要分析这些信息，给出合适的回复。");

        return new Thought(sb.toString(), false);
    }

    /**
     * 生成思考
     */
    private Thought generateThought(ExecutionResult result, ThinkingChain chain, String userMessage) {
        try {
            String prompt = buildThoughtPrompt(result, chain, userMessage);

            java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
            long startTime = System.currentTimeMillis();
            java.util.concurrent.atomic.AtomicInteger tokenCount = new java.util.concurrent.atomic.AtomicInteger(0);

            // 使用 generateStream 而非 chatSend：思考链每步是独立推理，prompt 已自包含
            // 全部上下文（历史思考+执行结果作为文本拼入），不应累积进 chat KV cache，
            // 否则连续推理会迅速吃满上下文导致解码失败。
            com.oilquiz.app.ai.jni.LlamaHelper.generateStream(prompt, 200, 0.7f, 0.9f, 40, false,
                new com.oilquiz.app.ai.jni.LlamaHelper.TokenCallback() {
                @Override
                public void onToken(String token) {
                    if (token != null) {
                        int currentTokenCount = tokenCount.incrementAndGet();
                        if (callback != null) {
                            callback.onThinkingToken(token);
                            long elapsed = System.currentTimeMillis() - startTime;
                            float tps = elapsed > 0 ? (currentTokenCount * 1000.0f) / elapsed : 0;
                            callback.onInferenceProgress(currentTokenCount, tps);
                        }
                    }
                }

                @Override
                public void onComplete(String fullText) {
                    if (callback != null && fullText != null) {
                        callback.onThinkingComplete(fullText);
                    }
                    future.complete(fullText);
                }

                @Override
                public void onError(String error) {
                    future.completeExceptionally(new RuntimeException(error));
                }
            });

            String response = future.get(15, java.util.concurrent.TimeUnit.SECONDS);

            if (response == null || response.trim().isEmpty()) {
                return new Thought("分析完成，可以生成最终回复。", true);
            }

            return parseThoughtResponse(response);

        } catch (Exception e) {
            AILogger.e(TAG, "Error generating thought: " + e.getMessage(), e);
            return new Thought("思考过程出现错误，将直接生成回复。", true);
        }
    }

    /**
     * 构建思考 Prompt
     * ReAct 风格：Thought → Action → Observation 循环
     */
    private String buildThoughtPrompt(ExecutionResult result, ThinkingChain chain, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个推理引擎，采用 ReAct 模式分析任务执行结果并决定下一步。\n\n");
        sb.append("【角色约束】\n");
        sb.append("- 严格输出指定格式，不要解释、不要多余文字\n");
        sb.append("- ANALYSIS 是你对当前结果的分析推理\n");
        sb.append("- COMPLETE=yes 表示已有足够信息生成最终回复；COMPLETE=no 表示需要更多信息\n");
        sb.append("- 如果结果失败或不完整，在 ANALYSIS 中说明缺什么\n\n");

        // 用户原始问题（让思考有明确目标）
        sb.append("【用户原始问题】\n").append(userMessage).append("\n\n");

        // 历史思考
        if (!chain.getSteps().isEmpty()) {
            sb.append("【之前的思考】\n");
            for (ThinkingStep step : chain.getSteps()) {
                sb.append("- ").append(step.getThought().getContent()).append("\n");
            }
            sb.append("\n");
        }

        // 执行结果
        sb.append("【执行结果】\n");
        if (result != null) {
            for (java.util.Map.Entry<String, com.oilquiz.app.ai.agent.software.model.TaskResult> entry :
                 result.getTaskResults().entrySet()) {
                sb.append("- ").append(entry.getKey()).append(": ");
                String value = entry.getValue().isSuccess()
                        ? entry.getValue().getResult() : "失败: " + entry.getValue().getError();
                sb.append(value != null ? value : "null");
                sb.append("\n");
            }
        } else {
            sb.append("无执行结果\n");
        }
        sb.append("\n");

        sb.append("【示例】\n");
        sb.append("ANALYSIS: 天气数据已获取，包含温度、湿度和风速，信息完整，可以生成回复。\n");
        sb.append("COMPLETE: yes\n\n");
        sb.append("【输出格式（严格）】\n");
        sb.append("ANALYSIS: <分析内容>\n");
        sb.append("COMPLETE: <yes/no>\n");

        return sb.toString();
    }

    /**
     * 解析思考响应
     */
    private Thought parseThoughtResponse(String response) {
        boolean isComplete = false;
        String content = response;

        try {
            Pattern completePattern = Pattern.compile("COMPLETE:\\s*(yes|no)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = completePattern.matcher(response);
            if (matcher.find()) {
                isComplete = matcher.group(1).toLowerCase().equals("yes");
            }

            // 提取分析内容
            Pattern analysisPattern = Pattern.compile("ANALYSIS:\\s*(.+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher analysisMatcher = analysisPattern.matcher(response);
            if (analysisMatcher.find()) {
                content = analysisMatcher.group(1).trim();
            }

        } catch (Exception e) {
            AILogger.w(TAG, "Error parsing thought response: " + e.getMessage());
        }

        return new Thought(content, isComplete);
    }

    /**
     * 决定动作
     * 如果思考标记完成 → FINISH
     * 如果执行结果有错误 → RETRY（提示重试）
     * 否则 → CONTINUE
     */
    private Action decideAction(Thought thought, ExecutionResult result) {
        if (thought.isComplete()) {
            return new Action(Action.ActionType.FINISH, "生成最终回复", null);
        }

        // 如果执行结果有错误，标记重试
        if (result != null && result.hasError()) {
            return new Action(Action.ActionType.RETRY, "重试失败的任务: " + result.getError(), null);
        }

        return new Action(Action.ActionType.CONTINUE, "继续分析", null);
    }

    /**
     * 执行动作
     */
    private Observation executeAction(Action action) {
        if (action.getType() == Action.ActionType.FINISH) {
            return Observation.complete(action.getDescription());
        }

        if (action.getType() == Action.ActionType.RETRY) {
            return Observation.continue_observation("检测到失败，将由上层决定是否重试: " + action.getDescription());
        }

        // 继续分析
        return Observation.continue_observation("分析继续进行中");
    }
}
