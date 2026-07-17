package com.oilquiz.app.ai.agent.software.result;

import com.oilquiz.app.ai.agent.software.model.AgentResponse;
import com.oilquiz.app.ai.agent.software.model.ExecutionResult;
import com.oilquiz.app.ai.agent.software.model.TaskResult;
import com.oilquiz.app.ai.agent.software.model.ThinkingChain;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.Map;

/**
 * ResultIntegrator - 结果整合模块
 * 
 * 整合所有结果生成最终回复
 * 硬件层调用: LLM.generate()
 */
public class ResultIntegrator {
    
    private static final String TAG = "ResultIntegrator";
    private final AIService aiService;
    
    public ResultIntegrator(AIService aiService) {
        this.aiService = aiService;
    }
    
    /**
     * 整合结果生成最终回复
     */
    public AgentResponse integrate(ThinkingChain chain, ExecutionResult executionResult, 
                                   String userMessage) {
        try {
            // 构建整合 Prompt
            String prompt = buildIntegrationPrompt(chain, executionResult, userMessage);
            
            // 如果聊天上下文活跃，先关闭再使用 generate
            boolean contextWasActive = com.oilquiz.app.ai.jni.LlamaHelper.isChatContextActive();
            if (contextWasActive) {
                AILogger.i(TAG, "Chat context active, destroying before result integration");
                try {
                    com.oilquiz.app.ai.jni.LlamaHelper.chatDestroy();
                    Thread.sleep(100);
                } catch (Exception e) {
                    AILogger.w(TAG, "Error destroying chat context: " + e.getMessage());
                }
            }
            
            // 调用 LLM 生成最终回复
            String response = com.oilquiz.app.ai.jni.LlamaHelper.generate(prompt, 1000, 0.7f);
            
            if (response == null || response.trim().isEmpty()) {
                AILogger.w(TAG, "LLM returned empty response for result integration");
                response = generateFallbackResponse(executionResult, userMessage);
            }
            
            // 构建响应
            return new AgentResponse(response, chain, executionResult);
            
        } catch (Exception e) {
            AILogger.e(TAG, "Error in result integration: " + e.getMessage(), e);
            return new AgentResponse(generateFallbackResponse(executionResult, userMessage), chain, executionResult);
        }
    }
    
    /**
     * 构建整合 Prompt
     */
    private String buildIntegrationPrompt(ThinkingChain chain, ExecutionResult result, 
                                          String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能助手，请根据以下信息生成最终回复。\n\n");
        
        // 用户原始问题
        sb.append("【用户问题】\n");
        sb.append(userMessage).append("\n\n");
        
        // 思考链
        sb.append("【思考过程】\n");
        if (chain != null && !chain.getSteps().isEmpty()) {
            for (int i = 0; i < chain.getSteps().size(); i++) {
                sb.append("Step ").append(i + 1).append(": ");
                sb.append(chain.getSteps().get(i).getThought().getContent()).append("\n");
            }
        } else {
            sb.append("无思考过程\n");
        }
        
        // 执行结果
        sb.append("\n【执行结果】\n");
        if (result != null && !result.getTaskResults().isEmpty()) {
            for (Map.Entry<String, TaskResult> entry : result.getTaskResults().entrySet()) {
                sb.append("- ").append(entry.getKey()).append(": ");
                if (entry.getValue().isSuccess()) {
                    sb.append(entry.getValue().getResult());
                } else {
                    sb.append("失败: ").append(entry.getValue().getError());
                }
                sb.append("\n");
            }
        } else {
            sb.append("无执行结果\n");
        }
        
        sb.append("\n【请生成回复】\n");
        sb.append("请基于以上信息，生成清晰、完整、有帮助的回复。\n");
        sb.append("回复应该：\n");
        sb.append("1. 直接回答用户的问题\n");
        sb.append("2. 包含必要的信息和细节\n");
        sb.append("3. 使用自然易懂的语言\n");
        
        return sb.toString();
    }
    
    /**
     * 生成备用回复
     */
    private String generateFallbackResponse(ExecutionResult result, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("根据您的问题：").append(userMessage).append("\n\n");
        
        if (result != null && !result.getTaskResults().isEmpty()) {
            sb.append("以下是处理结果：\n");
            for (Map.Entry<String, TaskResult> entry : result.getTaskResults().entrySet()) {
                sb.append("- ").append(entry.getValue().getResult()).append("\n");
            }
        } else {
            sb.append("我已完成分析，但无法生成详细的回复。请尝试更具体的问题。");
        }
        
        return sb.toString();
    }
}
