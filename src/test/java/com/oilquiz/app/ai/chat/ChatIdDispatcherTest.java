package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
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

    // ---- 2026-10-24：会话隔离（"当前回合"不能跨会话留着） -------------------

    /**
     * 切到一个**更老**的会话：主计数器不需要抬（max &lt; counter），但"当前回合"仍必须清空。
     * 原实现把清空写在计数器分支里，于是切回老会话后 {@code currentMasterId} 还是
     * 上一个会话最后那个 T —— 迟到的回调就会把消息挂到别的会话的回合下。
     */
    @Test
    public void switchingToOlderSessionClearsCurrentTurnAnchor() {
        ChatIdDispatcher d = ChatIdDispatcher.getInstance();
        String liveTurn = d.applyMasterId();
        org.junit.Assert.assertEquals(liveTurn, d.peekMasterId());

        d.seedFromTurnIds(Collections.singletonList("T1"));   // 比当前计数器老得多

        org.junit.Assert.assertNull("切会话后不能有\"当前回合\"属于上一个会话", d.peekMasterId());
        String nextTurn = d.applyMasterId();
        org.junit.Assert.assertNotEquals("新回合不得复用上一个会话的 turnId", liveTurn, nextTurn);
    }

    /** 恢复之后走隐式 applySubId（没有 masterId 参数的那条兜底路）也不得挂到旧回合。 */
    @Test
    public void implicitSubIdAfterRecoveryNeverSticksToPreviousSessionTurn() {
        ChatIdDispatcher d = ChatIdDispatcher.getInstance();
        String oldSessionTurn = d.applyMasterId();
        d.seedFromTurnIds(Collections.singletonList(oldSessionTurn));  // 恢复：认领当前会话

        String sub = d.applySubId(ChatIdDispatcher.IdType.COMPONENT);
        org.junit.Assert.assertNotNull(sub);
        org.junit.Assert.assertFalse("隐式补发不得挂到上一个会话的 turnId 上: " + sub,
                sub.startsWith(oldSessionTurn + "-"));
    }

    // ---- 2026-10-24：子 id 分桶从落盘数据派生（认领），不再靠进程状态猜 ------

    /**
     * 真机复现的形状：某回合已有 {@code C1/C2/C3}（产物卡），同一条消息里十几个工具组件没有 id。
     * 杀进程重进 → {@code subCounters} 归零 → 不先认领就补发，第一批补出来的就是
     * {@code C1/C2/C3}，与历史重号；而 data part 的 id 是 {@code data:<组件id>}，
     * keyed diff 会把两张卡当一个更新。认领后补发必须从 {@code C4} 起。
     */
    @Test
    public void adoptedComponentIdsAreNotReissuedAfterRestart() {
        ChatIdDispatcher d = ChatIdDispatcher.getInstance();
        String turn = d.applyMasterId();
        String c1 = d.applySubId(ChatIdDispatcher.IdType.COMPONENT, turn);
        String c2 = d.applySubId(ChatIdDispatcher.IdType.COMPONENT, turn);
        String c3 = d.applySubId(ChatIdDispatcher.IdType.COMPONENT, turn);
        org.junit.Assert.assertEquals(turn + "-C1", c1);
        org.junit.Assert.assertEquals(turn + "-C3", c3);

        d.reset();   // 模拟重启/清空：主 id 不回收（2026-10-28 起子桶也不再清）

        int adopted = d.adoptIds(Arrays.asList(c1, c2, c3, turn + "-A1", turn + "-K2", null, "脏数据"));
        org.junit.Assert.assertEquals("三个组件 id 加消息/思考轮 id 都应认领成功", 5, adopted);

        String issued1 = d.applySubId(ChatIdDispatcher.IdType.COMPONENT, turn);
        String issued2 = d.applySubId(ChatIdDispatcher.IdType.COMPONENT, turn);
        org.junit.Assert.assertEquals("认领后必须从 C4 继续，不能重发 C1", turn + "-C4", issued1);
        org.junit.Assert.assertEquals(turn + "-C5", issued2);
        for (String existing : Arrays.asList(c1, c2, c3)) {
            org.junit.Assert.assertNotEquals(existing, issued1);
            org.junit.Assert.assertNotEquals(existing, issued2);
        }
        // 思考轮桶同样被认领过（K2）→ 补发从 K3 起，不会撞历史里的 K1/K2
        org.junit.Assert.assertEquals(turn + "-K3",
                d.applySubId(ChatIdDispatcher.IdType.THINKING, turn));
    }

    /** 认领只前进不后退，且脏 id 忽略——序号是从落盘数据派生的，不能被旧值压低。 */
    @Test
    public void adoptMovesForwardOnlyAndIgnoresDirtyIds() {
        ChatIdDispatcher d = ChatIdDispatcher.getInstance();
        String turn = d.applyMasterId();

        org.junit.Assert.assertTrue(d.adoptId(turn + "-C9"));
        org.junit.Assert.assertEquals(turn + "-C10",
                d.applySubId(ChatIdDispatcher.IdType.COMPONENT, turn));
        org.junit.Assert.assertTrue(d.adoptId(turn + "-C2"));   // 比现状小：忽略
        org.junit.Assert.assertEquals(turn + "-C11",
                d.applySubId(ChatIdDispatcher.IdType.COMPONENT, turn));

        org.junit.Assert.assertFalse("null 不是合法 id", d.adoptId(null));
        org.junit.Assert.assertFalse(d.adoptId(""));
        org.junit.Assert.assertFalse("非 T<数字>-<类型码><数字> 形态忽略", d.adoptId("cmp-legacy:ord0"));
        org.junit.Assert.assertFalse(d.adoptId("T7-XYZ"));
        org.junit.Assert.assertFalse(d.adoptId("abc"));
        org.junit.Assert.assertFalse("主 id 自己没有类型码，不算子 id", d.adoptId(turn));
    }

    // ---- 2026-10-28：清空 → 新建 → 从回收站恢复，不能重发子 id ------------------

    /**
     * 清空全部历史（会话进回收站）之后，旧回合并没有消失——恢复回来时消息上还带着
     * {@code Tn-C1…}。旧实现 {@code reset()} 会清空子桶，于是清空之后任何针对旧回合的要号
     * （迟到的异步回调、恢复后的补发）都从 {@code C1} 重来，与磁盘上的 {@code Tn-C1} 撞车。
     */
    @Test
    public void resetKeepsSubBucketsSoRestoredTurnsAreNotReissued() {
        ChatIdDispatcher d = ChatIdDispatcher.getInstance();
        String turn = d.applyMasterId();
        String c1 = d.applySubId(ChatIdDispatcher.IdType.COMPONENT, turn);
        String c2 = d.applySubId(ChatIdDispatcher.IdType.COMPONENT, turn);
        org.junit.Assert.assertEquals(turn + "-C1", c1);
        org.junit.Assert.assertEquals(turn + "-C2", c2);

        d.reset();   // 清空全部历史：主 id 与子桶都不能回收

        // 恢复回来的旧回合再要号（补发/迟到回调），必须接着 C3 走，不能重发 C1/C2
        String afterRestore = d.applySubId(ChatIdDispatcher.IdType.COMPONENT, turn);
        org.junit.Assert.assertEquals(turn + "-C3", afterRestore);
        org.junit.Assert.assertNotEquals(c1, afterRestore);
        org.junit.Assert.assertNotEquals(c2, afterRestore);
    }

    /**
     * 用户给的完整序列：清空 → 新建一条对话 → 再从回收站恢复。
     * 新对话拿新主 id，恢复的旧回合沿用原 id，两边都不许重号。
     */
    @Test
    public void clearThenNewConversationThenRestoreNeverRepeatsIds() {
        ChatIdDispatcher d = ChatIdDispatcher.getInstance();

        // 旧会话：T(n)、一个组件
        String oldTurn = d.applyMasterId();
        String oldComponent = d.applySubId(ChatIdDispatcher.IdType.COMPONENT, oldTurn);

        d.reset();                                    // 清空所有历史（旧会话进回收站）

        String newTurn = d.applyMasterId();           // 新建对话并发言
        String newComponent = d.applySubId(ChatIdDispatcher.IdType.COMPONENT, newTurn);
        org.junit.Assert.assertNotEquals("新会话不能拿到旧会话的 turnId", oldTurn, newTurn);
        org.junit.Assert.assertNotEquals(oldComponent, newComponent);

        // 从回收站恢复旧会话：恢复出口会把旧 message/sub id 认领回来
        d.seedFromTurnIds(Arrays.asList(oldTurn, newTurn));
        d.adoptIds(Arrays.asList(oldComponent));

        String reissuedOld = d.applySubId(ChatIdDispatcher.IdType.COMPONENT, oldTurn);
        org.junit.Assert.assertNotEquals("恢复后旧回合不能重发已有组件 id", oldComponent, reissuedOld);
        org.junit.Assert.assertEquals(oldTurn + "-C2", reissuedOld);

        String nextTurn = d.applyMasterId();
        org.junit.Assert.assertNotEquals(oldTurn, nextTurn);
        org.junit.Assert.assertNotEquals(newTurn, nextTurn);
    }
}
