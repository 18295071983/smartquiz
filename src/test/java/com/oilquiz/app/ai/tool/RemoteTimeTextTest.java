package com.oilquiz.app.ai.tool;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 相对时间文案单测（2026-11-16）：连接页/扩展面板的"活着"指示依赖它。 */
public class RemoteTimeTextTest {

    @Test
    public void seconds() {
        assertEquals("0 秒前", RemoteTimeText.ago(0));
        assertEquals("0 秒前", RemoteTimeText.ago(-5000));
        assertEquals("1 秒前", RemoteTimeText.ago(1_000));
        assertEquals("37 秒前", RemoteTimeText.ago(37_400));
        assertEquals("59 秒前", RemoteTimeText.ago(59_900));
    }

    @Test
    public void minutes() {
        assertEquals("1 分钟前", RemoteTimeText.ago(60_000));
        assertEquals("3 分钟前", RemoteTimeText.ago(3 * 60_000 + 20_000));
    }

    @Test
    public void probeLine() {
        assertEquals("最近探测 0 秒前（45ms）", RemoteTimeText.probeLine(0, 45));
        assertEquals("最近探测 12 秒前（388ms）", RemoteTimeText.probeLine(12_300, 388));
        assertEquals("最近探测 2 分钟前（7ms）", RemoteTimeText.probeLine(2 * 60_000 + 1_000, 7));
    }

    @Test
    public void statusSuffix() {
        assertEquals("5 秒前", RemoteTimeText.statusSuffix(5_000));
    }
}
