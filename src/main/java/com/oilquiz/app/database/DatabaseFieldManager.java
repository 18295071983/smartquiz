package com.oilquiz.app.database;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 数据库字段管理器
 * 提供字段的查看、添加等操作
 * 注意：删除和修改字段功能已禁用，以保护系统安全
 */
public class DatabaseFieldManager {
    private static final String TAG = "DatabaseFieldManager";
    private static DatabaseFieldManager instance;
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private DatabaseFieldManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized DatabaseFieldManager getInstance(Context context) {
        if (instance == null) {
            instance = new DatabaseFieldManager(context);
        }
        return instance;
    }

    /**
     * 受保护的字段列表，这些字段不允许被删除
     * 包括：主键、系统字段、公共字段等
     */
    private static final Set<String> PROTECTED_FIELDS = new HashSet<>(Arrays.asList(
        "id", "created_at", "updated_at", "deleted_at", "is_deleted",
        "user_id", "create_time", "update_time", "modify_time",
        "status", "type", "name", "title", "content", "description",
        "question_id", "option_id", "answer", "correct_answer",
        "difficulty", "category_id", "subject_id", "bank_id"
    ));

    /**
     * 字段信息类
     */
    public static class FieldInfo {
        public String tableName;
        public String fieldName;
        public String fieldType;
        public boolean isNullable;
        public String defaultValue;
        public boolean isPrimaryKey;

        public FieldInfo(String tableName, String fieldName, String fieldType,
                       boolean isNullable, String defaultValue, boolean isPrimaryKey) {
            this.tableName = tableName;
            this.fieldName = fieldName;
            this.fieldType = fieldType;
            this.isNullable = isNullable;
            this.defaultValue = defaultValue;
            this.isPrimaryKey = isPrimaryKey;
        }

        @Override
        public String toString() {
            return fieldName + " (" + fieldType + ")" +
                   (isNullable ? " NULL" : " NOT NULL") +
                   (defaultValue != null ? " DEFAULT " + defaultValue : "") +
                   (isPrimaryKey ? " PRIMARY KEY" : "");
        }
    }

    /**
     * 操作结果类
     */
    public static class OperationResult {
        public boolean success;
        public String message;
        public boolean canRetry;

        public OperationResult(boolean success, String message, boolean canRetry) {
            this.success = success;
            this.message = message;
            this.canRetry = canRetry;
        }

        public static OperationResult success(String message) {
            return new OperationResult(true, message, false);
        }

        public static OperationResult failure(String message, boolean canRetry) {
            return new OperationResult(false, message, canRetry);
        }
    }

    /**
     * 获取所有表名
     */
    public Future<List<String>> getAllTables() {
        return executor.submit(() -> getAllTablesSync());
    }

    public List<String> getAllTablesSync() {
        List<String> tables = new ArrayList<>();
        try {
            AppDatabase db = AppDatabase.getDatabase(context);
            if (db == null) {
                Log.e(TAG, "获取数据库实例失败");
                return tables;
            }
            SupportSQLiteDatabase sqlite = db.getOpenHelper().getReadableDatabase();

            Cursor cursor = sqlite.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'");
            while (cursor.moveToNext()) {
                tables.add(cursor.getString(0));
            }
            cursor.close();
        } catch (Exception e) {
            Log.e(TAG, "获取表列表失败", e);
        }
        return tables;
    }

    /**
     * 获取表的字段列表
     */
    public Future<List<FieldInfo>> getTableFields(String tableName) {
        return executor.submit(() -> getTableFieldsSync(tableName));
    }

    public List<FieldInfo> getTableFieldsSync(String tableName) {
        List<FieldInfo> fields = new ArrayList<>();
        try {
            AppDatabase db = AppDatabase.getDatabase(context);
            if (db == null) {
                Log.e(TAG, "获取数据库实例失败");
                return fields;
            }
            SupportSQLiteDatabase sqlite = db.getOpenHelper().getReadableDatabase();

            Cursor cursor = sqlite.query("PRAGMA table_info(" + tableName + ")");
            while (cursor.moveToNext()) {
                String fieldName = cursor.getString(cursor.getColumnIndex("name"));
                String fieldType = cursor.getString(cursor.getColumnIndex("type"));
                int notNull = cursor.getInt(cursor.getColumnIndex("notnull"));
                String defaultValue = cursor.getString(cursor.getColumnIndex("dflt_value"));
                int pk = cursor.getInt(cursor.getColumnIndex("pk"));

                FieldInfo field = new FieldInfo(
                        tableName,
                        fieldName,
                        fieldType,
                        notNull == 0,
                        defaultValue,
                        pk == 1
                );
                fields.add(field);
            }
            cursor.close();
        } catch (Exception e) {
            Log.e(TAG, "获取表字段失败", e);
        }
        return fields;
    }

