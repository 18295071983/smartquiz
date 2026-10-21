package com.oilquiz.app.ai.chat.parts;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.chat.component.ComponentRegistry;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;

/**
 * 产物/数据 part 渲染器（parts P2.7 + P3c 档位对齐 + §11.3 复用，2026-10-21）。
 *
 * <p>渲染逻辑收敛到 {@link ProductCards}（与挂在工具卡下的产物同一实现），本类只负责给顶层
 * data part 建一个宿主 FrameLayout 并按 {@code part.id} 为 key 挂载 {@code part.data}。
 * 档位（HIDDEN/PEEK/INLINE）判断都走 {@code ComponentDisplay} 公共 API，与 legacy 同口径，
 * 不改 legacy，故 flag=false 现网呈现不受影响。</p>
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
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup frame = (ViewGroup) view;
        ProductCards.releaseAll(frame);
        if (part.data == null) {
            return;
        }
        ProductCards.mount(factory, frame, part.data, part.id, host);
    }

    @Override
    public void releaseView(View view) {
        if (view instanceof ViewGroup) {
            ProductCards.releaseAll((ViewGroup) view);
        }
    }
}
