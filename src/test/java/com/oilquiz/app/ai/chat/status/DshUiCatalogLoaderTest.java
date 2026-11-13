package com.oilquiz.app.ai.chat.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 甲板配置表（2026-11-13，M3 / P3 / 验收 A7 的可测部分）。
 *
 * <p>配置坏掉不能把甲板搞没：所有非法输入都必须回退默认值（1 / 2），且上限收敛到 0..3。</p>
 */
public class DshUiCatalogLoaderTest {

    @Test
    public void defaultsWhenMissingOrEmpty() {
        assertEquals(1, DshUiCatalogLoader.parse(null).maxRowsWithProgress());
        assertEquals(2, DshUiCatalogLoader.parse("").maxRowsWithoutProgress());
        assertEquals(1, DshUiCatalogLoader.load(null).maxRowsWithProgress());
    }

    @Test
    public void parsesValidConfig() {
        DshUiCatalogLoader c = DshUiCatalogLoader.parse(
                "{\"version\":2,\"deck\":{\"max_rows_with_progress\":2,\"max_rows_without_progress\":3}}");
        assertEquals(2, c.maxRowsWithProgress());
        assertEquals(3, c.maxRowsWithoutProgress());
        assertTrue("来源应写清楚", c.source().contains("v2"));
    }

    @Test
    public void brokenJsonFallsBackWithoutThrowing() {
        DshUiCatalogLoader c = DshUiCatalogLoader.parse("{not json");
        assertEquals(1, c.maxRowsWithProgress());
        assertEquals(2, c.maxRowsWithoutProgress());
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
        assertEquals(1, DshUiCatalogLoader.parse("{\"version\":1}").maxRowsWithProgress());
    }
}
