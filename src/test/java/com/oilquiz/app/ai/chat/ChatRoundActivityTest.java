package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.component.ComponentData;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 每轮活动区分组（2026-10-21）。
 *
 * <p>钉住：思考/工具按 TOOL 切成"每轮一组"，组的顺序与 contentRoundBounds 对齐；
 * 末尾只有思考的那一轮不能被丢掉。分组错了，逐轮渲染就会把工具挂到别的正文段上。</p>
 */
public class ChatRoundActivityTest {

    @Test
    public void groupsSplitAtToolAndKeepTrailingThinking() throws Exception {
        ChatMessage m = new ChatMessage.Builder(ChatMessage.MessageType.AI).build();
        m.thinkingRounds = new ArrayList<>(Arrays.asList("think-0", "think-1", "think-2"));
        m.contentRoundBounds = new ArrayList<>(Arrays.asList(0, 10));
        m.components = new ArrayList<>();
        m.components.add(tool("tc0"));
        m.components.add(tool("tc1"));

        List<ChatAdapter.ActivityGroup> groups = ChatAdapter.buildActivityGroups(m);
        assertEquals(3, groups.size());

        assertEquals(1, groups.get(0).thinks.size());
        assertEquals("think-0", groups.get(0).thinks.get(0).body);
        assertEquals(1, groups.get(0).tools.size());

        assertEquals(1, groups.get(1).thinks.size());
        assertEquals("think-1", groups.get(1).thinks.get(0).body);
        assertEquals(1, groups.get(1).tools.size());

        // 最后一轮只有思考，没有后续工具
        assertEquals(1, groups.get(2).thinks.size());
        assertEquals("think-2", groups.get(2).thinks.get(0).body);
        assertTrue(groups.get(2).tools.isEmpty());
    }

    @Test
    public void singleGroupWhenNoToolBoundary() {
        ChatMessage m = new ChatMessage.Builder(ChatMessage.MessageType.AI).build();
        m.thinkingRounds = new ArrayList<>(Arrays.asList("only-think"));
        m.components = new ArrayList<>();
        List<ChatAdapter.ActivityGroup> groups = ChatAdapter.buildActivityGroups(m);
        assertNotNull(groups);
        assertEquals(1, groups.size());
        assertEquals(1, groups.get(0).thinks.size());
        assertTrue(groups.get(0).tools.isEmpty());
    }

    private static ComponentData tool(String id) throws Exception {
        JSONObject props = new JSONObject();
        props.put("toolCallId", id);
        props.put("toolName", "ui_component");
        props.put("status", "done");
        return ComponentData.of("tool_call", props);
    }
}
