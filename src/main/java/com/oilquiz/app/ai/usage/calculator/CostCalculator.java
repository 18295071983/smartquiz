package com.oilquiz.app.ai.usage.calculator;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.usage.db.DatabaseManager;
import com.oilquiz.app.ai.usage.db.entity.ApiPriceConfig;

/**
 * 费用计算器
 * 根据模型 + 上下文长度查询单价, 计算 Token 费用
 */
public class CostCalculator {
    
    private static final String TAG = "CostCalculator";
    
    private final Context context;
    
    public CostCalculator(Context context) {
        this.context = context.getApplicationContext();
    }
    
    /**
     * 计算费用
     */
    public CostResult calculate(String modelId, String providerId,
                                 int inputTokens, int outputTokens,
                                 int cacheHitTokens, int cacheCreateTokens) {
        
        // 查询价格配置
        ApiPriceConfig inputPrice = DatabaseManager.getInstance(context)
                .priceDao()
                .findBy(modelId, "input");
        
        ApiPriceConfig outputPrice = DatabaseManager.getInstance(context)
                .priceDao()
                .findBy(modelId, "output");
        
        ApiPriceConfig cachePrice = DatabaseManager.getInstance(context)
                .priceDao()
                .findBy(modelId, "cache_read");
        
        // 兼容 cache_hit 和 cache_read 两种价格类型
        if (cachePrice == null) {
            cachePrice = DatabaseManager.getInstance(context)
                    .priceDao()
                    .findBy(modelId, "cache_hit");
        }
        
        if (inputPrice == null && outputPrice == null) {
            Log.w(TAG, "未找到模型 " + modelId + " 的价格配置, 费用为 0");
            return new CostResult(0, 0, 0, 0, 0);
        }
        
        // 计算各部分费用
        double inputCost = inputPrice != null ? 
                (inputTokens / 1_000_000.0) * inputPrice.getPricePer1M() : 0;
        double outputCost = outputPrice != null ? 
                (outputTokens / 1_000_000.0) * outputPrice.getPricePer1M() : 0;
        double cacheCost = cachePrice != null ? 
                (cacheHitTokens / 1_000_000.0) * cachePrice.getPricePer1M() : 0;
        
        double totalCost = inputCost + outputCost + cacheCost;
        
        // 缓存节省费用 = 缓存输入 Token 按正常输入价格 - 缓存输入 Token 按缓存价格
        double cacheSavedCost = 0;
        if (cachePrice != null && inputPrice != null) {
            double normalInputCost = (cacheHitTokens / 1_000_000.0) * inputPrice.getPricePer1M();
            cacheSavedCost = normalInputCost - cacheCost;
        }
        
        Log.i(TAG, String.format("费用计算 - 输入: %.6f, 输出: %.6f, 缓存: %.6f, 总计: %.6f, 节省: %.6f",
                inputCost, outputCost, cacheCost, totalCost, cacheSavedCost));
        
        return new CostResult(
            Math.round(inputCost * 1_000_000) / 1_000_000.0,
            Math.round(outputCost * 1_000_000) / 1_000_000.0,
            Math.round(cacheCost * 1_000_000) / 1_000_000.0,
            Math.round(totalCost * 1_000_000) / 1_000_000.0,
            Math.round(cacheSavedCost * 1_000_000) / 1_000_000.0
        );
    }
    
    /**
     * 计算结果
     */
    public static class CostResult {
        private final double inputCost;
        private final double outputCost;
        private final double cacheCost;
        private final double totalCost;
        private final double cacheSavedCost;
        
        public CostResult(double inputCost, double outputCost, double cacheCost,
                          double totalCost, double cacheSavedCost) {
            this.inputCost = inputCost;
            this.outputCost = outputCost;
            this.cacheCost = cacheCost;
            this.totalCost = totalCost;
            this.cacheSavedCost = cacheSavedCost;
        }
        
        public double getInputCost() { return inputCost; }
        public double getOutputCost() { return outputCost; }
        public double getCacheCost() { return cacheCost; }
        public double getTotalCost() { return totalCost; }
        public double getCacheSavedCost() { return cacheSavedCost; }
    }
}
