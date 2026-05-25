package com.oilquiz.app.ai.model;

import java.io.Serializable;

/**
 * API 使用量信息
 */
public class UsageInfo implements Serializable {
    
    /**
     * 总额度（tokens 或金额）
     */
    public long totalQuota;
    
    /**
     * 已使用额度
     */
    public long usedQuota;
    
    /**
     * 剩余额度
     */
    public long remainingQuota;
    
    /**
     * 额度类型：tokens 或 credits
     */
    public String quotaType;
    
    /**
     * 统计周期
     */
    public String period;
    
    /**
     * 费用信息（美元）
     */
    public double totalCost;
    
    /**
     * 已使用费用
     */
    public double usedCost;
    
    /**
     * 单位名称
     */
    public String unitName;
    
    /**
     * 更新时间
     */
    public long lastUpdated;
    
    /**
     * 是否支持使用量查询
     */
    public boolean supported;
    
    /**
     * 错误信息（如果查询失败）
     */
    public String errorMessage;
    
    public UsageInfo() {
        this.quotaType = "tokens";
        this.period = "monthly";
        this.unitName = "tokens";
        this.supported = false;
        this.lastUpdated = System.currentTimeMillis();
    }
    
    /**
     * 获取使用百分比
     */
    public int getUsagePercentage() {
        if (totalQuota <= 0) return 0;
        return (int) ((usedQuota * 100) / totalQuota);
    }
    
    /**
     * 格式化已用额度显示
     */
    public String getFormattedUsed() {
        if (totalQuota > 1000000) {
            return String.format("%.1fM", usedQuota / 1000000.0);
        } else if (totalQuota > 1000) {
            return String.format("%.1fK", usedQuota / 1000.0);
        }
        return String.valueOf(usedQuota);
    }
    
    /**
     * 格式化总量显示
     */
    public String getFormattedTotal() {
        if (totalQuota > 1000000) {
            return String.format("%.1fM", totalQuota / 1000000.0);
        } else if (totalQuota > 1000) {
            return String.format("%.1fK", totalQuota / 1000.0);
        }
        return String.valueOf(totalQuota);
    }
    
    /**
     * 格式化剩余额度显示
     */
    public String getFormattedRemaining() {
        if (remainingQuota > 1000000) {
            return String.format("%.1fM", remainingQuota / 1000000.0);
        } else if (remainingQuota > 1000) {
            return String.format("%.1fK", remainingQuota / 1000.0);
        }
        return String.valueOf(remainingQuota);
    }
    
    /**
     * 创建空的 UsageInfo
     */
    public static UsageInfo createEmpty() {
        UsageInfo info = new UsageInfo();
        info.totalQuota = 0;
        info.usedQuota = 0;
        info.remainingQuota = 0;
        info.supported = false;
        return info;
    }
    
    /**
     * 创建不支持使用量查询的 UsageInfo
     */
    public static UsageInfo createUnsupported(String message) {
        UsageInfo info = new UsageInfo();
        info.supported = false;
        info.errorMessage = message;
        return info;
    }
    
    @Override
    public String toString() {
        return String.format("UsageInfo[used=%s/%s (%d%%), remaining=%s]",
            getFormattedUsed(), getFormattedTotal(), getUsagePercentage(), getFormattedRemaining());
    }
}