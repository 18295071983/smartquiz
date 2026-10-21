package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.chat.component.ComponentDisplay;
import com.oilquiz.app.ai.chat.component.ComponentLifecycle;
import com.oilquiz.app.ai.chat.component.ComponentRegistry;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;

/**
 * 产物/数据 part 渲染器（parts P2.7 + P3c 档位对齐，2026-10-21）。
 *
 * <p>复用 {@link ComponentRegistry#render}，并按 {@link ComponentDisplay} 的档位对齐 legacy 呈现：</p>
 * <ul>
 *   <li>{@code HIDDEN}：不挂载（卡片数据仍在消息里，可复盘）；</li>
 *   <li>{@code PEEK}：一行"▸ 标题·关键值"摘要，点击就地展开/收起（展开态记在
 *       {@link ChatMessage#isComponentBodyExpanded}，按 part.id 为 key）；</li>
 *   <li>{@code INLINE}：整卡。</li>
 * </ul>
 *
 * <p>档位判断与摘要文案都走 {@code ComponentDisplay} 公共 API，与 legacy {@code mountRoundComponent}
 * 同口径；本类不改动 legacy 挂载代码，故 flag=false 现网呈现不受影响。</p>
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
        ViewGroup frame = (ViewGroup) view;
        releaseChildren(frame);
        Context ctx = host.context();
        ComponentData comp = part.data;

        ComponentDisplay.Mode mode = ComponentDisplay.resolve(ctx, comp);
        if (!ComponentDisplay.mounts(mode)) {
            return; // HIDDEN：不挂载
        }
        View body = factory.create(ctx, comp);
        if (body == null) {
            return;
        }
        if (mode == ComponentDisplay.Mode.PEEK) {
            frame.addView(buildPeek(ctx, host, part, body));
        } else {
            frame.addView(body); // INLINE
        }
    }

    /** PEEK 摘要行 + 可展开卡体（对齐 ChatAdapter.wrapProductPeek 的行为，复用 ComponentDisplay 文案）。 */
    private View buildPeek(Context ctx, PartHost host, AssistantTurn.Part part, View body) {
        ChatMessage msg = host.message();
        final String key = part.id; // 稳定唯一，作展开态 key
        boolean startExpanded = msg != null && msg.isComponentBodyExpanded(key);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        final TextView row = new TextView(ctx);
        row.setTextSize(12f);
        row.setClickable(true);
        row.setFocusable(true);
        row.setText(peekLabel(part.data, startExpanded));
        body.setVisibility(startExpanded ? View.VISIBLE : View.GONE);

        row.setOnClickListener(v -> {
            boolean expanded = msg != null
                    ? msg.toggleComponentBodyExpanded(key)
                    : body.getVisibility() != View.VISIBLE;
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            row.setText(peekLabel(part.data, expanded));
            host.invalidateSize();
        });

        box.addView(row);
        box.addView(body);
        return box;
    }

    private static String peekLabel(ComponentData comp, boolean expanded) {
        return (expanded ? "▾ " : "▸ ") + ComponentDisplay.peekLabel(comp);
    }

    @Override
    public void releaseView(View view) {
        if (view instanceof ViewGroup) {
            releaseChildren((ViewGroup) view);
        }
    }

    /** 释放并清空容器子树（深度释放嵌套 WebView 等）。 */
    private static void releaseChildren(ViewGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            ComponentLifecycle.release(group.getChildAt(i));
        }
        group.removeAllViews();
    }
}
