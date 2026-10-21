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

    @Test
    public void canRenderAllRequiresEveryPartCovered() {
        PartRendererRegistry r = PartRendererRegistry.createDefault();
        java.util.List<AssistantTurn.Part> covered = java.util.Arrays.asList(
                part(AssistantTurn.Part.TYPE_TEXT, "t"),
                part(AssistantTurn.Part.TYPE_REASONING, "t"),
                AssistantTurn.Part.toolCall("x", "c1", "weather", "{}", "done", 1L),
                AssistantTurn.Part.data("d", null, 1L));
        assertTrue(r.canRenderAll(covered));

        java.util.List<AssistantTurn.Part> withGap = new java.util.ArrayList<>(covered);
        withGap.add(part("unknown_type", "t")); // 未注册
        assertFalse("有未覆盖类型 → false（宿主应回落 legacy）", r.canRenderAll(withGap));
    }

    private static AssistantTurn.Part part(String type, String text) {
        AssistantTurn.Part p = new AssistantTurn.Part();
        p.id = "id-" + type;
        p.type = type;
        p.text = text;
        return p;
    }

    private static AssistantTurn.Part tool(String type, String toolCallId) {
        AssistantTurn.Part p = new AssistantTurn.Part();
        p.id = "id-" + type + "-" + toolCallId;
        p.type = type;
        p.toolCallId = toolCallId;
        return p;
    }

    @Test
    public void defaultRegistryResolvesTextReasoningErrorAndTools() {
        PartRendererRegistry r = PartRendererRegistry.createDefault();

        assertTrue(r.resolve(part(AssistantTurn.Part.TYPE_TEXT, "t")) instanceof TextPartRenderer);
        assertTrue(r.resolve(part(AssistantTurn.Part.TYPE_REASONING, "t"))
                instanceof ReasoningPartRenderer);
        assertTrue(r.resolve(part(AssistantTurn.Part.TYPE_ERROR, "t")) instanceof ErrorPartRenderer);
        assertTrue(r.resolve(tool(AssistantTurn.Part.TYPE_TOOL_CALL, "call_1"))
                instanceof ToolCallPartRenderer);
        assertTrue(r.resolve(tool(AssistantTurn.Part.TYPE_TOOL_RESULT, "call_1"))
                instanceof ToolResultPartRenderer);
        assertTrue(r.resolve(AssistantTurn.Part.approval("a1", "c1", "p", "q", null, 1L))
                instanceof ApprovalPartRenderer);
        assertTrue(r.resolve(AssistantTurn.Part.data("d1", null, 1L)) instanceof DataPartRenderer);
    }

    @Test
    public void unresolvedTypesReturnNull() {
        PartRendererRegistry r = PartRendererRegistry.createDefault();
        // 只有真正未注册的类型解析为 null（引擎据此跳过，不静默崩）。
        assertNull(r.resolve(part("unknown_type", null)));
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
