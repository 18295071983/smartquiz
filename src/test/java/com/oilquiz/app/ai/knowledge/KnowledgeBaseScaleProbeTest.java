package com.oilquiz.app.ai.knowledge;

import static org.junit.Assert.assertEquals;
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
 * 规模探针（2026-11-10）：回答"这套全表 LIKE 到底能撑多少条"。
 *
 * <p>不是功能测试，是给人看数字的：插入 N 块后分别用 1 个 token 和 6 个 token 检索，
 * 打印插入/检索耗时。成本模型是"每个 token 一次全表扫"，所以 token 数与耗时应当近似线性。</p>
 *
 * <p>注意：Robolectric 的 SQLite 与真机同源但不是同一份，绝对值只能当**相对参考**
 * （同机前后对比有意义的），真机数字要以设备上的实测为准。断言只钉"能跑完、能命中"，
 * 不钉耗时——否则机器一换就红。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class KnowledgeBaseScaleProbeTest {

    private static final int ROWS = 4000;

    private KnowledgeBaseManager manager;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        KnowledgeBaseManager.resetForTesting();
        manager = KnowledgeBaseManager.getInstance(context);
        manager.clear();
    }

    @Test
    public void fullScanCostGrowsLinearlyWithTokenCount() throws Exception {
        JSONArray items = new JSONArray();
        for (int i = 0; i < ROWS; i++) {
            JSONObject item = new JSONObject();
            item.put("title", "条目" + i);
            item.put("category", "general");
            item.put("content", "第" + i + "条：车间光气泄漏应急处理流程，先报警、再隔离、最后洗消，"
                    + "作业票编号 ABC" + i + "，参考规程 HG/T " + (20000 + i) + "。");
            items.put(item);
        }
        long insertStart = System.currentTimeMillis();
        int added = manager.addBatch(items);
        long insertMs = System.currentTimeMillis() - insertStart;
        assertEquals("探针前置：应全部写入", ROWS, added);

        long oneToken = timeSearch("光气");
        long threeTokens = timeSearch("光气泄漏处理");
        long sixTokens = timeSearch("车间光气泄漏应该怎么处理才安全");

        int hits = manager.search("光气泄漏处理", null, 5, false).length();
        assertTrue("大规模下仍应命中", hits > 0);

        System.out.println("[KB-PROBE] rows=" + ROWS
                + " insert=" + insertMs + "ms"
                + " | 1token=" + oneToken + "ms"
                + " | 3tokens=" + threeTokens + "ms"
                + " | 6tokens=" + sixTokens + "ms"
                + " | hits=" + hits
                + " | dbBytes=" + dbSizeBytes());
    }

    private long timeSearch(String query) {
        // 先跑一次预热（首次含连接、语句编译、页缓存冷启动），再取 3 次里的最小——
        // 不预热时第一次查询会比后续慢一个数量级，量出来的"线性成本"是假的
        manager.search(query, null, 5, false);
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long start = System.currentTimeMillis();
            manager.search(query, null, 5, false);
            best = Math.min(best, System.currentTimeMillis() - start);
        }
        return best;
    }

    private long dbSizeBytes() {
        Context context = ApplicationProvider.getApplicationContext();
        java.io.File file = context.getDatabasePath("knowledge_base.db");
        return file.exists() ? file.length() : -1;
    }
}
