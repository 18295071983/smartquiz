package com.oilquiz.app.ai.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.tool.AIToolResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.Map;

/**
 * knowledge_base 工具层的 update 动作（2026-10-28）。
 *
 * <p>钉住三件事：① action=update 路由到改库并回 {@code success:true + id + updated 字段清单}；
 * ② 只给 find_title 时能唯一定位；③ 同名多条时拒绝并提示改用 id（而不是悄悄改一堆）。
 */
@RunWith(RobolectricTestRunner.class)
public class KnowledgeBaseToolUpdateTest {

    private KnowledgeBaseManager manager;
    private KnowledgeBaseTool tool;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        KnowledgeBaseManager.resetForTesting();
        manager = KnowledgeBaseManager.getInstance(context);
        manager.clear();
        tool = new KnowledgeBaseTool(context);
    }

    private AIToolResult update(Map<String, Object> params) {
        params.put("action", "update");
        return tool.execute(params);
    }

    private Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test
    public void updateByIdReturnsChangedFieldList() throws Exception {
        long id = manager.addChunk("旧标题", "general", "", "旧正文 oldtok", "");

        AIToolResult r = update(params("id", id, "title", "新标题", "content", "新正文 newtok"));

        assertTrue("工具应报成功: " + r.getErrorMessage(), r.isSuccess());
        JSONObject payload = new JSONObject(String.valueOf(r.getResult()));
        assertEquals(id, payload.optLong("id"));
        assertEquals(2, payload.optInt("updated_count"));
        assertEquals("title", payload.optJSONArray("updated").optString(0));
        assertEquals("content", payload.optJSONArray("updated").optString(1));
    }

    @Test
    public void updateByUniqueFindTitleWorks() {
        long id = manager.addChunk("课程笔记", "guide", "", "第一版正文", "");
        assertTrue(id > 0);

        AIToolResult r = update(params("find_title", "课程笔记", "content", "第二版正文 newbody"));

        assertTrue("按唯一标题应能更新: " + r.getErrorMessage(), r.isSuccess());
        assertEquals(id, ((Number) r.getAdditionalInfo().get("id")).longValue());
    }

    @Test
    public void ambiguousFindTitleIsRejectedWithHint() {
        manager.addChunk("同名笔记", "guide", "", "块一", "doc.pdf");
        manager.addChunk("同名笔记", "guide", "", "块二", "doc.pdf");

        AIToolResult r = update(params("find_title", "同名笔记", "content", "想改掉"));

        assertFalse("同名多条不能盲改", r.isSuccess());
        assertTrue("错误里要提示改用 id: " + r.getErrorMessage(),
                r.getErrorMessage() != null && r.getErrorMessage().contains("id"));
    }

    @Test
    public void updateWithoutAnyTargetOrFieldIsRejected() {
        AIToolResult noTarget = update(params("content", "x"));
        assertFalse(noTarget.isSuccess());
        assertTrue(noTarget.getErrorMessage().contains("id"));

        long id = manager.addChunk("标题", "general", "", "正文", "");
        AIToolResult noField = update(params("id", id));
        assertFalse("没给要改的字段应报错", noField.isSuccess());
        assertTrue(noField.getErrorMessage().contains("字段"));
    }

    // ==================== list / get / 精确检索（2026-10-28） ====================

    /** list 只回索引（无正文），get 才取全文——避免"查一下知识库"就把整库正文拉进上下文。 */
    @Test
    public void listReturnsIndexWithoutContentAndGetReturnsOneRow() throws Exception {
        long id = manager.addChunk("条目A", "guide", "kw", "很长的正文 AAAAA", "s");
        manager.addChunk("条目B", "general", "", "正文 B", "");

        Map<String, Object> listParams = params("action", "list", "limit", 10);
        AIToolResult list = tool.execute(listParams);
        assertTrue(list.isSuccess());
        JSONObject payload = new JSONObject(String.valueOf(list.getResult()));
        assertEquals(2, payload.optInt("count"));
        JSONObject item = payload.optJSONArray("items").optJSONObject(0);
        assertFalse("索引条目不能带正文", item.has("content"));

        AIToolResult one = tool.execute(params("action", "get", "id", id));
        assertTrue(one.isSuccess());
        JSONObject row = new JSONObject(String.valueOf(one.getResult()));
        assertEquals("条目A", row.optString("title"));
        assertTrue(row.optString("content").contains("AAAAA"));
    }

    @Test
    public void getWithoutIdIsRejectedInsteadOfDumpingAll() {
        AIToolResult r = tool.execute(params("action", "get"));
        assertFalse("get 必须带 id，不能退化成'返回全部'", r.isSuccess());
        assertTrue(r.getErrorMessage().contains("id"));
    }

    /** search 带 exact_title 时走精确取：只命中标题全等的那条。 */
    @Test
    public void searchWithExactTitleSkipsFuzzyRecall() throws Exception {
        long id = manager.addChunk("光合作用", "guide", "", "光能转化学能", "");
        manager.addChunk("光合作用的发现史", "guide", "", "历史", "");

        AIToolResult fuzzy = tool.execute(params("action", "search", "query", "光合作用"));
        assertTrue(fuzzy.isSuccess());
        JSONArray fuzzyHits = new JSONArray(String.valueOf(fuzzy.getResult()));
        assertTrue("模糊检索会多命中: " + fuzzyHits.length(), fuzzyHits.length() >= 2);

        AIToolResult exact = tool.execute(params("action", "search", "exact_title", "光合作用"));
        assertTrue(exact.isSuccess());
        JSONArray exactHits = new JSONArray(String.valueOf(exact.getResult()));
        assertEquals("精确取只应命中一条", 1, exactHits.length());
        assertEquals(id, exactHits.optJSONObject(0).optLong("id"));
    }
}
