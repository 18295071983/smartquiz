package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 视频卡按视频比例算容器高度（P0 修固定 220dp 的竖屏/超宽黑边）。 */
public class VideoCardViewLayoutTest {

    @Test
    public void landscapeVideoUsesNaturalRatio() {
        assertEquals(180, VideoCardView.fitHeight(320, 1920, 1080, 160, 420));
    }

    @Test
    public void portraitVideoIsClampedToMax() {
        assertEquals(420, VideoCardView.fitHeight(320, 1080, 1920, 160, 420));
    }

    @Test
    public void ultraWideVideoIsClampedToMin() {
        assertEquals(160, VideoCardView.fitHeight(320, 3440, 1440, 160, 420));
    }

    @Test
    public void unknownSizeFallsBackToMin() {
        assertEquals(160, VideoCardView.fitHeight(320, 0, 0, 160, 420));
        assertEquals(160, VideoCardView.fitHeight(0, 1920, 1080, 160, 420));
    }
}
