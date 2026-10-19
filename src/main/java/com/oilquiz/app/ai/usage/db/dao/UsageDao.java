package com.oilquiz.app.ai.usage.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.oilquiz.app.ai.usage.db.entity.ApiUsageLog;

import java.util.List;

@Dao
public interface UsageDao {
    
    @Insert
    long insertUsageLog(ApiUsageLog log);
    
    @Query("SELECT SUM(totalTokens) FROM api_usage_log WHERE userId = :userId")
    Integer sumTotalTokens(String userId);
    
    @Query("SELECT SUM(totalCost) FROM api_usage_log WHERE userId = :userId")
    Double sumTotalCost(String userId);
    
    @Query("SELECT SUM(cacheSavedCost) FROM api_usage_log WHERE userId = :userId")
    Double sumCacheSavedCost(String userId);
    
    @Query("SELECT COUNT(*) FROM api_usage_log WHERE userId = :userId")
    Integer countMessages(String userId);
    
    @Query("SELECT * FROM api_usage_log WHERE userId = :userId ORDER BY callTime DESC LIMIT :limit OFFSET :offset")
    List<ApiUsageLog> getUsageHistory(String userId, int offset, int limit);
    
    @Query("SELECT COUNT(*) FROM api_usage_log WHERE userId = :userId")
    Integer countUsageHistory(String userId);
    
    @Query("SELECT DATE(callTime/1000, 'unixepoch') as day, " +
           "SUM(totalTokens) as totalTokens, " +
           "SUM(totalCost) as totalCost " +
           "FROM api_usage_log " +
           "WHERE userId = :userId AND callTime >= :since " +
           "GROUP BY DATE(callTime/1000, 'unixepoch') " +
           "ORDER BY day ASC")
    List<DailyUsageStats> getDailyUsage(String userId, long since);
    
    @Query("SELECT modelId, " +
           "SUM(totalTokens) as totalTokens, " +
           "SUM(totalCost) as totalCost " +
           "FROM api_usage_log " +
           "WHERE userId = :userId " +
           "GROUP BY modelId " +
           "ORDER BY totalCost DESC")
    List<ModelUsageStats> getUsageByModel(String userId);

    /**
     * 按模型+服务商聚合的真实明细：调用次数、输入/输出/缓存 token 分项、最近一次调用时间。
     *
     * <p>为什么不复用上面的 {@code getUsageByModel}：它只回 totalTokens 和 totalCost，
     * 而成本列本应用有意不填（不用价格表），排序会全为 0 失效，也拿不到调用次数与
     * 输入输出拆分 —— 用量页需要的是这些。故新增查询而非改老查询，避免影响既有调用方。</p>
     */
    @Query("SELECT modelId, providerId, " +
           "COUNT(*) as calls, " +
           "SUM(inputTokens) as inputTokens, " +
           "SUM(outputTokens) as outputTokens, " +
           "SUM(cacheHitTokens) as cacheHitTokens, " +
           "SUM(totalTokens) as totalTokens, " +
           "MAX(callTime) as lastCallTime " +
           "FROM api_usage_log " +
           "WHERE userId = :userId AND callTime >= :since " +
           "GROUP BY modelId, providerId " +
           "ORDER BY totalTokens DESC")
    List<ModelUsageRow> getModelUsageDetailSince(String userId, long since);
    
    /**
     * 每日用量统计
     */
    public static class DailyUsageStats {
        public String day;
        public Integer totalTokens;
        public Double totalCost;
        
        public String getDay() { return day; }
        public Integer getTotalTokens() { return totalTokens; }
        public Double getTotalCost() { return totalCost; }
    }

    /**
     * 按模型聚合的调用明细行（{@link #getModelUsageDetailSince} 的结果承载）。
     * 字段名与查询别名一一对应，Room 靠名字映射，改名即断。
     */
    public static class ModelUsageRow {
        public String modelId;
        public String providerId;
        public Integer calls;
        public Integer inputTokens;
        public Integer outputTokens;
        public Integer cacheHitTokens;
        public Integer totalTokens;
        public Long lastCallTime;

        public String getModelId() { return modelId; }
        public String getProviderId() { return providerId; }
        public int getCalls() { return calls == null ? 0 : calls; }
        public int getInputTokens() { return inputTokens == null ? 0 : inputTokens; }
        public int getOutputTokens() { return outputTokens == null ? 0 : outputTokens; }
        public int getCacheHitTokens() { return cacheHitTokens == null ? 0 : cacheHitTokens; }
        public int getTotalTokens() { return totalTokens == null ? 0 : totalTokens; }
        public long getLastCallTime() { return lastCallTime == null ? 0 : lastCallTime; }
    }

    /**
     * 模型用量统计
     */
    public static class ModelUsageStats {
        public String modelId;
        public Integer totalTokens;
        public Double totalCost;
        
        public String getModelId() { return modelId; }
        public Integer getTotalTokens() { return totalTokens; }
        public Double getTotalCost() { return totalCost; }
    }
}
