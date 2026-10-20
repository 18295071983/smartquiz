package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.oilquiz.app.ai.chat.component.ComponentData;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * contentRoundBounds 的不变量（2026-10-21）：
 * 数量 = tool_call 数、单调不减、落在正文范围、toolCallId 等长；
 * 以及正文 strip/trim 后的偏移重映射。
 */
public class RoundBoundsTest {

    @Test
    public void padsCountAndFillsToolIds() throws Exception {
        ChatMessage m = new ChatMessage.Builder(ChatMessage.MessageType.AI).build();
        m.content = "正文";
        m.components = new ArrayList<>();
        m.components.add(tool("tc0"));
        m.components.add(tool("tc1"));
        m.components.add(tool("tc2"));
        m.contentRoundBounds = new ArrayList<>(Arrays.asList(0));

        RoundBounds.normalize(m);

        assertEquals(3, m.contentRoundBounds.size());
        assertEquals(3, m.contentRoundToolIds.size());
        assertEquals(Arrays.asList(0, 0, 0), m.contentRoundBounds);
        assertEquals(Arrays.asList("tc0", "tc1", "tc2"), m.contentRoundToolIds);
    }

    @Test
    public void clampsAndMakesMonotonic() throws Exception {
        ChatMessage m = new ChatMessage.Builder(ChatMessage.MessageType.AI).build();
        m.content = "12345678901234567890"; // 20
        m.components = new ArrayList<>();
        m.components.add(tool("tc0"));
        m.components.add(tool("tc1"));
        m.components.add(tool("tc2"));
        m.contentRoundBounds = new ArrayList<>(Arrays.asList(10, 5, 99));

        RoundBounds.normalize(m);

        assertEquals(Arrays.asList(10, 10, 20), m.contentRoundBounds);
    }

    @Test
    public void trimsExtraBoundsToToolCount() throws Exception {
        ChatMessage m = new ChatMessage.Builder(ChatMessage.MessageType.AI).build();
        m.content = "正文";
        m.components = new ArrayList<>();
        m.components.add(tool("tc0"));
        m.components.add(tool("tc1"));
        m.contentRoundBounds = new ArrayList<>(Arrays.asList(0, 1, 2, 3));

        RoundBounds.normalize(m);

        assertEquals(2, m.contentRoundBounds.size());
        assertEquals(2, m.contentRoundToolIds.size());
    }

    @Test
    public void remapsAfterThinkingStripAndLeadingTrim() {
        List<Integer> bounds = new ArrayList<>(Arrays.asList(0, 5, 20));
        // 删除旧内容 [5,10)，删除后整体前导 trim 掉 2 个空白
        List<Integer> out = RoundBounds.remapAfterRemoval(bounds, 5, 10, 2, 15);
        assertNotNull(out);
        assertEquals(Arrays.asList(0, 3, 13), out);
    }

    private static ComponentData tool(String id) throws Exception {
        JSONObject props = new JSONObject();
        props.put("toolCallId", id);
        props.put("toolName", "ui_component");
        props.put("status", "done");
        return ComponentData.of("tool_call", props);
    }
}
