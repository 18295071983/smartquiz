package com.oilquiz.app.database;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库版本检查工具
 * 提供版本兼容性检查、升级路径规划、版本历史记录
 */
public class DatabaseVersionChecker {
    private static final String TAG = "DatabaseVersionChecker";

    // ==================== 版本常量 ====================
    
    /** 初始版本 */
    public static final int VERSION_INITIAL = 1;
    
    /** 最低支持版本 */
    public static final int MIN_SUPPORTED_VERSION = 1;
    
    /** 当前数据库版本（需与 AppDatabase.version 保持一致） */
    public static final int CURRENT_VERSION = AppDatabase.DATABASE_VERSION;
    
    /** 最新版本（从AppDatabase动态获取，不再硬编码） */
    public static final int LATEST_VERSION = AppDatabase.DATABASE_VERSION;

    // ==================== 版本历史 ====================
    
    private static final Map<Integer, VersionInfo> VERSION_HISTORY = new HashMap<>();
    
    static {
        // 版本 1-10: 基础版本
        VERSION_HISTORY.put(1, new VersionInfo(1, "基础版本", "2024-01-01", 
            "初始数据库结构", VersionType.MAJOR));
        VERSION_HISTORY.put(10, new VersionInfo(10, "添加用户表索引", "2024-03-15",
            "优化用户查询性能", VersionType.MINOR));
        
        // 版本 11-15: 功能增强
        VERSION_HISTORY.put(11, new VersionInfo(11, "添加笔记功能", "2024-06-01",
            "新增 Note 表和 NoteDao", VersionType.MINOR));
        VERSION_HISTORY.put(12, new VersionInfo(12, "添加聊天历史", "2024-08-15",
            "新增 ChatHistory 表", VersionType.MINOR));
        VERSION_HISTORY.put(13, new VersionInfo(13, "添加收藏功能", "2024-10-01",
            "新增 FavoriteQuestion 表", VersionType.MINOR));
        VERSION_HISTORY.put(14, new VersionInfo(14, "添加学习计划", "2024-11-15",
            "新增 StudyPlan 表和模板功能", VersionType.MINOR));
        VERSION_HISTORY.put(15, new VersionInfo(15, "优化题目表", "2024-12-20",
            "添加题目标签和难度字段索引", VersionType.MINOR));
        
        // 版本 16-18: AI 功能准备
        VERSION_HISTORY.put(16, new VersionInfo(16, "AI 功能准备", "2025-02-01",
            "添加 AI 相关字段预留", VersionType.MINOR));
        VERSION_HISTORY.put(17, new VersionInfo(17, "AI 题目增强", "2025-04-15",
            "添加题目 AI 增强相关字段", VersionType.MINOR));
        VERSION_HISTORY.put(18, new VersionInfo(18, "AI 导入增强", "2026-04-04",
            "添加 AI 导入相关字段和网络搜索支持", VersionType.MINOR));
        
        // 版本 19: 多模态支持（规划中）
        VERSION_HISTORY.put(19, new VersionInfo(19, "多模态支持（规划中）", "2026-05-01",
            "添加图片题目支持、OCR 历史表", VersionType.MAJOR));
        
        // 版本 20: 题目字段全面升级
        VERSION_HISTORY.put(20, new VersionInfo(20, "题目字段全面升级", "2026-04-24",
            "添加创建时间、更新时间、题目来源、题目标签、分值、时限、提示、详细解析、知识点、子分类、使用统计、题目状态、是否公开、题目作者、备注、额外选项等字段", VersionType.MAJOR));
    }

    // ==================== 核心方法 ====================
    
    /**
     * 检查是否需要升级
     * @param currentVersion 当前数据库版本
     * @return 升级信息
     */
    public static UpgradeInfo checkUpgrade(int currentVersion) {
        UpgradeInfo info = new UpgradeInfo();
        info.currentVersion = currentVersion;
        info.latestVersion = LATEST_VERSION;
        info.needUpgrade = currentVersion < LATEST_VERSION;
        info.versionsBehind = LATEST_VERSION - currentVersion;
        
        // 检查是否需要强制升级
        info.forceUpgrade = currentVersion < MIN_SUPPORTED_VERSION;
        
        // 生成升级路径
        info.migrationPath = getMigrationPath(currentVersion, LATEST_VERSION);
        
        // 生成消息
        if (currentVersion >= LATEST_VERSION) {
            info.message = "数据库已是最新版本";
        } else if (currentVersion < MIN_SUPPORTED_VERSION) {
            info.message = String.format("版本过低（%d），需要强制升级到 %d", 
                currentVersion, MIN_SUPPORTED_VERSION);
        } else {
            info.message = String.format("有 %d 个版本需要升级", 
                LATEST_VERSION - currentVersion);
        }
        
        return info;
    }
    
