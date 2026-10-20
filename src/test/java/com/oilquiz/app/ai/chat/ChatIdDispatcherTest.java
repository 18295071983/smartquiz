package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;

/**
 * T/C 子 ID 的跨会话唯一性（2026-10-21）。
 *
 * <p>要求：清空历史 / 杀进程重进后，主 id 计数器不能回到 T1；子 id 带主 id 前缀，
 * 所以 T/C 等子 id 也就不会跨会话重号。这里不做"必须等于 T1"的断言，
 * 只钉住"只增不退、前后不重"。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ChatIdDispatcherTest {

    @Test
    public void resetDoesNotRecycleMasterOrSubIds() {
        ChatIdDispatcher d = ChatIdDispatcher.getInstance();
        String turnA = d.applyMasterId();
        String componentA = d.applySubId(ChatIdDispatcher.IdType.COMPONENT, turnA);
        assertTrue(componentA.startsWith(turnA + "-C"));

        d.reset();

        String turnB = d.applyMasterId();
        String componentB = d.applySubId(ChatIdDispatcher.IdType.COMPONENT, turnB);
        assertNotEquals("清空历史后主 id 不能回收", turnA, turnB);
        assertNotEquals("组件 id 不能回收", componentA, componentB);
    }

    @Test
    public void seedingFromRestoredHistoryMovesCounterForward() {
        ChatIdDispatcher d = ChatIdDispatcher.getInstance();
        long next = d.seedFromTurnIds(Collections.singletonList("T900000"));
        assertTrue("播种后下一个主 id 必须大于历史最大 T 号", next > 900000L);
        String turn = d.applyMasterId();
        long n = Long.parseLong(turn.substring(1));
        assertTrue("新回合不能落在历史 T900000 上", n > 900000L);
    }
}
