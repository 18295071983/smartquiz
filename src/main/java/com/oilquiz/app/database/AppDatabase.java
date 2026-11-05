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
import com.oilquiz.app.util.AILogger;

import android.content.Context;
import android.util.Log;

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
    private static final String TAG = "AppDatabase";

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
                        // 已经 !isOpen() 的实例关不上也不能挡住重建，但要说出来：
                        // 底层连接可能还占着文件，紧接着的新实例有概率撞上"数据库被占用"。
                        AILogger.w(TAG, "丢弃未打开的旧实例时 close 失败: " + e.getMessage());
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
                Log.i(TAG, "数据库实例已建立（version=" + DATABASE_VERSION + "）");
            } catch (Exception e) {
                // 建库（含迁移）失败要让 ai_logs 里有这条：调用方拿到 null 之后各有各的兜底，
                // 现场只剩"某些功能没数据"，不记这里就没人知道是数据库没起来。
                AILogger.e(TAG, "数据库初始化失败，getDatabase 返回 null: " + e.getMessage(), e);
                // 清理失败的实例
                if (INSTANCE != null) {
                    try {
                        INSTANCE.close();
                    } catch (Exception closeEx) {
                        // 半死的实例关不上也不能挡住把 INSTANCE 置空——留着它，
                        // 下一次调用会在 isOpen() 上继续误判。吞掉但要说出来。
                        AILogger.w(TAG, "失败实例关闭没成功，已直接丢弃: " + closeEx.getMessage());
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
                    // 不吞：引用马上要置空，这条连接就再没人能关了。真发生的话后面
                    // 重开可能报 "database is locked"，得能从日志对上这次 close 失败。
                    AILogger.w(TAG, "destroyInstance 时 close 失败，连接可能仍被占用: " + e.getMessage());
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
        //
        // 原来是两条包着空 catch 的 ALTER，表名写的是 questions（复数），而 @Entity 的表名
        // 从 v1 起就是 question（单数，见 Question.java:13）。也就是说这 8 个版本里它们每次
        // 都在 "no such table" 上空转，被 catch 吞成静默 no-op——整条迁移实际什么都没做。
        //
        // 这里刻意不"顺手改对"：ai_enhanced / ai_tags 全仓库没有任何 Entity 字段或读写方
        // （只有本文件与 DatabaseUpgradeManager 的 DDL 提到过），真把列补上，Room 按 Entity
        // 算出的 schema 就和库里的对不上，反而把本来能升上去的旧库顶成"数据库不可用"。
        // 要做 AI 字段得先加 Entity、再走新版本迁移，不是在旧迁移里补 DDL。
        new Migration(17, 18) {
            @Override
            public void migrate(SupportSQLiteDatabase database) {
                // 空迁移：见上。这是"它一直是 no-op"的事实声明，不是漏实现。
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
                //    FK 原来指向 questions（复数）——实体表名是 question，Room 开着 foreign_keys，
                //    写错的 FK 让这张表一插入就报 "no such table: main.questions"。
                database.execSQL("CREATE TABLE IF NOT EXISTS question_images (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "question_id INTEGER, " +
                    "image_path TEXT, " +
                    "image_type TEXT, " +
                    "created_at INTEGER DEFAULT (strftime('%s', 'now')), " +
                    "FOREIGN KEY (question_id) REFERENCES question(id) ON DELETE CASCADE" +
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
                
                // 4. 索引：这里原来还有两条 CREATE INDEX ... ON questions(...)，已删。
                //    表名 questions 不存在（见上一条迁移的说明），ai_enhanced / created_at 两列
                //    也都不在 question 表上（实体里是 createdAt），而这两条<b>没包 catch</b>——
                //    所以从 v18 升上来的人是在这里直接抛 SQLException、整条 18->19 失败的。
                //    等价索引由下一条迁移在正确的表列上创建（idx_questions_created ON question(createdAt)），
                //    ai_enhanced 那条没有可指的对象，删掉不补。
            }
        },
        
        // v19 -> v20: 全面升级题目表字段
        new Migration(19, 20) {
            @Override
            public void migrate(SupportSQLiteDatabase database) {
                // 补 v20 新增列。原来 19 条 ALTER 各自包一层空 catch，现在由
                // MigrationSchema 先查 PRAGMA table_info 再决定是否执行；列名与定义逐字未动。
                MigrationSchema.addColumns(database, "question",
                        // 创建和更新时间
                        "createdAt", "INTEGER DEFAULT 0",
                        "updatedAt", "INTEGER DEFAULT 0",
                        // 题目来源 / 标签
                        "source", "TEXT",
                        "tags", "TEXT",
                        // 分值与限时
                        "points", "INTEGER DEFAULT 1",
                        "timeLimit", "INTEGER DEFAULT 0",
                        // 解析与知识点
                        "hint", "TEXT",
                        "analysis", "TEXT",
                        "knowledgePoint", "TEXT",
                        "subCategory", "TEXT",
                        // 使用统计
                        "usageCount", "INTEGER DEFAULT 0",
                        "correctCount", "INTEGER DEFAULT 0",
                        "incorrectCount", "INTEGER DEFAULT 0",
                        "lastUsedAt", "INTEGER DEFAULT 0",
                        // 状态 / 公开 / 作者 / 备注
                        "status", "INTEGER DEFAULT 0",
                        "isPublic", "INTEGER DEFAULT 0",
                        "author", "TEXT",
                        "comment", "TEXT",
                        // 额外选项（JSON，v22 起被 optionE~L 独立列取代）
                        "extraOptions", "TEXT");
                
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
                MigrationSchema.addColumns(database, "question",
                        // 标准答案文本（填空题/简答题）
                        "answerText", "TEXT",
                        // 题目配图路径
                        "imageUri", "TEXT",
                        // 听力题音频路径
                        "audioUri", "TEXT",
                        // 母题ID（子题关联）
                        "parentId", "INTEGER DEFAULT 0",
                        // 排序权重
                        "sortOrder", "INTEGER DEFAULT 0");
                // 索引
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_parent ON question(parentId)");
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_sort ON question(sortOrder)");
            }
        },
        
        // v21 -> v22: 选项E~L独立列（替代extraOptions JSON）
        new Migration(21, 22) {
            @Override
            public void migrate(SupportSQLiteDatabase database) {
                String[] optionCols = {"optionE", "optionF", "optionG", "optionH",
                                       "optionI", "optionJ", "optionK", "optionL"};
                for (String col : optionCols) {
                    MigrationSchema.addColumn(database, "question", col, "TEXT");
                }

                // 把老 extraOptions JSON 摊平到新列。整段原来包在空 catch 里，等于把
                // "这库压根没有 extraOptions 列"（走过 DatabaseUpgradeManager 那条旧升级路的库）
                // 和"读着读着出错了"当成同一件事吞掉。改成显式判列：缺列就是没数据可迁，
                // 记一条 W 跳过；列在，后面的异常一律原样抛。
                if (!MigrationSchema.hasColumn(database, "question", "extraOptions")) {
                    AILogger.w(TAG, "v21->v22: question 表没有 extraOptions 列，跳过 E~L 选项摊平");
                    return;
                }
                java.util.Set<String> columns = MigrationSchema.columns(database, "question");
                int unparsedRows = 0;
                int unknownKeys = 0;
                android.database.Cursor cursor = database.query(
                    "SELECT id, extraOptions FROM question WHERE extraOptions IS NOT NULL AND extraOptions != ''");
                try {
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(0);
                        String json = cursor.getString(1);
                        if (json == null || json.isEmpty()) {
                            continue;
                        }
                        org.json.JSONObject jo;
                        try {
                            jo = new org.json.JSONObject(json);
                        } catch (org.json.JSONException bad) {
                            // 存量数据里确实有非 JSON 的 extraOptions（老导入器写坏的）。
                            // 这一行的 E~L 会丢，但迁移不能因为它整库失败——计数后继续，
                            // 结束时报一条带样例 id 的 W，别让人以为数据是全的。
                            unparsedRows++;
                            if (unparsedRows <= 3) {
                                AILogger.w(TAG, "v21->v22: 题目 " + id + " 的 extraOptions 不是 JSON，"
                                        + "该行 E~L 选项未迁移: " + bad.getMessage());
                            }
                            continue;
                        }
                        java.util.Iterator<String> keys = jo.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            String val = jo.optString(key, "");
                            if (val.isEmpty()) {
                                continue;
                            }
                            // key 是直接拼进 SQL 的（UPDATE question SET <key> = ?），
                            // 所以只认表里真存在的列名；对不上的当作脏 key 记下来。
                            if (!columns.contains(key)) {
                                unknownKeys++;
                                if (unknownKeys <= 3) {
                                    AILogger.w(TAG, "v21->v22: 题目 " + id + " 的 extraOptions 里有个 key "
                                            + "不是 question 的列，已跳过: " + key);
                                }
                                continue;
                            }
                            database.execSQL(
                                "UPDATE question SET " + key + " = ? WHERE id = ?",
                                new Object[]{val, id}
                            );
                        }
                    }
                } finally {
                    cursor.close();
                }
                if (unparsedRows > 0 || unknownKeys > 0) {
                    AILogger.w(TAG, "v21->v22: extraOptions 摊平有损失——"
                            + unparsedRows + " 行 JSON 解析失败（E~L 已丢），"
                            + unknownKeys + " 个 key 不是 question 的列（已跳过）");
                }
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
