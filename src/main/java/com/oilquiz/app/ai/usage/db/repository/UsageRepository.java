package com.oilquiz.app.ai.usage.db.repository;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.usage.db.DatabaseManager;
import com.oilquiz.app.ai.usage.db.UsageDatabase;
import com.oilquiz.app.ai.usage.db.dao.UsageDao;
import com.oilquiz.app.ai.usage.db.entity.ApiUsageLog;

import java.util.List;

/**
 * 用量日志仓储类
 * 管理用量日志的数据操作
 */
public class UsageRepository {
    
    private static final String TAG = "UsageRepository";
    
    private final UsageDatabase usageDatabase;
    
    public UsageRepository(Context context) {
        this.usageDatabase = DatabaseManager.getInstance(context);
    }
    
    /**
     * 插入用量日志
     */
    public long insertUsageLog(ApiUsageLog log) {
        return usageDatabase.usageDao().insertUsageLog(log);
    }
    
    /**
     * 获取汇总数据
     */
    public SummaryStats getSummaryStats(String userId) {
        Integer totalTokens = usageDatabase.usageDao().sumTotalTokens(userId);
        Double totalCost = usageDatabase.usageDao().sumTotalCost(userId);
        Integer messageCount = usageDatabase.usageDao().countMessages(userId);
        Double cacheSavedCost = usageDatabase.usageDao().sumCacheSavedCost(userId);
        
        return new SummaryStats(
            totalTokens != null ? totalTokens : 0,
            totalCost != null ? totalCost : 0.0,
            messageCount != null ? messageCount : 0,
            cacheSavedCost != null ? cacheSavedCost : 0.0
        );
    }
    
    /**
     * 获取分页历史记录
     */
    public List<ApiUsageLog> getUsageHistory(String userId, int page, int pageSize) {
        int offset = page * pageSize;
        return usageDatabase.usageDao().getUsageHistory(userId, offset, pageSize);
    }
    
    /**
     * 获取记录总数
     */
    public int getTotalCount(String userId) {
        Integer count = usageDatabase.usageDao().countUsageHistory(userId);
        return count != null ? count : 0;
    }
    
    /**
     * 获取最近 7 天趋势
     */
    public List<UsageDao.DailyUsageStats> getWeeklyTrend(String userId) {
        long sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000;
        return usageDatabase.usageDao().getDailyUsage(userId, sevenDaysAgo);
    }
    
    /**
     * 获取模型分布
     */
    public List<UsageDao.ModelUsageStats> getModelDistribution(String userId) {
        return usageDatabase.usageDao().getUsageByModel(userId);
    }
    
    /**
     * 汇总统计数据
     */
    public static class SummaryStats {
        private final int totalTokens;
        private final double totalCost;
        private final int messageCount;
        private final double cacheSavedCost;
        
        public SummaryStats(int totalTokens, double totalCost, int messageCount, double cacheSavedCost) {
            this.totalTokens = totalTokens;
            this.totalCost = totalCost;
            this.messageCount = messageCount;
            this.cacheSavedCost = cacheSavedCost;
        }
        
        public int getTotalTokens() { return totalTokens; }
        public double getTotalCost() { return totalCost; }
        public int getMessageCount() { return messageCount; }
        public double getCacheSavedCost() { return cacheSavedCost; }
    }
}
