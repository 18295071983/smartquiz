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
