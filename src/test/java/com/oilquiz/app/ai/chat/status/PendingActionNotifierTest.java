package com.oilquiz.app.ai.chat.status;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 通知栏联动文案（2026-11-13）。纯函数，不碰 NotificationManager。
 */
public class PendingActionNotifierTest {

    @Test
    public void zeroPendingHasNoTitleOrText() {
        assertEquals("", PendingActionNotifier.titleFor(0, 0));
        assertEquals("", PendingActionNotifier.contentText(0, 0));
        assertEquals("", PendingActionNotifier.titleFor(-1, -3));
    }

    @Test
    public void kindIsNamedByComposition() {
        assertEquals("1 条审批待你处理，点这里回到对话", PendingActionNotifier.contentText(1, 0));
        assertEquals("2 条提问待你处理，点这里回到对话", PendingActionNotifier.contentText(0, 2));
        assertEquals("3 条审批与提问待你处理，点这里回到对话",
                PendingActionNotifier.contentText(2, 1));
    }

    @Test
    public void titleOnlyWhenSomethingPending() {
        assertEquals("待你处理", PendingActionNotifier.titleFor(0, 1));
        assertEquals("待你处理", PendingActionNotifier.titleFor(5, 0));
    }
}
