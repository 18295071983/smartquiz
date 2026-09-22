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
import com.oilquiz.app.ai.sessionlog.SessionEvent;
import com.oilquiz.app.ai.sessionlog.SessionEventDao;

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
        LogEntry.class,
        SessionEvent.class
    },
    version = 25,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    private static AppDatabase INSTANCE;
    
    /** 数据库版本号（需与 @Database 注解的 version 保持一致） */
    public static final int DATABASE_VERSION = 25;

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
    public abstract SessionEventDao sessionEventDao();

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
        },
        
        // v20 -> v21: 多题型支持增强
        new Migration(20, 21) {
            @Override
            public void migrate(SupportSQLiteDatabase database) {
                // 标准答案文本（填空题/简答题）
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN answerText TEXT");
                } catch (Exception e) {}
                // 题目配图路径
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN imageUri TEXT");
                } catch (Exception e) {}
                // 听力题音频路径
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN audioUri TEXT");
                } catch (Exception e) {}
                // 母题ID（子题关联）
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN parentId INTEGER DEFAULT 0");
                } catch (Exception e) {}
                // 排序权重
                try {
                    database.execSQL("ALTER TABLE question ADD COLUMN sortOrder INTEGER DEFAULT 0");
                } catch (Exception e) {}
                // 索引
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_parent ON question(parentId)");
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_sort ON question(sortOrder)");
            }
        },
        
        // v21 -> v22: 选项E~L独立列（替代extraOptions JSON）
        new Migration(21, 22) {
            @Override
            public void migrate(SupportSQLiteDatabase database) {
                // 添加 optionE~L 独立列
                String[] optionCols = {"optionE", "optionF", "optionG", "optionH",
                                       "optionI", "optionJ", "optionK", "optionL"};
                for (String col : optionCols) {
                    try {
                        database.execSQL("ALTER TABLE question ADD COLUMN " + col + " TEXT");
                    } catch (Exception e) {}
                }
                // 迁移 extraOptions JSON 数据到新列
                try {
                    android.database.Cursor cursor = database.query(
                        "SELECT id, extraOptions FROM question WHERE extraOptions IS NOT NULL AND extraOptions != ''");
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(0);
                        String json = cursor.getString(1);
                        if (json != null && !json.isEmpty()) {
                            try {
                                org.json.JSONObject jo = new org.json.JSONObject(json);
                                java.util.Iterator<String> keys = jo.keys();
                                while (keys.hasNext()) {
                                    String key = keys.next();
                                    String val = jo.optString(key, "");
                                    if (!val.isEmpty()) {
                                        database.execSQL(
                                            "UPDATE question SET " + key + " = ? WHERE id = ?",
                                            new Object[]{val, id}
                                        );
                                    }
                                }
                            } catch (Exception ignore) {}
                        }
                    }
                    cursor.close();
                } catch (Exception ignore) {}
            }
        },
        
        // v22 -> v23: 添加 questionType 索引优化查询性能
        new Migration(22, 23) {
            @Override
            public void migrate(SupportSQLiteDatabase database) {
                // 索引名必须与 Room @Index 自动生成的名称一致: index_{table}_{column}
                database.execSQL("CREATE INDEX IF NOT EXISTS index_question_questionType ON question(questionType)");
                database.execSQL("CREATE INDEX IF NOT EXISTS index_question_questionType_status ON question(questionType, status)");
            }
        },

        // v23 -> v24: P0 修复
        // 1) question 表 14 个 NOT NULL 字段补 DEFAULT（重建表方式，SQLite 不支持 ALTER 修改默认值）
        //    使外部 INSERT 只填业务字段即可成功，不再报 NOT NULL constraint failed
        // 2) score_history / wrong_question / favorite_question 补 userId 索引
        new Migration(23, 24) {
            @Override
            public void migrate(SupportSQLiteDatabase database) {
                // ---- 重建 question 表：新表结构必须与 Room 根据 Entity 生成的完全一致 ----
                database.execSQL("CREATE TABLE IF NOT EXISTS question_new (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "questionText TEXT, " +
                    "optionA TEXT, " +
                    "optionB TEXT, " +
                    "optionC TEXT, " +
                    "optionD TEXT, " +
                    "correctAnswer TEXT, " +
                    "category TEXT, " +
                    "difficulty INTEGER NOT NULL DEFAULT 1, " +
                    "explanation TEXT, " +
                    "relatedQuestion TEXT, " +
                    "questionType TEXT, " +
                    "favorite INTEGER NOT NULL DEFAULT 0, " +
                    "createdAt INTEGER NOT NULL DEFAULT 0, " +
                    "updatedAt INTEGER NOT NULL DEFAULT 0, " +
                    "source TEXT, " +
                    "tags TEXT, " +
                    "points INTEGER NOT NULL DEFAULT 0, " +
                    "timeLimit INTEGER NOT NULL DEFAULT 0, " +
                    "hint TEXT, " +
                    "analysis TEXT, " +
                    "knowledgePoint TEXT, " +
                    "subCategory TEXT, " +
                    "usageCount INTEGER NOT NULL DEFAULT 0, " +
                    "correctCount INTEGER NOT NULL DEFAULT 0, " +
                    "incorrectCount INTEGER NOT NULL DEFAULT 0, " +
                    "lastUsedAt INTEGER NOT NULL DEFAULT 0, " +
                    "status INTEGER NOT NULL DEFAULT 0, " +
                    "isPublic INTEGER NOT NULL DEFAULT 1, " +
                    "author TEXT, " +
                    "comment TEXT, " +
                    "optionE TEXT, " +
                    "optionF TEXT, " +
                    "optionG TEXT, " +
                    "optionH TEXT, " +
                    "optionI TEXT, " +
                    "optionJ TEXT, " +
                    "optionK TEXT, " +
                    "optionL TEXT, " +
                    "answerText TEXT, " +
                    "imageUri TEXT, " +
                    "audioUri TEXT, " +
                    "parentId INTEGER NOT NULL DEFAULT 0, " +
                    "sortOrder INTEGER NOT NULL DEFAULT 0)");

                // 迁移存量数据
                database.execSQL("INSERT INTO question_new SELECT " +
                    "id, questionText, optionA, optionB, optionC, optionD, correctAnswer, " +
                    "category, difficulty, explanation, relatedQuestion, questionType, favorite, " +
                    "createdAt, updatedAt, source, tags, points, timeLimit, hint, analysis, " +
                    "knowledgePoint, subCategory, usageCount, correctCount, incorrectCount, " +
                    "lastUsedAt, status, isPublic, author, comment, " +
                    "optionE, optionF, optionG, optionH, optionI, optionJ, optionK, optionL, " +
                    "answerText, imageUri, audioUri, parentId, sortOrder FROM question");

                database.execSQL("DROP TABLE question");
                database.execSQL("ALTER TABLE question_new RENAME TO question");

                // 重建 Entity 声明的 6 个索引（名称必须与 Room 自动生成一致）
                database.execSQL("CREATE INDEX IF NOT EXISTS index_question_category ON question(category)");
                database.execSQL("CREATE INDEX IF NOT EXISTS index_question_difficulty ON question(difficulty)");
                database.execSQL("CREATE INDEX IF NOT EXISTS index_question_favorite ON question(favorite)");
                database.execSQL("CREATE INDEX IF NOT EXISTS index_question_questionType ON question(questionType)");
                database.execSQL("CREATE INDEX IF NOT EXISTS index_question_category_difficulty ON question(category, difficulty)");
                database.execSQL("CREATE INDEX IF NOT EXISTS index_question_questionType_status ON question(questionType, status)");

                // 恢复旧迁移创建的非 Entity 索引（DROP TABLE 会一并丢失，避免查询性能回归）
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_created ON question(createdAt)");
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_updated ON question(updatedAt)");
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_status ON question(status)");
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_points ON question(points)");
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_parent ON question(parentId)");
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_sort ON question(sortOrder)");

                // ---- 补 userId 索引（P0-5）----
                database.execSQL("CREATE INDEX IF NOT EXISTS index_score_history_userId ON score_history(userId)");
                database.execSQL("CREATE INDEX IF NOT EXISTS index_wrong_question_userId ON wrong_question(userId)");
                database.execSQL("CREATE INDEX IF NOT EXISTS index_favorite_question_userId ON favorite_question(userId)");
            }
        },

        // v24 -> v25: 路径 B —— append-only 会话事件日志表（SessionEvent 实体）
        // 仅新增表与索引，不改动既有表，迁移零风险
        new Migration(24, 25) {
            @Override
            public void migrate(SupportSQLiteDatabase database) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `session_events` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`seq` INTEGER NOT NULL, " +
                    "`sessionId` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, " +
                    "`payload` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`durationMs` INTEGER NOT NULL)");
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_session_events_sessionId` " +
                    "ON `session_events` (`sessionId`)");
            }
        }
    };
}
