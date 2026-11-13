package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.ChatIdDispatcher;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * B6（2026-11-13）：DSH 镜像气泡的 id 必须与 subId 一致，且由 turnId 派生。
 *
 * <p>以前是 {@code UUID} 当 id、{@code subId} 指回自己 —— 与 turnId 脱节，
 * 按 id 聚合/恢复时会归错组。断言用独立派发器实例，不污染全局计数器。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class DshMirrorIdsTest {

    @Test
    public void subIdIsDerivedFromTurnId() {
        ChatIdDispatcher d = ChatIdDispatcher.getInstance();
        DshMirrorIds.Ids ids = DshMirrorIds.allocate(d);

        assertTrue("turnId 应形如 T{n}：" + ids.turnId, ids.turnId.matches("T\\d+"));
        assertTrue("subId 应形如 T{n}-A{k}：" + ids.subId,
                ids.subId.startsWith(ids.turnId + "-A"));
        assertNotEquals("id 不能等于 turnId（否则消息与回合同号）", ids.turnId, ids.subId);
    }

    @Test
    public void twoBubblesNeverShareIds() {
        ChatIdDispatcher d = ChatIdDispatcher.getInstance();
        DshMirrorIds.Ids a = DshMirrorIds.allocate(d);
        DshMirrorIds.Ids b = DshMirrorIds.allocate(d);

        assertNotEquals("两个气泡不能共用 turnId", a.turnId, b.turnId);
        assertNotEquals("两个气泡不能共用 subId", a.subId, b.subId);
        assertTrue("第二个气泡同样要满足 T{n}-A{k}：" + b.subId,
                b.subId.startsWith(b.turnId + "-A"));
    }
}
