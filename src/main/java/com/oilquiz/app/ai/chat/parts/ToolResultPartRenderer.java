package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.chat.parts.ToolCallPartRenderer.ToolCardProps;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;

import org.json.JSONObject;

import java.util.List;

/**
 * 工具结果 part 渲染器（parts 架构升级 P2.5，2026-10-21）。
 *
 * <p>§4.6 归属：结果不建独立卡，而是按 {@code toolCallId} 挂回所属 {@code tool_call} 卡，
 * 合并 result/status 后原地重渲染同一张卡（复用 {@link ToolCallPartRenderer.ComponentFactory}
 * → {@code ComponentRegistry}）。{@link #anchorIdFor} 声明它是 attached part，
 * {@link TurnPartsHost} 因此不会给它建视图，并把它的 {@code bindView} 指向 anchor 卡视图。</p>
 */
public final class ToolResultPartRenderer implements PartRenderer {

    private final ToolCallPartRenderer.ComponentFactory factory;

    public ToolResultPartRenderer(ToolCallPartRenderer.ComponentFactory factory) {
        this.factory = factory;
    }

    @Override
    public boolean canRender(AssistantTurn.Part part) {
        return part != null && AssistantTurn.Part.TYPE_TOOL_RESULT.equals(part.type);
    }

    @Override
    public String anchorIdFor(AssistantTurn.Part part, List<AssistantTurn.Part> orderedParts) {
        if (part == null || part.toolCallId == null || part.toolCallId.isEmpty() || orderedParts == null) {
            return null;
        }
        String found = null;
        for (AssistantTurn.Part p : orderedParts) {
            if (p == part) {
                break; // 只看它之前的 tool_call
            }
            if (p != null && AssistantTurn.Part.TYPE_TOOL_CALL.equals(p.type)
                    && part.toolCallId.equals(p.toolCallId)) {
                found = p.id;
            }
        }
        return found;
    }

    @Override
    public View createView(PartHost host, ViewGroup parent) {
        // attached：正常情况下不会被调用（引擎只会走 anchor 视图）。若 anchor 缺失被当独立视图，
        // 返回空视图，绝不建出第二张工具卡。
        return new View(host.context());
    }

    @Override
    public void bindView(View view, AssistantTurn.Part part, PartHost host) {
        if (!(view instanceof ViewGroup)) {
            return;
        }
        // view 即所属工具卡的宿主 FrameLayout；合并结果后重渲染同一张卡。
        JSONObject props = view.getTag() instanceof JSONObject
                ? (JSONObject) view.getTag()
                : ToolCardProps.fromCall(synthesizeCallFromResult(part));
        ToolCardProps.mergeResult(props, part);
        view.setTag(props);
        View card = factory.create(host.context(), ComponentData.of(ToolCardProps.TYPE, props));
        ViewGroup group = (ViewGroup) view;
        group.removeAllViews();
        if (card != null) {
            group.addView(card);
        }
    }

    @Override
    public void releaseView(View view) {
        // anchor 视图由 tool_call 的 renderer 负责释放；此处不重复释放。
    }

    /** anchor 视图无 tag（理论上不该发生）时，用 result part 自身的公共字段兜底建 props。 */
    private static AssistantTurn.Part synthesizeCallFromResult(AssistantTurn.Part result) {
        AssistantTurn.Part pseudo = new AssistantTurn.Part();
        pseudo.toolCallId = result.toolCallId;
        pseudo.toolName = result.toolName;
        pseudo.status = result.status;
        return pseudo;
    }
}
