package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.oilquiz.app.ai.chat.turn.AssistantTurn;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;

/**
 * WebView 面板载荷（PoC，2026-11-18）：同一份 part 必须产出与原生头一致的文案 + 可渲染的 steps。
 */
@RunWith(RobolectricTestRunner.class)
public class AgentPanelPayloadTest {

    private Context ctx() {
        return com.oilquiz.app.testsupport.ThemedAppContext.get();
    }

    private static AssistantTurn.Part reasoning(String id, String text, long ms, boolean streaming) {
        AssistantTurn.Part p = AssistantTurn.Part.reasoning(id, text, 1L);
        p.durationMs = ms;
        if (streaming) {
            p.state = AssistantTurn.Part.STATE_STREAMING;
        }
        return p;
    }

    private static AssistantTurn.Part tools(String id, String... statuses) {
        java.util.List<AssistantTurn.Part> calls = new java.util.ArrayList<>();
        int i = 0;
        for (String s : statuses) {
            calls.add(AssistantTurn.Part.toolCall("t" + i, "c" + i, "search",
                    "{\"q\":\"onDraw\"}", s, 2L));
            i++;
        }
        return AssistantTurn.Part.toolGroup(id, calls, 2L);
    }

    @Test
    public void donePayloadCarriesLabelAndOrderedSteps() throws Exception {
        JSONObject p = AgentPanelPayload.of(ctx(), Arrays.asList(
                reasoning("r1", "先读题", 3_200L, false),
                tools("g1", "success", "success")), false);

        assertFalse(p.getBoolean("active"));
        assertEquals("深度思考 3 秒", p.getString("label"));
        assertTrue("收起态副标题应带最后一步：" + p.optString("summary"),
                p.optString("summary").contains("最后："));

        JSONArray steps = p.getJSONArray("steps");
        assertEquals("1 段思考 + 2 个工具行", 3, steps.length());
        assertEquals("thinking", steps.getJSONObject(0).getString("kind"));
        assertEquals("先读题", steps.getJSONObject(0).getString("text"));
        JSONObject tool = steps.getJSONObject(1);
        assertEquals("tool", tool.getString("kind"));
        assertEquals("success", tool.getString("status"));
        assertTrue("工具行要带人话参数摘要：" + tool.getString("detail"),
                tool.getString("detail").contains("onDraw"));
    }

    @Test
    public void runningPayloadIsActiveAndCarriesTheme() throws Exception {
        JSONObject p = AgentPanelPayload.of(ctx(), Collections.singletonList(
                tools("g1", "running")), true);

        assertTrue("工具在跑 → 面板保持展开", p.getBoolean("active"));
        assertTrue("文案应是工具短语或运行中：" + p.getString("label"),
                p.getString("label").contains("运行") || p.getString("label").contains("执行中"));
        JSONObject theme = p.getJSONObject("theme");
        for (String key : new String[]{"fg", "muted", "accent", "border", "surface"}) {
            assertTrue("主题变量缺 " + key, theme.has(key));
        }
        assertTrue("accent 应是 #RRGGBB", theme.getString("accent").startsWith("#"));
        assertTrue("surface 应是 rgba", theme.getString("surface").startsWith("rgba("));
    }
}
