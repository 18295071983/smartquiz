package com.oilquiz.app.database;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.oilquiz.app.model.User;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.model.ScoreHistory;
import com.oilquiz.app.model.StudyPlan;
import com.oilquiz.app.model.Template;
import com.oilquiz.app.model.WrongQuestion;
import com.oilquiz.app.model.FavoriteQuestion;
import com.oilquiz.app.model.Note;
import com.oilquiz.app.model.ChatHistory;
import com.oilquiz.app.model.LogEntry;

import android.content.Context;

@Database(
    entities = {
        User.class,
        Question.class,
        ScoreHistory.class,
        StudyPlan.class,
        Template.class,
        WrongQuestion.class,
        FavoriteQuestion.class,
        Note.class,
        ChatHistory.class,
        LogEntry.class
    },
    version = 20,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    private static AppDatabase INSTANCE;
    
    /** 数据库版本号（需与 @Database 注解的 version 保持一致） */
    public static final int DATABASE_VERSION = 20;

    public abstract UserDao userDao();
    public abstract QuestionDao questionDao();
    public abstract ScoreDao scoreDao();
    public abstract StudyPlanDao studyPlanDao();
    public abstract TemplateDao templateDao();
    public abstract WrongQuestionDao wrongQuestionDao();
    public abstract FavoriteQuestionDao favoriteQuestionDao();
    public abstract NoteDao noteDao();
    public abstract ChatHistoryDao chatHistoryDao();
    public abstract LogEntryDao logEntryDao();

    private static volatile boolean isInitializing = false;
    
    public static synchronized AppDatabase getDatabase(Context context) {
        if (INSTANCE != null && INSTANCE.isOpen()) {
            return INSTANCE;
        }
        
        // 如果正在初始化中，等待一下再试
        if (isInitializing) {
            try {
                Thread.sleep(100);
                if (INSTANCE != null && INSTANCE.isOpen()) {
                    return INSTANCE;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        if (INSTANCE == null || !INSTANCE.isOpen()) {
            isInitializing = true;
            try {
                // 如果之前的实例有问题，先清理
                if (INSTANCE != null && !INSTANCE.isOpen()) {
                    try {
                        INSTANCE.close();
                    } catch (Exception e) {
                        // 忽略关闭异常
                    }
                    INSTANCE = null;
                }
                
                INSTANCE = Room.databaseBuilder(
                    context.getApplicationContext(),
                    AppDatabase.class,
                    "smartquiz_database"
                )
                .addMigrations(MIGRATIONS)
                .fallbackToDestructiveMigration()
                .build();
                System.out.println("数据库初始化成功");
            } catch (Exception e) {
                System.out.println("数据库初始化失败: " + e.getMessage());
                e.printStackTrace();
                // 清理失败的实例
                if (INSTANCE != null) {
                    try {
                        INSTANCE.close();
                    } catch (Exception closeEx) {
                        // 忽略关闭异常
                    }
                    INSTANCE = null;
                }
                return null;
            } finally {
                isInitializing = false;
            }
        }
        return INSTANCE;
    }

    public static void destroyInstance() {
        synchronized (AppDatabase.class) {
            if (INSTANCE != null) {
                try {
                    if (INSTANCE.isOpen()) {
                        INSTANCE.close();
                    }
                } catch (Exception e) {
                    // 忽略关闭异常
                }
                INSTANCE = null;
            }
            isInitializing = false;
        }
    }
    
    public static synchronized void resetInstance() {
        destroyInstance();
    }

    // 数据库迁移定义（按版本顺序添加）
    private static final Migration[] MIGRATIONS = new Migration[]{
        
        // v1 -> v2: 添加索引（示例）
        // new Migration(1, 2) {
        //     @Override
        //     public void migrate(SupportSQLiteDatabase database) {
        //         database.execSQL("CREATE INDEX idx_user_email ON users(email)");
        //     }
        // },
        
        // v17 -> v18: AI 相关字段预留
        new Migration(17, 18) {
            @Override
            public void migrate(SupportSQLiteDatabase database) {
                // 添加 AI 增强字段（如果表中不存在）
                try {
                    database.execSQL("ALTER TABLE questions ADD COLUMN ai_enhanced INTEGER DEFAULT 0");
                } catch (Exception e) {
                    // 字段可能已存在，忽略错误
                }
                try {
                    database.execSQL("ALTER TABLE questions ADD COLUMN ai_tags TEXT");
                } catch (Exception e) {
                    // 字段可能已存在，忽略错误
                }
            }
        },
        
        // v18 -> v19: 多模态支持
        new Migration(18, 19) {
            @Override
            public void migrate(SupportSQLiteDatabase database) {
                // 1. 添加 OCR 历史表
                database.execSQL("CREATE TABLE IF NOT EXISTS ocr_history (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "image_path TEXT, " +
                    "recognized_text TEXT, " +
                    "confidence REAL, " +
                    "language TEXT DEFAULT 'zh', " +
                    "created_at INTEGER DEFAULT (strftime('%s', 'now')), " +
                    "question_count INTEGER DEFAULT 0" +
                ")");
                
                // 2. 添加题目图片关联表
                database.execSQL("CREATE TABLE IF NOT EXISTS question_images (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "question_id INTEGER, " +
                    "image_path TEXT, " +
                    "image_type TEXT, " +
                    "created_at INTEGER DEFAULT (strftime('%s', 'now')), " +
                    "FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE CASCADE" +
                ")");
                
                // 3. 添加 AI 模型使用记录表
                database.execSQL("CREATE TABLE IF NOT EXISTS ai_usage_log (" +
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
                
                // 4. 添加索引
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_ai_enhanced ON questions(ai_enhanced)");
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_created ON questions(created_at)");
            }
        },
        
        // v19 -> v20: 全面升级题目表字段
        new Migration(19, 20) {
            @Override
            public void migrate(SupportSQLiteDatabase database) {
                // 添加创建和更新时间字段
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN createdAt INTEGER DEFAULT 0");
                } catch (Exception e) {
                }
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN updatedAt INTEGER DEFAULT 0");
                } catch (Exception e) {
                }
                
                // 添加题目来源字段
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN source TEXT");
                } catch (Exception e) {
                }
                
                // 添加题目标签字段
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN tags TEXT");
                } catch (Exception e) {
                }
                
                // 添加题目分值字段
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN points INTEGER DEFAULT 1");
                } catch (Exception e) {
                }
                
                // 添加答题时限字段
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN timeLimit INTEGER DEFAULT 0");
                } catch (Exception e) {
                }
                
                // 添加题目提示字段
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN hint TEXT");
                } catch (Exception e) {
                }
                
                // 添加详细解析字段
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN analysis TEXT");
                } catch (Exception e) {
                }
                
                // 添加知识点字段
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN knowledgePoint TEXT");
                } catch (Exception e) {
                }
                
                // 添加子分类字段
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN subCategory TEXT");
                } catch (Exception e) {
                }
                
                // 添加使用统计字段
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN usageCount INTEGER DEFAULT 0");
                } catch (Exception e) {
                }
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN correctCount INTEGER DEFAULT 0");
                } catch (Exception e) {
                }
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN incorrectCount INTEGER DEFAULT 0");
                } catch (Exception e) {
                }
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN lastUsedAt INTEGER DEFAULT 0");
                } catch (Exception e) {
                }
                
                // 添加题目状态字段
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN status INTEGER DEFAULT 0");
                } catch (Exception e) {
                }
                
                // 添加是否公开字段
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN isPublic INTEGER DEFAULT 0");
                } catch (Exception e) {
                }
                
                // 添加题目作者字段
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN author TEXT");
                } catch (Exception e) {
                }
                
                // 添加题目备注字段
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN comment TEXT");
                } catch (Exception e) {
                }
                
                // 添加额外选项字段
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN extraOptions TEXT");
                } catch (Exception e) {
                }
                
                // 添加新索引
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_created ON question(createdAt)");
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_updated ON question(updatedAt)");
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_status ON question(status)");
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_points ON question(points)");
            }
        }
    };
}
