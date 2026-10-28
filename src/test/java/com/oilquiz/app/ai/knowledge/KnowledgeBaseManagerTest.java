package com.oilquiz.app.ai.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 知识库"修改已有条目"（2026-10-28）。
 *
 * <p>背景：从前只有 add/delete，用户想改一条已入库的知识只能先删再重加——id 变了、
 * created_at 丢了，文档导入的多块知识还会被拆散。这里钉住 update 的三条硬约束：
 * <ol>
 *   <li>id 不变、created_at 不动，只刷新传入的字段 + search_text + updated_at；</li>
 *   <li>没传（或传空串）的字段保持原值；</li>
 *   <li>按标题定位时，同名多条必须拒绝（返回 -2），不能让模型"改一堆"。</li>
 * </ol>
 *
 * <p>检索断言一律用正文里的 ASCII 唯一串：中文侧是"按字切 token + AND"的粗召回，
 * 用中文句子判断"旧内容不再命中"会被相邻字误命中（关键词里也有旧字）。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class KnowledgeBaseManagerTest {

    private KnowledgeBaseManager manager;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        // Robolectric 每次测试都会重置 SQLite 环境：单例里缓存的连接会失效，
        // 必须丢弃单例重建，否则第 2 个用例起报 "Illegal connection pointer"
        KnowledgeBaseManager.resetForTesting();
        manager = KnowledgeBaseManager.getInstance(context);
        manager.clear();   // 单测之间不能互相看见残留知识
    }

    private long add(String title, String category, String keywords, String content, String source) {
        long id = manager.addChunk(title, category, keywords, content, source);
        assertTrue("添加应成功: " + manager.getLastError(), id > 0);
        return id;
    }

    private int hits(String query) {
        JSONArray arr = manager.search(query, null, 5, false);
        assertNotNull(arr);
        return arr.length();
    }

    private JSONObject hit(String query) {
        JSONArray arr = manager.search(query, null, 5, false);
        assertNotNull(arr);
        assertEquals("检索命中条数: " + arr, 1, arr.length());
        return arr.optJSONObject(0);
    }

    @Test
    public void updateKeepsIdAndOnlyChangesGivenFields() {
        long id = add("旧标题", "general", "旧关键词", "旧正文 oldtoken123", "old.txt");

        assertTrue(manager.updateChunk(id, "新标题", null, null, "新正文 newtoken456", null));

        JSONObject row = hit("newtoken456");
        assertEquals("id 必须保持不变", id, row.optLong("id"));
        assertEquals("新标题", row.optString("title"));
        assertEquals("没传的字段保持原值", "旧关键词", row.optString("keywords"));
        assertEquals("没传的字段保持原值", "old.txt", row.optString("source"));
        assertTrue("正文应已更新", row.optString("content").contains("newtoken456"));

        // search_text 必须跟着更新：旧正文里的唯一串不该再被召回
        assertEquals("更新后旧内容不应再命中", 0, hits("oldtoken123"));
    }

    @Test
    public void blankFieldsKeepOldValues() {
        long id = add("标题", "guide", "k1", "正文 oldtok", "src");

        // 模型经常把不动的字段填成空串：语义必须是"不改这项"，而不是清空
        assertTrue(manager.updateChunk(id, "", "", "", "新正文 newtok", ""));

        JSONObject row = hit("newtok");
        assertEquals("标题不能被空串清掉", "标题", row.optString("title"));
        assertEquals("分类不能被空串清掉", "guide", row.optString("category"));
        assertEquals("关键词不能被空串清掉", "k1", row.optString("keywords"));
        assertEquals("来源不能被空串清掉", "src", row.optString("source"));
    }

    @Test
    public void updateUnknownIdFailsWithReason() {
        assertFalse(manager.updateChunk(999999L, "x", null, null, "y", null));
        String err = manager.getLastError();
        assertNotNull("失败必须给出原因", err);
        assertTrue("原因应说明没找到: " + err, err.contains("未找到"));
    }

    @Test
    public void findUniqueIdByTitleDistinguishesUniqueAmbiguousMissing() {
        long id = add("唯一标题", "general", "", "正文", "");

        assertEquals("标题唯一时应返回该 id", id, manager.findUniqueIdByTitle("唯一标题"));
        assertEquals("没有该标题返回 -1", -1L, manager.findUniqueIdByTitle("不存在的标题"));

        // 文档导入会按标题切块：同名多条 → 有歧义，必须返回 -2 并要求改用 id
        add("重复标题", "general", "", "第一块", "doc.pdf");
        add("重复标题", "general", "", "第二块", "doc.pdf");
        assertEquals("同名多条返回 -2（歧义）", -2L, manager.findUniqueIdByTitle("重复标题"));
        String err = manager.getLastError();
        assertNotNull(err);
        assertTrue("歧义提示应引导改用 id: " + err, err.contains("id"));
    }
}
