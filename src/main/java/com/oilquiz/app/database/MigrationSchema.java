package com.oilquiz.app.database;

import android.database.Cursor;

import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * 迁移期用的 schema 探针：表/列存在性 + "缺了才补" 的 ALTER。
 *
 * <p>为什么要单独一个类：AppDatabase 的每条 ALTER 历史上都各自包一层空 catch，注释写
 * "字段可能已存在，忽略错误"。那是把异常当控制流——它确实挡住了"重复加列"这一种<b>预期内</b>
 * 的失败，同时把<b>真故障</b>（表名写错、SQL 拼错、磁盘写不进、库被别的连接占着）一起吞掉。
 * 结果列没加上却继续往下走，最后由 Room 迁移后的 schema 校验抛
 * "Migration ... probably didn't work out"：结局同样是升级失败，只是现场全丢了——
 * 排查时只看得到"数据库起不来"，看不到少的是哪一列。</p>
 *
 * <p>所以这里的口径是<b>先查后改，异常原样抛</b>：用 {@code PRAGMA table_info} 判存在，
 * 不存在才执行 ALTER；ALTER 失败不再被任何人接住。传给 {@code definition} 的 SQL 文案
 * 与迁移里原来写的逐字一致，改的只是"要不要执行"的判据。</p>
 *
 * <p>不校验"表在不在"就补列：表缺失时 {@code table_info} 返回空集，走 ALTER 抛
 * "no such table"，正是要它响——静默跳过的话，Room 那边会报成更难读的哈希不符。</p>
 */
public final class MigrationSchema {

    private static final String TAG = "MigrationSchema";

    private MigrationSchema() {
    }

    /**
     * 列是否存在。列名按大小写不敏感比较：SQLite 的列名本身不区分大小写，
     * 迁移里用的是 camelCase（createdAt），而旧库存下来时可能是别的写法。
     */
    public static boolean hasColumn(SupportSQLiteDatabase database, String table, String column) {
        return columns(database, table).contains(column);
    }

    /**
     * 表的全部列名，装成大小写不敏感的 Set。
     *
     * <p>给"按存量 JSON 的 key 反写列"这类迁移用：key 是拼进 SQL 的，必须先过一道
     * "这列真存在吗"，否则一份脏 extraOptions 就能改写迁移语句本身。逐 key 查
     * PRAGMA 太碎，一次取全表列名再比。</p>
     */
    public static java.util.Set<String> columns(SupportSQLiteDatabase database, String table) {
        java.util.Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Cursor cursor = database.query("PRAGMA table_info(" + quoteIdentifier(table) + ")");
        try {
            int nameColumn = cursor.getColumnIndex("name");
            if (nameColumn < 0) {
                return names;
            }
            while (cursor.moveToNext()) {
                String existing = cursor.getString(nameColumn);
                if (existing != null) {
                    names.add(existing);
                }
            }
            return names;
        } finally {
            cursor.close();
        }
    }

    /**
     * 缺则补列，已存在直接返回。
     *
     * <p>ALTER 的异常<b>不接</b>：能让迁移失败的事就该让迁移失败，别留一库缺列的数据给人踩。
     * 回滚由 Room 的迁移事务负责，不需要这里兜。</p>
     *
     * @param definition 列类型与约束，例如 {@code "INTEGER NOT NULL DEFAULT 0"} 或 {@code "TEXT"}
     */
    public static void addColumn(SupportSQLiteDatabase database, String table, String column, String definition) {
        if (hasColumn(database, table, column)) {
            return;
        }
        database.execSQL("ALTER TABLE " + quoteIdentifier(table)
                + " ADD COLUMN " + quoteIdentifier(column) + " " + definition);
    }

    /**
     * 批量补列，参数按 {@code 列名, 定义, 列名, 定义...} 成对给出。
     *
     * <p>成对而不是拼成一条完整 DDL：列名走 {@link #quoteIdentifier} 转义，定义原样透传
     * （DEFAULT 子句没法参数化）。迁移里二十几条 ALTER 挤成一段清单，少一层抄漏的风险。</p>
     */
    public static void addColumns(SupportSQLiteDatabase database, String table, String... columnAndDefinition) {
        if (columnAndDefinition.length % 2 != 0) {
            throw new IllegalArgumentException(TAG + ": 列名与定义要成对给出，收到 "
                    + columnAndDefinition.length + " 项");
        }
        for (int i = 0; i < columnAndDefinition.length; i += 2) {
            addColumn(database, table, columnAndDefinition[i], columnAndDefinition[i + 1]);
        }
    }

    /** 标识符加反引号，内嵌的反引号按 SQLite 规则双写 */
    private static String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
