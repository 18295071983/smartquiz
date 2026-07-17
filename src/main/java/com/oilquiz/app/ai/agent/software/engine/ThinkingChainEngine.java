package com.oilquiz.app.ai.agent.software.engine;

import com.oilquiz.app.ai.agent.software.model.ExecutionResult;
import com.oilquiz.app.ai.agent.software.model.ThinkingChain;
import com.oilquiz.app.ai.agent.software.model.ThinkingStep;
import com.oilquiz.app.ai.agent.software.model.Thought;
import com.oilquiz.app.ai.agent.software.model.Action;
import com.oilquiz.app.ai.agent.software.model.Observation;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ThinkingChainEngine - 思考链引擎
 * 
 * 驱动 Agent 的思考过程
 * 实现 Thought → Action → Observation 循环
 * 硬件层调用: LLM.generate()
 */
public class ThinkingChainEngine {
    
    private static final String TAG = "ThinkingChainEngine";
    private static final int MAX_THINKING_STEPS = 5;
    
    private final AIService aiService;
    
    public ThinkingChainEngine(AIService aiService) {
        this.aiService = aiService;
    }
    
    /**
     * 处理执行结果，生成思考链
     */
    public ThinkingChain process(ExecutionResult executionResult, String userMessage) {
        ThinkingChain chain = new ThinkingChain();
        int stepCount = 0;
        
        // 初始思考
        Thought initialThought = generateInitialThought(userMessage, executionResult);
        chain.addStep(new ThinkingStep(initialThought, null, null));
        stepCount++;
        
        while (stepCount < MAX_THINKING_STEPS) {
            // Thought: 分析当前状态
            Thought thought = generateThought(executionResult, chain);
            
            // Action: 决定下一步
            Action action = decideAction(thought, executionResult);
            
            // Observation: 执行动作并观察
            Observation observation = executeAction(action);
            
            // 添加步骤
            ThinkingStep step = new ThinkingStep(thought, action, observation);
            chain.addStep(step);
            stepCount++;
            
            // 检查是否完成
            if (thought.isComplete() || observation.isFinal()) {
                AILogger.i(TAG, "Thinking chain completed at step " + stepCount);
                break;
            }
        }
        
        return chain;
    }
    
    /**
     * 生成初始思考
     */
    private Thought generateInitialThought(String userMessage, ExecutionResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户请求: ").append(userMessage).append("\n");
        
        if (result != null && !result.getTaskResults().isEmpty()) {
            sb.append("执行结果: 已完成 ").append(result.getTaskResults().size()).append(" 个任务\n");
        }
        
        sb.append("我需要分析这些信息，给出合适的回复。");
        
        return new Thought(sb.toString(), false);
    }
    
    /**
     * 生成思考
     */
    private Thought generateThought(ExecutionResult result, ThinkingChain chain) {
        try {
            String prompt = buildThoughtPrompt(result, chain);
            
            // 如果聊天上下文活跃，先关闭再使用 generate
            boolean contextWasActive = com.oilquiz.app.ai.jni.LlamaHelper.isChatContextActive();
            if (contextWasActive) {
                AILogger.i(TAG, "Chat context active, destroying before thought generation");
                try {
                    com.oilquiz.app.ai.jni.LlamaHelper.chatDestroy();
                    Thread.sleep(100);
                } catch (Exception e) {
                    AILogger.w(TAG, "Error destroying chat context: " + e.getMessage());
                }
            }
            
            // 调用 LLM 生成思考
            String response = com.oilquiz.app.ai.jni.LlamaHelper.generate(prompt, 200, 0.3f);
            
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
     */
    private String buildThoughtPrompt(ExecutionResult result, ThinkingChain chain) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能助手，正在分析任务执行结果。\n\n");
        
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
                sb.append(entry.getValue().isSuccess() ? entry.getValue().getResult() : "失败");
                sb.append("\n");
            }
        }
        
        sb.append("\n【请分析】\n");
        sb.append("1. 结果是否满足用户需求？\n");
        sb.append("2. 是否需要进一步处理？\n");
        sb.append("3. 如果完成，请说明理由。\n\n");
        sb.append("输出格式:\n");
        sb.append("ANALYSIS: 分析内容\n");
        sb.append("COMPLETE: yes/no\n");
        
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
     */
    private Action decideAction(Thought thought, ExecutionResult result) {
        // 简单的动作决定逻辑
        if (thought.isComplete()) {
            return new Action(Action.ActionType.FINISH, "生成最终回复", null);
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
        
        // 继续分析
        return Observation.continue_observation("分析继续进行中");
    }
}