    /**
     * 检查字段是否存在
     */
    public Future<Boolean> fieldExists(String tableName, String fieldName) {
        return executor.submit(() -> {
            try {
                AppDatabase db = AppDatabase.getDatabase(context);
                SupportSQLiteDatabase sqlite = db.getOpenHelper().getReadableDatabase();

                Cursor cursor = sqlite.query("PRAGMA table_info(" + tableName + ")");
                while (cursor.moveToNext()) {
                    if (fieldName.equals(cursor.getString(cursor.getColumnIndex("name")))) {
                        cursor.close();
                        return true;
                    }
                }
                cursor.close();
            } catch (Exception e) {
                Log.e(TAG, "检查字段存在失败", e);
            }
            return false;
        });
    }

    /**
     * 检查字段是否受保护（不允许删除）
     * @param fieldName 字段名
     * @return true if protected
     */
    public boolean isFieldProtected(String fieldName) {
        if (PROTECTED_FIELDS.contains(fieldName.toLowerCase())) {
            return true;
        }
        if (fieldName.toLowerCase().endsWith("_id")) {
            return true;
        }
        if (fieldName.toLowerCase().contains("_at") || fieldName.toLowerCase().contains("_time")) {
            return true;
        }
        return false;
    }

    /**
     * 获取字段保护状态描述
     * @param fieldName 字段名
     * @return 保护原因
     */
    public String getFieldProtectionReason(String fieldName) {
        String lower = fieldName.toLowerCase();
        if (PROTECTED_FIELDS.contains(lower)) {
            return "系统保护字段";
        }
        if (lower.endsWith("_id")) {
            return "关联ID字段，删除可能导致数据关联失效";
        }
        if (lower.contains("_at") || lower.contains("_time")) {
            return "时间戳字段，删除可能影响数据追踪";
        }
        return "未知原因";
    }

    /**
     * 添加字段
     */
    public Future<OperationResult> addField(String tableName, String fieldName, String fieldType,
                                           boolean nullable, String defaultValue) {
        return executor.submit(() -> {
            try {
                // 直接检查字段是否存在（不使用异步调用）
                if (checkFieldExistsSync(tableName, fieldName)) {
                    Log.w(TAG, "字段已存在: " + tableName + "." + fieldName);
                    return OperationResult.failure("字段已存在: " + tableName + "." + fieldName, false);
                }

                AppDatabase db = AppDatabase.getDatabase(context);
                if (db == null) {
                    Log.e(TAG, "获取数据库实例失败");
                    return OperationResult.failure("获取数据库实例失败，请检查数据库连接", false);
                }
                SupportSQLiteDatabase sqlite = db.getOpenHelper().getWritableDatabase();

                sqlite.beginTransaction();
                try {
                    StringBuilder sql = new StringBuilder("ALTER TABLE ");
                    sql.append(tableName);
                    sql.append(" ADD COLUMN ");
                    sql.append(fieldName);
                    sql.append(" ");
                    sql.append(fieldType);

                    if (!nullable) {
                        sql.append(" NOT NULL");
                    }

                    if (defaultValue != null) {
                        sql.append(" DEFAULT ");
                        if (fieldType.toLowerCase().contains("text") || fieldType.toLowerCase().contains("varchar")) {
                            sql.append("'").append(defaultValue).append("'");
                        } else {
                            sql.append(defaultValue);
                        }
                    }

                    Log.i(TAG, "执行SQL: " + sql.toString());
                    sqlite.execSQL(sql.toString());
                    sqlite.setTransactionSuccessful();
                    Log.i(TAG, "成功添加字段: " + tableName + "." + fieldName);
                    return OperationResult.success("成功添加字段: " + tableName + "." + fieldName);
                } finally {
                    sqlite.endTransaction();
                }
            } catch (Exception e) {
                Log.e(TAG, "添加字段失败", e);
                return OperationResult.failure("添加字段失败: " + e.getMessage(), true);
            }
        });
    }

    /**
     * 同步检查字段是否存在（供内部使用，避免嵌套异步调用）
     */
    private boolean checkFieldExistsSync(String tableName, String fieldName) {
        try {
            AppDatabase db = AppDatabase.getDatabase(context);
            if (db == null) {
                Log.e(TAG, "获取数据库实例失败");
                return false;
            }
            SupportSQLiteDatabase sqlite = db.getOpenHelper().getReadableDatabase();

            Cursor cursor = sqlite.query("PRAGMA table_info(" + tableName + ")");
            while (cursor.moveToNext()) {
                if (fieldName.equals(cursor.getString(cursor.getColumnIndex("name")))) {
                    cursor.close();
                    return true;
                }
            }
            cursor.close();
        } catch (Exception e) {
            Log.e(TAG, "检查字段存在失败", e);
        }
        return false;
    }

