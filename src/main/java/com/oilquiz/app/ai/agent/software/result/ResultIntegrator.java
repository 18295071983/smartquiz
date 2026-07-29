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
            
            // 调用 LLM 生成最终回复（generate 独占推理锁，不触碰 chat context，无需 destroy）
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
     * 包含角色约束、上下文、风格指引和严格输出要求
     */
    private String buildIntegrationPrompt(ThinkingChain chain, ExecutionResult result,
                                          String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业回复生成引擎，基于任务执行结果生成最终用户回复。\n\n");
        sb.append("【角色约束】\n");
        sb.append("- 直接回应用户问题，不要复述任务过程\n");
        sb.append("- 基于执行结果中的真实数据，不要编造未出现的信息\n");
        sb.append("- 如果执行结果失败，诚实告知失败原因并提供替代建议\n");
        sb.append("- 使用自然、清晰、有条理的中文\n");
        sb.append("- 天气类结果用结构化展示（温度/湿度/风速等分点列出）\n");
        sb.append("- 聊天类回复用自然语言，口语化\n");
        sb.append("- 搜索/数据类结果先给结论再补充细节\n\n");

        // 用户原始问题
        sb.append("【用户问题】\n");
        sb.append(userMessage).append("\n\n");

        // 思考链（简要）
        sb.append("【思考过程】\n");
        if (chain != null && !chain.getSteps().isEmpty()) {
            for (int i = 0; i < chain.getSteps().size(); i++) {
                String content = chain.getSteps().get(i).getThought().getContent();
                // 只取每步思考的前 100 字，避免 prompt 过长
                sb.append("Step ").append(i + 1).append(": ")
                  .append(truncate(content, 100)).append("\n");
            }
        } else {
            sb.append("无\n");
        }

        // 执行结果（截断过长的结果避免超出上下文）
        sb.append("\n【执行结果】\n");
        if (result != null && !result.getTaskResults().isEmpty()) {
            for (Map.Entry<String, TaskResult> entry : result.getTaskResults().entrySet()) {
                sb.append("- ").append(entry.getKey()).append(": ");
                if (entry.getValue().isSuccess()) {
                    String r = entry.getValue().getResult();
                    sb.append(r != null ? truncate(r, 1500) : "空结果");
                } else {
                    sb.append("失败: ").append(entry.getValue().getError());
                }
                sb.append("\n");
            }
        } else {
            sb.append("无执行结果\n");
        }

        sb.append("\n【请生成回复】\n");
        sb.append("基于以上信息生成清晰、完整、有帮助的回复。\n");

        return sb.toString();
    }

    /**
     * 截断字符串到指定长度
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…(已截断)";
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
