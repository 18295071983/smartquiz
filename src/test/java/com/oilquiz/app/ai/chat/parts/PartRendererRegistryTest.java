package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.turn.AssistantTurn;

import org.junit.Test;

/**
 * PartRendererRegistry 按类型解析（parts 架构升级 P2）。纯逻辑，不碰 Android 视图。
 */
public class PartRendererRegistryTest {

    private static AssistantTurn.Part part(String type, String text) {
        AssistantTurn.Part p = new AssistantTurn.Part();
        p.id = "id-" + type;
        p.type = type;
        p.text = text;
        return p;
    }

    @Test
    public void defaultRegistryResolvesTextReasoningAndError() {
        PartRendererRegistry r = PartRendererRegistry.createDefault();

        assertTrue(r.resolve(part(AssistantTurn.Part.TYPE_TEXT, "t")) instanceof TextPartRenderer);
        assertTrue(r.resolve(part(AssistantTurn.Part.TYPE_REASONING, "t"))
                instanceof ReasoningPartRenderer);
        assertTrue(r.resolve(part(AssistantTurn.Part.TYPE_ERROR, "t")) instanceof ErrorPartRenderer);
    }

    @Test
    public void unresolvedTypesReturnNull() {
        PartRendererRegistry r = PartRendererRegistry.createDefault();
        // tool_call/tool_result/data 留到 P3，P2 未注册 → 解析为 null（引擎会跳过，不静默崩）。
        assertNull(r.resolve(part(AssistantTurn.Part.TYPE_TOOL_CALL, null)));
        assertNull(r.resolve(part(AssistantTurn.Part.TYPE_TOOL_RESULT, null)));
        assertNull(r.resolve(part("data", null)));
        assertNull(r.resolve(null));
    }

    @Test
    public void firstRegisteredMatchingRendererWins() {
        TextPartRenderer first = new TextPartRenderer();
        TextPartRenderer second = new TextPartRenderer();
        PartRendererRegistry r = new PartRendererRegistry().register(first).register(second);

        assertSame(first, r.resolve(part(AssistantTurn.Part.TYPE_TEXT, "t")));
    }

    @Test
    public void canRenderOnlyMatchesItsOwnType() {
        assertFalse(new TextPartRenderer().canRender(part(AssistantTurn.Part.TYPE_REASONING, "t")));
        assertTrue(new ReasoningPartRenderer().canRender(part(AssistantTurn.Part.TYPE_REASONING, "t")));
        assertFalse(new ReasoningPartRenderer().canRender(null));
    }
}
