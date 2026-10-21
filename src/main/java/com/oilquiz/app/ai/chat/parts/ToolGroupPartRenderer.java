package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.chat.component.ComponentLifecycle;
import com.oilquiz.app.ai.chat.component.ComponentRegistry;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;
import com.oilquiz.app.ai.chat.turn.AssistantTurn.Part;

import java.util.List;

/**
 * 工具组折叠卡（parts §11.1）：把连续多工具渲成一行"已运行 N 个工具"，点击展开列每个工具卡。
 *
 * <p>成员卡复用 {@link ComponentRegistry}（{@code tool_call} 组件 → ToolCallCardView），
 * 与单工具卡同一渲染源；展开态走 {@link PartHost#toolGroupExpanded}/{@code toggleToolGroup}
 * （宿主存于 {@code ChatMessage}）。数据来自 {@link Part#group}（每个成员已并入 result）。</p>
 */
public final class ToolGroupPartRenderer implements PartRenderer {

    private final ToolCallPartRenderer.ComponentFactory factory;

    public ToolGroupPartRenderer() {
        this((ctx, data) -> ComponentRegistry.getInstance().render(ctx, data));
    }

    ToolGroupPartRenderer(ToolCallPartRenderer.ComponentFactory factory) {
        this.factory = factory;
    }

    @Override
    public boolean canRender(Part part) {
        return part != null && Part.TYPE_TOOL_GROUP.equals(part.type);
    }

    @Override
    public View createView(PartHost host, ViewGroup parent) {
        Context ctx = host.context();
        int pad = dp(ctx, 6);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView header = new TextView(ctx);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        header.setPadding(pad, pad, pad, pad);
        header.setClickable(true);
        header.setFocusable(true);
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);

        root.addView(header);
        root.addView(body);
        root.setTag(new Views(header, body));
        return root;
    }

    @Override
    public void bindView(View view, Part part, PartHost host) {
        Views v = view instanceof LinearLayout ? (Views) ((LinearLayout) view).getTag() : null;
        List<Part> members = part.group;
        if (v == null || members == null || members.isEmpty()) {
            return;
        }
        Context ctx = host.context();
        final String groupId = part.id;

        boolean anyRunning = false;
        for (Part m : members) {
            if (m != null && "running".equals(ToolCallPartRenderer.ToolCardProps
                    .normalizeStatus(m.status))) {
                anyRunning = true;
                break;
            }
        }
        int n = members.size();
        v.header.setText(anyRunning
                ? ("⏳ " + n + " 个工具执行中")
                : ("🔧 已运行 " + n + " 个工具"));

        // 成员卡（复用 registry），折叠也预建，展开即时。
        releaseChildren(v.body);
        for (Part m : members) {
            ComponentData cd = ComponentData.of(ToolCallPartRenderer.ToolCardProps.TYPE,
                    ToolCallPartRenderer.ToolCardProps.fromCall(m));
            View card = factory.create(ctx, cd);
            if (card != null) {
                v.body.addView(card);
            }
        }

        final PartHost h = host;
        v.body.setVisibility(h.toolGroupExpanded(groupId) ? View.VISIBLE : View.GONE);
        v.header.setOnClickListener(x -> {
            h.toggleToolGroup(groupId);
            v.body.setVisibility(h.toolGroupExpanded(groupId) ? View.VISIBLE : View.GONE);
            h.invalidateSize();
        });
    }

    @Override
    public void releaseView(View view) {
        if (view instanceof LinearLayout) {
            Views v = (Views) ((LinearLayout) view).getTag();
            if (v != null) {
                releaseChildren(v.body);
            }
        }
    }

    private static void releaseChildren(ViewGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            ComponentLifecycle.release(group.getChildAt(i));
        }
        group.removeAllViews();
    }

    private static int dp(Context ctx, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    private static final class Views {
        final TextView header;
        final LinearLayout body;
        Views(TextView header, LinearLayout body) {
            this.header = header;
            this.body = body;
        }
    }
}
