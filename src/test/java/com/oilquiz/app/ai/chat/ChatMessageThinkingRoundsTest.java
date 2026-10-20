package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 思考轮的"每轮一行、每行独立折叠"所依赖的数据口径（2026-10-20）。
 *
 * <p>UI 侧（{@code ChatAdapter#buildThinkingRow}）不在 JVM 里测，这里钉住它的前提：
 * 三条列表（内容 / id / 耗时）严格同下标、空轮不占位、旧数据缺耗时不炸、
 * 折叠状态默认是"收起"（语义刚从"记已折叠"反转成"记已展开"，反转错了这里就会红）。</p>
 */
public class ChatMessageThinkingRoundsTest {

    private ChatMessage aiMessage() {
        ChatMessage m = ChatMessage.createAIMessage("正文");
        m.turnId = "T1";
        return m;
    }

    @Test
    public void threeListsStayIndexAligned() {
        ChatMessage m = aiMessage();
        m.addThinkingRound("第一轮想法", "T1-K1", 3200L);
        m.addThinkingRound("第二轮想法", "T1-K2", 1800L);

        assertEquals(2, m.thinkingRounds.size());
        assertEquals(2, m.thinkingRoundIds.size());
        assertEquals(2, m.thinkingRoundDurationsMs.size());
        assertEquals("T1-K2", m.thinkingRoundIds.get(1));
        assertEquals(3200L, m.thinkingRoundDurationMs(0));
        assertEquals(1800L, m.thinkingRoundDurationMs(1));
    }

    @Test
    public void blankRoundDoesNotTakeASlot() {
        ChatMessage m = aiMessage();
        m.addThinkingRound("有内容", "T1-K1", 100L);
        m.addThinkingRound("   ", "T1-K2", 200L);   // 空轮：不该占一个下标
        m.addThinkingRound(null, "T1-K3", 300L);

        assertEquals(1, m.thinkingRounds.size());
        assertEquals(1, m.thinkingRoundIds.size());
        assertEquals(1, m.thinkingRoundDurationsMs.size());
        assertEquals(100L, m.thinkingRoundDurationMs(0));
    }

    /** 旧会话文件里没有耗时字段：取值必须是 0（UI 侧 0 = 不显示秒数），且不能越界。 */
    @Test
    public void legacyDataWithoutDurationsFallsBackToZero() {
        ChatMessage m = aiMessage();
        m.addThinkingRound("旧数据一轮", "T1-K1");   // 2 参重载 = 未知耗时
        assertEquals(1, m.thinkingRounds.size());
        assertEquals(0L, m.thinkingRoundDurationMs(0));
        assertEquals("越界取值必须是 0 而不是抛异常", 0L, m.thinkingRoundDurationMs(9));
        assertEquals("负下标同理", 0L, m.thinkingRoundDurationMs(-1));

        // 恢复出来的旧消息三条列表长度不齐（历史上真出现过）：补齐口径要能撑住
        ChatMessage ragged = aiMessage();
        ragged.thinkingRounds = new java.util.ArrayList<>();
        ragged.thinkingRounds.add("a");
        ragged.thinkingRounds.add("b");
        ragged.thinkingRoundIds = new java.util.ArrayList<>();
        ragged.thinkingRoundIds.add("X1");
        ragged.addThinkingRound("c", "X3", 50L);
        assertEquals(3, ragged.thinkingRounds.size());
        assertEquals("id 列表要按轮数补到同长", 3, ragged.thinkingRoundIds.size());
        assertEquals("X1 不能被挪位", "X1", ragged.thinkingRoundIds.get(0));
        assertEquals("补出来的占位不能算成耗时", 0L, ragged.thinkingRoundDurationMs(0));
        assertEquals(50L, ragged.thinkingRoundDurationMs(2));
    }

    /** 折叠语义：默认收起（只有被点开的轮才在集合里），点两次回到收起。 */
    @Test
    public void foldStateDefaultsToCollapsedAndTogglesBack() {
        ChatMessage m = aiMessage();
        m.addThinkingRound("一轮", "T1-K1", 10L);

        assertFalse("默认必须是收起：一条 10 轮的消息全展开比统一折叠更难读",
                m.isThinkingRoundExpanded("T1-K1"));
        m.toggleThinkingRoundExpanded("T1-K1");
        assertTrue(m.isThinkingRoundExpanded("T1-K1"));
        m.toggleThinkingRoundExpanded("T1-K1");
        assertFalse(m.isThinkingRoundExpanded("T1-K1"));

        // 空 id 不记账：否则一个 null key 会把整条消息的所有无名轮一起点开
        m.toggleThinkingRoundExpanded(null);
        m.toggleThinkingRoundExpanded("");
        assertFalse(m.isThinkingRoundExpanded(null));
    }

    /** 折叠状态是运行时 UI 状态，不该被写进会话文件（与产物卡展开集同一口径）。 */
    @Test
    public void foldStateIsTransient() throws Exception {
        java.lang.reflect.Field f = ChatMessage.class.getField("thinkingExpandedRounds");
        assertTrue("thinkingExpandedRounds 必须是 transient：重启后不该复活上一世的点开状态",
                java.lang.reflect.Modifier.isTransient(f.getModifiers()));
    }
}
