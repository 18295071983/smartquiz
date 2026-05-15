package com.oilquiz.app.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据库升级管理器
 * 提供安全的数据库升级、备份、回滚功能
 */
public class DatabaseUpgradeManager {
    private static final String TAG = "DatabaseUpgradeManager";
    
    private static DatabaseUpgradeManager instance;
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isUpgrading = new AtomicBoolean(false);
    
    // 升级状态回调
    private UpgradeCallback upgradeCallback;
    
    // 当前升级进度
    private UpgradeProgress currentProgress;

    private DatabaseUpgradeManager(Context context) {
        this.context = context.getApplicationContext();
    }
    
    public static synchronized DatabaseUpgradeManager getInstance(Context context) {
        if (instance == null) {
            instance = new DatabaseUpgradeManager(context);
        }
        return instance;
    }

    // ==================== 升级回调接口 ====================
    
    public interface UpgradeCallback {
        void onUpgradeStart(int fromVersion, int toVersion);
        void onProgressUpdate(UpgradeProgress progress);
        void onUpgradeSuccess(int newVersion);
        void onUpgradeFailed(String error, Throwable throwable);
    }
    
    /**
     * 升级进度信息
     */
    public static class UpgradeProgress {
        public int fromVersion;
        public int toVersion;
        public int currentVersion;
        public int totalSteps;
        public int completedSteps;
        public String currentStep;
        public float progressPercent;
        
        public UpgradeProgress(int from, int to) {
            this.fromVersion = from;
            this.toVersion = to;
            this.totalSteps = to - from;
            this.currentVersion = from;
            this.completedSteps = 0;
            this.progressPercent = 0f;
        }
        
        public void setStep(int step, String description) {
            this.currentVersion = fromVersion + step;
            this.completedSteps = step;
            this.currentStep = description;
            this.progressPercent = (float) step / totalSteps * 100;
        }
        
        public String toDisplayString() {
            return String.format("v%d → v%d (%d/%d): %s", 
                fromVersion, toVersion, completedSteps, totalSteps, currentStep);
        }
    }

    // ==================== 核心升级方法 ====================
    
    /**
     * 设置升级回调
     */
    public void setUpgradeCallback(UpgradeCallback callback) {
        this.upgradeCallback = callback;
    }
    
    /**
     * 执行数据库升级
     * @param targetVersion 目标版本
     * @return Future<Boolean> 升级是否成功
     */
    public Future<Boolean> upgrade(int targetVersion) {
        if (isUpgrading.get()) {
            Log.w(TAG, "升级正在进行中，忽略重复请求");
            return executor.submit(() -> false);
        }
        
        isUpgrading.set(true);
        
        return executor.submit(() -> {
            boolean success = false;
            try {
                // 1. 获取当前版本
                AppDatabase db = AppDatabase.getDatabase(context);
                int currentVersion = db.getOpenHelper().getWritableDatabase().getVersion();
                
                // 2. 检查是否需要升级
                DatabaseVersionChecker.UpgradeInfo info = 
                    DatabaseVersionChecker.checkUpgrade(currentVersion);
                
                if (!info.needUpgrade) {
                    Log.i(TAG, "数据库已是最新版本: " + currentVersion);
                    notifySuccess(currentVersion);
                    return true;
                }
                
                // 3. 开始升级
                Log.i(TAG, String.format("开始升级数据库: v%d → v%d", 
                    currentVersion, targetVersion));
                
                currentProgress = new UpgradeProgress(currentVersion, targetVersion);
                notifyStart(currentVersion, targetVersion);
                
                // 4. 备份数据库
                currentProgress.setStep(0, "备份数据库...");
                notifyProgress();
                String backupPath = backupDatabase();
                if (backupPath == null) {
                    throw new Exception("数据库备份失败");
                }
                Log.i(TAG, "数据库已备份到: " + backupPath);
                
                // 5. 执行迁移
                for (int step = 1; step <= info.migrationPath.size(); step++) {
                    int version = info.migrationPath.get(step - 1);
                    
                    currentProgress.setStep(step, "迁移到 v" + version + "...");
                    notifyProgress();
                    
                    boolean migrated = migrateToVersion(version);
                    if (!migrated) {
                        throw new Exception("迁移到 v" + version + " 失败");
                    }
                    
                    // 每步之间稍微延迟，确保进度更新
                    Thread.sleep(100);
                }
                
                // 6. 验证升级结果
                currentProgress.setStep(currentProgress.totalSteps, "验证升级结果...");
                notifyProgress();
                
                int finalVersion = db.getOpenHelper().getWritableDatabase().getVersion();
                if (finalVersion != targetVersion) {
                    throw new Exception(String.format("版本验证失败: 期望 v%d, 实际 v%d",
                        targetVersion, finalVersion));
                }
                
                // 7. 清理备份
                cleanupOldBackups();
                
                success = true;
                Log.i(TAG, "数据库升级成功: v" + finalVersion);
                notifySuccess(finalVersion);
                
            } catch (Exception e) {
                Log.e(TAG, "数据库升级失败", e);
                notifyFailed(e.getMessage(), e);
                
                // 尝试回滚
                try {
                    Log.w(TAG, "尝试回滚到备份...");
                    rollback();
                } catch (Exception rollbackError) {
                    Log.e(TAG, "回滚也失败了", rollbackError);
                }
            } finally {
                isUpgrading.set(false);
            }
            
            return success;
        });
    }
    
