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
 * v6 本地向量召回的测试：用"概念字典"假向量模拟 embedding，不联网。
 *
 * <p>假向量的设计要点：语义相近的词落在同一维（"光气"/"有毒气体" 同维，
 * "泄漏"/"跑冒滴漏" 同维），这样才能验证"关键词一个字都不命中、语义却能召回"，
 * 而这正是引入向量层的唯一理由。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class KnowledgeBaseSemanticSearchTest {

    /** 概念字典：[维度][同义词...]，文本命中任一同义词则该维 +1，最后 L2 归一化 */
    private static final String[][] CONCEPTS = {
            {"光气", "有毒气体", "毒气", "窒息性气体"},
            {"泄漏", "泄露", "跑冒滴漏", "逸散"},
            {"培训", "教育", "演练"},
            {"审批", "签字", "作业票"},
    };

    private KnowledgeBaseManager manager;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        KnowledgeBaseManager.resetForTesting();
        manager = KnowledgeBaseManager.getInstance(context);
        manager.clear();
        // 关掉"写入后自动补向量"：本类显式调用 backfillEmbeddings，避免后台线程抢跑导致计数不确定
        manager.setAutoBackfillForTesting(false);
        manager.setEmbedderForTesting(KnowledgeBaseSemanticSearchTest::conceptVector);
    }

    private static float[] conceptVector(String text) {
        float[] vec = new float[CONCEPTS.length];
        for (int d = 0; d < CONCEPTS.length; d++) {
            for (String word : CONCEPTS[d]) {
                if (text.contains(word)) {
                    vec[d] += 1;
                    break;
                }
            }
        }
        double norm = 0;
        for (float v : vec) norm += (double) v * v;
        if (norm == 0) return vec;
        float inv = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < vec.length; i++) vec[i] *= inv;
        return vec;
    }

    /** 写入后回填：每块都该拿到向量，stats 能看见已向量化的数量。 */
    @Test
    public void backfillEmbedsEveryChunkAndReportsIt() throws Exception {
        manager.addChunk("光气泄漏处置", "general", "", "光气泄漏后先撤离再洗消", "");
        manager.addChunk("车间培训记录", "general", "", "本月安全培训与应急演练安排", "");
        manager.addChunk("作业票审批", "general", "", "动火作业票需要签字审批", "");

        assertEquals(3, manager.backfillEmbeddings(10));

        JSONObject stats = manager.stats();
        assertEquals(3, stats.optInt("embedded"));
        assertEquals(3, stats.optInt("total"));
        assertTrue("回填不该改变文档数", stats.optInt("documents") <= 3);
    }

    /**
     * 核心用例：关键词一个字都不命中，语义照样能召回。
     *
     * <p>查询用"有毒气体跑冒滴漏了咋整"，与库里的"光气泄漏"在 bigram 上零重叠；
     * 但两者在概念空间同维，余弦为 1。</p>
     */
    @Test
    public void semanticRecallCatchesWhatKeywordsMiss() throws Exception {
        manager.addChunk("光气泄漏应急处置", "general", "", "一旦发生光气泄漏，立即撤离人员并做洗消。", "");
        manager.addChunk("月度培训计划", "general", "", "本月安排安全教育与应急演练。", "");
        assertEquals(2, manager.backfillEmbeddings(10));

        String query = "有毒气体跑冒滴漏了咋整";

        JSONArray keywordOnly = manager.search(query, null, 5, false);
        assertFalse("前提：关键词召回确实找不到（否则这条用例证明不了扩召回）",
                containsId(keywordOnly, 1));

        JSONArray semantic = manager.semanticSearch(query, null, 5);
        assertTrue("向量召回应能捞回语义相同的块", containsId(semantic, 1));

        JSONArray hybrid = manager.search(query, null, 5, true);
        assertTrue("semantic=true 且已有向量时应走融合，结果里要有它", containsId(hybrid, 1));
        assertNotNull("融合结果应带 rrf_score", findById(hybrid, 1).opt("rrf_score"));
        assertFalse("不相关的培训块不该被向量灌进来", containsId(hybrid, 2));
    }

    /** 向量还没回填时，semantic=true 不能改变原有关键词结果（保持 v5 行为）。 */
    @Test
    public void semanticFlagFallsBackWhenNoVectors() throws Exception {
        manager.addChunk("光气泄漏应急处置", "general", "", "光气泄漏后立即撤离。", "");
        assertEquals("没回填就没有向量", 0, manager.countEmbeddings(null));

        JSONArray plain = manager.search("光气泄漏", null, 5, false);
        JSONArray semantic = manager.search("光气泄漏", null, 5, true);
        assertEquals("没有向量时应回落到关键词召回，条数一致", plain.length(), semantic.length());
        assertTrue(containsId(semantic, 1));
    }

    /** 向量化失败（模型/网络问题）不能把关键词结果弄丢。 */
    @Test
    public void embeddingFailureDoesNotBreakKeywordSearch() throws Exception {
        manager.addChunk("光气泄漏应急处置", "general", "", "光气泄漏后立即撤离。", "");
        manager.setEmbedderForTesting(text -> {
            throw new IllegalStateException("模拟 embedding 服务不可用");
        });

        assertEquals("失败时回填返回 0，不抛异常", 0, manager.backfillEmbeddings(10));
        JSONArray results = manager.search("光气泄漏", null, 5, true);
        assertTrue("关键词命中的结果必须还在", containsId(results, 1));
        assertEquals(0, manager.semanticSearch("光气泄漏", null, 5).length());
    }

    /** 两路都命中的同一块只能出现一次。 */
    @Test
    public void hybridResultsDoNotDuplicateSameChunk() throws Exception {
        manager.addChunk("光气泄漏应急处置", "general", "", "光气泄漏后立即撤离并洗消。", "");
        assertEquals(1, manager.backfillEmbeddings(10));

        JSONArray hybrid = manager.search("光气泄漏", null, 5, true);
        assertEquals("一块命中两路也只能占一个位置", 1, hybrid.length());
        assertEquals(1, hybrid.optJSONObject(0).optLong("id"));
    }

    /** 分类过滤仍然生效：别的分类不进语义结果。 */
    @Test
    public void semanticSearchRespectsCategory() throws Exception {
        manager.addChunk("光气泄漏应急处置", "safety", "", "光气泄漏后立即撤离。", "");
        manager.addChunk("光气泄漏应急处置", "training", "", "光气泄漏演练安排。", "");
        assertEquals(2, manager.backfillEmbeddings(10));

        JSONArray results = manager.semanticSearch("有毒气体泄漏", "safety", 5);
        assertEquals("只该回 safety 那一块", 1, results.length());
        assertEquals("safety", results.optJSONObject(0).optString("category"));
    }

    /** 换模型（向量维度不同）时旧向量应整批作废，而不是拿旧维度硬算。 */
    @Test
    public void switchingEmbedderInvalidatesOldVectors() throws Exception {
        manager.addChunk("光气泄漏应急处置", "general", "", "光气泄漏后立即撤离。", "");
        assertEquals(1, manager.backfillEmbeddings(10));
        assertEquals(1, manager.countEmbeddings(null));

        // 模拟换模型：向量维度从 4 变 8，旧向量与新 query 维度不一致
        manager.setEmbedderForTesting(text -> new float[]{1, 0, 0, 0, 0, 0, 0, 0});
        JSONArray results = manager.semanticSearch("光气泄漏", null, 5);
        assertEquals("维度对不上的旧向量不能被当成“很像”返回", 0, results.length());
    }

    /**
     * 端点坏了就"静默不启用"：连续失败两次熔断，之后不再打接口；
     * 检索结果与纯关键词一致，不报错也不卡超时（这是"别给主 agent 制造故障"的底线）。
     */
    @Test
    public void brokenEmbedderFallsBackSilentlyAndStopsRetrying() throws Exception {
        manager.addChunk("光气泄漏应急处置", "general", "", "光气泄漏后立即撤离。", "");
        assertEquals(1, manager.backfillEmbeddings(10));

        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        manager.setEmbedderForTesting(text -> {
            calls.incrementAndGet();
            throw new java.io.IOException("模拟 embedding 端点不可用");
        });

        for (int i = 0; i < 3; i++) {
            JSONArray hits = manager.search("光气泄漏", null, 5, true);
            assertTrue("向量路失败不该把关键词命中一起丢掉", containsId(hits, 1));
        }
        assertEquals("连续失败达到阈值后应停止重试，第三次检索不再调用", 2, calls.get());
        assertEquals("熔断期内语义路返回空，不抛异常", 0,
                manager.semanticSearch("光气泄漏", null, 5).length());
        // 熔断后 keyword 结果仍然完整
        assertTrue(containsId(manager.search("光气泄漏", null, 5, false), 1));
    }

    private static boolean containsId(JSONArray array, long id) {
        return findById(array, id) != null;
    }

    private static JSONObject findById(JSONArray array, long id) {
        if (array == null) return null;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && item.optLong("id") == id) return item;
        }
        return null;
    }
}
