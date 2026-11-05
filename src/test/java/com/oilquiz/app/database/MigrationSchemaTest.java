package com.oilquiz.app.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link MigrationSchema} 对真 SQLite 的行为钉版。
 *
 * <p>这个类的所有风险都在 SQL 文案上：{@code PRAGMA table_info(`x`)} 写错了不会编译报错，
 * 只会让每个迁移都"以为列已存在"或"以为列不存在"，最后炸在用户升级的那一刻——而升级路径
 * 在日常开发里根本走不到（手上的库已经是最新版）。所以拿一个真的 SQLite 库来跑，
 * 验的是<b>语句本身能不能被 SQLite 接受、结果对不对</b>，不是 mock 答应了我什么。</p>
 *
 * <p>不碰 AppDatabase 与 Room 单例：这里只要一个裸的 androidx.sqlite 帮助类打开的库。
 * 真迁移链路（v17→v25 一路升上来）只能靠旧库，本机没有，留作真机/仪表验证项，不在这个类里假装跑过。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class MigrationSchemaTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /** 每个用例一个独立库文件，避免上一个用例补的列影响下一个。 */
    private SupportSQLiteDatabase open(String... createStatements) {
        final String[] ddl = createStatements;
        SupportSQLiteOpenHelper.Callback callback = new SupportSQLiteOpenHelper.Callback(1) {
            @Override
            public void onCreate(SupportSQLiteDatabase db) {
                for (String sql : ddl) {
                    db.execSQL(sql);
                }
            }

            @Override
            public void onUpgrade(SupportSQLiteDatabase db, int oldVersion, int newVersion) {
                // 版本固定为 1，不走升级
            }
        };
        String dbName = "migration_schema_test_" + DB_SEQ.incrementAndGet() + ".db";
        SupportSQLiteOpenHelper helper = new FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(ctx())
                        .name(dbName)
                        .callback(callback)
                        .build());
        return helper.getWritableDatabase();
    }

    private SupportSQLiteDatabase questionTable() {
        return open("CREATE TABLE question ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "createdAt INTEGER DEFAULT 0, "
                + "analysis TEXT)");
    }

    @Test
    public void hasColumnFindsExistingAndMissesAbsent() {
        SupportSQLiteDatabase db = questionTable();

        assertTrue(MigrationSchema.hasColumn(db, "question", "createdAt"));
        assertTrue(MigrationSchema.hasColumn(db, "question", "analysis"));
        assertFalse(MigrationSchema.hasColumn(db, "question", "points"));
    }

    /**
     * 列名比较必须大小写不敏感：迁移里写的是 camelCase，而旧库存下来的是什么写法没人保证
     * （同一列在 SQLite 里按名字不区分大小写处理）。判据要是区分大小写的，
     * "已存在"就会被误判成"缺失"，接着 ALTER 抛 duplicate column，整条迁移失败。
     */
    @Test
    public void hasColumnIgnoresCase() {
        SupportSQLiteDatabase db = questionTable();

        assertTrue(MigrationSchema.hasColumn(db, "question", "CREATEDAT"));
        assertTrue(MigrationSchema.hasColumn(db, "question", "createdat"));
    }

    /** 表不存在时列清单为空，而不是抛"no such table"——探针对"查不到"和"表没了"给出同一个答案。 */
    @Test
    public void hasColumnOnMissingTableIsFalse() {
        SupportSQLiteDatabase db = questionTable();

        assertFalse(MigrationSchema.hasColumn(db, "no_such_table", "anything"));
    }

    @Test
    public void columnsListsEveryColumn() {
        Set<String> columns = MigrationSchema.columns(questionTable(), "question");

        assertEquals(3, columns.size());
        assertTrue(columns.contains("id"));
        assertTrue(columns.contains("createdAt"));
        // 白名单比较同样不区分大小写：这个 Set 是给"按存量 JSON 的 key 反写列"用的
        assertTrue(columns.contains("CREATEDAT"));
    }

    /** 缺则补，补完真的能写能读——证明 ALTER 的 SQL 被 SQLite 接受了。 */
    @Test
    public void addColumnCreatesTheColumn() {
        SupportSQLiteDatabase db = questionTable();
        MigrationSchema.addColumn(db, "question", "points", "INTEGER NOT NULL DEFAULT 0");

        assertTrue(MigrationSchema.hasColumn(db, "question", "points"));
        db.execSQL("INSERT INTO question (points) VALUES (7)");
        android.database.Cursor cursor = db.query("SELECT points FROM question");
        try {
            assertTrue(cursor.moveToFirst());
            assertEquals(7, cursor.getInt(0));
        } finally {
            cursor.close();
        }
    }

    /**
     * 已存在时必须是<b>什么都没执行</b>：再发一次 ALTER 就是 duplicate column 异常。
     * 这正是本类取代"每条 ALTER 包一层空 catch"的那半步——用查询判，而不是用异常判。
     */
    @Test
    public void addColumnIsNoOpWhenColumnExists() {
        SupportSQLiteDatabase db = questionTable();

        MigrationSchema.addColumn(db, "question", "analysis", "TEXT");
        // 定义故意写成和存量冲突的类型+非空无默认值：若真执行了必然抛异常
        MigrationSchema.addColumn(db, "question", "analysis", "INTEGER NOT NULL");

        assertTrue(MigrationSchema.hasColumn(db, "question", "analysis"));
    }

    @Test
    public void addColumnsHandlesPairs() {
        SupportSQLiteDatabase db = questionTable();

        MigrationSchema.addColumns(db, "question",
                "optionE", "TEXT",
                "parentId", "INTEGER DEFAULT 0",
                "sortOrder", "INTEGER DEFAULT 0");

        Set<String> columns = MigrationSchema.columns(db, "question");
        assertTrue(columns.contains("optionE"));
        assertTrue(columns.contains("parentId"));
        assertTrue(columns.contains("sortOrder"));
    }

    /** 参数漏了一个定义属于调用方写错，必须当场说清楚，而不是拼出一条半截 DDL 让 SQLite 猜。 */
    @Test
    public void addColumnsRejectsOddArgumentCount() {
        SupportSQLiteDatabase db = questionTable();

        try {
            MigrationSchema.addColumns(db, "question", "points", "INTEGER", "author");
            fail("列名与定义不成对时应该抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("3"));
        }
        assertFalse(MigrationSchema.hasColumn(db, "question", "points"));
    }

    /**
     * 真故障要响：表不存在时 addColumn 不许静默跳过，异常得原样抛出去。
     *
     * <p>这是整个改造的判据所在。旧写法在这里会吞掉异常继续跑，最后由 Room 报一句
     * "Migration didn't work out"，谁都不知道少的是哪张表。</p>
     */
    @Test
    public void addColumnPropagatesRealSqlFailure() {
        SupportSQLiteDatabase db = questionTable();

        try {
            MigrationSchema.addColumn(db, "no_such_table", "points", "INTEGER");
            fail("表不存在时必须抛出 SQL 失败，不能静默返回");
        } catch (android.database.SQLException expected) {
            // 只验"抛了"，不验消息：Robolectric 内置引擎给的是
            // "Cannot prepare statement, base error code: 1"，真机上才是 "no such table: …"。
            // 消息文案不是本类的行为契约，钉它等于把测试钉在模拟器实现上。
            assertTrue(expected instanceof android.database.sqlite.SQLiteException);
        }
    }

    /** 标识符里的反引号按 SQLite 规则双写，不让列名把 DDL 结构带跑。 */
    @Test
    public void addColumnQuotesAwkwardColumnNames() {
        SupportSQLiteDatabase db = open("CREATE TABLE t (id INTEGER PRIMARY KEY)");

        MigrationSchema.addColumn(db, "t", "order", "INTEGER DEFAULT 0");

        assertTrue(MigrationSchema.hasColumn(db, "t", "order"));
        db.execSQL("INSERT INTO t (\"order\") VALUES (1)");
    }
}
