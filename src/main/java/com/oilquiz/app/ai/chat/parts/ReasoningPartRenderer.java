package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.turn.AssistantTurn;
import com.oilquiz.app.ai.chat.ui.SegmentScraper;

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
        header.setText(HEADER_PLAIN);

        final TextView body = new TextView(ctx);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        body.setPadding(pad, 0, pad, pad);
        body.setTextIsSelectable(true);
        body.setMovementMethod(LinkMovementMethod.getInstance());
        body.setVisibility(View.GONE); // 默认折叠

        final Row row = new Row(header, body);
        header.setOnClickListener(v -> {
            boolean expanded = body.getVisibility() == View.VISIBLE;
            if (!expanded) {
                // 自愈：折叠期间异步渲染结果可能因代次过期被丢弃（视图当时未 attach 等），
                // 展开这一刻若正文仍为空就重发一次，避免"点开是空的"。
                PartHost h = row.host;
                AssistantTurn.Part p = row.part;
                if (h != null && p != null && body.getText().length() == 0
                        && p.text != null && !p.text.isEmpty()) {
                    h.renderMarkdown(body, p.text);
                }
            }
            body.setVisibility(expanded ? View.GONE : View.VISIBLE);
            applyHeader(row, !expanded);
        });

        root.addView(header);
        root.addView(body);
        root.setTag(row);
        return root;
    }

    @Override
    public void bindView(View view, AssistantTurn.Part part, PartHost host) {
        Row row = view instanceof LinearLayout ? (Row) ((LinearLayout) view).getTag() : null;
        if (row == null) {
            return;
        }
        String text = part.text != null ? part.text : "";
        row.part = part;
        row.host = host;
        // 正文复用宿主 markdown 原语（后台渲染 + 链接 + 图片 + 主题），不在此自渲染。
        host.renderMarkdown(row.body, text);
        // 刮刀滑入（与顶部生成状态条同一个原语）：只在"思考新增了一整段"时播一次。
        // 逐 token 追加不播——2026-10-06 那次修的闪烁就是每片段重启动画（见 SegmentScraper 注释）。
        // 首次绑定也不播（sig 从 0 起，新段判据要求变大），入场交给 ChatAdapter 的逐块错峰。
        int signal = SegmentScraper.segmentSignal(text);
        if (SegmentScraper.isNewSegment(row.segmentSignal, signal)) {
            SegmentScraper.slideIn(row.header);
        }
        row.segmentSignal = signal;
        // header 用上接到的思考时长（"已深度思考 X 秒"），无时长则退回"深度思考"。
        row.baseLabel = part.durationMs > 0L
                ? ("已深度思考 " + Math.round(part.durationMs / 1000.0) + " 秒")
                : HEADER_PLAIN;
        applyHeader(row, row.body.getVisibility() == View.VISIBLE);
    }

    private static void applyHeader(Row row, boolean expanded) {
        row.header.setText((expanded ? "▾ " : "▸ ") + row.baseLabel);
    }

    @Override
    public void releaseView(View view) {
        // 无重资源；视图随容器移除即可。
    }

    /** 折叠行的两个子视图句柄 + 当前基础文案，挂在 root 的 tag 上，bindView 复用同实例时取回。 */
    private static final class Row {
        final TextView header;
        final TextView body;
        String baseLabel = HEADER_PLAIN;
        /** 上次播刮刀时的"换段信号"（= 思考文本的换行数）。随视图复用而保留。 */
        int segmentSignal;
        /** 最近一次绑定的 part/宿主，供展开时"正文为空就重发渲染"自愈用。 */
        AssistantTurn.Part part;
        PartHost host;
        Row(TextView header, TextView body) {
            this.header = header;
            this.body = body;
        }
    }

    private static final String HEADER_PLAIN = "深度思考";
}