    /**
     * 检查版本兼容性
     * @param version 数据库版本
     * @return 是否兼容
     */
    public static boolean isVersionCompatible(int version) {
        return version >= MIN_SUPPORTED_VERSION && version <= LATEST_VERSION;
    }
    
    /**
     * 获取版本迁移路径
     * @param from 起始版本
     * @param to 目标版本
     * @return 需要执行的迁移版本列表
     */
    public static List<Integer> getMigrationPath(int from, int to) {
        List<Integer> path = new ArrayList<>();
        if (from >= to) {
            return path;
        }
        
        for (int v = from + 1; v <= to; v++) {
            path.add(v);
        }
        
        return path;
    }
    
    /**
     * 获取版本信息
     * @param version 版本号
     * @return 版本信息，null 如果版本不存在
     */
    public static VersionInfo getVersionInfo(int version) {
        return VERSION_HISTORY.get(version);
    }
    
    /**
     * 获取所有版本信息
     * @return 版本信息列表
     */
    public static List<VersionInfo> getAllVersionInfo() {
        List<VersionInfo> list = new ArrayList<>();
        List<Integer> keys = new ArrayList<>(VERSION_HISTORY.keySet());
        java.util.Collections.sort(keys);
        for (int key : keys) {
            list.add(VERSION_HISTORY.get(key));
        }
        return list;
    }
    
    /**
     * 获取版本间的变更摘要
     * @param from 起始版本
     * @param to 目标版本
     * @return 变更摘要列表
     */
    public static List<String> getChangeSummary(int from, int to) {
        List<String> summary = new ArrayList<>();
        List<Integer> path = getMigrationPath(from, to);
        
        for (int version : path) {
            VersionInfo info = VERSION_HISTORY.get(version);
            if (info != null) {
                summary.add(String.format("v%d (%s): %s - %s", 
                    version, info.date, info.name, info.description));
            }
        }
        
        return summary;
    }

    // ==================== 内部类 ====================
    
    /**
     * 版本类型枚举
     */
    public enum VersionType {
        MAJOR,  // 重大更新，可能需要数据迁移
        MINOR,  // 小更新，自动迁移
        PATCH   // 修复，不涉及结构变更
    }
    
    /**
     * 版本信息
     */
    public static class VersionInfo {
        public int version;
        public String name;
        public String date;
        public String description;
        public VersionType type;
        
        public VersionInfo(int version, String name, String date, 
                          String description, VersionType type) {
            this.version = version;
            this.name = name;
            this.date = date;
            this.description = description;
            this.type = type;
        }
        
        @Override
        public String toString() {
            return String.format("v%d %s (%s)\n%s", version, name, date, description);
        }
    }
    
    /**
     * 升级信息
     */
    public static class UpgradeInfo {
        public int currentVersion;
        public int latestVersion;
        public boolean needUpgrade;
        public boolean forceUpgrade;
        public int versionsBehind;
        public List<Integer> migrationPath;
        public String message;
        
        public UpgradeInfo() {
            this.migrationPath = new ArrayList<>();
        }
        
        /**
         * 是否可以平滑升级
         */
        public boolean canSmoothUpgrade() {
            // 如果跨版本太多，不建议平滑迁移
            return versionsBehind <= 5;
        }
        
        /**
         * 获取升级预估时间（秒）
         */
        public int estimateUpgradeTime() {
            // 每个版本预估 5-10 秒
            return versionsBehind * 8;
        }
        
        @Override
        public String toString() {
            return String.format(
                "UpgradeInfo{current=%d, latest=%d, needUpgrade=%s, " +
                "versionsBehind=%d, force=%s, message='%s'}",
                currentVersion, latestVersion, needUpgrade, 
                versionsBehind, forceUpgrade, message);
        }
    }
}
