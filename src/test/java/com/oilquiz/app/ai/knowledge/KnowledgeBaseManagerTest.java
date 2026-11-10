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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

    // ==================== 先索引后精确（2026-10-28） ====================

    /** 索引不带正文（省 token），按 id 取单条才回全文。 */
    @Test
    public void listMetaOmitsContentAndGetByIdReturnsFullRow() {
        long id = add("标题A", "guide", "kwA", "正文A", "srcA");
        add("标题B", "general", "", "正文B", "");

        JSONArray list = manager.listMeta(null, 10);
        assertEquals(2, list.length());
        JSONObject first = list.optJSONObject(0);
        assertNotNull(first);
        assertFalse("索引里不能带正文（那正是浪费 token 的根源）", first.has("content"));
        assertEquals("", first.optString("content"));
        assertTrue("索引要带 id/标题/分类", first.optLong("id") > 0 && !first.optString("title").isEmpty());

        JSONObject row = manager.getById(id);
        assertNotNull(row);
        assertEquals("正文A", row.optString("content"));
        assertEquals("标题A", row.optString("title"));
        assertEquals("kwA", row.optString("keywords"));

        // 分类过滤
        JSONArray guideOnly = manager.listMeta("guide", 10);
        assertEquals(1, guideOnly.length());
        assertEquals(id, guideOnly.optJSONObject(0).optLong("id"));
    }

    /** 精确取：标题全等 / id，不做"按字拆 token"的模糊召回。 */
    @Test
    public void exactSearchByTitleAndIdDoesNotFuzz() {        long id = add("光合作用", "guide", "", "植物把光能转成化学能", "");
        add("光合作用的发现史", "guide", "", "历史资料", "");

        JSONArray exact = manager.searchExact(null, "光合作用", 5);
        assertEquals("标题全等只应命中一条", 1, exact.length());
        assertEquals(id, exact.optJSONObject(0).optLong("id"));

        JSONArray byId = manager.searchExact(id, null, 5);
        assertEquals(1, byId.length());
        assertEquals("光合作用", byId.optJSONObject(0).optString("title"));

        // 模糊检索会把"发现史"也召回——这正是需要精确取的原因
        assertTrue("模糊检索会多命中", manager.search("光合作用", "guide", 5, false).length() >= 2);
        assertEquals("标题不存在时精确取返回 0", 0,
                manager.searchExact(null, "不存在的标题", 5).length());
    }
    /** 标签/别名精确过滤（2026-10-28）：keywords 存别名，tag 走整词匹配，不参与分词。 */
    @Test
    public void tagFilterMatchesWholeKeywordOnly() {
        long id = add("植物光合作用", "guide", "光合,photosynthesis", "正文一", "");
        add("光合作用的发现史", "guide", "发现史", "正文二", "");

        JSONArray byTag = manager.search("", null, "光合", 10, false);
        assertEquals("tag 整词命中只应第一条", 1, byTag.length());
        assertEquals(id, byTag.optJSONObject(0).optLong("id"));

        // 整词边界：查"光"不能命中"光合"
        assertEquals("tag 是整词匹配，不能前缀命中", 0,
                manager.search("", null, "光", 10, false).length());

        // 大小写/空格/中文逗号归一
        assertEquals("大小写应归一", 1,
                manager.search("", null, "PHOTOSYNTHESIS", 10, false).length());

        JSONArray list = manager.listMeta(null, "光合", 10);
        assertEquals("list 也支持 tag 过滤", 1, list.length());
        assertEquals(id, list.optJSONObject(0).optLong("id"));
    }
    // ==================== U1：中文 bigram 分词（2026-10-28） ====================

    @Test
    public void queryTokensAreCjkBigrams() {
        assertEquals(Arrays.asList("光合", "合作", "作用"),
                KnowledgeBaseManager.tokenizeQuery("光合作用"));
        // 中英混排：英文按词、中文按 bigram
        assertEquals(Arrays.asList("abc", "光合"),
                KnowledgeBaseManager.tokenizeQuery("ABC 光合"));
        // 单字保留单字（否则单字查询永远命中不了）
        assertEquals(Collections.singletonList("光"), KnowledgeBaseManager.tokenizeQuery("光"));
    }

    /** v3（逐字 AND）的反例：四个字分散在无关条目里也会命中；v4 bigram 必须只命中真正连写的。 */
    @Test
    public void bigramSearchRejectsScatteredCharacters() {
        long id = add("光合作用", "guide", "", "植物把光能转成化学能", "");
        add("无关条目", "general", "", "阳光、合并、作业、用途各不相同", "");

        JSONArray hits = manager.search("光合作用", null, 5, false);
        assertEquals("只有真正含「光合作用」的才命中", 1, hits.length());
        assertEquals(id, hits.optJSONObject(0).optLong("id"));
    }

    /** 顺序敏感：反序的四个字不应命中。 */
    @Test
    public void bigramSearchIsOrderSensitive() {
        add("光合作用", "guide", "", "植物把光能转成化学能", "");
        JSONArray reversed = manager.search("用作文合", null, 5, false);
        assertEquals("反序 query 不应命中" + reversed, 0, reversed.length());
    }

    // ==================== v5：整句查询可召回（2026-11-10） ====================

    /**
     * 分词必须按虚词断开，不能把跨词边界的 bigram 也当成检索词。
     *
     * <p>「光气泄漏怎么处理」如果整段切 bigram 会得到 光气、气泄、泄漏、漏怎、怎么、么处、处理，
     * 其中 漏怎 与 么处 在任何真实文档里都不存在（它们跨越了词边界）；v4 又把它们全 AND 起来，
     * 于是整句查询恒 0 命中。</p>
     */
    @Test
    public void queryTokensBreakAtStopwords() {
        assertEquals(Arrays.asList("光气", "气泄", "泄漏", "处理"),
                KnowledgeBaseManager.tokenizeQuery("光气泄漏怎么处理"));
        // 纯虚词被切掉后不留空 token
        assertEquals(Collections.emptyList(), KnowledgeBaseManager.tokenizeQuery("怎么办"));
        // 已有用例的三种形态必须保持不变（入库侧 buildSearchText 同口径）
        assertEquals(Arrays.asList("光合", "合作", "作用"),
                KnowledgeBaseManager.tokenizeQuery("光合作用"));
    }

    /** 自然语言整句提问，必须能命中真实条目——这是 v4 断掉的那条路。 */
    @Test
    public void naturalLanguageQueryHitsRealDoc() {
        long id = add("光气泄漏应急处理", "guide", "", "光气泄漏时应立即撤离并佩戴防毒面具；应急处理流程分三步：报警、隔离、洗消。", "");
        JSONArray hits = manager.search("光气泄漏怎么处理", null, 5, false);
        assertEquals("整句提问也要能命中: " + hits, 1, hits.length());
        assertEquals(id, hits.optJSONObject(0).optLong("id"));
    }

    /**
     * 真实输入往往是"场景 + 问题"，前面挂着一堆库里的条目并不含的词。
     *
     * <p>{@code OnlineAgentEngine.buildKnowledgeContext} 正是把**整条用户消息**当 query 传进来，
     * 覆盖率必须扛得住这些无关前缀（命中 4 / 共 7 个 token ≈ 0.57，高于 0.34 门槛）。</p>
     */
    @Test
    public void extraScenarioWordsDoNotSinkRecall() {
        add("光气泄漏应急处理", "guide", "", "光气泄漏时应立即撤离并佩戴防毒面具，应急处理流程分三步。", "");
        assertTrue("带场景词的整句也要命中",
                manager.search("车间发生光气泄漏应该怎么处理？", null, 5, false).length() >= 1);
    }

    /**
     * 覆盖率排序：只说"处理"的泛化条目不能盖过真正讲光气泄漏的条目，也不该被返回。
     */
    @Test
    public void coverageRanksRelevantAboveGeneric() {
        long relevant = add("光气泄漏应急处理", "guide", "", "光气泄漏应急处理：先撤离，再隔离，最后洗消。", "");
        add("无关条目", "general", "", "这条只讲处理流程的通用原则。", "");

        JSONArray hits = manager.search("光气泄漏处理", null, 5, false);
        assertEquals("覆盖率不足的泛化条目应被门槛挡掉: " + hits, 1, hits.length());
        assertEquals(relevant, hits.optJSONObject(0).optLong("id"));
    }

    /**
     * 候选不能先按 updated_at 截断再打分（v4 的 ORDER BY updated_at DESC LIMIT 50）。
     *
     * <p>批量导入的所有行时间戳相同，命中集一超过 50，真正相关但"更旧"的行就再也进不了排序。
     * 这里把目标条目**最先**写入，再灌 60 条泛化条目——旧实现只能在前 50 条里挑，必然丢。</p>
     */
    @Test
    public void oldCandidateIsNotDroppedByRecentRowBias() {
        long target = add("光气泄漏处理", "guide", "", "光气泄漏处理三步走。", "");
        for (int i = 0; i < 60; i++) {
            add("噪音" + i, "general", "", "通用处理流程第" + i + "条。", "");
        }
        JSONArray hits = manager.search("光气泄漏处理", null, 5, false);
        assertEquals("目标条目不能被候选截断丢掉: " + hits, 1, hits.length());
        assertEquals(target, hits.optJSONObject(0).optLong("id"));
    }

    // ==================== v5 资产安全：去重/按来源删/导出/清空保护（2026-11-10） ====================

    private List<KnowledgeBaseManager.KnowledgeChunk> docChunks(String title, String source, String... contents) {
        List<KnowledgeBaseManager.KnowledgeChunk> list = new ArrayList<>();
        for (String content : contents) {
            list.add(new KnowledgeBaseManager.KnowledgeChunk(title, "guide", "", content, source));
        }
        return list;
    }

    /**
     * 同一来源的导入去重：内容没变 → 跳过；内容变了 → 报冲突而不是悄悄写第二份；
     * 显式 delete(source) 之后才能重新导入。
     */
    @Test
    public void reimportSameSourceSkipsAndChangedSourceConflicts() {
        String source = "/sdcard/jiangyi.txt";
        JSONObject first = manager.storeChunks(docChunks("讲义", source, "第一段 光气泄漏", "第二段 应急处理"), "讲义", source);
        assertEquals("首次导入应写入 2 块: " + first, 2, first.optInt("added"));

        JSONObject again = manager.storeChunks(docChunks("讲义", source, "第一段 光气泄漏", "第二段 应急处理"), "讲义", source);
        assertTrue("同内容再导应跳过: " + again, again.optBoolean("skipped"));
        assertEquals(0, again.optInt("added"));
        assertEquals(2, manager.stats().optInt("total"));

        JSONObject conflict = manager.storeChunks(docChunks("讲义", source, "改版后的新内容"), "讲义", source);
        assertTrue("内容变了应报冲突: " + conflict, conflict.optBoolean("conflict"));
        assertFalse("冲突时不能写入", conflict.optBoolean("success"));
        assertEquals("库里仍是原来的 2 块", 2, manager.stats().optInt("total"));

        assertEquals("按来源删除应删掉整篇 2 块", 2, manager.deleteBySource(source));
        JSONObject replaced = manager.storeChunks(docChunks("讲义", source, "改版后的新内容"), "讲义", source);
        assertEquals("删干净后重导才能写入", 1, replaced.optInt("added"));
    }

    /** 按来源删只影响这一篇，别的来源不能被牵连。 */
    @Test
    public void deleteBySourceOnlyRemovesThatDocument() {
        String source = "/sdcard/a.pdf";
        manager.storeChunks(docChunks("文档甲", source, "甲一", "甲二", "甲三"), "文档甲", source);
        add("别的条目", "guide", "", "正文", "/sdcard/b.pdf");

        assertEquals(3, manager.deleteBySource(source));
        assertEquals("别的来源不能被牵连", 1, manager.stats().optInt("total"));
        assertEquals(0, manager.deleteBySource("/sdcard/none.pdf"));
    }

    /**
     * 清空是**全清**：连 tool_defs 一起删。
     *
     * <p>数字上不再区分"保留"——`ensureToolDefsInKnowledgeBase` 会校验"版本标记对得上且库里真有行"，
     * 清空后下次 Agent 初始化自动重灌，所以不需要在 clear 上留保护（2026-11-10 定稿）。</p>
     */
    @Test
    public void clearWipesEverythingIncludingToolDefs() {
        add("用户条目", "general", "", "正文", "");
        add("工具定义", "tool_defs", "", "工具正文", "system_tool_defs");
        assertEquals(2, manager.countByCategory("tool_defs") + manager.countByCategory("general"));

        int cleared = manager.clear();

        assertEquals("两条一起清", 2, cleared);
        assertEquals(0, manager.countByCategory("general"));
        assertEquals("tool_defs 也清掉（下次初始化会自动重灌）", 0, manager.countByCategory("tool_defs"));
    }

    /** 导出 → 清空 → 导回，一条不能少（库是用户唯一资产，得有备份通道）。 */
    @Test
    public void exportJsonRoundTripsThroughImport() throws Exception {
        add("条目一", "guide", "kw1", "正文一", "src1");
        add("条目二", "general", "", "正文二", "src2");

        JSONObject exported = manager.exportJson(null, null);
        assertTrue("导出应成功: " + exported, exported.optBoolean("success"));
        assertEquals(2, exported.optInt("count"));

        File file = new File(exported.getString("file"));
        assertTrue("导出文件应落盘: " + file, file.exists());
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue("结构要能被 import_json 吃回去: " + json, json.contains("\"chunks\""));

        manager.clear();
        assertEquals(0, manager.stats().optInt("total"));
        assertEquals("导回 2 条", 2, manager.importJson(json));
        assertEquals(2, manager.stats().optInt("total"));
    }

    // ==================== v5 文档层：溯源 / 去重 / 删除联动 / 翻页 / 过滤下推 ====================

    /** 一篇文档 = kb_documents 一行，块带 doc_id 与从 1 开始的块序，检索结果能说"第几块"。 */
    @Test
    public void storeChunksCreatesDocumentWithSequentialChunkSeq() {
        String source = "/sdcard/manual.md";
        JSONObject stored = manager.storeChunks(
                docChunks("手册", source, "第一块 alpha", "第二块 beta", "第三块 gamma"), "手册", source);
        assertEquals("首次导入应写入 3 块: " + stored, 3, stored.optInt("added"));
        long docId = stored.optLong("doc_id");
        assertTrue("应建出文档记录并回 doc_id: " + stored, docId > 0);

        JSONArray index = manager.listMeta(null, null, 10, 0);
        assertEquals(3, index.length());
        // listMeta 按 updated_at DESC, id DESC：最后写入的第 3 块在最前
        assertEquals("块序从 1 开始", 3, index.optJSONObject(0).optInt("chunk_seq"));
        assertEquals(docId, index.optJSONObject(0).optLong("doc_id"));
        assertEquals(1, index.optJSONObject(2).optInt("chunk_seq"));

        JSONObject stats = manager.stats();
        assertEquals("统计要带文档数", 1, stats.optInt("documents"));
        assertEquals(3, stats.optInt("total"));

        // 检索结果同样带溯源字段（模型能说"出自《手册》第 1 块"）
        JSONObject row = hit("alpha");
        assertEquals(docId, row.optLong("doc_id"));
        assertEquals(1, row.optInt("chunk_seq"));
    }

    /** 删掉一块后文档指纹要重算：块数变小，且再导"原版"必须被判成内容不同（否则哈希永远对不上库）。 */
    @Test
    public void deletingOneChunkRefreshesDocumentFingerprint() {
        String source = "/sdcard/notes.md";
        manager.storeChunks(docChunks("笔记", source, "保留 token1", "删掉 token2"), "笔记", source);
        long dropId = manager.search("token2", null, 5, false).optJSONObject(0).optLong("id");
        assertTrue(manager.deleteById(dropId));

        JSONObject stats = manager.stats();
        assertEquals("块数应减到 1", 1, stats.optInt("total"));
        assertEquals("文档记录仍在（还剩一块）", 1, stats.optInt("documents"));

        JSONObject reimport = manager.storeChunks(
                docChunks("笔记", source, "保留 token1", "删掉 token2"), "笔记", source);
        assertTrue("删过一块后指纹已变，再导原版应报冲突: " + reimport, reimport.optBoolean("conflict"));
    }

    /** 按标题删会删掉整篇的块：文档记录不能留成孤儿（块数为 0 时应自动消失）。 */
    @Test
    public void deletingDocumentByTitleDropsDocumentRecord() {
        String source = "/sdcard/same-title.md";
        manager.storeChunks(docChunks("同名标题", source, "甲 a1", "乙 a2"), "同名标题", source);
        assertEquals(1, manager.stats().optInt("documents"));

        assertEquals(2, manager.deleteByTitle("同名标题"));
        assertEquals(0, manager.stats().optInt("total"));
        assertEquals("块删光了，文档记录必须一起消失", 0, manager.stats().optInt("documents"));
    }

    /** list 翻页：offset 既不能返回重复行，也不能因为时间戳相同而漏行。 */
    @Test
    public void listMetaOffsetPagesWithoutOverlap() {
        for (int i = 0; i < 5; i++) {
            add("分页" + i, "general", "", "正文分页" + i, "");
        }
        JSONArray page1 = manager.listMeta(null, null, 2, 0);
        JSONArray page2 = manager.listMeta(null, null, 2, 2);
        assertEquals(2, page1.length());
        assertEquals(2, page2.length());

        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (JSONArray page : new JSONArray[]{page1, page2}) {
            for (int i = 0; i < page.length(); i++) {
                assertTrue("同一行不能在两页重复出现: " + page,
                        ids.add(page.optJSONObject(i).optLong("id")));
            }
        }
        assertEquals("两页应各覆盖 2 条不同的行", 4, ids.size());
    }

    /**
     * tool_defs 过滤下推到 SQL：不传 category 的检索永不返回工具定义，显式传才返回。
     *
     * <p>v4 时这层过滤在工具层 Java 侧、且在 top_k 截断之后，工具定义会白占召回名额。</p>
     */
    @Test
    public void searchExcludesToolDefsUnlessCategoryAsked() {
        add("光气泄漏处理", "guide", "", "光气泄漏处理步骤：撤离、隔离、洗消 alpha1", "");
        add("tool_guide_flow 工具用法", "tool_defs", "", "光气泄漏处理相关工具定义 alpha2", "system_tool_defs");

        JSONArray userHits = manager.search("光气泄漏处理", null, 8, false);
        assertEquals("不传 category 时工具定义不能占名额: " + userHits, 1, userHits.length());
        assertEquals("guide", userHits.optJSONObject(0).optString("category"));

        JSONArray toolHits = manager.search("光气泄漏处理", "tool_defs", 8, false);
        assertTrue("显式查 tool_defs 要能返回: " + toolHits, toolHits.length() >= 1);
        assertEquals("tool_defs", toolHits.optJSONObject(0).optString("category"));
    }

    /** stats 的 db_version 读的是库内真实 user_version（值应等于现行版本 5）。 */
    @Test
    public void statsReportsDatabaseVersion() {
        assertEquals("库内版本应为 v6", 6, manager.stats().optInt("db_version"));
    }

    /** clear 连文档层一起清：否则下次 list/去重会读到没有块的孤儿文档。 */
    @Test
    public void clearAlsoWipesDocumentsTable() {
        String source = "/sdcard/del.md";
        manager.storeChunks(docChunks("待清", source, "x1", "x2"), "待清", source);
        assertEquals(1, manager.stats().optInt("documents"));

        manager.clear();

        assertEquals(0, manager.stats().optInt("total"));
        assertEquals(0, manager.stats().optInt("documents"));
        JSONObject after = manager.storeChunks(docChunks("待清", source, "x1", "x2"), "待清", source);
        assertEquals("清空后同来源不该被判已存在", 2, after.optInt("added"));
    }

    /** 导出 → 导回：文档身份要活下来（同 source 的块重新聚成一篇，块序还原成 1..N）。 */
    @Test
    public void exportImportKeepsDocumentStructure() throws Exception {        String source = "/sdcard/keep.md";
        manager.storeChunks(docChunks("保留", source, "块一 one", "块二 two", "块三 three"), "保留", source);
        JSONObject exported = manager.exportJson(null, null);
        String json = new String(Files.readAllBytes(new File(exported.getString("file")).toPath()),
                StandardCharsets.UTF_8);

        manager.clear();
        assertEquals("导回 3 块", 3, manager.importJson(json));
        JSONObject stats = manager.stats();
        assertEquals(3, stats.optInt("total"));
        assertEquals("导回后仍是 1 篇文档，不是 3 个散块", 1, stats.optInt("documents"));

        JSONArray index = manager.listMeta(null, null, 10, 0);
        List<Integer> seqs = new ArrayList<>();
        for (int i = 0; i < index.length(); i++) {
            seqs.add(index.optJSONObject(i).optInt("chunk_seq"));
        }
        Collections.sort(seqs);
        assertEquals(Arrays.asList(1, 2, 3), seqs);
    }

    /** 导入没有 source 的裸条目：写入库但不算文档（doc_id=0），文档数不该涨。 */
    @Test
    public void importJsonKeepsLooseEntriesWithoutDocument() {
        String json = "{\"chunks\":[{\"title\":\"手工一\",\"category\":\"general\","
                + "\"content\":\"loose alpha 正文\"}]}";
        assertEquals("无 source 的条目也要导进来", 1, manager.importJson(json));

        JSONObject stats = manager.stats();
        assertEquals(1, stats.optInt("total"));
        assertEquals("没有 source 就不该凭空造文档记录", 0, stats.optInt("documents"));
        JSONObject row = manager.listMeta(null, null, 5, 0).optJSONObject(0);
        assertEquals("手工条目的 doc_id 是 0（不属于任何文档）", 0, row.optLong("doc_id"));
        assertEquals(0, row.optInt("chunk_seq"));
    }

    /**
     * 坏库自愈冒烟：把库文件写成垃圾字节再打开，操作必须能继续（自愈重建，而不是永久抛异常）。
     *
     * <p>为什么不写"断言抛 CorruptException 再恢复"：Robolectric 的 SQLite 实现抛异常的时机
     * 与真机不完全一致，卡死实现细节的断言只会变脆。这里钉的是**结果**——坏文件之后库还能用。
     * 真机上的完整验证见 {@code docs/knowledge_base.md} 5.5。</p>
     */
    @Test
    public void corruptedDatabaseFileRecoversToUsableState() throws Exception {
        add("旧条目", "general", "", "旧正文", "");
        KnowledgeBaseManager.resetForTesting();

        Context context = ApplicationProvider.getApplicationContext();
        File dbFile = context.getDatabasePath("knowledge_base.db");
        assertTrue("前置：库文件应已存在", dbFile.exists());
        Files.write(dbFile.toPath(), "this is definitely not a sqlite file"
                .getBytes(StandardCharsets.UTF_8));
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
            File side = new File(dbFile.getPath() + suffix);
            if (side.exists()) {
                side.delete();
            }
        }

        KnowledgeBaseManager recovered = KnowledgeBaseManager.getInstance(context);
        assertNotNull(recovered.stats());
        // 旧数据可能已经找不回（原文件留档为 *.corrupted-*），但库必须是**可用**的
        long id = recovered.addChunk("新条目", "general", "", "坏库重建后的正文", "");
        assertTrue("自愈后必须能写入: " + recovered.getLastError(), id > 0);
        assertNotNull(recovered.search("重建", null, 5, false));
    }

    /**
     * 损坏判定只认"真损坏信号"：把普通 SQL 错误当损坏去重建库，等于用丢光用户数据掩盖一个 bug。
     *
     * <p>Robolectric 下写坏文件不一定抛异常（见上一条），所以判定函数本身必须单独钉住。</p>
     */
    @Test
    public void corruptionDetectionOnlyMatchesRealCorruptionSignals() throws Exception {
        java.lang.reflect.Method method = KnowledgeBaseManager.class.getDeclaredMethod(
                "isCorruption", android.database.sqlite.SQLiteException.class);
        method.setAccessible(true);

        assertTrue("明确的 CorruptException 必须认",
                (Boolean) method.invoke(null,
                        new android.database.sqlite.SQLiteDatabaseCorruptException("boom")));
        assertTrue((Boolean) method.invoke(null,
                new android.database.sqlite.SQLiteException("database disk image is malformed")));
        assertTrue((Boolean) method.invoke(null,
                new android.database.sqlite.SQLiteException("file is not a database")));
        assertTrue((Boolean) method.invoke(null,
                new android.database.sqlite.SQLiteException("SQLITE_CORRUPT: bad header")));

        assertFalse("普通 SQL 错误不能当损坏（否则会白丢用户数据）",
                (Boolean) method.invoke(null,
                        new android.database.sqlite.SQLiteException("no such table: kb_chunks")));
        assertFalse((Boolean) method.invoke(null,
                new android.database.sqlite.SQLiteException("disk I/O error")));
        assertFalse((Boolean) method.invoke(null,
                new android.database.sqlite.SQLiteException("database is locked")));
    }
}