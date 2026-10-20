package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.turn.AssistantTurn;

/**
 * 推理/思考 part 渲染器（parts 架构升级 P2，2026-10-21）。
 *
 * <p>正文复用与 text 同一条 markdown 渲染原语（{@link MarkdownRenderer#render}），外面套
 * 一个"折叠头 + 展开体"的行结构，对应现有 think line 的形状：<b>默认折叠</b>，点头展开。</p>
 *
 * <p>说明：现有 think line 的 header 文案（"已深度思考 X 秒 · N 步"）、步数聚合与逐轮展开态
 * 都长在 {@code ChatAdapter} 私有方法里，且依赖 {@code ActivityTimeline} 聚合。P2 先给出
 * 可独立运行、可单测的最小结构；P3 由 ChatAdapter 实现 {@link PartHost} 时把 header 文案与
 * 展开态透出来，替换这里的占位 header，避免与旧 think line 双份展示。</p>
 */
public final class ReasoningPartRenderer implements PartRenderer {

    @Override
    public boolean canRender(AssistantTurn.Part part) {
        return part != null && AssistantTurn.Part.TYPE_REASONING.equals(part.type);
    }

    @Override
    public View createView(PartHost host, ViewGroup parent) {
        Context ctx = host.context();
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f,
                ctx.getResources().getDisplayMetrics());

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView header = new TextView(ctx);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        header.setPadding(pad, pad, pad, pad);
        header.setText(HEADER_COLLAPSED);

        final TextView body = new TextView(ctx);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        body.setPadding(pad, 0, pad, pad);
        body.setTextIsSelectable(true);
        body.setMovementMethod(LinkMovementMethod.getInstance());
        body.setVisibility(View.GONE); // 默认折叠

        header.setOnClickListener(v -> {
            boolean expanded = body.getVisibility() == View.VISIBLE;
            body.setVisibility(expanded ? View.GONE : View.VISIBLE);
            header.setText(expanded ? HEADER_COLLAPSED : HEADER_EXPANDED);
        });

        root.addView(header);
        root.addView(body);
        root.setTag(new Row(header, body));
        return root;
    }

    @Override
    public void bindView(View view, AssistantTurn.Part part, PartHost host) {
        Row row = view instanceof LinearLayout ? (Row) ((LinearLayout) view).getTag() : null;
        if (row == null) {
            return;
        }
        String text = part.text != null ? part.text : "";
        // 正文复用宿主 markdown 原语（后台渲染 + 链接 + 图片 + 主题），不在此自渲染。
        host.renderMarkdown(row.body, text);
    }

    @Override
    public void releaseView(View view) {
        // 无重资源；视图随容器移除即可。
    }

    /** 折叠行的两个子视图句柄，挂在 root 的 tag 上，bindView 复用同实例时取回。 */
    private static final class Row {
        final TextView header;
        final TextView body;
        Row(TextView header, TextView body) {
            this.header = header;
            this.body = body;
        }
    }

    private static final String HEADER_COLLAPSED = "深度思考";
    private static final String HEADER_EXPANDED = "收起思考";
}
