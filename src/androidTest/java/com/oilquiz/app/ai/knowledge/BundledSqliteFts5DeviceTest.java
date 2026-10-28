package com.oilquiz.app.ai.knowledge;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.sqlite.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 真机探针：验证"自带 SQLite"（SQLite 官方 Android Bindings 的 Maven 再发布
 * `mil.nga:sqlite-android`）在**这台设备**上是否真的提供 FTS5 + bm25 + trigram。
 *
 * <p>背景：系统自带 SQLite 在这台机器上没编 FTS5（v1 报 no such module: fts5），
 * FTS4 也没开 `SQLITE_ENABLE_FTS3_PARENTHESIS`（v2 布尔查询恒 0 命中）——所以才退回
 * 全表 LIKE。要换成真正的倒排索引，必须先证明"自带的那份"确实带这些能力。</p>
 *
 * <p>这是 instrumented test（跑在设备上），不是 Robolectric——bundled SQLite 只有
 * Android native 库，JVM 侧装不起来。</p>
 */
@RunWith(AndroidJUnit4.class)
public class BundledSqliteFts5DeviceTest {

    private static final String TAG = "KbFts5Probe";

    private SQLiteDatabase open(Context ctx, String name) {
        // 官方 bindings 的 native 库不会自动加载：显式 System.loadLibrary("sqliteX")
        // （aar 里是 jni/arm64-v8a/libsqliteX.so；老的 loadLibs(Context) 这版已移除）
        System.loadLibrary("sqliteX");
        File dir = ctx.getDatabasePath("probe").getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        File f = new File(dir, name);
        if (f.exists()) f.delete();
        return SQLiteDatabase.openOrCreateDatabase(f, null);
    }

    /** 打印并断言编译开关里确实带 FTS5。 */
    @Test
    public void compileOptionsIncludeFts5() {
        Context ctx = ApplicationProvider.getApplicationContext();
        SQLiteDatabase db = open(ctx, "probe_fts5.db");
        try {
            List<String> opts = new ArrayList<>();
            Cursor c = db.rawQuery("PRAGMA compile_options", null);
            while (c.moveToNext()) {
                String v = c.getString(0);
                opts.add(v);
                Log.i(TAG, "compile_option: " + v);
            }
            c.close();
            String joined = String.join(",", opts).toUpperCase(Locale.ROOT);
            Log.i(TAG, "sqlite_version=" + sqliteVersion(db));
            assertTrue("自带 SQLite 必须编进 FTS5；实际开关=" + joined, joined.contains("ENABLE_FTS5"));
        } finally {
            db.close();
        }
    }

    private String sqliteVersion(SQLiteDatabase db) {
        Cursor c = db.rawQuery("select sqlite_version()", null);
        String v = c.moveToFirst() ? c.getString(0) : "?";
        c.close();
        return v;
    }

    /** FTS5 + 预切分 bigram 词流（unicode61）：查 2 字词也能命中，并能用 bm25 排序。 */
    @Test
    public void fts5MatchesBigramTokensAndRanksWithBm25() {
        Context ctx = ApplicationProvider.getApplicationContext();
        SQLiteDatabase db = open(ctx, "probe_fts5_match.db");
        try {
            db.execSQL("CREATE VIRTUAL TABLE kb_fts USING fts5("
                    + "tok, title UNINDEXED, content UNINDEXED, tokenize='unicode61')");
            // 索引列存"空格分隔的 bigram 词流"（与生产侧 buildSearchText 同口径）
            insert(db, "光合 合作 作用 植物 物把 把光 光能 能转 转成 成化 化学 学能", "光合作用", "植物把光能转成化学能");
            insert(db, "阳光 光合 合并 作业 用途", "无关条目", "阳光、合并、作业、用途各不相同");

            // 与入库侧同口径：查询也切 bigram，空格分隔 = 隐式 AND
            Cursor c = db.rawQuery(
                    "SELECT title, bm25(kb_fts) AS score FROM kb_fts WHERE kb_fts MATCH ? ORDER BY score LIMIT 5",
                    new String[]{"光合 合作 作用"});
            List<String> hits = new ArrayList<>();
            while (c.moveToNext()) {
                hits.add(c.getString(0) + "(" + c.getDouble(1) + ")");
            }
            c.close();
            Log.i(TAG, "bigram MATCH hits=" + hits);
            assertTrue("应只命中真正含「光合作用」的条目，实际=" + hits,
                    hits.size() == 1 && hits.get(0).startsWith("光合作用"));
        } finally {
            db.close();
        }
    }

    /** FTS5 trigram：中文子串直接 MATCH（≥3 字），不需要预切分。 */
    @Test
    public void fts5TrigramSupportsRawCjkSubstring() {
        Context ctx = ApplicationProvider.getApplicationContext();
        SQLiteDatabase db = open(ctx, "probe_fts5_trigram.db");
        try {
            db.execSQL("CREATE VIRTUAL TABLE t USING fts5(x, tokenize='trigram')");
            db.execSQL("INSERT INTO t(x) VALUES ('植物通过光合作用把光能转成化学能')");
            Cursor c = db.rawQuery("SELECT count(*) FROM t WHERE t MATCH ?", new String[]{"光合作用"});
            int n = c.moveToFirst() ? c.getInt(0) : -1;
            c.close();
            Log.i(TAG, "trigram 3+ chars hits=" + n);
            assertTrue("trigram 应支持 3 字以上中文子串匹配", n == 1);

            // 2 字查询：trigram 的已知边界（索引以 3 字符为单位），记录下来供方案取舍
            Cursor c2 = db.rawQuery("SELECT count(*) FROM t WHERE t MATCH ?", new String[]{"光合"});
            int n2 = c2.moveToFirst() ? c2.getInt(0) : -1;
            c2.close();
            Log.i(TAG, "trigram 2 chars hits=" + n2 + "（已知边界：可能为 0）");
        } finally {
            db.close();
        }
    }

    private void insert(SQLiteDatabase db, String tok, String title, String content) {
        db.execSQL("INSERT INTO kb_fts(tok, title, content) VALUES(?,?,?)",
                new Object[]{tok, title, content});
    }
}
