package com.oilquiz.app.ai.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;

/**
 * 知识库 v3 → v4 数据迁移（2026-10-28，U1 bigram）。
 *
 * <p>v3 的 {@code search_text} 存的是"原文小写"，v4 换成"空格分隔的 bigram token 流"。
 * 老用户盘上已经有 63 条工具定义（category=tool_defs），必须靠 onUpgrade **原地重算**，
 * 不能要求清库重导。这条用例模拟一个 v3 库，验证：开库即迁移、search_text 真的变成
 * bigram 流、按 bigram 能查到、user_version 抬到 4。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class KnowledgeBaseMigrationTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        KnowledgeBaseManager.resetForTesting();
        File dbFile = context.getDatabasePath("knowledge_base.db");
        new File(dbFile.getPath() + "-journal").delete();
        dbFile.delete();
        dbFile.getParentFile().mkdirs();
    }

    @Test
    public void v3DatabaseIsRebuiltToBigramTokensOnOpen() {
        File dbFile = context.getDatabasePath("knowledge_base.db");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        db.execSQL("CREATE TABLE kb_chunks ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL,"
                + "category TEXT NOT NULL DEFAULT 'general',"
                + "keywords TEXT NOT NULL DEFAULT '',"
                + "content TEXT NOT NULL,"
                + "source TEXT NOT NULL DEFAULT '',"
                + "search_text TEXT NOT NULL DEFAULT '',"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
        // v3 口径：search_text 是"原文小写"，没有分词、没有 bigram
        db.execSQL("INSERT INTO kb_chunks (title,category,keywords,content,search_text,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,?,?)",
                new Object[]{"光合作用", "guide", "photosynthesis",
                        "植物把光能转成化学能", "光合作用 guide photosynthesis 植物把光能转成化学能", 1L, 1L});
        db.setVersion(3);
        db.close();

        // 打开即迁移（getReadableDatabase → onUpgrade v3→v4 → ensureSearchTextV4）
        KnowledgeBaseManager manager = KnowledgeBaseManager.getInstance(context);
        JSONArray hits = manager.search("光合作用", null, 5, false);
        // 关掉连接再看盘上内容（排除 Robolectric 连接未 flush 的干扰）
        KnowledgeBaseManager.resetForTesting();

        SQLiteDatabase debug = SQLiteDatabase.openDatabase(
                dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        String debugText = "";
        int debugVersion = -1;
        try {
            debugVersion = debug.getVersion();
            Cursor c = debug.rawQuery("SELECT search_text FROM kb_chunks LIMIT 1", null);
            if (c.moveToFirst()) debugText = c.getString(0);
            c.close();
        } finally {
            debug.close();
        }
        assertEquals("迁移后按 bigram 应能查到: hits=" + hits + " version=" + debugVersion
                + " search_text=" + debugText + " lastError=" + manager.getLastError(), 1, hits.length());

        SQLiteDatabase reopened = SQLiteDatabase.openDatabase(
                dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            assertEquals("user_version 应抬到 4", 4, reopened.getVersion());
            Cursor c = reopened.rawQuery("SELECT search_text FROM kb_chunks LIMIT 1", null);
            assertTrue(c.moveToFirst());
            String st = c.getString(0);
            c.close();
            assertTrue("search_text 应是 bigram token: " + st, st.contains("光合"));
            assertTrue("相邻两字都要有: " + st, st.contains("合作") && st.contains("作用"));
            assertTrue("token 之间应以空格分隔: " + st, st.contains(" "));
            assertTrue("英文词仍按词保留: " + st, st.contains("photosynthesis"));
            assertTrue("不该再是原文整段: " + st, !st.contains("植物把光能转成化学能"));
        } finally {
            reopened.close();
        }
    }
}
