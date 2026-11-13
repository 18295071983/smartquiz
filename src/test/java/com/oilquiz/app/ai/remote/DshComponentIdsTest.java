package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

/**
 * B4 / M4（2026-11-13）：动作回传不能再靠 id 前缀嗅探。
 *
 * <p>真实组件 id 是派发器发的 {@code T5-C12}/{@code T5-T3}，旧代码只看
 * {@code startsWith("dsh-")} → 所有 DSH 卡片动作都被误判成本机事件。
 * 这里钉住三件事：登记表认 id、旧前缀仍兼容、跨重启按 {@code source} 重建。</p>
 */
public class DshComponentIdsTest {

    @After
    public void tearDown() {
        DshComponentIds.clearForTest();
    }

    @Test
    public void markedIdRoutesToDsh() {
        DshComponentIds.mark("T5-C12");
        assertTrue("登记过的 id 应回传电脑端", DshComponentIds.isDshComponent("T5-C12"));
        assertFalse("没登记的不该被当成电脑端", DshComponentIds.isDshComponent("T5-C13"));
    }

    @Test
    public void legacyPrefixStillRecognized() {
        assertTrue("旧数据的 dsh- 前缀仍然认", DshComponentIds.isDshComponent("dsh-7"));
        assertFalse(DshComponentIds.isDshComponent(null));
        assertFalse(DshComponentIds.isDshComponent(""));
    }

    @Test
    public void rebuildsFromHistorySource() {
        ChatMessage m = ChatMessage.createAIMessage("T7-A1", "", 1L, null, 0, 0);
        ComponentData card = ComponentData.of("tool_call", new JSONObject());
        card.id = "T7-T1";
        card.source = DshComponentIds.SOURCE_DSH;
        ComponentData local = ComponentData.of("table_card", new JSONObject());
        local.id = "T7-C9";     // 本机卡片：不登记
        m.components = new java.util.ArrayList<>();
        m.components.add(card);
        m.components.add(local);

        DshComponentIds.markFromHistory(java.util.Collections.singletonList(m));

        assertTrue("恢复后 DSH 卡片仍应回传", DshComponentIds.isDshComponent("T7-T1"));
        assertFalse("本机卡片不得混进回传通道", DshComponentIds.isDshComponent("T7-C9"));
    }

    @Test
    public void sourceSurvivesPersistableJson() {
        ComponentData c = ComponentData.of("tool_call", new JSONObject());
        c.source = DshComponentIds.SOURCE_DSH;
        String json = c.toPersistableJson();
        assertNotNull(json);
        ComponentData back = ComponentData.fromPersistableJson(json);
        assertNotNull(back);
        assertEquals("来源必须落盘（重启后靠它重建路由）", "dsh", back.source);
    }
}
