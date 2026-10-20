package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.chat.component.ComponentRegistry;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 工具调用 part 渲染器（parts 架构升级 P2.5，2026-10-21）。
 *
 * <p><b>复用而非重写</b>：工具卡的真正渲染（状态色、结果限长/"查看全部"、预览 chip 等）全在
 * {@link ComponentRegistry#render} 背后的 {@code ToolCallCardView} 里。本类只做两件事——把
 * {@link AssistantTurn.Part} 的 tool 字段拼成 {@code tool_call} 的 {@link ComponentData}，
 * 再把注册表产出的卡片挂进一个宿主 FrameLayout。渲染逻辑零复制。</p>
 *
 * <p>结果归属：{@code result} 由独立的 {@code tool_result} part 携带，经
 * {@link ToolResultPartRenderer} 按 {@code toolCallId} 挂回本卡（§4.6，不建第二张卡）。
 * anchor 视图上以 tag 存本次 {@code props}，供 tool_result 合并后原地重渲染同一张卡。</p>
 */
public final class ToolCallPartRenderer implements PartRenderer {

    /** 组件渲染工厂；默认走 {@link ComponentRegistry}，单测可注入假实现验证归属而不触真卡片。 */
    public interface ComponentFactory {
        View create(Context ctx, ComponentData data);
    }

    private final ComponentFactory factory;

    public ToolCallPartRenderer() {
        this((ctx, data) -> ComponentRegistry.getInstance().render(ctx, data));
    }

    ToolCallPartRenderer(ComponentFactory factory) {
        this.factory = factory;
    }

    ComponentFactory componentFactory() {
        return factory;
    }

    @Override
    public boolean canRender(AssistantTurn.Part part) {
        return part != null && AssistantTurn.Part.TYPE_TOOL_CALL.equals(part.type);
    }

    @Override
    public View createView(PartHost host, ViewGroup parent) {
        FrameLayout frame = new FrameLayout(host.context());
        frame.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return frame;
    }

    @Override
    public void bindView(View view, AssistantTurn.Part part, PartHost host) {
        if (!(view instanceof ViewGroup)) {
            return;
        }
        JSONObject props = ToolCardProps.fromCall(part);
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
        if (view instanceof ViewGroup) {
            ((ViewGroup) view).removeAllViews();
        }
    }

    /** tool_call / tool_result 共用的 props 组装（字段口径与 ChatAdapter 组件一致）。 */
    static final class ToolCardProps {
        static final String TYPE = "tool_call";

        static JSONObject fromCall(AssistantTurn.Part call) {
            JSONObject o = new JSONObject();
            try {
                o.put("toolCallId", nz(call.toolCallId));
                o.put("toolName", nz(call.toolName));
                o.put("args", nz(call.args));
                o.put("status", nz(call.status));
                o.put("result", nz(call.result));
            } catch (JSONException ignore) {
            }
            return o;
        }

        /** 把 result/status 合进已有 props（tool_result 挂回工具卡时用）。 */
        static void mergeResult(JSONObject props, AssistantTurn.Part result) {
            try {
                if (result.result != null) props.put("result", result.result);
                if (result.status != null) props.put("status", result.status);
                if ((props.optString("toolName").isEmpty())) props.put("toolName", nz(result.toolName));
            } catch (JSONException ignore) {
            }
        }

        private static String nz(String s) {
            return s != null ? s : "";
        }

        private ToolCardProps() {
        }
    }
}
