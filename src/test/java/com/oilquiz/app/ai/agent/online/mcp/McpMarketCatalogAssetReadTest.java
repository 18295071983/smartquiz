package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * 走真资源通道（{@code AssetManager}）读清单，并验检索与缓存。
 *
 * <p>{@link McpMarketCatalogTest} 已经用同一份文件的字节校过数据契约；这里补的是"资源名写错、
 * 打包没带上、缓存串了"这三件只有 Context 才暴露得出来的事。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class McpMarketCatalogAssetReadTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        McpMarketCatalog.clearCacheForTest();
    }

    @Test
    public void assetIsPackagedAndParsesIntoTheSameSnapshotAsTheFile() throws Exception {
        McpMarketCatalog.Snapshot fromAsset = McpMarketCatalog.load(context);
        String raw = new String(Files.readAllBytes(
                Paths.get("src/main/assets/mcp_market_catalog.json")), StandardCharsets.UTF_8);
        McpMarketCatalog.Snapshot fromFile = McpMarketCatalog.parse(raw);

        assertEquals(fromFile.version, fromAsset.version);
        assertEquals(fromFile.entries.size(), fromAsset.entries.size());
        for (int i = 0; i < fromFile.entries.size(); i++) {
            assertEquals(fromFile.entries.get(i).id, fromAsset.entries.get(i).id);
        }
        assertTrue("资源通道不该报数据问题：" + fromAsset.problems, fromAsset.problems.isEmpty());
    }

    @Test
    public void secondLoadUsesTheCache() {
        McpMarketCatalog.Snapshot first = McpMarketCatalog.load(context);
        McpMarketCatalog.Snapshot second = McpMarketCatalog.load(context);
        assertSame("重复 load 必须复用同一份快照（列表页每次重绘都读一遍资源没必要）", first, second);
    }

    @Test
    public void searchMatchesProviderTagAndIdButKeepsCaseInsensitive() {
        List<McpMarketEntry> all = McpMarketCatalog.entries(context);
        assertTrue(all.size() >= 6);

        List<McpMarketEntry> byProvider = McpMarketCatalog.search(context, "腾讯");
        assertFalse("按服务商要能搜到：" + byProvider, byProvider.isEmpty());
        for (McpMarketEntry e : byProvider) {
            assertTrue("搜出来不该混进别家：" + e.title + " / " + e.provider,
                    e.provider.contains("腾讯") || e.title.contains("腾讯"));
        }

        List<McpMarketEntry> byTag = McpMarketCatalog.search(context, "路径规划");
        assertFalse("按标签要能搜到", byTag.isEmpty());

        List<McpMarketEntry> byId = McpMarketCatalog.search(context, "AMAP-MAPS");
        assertFalse("搜索要忽略大小写（id 是英文）：" + byId, byId.isEmpty());

        assertTrue("搜不到时应返回空而不是全量",
                McpMarketCatalog.search(context, "不可能存在的服务名xyz").isEmpty());
        assertEquals("空查询等于不过滤", all.size(), McpMarketCatalog.search(context, "  ").size());
    }

    @Test
    public void byIdFindsAndMissesCleanly() {
        List<McpMarketEntry> all = McpMarketCatalog.entries(context);
        String firstId = all.get(0).id;
        assertEquals(firstId, McpMarketCatalog.byId(context, firstId).id);
        // 取不到时必须给 null，不能拿第一条兜底——市场页会照着它弹安装框
        assertNull(McpMarketCatalog.byId(context, "definitely-not-in-catalog"));
        assertNull(McpMarketCatalog.byId(context, null));
        assertNull(McpMarketCatalog.byId(context, firstId.toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    public void nullContextFallsBackToCacheWhenWarmAndEmptyWhenCold() {
        McpMarketCatalog.clearCacheForTest();
        assertEquals("冷缓存又没 Context：给空清单而不是崩",
                0, McpMarketCatalog.entries(null).size());
        assertNull(McpMarketCatalog.byId(null, "tencent-map"));

        McpMarketCatalog.clearCacheForTest();
        int warm = McpMarketCatalog.entries(context).size();
        assertTrue(warm > 0);
        assertEquals("缓存已热时没 Context 也该给同一份，不该把清单抹掉",
                warm, McpMarketCatalog.entries(null).size());
    }
}
