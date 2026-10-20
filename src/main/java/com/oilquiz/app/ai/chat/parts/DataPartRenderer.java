package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.chat.component.ComponentRegistry;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;

/**
 * 产物/数据 part 渲染器（parts P2.7，2026-10-21）。
 *
 * <p>完全复用 {@link ComponentRegistry#render}——图表/表格/JSON/文件等卡片的真实渲染与
 * display 档位都在既有组件体系里，本类只把 {@code part.data}（引用 {@code ChatMessage.components}）
 * 交给注册表出卡，挂进宿主 FrameLayout。对齐 AI SDK 的 {@code data-*} part 家族。</p>
 *
 * <p>说明：§4.6 里"产物挂在所属工具卡下"的归属细化、以及 PEEK/HIDDEN 折叠档位（依赖
 * {@code ComponentDisplay}）在 P3 接线时按宿主口径补；本轮先保证 data part 能独立成块渲染。</p>
 */
public final class DataPartRenderer implements PartRenderer {

    private final ToolCallPartRenderer.ComponentFactory factory;

    public DataPartRenderer() {
        this((ctx, data) -> ComponentRegistry.getInstance().render(ctx, data));
    }

    DataPartRenderer(ToolCallPartRenderer.ComponentFactory factory) {
        this.factory = factory;
    }

    @Override
    public boolean canRender(AssistantTurn.Part part) {
        return part != null && AssistantTurn.Part.TYPE_DATA.equals(part.type);
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
        if (!(view instanceof ViewGroup) || part.data == null) {
            return;
        }
        Context ctx = host.context();
        View card = factory.create(ctx, part.data);
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
}
