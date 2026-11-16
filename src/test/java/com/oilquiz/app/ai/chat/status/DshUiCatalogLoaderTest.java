package com.oilquiz.app.ai.chat.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 甲板配置表（2026-11-13，M3 / P3 / 验收 A7 的可测部分）。
 *
 * <p>配置坏掉不能把甲板搞没：所有非法输入都必须回退默认值，且上限收敛到 0..{@link DshUiCatalogLoader#HARD_MAX}。</p>
 *
 * <p><b>默认值口径（2026-11-16 订正）</b>：{@code e7a0560b} 把甲板默认行数从 1/2 提到
 * <b>2/5</b>（硬上限 3→6），因为状态拆成了 5 个 dock 插槽，"只显示 1 行"放不下。
 * 本类原先按 1/2 断言（c1aa7f99 的旧口径），一直红到现在 —— 现在跟产品口径对齐：
 * 测的是"没配置/坏配置时回退到几行"，具体数字应按当前默认值断言，而不是按历史值。</p>
 */
public class DshUiCatalogLoaderTest {

    @Test
    public void defaultsWhenMissingOrEmpty() {
        // 当前默认：有进度 2 行、无进度 5 行（见 e7a0560b 的说明与 DEFAULT_MAX_* 常量）
        assertEquals(2, DshUiCatalogLoader.parse(null).maxRowsWithProgress());
        assertEquals(5, DshUiCatalogLoader.parse("").maxRowsWithoutProgress());
        assertEquals(2, DshUiCatalogLoader.load(null).maxRowsWithProgress());
    }

    @Test
    public void parsesValidConfig() {
        DshUiCatalogLoader c = DshUiCatalogLoader.parse(
                "{\"version\":2,\"deck\":{\"max_rows_with_progress\":3,\"max_rows_without_progress\":4}}");
        assertEquals(3, c.maxRowsWithProgress());
        assertEquals(4, c.maxRowsWithoutProgress());
        assertTrue("来源应写清楚", c.source().contains("v2"));
    }

    @Test
    public void brokenJsonFallsBackWithoutThrowing() {
        DshUiCatalogLoader c = DshUiCatalogLoader.parse("{not json");
        assertEquals(2, c.maxRowsWithProgress());
        assertEquals(5, c.maxRowsWithoutProgress());
        assertTrue("应说明已回退", c.source().contains("回退"));
    }

    @Test
    public void outOfRangeValuesAreClamped() {
        DshUiCatalogLoader c = DshUiCatalogLoader.parse(
                "{\"deck\":{\"max_rows_with_progress\":99,\"max_rows_without_progress\":-5}}");
        assertEquals(DshUiCatalogLoader.HARD_MAX, c.maxRowsWithProgress());
        assertEquals(0, c.maxRowsWithoutProgress());
    }

    @Test
    public void missingDeckObjectFallsBack() {
        assertEquals(2, DshUiCatalogLoader.parse("{\"version\":1}").maxRowsWithProgress());
    }
}
