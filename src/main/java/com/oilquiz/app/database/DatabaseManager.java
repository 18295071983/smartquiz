package com.oilquiz.app.database;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.model.User;
import com.oilquiz.app.model.ScoreHistory;
import com.oilquiz.app.model.StudyPlan;
import com.oilquiz.app.model.Template;
import com.oilquiz.app.model.WrongQuestion;
import com.oilquiz.app.model.FavoriteQuestion;
import com.oilquiz.app.model.Note;
import com.oilquiz.app.model.ChatHistory;
import com.oilquiz.app.model.LogEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class DatabaseManager {
    private static final String TAG = "DatabaseManager";
    private static DatabaseManager instance;
    private final AppDatabase database;
    private final Context context;
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);
    
    // 单例模式
    public static synchronized DatabaseManager getInstance(Context context) {
        if (instance == null) {
            instance = new DatabaseManager(context);
        }
        return instance;
    }
    
    private DatabaseManager(Context context) {
        this.context = context.getApplicationContext();
        this.database = AppDatabase.getDatabase(context);
    }
    
    // ==================== Question 相关操作 ====================
    
    /**
     * 批量添加题目
     */
    public Future<Boolean> addQuestions(List<Question> questions) {
        return executorService.submit(() -> {
            try {
                database.questionDao().insertAll(questions);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "添加题目失败: " + e.getMessage(), e);
                return false;
            }
        });
    }
    
    /**
     * 更新题目
     */
    public Future<Boolean> updateQuestion(Question question) {
        return executorService.submit(() -> {
            try {
                database.questionDao().update(question);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "更新题目失败: " + e.getMessage(), e);
                return false;
            }
        });
    }
    
    /**
     * 删除题目
     */
    public Future<Boolean> deleteQuestion(long id) {
        return executorService.submit(() -> {
            try {
                database.questionDao().deleteQuestion(id);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "删除题目失败: " + e.getMessage(), e);
                return false;
            }
        });
    }
    
    /**
     * 清空所有题目
     */
    public Future<Boolean> clearAllQuestions() {
        return executorService.submit(() -> {
            try {
                database.questionDao().deleteAllQuestions();
                return true;
            } catch (Exception e) {
                Log.e(TAG, "清空题目失败: " + e.getMessage(), e);
                return false;
            }
        });
    }
    
    /**
     * 根据ID获取题目
     */
    public Future<Question> getQuestionById(long id) {
        return executorService.submit(() -> {
            try {
                return database.questionDao().getQuestionById(id);
            } catch (Exception e) {
                Log.e(TAG, "获取题目失败: " + e.getMessage(), e);
                return null;
            }
        });
    }
    
    /**
     * 获取所有题目
     */
    public Future<List<Question>> getAllQuestions() {
        return executorService.submit(() -> {
            try {
                return database.questionDao().getQuestions();
            } catch (Exception e) {
                Log.e(TAG, "获取所有题目失败: " + e.getMessage(), e);
                return new ArrayList<>();
            }
        });
    }
    
    /**
     * 分页获取题目
     */
    public Future<List<Question>> getQuestionsByPage(int page, int pageSize) {
        return executorService.submit(() -> {
            try {
                int offset = (page - 1) * pageSize;
                return database.questionDao().getQuestionsByPage(pageSize, offset);
            } catch (Exception e) {
                Log.e(TAG, "分页获取题目失败: " + e.getMessage(), e);
                return new ArrayList<>();
            }
        });
    }
    
    /**
     * 根据分类获取题目
     */
    public Future<List<Question>> getQuestionsByCategory(String category) {
        return executorService.submit(() -> {
            try {
                return database.questionDao().getQuestionsByCategory(category);
            } catch (Exception e) {
                Log.e(TAG, "根据分类获取题目失败: " + e.getMessage(), e);
                return new ArrayList<>();
            }
        });
    }
    
    /**
     * 根据难度获取题目
     */
    public Future<List<Question>> getQuestionsByDifficulty(int difficulty) {
        return executorService.submit(() -> {
            try {
                return database.questionDao().getQuestionsByDifficulty(difficulty);
            } catch (Exception e) {
                Log.e(TAG, "根据难度获取题目失败: " + e.getMessage(), e);
                return new ArrayList<>();
            }
        });
    }
    
    /**
     * 根据类型获取题目
     */
    public Future<List<Question>> getQuestionsByType(String type) {
        return executorService.submit(() -> {
            try {
                return database.questionDao().getQuestionsByType(type);
            } catch (Exception e) {
                Log.e(TAG, "根据类型获取题目失败: " + e.getMessage(), e);
                return new ArrayList<>();
            }
        });
    }
    
    /**
     * 搜索题目
     */
    public Future<List<Question>> searchQuestions(String keyword) {
        return executorService.submit(() -> {
            try {
                return database.questionDao().searchQuestions(keyword);
            } catch (Exception e) {
                Log.e(TAG, "搜索题目失败: " + e.getMessage(), e);
                return new ArrayList<>();
            }
        });
    }
    
    /**
     * 高级搜索题目
     */
    public Future<List<Question>> searchQuestionsWithFilters(String keyword, String category, String type, Integer difficulty) {
        return executorService.submit(() -> {
            try {
                return database.questionDao().searchQuestionsWithFilters(keyword, category, type, difficulty);
            } catch (Exception e) {
                Log.e(TAG, "高级搜索题目失败: " + e.getMessage(), e);
                return new ArrayList<>();
            }
        });
    }
    
    /**
     * 获取题目总数
     */
    public Future<Integer> getQuestionCount() {
        return executorService.submit(() -> {
            try {
                return database.questionDao().getQuestionCount();
            } catch (Exception e) {
                Log.e(TAG, "获取题目总数失败: " + e.getMessage(), e);
                return 0;
            }
        });
    }
    
    /**
     * 获取所有分类
     */
    public Future<List<String>> getAllCategories() {
        return executorService.submit(() -> {
            try {
                return database.questionDao().getAllCategories();
            } catch (Exception e) {
                Log.e(TAG, "获取所有分类失败: " + e.getMessage(), e);
                return new ArrayList<>();
            }
        });
    }
    
    /**
     * 获取所有题目类型
     */
    public Future<List<String>> getAllQuestionTypes() {
        return executorService.submit(() -> {
            try {
                return database.questionDao().getAllQuestionTypes();
            } catch (Exception e) {
                Log.e(TAG, "获取所有题目类型失败: " + e.getMessage(), e);
                return new ArrayList<>();
            }
        });
    }
    
    // ==================== 其他实体相关操作 ====================
    
    /**
     * 添加用户
     */
    public Future<Long> addUser(User user) {
        return executorService.submit(() -> {
            try {
                return database.userDao().insert(user);
            } catch (Exception e) {
                Log.e(TAG, "添加用户失败: " + e.getMessage(), e);
                return -1L;
            }
        });
    }
    
    /**
     * 获取用户
     */
    public Future<User> getUser(String username) {
        return executorService.submit(() -> {
            try {
                return database.userDao().getByUsername(username);
            } catch (Exception e) {
                Log.e(TAG, "获取用户失败: " + e.getMessage(), e);
                return null;
            }
        });
    }
    
    /**
     * 添加分数记录
     */
    public Future<Long> addScore(ScoreHistory score) {
        return executorService.submit(() -> {
            try {
                return database.scoreDao().insert(score);
            } catch (Exception e) {
                Log.e(TAG, "添加分数记录失败: " + e.getMessage(), e);
                return -1L;
            }
        });
    }
    
    /**
     * 获取分数记录
     */
    public Future<List<ScoreHistory>> getScoreHistory(long userId) {
        return executorService.submit(() -> {
            try {
                return database.scoreDao().getScoresByUserId(userId);
            } catch (Exception e) {
                Log.e(TAG, "获取分数记录失败: " + e.getMessage(), e);
                return new ArrayList<>();
            }
        });
    }
    
    /**
     * 获取分数记录（按分类）
     */
    public Future<List<ScoreHistory>> getScoreHistoryByCategory(String category) {
        return executorService.submit(() -> {
            try {
                return database.scoreDao().getScoresByCategory(category);
            } catch (Exception e) {
                Log.e(TAG, "获取分数记录失败: " + e.getMessage(), e);
                return new ArrayList<>();
            }
        });
    }
    
    /**
     * 获取用户平均分
     */
    public Future<Float> getAverageScore(long userId) {
        return executorService.submit(() -> {
            try {
                return database.scoreDao().getAverageScoreByUserId(userId);
            } catch (Exception e) {
                Log.e(TAG, "获取平均分失败: " + e.getMessage(), e);
                return 0.0f;
            }
        });
    }
    
    // ==================== 事务操作 ====================
    
    /**
     * 执行事务
     */
    public <T> Future<T> executeTransaction(Callable<T> task) {
        return executorService.submit(() -> {
            try {
                return database.runInTransaction(task);
            } catch (Exception e) {
                Log.e(TAG, "执行事务失败: " + e.getMessage(), e);
                return null;
            }
        });
    }
    
    // ==================== 统计功能 ====================
    
    /**
     * 获取题目统计信息
     */
    public Future<QuestionStatistics> getQuestionStatistics() {
        return executorService.submit(() -> {
            try {
                QuestionStatistics stats = new QuestionStatistics();
                stats.totalQuestions = database.questionDao().getQuestionCount();
                stats.easyQuestions = database.questionDao().getEasyQuestionCount();
                stats.mediumQuestions = database.questionDao().getMediumQuestionCount();
                stats.hardQuestions = database.questionDao().getHardQuestionCount();
                stats.noDifficultyQuestions = database.questionDao().getNoDifficultyQuestionCount();
                stats.categories = database.questionDao().getAllCategories().size();
                stats.questionTypes = database.questionDao().getAllQuestionTypes().size();
                return stats;
            } catch (Exception e) {
                Log.e(TAG, "获取题目统计信息失败: " + e.getMessage(), e);
                return new QuestionStatistics();
            }
        });
    }
    
    // 题目统计信息类
    public static class QuestionStatistics {
        public int totalQuestions;
        public int easyQuestions;
        public int mediumQuestions;
        public int hardQuestions;
        public int noDifficultyQuestions;
        public int categories;
        public int questionTypes;
        
        public QuestionStatistics() {
            totalQuestions = 0;
            easyQuestions = 0;
            mediumQuestions = 0;
            hardQuestions = 0;
            noDifficultyQuestions = 0;
            categories = 0;
            questionTypes = 0;
        }
    }
    
    // ==================== 数据库版本管理 ====================
    
    /**
     * 获取当前数据库版本
     */
    public int getDatabaseVersion() {
        try {
            if (database == null) {
                Log.e(TAG, "数据库实例为空，无法获取版本");
                return -1;
            }
            return database.getOpenHelper().getWritableDatabase().getVersion();
        } catch (Exception e) {
            Log.e(TAG, "获取数据库版本失败", e);
            return -1;
        }
    }
    
    /**
     * 获取数据库版本信息
     */
    public DatabaseVersionChecker.UpgradeInfo getVersionInfo() {
        int currentVersion = getDatabaseVersion();
        if (currentVersion < 0) {
            DatabaseVersionChecker.UpgradeInfo info = new DatabaseVersionChecker.UpgradeInfo();
            info.currentVersion = -1;
            info.message = "无法获取数据库版本";
            return info;
        }
        return DatabaseVersionChecker.checkUpgrade(currentVersion);
    }
    
    /**
     * 检查是否有可用更新
     */
    public boolean hasAvailableUpdate() {
        DatabaseVersionChecker.UpgradeInfo info = getVersionInfo();
        return info != null && info.needUpgrade;
    }
    
    /**
     * 获取版本迁移路径
     */
    public java.util.List<Integer> getMigrationPath() {
        int currentVersion = getDatabaseVersion();
        if (currentVersion < 0) {
            return new java.util.ArrayList<>();
        }
        return DatabaseVersionChecker.getMigrationPath(
            currentVersion, DatabaseVersionChecker.LATEST_VERSION);
    }
    
    /**
     * 执行数据库升级
     */
    public Future<Boolean> upgradeDatabase() {
        return executorService.submit(() -> {
            try {
                DatabaseUpgradeManager upgradeManager = 
                    DatabaseUpgradeManager.getInstance(context);
                return upgradeManager.upgrade(DatabaseVersionChecker.LATEST_VERSION).get();
            } catch (Exception e) {
                Log.e(TAG, "数据库升级失败", e);
                return false;
            }
        });
    }
    
    /**
     * 执行数据库升级（指定目标版本）
     */
    public Future<Boolean> upgradeDatabase(int targetVersion) {
        return executorService.submit(() -> {
            try {
                DatabaseUpgradeManager upgradeManager = 
                    DatabaseUpgradeManager.getInstance(context);
                return upgradeManager.upgrade(targetVersion).get();
            } catch (Exception e) {
                Log.e(TAG, "数据库升级失败", e);
                return false;
            }
        });
    }
    
    /**
     * 备份数据库
     */
    public Future<String> backupDatabase() {
        return executorService.submit(() -> {
            try {
                DatabaseUpgradeManager upgradeManager = 
                    DatabaseUpgradeManager.getInstance(context);
                return upgradeManager.backupDatabase();
            } catch (Exception e) {
                Log.e(TAG, "数据库备份失败", e);
                return null;
            }
        });
    }
    
    /**
     * 回滚数据库
     */
    public Future<Boolean> rollbackDatabase() {
        return executorService.submit(() -> {
            try {
                DatabaseUpgradeManager upgradeManager = 
                    DatabaseUpgradeManager.getInstance(context);
                return upgradeManager.rollback();
            } catch (Exception e) {
                Log.e(TAG, "数据库回滚失败", e);
                return false;
            }
        });
    }
    
    /**
     * 获取备份列表
     */
    public java.util.List<String> getBackupList() {
        try {
            DatabaseUpgradeManager upgradeManager = 
                DatabaseUpgradeManager.getInstance(context);
            return upgradeManager.getBackupList();
        } catch (Exception e) {
            Log.e(TAG, "获取备份列表失败", e);
            return new java.util.ArrayList<>();
        }
    }
    
    /**
     * 重置数据库（危险操作）
     */
    public Future<Boolean> resetDatabase() {
        return executorService.submit(() -> {
            try {
                if (database == null) {
                    Log.e(TAG, "数据库实例为空，无法重置");
                    return false;
                }
                // 1. 备份当前数据库
                backupDatabase().get();
                
                // 2. 关闭数据库
                database.close();
                
                // 3. 删除数据库文件
                String dbPath = context.getDatabasePath("smartquiz_database").getPath();
                java.io.File dbFile = new java.io.File(dbPath);
                if (dbFile.exists()) {
                    dbFile.delete();
                }
                
                // 4. 删除 WAL 和 SHM 文件
                java.io.File walFile = new java.io.File(dbPath + "-wal");
                java.io.File shmFile = new java.io.File(dbPath + "-shm");
                if (walFile.exists()) walFile.delete();
                if (shmFile.exists()) shmFile.delete();
                
                // 5. 重新初始化
                AppDatabase.destroyInstance();
                instance = new DatabaseManager(context);
                
                Log.i(TAG, "数据库重置完成");
                return true;
            } catch (Exception e) {
                Log.e(TAG, "数据库重置失败", e);
                return false;
            }
        });
    }
    
    /**
     * 获取数据库文件大小
     */
    public long getDatabaseSize() {
        try {
            String dbPath = context.getDatabasePath("smartquiz_database").getPath();
            java.io.File dbFile = new java.io.File(dbPath);
            return dbFile.exists() ? dbFile.length() : 0;
        } catch (Exception e) {
            Log.e(TAG, "获取数据库大小失败", e);
            return 0;
        }
    }
    
    /**
     * 验证数据库完整性
     */
    public Future<Boolean> verifyDatabaseIntegrity() {
        return executorService.submit(() -> {
            try {
                if (database == null) {
                    Log.e(TAG, "数据库实例为空，无法验证完整性");
                    return false;
                }
                database.getOpenHelper().getReadableDatabase();
                database.questionDao().getQuestionCount();
                return true;
            } catch (Exception e) {
                Log.e(TAG, "数据库完整性验证失败", e);
                return false;
            }
        });
    }
    
    /**
     * 检查数据库是否正在升级
     */
    public boolean isUpgrading() {
        return DatabaseUpgradeManager.getInstance(context).isUpgrading();
    }
    
    /**
     * 获取升级进度
     */
    public DatabaseUpgradeManager.UpgradeProgress getUpgradeProgress() {
        return DatabaseUpgradeManager.getInstance(context).getCurrentProgress();
    }
    
    // ==================== 清理资源 ====================
    
    public void shutdown() {
        executorService.shutdown();
    }
}
