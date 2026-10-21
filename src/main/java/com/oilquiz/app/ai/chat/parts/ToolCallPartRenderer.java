package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

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
    /** 所属产物卡（§11.3）的渲染工厂；默认走 {@link ComponentRegistry}，与工具卡同源。 */
    private final ComponentFactory productFactory;

    public ToolCallPartRenderer() {
        this((ctx, data) -> ComponentRegistry.getInstance().render(ctx, data));
    }

    ToolCallPartRenderer(ComponentFactory factory) {
        this(factory, (ctx, data) -> ComponentRegistry.getInstance().render(ctx, data));
    }

    ToolCallPartRenderer(ComponentFactory factory, ComponentFactory productFactory) {
        this.factory = factory;
        this.productFactory = productFactory;
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
        // 竖排宿主：[工具卡] 在上，其所属产物卡依次在下（§11.3）。单写者——本 part 独占整棵子树，
        // 不靠 attached-companion 往回写，规避"工具卡重绑 removeAllViews 抹掉产物"的双写 bug。
        LinearLayout column = new LinearLayout(host.context());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return column;
    }

    @Override
    public void bindView(View view, AssistantTurn.Part part, PartHost host) {
        if (!(view instanceof ViewGroup)) {
            return;
        }
        // 增量合并进 anchor 视图已有的 props，而不是从 call 部分整个重建：
        // 结果回来后工具状态再 running→success 触发本 part 重绑时，保留已合并的 result。
        JSONObject props = view.getTag() instanceof JSONObject
                ? (JSONObject) view.getTag()
                : new JSONObject();
        ToolCardProps.mergeCall(props, part);
        view.setTag(props);
        ViewGroup group = (ViewGroup) view;
        ProductCards.releaseAll(group); // 先深度释放旧子树（含产物 WebView）
        View card = factory.create(host.context(), ComponentData.of(ToolCardProps.TYPE, props));
        if (card != null) {
            group.addView(card);
        }
        // 所属产物（§11.3）：挂在工具卡下方，与工具同进同出。
        ProductCards.mountAll(productFactory, group, part.products,
                part.id + ":prod:", host);
    }

    @Override
    public void releaseView(View view) {
        if (view instanceof ViewGroup) {
            ProductCards.releaseAll((ViewGroup) view);
        }
    }

    /** tool_call / tool_result 共用的 props 组装（字段口径与 ChatAdapter 组件一致）。 */
    static final class ToolCardProps {
        static final String TYPE = "tool_call";

        /**
         * 词表归一：parts/fromMessage 的 status 与内联工具卡（{@code ToolCallCardView}）认的
         * {@code running/success/failed} 不是同一套词——快照兜底路径写的是 {@code "done"}，直接喂
         * 卡片会落到 default 分支显示"⏳ 执行中"。这里在 renderer 边界收敛成卡片词表，不改共享卡。
         */
        static String normalizeStatus(String raw) {
            if (raw == null) {
                return "running";
            }
            String s = raw.trim().toLowerCase(java.util.Locale.US);
            switch (s) {
                case "success":
                case "done":
                case "completed":
                case "complete":
                case "ok":
                    return "success";
                case "failed":
                case "fail":
                case "error":
                    return "failed";
                case "running":
                case "pending":
                case "started":
                case "in_progress":
                case "progress":
                    return "running";
                default:
                    return s.isEmpty() ? "running" : s; // 未知词原样透传，卡片 default 仍归"执行中"
            }
        }

        static JSONObject fromCall(AssistantTurn.Part call) {
            JSONObject o = new JSONObject();
            try {
                o.put("toolCallId", nz(call.toolCallId));
                o.put("toolName", nz(call.toolName));
                o.put("args", nz(call.args));
                o.put("status", normalizeStatus(call.status));
                o.put("result", nz(call.result));
                o.put("durationMs", call.durationMs);
            } catch (JSONException ignore) {
            }
            return o;
        }

        /**
         * 增量合并调用字段到既有 props：更新 toolCallId/toolName/args/status，
         * 但 <b>result 只在 call 自带非空时才覆盖</b>——否则会用空的 call.result 抹掉
         * 已由 {@link ToolResultPartRenderer} 合并进去的 result（见 bindView 注释）。
         */
        static void mergeCall(JSONObject props, AssistantTurn.Part call) {
            try {
                props.put("toolCallId", nz(call.toolCallId));
                props.put("toolName", nz(call.toolName));
                props.put("args", nz(call.args));
                props.put("status", normalizeStatus(call.status));
                if (call.durationMs > 0L) {
                    props.put("durationMs", call.durationMs);
                }
                if (call.result != null && !call.result.isEmpty()) {
                    props.put("result", call.result);
                }
            } catch (JSONException ignore) {
            }
        }

        /** 把 result/status 合进已有 props（tool_result 挂回工具卡时用）。 */
        static void mergeResult(JSONObject props, AssistantTurn.Part result) {
            try {
                if (result.result != null) props.put("result", result.result);
                if (result.status != null) props.put("status", normalizeStatus(result.status));
                if (props.optString("toolName").isEmpty()) props.put("toolName", nz(result.toolName));
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
