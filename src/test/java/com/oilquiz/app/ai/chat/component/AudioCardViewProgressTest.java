package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 音乐播放卡片的进度换算与文字（2026-10-23 修"进度条/秒数不跟随"）。
 *
 * <p>真正的故障在 threading：旧实现用后台线程轮询 ExoPlayer，Media3 只允许在创建线程
 * （主线程）访问，每轮都抛 IllegalStateException 又被吞掉，UI 一次没更新过——
 * 这一条只能在真机上验，JVM 测不到。这里钉住的是同一段代码里随手会错的数学与文案：
 * 时长未知时不许把 {@code C.TIME_UNSET} 格式化成 00:00 冒充零时长，
 * 位置越界不许把 SeekBar 的 progress 撑爆（setProgress 会抛 IllegalArgumentException）。</p>
 */
public class AudioCardViewProgressTest {

    @Test
    public void progressMapsPositionToThousandths() {
        assertEquals(0, AudioCardView.progressOf(0, 100_000));
        assertEquals(500, AudioCardView.progressOf(50_000, 100_000));
        assertEquals(1000, AudioCardView.progressOf(100_000, 100_000));
        // 浮点误差/seek 越界时夹取
        assertEquals(1000, AudioCardView.progressOf(120_000, 100_000));
        assertEquals(0, AudioCardView.progressOf(-5_000, 100_000));
    }

    @Test
    public void unknownDurationHasNoProgress() {
        // C.TIME_UNSET 是负数；直播/未 READY 的时长也是 <=0
        assertEquals(-1, AudioCardView.progressOf(0, -1));
        assertEquals(-1, AudioCardView.progressOf(10_000, 0));
        assertEquals(-1, AudioCardView.progressOf(10_000, Long.MIN_VALUE + 1));
    }

    @Test
    public void progressTextShowsDashForUnknownDuration() {
        assertEquals("01:05 / 03:05", AudioCardView.formatProgressText(65_000, 185_000));
        assertEquals("00:00 / 03:05", AudioCardView.formatProgressText(-1, 185_000));
        assertEquals("00:00 / --:--", AudioCardView.formatProgressText(0, -1));
        assertEquals("00:00 / --:--", AudioCardView.formatProgressText(0, 0));
        assertEquals("10:00 / 10:00", AudioCardView.formatProgressText(600_000, 600_000));
    }
}
