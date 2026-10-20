package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;

import com.oilquiz.app.ai.chat.parts.ToolCallPartRenderer.ToolCardProps;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 工具 status 词表归一（parts P2.5）。纯逻辑，org.json 在单测可用。
 *
 * <p>钉住：parts 侧的 {@code done/completed} 等喂进 renderer 后必须收敛成内联卡认的
 * {@code success/failed/running}，否则"已完成"会被 {@code ToolCallCardView} 落到 default
 * 分支显示"⏳ 执行中"。</p>
 */
public class ToolStatusNormalizationTest {

    @Test
    public void normalizeStatusMapsKnownVocabsToCardVocab() {
        assertEquals("success", ToolCardProps.normalizeStatus("done"));
        assertEquals("success", ToolCardProps.normalizeStatus("completed"));
        assertEquals("success", ToolCardProps.normalizeStatus("SUCCESS"));
        assertEquals("failed", ToolCardProps.normalizeStatus("error"));
        assertEquals("failed", ToolCardProps.normalizeStatus("failed"));
        assertEquals("running", ToolCardProps.normalizeStatus("pending"));
        assertEquals("running", ToolCardProps.normalizeStatus("RUNNING"));
        assertEquals("running", ToolCardProps.normalizeStatus(null));
        assertEquals("running", ToolCardProps.normalizeStatus(""));
        // 未知词原样透传（卡片 default 仍归执行中），保守不误判为成功
        assertEquals("cancelled", ToolCardProps.normalizeStatus("cancelled"));
    }

    @Test
    public void fromCallNormalizesSnapshotDoneStatus() {
        // fromMessage 的快照兜底路径把 status 写成 "done"
        AssistantTurn.Part call = AssistantTurn.Part.toolCall(
                "R1:tool:c1", "c1", "weather", "{}", "done", 1L);
        JSONObject props = ToolCardProps.fromCall(call);
        assertEquals("done 必须归一成卡片认的 success", "success", props.optString("status"));
        assertEquals("weather", props.optString("toolName"));
    }

    @Test
    public void mergeResultCarriesResultAndNormalizedStatus() {
        JSONObject props = new JSONObject();
        AssistantTurn.Part result = new AssistantTurn.Part();
        result.type = AssistantTurn.Part.TYPE_TOOL_RESULT;
        result.toolCallId = "c1";
        result.status = "completed";
        result.result = "晴天 25度";
        ToolCardProps.mergeResult(props, result);
        assertEquals("completed", "success", props.optString("status"));
        assertEquals("晴天 25度", props.optString("result"));
    }
}
