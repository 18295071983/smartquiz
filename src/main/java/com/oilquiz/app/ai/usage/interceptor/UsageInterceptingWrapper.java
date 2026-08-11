package com.oilquiz.app.ai.usage.interceptor;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.usage.calculator.CostCalculator;
import com.oilquiz.app.ai.usage.calculator.CostCalculator.CostResult;
import com.oilquiz.app.ai.usage.db.DatabaseManager;
import com.oilquiz.app.ai.usage.db.entity.ApiUsageLog;
import com.oilquiz.app.ai.usage.parser.ParserRouter;
import com.oilquiz.app.ai.usage.parser.UsageParser;
import com.oilquiz.app.ai.usage.parser.UsageParser.ParseResult;
import com.oilquiz.app.ai.usage.tracker.TraceManager;

import org.json.JSONException;

import java.io.IOException;

/**
 * API 拦截器
 * 拦截 AI API 调用, 自动解析用量并记录日志
 */
public class UsageInterceptingWrapper {
    
    private static final String TAG = "UsageInterceptingWrapper";
    
    private final Context context;
    private final ParserRouter parserRouter;
    private final CostCalculator costCalculator;
    
    public UsageInterceptingWrapper(Context context) {
        this.context = context.getApplicationContext();
        this.parserRouter = new ParserRouter(context);
        this.costCalculator = new CostCalculator(context);
    }
    
    /**
     * 处理 API 响应 (在 API 调用后调用此方法)
     * 
     * @param traceId 追踪 ID
     * @param sessionId 会话 ID
     * @param userId 用户 ID
     * @param modelId 模型 ID
     * @param providerId 服务商 ID
     * @param responseBody API 响应体 JSON 字符串
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param status 调用状态
     * @param errorMsg 错误信息
     * @return 用量日志记录
     */
    public ApiUsageLog processResponse(String traceId, String sessionId, String userId,
                                        String modelId, String providerId,
                                        String responseBody, long startTime, long endTime,
                                        String status, String errorMsg) {
        ApiUsageLog log = new ApiUsageLog();
        log.setTraceId(traceId);
        log.setSessionId(sessionId);
        log.setUserId(userId);
        log.setModelId(modelId);
        log.setProviderId(providerId);
        log.setCallTime(startTime);
        log.setDuration(endTime - startTime);
        log.setStatus(status);
        log.setErrorMessage(errorMsg);
        
        // 如果调用失败, 直接使用 0 用量
        if (!"success".equals(status) || responseBody == null || responseBody.isEmpty()) {
            log.setInputTokens(0);
            log.setOutputTokens(0);
            log.setCacheHitTokens(0);
            log.setCacheCreateTokens(0);
            log.setTotalTokens(0);
            log.setInputCost(0);
            log.setOutputCost(0);
            log.setCacheCost(0);
            log.setTotalCost(0);
            log.setCacheSavedCost(0);
            
            DatabaseManager.getInstance(context).usageDao().insertUsageLog(log);
            Log.w(TAG, "API 调用失败, 用量为 0");
            return log;
        }
        
        try {
            // 1. 解析用量
            UsageParser parser = parserRouter.getParser(modelId, providerId);
            ParseResult usage = parser.parse(responseBody);
            
            log.setInputTokens(usage.getInputTokens());
            log.setOutputTokens(usage.getOutputTokens());
            log.setCacheHitTokens(usage.getCacheHitTokens());
            log.setCacheCreateTokens(usage.getCacheCreateTokens());
            log.setTotalTokens(usage.getTotalTokens());
            
            // 2. 计算费用
            CostResult cost = costCalculator.calculate(
                modelId, providerId,
                usage.getInputTokens(), usage.getOutputTokens(),
                usage.getCacheHitTokens(), usage.getCacheCreateTokens()
            );
            
            log.setInputCost(cost.getInputCost());
            log.setOutputCost(cost.getOutputCost());
            log.setCacheCost(cost.getCacheCost());
            log.setTotalCost(cost.getTotalCost());
            log.setCacheSavedCost(cost.getCacheSavedCost());
            
            Log.i(TAG, String.format("用量记录 - TraceId: %s, 模型: %s, 总费用: %.6f",
                    traceId, modelId, cost.getTotalCost()));
            
        } catch (Exception e) {
            Log.e(TAG, "解析用量失败: " + e.getMessage(), e);
            // 解析失败时字段保持默认值 0
            log.setStatus("parse_error");
            log.setErrorMessage("解析用量失败: " + e.getMessage());
        }
        
        // 3. 保存日志
        try {
            DatabaseManager.getInstance(context).usageDao().insertUsageLog(log);
        } catch (Exception e) {
            Log.e(TAG, "保存日志失败: " + e.getMessage(), e);
        }
        
        return log;
    }
    
    /**
     * 便捷方法: 从 ChatCompletionStats 获取用量 (OpenAI 格式)
     */
    public ApiUsageLog recordUsage(String traceId, String sessionId, String userId,
                                    String modelId, String providerId,
                                    long inputTokens, long outputTokens,
                                    long startTime, long endTime) {
        ApiUsageLog log = new ApiUsageLog();
        log.setTraceId(traceId);
        log.setSessionId(sessionId);
        log.setUserId(userId);
        log.setModelId(modelId);
        log.setProviderId(providerId);
        log.setInputTokens((int) inputTokens);
        log.setOutputTokens((int) outputTokens);
        log.setTotalTokens((int) (inputTokens + outputTokens));
        log.setCallTime(startTime);
        log.setDuration(endTime - startTime);
        log.setStatus("success");
        
        CostResult cost = costCalculator.calculate(modelId, providerId,
                (int) inputTokens, (int) outputTokens, 0, 0);
        log.setInputCost(cost.getInputCost());
        log.setOutputCost(cost.getOutputCost());
        log.setTotalCost(cost.getTotalCost());
        log.setCacheSavedCost(cost.getCacheSavedCost());
        
        DatabaseManager.getInstance(context).usageDao().insertUsageLog(log);
        return log;
    }
}