    /**
     * 删除字段（已禁用）
     * 由于SQLite限制和系统安全考虑，删除字段功能已被禁用
     * 删除字段可能导致应用崩溃或数据丢失
     * @deprecated 请通过数据库版本升级来管理字段变更
     */
    @Deprecated
    public Future<OperationResult> removeField(String tableName, String fieldName) {
        return executor.submit(() -> {
            Log.w(TAG, "删除字段功能已禁用: " + tableName + "." + fieldName);
            String reason = getFieldProtectionReason(fieldName);
            return OperationResult.failure(
                "删除字段功能已禁用以保护系统安全。\n\n" +
                "字段: " + fieldName + "\n" +
                "原因: " + reason + "\n\n" +
                "如需变更字段，请通过数据库版本升级来处理。",
                false
            );
        });
    }

    /**
     * 修改字段类型（已禁用）
     * 由于SQLite限制和系统安全考虑，修改字段类型功能已被禁用
     * 修改字段类型可能导致应用崩溃或数据丢失
     * @deprecated 请通过数据库版本升级来管理字段变更
     */
    @Deprecated
    public Future<OperationResult> modifyFieldType(String tableName, String fieldName, String newType) {
        return executor.submit(() -> {
            Log.w(TAG, "修改字段类型功能已禁用: " + tableName + "." + fieldName + " -> " + newType);
            return OperationResult.failure(
                "修改字段类型功能已禁用以保护系统安全。\n\n" +
                "字段: " + fieldName + "\n" +
                "新类型: " + newType + "\n\n" +
                "如需变更字段，请通过数据库版本升级来处理。",
                false
            );
        });
    }

    /**
     * 初始化基础数据
     */
    public Future<Boolean> initializeBasicData() {
        return executor.submit(() -> {
            try {
                AppDatabase db = AppDatabase.getDatabase(context);
                SupportSQLiteDatabase sqlite = db.getOpenHelper().getWritableDatabase();

                sqlite.beginTransaction();
                try {
                    if (!tableExists(sqlite, "question_types")) {
                        sqlite.execSQL("CREATE TABLE IF NOT EXISTS question_types (id INTEGER PRIMARY KEY AUTOINCREMENT, type_name TEXT UNIQUE, description TEXT, created_at INTEGER DEFAULT (strftime('%s', 'now')))");

                        String[] basicTypes = {"单选题", "多选题", "判断题", "简答题", "填空题"};
                        for (String type : basicTypes) {
                            sqlite.execSQL(
                                "INSERT OR IGNORE INTO question_types (type_name, description) VALUES (?, ?)",
                                new Object[]{type, type + "题型"}
                            );
                        }
                        Log.i(TAG, "已初始化题型基础数据");
                    }

                    if (!tableExists(sqlite, "difficulty_levels")) {
                        sqlite.execSQL("CREATE TABLE IF NOT EXISTS difficulty_levels (id INTEGER PRIMARY KEY AUTOINCREMENT, level INTEGER UNIQUE, name TEXT, description TEXT, created_at INTEGER DEFAULT (strftime('%s', 'now')))");

                        sqlite.execSQL("INSERT OR IGNORE INTO difficulty_levels (level, name, description) VALUES (?, ?, ?)",
                            new Object[]{1, "简单", "基础知识点"});
                        sqlite.execSQL("INSERT OR IGNORE INTO difficulty_levels (level, name, description) VALUES (?, ?, ?)",
                            new Object[]{2, "中等", "进阶知识点"});
                        sqlite.execSQL("INSERT OR IGNORE INTO difficulty_levels (level, name, description) VALUES (?, ?, ?)",
                            new Object[]{3, "困难", "高级知识点"});
                        Log.i(TAG, "已初始化难度级别基础数据");
                    }

                    sqlite.setTransactionSuccessful();
                    return true;
                } finally {
                    sqlite.endTransaction();
                }
            } catch (Exception e) {
                Log.e(TAG, "初始化基础数据失败", e);
                return false;
            }
        });
    }

    /**
     * 检查表是否存在
     */
    private boolean tableExists(SupportSQLiteDatabase db, String tableName) {
        try {
            Cursor cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                                   new String[]{tableName});
            boolean exists = cursor.moveToFirst();
            cursor.close();
            return exists;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 关闭执行器
     */
    public void shutdown() {
        executor.shutdown();
    }
}
