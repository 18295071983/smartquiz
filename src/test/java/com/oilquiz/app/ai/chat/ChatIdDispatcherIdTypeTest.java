package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * id 派发器的类型表护栏（2026-11-14）。
 *
 * <p>背景：DSH 转换器要按类型申请 id（Tn-G1 分段消息、Tn-P1 卡片…），类型编码一旦撞车，
 * 两种消息就会拿到同一个 id —— {@code setHasStableIds(true)} 下 RecyclerView 直接把内容串行。
 * 这里把"编码唯一、格式统一、预分配不重号"钉死，以后加类型忘了改字母会立刻红。</p>
 */
public class ChatIdDispatcherIdTypeTest {

    @Test
    public void typeCodesAreUnique() {
        Set<String> codes = new HashSet<>();
        for (ChatIdDispatcher.IdType t : ChatIdDispatcher.IdType.values()) {
            assertTrue("类型编码不能为空: " + t.name(), t.code != null && t.code.length() == 1);
            assertTrue("类型编码重复: " + t.code + " (" + t.name() + ")",
                    codes.add(t.code));
        }
    }

    @Test
    public void greekCodesAreSingleCharAndWorkWithAdoptId() {
        java.util.List<ChatIdDispatcher.IdType> greek = new java.util.ArrayList<>();
        for (ChatIdDispatcher.IdType t : ChatIdDispatcher.IdType.values()) {
            if (t.code != null && t.code.length() == 1 && t.code.charAt(0) >= 0x03B1
                    && t.code.charAt(0) <= 0x03C9) {
                greek.add(t);
            }
        }
        assertTrue("应该有一批希腊字母预留类型", greek.size() >= 10);
        ChatIdDispatcher d = ChatIdDispatcher.getInstance();
        String turn = "T" + System.nanoTime();
        for (ChatIdDispatcher.IdType t : greek) {
            String id = d.applySubId(t, turn);
            assertTrue("希腊码 id 形态: " + id, id.startsWith(turn + "-" + t.code));
            assertTrue("adoptId 要能认领希腊码 id: " + id, d.adoptId(id));
        }
    }

    @Test
    public void dshIdsAdoptedAfterRestartKeepCounting() {
        // 模拟"杀进程重进"：子桶计数器从 1 起，先认领落盘里出现过的 DSH id，
        // 再发号就必须接着往后走 —— 否则新段 T12-G1 会和历史里的 T12-G1 撞车。
        ChatIdDispatcher d = ChatIdDispatcher.getInstance();
        String turn = "T" + System.nanoTime();
        assertTrue(d.adoptId(turn + "-" + ChatIdDispatcher.IdType.DSH_SEGMENT.code + "3"));
        assertTrue(d.adoptId(turn + "-" + ChatIdDispatcher.IdType.DSH_CARD.code + "2"));
        assertTrue(d.adoptId(turn + "-" + ChatIdDispatcher.IdType.DSH_TOOLCALL.code + "5"));
        String seg = d.nextDshSegmentId(turn);
        assertTrue("认领后分段号要接着走: " + seg,
                seg.equals(turn + "-" + ChatIdDispatcher.IdType.DSH_SEGMENT.code + "4"));
        String card = d.applySubId(ChatIdDispatcher.IdType.DSH_CARD, turn);
        assertTrue("认领后卡片号要接着走: " + card,
                card.equals(turn + "-" + ChatIdDispatcher.IdType.DSH_CARD.code + "3"));
        String tool = d.applySubId(ChatIdDispatcher.IdType.DSH_TOOLCALL, turn);
        assertTrue("认领后工具号要接着走: " + tool,
                tool.equals(turn + "-" + ChatIdDispatcher.IdType.DSH_TOOLCALL.code + "6"));
    }

    @Test
    public void reservedPoolIsUsableAndCounting() {
        assertTrue("字库要够用（>=90）", ChatIdDispatcher.codeCapacity() >= 90);
        ChatIdDispatcher d = ChatIdDispatcher.getInstance();
        String turn = "T" + System.nanoTime();
        int checked = 0;
        for (int i = 0; i < ChatIdDispatcher.RESERVED_CODE_POOL.length() && checked < 40; i += 3) {
            char c = ChatIdDispatcher.RESERVED_CODE_POOL.charAt(i);
            assertTrue("字库码不该已被占用: " + c, ChatIdDispatcher.isCodeAvailable(c));
            String id = turn + "-" + c + "1";
            assertTrue("adoptId 要能认领字库码 id: " + id, d.adoptId(id));
            checked += 1;
        }
        assertEquals(ChatIdDispatcher.RESERVED_CODE_POOL.length(),
                ChatIdDispatcher.codeCapacity() + countUsedPoolCodes());
    }

    /** 字库里已被枚举占用的个数（正常是 0） */
    private static int countUsedPoolCodes() {
        int used = 0;
        for (int i = 0; i < ChatIdDispatcher.RESERVED_CODE_POOL.length(); i += 1) {
            char c = ChatIdDispatcher.RESERVED_CODE_POOL.charAt(i);
            if (!ChatIdDispatcher.isCodeAvailable(c)) used += 1;
        }
        return used;
    }

    @Test
    public void dshBucketsDoNotCollideWithLocalOnes() {
        Set<String> local = new HashSet<>();
        local.add(ChatIdDispatcher.IdType.USER.code);
        local.add(ChatIdDispatcher.IdType.AI.code);
        local.add(ChatIdDispatcher.IdType.THINKING.code);
        local.add(ChatIdDispatcher.IdType.TOOL.code);
        local.add(ChatIdDispatcher.IdType.COMPONENT.code);
        local.add(ChatIdDispatcher.IdType.SYSTEM.code);
        for (ChatIdDispatcher.IdType t : ChatIdDispatcher.IdType.values()) {
            if (!t.name().startsWith("DSH_")) continue;
            assertTrue("DSH 类型不能占用本地编码: " + t.name(),
                    !local.contains(t.code));
        }
    }

    @Test
    public void subIdFormatIsStable() {
        ChatIdDispatcher d = ChatIdDispatcher.getInstance();
        String turn = "T" + System.nanoTime();
        String a = d.applySubId(ChatIdDispatcher.IdType.DSH_SEGMENT, turn);
        String b = d.applySubId(ChatIdDispatcher.IdType.DSH_SEGMENT, turn);
        String card = d.applySubId(ChatIdDispatcher.IdType.DSH_CARD, turn);
        assertTrue(a.startsWith(turn + "-" + ChatIdDispatcher.IdType.DSH_SEGMENT.code));
        assertTrue(b.startsWith(turn + "-" + ChatIdDispatcher.IdType.DSH_SEGMENT.code));
        assertTrue(card.startsWith(turn + "-" + ChatIdDispatcher.IdType.DSH_CARD.code));
        assertNotEquals(a, b);
    }

    @Test
    public void missingTurnFallsBackToUuid() {
        ChatIdDispatcher d = ChatIdDispatcher.getInstance();
        String id = d.nextDshSegmentId(null);
        assertTrue("turnId 缺失要退回 UUID，不能返回 null", id != null && id.length() > 20);
        String other = d.nextDshSegmentId("");
        assertNotEquals(id, other);
    }
}