    /**
     * 执行迁移到指定版本
     */
    private boolean migrateToVersion(int targetVersion) {
        AppDatabase db = AppDatabase.getDatabase(context);
        SupportSQLiteDatabase sqlite = db.getOpenHelper().getWritableDatabase();
        
        try {
            sqlite.beginTransaction();
            
            switch (targetVersion) {
                case 19:
                    // v18 → v19: 添加多模态支持
                    migrateToV19(sqlite);
                    break;
                case 20:
                    // v19 → v20: 添加更多功能
                    migrateToV20(sqlite);
                    break;
                default:
                    Log.w(TAG, "未知目标版本: " + targetVersion + "，跳过");
                    break;
            }
            
            sqlite.setVersion(targetVersion);
            sqlite.setTransactionSuccessful();
            sqlite.endTransaction();
            
            Log.i(TAG, "成功迁移到 v" + targetVersion);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "迁移到 v" + targetVersion + " 失败", e);
            if (sqlite.inTransaction()) {
                sqlite.endTransaction();
            }
            return false;
        }
    }
    
    /**
     * 迁移到 v19
     */
    private void migrateToV19(SupportSQLiteDatabase db) {
        Log.i(TAG, "执行 v18 → v19 迁移...");
        
        // 1. 添加 OCR 历史表
        db.execSQL("CREATE TABLE IF NOT EXISTS ocr_history (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "image_path TEXT, " +
            "recognized_text TEXT, " +
            "confidence REAL, " +
            "language TEXT DEFAULT 'zh', " +
            "created_at INTEGER DEFAULT (strftime('%s', 'now')), " +
            "question_count INTEGER DEFAULT 0" +
        ")");
        
        // 2. 添加题目图片关联表
        db.execSQL("CREATE TABLE IF NOT EXISTS question_images (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "question_id INTEGER, " +
            "image_path TEXT, " +
            "image_type TEXT, " +
            "created_at INTEGER DEFAULT (strftime('%s', 'now')), " +
            "FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE CASCADE" +
        ")");
        
        // 3. 添加 AI 模型使用记录表
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_usage_log (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "model_name TEXT, " +
            "model_type TEXT, " +
            "operation_type TEXT, " +
            "prompt_length INTEGER, " +
            "response_length INTEGER, " +
            "tokens_used INTEGER, " +
            "duration_ms INTEGER, " +
            "success INTEGER DEFAULT 1, " +
            "error_message TEXT, " +
            "created_at INTEGER DEFAULT (strftime('%s', 'now'))" +
        ")");
        
        // 4. 为现有表添加新索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_ai_enhanced " +
            "ON questions(ai_enhanced)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_created " +
            "ON questions(created_at)");
        
        Log.i(TAG, "v18 → v19 迁移完成");
    }
    
    /**
     * 迁移到 v20
     */
    private void migrateToV20(SupportSQLiteDatabase db) {
        Log.i(TAG, "执行 v19 → v20 迁移...");
        
        // 预留：可根据需要添加更多表结构
        // 示例：
        // db.execSQL("ALTER TABLE questions ADD COLUMN embedding BLOB");
        
        Log.i(TAG, "v19 → v20 迁移完成");
    }

    // ==================== 备份与回滚 ====================
    
    /**
     * 备份数据库
     * @return 备份文件路径，失败返回 null
     */
    public String backupDatabase() {
        try {
            String dbName = "smartquiz_database";
            File dbFile = context.getDatabasePath(dbName);
            
            if (!dbFile.exists()) {
                Log.w(TAG, "数据库文件不存在: " + dbFile.getPath());
                return null;
            }
            
            // 创建备份目录
            File backupDir = new File(context.getFilesDir(), "db_backups");
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }
            
            // 生成备份文件名（带时间戳）
            String timestamp = new java.text.SimpleDateFormat(
                "yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(new java.util.Date());
            String backupName = dbName + "_v" + 
                AppDatabase.getDatabase(context).getOpenHelper()
                    .getWritableDatabase().getVersion() + 
                "_" + timestamp + ".db";
            
            File backupFile = new File(backupDir, backupName);
            
            // 复制数据库文件
            java.io.FileInputStream fis = new java.io.FileInputStream(dbFile);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(backupFile);
            
            byte[] buffer = new byte[8192];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            
            fis.close();
            fos.close();
            
            Log.i(TAG, "数据库备份成功: " + backupFile.getPath());
            return backupFile.getPath();
            
        } catch (Exception e) {
            Log.e(TAG, "数据库备份失败", e);
            return null;
        }
    }
    
    /**
     * 回滚到最近备份
     * @return 是否成功
     */
    public boolean rollback() {
        try {
            File backupDir = new File(context.getFilesDir(), "db_backups");
            if (!backupDir.exists()) {
                Log.w(TAG, "没有找到备份目录");
                return false;
            }
            
            // 找到最新的备份文件
            File[] backups = backupDir.listFiles((dir, name) -> 
                name.startsWith("smartquiz_database"));
            
            if (backups == null || backups.length == 0) {
                Log.w(TAG, "没有找到备份文件");
                return false;
            }
            
            // 按修改时间排序，取最新的
            java.util.Arrays.sort(backups, (a, b) -> 
                Long.compare(b.lastModified(), a.lastModified()));
            
            File latestBackup = backups[0];
            Log.i(TAG, "找到最新备份: " + latestBackup.getName());
            
            // 关闭当前数据库
            AppDatabase.destroyInstance();
            
            // 用备份文件覆盖当前数据库
            File currentDb = context.getDatabasePath("smartquiz_database");
            java.io.FileInputStream fis = new java.io.FileInputStream(latestBackup);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(currentDb);
            
            byte[] buffer = new byte[8192];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            
            fis.close();
            fos.close();
            
            Log.i(TAG, "回滚成功");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "回滚失败", e);
            return false;
        }
    }
    
    /**
     * 获取可用备份列表
     */
    public java.util.List<String> getBackupList() {
        java.util.List<String> list = new java.util.ArrayList<>();
        
        File backupDir = new File(context.getFilesDir(), "db_backups");
        if (!backupDir.exists()) {
            return list;
        }
        
        File[] backups = backupDir.listFiles((dir, name) -> 
            name.startsWith("smartquiz_database"));
        
        if (backups != null) {
            for (File f : backups) {
                list.add(f.getName() + " (" + 
                    (f.length() / 1024) + " KB, " +
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", 
                        java.util.Locale.getDefault())
                        .format(new java.util.Date(f.lastModified())) + ")");
            }
        }
        
        return list;
    }
    
    /**
     * 清理旧备份（保留最近 5 个）
     */
    private void cleanupOldBackups() {
        try {
            File backupDir = new File(context.getFilesDir(), "db_backups");
            if (!backupDir.exists()) return;
            
            File[] backups = backupDir.listFiles((dir, name) -> 
                name.startsWith("smartquiz_database"));
            
            if (backups == null || backups.length <= 5) return;
            
            // 按时间排序
            java.util.Arrays.sort(backups, (a, b) -> 
                Long.compare(b.lastModified(), a.lastModified()));
            
            // 删除旧备份（保留最近 5 个）
            for (int i = 5; i < backups.length; i++) {
                if (backups[i].delete()) {
                    Log.i(TAG, "已删除旧备份: " + backups[i].getName());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "清理旧备份失败", e);
        }
    }

    // ==================== 状态查询 ====================
    
    /**
     * 检查是否正在升级
     */
    public boolean isUpgrading() {
        return isUpgrading.get();
    }
    
    /**
     * 获取当前升级进度
     */
    public UpgradeProgress getCurrentProgress() {
        return currentProgress;
    }
    
    /**
     * 获取当前数据库版本
     */
    public int getCurrentVersion() {
        try {
            AppDatabase db = AppDatabase.getDatabase(context);
            return db.getOpenHelper().getWritableDatabase().getVersion();
        } catch (Exception e) {
            Log.e(TAG, "获取数据库版本失败", e);
            return -1;
        }
    }

    // ==================== 回调通知 ====================
    
    private void notifyStart(int from, int to) {
        if (upgradeCallback != null) {
            upgradeCallback.onUpgradeStart(from, to);
        }
    }
    
    private void notifyProgress() {
        if (upgradeCallback != null && currentProgress != null) {
            upgradeCallback.onProgressUpdate(currentProgress);
        }
    }
    
    private void notifySuccess(int newVersion) {
        if (upgradeCallback != null) {
            upgradeCallback.onUpgradeSuccess(newVersion);
        }
    }
    
    private void notifyFailed(String error, Throwable throwable) {
        if (upgradeCallback != null) {
            upgradeCallback.onUpgradeFailed(error, throwable);
        }
    }
}
