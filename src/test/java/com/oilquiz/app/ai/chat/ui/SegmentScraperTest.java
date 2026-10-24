package com.oilquiz.app.ai.chat.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 刮刀滑入的"换段判据"（纯逻辑那半）。动画本身要真机看，但**什么时候该播**是这条链上
 * 唯一会出错的地方——2026-10-06 的闪烁就是"每个 token 片段都重启动画"，判据必须钉住。
 */
public class SegmentScraperTest {

    @Test
    public void signalCountsWholeLinesOnly() {
        assertEquals(0, SegmentScraper.segmentSignal(null));
        assertEquals(0, SegmentScraper.segmentSignal(""));
        assertEquals(0, SegmentScraper.segmentSignal("同一行还在长字"));
        assertEquals(1, SegmentScraper.segmentSignal("第一段\n"));
        assertEquals(2, SegmentScraper.segmentSignal("第一段\n第二段\n"));
    }

    @Test
    public void onlyGrowthTriggersOneSlide() {
        assertTrue("新增一整段才滑", SegmentScraper.isNewSegment(1, 2));
        assertFalse("逐 token 追加（没换行）不重播", SegmentScraper.isNewSegment(1, 1));
        assertFalse("重置/回退不播，否则会立刻补一次滑入", SegmentScraper.isNewSegment(3, 0));
        assertFalse("首次绑定不播（入场交给逐块错峰）", SegmentScraper.isNewSegment(0, 0));
    }

    @Test
    public void streamingThinkingGrowsSlidesOncePerParagraph() {
        // 模拟同一条 reasoning part 逐 tick 变长：只有跨行那一刻算换段
        String[] ticks = {"想", "想想", "想想这", "想想这\n", "想想这\n那", "想想这\n那\n"};
        int prev = 0;
        int slides = 0;
        for (String t : ticks) {
            int sig = SegmentScraper.segmentSignal(t);
            if (SegmentScraper.isNewSegment(prev, sig)) slides++;
            prev = sig;
        }
        assertEquals("两段思考只该滑两次", 2, slides);
    }
}
